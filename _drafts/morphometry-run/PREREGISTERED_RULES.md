# Pre-registered decision rules — morphometry module

Written **before** the calibration output was read, for the same reason the AGER
cutoff was locked from controls only: a threshold chosen after seeing the
outcome is not a measurement.

## R1. Tissue-phase channel

`IFQ_MORPH_CHANNELS = 0` (DAPI only).

Panel LEFT is DAPI / KRT5-488 / AGER-555 / PDPN-647. There is **no tissue
counterstain**. AGER (ch 2) is the marker whose density *defines* the compartment
under test, so using it is circular by construction. PDPN (ch 3) is an AT1 marker
with the same biology as AGER — this is exactly the ground on which AGER
co-negativity was retracted from the KRT5 numerator (`docs/ECTOPIC_POD_ENDPOINT.md`
§4d), and the same logic applies here. KRT5 (ch 1) is the endpoint numerator and
is dominated by autofluorescence on this panel (949 ms exposure vs 0.5–2 ms).

DAPI is therefore the only channel independent of the thing being checked.
The consequence is stated in the code header and must be stated in any methods
section: the segmented phase is the **nucleated** phase, not the septal wall, and
every length is an **internuclear** intercept, not an alveolar one.

A PDPN-included variant (`0,3`) is run as a **secondary, explicitly
non-independent** sensitivity analysis only.

## R2. Tissue-phase threshold

`T_DAPI` = arithmetic mean, over the **two uninfected control slides only**
(`het m4-2`, `hom m6`), of the mean in-ROI Otsu threshold across systematically
sampled fine-resolution blocks at the locked fine downsample, **rounded to the
nearest 10**.

The infected slides are not opened by the calibration run.

Rationale: a per-slide adaptive threshold already inverted the damage endpoint
once in this repo. One fixed number, derived from controls, removes that failure
mode. Otsu is used only to *place* the number; it is then frozen.

**Mandatory sensitivity analysis:** every headline result is re-reported at
`0.5·T`, `0.75·T`, `1.5·T`, `2·T`. A damaged-vs-intact contrast that only exists
at one threshold is not a finding.

## R3. Analysis resolution

Locked by a resolution sweep run **before** any compartment comparison, at
ds ∈ {1, 2, 4, 8, 16} on the same windows. The locked fine downsample is the
coarsest one whose airspace fraction, MLI and wall thickness are within **5 %** of
the ds = 1 (native, 0.345 µm/px) value on those windows. Surface density is
excluded from the locking criterion because it is a coastline quantity with no
resolution-independent limit; it is reported at every resolution instead.

The whole four-slide analysis is then also run at the two neighbouring
downsamples so the resolution dependence of every reported number is measured at
slide level, not extrapolated from a window.

## R4. Compartment definition

Reproduced **verbatim** from `scripts/measure_damage_locked.groovy`: ds = 8,
tissue = DAPI blurred σ=2 px, whole-frame Otsu, close r=4; AGER blurred σ=1 px,
threshold 150; local fraction Gaussian σ = 40 µm; damaged = tissue ∧ density <
0.14. Verified to reproduce the published damaged fractions to 2 d.p.

The analysis ROI is that same tissue mask — not the Stage 1 ds-16 recipe —
because the cross-check has to be run on the endpoint's own denominator.

`damaged_core` / `intact_core` are the two compartments eroded by 40 µm (one σ)
to test whether any contrast is an artefact of the smoothed boundary.

## R5. What counts as a positive cross-check

The damaged compartment is *architecturally distinct* only if:

1. the damaged-vs-intact contrast is present in the **infected** animals, **and**
2. it is materially **larger** than the same contrast measured in the
   **uninfected controls**, where the "damaged" compartment is by construction
   almost entirely false positives and non-alveolar structure (0.93 % / 0.18 %
   of tissue), **and**
3. it survives the threshold sensitivity of R2 and the core erosion of R4.

Condition 2 is the one that makes this a test rather than a tautology. If the
damaged compartment differs from intact just as much in a control lung, the
architecture is tracking *anatomy* (airway walls, vessels, pleura — all
AGER-negative) rather than *injury*, and the cross-check has failed.

## R6. Reporting stance

- Infected vs uninfected morphometry is **descriptive, not held out**: the
  controls were used to lock the damage detector *and* the tissue threshold.
- n = 1 animal per group. Nothing here supports an inferential claim.
- MLI from a single 2-D section is a biased estimator of the 3-D quantity;
  the caveats are reported with the number, not in a footnote.
