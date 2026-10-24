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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mixed GC 场景：制造“晋升后死亡”的 Old Region，触发并发标记，再等待真正的
 * {@code Pause Young (Mixed)}。
 *
 * <p>关键时序：Concurrent Start → Concurrent Mark → Remark → Cleanup → 下一次
 * Young Pause 携带 Mixed CSet。只保留全存活对象不会产生可回收 old region；
 * 只在 Cleanup 后结束也会错过 Mixed。因此实现中先制造 dead batch，再保留 live
 * batch，最后追加压力并主动轮询日志。</p>
 */
@Component
public class MixedGcScenario implements GcScenario {

    private final ScenarioSupport support;

    public MixedGcScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "mixed-gc";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Mixed GC：并发标记 + 混合回收",
                "先把一批即将死亡的对象晋升到 Old 再释放，制造可回收 old region；随后持续施压触发并发标记与 Mixed GC。",
                "中",
                false,
                List.of(
                        new ParamSpec("deadMb", "将死批次", support.mixedDeadMb(), 64, 1024, "MB"),
                        new ParamSpec("liveMb", "存活批次", support.mixedLiveMb(), 32, 512, "MB"),
                        new ParamSpec("finalChurnMb", "追加压力", support.mixedChurnMb(), 128, 1536, "MB")
                ),
                List.of("Concurrent Start", "Concurrent Mark", "Pause Remark", "Pause Cleanup", "Pause Young (Mixed)")
        );
    }

    /**
     * 三阶段触发真实 Mixed GC：
     * <ol>
     *   <li>分配 dead 批次，经历多轮 Young GC 使其晋升到 Old；随后释放；</li>
     *   <li>保留 live 批次，维持 Old 占用；</li>
     *   <li>持续施压跨过 IHOP，等待并发周期完成并在下一次 Young 中执行 Mixed。</li>
     * </ol>
     *
     * <p>如果只保留全存活对象，Old Region 没有可回收空间，Mixed 不会发生。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        int deadMb = params.get("deadMb", support.mixedDeadMb());
        int liveMb = params.get("liveMb", support.mixedLiveMb());
        int finalChurnMb = params.get("finalChurnMb", support.mixedChurnMb());
        int promoteChurnMb = Math.min(512, Math.max(384, deadMb * 3 / 2));
        long planned = (long) deadMb + liveMb + finalChurnMb + promoteChurnMb;
        support.checkAllocationMb(planned);
        support.checkPeak((long) (deadMb + liveMb) * ByteSize.MB);
        support.checkHeadroom((long) (deadMb + liveMb) * ByteSize.MB);
        support.retained().clear();

        long oldBefore = support.heap().oldUsed();
        long gcBefore = support.stats().totalCollections();

        // 阶段 1：保持 dead 批次跨多轮 Young GC，使其晋升到 Old，再主动释放引用。
        ctx.phase("阶段 1/3：将死批次晋升到 Old", 10);
        List<Object> dead = new ArrayList<>();
        support.allocateInto(dead, deadMb * ByteSize.MB, (byte) 0x21);
        support.retained().put("mixed-dead", dead, deadMb * ByteSize.MB);
        support.churn(
                ctx,
                promoteChurnMb * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(12),
                () -> support.stats().totalCollections() - gcBefore >= 4
        );
        support.retained().remove("mixed-dead");
        dead = null;

        // 阶段 2：保留 live 批次，让并发标记开始时仍有足够的 Old 占用。
        ctx.phase("阶段 2/3：制造存活批次", 35);
        List<Object> live = new ArrayList<>();
        support.allocateInto(live, liveMb * ByteSize.MB, (byte) 0x42);
        support.retained().put("mixed-live", live, liveMb * ByteSize.MB);

        // 阶段 3：持续分配，跨过 IHOP 并等待真正的 Mixed Pause 出现。
        ctx.phase("阶段 3/3：施压等待并发周期与 Mixed", 55);
        Instant mixedWatch = Instant.now().minusMillis(200);
        long finalAllocated = support.churn(
                ctx,
                finalChurnMb * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(20),
                () -> support.eventStore().hasTypeSince("Mixed", mixedWatch)
        );
        boolean mixedSeen = support.eventStore().hasTypeSince("Mixed", mixedWatch);
        long extraAllocated = 0;
        if (!mixedSeen) {
            // 并发周期以 Remark/Cleanup 收尾；真正的 Mixed 往往发生在之后的下一次
            // Young Pause。这里继续施压，确保那次暂停实际发生。
            extraAllocated = support.churn(
                    ctx,
                    512 * ByteSize.MB,
                    support.normalChunkBytes(),
                    Duration.ofSeconds(8),
                    () -> support.eventStore().hasTypeSince("Mixed", mixedWatch)
            );
            // 等待 Cleanup 完成；若尚未看到 Mixed，再补一段压力触发下一次 Young。
            support.awaitType(ctx, Set.of("Cleanup"), mixedWatch, Duration.ofSeconds(5));
            if (!support.eventStore().hasTypeSince("Mixed", mixedWatch)) {
                extraAllocated += support.churn(
                        ctx,
                        512 * ByteSize.MB,
                        support.normalChunkBytes(),
                        Duration.ofSeconds(8),
                        () -> support.eventStore().hasTypeSince("Mixed", mixedWatch)
                );
            }
            mixedSeen = support.awaitType(ctx, Set.of("Mixed"), mixedWatch, Duration.ofSeconds(5));
        }
        support.sleep(ctx, 100);
        ctx.phase("汇总", 95);

        long oldAfter = support.heap().oldUsed();
        long gcDelta = support.stats().totalCollections() - gcBefore;

        return new ScenarioOutcome(
                (deadMb + promoteChurnMb + liveMb + finalAllocated / ByteSize.MB
                        + extraAllocated / ByteSize.MB),
                support.retained().usedBytes() / ByteSize.MB,
                List.of(
                        "将死批次 " + deadMb + " MB，压力分配 " + promoteChurnMb + " MB",
                        "存活批次 " + liveMb + " MB",
                        "追加分配 " + ByteSize.human(finalAllocated + extraAllocated),
                        "Old " + ByteSize.human(oldBefore) + " -> " + ByteSize.human(oldAfter),
                        "GC 次数增量 " + gcDelta,
                        "mixed 事件：" + mixedSeen
                ),
                mixedSeen
                        ? "在日志中定位 Concurrent Start、Remark、Cleanup 与 Pause Young (Mixed)，观察 CSet 中的 old region。"
                        : "未观察到 Mixed：可增大追加压力、降低 IHOP，或确认日志 watcher 已生效；仅靠全存活对象不会产生可回收 old region。",
                Map.of(
                        "deadMb", deadMb,
                        "promoteChurnMb", promoteChurnMb,
                        "liveMb", liveMb,
                        "mixedSeen", mixedSeen,
                        "gcDelta", gcDelta,
                        "oldBeforeMb", oldBefore / ByteSize.MB,
                        "oldAfterMb", oldAfter / ByteSize.MB
                )
        );
    }
}
