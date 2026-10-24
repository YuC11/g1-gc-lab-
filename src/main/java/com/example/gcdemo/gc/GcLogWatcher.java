package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 增量监听最新 GC 日志，是项目实时 GC 事件的主数据源。
 *
 * <p>为什么必须监听日志而不是只依赖 JMX：JDK 21 的 G1 日志能把
 * {@code Pause Young (Normal)}、{@code (Concurrent Start)}、{@code (Prepare Mixed)}、
 * {@code (Mixed)}、{@code Pause Full} 区分开；JMX notification 做不到。</p>
 *
 * <p>实现要点：
 * <ul>
 *   <li>首次绑定到已有文件时从文件末尾开始，避免应用重启后重放历史；</li>
 *   <li>文件轮转时从新文件开头读取；文件截断时重置 offset；</li>
 *   <li>半行缓冲是<b>字节级</b>的：按 {@code \n} 字节切分、只解码完整行，
 *       因此 UTF-8 多字节字符跨读取边界也不会损坏；</li>
 *   <li>单次最多读取 512KB，避免大日志阻塞调度线程；</li>
 *   <li>{@link #poll()} 是 synchronized 的，场景线程可主动调用它来消除
 *       与 500ms 定时轮询的竞态；{@link #pollUntilQuiet} 在此之上提供
 *       “读到追平为止”的同步点，供场景结束时汇齐事件；</li>
 *   <li>疑似 pause 汇总行但解析失败会计入 {@code pauseLinesSkipped}，
 *       JDK 升级导致日志格式漂移时能在状态页报警，而不是事件悄悄变少。</li>
 * </ul>
 * </p>
 */
@Component
public class GcLogWatcher {

    private static final Logger log = LoggerFactory.getLogger(GcLogWatcher.class);
    private static final long MAX_READ_BYTES = 512 * 1024;

    private final DemoProperties properties;
    private final GcEventStore eventStore;
    private final GcLogParser parser;
    private final Instant processStart = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());

    private final Path logDir;
    private final PathMatcher matcher;

    /** 跨读取边界的半行字节缓冲；只有遇到 \n 才整体解码。 */
    private final GrowableBuffer pending = new GrowableBuffer();

    /** 按 GC(n) 聚合 pause 汇总行与后续补充行（Region/phases/CPU/Metaspace/verbose 细节）。 */
    private final GcCycleAggregator aggregator = new GcCycleAggregator();

    private Path activeFile;
    private long offset;
    private boolean attachedOnce;
    private long bytesRead;
    private long linesRead;
    private int parsedEvents;
    private long pauseLinesSkipped;
    private int quietPolls;
    private String lastError;

    public GcLogWatcher(DemoProperties properties, GcEventStore eventStore, GcLogParser parser) {
        this.properties = properties;
        this.eventStore = eventStore;
        this.parser = parser;
        this.logDir = Paths.get(properties.getLogDir()).toAbsolutePath().normalize();
        this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + properties.getGcLogPattern());
    }

    /**
     * 定时调度入口，也可被场景线程主动调用以立即推进日志消费。
     *
     * <p>方法整体串行化：文件查找、offset 推进、半行缓冲和事件写入不能交错。
     * 读取失败只记录 status，不让调度线程崩溃。</p>
     */
    @Scheduled(fixedDelayString = "${demo.poll-interval-ms:500}")
    public synchronized void poll() {
        try {
            Optional<Path> latest = findLatest();
            if (latest.isEmpty()) {
                lastError = "No GC log matching " + properties.getGcLogPattern()
                        + " in " + logDir + " (start with scripts/run.sh to enable live log parsing)";
                return;
            }
            Path file = latest.get();
            if (!file.equals(activeFile)) {
                // 轮转：先把上一个文件的 pending 事件定稿发布，再切换 offset。
                publish(aggregator.flush());
                activeFile = file;
                pending.reset();
                offset = attachedOnce ? 0 : Files.size(file);
                attachedOnce = true;
                quietPolls = 0;
            }
            readNewLines(file);
            lastError = null;
        } catch (IOException | RuntimeException ex) {
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.debug("GC log poll failed", ex);
        }
    }

    /**
     * 反复 poll 直到连续两轮没有读到新字节（日志已追平、聚合器已定稿）或超时。
     *
     * <p>单次 poll 最多消费 512KB；场景在高压/verbose 下产生的日志可能需要多轮。
     * 要求“连续两轮安静”是因为最后一个 GC 事件在聚合器里 pending，第一轮安静后的
     * 下一轮 poll 才会把它定稿发布。场景结束时调用它，保证 observedEventTypes
     * 覆盖最后一次 pause。</p>
     *
     * @return true 表示在超时前追平且无 pending 事件
     */
    public synchronized boolean pollUntilQuiet(Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        int consecutiveQuietRounds = 0;
        while (System.nanoTime() < deadline) {
            long before = bytesRead;
            poll();
            if (bytesRead == before) {
                if (++consecutiveQuietRounds >= 2) {
                    return !aggregator.hasPending();
                }
            } else {
                consecutiveQuietRounds = 0;
            }
        }
        return !aggregator.hasPending();
    }

    private Optional<Path> findLatest() throws IOException {
        if (!Files.isDirectory(logDir)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(logDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(path.getFileName()))
                    .max(Comparator.comparingLong(this::lastModified));
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 从当前 offset 读取新增字节，并按完整行（字节级）解析。
     *
     * <p>新增字节先在缓冲内按 {@code \n} 切分：换行之前的部分与上一次的半行
     * 拼接成完整行再解码，避免 UTF-8 多字节字符被读取边界切断。若文件变小，
     * 说明发生截断/重写，offset 归零并丢弃旧缓冲。</p>
     */
    private void readNewLines(Path file) throws IOException {
        long size = Files.size(file);
        if (size < offset) {
            // 截断/重写：定稿 pending 后 offset 归零重读。
            publish(aggregator.flush());
            offset = 0;
            pending.reset();
            quietPolls = 0;
        }
        if (size == offset) {
            // 连续两轮无新增字节：日志已安静，把聚合器里 pending 的最后一个 GC
            // 事件定稿发布，避免最新一次 pause 要等下一次 GC 才可见。
            if (++quietPolls >= 2 && aggregator.hasPending()) {
                quietPolls = 0;
                publish(aggregator.flush());
            }
            return;
        }
        quietPolls = 0;
        int toRead = (int) Math.min(size - offset, MAX_READ_BYTES);
        byte[] buffer = new byte[toRead];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buffer);
        }
        offset += toRead;
        bytesRead += toRead;

        int start = 0;
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] == '\n') {
                handleLine(pending.appendAndDrain(buffer, start, i));
                start = i + 1;
            }
        }
        pending.append(buffer, start, buffer.length);
    }

    private void handleLine(byte[] lineBytes) {
        String line = new String(lineBytes, StandardCharsets.UTF_8);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        linesRead++;
        Optional<ParsedLine> parsed = parser.parseLine(line, processStart);
        if (parsed.isPresent()) {
            publish(aggregator.offer(parsed.get()));
        } else if (looksLikePauseSummary(line)) {
            // 形似 pause 汇总却解析失败：大概率是 JDK 日志格式漂移，暴露出来而不是静默丢弃。
            pauseLinesSkipped++;
        }
    }

    private void publish(java.util.List<GcEvent> events) {
        for (GcEvent event : events) {
            eventStore.append(event);
            parsedEvents++;
        }
    }

    /**
     * gc,start 行（装饰器含 {@code ,start} tag）没有堆与耗时数据，忽略是预期行为；
     * 除此之外的含 Pause 的 GC 行解析失败才值得报警。
     */
    private boolean looksLikePauseSummary(String line) {
        return line.contains("Pause ") && line.contains("GC(") && !line.contains(",start");
    }

    /**
     * @return 供状态页显示的日志源信息，不抛出 IO 异常
     */
    public synchronized LogWatchStatus status() {
        return new LogWatchStatus(
                activeFile != null && Files.isRegularFile(activeFile),
                activeFile == null ? null : activeFile.toString(),
                bytesRead,
                parsedEvents,
                linesRead,
                pauseLinesSkipped,
                lastError
        );
    }

    /**
     * 日志文件真实可用时才让 JMX fallback 静默，避免同一 GC 产生两条事件。
     */
    public synchronized boolean available() {
        return activeFile != null && Files.isRegularFile(activeFile);
    }

    public Path logDir() {
        return logDir;
    }

    /**
     * 简单的可增长字节缓冲，只为半行拼装服务。
     *
     * <p>不引入 ByteArrayOutputStream 是因为这里需要“区间拼接 + 区间追加 + 清空”
     * 的组合，且要避免每行 toByteArray 的复制开销。</p>
     */
    private static final class GrowableBuffer {
        private byte[] data = new byte[1024];
        private int length;

        void reset() {
            length = 0;
        }

        void append(byte[] source, int from, int to) {
            int count = to - from;
            ensureCapacity(length + count);
            System.arraycopy(source, from, data, length, count);
            length += count;
        }

        /**
         * 追加区间后返回完整行并清空缓冲；缓冲为空时零拷贝地返回输入区间。
         */
        byte[] appendAndDrain(byte[] source, int from, int to) {
            if (length == 0) {
                byte[] line = new byte[to - from];
                System.arraycopy(source, from, line, 0, line.length);
                return line;
            }
            append(source, from, to);
            byte[] line = new byte[length];
            System.arraycopy(data, 0, line, 0, length);
            length = 0;
            return line;
        }

        private void ensureCapacity(int required) {
            if (required <= data.length) {
                return;
            }
            int capacity = data.length;
            while (capacity < required) {
                capacity *= 2;
            }
            byte[] grown = new byte[capacity];
            System.arraycopy(data, 0, grown, 0, length);
            data = grown;
        }
    }
}
