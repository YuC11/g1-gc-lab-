package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GcLogWatcher 的边界行为契约：尾部附着、半行缓冲、轮转、截断、UTF-8 切断与
 * 可疑 pause 行计数——这些是注释里声明过的设计保证，必须有回归测试。
 *
 * <p>注意 watcher 的附着语义：首次绑定到已有文件时从 EOF 开始（不重放历史），
 * 因此测试都先绑定空文件再追加内容。</p>
 */
class GcLogWatcherTest {

    private static final String LINE_1 =
            "[2026-10-22T03:00:01.000+0000][1.000s][info][gc] "
                    + "GC(1) Pause Young (Normal) (G1 Evacuation Pause) 512K->32K(2G) 1.5ms";
    private static final String LINE_2 =
            "[2026-10-22T03:00:02.000+0000][2.000s][info][gc] "
                    + "GC(2) Pause Full (System.gc()) 40M->9M(2048M) 120.5ms";

    @TempDir
    Path tempDir;

    private DemoProperties properties;
    private GcEventStore store;
    private GcLogWatcher watcher;

    @BeforeEach
    void setUp() {
        properties = new DemoProperties();
        properties.setLogDir(tempDir.toString());
        properties.setGcLogPattern("gc-*.log");
        store = new GcEventStore(properties);
        watcher = new GcLogWatcher(properties, store, new GcLogParser());
    }

    private Path bindEmptyLog(String name) throws IOException {
        Path log = tempDir.resolve(name);
        Files.writeString(log, "");
        watcher.poll();
        assertThat(store.size()).isZero();
        return log;
    }

    private void append(Path log, String text) throws IOException {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /**
     * 聚合器把 pause 事件保持为 pending，直到下一个 GC 出现或日志安静两轮；
     * 测试里连 poll 4 轮保证定稿发布。
     */
    private void pollUntilFlushed() {
        for (int i = 0; i < 4; i++) {
            watcher.poll();
        }
    }

    @Test
    void parsesAppendedLinesIncrementally() throws IOException {
        Path log = bindEmptyLog("gc-1.log");

        append(log, LINE_1 + "\n");
        watcher.poll();
        // 第一轮读到行但事件仍在聚合器 pending，未发布
        assertThat(store.size()).isZero();
        pollUntilFlushed();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.latest().type()).isEqualTo("Young");

        append(log, LINE_2 + "\n");
        pollUntilFlushed();

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.latest().type()).isEqualTo("Full");
        assertThat(watcher.status().parsedEvents()).isEqualTo(2);
        assertThat(watcher.status().error()).isNull();
    }

    @Test
    void preExistingContentIsNotReplayedOnFirstBind() throws IOException {
        Path stale = tempDir.resolve("gc-old.log");
        Files.writeString(stale, LINE_1 + "\n" + LINE_2 + "\n");
        watcher.poll();

        // 应用重启后绑定到已有日志：从 EOF 开始，不把历史 GC 当成新事件。
        assertThat(store.size()).isZero();
    }

    @Test
    void partialLineIsBufferedUntilNewline() throws IOException {
        Path log = bindEmptyLog("gc-1.log");

        append(log, LINE_2.substring(0, 80));
        watcher.poll();
        assertThat(store.size()).isZero();

        append(log, LINE_2.substring(80) + "\n");
        pollUntilFlushed();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.latest().pauseMs()).isEqualTo(120.5);
    }

    @Test
    void rotationSwitchesToNewerFileFromStart() throws IOException {
        Path first = bindEmptyLog("gc-1.log");
        append(first, LINE_1 + "\n");
        pollUntilFlushed();
        assertThat(store.size()).isEqualTo(1);
        Files.setLastModifiedTime(first, FileTime.from(Instant.now().minusSeconds(60)));

        Path second = tempDir.resolve("gc-2.log");
        Files.writeString(second, LINE_2 + "\n");
        pollUntilFlushed();

        // 轮转到新文件：从文件开头读，不沿用旧 offset。
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.latest().gcId()).isEqualTo(2);
        assertThat(watcher.status().file()).isEqualTo(second.toAbsolutePath().toString());
    }

    @Test
    void truncationResetsOffsetAndReparses() throws IOException {
        Path log = bindEmptyLog("gc-1.log");
        append(log, LINE_1 + "\n");
        pollUntilFlushed();
        assertThat(store.size()).isEqualTo(1);

        // 截断为更短的新内容：offset 必须归零重读，否则永远读不到新行。
        String shorter = "[2026-10-22T03:00:03.000+0000][3.000s][info][gc] "
                + "GC(9) Pause Remark 20M->20M(2048M) 2.2ms\n";
        assertThat(shorter.length()).isLessThan(LINE_1.length());
        Files.writeString(log, shorter);
        pollUntilFlushed();

        assertThat(watcher.status().parsedEvents()).isEqualTo(2);
        assertThat(store.latest().type()).isEqualTo("Remark");
    }

    @Test
    void utf8CharSplitAcrossReadsIsReassembled() throws IOException {
        Path log = bindEmptyLog("gc-1.log");

        byte[] full = ("中" + LINE_1 + "\n").getBytes(StandardCharsets.UTF_8);
        // “中”是 3 字节 UTF-8 字符；把前 2 字节和剩余部分分两次写入。
        Files.write(log, Arrays.copyOfRange(full, 0, 2), StandardOpenOption.APPEND);
        watcher.poll();
        assertThat(store.size()).isZero();

        Files.write(log, Arrays.copyOfRange(full, 2, full.length), StandardOpenOption.APPEND);
        pollUntilFlushed();

        assertThat(store.size()).isEqualTo(1);
        // 若按块解码，“中”会被替换字符破坏；字节级缓冲必须还原完整字符。
        assertThat(store.latest().rawLine()).startsWith("中");
    }

    @Test
    void suspiciousPauseLinesAreCountedNotSilentlyDropped() throws IOException {
        Path log = bindEmptyLog("gc-1.log");
        append(log, "[2026-10-22T03:00:03.000+0000][3.000s][info][gc] "
                + "GC(3) Pause Young (Normal) truncated-corrupt-line\n");
        watcher.poll();

        assertThat(store.size()).isZero();
        assertThat(watcher.status().linesRead()).isEqualTo(1);
        assertThat(watcher.status().pauseLinesSkipped()).isEqualTo(1);
    }

    @Test
    void startTaggedLinesAreNotCountedAsSkipped() throws IOException {
        Path log = bindEmptyLog("gc-1.log");
        append(log, "[2026-10-22T03:00:03.000+0000][3.000s][info][gc,start] "
                + "GC(3) Pause Young (Normal) (G1 Evacuation Pause)\n");
        watcher.poll();

        // gc,start 行本来就没有堆/耗时数据，忽略是预期行为，不是格式漂移。
        assertThat(watcher.status().linesRead()).isEqualTo(1);
        assertThat(watcher.status().pauseLinesSkipped()).isZero();
    }

    @Test
    void pollUntilQuietReturnsOnceCaughtUp() throws IOException {
        Path log = bindEmptyLog("gc-1.log");
        append(log, LINE_1 + "\n");
        append(log, LINE_2 + "\n");

        // 两轮安静后才定稿，pollUntilQuiet 必须等到事件真正发布
        assertThat(watcher.pollUntilQuiet(java.time.Duration.ofSeconds(2))).isTrue();
        List<GcEvent> events = store.recent(10);
        assertThat(events).hasSize(2);
    }

    @Test
    void pauseDetailsAreAggregatedIntoOneEnrichedEvent() throws IOException {
        Path log = bindEmptyLog("gc-1.log");
        // JDK 21 真实 GC 块行序：细节行先于汇总行，cpu 行在汇总行之后
        append(log, LINE_1 + "\n");
        append(log, "[2026-10-22T03:00:01.100+0000][1.100s][info][gc,heap     ] GC(1) Eden regions: 194->0(213)\n");
        append(log, "[2026-10-22T03:00:01.100+0000][1.100s][info][gc,heap     ] GC(1) Humongous regions: 4->0\n");
        append(log, "[2026-10-22T03:00:01.101+0000][1.101s][info][gc,phases   ] GC(1)   Evacuate Collection Set: 1.2ms\n");
        append(log, "[2026-10-22T03:00:01.101+0000][1.101s][info][gc,phases   ] GC(1)   Other: 0.1ms\n");
        append(log, "[2026-10-22T03:00:01.102+0000][1.102s][info][gc,cpu      ] GC(1) User=0.09s Sys=0.01s Real=0.02s\n");
        append(log, "[2026-10-22T03:00:01.050+0000][1.050s][info][gc          ] GC(1) Concurrent Mark Cycle 3.500ms\n");

        assertThat(watcher.pollUntilQuiet(java.time.Duration.ofSeconds(2))).isTrue();

        // 汇总行 + 5 条补充行聚合成 1 个富事件；并发周期行独立成 1 个事件
        List<GcEvent> events = store.recent(10);
        assertThat(events).hasSize(2);

        // 并发行是独立事件，先于 pending 定稿发布；按类型查找避免依赖顺序
        GcEvent pause = events.stream().filter(e -> e.type().equals("Young")).findFirst().orElseThrow();
        assertThat(pause.gcId()).isEqualTo(1);
        assertThat(pause.regionTransitions().edenBefore()).isEqualTo(194);
        assertThat(pause.regionTransitions().edenTarget()).isEqualTo(213);
        assertThat(pause.regionTransitions().humongousAfter()).isEqualTo(0);
        assertThat(pause.phases()).containsEntry("Evacuate Collection Set", 1.2);
        assertThat(pause.phases()).containsEntry("Other", 0.1);
        assertThat(pause.cpu().userSeconds()).isEqualTo(0.09);

        GcEvent concurrent = events.stream().filter(e -> e.type().equals("Concurrent")).findFirst().orElseThrow();
        assertThat(concurrent.cause()).isEqualTo("Mark Cycle");
    }
}
