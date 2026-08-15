#!/usr/bin/env python3
"""Fail-closed status and review-package tooling for the G-SURF H&E pipeline.

This module does not make lesion calls. It reconciles the approved R1 image-QC
gate, inventories the exploratory H4 context, and builds a blinded whole-section
pathology review package for H5-H7 development.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_STUDY = REPO_ROOT / "config" / "studies" / "g_surf_he_20260812.json"
DEFAULT_RUBRIC = REPO_ROOT / "config" / "brightfield" / "he_pathology_review_rubric.json"
DEFAULT_REPO_PROFILE = (
    REPO_ROOT
    / "config"
    / "brightfield"
    / "he_stain_profiles"
    / "g_surf_he_20260812_reviewed_locked_v1.json"
)
DEFAULT_R1 = Path(r"D:\IFQ_Runs\H&E_20260812\10_R1_IMAGE_QC_APPROVED_FINAL")
DEFAULT_H4 = Path(
    r"D:\IFQ_Runs\H&E_20260812\13_H4_SPATIALLY_BALANCED_REGION_REVIEW"
)
DEFAULT_REVIEW_OUTPUT = Path(
    r"D:\IFQ_Runs\H&E_20260812\14_H5_H7_PATHOLOGY_REVIEW_DEVELOPMENT"
)


class ContractError(RuntimeError):
    """A fail-closed H&E contract violation."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_json(path: Path) -> dict[str, Any]:
    require(path.is_file(), f"Required JSON is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def read_csv(path: Path) -> list[dict[str, str]]:
    require(path.is_file(), f"Required CSV is missing: {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))


def write_json(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def write_csv(path: Path, fieldnames: list[str], rows: Iterable[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(path: Path) -> str:
    payload = read_json(path)
    encoded = json.dumps(
        payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def expected_sections(study: dict[str, Any]) -> list[str]:
    return [
        section_id
        for sample in study["samples"]
        for section_id in sample["section_ids"]
    ]


def validate_study(study: dict[str, Any]) -> dict[str, Any]:
    samples = study.get("samples", [])
    sections = expected_sections(study)
    blind_map = study.get("blind_section_map", [])
    source_root = Path(study["source_root"])

    require(study.get("modality") == "brightfield_he", "Study modality is not H&E.")
    require(
        len(samples) == study["expected_mouse_count"],
        "Declared mouse count does not match the sample list.",
    )
    require(
        len(sections) == study["expected_analytical_sections"],
        "Declared section count does not match the sample list.",
    )
    require(len(sections) == len(set(sections)), "Duplicate analytical section ID.")
    require(
        {row["section_id"] for row in blind_map} == set(sections),
        "Blind-section map does not cover the declared analytical sections exactly.",
    )
    require(
        len({row["blind_section_id"] for row in blind_map}) == len(sections),
        "Blind-section IDs are not unique.",
    )
    require(source_root.is_dir(), f"H&E source root is missing: {source_root}")

    expected_vsi = {sample["source_file"] for sample in samples}
    observed_vsi = {path.name for path in source_root.glob("*.vsi")}
    require(
        observed_vsi == expected_vsi,
        "VSI inventory differs from the four-slide study contract: "
        f"missing={sorted(expected_vsi - observed_vsi)}, "
        f"extra={sorted(observed_vsi - expected_vsi)}",
    )

    ets_counts: dict[str, int] = {}
    for sample in samples:
        source = source_root / sample["source_file"]
        require(source.is_file(), f"Declared VSI is missing: {source}")
        companion = source_root / f"_{source.stem}_"
        require(companion.is_dir(), f"VSI companion directory is missing: {companion}")
        ets_count = len(list(companion.rglob("*.ets")))
        require(ets_count >= 2, f"VSI companion data are incomplete: {companion}")
        ets_counts[sample["mouse_id"]] = ets_count

    return {
        "mouse_count": len(samples),
        "section_count": len(sections),
        "vsi_count": len(observed_vsi),
        "ets_files_by_mouse": ets_counts,
        "source_root": str(source_root),
    }


def verify_manifest_files(root: Path, manifest: dict[str, Any]) -> int:
    checked = 0
    for item in manifest.get("files", []):
        path = root / item["relative_path"]
        require(path.is_file(), f"Manifest file is missing: {path}")
        require(
            sha256_file(path).lower() == item["sha256"].lower(),
            f"Manifest hash mismatch: {path}",
        )
        checked += 1
    return checked


def validate_r1(
    study: dict[str, Any], r1_root: Path, repo_profile: Path = DEFAULT_REPO_PROFILE
) -> dict[str, Any]:
    require(r1_root.is_dir(), f"Approved R1 package is missing: {r1_root}")
    approved = study["approved_packages"]
    approval_path = r1_root / "R1_REVIEW_APPROVAL.json"
    manifest_path = r1_root / "PACKAGE_MANIFEST.json"
    source_profile = (
        r1_root
        / "INTERNAL_DO_NOT_SEND"
        / "04_LOCKED_R1_PROFILE"
        / "g_surf_he_20260812_reviewed_locked_v1.json"
    )

    require(
        sha256_file(approval_path) == approved["r1_approval_sha256"],
        "R1 approval hash differs from the study contract.",
    )
    require(
        sha256_file(manifest_path) == approved["r1_package_manifest_sha256"],
        "R1 package-manifest hash differs from the study contract.",
    )
    require(
        sha256_file(source_profile) == approved["locked_stain_profile_sha256"],
        "Approved stain-profile file hash differs from the study contract.",
    )
    require(
        canonical_json_sha256(source_profile) == canonical_json_sha256(repo_profile),
        "Repository stain profile is not semantically identical to the approved profile.",
    )

    approval = read_json(approval_path)
    manifest = read_json(manifest_path)
    require(approval.get("decision") == "APPROVED_IMAGE_QC", "R1 QC is not approved.")
    require(
        manifest.get("status") == "R1_IMAGE_QC_APPROVED_FINAL",
        "R1 package does not carry final-approved status.",
    )
    blind_ids = {row["blind_section_id"] for row in study["blind_section_map"]}
    require(set(manifest.get("sections", [])) == blind_ids, "R1 section set mismatch.")
    checked = verify_manifest_files(r1_root, manifest)

    metrics = read_csv(r1_root / "R1_APPROVED_QC_METRICS.csv")
    require(len(metrics) == len(blind_ids), "R1 QC metrics are incomplete.")
    require(
        {row["blind_id"] for row in metrics} == blind_ids,
        "R1 QC metrics do not match the blind-section set.",
    )
    return {
        "decision": approval["decision"],
        "approved_utc": approval["approved_utc"],
        "section_count": len(metrics),
        "manifest_files_verified": checked,
        "locked_profile_id": approved["locked_stain_profile_id"],
    }


def validate_h4(study: dict[str, Any], h4_root: Path) -> dict[str, Any]:
    require(h4_root.is_dir(), f"H4 development package is missing: {h4_root}")
    manifest_path = h4_root / "H4_REGION_PACKAGE_MANIFEST.json"
    require(
        sha256_file(manifest_path)
        == study["approved_packages"]["h4_package_manifest_sha256"],
        "H4 package-manifest hash differs from the study contract.",
    )
    manifest = read_json(manifest_path)
    require(
        manifest.get("status") == "H4_REGION_REVIEW_REQUIRED_NOT_R2_RESULT",
        "H4 package status is not the expected development-only state.",
    )

    candidates = read_csv(
        h4_root / "INTERNAL_PROVENANCE" / "H4_REGION_CANDIDATES__UNBLINDED.csv"
    )
    inventory = read_csv(
        h4_root / "INTERNAL_PROVENANCE" / "H4_REGION_EXPORT_INVENTORY.csv"
    )
    reviews = read_csv(h4_root / "04_REVIEW_FORMS" / "H4_REGION_REVIEW.csv")
    require(len(candidates) == 96, "H4 candidate inventory must contain 96 regions.")
    require(len(inventory) == len(candidates), "H4 export inventory is incomplete.")
    require(len(reviews) == len(candidates), "H4 review form is incomplete.")

    for row in inventory:
        path = h4_root / row["relative_path"]
        require(path.is_file(), f"H4 region export is missing: {path}")
        require(sha256_file(path) == row["sha256"], f"H4 region hash mismatch: {path}")

    editable = [
        field
        for field in reviews[0]
        if field not in {"candidate_id", "blind_id", "sampling_arm"}
    ]
    touched = sum(any((row.get(field) or "").strip() for field in editable) for row in reviews)
    required = [
        "reviewable_yes_no",
        "dominant_context",
        "accept_geometry_yes_no_edit",
        "reviewer_id",
        "reviewed_utc",
    ]
    completed = sum(all((row.get(field) or "").strip() for field in required) for row in reviews)
    return {
        "status": manifest["status"],
        "candidate_count": len(candidates),
        "primary_spatial_count": sum(
            row["sampling_arm"] == "CORE_SPATIAL" for row in candidates
        ),
        "diversity_supplement_count": sum(
            row["sampling_arm"] == "DIVERSITY_SUPPLEMENT" for row in candidates
        ),
        "review_rows_touched": touched,
        "review_rows_complete": completed,
        "use_as_endpoint": False,
    }


def stage_rows(r1: dict[str, Any], h4: dict[str, Any]) -> list[dict[str, str]]:
    return [
        {"stage": "H0", "status": "PASS", "evidence": "4 declared calibrated RGB VSI slides"},
        {"stage": "H1", "status": "PASS", "evidence": "4 mice / 8 analytical sections / blind map"},
        {"stage": "H2", "status": "APPROVED_R1", "evidence": r1["locked_profile_id"]},
        {"stage": "H3", "status": "APPROVED_R1", "evidence": "reviewed masks and artifact presentation"},
        {
            "stage": "H4",
            "status": "DEVELOPMENT_CONTEXT_AVAILABLE",
            "evidence": f"{h4['candidate_count']} regions; incomplete review; not an endpoint",
        },
        {
            "stage": "H5",
            "status": "BLOCKED",
            "evidence": "no validated nuclei or lesion-candidate engine",
        },
        {
            "stage": "H6",
            "status": "BLOCKED",
            "evidence": "no validated compartment/topology authorization",
        },
        {
            "stage": "H7",
            "status": "RUBRIC_DEFINED_REVIEW_REQUIRED",
            "evidence": "blinded whole-section development rubric",
        },
        {
            "stage": "H8",
            "status": "BLOCKED",
            "evidence": "section review and endpoint components incomplete",
        },
        {
            "stage": "H9",
            "status": "BLOCKED",
            "evidence": "n=1 per design cell; mouse-level descriptive join only",
        },
    ]


def pipeline_status(
    study_path: Path = DEFAULT_STUDY,
    r1_root: Path = DEFAULT_R1,
    h4_root: Path = DEFAULT_H4,
) -> dict[str, Any]:
    study = read_json(study_path)
    source = validate_study(study)
    r1 = validate_r1(study, r1_root)
    h4 = validate_h4(study, h4_root)
    return {
        "schema_version": "1.0.0",
        "checked_utc": utc_now(),
        "study_id": study["study_id"],
        "highest_authorized_release": "R1",
        "highest_authorized_stage": "H3",
        "launcher_route_enabled": False,
        "source": source,
        "r1": r1,
        "h4": h4,
        "stages": stage_rows(r1, h4),
        "reportability": {
            "image_qc_and_denominator": "APPROVED_FOR_THIS_COHORT",
            "section_pathology_scores": "REVIEW_REQUIRED",
            "automated_lesion_burden": "NOT_AVAILABLE",
            "mouse_summary": "BLOCKED",
            "group_inference": "NOT_SUPPORTED_N1_PER_DESIGN_CELL",
            "he_identifies_krt5_pod": False,
            "immune_lineage_from_he": False,
        },
    }


def section_review_rows(
    study: dict[str, Any], rubric: dict[str, Any]
) -> tuple[list[str], list[dict[str, str]]]:
    fields = list(rubric["section_form_fields"])
    rows = []
    for mapping in sorted(
        study["blind_section_map"], key=lambda row: row["blind_section_id"]
    ):
        row = {field: "" for field in fields}
        row["blind_section_id"] = mapping["blind_section_id"]
        rows.append(row)
    return fields, rows


def _copy_required(source: Path, target: Path) -> None:
    require(source.is_file(), f"Review-package source is missing: {source}")
    shutil.copy2(source, target)


def build_review_package(
    output_root: Path,
    study_path: Path = DEFAULT_STUDY,
    rubric_path: Path = DEFAULT_RUBRIC,
    r1_root: Path = DEFAULT_R1,
    h4_root: Path = DEFAULT_H4,
) -> dict[str, Any]:
    require(not output_root.exists(), f"Refusing to overwrite existing output: {output_root}")
    study = read_json(study_path)
    rubric = read_json(rubric_path)
    status = pipeline_status(study_path, r1_root, h4_root)
    require(status["highest_authorized_release"] == "R1", "R1 gate is not satisfied.")

    directories = [
        "00_START_HERE",
        "01_BLINDED_SECTION_OVERVIEWS",
        "02_R1_APPROVED_QC_CONTEXT",
        "03_HIGH_RES_SUPPORTING_CONTEXT",
        "04_REVIEW_FORMS",
        "INTERNAL_DO_NOT_SEND",
    ]
    for relative in directories:
        (output_root / relative).mkdir(parents=True, exist_ok=False)

    reviewer_root = r1_root / "SEND_TO_REVIEWER"
    blind_ids = [
        row["blind_section_id"]
        for row in sorted(
            study["blind_section_map"], key=lambda row: row["blind_section_id"]
        )
    ]
    for blind_id in blind_ids:
        _copy_required(
            reviewer_root / "01_RAW_REFERENCE" / f"{blind_id}__01_raw_reference.png",
            output_root
            / "01_BLINDED_SECTION_OVERVIEWS"
            / f"{blind_id}__whole_section_reference.png",
        )
        _copy_required(
            reviewer_root / "04_QC_OVERLAYS" / f"{blind_id}__qc_overlay__DISPLAY_ONLY.png",
            output_root
            / "02_R1_APPROVED_QC_CONTEXT"
            / f"{blind_id}__R1_approved_qc_display_only.png",
        )
        _copy_required(
            h4_root / "00_START_HERE" / f"CONTACT_SHEET_{blind_id}_HIGHRES.jpg",
            output_root
            / "03_HIGH_RES_SUPPORTING_CONTEXT"
            / f"{blind_id}__high_resolution_supporting_regions.jpg",
        )

    fields, rows = section_review_rows(study, rubric)
    write_csv(
        output_root / "04_REVIEW_FORMS" / "H7_SECTION_PATHOLOGY_REVIEW.csv",
        fields,
        rows,
    )
    write_csv(
        output_root / "04_REVIEW_FORMS" / "ENDPOINT_REPORTABILITY.csv",
        ["endpoint", "current_status", "maximum_claim"],
        rubric["reportability"],
    )
    shutil.copy2(
        rubric_path,
        output_root / "04_REVIEW_FORMS" / "PATHOLOGY_REVIEW_RUBRIC.json",
    )

    unblinding_source = (
        r1_root
        / "INTERNAL_DO_NOT_SEND"
        / "01_UNBLINDING_KEY"
        / "SECTION_UNBLINDING_KEY__DO_NOT_SEND.csv"
    )
    _copy_required(
        unblinding_source,
        output_root / "INTERNAL_DO_NOT_SEND" / "SECTION_UNBLINDING_KEY__DO_NOT_SEND.csv",
    )
    for source, name in (
        (r1_root / "R1_REVIEW_APPROVAL.json", "R1_REVIEW_APPROVAL.json"),
        (r1_root / "PACKAGE_MANIFEST.json", "R1_PACKAGE_MANIFEST.json"),
        (h4_root / "H4_REGION_PACKAGE_MANIFEST.json", "H4_CONTEXT_PACKAGE_MANIFEST.json"),
    ):
        _copy_required(source, output_root / "INTERNAL_DO_NOT_SEND" / name)
    write_json(output_root / "INTERNAL_DO_NOT_SEND" / "PIPELINE_STATUS.json", status)

    readme = """# H&E whole-section pathology review - development package

## What to review

Score each blinded whole section in 04_REVIEW_FORMS/H7_SECTION_PATHOLOGY_REVIEW.csv.
Start with the whole-section reference, use the approved R1 overlay only to recognize
the accepted denominator/artifacts, and consult the high-resolution contact sheet
only when morphology needs confirmation.

This replaces the low-value task of classifying every sampled tile as airway,
vessel, or alveolus. The primary decision is whether the section contains abnormal
inflammatory-cell-rich structural injury, how extensive it is, and where it occurs.

## Anatomy shorthand

- Airway: circular or branching lumen surrounded by a continuous epithelial
  nuclear lining.
- Alveolar parenchyma: sponge-like small airspaces separated by thin septa.
- Vessel: thin-walled elongated/slit-like or partly collapsed lumen with an
  endothelial nuclear lining.

## Interpretation boundary

H&E can support inflammatory-cell-rich infiltration, consolidation, cuffing,
airspace loss, and epithelial injury. It cannot identify immune lineage or a
KRT5-positive pod. Pod identity remains in the settled IF analysis; H&E supplies
whole-section injury context. The current n=4 cohort is descriptive only.
"""
    (output_root / "00_START_HERE" / "README_REVIEW.md").write_text(
        readme, encoding="utf-8"
    )

    files = []
    for package_file in sorted(output_root.rglob("*")):
        if not package_file.is_file() or package_file.name == "PACKAGE_MANIFEST.json":
            continue
        files.append(
            {
                "relative_path": package_file.relative_to(output_root).as_posix(),
                "bytes": package_file.stat().st_size,
                "sha256": sha256_file(package_file),
            }
        )
    package_manifest = {
        "schema_version": "1.0.0",
        "created_utc": utc_now(),
        "status": "H5_H7_DEVELOPMENT_REVIEW_REQUIRED_NOT_AN_ANALYSIS_RESULT",
        "study_id": study["study_id"],
        "highest_input_release": "R1",
        "section_count": len(blind_ids),
        "primary_review_unit": "blinded_whole_section",
        "supporting_region_role": "evidence_locator_not_replicate_or_prevalence_sample",
        "files": files,
    }
    write_json(output_root / "PACKAGE_MANIFEST.json", package_manifest)
    return package_manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    status_parser = subparsers.add_parser("status", help="Validate and print H&E stage status.")
    status_parser.add_argument("--study", type=Path, default=DEFAULT_STUDY)
    status_parser.add_argument("--r1-root", type=Path, default=DEFAULT_R1)
    status_parser.add_argument("--h4-root", type=Path, default=DEFAULT_H4)
    status_parser.add_argument("--output", type=Path)

    build_parser = subparsers.add_parser(
        "build-review", help="Build the blinded whole-section pathology review package."
    )
    build_parser.add_argument("--study", type=Path, default=DEFAULT_STUDY)
    build_parser.add_argument("--rubric", type=Path, default=DEFAULT_RUBRIC)
    build_parser.add_argument("--r1-root", type=Path, default=DEFAULT_R1)
    build_parser.add_argument("--h4-root", type=Path, default=DEFAULT_H4)
    build_parser.add_argument("--output-root", type=Path, default=DEFAULT_REVIEW_OUTPUT)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "status":
            result = pipeline_status(args.study, args.r1_root, args.h4_root)
            if args.output:
                args.output.parent.mkdir(parents=True, exist_ok=True)
                write_json(args.output, result)
        else:
            result = build_review_package(
                args.output_root, args.study, args.rubric, args.r1_root, args.h4_root
            )
        print(json.dumps(result, indent=2))
        return 0
    except ContractError as error:
        print(f"H&E PIPELINE CONTRACT ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
