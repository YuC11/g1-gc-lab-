package com.example.gcdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 教学实验台的运行约束配置（前缀 {@code demo}）。
 *
 * <p>这里只放应用层可动态读取的配置：retained 上限、单场景分配上限、线程/时长上限、
 * 是否允许显式 Full GC、GC 日志目录等。真正的 G1 flag（例如
 * {@code -XX:G1HeapRegionSize}）必须作为 JVM 启动参数传入，Spring 无法在运行中修改。</p>
 *
 * <p>这些限制不是装饰：场景会主动分配数 GB 内存，必须防止误操作把实验进程直接 OOM。</p>
 */
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    /** Maximum strong references kept for demos (the guard also caps this at 40% of heap). */
    private int maxRetainedMb = 768;

    /** Maximum bytes a single scenario may allocate across its lifetime. */
    private int maxAllocationMb = 3072;

    private int maxThreads = 8;

    private int maxDurationMs = 15_000;

    /**
     * 单个场景的墙钟预算（毫秒）。max-duration-ms 只约束请求参数（如 allocation-storm
     * 的 durationMs）；场景内部的分阶段等待链路（如 mixed-gc 的并发周期等待）由本值兜底，
     * 这是“场景时长上限”声明的真实边界。默认 90s，覆盖 mixed-gc 最坏 ~58s 链路。
     */
    private long maxScenarioWallMs = 90_000L;

    /** 运行历史保留条数（运行历史 / A/B 对比面板的数据源）。 */
    private int historySize = 20;

    /** JFR 单次录制时长上限（秒）；录制文件写入 logDir。 */
    private int maxJfrSeconds = 300;

    /** Allows the explicitly labelled full-gc and class-unload scenarios to call System.gc(). */
    private boolean allowFullGc = true;

    private String logDir = "logs";

    private String gcLogPattern = "gc-*.log";

    private long pollIntervalMs = 500;

    private int eventBufferSize = 500;

    public int getMaxRetainedMb() {
        return maxRetainedMb;
    }

    public void setMaxRetainedMb(int maxRetainedMb) {
        this.maxRetainedMb = maxRetainedMb;
    }

    public int getMaxAllocationMb() {
        return maxAllocationMb;
    }

    public void setMaxAllocationMb(int maxAllocationMb) {
        this.maxAllocationMb = maxAllocationMb;
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
    }

    public int getMaxDurationMs() {
        return maxDurationMs;
    }

    public void setMaxDurationMs(int maxDurationMs) {
        this.maxDurationMs = maxDurationMs;
    }

    public boolean isAllowFullGc() {
        return allowFullGc;
    }

    public void setAllowFullGc(boolean allowFullGc) {
        this.allowFullGc = allowFullGc;
    }

    public String getLogDir() {
        return logDir;
    }

    public void setLogDir(String logDir) {
        this.logDir = logDir;
    }

    public String getGcLogPattern() {
        return gcLogPattern;
    }

    public void setGcLogPattern(String gcLogPattern) {
        this.gcLogPattern = gcLogPattern;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getEventBufferSize() {
        return eventBufferSize;
    }

    public void setEventBufferSize(int eventBufferSize) {
        this.eventBufferSize = eventBufferSize;
    }

    public long getMaxScenarioWallMs() {
        return maxScenarioWallMs;
    }

    public void setMaxScenarioWallMs(long maxScenarioWallMs) {
        this.maxScenarioWallMs = maxScenarioWallMs;
    }

    public int getHistorySize() {
        return historySize;
    }

    public void setHistorySize(int historySize) {
        this.historySize = historySize;
    }

    public int getMaxJfrSeconds() {
        return maxJfrSeconds;
    }

    public void setMaxJfrSeconds(int maxJfrSeconds) {
        this.maxJfrSeconds = maxJfrSeconds;
    }
}
