#!/usr/bin/env python3
"""Build an anatomy-blind, spatially balanced H4 H&E region-review package.

The primary sampling arm uses only the approved R1 tissue envelope and spatial
coordinates.  A separate, non-prevalence diversity arm uses generic stain and
microtexture measurements to broaden the review set without assigning anatomy.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


DEFAULT_R1 = Path(r"D:\IFQ_Runs\H&E_20260812\10_R1_IMAGE_QC_APPROVED_FINAL")
DEFAULT_OUTPUT = Path(r"D:\IFQ_Runs\H&E_20260812\13_H4_SPATIALLY_BALANCED_REGION_REVIEW")
PROTOCOL_VERSION = "H4-region-review-v1.0.0"
CORE_PER_SECTION = 8
DIVERSITY_PER_SECTION = 4
TILE_WIDTH_UM = 1120.0
EXPORT_DOWNSAMPLE = 4.0
OVERVIEW_HALF_WINDOW = 32
ELIGIBLE_GRID_STEP = 8
MIN_ENVELOPE_FRACTION = 0.70
MIN_CENTER_SEPARATION_OVERVIEW_PX = 42.0


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        return list(csv.DictReader(handle))


def write_csv(path: Path, fieldnames: list[str], rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def integral(array: np.ndarray) -> np.ndarray:
    return np.pad(array.astype(np.float64), ((1, 0), (1, 0))).cumsum(0).cumsum(1)


def rectangle_sum(ii: np.ndarray, x0: int, y0: int, x1: int, y1: int) -> float:
    return float(ii[y1, x1] - ii[y0, x1] - ii[y1, x0] + ii[y0, x0])


def stable_rng(blind_id: str) -> np.random.Generator:
    payload = f"{PROTOCOL_VERSION}|{blind_id}|geometry-only-primary".encode("utf-8")
    seed = int(hashlib.sha256(payload).hexdigest()[:16], 16)
    return np.random.default_rng(seed)


def farthest_spatial_indices(
    xy: np.ndarray, count: int, rng: np.random.Generator
) -> list[int]:
    if len(xy) < count:
        raise RuntimeError(f"Only {len(xy)} eligible centers for {count} requested tiles")
    scale = np.maximum(np.ptp(xy, axis=0), 1.0)
    normalized = (xy - np.min(xy, axis=0)) / scale
    chosen = [int(rng.integers(0, len(xy)))]
    min_distance = np.sum((normalized - normalized[chosen[0]]) ** 2, axis=1)
    for _ in range(1, count):
        next_index = int(np.argmax(min_distance))
        chosen.append(next_index)
        distance = np.sum((normalized - normalized[next_index]) ** 2, axis=1)
        min_distance = np.minimum(min_distance, distance)
        min_distance[chosen] = -1.0
    return chosen


def kmeans_diversity_indices(
    features: np.ndarray,
    xy: np.ndarray,
    count: int,
    rng: np.random.Generator,
) -> list[int]:
    if len(features) < count:
        raise RuntimeError("Insufficient candidates for diversity supplement")
    mean = features.mean(axis=0)
    std = features.std(axis=0)
    z = (features - mean) / np.where(std > 1e-8, std, 1.0)

    centers = [int(rng.integers(0, len(z)))]
    minimum = np.sum((z - z[centers[0]]) ** 2, axis=1)
    for _ in range(1, count):
        probability = np.maximum(minimum, 0.0)
        if probability.sum() == 0:
            candidate = int(np.argmax(minimum))
        else:
            candidate = int(rng.choice(len(z), p=probability / probability.sum()))
        centers.append(candidate)
        minimum = np.minimum(minimum, np.sum((z - z[candidate]) ** 2, axis=1))

    centroids = z[centers].copy()
    labels = np.zeros(len(z), dtype=np.int32)
    for _ in range(30):
        distances = ((z[:, None, :] - centroids[None, :, :]) ** 2).sum(axis=2)
        new_labels = distances.argmin(axis=1)
        new_centroids = centroids.copy()
        for cluster in range(count):
            members = z[new_labels == cluster]
            if len(members):
                new_centroids[cluster] = members.mean(axis=0)
        if np.array_equal(new_labels, labels) and np.allclose(new_centroids, centroids):
            break
        labels, centroids = new_labels, new_centroids

    selected: list[int] = []
    for cluster in range(count):
        members = np.where(labels == cluster)[0]
        if not len(members):
            continue
        ordering = members[np.argsort(np.sum((z[members] - centroids[cluster]) ** 2, axis=1))]
        pick = None
        for candidate in ordering:
            if all(
                np.linalg.norm(xy[candidate] - xy[previous])
                >= MIN_CENTER_SEPARATION_OVERVIEW_PX
                for previous in selected
            ):
                pick = int(candidate)
                break
        selected.append(int(ordering[0]) if pick is None else pick)

    if len(selected) < count:
        remaining = [index for index in range(len(z)) if index not in selected]
        while len(selected) < count and remaining:
            if not selected:
                selected.append(remaining.pop(0))
                continue
            distances = [
                min(np.linalg.norm(xy[index] - xy[picked]) for picked in selected)
                for index in remaining
            ]
            best_position = int(np.argmax(distances))
            selected.append(remaining.pop(best_position))
    return selected[:count]


def get_font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    candidates = [
        Path(r"C:\Windows\Fonts\arialbd.ttf") if bold else Path(r"C:\Windows\Fonts\arial.ttf"),
        Path(r"C:\Windows\Fonts\segoeuib.ttf") if bold else Path(r"C:\Windows\Fonts\segoeui.ttf"),
    ]
    for candidate in candidates:
        if candidate.is_file():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def make_overview(
    raw: Image.Image,
    blind_id: str,
    rows: list[dict[str, object]],
    output: Path,
) -> None:
    banner_height = 74
    canvas = Image.new("RGB", (raw.width, raw.height + banner_height), "white")
    canvas.paste(raw.convert("RGB"), (0, banner_height))
    draw = ImageDraw.Draw(canvas)
    draw.text((12, 8), f"{blind_id} - H4 ANATOMY-BLIND REGION SELECTION (DISPLAY ONLY)", fill="black", font=get_font(21, True))
    draw.text((12, 39), "BLUE = primary spatial arm | ORANGE = diversity supplement | boxes are fixed 1.12 mm tiles", fill="black", font=get_font(16))
    for row in rows:
        x0 = int(row["overview_x0_px"])
        y0 = int(row["overview_y0_px"]) + banner_height
        x1 = int(row["overview_x1_px"])
        y1 = int(row["overview_y1_px"]) + banner_height
        color = (0, 110, 255) if row["sampling_arm"] == "CORE_SPATIAL" else (255, 125, 0)
        draw.rectangle((x0, y0, x1, y1), outline=color, width=3)
        label = str(row["candidate_id"]).split("-")[-1]
        draw.rectangle((x0, y0, x0 + 38, y0 + 19), fill=color)
        draw.text((x0 + 2, y0 + 1), label, fill="white", font=get_font(13, True))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output)


def make_contact_sheet(paths: list[Path], output: Path, title: str, columns: int = 4) -> None:
    images = [Image.open(path).convert("RGB") for path in paths]
    if not images:
        return
    thumb_w, thumb_h = 420, 330
    title_h = 58
    rows = math.ceil(len(images) / columns)
    canvas = Image.new("RGB", (columns * thumb_w, title_h + rows * thumb_h), "white")
    draw = ImageDraw.Draw(canvas)
    draw.text((12, 13), title, fill="black", font=get_font(24, True))
    for index, (path, image) in enumerate(zip(paths, images)):
        image.thumbnail((thumb_w - 14, thumb_h - 36), Image.Resampling.LANCZOS)
        x = (index % columns) * thumb_w + (thumb_w - image.width) // 2
        y = title_h + (index // columns) * thumb_h + 26
        canvas.paste(image, (x, y))
        draw.text(((index % columns) * thumb_w + 8, title_h + (index // columns) * thumb_h + 4), path.stem, fill="black", font=get_font(14, True))
    output.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output, quality=92)
    for image in images:
        image.close()


def load_mapping(r1_root: Path) -> dict[str, dict[str, str]]:
    key_path = r1_root / "INTERNAL_DO_NOT_SEND" / "01_UNBLINDING_KEY" / "SECTION_UNBLINDING_KEY__DO_NOT_SEND.csv"
    qc_path = r1_root / "INTERNAL_DO_NOT_SEND" / "02_PROVENANCE" / "he_section_qc__unblinded.csv"
    key_rows = read_csv(key_path)
    qc_rows = {row["section_id"]: row for row in read_csv(qc_path)}
    mapping: dict[str, dict[str, str]] = {}
    for key in key_rows:
        row = dict(qc_rows[key["section_id"]])
        row.update(key)
        mapping[key["blind_section_id"]] = row
    return mapping


def select_section(
    blind_id: str,
    raw_path: Path,
    envelope_path: Path,
    material_path: Path,
    source: dict[str, str],
) -> list[dict[str, object]]:
    raw = np.asarray(Image.open(raw_path).convert("RGB"), dtype=np.float32)
    envelope = np.asarray(Image.open(envelope_path).convert("L")) > 0
    material = np.asarray(Image.open(material_path).convert("L")) > 0
    if raw.shape[:2] != envelope.shape or envelope.shape != material.shape:
        raise RuntimeError(f"Dimension mismatch for {blind_id}")
    height, width = envelope.shape
    half = OVERVIEW_HALF_WINDOW
    area = float((2 * half) ** 2)

    gray = raw.mean(axis=2) / 255.0
    maximum = raw.max(axis=2)
    minimum = raw.min(axis=2)
    saturation = np.divide(maximum - minimum, np.maximum(maximum, 1.0))
    darkness = 1.0 - gray
    envelope_ii = integral(envelope)
    material_ii = integral(material)
    darkness_ii = integral(darkness * envelope)
    saturation_ii = integral(saturation * envelope)
    gray_ii = integral(gray * envelope)
    gray2_ii = integral(gray * gray * envelope)

    candidates: list[dict[str, float]] = []
    for y in range(half, height - half, ELIGIBLE_GRID_STEP):
        for x in range(half, width - half, ELIGIBLE_GRID_STEP):
            if not envelope[y, x]:
                continue
            x0, y0, x1, y1 = x - half, y - half, x + half, y + half
            envelope_pixels = rectangle_sum(envelope_ii, x0, y0, x1, y1)
            envelope_fraction = envelope_pixels / area
            if envelope_fraction < MIN_ENVELOPE_FRACTION:
                continue
            denominator = max(envelope_pixels, 1.0)
            material_fraction = rectangle_sum(material_ii, x0, y0, x1, y1) / denominator
            mean_darkness = rectangle_sum(darkness_ii, x0, y0, x1, y1) / denominator
            mean_saturation = rectangle_sum(saturation_ii, x0, y0, x1, y1) / denominator
            mean_gray = rectangle_sum(gray_ii, x0, y0, x1, y1) / denominator
            mean_gray2 = rectangle_sum(gray2_ii, x0, y0, x1, y1) / denominator
            gray_std = math.sqrt(max(mean_gray2 - mean_gray * mean_gray, 0.0))
            candidates.append(
                {
                    "x": float(x),
                    "y": float(y),
                    "envelope_fraction": envelope_fraction,
                    "material_fraction": material_fraction,
                    "mean_darkness": mean_darkness,
                    "mean_saturation": mean_saturation,
                    "gray_std": gray_std,
                }
            )
    if len(candidates) < CORE_PER_SECTION + DIVERSITY_PER_SECTION:
        raise RuntimeError(f"Too few eligible candidates for {blind_id}: {len(candidates)}")

    xy = np.array([[candidate["x"], candidate["y"]] for candidate in candidates])
    rng = stable_rng(blind_id)
    core_indices = farthest_spatial_indices(xy, CORE_PER_SECTION, rng)
    core_xy = xy[core_indices]

    eligible_diversity = []
    for index, point in enumerate(xy):
        if index in core_indices:
            continue
        if np.min(np.linalg.norm(core_xy - point, axis=1)) < MIN_CENTER_SEPARATION_OVERVIEW_PX:
            continue
        eligible_diversity.append(index)
    diversity_features = np.array(
        [
            [
                candidates[index]["material_fraction"],
                candidates[index]["mean_darkness"],
                candidates[index]["mean_saturation"],
                candidates[index]["gray_std"],
            ]
            for index in eligible_diversity
        ]
    )
    diversity_xy = xy[eligible_diversity]
    local_diversity = kmeans_diversity_indices(
        diversity_features, diversity_xy, DIVERSITY_PER_SECTION, rng
    )
    diversity_indices = [eligible_diversity[index] for index in local_diversity]

    source_width = int(source["source_width_px"])
    source_height = int(source["source_height_px"])
    pixel_width_um = float(source["pixel_width_um"])
    pixel_height_um = float(source["pixel_height_um"])
    crop_width = int(round(TILE_WIDTH_UM / pixel_width_um))
    crop_height = int(round(TILE_WIDTH_UM / pixel_height_um))
    scale_x = source_width / width
    scale_y = source_height / height

    rows: list[dict[str, object]] = []
    for arm, selected, prefix in (
        ("CORE_SPATIAL", core_indices, "S"),
        ("DIVERSITY_SUPPLEMENT", diversity_indices, "D"),
    ):
        for rank, index in enumerate(selected, start=1):
            candidate = candidates[index]
            center_x = int(round(candidate["x"] * scale_x))
            center_y = int(round(candidate["y"] * scale_y))
            crop_x = max(0, min(center_x - crop_width // 2, source_width - crop_width))
            crop_y = max(0, min(center_y - crop_height // 2, source_height - crop_height))
            candidate_id = f"{blind_id}-{prefix}{rank:02d}"
            rows.append(
                {
                    "candidate_id": candidate_id,
                    "blind_id": blind_id,
                    "sampling_arm": arm,
                    "rank_within_arm": rank,
                    "overview_center_x_px": int(candidate["x"]),
                    "overview_center_y_px": int(candidate["y"]),
                    "overview_x0_px": int(candidate["x"] - half),
                    "overview_y0_px": int(candidate["y"] - half),
                    "overview_x1_px": int(candidate["x"] + half),
                    "overview_y1_px": int(candidate["y"] + half),
                    "envelope_fraction": round(candidate["envelope_fraction"], 6),
                    "material_fraction": round(candidate["material_fraction"], 6),
                    "mean_darkness": round(candidate["mean_darkness"], 6),
                    "mean_saturation": round(candidate["mean_saturation"], 6),
                    "gray_std": round(candidate["gray_std"], 6),
                    "source_file": source["source_file"],
                    "series_index": source["series_index"],
                    "source_width_px": source_width,
                    "source_height_px": source_height,
                    "pixel_width_um": pixel_width_um,
                    "pixel_height_um": pixel_height_um,
                    "crop_x_full_px": crop_x,
                    "crop_y_full_px": crop_y,
                    "crop_width_full_px": crop_width,
                    "crop_height_full_px": crop_height,
                    "export_downsample": EXPORT_DOWNSAMPLE,
                    "crop_filename": f"{candidate_id}__highres_region.png",
                }
            )
    return rows


def groovy_exporter_text() -> str:
    return r'''import qupath.lib.images.servers.ImageServers
import qupath.lib.regions.RegionRequest
import javax.imageio.ImageIO
import java.awt.Color
import java.awt.Font

def fail = { String m -> System.err.println("H4_REGION_EXPORT_ERROR\t"+m); System.exit(2) }
def csvFile = new File(System.getenv("IFQ_H4_REGION_CSV") ?: "")
def rootDir = new File(System.getenv("IFQ_H4_REGION_OUTPUT") ?: "")
if (!csvFile.isFile()) fail("candidate CSV missing")
if (!rootDir.isDirectory() && !rootDir.mkdirs()) fail("cannot create output")
def lines=csvFile.readLines("UTF-8")
if (lines.size()<2) fail("no candidates")
def header=lines[0].replace("\uFEFF", "").split(",",-1) as List
def parse={ String line -> def v=line.split(",",-1); def m=[:]; header.eachWithIndex{k,i->m[k]=v[i]}; m }
def rows=lines.drop(1).collect(parse)
def grouped=rows.groupBy{it.source_file+"|"+it.series_index}
grouped.each{ key, group ->
 def first=group[0]
 def server=ImageServers.buildServer(new File(first.source_file).toURI(),"--series",first.series_index)
 try {
  group.each{ r ->
   double ds=Double.parseDouble(r.export_downsample)
   int x=Integer.parseInt(r.crop_x_full_px), y=Integer.parseInt(r.crop_y_full_px)
   int w=Integer.parseInt(r.crop_width_full_px), h=Integer.parseInt(r.crop_height_full_px)
   def request=RegionRequest.createInstance(server.getPath(),ds,x,y,w,h)
   def image=server.readRegion(request)
   def g=image.createGraphics()
   def color=r.sampling_arm=="CORE_SPATIAL" ? new Color(0,110,255) : new Color(255,125,0)
   g.setColor(color); g.setFont(new Font("SansSerif",Font.BOLD,24))
   g.drawRect(1,1,image.getWidth()-3,image.getHeight()-3)
   g.fillRect(0,0,Math.min(image.getWidth(),340),36)
   g.setColor(Color.WHITE); g.drawString(r.candidate_id+"  "+r.sampling_arm,8,27)
   g.dispose()
   def armDir=new File(rootDir,r.sampling_arm=="CORE_SPATIAL" ? "02_CORE_SPATIAL_TILES" : "03_DIVERSITY_SUPPLEMENT_TILES")
   if (!armDir.isDirectory() && !armDir.mkdirs()) fail("cannot create arm output")
   ImageIO.write(image,"PNG",new File(armDir,r.crop_filename))
   println("H4_REGION_CROP\t"+r.candidate_id)
  }
 } finally { server.close() }
}
println("H4_REGION_EXPORT_COMPLETE\t"+rows.size())
'''


def prepare(r1_root: Path, output_root: Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    for relative in (
        "00_START_HERE",
        "01_OVERVIEW_SELECTION",
        "02_CORE_SPATIAL_TILES",
        "03_DIVERSITY_SUPPLEMENT_TILES",
        "04_REVIEW_FORMS",
        "INTERNAL_PROVENANCE",
        "logs",
    ):
        (output_root / relative).mkdir(parents=True, exist_ok=True)

    mapping = load_mapping(r1_root)
    review_root = r1_root / "SEND_TO_REVIEWER"
    all_rows: list[dict[str, object]] = []
    overview_paths: list[Path] = []
    for blind_id in sorted(mapping):
        raw_path = review_root / "01_RAW_REFERENCE" / f"{blind_id}__01_raw_reference.png"
        envelope_path = review_root / "03_MASK_REVIEW" / f"{blind_id}__lung_section_envelope__REVIEW_CANDIDATE.png"
        material_path = review_root / "03_MASK_REVIEW" / f"{blind_id}__stained_tissue_material__REVIEW_CANDIDATE.png"
        rows = select_section(blind_id, raw_path, envelope_path, material_path, mapping[blind_id])
        all_rows.extend(rows)
        overview_output = output_root / "01_OVERVIEW_SELECTION" / f"{blind_id}__H4_region_selection__DISPLAY_ONLY.png"
        make_overview(Image.open(raw_path), blind_id, rows, overview_output)
        overview_paths.append(overview_output)

    internal_fields = list(all_rows[0].keys())
    internal_csv = output_root / "INTERNAL_PROVENANCE" / "H4_REGION_CANDIDATES__UNBLINDED.csv"
    write_csv(internal_csv, internal_fields, all_rows)

    review_fields = [
        "candidate_id", "blind_id", "sampling_arm", "reviewable_yes_no",
        "airway_present_yes_no_uncertain", "vessel_present_yes_no_uncertain",
        "alveolar_parenchyma_present_yes_no_uncertain", "pleural_surface_present_yes_no_uncertain",
        "artifact_present_yes_no_uncertain", "dominant_context", "accept_geometry_yes_no_edit",
        "reviewer_id", "reviewed_utc", "notes",
    ]
    review_rows = [
        {"candidate_id": row["candidate_id"], "blind_id": row["blind_id"], "sampling_arm": row["sampling_arm"]}
        for row in all_rows
    ]
    write_csv(output_root / "04_REVIEW_FORMS" / "H4_REGION_REVIEW.csv", review_fields, review_rows)

    allowed = """Presence fields: yes | no | uncertain
dominant_context: airway | peribronchial | vascular | perivascular | alveolar | pleural | mixed | unresolved
accept_geometry_yes_no_edit: yes | no | edit
"""
    (output_root / "04_REVIEW_FORMS" / "ALLOWED_REVIEW_VALUES.txt").write_text(allowed, encoding="utf-8")
    readme = f"""# H4 anatomy-blind region review

This package replaces the lumen-size-ranked pilot. The **primary CORE_SPATIAL arm** contains {CORE_PER_SECTION} fixed-size regions per blinded section selected from the approved R1 tissue envelope using spatial coordinates only. It does not use lumen size, circularity, stain appearance, anatomy, genotype, infection status or outcome.

The **DIVERSITY_SUPPLEMENT arm** contains {DIVERSITY_PER_SECTION} additional regions per section selected using generic material fraction, darkness, saturation and grayscale variation. It improves visual coverage but must not be used for prevalence estimates.

Each region is {TILE_WIDTH_UM / 1000:.2f} mm square and exported from the original VSI at downsample {EXPORT_DOWNSAMPLE:g}. A region can contain more than one anatomical structure, so review uses independent presence fields rather than a forced single lumen class.

## Review order

1. Review `CONTACT_SHEET_HE-###_HIGHRES.jpg` for each blinded section.
2. Open the paired PNG in `02_CORE_SPATIAL_TILES` or `03_DIVERSITY_SUPPLEMENT_TILES` when needed.
3. Fill `04_REVIEW_FORMS/H4_REGION_REVIEW.csv`.
4. Mark airway, vessel, alveolar parenchyma, pleural surface and artifact independently as `yes`, `no` or `uncertain`.
5. Choose one dominant context: `airway`, `peribronchial`, `vascular`, `perivascular`, `alveolar`, `pleural`, `mixed` or `unresolved`.

The primary arm is anatomy-blind and spatially balanced, but this pilot is not yet a calibrated stereology or prevalence estimator. Do not infer inflammation, lesion severity, immune lineage, infection, genotype or a mouse-level endpoint.
"""
    (output_root / "00_START_HERE" / "README_H4_REGION_REVIEW.md").write_text(readme, encoding="utf-8")
    (output_root / "INTERNAL_PROVENANCE" / "export_h4_region_crops.groovy").write_text(groovy_exporter_text(), encoding="utf-8")
    design = {
        "protocol_version": PROTOCOL_VERSION,
        "created_utc": utc_now(),
        "status": "PREPARED_AWAITING_HIGH_RES_EXPORT",
        "source_r1_package": str(r1_root),
        "primary_arm": {
            "name": "CORE_SPATIAL",
            "count_per_blinded_section": CORE_PER_SECTION,
            "selection_inputs": ["approved_R1_tissue_envelope", "overview_coordinates", "fixed_seed"],
            "excluded_inputs": ["lumen_size", "circularity", "stain_features", "anatomy_label", "genotype", "infection_status", "outcome"],
            "method": "deterministic seeded farthest-point spatial balance over eligible fixed-size tissue-centered tiles",
        },
        "supplement_arm": {
            "name": "DIVERSITY_SUPPLEMENT",
            "count_per_blinded_section": DIVERSITY_PER_SECTION,
            "selection_inputs": ["material_fraction", "mean_darkness", "mean_saturation", "grayscale_variation"],
            "use_for_prevalence": False,
        },
        "tile_width_um": TILE_WIDTH_UM,
        "export_downsample": EXPORT_DOWNSAMPLE,
        "minimum_envelope_fraction": MIN_ENVELOPE_FRACTION,
    }
    (output_root / "INTERNAL_PROVENANCE" / "SAMPLING_DESIGN.json").write_text(json.dumps(design, indent=2), encoding="utf-8")
    make_contact_sheet(
        overview_paths,
        output_root / "00_START_HERE" / "CONTACT_SHEET_H4_REGION_SELECTION_OVERVIEWS.jpg",
        "H4 anatomy-blind spatial region selection",
        columns=2,
    )
    print(json.dumps({"status": "prepared", "output": str(output_root), "candidate_count": len(all_rows)}, indent=2))


def finalize(output_root: Path) -> None:
    candidates = read_csv(output_root / "INTERNAL_PROVENANCE" / "H4_REGION_CANDIDATES__UNBLINDED.csv")
    missing: list[str] = []
    inventory: list[dict[str, object]] = []
    for row in candidates:
        directory = "02_CORE_SPATIAL_TILES" if row["sampling_arm"] == "CORE_SPATIAL" else "03_DIVERSITY_SUPPLEMENT_TILES"
        path = output_root / directory / row["crop_filename"]
        if not path.is_file():
            missing.append(str(path))
            continue
        with Image.open(path) as image:
            image.verify()
        inventory.append(
            {
                "candidate_id": row["candidate_id"],
                "sampling_arm": row["sampling_arm"],
                "relative_path": path.relative_to(output_root).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    if missing:
        raise RuntimeError(f"Missing {len(missing)} exports; first: {missing[0]}")
    write_csv(
        output_root / "INTERNAL_PROVENANCE" / "H4_REGION_EXPORT_INVENTORY.csv",
        ["candidate_id", "sampling_arm", "relative_path", "bytes", "sha256"],
        inventory,
    )

    for blind_id in sorted({row["blind_id"] for row in candidates}):
        paths = []
        for row in candidates:
            if row["blind_id"] != blind_id:
                continue
            directory = "02_CORE_SPATIAL_TILES" if row["sampling_arm"] == "CORE_SPATIAL" else "03_DIVERSITY_SUPPLEMENT_TILES"
            paths.append(output_root / directory / row["crop_filename"])
        make_contact_sheet(
            paths,
            output_root / "00_START_HERE" / f"CONTACT_SHEET_{blind_id}_HIGHRES.jpg",
            f"{blind_id} - H4 spatial regions (blue primary, orange supplement)",
            columns=4,
        )

    files = []
    for path in sorted(output_root.rglob("*")):
        if not path.is_file() or path.name == "H4_REGION_PACKAGE_MANIFEST.json":
            continue
        files.append(
            {
                "relative_path": path.relative_to(output_root).as_posix(),
                "bytes": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )
    manifest = {
        "schema_version": "1.0.0",
        "created_utc": utc_now(),
        "status": "H4_REGION_REVIEW_REQUIRED_NOT_R2_RESULT",
        "primary_candidate_count": sum(row["sampling_arm"] == "CORE_SPATIAL" for row in candidates),
        "supplement_candidate_count": sum(row["sampling_arm"] == "DIVERSITY_SUPPLEMENT" for row in candidates),
        "review_unit": "fixed_size_multilabel_tissue_region",
        "files": files,
    }
    (output_root / "H4_REGION_PACKAGE_MANIFEST.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"status": "finalized", "output": str(output_root), "files": len(files)}, indent=2))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("prepare", "finalize"), required=True)
    parser.add_argument("--r1-root", type=Path, default=DEFAULT_R1)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.phase == "prepare":
        prepare(args.r1_root, args.output_root)
    else:
        finalize(args.output_root)


if __name__ == "__main__":
    main()
