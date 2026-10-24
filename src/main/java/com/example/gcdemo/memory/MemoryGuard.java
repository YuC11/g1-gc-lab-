package com.example.gcdemo.memory;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.util.ByteSize;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 场景内存与并发资源的安全边界。
 *
 * <p>retained 上限取 {@code min(demo.max-retained-mb, 40% 堆)}；单场景总分配、线程数、
 * 持续时间也都有硬上限。场景在分配前调用 guard，而不是依赖捕获 OOME，因为 OOME
 * 之后进程状态已经不可靠。</p>
 */
@Component
public class MemoryGuard {

    private static final long PERCENT_LIMIT = 40L;

    private final DemoProperties properties;
    private final long maxHeapBytes;
    private final long safeRetainedBytes;

    public MemoryGuard(DemoProperties properties) {
        this.properties = properties;
        this.maxHeapBytes = Runtime.getRuntime().maxMemory();
        long configured = properties.getMaxRetainedMb() * ByteSize.MB;
        this.safeRetainedBytes = Math.min(configured, maxHeapBytes * PERCENT_LIMIT / 100L);
    }

    /**
     * @return JVM 实际最大堆，用于推导实验规模与 retained 安全上限
     */
    public long maxHeapBytes() {
        return maxHeapBytes;
    }

    public long maxHeapMb() {
        return maxHeapBytes / ByteSize.MB;
    }

    public long safeRetainedBytes() {
        return safeRetainedBytes;
    }

    /**
     * 按当前堆比例推导场景默认 MB，并夹在绝对上下限内。
     *
     * <p>这样同一套场景在 2G/4G 下都能产生可观察现象，而不会把 2G 的绝对量硬编码
     * 到 4G 环境。</p>
     */
    public int scaledMb(double fraction, int capMb, int floorMb) {
        long value = (long) (maxHeapMb() * fraction);
        return (int) ByteSize.clamp(value, floorMb, Math.min(capMb, properties.getMaxRetainedMb()));
    }

    /**
     * 检查单场景累计分配上限，超限返回 400 而不是尝试分配。
     */
    public void checkAllocationBytes(long bytes) {
        long max = properties.getMaxAllocationMb() * ByteSize.MB;
        if (bytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "分配量必须大于 0");
        }
        if (bytes > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "单场景分配量 " + ByteSize.human(bytes) + " 超过上限 " + ByteSize.human(max),
                    "可通过 demo.max-allocation-mb 调整上限，但不要超过堆大小。");
        }
    }

    /**
     * 检查 retained 峰值；调用方必须在真正持有对象前传入预估峰值。
     */
    public void checkPeakRetained(long bytes) {
        if (bytes > safeRetainedBytes) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "retained 峰值 " + ByteSize.human(bytes) + " 超过安全上限 "
                            + ByteSize.human(safeRetainedBytes),
                    "安全上限 = min(demo.max-retained-mb, 40% 堆)。先运行 reset 或减少场景参数。");
        }
    }

    /**
     * 限制 UI 输入的单次 chunk 大小，防止用户误触发大量 Humongous 分配。
     */
    public int clampChunkKb(Integer requested, int defaultKb) {
        int value = requested == null ? defaultKb : requested;
        return Math.max(1, Math.min(value, 8192));
    }

    /**
     * 限制分配风暴线程数，避免压测线程本身耗尽 CPU/栈资源。
     */
    public int clampThreads(Integer requested, int defaultThreads) {
        int value = requested == null ? defaultThreads : requested;
        if (value > properties.getMaxThreads()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "线程数超过上限 " + properties.getMaxThreads());
        }
        return Math.max(1, value);
    }

    /**
     * 限制场景持续时间；所有等待循环都会尊重这个上限。
     */
    public int clampDurationMs(Integer requested, int defaultMs) {
        int value = requested == null ? defaultMs : requested;
        if (value > properties.getMaxDurationMs()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "持续时间超过上限 " + properties.getMaxDurationMs() + "ms");
        }
        return Math.max(100, value);
    }
}
