# 实现与验证记录

> 本文按时间顺序记录项目的实现步骤、实际执行命令、关键输出、遇到的问题和修复方式。目标是让另一位工程师可以据此复核“这个项目是怎么做出来的”，并配合 [08-reproduction-runbook.md](08-reproduction-runbook.md) 从零复现。

## 0. 记录约定

- 时间：2026-10-19 完成主体实现；2026-10-20 完成最终环境验证与样例复核。
- 环境：macOS、JDK 21.0.11、Maven 3.9.16、10 CPU、32GB 内存。
- 路径：`/Users/yc/Downloads/gc-demo`。
- “产出”表示当天实际写入磁盘的文件；“验证”表示实际执行的命令/响应。
- 所有日志样例都来自真实 JVM 运行，不是手工伪造。

## 1. 环境基线

### 1.1 初始目录

```bash
pwd
# /Users/yc/Downloads/gc-demo

ls -la
# total 0
```

工作区为空，确认需要从零创建 Maven 项目。

### 1.2 工具版本

```bash
java -version
# openjdk version "21.0.11" 2026-04-21 LTS

mvn -version
# Apache Maven 3.9.16
# Java version: 21.0.11

sysctl -n hw.memsize hw.ncpu
# 34359738368 (32GB), 10
```

结论：JDK/Maven/资源满足 2G、4G 堆实验要求。

### 1.3 G1 ergonomics 预检

```bash
java -XshowSettings:vm -version
java -XX:+PrintFlagsFinal -version | grep -E 'UseG1GC|...'
```

关键结果：

```text
UseG1GC = true
MaxGCPauseMillis = 200
InitiatingHeapOccupancyPercent = 45
G1UseAdaptiveIHOP = true
ParallelGCThreads = 9
ConcGCThreads = 2
G1ConcRefinementThreads = 9
G1HeapWastePercent = 5
G1MixedGCCountTarget = 8
G1ReservePercent = 10
UseStringDeduplication = false
```

2G/4G 实测：

```bash
for h in 2g 4g; do
  java -Xms$h -Xmx$h -Xlog:gc+init=info -version 2>&1 \
    | grep -E 'Heap Region Size|Heap Max Capacity'
done
```

```text
2G -> Heap Region Size: 1M
4G -> Heap Region Size: 2M
```

后续文档和 UI 采用 `region 数约 2048`、`Humongous 阈值 = region/2` 的解释。

### 1.4 实验性参数确认

```bash
java -XX:+UnlockExperimentalVMOptions -XX:+PrintFlagsFinal \
  -Xms2g -Xmx2g -version 2>&1 \
  | grep -E 'G1NewSizePercent|G1MaxNewSizePercent|G1MixedGCLiveThresholdPercent|G1OldCSetRegionThresholdPercent'
```

```text
G1NewSizePercent = 5
G1MaxNewSizePercent = 60
G1MixedGCLiveThresholdPercent = 85
G1OldCSetRegionThresholdPercent = 10
```

结论：JDK 21 中这些参数需要 `-XX:+UnlockExperimentalVMOptions`，启动脚本必须显式解锁，否则 `/api/config` 会显示 `n/a`，调优 profile 也会失败。

## 2. 项目骨架

### Step 1：创建 Maven 工程

产出：

- `pom.xml`
- `src/main/java/com/example/gcdemo/GcDemoApplication.java`
- `src/main/resources/application.yml`
- `.gitignore`

技术决策：

- Spring Boot `3.5.16`
- Java 21
- `spring-boot-starter-web`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- `spring-boot-starter-test`
- 不使用 Lombok/前端构建工具
- 应用只绑定 `127.0.0.1`

### Step 2：生成 Maven Wrapper

```bash
mvn wrapper:wrapper -Dmaven=3.9.16
```

产出：

```text
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
```

验证：

```bash
MAVEN_USER_HOME="$PWD/.m2wrapper" ./mvnw -v
# Apache Maven 3.9.16
```

### Step 3：第一次编译

```bash
mvn -Dmaven.repo.local="$PWD/.m2repo" -DskipTests compile
```

出现第一次编译错误：

```text
HumongousScenario.java: 从 long 转换到 int 可能会有损失
MixedGcScenario.java: 从 long 转换到 int 可能会有损失
PromotionScenario.java: 从 long 转换到 int 可能会有损失
```

原因：`ScenarioSupport.checkAllocationMb` 的参数类型是 `int`，而场景传入的是 `long` MB。

修复：把 guard 接口改为 `long mb`，重新编译通过。

> 记录：这个错误发生在业务代码，而不是 Maven/依赖问题。

## 3. 观测层实现

### Step 4：JVM 与参数读取

产出 `JvmInfoService`：

- `RuntimeMXBean` 读取 JVM 版本、PID、启动参数。
- `HotSpotDiagnosticMXBean` 读取 `UseG1GC`、`G1HeapRegionSize`、`G1MaxNewSizePercent` 等参数。
- 计算 Region 数、Humongous 阈值。

验证：

```bash
curl -s http://127.0.0.1:18080/api/config | jq '.selectedFlags'
```

发现：未加 `UnlockExperimentalVMOptions` 时，几个 experimental flag 返回 `n/a`。随后在 `run.sh` 加入：

```text
-XX:+UnlockExperimentalVMOptions
```

### Step 5：堆与 GC 统计

产出：

- `HeapService`：`MemoryMXBean` + `MemoryPoolMXBean`
- `GcStatsService`：`GarbageCollectorMXBean`
- `HeapSnapshot` / `GcStats` record

验证：`/api/status` 返回 Eden、Survivor、Old、Metaspace 以及 GC 次数/耗时。

### Step 6：Unified GC Log 解析

产出 `GcLogParser`。第一次实现用正则直接取最后一个括号：

```text
Pause Full (System.gc())
```

实际得到 cause=`")"`，而不是 `System.gc()`。

原因：正则把嵌套括号的最内层当成了最后一个组。

修复：改为手写括号深度扫描，只取顶层括号组。增加单元测试：

```bash
mvn -Dmaven.repo.local="$PWD/.m2repo" -Dtest=GcLogParserTest test
```

### Step 7：GC 日志增量监听

产出 `GcLogWatcher`：

- 每 500ms 找最新 `logs/gc-*.log`
- 从末尾增量读取
- 处理半行、文件截断、rotation
- 只解析带 `before->after(capacity) pause` 的汇总行，避免 start/phases 重复

验证：运行场景后 `/api/status`：

```json
{"available":true,"parsedEvents":2,"error":null}
```

开始时 `bytesRead=0` 是预期行为：监听器从当前文件末尾开始，不重放启动前的旧日志。

### Step 8：日志 tail API

产出 `LogFileService`：

- 列出 `logs/*.log`
- 安全尾读
- 拒绝路径穿越与符号链接逃逸

第一次测试发现文件末尾的换行会导致最后两行变成“最后一行 + 空行”。

修复：在取最后 N 行前去掉尾部的空元素。测试 `LogFileServiceTest` 覆盖。

另外，日志按修改时间排序时 safepoint 文件可能排在 GC 文件前，导致 UI 默认选中错误文件。修复：

- GC 日志优先排序
- UI 优先选择 `gc-` 文件

### Step 9：JMX fallback

产出 `JmxGcFallback`：

- 日志文件不可用时监听 GC notification
- 至少提供 Young/Full/Concurrent 事件
- 与 GC 日志解析不重复：日志可用时 JMX 不再写事件

## 4. 内存 Guard 与场景

### Step 10：retained 与内存限制

产出：

- `RetainedMemory`：按 key 保存强引用
- `MemoryGuard`：retained 上限 = `min(768MB, 40% 堆)`
- `AllocationTools`：按页 touch，volatile sink 防止 JIT 消除

验证：

- 场景超过上限返回 400
- 并发执行返回 409
- 全场景串行

### Step 11：Young 场景的第一个陷阱

第一版场景默认 chunk = 512KB，堆 2G 时 `G1HeapRegionSize=1M`。运行时日志出现：

```text
Pause Young (Prepare Mixed) (G1 Humongous Allocation) (Evacuation Failure) 2047M->57M(2048M)
```

发现：512KB byte[] 加上对象头后超过 region/2（512KB），每个 chunk 都是 Humongous 对象，直接把堆塞满，触发 Evacuation Failure。

修复：

- 普通场景统一使用 256KB chunk
- 所有 retained 批次按总量循环分配，而不是按“次数”误算
- 文档记录对象头导致 H 判定越界的问题

这是实现过程中最重要的调优经验之一。

### Step 12：Mixed 场景的第一个陷阱

第一版 Mixed 场景只保留 live 对象并施压，结果没有 `Pause Young (Mixed)`。

原因：

1. Old Region 中的对象几乎全存活，没有低存活率 CSet 可回收。
2. 并发周期结束后需要下一次 Young Pause 才真正执行 Mixed。
3. 场景在 Cleanup 后马上结束，没等到下一次 Young。

修复：

1. 先制造一批会被晋升、随后释放的 dead 对象，形成可回收 old region。
2. 保留 live 批次维持堆占用。
3. 等待 Cleanup 后再分配一段压力，触发下一次 Young。
4. 在等待期间主动调用 `GcLogWatcher.poll()`，避免场景断言和 500ms 定时轮询竞争。

最终 `/api/scenarios/mixed-gc/run` 返回：

```json
{
  "observedEventTypes": [
    "Young", "Concurrent Start", "Remark", "Cleanup",
    "Prepare Mixed", "Mixed"
  ]
}
```

### Step 13：String Dedup 日志

最初只配置 `-Xlog:gc*=info`，但日志中没有 `Concurrent String Deduplication`。

查 JDK 21 日志后确认：该消息的 tag 是 **`stringdedup`**，不是 `gc,stringdedup`，因此 `gc*` 选择器不会匹配。

修复：

- 新增独立日志：

```text
-Xlog:stringdedup=info:file=logs/stringdedup-%t.log:...
```

- 默认 `StringDeduplicationAgeThreshold=1`，让短实验也能出现去重统计。
- UI 日志列表可查看该文件。

### Step 14：Class Unload 日志

配置：

```text
-Xlog:class+unload=info:file=logs/class-unload-%t.log:...
```

场景步骤：

1. 生成 500 个嵌套类。
2. 用独立 URLClassLoader 加载。
3. 清空引用并 `System.gc()`。
4. 查看 `unloading class` 与 Metaspace。

实际结果（最新一次样例运行）：

- 500 条 unloading 记录
- Metaspace 在当前场景 JSON 中记录为 `41.5MB -> 51.3MB -> 50.5MB`（不同运行略有浮动）
- 说明 class unload 已经发生，但 Metaspace committed 不会立即回落到系统

### Step 15：其余场景

- `humongous`：32 个 2MB 对象，日志出现 `Humongous regions: 96->48`、`49->1`
- `full-gc`：`Pause Full (System.gc())`
- `cross-region`：25 万节点，verbose 日志查看 `gc,remset`、`gc,refine,stats`
- `allocation-storm`：达到 3GB 分配上限后提前结束，实际时长会记录在响应中
- `promotion`：保留 256MB，Old 从约 17MB 增加到约 350MB

## 5. API 与控制台

### Step 16：REST API

产出：

- `/api/status`
- `/api/config`
- `/api/scenarios`
- `/api/scenarios/{id}/run`
- `/api/scenarios/reset`
- `/api/gc/events`
- `/api/logs`
- `/api/logs/tail`
- actuator health/metrics/heapdump

错误统一 `ProblemDetail`；200/400/403/404/409/501 在测试和实际调用中都验证过。

### Step 17：静态控制台

产出：

- `src/main/resources/static/index.html`
- `style.css`
- `app.js`

验证：

```bash
curl -s http://127.0.0.1:18080/ | grep '<title>'
curl -s http://127.0.0.1:18080/app.js | wc -c
```

页面轮询 `/api/status`、`/api/gc/events`、`/api/logs/tail`，不依赖 CDN。

## 6. 测试与首次完整运行

### Step 18：单元/集成测试

```bash
mvn -Dmaven.repo.local="$PWD/.m2repo" test
```

结果：

```text
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
```

覆盖：

- GcLogParser 5 个 tests
- LogFileService 2 个 tests
- MemoryGuard 2 个 tests
- ScenarioRegistry 1 个 test
- ApiIntegrationTest 5 个 tests

第一次测试有 2 个失败：

1. Full GC cause 解析为 `")"`
2. 日志 tail 多一行空行

均已按 Step 6/8 修复。

### Step 19：2G 场景全量运行

启动：

```bash
HEAP=2g PROFILE=verbose SKIP_BUILD=1 ./scripts/run.sh
```

依次执行：

```text
young-gc
promotion
mixed-gc
humongous
cross-region
string-dedup
allocation-storm
full-gc
class-unload
reset
```

结果（详见 [samples/scenario-results-2g.json](samples/scenario-results-2g.json)）：

| 场景 | GC 增量 | 观察到的类型 |
|---|---:|---|
| young-gc | 2 | Young |
| promotion | 1 | Young |
| mixed-gc | 12 | Young, Concurrent Start, Remark, Cleanup, Prepare Mixed, Mixed |
| humongous | 1 | Mixed |
| cross-region | 1 | Mixed |
| string-dedup | 4 | Concurrent Start, Young, Remark, Cleanup |
| allocation-storm | 6 | Prepare Mixed, Mixed, Young |
| full-gc | 1 | Full |
| class-unload | 1 | Full |
| reset | 1 | Full |

### Step 20：生成真实日志样例

```bash
MAVEN_CMD=mvn ./scripts/generate-sample-logs.sh
```

脚本行为：

1. 启动 2G verbose 实例。
2. 跑全部场景。
3. 保存 2G GC 日志、class unload、string dedup、safepoint。
4. 保存 2G 场景 JSON。
5. 启动 4G 实例，跑 Young/Humongous/Full/Reset。
6. 保存 4G GC 日志与场景 JSON。
7. 裁剪日志到 `docs/samples/`。

实际生成：

```text
docs/samples/gc-scenarios-2g.log
docs/samples/gc-scenarios-4g.log
docs/samples/scenario-results-2g.json
docs/samples/scenario-results-4g.json
docs/samples/class-unload-2g.log
docs/samples/stringdedup-2g.log
docs/samples/safepoint-2g.log
```

### Step 21：日志分析脚本验证

```bash
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-2g.log
```

2G 结果：

```text
Young     22  avg 8.146ms  max 34.789ms
Mixed      5  avg 4.916ms  max 8.347ms
Remark     4  avg 2.458ms  max 2.668ms
Cleanup    4  avg 0.311ms  max 0.452ms
Full       3  avg 23.095ms max 27.204ms
TOTAL     38  avg 7.478ms  max 34.789ms
```

4G 结果：

```text
Young     5  avg 7.879ms  max 15.903ms
Mixed     1  avg 9.413ms  max 9.413ms
Remark    2  avg 2.825ms  max 3.409ms
Cleanup   2  avg 0.241ms  max 0.349ms
Full      2  avg 21.297ms max 22.720ms
TOTAL    12  avg 8.128ms  max 22.720ms
```

## 7. 最终验证

### Step 22：测试全部通过

```bash
mvn -Dmaven.repo.local="$PWD/.m2repo" test
```

结果：15 tests, 0 failures。

### Step 23：五个 profile 启动冒烟

逐一启动并请求 `/api/config`、`/api/status`：

| Profile | 堆 | Region | 结果 |
|---|---:|---:|---|
| jdk-defaults | 2G | 1M | OK |
| low-latency | 4G | 2M | OK |
| throughput | 4G | 2M | OK |
| humongous | 2G | 4M | OK |
| verbose | 2G | 1M | OK |

### Step 24：最终运行与接口确认

- 使用 Maven Wrapper 构建并启动成功
- `/actuator/health` = UP
- `/api/status` 返回 G1、2G、1M、2048 regions
- `/api/scenarios` 返回 10 个实现（9 个场景 + reset）
- 静态页面、样式、JS 均返回 200
- `/api/logs` 正确标记最新 GC 日志为 active
- `/api/logs/tail` 返回真实日志
- Mixed 场景实际观察到 `Mixed`
- String dedup 独立日志有 `Concurrent String Deduplication` 记录

## 8. 已知限制

- UI 已用 Playwright + 系统 Chrome 做整页渲染与结构检查；未覆盖多浏览器视觉回归。
- 4G 只做 Young/Humongous/Full/Reset 冒烟，没有重新跑完整 9 场景；2G 覆盖完整场景。
- `allocation-storm` 会优先达到 3GB 单场景分配上限，因此实际耗时可能短于目标 duration；响应中会记录真实分配量与时长。
- Mixed 的可重复性受自适应 IHOP 和存活率影响；项目通过 dead batch + post-Cleanup 压力提高确定性，但仍可能在极端参数下不出现。
- 日志格式依赖 JDK 21；升级 JDK 后应重新运行测试和日志样例生成。

## 9. 2026-10-21 增补：专业注释与学习路径

### Step 25：为生产代码补充专业注释

目标：让代码本身承担教学职责，而不是只靠外部文档。

完成内容：

- 44 个生产 Java 文件全部增加类级 Javadoc，说明职责、G1 语义、线程安全和边界条件；
- 关键方法增加方法级 Javadoc（日志增量读取、parser 括号解析、内存门槛、场景等待、
  CSet 触发、类卸载、日志安全 tail 等）；
- 对非显然逻辑增加行内注释：
  - 256KB chunk 与 Humongous 阈值的因果关系；
  - Mixed 的“Cleanup 后下一次 Young”时序；
  - 实验性 G1 参数需要解锁；
  - 日志 watcher 的首次 offset / 半行缓冲 / 文件轮转；
  - 场景串行与日志 ingest 竞态。
- 前端 `app.js` 增加模块说明、函数级 JSDoc、XSS/轮询/DOM 上限注释；
- CSS 增加分区注释和“学习路径”样式。

验证：

```bash
mvn -q -DskipTests compile
node --check src/main/resources/static/app.js
```

### Step 26：G1 学习路线

新增 `docs/10-g1-learning-path.md`，包含：

- 学习循环：观察 → 假设 → 单变量 → 复测 → 决策；
- 10 个模块：Region、Young、晋升、RSet、SATB、Mixed、Humongous/Full、
  Class Unload/String Dedup、日志阅读、调优方法；
- 每个模块的命令、观察点、必须回答的问题和完成标准；
- 7 天学习安排、15 道自测题、常见错误学习方式。

### Step 27：调优工作坊

新增 `docs/11-g1-tuning-workshop.md`，包含：

- 目标定义、基线指标、固定 workload；
- pause target / 堆 / Region / IHOP / tenuring / String Dedup / 显式 GC 七组 A/B 实验；
- 记录模板、决策树、什么时候停止调 GC；
- 最终一页纸报告练习。

### Step 28：调优实验台与 UI 学习入口

新增 `scripts/tuning-lab.sh`：

- 按 profile 串行启动 JVM；
- 执行同一个场景；
- 保存 `app.log`、`result.json`、`config.json`、`gc.log`、`analysis.txt`、`summary.tsv`；
- 自动停止实例。

新增 UI “LEARNING PATH” 区块，展示 6 个学习步骤和“基线 → 假设 → 单变量 → 复测 → 决策”调优闭环。

验证：

```bash
PROFILES="jdk-defaults" SCENARIO=young-gc SKIP_BUILD=1 PORT=18150 ./scripts/tuning-lab.sh
# 结果：jdk-defaults / 2G / 1M / gcDelta=2 / Young / maxPause=8.002ms
```

### Step 29：浏览器渲染验证

使用系统 Chrome + Playwright 打开控制台，等待状态接口和轮询完成，检查：

- 页面标题与学习路线标题存在；
- 场景卡片 = 10；
- 学习步骤 = 6；
- 无 JS pageerror；
- 保存整页截图为 `docs/samples/ui-console.png`。

第一次渲染有 1 个 404，定位为浏览器请求 `/favicon.ico`；新增 `favicon.svg` 并在 HTML 中声明后消除。

### Step 30：G1 全流程与内部数据结构文档

新增 `docs/12-g1-end-to-end-internals.md`，重点讲清楚：

- Region、HeapRegion、TAMS、BOT、CSet；
- 初始标记（Concurrent Start）的根扫描路径；
- `G1RootProcessor` 的 Java/VM/CLD/CodeCache 根；
- root region 的登记与 `[TAMS, top]` 并发扫描；
- mark bitmap 的位粒度与 `par_mark` CAS；
- 本地 task queue、全局 mark stack、工作窃取与终止协议；
- SATB queue/buffer、pre-write barrier 和 Remark 收尾；
- Remark 的引用处理、类卸载、Region 存活统计、TARS；
- Cleanup、Mixed CSet、Full GC；
- 每个阶段的数据结构总表与 OpenJDK 21 源码阅读顺序。

> 2026-10-21 的增补只涉及注释、文档、UI 和实验脚本，不改变 GC 场景算法；因此未重新跑完整 2G/4G 全量实验，最终仍执行编译/测试回归。

## 10. UI 验证摘要

- 视口：1440 × 1200（整页截图）
- 场景卡片：10
- 学习步骤：6
- 页面错误：无（favicon 修复后）
- 截图：[samples/ui-console.png](samples/ui-console.png)

## 11. 相关文档

- [08-reproduction-runbook.md](08-reproduction-runbook.md)：从零复现步骤
- [09-gc-log-file-analysis.md](09-gc-log-file-analysis.md)：每个日志样例的分析记录
- [10-g1-learning-path.md](10-g1-learning-path.md)：系统学习路线
- [11-g1-tuning-workshop.md](11-g1-tuning-workshop.md)：调优 A/B 工作坊
- [12-g1-end-to-end-internals.md](12-g1-end-to-end-internals.md)：全流程与内部数据结构
- [03-gc-log-analysis.md](03-gc-log-analysis.md)：GC 日志字段与教学案例
- [00-coverage-matrix.md](00-coverage-matrix.md)：知识覆盖矩阵

## 2026-10-24 · v2 全面重构（正确性 + 参数模型 + 解析器 v2 + 控制台升级 + 结束清理）

### 正确性修复（M1）

- AllocationStormScenario 计数器竞态：`allocated.get()+set()` 在 4 线程下互相覆盖（少报分配量、上限可被绕过）→ 改为 CAS 循环，"检查上限 + 累加"原子完成。
- JmxGcFallback 堆语义：`used()` 曾把 Metaspace 等 non-heap 池算进 heapBefore/After → 改为只累加 `MemoryType.HEAP` 池，与日志路径可比。
- GcLogWatcher：半行缓冲改为字节级（按 `\n` 字节切分、只解码完整行），UTF-8 多字节字符跨读取边界不再损坏；新增 `pollUntilQuiet`（读到追平为止）替换 ScenarioRunner 固定 sleep 700ms 的碰运气等待；新增 `linesRead/pauseLinesSkipped` 计数，日志格式漂移在状态页报警。
- HumongousScenario `objectMb` 与 count 一致地 clamp；新增堆水位检查 `checkHeadroom`（当前已用 + 存活峰值 > 92% 堆即 400）。
- run.sh：JDK 21 预检（major < 21 友好报错）、`EXTRA_JAVA_OPTS` eval 数组解析（带引号参数不再碎裂）。
- 测试：新增 GcLogWatcherTest（9 例，含 UTF-8 切断/轮转/截断/跳过行计数）与 JmxGcFallbackTest；开发过程中 GrowableBuffer 的"长度当下标"传参 bug 就是被这组测试揪出来的。

### 参数模型 + 进度/取消/预算（M2）

- `ScenarioRequest`（9 个通用字段跨场景复用，mixed-gc 的 count 竟表示"追加压力 MB"）→ `ScenarioParams`：请求体是按场景命名的 map，Runner 按 ParamSpec min/max 严格校验，未知/越界 400 带教学提示；参数改为真实命名（promotion 的 retainedMb/churnMb、mixed-gc 的 deadMb/liveMb/finalChurnMb）。前端卡片本就按 ParamSpec 动态渲染，零改动。
- `ScenarioContext`：墙钟预算（`demo.max-scenario-wall-ms` 默认 90s，所有 churn/awaitType/sleep 都夹到剩余预算内——README 的"时长上限"声明从此为真）、协作式取消（`POST /api/scenarios/current/cancel`，检查点抛出 → cancelled=true 的部分结果）、阶段进度（`/api/status.scenarioProgress`）。
- `ScenarioHistory`（demo.history-size 默认 20）→ `GET /api/scenarios/history`。
- JvmInfoService 静态部分缓存：一次 /api/scenarios 原先触发 300+ 次 getVMOption MXBean 调用。

### 解析器 v2（M3）

以项目真实 JDK 21 日志（logs/gc-2026-10-22_*.log）为夹具，不发明格式：

- info 级新解析：Region 转移行四族（`Eden regions: 75->0(199)` 等）、并发周期（Concurrent Mark Cycle/Mark From Roots/Scan Root Regions/Clear Claimed Marks/Cleanup for Next Mark，独立成 type="Concurrent" 事件）、暂停阶段分解（Pre/Evacuate/Post Evacuate Collection Set、Merge Heap Roots、Full GC Phase 1-4）、gc,cpu（User/Sys/Real）、gc,metaspace。
- verbose 级新解析（进事件 details）：自适应 IHOP、gc,ergo 决策（"do not continue mixed GCs"——"为什么 Mixed 停了"的一手答案）、tenuring 阈值、引用处理 Discovered/Dropped/Processed、Humongous Reclaimed、PLAB、Merged/Skipped/Dirty Cards、refinement 统计。
- `GcCycleAggregator` 按 GC(n) 聚合：汇总行建 pending、补充行附加、下一个 GC/轮转/两轮安静时定稿；并发阶段行独立放行。
- 侧日志证据闭环（`SideLogWatchers`）：string-dedup 场景响应直接返回 new/deduped/平均去重率；class-unload 场景返回实际卸载数——不再让学生自己去翻文件。
- 已确认 JDK 21 不存在的行名（不要去匹配）：Concurrent Complete Cleanup、Concurrent Undo Cycle、SATB Filtering、字面 Soft/Phantom Processing、Free CSet、Per GC Thread。
- generate-sample-logs.sh 裁剪正则补 Region/gc,cpu/Metaspace 行（旧样例低估了 info 级产出）。

### 控制台升级（M4）

- 暂停时间线 SVG（零依赖手绘）：等距铺开最近事件、按类型着色、并发阶段底部带、悬浮 tooltip（含 Region/阶段分解/CPU/Metaspace/details）、分类型统计条（次数/平均/最大）。
- 运行历史面板：最近 20 次运行 + 勾选两条做 A/B 对比（指标并排、差异高亮）。
- 进度条 + 取消按钮；取消结果标记"已取消 · 部分证据"。
- JFR 面板：`/api/jfr/start|stop|status`（FlightRecorderMXBean，profile 配置，时长上限 demo.max-jfr-seconds=300）+ `/api/logs/download`（防穿越守卫扩展 .jfr）。
- refreshLogs 只在文件列表变化时重建 select，不再打断展开的下拉框。
- 踩坑记录：`FlightRecorderMXBean` 的包是 `jdk.management.jfr` 而不是 `com.sun.management`；其 stopRecording/getRecordings 在 JDK 21 不抛 IOException 而 closeRecording/setRecordingOptions 抛——编译器逐个纠正。

### 结束清理脚本（M5，用户新增需求）

- `scripts/cleanup.sh`：白名单 glob 清除 logs/ 全部运行残留（gc/stringdedup/safepoint/class-unload/generate-console 日志、scenario-*.json、hs_err、heapdump、.jfr）、reports/tuning-*（--keep-reports 保留）、递归 .DS_Store、/tmp/g1-class-unload-*；不动 docs/samples 与源码，target/ 仅 --all；--dry-run 先看清单。幂等。
- run.sh 启动即提示；generate-sample-logs.sh 支持 CLEANUP=1 自动收尾；tuning-lab.sh 结束提示 --keep-reports（报告是交付物不自动删）。
- ClassUnloadScenario 的 URLClassLoader 改 try-with-resources；JfrService @PreDestroy 兜底停止，不留悬挂录制。

### 测试

61 个测试（原 26 → 61）：GcLogWatcherTest 10、GcDetailParsersTest 16（全部引用真实日志行）、GcCycleAggregatorTest 5、ScenarioContextTest 4、ScenarioParamsValidationTest 5、ApiIntegrationTest 9（新端点与参数校验）等。

### 踩坑补记：string-dedup 一手证据的异步落盘（2026-10-24 深挖）

现象：手动首跑 string-dedup 时 0.4s 内即可在响应中拿到去重统计；但前置 5 个场景后再跑，8s 等待窗口内永远拿不到，尽管统计行就在场景窗口内"发生"。

定位过程（探针日志逐步收窄）：
1. SideLogWatchers 的 attach/增量读逻辑正常（attach-at-EOF 后每 500ms 消费新行，前置场景期间一直在正常工作）；
2. 时间戳匹配与水位匹配双策略均未命中，排除时钟一致性问题；
3. 探针证实：等待窗口内 `Files.size` 一直等于 offset——本批去重统计行**根本还没写进文件**；
4. 铁证：本批行的时间戳是场景结束的那一瞬间（13.004s），但实际落盘比场景结束晚 **40 秒以上**（JDK StringDedup 线程的异步写出节奏，与日志时间戳解耦）。

结论与决策：这是 JVM 内部行为，外部无法催动（用小口 churn 催 GC 也无效）。最终策略：
- 场景保留 8s 有界等待（正常环境 <0.4s 命中）；
- 拿不到时降级为明确提示："本轮统计行尚未落盘（去重线程异步写出，实测可滞后数十秒），稍后在 /api/status 的 sideLogs.stringDedup 或日志面板中查看"；
- scheduler 持续消费侧日志，统计最终一定会出现在控制台——教学价值不损，只是不在 run 响应里。

顺带的通用收获：JFR MXBean 的录制选项名是 `destination`（不是 jcmd 的 `filename`），启动时直接 500 报错才暴露。
