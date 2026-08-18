# Negative results — markers tested and found not to discriminate

> **Status: VALIDATED.** Every entry here is a test that was run against real
> data, with the numbers that produced the verdict. Sections 1 and 2 are marker
> rejections; section 3 is a specification error. Data:
> `D:\IFQ_Runs\confocal_260808_fixed` unless stated otherwise.
> Last checked: 2026-08-08.

A record of markers that were evaluated as discriminators and **failed**, with the
measurement that killed each one.

This file exists because a negative result that is not written down gets
re-derived. Both entries below were reached by the same test and produced the
same signature, and the second was only recognised quickly *because* the first
was on record.

**The test.** Lock an operating point from the uninfected controls alone
(worst-of-both, so neither animal sets a permissive cut), apply it to the
held-out infected animals, and compute

```
R = mean(infected fraction beyond the cut) / mean(control fraction beyond the cut)
```

R ≈ 1 means the marker separates nothing: the infected animals sit where the
controls' own tail already predicted. A discriminating marker gives R far from 1
across a range of cuts, not at one lucky value.

---

## 1. AGER as a co-negativity marker — RETRACTED

**Claim tested.** That KRT5⁺AGER⁻ isolates dysplastic tissue, i.e. that AGER
absence marks damaged alveolus.

**Result.** R ≈ **0.99–1.05**. Indiscriminate. PDPN/T1α gave R = 0.14–0.62 on the
same test, which is why PDPN was retained at the time.

**Why it failed.** The removal was *definitional*, not biological: "intact" was
already defined by AGER density, so requiring AGER-negativity removed area by
construction and the enrichment test exposed it. Circularity, measured.

**Status.** Retracted. AGER must not be reintroduced as a co-negativity marker.
See `docs/ECTOPIC_POD_ENDPOINT.md` §4d.

**Note added later.** The retraction was correct but the framing was wrong.
Lin et al. never used AGER in this endpoint at all, and the co-*negativity*
question itself was misposed — see §3 below.

---

## 2. KRT8 as an infected/uninfected discriminator — NEGATIVE

**Claim tested.** That KRT8-high marks the transitional/DATP state and so
separates infected from uninfected alveolar epithelium. The operator's
requirement was that KRT8 be *strict*, i.e. not label normal alveolar regions.

**Data.** `D:\IFQ_Runs\confocal_260808_fixed` (post-`blackBackground`-fix run, so
the per-cell segmentation is sound). RIGHT panel, per-cell `KRT8_mean`, which is
the **perinuclear cytoplasmic ring** at 2 µm expansion — the correct compartment
for a cytoplasmic keratin, so this is not an artefact of measuring the nucleus.

Per-cell KRT8:

| mouse | condition | n cells | p50 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| M2 | PR8 | 20,501 | 105.9 | 444.1 | 558.8 | 831.3 |
| M4-1 | PR8 | 22,106 | **75.7** | **309.6** | **388.8** | **556.5** |
| M4-2 | uninfected | 3,946 | 78.2 | 401.9 | 513.9 | 745.9 |
| M6 | uninfected | 6,929 | 95.8 | 384.7 | 492.4 | 717.0 |

Control-locked cut, applied to held-out infected:

| control quantile | cut | M2 | M4-1 | M4-2 | M6 | **R** |
|---|---|---|---|---|---|---|
| p90 | 401.9 | 0.126 | 0.044 | 0.100 | 0.091 | **0.89** |
| p95 | 513.9 | 0.066 | 0.014 | 0.050 | 0.042 | **0.88** |
| p99 | 745.9 | 0.017 | 0.002 | 0.010 | 0.008 | **1.07** |
| p99.5 | 824.5 | 0.011 | 0.001 | 0.005 | 0.005 | **1.25** |
| p99.9 | 1203.0 | 0.0010 | 0.0001 | 0.0008 | 0.0006 | **0.80** |

**Result.** R = **0.80–1.25 at every operating point**. No enrichment anywhere.
No threshold on KRT8 separates these groups.

The engine's own adaptive call agrees and shows why it looked plausible: KRT8⁺
fractions were 21.6% / 19.9% infected against 18.0% / 18.8% uninfected, with
**~80% of cells indeterminate**. An adaptive threshold on a constitutively
expressed marker cannot detect a shift — it moves with the data. That is the
same trap that made KRT5 uncalibratable on the slide scanner.

**The diagnostic detail.** Look at the ordering, not just the ratio: **M4-1
(infected) is below BOTH controls at every percentile**, while M2 (infected) is
above both. The infected animals *bracket* the controls rather than exceeding
them. So **between-section staining variance exceeds the between-condition
biological signal**. M4-1's whole distribution is shifted down — a section-level
offset, the same failure mode as the M6 LEFT AGER staining failure identified by
the PI, now appearing on the RIGHT panel.

**What this does NOT establish.** It does not show DATP cells are absent. It
shows that with two animals per condition and this much section-to-section
variance, KRT8 cannot demonstrate them. What would change the conclusion, in
order of cost:

1. **More animals** — between-section variance averages out with n; the
   reference (Lin et al.) used n = 15 per group.
2. **A within-section normaliser** — ratio KRT8 to a channel assumed stable
   across sections. Trades one assumption for another and must be justified.
3. **Staining consistency** — a bench fix, and the thing both this and the M6
   LEFT AGER failure are pointing at.

**Status.** KRT8 is not a usable discriminator in this batch. `run_summary.csv`
labels it `adaptive_otsu_exploratory`, which understates the position: the marker
was tested against controls and did not separate.

**Reproduce.** `scripts/krt8_operating_point.py` against
`D:\IFQ_Runs\confocal_260808_fixed\analysis`.

---

## 3. Co-negativity as the endpoint's form — SUPERSEDED

Not a marker failure but a specification error, recorded here because it
invalidates the framing of §1.

The endpoint was implemented as KRT5⁺PDPN**⁻** over a computed damaged area. The
reference says the opposite. Verbatim, Lin et al. 2024
(J Clin Invest 134(19):e176828):

- Fig 2A–B: "percentages of **KRT5⁺PDPN⁺** areas in PDPN⁻ and KRT5⁺ areas"
- Fig 2E–F: "dysplastic cells (**KRT5⁺ PDPN⁺**) and AT1 (PDPN⁺) cells"
- Methods: "To quantify KRT5⁺ **or** PDPN⁻ area … measured using **outline
  spline** in … Axiovision 4.8"

PDPN is expressed by basal/dysplastic cells as well as AT1, so it does not
discriminate by absence — requiring PDPN-negativity excluded the population being
measured. The denominator is a **hand-traced union of regions** over a whole-lobe
mosaic, not a per-pixel mask, which also means the reference endpoint is a
whole-slide measurement that sampled confocal fields cannot reproduce.

Corrected spec: `config/endpoints/dysplastic_over_damaged.json`.

**Lesson, and it applies to §1 too.** The enrichment test in §1 was executed
correctly and answered a question the reference never asked. A measurement can be
sound and still be pointed at the wrong quantity — check the primary source
before calibrating against it, not after.

---

## 4. The AGER intact/damaged split as a KRT5 negative — NEGATIVE

**Claim tested.** That `scripts/calibrate_krt5_controls.groovy` could re-derive the
KRT5 cutoff on whole-slide `.vsi` data, so that a section-level KRT5 area
measurement could be compared against the sampled-confocal figures.

**Run.** The repo script unchanged except for the `DIR` constant
(`D:/Confocal_Images/20260806_CW/` → `D:/Microscopy_Images/20260806_CW_Slidescanner/`),
QuPath 0.7.0, `DS = 8` → 2.760 µm/px, on the two uninfected slides only. Exit 0.
Infected slides were never opened.

**Result: no admissible threshold exists.**

| α | selected | intact FP (worst) | damaged (worst) |
|---|---|---|---|
| 1e-5, 5e-5, 1e-4 | *none in sweep* | — | — |
| 5e-4 | 500 | 4.40e-4 | **0.000000** |
| 1e-3 | 250 | 9.58e-4 | 9.31e-4 |

The lowest intact false-positive fraction anywhere in the 40–600 sweep is
**2.99e-4** at thr 600, so the constraint is unsatisfiable at 1e-4 and tighter.
Where the constraint *is* satisfiable the script's own sanity check fails: the
DAMAGED fraction crosses below INTACT at **thr 200** (0.001185 vs 0.001189) and is
exactly zero from 400 up. Sanity needs thr ≤ 175; the constraint needs thr ≥ 250.
The windows are disjoint at every α, not only the tabulated ones — admitting
thr 150 would require α ≥ 2.3e-3.

**Observation, established by overlay. Mechanism NOT established.** The script's premise is
that AGER-negative airway epithelium lands in DAMAGED, keeping genuine airway
basal-cell KRT5 out of the negative. Rendering the compartment mask at the locked
parameters shows the opposite:

> **100.0 % (M4-2) and 99.7 % (M6) of surviving KRT5⁺ area lies inside INTACT** —
> the declared negative. DAMAGED holds 1 and 9 pixels respectively.

**Corrected 2026-08-18.** An earlier version of this entry asserted the mechanism:
that the 40 µm blur (14.5 px at 2.760 µm/px, comparable to a bronchiolar wall)
floods the airway mask with AGER density from surrounding alveolar sheet, so the
negative contains the airway. **That mechanism is not established, and two
subsequent tests failed to support it.**

- A geometry sweep over `AGER_THR` × σ × cutoff found parameters giving a genuinely
  reproducible AGER-poor compartment (coherence 0.99–1.00 on both controls, spread
  1.01), but **no candidate captured the control KRT5**: the best case still left
  72.7 % (M4-2) and 99.0 % (M6) of it in the negative. The compartment is real and
  the KRT5 is simply not in it.
- Zoom crops of the largest control KRT5⁺ components show thin smooth unbranched
  ribbons following the tissue contour, with no lumen and no epithelial wall. That
  is not airway morphology. A follow-up erosion test then also refuted the
  edge-artefact reading: eroding 10 µm inward removed 12 % of tissue but **retained
  96 % of the KRT5**, so the signal is not boundary-bound either. It sits in a band
  roughly 20–80 µm inside the contour, and even discarding 76 % of the tissue leaves
  the false-positive fraction at 1.85e-4, still above target.

What is established is the failure itself: at 948.7 ms FITC exposure the control
KRT5 channel carries bright objects that no AGER-based compartment isolates and no
erosion margin removes at tolerable cost. **What those objects are remains
unidentified.** The airway explanation and the edge-artefact explanation were both
asserted here and both are withdrawn.

**Why re-deriving the damage parameters does not fix it.**
`scripts/calibrate_damage_controls.groovy` declares the control damaged fraction
to be the detector's *false-positive rate* and constrains it to α ∈ {0.5, 1, 2,
5} %. But in an uninfected lung the dominant AGER-negative structure **is** the
conducting airway. Minimising damaged % in controls therefore actively excludes
airways from the damaged compartment — the exact behaviour that breaks the KRT5
negative. The two calibrations impose contradictory requirements on one
compartment. The observed 0.93 % and 0.18 % already sit inside the loosest α, so
the damage detector is behaving as calibrated; it is fit for its own purpose and
unfit for this one.

**What this does not show.** The endpoint is not infeasible. The compartment
definition failed, not the assay. Nothing here touches the infected animals or the
settled confocal release.

**Also established.**

- **Threshold 300 does not transfer across modalities.** Slide-scanner intact
  background is p99.99 = 819 / 689 against 283 / 255 on confocal (~2.8×). At 300
  the intact false-positive fraction here is 8.17e-4, not ≤ 1e-4. The on-modality
  equivalent is ~850–1000 by two independent routes (background ratio, and
  log-interpolation of the sweep to α = 1e-4).
- **`mean + 5 sd` is not a usable fallback** — 149 and 139, below p99.9 (243, 188).
  This background is non-Gaussian and dominated by discrete bright objects.
- **The AGER classifier is not ready to be wired into the engine.** It had been
  proposed as the fix for `compartment = unassigned` in the settled release. At
  these parameters it labels 99.07 % and 99.82 % of tissue intact and finds
  essentially nothing else; wiring it in would produce authoritative-looking
  labels that mean nothing, which is worse than the honest `unassigned`.

**Lesson.** A two-compartment model (intact / damaged) cannot serve a lung that
has three relevant states: intact alveolar parenchyma, conducting airway, and
genuine damage. Airway is not damage, but it is not intact alveolus either, and
collapsing it into one of the two poisons whichever compartment receives it.

Artefacts: `scratchpad/calib/calibration_log.txt`,
`scratchpad/calib/overlay/*_compartment_overlay.png`.

---

## 5. The M2 vs M4-1 KRT5 ranking — NOT ESTABLISHED

**Claim demoted.** The report states KRT5-positive area of 14.11 % (M2, IFN-γ-KO
homozygous, PR8) against 11.98 % (M4-1, heterozygous, PR8): M2 > M4-1 by 2.13
percentage points. **That ordering is not established, and neither is its reverse.**

**What prompted the check.** Whole-section overviews suggested to the reviewer that
M4-1 carried the more extensive KRT5, opposite to the published ordering. The
published figures come from ten reviewer-*selected* confocal fields per mouse
covering 1.32 mm² (M2) and 1.47 mm² (M4-1) — about 1/86th of the imaged section
footprint — so a section-scale disagreement was a live possibility.

**Test performed.** A threshold-dominance curve on all four whole slide-scanner
sections, DS = 8, tissue = Otsu on DAPI, KRT5⁺ area after the engine's 50 µm²
component filter, over thresholds 150–1200. No threshold was selected from any
slide, so there was nothing to tune.

| threshold | M2 | M4-1 | M2 − M4-1 |
|---|---|---|---|
| 150 | 8.04 % | 10.97 % | −2.93 |
| 300 | 4.93 % | 5.55 % | −0.62 |
| 400 | 3.63 % | 3.60 % | +0.02 |
| 800 | 1.14 % | 0.69 % | +0.45 |
| 1200 | 0.37 % | 0.14 % | +0.23 |

**Three reasons the ranking cannot be reported.**

1. **No threshold-invariant ordering exists.** The curves cross at ≈ 400. The swing
   in M2 − M4-1 across plausible thresholds is **2.93 pp**, larger than the 2.13 pp
   effect in dispute. The operating point moves the answer by more than the claimed
   biology does.
2. **At the intensity-matched operating point the whole section agrees with the
   published direction.** Threshold 300 does not transfer between modalities (§4);
   the slide-scanner equivalent is ~850–1000. There M2 exceeds M4-1 by 0.34–0.45 pp
   (ratios 1.65–2.11). The apparent reversal exists only *below* the operating
   point, in the regime §4 shows to be background-dominated on this modality.
3. **A monotone intensity transform reproduces the crossover with no biology.**
   Fitting implied scale against threshold gives a compressive power law, γ ≈ 0.74,
   which forward-predicts M4-1's whole curve from M2's to within 10–15 % of area
   across 150–1200. Note this also *excludes* a pure staining-intensity difference:
   under exact multiplicative scaling `A₄₁(t) = A₂(t/k)`, and scaled curves can
   never cross. Something compressive — section thickness, focal plane, optical
   sectioning — is not excluded.

**What the two animals do differ in.** Not a scalar amount, but the *shape* of the
KRT5 intensity distribution. M4-1 carries more dim-and-above signal (in-tissue FITC
p90 = 170 vs 102); M2 carries more bright signal (p99 = 867 vs 728, p99.99 = 2455
vs 1977). The reviewer's visual impression of greater extent in M4-1 is consistent
with the p90 difference. That is a real feature of the data and it is **not** the
same claim as "more pod area".

**A staining gate that could not work.** This entry originally proposed comparing
the control-vs-control FITC p99 ratio (1.12) against infected-vs-infected (1.19) as
a stop/go test. It is invalid on three counts: it is computed from the same
distribution it was meant to validate; n = 2 controls yields one ratio with df = 0,
a single draw and not a variance estimate; and the two ratios measure different
physical quantities, since control p99 sits in pure background (0.08 % KRT5⁺ area)
while infected p99 sits inside real signal (4.93 %). It is recorded here so it is
not reused.

**Not affected.** The infected-versus-uninfected separation is robust — roughly four
orders of magnitude, and insensitive to threshold across the whole sweep. Nothing
here revises the settled confocal release; it revises what the 14.11 / 11.98 figures
can be claimed to represent.

**What would settle it, and why it was not pursued.** A per-slide internal
normaliser built from airway-basal KRT5 — constitutive, genotype-independent,
present on all four sections — re-expressing every threshold in multiples of that
slide's own airway-basal intensity. If the crossover vanishes it is staining; if it
survives it is biology. It needs no new imaging. The obstacle is that airway basal
cells are trivially identifiable in the controls (they are ~100 % of control KRT5)
but must be separated from pods in the infected sections, and the AGER-compartment
route for that separation is the failure recorded in §4. Independently, with n = 1
per genotype × condition cell, no genotype-level claim is available at any
threshold under this project's own rule that the statistical unit is the mouse.

Artefacts: `scratchpad/calib/` (dominance curve, geometry sweep, partition test,
erosion test, overlays), `scratchpad/pipeline/state.json` (gate `G0` = FAIL, with
the recorded reason that native-resolution tiling would not resolve this).
