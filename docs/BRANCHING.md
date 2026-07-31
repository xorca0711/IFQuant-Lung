# Branch roles

## Active branches

- `main`: stable universal morphology-first pipeline and released launcher.
- `codex/z-stack-analysis`: additive layer-aware Z-stack implementation,
  marker-profile refinements, ALI presets, validation outputs, and launcher
  v1.6.0. Merge only after representative stacks from all three ALI panels pass
  visual and quantitative QC.
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

## Z-stack merge gate

Before merging `codex/z-stack-analysis` into `main`:

1. Run at least one representative 20× stack for `ALI1`, `ALI2`, and `ALI3`.
2. Confirm each `run_manifest.json` is complete with no image failures.
3. Review DAPI candidate/accepted/rejected QC and reject any zero-cell field.
4. Review every `*__z_plane_profile.csv`.
5. Confirm cell-body and apical slabs are biologically plausible.
6. Freeze explicit Z ranges if the acquisition geometry is consistent.
7. Compare final cell counts and regional areas with blinded manual review.
8. Rebuild and self-test the versioned launcher.
