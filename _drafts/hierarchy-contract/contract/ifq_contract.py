#!/usr/bin/env python3
"""
ifq_contract.py -- IFQuant-Lung module contract validator  (DRAFT v1.0.0)
=====================================================================
Tell a module author, BEFORE they run an analysis, exactly what
aggregate_to_mouse.py will do with every column they emit:
SUM it, RECOMPUTE a ratio from it, or SILENTLY DROP it.

Why this file exists
--------------------
aggregate_to_mouse.py does not carry columns forward generically. It builds a
closed whitelist of column-name suffixes (classify_columns, lines 114-201),
sums only those, recomputes ~16 named ratios from the pooled sums, and never
reads anything else. A new metric with a name outside that vocabulary does not
aggregate incorrectly -- it VANISHES, with no warning.

This validator does not re-implement that vocabulary. It IMPORTS
classify_columns from the real aggregate_to_mouse.py, so the two can never
drift. If the aggregator is ever edited (it should not be), this tool changes
its answers on the same day.

Read-only. Writes nothing. Exits 1 on a blocking violation, 0 otherwise.

Usage
-----
  python3 contract/ifq_contract.py --csv path/to/module_summary.csv
  python3 contract/ifq_contract.py --csv a.csv --csv b.csv --check-ownership
  python3 contract/ifq_contract.py --csv a.csv --provenance a.provenance.json
  python3 contract/ifq_contract.py --explain morph_airspace_positive_area_um2
  python3 contract/ifq_contract.py --vocabulary

  --repo PATH    where aggregate_to_mouse.py lives (default: parent of this file)
  --strict       treat WARNINGs as blocking too

Standard library only.
=====================================================================
"""
import argparse
import csv
import json
import os
import re
import sys

CONTRACT_VERSION = "1.0.0"

# Scopes a module may declare in panel = "<PANEL_KEY>@<scope>".
KNOWN_SCOPES = {
    "whole_tissue", "parenchyma", "damaged", "intact",
    "airway", "vessel", "tumor", "stroma", "ali_membrane", "registered",
}

# Column-name endings that are DERIVED quantities. A module must never emit
# one: aggregate_to_mouse.py either drops it (usual) or -- worse -- sums it if
# the name also happens to match a count/area suffix.
FORBIDDEN_ENDINGS = (
    "_frac", "_fraction", "_ratio", "_index", "_mean", "_median",
    "_per_mm2", "_per_um2", "_percent", "_pct", "_um", "_score", "_density",
)

# Identity / provenance columns that are legitimately dropped by the aggregator.
ALLOWED_DROPPED = {
    "image", "output_key", "region", "section_id", "compartment", "region_tags",
    "mouse_id", "genotype", "condition", "panel", "slide", "module_id",
    "primary_endpoint_marker", "primary_endpoint_channel",
    "dapi_segmentation_method", "partitioned", "qc_status", "qc_notes",
    "stage1_area_column", "source_vsi", "series_index",
}
ALLOWED_DROPPED_SUFFIXES = (
    "_threshold", "_threshold_source", "_call_authority", "_measurement_model",
    "_is_primary_endpoint", "_candidate_threshold_sensitivity",
)


# --------------------------------------------------------------------------
def load_aggregator(repo):
    """Import the REAL classify_columns so this validator cannot drift from it."""
    agg = os.path.join(repo, "aggregate_to_mouse.py")
    if not os.path.isfile(agg):
        sys.exit("ERROR: aggregate_to_mouse.py not found at %s\n"
                 "       Pass --repo <IFQuant-Lung root>." % agg)
    sys.path.insert(0, repo)
    try:
        from aggregate_to_mouse import (  # noqa: E402
            classify_columns, KEY_COLS, ROW_ID_COLS, _num,
        )
    except ImportError as exc:
        sys.exit("ERROR: could not import aggregate_to_mouse: %s" % exc)
    return classify_columns, list(KEY_COLS), list(ROW_ID_COLS), _num


def fate_of(col, cats, key_cols, row_id_cols):
    """Return (fate, detail) for one column. fate in SUM|DERIVED|IDENTITY|DROPPED."""
    if col in key_cols:
        return "IDENTITY", "grouping key"
    if col in row_id_cols:
        return "IDENTITY", "row identity"
    if col == "output_key":
        return "IDENTITY", "preferred row identity (duplicate check)"
    if col in cats["sum_cols"]:
        for name in ("pos_count", "raw_mean_pos_count", "positive_area",
                     "n_components", "pod_area", "n_pods", "class_count",
                     "nucleus_qc_count", "state_counts"):
            if col in cats.get(name, ()):
                return "SUM", "category=%s" % name
        return "SUM", "category=core"
    return "DROPPED", "no recognised suffix"


def derived_outputs(col, cats):
    """Mouse-level columns aggregate_to_mouse.py will DERIVE from this column."""
    out = []
    if col in cats["pos_count"]:
        m = col[: -len("_pos_count")]
        out += ["%s_pos_count_total" % m, "%s_density_per_mm2" % m]
    if col in cats["positive_area"]:
        m = col[: -len("_positive_area_um2")]
        out += ["%s_positive_area_um2_total" % m,
                "%s_positive_area_fraction  (= /sum(region_area_um2))" % m,
                "%s_mean_component_area_um2  (needs %s_n_components)" % (m, m)]
    if col in cats["pod_area"]:
        m = col[: -len("_pod_area_um2")]
        out += ["%s_pod_area_um2_total" % m,
                "%s_pod_area_frac  (= /sum(region_area_um2))" % m,
                "%s_mean_pod_area_um2  (needs %s_n_pods)" % (m, m)]
    if col in cats["class_count"]:
        b = col[: -len("_count")]
        out += ["%s_count_total" % b, "%s_density_per_mm2" % b]
    if col == "region_area_um2":
        out += ["total_tissue_area_um2  -- THE DENOMINATOR of every area fraction"]
    if col == "n_nuclei":
        out += ["total_nuclei  -- the denominator of every *_fraction_of_total_cells"]
    return out


# --------------------------------------------------------------------------
def check_csv(path, classify_columns, key_cols, row_id_cols, numparse):
    """Validate one module CSV. Returns (errors, warnings, info, rows, header)."""
    errors, warnings, info = [], [], []
    with open(path, newline="", encoding="utf-8-sig") as fh:
        reader = csv.DictReader(fh)
        header = list(reader.fieldnames or [])
        rows = [r for r in reader if any((v or "").strip() for v in r.values())]
    if not header:
        return ["%s: empty or headerless" % path], [], [], [], []
    if not rows:
        errors.append("%s: no data rows" % path)

    cats = classify_columns(header)

    # ---- 1. required columns (aggregate_to_mouse.py:71) -------------------
    missing = [c for c in key_cols + row_id_cols if c not in header]
    if missing:
        errors.append("missing required column(s): %s  "
                      "(aggregate_to_mouse.validate_rows would sys.exit)"
                      % ", ".join(missing))
    if "output_key" not in header:
        warnings.append("no 'output_key' column: rows will be keyed on 'image' "
                        "(aggregate_to_mouse.py:97) and can collide with engine rows "
                        "in a merged CSV")

    # ---- 2. denominator ---------------------------------------------------
    owns_area = "region_area_um2" in header
    if not owns_area:
        info.append("does not emit region_area_um2 -> contributes NUMERATORS only; "
                    "another producer must own the denominator for this panel scope")

    # ---- 3. column fates --------------------------------------------------
    fates = {}
    for col in header:
        fate, detail = fate_of(col, cats, key_cols, row_id_cols)
        fates[col] = (fate, detail)
        if fate != "DROPPED":
            continue
        if col in ALLOWED_DROPPED or col.endswith(ALLOWED_DROPPED_SUFFIXES):
            continue
        numeric = any(numparse(r.get(col)) is not None for r in rows[:200])
        if numeric:
            errors.append(
                "column '%s' is NUMERIC but matches no aggregation suffix -- "
                "aggregate_to_mouse.py will SILENTLY DROP it. Rename to a "
                "recognised suffix (see --vocabulary) or delete it." % col)
        else:
            warnings.append("column '%s' is dropped at mouse level (non-numeric, "
                            "no declared identity role)" % col)

    # ---- 4. forbidden derived names --------------------------------------
    for col in header:
        low = col.lower()
        if low.endswith(FORBIDDEN_ENDINGS) and fates[col][0] == "SUM":
            errors.append(
                "column '%s' looks DERIVED but lands in sum_cols -- it will be "
                "SUMMED across regions, which is arithmetically wrong. This is the "
                "one way a module can produce a silently incorrect number." % col)
        elif low.endswith(FORBIDDEN_ENDINGS) and col not in ALLOWED_DROPPED:
            warnings.append("column '%s' looks like a ratio/mean. Ratios must be "
                            "declared in config/endpoints/, not emitted as columns."
                            % col)

    # ---- 5. panel@scope ---------------------------------------------------
    scopes_seen = {}
    for r in rows:
        panel = (r.get("panel") or "").strip()
        if "@" not in panel:
            warnings.append("panel '%s' has no '@<denominator_scope>' suffix; the "
                            "denominator this row's fractions use is undeclared" % panel)
            continue
        base, _, scope = panel.rpartition("@")
        if scope not in KNOWN_SCOPES:
            warnings.append("panel scope '%s' is not in KNOWN_SCOPES %s"
                            % (scope, sorted(KNOWN_SCOPES)))
        scopes_seen.setdefault(panel, 0)
        scopes_seen[panel] += 1

    # ---- 6. duplicate rows (mirror aggregate_to_mouse.py:97-111) ---------
    ident = "output_key" if "output_key" in header else "image"
    seen, dups = set(), []
    for r in rows:
        k = tuple((r.get(c) or "").strip() for c in [ident, "region", "section_id", "panel"])
        if k in seen:
            dups.append(k)
        seen.add(k)
    if dups:
        errors.append("%d duplicate (%s, region, section_id, panel) row(s); "
                      "aggregate_to_mouse.py would sys.exit. First: %s"
                      % (len(dups), ident, dups[0]))

    # ---- 7. mouse identity guards ----------------------------------------
    bad = [r for r in rows
           if (r.get("mouse_id") or "").strip().upper() in {"", "NA", "N/A", "UNKNOWN"}]
    if bad:
        errors.append("%d row(s) have an unusable mouse_id (aggregate_to_mouse.py:76)"
                      % len(bad))
    ident_map = {}
    for r in rows:
        ident_map.setdefault((r.get("mouse_id") or "").strip(), set()).add(
            ((r.get("genotype") or "NA").strip(), (r.get("condition") or "NA").strip()))
    for m, vals in ident_map.items():
        if len(vals) > 1:
            errors.append("mouse_id '%s' maps to multiple genotype/condition "
                          "identities %s (aggregate_to_mouse.py:87)" % (m, sorted(vals)))

    # ---- 8. scope homogeneity of the denominator -------------------------
    if owns_area:
        by_group = {}
        for r in rows:
            g = tuple((r.get(c) or "NA").strip() for c in key_cols)
            v = numparse(r.get("region_area_um2"))
            if v is not None:
                by_group.setdefault(g, []).append(v)
        for g, vals in by_group.items():
            if any(v <= 0 for v in vals):
                warnings.append("group %s has a non-positive region_area_um2; every "
                                "fraction for that mouse will be forced to 0.0" % (g,))

    return errors, warnings, info, rows, header


def check_ownership(csv_paths, classify_columns, key_cols):
    """Exactly one producer may own region_area_um2 per (mouse, panel@scope)."""
    owners = {}
    problems = []
    for p in csv_paths:
        with open(p, newline="", encoding="utf-8-sig") as fh:
            reader = csv.DictReader(fh)
            header = list(reader.fieldnames or [])
            if "region_area_um2" not in header:
                continue
            for r in reader:
                if not (r.get("region_area_um2") or "").strip():
                    continue
                g = tuple((r.get(c) or "NA").strip() for c in key_cols)
                owners.setdefault(g, set()).add(os.path.basename(p))
    for g, srcs in sorted(owners.items()):
        if len(srcs) > 1:
            problems.append(
                "DOUBLE-COUNTED DENOMINATOR: group %s receives region_area_um2 from "
                "%d producers %s. Every area fraction for that mouse is wrong by the "
                "ratio of the areas." % (g, len(srcs), sorted(srcs)))
    return problems


def check_provenance(path, schema_path=None):
    errors = []
    try:
        with open(path, encoding="utf-8") as fh:
            p = json.load(fh)
    except Exception as exc:                                    # noqa: BLE001
        return ["provenance %s unreadable: %s" % (path, exc)]

    required = {
        "provenance_schema_version": str,
        "module": dict, "run": dict, "software": dict,
        "inputs": list, "parameters": dict, "outputs": list,
        "qc": dict, "contract": dict, "statistics": dict,
    }
    for k, t in required.items():
        if k not in p:
            errors.append("provenance: missing top-level '%s'" % k)
        elif not isinstance(p[k], t):
            errors.append("provenance: '%s' must be %s" % (k, t.__name__))
    if errors:
        return errors

    for k in ("module_id", "module_version", "level", "panel_scope",
              "owns_region_area_um2"):
        if k not in p["module"]:
            errors.append("provenance.module: missing '%s'" % k)
    if p["module"].get("level") not in {"region", "tile", "slide", "mouse", None}:
        errors.append("provenance.module.level must be region|tile|slide")

    if not p["software"].get("runtimes"):
        errors.append("provenance.software.runtimes is empty -- a methods section "
                      "cannot be written without software versions")
    for k in ("ifquant_repo_commit", "contract_version"):
        if k not in p["software"]:
            errors.append("provenance.software: missing '%s'" % k)

    for i, inp in enumerate(p["inputs"]):
        if "path" not in inp:
            errors.append("provenance.inputs[%d]: missing 'path'" % i)
        if inp.get("sha256") is None and not inp.get("sha256_skipped_reason"):
            errors.append("provenance.inputs[%d] (%s): sha256 is null without a "
                          "'sha256_skipped_reason'" % (i, inp.get("path", "?")))

    locked = p["parameters"].get("locked", {})
    src = p["parameters"].get("lock_source", {})
    for k in locked:
        if k not in src:
            errors.append("provenance.parameters: locked value '%s' has no entry in "
                          "lock_source -- its calibration is unciteable" % k)
    if locked and "calibration" not in p["parameters"]:
        errors.append("provenance.parameters: locked values present but no "
                      "'calibration' block (calibrated_on / criterion / locked_utc)")

    if p["statistics"].get("n_definition") != "mouse_id":
        errors.append("provenance.statistics.n_definition must be 'mouse_id'. "
                      "n = MICE, never sections/slides/tiles.")

    ar = p.get("qc", {}).get("area_reconciliation")
    if ar and ar.get("rel_diff") is not None and ar.get("tolerance") is not None:
        if ar["rel_diff"] > ar["tolerance"] and ar.get("passed"):
            errors.append("provenance.qc.area_reconciliation: rel_diff %.4f exceeds "
                          "tolerance %.4f but passed=true"
                          % (ar["rel_diff"], ar["tolerance"]))
    return errors


VOCAB = """
RECOGNISED COLUMN VOCABULARY  (source: aggregate_to_mouse.classify_columns)

  SUMMED, exact name
    region_area_um2                     THE denominator of every area fraction
    n_nuclei                            denominator of *_fraction_of_total_cells
    n_rejected_nucleus_candidates  n_rejected_below_min_area
    n_rejected_at_image_edge       n_rejected_by_particle_filter
    n_nucleus_candidates_total

  SUMMED, by suffix        ->  MOUSE-LEVEL COLUMNS YOU GET FREE
    <N>_pos_count          ->  <N>_pos_count_total, <N>_density_per_mm2
    <N>_positive_area_um2  ->  <N>_positive_area_um2_total,
                               <N>_positive_area_fraction,
                               <N>_mean_component_area_um2
    <N>_n_components       ->  <N>_n_components_total
    <N>_pod_area_um2       ->  <N>_pod_area_um2_total, <N>_pod_area_frac,
                               <N>_mean_pod_area_um2
    <N>_n_pods             ->  <N>_n_pods_total
    class_<L>_count        ->  class_<L>_count_total, class_<L>_density_per_mm2
    class_<L>_evaluable_count, class_<L>_indeterminate_count
    <M>_morphology_pos_count / _morphology_negative_count /
      _morphology_evaluable_count / _indeterminate_count
    <M>_final_positive_cell_count / _final_negative_cell_count /
      _final_indeterminate_cell_count
    <M>_context_resolved_positive_count / _context_resolved_evaluable_count /
      _context_unresolved_positive_count / _marker_evidence_pos_count
    <M>_raw_mean_pos_count
    <M>_raw_positive_final_negative_count / _raw_negative_final_positive_count /
      _intensity_morphology_discordant_count / _review_burden_proxy_count

  TRAPS
    <N>_mean_pod_area_um2   EXCLUDED from pod_area   (aggregate_to_mouse.py:169)
    <N>_true_pos_count      EXCLUDED from pos_count and added to NOTHING -> dropped
    class_*_indeterminate_count is a CLASS column, not a marker column
    a bare <N>_area_um2 (not _positive_ / not _pod_) is DROPPED
    region_area_um2 and n_nuclei match by EXACT NAME only

  ANYTHING ELSE IS SILENTLY DROPPED. Ratios, means, indices and thresholds must
  be declared in config/endpoints/*.json, never emitted as columns.
"""


def main():
    ap = argparse.ArgumentParser(description="Validate a module CSV against the "
                                             "IFQuant-Lung module contract.")
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--repo", default=os.path.dirname(here),
                    help="IFQuant-Lung root holding aggregate_to_mouse.py")
    ap.add_argument("--csv", action="append", default=[], help="module CSV (repeatable)")
    ap.add_argument("--provenance", action="append", default=[],
                    help="provenance sidecar JSON (repeatable)")
    ap.add_argument("--check-ownership", action="store_true",
                    help="verify only one producer owns region_area_um2 per group")
    ap.add_argument("--explain", default=None, help="explain one column name")
    ap.add_argument("--vocabulary", action="store_true", help="print the naming vocabulary")
    ap.add_argument("--strict", action="store_true", help="warnings are blocking too")
    args = ap.parse_args()

    if args.vocabulary:
        print(VOCAB)
        return 0

    classify_columns, key_cols, row_id_cols, numparse = load_aggregator(args.repo)

    if args.explain:
        col = args.explain
        cats = classify_columns([col, "region_area_um2", "n_nuclei"])
        fate, detail = fate_of(col, cats, key_cols, row_id_cols)
        print("column : %s" % col)
        print("fate   : %s  (%s)" % (fate, detail))
        for d in derived_outputs(col, cats):
            print("derives: %s" % d)
        if fate == "DROPPED" and col not in ALLOWED_DROPPED:
            print("WARNING: aggregate_to_mouse.py will not read this column at all.")
            print("         Run --vocabulary for the recognised suffixes.")
        return 0

    if not args.csv and not args.provenance:
        ap.error("give at least one --csv or --provenance (or --vocabulary/--explain)")

    all_err, all_warn = [], []
    for path in args.csv:
        print("=" * 72)
        print("CSV: %s" % path)
        e, w, i, rows, header = check_csv(path, classify_columns, key_cols,
                                          row_id_cols, numparse)
        cats = classify_columns(header) if header else {"sum_cols": set()}
        n_sum = sum(1 for c in header if c in cats["sum_cols"])
        n_id = sum(1 for c in header
                   if fate_of(c, cats, key_cols, row_id_cols)[0] == "IDENTITY")
        print("  %d columns: %d summed, %d identity, %d dropped, %d rows"
              % (len(header), n_sum, n_id, len(header) - n_sum - n_id, len(rows)))
        for m in i:
            print("  INFO    %s" % m)
        for m in w:
            print("  WARNING %s" % m)
        for m in e:
            print("  ERROR   %s" % m)
        all_err += ["%s: %s" % (path, m) for m in e]
        all_warn += ["%s: %s" % (path, m) for m in w]

    if args.check_ownership and len(args.csv) > 1:
        print("=" * 72)
        print("DENOMINATOR OWNERSHIP across %d file(s)" % len(args.csv))
        probs = check_ownership(args.csv, classify_columns, key_cols)
        for m in probs:
            print("  ERROR   %s" % m)
        if not probs:
            print("  ok: no group receives region_area_um2 from two producers")
        all_err += probs

    for path in args.provenance:
        print("=" * 72)
        print("PROVENANCE: %s" % path)
        e = check_provenance(path)
        for m in e:
            print("  ERROR   %s" % m)
        if not e:
            print("  ok")
        all_err += ["%s: %s" % (path, m) for m in e]

    print("=" * 72)
    blocking = all_err + (all_warn if args.strict else [])
    if blocking:
        print("CONTRACT v%s: FAIL -- %d blocking, %d warning"
              % (CONTRACT_VERSION, len(all_err), len(all_warn)))
        return 1
    print("CONTRACT v%s: PASS -- %d warning(s)" % (CONTRACT_VERSION, len(all_warn)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
