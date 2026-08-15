import ast
import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "scripts" / "he_pipeline.py"
SPEC = importlib.util.spec_from_file_location("he_pipeline", MODULE_PATH)
he_pipeline = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(he_pipeline)


class HePipelineContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.study = json.loads(
            (ROOT / "config" / "studies" / "g_surf_he_20260812.json").read_text(
                encoding="utf-8"
            )
        )
        cls.rubric = json.loads(
            (
                ROOT
                / "config"
                / "brightfield"
                / "he_pathology_review_rubric.json"
            ).read_text(encoding="utf-8")
        )
        cls.profile = json.loads(
            (
                ROOT
                / "config"
                / "brightfield"
                / "he_stain_profiles"
                / "g_surf_he_20260812_reviewed_locked_v1.json"
            ).read_text(encoding="utf-8")
        )

    def test_runner_is_valid_python(self):
        ast.parse(MODULE_PATH.read_text(encoding="utf-8"))

    def test_study_records_approved_r1_and_exact_blinding(self):
        self.assertEqual(self.study["current_release"]["id"], "R1")
        self.assertEqual(
            self.study["current_release"]["decision"], "APPROVED_IMAGE_QC"
        )
        self.assertEqual(len(self.study["blind_section_map"]), 8)
        expected = set(he_pipeline.expected_sections(self.study))
        observed = {row["section_id"] for row in self.study["blind_section_map"]}
        self.assertEqual(observed, expected)

    def test_locked_profile_is_reviewer_approved_but_not_pathology_authorized(self):
        self.assertEqual(self.profile["status"], "REVIEWED_LOCKED")
        self.assertEqual(self.profile["review"]["decision"], "APPROVED_IMAGE_QC")
        restrictions = " ".join(self.profile["restrictions"])
        self.assertIn("Not authorized for lesion", restrictions)

    def test_whole_section_review_replaces_tile_as_primary_unit(self):
        self.assertEqual(self.rubric["review_unit"], "blinded_whole_section")
        fields, rows = he_pipeline.section_review_rows(self.study, self.rubric)
        self.assertEqual(len(rows), 8)
        self.assertEqual({row["blind_section_id"] for row in rows},
                         {f"HE-{index:03d}" for index in range(1, 9)})
        self.assertIn("whole_section_inflammation_extent_0_4_uncertain", fields)
        self.assertNotIn("mouse_id", fields)
        self.assertNotIn("genotype", fields)
        self.assertNotIn("condition", fields)

    def test_stage_contract_blocks_unavailable_claims(self):
        rows = he_pipeline.stage_rows(
            {"locked_profile_id": self.profile["profile_id"]},
            {"candidate_count": 96},
        )
        by_stage = {row["stage"]: row for row in rows}
        self.assertEqual(list(by_stage), [f"H{index}" for index in range(10)])
        self.assertEqual(by_stage["H2"]["status"], "APPROVED_R1")
        self.assertEqual(by_stage["H5"]["status"], "BLOCKED")
        self.assertEqual(by_stage["H8"]["status"], "BLOCKED")

    def test_rubric_preserves_interpretation_boundaries(self):
        hard_rules = " ".join(self.rubric["hard_rules"])
        self.assertIn("cannot identify immune lineage", hard_rules)
        self.assertIn("cannot identify a KRT5-positive pod", hard_rules)
        self.assertIn("supporting tiles are evidence locators", hard_rules)


if __name__ == "__main__":
    unittest.main()
