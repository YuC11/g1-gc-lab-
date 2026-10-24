package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.scenario.GcScenario;
import com.example.gcdemo.scenario.ScenarioContext;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioOutcome;
import com.example.gcdemo.scenario.ScenarioParams;
import com.example.gcdemo.scenario.ScenarioSupport;
import com.example.gcdemo.util.ByteSize;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 显式 Full GC 场景：调用 {@code System.gc()} 并等待 {@code Pause Full}。
 *
 * <p>用于观察 G1 的全堆压缩兜底路径。生产环境中更常见、更危险的是
 * Allocation Failure / Evacuation Failure 触发的 Full GC；本场景只提供可控入口，
 * 不应被理解为推荐的回收方式。</p>
 */
@Component
public class FullGcScenario implements GcScenario {

    private final ScenarioSupport support;

    public FullGcScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "full-gc";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Full GC：显式 System.gc()",
                "故意调用 System.gc()，观察 G1 的 Full GC、堆压缩以及显式 GC 相关参数的影响。",
                "高（会 STW 并压缩堆）",
                true,
                List.of(),
                List.of("Pause Full (System.gc())", "Phase 4: Compact heap", "GC(n) Pause Full")
        );
    }

    /**
     * 调用一次显式 System.gc()，并等待 Full 事件。
     *
     * <p>会检测 DisableExplicitGC / ExplicitGCInvokesConcurrent 并返回教学提示；
     * 不会为了“制造 Full GC”而故意制造 allocation failure。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        support.checkFullGcAllowed();
        if (!support.jvmInfo().flagValue("UseG1GC").equalsIgnoreCase("true")) {
            throw new ApiException(HttpStatus.CONFLICT, "当前 JVM 没有使用 G1");
        }
        boolean disabled = support.jvmInfo().flagEnabled("DisableExplicitGC");
        boolean concurrentExplicit = support.jvmInfo().flagEnabled("ExplicitGCInvokesConcurrent");
        long before = support.stats().totalCollections();
        long fullBefore = support.stats().snapshot().fullCollections();
        Instant start = Instant.now().minusMillis(100);
        ctx.phase("调用 System.gc() 并等待 Pause Full", 40);
        System.gc();
        boolean fullSeen = support.awaitType(ctx, Set.of("Full"), start, Duration.ofSeconds(5));
        support.sleep(ctx, 100);
        ctx.phase("汇总", 90);
        long fullAfter = support.stats().snapshot().fullCollections();
        long delta = support.stats().totalCollections() - before;

        String hint;
        if (disabled) {
            hint = "检测到 -XX:+DisableExplicitGC，System.gc() 会被忽略；移除该参数后重试。";
        } else if (concurrentExplicit) {
            hint = "检测到 -XX:+ExplicitGCInvokesConcurrent，显式 GC 可能转为并发周期，而不是 Pause Full。";
        } else if (!fullSeen && fullAfter == fullBefore) {
            hint = "没有观察到 Full GC；检查日志 watcher / 显式 GC 参数。";
        } else {
            hint = "Pause Full 说明 G1 退化为全堆压缩；生产环境应优先排查 allocation failure 根因，而不是频繁触发 Full GC。";
        }

        return new ScenarioOutcome(
                0,
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "System.gc() 已调用",
                        "Full 事件：" + fullSeen,
                        "Old collector 次数 " + fullBefore + " -> " + fullAfter,
                        "GC 次数增量 " + delta
                ),
                hint,
                Map.of(
                        "fullSeen", fullSeen,
                        "explicitGcDisabled", disabled,
                        "explicitGcConcurrent", concurrentExplicit,
                        "gcDelta", delta
                )
        );
    }
}
