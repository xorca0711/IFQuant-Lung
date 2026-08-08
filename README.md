# IFQuant-Lung

Image quantification for mouse-lung immunofluorescence, built for one question:
after PR8 influenza injury, does IFN-γ **ligand** knockout change the amount of
dysplastic KRT5⁺ repair? It converts confocal fields or whole-slide scans into
per-mouse area and cell measurements, with every threshold, mask and parameter
written to disk so a number can be traced back to the pixels that produced it.

Two audiences: the person at the microscope, who gets a Windows launcher and no
command line; and the person who has to check the result afterwards, who gets
per-run provenance, per-marker masks, and a written record of what failed.

## Architecture

```
 .vsi whole slide          QuPath 0.7                 tiles + ROI zips
 57165 x 42154 x 4ch  ──►  opens the slide,      ──►  <tile>.ome.tif
 = 19.3 GB, will not       detects tissue once,       <tile>.ome_RoiSet.zip
 fit in 15.6 GB RAM        cuts 2048 px tiles              │
                           MEASURES NOTHING                │
                                                           ▼
 .oir / .czi / .nd2  ─────────────────────────►  Fiji + IF_Quant_Pipeline.groovy
 confocal fields                                 THE ONLY MEASUREMENT ENGINE
                                                 nuclei, three-state marker calls,
                                                 area masks, per-cell CSV, QC PNGs
                                                           │
                                                           ▼
                                                 Python aggregation
                                                 tile ─► slide ─► mouse
                                                 (n is counted in mice)
                                                           │
                                                           ▼
                                                 run_summary.csv
                                                 slide_level_summary.csv
                                                 mouse_level_summary.csv

 IFQuantLauncher (C#/WinForms) fronts all of the above as four explicit routes.
```

**Why it is split this way.** QuPath is the only half that can open an Olympus
`.vsi` — it bundles the JPEG-2000 codec (`ome-jai`) that Fiji's Bio-Formats
lacks — and the only half that can tile a slide too large to hold in memory.
Fiji hosts the measurement engine. The two applications ship **incompatible
Java versions**, so the handoff is files on disk rather than an in-process call;
this is the pattern described by Chiaruttini et al. 2022 (*Front Comput Sci*
3:780026). Writing a second measurement engine inside QuPath was considered and
rejected: two engines drift, and then a result depends on which one produced it.
**One engine measures. QuPath reads and cuts. Python only sums.**

## What is validated

| Claim | Evidence | Where |
|---|---|---|
| **You can run this one yourself, from a clone** | `powershell -ExecutionPolicy Bypass -File .\launcher\run_legacy_equivalence.ps1` — builds the harness and the launcher from source and diffs what a real child process receives under v1.7.2's environment versus route 4's. **84 checks, 0 failures**, including the self-critical one: the embedded engine no longer matches v1.7.2's and the harness detects and reports the drift. No data, no D: drive, no credentials. | [`launcher/run_legacy_equivalence.ps1`](launcher/run_legacy_equivalence.ps1) |
| Tile → slide reconciliation is exact | Stage 2 `sum(region_area_um2)` vs Stage 1 core tissue area: 1106122.8907924264 vs …67 µm², relative difference **2.1e-16** (machine epsilon) | [`docs/WSI_TILING_WORKFLOW.md`](docs/WSI_TILING_WORKFLOW.md) |
| The launcher's legacy route reproduces v1.7.2 | **82 checks, 0 failures**, by *executing* both versions: 7 environment fixtures byte-identical, child-process environments compared (not just dictionaries), 23 Advanced-box lines decided identically, plus a drift guard that re-reads the real v1.7.2 source and checks key set, assignment order and hardcoded values | [`launcher/legacy_equivalence_report.txt`](launcher/legacy_equivalence_report.txt) |
| The KRT5 cutoff is fixed from controls, not from the data being tested | `IFQ_KRT5_THRESHOLD = 300`, derived from the two uninfected animals alone (in-tissue p99.99 = 283 and 255), so control false-positive area ≤ 1e-4 in each independently; the infected animal measured has 8.1 % of tissue above 500 | [`scripts/run_confocal_260808.ps1`](scripts/run_confocal_260808.ps1) |
| A segmentation bug was found, quantified, fixed and re-validated | One missing `black` token in an ImageJ Binary Options macro string set `Prefs.blackBackground = false` **globally**, inverting `Fill Holes` during nucleus segmentation and erasing every nucleus not touching the image frame. Cost, pooled over all 79 fields (total nuclei / total tissue area): **152.5 nuclei/mm² recorded against 15,393.3/mm² corrected, a ~101× undercount**, with 100 % of candidate components border-touching in all 79 fields. The buggy path was replayed and reproduced the shipped mask at **IoU = 1.0000**, which is what pins the cause. Area outputs were unaffected (worst per-field change across all markers **0.0209 percentage points**, T1α; KRT5 **0.0154 pp**): they read the mask by pixel value, and `Convert to Mask` inverts the LUT, not the data. | [`IF_Quant_Pipeline.groovy:1765-1783`](IF_Quant_Pipeline.groovy), where the explanation is kept next to the token |
| Markers were rejected on evidence and the rejections are on the record | Control-locked enrichment test, R = mean(infected fraction beyond the cut) / mean(control fraction). **AGER as a co-negativity marker: R ≈ 0.99–1.05.** **KRT8 as an infected/uninfected discriminator: R = 0.80–1.25 at every cut**, with the two infected animals *bracketing* the two controls — between-section staining variance exceeds the biological signal. | [`docs/NEGATIVE_RESULTS.md`](docs/NEGATIVE_RESULTS.md) |
| An endpoint specification error was caught by reading the primary source | The implementation computed KRT5⁺PDPN⁻ over a computed damaged area. Lin et al. 2024 (*J Clin Invest* 134(19):e176828) specify **KRT5⁺PDPN⁺** over a hand-traced **PDPN⁻ OR KRT5⁺** union. PDPN is expressed by dysplastic cells as well as AT1, so requiring PDPN-negativity excluded the population being measured. | [`config/endpoints/dysplastic_over_damaged.json`](config/endpoints/dysplastic_over_damaged.json) |

## What is not established

| Open item | Status |
|---|---|
| **Statistics** | **n = 1 mouse per genotype × condition** (hom/het × PR8/uninfected). Genotype is confounded with condition. **No statistics are possible from this batch.** Any group comparison would be describing one animal. |
| Corrected endpoint | The corrected KRT5⁺PDPN⁺ / (PDPN⁻ OR KRT5⁺) spec is declared as data and **has not been re-run**. Every number quoted anywhere in this repository predates it and is either single-marker area or the old, inverted relation. The superseded spec `config/endpoints/ectopic_pod_over_damaged.json` is kept only as the record of the error. |
| AGER damage detector | Locked from controls (`AGER 150`, σ 40 µm, cutoff 0.14; held-out infected 6.71 % / 4.68 % vs controls 0.93 % / 0.18 %) — but it answers a question the reference does not ask. The reference denominator is a **hand-traced union of regions**, not a density detector. Treat the detector as an internally calibrated proxy, not as a reproduction of the published endpoint. |
| KRT5 threshold provenance | 300 was derived from two uninfected animals, one of which (M6) has a LEFT-panel AGER staining failure identified by the PI. In practice the cutoff rests on **one clean control**. |
| RIGHT panel (ProSPC / mRAGE / KRT8) | **Not usable as an endpoint.** The marker registry defines area mode only for KRT5, AGER and T1A, so ProSPC and KRT8 produce cell counts only — and KRT8 failed the enrichment test above. The panel is renderable and countable; it is not measuring anything that separates the groups. |
| AGER and T1A calls | Deliberately left adaptive. Both are constitutively expressed, so "the control should be negative" gives no calibration handle; the engine labels these calls `exploratory_*`. Do not report them as confirmatory. |
| Airway exclusion | Not implemented. It needs hand-drawn annotations. |
| Visual panels | The v8 design (mask-driven, object-level, three composited layers) is specified in [`docs/VISUAL_PANELS.md`](docs/VISUAL_PANELS.md) and the renderers exist under `panels/`. They are **new, untracked, and not yet validated against the post-fix masks.** v1–v7 all re-derived thresholds from pixel intensity, which cannot express "positive **cell**", and were replaced for that reason. |
| Launcher versioning | **Resolved.** Three distinct binaries were built as `v1.8.0` during development, so that string stopped identifying unique code and was retired rather than patched. Current release is **[v1.9.0](https://github.com/xorca0711/IFQuant-Lung/releases/tag/v1.9.0)** (sha256 `de98697b…`), which carries the engine fix; [v1.8.0](https://github.com/xorca0711/IFQuant-Lung/releases/tag/v1.8.0) is marked SUPERSEDED because it embeds the pre-fix engine. |
| Known aggregation bug | Five partition QC columns are silently dropped at mouse level — `aggregate_to_mouse.classify_columns()` uses a closed whitelist (`aggregate_to_mouse.py:184-186`). The primary endpoint is unaffected; per-mouse QC is lost. The proposed fix changes the shape of `slide_level_summary.csv` and has not been applied. |

## What it has measured so far

LEFT panel, 79 of 82 confocal fields analysed, area-based outputs at the locked
KRT5 cutoff. The three failures are data, not pipeline: two truncated
acquisitions (7.3 and 8.2 MB against a uniform 37.7 MB) and one field where DAPI
tissue detection refused rather than analyse background.

| mouse | genotype | condition | KRT5⁺ area | KRT5 pods | T1α area |
|---|---|---|---|---|---|
| M2 | hom | PR8 | 14.11 % | 1080 | 13.4 % |
| M4-1 | het | PR8 | 11.98 % | 1092 | 13.4 % |
| M4-2 | het | uninfected | 0.000 % | 0 | 24.6 % |
| M6 | hom | uninfected | 0.003 % | ~0 (23 µm²) | 28.7 % |

The separation is near-binary and T1α moves in the expected direction (AT1 loss
after injury). **This is four animals, one per cell, so it is a description of
four mice and not a comparison of groups.** Cell-count and density columns from
the pre-fix run are void; only the area columns above survived the
`blackBackground` bug, for the mechanical reason given in the validation table.

## How to run

### Windows launcher (recommended for operators)

The `.exe` is **not committed** (it is gitignored; binaries belong in Releases).
Build it from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\launcher\build.ps1
```

The build runs `--self-test` and a UI smoke test and discards the binary on
failure. It prints the SHA-256 of the executable and of every embedded artefact
(engine, marker registry, QuPath tiling script, Python reconciliation), so a
shipped binary can be traced to the exact engine it carries.

Route is the first choice, before any folder:

| # | Route | Tools | Produces |
|---|---|---|---|
| 1 | IF — confocal / field images | Fiji only | `run_summary.csv` (+ `.xlsx`, `run_manifest.json`), one row per (image, region) |
| 2 | IF — slide scanner (`.vsi`) | QuPath → Fiji → Python | tiles → per-tile measurements → `stats/slide_level_summary.csv` |
| 3 | H&E / brightfield | — | **deliberately disabled in this build** |
| 4 | Fiji-only legacy mode | Fiji only | the v1.7.2 environment and command line, verified by execution |

Route 3 is visible and greyed rather than hidden, with a written reason: the
fluorescence engine assumes bright signal on a dark background, which is
inverted for H&E, so pointing route 1 at an H&E slide **would not fail** — it
would produce a complete, plausible, wrong `run_summary.csv`. Route 2
hard-blocks a missing threshold; routes 1 and 4 only flag it, because a field
run with adaptive thresholds is a defensible exploratory measurement whereas a
slide run silently re-deriving a threshold on each of ~370 tiles is not one
measurement at all. Details in [`launcher/README.md`](launcher/README.md).

### Field / confocal route, directly

A complete worked example is
[`scripts/run_confocal_260808.ps1`](scripts/run_confocal_260808.ps1) — the run
that produced the calibrated KRT5 result. The shape of it:

```powershell
$env:IFQ_INPUT_DIR       = 'D:\Confocal_Images\<batch>'   # samplesheet.csv must sit here
$env:IFQ_OUTPUT_DIR      = 'D:\IFQ_Runs\<run>\analysis'   # must be empty
$env:IFQ_RECURSIVE       = 'true'
$env:IFQ_INCLUDE_REGEX   = '.*20x 2k.*\.oir'              # full match on the ABSOLUTE path
$env:IFQ_PANEL_MAP_PATH  = 'D:\IFQ_Runs\<run>\panel_map.csv'
$env:IFQ_MARKER_REGISTRY = "$PWD\config\lung_marker_registry.json"
$env:IFQ_SEGMENTER       = 'classic'                      # headless-safe
$env:IFQ_KRT5_THRESHOLD  = '300'                          # frozen from controls

$java = (Get-ChildItem 'X:\Fiji\java' -Recurse -Filter java.exe | Select-Object -First 1).FullName
& $java '--add-opens=java.base/java.lang=ALL-UNNAMED' `
  '-javaagent:X:\Fiji\jars\ij1-patcher-2.0.0.jar=init' `
  '-Djava.awt.headless=true' '-Dplugins.dir=X:\Fiji' `
  -cp 'X:\Fiji\jars\*;X:\Fiji\plugins\*' net.imagej.Main --headless `
  --run '<repo>\IF_Quant_Pipeline.groovy'

python aggregate_to_mouse.py 'D:\IFQ_Runs\<run>\analysis\run_summary.csv'
```

On Windows ARM64 the Fiji launcher `.exe` itself does not start; invoking the
bundled JVM directly, as above, is the working path.

### Whole-slide route

```powershell
# Stage 1 — QuPath tiles the slide (measures nothing)
$env:IFQ_WSI_INPUT  = 'D:\Confocal_Images\<slides>'   # .vsi file or folder; .ets is refused
$env:IFQ_WSI_OUTPUT = 'D:\IFQ_Runs\<run>'
& 'X:\QuPath\QuPath-0.7.0 (console).exe' script qupath_wsi_tile_export.groovy

# Stage 2 — the same Fiji engine measures the tiles, sharded across cores
.\scripts\Invoke-Stage2Sharded.ps1 -TilesDir 'D:\IFQ_Runs\<run>\slideA\tiles' `
    -OutputRoot 'D:\IFQ_Runs\<run>\slideA' -Shards 5 -Krt5Threshold 300

# Stage 3 — reconcile tiles back to one slide, then roll up to mice
python aggregate_tiles_to_slide.py --slide-root 'D:\IFQ_Runs\<run>'
python aggregate_to_mouse.py 'D:\IFQ_Runs\<run>\stats\slide_level_summary.csv'
```

Stage 3 refuses to emit a summary when tiles are missing. Stage 2 does **not**
set thresholds: pass calibrated values, or the engine falls back to per-tile
adaptive Otsu, which on a mostly-background tile reports
`KRT5_pod_area_frac ≈ 0.89`.

### Requirements

- **[Fiji](https://fiji.sc/)** — hosts the measurement engine; required for both
  routes. Bio-Formats is bundled.
- **[QuPath](https://qupath.github.io/) 0.7+** — whole-slide route only.
- **StarDist + CSBDeep** update sites — recommended for nuclei; without them set
  `IFQ_SEGMENTER=classic` (watershed fallback, and the only headless-safe mode).
- **Python 3**, standard library only, for both aggregation scripts.
- Windows ARM64 or x64 with .NET Framework 4.x for the launcher.

## Repository map

| Path | What it is |
|---|---|
| `IF_Quant_Pipeline.groovy` | The measurement engine. Every number comes from here. Changes to it are deliberately rare. |
| `qupath_wsi_tile_export.groovy` | Stage 1 whole-slide front end: series selection, one global tissue detection, calibrated tiles with halos. Measures nothing. |
| `scripts/Invoke-Stage2Sharded.ps1` | Stage 2 fan-out over tiles using NTFS hard links (no image data copied). |
| `aggregate_tiles_to_slide.py` | Stage 3: tile → slide, with coverage reconciliation; refuses to summarise an incomplete slide. |
| `aggregate_to_mouse.py` | Region → mouse → group, area-weighted. Reports `n_mice`. Computes no p-values on purpose. |
| `endpoints/` | Relational endpoints (a relation *between* two markers) evaluated by boolean algebra on masks the engine already wrote. The engine is marker-wise and cannot express this; the endpoint scripts close that gap without modifying it. |
| `config/endpoints/` | Endpoint specifications as reviewable, diffable data — including the superseded one and why it was superseded. |
| `config/lung_marker_registry.json` | Marker aliases, localisation, analytical-role defaults. Not a whitelist and not a diagnostic classifier. |
| `launcher/` | C#/WinForms front end, its build script, and the executed legacy-equivalence harness. |
| `panels/` | Figure rendering (merge panels, QC overlays). New, untracked, not yet validated. |
| `docs/` | Depth: [`PROJECT_STATE.md`](docs/PROJECT_STATE.md) (living handoff), [`NEGATIVE_RESULTS.md`](docs/NEGATIVE_RESULTS.md), [`ECTOPIC_POD_ENDPOINT.md`](docs/ECTOPIC_POD_ENDPOINT.md), [`WSI_TILING_WORKFLOW.md`](docs/WSI_TILING_WORKFLOW.md), [`QUPATH_FIJI_INTEGRATION.md`](docs/QUPATH_FIJI_INTEGRATION.md), [`MARKER_MORPHOLOGY_GUIDE.md`](docs/MARKER_MORPHOLOGY_GUIDE.md), [`VISUAL_PANELS.md`](docs/VISUAL_PANELS.md). |
| `legacy/` | Non-authoritative archive. No threshold in it is current. |
| `WORKFLOW.md` | Superseded as an entry point; see the banner at its top. |

Note on `docs/PROJECT_STATE.md`: parts of it predate the KRT5 calibration and
the segmentation fix and still describe both as open. Where it disagrees with
this file, this file is newer.

---

Everything below is reference detail. The five minutes' worth is above.

---

## Biological rationale

After severe influenza (PR8/H1N1) injury, p63⁺/KRT5⁺ distal-airway progenitors
(DASCs / LNEPs) expand and migrate into denuded alveolar zones, forming ectopic
**KRT5⁺ "pods"** — a marker of dysplastic, non-resolving repair that competes
with functional AT1/AT2 alveolar regeneration.

- **Reference result:** IFN-γ **receptor** KO reduces the pod region.
- **This study's question:** does IFN-γ **ligand** KO (Het / KO on a PR8
  background) also reduce it, while tracking the immune (CD4/CD8) and
  regenerative (AT1/AT2) response?

Two caveats that the image analysis cannot resolve, and does not pretend to:

- Global IFN-γ **ligand** KO alters injury severity differently from epithelial
  **receptor** KO. Keep an independent viral-clearance control (NP stain or
  qPCR) outside this pipeline; it does not correct for differences in viral
  load.
- With one animal per genotype × condition cell, genotype and condition cannot
  be separated at all. See "What is not established".

### The endpoint, and its correction

The reference endpoint (Lin et al. 2024) is

```
dysplastic fraction = KRT5+ PDPN+ area / (PDPN- OR KRT5+) area
```

where the denominator is **hand-traced** on a whole-lobe mosaic. This repository
first implemented the numerator with the sign inverted (KRT5⁺PDPN⁻) and the
denominator as a computed AGER-density damage mask, and quoted the paper to
justify both. Reading the figure legends verbatim showed the opposite. The
corrected specification is
[`config/endpoints/dysplastic_over_damaged.json`](config/endpoints/dysplastic_over_damaged.json);
it declares the relation as data, records what it supersedes and why, and is
evaluated by mask algebra rather than by editing the engine. It has not yet been
run over the data.

## Panels and marker configuration

### Priority project panels

| Preset | Acquisition channels | Tracked cell counts | Area readouts |
|---|---|---|---|
| `LEFT` | C1 DAPI; C2 KRT5-488; C3 AGER-555; C4 T1alpha-647 | KRT5, AGER, T1A final positive/negative/indeterminate | KRT5 pod area; AGER and T1A membrane-positive area |
| `RIGHT` | C1 DAPI; C2 Pro-SPC-488; C3 AGER-555; C4 KRT8-647 | ProSPC, AGER, KRT8 final positive/negative/indeterminate | AGER membrane-positive area only |
| `ALI1` | C1 DAPI; C2 SCGB3A2-488; C3 tdTOM; **C4 p63-647** | C4 p63 primary; SCGB3A2 and tdTOM secondary | tdTOM reporter area; transitional SCGB3A2⁺/p63⁺ class |
| `ALI2` | C1 DAPI; C2 KRT5-488; C3 tdTOM; **C4 AcTub-647** | C4 AcTub primary, filtered to cilia-like tufts | apical ciliary area/components; KRT5 and tdTOM area |
| `ALI3` | C1 DAPI; C2 KRT5-488; C3 tdTOM; **C4 MUC5AC-647** | C4 MUC5AC primary | apical MUC5AC area/components; KRT5 and tdTOM area |
| `ALI1_MAP` | C1 DAPI; C2 SCGB3A2-488; C3 tdTOM | 4× mapping subset; p63 absent, no call | tdTOM reporter area |
| `ALI23_MAP` | C1 DAPI; C2 KRT5-488; C3 tdTOM | 4× mapping subset; C4 absent, no call | KRT5 and tdTOM reporter area |

Marker identity and acquisition index are authoritative; displayed colours are
not. `T1A` is the output label for T1alpha/podoplanin and `ProSPC` for
Pro-SPC/SFTPC.

Original study panels, retained:

| Panel | Channels (acquisition order) | Purpose | Key classification |
|:-----:|---|---|---|
| **A** | DAPI · KRT5 · AGER | Pod area + AT1 (RAGE) boundary | KRT5⁺ area vs AGER⁺ area |
| **B** | DAPI · KRT5 · Pro-SPC | Regeneration readout (AT2) | KRT5⁻/Pro-SPC⁺ |
| **C** | DAPI · KRT5 · CD8 | Cytotoxic T-cell infiltrate | CD8⁺, KRT5⁺/CD8⁺ |
| **D** | DAPI · KRT5 · CD4 | Helper T-cell infiltrate | CD4⁺, KRT5⁺/CD4⁺ |
| **P** | DAPI · KRT5 · PDPN | AT1 (T1-α/podoplanin) | **KRT5⁺/PDPN⁺** (corrected sign) |
| **S** | DAPI · KRT5 · Sox2 | Airway/epithelial (optional) | KRT5⁺/Sox2⁺, KRT5⁺/Sox2⁻ |
| **S2** | DAPI · KRT5 · p63 · YAP | Scheme 2 (future) mechanistic | KRT5⁺/p63⁺, KRT5⁺/YAP⁺ |

Panel A's KRT5⁺/AGER⁻ classification is **retired**: AGER co-negativity was
tested and retracted (R ≈ 0.99–1.05; the removal was definitional, not
biological). See [`docs/NEGATIVE_RESULTS.md`](docs/NEGATIVE_RESULTS.md).

### Marker roles the engine understands

- **nuclear** (DAPI) — segmentation channel.
- **cyto** (KRT5, KRT8, Pro-SPC, CC10, tdTomato) — perinuclear ring.
- **membrane** (AGER, PDPN, T1A, mRAGE, CD4, CD8) — local membrane-support ring.
- **nuc_marker** (p63, Sox2) — inside the nucleus.
- **nuc_ratio** (YAP) — nucleus vs a true cytoplasmic ring; needs a single Z
  plane.
- **apical_cilia** (acetylated α-tubulin) — thresholded apical patches plus an
  explicitly approximate nucleus-proximity association.
- **regional_area** (COL1A1, CTHRC1, ACTA2, mucins) — positive-area mask with no
  per-nucleus call.

For a new study, copy
[`config/custom_panels.example.json`](config/custom_panels.example.json), edit
the channel indices and marker set, and load it with `IFQ_PANEL_CONFIG`. Do not
edit the built-in `PANELS`: a study-owned JSON keeps the channel map versioned
independently from the engine. The registry is not a whitelist; an unknown
marker can be used by declaring its role explicitly. See
[`docs/UNIVERSAL_MARKER_CONFIGURATION.md`](docs/UNIVERSAL_MARKER_CONFIGURATION.md)
and
[`docs/COMPARTMENT_TAGS_AND_PROGRESSION.md`](docs/COMPARTMENT_TAGS_AND_PROGRESSION.md).

### Morphology-first call authority

The final marker call has three states: positive, negative, or **indeterminate**.
An intensity cutoff defines candidate positive pixels; a final positive then
requires the marker-specific minimum spatial coverage, connected pattern,
localisation/enrichment rule, unique ownership where applicable, and anatomical
context policy. A known incompatible compartment, missing context with no
positive evidence, invalid YAP projection, empty support, or support shared
between cells yields **indeterminate rather than negative** — an unresolved
anatomy is never silently counted as a biological negative.

`<marker>_pos` is the legacy mean-intensity audit value. Classifications and
counts use `<marker>_final_call` and never the raw intensity result. Adaptive
Otsu calls are explicitly exploratory; confirmatory analysis requires fixed,
control-derived `IFQ_<MARKER>_THRESHOLD` values.

## Where things happen in the code

| Capability | Implementation |
|---|---|
| Read original files via Bio-Formats, keep metadata and calibration | `bfOpen()` — no autoscale |
| Split channels, preserve Z-stack and calibration | `ChannelSplitter.split` + `projectChannel` (calibration re-applied) |
| Tissue / lesion ROIs | `resolveTissueRois()` — manual `RoiSet.zip`/`.roi` if present, else auto from DAPI |
| Nuclei and cells | `segmentNuclei()` — StarDist preferred, classic watershed fallback; perinuclear ring is the "cell" |
| KRT5⁺ pod area and counts | independent threshold mask → `positiveAreaInRoi` + Analyze Particles |
| Membrane markers (AGER / PDPN / T1A) | ring/membrane support measurement |
| Relations between two markers | `endpoints/evaluate_endpoints.groovy`, mask algebra outside the engine |
| Every threshold, filter, plugin version, parameter | `*__params.json` + `run_manifest.json`; resolved thresholds also in `run_summary.csv` |

## Input data expectations

- **Formats:** anything Bio-Formats reads (`.czi`, `.lif`, `.nd2`, `.oir`,
  `.oib`, `.oif`, `.ics`, `.tif/.tiff`). Slide-scanner `.vsi` is deliberately
  **not** in this list — the engine never opens one directly; QuPath tiles it
  first. Never point anything at a `.ets` file: those are internal pyramid tiles
  inside the hidden `_<name>_` folder, and reading one gives a partial image.
- **Calibration:** positive square-pixel dimensions in micrometres are required.
  The pipeline stops rather than silently treating pixels as micrometres.
- **Metadata resolution order** per file:
  1. `samplesheet.csv` row matched by `relative_path`, then exact `filename` —
     preferred; `relative_path` is required when filenames repeat in a recursive
     tree;
  2. otherwise the filename convention `mouseID_condition_panel_section.ext`
     (e.g. `m01_PR8_A_s1.czi`);
  3. otherwise the script defaults (`PANEL`, `genotype = NA`).

`IFQ_OUTPUT_DIR` must be empty, which prevents stale masks and cell tables from
a previous run being mixed with new ones. Set
`IFQ_ALLOW_NONEMPTY_OUTPUT=true` only after reviewing the existing folder.

## Configuration reference (most-used knobs)

| Parameter | Meaning |
|---|---|
| `IFQ_PANEL_MAP_PATH` | `relative_path,panel` CSV for strict per-image routing; generated by launcher AUTO and copied into results |
| `IFQ_SEGMENTER` | `stardist` (preferred) or `classic` watershed (headless-safe) |
| `STARDIST_PROB` / `STARDIST_NMS` | detection probability / overlap thresholds |
| `IFQ_PROJECTION` | `max` (CLI default), `layer_aware` marker-specific slabs, `sum`, `avg`, `single` |
| `IFQ_SINGLE_PLANE` | 1-based plane index when projection is `single`; `-1` = middle |
| `IFQ_Z_NUCLEAR_RANGE` / `IFQ_Z_CELL_BODY_RANGE` / `IFQ_Z_APICAL_RANGE` | `full`, `auto`, or inclusive `start:end` |
| `IFQ_Z_CELL_BODY_PLANES` / `IFQ_Z_APICAL_PLANES` | automatic slab widths; defaults 5 and 3 planes |
| `IFQ_DAPI_METHOD` | `local_phansalkar` or `global_otsu`; layer-aware mode defaults to global Otsu |
| `IFQ_DAPI_BACKGROUND_RADIUS_UM` / `IFQ_DAPI_LOCAL_RADIUS_UM` / `IFQ_DAPI_BLUR_SIGMA_PX` | DAPI preprocessing; the defaults were tuned on slide-scanner data and are a live calibration question on confocal fields |
| `IFQ_MIN_NUCLEUS_AREA_UM2` | size floor for accepted nuclei |
| `IFQ_MIN_INCLUDED_NUCLEI` | minimum included nuclei per region; default `1` prevents a false-success zero-cell result |
| `IFQ_RING_EXPAND_UM` | perinuclear ring width for the cytoplasm/membrane proxy |
| `IFQ_ALLOW_NONEMPTY_OUTPUT` | `false` by default; prevents stale mixed-run exports |
| `POD_MIN_AREA_UM2` / `POD_THRESH_METHOD` | pod particle size floor and auto-threshold method |
| `POS_SENSITIVITY` | per-marker multiplier on the auto threshold (`>1` stricter) |
| `IFQ_MORPHOLOGY_PRIMARY` | must remain `true`; intensity-only final calls are rejected |
| `IFQ_WHOLE_FIELD_COMPARTMENT` | explicit context for a reviewed homogeneous field: `airway`, `alveolar`, `tumor`, `fibrotic`, `stromal`, `vascular`, `immune`, `ambiguous`, `unassigned` |
| `IFQ_<MARKER>_THRESHOLD` | fixed control-derived candidate-pixel cutoff (non-alphanumerics are stripped: `tdTOM` → `IFQ_TDTOM_THRESHOLD`) |
| `IFQ_<MARKER>_MIN_POSITIVE_FRACTION` | minimum fraction of role-specific support above cutoff |
| `IFQ_<MARKER>_MIN_LARGEST_COMPONENT_SHARE` | minimum connectedness of positive support |
| `IFQ_ACTUB_*` | cilia-specific seed percentile, local density, support width, and patch-area bounds |
| `IFQ_EXPORT_DISPLAY_CHANNELS` / `IFQ_DISPLAY_*` | display-only enhancement; never touches the quantitative branch |
| `TISSUE_THRESH_METHOD` / `TISSUE_MIN_AREA_UM2` | auto tissue detection when no manual ROI |
| `IFQ_WSI_*` | Stage 1 tiling only — see [`docs/WSI_TILING_WORKFLOW.md`](docs/WSI_TILING_WORKFLOW.md) |

## Outputs

```
OUTPUT_DIR/
├── run_summary.csv                     # per-region summary — input to aggregate_to_mouse.py
├── run_manifest.json                   # versions, full config, per-image status
└── <image_stem>/
    ├── <stem>__cells.csv               # one row per nucleus/cell
    ├── <stem>__params.json             # parameters, calibration, channel map, thresholds
    ├── <stem>__z_plane_profile.csv     # per-plane signal profile and selected Z ranges
    ├── <stem>__<region>__QC.png        # tissue (white), nuclei (cyan), KRT5⁺ (magenta), pods (yellow)
    ├── <stem>__<region>__nuclei_mask.tif
    ├── <stem>__<region>__<marker>_morphology_positive_nuclei_mask.tif
    ├── <stem>__<region>__<marker>_indeterminate_nuclei_mask.tif
    ├── <stem>__<region>__<marker>_CALL_QC.png
    ├── <stem>__KRT5_pod_mask.tif
    └── <stem>__T1A_membrane_positive_mask.tif    # analogous AGER/PDPN/mRAGE masks
```

Every display PNG is labelled **DISPLAY ONLY - NOT QUANTIFIED** and the merged
figure **VISUAL MERGE PANEL - NOT QUANTIFIED**. Percentile stretching and gamma
are applied to duplicate 8-bit copies; segmentation, thresholds, masks,
morphology features and final calls use the original calibrated projections.

A confocal run is about 7.6 GB: 5.9 GB of uncompressed 2048² mask TIFFs, 1.7 GB
of QC PNGs, and 2.7 MB of actual numbers. The masks must be kept — the endpoint
scripts do mask algebra on them — but they compress 50–100×.

Key `run_summary.csv` columns (marker columns vary by panel):

- `image, output_key, mouse_id, section_id, genotype, condition, panel, region`
  — identifiers.
- `region_area_um2, n_nuclei` — denominators.
- `n_nucleus_candidates_total`, acceptance/rejection fractions, rejection-reason
  counts — segmentation QC. Watch these: they are what exposed the
  `blackBackground` bug.
- `<marker>_pos_threshold`, `<marker>_threshold_source` — the resolved cutoff and
  whether it was fixed or adaptive. **Check this before quoting any number.**
- `<marker>_morphology_pos_count`, `_morphology_negative_count`,
  `_indeterminate_count`, `_morphology_evaluable_count` — the three-state summary.
- `<marker>_final_positive_cell_count` and the `_fraction_of_total_cells`
  companions — the primary per-cell tracking fields.
- `<marker>_marker_evidence_pos_count`, `_context_unresolved_positive_count`,
  `_context_excluded_evidence_positive_count` — strict evidence before or
  alongside anatomical eligibility, so context loss is visible instead of
  resembling a biological negative.
- `KRT5_pod_area_um2`, `KRT5_pod_area_frac`, `KRT5_n_pods`, `KRT5_pod_threshold`
  — the area endpoint.
- `<marker>_positive_area_um2`, `_positive_area_frac`, `_n_components`,
  `_area_mode` — morphology-neutral regional fields.
- `class_<rule>_count`, `_evaluable_count`, `_indeterminate_count` — every
  declared class is emitted even when all cells are indeterminate.

After `aggregate_to_mouse.py`:

- **`mouse_level_summary.csv`** — one row per (mouse × panel); fractions and
  densities are **area-weighted** across sections (`Σ area / Σ area`, not a mean
  of percentages).
- **`group_level_summary.csv`** — mean / sd / sem / `n_mice` per
  (genotype × condition × panel × metric).

## Statistics: n is mice

Three technical sections from one animal are still n = 1. `mouse_id` and
`section_id` travel through every export precisely so the collapse to animal
happens before any test. `aggregate_to_mouse.py` does that collapse
(area-weighted) and reports `n_mice`. It deliberately computes no p-values:
export `mouse_level_summary.csv` and apply the appropriate test elsewhere.

For the current batch that is moot. **n = 1 per genotype × condition cell, and
genotype is confounded with condition.** The numbers in this repository describe
individual animals and support no inference. Reporting them as group results
would be wrong regardless of how they were computed.

## Threshold-tuning workflow

1. Run 2–3 representative images with default settings.
2. Open the `__QC.png` and `__CALL_QC.png` overlays. Are nuclei split correctly?
   Do positives sit where you would put them by eye? Check the nucleus
   acceptance/rejection fractions, not only the picture.
3. Adjust: under-segmented nuclei → `STARDIST_PROB` / `STARDIST_NMS`; too
   many/few positives → `POS_SENSITIVITY[marker]`; pod mask too greedy or sparse
   → `POD_THRESH_METHOD`, `POD_BLUR_SIGMA_PX`, `POD_MIN_AREA_UM2`.
4. Derive fixed cutoffs from **controls only**, without consulting the
   experimental groups' outcomes.
5. Freeze every parameter and batch the cohort with identical settings. Never
   tune per image across a cohort.

A marker that is constitutively expressed gives no calibration handle from "the
control should be negative" — an adaptive threshold moves with the data and
cannot detect a shift. That is why AGER and T1A remain exploratory here, and it
is the same trap that made KRT5 uncalibratable on the slide scanner and KRT8
undiagnostic on confocal.

## Caveats and interpretation

- **AGER/PDPN are thin membrane signals.** Per-cell mean intensity is weak; area
  relationships are more robust than per-object AT1 calls.
- **Projection matters.** MAX projection is fine for pod area. For YAP it
  corrupts the nuclear:cytoplasmic ratio — use `IFQ_PROJECTION=single`.
- **Automatic Z ranges are pilot settings.** Replace them with fixed ranges after
  QC. See [`docs/Z_STACK_ANALYSIS.md`](docs/Z_STACK_ANALYSIS.md).
- **Adaptive thresholds are exploratory placeholders**, and the engine labels
  them as such. Confirmatory results require fixed marker cutoffs and frozen
  morphology parameters.
- **Machine limits shape what is possible.** Snapdragon X Oryon, 8 cores,
  15.6 GB RAM, ARM64. A full-resolution slide is 19.3 GB, which is why tiling is
  mandatory and why the PDPN ceiling has still not been measured at full
  resolution. Keep `-Xmx` at or below ~40 % of RAM; earlier `-Xmx12g` runs paged
  to disk and the symptom looked like slow I/O.

## Reproducibility and provenance

Every run records ImageJ, Bio-Formats, Java and OS versions
(`run_manifest.json`); the full configuration including segmenter, projection,
`blackBackground`, tissue method and sensitivities; per-image calibration and
channel→marker map; and the **resolved numeric thresholds** for pods and each
marker. StarDist/CSBDeep model and parameters are captured; exact plugin build
strings are not reliably queryable from a script and must be noted from
`Help ▸ Update`.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INPUT_DIR is not a folder` | set `IFQ_INPUT_DIR` before launching Fiji |
| `OUTPUT_DIR is not empty` | use a new run directory; do not mix old and new masks |
| `No images matched` | `IFQ_INCLUDE_REGEX` is a full match against the absolute path |
| `Found N channels but panel references channel M` | fix the panel `idx` map or samplesheet panel; the image is recorded as failed |
| StarDist errors / not found | install CSBDeep + StarDist, or set `IFQ_SEGMENTER=classic` |
| Nucleus counts implausibly low, masks look like rim fragments | the `blackBackground` failure mode; check that candidate components are not ~100 % border-touching, and see `IF_Quant_Pipeline.groovy:1765-1783` |
| Areas inverted or zero | the pipeline forces `blackBackground=true`; if you edited masking, keep foreground = 255 |
| Densities 10–100× off | check the embedded calibration (µm/pixel) in the source files |
| Fiji `.exe` will not start (ARM64) | invoke the bundled JVM directly, as in the field-route example above |
