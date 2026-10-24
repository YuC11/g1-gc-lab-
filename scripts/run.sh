#!/usr/bin/env bash
#
# G1 GC Lab launcher.
#
# GC flags are startup-only: they are built here and passed to the JVM, never put
# into application.yml. Profiles intentionally override pause/tenuring/IHOP for
# faster classroom demos; `jdk-defaults` keeps the JDK ergonomics instead.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

HEAP_EXPLICIT="${HEAP:-}"
HEAP="${HEAP:-2g}"
REGION_SIZE="${REGION_SIZE:-auto}"
PROFILE="${PROFILE:-default}"
SERVER_PORT="${SERVER_PORT:-8080}"
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
