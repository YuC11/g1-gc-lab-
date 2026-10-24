#!/usr/bin/env bash
#
# G1 GC Lab 结束清理脚本：每次实验/开发会话结束后，清除所有无关的残留数据与日志文件。
#
# 清理范围（白名单，绝不做无差别递归删除）：
#   logs/       gc-*.log、stringdedup-*.log、safepoint-*.log、class-unload-*.log、
#               generate-console-*.log、scenario-*.json、.sample-run-marker、
#               hs_err_pid*.log、java_pid*.hprof（OOME 堆转储）、*.jfr（JFR 录制）
#   reports/    tuning-*/（A/B 实验输出；--keep-reports 可保留）
#   仓库内      .DS_Store（递归）
#   /tmp        g1-class-unload-*（类卸载场景 best-effort 清理漏掉的临时目录）
#
# 明确不动：docs/samples/（提交样例）、源码、.idea/、target/（除非 --all）。
# 幂等可重复执行；--dry-run 只列清单不删除。
#
# Usage:
#   ./scripts/cleanup.sh [--dry-run] [--all] [--keep-reports]
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY_RUN=0
WIPE_TARGET=0
KEEP_REPORTS=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --all) WIPE_TARGET=1 ;;
    --keep-reports) KEEP_REPORTS=1 ;;
    *) echo "Unknown option: $arg (--dry-run|--all|--keep-reports)" >&2; exit 1 ;;
  esac
done

if [[ "$DRY_RUN" == "1" ]]; then
  echo "== dry-run：以下条目将被删除（不会实际执行）=="
else
  echo "== 开始清理 G1 GC Lab 运行残留 =="
fi
[[ "$WIPE_TARGET" == "1" ]] && echo "（--all：target/ 构建产物一并清除）"
[[ "$KEEP_REPORTS" == "1" ]] && echo "（--keep-reports：保留 reports/tuning-* 实验报告）"
echo

declare -i total_count=0
declare -i total_bytes=0

# 逐条删除：打印、统计大小、执行 rm。 Whitespaces are safe via -print0.
collect_and_delete() {
  local label="$1"; shift
  local found=0
  local size_bytes count
  while IFS= read -r -d '' path; do
    found=1
    if [[ -d "$path" ]]; then
      size_bytes="$(du -sk "$path" 2>/dev/null | awk '{print $1}')"
      size_bytes=$(( size_bytes * 1024 ))
      echo "  [dir ] $label: ${path#$ROOT/}"
      if [[ "$DRY_RUN" == "0" ]]; then rm -rf -- "$path"; fi
    else
      size_bytes="$(stat -f%z "$path" 2>/dev/null || stat -c%s "$path" 2>/dev/null || echo 0)"
      echo "  [file] $label: ${path#$ROOT/} ($(du -h "$path" 2>/dev/null | awk '{print $1}'))"
      if [[ "$DRY_RUN" == "0" ]]; then rm -f -- "$path"; fi
    fi
    total_count+=1
    total_bytes+=$((${size_bytes:-0}))
  done < <(find "$@" -print0 2>/dev/null || true)
  if [[ "$found" == "0" ]]; then
    echo "  [ ok  ] $label: 无残留"
  fi
}

echo "logs/ 运行残留："
if [[ -d "$ROOT/logs" ]]; then
  collect_and_delete "GC 日志"        "$ROOT/logs" -maxdepth 1 -type f \( -name 'gc-*.log' -o -name 'stringdedup-*.log' -o -name 'safepoint-*.log' -o -name 'class-unload-*.log' -o -name 'generate-console-*.log' \)
  collect_and_delete "场景响应 JSON"  "$ROOT/logs" -maxdepth 1 -type f -name 'scenario-*.json'
  collect_and_delete "JFR 录制"       "$ROOT/logs" -maxdepth 1 -type f -name '*.jfr'
  collect_and_delete "崩溃/转储"      "$ROOT/logs" -maxdepth 1 -type f \( -name 'hs_err_pid*.log' -o -name 'java_pid*.hprof' -o -name '.sample-run-marker' \)
else
  echo "  [ ok  ] logs/ 目录不存在"
fi
echo

echo "reports/ A/B 实验输出："
if [[ "$KEEP_REPORTS" == "1" ]]; then
  echo "  [skip] 已按 --keep-reports 保留"
elif [[ -d "$ROOT/reports" ]]; then
  collect_and_delete "tuning 报告" "$ROOT/reports" -maxdepth 1 -type d -name 'tuning-*'
  # reports 目录空了就顺手移除
  if [[ "$DRY_RUN" == "0" ]] && [[ -d "$ROOT/reports" ]] && [[ -z "$(ls -A "$ROOT/reports" 2>/dev/null)" ]]; then
    rmdir "$ROOT/reports" 2>/dev/null || true
  fi
else
  echo "  [ ok  ] 无 reports/ 目录"
fi
echo

echo "临时目录与系统杂物："
collect_and_delete "类卸载临时目录" "${TMPDIR:-/tmp}" -maxdepth 1 -type d -name 'g1-class-unload-*'
collect_and_delete ".DS_Store"      "$ROOT" -name '.DS_Store' -not -path '*/target/*'
echo

if [[ "$WIPE_TARGET" == "1" ]]; then
  echo "target/ 构建产物："
  collect_and_delete "Maven target" "$ROOT" -maxdepth 1 -type d -name 'target'
  echo
fi

echo "== 清理完成：$total_count 个条目，约 $(echo $total_bytes | awk '{printf "%.1f MB", $1/1024/1024}') =="
if [[ "$DRY_RUN" == "1" ]]; then
  echo "（dry-run 模式，未实际删除；去掉 --dry-run 执行清理）"
else
  echo "docs/samples/ 提交样例与源码保持原样。"
fi
