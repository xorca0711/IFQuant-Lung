"""Build a blinded, clearly labelled H&E R1 reviewer package."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


DEFAULT_SOURCE = Path(r"D:\IFQ_Runs\H&E_20260812\04_r1_qc_candidate_ds64_nohole")
DEFAULT_DESTINATION = Path(r"D:\IFQ_Runs\H&E_20260812\05_R1_REVIEW_REQUEST")


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path: Path, rows: list[dict[str, object]], fields: list[str]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def font(size: int, bold: bool = False) -> ImageFont.ImageFont:
    names = ["arialbd.ttf", "Arial Bold.ttf"] if bold else ["arial.ttf", "Arial.ttf"]
    for name in names:
        try:
            return ImageFont.truetype(name, size=size)
        except OSError:
            pass
    return ImageFont.load_default(size=size)


def make_blinded_overlay(source: Path, destination: Path, blind_id: str) -> None:
    image = Image.open(source).convert("RGB")
    draw = ImageDraw.Draw(image)
    banner = max(42, round(image.height * 0.045))
    draw.rectangle((0, 0, image.width, banner), fill=(0, 0, 0))
    label = (
        f"{blind_id} | GREEN tissue | ORANGE pen | MAGENTA dark | "
        "YELLOW chromatic | PURPLE fold/dense"
    )
    draw.text((12, max(4, banner // 4)), label, fill=(255, 255, 255), font=font(max(12, banner // 3), True))
    image.save(destination, format="PNG", optimize=True)


def make_contact_sheet(source: Path, destination: Path, title: str) -> None:
    files = sorted(source.glob("*.png"))
    if len(files) != 8:
        raise RuntimeError(f"Expected eight PNG files in {source}, found {len(files)}")
    tile_w, tile_h, label_h, title_h = 520, 350, 36, 48
    sheet = Image.new("RGB", (tile_w * 2, title_h + (tile_h + label_h) * 4), "white")
    draw = ImageDraw.Draw(sheet)
    draw.text((12, 10), title, fill="black", font=font(22, True))
    for index, path in enumerate(files):
        blind_id = path.stem.split("__", 1)[0]
        x = (index % 2) * tile_w
        y = title_h + (index // 2) * (tile_h + label_h)
        draw.text((x + 8, y + 5), blind_id, fill="black", font=font(16, True))
        image = Image.open(path).convert("RGB")
        image.thumbnail((tile_w, tile_h), Image.Resampling.LANCZOS)
        paste_x = x + (tile_w - image.width) // 2
        paste_y = y + label_h + (tile_h - image.height) // 2
        sheet.paste(image, (paste_x, paste_y))
    sheet.save(destination, format="JPEG", quality=88, optimize=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-run", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--destination", type=Path, default=DEFAULT_DESTINATION)
    args = parser.parse_args()
    source = args.source_run.resolve()
    destination = args.destination.resolve()
    analysis = source / "analysis"
    manifest_path = analysis / "he_run_manifest.json"
    section_path = analysis / "tables" / "he_section_qc.csv"
    queue_path = analysis / "tables" / "he_review_queue.csv"
    if not all(path.is_file() for path in (manifest_path, section_path, queue_path)):
        raise SystemExit(f"Incomplete H&E R1 source run: {source}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("module") != "brightfield_he_r1_qc_candidate":
        raise SystemExit("Source is not an H&E R1 QC candidate")
    if manifest.get("status") != "COMPLETE_REVIEW_REQUIRED" or manifest.get("section_count") != 8:
        raise SystemExit("Source must be a complete eight-section review-pending run")
    if destination.exists() and any(destination.iterdir()):
        raise SystemExit(f"Destination is not empty: {destination}")

    send = destination / "SEND_TO_REVIEWER"
    internal = destination / "INTERNAL_DO_NOT_SEND"
    folders = {
        "start": send / "00_START_HERE",
        "raw": send / "01_RAW_REFERENCE",
        "stains": send / "02_STAIN_SEPARATION",
        "tissue": send / "03_TISSUE_MASKS",
        "overlays": send / "04_QC_OVERLAYS",
        "artifacts": send / "05_ARTIFACT_MASKS",
        "forms": send / "06_REVIEW_FORMS",
        "key": internal / "01_UNBLINDING_KEY",
        "provenance": internal / "02_PROVENANCE",
    }
    for folder_path in folders.values():
        folder_path.mkdir(parents=True, exist_ok=True)

    sections = read_csv(section_path)
    queue = read_csv(queue_path)
    blind_by_section = {row["section_id"]: row["blind_section_id"] for row in queue}
    if len(sections) != 8 or len(blind_by_section) != 8:
        raise SystemExit("Expected eight sections and eight blinded identifiers")
    review_rows: list[dict[str, object]] = []
    key_rows: list[dict[str, object]] = []
    package_rows: list[dict[str, object]] = []

    for section in sorted(sections, key=lambda row: blind_by_section[row["section_id"]]):
        blind = blind_by_section[section["section_id"]]
        names = {
            "raw_reference": f"{blind}__01_raw_reference.png",
            "stain_separation": f"{blind}__02_stain_separation__H-E-residual.png",
            "tissue_mask": f"{blind}__03_tissue_mask__white-tissue_black-background.png",
            "qc_overlay": f"{blind}__04_qc_overlay__green-tissue_orange-pen_magenta-dark_yellow-chromatic_purple-fold.png",
            "artifact_mask": f"{blind}__05_artifact_mask__orange-pen_magenta-dark_yellow-chromatic_purple-fold.png",
        }
        targets = {
            "raw_reference": folders["raw"] / names["raw_reference"],
            "stain_separation": folders["stains"] / names["stain_separation"],
            "tissue_mask": folders["tissue"] / names["tissue_mask"],
            "qc_overlay": folders["overlays"] / names["qc_overlay"],
            "artifact_mask": folders["artifacts"] / names["artifact_mask"],
        }
        shutil.copy2(analysis / "previews" / section["raw_preview"], targets["raw_reference"])
        shutil.copy2(analysis / "stain_separation" / section["stain_separation"], targets["stain_separation"])
        shutil.copy2(analysis / "masks" / section["tissue_mask"], targets["tissue_mask"])
        make_blinded_overlay(analysis / "qc_overlays" / section["qc_overlay"], targets["qc_overlay"], blind)
        shutil.copy2(analysis / "masks" / section["artifact_mask"], targets["artifact_mask"])

        candidates = {row["review_kind"]: row for row in queue if row["section_id"] == section["section_id"]}
        review_rows.append(
            {
                "blind_section_id": blind,
                "stain_separation": "PENDING",
                "tissue_mask": "PENDING",
                "dark_candidate_pixels": candidates["dark_saturated_candidate"]["candidate_pixels"],
                "dark_decision": "PENDING",
                "chromatic_candidate_pixels": candidates["chromatic_outlier_candidate"]["candidate_pixels"],
                "chromatic_decision": "PENDING",
                "fold_or_dense_candidate_pixels": candidates["fold_or_dense_material_candidate"]["candidate_pixels"],
                "fold_or_dense_decision": "PENDING",
                "reviewer_id": "",
                "reviewed_utc": "",
                "notes": "",
            }
        )
        key_rows.append(
            {
                "blind_section_id": blind,
                "section_id": section["section_id"],
                "mouse_id": section["mouse_id"],
                "slide_id": section["slide_id"],
                "series_name": section["series_name"],
                "series_index": section["series_index"],
            }
        )
        for kind, path in targets.items():
            package_rows.append(
                {
                    "blind_section_id": blind,
                    "file_kind": kind,
                    "relative_path": path.relative_to(destination).as_posix(),
                    "sha256": digest(path),
                }
            )

    review_fields = list(review_rows[0])
    write_csv(folders["forms"] / "R1_REVIEW_FORM.csv", review_rows, review_fields)
    detailed_fields = ["blind_section_id", "review_kind", "candidate_pixels", "decision", "reviewer_id", "reviewed_utc", "notes"]
    write_csv(
        folders["forms"] / "R1_DETAILED_CANDIDATE_QUEUE.csv",
        [{key: row[key] for key in detailed_fields} for row in queue],
        detailed_fields,
    )
    write_csv(folders["key"] / "SECTION_UNBLINDING_KEY__DO_NOT_SEND.csv", key_rows, list(key_rows[0]))
    shutil.copy2(manifest_path, folders["provenance"] / "he_run_manifest.json")
    shutil.copy2(analysis / "overlay_render_manifest.json", folders["provenance"] / "overlay_render_manifest.json")
    shutil.copy2(section_path, folders["provenance"] / "he_section_qc__unblinded.csv")
    write_csv(internal / "PACKAGE_FILE_MANIFEST_SHA256.csv", package_rows, list(package_rows[0]))

    sheets = [
        ("raw", "CONTACT_SHEET__01_RAW_REFERENCE.jpg", "H&E R1 review: raw reference"),
        ("stains", "CONTACT_SHEET__02_STAIN_SEPARATION.jpg", "H&E R1 review: hematoxylin / eosin / residual"),
        ("tissue", "CONTACT_SHEET__03_TISSUE_MASKS.jpg", "H&E R1 review: tissue masks"),
        ("overlays", "CONTACT_SHEET__04_QC_OVERLAYS.jpg", "H&E R1 review: QC overlays"),
        ("artifacts", "CONTACT_SHEET__05_ARTIFACT_MASKS.jpg", "H&E R1 review: artifact masks"),
    ]
    for source_key, name, title in sheets:
        make_contact_sheet(folders[source_key], folders["start"] / name, title)

    instructions = """# H&E R1 reviewer request

Review HE-001 through HE-008 without opening `INTERNAL_DO_NOT_SEND`.

For each section, confirm:

1. Hematoxylin/eosin separation is morphologically coherent and the residual is minimal.
2. Stained lung tissue is included; glass stays excluded; alveolar airspaces remain holes.
3. Magenta, yellow, and purple candidates are artifacts or valid tissue.

Enter decisions in `06_REVIEW_FORMS/R1_REVIEW_FORM.csv`.

Allowed decisions:

- stain/tissue: `ACCEPT`, `REJECT`, or `INDETERMINATE`
- artifact candidates: `RETAIN_TISSUE`, `EXCLUDE_ARTIFACT`, or `INDETERMINATE`

This review authorizes R1 image QC only. It does not authorize pathology or biological endpoints.
"""
    (folders["start"] / "README__REVIEW_REQUEST.md").write_text(instructions, encoding="utf-8")
    package_index = """# H&E R1 review package

- `SEND_TO_REVIEWER`: blinded images, contact sheets, and review forms.
- `INTERNAL_DO_NOT_SEND`: unblinding key, provenance, and SHA-256 file manifest.

Canonical source run: `04_r1_qc_candidate_ds64_nohole`.
"""
    (destination / "README__PACKAGE_LAYOUT.md").write_text(package_index, encoding="utf-8")

    root_index = f"""# H&E 20260812 directory layout

- `00_preflight`: source/series inspection.
- `01_pilot_r1_od010`: early exploratory pilot.
- `02_pilot_r2_od018`: prior H0-H3 pilot baseline.
- `03_r1_qc_candidate_ds32`: rejected runtime experiment.
- `03_r1_qc_candidate_ds64`: rejected hole-fill experiment.
- `04_r1_qc_candidate_ds64_nohole`: current accepted R1 QC source run; review pending.
- `05_R1_REVIEW_REQUEST`: organized blinded reviewer package.
- `90_diagnostics`: auxiliary diagnostics.

Use `05_R1_REVIEW_REQUEST/SEND_TO_REVIEWER` for review. Do not send the internal folder.
"""
    (destination.parent / "README__CURRENT_LAYOUT.md").write_text(root_index, encoding="utf-8")

    reviewer_pngs = list(send.rglob("*.png"))
    contact_sheets = list(folders["start"].glob("CONTACT_SHEET*.jpg"))
    if len(reviewer_pngs) != 40 or len(contact_sheets) != 5 or len(read_csv(folders["forms"] / "R1_REVIEW_FORM.csv")) != 8:
        raise SystemExit("Review package validation failed")
    print(f"H&E R1 review package created: {destination}")
    print("  blinded sections: 8")
    print("  reviewer PNG files: 40")
    print("  contact sheets: 5")
    print("  review form: SEND_TO_REVIEWER/06_REVIEW_FORMS/R1_REVIEW_FORM.csv")
    print("  unblinding key: INTERNAL_DO_NOT_SEND/01_UNBLINDING_KEY")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
