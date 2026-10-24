package com.example.gcdemo.gc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 按 {@code GC(n)} 把“GC 块的多行输出”聚合成一个富事件。
 *
 * <p><b>关键事实（以真实 JDK 21 日志为准）</b>：一个 GC 块的行序是——
 * {@code gc,start} 起始行、worker 行、{@code gc,phases} 阶段分解、
 * {@code gc,heap} Region 转移、Metaspace 行，<b>然后才是 pause 汇总行</b>，
 * 汇总行之后偶有 {@code gc,cpu} 行。因此补充行常常先于汇总行到达：</p>
 *
 * <p>聚合策略：维护 pending 事件与 orphan 缓冲——
 * <ul>
 *   <li>{@code EventDetail} 先尝试附加到同 gcId 的 pending；没有匹配 pending 时
 *       暂存到 orphan 缓冲（gcId → 待应用操作），等该 GC 的汇总行到达时回填；</li>
 *   <li>{@code PauseEvent}（汇总行）到达：定稿上一个 pending，从 orphan 取出本
 *       gcId 的操作依次应用后设为新 pending；</li>
 *   <li>下一个 GC 的汇总行、文件轮转、截断或日志安静（watcher 两轮 quiet poll）
 *       时定稿发布；</li>
 *   <li>并发周期行（Concurrent Mark Cycle 等）作为独立事件直接放行。</li>
 * </ul>
 * orphan 缓冲有容量上限，防御性日志乱序时不会无限增长。</p>
 *
 * <p>非线程安全——由 {@link GcLogWatcher} 在 synchronized poll 内单线程调用。</p>
 */
public final class GcCycleAggregator {

    private static final int MAX_ORPHAN_GC_IDS = 16;
    private static final int MAX_ORPHAN_APPLIERS_PER_GC = 64;

    private GcEvent pending;

    /** 先于汇总行到达的补充行：gcId → 依次待应用的富化操作。 */
    private final Map<Long, Deque<UnaryOperator<GcEvent>>> orphanDetails = new LinkedHashMap<>();

    /**
     * @return 本次输入产生、应当立即追加到事件存储的事件（通常为空）
     */
    public List<GcEvent> offer(ParsedLine parsed) {
        if (parsed instanceof ParsedLine.PauseEvent pause) {
            List<GcEvent> finalized = flushIntoList();
            GcEvent event = pause.event();
            for (UnaryOperator<GcEvent> applier : takeOrphans(event.gcId())) {
                event = applier.apply(event);
            }
            pending = event;
            return finalized;
        }
        if (parsed instanceof ParsedLine.EventDetail detail) {
            if (pending != null && pending.gcId() == detail.gcId()) {
                pending = detail.applier().apply(pending);
            } else {
                Deque<UnaryOperator<GcEvent>> queue = orphanDetails
                        .computeIfAbsent(detail.gcId(), key -> new ArrayDeque<>());
                if (queue.size() < MAX_ORPHAN_APPLIERS_PER_GC) {
                    queue.addLast(detail.applier());
                }
                while (orphanDetails.size() > MAX_ORPHAN_GC_IDS) {
                    Long oldest = orphanDetails.keySet().iterator().next();
                    orphanDetails.remove(oldest);
                }
            }
            return List.of();
        }
        if (parsed instanceof ParsedLine.StandaloneEvent standalone) {
            return List.of(standalone.event());
        }
        return List.of();
    }

    /**
     * 定稿 pending（文件轮转、截断、日志安静或关闭时调用）。
     */
    public List<GcEvent> flush() {
        orphanDetails.clear();
        return flushIntoList();
    }

    /**
     * @return 是否存在尚未定稿的事件（用于“日志已安静但最后一个 GC 还没发布”的场景）
     */
    public boolean hasPending() {
        return pending != null;
    }

    private List<UnaryOperator<GcEvent>> takeOrphans(long gcId) {
        Deque<UnaryOperator<GcEvent>> queue = orphanDetails.remove(gcId);
        return queue == null ? List.of() : new ArrayList<>(queue);
    }

    private List<GcEvent> flushIntoList() {
        if (pending == null) {
            return List.of();
        }
        GcEvent finalized = pending;
        pending = null;
        List<GcEvent> out = new ArrayList<>(1);
        out.add(finalized);
        return out;
    }
}
