package com.example.gcdemo.memory;

/**
 * 教学场景专用的分配工具。
 *
 * <p>普通分配直接 {@code new byte[]} 会被 JIT 逃逸分析消除，导致“分配了但没触发 GC”。
 * 这里把最后一个对象写入 {@code volatile sink}，并按 4KB 页 touch，确保内存实际提交。</p>
 *
 * <p>注意：调用方必须使用远小于 region/2 的 chunk（项目统一 256KB），否则普通
 * churn 会被 G1 当成 Humongous 对象，彻底改变实验结论。</p>
 */
public final class AllocationTools {

    /** Volatile sink prevents the JIT from eliminating allocations as dead code. */
    private static volatile Object sink;

    private AllocationTools() {
    }

    /**
     * 分配并 touch 一个 byte[]，确保它真正进入 G1 的分配路径。
     *
     * <p>按 4096 字节页写一次，避免只保留虚拟地址；写入 volatile sink，
     * 避免 JIT 把整个数组判定为死代码。调用方应使用小于 region/2 的 chunk，
     * 否则对象会变成 Humongous。</p>
     */
    public static byte[] allocate(int bytes, byte fill) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("bytes must be positive");
        }
        byte[] array = new byte[bytes];
        array[0] = fill;
        array[bytes - 1] = fill;
        for (int i = 0; i < bytes; i += 4096) {
            array[i] = fill;
        }
        sink = array;
        return array;
    }

    public static void blackhole(Object value) {
        sink = value;
    }
}
