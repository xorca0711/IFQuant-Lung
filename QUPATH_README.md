# QuPath variant — IF quantification (IFN-γ KO / PR8 influenza injury)

A QuPath port of `IF_Quant_Pipeline.groovy` (Fiji). **Same research scheme, panels,
markers, and readouts** — KRT5⁺ pod area/counts, AT1 (AGER/PDPN), AT2 (Pro-SPC),
immune (CD4/CD8), Sox2, and the double±/negative classifications (e.g. KRT5⁺/PDPN⁻).

- **`QuPath_IF_Quant.groovy`** — the analysis script.
- Pairs with `aggregate_to_mouse.py` for the mouse-level (n = mice) roll-up.

## Why also do it in QuPath?
- **True compartments.** QuPath measures **Nucleus / Cytoplasm / Cell** separately, so
  cytoplasmic and membrane markers (KRT5, AGER, PDPN, CD4/CD8) are read on the **ring only** —
  this avoids the "ring includes the nucleus" dilution the Fiji smoke test found.
- **Object-level thresholding.** Positivity Otsu runs on the distribution of **cell means**, not
  pixels — which sidesteps the pixel-vs-object over-call bias the Fiji run exposed (M3 at 99.2%).
- **Scales** to whole-slide / large tiled fluorescence better than ImageJ.

> ⚠️ QuPath analyses a **single 2D plane**. Project/flatten confocal z-stacks before import (or set
> the plane) — this script does not z-project. Bio-Formats handles the import + calibration.

## Requirements
- **QuPath v0.5.x** (https://qupath.github.io/). Bio-Formats is bundled.
- Optional: the **StarDist extension** for nuclei (otherwise the built-in Watershed cell detection
  is used — no extension needed).

## Launcher

### A) GUI (easiest for first validation)
1. Open your image in QuPath.
2. `Automate ▸ Script editor…` → open `QuPath_IF_Quant.groovy`.
3. Set `PANEL` and the channel **names** to match your image (the script prints the detected
   channel names if they don't match), then **Run** (▶).

### B) Headless / batch — command line
QuPath ships a `script` subcommand. Use the **console** launcher so you see output.

**Windows** (adjust the version/path; ARM note below):
```
"C:\Program Files\QuPath-0.5.1\QuPath-0.5.1 (console).exe" script ^
   -i "C:\path\to\image.ome.tif" ^
   "C:\...\QuPath_IF_Quant.groovy"
```

**macOS / Linux:**
```
/Applications/QuPath-0.5.1.app/Contents/MacOS/QuPath script \
   -i /path/to/image.ome.tif \
   /path/to/QuPath_IF_Quant.groovy
```

**Run over a whole project** (batch every image):
```
QuPath script -p /path/to/project.qpproj QuPath_IF_Quant.groovy
```

Flag names can differ slightly by build — check `QuPath script --help` (`-i/--image`, `-p/--project`).

> **Windows ARM64 note.** Like Fiji, a native Windows-ARM64 QuPath build may not exist; the x64
> build runs under emulation. macOS Apple-Silicon has a native build. If the launcher misbehaves,
> run the bundled Java directly against the QuPath jars (same pattern your Fiji run used).

## Configure (top of the script)
- `PANEL` — `A | B | C | D | P | S | S2`.
- `PANELS[...].channels[].name` — **must match your image's channel names** (script prints them).
- `CELL.threshold` / `CELL.cellExpansionMicrons` — nuclear detection cutoff and ring width (2 µm).
- `AUTO_THRESH` + `POS_SENS` — object-level Otsu × per-marker sensitivity (tune >1 if over-calling).
- `POD_METHOD` — `'cells'` (area of KRT5⁺ cells, robust) or `'threshold'` (raw KRT5⁺ pixel area,
  closest to the Fiji readout).

## Outputs (under `OUTPUT_DIR`)
- `<image>__cells.tsv` — per-cell measurements (QuPath detection table).
- `qupath_summary.csv` — one row per image: tissue area, counts, densities/mm², pod area/fraction,
  resolved thresholds, class counts. Append-safe across a batch.
- `<image>__params.json` — provenance: QuPath version, calibration, channel map, all parameters.

Then roll up to animal level:
```
python3 aggregate_to_mouse.py /path/to/OUTPUT_DIR/qupath_summary.csv
```
(the aggregator keys on `mouse_id`/`genotype`/`condition`/`panel`; if a column name differs from
the Fiji `run_summary.csv`, adjust the header once — the pooling logic is identical.)

## Validate once, then batch (this script is untested against a live QuPath)
Three lines are **version-sensitive** — confirm them in your QuPath build on the first GUI run,
then freeze and batch:
1. **Cell-detection param name** — `detectionImageFluorescence` (the 1-based nuclear channel).
   If detection finds 0 cells, check this key against your build's Watershed Cell Detection.
2. **Measurement names** — `"Cytoplasm: <ch> mean"` / `"Nucleus: <ch> mean"` / `"Cell: <ch> mean"`.
   Open one detected cell and read its measurement list; if lookups return null, match the exact
   strings (some builds prefix channel names differently).
3. **Export/measurement API** — `saveDetectionMeasurements(...)` and `measurementList.get(name)`
   (some builds use `getMeasurementValue(name)`).

Same discipline as the Fiji pipeline: tune on 2–3 images against the QuPath viewer, freeze
parameters, then run the cohort unchanged.

## Relationship to the Fiji pipeline
| | Fiji (`IF_Quant_Pipeline.groovy`) | QuPath (`QuPath_IF_Quant.groovy`) |
|---|---|---|
| Segmentation | StarDist / watershed | Watershed cell detection (StarDist optional) |
| Cytoplasm/membrane read on | ring (disc incl. nucleus in v1) | true Cytoplasm compartment |
| Positivity threshold | pixel Otsu in tissue | object-mean Otsu |
| Z-stacks | projects internally | project before import |
| Best for | confocal fields | fields + whole-slide/tiled |

Use whichever matches the data at hand; both write mouse-traceable summaries for the same stats.
