package com.example.gcdemo.gc;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.springframework.stereotype.Service;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取 JVM 身份、运行参数和 G1 关键 flag。
 *
 * <p>优先使用 {@code HotSpotDiagnosticMXBean}，因为它反映实际生效值；JDK 21 中
 * {@code G1NewSizePercent}、{@code G1MixedGCLiveThresholdPercent} 等参数是
 * experimental，未加 {@code -XX:+UnlockExperimentalVMOptions} 时可能读取失败，
 * 因此这里对每个 flag 做隔离降级，而不是让整个状态接口失败。</p>
 */
@Service
public class JvmInfoService {

    private static final List<String> TRACKED_FLAGS = List.of(
            "UseG1GC",
            "G1HeapRegionSize",
            "MaxGCPauseMillis",
            "InitiatingHeapOccupancyPercent",
            "G1UseAdaptiveIHOP",
            "G1NewSizePercent",
            "G1MaxNewSizePercent",
            "G1MixedGCLiveThresholdPercent",
            "G1HeapWastePercent",
            "G1MixedGCCountTarget",
            "G1OldCSetRegionThresholdPercent",
            "G1ReservePercent",
            "G1PeriodicGCInterval",
            "G1PeriodicGCInvokesConcurrent",
            "G1PeriodicGCSystemLoadThreshold",
            "ParallelGCThreads",
            "ConcGCThreads",
            "G1ConcRefinementThreads",
            "UseDynamicNumberOfGCThreads",
            "G1RSetUpdatingPauseTimePercent",
            "G1SATBBufferSize",
            "G1UpdateBufferSize",
            "UseStringDeduplication",
            "StringDeduplicationAgeThreshold",
            "ClassUnloading",
            "ClassUnloadingWithConcurrentMark",
            "ParallelRefProcEnabled",
            "ExplicitGCInvokesConcurrent",
            "DisableExplicitGC",
            "MaxTenuringThreshold",
            "InitialTenuringThreshold",
            "GCTimeRatio"
    );

    private final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
    private final HotSpotDiagnosticMXBean hotSpotMXBean =
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    /**
     * 静态部分缓存：VM flag、region 推导、JVM 身份在启动后不变，而一次
     * {@code GET /api/scenarios} 会为 10 个场景各推导一遍默认值（每个 32 个
     * getVMOption MXBean 调用），不缓存会产生 300+ 次重复调用。uptime 单独取新值。
     */
    private volatile JvmInfo cached;

    /**
     * 构造一份 JVM/G1 参数快照（静态部分缓存，uptime 实时）。
     *
     * <p>region count 和 Humongous 阈值都是推导值；如果读取不到
     * {@code G1HeapRegionSize}，返回 0 而不是抛出异常，保证状态页仍可用。</p>
     */
    public JvmInfo info() {
        JvmInfo snapshot = cached;
        if (snapshot == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = buildInfo();
                }
                snapshot = cached;
            }
        }
        return new JvmInfo(
                snapshot.javaVersion(),
                snapshot.javaVendor(),
                snapshot.vmName(),
                snapshot.vmVendor(),
                snapshot.pid(),
                snapshot.startTime(),
                runtimeMXBean.getUptime(),
                snapshot.inputArguments(),
                snapshot.collectors(),
                snapshot.maxHeapBytes(),
                snapshot.regionSizeBytes(),
                snapshot.regionCount(),
                snapshot.humongousThresholdBytes(),
                snapshot.selectedFlags()
        );
    }

    /**
     * 丢弃缓存（测试或通过 MXBean 动态改 flag 后调用）。
     */
    public void refresh() {
        cached = null;
    }

    private JvmInfo buildInfo() {
        Map<String, String> flags = new LinkedHashMap<>();
        for (String flag : TRACKED_FLAGS) {
            flags.put(flag, flagValue(flag));
        }

        long maxHeap = Runtime.getRuntime().maxMemory();
        long regionSize = parseLong(flags.get("G1HeapRegionSize"), 0L);
        long regionCount = regionSize > 0 ? maxHeap / regionSize : 0;
        long humongousThreshold = regionSize > 0 ? regionSize / 2 : 0;

        List<String> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            collectors.add(bean.getName());
        }

        return new JvmInfo(
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.vendor"),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(runtimeMXBean.getStartTime()),
                0L,
                List.copyOf(runtimeMXBean.getInputArguments()),
                List.copyOf(collectors),
                maxHeap,
                regionSize,
                regionCount,
                humongousThreshold,
                Map.copyOf(flags)
        );
    }

    /**
     * 读取单个 HotSpot flag 的实际生效值。
     *
     * <p>experimental flag 未解锁、或 JVM 不支持该 flag 时返回 {@code "n/a"}；
     * 调用方必须把 n/a 当作“不可判定”，不能当成 false。</p>
     */
    public String flagValue(String name) {
        if (hotSpotMXBean == null) {
            return "n/a";
        }
        try {
            return hotSpotMXBean.getVMOption(name).getValue();
        } catch (RuntimeException ex) {
            return "n/a";
        }
    }

    /**
     * @return 仅当 flag 明确为字符串 {@code "true"} 时返回 true
     */
    public boolean flagEnabled(String name) {
        return "true".equalsIgnoreCase(flagValue(name));
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
