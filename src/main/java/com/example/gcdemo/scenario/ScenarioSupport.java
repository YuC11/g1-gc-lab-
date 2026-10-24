package com.example.gcdemo.scenario;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.gc.GcEventStore;
import com.example.gcdemo.gc.GcLogWatcher;
import com.example.gcdemo.gc.GcStatsService;
import com.example.gcdemo.gc.HeapService;
import com.example.gcdemo.gc.JvmInfoService;
import com.example.gcdemo.gc.SideLogWatchers;
import com.example.gcdemo.memory.AllocationTools;
import com.example.gcdemo.memory.MemoryGuard;
import com.example.gcdemo.memory.RetainedMemory;
import com.example.gcdemo.util.ByteSize;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 场景共享的分配、等待、按堆缩放和安全检查工具。
 *
 * <p>设计原则：
 * <ul>
 *   <li>所有普通 chunk 使用 {@link #normalChunkBytes()}（256KB），避免无意中制造
 *       Humongous 对象；</li>
 *   <li>默认分配量按当前堆比例推导，并同时受 MemoryGuard 和绝对上限约束；</li>
 *   <li>等待 GC 事件时主动 poll 日志 watcher，避免与 500ms 定时轮询竞争。</li>
 * </ul>
 * </p>
 */
@Component
public class ScenarioSupport {

    private final DemoProperties properties;
    private final MemoryGuard guard;
    private final RetainedMemory retained;
    private final HeapService heap;
    private final JvmInfoService jvmInfo;
    private final GcStatsService stats;
    private final GcEventStore eventStore;
    private final GcLogWatcher logWatcher;
    private final SideLogWatchers sideLogs;

    public ScenarioSupport(DemoProperties properties,
                           MemoryGuard guard,
                           RetainedMemory retained,
                           HeapService heap,
                           JvmInfoService jvmInfo,
                           GcStatsService stats,
                           GcEventStore eventStore,
                           GcLogWatcher logWatcher,
                           SideLogWatchers sideLogs) {
        this.properties = properties;
        this.guard = guard;
        this.retained = retained;
        this.heap = heap;
        this.jvmInfo = jvmInfo;
        this.stats = stats;
        this.eventStore = eventStore;
        this.logWatcher = logWatcher;
        this.sideLogs = sideLogs;
    }

    public DemoProperties properties() {
        return properties;
    }

    public MemoryGuard guard() {
        return guard;
    }

    public RetainedMemory retained() {
        return retained;
    }

    public HeapService heap() {
        return heap;
    }

    public JvmInfoService jvmInfo() {
        return jvmInfo;
    }

    public GcStatsService stats() {
        return stats;
    }

    public GcEventStore eventStore() {
        return eventStore;
    }

    /**
     * 侧日志证据源（stringdedup / class-unload），场景结束时主动 poll 再读取增量。
     */
    public SideLogWatchers sideLogs() {
        return sideLogs;
    }

    public int heapMb() {
        return (int) guard.maxHeapMb();
    }

    /**
     * 普通 churn 的安全 chunk 大小。
     *
     * <p>固定 256KB，确保在最小 1M Region 下也明显小于 region/2；否则 byte[] 加上
     * 对象头后会变成 Humongous，实验会从“普通 Young GC”变成 H region 行为。</p>
     */
    public int normalChunkBytes() {
        return 256 * 1024;
    }

    /**
     * 按固定 chunk 把指定总量分配进目标列表。
     *
     * <p>按“总字节数”循环，而不是按“次数”循环，避免 MB 与 chunk 大小不一致时
     * 实际只分配了目标的 1/4（这是实现过程中实际踩过的坑）。</p>
     */
    public void allocateInto(List<Object> target, long bytes, byte fill) {
        long remaining = bytes;
        while (remaining > 0) {
            int size = (int) Math.min(normalChunkBytes(), remaining);
            target.add(AllocationTools.allocate(size, fill));
            remaining -= size;
        }
    }

    public int regionMb() {
        long region = jvmInfo.info().regionSizeBytes();
        return region <= 0 ? 1 : (int) Math.max(1, region / ByteSize.MB);
    }

    /**
     * @return 一个确定超过 region/2 的 MB 大小，用于 H 场景
     */
    public int humongousObjectMb() {
        return Math.max(2, regionMb() / 2 + 1);
    }

    /**
     * @return region/2 的字节数，用于面板展示与实验解释
     */
    public long humongousThresholdBytes() {
        long region = jvmInfo.info().regionSizeBytes();
        return region <= 0 ? 0 : region / 2;
    }

    /**
     * Young 场景默认分配量。
     *
     * <p>lab 的 max young 是堆的 35%，这里按 60% 分配以确保至少发生一次 Young GC；
     * 实际会在达到 2 次 GC 或时间上限时提前停止。</p>
     */
    public int youngDefaultMb() {
        return scaledMb(0.60d, 2048, 768, properties.getMaxAllocationMb());
    }

    public int promotionRetainedMb() {
        return scaledMb(0.125d, 384, 128, properties.getMaxRetainedMb());
    }

    public int promotionChurnMb() {
        return scaledMb(0.25d, 768, 256, properties.getMaxAllocationMb());
    }

    public int mixedDeadMb() {
        return scaledMb(0.20d, 512, 128, properties.getMaxRetainedMb());
    }

    public int mixedLiveMb() {
        return scaledMb(0.10d, 256, 64, properties.getMaxRetainedMb());
    }

    public int mixedChurnMb() {
        return scaledMb(0.30d, 1024, 256, properties.getMaxAllocationMb());
    }

    public int crossRegionChurnMb() {
        return scaledMb(0.20d, 512, 128, properties.getMaxAllocationMb());
    }

    public int stringChurnMb() {
        return scaledMb(0.50d, 1024, 512, properties.getMaxAllocationMb());
    }

    public int humongousChurnMb() {
        return scaledMb(0.15d, 384, 128, properties.getMaxAllocationMb());
    }

    private int scaledMb(double fraction, int cap, int floor, int absoluteCap) {
        long value = (long) (heapMb() * fraction);
        int upper = Math.min(cap, absoluteCap);
        return (int) ByteSize.clamp(value, floor, Math.max(floor, upper));
    }

    public void checkAllocationMb(long mb) {
        guard.checkAllocationBytes(mb * ByteSize.MB);
    }

    public void checkPeak(long bytes) {
        guard.checkPeakRetained(bytes);
    }

    /**
     * 堆水位检查：当前堆已用 + 本次实验的瞬时存活峰值不得超过堆的 92%。
     *
     * <p>{@link MemoryGuard} 的上限是“绝对量 vs 固定配置”，不感知当前堆里还剩多少
     * 空间；本检查补上这一层。只对“会长期存活的峰值”（retained 批次）调用——
     * 纯短命 churn 由 Young GC 持续回收，瞬时占用远低于分配总量，不适用本检查。</p>
     */
    public void checkHeadroom(long plannedRetainedBytes) {
        long max = guard.maxHeapBytes();
        long used = heap.snapshot().heapUsed();
        if (used + plannedRetainedBytes > max * 92L / 100L) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "当前堆已用 " + ByteSize.human(used) + "，叠加本次存活峰值 "
                            + ByteSize.human(plannedRetainedBytes) + " 会超过堆的 92%",
                    "先运行 reset 释放 retained 引用，或调小场景参数。");
        }
    }

    /**
     * 阻塞等待 GC 日志消费追平（最多 2s）。
     *
     * <p>场景结束时调用：单次 poll 最多读 512KB，verbose/高压场景的日志需要
     * 多轮消费；追平后再汇总事件，避免 observedEventTypes 缺最后一次 pause。</p>
     */
    public void waitForLogCaughtUp() {
        try {
            logWatcher.pollUntilQuiet(Duration.ofSeconds(2));
        } catch (RuntimeException ignored) {
            // watcher 的错误会体现在 /api/status 的 logWatcher.error；不阻塞场景结果
        }
    }

    public void checkFullGcAllowed() {
        if (!properties.isAllowFullGc()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "full GC 场景已被 demo.allow-full-gc=false 禁用");
        }
    }

    /**
     * 分配短命对象直到总量/超时/停止条件满足。
     *
     * <p>只保留最后一个对象在 volatile sink 中，不保留整批引用，因此模拟的是
     * “高分配率、低存活率”的短命对象流。stop 在每次分配前检查，避免事件已经出现
     * 后还继续制造无意义压力。超时会被夹到 {@code ctx} 的剩余墙钟预算内；
     * 每次分配前也是取消检查点。</p>
     *
     * @return 实际分配字节数
     */
    public long churn(ScenarioContext ctx, long totalBytes, int chunkBytes, Duration timeout, BooleanSupplier stop) {
        long deadline = System.nanoTime() + limit(ctx, timeout).toNanos();
        long allocated = 0;
        while (allocated < totalBytes && System.nanoTime() < deadline) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            if (stop != null && stop.getAsBoolean()) {
                break;
            }
            int size = (int) Math.min(chunkBytes, totalBytes - allocated);
            AllocationTools.allocate(size, (byte) 0x5A);
            allocated += size;
        }
        return allocated;
    }

    /**
     * 等待指定 GC 事件，并主动 poll 日志 watcher。
     *
     * <p>为什么不直接调用 {@link GcEventStore#awaitType}：日志写入文件、watcher
     * 读取、event store 写入是异步链路，场景断言必须推进这条链路，否则可能
     * 在 Mixed 已经打印后仍返回 false。等待时长夹到墙钟预算内，每轮都是取消检查点。</p>
     */
    public boolean awaitType(ScenarioContext ctx, Set<String> types, Instant since, Duration timeout) {
        long deadline = System.nanoTime() + limit(ctx, timeout).toNanos();
        while (System.nanoTime() < deadline) {
            // Poll on demand so scenario assertions do not race the scheduled watcher.
            logWatcher.poll();
            if (eventStore.hasAnyTypeSince(types, since)) {
                return true;
            }
            if (ctx != null) {
                ctx.checkCancelled();
            }
            sleep(ctx, 50);
        }
        logWatcher.poll();
        return eventStore.hasAnyTypeSince(types, since);
    }

    /**
     * 轮询等待 GC 次数增量，仅用于没有特定事件类型要求的场景。
     */
    public boolean waitGcDelta(ScenarioContext ctx, long before, long target, Duration timeout) {
        long deadline = System.nanoTime() + limit(ctx, timeout).toNanos();
        while (System.nanoTime() < deadline) {
            if (stats.totalCollections() - before >= target) {
                return true;
            }
            if (ctx != null) {
                ctx.checkCancelled();
            }
            sleep(ctx, 50);
        }
        return false;
    }

    /**
     * 可中断、可取消的睡眠：按 50ms 分片检查取消标志；中断后恢复 interrupt flag
     * 并转为 500，避免静默吞掉取消信号。
     */
    public void sleep(ScenarioContext ctx, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (ctx != null) {
                ctx.checkCancelled();
            }
            long remainingMs = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            long slice = Math.min(50L, remainingMs);
            try {
                TimeUnit.MILLISECONDS.sleep(slice);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "场景被中断");
            }
        }
    }

    private static Duration limit(ScenarioContext ctx, Duration requested) {
        return ctx == null ? requested : ctx.limit(requested);
    }

    public Map<String, Long> retainedDetails() {
        return retained.details();
    }
}
