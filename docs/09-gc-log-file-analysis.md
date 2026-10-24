# GC 日志文件分析记录

> 本文分析 `docs/samples/` 中实际生成的日志和场景响应快照。所有统计均可用文末命令重新得到。数值来自 2026-10-20 的实际运行，机器/JDK/参数变化会导致结果不同。

## 1. 分析对象与生成方式

环境：

```text
JDK 21.0.11
Spring Boot 3.5.16
PROFILE=verbose
2G 运行：HEAP=2g, Region=1M, IHOP=30, MaxTenuringThreshold=3, StringDedup age=1
4G 运行：HEAP=4g, Region=2M, 其余相同
```

生成命令：

```bash
cd /Users/yc/Downloads/gc-demo
MAVEN_CMD=mvn ./scripts/generate-sample-logs.sh
```

生成过程：

1. 启动 2G verbose 实例；
2. 依次执行 10 个场景（9 场景 + reset）；
3. 保存 GC / class unload / stringdedup / safepoint 日志和场景 JSON；
4. 启动 4G 实例，执行 Young/Humongous/Full/Reset；
5. 裁剪日志到 `docs/samples/`。

## 2. 文件清单

| 文件 | 类型 | 行数/大小 | 说明 |
|---|---|---|---|
| `docs/samples/gc-scenarios-2g.log` | GC 日志 | 169 行（149 行 GC + 20 行 side 摘要）/ 约 18.9KB | 2G、1M Region，全部场景 |
| `docs/samples/gc-scenarios-4g.log` | GC 日志 | 55 行 / 约 5.7KB | 4G、2M Region，冒烟场景 |
| `docs/samples/scenario-results-2g.json` | API 响应 | 10 个对象 | 全部 2G 场景结果 |
| `docs/samples/scenario-results-4g.json` | API 响应 | 4 个对象 | 4G 冒烟结果 |
| `docs/samples/class-unload-2g.log` | class unload 日志 | 20 行（摘录） | 完整 500 行在 `logs/` |
| `docs/samples/stringdedup-2g.log` | stringdedup 日志 | 33 行 | 完整去重统计 |
| `docs/samples/safepoint-2g.log` | safepoint 日志 | 80 行 | G1 全部安全点 |

> 2G GC 日志末尾还附加了 8 条 class unload 与 8 条 stringdedup 摘要，方便单文件阅读。

## 3. 2G GC 日志总体分析

### 3.1 暂停统计

命令：

```bash
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-2g.log
```

实际输出摘要：

| 类型 | 次数 | 平均(ms) | 最大(ms) |
|---|---:|---:|---:|
| Young | 22 | 8.146 | 34.789 |
| Mixed | 5 | 4.916 | 8.347 |
| Remark | 4 | 2.458 | 2.668 |
| Cleanup | 4 | 0.311 | 0.452 |
| Full | 3 | 23.095 | 27.204 |
| **总计** | **38** | **7.478** | **34.789** |

结论：

- 本场景以 Young 为主，符合短命对象洪峰；
- Mixed 出现了 5 次，说明混合回收路径被实际触发；
- Full 3 次来自显式 GC：full-gc、class-unload、reset；
- 最长暂停 34.789ms，来自 promotion 的晋升回收，而不是 Full GC；
- **暂停次数多不等于单次长**，要分别看分配率、存活集和阶段。

### 3.2 启动 Region ergonomics

```text
[2026-10-20T10:40:35.814+0000][0.005s][info ][gc       ] Using G1
[2026-10-20T10:40:35.817+0000][0.008s][info ][gc,init       ] Heap Region Size: 1M
[2026-10-20T10:40:35.817+0000][0.008s][info ][gc,init       ] Heap Max Capacity: 2G
```

分析：

- 2G/2048 ≈ 1M，符合 G1 约 2048 Region 的 ergonomics；
- 1M Region 的 Humongous 阈值约 512KB；
- 场景普通 chunk 使用 256KB，避免误入 H 对象。

### 3.3 事件时间线

以下是从日志中提取的关键事件（时间戳省略到秒）：

| 时间 | GC | 类型 | 堆 | Pause |
|---|---|---|---:|---:|
| 0.650s | GC(0) | Concurrent Start (Metadata) | 147M->9M | 5.587ms |
| 1.5s 左右 | GC(2/3) | Prepare Mixed / Mixed (Metadata) | 约 151M->18M | 约 8-9ms |
| 2.x s | - | Normal Young（启动/预热） | 约 211M->18M | 约 5ms |
| 3.638s | GC(8) | Young Normal（Promotion） | 732M->432M | **34.789ms** |
| 7.510s | GC(20) | Humongous 清理 | H 96->48 | - |
| 9.286s | GC(21) | Humongous 清理 | H 49->1 | - |
| 11.043s | GC(22) | Concurrent Start | 1613M->934M | 9.070ms |
| 11.069s | GC(23) | Remark | 1070M->200M | 2.668ms |
| 11.075s | GC(23) | Cleanup | 271M->271M | 0.313ms |
| 12.813s | GC(26) | **Mixed** | 743M->29M | 3.209ms |
| 14.598s | GC(31) | Full | 255M->18M | 20.377ms |
| 16.966s | GC(32) | Full（class unload） | 78M->19M | 27.204ms |
| 19.965s | GC(33) | Full（reset） | 21M->18M | 21.704ms |

说明：

- 启动阶段 `Metadata GC Threshold` 会打乱“干净堆”假设，因此不要拿第一次 GC 当作纯业务场景。
- promotion 的 34.789ms 是本次最长 pause，说明**存活对象复制/晋升**比 Full GC 更值得分析。
- Mixed 在 Cleanup 后约 1.7s 出现，证明“Cleanup 之后下一次 Young Pause”这个时序假设。

### 3.4 Mixed 详细分析

关键行：

```text
[2026-10-20T10:40:46.843+0000][11.034s][info ][gc,start] GC(22) Pause Young (Concurrent Start) (G1 Evacuation Pause)
[2026-10-20T10:40:46.852+0000][11.043s][info ][gc      ] GC(22) Pause Young (Concurrent Start) (G1 Evacuation Pause) 1613M->934M(2048M) 9.070ms
[2026-10-20T10:40:46.875+0000][11.067s][info ][gc,start] GC(23) Pause Remark
[2026-10-20T10:40:46.878+0000][11.069s][info ][gc      ] GC(23) Pause Remark 1070M->200M(2048M) 2.668ms
[2026-10-20T10:40:46.883+0000][11.075s][info ][gc,start] GC(23) Pause Cleanup
[2026-10-20T10:40:46.884+0000][11.075s][info ][gc      ] GC(23) Pause Cleanup 271M->271M(2048M) 0.313ms
[2026-10-20T10:40:48.618+0000][12.809s][info ][gc,start] GC(26) Pause Young (Mixed) (G1 Evacuation Pause)
[2026-10-20T10:40:48.621+0000][12.813s][info ][gc      ] GC(26) Pause Young (Mixed) (G1 Evacuation Pause) 743M->29M(2048M) 3.209ms
```

分析：

1. 堆占用达到 IHOP（lab 30%），Concurrent Start 启动标记周期。
2. Remark 将堆从 1070M 降到 200M，说明标记/清理了大量浮动垃圾。
3. Cleanup 回收空 Region，为 Mixed 准备 CSet。
4. 约 1.7s 后下一次 Young Pause 真正带回 Old Region，出现 Mixed。
5. Mixed 后堆从 743M 降到 29M，是本次实验中最明显的回收效果之一。
6. GC id 中的 GC(22)、GC(23)、GC(26) 不连续，是并发阶段内部编号导致的，不能按 id 强行配对每次阶段。

配套 JSON：

```json
{
  "scenario": "mixed-gc",
  "gcDelta": 12,
  "observedEventTypes": ["Young", "Concurrent Start", "Remark", "Cleanup", "Prepare Mixed", "Mixed"]
}
```

### 3.5 Full GC 分析

```text
GC(31) Pause Full (System.gc()) 255M->18M(2048M) 20.377ms
GC(32) Pause Full (System.gc()) 78M->19M(2048M) 27.204ms
GC(33) Pause Full (System.gc()) 21M->18M(2048M) 21.704ms
```

- 3 次都来自显式 `System.gc()`，不是 allocation failure。
- 每次堆都不大（≤255MB），但 Full GC 仍明显慢于 Young：20-27ms。
- 后续 `class-unload` 与 `reset` 也使用显式 GC，因此这里的 3 次 Full 是设计行为。
- 生产环境不能用这三次的绝对值判断“Full GC 快”，因为堆占用很低；堆大时 Full GC 会更重。

### 3.6 Humongous 分析

```text
GC(20) Humongous regions: 96->48
GC(21) Humongous regions: 49->1
GC(22) Humongous regions: 1->1
```

- 分配 32 个 2MB 对象（1M Region，H 阈值 512KB），保留一半；
- H Region 从 96 降到 48，再降到 1，说明释放/回收链路有效；
- H 对象数量与 region 大小直接相关，不能只看“对象个数”。

## 4. 4G GC 日志分析

### 4.1 暂停统计

```bash
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-4g.log
```

| 类型 | 次数 | 平均(ms) | 最大(ms) |
|---|---:|---:|---:|
| Young | 5 | 7.879 | 15.903 |
| Mixed | 1 | 9.413 | 9.413 |
| Remark | 2 | 2.825 | 3.409 |
| Cleanup | 2 | 0.241 | 0.349 |
| Full | 2 | 21.297 | 22.720 |
| **总计** | **12** | **8.128** | **22.720** |

### 4.2 Region 差异

```text
[2026-10-20T10:41:00.969+0000][0.009s][info][gc,init] Heap Region Size: 2M
[2026-10-20T10:41:00.969+0000][0.009s][info][gc,init] Heap Max Capacity: 4G
```

- 4G → 2M Region，Region 数仍约 2048；
- H 阈值上升到约 1MB；
- 同一个 2MB 对象在 4G 下仍为 H 对象，但占用的 Region 数不同。

### 4.3 Humongous / Full

```text
GC(7) Humongous regions: 64->32
GC(8) Humongous regions: 32->32
GC(9) Humongous regions: 32->0
GC(8) Pause Full (System.gc()) 369M->81M(4096M) 22.720ms
```

- H 回收路径与 2G 一致，但数量不同；
- 4G 的 Full 最大 pause 为 22.720ms，不能与 2G 直接比较，因为本次 4G 只跑了冒烟场景。

## 5. Class Unload 日志分析

文件：`docs/samples/class-unload-2g.log`（前 20 行摘录；完整 500 行在 `logs/`）。

示例：

```text
[2026-10-20T10:40:52.754+0000][16.946s][info][class,unload] unloading class DynamicClasses$C499 0x000000e801710a50
[2026-10-20T10:40:52.755+0000][16.946s][info][class,unload] unloading class DynamicClasses$C498 0x000000e801710840
...
```

统计：

```bash
grep -c 'unloading class' logs/class-unload-*.log
# 500
```

与场景 JSON 对照：

```text
Metaspace 41.5 MB -> 51.3 MB -> 50.5 MB
```

分析：

- 500 个动态加载的嵌套类都触发了卸载；
- Metaspace 加载后增加约 9.8MB，卸载后回落约 0.8MB；
- **真正证明卸载发生的是 `unloading class` 行**，不仅是 Metaspace 数字；
- committed Metaspace 不立即归还 OS，因此不能用“RSS 没降”否定类卸载。

## 6. String Dedup 日志分析

文件：`docs/samples/stringdedup-2g.log`，33 行，全部是 `Concurrent String Deduplication` 统计。

关键行：

```text
Concurrent String Deduplication 16021/1109.5K (new), 15344/924.4K (deduped), avg 83.3%, 14.627ms of 14.956ms
...
Concurrent String Deduplication 9/1072.0B (new), 30030/15469.3K (deduped), avg 637.0%, 68.089ms of 68.090ms
```

分析：

- 前几行来自启动/预热；
- 最后一个高峰中，去重线程处理了 30030 个字符串，节省约 15.1MB；
- `avg 637.0%` 是 JDK 内部统计口径，不要按字面理解成“效率 637%”；重点是 `deduped` 数量和大小；
- 去重线程耗时 68.089ms，但这是并发阶段，不是 STW；
- age threshold 设为 1 后，短实验也能触发去重。

## 7. Safepoint 日志分析

文件：`docs/samples/safepoint-2g.log`，80 条。

统计（脚本见文末）：

| Safepoint 原因 | 次数 | 平均(ns) | 最大(ns) |
|---|---:|---:|---:|
| G1CollectForAllocation | 23 | 7,933,548 | 34,896,041 |
| G1CollectFull | 3 | 23,207,458 | 27,359,250 |
| CollectForMetadataAllocation | 4 | 8,629,073 | 15,837,959 |
| G1PauseRemark | 4 | 2,592,333 | 2,766,875 |
| G1PauseCleanup | 4 | 406,520 | 519,833 |
| Cleanup | 14 | 99,136 | 503,042 |
| ICBufferFull | 28 | 114,267 | 160,959 |

最长：

```text
[2026-10-20T10:40:39.446+0000][3.638s][info][safepoint]
Safepoint "G1CollectForAllocation", Time since last: 795532375 ns,
Reaching safepoint: 3583 ns, Cleanup: 35000 ns,
At safepoint: 34857458 ns, Total: 34896041 ns
```

分析：

- `At safepoint` 占了 34.857ms，几乎等于 34.789ms 的 Young pause；这说明 safepoint 日志和 GC pause 时间是同一事件的两种视角。
- `G1CollectForAllocation` 是最常见原因，说明分配触发的 GC 占主导。
- `ICBufferFull` 是最频繁但最短的安全点，属于 JIT 代码缓存管理。
- 读 safepoint 日志时先看 `At safepoint`，再看 `Total`；不要把所有安全点都当成 GC。

## 8. 场景 JSON 分析

### 8.1 2G 全场景

| 场景 | 耗时(ms) | 分配(MB) | retained(MB) | GC 增量 | 观察到的事件 |
|---|---:|---:|---:|---:|---|
| young-gc | 737 | 279 | 0 | 2 | Young |
| promotion | 898 | 768 | 256 | 1 | Young |
| mixed-gc | 950 | 2763 | 204 | 12 | Young, Concurrent Start, Remark, Cleanup, Prepare Mixed, Mixed |
| humongous | 732 | 339 | 32 | 1 | Mixed |
| cross-region | 737 | 409 | 22 | 1 | Mixed |
| string-dedup | 754 | 1024 | 31 | 4 | Concurrent Start, Young, Remark, Cleanup |
| allocation-storm | 751 | 3072 | 31 | 6 | Prepare Mixed, Mixed, Young |
| full-gc | 829 | 0 | 31 | 1 | Full |
| class-unload | 2473 | 0 | 31 | 1 | Full |
| reset | 1030 | 0 | 0 | 1 | Full |

注意：

- `retained` 是场景结束时的 retained，不是分配量；后续场景开始时会清空。
- young 实际只分配了 279MB 就达到 2 次 GC 的停止条件，所以不是固定分配 1.2GB。
- allocation-storm 达到 3GB 上限提前结束，响应中会记录实际值。
- 场景不是隔离的：promotion 之后的 Old 状态会影响 mixed；要严格对比需重启或 reset。

### 8.2 4G 冒烟

| 场景 | 耗时(ms) | 分配(MB) | GC 增量 | 事件 |
|---|---:|---:|---:|---|
| young-gc | 750 | 512 | 1 | Young |
| humongous | 750 | 416 | 1 | Young |
| full-gc | 833 | 0 | 1 | Full |
| reset | 1027 | 0 | 1 | Full |

4G 只做冒烟，不用于完整对比统计，避免把不同 workload 混在一起。

## 9. 一致性检查

| 检查项 | 结果 |
|---|---|
| `/api/status` 2G Region | 1M / 2048 |
| `/api/status` 4G Region | 2M / 2048 |
| 2G 日志启动行 | `Heap Region Size: 1M` |
| 4G 日志启动行 | `Heap Region Size: 2M` |
| Mixed 触发 | `observedEventTypes` 包含 Mixed |
| Full cause | `System.gc()` |
| class unload | 500 条 unloading |
| string dedup | 33 条去重统计 |
| 日志解析器统计 | 与脚本统计一致 |

## 10. 复现分析命令

```bash
cd /Users/yc/Downloads/gc-demo

# 总体暂停统计
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-2g.log
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-4g.log

# 场景结果
jq -r '.[] | [.scenario,.elapsedMs,.allocatedMb,.retainedMb,.gcDelta,(.observedEventTypes|join("|"))] | @tsv' \
  docs/samples/scenario-results-2g.json

# Mixed / Full / H
grep -E 'Pause Young \(Mixed\)|Pause Full|Humongous regions' docs/samples/gc-scenarios-2g.log

# class unload
grep -c 'unloading class' logs/class-unload-*.log

# string dedup
grep 'Concurrent String Deduplication' docs/samples/stringdedup-2g.log

# safepoint 原因聚合
python3 - <<'PY'
import re, collections
rows = []
for line in open('docs/samples/safepoint-2g.log'):
    m = re.search(r'Safepoint "([^"]+)".*Total: (\d+) ns', line)
    if m:
        rows.append((m.group(1), int(m.group(2))))
d = collections.defaultdict(list)
for reason, total in rows:
    d[reason].append(total)
print('total', len(rows), 'max_ns', max(t for _, t in rows))
for reason, vals in sorted(d.items(), key=lambda x: -max(x[1])):
    print(reason, len(vals), sum(vals) // len(vals), max(vals))
PY
```

## 11. 分析结论

1. 2G 下 Region=1M，H 阈值 512KB；4G 下 Region=2M，H 阈值 1MB。
2. 本项目的 Mixed 路径真实发生，且需要“并发周期 + Cleanup 后的下一次 Young”两个条件。
3. 最长的 GC 暂停来自晋升/复制（34.789ms），不是 Full GC。
4. 显式 Full GC 的 cause 明确为 System.gc()，不代表生产中的 allocation failure。
5. 类卸载以 `unloading class` 为准；Metaspace committed 不立即回落。
6. 字符串去重是并发工作，独立日志 tag 为 `stringdedup`，不能被 `gc*` 自动包含。
7. Safepoint 日志的 `At safepoint` 与 GC pause 可以互相对照。
8. 这些数值只对当前参数和 JDK 21 有效；换堆、Region、pause target 后必须重新测量。
