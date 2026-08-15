"""Render opaque, morphology-preserving H&E R1 QC overlays.

This is the canonical overlay renderer for R1. It composites labels over the
raw preview; it never substitutes translucent ARGB pixels for source pixels.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


LABELS = (
    ("red_pen", (255, 136, 0), 180),
    ("dark", (255, 0, 255), 145),
    ("chromatic", (255, 255, 0), 145),
    ("fold", (136, 0, 255), 115),
)
ARTIFACT_RGB = {
    "red_pen": (255, 136, 0),
    "dark": (255, 0, 255),
    "chromatic": (255, 255, 0),
    "fold": (136, 0, 255),
}
TISSUE_RGB = (0, 220, 120)
TISSUE_ALPHA = 30


def blend(base: np.ndarray, mask: np.ndarray, color: tuple[int, int, int], alpha: int) -> None:
    if not np.any(mask):
        return
    source = base[mask].astype(np.uint16)
    target = np.asarray(color, dtype=np.uint16)
    base[mask] = ((source * (255 - alpha) + target * alpha + 127) // 255).astype(np.uint8)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", required=True, type=Path)
    args = parser.parse_args()
    root = args.output_root.resolve()
    analysis = root / "analysis"
    table = analysis / "tables" / "he_section_qc.csv"
    manifest_path = analysis / "he_run_manifest.json"
    if not table.is_file() or not manifest_path.is_file():
        raise SystemExit(f"Not an H&E R1 run: {root}")

    rows = list(csv.DictReader(table.open("r", encoding="utf-8-sig", newline="")))
    if len(rows) != 8:
        raise SystemExit(f"Expected 8 R1 sections, found {len(rows)}")
    rendered: list[dict[str, object]] = []
    for row in rows:
        raw_path = analysis / "previews" / row["raw_preview"]
        tissue_path = analysis / "masks" / row["tissue_mask"]
        artifact_path = analysis / "masks" / row["artifact_mask"]
        output_path = analysis / "qc_overlays" / row["qc_overlay"]
        raw = np.asarray(Image.open(raw_path).convert("RGB"), dtype=np.uint8).copy()
        tissue = np.asarray(Image.open(tissue_path).convert("L"), dtype=np.uint8) > 127
        artifact = np.asarray(Image.open(artifact_path).convert("RGB"), dtype=np.uint8)
        if raw.shape[:2] != tissue.shape or raw.shape != artifact.shape:
            raise SystemExit(f"Shape mismatch for {row['section_id']}")

        blend(raw, tissue, TISSUE_RGB, TISSUE_ALPHA)
        counts: dict[str, int] = {}
        for name, color, alpha in LABELS:
            color_array = np.asarray(ARTIFACT_RGB[name], dtype=np.uint8)
            mask = np.all(artifact == color_array, axis=2)
            counts[name] = int(mask.sum())
            blend(raw, mask, color, alpha)

        image = Image.fromarray(raw, mode="RGB")
        draw = ImageDraw.Draw(image)
        banner = max(42, round(image.height * 0.045))
        draw.rectangle((0, 0, image.width, banner), fill=(0, 0, 0))
        font = ImageFont.load_default(size=max(12, banner // 3))
        label = (
            f"{row['section_id']} | GREEN tissue | ORANGE excluded pen | "
            "MAGENTA dark | YELLOW chromatic | PURPLE fold"
        )
        draw.text((12, max(4, banner // 4)), label, fill=(255, 255, 255), font=font)
        image.save(output_path, format="PNG", optimize=True)
        rendered.append(
            {
                "section_id": row["section_id"],
                "path": str(output_path.relative_to(analysis)).replace("\\", "/"),
                "mode": "RGB_OPAQUE",
                "sha256": sha256(output_path),
                "artifact_pixels": counts,
            }
        )

    renderer_path = Path(__file__).resolve()
    render_manifest = {
        "schema_version": "1.0.0",
        "renderer": "precomposited_opaque_v1",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "renderer_path": str(renderer_path),
        "renderer_sha256": sha256(renderer_path),
        "sections": rendered,
    }
    render_manifest_path = analysis / "overlay_render_manifest.json"
    render_manifest_path.write_text(json.dumps(render_manifest, indent=2), encoding="utf-8")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["overlay_rendering"] = {
        "method": "precomposited_opaque_v1",
        "manifest": "overlay_render_manifest.json",
        "morphology_visible": True,
    }
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Rendered {len(rendered)} opaque R1 overlays: {analysis / 'qc_overlays'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
