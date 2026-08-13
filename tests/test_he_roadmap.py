import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HeRoadmapTests(unittest.TestCase):
    def setUp(self):
        self.roadmap = json.loads(
            (ROOT / "config" / "brightfield" / "he_feature_roadmap.json").read_text(
                encoding="utf-8"
            )
        )

    def test_unavailable_features_are_structured_and_fail_closed(self):
        features = self.roadmap["features"]
        self.assertEqual(
            [item["id"] for item in features],
            [f"HE-F{i:02d}" for i in range(1, 15)],
        )
        self.assertFalse(self.roadmap["launcher_route_enabled"])
        self.assertEqual(self.roadmap["biological_unit"], "mouse")
        for item in features:
            self.assertIn(item["status"], {"PARTIAL", "PROTOTYPE_ONLY", "UNAVAILABLE"})
            self.assertTrue(item["stages"])
            self.assertTrue(item["implementation"])
            self.assertTrue(item["outputs"])
            self.assertTrue(item["acceptance"])
            self.assertTrue(item["remaining_blocker"])

    def test_launcher_levels_require_features_and_do_not_overclaim(self):
        levels = self.roadmap["release_levels"]
        self.assertEqual([item["id"] for item in levels], [f"R{i}" for i in range(5)])
        self.assertEqual(levels[0]["status"], "AVAILABLE_REVIEW_REQUIRED")
        self.assertTrue(all(item["status"] == "UNAVAILABLE" for item in levels[1:]))
        known = {item["id"] for item in self.roadmap["features"]}
        for gate in self.roadmap["launcher_enablement"].values():
            self.assertTrue(set(gate["required_features"]).issubset(known))
        self.assertTrue(
            any("immune lineage" in item.lower() for item in self.roadmap["non_goals"])
        )


if __name__ == "__main__":
    unittest.main()
