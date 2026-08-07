#!/usr/bin/env python3
"""
ifq_module_contract.py
=====================================================================
PREFLIGHT GATE for any IFQuant-Lung module output that will be fed to
aggregate_to_mouse.py.

Why this exists
---------------
aggregate_to_mouse.py has an ALLOW-LIST pooling model
(aggregate_to_mouse.py:114-201). A column whose name matches one of a fixed set
of suffixes is SUMMED; every other measurement column is SILENTLY DISCARDED,
and the derived quantities (fractions, densities, mean object sizes) are MINTED
FRESH from the pooled sums (aggregate_to_mouse.py:256-338).

Two consequences kill new modules quietly:

  1. A natural morphometry / spatial column name -- MLI_um,
     septal_thickness_um, krt5_ager_nn_distance_um -- matches nothing and
     VANISHES. No warning, no error, no column in mouse_level_summary.csv.

  2. aggregate_mice() groups ONLY on (mouse_id, genotype, condition, panel)
     (KEY_COLS, aggregate_to_mouse.py:43) and then SUMS region_area_um2 over
     every row in the group. `region` and `section_id` are NOT grouping keys --
     they only feed the duplicate check (aggregate_to_mouse.py:97-111).
     So a slide-level module that appends a row with the SAME panel and a
     different `region` silently adds its own tissue area to the endpoint
     DENOMINATOR. Measured here: appending one whole-tissue row
     (75 mm2) to one damaged-parenchyma row (1 mm2) diluted
     KRT5_pod_area_frac by 76x, with no error raised anywhere.

This script refuses both failure modes BEFORE the data reaches
aggregate_to_mouse.py.

It imports classify_columns from aggregate_to_mouse itself, so the definition
of "summable" can never drift from the aggregator. That import is the same
anti-fork device aggregate_tiles_to_slide.py uses (lines 67-74).

Usage
-----
  python ifq_module_contract.py module_rows.csv
  python ifq_module_contract.py module_rows.csv --provenance module_provenance.json
  python ifq_module_contract.py slide_level_summary.csv --strict

Exit codes: 0 clean (warnings allowed), 1 contract violation, 2 bad invocation.
No third-party dependencies (standard library only).
=====================================================================
"""
import argparse
import csv
import json
import os
import sys
from collections import defaultdict

# --- locate the ONE aggregator ------------------------------------------------
# Default: this file sits in <repo>/contract/, aggregate_to_mouse.py at <repo>/.
_HERE = os.path.dirname(os.path.abspath(__file__))
for _cand in (os.environ.get("IFQ_REPO_ROOT", ""), os.path.dirname(_HERE),
              _HERE, os.getcwd()):
    if not _cand:
        continue
    if os.path.isfile(os.path.join(_cand, "aggregate_to_mouse.py")):
        sys.path.insert(0, _cand)
        break
try:
    from aggregate_to_mouse import KEY_COLS, ROW_ID_COLS, classify_columns, _num
except ImportError:
    sys.exit("ERROR: cannot import aggregate_to_mouse.py. Put this script in "
             "<repo>/contract/ or run it from the repo root. The contract is "
             "DEFINED by that file; this checker must not restate it.")

CONTRACT_VERSION = "1.0.0"

# Columns aggregate_mice() MINTS. A module must never write these itself: the
# value it writes is discarded and replaced (aggregate_to_mouse.py:256-338).
MINTED_SUFFIXES = (
    "_density_per_mm2", "_pod_area_frac", "_positive_area_fraction",
    "_mean_pod_area_um2", "_mean_component_area_um2",
    "_fraction_of_total_cells", "_fraction_of_evaluable",
    "_fraction_of_included", "_fraction_of_rejected",
    "_context_resolved_positive_fraction",
    "nucleus_candidate_acceptance_fraction",
    "nucleus_candidate_rejection_fraction",
)
# Anything that looks like a pre-computed statistic. These cannot survive
# pooling and must be expressed as numerator + denominator instead.
DERIVED_LOOKING = ("_frac", "_fraction", "_ratio", "_index", "_per_mm2",
                   "_per_um2", "_percent", "_pct")
DERIVED_LOOKING_PREFIX_TOKENS = ("_mean_", "_median_", "_sd_", "_std_",
                                 "_min_", "_max_", "_p50_", "_p90_", "_p99_",
                                 "_avg_")
# Free-text / provenance columns that are expected to be dropped and are fine.
BENIGN_DROPPED_SUFFIXES = (
    "_threshold", "_threshold_source", "_measurement_model", "_call_authority",
    "_area_mask_model", "_area_mode", "_area_call_status", "_context_policy",
    "_expected_compartment", "_is_primary_endpoint", "_cellular_context_model",
    "_candidate_threshold_sensitivity", "_model", "_source", "_status",
    "_method", "_version", "_id", "_note", "_notes", "_units",
)
BENIGN_DROPPED_EXACT = {
    "output_key", "compartment", "region_tags", "dapi_segmentation_method",
    "primary_endpoint_marker", "primary_endpoint_channel", "slide",
    "partitioned", "qc_status", "qc_notes", "stage1_area_column", "source_vsi",
    "series_index", "module", "module_version", "level", "denominator_id",
    "denominator_semantics", "provenance_path", "contract_version",
}

SEV_ORDER = {"ERROR": 0, "WARN": 1, "INFO": 2}


class Finding(object):
    def __init__(self, sev, code, msg):
        self.sev, self.code, self.msg = sev, code, msg

    def __str__(self):
        return "%-5s %-22s %s" % (self.sev, self.code, self.msg)


# --------------------------------------------------------------------------
def aggregation_of(col, cats, header):
    """
    Return how aggregate_to_mouse.py will treat `col`:
      KEY        identity column, carried or used for grouping
      SUM        pooled by addition, surfaced as <col>_total (or renamed)
      DROPPED    discarded entirely -- never appears in mouse_level_summary.csv
    Plus a note naming the minted column(s) it feeds, if any.
    """
    if col in KEY_COLS or col in ROW_ID_COLS:
        return "KEY", ""
    if col in cats["sum_cols"]:
        if col == "region_area_um2":
            return "SUM", "-> total_tissue_area_um2 AND the denominator of every fraction/density"
        if col == "n_nuclei":
            return "SUM", "-> total_nuclei AND the denominator of every *_fraction_of_total_cells"
        if col in cats["pos_count"]:
            m = col[: -len("_pos_count")]
            return "SUM", "-> %s_pos_count_total, %s_density_per_mm2" % (m, m)
        if col in cats["positive_area"]:
            m = col[: -len("_positive_area_um2")]
            return "SUM", ("-> %s_positive_area_um2_total, %s_positive_area_fraction"
                           % (m, m))
        if col in cats["pod_area"]:
            m = col[: -len("_pod_area_um2")]
            return "SUM", "-> %s_pod_area_um2_total, %s_pod_area_frac" % (m, m)
        if col in cats["n_components"]:
            m = col[: -len("_n_components")]
            if (m + "_positive_area_um2") in header:
                return "SUM", "-> %s_n_components_total, %s_mean_component_area_um2" % (m, m)
            return "SUM", "!! summed then DROPPED: no %s_positive_area_um2 to pair with" % m
        if col in cats["n_pods"]:
            m = col[: -len("_n_pods")]
            if (m + "_pod_area_um2") in header:
                return "SUM", "-> %s_n_pods_total, %s_mean_pod_area_um2" % (m, m)
            return "SUM", "!! summed then DROPPED: no %s_pod_area_um2 to pair with" % m
        if col in cats["class_count"]:
            b = col[: -len("_count")]
            return "SUM", "-> %s_count_total, %s_density_per_mm2" % (b, b)
        return "SUM", "-> %s_total" % col
    return "DROPPED", ""


def _benign(col):
    if col in BENIGN_DROPPED_EXACT:
        return True
    return any(col.endswith(s) for s in BENIGN_DROPPED_SUFFIXES)


def _derived_looking(col):
    if any(col.endswith(s) for s in DERIVED_LOOKING):
        return True
    return any(t in col for t in DERIVED_LOOKING_PREFIX_TOKENS)


# --------------------------------------------------------------------------
def check(header, rows, provenance=None, strict=False):
    out = []
    cats = classify_columns(header)
    add = out.append

    # --- C1 required identity ------------------------------------------------
    missing = [c for c in KEY_COLS + ROW_ID_COLS if c not in header]
    if missing:
        add(Finding("ERROR", "MISSING_IDENTITY",
                    "aggregate_to_mouse.py:71-74 requires these columns and exits "
                    "without them: " + ", ".join(missing)))

    # --- C2 mouse_id validity (mirrors aggregate_to_mouse.py:76-85) ----------
    bad = [r for r in rows
           if (r.get("mouse_id") or "").strip().upper() in {"", "NA", "N/A", "UNKNOWN"}]
    if bad:
        add(Finding("ERROR", "BAD_MOUSE_ID",
                    "%d row(s) have no usable mouse_id; aggregate_to_mouse.py:82 "
                    "refuses to pool unknown animals." % len(bad)))

    # --- C3 identity conflicts (aggregate_to_mouse.py:87-95) ----------------
    ident = defaultdict(set)
    for r in rows:
        ident[(r.get("mouse_id") or "").strip()].add(
            ((r.get("genotype") or "NA").strip(), (r.get("condition") or "NA").strip()))
    for mouse, vals in ident.items():
        if len(vals) > 1:
            add(Finding("ERROR", "IDENTITY_CONFLICT",
                        "mouse_id %r maps to >1 genotype/condition: %s" % (mouse, sorted(vals))))

    # --- C4 duplicate rows (aggregate_to_mouse.py:97-111) -------------------
    idcol = "output_key" if "output_key" in header else "image"
    seen, dups = set(), []
    for r in rows:
        k = tuple((r.get(c) or "").strip() for c in (idcol, "region", "section_id", "panel"))
        if k in seen:
            dups.append(k)
        seen.add(k)
    if dups:
        add(Finding("ERROR", "DUPLICATE_ROW",
                    "%d row(s) repeat (%s, region, section_id, panel): %s"
                    % (len(dups), idcol, dups[:3])))

    # --- C5 THE DENOMINATOR RULE (the one that kills endpoints silently) ----
    # aggregate_mice() sums region_area_um2 across every row sharing
    # (mouse_id, genotype, condition, panel). Rows measured over different
    # territories MUST NOT share a panel token.
    if "region_area_um2" not in header:
        add(Finding("ERROR", "NO_DENOMINATOR",
                    "region_area_um2 is absent. Every fraction and density in "
                    "mouse_level_summary.csv would be 0.0 "
                    "(aggregate_to_mouse.py:230, 260, 320, 330) with no error."))
    else:
        blanks = [r for r in rows if _num(r.get("region_area_um2")) in (None, 0.0)]
        if blanks:
            add(Finding("ERROR", "BLANK_DENOMINATOR",
                        "%d row(s) have blank/zero region_area_um2. Blank parses to "
                        "None and is dropped from the pooled sum, so that row's "
                        "numerators land on somebody else's denominator." % len(blanks)))

    groups = defaultdict(list)
    for r in rows:
        groups[tuple((r.get(k) or "NA").strip() for k in KEY_COLS)].append(r)
    for key, grp in sorted(groups.items()):
        dens = {(r.get("denominator_id") or "").strip() for r in grp}
        regions = sorted({(r.get("region") or "").strip() for r in grp})
        if "denominator_id" not in header:
            if len(regions) > 1:
                add(Finding("WARN" if not strict else "ERROR", "MIXED_REGIONS",
                            "panel %r for mouse %r pools %d different `region` values %s "
                            "into ONE denominator. region is NOT a grouping key "
                            "(aggregate_to_mouse.py:43, 212). If these are different "
                            "territories, give the module its own panel token."
                            % (key[3], key[0], len(regions), regions)))
        elif len(dens) > 1:
            add(Finding("ERROR", "MIXED_DENOMINATOR",
                        "panel %r for mouse %r mixes denominator_id %s. Their "
                        "region_area_um2 values will be added together and every "
                        "fraction for this mouse will be wrong."
                        % (key[3], key[0], sorted(dens))))

    # --- C6 columns that vanish ---------------------------------------------
    vanished, minted_written, orphans = [], [], []
    for c in header:
        how, note = aggregation_of(c, cats, header)
        if note.startswith("!!"):
            orphans.append((c, note))
        if how != "DROPPED":
            continue
        if any(c.endswith(s) or c == s for s in MINTED_SUFFIXES):
            minted_written.append(c)
            continue
        if _benign(c):
            continue
        if _derived_looking(c):
            add(Finding("ERROR", "PRECOMPUTED_STATISTIC",
                        "%r is a pre-computed statistic. Pooling cannot average it "
                        "correctly, so aggregate_to_mouse.py discards it. Emit the "
                        "NUMERATOR and DENOMINATOR as separate extensive columns "
                        "instead (see contract table)." % c))
            continue
        vanished.append(c)
    for c, note in orphans:
        add(Finding("ERROR", "ORPHAN_PAIR",
                    "%r %s -- it is summed but never surfaced. Add the paired area "
                    "column or rename." % (c, note[3:])))
    if minted_written:
        add(Finding("INFO", "OVERWRITTEN_BY_AGGREGATOR",
                    "%d column(s) are recomputed downstream and your values are "
                    "discarded (this is correct, not an error): %s"
                    % (len(minted_written), minted_written[:6])))
    for c in vanished:
        add(Finding("ERROR", "COLUMN_VANISHES",
                    "%r matches no pooling rule in classify_columns() and will be "
                    "silently absent from mouse_level_summary.csv. Rename to one of "
                    "<X>_pos_count / <X>_positive_area_um2 / <X>_pod_area_um2 / "
                    "class_<label>_count, or drop it." % c))

    # --- C7 provenance -------------------------------------------------------
    if provenance is None:
        add(Finding("WARN", "NO_PROVENANCE",
                    "No module_provenance.json supplied. A methods section cannot "
                    "be written from these outputs alone."))
    else:
        for req in ("schema_version", "module", "runtime", "inputs",
                    "parameters", "outputs", "emits"):
            if req not in provenance:
                add(Finding("ERROR", "PROVENANCE_INCOMPLETE",
                            "module_provenance.json is missing %r" % req))
        emitted = {e.get("column") for e in (provenance.get("emits") or [])}
        undeclared = [c for c in header
                      if c not in KEY_COLS + ROW_ID_COLS
                      and c not in emitted and not _benign(c)]
        if undeclared:
            add(Finding("WARN", "UNDECLARED_COLUMNS",
                        "%d measurement column(s) are not declared in "
                        "provenance.emits[]: %s" % (len(undeclared), undeclared[:6])))

    return out, cats


# --------------------------------------------------------------------------
def read_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        if rd.fieldnames is None:
            sys.exit("ERROR: %s is empty or has no header." % path)
        return rd.fieldnames, [r for r in rd if any((v or "").strip() for v in r.values())]


def main():
    ap = argparse.ArgumentParser(
        description="Preflight a module CSV against the aggregate_to_mouse.py contract.")
    ap.add_argument("csv_path")
    ap.add_argument("--provenance", default=None,
                    help="module_provenance.json written beside the CSV")
    ap.add_argument("--strict", action="store_true",
                    help="promote MIXED_REGIONS from WARN to ERROR")
    ap.add_argument("--explain", action="store_true",
                    help="print the aggregation verdict for every column")
    args = ap.parse_args()

    if not os.path.isfile(args.csv_path):
        sys.exit(2)
    header, rows = read_rows(args.csv_path)
    prov = None
    prov_path = args.provenance
    if prov_path is None:
        guess = os.path.join(os.path.dirname(os.path.abspath(args.csv_path)),
                             "module_provenance.json")
        prov_path = guess if os.path.isfile(guess) else None
    if prov_path:
        with open(prov_path, encoding="utf-8") as fh:
            prov = json.load(fh)

    findings, cats = check(header, rows, prov, args.strict)

    if args.explain:
        print("COLUMN AGGREGATION VERDICT  (source: aggregate_to_mouse.classify_columns)")
        for c in header:
            how, note = aggregation_of(c, cats, header)
            print("  %-9s %-48s %s" % (how, c, note))
        print("")

    findings.sort(key=lambda f: SEV_ORDER[f.sev])
    for f in findings:
        print(f)
    errs = [f for f in findings if f.sev == "ERROR"]
    print("")
    print("%s: %d row(s), %d column(s), %d error(s), %d warning(s)"
          % (os.path.basename(args.csv_path), len(rows), len(header),
             len(errs), len([f for f in findings if f.sev == "WARN"])))
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    main()
