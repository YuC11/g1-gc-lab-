package com.example.gcdemo.web;

import com.example.gcdemo.gc.GcEvent;
import com.example.gcdemo.gc.GcEventStore;
import com.example.gcdemo.gc.GcLogWatcher;
import com.example.gcdemo.gc.GcStatsService;
import com.example.gcdemo.gc.HeapService;
import com.example.gcdemo.gc.JvmInfoService;
import com.example.gcdemo.gc.JfrService;
import com.example.gcdemo.gc.LogFileInfo;
import com.example.gcdemo.gc.LogFileService;
import com.example.gcdemo.gc.LogTail;
import com.example.gcdemo.gc.LogWatchStatus;
import com.example.gcdemo.gc.SideLogWatchers;
import com.example.gcdemo.memory.RetainedMemory;
import com.example.gcdemo.scenario.ScenarioHistory;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioRegistry;
import com.example.gcdemo.scenario.ScenarioResult;
import com.example.gcdemo.scenario.ScenarioRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * G1 Lab 的 REST 入口。
 *
 * <p>提供状态、参数、场景执行、GC 事件和日志 tail。控制器只做协议转换和参数校验，
 * 场景串行、内存保护、日志解析等逻辑都在 service 层。应用默认只绑定
 * {@code 127.0.0.1}，这些接口会主动分配内存，不能暴露到公网。</p>
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final JvmInfoService jvmInfoService;
    private final HeapService heapService;
    private final GcStatsService gcStatsService;
    private final GcEventStore eventStore;
    private final RetainedMemory retainedMemory;
    private final LogFileService logFileService;
    private final ScenarioRegistry scenarioRegistry;
    private final ScenarioRunner scenarioRunner;
    private final ScenarioHistory scenarioHistory;
    private final GcLogWatcher logWatcher;
    private final SideLogWatchers sideLogWatchers;
    private final JfrService jfrService;

    private final AtomicReference<ScenarioResult> lastScenario = new AtomicReference<>();

    public DemoController(JvmInfoService jvmInfoService,
                          HeapService heapService,
                          GcStatsService gcStatsService,
                          GcEventStore eventStore,
                          RetainedMemory retainedMemory,
                          LogFileService logFileService,
                          ScenarioRegistry scenarioRegistry,
                          ScenarioRunner scenarioRunner,
                          ScenarioHistory scenarioHistory,
                          GcLogWatcher logWatcher,
                          SideLogWatchers sideLogWatchers,
                          JfrService jfrService) {
        this.jvmInfoService = jvmInfoService;
        this.heapService = heapService;
        this.gcStatsService = gcStatsService;
        this.eventStore = eventStore;
        this.retainedMemory = retainedMemory;
        this.logFileService = logFileService;
        this.scenarioRegistry = scenarioRegistry;
        this.scenarioRunner = scenarioRunner;
        this.scenarioHistory = scenarioHistory;
        this.logWatcher = logWatcher;
        this.sideLogWatchers = sideLogWatchers;
        this.jfrService = jfrService;
    }

    /**
     * 聚合状态：JVM/G1 参数、堆、GC 统计、retained、最近事件、日志源与场景进度。
     *
     * <p>前端每 2s 轮询一次；读取的都是低成本的 MXBean/缓存，不触发 GC 或堆转储。</p>
     */
    @GetMapping("/status")
    public StatusResponse status() {
        LogWatchStatus watchStatus = logWatcher.status();
        List<com.example.gcdemo.gc.SideLogWatchers.StringDedupStats> dedup = sideLogWatchers.dedupStats();
        return new StatusResponse(
                jvmInfoService.info(),
                heapService.snapshot(),
                gcStatsService.snapshot(),
                new StatusResponse.RetainedInfo(retainedMemory.usedBytes(), retainedMemory.details()),
                eventStore.recent(30),
                watchStatus,
                scenarioRunner.isRunning(),
                lastScenario.get(),
                scenarioRunner.currentProgress(),
                new StatusResponse.SideLogs(
                        dedup.isEmpty() ? null : dedup.getFirst(),
                        sideLogWatchers.unloadedTotal(),
                        sideLogWatchers.lastUnloadAt()),
                Instant.now()
        );
    }

    /**
     * 返回 JVM 与 G1 flag 快照，等价于 status 中的 jvm 部分。
     */
    @GetMapping("/config")
    public Object config() {
        return jvmInfoService.info();
    }

    /**
     * 返回场景元数据，前端据此动态渲染参数输入框。参数名即 /run 请求体的键。
     */
    @GetMapping("/scenarios")
    public List<ScenarioInfo> scenarios() {
        return scenarioRegistry.infos();
    }

    /**
     * 执行一个场景。
     *
     * <p>请求体可省略；键名必须匹配该场景的参数（GET /api/scenarios），值会按
     * min/max 严格校验，未知/越界返回 400。Runner 拒绝并发执行（409）。</p>
     */
    @PostMapping("/scenarios/{id}/run")
    public ScenarioResult run(@PathVariable String id,
                              @RequestBody(required = false) Map<String, Integer> params) {
        ScenarioResult result = scenarioRunner.run(id, params);
        lastScenario.set(result);
        return result;
    }

    /**
     * 清空 retained 引用；{@code gc=false} 时只清引用不触发显式 GC。
     */
    @PostMapping("/scenarios/reset")
    public ScenarioResult reset(@RequestParam(defaultValue = "true") boolean gc) {
        ScenarioResult result = scenarioRunner.run("reset", Map.of(),
                gc ? Map.of() : Map.of("gc", "false"));
        lastScenario.set(result);
        return result;
    }

    /**
     * 请求取消正在运行的场景（协作式取消，在下个检查点生效）。
     */
    @PostMapping("/scenarios/current/cancel")
    public Map<String, Object> cancel() {
        boolean accepted = scenarioRunner.cancelCurrent();
        return Map.of("accepted", accepted);
    }

    /**
     * 最近 N 次场景运行（含被取消的），最新在前；A/B 对比面板的数据源。
     */
    @GetMapping("/scenarios/history")
    public List<ScenarioResult> history() {
        return scenarioHistory.recent();
    }

    /**
     * 增量事件接口；limit 在服务端再次限制到 1..500。
     */
    @GetMapping("/gc/events")
    public List<GcEvent> events(@RequestParam(defaultValue = "0") long afterSeq,
                                @RequestParam(defaultValue = "100") int limit) {
        return eventStore.since(afterSeq, Math.max(1, Math.min(limit, 500)));
    }

    /**
     * 列出 logs/ 下的可读日志文件，GC 文件优先。
     */
    @GetMapping("/logs")
    public List<LogFileInfo> logs() {
        return logFileService.list();
    }

    /**
     * 读取文件最后 N 行。
     */
    @GetMapping("/logs/tail")
    public LogTail tail(@RequestParam String name,
                        @RequestParam(defaultValue = "200") int lines) {
        return logFileService.tail(name, lines);
    }

    /**
     * 下载日志或 JFR 录制文件（.log / .jfr，防穿越守卫与 tail 相同）。
     */
    @GetMapping("/logs/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @RequestParam String name) {
        java.nio.file.Path file = logFileService.resolveForDownload(name);
        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.FileSystemResource(file);
        String contentType = name.endsWith(".jfr")
                ? "application/octet-stream"
                : "text/plain; charset=utf-8";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getFileName() + "\"")
                .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, contentType)
                .body(resource);
    }

    // ---- JFR ----

    /**
     * 开始一次 JFR 录制；请求体 {"seconds": 60} 可省略，上限 demo.max-jfr-seconds。
     */
    @PostMapping("/jfr/start")
    public JfrService.JfrStatus jfrStart(@RequestBody(required = false) Map<String, Integer> body) {
        return jfrService.start(body == null ? null : body.get("seconds"));
    }

    /**
     * 停止当前录制并落盘到 logs/jfr-*.jfr。
     */
    @PostMapping("/jfr/stop")
    public JfrService.JfrStatus jfrStop() {
        return jfrService.stop();
    }

    /**
     * JFR 录制状态（是否运行、剩余秒、最近落盘文件）。
     */
    @GetMapping("/jfr/status")
    public JfrService.JfrStatus jfrStatus() {
        return jfrService.status();
    }
}
