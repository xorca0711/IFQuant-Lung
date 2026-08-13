from __future__ import annotations

import csv
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage, signal


BASE = Path(r"D:\IFQ_Runs\confocal_region_map_260808")
WSI = BASE / "wsi_overviews"
OUT = BASE / "wsi_annotated"
OUT.mkdir(parents=True, exist_ok=True)

SAMPLES = {
    "M2": "IFNg_KO_hom_26.03.25_m2_pr8_infection",
    "M6": "IFNg_KO_hom_26.03.25_m6_pr8_no_infection",
    "M4-1": "IFNg_KO_het_26.03.25_m4-1_pr8_infection",
    "M4-2": "IFNg_KO_het_26.03.25_m4-2_pr8_no_infection",
}


def mask_from_dapi(path: Path, max_dim: int = 1100) -> tuple[np.ndarray, float]:
    im = Image.open(path).convert("L")
    scale = max_dim / max(im.size)
    small = im.resize((round(im.width * scale), round(im.height * scale)), Image.Resampling.LANCZOS)
    a = np.asarray(small, dtype=np.float32)
    nz = a[a > 0]
    threshold = np.percentile(nz, 55) if nz.size else 1
    m = a > threshold
    m = ndimage.binary_closing(m, iterations=2)
    m = ndimage.binary_dilation(m, iterations=1)
    return m.astype(np.float32), scale


def best_translation(source: np.ndarray, target: np.ndarray) -> tuple[float, int, int, float]:
    best = None
    for angle in (0, 180):
        s0 = ndimage.rotate(source, angle, reshape=True, order=0) if angle else source
        for scale in np.linspace(0.55, 1.45, 37):
            s = ndimage.zoom(s0, scale, order=0)
            if s.shape[0] >= target.shape[0] or s.shape[1] >= target.shape[1]:
                continue
            corr = signal.fftconvolve(target, s[::-1, ::-1], mode="valid")
            norm = np.sqrt(max(1.0, s.sum()))
            iy, ix = np.unravel_index(np.argmax(corr), corr.shape)
            score = float(corr[iy, ix] / norm)
            if best is None or score > best[0]:
                best = (score, angle, scale, int(ix), int(iy), s.shape)
    if best is None:
        raise RuntimeError("No valid registration candidate")
    return best[2], best[3], best[4], best[1], best[0]


rows = list(csv.DictReader((BASE / "metadata" / "field_annotations.csv").open(encoding="utf-8-sig")))
manifest = []

for sample, stem in SAMPLES.items():
    wsi_dapi = WSI / f"{stem}__WSI_DAPI.png"
    wsi_composite = WSI / f"{stem}__WSI_composite.jpg"
    target, target_small_scale = mask_from_dapi(wsi_dapi)
    for side, panel_key, old_slug in (
        ("LEFT", "KRT5 / Ager / T1alpha", "krt5_ager_t1a"),
        ("RIGHT", "ProSPC / Ager / KRT8", "prospc_ager_krt8"),
    ):
        old_dapi = BASE / "overviews" / f"{sample.replace('-', '_')}_{old_slug}_dapi.jpg"
        source, source_small_scale = mask_from_dapi(old_dapi)
        scale, tx, ty, angle, score = best_translation(source, target)

        # Coordinates in field_annotations.csv are in the old overview's native pixels.
        # Convert to each small-mask coordinate frame, then apply the fitted transform,
        # then return to the high-resolution WSI export frame.
        image = Image.open(wsi_composite).convert("RGB")
        draw = ImageDraw.Draw(image)
        font = ImageFont.truetype(r"C:\Windows\Fonts\arialbd.ttf", 42)
        selected = [r for r in rows if r["Sample"] == sample and r["Panel"] == panel_key]
        old_w, old_h = Image.open(old_dapi).size
        for r in selected:
            x = float(r["CenterX"]) * source_small_scale
            y = float(r["CenterY"]) * source_small_scale
            if angle == 180:
                x = source.shape[1] - 1 - x
                y = source.shape[0] - 1 - y
            x = (x * scale + tx) / target_small_scale
            y = (y * scale + ty) / target_small_scale
            # Approximate 20x field footprint: 706 um / WSI export pixel size.
            side_px = 706.0 / (0.3449973537 / target_small_scale)
            half = side_px / 2
            draw.rectangle((x-half, y-half, x+half, y+half), outline=(255,232,0), width=10)
            label = str(r["Order"])
            bbox = draw.textbbox((0,0), label, font=font, stroke_width=2)
            tw, th = bbox[2]-bbox[0], bbox[3]-bbox[1]
            lx, ly = x-half, max(0, y-half-th-14)
            draw.rounded_rectangle((lx,ly,lx+tw+24,ly+th+12), radius=8, fill=(255,232,0))
            draw.text((lx+12,ly+2), label, font=font, fill=(8,11,16), stroke_width=1)

        out = OUT / f"{sample.replace('-', '_')}_{side}_WSI_annotated.jpg"
        image.save(out, quality=96, subsampling=0)
        manifest.append({
            "sample": sample, "side": side, "panel": panel_key, "output": str(out),
            "rotation": angle, "scale_small": scale, "tx_small": tx, "ty_small": ty,
            "registration_score": score,
        })

with (OUT / "registration_manifest.csv").open("w", newline="", encoding="utf-8") as f:
    writer = csv.DictWriter(f, fieldnames=manifest[0].keys())
    writer.writeheader(); writer.writerows(manifest)
print(f"Wrote {len(manifest)} annotated WSI maps to {OUT}")

