#!/usr/bin/env python3
"""
test_aggregation_contract.py
=====================================================================
Runs the UNMODIFIED repo aggregate_to_mouse.py against synthetic
morphometry_slide_summary CSVs and asserts, mechanically, the claims the module
makes about it. Nothing here is argued; everything is executed.

The five things being proved
  T1  A ratio column (mean linear intercept) written by a module NEVER reaches
      mouse level. It is DROPPED, silently.
  T2  A ratio wearing a recognised count suffix IS summed -- the actual hazard.
      Demonstrated on a deliberately mis-named column, and then shown absent
      from the real schema.
  T3  Pooled MLI (sum L / sum N) differs from the mean of per-slide MLIs, so the
      primitive-carrying design is not cosmetic.
  T4  Compartments carried in `panel` survive as separate mouse rows;
      compartments carried in `region` are MERGED into one row and the
      damaged-vs-intact comparison is destroyed. This is the bug the draft had.
  T5  Every column the real Groovy emits lands in the family its name claims,
      and no emitted <Name> ends in a suffix MODULE_CONTRACT.md 2.2 forbids.

Usage
  python test_aggregation_contract.py --agg <repo>/aggregate_to_mouse.py
                                      [--slide-csv <a real slide summary>]
=====================================================================
"""
import argparse
import csv
import math
import os
import subprocess
import sys
import tempfile

PY = sys.executable
FAILED = []
PASSED = []


def check(name, cond, detail=""):
    (PASSED if cond else FAILED).append(name)
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"   {detail}" if detail else ""))


def run_agg(agg, rows, outdir):
    os.makedirs(outdir, exist_ok=True)
    path = os.path.join(outdir, "slide_summary.csv")
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
    p = subprocess.run([PY, agg, path, "--outdir", outdir],
                       capture_output=True, text=True)
    if p.returncode != 0:
        return None, p.stdout + p.stderr
    with open(os.path.join(outdir, "mouse_level_summary.csv"), newline="",
              encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        return (rd.fieldnames, [r for r in rd]), p.stdout


def base_row(**kw):
    r = {"image": "slideA", "output_key": "slideA__k", "region": "parenchyma_all",
         "section_id": "slideA", "mouse_id": "m1", "genotype": "het",
         "condition": "PR8", "panel": "LEFT@parenchyma", "region_area_um2": 1_000_000.0}
    r.update(kw)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--agg", required=True)
    ap.add_argument("--slide-csv", default=None,
                    help="a real morphometry_slide_summary_ds*.csv, for T5")
    args = ap.parse_args()
    agg = os.path.abspath(args.agg)
    if not os.path.isfile(agg):
        sys.exit(f"aggregate_to_mouse.py not found: {agg}")
    tmp = tempfile.mkdtemp(prefix="ifq_contract_")

    # ---------------------------------------------------------------- T1 ----
    print("\nT1  a module-computed ratio is DROPPED, never summed")
    rows = [
        base_row(image="s1", output_key="s1__k", section_id="s1",
                 morph_mli_direct_um=61.0, morph_septal_thickness_um=3.2,
                 morph_airspace_area_um2=500000.0,
                 morph_tissue_positive_area_um2=400000.0),
        base_row(image="s2", output_key="s2__k", section_id="s2",
                 morph_mli_direct_um=97.0, morph_septal_thickness_um=5.4,
                 morph_airspace_area_um2=700000.0,
                 morph_tissue_positive_area_um2=300000.0),
    ]
    (hdr, out), log = run_agg(agg, rows, os.path.join(tmp, "t1"))
    check("morph_mli_direct_um absent from mouse level", "morph_mli_direct_um" not in hdr)
    check("morph_septal_thickness_um absent from mouse level", "morph_septal_thickness_um" not in hdr)
    check("bare *_area_um2 (morph_airspace_area_um2) absent -- contract trap 4",
          "morph_airspace_area_um2" not in hdr)
    check("*_positive_area_um2 DOES survive and is summed",
          abs(float(out[0]["morph_tissue_positive_area_um2_total"]) - 700000.0) < 1e-6,
          f"got {out[0].get('morph_tissue_positive_area_um2_total')}")
    check("its fraction is recomputed from POOLED numerator/denominator",
          abs(float(out[0]["morph_tissue_positive_area_fraction"]) - 700000.0 / 2000000.0) < 1e-9)
    # the drop is silent: nothing in stdout mentions the discarded columns
    check("the drop is SILENT (no warning mentions the dropped column)",
          "morph_mli_direct_um" not in log)

    # ---------------------------------------------------------------- T2 ----
    print("\nT2  a ratio wearing a count suffix IS summed -- the real hazard")
    rows = [
        base_row(image="s1", output_key="s1__k", section_id="s1", **{"class_morph_mli_count": 61.0}),
        base_row(image="s2", output_key="s2__k", section_id="s2", **{"class_morph_mli_count": 97.0}),
    ]
    (hdr, out), _ = run_agg(agg, rows, os.path.join(tmp, "t2"))
    v = float(out[0]["class_morph_mli_count_total"])
    check("a ratio named class_*_count is summed to nonsense (61+97=158 um)",
          abs(v - 158.0) < 1e-9, f"class_morph_mli_count_total = {v}")
    check("...and it even gets a bogus density_per_mm2",
          "class_morph_mli_density_per_mm2" in hdr)
    print("      -> this is why the schema forbids ratio-shaped names in the count family;")
    print("         T5 checks the real Groovy output for exactly this.")

    # ---------------------------------------------------------------- T3 ----
    print("\nT3  pooled MLI != mean of per-slide MLI")
    # slide 1: 1000 chords totalling 61000 um; slide 2: 100 chords totalling 9700 um
    rows = [
        base_row(image="s1", output_key="s1__k", section_id="s1",
                 **{"class_morph_chordlen000um_count": 61000.0, "class_morph_chordn000_count": 1000.0}),
        base_row(image="s2", output_key="s2__k", section_id="s2",
                 **{"class_morph_chordlen000um_count": 9700.0, "class_morph_chordn000_count": 100.0}),
    ]
    (hdr, out), _ = run_agg(agg, rows, os.path.join(tmp, "t3"))
    pooled = float(out[0]["class_morph_chordlen000um_count_total"]) / float(out[0]["class_morph_chordn000_count_total"])
    naive = (61.0 + 97.0) / 2.0
    check("pooled MLI = 64.27 um, naive mean of slide MLIs = 79.00 um",
          abs(pooled - 70700.0 / 1100.0) < 1e-9 and abs(pooled - naive) > 5.0,
          f"pooled={pooled:.2f} naive={naive:.2f} (naive is {100*(naive/pooled-1):.1f}% high)")

    # ---------------------------------------------------------------- T4 ----
    print("\nT4  compartments must be carried in `panel`, not `region`")
    # (a) region-carried -- the draft's design
    rows = [
        base_row(image="s1", output_key="s1__d", section_id="s1", region="parenchyma_damaged",
                 region_area_um2=100000.0,
                 **{"class_morph_chordlen000um_count": 2000.0, "class_morph_chordn000_count": 100.0}),
        base_row(image="s1", output_key="s1__i", section_id="s1", region="parenchyma_intact",
                 region_area_um2=900000.0,
                 **{"class_morph_chordlen000um_count": 54000.0, "class_morph_chordn000_count": 900.0}),
    ]
    (hdr, out), _ = run_agg(agg, rows, os.path.join(tmp, "t4a"))
    check("region-carried compartments MERGE into ONE mouse row", len(out) == 1,
          f"{len(out)} row(s)")
    merged = float(out[0]["class_morph_chordlen000um_count_total"]) / float(out[0]["class_morph_chordn000_count_total"])
    check("...and the damaged (20 um) vs intact (60 um) contrast is destroyed",
          abs(merged - 56.0) < 1e-9, f"one merged MLI = {merged:.2f} um; the two inputs were 20.00 and 60.00")
    # (b) panel-carried -- this module's design
    rows = [
        base_row(image="s1", output_key="s1__d", section_id="s1", region="parenchyma_damaged",
                 panel="LEFT@damaged", region_area_um2=100000.0,
                 **{"class_morph_chordlen000um_count": 2000.0, "class_morph_chordn000_count": 100.0}),
        base_row(image="s1", output_key="s1__i", section_id="s1", region="parenchyma_intact",
                 panel="LEFT@intact", region_area_um2=900000.0,
                 **{"class_morph_chordlen000um_count": 54000.0, "class_morph_chordn000_count": 900.0}),
    ]
    (hdr, out), _ = run_agg(agg, rows, os.path.join(tmp, "t4b"))
    check("panel-carried compartments stay as TWO mouse rows", len(out) == 2, f"{len(out)} row(s)")
    by = {r["panel"]: r for r in out}
    md = float(by["LEFT@damaged"]["class_morph_chordlen000um_count_total"]) / float(by["LEFT@damaged"]["class_morph_chordn000_count_total"])
    mi = float(by["LEFT@intact"]["class_morph_chordlen000um_count_total"]) / float(by["LEFT@intact"]["class_morph_chordn000_count_total"])
    check("...and the two MLIs are recoverable exactly (20.00 / 60.00)",
          abs(md - 20.0) < 1e-9 and abs(mi - 60.0) < 1e-9, f"damaged={md:.2f} intact={mi:.2f}")
    check("each compartment keeps its OWN denominator (total_tissue_area_um2)",
          abs(float(by["LEFT@damaged"]["total_tissue_area_um2"]) - 100000.0) < 1e-6 and
          abs(float(by["LEFT@intact"]["total_tissue_area_um2"]) - 900000.0) < 1e-6)

    # ---------------------------------------------------------------- T5 ----
    print("\nT5  the real emitted schema")
    if not args.slide_csv or not os.path.isfile(args.slide_csv):
        print("      (skipped: pass --slide-csv <morphometry_slide_summary_ds*.csv>)")
    else:
        with open(args.slide_csv, newline="", encoding="utf-8-sig") as fh:
            real_hdr = csv.DictReader(fh).fieldnames
        forbidden_tail = ("_mean", "_median", "_index", "_ratio", "_frac", "_fraction",
                          "_per_mm2", "_um")
        bad = []
        for c in real_hdr:
            name = None
            for suf in ("_positive_area_um2", "_pod_area_um2", "_pos_count", "_n_components", "_n_pods"):
                if c.endswith(suf):
                    name = c[: -len(suf)]
                    break
            if name is None and c.startswith("class_") and c.endswith("_count"):
                name = c[len("class_"): -len("_count")]
            if name is not None and name.endswith(forbidden_tail):
                bad.append(c)
        check("no emitted <Name> ends in a MODULE_CONTRACT 2.2 forbidden suffix",
              not bad, f"offenders: {bad}" if bad else "")
        # every numeric measurement column must be in a recognised family
        idc = {"image", "output_key", "region", "section_id", "mouse_id", "genotype",
               "condition", "panel", "module_id"}
        recognised, dropped = [], []
        for c in real_hdr:
            if c in idc or c == "region_area_um2" or c == "n_nuclei":
                continue
            if (c.endswith("_positive_area_um2") or c.endswith("_n_components")
                    or c.endswith("_pos_count") or c.endswith("_pod_area_um2")
                    or c.endswith("_n_pods")
                    or (c.startswith("class_") and c.endswith("_count"))):
                recognised.append(c)
            else:
                dropped.append(c)
        qc_only = [c for c in dropped if c.startswith("morph_")]
        check("every dropped column is QC/provenance (morph_* prefix), never a measurement",
              set(dropped) == set(qc_only), f"non-QC dropped: {sorted(set(dropped) - set(qc_only))}")
        print(f"      {len(recognised)} measurement columns survive; {len(dropped)} QC columns are dropped by design")
        # and no name would collide with an engine column
        check("no emitted measurement column lacks the morph_ namespace",
              all(("morph" in c) for c in recognised),
              f"offenders: {[c for c in recognised if 'morph' not in c][:5]}")
        # run the real file through the aggregator end to end
        outdir = os.path.join(tmp, "t5")
        os.makedirs(outdir, exist_ok=True)
        p = subprocess.run([PY, agg, args.slide_csv, "--outdir", outdir],
                           capture_output=True, text=True)
        check("the real slide CSV passes aggregate_to_mouse.py unmodified",
              p.returncode == 0, p.stdout.strip().splitlines()[-1] if p.stdout else p.stderr[:200])
        if p.returncode == 0:
            with open(os.path.join(outdir, "mouse_level_summary.csv"), newline="",
                      encoding="utf-8-sig") as fh:
                mh = csv.DictReader(fh).fieldnames
            missing = [c for c in recognised
                       if not any(m.startswith(c.rsplit("_", 1)[0]) for m in mh)]
            check("every recognised measurement column reaches mouse level",
                  not missing, f"missing: {missing[:5]}")
            mli_like = [c for c in mh if "mli" in c.lower() or "intercept" in c.lower()]
            check("NO mouse-level column called MLI/intercept exists (it is derived later)",
                  not mli_like, f"found: {mli_like}")

    print(f"\n==== {len(PASSED)} passed, {len(FAILED)} failed ====")
    if FAILED:
        for f in FAILED:
            print("  FAILED: " + f)
        sys.exit(1)


if __name__ == "__main__":
    main()
