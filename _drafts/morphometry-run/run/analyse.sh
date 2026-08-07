#!/usr/bin/env bash
# aggregate -> derive -> tables, for one or more morphometry output folders.
# Uses the UNMODIFIED repo aggregate_to_mouse.py.
set -euo pipefail
PY="C:/Users/dream/AppData/Local/Programs/Python/Python312-arm64/python.exe"
REPO="C:/Users/dream/Documents/GitHub/IFQuant-Lung"
SP="C:/Users/dream/AppData/Local/Temp/claude/X--QuPath/7933abe5-e14c-44b2-aa07-c4127fa41a9e/scratchpad/build2/morphometry"

STATS_DIRS=()
for out in "$@"; do
  for csv in "$out"/morphometry_slide_summary_ds*.csv; do
    [ -e "$csv" ] || continue
    tag=$(basename "$csv" .csv); tag=${tag#morphometry_slide_summary}
    stats="$out/stats$tag"
    echo "=== $csv -> $stats ==="
    "$PY" "$REPO/aggregate_to_mouse.py" "$csv" --outdir "$stats"
    "$PY" "$SP/morphometry_derive.py" "$stats/mouse_level_summary.csv" --tag "$tag"
    STATS_DIRS+=("$stats")
  done
done
echo
"$PY" "$SP/report_tables.py" "${STATS_DIRS[@]}"
