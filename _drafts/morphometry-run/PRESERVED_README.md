# Morphometry module — work-in-progress, preserved mid-run

Copied out of scratchpad on 2026-08-07 at ~22:50 because the agent producing it
was stopped after 178 minutes (2.4× the longest completed agent this session).
It was **not** stalled — it was still writing a threshold-sensitivity sweep two
minutes before being stopped. It was stopped to free contention for the launcher
track, not because it had failed.

**Nothing here has been reviewed and nothing has been verified.** The adversarial
verifier for this track never ran.

## Read this first: which numbers are real

| path | data |
|---|---|
| `out_ds4/` | **REAL** — run against the four `.vsi` slides at downsample 4 |
| `out_thr440/` | **REAL** — threshold-sensitivity run, incomplete when stopped |
| `contract_work/` | **SYNTHETIC** — hand-made rows for testing the aggregation contract |
| `draft/`, `draft_orig/` | the earlier unrun drafts this run started from |
| `probe/`, `run/` | probe scripts and run logs |

`contract_work/scoped_panel/stats/damaged_vs_intact_morphometry.csv` looks like
a result and is **not one**. Its values are round test numbers
(`damaged_area_mm2 = 5.0`, `intact_area_mm2 = 95.0`, mouse `M2` labelled
`naive`) fabricated to exercise the contract. Do not quote it. The real
compartment comparison is `out_ds4/stats_ds4/compartment_contrast_ds4.csv`.

## What the module was for

Morphometry measures lung **architecture** — airspace fraction, mean linear
intercept, septal thickness, surface density — from the DAPI-derived tissue
mask, independently of any marker.

Its most valuable job is a **cross-check on the damaged-area denominator**. The
endpoint currently rests on one marker (AGER). If regions the AGER-density
detector calls "damaged" are not also architecturally distorted, the denominator
is measuring staining rather than injury. That test is what
`compartment_contrast_ds4.csv` is meant to answer.

**The cross-check must not be circular.** Morphometry has to be computed from
the DAPI/tissue mask, never from AGER — otherwise it is comparing AGER to
itself. Whether this implementation actually honours that is exactly the kind of
thing the verifier would have checked, and did not.

## Documents produced

`RESULTS.md`, `REVIEW.md`, `SCHEMA.md`, `RESOLUTION.md`, `STEREOLOGY_CAVEATS.md`,
`README.md` — read `REVIEW.md` and `STEREOLOGY_CAVEATS.md` first. MLI from a
single 2D section is a biased estimator of the 3D quantity, and any claim made
from it needs those caveats attached.

## To resume

`lung_morphometry.groovy` (67 KB) is the reworked module; `morphometry_derive.py`
is the roll-up. `run/Invoke-Morphometry.ps1` shows the invocation. Re-running is
cheap relative to re-deriving; the expensive part was the design decisions
recorded in the markdown.
