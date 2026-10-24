package com.example.gcdemo.scenario;

import java.util.List;
import java.util.Map;

/**
 * 场景执行后的完整响应。
 *
 * <p>{@code observedEventTypes} 来自 GC 日志/事件存储，是判断 Mixed、Full 是否真的
 * 发生的关键证据；{@code hint} 提供下一步观察与调优建议。{@code cancelled=true}
 * 表示场景在协作式取消检查点中止，elapsed/gcDelta 等字段是中止前的部分证据。</p>
 */
public record ScenarioResult(
        String scenario,
        String title,
        long elapsedMs,
        long allocatedMb,
        long retainedMb,
        long gcCountBefore,
        long gcCountAfter,
        long gcDelta,
        List<String> observedEventTypes,
        List<String> observations,
        String hint,
        Map<String, Object> metrics,
        boolean cancelled
) {
}
