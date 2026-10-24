package com.example.gcdemo.scenario;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.gc.GcEventStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 场景执行的单飞（single-flight）协调器。
 *
 * <p>场景会分配数 GB 内存，必须串行；这里用 CAS 保证同一时刻只有一个场景。
 * 职责还包括：按 ParamSpec 严格校验请求参数（未知/越界都是 400 带教学提示）、
 * 构造 {@link ScenarioContext}（墙钟预算/进度/取消）、把结果写入运行历史，
 * 以及在结束前把 GC 日志消费到追平（单次 poll 最多 512KB，可能需要多轮）。</p>
 */
@Service
public class ScenarioRunner {

    private final ScenarioRegistry registry;
    private final ScenarioSupport support;
    private final ScenarioHistory history;
    private final DemoProperties properties;
    private final GcEventStore eventStore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ScenarioContext> currentContext = new AtomicReference<>();

    public ScenarioRunner(ScenarioRegistry registry,
                          ScenarioSupport support,
                          ScenarioHistory history,
                          DemoProperties properties,
                          GcEventStore eventStore) {
        this.registry = registry;
        this.support = support;
        this.history = history;
        this.properties = properties;
        this.eventStore = eventStore;
    }

    /**
     * 串行执行一个场景并补齐公共观测字段。
     */
    public ScenarioResult run(String id, Map<String, Integer> rawParams) {
        return run(id, rawParams, Map.of());
    }

    /**
     * 带非数值 flags 的执行入口（目前只有 reset 的 {@code gc=false}）。
     */
    public ScenarioResult run(String id, Map<String, Integer> rawParams, Map<String, String> flags) {
        GcScenario scenario = registry.require(id);
        ScenarioParams params = new ScenarioParams(validate(scenario, rawParams), flags);
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "另一个 GC 场景正在运行",
                    "场景会分配大量内存，必须串行执行；请等待当前请求结束。");
        }
        ScenarioContext ctx = new ScenarioContext(properties.getMaxScenarioWallMs());
        currentContext.set(ctx);
        Instant start = Instant.now();
        long gcBefore = support.stats().totalCollections();
        try {
            ctx.phase(scenario.info().title(), 0);
            ScenarioOutcome outcome;
            try {
                outcome = scenario.run(params, ctx);
            } catch (ScenarioCancelledException cancelled) {
                // 取消不是错误：返回已发生的部分证据，前端标记“已取消”。
                support.waitForLogCaughtUp();
                ctx.phase("已取消", 100);
                return finish(scenario, start, gcBefore, cancelledOutcome(cancelled), true);
            }
            ctx.phase("汇总事件", 100);
            // Drain the async log pipeline (one poll reads at most 512KB) so the response
            // includes the final pauses; replaces the old fixed 700ms guess.
            support.waitForLogCaughtUp();
            return finish(scenario, start, gcBefore, outcome, false);
        } finally {
            currentContext.set(null);
            running.set(false);
        }
    }

    private ScenarioResult finish(GcScenario scenario, Instant start, long gcBefore,
                                  ScenarioOutcome outcome, boolean cancelled) {
        long gcAfter = support.stats().totalCollections();
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        ScenarioResult result = new ScenarioResult(
                scenario.id(),
                scenario.info().title(),
                elapsed,
                outcome.allocatedMb(),
                outcome.retainedMb(),
                gcBefore,
                gcAfter,
                gcAfter - gcBefore,
                eventStore.typesSince(start.minusMillis(100)),
                outcome.observations(),
                outcome.hint(),
                outcome.metrics(),
                cancelled
        );
        history.add(result);
        return result;
    }

    private ScenarioOutcome cancelledOutcome(ScenarioCancelledException cancelled) {
        return new ScenarioOutcome(
                0,
                support.retained().usedBytes() / com.example.gcdemo.util.ByteSize.MB,
                List.of("在阶段「" + cancelled.phase() + "」被取消"),
                "已中止；取消前的 GC 事件仍保留在时间线与日志中。",
                Map.of("cancelledPhase", cancelled.phase())
        );
    }

    /**
     * 按场景 ParamSpec 严格校验请求参数：未知键、空值、越界都返回 400。
     */
    static Map<String, Integer> validate(GcScenario scenario, Map<String, Integer> rawParams) {
        if (rawParams == null || rawParams.isEmpty()) {
            return Map.of();
        }
        List<ParamSpec> specs = scenario.info().params();
        Map<String, Integer> validated = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : rawParams.entrySet()) {
            String key = entry.getKey();
            Integer value = entry.getValue();
            ParamSpec spec = specs.stream()
                    .filter(candidate -> candidate.name().equals(key))
                    .findFirst()
                    .orElseThrow(() -> unknownParam(scenario, key, specs));
            if (value == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "参数 " + key + " 缺少数值",
                        "应为 1 个整数，单位 " + spec.unit() + "。");
            }
            if (value < spec.min() || value > spec.max()) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "参数 " + key + " 越界: " + value,
                        "允许范围 " + spec.min() + " ~ " + spec.max() + " " + spec.unit() + "。");
            }
            validated.put(key, value);
        }
        return validated;
    }

    private static ApiException unknownParam(GcScenario scenario, String key, List<ParamSpec> specs) {
        String names = specs.isEmpty()
                ? "（该场景无可调参数，请求体应为 {}）"
                : specs.stream().map(ParamSpec::name).toList().toString();
        return new ApiException(HttpStatus.BAD_REQUEST,
                "未知参数: " + key + "（场景 " + scenario.id() + "）",
                "可用参数: " + names + "；参数说明见 GET /api/scenarios。");
    }

    /**
     * 请求取消当前场景；返回是否找到了正在运行的场景。
     */
    public boolean cancelCurrent() {
        ScenarioContext ctx = currentContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.cancel();
        return true;
    }

    /**
     * @return 当前场景进度；空闲时为 null
     */
    public ScenarioProgress currentProgress() {
        ScenarioContext ctx = currentContext.get();
        return ctx == null ? null : ctx.progress();
    }

    public boolean isRunning() {
        return running.get();
    }
}
