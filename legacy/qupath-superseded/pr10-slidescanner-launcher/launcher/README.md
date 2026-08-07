# QuPath SlideScanner Windows launcher

`IFQuantLauncher_QuPath_SlideScanner-v0.1.0.exe` is a Windows Forms front end for
`QuPath_SlideScanner_Quant.groovy` — whole-slide (slide-scanner) IF quantification
for the IFN-γ KO / PR8 influenza project. It is the slide-scanner sibling of the
Fiji-confocal `IFQuantLauncher`.

Like the Fiji launcher, it **embeds the exact analysis script at build time** and
does not reimplement image analysis; it invokes **QuPath's headless `script`
subcommand** once per slide and passes every parameter through `IFQ_*`
environment variables.

## Status
> ⚠️ **Not compiled or tested in the authoring environment** (no C# toolchain, no
> QuPath there). Build with `build.ps1` and smoke-test on one slide before use.
> The embedded Groovy script also has three QuPath-version-sensitive lines flagged
> in its header — validate them once, then batch.

## Build
From the repository root (Windows, .NET Framework 4.x present — no SDK needed):
```powershell
powershell -ExecutionPolicy Bypass -File .\launcher-qupath-slidescanner\build.ps1
```
Writes to the repo root:
- `IFQuantLauncher_QuPath_SlideScanner-v0.1.0.exe`
- `IFQuantLauncher_QuPath_SlideScanner-v0.1.0.sha256.txt`

The build embeds `QuPath_SlideScanner_Quant.groovy`, then runs the launcher's
`--self-test` (exit 0 = the embedded script is present and contains the required
morphology-primary / tissue-detection / summary markers).

Compiled `AnyCPU` — one file for Windows ARM64 and x64.

## Runtime requirements
- Windows ARM64 or x64 with .NET Framework 4.x.
- **QuPath v0.5.x** installed (use the **console** build so output is captured).
- Slide-scanner images reachable via a local/mapped/network folder.

## Workflow
1. **Slide file or folder** — pick a single slide or a folder (batch). Recognised:
   `.svs .ndpi .mrxs .scn .vsi .qptiff .bif .czi .tif/.tiff .ome.tif`.
2. **QuPath executable** — the QuPath launcher (console build recommended).
3. **Output folder**.
4. **Panel** — `A | B | C | D | P | S | S2` (must match the slide's channels).
5. Optional metadata (`mouse_id`, `section_id`, `genotype`, `condition`), tile
   size (µm; 0 = no tiling), and the nucleus-detection threshold.
6. **Run** — one QuPath process per slide; live log in the window.

Morphology-primary calling is **enforced** (`IFQ_MORPHOLOGY_PRIMARY=true`), matching
`IF_Quant_Pipeline.groovy` on `main`, so the confocal and slide-scanner engines agree
on what a positive cell is.

## Outputs (in the chosen output folder)
- `<slide>__cells.tsv` — per-cell detection measurements + calls.
- `qupath_slidescanner_summary.csv` — one row per slide (tissue area, per-marker
  positive/negative/indeterminate counts, densities/mm², pod area/fraction,
  class counts). Append-safe across a batch.
- `<slide>__params.json` — provenance (QuPath version, calibration, channel map,
  every parameter and morphology gate).

Then roll up to animal level (n = **mice**, not slides):
```
python3 aggregate_to_mouse.py <output>\qupath_slidescanner_summary.csv
```

## Parameters (env vars the script reads)
`IFQ_OUTPUT_DIR`, `IFQ_PANEL`, `IFQ_MORPHOLOGY_PRIMARY`, `IFQ_MOUSE_ID`,
`IFQ_SECTION_ID`, `IFQ_GENOTYPE`, `IFQ_CONDITION`, `IFQ_TILE_SIZE_UM`,
`IFQ_CELL_THRESHOLD`, `IFQ_ANALYSIS_DOWNSAMPLE`, `IFQ_TISSUE_DOWNSAMPLE`,
`IFQ_<MARKER>_MIN_POSITIVE_FRACTION`, `IFQ_<MARKER>_MIN_LARGEST_COMPONENT_SHARE`,
`IFQ_<MARKER>_SENSITIVITY`, … (see the script header for the full list).

## Relationship to the confocal engines
| Engine | Input | Launcher |
|---|---|---|
| `IF_Quant_Pipeline.groovy` (Fiji) | confocal fields, z-stacks | `IFQuantLauncher` |
| `QuPath_IF_Quant.groovy` | confocal / small fluorescence | (GUI / CLI) |
| **`QuPath_SlideScanner_Quant.groovy`** | **pyramidal whole slides** | **this launcher** |

All three write mouse-traceable summaries under the same morphology-primary call
model for the same downstream statistics.
