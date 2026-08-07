# Morphometry module — working build

An **AGER-independent architectural check** on the damaged-alveolar-area
denominator of the ectopic-pod endpoint. Rebuilt from
`claude/module-drafts:_drafts/morphometry/`, run against all four pilot slides.

Nothing here modifies the repo. `IF_Quant_Pipeline.groovy` is neither called nor
required. `aggregate_to_mouse.py` is used **unmodified** and is not forked.

## Read in this order

| file | what it is |
|---|---|
| **`RESULTS.md`** | the four-slide numbers and the cross-check verdict |
| **`REVIEW.md`** | what was wrong with the draft, with measurements |
| **`SCHEMA.md`** | output schema, every column marked SUM / RECOMPUTE / DROP |
| **`RESOLUTION.md`** | how each metric moves with pixel size, measured |
| **`STEREOLOGY_CAVEATS.md`** | what can and cannot be claimed; verified citations |
| **`PREREGISTERED_RULES.md`** | the decision rules, written before the numbers |

## Code

| file | role |
|---|---|
| `lung_morphometry.groovy` | QuPath host. Self-test, calibrate, sweep and measure modes. |
| `morphometry_derive.py` | forms every ratio from pooled primitives, after aggregation |
| `test_aggregation_contract.py` | 20 executed assertions against the real `aggregate_to_mouse.py` |
| `report_tables.py` | renders the tables in `RESULTS.md` |
| `fix_scope_names.py` | one-off label repair for CSVs written before the scope rename |
| `run/Invoke-Morphometry.ps1` | the runner; all locked parameters in one place |
| `run/Calibrate.ps1` | control-only threshold calibration |
| `run/Sweep.ps1` | resolution sweep at a fixed threshold |
| `run/analyse.sh` | aggregate → derive → tables |
| `probe/bench_upsample.groovy` | the 99× dynamic-vs-static loop benchmark |

## Reproduce

```powershell
# 1. self-test only: 43 synthetic-phantom checks, no data needed
$env:IFQ_MORPH_SELFTEST="true"
& "X:\QuPath\QuPath-0.7.0 (console).exe" script .\lung_morphometry.groovy

# 2. calibrate the tissue threshold on the CONTROL slides only
.\run\Calibrate.ps1

# 3. resolution sweep at the locked threshold
.\run\Sweep.ps1 -Threshold 880

# 4. measure
.\run\Invoke-Morphometry.ps1 -Output .\out_ds2 -TissueThreshold 880 `
                             -DsFine 2 -BlockStride 2
```

```bash
# 5. aggregate (UNMODIFIED aggregate_to_mouse.py) -> derive -> tables
bash run/analyse.sh out_ds2 out_ds4

# 6. prove the aggregation contract
python test_aggregation_contract.py \
  --agg <repo>/aggregate_to_mouse.py \
  --slide-csv out_ds2/morphometry_slide_summary_ds2.csv
```

## Locked parameters

| parameter | value | derived from |
|---|---|---|
| `IFQ_MORPH_CHANNELS` | `0` (DAPI) | the only channel independent of AGER (R1) |
| `IFQ_MORPH_TISSUE_THRESHOLD` | `880` | mean control in-ROI Otsu at ds 2 (R2) |
| `IFQ_MORPH_DS_COARSE` | `8` | the damage detector's own downsample |
| `IFQ_MORPH_DS_FINE` | `2` primary, `4` secondary | resolution sweep (R3) |
| `IFQ_WSI_AGER_THRESHOLD` / `SIGMA_UM` / `CUTOFF` | `150` / `40` / `0.14` | **unchanged**, the locked detector |
| `IFQ_MORPH_COMPARTMENT_ERODE_UM` | `40` | one damage-detector σ |

## One-line summary of the finding

The AGER-damaged compartment **is** architecturally different from the intact
compartment of the same lung, on measures that never touch AGER — and the
difference points the **opposite way** in infected and uninfected animals, which
is what makes it evidence about injury rather than about staining. The effect is
carried mainly by nuclear area fraction and surface density, is large in one
infected animal and modest in the other, and rests on a nuclear mask rather than
a true tissue mask. See `RESULTS.md` §3 for the full verdict and its limits.
