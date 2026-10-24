# 真正学会 G1：从观察到调优的学习路线

> 背参数不等于会 G1。真正的能力是：看到一次暂停，能判断它属于哪个阶段、时间花在哪里、下一步应该验证什么。
>
> 本文把项目里的场景、日志、文档和代码组织成一条可以自学的路线。建议至少安排 4 次、每次 60–90 分钟的实验。

## 0. 学习目标

完成本路线后，你应该能够：

1. 解释 G1 的 Region、CSet、RSet、SATB、Humongous 和三种回收周期。
2. 从一行 GC 日志读出 GC 类型、cause、前后堆、暂停和 Region 变化。
3. 区分“分配率问题”“存活集问题”“RSet 问题”“Humongous/碎片问题”“显式 GC 问题”。
4. 设计一个只改变一个变量的 A/B 实验，并用暂停分布、吞吐和 Full GC 次数做决策。
5. 不把 `MaxGCPauseMillis` 当硬保证，不把“加堆”当唯一调优手段。

## 1. 学习循环：不要只看，必须预测

每一次实验按下面五步做：

```text
1. 观察现状（GC 次数、pause、堆、Region）
2. 写下假设（我认为瓶颈是……）
3. 只改一个变量
4. 重跑相同 workload
5. 用日志证据推翻或接受假设
```

如果只点按钮、不看日志，你得到的是演示；如果先预测再验证，你得到的是可迁移的调优能力。

## 2. 模块一：建立 Region 直觉

**目标**：理解“2G 为什么是 1M Region，4G 为什么是 2M”。

**实验**：

```bash
HEAP=2g ./scripts/run.sh
# 查看 /api/status 中的 regionSizeBytes / regionCount / humongousThresholdBytes
```

停止后：

```bash
HEAP=4g ./scripts/run.sh
```

**观察**：

| 堆 | Region | 数量 | H 阈值 |
|---|---|---|---|
| 2G | 1M | 2048 | 约 512KB |
| 4G | 2M | 2048 | 约 1MB |

**必须回答**：

- 为什么 G1 希望约 2048 个 Region？
- Region 变大后，Humongous 门槛、RSet 粒度、碎片分别怎么变？
- 为什么“对象刚好 512KB”在 1M Region 下可能成为 Humongous？

**完成标准**：能不用文档画出 Eden/Survivor/Old/Humongous/Free 的转换，并解释 H 判定。

## 3. 模块二：Young GC 与分配率

**目标**：把“GC 次数多”拆成分配率、Eden 大小、存活集三个因素。

**实验**：

1. 执行 `young-gc`。
2. 再执行 `allocation-storm`，把线程数从 4 改成 2 或 8。
3. 用 `analyze-gc-log.sh` 比较暂停次数和平均 pause。

**看什么**：

```text
Pause Young (Normal) (G1 Evacuation Pause)
Eden regions: ...
```

**必须回答**：

- 为什么 GC 次数增加但单次 pause 不一定增加？
- 分配率翻倍时，Young GC 次数、CPU、吞吐分别怎么变？
- `MaxGCPauseMillis` 调小为什么可能让 GC 更频繁？

**完成标准**：能解释“高分配率”与“高存活集”是两类不同问题。

## 4. 模块三：Survivor、年龄与晋升

**目标**：理解对象如何从 Eden 到 Survivor，再到 Old。

**实验**：

1. `reset`。
2. 执行 `promotion`。
3. 记录响应中的 `oldBeforeMb`、`oldAfterMb`。
4. 用 `HEAP=2g PROFILE=jdk-defaults` 再跑一次，对比 lab 的 `MaxTenuringThreshold=3`。

**看什么**：

```text
Survivor regions: ...
Old regions: ...
```

**必须回答**：

- 为什么“活着”不等于“在 Old”？
- Survivor 放不下会发生什么？
- 提前晋升会怎样影响 Mixed 和 Full GC 风险？

**完成标准**：能解释 Promotion 场景为什么 Old 会增长，以及如何构造“晋升后死亡”的 Old 垃圾。

## 5. 模块四：RSet、写屏障与跨 Region 引用

**目标**：理解 G1 为什么需要 RSet，以及它如何变成暂停成本。

**实验**：

```bash
PROFILE=verbose ./scripts/run.sh
```

点击 `cross-region`，然后：

```bash
grep -E 'gc,remset|gc,refine,stats|Remembered Sets|Scanned Cards' logs/gc-*.log | tail -30
```

**必须回答**：

- 没有 RSet 时，回收一个 Region 为什么可能扫描全堆？
- 跨 Region 引用多，Update/Scan/Refinement 成本怎么变化？
- 为什么“减少对象图跨区引用”有时比调 GC 参数更有效？

**完成标准**：能从 verbose 日志指出 RSet 相关的统计行。

## 6. 模块五：并发标记与 SATB

**目标**：理解并发标记为什么需要 SATB、Remark 和 Cleanup。

**实验**：

1. `reset`。
2. 执行 `mixed-gc`。
3. 在日志中找出：

```text
Concurrent Start
Concurrent Mark Cycle
Pause Remark
Pause Cleanup
Prepare Mixed
Pause Young (Mixed)
```

**必须回答**：

- 并发标记与业务线程并行，为什么仍然需要 Remark？
- TAMS 和 SATB 分别解决什么问题？
- 为什么“看到 Concurrent Start”不等于“Mixed 已经发生”？

**完成标准**：能按时间顺序复述一个完整的 space-reclamation 周期，并指出其中哪些是 STW。

## 7. 模块六：Mixed GC 与 CSet

**目标**：知道 Mixed 需要“可回收的 Old Region + 下一次 Young Pause”。

**实验**：

1. 先只保留全存活对象，观察 Mixed 是否出现。
2. 再执行项目的 `mixed-gc`（先制造并释放 dead 批次）。
3. 对比 `Mixed` 次数和 Old used。

**必须回答**：

- 为什么全存活对象不会产生 Mixed？
- `G1MixedGCLiveThresholdPercent`、`G1HeapWastePercent` 影响什么？
- 为什么 Mixed 发生在 Cleanup 之后，而不是 Concurrent Start 当场？

**完成标准**：能解释项目 Mixed 场景三阶段设计的必要性。

## 8. 模块七：Humongous、碎片与 Full GC

**目标**：区分 H 对象、晋升失败和显式 Full GC。

**实验**：

1. `humongous` 场景，查看 `Humongous regions`。
2. `full-gc` 场景，查看 `Pause Full (System.gc())` 和 `Phase 4: Compact heap`。
3. 用 `EXTRA_JAVA_OPTS='-XX:+DisableExplicitGC'` 重启后再执行 full-gc。
4. 用 `EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent'` 观察差异。

**必须回答**：

- 大对象为什么可能造成碎片？
- Evacuation Failure 与显式 GC 的 Full GC 在 cause 上有什么区别？
- 为什么不能把“频繁 Full GC”直接等同于“把堆调大”？

**完成标准**：能根据 cause 判断 Full GC 是设计触发还是故障兜底。

## 9. 模块八：类卸载与字符串去重

**目标**：知道 G1 不只回收普通对象。

**实验**：

1. `class-unload`，查看独立 class-unload 日志。
2. `string-dedup`，查看独立 stringdedup 日志。
3. 执行前后对比 Metaspace 和响应中的 dedup 数据。

**必须回答**：

- 类卸载需要哪些引用不可达？
- Metaspace committed 不下降是否代表没有卸载？
- 字符串去重的收益与 CPU 成本如何权衡？

**完成标准**：能从独立日志找到 `unloading class` 和 `Concurrent String Deduplication`。

## 10. 模块九：读日志，而不是背日志

拿 [samples/gc-scenarios-2g.log](samples/gc-scenarios-2g.log) 做三遍练习：

1. 只圈出所有 pause，按类型计数。
2. 找出最长 pause，猜它属于哪个场景，再用 09 文档验证。
3. 找一次 Mixed 的完整周期，标出 STW 和并发阶段。

然后拿 [09-gc-log-file-analysis.md](09-gc-log-file-analysis.md) 对照。不要先看答案。

**完成标准**：能在 5 分钟内独立说出一个日志文件的主要 GC 行为。

## 11. 模块十：调优方法论

前往 [11-g1-tuning-workshop.md](11-g1-tuning-workshop.md) 做 A/B 实验。

核心不是记住“IHOP=30”，而是学会：

```text
基线 → 假设 → 单变量 → 相同 workload → 看分布 → 决策 → 回归
```

必须始终记录：

- 堆/Region/IHOP/pause target
- 分配率、live set
- P50/P95/P99/最大 pause
- GC 次数、GC 开销、Full GC 次数
- CPU、吞吐、业务错误率

**完成标准**：能设计一个实验，证明某个参数改变了什么、没有改变什么。

## 12. 建议的 7 天安排

| 天 | 内容 | 产出 |
|---|---|---|
| Day 1 | 模块一、二 | 解释 2G/4G Region 与 Young GC |
| Day 2 | 模块三、四 | Promotion 与 RSet 日志笔记 |
| Day 3 | 模块五、六 | 画出一个 Mixed 周期 |
| Day 4 | 模块七、八 | Full GC cause 与类卸载/去重 |
| Day 5 | 模块九 | 独立分析 2G 日志 |
| Day 6 | 模块十 | 两组 A/B 实验 |
| Day 7 | 复盘 | 一页调优报告 |

## 13. 自测题

1. G1 的 Region 大小由哪些因素决定？
2. Humongous 的判定条件是什么？对象头为什么重要？
3. `Pause Young (Mixed)` 与 `Pause Young (Normal)` 的 CSet 差异是什么？
4. `Concurrent Start` 是 STW 吗？`Remark` 呢？
5. SATB 解决了什么问题？TAMS 是什么？
6. RSet 记录“谁指向谁”？为什么需要写屏障？
7. 为什么 `MaxGCPauseMillis` 不是硬保证？
8. IHOP 调低有什么代价？
9. `G1ReservePercent` 与 Evacuation Failure 有什么关系？
10. 为什么“全存活对象”不会触发 Mixed？
11. `System.gc()` 一定会 Full GC 吗？有哪些开关会改变行为？
12. 类卸载的判定依据是 Metaspace 回落还是 unloading 日志？
13. `stringdedup` 日志为什么不在 `gc*` 里？
14. 如何区分“分配率过高”和“存活集过大”？
15. 设计一个实验验证 Region 大小对 Humongous 的影响。

> 参考答案不直接给出，优先通过实验和日志验证。项目文档 01–09 足以支撑全部问题。

## 14. 常见错误学习方式

- 只背参数表，不跑场景。
- 只看 GC 次数，不看 pause 分布和分配率。
- 看到 Full GC 就加堆。
- Mixed 场景只保留活对象，然后说“G1 不回收老年代”。
- 把并发阶段耗时当成 STW pause。
- 把 Metaspace committed 不下降等同于类没有卸载。
- 用一次运行的绝对值下结论，不做重复实验。
- 同时改多个参数，无法归因。

## 15. 下一步

- 想深入源码级机制：读 [12-g1-end-to-end-internals.md](12-g1-end-to-end-internals.md)。
- 想系统做实验：读 [11-g1-tuning-workshop.md](11-g1-tuning-workshop.md)。
- 想看逐文件日志结论：读 [09-gc-log-file-analysis.md](09-gc-log-file-analysis.md)。
- 想从零复现：读 [08-reproduction-runbook.md](08-reproduction-runbook.md)。
- 想回顾实现与踩坑：读 [07-implementation-journal.md](07-implementation-journal.md)。


## 16. 自测题参考答案

1. **Region 大小**：G1 以约 2048 个 Region 为目标，取满足堆大小/2048 的 2 的幂，范围 1–32MB；可由 `G1HeapRegionSize` 覆盖。
2. **H 判定**：对象大小严格大于 region/2；byte[] 的对象头也计入大小，所以“刚好半个 Region”的数组可能越界。
3. **CSet 差异**：Normal 只回收 Young；Mixed 在 Young 之外还加入低存活率的 Old Region。
4. **STW**：Concurrent Start 本身是一次 Young Pause，是 STW；并发标记不是；Remark 是 STW；Cleanup 也是 STW。
5. **SATB/TAMS**：SATB 以标记开始时的对象图快照为准，pre-write barrier 记录被覆盖的旧引用；TAMS 是标记开始时的分配顶端，用来区分新分配对象。
6. **RSet/写屏障**：RSet 记录“其他 Region 指向本 Region”的引用，使回收时无需扫描全堆；post-write barrier 把被修改的 card 标记为 dirty。
7. **pause target**：`MaxGCPauseMillis` 是目标而非硬保证；G1 通过选择 CSet 和 Young 大小尽量满足，但对象复制/引用处理等仍可能超出。
8. **IHOP 代价**：调低会更早开始并发标记，通常降低 Full GC 风险，但增加并发 CPU 和标记频率。
9. **Reserve**：预留空间用于降低晋升/Evacuation 失败的风险，但不能保证不发生，也不是越大越好。
10. **Mixed 条件**：需要可回收的低存活率 Old Region，并且通常要等 Cleanup 后的下一次 Young Pause；全存活对象没有可回收 CSet。
11. **System.gc**：默认通常会 Full GC；`-XX:+DisableExplicitGC` 可忽略，`-XX:+ExplicitGCInvokesConcurrent` 可能转为并发周期。
12. **类卸载**：以 `class,unload` 的 `unloading class` 行为准；Metaspace committed 可能因分配器/OS 策略不立即下降。
13. **stringdedup**：JDK 21 中它使用独立 tag `stringdedup`，不是 `gc,stringdedup`；`gc*` 选择器不会匹配。
14. **两类问题**：高分配率通常表现为频繁但较短的 Young GC；存活集过大表现为复制/晋升成本、Old 增长、Mixed/Full 风险。
15. **Region 实验**：固定堆、对象大小/数量和 workload，只改 `REGION_SIZE`，比较 Humongous regions、暂停阶段、堆浪费和 Full GC。
