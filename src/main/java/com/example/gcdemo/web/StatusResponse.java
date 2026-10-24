package com.example.gcdemo.web;

import com.example.gcdemo.gc.GcEvent;
import com.example.gcdemo.gc.GcStats;
import com.example.gcdemo.gc.HeapSnapshot;
import com.example.gcdemo.gc.JvmInfo;
import com.example.gcdemo.gc.LogWatchStatus;
import com.example.gcdemo.gc.SideLogWatchers;
import com.example.gcdemo.scenario.ScenarioProgress;
import com.example.gcdemo.scenario.ScenarioResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/status} 的聚合响应。
 *
 * <p>把 JVM 参数、堆快照、GC 统计、retained、最近事件和日志 watcher 状态一次返回，
 * 减少前端轮询请求数。{@code lastScenario} 只保存最近一次结果；完整历史见
 * {@code /api/scenarios/history}。{@code scenarioProgress} 在场景运行时非空；
 * {@code sideLogs} 是 stringdedup / class-unload 侧日志的一手证据摘要。</p>
 */
public record StatusResponse(
        JvmInfo jvm,
        HeapSnapshot heap,
        GcStats gc,
        RetainedInfo retained,
        List<GcEvent> events,
        LogWatchStatus logWatcher,
        boolean scenarioRunning,
        ScenarioResult lastScenario,
        ScenarioProgress scenarioProgress,
        SideLogs sideLogs,
        Instant serverTime
) {
    public record RetainedInfo(long usedBytes, Map<String, Long> entries) {
    }

    /**
     * 侧日志证据：最新一条去重统计（可能为 null）与累计类卸载数。
     */
    public record SideLogs(
            SideLogWatchers.StringDedupStats stringDedup,
            long unloadedClasses,
            Instant lastUnloadAt
    ) {
    }
}
