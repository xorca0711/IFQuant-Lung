# Archived launcher binaries

This directory keeps launcher binaries that still matter for reproducibility or
for access to a superseded release.

## `IFQuantLauncher-v1.7.2.exe`

```
sha256  bd8e71764013c2dc7de0d43b76457fca136b50be20fc89e8d19d85fb4cb4a1c4
```

This path is **hardcoded in the shipping launcher**
(`launcher/IFQuantLauncher.Routing.cs`, `LegacyProfile.V172ExeArchivePath`) and
is printed to the operator at runtime, with that hash, whenever legacy mode
detects that its embedded engine has drifted from the one v1.7.2 shipped:

> *"The environment is reproduced; the numbers may not be. To reproduce the
> numbers, run the archived binary `legacy/launchers/IFQuantLauncher-v1.7.2.exe`
> (sha256 …) instead."*

That drift is real and expected — the current engine carries the
`blackBackground` fix, which changes measured nucleus counts by ~101×. So route
4 reproduces v1.7.2's **environment and command line** exactly, and this binary
is the only way to reproduce v1.7.2's **numbers**. Deleting it would turn a
runtime instruction into a dead reference.

Do not move or rename it without changing `V172ExeArchivePath` in the same
commit.

## `IFQuantLauncher-v1.9.0.exe`

Version 1.9.0 is the previous release. Its executable and SHA-256 sidecar were
moved here after v1.9.1 replaced it at the repository root. The v1.9.0 release
corresponds to commit `22afada`.

## Other historical releases were removed

Nine superseded binaries (v1.1 through v1.7.1, plus v1.8.0) and their sidecars
were deleted from the working tree on 2026-08-09.

**This does not shrink a clone.** They remain in git history, and history was
deliberately *not* rewritten: `docs/BRANCHING.md` and `launcher/README.md` quote
commit SHAs as runnable instructions, and a rewrite would invalidate them for a
saving measured at a few MB against an 11 MB `.git`. The deletion is for
legibility — this directory now answers "which archived binary matters?" with
one file instead of eighteen.

**They are not lost.** Each is recoverable from history, and v1.8.0 is also
attached to its [GitHub Release](https://github.com/xorca0711/IFQuant-Lung/releases/tag/v1.8.0)
(marked SUPERSEDED, because it embeds the pre-fix engine).

```bash
git log --oneline --all -- legacy/launchers/IFQuantLauncher-v1.6.2.exe
git checkout <sha> -- legacy/launchers/IFQuantLauncher-v1.6.2.exe
```

Recovered binaries are **archived as built, not re-tested**. None of them was
re-run before removal, so treat any of them as a historical artefact rather than
a working tool.

## Current release

`v1.9.2` is the current maintenance source. It completes the post-release GUI
reachability repair and retains the validated 20x/2k lung-field preset. The
v1.9.1 binaries are retained here as local ignored historical artifacts. See
[Releases](https://github.com/xorca0711/IFQuant-Lung/releases).
New build artefacts are not tracked in git by default; `launcher/build.ps1`
writes them to the repository root, where `.gitignore` excludes them.
