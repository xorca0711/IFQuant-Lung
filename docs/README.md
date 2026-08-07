# Documentation Index

The repository supports **two acquisition routes**, both measured by the same
validated Fiji engine:

* **field / confocal route** — small calibrated images go straight to
  `IF_Quant_Pipeline.groovy`. Described in [`../WORKFLOW.md`](../WORKFLOW.md).
* **whole-slide route** — a slide-scanner container (`.vsi`) is opened and tiled
  by QuPath, then the same engine measures the tiles. Described in
  [`WSI_TILING_WORKFLOW.md`](WSI_TILING_WORKFLOW.md).

QuPath never measures anything; it is a reader and tiler. All quantification
stays in the Fiji engine so both routes share one validated decision model.

## Start here

- [`PROJECT_STATE.md`](PROJECT_STATE.md): **living handoff** — where everything is,
  what is validated versus not, what is in flight, and the decisions waiting on
  the user. Read this first when resuming.

- [`QUPATH_FIJI_INTEGRATION.md`](QUPATH_FIJI_INTEGRATION.md): **why** the two
  tools are used together, the published pattern this follows
  (Chiaruttini et al. 2022), where we deliberately differ, and why the handoff
  is file-based rather than in-process.

## Study endpoint

- [`ECTOPIC_POD_ENDPOINT.md`](ECTOPIC_POD_ENDPOINT.md): the KRT5⁺ ectopic pod
  endpoint — its damaged-area denominator, the PDPN co-negativity numerator, the
  AGER-as-airway-detector hazard, every calibration result with its validation
  status, and the known aggregation bug. **Read before running any batch.**

## Routes

- [`WSI_TILING_WORKFLOW.md`](WSI_TILING_WORKFLOW.md): the whole-slide route —
  series selection, global tissue detection, tiling with per-tile core ROIs,
  seam correctness, and tile → slide → mouse aggregation.

## Interpretation and configuration

- [`MARKER_MORPHOLOGY_GUIDE.md`](MARKER_MORPHOLOGY_GUIDE.md): the authoritative
  interpretation guide — marker roles, the morphology-first decision hierarchy,
  sectioning, control policy, and literature basis.
- [`UNIVERSAL_MARKER_CONFIGURATION.md`](UNIVERSAL_MARKER_CONFIGURATION.md):
  the reusable marker/panel schema, ROI context vocabulary, and research
  profiles for acute injury, IPF/fibrosis, and lung-adenocarcinoma studies.
- [`COMPARTMENT_TAGS_AND_PROGRESSION.md`](COMPARTMENT_TAGS_AND_PROGRESSION.md):
  every anatomical tag and subcellular analytical role, multi-tag precedence,
  naming examples, and the complete call progression.
- [`Z_STACK_ANALYSIS.md`](Z_STACK_ANALYSIS.md): marker-specific Z policies,
  automatic and fixed slab selection, per-plane QC, and true-3D escalation
  criteria. A preserved capability of the Fiji engine, not legacy.

## Project

- [`BRANCHING.md`](BRANCHING.md): branch roles, what is superseded, and the
  merge-gate responsibilities.

## Entry points

- [`../WORKFLOW.md`](../WORKFLOW.md): operational sequence for the
  **field / confocal route**, plus the shared interpretation model.
- [`../README.md`](../README.md): installation, configuration, output schema,
  and statistics.

## Archived

Point-in-time audits and pilot records that no longer describe the current
pipeline have moved to [`../legacy/docs/`](../legacy/docs/README.md). They are
kept because they are the only record of the validation they document — but
**none of their numbers are current**, and thresholds quoted there are pilot
placeholders. The only calibrated parameters are in `ECTOPIC_POD_ENDPOINT.md`.

Historical diagrams are archived in
[`../legacy/figures/`](../legacy/figures/README.md); their intensity-centered
logic no longer matches the morphology-first engine.
