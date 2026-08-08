# IF Quant Windows launcher

`IFQuantLauncher-v1.8.0.exe` is a Windows Forms front end for the analysis
pipeline. It embeds the exact Groovy engine, marker registry, QuPath tiling
script and Python reconciliation script present at build time. **It does not
reimplement image analysis.** Every number it produces comes from
`IF_Quant_Pipeline.groovy`, which is frozen.

## What changed in v1.8.0

v1.7.2 assumed one kind of input: a folder of confocal/field images measured by
Fiji. v1.8.0 makes the *kind of image* an explicit first choice, because the
correct chain of tools differs per kind and choosing wrongly produces numbers
that look fine and are not.

The old behaviour did not go away — it is route 4, and it is verified equal to
v1.7.2 by execution rather than by assertion (see **Legacy equivalence** below).

## The four routes

Route is the first thing selected, before any folder. Each route declares its
own stage list, its own required tools, and its own hazard policy; the launcher
refuses to start a run it cannot describe.

| # | Route | Tools | Produces |
|---|---|---|---|
| 1 | **IF — confocal / field images** | Fiji only | `run_summary.csv` (+ `.xlsx`, `run_manifest.json`), one row per (image, region) |
| 2 | **IF — slide scanner (`.vsi` whole slide)** | QuPath → Fiji → Python | tiles → per-tile measurements → `stats/slide_level_summary.csv` |
| 3 | **H&E / brightfield** | — | **not available in this build** |
| 4 | **Fiji-only legacy mode** | Fiji only | byte-for-byte the v1.7.2 environment and command line |

**Route 2 is the important architectural point.** QuPath reads and tiles the
slide; the *same* frozen Fiji engine measures the tiles; stage 3 reconciles
tiles back to one slide. QuPath never measures anything. The handoff is
file-based because the two applications ship incompatible Java versions
(Chiaruttini et al. 2022, *Front Comput Sci* 3:780026).

Route 2 also **hard-blocks** an omitted threshold. Routes 1 and 4 only flag it.
The difference is deliberate: a field run with adaptive thresholds is a
defensible exploratory measurement, whereas a slide run silently re-derives a
threshold on each of ~370 tiles, which is not one measurement at all.

### Route 3 is visible and deliberately unselectable

It appears in the list, greyed, with a written reason. That is a design choice,
not an oversight. Hiding it would invite someone to point route 1 at an H&E
slide, and **that would not fail** — the fluorescence engine assumes signal is
bright on a dark background, which is inverted for H&E. It would produce a
complete, plausible, wrong `run_summary.csv`.

Re-enabling is one line:

```csharp
// launcher/IFQuantLauncher.Routing.cs
public static readonly bool BrightfieldRouteEnabled = false;   // this build
```

It is `static readonly`, not `const`, so the branches are *not* folded away at
compile time and the disabled paths stay reachable and testable. Flipping it
makes the route selectable but does not conjure an engine: `BuildStage2` still
refuses with a named cause, so a half-finished re-enable fails at the Run
button instead of producing an empty run.

An unknown route id fails closed on every axis — no tools assumed present, an
omitted threshold treated as a hard stop, nothing written.

## Legacy equivalence (route 4)

Route 4 exists so that analyses run before v1.8.0 stay reproducible. It is
checked by a harness that *executes* both versions rather than asserting about
them — `launcher/legacy_equivalence_report.txt`, 82 checks, 0 failures:

- **Environment**: 7 fixture cases (defaults, all-non-default, each conditional
  key, both at once, an Advanced overlay that shadows a base key, and values
  containing spaces/quotes/non-ASCII) produce byte-identical variable sets.
- **Process level**: the *child process* environment is compared, not just the
  dictionary — inherited `IFQ_*` variables are stripped, and the child receives
  no `IFQ_MIN_INCLUDED_NUCLEI`, which v1.7.2 never wrote.
- **Drift guard**: the harness re-reads the real v1.7.2 source and confirms the
  key set, the assignment *order*, the hardcoded values, and that v1.7.2
  contains no QuPath/`.vsi`/brightfield reference. If someone edits the legacy
  profile to match a changed v1.7.2, this fails.
- **Advanced box**: 23 input lines, accepted/refused identically by both.

To re-run it, the v1.7.2 source must be restored from git history — the working
tree now holds v1.8.0:

```bash
git show 072f28b:launcher/IFQuantLauncher.cs > /tmp/IFQuantLauncher-v1.7.2.cs
```

## Build

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\launcher\build.ps1
```

Three source files are compiled into one self-contained `AnyCPU` executable
with no external dependencies; the same file supports Windows ARM64 and x64 and
needs no .NET SDK on the analysis system. The build **runs** `--self-test` and
a UI smoke test, and discards the binary on failure. (v1.7.2 shipped a
self-test and never ran it.)

Artifacts are written to the repository root and are **not committed** —
`.exe` and its `.sha256.txt` sidecar belong in GitHub Releases:

- `IFQuantLauncher-v1.8.0.exe`
- `IFQuantLauncher-v1.8.0.sha256.txt`

The build prints the SHA-256 of the exe and of each embedded artefact, so a
shipped binary can be traced to the exact engine it carries.

## Runtime requirements

- Windows ARM64 or x64 with .NET Framework 4.x
- Fiji with Bio-Formats and the plugins for the selected segmentation mode
- QuPath 0.7+ for route 2 only
- Python 3 for route 2 stage 3 only
- images reachable through a local, mapped, or network folder

## Panel assignment

Leave the staining panel on **AUTO** when the complete marker combination is
present in image or folder names. AUTO assigns every matching analytical image
independently, so multiple recognized panels and validated marker subsets can
share one batch. An explicit `samplesheet.csv` panel is used first; otherwise
the marker names select a built-in preset and its fixed acquisition channel
order.

AUTO does **not** identify stains from fluorescence colours, intensity, or
image content. Any unknown image stops the run before Fiji starts, and a
nonstandard channel order requires a validated custom panel. The confirmation
dialog lists each allocated panel and its image count.

## Aggregation is not optional

Every route produces per-image or per-tile rows. Those are **not** the
statistical unit. Run `aggregate_to_mouse.py` before any test; n = mice.
