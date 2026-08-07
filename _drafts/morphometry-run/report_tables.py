#!/usr/bin/env python3
"""
report_tables.py -- render the morphometry outputs as readable tables.

  python report_tables.py <stats_dir> [<stats_dir> ...]

Each stats dir must contain mouse_level_morphometry*.csv and
compartment_contrast*.csv written by morphometry_derive.py.
Multiple dirs are compared side by side (that is how the resolution
dependence is reported).
"""
import csv
import glob
import math
import os
import sys

GROUP = {
    "m4-1": ("het", "PR8 INFECTED"),
    "m2":   ("hom", "PR8 INFECTED"),
    "m4-2": ("het", "uninfected"),
    "m6":   ("hom", "uninfected"),
}
ORDER = ["m4-1", "m2", "m4-2", "m6"]

HEADLINE = [
    ("morph_tissue_fraction",              "nucleated area fraction", "{:.4f}"),
    ("morph_mli_direct_um",                "MLI direct (um)",         "{:.2f}"),
    ("morph_mli_indirect_um",              "MLI indirect 2L/N (um)",  "{:.2f}"),
    ("morph_mli_anisotropy_ratio",         "MLI anisotropy max/min",  "{:.3f}"),
    ("morph_airspace_width_edmmean_um",    "airspace width 4*EDM (um)", "{:.2f}"),
    ("morph_airspace_width_edmmedian_um",  "airspace width 4*med (um)", "{:.2f}"),
    ("morph_wall_thickness_edmmean_um",    "wall thickness 4*EDM (um)", "{:.3f}"),
    ("morph_wall_thickness_2a_over_b_um",  "wall thickness 2A/B (um)",  "{:.3f}"),
    ("morph_surface_density_per_um",       "surface density Sv (1/um)", "{:.5f}"),
    ("morph_chord_truncated_fraction",     "chords truncated (frac)",   "{:.3f}"),
    ("morph_finepass_coverage_of_compartment", "fine-pass coverage",    "{:.3f}"),
    ("morph_compartment_area_mm2",         "compartment area (mm2)",    "{:.4f}"),
]


def rd(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        return list(csv.DictReader(fh))


def f(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def fmt(v, spec):
    return spec.format(v) if v is not None else "  --  "


def load(stats_dir):
    m = glob.glob(os.path.join(stats_dir, "mouse_level_morphometry*.csv"))
    c = glob.glob(os.path.join(stats_dir, "compartment_contrast*.csv"))
    if not m:
        sys.exit(f"no mouse_level_morphometry*.csv in {stats_dir}")
    rows = rd(m[0])
    by = {}
    for r in rows:
        by[(r["mouse_id"], r.get("morph_scope") or r["panel"].split("@")[-1])] = r
    return by, (rd(c[0]) if c else [])


def main():
    dirs = sys.argv[1:]
    if not dirs:
        sys.exit(__doc__)
    loaded = [(d, *load(d)) for d in dirs]

    for scope in ("parenchyma", "damaged", "intact", "damaged_edge", "damaged_core", "intact_edge", "intact_core"):
        print("\n" + "=" * 118)
        print(f"COMPARTMENT: {scope}")
        print("=" * 118)
        for d, by, _ in loaded:
            tag = os.path.basename(d.rstrip("/\\"))
            print(f"\n-- {tag} " + "-" * (110 - len(tag)))
            hdr = f"{'metric':<30}" + "".join(f"{GROUP[m][1][:9]+' '+m:>21}" for m in ORDER)
            print(hdr)
            for col, label, spec in HEADLINE:
                cells = []
                for m in ORDER:
                    r = by.get((m, scope))
                    cells.append(fmt(f(r.get(col)) if r else None, spec))
                print(f"{label:<30}" + "".join(f"{c:>21}" for c in cells))

    print("\n" + "=" * 118)
    print("CROSS-CHECK: damaged vs intact, WITHIN each animal (percent change, damaged relative to intact)")
    print("=" * 118)
    for d, _, contrast in loaded:
        tag = os.path.basename(d.rstrip("/\\"))
        for pair in ("damaged_vs_intact", "damaged_core_vs_intact_core"):
            sub = [r for r in contrast if r["pair"] == pair]
            if not sub:
                continue
            print(f"\n-- {tag} / {pair} " + "-" * max(0, 96 - len(tag) - len(pair)))
            print(f"{'metric':<38}" + "".join(f"{GROUP[m][1][:9]+' '+m:>19}" for m in ORDER))
            metrics = []
            for r in sub:
                if r["metric"] not in metrics:
                    metrics.append(r["metric"])
            for mt in metrics:
                cells = []
                for mouse in ORDER:
                    hit = [r for r in sub if r["metric"] == mt and r["mouse_id"] == mouse]
                    v = f(hit[0]["pct_change"]) if hit else None
                    cells.append(f"{v:+.1f}%" if v is not None else "  --  ")
                print(f"{mt.replace('morph_',''):<38}" + "".join(f"{c:>19}" for c in cells))

    # the decisive comparison: is the damaged-vs-intact contrast BIGGER in
    # infected animals than in controls? If not, the architecture is tracking
    # anatomy (airway/vessel walls are AGER-negative too), not injury.
    print("\n" + "=" * 118)
    print("IS THE CONTRAST SPECIFIC TO INFECTION?")
    print("SIGNED log2(damaged/intact) per animal. `separated` = the two infected values and the two")
    print("control values do not overlap, i.e. the contrast itself discriminates infection.")
    print("=" * 118)
    for d, _, contrast in loaded:
        tag = os.path.basename(d.rstrip("/\\"))
        for pair in ("damaged_vs_intact", "damaged_core_vs_intact_core"):
            sub = [r for r in contrast if r["pair"] == pair]
            if not sub:
                continue
            print(f"\n-- {tag} / {pair}")
            metrics = []
            for r in sub:
                if r["metric"] not in metrics:
                    metrics.append(r["metric"])
            print(f"{'metric':<38}{'INFECTED log2':>26}{'CONTROL log2':>26}{'separated':>12}")
            for mt in metrics:
                def vals(pred):
                    return [f(r["log2_ratio"]) for r in sub
                            if r["metric"] == mt and pred(r["mouse_id"])
                            and f(r["log2_ratio"]) is not None]
                inf = vals(lambda m: GROUP.get(m, ("", ""))[1].startswith("PR8"))
                ctl = vals(lambda m: GROUP.get(m, ("", ""))[1].startswith("unin"))
                if len(inf) < 2 or len(ctl) < 2:
                    continue
                sep = "YES" if (min(inf) > max(ctl) or max(inf) < min(ctl)) else "no"
                si = ", ".join(f"{v:+.3f}" for v in inf)
                sc = ", ".join(f"{v:+.3f}" for v in ctl)
                print(f"{mt.replace('morph_',''):<38}{si:>26}{sc:>26}{sep:>12}")


if __name__ == "__main__":
    main()
