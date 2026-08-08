# Project state — living handoff

Updated 2026-08-08 ~18:40 KST. **Update this whenever work is parked.**
It exists so a fresh session can resume without the prior conversation.

---

## 0. Latest session (2026-08-08 evening) — read this first

**Confocal data arrived and the KRT5 threshold is now calibrated.** The whole
project was blocked on it; it no longer is.

`D:\Confocal_Images\260808-CW\260808-CW` — 4 mice × BOTH panels × ~10 fields
= 82 analysis fields, 2048², 4ch, single plane, 0.3107 µm/px. The RIGHT panel
(ProSPC/mRAGE/KRT8) that was deferred to confocal is now in hand.

Confocal removes the slide-scanner autofluorescence floor that made KRT5
uncalibratable. Pooled in-tissue 488 statistics:

| mouse | condition | p99.9 | p99.99 | max | frac>500 |
|---|---|---|---|---|---|
| M4-2 het | uninfected | 211 | 283 | 4095 | 0.00001 |
| M6 hom | uninfected | 195 | 255 | 1254 | 0.00000 |
| M2 hom | infected | 4095 | 4095 | 4095 | 0.08112 |

**`IFQ_KRT5_THRESHOLD = 300`**, derived from the two uninfected controls only
(just above both p99.99, so false-positive area ≤ 1e-4 in each control
independently). AGER and T1A are deliberately left ADAPTIVE — they are
constitutively expressed, so "the control should be negative" gives no
calibration handle, and the engine labels their calls `exploratory_*`.

Run: `D:\IFQ_Runs\confocal_260808\` (script: `scratchpad/run_confocal.ps1`).
79/82 succeeded. The 3 failures are data, not pipeline: two truncated
acquisitions (7.3 / 8.2 MB against a uniform 37.7 MB) and one field where DAPI
tissue detection correctly refused rather than analysing background. 13
`Map_A01.oir` overviews were skipped by the engine's own guard.

### The result (mouse level, LEFT panel, area-based)

| mouse | condition | KRT5⁺ area | KRT5 pods | T1α area |
|---|---|---|---|---|
| M2 (hom) | PR8 | 14.11% | 1080 | 13.4% |
| M4-1 (het) | PR8 | 11.98% | 1092 | 13.4% |
| M4-2 (het) | uninfected | 0.000% | 0 | 24.6% |
| M6 (hom) | uninfected | 0.003% | ~0 (23 µm²) | 28.7% |

Near-binary separation, and T1α area moves the right way (down in infected =
AT1 loss). **Caveat: n = 1 mouse per genotype × condition cell.** Genotype is
confounded with condition; no statistics are possible from this batch.

### KNOWN DEFECT — nucleus segmentation under-detects ~50–100×

Measured density is **~140 nuclei/mm²**; lung parenchyma is ~5e3–2e4. The
candidate count before filtering is only 35–106 per field, so DAPI thresholding
is failing upstream of the size filter rather than being over-filtered. The
defaults (`dapiLocalRadiusUm=4.0`, `dapiBackgroundRadiusUm=15.0`,
`minNucArea=8.0`) were tuned on slide-scanner data.

- **Unaffected** (area-based, no nuclei): `*_positive_area_um2`,
  `*_positive_area_frac`, `*_pod_area_um2`, `*_n_pods`, `region_area_um2`.
  The LEFT-panel result above is therefore sound.
- **Affected — do not report**: every `*_pos_count`, `*_density_per_mm2`,
  `*_morphology_*`, `*_final_*_cell_count`, `class_*_count`, `n_nuclei`.

Consequence: the **RIGHT panel is currently unusable**, because the registry
defines area mode only for KRT5/AGER/T1A, so ProSPC and KRT8 have cell-count
outputs only. Fixing this needs no engine change — the DAPI parameters are
environment-configurable — but it does need a calibration sweep against
hand-counted fields.

### Storage and locations

A confocal run is ~7.6 GB: 5.9 GB uncompressed 2048² mask TIFFs, 1.7 GB QC
PNGs, and 2.7 MB of actual numbers. Masks must be kept
(`evaluate_endpoints.groovy` does mask algebra on them) but compress ~50–100×.

Results live under `D:\IFQ_Runs\`. The July runs the v1.7.2 launcher had
written to `C:\Users\dream\Documents\IFQuantResults` (12.0 GB, 14 runs) were
moved to `D:\IFQ_Runs\archive_202607_pre_revision\`, and the launcher's
first-run default no longer points at the system drive.

### Launcher v1.8.0 landed (`f7dbb02`)

Four routes (confocal / slide scanner / H&E-disabled / legacy). Route 4 proven
equal to v1.7.2 by execution: 82 checks, 0 failures, recorded in
`launcher/legacy_equivalence_report.txt`. See `launcher/README.md`.

---

## 1. Where everything is

| | |
|---|---|
| repo | `X:\GitHub\IFQuant-Lung` (renamed from `Fiji_ImageJ_Cell_Counting`) |
| GitHub | `xorca0711/IFQuant-Lung` |
| branch | `main` @ `99bdda9`, tagged **`v2.0.0`** |
| review branch | `claude/module-drafts` @ `b953e7d` — **never merge** |
| released launcher | `IFQuantLauncher-v1.7.2.exe` — **unchanged, still current** |
| data | `D:\Confocal_Images\20260806_CW\20260806_CW\*.vsi` (4 slides) |
| pipeline runs | `D:\IFQ_Runs\` — `validated/` and `superseded/`, see its README |
| channel cache | `X:\ifq_cache` (ds=8, bit-exact, 31× faster than decoding) |
| QuPath | `X:\QuPath\QuPath-0.7.0 (console).exe` |
| Fiji | `X:\Fiji` — its `.exe` is **broken on ARM64**; invoke the JVM directly |

Only two remote branches exist: `main` and `claude/module-drafts`. Everything
else was merged or retired on 2026-08-07; see `BRANCHING.md`.

---

## 2. The endpoint, and what is actually established

```
ectopic pod fraction = KRT5+ PDPN- area / damaged alveolar area
```

| item | status |
|---|---|
| WSI chain Stage 1→2→3 | **validated** — reconciliation exact (2.1e-16) |
| damaged-area denominator | **LOCKED** from controls: AGER 150, σ 40 µm, cutoff 0.14 |
| held-out check | infected 6.71% / 4.68% vs controls 0.93% / 0.18% |
| `endpoints/` relational module | built, runs, guards in place |
| **`IFQ_KRT5_THRESHOLD`** | **NOT CALIBRATED — blocks any reportable number** |
| PDPN co-negativity ceiling | **proposed 200, not locked** (measured only at 2.76 µm/px) |
| airway exclusion | **not implemented** — needs hand-drawn annotations |
| morphometry cross-check | drafted, unverified, on `claude/module-drafts` |

Full evidence and derivations: [`ECTOPIC_POD_ENDPOINT.md`](ECTOPIC_POD_ENDPOINT.md).

**Nothing here produces a defensible pod number yet.**

---

## 3. In flight when parked

**Launcher v1.8.0 fix + re-verify** — workflow `wf_3c3980ae-8ed`, started 23:29,
0/2 returned at time of writing.

* script: `C:\Users\dream\.claude\projects\X--QuPath\7933abe5-e14c-44b2-aa07-c4127fa41a9e\workflows\scripts\launcher-fix-and-reverify-wf_3c3980ae-8ed.js`
* transcripts + per-agent results: `…\subagents\workflows\wf_3c3980ae-8ed\journal.jsonl`
* work dir: `…\scratchpad\launcher_final\` — **scratchpad is temp storage**

Resume with `Workflow({scriptPath, resumeFromRunId: "wf_3c3980ae-8ed"})`.
Completed agents replay from cache.

---

## 4. Launcher v1.8.0 — five defects found, fixes in flight

A build produced a compiling v1.8.0 with routes R1/R2/R4 and R3 disabled. An
adversarial verifier driving the real exe returned
**`legacy_equivalence_holds: false`** and five defects. **Do not ship v1.8.0
until these are confirmed fixed.**

**D1 (critical) — H2 is bypassable with a custom panel.**
`FailClosedGate.Evaluate` guards H2 with `if (panel != null && …)`, and
`ResolveSelectedPanel()` returns **null for a custom panel key**. So custom panel
+ custom JSON + confirmatory tier + zero thresholds gives a green
*"Ready … 0/0 thresholds fixed, tier confirmatory"* bar, starts, and records
`run_classification=THRESHOLDS_FROZEN` — while every channel runs adaptive Otsu.
That is the 4.95%-KRT5-on-an-uninfected-control failure, reachable in four
clicks and labelled frozen. Also bypasses the route-2 "unconditional" block.

**D2 — route 4 mislabels itself.** It writes no thresholds by construction, yet
emits `THRESHOLDS_FROZEN`/`thresholds_frozen=true`, contradicting its own
threshold_policy block. Any aggregator grepping that field pools unfrozen legacy
runs with frozen ones.

**D3 — route 4 breaks legacy equivalence.** v1.7.2 accepted any
`^IFQ_[A-Z0-9_]+$` Advanced key; v1.8.0 blocks `IFQ_MIN_INCLUDED_NUCLEI`
(the *only* way v1.7.2 could set the nuclei floor) and unknown keys. Archived
analyses using them cannot be reproduced.

**D4 — R3 re-enable is two edits**, but the user-visible string says one.

**D5 — `RunEnvironment.BuildStage1` has no route guard** (returns a full stage-1
environment for route 3), and `RouteCatalog.Describe` **fails open** on an
undefined enum value.

Also: two real CS0162 unreachable-code warnings.

**Lesson worth keeping:** the first pass reported GateMatrix 36/36 and
LegacyEquivalence 48/48 with D1 wide open, because every gate scenario used a
built-in panel. Green test counts are not evidence; the adversarial pass is.

---

## 5. Known bug carried in main

Five partition QC columns are **silently dropped** at mouse level —
`aggregate_to_mouse.classify_columns()` uses a closed whitelist
(`aggregate_to_mouse.py:184-186`), and anything outside it vanishes with no
error. The primary endpoint is unaffected; per-mouse QC is lost. Proposed fix
(`panel = "<PANEL>@<scope>"`) is **not applied** — it changes the shape of
`slide_level_summary.csv`. See `ECTOPIC_POD_ENDPOINT.md` §9.

---

## 6. Decisions waiting on the user

1. **`panel@scope`** — apply the QC-column fix, or leave it?
2. **Threshold calibration** — `IFQ_KRT5_THRESHOLD` needs blinded control
   review. Nothing downstream is interpretable first.
3. **Saturday confocal** — image an uninfected section at **low 488 exposure**.
   KRT5 was acquired at ~949 ms vs ~0.5–2 ms for the other channels; if the
   background is an acquisition artifact, that fixes the numerator at source and
   makes the whole co-negativity apparatus far less load-bearing.
4. **Is `het` the control?** A heterozygous *Ifng*⁺/⁻ often signals normally.
   This defines n and therefore the comparison.

---

## 7. Machine constraints (they shape what is possible)

Snapdragon X Oryon, **8 cores**, **15.6 GB RAM**, **ARM64**.

A full-resolution slide is 57165 × 42154 × 2 B × 4 ch = **19.3 GB** — it cannot
be held in memory. That is *why* tiling is mandatory and why the PDPN ceiling
still cannot be confirmed at full resolution. **64 GB would turn several
outstanding caveats into answered questions.**

`-Xmx` must stay ≤ ~40% of RAM. Earlier runs used `-Xmx12g` on this machine and
paged to disk; the symptom looks like slow I/O, not swapping.

GPU would only help if deep-learning segmentation (StarDist/Cellpose) were
adopted — and the area-based pod endpoint never touches nuclei segmentation.

---

## 8. Next steps, in order

1. Land the launcher fixes; **apply only if `safe_to_apply` is true**.
2. Calibrate `IFQ_KRT5_THRESHOLD` from blinded controls (use the channel cache —
   sweeps are now ~1 s/slide instead of ~38 s).
3. Re-measure the PDPN ceiling at full tile resolution.
4. Airway annotation workflow (QuPath GeoJSON → per-tile subtraction).
5. Morphometry, as an independent check on the damaged-area denominator.
