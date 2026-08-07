// Drives the REAL MainForm out of the built IFQuantLauncher-v1.8.0.exe by
// reflection, to see what a user actually sees. No launcher source is copied.
using System;
using System.Collections;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

internal static class UiHole
{
    static object form;
    static Type t;

    static object F(string name)
    {
        FieldInfo fi = t.GetField(name, BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public);
        if (fi == null) throw new Exception("no field " + name);
        return fi.GetValue(form);
    }
    static void Call(string name)
    {
        MethodInfo mi = t.GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public);
        if (mi == null) throw new Exception("no method " + name);
        mi.Invoke(form, null);
    }
    static string Summary() { return ((Label)F("gateSummaryLabel")).Text; }
    static bool RunEnabled() { return ((Button)F("runButton")).Enabled; }

    [STAThread]
    static int Main(string[] args)
    {
        Assembly asm = Assembly.LoadFrom(args[0]);
        t = asm.GetType("IFQuantLauncher.MainForm", false);
        if (t == null)
            foreach (Type x in asm.GetTypes())
                if (x.Name == "MainForm") { t = x; break; }
        Console.WriteLine("MainForm type: " + t.FullName);
        form = Activator.CreateInstance(t, true);
        Form f = (Form)form;
        f.Opacity = 0; f.ShowInTaskbar = false; f.WindowState = FormWindowState.Minimized;
        f.Show();
        Application.DoEvents();

        string sandbox = Path.Combine(Path.GetTempPath(), "advui_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(sandbox);
        File.WriteAllBytes(Path.Combine(sandbox, "fiji-windows-x64.exe"), new byte[0]);
        File.WriteAllBytes(Path.Combine(sandbox, "fiji-windows-arm64.exe"), new byte[0]);
        string cfg = Path.Combine(sandbox, "mypanel.json");
        File.WriteAllText(cfg, "{}");

        ((TextBox)F("fijiBox")).Text = sandbox;
        ((TextBox)F("outputBaseBox")).Text = sandbox;
        ((TextBox)F("inputBox")).Text = sandbox;
        ((TextBox)F("advancedBox")).Text = "";
        ((TextBox)F("panelConfigBox")).Text = "";

        int bad = 0;

        // ---- baseline: built-in panel LEFT, no thresholds -> must be FLAGGED
        ((ComboBox)F("panelBox")).Text = "LEFT";
        Call("RebuildThresholdGrid");
        Call("RefreshGateSummary");
        Console.WriteLine();
        Console.WriteLine("[LEFT, no thresholds, exploratory tier]");
        Console.WriteLine("  runEnabled=" + RunEnabled());
        Console.WriteLine("  summary   =" + Summary());
        if (Summary().IndexOf("FLAGGED", StringComparison.Ordinal) < 0)
        { Console.WriteLine("  !! expected FLAGGED"); bad++; }

        // ---- the hole: a CUSTOM panel key + custom panel JSON, no thresholds
        ((ComboBox)F("panelBox")).Text = "MYCUSTOM";
        ((TextBox)F("panelConfigBox")).Text = cfg;
        SelectTier("confirmatory");
        Call("RebuildThresholdGrid");
        Call("RefreshGateSummary");
        Console.WriteLine();
        Console.WriteLine("[MYCUSTOM + custom JSON, NO thresholds, CONFIRMATORY tier]");
        Console.WriteLine("  runEnabled     =" + RunEnabled());
        Console.WriteLine("  summary        =" + Summary());
        Console.WriteLine("  thresholdNote  =" + ((Label)F("thresholdNoteLabel")).Text);
        Console.WriteLine("  thresholdBoxes =" +
            ((ICollection)F("thresholdBoxes")).Count);
        if (RunEnabled() && Summary().StartsWith("Ready.", StringComparison.Ordinal))
        {
            Console.WriteLine("  !! REACHABLE: a confirmatory-tier run with zero frozen");
            Console.WriteLine("  !! thresholds is GREEN and startable.");
            bad++;
        }

        // ---- route 2 (whole slide) with the same custom panel
        ((ComboBox)F("routeBox")).SelectedIndex = 1;
        ((TextBox)F("wsiInputBox")).Text = Path.Combine(sandbox, "s.vsi");
        File.WriteAllBytes(Path.Combine(sandbox, "s.vsi"), new byte[0]);
        ((TextBox)F("wsiOutputBox")).Text = sandbox;
        string qp = Path.Combine(sandbox, "QuPath-0.7.0 (console).exe");
        File.WriteAllBytes(qp, new byte[0]);
        ((TextBox)F("quPathBox")).Text = qp;
        ((TextBox)F("pythonBox")).Text = cfg;
        ((TextBox)F("slideMetadataBox")).Text = cfg;
        Call("RefreshGateSummary");
        Console.WriteLine();
        Console.WriteLine("[route 2 whole slide, MYCUSTOM, NO thresholds, all tools present]");
        Console.WriteLine("  runEnabled=" + RunEnabled());
        Console.WriteLine("  summary   =" + Summary());
        if (RunEnabled())
        { Console.WriteLine("  !! whole-slide hard block on adaptive Otsu did NOT fire"); bad++; }

        // ---- route 4 legacy: what does the user see about threshold policy?
        ((ComboBox)F("routeBox")).SelectedIndex = 3;
        ((ComboBox)F("panelBox")).Text = "LEFT";
        ((TextBox)F("panelConfigBox")).Text = "";
        Call("RebuildThresholdGrid");
        Call("RefreshGateSummary");
        Console.WriteLine();
        Console.WriteLine("[route 4 legacy, panel LEFT]");
        Console.WriteLine("  runEnabled=" + RunEnabled());
        Console.WriteLine("  summary   =" + Summary());

        // route 4 reproducing a v1.7.2 run that set the floor in Advanced
        ((TextBox)F("advancedBox")).Text = "IFQ_MIN_INCLUDED_NUCLEI=3";
        Call("RefreshGateSummary");
        Console.WriteLine();
        Console.WriteLine("[route 4 legacy + Advanced IFQ_MIN_INCLUDED_NUCLEI=3 (v1.7.2 accepted this)]");
        Console.WriteLine("  runEnabled=" + RunEnabled());
        Console.WriteLine("  summary   =" + Summary());
        if (!RunEnabled())
        { Console.WriteLine("  !! route 4 refuses an Advanced setting v1.7.2 accepted"); bad++; }
        ((TextBox)F("advancedBox")).Text = "";
        Call("RefreshGateSummary");

        // ---- route 3: confirm the picker refuses it
        int before = ((ComboBox)F("routeBox")).SelectedIndex;
        ((ComboBox)F("routeBox")).SelectedIndex = 2;
        Application.DoEvents();
        Console.WriteLine();
        Console.WriteLine("[route 3 selection attempt]");
        Console.WriteLine("  indexBefore=" + before +
                          " indexAfter=" + ((ComboBox)F("routeBox")).SelectedIndex);
        Console.WriteLine("  routeHelp  =" +
            ((Label)F("routeHelpLabel")).Text.Replace("\r\n", " / ").Substring(0,
                Math.Min(220, ((Label)F("routeHelpLabel")).Text.Replace("\r\n", " / ").Length)));
        if (((ComboBox)F("routeBox")).SelectedIndex == 2)
        { Console.WriteLine("  !! route 3 WAS selectable"); bad++; }

        try { Directory.Delete(sandbox, true); } catch { }
        Console.WriteLine();
        Console.WriteLine("UI_PROBLEMS=" + bad);
        return bad;
    }

    static void SelectTier(string key)
    {
        ComboBox tier = (ComboBox)F("tierBox");
        for (int i = 0; i < tier.Items.Count; i++)
        {
            string s = Convert.ToString(tier.Items[i], CultureInfo.InvariantCulture);
            if (s != null && s.StartsWith(key, StringComparison.OrdinalIgnoreCase))
            { tier.SelectedIndex = i; return; }
        }
        Console.WriteLine("  (tier '" + key + "' not found; items: " + tier.Items.Count + ")");
    }
}
