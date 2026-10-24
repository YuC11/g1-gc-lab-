package com.example.gcdemo.scenario;

/**
 * 场景参数元数据，驱动前端动态渲染输入框。
 *
 * @param name         与 {@link ScenarioRequest} 字段名一致
 * @param defaultValue 按当前堆和 Region 推导的建议值
 * @param unit         MB/KB/ms/个，仅用于显示
 */
public record ParamSpec(
        String name,
        String label,
        int defaultValue,
        int min,
        int max,
        String unit
) {
}
