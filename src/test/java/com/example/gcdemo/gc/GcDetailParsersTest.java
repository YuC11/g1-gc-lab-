package com.example.gcdemo.gc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析器 v2 补充行族的契约测试。
 *
 * <p>所有行都取自项目真实 JDK 21 日志（logs/gc-2026-10-22_*.log、
 * docs/samples/stringdedup-2g.log），格式漂移在这里第一时间暴露。</p>
 */
class GcDetailParsersTest {

    private final Instant start = Instant.parse("2026-10-22T03:00:00Z");
    private final GcLogParser parser = new GcLogParser();

    private ParsedLine parse(String line) {
        Optional<ParsedLine> parsed = parser.parseLine(line, start);
        assertThat(parsed).as("line should match a known family: " + line).isPresent();
        return parsed.get();
    }

    // ---- info 级：Region 转移 ----

    @Test
    void parsesEdenRegionLineWithTarget() {
        ParsedLine parsed = parse("[2026-10-22T11:55:00.512+0800][0.512s][info][gc,heap] GC(0) Eden regions: 75->0(199)");
        assertThat(parsed).isInstanceOf(ParsedLine.EventDetail.class);
        GcEvent enriched = apply(parsed, gcEvent(0));
        assertThat(enriched.regionTransitions().edenBefore()).isEqualTo(75);
        assertThat(enriched.regionTransitions().edenAfter()).isEqualTo(0);
        assertThat(enriched.regionTransitions().edenTarget()).isEqualTo(199);
    }

    @Test
    void parsesSurvivorOldAndHumongousRegionLines() {
        GcEvent event = gcEvent(21);
        event = apply(parse("[info ][gc,heap] GC(21) Survivor regions: 12->8(26)"), event);
        event = apply(parse("[info ][gc,heap] GC(21) Old regions: 96->96"), event);
        event = apply(parse("[info ][gc,heap] GC(21) Humongous regions: 96->48"), event);

        assertThat(event.regionTransitions().survivorBefore()).isEqualTo(12);
        assertThat(event.regionTransitions().survivorTarget()).isEqualTo(26);
        assertThat(event.regionTransitions().oldAfter()).isEqualTo(96);
        // Old/Humongous 无目标值
        assertThat(event.regionTransitions().oldBefore()).isEqualTo(96);
        assertThat(event.regionTransitions().humongousBefore()).isEqualTo(96);
        assertThat(event.regionTransitions().humongousAfter()).isEqualTo(48);
    }

    // ---- info 级：并发周期（独立事件） ----

    @Test
    void parsesConcurrentPhaseDurationsAsStandaloneEvents() {
        ParsedLine markCycle = parse("[2026-10-22T11:55:01.123+0800][1.123s][info][gc] GC(1) Concurrent Mark Cycle 10.525ms");
        assertThat(markCycle).isInstanceOf(ParsedLine.StandaloneEvent.class);
        GcEvent event = ((ParsedLine.StandaloneEvent) markCycle).event();
        assertThat(event.type()).isEqualTo("Concurrent");
        assertThat(event.cause()).isEqualTo("Mark Cycle");
        assertThat(event.pauseMs()).isEqualTo(10.525);
        assertThat(event.gcId()).isEqualTo(1);

        assertThat(parse("[info ][gc,marking] GC(1) Concurrent Scan Root Regions 1.963ms"))
                .isInstanceOf(ParsedLine.StandaloneEvent.class);
        assertThat(parse("[info ][gc,marking] GC(1) Concurrent Clear Claimed Marks 0.034ms"))
                .isInstanceOf(ParsedLine.StandaloneEvent.class);
        assertThat(parse("[info ][gc,marking] GC(1) Concurrent Cleanup for Next Mark 3.954ms"))
                .isInstanceOf(ParsedLine.StandaloneEvent.class);
    }

    @Test
    void concurrentStartLinesWithoutDurationAreIgnored() {
        // 无耗时的起始行不产生事件（有耗时的完成行才有）
        assertThat(parser.parseLine("[info ][gc] GC(1) Concurrent Mark Cycle", start)).isEmpty();
    }

    // ---- info 级：阶段分解 ----

    @Test
    void parsesPausePhaseTotals() {
        GcEvent event = gcEvent(0);
        event = apply(parse("[info ][gc,phases] GC(0)   Pre Evacuate Collection Set: 0.2ms"), event);
        event = apply(parse("[info ][gc,phases] GC(0)   Evacuate Collection Set: 3.1ms"), event);
        event = apply(parse("[info ][gc,phases] GC(0)   Post Evacuate Collection Set: 0.8ms"), event);
        event = apply(parse("[info ][gc,phases] GC(0)   Merge Heap Roots: 0.1ms"), event);

        assertThat(event.phases()).containsEntry("Pre Evacuate Collection Set", 0.2);
        assertThat(event.phases()).containsEntry("Evacuate Collection Set", 3.1);
        assertThat(event.phases()).containsEntry("Post Evacuate Collection Set", 0.8);
        assertThat(event.phases()).containsEntry("Merge Heap Roots", 0.1);
    }

    @Test
    void parsesFullGcPhases() {
        GcEvent event = apply(parse("[info ][gc,phases] GC(31) Phase 1: Mark live objects 9.591ms"), gcEvent(31));
        event = apply(parse("[info ][gc,phases] GC(31) Phase 4: Compact heap 0.910ms"), event);

        assertThat(event.phases()).containsEntry("Phase 1: Mark live objects", 9.591);
        assertThat(event.phases()).containsEntry("Phase 4: Compact heap", 0.910);
    }

    // ---- info 级：CPU 与 Metaspace ----

    @Test
    void parsesCpuParallelismLine() {
        GcEvent event = apply(parse("[info ][gc,cpu] GC(0) User=0.03s Sys=0.01s Real=0.01s"), gcEvent(0));
        assertThat(event.cpu()).isEqualTo(new GcEvent.CpuTimes(0.03, 0.01, 0.01));
    }

    @Test
    void parsesMetaspaceChangeLine() {
        ParsedLine parsed = parse("[info ][gc,metaspace] GC(0) Metaspace: 21154K(21568K)->21154K(21568K) NonClass: 18446K(18688K)->18446K(18688K) Class: 2707K(2880K)->2707K(2880K)");
        GcEvent event = apply(parsed, gcEvent(0));
        assertThat(event.metaspace().usedBeforeBytes()).isEqualTo(21154L * 1024);
        assertThat(event.metaspace().usedAfterBytes()).isEqualTo(21154L * 1024);
        assertThat(event.metaspace().committedAfterBytes()).isEqualTo(21568L * 1024);
    }

    // ---- verbose 级（gc*=debug）----

    @Test
    void parsesTenuringThreshold() {
        GcEvent event = apply(parse("[debug][gc,age] GC(0) Desired survivor size 13631488 bytes, new threshold 3 (max threshold 3)"), gcEvent(0));
        assertThat(event.details()).containsEntry("tenuringThreshold", "3 (max 3)");
    }

    @Test
    void parsesAdaptiveIhop() {
        GcEvent event = apply(parse("[debug][gc,ihop] GC(0) Adaptive IHOP information (value update), threshold: 1288490188B (35.29), internal target occupancy: 3650722201B, occupancy: 10005688B, additional buffer size: 427819008B, predicted old gen allocation rate: 0.00B/s, predicted marking phase length: 1589.78ms, prediction active: false"), gcEvent(0));
        assertThat(event.details()).containsEntry("ihopThresholdBytes", "1288490188");
        assertThat(event.details()).containsEntry("ihopOccupancyPercent", "35.29");
        assertThat(event.details()).containsEntry("ihopMarkingPhaseMs", "1589.78");
        assertThat(event.details()).containsEntry("ihopPredictionActive", "false");
    }

    @Test
    void parsesErgoDecisions() {
        GcEvent mixed = apply(parse("[debug][gc,ergo] GC(3) do not continue mixed GCs (candidate old regions not available)"), gcEvent(3));
        assertThat(mixed.details()).containsEntry("ergo", "do not continue mixed GCs (candidate old regions not available)");
    }

    @Test
    void parsesReferenceProcessingStats() {
        GcEvent event = apply(parse("[debug][gc,phases,ref] GC(0)       PhantomReference Discovered: 3320, Dropped: 251, Processed: 3069"), gcEvent(0));
        assertThat(event.details()).containsEntry("phantomRef", "Discovered 3320, Dropped 251, Processed 3069");
    }

    @Test
    void parsesHumongousReclaimed() {
        GcEvent event = apply(parse("[debug][gc,phases] GC(21)         Humongous Reclaimed            Min: 16, Avg: 16.0, Max: 16, Diff: 0, Sum: 16, Workers: 1"), gcEvent(21));
        assertThat(event.details()).containsEntry("humongousReclaimedRegions", "16");
    }

    @Test
    void parsesPlabStats() {
        GcEvent event = apply(parse("[debug][gc,plab] GC(0) Young PLAB allocation: allocated: 8748648B, wasted: 18584B, unused: 509768B, used: 8220296B, undo waste: 0B,"), gcEvent(0));
        assertThat(event.details()).containsEntry("youngPlabAllocatedBytes", "8748648");
        assertThat(event.details()).containsEntry("youngPlabUsedBytes", "8220296");
    }

    @Test
    void parsesCardAndRefinementStats() {
        GcEvent event = gcEvent(0);
        event = apply(parse("[debug][gc,phases] GC(0)       Dirty Cards:                   Min: 0, Avg: 60.1, Max: 256, Diff: 256, Sum: 541, Workers: 9"), event);
        event = apply(parse("[debug][gc,refine,stats] GC(0) Total refinement: 0.00ms, refined: 0, precleaned: 0, dirtied: 541"), event);

        assertThat(event.details()).containsEntry("dirtyCards", "541");
        assertThat(event.details()).containsEntry("dirtied", "541");
    }

    // ---- 不属于任何家族的行 ----

    @Test
    void unknownLinesReturnEmpty() {
        assertThat(parser.parseLine("[0.036s][info][gc] GC(0) Heap Region Size 1M", start)).isEmpty();
        assertThat(parser.parseLine("[1.234s][debug][gc,stats] Marking Stats, task = 0, calls = 10", start)).isEmpty();
        assertThat(parser.parseLine("not a gc line at all", start)).isEmpty();
    }

    // ---- helpers ----

    private GcEvent gcEvent(int gcId) {
        return GcEvent.basic(0, gcId, "Young", "test", 0, 0, 0, 0, gcId, start, "gc-log", "GC(" + gcId + ")");
    }

    private GcEvent apply(ParsedLine parsed, GcEvent current) {
        assertThat(parsed).isInstanceOf(ParsedLine.EventDetail.class);
        ParsedLine.EventDetail detail = (ParsedLine.EventDetail) parsed;
        return detail.applier().apply(current);
    }
}
