package com.example.gcdemo.gc;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryUsage;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JMX fallback 的堆语义契约：heapUsed 只累加 heap 池。
 *
 * <p>修复前 it 把 Metaspace / Compressed Class Space 等 non-heap 池也算进
 * heapBefore/After，与日志解析路径的“堆 used”不可比。</p>
 */
class JmxGcFallbackTest {

    @Test
    void heapUsedSumsOnlyHeapPools() {
        Map<String, MemoryUsage> usages = new HashMap<>();
        usages.put("G1 Eden Space", new MemoryUsage(-1, 100, 200, -1));
        usages.put("G1 Survivor Space", new MemoryUsage(-1, 10, 64, -1));
        usages.put("G1 Old Gen", new MemoryUsage(-1, 50, 200, -1));
        usages.put("Metaspace", new MemoryUsage(-1, 999, 999, -1));
        usages.put("Compressed Class Space", new MemoryUsage(-1, 500, 500, -1));

        assertThat(JmxGcFallback.heapUsed(usages)).isEqualTo(160);
    }

    @Test
    void nullOrNegativeUsagesAreIgnored() {
        Map<String, MemoryUsage> usages = new HashMap<>();
        usages.put("G1 Eden Space", new MemoryUsage(-1, 42, 64, -1));
        usages.put("G1 Old Gen", null);

        assertThat(JmxGcFallback.heapUsed(usages)).isEqualTo(42);
    }
}
