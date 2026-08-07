#!/usr/bin/env python3
"""
spatial/build_point_pattern.py
=====================================================================
STAGE S1 of the spatial module: turn many per-tile `__cells.csv` files into ONE
de-duplicated, slide-global point pattern that spatial statistics can be run on.

Pipeline position
-----------------
  Stage 1  qupath_wsi_tile_export.groovy  -> tiles + tile_manifest.csv
  Stage 2  IF_Quant_Pipeline.groovy       -> per-tile __cells.csv (FROZEN)
  Stage 3  aggregate_tiles_to_slide.py    -> slide_level_summary.csv
  Stage S1 THIS SCRIPT                    -> <slide>__points.csv  (+ QC json)
  Stage S2 spatial_stats.py               -> spatial_summary.csv
  Stage S3 join_spatial_to_summary.py     -> *_with_spatial.csv
  Stage 4  aggregate_to_mouse.py          -> mouse_level / group_level  (UNCHANGED)

Why this stage exists
---------------------
Area endpoints tolerate the tile seam. Spatial statistics do not.

  * ImageJ's ParticleAnalyzer CLIPS a nucleus at the ROI edge rather than
    excluding it (aggregate_tiles_to_slide.py docstring, lines 31-36). A nucleus
    straddling a core boundary therefore appears as a fragment in EACH of the
    two neighbouring tiles. Measured rate on this study: 0.35% of cells.
  * 0.35% duplicates is invisible in a summed area. In a pair-correlation
    function it is a catastrophe: each duplicate is a pair separated by 1-3 um,
    i.e. an excess of pairs concentrated entirely in the first radius bin. With
    ~2e6 cells, 0.35% duplicates inject ~7000 pairs at r < 4 um where the CSR
    expectation over a whole slide is a few hundred. g(r->0) is inflated by an
    order of magnitude and the inflation is spatially organised along seam
    lines, so it reads as real clustering.
  * The clipped fragment's centroid is displaced toward the interior of its own
    tile, by up to half a nuclear radius.

What this script guarantees
---------------------------
  1. ONE definition of the tile-local -> slide-global transform. The arithmetic
     is `_tile_origin_um()` below, and a startup self-check re-runs
     `aggregate_tiles_to_slide.collect_cell_centroids()` on the same inputs and
     aborts if the two ever disagree by more than 1e-9 um. Stage 3's seam
     diagnostic and this stage can therefore never drift apart.
  2. Every retained cell is proved to lie inside its own tile's CORE rectangle.
     A violation means the per-tile `<stem>.ome_RoiSet.zip` was not applied and
     the measurement covered the halo -- fatal, not a warning.
  3. Duplicate resolution is geometric AND topological: candidates must sit in
     the seam band of a SHARED core edge, not merely be close. Two genuinely
     distinct nuclei 3 um apart in the middle of a tile are never merged.
  4. Merged fragments are replaced by their AREA-WEIGHTED centroid, which
     recovers the unclipped nucleus centre to first order.

Output
------
  <outdir>/<slide>__points.csv   one row per RETAINED cell, slide-global um,
                                 carrying every identity/call column needed
                                 downstream, plus:
      x_um, y_um                 slide-global calibrated centroid
      seam_merge_n               1 = untouched, >=2 = fragments merged
      seam_band                  1 = centroid within --seam-band-um of its core edge
      src_section_id             the tile the representative fragment came from
  <outdir>/<slide>__pointpattern_qc.json

Standard library + numpy. numpy is used only for the union-find pair search.
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

_HERE = os.path.dirname(os.path.abspath(__file__))
# The repo root is the parent of spatial/ once this module is installed there.
_REPO = os.path.dirname(_HERE)
for p in (_REPO, _HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

try:
    from aggregate_to_mouse import _num
    import aggregate_tiles_to_slide as ats
except ImportError:
    sys.exit("ERROR: spatial/ must sit inside the IFQuant-Lung repo beside "
             "aggregate_to_mouse.py and aggregate_tiles_to_slide.py. Their "
             "coordinate and pooling logic is REUSED, not reimplemented.")


# Columns carried through to the point pattern regardless of panel.
IDENTITY_COLS = ["image", "output_key", "panel", "region", "compartment",
                 "region_tags", "cell_id", "mouse_id", "section_id",
                 "genotype", "condition"]
GEOMETRY_COLS = ["centroid_x_um", "centroid_y_um", "nucleus_area_um2"]


def read_csv_rows(path):
    return ats.read_csv_rows(path)


def _tile_origin_um(tm):
    """THE tile-local -> slide-global origin, in microns.

    Identical to aggregate_tiles_to_slide.collect_cell_centroids(), lines
    163-165:  ox = export_x * pixel_size_um ;  oy = export_y * pixel_size_um.
    The engine writes centroid_x_um / centroid_y_um relative to the EXPORTED
    tile origin (which includes the halo), not to the core.
    """
    px = float(tm["pixel_size_um"])
    return float(tm["export_x"]) * px, float(tm["export_y"]) * px, px


def _core_rect_um(tm):
    px = float(tm["pixel_size_um"])
    x0 = float(tm["core_x"]) * px
    y0 = float(tm["core_y"]) * px
    return x0, y0, x0 + float(tm["core_w"]) * px, y0 + float(tm["core_h"]) * px


def _section_for_folder(folder, sections):
    """Same folder -> section_id matching rule as
    aggregate_tiles_to_slide.collect_cell_centroids() (lines 152-158)."""
    for sec in sections:
        if folder.endswith("_" + sec) or ("_" + sec + "__") in folder:
            return sec
    return None


def collect_cell_records(analysis_dirs, manifest_by_section):
    """Read every per-tile __cells.csv and return slide-global cell records."""
    records = []
    unmatched_files = 0
    header_union = []
    paths = []
    for d in analysis_dirs:
        paths.extend(glob.glob(os.path.join(d, "**", "*__cells.csv"), recursive=True))
    for cells_path in sorted(set(paths)):
        folder = os.path.basename(os.path.dirname(cells_path))
        section = _section_for_folder(folder, manifest_by_section)
        if section is None:
            unmatched_files += 1
            continue
        tm = manifest_by_section[section]
        ox, oy, px = _tile_origin_um(tm)
        cx0, cy0, cx1, cy1 = _core_rect_um(tm)
        header, rows = read_csv_rows(cells_path)
        for c in header:
            if c not in header_union:
                header_union.append(c)
        for r in rows:
            lx, ly = _num(r.get("centroid_x_um")), _num(r.get("centroid_y_um"))
            if lx is None or ly is None:
                continue
            rec = dict(r)
            rec["x_um"] = lx + ox
            rec["y_um"] = ly + oy
            rec["src_section_id"] = section
            rec["_core"] = (cx0, cy0, cx1, cy1)
            rec["_area"] = _num(r.get("nucleus_area_um2")) or 0.0
            records.append(rec)
    return records, unmatched_files, header_union


def selfcheck_against_stage3(records, analysis_dirs, manifest_by_section):
    """Abort if this module's global coordinates ever drift from Stage 3's.

    This is the anti-fork guard for geometry: exactly as aggregate_to_mouse
    owns the single definition of pooling, aggregate_tiles_to_slide owns the
    single definition of the tile origin.
    """
    ref, _ = ats.collect_cell_centroids(analysis_dirs, manifest_by_section)
    if len(ref) != len(records):
        sys.exit(f"ERROR: coordinate self-check failed -- Stage 3 read {len(ref)} "
                 f"centroids, this stage read {len(records)}. The two must read the "
                 "same __cells.csv files. Do not proceed.")
    mine = sorted((r["x_um"], r["y_um"], r["src_section_id"]) for r in records)
    theirs = sorted(ref)
    worst = 0.0
    for (ax, ay, asec), (bx, by, bsec) in zip(mine, theirs):
        if asec != bsec:
            sys.exit("ERROR: coordinate self-check failed -- section assignment "
                     f"differs ({asec} vs {bsec}).")
        worst = max(worst, abs(ax - bx), abs(ay - by))
    if worst > 1e-9:
        sys.exit(f"ERROR: coordinate self-check failed -- max |delta| = {worst:g} um "
                 "between this stage and aggregate_tiles_to_slide.collect_cell_centroids(). "
                 "There must be exactly ONE tile-origin definition.")
    return worst


def check_core_containment(records, tol_um):
    """Every cell must lie inside its own tile CORE. Halo cells mean the
    per-tile RoiSet was not applied -- the same failure Stage 3 detects as an
    area mismatch (aggregate_tiles_to_slide.py lines 351-357), caught here per
    cell instead of in aggregate."""
    bad = []
    for r in records:
        x0, y0, x1, y1 = r["_core"]
        if not (x0 - tol_um <= r["x_um"] <= x1 + tol_um and
                y0 - tol_um <= r["y_um"] <= y1 + tol_um):
            bad.append(r)
    return bad


def _seam_band_distance(rec):
    """Distance from a cell to the nearest edge of ITS OWN core rectangle."""
    x0, y0, x1, y1 = rec["_core"]
    return min(rec["x_um"] - x0, x1 - rec["x_um"],
               rec["y_um"] - y0, y1 - rec["y_um"])


class _DSU:
    def __init__(self, n):
        self.p = list(range(n))

    def find(self, a):
        while self.p[a] != a:
            self.p[a] = self.p[self.p[a]]
            a = self.p[a]
        return a

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.p[rb] = ra


def find_seam_duplicates(records, merge_um, band_um):
    """Cluster cross-tile fragments of the same physical nucleus.

    A pair (i, j) is a duplicate candidate only when ALL of:
      * different src_section_id  (same tile -> two real nuclei, never merged)
      * both centroids within band_um of their own core boundary
      * their two core rectangles TOUCH, and the pair STRADDLES every shared
        boundary line between them (both lines, for a diagonal/corner pair)
      * separation <= merge_um
    The straddle test is what separates this from a plain proximity merge. Two
    nuclei sitting side by side on the SAME side of a seam are never fused, no
    matter how close, because one nucleus cannot be clipped into two fragments
    that lie on one side of the cut.

    A chance false-merge rate is estimated at the same time, from the annulus
    (merge_um, 2*merge_um]. Under a locally homogeneous chance model the disc of
    radius merge_um has one third the area of that annulus, so
        expected chance pairs within merge_um  ~=  n_annulus_pairs / 3.
    True fragments of one clipped nucleus concentrate at separations of 0-3 um
    and do not populate the annulus, so this estimate is a usable upper bound on
    how many merges were coincidence. It is reported, never subtracted.
    """
    idx_band = [i for i, r in enumerate(records) if _seam_band_distance(r) <= band_um]
    grid = defaultdict(list)
    cell = max(2.0 * merge_um, 1e-6)
    for i in idx_band:
        r = records[i]
        grid[(int(math.floor(r["x_um"] / cell)), int(math.floor(r["y_um"] / cell)))].append(i)

    dsu = _DSU(len(records))
    d2 = merge_um * merge_um
    d2_outer = 4.0 * d2
    n_pairs = 0
    n_annulus = 0
    for (gx, gy), idxs in grid.items():
        neigh = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                neigh.extend(grid.get((gx + dx, gy + dy), ()))
        for i in idxs:
            ri = records[i]
            for j in neigh:
                if j <= i:
                    continue
                rj = records[j]
                if ri["src_section_id"] == rj["src_section_id"]:
                    continue
                dx = ri["x_um"] - rj["x_um"]
                dy = ri["y_um"] - rj["y_um"]
                sep2 = dx * dx + dy * dy
                if sep2 > d2_outer:
                    continue
                if not _straddles_shared_seam(ri, rj, merge_um):
                    continue
                if sep2 <= d2:
                    dsu.union(i, j)
                    n_pairs += 1
                else:
                    n_annulus += 1

    clusters = defaultdict(list)
    for i in range(len(records)):
        clusters[dsu.find(i)].append(i)
    return ({root: members for root, members in clusters.items() if len(members) > 1},
            n_pairs, n_annulus / 3.0)


def _shared_seam_lines(a, b, tol_um):
    """The core-rectangle boundary line(s) two tiles have in common."""
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    if not (ax0 - tol_um <= bx1 and bx0 - tol_um <= ax1 and
            ay0 - tol_um <= by1 and by0 - tol_um <= ay1):
        return []
    lines = []
    if abs(ax1 - bx0) <= tol_um:
        lines.append(("x", ax1))
    if abs(bx1 - ax0) <= tol_um:
        lines.append(("x", ax0))
    if abs(ay1 - by0) <= tol_um:
        lines.append(("y", ay1))
    if abs(by1 - ay0) <= tol_um:
        lines.append(("y", ay0))
    return lines


def _straddles_shared_seam(ri, rj, tol_um):
    lines = _shared_seam_lines(ri["_core"], rj["_core"], tol_um)
    if not lines:
        return False
    for axis, v in lines:
        key = "x_um" if axis == "x" else "y_um"
        si, sj = ri[key] - v, rj[key] - v
        if si * sj > 0 and min(abs(si), abs(sj)) > tol_um:
            return False        # both on the same side of a shared cut
    return True


def merge_clusters(records, clusters):
    """Collapse each fragment cluster to one representative.

    Representative = largest nucleus_area_um2 (the least-clipped fragment).
    Ties broken deterministically by (src_section_id, region, cell_id) so the
    result does not depend on file iteration order.
    Position = AREA-WEIGHTED centroid of the fragments, which reconstructs the
    unclipped nucleus centre to first order; nucleus_area_um2 becomes the SUM of
    the fragment areas.
    """
    drop = set()
    shifts = []
    for members in clusters.values():
        def _key(i):
            r = records[i]
            return (-r["_area"], str(r.get("src_section_id")),
                    str(r.get("region")), str(r.get("cell_id")))
        members = sorted(members, key=_key)
        keep, rest = members[0], members[1:]
        wsum = sum(records[i]["_area"] for i in members)
        if wsum > 0:
            nx = sum(records[i]["_area"] * records[i]["x_um"] for i in members) / wsum
            ny = sum(records[i]["_area"] * records[i]["y_um"] for i in members) / wsum
        else:
            nx = sum(records[i]["x_um"] for i in members) / len(members)
            ny = sum(records[i]["y_um"] for i in members) / len(members)
        shifts.append(math.hypot(nx - records[keep]["x_um"], ny - records[keep]["y_um"]))
        records[keep]["x_um"], records[keep]["y_um"] = nx, ny
        records[keep]["nucleus_area_um2"] = wsum
        records[keep]["seam_merge_n"] = len(members)
        drop.update(rest)
    return drop, shifts


def main():
    ap = argparse.ArgumentParser(
        description="Build a de-duplicated slide-global point pattern from per-tile __cells.csv.")
    ap.add_argument("--slide-dir", required=True,
                    help="one Stage 1 slide folder (contains tile_manifest.csv and analysis*/)")
    ap.add_argument("--outdir", default=None, help="default: <slide-dir>/spatial")
    ap.add_argument("--seam-merge-um", type=float, default=4.0,
                    help="max separation for two cross-tile fragments of one nucleus "
                         "(default 4.0, the SAME default aggregate_tiles_to_slide.py uses)")
    ap.add_argument("--seam-band-um", type=float, default=6.0,
                    help="a fragment must lie within this distance of its own core edge "
                         "to be a merge candidate (default 6.0 ~ one nuclear radius)")
    ap.add_argument("--core-tolerance-um", type=float, default=1.0,
                    help="allowed overshoot of the core rectangle before failing (default 1.0)")
    ap.add_argument("--allow-halo-cells", action="store_true",
                    help="downgrade the core-containment failure to a warning (DIAGNOSTIC ONLY: "
                         "cells measured in the halo are double counted by construction)")
    args = ap.parse_args()

    slide_dir = os.path.abspath(args.slide_dir)
    slide = os.path.basename(slide_dir.rstrip(os.sep))
    tm_path = os.path.join(slide_dir, "tile_manifest.csv")
    if not os.path.isfile(tm_path):
        sys.exit(f"ERROR: {tm_path} not found. --slide-dir must be a Stage 1 slide folder.")
    _, manifest_rows = read_csv_rows(tm_path)
    if not manifest_rows:
        sys.exit(f"ERROR: {tm_path} has no rows.")
    for need in ("section_id", "export_x", "export_y", "pixel_size_um",
                 "core_x", "core_y", "core_w", "core_h"):
        if need not in manifest_rows[0]:
            sys.exit(f"ERROR: tile_manifest.csv lacks required column '{need}'. "
                     "Re-export with the current qupath_wsi_tile_export.groovy.")
    by_section = {r["section_id"]: r for r in manifest_rows}

    summaries = ats.find_run_summaries(slide_dir)
    if not summaries:
        sys.exit(f"ERROR: no run_summary.csv under {slide_dir}; Stage 2 has not run.")
    analysis_dirs = sorted({os.path.dirname(s) for s in summaries})

    records, unmatched, header_union = collect_cell_records(analysis_dirs, by_section)
    if not records:
        sys.exit("ERROR: no per-cell rows found. Spatial statistics need __cells.csv, "
                 "not just run_summary.csv.")
    n_raw = len(records)
    print(f"{slide}: {n_raw} cell rows from {len(analysis_dirs)} Stage 2 folder(s), "
          f"{len(by_section)} tiles")
    if unmatched:
        print(f"  note: {unmatched} __cells.csv file(s) could not be matched to a tile")

    worst = selfcheck_against_stage3(records, analysis_dirs, by_section)
    print(f"  coordinate self-check vs aggregate_tiles_to_slide: max delta {worst:.3g} um  OK")

    halo = check_core_containment(records, args.core_tolerance_um)
    if halo:
        msg = (f"{len(halo)} of {n_raw} cells lie OUTSIDE their own tile core rectangle. "
               "The per-tile '<stem>.ome_RoiSet.zip' was not applied, so halo regions were "
               "measured and every halo cell is counted twice.")
        if not args.allow_halo_cells:
            sys.exit("ERROR: " + msg + " Refusing to build a point pattern.")
        print("WARNING: " + msg)

    clusters, n_pairs, chance = find_seam_duplicates(
        records, args.seam_merge_um, args.seam_band_um)
    for r in records:
        r.setdefault("seam_merge_n", 1)
    drop, shifts = merge_clusters(records, clusters)
    kept = [r for i, r in enumerate(records) if i not in drop]
    n_multi = sum(1 for m in clusters.values() if len(m) > 2)

    for r in kept:
        r["seam_band"] = 1 if _seam_band_distance(r) <= args.seam_band_um else 0
        r.pop("_core", None)
        r.pop("_area", None)

    outdir = args.outdir or os.path.join(slide_dir, "spatial")
    os.makedirs(outdir, exist_ok=True)
    out_csv = os.path.join(outdir, f"{slide}__points.csv")

    cols = []
    for c in header_union + ["x_um", "y_um", "src_section_id", "seam_merge_n", "seam_band"]:
        if c not in cols:
            cols.append(c)
    with open(out_csv, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in kept:
            w.writerow(r)

    qc = {
        "slide": slide,
        "n_cell_rows_read": n_raw,
        "n_cells_retained": len(kept),
        "n_cells_dropped_as_seam_duplicates": len(drop),
        "seam_duplicate_fraction": (len(drop) / n_raw) if n_raw else 0.0,
        "n_duplicate_clusters": len(clusters),
        "n_clusters_size_ge_3": n_multi,
        "n_candidate_pairs": n_pairs,
        "estimated_chance_merge_pairs": chance,
        "estimated_chance_merge_fraction_of_pairs": (chance / n_pairs) if n_pairs else 0.0,
        "max_centroid_shift_um": max(shifts) if shifts else 0.0,
        "mean_centroid_shift_um": (sum(shifts) / len(shifts)) if shifts else 0.0,
        "seam_merge_um": args.seam_merge_um,
        "seam_band_um": args.seam_band_um,
        "n_cells_outside_core": len(halo),
        "coordinate_selfcheck_max_delta_um": worst,
        "n_tiles": len(by_section),
        "coordinate_transform": "global_um = export_origin_px * pixel_size_um + local_um "
                                "(aggregate_tiles_to_slide.collect_cell_centroids)",
    }
    with open(os.path.join(outdir, f"{slide}__pointpattern_qc.json"), "w", encoding="utf-8") as fh:
        json.dump(qc, fh, indent=2)

    print(f"  seam: {len(drop)} duplicate fragment(s) merged into {len(clusters)} nuclei "
          f"({100.0 * len(drop) / n_raw:.2f}% of rows); {n_multi} cluster(s) of >=3 "
          f"(tile corners); mean centroid correction {qc['mean_centroid_shift_um']:.2f} um")
    print(f"  seam false-merge estimate: ~{chance:.1f} of {n_pairs} merged pair(s) are "
          f"coincidence ({100.0 * chance / n_pairs if n_pairs else 0.0:.1f}%), from the "
          f"({args.seam_merge_um:g}, {2 * args.seam_merge_um:g}] um annulus")
    if n_pairs and chance / n_pairs > 0.2:
        print("  WARNING: more than 20% of seam merges look like coincidence. Lower "
              "--seam-merge-um, or accept that cell COUNTS near seams carry that error.")
    print(f"  wrote {len(kept)} points -> {out_csv}")
    print("  NOTE: tile core edges are now INTERNAL to one slide-global pattern. "
          "Edge correction downstream must use the SLIDE window only; per-tile "
          "spatial statistics are invalid.")


if __name__ == "__main__":
    main()
