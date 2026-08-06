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

## Study endpoint

- [`ECTOPIC_POD_ENDPOINT.md`](ECTOPIC_POD_ENDPOINT.md): the KRT5⁺ ectopic pod
  endpoint, its damaged-area denominator, the AGER-as-airway-detector hazard,
  and the threshold-calibration evidence. **Read before running any batch.**

## Routes

- [`WSI_TILING_WORKFLOW.md`](WSI_TILING_WORKFLOW.md): the whole-slide route —
  series selection, global tissue detection, tiling with per-tile core ROIs,
  seam correctness, and tile → slide → mouse aggregation.

## Interpretation and configuration

- [`MARKER_MORPHOLOGY_GUIDE.md`](MARKER_MORPHOLOGY_GUIDE.md): marker roles, decision hierarchy, sectioning,
  control policy, and literature basis.
- [`UNIVERSAL_MARKER_CONFIGURATION.md`](UNIVERSAL_MARKER_CONFIGURATION.md):
  reusable marker/panel schema, ROI context vocabulary, and research profiles
  for acute injury, IPF/fibrosis, and lung-adenocarcinoma lineage studies.
- [`PILOT_G002_MORPHOLOGY_RESULTS.md`](PILOT_G002_MORPHOLOGY_RESULTS.md): validated one-image results for panel E
  and panel R, including post-run improvements and caveats.
- [`SCRIPT_SELF_REVIEW_20260723.md`](SCRIPT_SELF_REVIEW_20260723.md): software-defect audit,
  corrections, verification evidence, and unresolved validation limits.
- [`UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md`](UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md):
  marker-wide context/evaluability audit, corrected decision matrix, and Fiji
  regression results.
- [`COMPARTMENT_TAGS_AND_PROGRESSION.md`](COMPARTMENT_TAGS_AND_PROGRESSION.md):
  descriptions of every anatomical tag and subcellular analytical role,
  multi-tag precedence, naming examples, and the complete call progression.
- [`Z_STACK_ANALYSIS.md`](Z_STACK_ANALYSIS.md): marker-specific Z policies,
  automatic/fixed slab selection, per-plane QC, and true-3D escalation criteria.
- [`BRANCHING.md`](BRANCHING.md): stable-main, Z-stack feature, legacy snapshot,
  and merge-gate responsibilities.

## Audits and validation records

- [`TEST_RUN_ERROR_RATE_AUDIT.md`](TEST_RUN_ERROR_RATE_AUDIT.md): observed
  per-image failure modes and their rates across recorded test runs.

## Entry points

- [`../WORKFLOW.md`](../WORKFLOW.md): operational sequence for the
  **field / confocal route**. It does not cover the whole-slide route.
- [`../README.md`](../README.md): installation, configuration, output schema,
  and statistics.

Historical diagrams are not kept here because their intensity-centered logic no
longer matches the production pipeline. They are archived in
[`../legacy/figures/`](../legacy/figures/README.md).
