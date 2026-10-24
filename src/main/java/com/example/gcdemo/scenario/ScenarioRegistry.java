package com.example.gcdemo.scenario;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 所有 GC 场景的注册表。
 *
 * <p>启动时按 {@link GcScenario#order()} 排序并检查 id 唯一，避免两个场景使用同一个
 * URL。场景列表同时作为前端卡片的数据源。</p>
 */
@Component
public class ScenarioRegistry {

    private final Map<String, GcScenario> scenarios;

    public ScenarioRegistry(List<GcScenario> discovered) {
        List<GcScenario> sorted = new ArrayList<>(discovered);
        sorted.sort(Comparator.comparingInt(GcScenario::order).thenComparing(GcScenario::id));
        Map<String, GcScenario> map = new LinkedHashMap<>();
        for (GcScenario scenario : sorted) {
            GcScenario previous = map.putIfAbsent(scenario.id(), scenario);
            if (previous != null) {
                throw new IllegalStateException("Duplicate scenario id: " + scenario.id());
            }
        }
        this.scenarios = Collections.unmodifiableMap(map);
    }

    /**
     * @return 对应 id 的场景；未知 id 返回 empty，由调用方转换为 404
     */
    public Optional<GcScenario> find(String id) {
        return Optional.ofNullable(scenarios.get(id));
    }

    /**
     * 获取场景，未知 id 抛出带教学提示的 404。
     */
    public GcScenario require(String id) {
        return find(id).orElseThrow(() -> new com.example.gcdemo.error.ApiException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "未知场景: " + id,
                "GET /api/scenarios 可查看全部场景。"));
    }

    public List<ScenarioInfo> infos() {
        return scenarios.values().stream().map(GcScenario::info).toList();
    }

    public List<String> ids() {
        return List.copyOf(scenarios.keySet());
    }
}
