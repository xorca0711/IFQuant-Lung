# Active Quantification and Marker Morphology Workflow

This is the primary operational and interpretation reference for the current
morphology-first Fiji/ImageJ pipeline. Final marker calls are determined by
role-appropriate spatial morphology. Mean intensity is retained for audit, but
it does not authorize a positive or negative call.

The numeric morphology settings below are conservative pilot defaults, not
universal biological cutoffs. Derive intensity cutoffs and validate morphology
parameters using blinded negative and positive controls, then freeze them before
comparing experimental groups.

## Current branch and release status

| Line | Responsibility | Status |
|---|---|---|
| `main` | Universal morphology-first pipeline plus optional layer-aware Z routing and display enhancement | Current production branch |
| `codex/z-stack-analysis` | Integration history for layer-aware 2.5D Z routing, ALI presets, marker refinements, Z QC, display enhancement, and zero-cell protection | Validated and promoted to `main` |
| `codex/legacy-pre-reorganization` | Historical pre-reorganization snapshot | Retain as legacy history |
| Launcher | `IFQuantLauncher-v1.7.0.exe` in the repository root | Per-image AUTO panel/subset routing plus independent five-image enhanced-preview and full-analysis buttons |

The layer-aware implementation is additive. Legacy `max`, `sum`, `avg`, and
`single` projection modes retain their established behavior. The Windows GUI
recommends `layer_aware` for multichannel stacks, while command-line runs remain
explicitly configurable.

### Representative 20× Z-stack validation

The final built-in presets were run headlessly with ARM64 Fiji/ImageJ 1.54p99,
Bio-Formats 8.5.0, Java 21.0.7, classic segmentation, whole-field airway
context, and automatic layer-aware ranges:

| Preset | Stack | Resolved Z ranges | Included nuclei | Exploratory outputs | Manifest |
|---|---:|---|---:|---|---|
| `ALI1` | 10 planes | DAPI 1–10; SCGB3A2/tdTOM 4–8; p63 1–10 | 1,990 | SCGB3A2⁺ 51; tdTOM⁺ 644; p63⁺ 414 | complete |
| `ALI2` | 12 planes | DAPI 1–12; KRT5/tdTOM 1–5; AcTub 6–8 | 1,734 | KRT5⁺ 729; tdTOM⁺ 645; AcTub-associated cells 62; AcTub area fraction 0.0301 | complete |
| `ALI3` | 12 planes | DAPI 1–12; KRT5/tdTOM 8–12; MUC5AC 10–12 | 874 | KRT5⁺ 282; tdTOM⁺ 68; MUC5AC area fraction 0.2781; 543 components | complete |

These are software-validation outputs generated with adaptive exploratory
thresholds. They demonstrate that import, Z routing, segmentation, marker
decisions, workbook export, and provenance complete; they are not frozen
biological results. Review the QC images and replace automatic ranges and
thresholds with study-validated settings before inference.

The ALI3 field was rerun after enabling display enhancement. It exported four
individually labeled enhanced channels plus one enhanced merge. Its
quantitative outputs were exactly unchanged from the pre-enhancement run:
874 nuclei, 282 KRT5-positive cells, 68 tdTomato-positive cells, KRT5 area
fraction 0.1670835, tdTomato area fraction 0.1015151, and MUC5AC area fraction
0.2781129. This regression confirms that the display copies are isolated from
the quantitative branch.

Validation also identified and corrected two unsafe false-success paths:

1. Unrecognized underscore-heavy filenames can no longer override the panel
   selected by the launcher; only a recognized convention or samplesheet may
   provide a per-image override.
2. Layer-aware dense ALI DAPI defaults to global Otsu when no DAPI method is
   explicitly supplied. Local Phansalkar rejected all candidates in the tested
   field. Any region below `IFQ_MIN_INCLUDED_NUCLEI` now fails instead of
   exporting a successful zero-cell result.

## Active entry points

- [`IF_Quant_Pipeline.groovy`](IF_Quant_Pipeline.groovy): production
  Fiji/ImageJ analysis.
- [`aggregate_to_mouse.py`](aggregate_to_mouse.py): region-to-mouse aggregation.
- [`samplesheet_template.csv`](samplesheet_template.csv): metadata template.
- [`README.md`](README.md): installation, panel definitions, configuration, and
  output schema.
- [`docs/MARKER_MORPHOLOGY_GUIDE.md`](docs/MARKER_MORPHOLOGY_GUIDE.md): extended
  morphology and literature notes.
- [`docs/UNIVERSAL_MARKER_CONFIGURATION.md`](docs/UNIVERSAL_MARKER_CONFIGURATION.md):
  reusable marker, disease-context, panel, and ROI-tag hierarchy.
- [`docs/UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md`](docs/UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md):
  cross-marker context/evaluability audit and representative Fiji regressions.
- [`docs/COMPARTMENT_TAGS_AND_PROGRESSION.md`](docs/COMPARTMENT_TAGS_AND_PROGRESSION.md):
  anatomical tag meanings, subcellular analytical roles, ROI naming, and
  image-to-call progression.
- [`docs/Z_STACK_ANALYSIS.md`](docs/Z_STACK_ANALYSIS.md): layer-aware Z policies,
  automatic or fixed slab selection, per-plane QC, and the boundary between
  restricted 2.5D projections and true 3D analysis.
- [`config/lung_marker_registry.json`](config/lung_marker_registry.json): marker
  aliases, localization, lineage/state notes, and analytical-role defaults.
- [`config/custom_panels.example.json`](config/custom_panels.example.json):
  opt-in study panel templates; built-in panels remain unchanged.
- [`docs/PILOT_G002_MORPHOLOGY_RESULTS.md`](docs/PILOT_G002_MORPHOLOGY_RESULTS.md):
  validated one-image pilots.

## Priority real-project antibody panels

The pipeline remains universal: registry markers, legacy panels, study-owned
custom panels, morphology-first decisions, and per-image cell counts all remain
available. The following two channel maps are the priority presets for the real
project:

| Preset | C1 | C2 | C3 | C4 |
|---|---|---|---|---|
| `LEFT` | DAPI | KRT5-488 | AGER-555 | T1alpha-647 |
| `RIGHT` | DAPI | Pro-SPC-488 | AGER-555 | KRT8-647 |

The 260730-CW ALI Z-stack acquisitions are available as additional presets:

| Preset | C1 | C2 | C3 | C4 |
|---|---|---|---|---|
| `ALI1` | DAPI | SCGB3A2-488 | tdTomato | p63-647 |
| `ALI2` | DAPI | KRT5-488 | tdTomato | acetylated-tubulin-647 |
| `ALI3` | DAPI | KRT5-488 | tdTomato | MUC5AC-647 |

The ALI presets declare nuclear, cell-body and apical Z policies but do not
replace `LEFT`/`RIGHT` as the priority biological panels.

Primary tracking is per image and per marker: total included DAPI cells,
final-positive cells, final-negative cells, indeterminate cells, and
final-positive fraction of total included cells. KRT5 also retains pod-area
quantification; AGER and T1alpha retain membrane-positive-area quantification.
Co-expression classes are secondary descriptive endpoints and do not replace
the individual marker counts.

Marker-specific support remains unchanged:

- KRT5 and KRT8: connected perinuclear cytoplasmic keratin support;
- Pro-SPC: connected granular perinuclear cytoplasmic support;
- AGER and T1alpha: connected thin-membrane support in an alveolar context;
- DAPI: nuclear segmentation and the denominator for included cells.

The same three-state decision hierarchy applies to both presets. In particular,
an alveolar-marker negative requires a compatible independently declared
alveolar context; unresolved anatomy is not silently counted as negative.

## Universal marker-selection hierarchy

Before analyzing a new marker set, freeze these layers in order:

1. Research question and biological unit: lineage, transient state,
   localization, regional burden, or spatial relationship.
2. Species, preparation, modality, antibody clone, and known controls.
3. Blinded anatomical/context ROIs.
4. A lineage anchor, state marker, and nearest-alternative/exclusion marker.
5. Analytical role: nuclear, nuclear ratio, cytoplasmic, membrane, apical
   cilia, or regional area.
6. Channel map and projection policy for the actual acquisition.
7. Control-derived intensity cutoff and validated morphology/size gates.

Marker identity, image channel, analytical geometry, and biological
interpretation are separate. A marker registry entry may supply a geometry
default, but it never assigns a disease diagnosis or a final cell identity.
Unknown markers remain supported when the custom panel declares their role.

For the newly expanded profiles: KRT8 uses connected cytoplasmic filament
support; ITGA2/CD49b uses connected membrane support; SOX9 requires connected
DAPI-nuclear enrichment; and PDGFRB is preferably a regional/perivascular area
endpoint at 20x, with per-nucleus membrane calls reserved for validated
high-resolution ownership. Red2-Kras uses connected cytoplasmic RFP reporter
support with clone area primary at 20x; RFP-positive marks the verified
oncogene-coupled clone, whereas RFP-negative alone is not a wild-type call.
Pan-KRAS uses connected cytoplasmic/inner-membrane protein support but does not
imply a KRAS mutation. Ki-67/MKI67 uses connected nuclear enrichment and is
summarized as a labeling index inside a predeclared population or ROI. `IGTA2`
is accepted as an alias of canonical `ITGA2`. Except for the construct-linked
Red2-Kras RFP interpretation, none of these markers assigns a lineage,
mutation, malignancy, or disease state by itself.

## End-to-end workflow

### Per-image panel and channel routing

```mermaid
flowchart TD
    A[Matching analytical image paths] --> B{Explicit samplesheet panel?}
    B -->|Yes| C[Use samplesheet panel key]
    B -->|No| D[Match marker names in file and folder path]
    D --> E{Recognized built-in panel or subset?}
    E -->|No| X[Stop before Fiji; require manual or custom mapping]
    E -->|Yes| F[Assign validated channel index to marker map]
    C --> F
    F --> G[Write temporary relative-path panel map]
    G --> H[Process each image with its own panel definition]
    H --> I[Export panel on every image and region row]
    I --> J[Full analysis retains auto_panel_assignments.csv]
```

AUTO no longer requires one panel for the entire directory. Every image is
allocated independently, but allocation remains strict: a recognized
samplesheet key or built-in marker combination must resolve before Fiji starts.
The map selects a validated panel definition; it does not infer marker identity
from pixel color or intensity. Images with absent channels use a declared subset
panel, so absent markers produce no cell decision rather than a false negative.
The known 4× ALI subsets are `ALI1_MAP` (DAPI, SCGB3A2, tdTOM) and
`ALI23_MAP` (DAPI, KRT5, tdTOM).

```mermaid
flowchart TD
    A[Original Bio-Formats image] --> B[Verify file identity, channel map, calibration, bit depth, and Z policy]
    B --> Z{Z handling}
    Z -- Global legacy mode --> D1[Apply one declared projection to every channel]
    Z -- Layer-aware mode --> D2[Resolve full, nuclear, cell-body, apical, single-plane, or explicit range per marker]
    D1 --> C[Define blinded tissue and anatomical/context ROIs]
    D2 --> C
    C --> D[Create the declared global or marker-specific restricted projections]
    D --> E[Segment DAPI nuclei and reject undersized or edge-touching candidates]
    E --> F{Nucleus and marker support technically evaluable?}
    F -- No: invalid projection, empty support, or shared ownership --> U[Indeterminate]
    F -- Yes --> G[Resolve fixed control-derived or exploratory pixel cutoff]
    G --> H[Measure role-specific support: nucleus, ring, membrane, ciliary component, or regional area]
    H --> I{Coverage, connectedness, localization, enrichment, and ownership pass?}
    I -- Yes --> J[Strict marker evidence positive]
    I -- No --> K[Strict marker evidence absent]
    J --> L{Expected anatomical context}
    K --> L
    L -- Compatible or not required --> M{Marker evidence positive?}
    M -- Yes --> P[Positive]
    M -- No --> N[Evaluable negative]
    L -- Unresolved + marker evidence positive --> Q[Exploratory context-unresolved positive]
    L -- Unresolved + evidence absent --> U
    L -- Known incompatible --> U
    P --> V[Evaluate compound phenotype rules]
    N --> V
    Q --> W[Do not authorize compound lineage/state class]
    U --> W
    V --> X[Export cell CSV, summaries, masks, call-QC overlays, and provenance]
    W --> X
    X --> Y[Review QC; exclude failed fields without relabeling them negative]
    Y --> AA[Aggregate accepted regions to section or mouse level]
```

### Layer-aware Z routing

```mermaid
flowchart TD
    A[Calibrated multichannel stack] --> B[Retain all channels and Z planes]
    B --> C{Marker Z policy}
    C -- full_stack --> D[Use all planes]
    C -- nuclear_stack --> E[Use configured nuclear range]
    C -- cell_body_slab --> F[Use configured range or brightest contiguous DAPI window]
    C -- apical_slab --> G[Use configured range or brightest contiguous marker window]
    C -- single_plane --> H[Use declared or DAPI-selected plane]
    C -- explicit_range --> I[Use validated panel zStart:zEnd]
    D --> J[Restricted max, average, sum, or single-plane image]
    E --> J
    F --> J
    G --> J
    H --> J
    I --> J
    J --> K[Write per-plane profile and resolved range provenance]
    K --> L[Run the unchanged morphology-first decision hierarchy]
```

#### What each schematic block does

| Block | Algorithmic meaning | Output or safeguard |
|---|---|---|
| **A. Calibrated multichannel stack** | Bio-Formats imports the original channels and Z planes. The pipeline requires positive XY calibration and, for a multi-plane layer-aware run, positive Z spacing. | Preserves physical units and rejects an uncalibrated stack instead of applying pixel-based biological distances. |
| **B. Retain all channels and Z planes** | Channels are split without collapsing Z. The declared channel index is checked against the acquired channel count. | Prevents a single global projection from erasing marker-specific depth information. |
| **C. Marker Z policy** | Each panel channel receives a policy from its panel definition, marker registry, or role default. A panel-specific declaration has priority. | Routes every marker deterministically and records the policy used. |
| **D. `full_stack`** | Select planes 1 through the final plane. This is appropriate for robust regional signal or a complete DAPI overview. | Full-stack inclusive range. |
| **E. `nuclear_stack`** | Use `IFQ_Z_NUCLEAR_RANGE`; `full` is the default, `auto` selects the brightest DAPI plane, and `start:end` freezes a validated range. | Nuclear-localized marker range that is independent of cytoplasmic/apical ranges. |
| **F. `cell_body_slab`** | Use the configured cell-body range, or select the brightest contiguous DAPI window of `IFQ_Z_CELL_BODY_PLANES` planes. The window score is the summed DAPI signal. | DAPI-guided epithelial/cell-body slab for keratins, reporters, secretory cytoplasm, Pro-SPC, and related markers. |
| **G. `apical_slab`** | Use the configured apical range, or select the brightest contiguous window in that marker's own channel using `IFQ_Z_APICAL_PLANES`. | Marker-guided luminal/apical slab for AcTub, MUC5AC, and similarly localized structures. |
| **H. `single_plane`** | Use an explicitly declared plane; otherwise use the configured global plane or a DAPI-selected plane. | A local optical section for ratios such as nuclear YAP, where a projection would mix compartments. |
| **I. `explicit_range`** | Require and validate the panel's 1-based inclusive `zStart:zEnd`. Out-of-stack values stop the image. | Frozen, study-specific range suitable for a confirmatory cohort. |
| **J. Restricted projection** | Project only the resolved range with the declared `max`, `avg`, `sum`, or `single` method. The default is maximum intensity within the restricted slab. | One marker-specific 2D analysis image; the source stack remains unchanged. |
| **K. Profile and provenance** | Export every plane's intensity profile, selected status, resolved range/source, projection, automatic score, and intensity-weighted Z centroid. | Makes automatic selection auditable and allows a fixed range to be chosen before confirmatory analysis. |
| **L. Morphology-first hierarchy** | Feed the marker-specific projection into the same pixel-threshold, localization, connectedness, compartment, ownership, and evaluability rules used by the established workflow. | Z routing changes the optical support presented to the decision algorithm; it does not replace or weaken the final marker rules. |

The automatic branches select a range from image signal only. They do not use
condition, genotype, experimental group, or the eventual positive/negative
cell calls. When acquisition orientation is reproducible, inspect pilot Z
profiles and replace `auto` with predeclared ranges before cohort-level
statistics.

Automatic windows are deterministic pilot choices. A confirmatory cohort
should use explicit ranges whenever acquisition orientation and depth are
consistent. The current layer-aware path is 2.5D: it restricts projections but
does not claim 3D cell-boundary reconstruction.

### Visualization-only channel enhancement

```mermaid
flowchart TD
    A[Marker-specific projected channel] --> P{Launcher operation}
    P -->|Review and run analysis| B[Keep original calibrated pixels]
    P -->|Preview enhanced images; first 5| C[Duplicate display copy]
    C --> D[Resolve per-channel low and high percentiles]
    D --> E[Map display range to 8-bit]
    E --> F[Apply optional display gamma]
    F --> G[Write labeled individual marker PNG]
    F --> H[Color-merge enhanced marker copies]
    H --> I[Write labeled enhanced composite]
    I --> Q[Stop: PNG files only]
    B --> J[Thresholds, masks, morphology, and final calls]
```

Intensity adjustment is deliberately isolated from measurement. The default
display range is the 1.0th to 99.8th percentile with gamma 1.0. A panel channel
may override `displayLowPercentile`, `displayHighPercentile`, or
`displayGamma`. All enhanced files contain the banner
`DISPLAY ONLY - NOT QUANTIFIED`. Preview-only mode deliberately creates no
parameter or analysis files. The original projected pixels—not the enhanced
8-bit copies—remain the only source for thresholds, masks, intensity audit
fields, morphology features, and final calls.

The launcher presents **Preview enhanced images (first 5)** next to
**Review and run analysis**. Preview mode uses the selected panel and Z-routing
rules, processes at most five analytical images, and exits immediately after
writing labeled individual and merged PNGs. It does not run DAPI segmentation,
cell inclusion, marker decisions, or aggregation, and it writes no masks, CSV,
Excel, parameter JSON, Z profile, analysis manifest, or launcher record.

The launcher explicitly disables enhanced primary-view export during
**Review and run analysis**, keeping visualization previews and quantitative
result folders separate. Expert command-line runs may still opt in with
`IFQ_EXPORT_DISPLAY_CHANNELS=true`; its default is false.

## Decision authority and three-state semantics

The authoritative cell call is `<marker>_final_call`:

- `1`: morphology-positive;
- `0`: evaluable morphology-negative;
- blank: indeterminate because the marker could not be evaluated safely.

Classification and summary endpoints consume this field. The legacy object-mean
field `<marker>_pos` is audit-only.

This hierarchy has three important consequences:

1. A high object mean alone cannot produce a final positive.
2. A low object mean does not force a negative when a thin or localized
   structure has sufficient connected pixels above the cutoff.
3. Missing spatial information is indeterminate, not negative. Segmentation
   failure, ambiguous ownership, invalid projection, or an unassigned required
   compartment must not silently inflate the negative group.

For every cell-call marker with `expectedCompartment` or
`expectedCompartments`, context is asymmetric:

- strict localization-correct marker evidence may be retained as an
  `exploratory_positive_context_unresolved` call when anatomy is unassigned or
  ambiguous;
- absence becomes negative only inside a compatible, independently assigned
  compartment;
- evidence in a known incompatible compartment remains indeterminate for the
  intended endpoint and is separately counted as
  `<marker>_context_excluded_evidence_positive_count`;
- context-unresolved positives cannot authorize compound lineage/state
  classifications.

This preserves observable marker expression without allowing the marker channel
to declare its own negative population or anatomical identity. A custom channel
can set `allowPositiveWithoutCompartment: false` when even marker positivity is
not interpretable without geography.

Adaptive Otsu thresholds are allowed for pilot exploration and produce
`exploratory_positive` or `exploratory_negative` status. Confirmatory analysis
requires a fixed cutoff declared from appropriate controls before analysis.

## Morphology gate definitions

For each marker-specific support region, the pipeline calculates:

- **Positive fraction:** fraction of support pixels at or above the resolved
  marker cutoff.
- **Largest-component share:** fraction of positive pixels belonging to the
  largest 8-connected component. This rejects scattered bright specks.
- **Localization:** nuclear enrichment for p63/Sox2, nuclear-to-cytoplasmic ratio
  for YAP, or the appropriate perinuclear/apical support for other roles.
- **Ownership:** whether another included nucleus lies inside support that is
  meant to belong uniquely to the current cell. Shared support is indeterminate.
- **Compartment/context:** whether the marker is evaluated in a compatible
  airway, alveolar, tumor, fibrotic, stromal, vascular, or immune ROI. Multiple
  tags can coexist in one ROI name.

A final positive is the logical AND of all applicable gates. A final negative is
allowed only when the marker is evaluable.

## Current pilot morphology matrix

| Marker | Expected role and analytical support | Minimum positive fraction | Minimum largest-component share | Additional gate or primary readout |
|---|---|---:|---:|---|
| KRT5 | Perinuclear cytoplasmic ring | 0.20 | 0.50 | Unique ownership; independent pod area is also reported |
| AGER | Membrane-support ring | 0.25 | 0.40 | Alveolar ROI; unique ownership; membrane area also reported |
| PDPN | Membrane-support ring | 0.25 | 0.40 | Alveolar ROI; unique ownership; membrane area also reported |
| T1A | Membrane-support ring | 0.30 | 0.40 | Alveolar ROI; unique ownership; membrane area also reported |
| mRAGE | Membrane-support ring | 0.30 | 0.40 | Alveolar ROI; unique ownership; membrane area also reported |
| Pro-SPC | Perinuclear granular cytoplasm | 0.15 | 0.40 | Alveolar ROI; unique ownership |
| CD4/CD8 | Nucleus-associated membrane proxy | 0.20 | 0.40 | Unique ownership |
| Sox2 | DAPI nucleus | 0.40 | 0.60 | Nuclear:ring enrichment at least 1.25 |
| p63 | DAPI nucleus | 0.40 | 0.60 | Nuclear:ring enrichment at least 1.25 |
| YAP | DAPI nucleus plus cytoplasmic reference ring | 0.30 | 0.60 | Nuclear:cytoplasmic ratio at least 1.50; single plane |
| Aqp5 | Perinuclear support | 0.20 | 0.40 | Unique ownership |
| CC10/SCGB1A1 | Perinuclear secretory cytoplasm | 0.20 | 0.40 | Unique ownership |
| tdTomato | Perinuclear reporter support | 0.20 | 0.40 | Unique ownership; independent reporter area also reported |
| Acetylated tubulin | Unique nearest ciliary component in a 1-6 um apical shell | 0.10 | 0.30 | Contextual positive allowed if all gates pass; negative requires airway ROI; regional patches are primary at 20x |

The AcTub regional patch filter is 2.0 um2. The former 0.5 um2 filter was only
about five pixels at the tested 0.311 um/pixel calibration and was too permissive
for a structure-level endpoint.

## Marker-specific interpretation

### Nuclear markers: p63 and Sox2

Positive signal must occupy a substantial connected portion of the DAPI nucleus
and be enriched relative to the reference ring. This rejects isolated nuclear
specks and perinuclear blur that could raise a nucleus mean.

### Nuclear localization marker: YAP

YAP is a localization phenotype, not merely a nuclear-intensity marker. Require
both connected nuclear support and the nuclear:cytoplasmic ratio. Use a single
optical plane or a validated 3D workflow; maximum projection mixes different
depths and makes the ratio non-local.

### Cytoplasmic and reporter markers

KRT5, Pro-SPC, CC10, and tdTomato use a perinuclear ring because the nucleus is
not the expected signal compartment. Connected thresholded coverage is primary.
Whole-marker area remains important for dense KRT5 pods and tdTomato reporter
fields that cannot be represented reliably by one nucleus-centered measurement
per cell.

CC10 denotes current secretory protein phenotype; it does not prove club-cell
ancestry after injury. tdTomato denotes recombination history, not current cell
identity.

### Membrane markers

AGER, PDPN/T1A, mRAGE, CD4, and CD8 are membrane-associated. Their signal can be
thin and bright while the mean over a larger ring is low. Positive fraction and
connected-pattern gates are therefore more appropriate than ring mean alone.
AGER, PDPN/T1A, and mRAGE require alveolar anatomical context for the specified
AT1 interpretation. Regional membrane area and cell-associated calls answer
different questions and must be reported separately.

### Acetylated alpha-tubulin

AcTub is concentrated in apical cilia. At 20x, the primary endpoint is regional
ciliary-patch area and component distribution, not an individual-cilium count.
For a cellular association, accepted ciliary components (at least 2 um2) are
assigned to exactly one nearest nucleus. The component centroid must lie at
least 1 um outside the equivalent-radius nuclear boundary, no farther than the
6 um apical support shell, and the local support must pass both 0.10 coverage
and 0.30 connected-pattern gates.

This is an asymmetric decision. A nucleus satisfying all component and spatial
rules may be reported as
`exploratory_positive_cellular_context` when the whole field is unassigned or
ambiguous. Failure to find such a component is **not** an AcTub-negative call
without an independently defined airway ROI; it remains indeterminate. A known
non-airway ROI is never overridden by the target marker. Inside a validated
airway ROI, both positive and negative calls are allowed. Regional ciliary area
remains the primary 20x endpoint.

## Sectioning rules

### Optical sectioning

- One-plane acquisitions are analyzed as that plane.
- Maximum projection is acceptable for robust area measurements when validated.
- `IFQ_PROJECTION=layer_aware` keeps the legacy decision hierarchy but resolves
  separate full-stack, nuclear, DAPI-guided cell-body, marker-guided apical, or
  explicit Z ranges before thresholding.
- YAP nuclear:cytoplasmic analysis requires a representative single plane or a
  validated 3D method.
- Apical cilia are best assessed in a single apical plane or restricted apical Z
  range when a stack is available.
- Automatic cell-body/apical slab discovery is exploratory. Review
  `*__z_plane_profile.csv`, then freeze explicit ranges when acquisition
  geometry is consistent.
- The layer-aware path is a restricted-projection 2.5D method. Dense nuclei that
  overlap in XY, volumetric endpoints, and genuinely three-dimensional
  ownership require a separately validated anisotropy-aware 3D segmenter.

The tested G002 and G003 Olympus OIR files each contain one optical section, so
no marker-specific Z projection is needed for those particular files. The
260730-CW examples contain 10–12 planes and should use the layer-aware pathway
or prevalidated explicit ranges.

### Anatomical sectioning

Draw ROIs without consulting the target marker channel. The supported
anatomical/context tags are:

| Tag/state | Short description |
|---|---|
| `airway` | Conducting-airway or bronchiolar anatomy; names containing `airway` or `bronch` |
| `alveolar` | Distal gas-exchange parenchyma; names containing `alveol` |
| `tumor` | Histologically/experimentally defined tumor region; `tumor`, `tumour`, or `luad` |
| `fibrotic` | Scarred/remodeled, honeycomb, or UIP-pattern region; `fibrot`, `honeycomb`, or `uip` |
| `stromal` | Mesenchymal/connective-tissue region; `strom` or `mesench` |
| `vascular` | Vessel/capillary-associated region; `vascul`, `vessel`, or `capillar` |
| `immune` | Immune-rich/inflammatory/lymphoid region; `immune`, `inflamm`, or `lymph` |
| `ambiguous` | Mixed or uncertain anatomy; it never authorizes a negative |
| `unassigned` | No recognized tag and no override; missing context, not negative/background |

The pipeline exports all recognized labels as `region_tags`, while
`compartment` remains a single backward-compatible primary label. A panel can
accept any of several tags through `expectedCompartments`.

Multiple tags may coexist, such as `alveolar_fibrotic_01` or
`tumor_stromal_02`. Decisions use the complete `region_tags` set. The display
field `compartment` uses `ambiguous > alveolar > airway > unassigned > first
other tag` precedence. If `ambig` occurs anywhere in the ROI name, all other
tags are intentionally discarded.

For study runs:

```powershell
$env:IFQ_COMPARTMENT_MODE = 'required'
```

An unrecognized or ambiguous required compartment produces indeterminate calls
for compartment-dependent markers.

For a visually reviewed, anatomically homogeneous field only,
`IFQ_WHOLE_FIELD_COMPARTMENT` can record any supported explicit context
(`airway`, `alveolar`, `tumor`, `fibrotic`, `stromal`, `vascular`, `immune`, or
`ambiguous`) in provenance. Never force a mixed field into one compartment;
draw separate ROIs or use `ambiguous` instead.

The complete tag definitions, analytical subcellular roles, ROI naming
examples, and image-to-call progression are in
[`docs/COMPARTMENT_TAGS_AND_PROGRESSION.md`](docs/COMPARTMENT_TAGS_AND_PROGRESSION.md).

### Analytical sectioning

Choose the unit that matches the biology: nucleus, perinuclear cytoplasmic ring,
membrane-support ring, independent positive-area mask, apical-cilia support, or
nuclear:cytoplasmic ratio. These units are not interchangeable.

## Threshold and control policy

- Use unstained, secondary-only, or biological negative controls to estimate
  background and nonspecific signal.
- Use known positive tissue to verify that the threshold captures the expected
  structure rather than merely the brightest pixels.
- Derive fixed thresholds without consulting experimental group outcomes.
- Freeze threshold, minimum positive fraction, connectedness, minimum area,
  support width, projection, DAPI segmentation, and compartment rules before a
  cohort run.
- Keep all resolved parameters in `__params.json` and `run_manifest.json`.

Marker-specific overrides follow these patterns:

```powershell
$env:IFQ_CC10_THRESHOLD = 'control-derived-value'
$env:IFQ_CC10_MIN_POSITIVE_FRACTION = '0.20'
$env:IFQ_CC10_MIN_LARGEST_COMPONENT_SHARE = '0.40'
$env:IFQ_YAP_MIN_NUC_CYTO_RATIO = '1.50'
$env:IFQ_P63_MIN_NUCLEAR_ENRICHMENT = '1.25'
$env:IFQ_ACTUB_MIN_SUPPORT_FRACTION = '0.10'
$env:IFQ_ACTUB_MIN_PATCH_AREA_UM2 = '2.0'
```

Use `IFQ_<MARKER>_THRESHOLD` for a fixed cutoff. Non-alphanumeric characters are
removed from the environment token: `tdTOM` becomes `IFQ_TDTOM_THRESHOLD` and
`mRAGE` becomes `IFQ_MRAGE_THRESHOLD`.

## Minimal Fiji batch configuration

The recommended Windows route is
[`IFQuantLauncher-v1.7.0.exe`](IFQuantLauncher-v1.7.0.exe). It exposes the
directories and settings below in a GUI, creates a fresh timestamped output
folder, clears stale inherited `IFQ_*` variables, and chooses the appropriate
ARM64 or x64 Fiji launcher when a Fiji installation folder is selected.

For command-line or development runs, configure the same values manually:

```powershell
$env:IFQ_INPUT_DIR = 'G:\path\to\originals'
$env:IFQ_OUTPUT_DIR = "$PWD\analysis_output\run_name"
$env:IFQ_PANEL = 'E'
$env:IFQ_SEGMENTER = 'classic'
$env:IFQ_PROJECTION = 'layer_aware'
$env:IFQ_Z_NUCLEAR_RANGE = 'full'
$env:IFQ_Z_CELL_BODY_RANGE = 'auto'
$env:IFQ_Z_APICAL_RANGE = 'auto'
$env:IFQ_MARKER_REGISTRY = "$PWD\config\lung_marker_registry.json"
# For a new study: $env:IFQ_PANEL_CONFIG = 'D:\study\panels.json'
$env:IFQ_INCLUDE_REGEX = '.*A01_G002_0001.*'
$env:IFQ_MAX_IMAGES = '1'
$env:IFQ_MORPHOLOGY_PRIMARY = 'true'
```

Use a new, empty output directory for every run. A batch with no matching
images exits with code 1. Per-image failures are retained in `run_manifest.json`
and make the final manifest status `partial_failure` or `failed`; headless Fiji
also exits with code 1 after preserving the partial summary.

Run `IF_Quant_Pipeline.groovy` headlessly or through Fiji's Groovy script editor.
Every run must retain `run_manifest.json`, per-image `__params.json`, cell CSVs,
region summaries, decision masks, and call-QC PNGs. `run_summary.xlsx` opens on
**Image Positive Counts**, with one aligned row per image/region containing
total cells and every marker's final-positive cell count and fraction of that
row's total cells. The complete three-state audit remains on **Run Summary**,
and deliberate exclusions remain on **Skipped Inputs**. Microscope
`Map_A##.oir` navigation acquisitions are classified as deliberate skips
before analysis and do not make an otherwise successful run fail.

## Exported decision fields

The per-cell CSV includes:

- `<marker>_mean` and `<marker>_pos`: legacy mean-intensity audit values;
- `<marker>_support_fraction_above_threshold`;
- `<marker>_largest_positive_component_share`;
- `<marker>_fraction_pass`, `<marker>_connected_pattern_pass`,
  `<marker>_ownership_clear`, `<marker>_enrichment_pass`, and
  `<marker>_compartment_pass`;
- `<marker>_final_call`: `1`, `0`, or blank;
- `<marker>_call_status` and `<marker>_call_reason`;
- `<marker>_true_pos`: compatibility alias for the final call.

The region summary separately reports raw mean-intensity, morphology-positive,
morphology-negative, indeterminate, and evaluable counts. It also exports:

- nucleus-candidate acceptance and rejection fractions, with rejection reasons;
- marker positive/negative fractions among evaluable cells;
- marker indeterminate fractions among included nuclei;
- raw-intensity-positive/final-negative and
  raw-intensity-negative/final-positive disagreement counts;
- an intensity-morphology discordance fraction and a review-burden proxy
  (`indeterminate + discordant`) for longitudinal script QC.

These audit fractions are sensitivity and review-burden proxies, not validated
false-positive or false-negative rates. Classification rules use
`<marker>_final_call`, never `<marker>_pos`.

Every marker receives morphology-positive and indeterminate nuclei label masks,
plus a call-QC PNG with positive nuclei in green, evaluable negatives in cyan,
indeterminate nuclei in magenta, and the analysis ROI in orange.

## QC acceptance order

1. Confirm image identity, channel order, XY/Z calibration, and projection mode.
2. For a stack, review `*__z_plane_profile.csv` and every resolved marker range.
3. Review DAPI candidate, accepted, rejected, split, and merged objects.
4. Confirm threshold boundaries follow the intended marker structure.
5. Confirm connected-support gates reject isolated bright specks.
6. Review call-QC images and verify spatially plausible positives.
7. Confirm shared support is not forced to a cell.
8. Confirm airway, alveolar, and ambiguous ROI labels are defensible.
9. Confirm no blank final call was converted to zero.
10. Freeze Z ranges, thresholds and morphology parameters before blinded group
    analysis.

## Statistical unit

Run:

```powershell
uv run --no-project python aggregate_to_mouse.py analysis_output\run_name\run_summary.csv
```

All inferential statistics use mouse-level rows. Sections, fields, regions, and
nuclei are not independent biological replicates.

## Current validation outputs

The two final local pilots are under `test_runs/current/`:

- `FinalPilot_CC10_AcTub_G002_morphology_primary_v2`;
- `FinalPilot_T1A_mRAGE_G002_morphology_primary_v2`.

These outputs are intentionally ignored by Git because they contain generated
images and tables. Their numerical results are preserved in
[`docs/PILOT_G002_MORPHOLOGY_RESULTS.md`](docs/PILOT_G002_MORPHOLOGY_RESULTS.md).
All reported calls and areas remain exploratory because the pilot used
image-specific Otsu thresholds.

## Literature basis

- YAP activity and nuclear localization:
  [PMC5360446](https://pmc.ncbi.nlm.nih.gov/articles/PMC5360446/).
- Acetylated tubulin as a ciliated-airway-cell marker:
  [PMC3604083](https://pmc.ncbi.nlm.nih.gov/articles/PMC3604083/).
- CC10 and apical-cilia organization in airway epithelium:
  [PMC11212965](https://pmc.ncbi.nlm.nih.gov/articles/PMC11212965/).
- Podoplanin/RAGE and AT1 phenotypes:
  [PMC2542444](https://pmc.ncbi.nlm.nih.gov/articles/PMC2542444/),
  [PMC8480975](https://pmc.ncbi.nlm.nih.gov/articles/PMC8480975/).
- KRT5-positive injury pods and lineage interpretation:
  [PMC5906746](https://pmc.ncbi.nlm.nih.gov/articles/PMC5906746/),
  [PMC4312207](https://pmc.ncbi.nlm.nih.gov/articles/PMC4312207/).

## Legacy boundary

[`legacy/`](legacy/README.md) is non-authoritative. Do not copy thresholds or
call semantics from that archive into a new study run. The pre-organization
repository is also preserved at branch `codex/legacy-pre-reorganization`.
