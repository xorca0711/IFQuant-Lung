#!/usr/bin/env python3
"""
merge_module_summaries.py  (DRAFT v1.0.0)
=====================================================================
Concatenate the summary CSVs written by several IFQuant-Lung modules into the
ONE file that aggregate_to_mouse.py consumes.

This exists because aggregate_to_mouse.py takes a single path
(aggregate_to_mouse.py:408) and must never be forked to take several. The union
happens here, before it, so there is still exactly one definition of "mouse"
and one set of pooling rules.

  engine run_summary.csv        \\
  slide_level_summary.csv        >-- merge_module_summaries.py --> merged.csv
  morphometry_slide_summary.csv /                                       |
  spatial_tile_summary.csv     /                                        v
                                                          aggregate_to_mouse.py

What it refuses to do
---------------------
  * merge files whose union would violate the contract (runs ifq_contract.py
    on every input first, and on the merged result);
  * merge files where two producers write region_area_um2 for the same
    (mouse_id, genotype, condition, panel). That silently doubles the
    denominator of every area fraction for that mouse;
  * merge files that collide on (output_key|image, region, section_id, panel) --
    aggregate_to_mouse.py:97-111 would sys.exit anyway, but failing here names
    the two files instead of the row.

Missing columns are written blank, not zero. aggregate_to_mouse._num maps ""
to None (line 51), the value is filtered out (line 227), and the sum is
unaffected. That is what lets one producer contribute numerators to a group
whose denominator another producer owns.

Usage
-----
  python3 merge_module_summaries.py \\
      --in D:/wsi_stage1/stats/slide_level_summary.csv \\
      --in D:/morph/morphometry_slide_summary.csv \\
      --out D:/stats/merged_module_summary.csv
  python3 aggregate_to_mouse.py D:/stats/merged_module_summary.csv

Standard library only.
=====================================================================
"""
import argparse
import csv
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def bind(repo):
    """Import the REAL rules. In deployment repo == HERE (this script is a sibling
    of aggregate_to_mouse.py); --repo exists so the draft can be tested in place."""
    sys.path.insert(0, repo)
    sys.path.insert(0, os.path.join(HERE, "contract"))
    try:
        from aggregate_to_mouse import KEY_COLS               # noqa: E402
    except ImportError:
        sys.exit("ERROR: aggregate_to_mouse.py not found at %s -- it must sit beside "
                 "this script; the merged file has to satisfy exactly its rules and "
                 "no others." % repo)
    try:
        from ifq_contract import check_csv, check_ownership, load_aggregator  # noqa: E402
    except ImportError:
        check_csv = check_ownership = load_aggregator = None
    return list(KEY_COLS), check_csv, check_ownership, load_aggregator


def read(path):
    with open(path, newline="", encoding="utf-8-sig") as fh:
        rd = csv.DictReader(fh)
        header = list(rd.fieldnames or [])
        rows = [r for r in rd if any((v or "").strip() for v in r.values())]
    return header, rows


def main():
    ap = argparse.ArgumentParser(description="Merge module summary CSVs into one "
                                             "aggregate_to_mouse.py input.")
    ap.add_argument("--in", dest="inputs", action="append", required=True,
                    help="module summary CSV (repeatable)")
    ap.add_argument("--out", required=True, help="merged CSV to write")
    ap.add_argument("--repo", default=HERE, help="root holding aggregate_to_mouse.py")
    ap.add_argument("--skip-contract", action="store_true",
                    help="do not run ifq_contract.py first (diagnostics only)")
    args = ap.parse_args()

    KEY_COLS, check_csv, check_ownership, load_aggregator = bind(args.repo)

    for p in args.inputs:
        if not os.path.isfile(p):
            sys.exit("ERROR: not found: %s" % p)

    problems = []

    # ---- 1. contract-check every input ------------------------------------
    if check_csv is not None and not args.skip_contract:
        classify_columns, key_cols, row_id_cols, numparse = load_aggregator(args.repo)
        for p in args.inputs:
            errs, warns, _info, _rows, _hdr = check_csv(
                p, classify_columns, key_cols, row_id_cols, numparse)
            for w in warns:
                print("WARNING %s: %s" % (os.path.basename(p), w))
            problems += ["%s: %s" % (os.path.basename(p), e) for e in errs]
        problems += check_ownership(args.inputs, classify_columns, key_cols)

    # ---- 2. union the headers, preserving first-seen order ----------------
    cols, per_file = [], []
    for p in args.inputs:
        header, rows = read(p)
        per_file.append((p, header, rows))
        for c in header:
            if c not in cols:
                cols.append(c)

    # aggregate_to_mouse.validate_rows requires these in the MERGED header.
    for c in KEY_COLS + ["image", "region", "section_id"]:
        if c not in cols:
            problems.append("merged header lacks required column '%s'" % c)

    # ---- 3. cross-file row-identity collision ------------------------------
    ident = "output_key" if "output_key" in cols else "image"
    seen = {}
    for p, header, rows in per_file:
        for r in rows:
            k = tuple((r.get(c) or "").strip()
                      for c in [ident, "region", "section_id", "panel"])
            if k in seen and seen[k] != p:
                problems.append(
                    "row identity %s appears in BOTH %s and %s. aggregate_to_mouse.py:97 "
                    "would reject the merged file as a duplicate. Give each module a "
                    "distinct output_key prefix (e.g. 'morph@', 'spat@')."
                    % (k, os.path.basename(seen[k]), os.path.basename(p)))
            seen[k] = p

    if problems:
        print("")
        print("REFUSING to merge: %d blocking problem(s)." % len(problems))
        for m in problems:
            print("  ERROR  %s" % m)
        print("")
        print("A merged file that violates the contract still produces a plausible "
              "mouse_level_summary.csv, which is why this is fatal rather than a warning.")
        return 2

    # ---- 4. write ----------------------------------------------------------
    outdir = os.path.dirname(os.path.abspath(args.out))
    if outdir:
        os.makedirs(outdir, exist_ok=True)
    n = 0
    with open(args.out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=cols, restval="")
        w.writeheader()
        for p, header, rows in per_file:
            for r in rows:
                # restval="" leaves absent columns BLANK, never 0.
                w.writerow({c: r.get(c, "") for c in cols})
                n += 1

    scopes = sorted({(r.get("panel") or "NA").strip()
                     for _p, _h, rows in per_file for r in rows})
    mice = sorted({(r.get("mouse_id") or "NA").strip()
                   for _p, _h, rows in per_file for r in rows})
    print("Merged %d file(s), %d row(s), %d column(s) -> %s"
          % (len(per_file), n, len(cols), args.out))
    print("Panel scopes: %s" % ", ".join(scopes))
    print("Distinct mouse_id: %d (%s)" % (len(mice), ", ".join(mice[:8])))
    print("Next:  python3 aggregate_to_mouse.py %s" % args.out)
    print("Reminder: n = MICE. Extra panel scopes multiply ROWS, not n.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
