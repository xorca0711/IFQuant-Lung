# What these numbers can and cannot be claimed to mean

All literature below was retrieved via **PubMed** and each record was checked to
exist (PMID + DOI verified, abstract read).

---

## 1. The panel-specific caveat, which dominates everything else

Panel LEFT is DAPI / KRT5-488 / AGER-555 / PDPN-647. **There is no tissue
counterstain.** The segmentable, AGER-independent signal is DAPI, so the phase
this module measures is the **nucleated** phase and its complement is *not*
alveolar airspace — it is airspace **plus** every anuclear stretch of septal
wall, matrix and capillary lumen.

Consequences, in order of severity:

1. **The chord lengths are internuclear intercepts, not alveolar intercepts.**
   They are not comparable to any published MLI and must never be reported as
   "MLI" without that qualifier. Measured values here are ~13–24 µm; published
   mouse alveolar chord lengths are ~35–45 µm (§3). The two numbers are not
   measuring the same distance and the smaller one is not "a shrunken alveolus".
2. **"Wall thickness" is nuclear-cluster thickness.** 4·mean(EDM) over a DAPI
   mask measures how thick the nucleated objects are (~3.5–4.5 µm at ds 2,
   consistent with a nucleus, not with a 2–5 µm alveolar septum measured
   end-on).
3. **Airspace topology is not measurable at all.** Nuclei do not form a
   continuous barrier, so connected-component labelling floods: 86–92 % of
   airspace area sits in one confluent component. Every connectivity-derived
   column is gated off (`morph_connectivity_interpretable = false`).
4. **What *is* valid** is the comparison of the same construct between
   compartments and between animals: nuclear area fraction, internuclear
   intercept, nucleated-object thickness and boundary density are well-defined,
   reproducible, additive descriptors of tissue architecture, and they are
   independent of AGER. That independence is the entire point of the
   cross-check.

The fix, if true alveolar morphometry is wanted, is a **serial H&E / Masson
section** where the airspace is the white background and optical density is a
genuine tissue/air discriminator. The draft's brightfield path exists for this
and remains untested — no brightfield slide was available.

---

## 2. Why MLI from a single 2-D section is a biased estimator of the 3-D quantity

* **Orientation.** The direct chord estimate is unbiased for `4·V_air/S` only
  under **isotropic uniform random** test lines through an isotropic structure.
  A single section plane through an anisotropic lung is neither. This module
  measures four in-plane orientations (0°, 45°, 90°, 135°) with equal weight and
  publishes `morph_mli_anisotropy_ratio`; that detects **in-plane** anisotropy
  only. Anisotropy *out of* the section plane is invisible to any 2-D method and
  is not corrected here. The standard remedy is isotropic uniform random (IUR) or
  vertical-uniform-random sectioning at cutting time, which this material did not
  receive.
* **Section thickness / overprojection.** A section of finite thickness
  superimposes structure through its depth (the Holmes effect). Thin septa are
  over-represented and small airspaces are filled in, which shortens chords and
  inflates the tissue fraction. Section thickness is not recorded for this
  dataset and no correction is applied.
* **Inflation.** This is the single largest determinant of MLI and it is not a
  measurement artefact but a physiological one. Knudsen et al. state it plainly:
  L_m *"is not a robust parameter of internal lung structure because it crucially
  depends on lung volume"* — inflation pressure changes L_m directly, and in
  emphysema models part of the apparent change is altered recoil rather than
  altered architecture. Inflation pressure and fixation protocol are not recorded
  for this dataset. **Absolute values are therefore not comparable to any other
  laboratory's**, and only the within-animal, within-section compartment contrast
  is defensible.
  (Knudsen L, Weibel ER, Gundersen HJG, Weinstein FV, Ochs M. *Assessment of air
  space size characteristics by intercept (chord) measurement.* J Appl Physiol
  2010;108(2):412–21. [DOI](https://doi.org/10.1152/japplphysiol.01100.2009),
  PMID 19959763.)
* **Reference-volume / reference-trap.** A ratio like "airspace fraction" changes
  if the reference compartment changes, independently of the structure. Here the
  reference is the damage detector's own tissue mask, which itself depends on a
  DAPI Otsu threshold that varies slide to slide (264.5–305.7). Between-animal
  comparisons inherit that variation; within-animal compartment contrasts do not,
  because both compartments share one mask.
* **Direct vs indirect.** The indirect (Dunnill/Thurlbeck) estimator `2L/N`
  includes the wall thickness it crosses and clips chords at field edges. Madi et
  al. quantify both: the indirect method *"consistently overestimated MLI due to
  Septa Bias and Partial Chord Bias"*, and the direct method has lower standard
  error and is far less sensitive to guideline length.
  (Madi A, Politis DA, Salsabili S, Chan ADC. *Automated mean linear intercept
  measurement: quantifying bias and parameter sensitivity in lung morphometry.*
  Physiol Meas 2025;46(7). [DOI](https://doi.org/10.1088/1361-6579/adf0bd),
  PMID 40669490.) Both are reported here; the direct one is primary. Measured
  ratio indirect/direct on this data: **1.36–1.55**, consistent with their
  finding.
* **Truncated chords.** Excluding chords not bounded by tissue on both sides
  removes Partial Chord Bias but introduces the opposite one: long chords are
  more likely to touch the ROI boundary, so the surviving population is biased
  short. On this data **25–53 %** of chords are truncated, mostly at the ragged
  boundary of the parenchyma mask. That is a large fraction and it is why both
  `morph_chord_truncated_fraction` and
  `morph_chord_truncated_length_fraction` are reported alongside every MLI.
* **Resolution.** Boundary length has no resolution-independent limit (the
  coastline problem), so surface density S_V is defined only relative to a stated
  pixel size. Measured here: S_V falls 12–20 % from ds 1 to ds 2 and ~50 % from
  ds 1 to ds 8. It is reported at every resolution and never compared across
  resolutions.
* **Design standards.** The methodological requirements for all of the above
  (sampling design, reference volume, isotropy, section thickness) are set out in
  Hsia CCW, Hyde DM, Ochs M, Weibel ER. *An official research policy statement of
  the ATS/ERS: standards for quantitative assessment of lung structure.* Am J
  Respir Crit Care Med 2010;181(4):394–418.
  [DOI](https://doi.org/10.1164/rccm.200809-1522ST), PMID 20130146.

---

## 3. Expected values for mouse lung, and how far this is from them

The only directly comparable published mouse numbers are airspace **chord
lengths** measured on fixed, inflated lung:

| strain | mean chord length | source |
|---|---|---|
| C3H/HeJ | 45 ± 5 µm | Soutiere et al. 2004 |
| A/J | 38 ± 2 µm | Soutiere et al. 2004 |
| **C57BL/6J** | **35 ± 3 µm** | Soutiere et al. 2004 |

Soutiere SE, Tankersley CG, Mitzner W. *Differences in alveolar size in inbred
mouse strains.* Respir Physiol Neurobiol 2004;140(3):283–91.
[DOI](https://doi.org/10.1016/j.resp.2004.02.003), PMID 15186789.

Andersen et al. report L_m rising along a sigmoidal dose–response in
elastase-treated C57BL/6J, with the fractal box dimension falling from 1.66 to
1.47 across the same range (R = −0.95 against L_m).
[DOI](https://doi.org/10.2147/COPD.S26493), PMID 22500123.

**This module's values are 13–24 µm and that discrepancy is expected, not a
bug**: it is measuring internuclear distance on a nuclear mask, not airspace
chord on a tissue mask (§1). The comparison is recorded here so that nobody
later reads 16 µm as "severely reduced alveolar size in an IFNγ-KO mouse". The
right way to obtain a number comparable to 35 µm on this material is a
brightfield serial section, not a different threshold.

---

## 4. What can legitimately be claimed

**Can be claimed**

* Within one section, the AGER-defined damaged compartment does / does not
  differ architecturally from the intact compartment of the same lung, by a
  stated effect size, on measures that never touch AGER.
* Whether that within-animal contrast is larger in infected animals than in
  uninfected controls — the comparison that separates "injury" from "anatomy".
* The direction and magnitude of resolution dependence of every metric, measured.
* That the estimators are exactly additive, so mouse-level pooling is
  numerically correct.

**Cannot be claimed**

* Any absolute alveolar dimension, in µm, comparable to the literature.
* Any 3-D quantity (V_V, S_V, alveolar number) — no IUR sectioning, no recorded
  section thickness, no recorded inflation pressure, no disector.
* Emphysema, septal thickening or airspace enlargement as pathological
  diagnoses. These are named surrogates, not Saetta's destructive index, which is
  a human point count with verbal criteria and is not reproduced here.
* Any inferential (p-value-bearing) statement about genotype or infection.
  n = 1 animal per group.
* That infected-vs-uninfected morphometry is held out. **It is not.** The
  controls were used to lock the damage detector *and* the tissue threshold.
  Only the *within-animal* compartment contrast is free of that.
