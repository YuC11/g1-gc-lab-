#!/usr/bin/env bash
#
# G1 GC Lab launcher.
#
# GC flags are startup-only: they are built here and passed to the JVM, never put
# into application.yml. Profiles intentionally override pause/tenuring/IHOP for
# faster classroom demos; `jdk-defaults` keeps the JDK ergonomics instead.
#
# Heap can be any JVM size: HEAP=1536m env prefix, --heap 1536m flag, or a
# persistent default in scripts/heap.conf (see usage via -h). The script
# validates the input and previews the expected G1 region ergonomics.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# 可选的命令行参数。与前置环境变量等价且优先级更高：
#   ./scripts/run.sh --heap 1536m --profile low-latency --skip-build
# 也可以沿用环境变量风格：HEAP=1536m PROFILE=low-latency ./scripts/run.sh
# ---------------------------------------------------------------------------
usage() {
  cat <<'USAGE'
用法: ./scripts/run.sh [选项]        （括号内为等价的环境变量写法）

  --heap <大小>     堆大小，任意 JVM 值：512m / 1536m / 3g / 8192m（HEAP=）
  --profile <名>    default | jdk-defaults | low-latency | throughput
                    | humongous | verbose（PROFILE=）
  --region <大小>   强制 G1HeapRegionSize 做对照实验，如 1m / 4m（REGION_SIZE=）
  --port <端口>     服务端口，默认 8080（SERVER_PORT=）
  --skip-build      复用 target/ 已构建的 jar（SKIP_BUILD=1）
  -h, --help        显示本帮助

默认堆优先级：--heap / HEAP 环境变量 > scripts/heap.conf > 2g。
脚本会校验堆输入，并按 堆/2048 向上取 2 的幂（1M~32M）预告 Region 与
Humongous 阈值；实际值以启动日志与 /api/config 为准。
USAGE
}

HEAP_EXPLICIT="${HEAP:-}"
REGION_SIZE="${REGION_SIZE:-auto}"
PROFILE="${PROFILE:-default}"
SERVER_PORT="${SERVER_PORT:-8080}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --heap)
      [[ -n "${2:-}" && "$2" != -* ]] || { echo "错误: --heap 需要一个值（如 1536m、3g）" >&2; exit 1; }
      HEAP="$2"; HEAP_EXPLICIT="$2"; shift 2 ;;
    --profile)
      [[ -n "${2:-}" && "$2" != -* ]] || { echo "错误: --profile 需要一个值" >&2; exit 1; }
      PROFILE="$2"; shift 2 ;;
    --region)
      [[ -n "${2:-}" && "$2" != -* ]] || { echo "错误: --region 需要一个值（如 1m、4m）" >&2; exit 1; }
      REGION_SIZE="$2"; shift 2 ;;
    --port)
      [[ -n "${2:-}" && "$2" != -* ]] || { echo "错误: --port 需要一个端口号" >&2; exit 1; }
      SERVER_PORT="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "未知参数: $1（-h 查看用法）" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# 默认堆：环境变量/参数 > scripts/heap.conf（一行，如 "4g"）> 2g。
# GC 参数只能走 JVM 启动参数，不能进 application.yml；heap.conf 是"固定我常用堆"
# 的唯一持久化入口。
# ---------------------------------------------------------------------------
HEAP_SOURCE="命令行/环境变量"
if [[ -z "${HEAP:-}" ]]; then
  HEAP_CONF_VALUE=""
  if [[ -r "$ROOT/scripts/heap.conf" ]]; then
    HEAP_CONF_VALUE="$(grep -E '^[0-9]+[kKmMgG]?$' "$ROOT/scripts/heap.conf" | head -1 || true)"
  fi
  if [[ -n "$HEAP_CONF_VALUE" ]]; then
    HEAP="$HEAP_CONF_VALUE"
    HEAP_SOURCE="scripts/heap.conf"
  else
    HEAP="2g"
    HEAP_SOURCE="内置默认"
  fi
fi

# ---------------------------------------------------------------------------
# 堆输入校验：语法、下限、与物理内存的关系。拒绝明显错误，风险值给警告。
# ---------------------------------------------------------------------------
heap_to_mb() {
  local num unit
  [[ "$1" =~ ^([0-9]+)([kKmMgG]?)$ ]] || return 1
  num="${BASH_REMATCH[1]}"
  unit="$(printf '%s' "${BASH_REMATCH[2]}" | tr '[:upper:]' '[:lower:]')"
  case "$unit" in
    "") echo $(( (num + 1048575) / 1048576 )) ;;   # 无单位 = 字节
    k)  echo $(( (num + 1023) / 1024 )) ;;
    m)  echo "$num" ;;
    g)  echo $(( num * 1024 )) ;;
  esac
}

physical_mem_bytes() {
  if [[ "$(uname -s)" == "Darwin" ]]; then
    sysctl -n hw.memsize 2>/dev/null || echo 0
  elif [[ -r /proc/meminfo ]]; then
    awk '/^MemTotal/ {print $2 * 1024}' /proc/meminfo
  else
    echo 0
  fi
}

HEAP_MB="$(heap_to_mb "$HEAP")" || {
  echo "错误: HEAP=\"$HEAP\" 不是合法的堆大小。应为数字 + 可选单位 k/m/g，例如 512m、1536m、3g。" >&2
  exit 1
}
if (( HEAP_MB < 512 )); then
  echo "错误: 堆 ${HEAP}（${HEAP_MB}MB）过小，最低 512m；实验场景的默认量级在此堆下无法产生可观察现象。" >&2
  exit 1
fi
if (( HEAP_MB < 1024 )); then
  echo "警告: 堆 ${HEAP_MB}MB 小于 1G，部分场景的默认下限（floor）可能触发安全 guard 的 400 拒绝；可调小场景参数后重试。" >&2
fi
PHYS_BYTES="$(physical_mem_bytes)"
if (( PHYS_BYTES > 0 && HEAP_MB * 1048576 > PHYS_BYTES / 2 )); then
  if (( HEAP_MB * 1048576 > PHYS_BYTES )); then
    echo "警告: 堆 $HEAP 超过本机物理内存 $(awk -v b="$PHYS_BYTES" 'BEGIN{printf "%.0fG", b/1073741824}')，几乎必然导致 swap/启动失败。" >&2
  else
    echo "警告: 堆 $HEAP 超过本机物理内存 $(awk -v b="$PHYS_BYTES" 'BEGIN{printf "%.0fG", b/1073741824}') 的一半，GC 复制/实验峰值可能挤压系统。" >&2
  fi
fi

# ---------------------------------------------------------------------------
# G1 ergonomics 预告：region = 堆/2048 向上取 2 的幂，夹在 [1M, 32M]；
# Humongous 阈值 = region/2。显式 REGION_SIZE 时 ergonomics 被覆盖。
# ---------------------------------------------------------------------------
human_bytes() {
  if (( $1 % 1048576 == 0 )); then echo "$(( $1 / 1048576 ))M"
  else echo "$(( $1 / 1024 ))K"
  fi
}

if [[ "$REGION_SIZE" != "auto" ]]; then
  echo "Region: 已强制 G1HeapRegionSize=$REGION_SIZE（对照实验；以下 ergonomics 预告不适用）"
else
  HEAP_BYTES=$(( HEAP_MB * 1048576 ))
  REGION_BYTES=1048576
  AVG_REGION=$(( HEAP_BYTES / 2048 ))
  while (( REGION_BYTES < AVG_REGION )); do REGION_BYTES=$(( REGION_BYTES * 2 )); done
  (( REGION_BYTES > 33554432 )) && REGION_BYTES=33554432
  echo "堆 ${HEAP}（${HEAP_MB}MB）· 来源：$HEAP_SOURCE"
  echo "预期 G1 ergonomics：Region $(human_bytes "$REGION_BYTES") × $(( HEAP_BYTES / REGION_BYTES ))，Humongous 阈值约 $(human_bytes $(( REGION_BYTES / 2 )))（实际以 /api/config 与启动日志为准）"
fi

LOG_DIR="${LOG_DIR:-$ROOT/logs}"
JAVA_BIN="${JAVA_BIN:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
JAVA_BIN="${JAVA_BIN:-java}"
JAR="$(ls -1 "$ROOT"/target/g1-gc-lab-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"

if [[ ! -x "$JAVA_BIN" ]] && ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
  echo "Java not found: $JAVA_BIN" >&2
  exit 1
fi

# GC 日志格式与 experimental flag 都以 JDK 21 为准；先校验再启动，避免编译期才报错。
java_major() {
  "$1" -version 2>&1 | awk -F'"' '/version "/ {print $2}' | awk -F'[._]' '{print ($1==1)?$2:$1}'
}
require_java21() {
  local bin="$1" label="$2" major
  major="$(java_major "$bin" || true)"
  if [[ -z "$major" || "$major" -lt 21 ]]; then
    echo "需要 JDK 21+（$label），当前:" >&2
    "$bin" -version 2>&1 | head -1 >&2 || true
    exit 1
  fi
}
require_java21 "$JAVA_BIN" "运行应用"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" && "$JAVA_HOME/bin/java" != "$JAVA_BIN" ]]; then
  require_java21 "$JAVA_HOME/bin/java" "JAVA_HOME（Maven 编译）"
fi

mkdir -p "$LOG_DIR"

if [[ "${SKIP_BUILD:-0}" != "1" || -z "${JAR}" ]]; then
  if [[ -n "${MAVEN_CMD:-}" ]]; then
    MVN="$MAVEN_CMD"
  elif [[ -x "./mvnw" ]]; then
    MVN="./mvnw"
  else
    MVN="mvn"
  fi
  MVN_ARGS=(-q -DskipTests package)
  if [[ -n "${MVN_REPO:-}" ]]; then
    MVN_ARGS+=("-Dmaven.repo.local=$MVN_REPO")
  fi
  echo "Building g1-gc-lab ..."
  "$MVN" "${MVN_ARGS[@]}"
  JAR="$(ls -1 "$ROOT"/target/g1-gc-lab-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
fi

if [[ -z "${JAR}" ]]; then
  echo "Could not find target/g1-gc-lab-*.jar" >&2
  exit 1
fi

PAUSE_MS=100
IHOP=30
NEW_MIN=10
NEW_MAX=35
MIXED_COUNT=8
OLD_CSET=10
TENURING=3
STRING_DEDUP=1
GC_LEVEL=info

# Profile-specific overrides. Empty values mean "do not pass this flag", which is
# how jdk-defaults falls back to JDK ergonomics.
case "$PROFILE" in
  default)
    ;;
  jdk-defaults)
    PAUSE_MS=""
    IHOP=""
    NEW_MIN=""
    NEW_MAX=""
    TENURING=""
    STRING_DEDUP=0
    ;;
  low-latency)
    [[ -z "$HEAP_EXPLICIT" ]] && HEAP=4g
    PAUSE_MS=50
    IHOP=25
    NEW_MIN=10
    NEW_MAX=40
    MIXED_COUNT=4
    OLD_CSET=5
    TENURING=3
    ;;
  throughput)
    [[ -z "$HEAP_EXPLICIT" ]] && HEAP=4g
    PAUSE_MS=500
    IHOP=50
    NEW_MIN=5
    NEW_MAX=60
    MIXED_COUNT=16
    OLD_CSET=15
    TENURING=3
    ;;
  humongous)
    [[ "$REGION_SIZE" == "auto" ]] && REGION_SIZE=4m
    IHOP=35
    TENURING=3
    ;;
  verbose)
    GC_LEVEL=debug
    ;;
  *)
    echo "Unknown PROFILE=$PROFILE (default|jdk-defaults|low-latency|throughput|humongous|verbose)" >&2
    exit 1
    ;;
esac

# Common diagnostic logging: GC pauses, class unloading, string dedup and
# safepoints are written to separate files so each one can be analyzed alone.
JVM_ARGS=(
  "-Xms$HEAP"
  "-Xmx$HEAP"
  "-XX:+UseG1GC"
  # Allows runtime inspection (and optional tuning) of G1 experimental flags such as
  # G1NewSizePercent / G1MixedGCLiveThresholdPercent on JDK 21.
  "-XX:+UnlockExperimentalVMOptions"
  "-Xlog:gc*=$GC_LEVEL:file=$LOG_DIR/gc-%t.log:utctime,uptime,level,tags:filecount=10,filesize=20m"
  "-Xlog:class+unload=info:file=$LOG_DIR/class-unload-%t.log:utctime,uptime,level,tags:filecount=5,filesize=10m"
  "-Xlog:stringdedup=info:file=$LOG_DIR/stringdedup-%t.log:utctime,uptime,level,tags:filecount=5,filesize=10m"
  "-Xlog:safepoint=info:file=$LOG_DIR/safepoint-%t.log:utctime,uptime,level,tags:filecount=5,filesize=10m"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=$LOG_DIR"
  "-XX:ErrorFile=$LOG_DIR/hs_err_pid%p.log"
)

if [[ "$REGION_SIZE" != "auto" ]]; then
  JVM_ARGS+=("-XX:G1HeapRegionSize=$REGION_SIZE")
fi
if [[ -n "$PAUSE_MS" ]]; then
  JVM_ARGS+=("-XX:MaxGCPauseMillis=$PAUSE_MS")
fi
if [[ -n "$IHOP" ]]; then
  JVM_ARGS+=("-XX:InitiatingHeapOccupancyPercent=$IHOP")
fi
if [[ -n "$TENURING" ]]; then
  JVM_ARGS+=("-XX:MaxTenuringThreshold=$TENURING")
fi
if [[ "$STRING_DEDUP" == "1" ]]; then
  JVM_ARGS+=("-XX:+UseStringDeduplication" "-XX:StringDeduplicationAgeThreshold=1")
fi

if [[ -n "$NEW_MIN" && -n "$NEW_MAX" ]]; then
  JVM_ARGS+=(
    "-XX:G1NewSizePercent=$NEW_MIN"
    "-XX:G1MaxNewSizePercent=$NEW_MAX"
  )
fi
if [[ "$PROFILE" == "low-latency" || "$PROFILE" == "throughput" ]]; then
  JVM_ARGS+=(
    "-XX:G1MixedGCCountTarget=$MIXED_COUNT"
    "-XX:G1OldCSetRegionThresholdPercent=$OLD_CSET"
  )
fi

if [[ -n "${EXTRA_JAVA_OPTS:-}" ]]; then
  # eval-array：普通数组展开会破坏带引号的参数（如含空格的 -Xlog file 路径），
  # eval 赋值让引号语义生效。变量内容由本机使用者自己提供，不接收外部输入。
  eval "EXTRA_ARRAY=(${EXTRA_JAVA_OPTS})"
  JVM_ARGS+=("${EXTRA_ARRAY[@]}")
fi

# Application config is passed after -jar; GC flags must stay on the JVM command line.
APP_ARGS=(
  "--server.port=$SERVER_PORT"
  "--demo.log-dir=$LOG_DIR"
)

echo "PROFILE=$PROFILE HEAP=$HEAP REGION_SIZE=$REGION_SIZE PORT=$SERVER_PORT LOG_DIR=$LOG_DIR"
echo "提示：实验结束后运行 ./scripts/cleanup.sh 清除残留日志与数据。"
printf 'JVM:'
printf ' %q' "$JAVA_BIN" "${JVM_ARGS[@]}"
printf ' -jar %q' "$JAR"
printf ' %q' "${APP_ARGS[@]}"
printf '\n'

exec "$JAVA_BIN" "${JVM_ARGS[@]}" -jar "$JAR" "${APP_ARGS[@]}"
