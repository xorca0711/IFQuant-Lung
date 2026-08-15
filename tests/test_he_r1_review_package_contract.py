import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HeR1ReviewPackageContractTests(unittest.TestCase):
    def test_review_packager_is_valid_and_blinded(self):
        path = ROOT / "scripts" / "new_he_r1_review_package.py"
        source = path.read_text(encoding="utf-8")
        ast.parse(source)
        self.assertIn("SEND_TO_REVIEWER", source)
        self.assertIn("INTERNAL_DO_NOT_SEND", source)
        self.assertIn("SECTION_UNBLINDING_KEY__DO_NOT_SEND.csv", source)
        self.assertIn("R1_REVIEW_FORM.csv", source)
        self.assertIn("This review authorizes R1 image QC only", source)


if __name__ == "__main__":
    unittest.main()
