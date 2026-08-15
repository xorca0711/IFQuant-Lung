# H&E brightfield pipeline

## Current authorized state

The 2026-08-12 G-SURF H&E cohort is at **R1 / H3**. Image QC is
reviewer-approved for four mice and eight technical sections. The approval
covers stain separation, the lung-section envelope, stained tissue material,
artifact presentation, and the usable-tissue denominator.

It does not authorize automated lesion burden, nuclear density, ordinal
pathology scores, immune lineage, KRT5-pod identification, or mouse-level
hypothesis tests.

Run the fail-closed status audit from the repository root:

```powershell
python .\scripts\he_pipeline.py status
```

The command validates the raw VSI/ETS inventory, blind-section mapping, locked
stain profile, approved R1 hashes, every approved package file, and the H4
development exports. Any identity, file, or hash mismatch is blocking.

## Analysis hierarchy

| Stage | Decision | Current state |
|---|---|---|
| H0 | RGB brightfield modality and declared analytical series | passed |
| H1 | mouse, slide, section, and blind identity | passed |
| H2 | frozen H&E stain/scan profile | R1 approved |
| H3 | tissue, artifact, and usable-denominator masks | R1 approved |
| H4 | airway, vessel, alveolar, pleural, or unresolved anatomy | development context only |
| H5 | inflammatory-cell-rich and structural-injury candidates | no validated engine |
| H6 | compartment/topology-compatible lesion authorization | unavailable |
| H7 | blinded whole-section pathology review | rubric ready; review incomplete |
| H8 | technical-section QC and mouse aggregation | blocked |
| H9 | H&E-to-IF association | blocked; descriptive mouse-level only when available |

Route 3 remains disabled because an approved denominator is not equivalent to
an approved pathology numerator.

## Practical review target

The next review is one row per blinded whole section, not one yes/no anatomy
decision per sampled tile. Score:

1. overall extent of abnormal inflammatory-cell-rich or consolidated tissue;
2. alveolar/interstitial inflammation;
3. peribronchial inflammation;
4. perivascular inflammation;
5. consolidation or airspace loss;
6. airway epithelial injury or luminal debris.

Supporting high-resolution tiles are evidence locators only. They are not
replicates and are not used to estimate prevalence.

Anatomy shorthand:

- **Airway:** circular or branching lumen with a continuous epithelial
  cell-nuclear lining.
- **Alveolar parenchyma:** sponge-like small airspaces separated by thin septa.
- **Vessel:** thin-walled elongated, slit-like, or partly collapsed lumen with an
  endothelial nuclear lining.

## Build the review package

```powershell
python .\scripts\he_pipeline.py build-review
```

The command refuses to overwrite an existing package. Its default output is:

```text
D:\IFQ_Runs\H&E_20260812\14_H5_H7_PATHOLOGY_REVIEW_DEVELOPMENT
```

The package contains blinded whole-section references, approved R1 QC context,
high-resolution supporting contact sheets, the locked development rubric, a
single eight-row review form, reportability boundaries, and internal provenance.

## Endpoint interpretation

H&E supplies whole-section inflammatory and structural context for the settled
confocal result. It can support descriptions of cell-rich infiltration,
consolidation, cuffing, airspace loss, and epithelial injury. It cannot
establish immune-cell lineage or identify a KRT5-positive pod.

After complete blinded review, section scores may be described. Mouse-level
fractions must pool raw numerator and denominator components across BF_01 and
BF_02; technical sections never increase biological n. With one mouse in each
genotype-by-infection cell, the present cohort remains descriptive and cannot
support genotype, infection, or interaction inference.
