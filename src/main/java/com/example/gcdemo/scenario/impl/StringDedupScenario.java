package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.gc.SideLogWatchers;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字符串去重场景：创建内容相同、底层数组独立的字符串，等待并发去重。
 *
 * <p>JDK 21 中去重统计使用独立 tag {@code stringdedup}，不会被 {@code gc*} 选择器
 * 自动包含；启动脚本为它单独配置了日志文件。要看的是
 * {@code Concurrent String Deduplication} 的 new/deduped 数据，而不是 GC pause。</p>
 */
@Component
public class StringDedupScenario implements GcScenario {

    private final ScenarioSupport support;

    public StringDedupScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "string-dedup";
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "String Dedup：字符串去重",
                "创建大量内容相同但底层数组独立的字符串，观察 -XX:+UseStringDeduplication 的效果和成本。",
                "低",
                false,
                List.of(
                        new ParamSpec("count", "字符串数量", 30_000, 1000, 200_000, "个"),
                        new ParamSpec("churnMb", "触发 GC 的分配量", support.stringChurnMb(), 64, 1024, "MB")
                ),
                List.of("Concurrent String Deduplication", "stringdedup", "Pause Young")
        );
    }

    /**
     * 创建内容相同、底层数组独立的字符串并经历多轮 GC。
     *
     * <p>去重由并发线程完成，统计写在独立 {@code stringdedup} 日志；场景本身只负责
     * 制造满足年龄阈值和 GC 次数的条件。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        // 参数解析与默认值
        int count = params.get("count", 30_000);
        int churnMb = params.get("churnMb", support.stringChurnMb());
        // 安全检查：先计算再分配
        support.checkAllocationMb(churnMb);
        long estimate = (long) count * (512L * 2 + 64);
        support.checkPeak(estimate);
        support.checkHeadroom(estimate);
        // 清理上一轮retained
        support.retained().clear();
        // 检查JVM是否开启去重配置
        boolean enabled = support.jvmInfo().flagEnabled("UseStringDeduplication");
        // 创建 底层数组独立 的字符串，并放入 retained 强引用区
        ctx.phase("创建重复字符串", 30);
        char[] chars = new char[512];
        Arrays.fill(chars, 'x');
        List<Object> strings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (i % 10_000 == 0 && i > 0) {
                ctx.checkCancelled();
            }
            strings.add(new String(chars));
        }
        support.retained().put("string-dedup", strings, estimate);

        // 记录GC起点与侧日志水位（水位匹配不依赖时钟一致性）
        long gcBefore = support.stats().totalCollections();
        Instant start = Instant.now();
        int dedupWatermark = support.sideLogs().dedupStats().size();
        // 制造短命垃圾，触发多轮 Young GC
        ctx.phase("触发 GC 让字符串达到去重年龄", 70);
        long churned = support.churn(
                ctx,
                churnMb * ByteSize.MB,
                support.normalChunkBytes(),
                Duration.ofSeconds(12),
                () -> support.stats().totalCollections() - gcBefore >= 4
        );
        // 主动消费侧日志，拿到本实验产生的去重统计（一手证据）。
        // 实测（verbose/前置多场景后）：本批统计行由 StringDedup 线程异步产生，
        // 落盘可能滞后场景结束数十秒。等待循环里用小口 churn 催一轮 Young GC
        // 推进去重线程；仍拿不到时降级为提示（统计稍后仍会出现在
        // /api/status 的 sideLogs.stringDedup 与日志面板中）。
        ctx.phase("汇总", 95);
        support.sideLogs().poll();
        SideLogWatchers.StringDedupStats stats = support.sideLogs().dedupSince(start)
                .or(() -> support.sideLogs().bestDedupAfter(dedupWatermark, count / 2))
                .orElse(null);
        long nudgeAllocated = 0;
        if (stats == null && enabled) {
            for (int i = 0; i < 20 && stats == null; i++) {
                if (i % 4 == 0) {
                    // 每 ~1.6s 催一次小 Young GC：给去重线程一个新的处理触发点
                    nudgeAllocated += support.churn(ctx, 48 * ByteSize.MB, support.normalChunkBytes(),
                            Duration.ofSeconds(2), () -> false);
                }
                support.sleep(ctx, 400);
                support.sideLogs().poll();
                stats = support.sideLogs().dedupSince(start)
                        .or(() -> support.sideLogs().bestDedupAfter(dedupWatermark, count / 2))
                        .orElse(null);
            }
        }
        long delta = support.stats().totalCollections() - gcBefore;

        List<String> observations = new ArrayList<>(List.of(
                "字符串 " + count + " 个，每个 512 字符，估算 " + ByteSize.human(estimate),
                "UseStringDeduplication=" + enabled,
                "GC 次数增量 " + delta
        ));
        Map<String, Object> metrics = new LinkedHashMap<>(Map.of("count", count, "enabled", enabled, "gcDelta", delta));
        if (stats != null) {
            observations.add("去重统计（一手证据）：new " + stats.newCount() + "/" + stats.newBytesLabel()
                    + "，deduped " + stats.dedupedCount() + "/" + stats.dedupedBytesLabel()
                    + "，平均 " + stats.avgDedupedPercent() + "%，耗时 " + stats.durationMs() + "ms");
            metrics.put("dedupedCount", stats.dedupedCount());
            metrics.put("dedupAvgPercent", stats.avgDedupedPercent());
        } else if (enabled) {
            observations.add("本轮统计行尚未落盘（去重线程异步写出，实测可滞后数十秒）；"
                    + "稍后在 /api/status 的 sideLogs.stringDedup 或日志面板中查看");
        }

        // 构建返回部分结果
        return new ScenarioOutcome(
                (churned + nudgeAllocated) / ByteSize.MB,
                support.retained().usedBytes() / ByteSize.MB,
                observations,
                enabled
                        ? (stats != null
                                ? "去重率 " + stats.avgDedupedPercent() + "% 已写入响应；对照 logs/stringdedup-*.log 的 Concurrent String Deduplication 行。"
                                : "查看 logs/stringdedup-*.log 中的 Concurrent String Deduplication 统计；去重发生在对象达到 StringDeduplicationAgeThreshold 后。")
                        : "当前未开启 -XX:+UseStringDeduplication，场景只会产生普通 Young GC；用 default profile 启动后重试。",
                metrics
        );
    }
}
