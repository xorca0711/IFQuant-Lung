# Legacy Archive

This folder contains superseded development artifacts. Nothing here is an
authoritative analysis entry point.

The complete repository layout immediately before organization is preserved on
Git branch:

`codex/legacy-pre-reorganization`

## Contents

- `figures/`: earlier workflow and overlay diagrams. These predate the final
  morphology-authoritative three-state hierarchy.
- `launchers/`: archived launcher executables and checksums. Version 1.7.2 is
  retained for byte-exact legacy reproduction, and v1.9.0 is the superseded
  release immediately preceding the current repository-root v1.9.2 launcher.
- `scripts/README.md`: routes historical script access to the snapshot branch
  and commit history; no legacy script is an active entry point.
- `test_runs/`: local smoke, baseline, and intermediate Fiji outputs.
- `pilot_output/`: earliest sample-image pilot output.
- `ref_images_analysis_output/`: output formerly stored beside the reference
  ND2 image.

Generated run folders and test scripts remain ignored by Git. This README and
the archived tracked figures document what was moved.

## Run chronology

- `TestRun1` through `TestRun9`: import, channel-map, DAPI, naming, and initial
  T1alpha/mRAGE development.
- `TestRun10`: CC10/AcTub baseline before morphology authority.
- `TestRun11`: initial morphology implementation.
- `TestRun12`: two-image morphology-primary validation before final area-mask
  consistency improvements.
- `FinalPilot_*_morphology_primary`: first one-image confirmation runs.
- The final `*_v2` runs are not legacy. They were in `../test_runs/current/`, which was deleted on 2026-08-09 (gitignored generated output, regenerable). Their numbers survive in `docs/PILOT_G002_MORPHOLOGY_RESULTS.md`.

For current work, start at `../WORKFLOW.md` and
`../IF_Quant_Pipeline.groovy`.
