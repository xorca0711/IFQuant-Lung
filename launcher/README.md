# IF Quant Windows launcher

`IFQuantLauncher-v1.7.1.exe` is a Windows Forms front end for
`IF_Quant_Pipeline.groovy`. The executable embeds the exact Groovy pipeline and
marker registry present at build time. It does not reimplement image analysis.

## Build

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\launcher\build.ps1
```

The build reads the assembly version and writes versioned artifacts directly
to the repository root:

- `IFQuantLauncher-v1.7.1.exe`
- `IFQuantLauncher-v1.7.1.sha256.txt`

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
4. Leave the staining panel on **AUTO** when the complete marker combination is
   present in image or folder names. AUTO assigns every matching analytical
   image independently, so multiple recognized panels and validated marker
   subsets can share one batch. An explicit `samplesheet.csv` panel is used
   first. Otherwise, the marker names select a built-in preset and its fixed
   acquisition channel order. AUTO does not identify stains from fluorescence
   colors, intensity, or image content. Any unknown image stops before Fiji
   starts, and nonstandard channel order requires a validated custom panel.
   The confirmation dialog lists each allocated panel and image count.
5. For a first pilot, set **Image limit** to `1`; otherwise `0` means all
   matching images. The recommended settings can normally remain unchanged.
6. Click **Create visual merge panels** to generate the merged marker
   presentation for the configured image scope, if desired. With **Image
   limit** set to `0`, every matching image is processed. This separate
   operation creates merged and supporting enhanced channel PNGs only; it does
   not segment cells or create quantitative outputs.
7. Click **Review and run analysis**, verify the plain-language run summary,
   and click **OK**. The full run exports the same enhanced per-channel and
   merged views for every analyzed image in addition to quantitative results.

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
  and both `run_summary.csv` and `run_summary.xlsx` exist. Microscope
  `Map_A##.oir` acquisitions may be listed as deliberate non-analysis skips
  without making the run fail.
- **Stopped with a problem**: Fiji terminated or required outputs are missing;
  inspect the visible log and `run_manifest.json`.
- **Cancelled**: the user terminated Fiji. Partial files are diagnostic only
  and must not be aggregated.

For the visual-merge-only operation, the same progress area reports
preparation, the current source image, and PNG verification. Success requires
at least one visual merge panel, Fiji exit code 0, and no unexpected output
file types. No summary button is enabled because this mode intentionally
creates no `run_summary.csv` or workbook.

The launcher creates a new timestamped folder for every run. It will never set
`IFQ_ALLOW_NONEMPTY_OUTPUT=true`, and it always sets
`IFQ_MORPHOLOGY_PRIMARY=true`.

The adjacent **Create visual merge panels** button sets the protected internal
`IFQ_DISPLAY_PREVIEW_ONLY=true` mode and forces enhanced display export. It
uses the current panel selection, Z-stack handling, filename filter, recursive
setting, and image limit. An image limit of `0` processes all matching images.
It stops after PNG creation and writes no masks, cell tables, summary
CSV/Excel, parameter JSON, Z-profile table, analysis manifest, or
`launcher_run.txt`.

The launcher sets `IFQ_EXPORT_DISPLAY_CHANNELS=true` for full analyses. Every
analyzed image therefore receives enhanced per-channel and merged companion
views in its result folder. Display percentiles and optional gamma remain
reproducible expert/panel settings; all enhanced files are labeled
`DISPLAY ONLY - NOT QUANTIFIED` and never feed back into quantification.

The completed folder contains the normal Fiji pipeline outputs plus
`launcher_run.txt`, which records:

- launcher version;
- Fiji executable and exit code;
- pipeline and registry SHA-256 hashes;
- the exact `IFQ_*` environment used for the run.

`run_summary.csv` remains the primary machine-readable region-level summary.
`run_summary.xlsx` opens on **Image Positive Counts**, with one aligned row per
image/region showing total cells plus every marker's final-positive cell count
and fraction of that image/region's total cells. **Run Summary** retains the
complete audit fields, and **Skipped Inputs** records deliberate exclusions. A
run is shown as complete only when Fiji exits successfully, the manifest
status is `complete`, and both summary files exist.

For a full AUTO run, `auto_panel_assignments.csv` records the exact
`relative_path` to panel allocation used for every image. The manifest and
summary retain the panel on every image/region row. Marker columns are aligned
across panels; cells are blank for markers absent from that image's allocated
panel rather than being interpreted as negative. Statistical aggregation must
remain stratified by compatible panel and endpoint.

The validated ALI mapping subsets are:

- `ALI1_MAP`: C1 DAPI, C2 SCGB3A2-488, C3 tdTOM;
- `ALI23_MAP`: C1 DAPI, C2 KRT5-488, C3 tdTOM.

These presets are selected for known 4× mapping acquisitions whose folder name
still mentions a non-acquired 647 marker. The absent p63, AcTub, or MUC5AC
channel is not analyzed and cannot generate a negative call.

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
