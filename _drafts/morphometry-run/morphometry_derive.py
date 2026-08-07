#!/usr/bin/env python3
"""
morphometry_derive.py
=====================================================================
Post-step for the morphometry module. Turns the POOLED ADDITIVE PRIMITIVES that
aggregate_to_mouse.py produced into the named morphometric endpoints, and emits
the damaged-vs-intact compartment contrast.

PIPELINE POSITION
  lung_morphometry.groovy   -> morphometry_slide_summary_ds<N>.csv
  aggregate_to_mouse.py     -> mouse_level_summary.csv        (UNMODIFIED)
  THIS SCRIPT               -> mouse_level_morphometry.csv
                               group_level_morphometry.csv
                               compartment_contrast.csv

WHY IT IS SEPARATE
  Every quantity below is a RATIO, and ratios are not summable. If MLI were a
  column, aggregate_to_mouse.py would have to sum it (nonsense) or average it
  (weights a 0.5 mm2 compartment the same as a 55 mm2 one). So the Groovy emits
  only numerators and denominators, aggregate_to_mouse.py pools those with the
  rules it already has, and the ratios are formed here, ONCE, from the pooled
  totals. aggregate_to_mouse.py is not modified, not copied, not forked.

    MLI_mouse  !=  mean(MLI_slide)
    MLI_mouse  ==  mean_over_orientations( sum(chord length) / sum(chord count) )

HOW COMPARTMENTS SURVIVE AGGREGATION
  aggregate_to_mouse.py groups on (mouse_id, genotype, condition, panel) and
  pools ACROSS `region` inside a group. So a damaged row and an intact row that
  shared a panel would be ADDED and the comparison would silently vanish. The
  compartment is therefore carried in `panel` as "<PANEL>@<scope>"
  (MODULE_CONTRACT.md 2.3), which is the only free grouping key.

MLI ORIENTATION WEIGHTING
  The four chord directions are kept separate on purpose. Diagonal test lines on
  a square lattice are spaced delta/sqrt(2) apart, so they deliver sqrt(2) times
  the test-line length per unit area; pooling all four numerators and
  denominators over-weights the diagonals by that factor (measured on the disk
  phantom: 1.41-1.43x more chords). MLI here is the EQUAL-WEIGHT mean of the
  four per-orientation ratios, which is the orientation average stereology asks
  for. Each per-orientation ratio is itself pooled numerator / pooled
  denominator, so it stays area-weighted and exact.

Standard library only.
=====================================================================
"""
import argparse
import csv
import math
import os
import sys
from collections import defaultdict

KEY_COLS = ["mouse_id", "genotype", "condition", "panel"]
DIRECTIONS = ["000", "045", "090", "135"]
DIST_BINS = 24


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


def hist_quantile(counts, bin_um, q):
    """Quantile of a pooled histogram. Last bin is an overflow bin: if the
    quantile lands in it, return None rather than a number the bin cannot
    support."""
    tot = sum(counts)
    if tot <= 0:
        return None
    target = q * tot
    cum = 0.0
    for i, c in enumerate(counts):
        if cum + c >= target:
            if i == len(counts) - 1:
                return None            # in the overflow bin: undefined
            frac = (target - cum) / c if c > 0 else 0.0
            return (i + frac) * bin_um
        cum += c
    return None


def derive(row):
    """Every output is recomputed from POOLED numerator and denominator."""
    out = {}
    panel = (row.get("panel") or "")
    out["morph_scope"] = panel.split("@", 1)[1] if "@" in panel else ""

    roi = g(row, "total_tissue_area_um2")
    tissue = g(row, "morph_tissue_positive_area_um2_total")
    air = g(row, "morph_airspace_positive_area_um2_total")
    measured = g(row, "morph_measured_positive_area_um2_total")

    out["morph_compartment_area_mm2"] = roi / 1e6
    out["morph_measured_area_mm2"] = measured / 1e6
    out["morph_tissue_area_mm2"] = tissue / 1e6

    # ---- 1. AREAL FRACTIONS ------------------------------------------------
    # Areal fraction A_A. By the Delesse principle A_A is an unbiased estimator
    # of the volume fraction V_V PROVIDED the section plane is random with
    # respect to the structure. Denominator is the area the fine pass actually
    # visited, so a partial run cannot inflate it.
    out["morph_tissue_fraction"] = safe_div(tissue, measured)
    out["morph_airspace_fraction"] = safe_div(air, measured)
    out["morph_finepass_coverage_of_compartment"] = safe_div(measured, roi)

    # ---- 2. MEAN LINEAR INTERCEPT -----------------------------------------
    #  DIRECT   mean airspace chord length, truncated chords excluded. Estimates
    #           4*V_air/S under isotropic uniform random test lines.
    #  INDIRECT Dunnill/Thurlbeck 2L/N, where L is total test-line length over
    #           the compartment and N the number of phase transitions. It
    #           includes the thickness of the walls it crosses, so it is
    #           systematically larger than the direct estimate.
    per_dir = []
    for d in DIRECTIONS:
        L = g(row, f"class_morph_chordlen{d}um_count_total")
        N = g(row, f"class_morph_chordn{d}_count_total")
        v = safe_div(L, N)
        out[f"morph_mli_dir{d}_um"] = v
        if v is not None:
            per_dir.append(v)
    out["morph_mli_direct_um"] = (sum(per_dir) / len(per_dir)) if per_dir else None
    out["morph_mli_anisotropy_ratio"] = (max(per_dir) / min(per_dir)) if (per_dir and min(per_dir) > 0) else None
    out["morph_mli_n_orientations"] = len(per_dir)

    tl = g(row, "class_morph_testlineum_count_total")
    tr = g(row, "class_morph_transition_count_total")
    out["morph_mli_indirect_um"] = safe_div(2.0 * tl, tr)

    trunc_n = g(row, "class_morph_chordtruncn_count_total")
    trunc_l = g(row, "class_morph_chordtrunclenum_count_total")
    cn_all = sum(g(row, f"class_morph_chordn{d}_count_total") for d in DIRECTIONS)
    cl_all = sum(g(row, f"class_morph_chordlen{d}um_count_total") for d in DIRECTIONS)
    out["morph_chord_n_total"] = cn_all
    # Publish this. It BOUNDS the Partial Chord Bias instead of assuming it away.
    out["morph_chord_truncated_fraction"] = safe_div(trunc_n, cn_all + trunc_n)
    out["morph_chord_truncated_length_fraction"] = safe_div(trunc_l, cl_all + trunc_l)

    # ---- 3. WALL THICKNESS -------------------------------------------------
    # Two slab-calibrated estimators plus a distribution statistic.
    #  (a) 4*mean(EDM): for a slab of thickness t the mean distance-to-boundary
    #      is t/4. The Groovy already removed the 0.5 px discretisation offset.
    #  (b) 2A/B: classical arithmetic mean thickness tau = 2V/S, in section
    #      2*Area/Crofton boundary length.
    #  (c) 4*median(EDM): for a slab the distance is uniform on [0, t/2] so the
    #      median is t/4 as well. It is the estimator that survives blob
    #      contamination (vessel walls, consolidation), where the mean does not.
    edm_sum = g(row, "class_morph_septaldistum_count_total")
    tpx = g(row, "class_morph_septalpx_count_total")
    per = g(row, "class_morph_perimeterum_count_total")
    bin_um = 0.5
    shist = [g(row, f"class_morph_sdist_b{b:02d}_count_total") for b in range(DIST_BINS)]
    ahist = [g(row, f"class_morph_adist_b{b:02d}_count_total") for b in range(DIST_BINS)]

    out["morph_wall_thickness_edmmean_um"] = safe_div(4.0 * edm_sum, tpx)
    out["morph_wall_thickness_2a_over_b_um"] = safe_div(2.0 * tissue, per)
    q = hist_quantile(shist, bin_um, 0.5)
    out["morph_wall_thickness_edmmedian_um"] = (4.0 * q) if q is not None else None
    q9 = hist_quantile(shist, bin_um, 0.9)
    out["morph_wall_dist_p90_um"] = q9
    a = out["morph_wall_thickness_edmmean_um"]
    b = out["morph_wall_thickness_2a_over_b_um"]
    # Disagreement between the two slab-calibrated estimators means the phase is
    # blob-like rather than septal. That is diagnostic, not a nuisance.
    out["morph_wall_estimator_ratio"] = (a / b) if (a and b) else None

    # ---- 4. AIRSPACE WIDTH BY DISTANCE TRANSFORM ---------------------------
    # Same slab calibration applied to the OTHER phase. Completely independent
    # of the chord scan (no test lines, no truncation), so agreement between
    # morph_airspace_width_edmmean_um and morph_mli_direct_um is a real check.
    aedm_sum = g(row, "class_morph_airdistum_count_total")
    apx = g(row, "class_morph_airpx_count_total")
    out["morph_airspace_width_edmmean_um"] = safe_div(4.0 * aedm_sum, apx)
    qa = hist_quantile(ahist, bin_um, 0.5)
    out["morph_airspace_width_edmmedian_um"] = (4.0 * qa) if qa is not None else None
    out["morph_airspace_dist_p90_um"] = hist_quantile(ahist, bin_um, 0.9)

    # ---- 5. SURFACE DENSITY ------------------------------------------------
    # For a plane section through an isotropic structure S_V = (4/pi) * B_A,
    # with B_A the boundary length per unit section area (Tomkeieff/Saltykov).
    # The most resolution-sensitive quantity in the module: boundary length
    # grows without limit as the pixel shrinks (the coastline problem), which is
    # why the analysis resolution is locked and swept rather than chosen.
    ba = safe_div(per, measured)                    # um / um2 = 1/um
    out["morph_boundary_length_density_per_um"] = ba
    out["morph_surface_density_per_um"] = (4.0 / math.pi) * ba if ba else None
    out["morph_surface_density_mm2_per_mm3"] = ((4.0 / math.pi) * ba * 1000.0) if ba else None
    out["morph_perimeter_total_mm"] = per / 1000.0

    # ---- 6. CONNECTIVITY (gated) -------------------------------------------
    # Saetta's destructive index is a human point count with verbal criteria and
    # is NOT reproduced here. These are named surrogates, not DI.
    ac_area = g(row, "morph_aircomp_positive_area_um2_total")
    ac_n = g(row, "morph_aircomp_n_components_total")
    big = g(row, "morph_airbig_positive_area_um2_total")
    out["morph_airspace_component_count"] = ac_n
    out["morph_mean_airspace_component_area_um2"] = safe_div(ac_area, ac_n)
    mca = out["morph_mean_airspace_component_area_um2"]
    out["morph_mean_airspace_component_eqdiam_um"] = (2.0 * math.sqrt(mca / math.pi) if mca else None)
    out["morph_confluent_airspace_fraction"] = safe_div(big, ac_area)
    out["morph_airspace_component_density_per_mm2"] = safe_div(ac_n, roi / 1e6)
    cf = out["morph_confluent_airspace_fraction"]
    out["morph_connectivity_interpretable"] = "false" if (cf is None or cf > 0.90) else "true"

    # ---- 7. ARCHITECTURE-ONLY CONSOLIDATION --------------------------------
    lowair = g(row, "morph_lowair_positive_area_um2_total")
    out["morph_lowairspace_fraction_of_compartment"] = safe_div(lowair, roi)

    out["morph_n_slide_rows"] = g(row, "class_morph_rows_count_total")
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


CONTRAST_METRICS = [
    "morph_tissue_fraction", "morph_airspace_fraction",
    "morph_mli_direct_um", "morph_mli_indirect_um",
    "morph_airspace_width_edmmean_um", "morph_airspace_width_edmmedian_um",
    "morph_wall_thickness_edmmean_um", "morph_wall_thickness_edmmedian_um",
    "morph_wall_thickness_2a_over_b_um",
    "morph_surface_density_per_um", "morph_mli_anisotropy_ratio",
    "morph_mean_airspace_component_eqdiam_um", "morph_confluent_airspace_fraction",
]


def main():
    ap = argparse.ArgumentParser(
        description="Recompute morphometric ratios from POOLED primitives in "
                    "mouse_level_summary.csv. Never sums or averages a ratio.")
    ap.add_argument("mouse_level_summary")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--tag", default="", help="suffix for output filenames, e.g. _ds2")
    args = ap.parse_args()

    if not os.path.isfile(args.mouse_level_summary):
        sys.exit(f"ERROR: not found: {args.mouse_level_summary}")
    outdir = args.outdir or os.path.dirname(os.path.abspath(args.mouse_level_summary))
    os.makedirs(outdir, exist_ok=True)

    header, rows = read_rows(args.mouse_level_summary)
    for req in KEY_COLS + ["total_tissue_area_um2"]:
        if req not in header:
            sys.exit(f"ERROR: '{req}' missing. This must be an aggregate_to_mouse.py output.")
    if "class_morph_chordn000_count_total" not in header:
        sys.exit("ERROR: no morphometry primitives found (class_morph_chordn000_count_total is "
                 "missing). Did aggregate_to_mouse.py run on morphometry_slide_summary_ds*.csv?")

    # Synthesise the composite scopes by ADDING the pooled primitives of the
    # partition scopes. Exact, not approximate: the four emitted scopes partition
    # the analysis ROI, every carried column is an additive primitive, and the
    # ratios are formed afterwards from the summed numerator and denominator --
    # the same rule the aggregator itself follows. This is NOT an average of the
    # parts' ratios, which would be wrong.
    #
    # These composites cannot be emitted by the Groovy: `damaged` overlaps
    # `damaged_core`, and two overlapping rows inside one panel group would be
    # double-counted by aggregate_to_mouse.py.
    COMPOSITES = {
        "damaged":    ["damaged_edge", "damaged_core"],
        "intact":     ["intact_edge", "intact_core"],
        "parenchyma": ["damaged_edge", "damaged_core", "intact_edge", "intact_core"],
    }
    synth = defaultdict(dict)
    for r in rows:
        panel = r.get("panel") or ""
        pk, _, scope = panel.partition("@")
        synth[(r.get("mouse_id"), r.get("genotype"), r.get("condition"), pk)][scope] = r
    new_rows = []
    for (mouse, geno, cond, pk), scopes in synth.items():
        for name, parts in COMPOSITES.items():
            src = [scopes[p] for p in parts if p in scopes]
            if len(src) != len(parts):
                continue
            merged = {"mouse_id": mouse, "genotype": geno, "condition": cond,
                      "panel": pk + "@" + name,
                      "n_regions": None, "n_sections": src[0].get("n_sections")}
            for c in header:
                if c in KEY_COLS or c in ("n_regions", "n_sections"):
                    continue
                vals = [_num(s.get(c)) for s in src]
                if all(v is None for v in vals):
                    continue
                merged[c] = sum(v or 0.0 for v in vals)
            new_rows.append(merged)
    rows.extend(new_rows)

    out_rows = []
    for r in rows:
        rec = {k: r.get(k) for k in KEY_COLS}
        rec["n_regions"] = r.get("n_regions")
        rec["n_sections"] = r.get("n_sections")
        rec.update(derive(r))
        out_rows.append(rec)

    skip = set(KEY_COLS) | {"n_regions", "n_sections", "morph_scope",
                            "morph_connectivity_interpretable"}
    metric_cols, seen = [], set()
    for r in out_rows:
        for c, v in r.items():
            if c in skip or c in seen:
                continue
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                metric_cols.append(c)
                seen.add(c)

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

    # ---- compartment contrast: damaged vs intact, per mouse ----------------
    # Paired WITHIN an animal, so it is free of between-animal staining and
    # inflation differences. The number to look at is the log2 ratio: if the
    # AGER-damaged compartment is genuinely injured tissue it must differ
    # architecturally from the intact compartment of the SAME lung.
    by_mouse = defaultdict(dict)
    for r in out_rows:
        panel = r["panel"] or ""
        scope = panel.split("@", 1)[1] if "@" in panel else panel
        by_mouse[(r["mouse_id"], r["genotype"], r["condition"], panel.split("@", 1)[0])][scope] = r
    contrast_rows = []
    for (mouse, geno, cond, pk), scopes in sorted(by_mouse.items()):
        for pair in (("damaged", "intact"), ("damaged_core", "intact_core")):
            d, i = scopes.get(pair[0]), scopes.get(pair[1])
            if not d or not i:
                continue
            for m in CONTRAST_METRICS:
                dv, iv = d.get(m), i.get(m)
                if dv is None or iv is None:
                    continue
                rec = {"mouse_id": mouse, "genotype": geno, "condition": cond,
                       "panel_key": pk, "pair": f"{pair[0]}_vs_{pair[1]}",
                       "metric": m, "damaged": dv, "intact": iv,
                       "ratio": (dv / iv) if iv else None,
                       "pct_change": (100.0 * (dv / iv - 1.0)) if iv else None,
                       "log2_ratio": (math.log2(dv / iv) if (iv and dv and dv > 0) else None),
                       "damaged_area_mm2": d.get("morph_compartment_area_mm2"),
                       "intact_area_mm2": i.get("morph_compartment_area_mm2")}
                contrast_rows.append(rec)

    tag = args.tag
    mp = os.path.join(outdir, f"mouse_level_morphometry{tag}.csv")
    gp = os.path.join(outdir, f"group_level_morphometry{tag}.csv")
    cp = os.path.join(outdir, f"compartment_contrast{tag}.csv")
    write_csv(mp, out_rows)
    write_csv(gp, grp_rows)
    write_csv(cp, contrast_rows)
    print(f"Wrote {len(out_rows)} mouse x scope row(s) -> {mp}")
    print(f"Wrote {len(grp_rows)} group x metric row(s) -> {gp}")
    print(f"Wrote {len(contrast_rows)} contrast row(s)   -> {cp}")
    print("n = MICE. Every ratio was formed from POOLED numerators and denominators.")
    n_mice = len({(r["mouse_id"], r["genotype"], r["condition"]) for r in out_rows})
    if n_mice < 6:
        print(f"NOTE: {n_mice} distinct animal(s). Absolute morphometry is protocol-bound "
              "(inflation pressure, fixative, section thickness); with n this small these are "
              "descriptive, not inferential.")
    bad = [r for r in out_rows if r.get("morph_connectivity_interpretable") == "false"]
    if bad:
        print(f"WARNING: connectivity columns are UNINTERPRETABLE for {len(bad)}/{len(out_rows)} "
              "rows (>90% of airspace in one confluent component). Ignore "
              "morph_*_component_* and morph_confluent_* there. Area/length columns are unaffected.")


if __name__ == "__main__":
    main()
