# G1 调优与故障排查

## 1. 先确认你在看什么

一次 G1 停顿通常由以下部分构成：

```text
总暂停
├── 根扫描（Ext Root Scanning / Code Root Scanning）
├── RSet 更新（Remembered Sets）
├── RSet 扫描（Scanned Cards）
├── 对象复制（Object Copy）
├── 引用处理（Reference Processing）
├── 类卸载 / 字符串去重等并发工作
└── 其它（Termination、GC worker 调度、safepoint）
```

不要只看 `MaxGCPauseMillis`。先确认：

- GC 类型（Young / Mixed / Full）
- 是 STW 还是并发阶段
- 暂停的主要阶段
- 分配率、存活集、晋升率
- 是否有 Full GC / Evacuation Failure
- 是否受容器 CPU/内存限制

工具见 [06-tools-and-observability.md](06-tools-and-observability.md)。

## 2. Young GC 太频繁

**症状**：GC 次数多、单次很短、应用吞吐下降。

排查：

1. 分配率是否过高（`allocation-storm` 可复现）。
2. Young 是否过小（pause target 太紧、`G1MaxNewSizePercent` 限制）。
3. 是否有大量短命大对象/Humongous。
4. 是否 Survivor 太小导致提前晋升。

可能的动作：

- 减少临时对象和大数组。
- 适度放宽 `MaxGCPauseMillis`。
- 调整 `G1NewSizePercent` / `G1MaxNewSizePercent`（experimental，先实验）。
- 增加堆（但不能只靠加堆）。
- 对可复用 buffer 使用池化，避免每次分配大数组。

日志证据：

```text
Pause Young (Normal) (G1 Evacuation Pause)
Eden regions: 200->0(200)
```

## 3. Young pause 变长

**症状**：单次 Young 暂停上升，GC 次数未必增加。

按阶段排查：

| 阶段高 | 可能原因 | 方向 |
|---|---|---|
| `Remembered Sets` / `Scanned Cards` | dirty card 多、refinement 跟不上 | 调整 RSet 参数、减少跨 Region 引用 |

| `Object Copy` | 存活对象多/晋升多 | 降低存活集、检查 Survivor/晋升参数 |
| Reference | 软/弱/虚引用、finalizer | 检查引用处理与 finalization |
| Ext Root Scanning | 根对象多、类加载器多 | 检查线程、类加载结构 |
| Code Root Scanning | JIT 代码缓存引用多 | 通常不是首要调优点 |

注意：这些阶段只在 debug 级日志中详细显示：

```bash
-Xlog:gc+phases=debug
```

## 4. 看不到 Mixed GC

**症状**：日志只有 Young，没有 `Pause Young (Mixed)`。

检查：

1. 是否跨过 IHOP？看 `Concurrent Start` 或 `Concurrent Mark Cycle`。
2. Old Region 是否真的存在可回收垃圾？
   - 如果对象全是长寿命，Mixed 没有可回收 CSet。
   - 本项目的 Mixed 场景特意先制造“晋升后死亡”的批次。
3. `G1HeapWastePercent` 是否太高，导致 G1 不愿意回收？
4. `G1MixedGCLiveThresholdPercent` 是否太低，导致没有 Region 满足条件？
5. 日志 watcher 是否正常（`/api/status` 的 `logWatcher.available`）。

实验：

```bash
./scripts/run.sh
curl -X POST localhost:8080/api/scenarios/mixed-gc/run -H 'Content-Type: application/json' -d '{}'
```

如果仍然不出现，可增大场景追加压力，或适度降低 IHOP。

## 5. 频繁 Full GC

**先看原因**，不同 cause 对应不同处理：

| 日志 cause | 常见原因 | 处理 |
|---|---|---|
| `System.gc()` | 代码/框架显式调用 | 查找调用；考虑 `DisableExplicitGC` 或 `ExplicitGCInvokesConcurrent` |
| `Allocation Failure` | 堆不足或回收不及时 | 增加堆、降低分配率、调整 IHOP |
| `Evacuation Failure` | 晋升/复制空间不足 | 提高 `G1ReservePercent`、检查存活集、增加堆 |
| `G1 Compaction Pause` | 兜底压缩 | 找根因，不要把它当正常路径 |
| Metadata / Metaspace | 类加载器泄漏 | 检查 ClassLoader、动态代理、脚本引擎 |

如果确实需要显式 GC，可以先实验：

```bash
EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent' ./scripts/run.sh
```

它可能把显式 GC 变成并发周期，但**不应成为修复 Full GC 的主要手段**。

## 6. To-space exhausted / Evacuation Failure

**含义**：Evacuation 时目标空间不足，存活对象无法复制，可能触发 Full GC。

常见原因：

- 存活对象太多，堆容量不足。
- Survivor/Old 预留不足。
- 晋升过快，Old 没有空间。
- Humongous/碎片导致连续空间不足。
- 分配速率过高，GC 追不上。

动作：

1. 先加堆或减少 live set。
2. 观察 `G1ReservePercent`（默认 10）。
3. 检查 Survivor 与晋升参数。
4. 检查大对象/碎片。
5. 关注 Full GC 后的 Phase 4: Compact heap。

日志：

```text
To-space exhausted
Evacuation Failure
Pause Full (G1 Compaction Pause)
```

## 7. Humongous 抖动

**症状**：`Humongous regions` 大量出现，GC 频繁，堆上有碎片。

判定：对象 > region/2。

动作：

- 拆小大数组/大字符串/序列化 buffer。
- 复用 buffer，避免每次 new。
- 评估 Region 大小是否过小。
- 避免不同大小的大对象反复分配造成连续空间碎片。
- 用 `-Xlog:gc+humongous=debug` 观察。

注意：把 Region 调大可能减少 H 对象，但也会增大 RSet 粒度并影响碎片；必须实测。

## 8. RSet / Refinement 压力

**症状**：

- `Remembered Sets`/`Scanned Cards` 高，或 `gc,remset` 统计异常。
- refinement 队列积压。
- 跨 Region 引用密集。

动作：

- 减少跨 Region 的长期引用图。
- 检查 Region 大小是否导致引用被过度切分。
- 查看 `G1ConcRefinementThreads`、`G1RSetUpdatingPauseTimePercent` 与 `gc,refine,stats`。
- 使用 `cross-region` 场景复现并对比 verbose 日志。

不要在没有阶段证据时盲目增加 refinement 线程。

## 9. Metaspace / 类卸载问题

**症状**：Metaspace 持续上涨，Full GC 后不回落。

可能原因：

- ClassLoader 泄漏（应用服务器、脚本引擎、动态代理、反射生成类）。
- 类实例/静态字段仍然存活。
- 类卸载未发生。
- `MaxMetaspaceSize` 限制导致 OOM。

排查：

```bash
jcmd <pid> GC.class_histogram
jcmd <pid> GC.heap_dump logs/heap.hprof
jcmd <pid> VM.metaspace
```

日志：

```text
-Xlog:class+unload=info
-Xlog:gc+metaspace=debug
```

项目 `class-unload` 场景提供对照实验。

## 10. 容器与 CPU 限制

- 使用 cgroup 感知的 JVM（现代 JDK 默认支持）。
- 用 `-XX:MaxRAMPercentage` 代替固定大堆。
- 用 `-XX:ActiveProcessorCount` 显式匹配 CPU quota。
- GC 线程数由 CPU 推导；容器 CPU 很低时，STW 阶段可能达不到预期并行度。
- 容器内存限制和 JVM 堆不是一回事；还需要为 Metaspace、线程栈、直接内存、JVM 自身留出空间。

## 11. 直接内存 / Native 内存

G1 只管理 Java 堆。以下问题不会被 Full GC 修复：

- `DirectByteBuffer` / Netty direct memory
- JNI native allocation
- 线程栈
- 代码缓存
- GC 自身结构（RSet、卡表等）

结合 `jcmd VM.native_memory`（需要 `-XX:NativeMemoryTracking=summary`）和系统 `pmap`/`vmmap` 分析。

## 12. 一个务实的调优顺序

1. 用 G1 GC 日志确认暂停来源和 Full GC 原因。
2. 优化分配率和对象生命周期（通常收益最大）。
3. 确保堆能容纳 live set，并留出晋升空间。
4. 设定合理 pause target，而不是追求极低值。
5. 只在有证据时调整 IHOP / Mixed / Region / 线程。
6. 用相同 workload 复测 P50/P95/P99、吞吐、CPU、Full GC 次数。
7. 变更一次只动一到两个参数，记录对比。

## 13. 本项目可用于复现的故障实验

| 实验 | 命令 / 接口 | 观察 |
|---|---|---|
| 显式 Full GC | `POST /api/scenarios/full-gc/run` | `Pause Full`、压缩 |
| 禁止显式 GC | `EXTRA_JAVA_OPTS='-XX:+DisableExplicitGC'` | 接口提示 no-op |
| 显式 GC 并发化 | `EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent'` | 可能进入并发周期 |
| 高分配率 | `allocation-storm` | 连续 Young GC |
| H 碎片 | `humongous` + reset | H regions 增减 |
| 晋升 | `promotion` | Old 增长 |
| Mixed | `mixed-gc` | 并发周期与 Mixed |
| 类卸载 | `class-unload` | Metaspace 与 class unload |
| RSet 压力 | `cross-region` + verbose | Remembered Sets / Scanned Cards |

## 14. 反模式清单

- 用 `System.gc()` 定时“清理内存”。
- 把 `MaxGCPauseMillis` 设成 1ms 并期待 G1 满足。
- 看到 Full GC 就盲目加 `-Xmx`。
- Mixed 场景只保留活对象。
- 为了消除 Humongous 把所有 Region 调到 32MB。
- 用 `DisableExplicitGC` 掩盖框架里的显式 GC 调用。
- 在生产中长期开启 `gc*=debug` 却不做日志轮转。
- 只看 GC 次数，不看暂停分布、分配率和 CPU。
