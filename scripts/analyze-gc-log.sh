#!/usr/bin/env bash
#
# Summarize JDK 21 G1 pause lines without external dependencies.
# It intentionally ignores gc,start/phases lines and only counts summary lines
# that contain before->after(capacity) and a pause in milliseconds.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${LOG_DIR:-$ROOT/logs}"
FILE="${1:-}"

if [[ -z "$FILE" ]]; then
  FILE="$(ls -1t "$LOG_DIR"/gc-*.log 2>/dev/null | head -1 || true)"
fi
if [[ -z "$FILE" || ! -f "$FILE" ]]; then
  echo "No GC log found under $LOG_DIR. Start with scripts/run.sh first." >&2
  exit 1
fi

echo "GC log: $FILE"
echo "Size: $(wc -c < "$FILE" | tr -d ' ') bytes"
echo

awk '
/GC\([0-9]+\) Pause/ && /ms[[:space:]]*$/ {
    count++
    type = "Other"
    pause = 0
    size = ""
    n = split($0, f, /[[:space:]]+/)
    for (i = 1; i <= n; i++) {
        if (f[i] == "Pause") {
            kind = f[i + 1]
            if (kind == "Young") {
                subtype = f[i + 2]
                gsub(/[()]/, "", subtype)
                if (subtype == "Mixed") type = "Mixed"
                else if (subtype == "Concurrent Start") type = "Concurrent Start"
                else if (subtype == "Prepare Mixed") type = "Prepare Mixed"
                else type = "Young"
            } else {
                type = kind
            }
        }
        if (index(f[i], "->") > 0 && size == "") {
            size = f[i]
            split(size, size_parts, "->")
            last_after = size_parts[2]
            sub(/\(.*/, "", last_after)
        }
        if (f[i] ~ /^[0-9.]+ms$/) {
            pause = f[i]
            sub(/ms$/, "", pause)
            pause += 0
        }
    }
    cnt[type]++
    total[type] += pause
    if (pause > max[type]) max[type] = pause
    all_total += pause
    if (pause > global_max) {
        global_max = pause
        global_max_line = $0
    }
    if (type == "Full") full_lines = full_lines $0 "\n"
    if (size != "") last_size = size
}
END {
    printf "Pause summary\n"
    printf "%-18s %8s %12s %12s\n", "type", "count", "avg(ms)", "max(ms)"
    order[1] = "Young"
    order[2] = "Mixed"
    order[3] = "Concurrent Start"
    order[4] = "Prepare Mixed"
    order[5] = "Remark"
    order[6] = "Cleanup"
    order[7] = "Full"
    order[8] = "Other"
    for (i = 1; i <= 8; i++) {
        t = order[i]
        if (cnt[t] > 0) {
            printf "%-18s %8d %12.3f %12.3f\n", t, cnt[t], total[t] / cnt[t], max[t]
        }
    }
    printf "%-18s %8d %12.3f %12.3f\n", "TOTAL", count, (count ? all_total / count : 0), global_max
    printf "\nLast heap after pause: %s\n", last_after
    printf "Longest pause: %.3f ms\n", global_max
    if (global_max_line != "") printf "  %s\n", global_max_line
    if (full_lines != "") {
        printf "\nFull GC lines\n%s", full_lines
    }
}
' "$FILE"

echo
echo "Concurrent / humongous / failure markers"
grep -E 'Concurrent (Mark|Start|Cleanup|Remark)|Humongous regions|To-space exhausted|Evacuation Failure|Allocation Failure|Concurrent String Deduplication|class,unload|Unloading class' "$FILE" \
  | tail -40 || true

echo
echo "Quick queries"
echo "  grep -E 'Pause (Young|Full|Remark|Cleanup)' '$FILE'"
echo "  grep -E 'Remembered Sets|Scanned Cards|gc,remset|gc,refine,stats' '$FILE'"
echo "  grep -E 'Humongous|To-space|Evacuation Failure|Allocation Failure' '$FILE'"
