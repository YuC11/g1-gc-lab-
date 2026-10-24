package com.example.gcdemo.gc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 GC 事件的不可变快照。
 *
 * <p>基础字段对应 JDK 21 unified GC log 的 pause 汇总行，例如：
 * {@code GC(12) Pause Young (Mixed) (G1 Evacuation Pause) 743M->29M(2048M) 3.209ms}。</p>
 *
 * <p>v2 新增字段（全部可空，JSON 向后兼容）来自同一日志的补充行，由
 * {@link GcCycleAggregator} 按 {@code GC(n)} 聚合附加：
 * <ul>
 *   <li>{@code regionTransitions}：info 级 {@code gc,heap} 行（Eden/Survivor/Old/Humongous
 *       regions X->Y(Z)）——Region 布局与年轻代回收的一手证据；</li>
 *   <li>{@code cpu}：info 级 {@code gc,cpu} 行（User/Sys/Real）——GC 并行度；</li>
 *   <li>{@code metaspace}：info 级 {@code gc,metaspace} 行——类元数据回收；</li>
 *   <li>{@code phases}：info 级 {@code gc,phases} 汇总（Pre/Evacuate/Post Evacuate
 *       Collection Set、Merge Heap Roots、Full GC Phase 1..4）——暂停内部结构；</li>
 *   <li>{@code details}：verbose 级深水区证据（自适应 IHOP、ergo 决策、tenuring 阈值、
 *       引用处理统计、Humongous Reclaimed、PLAB、卡表/refinement）。</li>
 * </ul>
 * 并发周期行（Concurrent Mark Cycle 等）作为独立事件追加，type 为 {@code Concurrent}，
 * 阶段名放在 cause 字段。</p>
 *
 * @param seq               应用内单调递增序号，用于前端增量拉取
 * @param gcId              JVM 日志中的 {@code GC(n)} 编号；并发阶段编号可能不连续
 * @param type              Young / Mixed / Prepare Mixed / Concurrent Start / Remark / Cleanup / Full / Concurrent
 * @param cause             触发原因（Concurrent 事件为其阶段名）
 * @param heapBeforeBytes   GC 前已用堆
 * @param heapAfterBytes    GC 后已用堆
 * @param heapCapacityBytes 日志括号中的当前堆容量
 * @param pauseMs           STW 暂停毫秒数（Concurrent 事件为阶段耗时）
 * @param uptimeSeconds     JVM 启动后的秒数
 * @param timestamp         由进程启动时间 + uptime 推导出的绝对时间
 * @param source            gc-log 或 jmx
 * @param rawLine           原始日志行，便于页面 tooltip 与排障
 */
public record GcEvent(
        long seq,
        long gcId,
        String type,
        String cause,
        long heapBeforeBytes,
        long heapAfterBytes,
        long heapCapacityBytes,
        double pauseMs,
        double uptimeSeconds,
        Instant timestamp,
        String source,
        String rawLine,
        RegionTransitions regionTransitions,
        CpuTimes cpu,
        MetaspaceChange metaspace,
        Map<String, Double> phases,
        Map<String, String> details
) {

    /**
     * info 级 {@code gc,heap} Region 转移行：本次 GC 前后各代 Region 数。
     *
     * @param edenTarget 日志中的目标值，如 {@code Eden regions: 75->0(199)} 的 199；无目标时为 -1
     */
    public record RegionTransitions(
            int edenBefore,
            int edenAfter,
            int edenTarget,
            int survivorBefore,
            int survivorAfter,
            int survivorTarget,
            int oldBefore,
            int oldAfter,
            int humongousBefore,
            int humongousAfter
    ) {
        public static RegionTransitions empty() {
            return new RegionTransitions(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
        }
    }

    /** info 级 {@code gc,cpu} 行：User/Sys/Real 秒数，User/Real 比值即 GC 并行度。 */
    public record CpuTimes(double userSeconds, double sysSeconds, double realSeconds) {
    }

    /** info 级 {@code gc,metaspace} 行：Metaspace used 前后变化与 committed。 */
    public record MetaspaceChange(long usedBeforeBytes, long usedAfterBytes, long committedAfterBytes) {
    }

    /** 基础 13 字段的构造入口（v2 扩展字段为 null）。 */
    public static GcEvent basic(long seq, long gcId, String type, String cause,
                                long heapBeforeBytes, long heapAfterBytes, long heapCapacityBytes,
                                double pauseMs, double uptimeSeconds, Instant timestamp,
                                String source, String rawLine) {
        return new GcEvent(seq, gcId, type, cause, heapBeforeBytes, heapAfterBytes,
                heapCapacityBytes, pauseMs, uptimeSeconds, timestamp, source, rawLine,
                null, null, null, null, null);
    }

    /** 聚合器附加补充行时的 seq 保留复制。 */
    public GcEvent withSeq(long newSeq) {
        return new GcEvent(newSeq, gcId, type, cause, heapBeforeBytes, heapAfterBytes,
                heapCapacityBytes, pauseMs, uptimeSeconds, timestamp, source, rawLine,
                regionTransitions, cpu, metaspace, phases, details);
    }

    public GcEvent withRegionLine(String kind, int before, int after, int target) {
        RegionTransitions current = regionTransitions == null ? RegionTransitions.empty() : regionTransitions;
        RegionTransitions updated = switch (kind) {
            case "Eden" -> new RegionTransitions(before, after, target,
                    current.survivorBefore(), current.survivorAfter(), current.survivorTarget(),
                    current.oldBefore(), current.oldAfter(),
                    current.humongousBefore(), current.humongousAfter());
            case "Survivor" -> new RegionTransitions(
                    current.edenBefore(), current.edenAfter(), current.edenTarget(),
                    before, after, target,
                    current.oldBefore(), current.oldAfter(),
                    current.humongousBefore(), current.humongousAfter());
            case "Old" -> new RegionTransitions(
                    current.edenBefore(), current.edenAfter(), current.edenTarget(),
                    current.survivorBefore(), current.survivorAfter(), current.survivorTarget(),
                    before, after,
                    current.humongousBefore(), current.humongousAfter());
            case "Humongous" -> new RegionTransitions(
                    current.edenBefore(), current.edenAfter(), current.edenTarget(),
                    current.survivorBefore(), current.survivorAfter(), current.survivorTarget(),
                    current.oldBefore(), current.oldAfter(),
                    before, after);
            default -> current;
        };
        return with(updated, cpu, metaspace, phases, details);
    }

    public GcEvent withCpu(CpuTimes value) {
        return with(regionTransitions, value, metaspace, phases, details);
    }

    public GcEvent withMetaspace(MetaspaceChange value) {
        return with(regionTransitions, cpu, value, phases, details);
    }

    public GcEvent withPhase(String name, double ms) {
        Map<String, Double> merged = phases == null ? new LinkedHashMap<>() : new LinkedHashMap<>(phases);
        merged.put(name, ms);
        return with(regionTransitions, cpu, metaspace, Map.copyOf(merged), details);
    }

    public GcEvent withDetail(String key, String value) {
        Map<String, String> merged = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        merged.put(key, value);
        return with(regionTransitions, cpu, metaspace, phases, Map.copyOf(merged));
    }

    private GcEvent with(RegionTransitions regions, CpuTimes cpuTimes, MetaspaceChange meta,
                         Map<String, Double> phaseMap, Map<String, String> detailMap) {
        return new GcEvent(seq, gcId, type, cause, heapBeforeBytes, heapAfterBytes,
                heapCapacityBytes, pauseMs, uptimeSeconds, timestamp, source, rawLine,
                regions, cpuTimes, meta, phaseMap, detailMap);
    }
}
