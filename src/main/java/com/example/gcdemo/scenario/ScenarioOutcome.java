package com.example.gcdemo.scenario;

import java.util.List;
import java.util.Map;

/**
 * 场景实现层返回的原始观察结果。
 *
 * <p>由 {@link ScenarioRunner} 补充 GC 前后计数、耗时和事件类型后转换为
 * {@link ScenarioResult}。这里不包含 HTTP 相关字段，便于直接单测。</p>
 */
public record ScenarioOutcome(
        long allocatedMb,
        long retainedMb,
        List<String> observations,
        String hint,
        Map<String, Object> metrics
) {
}
