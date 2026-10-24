package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.util.ByteSize;
import jdk.management.jfr.FlightRecorderMXBean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 基于 JDK 内置 {@code FlightRecorderMXBean} 的 JFR 录制服务，零外部依赖。
 *
 * <p>docs/06 教了 JFR 但旧版本只能去命令行敲 jcmd；本服务把“开始录制/停止落盘/
 * 下载”搬进控制台。录制文件写入 logDir，可用 JDK Mission Control 打开。</p>
 *
 * <p>安全边界：同一时刻只允许一个 lab 录制；时长默认 60s、上限
 * {@code demo.max-jfr-seconds}（duration 到期 JVM 自动停止并写文件）；应用关闭时
 * 兜底停止，不留悬挂录制。</p>
 */
@Service
public class JfrService {

    private static final Logger log = LoggerFactory.getLogger(JfrService.class);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault());

    private final DemoProperties properties;
    private final FlightRecorderMXBean flightRecorder;
    private final Path logDir;

    /** 当前 lab 录制的 JVM recording id；-1 表示无。 */
    private long activeId = -1;
    private Instant startedAt;
    private int plannedSeconds;
    private Path targetFile;
    private String lastFile;

    public JfrService(DemoProperties properties) {
        this.properties = properties;
        this.logDir = Paths.get(properties.getLogDir()).toAbsolutePath().normalize();
        FlightRecorderMXBean bean = null;
        try {
            bean = ManagementFactory.getPlatformMXBean(FlightRecorderMXBean.class);
        } catch (RuntimeException ex) {
            log.warn("FlightRecorderMXBean unavailable, JFR endpoints disabled: {}", ex.getMessage());
        }
        this.flightRecorder = bean;
    }

    /**
     * 开始一次录制（profile 配置，含 GC 事件）；已有录制运行中返回 409。
     */
    public synchronized JfrStatus start(Integer requestedSeconds) {
        requireAvailable();
        reapIfFinished();
        if (activeId >= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "已有 JFR 录制正在运行",
                    "等待它结束（duration 到期自动落盘）或先调用 /api/jfr/stop。");
        }
        int max = properties.getMaxJfrSeconds();
        int seconds = requestedSeconds == null ? 60 : Math.max(1, Math.min(requestedSeconds, max));
        try {
            Files.createDirectories(logDir);
            Path file = logDir.resolve("jfr-" + FILE_STAMP.format(Instant.now()) + ".jfr");
            long id = flightRecorder.newRecording();
            flightRecorder.setPredefinedConfiguration(id, "profile");
            // 注意 MXBean 的选项名是 destination（写文件目标），不是 jcmd 的 filename。
            flightRecorder.setRecordingOptions(id, Map.of(
                    "name", "g1-lab",
                    "destination", file.toString(),
                    "duration", seconds + " s",
                    "disk", "true",
                    "maxSize", String.valueOf(128L * 1024 * 1024)));
            flightRecorder.startRecording(id);
            activeId = id;
            startedAt = Instant.now();
            plannedSeconds = seconds;
            targetFile = file;
            return status();
        } catch (IOException | RuntimeException ex) {
            closeQuietly(activeId);
            activeId = -1;
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "JFR 录制启动失败: " + ex.getMessage());
        }
    }

    /**
     * 停止当前录制；JVM 会把数据写入启动时指定的 filename。
     */
    public synchronized JfrStatus stop() {
        requireAvailable();
        if (activeId < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "当前没有运行中的 JFR 录制");
        }
        long id = activeId;
        activeId = -1;
        try {
            flightRecorder.stopRecording(id);
            lastFile = targetFile == null ? null : targetFile.getFileName().toString();
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "JFR 停止失败: " + ex.getMessage());
        } finally {
            closeQuietly(id);
        }
        return status();
    }

    /**
     * 录制状态；如果 duration 已到期，顺手收割（关闭 recording、登记文件）。
     */
    public synchronized JfrStatus status() {
        if (flightRecorder == null) {
            return new JfrStatus(false, false, null, null, null, null, 0);
        }
        reapIfFinished();
        boolean active = activeId >= 0;
        Long lastSize = null;
        if (lastFile != null) {
            Path file = logDir.resolve(lastFile);
            try {
                lastSize = Files.isRegularFile(file) ? Files.size(file) : null;
            } catch (IOException ignored) {
                // 状态页不因 IO 抖动失败
            }
        }
        return new JfrStatus(
                active,
                true,
                active ? startedAt : null,
                active ? plannedSeconds : null,
                lastFile,
                lastSize,
                active ? Math.max(0, plannedSeconds - (Instant.now().toEpochMilli() - startedAt.toEpochMilli()) / 1000) : 0);
    }

    /** duration 到期后 JVM 自动停止；这里检测并收割，避免悬挂 recording。 */
    private void reapIfFinished() {
        if (activeId < 0) {
            return;
        }
        boolean stillRunning;
        try {
            stillRunning = flightRecorder.getRecordings().stream()
                    .anyMatch(info -> info.getId() == activeId && "RUNNING".equals(info.getState()));
        } catch (RuntimeException ex) {
            // 状态查询失败时保持现状，下一轮再收割
            return;
        }
        if (!stillRunning) {
            long id = activeId;
            activeId = -1;
            lastFile = targetFile == null ? null : targetFile.getFileName().toString();
            closeQuietly(id);
        }
    }

    @PreDestroy
    void shutdown() {
        if (activeId >= 0) {
            long id = activeId;
            activeId = -1;
            try {
                flightRecorder.stopRecording(id);
                lastFile = targetFile == null ? null : targetFile.getFileName().toString();
            } catch (RuntimeException ignored) {
                // Best effort during shutdown.
            }
            closeQuietly(id);
        }
    }

    private void requireAvailable() {
        if (flightRecorder == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "当前 JVM 不支持 JFR（FlightRecorderMXBean 不可用）");
        }
    }

    private void closeQuietly(long id) {
        if (id < 0) {
            return;
        }
        try {
            flightRecorder.closeRecording(id);
        } catch (IOException | RuntimeException ignored) {
            // Recording may already be closed by duration expiry.
        }
    }

    /**
     * @param active            是否有 lab 录制运行中
     * @param available         JVM 是否支持 JFR
     * @param startedAt         录制开始时间（仅 active 时）
     * @param plannedSeconds    计划时长秒（仅 active 时）
     * @param lastFile          最近一次落盘文件名（logs/ 下 basename）
     * @param lastFileSizeBytes 最近文件大小；文件不存在时为 null
     * @param remainingSeconds  剩余秒（仅 active 时）
     */
    public record JfrStatus(
            boolean active,
            boolean available,
            Instant startedAt,
            Integer plannedSeconds,
            String lastFile,
            Long lastFileSizeBytes,
            long remainingSeconds
    ) {
        public String lastFileSizeHuman() {
            return lastFileSizeBytes == null ? null : ByteSize.human(lastFileSizeBytes);
        }
    }
}
