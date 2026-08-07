# Ectopic KRT5+ pod endpoint — definition, hazards, and calibration

Status: **damaged-area denominator implemented and mechanically validated;
airway exclusion NOT implemented; thresholds NOT calibrated.** Written 2026-08-07.
Supersedes the endpoint described in `docs/WSI_TILING_WORKFLOW.md` §"What this
measures", which used total tissue area as the denominator.

---

## 1. The endpoint

```
ectopic pod fraction  =   KRT5+ area  /  damaged alveolar area
```

both measured **within alveolar parenchyma only**, with conducting airways
excluded from numerator and denominator alike.

* **damaged alveolar area** = alveolar tissue lacking AT1 coverage.
  Primary marker **AGER⁻**. PDPN⁻ is computed alongside as a cross-check.
* **numerator** = KRT5+ area inside that same alveolar region.

### Why not KRT5+ area / total tissue area

Because it silently measures infection severity. Pod area scales with how much
lung was damaged, and that varies mouse to mouse. A smaller pod area in a KO
animal could simply mean that animal had a milder infection. Normalising to the
damaged area is what makes the genotype comparison honest.

### Why AGER rather than PDPN as the AT1 marker

PDPN also marks lymphatics and is induced on injured fibroblasts and some
inflammatory cells; AGER (RAGE) is tightly AT1-restricted. Both are on the same
section in panel LEFT (KRT5-488 / AGER-555 / T1α-647), so both denominators are
computable from one image and can be reported side by side. **AGER⁻ is primary.**

### Reference method

Lin X. et al., *Viral infection induces inflammatory signals that coordinate YAP
regulation of dysplastic cells in lung alveoli*, J Clin Invest 2024;134(19):e176828.
DOI: https://doi.org/10.1172/JCI176828 (PMID 39352385). Retrieved via PubMed.

Their metric is "percentages of KRT5+ dysplastic cell areas in damaged alveolar
areas (PDPN− and KRT5+)". Their methods state the areas "were measured using
outline spline in the measure menu of Axiovision 4.8" — i.e. **drawn by hand**.

Two consequences follow, and both matter:

1. There is no published automated threshold to inherit. Any automated
   implementation must be validated against manual outlines on a subset before
   its numbers mean anything.
2. Manual outlining is *why* airway exclusion never appears as a rule in the
   literature. A human drawing "damaged alveolar area" excludes conducting
   airways by eye without writing anything down. An automated version has to
   make that implicit step explicit.

---

## 2. Hazard: do NOT use AGER/PDPN to find airways

Airway epithelium is AGER/PDPN-negative. So is severely injured alveolar
parenchyma — because PR8 destroys the AT1 sheet. **The most damaged alveolar
regions are the most AGER-poor, and that is precisely where pods form.**

An "exclude AGER-poor regions as airway" rule would therefore preferentially
delete the pods it is meant to measure, biasing the result toward the null —
in the same direction as the hypothesis being tested. That is the worst kind of
analysis bug: one that produces the expected answer.

**AGER⁻ as the damaged-area denominator is correct. AGER⁻ as an airway detector
is not.** These are different uses of the same channel and must not be conflated.

Nothing in the 4-channel panel (DAPI / KRT5 / AGER / PDPN) separates conducting
airway from injured alveolus on marker identity alone.

---

## 3. Implementation: anatomical partition at Stage 1

No change to the frozen `IF_Quant_Pipeline.groovy`. The engine already accepts
multiple named, non-overlapping ROIs per image and derives an anatomical tag
from each ROI name. Stage 1 writes **two** ROIs per tile instead of one:

| ROI name | geometry | tag |
|---|---|---|
| `alveolar_core_<tileid>` | core ∩ tissue − airway | alveolar |
| `airway_core_<tileid>` | core ∩ tissue ∩ airway | airway |

(the airway ROI is omitted when empty). The engine emits one summary row per
region; Stage 3 reports the alveolar row as the ectopic endpoint.

This is numerically exact: the KRT5 area mask is built once per tile on the whole
field *before* any region clipping, so both regions are measured against an
identical mask at an identical threshold, and alveolar + airway area = whole-tile
area. The existing Stage 3 area reconciliation therefore still passes unchanged
and keeps guarding against silent tile loss.

**It also keeps the airway signal as a built-in positive control.** Airway KRT5
per mm² of airway tissue should be present in every group including uninfected
controls, and should not differ by genotype. If it does, that is a staining or
threshold problem, discovered inside the same run rather than after the fact.

### The manual step, stated honestly

Airway polygons are drawn by a human in QuPath at slide level, exported as
GeoJSON in full-resolution slide coordinates, and subtracted per tile by Stage 1.

A geometry-only proposer (enclosed lumen + continuous DAPI-dense epithelial ring,
computed on the DAPI channel Stage 1 already thresholds) may propose candidates
for human confirmation. It must not touch the KRT5 channel, and it fails on
longitudinal and tangential airway cuts, which have no closed lumen. Semi-automatic
proposal, human confirmation, blinded, KRT5 channel off.

Annotation burden is an **estimate, not a measurement**: tens of airway profiles
per section, plausibly 15–30 min per slide, four slides, once. Count them on one
slide before committing.

---

## 4. Calibration: fixed thresholds are load-bearing

Measured on the four pilot slides, whole-slide, DAPI-Otsu tissue mask,
per-image Otsu on the KRT5 channel:

| slide | KRT5 Otsu threshold | KRT5⁺ % of tissue |
|---|---|---|
| het m4-1 **infected** | 218.0 | 6.68 |
| hom m2 **infected** | 290.5 | 4.41 |
| het m4-2 *uninfected* | 224.8 | **0.09** |
| hom m6 *uninfected* | **54.6** | **4.95** |

Uninfected `m6` reads 4.95% KRT5⁺ — indistinguishable from an infected animal —
because per-image Otsu chose a threshold of 54.6, inside that channel's noise
floor (in-tissue p50 ≈ 36, p90 ≈ 70). Uninfected `m4-2`, thresholded at 224.8,
reads 0.09%.

Otsu assumes a bimodal distribution. On a slide with almost no KRT5 signal there
is no second mode, so it splits noise. **Per-image adaptive thresholds would
manufacture pods in uninfected controls.** AGER thresholds drift too (527 → 742,
~40%), so AGER needs a fixed value as well.

`IFQ_KRT5_THRESHOLD`, `IFQ_AGER_THRESHOLD` and `IFQ_T1A_THRESHOLD` must be frozen
from blinded control review **before** any batch run. Until then every call is
`adaptive_otsu_exploratory` and must be reported as exploratory.

There is no secondary-only control section for this dataset. The available
biological negative is uninfected alveolar parenchyma, which controls for ectopic
alveolar KRT5 but **not** for airway KRT5. State this as a limitation.

Note also that the KRT5/488 channel was acquired at ~949 ms exposure versus
~0.5–2 ms for the other channels, so autofluorescence in that band is a live
concern and argues for a conservative threshold.

---

## 4b. How the damaged area is computed, and its measured parameters

Healthy alveolus is mostly airspace with thin AT1 membranes, so "pixels below
the AGER threshold" is not the damaged area. Binary closing of the AGER mask was
tried first and **failed**: it reported 61–87% damaged even in uninfected lung,
because at 5.5 µm/px the membranes are largely sub-resolution.

What works is a **local area fraction**: AT1-intact territory is where AGER⁺
pixels occupy at least `cutoff` of an alveolus-sized neighbourhood. Smoothing a
0/1 mask with a Gaussian *is* that local fraction.

Measured on all four pilot slides (detection at 2.76 µm/px):

| slide | AGER in-tissue p50 | AGER⁺% | **damaged%** |
|---|---|---|---|
| het m4-1 **infected** | 304 | 71.6 | **12.4** |
| hom m2 **infected** | 285 | 69.9 | **9.9** |
| het m4-2 *uninfected* | 314 | 80.5 | **2.1** |
| hom m6 *uninfected* | 369 | 95.6 | **1.0** |

at `IFQ_WSI_AGER_THRESHOLD=200`, `IFQ_WSI_DAMAGE_SIGMA_UM=30`,
`IFQ_WSI_DAMAGE_CUTOFF=0.10`.

The **het pair is the internal control**: `m4-1` and `m4-2` have matched AGER
staining intensity (in-tissue p50 304 vs 314) yet separate 12.4% vs 2.1%, so the
separation is not a staining-intensity artifact.

**It is fragile.** At a fixed threshold of 400 the separation vanishes; at 500+
it inverts. With *per-slide adaptive* Otsu it inverts at every one of 15
parameter combinations tested — uninfected read **more** damaged than infected —
because Otsu picked higher AGER thresholds on the uninfected slides (690/864 vs
616/617). Stage 1 therefore refuses to partition without an explicit
`IFQ_WSI_AGER_THRESHOLD`.

**Those values were tuned on the outcome** — they were chosen by looking at how
well infected and uninfected separated, which is exactly the thing the endpoint
is supposed to measure. They have been superseded. See 4c.

## 4c. LOCKED operating point, derived from controls only

Selection rule, **declared before any number was read**:

* Uninfected lung has an intact AT1 sheet, so its damaged fraction *is* the
  detector's **false-positive rate**.
* **Constraint:** worst-of-both control slides ≤ α.
  Worst-of-both, not the mean — one clean slide must not buy tolerance for a
  dirty one.
* **Objective:** subject to that, the **largest** cutoff. Damaged% is monotone
  increasing in cutoff, so the largest admissible cutoff is the most sensitive
  operating point that still meets the specificity floor. Ties break toward the
  flattest local slope.
* The calibration script opens **only** `m4-2` and `m6`. The infected slides are
  never read, so the operating point cannot be tuned on the outcome.

Result (worst-of-both control damaged%, i.e. false-positive rate):

| α | AGER thr | σ (µm) | cutoff | het m4-2 | hom m6 | slope |
|---|---|---|---|---|---|---|
| 0.5% | 150 | 80 | 0.10 | 0.27 | 0.09 | 5.96 |
| **1%** | **150** | **40** | **0.14** | **0.93** | **0.18** | **11.57** |
| 2% | 150 | 20 | 0.18 | 1.71 | 0.22 | 13.66 |
| 5% | 150 | 10 | 0.35 | 4.95 | 0.64 | 22.56 |

Every α level independently selects **AGER threshold 150**, not the 200 used in
4b.

### LOCKED: α = 1% → `AGER_THRESHOLD=150`, `DAMAGE_SIGMA_UM=40`, `DAMAGE_CUTOFF=0.14`

Rationale for α = 1%: a clean specificity spec for "healthy lung reads as
undamaged", and σ = 40 µm is approximately one alveolar diameter — the
physiologically natural neighbourhood for local AT1 coverage. The α = 0.5% point
is more stable (slope 5.96) but needs σ = 80 µm, which averages across 2–3
alveoli and blurs the damaged/intact boundary.

These are now the Stage 1 defaults for σ and cutoff. The AGER threshold remains
**required** rather than defaulted, so that supplying it stays a deliberate act.

### The binding control

`het m4-2` carries roughly 5× the false-positive rate of `hom m6` at every α
(0.93 vs 0.18 at α = 1%). That traces to staining intensity: `m6` has brighter
AGER (in-tissue p50 369 vs 314), so at a fixed threshold it reads as more
intact. Using worst-of-both rather than the mean was therefore load-bearing —
the mean would have let `m6` mask `m4-2`.

### Still outstanding

The infected slides have **not** been measured at the locked parameters. When
they are, that is a genuine held-out readout, not part of the selection. It must
be reported as such.

Validation against hand-drawn outlines on a subset is still required — the
reference method is manual, so manual outlines are the only available ground
truth.

## 4d. The KRT5 numerator: co-negativity, not a bare threshold

### What the reference actually measures

Lin et al. Fig 1G quantifies "percentages of KRT5+ lung areas in total damaged
alveolar areas (PDPN− and KRT5+)". Fig 2A–B quantifies "percentages of
**KRT5+PDPN−** areas in PDPN− and KRT5+ areas" (n = 15 mice/group, two-tailed
Mann-Whitney).

So the numerator is **KRT5+ AND PDPN−**, not bare KRT5+ area. That co-negativity
constraint is not cosmetic — it is what makes the measurement possible on this
dataset.

### Why a bare KRT5 threshold fails here

Measured inside intact parenchyma of the uninfected controls (a compartment with
no pods and no airway basal cells, so any KRT5 signal there is background):

| KRT5 thr | bare KRT5+ false-positive area fraction |
|---|---|
| 200 | 1.20e-3 |
| 400 | 6.0e-4 |
| 500 | 4.5e-4 |

No threshold reaches 1e-4. The background maxes at 3194–3894 (near the 12-bit
ceiling of 4095), and the engine's 50 µm² component filter barely touches it
(4.51e-4 → 4.40e-4), which rules out scattered noise — the false positives are
large, bright, connected objects. That is the signature of autofluorescence,
consistent with the KRT5/488 channel being exposed ~949 ms against ~0.5–2 ms for
the other channels.

Worse, above threshold ~200 the damaged compartment of control slides shows the
*same or less* KRT5 than intact, and by 400 it reads zero: genuine airway basal
cell signal dies before the background does.

### Why co-negativity fixes it

Autofluorescent structures are bright in **every** channel. Genuine dysplastic
pods are KRT5-bright but PDPN-negative. Requiring PDPN− therefore rejects
autofluorescence while retaining real signal.

Enrichment ratio R = P(co-negative | KRT5 bright) / P(co-negative). R ≪ 1 means
KRT5-bright pixels are preferentially co-bright, i.e. the constraint is doing
real work; R ≈ 1 means it is only removing area indiscriminately.

| ceiling t | R_PDPN m4-2 | R_PDPN m6 | R_AGER m4-2 | R_AGER m6 |
|---|---|---|---|---|
| 100 | 0.232 | 0.139 | 0.620 | 0.000 |
| 150 | 0.405 | 0.319 | 0.806 | 2.160 |
| 200 | 0.620 | 0.548 | 0.987 | 0.386 |
| 300 | 0.833 | 0.895 | 1.049 | 0.463 |

### RETRACTED: AGER must NOT be used for co-negativity

An earlier analysis in this session reported that AGER− co-negativity performed
*better* than PDPN−. **That was an artifact and is withdrawn.** "Intact" is
*defined* as AGER-dense territory, so requiring AGER < t removes area by
construction. The enrichment ratio proves it: R_AGER ≈ 1.0 (0.987, 1.049) on
m4-2 and erratic on m6, versus R_PDPN = 0.14–0.62 consistently below 1.

AGER co-negativity also destroys real signal — it discards **100%** of genuine
KRT5+ airway pixels in m6 at every ceiling tested.

Use **PDPN** for co-negativity. AGER stays as the *denominator* marker only.

### The ceiling cannot be set by minimising false positives

That objective is monotone: lower t always looks better, driving t → 0, which
destroys real signal along with background. The controls do supply a positive
control — conducting airway in the damaged compartment is genuinely KRT5+PDPN−:

| ceiling t | airway KRT5 preserved (PDPN) |
|---|---|
| 100 | 0.150 / 0.286 |
| 150 | 0.450 / 1.000 |
| **200** | **0.900 / 1.000** |
| 300 | 1.000 / 1.000 |

At t = 100 — the best-looking value on specificity alone — PDPN− discards
72–85% of genuine KRT5.

**Two-sided rule, still control-only:** maximise specificity subject to
preserving ≥ 90% of genuine airway KRT5.

### PROPOSED (not yet locked): PDPN co-negativity ceiling t = 200

R = 0.55–0.62 (real enrichment) while preserving 90–100% of genuine airway KRT5.

**Not locked, because two checks are outstanding:**

1. Everything above is measured at 2.76 µm/px. Partial-volume mixing at
   pod/AT1 boundaries is worst at that scale, so the constraint may reject real
   pods more than these numbers suggest. It must be re-measured at full tile
   resolution (0.345 µm/px) before freezing.
2. m6's airway positive control is only ~11.8k px, and its KRT5-bright subset is
   a handful of pixels (0.286 ≈ 2/7). It is statistically unusable. The
   recommendation rests on m4-2 alone.

### Parameter count versus control count

Five parameters are now derived from two control animals: AGER threshold, damage
sigma, damage cutoff, KRT5 threshold, and the PDPN ceiling. That is a real
overfitting risk and belongs in any methods section as a stated limitation, not
buried. Additional control animals would do more for confidence here than any
further tuning.

## 5. Stage 1 ROI naming

Fixed. Stage 1 previously named every tile ROI `alveolar_core`, which asserts the
tile contains no conducting airway — mislabelling pure-airway tiles. The default
is now the neutral `parenchyma_core` (matching no compartment keyword), and the
alveolar claim is opt-in via `IFQ_WSI_ROI_COMPARTMENT=alveolar`, to be set only
once airways are actually excluded.

Consequence of the neutral default: AGER and T1A declare
`expectedCompartment:"alveolar"`, so their calls degrade to
`context_unresolved` / `indeterminate`. The KRT5 pod endpoint is unaffected.

---

## 6. Mandatory companions

1. Freeze `IFQ_KRT5_THRESHOLD` from blinded controls first. Nothing about
   ectopic pods is interpretable before this.
2. `IFQ_MIN_INCLUDED_NUCLEI=0` — a small airway region with zero accepted nuclei
   throws inside the region loop and kills the whole tile, alveolar row included.
3. Fix the `alveolar_core` mis-tag above.
4. Pre-register where the airway annotation boundary falls relative to a
   peribronchiolar pod. It materially changes the endpoint. A dilation
   sensitivity analysis (0 / +10 / +25 µm) bounds it honestly, but the primary
   rule must be fixed before unblinding.

---

## 7. Open questions

* Whether AGER and PDPN survive in the injured/consolidated regions of *this*
  dataset. This decides whether any marker-based automation is safe at all.
  Check positive-area fraction in visibly injured versus spared parenchyma on one
  slide.
* No repo-derived size/shape distribution exists for KRT5 components in mouse
  lung sections, so no size/shape pod filter can be tuned yet.
* Airway profile count per section on these slides — not measured.
* The two-ROI RoiSet round trip through `RoiEncoder`/`readRoiFile`, and the
  engine's non-overlap check, are code readings and must be smoke-tested on one
  tile before any batch.
* Stage 2 runtime under a two-region partition: `segmentNuclei` re-thresholds and
  re-watersheds the full tile per region, so tiles containing airway roughly
  double in cost. Measure on the 6-tile pilot.

---

## 9. KNOWN BUG: partition QC columns do not reach mouse level

Found 2026-08-07 by a design audit, then confirmed empirically.

`aggregate_to_mouse.classify_columns()` builds `sum_cols` as a **closed whitelist
of name suffixes** (`aggregate_to_mouse.py:184-186`) and `aggregate_mice()`
writes `rec[]` only from that set. **Every column outside the whitelist is
silently discarded** — no error, no warning, it simply is not in the output
header.

Verified by running `aggregate_to_mouse.py` on the real partitioned slide
summary:

| column | in slide summary | at mouse level |
|---|---|---|
| `damaged_area_um2` | present | **DROPPED** |
| `intact_area_um2` | present | **DROPPED** |
| `damaged_fraction_of_parenchyma` | present | **DROPPED** |
| `KRT5_pod_area_um2_in_intact` | present | **DROPPED** |
| `KRT5_pod_area_frac_of_intact` | present | **DROPPED** |
| `KRT5_pod_area_um2` | present | survives |

### The primary endpoint is NOT affected

`region_area_um2` carries the damaged area when partitioned, and
`<M>_pod_area_um2` matches the whitelist, so pod-area-over-damaged-area survives
and its fraction is recomputed from pooled numerators correctly.

### What is lost

Per-mouse QC. "% of lung damaged" never becomes a mouse-level endpoint, and the
KRT5-in-intact tripwire — the check that would catch a bad damage mask or a bad
KRT5 threshold — stops at slide level and never reaches the group table. That is
exactly the readout most worth having per animal.

### Why the obvious fix does not work

Renaming the columns to match the whitelist is not enough, because every
recomputed area fraction in `aggregate_to_mouse` divides by
`sum(region_area_um2)`. There is only ONE denominator. And `KEY_COLS`
(`aggregate_to_mouse.py:43`) is `[mouse_id, genotype, condition, panel]` — it
does **not** include `region`, so writing damaged and intact as separate `region`
values does not separate them either; they are summed into one mouse row.

### Proposed fix, not yet applied

Emit one row per denominator **scope** rather than one row per slide, using the
only free grouping key: `panel = "<PANEL>@<scope>"`, e.g. `LEFT@damaged` and
`LEFT@parenchyma`. Each row then carries its own `region_area_um2`, and every
fraction is recomputed against the correct denominator, with
`aggregate_to_mouse.py` completely unchanged.

This changes the shape of `slide_level_summary.csv`, so it is a deliberate
decision rather than a patch, and is left for review.

## 8. What this pilot can and cannot claim

n = 1 mouse per group cell (het/hom × infected/uninfected). With n = mice as the
statistical unit, this dataset supports pipeline validation and threshold
freezing. It does not support a group comparison.

Separately: `het` is likely the control rather than a second genotype arm — a
heterozygous *Ifng*⁺/⁻ often retains enough cytokine to signal normally. Confirm
the line before interpreting any pod difference.

A global IFN-γ ligand knockout can also alter viral clearance, so a pod
difference may be an infection-severity difference. NP staining or NP qPCR is
needed to exclude this; no image analysis can substitute for it.
