package com.example.gcdemo.gc;

import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.List;

/**
 * 基于 {@code MemoryMXBean} / {@code MemoryPoolMXBean} 的堆快照服务。
 *
 * <p>G1 的池名称在 HotSpot 中固定为 G1 Eden/Survivor/Old Gen；Metaspace 和
 * Compressed Class Space 用于观察类卸载。池的 max 可能是 -1，此时回退到 committed。</p>
 */
@Service
public class HeapService {

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();

    /**
     * 采样一次堆与关键池使用量。
     *
     * <p>返回的是管理 API 的瞬时值，不是原子快照；在 GC 并发进行时各池数字可能
     * 来自不同瞬间，足以用于教学展示，但不适合作为严格的事务性指标。</p>
     */
    public HeapSnapshot snapshot() {
        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();
        MemoryUsage eden = usage("G1 Eden Space");
        MemoryUsage survivor = usage("G1 Survivor Space");
        MemoryUsage old = usage("G1 Old Gen");
        MemoryUsage metaspace = usage("Metaspace");
        MemoryUsage compressedClass = usage("Compressed Class Space");

        long max = Math.max(heap.getMax(), Runtime.getRuntime().maxMemory());
        double percent = max > 0 ? (heap.getUsed() * 100.0d / max) : 0.0d;
        return new HeapSnapshot(
                Instant.now(),
                used(heap),
                committed(heap),
                max,
                Math.round(percent * 100.0d) / 100.0d,
                used(nonHeap),
                committed(nonHeap),
                used(eden),
                committed(eden),
                used(survivor),
                committed(survivor),
                used(old),
                committed(old),
                max(old),
                used(metaspace),
                committed(metaspace),
                used(compressedClass)
        );
    }

    /**
     * Promotion/Mixed 场景用来比较执行前后的 Old 增长。
     */
    public long oldUsed() {
        return used(usage("G1 Old Gen"));
    }

    /**
     * Class Unload 场景用来比较类元数据使用量；committed 不一定会同步下降。
     */
    public long metaspaceUsed() {
        return used(usage("Metaspace"));
    }

    private MemoryUsage usage(String name) {
        for (MemoryPoolMXBean pool : pools) {
            if (pool.getName().equals(name)) {
                return pool.getUsage();
            }
        }
        return null;
    }

    private long used(MemoryUsage usage) {
        if (usage == null || usage.getUsed() < 0) {
            return 0;
        }
        return usage.getUsed();
    }

    private long committed(MemoryUsage usage) {
        if (usage == null || usage.getCommitted() < 0) {
            return 0;
        }
        return usage.getCommitted();
    }

    private long max(MemoryUsage usage) {
        if (usage == null || usage.getMax() < 0) {
            return committed(usage);
        }
        return usage.getMax();
    }
}
