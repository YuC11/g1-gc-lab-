package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.memory.AllocationTools;
import com.example.gcdemo.scenario.GcScenario;
import com.example.gcdemo.scenario.ParamSpec;
import com.example.gcdemo.scenario.ScenarioContext;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioOutcome;
import com.example.gcdemo.scenario.ScenarioParams;
import com.example.gcdemo.scenario.ScenarioSupport;
import com.example.gcdemo.util.ByteSize;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高分配率场景：多个线程持续分配短命对象，观察连续 Young GC、暂停分布和可能的
 * 并发周期。
 *
 * <p>日志证据：多次 {@code Pause Young}，可能进入 Mixed。分配总量达到
 * {@code max-allocation-mb} 会提前结束，响应中记录实际时长，避免误解为固定 10s。</p>
 */
@Component
public class AllocationStormScenario implements GcScenario {

    private final ScenarioSupport support;

    public AllocationStormScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "allocation-storm";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Allocation Storm：高分配率",
                "多线程持续分配短命对象，观察连续 Young GC、暂停分布以及是否会触发并发周期。",
                "中",
                false,
                List.of(
                        new ParamSpec("threads", "线程数", 4, 1, 8, "个"),
                        new ParamSpec("durationMs", "持续时间", 10000, 1000, 15000, "ms"),
                        new ParamSpec("chunkKb", "单次分配", 256, 16, 8192, "KB")
                ),
                List.of("Pause Young (Normal)", "Pause Young (Mixed)", "IHOP")
        );
    }

    /**
     * 多线程持续分配短命对象，直到目标时长或单场景分配上限。
     *
     * <p>响应中的实际时长可能明显小于目标时长，因为达到 3GB 安全上限会提前结束；
     * 这是预期行为，不应解读为时钟或 GC 异常。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        int threads = support.guard().clampThreads(params.get("threads"), 4);
        int durationMs = support.guard().clampDurationMs(params.get("durationMs"), 10_000);
        int chunkKb = support.guard().clampChunkKb(params.get("chunkKb"), 256);
        int chunkBytes = chunkKb * 1024;
        long maxBytes = support.properties().getMaxAllocationMb() * ByteSize.MB;

        ctx.phase("多线程分配风暴", 20);
        long gcBefore = support.stats().totalCollections();
        long stormStarted = System.nanoTime();
        AtomicLong allocated = new AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean capReached = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = stormStarted + TimeUnit.MILLISECONDS.toNanos(durationMs);
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "allocation-storm");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted() && System.nanoTime() < deadline) {
                    if (ctx.isCancelled()) {
                        return;
                    }
                    // CAS 计数：多线程 get()+set() 会互相覆盖（少报分配量、上限可被绕过），
                    // 这里保证“检查上限 + 累加”原子完成。
                    long current = allocated.get();
                    if (current + chunkBytes > maxBytes) {
                        capReached.set(true);
                        return;
                    }
                    if (!allocated.compareAndSet(current, current + chunkBytes)) {
                        continue;
                    }
                    AllocationTools.allocate(chunkBytes, (byte) 0x5A);
                }
            }));
        }
        try {
            for (Future<?> future : futures) {
                future.get(durationMs + 5_000L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            throw new com.example.gcdemo.error.ApiException(
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "分配风暴执行失败: " + ex.getMessage());
        } finally {
            executor.shutdownNow();
        }

        long delta = support.stats().totalCollections() - gcBefore;
        long allocatedMb = allocated.get() / ByteSize.MB;
        long actualMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stormStarted);
        return new ScenarioOutcome(
                allocatedMb,
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "线程 " + threads + "，目标持续 " + durationMs + "ms，实际 " + actualMs + "ms",
                        "分配约 " + ByteSize.human(allocated.get())
                                + (capReached.get() ? "（达到单场景分配上限）" : ""),
                        "GC 次数增量 " + delta
                ),
                "对比不同 MaxGCPauseMillis / 线程数下的暂停分布；高分配率会直接推高 Young GC 频率。",
                Map.of(
                        "threads", threads,
                        "durationMs", durationMs,
                        "allocatedMb", allocatedMb,
                        "gcDelta", delta
                )
        );
    }
}
