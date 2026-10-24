# 场景实验手册

所有场景串行执行，接口为：

```http
POST /api/scenarios/{id}/run
Content-Type: application/json
```

请求体可以省略，也可以只覆盖想调整的参数：

```json
{"totalMb": 512, "churnMb": 256, "count": 32, "threads": 4, "durationMs": 8000, "chunkKb": 256, "objectMb": 2}
```

页面按钮会自动读取当前堆与 `/api/scenarios` 返回的默认值。命令行示例默认端口 8080。

---

## 1. `young-gc`：Young GC

**目的**：制造短命对象洪峰，耗尽 Eden，观察一次普通 Young GC。

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/young-gc/run \
  -H 'Content-Type: application/json' -d '{}'
```

默认参数：

- lab 配置下总分配量约为堆的 60%，最多 2048MB；
- chunk 256KB，避免落入 Humongous；
- 最多等待 2 次 GC 或 12 秒。

预期日志：

```text
Pause Young (Normal) (G1 Evacuation Pause)
Eden regions: ...->0(...)
```

如果先出现了 `Concurrent Start` 或 `Mixed`，说明之前的实验留下了 Old 垃圾或自适应 IHOP 较早触发；先执行 reset，或查看 `03-gc-log-analysis.md`。教学项目不强制每次都是 Normal。

观察点：

- `before->after`：本次回收释放了多少堆。
- `Eden regions`：年轻代 Region 数量变化。
- pause 与 CPU `user/sys/real`。
- 分配率越高，Young GC 越频繁。

---

## 2. `promotion`：晋升到 Old

**目的**：保留一批对象，让它经历 Survivor 并被晋升；随后持续制造年轻代压力。

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/promotion/run \
  -H 'Content-Type: application/json' -d '{"totalMb":256,"churnMb":512}'
```

默认：

- 保留约 12.5% 堆（上限 384MB）；
- 再分配约 25% 堆（上限 768MB）作为短命压力；
- lab 的 `MaxTenuringThreshold=3` 加快晋升。

观察：

- `/api/status` 中 Old used 明显增长；
- 日志中 `Survivor regions` / `Old regions`；
- `Object Copy` 阶段成本随存活对象增长。

**注意**：全存活批次不会产生可回收 Old 垃圾；这正是 Mixed 场景需要单独设计的原因。

---

## 3. `mixed-gc`：并发标记 + Mixed

**目的**：制造已晋升的垃圾，保留一个 live set，跨过 IHOP 触发并发周期，最终观察到真正的 `Pause Young (Mixed)`。

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/mixed-gc/run \
  -H 'Content-Type: application/json' -d '{}'
```

默认三阶段：

1. 分配约 20% 堆的 dead 批次，保留并制造压力，使其晋升到 Old；
2. 释放 dead 批次，保留约 10% 堆的 live 批次；
3. 持续分配，等待 Concurrent Start → Remark → Cleanup → 下一次 Mixed。

预期日志：

```text
Pause Young (Concurrent Start) (G1 Evacuation Pause)
Concurrent Mark Cycle
Pause Remark
Pause Cleanup
Pause Young (Prepare Mixed) (G1 Evacuation Pause)
Pause Young (Mixed) (G1 Evacuation Pause)
```

失败时：

- 看 `/api/status` 的 `logWatcher.available`；
- 确认 `IHOP` 与当前堆占用；
- 增大追加压力（场景中的 `count` 参数实际表示追加压力 MB）；
- 检查 Old Region 是否全是活对象。

2G 下本机实测约 1.4 秒完成；4G 下参数按比例放大，但总分配有上限保护。

---

## 4. `humongous`：大对象

**目的**：分配大于 Region 一半的数组，观察连续的 Humongous Region、占用和回收。

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/humongous/run \
  -H 'Content-Type: application/json' -d '{"count":32,"objectMb":2}'
```

默认对象大小 = `regionSize/2 + 1MB`（最少 2MB）：

| 堆 | Region | 默认对象 | H 阈值 |
|---|---|---|---|
| 2G | 1M | 2MB | 512KB |
| 4G | 2M | 2MB | 1MB |
| `REGION_SIZE=4m` | 4M | 3MB | 2MB |

日志：

```text
Humongous regions: 96->48
```

补充：

- 数组对象头也算对象大小，所以“刚好半 Region”的 byte[] 可能成为 H 对象。
- 256KB chunk 是普通分配的安全值；不要把普通 churn 调到 Region/2 以上。
- 释放引用后运行 reset，可观察 H Region 回落。

---

## 5. `allocation-storm`：高分配率

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/allocation-storm/run \
  -H 'Content-Type: application/json' -d '{"threads":4,"durationMs":10000,"chunkKb":256}'
```

观察：

- 连续 `Pause Young`；
- 暂停次数随分配率上升；
- 可能进入并发周期；
- 单场景总分配达到上限会提前结束，返回结果会注明。

对比实验：

```bash
PROFILE=low-latency ./scripts/run.sh   # 50ms target，GC 更频繁但单次更短
PROFILE=throughput ./scripts/run.sh    # 500ms target，GC 更少但单次更长
```

---

## 6. `full-gc`：显式 Full GC

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/full-gc/run \
  -H 'Content-Type: application/json' -d '{}'
```

预期：

```text
Pause Full (System.gc())
Phase 4: Compact heap
```

对比：

```bash
# 禁用显式 GC
EXTRA_JAVA_OPTS='-XX:+DisableExplicitGC' ./scripts/run.sh

# 尝试转成并发周期
EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent' ./scripts/run.sh
```

接口会检测这两个参数并返回相应提示。`demo.allow-full-gc=false` 可禁用该场景。

---

## 7. `cross-region`：RSet / 写屏障

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/cross-region/run \
  -H 'Content-Type: application/json' -d '{"count":250000,"churnMb":384}'
```

它会创建 25 万个带随机跨对象引用的 Node，保留后制造年轻代压力。

需要 `PROFILE=verbose` 才能看到更多阶段细节：

```text
Remembered Sets
Scanned Cards
gc,remset
gc,refine,stats
```

观察：

- 节点越多，RSet 的维护与扫描成本越高；
- 跨 Region 引用是 G1 暂停的固定成本之一；
- 不是“暂停长就调大 IHOP”，要区分是 Remembered Sets/Scanned Cards 还是 Object Copy。

---

## 8. `string-dedup`：字符串去重

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/string-dedup/run \
  -H 'Content-Type: application/json' -d '{"count":30000,"churnMb":1024}'
```

默认 lab 开启：

```text
-XX:+UseStringDeduplication -XX:StringDeduplicationAgeThreshold=1
```

字符串内容相同但底层数组独立；经历多次 Young GC 后，达到年龄阈值的字符串参与并发去重。

- 详细统计需要 `PROFILE=verbose`（`stringdedup` 独立日志）。
- 如果显示 `UseStringDeduplication=false`，说明当前启动参数没有开启。
- 去重节省堆，但会消耗 CPU；对短命字符串通常没有收益。

---

## 9. `class-unload`：类卸载

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/class-unload/run \
  -H 'Content-Type: application/json' -d '{"count":500}'
```

实现：

1. 使用 JDK Compiler 动态生成 `DynamicClasses$C0..C499`；
2. 用独立 `URLClassLoader` 加载；
3. 丢弃 ClassLoader 引用并调用 `System.gc()`；
4. 对比 Metaspace 使用量。

日志在独立文件：

```bash
grep 'unloading class' logs/class-unload-*.log | head
```

观察：

- `class,unload` 行出现；
- Metaspace 回落；
- 如果没有回落，可能是 ClassLoader/Class 仍被引用，或 GC 未发生。

需要完整 JDK（javac），JRE 环境会返回 501。

---

## 10. `reset`

```bash
curl -s -X POST http://127.0.0.1:8080/api/scenarios/reset
```

清空所有 retained 引用，默认执行 `System.gc()`。用于场景之间的复位。

---

## 一键生成可复现实验

```bash
./scripts/generate-sample-logs.sh
```

脚本会：

1. 启动 2G verbose 实例；
2. 依次运行全部场景；
3. 启动 4G 实例，做 Young/Humongous/Full 对比；
4. 将裁剪后的日志写入 `docs/samples/gc-scenarios-2g.log` 与 `gc-scenarios-4g.log`；
5. 保留每个场景的 JSON 响应在 `logs/scenario-*.json`。

分析：

```bash
./scripts/analyze-gc-log.sh logs/gc-xxxx.log
```

---

## 常见操作问题

| 问题 | 处理 |
|---|---|
| 页面没有 GC 日志 | 必须用 `scripts/run.sh` 启动，确保 `-Xlog` 指向 `logs/`；否则会显示 JMX fallback |
| 场景返回 409 | 另一个场景在运行，等待或刷新页面 |
| 场景返回 400 | 参数超过 guard 上限，或请求格式错误 |
| Mixed 不出现 | 先 reset；检查 IHOP、存活对象、追加压力与日志 watcher |
| Full GC 不出现 | 检查 `DisableExplicitGC`、`ExplicitGCInvokesConcurrent`、`allow-full-gc` |
| Class unload 无日志 | 检查独立 class-unload 日志、JDK 是否包含 javac、ClassLoader 是否仍然可达 |

---

## v2 补充：场景响应中的一手证据（2026-10-24 重构）

- **string-dedup**：响应直接包含本轮去重统计（`metrics.dedupedCount`、`metrics.dedupAvgPercent`，observations 里有人类可读版本）——来自 stringdedup-*.log 的实时解析，不必再手动翻文件。
- **class-unload**：响应包含 `metrics.unloadedClasses`（本轮实际观测到的 `unloading class` 行数），与 Metaspace before→loaded→after 三点对照。
- **mixed-gc**：verbose profile 下事件 details 里的 `ergo` 字段（如 `do not continue mixed GCs (candidate old regions not available)`）直接回答"为什么 Mixed 停了"。
- **所有场景**：可在控制台"运行历史与 A/B 对比"面板勾选两次运行做单变量对比；长场景显示阶段进度并支持取消（取消结果保留部分证据）。
- **JFR**：控制台 JFR 面板可一键录制（默认 60s），落盘 logs/jfr-*.jfr 后直接下载，用 JDK Mission Control 打开对照 GC 事件。
