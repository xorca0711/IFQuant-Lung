from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_visual_merge_scale_bar_is_calibrated_and_internal():
    pipeline = (ROOT / "IF_Quant_Pipeline.groovy").read_text(encoding="utf-8")

    assert 'envDouble("IFQ_DISPLAY_SCALE_BAR_UM", 100.0d)' in pipeline
    assert 'envInt("IFQ_DISPLAY_SCALE_BAR_THICKNESS_PX", 6)' in pipeline
    assert "cfg.displayScaleBarUm as double" in pipeline
    assert "cfg.displayScaleBarThicknessPx as int" in pipeline
    assert "composite.setCalibration(first.getCalibration())" in pipeline
    assert "labeled.setCalibration(source.getCalibration())" in pipeline
    assert "Cannot draw visual-merge scale bar without positive micrometre calibration" in pipeline
    assert "cp.setRoi(barX, barY, barWidthPx, thickness)" in pipeline
    assert r'" \u00b5m"' in pipeline


def test_launcher_owns_modern_scale_bar_defaults_but_not_legacy_route():
    launcher = (ROOT / "launcher" / "IFQuantLauncher.cs").read_text(encoding="utf-8")
    routing = (ROOT / "launcher" / "IFQuantLauncher.Routing.cs").read_text(encoding="utf-8")

    assert 'AssemblyFileVersion("1.9.3.0")' in launcher
    assert '"IFQ_DISPLAY_SCALE_BAR_UM", "IFQ_DISPLAY_SCALE_BAR_THICKNESS_PX"' in launcher
    assert 'public const string Version = "1.9.3";' in routing
    assert 'env["IFQ_DISPLAY_SCALE_BAR_UM"] = "100";' in routing
    assert 'env["IFQ_DISPLAY_SCALE_BAR_THICKNESS_PX"] = "6";' in routing

    legacy = routing[routing.index("internal static class LegacyProfile") :]
    assert "IFQ_DISPLAY_SCALE_BAR_UM" not in legacy
    assert "IFQ_DISPLAY_SCALE_BAR_THICKNESS_PX" not in legacy
