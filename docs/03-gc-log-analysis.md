# GC 日志案例分析（JDK 21）

本文所有示例都来自本项目在 **JDK 21.0.11** 上生成的日志。完整样例在：

- [samples/gc-scenarios-2g.log](samples/gc-scenarios-2g.log)：2G、1M Region、全部场景、verbose
- [samples/gc-scenarios-4g.log](samples/gc-scenarios-4g.log)：4G、2M Region、Young/Humongous/Full 对照

日志中的数字会随机器、堆、负载变化；**格式和字段含义**才是重点。逐文件的最新统计见 [09-gc-log-file-analysis.md](09-gc-log-file-analysis.md)。

## 1. 一行日志怎么读

典型暂停汇总行：

```text
[2026-10-19T02:28:56.091+0000][4.296s][info ][gc,heap        ] GC(8) Humongous regions: 0->0
[2026-10-19T02:28:56.091+0000][4.296s][info ][gc             ] GC(8) Pause Young (Normal) (G1 Evacuation Pause) 731M->401M(2048M) 30.696ms
```

拆开：

| 部分 | 示例 | 含义 |
|---|---|---|
| utctime | `2026-10-19T02:28:56.091+0000` | UTC 时间 |
| uptime | `4.296s` | JVM 启动后的秒数 |
| level | `info` | 日志级别 |
| tags | `gc,heap` / `gc` | unified logging tag |
| GC id | `GC(8)` | GC 编号；并发阶段可能复用/关联同一周期 |
| 类型 | `Pause Young (Normal)` | 年轻代普通回收 |
| cause | `(G1 Evacuation Pause)` | 触发原因 |
| 堆 | `731M->401M(2048M)` | GC 前已用 → GC 后已用（当前堆容量） |
| 暂停 | `30.696ms` | 本次 STW 时间 |

注意：

- 括号里的 `2048M` 是**当前堆容量**，不一定等于 `-Xmx`；当 Xms≠Xmx 时两者可能不同。本项目 Xms=Xmx，所以一致。
- 这条汇总行不是 start 行。`gc,start` 行只有 `Pause ...`，没有 `before->after` 和毫秒数；解析器只取汇总行，避免重复。
- 单行数字单位可能是 K/M/G，解析器都支持。
- 并发阶段行没有 `Pause`，它们是应用线程与 GC 线程并行执行的，不计入 STW pause。

## 2. 启动 ergonomics：2G 与 4G

2G：

```text
[2026-10-19T02:28:51.802+0000][0.007s][info ][gc          ] Using G1
[2026-10-19T02:28:51.804+0000][0.009s][info ][gc,init    ] Heap Region Size: 1M
[2026-10-19T02:28:51.804+0000][0.009s][info ][gc,init    ] Heap Max Capacity: 2G
[2026-10-19T02:28:51.804+0000][0.009s][info ][gc,init    ] Concurrent Refinement Workers: 9
```

4G：

```text
[2026-10-19T02:29:17.535+0000][0.007s][info ][gc       ] Using G1
[2026-10-19T02:29:17.538+0000][0.010s][info ][gc,init   ] Heap Region Size: 2M
[2026-10-19T02:29:17.538+0000][0.010s][info ][gc,init   ] Heap Max Capacity: 4G
```

结论：

- G1 自动把 Region 维持在约 2048 个：2G/2048=1M，4G/2048=2M。
- 4G 的 Humongous 阈值从约 512KB 变为约 1MB。
- 这不是“Region 越大越先进”，而是堆规模/管理粒度的折中。
- 可通过 `/api/config` 或 `jcmd <pid> VM.flags` 验证。

## 3. 普通 Young GC

```text
[2026-10-19T02:28:56.060+0000][4.265s][info ][gc,start    ] GC(6) Pause Young (Normal) (G1 Evacuation Pause)
[2026-10-19T02:28:56.091+0000][4.296s][info ][gc          ] GC(6) Pause Young (Normal) (G1 Evacuation Pause) 211M->18M(2048M) 5.580ms
```

解读：

- 在 4.296s 时，Eden 分配压力触发普通 Young GC。
- 堆从 211MB 降到 18MB，说明绝大多数对象是短命的。
- 暂停 5.58ms，远低于 `MaxGCPauseMillis=100`。
- 这是 `young-gc` 场景最希望看到的结果。

对应的 Region 统计（来自同一次 GC 附近）：

```text
GC(6) Eden regions: 195->0(221)
GC(6) Survivor regions: 9->4(26)
GC(6) Old regions: 8->16
GC(6) Humongous regions: 0->0
```

含义：

- EDEN `195->0`：Eden Region 被清空。
- Survivor `9->4`、Old `8->16`：存活对象部分留在 Survivor，部分晋升到 Old。
- `(221)` 是本次统计中 Eden 可用的 Region 数上限/相关容量，具体取决于 G1 内部记录方式；不要把括号简单理解为“之前有 221 个”。
- Humongous 为 0，说明这次回收没有 H 对象。

## 4. 暂停阶段：时间花在哪里

`gc,phases` 的 debug 行（以下数字取自一次 30ms 的晋升回收 `GC(8)`，不是上面的 GC(6)）：

```text
GC(8)     Ext Root Scanning (ms):        Min: 0.0, Avg: 0.1, Max: 0.4, Sum: 0.7
GC(8)     Scan Heap Roots (ms):          Min: 0.0, Avg: 0.2, Max: 0.3, Sum: 1.7
GC(8)       Scanned Cards:               Min: 0, Avg: 266.9, Max: 680, Sum: 2402
GC(8)       Found Roots:                 Min: 0, Avg: 2333.4, Max: 9269, Sum: 21001
GC(8)     Object Copy (ms):              Min: 26.0, Avg: 26.7, Max: 27.1, Sum: 240.6
GC(8)     Termination (ms):              Min: 0.0, Avg: 0.0, Max: 0.1, Sum: 0.4
GC(8)     Remembered Sets (ms):          Min: 0.0, Avg: 0.0, Max: 0.1, Sum: 0.4
```

- `Sum` 是所有 worker 的累计时间，可能大于实际墙钟暂停。
- `Min/Avg/Max` 是各 worker 的分布；某个 worker 特别慢会拉长暂停。
- `Scanned Cards` / `Found Roots` 能直接反映 RSet 扫描量。
- `Object Copy` 是存活对象复制/晋升的主要成本。
- Reference 处理、类卸载、字符串去重等也有独立阶段。

本项目的 `allocation-storm` 可以增大分配率，`promotion` 增大存活集，`cross-region` 增大 RSet 扫描，分别观察不同阶段。

## 5. 并发标记周期

### 5.1 Concurrent Start

```text
[2026-10-19T02:28:57.972+0000][6.177s][info ][gc,start] GC(11) Pause Young (Concurrent Start) (G1 Evacuation Pause)
[2026-10-19T02:28:57.989+0000][6.194s][info ][gc      ] GC(11) Pause Young (Concurrent Start) (G1 Evacuation Pause) 881M->905M(2048M) 17.392ms
```

堆占用接近/跨过 IHOP（本项目 lab 为 30%），G1 在年轻代暂停中启动初始标记。注意 after 比 before 还大，这是正常现象：这是标记周期的起点，不代表对象被回收。

### 5.2 并发标记

```text
[2026-10-19T02:28:57.990+0000][6.195s][info ][gc        ] GC(12) Concurrent Mark Cycle
[2026-10-19T02:28:57.990+0000][6.195s][info ][gc,marking ] GC(12) Concurrent Mark From Roots
[2026-10-19T02:28:58.036+0000][6.241s][info ][gc,marking ] GC(12) Concurrent Mark From Roots 46.140ms
```

- 并发标记与业务线程同时运行，不产生 STW pause。
- 46.140ms 是并发阶段持续时间，不是暂停时间。
- GC id 与 Concurrent Start 的 GC(11) 不同；并发阶段通常有自己的内部编号。读日志时不要强行用 id 对齐所有行。

### 5.3 Remark + Cleanup

```text
[2026-10-19T02:28:58.036+0000][6.241s][info ][gc,start] GC(12) Pause Remark
[2026-10-19T02:28:58.039+0000][6.244s][info ][gc      ] GC(12) Pause Remark 1285M->997M(2048M) 2.679ms
[2026-10-19T02:28:58.048+0000][6.252s][info ][gc,start] GC(12) Pause Cleanup
[2026-10-19T02:28:58.048+0000][6.253s][info ][gc      ] GC(12) Pause Cleanup 1217M->1217M(2048M) 0.484ms
[2026-10-19T02:28:58.048+0000][6.253s][info ][gc,marking] GC(12) Concurrent Cleanup for Next Mark
```

- Remark 处理 SATB 残留，通常很短。
- Cleanup 回收完全空的 Region、统计存活、为 Mixed 做准备。
- 这两个也是 STW，且都是 pause。

> 重要：本项目的 `mixed-gc` 场景除了等待 Remark/Cleanup，还会再制造一次年轻代压力，因为 **Mixed GC 往往发生在 Cleanup 之后的下一次 Young Pause**。

### 5.4 Mixed

```text
[2026-10-19T02:29:05.204+0000][13.408s][info ][gc,start] GC(26) Pause Young (Mixed) (G1 Evacuation Pause)
[2026-10-19T02:29:05.206+0000][13.411s][info ][gc      ] GC(26) Pause Young (Mixed) (G1 Evacuation Pause) 742M->27M(2048M) 2.954ms
```

前几次 Mixed：

```text
GC(19) Pause Young (Mixed) (G1 Evacuation Pause) 1514M->928M(2048M) 2.850ms
GC(20) Pause Young (Mixed) (G1 Evacuation Pause) 1680M->988M(2048M) 4.147ms
```

- `Mixed` 表示 CSet 里包含 Old Region，不只是年轻代。
- 不是所有 Old Region 都会被回收，只选择低存活率、收益足够的部分。
- 日志里出现 `Pause Young (Mixed)` 是 Mixed 真正发生的证据；只有 `Concurrent Start` 不代表已经完成 Mixed。
- 如果日志显示 `do not continue mixed GCs (candidate old regions not available)`，说明没有更多合适的 Old CSet 了。

### 5.5 CSet 选择

verbose 日志：

```text
GC(26) Start adding marking candidates to collection set. Min 1 regions, max 205 regions ...
GC(26) Marking candidates exhausted.
GC(26) Finish adding marking candidates to collection set. Initial: 2, optional: 0 ...
```

- `Initial` 是必须回收的 Region，`optional` 是按收益追加的 Region。
- `marking candidates exhausted` 表示可选候选已经用完。
- 这能解释“为什么 Mixed 只回收了几个 Region”：不是每次都能凑满 CSet。

## 6. Humongous 对象

4G 示例：

```text
GC(7) Humongous regions: 64->32
GC(8) Humongous regions: 32->32
GC(9) Humongous regions: 32->0
```

2G 示例：

```text
GC(19) Humongous regions: 96->48
GC(20) Humongous regions: 49->1
```

解读：

- `64->32`：回收前 64 个 H Region，回收后剩 32 个。
- `32->0`：引用释放后，H Region 被回收。
- H 对象分配会直接占用 Old/H 区域，容易造成碎片。
- 2G/1M Region 与 4G/2M Region 的 H 数量不同，因为同一对象占用的 Region 数不同。
- 对象的数组头也计入大小，所以“刚好半 Region”的 byte[] 也可能是 H 对象。项目普通场景使用 256KB chunk 来规避这个问题。

## 7. 显式 Full GC

2G 样例：

```text
[2026-10-19T02:29:06.964+0000][15.169s][info ][gc,start] GC(31) Pause Full (System.gc())
[2026-10-19T02:29:06.981+0000][15.186s][info ][gc      ] GC(31) Pause Full (System.gc()) 285M->18M(2048M) 17.050ms
```

4G 样例：

```text
[2026-10-19T02:29:23.259+0000][5.730s][info ][gc,start] GC(8) Pause Full (System.gc())
[2026-10-19T02:29:23.282+0000][5.754s][info ][gc      ] GC(8) Pause Full (System.gc()) 364M->80M(4096M) 23.219ms
```

verbose 还能看到：

```text
GC(31) Phase 4: Compact heap
GC(31) Phase 4: Compact heap 0.798ms
```

- Full GC 会 STW，并压缩/整理堆。
- 显式 `System.gc()` 是最容易复现的 Full GC 入口，但不代表生产环境的常见原因。
- 如果启动参数有 `DisableExplicitGC`，这个场景会变成 no-op；如果有 `ExplicitGCInvokesConcurrent`，可能变成并发周期。
- 生产环境真正需要关注的是 `Allocation Failure`、`Evacuation Failure`、H 碎片等原因导致的兜底 Full GC。

## 8. Class Unload

独立日志：

```text
[2026-10-19T02:29:09.375+0000][17.580s][info][class,unload] unloading class DynamicClasses$C499 0x0000007801710a50
[2026-10-19T02:29:09.375+0000][17.580s][info][class,unload] unloading class DynamicClasses$C498 ...
```

同时观察：

```text
Metaspace 41.5 MB -> 51.3 MB -> 50.5 MB
```

解释：

- 动态类加载让 Metaspace 上升约 10MB。
- 丢弃 ClassLoader 并 Full GC 后，`class,unload` 行出现。
- Metaspace 只回落约 0.8MB，是因为类元数据并非全部可立即归还给操作系统，且 Metaspace 的 committed 不会立刻缩小；**看 class unload 行比只看 Metaspace 数字更可靠**。
- 如果完全没有 unloading 行，优先排查 ClassLoader 是否仍然可达。

## 9. String Deduplication

独立日志（tag 是 `stringdedup`，不在 `gc*` 里）：

```text
[2026-10-19T02:28:52.585+0000][0.790s][info][stringdedup] Concurrent String Deduplication 16021/1109.5K (new), 15344/924.4K (deduped), avg 83.3%, 14.627ms of 14.956ms
```

解读：

- `new`：新加入去重表的字符串数量和大小。
- `deduped`：实际去重数量/节省的字节。
- `avg 83.3%`：字符串表的平均去重率。
- `17.316ms of 17.698ms`：去重线程实际处理时间 / 总持续时间。
- 去重发生在并发阶段，不是 STW；但对 CPU 有成本。
- 本项目默认使用 `-XX:+UseStringDeduplication -XX:StringDeduplicationAgeThreshold=1` 让实验更快出现。

## 10. RSet / Refinement 日志

JDK 21 的 verbose GC 日志里更常见的是这些名字：

```text
GC(8)     Remembered Sets (ms):          Min: 0.0, Avg: 0.0, Max: 0.1, Sum: 0.4
GC(8)       Scanned Cards:               Min: 0, Avg: 266.9, Max: 680, Sum: 2402
GC(8)     Scan Heap Roots (ms):          Min: 0.0, Avg: 0.2, Max: 0.3, Sum: 1.7
GC(8)       Found Roots:                 Min: 0, Avg: 2333.4, Max: 9269, Sum: 21001
GC(8)     Object Copy (ms):              Min: 26.0, Avg: 26.7, Max: 27.1, Sum: 240.6
```

G1 在本次回收开始前，处理“脏卡”（dirty card）的统计日志，反应了RSet更新工作量：

```text
GC(26) Mutator refinement: 0.00ms, refined: 0, precleaned: 0, dirtied: 1
GC(26) Total refinement: 0.00ms, refined: 0, precleaned: 0, dirtied: 1
GC(26) Visited cards 2016 Total dirty 38912 (5.18%) Total old 2994176 (0.07%)
```

- `dirtied`：应用线程写屏障弄脏的 card 数。
- `refined`：refinement 实际处理的 card 数。
- `Scanned Cards`：本次暂停扫描的 card 数。
- `Remembered Sets` / `Found Roots`：RSet 相关准备与找到的根数量。
- 跨 Region 引用越多，这些数字通常越大。

`cross-region` 场景专门放大这部分成本。

## 11. To-space exhausted / Evacuation Failure

GC 触发了一次疏散失败（Evacuation Failure），并直接导致了 To-space 耗尽。
```text
Pause Young (Prepare Mixed) (G1 Humongous Allocation) (Evacuation Failure) 2047M->57M(2048M) 5.528ms
To-space exhausted
```

含义：

- Evacuation 时目标空间不足，存活对象复制失败。
- G1 可能放弃当前回收、尝试 Full GC，或者在其他阶段完成整理。
- 这通常是堆/存活集/预留空间问题，不是简单的“暂停参数”问题。
- 本项目的 `05-tuning-and-troubleshooting.md` 有对应处理路径。

## 12. 2G vs 4G 对照

| 项目 | 2G | 4G |
|---|---|---|
| Region Size | 1M | 2M |
| Region 数 | 2048 | 2048 |
| H 阈值 | 约 512KB | 约 1MB |
| 普通 Young 示例 | `211M->18M(2048M)` | `415M->19M(4096M)` |
| Mixed 示例 | `742M->27M(2048M)` | 本次 4G 冒烟未专门跑 Mixed |
| Full 示例 | `285M->18M(2048M)` | `364M->80M(4096M)` |
| 观察 | 更小的 Region、更细的 H 粒度 | 更大的 Region、不同的碎片形态 |

不要直接比较绝对暂停数字：堆、Region、分配量、缓存状态都不同。要比较的是**相同 workload 下的趋势**。

## 13. 用脚本快速分析

```bash
./scripts/analyze-gc-log.sh logs/gc-xxxx.log
```

输出包含：

- 各类型暂停次数、平均/最大 pause；
- 最长暂停原始行；
- Full GC 清单；
- Concurrent/Humongous/Failure 标记。

常用命令：

```bash
# 所有暂停
grep -E 'Pause (Young|Full|Remark|Cleanup)' logs/gc-*.log

# 只看 Mixed
grep 'Pause Young (Mixed)' logs/gc-*.log

# 并发周期
grep -E 'Concurrent (Mark|Start|Cleanup|Remark)' logs/gc-*.log

# RSet
grep -E 'Remembered Sets|Scanned Cards|gc,remset|gc,refine,stats' logs/gc-*.log

# 异常
grep -E 'To-space exhausted|Evacuation Failure|Allocation Failure' logs/gc-*.log

# 类卸载
grep 'unloading class' logs/class-unload-*.log

# 字符串去重
grep 'Concurrent String Deduplication' logs/stringdedup-*.log
```

## 14. 分析方法总结

1. 先找 pause 汇总行，按类型统计。
2. 再看 pause 前后的堆使用和 Region 统计。
3. 对长暂停，打开 phases 看具体阶段。
4. 对 Full GC，找 cause 和前面的失败证据。
5. 对 Mixed，确认“并发周期 + CSet”都发生，而不是只看到 Concurrent Start。
6. 对 H/RSet/字符串/类卸载，分别看对应 tag，不要只看总暂停。
7. 最后结合分配率、live set、CPU 和业务指标决定是否调参。

---

## v2 补充：解析器现在直接消费的行族（2026-10-24 重构）

控制台不再只解析 pause 汇总行。以下行族都会进入 `/api/gc/events` 的富字段，悬停时间线图/事件表即可查看：

| 行族 | 示例（真实日志） | 事件字段 | G1 概念 |
|---|---|---|---|
| Region 转移（info） | `GC(6) Eden regions: 194->0(213)`、`Humongous regions: 4->0` | `regionTransitions` | Region 布局与年轻代回收 |
| 并发周期（info） | `GC(1) Concurrent Mark Cycle 10.525ms`、`Scan Root Roots`→`Scan Root Regions`、`Clear Claimed Marks`、`Cleanup for Next Mark` | 独立 `type=Concurrent` 事件 | 并发标记周期 |
| 暂停阶段分解（info） | `Pre/Evacuate/Post Evacuate Collection Set: x.xms`、`Merge Heap Roots`、`Other`、Full GC `Phase 1..4` | `phases` | 暂停内部结构 |
| CPU（info） | `GC(6) User=0.03s Sys=0.01s Real=0.00s` | `cpu` | GC 并行度（User/Real 比） |
| Metaspace（info） | `Metaspace: 41636K(42432K)->41636K(42432K)` | `metaspace` | 类元数据回收 |
| IHOP（verbose） | `Adaptive IHOP information ... threshold: 1288490188B (35.29)` | `details.ihop*` | 自适应 IHOP |
| ergo 决策（verbose） | `do not continue mixed GCs (candidate old regions not available)` | `details.ergo` | "为什么 Mixed 停了"的一手答案 |
| tenuring（verbose） | `Desired survivor size ... new threshold 3` | `details.tenuringThreshold` | 自适应晋升 |
| 引用处理（verbose） | `PhantomReference Discovered: 3320, Dropped: 251, Processed: 3069` | `details.*Ref` | 引用处理器 |
| 大对象回收（verbose） | `Humongous Reclaimed ... Sum: 16` | `details.humongousReclaimedRegions` | Eager reclaim |
| PLAB / 卡表（verbose） | `Young PLAB allocation: allocated: ...`、`Dirty Cards ... Sum: 541` | `details.*Plab*` / `details.*Cards` | TLAB/PLAB、卡表 refinement |

**真实 GC 块行序**（重要，与直觉相反）：JDK 21 中 pause 汇总行在块的**末尾**——先是 `gc,start`、worker、`gc,phases`、`gc,heap` Region、Metaspace 行，然后才是 `GC(n) Pause ... 211M->19M(2048M) 5.415ms` 汇总行，`gc,cpu` 行跟在最后。聚合器（`GcCycleAggregator`）用 orphan 缓冲支持"细节先到、汇总后到"的回填。
