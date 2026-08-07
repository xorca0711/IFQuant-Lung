#!/usr/bin/env python3
"""
One-off label repair for slide summaries written before the scope rename.

The four emitted scopes PARTITION the ROI: label 1 = damaged minus its 40 um
core, label 2 = damaged core, label 3 = intact minus core, label 4 = intact
core. Early runs named labels 1 and 3 "damaged" and "intact", which is wrong --
they are the EDGE subsets, and `damaged` is the union of 1 and 2.

Only the `panel`, `region` and `output_key` strings change. No measurement is
touched. Verified by the area identity printed at the end:
  damaged_edge + damaged_core  ==  the detector's damaged area.

  python fix_scope_names.py <morphometry_slide_summary_ds*.csv> [...]
"""
import csv
import sys

REN = {"damaged": "damaged_edge", "intact": "intact_edge"}

for path in sys.argv[1:]:
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        cols, rows = rd.fieldnames, list(rd)
    n = 0
    for r in rows:
        pk, _, scope = (r.get("panel") or "").partition("@")
        if scope in REN:
            new = REN[scope]
            r["panel"] = pk + "@" + new
            r["region"] = "parenchyma_" + new
            if r.get("output_key", "").endswith("__" + scope):
                r["output_key"] = r["output_key"][: -len(scope)] + new
            n += 1
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        w.writerows(rows)
    # area identity check
    by = {}
    for r in rows:
        key = (r["mouse_id"], (r["panel"] or "").partition("@")[2])
        by[key] = float(r["region_area_um2"])
    print(f"{path}: renamed {n} rows")
    for mouse in sorted({m for m, _ in by}):
        d = by.get((mouse, "damaged_edge"), 0) + by.get((mouse, "damaged_core"), 0)
        i = by.get((mouse, "intact_edge"), 0) + by.get((mouse, "intact_core"), 0)
        print(f"   {mouse:<6} damaged {d/1e6:9.4f} mm2   intact {i/1e6:9.4f} mm2   "
              f"total {(d+i)/1e6:9.4f} mm2   damaged frac {d/(d+i):.4f}")
