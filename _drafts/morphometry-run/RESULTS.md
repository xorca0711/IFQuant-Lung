# Morphometry results — four real slides

Analysis resolution ds 4 = **1.380 µm/px** (whole-slide, every ROI block).
Tissue phase = DAPI ≥ **880** (control-derived, pre-registered rule R2).
Compartments = the **locked** AGER damage detector, reproduced verbatim.
Raw: `out_ds4/`, `out_ds4/stats_ds4/`.

---

## 0. The compartment definition reproduces the locked endpoint exactly

| slide | group | published damaged % | this module | tissue mask |
|---|---|---|---|---|
| het m4-1 | PR8 infected | 6.71 % | **6.71 %** | 58.474 mm² |
| hom m2 | PR8 infected | 4.68 % | **4.68 %** | 70.196 mm² |
| het m4-2 | uninfected | 0.93 % | **0.93 %** | 54.827 mm² |
| hom m6 | uninfected | 0.18 % | **0.18 %** | 50.381 mm² |

Every number below is measured **inside those exact compartments**, on channels
that never touch AGER.

`morph_finepass_coverage_of_compartment = 1.000` on all 20 rows: the fine pass
visited exactly the compartment area the coarse grid defined, so the
coarse→fine mapping is exact and nothing was silently skipped.

---

## 1. Per-slide values, whole parenchyma (damaged + intact pooled)

| metric | het m4-1 **INF** | hom m2 **INF** | het m4-2 *ctl* | hom m6 *ctl* |
|---|---|---|---|---|
| parenchyma area (mm²) | 58.474 | 70.196 | 54.827 | 50.381 |
| nucleated area fraction | 0.2204 | 0.1979 | 0.1817 | 0.2149 |
| internuclear intercept, direct (µm) | 16.10 | 17.33 | 18.78 | 17.18 |
| intercept, indirect 2L/N (µm) | 26.82 | 27.84 | 29.69 | 26.59 |
| in-plane anisotropy (max/min) | 1.062 | 1.064 | 1.056 | 1.048 |
| airspace width 4·mean(EDM) (µm) | 12.08 | 12.92 | 12.58 | 11.68 |
| airspace width 4·median (µm) | 9.11 | 9.35 | 9.43 | 9.23 |
| nucleated-object thickness 4·mean(EDM) (µm) | 5.356 | 4.860 | 4.704 | 4.955 |
| thickness 2A/B (µm) | 3.750 | 3.493 | 3.424 | 3.627 |
| surface density S_V (1/µm) | 0.14968 | 0.14422 | 0.13515 | 0.15091 |
| chords truncated | 47.2 % | 44.8 % | 52.6 % | 41.3 % |

**Whole-lung morphometry does not separate infected from uninfected.** Nucleated
fraction and intercept overlap completely across the four animals. That is
expected — only 4.7–6.7 % of the infected lung is called damaged — and it is
also the reason the whole-lung numbers are not the interesting readout.

*Infected vs uninfected here is **descriptive, not held out**: both controls were
used to lock the damage detector and to lock the DAPI threshold.*

---

## 2. THE CROSS-CHECK — damaged vs intact, within each animal

Paired within one section, so free of between-animal staining and inflation
differences.

### Sample sizes and reliability

| animal | compartment | area (mm²) | untruncated chords | truncated |
|---|---|---|---|---|
| het m4-1 INF | damaged | 3.923 | 376 225 | 50.6 % |
| | intact | 54.551 | 5 211 578 | 46.9 % |
| hom m2 INF | damaged | 3.284 | 167 570 | **72.7 %** |
| | intact | 66.913 | 6 438 128 | 43.7 % |
| het m4-2 ctl | damaged | **0.512** | 23 716 | **83.9 %** |
| | intact | 54.315 | 4 417 562 | 51.4 % |
| hom m6 ctl | damaged | **0.090** | 2 428 | **93.6 %** |
| | intact | 50.291 | 5 097 318 | 40.9 % |

Read this table before the next one. The control damaged compartments are
**0.51 and 0.09 mm²** — 7× and 40× smaller than the infected ones — and 84 % /
94 % of their chords touch the ROI boundary and are discarded. **Chord-derived
statistics for the control damaged compartment are not reliable.** The areal
statistics (nucleated fraction, airspace fraction, surface density) do not use
chords and are reliable everywhere; those are what the verdict rests on.

### Signed contrast, log2(damaged / intact)

| metric | het m4-1 **INF** | hom m2 **INF** | het m4-2 *ctl* | hom m6 *ctl* | infected vs control separated? |
|---|---|---|---|---|---|
| **nucleated area fraction** | **−0.264** | **−0.763** | **+0.102** | **−0.017** | **YES** |
| **airspace fraction** | **+0.068** | **+0.143** | **−0.024** | **+0.005** | **YES** |
| **surface density S_V** | **−0.023** | **−0.381** | **+0.109** | **+0.207** | **YES** |
| intercept, direct | +0.071 | +0.156 | −0.659 | −1.174 | YES (chords unreliable in ctl) |
| intercept, indirect | +0.026 | +0.384 | −0.101 | −0.194 | YES |
| airspace width, mean | −0.034 | +0.320 | −0.361 | −1.048 | YES |
| airspace width, median | −0.018 | +0.033 | −0.669 | −1.303 | YES |
| thickness 2A/B | −0.241 | −0.382 | −0.006 | −0.224 | knife-edge (−0.241 vs −0.224) |
| thickness 4·mean(EDM) | −0.094 | −0.349 | −0.060 | −0.447 | no |
| thickness 4·median | −0.078 | −0.125 | −0.018 | −0.147 | no |
| in-plane anisotropy | +0.028 | +0.086 | +0.241 | +0.052 | no |

As percent change (damaged relative to intact):

| metric | het m4-1 INF | hom m2 INF | het m4-2 ctl | hom m6 ctl |
|---|---|---|---|---|
| nucleated area fraction | **−16.7 %** | **−41.1 %** | +7.4 % | −1.2 % |
| airspace fraction | +4.8 % | +10.4 % | −1.6 % | +0.3 % |
| surface density S_V | −1.6 % | −23.2 % | +7.8 % | +15.4 % |
| intercept, direct | +5.1 % | +11.4 % | −36.7 % | −55.7 % |
| airspace width, mean | −2.3 % | +24.8 % | −22.1 % | −51.6 % |
| airspace width, median | −1.3 % | +2.3 % | −37.1 % | −59.5 % |
| wall thickness 2A/B | −15.4 % | −23.3 % | −0.4 % | −14.4 % |

---

## 3. VERDICT

**The damaged compartment is architecturally distinct from the intact
compartment, and the distinction is specific to infection — but the specificity
lives in the SIGN of the contrast, not its size, and in one of the two infected
animals the effect is confined to cellularity.**

Point by point.

**3.1 The contrast exists, and it points the opposite way in health and in
disease.** In both infected animals the AGER-poor territory has *fewer nuclei per
unit area, more airspace, less boundary length per unit area, and longer
intercepts* than the intact territory of the same lung. In both uninfected
controls the very same detector selects territory with the *opposite*
architecture: equal-or-more nuclei, more boundary, and much shorter intercepts.
Eight of eleven metrics separate the two infected animals from the two controls
with **no overlap** in signed contrast. This is the result the module was built
to produce, and it is a **pass**: the denominator is not merely a staining
artefact.

**3.2 What the controls' "damaged" compartment actually is.** Higher nucleated
fraction, higher surface density, much shorter intercepts, much smaller
airspaces — that is the signature of dense non-alveolar structure: conducting
airway walls, vessel walls, pleura, lymphoid tissue. All are AGER-negative, and
`docs/ECTOPIC_POD_ENDPOINT.md` §2 already says so. The 0.93 % / 0.18 % control
"false-positive rate" is therefore not random noise; it is **anatomy**, and it
is architecturally identifiable. That is useful: it means an airway/vessel
exclusion step would attack a compartment with a measurable morphometric
signature rather than an invisible one.

**3.3 The one thing that would have falsified the denominator did not happen.**
There is a construction-level coupling to worry about: AGER and DAPI both sit on
alveolar septa, so an AGER-poor neighbourhood might be DAPI-poor for purely
geometric reasons, with no injury involved. If that were driving the result, the
*controls* would show it too. They do not (+7.4 %, −1.2 % nucleated fraction).
So the coupling is not the explanation.

**3.4 Where it is weak, stated plainly.**

* **n = 2 per group, and the two infected animals disagree in magnitude by ~2.5×.**
  het m4-1: −16.7 % nucleated fraction, −1.6 % surface density, +5.1 % intercept.
  hom m2: −41.1 %, −23.2 %, +11.4 %. Nothing here supports an inferential claim.
* **In m4-1 the damaged compartment is hypocellular, not architecturally
  distorted.** Its airspace width is unchanged to within 2.3 % on the mean and
  1.3 % on the median, and its surface density is unchanged to within 1.6 %. Only
  the nuclear content differs. "Fewer nuclei in the same alveolar geometry" is a
  real, AGER-independent difference, but it is *not* septal destruction. Note
  that m4-1 is the animal with the **larger** damaged fraction (6.71 % vs 4.68 %),
  so damaged *extent* and architectural *severity* do not track each other here.
* **In m2 the airspace enlargement is tail-driven.** Mean airspace width +24.8 %
  but median only +2.3 %: the excess sits in a small number of large voids, not
  in a general widening. A handful of large voids is what a bronchiolar or
  vascular lumen looks like, so part of that signal may be lumen rather than
  destroyed parenchyma.
* **The control damaged compartments are too small and too truncated to
  adjudicate the chord-based metrics** (0.09 mm², 94 % truncation for m6). The
  verdict rests on the areal metrics, which do not use chords.
* **The measured phase is nuclei.** "Reduced tissue fraction" means reduced
  *nuclear area fraction*. Confirming that the septal *network* is simplified
  needs a tissue counterstain — a serial H&E or Masson section. See
  `STEREOLOGY_CAVEATS.md`.

**3.5 What would settle it.** One brightfield serial section through the same
block, run through the same code in `IFQ_MORPH_MODE=brightfield` (the draft's
untested path), would replace the nuclear mask with a true tissue/air mask and
turn "hypocellular" into a defensible statement about septal architecture. That
is a one-slide experiment.

---

## 4. Compartment geometry — the damaged region is compact, not a boundary film

Splitting each compartment by **distance to the other compartment** (40 µm = one
damage-detector σ) gives, on het m4-1:

| | edge (within 40 µm of the other compartment) | core (further than 40 µm) |
|---|---|---|
| damaged | 179 182 coarse px (34.8 %) | **335 769 px (65.2 %)** |
| intact | 237 182 px (3.3 %) | 6 924 122 px (96.7 %) |

So **65 % of the AGER-damaged territory is more than 40 µm from any intact
tissue** — the damaged compartment is a set of genuinely compact patches at least
~80 µm across, not a thin film smeared along a smoothed boundary. That matters
for the endpoint: it means the σ = 40 µm Gaussian is not simply manufacturing a
boundary zone, and it makes the compartment comparable in kind (if not in
delineation) to the hand-drawn damaged alveolar areas of the reference method
(Lin et al. 2024).

The `*_core` contrasts are therefore a real sensitivity analysis rather than a
noise floor, and where the contrast survives restriction to the cores it is not a
boundary-mixing artefact.

> **A defect found and fixed during this work.** The first implementation defined
> the core by *eroding the compartment itself*. On this material that deletes
> 88–99 % of **both** compartments, because the analysis ROI is a lacy septal
> network only a few coarse pixels wide — plain erosion measures septal width,
> not distance to the compartment boundary. The corrected definition
> (subtract a dilation of the *opposite* compartment; non-ROI territory stays
> neutral, because being near the pleural surface is not being near intact
> tissue) is what produces the table above. The ds 4 run predates the fix, so its
> `*_core` rows are not interpretable; its `damaged`, `intact` and `parenchyma`
> rows are unaffected, because those are sums over the partition and the
> partition itself was always correct.
