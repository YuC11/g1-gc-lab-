# G1 内部机制：从 Region 到 Mixed GC

## 1. 为什么需要 G1

G1（Garbage-First）从 JDK 7 引入，JDK 9 起成为默认收集器。它试图同时满足：

- **可预测的停顿**：通过 `MaxGCPauseMillis` 选择回收价值高的 Region，而不是每次扫全堆。
- **分代回收**：保留 Young / Old 分代假设，但不再要求连续的新生代和老年代空间。
- **并行 + 并发**：多线程 Evacuation，同时并发标记。
- **可增量整理**：通过 Region 复制减少碎片，而不是每次 Full GC 全堆压缩。

它适合大多数服务端负载，尤其是堆较大（数 GB 到数十 GB）、希望控制单次暂停、又不想使用 ZGC 这类低延迟收集器的场景。

## 2. Region 化堆

G1 把堆切成大小相等的 Region。默认期望约 **2048 个 Region**，Region 大小取满足条件的 2 的幂，范围 1–32MB。

本机实测：

```text
-Xms2g -Xmx2g  -> Heap Region Size: 1M, 2048 regions
-Xms4g -Xmx4g  -> Heap Region Size: 2M, 2048 regions
```

因此“堆越大 Region 越大”不是固定规则，而是 ergonomics 的结果。也可以显式设置：

```text
-XX:G1HeapRegionSize=2m
```

每个 Region 在任意时刻可以是：

```text
┌────────┬────────┬────────┬────────┬────────┬────────┐
│ Free   │ Eden   │ Survivor│ Old    │ Humongous│ Free  │
└────────┴────────┴────────┴────────┴────────┴────────┘
          \______ young ______/   \___ old ___/
```

一个 Region 同时只属于一个空间。Young 集合由 Eden + Survivor 组成，Old 集合由晋升对象和 Humongous Region 组成。

### 2.1 Region 大小的取舍

| 现象 | 较小 Region | 较大 Region |
|---|---|---|
| Humongous 门槛 | 低，大对象更容易 H | 高，可容纳更大对象 |
| RSet / 卡表粒度 | 细，跨 Region 引用更多 | 粗，单条记录覆盖更大范围 |
| 管理开销 | 元数据更多 | 元数据更少 |
| 碎片 | 更容易利用小空洞 | 大对象更容易造成连续 H 区占用 |
| 调优 | 适合小堆或大量中等对象 | 适合大堆、少量大对象 |

**没有免费的午餐**。教学项目默认让 G1 自己选；用 `REGION_SIZE=1m|2m|4m` 做对照实验最直观。

## 3. 对象分配与生命周期

### 3.1 TLAB / PLAB

- **TLAB**（Thread Local Allocation Buffer）：线程在 Eden 内私有的分配缓冲区，避免每次分配都做全局同步。
- **PLAB**（Promotion Local Allocation Buffer）：Evacuation 时每个 GC 线程在目标 Survivor/Old Region 中使用的复制缓冲区。

### 3.2 晋升

对象一般路径：

```text
Eden -> Survivor -> ... -> Old
```

G1 使用年龄计数和 `MaxTenuringThreshold`（本机默认 15），同时根据 Survivor 占用自适应调整阈值。项目默认把 `MaxTenuringThreshold` 设为 3，让晋升演示更快出现，但这不是 JDK 默认值。

Survivor 目标占用由 `TargetSurvivorRatio`（默认 50）等参数影响。如果 Survivor 放不下，对象可能提前晋升，进而推高 Old 占用。

### 3.3 Evacuation

G1 的年轻代回收不是“原地清理”，而是：

1. 选择 CSet。
2. 并行扫描 CSet 中对象的引用。
3. 把存活对象复制到新的 Survivor/Old Region。
4. 在原对象头写入 forwarding pointer。
5. 更新引用。
6. 回收旧 Region。

如果目标空间不足，会发生 **Evacuation Failure**，日志出现 `To-space exhausted`，G1 可能退化为 Full GC 并做全堆压缩。

## 4. CSet 与回收价值

**Collection Set（CSet）** 是本次暂停要回收的 Region 集合。

- Young GC 的 CSet：Eden + Survivor。
- Mixed GC 的 CSet：Young 集合 + 一部分低存活率 Old Region。

G1 会评估 Old Region 的存活率、回收收益和 `G1HeapWastePercent` 等约束。Mixed 回收不是一次扫完所有 Old Region，而是分摊到多次 Mixed GC（`G1MixedGCCountTarget`）。

## 5. RSet、卡表与写屏障

### 5.1 问题

如果对象 A 在 Region 1，引用了 Region 2 的 B，那么回收 Region 2 时必须知道 A 是否还存活。

G1 使用 **Remembered Set（RSet）** 记录“谁指向我这个 Region”，避免扫描整个堆。

### 5.2 卡表与 dirty card queue

- 堆被进一步划分为 card（本机日志显示 CardTable entry size: 512）。
- 对象引用字段被写入时，post-write barrier 把对应 card 标记为 dirty。
- dirty card 进入队列，由 refinement 线程处理。
- refinement 更新目标 Region 的 RSet。

### 5.3 成本在哪里

GC 日志的阶段行会出现：

```text
Ext Root Scanning
Scan Heap Roots
  Scanned Cards
  Scanned Blocks
  Found Roots
Code Root Scan
Object Copy
Termination
Remembered Sets
```

- **Scan Heap Roots / Scanned Cards**：扫描 RSet 指向的跨 Region 引用。
- **Remembered Sets**：RSet 相关的准备/更新工作。
- **gc,remset / gc,refine,stats**：RSet 访问、dirty card、refinement 统计。
- 跨 Region 引用越多、老对象越分散，这些部分成本越高。

项目 `cross-region` 场景构造 25 万个随机引用节点，配合 `PROFILE=verbose` 可以观察这些阶段。

## 6. SATB 与并发标记

G1 的并发标记使用 **SATB（Snapshot-At-The-Beginning）**：以标记开始时的对象图快照为准。

- 并发标记开始时，逻辑上认为所有当前存活对象都要保留。
- 应用线程继续运行时，pre-write barrier 会把“即将被覆盖的旧引用”记录下来，避免漏标。
- TAMS（Top-At-Mark-Start）区分标记开始前后分配的对象；标记开始后新分配的对象默认视为存活。
- Remark 阶段处理 SATB 缓冲区和引用队列，完成最终标记。
- Cleanup 统计 Region 存活情况、回收完全空闲的 Region，并重置标记状态。

SATB 的好处是并发标记与应用线程可以并行，不需要像 CMS 那样处理太多增量更新；代价是可能保留一些“本可以回收”的对象（浮动垃圾）。

## 7. G1 的回收周期

### 7.1 Young-only

应用分配耗尽 Eden：

```text
Pause Young (Normal) (G1 Evacuation Pause)
```

只回收年轻代，通常是最常见的暂停。

### 7.2 并发标记周期

当堆占用跨过 IHOP（G1 启动并发标记周期的堆占用率阈值，默认 45%，自适应；本项目 lab 为 30%）：

```text
Pause Young (Concurrent Start)
Concurrent Mark Cycle
Pause Remark
Pause Cleanup
Pause Young (Prepare Mixed)
Pause Young (Mixed)
```

`Concurrent Start` 是一次带初始标记的 Young Pause。之后标记线程与应用线程并发工作。Remark/Cleanup 也是 STW，但通常比 Full GC 短。

### 7.3 Mixed GC

标记完成后，G1 知道哪些 Old Region 的存活率低。后续 Young GC 可以选择性地加入这些 Old Region：

```text
Pause Young (Mixed) (G1 Evacuation Pause)
```

Mixed 回收不是“回收整个老年代”，而是按收益分批进行。一次 Mixed 的 CSet 大小受 `G1OldCSetRegionThresholdPercent` 等约束。

### 7.4 Full GC

触发条件包括：

- 显式 `System.gc()`（未配置并发显式 GC）。
- Evacuation Failure / to-space exhausted。
- Humongous 分配无法满足。
- 堆严重不足，Young/Mixed 连续失败。
- 元空间等非堆问题也可能触发 Full GC。

日志：

```text
Pause Full (System.gc())
Phase 4: Compact heap
```

Full GC 会 STW 并压缩/整理堆，是最后兜底，不应作为常规回收路径。

## 8. 年轻代大小与暂停目标

G1 的 Young 大小是动态的，受以下因素影响：

- `MaxGCPauseMillis`（本机默认 200ms）
- `G1NewSizePercent` / `G1MaxNewSizePercent`（JDK 21 experimental：5 / 60）
- `GCTimeRatio`
- 实际暂停历史、对象存活率、Region 数量

如果 pause target 设得太小，G1 会缩小 Young，导致更频繁的 Young GC；设得太大，单次暂停可能变长。教学项目用 100ms，低延迟 profile 用 50ms，吞吐 profile 用 500ms，便于对比。

## 9. Humongous 对象

判定：对象大小 **大于 Region 大小的一半** 即被视为 Humongous。

- 占用一个或多个连续 Region。
- 直接分配在 Old 区域（逻辑上）。
- 容易产生碎片，尤其是反复分配/释放不同大小的大数组。
- 完全不可达的 H Region 可以在 Cleanup 或后续回收中释放。

2G/1M Region 的阈值约 512KB；4G/2M Region 约 1MB。**数组对象头会让“刚好半 Region”的字节数组也越界成为 H 对象**，这是实验中很容易踩到的细节。

## 10. 字符串去重、类卸载、引用

### 10.1 String Deduplication

`-XX:+UseStringDeduplication` 开启对 `String` 底层字节/字符数组的去重：

- 只处理已经存活到一定年龄（`StringDeduplicationAgeThreshold`）的字符串。
- 在 GC 周期中并发执行。
- 节省堆，但消耗 CPU；不是所有字符串都值得去重。

项目 lab 将年龄阈值设为 1，便于短时间观察到效果。

### 10.2 类卸载

类卸载需要：

1. 对应的 ClassLoader 不可达。
2. 类实例、静态引用、JNI 引用等都不再存活。
3. 发生支持类卸载的 GC（G1 通常在并发标记/Remark/Cleanup 或 Full GC 中处理）。

开启 `ClassUnloadingWithConcurrentMark`（默认 true）后，不必等 Full GC。项目的 class-unload 场景故意使用 `System.gc()` 让回收更确定。

### 10.3 引用与 finalization

软引用、弱引用、虚引用、finalizer 由 Reference Processing 处理。日志中的 `gc,ref`、`ParallelRefProcEnabled` 与引用处理线程相关。长引用链和大量 finalizer 会拉长暂停。

## 11. G1 与其它收集器

| 收集器 | 特点 | 适合 |
|---|---|---|
| Serial | 单线程，简单 | 小堆、客户端 |
| Parallel | 多线程吞吐优先 | 批处理、吞吐优先 |
| G1 | Region 化、可预测停顿、并发标记、增量整理 | 大多数服务端 |
| ZGC | 超低暂停、并发整理 | 大堆、极低延迟 |
| Shenandoah | 低暂停、并发整理 | 低延迟、特定发行版 |

选择 G1 的关键不是“默认”，而是确认单次停顿、分配率、存活集和 Full GC 风险是否可接受。

## 12. 从源码到日志的对应关系

| 机制 | 日志证据 |
|---|---|
| 年轻代回收 | `Pause Young (Normal)` |
| IHOP 触发 | `Pause Young (Concurrent Start)` |
| 并发标记 | `Concurrent Mark Cycle`、`Concurrent Mark From Roots` |
| SATB 收尾 | `Pause Remark`、`gc,marking` |
| Region 清理 | `Pause Cleanup`、`Concurrent Cleanup for Next Mark` |
| 混合回收 | `Pause Young (Mixed)` |
| 晋升失败 | `Evacuation Failure`、`To-space exhausted` |
| 大对象 | `Humongous regions` |
| RSet | `Remembered Sets`、`Scanned Cards`、`gc,remset` |
| 类卸载 | `class,unload` 的 `unloading class` |
| 字符串去重 | `stringdedup` 独立日志 |

更细的阶段、数据结构和 OpenJDK 21 源码对照见 [12-g1-end-to-end-internals.md](12-g1-end-to-end-internals.md)。

下一篇：[02-g1-flags.md](02-g1-flags.md) 逐项解释参数和调优顺序。
