package com.example.gcdemo.gc;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pause 汇总行之外的 GC 日志补充行解析（v2）。
 *
 * <p>每个 Pattern 都以项目真实 JDK 21 日志（logs/、docs/samples/）中的原行校准，
 * 不发明格式。info 级（默认 profile）可解析：Region 转移、并发周期、暂停阶段
 * 分解、gc,cpu、gc,metaspace；verbose 级（gc*=debug）额外解析：自适应 IHOP、
 * ergo 决策、tenuring 阈值、引用处理统计、Humongous Reclaimed、PLAB、
 * 卡表 Merged/Skipped/Dirty、refinement 统计。</p>
 *
 * <p>JDK 21 中不存在这些行名（不要去匹配）：Concurrent Complete Cleanup、
 * Concurrent Undo Cycle、SATB Filtering、字面的 Soft/Phantom Processing、
 * Free CSet、Per GC Thread。</p>
 */
final class GcDetailParsers {

    /**
     * 行型锚定 GC(n) 之后的内容，不依赖装饰器组合（有无 utctime/uptime/level/tags
     * 均可匹配）；只有独立成事件的并发阶段行需要 uptime 换算时间戳，缺失时退化为当前时间。
     */
    private static final Pattern UPTIME = Pattern.compile("\\[(\\d+(?:\\.\\d+)?)s]");

    // ---- info 级 ----

    /** 例：GC(0) Eden regions: 75->0(199)；Old/Humongous 无 (target)。 */
    private static final Pattern REGION = Pattern.compile(
            "\\bGC\\((\\d+)\\) (Eden|Survivor|Old|Humongous) regions: "
                    + "(\\d+)->(\\d+)(?:\\((\\d+)\\))?\\s*$");

    /** 例：GC(1) Concurrent Mark Cycle 10.525ms（另有不带耗时的 start 行，忽略）。 */
    private static final Pattern CONCURRENT = Pattern.compile(
            "\\bGC\\((\\d+)\\) Concurrent "
                    + "(Mark Cycle|Mark From Roots|Scan Root Regions|Clear Claimed Marks|Cleanup for Next Mark) "
                    + "([0-9.]+)ms\\s*$");

    /** 例：GC(0)   Pre Evacuate Collection Set: 0.2ms（缩进不定；Other 为收尾杂项）。 */
    private static final Pattern PHASE_TOTAL = Pattern.compile(
            "\\bGC\\((\\d+)\\)\\s+"
                    + "(Pre Evacuate Collection Set|Evacuate Collection Set|Post Evacuate Collection Set"
                    + "|Merge Heap Roots|Other): ([0-9.]+)ms\\s*$");

    /** 例：GC(31) Phase 1: Mark live objects 9.591ms。 */
    private static final Pattern FULL_PHASE = Pattern.compile(
            "\\bGC\\((\\d+)\\) Phase (\\d): (.+?) ([0-9.]+)ms\\s*$");

    /** 例：GC(0) User=0.03s Sys=0.01s Real=0.01s。 */
    private static final Pattern CPU = Pattern.compile(
            "\\bGC\\((\\d+)\\) User=([0-9.]+)s Sys=([0-9.]+)s Real=([0-9.]+)s");

    /** 例：GC(0) Metaspace: 21154K(21568K)->21154K(21568K) NonClass: ...。 */
    private static final Pattern METASPACE = Pattern.compile(
            "\\bGC\\((\\d+)\\) Metaspace: "
                    + "(\\d+)([KMG])\\(\\d+[KMG]\\)->(\\d+)([KMG])\\((\\d+)([KMG])\\)");

    // ---- verbose 级（gc*=debug）----

    /** 例：GC(0) Desired survivor size 13631488 bytes, new threshold 3 (max threshold 3)。 */
    private static final Pattern TENURING = Pattern.compile(
            "\\bGC\\((\\d+)\\) Desired survivor size \\d+ bytes, "
                    + "new threshold (\\d+) \\(max threshold (\\d+)\\)");

    /** 例：GC(0) Adaptive IHOP information (value update), threshold: 1288490188B (35.29), ...。 */
    private static final Pattern IHOP = Pattern.compile(
            "\\bGC\\((\\d+)\\) Adaptive IHOP information \\([^)]+\\), "
                    + "threshold: (\\d+)B \\((\\d+\\.\\d+)\\).*"
                    + "predicted marking phase length: ([0-9.]+)ms, prediction active: (\\w+)");

    /** 例：GC(3) do not continue mixed GCs (candidate old regions not available)。 */
    private static final Pattern ERGO = Pattern.compile(
            "\\bGC\\((\\d+)\\) (Initiate concurrent cycle [^.]*\\.?|"
                    + "do not continue mixed GCs[^.]*\\.?|Request concurrent cycle initiation[^.]*\\.?)\\s*$");

    /** 例：GC(0)       PhantomReference Discovered: 3320, Dropped: 251, Processed: 3069。 */
    private static final Pattern REF_STATS = Pattern.compile(
            "\\bGC\\((\\d+)\\)\\s+(Soft|Weak|Phantom|Final)Reference "
                    + "Discovered: (\\d+), Dropped: (\\d+), Processed: (\\d+)");

    /** 例：GC(21)         Humongous Reclaimed            Min: 16, ..., Sum: 16, Workers: 1。 */
    private static final Pattern HUMONGOUS_RECLAIMED = Pattern.compile(
            "\\bGC\\((\\d+)\\)\\s+Humongous Reclaimed\\s+.*Sum: (\\d+), Workers: \\d+");

    /** 例：GC(0) Young PLAB allocation: allocated: 8748648B, wasted: 18584B, unused: 509768B, used: 8220296B, ...。 */
    private static final Pattern PLAB = Pattern.compile(
            "\\bGC\\((\\d+)\\) (Young|Old) PLAB allocation: "
                    + "allocated: (\\d+)B, wasted: (\\d+)B, unused: (\\d+)B, used: (\\d+)B");

    /** 例：GC(0)       Merged Cards: ... Sum: 0, ...（Skipped/Dirty 同型）。 */
    private static final Pattern CARDS = Pattern.compile(
            "\\bGC\\((\\d+)\\)\\s+(Merged|Skipped|Dirty) Cards:\\s+.*Sum: (\\d+), Workers: \\d+");

    /** 例：GC(0) Mutator refinement: 0.00ms, refined: 0, precleaned: 0, dirtied: 541（Total 同型）。 */
    private static final Pattern REFINE = Pattern.compile(
            "\\bGC\\((\\d+)\\) (?:Mutator|Total) refinement: "
                    + "([0-9.]+)ms, refined: (\\d+), precleaned: (\\d+), dirtied: (\\d+)");

    private GcDetailParsers() {
    }

    /**
     * @return 补充行的归类；不属于任何已知家族时为 empty
     */
    static Optional<ParsedLine> parse(String line, Instant processStart) {
        if (line == null || line.isBlank() || !line.contains("GC(")) {
            return Optional.empty();
        }

        Matcher region = REGION.matcher(line);
        if (region.find()) {
            long gcId = Long.parseLong(region.group(1));
            String kind = region.group(2);
            int before = Integer.parseInt(region.group(3));
            int after = Integer.parseInt(region.group(4));
            int target = region.group(5) == null ? -1 : Integer.parseInt(region.group(5));
            return Optional.of(new ParsedLine.EventDetail(gcId,
                    event -> event.withRegionLine(kind, before, after, target)));
        }

        Matcher concurrent = CONCURRENT.matcher(line);
        if (concurrent.find()) {
            long gcId = Long.parseLong(concurrent.group(1));
            String phase = concurrent.group(2);
            double durationMs = Double.parseDouble(concurrent.group(3));
            return Optional.of(new ParsedLine.StandaloneEvent(GcEvent.basic(
                    0, gcId, "Concurrent", phase, 0, 0, 0, durationMs, 0,
                    timestamp(line, processStart), "gc-log", line.strip())));
        }

        Matcher phaseTotal = PHASE_TOTAL.matcher(line);
        if (phaseTotal.find()) {
            long gcId = Long.parseLong(phaseTotal.group(1));
            String name = phaseTotal.group(2);
            double ms = Double.parseDouble(phaseTotal.group(3));
            return Optional.of(detail(gcId, event -> event.withPhase(name, ms)));
        }

        Matcher fullPhase = FULL_PHASE.matcher(line);
        if (fullPhase.find()) {
            long gcId = Long.parseLong(fullPhase.group(1));
            String name = "Phase " + fullPhase.group(2) + ": " + fullPhase.group(3).strip();
            double ms = Double.parseDouble(fullPhase.group(4));
            return Optional.of(detail(gcId, event -> event.withPhase(name, ms)));
        }

        Matcher cpu = CPU.matcher(line);
        if (cpu.find()) {
            long gcId = Long.parseLong(cpu.group(1));
            GcEvent.CpuTimes times = new GcEvent.CpuTimes(
                    Double.parseDouble(cpu.group(2)),
                    Double.parseDouble(cpu.group(3)),
                    Double.parseDouble(cpu.group(4)));
            return Optional.of(detail(gcId, event -> event.withCpu(times)));
        }

        Matcher metaspace = METASPACE.matcher(line);
        if (metaspace.find()) {
            long gcId = Long.parseLong(metaspace.group(1));
            GcEvent.MetaspaceChange change = new GcEvent.MetaspaceChange(
                    toBytes(metaspace.group(2), metaspace.group(3)),
                    toBytes(metaspace.group(4), metaspace.group(5)),
                    toBytes(metaspace.group(6), metaspace.group(7)));
            return Optional.of(detail(gcId, event -> event.withMetaspace(change)));
        }

        return parseVerboseDetails(line, processStart);
    }

    private static Optional<ParsedLine> parseVerboseDetails(String line, Instant processStart) {
        Matcher tenuring = TENURING.matcher(line);
        if (tenuring.find()) {
            long gcId = Long.parseLong(tenuring.group(1));
            String value = tenuring.group(2) + " (max " + tenuring.group(3) + ")";
            return Optional.of(detail(gcId, event -> event.withDetail("tenuringThreshold", value)));
        }

        Matcher ihop = IHOP.matcher(line);
        if (ihop.find()) {
            long gcId = Long.parseLong(ihop.group(1));
            String thresholdBytes = ihop.group(2);
            String occupancyPercent = ihop.group(3);
            String markingMs = ihop.group(4);
            String active = ihop.group(5);
            return Optional.of(detail(gcId, event -> event
                    .withDetail("ihopThresholdBytes", thresholdBytes)
                    .withDetail("ihopOccupancyPercent", occupancyPercent)
                    .withDetail("ihopMarkingPhaseMs", markingMs)
                    .withDetail("ihopPredictionActive", active)));
        }

        Matcher ergo = ERGO.matcher(line);
        if (ergo.find()) {
            long gcId = Long.parseLong(ergo.group(1));
            String decision = ergo.group(2).strip();
            return Optional.of(detail(gcId, event -> event.withDetail("ergo", decision)));
        }

        Matcher refs = REF_STATS.matcher(line);
        if (refs.find()) {
            long gcId = Long.parseLong(refs.group(1));
            String kind = refs.group(2).toLowerCase();
            String value = "Discovered " + refs.group(3) + ", Dropped " + refs.group(4)
                    + ", Processed " + refs.group(5);
            return Optional.of(detail(gcId, event -> event.withDetail(kind + "Ref", value)));
        }

        Matcher humongous = HUMONGOUS_RECLAIMED.matcher(line);
        if (humongous.find()) {
            long gcId = Long.parseLong(humongous.group(1));
            return Optional.of(detail(gcId, event ->
                    event.withDetail("humongousReclaimedRegions", humongous.group(2))));
        }

        Matcher plab = PLAB.matcher(line);
        if (plab.find()) {
            long gcId = Long.parseLong(plab.group(1));
            String gen = plab.group(2).toLowerCase();
            return Optional.of(detail(gcId, event -> event
                    .withDetail(gen + "PlabAllocatedBytes", plab.group(3))
                    .withDetail(gen + "PlabWastedBytes", plab.group(4))
                    .withDetail(gen + "PlabUsedBytes", plab.group(6))));
        }

        Matcher cards = CARDS.matcher(line);
        if (cards.find()) {
            long gcId = Long.parseLong(cards.group(1));
            String kind = cards.group(2).toLowerCase();
            return Optional.of(detail(gcId, event -> event.withDetail(kind + "Cards", cards.group(3))));
        }

        Matcher refine = REFINE.matcher(line);
        if (refine.find()) {
            long gcId = Long.parseLong(refine.group(1));
            return Optional.of(detail(gcId, event -> event
                    .withDetail("refined", refine.group(3))
                    .withDetail("precleaned", refine.group(4))
                    .withDetail("dirtied", refine.group(5))));
        }

        return Optional.empty();
    }

    private static ParsedLine.EventDetail detail(long gcId, java.util.function.UnaryOperator<GcEvent> applier) {
        return new ParsedLine.EventDetail(gcId, applier);
    }

    /**
     * 并发事件的时间戳：优先用行内 uptime 装饰器换算；无 uptime 时退化为当前时间
     * （补充行不需要时间戳，只有独立事件走到这里）。
     */
    private static Instant timestamp(String line, Instant processStart) {
        if (processStart == null) {
            return Instant.now();
        }
        Matcher uptime = UPTIME.matcher(line);
        if (!uptime.find()) {
            return Instant.now();
        }
        return processStart.plusMillis((long) (Double.parseDouble(uptime.group(1)) * 1000L));
    }

    private static long toBytes(String value, String unit) {
        long number = Long.parseLong(value);
        return switch (unit) {
            case "K" -> number * 1024L;
            case "M" -> number * 1024L * 1024L;
            case "G" -> number * 1024L * 1024L * 1024L;
            default -> number;
        };
    }
}
