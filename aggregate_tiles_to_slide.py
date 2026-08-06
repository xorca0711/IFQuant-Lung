#!/usr/bin/env python3
"""
aggregate_tiles_to_slide.py
=====================================================================
STAGE 3 of the whole-slide (WSI) route: roll per-TILE results up to the
SLIDE level, and prove that nothing was silently lost on the way.

Pipeline position
-----------------
  Stage 1  qupath_wsi_tile_export.groovy  -> tiles + tile_manifest.csv
  Stage 2  IF_Quant_Pipeline.groovy       -> run_summary.csv (one row per tile)
  Stage 3  THIS SCRIPT                    -> slide_level_summary.csv
  Stage 4  aggregate_to_mouse.py          -> mouse_level / group_level

Why this exists
---------------
Tiles overlap by a halo so objects at a core boundary are fully imaged. Stage 1
writes a per-tile `_RoiSet.zip` restricting every measurement to the tile CORE
intersected with tissue, so AREA endpoints sum exactly with no double counting.
But three things still need checking, and none of them are visible in
run_summary.csv alone:

  1. COVERAGE. A tile that failed in Stage 2 vanishes from run_summary.csv.
     Its tissue area disappears from the denominator and its KRT5 pod area from
     the numerator. The slide still produces a perfectly plausible number.
     This script reconciles run_summary.csv against tile_manifest.csv and
     REFUSES to emit a slide row when tiles are missing.
  2. AREA RECONCILIATION. sum(region_area_um2) over tiles must equal the global
     tissue area QuPath measured in Stage 1. A mismatch means the ROIs did not
     do what we think they did.
  3. SEAM COUNT INFLATION. ImageJ's ParticleAnalyzer CLIPS nuclei at the ROI
     edge rather than excluding them, so one nucleus straddling a core boundary
     can appear as a fragment in each neighbour. AREA endpoints are unaffected;
     CELL COUNTS can be inflated by a few percent. This script measures that
     inflation from the per-cell centroids and reports it. Correction is
     opt-in -- see --seam-counts.

Pooling reuses aggregate_to_mouse.classify_columns() so slide-level and
mouse-level pooling can never drift apart.

Usage
-----
  python3 aggregate_tiles_to_slide.py \\
      --stage1-manifest D:/wsi_stage1/stage1_manifest.json \\
      --slide-root      D:/wsi_stage1 \\
      --outdir          D:/wsi_stage1/stats

  # then
  python3 aggregate_to_mouse.py D:/wsi_stage1/stats/slide_level_summary.csv

Each slide folder under --slide-root is expected to look like:
  <slide>/tile_manifest.csv
  <slide>/analysis*/run_summary.csv     (one, or several if Stage 2 was sharded)

No third-party dependencies (standard library only).
=====================================================================
"""
import argparse
import csv
import glob
import json
import math
import os
import sys
from collections import defaultdict

# Reuse the validated pooling classification so slide-level and mouse-level
# aggregation can never disagree about what is a sum vs a derived quantity.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    from aggregate_to_mouse import classify_columns, _num, marker_of
except ImportError:  # pragma: no cover
    sys.exit("ERROR: aggregate_to_mouse.py must sit beside this script "
             "(its column classification is reused so pooling cannot drift).")

# Identity columns carried through to the slide row.
CARRY = ["mouse_id", "genotype", "condition", "panel"]


def read_csv_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        if reader.fieldnames is None:
            return [], []
        rows = [r for r in reader if any((v or "").strip() for v in r.values())]
        return reader.fieldnames, rows


def find_run_summaries(slide_dir):
    """Stage 2 may be sharded across several output folders; collect them all."""
    hits = sorted(glob.glob(os.path.join(slide_dir, "**", "run_summary.csv"), recursive=True))
    return [h for h in hits if os.path.getsize(h) > 0]


def load_stage1_manifest(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def read_stage2_manifests(analysis_dirs):
    """
    Collect Stage 2 per-image failures from run_manifest.json.

    The engine catches every per-image Throwable, records it, and carries on;
    outputs are all written before the terminal failRun, and the process exits 1
    whenever ANY image failed. So a non-zero exit does NOT mean "no results",
    and run_summary.csv alone cannot tell you a tile is missing -- the row is
    simply absent. Reading the manifest turns that into a precise reason.
    """
    total_fail, reasons, statuses = 0, [], []
    for d in analysis_dirs:
        p = os.path.join(d, "run_manifest.json")
        if not os.path.isfile(p):
            continue
        try:
            with open(p, encoding="utf-8") as fh:
                man = json.load(fh)
        except Exception as exc:                       # noqa: BLE001
            reasons.append(f"{p}: unreadable ({exc})")
            continue
        statuses.append(str(man.get("status", "unknown")))
        total_fail += int(man.get("failure_count", 0) or 0)
        for img in (man.get("images") or []):
            err = img.get("error")
            if err:
                reasons.append(f"{img.get('image', '<unknown>')}: {err}")
    return total_fail, reasons, statuses


# --------------------------------------------------------------------------
# Seam diagnostics
# --------------------------------------------------------------------------
def collect_cell_centroids(analysis_dirs, manifest_by_section):
    """
    Read every per-tile __cells.csv and return global centroids in microns.

    The Fiji engine writes centroid_x_um / centroid_y_um in TILE-LOCAL
    calibrated coordinates, relative to the exported tile origin. tile_manifest
    gives that origin in full-resolution slide pixels, so
        global_um = (export_origin_px * pixel_size_um) + local_um

    Only the analysis folders that actually contributed a run_summary.csv are
    scanned. Globbing the whole slide folder would also pick up abandoned or
    partial Stage 2 output directories and count their cells a second time.
    """
    out = []
    missing_cells = 0
    paths = []
    for d in analysis_dirs:
        paths.extend(glob.glob(os.path.join(d, "**", "*__cells.csv"), recursive=True))
    for cells_path in sorted(set(paths)):
        # output folder name is <mouse>_<condition>_<panel>_<section_id>[__hash]
        folder = os.path.basename(os.path.dirname(cells_path))
        section = None
        for sec in manifest_by_section:
            if folder.endswith("_" + sec) or ("_" + sec + "__") in folder:
                section = sec
                break
        if section is None:
            missing_cells += 1
            continue
        tm = manifest_by_section[section]
        px = float(tm["pixel_size_um"])
        ox = float(tm["export_x"]) * px
        oy = float(tm["export_y"]) * px
        _, rows = read_csv_rows(cells_path)
        for r in rows:
            cx, cy = _num(r.get("centroid_x_um")), _num(r.get("centroid_y_um"))
            if cx is None or cy is None:
                continue
            out.append((cx + ox, cy + oy, section))
    return out, missing_cells


def estimate_seam_duplicates(centroids, merge_dist_um):
    """
    Count cells from DIFFERENT tiles whose global centroids are closer than
    merge_dist_um. Those are the same physical nucleus clipped by a shared core
    boundary. Uses a uniform grid so it stays linear in cell count.
    """
    if merge_dist_um <= 0 or not centroids:
        return 0, 0
    cell = merge_dist_um
    grid = defaultdict(list)
    for i, (x, y, sec) in enumerate(centroids):
        grid[(int(math.floor(x / cell)), int(math.floor(y / cell)))].append(i)

    seen_pair = set()
    dup = 0
    d2 = merge_dist_um * merge_dist_um
    for (gx, gy), idxs in grid.items():
        neigh = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                neigh.extend(grid.get((gx + dx, gy + dy), ()))
        for i in idxs:
            xi, yi, si = centroids[i]
            for j in neigh:
                if j <= i:
                    continue
                xj, yj, sj = centroids[j]
                if si == sj:            # same tile -> genuinely two nuclei
                    continue
                if (xi - xj) ** 2 + (yi - yj) ** 2 <= d2:
                    key = (i, j)
                    if key not in seen_pair:
                        seen_pair.add(key)
                        dup += 1
    return dup, len(centroids)


# --------------------------------------------------------------------------
def aggregate_slide(slide_name, manifest_rows, header, tile_rows, stage1_slide,
                    seam_dup, n_cells, stage2_failures=0, stage2_reasons=()):
    """Sum tile rows into one slide row, after reconciling coverage."""
    cats = classify_columns(header)

    expected = {r["section_id"] for r in manifest_rows}
    got = {(r.get("section_id") or "").strip() for r in tile_rows}
    missing = sorted(expected - got)
    extra = sorted(got - expected)

    problems = []
    if missing:
        detail = ""
        if stage2_reasons:
            detail = " Stage 2 reported: " + " || ".join(stage2_reasons[:4])
        problems.append(
            f"{len(missing)} tile(s) in tile_manifest.csv have NO run_summary row "
            f"(their tissue area and KRT5 pod area are missing from this slide): "
            + ", ".join(missing[:8]) + ("..." if len(missing) > 8 else "") + detail)
    if stage2_failures and not missing:
        problems.append(
            f"Stage 2 run_manifest.json reports {stage2_failures} per-image failure(s) even "
            "though every tile has a summary row. Inspect before trusting this slide.")
    if extra:
        problems.append(f"{len(extra)} run_summary row(s) are not in tile_manifest.csv: "
                        + ", ".join(extra[:8]))
    if stage1_slide is not None and not stage1_slide.get("coverage_complete", True):
        problems.append("Stage 1 recorded coverage_complete=false "
                        "(IFQ_WSI_MAX_TILES_PER_SLIDE was set) -- this is a smoke test, not an analysis.")
    if stage1_slide is not None and stage1_slide.get("dry_run", False):
        problems.append("Stage 1 recorded dry_run=true -- no tiles were actually exported.")

    rec = {"slide": slide_name}
    for c in CARRY:
        vals = {(r.get(c) or "").strip() for r in tile_rows if (r.get(c) or "").strip()}
        if len(vals) > 1:
            problems.append(f"tiles disagree on '{c}': {sorted(vals)}")
        rec[c] = sorted(vals)[0] if vals else "NA"

    # aggregate_to_mouse keys rows on (image|output_key, region, section_id, panel).
    # One row per slide, so give it stable slide-scoped identity columns.
    rec["image"] = slide_name
    rec["section_id"] = slide_name
    rec["region"] = (tile_rows[0].get("region") if tile_rows else "alveolar_core") or "alveolar_core"

    sums = {}
    for c in cats["sum_cols"]:
        vals = [_num(r.get(c)) for r in tile_rows]
        vals = [v for v in vals if v is not None]
        sums[c] = sum(vals) if vals else 0.0
    for c, v in sums.items():
        rec[c] = v

    total_area = sums.get("region_area_um2", 0.0)

    # Recompute every derived quantity from POOLED numerators. Never average
    # per-tile fractions: tiles differ in tissue area.
    for c in cats["pod_area"]:
        m = marker_of(c, "_pod_area_um2")
        rec[f"{m}_pod_area_frac"] = (sums[c] / total_area) if total_area > 0 else 0.0
        npods = sums.get(f"{m}_n_pods", 0.0)
        rec[f"{m}_mean_pod_area_um2"] = (sums[c] / npods) if npods > 0 else 0.0
    for c in cats["positive_area"]:
        m = marker_of(c, "_positive_area_um2")
        rec[f"{m}_positive_area_frac"] = (sums[c] / total_area) if total_area > 0 else 0.0

    # ---- QC / provenance ------------------------------------------------
    rec["n_tiles_expected"] = len(manifest_rows)
    rec["n_tiles_analyzed"] = len(tile_rows)
    rec["n_tiles_missing"] = len(missing)
    rec["tile_coverage_fraction"] = (len(tile_rows) / len(manifest_rows)) if manifest_rows else 0.0

    manifest_core_um2 = sum(float(r["core_tissue_area_um2"]) for r in manifest_rows)
    rec["stage1_core_tissue_area_um2"] = manifest_core_um2
    rec["stage2_region_area_um2"] = total_area
    rec["tissue_area_rel_diff"] = (
        abs(total_area - manifest_core_um2) / manifest_core_um2 if manifest_core_um2 > 0 else 0.0)
    if stage1_slide is not None:
        rec["stage1_slide_tissue_mm2"] = stage1_slide.get("tissue_area_mm2")
        rec["stage1_tissue_threshold_otsu"] = stage1_slide.get("tissue_threshold_otsu")
        rec["source_vsi"] = stage1_slide.get("source_vsi")
        rec["series_index"] = stage1_slide.get("series_index")

    rec["seam_duplicate_cell_pairs"] = seam_dup
    rec["n_cells_scanned"] = n_cells
    rec["seam_duplicate_fraction"] = (seam_dup / n_cells) if n_cells else 0.0

    if rec["tissue_area_rel_diff"] > 0.01:
        problems.append(
            f"Stage 2 tissue area ({total_area:.0f} um2) differs from the Stage 1 core "
            f"tissue area ({manifest_core_um2:.0f} um2) by "
            f"{rec['tissue_area_rel_diff'] * 100:.2f}%. The per-tile _RoiSet.zip may not "
            "have been picked up -- check that each tile has a companion "
            "'<stem>.ome_RoiSet.zip' beside it.")

    rec["qc_status"] = "ok" if not problems else "PROBLEM"
    rec["qc_notes"] = " | ".join(problems)
    return rec, problems


def write_csv(path, rows):
    if not rows:
        open(path, "w").close()
        return
    cols = []
    for r in rows:
        for c in r:
            if c not in cols:
                cols.append(c)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        for r in rows:
            w.writerow(r)


def main():
    ap = argparse.ArgumentParser(
        description="Roll per-tile Fiji results up to slide level, with coverage reconciliation.")
    ap.add_argument("--slide-root", required=True,
                    help="Stage 1 output root (contains one folder per slide)")
    ap.add_argument("--stage1-manifest", default=None,
                    help="stage1_manifest.json (default: <slide-root>/stage1_manifest.json)")
    ap.add_argument("--outdir", default=None, help="output folder (default: <slide-root>/stats)")
    ap.add_argument("--seam-merge-um", type=float, default=4.0,
                    help="two centroids from different tiles closer than this are treated as "
                         "one nucleus clipped at a seam (default 4.0 um, ~one nuclear radius)")
    ap.add_argument("--seam-counts", choices=["report", "off"], default="report",
                    help="'report' measures seam count inflation and records it (default); "
                         "'off' skips reading per-cell CSVs. Counts are NOT silently altered.")
    ap.add_argument("--allow-incomplete", action="store_true",
                    help="emit slide rows even when tiles are missing (NOT for analysis)")
    args = ap.parse_args()

    root = os.path.abspath(args.slide_root)
    if not os.path.isdir(root):
        sys.exit(f"ERROR: --slide-root not found: {root}")
    manifest_path = args.stage1_manifest or os.path.join(root, "stage1_manifest.json")
    stage1 = load_stage1_manifest(manifest_path) if os.path.isfile(manifest_path) else None
    if stage1 is None:
        print(f"WARNING: {manifest_path} not found; Stage 1 provenance checks are disabled.")
    stage1_by_stem = {s["slide_stem"]: s for s in (stage1 or {}).get("slides", [])}

    outdir = args.outdir or os.path.join(root, "stats")
    os.makedirs(outdir, exist_ok=True)

    slide_rows, all_problems = [], []
    for entry in sorted(os.listdir(root)):
        slide_dir = os.path.join(root, entry)
        tm_path = os.path.join(slide_dir, "tile_manifest.csv")
        if not os.path.isfile(tm_path):
            continue
        _, manifest_rows = read_csv_rows(tm_path)
        if not manifest_rows:
            print(f"WARNING: {tm_path} has no rows; skipping {entry}")
            continue

        summaries = find_run_summaries(slide_dir)
        if not summaries:
            msg = (f"{entry}: no non-empty run_summary.csv found under {slide_dir}. "
                   "Stage 2 has not run, or every tile failed.")
            print("ERROR: " + msg)
            all_problems.append(msg)
            continue

        header, tile_rows = [], []
        for s in summaries:
            h, rws = read_csv_rows(s)
            for c in h:
                if c not in header:
                    header.append(c)
            tile_rows.extend(rws)
        print(f"{entry}: {len(manifest_rows)} tiles expected, {len(tile_rows)} run_summary rows "
              f"from {len(summaries)} Stage 2 output folder(s)")

        analysis_dirs = sorted({os.path.dirname(s) for s in summaries})
        stage2_failures, stage2_reasons, stage2_statuses = read_stage2_manifests(analysis_dirs)
        if stage2_failures:
            print(f"  Stage 2 reported {stage2_failures} per-image failure(s); "
                  f"status={','.join(sorted(set(stage2_statuses))) or 'unknown'}")

        seam_dup, n_cells = 0, 0
        if args.seam_counts == "report":
            by_section = {r["section_id"]: r for r in manifest_rows}
            centroids, unmatched = collect_cell_centroids(analysis_dirs, by_section)
            if unmatched:
                print(f"  note: {unmatched} __cells.csv file(s) could not be matched to a tile")
            seam_dup, n_cells = estimate_seam_duplicates(centroids, args.seam_merge_um)
            if n_cells:
                print(f"  seam diagnostic: {seam_dup} duplicate cell pair(s) of {n_cells} cells "
                      f"({100.0 * seam_dup / n_cells:.2f}%) within {args.seam_merge_um} um "
                      "across a tile boundary")

        rec, problems = aggregate_slide(entry, manifest_rows, header, tile_rows,
                                        stage1_by_stem.get(entry), seam_dup, n_cells,
                                        stage2_failures, stage2_reasons)
        for p in problems:
            print(f"  QC: {p}")
        all_problems.extend(f"{entry}: {p}" for p in problems)
        slide_rows.append(rec)

    if not slide_rows:
        sys.exit("ERROR: no slides aggregated.")

    blocking = [r for r in slide_rows if r["qc_status"] != "ok"]
    out_path = os.path.join(outdir, "slide_level_summary.csv")
    if blocking and not args.allow_incomplete:
        write_csv(os.path.join(outdir, "slide_level_summary.REJECTED.csv"), slide_rows)
        print("")
        print(f"REFUSING to write slide_level_summary.csv: {len(blocking)} slide(s) failed QC.")
        print("A slide with missing tiles still produces a plausible number, so this is fatal "
              "by default. Inspect slide_level_summary.REJECTED.csv, fix Stage 2, and rerun. "
              "Use --allow-incomplete only for diagnostics.")
        sys.exit(2)

    write_csv(out_path, slide_rows)
    print("")
    print(f"Wrote {len(slide_rows)} slide row(s) -> {out_path}")
    print("Next:  python3 aggregate_to_mouse.py " + out_path)
    print("Reminder: n = MICE. With one slide per mouse, n equals the number of slides, "
          "not the number of tiles.")


if __name__ == "__main__":
    main()
