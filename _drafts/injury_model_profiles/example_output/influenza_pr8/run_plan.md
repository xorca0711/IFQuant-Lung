# Run plan: influenza_pr8 v1.0.0

Generated 2026-08-07T04:45:56Z by tools/model_profile_to_run.py. Do not hand-edit; edit the profile and regenerate.

## Validation gate

* requested tier: **exploratory**
* authored profile status: `pilot_tuned`
* COMPUTED status (weakest endpoint-critical parameter): **`pilot_tuned`** via `pod_min_area_um2`

Gate passed for tier `exploratory`.

> BLOCKING CONFOUND OPEN: ifng_alters_viral_clearance -- A global IFN-gamma ligand knockout can alter viral clearance. If KO animals clear PR8 more slowly (or faster), they sustain a different amount of alveolar destruction, so a difference in KRT5+ pod area may be an infection-severity difference rather than a difference in the dysplastic response. (needs: Influenza NP immunostaining on a serial section, or NP qPCR. No image analysis can substitute for it.)

> BLOCKING CONFOUND OPEN: airway_krt5_in_numerator -- Conducting airways are not excluded. Airway basal cells are KRT5+ in every animal, so the numerator carries an airway component whose size depends on how much airway each tile happens to contain. (needs: Manual airway polygons in QuPath, drawn blinded with the KRT5 channel off, exported as GeoJSON in slide coordinates and subtracted per tile by Stage 1.)

> BLOCKING CONFOUND OPEN: n_of_one -- n = 1 mouse per group cell. With n = mice as the statistical unit, no group comparison is possible; any apparent difference is a single-animal anecdote. (needs: More mice.)

### Endpoint-critical parameter ladder

| parameter | status | rank |
|---|---|---|
| `pod_min_area_um2` | pilot_tuned | 1 |
| `krt5_pos_threshold` | control_derived | 2 |
| `panel_key` | control_derived | 2 |
| `wsi_ager_channel` | control_derived | 2 |
| `wsi_panel_key` | control_derived | 2 |
| `wsi_ager_threshold` | frozen_blinded_controls | 3 |
| `wsi_damage_cutoff` | frozen_blinded_controls | 3 |
| `wsi_damage_sigma_um` | frozen_blinded_controls | 3 |
| `wsi_partition_damage` | frozen_blinded_controls | 3 |
| `wsi_roi_name_damaged` | frozen_blinded_controls | 3 |
| `wsi_roi_name_intact` | frozen_blinded_controls | 3 |

### Endpoint-critical but NOT YET IMPLEMENTED (excluded from the ladder, blocks confirmatory)

| parameter | status | tracked by confound |
|---|---|---|
| `airway_dilation_um` | unset | `airway_krt5_in_numerator` |

## Primary endpoint

`ectopic_pod_area_fraction`

> KRT5+ area divided by DAMAGED ALVEOLAR area, where damaged alveolar area is parenchyma lacking AT1 coverage (AGER-negative territory measured at alveolar scale). Both terms are measured inside the same parenchymal ROI on the same section.

* numerator column: `KRT5_pod_area_um2` (SUM)
* denominator column: `region_area_um2` (SUM), scope `partitioned_damaged`
* mouse-level column to report: `KRT5_pod_area_frac`
* aggregation path: tile rows (2 per tile: parenchyma_damaged + parenchyma_intact) -> aggregate_tiles_to_slide.py keeps ONLY the 'damaged' rows as endpoint rows and sums them into one slide row whose region_area_um2 IS the damaged area (aggregate_tiles_to_slide.py:225-228, 272-284) -> aggregate_to_mouse.py pools slide rows per (mouse_id, genotype, condition, panel) and recomputes KRT5_pod_area_frac = sum(KRT5_pod_area_um2) / sum(region_area_um2) (aggregate_to_mouse.py:325-332) -> group_stats with n = mice.

## Stage 1 environment (QuPath WSI tile export)

```powershell
$env:IFQ_WSI_AGER_CHANNEL = '2'   # wsi_ager_channel [control_derived]
$env:IFQ_WSI_AGER_THRESHOLD = '150'   # wsi_ager_threshold [frozen_blinded_controls]
$env:IFQ_WSI_COMPRESSION = 'ZLIB'   # wsi_compression [not_applicable]
$env:IFQ_WSI_CORE_PX = '2048'   # wsi_core_px [pilot_tuned]
$env:IFQ_WSI_DAMAGE_CUTOFF = '0.14'   # wsi_damage_cutoff [frozen_blinded_controls]
$env:IFQ_WSI_DAMAGE_SIGMA_UM = '40'   # wsi_damage_sigma_um [frozen_blinded_controls]
$env:IFQ_WSI_HALO_PX = '128'   # wsi_halo_px [pilot_tuned]
$env:IFQ_WSI_PANEL = 'LEFT'   # wsi_panel_key [control_derived]
$env:IFQ_WSI_PARTITION_DAMAGE = 'true'   # wsi_partition_damage [frozen_blinded_controls]
$env:IFQ_WSI_ROI_NAME_DAMAGED = 'parenchyma_damaged'   # wsi_roi_name_damaged [frozen_blinded_controls]
$env:IFQ_WSI_ROI_NAME_INTACT = 'parenchyma_intact'   # wsi_roi_name_intact [frozen_blinded_controls]
$env:IFQ_WSI_INPUT = 'D:/Confocal_Images/20260806_CW/20260806_CW/'
$env:IFQ_WSI_OUTPUT = 'D:/wsi_stage1'
```

## Stage 2 environment (frozen Fiji engine)

```powershell
$env:IFQ_KRT5_THRESHOLD = '500'   # krt5_pos_threshold [control_derived]
$env:IFQ_MIN_INCLUDED_NUCLEI = '0'   # min_included_nuclei [not_applicable]
$env:IFQ_MORPHOLOGY_PRIMARY = 'true'   # morphology_primary [not_applicable]
$env:IFQ_PANEL = 'LEFT'   # panel_key [control_derived]
$env:IFQ_PROJECTION = 'max'   # projection [not_applicable]
$env:IFQ_SEGMENTER = 'classic'   # segmenter [not_applicable]
$env:IFQ_INPUT_DIR = 'D:/wsi_stage1/slideA/tiles'
$env:IFQ_OUTPUT_DIR = 'D:/wsi_stage1/slideA/analysis'
$env:IFQ_MARKER_REGISTRY = 'C:/Users/dream/Documents/GitHub/IFQuant-Lung/config/lung_marker_registry.json'
```

`scripts/Invoke-Stage2Sharded.ps1` sets IFQ_INPUT_DIR, IFQ_OUTPUT_DIR, IFQ_PANEL,
IFQ_SEGMENTER, IFQ_MIN_INCLUDED_NUCLEI and the three threshold parameters itself, and
inherits everything else from the parent process. So dot-source `stage2_env.ps1` FIRST,
then call the shard runner, and pass the thresholds it knows about explicitly:

```powershell
. .\stage2_env.ps1
.\scripts\Invoke-Stage2Sharded.ps1 -TilesDir <tiles> -OutputRoot <out> -Shards 5 `
    -Panel 'LEFT' -Krt5Threshold 500
```

## Stage 3 and 4 -- unchanged, and NOT parameterised by this profile

```bash
python aggregate_tiles_to_slide.py --slide-root <stage1 output root>
python aggregate_to_mouse.py <slide_summary.csv or run_summary.csv> --outdir ./stats
```

Report `KRT5_pod_area_frac` from `mouse_level_summary.csv` and `n_mice` from `group_level_summary.csv`.

## Samplesheet contract

* allowed `genotype`: ['het', 'hom']
* allowed `condition`: ['uninfected', 'PR8_d28']
* allowed `panel`: ['LEFT', 'RIGHT']

* mouse_id must never be NA/blank/UNKNOWN; aggregate_to_mouse.py exits on it (lines 76-85).
* One mouse_id must map to exactly one (genotype, condition); the aggregator exits on a conflict (lines 87-95).
* Timepoint belongs INSIDE the condition token. There is no timepoint column and adding one would fork the grouping.
* Token case and spelling are load-bearing: 'het' and 'Het' become two different groups.
* (image|output_key, region, section_id, panel) must be unique across all rows (lines 97-111).

Validate a real sheet with:

```bash
python tools/model_profile_to_run.py <profile.json> --validate-samplesheet <samplesheet.csv>
```

## Controls required by this model

* **ctrl_uninfected_het_m4_2** (biological_negative) -- controls for: false-positive rate of the damage detector on healthy lung; KRT5 background inside intact alveolar parenchyma; AGER staining intensity drift (paired with inf_het_m4_1: in-tissue p50 304 vs 314)
  * does NOT control for: airway KRT5 - there is no secondary-only section for this dataset, so airway basal KRT5 is uncontrolled; antibody specificity (no isotype or secondary-only control exists); autofluorescence in the 488 band, which is a live concern at the ~949 ms KRT5 exposure versus ~0.5-2 ms elsewhere; whether AGER survives in consolidated regions of INFECTED lung - by construction there are none here
* **ctrl_uninfected_hom_m6** (biological_negative) -- controls for: that the damage detector does not read genotype in the absence of infection; second independent estimate of the KRT5 background floor
  * does NOT control for: airway KRT5; antibody specificity; the 5x staining-intensity spread seen against m4-2 - it is the EASY control and would have masked m4-2 under a mean-based rule
* **genotype_het_as_control** (genotype_control) -- controls for: background strain, housing, infection batch, staining batch
  * does NOT control for: gene dosage. A heterozygous Ifng+/- often retains enough cytokine to signal normally, in which case het is a control - but if it is haploinsufficient in this context, het is a THIRD arm and the study has no true wild-type. Confirm the line before interpreting any pod difference (docs/ECTOPIC_POD_ENDPOINT.md:303-305).
* **secondary_only** (secondary_only) -- controls for: secondary-antibody background; the 488-band autofluorescence floor at the long KRT5 exposure
  * does NOT control for: primary-antibody cross-reactivity; biological KRT5 in airway
* **manual_outline_subset** (technical_replicate) -- controls for: agreement between the automated endpoint and the published manual method
  * does NOT control for: operator-to-operator variability in the manual method itself, unless two operators draw the same subset

## Confounds

* **ifng_alters_viral_clearance** [open, interpretation_ending, bias either] -- A global IFN-gamma ligand knockout can alter viral clearance. If KO animals clear PR8 more slowly (or faster), they sustain a different amount of alveolar destruction, so a difference in KRT5+ pod area may be an infection-severity difference rather than a difference in the dysplastic response.
  * orthogonal assay: Influenza NP immunostaining on a serial section, or NP qPCR. No image analysis can substitute for it.  **(BLOCKING)**
* **airway_krt5_in_numerator** [open, major, bias toward_null] -- Conducting airways are not excluded. Airway basal cells are KRT5+ in every animal, so the numerator carries an airway component whose size depends on how much airway each tile happens to contain.
  * orthogonal assay: Manual airway polygons in QuPath, drawn blinded with the KRT5 channel off, exported as GeoJSON in slide coordinates and subtracted per tile by Stage 1.  **(BLOCKING)**
* **ager_negativity_as_airway_detector** [resolved, interpretation_ending, bias toward_null] -- Airway epithelium is AGER-negative and so is severely injured alveolar parenchyma. An 'exclude AGER-poor regions as airway' rule would preferentially delete the pods it is meant to measure.
  * orthogonal assay: none - this is a design prohibition, not a measurement gap. AGER-negative as DENOMINATOR is correct; AGER-negative as AIRWAY DETECTOR is forbidden (docs/ECTOPIC_POD_ENDPOINT.md:59-74).
* **per_image_adaptive_thresholds** [resolved, interpretation_ending, bias toward_hypothesis] -- Otsu assumes a bimodal distribution. On a slide with almost no KRT5 signal there is no second mode, so it splits noise: uninfected m6 read 4.95% KRT5+ at an Otsu threshold of 54.6, indistinguishable from an infected animal. With per-slide adaptive AGER thresholds the damage comparison INVERTED at all 15 parameter combinations tested.
  * orthogonal assay: none - fixed control-derived thresholds.
* **krt5_channel_exposure_autofluorescence** [open, moderate, bias toward_hypothesis] -- The KRT5/488 channel was acquired at ~949 ms versus ~0.5-2 ms for the other channels, so autofluorescence in that band is amplified relative to everything else and argues for a conservative threshold.
  * orthogonal assay: Secondary-only or unstained section at identical exposure.
* **n_of_one** [open, interpretation_ending, bias either] -- n = 1 mouse per group cell. With n = mice as the statistical unit, no group comparison is possible; any apparent difference is a single-animal anecdote.
  * orthogonal assay: More mice.  **(BLOCKING)**

## Forbidden in this model

* Do NOT use AGER or PDPN negativity to detect conducting airway. The most damaged alveolar regions are the most AGER-poor, and that is precisely where pods form; an AGER-based airway filter would delete the pods and bias toward the null.
* Do NOT name a tile ROI 'alveolar_*' until airway polygons are actually subtracted. Naming asserts the claim: the engine tags compartments by substring on the ROI name (IF_Quant_Pipeline.groovy:2102-2109), so 'alveolar_core' on a pure-airway tile is a silent mislabel.
* Do NOT interpret the ambiguous/unassigned degradation of AGER and T1A calls as a KRT5 problem. With the neutral ROI name those two markers declare expectedCompartment 'alveolar' and correctly degrade to context_unresolved/indeterminate; the pod-area endpoint is unaffected.
* No model-specific aggregation script. aggregate_to_mouse.py is the only path from slide to mouse to group.
* No new KEY_COL. Timepoint lives inside the condition token; genotype lives in genotype; nothing else groups.
* No averaging of region-level or slide-level fractions. Fractions are always recomputed from pooled numerators and pooled denominators.
* No pooling across profile_version MAJOR bumps: a changed frozen threshold means the numbers are not the same measurement.

> **stage1_note**: Stage 1 refuses to partition without an explicit IFQ_WSI_AGER_THRESHOLD, by design. Do not add a default.

> **stage2_note**: Run via scripts/Invoke-Stage2Sharded.ps1. That script does NOT set thresholds; pass them or the engine falls back to per-tile adaptive Otsu, which on a background-dominated tile has reported KRT5_pod_area_frac ~0.89.

> **stage3_note**: aggregate_tiles_to_slide.py globs every run_summary.csv under the slide folder, so sharded output needs no extra bookkeeping. It reconciles against tile_manifest.csv; a coverage failure means tissue area went missing and the endpoint denominator is wrong.

> **stage4_note**: aggregate_to_mouse.py unchanged. Report KRT5_pod_area_frac from mouse_level_summary.csv, and n_mice from group_level_summary.csv as the real n.

> **blocking_note**: Three blocking confounds are OPEN (ifng_alters_viral_clearance, airway_krt5_in_numerator, n_of_one). Any output of this profile is pipeline validation, not a biological result.

