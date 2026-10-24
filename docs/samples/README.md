# GC 日志样例

这里的 `.log` 文件由 `scripts/generate-sample-logs.sh` 在 **JDK 21** 上实际运行产生，并经过裁剪：

- 保留启动 ergonomics、暂停汇总行、并发周期、Humongous、Full GC 等关键行；
- 2G 文件末尾附加 `class,unload` 与 `stringdedup` 独立日志的实际摘录；
- 完整原始日志保留在项目根目录 `logs/`（已 gitignore）；
- 不同机器、JDK 小版本、堆大小会改变具体数值与时间戳。

| 文件 | 内容 |
|---|---|
| `gc-scenarios-2g.log` | 2G、1M Region，全部场景（verbose），末尾附加 class unload / string dedup 摘要 |
| `gc-scenarios-4g.log` | 4G、2M Region，Young/Humongous/Full/Reset 对照 |
| `scenario-results-2g.json` | 2G 全部场景的实际 API 响应快照 |
| `scenario-results-4g.json` | 4G 冒烟场景的实际 API 响应快照 |
| `class-unload-2g.log` | class unload 日志前 20 行（完整文件在 logs/） |
| `stringdedup-2g.log` | String Deduplication 完整日志（33 行） |
| `safepoint-2g.log` | safepoint 完整日志（80 行） |
| `ui-console.png` | 浏览器控制台整页截图（含学习路线） |

重新生成：

```bash
./scripts/generate-sample-logs.sh
```

分析：

```bash
./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-2g.log
```

逐段讲解见 [../03-gc-log-analysis.md](../03-gc-log-analysis.md) 第一部分；各文件的统计与结论见同文第二部分。
