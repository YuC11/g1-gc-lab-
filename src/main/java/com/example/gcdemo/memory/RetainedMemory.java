package com.example.gcdemo.memory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 实验场景的强引用持有区。
 *
 * <p>场景把需要存活的对象按 key 放入这里，用于制造 Old 占用、Humongous 占用和类卸载
 * 后的残留引用。{@code bytes} 是估算值，用于 MemoryGuard 的峰值检查；它不是精确的
 * JVM 对象图大小。</p>
 */
@Component
public class RetainedMemory {

    private record Entry(List<Object> objects, long bytes) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong usedBytes = new AtomicLong();

    /**
     * 以 key 保存强引用；重复 put 会先扣减旧估算值。
     *
     * <p>保留的是对象引用，JVM 对象图实际占用可能高于估算值；因此 guard 还会对
     * “峰值估算”做独立检查。</p>
     */
    public synchronized void put(String key, List<Object> objects, long bytes) {
        Entry previous = entries.put(key, new Entry(List.copyOf(objects), bytes));
        if (previous != null) {
            usedBytes.addAndGet(-previous.bytes());
        }
        usedBytes.addAndGet(bytes);
    }

    /**
     * 释放某个实验批次的强引用，使对象在后续 GC 中变为可回收。
     */
    public synchronized void remove(String key) {
        Entry previous = entries.remove(key);
        if (previous != null) {
            usedBytes.addAndGet(-previous.bytes());
        }
    }

    /**
     * 清空所有实验引用；不触发 GC，回收发生在后续 GC 周期。
     */
    public synchronized void clear() {
        entries.clear();
        usedBytes.set(0);
    }

    public long usedBytes() {
        return usedBytes.get();
    }

    /**
     * @return key -> 估算字节数，用于状态页和文档分析
     */
    public Map<String, Long> details() {
        Map<String, Long> result = new LinkedHashMap<>();
        entries.forEach((key, entry) -> result.put(key, entry.bytes()));
        return result;
    }
}
