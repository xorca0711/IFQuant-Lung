// Independent adversarial probe. Links the SHIPPING IFQuantLauncher.Routing.cs.
// Nothing here is copied from the previous agent's harnesses.
using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using IFQuantLauncher.Routing;

internal static class AdvProbe
{
    static int fails = 0;
    static int checks = 0;
    static string groovyPath;
    static string v172Path;

    static void OK(string name, bool cond, string detail)
    {
        checks++;
        if (!cond) { fails++; Console.WriteLine("FAIL  " + name + "  :: " + detail); }
        else Console.WriteLine("pass  " + name + (detail.Length > 0 ? "  (" + detail + ")" : ""));
    }

    static int Main(string[] args)
    {
        groovyPath = args[0];
        v172Path = args[1];
        Console.WriteLine("=== SECTION A : ROUTE 3 REACHABILITY ===");
        SectionA();
        Console.WriteLine();
        Console.WriteLine("=== SECTION B : LEGACY EQUIVALENCE (my own oracle) ===");
        SectionB();
        Console.WriteLine();
        Console.WriteLine("=== SECTION C : DEFEATING H1-H5 ===");
        SectionC();
        Console.WriteLine();
        Console.WriteLine("=== SECTION D : R4 ADVANCED-KEY ACCEPTANCE vs v1.7.2 ===");
        SectionD();
        Console.WriteLine();
        Console.WriteLine("checks=" + checks + " fails=" + fails);
        return fails == 0 ? 0 : 1;
    }

    // ---------------------------------------------------------------- helpers
    static Dictionary<string, PanelDef> Panels()
    {
        return PanelRegistry.ParseFromPipeline(File.ReadAllText(groovyPath));
    }
    static HashSet<string> ThreshMarkers()
    {
        return PanelRegistry.ParseThresholdMarkerTokens(File.ReadAllText(groovyPath));
    }
    static ToolInventory FullTools()
    {
        // Everything present, so no tool rule can mask the rule under test.
        ToolInventory t = new ToolInventory();
        t.FijiExecutable = @"C:\fiji\fiji.exe";
        t.FijiDirectory = @"C:\fiji";
        t.JavaExecutable = @"C:\fiji\java\java.exe";
        t.Ij1PatcherJar = @"C:\fiji\jars\ij1-patcher-1.0.jar";
        t.QuPathExecutable = @"C:\qp\QuPath (console).exe";
        t.PythonExecutable = @"C:\py\python.exe";
        return t;
    }
    static RunRequest Base(ImageRoute route, string panelKey)
    {
        RunRequest r = new RunRequest();
        r.Route = route;
        r.Tier = RunTier.Exploratory;
        r.InputDirectory = @"C:\in";
        r.OutputBase = @"C:\out";
        r.RunName = "run";
        r.FijiPath = @"C:\fiji";
        r.PanelKey = panelKey;
        r.MinIncludedNuclei = 0;
        r.WsiInput = @"C:\s\a.vsi";
        r.WsiOutput = @"C:\s\out";
        r.SlideMetadataCsv = @"C:\s\m.csv";
        return r;
    }
    static void FreezeAll(RunRequest r, PanelDef p, HashSet<string> tm)
    {
        foreach (ChannelDef c in p.AnalysisChannels)
            if (tm.Contains(c.Token)) r.Thresholds[c.Token] = "500";
    }
    static string Codes(GateResult g, Severity s)
    {
        List<string> c = new List<string>();
        foreach (GateFinding f in g.OfSeverity(s)) c.Add(f.Code);
        return string.Join(",", c.ToArray());
    }

    // ================================================================ SECTION A
    static void SectionA()
    {
        Dictionary<string, PanelDef> panels = Panels();
        HashSet<string> tm = ThreshMarkers();
        PanelDef left = panels["LEFT"];

        OK("A1 flag is off", LauncherBuild.BrightfieldRouteEnabled == false, "");
        RouteSpec s3 = RouteCatalog.Describe(ImageRoute.HeBrightfield);
        OK("A2 route 3 not Available", s3.Available == false, "");
        OK("A3 route 3 still listed", Array.IndexOf(RouteCatalog.All(), ImageRoute.HeBrightfield) >= 0, "visible in All()");
        OK("A4 route 3 has zero stages", s3.Stages.Count == 0, "stages=" + s3.Stages.Count);
        OK("A5 reason is non-empty", !string.IsNullOrEmpty(s3.UnavailableReason), "");
        OK("A6 display name says PLANNED",
           s3.DisplayName.IndexOf("NOT AVAILABLE", StringComparison.Ordinal) >= 0, s3.DisplayName);

        // A7: perfect request except route -> must still Block, no phrase offered.
        RunRequest r3 = Base(ImageRoute.HeBrightfield, "LEFT");
        FreezeAll(r3, left, tm);
        GateResult g3 = FailClosedGate.Evaluate(r3, left, tm, FullTools());
        OK("A7 gate blocks an otherwise-perfect route 3", g3.Blocked, Codes(g3, Severity.Block));
        OK("A8 gate offers no unlock phrase", g3.RequiredPhrases.Count == 0, "");

        // A9: BuildStage2 must throw and emit nothing.
        Dictionary<string, string> leaked = null;
        bool threw = false; string msg = "";
        try
        {
            leaked = RunEnvironment.BuildStage2(
                r3, left, tm, @"C:\rt\reg.json", @"C:\out\r", @"C:\in", null, false);
        }
        catch (Exception ex) { threw = true; msg = ex.GetType().Name; }
        OK("A9 BuildStage2 throws on route 3", threw, msg);
        OK("A10 no environment escaped", leaked == null, "");

        // A11: PreStartAssertions must throw even when handed a perfect env.
        RunRequest r1 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(r1, left, tm);
        Dictionary<string, string> good = RunEnvironment.BuildStage2(
            r1, left, tm, @"C:\rt\reg.json", @"C:\out\r", @"C:\in", null, false);
        threw = false; msg = "";
        try { PreStartAssertions.AssertStage2Environment(good, null, ImageRoute.HeBrightfield); }
        catch (Exception ex) { threw = true; msg = ex.GetType().Name; }
        OK("A11 PreStartAssertions throws on route 3 with a VALID env", threw, msg);

        // A12: BuildStage1 (the QuPath stage) is NOT route-guarded. Route 3
        // declares RequiresQuPath, so ask whether a stage-1 env can be minted.
        Dictionary<string, string> st1 = null; threw = false; msg = "";
        try { st1 = RunEnvironment.BuildStage1(r3, "LEFT"); }
        catch (Exception ex) { threw = true; msg = ex.GetType().Name; }
        OK("A12 BuildStage1 refuses route 3", threw,
           threw ? msg : "RETURNED " + st1.Count + " vars: " + LegacyProfile.Canonicalize(st1).Replace("\n", " | "));

        // A13: RunRecord must not describe a route-3 run as runnable.
        threw = false;
        string rec = null;
        try
        {
            rec = RunRecord.Build(r3, g3, good, "1.8.0.0", "X64", "f.exe", "x", 0, "complete", "a", "b", null);
        }
        catch (Exception) { threw = true; }
        OK("A13 RunRecord for route 3 carries the block", !threw && rec != null &&
           rec.IndexOf("ROUTE_NOT_AVAILABLE", StringComparison.Ordinal) >= 0,
           threw ? "threw" : "recorded");

        // A14: an out-of-range enum value. Not R3, but the same door.
        ImageRoute bogus = (ImageRoute)7;
        RouteSpec sb = RouteCatalog.Describe(bogus);
        RunRequest rb = Base(bogus, "LEFT");
        FreezeAll(rb, left, tm);
        GateResult gb = FailClosedGate.Evaluate(rb, left, tm, FullTools());
        Dictionary<string, string> ebo = null; threw = false;
        try { ebo = RunEnvironment.BuildStage2(rb, left, tm, @"C:\rt\reg.json", @"C:\o", @"C:\in", null, false); }
        catch (Exception) { threw = true; }
        bool preOk = true;
        try { PreStartAssertions.AssertStage2Environment(ebo, null, bogus); } catch (Exception) { preOk = false; }
        OK("A14 unknown route value is refused somewhere",
           !sb.Available || gb.Blocked || threw || !preOk,
           "Available=" + sb.Available + " Blocked=" + gb.Blocked + " BuildThrew=" + threw +
           " PreStartPassed=" + preOk + " vars=" + (ebo == null ? 0 : ebo.Count));
    }

    // ================================================================ SECTION B
    // My own transcription of v1.7.2 IFQuantLauncher.cs:1394-1424, read directly
    // from the repo file. Deliberately NOT reusing LegacyProfile.
    static Dictionary<string, string> Oracle172(
        string input, string outputDirectory, string panelKey, string registryPath,
        string autoPanelMapPath, string panelConfig, bool recursive, string includeRegex,
        int maxImages, string segmenter, string projection, int singlePlane,
        bool previewOnly, string tissueMode, string compartmentMode, string wholeFieldCompartment,
        Dictionary<string, string> advanced)
    {
        Dictionary<string, string> env = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        env["IFQ_INPUT_DIR"] = Path.GetFullPath(input);
        env["IFQ_OUTPUT_DIR"] = outputDirectory;
        env["IFQ_PANEL"] = panelKey;
        env["IFQ_MARKER_REGISTRY"] = registryPath;
        if (!string.IsNullOrWhiteSpace(autoPanelMapPath))
            env["IFQ_PANEL_MAP_PATH"] = autoPanelMapPath;
        if (panelConfig.Length > 0)
            env["IFQ_PANEL_CONFIG"] = Path.GetFullPath(panelConfig);
        env["IFQ_RECURSIVE"] = recursive ? "true" : "false";
        env["IFQ_INCLUDE_REGEX"] = includeRegex;
        env["IFQ_MAX_IMAGES"] = maxImages.ToString(CultureInfo.InvariantCulture);
        env["IFQ_SEGMENTER"] = segmenter;
        env["IFQ_PROJECTION"] = projection;
        env["IFQ_SINGLE_PLANE"] = singlePlane.ToString(CultureInfo.InvariantCulture);
        env["IFQ_EXPORT_DISPLAY_CHANNELS"] = "true";       // DisplayChannelExportSetting
        env["IFQ_DISPLAY_PREVIEW_ONLY"] = previewOnly ? "true" : "false";
        env["IFQ_TISSUE_MODE"] = tissueMode;
        env["IFQ_COMPARTMENT_MODE"] = compartmentMode;
        env["IFQ_WHOLE_FIELD_COMPARTMENT"] = wholeFieldCompartment;
        env["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "false";
        env["IFQ_MORPHOLOGY_PRIMARY"] = "true";
        if (advanced != null)
            foreach (KeyValuePair<string, string> item in advanced) env[item.Key] = item.Value;
        return env;
    }

    // What route 4 does, exactly as IFQuantLauncher.cs:1499-1509 calls it.
    static Dictionary<string, string> Route4(
        string input, string outputDirectory, string panelKey, string registryPath,
        string autoPanelMapPath, string panelConfig, bool recursive, string includeRegex,
        int maxImages, string segmenter, string projection, int singlePlane,
        bool previewOnly, string tissueMode, string compartmentMode, string wholeFieldCompartment,
        Dictionary<string, string> advanced)
    {
        Dictionary<string, string> env = LegacyProfile.BuildEnvironment(
            input, outputDirectory, panelKey, registryPath,
            autoPanelMapPath, panelConfig.Length > 0 ? panelConfig : null,
            recursive, includeRegex, maxImages, segmenter, projection, singlePlane,
            previewOnly, tissueMode, compartmentMode, wholeFieldCompartment);
        if (advanced != null)
            foreach (KeyValuePair<string, string> item in advanced) env[item.Key] = item.Value;
        return env;
    }

    static string Canon(Dictionary<string, string> e)
    {
        List<string> k = new List<string>(e.Keys);
        k.Sort(StringComparer.Ordinal);
        StringBuilder b = new StringBuilder();
        foreach (string s in k) b.Append(s).Append('=').Append(e[s]).Append('\n');
        return b.ToString();
    }

    static void Compare(string name,
        string input, string outputDirectory, string panelKey, string registryPath,
        string autoPanelMapPath, string panelConfig, bool recursive, string includeRegex,
        int maxImages, string segmenter, string projection, int singlePlane,
        bool previewOnly, string tissueMode, string compartmentMode, string wholeFieldCompartment,
        Dictionary<string, string> advanced)
    {
        string a = Canon(Oracle172(input, outputDirectory, panelKey, registryPath, autoPanelMapPath,
            panelConfig, recursive, includeRegex, maxImages, segmenter, projection, singlePlane,
            previewOnly, tissueMode, compartmentMode, wholeFieldCompartment, advanced));
        string b = Canon(Route4(input, outputDirectory, panelKey, registryPath, autoPanelMapPath,
            panelConfig, recursive, includeRegex, maxImages, segmenter, projection, singlePlane,
            previewOnly, tissueMode, compartmentMode, wholeFieldCompartment, advanced));
        OK("B " + name, string.Equals(a, b, StringComparison.Ordinal), Diff(a, b));
    }

    static string Diff(string a, string b)
    {
        if (string.Equals(a, b, StringComparison.Ordinal)) return "identical";
        List<string> la = new List<string>(a.Split('\n'));
        List<string> lb = new List<string>(b.Split('\n'));
        StringBuilder d = new StringBuilder();
        foreach (string x in la) if (!lb.Contains(x) && x.Length > 0) d.Append(" v172-only[").Append(x).Append("]");
        foreach (string x in lb) if (!la.Contains(x) && x.Length > 0) d.Append(" R4-only[").Append(x).Append("]");
        return d.ToString();
    }

    static void SectionB()
    {
        Dictionary<string, string> none = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        Compare("b1 recommended defaults",
            @"C:\in", @"C:\out\run_1", "LEFT", @"C:\rt\reg.json", null, "", true, ".*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", none);

        Compare("b2 every non-default + preview",
            @"C:\in\deep", @"C:\out\r2", "RIGHT", @"C:\rt\reg.json", null, "", false, "^A.*\\.tif$", 12,
            "stardist", "max", 3, true, "manual", "optional", "airway", none);

        Compare("b3 AUTO panel map present",
            @"C:\in", @"C:\out\r3", "LEFT", @"C:\rt\reg.json", @"C:\rt\panel_map.json", "", true, ".*", 0,
            "classic", "single", 0, false, "auto", "required", "alveolar", none);

        Compare("b4 custom panel json present",
            @"C:\in", @"C:\out\r4", "MYPANEL", @"C:\rt\reg.json", null, @"C:\cfg\p.json", true, ".*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", none);

        Compare("b5 both conditionals",
            @"C:\in", @"C:\out\r5", "LEFT", @"C:\rt\reg.json", @"C:\rt\pm.json", @"C:\cfg\p.json", true,
            ".*", 5, "classic", "layer_aware", -1, true, "auto", "required", "unassigned", none);

        Dictionary<string, string> adv = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        adv["IFQ_KRT5_THRESHOLD"] = "512.5";
        adv["IFQ_AGER_THRESHOLD"] = "300";
        adv["IFQ_MIN_INCLUDED_NUCLEI"] = "25";
        adv["IFQ_DAPI_METHOD"] = "otsu";
        Compare("b6 advanced overlay incl. thresholds + nuclei floor",
            @"C:\in", @"C:\out\r6", "LEFT", @"C:\rt\reg.json", null, "", true, ".*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", adv);

        Dictionary<string, string> odd = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        odd["IFQ_WEIRD"] = "a b \"c\" ünïcøde";
        Compare("b7 spaces/quotes/non-ascii",
            @"C:\in with space", @"C:\out\r 7", "LEFT", @"C:\rt\reg 1.json", null, "", true, ".* .*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", odd);

        Compare("b8 maxImages int.MaxValue / singlePlane int.MinValue",
            @"C:\in", @"C:\out\r8", "LEFT", @"C:\rt\reg.json", null, "", true, ".*", int.MaxValue,
            "classic", "layer_aware", int.MinValue, false, "auto", "required", "unassigned", none);

        Compare("b9 whitespace-only auto panel map path",
            @"C:\in", @"C:\out\r9", "LEFT", @"C:\rt\reg.json", "   ", "", true, ".*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", none);

        Compare("b10 relative input path",
            @".", @"C:\out\r10", "LEFT", @"C:\rt\reg.json", null, "", true, ".*", 0,
            "classic", "layer_aware", -1, false, "auto", "required", "unassigned", none);

        // The command line
        string cl = LegacyProfile.CommandLine(@"C:\rt\IF_Quant_Pipeline.groovy");
        OK("B b11 command line == v1.7.2 inline string",
           cl == "--headless --console --run \"C:\\rt\\IF_Quant_Pipeline.groovy\"", cl);

        // The exact string v1.7.2 builds, checked against the repo source.
        string src = File.ReadAllText(v172Path);
        OK("B b12 v1.7.2 really uses that command line",
           src.IndexOf("\"--headless --console --run \" + QuoteArgument(config.ScriptPath)",
                       StringComparison.Ordinal) >= 0, "");
        OK("B b13 v1.7.2 really is 1.7.2.0",
           src.IndexOf("AssemblyFileVersion(\"1.7.2.0\")", StringComparison.Ordinal) >= 0, "");
        OK("B b14 v1.7.2 never assigns IFQ_MIN_INCLUDED_NUCLEI",
           !Regex.IsMatch(src, "env\\[\"IFQ_MIN_INCLUDED_NUCLEI\"\\]\\s*="), "");
        OK("B b15 v1.7.2 never assigns any IFQ_*_THRESHOLD",
           !Regex.IsMatch(src, "env\\[\"IFQ_[A-Z0-9_]*_THRESHOLD\"\\]\\s*="), "");

        // Key set and order, extracted from the repo file, against LegacyProfile.
        MatchCollection m = Regex.Matches(src, "env\\[\"(IFQ_[A-Z0-9_]+)\"\\]\\s*=");
        List<string> found = new List<string>();
        foreach (Match x in m) if (!found.Contains(x.Groups[1].Value)) found.Add(x.Groups[1].Value);
        OK("B b16 19 keys in v1.7.2", found.Count == 19, "found " + found.Count);
        OK("B b17 order matches LegacyProfile.KeyOrder",
           string.Join(",", found.ToArray()) == string.Join(",", LegacyProfile.KeyOrder),
           string.Join(",", found.ToArray()));

        // Real child process: does the environment actually ARRIVE identical?
        string probe = Path.Combine(Path.GetDirectoryName(
            System.Reflection.Assembly.GetExecutingAssembly().Location), "AdvEnvProbe.exe");
        if (File.Exists(probe))
        {
            Environment.SetEnvironmentVariable("IFQ_PANEL", "T");
            Environment.SetEnvironmentVariable("IFQ_MIN_INCLUDED_NUCLEI", "9");
            Environment.SetEnvironmentVariable("IFQ_STALE_JUNK", "yes");
            Dictionary<string, string> o = Oracle172(@"C:\in", @"C:\out\p", "LEFT",
                @"C:\rt\reg.json", null, "", true, ".*", 0, "classic", "layer_aware", -1, false,
                "auto", "required", "unassigned", adv);
            Dictionary<string, string> f = Route4(@"C:\in", @"C:\out\p", "LEFT",
                @"C:\rt\reg.json", null, "", true, ".*", 0, "classic", "layer_aware", -1, false,
                "auto", "required", "unassigned", adv);
            string ro = RunChild(probe, o), rf = RunChild(probe, f);
            OK("B b18 child process receives identical env",
               string.Equals(ro, rf, StringComparison.Ordinal), Diff(ro, rf));
            OK("B b19 stale inherited IFQ_ vars stripped",
               ro.IndexOf("IFQ_STALE_JUNK", StringComparison.Ordinal) < 0, "");
            OK("B b20 no IFQ_MIN_INCLUDED_NUCLEI leaks from the shell",
               ro.IndexOf("IFQ_MIN_INCLUDED_NUCLEI=9", StringComparison.Ordinal) < 0, "");
        }
        else Console.WriteLine("skip  B b18-b20 (AdvEnvProbe.exe missing)");
    }

    static string RunChild(string exe, Dictionary<string, string> env)
    {
        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = exe;
        psi.UseShellExecute = false;
        psi.RedirectStandardOutput = true;
        psi.CreateNoWindow = true;
        EnvironmentApply.Apply(psi, env);
        using (Process p = Process.Start(psi))
        {
            string o = p.StandardOutput.ReadToEnd();
            p.WaitForExit();
            return o;
        }
    }

    // ================================================================ SECTION C
    static void SectionC()
    {
        Dictionary<string, PanelDef> panels = Panels();
        HashSet<string> tm = ThreshMarkers();
        PanelDef left = panels["LEFT"];
        PanelDef pilotT = panels.ContainsKey("T") ? panels["T"] : null;

        // ---- H1 ----------------------------------------------------------
        RunRequest c1 = Base(ImageRoute.IfConfocal, "");
        GateResult g = FailClosedGate.Evaluate(c1, null, tm, FullTools());
        OK("C H1-a blank panel blocked", g.Blocked, Codes(g, Severity.Block));

        RunRequest c2 = Base(ImageRoute.IfConfocal, "T");
        c2.PilotPanelsUnlocked = false;
        g = FailClosedGate.Evaluate(c2, pilotT, tm, FullTools());
        OK("C H1-b panel T locked is blocked", g.Blocked, Codes(g, Severity.Block));

        RunRequest c3 = Base(ImageRoute.IfConfocal, "t");   // lower case
        c3.PilotPanelsUnlocked = false;
        g = FailClosedGate.Evaluate(c3, pilotT, tm, FullTools());
        OK("C H1-c panel 't' lower-case still blocked", g.Blocked, Codes(g, Severity.Block));

        // Trailing space + a custom panel JSON: does 'T' slip past?
        RunRequest c4 = Base(ImageRoute.IfConfocal, "T ");
        c4.PanelConfigJson = @"C:\cfg\p.json";
        c4.PilotPanelsUnlocked = false;
        g = FailClosedGate.Evaluate(c4, null, tm, FullTools());
        bool wroteT = false; string panelWritten = "";
        if (!g.Blocked)
        {
            Dictionary<string, string> e = RunEnvironment.BuildStage2(
                c4, null, tm, @"C:\rt\r.json", @"C:\o", @"C:\in", null, false);
            panelWritten = e["IFQ_PANEL"];
            wroteT = PanelRegistry.NormalizeMarkerToken(panelWritten) == "T";
        }
        OK("C H1-d 'T ' + custom JSON cannot become panel T", !wroteT,
           g.Blocked ? "blocked" : "NOT blocked, IFQ_PANEL='" + panelWritten + "'");

        RunRequest c5 = Base(ImageRoute.IfConfocal, "NOSUCHPANEL");
        g = FailClosedGate.Evaluate(c5, null, tm, FullTools());
        OK("C H1-e unknown panel with no JSON blocked", g.Blocked, Codes(g, Severity.Block));

        // Direct assault on the builder.
        RunRequest c6 = Base(ImageRoute.IfConfocal, "   ");
        bool threw = false;
        try { RunEnvironment.BuildStage2(c6, null, tm, "r", "o", @"C:\in", null, false); }
        catch (Exception) { threw = true; }
        OK("C H1-f BuildStage2 refuses a blank panel", threw, "");

        Dictionary<string, string> noPanel = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        noPanel["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "false";
        noPanel["IFQ_MIN_INCLUDED_NUCLEI"] = "0";
        threw = false;
        try { PreStartAssertions.AssertStage2Environment(noPanel, null, ImageRoute.IfConfocal); }
        catch (Exception) { threw = true; }
        OK("C H1-g PreStartAssertions refuses a missing IFQ_PANEL", threw, "");

        // ---- H2 ----------------------------------------------------------
        RunRequest d1 = Base(ImageRoute.IfConfocal, "LEFT");   // all boxes blank
        g = FailClosedGate.Evaluate(d1, left, tm, FullTools());
        OK("C H2-a blank thresholds -> Exploratory", g.Exploratory, "");
        OK("C H2-b blank thresholds -> phrase required",
           g.RequiredPhrases.Contains(FailClosedGate.ExploratoryPhrase),
           string.Join("/", g.RequiredPhrases.ToArray()));
        OK("C H2-c blank thresholds -> folder stamp",
           g.FolderStamps().Contains(FailClosedGate.ExploratoryStamp),
           string.Join("/", g.FolderStamps().ToArray()));

        RunRequest d2 = Base(ImageRoute.IfConfocal, "LEFT");
        d2.Tier = RunTier.Confirmatory;
        g = FailClosedGate.Evaluate(d2, left, tm, FullTools());
        OK("C H2-d blank thresholds at confirmatory tier blocked", g.Blocked, Codes(g, Severity.Block));

        RunRequest d3 = Base(ImageRoute.IfSlideScanner, "LEFT");
        g = FailClosedGate.Evaluate(d3, left, tm, FullTools());
        OK("C H2-e blank thresholds on whole slide blocked", g.Blocked, Codes(g, Severity.Block));

        RunRequest d4 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(d4, left, tm);
        foreach (ChannelDef c in left.AnalysisChannels) { d4.Thresholds[c.Token] = "0"; break; }
        g = FailClosedGate.Evaluate(d4, left, tm, FullTools());
        OK("C H2-f threshold 0 blocked", g.Blocked, Codes(g, Severity.Block));

        RunRequest d5 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(d5, left, tm);
        foreach (ChannelDef c in left.AnalysisChannels) { d5.Thresholds[c.Token] = "abc"; break; }
        g = FailClosedGate.Evaluate(d5, left, tm, FullTools());
        OK("C H2-g non-numeric threshold blocked", g.Blocked, Codes(g, Severity.Block));

        // >>> THE ATTACK: a CUSTOM panel JSON makes panel==null. <<<
        RunRequest d6 = Base(ImageRoute.IfConfocal, "MYCUSTOM");
        d6.PanelConfigJson = @"C:\cfg\p.json";
        d6.Tier = RunTier.Confirmatory;          // strictest tier
        // no thresholds at all
        g = FailClosedGate.Evaluate(d6, null, tm, FullTools());
        Dictionary<string, string> e6 = null;
        if (!g.Blocked)
            e6 = RunEnvironment.BuildStage2(d6, null, tm, @"C:\rt\r.json", @"C:\o", @"C:\in", null, false);
        int thr = 0;
        if (e6 != null) foreach (string k in e6.Keys) if (k.EndsWith("_THRESHOLD", StringComparison.Ordinal)) thr++;
        string rec6 = e6 == null ? "" : RunRecord.Build(
            d6, g, e6, "1.8.0.0", "X64", "f.exe", "x", 0, "complete", "a", "b", null);
        OK("C H2-h CUSTOM PANEL at confirmatory tier with ZERO thresholds is refused or flagged",
           g.Blocked || g.Exploratory,
           "Blocked=" + g.Blocked + " Exploratory=" + g.Exploratory +
           " phrases=" + g.RequiredPhrases.Count + " stamps=" + g.FolderStamps().Count +
           " thresholdVarsEmitted=" + thr +
           " record=" + (rec6.IndexOf("run_classification=THRESHOLDS_FROZEN", StringComparison.Ordinal) >= 0
                          ? "THRESHOLDS_FROZEN" : "flagged"));

        // Same, whole-slide route, where H2 is meant to be a hard block.
        RunRequest d7 = Base(ImageRoute.IfSlideScanner, "MYCUSTOM");
        d7.PanelConfigJson = @"C:\cfg\p.json";
        g = FailClosedGate.Evaluate(d7, null, tm, FullTools());
        OK("C H2-i CUSTOM PANEL on the whole-slide route with zero thresholds refused",
           g.Blocked, "Blocked=" + g.Blocked + " Exploratory=" + g.Exploratory);

        // ---- H3 ----------------------------------------------------------
        RunRequest f1 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(f1, left, tm);
        Dictionary<string, string> ef1 = RunEnvironment.BuildStage2(
            f1, left, tm, "r", "o", @"C:\in", null, false);
        OK("C H3-a route 1 always writes IFQ_MIN_INCLUDED_NUCLEI",
           ef1.ContainsKey("IFQ_MIN_INCLUDED_NUCLEI") && ef1["IFQ_MIN_INCLUDED_NUCLEI"] == "0",
           ef1.ContainsKey("IFQ_MIN_INCLUDED_NUCLEI") ? ef1["IFQ_MIN_INCLUDED_NUCLEI"] : "MISSING");

        RunRequest f2 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(f2, left, tm);
        f2.MinIncludedNuclei = 5;
        g = FailClosedGate.Evaluate(f2, left, tm, FullTools());
        OK("C H3-b floor>0 on an area-endpoint panel blocked",
           g.Blocked, Codes(g, Severity.Block) + " areaMarkers=" + left.AreaMarkers.Count);

        RunRequest f3 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(f3, left, tm);
        f3.MinIncludedNuclei = -1;
        g = FailClosedGate.Evaluate(f3, left, tm, FullTools());
        OK("C H3-c negative floor blocked", g.Blocked, Codes(g, Severity.Block));

        // Can the Advanced box put the floor back behind the UI's back?
        RunRequest f4 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(f4, left, tm);
        f4.AdvancedText = "IFQ_MIN_INCLUDED_NUCLEI=25";
        g = FailClosedGate.Evaluate(f4, left, tm, FullTools());
        Dictionary<string, string> ef4 = RunEnvironment.BuildStage2(
            f4, left, tm, "r", "o", @"C:\in", null, false);
        OK("C H3-d Advanced cannot override the floor",
           g.Blocked && ef4["IFQ_MIN_INCLUDED_NUCLEI"] == "0",
           "blocked=" + g.Blocked + " value=" + ef4["IFQ_MIN_INCLUDED_NUCLEI"]);

        // Route 4 must NOT carry it.
        Dictionary<string, string> legacyPlusFloor = LegacyProfile.BuildEnvironment(
            @"C:\in", @"C:\o", "LEFT", "r", null, null, true, ".*", 0, "classic",
            "layer_aware", -1, false, "auto", "required", "unassigned");
        OK("C H3-e legacy env has no floor", !legacyPlusFloor.ContainsKey("IFQ_MIN_INCLUDED_NUCLEI"), "");
        legacyPlusFloor["IFQ_MIN_INCLUDED_NUCLEI"] = "0";
        threw = false;
        try { PreStartAssertions.AssertStage2Environment(legacyPlusFloor, null, ImageRoute.LegacyFiji172); }
        catch (Exception) { threw = true; }
        OK("C H3-f PreStartAssertions rejects a legacy env carrying the floor", threw, "");

        // ---- H4 ----------------------------------------------------------
        string tmp = Path.Combine(Path.GetTempPath(), "advH4_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tmp);
        threw = false;
        try { PreStartAssertions.AssertOutputDirectoryEmpty(tmp); } catch (Exception) { threw = true; }
        OK("C H4-a empty dir passes", !threw, "");
        File.WriteAllText(Path.Combine(tmp, "stale.csv"), "x");
        threw = false;
        try { PreStartAssertions.AssertOutputDirectoryEmpty(tmp); } catch (Exception) { threw = true; }
        OK("C H4-b non-empty dir throws", threw, "");
        // A directory-only child (e.g. a masks/ folder left behind)
        Directory.Delete(tmp, true); Directory.CreateDirectory(tmp);
        Directory.CreateDirectory(Path.Combine(tmp, "masks"));
        threw = false;
        try { PreStartAssertions.AssertOutputDirectoryEmpty(tmp); } catch (Exception) { threw = true; }
        OK("C H4-c a leftover SUBDIRECTORY also throws", threw, "");
        Directory.Delete(tmp, true);

        RunRequest h1 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(h1, left, tm);
        h1.AdvancedText = "IFQ_ALLOW_NONEMPTY_OUTPUT=true";
        g = FailClosedGate.Evaluate(h1, left, tm, FullTools());
        Dictionary<string, string> eh1 = RunEnvironment.BuildStage2(
            h1, left, tm, "r", "o", @"C:\in", null, false);
        OK("C H4-d Advanced cannot set IFQ_ALLOW_NONEMPTY_OUTPUT",
           g.Blocked && eh1["IFQ_ALLOW_NONEMPTY_OUTPUT"] == "false",
           "blocked=" + g.Blocked + " value=" + eh1["IFQ_ALLOW_NONEMPTY_OUTPUT"]);

        Dictionary<string, string> tampered = new Dictionary<string, string>(eh1, StringComparer.OrdinalIgnoreCase);
        tampered["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "true";
        threw = false;
        try { PreStartAssertions.AssertStage2Environment(tampered, null, ImageRoute.IfConfocal); }
        catch (Exception) { threw = true; }
        OK("C H4-e PreStartAssertions catches a tampered ALLOW_NONEMPTY", threw, "");

        RunRequest h2 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(h2, left, tm);
        h2.OutputBase = "";
        g = FailClosedGate.Evaluate(h2, left, tm, FullTools());
        OK("C H4-f empty output base blocked", g.Blocked, Codes(g, Severity.Block));

        // ---- H5 ----------------------------------------------------------
        RunRequest i1 = Base(ImageRoute.IfConfocal, "LEFT");   // blank thresholds
        g = FailClosedGate.Evaluate(i1, left, tm, FullTools());
        Dictionary<string, string> ei1 = RunEnvironment.BuildStage2(
            i1, left, tm, "r", "o", @"C:\in", null, false);
        string rec = RunRecord.Build(i1, g, ei1, "1.8.0.0", "X64", "f.exe", "x", 0,
                                     "complete", "a", "b", null);
        OK("C H5-a record says EXPLORATORY_DO_NOT_AGGREGATE",
           rec.IndexOf("run_classification=EXPLORATORY_DO_NOT_AGGREGATE", StringComparison.Ordinal) >= 0, "");
        OK("C H5-b record says thresholds_frozen=false",
           rec.IndexOf("thresholds_frozen=false", StringComparison.Ordinal) >= 0, "");
        OK("C H5-c per-channel policy present",
           rec.IndexOf("adaptive_otsu_exploratory", StringComparison.Ordinal) >= 0, "");
        OK("C H5-d marker text quotes the measured failure",
           RunRecord.ExploratoryMarkerText(i1, g).IndexOf("4.95", StringComparison.Ordinal) >= 0, "");

        RunRequest i2 = Base(ImageRoute.IfConfocal, "LEFT");
        FreezeAll(i2, left, tm);
        g = FailClosedGate.Evaluate(i2, left, tm, FullTools());
        Dictionary<string, string> ei2 = RunEnvironment.BuildStage2(
            i2, left, tm, "r", "o", @"C:\in", null, false);
        string rec2 = RunRecord.Build(i2, g, ei2, "1.8.0.0", "X64", "f.exe", "x", 0,
                                      "complete", "a", "b", null);
        bool allFrozen = true;
        foreach (ChannelDef c in left.AnalysisChannels) if (!tm.Contains(c.Token)) allFrozen = false;
        OK("C H5-e a fully frozen run is NOT flagged (panel LEFT fully thresholdable=" + allFrozen + ")",
           !allFrozen || rec2.IndexOf("run_classification=THRESHOLDS_FROZEN", StringComparison.Ordinal) >= 0,
           g.Exploratory ? "still exploratory: " + string.Join(";", g.ThresholdPolicy.ToArray()) : "frozen");

        // Route 4 is exploratory by construction (no thresholds possible). Is it marked?
        RunRequest i3 = Base(ImageRoute.LegacyFiji172, "LEFT");
        g = FailClosedGate.Evaluate(i3, left, tm, FullTools());
        Dictionary<string, string> ei3 = LegacyProfile.BuildEnvironment(
            @"C:\in", @"C:\o", "LEFT", "r", null, null, true, ".*", 0, "classic",
            "layer_aware", -1, false, "auto", "required", "unassigned");
        string rec3 = RunRecord.Build(i3, g, ei3, "1.8.0.0", "X64", "f.exe", "x", 0,
                                      "complete", "a", "b", null);
        OK("C H5-f ROUTE 4 (always adaptive Otsu) is marked EXPLORATORY",
           rec3.IndexOf("run_classification=EXPLORATORY_DO_NOT_AGGREGATE", StringComparison.Ordinal) >= 0,
           "Exploratory=" + g.Exploratory + " stamps=" + g.FolderStamps().Count +
           " classification=" + (rec3.IndexOf("THRESHOLDS_FROZEN", StringComparison.Ordinal) >= 0
                                 ? "THRESHOLDS_FROZEN" : "EXPLORATORY"));
    }

    // ================================================================ SECTION D
    static void SectionD()
    {
        Dictionary<string, PanelDef> panels = Panels();
        HashSet<string> tm = ThreshMarkers();
        PanelDef left = panels["LEFT"];
        string groovy = File.ReadAllText(groovyPath);

        // Every IFQ_ name the engine actually reads via env*(...) helpers.
        HashSet<string> engine = new HashSet<string>(StringComparer.Ordinal);
        foreach (Match m in Regex.Matches(groovy, "env(?:Or|Int|Double|Bool|Flag)?\\s*\\(\\s*\"(IFQ_[A-Z0-9_]+)\""))
            engine.Add(m.Groups[1].Value);
        Console.WriteLine("      engine reads " + engine.Count + " literal IFQ_ names via env*()");

        List<string> rejected = new List<string>();
        foreach (string name in engine)
        {
            RunRequest r = Base(ImageRoute.IfConfocal, "LEFT");
            FreezeAll(r, left, tm);
            r.AdvancedText = name + "=1";
            GateResult g = FailClosedGate.Evaluate(r, left, tm, FullTools());
            bool advBlock = false;
            foreach (GateFinding f in g.OfSeverity(Severity.Block))
                if (f.Code.StartsWith("ADV_", StringComparison.Ordinal)) advBlock = true;
            if (advBlock && !EnvSurface.ProtectedKeys.Contains(name)) rejected.Add(name);
        }
        OK("D d1 no engine-read IFQ_ name is rejected as ADV_UNKNOWN_KEY",
           rejected.Count == 0,
           rejected.Count == 0 ? "" : "REJECTED: " + string.Join(", ", rejected.ToArray()));

        // Route 4: v1.7.2 accepted anything matching ^IFQ_[A-Z0-9_]+$ that was
        // not one of its own 19. Does route 4 still accept those?
        string[] legacyAdvanced = new string[]
        {
            "IFQ_MIN_INCLUDED_NUCLEI=3",     // v1.7.2 accepted; the ONLY way to set it then
            "IFQ_KRT5_THRESHOLD=512",        // v1.7.2 accepted; the ONLY way to freeze then
            "IFQ_DAPI_METHOD=otsu",
            "IFQ_STUDY_TAG=pilot7"           // v1.7.2 accepted (shape-only check)
        };
        foreach (string line in legacyAdvanced)
        {
            RunRequest r = Base(ImageRoute.LegacyFiji172, "LEFT");
            r.AdvancedText = line;
            GateResult g = FailClosedGate.Evaluate(r, left, tm, FullTools());
            bool advBlock = false; string code = "";
            foreach (GateFinding f in g.OfSeverity(Severity.Block))
                if (f.Code.StartsWith("ADV_", StringComparison.Ordinal)) { advBlock = true; code = f.Code; }
            OK("D d2 route 4 still accepts what v1.7.2 accepted: " + line, !advBlock, code);
        }
    }
}
