package com.example.gcdemo.memory;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.util.ByteSize;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证实验台的硬上限：分配量、线程、时长与 retained 峰值。
 */
class MemoryGuardTest {

    private DemoProperties properties() {
        DemoProperties properties = new DemoProperties();
        properties.setMaxRetainedMb(768);
        properties.setMaxAllocationMb(10);
        properties.setMaxThreads(2);
        properties.setMaxDurationMs(500);
        return properties;
    }

    @Test
    void enforcesAllocationThreadAndDurationLimits() {
        MemoryGuard guard = new MemoryGuard(properties());

        assertThatThrownBy(() -> guard.checkAllocationBytes(11 * ByteSize.MB))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.clampThreads(3, 1))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.clampDurationMs(600, 100))
                .isInstanceOf(ApiException.class);
        assertThat(guard.clampThreads(2, 1)).isEqualTo(2);
        assertThat(guard.clampChunkKb(16_384, 128)).isEqualTo(8192);
    }

    @Test
    void capsRetainedAtFortyPercentOfHeap() {
        MemoryGuard guard = new MemoryGuard(properties());
        long safe = guard.safeRetainedBytes();

        assertThat(safe).isPositive();
        assertThat(safe).isLessThanOrEqualTo(guard.maxHeapBytes() * 40 / 100);
        assertThatThrownBy(() -> guard.checkPeakRetained(safe + 1))
                .isInstanceOf(ApiException.class);
    }
}
