import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HeSpecificationTests(unittest.TestCase):
    def setUp(self):
        self.hierarchy = json.loads(
            (ROOT / "config" / "brightfield" / "he_decision_hierarchy.json").read_text(
                encoding="utf-8"
            )
        )
        self.endpoints = json.loads(
            (ROOT / "config" / "brightfield" / "he_endpoints.json").read_text(
                encoding="utf-8"
            )
        )
        self.study = json.loads(
            (ROOT / "config" / "studies" / "g_surf_he_20260812.json").read_text(
                encoding="utf-8"
            )
        )

    def test_hierarchy_is_ordered_and_fail_closed(self):
        self.assertEqual(
            [stage["id"] for stage in self.hierarchy["stages"]],
            [f"H{i}" for i in range(10)],
        )
        self.assertEqual(self.hierarchy["biological_unit"], "mouse")
        self.assertNotEqual(self.hierarchy["status"], "VALIDATED")
        for stage in self.hierarchy["stages"]:
            self.assertTrue(stage["required_evidence"])
            self.assertTrue(stage["failure_action"])

    def test_current_study_has_four_mice_and_eight_technical_sections(self):
        samples = self.study["samples"]
        self.assertEqual(self.study["expected_mouse_count"], 4)
        self.assertEqual(self.study["expected_analytical_sections"], 8)
        self.assertEqual(len(samples), 4)
        self.assertEqual(len({sample["biological_unit_id"] for sample in samples}), 4)
        self.assertEqual(sum(len(sample["section_ids"]) for sample in samples), 8)
        self.assertTrue(all(len(sample["section_ids"]) == 2 for sample in samples))
        self.assertEqual(
            self.study["analytical_series"]["allow_names"],
            ["20x_BF_01", "20x_BF_02"],
        )

    def test_endpoint_aggregation_pools_raw_components(self):
        aggregation = self.endpoints["aggregation"]
        self.assertEqual(aggregation["biological_unit"], "mouse")
        self.assertEqual(
            aggregation["fraction_rule"],
            "sum_numerators_divided_by_sum_denominators",
        )
        quantitative = self.endpoints["endpoint_tiers"]["tier_1_quantitative"]
        self.assertTrue(all(item.get("numerator") for item in quantitative))
        self.assertTrue(all(item.get("denominator") for item in quantitative))

    def test_lineage_and_invalid_stereology_are_deferred(self):
        deferred = {
            item["id"]
            for item in self.endpoints["endpoint_tiers"]["tier_3_deferred"]
        }
        self.assertIn("immune_lineage_from_he", deferred)
        self.assertIn("alveolar_number_or_volume", deferred)
        density = next(
            item
            for item in self.endpoints["endpoint_tiers"]["tier_1_quantitative"]
            if item["id"] == "hematoxylin_nuclear_density"
        )
        self.assertIn("not immune lineage", density["interpretation"].lower())


if __name__ == "__main__":
    unittest.main()
