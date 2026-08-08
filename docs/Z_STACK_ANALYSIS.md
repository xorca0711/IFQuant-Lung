# Layer-aware Z-stack analysis

> **Status: REFERENCE — implemented, exercised on the ALI pilot only, and NOT
> used by the current study.**
>
> `IFQ_PROJECTION=layer_aware` exists in the engine and was exercised during the
> ALI organoid pilot that preceded this study (the global-Otsu vs local-Phansalkar
> result in "Environment settings" is from that pilot and is real). The merge gate
> that promoted it is recorded in [`BRANCHING.md`](BRANCHING.md).
>
> **It is dormant for the lung study.** The 260808-CW confocal acquisitions are
> single-plane (`SizeZ == 1`), and the whole-slide route rejects any series with
> `SizeZ != 1` outright. Nothing in the current results passed through this code
> path. The automatic Z ranges described here remain **pilot settings**: a
> confirmatory study must freeze explicit ranges before use.
>
> Kept because it is a preserved engine capability with a written policy, not
> because it is in use. Last checked: 2026-08-08.

## Scope

`IFQ_PROJECTION=layer_aware` adds a marker-specific 2.5D workflow without
changing the established global projection modes. It is intended for
multichannel Z-stacks in which nuclei, cell bodies, thin AT1 membranes, cilia,
and secreted mucin occupy different optical depths.

This mode does **not** claim true 3D cell-boundary reconstruction. It produces
restricted, auditable 2D slab projections and applies the existing
morphology-first decision hierarchy to those projections.

## Processing order

```text
Bio-Formats OIR/CZI/LIF/ND2 import
↓
Validate XY and Z calibration
↓
Split channels while retaining every Z plane
↓
Resolve one Z policy and inclusive plane range per marker
↓
Create the marker-specific restricted projection
↓
Segment DAPI and resolve tissue/anatomical context
↓
Apply marker-role morphology, connectedness, localization and ownership gates
↓
Export per-cell calls, regional endpoints, Z profiles and provenance
```

The Z range is resolved before marker thresholds or positive/negative calls.
Automatic range selection therefore cannot be changed by the experimental
group label or final cell classification.

## Z policies

| Policy | Intended use | Automatic reference |
|---|---|---|
| `full_stack` | DAPI or robust regional structures | all planes |
| `nuclear_stack` | p63, Ki-67 and other nuclear markers | configured nuclear range; full by default |
| `cell_body_slab` | keratins, secretory cytoplasm, reporters, Pro-SPC, membrane support | brightest contiguous DAPI window |
| `apical_slab` | AcTub, MUC5AC and other apical structures | brightest contiguous marker-channel window |
| `single_plane` | YAP or another explicitly local ratio | configured plane or brightest DAPI plane |
| `explicit_range` | study-frozen marker range | panel `zStart` and `zEnd` |

Registry `default_z_policy` values supply defaults. A study panel can override
the policy because the same antigen may need different geometry in a different
preparation.

## Environment settings

```powershell
$env:IFQ_PROJECTION = 'layer_aware'
$env:IFQ_Z_NUCLEAR_RANGE = 'full' # full | auto | 1-based start:end
$env:IFQ_Z_CELL_BODY_RANGE = 'auto'
$env:IFQ_Z_APICAL_RANGE = 'auto'
$env:IFQ_Z_CELL_BODY_PLANES = '5'
$env:IFQ_Z_APICAL_PLANES = '3'
```

When `IFQ_DAPI_METHOD` is not explicitly set, layer-aware mode uses
`global_otsu`; legacy projection modes retain the historical
`local_phansalkar` default. The validated dense ALI pilot produced 1,734
included nuclei with global Otsu, whereas local Phansalkar rejected every
candidate. The effective method and its source are recorded in provenance.

`IFQ_MIN_INCLUDED_NUCLEI` defaults to `1`. A region below that minimum is an
image failure, not a successful zero-cell result.

An explicit example:

```powershell
$env:IFQ_Z_NUCLEAR_RANGE = '2:10'
$env:IFQ_Z_CELL_BODY_RANGE = '3:7'
$env:IFQ_Z_APICAL_RANGE = '8:10'
```

Ranges are inclusive and 1-based. A range outside the available stack is a hard
error rather than being silently clipped.

## Per-panel overrides

```json
{
  "idx": 4,
  "marker": "AcTub",
  "role": "apical_cilia",
  "zPolicy": "apical_slab",
  "zProjection": "max"
}
```

For a prevalidated fixed range:

```json
{
  "idx": 4,
  "marker": "AcTub",
  "role": "apical_cilia",
  "zPolicy": "explicit_range",
  "zStart": 8,
  "zEnd": 10,
  "zProjection": "max"
}
```

`zProjection` accepts `max`, `avg`, `sum`, or `single`. Maximum projection is
the default within a restricted slab.

## Marker-specific interpretation

- **DAPI:** the full stack is projected for the current 2.5D segmentation. Dense
  fields with nuclei overlapping in XY still require a true 3D segmenter.
- **p63/Ki-67:** nuclear support is measured within the nuclear Z policy.
- **KRT5/KRT8/SCGB3A2/CC10/Pro-SPC/tdTomato:** use the DAPI-guided cell-body
  slab so signal at unrelated depths is not assigned to the nucleus.
- **AGER/T1alpha:** use cell-body/membrane support plus the regional membrane
  endpoint. Thin AT1 extensions make per-nucleus ownership a secondary result.
- **AcTub:** use the apical slab, then retain only high-intensity, locally dense,
  2–150 µm² cilia-like components. Regional area/components in this filtered
  mask are primary; the uniquely associated ciliated-cell call is secondary.
  Broad stable cytoplasmic microtubule signal is neither displayed nor used as
  cellular support.
- **MUC5AC:** use the apical slab. Mucin-positive area and clusters are primary;
  a goblet-cell count requires separately validated ownership.

## Outputs and QC

Each analyzed image now writes:

- `*__z_plane_profile.csv`: every marker and Z plane, mean and maximum
  intensity, selected/not-selected status, policy, projection and range source.
- `*__params.json`: resolved start/end planes, intensity-weighted Z centroid,
  automatic-selection score, voxel anisotropy, and the 2.5D limitation.
- `*__DISPLAY_ONLY__C#-<marker>_enhanced.png`: an individually labeled marker
  view using a recorded percentile display stretch and optional gamma.
- `*__VISUAL_MERGE_PANEL__merged_enhanced.png`: the labeled color merge assembled
  from those display copies.
- The existing call overlays, per-cell CSV, regional summaries, masks and
  workbook outputs.

The enhanced images are a separate visualization branch. They are converted
to 8-bit after display scaling and must not be used for quantitative intensity
measurement or threshold calibration. Original projected pixels remain the
source of segmentation, thresholds, masks, morphology features, and calls.

Before accepting a pilot:

1. Confirm channel order and Z calibration.
2. Plot or inspect the Z profile.
3. Confirm the cell-body slab follows the DAPI-rich epithelium.
4. Confirm AcTub/MUC5AC slabs follow the luminal/apical surface.
5. Check that the selected ranges are stable across positive and negative
   controls.
6. Replace `auto` with fixed ranges when acquisition geometry is consistent.
7. Freeze ranges, projection, thresholds and morphology gates before a cohort
   run.

## When true 3D is required

Use a validated anisotropy-aware 3D workflow when:

- nuclei overlap substantially in XY but are separate in Z;
- cell counts change materially with the chosen slab;
- ownership requires a 3D distance rather than a projected XY distance;
- volumetric fractions or object volumes are the scientific endpoint.

The portable Fiji pathway is 3D connected-component/watershed processing with
physical calibration. Cellpose or StarDist 3D may be used when their runtime and
model are available, but the model must be validated against manual 3D
annotations from the same acquisition type.
