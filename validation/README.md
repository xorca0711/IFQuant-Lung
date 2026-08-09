# Synthetic validation fixture: the `blackBackground` bug, demonstrable from a fresh clone

This directory makes the project's central bug **reproducible with no microscope
data and no `D:` drive** -- a reviewer with a fresh clone and a Fiji install can
watch the bug erase nuclei and the fix restore them, in under a minute.

## The bug being demonstrated

`IF_Quant_Pipeline.groovy:1793` (the frozen measurement engine, untouched by
this fixture) runs, on the auto-tissue mask, *before* nucleus segmentation:

```groovy
IJ.run(mask, "Options...", "iterations=2 count=1 black do=Close")
```

An earlier version omitted the `black` token. ImageJ's Binary Options dialog is
a `GenericDialog`: in macro mode an **absent checkbox keyword reads as
unchecked**, so the buggy string silently wrote `Prefs.blackBackground = false`
**globally**, overriding the `true` set at pipeline startup (line 3501). That
inverted the polarity of every subsequent `Fill Holes`
(`ij.plugin.filter.Binary`: `fg = Prefs.blackBackground ? 255 : 0`, flipped
again when the mask carries an inverted LUT): the border flood-fill claims the
whole field and **every nucleus not touching the image frame is erased**. On
the real 260808-CW confocal batch this cost a ~101x pooled undercount
(152.5 vs 15,393.3 nuclei/mm^2); replaying the buggy sequence reproduced the
shipped mask at IoU = 1.0000. Those real-data numbers are documented in the
engine's source comment (lines 1766-1792) but are unverifiable from a clone --
this fixture makes the *mechanism* verifiable.

## What is here

| File | Role |
|---|---|
| `generate_fixture.groovy` | Writes a deterministic (seed 20260808) 512x512 16-bit synthetic DAPI field at 0.31 um/px: 196 clearly separated interior Gaussian-blob nuclei (~5-6 um apparent diameter) + 12 blobs centred on the frame (so the bug's "only border-connected components survive" signature is visible), mild noise, dim background. |
| `demo_blackbackground_bug.groovy` | Runs the **exact production nucleus-candidate sequence** (transcribed line-by-line from `IF_Quant_Pipeline.groovy` -- tissue stage 1584-1604 + 1793, nucleus stage 1808-1866, counting core 969-1015, with the shipped defaults) twice on that field: world A after the fixed `Options...` call, world B after the buggy call without `black`. Prints both counts and the measured `Prefs.blackBackground` after each call; restores the pref to `true` at the end. |
| `run_demo.ps1` | One command: generates the fixture, runs the demo in headless Fiji, prints a PASS/FAIL verdict. PowerShell 5.1 compatible; invokes Fiji's bundled JVM directly (the launcher exe is broken on ARM64); caps the JVM at `-Xmx1g`. |
| `out/` | Runtime outputs (fixture TIFF, world masks, logs). Git-ignored; regenerated on every run. |

## Run it

```powershell
powershell -ExecutionPolicy Bypass -File validation\run_demo.ps1
```

Fiji is found via `$env:IFQ_FIJI_DIR` if set, else `X:\Fiji`. If neither
exists, the script tells you to set `IFQ_FIJI_DIR` to your Fiji directory (the
folder containing `jars`, `plugins`, and `java`). Exit code 0 = PASS.

## Expected output (actual measured output of this fixture, 2026-08-09)

```
[1/2] Generating synthetic fixture (headless Fiji, -Xmx1g)...
FIXTURE: wrote ...\validation\out\fixture_dapi.tif
FIXTURE: 512x512 16-bit, 0.31 um/px, seed=20260808
FIXTURE: interior blobs=196 border blobs=12 total=208
[2/2] Running bug demo (fixed vs buggy Options call, same JVM)...
=== blackBackground bug demo (synthetic fixture, real ImageJ code path) ===
TRUTH: interior blobs=196 border blobs=12 total=208
WORLD A: Options string            = 'iterations=2 count=1 black do=Close'
WORLD A: Prefs.blackBackground      = true before Options..., true after
WORLD A: candidate particles        = 209  (any size, edges included; cf. line 1863)
WORLD A: included nuclei            = 196  (>= 8 um^2, edge-excluded; cf. line 1866)
WORLD B: Options string            = 'iterations=2 count=1 do=Close'
WORLD B: Prefs.blackBackground      = true before Options..., false after
WORLD B: candidate particles        = 10  (any size, edges included; cf. line 1863)
WORLD B: included nuclei            = 0  (>= 8 um^2, edge-excluded; cf. line 1866)
Prefs.blackBackground restored to true
RESULT: worldA_included=196 worldB_included=0 undercount_factor=infinite (world B included = 0)
VERDICT: PASS -- fixed call keeps blackBackground=true and recovers 196/196 interior
nuclei (within 25%); buggy call flips blackBackground to false and collapses the
count to 0 (< 20% of world A).
```

The counts are deterministic (verified across repeated runs). The two saved
masks make the erasure visible directly: `out/mask_worldA.tif` contains all
segmented nuclei; `out/mask_worldB.tif` contains only border-connected
remnants (the 10 world-B candidates are all frame-touching, mirroring the
real-data observation that 100% of candidate components were border-touching
in all 79 affected fields).

## What PASS demonstrates

1. **The mechanism.** The single missing `black` token in a Binary `Options...`
   macro string flips the *global* `Prefs.blackBackground` from `true` to
   `false` -- printed directly, before/after, in both worlds.
2. **The consequence.** With the pref flipped, the identical downstream
   segmentation sequence (subtract background -> enhance contrast -> 8-bit ->
   Auto Local Threshold Phansalkar -> Fill Holes -> Watershed -> particle
   filter) erases every nucleus not touching the image frame: included count
   196 -> 0 on a field whose ground truth is 196 interior nuclei.
3. **The fix.** With `black` present, the same sequence recovers exactly the
   generated interior blob count, through the real ImageJ code path (real
   `Binary`/`Fill Holes`/`Auto Local Threshold` classes, headless, same JVM
   flags as production runs).

## Limitations (read before citing this)

Synthetic Gaussian blobs are not tissue. This fixture demonstrates the
**mechanism** of the bug and the correctness of the fix on a controlled field;
it does **not** validate any biological threshold, the Phansalkar parameters,
watershed behaviour on touching nuclei, marker gating, or the pipeline's
quantitative accuracy on real lung immunofluorescence. The real-data magnitudes
(152.5 vs 15,393.3 nuclei/mm^2, ~101x pooled) come from the 260808-CW confocal
batch and cannot be reproduced from a clone; what this fixture shows is that
the causal chain claimed in the engine's source comment (missing `black` ->
global pref flip -> inverted Fill Holes -> border-only survivors) is real and
sufficient to collapse a nucleus count. The demo also collapses to an
*infinite* undercount factor here (0 included nuclei) rather than the ~101x
seen on tissue, because real tissue fields have border-touching tissue whose
nuclei partially survive edge exclusion at the region level, while this
fixture's border blobs are all individually edge-excluded. The tissue-stage
replication is minimal (blur -> Triangle -> Convert to Mask -> the `Options...`
call): it carries the pref flip exactly as in production but does not exercise
region-ROI handling. One further honest note: world A's candidate count is 209,
not 208 -- one extra sub-minimum-size noise/split particle -- which is why the
verdict is defined on the included (>= 8 um^2, edge-excluded) count against the
interior ground truth.

## Corrected endpoint algebra fixture

`run_endpoint_demo.ps1` generates four tiny deterministic masks and invokes the
production `endpoints/evaluate_endpoints.groovy` executor. The fixture asserts:

* numerator `KRT5 AND T1A` = 2 pixels;
* denominator `NOT T1A OR KRT5` = 6 pixels;
* fraction = 1/3, while bare KRT5 = 4 pixels and the tissue region = 8 pixels.

Run it with:

```powershell
powershell -ExecutionPolicy Bypass -File validation\run_endpoint_demo.ps1
```

This validates boolean algebra, output columns, containment guards, refusal of
uncalibrated parameters, and refusal of retracted specifications. It does not
calibrate T1A/PDPN or establish a biological endpoint.
