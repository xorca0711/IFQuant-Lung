# Superseded QuPath-side measurement engines

Preserved 2026-08-07 from open PRs #9 and #10 before their branches were retired.
**Nothing here is part of the current pipeline. Do not run it.**

## What this was

Both PRs implemented a **second, independent measurement engine on the QuPath
side**, running in parallel to `IF_Quant_Pipeline.groovy`:

| Source | Preserved as |
|---|---|
| PR #9 `claude/qupath-influenza-pipeline-d4jt28` | `pr09-qupath-pipeline/` |
| PR #10 `claude/qupath-slidescanner-launcher-d4jt28` | `pr10-slidescanner-launcher/` |

The four `docs/*.md` files those branches also carried are already preserved in
[`../docs/`](../docs/README.md) and are not duplicated here.

## Why it was rejected

Two independent measurement engines drift. The morphology-first decision model —
intensity nominates, morphology authorizes, three-state calls, per-marker gates —
has been validated exactly once, in the Fiji engine. A second implementation
would have to reproduce all of it and then stay in step with it forever, and any
divergence would show up as a silent disagreement between two numbers that are
supposed to mean the same thing.

The adopted architecture instead keeps **QuPath as a reader and tiler only**:
it opens the slide, picks the true high-resolution series, detects tissue, and
cuts calibrated tiles that the *unchanged* Fiji engine measures. See
[`../../docs/QUPATH_FIJI_INTEGRATION.md`](../../docs/QUPATH_FIJI_INTEGRATION.md).

That decision is the reason `docs/BRANCHING.md` marks these branches
"do not build on these".

## What is still worth reading

`pr10-slidescanner-launcher/launcher/IFQuantLauncher_QuPath_SlideScanner.cs` is
a worked example of a **route-aware Windows launcher** — a separate launcher for
a separate acquisition route. The current launcher redesign takes the opposite
approach (one launcher with an explicit image-type selector, plus a Fiji-only
legacy mode), but the argument-building and QuPath-invocation code here is a
useful reference.

`QuPath_SlideScanner_Quant.groovy` also contains reasonable QuPath-side
series-selection and tiling logic. Most of it was independently rediscovered and
hardened in `qupath_wsi_tile_export.groovy`, which additionally handles the
things this version does not: JPEG-2000 `.ets` decoding constraints, the
`Area.getBounds()` outward-rounding trap, GeometryCollection rejection by JTS,
`pixelWidth != pixelHeight`, and the per-tile `_RoiSet.zip` that makes
overlapping tiles sum exactly.

## Recovering the full branches

Only files unique to those branches are preserved here. The complete history is
recoverable from the commits if the branches are ever restored:

* PR #9 — `ac9250a`
* PR #10 — `3160753`
