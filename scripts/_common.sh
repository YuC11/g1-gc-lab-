#!/usr/bin/env bash
#
# G1 GC Lab 脚本共享函数库。被 run.sh / generate-sample-logs.sh / tuning-lab.sh
# source，只定义函数、不做任何副作用。调用方需已 set -euo pipefail。
#
# 提供：
#   g1_select_maven     打印可用的 Maven 命令（MAVEN_CMD > ./mvnw > mvn）
#   g1_wait_ready       等待 /actuator/health 就绪（端口超时进程三重防护）
#   g1_stop_app         TERM → 宽限 → KILL → wait 的优雅停止
#

# Maven 命令选择：显式 MAVEN_CMD 优先，其次项目 wrapper，最后系统 mvn。
g1_select_maven() {
  if [[ -n "${MAVEN_CMD:-}" ]]; then
    echo "$MAVEN_CMD"
  elif [[ -x "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/mvnw" ]]; then
    echo "./mvnw"
  else
    echo "mvn"
  fi
}

# 等待应用就绪。用法: g1_wait_ready <port> [timeout_seconds] [pid]
#   pid 可选：进程先死则立即失败（区别于网络慢）。
g1_wait_ready() {
  local port="$1" timeout="${2:-60}" pid="${3:-}"
  local i
  for i in $(seq 1 "$timeout"); do
    if curl -fsS "http://127.0.0.1:${port}/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then
      echo "应用进程 (pid=$pid) 在就绪前退出。" >&2
      return 1
    fi
    sleep 1
  done
  echo "等待 /actuator/health 就绪超时（${timeout}s, port=$port）。" >&2
  return 1
}

# 优雅停止应用。用法: g1_stop_app <pid> [grace_seconds]
g1_stop_app() {
  local pid="$1" grace="${2:-20}" _
  [[ -n "$pid" ]] || return 0
  kill -TERM "$pid" 2>/dev/null || return 0
  for _ in $(seq 1 "$grace"); do
    kill -0 "$pid" 2>/dev/null || { wait "$pid" 2>/dev/null || true; return 0; }
    sleep 1
  done
  kill -KILL "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  return 0
}
