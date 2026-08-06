// ============================================================================
//  IFQuantLauncher_QuPath_SlideScanner.cs
//  Windows front end for QuPath_SlideScanner_Quant.groovy — whole-slide
//  (slide-scanner) IF quantification for the IFN-gamma KO / PR8 influenza
//  project. Sibling to IFQuantLauncher (Fiji / confocal fields).
//
//  It does NOT reimplement image analysis. It embeds the exact QuPath Groovy
//  script present at build time, extracts it at run time, and invokes QuPath's
//  headless `script` subcommand once per slide, passing every parameter through
//  IFQ_* environment variables (the same names the script reads).
//
//  Target: .NET Framework 4.x, AnyCPU (Windows ARM64 + x64). Build with
//  launcher-qupath-slidescanner/build.ps1. NOT compiled/tested in this
//  environment — build and smoke-test locally before use.
// ============================================================================
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

[assembly: AssemblyTitle("IF Quant Launcher - QuPath SlideScanner")]
[assembly: AssemblyDescription("Windows launcher for the QuPath whole-slide morphology-primary IF quantification script")]
[assembly: AssemblyCompany("IF Quant Pipeline")]
[assembly: AssemblyProduct("IF Quant Launcher - QuPath SlideScanner")]
[assembly: AssemblyCopyright("Research software")]
[assembly: AssemblyVersion("0.1.0.0")]
[assembly: AssemblyFileVersion("0.1.0.0")]

namespace IFQuantLauncherQuPathSlideScanner
{
    internal static class Program
    {
        [STAThread]
        internal static int Main(string[] args)
        {
            foreach (string a in args)
            {
                if (string.Equals(a, "--self-test", StringComparison.OrdinalIgnoreCase))
                    return RuntimeBundle.SelfTest();
                if (string.Equals(a, "--version", StringComparison.OrdinalIgnoreCase))
                {
                    Console.WriteLine(Assembly.GetExecutingAssembly().GetName().Version.ToString());
                    return 0;
                }
            }
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
            return 0;
        }
    }

    // Slide-scanner formats QuPath reads through Bio-Formats.
    internal static class SlideFormats
    {
        public static readonly string[] Extensions =
        {
            ".svs", ".ndpi", ".mrxs", ".scn", ".vsi", ".qptiff", ".bif",
            ".czi", ".ome.tif", ".ome.tiff", ".tif", ".tiff"
        };

        public static bool IsSlide(string path)
        {
            string lower = path.ToLowerInvariant();
            foreach (string ext in Extensions)
                if (lower.EndsWith(ext, StringComparison.Ordinal)) return true;
            return false;
        }
    }

    internal sealed class MainForm : Form
    {
        private readonly TextBox _inputBox = new TextBox();
        private readonly TextBox _quPathBox = new TextBox();
        private readonly TextBox _outputBox = new TextBox();
        private readonly ComboBox _panelBox = new ComboBox();
        private readonly TextBox _mouseBox = new TextBox();
        private readonly TextBox _sectionBox = new TextBox();
        private readonly TextBox _genotypeBox = new TextBox();
        private readonly TextBox _conditionBox = new TextBox();
        private readonly TextBox _tileBox = new TextBox();
        private readonly TextBox _cellThresholdBox = new TextBox();
        private readonly TextBox _logBox = new TextBox();
        private readonly Button _runButton = new Button();

        public MainForm()
        {
            Text = "IF Quant — QuPath SlideScanner v" +
                   Assembly.GetExecutingAssembly().GetName().Version.ToString(3);
            Width = 760;
            Height = 640;
            Font = new Font("Segoe UI", 9f);
            StartPosition = FormStartPosition.CenterScreen;

            int y = 12;
            y = AddPathRow("Slide file or folder:", _inputBox, "Browse folder…", BrowseInputFolder, y, "Browse file…", BrowseInputFile);
            y = AddPathRow("QuPath executable:", _quPathBox, "Browse…", BrowseQuPath, y);
            y = AddPathRow("Output folder:", _outputBox, "Browse…", BrowseOutput, y);

            AddLabel("Panel:", 12, y + 4);
            _panelBox.DropDownStyle = ComboBoxStyle.DropDownList;
            _panelBox.Items.AddRange(new object[] { "A", "B", "C", "D", "P", "S", "S2" });
            _panelBox.SelectedIndex = 0;
            _panelBox.SetBounds(150, y, 80, 24);
            Controls.Add(_panelBox);

            AddLabel("Tile size (µm, 0=off):", 260, y + 4);
            _tileBox.Text = "0";
            _tileBox.SetBounds(410, y, 70, 24);
            Controls.Add(_tileBox);

            AddLabel("Nucleus threshold:", 500, y + 4);
            _cellThresholdBox.Text = "100";
            _cellThresholdBox.SetBounds(630, y, 70, 24);
            Controls.Add(_cellThresholdBox);
            y += 34;

            AddLabel("mouse_id:", 12, y + 4);
            _mouseBox.SetBounds(90, y, 100, 24); Controls.Add(_mouseBox);
            AddLabel("section_id:", 200, y + 4);
            _sectionBox.SetBounds(280, y, 100, 24); Controls.Add(_sectionBox);
            AddLabel("genotype:", 390, y + 4);
            _genotypeBox.SetBounds(460, y, 90, 24); Controls.Add(_genotypeBox);
            AddLabel("condition:", 560, y + 4);
            _conditionBox.SetBounds(630, y, 90, 24); Controls.Add(_conditionBox);
            y += 40;

            _runButton.Text = "Run analysis";
            _runButton.SetBounds(12, y, 140, 30);
            _runButton.Click += RunClicked;
            Controls.Add(_runButton);

            Label note = new Label();
            note.Text = "Morphology-primary calls are enforced. n = mice (aggregate summary before stats).";
            note.SetBounds(170, y + 6, 560, 20);
            note.ForeColor = Color.DimGray;
            Controls.Add(note);
            y += 40;

            _logBox.Multiline = true;
            _logBox.ScrollBars = ScrollBars.Vertical;
            _logBox.ReadOnly = true;
            _logBox.SetBounds(12, y, 720, Height - y - 52);
            _logBox.Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right;
            _logBox.Font = new Font("Consolas", 8.5f);
            Controls.Add(_logBox);
        }

        private int AddPathRow(string label, TextBox box, string btnText, EventHandler handler, int y,
                               string btn2Text = null, EventHandler handler2 = null)
        {
            AddLabel(label, 12, y + 4);
            box.SetBounds(150, y, btn2Text == null ? 480 : 380, 24);
            box.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Controls.Add(box);
            Button b = new Button();
            b.Text = btnText;
            b.SetBounds(btn2Text == null ? 640 : 540, y - 1, 90, 26);
            b.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            b.Click += handler;
            Controls.Add(b);
            if (btn2Text != null)
            {
                Button b2 = new Button();
                b2.Text = btn2Text;
                b2.SetBounds(636, y - 1, 94, 26);
                b2.Anchor = AnchorStyles.Top | AnchorStyles.Right;
                b2.Click += handler2;
                Controls.Add(b2);
            }
            return y + 34;
        }

        private void AddLabel(string text, int x, int y)
        {
            Label l = new Label();
            l.Text = text;
            l.AutoSize = true;
            l.SetBounds(x, y, 10, 20);
            Controls.Add(l);
        }

        private void BrowseInputFolder(object sender, EventArgs e)
        {
            using (FolderBrowserDialog d = new FolderBrowserDialog())
                if (d.ShowDialog() == DialogResult.OK) _inputBox.Text = d.SelectedPath;
        }

        private void BrowseInputFile(object sender, EventArgs e)
        {
            using (OpenFileDialog d = new OpenFileDialog())
            {
                d.Filter = "Slide images|*.svs;*.ndpi;*.mrxs;*.scn;*.vsi;*.qptiff;*.bif;*.czi;*.tif;*.tiff|All files|*.*";
                if (d.ShowDialog() == DialogResult.OK) _inputBox.Text = d.FileName;
            }
        }

        private void BrowseQuPath(object sender, EventArgs e)
        {
            using (OpenFileDialog d = new OpenFileDialog())
            {
                d.Filter = "QuPath executable|QuPath*.exe;*.exe|All files|*.*";
                if (d.ShowDialog() == DialogResult.OK) _quPathBox.Text = d.FileName;
            }
        }

        private void BrowseOutput(object sender, EventArgs e)
        {
            using (FolderBrowserDialog d = new FolderBrowserDialog())
                if (d.ShowDialog() == DialogResult.OK) _outputBox.Text = d.SelectedPath;
        }

        private void Log(string line)
        {
            if (_logBox.InvokeRequired) { _logBox.BeginInvoke(new Action<string>(Log), line); return; }
            _logBox.AppendText(line + Environment.NewLine);
        }

        private List<string> ResolveSlides(string input)
        {
            List<string> slides = new List<string>();
            if (File.Exists(input))
            {
                if (SlideFormats.IsSlide(input)) slides.Add(input);
            }
            else if (Directory.Exists(input))
            {
                foreach (string f in Directory.GetFiles(input))
                    if (SlideFormats.IsSlide(f)) slides.Add(f);
                slides.Sort(StringComparer.OrdinalIgnoreCase);
            }
            return slides;
        }

        private void RunClicked(object sender, EventArgs e)
        {
            _logBox.Clear();
            string input = _inputBox.Text.Trim();
            string quPath = _quPathBox.Text.Trim();
            string output = _outputBox.Text.Trim();

            if (input.Length == 0 || (!File.Exists(input) && !Directory.Exists(input)))
            { MessageBox.Show("Select a valid slide file or folder."); return; }
            if (!File.Exists(quPath))
            { MessageBox.Show("Select the QuPath executable (use the console build for output)."); return; }
            if (output.Length == 0) { MessageBox.Show("Select an output folder."); return; }
            Directory.CreateDirectory(output);

            List<string> slides = ResolveSlides(input);
            if (slides.Count == 0) { MessageBox.Show("No slide-scanner images found."); return; }

            RuntimePaths paths;
            try { paths = RuntimeBundle.EnsureExtracted(); }
            catch (Exception ex) { MessageBox.Show("Could not extract the embedded QuPath script: " + ex.Message); return; }

            _runButton.Enabled = false;
            Log("QuPath script: " + paths.ScriptPath);
            Log("Slides to process: " + slides.Count);
            Log("");

            System.Threading.ThreadPool.QueueUserWorkItem(delegate
            {
                int ok = 0, fail = 0;
                foreach (string slide in slides)
                {
                    Log("==== " + Path.GetFileName(slide) + " ====");
                    try
                    {
                        int code = RunOne(quPath, paths.ScriptPath, slide, output);
                        if (code == 0) { ok++; Log("  OK"); }
                        else { fail++; Log("  FAILED (exit " + code + ")"); }
                    }
                    catch (Exception ex) { fail++; Log("  ERROR: " + ex.Message); }
                    Log("");
                }
                Log("Done. " + ok + " ok, " + fail + " failed. Summary: " +
                    Path.Combine(output, "qupath_slidescanner_summary.csv"));
                if (_runButton.InvokeRequired)
                    _runButton.BeginInvoke(new Action(delegate { _runButton.Enabled = true; }));
                else _runButton.Enabled = true;
            });
        }

        private int RunOne(string quPath, string scriptPath, string slide, string output)
        {
            ProcessStartInfo psi = new ProcessStartInfo();
            psi.FileName = quPath;
            psi.Arguments = "script -i " + QuoteArgument(slide) + " " + QuoteArgument(scriptPath);
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            psi.RedirectStandardError = true;
            psi.CreateNoWindow = true;

            // Parameters passed to the Groovy script (IFQ_* env vars).
            psi.EnvironmentVariables["IFQ_OUTPUT_DIR"] = output;
            psi.EnvironmentVariables["IFQ_PANEL"] = _panelBox.SelectedItem.ToString();
            psi.EnvironmentVariables["IFQ_MORPHOLOGY_PRIMARY"] = "true";
            psi.EnvironmentVariables["IFQ_MOUSE_ID"] = Nz(_mouseBox.Text);
            psi.EnvironmentVariables["IFQ_SECTION_ID"] = Nz(_sectionBox.Text);
            psi.EnvironmentVariables["IFQ_GENOTYPE"] = Nz(_genotypeBox.Text);
            psi.EnvironmentVariables["IFQ_CONDITION"] = Nz(_conditionBox.Text);
            psi.EnvironmentVariables["IFQ_TILE_SIZE_UM"] = NumOr(_tileBox.Text, "0");
            psi.EnvironmentVariables["IFQ_CELL_THRESHOLD"] = NumOr(_cellThresholdBox.Text, "100");

            using (Process p = new Process())
            {
                p.StartInfo = psi;
                p.OutputDataReceived += delegate (object s, DataReceivedEventArgs ev) { if (ev.Data != null) Log("  " + ev.Data); };
                p.ErrorDataReceived += delegate (object s, DataReceivedEventArgs ev) { if (ev.Data != null) Log("  ! " + ev.Data); };
                p.Start();
                p.BeginOutputReadLine();
                p.BeginErrorReadLine();
                p.WaitForExit();
                return p.ExitCode;
            }
        }

        private static string Nz(string s) { return string.IsNullOrWhiteSpace(s) ? "NA" : s.Trim(); }

        private static string NumOr(string s, string fallback)
        {
            double v;
            return double.TryParse(s, out v) ? s.Trim() : fallback;
        }

        internal static string QuoteArgument(string value)
        {
            if (string.IsNullOrEmpty(value)) return "\"\"";
            if (value.IndexOf(' ') < 0 && value.IndexOf('"') < 0) return value;
            return "\"" + value.Replace("\"", "\\\"") + "\"";
        }
    }

    internal sealed class RuntimePaths
    {
        public string RuntimeDirectory;
        public string ScriptPath;
    }

    internal static class RuntimeBundle
    {
        private const string ScriptResource = "IFQuant.QuPath_SlideScanner_Quant.groovy";

        public static RuntimePaths EnsureExtracted()
        {
            Version version = Assembly.GetExecutingAssembly().GetName().Version;
            string root = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "IFQuantLauncher_QuPath_SlideScanner", "runtime", version.ToString());
            Directory.CreateDirectory(root);
            string script = Path.Combine(root, "QuPath_SlideScanner_Quant.groovy");
            ExtractResource(ScriptResource, script);
            RuntimePaths paths = new RuntimePaths();
            paths.RuntimeDirectory = root;
            paths.ScriptPath = script;
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

        // Exit codes: 0 pass; 11 script missing/short; 16-18 required markers absent.
        public static int SelfTest()
        {
            try
            {
                RuntimePaths paths = EnsureExtracted();
                if (!File.Exists(paths.ScriptPath) || new FileInfo(paths.ScriptPath).Length < 1000)
                    return 11;
                string text = File.ReadAllText(paths.ScriptPath, Encoding.UTF8);
                if (text.IndexOf("morphology must authorise", StringComparison.Ordinal) < 0)
                    return 16;
                if (text.IndexOf("WatershedCellDetection", StringComparison.Ordinal) < 0 ||
                    text.IndexOf("SimpleTissueDetection2", StringComparison.Ordinal) < 0)
                    return 17;
                if (text.IndexOf("qupath_slidescanner_summary.csv", StringComparison.Ordinal) < 0 ||
                    text.IndexOf("n = mice", StringComparison.Ordinal) < 0)
                    return 18;
                return 0;
            }
            catch { return 99; }
        }
    }
}
