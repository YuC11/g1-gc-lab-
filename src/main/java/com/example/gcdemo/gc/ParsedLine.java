package com.example.gcdemo.gc;

import java.util.function.UnaryOperator;

/**
 * 一行 unified GC log 解析后的归类结果，供 {@link GcCycleAggregator} 消费。
 */
public sealed interface ParsedLine {

    /**
     * pause 汇总行：创建一个新的 GC 事件（同时定稿上一个 pending 事件）。
     */
    record PauseEvent(GcEvent event) implements ParsedLine {
    }

    /**
     * 属于某个 {@code GC(n)} 的补充行（region/cpu/metaspace/phases/verbose 细节）：
     * 附加到该 GC 的 pending 事件上；没有匹配的 pending 时丢弃。
     */
    record EventDetail(long gcId, UnaryOperator<GcEvent> applier) implements ParsedLine {
    }

    /**
     * 独立事件（并发周期各阶段）：直接追加，不参与聚合。
     */
    record StandaloneEvent(GcEvent event) implements ParsedLine {
    }
}
