# Archived documentation

These are **point-in-time records**, not current guidance. They were moved here
on 2026-08-07 because they describe a state of the pipeline that has since
changed, but they are the **only** record of the validation work they document,
so they are archived rather than deleted.

Read them as history. Do not follow their instructions.

| Document | Recorded | What it is | Why it moved |
|---|---|---|---|
| `PILOT_G002_MORPHOLOGY_RESULTS.md` | 2026-07-21 | validated one-image results for panels E and R | single-image pilot, superseded by the whole-slide route |
| `TEST_RUN_ERROR_RATE_AUDIT.md` | 2026-07-22 | observed per-image failure modes and rates | predates the WSI route and its failure modes |
| `SCRIPT_SELF_REVIEW_20260723.md` | 2026-07-23 | software-defect audit and corrections | defects fixed; the review is a snapshot of that day |
| `UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md` | 2026-07-28 | marker-wide context/evaluability audit | its decision matrix is now in `docs/MARKER_MORPHOLOGY_GUIDE.md` |

## What replaced them

Current documentation lives in [`../../docs/`](../../docs/README.md). The
nearest live equivalents:

* interpretation and the decision model → `docs/MARKER_MORPHOLOGY_GUIDE.md`
* compartment/context tagging → `docs/COMPARTMENT_TAGS_AND_PROGRESSION.md`
* the whole-slide route → `docs/WSI_TILING_WORKFLOW.md`
* the study endpoint and its calibration → `docs/ECTOPIC_POD_ENDPOINT.md`

## A caution about the numbers in here

Several of these documents quote thresholds, gate values and error rates. Those
were pilot placeholders at the time and **none of them are the current locked
values**. The only calibrated parameters in this project are recorded in
`docs/ECTOPIC_POD_ENDPOINT.md`, with their derivation, their validation status,
and what is still outstanding. Do not copy a number out of this folder.
