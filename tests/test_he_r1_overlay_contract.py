import ast
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HeR1OverlayContractTests(unittest.TestCase):
    def test_renderer_is_valid_python_and_forces_opaque_output(self):
        path = ROOT / "scripts" / "render_he_r1_qc_overlays.py"
        source = path.read_text(encoding="utf-8")
        ast.parse(source)
        self.assertIn('mode="RGB"', source)
        self.assertIn("precomposited_opaque_v1", source)
        self.assertIn("morphology_visible", source)

    def test_canonical_wrapper_preserves_airspaces(self):
        source = (ROOT / "scripts" / "Invoke-HeR1Qc.ps1").read_text(encoding="utf-8")
        self.assertIn("MaxHolePixels = 0", source)
        self.assertIn("render_he_r1_qc_overlays.py", source)
        self.assertIn("Test-HeR1RenderedOutput.ps1", source)


if __name__ == "__main__":
    unittest.main()
