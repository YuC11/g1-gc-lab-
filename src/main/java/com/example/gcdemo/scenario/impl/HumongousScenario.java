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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Humongous 场景：分配大于 region/2 的 byte[]，观察连续 H Region 的占用和回收。
 *
 * <p>注意对象头：一个“刚好 region/2”的 byte[] 也可能超过阈值。普通场景使用
 * 256KB chunk 正是为了避免这个陷阱。2G/1M Region 与 4G/2M Region 的 H 数量
 * 会明显不同。</p>
 */
@Component
public class HumongousScenario implements GcScenario {

    private final ScenarioSupport support;

    public HumongousScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "humongous";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Humongous：大对象与 Region 碎片",
                "分配超过 region 一半的大数组，观察连续 H region 的占用、释放与 cleanup。",
                "中",
                false,
                List.of(
                        new ParamSpec("count", "对象个数", 32, 2, 512, "个"),
                        new ParamSpec("objectMb", "单对象大小", support.humongousObjectMb(), 1, 256, "MB")
                ),
                List.of("Humongous regions", "Pause Cleanup", "Pause Young (Normal)")
        );
    }

    /**
     * 分配超过 region/2 的大数组，保留一半并制造回收压力。
     *
     * <p>日志中的 {@code Humongous regions} 是主要证据；释放引用后可用 reset
     * 观察 H Region 回落。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        // 参数已由 Runner 按 ParamSpec（count 2..512、objectMb 1..256）严格校验。
        int count = params.get("count", 32);
        int objectMb = params.get("objectMb", support.humongousObjectMb());
        long totalBytes = (long) count * objectMb * ByteSize.MB;
        support.checkAllocationMb((long) count * objectMb);
        support.checkPeak(totalBytes / 2);
        support.checkHeadroom(totalBytes);
        support.retained().clear();

        long gcBefore = support.stats().totalCollections();
        ctx.phase("分配 Humongous 大对象", 30);
        List<Object> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i % 16 == 0) {
                ctx.checkCancelled();
            }
            all.add(AllocationTools.allocate((int) (objectMb * ByteSize.MB), (byte) 0x66));
        }
        int retainedCount = Math.max(1, count / 2);
        List<Object> retained = new ArrayList<>(all.subList(0, retainedCount));
        support.retained().put("humongous", retained, (long) retainedCount * objectMb * ByteSize.MB);
        all.clear();

        ctx.phase("制造回收压力", 70);
        long churned = support.churn(
                ctx,
                support.humongousChurnMb() * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(10),
                () -> support.stats().totalCollections() - gcBefore >= 2
        );
        ctx.phase("汇总", 95);
        long delta = support.stats().totalCollections() - gcBefore;

        return new ScenarioOutcome(
                (count + churned / ByteSize.MB),
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "region 大小约 " + support.regionMb() + " MB，H 阈值约 "
                                + ByteSize.human(support.humongousThresholdBytes()),
                        "分配 " + count + " 个 " + objectMb + " MB 大对象，保留 " + retainedCount + " 个",
                        "GC 次数增量 " + delta
                ),
                "查看日志中的 Humongous regions 与 Pause Cleanup；释放全部引用后运行 reset 观察 H region 回收。",
                Map.of(
                        "count", count,
                        "objectMb", objectMb,
                        "regionMb", support.regionMb(),
                        "gcDelta", delta
                )
        );
    }
}
