package com.example.gcdemo.gc;

import com.sun.management.GarbageCollectionNotificationInfo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 没有 GC 日志文件时的 JMX GC 通知兜底。
 *
 * <p>通过 {@code NotificationEmitter} 监听 G1 的 GC notification。它无需解析文件，
 * 但只能把 collector 粗略映射为 Young/Concurrent/Full，无法可靠区分 Mixed。
 * 因此日志可用时优先使用 {@link GcLogWatcher}，本类不再写入事件，避免重复。</p>
 */
@Component
public class JmxGcFallback {

    private static final Logger log = LoggerFactory.getLogger(JmxGcFallback.class);

    private final GcEventStore eventStore;
    private final GcLogWatcher logWatcher;
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener = this::onNotification;

    /**
     * heap 池名称集合（G1: Eden/Survivor/Old Gen）。
     *
     * <p>{@code getMemoryUsageBeforeGc()} 返回的是<b>所有</b>内存池（含 Metaspace 等
     * non-heap 池）；不加过滤会把 non-heap 也算进 heapBefore/After，与日志解析路径
     * 的“堆 used”语义不一致。</p>
     */
    private static final Set<String> HEAP_POOL_NAMES = ManagementFactory.getMemoryPoolMXBeans()
            .stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .map(MemoryPoolMXBean::getName)
            .collect(Collectors.toUnmodifiableSet());

    public JmxGcFallback(GcEventStore eventStore, GcLogWatcher logWatcher) {
        this.eventStore = eventStore;
        this.logWatcher = logWatcher;
    }

    /**
     * 注册 JMX listener。
     *
     * <p>某些 JVM/collector 的 MXBean 不实现 NotificationEmitter；这里只注册支持的
     * bean，不允许一个不支持的 bean 阻止应用启动。</p>
     */
    @PostConstruct
    void register() {
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(listener, null, null);
                emitters.add(emitter);
            }
        }
        log.info("Registered JMX GC notification fallback on {} collector(s)", emitters.size());
    }

    @PreDestroy
    void unregister() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (Exception ignored) {
                // Best effort during shutdown.
            }
        }
    }

    /**
     * 将 JMX GC notification 转换为统一事件。
     *
     * <p>日志可用时直接丢弃，防止与 GcLogWatcher 双写。Cause 和前后堆来自
     * {@code GcInfo}，但 Mixed 无法可靠识别，这是 fallback 的固有局限。</p>
     */
    private void onNotification(javax.management.Notification notification, Object handback) {
        if (logWatcher.available()) {
            return;
        }
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
            return;
        }
        if (!(notification.getUserData() instanceof CompositeData data)) {
            return;
        }
        try {
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from(data);
            long before = heapUsed(info.getGcInfo().getMemoryUsageBeforeGc());
            long after = heapUsed(info.getGcInfo().getMemoryUsageAfterGc());
            String collector = info.getGcName().toLowerCase();
            String type = collector.contains("young") ? "Young"
                    : collector.contains("concurrent") ? "Concurrent" : "Full";
            long maxHeap = Runtime.getRuntime().maxMemory();
            Instant timestamp = Instant.ofEpochMilli(notification.getTimeStamp());
            double uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0d;
            eventStore.append(GcEvent.basic(
                    0,
                    -1,
                    type,
                    info.getGcCause() == null ? "" : info.getGcCause(),
                    before,
                    after,
                    maxHeap,
                    info.getGcInfo().getDuration(),
                    uptime,
                    timestamp,
                    "jmx",
                    info.getGcName() + " " + info.getGcAction()
            ));
        } catch (RuntimeException ex) {
            log.debug("Could not decode JMX GC notification", ex);
        }
    }

    /**
     * 只累加 heap 池的 used；non-heap 池（Metaspace 等）不计入。
     */
    static long heapUsed(Map<String, java.lang.management.MemoryUsage> usages) {
        long total = 0;
        for (Map.Entry<String, java.lang.management.MemoryUsage> entry : usages.entrySet()) {
            java.lang.management.MemoryUsage usage = entry.getValue();
            if (HEAP_POOL_NAMES.contains(entry.getKey()) && usage != null && usage.getUsed() > 0) {
                total += usage.getUsed();
            }
        }
        return total;
    }
}
