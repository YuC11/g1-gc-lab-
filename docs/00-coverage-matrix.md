# G1 核心知识面覆盖矩阵

> 目标不是罗列 JVM 的每一个 flag，而是保证 G1 的核心知识面在项目里都有“可读、可配、可运行、可验证”的落点。参数默认值以本机 **JDK 21.0.11** 的 `-XX:+PrintFlagsFinal` 为准；experimental 参数需要 `-XX:+UnlockExperimentalVMOptions`。

## 1. 架构与对象生命周期

| 知识域 | 关键点 | 项目落点 | 验证方式 |
|---|---|---|---|
| 设计目标 | 可预测停顿、吞吐与延迟折中、分代 + Region | `01-g1-internals.md` 第一部分 | 文档 |
| 堆布局 | Eden / Survivor / Old / Humongous / Free Region | 面板堆组成、`Humongous` 场景 | `/api/status`、GC 日志 heap 行 |
| Region 大小 | 约 2048 个 Region、2 的幂、1–32MB；2G→1M、4G→2M | `HEAP=2g/4g`、`REGION_SIZE=1m/2m/4m` | `/api/config`、启动日志 `Heap Region Size` |
| Region 状态转换 | 空闲、Eden、Survivor、Old、Humongous、CSet | `01-g1-internals.md`、面板 Region 信息 | 日志 `Eden regions` 等 |
| CSet | 本次回收的 Region 集合，Young/Mixed 不同 | `mixed-gc` 场景 | `Pause Young (Mixed)` |
| TLAB / PLAB | 线程本地分配缓冲、晋升复制缓冲 | `01-g1-internals.md`；verbose 事件 `youngPlab*Bytes` details 字段 | 文档；`PROFILE=verbose` 下事件 details 的 PLAB allocated/wasted/used |
| 分配与晋升 | 年龄、tenuring threshold、Survivor 自适应、目标存活率 | `promotion` 场景、`MaxTenuringThreshold=3` lab 配置 | Old 池增长、Survivor 日志 |
| Evacuation | 复制存活对象、forwarding pointer、失败处理 | `05-tuning-and-troubleshooting.md` | 日志 `Evacuation Failure` |
| 引用处理 | 软/弱/虚引用、finalizer | `06-tools-and-observability.md`；verbose 事件 `softRef/weakRef/phantomRef/finalRef` details | 文档；verbose 下事件 details 的 Discovered/Dropped/Processed |

## 2. 并发标记与写屏障

| 知识域 | 关键点 | 项目落点 | 验证方式 |
|---|---|---|---|
| SATB | 初始快照标记、并发正确性、pre-write barrier | `01-g1-internals.md` | 文档 |
| Post-write barrier | 卡表标记、dirty card queue | `cross-region` 场景 | Remembered Sets / Scanned Cards |
| Mark bitmap | 标记位图、TAMS、并发分配 | `01-g1-internals.md` | 日志 concurrent marking |
| RSet | 跨 Region 引用集合、粗化、成本 | `cross-region` 场景 | `Remembered Sets` / `Scanned Cards` |
| 卡表与 refinement | card table、refinement 线程、green/yellow/red zone | `PROFILE=verbose`、`G1ConcRefinementThreads` | 日志 `gc,remset` / `gc,refine,stats` |
| 并发周期 | Concurrent Start、Mark、Remark、Cleanup | `mixed-gc` 场景 | 日志对应行 |
| 类卸载 | ClassUnloadingWithConcurrentMark、Metaspace 回收 | `class-unload` 场景 | `class,unload` 日志 |
| 字符串去重 | `UseStringDeduplication`、年龄阈值、CPU 成本 | `string-dedup` 场景、lab 开启 | `stringdedup` 独立日志 |
| Reference Processing | 引用处理器、ParallelRefProcEnabled | 事件 details（Discovered/Dropped/Processed，verbose） | verbose 运行后查看事件表 tooltip 的引用统计 |

## 3. Humongous 与碎片

| 知识域 | 关键点 | 项目落点 | 验证方式 |
|---|---|---|---|
| Humongous 判定 | 对象 > region/2，连续 H region | `humongous` 场景 | 日志 `Humongous regions` |
| Region 大小影响 | 2G/1M 与 4G/2M 的门槛差异 | `HEAP=2g/4g`、`REGION_SIZE` | `/api/config`、H 场景 |
| 分配与回收 | H region 直接进入 Old；cleanup/mixed/full 回收 | 场景 + `reset` | 日志 Humongous regions 减少 |
| 碎片与 Full GC | 大对象碎片导致分配失败/Full GC | `05-tuning-and-troubleshooting.md` | 文档 + Full 场景 |
| 规避策略 | 拆小对象、调整 region、复用缓冲、控制大数组 | 文档 | 调优建议 |

## 4. 回收周期与暂停类型

| 类型 | 何时发生 | 项目落点 | 日志关键字 |
|---|---|---|---|
| Young-only | Eden 耗尽，普通年轻代回收 | `young-gc`、`allocation-storm` | `Pause Young (Normal)` |
| Concurrent Start | 堆占用跨过 IHOP，启动并发标记 | `mixed-gc` | `Pause Young (Concurrent Start)` |
| Prepare Mixed | 并发标记完成，准备进入混合回收 | `mixed-gc` | `Pause Young (Prepare Mixed)` |
| Remark | 重新标记 SATB 残留引用 | `mixed-gc` | `Pause Remark` |
| Cleanup | 统计存活、回收空 Region、重置标记状态 | `mixed-gc`、`humongous` | `Pause Cleanup` |
| Mixed | 年轻代 + 低存活率 old region | `mixed-gc` | `Pause Young (Mixed)` |
| Full | 显式 GC、晋升失败、H 分配失败等兜底 | `full-gc`、`class-unload` | `Pause Full`、Phase 4: Compact heap |
| 触发原因 | Metadata GC Threshold、G1 Evacuation Pause、G1 Humongous Allocation、System.gc() 等 | 所有场景 | pause 行的 cause |

## 5. 参数体系（JDK 21）

| 类别 | 参数 | 默认值 | 项目落点 |
|---|---|---|---|
| 开关 | `UseG1GC` | true | 启动脚本、参数面板 |
| 堆 | `-Xms` / `-Xmx` | 机器相关 | 2G/4G |
| 暂停目标 | `MaxGCPauseMillis` | 200 | lab 100、low-latency 50、throughput 500 |
| Region | `G1HeapRegionSize` | ergonomic | auto / 1m / 2m / 4m |
| 并发阈值 | `InitiatingHeapOccupancyPercent` | 45 | lab 30 |
| 自适应 IHOP | `G1UseAdaptiveIHOP` | true | 面板 |
| 年轻代 | `G1NewSizePercent`（experimental） | 5 | lab 10 |
| 年轻代上限 | `G1MaxNewSizePercent`（experimental） | 60 | lab 35 |
| 混合回收 | `G1MixedGCLiveThresholdPercent`（experimental） | 85 | 文档 |
| 混合次数 | `G1MixedGCCountTarget` | 8 | low-latency 4 / throughput 16 |
| 混合 old 上限 | `G1OldCSetRegionThresholdPercent`（experimental） | 10 | 文档/profile |
| 堆浪费 | `G1HeapWastePercent` | 5 | 文档 |
| 预留 | `G1ReservePercent` | 10 | 文档 |
| 周期 GC | `G1PeriodicGCInterval` / `G1PeriodicGCInvokesConcurrent` / `G1PeriodicGCSystemLoadThreshold` | 0 / true / 0 | 文档 |
| 并行线程 | `ParallelGCThreads` | 由 CPU 推导 | 面板 |
| 并发线程 | `ConcGCThreads` | 由 CPU 推导 | 面板 |
| Refinement | `G1ConcRefinementThreads`、`G1RSetUpdatingPauseTimePercent` | 9 / 10（本机） | 面板、文档 |
| SATB buffer | `G1SATBBufferSize`、`G1SATBBufferEnqueueingThresholdPercent` | 1024 / 60 | 文档 |
| Update buffer | `G1UpdateBufferSize` | 256 | 文档 |
| 标记栈 | `MarkStackSize` / `MarkStackSizeMax` | 本机 4MB / 512MB | 文档 |
| 晋升 | `InitialTenuringThreshold` / `MaxTenuringThreshold` / `TargetSurvivorRatio` | 7 / 15 / 50 | lab 3 |
| 字符串去重 | `UseStringDeduplication` / `StringDeduplicationAgeThreshold` | false / 3 | lab true / 1 |
| 类卸载 | `ClassUnloading` / `ClassUnloadingWithConcurrentMark` | true / true | 面板 |
| 显式 GC | `DisableExplicitGC` / `ExplicitGCInvokesConcurrent` | false / false | full-gc |
| 引用处理 | `ParallelRefProcEnabled` | true（本机 JDK21） | 面板 |
| 日志 | `-Xlog:gc*` | — | 启动脚本 |
| 诊断 | `HeapDumpOnOutOfMemoryError`、`ErrorFile` | — | 启动脚本 |

完整解释见 [02-g1-flags.md](02-g1-flags.md)。

## 6. GC 日志与工具

| 知识域 | 关键点 | 项目落点 |
|---|---|---|
| Unified Logging | tag / level / decorator / rotation | 启动脚本、03 文档 |
| 暂停行 | GC id、before/after、capacity、pause | `GcLogParser`、日志 tail |
| 阶段行 | phases、heap、metaspace、marking | `verbose` profile |
| Region 统计 | Eden/Survivor/Old/Humongous regions | 解析器 v2：事件 `regionTransitions` 字段 + 暂停时间线图 tooltip | 任意场景运行后查看 `/api/gc/events` 或图表悬浮 |
| RSet 统计 | Remembered Sets / Scanned Cards / gc,remset | `cross-region` + verbose |
| Safepoint | safepoint log、time to safepoint | 独立日志文件 |
| JMX | GarbageCollectorMXBean、MemoryPoolMXBean | `JvmInfoService`、`HeapService` |
| JFR | G1GarbageCollection、GCHeapSummary、EvacuationFailed、ClassUnload | `/api/jfr/*` 端点 + 控制台 JFR 面板（start/stop/status/下载） | 控制台录制 30s 后用 JMC 打开 logs/jfr-*.jfr |
| jcmd | GC.heap_info、GC.class_histogram、heap dump、JFR | 06 文档 |
| jstat | gcutil / gccause | 06 文档 |
| Actuator | health / metrics / heapdump | API 表 |

## 7. 故障模式与调优

| 症状 | 可能原因 | 项目验证 |
|---|---|---|
| Young GC 很频繁 | 分配率高、Eden 太小、pause target 过紧 | `allocation-storm` |
| Young pause 变长 | 存活对象多、RSet 大、引用处理慢 | `promotion`、`cross-region` |
| 看不到 Mixed | IHOP 未跨过、old region 全存活、没有可回收 CSet | `mixed-gc` |
| Full GC 频繁 | 显式 GC、晋升失败、H 碎片、堆不足 | `full-gc` + 05 文档 |
| To-space exhausted | 晋升速度超过 Survivor/Old 可用空间 | 05 文档 |
| H 对象抖动 | 大数组反复分配、region 门槛过低 | `humongous` |
| RSet refinement 跟不上 | 跨 region 引用多、refinement 线程不足 | `cross-region` |
| Metaspace 涨不回去 | ClassLoader 泄漏、类卸载未发生 | `class-unload` |
| 容器内存被误判 | cgroup 感知、MaxRAMPercentage、JVM 参数 | 05 文档 |
| GC 日志过大 | 级别过高、未 rotation | 启动脚本 filecount/filesize |

## 8. JDK 21 / 生产注意事项

- G1 是默认 GC；确认容器内仍使用 G1。
- 不要把 `System.gc()` 当作常规回收手段。
- experimental 参数需显式解锁，且在不同 JDK 小版本可能变化。
- 先确认分配率、存活集、暂停来源，再调参数；不要只盯着 `MaxGCPauseMillis`。
- 使用 JFR、GC 日志和 heap dump 三者交叉验证。
- 本地教学工具默认绑定 `127.0.0.1`，不要暴露到公网。


## 9. 延伸学习

- 全流程与内部数据结构深潜：[01-g1-internals.md](01-g1-internals.md) 第二部分
- 系统学习路线：[10-g1-learning-path.md](10-g1-learning-path.md)
- 调优实验工作坊：[11-g1-tuning-workshop.md](11-g1-tuning-workshop.md)
- 样例日志逐文件分析：[03-gc-log-analysis.md](03-gc-log-analysis.md) 第二部分
