package com.example.gcdemo.gc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GcCycleAggregator 的聚合契约：汇总行创建 pending、补充行按 gcId 附加、
 * 下一个 GC 出现或 flush 时定稿、并发阶段独立放行。
 */
class GcCycleAggregatorTest {

    private final Instant start = Instant.parse("2026-10-22T03:00:00Z");
    private final GcCycleAggregator aggregator = new GcCycleAggregator();

    private GcEvent pause(int gcId, String type) {
        return GcEvent.basic(0, gcId, type, "test", 100, 50, 2048, 5.0, gcId,
                start.plusSeconds(gcId), "gc-log", "GC(" + gcId + ") ...");
    }

    @Test
    void detailsArrivingBeforeSummaryAreReplayedOntoIt() {
        // JDK 21 真实行序：补充行先于 pause 汇总行出现（gc,phases/gc,heap → 汇总 → gc,cpu）。
        aggregator.offer(new ParsedLine.EventDetail(10, event -> event.withPhase("Evacuate Collection Set", 4.0)));
        aggregator.offer(new ParsedLine.EventDetail(10, event -> event.withRegionLine("Eden", 194, 0, 213)));
        aggregator.offer(new ParsedLine.EventDetail(10, event -> event.withMetaspace(new GcEvent.MetaspaceChange(1, 1, 1))));
        aggregator.offer(new ParsedLine.PauseEvent(pause(10, "Young")));
        aggregator.offer(new ParsedLine.EventDetail(10, event -> event.withCpu(new GcEvent.CpuTimes(0.03, 0.01, 0.0))));

        GcEvent finalized = aggregator.flush().getFirst();
        assertThat(finalized.gcId()).isEqualTo(10);
        assertThat(finalized.phases()).containsEntry("Evacuate Collection Set", 4.0);
        assertThat(finalized.regionTransitions().edenBefore()).isEqualTo(194);
        assertThat(finalized.regionTransitions().edenTarget()).isEqualTo(213);
        assertThat(finalized.metaspace()).isNotNull();
        assertThat(finalized.cpu()).isNotNull();
    }

    @Test
    void orphanBufferIsBoundedAndClearedOnFlush() {
        for (int i = 1; i <= 40; i++) {
            int gcId = i;
            aggregator.offer(new ParsedLine.EventDetail(gcId, event -> event.withDetail("ergo", "x" + gcId)));
        }
        // 上限 16 个 gcId：早期 orphan 已被驱逐，不影响后续
        assertThat(aggregator.flush()).isEmpty();
        // flush 后 orphan 清空：GC(1) 的汇总行不会回填被驱逐的细节
        aggregator.offer(new ParsedLine.PauseEvent(pause(1, "Young")));
        GcEvent finalized = aggregator.flush().getFirst();
        assertThat(finalized.details()).isNull();
    }

    @Test
    void pauseLineCreatesPendingAndNextGcFinalizesIt() {
        assertThat(aggregator.offer(new ParsedLine.PauseEvent(pause(1, "Young")))).isEmpty();
        assertThat(aggregator.hasPending()).isTrue();

        List<GcEvent> finalized = aggregator.offer(new ParsedLine.PauseEvent(pause(2, "Young")));
        assertThat(finalized).hasSize(1);
        assertThat(finalized.getFirst().gcId()).isEqualTo(1);
        assertThat(aggregator.hasPending()).isTrue(); // GC(2) 现在是新的 pending
    }

    @Test
    void detailsAttachToMatchingGcOnly() {
        aggregator.offer(new ParsedLine.PauseEvent(pause(1, "Young")));
        // gcId 匹配：附加
        aggregator.offer(new ParsedLine.EventDetail(1, event -> event.withPhase("Evacuate Collection Set", 3.1)));
        aggregator.offer(new ParsedLine.EventDetail(1, event -> event.withCpu(new GcEvent.CpuTimes(0.03, 0.01, 0.01))));
        // gcId 不匹配（顺序错乱）：静默丢弃
        aggregator.offer(new ParsedLine.EventDetail(99, event -> event.withDetail("ergo", "should be dropped")));

        GcEvent finalized = aggregator.flush().getFirst();
        assertThat(finalized.phases()).containsEntry("Evacuate Collection Set", 3.1);
        assertThat(finalized.cpu()).isEqualTo(new GcEvent.CpuTimes(0.03, 0.01, 0.01));
        assertThat(finalized.details()).isNull();
    }

    @Test
    void multiplePhasesAndDetailsAccumulate() {
        aggregator.offer(new ParsedLine.PauseEvent(pause(3, "Full")));
        aggregator.offer(new ParsedLine.EventDetail(3, event -> event.withPhase("Phase 1: Mark live objects", 9.591)));
        aggregator.offer(new ParsedLine.EventDetail(3, event -> event.withPhase("Phase 4: Compact heap", 0.910)));
        aggregator.offer(new ParsedLine.EventDetail(3, event -> event.withDetail("ergo", "do not continue mixed GCs")));

        GcEvent finalized = aggregator.flush().getFirst();
        assertThat(finalized.phases()).hasSize(2);
        assertThat(finalized.details()).containsEntry("ergo", "do not continue mixed GCs");
    }

    @Test
    void concurrentPhasesPassThroughImmediately() {
        aggregator.offer(new ParsedLine.PauseEvent(pause(1, "Concurrent Start")));
        GcEvent concurrent = GcEvent.basic(0, 1, "Concurrent", "Mark Cycle", 0, 0, 0, 10.5, 1,
                start, "gc-log", "GC(1) Concurrent Mark Cycle 10.5ms");

        List<GcEvent> out = aggregator.offer(new ParsedLine.StandaloneEvent(concurrent));

        // 独立事件立即返回，不影响 pending 的 Concurrent Start 暂停事件
        assertThat(out).hasSize(1);
        assertThat(out.getFirst().type()).isEqualTo("Concurrent");
        assertThat(aggregator.hasPending()).isTrue();
        assertThat(aggregator.flush().getFirst().type()).isEqualTo("Concurrent Start");
    }

    @Test
    void flushIsIdempotentAndEmptyAfterwards() {
        aggregator.offer(new ParsedLine.PauseEvent(pause(7, "Young")));
        assertThat(aggregator.flush()).hasSize(1);
        assertThat(aggregator.flush()).isEmpty();
        assertThat(aggregator.hasPending()).isFalse();
    }
}
