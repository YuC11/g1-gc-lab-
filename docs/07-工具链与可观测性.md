# 观测与诊断工具

## 1. 项目内置观测

| 能力 | 实现 | 入口 |
|---|---|---|
| GC 事件 | 解析 `-Xlog` 的 pause 汇总行 | `/api/gc/events`、GC 时间线 |
| GC 回退 | JMX `GarbageCollectionNotificationInfo` | 无日志文件时自动启用 |
| 堆与池 | `MemoryMXBean` + `MemoryPoolMXBean` | `/api/status`、堆组成 |
| GC 计数 | `GarbageCollectorMXBean` | Young/Full 次数与耗时 |
| G1 参数 | `HotSpotDiagnosticMXBean` | `/api/config`、参数面板 |
| 日志 tail | 安全的文件尾读取 | `/api/logs/tail` |
| Metrics | Actuator + Micrometer | `/actuator/metrics` |
| 堆转储 | Actuator heapdump | `/actuator/heapdump` |

GC 日志是主数据源，因为它能区分 **Normal / Concurrent Start / Prepare Mixed / Mixed / Full**；JMX 只有 Young/Full 级别的粗略分类。

## 2. JMX MXBean

### 2.1 `GarbageCollectorMXBean`

```java
for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
    bean.getName();            // G1 Young Generation / G1 Old Generation
    bean.getCollectionCount();
    bean.getCollectionTime();
}
```

### 2.2 `GarbageCollectionNotificationInfo`

通过 `NotificationEmitter.addNotificationListener` 注册。可拿到：

- GC 名称、action、cause
- `GcInfo.getDuration()`
- GC 前后各 MemoryPool 的 `MemoryUsage`
- GC 线程数

本项目的 `JmxGcFallback` 就使用它。

### 2.3 `MemoryPoolMXBean`

G1 相关池：

```text
G1 Eden Space
G1 Survivor Space
G1 Old Gen
Metaspace
Compressed Class Space
```

## 3. JFR

### 3.1 启动时录制

```bash
java -XX:StartFlightRecording=filename=logs/g1.jfr,settings=profile,dumponexit=true -jar ...
```

或运行中：

```bash
jcmd <pid> JFR.start name=g1 settings=profile filename=logs/g1.jfr
# 复现问题后
jcmd <pid> JFR.dump name=g1 filename=logs/g1-dump.jfr
jcmd <pid> JFR.stop name=g1
```

### 3.2 常用 GC 事件

| 事件 | 价值 |
|---|---|
| `jdk.G1GarbageCollection` | G1 GC 类型（Young/Mixed/Full）、duration |
| `jdk.GCHeapSummary` | before/after 堆使用 |
| `jdk.GCPhasePause` | STW 阶段耗时 |
| `jdk.GCPhaseConcurrent` | 并发阶段耗时 |
| `jdk.G1HeapRegionTypeChange` | Region 状态转换 |
| `jdk.G1HeapRegionInformation` | Region 信息 |
| `jdk.EvacuationFailed` | Evacuation Failure |
| `jdk.YoungGarbageCollection` | 年轻代 GC |
| `jdk.OldGarbageCollection` | Old/Full GC |
| `jdk.ClassUnload` | 类卸载 |
| `jdk.G1AdaptiveIHOP` | 自适应 IHOP 决策 |

查看元数据：

```bash
jfr metadata | grep -A20 'class G1GarbageCollection'
```

打印：

```bash
jfr print --events jdk.G1GarbageCollection,jdk.GCHeapSummary logs/g1.jfr
jfr summary logs/g1.jfr
```

JFR 对分析“为什么这次暂停长”通常比只看 GC 日志更有结构，但 GC 日志更适合逐行教学。

## 4. jcmd

```bash
jcmd -l
jcmd <pid> VM.command_line
jcmd <pid> VM.flags
jcmd <pid> VM.flags -all
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jcmd <pid> GC.heap_dump logs/heap.hprof
jcmd <pid> GC.run
jcmd <pid> VM.metaspace
jcmd <pid> Thread.print
jcmd <pid> JFR.start name=g1 filename=logs/g1.jfr
```

- `GC.heap_info`：G1 Region/堆使用概览。
- `GC.class_histogram`：类实例占用；会触发安全点。
- `GC.heap_dump`：完整堆转储，文件可能很大。
- `GC.run`：建议只在本地实验使用。
- `VM.flags -all`：包含 experimental 参数。

## 5. jstat

```bash
jstat -gc <pid> 1000 20
jstat -gcutil <pid> 1000 20
jstat -gccause <pid> 1000 20
```

字段速读：

| 字段 | 含义 |
|---|---|
| S0/S1 | Survivor 使用率 |
| E | Eden 使用率 |
| O | Old 使用率 |
| M | Metaspace 使用率 |
| YGC/YGCT | Young GC 次数/累计时间 |
| FGC/FGCT | Full GC 次数/累计时间 |
| GCT | 总 GC 时间 |
| GCC | 最近原因 |

`jstat` 是快速看趋势的工具，不替代日志和 JFR。

## 6. 日志分析

项目脚本：

```bash
./scripts/analyze-gc-log.sh logs/gc-xxxx.log
```

它会输出：

- 按类型统计的暂停次数/平均/最大；
- 最长暂停原始行；
- Full GC 行；
- Concurrent / Humongous / Failure 标记；
- 常用 grep 命令。

手工查询：

```bash
grep -E 'Pause (Young|Full|Remark|Cleanup)' logs/gc-*.log
grep -E 'Concurrent (Mark|Start|Cleanup|Remark)' logs/gc-*.log
grep -E 'Remembered Sets|Scanned Cards|gc,remset|gc,refine,stats' logs/gc-*.log
grep -E 'To-space exhausted|Evacuation Failure|Allocation Failure' logs/gc-*.log
grep -E 'Humongous regions' logs/gc-*.log
cat logs/class-unload-*.log | grep 'unloading class'
```

## 7. Safepoint

```bash
-Xlog:safepoint=info:file=logs/safepoint-%t.log:utctime,uptime,level,tags
```

常见内容：

```text
Safepoint "G1CollectForAllocation", Time since last: ... ns, Reaching safepoint: ... ns, At safepoint: ... ns, Total: ... ns
```

关注：

- `Time since last`：两次安全点间隔。
- `Reaching safepoint`：线程到达安全点耗时。
- `At safepoint`：安全点内操作耗时。
- 大量 `Cleanup`/`GC` 安全点说明 GC 或分配压力大。

## 8. 堆转储分析

```bash
jcmd <pid> GC.heap_dump logs/heap.hprof
```

工具：

- Eclipse MAT：Dominator Tree、Histogram、Leak Suspects。
- VisualVM：类直方图、堆 dump。
- `jhsdb` / `jmap`：命令行。

分析步骤：

1. Histogram 找异常大的类数量。
2. Dominator Tree 找保留最多内存的对象。
3. 查看 GC Roots 到泄漏对象的引用链。
4. 对照 ClassLoader / ThreadLocal / 静态集合等常见泄漏源。

## 9. JConsole / VisualVM

连接本地 PID 即可查看：

- 堆/非堆使用
- GC 次数与时间
- 线程
- 类加载
- MBean

JConsole 适合快速确认，生产事故更推荐 JFR + GC 日志 + heap dump。

## 10. Actuator

```bash
curl -s localhost:8080/actuator/health | jq .
curl -s localhost:8080/actuator/metrics | jq .
curl -s localhost:8080/actuator/metrics/jvm.memory.used | jq .
curl -s localhost:8080/actuator/metrics/jvm.gc.pause | jq .
```

默认只暴露 health/info/metrics/heapdump，且应用只绑定 `127.0.0.1`。

## 11. 观测顺序建议

1. `/api/status` 或 Actuator 看趋势。
2. `jcmd GC.heap_info` 确认堆与 Region。
3. `jstat -gcutil` 确认分配/晋升趋势。
4. GC 日志确认暂停类型、阶段、cause。
5. JFR 确认阶段耗时和 Region 变化。
6. heap dump 定位对象/ClassLoader 泄漏。
7. 最后才改参数，并做前后对比。
