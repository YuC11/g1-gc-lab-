package com.example.gcdemo.scenario;

import com.example.gcdemo.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runner 的参数校验契约：按 ParamSpec 严格校验，未知/空值/越界都是 400 带教学提示。
 */
class ScenarioParamsValidationTest {

    private final GcScenario stub = new GcScenario() {
        @Override
        public String id() {
            return "stub";
        }

        @Override
        public ScenarioInfo info() {
            return new ScenarioInfo(id(), "stub", "", "低", false,
                    List.of(
                            new ParamSpec("deadMb", "将死批次", 256, 64, 1024, "MB"),
                            new ParamSpec("liveMb", "存活批次", 128, 32, 512, "MB")
                    ),
                    List.of());
        }

        @Override
        public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
            return new ScenarioOutcome(0, 0, List.of(), "", Map.of());
        }
    };

    @Test
    void acceptsKnownParamsInRangeAndPassesThrough() {
        Map<String, Integer> validated = ScenarioRunner.validate(stub,
                Map.of("deadMb", 300, "liveMb", 64));

        assertThat(validated).containsEntry("deadMb", 300).containsEntry("liveMb", 64);
    }

    @Test
    void nullAndEmptyBodiesYieldEmptyParams() {
        assertThat(ScenarioRunner.validate(stub, null)).isEmpty();
        assertThat(ScenarioRunner.validate(stub, Map.of())).isEmpty();
    }

    @Test
    void unknownParamIsRejectedWithHintListingValidNames() {
        assertThatThrownBy(() -> ScenarioRunner.validate(stub, Map.of("count", 10)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("count")
                .hasMessageContaining("stub")
                .satisfies(ex -> assertThat(((ApiException) ex).getHint()).contains("deadMb", "liveMb"));
    }

    @Test
    void outOfRangeParamIsRejectedWithRange() {
        assertThatThrownBy(() -> ScenarioRunner.validate(stub, Map.of("deadMb", 2000)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("越界")
                .satisfies(ex -> assertThat(((ApiException) ex).getHint()).contains("64").contains("1024"));
    }

    @Test
    void nullValueIsRejected() {
        Map<String, Integer> withNull = new java.util.HashMap<>();
        withNull.put("deadMb", null);
        assertThatThrownBy(() -> ScenarioRunner.validate(stub, withNull))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("缺少数值");
    }
}
