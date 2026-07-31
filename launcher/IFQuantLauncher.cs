using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("IF Quant Launcher")]
[assembly: AssemblyDescription("Windows launcher for the Fiji morphology-primary IF quantification pipeline")]
[assembly: AssemblyCompany("IF Quant Pipeline")]
[assembly: AssemblyProduct("IF Quant Launcher")]
[assembly: AssemblyCopyright("Research software")]
[assembly: AssemblyVersion("1.7.1.0")]
[assembly: AssemblyFileVersion("1.7.1.0")]

namespace IFQuantLauncher
{
    internal static class Program
    {
        [STAThread]
        private static void Main(string[] args)
        {
            if (args != null && args.Length > 0 &&
                string.Equals(args[0], "--self-test", StringComparison.OrdinalIgnoreCase))
            {
                Environment.ExitCode = RuntimeBundle.SelfTest();
                return;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    internal sealed class MainForm : Form
    {
        private TextBox inputBox;
        private TextBox fijiBox;
        private TextBox outputBaseBox;
        private TextBox runNameBox;
        private TextBox includeRegexBox;
        private TextBox panelConfigBox;
        private TextBox advancedBox;
        private ComboBox panelBox;
        private ComboBox segmenterBox;
        private ComboBox projectionBox;
        private ComboBox tissueModeBox;
        private ComboBox compartmentModeBox;
        private ComboBox wholeCompartmentBox;
        private CheckBox recursiveBox;
        private NumericUpDown maxImagesBox;
        private NumericUpDown singlePlaneBox;
        private Button runButton;
        private Button previewButton;
        private Button cancelButton;
        private Button openOutputButton;
        private Button openSummaryButton;
        private TextBox logBox;
        private Label statusLabel;
        private Label progressDetailLabel;
        private Label panelHelpLabel;
        private ProgressBar progressBar;
        private GroupBox advancedGroup;
        private CheckBox showAdvancedBox;
        private ToolTip toolTips;

        private Process runningProcess;
        private string lastRunDirectory;
        private string lastSummaryPath;
        private bool cancellationRequested;
        private bool runningPreview;
        private readonly object processLock = new object();

        private static readonly Dictionary<string, string> PanelDescriptions =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
            {
                { "LEFT", "Priority project left panel: DAPI, KRT5-488, AGER-555, T1alpha-647 (channels 1-4). Per-marker final-positive counts remain primary; KRT5 pod area and AGER/T1alpha membrane areas are also exported." },
                { "RIGHT", "Priority project right panel: DAPI, Pro-SPC-488, AGER-555, KRT8-647 (channels 1-4). Per-marker final-positive counts remain primary; co-expression classes are descriptive research endpoints." },
                { "ALI1", "20x ALI Z-stack: DAPI, SCGB3A2-488, tdTOM, p63-647 (channels 1-4). SCGB3A2/tdTOM use the cell-body slab; p63 uses the nuclear range." },
                { "ALI2", "20x ALI Z-stack: DAPI, KRT5-488, tdTOM, acetylated-tubulin-647 (channels 1-4). AcTub uses the independent apical slab and regional ciliary area remains primary." },
                { "ALI3", "20x ALI Z-stack: DAPI, KRT5-488, tdTOM, MUC5AC-647 (channels 1-4). MUC5AC uses apical area/cluster analysis and is not forced into a per-cell call." },
                { "ALI1_MAP", "4x ALI mapping subset: DAPI, SCGB3A2-488, tdTOM (channels 1-3). The named p63 channel is absent from the mapping acquisition and is not analyzed." },
                { "ALI23_MAP", "4x ALI mapping subset: DAPI, KRT5-488, tdTOM (channels 1-3). The named AcTub/MUC5AC channel is absent from the mapping acquisition and is not analyzed." },
                { "E", "20x airway panel: DAPI, CC10, tdTOM, acetylated tubulin (channels 1-4). AcTub uses uniquely nucleus-owned apical ciliary components. Strict positive evidence can be retained when context is unresolved; a negative still requires an airway ROI." },
                { "R", "20x alveolar panel: DAPI, T1alpha/PDPN, tdTOM, mRAGE (channels 1-4). T1alpha and mRAGE negatives require an alveolar ROI; strict evidence in unresolved context is reported separately." },
                { "M", "4x mapping panel: DAPI, CC10, tdTOM (channels 1-3)." },
                { "A", "DAPI, KRT5, AGER (channels 1-3)." },
                { "B", "DAPI, KRT5, ProSPC (channels 1-3)." },
                { "C", "DAPI, KRT5, CD8 (channels 1-3)." },
                { "D", "DAPI, KRT5, CD4 (channels 1-3)." },
                { "P", "DAPI, KRT5, PDPN/T1alpha (channels 1-3)." },
                { "S", "DAPI, KRT5, Sox2 (channels 1-3)." },
                { "S2", "DAPI, KRT5, p63, YAP (channels 1-4); use a single z-plane for YAP." },
                { "T", "Pilot plumbing test only; not valid for biological interpretation." }
            };

        private static readonly HashSet<string> ProtectedEnvironmentKeys =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                "IFQ_INPUT_DIR", "IFQ_OUTPUT_DIR", "IFQ_PANEL",
                "IFQ_MARKER_REGISTRY", "IFQ_PANEL_CONFIG", "IFQ_PANEL_MAP_PATH",
                "IFQ_RECURSIVE", "IFQ_INCLUDE_REGEX", "IFQ_MAX_IMAGES",
                "IFQ_SEGMENTER", "IFQ_PROJECTION", "IFQ_SINGLE_PLANE",
                "IFQ_EXPORT_DISPLAY_CHANNELS", "IFQ_DISPLAY_PREVIEW_ONLY",
                "IFQ_TISSUE_MODE", "IFQ_COMPARTMENT_MODE",
                "IFQ_WHOLE_FIELD_COMPARTMENT",
                "IFQ_ALLOW_NONEMPTY_OUTPUT", "IFQ_MORPHOLOGY_PRIMARY"
            };

        private static readonly HashSet<string> SupportedImageExtensions =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                ".czi", ".lif", ".nd2", ".oir", ".oib", ".oif",
                ".ics", ".tif", ".tiff"
            };

        public MainForm()
        {
            Text = "IF Quant Launcher";
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(960, 720);
            Size = new Size(1280, 1000);
            WindowState = FormWindowState.Maximized;
            AutoScroll = true;
            AutoScaleMode = AutoScaleMode.Dpi;
            Font = new Font("Segoe UI", 9F);
            toolTips = new ToolTip();
            toolTips.AutoPopDelay = 12000;
            toolTips.InitialDelay = 400;
            toolTips.ReshowDelay = 150;

            BuildInterface();
            LoadSavedSettings();
            ApplyFirstRunDefaults();
            UpdateAdvancedVisibility();
            UpdatePanelHelp();

            FormClosing += delegate(object sender, FormClosingEventArgs e)
            {
                lock (processLock)
                {
                    if (runningProcess != null && !runningProcess.HasExited)
                    {
                        DialogResult result = MessageBox.Show(
                            this,
                            "Fiji is still running. Cancel the analysis and close?",
                            "Analysis in progress",
                            MessageBoxButtons.YesNo,
                            MessageBoxIcon.Warning);
                        if (result != DialogResult.Yes)
                        {
                            e.Cancel = true;
                            return;
                        }
                        CancelRunningProcess();
                    }
                }
                SaveSettings();
            };
        }

        private void BuildInterface()
        {
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.AutoScroll = true;
            root.Padding = new Padding(12);
            root.ColumnCount = 1;
            root.RowCount = 7;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            Controls.Add(root);

            Label intro = new Label();
            intro.AutoSize = true;
            intro.MaximumSize = new Size(980, 0);
            intro.Padding = new Padding(0, 0, 0, 8);
            intro.Text =
                "Quick start: (1) choose the folder containing your original microscope files, " +
                "(2) choose the Fiji installation and where results should be saved, " +
                "(3) leave panel selection on AUTO when the complete marker panel is named in the file/folder path, then create visual merge panels or run analysis. " +
                "Recommended settings can normally be left unchanged. Research use only.";
            root.Controls.Add(intro, 0, 0);

            GroupBox pathsGroup = new GroupBox();
            pathsGroup.Text = "Required locations";
            pathsGroup.Dock = DockStyle.Top;
            pathsGroup.AutoSize = true;
            pathsGroup.Padding = new Padding(10);
            root.Controls.Add(pathsGroup, 0, 1);

            TableLayoutPanel paths = new TableLayoutPanel();
            paths.Dock = DockStyle.Top;
            paths.AutoSize = true;
            paths.ColumnCount = 3;
            paths.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 175F));
            paths.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            paths.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 95F));
            pathsGroup.Controls.Add(paths);

            inputBox = AddPathRow(paths, 0, "Original image folder", true, false);
            fijiBox = AddPathRow(paths, 1, "Fiji executable or folder", false, true);
            outputBaseBox = AddPathRow(paths, 2, "Output parent folder", true, false);
            toolTips.SetToolTip(inputBox, "Choose the folder containing the unedited CZI, LIF, ND2, OIR/OIB/OIF, ICS, TIF, or TIFF files.");
            toolTips.SetToolTip(fijiBox, "Choose Fiji's installation folder or its executable. The launcher selects the correct Windows ARM64/x64 executable.");
            toolTips.SetToolTip(outputBaseBox, "A new timestamped run folder will be created here. Existing analysis folders are not overwritten.");

            paths.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            paths.Controls.Add(MakeLabel("Run name (optional)"), 0, 3);
            runNameBox = new TextBox();
            runNameBox.Dock = DockStyle.Fill;
            paths.Controls.Add(runNameBox, 1, 3);
            Label runHint = new Label();
            runHint.AutoSize = true;
            runHint.Text = "Fresh folder";
            runHint.Anchor = AnchorStyles.Left;
            paths.Controls.Add(runHint, 2, 3);
            toolTips.SetToolTip(runNameBox, "Optional readable name such as Mouse12_CC10_AcTub. A timestamp is added automatically.");

            GroupBox runGroup = new GroupBox();
            runGroup.Text = "Analysis settings — recommended defaults are appropriate for most first runs";
            runGroup.Dock = DockStyle.Top;
            runGroup.AutoSize = true;
            runGroup.Padding = new Padding(10);
            root.Controls.Add(runGroup, 0, 2);

            TableLayoutPanel settings = new TableLayoutPanel();
            settings.Dock = DockStyle.Top;
            settings.AutoSize = true;
            settings.ColumnCount = 4;
            settings.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 155F));
            settings.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            settings.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 155F));
            settings.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            runGroup.Controls.Add(settings);

            panelBox = MakeCombo(
                new string[] {
                    "AUTO",
                    "LEFT — priority: KRT5-488 + AGER-555 + T1alpha-647",
                    "RIGHT — priority: Pro-SPC-488 + AGER-555 + KRT8-647",
                    "ALI1",
                    "ALI2",
                    "ALI3",
                    "ALI1_MAP — 4x DAPI + SCGB3A2 + tdTOM subset",
                    "ALI23_MAP — 4x DAPI + KRT5 + tdTOM subset",
                    "E — 20x CC10 + tdTOM + acetylated tubulin",
                    "R — 20x T1alpha + tdTOM + mRAGE",
                    "M — 4x CC10 + tdTOM mapping",
                    "A — KRT5 + AGER",
                    "B — KRT5 + ProSPC",
                    "C — KRT5 + CD8",
                    "D — KRT5 + CD4",
                    "P — KRT5 + PDPN",
                    "S — KRT5 + Sox2",
                    "S2 — KRT5 + p63 + YAP",
                    "T — pilot test only"
                },
                "AUTO",
                true);
            segmenterBox = MakeCombo(
                new string[] {
                    "classic — Recommended; built into Fiji",
                    "stardist — Requires the StarDist plugin"
                },
                "classic — Recommended; built into Fiji",
                false);
            projectionBox = MakeCombo(
                new string[] {
                    "layer_aware — Marker-specific Z slabs; recommended for multichannel stacks",
                    "max — Whole-stack maximum intensity; legacy/global mode",
                    "single — One z-plane; required for YAP ratio",
                    "avg — Average intensity",
                    "sum — Sum intensity"
                },
                "layer_aware — Marker-specific Z slabs; recommended for multichannel stacks",
                false);
            tissueModeBox = MakeCombo(
                new string[] {
                    "auto — Automatically identify tissue",
                    "whole_field — Analyze the entire image"
                },
                "auto — Automatically identify tissue",
                false);
            compartmentModeBox = MakeCombo(
                new string[] {
                    "required — Strict compartment gating",
                    "optional — Allow unassigned cells"
                },
                "required — Strict compartment gating",
                false);
            wholeCompartmentBox = MakeCombo(
                new string[] { "unassigned", "airway", "alveolar", "tumor", "fibrotic", "stromal", "vascular", "immune", "ambiguous" },
                "unassigned",
                false);

            AddSetting(settings, 0, 0, "Staining panel", panelBox);
            AddSetting(settings, 0, 2, "Nucleus detection", segmenterBox);

            panelHelpLabel = new Label();
            panelHelpLabel.AutoSize = true;
            panelHelpLabel.ForeColor = Color.FromArgb(75, 75, 75);
            panelHelpLabel.Padding = new Padding(4, 2, 4, 7);
            settings.Controls.Add(panelHelpLabel, 0, 1);
            settings.SetColumnSpan(panelHelpLabel, 4);

            AddSetting(settings, 2, 0, "Z-stack handling", projectionBox);
            singlePlaneBox = new NumericUpDown();
            singlePlaneBox.Minimum = -1;
            singlePlaneBox.Maximum = 10000;
            singlePlaneBox.Value = -1;
            singlePlaneBox.Dock = DockStyle.Fill;
            AddSetting(settings, 2, 2, "Z-plane (-1 = middle)", singlePlaneBox);
            AddSetting(settings, 3, 0, "Tissue boundary", tissueModeBox);
            AddSetting(settings, 3, 2, "Anatomical gate", compartmentModeBox);
            AddSetting(settings, 4, 0, "Whole-image tissue type", wholeCompartmentBox);

            recursiveBox = new CheckBox();
            recursiveBox.Text = "Search subfolders";
            recursiveBox.Checked = true;
            recursiveBox.AutoSize = true;
            recursiveBox.Anchor = AnchorStyles.Left;
            AddSetting(settings, 4, 2, "Subfolders", recursiveBox);

            includeRegexBox = new TextBox();
            includeRegexBox.Text = ".*";
            includeRegexBox.Dock = DockStyle.Fill;
            AddSetting(settings, 5, 0, "Filename filter", includeRegexBox);

            maxImagesBox = new NumericUpDown();
            maxImagesBox.Minimum = 0;
            maxImagesBox.Maximum = 1000000;
            maxImagesBox.Value = 0;
            maxImagesBox.Dock = DockStyle.Fill;
            AddSetting(settings, 5, 2, "Image limit (0 = all)", maxImagesBox);

            panelBox.SelectedIndexChanged += delegate { UpdatePanelHelp(); };
            panelBox.TextChanged += delegate { UpdatePanelHelp(); };
            toolTips.SetToolTip(panelBox, "AUTO assigns each matching image independently from marker names in its file/folder path, then applies that built-in panel's fixed acquisition channel order. Multiple recognized panels may share one run. Unknown images stop for manual review; stains are not inferred from colors or intensity.");
            toolTips.SetToolTip(segmenterBox, "Classic is the safest first choice. Choose StarDist only when that Fiji installation has the plugin and model.");
            toolTips.SetToolTip(projectionBox, "Layer-aware mode keeps DAPI across the stack, selects a DAPI-guided cell-body slab, and selects a marker-guided apical slab. Review the saved Z profile and freeze explicit ranges before confirmatory analysis.");
            toolTips.SetToolTip(singlePlaneBox, "Used only when Z-stack handling is single. -1 asks the pipeline to use the middle plane.");
            toolTips.SetToolTip(tissueModeBox, "Auto excludes empty background. Whole field is appropriate only when the entire image should be analyzed.");
            toolTips.SetToolTip(compartmentModeBox, "Required protects the negative denominator. Strict marker evidence may be retained when anatomy is unresolved, but a negative requires a compatible compartment; a known incompatible compartment remains indeterminate.");
            toolTips.SetToolTip(wholeCompartmentBox, "Use this only when the whole image contains one known tissue compartment.");
            toolTips.SetToolTip(includeRegexBox, "Leave .* to include every supported microscope image. This is an expert regular-expression filter.");
            toolTips.SetToolTip(maxImagesBox, "0 analyzes all matching images. Use 1 for a quick pilot run.");

            TableLayoutPanel advancedContainer = new TableLayoutPanel();
            advancedContainer.Dock = DockStyle.Top;
            advancedContainer.AutoSize = true;
            advancedContainer.ColumnCount = 1;
            advancedContainer.RowCount = 2;
            advancedContainer.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            advancedContainer.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(advancedContainer, 0, 3);

            showAdvancedBox = new CheckBox();
            showAdvancedBox.Text = "Show advanced study options";
            showAdvancedBox.AutoSize = true;
            showAdvancedBox.Padding = new Padding(0, 5, 0, 2);
            showAdvancedBox.CheckedChanged += delegate { UpdateAdvancedVisibility(); };
            advancedContainer.Controls.Add(showAdvancedBox, 0, 0);
            toolTips.SetToolTip(showAdvancedBox, "Most users should leave this closed. Open it only for a validated custom panel or predeclared IFQ settings.");

            advancedGroup = new GroupBox();
            advancedGroup.Text = "Advanced study configuration — leave blank unless your study protocol requires it";
            advancedGroup.Dock = DockStyle.Top;
            advancedGroup.AutoSize = true;
            advancedGroup.Padding = new Padding(10);
            advancedContainer.Controls.Add(advancedGroup, 0, 1);

            TableLayoutPanel optional = new TableLayoutPanel();
            optional.Dock = DockStyle.Top;
            optional.AutoSize = true;
            optional.ColumnCount = 3;
            optional.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 175F));
            optional.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            optional.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 95F));
            advancedGroup.Controls.Add(optional);

            optional.Controls.Add(MakeLabel("Validated custom panel file"), 0, 0);
            panelConfigBox = new TextBox();
            panelConfigBox.Dock = DockStyle.Fill;
            optional.Controls.Add(panelConfigBox, 1, 0);
            Button panelBrowse = new Button();
            panelBrowse.Text = "Browse...";
            panelBrowse.Dock = DockStyle.Fill;
            panelBrowse.Click += delegate { BrowseJsonFile(panelConfigBox); };
            optional.Controls.Add(panelBrowse, 2, 0);

            optional.Controls.Add(MakeLabel("Predeclared IFQ settings"), 0, 1);
            advancedBox = new TextBox();
            advancedBox.Dock = DockStyle.Fill;
            advancedBox.Multiline = true;
            advancedBox.ScrollBars = ScrollBars.Vertical;
            advancedBox.Height = 76;
            advancedBox.AcceptsReturn = true;
            optional.Controls.Add(advancedBox, 1, 1);
            optional.SetColumnSpan(advancedBox, 2);
            toolTips.SetToolTip(panelConfigBox, "Expert use: a study-owned JSON file that defines marker-to-channel mappings not included in the built-in panels.");
            toolTips.SetToolTip(advancedBox, "Expert use: one validated IFQ_KEY=VALUE setting per line. Do not invent thresholds during an analysis run.");

            FlowLayoutPanel actions = new FlowLayoutPanel();
            actions.Dock = DockStyle.Top;
            actions.AutoSize = true;
            actions.FlowDirection = FlowDirection.LeftToRight;
            actions.Padding = new Padding(0, 8, 0, 6);
            root.Controls.Add(actions, 0, 4);

            Button helpButton = new Button();
            helpButton.Text = "First-time help";
            helpButton.AutoSize = true;
            helpButton.Padding = new Padding(8, 5, 8, 5);
            helpButton.Click += delegate { ShowFirstTimeHelp(); };
            actions.Controls.Add(helpButton);

            Button defaultsButton = new Button();
            defaultsButton.Text = "Restore recommended settings";
            defaultsButton.AutoSize = true;
            defaultsButton.Padding = new Padding(8, 5, 8, 5);
            defaultsButton.Click += delegate { RestoreRecommendedSettings(); };
            actions.Controls.Add(defaultsButton);

            runButton = new Button();
            runButton.Text = "Review and run analysis";
            runButton.AutoSize = true;
            runButton.Padding = new Padding(12, 5, 12, 5);
            runButton.Click += delegate { StartAnalysis(); };
            actions.Controls.Add(runButton);

            previewButton = new Button();
            previewButton.Text = "Create visual merge panels";
            previewButton.AutoSize = true;
            previewButton.Padding = new Padding(12, 5, 12, 5);
            previewButton.Click += delegate { StartDisplayPreview(); };
            actions.Controls.Add(previewButton);
            toolTips.SetToolTip(
                previewButton,
                "Creates the primary visual merge panel and supporting enhanced channel views for every image in the configured run scope. " +
                "No segmentation, cell calls, masks, CSV, Excel, parameters, or analysis manifest are produced.");

            cancelButton = new Button();
            cancelButton.Text = "Cancel";
            cancelButton.Enabled = false;
            cancelButton.AutoSize = true;
            cancelButton.Padding = new Padding(8, 5, 8, 5);
            cancelButton.Click += delegate { CancelRunningProcess(); };
            actions.Controls.Add(cancelButton);

            openOutputButton = new Button();
            openOutputButton.Text = "Open output folder";
            openOutputButton.Enabled = false;
            openOutputButton.AutoSize = true;
            openOutputButton.Padding = new Padding(8, 5, 8, 5);
            openOutputButton.Click += delegate { OpenPath(lastRunDirectory, true); };
            actions.Controls.Add(openOutputButton);

            openSummaryButton = new Button();
            openSummaryButton.Text = "Open summary Excel";
            openSummaryButton.Enabled = false;
            openSummaryButton.AutoSize = true;
            openSummaryButton.Padding = new Padding(8, 5, 8, 5);
            openSummaryButton.Click += delegate { OpenPath(lastSummaryPath, false); };
            actions.Controls.Add(openSummaryButton);

            TableLayoutPanel progressPanel = new TableLayoutPanel();
            progressPanel.Dock = DockStyle.Top;
            progressPanel.AutoSize = true;
            progressPanel.ColumnCount = 1;
            progressPanel.RowCount = 3;
            progressPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            progressPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            progressPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(progressPanel, 0, 5);

            statusLabel = new Label();
            statusLabel.Text = "Ready — choose the three required locations and staining panel";
            statusLabel.AutoSize = true;
            statusLabel.Padding = new Padding(0, 4, 0, 3);
            statusLabel.Font = new Font(Font, FontStyle.Bold);
            progressPanel.Controls.Add(statusLabel, 0, 0);

            progressBar = new ProgressBar();
            progressBar.Dock = DockStyle.Top;
            progressBar.Height = 24;
            progressBar.Minimum = 0;
            progressBar.Maximum = 100;
            progressBar.Value = 0;
            progressBar.Style = ProgressBarStyle.Continuous;
            progressPanel.Controls.Add(progressBar, 0, 1);

            progressDetailLabel = new Label();
            progressDetailLabel.Text = "Not started";
            progressDetailLabel.AutoSize = true;
            progressDetailLabel.ForeColor = Color.FromArgb(80, 80, 80);
            progressDetailLabel.Padding = new Padding(0, 3, 0, 7);
            progressPanel.Controls.Add(progressDetailLabel, 0, 2);

            logBox = new TextBox();
            logBox.Dock = DockStyle.Fill;
            logBox.Multiline = true;
            logBox.ReadOnly = true;
            logBox.ScrollBars = ScrollBars.Both;
            logBox.WordWrap = false;
            logBox.Font = new Font("Consolas", 9F);
            logBox.MinimumSize = new Size(0, 140);
            root.Controls.Add(logBox, 0, 6);
        }

        private TextBox AddPathRow(TableLayoutPanel table, int row, string label, bool folder, bool executable)
        {
            table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            table.Controls.Add(MakeLabel(label), 0, row);
            TextBox box = new TextBox();
            box.Dock = DockStyle.Fill;
            table.Controls.Add(box, 1, row);
            Button browse = new Button();
            browse.Text = "Browse...";
            browse.Dock = DockStyle.Fill;
            browse.Click += delegate
            {
                if (executable)
                    BrowseFiji(box);
                else if (folder)
                    BrowseFolder(box);
            };
            table.Controls.Add(browse, 2, row);
            return box;
        }

        private static Label MakeLabel(string text)
        {
            Label label = new Label();
            label.Text = text;
            label.AutoSize = true;
            label.Anchor = AnchorStyles.Left;
            label.Padding = new Padding(0, 4, 0, 4);
            return label;
        }

        private static ComboBox MakeCombo(string[] values, string selected, bool editable)
        {
            ComboBox combo = new ComboBox();
            combo.Dock = DockStyle.Fill;
            combo.DropDownStyle = editable ? ComboBoxStyle.DropDown : ComboBoxStyle.DropDownList;
            combo.Items.AddRange(values);
            combo.Text = selected;
            return combo;
        }

        private static void AddSetting(TableLayoutPanel table, int row, int column, string label, Control control)
        {
            while (table.RowCount <= row)
            {
                table.RowCount++;
                table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            }
            table.Controls.Add(MakeLabel(label), column, row);
            control.Dock = DockStyle.Fill;
            table.Controls.Add(control, column + 1, row);
        }

        private static string ChoiceKey(ComboBox combo)
        {
            string text = (combo.Text ?? "").Trim();
            int separator = text.IndexOf(" — ", StringComparison.Ordinal);
            return separator > 0 ? text.Substring(0, separator).Trim() : text;
        }

        private static void SelectChoice(ComboBox combo, string key)
        {
            string requested = (key ?? "").Trim();
            for (int index = 0; index < combo.Items.Count; index++)
            {
                string item = Convert.ToString(combo.Items[index], CultureInfo.InvariantCulture);
                if (string.Equals(item, requested, StringComparison.OrdinalIgnoreCase) ||
                    item.StartsWith(requested + " — ", StringComparison.OrdinalIgnoreCase))
                {
                    combo.SelectedIndex = index;
                    return;
                }
            }
            if (combo.DropDownStyle != ComboBoxStyle.DropDownList)
                combo.Text = requested;
        }

        private void UpdatePanelHelp()
        {
            if (panelHelpLabel == null || panelBox == null)
                return;
            string key = ChoiceKey(panelBox);
            string description;
            if (string.Equals(key, "AUTO", StringComparison.OrdinalIgnoreCase))
                description =
                    "Automatic mode scans matching analytical image paths for marker names. " +
                    "Each recognized image receives its own built-in panel/channel map, so multiple panels and marker subsets may share one run. Unknown images are rejected.";
            else if (!PanelDescriptions.TryGetValue(key, out description))
                description = "Custom panel key. Select a validated custom panel file under Advanced study options.";
            panelHelpLabel.Text =
                description + " Verify marker identity and acquisition channel order before running; colors in a displayed composite are not sufficient.";
            panelHelpLabel.ForeColor = string.Equals(key, "T", StringComparison.OrdinalIgnoreCase)
                ? Color.DarkRed
                : Color.FromArgb(75, 75, 75);
        }

        internal static string InferBuiltInPanelFromText(string sourceText)
        {
            string upper = (sourceText ?? "").ToUpperInvariant().Replace("Α", "ALPHA");
            string text = Regex.Replace(upper, "[^A-Z0-9]+", "");
            bool krt5 = text.Contains("KRT5");
            bool krt8 = text.Contains("KRT8");
            bool ager = text.Contains("AGER");
            bool tdtom = text.Contains("TDTOM");
            bool t1a = text.Contains("T1ALPHA") || text.Contains("T1A") ||
                       text.Contains("PDPN") || text.Contains("PODOPLANIN");
            bool prospc = text.Contains("PROSPC") || text.Contains("SFTPC");
            bool actub = text.Contains("ACTUB") || text.Contains("ACETYL");
            bool muc5ac = text.Contains("MUC5AC") || text.Contains("MU5AC");
            bool cc10 = text.Contains("CC10") || text.Contains("SCGB1A1");
            bool p63 = text.Contains("P63") || text.Contains("TP63");
            bool mapping4x = text.Contains("4X") && text.Contains("MAPPING");

            // Resolve the most specific four-channel panels before the shorter
            // universal marker combinations.
            // Olympus 4x map acquisitions contain only DAPI, the 488 marker,
            // and tdTOM even when their folder names retain the omitted 647
            // marker. Route these validated three-channel subsets first.
            if (mapping4x && cc10 && tdtom) return "M";
            if (mapping4x && text.Contains("SCGB3A2") && tdtom) return "ALI1_MAP";
            if (mapping4x && krt5 && tdtom) return "ALI23_MAP";
            if (krt5 && ager && t1a) return "LEFT";
            if (prospc && ager && krt8) return "RIGHT";
            if (text.Contains("SCGB3A2") && tdtom && p63) return "ALI1";
            // CC10 identifies the airway panel even when the mouse genotype
            // prefix contains "krt5-creERT2".
            if (cc10 && tdtom && actub) return "E";
            if (krt5 && tdtom && actub) return "ALI2";
            if (krt5 && tdtom && muc5ac) return "ALI3";
            if (t1a && tdtom && text.Contains("MRAGE")) return "R";
            if (krt5 && p63 && text.Contains("YAP")) return "S2";
            if (krt5 && text.Contains("SOX2")) return "S";
            if (krt5 && text.Contains("CD8")) return "C";
            if (krt5 && text.Contains("CD4")) return "D";
            if (krt5 && prospc) return "B";
            if (krt5 && ager) return "A";
            if (krt5 && t1a) return "P";
            return null;
        }

        private static string InferBuiltInPanelFromPath(
            string inputDirectory,
            string imagePath)
        {
            string context = Path.GetFileName(imagePath);
            string panel = InferBuiltInPanelFromText(context);
            if (panel != null)
                return panel;

            string root = Path.GetFullPath(inputDirectory)
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            DirectoryInfo directory = new FileInfo(imagePath).Directory;
            while (directory != null &&
                   directory.FullName.StartsWith(root, StringComparison.OrdinalIgnoreCase))
            {
                context = directory.Name + " " + context;
                panel = InferBuiltInPanelFromText(context);
                if (panel != null)
                    return panel;
                if (string.Equals(
                        directory.FullName.TrimEnd(
                            Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar),
                        root,
                        StringComparison.OrdinalIgnoreCase))
                    break;
                directory = directory.Parent;
            }
            return null;
        }

        internal static PanelDetectionResult DetectBuiltInPanels(
            string inputDirectory,
            string includeRegex,
            bool recursive)
        {
            Regex includePattern = new Regex(
                "^(?:" + includeRegex + ")$",
                RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            SearchOption option = recursive ? SearchOption.AllDirectories : SearchOption.TopDirectoryOnly;
            List<string> analyticalFiles;
            try
            {
                analyticalFiles = Directory.EnumerateFiles(inputDirectory, "*", option)
                    .Where(delegate(string path)
                    {
                        return SupportedImageExtensions.Contains(Path.GetExtension(path)) &&
                               includePattern.IsMatch(Path.GetFullPath(path)) &&
                               !Regex.IsMatch(
                                   Path.GetFileName(path),
                                   @"^Map_A\d+\.(oir|oib|oif)$",
                                   RegexOptions.IgnoreCase);
                    })
                    .OrderBy(delegate(string path) { return path; }, StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
            catch (Exception ex)
            {
                throw new InvalidOperationException(
                    "Automatic panel detection could not scan the input folder:\r\n" + ex.Message);
            }

            if (analyticalFiles.Count == 0)
                throw new InvalidOperationException(
                    "Automatic panel detection found no matching analytical image files. " +
                    "Check the filename filter and subfolder option, or select the panel manually.");

            PanelDetectionResult result = new PanelDetectionResult();
            result.AnalyticalImageCount = analyticalFiles.Count;
            List<string> unknown = new List<string>();
            SamplesheetPanelAssignments samplesheet =
                LoadSamplesheetPanels(inputDirectory);
            string fullRoot = Path.GetFullPath(inputDirectory)
                .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) +
                Path.DirectorySeparatorChar;
            foreach (string path in analyticalFiles)
            {
                string fullPath = Path.GetFullPath(path);
                if (!fullPath.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException(
                        "Automatic panel detection found a path outside the selected input folder: " + fullPath);
                string relative = fullPath.Substring(fullRoot.Length).Replace('\\', '/');
                string panel;
                if (!samplesheet.ByRelativePath.TryGetValue(relative, out panel) &&
                    !samplesheet.ByFilename.TryGetValue(Path.GetFileName(path), out panel))
                {
                    panel = InferBuiltInPanelFromPath(inputDirectory, path);
                }
                if (panel == null)
                {
                    unknown.Add(path);
                    continue;
                }
                string canonicalPanel = PanelDescriptions.Keys.FirstOrDefault(
                    delegate(string key)
                    {
                        return string.Equals(key, panel, StringComparison.OrdinalIgnoreCase);
                    });
                if (canonicalPanel == null)
                {
                    throw new InvalidOperationException(
                        "AUTO found panel '" + panel + "' for " + relative +
                        ", but that panel is not built in. Select a validated custom panel " +
                        "or use its samplesheet outside AUTO mode.");
                }
                panel = canonicalPanel;
                result.PanelByRelativePath[relative] = panel;
                int count;
                result.PanelCounts.TryGetValue(panel, out count);
                result.PanelCounts[panel] = count + 1;
            }

            if (unknown.Count > 0)
            {
                string examples = string.Join(
                    "\r\n",
                    unknown.Take(3).Select(delegate(string path) { return "• " + Path.GetFileName(path); }));
                throw new InvalidOperationException(
                    "Automatic panel detection could not identify " + unknown.Count +
                    " of " + analyticalFiles.Count + " matching image(s):\r\n" + examples +
                    "\r\n\r\nChoose the panel manually or narrow the filename filter. " +
                    "No image was assigned by color alone.");
            }
            result.FallbackPanel = result.PanelCounts
                .OrderByDescending(delegate(KeyValuePair<string, int> pair) { return pair.Value; })
                .ThenBy(delegate(KeyValuePair<string, int> pair) { return pair.Key; },
                        StringComparer.OrdinalIgnoreCase)
                .First().Key;
            return result;
        }

        private static SamplesheetPanelAssignments LoadSamplesheetPanels(
            string inputDirectory)
        {
            SamplesheetPanelAssignments assignments =
                new SamplesheetPanelAssignments();
            string path = Path.Combine(inputDirectory, "samplesheet.csv");
            if (!File.Exists(path))
                return assignments;

            string[] lines = File.ReadAllLines(path, Encoding.UTF8);
            if (lines.Length == 0)
                return assignments;
            List<string> header = ParseCsvLine(lines[0]);
            if (header.Count > 0)
                header[0] = header[0].TrimStart('\uFEFF');
            int filenameIndex = header.FindIndex(
                delegate(string value)
                {
                    return string.Equals(value.Trim(), "filename",
                                         StringComparison.OrdinalIgnoreCase);
                });
            int relativeIndex = header.FindIndex(
                delegate(string value)
                {
                    return string.Equals(value.Trim(), "relative_path",
                                         StringComparison.OrdinalIgnoreCase);
                });
            int panelIndex = header.FindIndex(
                delegate(string value)
                {
                    return string.Equals(value.Trim(), "panel",
                                         StringComparison.OrdinalIgnoreCase);
                });
            if (panelIndex < 0 || (filenameIndex < 0 && relativeIndex < 0))
                return assignments;

            HashSet<string> duplicateFilenames =
                new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            for (int lineIndex = 1; lineIndex < lines.Length; lineIndex++)
            {
                string line = lines[lineIndex].Trim();
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                    continue;
                List<string> values = ParseCsvLine(lines[lineIndex]);
                string panel = panelIndex < values.Count ? values[panelIndex].Trim() : "";
                if (panel.Length == 0 ||
                    string.Equals(panel, "NA", StringComparison.OrdinalIgnoreCase))
                    continue;
                string relative = relativeIndex >= 0 && relativeIndex < values.Count
                    ? values[relativeIndex].Trim().Replace('\\', '/')
                    : "";
                if (relative.Length > 0 &&
                    !string.Equals(relative, "NA", StringComparison.OrdinalIgnoreCase))
                {
                    if (assignments.ByRelativePath.ContainsKey(relative))
                        throw new InvalidOperationException(
                            "samplesheet.csv repeats relative_path '" + relative + "'.");
                    assignments.ByRelativePath[relative] = panel;
                }

                string filename = filenameIndex >= 0 && filenameIndex < values.Count
                    ? values[filenameIndex].Trim()
                    : "";
                if (filename.Length == 0 ||
                    string.Equals(filename, "NA", StringComparison.OrdinalIgnoreCase))
                    continue;
                if (assignments.ByFilename.ContainsKey(filename))
                {
                    assignments.ByFilename.Remove(filename);
                    duplicateFilenames.Add(filename);
                }
                else if (!duplicateFilenames.Contains(filename))
                {
                    assignments.ByFilename[filename] = panel;
                }
            }
            return assignments;
        }

        private static List<string> ParseCsvLine(string line)
        {
            List<string> values = new List<string>();
            StringBuilder field = new StringBuilder();
            bool quoted = false;
            for (int index = 0; index < (line ?? "").Length; index++)
            {
                char value = line[index];
                if (value == '"')
                {
                    if (quoted && index + 1 < line.Length && line[index + 1] == '"')
                    {
                        field.Append('"');
                        index++;
                    }
                    else
                    {
                        quoted = !quoted;
                    }
                }
                else if (value == ',' && !quoted)
                {
                    values.Add(field.ToString());
                    field.Length = 0;
                }
                else
                {
                    field.Append(value);
                }
            }
            if (quoted)
                throw new InvalidOperationException("Unclosed quoted field in samplesheet.csv.");
            values.Add(field.ToString());
            return values;
        }

        internal static string WriteAutoPanelMap(
            PanelDetectionResult detection,
            string runtimeDirectory)
        {
            string directory = Path.Combine(runtimeDirectory, "auto_panel_maps");
            Directory.CreateDirectory(directory);
            string path = Path.Combine(
                directory,
                "panel_map_" + Guid.NewGuid().ToString("N") + ".csv");
            StringBuilder csv = new StringBuilder();
            csv.AppendLine("relative_path,panel");
            foreach (KeyValuePair<string, string> assignment in
                     detection.PanelByRelativePath.OrderBy(
                         delegate(KeyValuePair<string, string> pair) { return pair.Key; },
                         StringComparer.OrdinalIgnoreCase))
            {
                csv.Append(EscapeCsv(assignment.Key))
                   .Append(',')
                   .Append(EscapeCsv(assignment.Value))
                   .AppendLine();
            }
            File.WriteAllText(path, csv.ToString(), new UTF8Encoding(false));
            return path;
        }

        private static string EscapeCsv(string value)
        {
            string text = value ?? "";
            if (text.IndexOfAny(new char[] { ',', '"', '\r', '\n' }) >= 0)
                return "\"" + text.Replace("\"", "\"\"") + "\"";
            return text;
        }

        private static void DeleteTemporaryPanelMap(RunConfiguration config)
        {
            if (config == null || string.IsNullOrWhiteSpace(config.AutoPanelMapPath))
                return;
            try
            {
                if (File.Exists(config.AutoPanelMapPath))
                    File.Delete(config.AutoPanelMapPath);
            }
            catch
            {
                // The generated map contains paths and panel keys only. A stale
                // cache file must never change the completed analysis status.
            }
        }

        private void UpdateAdvancedVisibility()
        {
            if (advancedGroup == null || showAdvancedBox == null)
                return;
            bool hasSavedAdvanced =
                (panelConfigBox != null && !string.IsNullOrWhiteSpace(panelConfigBox.Text)) ||
                (advancedBox != null && !string.IsNullOrWhiteSpace(advancedBox.Text));
            if (hasSavedAdvanced && !showAdvancedBox.Checked)
                showAdvancedBox.Checked = true;
            advancedGroup.Visible = showAdvancedBox.Checked;
        }

        private void ShowFirstTimeHelp()
        {
            MessageBox.Show(
                this,
                "1. Original image folder: choose unedited microscope files.\r\n\r\n" +
                "2. Fiji: choose the Fiji folder or executable. The launcher automatically selects ARM64 or x64.\r\n\r\n" +
                "3. Output parent folder: choose where a new timestamped result folder should be created.\r\n\r\n" +
                "4. Staining panel: leave AUTO selected when marker names are present in the image or folder names. " +
                "AUTO allocates every recognized image independently, so different built-in panels and marker subsets may share one batch. " +
                "Unknown images stop rather than being guessed. Each allocation uses a preset's fixed acquisition channel order; AUTO does not discover marker identity from fluorescence colors. " +
                "Use a manual/custom choice when naming is insufficient or channel order differs.\r\n\r\n" +
                "5. For a first pilot, set Image limit to 1. Leave the other recommended settings unchanged.\r\n\r\n" +
                "6. Click Create visual merge panels to generate the merged marker presentation for every image in the configured run scope. " +
                "This visual-only operation performs no segmentation, cell calls, masks, CSV, Excel, or manifest export.\r\n\r\n" +
                "7. When the marker allocation and merge presentation are correct, click Review and run analysis. " +
                "The full run creates a visual merge panel for every analyzed image as well as the quantitative results. " +
                "Watch the progress bar and status text; a successful analysis enables Open summary Excel.\r\n\r\n" +
                "Always inspect the QC overlays. The software quantifies fluorescence patterns for research and does not make a diagnosis.",
                "First-time guide",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }

        private void RestoreRecommendedSettings()
        {
            SelectChoice(panelBox, "AUTO");
            SelectChoice(segmenterBox, "classic");
            SelectChoice(projectionBox, "layer_aware");
            singlePlaneBox.Value = -1;
            SelectChoice(tissueModeBox, "auto");
            SelectChoice(compartmentModeBox, "required");
            SelectChoice(wholeCompartmentBox, "unassigned");
            recursiveBox.Checked = true;
            includeRegexBox.Text = ".*";
            maxImagesBox.Value = 0;
            MessageBox.Show(
                this,
                "Recommended processing settings were restored. Panel selection is now AUTO; folder locations were left unchanged. AUTO still requires you to confirm the detected marker names and channel order before the run starts.",
                "Recommended settings restored",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
        }

        private void BrowseFolder(TextBox target)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.ShowNewFolderButton = true;
                if (Directory.Exists(target.Text))
                    dialog.SelectedPath = target.Text;
                if (dialog.ShowDialog(this) == DialogResult.OK)
                    target.Text = dialog.SelectedPath;
            }
        }

        private void BrowseFiji(TextBox target)
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "Select the Fiji or ImageJ executable";
                dialog.Filter = "Fiji/ImageJ executable (*.exe)|*.exe|All files (*.*)|*.*";
                dialog.CheckFileExists = true;
                string current = target.Text.Trim();
                if (File.Exists(current))
                    dialog.FileName = current;
                else if (Directory.Exists(current))
                    dialog.InitialDirectory = current;
                if (dialog.ShowDialog(this) == DialogResult.OK)
                    target.Text = dialog.FileName;
            }
        }

        private void BrowseJsonFile(TextBox target)
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "Select a study panel JSON file";
                dialog.Filter = "JSON files (*.json)|*.json|All files (*.*)|*.*";
                dialog.CheckFileExists = true;
                if (File.Exists(target.Text))
                    dialog.FileName = target.Text;
                if (dialog.ShowDialog(this) == DialogResult.OK)
                    target.Text = dialog.FileName;
            }
        }

        private void ApplyFirstRunDefaults()
        {
            if (string.IsNullOrWhiteSpace(fijiBox.Text))
            {
                string defaultFiji = @"X:\Fiji";
                if (Directory.Exists(defaultFiji))
                    fijiBox.Text = defaultFiji;
            }
            if (string.IsNullOrWhiteSpace(outputBaseBox.Text))
            {
                outputBaseBox.Text = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
                    "IFQuantResults");
            }
        }

        private void StartAnalysis()
        {
            StartFijiRun(false);
        }

        private void StartDisplayPreview()
        {
            StartFijiRun(true);
        }

        private void StartFijiRun(bool previewOnly)
        {
            RunConfiguration config;
            try
            {
                config = ReadAndValidateConfiguration(previewOnly);
            }
            catch (Exception ex)
            {
                MessageBox.Show(
                    this,
                    ex.Message,
                    previewOnly ? "Cannot create visual merge panels" : "Cannot start analysis",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return;
            }

            if (!(previewOnly ? ConfirmDisplayPreview(config) : ConfirmRun(config)))
                return;

            Directory.CreateDirectory(config.OutputDirectory);
            SaveSettings();
            logBox.Clear();
            lastRunDirectory = config.OutputDirectory;
            lastSummaryPath = null;
            cancellationRequested = false;
            runningPreview = previewOnly;
            openOutputButton.Enabled = true;
            openSummaryButton.Enabled = false;
            SetProgressPreparing();

            AppendLog("IF Quant Launcher " + Assembly.GetExecutingAssembly().GetName().Version);
            AppendLog("Windows architecture: " + GetWindowsArchitecture());
            AppendLog("Input:  " + config.InputDirectory);
            AppendLog("Output: " + config.OutputDirectory);
            AppendLog("Fiji:   " + config.FijiExecutable);
            AppendLog(config.PanelWasAutoDetected
                ? "Panels: " + string.Join(
                    ", ",
                    config.AutoPanelCounts.OrderBy(
                        delegate(KeyValuePair<string, int> pair) { return pair.Key; },
                        StringComparer.OrdinalIgnoreCase)
                    .Select(delegate(KeyValuePair<string, int> pair)
                    {
                        return pair.Key + "=" + pair.Value;
                    }))
                : "Panel:  " + config.Environment["IFQ_PANEL"]);
            AppendLog(previewOnly
                ? "Mode:   visual merge panels only; configured image scope; no quantification"
                : "Mode:   full analysis with a visual merge panel for every analyzed image");
            AppendLog("Starting Fiji...");

            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = config.FijiExecutable;
            psi.Arguments = "--headless --console --run " + QuoteArgument(config.ScriptPath);
            psi.WorkingDirectory = config.RuntimeDirectory;
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;

            ClearIfqEnvironment(psi.EnvironmentVariables);
            foreach (KeyValuePair<string, string> item in config.Environment)
                psi.EnvironmentVariables[item.Key] = item.Value;

            Process process = new Process();
            process.StartInfo = psi;
            process.EnableRaisingEvents = true;
            process.OutputDataReceived += delegate(object sender, DataReceivedEventArgs e)
            {
                if (!string.IsNullOrEmpty(e.Data))
                    HandleFijiLine(e.Data, false);
            };
            process.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs e)
            {
                if (!string.IsNullOrEmpty(e.Data))
                    HandleFijiLine(e.Data, true);
            };

            try
            {
                lock (processLock)
                {
                    runningProcess = process;
                    process.Start();
                }
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
            }
            catch (Exception ex)
            {
                lock (processLock) { runningProcess = null; }
                if (!config.PreviewOnly)
                    WriteLauncherRecord(config, -1, "failed_to_start: " + ex.Message);
                DeleteTemporaryPanelMap(config);
                MessageBox.Show(this, ex.Message, "Fiji could not start", MessageBoxButtons.OK, MessageBoxIcon.Error);
                SetRunningState(false);
                SetProgressTerminal("Fiji could not start", false, false);
                return;
            }

            SetRunningState(true);
            ThreadPool.QueueUserWorkItem(delegate
            {
                int exitCode = -1;
                string waitError = null;
                try
                {
                    process.WaitForExit();
                    exitCode = process.ExitCode;
                }
                catch (Exception ex)
                {
                    waitError = ex.Message;
                }
                finally
                {
                    lock (processLock)
                    {
                        if (ReferenceEquals(runningProcess, process))
                            runningProcess = null;
                    }
                }

                BeginInvoke(new Action(delegate
                {
                    if (config.PreviewOnly)
                        FinishDisplayPreview(config, exitCode, waitError);
                    else
                        FinishAnalysis(config, exitCode, waitError);
                }));
            });
        }

        private string DescribePanelAllocation(RunConfiguration config)
        {
            if (config.PanelWasAutoDetected && config.AutoPanelCounts != null)
            {
                StringBuilder text = new StringBuilder();
                text.Append("AUTO per-image allocation:\r\n");
                foreach (KeyValuePair<string, int> pair in
                         config.AutoPanelCounts.OrderBy(
                             delegate(KeyValuePair<string, int> item) { return item.Key; },
                             StringComparer.OrdinalIgnoreCase))
                {
                    string description;
                    if (!PanelDescriptions.TryGetValue(pair.Key, out description))
                        description = pair.Key;
                    text.Append("  ").Append(pair.Key).Append(": ")
                        .Append(pair.Value).Append(" image(s) — ")
                        .Append(description).Append("\r\n");
                }
                return text.ToString().TrimEnd();
            }

            string panelKey = config.Environment["IFQ_PANEL"];
            string panelDescription;
            if (!PanelDescriptions.TryGetValue(panelKey, out panelDescription))
                panelDescription = "Custom validated panel: " + panelKey;
            return panelDescription;
        }

        private bool ConfirmDisplayPreview(RunConfiguration config)
        {
            DialogResult result = MessageBox.Show(
                this,
                "Create visual merge panels only?\r\n\r\n" +
                "Input:\r\n" + config.InputDirectory + "\r\n\r\n" +
                "Detected/selected marker-channel allocation:\r\n" +
                DescribePanelAllocation(config) + "\r\n\r\n" +
                "Z-stack handling: " + config.Environment["IFQ_PROJECTION"] + "\r\n" +
                "Images: " + (config.Environment["IFQ_MAX_IMAGES"] == "0"
                    ? "all matching analytical images"
                    : "up to " + config.Environment["IFQ_MAX_IMAGES"] + " matching analytical image(s)") + "\r\n\r\n" +
                "Output folder:\r\n" + config.OutputDirectory + "\r\n\r\n" +
                "This operation writes the primary visual merge panel and supporting enhanced channel PNGs. " +
                "It will not run segmentation or create cell counts, masks, CSV, Excel, " +
                "parameter files, Z-profile tables, or an analysis manifest.\r\n\r\n" +
                "Continue?",
                "Review visual merge panel generation",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Information);
            return result == DialogResult.OK;
        }

        private bool ConfirmRun(RunConfiguration config)
        {
            string panelKey = config.Environment["IFQ_PANEL"];
            string limit = config.Environment["IFQ_MAX_IMAGES"] == "0"
                ? "all matching images"
                : "up to " + config.Environment["IFQ_MAX_IMAGES"] + " image(s)";
            bool includesPanelT = string.Equals(panelKey, "T", StringComparison.OrdinalIgnoreCase) ||
                (config.AutoPanelCounts != null && config.AutoPanelCounts.ContainsKey("T"));
            string warning = includesPanelT
                ? "\r\n\r\nWARNING: Panel T is a plumbing test and its positivity results are not biologically meaningful."
                : "";
            bool includesPanelE = string.Equals(panelKey, "E", StringComparison.OrdinalIgnoreCase) ||
                (config.AutoPanelCounts != null && config.AutoPanelCounts.ContainsKey("E"));
            if (includesPanelE &&
                !string.Equals(config.Environment["IFQ_WHOLE_FIELD_COMPARTMENT"], "airway", StringComparison.OrdinalIgnoreCase))
            {
                warning +=
                    "\r\n\r\nAcTub context note: without an independently assigned airway ROI, " +
                    "only nuclei meeting the strict apical ciliary-component rule can be exploratory positive. " +
                    "All other AcTub calls remain indeterminate, not negative.";
            }

            DialogResult result = MessageBox.Show(
                this,
                "Please confirm this analysis:\r\n\r\n" +
                "Input:\r\n" + config.InputDirectory + "\r\n\r\n" +
                "Marker-channel allocation:\r\n" +
                DescribePanelAllocation(config) + "\r\n\r\n" +
                (config.PanelWasAutoDetected
                    ? "Panel selection: automatically detected from " +
                      config.PanelDetectionImageCount + " matching analytical image path(s).\r\n" +
                      "Each image will use its allocated panel independently. " +
                      "Confirm that every stated acquisition channel order is correct.\r\n\r\n"
                    : "Panel selection: manual/custom. Confirm the acquisition channel order.\r\n\r\n") +
                "Nucleus detection: " + config.Environment["IFQ_SEGMENTER"] + "\r\n" +
                "Z-stack handling: " + config.Environment["IFQ_PROJECTION"] + "\r\n" +
                "Enhanced marker views: exported for every analyzed image (display-only; not quantified)\r\n" +
                "Files: " + limit + "\r\n\r\n" +
                "New result folder:\r\n" + config.OutputDirectory + warning +
                "\r\n\r\nStart Fiji analysis now?",
                "Review analysis settings",
                MessageBoxButtons.OKCancel,
                MessageBoxIcon.Question);
            return result == DialogResult.OK;
        }

        private RunConfiguration ReadAndValidateConfiguration(bool previewOnly)
        {
            string input = inputBox.Text.Trim();
            if (!Directory.Exists(input))
                throw new InvalidOperationException("The original image folder does not exist:\r\n" + input);

            string fiji = ResolveFijiExecutable(fijiBox.Text.Trim());
            if (fiji == null)
                throw new InvalidOperationException(
                    "Could not find a Fiji/ImageJ executable. Select the executable itself or its installation folder.");

            string outputBase = outputBaseBox.Text.Trim();
            if (outputBase.Length == 0)
                throw new InvalidOperationException("Choose an output parent folder.");
            Directory.CreateDirectory(outputBase);

            string includeRegex = includeRegexBox.Text.Trim();
            if (includeRegex.Length == 0)
                includeRegex = ".*";
            try { new Regex(includeRegex); }
            catch (Exception ex)
            {
                throw new InvalidOperationException("The filename include regex is invalid:\r\n" + ex.Message);
            }

            string panelConfig = panelConfigBox.Text.Trim();
            if (panelConfig.Length > 0 && !File.Exists(panelConfig))
                throw new InvalidOperationException("The custom panel JSON does not exist:\r\n" + panelConfig);
            string panelKey = ChoiceKey(panelBox);
            if (panelKey.Length == 0)
                throw new InvalidOperationException("Choose the staining panel that matches the marker names and acquisition channel order.");
            bool panelWasAutoDetected =
                string.Equals(panelKey, "AUTO", StringComparison.OrdinalIgnoreCase);
            int panelDetectionImageCount = 0;
            PanelDetectionResult autoDetection = null;
            if (panelWasAutoDetected)
            {
                if (panelConfig.Length > 0)
                    throw new InvalidOperationException(
                        "AUTO detects built-in panels only. Choose the custom panel key explicitly when a custom panel JSON is selected.");
                autoDetection = DetectBuiltInPanels(
                    input, includeRegex, recursiveBox.Checked);
                panelDetectionImageCount = autoDetection.AnalyticalImageCount;
                panelKey = autoDetection.FallbackPanel;
            }
            string builtInPanelKey = PanelDescriptions.Keys.FirstOrDefault(
                delegate(string key) { return string.Equals(key, panelKey, StringComparison.OrdinalIgnoreCase); });
            if (builtInPanelKey != null)
                panelKey = builtInPanelKey;
            if (!PanelDescriptions.ContainsKey(panelKey) && panelConfig.Length == 0)
                throw new InvalidOperationException(
                    "Panel '" + panelKey + "' is not built in. Select its validated custom panel JSON under Advanced study options.");
            bool includesS2 = string.Equals(panelKey, "S2", StringComparison.OrdinalIgnoreCase) ||
                (autoDetection != null && autoDetection.PanelCounts.ContainsKey("S2"));
            if (includesS2 &&
                !string.Equals(ChoiceKey(projectionBox), "single", StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(ChoiceKey(projectionBox), "layer_aware", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "Panel S2 contains YAP nuclear-to-cytoplasmic analysis and requires either Z-stack handling = single " +
                    "or layer_aware, which assigns YAP a single-plane policy. Confirm the intended plane in the saved Z profile.");

            Dictionary<string, string> advanced = ParseAdvancedEnvironment(advancedBox.Text);
            RuntimePaths runtime = RuntimeBundle.EnsureExtracted();
            string autoPanelMapPath = autoDetection == null ? null :
                WriteAutoPanelMap(autoDetection, runtime.RuntimeDirectory);

            string runStem = SanitizeFileName(runNameBox.Text.Trim());
            if (runStem.Length == 0)
                runStem = "IFQ_run";
            if (previewOnly)
                runStem += "_visual_merge_panels";
            string timestamp = DateTime.Now.ToString("yyyyMMdd_HHmmss", CultureInfo.InvariantCulture);
            string outputDirectory = MakeUniqueDirectory(Path.Combine(outputBase, runStem + "_" + timestamp));

            Dictionary<string, string> env = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            env["IFQ_INPUT_DIR"] = Path.GetFullPath(input);
            env["IFQ_OUTPUT_DIR"] = outputDirectory;
            env["IFQ_PANEL"] = panelKey;
            env["IFQ_MARKER_REGISTRY"] = runtime.RegistryPath;
            if (!string.IsNullOrWhiteSpace(autoPanelMapPath))
                env["IFQ_PANEL_MAP_PATH"] = autoPanelMapPath;
            if (panelConfig.Length > 0)
                env["IFQ_PANEL_CONFIG"] = Path.GetFullPath(panelConfig);
            env["IFQ_RECURSIVE"] = recursiveBox.Checked ? "true" : "false";
            env["IFQ_INCLUDE_REGEX"] = includeRegex;
            env["IFQ_MAX_IMAGES"] =
                Decimal.ToInt32(maxImagesBox.Value).ToString(CultureInfo.InvariantCulture);
            env["IFQ_SEGMENTER"] = ChoiceKey(segmenterBox);
            env["IFQ_PROJECTION"] = ChoiceKey(projectionBox);
            env["IFQ_SINGLE_PLANE"] = Decimal.ToInt32(singlePlaneBox.Value).ToString(CultureInfo.InvariantCulture);
            // Both operations export a disposable visualization branch. In a
            // full run, quantification still consumes only the untouched
            // calibrated projections; the enhanced 8-bit PNGs are never fed
            // back into segmentation, thresholds, masks, or marker calls.
            env["IFQ_EXPORT_DISPLAY_CHANNELS"] =
                DisplayChannelExportSetting(previewOnly);
            env["IFQ_DISPLAY_PREVIEW_ONLY"] = previewOnly ? "true" : "false";
            env["IFQ_TISSUE_MODE"] = ChoiceKey(tissueModeBox);
            env["IFQ_COMPARTMENT_MODE"] = ChoiceKey(compartmentModeBox);
            env["IFQ_WHOLE_FIELD_COMPARTMENT"] = ChoiceKey(wholeCompartmentBox);
            env["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "false";
            env["IFQ_MORPHOLOGY_PRIMARY"] = "true";

            foreach (KeyValuePair<string, string> item in advanced)
                env[item.Key] = item.Value;

            RunConfiguration config = new RunConfiguration();
            config.InputDirectory = Path.GetFullPath(input);
            config.OutputDirectory = outputDirectory;
            config.FijiExecutable = fiji;
            config.RuntimeDirectory = runtime.RuntimeDirectory;
            config.ScriptPath = runtime.ScriptPath;
            config.RegistryPath = runtime.RegistryPath;
            config.Environment = env;
            config.PanelWasAutoDetected = panelWasAutoDetected;
            config.PanelDetectionImageCount = panelDetectionImageCount;
            config.AutoPanelCounts = autoDetection == null ? null : autoDetection.PanelCounts;
            config.AutoPanelMapPath = autoPanelMapPath;
            config.PreviewOnly = previewOnly;
            return config;
        }

        internal static string DisplayChannelExportSetting(bool previewOnly)
        {
            // Kept as a mode-aware policy seam so the packaged self-test can
            // verify that both launcher operations request companion views.
            return "true";
        }

        private static Dictionary<string, string> ParseAdvancedEnvironment(string text)
        {
            Dictionary<string, string> values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            string[] lines = (text ?? "").Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
            for (int index = 0; index < lines.Length; index++)
            {
                string line = lines[index].Trim();
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal))
                    continue;
                int equals = line.IndexOf('=');
                if (equals <= 0)
                    throw new InvalidOperationException(
                        "Advanced setting line " + (index + 1) + " must use KEY=VALUE.");
                string key = line.Substring(0, equals).Trim().ToUpperInvariant();
                string value = line.Substring(equals + 1).Trim();
                if (!Regex.IsMatch(key, @"^IFQ_[A-Z0-9_]+$"))
                    throw new InvalidOperationException(
                        "Advanced setting line " + (index + 1) + " has an invalid IFQ key: " + key);
                if (ProtectedEnvironmentKeys.Contains(key))
                    throw new InvalidOperationException(
                        key + " is controlled by the launcher interface and cannot be overridden in Advanced settings.");
                if (value.Length == 0)
                    throw new InvalidOperationException(key + " cannot have an empty value.");
                values[key] = value;
            }
            return values;
        }

        private static void ClearIfqEnvironment(StringDictionary environment)
        {
            List<string> stale = new List<string>();
            foreach (string key in environment.Keys)
            {
                if (key != null && key.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase))
                    stale.Add(key);
            }
            foreach (string key in stale)
                environment.Remove(key);
        }

        private void HandleFijiLine(string line, bool isError)
        {
            AppendLog(isError ? "ERROR: " + line : line);

            Match progress = Regex.Match(
                line,
                @"\[IFQ_PROGRESS\]\s+(\d+)\s*/\s*(\d+)\s*(.*)",
                RegexOptions.IgnoreCase);
            if (progress.Success)
            {
                int current;
                int total;
                if (Int32.TryParse(progress.Groups[1].Value, out current) &&
                    Int32.TryParse(progress.Groups[2].Value, out total))
                {
                    UpdateImageProgress(current, total, progress.Groups[3].Value.Trim());
                }
            }
            else if (line.IndexOf("DONE. Wrote run_summary.csv", StringComparison.OrdinalIgnoreCase) >= 0 ||
                     line.IndexOf("VISUAL MERGE PANELS COMPLETE", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                UpdateFinalizingProgress();
            }
        }

        private void SetProgressPreparing()
        {
            progressBar.Style = ProgressBarStyle.Marquee;
            progressBar.MarqueeAnimationSpeed = 28;
            progressDetailLabel.Text = runningPreview
                ? "Preparing Fiji to create visual merge panels..."
                : "Preparing the embedded pipeline and starting Fiji...";
            statusLabel.Text = runningPreview
                ? "Starting — visual merge panels are being prepared"
                : "Starting — Fiji is being prepared";
            statusLabel.ForeColor = Color.DarkBlue;
        }

        private void UpdateImageProgress(int current, int total, string fileName)
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action<int, int, string>(UpdateImageProgress), current, total, fileName);
                return;
            }
            total = Math.Max(1, total);
            current = Math.Max(1, Math.Min(total, current));
            progressBar.MarqueeAnimationSpeed = 0;
            progressBar.Style = ProgressBarStyle.Continuous;
            progressBar.Minimum = 0;
            progressBar.Maximum = total;
            progressBar.Value = Math.Max(0, current - 1);
            statusLabel.Text = runningPreview
                ? "Merging — image " + current + " of " + total
                : "Running — processing image " + current + " of " + total;
            statusLabel.ForeColor = Color.DarkBlue;
            progressDetailLabel.Text = fileName.Length > 0
                ? (runningPreview ? "Currently creating a visual merge panel for: " : "Currently analyzing: ") + fileName
                : (runningPreview ? "Fiji visual merge panel generation is ongoing." : "Fiji analysis is ongoing.");
        }

        private void UpdateFinalizingProgress()
        {
            if (InvokeRequired)
            {
                BeginInvoke(new Action(UpdateFinalizingProgress));
                return;
            }
            progressBar.MarqueeAnimationSpeed = 25;
            progressBar.Style = ProgressBarStyle.Marquee;
            statusLabel.Text = runningPreview
                ? "Finalizing — checking visual merge panels"
                : "Finalizing — writing summary and run record";
            statusLabel.ForeColor = Color.DarkBlue;
            progressDetailLabel.Text = runningPreview
                ? "Visual merge generation finished; checking PNG outputs."
                : "Image processing finished; checking required output files.";
        }

        private void SetProgressTerminal(string detail, bool succeeded, bool cancelled)
        {
            progressBar.MarqueeAnimationSpeed = 0;
            progressBar.Style = ProgressBarStyle.Continuous;
            progressBar.Minimum = 0;
            progressBar.Maximum = 100;
            progressBar.Value = succeeded ? 100 : 0;
            if (cancelled)
            {
                statusLabel.Text = "Cancelled — Fiji was terminated";
                statusLabel.ForeColor = Color.DarkOrange;
            }
            else if (succeeded)
            {
                statusLabel.Text = "Complete — summary and QC outputs are ready";
                statusLabel.ForeColor = Color.DarkGreen;
            }
            else
            {
                statusLabel.Text = "Stopped with a problem — review the log and manifest";
                statusLabel.ForeColor = Color.DarkRed;
            }
            progressDetailLabel.Text = detail;
        }

        private void FinishDisplayPreview(RunConfiguration config, int exitCode, string waitError)
        {
            SetRunningState(false);
            string[] allFiles = Directory.Exists(config.OutputDirectory)
                ? Directory.GetFiles(config.OutputDirectory, "*", SearchOption.AllDirectories)
                : new string[0];
            string[] previewPngs = allFiles.Where(delegate(string path)
            {
                string name = Path.GetFileName(path);
                return path.EndsWith(".png", StringComparison.OrdinalIgnoreCase) &&
                       (name.IndexOf("__DISPLAY_ONLY__", StringComparison.OrdinalIgnoreCase) >= 0 ||
                        name.IndexOf("__VISUAL_MERGE_PANEL__", StringComparison.OrdinalIgnoreCase) >= 0);
            }).ToArray();
            int mergedCount = previewPngs.Count(delegate(string path)
            {
                return Path.GetFileName(path).EndsWith(
                    "__VISUAL_MERGE_PANEL__merged_enhanced.png",
                    StringComparison.OrdinalIgnoreCase);
            });
            string[] unexpectedFiles = allFiles.Where(delegate(string path)
            {
                string name = Path.GetFileName(path);
                return !path.EndsWith(".png", StringComparison.OrdinalIgnoreCase) ||
                       (name.IndexOf("__DISPLAY_ONLY__", StringComparison.OrdinalIgnoreCase) < 0 &&
                        name.IndexOf("__VISUAL_MERGE_PANEL__", StringComparison.OrdinalIgnoreCase) < 0);
            }).ToArray();

            AppendLog("");
            AppendLog("Fiji visual merge panel exit code: " + exitCode);
            AppendLog("Visual merge/supporting PNGs: " + previewPngs.Length +
                      " across " + mergedCount + " source image(s).");
            if (unexpectedFiles.Length > 0)
                AppendLog("Unexpected non-visual files: " + unexpectedFiles.Length + ".");
            if (waitError != null)
                AppendLog("Process wait error: " + waitError);

            lastSummaryPath = null;
            openSummaryButton.Enabled = false;
            openOutputButton.Enabled = Directory.Exists(config.OutputDirectory);
            bool complete = exitCode == 0 && waitError == null &&
                            mergedCount > 0 &&
                            unexpectedFiles.Length == 0;
            if (cancellationRequested)
            {
                SetProgressTerminal(
                    "Visual merge panel generation was cancelled. Any PNGs already written remain available for inspection.",
                    false,
                    true);
            }
            else if (complete)
            {
                SetProgressTerminal(
                    "Created visual merge panels for " + mergedCount +
                    " image(s). No segmentation or quantitative outputs were generated.",
                    true,
                    false);
                statusLabel.Text = "Complete — visual merge panels are ready";
                progressDetailLabel.Text =
                    "Open the output folder to review the labeled per-channel and merged PNGs.";
            }
            else
            {
                SetProgressTerminal(
                    "Visual merge panel generation did not complete cleanly. Review the Fiji log; no output is valid for quantification.",
                    false,
                    false);
                statusLabel.Text = "Visual merge panels stopped with a problem — review the log";
            }
            DeleteTemporaryPanelMap(config);
            runningPreview = false;
        }

        private void FinishAnalysis(RunConfiguration config, int exitCode, string waitError)
        {
            SetRunningState(false);
            string manifestPath = Path.Combine(config.OutputDirectory, "run_manifest.json");
            string summaryCsvPath = Path.Combine(config.OutputDirectory, "run_summary.csv");
            string summaryWorkbookPath = Path.Combine(config.OutputDirectory, "run_summary.xlsx");
            string manifestStatus = "missing";
            string successCount = "?";
            string skippedCount = "?";
            string failureCount = "?";
            string outputFailureCount = "?";

            if (File.Exists(manifestPath))
            {
                try
                {
                    JavaScriptSerializer json = new JavaScriptSerializer();
                    Dictionary<string, object> manifest =
                        json.Deserialize<Dictionary<string, object>>(File.ReadAllText(manifestPath, Encoding.UTF8));
                    manifestStatus = GetDictionaryValue(manifest, "status", "unknown");
                    successCount = GetDictionaryValue(manifest, "success_count", "?");
                    skippedCount = GetDictionaryValue(manifest, "skipped_count", "0");
                    failureCount = GetDictionaryValue(manifest, "failure_count", "?");
                    outputFailureCount = GetDictionaryValue(manifest, "output_failure_count", "0");
                }
                catch (Exception ex)
                {
                    AppendLog("Could not read run manifest: " + ex.Message);
                    manifestStatus = "unreadable";
                }
            }

            WriteLauncherRecord(config, exitCode, manifestStatus);
            AppendLog("");
            AppendLog("Fiji exit code: " + exitCode);
            AppendLog("Manifest status: " + manifestStatus);
            AppendLog("Analytical images: " + successCount + " successful; " + failureCount + " failed.");
            AppendLog("Non-analysis acquisitions deliberately skipped: " + skippedCount + ".");
            if (!string.Equals(outputFailureCount, "0", StringComparison.OrdinalIgnoreCase))
                AppendLog("Output-generation failures: " + outputFailureCount + ".");

            if (waitError != null)
                AppendLog("Process wait error: " + waitError);

            lastSummaryPath = File.Exists(summaryWorkbookPath)
                ? summaryWorkbookPath
                : (File.Exists(summaryCsvPath) ? summaryCsvPath : null);
            openSummaryButton.Enabled = lastSummaryPath != null;
            openOutputButton.Enabled = Directory.Exists(config.OutputDirectory);

            bool complete = exitCode == 0 &&
                string.Equals(manifestStatus, "complete", StringComparison.OrdinalIgnoreCase) &&
                File.Exists(summaryCsvPath) &&
                File.Exists(summaryWorkbookPath);
            if (cancellationRequested)
            {
                SetProgressTerminal(
                    "Analysis was cancelled. Partial outputs are retained only for troubleshooting and must not be aggregated.",
                    false,
                    true);
            }
            else if (complete)
            {
                SetProgressTerminal(
                    "Finished successfully: " + successCount + " analytical image(s) processed, " +
                    skippedCount + " non-analysis acquisition(s) skipped, " + failureCount + " failed.",
                    true,
                    false);
                AppendLog("Excel summary: " + summaryWorkbookPath);
                AppendLog("Region-level CSV: " + summaryCsvPath);
            }
            else
            {
                SetProgressTerminal(
                    "Analysis terminated or was incomplete: " + successCount + " image(s) succeeded, " + failureCount +
                    " failed; " + skippedCount + " non-analysis acquisition(s) were skipped. " +
                    "Check the log below and run_manifest.json.",
                    false,
                    false);
            }
            DeleteTemporaryPanelMap(config);
        }

        private static string GetDictionaryValue(Dictionary<string, object> values, string key, string fallback)
        {
            object value;
            if (values != null && values.TryGetValue(key, out value) && value != null)
                return Convert.ToString(value, CultureInfo.InvariantCulture);
            return fallback;
        }

        private void SetRunningState(bool running)
        {
            runButton.Enabled = !running;
            previewButton.Enabled = !running;
            cancelButton.Enabled = running;
            if (running)
            {
                statusLabel.Text = runningPreview
                    ? "Running Fiji visual merge panel generation..."
                    : "Running Fiji analysis...";
                statusLabel.ForeColor = Color.DarkBlue;
            }
        }

        private void CancelRunningProcess()
        {
            Process process = null;
            lock (processLock)
            {
                process = runningProcess;
            }
            if (process == null)
                return;

            DialogResult result = MessageBox.Show(
                this,
                runningPreview
                    ? "Cancel visual merge panel generation? Any PNGs already written will remain in the output folder."
                    : "Cancel the running Fiji analysis? Partial outputs will be retained for diagnosis and must not be aggregated.",
                runningPreview ? "Cancel visual merge panels" : "Cancel analysis",
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning);
            if (result != DialogResult.Yes)
                return;

            cancellationRequested = true;
            progressBar.MarqueeAnimationSpeed = 22;
            progressBar.Style = ProgressBarStyle.Marquee;
            statusLabel.Text = "Cancelling — terminating Fiji";
            statusLabel.ForeColor = Color.DarkOrange;
            progressDetailLabel.Text = "Please wait while the Fiji process and its child processes close.";

            try
            {
                if (!process.HasExited)
                {
                    ProcessStartInfo taskKill = new ProcessStartInfo();
                    taskKill.FileName = "taskkill.exe";
                    taskKill.Arguments = "/PID " + process.Id + " /T /F";
                    taskKill.UseShellExecute = false;
                    taskKill.CreateNoWindow = true;
                    using (Process killer = Process.Start(taskKill))
                    {
                        killer.WaitForExit(10000);
                    }
                }
                AppendLog("Cancellation requested.");
            }
            catch (Exception ex)
            {
                AppendLog("Cancellation error: " + ex.Message);
                try { if (!process.HasExited) process.Kill(); } catch { }
            }
        }

        private void AppendLog(string message)
        {
            if (logBox.InvokeRequired)
            {
                logBox.BeginInvoke(new Action<string>(AppendLog), message);
                return;
            }
            logBox.AppendText((message ?? "") + Environment.NewLine);
        }

        private static void WriteLauncherRecord(RunConfiguration config, int exitCode, string status)
        {
            try
            {
                StringBuilder record = new StringBuilder();
                record.AppendLine("IF Quant Launcher run record");
                record.AppendLine("launcher_version=" + Assembly.GetExecutingAssembly().GetName().Version);
                record.AppendLine("recorded_at=" + DateTimeOffset.Now.ToString("o", CultureInfo.InvariantCulture));
                record.AppendLine("windows_architecture=" + GetWindowsArchitecture());
                record.AppendLine("fiji_executable=" + config.FijiExecutable);
                record.AppendLine("fiji_exit_code=" + exitCode);
                record.AppendLine("manifest_status=" + status);
                record.AppendLine("pipeline_sha256=" + ComputeSha256(config.ScriptPath));
                record.AppendLine("registry_sha256=" + ComputeSha256(config.RegistryPath));
                record.AppendLine();
                record.AppendLine("[environment]");
                foreach (KeyValuePair<string, string> item in config.Environment.OrderBy(delegate(KeyValuePair<string, string> pair) { return pair.Key; }))
                    record.AppendLine(item.Key + "=" + item.Value);
                File.WriteAllText(
                    Path.Combine(config.OutputDirectory, "launcher_run.txt"),
                    record.ToString(),
                    new UTF8Encoding(false));
            }
            catch
            {
                // The Fiji outputs remain authoritative if this convenience record cannot be written.
            }
        }

        private static string ComputeSha256(string path)
        {
            using (SHA256 algorithm = SHA256.Create())
            using (FileStream stream = File.OpenRead(path))
            {
                byte[] hash = algorithm.ComputeHash(stream);
                StringBuilder text = new StringBuilder(hash.Length * 2);
                foreach (byte value in hash)
                    text.Append(value.ToString("x2", CultureInfo.InvariantCulture));
                return text.ToString();
            }
        }

        internal static string ResolveFijiExecutable(string path)
        {
            if (File.Exists(path) && path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                return Path.GetFullPath(path);
            if (!Directory.Exists(path))
                return null;

            string architecture = GetWindowsArchitecture();
            string[] preferred;
            if (architecture == "ARM64")
            {
                preferred = new string[]
                {
                    "fiji-windows-arm64.exe",
                    "fiji-windows-x64.exe",
                    "ImageJ-win64.exe",
                    "fiji-windows.exe",
                    "ImageJ.exe"
                };
            }
            else
            {
                preferred = new string[]
                {
                    "fiji-windows-x64.exe",
                    "ImageJ-win64.exe",
                    "fiji-windows.exe",
                    "ImageJ.exe",
                    "fiji-windows-arm64.exe"
                };
            }
            foreach (string name in preferred)
            {
                string candidate = Path.Combine(path, name);
                if (File.Exists(candidate))
                    return candidate;
            }

            string[] executables = Directory.GetFiles(path, "*.exe", SearchOption.TopDirectoryOnly);
            foreach (string candidate in executables.OrderBy(delegate(string value) { return value; }))
            {
                string name = Path.GetFileName(candidate);
                if (name.IndexOf("fiji", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    name.IndexOf("imagej", StringComparison.OrdinalIgnoreCase) >= 0)
                    return candidate;
            }
            return null;
        }

        internal static string GetWindowsArchitecture()
        {
            string architecture = Environment.GetEnvironmentVariable("PROCESSOR_ARCHITEW6432");
            if (string.IsNullOrWhiteSpace(architecture))
                architecture = Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE");
            architecture = (architecture ?? "unknown").Trim().ToUpperInvariant();
            if (architecture.Contains("ARM64"))
                return "ARM64";
            if (architecture.Contains("AMD64") || architecture.Contains("X86_64"))
                return "X64";
            if (architecture.Contains("86"))
                return "X86";
            return architecture;
        }

        private static string QuoteArgument(string value)
        {
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private static string SanitizeFileName(string value)
        {
            if (string.IsNullOrWhiteSpace(value))
                return "";
            string sanitized = value.Trim();
            foreach (char invalid in Path.GetInvalidFileNameChars())
                sanitized = sanitized.Replace(invalid, '_');
            sanitized = Regex.Replace(sanitized, @"\s+", "_");
            return sanitized.Trim('_', '.', ' ');
        }

        private static string MakeUniqueDirectory(string candidate)
        {
            string path = candidate;
            int suffix = 2;
            while (Directory.Exists(path) || File.Exists(path))
            {
                path = candidate + "_" + suffix.ToString(CultureInfo.InvariantCulture);
                suffix++;
            }
            return path;
        }

        private static void OpenPath(string path, bool directory)
        {
            if (string.IsNullOrWhiteSpace(path))
                return;
            try
            {
                if (directory)
                {
                    if (Directory.Exists(path))
                        Process.Start("explorer.exe", QuoteArgument(path));
                }
                else if (File.Exists(path))
                {
                    ProcessStartInfo open = new ProcessStartInfo(path);
                    open.UseShellExecute = true;
                    Process.Start(open);
                }
            }
            catch (Exception ex)
            {
                MessageBox.Show(ex.Message, "Could not open path", MessageBoxButtons.OK, MessageBoxIcon.Error);
            }
        }

        private string SettingsPath
        {
            get
            {
                return Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "IFQuantLauncher",
                    "settings.ini");
            }
        }

        private void SaveSettings()
        {
            try
            {
                string directory = Path.GetDirectoryName(SettingsPath);
                Directory.CreateDirectory(directory);
                Dictionary<string, string> settings = new Dictionary<string, string>();
                settings["input"] = inputBox.Text;
                settings["fiji"] = fijiBox.Text;
                settings["output"] = outputBaseBox.Text;
                settings["run_name"] = runNameBox.Text;
                settings["panel"] = ChoiceKey(panelBox);
                settings["segmenter"] = ChoiceKey(segmenterBox);
                settings["projection"] = ChoiceKey(projectionBox);
                settings["single_plane"] = singlePlaneBox.Value.ToString(CultureInfo.InvariantCulture);
                settings["tissue"] = ChoiceKey(tissueModeBox);
                settings["compartment_mode"] = ChoiceKey(compartmentModeBox);
                settings["whole_compartment"] = ChoiceKey(wholeCompartmentBox);
                settings["recursive"] = recursiveBox.Checked ? "true" : "false";
                settings["include_regex_b64"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(includeRegexBox.Text));
                settings["max_images"] = maxImagesBox.Value.ToString(CultureInfo.InvariantCulture);
                settings["panel_config"] = panelConfigBox.Text;
                settings["advanced_b64"] = Convert.ToBase64String(Encoding.UTF8.GetBytes(advancedBox.Text));

                StringBuilder content = new StringBuilder();
                foreach (KeyValuePair<string, string> item in settings)
                    content.AppendLine(item.Key + "=" + (item.Value ?? ""));
                File.WriteAllText(SettingsPath, content.ToString(), new UTF8Encoding(false));
            }
            catch
            {
                // Settings persistence is optional and must never block a run.
            }
        }

        private void LoadSavedSettings()
        {
            try
            {
                if (!File.Exists(SettingsPath))
                    return;
                Dictionary<string, string> values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
                foreach (string line in File.ReadAllLines(SettingsPath, Encoding.UTF8))
                {
                    int equals = line.IndexOf('=');
                    if (equals > 0)
                        values[line.Substring(0, equals)] = line.Substring(equals + 1);
                }
                inputBox.Text = GetValue(values, "input", inputBox.Text);
                fijiBox.Text = GetValue(values, "fiji", fijiBox.Text);
                outputBaseBox.Text = GetValue(values, "output", outputBaseBox.Text);
                runNameBox.Text = GetValue(values, "run_name", runNameBox.Text);
                SelectChoice(panelBox, GetValue(values, "panel", "AUTO"));
                SelectChoice(segmenterBox, GetValue(values, "segmenter", "classic"));
                SelectChoice(projectionBox, GetValue(values, "projection", "layer_aware"));
                SetNumeric(singlePlaneBox, GetValue(values, "single_plane", "-1"));
                SelectChoice(tissueModeBox, GetValue(values, "tissue", "auto"));
                SelectChoice(compartmentModeBox, GetValue(values, "compartment_mode", "required"));
                SelectChoice(wholeCompartmentBox, GetValue(values, "whole_compartment", "unassigned"));
                recursiveBox.Checked = string.Equals(GetValue(values, "recursive", "true"), "true", StringComparison.OrdinalIgnoreCase);
                includeRegexBox.Text = DecodeBase64(GetValue(values, "include_regex_b64", ""), ".*");
                SetNumeric(maxImagesBox, GetValue(values, "max_images", "0"));
                panelConfigBox.Text = GetValue(values, "panel_config", "");
                advancedBox.Text = DecodeBase64(GetValue(values, "advanced_b64", ""), "");
            }
            catch
            {
                // Ignore malformed previous settings and retain safe defaults.
            }
        }

        private static string GetValue(Dictionary<string, string> values, string key, string fallback)
        {
            string value;
            return values.TryGetValue(key, out value) ? value : fallback;
        }

        private static string DecodeBase64(string value, string fallback)
        {
            if (string.IsNullOrEmpty(value))
                return fallback;
            try { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
            catch { return fallback; }
        }

        private static void SetNumeric(NumericUpDown control, string text)
        {
            decimal value;
            if (Decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out value))
            {
                value = Math.Max(control.Minimum, Math.Min(control.Maximum, value));
                control.Value = value;
            }
        }
    }

    internal sealed class PanelDetectionResult
    {
        public int AnalyticalImageCount;
        public string FallbackPanel;
        public Dictionary<string, string> PanelByRelativePath =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public Dictionary<string, int> PanelCounts =
            new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);

        public bool IsMixed
        {
            get { return PanelCounts.Count > 1; }
        }

        public string CountSummary()
        {
            return string.Join(
                ", ",
                PanelCounts.OrderBy(delegate(KeyValuePair<string, int> pair) { return pair.Key; },
                                    StringComparer.OrdinalIgnoreCase)
                           .Select(delegate(KeyValuePair<string, int> pair)
                           {
                               return pair.Key + "=" + pair.Value;
                           }));
        }
    }

    internal sealed class SamplesheetPanelAssignments
    {
        public Dictionary<string, string> ByRelativePath =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        public Dictionary<string, string> ByFilename =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
    }

    internal sealed class RunConfiguration
    {
        public string InputDirectory;
        public string OutputDirectory;
        public string FijiExecutable;
        public string RuntimeDirectory;
        public string ScriptPath;
        public string RegistryPath;
        public bool PanelWasAutoDetected;
        public int PanelDetectionImageCount;
        public Dictionary<string, int> AutoPanelCounts;
        public string AutoPanelMapPath;
        public bool PreviewOnly;
        public Dictionary<string, string> Environment;
    }

    internal sealed class RuntimePaths
    {
        public string RuntimeDirectory;
        public string ScriptPath;
        public string RegistryPath;
    }

    internal static class RuntimeBundle
    {
        private const string ScriptResource = "IFQuant.IF_Quant_Pipeline.groovy";
        private const string RegistryResource = "IFQuant.lung_marker_registry.json";

        public static RuntimePaths EnsureExtracted()
        {
            Version version = Assembly.GetExecutingAssembly().GetName().Version;
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "IFQuantLauncher",
                "runtime",
                version.ToString());
            string config = Path.Combine(root, "config");
            Directory.CreateDirectory(config);

            string script = Path.Combine(root, "IF_Quant_Pipeline.groovy");
            string registry = Path.Combine(config, "lung_marker_registry.json");
            ExtractResource(ScriptResource, script);
            ExtractResource(RegistryResource, registry);

            RuntimePaths paths = new RuntimePaths();
            paths.RuntimeDirectory = root;
            paths.ScriptPath = script;
            paths.RegistryPath = registry;
            return paths;
        }

        private static void ExtractResource(string resourceName, string destination)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            using (Stream input = assembly.GetManifestResourceStream(resourceName))
            {
                if (input == null)
                    throw new InvalidOperationException("Embedded runtime resource is missing: " + resourceName);
                using (FileStream output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.None))
                    input.CopyTo(output);
            }
        }

        public static int SelfTest()
        {
            try
            {
                RuntimePaths paths = EnsureExtracted();
                if (!File.Exists(paths.ScriptPath) || new FileInfo(paths.ScriptPath).Length < 1000)
                    return 11;
                string pipelineText = File.ReadAllText(paths.ScriptPath, Encoding.UTF8);
                if (pipelineText.IndexOf("[IFQ_PROGRESS]", StringComparison.Ordinal) < 0)
                    return 16;
                if (pipelineText.IndexOf("non_analytical_map_acquisition", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("run_summary.xlsx", StringComparison.Ordinal) < 0)
                    return 17;
                if (pipelineText.IndexOf("LEFT_KRT5_AGER_T1A", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("RIGHT_ProSPC_AGER_KRT8", StringComparison.Ordinal) < 0)
                    return 18;
                if (pipelineText.IndexOf("layer_aware_2_5d", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("__z_plane_profile.csv", StringComparison.Ordinal) < 0)
                    return 19;
                if (pipelineText.IndexOf("ALI1_SCGB3A2_tdTOM_p63", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("ALI2_KRT5_tdTOM_AcTub", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("ALI3_KRT5_tdTOM_MUC5AC", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("ALI1_MAP_SCGB3A2_tdTOM", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("ALI23_MAP_KRT5_tdTOM", StringComparison.Ordinal) < 0)
                    return 20;
                if (pipelineText.IndexOf("DISPLAY ONLY - NOT QUANTIFIED", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("__VISUAL_MERGE_PANEL__merged_enhanced.png", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("visualization_only_not_quantification", StringComparison.Ordinal) < 0)
                    return 21;
                if (!string.Equals(
                        MainForm.InferBuiltInPanelFromText(
                            @"C:\images\Basal ALI krt5_488 tdTOM acetyl_647\field.oir"),
                        "ALI2",
                        StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(
                        MainForm.InferBuiltInPanelFromText(
                            @"C:\images\DAPI Pro-SPC_488 AGER_555 KRT8_647\field.czi"),
                        "RIGHT",
                        StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(
                        MainForm.InferBuiltInPanelFromText(
                            @"C:\images\krt5-creERT2 tdTOM CC10_488 acetyl_647\field.oir"),
                        "E",
                        StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(
                        MainForm.InferBuiltInPanelFromText(
                            @"C:\images\krt5_488 tdTOM acetyl_647 4x mapping\field.oir"),
                        "ALI23_MAP",
                        StringComparison.OrdinalIgnoreCase) ||
                    MainForm.InferBuiltInPanelFromText(@"C:\images\unnamed\field.oir") != null)
                    return 22;
                if (pipelineText.IndexOf("IFQ_DISPLAY_PREVIEW_ONLY", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("[IFQ_PREVIEW]", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("VISUAL MERGE PANELS COMPLETE", StringComparison.Ordinal) < 0)
                    return 23;
                if (pipelineText.IndexOf("IFQ_PANEL_MAP_PATH", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("auto_panel_assignments.csv", StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf("per_image_panel_routing", StringComparison.Ordinal) < 0)
                    return 24;
                if (!string.Equals(
                        MainForm.DisplayChannelExportSetting(false), "true",
                        StringComparison.OrdinalIgnoreCase) ||
                    !string.Equals(
                        MainForm.DisplayChannelExportSetting(true), "true",
                        StringComparison.OrdinalIgnoreCase))
                    return 26;
                if (pipelineText.IndexOf(
                        "if (MAX_IMAGES > 0) files = files.take(MAX_IMAGES)",
                        StringComparison.Ordinal) < 0 ||
                    pipelineText.IndexOf(
                        "if (DISPLAY_PREVIEW_ONLY) files = files.take(5)",
                        StringComparison.Ordinal) >= 0)
                    return 27;
                string mixedRoot = Path.Combine(
                    Path.GetTempPath(),
                    "IFQuantLauncher-mixed-panel-" + Guid.NewGuid().ToString("N"));
                bool mixedDetectionOk = false;
                try
                {
                    string left = Path.Combine(
                        mixedRoot, "DAPI KRT5_488 AGER_555 T1alpha_647");
                    string right = Path.Combine(
                        mixedRoot, "DAPI Pro-SPC_488 AGER_555 KRT8_647");
                    Directory.CreateDirectory(left);
                    Directory.CreateDirectory(right);
                    File.WriteAllBytes(Path.Combine(left, "left_field.oir"), new byte[0]);
                    File.WriteAllBytes(Path.Combine(right, "right_field.oir"), new byte[0]);
                    PanelDetectionResult mixed =
                        MainForm.DetectBuiltInPanels(mixedRoot, ".*", true);
                    string generatedMap =
                        MainForm.WriteAutoPanelMap(mixed, mixedRoot);
                    mixedDetectionOk =
                        mixed.AnalyticalImageCount == 2 &&
                        mixed.PanelCounts.Count == 2 &&
                        mixed.PanelCounts.ContainsKey("LEFT") &&
                        mixed.PanelCounts.ContainsKey("RIGHT") &&
                        mixed.PanelByRelativePath.Count == 2 &&
                        File.Exists(generatedMap) &&
                        File.ReadAllLines(generatedMap, Encoding.UTF8).Length == 3;
                }
                finally
                {
                    if (Directory.Exists(mixedRoot))
                        Directory.Delete(mixedRoot, true);
                }
                if (!mixedDetectionOk)
                    return 25;
                if (!File.Exists(paths.RegistryPath) || new FileInfo(paths.RegistryPath).Length < 100)
                    return 12;
                JavaScriptSerializer json = new JavaScriptSerializer();
                Dictionary<string, object> registry =
                    json.Deserialize<Dictionary<string, object>>(File.ReadAllText(paths.RegistryPath, Encoding.UTF8));
                if (registry == null || !registry.ContainsKey("markers"))
                    return 13;
                string knownFiji = @"X:\Fiji";
                if (Directory.Exists(knownFiji))
                {
                    string resolved = MainForm.ResolveFijiExecutable(knownFiji);
                    if (resolved == null || !File.Exists(resolved))
                        return 14;
                    string architecture = MainForm.GetWindowsArchitecture();
                    string expectedName = architecture == "ARM64"
                        ? "fiji-windows-arm64.exe"
                        : "fiji-windows-x64.exe";
                    string expected = Path.Combine(knownFiji, expectedName);
                    if (File.Exists(expected) &&
                        !string.Equals(Path.GetFullPath(expected), Path.GetFullPath(resolved), StringComparison.OrdinalIgnoreCase))
                        return 15;
                }
                return 0;
            }
            catch (Exception ex)
            {
                try
                {
                    File.WriteAllText(
                        Path.Combine(Path.GetTempPath(), "IFQuantLauncher-self-test-error.txt"),
                        ex.ToString(),
                        Encoding.UTF8);
                }
                catch
                {
                    // The numeric exit code remains authoritative when a
                    // restricted system cannot write the diagnostic file.
                }
                return 10;
            }
        }
    }
}
