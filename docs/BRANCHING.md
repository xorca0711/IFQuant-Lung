# Branch roles

## Active branches

- `main`: current universal morphology-first pipeline, additive layer-aware
  Z-stack implementation, visualization-only intensity enhancement, ALI
  presets, and released launcher v1.7.1.
- `codex/z-stack-analysis`: retained integration branch for the v1.6
  Z-stack/display release. It has been promoted to `main`; new production work
  should start from `main`.
- `codex/legacy-pre-reorganization`: historical pre-reorganization snapshot.

## Already merged historical labels

The following branches are already ancestors of `main` and do not contain
unmerged work:

- `claude/influenza-injury-fiji-pipeline-d4jt28`
- `codex/incorporate-morphology-hierarchy`
- `codex/universal-lung-marker-profiles`

They may remain as read-only history labels or be deleted from the remote after
the corresponding tags/commits have been verified. Deleting them is not
required for repository correctness and must not be confused with deleting the
legacy snapshot.

## Completed Z-stack merge gate

The following checks were completed before promoting
`codex/z-stack-analysis` to `main`:

1. Representative 20× stacks for `ALI1`, `ALI2`, and `ALI3` completed.
2. Each validation manifest completed without image failures.
3. Zero-cell false-success protection was enabled and DAPI QC reviewed.
4. Per-marker Z profiles and selected slabs were recorded.
5. The display-enhancement regression reproduced the pre-enhancement ALI3
   counts and area fractions exactly.
6. The versioned launcher was rebuilt and its embedded-runtime self-test
   returned exit code 0. Launcher v1.6.1 additionally passed real-folder AUTO
   detection for ALI1, ALI2, and ALI3 and rejected their mixed parent folder.
   Launcher v1.6.2 added the independently validated five-image,
   enhanced-PNG-only preview route without changing the full-analysis path.
   Launcher v1.7.0 adds strict per-image routing for mixed built-in panels and
   validated three-channel ALI mapping subsets.
   Launcher v1.7.1 additionally exports a companion visual merge panel for
   every image in a full analysis and removes the five-image cap from the
   separate visual-merge-only operation.

Automatic Z ranges remain pilot settings. A confirmatory study must still
freeze explicit ranges, intensity thresholds, and morphology gates after
blinded control review.
