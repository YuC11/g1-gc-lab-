#!/usr/bin/env bash
#
# End-to-end evidence generator.
# Runs the full 2G suite, snapshots GC/side logs and API responses, then runs a
# 4G smoke suite. Outputs are curated into docs/samples/ and can be committed;
# full raw logs stay under logs/ (gitignored).
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 共享函数库（g1_wait_ready / g1_stop_app），见 scripts/_common.sh。
# shellcheck source=scripts/_common.sh
source "$ROOT/scripts/_common.sh"

PORT="${PORT:-18080}"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR" "$ROOT/docs/samples"

APP_PID=""
cleanup() {
  g1_stop_app "$APP_PID"
  APP_PID=""
}
trap cleanup EXIT INT TERM

wait_ready() {
  g1_wait_ready "$PORT" 90 "$APP_PID"
}

post() {
  local name="$1" body
  if [[ $# -ge 2 ]]; then
    body="$2"
  else
    body='{}'
  fi
  echo "== $name"
  # 场景失败时打印已落盘的响应体再退出，保证可诊断（响应保存在同名 json 里）。
  if ! curl -fsS --max-time 180 \
      -H 'Content-Type: application/json' \
      -X POST "http://127.0.0.1:$PORT/api/scenarios/$name/run" \
      -d "$body" \
      -o "$LOG_DIR/scenario-$name.json" \
      -w "  HTTP %{http_code} in %{time_total}s\n"; then
    echo "场景 $name 执行失败，响应内容：" >&2
    cat "$LOG_DIR/scenario-$name.json" >&2 2>/dev/null || echo "（无响应体）" >&2
    exit 1
  fi
  sleep 1
}

start_app() {
  local heap="$1"
  local console="$2"
  local skip_build="${3:-1}"
  echo "Starting application: HEAP=$heap PROFILE=verbose PORT=$PORT"
  HEAP="$heap" PROFILE=verbose SERVER_PORT="$PORT" LOG_DIR="$LOG_DIR" SKIP_BUILD="$skip_build" \
    ./scripts/run.sh >"$console" 2>&1 &
  APP_PID=$!
  if ! wait_ready; then
    echo "Health check failed. Console output:" >&2
    tail -80 "$console" >&2 || true
    exit 1
  fi
  echo "Ready (pid=$APP_PID)"
}

extract_sample() {
  local source="$1"
  local target="$2"
  # 覆盖解析器 v2 在 info 级可消费的全部行族：pause 汇总、Region 转移、并发周期、
  # 阶段分解、gc,cpu 与 Metaspace（旧正则漏掉了后四类，样例低估了 info 级产出）。
  grep -E 'Using G1|Heap Region Size|Heap Max Capacity|Pause (Young|Full|Remark|Cleanup)|Concurrent (Mark|Start|Cleanup|Remark|Scan Root|Clear Claimed)|(Eden|Survivor|Old|Humongous) regions|Pre Evacuate Collection Set|Evacuate Collection Set:|Post Evacuate Collection Set|Merge Heap Roots|Phase [1-4]:|User=[0-9.]+s Sys=|Metaspace:|To-space exhausted|Evacuation Failure|Allocation Failure|Concurrent String Deduplication|Unloading class|unloading class|class,unload' "$source" \
    | head -5000 > "$target" || true
  echo "Wrote $(wc -l < "$target" | tr -d ' ') lines to $target"
}

append_side_samples() {
  local target="$1"
  local class_file dedup_file safepoint_file
  class_file="$(find "$LOG_DIR" -maxdepth 1 -name 'class-unload-*.log' -newer "$RUN_MARKER" -print 2>/dev/null | head -1 || true)"
  dedup_file="$(find "$LOG_DIR" -maxdepth 1 -name 'stringdedup-*.log' -newer "$RUN_MARKER" -print 2>/dev/null | head -1 || true)"
  safepoint_file="$(find "$LOG_DIR" -maxdepth 1 -name 'safepoint-*.log' -newer "$RUN_MARKER" -print 2>/dev/null | head -1 || true)"
  {
    echo
    echo "===== class,unload (from $(basename "${class_file:-none}")) ====="
    if [[ -n "$class_file" ]]; then
      grep 'unloading class' "$class_file" | head -8 || true
    fi
    echo
    echo "===== stringdedup (from $(basename "${dedup_file:-none}")) ====="
    if [[ -n "$dedup_file" ]]; then
      grep 'Concurrent String Deduplication' "$dedup_file" | head -8 || true
    fi
  } >> "$target"

  if [[ -n "$class_file" ]]; then
    head -20 "$class_file" > "$ROOT/docs/samples/class-unload-2g.log"
  fi
  if [[ -n "$dedup_file" ]]; then
    cp "$dedup_file" "$ROOT/docs/samples/stringdedup-2g.log"
  fi
  if [[ -n "$safepoint_file" ]]; then
    cp "$safepoint_file" "$ROOT/docs/samples/safepoint-2g.log"
  fi
}

snapshot_results() {
  local label="$1"
  if ! command -v jq >/dev/null 2>&1; then
    echo "提示: 未安装 jq，跳过 scenario-results-$label.json 汇总（可选产物）。"
    return 0
  fi
  local -a scenarios
  if [[ "$label" == "4g" ]]; then
    scenarios=(young-gc humongous full-gc reset)
  else
    scenarios=(young-gc promotion mixed-gc humongous cross-region string-dedup allocation-storm full-gc class-unload reset)
  fi
  local files=()
  for scenario in "${scenarios[@]}"; do
    if [[ -f "$LOG_DIR/scenario-$scenario.json" ]]; then
      files+=("$LOG_DIR/scenario-$scenario.json")
    fi
  done
  if [[ ${#files[@]} -gt 0 ]]; then
    jq -s '.' "${files[@]}" > "$ROOT/docs/samples/scenario-results-$label.json"
  fi
}

RUN_MARKER="$LOG_DIR/.sample-run-marker"
touch "$RUN_MARKER"
start_app 2g "$LOG_DIR/generate-console-2g.log" 0
post young-gc '{}'
post promotion '{}'
post mixed-gc '{}'
post humongous '{}'
post cross-region '{}'
post string-dedup '{}'
post allocation-storm '{"durationMs":6000}'
post full-gc '{}'
post class-unload '{}'
post reset '{}'
sleep 2
g1_stop_app "$APP_PID"; APP_PID=""

LATEST_2G="$(ls -1t "$LOG_DIR"/gc-*.log 2>/dev/null | head -1 || true)"
if [[ -z "$LATEST_2G" ]]; then
  echo "2G 运行结束但未找到 gc-*.log，无法提取样例。" >&2
  exit 1
fi
echo "2G GC log: $LATEST_2G"
extract_sample "$LATEST_2G" "$ROOT/docs/samples/gc-scenarios-2g.log"
append_side_samples "$ROOT/docs/samples/gc-scenarios-2g.log"
snapshot_results 2g

start_app 4g "$LOG_DIR/generate-console-4g.log"
post young-gc '{"totalMb":512}'
post humongous '{}'
post full-gc '{}'
post reset '{}'
sleep 2
g1_stop_app "$APP_PID"; APP_PID=""

LATEST_4G="$(ls -1t "$LOG_DIR"/gc-*.log 2>/dev/null | head -1 || true)"
if [[ -z "$LATEST_4G" ]]; then
  echo "4G 运行结束但未找到 gc-*.log，无法提取样例。" >&2
  exit 1
fi
echo "4G GC log: $LATEST_4G"
extract_sample "$LATEST_4G" "$ROOT/docs/samples/gc-scenarios-4g.log"
snapshot_results 4g

echo
rm -f "$RUN_MARKER"
echo "Done. Curated logs under docs/samples/; raw run residue under $LOG_DIR."
echo "Inspect with: ./scripts/analyze-gc-log.sh docs/samples/gc-scenarios-2g.log"

# 结束清理：样例已提取进 docs/samples/，原始日志/响应属于运行残留。
if [[ "${CLEANUP:-0}" == "1" ]]; then
  echo
  echo "CLEANUP=1：清除本次生成的全部运行残留（docs/samples 保留）..."
  ./scripts/cleanup.sh
else
  echo "提示：会话结束后运行 ./scripts/cleanup.sh 清除 logs/ 等残留。"
fi
