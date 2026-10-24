/**
 * G1 GC Lab 控制台（无框架、零 CDN）。
 *
 * 设计约束：
 * - 通过轮询 /api/status、/api/gc/events、/api/logs/tail 更新页面；
 * - 场景必须串行执行，按钮在请求期间统一禁用；运行中显示进度并支持协作式取消；
 * - 所有服务端数据在插入 HTML 前经过 esc()，日志内容使用 textContent；
 * - 事件时间线按 seq 去重并按新到旧展示；暂停时间线为手绘 SVG，同样零依赖；
 * - 运行历史支持勾选两条做单变量 A/B 对比（调优工作坊的核心闭环）；
 * - JFR 录制走 /api/jfr/*，文件经 /api/logs/download 下载。
 */
(() => {
    "use strict";

    /** 前端会话状态；lastSeq 用于 GC 事件增量拉取，allEvents 驱动时间线图。 */
    const state = {
        lastSeq: 0,
        scenarios: [],
        logs: [],
        logsKey: "",
        selectedLog: null,
        history: [],
        allEvents: [],
        busy: false,
        toastTimer: null,
        firstStatus: true
    };

    const $ = (id) => document.getElementById(id);

    const FLAGS = [
        "UseG1GC", "G1HeapRegionSize", "MaxGCPauseMillis", "InitiatingHeapOccupancyPercent",
        "G1UseAdaptiveIHOP", "G1NewSizePercent", "G1MaxNewSizePercent",
        "G1MixedGCLiveThresholdPercent", "G1HeapWastePercent", "G1MixedGCCountTarget",
        "G1OldCSetRegionThresholdPercent", "G1ReservePercent", "ParallelGCThreads",
        "ConcGCThreads", "G1ConcRefinementThreads", "UseStringDeduplication",
        "ClassUnloadingWithConcurrentMark", "G1PeriodicGCInterval", "MaxTenuringThreshold"
    ];

    /** 图表/表格共用的类型颜色体系（CSS 类名）。 */
    const TYPE_META = [
        {type: "Young", label: "Young", cls: "young"},
        {type: "Mixed", label: "Mixed", cls: "mixed"},
        {type: "Concurrent Start", label: "Concurrent Start", cls: "start"},
        {type: "Prepare Mixed", label: "Prepare Mixed", cls: "start"},
        {type: "Remark", label: "Remark", cls: "meta"},
        {type: "Cleanup", label: "Cleanup", cls: "meta"},
        {type: "Full", label: "Full", cls: "full"},
        {type: "Concurrent", label: "Concurrent（并发阶段）", cls: "concurrent"}
    ];

    /**
     * 统一 JSON 请求：解析 RFC 7807 ProblemDetail，把 detail + hint 合并成用户可读错误。
     */
    async function fetchJson(url, options = {}) {
        const response = await fetch(url, {
            headers: {"Content-Type": "application/json", ...(options.headers || {})},
            ...options
        });
        let payload = null;
        try {
            payload = await response.json();
        } catch (ignored) {
            // Empty/non-JSON error body.
        }
        if (!response.ok) {
            const detail = payload && payload.detail ? payload.detail : `HTTP ${response.status}`;
            const hint = payload && payload.hint ? ` ${payload.hint}` : "";
            throw new Error(detail + hint);
        }
        return payload;
    }

    /** 转义插入 innerHTML 的动态文本，避免日志 cause/文件名等形成 XSS。 */
    function esc(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }

    function formatBytes(bytes, digits = 1) {
        const value = Number(bytes) || 0;
        if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(2)} GB`;
        if (value >= 1024 ** 2) return `${(value / 1024 ** 2).toFixed(digits)} MB`;
        if (value >= 1024) return `${(value / 1024).toFixed(digits)} KB`;
        return `${value} B`;
    }

    function formatMs(ms) {
        const value = Number(ms) || 0;
        if (value >= 1000) return `${(value / 1000).toFixed(2)} s`;
        return `${value.toFixed(1)} ms`;
    }

    function formatTime(iso) {
        if (!iso) return "--";
        const date = new Date(iso);
        if (Number.isNaN(date.getTime())) return "--";
        return date.toLocaleTimeString("zh-CN", {hour12: false}) + "." +
            String(date.getMilliseconds()).padStart(3, "0");
    }

    /** 把 G1 pause 类型映射到 CSS badge/bar 颜色。 */
    function typeClass(type) {
        const meta = TYPE_META.find(m => m.type === type);
        return meta ? `type-${meta.cls}` : "type-young";
    }

    function flagDisplay(name, value) {
        if (value === "n/a" || value == null) return value;
        if (name === "G1HeapRegionSize") {
            const number = Number(value);
            return Number.isFinite(number) && number > 0 ? formatBytes(number, 0) : value;
        }
        if (name.endsWith("Percent")) return `${value}%`;
        if (name === "MaxGCPauseMillis") return `${value} ms`;
        return value;
    }

    function setText(id, text) {
        const element = $(id);
        if (element) element.textContent = text;
    }

    function showToast(message, isError = false) {
        const toast = $("toast");
        toast.textContent = message;
        toast.classList.toggle("error", isError);
        toast.classList.add("visible");
        clearTimeout(state.toastTimer);
        state.toastTimer = setTimeout(() => toast.classList.remove("visible"), 4500);
    }

    // ---------- 状态 / 参数 / 堆 ----------

    function renderJvm(status) {
        const jvm = status.jvm;
        const heap = status.heap;
        $("jvmSummary").innerHTML = [
            ["JDK", jvm.javaVersion],
            ["VM", jvm.vmName],
            ["PID", jvm.pid],
            ["Max heap", formatBytes(heap.heapMax, 0)],
            ["Region", `${formatBytes(jvm.regionSizeBytes, 0)} × ${jvm.regionCount}`],
            ["H 阈值", formatBytes(jvm.humongousThresholdBytes, 0)],
            ["Pause target", `${jvm.selectedFlags.MaxGCPauseMillis} ms`],
            ["IHOP", `${jvm.selectedFlags.InitiatingHeapOccupancyPercent}%`]
        ].map(([label, value]) => `<span>${esc(label)} <b>${esc(value)}</b></span>`).join("");

        $("flagList").innerHTML = FLAGS
            .filter(name => jvm.selectedFlags[name] !== undefined)
            .map(name => `<span class="flag">${esc(name)} <b>${esc(flagDisplay(name, jvm.selectedFlags[name]))}</b></span>`)
            .join("");

        const percent = Number(heap.heapUsedPercent) || 0;
        $("heapRing").style.setProperty("--used", Math.min(100, percent).toFixed(2));
        setText("heapPercent", `${percent.toFixed(1)}%`);
        setText("heapUsed", formatBytes(heap.heapUsed, 0));
        setText("heapMax", `max ${formatBytes(heap.heapMax, 0)}`);

        const max = Math.max(heap.heapMax, 1);
        setPool("eden", heap.edenUsed, heap.edenCommitted, max);
        setPool("survivor", heap.survivorUsed, heap.survivorCommitted, max);
        setPool("old", heap.oldUsed, heap.oldCommitted, max);
        setPool("meta", heap.metaspaceUsed, heap.metaspaceCommitted, max);
    }

    function setPool(prefix, used, committed, heapMax) {
        const width = Math.min(100, (Number(used) || 0) * 100 / heapMax);
        $(`${prefix}Bar`).style.width = `${width.toFixed(2)}%`;
        setText(`${prefix}Text`, `${formatBytes(used)} / ${formatBytes(committed)}`);
    }

    function renderGc(status) {
        const gc = status.gc;
        setText("gcCount", String(gc.totalCollections));
        setText("gcSplit", `Young ${gc.youngCollections} / Full ${gc.fullCollections}`);
        setText("gcTime", formatMs(gc.totalCollectionTimeMs));
        setText("gcOverhead", `overhead ${Number(gc.gcOverheadPercent).toFixed(2)}%`);
        const last = gc.lastEvent;
        setText("lastPause", last ? formatMs(last.pauseMs) : "--");
        setText("lastType", last ? `${last.type}${last.cause ? " · " + last.cause : ""}` : "等待 GC 事件");
        setText("retained", formatBytes(status.retained.usedBytes));
        const keys = Object.keys(status.retained.entries || {});
        setText("retainedKeys", keys.length ? `${keys.length} 组: ${keys.join(", ")}` : "无强引用实验数据");

        const running = status.scenarioRunning || state.busy;
        const scenarioState = $("scenarioState");
        scenarioState.textContent = running ? "运行中" : "空闲";
        scenarioState.className = `pill ${running ? "" : "pill-muted"}`;

        renderProgress(status.scenarioProgress, running);

        const connection = $("connection");
        connection.textContent = "G1 在线";
        connection.className = "pill";

        const watcher = status.logWatcher;
        const source = $("logSource");
        if (watcher.available) {
            const name = watcher.file ? watcher.file.split("/").pop() : "gc log";
            const skipped = Number(watcher.pauseLinesSkipped) > 0
                ? ` · ⚠ ${watcher.pauseLinesSkipped} 行疑似格式漂移` : "";
            source.textContent = `日志解析: ${name} · ${watcher.parsedEvents} 条${skipped}`;
            source.className = Number(watcher.pauseLinesSkipped) > 0 ? "pill pill-warn" : "pill";
        } else {
            source.textContent = watcher.error ? "JMX fallback（未发现 GC 日志）" : "JMX fallback";
            source.className = "pill pill-warn";
            source.title = watcher.error || "";
        }
        $("eventSource").textContent = watcher.available ? "GC log 实时解析" : "JMX fallback";
    }

    /** 场景进度条 + 取消按钮；空闲时隐藏。 */
    function renderProgress(progress, running) {
        const box = $("scenarioProgress");
        if (!running || !progress) {
            box.classList.add("hidden");
            return;
        }
        box.classList.remove("hidden");
        const percent = Number.isFinite(progress.percent) && progress.percent >= 0 ? progress.percent : null;
        $("progressBar").style.width = percent == null ? "100%" : `${percent}%`;
        $("progressBar").classList.toggle("indeterminate", percent == null);
        const cancelMark = progress.cancelled ? "（已请求取消，等待检查点…）" : "";
        setText("progressText", `${progress.phase || "运行中"}${percent == null ? "" : ` · ${percent}%`}${cancelMark}`);
    }

    // ---------- 事件表 ----------

    /** 把 v2 富事件的阶段/细节拼成 tooltip 文本。 */
    function eventTooltip(event) {
        const parts = [event.rawLine || ""];
        if (event.regionTransitions) {
            const r = event.regionTransitions;
            const regionBits = [];
            if (r.edenBefore >= 0) regionBits.push(`Eden ${r.edenBefore}->${r.edenAfter}(${r.edenTarget})`);
            if (r.survivorBefore >= 0) regionBits.push(`Survivor ${r.survivorBefore}->${r.survivorAfter}(${r.survivorTarget})`);
            if (r.oldBefore >= 0) regionBits.push(`Old ${r.oldBefore}->${r.oldAfter}`);
            if (r.humongousBefore >= 0) regionBits.push(`Humongous ${r.humongousBefore}->${r.humongousAfter}`);
            if (regionBits.length) parts.push("Region: " + regionBits.join(", "));
        }
        if (event.phases && Object.keys(event.phases).length) {
            parts.push("阶段: " + Object.entries(event.phases).map(([k, v]) => `${k} ${v}ms`).join("; "));
        }
        if (event.cpu) {
            parts.push(`CPU: User ${event.cpu.userSeconds}s / Sys ${event.cpu.sysSeconds}s / Real ${event.cpu.realSeconds}s`);
        }
        if (event.metaspace) {
            parts.push(`Metaspace: ${formatBytes(event.metaspace.usedBeforeBytes)} -> ${formatBytes(event.metaspace.usedAfterBytes)}`);
        }
        if (event.details && Object.keys(event.details).length) {
            parts.push("细节: " + Object.entries(event.details).map(([k, v]) => `${k}=${v}`).join("; "));
        }
        return parts.join("\n");
    }

    function eventRow(event) {
        const cause = event.cause ? event.cause : "—";
        const source = event.source === "jmx" ? "JMX" : "log";
        return `<tr title="${esc(eventTooltip(event))}">
            <td>${esc(formatTime(event.timestamp))}</td>
            <td><span class="type ${typeClass(event.type)}">${esc(event.type)}</span></td>
            <td>${esc(cause)} <small>(${esc(source)})</small></td>
            <td>${esc(formatBytes(event.heapBeforeBytes))} → ${esc(formatBytes(event.heapAfterBytes))}</td>
            <td>${esc(formatMs(event.pauseMs))}</td>
        </tr>`;
    }

    /**
     * 合并增量事件：按 seq 去重、插入表格顶部、喂给时间线图，只保留最近 150 行。
     */
    function mergeEvents(events) {
        if (!events || !events.length) return;
        const body = $("eventBody");
        const placeholder = body.querySelector(".empty");
        if (placeholder) placeholder.remove();
        const sorted = [...events].sort((a, b) => b.seq - a.seq);
        const fragment = document.createDocumentFragment();
        for (const event of sorted) {
            if (event.seq <= state.lastSeq) continue;
            const wrapper = document.createElement("tbody");
            wrapper.innerHTML = eventRow(event);
            while (wrapper.firstChild) fragment.appendChild(wrapper.firstChild);
            state.lastSeq = Math.max(state.lastSeq, event.seq);
            state.allEvents.unshift(event);
        }
        body.prepend(fragment);
        while (body.children.length > 150) body.removeChild(body.lastElementChild);
        if (state.allEvents.length > 300) state.allEvents.length = 300;
        renderChart();
    }

    // ---------- 暂停时间线 SVG ----------

    /**
     * 手绘 SVG 时间线：横轴等距铺开最近事件，纵轴为暂停毫秒（自动量程）；
     * 并发阶段事件画在底部专属带。悬停显示完整 tooltip。
     */
    function renderChart() {
        const svg = $("pauseChart");
        if (!svg) return;
        const events = [...state.allEvents].reverse(); // 旧 → 新
        const width = 960;
        const height = 240;
        const axisHeight = 22;
        const concurrentBand = 16;
        const plotHeight = height - axisHeight - concurrentBand;
        const pauses = events.filter(e => e.type !== "Concurrent");
        const concurrents = events.filter(e => e.type === "Concurrent");

        if (!events.length) {
            svg.innerHTML = `<text x="480" y="120" text-anchor="middle" class="chart-empty">等待 GC 事件…</text>`;
            renderChartStats(events);
            renderChartLegend();
            return;
        }

        const maxPause = Math.max(10, ...pauses.map(e => Number(e.pauseMs) || 0));
        const step = width / Math.max(events.length, 1);
        const barWidth = Math.max(2, Math.min(14, step * 0.7));
        const parts = [];

        // 纵轴刻度线（3 条）
        for (const fraction of [0.25, 0.5, 0.75]) {
            const y = (plotHeight * (1 - fraction)).toFixed(1);
            parts.push(`<line x1="0" y1="${y}" x2="${width}" y2="${y}" class="chart-grid"/>`);
            parts.push(`<text x="4" y="${y - 3}" class="chart-axis">${(maxPause * fraction).toFixed(0)}ms</text>`);
        }

        let pauseIndex = 0;
        events.forEach((event, index) => {
            const x = index * step + step / 2;
            if (event.type === "Concurrent") {
                const y = height - axisHeight - concurrentBand + 3;
                parts.push(`<rect x="${(x - barWidth / 2).toFixed(1)}" y="${y}" width="${barWidth.toFixed(1)}" height="10"
                    class="bar-concurrent" data-chart-idx="${index}" rx="1"/>`);
            } else {
                const ms = Number(event.pauseMs) || 0;
                const h = Math.max(2, ms / maxPause * (plotHeight - 8));
                const y = plotHeight - h;
                parts.push(`<rect x="${(x - barWidth / 2).toFixed(1)}" y="${y.toFixed(1)}" width="${barWidth.toFixed(1)}" height="${h.toFixed(1)}"
                    class="pause-bar ${typeClass(event.type)}" data-chart-idx="${index}" rx="2"/>`);
                pauseIndex++;
            }
        });

        // 底部时间范围
        const first = formatTime(events[0].timestamp);
        const last = formatTime(events[events.length - 1].timestamp);
        parts.push(`<line x1="0" y1="${plotHeight}" x2="${width}" y2="${plotHeight}" class="chart-axis-line"/>`);
        parts.push(`<text x="4" y="${height - 6}" class="chart-axis">${esc(first)}</text>`);
        parts.push(`<text x="${width - 4}" y="${height - 6}" text-anchor="end" class="chart-axis">${esc(last)} · ${pauses.length} 次暂停</text>`);
        parts.push(`<text x="4" y="${height - axisHeight - 3}" class="chart-axis">并发阶段带（${concurrents.length}）</text>`);

        svg.innerHTML = parts.join("");
        renderChartStats(events);
        renderChartLegend();
    }

    /** 分类型统计条：次数 / 平均 / 最大。 */
    function renderChartStats(events) {
        const box = $("chartStats");
        const byType = new Map();
        for (const event of events) {
            const list = byType.get(event.type) || [];
            list.push(event);
            byType.set(event.type, list);
        }
        if (!byType.size) {
            box.textContent = "等待事件…";
            return;
        }
        box.innerHTML = TYPE_META
            .filter(meta => byType.has(meta.type))
            .map(meta => {
                const list = byType.get(meta.type);
                const pauses = list.map(e => Number(e.pauseMs) || 0);
                const avg = pauses.reduce((a, b) => a + b, 0) / pauses.length;
                const max = Math.max(...pauses);
                return `<span class="stat-chip ${typeClass(meta.type)}">${esc(meta.type)}
                    <b>${list.length}</b> · avg ${esc(formatMs(avg))} · max ${esc(formatMs(max))}</span>`;
            }).join("");
    }

    function renderChartLegend() {
        const legend = $("chartLegend");
        legend.innerHTML = TYPE_META
            .map(meta => `<span class="legend-item"><i class="${typeClass(meta.type)}"></i>${esc(meta.label)}</span>`)
            .join("");
    }

    /** SVG 悬停 tooltip：事件委托，取 data-chart-idx 对应事件。 */
    function bindChartTooltip() {
        const svg = $("pauseChart");
        const tip = $("chartTip");
        svg.addEventListener("mousemove", event => {
            const target = event.target.closest("[data-chart-idx]");
            if (!target) {
                tip.classList.add("hidden");
                return;
            }
            const index = Number(target.dataset.chartIdx);
            const item = [...state.allEvents].reverse()[index];
            if (!item) return;
            tip.textContent = eventTooltip(item);
            tip.classList.remove("hidden");
            const wrap = svg.parentElement.getBoundingClientRect();
            const x = Math.min(event.clientX - wrap.left + 12, wrap.width - tip.offsetWidth - 8);
            const y = Math.max(event.clientY - wrap.top - tip.offsetHeight - 8, 4);
            tip.style.left = `${x}px`;
            tip.style.top = `${y}px`;
        });
        svg.addEventListener("mouseleave", () => tip.classList.add("hidden"));
    }

    // ---------- 运行历史与 A/B 对比 ----------

    async function refreshHistory() {
        try {
            state.history = await fetchJson("/api/scenarios/history");
            renderHistory();
        } catch (error) {
            console.error(error);
        }
    }

    function renderHistory() {
        const body = $("historyBody");
        if (!state.history.length) {
            body.innerHTML = `<tr><td colspan="8" class="empty">还没有运行记录。</td></tr>`;
            updateCompareButton();
            return;
        }
        body.innerHTML = state.history.map((run, index) => `
            <tr>
                <td><input type="checkbox" data-history="${index}" aria-label="选择对比"></td>
                <td>${esc(formatTime(run.metrics && run.metrics.startedAtIso || ""))}</td>
                <td>${esc(run.title || run.scenario)}</td>
                <td>${run.elapsedMs} ms</td>
                <td>+${run.gcDelta}</td>
                <td>${formatBytes((run.allocatedMb || 0) * 1024 * 1024, 0)}</td>
                <td class="history-events">${esc((run.observedEventTypes || []).join(" / ") || "—")}</td>
                <td>${run.cancelled ? '<span class="badge badge-danger">已取消</span>' : '<span class="badge">完成</span>'}</td>
            </tr>`).join("");
        updateCompareButton();
    }

    function selectedHistoryRuns() {
        return [...document.querySelectorAll("input[data-history]:checked")]
            .map(input => state.history[Number(input.dataset.history)])
            .filter(Boolean);
    }

    function updateCompareButton() {
        const count = document.querySelectorAll("input[data-history]:checked").length;
        const button = $("compareBtn");
        button.disabled = count !== 2;
        button.textContent = `对比选中 (${count}/2)`;
    }

    /** 两条运行的并排对比：核心指标 + metrics 并集，差异高亮。 */
    function renderCompare() {
        const [a, b] = selectedHistoryRuns();
        const box = $("compareBox");
        if (!a || !b) {
            box.classList.add("hidden");
            box.innerHTML = "";
            return;
        }
        const rows = [
            ["场景", a.title || a.scenario, b.title || b.scenario],
            ["耗时", `${a.elapsedMs} ms`, `${b.elapsedMs} ms`],
            ["GC 次数增量", `+${a.gcDelta}`, `+${b.gcDelta}`],
            ["分配量", formatBytes((a.allocatedMb || 0) * 1024 * 1024, 0), formatBytes((b.allocatedMb || 0) * 1024 * 1024, 0)],
            ["Retained", formatBytes((a.retainedMb || 0) * 1024 * 1024, 0), formatBytes((b.retainedMb || 0) * 1024 * 1024, 0)],
            ["观测事件", (a.observedEventTypes || []).join(" / ") || "—", (b.observedEventTypes || []).join(" / ") || "—"],
            ["状态", a.cancelled ? "已取消" : "完成", b.cancelled ? "已取消" : "完成"]
        ];
        const metricKeys = [...new Set([...Object.keys(a.metrics || {}), ...Object.keys(b.metrics || {})])];
        for (const key of metricKeys) {
            rows.push([`metrics.${key}`, String((a.metrics || {})[key] ?? "—"), String((b.metrics || {})[key] ?? "—")]);
        }
        box.innerHTML = `
            <div class="compare-head">A/B 对比：勾选两条记录的差异高亮显示。单变量实验要求 workload 相同、只改一个参数。</div>
            <div class="table-scroll"><table class="compare-table">
                <thead><tr><th>指标</th><th>A</th><th>B</th></tr></thead>
                <tbody>${rows.map(([label, va, vb]) => {
                    const diff = String(va) !== String(vb) ? " diff" : "";
                    return `<tr><td>${esc(label)}</td><td class="${diff.trim()}">${esc(va)}</td><td class="${diff.trim()}">${esc(vb)}</td></tr>`;
                }).join("")}</tbody>
            </table></div>`;
        box.classList.remove("hidden");
    }

    // ---------- 场景执行 ----------

    async function refreshStatus() {
        try {
            const status = await fetchJson("/api/status");
            renderJvm(status);
            renderGc(status);
            if (state.firstStatus) {
                if (status.events && status.events.length) {
                    mergeEvents(status.events);
                }
                state.firstStatus = false;
            }
        } catch (error) {
            const connection = $("connection");
            connection.textContent = "连接失败";
            connection.className = "pill pill-danger";
            $("eventSource").textContent = "API 不可达";
            console.error(error);
        }
    }

    async function refreshEvents() {
        try {
            const events = await fetchJson(`/api/gc/events?afterSeq=${state.lastSeq}&limit=100`);
            mergeEvents(events);
        } catch (error) {
            console.error(error);
        }
    }

    function scenarioCard(info) {
        const params = (info.params || []).map(param => `
            <label class="param">${esc(param.label)} (${esc(param.unit)})
                <input type="number" data-param="${esc(param.name)}"
                       value="${param.defaultValue}" min="${param.min}" max="${param.max}" step="1">
            </label>`).join("");
        const riskClass = info.triggersFullGc ? "badge-danger" : "";
        return `<article class="scenario-card" data-scenario="${esc(info.id)}">
            <div class="scenario-top">
                <h3>${esc(info.title)}</h3>
                <div class="badges">
                    <span class="badge">${esc(info.risk)}</span>
                    ${info.triggersFullGc ? '<span class="badge badge-danger">Full GC</span>' : ""}
                </div>
            </div>
            <p class="scenario-goal">${esc(info.goal)}</p>
            <div class="scenario-logs">${(info.expectedLog || []).map(line => `<code>${esc(line)}</code>`).join("")}</div>
            <div class="param-grid">${params || '<span class="scenario-goal">无参数，直接执行。</span>'}</div>
            <div class="scenario-actions">
                <button type="button" class="button ${info.triggersFullGc ? "button-danger" : ""}" data-run="${esc(info.id)}">运行</button>
            </div>
            <div class="scenario-result" data-result="${esc(info.id)}"></div>
        </article>`;
    }

    async function loadScenarios() {
        try {
            state.scenarios = await fetchJson("/api/scenarios");
            $("scenarioGrid").innerHTML = state.scenarios.map(scenarioCard).join("");
        } catch (error) {
            $("scenarioGrid").innerHTML = `<div class="empty">场景加载失败：${esc(error.message)}</div>`;
            showToast(`场景加载失败：${error.message}`, true);
        }
    }

    function readParams(card) {
        const body = {};
        card.querySelectorAll("input[data-param]").forEach(input => {
            const value = Number(input.value);
            if (Number.isFinite(value) && value > 0) body[input.dataset.param] = Math.trunc(value);
        });
        return body;
    }

    function setBusy(busy) {
        state.busy = busy;
        document.querySelectorAll("[data-run]").forEach(button => {
            button.disabled = busy;
        });
        $("resetBtn").disabled = busy;
        $("cancelBtn").disabled = !busy;
        const scenarioState = $("scenarioState");
        scenarioState.textContent = busy ? "运行中" : "空闲";
        scenarioState.className = `pill ${busy ? "" : "pill-muted"}`;
        if (!busy) {
            $("scenarioProgress").classList.add("hidden");
        }
    }

    async function runScenario(id, card, button) {
        if (state.busy) {
            showToast("已有场景正在执行，请等待结束。", true);
            return;
        }
        const body = readParams(card);
        const resultBox = card.querySelector(`[data-result="${CSS.escape(id)}"]`);
        setBusy(true);
        card.classList.add("running");
        button.textContent = "运行中…";
        resultBox.classList.remove("visible");
        resultBox.innerHTML = "";
        try {
            const result = await fetchJson(`/api/scenarios/${encodeURIComponent(id)}/run`, {
                method: "POST",
                body: JSON.stringify(body)
            });
            const eventTypes = (result.observedEventTypes || []).join(" / ") || "暂无新事件";
            const cancelledMark = result.cancelled ? '<strong class="error">已取消 · 部分证据</strong><br>' : "";
            resultBox.innerHTML = `${cancelledMark}<strong>${esc(result.title)}</strong> · ${result.elapsedMs} ms · GC +${result.gcDelta}
                <div class="result-events">事件: ${esc(eventTypes)}</div>
                <ul>${result.observations.map(item => `<li>${esc(item)}</li>`).join("")}</ul>
                <div class="hint">${esc(result.hint)}</div>`;
            resultBox.classList.add("visible");
            showToast(result.cancelled ? `${result.title} 已取消` : `${result.title} 完成，GC +${result.gcDelta}`);
            await Promise.allSettled([refreshStatus(), refreshEvents(), refreshLogs(), refreshHistory()]);
        } catch (error) {
            resultBox.innerHTML = `<strong class="error">执行失败</strong><br>${esc(error.message)}`;
            resultBox.classList.add("visible");
            showToast(`执行失败：${error.message}`, true);
        } finally {
            setBusy(false);
            card.classList.remove("running");
            button.textContent = "运行";
        }
    }

    async function resetDemo() {
        if (state.busy) {
            showToast("场景运行中，暂不能复位。", true);
            return;
        }
        setBusy(true);
        try {
            const result = await fetchJson("/api/scenarios/reset", {method: "POST"});
            showToast(`已复位，保留引用 ${formatBytes(result.retainedMb * 1024 * 1024)}`);
            await Promise.allSettled([refreshStatus(), refreshEvents(), refreshLogs(), refreshHistory()]);
        } catch (error) {
            showToast(`复位失败：${error.message}`, true);
        } finally {
            setBusy(false);
        }
    }

    async function cancelScenario() {
        try {
            const result = await fetchJson("/api/scenarios/current/cancel", {method: "POST"});
            showToast(result.accepted ? "已请求取消：场景将在下一个检查点中止。" : "当前没有运行中的场景。", !result.accepted);
        } catch (error) {
            showToast(`取消失败：${error.message}`, true);
        }
    }

    // ---------- 日志 ----------

    async function refreshLogs() {
        try {
            state.logs = await fetchJson("/api/logs");
            const select = $("logSelect");
            if (!state.logs.length) {
                select.innerHTML = '<option value="">暂无日志</option>';
                state.selectedLog = null;
                setText("logTail", "没有找到 .log 文件。请使用 scripts/run.sh 启动，让 JVM 将 -Xlog 输出到 logs/。");
                return;
            }
            if (!state.selectedLog || !state.logs.some(log => log.name === state.selectedLog)) {
                const active = state.logs.find(log => log.name.startsWith("gc-"))
                    || state.logs.find(log => log.active)
                    || state.logs[0];
                state.selectedLog = active.name;
            }
            // 仅在列表（名 + 大小）变化时重建 <select>，避免打断展开的下拉框
            const key = state.logs.map(log => `${log.name}:${log.size}`).join("|");
            if (key !== state.logsKey) {
                state.logsKey = key;
                select.innerHTML = state.logs.map(log =>
                    `<option value="${esc(log.name)}" ${log.name === state.selectedLog ? "selected" : ""}>${esc(log.name)} (${formatBytes(log.size)})</option>`
                ).join("");
            } else {
                const option = select.querySelector(`option[value="${CSS.escape(state.selectedLog)}"]`);
                if (option) option.selected = true;
            }
            await refreshTail();
        } catch (error) {
            setText("logTail", `日志列表读取失败：${error.message}`);
        }
    }

    async function refreshTail() {
        if (!state.selectedLog) return;
        try {
            const tail = await fetchJson(`/api/logs/tail?name=${encodeURIComponent(state.selectedLog)}&lines=200`);
            const pre = $("logTail");
            const atBottom = pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 24;
            pre.textContent = tail.content || "日志文件为空。";
            if (atBottom) pre.scrollTop = pre.scrollHeight;
        } catch (error) {
            setText("logTail", `日志读取失败：${error.message}`);
        }
    }

    // ---------- JFR ----------

    async function refreshJfr() {
        try {
            const status = await fetchJson("/api/jfr/status");
            renderJfr(status);
        } catch (error) {
            console.error(error);
        }
    }

    function renderJfr(status) {
        const pill = $("jfrState");
        const startBtn = $("jfrStartBtn");
        const stopBtn = $("jfrStopBtn");
        const download = $("jfrDownload");
        if (!status.available) {
            pill.textContent = "不可用";
            pill.className = "pill pill-warn";
            startBtn.disabled = true;
            stopBtn.disabled = true;
            return;
        }
        if (status.active) {
            pill.textContent = `录制中 · 剩余约 ${status.remainingSeconds}s`;
            pill.className = "pill";
            startBtn.disabled = true;
            stopBtn.disabled = false;
        } else {
            pill.textContent = "未录制";
            pill.className = "pill pill-muted";
            startBtn.disabled = false;
            stopBtn.disabled = true;
        }
        if (status.lastFile) {
            download.classList.remove("hidden");
            download.href = `/api/logs/download?name=${encodeURIComponent(status.lastFile)}`;
            download.textContent = `下载 ${status.lastFile}${status.lastFileSizeHuman ? " (" + status.lastFileSizeHuman + ")" : ""}`;
        } else {
            download.classList.add("hidden");
        }
    }

    async function startJfr() {
        const seconds = Math.trunc(Number($("jfrSeconds").value) || 60);
        try {
            const status = await fetchJson("/api/jfr/start", {
                method: "POST",
                body: JSON.stringify({seconds})
            });
            renderJfr(status);
            showToast(`JFR 录制已开始：${status.plannedSeconds}s 后自动落盘到 logs/。`);
        } catch (error) {
            showToast(`JFR 启动失败：${error.message}`, true);
        }
    }

    async function stopJfr() {
        try {
            const status = await fetchJson("/api/jfr/stop", {method: "POST"});
            renderJfr(status);
            showToast(status.lastFile ? `已落盘：${status.lastFile}` : "录制已停止。");
        } catch (error) {
            showToast(`JFR 停止失败：${error.message}`, true);
        }
    }

    // ---------- 绑定与初始化 ----------

    function bind() {
        $("scenarioGrid").addEventListener("click", event => {
            const button = event.target.closest("[data-run]");
            if (!button) return;
            const id = button.dataset.run;
            const card = button.closest("[data-scenario]");
            runScenario(id, card, button);
        });
        $("resetBtn").addEventListener("click", resetDemo);
        $("cancelBtn").addEventListener("click", cancelScenario);
        $("logRefresh").addEventListener("click", refreshLogs);
        $("logSelect").addEventListener("change", event => {
            state.selectedLog = event.target.value || null;
            refreshTail();
        });
        $("jfrStartBtn").addEventListener("click", startJfr);
        $("jfrStopBtn").addEventListener("click", stopJfr);
        $("historyBody").addEventListener("change", event => {
            if (event.target.matches("input[data-history]")) {
                const checked = document.querySelectorAll("input[data-history]:checked");
                if (checked.length > 2) {
                    event.target.checked = false;
                    showToast("最多选择两条记录做对比。", true);
                }
                updateCompareButton();
            }
        });
        $("compareBtn").addEventListener("click", renderCompare);
        bindChartTooltip();
        document.addEventListener("visibilitychange", () => {
            if (!document.hidden) {
                refreshStatus();
                refreshEvents();
                refreshLogs();
                refreshHistory();
                refreshJfr();
            }
        });
    }

    async function init() {
        bind();
        renderChart();
        await loadScenarios();
        await Promise.allSettled([refreshStatus(), refreshEvents(), refreshLogs(), refreshHistory(), refreshJfr()]);
        setInterval(() => {
            if (!document.hidden) refreshStatus();
        }, 2000);
        setInterval(() => {
            if (!document.hidden) refreshEvents();
        }, 2000);
        setInterval(() => {
            if (!document.hidden) refreshLogs();
        }, 5000);
        setInterval(() => {
            if (!document.hidden) refreshJfr();
        }, 5000);
    }

    init();
})();
