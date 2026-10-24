package com.example.gcdemo.gc;

import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * 从 {@code GarbageCollectorMXBean} 读取 G1 的累计计数。
 *
 * <p>MXBean 适合做低成本的总体趋势（Young/Full 次数、累计停顿），但不能区分
 * Normal 与 Mixed；具体暂停类型仍以 GC 日志为准。这里刻意不把 MXBean 的
 * Young 计数误称为“普通 Young GC”。</p>
 */
@Service
public class GcStatsService {

    private final GcEventStore eventStore;

    public GcStatsService(GcEventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * 读取所有 collector 的累计数据。
     *
     * <p>G1 的 Young collector 计数包含普通 Young 和 Mixed 的年轻代暂停；
     * 本方法保留原始 collector 名称，避免把该计数误当成 Normal Young 专属。</p>
     */
    public GcStats snapshot() {
        List<GcStats.CollectorStats> collectors = new ArrayList<>();
        long totalCollections = 0;
        long totalTime = 0;
        long young = 0;
        long full = 0;

        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0, bean.getCollectionCount());
            long time = Math.max(0, bean.getCollectionTime());
            collectors.add(new GcStats.CollectorStats(bean.getName(), count, time));
            totalCollections += count;
            totalTime += time;
            String name = bean.getName().toLowerCase();
            if (name.contains("young") || name.contains("scavenge")) {
                young += count;
            }
            if (name.contains("old") || name.contains("full") || name.contains("mark sweep")) {
                full += count;
            }
        }

        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        double overhead = uptime > 0 ? (totalTime * 100.0d / uptime) : 0.0d;
        return new GcStats(
                List.copyOf(collectors),
                totalCollections,
                totalTime,
                Math.round(overhead * 100.0d) / 100.0d,
                young,
                full,
                eventStore.latest()
        );
    }

    /**
     * 用于场景执行前后的 GC 次数差值判断；O(collector 数)，非常轻量。
     */
    public long totalCollections() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, bean.getCollectionCount());
        }
        return total;
    }
}
