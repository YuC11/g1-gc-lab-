package com.example.gcdemo.gc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * JVM 与 G1 关键参数的只读快照。
 *
 * <p>{@code selectedFlags} 中的值是启动时实际生效的 flag，可能来自命令行、JDK
 * ergonomics 或 profile。{@code regionCount} 由 max heap / region size 估算，
 * {@code humongousThresholdBytes} 为 region/2，用于解释 H 对象阈值。</p>
 */
public record JvmInfo(
        String javaVersion,
        String javaVendor,
        String vmName,
        String vmVendor,
        long pid,
        Instant startTime,
        long uptimeMs,
        List<String> inputArguments,
        List<String> collectors,
        long maxHeapBytes,
        long regionSizeBytes,
        long regionCount,
        long humongousThresholdBytes,
        Map<String, String> selectedFlags
) {
}
