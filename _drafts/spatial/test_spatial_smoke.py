#!/usr/bin/env python3
"""
spatial/test_spatial_smoke.py
=====================================================================
Self-contained correctness + end-to-end test for the spatial module.
No repo data required: it synthesises a 2x2-tile slide with a KNOWN answer.

Run:
  python3 spatial/test_spatial_smoke.py

Part A -- numerical primitives, checked against closed forms
  A1 edt_capped  vs brute-force Euclidean distance on a random mask
  A2 TranslationCorrector on a RECTANGLE, where the set covariance is exactly
     gamma(dx, dy) = (W - |dx|)(H - |dy|)
  A3 Ripley's K on a homogeneous Poisson pattern in a rectangle: the
     translation-corrected K-hat must recover pi*r^2
  A4 the same K WITHOUT edge correction must be visibly biased DOWN, which is
     the whole reason the correction exists

Part B -- end-to-end
  B1 build_point_pattern.py removes PLANTED seam duplicates and only those
  B2 spatial_stats.py -> join -> aggregate_to_mouse.py -> spatial_mouse_metrics.py
  B3 the planted enrichment (a cell class deliberately packed around the
     structure) comes out > 1, and the unenriched class comes out ~ 1
=====================================================================
"""
import csv
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from spatial_core import (Raster, TranslationCorrector, UniformGrid,  # noqa: E402
                          edt_capped, rasterise_points, weighted_histogram)

PY = sys.executable
FAILS = []


def check(name, ok, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + name + (("   " + detail) if detail else ""))
    if not ok:
        FAILS.append(name)


# --------------------------------------------------------------------------
def part_a():
    print("Part A -- numerical primitives")
    rng = np.random.default_rng(7)

    # A1 -----------------------------------------------------------------
    m = rng.random((40, 55)) < 0.02
    m[0, 0] = True
    got = edt_capped(m, 25.0)
    ys, xs = np.nonzero(m)
    yy, xx = np.mgrid[0:40, 0:55]
    brute = np.sqrt(((yy[..., None] - ys) ** 2 + (xx[..., None] - xs) ** 2).min(axis=-1))
    brute = np.minimum(brute, 25.0)
    check("A1 edt_capped == brute-force EDT",
          np.allclose(got, brute, atol=1e-4), f"max err {np.abs(got - brute).max():.2e}")

    # A2 -----------------------------------------------------------------
    win = Raster(np.ones((120, 160), dtype=bool), 0.0, 0.0, 1.0)
    tc = TranslationCorrector(win, max_offset_um=20.0, cov_px_um=1.0)
    errs = []
    for dx, dy in ((0, 0), (5, 0), (0, 7), (11, -9), (-3, 4)):
        exact = (160 - abs(dx)) * (120 - abs(dy))
        w = float(tc.weights(np.array([float(dx)]), np.array([float(dy)]))[0])
        errs.append(abs(w - (160 * 120) / exact))
    check("A2 translation weight == |W| / (W-|dx|)(H-|dy|) on a rectangle",
          max(errs) < 1e-6, f"max err {max(errs):.2e}")

    # A3 / A4 ------------------------------------------------------------
    W_um, H_um = 900.0, 700.0
    n = 4000
    x = rng.random(n) * W_um
    y = rng.random(n) * H_um
    win = Raster(np.ones((int(H_um / 2), int(W_um / 2)), dtype=bool), 0.0, 0.0, 2.0)
    tc = TranslationCorrector(win, max_offset_um=120.0, cov_px_um=4.0)
    edges = np.array([0.0, 25.0, 50.0, 75.0, 100.0])
    area = win.area_um2()

    grid = UniformGrid(x, y, float(edges[-1]))
    s_tr = np.zeros(len(edges) - 1)
    s_naive = np.zeros(len(edges) - 1)
    for qi, cj, d2 in grid.query_buckets(x, y):
        if cj.size == 0:
            continue
        d = np.sqrt(d2)
        sel = (d > 0) & (d <= edges[-1])
        if not sel.any():
            continue
        ii, jj = np.nonzero(sel)
        dv = d[ii, jj]
        w = tc.weights(x[cj[jj]] - x[qi[ii]], y[cj[jj]] - y[qi[ii]])
        s_tr += weighted_histogram(dv, w, edges)
        s_naive += weighted_histogram(dv, np.ones_like(dv), edges)
    s_tr = np.cumsum(s_tr)
    s_naive = np.cumsum(s_naive)
    den = n * n / area
    k_tr = s_tr / den
    k_nv = s_naive / den
    theory = math.pi * edges[1:] ** 2

    rel_tr = np.abs(k_tr - theory) / theory
    check("A3 translation-corrected K recovers pi*r^2 under CSR",
          rel_tr.max() < 0.10,
          "rel err per bin " + ", ".join(f"{v:.3f}" for v in rel_tr))
    rel_nv = (theory - k_nv) / theory
    check("A4 uncorrected K is biased DOWN (this is why correction matters)",
          rel_nv[-1] > 0.05,
          f"uncorrected K(100um) is {100 * rel_nv[-1]:.1f}% below theory")


# --------------------------------------------------------------------------
def _write_csv(path, rows, cols):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)


def synth_slide(root, mouse="M1", geno="hom", cond="PR8", panel="LEFT",
                n_per_tile=1400, n_dup=25, seed=11):
    """2x2 tiles, 1000x1000 px cores at 0.5 um/px, 128 px halo.

    Planted truth:
      * KRT5pos cells are packed in a disc at the slide centre -> clustered, and
        they are the structure the enrichment is measured against.
      * AGERpos cells are deliberately concentrated in an annulus around that
        disc -> enrichment near the structure must exceed 1.
      * T1Apos cells are uniform -> enrichment must be ~ 1.
      * n_dup nuclei are duplicated across the vertical core seam at x = 500 px.
    """
    rng = np.random.default_rng(seed)
    px = 0.5
    core = 1000
    halo = 128
    slide = "SLIDE_A"
    slide_dir = os.path.join(root, slide)
    adir = os.path.join(slide_dir, "analysis")
    os.makedirs(adir, exist_ok=True)

    tiles = []
    for ty in (0, 1):
        for tx in (0, 1):
            tiles.append({
                "tile_id": f"t{ty}{tx}", "section_id": f"tile_{ty}{tx}",
                "pixel_size_um": px,
                "core_x": tx * core, "core_y": ty * core,
                "core_w": core, "core_h": core,
                "export_x": max(0, tx * core - halo), "export_y": max(0, ty * core - halo),
                "export_w": core + 2 * halo, "export_h": core + 2 * halo,
            })

    cx_um, cy_um = core * px, core * px      # slide centre in um (1000, 1000)
    r_pod_um = 120.0

    cells_by_tile = {t["section_id"]: [] for t in tiles}
    cell_counter = {t["section_id"]: 0 for t in tiles}

    def tile_of(gx_um, gy_um):
        tx = 0 if gx_um < core * px else 1
        ty = 0 if gy_um < core * px else 1
        return f"tile_{ty}{tx}"

    def emit(gx, gy, krt5, ager, t1a, force_tile=None):
        sec = force_tile or tile_of(gx, gy)
        t = next(tt for tt in tiles if tt["section_id"] == sec)
        cell_counter[sec] += 1
        lx = gx - t["export_x"] * px
        ly = gy - t["export_y"] * px
        cells_by_tile[sec].append({
            "image": f"{slide}_{sec}", "output_key": f"{slide}_{sec}", "panel": panel,
            "region": "damaged_parenchyma", "compartment": "alveolar",
            "region_tags": "alveolar", "cell_id": cell_counter[sec],
            "mouse_id": mouse, "section_id": sec, "genotype": geno, "condition": cond,
            "centroid_x_um": f"{lx:.4f}", "centroid_y_um": f"{ly:.4f}",
            "nucleus_area_um2": f"{rng.uniform(30, 70):.2f}",
            "KRT5_final_call": krt5, "AGER_final_call": ager, "T1A_final_call": t1a,
            "KRT5_call_status": "positive" if krt5 == 1 else "negative",
        })

    total = 4 * n_per_tile
    # background: uniform over the whole 2000x2000 um slide
    for _ in range(total):
        gx, gy = rng.random() * 2 * core * px, rng.random() * 2 * core * px
        d = math.hypot(gx - cx_um, gy - cy_um)
        krt5 = 1 if d < r_pod_um else 0
        emit(gx, gy, krt5, 0, 1 if rng.random() < 0.30 else 0)
    # AGER+ concentrated in the annulus r_pod .. r_pod+60 um around the pod
    for _ in range(600):
        a = rng.random() * 2 * math.pi
        rr = r_pod_um + rng.random() * 60.0
        gx, gy = cx_um + rr * math.cos(a), cy_um + rr * math.sin(a)
        if 0 <= gx < 2 * core * px and 0 <= gy < 2 * core * px:
            emit(gx, gy, 0, 1, 0)

    # planted seam duplicates on the vertical core boundary x = 500 px = 250 um
    seam_x = core * px
    planted = []
    for _ in range(n_dup):
        gy = rng.random() * 2 * core * px
        gx = seam_x + rng.uniform(-1.5, 1.5)
        left = f"tile_{'1' if gy >= core * px else '0'}0"
        right = f"tile_{'1' if gy >= core * px else '0'}1"
        emit(gx - 0.8, gy, 0, 0, 0, force_tile=left)
        emit(gx + 0.8, gy, 0, 0, 0, force_tile=right)
        planted.append((gx, gy))

    cell_cols = list(cells_by_tile[tiles[0]["section_id"]][0])
    summary_rows = []
    for t in tiles:
        sec = t["section_id"]
        folder = f"{mouse}_{cond}_{panel}_{sec}"
        _write_csv(os.path.join(adir, folder, f"{slide}_{sec}__cells.csv"),
                   cells_by_tile[sec], cell_cols)
        n = len(cells_by_tile[sec])
        area = core * core * px * px
        summary_rows.append({
            "image": f"{slide}_{sec}", "output_key": f"{slide}_{sec}", "panel": panel,
            "region": "damaged_parenchyma", "mouse_id": mouse, "section_id": sec,
            "genotype": geno, "condition": cond, "compartment": "alveolar",
            "region_area_um2": area, "n_nuclei": n,
            "KRT5_pos_count": sum(1 for c in cells_by_tile[sec] if c["KRT5_final_call"] == 1),
            "KRT5_pod_area_um2": 0.0, "KRT5_n_pods": 0,
        })
    _write_csv(os.path.join(adir, "run_summary.csv"), summary_rows, list(summary_rows[0]))

    for t in tiles:
        sec = t["section_id"]
        t["core_tissue_area_um2"] = core * core * px * px
        t["core_raster_area_um2"] = core * core * px * px
        t["mouse_id"] = mouse
        t["genotype"] = geno
        t["condition"] = cond
        t["panel"] = panel
        t["partitioned"] = "true"
        t["region_name"] = "damaged_parenchyma"
    _write_csv(os.path.join(slide_dir, "tile_manifest.csv"), tiles, list(tiles[0]))
    return slide_dir, slide, len(planted)


def run(cmd, cwd=None):
    r = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd)
    return r.returncode, r.stdout + r.stderr


def part_b():
    print("Part B -- end to end")
    repo = os.path.dirname(HERE)
    for need in ("aggregate_to_mouse.py", "aggregate_tiles_to_slide.py"):
        if not os.path.isfile(os.path.join(repo, need)):
            check(f"B0 {need} beside spatial/", False,
                  f"expected at {os.path.join(repo, need)}")
            return
    tmp = tempfile.mkdtemp(prefix="ifq_spatial_")
    try:
        slide_dir, slide, n_planted = synth_slide(tmp)

        rc, out = run([PY, os.path.join(HERE, "build_point_pattern.py"),
                       "--slide-dir", slide_dir])
        check("B1 build_point_pattern.py ran", rc == 0, out.strip().splitlines()[-1] if out else "")
        if rc:
            print(out)
            return
        qc = json.load(open(os.path.join(slide_dir, "spatial",
                                         f"{slide}__pointpattern_qc.json")))
        dropped = qc["n_cells_dropped_as_seam_duplicates"]
        chance = qc["estimated_chance_merge_pairs"]
        check("B1 every planted seam duplicate removed",
              dropped >= n_planted, f"dropped {dropped}, planted {n_planted}")
        check("B1 excess merges are within the reported chance estimate",
              (dropped - n_planted) <= max(4.0, 3.0 * chance),
              f"excess {dropped - n_planted}, chance estimate {chance:.1f}")
        check("B1 chance false-merge rate is measured, not assumed",
              "estimated_chance_merge_fraction_of_pairs" in qc,
              f"{100 * qc['estimated_chance_merge_fraction_of_pairs']:.1f}% of merges")
        check("B1 coordinate self-check vs aggregate_tiles_to_slide passed",
              qc["coordinate_selfcheck_max_delta_um"] == 0.0)

        prof = os.path.join(tmp, "profile.json")
        base = json.load(open(os.path.join(HERE, "config", "spatial_profiles.json")))
        p = base["profiles"]["ifng_ko_pr8_ectopic_pod"]
        p["structures"][0]["source"] = "cell_class_dilation"
        p["structures"][0]["fallback"] = {"source": "cell_class_dilation",
                                          "cell_class": "KRT5pos", "dilate_um": 4.0}
        p["structures"][0]["target_classes"] = ["AGERpos", "T1Apos"]
        p["raster_um"] = 4.0
        p["covariance_um"] = 16.0
        p["window"]["dilate_um"] = 24.0
        p["max_query_points"] = 20000
        json.dump(base, open(prof, "w"), indent=1)

        rc, out = run([PY, os.path.join(HERE, "spatial_stats.py"),
                       "--points", os.path.join(slide_dir, "spatial", f"{slide}__points.csv"),
                       "--profile", "ifng_ko_pr8_ectopic_pod",
                       "--profiles-json", prof,
                       "--allow-structure-fallback",
                       "--n-permutations", "199"])
        check("B2 spatial_stats.py ran", rc == 0, "" if rc == 0 else out[-900:])
        if rc:
            return
        srow = next(iter(csv.DictReader(
            open(os.path.join(slide_dir, "spatial", "spatial_summary.csv"),
                 encoding="utf-8-sig"))))

        rc, out = run([PY, os.path.join(repo, "aggregate_tiles_to_slide.py"),
                       "--slide-root", tmp, "--outdir", os.path.join(tmp, "stats")])
        check("B2 aggregate_tiles_to_slide.py ran", rc == 0, "" if rc == 0 else out[-900:])
        if rc:
            return
        target = os.path.join(tmp, "stats", "slide_level_summary.csv")

        rc, out = run([PY, os.path.join(HERE, "join_spatial_to_summary.py"),
                       "--target", target,
                       "--spatial", os.path.join(slide_dir, "spatial", "spatial_summary.csv")])
        check("B2 join_spatial_to_summary.py ran", rc == 0, "" if rc == 0 else out[-900:])
        if rc:
            return
        joined = os.path.join(tmp, "stats", "slide_level_summary_with_spatial.csv")

        rc, out = run([PY, os.path.join(repo, "aggregate_to_mouse.py"), joined,
                       "--outdir", os.path.join(tmp, "stats")])
        check("B2 UNMODIFIED aggregate_to_mouse.py accepted the joined file",
              rc == 0, "" if rc == 0 else out[-900:])
        if rc:
            return
        mouse_csv = os.path.join(tmp, "stats", "mouse_level_summary.csv")
        mrow = next(iter(csv.DictReader(open(mouse_csv, encoding="utf-8-sig"))))
        carried = [c for c in mrow if c.startswith("class_spatial_") or "_to_KRT5pod_" in c
                   or "_nn_" in c]
        check("B2 spatial sums survived aggregate_to_mouse pooling",
              len(carried) > 20, f"{len(carried)} spatial columns carried to mouse level")

        rc, out = run([PY, os.path.join(HERE, "spatial_mouse_metrics.py"), mouse_csv,
                       "--outdir", os.path.join(tmp, "stats")])
        check("B2 spatial_mouse_metrics.py ran", rc == 0, "" if rc == 0 else out[-900:])
        if rc:
            return
        met = {}
        with open(os.path.join(tmp, "stats", "spatial_mouse_metrics.csv"),
                  encoding="utf-8-sig") as fh:
            for r in csv.DictReader(fh):
                met[r["metric"]] = float(r["value"])

        ag = met.get("spatial_enrichment_AGERpos_to_KRT5pod_d0_10um")
        t1 = met.get("spatial_enrichment_T1Apos_to_KRT5pod_d0_10um")
        check("B3 planted AGER+ enrichment near the structure > 1",
              ag is not None and ag > 1.3, f"enrichment = {ag}")
        check("B3 unenriched T1A+ class is ~ 1",
              t1 is not None and 0.6 < t1 < 1.6, f"enrichment = {t1}")

        grp = list(csv.DictReader(open(os.path.join(tmp, "stats", "spatial_group_metrics.csv"),
                                       encoding="utf-8-sig")))
        check("B3 n=1 group rows are stamped inference_supported=no",
              all(g["inference_supported"] == "no" for g in grp) and
              all(g["sd"] == "" for g in grp),
              f"{len(grp)} group x metric rows, all n_mice=1, sd left blank")

        pk = [k for k in srow if k.startswith("spatial_perm_p_")]
        check("B3 within-slide permutation p-value emitted as METADATA only",
              bool(pk) and not any(k in mrow for k in pk),
              f"{pk[:2]} present per slide, absent from mouse level (by design)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    part_a()
    part_b()
    print("")
    if FAILS:
        print(f"{len(FAILS)} FAILURE(S): " + "; ".join(FAILS))
        sys.exit(1)
    print("all checks passed")
