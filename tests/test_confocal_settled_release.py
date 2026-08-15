import csv
import json
import tempfile
import unittest
from pathlib import Path

from aggregate_to_mouse import aggregate_mice, group_stats
from scripts.build_confocal_settled_release import build_release


def write_csv(path, fieldnames, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


class AggregationSemanticsTests(unittest.TestCase):
    def test_confocal_sampling_units_are_fields(self):
        header = [
            "image", "region", "section_id", "mouse_id", "genotype",
            "condition", "panel", "region_area_um2", "n_nuclei",
        ]
        rows = [
            {
                "image": f"field_{index}", "region": "tissue",
                "section_id": f"F{index}", "mouse_id": "M1",
                "genotype": "G", "condition": "C", "panel": "LEFT",
                "region_area_um2": "100", "n_nuclei": "10",
            }
            for index in (1, 2)
        ]
        result = aggregate_mice(header, rows, sampling_unit="field")
        self.assertEqual(result[0]["n_fields"], 2)
        self.assertNotIn("n_sections", result[0])
        self.assertEqual(result[0]["sampling_unit"], "field")

    def test_one_mouse_variability_is_not_estimated(self):
        rows = [{
            "mouse_id": "M1", "genotype": "G", "condition": "C",
            "panel": "LEFT", "n_regions": 2, "n_fields": 2,
            "sampling_unit": "field", "metric_value": 4.5,
        }]
        result = group_stats(rows)
        metric = next(row for row in result if row["metric"] == "metric_value")
        self.assertEqual(metric["n_mice"], 1)
        self.assertIsNone(metric["sd"])
        self.assertIsNone(metric["sem"])
        self.assertEqual(metric["reportability"], "DESCRIPTIVE_ONLY")


class SettledReleaseTests(unittest.TestCase):
    def test_release_reconciles_success_missing_pair_and_stale_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            run = root / "run"
            analysis = run / "analysis"
            stats = run / "stats"
            visuals = root / "visuals"
            source.mkdir()
            analysis.mkdir(parents=True)
            stats.mkdir()
            visuals.mkdir()
            deck = root / "deck.pptx"
            deck.write_bytes(b"reviewed-deck")
            left_source = source / "left.oir"
            right_source = source / "right.oir"
            left_source.write_bytes(b"left")
            right_source.write_bytes(b"right")

            write_csv(
                root / "annotations.csv",
                [
                    "Sample", "Genotype", "Condition", "Panel", "Order",
                    "CenterX", "CenterY", "MatchScore", "Evidence",
                    "SourceFile", "AnnotatedOverview",
                ],
                [
                    {
                        "Sample": "M1", "Genotype": "G", "Condition": "C",
                        "Panel": "KRT5 / Ager / T1alpha", "Order": "1",
                        "CenterX": "10", "CenterY": "20", "MatchScore": "0.8",
                        "Evidence": "direct", "SourceFile": str(left_source),
                        "AnnotatedOverview": "left-map.jpg",
                    },
                    {
                        "Sample": "M1", "Genotype": "G", "Condition": "C",
                        "Panel": "ProSPC / Ager / KRT8", "Order": "1",
                        "CenterX": "12", "CenterY": "24", "MatchScore": "0.7",
                        "Evidence": "direct", "SourceFile": str(right_source),
                        "AnnotatedOverview": "right-map.jpg",
                    },
                ],
            )
            write_csv(
                run / "samplesheet.csv",
                [
                    "relative_path", "mouse_id", "section_id",
                    "genotype", "condition", "panel",
                ],
                [
                    {
                        "relative_path": "left.oir", "mouse_id": "M1",
                        "section_id": "L1", "genotype": "G",
                        "condition": "C", "panel": "LEFT",
                    },
                    {
                        "relative_path": "right.oir", "mouse_id": "M1",
                        "section_id": "R1", "genotype": "G",
                        "condition": "C", "panel": "RIGHT",
                    },
                ],
            )
            run_header = [
                "image", "output_key", "panel", "region", "mouse_id",
                "section_id", "genotype", "condition", "compartment",
                "region_area_um2", "n_nuclei",
            ]
            write_csv(
                analysis / "run_summary.csv",
                run_header,
                [{
                    "image": "left", "output_key": "M1_C_LEFT_L1",
                    "panel": "LEFT", "region": "tissue", "mouse_id": "M1",
                    "section_id": "L1", "genotype": "G", "condition": "C",
                    "compartment": "unassigned", "region_area_um2": "100",
                    "n_nuclei": "10",
                }],
            )
            engine_manifest = {
                "status": "partial_failure", "matched_input_count": 2,
                "analytical_input_count": 2, "success_count": 1,
                "skipped_count": 0, "failure_count": 1,
                "images": [
                    {
                        "relative_path": "left.oir", "output_key": "M1_C_LEFT_L1",
                        "status": "success",
                    },
                    {
                        "relative_path": "right.oir", "output_key": None,
                        "status": "failed", "error": "DAPI tissue detection",
                    },
                ],
            }
            (analysis / "run_manifest.json").write_text(
                json.dumps(engine_manifest), encoding="utf-8"
            )
            write_csv(
                stats / "mouse_level_summary.csv",
                [
                    "mouse_id", "genotype", "condition", "panel",
                    "n_regions", "n_sections", "total_tissue_area_um2",
                ],
                [{
                    "mouse_id": "M1", "genotype": "G", "condition": "C",
                    "panel": "LEFT", "n_regions": "1", "n_sections": "1",
                    "total_tissue_area_um2": "100",
                }],
            )
            write_csv(
                root / "legacy.csv",
                ["Sample", "Side", "Order", "Path"],
                [
                    {
                        "Sample": "M1", "Side": "LEFT", "Order": "1",
                        "Path": str(root / "old-left.jpg"),
                    },
                    {
                        "Sample": "M1", "Side": "RIGHT", "Order": "1",
                        "Path": str(root / "old-right.jpg"),
                    },
                ],
            )
            left_visual = visuals / "M1_C_LEFT_L1__VISUAL_MERGE_PANEL.jpg"
            right_visual = visuals / "M1_C_RIGHT_R1__VISUAL_MERGE_PANEL.jpg"
            left_visual.write_bytes(b"left-jpeg")
            right_visual.write_bytes(b"right-jpeg")

            config = {
                "schema_version": "1.0.0", "study_id": "synthetic",
                "source_root": str(source), "source_run": str(run),
                "field_annotations": str(root / "annotations.csv"),
                "legacy_retouched_order": str(root / "legacy.csv"),
                "reviewed_visual_root": str(visuals),
                "authoritative_deck": str(deck),
                "expected_mouse_count": 1, "expected_panels_per_mouse": 2,
                "expected_fields_per_panel": 1, "expected_fields": 2,
                "panels": {
                    "LEFT": "KRT5 / AGER / T1A",
                    "RIGHT": "ProSPC / AGER / KRT8",
                },
                "samples": [{
                    "mouse_id": "M1", "genotype": "G", "condition": "C",
                    "field_roles": {
                        "LEFT": ["POD"], "RIGHT": ["POD_ASSOCIATED"],
                    },
                }],
                "known_field_exceptions": [{
                    "mouse_id": "M1", "panel": "RIGHT", "field_order": 1,
                    "qc_status": "MISSING_QUANTIFICATION",
                    "reason": "synthetic failure",
                }],
                "interpretation_constraints": ["synthetic"],
            }
            config_path = root / "study.json"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            output = root / "release"
            manifest = build_release(config_path, output)

            reconciliation = manifest["canonical_reconciliation"]
            self.assertEqual(reconciliation["expected_fields"], 2)
            self.assertEqual(reconciliation["quantified_fields"], 1)
            self.assertEqual(reconciliation["missing_fields"], 1)
            self.assertEqual(
                reconciliation["stale_legacy_visual_paths_replaced"], 2
            )
            _, canonical = self._read(output / "canonical_field_manifest.csv")
            self.assertEqual(len(canonical), 2)
            self.assertEqual(
                {row["quantification_status"] for row in canonical},
                {"SUCCESS", "FAILED"},
            )
            _, pairs = self._read(output / "left_right_pair_summary.csv")
            self.assertEqual(pairs[0]["pair_status"], "RIGHT_QUANTIFICATION_MISSING")
            self.assertEqual(
                pairs[0]["pairing_scope"],
                "FIELD_ORDER_ONLY_NOT_PIXEL_REGISTERED",
            )

    @staticmethod
    def _read(path):
        with path.open(newline="", encoding="utf-8-sig") as handle:
            reader = csv.DictReader(handle)
            return reader.fieldnames, list(reader)


class StaticCanonicalGateTests(unittest.TestCase):
    def test_engine_and_launcher_share_canonical_manifest_environment(self):
        engine = Path("IF_Quant_Pipeline.groovy").read_text(encoding="utf-8")
        launcher = Path("launcher/IFQuantLauncher.Routing.cs").read_text(
            encoding="utf-8"
        )
        self.assertIn('envOr("IFQ_CANONICAL_MANIFEST_PATH"', engine)
        self.assertIn("not_in_canonical_manifest", engine)
        self.assertIn('"IFQ_CANONICAL_MANIFEST_PATH"', launcher)


if __name__ == "__main__":
    unittest.main()
