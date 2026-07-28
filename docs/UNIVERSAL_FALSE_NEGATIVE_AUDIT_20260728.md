# Universal Marker False-Negative Audit — 2026-07-28

## Scope

This audit reviews the shared decision layer used by all built-in and custom
cell-call markers. It addresses computational false-negative risk caused by
missing anatomy, invalid spatial support, or reporting omissions. It does not
estimate biological sensitivity or specificity; that requires blinded manual
annotations and acquisition-matched controls.

## Defects corrected

| Defect | Risk | Revision |
|---|---|---|
| Legacy mean-intensity mode ran before evaluability | Missing compartment, invalid projection, or shared support could become a negative | Evaluability now precedes both morphology and legacy-intensity authority |
| Only AcTub could preserve strict positive evidence when context was unresolved | Other compartment-dependent markers could show zero final positives even when localization-correct evidence was present | The asymmetric context policy now applies to every cell-call role |
| Known wrong context and unresolved context were not separately audited | Positive evidence could disappear inside the indeterminate total | Per-cell context state and region-level unresolved/excluded evidence counts are exported |
| A declared compound class with zero evaluable cells was omitted | Missing columns could be mistaken for “not tracked” or zero positive | Every declared class is always emitted with count, evaluable count, and indeterminate count |
| Area evidence in unresolved context used a generic status | A nonzero area could be overlooked | Area status now distinguishes context-unresolved or context-excluded positive evidence |

## Universal decision matrix

| Technical and marker evidence | Required context | Final marker call |
|---|---|---|
| Technical support valid; strict marker evidence passes | Compatible or not required | Positive |
| Technical support valid; strict marker evidence fails | Compatible or not required | Evaluable negative |
| Technical support valid; strict marker evidence passes | Unassigned or ambiguous | Exploratory context-unresolved positive |
| Technical support valid; strict marker evidence fails | Unassigned or ambiguous | Indeterminate |
| Any marker evidence | Known incompatible context | Indeterminate for the intended endpoint |
| Invalid projection, empty support, or ambiguous ownership | Any | Indeterminate |

Context-unresolved positives are marker-expression observations. They cannot
authorize a compound lineage, state, mutation, malignancy, or anatomical class.

## Marker coverage

| Group | Markers or examples | Protection |
|---|---|---|
| Built-in compartment-dependent cell calls | AGER, Pro-SPC, PDPN/T1A, mRAGE, AcTub | Positive evidence can survive unresolved context; negative requires alveolar or airway eligibility |
| Built-in markers without a required anatomical gate | KRT5, CD4, CD8, Sox2, p63, YAP, CC10, tdTomato | Invalid projection, shared ownership, and empty support remain indeterminate; otherwise positive/negative uses the role-specific morphology |
| Universal/custom epithelial and tumor profiles | KRT8, CLDN4, SFTPC, KRT17, TP63, NKX2-1, NAPSA, EPCAM, SOX9 | Same asymmetric policy whenever the panel declares `expectedCompartments` |
| Universal/custom mesenchymal or vascular cell calls | ITGA2, PDGFRB, PDGFRA, PECAM1/CD31, CDH5, CA4, GPIHBP1 | Same policy; regional area is preferred when 20x ownership is not reliable |
| Reporter/state markers | Red2-KrasG12D RFP, KRAS, Ki-67/MKI67 | Marker evidence is preserved, but context/co-markers remain mandatory for mutation-, clone-, tumor-, or lineage-level interpretation |
| Area-only markers | ACTA2, CTHRC1, COL1A1, MUC5AC, MUC5B and study-declared `regional_area` channels | No per-nucleus negative is created; numeric area and context evidence status are reported |

Unknown markers receive the same protection when a custom panel declares a
supported analytical role and, where relevant, `expectedCompartments`.
`allowPositiveWithoutCompartment: false` can disable unresolved-context
positives for an assay where even marker presence cannot be interpreted without
geography.

## Fiji regression results

### Panel R, G002, deliberately ambiguous context

| Marker | Positive | Negative | Indeterminate | Strict evidence positive |
|---|---:|---:|---:|---:|
| T1alpha | 429 | 0 | 1,679 | 429 |
| tdTomato | 1,183 | 817 | 108 | 1,183 |
| mRAGE | 113 | 0 | 1,995 | 113 |

The T1alpha and mRAGE positive counts exactly match the earlier
alveolar-context pilot. Their earlier negative counts were not retained because
the validation field was deliberately set to ambiguous. Both compound classes
(`T1A+_tdTOM+` and `mRAGE+_tdTOM+`) are explicitly present with 0 evaluable and
2,108 indeterminate cells.

### Panel E, G001, deliberately ambiguous context

| Marker | Positive | Negative | Indeterminate |
|---|---:|---:|---:|
| CC10 | 1,835 | 765 | 177 |
| tdTomato | 1,569 | 1,031 | 177 |
| AcTub | 321 | 0 | 2,456 |

Nuclei, all three marker decisions, AcTub regional area, and AcTub component
count match the prior validated cellular-context pilot exactly. The
`AcTub+_tdTOM+` compound class is now explicitly all-indeterminate because the
airway context was unresolved.

## Remaining validation limits

- Adaptive Otsu thresholds remain exploratory and can still be biologically too
  sensitive or insensitive. Freeze control-derived thresholds before a cohort
  study.
- A missed DAPI nucleus is a segmentation omission, not a marker-negative cell.
- Perinuclear rings approximate cytoplasm and membrane at 20x; validate
  ownership against manual annotations.
- Maximum-intensity projection is invalid for YAP nuclear localization unless
  separately validated; use a single plane or 3D workflow.
- Marker positivity alone does not establish a lung cell identity. Anatomical
  ROI and co-marker rules remain part of the biological endpoint.

These constraints follow multiplex IF best practice: nuclear/cell segmentation,
subcellular localization, tissue compartments, and coexpression-defined
phenotypes are separate analytical layers
([SITC multiplex IHC/IF best practices](https://pmc.ncbi.nlm.nih.gov/articles/PMC11749220/)).
The marker registry supplies research context, but lung cell identity still
requires multi-marker and anatomical evidence
([Human Lung Cell Atlas](https://www.nature.com/articles/s41586-020-2922-4)).
