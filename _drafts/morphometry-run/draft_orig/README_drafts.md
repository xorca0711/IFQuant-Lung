# Module drafts — REVIEW ONLY, DO NOT MERGE

This branch (`claude/module-drafts`) exists purely as a review surface. It is
branched from `ee4fb94` and is **not** intended to merge into
`claude/qupath-wsi-stage1-tiling` or `main` as-is.

Everything under `_drafts/` was produced by parallel design agents on
2026-08-07. None of it has been integrated, and none of it has been reviewed by
a human yet. Treat every number in it as unverified unless it cites a measured
result from `docs/ECTOPIC_POD_ENDPOINT.md`.

## What to look at, in order

| Path | What it is | Why it matters |
|---|---|---|
| `hierarchy-contract/PROPOSED_TREE.md` | the proposed repo layout | **read first** — decides what moves |
| `hierarchy-contract/MODULE_CONTRACT.md` | what a module must emit | the thing that stops `aggregate_to_mouse.py` forking |
| `morphometry/qupath_lung_morphometry.groovy` | (A) architecture measures | 80 KB, the largest single draft |
| `spatial/spatial_stats.py` | (B) niche/neighbourhood stats | consumes the engine's per-cell CSV |
| `injury_model_profiles/injury_models/influenza_pr8.model.json` | (D) worked model profile | 51 KB, the fully filled-in example |

## The one thing to check hardest

`aggregate_to_mouse.py` **sums** most columns and **recomputes** fractions. If a
new module names a column so that a ratio, a mean, or a mean linear intercept
gets summed, the mouse-level number is silently wrong — no error, just a bad
result. `MODULE_CONTRACT.md` claims to define the naming rule that prevents
this. That claim is the highest-value thing to verify.

## Known caveats going in

- The agents were told not to modify the repo, and did not — `ee4fb94` is clean.
- Two agents copied `aggregate_to_mouse.py` and `aggregate_tiles_to_slide.py`
  into their scratch area for testing. Those copies are excluded here.
- Some drafts contain example output CSVs generated from synthetic or partial
  data. They are illustrative, not measured results.
- The morphometry draft was written against the QuPath API but has **not** been
  run against the real `.vsi` data end to end.
- One of eight agents had not returned when this branch was cut.

## Status of the actual pipeline (for context)

The measured, committed state lives on `claude/qupath-wsi-stage1-tiling`:

- damaged-area denominator **locked** from controls (AGER 150, sigma 40 um,
  cutoff 0.14); held-out infected 6.71% / 4.68% vs controls 0.93% / 0.18%
- KRT5 numerator requires **PDPN co-negativity**; ceiling t = 200 **proposed,
  not locked** (needs re-measurement at full tile resolution)
- airway exclusion **not implemented** — still needs hand-drawn annotations
- five parameters now derived from two control animals; that overfitting risk is
  recorded in `docs/ECTOPIC_POD_ENDPOINT.md`
