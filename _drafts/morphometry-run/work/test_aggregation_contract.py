#!/usr/bin/env python3
"""
test_aggregation_contract.py
=====================================================================
Proves the ONE thing this module must not get wrong: that
morphometry_slide_summary.csv survives the UNMODIFIED aggregate_to_mouse.py,
and that the resulting mouse-level MLI is the AREA-WEIGHTED pooled value, not
the mean of the per-slide MLIs.

It builds a synthetic two-section mouse where the two sections deliberately
disagree, so the wrong answer and the right answer are far apart:

  section A:  4000 chords totalling 120000 um  -> MLI 30 um
  section B:   500 chords totalling  35000 um  -> MLI 70 um

  mean of per-section MLI      = 50.0 um     <-- WRONG (pseudo-replication)
  pooled sum(len)/sum(n)       = 34.44 um    <-- RIGHT

Run:
  python test_aggregation_contract.py --repo C:/Users/dream/Documents/GitHub/IFQuant-Lung
Nothing is written inside the repo; aggregate_to_mouse.py is imported read-only
and all output goes to a temp folder.
=====================================================================
"""
import argparse
import csv
import os
import subprocess
import sys
import tempfile

ROWS = [
    # mouse M1, two sections, deliberately discordant
    dict(image="slideA", region="parenchyma_all", section_id="slideA",
         mouse_id="M1", genotype="IFNg_KO_het", condition="naive", panel="LEFT",
         region_area_um2=60_000_000.0,
         morph_tissue_positive_area_um2=12_000_000.0,
         morph_airspace_positive_area_um2=48_000_000.0,
         morph_finepass_positive_area_um2=60_000_000.0,
         morph_airspacec_positive_area_um2=48_000_000.0,
         morph_airspacec_n_components=8000.0,
         morph_airspacebig_positive_area_um2=4_000_000.0,
         class_morph_perimeter_um_count=6_000_000.0,
         class_morph_chordlen_um_count=120_000.0,
         class_morph_chordn_count=4000.0,
         class_morph_testline_um_count=300_000.0,
         class_morph_transition_count=8000.0,
         class_morph_chordtrunc_count=100.0,
         class_morph_chordtrunclen_um_count=5000.0,
         class_morph_chordlenh_um_count=60_000.0,
         class_morph_chordnh_count=2000.0,
         class_morph_chordlenv_um_count=60_000.0,
         class_morph_chordnv_count=2000.0,
         class_morph_edmhalf_um_count=1_500_000.0,
         class_morph_tissuepx_count=2_000_000.0,
         class_morph_box_eps1_count=100000.0,
         class_morph_box_eps2_count=30000.0,
         class_morph_box_eps4_count=9000.0,
         class_morph_box_eps8_count=2600.0,
         class_morph_box_eps16_count=750.0,
         class_morph_rows_count=1.0,
         class_morph_pxfine_ok_count=1.0),
    dict(image="slideB", region="parenchyma_all", section_id="slideB",
         mouse_id="M1", genotype="IFNg_KO_het", condition="naive", panel="LEFT",
         region_area_um2=10_000_000.0,
         morph_tissue_positive_area_um2=1_000_000.0,
         morph_airspace_positive_area_um2=9_000_000.0,
         morph_finepass_positive_area_um2=10_000_000.0,
         morph_airspacec_positive_area_um2=9_000_000.0,
         morph_airspacec_n_components=500.0,
         morph_airspacebig_positive_area_um2=3_000_000.0,
         class_morph_perimeter_um_count=400_000.0,
         class_morph_chordlen_um_count=35_000.0,
         class_morph_chordn_count=500.0,
         class_morph_testline_um_count=50_000.0,
         class_morph_transition_count=1000.0,
         class_morph_chordtrunc_count=50.0,
         class_morph_chordtrunclen_um_count=4000.0,
         class_morph_chordlenh_um_count=17_500.0,
         class_morph_chordnh_count=250.0,
         class_morph_chordlenv_um_count=17_500.0,
         class_morph_chordnv_count=250.0,
         class_morph_edmhalf_um_count=200_000.0,
         class_morph_tissuepx_count=166_667.0,
         class_morph_box_eps1_count=9000.0,
         class_morph_box_eps2_count=2800.0,
         class_morph_box_eps4_count=850.0,
         class_morph_box_eps8_count=250.0,
         class_morph_box_eps16_count=72.0,
         class_morph_rows_count=1.0,
         class_morph_pxfine_ok_count=1.0),
]


def approx(a, b, tol=1e-6):
    return a is not None and abs(a - b) <= tol * max(1.0, abs(b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="IFQuant-Lung checkout (read-only)")
    ap.add_argument("--workdir", default=None)
    args = ap.parse_args()

    agg = os.path.join(args.repo, "aggregate_to_mouse.py")
    if not os.path.isfile(agg):
        sys.exit(f"ERROR: not found: {agg}")

    work = args.workdir or tempfile.mkdtemp(prefix="morph_contract_")
    os.makedirs(work, exist_ok=True)
    src = os.path.join(work, "morphometry_slide_summary.csv")
    cols = []
    for r in ROWS:
        for c in r:
            if c not in cols:
                cols.append(c)
    with open(src, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols)
        w.writeheader()
        for r in ROWS:
            w.writerow(r)
    print(f"wrote synthetic slide summary -> {src}")

    stats = os.path.join(work, "stats")
    r = subprocess.run([sys.executable, agg, src, "--outdir", stats],
                       capture_output=True, text=True)
    print(r.stdout.strip())
    if r.returncode != 0:
        print(r.stderr.strip())
        sys.exit("FAIL: the UNMODIFIED aggregate_to_mouse.py rejected the morphometry CSV.")

    mp = os.path.join(stats, "mouse_level_summary.csv")
    with open(mp, newline="", encoding="utf-8-sig") as fh:
        mrows = list(csv.DictReader(fh))
    assert len(mrows) == 1, f"expected 1 mouse row, got {len(mrows)}"
    m = mrows[0]

    fails = []

    def check(name, got, want, tol=1e-6):
        okv = approx(got, want, tol)
        print(f"  {'PASS' if okv else 'FAIL'}  {name:<58} got={got}  want={want}")
        if not okv:
            fails.append(name)

    print("\n-- did aggregate_to_mouse.py recognise every primitive? --")
    for c in ["class_morph_chordlen_um_count_total", "class_morph_chordn_count_total",
              "class_morph_perimeter_um_count_total", "class_morph_edmhalf_um_count_total",
              "class_morph_tissuepx_count_total", "class_morph_box_eps8_count_total",
              "morph_tissue_positive_area_um2_total", "morph_airspace_positive_area_um2_total",
              "morph_airspacec_n_components_total", "morph_airspacec_mean_component_area_um2",
              "morph_airspace_positive_area_fraction", "total_tissue_area_um2"]:
        present = c in m
        print(f"  {'PASS' if present else 'FAIL'}  column present: {c}")
        if not present:
            fails.append("missing " + c)

    print("\n-- are the pooled values right? --")
    check("total_tissue_area_um2 (SUM)", float(m["total_tissue_area_um2"]), 70_000_000.0)
    check("chord length total (SUM)", float(m["class_morph_chordlen_um_count_total"]), 155_000.0)
    check("chord count total (SUM)", float(m["class_morph_chordn_count_total"]), 4500.0)
    # aggregate_to_mouse recomputes the area fraction from POOLED areas
    check("airspace fraction of region_area (RECOMPUTED)",
          float(m["morph_airspace_positive_area_fraction"]), 57_000_000.0 / 70_000_000.0)
    check("mean airspace component area (RECOMPUTED)",
          float(m["morph_airspacec_mean_component_area_um2"]), 57_000_000.0 / 8500.0)

    print("\n-- morphometry_derive.py: the MLI pooling hazard --")
    here = os.path.dirname(os.path.abspath(__file__))
    r2 = subprocess.run([sys.executable, os.path.join(here, "morphometry_derive.py"), mp,
                         "--outdir", stats], capture_output=True, text=True)
    print(r2.stdout.strip())
    if r2.returncode != 0:
        print(r2.stderr.strip())
        sys.exit("FAIL: morphometry_derive.py errored.")
    with open(os.path.join(stats, "mouse_level_morphometry.csv"),
              newline="", encoding="utf-8-sig") as fh:
        d = list(csv.DictReader(fh))[0]
    pooled = 155_000.0 / 4500.0
    naive = (30.0 + 70.0) / 2.0
    check("morph_mli_direct_um is the POOLED value", float(d["morph_mli_direct_um"]), pooled)
    got = float(d["morph_mli_direct_um"])
    print(f"        (the naive mean-of-sections answer would be {naive:.2f} um, "
          f"{100 * (naive / pooled - 1):+.1f}% -- this is the bug the contract prevents)")
    if abs(got - naive) < 1e-6:
        fails.append("MLI was averaged, not pooled")
    check("morph_mli_indirect_um = 2L/N", float(d["morph_mli_indirect_um"]),
          2.0 * 350_000.0 / 9000.0)
    check("morph_septal_thickness_edm_um = 4*sum/npx",
          float(d["morph_septal_thickness_edm_um"]), 4.0 * 1_700_000.0 / 2_166_667.0)
    check("morph_septal_thickness_2a_over_b_um = 2A/B",
          float(d["morph_septal_thickness_2a_over_b_um"]), 2.0 * 13_000_000.0 / 6_400_000.0)
    check("morph_airspace_fraction (of fine-pass area)",
          float(d["morph_airspace_fraction"]), 57_000_000.0 / 70_000_000.0)
    check("morph_confluent_airspace_fraction",
          float(d["morph_confluent_airspace_fraction"]), 7_000_000.0 / 57_000_000.0)
    print(f"  INFO  morph_fractal_box_dimension = {d.get('morph_fractal_box_dimension')}")
    print(f"  INFO  morph_resolution_consistent = {d.get('morph_resolution_consistent')}")

    print("")
    if fails:
        print(f"CONTRACT TEST FAILED: {len(fails)} problem(s): {fails}")
        sys.exit(1)
    print("CONTRACT TEST PASSED. morphometry_slide_summary.csv is handled correctly by the "
          "UNMODIFIED aggregate_to_mouse.py, and every ratio is formed from pooled totals.")
    print(f"artefacts in {work}")


if __name__ == "__main__":
    main()
