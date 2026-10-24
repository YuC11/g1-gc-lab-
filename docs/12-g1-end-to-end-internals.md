# G1 全流程与内部数据结构

> 本文按 **JDK 21 HotSpot** 的实现，讲清楚 G1 从一次分配、一次 Young GC，到完整并发标记、Remark、Cleanup、Mixed 和 Full GC 的全流程，以及每个阶段用到的数据结构。
>
> 重点回答一个常被略过的问题：**并发标记周期的“起始标记”到底怎么扫描？Bitmap、TAMS、mark stack、SATB、root region 分别起什么作用？**
>
> 说明：HotSpot 内部类名/字段名可能随 JDK 版本调整；本文以 JDK 21 的命名和日志为准。概念稳定，类名仅用于帮助对照源码。

## 1. 一张总图

```text
应用线程分配
   │
   ├─ TLAB 内快速分配
   ├─ TLAB 用尽 → G1Allocator/G1AllocRegion 申请新 Eden Region
   ├─ 大对象 → Humongous Region（连续 Region）
   └─ 分配失败 / IHOP 触发 / 元数据触发 / 显式 GC
          │
          ▼
   ┌─────────────────────────────┐
   │ 1. Young-only GC             │
   │   CSet = Eden + Survivor     │
   │   root scan + RSet + copy    │
   └──────────────┬──────────────┘
                  │ 若需要并发周期
                  ▼
   ┌─────────────────────────────┐
   │ 2. Concurrent Start (STW)   │
   │   reset mark state          │
   │   set TAMS (old/H = top)    │
   │   scan strong roots         │
   │   mark bitmap + mark stack  │
   │   evacuate young            │
   │   enable SATB               │
   │   survivor regions become   │
   │   root regions              │
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │ 3. Concurrent Root Region   │
   │    Scan (并发)              │
   │   scan survivor [TAMS, top] │
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │ 4. Concurrent Mark (并发)   │
   │   task queue + mark stack   │
   │   bitmap CAS mark           │
   │   drain SATB buffers        │
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │ 5. Pause Remark (STW)       │
   │   drain SATB, refs, classes │
   │   reclaim empty regions     │
   │   RSet tracking / TARS      │
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │ 6. Pause Cleanup (STW)      │
   │   finalize liveness         │
   │   free/reuse regions        │
   │   choose mixed candidates   │
   │   concurrent clear bitmap   │
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │ 7. Mixed GC (可多次)        │
   │   young + old CSet regions  │
   └──────────────┬──────────────┘
                  │ 失败/碎片/显式 GC
                  ▼
   ┌─────────────────────────────┐
   │ 8. Full GC (兜底)           │
   │   mark-compact              │
   └─────────────────────────────┘
```

## 2. 堆的底层表示

### 2.1 Region 与堆映射

G1 逻辑上把堆切成大小相等的 Region，但物理地址仍然是一整段保留地址空间。

关键对象：

| 结构 | 作用 |
|---|---|
| `HeapRegion` | 单个 Region 的元数据：bottom/top/end、类型、TAMS、BOT、垃圾字节等 |
| `HeapRegionManager` | Region 数组、索引、空闲列表、分配/释放 Region |
| `G1RegionToSpaceMapper` | 把堆地址范围映射到 Region 元数据、card table、mark bitmap 等辅助空间 |
| `G1HeapRegionSet` | Region 集合（如 Old set、Survivor set） |
| `FreeRegionList` | 空闲 Region 链表 |

一个 Region 的简化布局：

```text
HeapRegion
├── _bottom / _top / _end   // 当前使用范围
├── _type                   // Free/Eden/Survivor/Old/Humongous
├── _top_at_mark_start      // TAMS
├── _parsable_bottom        // cleanup/scrub 后可解析边界
├── _garbage_bytes          // 标记后估算的垃圾
├── _bot_part               // G1BlockOffsetTablePart，辅助定位对象起始
└── remset / card set       // 指向本 Region 的 RSet
```

### 2.2 Region 大小

G1 以约 2048 个 Region 为目标，选择满足条件的 2 的幂大小，范围 1–32MB：

```text
2G / 2048 ≈ 1M
4G / 2048 ≈ 2M
8G / 2048 ≈ 4M
```

可用 `-XX:G1HeapRegionSize` 显式覆盖。Region 越大，Humongous 门槛越高，但 RSet 粒度和对象复制成本也会改变。

### 2.3 Humongous

判定：对象大小 **严格大于** `regionSize / 2`。

- 占一个或多个连续 Region。
- 逻辑上直接进入 Old/Humongous。
- 起始 Region 记录对象大小；后续 Region 标记为 continues Humongous。
- 释放后可以在 Cleanup/Full GC 中回收；G1 也可能做 eager reclaim。

对象头也计入大小，所以 `region/2` 的 byte[] 可能因对象头超过阈值。

## 3. 分配路径

### 3.1 TLAB（Thread Local Allocation Buffer，线程本地分配缓存）

每个应用线程优先在自己的 **TLAB**（Thread Local Allocation Buffer）里分配。TLAB 是 Eden Region 的一段私有区域，避免每次都做全局分配同步。

TLAB 用尽后：

1. 尝试新 TLAB；
2. 向 `G1Allocator` 申请新的 Eden Region；
3. 若分配无法满足，触发 `G1CollectForAllocation` 安全点，进入 Young GC。

### 3.2 PLAB（Promotion Local Allocation Buffer，晋升本地分配缓冲区）

GC 时存活对象需要复制到 Survivor/Old。每个 GC worker 在目标 Region 中使用自己的 **PLAB**，专门用于解决对象在晋升（复制） 过程中的多线程竞争问题。

相关结构：

- `G1ParScanThreadState`：每个 worker 的扫描/复制上下文；
- `G1PLABAllocator`：管理 PLAB；
- `G1AllocRegion`：Region 级分配器。

### 3.3 Humongous 分配

Humongous 不通过普通 TLAB：

1. 计算需要连续 Region 数；
2. 从空闲 Region 中找连续范围；
3. 初始化 starts/continues Humongous；
4. 如果找不到连续范围，可能触发 GC 或 Full GC。

## 4. Young-only GC

### 4.1 触发

常见 cause：

- `G1 Evacuation Pause`：Young 分配失败；
- `Metadata GC Threshold`：元数据/类加载触发；
- `GCLocker Initiated GC`；
- `Humongous Allocation`。

### 4.2 CSet 选择

Young CSet：

```text
CSet = Eden Regions + Survivor Regions
```

`G1CollectionSet` 保存本轮要回收的 Region。Mixed 时还会加入部分 Old Region。

### 4.3 根扫描

使用 `G1RootProcessor` 与 `G1RootClosures`：

- Java 线程栈、JNI handle、monitor；
- ClassLoaderData / 类静态字段；
- VM roots、OopStorage；
- Code cache 中的 nmethod；
- 其他 VM 内部根。

日志中的阶段：

```text
Ext Root Scanning
Scan Heap Roots
  Scanned Cards
  Found Roots
Code Root Scan
Object Copy
Termination
Remembered Sets
```

### 4.4 RSet 扫描

对 CSet 外的 Region，通过 card table + RSet 找到指向 CSet 的引用：

1. 处理 dirty card；
2. 合并 RSet；
3. 扫描 card，找到跨 Region 引用；
4. 把引用对象标记为存活并复制。

### 4.5 对象复制（Evacuation）

worker 对 CSet 中存活对象：

1. 计算目标 Survivor/Old；
2. 在 PLAB 中分配目标空间；
3. 复制对象；
4. 写入 forwarding pointer；
5. 更新引用；
6. 旧 Region 计入垃圾。

如果目标空间不足 → **Evacuation Failure** / `To-space exhausted`，可能触发 Full GC。

### 4.6 结束后

- Eden Region 清空并回到 free list；
- Survivor Region 更新年龄；
- 成熟对象晋升 Old；
- 如果正在并发标记，本轮产生的 Survivor Region 会被登记为 **root region**（见下一节）。

## 5. 并发标记周期：起始标记到底做了什么

这是本文的重点。

### 5.1 触发

IHOP（默认 45%，自适应；项目 lab 30）或显式/元数据等原因触发：

```text
Pause Young (Concurrent Start) (G1 Evacuation Pause)
```

`Concurrent Start` 不是“一个纯粹的标记线程开始跑”，它首先是一次 **STW Young GC**。

### 5.2 进入标记前：reset 与 TAMS

HotSpot 在 `pre_concurrent_start` 中并行执行：

1. **Reset marking state**：清空每 Region 的 live words、任务统计、root region 列表等；
2. **Note start of marking**：遍历所有 Region，为每个 Region 记录 TAMS。

JDK 21 的 `HeapRegion::note_start_of_marking()` 逻辑：

```text
if region is Old or Humongous:
    TAMS = current top
else:  // Eden/Survivor
    TAMS remains bottom (CA region)
```

为什么这样设计：

- Old/Humongous 中已有对象在标记开始时全部视为“需要标记的候选”，TAMS = top 表示“这个位置以上的新对象不需要再扫描”；
- Young 区域中的对象会经过本轮 evacuation 重排；Survivor 中产生的新对象成为 root regions，之后从 TAMS 到 top 扫描；
- 标记位图只需要覆盖对象起始地址，而不是复制整个对象图。

### 5.3 Mark Bitmap

关键结构：

```text
G1CMBitMap
└── MarkBitMap
    ├── _covered     // 覆盖的堆范围
    ├── _shifter     // 每个 bit 覆盖多少个 HeapWord
    └── _bm          // 底层 BitMap
```

标记粒度：

- 每个对象起始地址对应 **1 bit**；
- 通常 1 bit 覆盖 1 个 8 字节 HeapWord，即约 1/64 堆大小；
- 地址通过 `addr_to_offset(addr) = (addr - covered.start()) >> shifter` 转成位偏移；
- `par_mark` 使用原子操作，多个标记线程可以安全竞争同一对象；
- 标记成功才把对象压入 mark stack，避免重复扫描。

日志中看不到 bitmap 本身，但可以通过：

```text
GC(n) Concurrent Mark Cycle
GC(n) Concurrent Mark
```

以及 Remark 后统计的 live words 推断它的结果。

### 5.4 初始标记的根扫描

初始标记阶段使用 `G1RootProcessor` 扫描强根：

```text
G1RootProcessor
├── process_java_roots
│   ├── Threads::possibly_parallel_oops_do   // Java 栈、JNI、monitor
│   └── ClassLoaderDataGraph::roots_cld_do    // 类加载器/静态字段
├── process_vm_roots
│   └── OopStorage strong roots               // VM 内部根
└── process_code_cache_roots
    └── CodeCache nmethods
```

在并发标记开始时，nmethod 还分：

- **strong nmethods**：从线程栈可达，需要 evacuation + mark；
- **weak nmethods**：只从 code root remembered set 可达，需要 evacuation，但可以不 mark（允许类卸载）。

扫描到引用时：

```text
mark_in_bitmap(obj)
 ├─ 若 obj 在 TAMS 之上 → 直接视为新对象，不标记/不入栈
 ├─ bitmap.par_mark(obj) == false → 已标记，跳过
 └─ true → 记录 live_words，入 mark stack，等待并发扫描
```

因此“起始标记”并不是递归扫描整个对象图，而是：

1. 扫描根；
2. 标记根直接引用的对象；
3. 把它们的字段引用压入 mark stack；
4. 后续由并发标记任务继续扩展对象图。

### 5.5 Mark Stack

两个层级：

| 结构 | 说明 |
|---|---|
| `G1CMTaskQueue` | 每个标记 worker 的本地灰色对象队列 |
| `G1CMMarkStack` | 全局溢出 mark stack，分 chunk 存储（1024−1 个 entry/chunk） |
| `G1CMTaskQueueSet` | 所有 task queue 的集合，用于工作窃取 |
| `TaskTerminator` | 终止协议：所有队列为空且无新 SATB 才结束 |

对象变灰的过程：

```text
mark bitmap CAS 成功
   → 将对象字段引用压入本地队列/全局栈
   → task 循环 pop
   → 扫描对象字段
   → 对每个引用重复以上过程
```

栈溢出/队列过大时，通过两个同步屏障重初始化全局结构，避免数据竞争；`MarkStackSize`/`MarkStackSizeMax` 控制初始和最大容量。

### 5.6 Concurrent Root Region Scan

初始标记同时也会做 Young GC。本轮 evacuation 产生的 Survivor Region 会被登记为 **root region**：

```cpp
// 简化自 G1CollectedHeap::retire_gc_alloc_region
if (during_initial_mark && allocated_bytes > 0) {
    cm->add_root_region(alloc_region);
}
```

登记的是 `[TAMS, top]` 区间。为什么需要它：

- 这些 Survivor 对象是在标记开始后新复制/晋升的；
- 它们可能引用 Old 中的对象；
- 必须把它们当作额外的根区域，逐一扫描其中的引用。

初始标记完成后（非 STW），并发执行：

```text
G1CMRootRegionScanTask
  claim root region
  scan [TAMS, top] object-by-object
  oop_iterate fields
  mark referenced objects / push to mark stack
```

所以起始标记实际是两层扫描：

```text
STW 根扫描：Java/VM/CLD/CodeCache roots
并发 root region scan：初始标记 Young GC 产生的 Survivor regions
```

### 5.7 SATB 如何加入

初始标记暂停结束后，`post_concurrent_mark_start()` 激活 SATB：

```text
satb_mq_set.set_active_all_threads(true, ...)
```

应用线程继续运行后，任何可能覆盖引用的写操作都会走 **pre-write barrier**：

```text
旧引用 != null 且 SATB 活跃
  → 把旧引用写入线程本地 SATB queue
  → queue 满 → 移交 completed buffers
```

SATB 的目的：

- 以标记开始时的对象图为快照；
- 即使应用线程随后删除了某个引用，只要旧值被记录，它指向的对象仍会被标记；
- 代价是浮动垃圾：标记期间已经死亡的对象可能仍被视为存活。

相关结构：

- `G1SATBMarkQueueSet`：全局 SATB queue 集合；
- `SATBMarkQueue`：每线程队列；
- buffer：队列满后封装成 buffer；
- Remark 阶段 `G1CMTask::drain_satb_buffers()` 消费。

项目日志中如果看到：

```text
GC(n) Concurrent Cleanup for Next Mark
GC(n) Concurrent Mark Cycle
```

说明 SATB/标记周期已经进入收尾。

### 5.8 并发标记任务如何工作

`G1CMTask` 的典型循环：

```text
while (!terminated):
    drain SATB buffers if available
    从本地队列取对象
    若本地为空，尝试从全局栈/其他 task 窃取
    扫描对象字段
    对未标记引用执行 mark_in_bitmap
    标记成功则入队
    检查 yield / 终止协议
```

`mark_in_bitmap` 中的 TAMS 判断来自 `HeapRegion::obj_allocated_since_marking_start(obj)`：

```text
obj >= TAMS  → 标记开始后分配，视为隐式存活，不重复标记
obj < TAMS   → 进入 bitmap CAS
```

这也是为什么 **TAMS 不是对象头里的字段**，而是每个 Region 的元数据；它不参与对象布局，只参与标记/存活统计。

### 5.9 并发标记期间的 RSet

并发标记不等于完全不维护 RSet：

- 应用线程继续写跨 Region 引用，card table 会被弄脏；
- refinement 线程继续处理 dirty card；
- 标记完成后，Remark/Cleanup 可能需要重建部分 RSet（`top_at_rebuild_start` / TARS）；
- 这就是为什么日志中会同时出现 marking 和 remset 统计。

## 6. Remark

Remark 是 STW，目标是把并发期间遗留的标记工作收干净。

主要步骤：

```text
1. 停止应用线程
2. 处理所有 SATB buffers 中记录的旧引用
3. 处理 Reference（Soft/Weak/Final/Phantom）
4. 类卸载（如果开启）
5. 验证 mark bitmap / 标记状态
6. 回收完全空的 Region
7. 更新 RSet tracking，记录 TARS
8. 计算每 Region 的 live bytes / garbage bytes
9. 重置标记状态（保留 bitmap，等待下次清理）
10. 可能调整堆大小
```

日志：

```text
Pause Remark
  Finalize Concurrent Mark Cleanup
  Update Remembered Set Tracking Before Rebuild
  Reclaim Empty Regions
  Purge Metaspace
```

关键数据结构：

- SATB buffers；
- `ReferenceProcessor`；
- `G1RegionMarkStats`：每 Region live words；
- `_top_at_rebuild_starts`（TARS）：需要重建 RSet 的 Region 的起始扫描位置；
- mark bitmap：此时不再修改对象图，只用于验证/统计。

## 7. Cleanup

Cleanup 是并发周期的最终整理，日志中会看到：

```text
Pause Cleanup
Concurrent Cleanup for Next Mark
```

主要工作：

1. 根据 mark bitmap 与 live words 计算 Region 垃圾；
2. 完全空的 Region 回收到 free list；
3. 统计可用于 Mixed 的 Old Region；
4. 更新 RSet/堆大小；
5. 并发清除 mark bitmap，为下一轮做准备。

JDK 21 的实现有一个容易误解的点：

- 现场只有**一个当前 mark bitmap**（`G1CMBitMap _mark_bitmap`）；
- 周期结束后，`cleanup_for_next_mark()` 使用 `G1ClearBitMapTask` 并发清空它；
- 旧资料里的 “current/next bitmap” 描述不适用于每个版本，阅读源码时以当前版本字段为准。

数据流：

```text
mark bitmap + 每 Region live_words
        │
        ▼
  garbage_bytes / liveness
        │
        ├─ 空 Region → free list
        ├─ 低存活率 Old → Mixed 候选
        └─ 高存活率 Old → 留在 Old set
```

## 8. Mixed GC

### 8.1 CSet 组成

Mixed 发生在并发周期之后，CSet：

```text
Young CSet + 部分低存活率 Old Region
```

选择依据（概念上）：

- Old Region 存活率低于 `G1MixedGCLiveThresholdPercent`（默认 85，experimental）；
- 回收收益高于 `G1HeapWastePercent`；
- 单次 Mixed 不超过 `G1OldCSetRegionThresholdPercent` 的限制；
- 总次数目标受 `G1MixedGCCountTarget` 影响；
- 预留空间受 `G1ReservePercent` 影响。

### 8.2 日志

```text
Pause Young (Prepare Mixed) (G1 Evacuation Pause)
Pause Young (Mixed) (G1 Evacuation Pause)
```

- `Prepare Mixed` 是准备阶段；
- `Mixed` 才真正回收 Old Region；
- 周期完成后执行下一次 Young 时才可能触发 Mixed；
- 如果日志出现 `do not continue mixed GCs (candidate old regions not available)`，说明没有更多合适的 Old CSet。

### 8.3 为什么“全存活对象”不会触发 Mixed

Mixed 需要可回收的 Old Region：

```text
Old Region 中全部是活对象
  → liveness 接近 100%
  → 不在 Mixed 候选阈值内
  → CSet 没有 Old Region
  → 只能继续 Young GC
```

项目的 `mixed-gc` 场景先用 dead batch 制造“晋升后死亡”的 Old 垃圾，再保留 live batch，正是为了跨过这个条件。

## 9. Full GC

触发：

- 显式 `System.gc()`（默认）；
- Evacuation Failure / To-space exhausted；
- Humongous 分配失败；
- 堆不足时连续回收失败；
- 其他兜底情况。

Full GC 使用 **mark-compact**，而不是分区增量回收：

```text
标记所有存活对象
→ 按 Region 压缩/移动
→ 更新引用
→ 重建/清理 RSet
```

日志：

```text
Pause Full (System.gc())
Phase 4: Compact heap
```

Full GC 的数据结构更接近传统标记-压缩：

- mark bitmap / mark stack（标记阶段）；
- 压缩目标 Region / compaction point；
- forwarding pointer；
- 对象移动后的引用修正。

Full GC 是 STW 的最后兜底，生产环境不应把它当成常规回收路径。

## 10. 数据结构总表

| 数据/结构 | 所在阶段 | 作用 | 关键点 |
|---|---|---|---|
| `HeapRegion` | 全流程 | Region 元数据 | bottom/top/end、type、TAMS、BOT |
| `HeapRegionManager` | 全流程 | Region 管理 | 索引、free list、分配/回收 |
| `G1RegionToSpaceMapper` | 初始化 | 堆到辅助空间映射 | card table、bitmap、元数据 |
| TLAB | 分配 | 线程本地分配 | 减少全局同步 |
| PLAB | Evacuation | GC worker 复制缓冲 | 减少复制锁竞争 |
| `G1CollectionSet` | Young/Mixed | 本次回收 Region 集合 | Young + 可选 Old |
| `G1BlockOffsetTablePart` | 扫描/迭代 | Region 内对象起始定位 | 辅助解析 |
| `G1CardTable` | 写屏障/RSet | 1 byte/card，card 通常 512B | dirty/young/scanned 值 |
| `G1CardSet` | RSet | 每 Region 的引用卡片集合 | InlinePtr → ArrayOfCards → Howl/BitMap → Full |
| `G1RemSet` | 根扫描 | 合并/扫描 RSet | `scan_heap_roots`、`merge_heap_roots` |
| `G1SATBMarkQueueSet` | 并发标记 | 记录旧引用 | pre-write barrier、buffer |
| `G1CMBitMap` / `MarkBitMap` | 并发标记 | 对象起始标记位 | 1 bit/HeapWord，CAS 标记 |
| `_top_at_mark_start` (TAMS) | 并发标记 | 每 Region 标记起点 | Old/H = top；Young = bottom |
| `_top_at_rebuild_start` (TARS) | Remark/Cleanup | RSet 重建起点 | 标记后的重建扫描 |
| `G1CMTaskQueue` | 并发标记 | worker 本地灰色对象队列 | 工作窃取 |
| `G1CMMarkStack` | 并发标记 | 全局溢出 mark stack | chunk 化、可扩容 |
| `TaskTerminator` | 并发标记 | 终止协议 | 所有队列空且 SATB 处理完 |
| `G1RegionMarkStats` | 标记统计 | 每 Region live words | 供 Mixed 选择 |
| `ReferenceProcessor` | Remark | 处理引用 | 软/弱/虚/final |
| `G1FullCollector` | Full GC | 全堆标记压缩 | 最后兜底 |

## 11. 阶段与数据结构对照

| 阶段 | STW? | 主要输入 | 主要数据结构 | 主要输出/日志 |
|---|---|---|---|---|
| TLAB 分配 | 否 | 应用线程 | TLAB、Eden Region | 分配 |
| Young GC | 是 | Eden/Survivor CSet | root closures、RSet、PLAB | `Pause Young (Normal)` |
| Concurrent Start | 是 | 全堆 | TAMS、mark bitmap、root closures | `Pause Young (Concurrent Start)` |
| Root Region Scan | 否 | Survivor root regions | `G1CMRootMemRegions`、mark stack | `Concurrent Scan Root Region` |
| Concurrent Mark | 否 | 根/灰色对象/SATB | `G1CMTaskQueue`、`G1CMMarkStack`、bitmap | `Concurrent Mark From Roots` |
| Remark | 是 | SATB/引用/类 | SATB buffers、ReferenceProcessor、stats | `Pause Remark` |
| Cleanup | 是 | live words/bitmap | region stats、free list | `Pause Cleanup` |
| Concurrent cleanup | 否 | 下一轮 bitmap | `G1ClearBitMapTask` | `Concurrent Cleanup for Next Mark` |
| Mixed GC | 是 | Young + Old CSet | CSet、PLAB、RSet | `Pause Young (Mixed)` |
| Full GC | 是 | 全堆 | mark-compact 结构 | `Pause Full` |

## 12. 常见误区

1. **Concurrent Start 只是并发标记开始**：它本身是一次 STW Young Pause，带初始标记和 Young 回收。
2. **初始标记扫描整个堆**：不是；它扫描根并标记直接引用，后续对象图由并发标记扩展。
3. **TAMS 存在对象头里**：不是；它是 `HeapRegion` 的字段，用于判断“标记开始后分配”和计算 liveness。
4. **mark bitmap 记录每个对象字段**：不是；只标记对象起始地址，字段通过 mark stack 遍历。
5. **SATB 就是 finalizer**：不是；SATB 是并发标记的快照语义，与 finalization 无关。
6. **RSet 就是 card table**：不是；card table 是粗粒度脏页表，RSet 是每个 Region 的精细引用集合，card set 还会粗化。
7. **Cleanup 只是清空 bitmap**：它还计算/回收 Region、选择 Mixed 候选，并影响后续策略。
8. **所有 Mixed 都会一次清空 Old**：不是；按存活率、收益和次数目标分批回收。
9. **Full GC 是正常回收**：它是兜底；频繁 Full GC 应该找根因。
10. **所有 JDK 版本结构完全一样**：不是；本文按 JDK 21，旧版本的 bitmap/cleanup 细节可能不同。

## 13. 用项目日志验证

启动 verbose：

```bash
PROFILE=verbose ./scripts/run.sh
```

执行 Mixed：

```bash
curl -s -X POST localhost:8080/api/scenarios/mixed-gc/run \
  -H 'Content-Type: application/json' -d '{}'
```

重点搜索：

```bash
grep -E 'Pause Young \(Concurrent Start\)|Concurrent Mark From Roots|Pause Remark|Pause Cleanup|Pause Young \(Mixed\)' logs/gc-*.log
```

把日志按阶段对应到本文：

```text
Pause Young (Concurrent Start)
   → TAMS 设置、根扫描、mark bitmap 初始标记
Concurrent Mark From Roots
   → mark stack 扩展对象图
Pause Remark
   → SATB 收尾、引用/类、存活统计
Pause Cleanup
   → region 回收/混合候选、bitmap 清理准备
Pause Young (Mixed)
   → CSet 含 Old Region，执行 Evacuation
```

实际样例见 [samples/gc-scenarios-2g.log](samples/gc-scenarios-2g.log)，逐文件分析见 [09-gc-log-file-analysis.md](09-gc-log-file-analysis.md)。

## 14. 推荐源码阅读顺序

基于 OpenJDK 21：

1. `gc/g1/heapRegion.hpp`：Region/TAMS/BOT 字段
2. `gc/shared/markBitMap.hpp`、`gc/g1/g1ConcurrentMarkBitMap.hpp`：bitmap 粒度
3. `gc/g1/g1ConcurrentMark.hpp`：mark stack、task queue、region stats
4. `gc/g1/g1ConcurrentMark.cpp`：pre concurrent start、TAMS、scan root region、cleanup
5. `gc/g1/g1ConcurrentMark.inline.hpp`：`mark_in_bitmap` 与 TAMS 判断
6. `gc/g1/g1RootProcessor.cpp`：Java/VM/CLD/code cache 根扫描
7. `gc/g1/g1CollectedHeap.cpp`：root region 注册与 Young GC 集成
8. `gc/g1/g1CardSet.hpp`、`g1CardTable.hpp`：RSet/card table
9. `gc/g1/g1RemSet.hpp`：merge/scan heap roots

OpenJDK 源码（`jdk-21-ga`）：

- [g1ConcurrentMark.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1ConcurrentMark.hpp)
- [g1ConcurrentMark.cpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1ConcurrentMark.cpp)
- [g1ConcurrentMark.inline.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1ConcurrentMark.inline.hpp)
- [markBitMap.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/shared/markBitMap.hpp)
- [g1RootProcessor.cpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1RootProcessor.cpp)
- [g1CollectedHeap.cpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1CollectedHeap.cpp)
- [g1CardSet.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1CardSet.hpp)
- [g1CardTable.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/g1CardTable.hpp)
- [heapRegion.hpp](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/hotspot/share/gc/g1/heapRegion.hpp)

## 15. 一句话总结

G1 的全流程可以理解为：

```text
分配在 Region，回收靠 CSet；
跨区引用靠 card table/RSet；
并发标记靠 TAMS + mark bitmap + mark stack + SATB；
Cleanup 把 bitmap 结果变成 Region liveness 和 Mixed 候选；
Evacuation 失败才退到 Full GC。
```

理解这条链路，比记住任何单个参数都更重要。
