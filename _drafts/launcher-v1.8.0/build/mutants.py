# Reverse mutants: take the FIXED sources and put each defect back, one at a
# time, keeping the new API surface so the harnesses still compile. A test that
# does not go red against its own mutant is not testing anything.
import io, os, shutil, sys

SRC = r"C:\Users\dream\AppData\Local\Temp\claude\X--QuPath\7933abe5-e14c-44b2-aa07-c4127fa41a9e\scratchpad\launcher_final\launcher"
OUT = r"C:\Users\dream\AppData\Local\Temp\claude\X--QuPath\7933abe5-e14c-44b2-aa07-c4127fa41a9e\scratchpad\fixwork\mutants"

MUTANTS = {
    # ---- the pre-existing H1-H5 mutation set, re-anchored on the fixed code
    #      so the original coverage is shown to survive the rework ----------
    "H1_pilot_unlocked": [("IFQuantLauncher.Routing.cs",
        "                if (!request.PilotPanelsUnlocked)",
        "                if (false)")],
    "H2_slide_block_off": [("IFQuantLauncher.Routing.cs",
        "                else if (!spec.ThresholdsMayBeOmitted)\n                {\n                    result.Findings.Add(new GateFinding(\n                        Severity.Block, \"H2_ADAPTIVE_ON_WHOLE_SLIDE\",",
        "                else if (false)\n                {\n                    result.Findings.Add(new GateFinding(\n                        Severity.Block, \"H2_ADAPTIVE_ON_WHOLE_SLIDE\",")],
    "H3_area_floor_off": [("IFQuantLauncher.Routing.cs",
        "                    else if (hasAreaEndpoint)",
        "                    else if (false)")],
    "H4_output_check_off": [("IFQuantLauncher.Routing.cs",
        "            if (request.OutputDirectoryExistsAndIsNonEmpty)",
        "            if (false)")],
    "H5_never_exploratory": [("IFQuantLauncher.Routing.cs",
        "            result.Exploratory = adaptive.Count > 0 || unresolvedPanelReason != null;",
        "            result.Exploratory = false;")],

    # ---- D1: H2 skipped whenever the launcher has no channel list ----------
    "D1a_no_unresolved_rule": [("IFQuantLauncher.Routing.cs",
        "            else\n            {\n                // ---------------------------------------------------------\n                // D1. NO CHANNEL LIST.",
        "            else if (false)\n            {\n                // ---------------------------------------------------------\n                // D1. NO CHANNEL LIST.")],

    # ---- D1: custom-panel channels wrongly declared un-freezable ----------
    "D1b_thresholdmarkers_only": [("IFQuantLauncher.Routing.cs",
        "            if (panel != null && panel.ChannelsAreThresholdable && !channel.IsNuclear)\n                return true;",
        "            if (false)\n                return true;")],

    # ---- D1: ResolveSelectedPanel returns null for every custom key -------
    "D1c_ui_returns_null": [("MainForm.Routes.partial.cs",
        "            if (EnginePanels.TryGetValue(key, out panel)) return panel;\n            return ResolveCustomPanel(key);",
        "            if (EnginePanels.TryGetValue(key, out panel)) return panel;\n            return null;")],

    # ---- D2: route 4 can never be exploratory -----------------------------
    "D2_route4_never_exploratory": [("IFQuantLauncher.Routing.cs",
        "            result.Exploratory = adaptive.Count > 0 || unresolvedPanelReason != null;",
        "            result.Exploratory = (adaptive.Count > 0 || unresolvedPanelReason != null) && !legacyRoute;")],

    # ---- D3: full v1.8.0 Advanced judgement applied to route 4 ------------
    "D3_route4_adv_blocked": [
        ("IFQuantLauncher.Routing.cs",
         "            Severity legacyAdvisory = legacyRoute ? Severity.Warn : Severity.Block;",
         "            Severity legacyAdvisory = Severity.Block;"),
        ("IFQuantLauncher.Routing.cs",
         "                    if (!legacyRoute || protectedByV172)",
         "                    if (true)"),
        ("IFQuantLauncher.Routing.cs",
         "            else if (env.ContainsKey(\"IFQ_MIN_INCLUDED_NUCLEI\") &&\n                     !ContainsIgnoreCase(advancedKeys, \"IFQ_MIN_INCLUDED_NUCLEI\"))",
         "            else if (env.ContainsKey(\"IFQ_MIN_INCLUDED_NUCLEI\"))"),
    ],

    # ---- D4: the second place that asserts the flag, restored -------------
    "D4_second_switch": [
        ("IFQuantLauncher.cs",
         "            RouteSpec he = RouteCatalog.Describe(ImageRoute.HeBrightfield);\n            if (he.Available != LauncherBuild.BrightfieldRouteEnabled) return 30;",
         "            RouteSpec he = RouteCatalog.Describe(ImageRoute.HeBrightfield);\n            if (LauncherBuild.BrightfieldRouteEnabled) return 30;\n            if (he.Available != LauncherBuild.BrightfieldRouteEnabled) return 30;"),
    ],

    # ---- D4: the flag flipped, on the FIXED code (must stay green) -------
    "D4_flag_on": [("IFQuantLauncher.Routing.cs",
        "        public static readonly bool BrightfieldRouteEnabled = false;",
        "        public static readonly bool BrightfieldRouteEnabled = true;")],

    # ---- D4: the flag flipped, with the old guard back (must go red) -----
    "D4_flag_on_with_guard": [
        ("IFQuantLauncher.Routing.cs",
         "        public static readonly bool BrightfieldRouteEnabled = false;",
         "        public static readonly bool BrightfieldRouteEnabled = true;"),
        ("IFQuantLauncher.cs",
         "            RouteSpec he = RouteCatalog.Describe(ImageRoute.HeBrightfield);\n            if (he.Available != LauncherBuild.BrightfieldRouteEnabled) return 30;",
         "            RouteSpec he = RouteCatalog.Describe(ImageRoute.HeBrightfield);\n            if (LauncherBuild.BrightfieldRouteEnabled) return 30;\n            if (he.Available != LauncherBuild.BrightfieldRouteEnabled) return 30;"),
    ],

    # ---- D5: RouteCatalog.Describe fails open on an unknown id -----------
    "D5a_describe_fails_open": [("IFQuantLauncher.Routing.cs",
        "                default:\n                    // A route id this build does not define.",
        "                case (ImageRoute)999:\n                    // A route id this build does not define.")],

    # ---- D5: BuildStage1 unguarded -----------------------------------
    "D5b_stage1_unguarded": [
        ("IFQuantLauncher.Routing.cs",
         "            if (request.Route == ImageRoute.HeBrightfield)\n            {\n                if (!LauncherBuild.BrightfieldRouteEnabled)\n                    throw new InvalidOperationException(\n                        \"Route 3 (H&E / brightfield) is not available in this build and no \" +\n                        \"stage 1 environment will be produced for it.\\r\\n\\r\\n\" +",
         "            if (false)\n            {\n                if (!LauncherBuild.BrightfieldRouteEnabled)\n                    throw new InvalidOperationException(\n                        \"Route 3 (H&E / brightfield) is not available in this build and no \" +\n                        \"stage 1 environment will be produced for it.\\r\\n\\r\\n\" +"),
        ("IFQuantLauncher.Routing.cs",
         "            if (request.Route != ImageRoute.IfSlideScanner)\n                throw new InvalidOperationException(\n                    \"Stage 1 exists only on route 2 (IF - slide scanner). \" +",
         "            if (false)\n                throw new InvalidOperationException(\n                    \"Stage 1 exists only on route 2 (IF - slide scanner). \" +"),
    ],
}

FILES = ["IFQuantLauncher.cs", "IFQuantLauncher.Routing.cs",
         "MainForm.Routes.partial.cs", "app.manifest", "build.ps1"]

def build(name):
    target = os.path.join(OUT, name)
    if os.path.isdir(target):
        shutil.rmtree(target)
    os.makedirs(target)
    for f in FILES:
        shutil.copy(os.path.join(SRC, f), os.path.join(target, f))
    for (fname, old, new) in MUTANTS[name]:
        path = os.path.join(target, fname)
        text = io.open(path, encoding="utf-8").read()
        if text.count(old) != 1:
            raise SystemExit("MUTANT %s: anchor found %d times in %s\n%r"
                             % (name, text.count(old), fname, old[:120]))
        io.open(path, "w", encoding="utf-8").write(text.replace(old, new))
    print("built mutant", name)

if __name__ == "__main__":
    names = sys.argv[1:] or sorted(MUTANTS)
    for n in names:
        build(n)
