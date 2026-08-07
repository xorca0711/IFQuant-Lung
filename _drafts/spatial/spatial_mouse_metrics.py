#!/usr/bin/env python3
"""
spatial/spatial_mouse_metrics.py
=====================================================================
STAGE S4: form the spatial ENDPOINTS from POOLED per-mouse sums.

Input : mouse_level_summary.csv written by the UNCHANGED aggregate_to_mouse.py
Output: spatial_mouse_metrics.csv   one row per (mouse_id, genotype, condition,
                                    panel, metric)  --  n = MICE
        spatial_group_metrics.csv   mean / sd / sem / n_mice per group x metric

Why the ratios are computed HERE and not earlier
------------------------------------------------
Every spatial quantity emitted by spatial_stats.py is a SUM. aggregate_to_mouse
pools those sums over all sections, slides and tiles belonging to one animal and
carries the totals. Only now, with one pooled numerator and one pooled
denominator per mouse, is the ratio formed. This is the same discipline the repo
already applies to pod area fraction (aggregate_to_mouse.py lines 325-332) and it
is what stops two sections of unequal size from being averaged into a wrong
answer.

Recomputed here (all RECOMPUTE, none SUM):
  G_AB(r)            nn numerator_total / nn eligible_total          per bin
  median NN distance linear interpolation of the pooled G curve
  composition(r)     neighbour_total / total_neighbour_total
  enrichment(r)      neighbour_total / expected_neighbour_total      (random labelling)
  K_AB(r)            sum(S) / sum(D)          translation corrected
  L(r) - r           sqrt(K/pi) - r
  g(r)               annulus S / (D * annulus area)
  distance-to-structure enrichment:
                     (cells in bin / all cells) / (tissue area in bin / all area)

What this script refuses to do
------------------------------
  * It never tests across cells or across tiles. Its rows are per MOUSE.
  * When a group has n_mice < --min-n (default 3) it writes the descriptive
    numbers and stamps `inference_supported = no`, with the reason. At the
    current pilot (n = 1 per group cell) sd and sem returned by
    aggregate_to_mouse._stats() are 0.0 by construction (its lines 353-356) --
    that zero is the absence of a variance estimate, not a small variance, and
    reporting it as an error bar would be a fabrication.

Standard library only.
=====================================================================
"""
import argparse
import csv
import math
import os
import re
import sys
from collections import defaultdict

_HERE = os.path.dirname(os.path.abspath(__file__))
_REPO = os.path.dirname(_HERE)
for p in (_REPO, _HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

try:
    from aggregate_to_mouse import KEY_COLS, _num, _stats
except ImportError:
    sys.exit("ERROR: spatial/ must sit inside the IFQuant-Lung repo beside "
             "aggregate_to_mouse.py; its per-group statistics are reused, not "
             "reimplemented.")

MIN_N_DEFAULT = 3

# aggregate_to_mouse renames class_<X>_count -> class_<X>_count_total (line 337)
NN_NUM = re.compile(r"^class_(?P<tag>.+)_nn_(?P<bin>d[^_]+_[^_]+um)_count_total$")
NN_DEN = re.compile(r"^class_(?P<tag>.+)_nn_(?P<bin>d[^_]+_[^_]+um)_eligible_count_total$")
NBR = re.compile(r"^class_(?P<tag>.+)_nbr_(?P<r>r[0-9p]+um)_neighbour_count_total$")
K_S = re.compile(r"^class_(?P<tag>.+)_K_(?P<bin>d[^_]+_[^_]+um)_S_count_total$")
ST_CELL = re.compile(r"^class_(?P<cls>.+)_to_(?P<struct>[^_]+)_(?P<bin>d[^_]+_[^_]+um)_count_total$")
ST_AREA = re.compile(r"^(?P<struct>[^_]+)_(?P<bin>d[^_]+_[^_]+um)_positive_area_um2_total$")


def bin_hi_um(label):
    """'d0_25um' -> 25.0 ; 'dm50_m25um' -> -25.0 ; 'd0_2p5um' -> 2.5"""
    hi = label[1:-2].split("_")[-1]
    neg = hi.startswith("m")
    if neg:
        hi = hi[1:]
    v = float(hi.replace("p", "."))
    return -v if neg else v


def bin_lo_um(label):
    lo = label[1:-2].split("_")[0]
    neg = lo.startswith("m")
    if neg:
        lo = lo[1:]
    v = float(lo.replace("p", "."))
    return -v if neg else v


def read_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        if rd.fieldnames is None:
            sys.exit(f"ERROR: {path} is empty.")
        return list(rd.fieldnames), [r for r in rd if any((v or "").strip() for v in r.values())]


def _g(row, col):
    v = _num(row.get(col))
    return v


def metrics_for_mouse(row, header):
    """Every spatial endpoint for one mouse, as {metric_name: value}."""
    out = {}

    # ---- 1. nearest-neighbour CDF and pooled median -----------------------
    nn = defaultdict(dict)
    for c in header:
        m = NN_NUM.match(c)
        if m:
            nn[m.group("tag")].setdefault(m.group("bin"), {})["num"] = _g(row, c) or 0.0
        m = NN_DEN.match(c)
        if m:
            nn[m.group("tag")].setdefault(m.group("bin"), {})["den"] = _g(row, c) or 0.0
    for tag, bins in nn.items():
        curve = []
        for b, nd in sorted(bins.items(), key=lambda kv: bin_hi_um(kv[0])):
            den = nd.get("den", 0.0)
            g = (nd.get("num", 0.0) / den) if den > 0 else None
            r = bin_hi_um(b)
            out[f"spatial_G_{tag}_at_{b}"] = g
            if g is not None:
                curve.append((r, g))
        out[f"spatial_median_nn_um_{tag}"] = _interp_quantile(curve, 0.5)
        out[f"spatial_q25_nn_um_{tag}"] = _interp_quantile(curve, 0.25)

    # ---- 2. neighbourhood composition and random-labelling enrichment -----
    for c in header:
        m = NBR.match(c)
        if not m:
            continue
        tag, r = m.group("tag"), m.group("r")
        hit = _g(row, c) or 0.0
        tot = _g(row, f"class_{tag}_nbr_{r}_total_neighbour_count_total") or 0.0
        exp = _g(row, f"class_{tag}_nbr_{r}_expected_neighbour_count_total") or 0.0
        out[f"spatial_composition_{tag}_{r}"] = (hit / tot) if tot > 0 else None
        out[f"spatial_randomlabel_enrichment_{tag}_{r}"] = (hit / exp) if exp > 0 else None

    # ---- 3. Ripley K / L / g ---------------------------------------------
    ks = defaultdict(dict)
    for c in header:
        m = K_S.match(c)
        if m:
            ks[m.group("tag")][m.group("bin")] = _g(row, c) or 0.0
    for tag, bins in ks.items():
        den = _g(row, f"class_{tag}_K_denominator_per_um2_count_total") or 0.0
        if den <= 0:
            continue
        for b, s in sorted(bins.items(), key=lambda kv: bin_hi_um(kv[0])):
            r = bin_hi_um(b)
            k = s / den
            out[f"spatial_K_{tag}_at_{b}"] = k
            out[f"spatial_Lminusr_{tag}_at_{b}"] = math.sqrt(k / math.pi) - r if k >= 0 else None
            sa = _g(row, f"class_{tag}_g_{b}_S_count_total") or 0.0
            lo = bin_lo_um(b)
            ring = math.pi * (r * r - lo * lo)
            out[f"spatial_g_{tag}_at_{b}"] = (sa / (den * ring)) if ring > 0 else None

    # ---- 4. distance-to-structure, area-normalised enrichment -------------
    cells = defaultdict(dict)
    areas = defaultdict(dict)
    for c in header:
        m = ST_CELL.match(c)
        if m:
            cells[(m.group("cls"), m.group("struct"))][m.group("bin")] = _g(row, c) or 0.0
        m = ST_AREA.match(c)
        if m:
            areas[m.group("struct")][m.group("bin")] = _g(row, c) or 0.0
    for (cls, struct), bins in cells.items():
        abins = areas.get(struct, {})
        tot_c = sum(bins.values())
        tot_a = sum(abins.values())
        for b in sorted(bins, key=bin_hi_um):
            fc = (bins[b] / tot_c) if tot_c > 0 else None
            fa = (abins.get(b, 0.0) / tot_a) if tot_a > 0 else None
            out[f"spatial_cellfrac_{cls}_to_{struct}_{b}"] = fc
            out[f"spatial_areafrac_{struct}_{b}"] = fa
            out[f"spatial_enrichment_{cls}_to_{struct}_{b}"] = \
                (fc / fa) if (fc is not None and fa) else None
    return out


def _interp_quantile(curve, q):
    """Linear interpolation of r at G(r) = q on a pooled, monotone CDF."""
    prev_r, prev_g = 0.0, 0.0
    for r, g in curve:
        if g is None:
            continue
        if g >= q:
            if g == prev_g:
                return r
            return prev_r + (q - prev_g) * (r - prev_r) / (g - prev_g)
        prev_r, prev_g = r, g
    return None


def main():
    ap = argparse.ArgumentParser(
        description="Recompute spatial endpoints from POOLED per-mouse sums. n = MICE.")
    ap.add_argument("mouse_level_summary", help="mouse_level_summary.csv from aggregate_to_mouse.py")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--min-n", type=int, default=MIN_N_DEFAULT,
                    help=f"minimum mice per group before inference is marked supported "
                         f"(default {MIN_N_DEFAULT})")
    args = ap.parse_args()

    header, rows = read_rows(args.mouse_level_summary)
    for c in KEY_COLS:
        if c not in header:
            sys.exit(f"ERROR: {args.mouse_level_summary} lacks '{c}'. This must be the output of "
                     "aggregate_to_mouse.py, not a hand-made file.")
    if not any(c.startswith("class_spatial_") or "_nn_" in c or "_K_" in c for c in header):
        sys.exit("ERROR: no spatial columns in the mouse-level summary. Run "
                 "join_spatial_to_summary.py before aggregate_to_mouse.py, so the spatial "
                 "sums are pooled by the SAME code that pools the engine's counts.")

    outdir = args.outdir or os.path.dirname(os.path.abspath(args.mouse_level_summary))
    os.makedirs(outdir, exist_ok=True)

    mouse_rows = []
    for r in rows:
        base = {k: r.get(k, "NA") for k in KEY_COLS}
        for metric, value in metrics_for_mouse(r, header).items():
            if value is None:
                continue
            rec = dict(base)
            rec["metric"] = metric
            rec["value"] = value
            mouse_rows.append(rec)

    groups = defaultdict(list)
    for r in mouse_rows:
        groups[(r["genotype"], r["condition"], r["panel"], r["metric"])].append(r["value"])

    group_rows = []
    for (geno, cond, panel, metric), vals in sorted(groups.items()):
        n, mean, sd, sem = _stats(vals)
        supported = n >= args.min_n
        group_rows.append({
            "genotype": geno, "condition": cond, "panel": panel, "metric": metric,
            "n_mice": n, "mean": mean,
            "sd": sd if n > 1 else "",
            "sem": sem if n > 1 else "",
            "inference_supported": "yes" if supported else "no",
            "inference_note": "" if supported else
                (f"n_mice={n} < {args.min_n}. This is a DESCRIPTIVE value for the animals "
                 "measured. Between-animal variance is unestimated, so no comparison, "
                 "p-value or error bar is supportable. sd/sem are left blank rather than "
                 "written as 0."),
        })

    mp = os.path.join(outdir, "spatial_mouse_metrics.csv")
    gp = os.path.join(outdir, "spatial_group_metrics.csv")
    for path, data in ((mp, mouse_rows), (gp, group_rows)):
        if not data:
            open(path, "w").close()
            continue
        cols = []
        for r in data:
            for c in r:
                if c not in cols:
                    cols.append(c)
        with open(path, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=cols)
            w.writeheader()
            for r in data:
                w.writerow(r)

    n_mice = len({(r["mouse_id"], r["genotype"], r["condition"]) for r in mouse_rows})
    unsupported = sum(1 for r in group_rows if r["inference_supported"] == "no")
    print(f"Wrote {len(mouse_rows)} mouse x metric rows -> {mp}")
    print(f"Wrote {len(group_rows)} group x metric rows -> {gp}")
    print(f"Distinct animals: {n_mice}  (this is the statistical n)")
    if unsupported:
        print(f"WARNING: {unsupported} of {len(group_rows)} group x metric rows have "
              f"n_mice < {args.min_n} and are marked inference_supported=no. "
              "Report them as descriptive values only.")


if __name__ == "__main__":
    main()
