# G1 参数手册（JDK 21）

> 默认值来自本机 JDK 21.0.11 的 `-XX:+PrintFlagsFinal`。不同 CPU 数、堆大小、容器限制会导致 ergonomic 默认值变化；请以你的环境为准。
>
> JDK 21 中 `G1NewSizePercent`、`G1MaxNewSizePercent`、`G1MixedGCLiveThresholdPercent`、`G1OldCSetRegionThresholdPercent` 是 **experimental**，需要 `-XX:+UnlockExperimentalVMOptions` 才能设置/读取。

## 1. 如何查看真实参数

```bash
java -XX:+PrintFlagsFinal -version | grep -E 'G1|UseG1GC|MaxGCPauseMillis|GCThreads'
java -XX:+PrintCommandLineFlags -version
```

运行中的应用：

```bash
jcmd <pid> VM.flags
jcmd <pid> VM.command_line
jcmd <pid> GC.heap_info
```

本项目的 `/api/config` 通过 `HotSpotDiagnosticMXBean` 读取关键参数。experimental flag 在未解锁时可能显示 `n/a`。

## 2. 核心参数

| 参数 | JDK21 默认 | 作用 | 代价 / 注意 |
|---|---|---|---|
| `-XX:+UseG1GC` | true | 选择 G1 | JDK9+ 默认；显式写出便于确认 |
| `-Xms<N>` / `-Xmx<N>` | 机器相关 | 初始/最大堆 | 建议 Xms=Xmx 降低动态扩缩干扰（非绝对） |
| `-XX:MaxGCPauseMillis=200` | 200 | 期望最大暂停时间（目标，不是硬保证） | 调小会缩小 Young、增加 GC 次数；调大可能减少次数但拉长单次暂停 |
| `-XX:G1HeapRegionSize` | ergonomic | Region 大小，1–32MB 的 2 的幂 | 直接影响 Humongous 阈值、RSet 粒度、碎片 |
| `-XX:InitiatingHeapOccupancyPercent=45` | 45 | 触发并发标记的堆占用阈值 | 偏高可能来不及回收；偏低增加并发标记 CPU |
| `-XX:+G1UseAdaptiveIHOP` | true | 根据历史周期自动调整 IHOP | 调参前先理解自适应行为 |
| `-XX:G1HeapWastePercent=5` | 5 | 允许的堆浪费比例，决定是否继续 Mixed | 过高回收不积极；过低增加 Mixed 次数 |
| `-XX:G1MixedGCCountTarget=8` | 8 | 一轮并发周期中 Mixed GC 目标次数 | 越小单次 Mixed CSet 越大、暂停可能更长 |
| `-XX:G1ReservePercent=10` | 10 | 预留空间，降低晋升失败概率 | 过高浪费堆；过低增加 Evacuation Failure 风险 |
| `-XX:G1MixedGCLiveThresholdPercent=85` | 85（experimental） | 只把存活率低于该值的 Old Region 放入 Mixed CSet | 调高更积极但复制成本高；调低可能回收不到 |
| `-XX:G1OldCSetRegionThresholdPercent=10` | 10（experimental） | 单次 Mixed 最多选多少比例的 Old Region | 与暂停目标、MixedGCCountTarget 联动 |
| `-XX:G1NewSizePercent=5` | 5（experimental） | Young 初始占堆比例 | 过小 GC 频繁；过大老年代空间被压缩 |
| `-XX:G1MaxNewSizePercent=60` | 60（experimental） | Young 最大占堆比例 | 影响暂停上限与晋升行为 |
| `-XX:GCTimeRatio=12` | 12 | 吞吐目标（1/(1+ratio)） | 与 pause target 共同影响 ergonomics |

## 3. 线程与并发

| 参数 | JDK21 本机 | 说明 |
|---|---|---|
| `ParallelGCThreads` | 9（10 CPU） | STW 阶段并行 worker 数 |
| `ConcGCThreads` | 2 | 并发标记线程数 |
| `G1ConcRefinementThreads` | 9 | 处理 dirty card / RSet 的 refinement 线程 |
| `UseDynamicNumberOfGCThreads` | true | 根据负载动态调整 worker |
| `G1RSetUpdatingPauseTimePercent` | 10 | 允许用于 RSet 更新的暂停时间比例 |
| `G1SATBBufferSize` | 1024 | 每个线程的 SATB 缓冲区大小（内部单位） |
| `G1SATBBufferEnqueueingThresholdPercent` | 60 | SATB buffer 入队阈值 |
| `G1UpdateBufferSize` | 256 | dirty card update buffer 大小 |
| `MarkStackSize` / `MarkStackSizeMax` | 4MB / 512MB | 并发标记栈 |

线程数通常不需要手调；除非有证据表明 GC 线程不足（例如 CPU 很富余但暂停仍长）或线程过多产生竞争。容器中优先使用 `-XX:ActiveProcessorCount` 和 `-XX:MaxRAMPercentage`。

## 4. 晋升、引用与字符串

| 参数 | JDK21 默认 | 说明 |
|---|---|---|
| `InitialTenuringThreshold` | 7 | 初始晋升年龄 |
| `MaxTenuringThreshold` | 15 | 最大晋升年龄；G1 仍会自适应当前阈值 |
| `TargetSurvivorRatio` | 50 | Survivor 目标占用百分比 |
| `ParallelRefProcEnabled` | true（本机） | 并行处理 Reference |
| `UseStringDeduplication` | false | 开启字符串去重 |
| `StringDeduplicationAgeThreshold` | 3 | 字符串达到该年龄后参与去重 |
| `ClassUnloading` | true | 允许类卸载 |
| `ClassUnloadingWithConcurrentMark` | true | 并发标记期间卸载类 |

## 5. 显式 GC

| 参数 | 默认 | 行为 |
|---|---|---|
| `DisableExplicitGC` | false | 让 `System.gc()` 变成 no-op |
| `ExplicitGCInvokesConcurrent` | false | 让显式 GC 转为并发周期（G1 仍可能做其他处理） |

对比实验：

```bash
./scripts/run.sh                                         # 默认：System.gc() -> Pause Full
EXTRA_JAVA_OPTS='-XX:+DisableExplicitGC' ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent' ./scripts/run.sh
```

## 6. 周期 GC 与诊断

| 参数 | 默认 | 说明 |
|---|---|---|
| `G1PeriodicGCInterval` | 0 | 每隔多久检查一次周期 GC（0 关闭） |
| `G1PeriodicGCInvokesConcurrent` | true | 周期 GC 使用并发周期 |
| `G1PeriodicGCSystemLoadThreshold` | 0.0 | 系统负载低于阈值才触发 |
| `-Xlog:gc*` | — | 统一 GC 日志 |
| `-XX:+HeapDumpOnOutOfMemoryError` | — | OOM 转储 |
| `-XX:HeapDumpPath` | — | 转储路径 |
| `-XX:+AlwaysPreTouch` | false | 启动时预触堆页，降低运行时缺页，但启动更慢 |

## 7. 日志参数模板

```bash
-Xlog:gc*=info:file=logs/gc-%t.log:utctime,uptime,level,tags:filecount=10,filesize=20m
-Xlog:gc*=debug:file=logs/gc-debug-%t.log:utctime,uptime,level,tags:filecount=5,filesize=20m
-Xlog:safepoint=info:file=logs/safepoint-%t.log:utctime,uptime,level,tags
-Xlog:class+unload=info:file=logs/class-unload-%t.log:utctime,uptime,level,tags
```

- `filecount` 不包含当前文件。
- `filesize=0` 关闭自动 rotation。
- 异步日志：`-Xlog:async`，可降低日志对暂停的影响，但可能丢失崩溃前最后几条。
- 日志级别越高，暂停本身受 I/O 影响的风险越大；性能测试应使用低级别或磁盘足够快。

## 8. 调优顺序

### 第一步：先测量

- 分配率（Allocation Rate）
- 每次 GC 后的存活集（Live Set）
- 暂停分布（平均/P95/P99/最大）
- 晋升率、Humongous 分配
- Full GC 次数与原因
- CPU 使用、容器限制

### 第二步：堆与 Region

- 先确认堆足够容纳存活集，而不是用 Full GC 当容量规划工具。
- 大对象多时观察 `Humongous regions`；考虑拆小对象、复用 buffer，或适度提高 Region 大小。
- 不要为了“看起来整齐”随意固定 Region 大小。

### 第三步：暂停目标

- 从业务可接受的 P99 出发，而不是从某个“最佳参数”出发。
- 过低的 `MaxGCPauseMillis` 会让 Young 变得很小，GC 更频繁。
- 结合 `allocation-storm` 场景观察暂停与吞吐的折中。

### 第四步：IHOP 与 Mixed

- 如果 Mixed 不出现：检查是否跨过 IHOP、Old Region 是否有足够垃圾、日志 watcher 是否工作。
- `G1MixedGCLiveThresholdPercent`、`G1MixedGCCountTarget`、`G1OldCSetRegionThresholdPercent` 是实验性精细控制，先用日志证明问题再调。
- 不要只保留全存活对象来期待 Mixed；Mixed 需要可回收的 Old Region。

### 第五步：减少晋升与碎片

- 降低短命大对象的分配。
- 避免对象“刚好活过一次 GC”后死亡，形成 Old 垃圾。
- 检查 Survivor 是否过小导致过早晋升。

### 第六步：线程与 Buffer

- 只有在阶段日志显示 Remembered Sets / Scanned Cards / refinement 是瓶颈时才考虑。
- 线程数受 CPU 配额限制，盲目增加可能适得其反。

## 9. 本项目 profile 与参数对照

| Profile | 堆 | Pause target | IHOP | Young | Tenuring | 其它 |
|---|---|---|---|---|---|---|
| `default` | 2G | 100 | 30 | 10–35% | 3 | StringDedup / Age 1 |
| `jdk-defaults` | 2G | 200（JDK） | 45（JDK） | 5–60%（JDK） | 7/15（JDK） | 尽量贴近默认 |
| `low-latency` | 4G | 50 | 25 | 10–40% | 3 | MixedCount=4, OldCSet=5 |
| `throughput` | 4G | 500 | 50 | 5–60% | 3 | MixedCount=16, OldCSet=15 |
| `humongous` | 2G | 100 | 35 | 10–35% | 3 | Region=4m |
| `verbose` | 2G | 100 | 30 | 10–35% | 3 | GC debug |

> `jdk-defaults` 只代表“尽量接近本机 JDK 默认 ergonomics”，仍保留了本项目显式堆与日志参数。

## 10. 常见误区

1. **`MaxGCPauseMillis` 是保证值**：不是，G1 只能尽量满足。
2. **堆越大越好**：更大的堆可能拉长 Full GC，也会改变 Region 与晋升行为。
3. **频繁 Full GC 就调大 IHOP**：先找原因，可能是显式 GC、H 碎片、存活集过大或堆不足。
4. **Mixed 场景只保留活对象**：没有可回收 Old Region，Mixed 自然不出现。
5. **忽略实验性参数的版本差异**：不同 JDK 小版本默认值/可用性可能变化，必须用 `PrintFlagsFinal` 验证。
