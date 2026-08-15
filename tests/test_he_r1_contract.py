import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HeR1ContractTests(unittest.TestCase):
    def test_candidate_profile_is_review_gated(self):
        profile = json.loads(
            (ROOT / "config" / "brightfield" / "he_stain_profiles" /
             "g_surf_he_20260812_candidate.json").read_text(encoding="utf-8")
        )
        self.assertEqual(profile["status"], "CANDIDATE_REVIEW_REQUIRED")
        self.assertTrue(profile["review"]["required"])
        self.assertEqual(profile["review"]["decision"], "PENDING")
        self.assertEqual(len(profile["review"]["required_sections"]), 8)
        self.assertEqual(profile["image_type"], "BRIGHTFIELD_H_E")

    def test_r1_runner_cannot_emit_pathology_or_mouse_endpoints(self):
        text = (ROOT / "brightfield" / "qupath_he_r1_qc_candidate.groovy").read_text(
            encoding="utf-8"
        )
        self.assertIn("R1_CANDIDATE_NOT_REPORTABLE", text)
        self.assertIn('release_level:"R0"', text)
        self.assertIn("No lesion, cell, ordinal, mouse-level, or group endpoint", text)
        self.assertNotIn("he_mouse_summary.csv", text)
        self.assertNotIn("immune_lineage", text)


if __name__ == "__main__":
    unittest.main()
