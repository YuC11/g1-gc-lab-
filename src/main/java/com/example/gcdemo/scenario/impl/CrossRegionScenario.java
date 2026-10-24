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
import java.util.concurrent.ThreadLocalRandom;

/**
 * 跨 Region 引用场景：构建 25 万节点的随机引用图并保留，制造 RSet 更新/扫描压力。
 *
 * <p>verbose 日志中观察 {@code gc,remset}、{@code gc,refine,stats}、
 * {@code Remembered Sets} 和 {@code Scanned Cards}。这是解释“为什么 Young GC
 * 的固定成本会随堆对象图增长”的实验。</p>
 */
@Component
public class CrossRegionScenario implements GcScenario {

    private static final class Node {
        private final byte[] payload = new byte[24];
        private final long value;
        private Node left;
        private Node right;

        private Node(long value) {
            this.value = value;
        }
    }

    private final ScenarioSupport support;

    public CrossRegionScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "cross-region";
    }

    @Override
    public int order() {
        return 70;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Cross-Region：RSet 与写屏障",
                "构造跨 region 的随机引用图并保留，再制造年轻代压力，观察 Remembered Sets / Scanned Cards / refinement 开销。",
                "中",
                false,
                List.of(
                        new ParamSpec("count", "节点数", 250_000, 10_000, 1_000_000, "个"),
                        new ParamSpec("churnMb", "压力分配", support.crossRegionChurnMb(), 64, 1024, "MB")
                ),
                List.of("Remembered Sets", "Scanned Cards", "gc,remset", "gc,refine,stats")
        );
    }

    /**
     * 构建随机跨对象引用图并保留，再分配年轻代垃圾触发 RSet 更新/扫描。
     *
     * <p>需要 verbose 日志才能看到 {@code gc,remset} 和
     * {@code gc,refine,stats} 细节；默认 info 级别只显示暂停汇总。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        int count = params.get("count", 250_000);
        int churnMb = params.get("churnMb", support.crossRegionChurnMb());
        support.checkAllocationMb(churnMb);
        long estimate = (long) count * 96;
        support.checkPeak(estimate);
        support.checkHeadroom(estimate);
        support.retained().clear();

        ctx.phase("构建跨 region 引用图", 40);
        Node[] nodes = new Node[count];
        for (int i = 0; i < count; i++) {
            if (i % 100_000 == 0 && i > 0) {
                ctx.checkCancelled();
            }
            nodes[i] = new Node(i);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            nodes[i].left = nodes[random.nextInt(count)];
            nodes[i].right = nodes[random.nextInt(count)];
        }
        List<Object> refs = new ArrayList<>(1);
        refs.add(nodes);
        support.retained().put("cross-region", refs, estimate);

        ctx.phase("年轻代压力触发 RSet 扫描", 75);
        long gcBefore = support.stats().totalCollections();
        long churned = support.churn(
                ctx,
                churnMb * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(12),
                () -> support.stats().totalCollections() - gcBefore >= 3
        );
        ctx.phase("汇总", 95);
        long delta = support.stats().totalCollections() - gcBefore;

        return new ScenarioOutcome(
                churned / ByteSize.MB,
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "节点数 " + count + "，估算 " + ByteSize.human(estimate),
                        "跨 region 随机引用已建立",
                        "GC 次数增量 " + delta
                ),
                "用 PROFILE=verbose 启动，在日志中搜索 gc,remset / gc,refine,stats / Scanned Cards；RSet 越大，Young GC 的固定成本越高。",
                Map.of("count", count, "churnMb", churned / ByteSize.MB, "gcDelta", delta)
        );
    }
}
