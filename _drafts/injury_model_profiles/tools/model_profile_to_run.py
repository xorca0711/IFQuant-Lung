#!/usr/bin/env python3
"""
model_profile_to_run.py
=====================================================================
Turn ONE injury-model profile into the exact run configuration for a batch:
IFQ_* environment files, a samplesheet contract, a run plan, and a provenance
record. The profile is the single source of truth. Nobody hand-types a
threshold, and nobody can quietly promote a placeholder to a frozen value.

WHY THIS EXISTS
---------------
IF_Quant_Pipeline.groovy is FROZEN. Its only inputs are:
  * IFQ_* environment variables            (IF_Quant_Pipeline.groovy:114-117)
  * config/lung_marker_registry.json       (IFQ_MARKER_REGISTRY, line 158)
  * an optional custom panel JSON          (IFQ_PANEL_CONFIG, line 159)
  * an optional panel-map CSV              (IFQ_PANEL_MAP_PATH, line 164)
  * samplesheet.csv inside IFQ_INPUT_DIR   (lines 297-299, 3385-3425)
So a profile can be consumed WITHOUT touching the engine: this generator writes
the env set and the samplesheet contract, and nothing else changes.

THE FAILURE MODE THIS GUARDS AGAINST
------------------------------------
`envOr` falls back silently when a variable is absent OR empty. A typo'd
IFQ_KRT_5_THRESHOLD is not an error -- the engine simply uses adaptive Otsu and
the run looks fine. So every emitted name is checked against the engine's real
environment surface, and every marker token is resolved through the same
normalisation the engine uses.

VALIDATION LADDER (the anti-"pilot placeholder" mechanism)
----------------------------------------------------------
Every parameter carries validation.status. The profile's effective status is
RECOMPUTED here as the weakest link over the parameters the primary endpoint
depends on. An authored profile status stronger than the computed one is a hard
error, not a warning. --tier confirmatory additionally requires every relevant
calibration_requirement to be `frozen` and no blocking confound to be open.

Standard library only, to match aggregate_to_mouse.py.

USAGE
-----
  python model_profile_to_run.py PROFILE.json --check
  python model_profile_to_run.py PROFILE.json --outdir ./run_cfg --tier exploratory \
      --input-dir D:/wsi/slideA/tiles --output-dir D:/wsi/slideA/analysis
  python model_profile_to_run.py PROFILE.json --validate-samplesheet path/to/samplesheet.csv
  python model_profile_to_run.py --selftest
=====================================================================
"""
import argparse
import csv
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone

PROFILE_SCHEMA_PIN = "injury_model_profile/1.0.0"

# ---------------------------------------------------------------------------
# Engine environment surface.
# Extracted from the two front-ends; anything not here is a typo, because the
# engine ignores unknown IFQ_* names without complaint.
#   IF_Quant_Pipeline.groovy      -> stage2
#   qupath_wsi_tile_export.groovy -> stage1
# ---------------------------------------------------------------------------
STAGE2_STATIC_ENV = {
    "IFQ_ACTUB_CILIA_DENSITY_RADIUS_UM", "IFQ_ACTUB_CILIA_MIN_LOCAL_DENSITY",
    "IFQ_ACTUB_CILIA_SEED_PERCENTILE", "IFQ_ACTUB_MAX_COMPONENT_DISTANCE_UM",
    "IFQ_ACTUB_MAX_PATCH_AREA_UM2", "IFQ_ACTUB_MIN_COMPONENT_BOUNDARY_DISTANCE_UM",
    "IFQ_ACTUB_MIN_PATCH_AREA_UM2", "IFQ_ACTUB_MIN_SUPPORT_FRACTION",
    "IFQ_ACTUB_SUPPORT_EXPAND_UM", "IFQ_ALLOW_NONEMPTY_OUTPUT",
    "IFQ_COMPARTMENT_MODE", "IFQ_DAPI_BACKGROUND_RADIUS_UM", "IFQ_DAPI_BLUR_SIGMA_PX",
    "IFQ_DAPI_CONTRAST_SATURATION", "IFQ_DAPI_LOCAL_RADIUS_UM", "IFQ_DAPI_METHOD",
    "IFQ_DISPLAY_GAMMA", "IFQ_DISPLAY_HIGH_PERCENTILE", "IFQ_DISPLAY_LOW_PERCENTILE",
    "IFQ_DISPLAY_PREVIEW_ONLY", "IFQ_EXPORT_DISPLAY_CHANNELS", "IFQ_INCLUDE_REGEX",
    "IFQ_INPUT_DIR", "IFQ_MARKER_REGISTRY", "IFQ_MAX_IMAGES", "IFQ_MIN_INCLUDED_NUCLEI",
    "IFQ_MIN_NUCLEUS_AREA_UM2", "IFQ_MORPHOLOGY_PRIMARY", "IFQ_MRAGE_MIN_RING_FRACTION",
    "IFQ_OUTPUT_DIR", "IFQ_PANEL", "IFQ_PANEL_CONFIG", "IFQ_PANEL_MAP_PATH",
    "IFQ_PROJECTION", "IFQ_RECURSIVE", "IFQ_RING_EXPAND_UM", "IFQ_SEGMENTER",
    "IFQ_SINGLE_PLANE", "IFQ_T1A_MIN_RING_FRACTION", "IFQ_TISSUE_MODE",
    "IFQ_WHOLE_FIELD_COMPARTMENT", "IFQ_Z_APICAL_PLANES", "IFQ_Z_APICAL_RANGE",
    "IFQ_Z_CELL_BODY_PLANES", "IFQ_Z_CELL_BODY_RANGE", "IFQ_Z_NUCLEAR_RANGE",
}
STAGE1_STATIC_ENV = {
    "IFQ_WSI_AGER_CHANNEL", "IFQ_WSI_AGER_THRESHOLD", "IFQ_WSI_CHANNEL_PATTERNS",
    "IFQ_WSI_COMPRESSION", "IFQ_WSI_CORE_PX", "IFQ_WSI_DAMAGE_CUTOFF",
    "IFQ_WSI_DAMAGE_SIGMA_UM", "IFQ_WSI_DRY_RUN", "IFQ_WSI_EXPECT_CHANNELS",
    "IFQ_WSI_FILL_INTERIOR_RINGS", "IFQ_WSI_HALO_PX", "IFQ_WSI_INPUT",
    "IFQ_WSI_MAX_PIXEL_UM", "IFQ_WSI_MAX_TILES_PER_SLIDE", "IFQ_WSI_MIN_FRAGMENT_MM2",
    "IFQ_WSI_MIN_TILE_TISSUE_UM2", "IFQ_WSI_OUTPUT", "IFQ_WSI_PANEL",
    "IFQ_WSI_PARALLEL", "IFQ_WSI_PARTITION_DAMAGE", "IFQ_WSI_RESUME",
    "IFQ_WSI_ROI_COMPARTMENT", "IFQ_WSI_ROI_NAME", "IFQ_WSI_ROI_NAME_DAMAGED",
    "IFQ_WSI_ROI_NAME_INTACT", "IFQ_WSI_SLIDE_METADATA", "IFQ_WSI_TISSUE_BLUR_SIGMA",
    "IFQ_WSI_TISSUE_CLOSE_RADIUS", "IFQ_WSI_TISSUE_DOWNSAMPLE", "IFQ_WSI_TISSUE_OPEN_RADIUS",
    "IFQ_WSI_WRITE_TILE_PX",
}
# Per-marker families. <TOKEN> = marker upper-cased with every non-alphanumeric
# stripped (IF_Quant_Pipeline.groovy:194 and :657-658).
MARKER_ENV_SUFFIXES = {
    "_THRESHOLD": "candidate-pixel intensity cutoff (line 878-880)",
    "_MIN_POSITIVE_FRACTION": "morphology support fraction (line 904)",
    "_MIN_LARGEST_COMPONENT_SHARE": "connected-pattern share (line 905)",
    "_MIN_NUCLEAR_ENRICHMENT": "nuclear enrichment; ONLY applied when the resolved rule already has this key (line 906-908)",
    "_MIN_NUC_CYTO_RATIO": "nuclear:cytoplasmic ratio; ONLY applied when the resolved rule already has this key (line 909-911)",
}
CONDITIONAL_MARKER_SUFFIXES = {"_MIN_NUCLEAR_ENRICHMENT", "_MIN_NUC_CYTO_RATIO"}

# Fixed contract with aggregate_to_mouse.py (lines 43-44).
AGG_KEY_COLS = ["mouse_id", "genotype", "condition", "panel"]
AGG_ROW_ID_COLS = ["image", "region", "section_id"]
SAMPLESHEET_COLUMNS = ["filename", "mouse_id", "section_id", "genotype",
                       "condition", "panel", "relative_path"]

# Built-in panel channel maps we can cross-check against (IF_Quant_Pipeline.groovy:452-527).
BUILTIN_PANEL_CHANNELS = {
    "LEFT":  {"DAPI": 1, "KRT5": 2, "AGER": 3, "T1A": 4},
    "RIGHT": {"DAPI": 1, "ProSPC": 2, "AGER": 3, "KRT8": 4},
    "ALI1":  {"DAPI": 1, "SCGB3A2": 2, "tdTOM": 3, "p63": 4},
    "ALI2":  {"DAPI": 1, "KRT5": 2, "tdTOM": 3, "AcTub": 4},
    "ALI3":  {"DAPI": 1, "KRT5": 2, "tdTOM": 3, "MUC5AC": 4},
}

# Validation ladder. Higher = stronger. None = outside the ladder.
STATUS_RANK = {
    "unset": -1,
    "placeholder": 0,
    "literature": 1,
    "pilot_tuned": 1,
    "control_derived": 2,
    "frozen_blinded_controls": 3,
    "held_out_readout": None,
    "not_applicable": None,
}
TIER_MIN_RANK = {"dry": -1, "exploratory": 1, "confirmatory": 3}


# ---------------------------------------------------------------------------
class ProfileError(Exception):
    pass


def normalize_marker_token(value):
    """Same normalisation the engine applies (IF_Quant_Pipeline.groovy:657-658)."""
    if value is None:
        return ""
    return re.sub(r"[^A-Z0-9]+", "", str(value).upper())


def fmt_env_value(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        s = repr(v)
        return s[:-2] if s.endswith(".0") else s
    return str(v)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------------------------------------------------------------------
def load_registry(path):
    """Return (alias_token -> canonical) or None when the registry is absent."""
    if not path or not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8-sig") as fh:
        doc = json.load(fh)
    markers = doc.get("markers") or {}
    index = {}
    for canonical, profile in markers.items():
        names = [canonical] + list((profile or {}).get("aliases") or [])
        for name in names:
            tok = normalize_marker_token(name)
            if tok:
                index[tok] = canonical
    return {"index": index, "schema_version": doc.get("schema_version", "unknown"),
            "n_markers": len(markers),
            "research_profiles": sorted((doc.get("research_profiles") or {}).keys())}


def classify_env_name(name, registry):
    """
    -> (stage, kind, detail).  stage in {stage1, stage2, None}.
    kind in {static, marker_family, unknown_marker, unknown}.
    """
    if name in STAGE1_STATIC_ENV:
        return "stage1", "static", ""
    if name in STAGE2_STATIC_ENV:
        return "stage2", "static", ""
    for suffix, detail in MARKER_ENV_SUFFIXES.items():
        if name.endswith(suffix) and name.startswith("IFQ_"):
            token = name[len("IFQ_"):-len(suffix)]
            if not token:
                continue
            if registry is None:
                return "stage2", "marker_family", detail + " (registry unavailable; token unchecked)"
            if token in registry["index"]:
                return "stage2", "marker_family", detail
            return "stage2", "unknown_marker", (
                "token '%s' does not resolve to any registry marker or alias. The engine "
                "would ignore this variable silently. %s" % (token, detail))
    return None, "unknown", "not part of the engine's environment surface"


# ---------------------------------------------------------------------------
def jsonschema_check(profile, schema_path):
    """
    Validate against the real JSON Schema when `jsonschema` happens to be
    installed. It is NOT a dependency: structural_check below duplicates the
    load-bearing rules so the tool works on a bare Python, exactly like
    aggregate_to_mouse.py.
    """
    if not schema_path or not os.path.isfile(schema_path):
        return None
    try:
        from jsonschema import Draft202012Validator
    except ImportError:
        return None
    with open(schema_path, encoding="utf-8-sig") as fh:
        schema = json.load(fh)
    v = Draft202012Validator(schema)
    return ["schema: %s: %s" % ("/".join(str(p) for p in e.path) or "<root>", e.message)
            for e in sorted(v.iter_errors(profile), key=lambda e: list(e.path))]


def structural_check(profile):
    """Schema-shaped checks that do not need jsonschema installed."""
    errs = []
    if profile.get("profile_schema") != PROFILE_SCHEMA_PIN:
        errs.append("profile_schema must be %r" % PROFILE_SCHEMA_PIN)
    required = ["model_id", "profile_version", "title", "species", "authored",
                "registry_requirement", "panels", "insult", "timepoints",
                "cell_states", "morphology_signature", "primary_endpoint",
                "controls", "confounds", "compartment_tags",
                "calibration_requirements", "parameters", "aggregation_contract"]
    for key in required:
        if key not in profile:
            errs.append("missing required top-level key: %s" % key)
    if errs:
        return errs

    if not re.match(r"^[a-z][a-z0-9_]{2,47}$", profile["model_id"]):
        errs.append("model_id must be lower_snake, 3-48 chars")
    if not re.match(r"^\d+\.\d+\.\d+$", profile["profile_version"]):
        errs.append("profile_version must be semver")

    # Aggregation contract: the single most important invariant.
    agg = profile["aggregation_contract"]
    if agg.get("statistical_n") != "mouse":
        errs.append("aggregation_contract.statistical_n must be 'mouse'")
    if list(agg.get("key_cols") or []) != AGG_KEY_COLS:
        errs.append("aggregation_contract.key_cols must be exactly %s (aggregate_to_mouse.py:43). "
                    "A new grouping column forks the aggregator." % AGG_KEY_COLS)
    if list(agg.get("row_id_cols") or []) != AGG_ROW_ID_COLS:
        errs.append("aggregation_contract.row_id_cols must be exactly %s (aggregate_to_mouse.py:44)"
                    % AGG_ROW_ID_COLS)
    if "stage4_to_mouse" not in (agg.get("path") or []):
        errs.append("aggregation_contract.path must terminate in stage4_to_mouse")

    # Every parameter needs a validation block and a consumed_by.
    params = profile["parameters"]
    if not isinstance(params, dict) or not params:
        errs.append("parameters must be a non-empty object")
        return errs
    for pid, p in params.items():
        if not isinstance(p, dict):
            errs.append("parameter %s is not an object" % pid)
            continue
        for key in ("value", "units", "consumed_by", "validation"):
            if key not in p:
                errs.append("parameter %s is missing '%s'" % (pid, key))
        v = p.get("validation") or {}
        if v.get("status") not in STATUS_RANK:
            errs.append("parameter %s has invalid validation.status %r" % (pid, v.get("status")))
        if not str(v.get("evidence") or "").strip():
            errs.append("parameter %s has empty validation.evidence. Write 'none' if there is none; "
                        "an empty string is how a placeholder becomes invisible." % pid)
        cb = p.get("consumed_by") or {}
        if cb.get("kind") == "env":
            if not re.match(r"^IFQ_[A-Z0-9_]+$", str(cb.get("env") or "")):
                errs.append("parameter %s has a malformed env name %r" % (pid, cb.get("env")))
            if cb.get("stage") not in ("stage1", "stage2"):
                errs.append("parameter %s env must declare stage1 or stage2" % pid)
        elif cb.get("kind") == "samplesheet":
            if cb.get("column") not in SAMPLESHEET_COLUMNS:
                errs.append("parameter %s targets unknown samplesheet column %r" % (pid, cb.get("column")))
        elif cb.get("kind") not in ("panel_config", "wetlab", "downstream"):
            errs.append("parameter %s has unknown consumed_by.kind %r" % (pid, cb.get("kind")))

    # The not_yet_implemented escape hatch must point at a live blocking confound.
    confounds = {c.get("confound_id"): c for c in (profile.get("confounds") or [])}
    for pid, p in params.items():
        nyi = (p or {}).get("not_yet_implemented")
        if not nyi:
            continue
        if not p.get("endpoint_critical"):
            errs.append("parameter %s declares not_yet_implemented but is not endpoint_critical; "
                        "the escape hatch is only meaningful for load-bearing parameters." % pid)
        cf_id = nyi.get("tracked_by_confound")
        cf = confounds.get(cf_id)
        if cf is None:
            errs.append("parameter %s defers to confound %r, which does not exist. A deferred "
                        "endpoint-critical parameter must leave a visible record of the risk." % (pid, cf_id))
        else:
            if not cf.get("blocking"):
                errs.append("parameter %s defers to confound %r, but that confound has blocking=false. "
                            "Deferring a load-bearing parameter is only admissible if the resulting "
                            "risk blocks the claim." % (pid, cf_id))
            if cf.get("status") == "resolved":
                errs.append("parameter %s defers to confound %r, which is marked resolved. If the "
                            "confound is resolved the parameter can no longer be deferred." % (pid, cf_id))

    # Endpoints.
    for ep in [profile["primary_endpoint"]] + list(profile.get("secondary_endpoints") or []) \
            + list(profile.get("qc_endpoints") or []):
        eid = ep.get("endpoint_id", "<unnamed>")
        for key in ("statement", "numerator", "denominator", "region_scope",
                    "mouse_level_column", "aggregation", "validation"):
            if key not in ep:
                errs.append("endpoint %s is missing '%s'" % (eid, key))
        if (ep.get("aggregation") or {}).get("recompute_from_pooled") is not True:
            errs.append("endpoint %s must set aggregation.recompute_from_pooled = true" % eid)
        rs = ep.get("region_scope") or {}
        if rs.get("mode") == "partitioned_damaged":
            if "damaged" not in str(rs.get("endpoint_roi_name", "")).lower() and \
               "intact" not in str(rs.get("endpoint_roi_name", "")).lower():
                errs.append(
                    "endpoint %s uses partitioned_damaged but endpoint_roi_name %r contains neither "
                    "'damaged' nor 'intact'. aggregate_tiles_to_slide.py:225-226 selects rows by those "
                    "literal substrings; any other name silently makes the denominator the whole tile."
                    % (eid, rs.get("endpoint_roi_name")))
        for dep in ep.get("depends_on_parameters") or []:
            if dep not in params:
                errs.append("endpoint %s depends on unknown parameter %r" % (eid, dep))

    # Controls must state their blind spot.
    for c in profile["controls"]:
        if not (c.get("does_not_control_for") or []):
            errs.append("control %s must list does_not_control_for (non-empty)" % c.get("control_id"))

    # Timepoints: condition tokens must be unique and declared.
    seen = set()
    for tp in profile["timepoints"]:
        tok = tp.get("condition_token")
        if tok in seen:
            errs.append("duplicate condition_token %r across timepoints" % tok)
        seen.add(tok)
    declared = params.get("condition_tokens", {}).get("value")
    if isinstance(declared, str):
        allowed = set(declared.split("|"))
        for tp in profile["timepoints"]:
            if tp.get("condition_token") not in allowed:
                errs.append("timepoint %r uses condition_token %r which is not in "
                            "parameters.condition_tokens (%r)"
                            % (tp.get("label"), tp.get("condition_token"), declared))
    return errs


def semantic_check(profile, registry):
    """Cross-checks that need the engine's real behaviour. -> (errors, warnings)"""
    errs, warns = [], []
    params = profile["parameters"]

    def pval(pid):
        return (params.get(pid) or {}).get("value")

    # 1. Panel key must agree between stage 1 and stage 2.
    if "panel_key" in params and "wsi_panel_key" in params:
        if pval("panel_key") != pval("wsi_panel_key"):
            errs.append("panel_key (%r) != wsi_panel_key (%r). Stage 1 writes the panel into the "
                        "per-tile samplesheet, and `panel` is an aggregate_to_mouse KEY_COL, so a "
                        "mismatch splits one mouse into two rows."
                        % (pval("panel_key"), pval("wsi_panel_key")))

    # 2. IFQ_WSI_AGER_CHANNEL is 0-based; panel idx is 1-based.
    panel = pval("panel_key")
    ch = pval("wsi_ager_channel")
    if ch is not None and panel in BUILTIN_PANEL_CHANNELS:
        idx = BUILTIN_PANEL_CHANNELS[panel].get("AGER")
        if idx is not None and int(ch) != idx - 1:
            errs.append("wsi_ager_channel=%s but panel %s puts AGER at 1-based idx %d. "
                        "IFQ_WSI_AGER_CHANNEL is a 0-BASED raster band index "
                        "(qupath_wsi_tile_export.groovy:595-601), so it must be %d."
                        % (ch, panel, idx, idx - 1))

    # 3. Partitioning requires an explicit AGER threshold and the load-bearing ROI names.
    if pval("wsi_partition_damage") is True:
        if pval("wsi_ager_threshold") in (None, ""):
            errs.append("wsi_partition_damage=true requires a non-null wsi_ager_threshold. "
                        "Stage 1 refuses to partition without it, by design "
                        "(qupath_wsi_tile_export.groovy:229-239).")
        dmg = str(pval("wsi_roi_name_damaged") or "")
        itc = str(pval("wsi_roi_name_intact") or "")
        if "damaged" not in dmg.lower():
            errs.append("wsi_roi_name_damaged=%r must contain the substring 'damaged'; "
                        "aggregate_tiles_to_slide.py:225 selects endpoint rows with it." % dmg)
        if "intact" not in itc.lower():
            errs.append("wsi_roi_name_intact=%r must contain the substring 'intact'; "
                        "aggregate_tiles_to_slide.py:226 uses it for the specificity QC readout." % itc)
        if str(pval("wsi_roi_compartment") or "").strip():
            warns.append("wsi_roi_compartment is set to %r. That prefixes every ROI name and asserts "
                         "the anatomical claim. Only do this once conducting airways are actually "
                         "excluded." % pval("wsi_roi_compartment"))

    # 4. Nucleus floor.
    if "min_included_nuclei" in params and pval("min_included_nuclei") not in (0, "0"):
        warns.append("min_included_nuclei is %r, not 0. A region with zero accepted nuclei throws "
                     "inside the region loop and kills the WHOLE image, endpoint row included "
                     "(docs/ECTOPIC_POD_ENDPOINT.md:269-270)." % pval("min_included_nuclei"))

    # 5. Env name surface + marker token resolution.
    for pid, p in params.items():
        cb = p.get("consumed_by") or {}
        if cb.get("kind") != "env":
            continue
        name = cb.get("env")
        stage, kind, detail = classify_env_name(name, registry)
        if kind == "unknown":
            errs.append("parameter %s emits %s, which is NOT part of the engine's environment "
                        "surface. The engine ignores unknown IFQ_* names silently, so this would "
                        "be a no-op that looks like a configured run." % (pid, name))
        elif kind == "unknown_marker":
            errs.append("parameter %s emits %s: %s" % (pid, name, detail))
        elif stage and cb.get("stage") and stage != cb.get("stage"):
            errs.append("parameter %s declares stage=%s but %s belongs to %s"
                        % (pid, cb.get("stage"), name, stage))
        if kind == "marker_family":
            for suffix in CONDITIONAL_MARKER_SUFFIXES:
                if name.endswith(suffix):
                    warns.append("%s (parameter %s) only takes effect if the marker's resolved "
                                 "morphology rule already carries that key, i.e. role nuc_marker or "
                                 "nuc_ratio. With any other role the engine skips it silently "
                                 "(IF_Quant_Pipeline.groovy:906-911)." % (name, pid))

    # 6. Registry markers must resolve.
    req = profile["registry_requirement"]
    if registry is not None:
        for m in req.get("markers_required") or []:
            if normalize_marker_token(m) not in registry["index"]:
                errs.append("registry_requirement.markers_required contains %r, which does not "
                            "resolve in the loaded registry. Add the marker to the registry first; "
                            "a profile must never define one." % m)
        want = req.get("min_schema_version")
        have = registry.get("schema_version")
        if want and have and tuple(int(x) for x in str(have).split(".")) < \
                tuple(int(x) for x in str(want).split(".")):
            errs.append("registry schema_version %s < required %s" % (have, want))
        for rp in req.get("research_profile_refs") or []:
            if rp not in registry["research_profiles"]:
                warns.append("research_profile_ref %r is not in the registry's research_profiles %s"
                             % (rp, registry["research_profiles"]))
    else:
        warns.append("No marker registry loaded; marker names and env tokens were not resolved. "
                     "Pass --registry to enable that check.")

    # 7. Endpoint-critical parameters that cannot actually be set.
    for pid, p in params.items():
        if p.get("endpoint_critical") and (p.get("consumed_by") or {}).get("kind") == "downstream":
            warns.append("parameter %s is endpoint_critical but consumed_by.kind=downstream: it "
                         "cannot be set from this profile. It is recorded in provenance only." % pid)
        if p.get("endpoint_critical") and p.get("value") is None:
            warns.append("parameter %s is endpoint_critical and its value is null. The generator "
                         "emits nothing for it, so the engine falls back to its own default." % pid)
    return errs, warns


# ---------------------------------------------------------------------------
def endpoint_critical_ids(profile):
    ids = {pid for pid, p in profile["parameters"].items() if p.get("endpoint_critical")}
    ids |= set(profile["primary_endpoint"].get("depends_on_parameters") or [])
    return sorted(i for i in ids if i in profile["parameters"])


def compute_status(profile):
    """
    Weakest link over endpoint-critical parameters.

    Parameters carrying `not_yet_implemented` are held aside: no code path applies
    them, so scoring them would block every pilot. They are reported separately
    and are an unconditional blocker for tier=confirmatory.
    """
    weakest, weakest_rank, ladder, deferred = None, None, [], []
    for pid in endpoint_critical_ids(profile):
        p = profile["parameters"][pid]
        status = ((p.get("validation") or {}).get("status"))
        rank = STATUS_RANK.get(status)
        if p.get("not_yet_implemented"):
            deferred.append((pid, status, p["not_yet_implemented"].get("tracked_by_confound")))
            continue
        if rank is None:          # not_applicable / held_out_readout are outside the ladder
            continue
        ladder.append((pid, status, rank))
        if weakest_rank is None or rank < weakest_rank:
            weakest, weakest_rank = (pid, status), rank
    if weakest_rank is None:
        return {"computed_status": "not_applicable", "computed_rank": None,
                "weakest_parameter": None, "ladder": ladder, "deferred": deferred}
    return {"computed_status": weakest[1], "computed_rank": weakest_rank,
            "weakest_parameter": weakest[0], "ladder": ladder, "deferred": deferred}


def gate(profile, tier, allow_unvalidated):
    """-> (blockers, notes, status_info)"""
    info = compute_status(profile)
    blockers, notes = [], []

    authored = ((profile.get("profile_validation") or {}).get("status"))
    a_rank = STATUS_RANK.get(authored)
    if a_rank is not None and info["computed_rank"] is not None and a_rank > info["computed_rank"]:
        blockers.append(
            "profile_validation.status is %r (rank %d) but the weakest endpoint-critical parameter "
            "is %r at %r (rank %d). A profile may not claim a stronger validation status than its "
            "weakest load-bearing parameter."
            % (authored, a_rank, info["weakest_parameter"], info["computed_status"], info["computed_rank"]))

    need = TIER_MIN_RANK[tier]
    have = info["computed_rank"] if info["computed_rank"] is not None else 3
    if have < need:
        msg = ("tier=%s needs every endpoint-critical parameter at rank >= %d; the weakest is %r at "
               "%r (rank %d)." % (tier, need, info["weakest_parameter"], info["computed_status"], have))
        if tier == "exploratory" and allow_unvalidated:
            notes.append("OVERRIDE (--allow-unvalidated): " + msg)
        else:
            blockers.append(msg)

    if tier == "confirmatory":
        for pid, status, cf_id in info["deferred"]:
            blockers.append("endpoint-critical parameter %r is declared not_yet_implemented "
                            "(tracked by confound %r). A confirmatory run measures the endpoint as "
                            "DEFINED, so this cannot be deferred." % (pid, cf_id))
        for cr in profile["calibration_requirements"]:
            if cr.get("must_be_frozen_before") in ("stage1_partition", "stage2_batch",
                                                   "any_confirmatory_run") \
                    and cr.get("status") != "frozen":
                blockers.append("calibration requirement %r is %r, not 'frozen'."
                                % (cr.get("requirement_id"), cr.get("status")))
        for cf in profile["confounds"]:
            if cf.get("blocking") and cf.get("status") in ("open", "assay_planned",
                                                           "assay_done_unresolved"):
                blockers.append("blocking confound %r is %r; the orthogonal assay is: %s"
                                % (cf.get("confound_id"), cf.get("status"),
                                   cf.get("orthogonal_assay_required")))
    else:
        for cf in profile["confounds"]:
            if cf.get("blocking") and cf.get("status") != "resolved":
                notes.append("BLOCKING CONFOUND OPEN: %s -- %s (needs: %s)"
                             % (cf.get("confound_id"), cf.get("mechanism"),
                                cf.get("orthogonal_assay_required")))
    return blockers, notes, info


# ---------------------------------------------------------------------------
def collect_env(profile, stage):
    """-> ordered list of (name, value, parameter_id, status); skips null values."""
    out = []
    for pid, p in profile["parameters"].items():
        cb = p.get("consumed_by") or {}
        if cb.get("kind") != "env" or cb.get("stage") != stage:
            continue
        if p.get("value") is None:
            continue
        out.append((cb["env"], fmt_env_value(p["value"]), pid,
                    (p.get("validation") or {}).get("status")))
    return sorted(out)


def write_env_files(outdir, stage, pairs, extra, header_lines):
    ps1 = os.path.join(outdir, "%s_env.ps1" % stage)
    sh = os.path.join(outdir, "%s_env.sh" % stage)
    with open(ps1, "w", encoding="utf-8", newline="\n") as f:
        for line in header_lines:
            f.write("# %s\n" % line)
        f.write("\n")
        for name, value, pid, status in pairs:
            f.write("$env:%s = '%s'   # %s [%s]\n" % (name, value, pid, status))
        for name, value in extra:
            f.write("$env:%s = '%s'   # supplied at generation time\n" % (name, value))
    with open(sh, "w", encoding="utf-8", newline="\n") as f:
        f.write("#!/usr/bin/env bash\n")
        for line in header_lines:
            f.write("# %s\n" % line)
        f.write("\n")
        for name, value, pid, status in pairs:
            f.write("export %s='%s'   # %s [%s]\n" % (name, value, pid, status))
        for name, value in extra:
            f.write("export %s='%s'   # supplied at generation time\n" % (name, value))
    return ps1, sh


def samplesheet_contract(profile):
    params = profile["parameters"]

    def tokens(pid):
        v = (params.get(pid) or {}).get("value")
        return v.split("|") if isinstance(v, str) and v else []

    panels = sorted({p["panel_key"] for p in profile["panels"]})
    return {
        "columns": SAMPLESHEET_COLUMNS,
        "required_columns": ["filename", "mouse_id", "section_id", "genotype", "condition", "panel"],
        "key_cols_downstream": AGG_KEY_COLS,
        "allowed_genotype": tokens("genotype_tokens"),
        "allowed_condition": tokens("condition_tokens"),
        "allowed_panel": panels,
        "rules": [
            "mouse_id must never be NA/blank/UNKNOWN; aggregate_to_mouse.py exits on it (lines 76-85).",
            "One mouse_id must map to exactly one (genotype, condition); the aggregator exits on a conflict (lines 87-95).",
            "Timepoint belongs INSIDE the condition token. There is no timepoint column and adding one would fork the grouping.",
            "Token case and spelling are load-bearing: 'het' and 'Het' become two different groups.",
            "(image|output_key, region, section_id, panel) must be unique across all rows (lines 97-111).",
        ],
    }


def validate_samplesheet(path, contract):
    errs, warns = [], []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rows = [r for r in csv.DictReader(fh)
                if any((v or "").strip() for v in r.values())]
        header = list(rows[0].keys()) if rows else []
    if not rows:
        return ["samplesheet has no data rows"], []
    for c in contract["required_columns"]:
        if c not in header and not (c == "filename" and "relative_path" in header):
            errs.append("missing column: %s" % c)
    identities = {}
    for i, r in enumerate(rows, start=2):
        mouse = (r.get("mouse_id") or "").strip()
        if mouse.upper() in ("", "NA", "N/A", "UNKNOWN"):
            errs.append("row %d has no usable mouse_id" % i)
        g, c, p = (r.get("genotype") or "").strip(), (r.get("condition") or "").strip(), \
                  (r.get("panel") or "").strip()
        if contract["allowed_genotype"] and g not in contract["allowed_genotype"]:
            errs.append("row %d genotype %r not in %s" % (i, g, contract["allowed_genotype"]))
        if contract["allowed_condition"] and c not in contract["allowed_condition"]:
            errs.append("row %d condition %r not in %s" % (i, c, contract["allowed_condition"]))
        if contract["allowed_panel"] and p not in contract["allowed_panel"]:
            warns.append("row %d panel %r not declared by the profile %s"
                         % (i, p, contract["allowed_panel"]))
        identities.setdefault(mouse, set()).add((g, c))
    for mouse, vals in identities.items():
        if len(vals) > 1:
            errs.append("mouse_id %r maps to multiple (genotype, condition): %s"
                        % (mouse, sorted(vals)))
    return errs, warns


# ---------------------------------------------------------------------------
def render_run_plan(profile, tier, info, blockers, notes, env1, env2, extra1, extra2, contract):
    ep = profile["primary_endpoint"]
    L = []
    A = L.append
    A("# Run plan: %s v%s" % (profile["model_id"], profile["profile_version"]))
    A("")
    A("Generated %s by tools/model_profile_to_run.py. Do not hand-edit; edit the profile and regenerate."
      % datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    A("")
    A("## Validation gate")
    A("")
    A("* requested tier: **%s**" % tier)
    A("* authored profile status: `%s`" % ((profile.get("profile_validation") or {}).get("status")))
    A("* COMPUTED status (weakest endpoint-critical parameter): **`%s`**%s"
      % (info["computed_status"],
         (" via `%s`" % info["weakest_parameter"]) if info["weakest_parameter"] else ""))
    A("")
    if blockers:
        A("### BLOCKED -- nothing was emitted for a run")
        A("")
        for b in blockers:
            A("* %s" % b)
    else:
        A("Gate passed for tier `%s`." % tier)
    A("")
    for n in notes:
        A("> %s" % n)
        A("")
    A("### Endpoint-critical parameter ladder")
    A("")
    A("| parameter | status | rank |")
    A("|---|---|---|")
    for pid, status, rank in sorted(info["ladder"], key=lambda x: (x[2], x[0])):
        A("| `%s` | %s | %d |" % (pid, status, rank))
    A("")
    if info.get("deferred"):
        A("### Endpoint-critical but NOT YET IMPLEMENTED (excluded from the ladder, blocks confirmatory)")
        A("")
        A("| parameter | status | tracked by confound |")
        A("|---|---|---|")
        for pid, status, cf_id in sorted(info["deferred"]):
            A("| `%s` | %s | `%s` |" % (pid, status, cf_id))
        A("")
    A("## Primary endpoint")
    A("")
    A("`%s`" % ep["endpoint_id"])
    A("")
    A("> %s" % ep["statement"])
    A("")
    A("* numerator column: `%s` (SUM)" % ep["numerator"]["engine_column"])
    A("* denominator column: `%s` (SUM), scope `%s`"
      % (ep["denominator"]["engine_column"], (ep.get("region_scope") or {}).get("mode")))
    A("* mouse-level column to report: `%s`" % ep["mouse_level_column"])
    A("* aggregation path: %s" % (ep.get("aggregation") or {}).get("path"))
    A("")
    A("## Stage 1 environment (QuPath WSI tile export)")
    A("")
    A("```powershell")
    for name, value, pid, status in env1:
        A("$env:%s = '%s'   # %s [%s]" % (name, value, pid, status))
    for name, value in extra1:
        A("$env:%s = '%s'" % (name, value))
    A("```")
    A("")
    A("## Stage 2 environment (frozen Fiji engine)")
    A("")
    A("```powershell")
    for name, value, pid, status in env2:
        A("$env:%s = '%s'   # %s [%s]" % (name, value, pid, status))
    for name, value in extra2:
        A("$env:%s = '%s'" % (name, value))
    A("```")
    A("")
    A("`scripts/Invoke-Stage2Sharded.ps1` sets IFQ_INPUT_DIR, IFQ_OUTPUT_DIR, IFQ_PANEL,")
    A("IFQ_SEGMENTER, IFQ_MIN_INCLUDED_NUCLEI and the three threshold parameters itself, and")
    A("inherits everything else from the parent process. So dot-source `stage2_env.ps1` FIRST,")
    A("then call the shard runner, and pass the thresholds it knows about explicitly:")
    A("")
    thr = {n: v for n, v, _, _ in env2}
    A("```powershell")
    A(". .\\stage2_env.ps1")
    A(".\\scripts\\Invoke-Stage2Sharded.ps1 -TilesDir <tiles> -OutputRoot <out> -Shards 5 `")
    A("    -Panel '%s'%s%s%s" % (
        thr.get("IFQ_PANEL", "LEFT"),
        (" -Krt5Threshold %s" % thr["IFQ_KRT5_THRESHOLD"]) if "IFQ_KRT5_THRESHOLD" in thr else "",
        (" -AgerThreshold %s" % thr["IFQ_AGER_THRESHOLD"]) if "IFQ_AGER_THRESHOLD" in thr else "",
        (" -T1aThreshold %s" % thr["IFQ_T1A_THRESHOLD"]) if "IFQ_T1A_THRESHOLD" in thr else ""))
    A("```")
    if "IFQ_KRT5_THRESHOLD" not in thr:
        A("")
        A("> No KRT5 threshold is emitted. The engine will use per-tile adaptive Otsu, which on a")
        A("> background-dominated tile has reported KRT5_pod_area_frac ~0.89. Exploratory only.")
    A("")
    A("## Stage 3 and 4 -- unchanged, and NOT parameterised by this profile")
    A("")
    A("```bash")
    A("python aggregate_tiles_to_slide.py --slide-root <stage1 output root>")
    A("python aggregate_to_mouse.py <slide_summary.csv or run_summary.csv> --outdir ./stats")
    A("```")
    A("")
    A("Report `%s` from `mouse_level_summary.csv` and `n_mice` from `group_level_summary.csv`."
      % ep["mouse_level_column"])
    A("")
    A("## Samplesheet contract")
    A("")
    A("* allowed `genotype`: %s" % (contract["allowed_genotype"] or "<undeclared>"))
    A("* allowed `condition`: %s" % (contract["allowed_condition"] or "<undeclared>"))
    A("* allowed `panel`: %s" % (contract["allowed_panel"] or "<undeclared>"))
    A("")
    for r in contract["rules"]:
        A("* %s" % r)
    A("")
    A("Validate a real sheet with:")
    A("")
    A("```bash")
    A("python tools/model_profile_to_run.py <profile.json> --validate-samplesheet <samplesheet.csv>")
    A("```")
    A("")
    A("## Controls required by this model")
    A("")
    for c in profile["controls"]:
        if not c.get("required"):
            continue
        A("* **%s** (%s) -- controls for: %s"
          % (c["control_id"], c["kind"], "; ".join(c.get("controls_for") or [])))
        A("  * does NOT control for: %s" % "; ".join(c.get("does_not_control_for") or []))
    A("")
    A("## Confounds")
    A("")
    for cf in profile["confounds"]:
        A("* **%s** [%s, %s, bias %s] -- %s"
          % (cf["confound_id"], cf["status"], cf["severity"], cf["direction_of_bias"], cf["mechanism"]))
        A("  * orthogonal assay: %s%s" % (cf["orthogonal_assay_required"],
                                          "  **(BLOCKING)**" if cf.get("blocking") else ""))
    A("")
    A("## Forbidden in this model")
    A("")
    for f in profile["compartment_tags"]["forbidden_uses"]:
        A("* %s" % f)
    for f in profile["aggregation_contract"]["forbidden"]:
        A("* %s" % f)
    A("")
    for k, v in (profile.get("run_plan_hints") or {}).items():
        A("> **%s**: %s" % (k, v))
        A("")
    return "\n".join(L) + "\n"


# ---------------------------------------------------------------------------
def resolve_base_model(profile, base_dir):
    """
    Layered models (e.g. treg_depletion on top of influenza_pr8) import the base
    model's `shared_definition` parameters UNCHANGED. That set is the denominator
    definition, and only the base model may own it. Numerator thresholds and panel
    keys are deliberately NOT inherited: a layered model measures a different
    numerator on a different panel, and pretending otherwise would be worse than
    forking. Any confound referenced by a deferred imported parameter travels with
    it, so the inherited risk stays visible.
    """
    ref = ((profile.get("parameters") or {}).get("base_model_ref") or {}).get("value")
    if not ref:
        return None, []
    model_id = str(ref).split("@")[0]
    version = str(ref).split("@")[1] if "@" in str(ref) else None
    for fname in sorted(os.listdir(base_dir)):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(base_dir, fname)
        try:
            with open(path, encoding="utf-8-sig") as fh:
                doc = json.load(fh)
        except Exception:
            continue
        if doc.get("model_id") != model_id:
            continue
        if version and doc.get("profile_version") != version:
            continue
        base_params = doc.get("parameters") or {}
        shared = {pid: p for pid, p in base_params.items() if p.get("shared_definition")}
        imported, notes = {}, []
        if not shared:
            notes.append("base model %s declares no shared_definition parameters, so nothing was "
                         "inherited. If this model is meant to reuse the base denominator, mark "
                         "those parameters in the BASE profile." % ref)
        for pid, p in shared.items():
            if pid in profile["parameters"]:
                mine = profile["parameters"][pid].get("value")
                if mine is not None and mine != p.get("value"):
                    notes.append("CONFLICT: %s is %r here and %r in base model %s. The base model "
                                 "owns the shared denominator definition; a layered profile may not "
                                 "override it." % (pid, mine, p.get("value"), ref))
                continue
            imported[pid] = p
        # A deferred imported parameter drags its confound along, so the inherited
        # risk cannot be lost in translation.
        have_cf = {c.get("confound_id") for c in (profile.get("confounds") or [])}
        for pid, p in imported.items():
            nyi = p.get("not_yet_implemented") or {}
            cf_id = nyi.get("tracked_by_confound")
            if cf_id and cf_id not in have_cf:
                for cf in doc.get("confounds") or []:
                    if cf.get("confound_id") == cf_id:
                        profile.setdefault("confounds", []).append(cf)
                        have_cf.add(cf_id)
                        notes.append("Inherited blocking confound %r from base model %s together "
                                     "with parameter %r." % (cf_id, ref, pid))
        profile["parameters"].update(imported)
        notes.insert(0, "Imported %d shared_definition parameter(s) from base model %s: %s"
                     % (len(imported), ref, sorted(imported)))
        return path, notes
    return None, ["base_model_ref %r could not be resolved under %s" % (ref, base_dir)]


# ---------------------------------------------------------------------------
def run(args):
    with open(args.profile, encoding="utf-8-sig") as fh:
        profile = json.load(fh)

    registry = load_registry(args.registry)

    base_path, base_notes = (None, [])
    if args.base_dir:
        base_path, base_notes = resolve_base_model(profile, args.base_dir)

    schema_errs = jsonschema_check(profile, args.schema)
    errs = list(schema_errs or [])
    errs += structural_check(profile)
    sem_errs, warns = ([], [])
    if not errs:
        sem_errs, warns = semantic_check(profile, registry)
    errs += sem_errs
    errs += [n for n in base_notes if n.startswith("CONFLICT")]

    print("profile      : %s v%s  (%s)" % (profile.get("model_id"),
                                           profile.get("profile_version"), args.profile))
    print("registry     : %s" % (("%s markers, schema %s" % (registry["n_markers"],
                                                             registry["schema_version"]))
                                 if registry else "NOT LOADED"))
    print("json schema  : %s" % ("checked, %d error(s)" % len(schema_errs)
                                 if schema_errs is not None
                                 else "not checked (jsonschema not installed or --schema missing)"))
    for n in base_notes:
        print("base model   : %s" % n)
    for w in warns:
        print("WARN  %s" % w)
    for e in errs:
        print("ERROR %s" % e)
    if errs:
        print("\n%d structural/semantic error(s). Nothing emitted." % len(errs))
        return 2

    blockers, notes, info = gate(profile, args.tier, args.allow_unvalidated)
    print("computed status: %s%s" % (info["computed_status"],
                                     (" (weakest: %s)" % info["weakest_parameter"])
                                     if info["weakest_parameter"] else ""))
    for n in notes:
        print("NOTE  %s" % n)
    for b in blockers:
        print("BLOCK %s" % b)

    if args.check:
        print("\n--check: no files written. %s" % ("BLOCKED" if blockers else "would emit"))
        return 1 if blockers else 0

    if args.validate_samplesheet:
        contract = samplesheet_contract(profile)
        s_errs, s_warns = validate_samplesheet(args.validate_samplesheet, contract)
        for w in s_warns:
            print("WARN  samplesheet: %s" % w)
        for e in s_errs:
            print("ERROR samplesheet: %s" % e)
        print("samplesheet: %d error(s), %d warning(s)" % (len(s_errs), len(s_warns)))
        return 2 if s_errs else 0

    if blockers:
        print("\nBLOCKED at tier=%s. Nothing emitted." % args.tier)
        return 3

    outdir = args.outdir or "."
    os.makedirs(outdir, exist_ok=True)

    extra1 = [(k, v) for k, v in (("IFQ_WSI_INPUT", args.wsi_input),
                                  ("IFQ_WSI_OUTPUT", args.wsi_output)) if v]
    extra2 = [(k, v) for k, v in (("IFQ_INPUT_DIR", args.input_dir),
                                  ("IFQ_OUTPUT_DIR", args.output_dir),
                                  ("IFQ_MARKER_REGISTRY", args.registry)) if v]
    env1, env2 = collect_env(profile, "stage1"), collect_env(profile, "stage2")
    hdr = ["GENERATED from %s v%s at tier=%s" % (profile["model_id"],
                                                 profile["profile_version"], args.tier),
           "computed validation status: %s" % info["computed_status"],
           "Do not hand-edit. Edit the profile and regenerate."]
    write_env_files(outdir, "stage1", env1, extra1, hdr)
    write_env_files(outdir, "stage2", env2, extra2, hdr)

    contract = samplesheet_contract(profile)
    with open(os.path.join(outdir, "samplesheet_contract.json"), "w", encoding="utf-8") as f:
        json.dump(contract, f, indent=2)
    with open(os.path.join(outdir, "samplesheet_template.csv"), "w", encoding="utf-8",
              newline="") as f:
        w = csv.writer(f)
        w.writerow(SAMPLESHEET_COLUMNS)
        for g in (contract["allowed_genotype"] or ["<genotype>"]):
            for c in (contract["allowed_condition"] or ["<condition>"]):
                w.writerow(["<file>", "<mouse_id>", "<section_id>", g, c,
                            (contract["allowed_panel"] or ["<panel>"])[0], ""])

    provenance = {
        "generated_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "generator": "tools/model_profile_to_run.py",
        "tier": args.tier,
        "allow_unvalidated": bool(args.allow_unvalidated),
        "profile": {
            "path": os.path.abspath(args.profile),
            "sha256": sha256_of(args.profile),
            "model_id": profile["model_id"],
            "profile_version": profile["profile_version"],
            "authored_status": (profile.get("profile_validation") or {}).get("status"),
            "computed_status": info["computed_status"],
            "weakest_endpoint_critical_parameter": info["weakest_parameter"],
        },
        "base_model_profile": base_path,
        "registry": ({"path": os.path.abspath(args.registry),
                      "sha256": sha256_of(args.registry),
                      "schema_version": registry["schema_version"]} if registry else None),
        "primary_endpoint": {
            "endpoint_id": profile["primary_endpoint"]["endpoint_id"],
            "mouse_level_column": profile["primary_endpoint"]["mouse_level_column"],
            "numerator": profile["primary_endpoint"]["numerator"]["engine_column"],
            "denominator": profile["primary_endpoint"]["denominator"]["engine_column"],
            "region_scope": profile["primary_endpoint"]["region_scope"],
        },
        "parameters": {
            pid: {"value": p.get("value"), "units": p.get("units"),
                  "endpoint_critical": bool(p.get("endpoint_critical")),
                  "not_yet_implemented": p.get("not_yet_implemented"),
                  "consumed_by": p.get("consumed_by"),
                  "validation": p.get("validation")}
            for pid, p in profile["parameters"].items()
        },
        "endpoint_critical_ladder": [
            {"parameter": pid, "status": status, "rank": rank}
            for pid, status, rank in sorted(info["ladder"], key=lambda x: (x[2], x[0]))
        ],
        "endpoint_critical_deferred": [
            {"parameter": pid, "status": status, "tracked_by_confound": cf}
            for pid, status, cf in sorted(info.get("deferred") or [])
        ],
        "emitted_env": {"stage1": {n: v for n, v, _, _ in env1} | dict(extra1),
                        "stage2": {n: v for n, v, _, _ in env2} | dict(extra2)},
        "not_emitted_because_null": sorted(
            pid for pid, p in profile["parameters"].items()
            if p.get("value") is None and (p.get("consumed_by") or {}).get("kind") == "env"),
        "frozen_engine_constants_not_settable": {
            "POD_MIN_AREA_UM2": 50.0,
            "POD_BLUR_SIGMA_PX": 2.0,
            "POD_THRESH_METHOD": "Otsu",
            "_source": "IF_Quant_Pipeline.groovy:347-349; no environment override exists",
        },
        "open_blocking_confounds": [c["confound_id"] for c in profile["confounds"]
                                    if c.get("blocking") and c.get("status") != "resolved"],
        "warnings": warns,
        "notes": notes + base_notes,
    }
    with open(os.path.join(outdir, "provenance.json"), "w", encoding="utf-8") as f:
        json.dump(provenance, f, indent=2)

    with open(os.path.join(outdir, "run_plan.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write(render_run_plan(profile, args.tier, info, blockers, notes,
                                env1, env2, extra1, extra2, contract))

    print("\nWrote to %s:" % os.path.abspath(outdir))
    for name in ("stage1_env.ps1", "stage1_env.sh", "stage2_env.ps1", "stage2_env.sh",
                 "samplesheet_contract.json", "samplesheet_template.csv",
                 "provenance.json", "run_plan.md"):
        print("  %s" % name)
    print("\nStage1 vars: %d   Stage2 vars: %d   Suppressed (null): %d"
          % (len(env1) + len(extra1), len(env2) + len(extra2),
             len(provenance["not_emitted_because_null"])))
    return 0


# ---------------------------------------------------------------------------
def selftest():
    """Assertions that pin the behaviour this tool exists to guarantee."""
    ok = True

    def check(label, cond):
        nonlocal ok
        print(("PASS  " if cond else "FAIL  ") + label)
        ok = ok and cond

    check("marker token normalisation matches the engine",
          normalize_marker_token("Ki-67") == "KI67"
          and normalize_marker_token("RED2_KRAS_G12D_RFP") == "RED2KRASG12DRFP"
          and normalize_marker_token("T1alpha") == "T1ALPHA")

    reg = {"index": {"KRT5": "KRT5", "AGER": "AGER"}, "schema_version": "1.3.0",
           "n_markers": 2, "research_profiles": []}
    check("known static stage2 var classified",
          classify_env_name("IFQ_MIN_INCLUDED_NUCLEI", reg)[:2] == ("stage2", "static"))
    check("known static stage1 var classified",
          classify_env_name("IFQ_WSI_DAMAGE_CUTOFF", reg)[:2] == ("stage1", "static"))
    check("marker family accepted for a registry marker",
          classify_env_name("IFQ_KRT5_THRESHOLD", reg)[1] == "marker_family")
    check("typo'd marker token rejected",
          classify_env_name("IFQ_KRT55_THRESHOLD", reg)[1] == "unknown_marker")
    check("wholly unknown var rejected",
          classify_env_name("IFQ_NOT_A_REAL_SETTING", reg)[1] == "unknown")

    check("validation ladder orders pilot_tuned below control_derived",
          STATUS_RANK["pilot_tuned"] < STATUS_RANK["control_derived"] < STATUS_RANK["frozen_blinded_controls"])

    tiny = {
        "profile_schema": PROFILE_SCHEMA_PIN, "model_id": "test_model",
        "profile_version": "1.0.0", "title": "test profile", "species": "mus_musculus",
        "authored": {"author": "t", "date": "2026-01-01", "lab": "t", "notes": "t"},
        "profile_validation": {"status": "frozen_blinded_controls", "evidence": "none"},
        "registry_requirement": {"path_hint": "x", "min_schema_version": "1.3.0",
                                 "markers_required": ["KRT5"]},
        "panels": [{"panel_key": "LEFT", "source": "builtin", "role_in_model": "primary_endpoint",
                    "measures_states": [], "validation": {"status": "control_derived", "evidence": "x"}}],
        "insult": {"agent": "x", "route": "x", "vehicle": "x",
                   "dose": {"value": None, "units": "x", "validation": {"status": "unset", "evidence": "none"}},
                   "validation": {"status": "unset", "evidence": "none"}},
        "timepoints": [{"label": "t", "days_post_insult": 0, "condition_token": "uninfected",
                        "expected": [], "endpoint_evaluable": False,
                        "validation": {"status": "unset", "evidence": "none"}}],
        "cell_states": [{"state_id": "s", "description": "d", "markers": {},
                         "measured_as": "regional_area",
                         "validation": {"status": "unset", "evidence": "none"}}],
        "morphology_signature": {"architecture_expectations": [
            {"feature": "f", "expected_direction": "increase", "scale_um": 40.0,
             "validation": {"status": "unset", "evidence": "none"}}],
            "validation": {"status": "unset", "evidence": "none"}},
        "primary_endpoint": {
            "endpoint_id": "e", "statement": "numerator over denominator",
            "numerator": {"engine_column": "KRT5_pod_area_um2", "description": "d"},
            "denominator": {"engine_column": "region_area_um2", "description": "d"},
            "region_scope": {"mode": "partitioned_damaged",
                             "endpoint_roi_name": "parenchyma_damaged",
                             "contrast_roi_name": "parenchyma_intact",
                             "substring_contract": "damaged"},
            "mouse_level_column": "KRT5_pod_area_frac",
            "aggregation": {"recompute_from_pooled": True, "path": "p"},
            "depends_on_parameters": ["krt5_pos_threshold"],
            "validation": {"status": "control_derived", "evidence": "x"}},
        "controls": [{"control_id": "c", "kind": "biological_negative", "required": True,
                      "controls_for": ["a"], "does_not_control_for": ["b"],
                      "validation": {"status": "unset", "evidence": "none"}}],
        "confounds": [{"confound_id": "cf", "mechanism": "m", "direction_of_bias": "either",
                       "severity": "major", "orthogonal_assay_required": "a",
                       "blocking": True, "status": "open"}],
        "compartment_tags": {"meaningful": ["alveolar"], "not_meaningful": [],
                             "forbidden_uses": ["x"]},
        "calibration_requirements": [{"requirement_id": "r", "parameter_refs": ["krt5_pos_threshold"],
                                      "must_be_frozen_before": "stage2_batch", "control_set": ["c"],
                                      "selection_rule": "s", "status": "in_progress"}],
        "parameters": {
            "krt5_pos_threshold": {"value": 500, "units": "raw", "endpoint_critical": True,
                                   "consumed_by": {"kind": "env", "env": "IFQ_KRT5_THRESHOLD",
                                                   "stage": "stage2"},
                                   "validation": {"status": "control_derived", "evidence": "x"}},
            "condition_tokens": {"value": "uninfected", "units": "none",
                                 "consumed_by": {"kind": "samplesheet", "column": "condition"},
                                 "validation": {"status": "control_derived", "evidence": "x"}},
        },
        "aggregation_contract": {"statistical_n": "mouse", "key_cols": AGG_KEY_COLS,
                                 "row_id_cols": AGG_ROW_ID_COLS,
                                 "path": ["stage2_engine", "stage4_to_mouse"],
                                 "forbidden": ["x"]},
    }
    check("valid minimal profile passes structural check", structural_check(tiny) == [])

    info = compute_status(tiny)
    check("computed status is the weakest endpoint-critical parameter",
          info["computed_status"] == "control_derived" and info["weakest_parameter"] == "krt5_pos_threshold")

    blockers, _, _ = gate(tiny, "exploratory", False)
    check("authored 'frozen' over a control_derived parameter is BLOCKED",
          any("may not claim a stronger validation status" in b for b in blockers))

    tiny["profile_validation"]["status"] = "control_derived"
    blockers, _, _ = gate(tiny, "exploratory", False)
    check("honest profile passes tier=exploratory", blockers == [])
    blockers, _, _ = gate(tiny, "confirmatory", False)
    check("tier=confirmatory blocked by unfrozen parameter, unfrozen calibration and open blocking confound",
          len(blockers) >= 3)

    import copy
    bad = copy.deepcopy(tiny)
    bad["aggregation_contract"]["key_cols"] = AGG_KEY_COLS + ["timepoint"]
    check("adding a KEY_COL is rejected",
          any("key_cols must be exactly" in e for e in structural_check(bad)))

    bad = copy.deepcopy(tiny)
    bad["primary_endpoint"]["region_scope"]["endpoint_roi_name"] = "parenchyma_lesion"
    check("partitioned endpoint ROI without the 'damaged'/'intact' substring is rejected",
          any("selects rows by those literal substrings" in e for e in structural_check(bad)))

    bad = copy.deepcopy(tiny)
    bad["parameters"]["krt5_pos_threshold"]["consumed_by"]["env"] = "IFQ_KRT_5_THRESHOLD"
    _, sem = structural_check(bad), semantic_check(bad, reg)[0]
    check("typo'd env name is rejected", any("does not resolve" in e for e in sem))

    bad = copy.deepcopy(tiny)
    bad["parameters"]["krt5_pos_threshold"]["validation"]["evidence"] = ""
    check("empty evidence is rejected",
          any("empty validation.evidence" in e for e in structural_check(bad)))

    check("null-valued parameters are never emitted",
          collect_env({"parameters": {"x": {"value": None, "consumed_by":
                      {"kind": "env", "env": "IFQ_KRT5_THRESHOLD", "stage": "stage2"},
                      "validation": {"status": "unset"}}}}, "stage2") == [])

    print("\nSELFTEST %s" % ("PASSED" if ok else "FAILED"))
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(
        description="Generate an IFQuant-Lung run configuration from one injury-model profile.")
    ap.add_argument("profile", nargs="?", help="path to a *.model.json profile")
    ap.add_argument("--outdir", default=None)
    ap.add_argument("--tier", choices=["dry", "exploratory", "confirmatory"], default="exploratory")
    ap.add_argument("--allow-unvalidated", action="store_true",
                    help="permit tier=exploratory below the normal floor; stamped into provenance")
    ap.add_argument("--registry", default=os.path.join("config", "lung_marker_registry.json"))
    ap.add_argument("--schema", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                     os.pardir, "schema",
                                                     "injury_model_profile.schema.json"),
                    help="JSON Schema for model profiles; used only when `jsonschema` is installed")
    ap.add_argument("--base-dir", default=None,
                    help="directory of model profiles, used to resolve base_model_ref for layered models")
    ap.add_argument("--input-dir", default=None, help="becomes IFQ_INPUT_DIR")
    ap.add_argument("--output-dir", default=None, help="becomes IFQ_OUTPUT_DIR")
    ap.add_argument("--wsi-input", default=None, help="becomes IFQ_WSI_INPUT")
    ap.add_argument("--wsi-output", default=None, help="becomes IFQ_WSI_OUTPUT")
    ap.add_argument("--check", action="store_true", help="validate and gate only; write nothing")
    ap.add_argument("--validate-samplesheet", default=None)
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if not args.profile:
        ap.error("a profile path is required unless --selftest is given")
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
