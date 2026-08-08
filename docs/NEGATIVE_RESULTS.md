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
