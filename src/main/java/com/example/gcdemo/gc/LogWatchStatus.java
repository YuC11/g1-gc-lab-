package com.example.gcdemo.gc;

/**
 * GC 日志监听器的健康状态。
 *
 * @param available        是否已经绑定到存在的 GC 日志
 * @param bytesRead        本次启动累计读取字节
 * @param parsedEvents     成功解析的 pause 事件数
 * @param linesRead        本次启动累计读取的完整日志行数
 * @param pauseLinesSkipped 形似 pause 汇总行但解析失败的次数；持续增长说明日志格式漂移
 * @param error            最近一次读取/解析错误；null 表示正常
 */
public record LogWatchStatus(
        boolean available,
        String file,
        long bytesRead,
        int parsedEvents,
        long linesRead,
        long pauseLinesSkipped,
        String error
) {
}
