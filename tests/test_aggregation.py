import csv
import tempfile
import unittest
from pathlib import Path

from aggregate_to_mouse import aggregate_mice, classify_columns, merge_endpoint_rows


class PartitionQcAggregationTests(unittest.TestCase):
    def setUp(self):
        self.header = [
            "image", "region", "section_id", "mouse_id", "genotype",
            "condition", "panel", "region_area_um2", "n_nuclei",
            "KRT5_pod_area_um2", "KRT5_n_pods",
            "damaged_area_um2", "intact_area_um2",
            "damaged_fraction_of_parenchyma",
            "KRT5_pod_area_um2_in_intact",
            "KRT5_pod_area_frac_of_intact",
        ]
        identity = {
            "region": "damaged_parenchyma", "mouse_id": "M1",
            "genotype": "IFNg_KO_hom", "condition": "PR8", "panel": "LEFT",
        }
        self.rows = [
            {
                **identity, "image": "slide_a", "section_id": "slide_a",
                "region_area_um2": "100", "n_nuclei": "10",
                "KRT5_pod_area_um2": "20", "KRT5_n_pods": "2",
                "damaged_area_um2": "100", "intact_area_um2": "200",
                "damaged_fraction_of_parenchyma": str(1 / 3),
                "KRT5_pod_area_um2_in_intact": "5",
                "KRT5_pod_area_frac_of_intact": "0.025",
            },
            {
                **identity, "image": "slide_b", "section_id": "slide_b",
                "region_area_um2": "300", "n_nuclei": "30",
                "KRT5_pod_area_um2": "30", "KRT5_n_pods": "3",
                "damaged_area_um2": "300", "intact_area_um2": "100",
                "damaged_fraction_of_parenchyma": "0.75",
                "KRT5_pod_area_um2_in_intact": "7",
                "KRT5_pod_area_frac_of_intact": "0.07",
            },
        ]

    def test_partition_qc_columns_are_classified_as_additive(self):
        cats = classify_columns(self.header)
        self.assertEqual(
            set(cats["partition_area"]), {"damaged_area_um2", "intact_area_um2"}
        )
        self.assertEqual(cats["intact_pod_area"], ["KRT5_pod_area_um2_in_intact"])

    def test_mouse_level_partition_qc_is_pooled_not_averaged(self):
        result = aggregate_mice(self.header, self.rows)
        self.assertEqual(len(result), 1)
        mouse = result[0]
        self.assertEqual(mouse["damaged_area_um2"], 400.0)
        self.assertEqual(mouse["intact_area_um2"], 300.0)
        self.assertAlmostEqual(mouse["damaged_fraction_of_parenchyma"], 4 / 7)
        self.assertEqual(mouse["KRT5_pod_area_um2_in_intact"], 12.0)
        self.assertAlmostEqual(mouse["KRT5_pod_area_frac_of_intact"], 0.04)
        self.assertAlmostEqual(mouse["KRT5_pod_area_frac"], 0.125)

    def test_relational_endpoint_fraction_uses_pooled_denominator(self):
        header = self.header + [
            "KRT5dysplastic_pod_area_um2",
            "KRT5dysplastic_denominator_area_um2",
            "KRT5dysplastic_fraction",
        ]
        rows = [dict(row) for row in self.rows]
        rows[0].update({
            "KRT5dysplastic_pod_area_um2": "2",
            "KRT5dysplastic_denominator_area_um2": "6",
            "KRT5dysplastic_fraction": str(1 / 3),
        })
        rows[1].update({
            "KRT5dysplastic_pod_area_um2": "8",
            "KRT5dysplastic_denominator_area_um2": "10",
            "KRT5dysplastic_fraction": "0.8",
        })

        mouse = aggregate_mice(
            header,
            rows,
            endpoint_relation={
                "area_column": "KRT5dysplastic_pod_area_um2",
                "bare_area_column": "KRT5_pod_area_um2",
                "numerator_fraction_of_bare_column": "qc_krt5_pdpn_positive_fraction",
            },
        )[0]
        self.assertEqual(mouse["KRT5dysplastic_denominator_area_um2"], 16.0)
        self.assertAlmostEqual(mouse["KRT5dysplastic_fraction"], 10.0 / 16.0)
        self.assertAlmostEqual(mouse["qc_krt5_pdpn_positive_fraction"], 10.0 / 50.0)

    def test_endpoint_join_keeps_only_exact_evaluated_rows(self):
        header = self.header + ["output_key"]
        rows = [dict(row) for row in self.rows]
        rows[0]["output_key"] = "left_key"
        rows[1]["output_key"] = "right_key"
        rows[1]["panel"] = "RIGHT"

        with tempfile.TemporaryDirectory() as tmp:
            endpoint_path = Path(tmp) / "endpoint.csv"
            with endpoint_path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "output_key", "region", "KRT5dysplastic_pod_area_um2",
                        "KRT5dysplastic_denominator_area_um2", "KRT5dysplastic_fraction",
                    ],
                )
                writer.writeheader()
                writer.writerow({
                    "output_key": "left_key",
                    "region": "damaged_parenchyma",
                    "KRT5dysplastic_pod_area_um2": "2",
                    "KRT5dysplastic_denominator_area_um2": "6",
                    "KRT5dysplastic_fraction": str(1 / 3),
                })
            merged_header, merged_rows = merge_endpoint_rows(
                header, rows, str(endpoint_path)
            )

        self.assertIn("KRT5dysplastic_denominator_area_um2", merged_header)
        self.assertEqual(len(merged_rows), 1)
        self.assertEqual(merged_rows[0]["output_key"], "left_key")
        self.assertEqual(merged_rows[0]["panel"], "LEFT")


if __name__ == "__main__":
    unittest.main()
