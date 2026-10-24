package com.example.gcdemo.scenario;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次场景执行的运行时上下文：进度上报、协作式取消与墙钟预算。
 *
 * <p>三个职责：
 * <ul>
 *   <li><b>预算</b>：deadline 由 Runner 按 {@code demo.max-scenario-wall-ms} 设定，
 *       {@link ScenarioSupport} 的 churn/awaitType/sleep 都会把期望时长夹到剩余预算内，
 *       场景总时长从此有真实上限（旧实现里 mixed-gc 内部硬编码的等待链路不受
 *       {@code demo.max-duration-ms} 约束）；</li>
 *   <li><b>取消</b>：HTTP 取消端点设置 cancelled 标志，场景循环在检查点抛出
 *       {@link ScenarioCancelledException}，Runner 转成 cancelled=true 的部分结果；</li>
 *   <li><b>进度</b>：场景在阶段边界上报 phase 文本与百分比，/api/status 实时暴露。</li>
 * </ul>
 * </p>
 */
public final class ScenarioContext {

    private final long deadlineNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> phase = new AtomicReference<>("");
    private final AtomicInteger percent = new AtomicInteger(-1);

    public ScenarioContext(long wallMs) {
        this.deadlineNanos = System.nanoTime() + wallMs * 1_000_000L;
    }

    public void phase(String text) {
        phase.set(text == null ? "" : text);
    }

    public void phase(String text, int percent) {
        phase(text);
        this.percent.set(Math.max(-1, Math.min(percent, 100)));
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 幂等取消；下一次检查点（churn 循环 / 阶段边界 / sleep 分片）生效。
     */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 协作式取消检查点：已取消时抛出携带当前阶段的异常。
     */
    public void checkCancelled() {
        if (isCancelled()) {
            throw new ScenarioCancelledException(phase.get());
        }
    }

    public long remainingMillis() {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public boolean expired() {
        return remainingMillis() <= 0;
    }

    /**
     * 把期望时长夹到剩余预算内；预算耗尽返回 ZERO。
     */
    public Duration limit(Duration requested) {
        long remainingMs = remainingMillis();
        if (remainingMs <= 0) {
            return Duration.ZERO;
        }
        return requested.toMillis() <= remainingMs ? requested : Duration.ofMillis(remainingMs);
    }

    public ScenarioProgress progress() {
        return new ScenarioProgress(phase.get(), percent.get(), cancelled.get(), remainingMillis());
    }
}
