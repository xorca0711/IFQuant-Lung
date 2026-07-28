# IF Quant Windows launcher

`IFQuantLauncher.exe` is a Windows Forms front end for
`IF_Quant_Pipeline.groovy`. The executable embeds the exact Groovy pipeline and
marker registry present at build time. It does not reimplement image analysis.

## Build

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\launcher\build.ps1
```

The build uses the C# compiler included with Windows .NET Framework and creates:

- `dist/IFQuantLauncher.exe`
- `dist/SHA256SUMS.txt`

The executable is compiled as `AnyCPU`. The same file supports Windows ARM64
and Windows x64; no .NET SDK installation is required on the analysis system.

## Runtime requirements

- Windows ARM64 or Windows x64 with .NET Framework 4.x;
- a Fiji installation containing Bio-Formats and the plugins required by the
  selected segmentation mode;
- original images accessible through a local, mapped, or network folder.

## Launcher workflow

1. Select the folder containing the original confocal files.
2. Select the Fiji executable or its installation folder.
3. Select an output parent folder.
4. Select the staining panel that matches both the marker names and acquisition
   channel order. The description below the selector shows the expected order.
5. For a first pilot, set **Image limit** to `1`; otherwise `0` means all
   matching images. The recommended settings can normally remain unchanged.
6. Click **Review and run analysis**, verify the plain-language run summary,
   and click **OK**.

The **First-time help** button explains every required choice. Script-oriented
settings and custom panel files are hidden under **Show advanced study
options**; new users should leave that section closed. **Restore recommended
settings** safely resets processing choices without changing the experiment's
folders or staining panel.

All compartment-dependent cell markers use the same safe asymmetry. In an
ambiguous/unassigned field, strict localization-correct marker evidence can be
retained as an exploratory context-unresolved positive, but absence remains
indeterminate. A negative requires an independently assigned compatible ROI,
and a known incompatible ROI remains indeterminate. Context-unresolved
positives cannot authorize compound lineage/state classes. For panel E, AcTub
additionally requires a uniquely nucleus-owned apical ciliary component;
regional ciliary area remains the primary 20x measurement.

The launcher opens maximized by default. Its contents are DPI-aware and
scrollable so the **Run Fiji analysis**, readiness status, and log remain
reachable on displays using enlarged Windows scaling.

## Progress and completion states

The progress area distinguishes these states:

- **Starting**: Fiji and the embedded pipeline are being prepared.
- **Running**: the current image number and filename are shown. The Groovy
  pipeline emits `[IFQ_PROGRESS] current/total` events for the launcher.
- **Finalizing**: images are finished and summary files are being verified.
- **Complete**: Fiji exited successfully, `run_manifest.json` says `complete`,
  and `run_summary.csv` exists.
- **Stopped with a problem**: Fiji terminated or required outputs are missing;
  inspect the visible log and `run_manifest.json`.
- **Cancelled**: the user terminated Fiji. Partial files are diagnostic only
  and must not be aggregated.

The launcher creates a new timestamped folder for every run. It will never set
`IFQ_ALLOW_NONEMPTY_OUTPUT=true`, and it always sets
`IFQ_MORPHOLOGY_PRIMARY=true`.

The completed folder contains the normal Fiji pipeline outputs plus
`launcher_run.txt`, which records:

- launcher version;
- Fiji executable and exit code;
- pipeline and registry SHA-256 hashes;
- the exact `IFQ_*` environment used for the run.

`run_summary.csv` remains the primary region-level summary. A run is shown as
complete only when Fiji exits successfully, the manifest status is `complete`,
and `run_summary.csv` exists.

When an installation folder is selected, the launcher detects the Windows
architecture and chooses Fiji in this order:

- ARM64: `fiji-windows-arm64.exe`, then x64-compatible fallbacks;
- x64: `fiji-windows-x64.exe` or `ImageJ-win64.exe`, then generic fallbacks.

Selecting the executable directly always takes precedence. Fiji plugins that
ship native libraries must still match the chosen Fiji/Java architecture.

## Safety and interpretation

- Existing `IFQ_*` values inherited from Windows are cleared before each run,
  preventing stale thresholds or directories from leaking into a new analysis.
- Core directory, panel, projection, and decision-authority variables cannot be
  overridden from the Advanced box.
- Cancelling retains partial outputs for diagnosis; those outputs must not be
  aggregated.
- The launcher is research software. Marker thresholds and morphology gates
  still require control-derived validation as described in
  [`../WORKFLOW.md`](../WORKFLOW.md).
