#!/usr/bin/env python3
"""
spatial/spatial_stats.py
=====================================================================
STAGE S2 of the spatial module: one de-duplicated slide-global point pattern
-> one row of ADDITIVE SUFFICIENT STATISTICS per (image, region, section_id,
panel), in a schema that aggregate_to_mouse.py already knows how to pool.

THE NON-FORKING RULE
--------------------
aggregate_to_mouse.py must not change, and there must not be a second
definition of "mouse". This module therefore never emits a mean, a median, a
ratio, or a p-value as its output. It emits only quantities that are SUMS, with
column names that fall into aggregate_to_mouse.classify_columns()'s existing
suffix categories:

    class_<NAME>_count        -> summed; mouse level gets
                                 class_<NAME>_count_total   (SUM)
                                 class_<NAME>_density_per_mm2 (RECOMPUTE)
                                 (aggregate_to_mouse.py lines 171-175, 335-338)
    <NAME>_positive_area_um2  -> summed; mouse level gets
                                 <NAME>_positive_area_um2_total (SUM)
                                 <NAME>_positive_area_fraction  (RECOMPUTE,
                                 denominator = pooled region_area_um2)
                                 (lines 164, 315-322)

Every spatial ENDPOINT is a ratio of two of those sums, and the ratio is formed
ONLY after pooling, in spatial_mouse_metrics.py, from mouse_level_summary.csv.
That is the same "sum the numerators, sum the denominators, divide last"
discipline the repo already applies to pod area fraction. It is the reason two
sections of unequal size cannot be averaged into a wrong answer, and the reason
n stays equal to the number of MICE.

Consequence, stated plainly: a median nearest-neighbour distance is NOT
additive and is therefore never emitted per slide. What is emitted is the
nearest-neighbour distance HISTOGRAM (counts per fixed bin, plus the
reduced-sample denominator per bin). Histograms add. The pooled median is
recovered from the pooled histogram at mouse level.

THE MEASURES
------------
1. Cross-type nearest-neighbour distance distribution  G_AB(r)
   "How far is a cell of class A from the nearest cell of class B?"
   Reduced-sample (border) estimator:
       G(r) = |{i in A : d_i <= r AND b_i >= r}| / |{i in A : b_i >= r}|
   where b_i is the distance from i to the window boundary. A cell closer to
   the boundary than r may have its true nearest B outside the imaged tissue,
   so it is removed from BOTH numerator and denominator at that r rather than
   being allowed to contribute a censored distance. Numerator and denominator
   are both counts -> both SUM.

2. Local neighbourhood composition within radius r
   "Of the cells surrounding a KRT5+ pod cell, what fraction are AGER+?"
   Sum over eligible focal cells (b_i >= r) of the number of class-B cells
   within r, and of the total number of cells within r. Both SUM. The ratio is
   formed at mouse level.
   The random-labelling NULL expectation is computed ANALYTICALLY, not by
   simulation: under permutation of the class label within the eligible pool,
       E[#B within r of focal i] = n_total_within_r(i) * (N_B - [i in B]) / (N_pool - 1)
   Summing that expectation over focal cells gives an additive expected count.
   observed/expected pools correctly and needs no Monte Carlo.

3. Ripley's cross-K, L and pair correlation g(r), TRANSLATION corrected
   K_AB(r) = (1/lambda_B) E[#B within r of a typical A]. Estimator:
       S(r) = sum_{i in A} sum_{j in B, j != i} 1(d_ij <= r) * w_ij
       w_ij = |W| / gamma(x_j - x_i)        (Ohser-Stoyan translation weight)
       D    = n_A * n_B / |W|
       K(r) = S(r) / D
   S(r) and D are both additive across slides and mice, so the pooled
   estimator is sum(S)/sum(D) -- the standard ratio-unbiased pooled estimator
   for replicated point patterns. L(r) = sqrt(K(r)/pi); the interpretable
   statistic is L(r) - r. g(r) uses the annulus form of S.

   EDGE CORRECTION -- why it matters and which one:
     * No correction: every point's r-disc is partly outside the tissue, so
       neighbour counts are biased DOWN. Lung parenchyma is lacy; the tissue is
       ~20-27% of the slide canvas with an enormous perimeter-to-area ratio, so
       at r = 50 um a large share of every disc is off-tissue. The bias is not
       a nuisance, it is most of the signal.
     * "Ripley isotropic" as implemented by most quick code uses the closed-form
       RECTANGLE arc formulas. Applied to a tissue pattern it silently uses the
       bounding box as the window, giving a correction of ~1 everywhere: it is
       identical to no correction while appearing rigorous. This is the classic
       failure and this module refuses to implement it.
     * Border / reduced-sample: unbiased and assumption-free, but on lacy
       parenchyma nearly every point sits within 40 um of a tissue edge, so it
       discards most of the pattern at the radii of interest. Emitted here as a
       QC comparator (S_border, D_border) only.
     * Translation (Ohser-Stoyan): exact for an arbitrary, multiply connected
       window given its set covariance, and the covariance of a raster window
       is one FFT (spatial_core.TranslationCorrector). PRIMARY.

4. Distance-to-structure, area-normalised
   "Are AGER+ cells enriched near KRT5 pods?" -- with the only honest
   denominator, which is how much tissue is near a pod at all.
   Emits, per fixed distance bin:
       cells   : class_<A>_to_<S>_<bin>_count           (SUM)
       tissue  : <S>_<bin>_positive_area_um2            (SUM)
   Mouse-level enrichment = (cells in bin / all cells) / (area in bin / all
   area). A KO mouse with more damaged tissue near pods does NOT get a spurious
   enrichment, because the extra tissue is in the denominator.

THE NULL MODEL
--------------
Cells can only sit on tissue, so "random" cannot mean uniform on a rectangle.

  WRONG, and all three appear in published tissue analyses:
    - CSR over the bounding box. The bounding box is ~4x the tissue area and
      mostly glass. Every real pattern is then "clustered" at every radius.
    - CSR over the convex hull. A lung section's convex hull includes the
      airspace and the space between lobes.
    - Toroidal / periodic shift. It assumes a rectangular homogeneous window.
      Wrapping a lung section onto a torus glues pleura to bronchus.

  PRIMARY NULL -- RANDOM LABELLING (label permutation).
    Hold the complete set of nuclei EXACTLY where it is; that set already
    encodes the tissue geometry, the local cell density, the section thickness
    and the segmentation quality. Permute only the class label among the
    declared eligible pool. This tests "given where cells are, is the KRT5+ (or
    CD8+) label spatially associated with the structure?", which is the
    question the lab is actually asking. It is invariant to everything about
    tissue architecture, which no CSR null is.
    For measure 2 the null expectation is available in closed form
    (hypergeometric), so it is emitted as an additive expected count and no
    simulation is needed. For measure 4 the permutation test is exact and cheap
    -- the per-cell distances to the structure are fixed, so permuting labels
    is just drawing n_A cells from the eligible pool without replacement and
    re-histogramming their distances (--n-permutations, default 999). The
    resulting p-value is a WITHIN-SLIDE QC number, written as a plain metadata
    column that aggregate_to_mouse.py deliberately does NOT carry: it answers
    "is the association non-random in THIS animal", and must never be used as
    the group test. For measure 3 no permutation test is implemented in this
    draft; the translation-corrected K is reported against the pooled-pattern
    K under random labelling, which is its own reference.

  SECONDARY NULL -- inhomogeneous CSR conditioned on the window mask,
    used only when the question is about the STRUCTURE rather than the labels
    (e.g. "is this much tissue near a pod more than you would get by placing
    pods at random?"). Points are drawn uniformly on the window RASTER by
    rejection, never on a bounding box.

STATISTICAL UNIT
----------------
n = MICE. Per-slide rows here are sufficient statistics, not results. They are
summed to the mouse by aggregate_to_mouse.py and only then divided. Testing
across cells (n ~ 1e6) or across tiles/sections (pseudoreplication) is invalid
and this module deliberately makes it inconvenient: it emits no per-cell and no
per-tile statistic that could be fed to a t-test.

Dependencies: numpy (verified). scipy is NOT available and is not used.
tifffile is optional and only for engine pod masks.
=====================================================================
"""
import argparse
import csv
import json
import math
import os
import sys

import numpy as np

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
for p in (_REPO, _HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

from spatial_core import (Raster, TranslationCorrector, UniformGrid, bin_label,  # noqa: E402
                          dilate_binary, edt_capped, histogram_counts,
                          rasterise_points, weighted_histogram)

try:
    from aggregate_to_mouse import KEY_COLS, ROW_ID_COLS, _num
except ImportError:
    sys.exit("ERROR: spatial/ must sit inside the IFQuant-Lung repo beside "
             "aggregate_to_mouse.py; its KEY_COLS/ROW_ID_COLS are the single "
             "definition of row and mouse identity.")

try:
    import tifffile as _tiff
except ImportError:
    _tiff = None


# --------------------------------------------------------------------------
# Class predicates over the engine's per-cell columns
# --------------------------------------------------------------------------
def class_membership(rows, spec):
    """Return (member, evaluable) boolean arrays.

    THREE-STATE CALLS ARE RESPECTED. IF_Quant_Pipeline.groovy writes
    <MARKER>_final_call as 1, 0 or "" (empty = indeterminate; line 2532), and
    class_<KEY> as 1, 0 or "" (line 2557). An indeterminate cell is NOT a
    negative: it is excluded from the class and from the eligible pool, and the
    exclusion is counted. Treating "" as 0 would convert every unresolved
    anatomy call into evidence of absence.
    """
    n = len(rows)
    member = np.zeros(n, dtype=bool)
    evaluable = np.zeros(n, dtype=bool)
    kind = spec["kind"]
    if kind == "all":
        member[:] = True
        evaluable[:] = True
        return member, evaluable
    if kind == "call":
        col = spec["marker"] + "_final_call"
        want = int(spec.get("want", 1))
    elif kind == "class_col":
        col = spec["column"]
        want = int(spec.get("want", 1))
    else:
        raise ValueError(f"unknown class kind '{kind}'")
    if col not in rows[0]:
        raise KeyError(f"per-cell column '{col}' not present. Available example "
                       f"columns: {sorted(rows[0])[:12]} ...")
    for i, r in enumerate(rows):
        v = (r.get(col) or "").strip()
        if v == "":
            continue
        evaluable[i] = True
        member[i] = (int(float(v)) == want)
    return member, evaluable


# --------------------------------------------------------------------------
# Window
# --------------------------------------------------------------------------
def build_window(x, y, cfg):
    mode = cfg.get("mode", "nucleus_support")
    if mode != "nucleus_support":
        raise ValueError(f"window mode '{mode}' not implemented in this draft. "
                         "Supply a real tissue mask via a future --window-mask "
                         "rather than substituting a bounding box.")
    px = float(cfg.get("raster_um", 2.0))
    dil = float(cfg["dilate_um"])
    pad = dil * 2.0
    pts = rasterise_points(x, y, px, pad_um=pad)
    win = dilate_binary(pts.mask, dil / px)
    return Raster(win, pts.origin_x_um, pts.origin_y_um, px)


def boundary_distance_um(window, x, y, cap_um):
    """Distance from each point to the nearest NON-window pixel, in um."""
    cap_px = max(1.0, cap_um / window.px_um)
    outside = ~window.mask
    if not outside.any():
        # A completely filled raster has no boundary inside it; the frame is.
        outside = np.zeros_like(window.mask)
        outside[0, :] = outside[-1, :] = outside[:, 0] = outside[:, -1] = True
    d = edt_capped(outside, cap_px) * window.px_um
    vals, ok = window.sample(d, x, y, outside_value=0.0)
    return vals, ok


# --------------------------------------------------------------------------
# Structures
# --------------------------------------------------------------------------
def structure_raster_from_cells(x, y, member, dilate_um, template):
    if member.sum() == 0:
        return None
    px = template.px_um
    m = np.zeros_like(template.mask)
    row, col = template.to_px(x[member], y[member])
    ok = template.inside(row, col)
    m[row[ok], col[ok]] = True
    return Raster(dilate_binary(m, dilate_um / px), template.origin_x_um,
                  template.origin_y_um, px)


def structure_raster_from_engine_masks(slide_dir, mask_suffix, manifest_by_section,
                                       template, analysis_dirs):
    """Stitch the engine's per-tile binary masks into the slide window raster.

    IF_Quant_Pipeline.groovy line 2782 writes
        <fileKey>__<MARKER>_pod_mask.tif
    per analysed tile, at the tile's own calibration and FULL tile extent
    (including the halo). Only the CORE part is used, so the halo cannot
    contribute a structure twice.
    """
    if _tiff is None:
        raise RuntimeError("tifffile is not importable in this interpreter, so the "
                           "engine's pod masks cannot be read. Either install "
                           "tifffile or declare the cell_class_dilation fallback "
                           "explicitly in the profile -- the fallback is a PROXY "
                           "and its rows are stamped as such.")
    import glob as _glob
    out = np.zeros_like(template.mask)
    n_used = 0
    paths = []
    for d in analysis_dirs:
        paths.extend(_glob.glob(os.path.join(d, "**", "*" + mask_suffix), recursive=True))
    for p in sorted(set(paths)):
        folder = os.path.basename(os.path.dirname(p))
        sec = None
        for s in manifest_by_section:
            if folder.endswith("_" + s) or ("_" + s + "__") in folder:
                sec = s
                break
        if sec is None:
            continue
        tm = manifest_by_section[sec]
        pxs = float(tm["pixel_size_um"])
        ox, oy = float(tm["export_x"]) * pxs, float(tm["export_y"]) * pxs
        cx0, cy0 = float(tm["core_x"]) * pxs, float(tm["core_y"]) * pxs
        cx1 = cx0 + float(tm["core_w"]) * pxs
        cy1 = cy0 + float(tm["core_h"]) * pxs
        arr = _tiff.imread(p)
        if arr.ndim != 2:
            raise ValueError(f"{p}: expected a 2D binary mask, got shape {arr.shape}")
        yy, xx = np.nonzero(arr > 0)
        if yy.size == 0:
            n_used += 1
            continue
        gx = ox + (xx + 0.5) * pxs
        gy = oy + (yy + 0.5) * pxs
        keep = (gx >= cx0) & (gx < cx1) & (gy >= cy0) & (gy < cy1)
        row, col = template.to_px(gx[keep], gy[keep])
        ok = template.inside(row, col)
        out[row[ok], col[ok]] = True
        n_used += 1
    if n_used == 0:
        raise RuntimeError(f"no '*{mask_suffix}' masks found under the Stage 2 output "
                           "folders; refusing to invent a structure.")
    return Raster(out, template.origin_x_um, template.origin_y_um, template.px_um), n_used


# --------------------------------------------------------------------------
# Measures
# --------------------------------------------------------------------------
def measure_nn(rec, tag, xa, ya, ba, xb, yb, edges):
    """Cross-type nearest-neighbour, reduced-sample (border) estimator."""
    rmax = float(edges[-1])
    if xa.size == 0 or xb.size == 0:
        for k in range(len(edges) - 1):
            lab = bin_label(edges[k], edges[k + 1])
            rec[f"class_{tag}_nn_{lab}_count"] = 0
            rec[f"class_{tag}_nn_{lab}_eligible_count"] = 0
        return
    grid = UniformGrid(xb, yb, rmax)
    nn = np.full(xa.size, np.inf)
    for qi, cj, d2 in grid.query_buckets(xa, ya):
        if cj.size == 0:
            continue
        nn[qi] = np.minimum(nn[qi], np.sqrt(d2.min(axis=1)))
    for k in range(len(edges) - 1):
        r = float(edges[k + 1])
        lab = bin_label(edges[k], edges[k + 1])
        eligible = ba >= r
        rec[f"class_{tag}_nn_{lab}_count"] = int(((nn <= r) & eligible).sum())
        rec[f"class_{tag}_nn_{lab}_eligible_count"] = int(eligible.sum())


def measure_neighbourhood(rec, tag, xf, yf, bf, focal_is_b, xall, yall, is_b_all,
                          radius, n_pool, n_b_pool):
    """Composition within radius r, with the analytic random-labelling null."""
    lab = f"r{radius:g}um".replace(".", "p")
    grid = UniformGrid(xall, yall, float(radius))
    tot = np.zeros(xf.size, dtype=np.int64)
    hit = np.zeros(xf.size, dtype=np.int64)
    r2 = float(radius) * float(radius)
    for qi, cj, d2 in grid.query_buckets(xf, yf):
        if cj.size == 0:
            continue
        within = d2 <= r2
        tot[qi] += within.sum(axis=1)
        hit[qi] += (within & is_b_all[cj][None, :]).sum(axis=1)
    # remove self-matches (a focal cell is in the all-cells grid)
    tot -= 1
    hit -= focal_is_b.astype(np.int64)
    eligible = bf >= float(radius)
    n_tot = int(tot[eligible].sum())
    n_hit = int(hit[eligible].sum())
    rec[f"class_{tag}_nbr_{lab}_neighbour_count"] = n_hit
    rec[f"class_{tag}_nbr_{lab}_total_neighbour_count"] = n_tot
    rec[f"class_{tag}_nbr_{lab}_focal_count"] = int(eligible.sum())
    # E[#B in a neighbourhood of size m] under label permutation within the pool
    if n_pool > 1:
        p_other = (n_b_pool - focal_is_b[eligible].astype(np.float64)) / float(n_pool - 1)
        exp_hit = float((tot[eligible].astype(np.float64) * p_other).sum())
    else:
        exp_hit = 0.0
    rec[f"class_{tag}_nbr_{lab}_expected_neighbour_count"] = exp_hit


def measure_ripley(rec, tag, xa, ya, ba, xb, yb, edges, window, corrector,
                   n_a_full, n_b_full, query_fraction):
    """Cross-K sufficient statistics, translation (primary) + border (QC)."""
    rmax = float(edges[-1])
    area = window.area_um2()
    nbins = len(edges) - 1
    s_tr = np.zeros(nbins)
    s_bd = np.zeros(nbins)
    if xa.size and xb.size:
        grid = UniformGrid(xb, yb, rmax)
        for qi, cj, d2 in grid.query_buckets(xa, ya):
            if cj.size == 0:
                continue
            d = np.sqrt(d2)
            sel = (d > 0.0) & (d <= rmax)
            if not sel.any():
                continue
            ii, jj = np.nonzero(sel)
            dv = d[ii, jj]
            dx = xb[cj[jj]] - xa[qi[ii]]
            dy = yb[cj[jj]] - ya[qi[ii]]
            w = corrector.weights(dx, dy)
            s_tr += weighted_histogram(dv, w, edges)
            # border: keep only pairs whose focal point is >= r from the boundary
            bsel = ba[qi[ii]]
            for k in range(nbins):
                r = float(edges[k + 1])
                m = (dv <= r) & (bsel >= r)
                s_bd[k] += float(m.sum())
        # translation S is cumulative only if histogrammed cumulatively
        s_tr = np.cumsum(s_tr)

    d_tr = (n_a_full * n_b_full / area) if area > 0 else 0.0
    for k in range(nbins):
        lab = bin_label(edges[k], edges[k + 1])
        rec[f"class_{tag}_K_{lab}_S_count"] = float(s_tr[k])
        rec[f"class_{tag}_K_{lab}_Sborder_count"] = float(s_bd[k])
        # annulus form for g(r)
        rec[f"class_{tag}_g_{lab}_S_count"] = float(s_tr[k] - (s_tr[k - 1] if k else 0.0))
    rec[f"class_{tag}_K_denominator_per_um2_count"] = float(d_tr)
    rec[f"spatial_query_fraction_{tag}"] = float(query_fraction)
    rec[f"class_{tag}_K_n_a_count"] = int(n_a_full)
    rec[f"class_{tag}_K_n_b_count"] = int(n_b_full)


def measure_distance_to_structure(rec, sname, struct, window, x, y, classes, edges,
                                  cap_um, pool=None, n_perm=0, seed=0):
    """Cell counts AND available tissue area, per fixed distance bin.

    When `pool` is supplied and n_perm > 0, an exact random-labelling
    permutation test is run for the FIRST (primary) distance bin: the per-cell
    distances are fixed, so the null is simply "draw n_A cells at random from
    the eligible pool". The p-value is per-slide QC and is written as a plain
    column that aggregate_to_mouse.py does not aggregate, by design.
    """
    if not struct.mask.any():
        raise ValueError(f"structure '{sname}' is empty -- refusing to report "
                         "distances to a structure that does not exist.")
    cap_px = max(1.0, cap_um / window.px_um)
    dfield = edt_capped(struct.mask, cap_px) * window.px_um
    # tissue area per bin: the window restricted to the analysis window
    dwin = dfield[window.mask]
    acounts, a_under, a_over = histogram_counts(dwin, edges)
    px_area = window.pixel_area_um2
    for k in range(len(edges) - 1):
        lab = bin_label(edges[k], edges[k + 1])
        rec[f"{sname}_{lab}_positive_area_um2"] = float(acounts[k]) * px_area
    rec[f"{sname}_beyond_max_positive_area_um2"] = float(a_over) * px_area
    for cname, member in classes.items():
        dv, ok = window.sample(dfield, x[member], y[member], outside_value=np.nan)
        good = ok & np.isfinite(dv)
        counts, under, over = histogram_counts(dv[good], edges)
        for k in range(len(edges) - 1):
            lab = bin_label(edges[k], edges[k + 1])
            rec[f"class_{cname}_to_{sname}_{lab}_count"] = int(counts[k])
        rec[f"class_{cname}_to_{sname}_beyond_max_count"] = int(over)
        rec[f"class_{cname}_to_{sname}_offwindow_count"] = int((~good).sum())

        if pool is not None and n_perm > 0:
            r_primary = float(edges[1])
            dpool, okp = window.sample(dfield, x[pool], y[pool], outside_value=np.nan)
            dpool = dpool[np.isfinite(dpool)]
            n_a = int(good.sum())
            if 0 < n_a < dpool.size:
                obs = int((dv[good] <= r_primary).sum())
                rng = np.random.default_rng(seed)
                near = dpool <= r_primary
                ge = 0
                for _ in range(n_perm):
                    pick = rng.choice(dpool.size, size=n_a, replace=False)
                    if int(near[pick].sum()) >= obs:
                        ge += 1
                rec[f"spatial_perm_p_{cname}_to_{sname}_within_{r_primary:g}um"] = \
                    (ge + 1) / float(n_perm + 1)
                rec[f"spatial_perm_n_{cname}_to_{sname}"] = n_perm
    return int(a_under)


# --------------------------------------------------------------------------
def subsample(x, y, extra, limit, seed):
    n = x.size
    if limit <= 0 or n <= limit:
        return x, y, extra, 1.0
    rng = np.random.default_rng(seed)
    idx = rng.choice(n, size=limit, replace=False)
    idx.sort()
    return x[idx], y[idx], {k: v[idx] for k, v in extra.items()}, limit / float(n)


def main():
    ap = argparse.ArgumentParser(
        description="Spatial sufficient statistics from a de-duplicated slide point pattern.")
    ap.add_argument("--points", required=True, help="<slide>__points.csv from build_point_pattern.py")
    ap.add_argument("--profile", required=True, help="profile key in spatial_profiles.json")
    ap.add_argument("--profiles-json", default=os.path.join(_HERE, "config", "spatial_profiles.json"))
    ap.add_argument("--slide-dir", default=None,
                    help="Stage 1 slide folder; required when a structure uses source=engine_mask")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--allow-single-tile", action="store_true",
                    help="permit a point pattern drawn from ONE tile. Off by default: "
                         "per-tile spatial statistics turn every 707 um tile edge into a "
                         "real boundary and bias every radius above ~20 um.")
    ap.add_argument("--n-permutations", type=int, default=999,
                    help="random-labelling permutations for the WITHIN-SLIDE "
                         "distance-to-structure QC p-value (default 999; 0 disables). "
                         "This p-value is never the group test -- n = MICE for that.")
    ap.add_argument("--allow-structure-fallback", action="store_true",
                    help="permit the cell_class_dilation PROXY when the engine mask is "
                         "unavailable. Rows are stamped structure_source=..._proxy.")
    args = ap.parse_args()

    with open(args.profiles_json, encoding="utf-8") as fh:
        pj = json.load(fh)
    if args.profile not in pj["profiles"]:
        sys.exit(f"ERROR: profile '{args.profile}' not in {args.profiles_json}. "
                 f"Available: {sorted(pj['profiles'])}")
    prof = pj["profiles"][args.profile]
    class_defs = pj["cell_classes"]

    with open(args.points, newline="", encoding="utf-8-sig") as fh:
        rows = [r for r in csv.DictReader(fh)]
    if not rows:
        sys.exit(f"ERROR: {args.points} has no rows.")

    rf = prof.get("region_filter")
    if rf:
        rows = [r for r in rows if rf.lower() in (r.get("region") or "").lower()]
        if not rows:
            sys.exit(f"ERROR: region_filter '{rf}' matched no cells. The profile's "
                     "spatial window and the endpoint denominator must be the same "
                     "region; do not silently fall back to all tissue.")

    n_tiles = len({r.get("src_section_id") for r in rows})
    if n_tiles < 2 and not args.allow_single_tile:
        sys.exit(f"ERROR: point pattern spans only {n_tiles} tile. Spatial statistics on a "
                 "single tile treat the tile edge as tissue boundary. Pass "
                 "--allow-single-tile only for testing.")

    x = np.array([float(r["x_um"]) for r in rows])
    y = np.array([float(r["y_um"]) for r in rows])

    wincfg = dict(prof["window"])
    wincfg["raster_um"] = prof.get("raster_um", 2.0)
    window = build_window(x, y, wincfg)
    cap_um = max([float(prof["nn_edges_um"][-1]),
                  float(prof["ripley_edges_um"][-1]),
                  max([float(s["edges_um"][-1]) for s in prof.get("structures", [])] or [0.0]),
                  max([float(v) for v in prof.get("neighbourhood_radii_um", [])] or [0.0])])
    bdist, b_ok = boundary_distance_um(window, x, y, cap_um)

    members, evaluables = {}, {}
    for name, spec in class_defs.items():
        if name.startswith("_") or not isinstance(spec, dict):
            continue                      # JSON comment keys are not classes
        try:
            m, e = class_membership(rows, spec)
        except KeyError as exc:
            print(f"  skipping class '{name}': {exc}")
            continue
        members[name] = m
        evaluables[name] = e

    pool_name = prof.get("eligible_pool", "AllCells")
    pool = evaluables.get(pool_name, np.ones(len(rows), dtype=bool))

    rec = {}
    r0 = rows[0]
    for c in KEY_COLS + ROW_ID_COLS:
        rec[c] = (r0.get(c) or "").strip()
    # aggregate_to_mouse keys on (output_key|image, region, section_id, panel).
    # aggregate_tiles_to_slide gives the SLIDE row image == section_id == slide
    # name (its lines 266-267); match that exactly so the join is 1:1.
    slide = os.path.basename(args.points).replace("__points.csv", "")
    rec["image"] = slide
    rec["section_id"] = slide
    if "output_key" in r0:
        rec["output_key"] = slide

    rec["spatial_profile"] = args.profile
    rec["spatial_bin_signature"] = json.dumps(
        {"nn": prof["nn_edges_um"], "ripley": prof["ripley_edges_um"],
         "nbr": prof["neighbourhood_radii_um"],
         "struct": {s["name"]: s["edges_um"] for s in prof.get("structures", [])}},
        sort_keys=True, separators=(",", ":"))
    rec["spatial_window_mode"] = wincfg["mode"]
    rec["spatial_window_dilate_um"] = wincfg["dilate_um"]
    rec["spatial_window_positive_area_um2"] = window.area_um2()
    rec["spatial_raster_um"] = window.px_um
    rec["spatial_n_tiles"] = n_tiles
    rec["class_spatial_all_cells_count"] = len(rows)
    rec["spatial_n_points_offwindow"] = int((~b_ok).sum())
    rec["spatial_edge_correction"] = "ohser_translation_primary;border_reduced_sample_qc"
    rec["spatial_null_model"] = "random_labelling_within_eligible_pool"
    rec["spatial_eligible_pool"] = pool_name
    rec["class_spatial_eligible_pool_count"] = int(pool.sum())
    rec["class_spatial_indeterminate_excluded_count"] = int((~pool).sum())

    corrector = TranslationCorrector(window, cap_um, prof.get("covariance_um", 8.0))

    # ---- 1. nearest neighbour -------------------------------------------
    for pair in prof.get("nn_pairs", []):
        a, b = pair["from"], pair["to"]
        if a not in members or b not in members:
            continue
        ma, mb = members[a] & pool, members[b] & pool
        measure_nn(rec, f"{a}_to_{b}", x[ma], y[ma], bdist[ma], x[mb], y[mb],
                   prof["nn_edges_um"])

    # ---- 2. neighbourhood composition -----------------------------------
    limit = int(prof.get("max_query_points", 200000))
    seed = int(prof.get("query_seed", 0))
    for pair in prof.get("neighbourhood_pairs", []):
        f, b = pair["focal"], pair["neighbour"]
        if f not in members or b not in members:
            continue
        mf = members[f] & pool
        is_b = members[b] & pool
        xf, yf, extra, frac = subsample(x[mf], y[mf],
                                        {"b": bdist[mf], "isb": is_b[mf]}, limit, seed)
        for radius in prof["neighbourhood_radii_um"]:
            measure_neighbourhood(rec, f"{f}_with_{b}", xf, yf, extra["b"], extra["isb"],
                                  x[pool], y[pool], is_b[pool], float(radius),
                                  int(pool.sum()), int(is_b.sum()))
        rec[f"spatial_query_fraction_{f}_with_{b}"] = frac

    # ---- 3. Ripley ------------------------------------------------------
    for pair in prof.get("ripley_pairs", []):
        a, b = pair["a"], pair["b"]
        if a not in members or b not in members:
            continue
        ma, mb = members[a] & pool, members[b] & pool
        xa, ya, extra, frac = subsample(x[ma], y[ma], {"b": bdist[ma]}, limit, seed)
        measure_ripley(rec, f"{a}_x_{b}", xa, ya, extra["b"], x[mb], y[mb],
                       prof["ripley_edges_um"], window, corrector,
                       xa.size, int(mb.sum()), frac)
    rec["spatial_gamma_zero_lookups"] = int(corrector.n_gamma_zero)

    # ---- 4. distance to structure ---------------------------------------
    for s in prof.get("structures", []):
        sname = s["name"]
        struct, source = None, None
        if s.get("source") == "engine_mask" and args.slide_dir:
            try:
                import aggregate_tiles_to_slide as ats
                tmp, tmr = ats.read_csv_rows(os.path.join(args.slide_dir, "tile_manifest.csv"))
                by_sec = {r["section_id"]: r for r in tmr}
                adirs = sorted({os.path.dirname(p) for p in ats.find_run_summaries(args.slide_dir)})
                struct, n_used = structure_raster_from_engine_masks(
                    args.slide_dir, s["mask_suffix"], by_sec, window, adirs)
                source = f"engine_mask:{s['mask_suffix']}:{n_used}_tiles"
            except Exception as exc:                              # noqa: BLE001
                if not args.allow_structure_fallback:
                    sys.exit(f"ERROR: structure '{sname}' could not be built from engine "
                             f"masks ({exc}). Pass --allow-structure-fallback to use the "
                             "declared PROXY instead, and understand that the proxy is not "
                             "pod area.")
                print(f"  WARNING: engine mask unavailable for '{sname}' ({exc}); using proxy")
        if struct is None:
            fb = s.get("fallback")
            if not fb:
                sys.exit(f"ERROR: no source available for structure '{sname}'.")
            cm = members.get(fb["cell_class"])
            if cm is None:
                sys.exit(f"ERROR: fallback class '{fb['cell_class']}' unavailable.")
            struct = structure_raster_from_cells(x, y, cm & pool, float(fb["dilate_um"]), window)
            source = f"cell_class_dilation_proxy:{fb['cell_class']}:{fb['dilate_um']}um"
            if struct is None:
                print(f"  structure '{sname}': no member cells; skipping")
                continue
        rec[f"{sname}_structure_source"] = source
        rec[f"{sname}_structure_positive_area_um2"] = struct.area_um2()
        tgt = {c: (members[c] & pool) for c in s["target_classes"] if c in members}
        measure_distance_to_structure(rec, sname, struct, window, x, y, tgt,
                                      s["edges_um"], float(s["edges_um"][-1]),
                                      pool=pool, n_perm=args.n_permutations,
                                      seed=int(prof.get("query_seed", 0)))

    outdir = args.outdir or os.path.dirname(os.path.abspath(args.points))
    os.makedirs(outdir, exist_ok=True)
    out = os.path.join(outdir, "spatial_summary.csv")
    with open(out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rec))
        w.writeheader()
        w.writerow(rec)
    print(f"Wrote 1 spatial row ({len(rec)} columns) -> {out}")
    print("Next:  python3 spatial/join_spatial_to_summary.py --target <slide_level_summary.csv> "
          f"--spatial {out}")
    print("Reminder: every column here is a SUM. No ratio, median or p-value is "
          "computed before mouse-level pooling. n = MICE.")


if __name__ == "__main__":
    main()
