package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GC 事件的线程安全有界环形存储。
 *
 * <p>日志监听线程写入，HTTP 线程读取；每个事件分配应用内单调序号，前端用
 * {@code afterSeq} 做增量拉取。只保留最近 N 条，避免长时间运行时无限增长。</p>
 *
 * <p>场景断言会通过 {@link #awaitType} / {@link #hasAnyTypeSince} 等待特定的
 * G1 事件（例如 Mixed/Full），因此这里同时承担“事件缓冲”和“轻量同步点”的职责。</p>
 */
@Component
public class GcEventStore {

    private final int capacity;
    private final Deque<GcEvent> events = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();

    public GcEventStore(DemoProperties properties) {
        this.capacity = Math.max(50, properties.getEventBufferSize());
    }

    /**
     * 追加事件并分配应用内单调序号。
     *
     * @return 带 seq 的存储副本；超出容量时最老事件被丢弃
     */
    public GcEvent append(GcEvent event) {
        long seq = sequence.incrementAndGet();
        GcEvent stored = event.withSeq(seq);
        lock.lock();
        try {
            events.addLast(stored);
            while (events.size() > capacity) {
                events.removeFirst();
            }
        } finally {
            lock.unlock();
        }
        return stored;
    }

    /**
     * @return 最近 limit 条事件，按 seq 升序
     */
    public List<GcEvent> recent(int limit) {
        return since(0, limit);
    }

    /**
     * 增量读取接口，供前端轮询使用。
     *
     * @param afterSeq 只返回 seq 大于该值的事件；首次可传 0
     */
    public List<GcEvent> since(long afterSeq, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, capacity));
        lock.lock();
        try {
            List<GcEvent> result = new ArrayList<>(Math.min(safeLimit, events.size()));
            for (GcEvent event : events) {
                if (event.seq() > afterSeq) {
                    result.add(event);
                    if (result.size() >= safeLimit) {
                        break;
                    }
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return 指定时间之后出现过的事件类型（去重，保持首次出现顺序）
     */
    public List<String> typesSince(Instant from) {
        lock.lock();
        try {
            Set<String> types = new LinkedHashSet<>();
            for (GcEvent event : events) {
                if (!event.timestamp().isBefore(from)) {
                    types.add(event.type());
                }
            }
            return List.copyOf(types);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 场景断言：指定时间之后是否已观察到某类事件。
     */
    public boolean hasTypeSince(String type, Instant from) {
        return hasAnyTypeSince(Set.of(type), from);
    }

    public boolean hasAnyTypeSince(Set<String> types, Instant from) {
        lock.lock();
        try {
            for (GcEvent event : events) {
                if (!event.timestamp().isBefore(from) && types.contains(event.type())) {
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return 最新事件，尚无事件时为 null
     */
    public GcEvent latest() {
        lock.lock();
        try {
            return events.peekLast();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 等待日志监听器 ingest 指定类型的事件。
     *
     * <p>这是“日志已经打印”与“应用已经看到日志”之间的同步点。调用方通常还需
     * 配合 {@link com.example.gcdemo.gc.GcLogWatcher#poll()} 主动推进读取；
     * {@code ScenarioSupport#awaitType} 已经封装了该细节。</p>
     *
     * @return true 表示在超时前观察到至少一种类型
     */
    public boolean awaitType(Set<String> types, Instant from, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            lock.lock();
            try {
                for (GcEvent event : events) {
                    if (!event.timestamp().isBefore(from) && types.contains(event.type())) {
                        return true;
                    }
                }
            } finally {
                lock.unlock();
            }
            Thread.sleep(50);
        }
        return false;
    }

    /**
     * @return 当前最大应用内事件序号，供前端初始化 afterSeq
     */
    public long lastSequence() {
        return sequence.get();
    }

    public int size() {
        lock.lock();
        try {
            return events.size();
        } finally {
            lock.unlock();
        }
    }
}
