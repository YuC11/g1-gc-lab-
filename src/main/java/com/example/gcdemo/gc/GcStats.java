package com.example.gcdemo.gc;

import java.util.List;

/**
 * 某一时刻的 GC 汇总快照，供状态页和场景结果使用。
 *
 * @param collectors             JVM 中的 collector MXBean 原始计数
 * @param totalCollections       所有 collector 次数之和
 * @param totalCollectionTimeMs  所有 collector 累计停顿
 * @param gcOverheadPercent      GC 累计时间 / JVM uptime
 * @param youngCollections       G1 Young Generation 次数（包含 Mixed 的年轻代暂停）
 * @param fullCollections        G1 Old Generation / Full 次数
 * @param lastEvent              最近一条日志解析事件
 */
public record GcStats(
        List<CollectorStats> collectors,
        long totalCollections,
        long totalCollectionTimeMs,
        double gcOverheadPercent,
        long youngCollections,
        long fullCollections,
        GcEvent lastEvent
) {
    public record CollectorStats(String name, long collections, long collectionTimeMs) {
    }
}
