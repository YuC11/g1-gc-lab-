package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 侧日志证据源：{@code stringdedup-*.log} 与 {@code class-unload-*.log}。
 *
 * <p>GC 主日志（gc-*.log）由 {@link GcLogWatcher} 负责；这两个日志由独立的
 * {@code -Xlog:...} 输出，携带主日志没有的一手证据：
 * <ul>
 *   <li>去重统计行（new/deduped/avg%），string-dedup 场景不再让学生“自己去翻文件”；</li>
 *   <li>{@code unloading class} 行计数，class-unload 场景返回真实卸载数。</li>
 * </ul>
 * 实现与主 watcher 同一套模式：绑定最新文件、offset 增量读、半行缓冲、轮转/截断
 * 处理；两个文件互不干扰，解析失败只影响各自的统计。</p>
 */
@Component
public class SideLogWatchers {

    private static final Logger log = LoggerFactory.getLogger(SideLogWatchers.class);
    private static final long MAX_READ_BYTES = 256 * 1024;
    private static final int DEDUP_HISTORY = 64;

    /**
     * 例：Concurrent String Deduplication 15139/1082.0K (new), 13765/884.2K (deduped),
     * avg 81.7%, 16.309ms of 16.552ms
     */
    private static final Pattern DEDUP = Pattern.compile(
            "\\[[^\\]]+]\\[(\\d+(?:\\.\\d+)?)s]\\[[^\\]]*]\\[[^\\]]*].*?"
                    + "Concurrent String Deduplication "
                    + "(\\d+)/([\\d.]+[KMG]?) \\(new\\), (\\d+)/([\\d.]+[KMG]?) \\(deduped\\), "
                    + "avg ([\\d.]+)%, ([\\d.]+)ms");

    private static final Pattern UNLOADING = Pattern.compile("\\bunloading class (\\S+)");

    private final DemoProperties properties;
    private final Path logDir;
    private final PathMatcher dedupMatcher;
    private final PathMatcher unloadMatcher;

    private final Deque<StringDedupStats> dedupHistory = new ArrayDeque<>();
    private final StringBuilder dedupPending = new StringBuilder();
    private Path dedupFile;
    private long dedupOffset;
    private boolean dedupAttached;

    private final StringBuilder unloadPending = new StringBuilder();
    private Path unloadFile;
    private long unloadOffset;
    private boolean unloadAttached;
    private long unloadedTotal;
    private Instant lastUnloadAt;

    public SideLogWatchers(DemoProperties properties) {
        this.properties = properties;
        this.logDir = Paths.get(properties.getLogDir()).toAbsolutePath().normalize();
        this.dedupMatcher = FileSystems.getDefault().getPathMatcher("glob:stringdedup-*.log");
        this.unloadMatcher = FileSystems.getDefault().getPathMatcher("glob:class-unload-*.log");
    }

    /**
     * 定时推进两条侧日志；也可由场景线程直接调用以立即消费。
     */
    @Scheduled(fixedDelayString = "${demo.poll-interval-ms:500}")
    public synchronized void poll() {
        try {
            pollDedup();
        } catch (IOException | RuntimeException ex) {
            log.warn("stringdedup poll failed: {}", ex.toString());
        }
        try {
            pollUnload();
        } catch (IOException | RuntimeException ex) {
            log.warn("class-unload poll failed: {}", ex.toString());
        }
    }

    /**
     * @return 指定时间之后最新的一条去重统计；场景用它拿“本实验产生”的一手证据
     */
    public synchronized Optional<StringDedupStats> dedupSince(Instant from) {
        StringDedupStats newest = null;
        for (StringDedupStats stats : dedupHistory) {
            if (!stats.timestamp().isBefore(from) && (newest == null || stats.timestamp().isAfter(newest.timestamp()))) {
                newest = stats;
            }
        }
        return Optional.ofNullable(newest);
    }

    /**
     * 场景取证用的水位匹配：取 watermark 之后新增的统计里 dedupedCount 最大的一条。
     *
     * <p>时间戳匹配（{@link #dedupSince}）依赖 processStart+uptime 与 Instant.now() 的
     * 时钟一致性，在长会话中偶发失配；水位匹配只依赖“场景开始后新出现的行”，
     * 对“本批字符串”的特征（deduped 数量大）更鲁棒。watermark 取场景开始时的
     * {@link #dedupStats()} 的 size()。</p>
     */
    public synchronized Optional<StringDedupStats> bestDedupAfter(int watermark, long minDedupedCount) {
        List<StringDedupStats> all = dedupStats();
        int fresh = Math.max(0, all.size() - watermark);
        return all.stream()
                .limit(fresh)
                .filter(stats -> stats.dedupedCount() >= minDedupedCount)
                .max(java.util.Comparator.comparingLong(StringDedupStats::dedupedCount));
    }

    /** @return 本进程观测到的全部去重统计，最新在前 */
    public synchronized List<StringDedupStats> dedupStats() {
        return dedupHistory.stream()
                .sorted(Comparator.comparing(StringDedupStats::timestamp).reversed())
                .toList();
    }

    /** @return 本进程观测到的 unloading class 行总数 */
    public synchronized long unloadedTotal() {
        return unloadedTotal;
    }

    public synchronized Instant lastUnloadAt() {
        return lastUnloadAt;
    }

    private void pollDedup() throws IOException {
        Optional<Path> latest = latest(dedupMatcher);
        if (latest.isEmpty()) {
            return;
        }
        Path file = latest.get();
        if (!file.equals(dedupFile)) {
            dedupFile = file;
            dedupPending.setLength(0);
            dedupOffset = dedupAttached ? 0 : Files.size(file);
            dedupAttached = true;
        }
        byte[] chunk = readIncrement(file, dedupOffset);
        if (chunk == null) {
            dedupOffset = 0;
            dedupPending.setLength(0);
            return;
        }
        dedupOffset += chunk.length;
        dedupPending.append(new String(chunk, StandardCharsets.UTF_8));
        int newline;
        while ((newline = dedupPending.indexOf("\n")) >= 0) {
            String line = dedupPending.substring(0, newline);
            dedupPending.delete(0, newline + 1);
            Matcher matcher = DEDUP.matcher(line);
            if (matcher.find()) {
                StringDedupStats stats = new StringDedupStats(
                        Long.parseLong(matcher.group(2)),
                        matcher.group(3),
                        Long.parseLong(matcher.group(4)),
                        matcher.group(5),
                        Double.parseDouble(matcher.group(6)),
                        Double.parseDouble(matcher.group(7)),
                        processStart().plusMillis((long) (Double.parseDouble(matcher.group(1)) * 1000L)));
                synchronized (this) {
                    dedupHistory.addLast(stats);
                    while (dedupHistory.size() > DEDUP_HISTORY) {
                        dedupHistory.removeFirst();
                    }
                }
            }
        }
    }

    private void pollUnload() throws IOException {
        Optional<Path> latest = latest(unloadMatcher);
        if (latest.isEmpty()) {
            return;
        }
        Path file = latest.get();
        if (!file.equals(unloadFile)) {
            unloadFile = file;
            unloadPending.setLength(0);
            unloadOffset = unloadAttached ? 0 : Files.size(file);
            unloadAttached = true;
        }
        byte[] chunk = readIncrement(file, unloadOffset);
        if (chunk == null) {
            unloadOffset = 0;
            unloadPending.setLength(0);
            return;
        }
        unloadOffset += chunk.length;
        unloadPending.append(new String(chunk, StandardCharsets.UTF_8));
        int newline;
        while ((newline = unloadPending.indexOf("\n")) >= 0) {
            String line = unloadPending.substring(0, newline);
            unloadPending.delete(0, newline + 1);
            if (UNLOADING.matcher(line).find()) {
                synchronized (this) {
                    unloadedTotal++;
                    lastUnloadAt = Instant.now();
                }
            }
        }
    }

    private Optional<Path> latest(PathMatcher matcher) throws IOException {
        if (!Files.isDirectory(logDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(logDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(path.getFileName()))
                    .max(Comparator.comparingLong(this::lastModified));
        }
    }

    /**
     * @return 新增字节；文件截断时重置 offset 并返回 null（调用方下一轮重读）
     */
    private byte[] readIncrement(Path file, long currentOffset) throws IOException {
        long size = Files.size(file);
        if (size < currentOffset) {
            // 截断：调用方语义拆分太繁琐，这里直接把两种状态都清零。
            return null;
        }
        if (size == currentOffset) {
            return new byte[0];
        }
        int toRead = (int) Math.min(size - currentOffset, MAX_READ_BYTES);
        byte[] buffer = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(currentOffset);
            raf.readFully(buffer);
        }
        return buffer;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private Instant processStart() {
        return Instant.ofEpochMilli(java.lang.management.ManagementFactory
                .getRuntimeMXBean().getStartTime());
    }

    /**
     * 一轮去重统计。count 是字符串个数，bytesLabel 是 JDK 原样输出的体积标签。
     */
    public record StringDedupStats(
            long newCount,
            String newBytesLabel,
            long dedupedCount,
            String dedupedBytesLabel,
            double avgDedupedPercent,
            double durationMs,
            Instant timestamp
    ) {
    }
}
