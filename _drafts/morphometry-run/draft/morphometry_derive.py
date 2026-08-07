#!/usr/bin/env python3
"""
morphometry_derive.py
=====================================================================
Module A (morphometry) post-step. Turns the POOLED ADDITIVE PRIMITIVES that
aggregate_to_mouse.py produced into the named morphometric endpoints.

PIPELINE POSITION
  qupath_lung_morphometry.groovy  -> morphometry_slide_summary.csv
  aggregate_to_mouse.py           -> mouse_level_summary.csv   (UNMODIFIED)
  THIS SCRIPT                     -> mouse_level_morphometry.csv
                                     group_level_morphometry.csv

WHY THIS EXISTS AND WHY IT IS SEPARATE
  Every quantity below is a RATIO. Ratios are not summable. If MLI were carried
  as a column, aggregate_to_mouse.py would have to either sum it (nonsense) or
  average it (silently weights a 2 mm2 region the same as a 70 mm2 region). So
  the Groovy emits only numerators and denominators, aggregate_to_mouse.py
  pools those with the rules it already has, and the ratios are formed here,
  ONCE, from the pooled totals. aggregate_to_mouse.py is not modified, not
  copied, and not forked -- it never learns that morphometry exists.

  The specific hazard this design removes:
    MLI_mouse  !=  mean(MLI_slide)
    MLI_mouse  ==  sum(chord length) / sum(chord count)
  and likewise for septal thickness, surface density and the fractal slope.

COLUMN CONTRACT CONSUMED HERE
  aggregate_to_mouse.py renames as follows (verified against its source):
    region_area_um2                 -> total_tissue_area_um2       (line 232)
    <x>_positive_area_um2           -> <x>_positive_area_um2_total (line 319)
                                       <x>_positive_area_fraction  (line 320)
                                       <x>_mean_component_area_um2 (line 322)
    class_<x>_count                 -> class_<x>_count_total       (line 337)
  So this script reads only *_total / *_fraction columns and never re-reads the
  slide-level file.

USAGE
  python morphometry_derive.py <stats>/mouse_level_summary.csv
  python morphometry_derive.py <stats>/mouse_level_summary.csv --outdir <stats>

No third-party dependencies (standard library only), matching the rest of the
repo's Python.
=====================================================================
"""
import argparse
import csv
import math
import os
import sys
from collections import defaultdict

KEY_COLS = ["mouse_id", "genotype", "condition", "panel"]

# Pixel geometry has already been applied in the Groovy, so nothing here needs
# to know the analysis resolution -- except that comparing MLI or surface
# density between rows acquired at DIFFERENT resolutions is invalid. The Groovy
# stamps class_morph_pxfine_ok_count = 1 per row when the run matched the
# expected locked resolution; if the pooled total is less than the number of
# regions, resolutions were mixed and this script says so.


def _num(v):
    if v is None:
        return None
    s = str(v).strip()
    if s == "" or s.upper() == "NA":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def read_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        if reader.fieldnames is None:
            sys.exit(f"ERROR: {path} is empty or has no header.")
        return reader.fieldnames, [r for r in reader
                                   if any((v or "").strip() for v in r.values())]


def g(row, col, default=0.0):
    v = _num(row.get(col))
    return default if v is None else v


def safe_div(num, den):
    return (num / den) if den and den > 0 else None


def fit_box_dimension(eps_px, counts, px_um):
    """
    Fractal box dimension D_B = -slope of log N(eps) vs log eps, by ordinary
    least squares on the pooled counts. Andersen et al. 2012 (Int J COPD
    7:235-243) showed D_B falls as Lm rises in elastase-treated mice
    (R = -0.95), which makes it a cheap destruction readout.

    Fitted here rather than in the Groovy because a SLOPE IS NOT SUMMABLE:
    fitting per slide and averaging the slopes is not the same as fitting the
    pooled counts, and the pooled fit is the one that respects area weighting.
    Boxes are anchored at the slide origin, so counts add across regions except
    for boxes straddling a region boundary -- negligible at eps <= 64 px
    (177 um) against a ~70 mm2 ROI, but it is an approximation, not an
    identity.
    """
    xs, ys = [], []
    for e, c in zip(eps_px, counts):
        if c is None or c <= 0:
            continue
        xs.append(math.log(e * px_um))
        ys.append(math.log(c))
    if len(xs) < 3:
        return None, len(xs)
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx <= 0:
        return None, n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    return -(sxy / sxx), n


def derive(row, px_fine_um):
    """Every output here is recomputed from POOLED numerator and denominator."""
    out = {}
    roi = g(row, "total_tissue_area_um2")
    tissue = g(row, "morph_tissue_positive_area_um2_total")
    air = g(row, "morph_airspace_positive_area_um2_total")
    fine = g(row, "morph_finepass_positive_area_um2_total")

    out["morph_roi_area_mm2"] = roi / 1e6
    out["morph_tissue_area_mm2"] = tissue / 1e6
    out["morph_airspace_area_mm2"] = air / 1e6

    # ---- 1. AIRSPACE FRACTION / TISSUE-TO-AIRSPACE RATIO -------------------
    # Areal fraction A_A. By Delesse's principle A_A is an unbiased estimator
    # of the volume fraction V_V, PROVIDED the section plane is random with
    # respect to the structure. Denominator is the fine-pass area actually
    # measured, not the ROI area, so a capped/partial run cannot inflate it.
    out["morph_airspace_fraction"] = safe_div(air, fine)
    out["morph_tissue_fraction"] = safe_div(tissue, fine)
    out["morph_tissue_to_airspace_ratio"] = safe_div(tissue, air)
    # coverage: fine pass must have visited the whole ROI
    out["morph_finepass_coverage_of_roi"] = safe_div(fine, roi)

    # ---- 2. MEAN LINEAR INTERCEPT -----------------------------------------
    # TWO conventions, both reported, because the literature uses both and they
    # differ by roughly the reciprocal of the airspace fraction.
    #
    #  DIRECT   mean airspace chord length = sum(chord length)/count, truncated
    #           chords excluded. This is the quantity that estimates
    #           4*V_air/S_alv under isotropic uniform random test lines.
    #  INDIRECT Dunnill/Thurlbeck MLI = 2*L/N where L is the total test-line
    #           length over parenchyma and N the number of air<->tissue
    #           transitions (2 transitions per septum crossed). Madi et al.
    #           2025 (Physiol Meas) show the indirect method OVERESTIMATES,
    #           through Septa Bias (septal thickness is inside L) and Partial
    #           Chord Bias (chords clipped at a field edge).
    clen = g(row, "class_morph_chordlen_um_count_total")
    cn = g(row, "class_morph_chordn_count_total")
    tl = g(row, "class_morph_testline_um_count_total")
    tr = g(row, "class_morph_transition_count_total")
    trunc_n = g(row, "class_morph_chordtrunc_count_total")
    trunc_l = g(row, "class_morph_chordtrunclen_um_count_total")
    out["morph_mli_direct_um"] = safe_div(clen, cn)
    out["morph_mli_indirect_um"] = safe_div(2.0 * tl, tr)
    # How much of the chord population was thrown away, and how much length it
    # carried. Publish this: it bounds the Partial Chord Bias instead of
    # assuming it away.
    out["morph_chord_truncated_fraction"] = safe_div(trunc_n, cn + trunc_n)
    out["morph_chord_truncated_length_fraction"] = safe_div(trunc_l, clen + trunc_l)
    out["morph_chord_n_total"] = cn

    # Anisotropy. A 2-direction grid estimates MLI without bias only if the
    # structure is isotropic in the section plane. If horizontal and vertical
    # MLI disagree, they are not, and the 4-direction average above is a
    # partial fix at best. This is the number to look at before believing any
    # absolute MLI.
    mh = safe_div(g(row, "class_morph_chordlenh_um_count_total"),
                  g(row, "class_morph_chordnh_count_total"))
    mv = safe_div(g(row, "class_morph_chordlenv_um_count_total"),
                  g(row, "class_morph_chordnv_count_total"))
    out["morph_mli_direct_h_um"] = mh
    out["morph_mli_direct_v_um"] = mv
    out["morph_mli_anisotropy_ratio"] = (max(mh, mv) / min(mh, mv)) if (mh and mv) else None

    # ---- 3. SEPTAL WALL THICKNESS -----------------------------------------
    # Two slab-calibrated estimators that should agree; disagreement means the
    # "tissue" phase is blob-like (consolidation, vessel wall) rather than
    # septal, which is diagnostic in itself.
    #  (a) EDM: for a slab of thickness t the mean distance-to-boundary is t/4,
    #      so t = 4*mean(EDM). The Groovy already subtracted the empirically
    #      measured 0.5 px discretisation offset.
    #  (b) 2A/B: the classical arithmetic mean thickness tau = 2*V/S, which in
    #      section is 2*A/B with B the Crofton boundary length.
    edm_sum = g(row, "class_morph_edmhalf_um_count_total")
    tpx = g(row, "class_morph_tissuepx_count_total")
    per = g(row, "class_morph_perimeter_um_count_total")
    out["morph_septal_thickness_edm_um"] = (4.0 * edm_sum / tpx) if tpx > 0 else None
    out["morph_septal_thickness_2a_over_b_um"] = safe_div(2.0 * tissue, per)
    a, b = out["morph_septal_thickness_edm_um"], out["morph_septal_thickness_2a_over_b_um"]
    out["morph_septal_thickness_estimator_ratio"] = (a / b) if (a and b) else None

    # ---- 4. SURFACE DENSITY (surface-area-to-volume proxy) -----------------
    # For a plane section through an isotropic structure the surface density is
    # S_V = (4/pi) * B_A, with B_A the boundary length per unit section area
    # (Tomkeieff / Saltykov). Units 1/um; also given as mm2 per mm3.
    # This is the most resolution-sensitive quantity in the module -- boundary
    # length grows without limit as the pixel shrinks (the coastline problem),
    # which is why the analysis resolution is locked rather than chosen.
    ba = safe_div(per, fine)                       # um / um2 = 1/um
    out["morph_boundary_length_density_per_um"] = ba
    out["morph_surface_density_per_um"] = (4.0 / math.pi) * ba if ba else None
    out["morph_surface_density_mm2_per_mm3"] = ((4.0 / math.pi) * ba * 1000.0) if ba else None
    out["morph_perimeter_total_mm"] = per / 1000.0

    # ---- 5. DESTRUCTION / CONFLUENCE --------------------------------------
    # Saetta's destructive index is a HUMAN point-count with verbal criteria
    # and is not reproduced here. These are named surrogates, not DI.
    ac_area = g(row, "morph_airspacec_positive_area_um2_total")
    ac_n = g(row, "morph_airspacec_n_components_total")
    big = g(row, "morph_airspacebig_positive_area_um2_total")
    out["morph_airspace_component_count"] = ac_n
    out["morph_mean_airspace_component_area_um2"] = safe_div(ac_area, ac_n)
    # equivalent circular diameter of the mean airspace component
    mca = out["morph_mean_airspace_component_area_um2"]
    out["morph_mean_airspace_component_eqdiam_um"] = (
        2.0 * math.sqrt(mca / math.pi) if mca else None)
    # confluence: destruction merges alveoli, so airspace migrates into a few
    # very large components
    out["morph_confluent_airspace_fraction"] = safe_div(big, ac_area)
    out["morph_airspace_component_density_per_mm2"] = safe_div(ac_n, roi / 1e6)

    # ---- 6. CONSOLIDATION (architecture only) -----------------------------
    out["morph_consolidated_fraction"] = _num(row.get("morph_consolidated_positive_area_fraction"))

    # ---- 7. INDEPENDENT CHECK ON THE AGER DAMAGED-AREA DENOMINATOR --------
    # 2x2 area confusion between architecture-defined consolidation and the
    # AGER-density damage map, pooled over the mouse then turned into
    # agreement statistics. This is the whole reason the module exists: if the
    # AGER-poor territory is NOT architecturally abnormal, the endpoint
    # denominator is measuring staining rather than injury.
    tp = g(row, "morph_alvdmgcons_positive_area_um2_total")     # AGER-damaged AND consolidated
    fn = g(row, "morph_alvdmgonly_positive_area_um2_total")     # AGER-damaged, architecture normal
    fp = g(row, "morph_alvconsonly_positive_area_um2_total")    # consolidated, AGER intact
    tn = g(row, "morph_alvneither_positive_area_um2_total")
    tot = tp + fn + fp + tn
    if tot > 0:
        out["morph_agerdmg_area_fraction"] = _num(row.get("morph_agerdmg_positive_area_fraction"))
        out["morph_arch_vs_ager_sensitivity"] = safe_div(tp, tp + fn)
        out["morph_arch_vs_ager_specificity"] = safe_div(tn, tn + fp)
        out["morph_arch_vs_ager_jaccard"] = safe_div(tp, tp + fn + fp)
        po = (tp + tn) / tot
        pe = (((tp + fn) * (tp + fp)) + ((fp + tn) * (fn + tn))) / (tot * tot)
        out["morph_arch_vs_ager_kappa"] = ((po - pe) / (1.0 - pe)) if (1.0 - pe) > 1e-12 else None
        out["morph_arch_vs_ager_area_agreement"] = po

    # ---- 8. FRACTAL BOX DIMENSION -----------------------------------------
    eps, counts = [], []
    for c in sorted(row.keys()):
        if c.startswith("class_morph_box_eps") and c.endswith("_count_total"):
            try:
                e = int(c[len("class_morph_box_eps"):-len("_count_total")])
            except ValueError:
                continue
            eps.append(e)
            counts.append(g(row, c))
    if eps:
        order = sorted(range(len(eps)), key=lambda i: eps[i])
        eps = [eps[i] for i in order]
        counts = [counts[i] for i in order]
        db, npts = fit_box_dimension(eps, counts, px_fine_um)
        out["morph_fractal_box_dimension"] = db
        out["morph_fractal_fit_points"] = npts

    # ---- QC ----------------------------------------------------------------
    nrows = g(row, "class_morph_rows_count_total")
    okres = g(row, "class_morph_pxfine_ok_count_total")
    out["morph_n_region_rows"] = nrows
    out["morph_resolution_consistent"] = (
        "true" if (nrows > 0 and abs(okres - nrows) < 1e-9) else "FALSE")
    return out


def stats(values):
    vals = [v for v in values if v is not None]
    n = len(vals)
    if n == 0:
        return 0, None, None, None
    mean = sum(vals) / n
    if n > 1:
        sd = math.sqrt(sum((v - mean) ** 2 for v in vals) / (n - 1))
        return n, mean, sd, sd / math.sqrt(n)
    return n, mean, 0.0, 0.0


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
        description="Recompute morphometric ratios from the POOLED primitives in "
                    "mouse_level_summary.csv. Never sums or averages a ratio.")
    ap.add_argument("mouse_level_summary",
                    help="mouse_level_summary.csv written by aggregate_to_mouse.py")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--px-fine-um", type=float, default=0.690,
                    help="locked FINE analysis resolution, um/px. Only used for the "
                         "fractal-dimension x axis; every other quantity is already in "
                         "microns. Must match IFQ_MORPH_DS_FINE * the scanner pixel size.")
    ap.add_argument("--join-marker-mouse-level", default=None,
                    help="optional: the MARKER mouse_level_summary.csv (from the same "
                         "aggregate_to_mouse.py, run on the marker slide/tile summary). "
                         "Emits mouse_level_combined.csv = a pure COLUMN join on "
                         "(mouse_id, genotype, condition, panel) of two tables that were "
                         "each already pooled by aggregate_to_mouse.py. No re-aggregation "
                         "happens here; this cannot become a second definition of 'mouse'.")
    args = ap.parse_args()

    if not os.path.isfile(args.mouse_level_summary):
        sys.exit(f"ERROR: not found: {args.mouse_level_summary}")
    outdir = args.outdir or os.path.dirname(os.path.abspath(args.mouse_level_summary))
    os.makedirs(outdir, exist_ok=True)

    header, rows = read_rows(args.mouse_level_summary)
    for req in KEY_COLS + ["total_tissue_area_um2"]:
        if req not in header:
            sys.exit(f"ERROR: {args.mouse_level_summary} has no '{req}' column. "
                     "This file must be the output of aggregate_to_mouse.py.")
    if "class_morph_chordn_count_total" not in header:
        sys.exit("ERROR: no morphometry primitives found (class_morph_chordn_count_total is "
                 "missing). Did aggregate_to_mouse.py run on morphometry_slide_summary.csv?")

    out_rows = []
    warned = False
    for r in rows:
        rec = {k: r.get(k) for k in KEY_COLS}
        rec["n_regions"] = r.get("n_regions")
        rec["n_sections"] = r.get("n_sections")
        rec.update(derive(r, args.px_fine_um))
        if rec.get("morph_resolution_consistent") == "FALSE":
            warned = True
        out_rows.append(rec)

    # Scan EVERY row, not just the first: a metric that is None on the first
    # mouse (e.g. the AGER comparison was off for that slide) would otherwise
    # vanish from the group table for all mice.
    skip = set(KEY_COLS) | {"n_regions", "n_sections"}
    metric_cols, seen_cols = [], set()
    for r in out_rows:
        for c, v in r.items():
            if c in skip or c in seen_cols:
                continue
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                metric_cols.append(c)
                seen_cols.add(c)
    groups = defaultdict(list)
    for r in out_rows:
        groups[(r["genotype"], r["condition"], r["panel"])].append(r)
    grp_rows = []
    for (geno, cond, panel), grp in sorted(groups.items()):
        for m in metric_cols:
            n, mean, sd, sem = stats([x.get(m) for x in grp])
            if n == 0:
                continue
            grp_rows.append({"genotype": geno, "condition": cond, "panel": panel,
                             "metric": m, "n_mice": n, "mean": mean, "sd": sd, "sem": sem})

    mp = os.path.join(outdir, "mouse_level_morphometry.csv")
    gp = os.path.join(outdir, "group_level_morphometry.csv")
    write_csv(mp, out_rows)
    write_csv(gp, grp_rows)

    # ---- optional column join with the marker endpoint -------------------
    # Both sides were pooled by the SAME aggregate_to_mouse.py; this only
    # widens rows. It fails closed rather than silently emitting half a table,
    # because a mouse present on one side and missing on the other means the
    # two analyses did not see the same animals.
    if args.join_marker_mouse_level:
        if not os.path.isfile(args.join_marker_mouse_level):
            sys.exit(f"ERROR: --join-marker-mouse-level not found: {args.join_marker_mouse_level}")
        mh, mrows = read_rows(args.join_marker_mouse_level)
        for req in KEY_COLS:
            if req not in mh:
                sys.exit(f"ERROR: the marker file has no '{req}' column; it must be an "
                         "aggregate_to_mouse.py output.")
        def key(r):
            return tuple((r.get(k) or "").strip() for k in KEY_COLS)
        mk = {key(r): r for r in mrows}
        ok = {key(r): r for r in out_rows}
        if len(mk) != len(mrows) or len(ok) != len(out_rows):
            sys.exit("ERROR: duplicate (mouse_id, genotype, condition, panel) keys; refusing to join.")
        only_marker = sorted(set(mk) - set(ok))
        only_morph = sorted(set(ok) - set(mk))
        if only_marker or only_morph:
            sys.exit("ERROR: the two mouse-level tables do not cover the same animals.\n"
                     f"  marker only: {only_marker}\n  morphometry only: {only_morph}\n"
                     "Fix the inputs; a partial join would compare architecture in one set "
                     "of mice against the endpoint in another.")
        combined = []
        clash = set()
        for k in sorted(ok):
            row = dict(mk[k])
            for c, v in ok[k].items():
                if c in KEY_COLS:
                    continue
                if c in row and row[c] != v:
                    clash.add(c)
                    row["morph__" + c] = v
                else:
                    row[c] = v
            combined.append(row)
        cp = os.path.join(outdir, "mouse_level_combined.csv")
        write_csv(cp, combined)
        print(f"Wrote {len(combined)} joined mouse row(s) -> {cp}")
        if clash:
            print("NOTE: these columns existed on both sides and the morphometry copy was "
                  "prefixed 'morph__': " + ", ".join(sorted(clash)))
    print(f"Wrote {len(out_rows)} mouse x panel row(s) -> {mp}")
    print(f"Wrote {len(grp_rows)} group x metric row(s) -> {gp}")
    print("n = MICE. Every ratio above was formed from POOLED numerators and denominators, "
          "never by averaging per-slide ratios.")
    if warned:
        print("WARNING: morph_resolution_consistent=FALSE for at least one mouse. Rows were "
              "acquired at different analysis resolutions; MLI, septal thickness and surface "
              "density are NOT comparable across them.")
    n_mice = len({(r["mouse_id"], r["genotype"], r["condition"]) for r in out_rows})
    if n_mice < 6:
        print(f"NOTE: {n_mice} distinct animal(s). Absolute morphometry is protocol-bound "
              "(inflation pressure, fixative, section thickness); with n this small treat "
              "these as descriptive, not inferential.")


if __name__ == "__main__":
    main()
