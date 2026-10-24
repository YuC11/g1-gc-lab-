package com.example.gcdemo.gc;

import java.time.Instant;

/**
 * 堆与关键内存池的瞬时快照。
 *
 * <p>Eden/Survivor/Old 的 used/committed 用于解释 G1 当前处于分配、晋升还是
 * 老年代压力阶段；Metaspace 用于配合类卸载实验。数值来自管理 API，不是 Region
 * 级精确统计，不能替代 GC 日志中的 region counts。</p>
 */
public record HeapSnapshot(
        Instant timestamp,
        long heapUsed,
        long heapCommitted,
        long heapMax,
        double heapUsedPercent,
        long nonHeapUsed,
        long nonHeapCommitted,
        long edenUsed,
        long edenCommitted,
        long survivorUsed,
        long survivorCommitted,
        long oldUsed,
        long oldCommitted,
        long oldMax,
        long metaspaceUsed,
        long metaspaceCommitted,
        long compressedClassUsed
) {
}
