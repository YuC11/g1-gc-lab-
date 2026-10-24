# G1 调优工作坊：用实验代替猜参数

> 本篇不是“最佳参数清单”，而是一套可重复的调优方法。目标是在相同 workload 下做出可解释的 A/B 对比，而不是把网上看到的参数抄进生产。

## 1. 调优的第一原则

```text
先确认瓶颈 → 再提出假设 → 只改一个变量 → 用相同 workload 复测 → 记录决策
```

一个参数如果没有对应的可观测证据，就不应该改。

### 1.1 先定义目标

不同类型的服务对 GC 的“好”定义不同：

| 目标 | 更关注 | 不应只看 |
|---|---|---|
| 低延迟 API | P99 pause、超时、错误率 | GC 次数 |
| 吞吐型批处理 | 吞吐、GC 开销、CPU | 单次最长 pause |
| 内存敏感服务 | 峰值内存、Full GC、OOM | 平均 pause |
| 大对象/缓存服务 | Humongous、Old 增长、碎片 | 仅看 Young 次数 |

### 1.2 必须记录的基线

```text
JVM/GC 版本
-Xms/-Xmx
Region 大小/数量
MaxGCPauseMillis
IHOP / Adaptive IHOP
MaxTenuringThreshold
分配率（MB/s）
live set（GC 后 Old + Survivor 大致值）
P50/P95/P99/最大 pause
Young/Mixed/Full 次数
GC overhead
CPU 使用率
业务吞吐、错误率
```

项目内置：

```bash
curl -s localhost:8080/api/status | jq .
./scripts/analyze-gc-log.sh logs/gc-XXXX.log
```

## 2. 实验工具

### 2.1 固定 workload

建议用 `allocation-storm` 或 `young-gc` 作为统一 workload。不要一次用 storm，一次用 mixed，否则无法比较。

### 2.2 启动/停止 profile

```bash
# 实验 A
PROFILE=jdk-defaults MAVEN_CMD=mvn ./scripts/run.sh

# 实验 B
PROFILE=default MAVEN_CMD=mvn ./scripts/run.sh
```

每次执行同一请求：

```bash
curl -s -X POST localhost:8080/api/scenarios/allocation-storm/run \
  -H 'Content-Type: application/json' \
  -d '{"threads":4,"durationMs":8000,"chunkKb":256}' | jq .
```

### 2.3 自动 A/B 工作台

项目提供：

```bash
./scripts/tuning-lab.sh allocation-storm
```

它会依次启动多个 profile，执行同一场景，保存：

```text
reports/tuning-<timestamp>/
├── summary.tsv
└── <profile>/
    ├── app.log
    ├── result.json
    ├── config.json
    ├── gc.log
    └── analysis.txt
```

默认 profile 序列：

```text
jdk-defaults → default → low-latency → throughput
```

也可以指定：

```bash
SCENARIO=young-gc PROFILES="default low-latency" ./scripts/tuning-lab.sh
```

> 该脚本会真正启动 JVM、分配内存并停止实例，必须在本地机器运行。

## 3. 实验一：pause target 的取舍

**假设**：降低 `MaxGCPauseMillis` 会缩短单次暂停，但增加 GC 次数。

```bash
PROFILE=low-latency MAVEN_CMD=mvn ./scripts/run.sh   # 50ms
PROFILE=throughput MAVEN_CMD=mvn ./scripts/run.sh    # 500ms
```

执行同一个 `allocation-storm`，记录：

| 指标 | low-latency | throughput |
|---|---:|---:|
| Young GC 次数 | | |
| 平均 pause | | |
| P99 / max pause | | |
| GC overhead | | |
| 实际耗时/吞吐 | | |
| CPU | | |

**判断**：

- 如果业务 P99 远低于 50ms，而 GC 次数明显增加，可能不需要那么激进的 pause target。
- 如果 pause 已经很低但吞吐下降，考虑放宽目标。
- 如果 max pause 仍然超过目标，找阶段（Object Copy / RSet / Reference），不要继续盲目调小。

## 4. 实验二：堆大小 2G vs 4G

**假设**：更大的堆降低 GC 频率，但可能增加单次停顿和 Full GC 成本。

```bash
HEAP=2g MAVEN_CMD=mvn ./scripts/run.sh
HEAP=4g MAVEN_CMD=mvn ./scripts/run.sh
```

记录：

- Region 大小（1M vs 2M）
- Young/Mixed 次数
- 单次 pause
- Full GC 次数
- live set 与分配率

**注意**：

- 2G 和 4G 的默认场景规模不同（代码按比例缩放），要使用相同 `totalMb`/`durationMs` 做对比。
- 不要把 4G 的 H 对象数量与 2G 直接比较，除非你控制了对象大小与数量。

## 5. 实验三：Region 大小

**假设**：Region 变大会降低 Humongous 数量，但改变 RSet 粒度。

```bash
REGION_SIZE=1m HEAP=2g MAVEN_CMD=mvn ./scripts/run.sh
REGION_SIZE=2m HEAP=2g MAVEN_CMD=mvn ./scripts/run.sh
REGION_SIZE=4m HEAP=2g MAVEN_CMD=mvn ./scripts/run.sh
```

依次执行 `humongous`，记录：

- `Humongous regions`
- H 对象数量/总大小
- Young pause
- Update/Scan RSet 阶段
- 堆浪费和碎片

**判断**：

- 如果 H 对象是主要问题，可评估拆小对象或复用 buffer。
- 固定 Region 大小可能让 Region 数偏离 2048；大 Region 也可能增加单次复制成本。
- 不要为了消除 H 对象把 Region 调到 32MB 而不看其他影响。

## 6. 实验四：IHOP

**假设**：IHOP 降低会更早开始并发标记，减少 Full GC 风险，但增加并发 CPU 和标记次数。

```bash
EXTRA_JAVA_OPTS='-XX:InitiatingHeapOccupancyPercent=20' MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:InitiatingHeapOccupancyPercent=30' MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:InitiatingHeapOccupancyPercent=45' MAVEN_CMD=mvn ./scripts/run.sh
```

执行 `mixed-gc`，记录：

- 是否出现 Concurrent Start
- 是否出现 Mixed
- 并发标记时长
- 是否出现 Full GC
- CPU

**判断**：

- 低 IHOP：标记更早、可能更安全，但 CPU 压力更大。
- 高 IHOP：并发周期更少，但可能来不及回收，导致 allocation failure / Full GC。
- 自适应 IHOP 会根据历史调整；固定参数实验时应记录 `G1UseAdaptiveIHOP`。

## 7. 实验五：tenuring

**假设**：降低 `MaxTenuringThreshold` 会让对象更早晋升，增加 Old 压力；提高它会让对象在 Survivor 停留更久。

```bash
EXTRA_JAVA_OPTS='-XX:MaxTenuringThreshold=1' MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:MaxTenuringThreshold=3' MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:MaxTenuringThreshold=15' MAVEN_CMD=mvn ./scripts/run.sh
```

执行 `promotion`，记录：

- Survivor regions
- Old regions / Old used
- Young pause
- 是否提前 Full GC

**判断**：

- 提前晋升会占用 Old，但可能减少 Survivor 复制压力。
- 过高的阈值可能让 Survivor 长期高占用，未必更好。
- 生产环境优先通过减少短命大对象和优化对象生命周期解决，而不是只改阈值。

## 8. 实验六：String Dedup

**假设**：去重节省堆，但消耗并发 CPU。

```bash
# 默认开启
MAVEN_CMD=mvn ./scripts/run.sh
# 关闭
EXTRA_JAVA_OPTS='-XX:-UseStringDeduplication' MAVEN_CMD=mvn ./scripts/run.sh
```

执行 `string-dedup`，记录：

- 去重 deduped 数量/字节
- 并发去重耗时
- CPU
- 堆节省

**判断**：

- 大量重复长字符串才值得开启。
- 短命字符串通常没有收益。
- 去重不是 STW，但会占用并发线程。

## 9. 实验七：显式 GC

```bash
MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:+DisableExplicitGC' MAVEN_CMD=mvn ./scripts/run.sh
EXTRA_JAVA_OPTS='-XX:+ExplicitGCInvokesConcurrent' MAVEN_CMD=mvn ./scripts/run.sh
```

执行 `full-gc`，比较：

- 是否真的 Full GC
- 是否转为并发周期
- pause 与吞吐

**判断**：

- 先找谁在调用 `System.gc()`；用 `DisableExplicitGC` 掩盖问题通常不是修复。
- `ExplicitGCInvokesConcurrent` 可能减少停顿，但会改变语义和并发 CPU。

## 10. 调优记录模板

把每次实验写成一行：

```text
日期 | workload | 参数A | 参数B | 堆/Region | P99 | max | GC次数 | Full次数 | CPU | 吞吐 | 结论
```

或者使用表格：

| 字段 | 实验 A | 实验 B |
|---|---|---|
| 假设 | | |
| JVM flags | | |
| workload | | |
| 分配量/时长 | | |
| Region | | |
| P50/P95/P99 | | |
| 最大 pause | | |
| Young/Mixed/Full | | |
| GC overhead | | |
| CPU | | |
| 业务指标 | | |
| 结论 | | |

## 11. 调优决策树

```text
Full GC 很多？
├── cause = System.gc() → 查显式调用；评估 Disable/Concurrent
├── cause = Evacuation Failure → 检查 live set、Reserve、堆、晋升
└── cause = Allocation Failure → 检查分配率、IHOP、堆、碎片

Young pause 长？
├── Object Copy 高 → 存活集/晋升
├── Remembered Sets/Scanned Cards 高 → 跨 Region 引用/RSet
├── Reference 高 → 软/弱/虚引用/finalizer
└── 根扫描高 → 类加载器/线程/根对象

Mixed 不出现？
├── 没到 IHOP → 检查堆占用/自适应 IHOP/分配率
├── 到达且完成 Cleanup → 等下一次 Young；补压力
└── Old 全存活 → 没有可回收 CSet，不是参数问题
```

## 12. 什么时候不要继续调 GC

- 业务对象泄漏（Old 持续增长且 Full 后不降）。
- 线程/连接/ClassLoader 泄漏。
- 分配率异常来自业务代码，而不是 GC 参数。
- 容器 CPU/内存限制配置错误。
- 直接内存、JNI、元空间等其他内存区域不足。

GC 调优只能优化“回收策略”，不能修复“对象生命周期”或“容量规划”问题。

## 13. 最终练习

选一个真实或模拟服务，完成一份一页纸报告：

1. 基线：2G 和 4G 各一组。
2. 两次单变量实验：pause target 和 IHOP。
3. 一张日志证据截图/片段。
4. 一个明确的结论和回滚条件。
5. 一个“不采用”的参数及原因。

做到这里，你才真正具备 G1 调优能力，而不是记住几个数字。
