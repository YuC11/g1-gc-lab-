package com.example.gcdemo.gc;

import java.time.Instant;

/**
 * 日志尾读响应。
 *
 * @param requestedLines 请求的行数（已做 1..500 限制）
 * @param truncated      是否因为超过 512KB 而丢弃了文件开头
 * @param content        已经是文本，不会让前端执行 HTML
 */
public record LogTail(
        String name,
        long size,
        Instant modified,
        int requestedLines,
        boolean truncated,
        String content
) {
}
