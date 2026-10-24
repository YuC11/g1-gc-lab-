package com.example.gcdemo.gc;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 JDK 21 unified GC log 的 pause 汇总行解析为 {@link GcEvent}。
 *
 * <p>只接受同时包含 {@code GC(n)}、pause 类型、{@code before->after(capacity)} 和
 * {@code x.xxx ms} 的行。{@code gc,start} 行没有堆与耗时数据，必须忽略，否则一次 GC
 * 会被重复计数。</p>
 *
 * <p>类型分类比正则更重要：G1 的 {@code Pause Young (Mixed)}、{@code (Prepare Mixed)}、
 * {@code (Concurrent Start)} 分别代表不同阶段，不能统一标记成 Young。</p>
 *
 * <p>v2：{@link #parseLine} 是聚合链路的门面——汇总行包装为
 * {@link ParsedLine.PauseEvent}，其余行交给 {@link GcDetailParsers}（Region 转移、
 * 并发周期、阶段分解、CPU、Metaspace、verbose 细节）。旧 {@link #parse} 保持原语义，
 * 供测试与直接判断“这行是否产生了 pause 事件”使用。</p>
 */
@Component
public class GcLogParser {

    private static final Pattern SUMMARY = Pattern.compile(
            "\\[([^\\]]+)]\\[(\\d+(?:\\.\\d+)?)s].*?\\bGC\\((\\d+)\\)\\s+"
                    + "(Pause\\s+.+?)\\s+(\\d+)([KMG])->(\\d+)([KMG])\\((\\d+)([KMG])\\)\\s+([0-9.]+)ms\\s*$");

    private static final Pattern YOUNG_TYPE = Pattern.compile("Pause Young \\(([^)]+)\\)");
    /**
     * 解析一行 unified GC log。
     *
     * @param line         原始日志行，允许包含 utctime/uptime/level/tags 装饰器
     * @param processStart 进程启动时间，用于把 uptime 转成绝对时间
     * @return 仅当该行是带堆和 pause 的汇总行时返回事件；start/phases 行返回 empty
     */
    public Optional<GcEvent> parse(String line, Instant processStart) {
        if (line == null || line.isBlank() || !line.contains("Pause ")) {
            return Optional.empty();
        }
        Matcher matcher = SUMMARY.matcher(line);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String description = matcher.group(4).trim();
        String type = classify(description);
        if (type == null) {
            return Optional.empty();
        }
        String cause = extractCause(description);
        long before = toBytes(matcher.group(5), matcher.group(6));
        long after = toBytes(matcher.group(7), matcher.group(8));
        long capacity = toBytes(matcher.group(9), matcher.group(10));
        double uptime = Double.parseDouble(matcher.group(2));
        double pauseMs = Double.parseDouble(matcher.group(11));
        Instant timestamp = processStart == null
                ? Instant.now()
                : processStart.plusMillis((long) (uptime * 1000L));
        return Optional.of(GcEvent.basic(
                0,
                Long.parseLong(matcher.group(3)),
                type,
                cause,
                before,
                after,
                capacity,
                pauseMs,
                uptime,
                timestamp,
                "gc-log",
                line.strip()
        ));
    }

    /**
     * v2 门面：把一行日志归类为 PauseEvent / EventDetail / StandaloneEvent。
     *
     * @return 该行属于已知家族时返回归类，否则 empty
     */
    public Optional<ParsedLine> parseLine(String line, Instant processStart) {
        Optional<GcEvent> pause = parse(line, processStart);
        if (pause.isPresent()) {
            return Optional.of(new ParsedLine.PauseEvent(pause.get()));
        }
        return GcDetailParsers.parse(line, processStart);
    }

    /**
     * 把 JDK 21 的 pause 描述映射成教学/UI 使用的事件类型。
     *
     * <p>“Pause Young”不是单一类型：G1 在并发周期中会输出 Concurrent Start /
     * Prepare Mixed / Mixed，它们的回收含义不同，必须保留。</p>
     */
    private String classify(String description) {
        if (description.startsWith("Pause Young")) {
            Matcher matcher = YOUNG_TYPE.matcher(description);
            if (!matcher.find()) {
                return "Young";
            }
            return switch (matcher.group(1)) {
                case "Mixed" -> "Mixed";
                case "Prepare Mixed" -> "Prepare Mixed";
                case "Concurrent Start" -> "Concurrent Start";
                default -> "Young";
            };
        }
        if (description.startsWith("Pause Full")) {
            return "Full";
        }
        if (description.startsWith("Pause Remark")) {
            return "Remark";
        }
        if (description.startsWith("Pause Cleanup")) {
            return "Cleanup";
        }
        return null;
    }

    /**
     * 提取最后一个顶层括号中的 cause。
     *
     * <p>不能简单用“最后一个括号”正则：{@code Pause Full (System.gc())} 内含嵌套
     * 括号，正则会错误得到 {@code ")"}。这里用深度扫描保证 cause 为
     * {@code System.gc()}。</p>
     */
    private String extractCause(String description) {
        String last = "";
        int depth = 0;
        int start = -1;
        for (int i = 0; i < description.length(); i++) {
            char c = description.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    start = i + 1;
                }
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    last = description.substring(start, i);
                }
            }
        }
        return last;
    }

    private long toBytes(String value, String unit) {
        long number = Long.parseLong(value);
        return switch (unit) {
            case "K" -> number * 1024L;
            case "M" -> number * 1024L * 1024L;
            case "G" -> number * 1024L * 1024L * 1024L;
            default -> number;
        };
    }
}
