package com.example.gcdemo.gc;

import java.time.Instant;

/**
 * GC 日志文件列表项。
 *
 * @param active 当前 UI 默认选择的日志；GC 文件优先于 safepoint/class 日志
 */
public record LogFileInfo(
        String name,
        long size,
        Instant modified,
        boolean active
) {
}
