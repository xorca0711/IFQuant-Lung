# Project state — living handoff

> **Status: CURRENT.** This is the one document that is allowed to contradict an
> older one. Where any other doc in `docs/` disagrees with this file, this file
> wins and the other doc is stale — say so rather than reconciling silently.
>
> Last reconciled against data and code: **2026-08-09**, against
> `D:\IFQ_Runs\confocal_260809_rerun`, `main` @ `35d27b8`, and the
> [G-SURF research scheme](https://app.notion.com/p/39c151616b4480d88dffdd8585ba8fd9).

**Update this whenever work is parked.** It exists so a fresh session — or a
reader with five minutes — can tell what was built, what is validated, and what
is not.

---

## 0. The 60-second version

* The measurement engine is `IF_Quant_Pipeline.groovy` (Fiji). **QuPath measures
  nothing**; it reads and tiles whole slides. The handoff is file-based because
  the two ship incompatible Java versions.
* Confocal data arrived 2026-08-08. **`IFQ_KRT5_THRESHOLD = 300` is calibrated**
  from uninfected controls. AGER and T1A are **not** calibrated and every call
  they make is labelled `adaptive_otsu_exploratory`.
* The headline area result is real and reproducible: KRT5⁺ area 14.11% / 11.98%
  in infected mice against 0.000% / 0.003% in uninfected controls.
* **n = 1 mouse per genotype × condition cell, and genotype is confounded with
  condition. No statistics are possible from this batch.** Anything that reads
  like a group comparison is a description of four animals, not a result.
* Two things were tested and **rejected** with a control-locked enrichment test:
  AGER as a co-negativity marker, and KRT8 as a discriminator. See
  [`NEGATIVE_RESULTS.md`](NEGATIVE_RESULTS.md).
* One one-token bug (`black`, missing from an ImageJ Binary Options macro string)
  destroyed every nucleus count in the first run. Diagnosed, fixed, re-validated,
  and area measurements proven unaffected. Section 3 below.
* The endpoint **specification was wrong and has been corrected**: the reference
  measures KRT5⁺PDPN**⁺**, not KRT5⁺PDPN⁻. Section 2 below.

---

## 1. Where everything is

| | |
|---|---|
| repo | `X:\GitHub\IFQuant-Lung` (renamed from `Fiji_ImageJ_Cell_Counting`) |
| GitHub | `xorca0711/IFQuant-Lung` |
| branch | `main` @ `35d27b8`. Launcher tag `v1.9.0` points at `22afada`; the current tip is a post-release documentation baseline. See `BRANCHING.md`. |
| review branch | `claude/module-drafts` @ `b953e7d` — **never merge** |
| launcher binary | current `IFQuantLauncher-v1.9.1.exe` at repo root — **gitignored**; previous v1.9.0 archived under `legacy/launchers/`, see section 4 |
| confocal data | `D:\Confocal_Images\260808-CW\260808-CW` — 4 mice × 2 panels × ~10 fields |
| slide-scanner data | `D:\Confocal_Images\20260806_CW\20260806_CW\*.vsi` — 4 slides (WSI pilot) |
| pipeline runs | `D:\IFQ_Runs\` — see its README |
| research scheme | [G-SURF / 4W after PR8 infection](https://app.notion.com/p/39c151616b4480d88dffdd8585ba8fd9); M4-1 is confirmed **het** |
| channel cache | `<repo>\.cache\slide_channels` (ds=8, bit-exact, 24.5× faster than decoding). Gitignored, regenerable in ~10 min via `scripts/cache_slide_channels.groovy`. Override with `IFQ_CACHE_DIR`. |
| QuPath | `X:\QuPath\QuPath-0.7.0 (console).exe` |
| Fiji | `X:\Fiji` — its `.exe` is **broken on ARM64**; invoke the JVM directly |

Only two remote branches exist: `main` and `claude/module-drafts`. Everything
else was merged or retired on 2026-08-07; see [`BRANCHING.md`](BRANCHING.md).

### The runs, and which one to trust

| run | what it is | use it for |
|---|---|---|
| `D:\IFQ_Runs\confocal_260808` | first confocal run; carries the `blackBackground` bug | **areas only**; every count in it is wrong |
| `D:\IFQ_Runs\confocal_260808_fixed` | re-run after the fix | **trusted result baseline** |
| `D:\IFQ_Runs\confocal_260809_rerun` | independent 2026-08-09 reproduction | **verification record** — 79-row run summary and both ordinary aggregate CSVs are byte-identical to `confocal_260808_fixed` |
| `D:\IFQ_Runs\validated` / `superseded` | earlier WSI pilot outputs | provenance |
| `D:\IFQ_Runs\archive_202607_pre_revision` | 14 July runs (12.0 GB) moved off the system drive | provenance |

A confocal run is ~7.6 GB: 5.9 GB of uncompressed 2048² mask TIFFs, 1.7 GB of QC
PNGs, and 2.7 MB of actual numbers. Masks must be kept
(`endpoints/evaluate_endpoints.groovy` does mask algebra on them) but compress
~50–100×.

---

## 2. The endpoint — corrected specification

```
dysplastic fraction  =  KRT5+ AND PDPN+ area  /  (PDPN- OR KRT5+) area
```

**This is a change of sign from what was implemented.** Lin et al. 2024
(J Clin Invest 134(19):e176828) Fig 2A–B quantify *"percentages of **KRT5⁺PDPN⁺**
areas in PDPN⁻ and KRT5⁺ areas"*. The implementation computed KRT5⁺PDPN**⁻**
over a computed damaged area, and the old spec mis-quoted the paper to justify
it. PDPN is expressed by basal/dysplastic cells as well as AT1, so requiring
PDPN-negativity excluded the population being measured.

| artefact | status |
|---|---|
| `config/endpoints/dysplastic_over_damaged.json` | **CURRENT spec.** Correct sign, union denominator, cites the reference verbatim. |
| `config/endpoints/ectopic_pod_over_damaged.json` | **SUPERSEDED.** Wrong sign, wrong denominator, mis-quotes the reference. Kept as the record. |
| `endpoints/evaluate_endpoints.groovy` | **Executor implemented in the 2026-08-09 working tree.** It evaluates declarative `AND`/`OR`/negated terms for both numerator and denominator, emits both areas and the fraction, and rejects retracted specs. |

The algebra is now computable, but the scientific endpoint is still blocked by
an uncalibrated T1A/PDPN mask and the lack of manual-outline ground truth. The
executor therefore refuses uncalibrated masks by default; an explicit
exploratory override exists for engineering validation only. A corrected-algebra
engineering run exists under `confocal_260809_rerun` (39 LEFT fields; pooled
fractions M2 7.384278%, M4-1 6.363576%, M4-2 0%, M6 0.000346%), with an
`EXPLORATORY_ONLY.md` warning. These are not reportable. The earlier endpoint numbers
(`D:\IFQ_Runs\confocal_260808\endpoint_areas.csv`, `endpoint.log`) were produced
by the **superseded** spec — `endpoint : ectopic_pod_over_damaged`,
`numerator: KRT5_pod_mask AND NOT T1A_membrane_positive_mask`, denominator =
total tissue region, not damaged area. They validated the *machinery*, not the
*endpoint*. A new deterministic synthetic fixture tests the corrected
intersection/union algebra exactly.

### What is actually established

| item | status | evidence |
|---|---|---|
| WSI chain Stage 1→2→3 | **VALIDATED** | reconciliation to 2.1e-16; see [`WSI_TILING_WORKFLOW.md`](WSI_TILING_WORKFLOW.md) §10 |
| mask-algebra endpoint module | **VALIDATED mechanically** | synthetic numerator 2, denominator 6, fraction 1/3; uncalibrated and retracted specs refused; real-data reconstruction worst rel. diff 3.285e-07 |
| `IFQ_KRT5_THRESHOLD = 300` | **CALIBRATED**, one sound control | control p99.99 = 283 (M4-2) / 255 (M6); recorded as `fixed_predeclared` in both runs. **M6 LEFT is a staining failure, so this rests on M4-2 alone.** |
| `IFQ_AGER_THRESHOLD`, `IFQ_T1A_THRESHOLD` | **NOT CALIBRATED** | both run `adaptive_otsu_exploratory`; deliberately so — they are constitutively expressed, so "the control should be negative" gives no handle |
| AGER damage detector (AGER 150, σ 40 µm, cutoff 0.14) | **RETIRED as the denominator** | it was locked from controls and the derivation is sound, but the reference's denominator is a **hand-traced union**, not a density detector. It solves a problem the reference does not have. |
| PDPN ceiling t = 200 | **RETIRED** | derived as a co-*negativity* ceiling; the justification does not transfer to co-*positivity* |
| airway exclusion | **NOT IMPLEMENTED** | needs hand-drawn annotations |
| morphometry cross-check | **drafted, directional only** | on `claude/module-drafts`; its MLI is inter-nuclear spacing, not the classical quantity |

Full derivations and the evidence trail:
[`ECTOPIC_POD_ENDPOINT.md`](ECTOPIC_POD_ENDPOINT.md) — which is retained as the
**calibration record** and is no longer the specification. Read its banner first.

**No defensible dysplastic-fraction number exists yet.** The KRT5⁺ area numbers
in section 3 are area measurements, not the endpoint.

---

## 3. The confocal batch, and the bug that nearly ate it

`D:\Confocal_Images\260808-CW\260808-CW` — 4 mice × BOTH panels × ~10 fields
= **82 acquisitions**, 2048², 4 ch, single plane, 0.3107 µm/px. The RIGHT panel
(ProSPC / mRAGE / KRT8) that had been deferred to confocal is now in hand.

Confocal removes the slide-scanner autofluorescence floor that made KRT5
uncalibratable. Pooled in-tissue 488 statistics:

| mouse | condition | p99.9 | p99.99 | max | frac>500 |
|---|---|---|---|---|---|
| M4-2 het | uninfected | 211 | 283 | 4095 | 0.00001 |
| M6 hom | uninfected | 195 | 255 | 1254 | 0.00000 |
| M2 hom | infected | 4095 | 4095 | 4095 | 0.08112 |

`IFQ_KRT5_THRESHOLD = 300` sits just above both control p99.99 values, so the
false-positive area is ≤ 1e-4 in each control independently.

**82 → 79 analysed.** Two `.oir` files are truncated at acquisition (7.3 and
8.2 MB against a uniform 37.7 MB) and fail in both Bio-Formats paths; one field
was refused by DAPI tissue detection rather than analysed as background. 13
`Map_A01.oir` overviews were skipped by the engine's own guard. All three
failures are data, not pipeline.

### The `blackBackground` bug — FOUND, FIXED, RE-VALIDATED

The first run reported ~**150 nuclei/mm²** in lung parenchyma, where 5×10³–2×10⁴
is expected.

**Root cause: one missing token.** `IJ.run(mask, "Options...", "iterations=2
count=1 do=Close")` inside `resolveTissueRois`. ImageJ's Binary Options dialog is
a `GenericDialog`, and in macro mode an **absent** checkbox keyword reads as
*unchecked* — so the call wrote `Prefs.blackBackground = false` **globally**,
silently overriding the `true` set in `main()`. That ran before `segmentNuclei`
for every image, inverting the polarity of `Fill Holes`, which erased every
nucleus that did not touch the image frame and left only the border-connected
rim. 100% of candidate components were border-touching in all 79 fields.

**Diagnosis, not inference.** A replay of the buggy code path reproduced the
shipped mask at **IoU = 1.0000**. The fix is the token `black`, at
`IF_Quant_Pipeline.groovy:1783`, with the reasoning written into the source so it
cannot be removed again by tidying.

**Measured cost and blast radius**, pooled over all 79 fields, buggy run vs fixed
run:

| quantity | buggy | fixed | change |
|---|---|---|---|
| nucleus density | 152.5 /mm² | 15 393.3 /mm² | **~101× undercount** |
| `KRT5_pod_area_frac`, worst field | — | — | **0.0154 pp** |
| `T1A_positive_area_frac`, worst field | — | — | **0.0209 pp** |
| `AGER_positive_area_frac`, worst field | — | — | **0.0042 pp** |
| `region_area_um2`, worst field | — | — | 0.456% rel |

Area masks are read via `setThreshold(128, 255, NO_LUT_UPDATE)` on pixel values,
and *Convert to Mask* inverts the LUT rather than the data — so areas were
structurally immune. The table is the proof, not the argument: the largest change
in any area fraction across 79 fields is **0.021 percentage points**. The
LEFT-panel area result below therefore survived the bug unchanged.

> The in-source comment at `IF_Quant_Pipeline.groovy:1775-1778` quotes
> 185 → 16 422 /mm² and "89×", computed on a different field subset. The pooled
> figures above are the ones reproducible from `run_summary.csv`. The two should
> be reconciled — `IF_Quant_Pipeline.groovy` is outside this document's
> ownership.

### The result (mouse level, LEFT panel, area-based, `confocal_260808_fixed`)

| mouse | genotype | condition | KRT5⁺ area | KRT5 pods | T1α area | nuclei |
|---|---|---|---|---|---|---|
| M2 | hom | PR8 | **14.114%** | 1080 | 13.37% | 20 805 |
| M4-1 | het | PR8 | **11.977%** | 1094 | 13.38% | 23 086 |
| M4-2 | het | uninfected | **0.000%** | 0 | 24.62% | 3 623 |
| M6 | hom | uninfected | **0.0035%** | 0 (23 µm²) | 28.67% | 10 470 |

Near-binary separation, and T1α area moves the right way (down in infected =
AT1 loss).

**Caveat, and it is the binding one: n = 1 mouse per genotype × condition cell.**
Genotype is confounded with condition — there is no infected/uninfected pair
within a genotype *and* no het/hom pair within a condition that is not also
confounded by section. No statistics are possible from this batch. These four
numbers describe four animals.

### Markers tested and REJECTED

See [`NEGATIVE_RESULTS.md`](NEGATIVE_RESULTS.md) for the test, the data, and the
diagnosis in each case.

- **AGER as co-negativity marker**: R ≈ 0.99–1.05, indiscriminate. Retracted —
  the removal was definitional, not biological.
- **KRT8 as infected/uninfected discriminator**: R = 0.80–1.25 at every
  control-locked cut. The two infected mice **bracket** the two controls, so
  between-section staining variance exceeds the biological signal. Not a tuning
  problem.
- **Co-negativity as the endpoint's form**: superseded by section 2.

### What the RIGHT panel can and cannot do

Fixed. Per-cell masks and counts for ProSPC and KRT8 are now sound (that was the
`blackBackground` casualty). What remains true is narrower: the marker registry
defines **area** mode only for KRT5 / AGER / T1A, so ProSPC and KRT8 have
**cell-level outputs only** — no area endpoints. They are renderable and
countable, not area-quantifiable, without a registry change.

---

## 4. Launcher — v1.9.1 GUI repair candidate

Four routes: confocal fields / slide scanner / H&E (deliberately disabled) /
legacy. v1.8.0 committed at `f7dbb02`; **published v1.9.0 at `22afada`** adds a
responsive WinForms layout and makes the equivalence claim reproducible. The
v1.9.1 source candidate keeps file scope outside the unreliable scrolling pane
and adds a named preset for the validated 20x/2k lung cohort. See
`launcher/README.md`.

**Route 4 is proven equal to v1.7.2 by execution, not assertion.**
`launcher/legacy_equivalence_report.txt`: **84 checks, 0 failures**, in six
groups — canonical env diff across 7 fixtures, source-drift guards against the
real v1.7.2 file, "the source cannot quietly stop being legacy", process-level
diff of what the child process actually receives, command line, and the Advanced
box decided line-for-line (23/23 identical).

**Read the scope of that claim carefully.** Route 4 reproduces the v1.7.2
**environment and command line** exactly. It does **not** ship v1.7.2's engine:
the embedded pipeline hash now differs (`b45e4289…` against v1.7.2's
`defffe67…`) because the engine carries the `blackBackground` fix. The harness
**detects and names that drift** rather than papering over it, and points at the
archived v1.7.2 binary in `legacy/launchers/` for byte-exact historical numbers.
That is the honest version of the claim: same environment, deliberately newer
engine.

An earlier build of v1.8.0 was rejected by an adversarial verifier that drove the
real `.exe` and returned `legacy_equivalence_holds: false` with five defects —
the worst being a fail-closed gate bypassable in four clicks with a custom panel,
which would have recorded `run_classification=THRESHOLDS_FROZEN` while every
channel ran adaptive Otsu. **The lesson is kept deliberately**: that same build's
first test pass reported GateMatrix 36/36 and LegacyEquivalence 48/48 with the
bypass wide open, because every gate scenario used a built-in panel. Green test
counts are not evidence; the adversarial pass is.

### Resolved: the version collision

Two materially different launcher sources both declared `1.8.0.0` — the
committed one and a ~476-line uncommitted WinForms layout revision on top of it.
**Fixed at `22afada`:** the release source declares
`AssemblyFileVersion("1.9.0.0")`. The GUI repair advances the source to
`1.9.1.0`, so it does not repeat the earlier collision; a local
`IFQuantLauncher-v1.9.1.exe` build has its own SHA-256 sidecar.

### Still owed

* **Choose a long-term version namespace.** `v1.9.0` correctly tags the launcher
  release at `22afada`, while `v2.0.0` is an older repository tag. The coexistence
  is historically accurate but easy to misread.
* **The current binary is gitignored** (`.gitignore:58`,
  `/IFQuantLauncher-*.exe`). The previous v1.9.0 executable and its checksum are
  archived under `legacy/launchers/`; the current v1.9.1 build remains the only
  untracked link in that chain.
* Section `[c]` of the equivalence report is still headed "The **v1.8.0** source
  cannot quietly stop being legacy". Cosmetic, but it is the sort of stale label
  that later reads as evidence about the wrong build.

---

## 5. Aggregation QC bug — fixed in the 2026-08-09 working tree

Partition QC columns (`damaged_area_um2`, `intact_area_um2`, and intact-region
pod areas/fractions) were silently dropped at mouse level because
`classify_columns()` used a closed whitelist. They are now explicitly classified,
summed, and recomputed from pooled areas. Regression tests cover the classification
and pooled-fraction math; the output panel shape is unchanged.

---

## 6. Decisions waiting on the user

1. **Calibrate T1A/PDPN and define manual-outline ground truth.** The corrected
   endpoint algebra runs, but no defensible biological fraction exists without this.
2. **Whether the launcher and repository share a tag namespace.** `v1.9.0` is a
   launcher release; `v2.0.0` is an older repository tag.
3. **Low-exposure 488 control.** KRT5 was acquired at ~949 ms against ~0.5–2 ms
   for the other channels. If the background is an acquisition artefact, that
   fixes the numerator at source.
4. **Is `het` the control?** A heterozygous *Ifng*⁺/⁻ often signals normally.
   This defines n and therefore what comparison is even available.
5. **M6 LEFT staining failure.** AGER frac>500 = 0.0097 in M6 LEFT vs 0.289 in
   M6 RIGHT — same antibody, same animal. Until that is resolved, `KRT5=300`
   rests on one control.

---

## 7. Machine constraints (they shape what is possible)

Snapdragon X Oryon, **8 cores**, **15.6 GB RAM**, **ARM64**.

A full-resolution slide is 57165 × 42154 × 2 B × 4 ch = **19.3 GB** — it cannot
be held in memory. That is *why* tiling is mandatory. `-Xmx` must stay ≤ ~40% of
RAM; earlier runs used `-Xmx12g` on this machine and paged to disk, and the
symptom looks like slow I/O rather than swapping. **64 GB would turn several
outstanding caveats into answered questions.**

GPU would only help if deep-learning segmentation (StarDist/Cellpose) were
adopted — and the area-based endpoint never touches nuclei segmentation.

---

## 8. Next steps, in order

1. Preserve `confocal_260809_rerun` as the byte-identical reproducibility record.
2. Calibrate T1A/PDPN and validate the corrected endpoint against hand-drawn
   outlines; exploratory adaptive-Otsu output must not be treated as a result.
3. Airway annotation workflow (QuPath GeoJSON → per-tile subtraction). Every
   KRT5 number includes airway basal cells until this exists.
4. Re-derive `IFQ_KRT5_THRESHOLD` once a second sound control exists.
5. Re-run launcher route/equivalence validation on every release build.
