#!/usr/bin/env python3
"""
derive_metrics.py
=====================================================================
POST-aggregation derivation of ratios that aggregate_to_mouse.py cannot form.

Why this exists, and why it runs AFTER and not INSIDE the aggregator
--------------------------------------------------------------------
aggregate_to_mouse.py pools only EXTENSIVE quantities: counts
(<X>_pos_count, class_<L>_count) and areas (<X>_positive_area_um2,
<X>_pod_area_um2, region_area_um2, n_nuclei). It then mints exactly four
families of ratio (density per mm2, area fraction, mean object area,
per-cell fraction) -- aggregate_to_mouse.py:256-338.

A morphometry or spatial metric whose denominator is none of those --
mean linear intercept (length / intercept count), mean nearest-neighbour
distance, a colocalisation index -- has no carrier. If a module writes the
ratio directly, classify_columns() does not recognise the name and the column
VANISHES from mouse_level_summary.csv without any warning (verified).

The fix is NOT to teach aggregate_to_mouse.py new rules. Every rule added there
is a rule two modules can later disagree about, which is the fork this
repository is explicitly designed to prevent. Instead:

  * the module emits the NUMERATOR and DENOMINATOR as poolable counts/areas,
  * the module declares the ratio in module_provenance.json -> derived_metrics,
  * this script reads mouse_level_summary.csv (already pooled, n = mice) and
    appends the ratio columns.

The pooling is therefore performed exactly once, by exactly one file, for
every module. This script never groups, never sums, never touches identity.

Example (mean linear intercept)
-------------------------------
  module emits per slide:  mli_testline_pos_count = 64
                           mli_intercept_pos_count = 880
  provenance declares:     scale = 2048.0 um  (test-line length, a grid
                           constant -- it is a PARAMETER, not a measurement,
                           so it must never appear as a CSV column)
  aggregate_to_mouse gives: mli_testline_pos_count_total,
                            mli_intercept_pos_count_total
  this script computes:     mean_linear_intercept_um
                              = 2048.0 * total_testlines / total_intercepts

Usage
-----
  python derive_metrics.py mouse_level_summary.csv \
         --provenance morphometry/module_provenance.json [more.json ...] \
         --out mouse_level_summary.derived.csv

The input file is never modified in place.
No third-party dependencies (standard library only).
=====================================================================
"""
import argparse
import csv
import json
import os
import sys

KEY_COLS = ["mouse_id", "genotype", "condition", "panel"]


def load_specs(paths):
    specs = []
    for p in paths:
        with open(p, encoding="utf-8") as fh:
            prov = json.load(fh)
        panel = ((prov.get("denominator") or {}).get("panel_token"))
        for d in (prov.get("derived_metrics") or []):
            missing = [k for k in ("name", "numerator", "denominator", "units") if k not in d]
            if missing:
                sys.exit("ERROR: %s derived_metrics entry missing %s" % (p, missing))
            specs.append({
                "name": d["name"],
                "numerator": d["numerator"],
                "denominator": d["denominator"],
                "scale": float(d.get("scale", 1.0)),
                "units": d["units"],
                "panel": panel,          # None -> apply to every panel
                "source": os.path.basename(p),
            })
    return specs


def num(v):
    try:
        return float(str(v).strip())
    except (TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser(
        description="Append provenance-declared ratio metrics to mouse_level_summary.csv.")
    ap.add_argument("mouse_summary")
    ap.add_argument("--provenance", nargs="+", required=True,
                    help="one or more module_provenance.json files")
    ap.add_argument("--out", default=None)
    ap.add_argument("--strict", action="store_true",
                    help="fail when a declared numerator/denominator column is absent")
    args = ap.parse_args()

    specs = load_specs(args.provenance)
    if not specs:
        sys.exit("ERROR: no derived_metrics declared in the supplied provenance files.")

    with open(args.mouse_summary, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        header = list(rd.fieldnames or [])
        rows = [r for r in rd]
    if not rows:
        sys.exit("ERROR: no rows in " + args.mouse_summary)

    added, skipped = [], []
    for s in specs:
        if s["numerator"] not in header or s["denominator"] not in header:
            msg = ("%s: %s or %s absent from %s"
                   % (s["name"], s["numerator"], s["denominator"],
                      os.path.basename(args.mouse_summary)))
            if args.strict:
                sys.exit("ERROR: " + msg)
            skipped.append(msg)
            continue
        col = s["name"]
        if col in header:
            sys.exit("ERROR: %r already exists in the input. Refusing to overwrite "
                     "an aggregated column." % col)
        for r in rows:
            if s["panel"] and (r.get("panel") or "").strip() != s["panel"]:
                r[col] = ""
                continue
            n, d = num(r.get(s["numerator"])), num(r.get(s["denominator"]))
            r[col] = (s["scale"] * n / d) if (n is not None and d) else ""
        header.append(col)
        added.append("%s = %g * %s / %s  [%s, from %s]"
                     % (col, s["scale"], s["numerator"], s["denominator"],
                        s["units"], s["source"]))

    out = args.out or os.path.join(
        os.path.dirname(os.path.abspath(args.mouse_summary)),
        "mouse_level_summary.derived.csv")
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=header)
        w.writeheader()
        for r in rows:
            w.writerow(r)

    for a in added:
        print("  + " + a)
    for s in skipped:
        print("  ! skipped " + s)
    print("Wrote %d row(s), %d column(s) -> %s" % (len(rows), len(header), out))
    print("n is unchanged: this script derives, it never re-groups.")


if __name__ == "__main__":
    main()
