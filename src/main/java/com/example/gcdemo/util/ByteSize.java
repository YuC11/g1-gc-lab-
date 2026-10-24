package com.example.gcdemo.util;

/**
 * 字节单位常量与显示辅助。
 *
 * <p>仅用于教学展示、参数缩放和 guard 检查；JVM 内部仍以字节为准。</p>
 */
public final class ByteSize {

    public static final long KB = 1024L;
    public static final long MB = 1024L * 1024L;
    public static final long GB = 1024L * 1024L * 1024L;

    private ByteSize() {
    }

    /**
     * 将可空的 MB 参数转换为字节；null 使用 fallback。
     */
    public static long parseMb(Integer value, long fallback) {
        return value == null ? fallback : value * MB;
    }

    /**
     * 将值夹在 [min, max]；调用方需保证 min <= max。
     */
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    /**
     * 人类可读的字节显示，仅用于 UI/日志，不参与计算。
     */
    public static String human(long bytes) {
        if (bytes >= GB) {
            return String.format("%.2f GB", bytes / (double) GB);
        }
        if (bytes >= MB) {
            return String.format("%.1f MB", bytes / (double) MB);
        }
        if (bytes >= KB) {
            return String.format("%.1f KB", bytes / (double) KB);
        }
        return bytes + " B";
    }
}
