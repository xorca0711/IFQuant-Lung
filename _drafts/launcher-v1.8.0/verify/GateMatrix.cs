// =====================================================================
// GateMatrix.cs -- one row per fail-closed rule, executed.
//
// The packaged --self-test returns a single number. This prints the matrix,
// so each of H1-H5 has its own visible evidence rather than being covered by
// an aggregate pass. It links the SHIPPING IFQuantLauncher.Routing.cs.
// =====================================================================

using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using IFQuantLauncher.Routing;

namespace IFQuantLauncher.LegacyCheck
{
    internal static class GateMatrix
    {
        private static int checks;
        private static int failures;

        private static void Row(string rule, string scenario, bool ok, string observed)
        {
            checks++;
            if (!ok) failures++;
            Console.WriteLine(
                (ok ? "  PASS  " : "  FAIL  ") + rule.PadRight(4) + scenario.PadRight(62) +
                observed);
        }

        private static RunRequest Base(ImageRoute route, string panel)
        {
            RunRequest r = new RunRequest();
            r.Route = route;
            r.PanelKey = panel;
            r.OutputBase = @"C:\out";
            r.Tier = RunTier.Exploratory;
            return r;
        }

        private static string Verdict(GateResult g)
        {
            if (g.Blocked) return "BLOCK  " + FirstCode(g, Severity.Block);
            if (g.NeedsConfirmation)
                return "CONFIRM \"" + string.Join("\" + \"", g.RequiredPhrases.ToArray()) +
                       "\" " + FirstCode(g, Severity.Confirm);
            return "allow";
        }

        private static string FirstCode(GateResult g, Severity s)
        {
            foreach (GateFinding f in g.OfSeverity(s)) return f.Code;
            return "";
        }

        private static string Threw(Action action)
        {
            try { action(); return null; }
            catch (Exception ex) { return ex.GetType().Name; }
        }

        public static int Run(string pipelinePath, string registryPath)
        {
            string groovy = File.ReadAllText(pipelinePath, Encoding.UTF8);
            Dictionary<string, PanelDef> panels = PanelRegistry.ParseFromPipeline(groovy);
            HashSet<string> thresholdMarkers = PanelRegistry.ParseThresholdMarkerTokens(groovy);
            PanelDef left = panels["LEFT"];
            PanelDef pilot = panels["T"];
            Dictionary<string, string> defaultRoles =
                MarkerRoleDefaults.ParseFromRegistry(
                    File.ReadAllText(registryPath, Encoding.UTF8));

            Console.WriteLine("Fail-closed rule matrix (v" + LauncherBuild.Version + ")");
            Console.WriteLine("panels parsed from the engine: " + panels.Count +
                              ";  markers the engine thresholds: " + thresholdMarkers.Count);
            Console.WriteLine("LEFT analysis channels: " + left.AnalysisChannels.Count +
                              ", of which area endpoints: " + left.AreaMarkers.Count);
            Console.WriteLine();

            // ---- R3 ----------------------------------------------------
            RouteSpec he = RouteCatalog.Describe(ImageRoute.HeBrightfield);
            Row("R3", "route 3 is listed in the picker",
                Array.IndexOf(RouteCatalog.All(), ImageRoute.HeBrightfield) >= 0,
                "index " + Array.IndexOf(RouteCatalog.All(), ImageRoute.HeBrightfield));
            // D4. Everything below that could differ when the one line is
            // flipped is written against the flag, so the whole matrix stays
            // green on a build with route 3 turned on. Only the row that
            // deliberately documents THIS build's setting is unconditional.
            bool r3Off = !LauncherBuild.BrightfieldRouteEnabled;
            Row("R3", "route 3 is not available and carries a reason (flag off)",
                r3Off
                    ? !he.Available && !string.IsNullOrEmpty(he.UnavailableReason)
                    : he.Available && string.IsNullOrEmpty(he.UnavailableReason),
                "Available=" + he.Available);
            RunRequest heReq = Base(ImageRoute.HeBrightfield, "LEFT");
            foreach (ChannelDef c0 in left.AnalysisChannels) heReq.Thresholds[c0.Token] = "500";
            GateResult heGate = FailClosedGate.Evaluate(heReq, left, thresholdMarkers, null);
            Row("R3", "gate blocks route 3 with an otherwise valid request (flag off)",
                r3Off
                    ? heGate.Blocked && !heGate.NeedsConfirmation
                    : !heGate.Blocked,
                Verdict(heGate));
            string envError = null;
            try
            {
                RunEnvironment.BuildStage2(heReq, left, thresholdMarkers, "r", "o",
                                           Path.GetTempPath(), null, false);
            }
            catch (Exception ex) { envError = ex.GetType().Name; }
            Row("R3", "environment builder emits nothing for route 3",
                envError != null, envError ?? "RETURNED AN ENVIRONMENT");
            // Deliberately a COMPLETE, valid routes-1/2 environment: it carries
            // IFQ_PANEL, IFQ_ALLOW_NONEMPTY_OUTPUT=false and
            // IFQ_MIN_INCLUDED_NUCLEI, so nothing except the route rule itself
            // can be what throws. A legacy fixture here would have passed this
            // row for the wrong reason (its missing nuclei floor).
            Dictionary<string, string> validEnv = RunEnvironment.BuildStage2(
                Base(ImageRoute.IfConfocal, "LEFT"), left, thresholdMarkers,
                "reg", "out", Path.GetTempPath(), null, false);
            string assertError = Threw(delegate
            {
                PreStartAssertions.AssertStage2Environment(
                    validEnv, null, ImageRoute.HeBrightfield);
            });
            Row("R3", "pre-start assertion refuses route 3 even with a valid env (flag off)",
                r3Off == (assertError != null), assertError ?? "ALLOWED THE RUN");
            // D4. Written against the flag's VALUE, not against the assumption
            // that it is false, so flipping the one line advertised to the user
            // does not turn this row red for the wrong reason.
            Row("R3", "availability, reason and stage list all follow the build flag",
                he.Available == LauncherBuild.BrightfieldRouteEnabled &&
                he.Available != !string.IsNullOrEmpty(he.UnavailableReason) &&
                he.Available == (he.Stages.Count > 0),
                "flag=" + LauncherBuild.BrightfieldRouteEnabled +
                " Available=" + he.Available + " stages=" + he.Stages.Count);

            // D5. The stage 1 builder is the one a brightfield wiring reaches
            // first (route 3 is the only route besides 2 with RequiresQuPath),
            // and it had no guard: it returned a complete seven-variable
            // stage 1 environment for a route-3 request.
            RunRequest heStage1 = Base(ImageRoute.HeBrightfield, "LEFT");
            heStage1.WsiInput = Path.GetTempPath();
            heStage1.WsiOutput = Path.GetTempPath();
            string stage1Error = Threw(delegate
            {
                RunEnvironment.BuildStage1(heStage1, "LEFT");
            });
            Row("R3", "stage 1 builder emits nothing for route 3",
                stage1Error != null, stage1Error ?? "RETURNED AN ENVIRONMENT");

            RunRequest fijiStage1 = Base(ImageRoute.IfConfocal, "LEFT");
            fijiStage1.WsiInput = Path.GetTempPath();
            fijiStage1.WsiOutput = Path.GetTempPath();
            string stage1OnFiji = Threw(delegate
            {
                RunEnvironment.BuildStage1(fijiStage1, "LEFT");
            });
            Row("R3", "stage 1 builder emits nothing for a route with no stage 1",
                stage1OnFiji != null, stage1OnFiji ?? "RETURNED AN ENVIRONMENT");

            // The safety property that must hold in EVERY configuration of the
            // flag: one input decides selectability, and neither builder will
            // produce a route-3 environment until a brightfield engine is
            // actually wired in. With the flag on they raise
            // NotImplementedException instead of InvalidOperationException --
            // still nothing runs. Stated this way so that flipping the one line
            // leaves the whole matrix green, which is what "one line" means.
            Row("R3", "one flag decides it, and no builder honours it either way",
                RouteCatalog.IsAvailable(ImageRoute.HeBrightfield) ==
                    LauncherBuild.BrightfieldRouteEnabled &&
                envError != null && stage1Error != null,
                "LauncherBuild.BrightfieldRouteEnabled=" +
                LauncherBuild.BrightfieldRouteEnabled);

            // ---- R? undefined route id ---------------------------------
            // D5. RouteCatalog.Describe used to leave a fresh RouteSpec's field
            // initialisers in place for a route id it did not define, so
            // Available stayed TRUE: the gate said nothing, BuildStage2 emitted
            // 21 variables and PreStartAssertions passed.
            ImageRoute undefined = (ImageRoute)7;
            RouteSpec undefinedSpec = RouteCatalog.Describe(undefined);
            Row("R?", "an undefined route id is not available and says why",
                !undefinedSpec.Available &&
                !string.IsNullOrEmpty(undefinedSpec.UnavailableReason) &&
                undefinedSpec.Stages.Count == 0,
                "Available=" + undefinedSpec.Available +
                " stages=" + undefinedSpec.Stages.Count);

            RunRequest undefinedRequest = Base(undefined, "LEFT");
            foreach (ChannelDef c in left.AnalysisChannels)
                undefinedRequest.Thresholds[c.Token] = "500";
            GateResult undefinedGate =
                FailClosedGate.Evaluate(undefinedRequest, left, thresholdMarkers, null);
            Row("R?", "gate blocks an undefined route id",
                undefinedGate.Blocked, Verdict(undefinedGate));

            string undefinedBuild = Threw(delegate
            {
                RunEnvironment.BuildStage2(undefinedRequest, left, thresholdMarkers,
                                           "r", "o", Path.GetTempPath(), null, false);
            });
            Row("R?", "environment builder emits nothing for an undefined route id",
                undefinedBuild != null, undefinedBuild ?? "RETURNED AN ENVIRONMENT");

            string undefinedAssert = Threw(delegate
            {
                PreStartAssertions.AssertStage2Environment(validEnv, null, undefined);
            });
            Row("R?", "pre-start assertion refuses an undefined route id",
                undefinedAssert != null, undefinedAssert ?? "ALLOWED THE RUN");

            // ---- H1 ----------------------------------------------------
            RunRequest noPanel = Base(ImageRoute.IfConfocal, null);
            GateResult g = FailClosedGate.Evaluate(noPanel, null, thresholdMarkers, null);
            Row("H1", "no panel selected", g.Blocked, Verdict(g));

            RunRequest tLocked = Base(ImageRoute.IfConfocal, "T");
            g = FailClosedGate.Evaluate(tLocked, pilot, thresholdMarkers, null);
            Row("H1", "panel T while pilot panels are locked", g.Blocked, Verdict(g));

            RunRequest tUnlocked = Base(ImageRoute.IfConfocal, "T");
            tUnlocked.PilotPanelsUnlocked = true;
            g = FailClosedGate.Evaluate(tUnlocked, pilot, thresholdMarkers, null);
            Row("H1", "panel T after explicit unlock",
                !g.Blocked && g.RequiredPhrases.Contains(FailClosedGate.PilotPhrase) &&
                g.FolderStamps().Contains(FailClosedGate.PilotStamp), Verdict(g));

            RunRequest unknown = Base(ImageRoute.IfConfocal, "NOT_A_PANEL");
            g = FailClosedGate.Evaluate(unknown, null, thresholdMarkers, null);
            Row("H1", "panel name the engine does not declare", g.Blocked, Verdict(g));

            Dictionary<string, string> env = RunEnvironment.BuildStage2(
                Base(ImageRoute.IfConfocal, "LEFT"), left, thresholdMarkers,
                "reg", "out", Path.GetTempPath(), null, false);
            Row("H1", "IFQ_PANEL is always written",
                env.ContainsKey("IFQ_PANEL") && env["IFQ_PANEL"] == "LEFT",
                "IFQ_PANEL=" + env["IFQ_PANEL"]);
            string h1Assert = null;
            try
            {
                Dictionary<string, string> stripped =
                    new Dictionary<string, string>(env, StringComparer.OrdinalIgnoreCase);
                stripped.Remove("IFQ_PANEL");
                PreStartAssertions.AssertStage2Environment(
                    stripped, null, ImageRoute.IfConfocal);
            }
            catch (Exception ex) { h1Assert = ex.GetType().Name; }
            Row("H1", "pre-start assertion catches a missing IFQ_PANEL",
                h1Assert != null, h1Assert ?? "ALLOWED THE RUN");

            // ---- H2 ----------------------------------------------------
            RunRequest blank = Base(ImageRoute.IfConfocal, "LEFT");
            g = FailClosedGate.Evaluate(blank, left, thresholdMarkers, null);
            Row("H2", "blank thresholds on route 1",
                !g.Blocked && g.Exploratory &&
                g.RequiredPhrases.Contains(FailClosedGate.ExploratoryPhrase) &&
                g.FolderStamps().Contains(FailClosedGate.ExploratoryStamp), Verdict(g));

            RunRequest blankSlide = Base(ImageRoute.IfSlideScanner, "LEFT");
            blankSlide.WsiInput = "in"; blankSlide.WsiOutput = "out";
            g = FailClosedGate.Evaluate(blankSlide, left, thresholdMarkers, null);
            Row("H2", "blank thresholds on route 2 (whole slide)", g.Blocked, Verdict(g));

            RunRequest blankConfirm = Base(ImageRoute.IfConfocal, "LEFT");
            blankConfirm.Tier = RunTier.Confirmatory;
            g = FailClosedGate.Evaluate(blankConfirm, left, thresholdMarkers, null);
            Row("H2", "blank thresholds at confirmatory tier", g.Blocked, Verdict(g));

            RunRequest allFrozen = Base(ImageRoute.IfConfocal, "LEFT");
            foreach (ChannelDef c in left.AnalysisChannels) allFrozen.Thresholds[c.Token] = "500";
            g = FailClosedGate.Evaluate(allFrozen, left, thresholdMarkers, null);
            Row("H2", "every channel frozen clears the flag",
                !g.Blocked && !g.Exploratory && !g.NeedsConfirmation, Verdict(g));

            RunRequest zero = Base(ImageRoute.IfConfocal, "LEFT");
            foreach (ChannelDef c in left.AnalysisChannels) zero.Thresholds[c.Token] = "500";
            zero.Thresholds[left.AnalysisChannels[0].Token] = "0";
            g = FailClosedGate.Evaluate(zero, left, thresholdMarkers, null);
            Row("H2", "threshold of 0 (every pixel a candidate)", g.Blocked, Verdict(g));

            RunRequest junk = Base(ImageRoute.IfConfocal, "LEFT");
            foreach (ChannelDef c in left.AnalysisChannels) junk.Thresholds[c.Token] = "500";
            junk.Thresholds[left.AnalysisChannels[0].Token] = "high";
            g = FailClosedGate.Evaluate(junk, left, thresholdMarkers, null);
            Row("H2", "non-numeric threshold", g.Blocked, Verdict(g));

            Dictionary<string, string> frozenEnv = RunEnvironment.BuildStage2(
                allFrozen, left, thresholdMarkers, "reg", "out", Path.GetTempPath(), null, false);
            List<string> emitted = new List<string>();
            foreach (string key in frozenEnv.Keys)
                if (key.EndsWith("_THRESHOLD", StringComparison.Ordinal)) emitted.Add(key);
            emitted.Sort(StringComparer.Ordinal);
            Row("H2", "the engine's own token names are emitted",
                emitted.Contains("IFQ_T1A_THRESHOLD") &&
                !frozenEnv.ContainsKey("IFQ_PDPN_THRESHOLD"),
                string.Join(",", emitted.ToArray()));

            RunRequest typo = Base(ImageRoute.IfConfocal, "LEFT");
            foreach (ChannelDef c in left.AnalysisChannels) typo.Thresholds[c.Token] = "500";
            typo.AdvancedText = "IFQ_PDPN_THRESHOLD=400";
            g = FailClosedGate.Evaluate(typo, left, thresholdMarkers, null);
            Row("H2", "IFQ_PDPN_THRESHOLD on panel LEFT (silent no-op in v1.7.2)",
                g.Blocked, Verdict(g));

            // ---- H2, custom / unresolvable panels (D1) ------------------
            // v1.8.0.0 guarded the whole H2 block on `panel != null`, and
            // MainForm.ResolveSelectedPanel returned null for any custom panel
            // key. A custom key + a custom panel JSON + zero thresholds went
            // GREEN, and the run record said THRESHOLDS_FROZEN.
            RunRequest custom = Base(ImageRoute.IfConfocal, "MYCUSTOM");
            custom.PanelConfigJson = @"C:\fixture\custom_panel.json";
            g = FailClosedGate.Evaluate(custom, null, thresholdMarkers, null);
            Row("H2", "custom panel key with no channel list is NOT frozen",
                !g.Blocked && g.Exploratory &&
                g.RequiredPhrases.Contains(FailClosedGate.ExploratoryPhrase) &&
                g.FolderStamps().Contains(FailClosedGate.ExploratoryStamp), Verdict(g));

            string customRecord = RunRecord.Build(
                custom, g, env, "1.8.0.0", "ARM64", "fiji.exe", "launcher_exe",
                0, "complete", "p", "r", null);
            Row("H2", "its run record says EXPLORATORY, not THRESHOLDS_FROZEN",
                customRecord.IndexOf("run_classification=EXPLORATORY_DO_NOT_AGGREGATE",
                                     StringComparison.Ordinal) >= 0 &&
                customRecord.IndexOf("thresholds_frozen=false",
                                     StringComparison.Ordinal) >= 0,
                customRecord.IndexOf("thresholds_frozen=false", StringComparison.Ordinal) >= 0
                    ? "thresholds_frozen=false"
                    : "thresholds_frozen=TRUE");

            RunRequest customConfirm = Base(ImageRoute.IfConfocal, "MYCUSTOM");
            customConfirm.PanelConfigJson = @"C:\fixture\custom_panel.json";
            customConfirm.Tier = RunTier.Confirmatory;
            g = FailClosedGate.Evaluate(customConfirm, null, thresholdMarkers, null);
            Row("H2", "no channel list at confirmatory tier", g.Blocked, Verdict(g));

            RunRequest customSlide = Base(ImageRoute.IfSlideScanner, "MYCUSTOM");
            customSlide.PanelConfigJson = @"C:\fixture\custom_panel.json";
            customSlide.WsiInput = "in"; customSlide.WsiOutput = "out";
            g = FailClosedGate.Evaluate(customSlide, null, thresholdMarkers, null);
            Row("H2", "no channel list on the whole-slide route", g.Blocked, Verdict(g));

            RunRequest badConfig = Base(ImageRoute.IfConfocal, "MYCUSTOM");
            badConfig.PanelConfigJson = @"C:\fixture\custom_panel.json";
            badConfig.PanelResolutionError = "the custom panel JSON could not be read";
            g = FailClosedGate.Evaluate(badConfig, null, thresholdMarkers, null);
            Row("H2", "an unreadable custom panel JSON is a hard block",
                g.Blocked, Verdict(g));

            // ...and a custom panel that DOES parse is fully supported, not
            // merely blocked: a real channel list, a real threshold grid, and
            // the variable actually emitted.
            const string CustomJson =
                "{\"panels\":{\"MYCUSTOM\":{\"label\":\"probe\",\"channels\":[" +
                "{\"idx\":1,\"marker\":\"DAPI\",\"role\":\"nuclear\"}," +
                "{\"idx\":2,\"marker\":\"KRT5\",\"role\":\"cyto\",\"areaMarker\":true}," +
                "{\"idx\":3,\"marker\":\"SCGB3A2\"}]}}}";
            CustomPanelParse parsed = CustomPanelRegistry.Parse(
                CustomJson, defaultRoles, new List<string>(panels.Keys),
                left.ChannelsAreThresholdable);
            PanelDef customPanel = parsed.Ok ? parsed.Panels["MYCUSTOM"] : null;
            Row("H2", "a valid custom panel JSON yields a real channel list",
                parsed.Ok && customPanel != null &&
                customPanel.AnalysisChannels.Count == 2 &&
                customPanel.AreaMarkers.Count == 1,
                parsed.Ok
                    ? customPanel.AnalysisChannels.Count + " analysis channels, " +
                      customPanel.AreaMarkers.Count + " area endpoint(s)"
                    : "PARSE FAILED: " + parsed.Error);

            // SCGB3A2 carries no explicit role and is not on the engine's
            // thresholdMarkers list. The role comes from the registry's
            // default_role, exactly as the engine fills it, and the threshold
            // variable exists because IF_Quant_Pipeline.groovy:873-882 extends
            // the lookup to every non-nuclear channel of every declared panel.
            RunRequest customFrozen = Base(ImageRoute.IfConfocal, "MYCUSTOM");
            customFrozen.PanelConfigJson = @"C:\fixture\custom_panel.json";
            if (customPanel != null)
                foreach (ChannelDef c in customPanel.AnalysisChannels)
                    customFrozen.Thresholds[c.Token] = "480";
            g = FailClosedGate.Evaluate(customFrozen, customPanel, thresholdMarkers, null);
            Dictionary<string, string> customEnv = customPanel == null ? null :
                RunEnvironment.BuildStage2(customFrozen, customPanel, thresholdMarkers,
                                           "reg", "out", Path.GetTempPath(), null, false);
            Row("H2", "freezing every custom channel clears the flag",
                customPanel != null && !g.Blocked && !g.Exploratory && !g.NeedsConfirmation &&
                customEnv != null &&
                customEnv.ContainsKey("IFQ_KRT5_THRESHOLD") &&
                customEnv.ContainsKey("IFQ_SCGB3A2_THRESHOLD") &&
                customEnv["IFQ_PANEL"] == "MYCUSTOM",
                Verdict(g));

            RunRequest customBlank = Base(ImageRoute.IfConfocal, "MYCUSTOM");
            customBlank.PanelConfigJson = @"C:\fixture\custom_panel.json";
            g = FailClosedGate.Evaluate(customBlank, customPanel, thresholdMarkers, null);
            Row("H2", "blank thresholds on a custom panel are exploratory",
                !g.Blocked && g.Exploratory &&
                g.RequiredPhrases.Contains(FailClosedGate.ExploratoryPhrase), Verdict(g));

            Row("H2", "a malformed custom panel JSON never yields a partial list",
                !CustomPanelRegistry.Parse("{ not json", defaultRoles, null, true).Ok &&
                !CustomPanelRegistry.Parse("{\"panels\":{}}", defaultRoles, null, true).Ok &&
                !CustomPanelRegistry.Parse(
                    "{\"panels\":{\"X\":{\"channels\":[]}}}", defaultRoles, null, true).Ok &&
                !CustomPanelRegistry.Parse(
                    "{\"panels\":{\"X\":{\"channels\":[{\"marker\":\"KRT5\"}]}}}",
                    defaultRoles, null, true).Ok,
                "every malformed shape refused");
            Row("H2", "a channel whose role cannot be resolved is refused",
                !CustomPanelRegistry.Parse(
                    "{\"panels\":{\"X\":{\"channels\":[" +
                    "{\"idx\":1,\"marker\":\"NOT_IN_THE_REGISTRY\"}]}}}",
                    defaultRoles, null, true).Ok &&
                !CustomPanelRegistry.Parse(
                    "{\"panels\":{\"X\":{\"channels\":[" +
                    "{\"idx\":1,\"marker\":\"KRT5\",\"role\":\"invented\"}]}}}",
                    defaultRoles, null, true).Ok,
                "no role, and an unsupported role, both refused");
            Row("H2", "a custom key may not replace a built-in panel",
                !CustomPanelRegistry.Parse(
                    "{\"panels\":{\"LEFT\":{\"channels\":[" +
                    "{\"idx\":1,\"marker\":\"DAPI\",\"role\":\"nuclear\"}]}}}",
                    defaultRoles, new List<string>(panels.Keys), true).Ok,
                "refused, as IF_Quant_Pipeline.groovy:710-712 does");

            // ---- H2 on route 4 (D2) -------------------------------------
            // Route 4 writes no IFQ_*_THRESHOLD by construction, so every
            // channel is adaptive unless the v1.7.2 Advanced box froze it.
            // v1.8.0.0 skipped H2 on route 4 entirely and recorded
            // run_classification=THRESHOLDS_FROZEN for every legacy run.
            RunRequest legacyBlank = Base(ImageRoute.LegacyFiji172, "LEFT");
            GateResult legacyBlankGate =
                FailClosedGate.Evaluate(legacyBlank, left, thresholdMarkers, null);
            string legacyBlankRecord = RunRecord.Build(
                legacyBlank, legacyBlankGate, LegacyProfile.Fixture(), "1.8.0.0", "ARM64",
                "fiji.exe", "launcher_exe", 0, "complete", "p", "r", null);
            Row("H2", "route 4 without Advanced thresholds is EXPLORATORY",
                !legacyBlankGate.Blocked && legacyBlankGate.Exploratory &&
                legacyBlankGate.FolderStamps().Contains(FailClosedGate.ExploratoryStamp) &&
                legacyBlankRecord.IndexOf("run_classification=EXPLORATORY_DO_NOT_AGGREGATE",
                                          StringComparison.Ordinal) >= 0,
                Verdict(legacyBlankGate));
            Row("H2", "route 4 record never contradicts its threshold_policy",
                legacyBlankRecord.IndexOf("thresholds_frozen=false",
                                          StringComparison.Ordinal) >= 0,
                legacyBlankRecord.IndexOf("thresholds_frozen=true", StringComparison.Ordinal) >= 0
                    ? "thresholds_frozen=TRUE beside an adaptive policy block"
                    : "thresholds_frozen=false");

            RunRequest legacyFrozen = Base(ImageRoute.LegacyFiji172, "LEFT");
            StringBuilder legacyAdvanced = new StringBuilder();
            foreach (ChannelDef c in left.AnalysisChannels)
                legacyAdvanced.AppendLine(c.ThresholdEnvName + "=500");
            legacyFrozen.AdvancedText = legacyAdvanced.ToString();
            GateResult legacyFrozenGate =
                FailClosedGate.Evaluate(legacyFrozen, left, thresholdMarkers, null);
            Row("H2", "route 4 frozen through Advanced IS thresholds_frozen",
                !legacyFrozenGate.Blocked && !legacyFrozenGate.Exploratory &&
                RunRecord.Build(legacyFrozen, legacyFrozenGate, LegacyProfile.Fixture(),
                                "1.8.0.0", "ARM64", "f", "i", 0, "complete", "p", "r", null)
                    .IndexOf("run_classification=THRESHOLDS_FROZEN",
                             StringComparison.Ordinal) >= 0,
                Verdict(legacyFrozenGate));

            RunRequest legacyPartial = Base(ImageRoute.LegacyFiji172, "LEFT");
            legacyPartial.AdvancedText =
                left.AnalysisChannels[0].ThresholdEnvName + "=500";
            GateResult legacyPartialGate =
                FailClosedGate.Evaluate(legacyPartial, left, thresholdMarkers, null);
            Row("H2", "route 4 with SOME channels frozen is still exploratory",
                !legacyPartialGate.Blocked && legacyPartialGate.Exploratory,
                Verdict(legacyPartialGate));

            // ---- H3 ----------------------------------------------------
            Row("H3", "IFQ_MIN_INCLUDED_NUCLEI always written on routes 1/2",
                env.ContainsKey("IFQ_MIN_INCLUDED_NUCLEI") &&
                env["IFQ_MIN_INCLUDED_NUCLEI"] == "0",
                "IFQ_MIN_INCLUDED_NUCLEI=" + env["IFQ_MIN_INCLUDED_NUCLEI"]);
            Row("H3", "never written on route 4 (v1.7.2 never wrote it)",
                !LegacyProfile.Fixture().ContainsKey("IFQ_MIN_INCLUDED_NUCLEI"), "absent");

            RunRequest floor = Base(ImageRoute.IfConfocal, "LEFT");
            floor.MinIncludedNuclei = 3;
            g = FailClosedGate.Evaluate(floor, left, thresholdMarkers, null);
            Row("H3", "non-zero floor on a panel with an area endpoint", g.Blocked, Verdict(g));

            RunRequest floorSlide = Base(ImageRoute.IfSlideScanner, "LEFT");
            floorSlide.WsiInput = "in"; floorSlide.WsiOutput = "out";
            floorSlide.MinIncludedNuclei = 1;
            g = FailClosedGate.Evaluate(floorSlide, left, thresholdMarkers, null);
            Row("H3", "non-zero floor on the whole-slide route", g.Blocked, Verdict(g));

            RunRequest advFloor = Base(ImageRoute.IfConfocal, "LEFT");
            advFloor.AdvancedText = "IFQ_MIN_INCLUDED_NUCLEI=5";
            g = FailClosedGate.Evaluate(advFloor, left, thresholdMarkers, null);
            Row("H3", "the Advanced box cannot set it behind the UI's back",
                g.Blocked, Verdict(g));

            // D3. ...but route 4 must accept it, because under v1.7.2 the
            // Advanced box was the ONLY way to set the nuclei floor and
            // v1.7.2's protected list did not contain it. v1.8.0.0 blocked it
            // as ADV_PROTECTED and route 4 emitted no variables at all.
            RunRequest legacyFloor = Base(ImageRoute.LegacyFiji172, "LEFT");
            legacyFloor.AdvancedText = "IFQ_MIN_INCLUDED_NUCLEI=3";
            GateResult legacyFloorGate =
                FailClosedGate.Evaluate(legacyFloor, left, thresholdMarkers, null);
            Row("H3", "route 4 accepts the v1.7.2 Advanced nuclei floor",
                !legacyFloorGate.Blocked, Verdict(legacyFloorGate));
            Row("H3", "and the run record names the floor that will apply",
                RunRecord.Build(legacyFloor, legacyFloorGate, LegacyProfile.Fixture(),
                                "1.8.0.0", "ARM64", "f", "i", 0, "complete", "p", "r", null)
                    .IndexOf("legacy_min_included_nuclei=3", StringComparison.Ordinal) >= 0,
                legacyFloorGate.LegacyMinIncludedNuclei ?? "(not recorded)");

            Dictionary<string, string> legacyFloorEnv = LegacyProfile.Fixture();
            legacyFloorEnv["IFQ_MIN_INCLUDED_NUCLEI"] = "3";
            string typedFloor = Threw(delegate
            {
                PreStartAssertions.AssertStage2Environment(
                    legacyFloorEnv, null, ImageRoute.LegacyFiji172,
                    new string[] { "IFQ_MIN_INCLUDED_NUCLEI" });
            });
            string untypedFloor = Threw(delegate
            {
                PreStartAssertions.AssertStage2Environment(
                    legacyFloorEnv, null, ImageRoute.LegacyFiji172, new string[0]);
            });
            Row("H3", "route 4 floor is legal from Advanced, illegal from the launcher",
                typedFloor == null && untypedFloor != null,
                "typed=" + (typedFloor ?? "allowed") + " launcher=" + (untypedFloor ?? "ALLOWED"));

            // ---- H4 ----------------------------------------------------
            Row("H4", "IFQ_ALLOW_NONEMPTY_OUTPUT hardcoded false",
                env["IFQ_ALLOW_NONEMPTY_OUTPUT"] == "false", "false");
            RunRequest dirty = Base(ImageRoute.IfConfocal, "LEFT");
            dirty.OutputDirectoryExistsAndIsNonEmpty = true;
            g = FailClosedGate.Evaluate(dirty, left, thresholdMarkers, null);
            Row("H4", "gate blocks a non-empty output folder", g.Blocked, Verdict(g));

            string probe = Path.Combine(
                Path.GetTempPath(), "IFQ-h4-" + Guid.NewGuid().ToString("N"));
            string h4 = null;
            try
            {
                Directory.CreateDirectory(probe);
                File.WriteAllText(Path.Combine(probe, "stale_run_summary.csv"), "x");
                try { PreStartAssertions.AssertOutputDirectoryEmpty(probe); }
                catch (Exception ex) { h4 = ex.GetType().Name; }
            }
            finally { if (Directory.Exists(probe)) Directory.Delete(probe, true); }
            Row("H4", "pre-start assertion refuses a non-empty folder on disk",
                h4 != null, h4 ?? "ALLOWED THE RUN");

            RunRequest noBase = Base(ImageRoute.IfConfocal, "LEFT");
            noBase.OutputBase = null;
            g = FailClosedGate.Evaluate(noBase, left, thresholdMarkers, null);
            Row("H4", "no output parent chosen", g.Blocked, Verdict(g));

            RunRequest advAllow = Base(ImageRoute.IfConfocal, "LEFT");
            advAllow.AdvancedText = "IFQ_ALLOW_NONEMPTY_OUTPUT=true";
            g = FailClosedGate.Evaluate(advAllow, left, thresholdMarkers, null);
            Row("H4", "the Advanced box cannot turn the guard off", g.Blocked, Verdict(g));

            // ---- H5 ----------------------------------------------------
            GateResult flagged = FailClosedGate.Evaluate(blank, left, thresholdMarkers, null);
            string record = RunRecord.Build(
                blank, flagged, env, "1.8.0.0", "ARM64", "fiji.exe", "launcher_exe",
                0, "complete", "pipeline", "registry", null);
            Row("H5", "run record carries run_classification",
                record.IndexOf("run_classification=EXPLORATORY_DO_NOT_AGGREGATE",
                               StringComparison.Ordinal) >= 0,
                "run_classification=EXPLORATORY_DO_NOT_AGGREGATE");
            Row("H5", "run record carries thresholds_frozen=false",
                record.IndexOf("thresholds_frozen=false", StringComparison.Ordinal) >= 0,
                "thresholds_frozen=false");
            Row("H5", "run record carries a per-channel threshold source",
                record.IndexOf("[threshold_policy]", StringComparison.Ordinal) >= 0 &&
                record.IndexOf("adaptive_otsu_exploratory", StringComparison.Ordinal) >= 0,
                string.Join(" ", flagged.ThresholdPolicy.ToArray()));
            Row("H5", "folder name is stamped",
                flagged.FolderStamps().Contains(FailClosedGate.ExploratoryStamp),
                string.Join("", flagged.FolderStamps().ToArray()));
            Row("H5", "marker file text names the consequence",
                RunRecord.ExploratoryMarkerText(blank, flagged)
                    .IndexOf("DO NOT AGGREGATE", StringComparison.Ordinal) >= 0,
                FailClosedGate.ExploratoryMarkerFileName);

            GateResult clean = FailClosedGate.Evaluate(allFrozen, left, thresholdMarkers, null);
            string cleanRecord = RunRecord.Build(
                allFrozen, clean, frozenEnv, "1.8.0.0", "ARM64", "fiji.exe", "launcher_exe",
                0, "complete", "p", "r", null);
            Row("H5", "a fully frozen run is NOT marked exploratory",
                cleanRecord.IndexOf("run_classification=THRESHOLDS_FROZEN",
                                    StringComparison.Ordinal) >= 0 &&
                clean.FolderStamps().Count == 0,
                "run_classification=THRESHOLDS_FROZEN");

            Console.WriteLine();
            Console.WriteLine("checks: " + checks + "   failures: " + failures);
            return failures == 0 ? 0 : 42;
        }
    }

    internal static class GateMatrixProgram
    {
        private static int Main(string[] args)
        {
            try
            {
                string pipeline = args[0];
                string registry = args.Length > 1
                    ? args[1]
                    : Path.Combine(
                        Path.GetDirectoryName(Path.GetFullPath(pipeline)),
                        Path.Combine("config", "lung_marker_registry.json"));
                return GateMatrix.Run(pipeline, registry);
            }
            catch (Exception ex)
            {
                Console.WriteLine("MATRIX CRASHED: " + ex);
                return 52;
            }
        }
    }
}
