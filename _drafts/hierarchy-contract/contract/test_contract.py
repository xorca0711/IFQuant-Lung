#!/usr/bin/env python3
"""
test_contract.py -- pins the module contract to the REAL aggregate_to_mouse.py.

These tests are the regression net for the one thing that must never break:
the set of column-name suffixes aggregate_to_mouse.py will actually aggregate.
If someone widens classify_columns, or renames a category, or "helpfully" adds
a generic carry-forward, these tests fail loudly instead of every past run
quietly changing meaning.

Run:
  python3 contract/test_contract.py --repo C:/Users/dream/Documents/GitHub/IFQuant-Lung
  python3 -m unittest discover -s contract          (when repo == parent dir)

Standard library only.
"""
import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get("IFQ_REPO", os.path.dirname(HERE))
if "--repo" in sys.argv:
    REPO = sys.argv[sys.argv.index("--repo") + 1]
    del sys.argv[sys.argv.index("--repo"): sys.argv.index("--repo") + 2]

sys.path.insert(0, HERE)
sys.path.insert(0, REPO)
from ifq_contract import (                                     # noqa: E402
    check_csv, check_ownership, check_provenance, load_aggregator,
    fate_of, FORBIDDEN_ENDINGS,
)

classify_columns, KEY_COLS, ROW_ID_COLS, _num = load_aggregator(REPO)

BASE = ["image", "output_key", "region", "section_id",
        "mouse_id", "genotype", "condition", "panel"]


def write_csv(path, header, rows):
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=header)
        w.writeheader()
        for r in rows:
            w.writerow(r)


def row(**kw):
    r = {"image": "img", "output_key": "k1", "region": "damaged", "section_id": "s1",
         "mouse_id": "M1", "genotype": "het", "condition": "pr8", "panel": "LEFT@damaged"}
    r.update(kw)
    return r


class TestVocabulary(unittest.TestCase):
    """The suffix whitelist, asserted against the live classify_columns()."""

    def cats(self, cols):
        return classify_columns(cols)

    def test_recognised_suffixes_are_summed(self):
        for col in ["region_area_um2", "n_nuclei",
                    "X_pos_count", "X_positive_area_um2", "X_n_components",
                    "X_pod_area_um2", "X_n_pods", "class_Y_count",
                    "X_morphology_pos_count", "X_final_positive_cell_count"]:
            with self.subTest(col=col):
                self.assertIn(col, self.cats([col])["sum_cols"],
                              "%s must be summed" % col)

    def test_unrecognised_columns_are_dropped(self):
        for col in ["alveolar_area_um2", "mean_linear_intercept_um",
                    "septal_thickness_um", "my_ratio", "airspace_frac",
                    "damaged_area_um2", "intact_area_um2"]:
            with self.subTest(col=col):
                self.assertNotIn(col, self.cats([col])["sum_cols"],
                                 "%s must NOT be summed" % col)
                self.assertEqual(
                    fate_of(col, self.cats([col]), KEY_COLS, ROW_ID_COLS)[0],
                    "DROPPED")

    def test_mean_pod_area_is_excluded_from_pod_area(self):
        """aggregate_to_mouse.py:169 -- summing a mean would be silently wrong."""
        c = self.cats(["KRT5_mean_pod_area_um2"])
        self.assertNotIn("KRT5_mean_pod_area_um2", c["pod_area"])
        self.assertNotIn("KRT5_mean_pod_area_um2", c["sum_cols"])

    def test_true_pos_count_is_dropped_entirely(self):
        """Excluded from pos_count (line 123) and added to no other category."""
        c = self.cats(["X_true_pos_count"])
        self.assertNotIn("X_true_pos_count", c["pos_count"])
        self.assertNotIn("X_true_pos_count", c["sum_cols"])

    def test_class_prefix_beats_marker_suffix(self):
        c = self.cats(["class_A_indeterminate_count", "M_indeterminate_count"])
        self.assertIn("class_A_indeterminate_count", c["sum_cols"])
        self.assertIn("M_indeterminate_count", c["sum_cols"])
        self.assertNotIn("class_A_indeterminate_count", c["class_count"])

    def test_denominator_matches_by_exact_name_only(self):
        for near in ["tissue_region_area_um2", "region_area_um2_core", "n_nuclei_total"]:
            with self.subTest(col=near):
                self.assertNotIn(near, self.cats([near])["sum_cols"])

    def test_no_generic_carry_forward(self):
        """The load-bearing property: a module metric outside the vocabulary
        VANISHES rather than aggregating wrongly."""
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "in.csv")
            header = BASE + ["region_area_um2", "n_nuclei", "novel_metric_xyz"]
            write_csv(p, header, [
                row(output_key="k1", section_id="s1",
                    region_area_um2="1000", n_nuclei="10", novel_metric_xyz="7"),
                row(output_key="k2", section_id="s2",
                    region_area_um2="2000", n_nuclei="20", novel_metric_xyz="9"),
            ])
            subprocess.run(
                [sys.executable, os.path.join(REPO, "aggregate_to_mouse.py"),
                 p, "--outdir", d], check=True, capture_output=True)
            with open(os.path.join(d, "mouse_level_summary.csv"), encoding="utf-8") as fh:
                out_header = next(csv.reader(fh))
            self.assertNotIn("novel_metric_xyz", out_header)
            self.assertNotIn("novel_metric_xyz_total", out_header)
            self.assertIn("total_tissue_area_um2", out_header)


class TestDenominatorScope(unittest.TestCase):
    """panel@scope is the ONLY way to separate two denominators for one mouse."""

    def test_region_does_not_separate_groups(self):
        self.assertNotIn("region", KEY_COLS)
        self.assertIn("region", ROW_ID_COLS)
        self.assertEqual(KEY_COLS, ["mouse_id", "genotype", "condition", "panel"])

    def test_two_scopes_give_two_independent_denominators(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "in.csv")
            header = BASE + ["region_area_um2", "KRT5_pod_area_um2",
                             "damage_positive_area_um2"]
            write_csv(p, header, [
                row(output_key="eng@s1", panel="LEFT@damaged", region="damaged",
                    region_area_um2="1000000", KRT5_pod_area_um2="20000",
                    damage_positive_area_um2=""),
                row(output_key="mor@s1", panel="LEFT@parenchyma", region="parenchyma",
                    region_area_um2="10000000", KRT5_pod_area_um2="",
                    damage_positive_area_um2="1000000"),
            ])
            subprocess.run(
                [sys.executable, os.path.join(REPO, "aggregate_to_mouse.py"),
                 p, "--outdir", d], check=True, capture_output=True)
            with open(os.path.join(d, "mouse_level_summary.csv"),
                      encoding="utf-8") as fh:
                out = {r["panel"]: r for r in csv.DictReader(fh)}
            self.assertAlmostEqual(float(out["LEFT@damaged"]["KRT5_pod_area_frac"]), 0.02)
            self.assertAlmostEqual(
                float(out["LEFT@parenchyma"]["damage_positive_area_fraction"]), 0.1)

    def test_blank_denominator_does_not_contribute(self):
        """Blank -> None (line 51) -> filtered (line 227). This is what lets a
        numerator-only module join a group it does not own the denominator of."""
        self.assertIsNone(_num(""))
        self.assertIsNone(_num("NA"))
        self.assertIsNone(_num(None))
        self.assertEqual(_num("3.5"), 3.5)


class TestValidator(unittest.TestCase):

    def _check(self, header, rows):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "m.csv")
            write_csv(p, header, rows)
            return check_csv(p, classify_columns, KEY_COLS, ROW_ID_COLS, _num)

    def test_flags_dropped_numeric_column(self):
        e, _w, _i, _r, _h = self._check(
            BASE + ["region_area_um2", "mean_linear_intercept_um"],
            [row(region_area_um2="1000", mean_linear_intercept_um="45.2")])
        self.assertTrue(any("SILENTLY DROP" in m for m in e))

    def test_accepts_contract_compliant_column(self):
        e, _w, _i, _r, _h = self._check(
            BASE + ["region_area_um2", "morph_airspace_positive_area_um2"],
            [row(region_area_um2="1000", morph_airspace_positive_area_um2="700")])
        self.assertEqual(e, [])

    def test_flags_missing_required_column(self):
        header = [c for c in BASE if c != "section_id"] + ["region_area_um2"]
        e, _w, _i, _r, _h = self._check(
            header, [{k: v for k, v in row(region_area_um2="1000").items()
                      if k in header}])
        self.assertTrue(any("missing required column" in m for m in e))

    def test_flags_duplicate_row_identity(self):
        e, _w, _i, _r, _h = self._check(
            BASE + ["region_area_um2"],
            [row(region_area_um2="1000"), row(region_area_um2="2000")])
        self.assertTrue(any("duplicate" in m for m in e))

    def test_flags_bad_mouse_id(self):
        e, _w, _i, _r, _h = self._check(
            BASE + ["region_area_um2"],
            [row(mouse_id="NA", region_area_um2="1000")])
        self.assertTrue(any("mouse_id" in m for m in e))

    def test_warns_on_missing_panel_scope(self):
        _e, w, _i, _r, _h = self._check(
            BASE + ["region_area_um2"],
            [row(panel="LEFT", region_area_um2="1000")])
        self.assertTrue(any("@<denominator_scope>" in m for m in w))

    def test_ownership_collision_detected(self):
        with tempfile.TemporaryDirectory() as d:
            a = os.path.join(d, "a.csv")
            b = os.path.join(d, "b.csv")
            write_csv(a, BASE + ["region_area_um2"],
                      [row(output_key="a1", region_area_um2="1000")])
            write_csv(b, BASE + ["region_area_um2"],
                      [row(output_key="b1", region_area_um2="1000")])
            probs = check_ownership([a, b], classify_columns, KEY_COLS)
            self.assertTrue(any("DOUBLE-COUNTED DENOMINATOR" in m for m in probs))

    def test_forbidden_endings_cover_the_derived_names(self):
        for bad in ("_frac", "_fraction", "_ratio", "_mean", "_per_mm2", "_index"):
            self.assertIn(bad, FORBIDDEN_ENDINGS)


class TestProvenance(unittest.TestCase):

    def base(self):
        return {
            "provenance_schema_version": "1.0.0",
            "module": {"module_id": "morphometry.x", "module_version": "0.1.0",
                       "level": "slide", "panel_scope": "LEFT@parenchyma",
                       "emits": ["morph_x_positive_area_um2"],
                       "owns_region_area_um2": True},
            "run": {"run_id": "r1", "started_utc": "2026-08-07T00:00:00Z",
                    "command_line": "python x.py"},
            "software": {"ifquant_repo_commit": "abc", "contract_version": "1.0.0",
                         "runtimes": [{"name": "python", "version": "3.12"}]},
            "inputs": [{"role": "source_image", "path": "a.vsi", "sha256": None,
                        "sha256_skipped_reason": "12 GB"}],
            "parameters": {"locked": {"t": 150},
                           "lock_source": {"t": "config/injury_models/x.json#t"},
                           "calibration": {"calibrated_on": "controls",
                                           "criterion": "alpha=0.01",
                                           "locked_utc": "2026-08-05T00:00:00Z"},
                           "free": {}},
            "outputs": [{"path": "x.csv", "n_rows": 2,
                         "row_identity_key": ["output_key", "region",
                                              "section_id", "panel"]}],
            "qc": {"status": "ok"},
            "contract": {"contract_version": "1.0.0", "sum_columns": [],
                         "dropped_columns": []},
            "statistics": {"n_definition": "mouse_id"},
        }

    def run_check(self, doc):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "p.json")
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(doc, fh)
            return check_provenance(p)

    def test_good(self):
        self.assertEqual(self.run_check(self.base()), [])

    def test_n_definition_must_be_mouse(self):
        d = self.base(); d["statistics"]["n_definition"] = "section_id"
        self.assertTrue(any("n = MICE" in m for m in self.run_check(d)))

    def test_locked_value_needs_lock_source(self):
        d = self.base(); d["parameters"]["lock_source"] = {}
        self.assertTrue(any("lock_source" in m for m in self.run_check(d)))

    def test_null_sha_needs_reason(self):
        d = self.base(); d["inputs"][0]["sha256_skipped_reason"] = None
        self.assertTrue(any("sha256" in m for m in self.run_check(d)))

    def test_runtimes_required(self):
        d = self.base(); d["software"]["runtimes"] = []
        self.assertTrue(any("runtimes" in m for m in self.run_check(d)))

    def test_area_reconciliation_cannot_pass_over_tolerance(self):
        d = self.base()
        d["qc"]["area_reconciliation"] = {"rel_diff": 0.08, "tolerance": 0.01,
                                          "passed": True}
        self.assertTrue(any("exceeds tolerance" in m for m in self.run_check(d)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
