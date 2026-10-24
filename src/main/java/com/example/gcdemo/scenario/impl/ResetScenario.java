package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.scenario.GcScenario;
import com.example.gcdemo.scenario.ScenarioContext;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioOutcome;
import com.example.gcdemo.scenario.ScenarioParams;
import com.example.gcdemo.scenario.ScenarioSupport;
import com.example.gcdemo.util.ByteSize;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 复位场景：清空 retained 强引用，默认再执行一次显式 GC。
 *
 * <p>清引用不等于立刻回收；下一次 Young/Mixed 或显式 Full GC 才会真正释放。
 * 本场景用于让下一轮实验从相对干净的堆状态开始。</p>
 */
@Component
public class ResetScenario implements GcScenario {

    private final ScenarioSupport support;

    public ResetScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "reset";
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Reset：释放引用并复位",
                "清空 retained 强引用；默认调用一次显式 GC，让下一轮实验从干净堆开始。",
                "高（默认 Full GC）",
                true,
                List.of(),
                List.of("Pause Full (System.gc())")
        );
    }

    /**
     * 清空 retained 引用，默认执行一次显式 GC 帮助观察堆回落。
     *
     * <p>不应把 reset 当作生产回收手段；它的目的只是让下一轮教学实验可重复。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        ctx.phase("释放 retained 强引用", 40);
        long retainedBefore = support.retained().usedBytes();
        support.retained().clear();
        // gc flag 由 reset 端点传入（?gc=false 时只清引用不触发显式 GC）。
        boolean gc = !"false".equalsIgnoreCase(params.flag("gc"));
        boolean ran = false;
        if (gc && support.properties().isAllowFullGc()) {
            ctx.phase("显式 GC 帮助观察堆回落", 80);
            System.gc();
            ran = true;
        }
        support.sleep(ctx, 300);
        ctx.phase("完成", 100);
        return new ScenarioOutcome(
                0,
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "retained " + ByteSize.human(retainedBefore) + " -> 0",
                        "显式 GC 已执行：" + ran
                ),
                ran
                        ? "引用已经释放，但 Full GC 仍会压缩堆；观察日志中的 Pause Full 和堆回落。"
                        : "已释放引用，但未执行显式 GC；对象会在后续 Young/Mixed 周期中逐步回收。",
                Map.of("retainedBeforeMb", retainedBefore / ByteSize.MB, "gcRan", ran)
        );
    }
}
