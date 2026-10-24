package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * GC 日志文件的只读列举与安全 tail 服务。
 *
 * <p>安全约束：
 * <ul>
 *   <li>只允许 {@code logs/} 根目录下的 basename；</li>
 *   <li>只允许 {@code .log}；</li>
 *   <li>拒绝 {@code ..}、绝对路径和指向根目录外的符号链接；</li>
 *   <li>每次最多读取 512KB / 500 行，避免在线 tail 拖垮进程。</li>
 * </ul>
 * </p>
 */
@Service
public class LogFileService {

    private static final int MAX_LINES = 500;
    private static final int MAX_TAIL_BYTES = 512 * 1024;

    private final Path logDir;

    public LogFileService(DemoProperties properties) {
        this.logDir = Paths.get(properties.getLogDir()).toAbsolutePath().normalize();
    }

    /**
     * 列出日志目录中的 .log 与 .jfr 文件。
     *
     * <p>GC 日志优先排序，避免 safepoint 日志因为修改更晚而成为 UI 默认选中项；
     * .jfr 录制文件排在最后。只返回普通文件（不跟随符号链接）。</p>
     */
    public List<LogFileInfo> list() {
        if (!Files.isDirectory(logDir)) {
            return List.of();
        }
        List<LogFileInfo> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(logDir)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".log") || name.endsWith(".jfr");
                    })
                    .sorted(Comparator
                            .comparingInt((Path path) -> path.getFileName().toString().startsWith("gc-")
                                    ? 0 : path.getFileName().toString().endsWith(".jfr") ? 2 : 1)
                            .thenComparing(Comparator.comparingLong(this::lastModified).reversed()))
                    .limit(80)
                    .forEach(path -> result.add(new LogFileInfo(
                            path.getFileName().toString(),
                            size(path),
                            Instant.ofEpochMilli(lastModified(path)),
                            false
                    )));
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "无法读取日志目录: " + ex.getMessage());
        }
        if (!result.isEmpty()) {
            LogFileInfo first = result.getFirst();
            result.set(0, new LogFileInfo(first.name(), first.size(), first.modified(), true));
        }
        return result;
    }

    /**
     * 读取文件最后 N 行。
     *
     * <p>最多读取 512KB，并丢弃从中间截断出来的半行；行数上限 500。
     * 返回的内容始终以文本返回，前端只使用 {@code textContent}。</p>
     */
    public LogTail tail(String name, int requestedLines) {
        int lines = Math.max(1, Math.min(requestedLines, MAX_LINES));
        Path file = resolve(name);
        long size = size(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long start = Math.max(0, size - MAX_TAIL_BYTES);
            raf.seek(start);
            byte[] buffer = new byte[(int) (size - start)];
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            if (start > 0) {
                int firstNewline = text.indexOf('\n');
                text = firstNewline >= 0 ? text.substring(firstNewline + 1) : "";
            }
            String[] all = text.split("\n", -1);
            int end = all.length;
            if (end > 0 && all[end - 1].isEmpty()) {
                end--;
            }
            int from = Math.max(0, end - lines);
            StringBuilder content = new StringBuilder();
            for (int i = from; i < end; i++) {
                String line = all[i];
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                content.append(line).append('\n');
            }
            return new LogTail(
                    name,
                    size,
                    Instant.ofEpochMilli(lastModified(file)),
                    lines,
                    start > 0,
                    content.toString()
            );
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取日志失败: " + ex.getMessage());
        }
    }

    /**
     * 将外部传入的文件名解析为 logs 根目录内的真实文件（tail 用，仅 .log）。
     *
     * <p>这是本服务的安全边界：先拒绝路径分隔符和绝对路径，再用
     * {@code toRealPath()} 防止符号链接逃逸。任何不满足条件的情况都转换为
     * 400/404，而不是返回底层异常。</p>
     */
    private Path resolve(String name) {
        return resolve(name, ".log", "只允许读取 .log 文件");
    }

    /**
     * 下载用的解析：额外允许 .jfr 录制文件。
     */
    public Path resolveForDownload(String name) {
        return resolve(name, null, null);
    }

    private Path resolve(String name, String requiredSuffix, String suffixError) {
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "日志名称不合法");
        }
        if (requiredSuffix != null && !name.endsWith(requiredSuffix)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, suffixError);
        }
        if (!name.endsWith(".log") && !name.endsWith(".jfr")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "只允许读取 .log / .jfr 文件");
        }
        if (!Files.isDirectory(logDir)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "日志目录不存在，请使用 scripts/run.sh 启动应用");
        }
        try {
            Path root = logDir.toRealPath();
            Path candidate = root.resolve(name).normalize();
            if (!candidate.startsWith(root) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "日志文件不存在: " + name);
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "日志路径越界");
            }
            return real;
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.NOT_FOUND, "日志文件不可读: " + name);
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }
}
