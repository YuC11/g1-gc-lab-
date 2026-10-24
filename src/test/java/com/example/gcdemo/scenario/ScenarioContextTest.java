package com.example.gcdemo.scenario;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ScenarioContext 的预算/取消/进度契约。
 */
class ScenarioContextTest {

    @Test
    void limitClampsRequestedDurationToRemainingBudget() {
        ScenarioContext ctx = new ScenarioContext(10_000);

        assertThat(ctx.limit(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
        // 请求超过剩余预算时夹到剩余值（允许 ±50ms 的时钟粒度）
        assertThat(ctx.limit(Duration.ofMinutes(5)).toMillis())
                .isLessThanOrEqualTo(10_000L)
                .isGreaterThan(9_000L);
    }

    @Test
    void expiredBudgetClampsToZero() throws InterruptedException {
        ScenarioContext ctx = new ScenarioContext(30);
        Thread.sleep(60);

        assertThat(ctx.expired()).isTrue();
        assertThat(ctx.remainingMillis()).isZero();
        assertThat(ctx.limit(Duration.ofSeconds(5))).isEqualTo(Duration.ZERO);
    }

    @Test
    void cancelIsCooperativeAndCarriesPhase() {
        ScenarioContext ctx = new ScenarioContext(60_000);
        ctx.phase("阶段 2/3：制造存活批次", 55);

        assertThat(ctx.isCancelled()).isFalse();
        ctx.cancel();
        assertThat(ctx.isCancelled()).isTrue();

        assertThatThrownBy(ctx::checkCancelled)
                .isInstanceOf(ScenarioCancelledException.class)
                .satisfies(ex -> assertThat(((ScenarioCancelledException) ex).phase())
                        .isEqualTo("阶段 2/3：制造存活批次"));
    }

    @Test
    void progressSnapshotReflectsPhaseAndPercent() {
        ScenarioContext ctx = new ScenarioContext(60_000);
        ctx.phase("工作", 42);

        ScenarioProgress progress = ctx.progress();
        assertThat(progress.phase()).isEqualTo("工作");
        assertThat(progress.percent()).isEqualTo(42);
        assertThat(progress.cancelled()).isFalse();
        assertThat(progress.remainingMs()).isGreaterThan(50_000);
    }
}
