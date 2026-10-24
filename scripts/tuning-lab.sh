#!/usr/bin/env bash
#
# G1 tuning A/B harness.
#
# It starts one JVM per profile, runs the same scenario, stores raw evidence and
# stops the JVM. Comparisons must use the same scenario/body/parameters.
#
# Usage:
#   ./scripts/tuning-lab.sh [scenario]
#   SCENARIO=young-gc PROFILES="default low-latency" ./scripts/tuning-lab.sh
#
# Environment:
#   PROFILES   space-separated run.sh profiles (default: jdk-defaults default low-latency throughput)
#   PORT       local port (default 18085)
#   SKIP_BUILD 1 to reuse target jar
#   MAVEN_CMD  mvn or ./mvnw
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 汇总 scenario 响应依赖 jq；analyze-gc-log.sh 刻意只用 awk，这里不同。
if ! command -v jq >/dev/null 2>&1; then
  echo "tuning-lab.sh 需要 jq（解析 scenario JSON 响应）。安装示例：brew install jq" >&2
  exit 1
fi

SCENARIO="${1:-${SCENARIO:-allocation-storm}}"
PROFILES="${PROFILES:-jdk-defaults default low-latency throughput}"
PORT="${PORT:-18085}"
SKIP_BUILD="${SKIP_BUILD:-0}"
OUT="$ROOT/reports/tuning-$(date +%Y%m%d-%H%M%S)"
APP_PID=""

if [[ -z "${MAVEN_CMD:-}" ]]; then
  if [[ -x "./mvnw" ]]; then MAVEN_CMD="./mvnw"; else MAVEN_CMD="mvn"; fi
fi

cleanup() {
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill -TERM "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    kill -KILL "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

if [[ "$SKIP_BUILD" != "1" ]]; then
  echo "Building once with $MAVEN_CMD ..."
  "$MAVEN_CMD" -q -DskipTests package
fi
if ! ls "$ROOT"/target/g1-gc-lab-*.jar >/dev/null 2>&1; then
  echo "No target jar. Run with SKIP_BUILD=0 first." >&2
  exit 1
fi

mkdir -p "$OUT"
SUMMARY="$OUT/summary.tsv"
printf 'profile\theap_gb\tregion_mb\tgc_delta\tobserved\tmax_pause_ms\n' > "$SUMMARY"

wait_ready() {
  for _ in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then return 0; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then return 1; fi
    sleep 1
  done
  return 1
}

scenario_body() {
  case "$SCENARIO" in
    allocation-storm) echo '{"threads":4,"durationMs":8000,"chunkKb":256}' ;;
    *) echo '{}' ;;
  esac
}

run_profile() {
  local profile="$1"
  local dir="$OUT/$profile"
  mkdir -p "$dir/logs"
  echo
  echo "=== profile: $profile ==="
  PROFILE="$profile" SERVER_PORT="$PORT" LOG_DIR="$dir/logs" SKIP_BUILD=1 \
    ./scripts/run.sh > "$dir/app.log" 2>&1 &
  APP_PID=$!
  if ! wait_ready; then
    echo "Profile $profile failed to start. See $dir/app.log" >&2
    tail -40 "$dir/app.log" >&2 || true
    return 1
  fi

  curl -fsS "http://127.0.0.1:$PORT/api/config" > "$dir/config.json"
  curl -fsS --max-time 240 \
    -H 'Content-Type: application/json' \
    -X POST "http://127.0.0.1:$PORT/api/scenarios/$SCENARIO/run" \
    -d "$(scenario_body)" > "$dir/result.json"

  local log_file heap_mb region_mb gc_delta observed max_pause
  log_file="$(ls -1t "$dir/logs"/gc-*.log 2>/dev/null | head -1 || true)"
  if [[ -z "$log_file" ]]; then
    echo "No GC log for $profile" >&2
    return 1
  fi
  cp "$log_file" "$dir/gc.log"
  ./scripts/analyze-gc-log.sh "$dir/gc.log" > "$dir/analysis.txt" 2>&1 || true

  heap_mb="$(jq -r '.maxHeapBytes / 1073741824' "$dir/config.json")"
  region_mb="$(jq -r '.regionSizeBytes / 1048576' "$dir/config.json")"
  gc_delta="$(jq -r '.gcDelta' "$dir/result.json")"
  observed="$(jq -r '.observedEventTypes | join(",")' "$dir/result.json")"
  max_pause="$(awk '/Longest pause:/ {print $3}' "$dir/analysis.txt" | head -1)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$profile" "$heap_mb" "$region_mb" "$gc_delta" "$observed" "${max_pause:-n/a}" >> "$SUMMARY"

  kill -TERM "$APP_PID" 2>/dev/null || true
  for _ in $(seq 1 20); do
    kill -0 "$APP_PID" 2>/dev/null || break
    sleep 1
  done
  kill -KILL "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
  echo "  saved: $dir"
}

for profile in $PROFILES; do
  run_profile "$profile"
done

echo
echo "Tuning report: $OUT"
column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
echo
echo "Compare only runs with the same scenario body and workload."
echo "Deep dive: $OUT/<profile>/analysis.txt"

echo
echo "报告输出在 reports/ 下；如需清除实验残留（保留报告用 --keep-reports）："
echo "  ./scripts/cleanup.sh --keep-reports"
