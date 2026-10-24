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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 晋升场景：保留一批对象并持续制造年轻代压力，使对象进入 Survivor 并晋升到 Old。
 *
 * <p>日志证据：Old regions / Old used 增长，Survivor 数量变化。lab 中把
 * {@code MaxTenuringThreshold=3} 以加快演示，但这不是 JDK 默认值。</p>
 */
@Component
public class PromotionScenario implements GcScenario {

    private final ScenarioSupport support;

    public PromotionScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "promotion";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Promotion：Survivor 与晋升",
                "保留一批对象并持续制造年轻代压力，使对象经历 Survivor 后晋升到 Old。",
                "低",
                false,
                List.of(
                        new ParamSpec("retainedMb", "保留量", support.promotionRetainedMb(), 32, 768, "MB"),
                        new ParamSpec("churnMb", "GC 压力分配量", support.promotionChurnMb(), 128, 1536, "MB")
                ),
                List.of("Pause Young (Normal)", "Old regions", "Survivor regions")
        );
    }

    /**
     * 保留一批对象，再用短命分配制造多轮 Young GC，推动对象经过 Survivor 晋升。
     *
     * <p>执行前后比较 Old used；如果增长不明显，通常说明对象仍在 Survivor，或
     * 压力不足以触发足够的年轻代回收。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        int retainedMb = params.get("retainedMb", support.promotionRetainedMb());
        int churnMb = params.get("churnMb", support.promotionChurnMb());
        support.checkAllocationMb((long) retainedMb + churnMb);
        support.checkPeak(retainedMb * ByteSize.MB);
        support.checkHeadroom(retainedMb * ByteSize.MB);
        support.retained().clear();

        long oldBefore = support.heap().oldUsed();
        long gcBefore = support.stats().totalCollections();
        ctx.phase("分配保留批次", 20);
        List<Object> retained = new ArrayList<>(retainedMb);
        support.allocateInto(retained, retainedMb * ByteSize.MB, (byte) 0x31);
        support.retained().put("promotion", retained, retainedMb * ByteSize.MB);

        ctx.phase("制造年轻代压力推动晋升", 60);
        long churned = support.churn(
                ctx,
                churnMb * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(12),
                () -> support.stats().totalCollections() - gcBefore >= 6
        );
        support.sleep(ctx, 100);
        ctx.phase("汇总", 90);
        long oldAfter = support.heap().oldUsed();
        long delta = support.stats().totalCollections() - gcBefore;

        return new ScenarioOutcome(
                (retainedMb + churned / ByteSize.MB),
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "保留对象约 " + retainedMb + " MB",
                        "GC 次数增量 " + delta,
                        "Old 使用量 " + ByteSize.human(oldBefore) + " -> " + ByteSize.human(oldAfter)
                ),
                oldAfter - oldBefore < 8 * ByteSize.MB
                        ? "Old 增长不明显：对象可能仍在 Survivor。增大压力分配量或降低 MaxTenuringThreshold 后重试。"
                        : "观察日志中 Survivor/Old regions 的变化，以及对象年龄阈值对晋升的影响。",
                Map.of(
                        "retainedMb", retainedMb,
                        "churnMb", churned / ByteSize.MB,
                        "oldBeforeMb", oldBefore / ByteSize.MB,
                        "oldAfterMb", oldAfter / ByteSize.MB,
                        "gcDelta", delta
                )
        );
    }
}
