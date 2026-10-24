package com.example.gcdemo.scenario;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证场景注册表的排序、唯一性和 id 查询。
 */
class ScenarioRegistryTest {

    @Test
    void sortsScenariosByOrderAndRejectsDuplicates() {
        ScenarioRegistry registry = new ScenarioRegistry(List.of(
                scenario("late", 20),
                scenario("early", 10)
        ));

        assertThat(registry.ids()).containsExactly("early", "late");
        assertThat(registry.infos()).extracting(ScenarioInfo::id).containsExactly("early", "late");

        assertThatThrownBy(() -> new ScenarioRegistry(List.of(
                scenario("same", 1),
                scenario("same", 2)
        ))).isInstanceOf(IllegalStateException.class);
    }

    private GcScenario scenario(String id, int order) {
        return new GcScenario() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public ScenarioInfo info() {
                return new ScenarioInfo(id, id, "test", "low", false, List.of(), List.of());
            }

            @Override
            public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
                return new ScenarioOutcome(0, 0, List.of(), "ok", Map.of());
            }
        };
    }
}
