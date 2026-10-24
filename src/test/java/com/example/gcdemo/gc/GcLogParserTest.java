package com.example.gcdemo.gc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定 JDK 21 G1 日志格式的解析契约，防止升级/重构破坏 pause 分类与 cause 提取。
 */
class GcLogParserTest {

    private final GcLogParser parser = new GcLogParser();
    private final Instant start = Instant.parse("2026-10-19T02:00:00Z");

    @Test
    void parsesNormalYoungPause() {
        String line = "[2026-10-19T02:00:01.000+0000][1.234s][info][gc          ] "
                + "GC(12) Pause Young (Normal) (G1 Evacuation Pause) 1024M->128M(2048M) 8.123ms";

        Optional<GcEvent> event = parser.parse(line, start);

        assertThat(event).isPresent();
        assertThat(event.get().gcId()).isEqualTo(12);
        assertThat(event.get().type()).isEqualTo("Young");
        assertThat(event.get().cause()).isEqualTo("G1 Evacuation Pause");
        assertThat(event.get().heapBeforeBytes()).isEqualTo(1024L * 1024 * 1024);
        assertThat(event.get().heapAfterBytes()).isEqualTo(128L * 1024 * 1024);
        assertThat(event.get().heapCapacityBytes()).isEqualTo(2048L * 1024 * 1024);
        assertThat(event.get().pauseMs()).isEqualTo(8.123);
        assertThat(event.get().timestamp()).isEqualTo(start.plusMillis(1234));
        assertThat(event.get().rawLine()).contains("GC(12)");
    }

    @Test
    void parsesMixedAndPrepareMixed() {
        GcEvent mixed = parser.parse(
                "[2026-10-19T02:00:01.000+0000][2.000s][info][gc] "
                        + "GC(13) Pause Young (Mixed) (G1 Evacuation Pause) 800M->200M(2048M) 22.000ms",
                start).orElseThrow();
        GcEvent prepare = parser.parse(
                "[2026-10-19T02:00:01.000+0000][3.000s][info][gc] "
                        + "GC(14) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 900M->210M(2048M) 19.100ms",
                start).orElseThrow();

        assertThat(mixed.type()).isEqualTo("Mixed");
        assertThat(prepare.type()).isEqualTo("Prepare Mixed");
    }

    @Test
    void parsesConcurrentStartRemarkCleanupAndFull() {
        assertThat(parser.parse(line("Pause Young (Concurrent Start) (Metadata GC Threshold) 20M->19M(2048M) 3.5ms"), start)
                .orElseThrow().type()).isEqualTo("Concurrent Start");
        assertThat(parser.parse(line("Pause Remark 20M->20M(2048M) 2.2ms"), start)
                .orElseThrow().type()).isEqualTo("Remark");
        assertThat(parser.parse(line("Pause Cleanup 20M->20M(2048M) 0.2ms"), start)
                .orElseThrow().type()).isEqualTo("Cleanup");
        GcEvent full = parser.parse(line("Pause Full (System.gc()) 40M->9M(2048M) 120.5ms"), start).orElseThrow();
        assertThat(full.type()).isEqualTo("Full");
        assertThat(full.cause()).isEqualTo("System.gc()");
    }

    @Test
    void parsesOtherUnits() {
        GcEvent event = parser.parse(
                "[2026-10-19T02:00:01.000+0000][1.000s][info][gc] "
                        + "GC(1) Pause Young (Normal) 512K->32K(2G) 1.5ms",
                start).orElseThrow();

        assertThat(event.heapBeforeBytes()).isEqualTo(512L * 1024);
        assertThat(event.heapAfterBytes()).isEqualTo(32L * 1024);
        assertThat(event.heapCapacityBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    }

    @Test
    void ignoresStartAndMalformedLines() {
        assertThat(parser.parse(line("Pause Young (Normal) (G1 Evacuation Pause)"), start)).isEmpty();
        assertThat(parser.parse("not a gc line", start)).isEmpty();
        assertThat(parser.parse("", start)).isEmpty();
    }

    private String line(String message) {
        return "[2026-10-19T02:00:01.000+0000][9.999s][info][gc] GC(1) " + message;
    }
}
