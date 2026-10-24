package com.example.gcdemo.scenario;

import java.util.Map;

/**
 * 场景参数：按场景自己的 ParamSpec 命名，Runner 已按 min/max 严格校验。
 *
 * <p>取代旧的 9 个通用字段跨场景复用模型（旧模型里 mixed-gc 的 {@code count}
 * 实际表示“追加压力 MB”）。数值参数放在 {@code values}；少量非数值开关放在
 * {@code flags}（目前只有 reset 的 {@code gc=false}），flags 不做范围校验，
 * 由使用它的场景自行解释。</p>
 */
public record ScenarioParams(Map<String, Integer> values, Map<String, String> flags) {

    public ScenarioParams {
        values = values == null ? Map.of() : Map.copyOf(values);
        flags = flags == null ? Map.of() : Map.copyOf(flags);
    }

    /**
     * @return 原始数值；参数未提供时为 null，由场景套用默认值
     */
    public Integer get(String name) {
        return values.get(name);
    }

    /**
     * @return 参数值；未提供时返回 defaultValue
     */
    public int get(String name, int defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    /**
     * @return 非数值开关；未提供时为 null
     */
    public String flag(String name) {
        return flags.get(name);
    }

    public static ScenarioParams empty() {
        return new ScenarioParams(Map.of(), Map.of());
    }
}
