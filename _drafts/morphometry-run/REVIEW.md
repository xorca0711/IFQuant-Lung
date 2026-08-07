# Review of `_drafts/morphometry/` — what was wrong, and what was measured

Draft reviewed: `claude/module-drafts:_drafts/morphometry/` @ 7b78ade
(`qupath_lung_morphometry.groovy` 1553 lines, `morphometry_derive.py` 450,
`Invoke-Morphometry.ps1` 131).

## Does it run?

**Partly.** The measurement kernels compile and are correct.
`IFQ_MORPH_SELFTEST=true` on the unmodified draft: **33 phantom checks, 33
pass, exit 0**. The Crofton perimeter constant is exact for a disk (1255.88 vs
1256.64 at R = 200; the naive 4-connected boundary count is +27.3 %), the striped
phantom recovers MLI, airspace fraction and septal thickness, and block
additivity is exact to 1e-9. That part of the draft is good work and I kept it
nearly unchanged.

What it had never done is touch an image. Everything below is in the part that
only executes on real data.

---

## Blocking defects

### D1 — the compartment comparison is destroyed by the aggregator (fatal, design)

The draft emits damaged and intact as two `region` values sharing one `panel`.
`aggregate_to_mouse.py` groups on `(mouse_id, genotype, condition, panel)` and
pools **across** `region`. The two rows are therefore **added**.

Executed (`test_aggregation_contract.py` T4), same numbers into both designs:

| design | mouse rows | recovered MLI |
|---|---|---|
| compartment in `region` (draft) | 1 | one merged **56.00 µm** |
| compartment in `panel` (fixed) | 2 | damaged **20.00 µm**, intact **60.00 µm** |

The module's stated reason to exist — "the 2×2 area confusion table that tests
exactly that" — cannot survive its own aggregation step. The sibling draft
`_drafts/hierarchy-contract/MODULE_CONTRACT.md` §2.3 already specifies the fix
(`panel = "<PANEL>@<scope>"`); the morphometry draft does not use it. The two
drafts contradict each other and the morphometry one is wrong.

Fixed: compartment moved into `panel`.

### D2 — the ROI is not the endpoint's ROI (fatal, scientific)

The draft builds its analysis ROI with the **Stage 1** recipe (ds 16, blur 2,
Otsu, close r=4, open r=2, `removeFragments` 0.05 mm²) and then computes the AGER
damage map inside it at ds 8. The **locked** detector
(`scripts/measure_damage_locked.groovy`) uses a different mask: ds 8, DAPI blur
σ=2, whole-frame Otsu, close r=4, **no open, no fragment removal**.

Different mask → different Otsu → different normalisation of the AGER local
fraction → a compartment that is not the endpoint's compartment. A cross-check on
a lookalike denominator checks nothing.

Fixed: the locked recipe is reproduced verbatim. Verified — it returns exactly
the published damaged fractions on all four slides:

| slide | published | reproduced |
|---|---|---|
| het m4-1 infected | 6.71 % | **6.71 %** |
| hom m2 infected | 4.68 % | **4.68 %** |
| het m4-2 control | 0.93 % | **0.93 %** |
| hom m6 control | 0.18 % | **0.18 %** |

### D3 — the MLI orientation average is weighted, not equal (real, quantified)

`chordScan` pools all four directions into one `(ΣL, ΣN)` pair. Diagonal test
lines on a square lattice are spaced δ/√2 apart, so per unit area they deliver
√2 more test-line length and ~√2 more chords. Measured on the draft's own disk
phantom: **diagonals carry 1.41–1.43× the chords of the axes**. So the
"4-direction average" is a weighted orientation average with weights
(1, √2, 1, √2)/(2+2√2).

On an isotropic phantom the error is small (pooled 23.666 vs equal-weight 23.696,
0.13 %). On anisotropic tissue it is bounded by the anisotropy, and the draft
cannot detect anisotropy either because it only carries h and v separately, not
the diagonals.

Fixed: four orientations are carried as separate additive primitives and the
orientation average is formed with equal weight downstream.

### D4 — the fine-pass inner loops are dynamic Groovy (fatal, performance)

Lines 1379–1389 upsample the coarse ROI/label arrays onto the fine block grid
with `int gy = (ey + y) / K` at **script level**, outside `@CompileStatic`. In
Groovy `/` on two ints returns a `BigDecimal`, so this is two BigDecimal
divisions plus two array reads per pixel, over ~6.6 Mpx per block. The same
pattern appears in the block quick-reject (1347–1349) and in three whole-image
label scans per emitted row (1464–1465, 1476–1477, 1485–1486, `cw*ch` = 37 Mpx
each).

Measured (`probe/bench_upsample.groovy`, 2560×2560 block, this dataset's coarse
grid):

| implementation | time |
|---|---|
| draft, dynamic Groovy | **9 791 ms** |
| `@CompileStatic` + `Math.floorDiv`, warm | **99 ms** |

**99× slower.** For a 60-block × 4-slide run that is ~2 350 s (39 min) of pure
index arithmetic, on top of I/O, before a single measurement is made.

Fixed: all per-pixel work moved into `@CompileStatic` methods; blocks are also
processed in parallel (I/O-bound: measured 5.2 s to read one 2048×2048×4ch
region at ds 2).

### D5 — a QC flag that always passes

```groovy
row["class_morph_pxfine_ok_count"] =
  (Math.abs(pxFine - envDouble("IFQ_MORPH_EXPECT_PXFINE_UM", pxFine)) < 1e-6d) ? 1.0d : 0.0d
```

The fallback is `pxFine` itself, so with the env var unset the check compares a
number to itself and always emits 1. The draft's `.ps1` does set it, but the
Groovy alone silently self-approves, and `morphometry_derive.py` then prints
`morph_resolution_consistent = true` on a run that verified nothing.

Fixed structurally instead of with a flag: one CSV per fine downsample, so two
resolutions cannot enter the same aggregation at all.

### D6 — six emitted columns violate the sibling draft's own naming rule

`MODULE_CONTRACT.md` §2.2 forbids a `<Name>` ending in `_um`.
`class_morph_perimeter_um_count`, `class_morph_chordlen_um_count`,
`class_morph_testline_um_count`, `class_morph_chordtrunclen_um_count`,
`class_morph_chordlenh_um_count`, `class_morph_chordlenv_um_count`,
`class_morph_edmhalf_um_count` all break it.

This is a **false positive of the rule**, not a real hazard — an extensive
summed length is not a derived scalar — but the module has to either conform or
the contract has to be amended, and the draft does neither.

Fixed by conforming (`…um_count`, glued). **Recommended contract amendment:**
allow the `_um` / `_mm` tail on `class_*_count` columns specifically, since that
family is unconditionally summed and a length is the one derived-looking unit
that is legitimately additive.

---

## Scientific defects

### S1 — the default channel set makes the check circular

`Invoke-Morphometry.ps1` defaults `-Channels "0,3"` = DAPI + T1α/PDPN, and the
draft's own documentation admits: *"T1alpha/PDPN is an AT1 marker like AGER, so
the 'independent' check on the AGER denominator is only independent by channel,
not by biology."*

That is the exact ground on which AGER co-negativity was **retracted** from the
KRT5 numerator (`docs/ECTOPIC_POD_ENDPOINT.md` §4d). A default that the file
itself documents as partly circular should not be the default.

Fixed: `IFQ_MORPH_CHANNELS` has no default and the runner passes `0` (DAPI).
The PDPN-included variant is available as an explicitly flagged secondary.

### S2 — the airspace-topology measures cannot work on this panel, and the draft half-knows it

The draft warns about airspace "flooding" and offers two causes, (a) resolution
and (b) staining. On this data it is (b), and it is not fixable: a DAPI mask has
no continuous septal barrier because septal wall between nuclei is unlabelled.
Measured at ds 4, whole slide: **85.7 %** (m4-1 infected) and **91.8 %** (m4-2
control) of airspace area sits in components larger than 10 000 µm².

Fixed: kept but hard-gated — `morph_connectivity_interpretable` goes `false`
above 90 % and the runner prints why. The area/length measures never touch
connectivity and are unaffected.

### S3 — box counting is expensive and does not measure what it claims here

`boxCounts` allocates `nbx*nby*(nRegions+1)` bytes; at ε=1 on this dataset's
coarse grid with 2 regions that is **113 MB**, and ε=1 is just the tissue pixel
count. Over ε = 1…64 px at 2.76 µm/px the fitted range spans 2.8–177 µm —
sub-alveolar to multi-alveolar — so the slope mixes several regimes rather than
estimating a dimension.

The draft's citation is **real and correctly described**: Andersen MP et al.,
*Alveolar fractal box dimension inversely correlates with mean linear intercept
in mice with elastase-induced emphysema*, Int J Chron Obstruct Pulmon Dis
2012;7:235–43, R = −0.95 ([DOI](https://doi.org/10.2147/COPD.S26493), PMID
22500123, retrieved via PubMed). But their D_B is measured on binarised H&E
sections of whole alveolar structure, not on a nuclear mask.

Removed. Nothing in the cross-check needs it.

### S4 — the draft's resolution claim does not survive measurement

`Invoke-Morphometry.ps1` states: *"vs native 0.345 µm/px, ds2 shifts airspace
fraction +0.1 %, MLI +1.8 %"*.

Measured here on a DAPI mask at the control-locked threshold, two 1.41 mm
windows per slide, ds 1 → ds 2:

| quantity | m4-1 damaged | m4-1 intact | m4-2 damaged | m4-2 intact |
|---|---|---|---|---|
| nucleated area fraction | −0.3 % | +0.1 % | 0.0 % | −0.4 % |
| **MLI direct** | **+26.1 %** | **+15.6 %** | **+20.9 %** | **+19.1 %** |
| airspace width (4·mean EDM) | +7.4 % | +2.6 % | +3.7 % | +3.7 % |
| wall thickness (4·mean EDM) | +11.2 % | +6.0 % | +7.8 % | +8.3 % |
| surface density S_V | −20.0 % | −12.1 % | −15.3 % | −14.9 % |

The area fraction claim holds. **The MLI claim is off by an order of magnitude**
— it is +16 to +26 %, not +1.8 %. The draft measured it on a
DAPI + T1α mask, where the segmented phase is a much larger, smoother object; on
a DAPI-only mask the phase is small nuclei and chord statistics are far more
resolution-sensitive.

### S5 — the threshold placeholder is wrong for the DAPI-only mask

The draft's `.ps1` carries `-TissueThreshold 700` "PROVISIONAL", from Otsu on
max(DAPI, T1α) at 2.76 µm/px. Recalibrated on the two controls only, DAPI at fine
resolution: in-ROI Otsu = 820.0 / 948.8 (ds 1), 814.3 / 939.6 (ds 2), 796.9 /
924.4 (ds 4). Locked at **880** by the pre-registered rule (mean of the two
controls at ds 2, rounded to 10). Notably stable across resolution — the
threshold is not the resolution problem; the chord statistics are.

---

## Things the draft got right and that were kept

* The Crofton 4-direction perimeter estimator and its constant. Exact for a disk.
* The EDM offset. `mean(EDM) = t/4 + 0.5` for a slab of thickness t is exactly
  right for ImageJ's convention, and the self-test confirms it on t = 3, 4, 6, 8.
* Excluding truncated chords from the direct MLI, and carrying the truncated
  count *and* length so the residual bias can be bounded rather than assumed
  away.
* Carrying primitives instead of ratios, and doing the division after pooling.
  The design principle is right; the compartment plumbing (D1) is what breaks it.
* Halo-with-core attribution for exact block additivity.
* Requiring an explicit tissue threshold and refusing to default it.
* Refusing to emit `NA` for `mouse_id`.
* The `max`-downsample trick for preserving airspace topology across scales.
* All literature citations checked (Madi 2025, Andersen 2012) are real and
  accurately characterised.
