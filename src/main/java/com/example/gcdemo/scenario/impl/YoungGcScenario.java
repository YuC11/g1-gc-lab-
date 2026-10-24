package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.scenario.GcScenario;
import com.example.gcdemo.scenario.ParamSpec;
import com.example.gcdemo.scenario.ScenarioContext;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioOutcome;
import com.example.gcdemo.scenario.ScenarioParams;
import com.example.gcdemo.scenario.ScenarioSupport;
import com.example.gcdemo.util.ByteSize;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Young GC 场景：快速分配并丢弃短命对象，耗尽 Eden 后观察普通年轻代回收。
 *
 * <p>默认分配量按堆比例推导，chunk 固定为 256KB，避免误入 Humongous。日志证据：
 * {@code Pause Young (Normal)}、Eden regions 下降、少量晋升。</p>
 */
@Component
public class YoungGcScenario implements GcScenario {

    private final ScenarioSupport support;

    public YoungGcScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "young-gc";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public ScenarioInfo info() {
        int defaultMb = support.youngDefaultMb();
        return new ScenarioInfo(
                id(),
                "Young GC：短命对象洪峰",
                "快速分配并立即丢弃短命对象，耗尽 Eden 后触发 G1 Young GC。",
                "低",
                false,
                List.of(
                        new ParamSpec("totalMb", "总分配量", defaultMb, 32, 2048, "MB"),
                        new ParamSpec("chunkKb", "单次分配", 256, 16, 8192, "KB")
                ),
                List.of("Pause Young (Normal)", "Eden regions", "Evacuation Pause")
        );
    }

    /**
     * 执行短命对象洪峰。
     *
     * <p>达到 2 次 GC 或 12s 后停止，避免为了追求固定分配量而拉长实验。实际分配量
     * 与 GC 增量会在响应中返回。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        int totalMb = params.get("totalMb", support.youngDefaultMb());
        int chunkKb = support.guard().clampChunkKb(params.get("chunkKb"), 256);
        support.checkAllocationMb(totalMb);
        int chunkBytes = chunkKb * 1024;

        ctx.phase("分配短命对象洪峰", 10);
        long gcBefore = support.stats().totalCollections();
        long allocated = support.churn(
                ctx,
                totalMb * ByteSize.MB,
                chunkBytes,
                Duration.ofSeconds(12),
                () -> support.stats().totalCollections() - gcBefore >= 2
        );
        ctx.phase("汇总", 90);
        long delta = support.stats().totalCollections() - gcBefore;

        return new ScenarioOutcome(
                allocated / ByteSize.MB,
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "实际分配 " + ByteSize.human(allocated),
                        "Young GC 次数增量 " + delta
                ),
                delta == 0
                        ? "没有触发 GC：增大 totalMb，或检查堆是否远大于分配量。"
                        : "查看日志中 Pause Young (Normal) 的 before->after 与 Eden regions 变化。",
                Map.of("totalMb", totalMb, "chunkKb", chunkKb, "gcDelta", delta)
        );
    }
}
