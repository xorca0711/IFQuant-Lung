import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class EndpointSpecificationTests(unittest.TestCase):
    def test_tissue_exporter_preserves_black_background_polarity(self):
        exporter = (ROOT / "endpoints" / "export_tissue_region_masks.groovy").read_text(
            encoding="utf-8"
        )
        fixed_call = '"iterations=2 count=1 black do=Close"'
        buggy_call = '"iterations=2 count=1 do=Close"'
        self.assertIn(fixed_call, exporter)
        self.assertNotIn(buggy_call, exporter)

    def test_corrected_endpoint_declares_required_boolean_relation(self):
        spec = json.loads(
            (ROOT / "config" / "endpoints" / "dysplastic_over_damaged.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(spec["numerator"]["op"], "AND")
        self.assertEqual(
            [(t["mask"], t["negate"]) for t in spec["numerator"]["terms"]],
            [("KRT5_pod_mask", False), ("T1A_membrane_positive_mask", False)],
        )
        self.assertEqual(spec["denominator"]["op"], "OR")
        self.assertEqual(
            [(t["mask"], t["negate"]) for t in spec["denominator"]["terms"]],
            [("T1A_membrane_positive_mask", True), ("KRT5_pod_mask", False)],
        )
        self.assertIn("denominator_area_column", spec["output"])
        self.assertIn("fraction_column", spec["output"])
        self.assertEqual(spec["output"]["bare_area_column"], "KRT5bare_pod_area_um2")
        self.assertEqual(
            spec["output"]["numerator_fraction_of_bare_column"],
            "qc_krt5_pdpn_positive_fraction",
        )

    def test_superseded_endpoint_is_machine_readably_retracted(self):
        spec = json.loads(
            (ROOT / "config" / "endpoints" / "ectopic_pod_over_damaged.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertIn("RETRACTED", spec)
        self.assertEqual(spec["superseded_by"], "dysplastic_over_damaged")


if __name__ == "__main__":
    unittest.main()
