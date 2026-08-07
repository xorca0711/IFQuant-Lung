#!/usr/bin/env python3
"""
spatial/join_spatial_to_summary.py
=====================================================================
STAGE S3: attach spatial sufficient statistics to the summary CSV that
aggregate_to_mouse.py is about to read, WITHOUT changing aggregate_to_mouse.py
and without creating a second aggregation path.

  target  = run_summary.csv           (field / confocal route), or
            slide_level_summary.csv   (WSI route, from aggregate_tiles_to_slide.py)
  spatial = spatial_summary.csv       (from spatial_stats.py, one or many)
  output  = <target stem>_with_spatial.csv

  python3 aggregate_to_mouse.py <target stem>_with_spatial.csv     # UNCHANGED

Join key
--------
The identity aggregate_to_mouse.validate_rows() itself uses (lines 97-105):
    (output_key if present else image, region, section_id, panel)
imported from aggregate_to_mouse so it cannot drift. A spatial row must match
EXACTLY ONE target row. Zero matches or two matches is fatal: a silently
unjoined spatial row means the endpoint denominator and the spatial numerator
came from different tissue.

Why fail-loud on column collision
---------------------------------
If a spatial column name already exists in the target, one of them is wrong --
most likely the spatial module has accidentally reused an engine column such as
region_area_um2, whose value would then be overwritten and every downstream
fraction silently rescaled. Refuse, do not merge.

Why the bin signature is checked
--------------------------------
Distance histograms only add when the bin edges are identical. Two slides
analysed with different `spatial_bin_signature` values cannot be pooled into
one mouse, and the failure would be invisible in the arithmetic. Checked here,
before pooling, not after.

Standard library only.
=====================================================================
"""
import argparse
import csv
import json
import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
for p in (_REPO, _HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

try:
    from aggregate_to_mouse import KEY_COLS, ROW_ID_COLS
except ImportError:
    sys.exit("ERROR: spatial/ must sit inside the IFQuant-Lung repo beside "
             "aggregate_to_mouse.py.")

# Columns that describe the spatial run rather than measure anything. They are
# preserved in the joined CSV and in a provenance sidecar, but aggregate_to_mouse
# does not (and must not) aggregate them.
META_PREFIXES = ("spatial_profile", "spatial_bin_signature", "spatial_window_mode",
                 "spatial_edge_correction", "spatial_null_model",
                 "spatial_eligible_pool", "spatial_query_fraction",
                 "spatial_perm_", "structure_source")


def read_csv_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        if rd.fieldnames is None:
            sys.exit(f"ERROR: {path} is empty or has no header.")
        return list(rd.fieldnames), [r for r in rd if any((v or "").strip() for v in r.values())]


def identity(row, key_col):
    """Row identity, using the SAME column aggregate_to_mouse.validate_rows()
    will use on the joined file (its lines 97-102). The key column is decided
    once from the TARGET header and then applied to both sides, so a spatial row
    that happens to carry output_key cannot be keyed differently from the target
    row it must match."""
    return tuple((row.get(c) or "").strip() for c in (key_col, "region", "section_id", "panel"))


def main():
    ap = argparse.ArgumentParser(
        description="Join spatial_summary.csv onto the summary CSV that feeds aggregate_to_mouse.py.")
    ap.add_argument("--target", required=True,
                    help="run_summary.csv or slide_level_summary.csv")
    ap.add_argument("--spatial", required=True, nargs="+",
                    help="one or more spatial_summary.csv")
    ap.add_argument("--out", default=None, help="default: <target stem>_with_spatial.csv")
    ap.add_argument("--allow-unjoined-target-rows", action="store_true",
                    help="permit target rows with no spatial row (their spatial columns are "
                         "left BLANK, which aggregate_to_mouse._num() treats as absent, not 0)")
    args = ap.parse_args()

    t_header, t_rows = read_csv_rows(args.target)
    missing = [c for c in KEY_COLS + ROW_ID_COLS if c not in t_header]
    if missing:
        sys.exit(f"ERROR: {args.target} lacks {missing}; aggregate_to_mouse.py would reject it "
                 "anyway. Fix the target before joining.")

    s_header, s_rows = [], []
    for p in args.spatial:
        h, r = read_csv_rows(p)
        for c in h:
            if c not in s_header:
                s_header.append(c)
        s_rows.extend(r)
    if not s_rows:
        sys.exit("ERROR: no spatial rows to join.")

    sigs = {(r.get("spatial_bin_signature") or "") for r in s_rows}
    if len(sigs) > 1:
        sys.exit(f"ERROR: {len(sigs)} different spatial_bin_signature values among the spatial "
                 "rows. Distance histograms with different bin edges are not additive and "
                 "must not be pooled into one mouse. Re-run every slide with one profile.")
    profs = {(r.get("spatial_profile") or "") for r in s_rows}
    if len(profs) > 1:
        sys.exit(f"ERROR: spatial rows come from different profiles {sorted(profs)}.")

    new_cols = [c for c in s_header if c not in (KEY_COLS + ROW_ID_COLS + ["output_key"])]
    clash = [c for c in new_cols if c in t_header]
    if clash:
        sys.exit(f"ERROR: {len(clash)} spatial column(s) already exist in the target: "
                 f"{clash[:8]}. Refusing to overwrite engine measurements.")

    key_col = "output_key" if "output_key" in t_header else "image"
    if key_col not in s_header:
        sys.exit(f"ERROR: the target is keyed on '{key_col}' but the spatial rows do not "
                 f"carry that column. spatial_stats.py must stamp it.")
    index = {}
    for r in t_rows:
        index.setdefault(identity(r, key_col), []).append(r)

    joined, unmatched = 0, []
    for sr in s_rows:
        key = identity(sr, key_col)
        hits = index.get(key, [])
        if len(hits) != 1:
            unmatched.append((key, len(hits)))
            continue
        for c in new_cols:
            hits[0][c] = sr.get(c, "")
        joined += 1
    if unmatched:
        detail = "; ".join(f"{k} -> {n} target row(s)" for k, n in unmatched[:5])
        sys.exit("ERROR: {} spatial row(s) did not match exactly one target row: {}. "
                 "The join key is (output_key|image, region, section_id, panel), the same key "
                 "aggregate_to_mouse.py validates on. For the WSI route, spatial_stats.py "
                 "stamps image = section_id = slide name to match the slide row that "
                 "aggregate_tiles_to_slide.py writes (its lines 266-267)."
                 .format(len(unmatched), detail))

    blank = [r for r in t_rows if not any(r.get(c) for c in new_cols)]
    if blank and not args.allow_unjoined_target_rows:
        sys.exit(f"ERROR: {len(blank)} target row(s) received no spatial statistics "
                 f"(e.g. region='{(blank[0].get('region') or '')}'). Pooling a mouse from a "
                 "mixture of rows that do and do not carry spatial numerators produces a "
                 "numerator over the wrong denominator. Pass --allow-unjoined-target-rows "
                 "only when you intend those rows to contribute area but no spatial counts.")
    for r in t_rows:
        for c in new_cols:
            r.setdefault(c, "")

    out = args.out or (os.path.splitext(os.path.abspath(args.target))[0] + "_with_spatial.csv")
    cols = t_header + new_cols
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in t_rows:
            w.writerow(r)

    prov = {
        "target": os.path.abspath(args.target),
        "spatial_inputs": [os.path.abspath(p) for p in args.spatial],
        "spatial_profile": sorted(profs)[0],
        "spatial_bin_signature": sorted(sigs)[0],
        "n_target_rows": len(t_rows),
        "n_spatial_rows_joined": joined,
        "n_target_rows_without_spatial": len(blank),
        "metadata_columns_not_aggregated":
            [c for c in new_cols if c.startswith(META_PREFIXES) or c.endswith("_structure_source")],
    }
    with open(os.path.splitext(out)[0] + "_provenance.json", "w", encoding="utf-8") as fh:
        json.dump(prov, fh, indent=2)

    print(f"Joined {joined} spatial row(s) into {len(t_rows)} target row(s) -> {out}")
    print(f"Next:  python3 aggregate_to_mouse.py {out}")
    print("       python3 spatial/spatial_mouse_metrics.py "
          "<outdir>/mouse_level_summary.csv")


if __name__ == "__main__":
    main()
