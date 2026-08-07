// =====================================================================
// IFQuantLauncher.Routing.cs                                     v1.8.0
// ---------------------------------------------------------------------
// The route model, the environment surface, and the fail-closed gate.
//
// This file contains NO WinForms code and NO measurement code. Everything
// here is a decision or a description; IF_Quant_Pipeline.groovy remains the
// only thing that measures, and it is frozen.
//
// It is compiled together with IFQuantLauncher.cs and
// MainForm.Routes.partial.cs into a single assembly (launcher/build.ps1).
// It is ALSO compiled standalone, without WinForms, into the legacy
// equivalence harness, which is how route 4 is proven by execution rather
// than by assertion. Do not add a WinForms dependency to this file.
//
// THE FOUR ROUTES
//   R1  IF - confocal / field images     Fiji only. v1.7.2 behaviour + gates.
//   R2  IF - slide scanner (.vsi)        QuPath stage 1 -> Fiji -> stage 3.
//   R3  H&E / brightfield                DISABLED. See LauncherBuild below.
//   R4  Fiji-only legacy                 Byte-identical v1.7.2 environment.
//
// THE HAZARDS THIS FILE EXISTS TO MAKE IMPOSSIBLE BY ACCIDENT
//   H1  IFQ_PANEL defaults to "T"              IF_Quant_Pipeline.groovy:154
//   H2  no fixed threshold -> per-region Otsu  IF_Quant_Pipeline.groovy:2132-2140
//   H3  IFQ_MIN_INCLUDED_NUCLEI defaults to 1  IF_Quant_Pipeline.groovy:329
//   H4  a non-empty IFQ_OUTPUT_DIR aborts      IF_Quant_Pipeline.groovy:3479-3484
//   H5  an unfrozen run must say so in writing (this launcher's record)
// =====================================================================

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace IFQuantLauncher.Routing
{
    // =================================================================
    // 0. BUILD-TIME SWITCHES
    // =================================================================

    /// <summary>
    /// Compile-time policy for this launcher build.
    /// </summary>
    internal static class LauncherBuild
    {
        public const string Version = "1.8.1";
        public const string AssemblyVersion = "1.8.0.0";

        // =============================================================
        // >>> THE ONE LINE THAT RE-ENABLES ROUTE 3 (H&E / brightfield) <<<
        //
        //   BrightfieldRouteEnabled = false;   // this build
        //   BrightfieldRouteEnabled = true;    // when the module lands
        //
        // Flip this to true ONLY when a validated brightfield measurement
        // module exists and is wired to a route-3 stage list. Today there
        // is none:
        //
        //   * IF_Quant_Pipeline.groovy is fluorescence-only. It sets
        //     Prefs.blackBackground = true (line ~3472) and every marker
        //     rule assumes signal is BRIGHT on a DARK background. An H&E
        //     slide is the inverse, so every threshold, every area mask and
        //     every morphology rule inverts its meaning. It would not fail;
        //     it would produce numbers.
        //   * There is no colour deconvolution and no stain-vector model
        //     anywhere in the engine.
        //   * config/lung_marker_registry.json has no haematoxylin/eosin
        //     entry, so there is no validated numerator to report.
        //   * The morphometry module that would supply one is an unreviewed
        //     draft and is not part of this release.
        //
        // Turning this to true is NOT sufficient on its own. It makes the
        // route selectable and stops the gate blocking it; the stage list in
        // RouteCatalog.Describe and the environment builder in
        // RunEnvironment.BuildStage2 must then be given a real brightfield
        // engine to call. Both places assert on this constant, so a
        // half-finished re-enable fails loudly instead of running empty.
        // =============================================================
        public const bool BrightfieldRouteEnabled = false;

        /// Shown next to the greyed route-3 entry and in the block message.
        public const string BrightfieldDisabledReason =
            "Planned, not available in v" + Version + ".\r\n\r\n" +
            "The measurement engine (IF_Quant_Pipeline.groovy) is fluorescence-only: it " +
            "assumes a dark background with bright signal, and it contains no colour " +
            "deconvolution and no stain-vector model. There is no haematoxylin/eosin entry " +
            "in the marker registry, so there is no validated numerator to report. The " +
            "brightfield morphometry module that would supply one is an unreviewed draft " +
            "and is not part of this release.\r\n\r\n" +
            "Pointing the fluorescence engine at a brightfield slide would not fail. It " +
            "would invert the meaning of every threshold and every area mask and return " +
            "numbers. A route that appears to work and silently produces nothing " +
            "interpretable is worse than no route, so this one refuses to start.\r\n\r\n" +
            "Re-enabling it is one line: LauncherBuild.BrightfieldRouteEnabled in " +
            "launcher/IFQuantLauncher.Routing.cs.";
    }

    // =================================================================
    // 1. PANEL REGISTRY -- derived from the frozen engine, never retyped
    // =================================================================

    internal sealed class ChannelDef
    {
        public int Idx;
        public string Marker;
        public string Role;
        public bool AreaMarker;

        /// The engine's own normalisation, IF_Quant_Pipeline.groovy:194:
        ///   marker.toUpperCase().replaceAll(/[^A-Z0-9]+/, "")
        /// so panel LEFT's declared marker "T1A" gives IFQ_T1A_THRESHOLD.
        /// There is no IFQ_PDPN_THRESHOLD for that panel.
        public string Token
        {
            get { return PanelRegistry.NormalizeMarkerToken(Marker); }
        }

        public string ThresholdEnvName
        {
            get { return "IFQ_" + Token + "_THRESHOLD"; }
        }

        public bool IsNuclear
        {
            get { return string.Equals(Role, "nuclear", StringComparison.Ordinal); }
        }
    }

    internal sealed class PanelDef
    {
        public string Key;
        public string Label;
        public readonly List<ChannelDef> Channels = new List<ChannelDef>();

        public List<ChannelDef> AnalysisChannels
        {
            get
            {
                List<ChannelDef> list = new List<ChannelDef>();
                foreach (ChannelDef c in Channels)
                    if (!c.IsNuclear) list.Add(c);
                return list;
            }
        }

        public List<ChannelDef> AreaMarkers
        {
            get
            {
                List<ChannelDef> list = new List<ChannelDef>();
                foreach (ChannelDef c in Channels)
                    if (c.AreaMarker) list.Add(c);
                return list;
            }
        }

        public HashSet<string> Tokens
        {
            get
            {
                HashSet<string> set = new HashSet<string>(StringComparer.Ordinal);
                foreach (ChannelDef c in Channels) set.Add(c.Token);
                return set;
            }
        }
    }

    /// <summary>
    /// Parses the panel table AND the engine's threshold-marker whitelist out
    /// of the embedded IF_Quant_Pipeline.groovy.
    ///
    /// Parsing rather than retyping is deliberate. The launcher embeds that
    /// exact file at build time, so its idea of "which markers does panel LEFT
    /// have" cannot drift from the engine's. A hand-kept C# table is how you
    /// end up offering an IFQ_PDPN_THRESHOLD box that the engine ignores.
    /// </summary>
    internal static class PanelRegistry
    {
        private static readonly Regex PanelHead = new Regex(
            "\"(?<key>[A-Za-z0-9_]+)\"\\s*:\\s*\\[\\s*label:\"(?<label>[^\"]*)\"",
            RegexOptions.CultureInvariant);

        private static readonly Regex ChannelRow = new Regex(
            "idx:\\s*(?<idx>\\d+)\\s*,\\s*marker:\"(?<marker>[^\"]+)\"\\s*,\\s*role:\"(?<role>[^\"]+)\"",
            RegexOptions.CultureInvariant);

        private static readonly Regex ThresholdMarkerList = new Regex(
            "def\\s+thresholdMarkers\\s*=\\s*\\[(?<body>[^\\]]*)\\]",
            RegexOptions.CultureInvariant | RegexOptions.Singleline);

        public static string NormalizeMarkerToken(string value)
        {
            if (value == null) return "";
            return Regex.Replace(
                value.ToUpperInvariant(), "[^A-Z0-9]+", "", RegexOptions.CultureInvariant);
        }

        public static Dictionary<string, PanelDef> ParseFromPipeline(string groovyText)
        {
            if (string.IsNullOrEmpty(groovyText))
                throw new InvalidOperationException(
                    "The embedded pipeline is empty; panel definitions cannot be derived.");

            MatchCollection heads = PanelHead.Matches(groovyText);
            if (heads.Count == 0)
                throw new InvalidOperationException(
                    "No panel definitions were found in the embedded pipeline. The launcher " +
                    "refuses to guess a channel map.");

            Dictionary<string, PanelDef> panels =
                new Dictionary<string, PanelDef>(StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < heads.Count; i++)
            {
                Match head = heads[i];
                int start = head.Index;
                int end = (i + 1 < heads.Count) ? heads[i + 1].Index : groovyText.Length;
                string segment = groovyText.Substring(start, end - start);

                PanelDef panel = new PanelDef();
                panel.Key = head.Groups["key"].Value;
                panel.Label = head.Groups["label"].Value;

                foreach (Match ch in ChannelRow.Matches(segment))
                {
                    ChannelDef channel = new ChannelDef();
                    channel.Idx = Int32.Parse(ch.Groups["idx"].Value, CultureInfo.InvariantCulture);
                    channel.Marker = ch.Groups["marker"].Value;
                    channel.Role = ch.Groups["role"].Value;

                    // areaMarker lives inside this channel's own [...] literal.
                    // Stop at the closing bracket so a later channel's flag is
                    // never attributed to this one.
                    int from = ch.Index;
                    int close = segment.IndexOf(']', from);
                    if (close < 0) close = segment.Length;
                    channel.AreaMarker = segment.IndexOf(
                        "areaMarker:true", from, close - from, StringComparison.Ordinal) >= 0;

                    panel.Channels.Add(channel);
                }

                if (panel.Channels.Count == 0) continue;
                panels[panel.Key] = panel;
            }
            return panels;
        }

        /// <summary>
        /// The engine reads IFQ_&lt;TOKEN&gt;_THRESHOLD for a CLOSED list of markers
        /// (IF_Quant_Pipeline.groovy:189-199). A panel channel whose marker is not
        /// on that list has no threshold variable at all: setting one is a silent
        /// no-op and the channel stays on adaptive Otsu forever. The threshold grid
        /// must therefore say so instead of offering a box that does nothing.
        /// </summary>
        public static HashSet<string> ParseThresholdMarkerTokens(string groovyText)
        {
            HashSet<string> tokens = new HashSet<string>(StringComparer.Ordinal);
            Match list = ThresholdMarkerList.Match(groovyText ?? "");
            if (!list.Success)
                throw new InvalidOperationException(
                    "The embedded pipeline does not declare 'def thresholdMarkers = [...]'. " +
                    "The launcher will not guess which channels accept a fixed threshold.");
            foreach (Match m in Regex.Matches(list.Groups["body"].Value, "\"(?<m>[^\"]+)\""))
                tokens.Add(NormalizeMarkerToken(m.Groups["m"].Value));
            if (tokens.Count == 0)
                throw new InvalidOperationException(
                    "The embedded pipeline's thresholdMarkers list parsed empty.");
            return tokens;
        }
    }

    // =================================================================
    // 2. ENVIRONMENT SURFACE
    // =================================================================

    internal enum EnvKind { Stage1Static, Stage2Static, MarkerFamily, UnknownMarker, Unknown }

    internal sealed class EnvClassification
    {
        public EnvKind Kind;
        public string Stage;
        public string Detail;
    }

    /// <summary>
    /// The IFQ_* names the two front ends actually read.
    ///
    /// This exists because of one property of the engine: envOr / envInt /
    /// envDouble fall back SILENTLY when a variable is absent or empty. A
    /// typo'd IFQ_KRT_5_THRESHOLD is not an error -- the engine simply uses
    /// adaptive Otsu and the run looks configured. v1.7.2's Advanced box
    /// (IFQuantLauncher.cs ParseAdvancedEnvironment) validates only the shape
    /// ^IFQ_[A-Z0-9_]+$, so today that typo is accepted.
    /// </summary>
    internal static class EnvSurface
    {
        public static readonly HashSet<string> Stage1Static =
            new HashSet<string>(StringComparer.Ordinal)
            {
                "IFQ_WSI_AGER_CHANNEL", "IFQ_WSI_AGER_THRESHOLD", "IFQ_WSI_CHANNEL_PATTERNS",
                "IFQ_WSI_COMPRESSION", "IFQ_WSI_CORE_PX", "IFQ_WSI_DAMAGE_CUTOFF",
                "IFQ_WSI_DAMAGE_SIGMA_UM", "IFQ_WSI_DRY_RUN", "IFQ_WSI_EXPECT_CHANNELS",
                "IFQ_WSI_FILL_INTERIOR_RINGS", "IFQ_WSI_HALO_PX", "IFQ_WSI_INPUT",
                "IFQ_WSI_MAX_PIXEL_UM", "IFQ_WSI_MAX_TILES_PER_SLIDE", "IFQ_WSI_MIN_FRAGMENT_MM2",
                "IFQ_WSI_MIN_TILE_TISSUE_UM2", "IFQ_WSI_OUTPUT", "IFQ_WSI_PANEL",
                "IFQ_WSI_PARALLEL", "IFQ_WSI_PARTITION_DAMAGE", "IFQ_WSI_RESUME",
                "IFQ_WSI_ROI_COMPARTMENT", "IFQ_WSI_ROI_NAME", "IFQ_WSI_ROI_NAME_DAMAGED",
                "IFQ_WSI_ROI_NAME_INTACT", "IFQ_WSI_SLIDE_METADATA", "IFQ_WSI_TISSUE_BLUR_SIGMA",
                "IFQ_WSI_TISSUE_CLOSE_RADIUS", "IFQ_WSI_TISSUE_DOWNSAMPLE",
                "IFQ_WSI_TISSUE_OPEN_RADIUS", "IFQ_WSI_WRITE_TILE_PX"
            };

        public static readonly HashSet<string> Stage2Static =
            new HashSet<string>(StringComparer.Ordinal)
            {
                "IFQ_ACTUB_CILIA_DENSITY_RADIUS_UM", "IFQ_ACTUB_CILIA_MIN_LOCAL_DENSITY",
                "IFQ_ACTUB_CILIA_SEED_PERCENTILE", "IFQ_ACTUB_MAX_COMPONENT_DISTANCE_UM",
                "IFQ_ACTUB_MAX_PATCH_AREA_UM2", "IFQ_ACTUB_MIN_COMPONENT_BOUNDARY_DISTANCE_UM",
                "IFQ_ACTUB_MIN_PATCH_AREA_UM2", "IFQ_ACTUB_MIN_SUPPORT_FRACTION",
                "IFQ_ACTUB_SUPPORT_EXPAND_UM", "IFQ_ALLOW_NONEMPTY_OUTPUT",
                "IFQ_COMPARTMENT_MODE", "IFQ_DAPI_BACKGROUND_RADIUS_UM", "IFQ_DAPI_BLUR_SIGMA_PX",
                "IFQ_DAPI_CONTRAST_SATURATION", "IFQ_DAPI_LOCAL_RADIUS_UM", "IFQ_DAPI_METHOD",
                "IFQ_DISPLAY_GAMMA", "IFQ_DISPLAY_HIGH_PERCENTILE", "IFQ_DISPLAY_LOW_PERCENTILE",
                "IFQ_DISPLAY_PREVIEW_ONLY", "IFQ_EXPORT_DISPLAY_CHANNELS", "IFQ_INCLUDE_REGEX",
                "IFQ_INPUT_DIR", "IFQ_MARKER_REGISTRY", "IFQ_MAX_IMAGES", "IFQ_MIN_INCLUDED_NUCLEI",
                "IFQ_MIN_NUCLEUS_AREA_UM2", "IFQ_MORPHOLOGY_PRIMARY", "IFQ_MRAGE_MIN_RING_FRACTION",
                "IFQ_OUTPUT_DIR", "IFQ_PANEL", "IFQ_PANEL_CONFIG", "IFQ_PANEL_MAP_PATH",
                "IFQ_PROJECTION", "IFQ_RECURSIVE", "IFQ_RING_EXPAND_UM", "IFQ_SEGMENTER",
                "IFQ_SINGLE_PLANE", "IFQ_T1A_MIN_RING_FRACTION", "IFQ_TISSUE_MODE",
                "IFQ_WHOLE_FIELD_COMPARTMENT", "IFQ_Z_APICAL_PLANES", "IFQ_Z_APICAL_RANGE",
                "IFQ_Z_CELL_BODY_PLANES", "IFQ_Z_CELL_BODY_RANGE", "IFQ_Z_NUCLEAR_RANGE",
                "IFQ_MORPHOLOGY_PRIMARY", "IFQ_STARDIST_PROB", "IFQ_STARDIST_NMS"
            };

        public static readonly Dictionary<string, string> MarkerSuffixes =
            new Dictionary<string, string>(StringComparer.Ordinal)
            {
                { "_THRESHOLD", "candidate-pixel intensity cutoff" },
                { "_MIN_POSITIVE_FRACTION", "morphology support fraction" },
                { "_MIN_LARGEST_COMPONENT_SHARE", "connected-pattern share" },
                { "_MIN_NUCLEAR_ENRICHMENT", "applied only when the resolved rule already has the key" },
                { "_MIN_NUC_CYTO_RATIO", "applied only when the resolved rule already has the key" }
            };

        /// Owned by the launcher UI. The Advanced box may never set these.
        /// v1.7.2's nineteen, plus the four this build takes ownership of.
        public static readonly HashSet<string> ProtectedKeys =
            new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                // inherited verbatim from v1.7.2
                "IFQ_INPUT_DIR", "IFQ_OUTPUT_DIR", "IFQ_PANEL",
                "IFQ_MARKER_REGISTRY", "IFQ_PANEL_CONFIG", "IFQ_PANEL_MAP_PATH",
                "IFQ_RECURSIVE", "IFQ_INCLUDE_REGEX", "IFQ_MAX_IMAGES",
                "IFQ_SEGMENTER", "IFQ_PROJECTION", "IFQ_SINGLE_PLANE",
                "IFQ_EXPORT_DISPLAY_CHANNELS", "IFQ_DISPLAY_PREVIEW_ONLY",
                "IFQ_TISSUE_MODE", "IFQ_COMPARTMENT_MODE",
                "IFQ_WHOLE_FIELD_COMPARTMENT",
                "IFQ_ALLOW_NONEMPTY_OUTPUT", "IFQ_MORPHOLOGY_PRIMARY",
                // new in v1.8.0
                // H3: the sparse-region floor now has a first-class control, so
                // it must not also be settable behind the UI's back.
                "IFQ_MIN_INCLUDED_NUCLEI",
                // Stage 1 identity: one panel control writes both panel names.
                "IFQ_WSI_PANEL", "IFQ_WSI_INPUT", "IFQ_WSI_OUTPUT"
            };

        public static EnvClassification Classify(string name, HashSet<string> panelTokens)
        {
            EnvClassification result = new EnvClassification();
            if (Stage1Static.Contains(name))
            {
                result.Kind = EnvKind.Stage1Static; result.Stage = "stage1"; result.Detail = "";
                return result;
            }
            if (Stage2Static.Contains(name))
            {
                result.Kind = EnvKind.Stage2Static; result.Stage = "stage2"; result.Detail = "";
                return result;
            }
            foreach (KeyValuePair<string, string> pair in MarkerSuffixes)
            {
                if (!name.StartsWith("IFQ_", StringComparison.Ordinal)) continue;
                if (!name.EndsWith(pair.Key, StringComparison.Ordinal)) continue;
                string token = name.Substring(4, name.Length - 4 - pair.Key.Length);
                if (token.Length == 0) continue;
                result.Stage = "stage2";
                if (panelTokens == null || panelTokens.Contains(token))
                {
                    result.Kind = EnvKind.MarkerFamily;
                    result.Detail = pair.Value;
                }
                else
                {
                    result.Kind = EnvKind.UnknownMarker;
                    result.Detail =
                        "marker token '" + token + "' is not a channel of the selected panel. " +
                        "The engine would ignore this variable silently. " + pair.Value;
                }
                return result;
            }
            result.Kind = EnvKind.Unknown;
            result.Stage = null;
            result.Detail =
                "not part of either front end's environment surface. The engine ignores " +
                "unknown IFQ_* names without complaint, so this would be a no-op that " +
                "looks configured.";
            return result;
        }
    }

    // =================================================================
    // 3. ROUTE MODEL
    // =================================================================

    internal enum ImageRoute
    {
        IfConfocal = 1,
        IfSlideScanner = 2,
        HeBrightfield = 3,
        LegacyFiji172 = 4
    }

    internal sealed class StageSpec
    {
        public string Id;
        public string Title;
        public string Tool;
        public string Artifact;

        public StageSpec(string id, string title, string tool, string artifact)
        {
            Id = id; Title = title; Tool = tool; Artifact = artifact;
        }
    }

    internal sealed class RouteSpec
    {
        public ImageRoute Route;
        public string DisplayName;
        public string OneLine;
        public List<StageSpec> Stages = new List<StageSpec>();
        public bool RequiresFiji;
        public bool RequiresQuPath;
        public bool RequiresPython;
        public bool ProducesQuantitativeNumbers;

        /// false => the route is visible but NOT selectable, and the gate blocks it.
        public bool Available = true;
        /// non-null exactly when Available is false.
        public string UnavailableReason;

        /// true  => a missing fixed threshold is flagged EXPLORATORY (H2/H5)
        /// false => a missing fixed threshold is a hard block
        public bool ThresholdsMayBeOmitted;

        /// true  => the launcher writes IFQ_MIN_INCLUDED_NUCLEI explicitly (H3)
        /// false => route 4, which must not write what v1.7.2 never wrote
        public bool WritesMinIncludedNuclei;
    }

    internal static class RouteCatalog
    {
        public static RouteSpec Describe(ImageRoute route)
        {
            RouteSpec spec = new RouteSpec();
            spec.Route = route;
            switch (route)
            {
                case ImageRoute.IfConfocal:
                    spec.DisplayName = "1. IF - confocal / field images (Fiji only)";
                    spec.OneLine =
                        "Small calibrated multichannel fields. The frozen Fiji engine reads them " +
                        "directly. One run_summary.csv row per (image, region).";
                    spec.Stages.Add(new StageSpec(
                        "stage2", "Measure with the frozen Fiji engine", "fiji",
                        "run_summary.csv + run_summary.xlsx + run_manifest.json status=complete"));
                    spec.RequiresFiji = true;
                    spec.ProducesQuantitativeNumbers = true;
                    spec.ThresholdsMayBeOmitted = true;
                    spec.WritesMinIncludedNuclei = true;
                    break;

                case ImageRoute.IfSlideScanner:
                    spec.DisplayName = "2. IF - slide scanner (.vsi whole slide)";
                    spec.OneLine =
                        "QuPath reads and tiles the slide; the SAME frozen Fiji engine measures " +
                        "the tiles; stage 3 reconciles tiles back to one slide. QuPath never " +
                        "measures anything.";
                    spec.Stages.Add(new StageSpec(
                        "stage1", "Tile the slide (QuPath 0.7+, headless)", "qupath",
                        "tiles/*.ome.tif + tiles/*_RoiSet.zip + tiles/samplesheet.csv + stage1_manifest.json"));
                    spec.Stages.Add(new StageSpec(
                        "stage2", "Measure every tile (Fiji)", "fiji",
                        "analysis/run_summary.csv, one row per tile per region"));
                    spec.Stages.Add(new StageSpec(
                        "stage3", "Reconcile tiles to slide (Python)", "python",
                        "stats/slide_level_summary.csv"));
                    spec.RequiresFiji = true;
                    spec.RequiresQuPath = true;
                    spec.RequiresPython = true;
                    spec.ProducesQuantitativeNumbers = true;
                    spec.ThresholdsMayBeOmitted = false;   // hard block: ~370 tiles
                    spec.WritesMinIncludedNuclei = true;
                    break;

                case ImageRoute.HeBrightfield:
                    spec.DisplayName =
                        "3. H&E / brightfield" +
                        (LauncherBuild.BrightfieldRouteEnabled
                            ? " (brightfield morphometry)"
                            : "   [PLANNED - NOT AVAILABLE IN v" + LauncherBuild.Version + "]");
                    spec.OneLine =
                        "Haematoxylin/eosin and other brightfield stains. Deliberately visible " +
                        "and deliberately not selectable in this build.";
                    spec.RequiresQuPath = true;
                    spec.ProducesQuantitativeNumbers = false;
                    spec.ThresholdsMayBeOmitted = true;
                    spec.WritesMinIncludedNuclei = false;
                    if (!LauncherBuild.BrightfieldRouteEnabled)
                    {
                        spec.Available = false;
                        spec.UnavailableReason = LauncherBuild.BrightfieldDisabledReason;
                        // No stages are listed: there is nothing to run.
                    }
                    else
                    {
                        // Flipping the flag makes the route selectable. It does
                        // not conjure an engine: RunEnvironment.BuildStage2 still
                        // refuses until the brightfield module is wired in, so a
                        // half-finished re-enable fails at the Run button with a
                        // named cause instead of producing an empty run.
                        spec.Stages.Add(new StageSpec(
                            "stage2he", "Measure with the brightfield morphometry module",
                            "qupath", "run_summary.csv (brightfield endpoints)"));
                    }
                    break;

                case ImageRoute.LegacyFiji172:
                    spec.DisplayName = "4. Fiji-only legacy mode (reproduce v1.7.2 exactly)";
                    spec.OneLine =
                        "Byte-for-byte the v1.7.2 environment and the v1.7.2 Fiji command line " +
                        "for the field/confocal case, so an analysis run before v" +
                        LauncherBuild.Version + " stays reproducible.";
                    spec.Stages.Add(new StageSpec(
                        "stage2legacy", "Measure with the frozen Fiji engine (v1.7.2 environment)",
                        "fiji", "run_summary.csv + run_summary.xlsx + run_manifest.json"));
                    spec.RequiresFiji = true;
                    spec.ProducesQuantitativeNumbers = true;
                    spec.ThresholdsMayBeOmitted = true;
                    spec.WritesMinIncludedNuclei = false;  // v1.7.2 never wrote it
                    break;
            }
            return spec;
        }

        public static ImageRoute[] All()
        {
            return new ImageRoute[]
            {
                ImageRoute.IfConfocal, ImageRoute.IfSlideScanner,
                ImageRoute.HeBrightfield, ImageRoute.LegacyFiji172
            };
        }

        public static bool IsAvailable(ImageRoute route)
        {
            return Describe(route).Available;
        }
    }

    // =================================================================
    // 4. THE RUN REQUEST
    // =================================================================

    internal enum RunTier
    {
        Dry = 0,
        Exploratory = 1,
        Confirmatory = 2
    }

    /// How the Fiji engine is started.
    internal enum FijiInvocation
    {
        /// v1.7.2: fiji-windows-*.exe --headless --console --run "script".
        /// Route 4 is hardwired to this and may never use anything else.
        LauncherExe = 0,
        /// The bundled JVM, argument-for-argument as scripts/Invoke-Stage2Sharded.ps1
        /// starts it. Needed because the Fiji launcher .exe is unreliable on
        /// win-arm64, where it is the only .exe present.
        BundledJvm = 1
    }

    internal sealed class RunRequest
    {
        public ImageRoute Route = ImageRoute.IfConfocal;
        public RunTier Tier = RunTier.Exploratory;
        public FijiInvocation Invocation = FijiInvocation.LauncherExe;

        public string InputDirectory;
        public string OutputBase;
        public string RunName;
        public string FijiPath;
        public string QuPathExecutable;
        public string PythonExecutable;

        // Route 2 only
        public string WsiInput;
        public string WsiOutput;
        public string SlideMetadataCsv;
        public bool WsiResume = true;
        public bool WsiPartitionDamage;
        public int WsiMaxTilesPerSlide;

        // Shared analysis settings (AUTO is resolved before the gate runs)
        public string PanelKey;
        public bool PanelWasAuto;
        public string PanelConfigJson;
        public string Segmenter = "classic";
        public string Projection = "layer_aware";
        public int SinglePlane = -1;
        public string TissueMode = "auto";
        public string CompartmentMode = "required";
        public string WholeFieldCompartment = "unassigned";
        public bool Recursive = true;
        public string IncludeRegex = ".*";
        public int MaxImages;
        public bool PreviewOnly;

        /// H2. marker token -> threshold as typed. Absent or blank = adaptive Otsu.
        public readonly Dictionary<string, string> Thresholds =
            new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        /// H3. Always written explicitly on routes 1 and 2; never on route 4.
        public int MinIncludedNuclei;

        /// H1. Non-interpretable pilot panels are opt-in.
        public bool PilotPanelsUnlocked;

        /// H4. Filled by the caller from the file system immediately before the
        /// gate runs, so the gate itself stays a pure function.
        public bool OutputDirectoryExistsAndIsNonEmpty;

        public string AdvancedText = "";
        public string TypedConfirmation = "";
    }

    // =================================================================
    // 5. THE FAIL-CLOSED GATE
    // =================================================================

    internal enum Severity
    {
        Block = 3,
        Confirm = 2,
        Warn = 1,
        Note = 0
    }

    internal sealed class GateFinding
    {
        public Severity Severity;
        public string Code;
        public string Message;
        /// Suffix appended to the run folder name so a flagged run is
        /// identifiable from Explorer without opening anything.
        public string FolderStamp;

        public GateFinding(Severity severity, string code, string message)
        {
            Severity = severity; Code = code; Message = message;
        }

        public GateFinding(Severity severity, string code, string message, string folderStamp)
            : this(severity, code, message)
        {
            FolderStamp = folderStamp;
        }
    }

    internal sealed class GateResult
    {
        public readonly List<GateFinding> Findings = new List<GateFinding>();

        /// Every phrase the user must type before this run may start.
        ///
        /// A LIST, not a single string. Two independent hazards can apply at
        /// once -- panel T with no frozen thresholds is both non-interpretable
        /// AND exploratory -- and with one slot the second rule to be evaluated
        /// silently overwrote the first, so the user acknowledged one
        /// consequence and never saw the other. Each phrase gets its own box.
        public readonly List<string> RequiredPhrases = new List<string>();

        public bool NeedsConfirmation { get { return RequiredPhrases.Count > 0; } }

        public void RequirePhrase(string phrase)
        {
            if (string.IsNullOrEmpty(phrase)) return;
            if (!RequiredPhrases.Contains(phrase)) RequiredPhrases.Add(phrase);
        }

        /// H5. True when the run's thresholds are not all frozen, i.e. at least
        /// one analysis channel will be measured with per-region adaptive Otsu.
        public bool Exploratory;
        /// Human-readable per-channel threshold provenance, for launcher_run.txt.
        public readonly List<string> ThresholdPolicy = new List<string>();

        public bool Blocked
        {
            get
            {
                foreach (GateFinding f in Findings)
                    if (f.Severity == Severity.Block) return true;
                return false;
            }
        }

        public List<string> FolderStamps()
        {
            List<string> stamps = new List<string>();
            foreach (GateFinding f in Findings)
                if (!string.IsNullOrEmpty(f.FolderStamp) && !stamps.Contains(f.FolderStamp))
                    stamps.Add(f.FolderStamp);
            return stamps;
        }

        public List<GateFinding> OfSeverity(Severity s)
        {
            List<GateFinding> list = new List<GateFinding>();
            foreach (GateFinding f in Findings)
                if (f.Severity == s) list.Add(f);
            return list;
        }

        public string FirstBlockMessage()
        {
            foreach (GateFinding f in Findings)
                if (f.Severity == Severity.Block) return f.Message;
            return null;
        }
    }

    /// <summary>
    /// Every rule that makes a silent-garbage run impossible by accident.
    /// A pure function of (request, panel, tool inventory) -> findings: no UI,
    /// no file-system access, no side effects, so it is unit-testable and is
    /// unit-tested by RoutingSelfTest.
    /// </summary>
    internal static class FailClosedGate
    {
        public const string ExploratoryPhrase = "EXPLORATORY";
        public const string PilotPhrase = "NOT INTERPRETABLE";
        public const string ExploratoryStamp = "_EXPLORATORY";
        public const string PilotStamp = "_PILOT_NOT_INTERPRETABLE";
        public const string ExploratoryMarkerFileName = "EXPLORATORY_DO_NOT_AGGREGATE.txt";

        public static GateResult Evaluate(
            RunRequest request,
            PanelDef panel,
            HashSet<string> engineThresholdMarkers,
            ToolInventory tools)
        {
            GateResult result = new GateResult();
            RouteSpec spec = RouteCatalog.Describe(request.Route);

            // ---------------------------------------------------------
            // R0. Route availability.  R3 lands here.
            // ---------------------------------------------------------
            if (!spec.Available)
            {
                result.Findings.Add(new GateFinding(
                    Severity.Block, "ROUTE_NOT_AVAILABLE",
                    spec.DisplayName + "\r\n\r\n" + spec.UnavailableReason));
                // Deliberately keep evaluating: the review text should show the
                // whole picture, not stop at the first problem. Blocked is
                // already true, and nothing below can clear it.
            }

            if (spec.RequiresFiji && tools != null && !tools.FijiPresent)
                result.Findings.Add(new GateFinding(
                    Severity.Block, "FIJI_MISSING",
                    "This route measures with Fiji, and no Fiji/ImageJ executable was resolved " +
                    "from the selected path."));
            if (spec.RequiresFiji && request.Invocation == FijiInvocation.BundledJvm &&
                tools != null && !tools.BundledJvmPresent)
                result.Findings.Add(new GateFinding(
                    Severity.Block, "FIJI_JVM_MISSING",
                    "Direct JVM invocation was requested but the bundled runtime could not be " +
                    "located under the Fiji folder. Both java.exe (under java\\) and " +
                    "ij1-patcher-*.jar (under jars\\) are required; the patcher is what makes " +
                    "headless ImageJ1 work at all."));
            if (spec.RequiresQuPath && tools != null && !tools.QuPathPresent)
                result.Findings.Add(new GateFinding(
                    Severity.Block, "QUPATH_MISSING",
                    "This route needs the QuPath 0.7+ console build to read the .vsi. Fiji's " +
                    "Bio-Formats cannot decode the JPEG-2000 .ets pyramid, so there is no " +
                    "fallback."));
            if (spec.RequiresPython && tools != null && !tools.PythonPresent)
                result.Findings.Add(new GateFinding(
                    Severity.Block, "PYTHON_MISSING",
                    "Stage 3 (aggregate_tiles_to_slide.py) is the only path from tile rows to a " +
                    "slide-level number. Without Python the run would stop at unreconciled tiles."));

            // ---------------------------------------------------------
            // H1. IFQ_PANEL defaults to "T"
            //     IF_Quant_Pipeline.groovy:154  envOr("IFQ_PANEL", "T")
            // ---------------------------------------------------------
            if (string.IsNullOrEmpty(request.PanelKey) || request.PanelKey.Trim().Length == 0)
            {
                result.Findings.Add(new GateFinding(
                    Severity.Block, "H1_PANEL_UNSET",
                    "No staining panel is selected.\r\n\r\n" +
                    "The launcher will not start a run without writing IFQ_PANEL, because the " +
                    "engine's fallback is panel T -- a pilot plumbing panel whose nuclear " +
                    "channel is index 4 rather than 1 and whose green/red channels are smFISH " +
                    "probes from a SKIN sample. Forgetting the panel produces numbers, not an " +
                    "error."));
            }
            else if (string.Equals(request.PanelKey, "T", StringComparison.OrdinalIgnoreCase))
            {
                if (!request.PilotPanelsUnlocked)
                {
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "H1_PILOT_PANEL_LOCKED",
                        "Panel T is a plumbing test, not a study panel. Its channel map points " +
                        "at an unrelated skin smFISH sample, so any positivity it reports is " +
                        "meaningless. Tick 'Enable non-interpretable pilot panels' under " +
                        "Advanced study options if you really are testing the plumbing."));
                }
                else
                {
                    result.RequirePhrase(PilotPhrase);
                    result.Findings.Add(new GateFinding(
                        Severity.Confirm, "H1_PILOT_PANEL",
                        "Panel T is a plumbing test. Nothing it reports is biologically " +
                        "interpretable, and no number from this run may be aggregated or shown " +
                        "as a result.",
                        PilotStamp));
                }
            }
            else if (panel == null && string.IsNullOrEmpty(request.PanelConfigJson))
            {
                result.Findings.Add(new GateFinding(
                    Severity.Block, "H1_PANEL_UNKNOWN",
                    "Panel '" + request.PanelKey + "' is not one of the panels declared in the " +
                    "embedded IF_Quant_Pipeline.groovy, and no validated custom panel JSON is " +
                    "selected. The engine would fall back to panel T."));
            }

            // ---------------------------------------------------------
            // H2. A missing fixed threshold means per-region adaptive Otsu.
            //     IF_Quant_Pipeline.groovy:2132-2140
            //       double t = fixed ? ... : autoThresholdInRoi(..., "Otsu")
            //       chThreshSource[c.marker] = fixed ? "fixed_predeclared"
            //                                        : "adaptive_otsu_exploratory"
            //     Measured consequence: an uninfected control read 4.95% KRT5+
            //     at an Otsu threshold of 54.6, inside that channel's noise
            //     floor; a background-dominated tile can report
            //     KRT5_pod_area_frac ~0.89.
            // ---------------------------------------------------------
            List<string> adaptive = new List<string>();
            List<string> adaptiveArea = new List<string>();
            List<string> notThresholdable = new List<string>();

            if (panel != null && request.Route != ImageRoute.LegacyFiji172)
            {
                foreach (ChannelDef channel in panel.AnalysisChannels)
                {
                    bool engineReadsIt =
                        engineThresholdMarkers == null ||
                        engineThresholdMarkers.Contains(channel.Token);

                    string raw;
                    request.Thresholds.TryGetValue(channel.Token, out raw);
                    string value = (raw ?? "").Trim();

                    if (!engineReadsIt)
                    {
                        // Not a launcher failure. The engine simply has no
                        // IFQ_<TOKEN>_THRESHOLD for this marker, so the channel
                        // is adaptive by construction and no box can change it.
                        notThresholdable.Add(channel.Marker);
                        result.ThresholdPolicy.Add(
                            channel.Marker + "=adaptive_otsu_exploratory(no_env_variable_exists)");
                        adaptive.Add(channel.Marker);
                        if (channel.AreaMarker) adaptiveArea.Add(channel.Marker);
                        if (value.Length > 0)
                            result.Findings.Add(new GateFinding(
                                Severity.Block, "H2_THRESHOLD_NOT_READ",
                                "A threshold was typed for " + channel.Marker + ", but the engine " +
                                "reads IFQ_<MARKER>_THRESHOLD only for a closed list of markers " +
                                "(IF_Quant_Pipeline.groovy:189-199) and " + channel.Marker +
                                " is not on it. Setting " + channel.ThresholdEnvName + " would be " +
                                "a silent no-op: the channel would still use adaptive Otsu while " +
                                "the run record claimed a fixed cutoff."));
                        continue;
                    }

                    if (value.Length == 0)
                    {
                        adaptive.Add(channel.Marker);
                        if (channel.AreaMarker) adaptiveArea.Add(channel.Marker);
                        result.ThresholdPolicy.Add(
                            channel.Marker + "=adaptive_otsu_exploratory");
                        continue;
                    }

                    double parsed;
                    if (!Double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture,
                                         out parsed) ||
                        Double.IsNaN(parsed) || Double.IsInfinity(parsed) || parsed <= 0.0)
                    {
                        result.Findings.Add(new GateFinding(
                            Severity.Block, "H2_THRESHOLD_INVALID",
                            "The threshold for " + channel.Marker + " ('" + value + "') is not a " +
                            "positive number. The engine parses it with parseDoubleSetting and " +
                            "would abort, or worse, a value of 0 would make every pixel a " +
                            "candidate."));
                        result.ThresholdPolicy.Add(channel.Marker + "=INVALID(" + value + ")");
                        continue;
                    }
                    result.ThresholdPolicy.Add(
                        channel.Marker + "=fixed_predeclared(" +
                        parsed.ToString("R", CultureInfo.InvariantCulture) + ")");
                }
            }

            result.Exploratory = adaptive.Count > 0;

            if (adaptive.Count > 0 && request.Route != ImageRoute.LegacyFiji172)
            {
                string channelList = string.Join(", ", adaptive.ToArray());
                string areaNote = adaptiveArea.Count > 0
                    ? "\r\n\r\n" + string.Join(", ", adaptiveArea.ToArray()) +
                      " contribute AREA endpoints. Adaptive Otsu on an area endpoint is the " +
                      "failure that has already happened here: an uninfected control read 4.95% " +
                      "KRT5+ at an Otsu threshold of 54.6, inside that channel's noise floor, " +
                      "and a background-dominated tile can report KRT5_pod_area_frac ~0.89."
                    : "";
                string unavailableNote = notThresholdable.Count > 0
                    ? "\r\n\r\nNo IFQ_<MARKER>_THRESHOLD variable exists for: " +
                      string.Join(", ", notThresholdable.ToArray()) +
                      ". Those channels cannot be frozen from this launcher at all."
                    : "";

                if (request.Tier == RunTier.Confirmatory)
                {
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "H2_ADAPTIVE_IN_CONFIRMATORY",
                        "Confirmatory tier: every analysis channel must carry a fixed, " +
                        "control-derived threshold. These do not: " + channelList +
                        areaNote + unavailableNote));
                }
                else if (!spec.ThresholdsMayBeOmitted)
                {
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "H2_ADAPTIVE_ON_WHOLE_SLIDE",
                        "Whole-slide runs measure hundreds of tiles, and the threshold is chosen " +
                        "per region. Tiles that are mostly background get a threshold from " +
                        "background. That does not give an uncertain endpoint, it gives a wrong " +
                        "one. No fixed threshold for: " + channelList + areaNote + unavailableNote));
                }
                else
                {
                    result.RequirePhrase(ExploratoryPhrase);
                    result.Findings.Add(new GateFinding(
                        Severity.Confirm, "H2_ADAPTIVE_EXPLORATORY",
                        "No fixed threshold for: " + channelList + ".\r\n" +
                        "Those channels will use per-region adaptive Otsu, which the engine " +
                        "itself records as 'adaptive_otsu_exploratory'. The run is usable for " +
                        "looking, never for reporting." + areaNote + unavailableNote,
                        ExploratoryStamp));
                }
            }

            // ---------------------------------------------------------
            // H3. IFQ_MIN_INCLUDED_NUCLEI defaults to 1
            //     IF_Quant_Pipeline.groovy:329  envInt(..., 1)
            //     A region below the floor throws inside the region loop and
            //     kills the whole image: its tissue area AND its numerator
            //     vanish from run_summary.csv. Pooled fractions then quietly
            //     lose their sparse regions from the denominator.
            // ---------------------------------------------------------
            if (spec.WritesMinIncludedNuclei)
            {
                if (request.MinIncludedNuclei < 0)
                {
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "H3_NEGATIVE",
                        "Minimum nuclei per region must be zero or a positive integer; the " +
                        "engine rejects anything else (IF_Quant_Pipeline.groovy:395)."));
                }
                else if (request.MinIncludedNuclei > 0)
                {
                    bool hasAreaEndpoint = panel != null && panel.AreaMarkers.Count > 0;
                    if (!spec.ThresholdsMayBeOmitted)
                    {
                        result.Findings.Add(new GateFinding(
                            Severity.Block, "H3_FLOOR_ON_WHOLE_SLIDE",
                            "Minimum nuclei per region is " + request.MinIncludedNuclei +
                            ". On a tiled slide the sparse tiles are exactly the edge and " +
                            "alveolar tiles, and dropping them removes their tissue area from " +
                            "the slide total while their neighbours keep theirs. Set it to 0."));
                    }
                    else if (hasAreaEndpoint)
                    {
                        result.Findings.Add(new GateFinding(
                            Severity.Block, "H3_FLOOR_WITH_AREA_ENDPOINT",
                            "Minimum nuclei per region is " + request.MinIncludedNuclei +
                            ", and this panel has area endpoints (" +
                            DescribeMarkers(panel.AreaMarkers) + "). A dropped region takes its " +
                            "tissue area out of the denominator but leaves the other regions' " +
                            "numerators in, so the reported fraction rises for a reason that has " +
                            "nothing to do with biology. Set it to 0."));
                    }
                    else
                    {
                        result.Findings.Add(new GateFinding(
                            Severity.Warn, "H3_FLOOR_SET",
                            "Minimum nuclei per region is " + request.MinIncludedNuclei +
                            ". Regions with fewer accepted nuclei are dropped, and their tissue " +
                            "area is dropped with them."));
                    }
                }
            }
            else if (request.Route == ImageRoute.LegacyFiji172)
            {
                result.Findings.Add(new GateFinding(
                    Severity.Note, "H3_LEGACY_ENGINE_DEFAULT",
                    "Legacy mode does not write IFQ_MIN_INCLUDED_NUCLEI, because v1.7.2 never " +
                    "wrote it. The engine default of 1 therefore applies -- which is exactly " +
                    "what the original run did, and is the point of this mode."));
            }

            // ---------------------------------------------------------
            // H4. IFQ_OUTPUT_DIR must be empty or the engine aborts.
            //     IF_Quant_Pipeline.groovy:3479-3484
            // ---------------------------------------------------------
            if (request.OutputDirectoryExistsAndIsNonEmpty)
            {
                result.Findings.Add(new GateFinding(
                    Severity.Block, "H4_OUTPUT_NOT_EMPTY",
                    "The run output folder already contains files. The engine aborts on a " +
                    "non-empty IFQ_OUTPUT_DIR, because stale masks and cell tables from an " +
                    "earlier run would otherwise be mixed into this one. The launcher always " +
                    "creates a fresh timestamped folder, so seeing this means something else " +
                    "wrote into it."));
            }
            if (string.IsNullOrEmpty(request.OutputBase))
            {
                result.Findings.Add(new GateFinding(
                    Severity.Block, "H4_OUTPUT_BASE_UNSET",
                    "Choose the output parent folder. A timestamped run folder is created " +
                    "inside it."));
            }

            // ---------------------------------------------------------
            // Route-2 specific pre-flight
            // ---------------------------------------------------------
            if (request.Route == ImageRoute.IfSlideScanner)
            {
                if (string.IsNullOrEmpty(request.WsiInput))
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "WSI_INPUT_UNSET",
                        "Choose the .vsi slide file, or a folder containing them."));
                if (string.IsNullOrEmpty(request.WsiOutput))
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "WSI_OUTPUT_UNSET",
                        "Choose the stage 1 output root. Tiles, ROI sets and " +
                        "stage1_manifest.json are written there and are read back by stages 2 " +
                        "and 3."));
                if (request.PanelWasAuto)
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "WSI_AUTO_PANEL",
                        "AUTO panel detection is not offered on the whole-slide route. Stage 1 " +
                        "writes the panel into every tile's samplesheet row before Fiji sees a " +
                        "tile, and 'panel' is a grouping key downstream -- two panels for one " +
                        "animal silently split it into two rows."));
                if (string.IsNullOrEmpty(request.SlideMetadataCsv))
                    result.Findings.Add(new GateFinding(
                        Severity.Warn, "WSI_NO_SLIDE_METADATA",
                        "No slide metadata CSV. Stage 1 will not be able to stamp mouse_id, " +
                        "genotype and condition into the tile samplesheet, and the mouse-level " +
                        "aggregation would have to be done by hand later."));
            }

            // ---------------------------------------------------------
            // Advanced free-text keys (v1.7.2 checked shape only)
            // ---------------------------------------------------------
            HashSet<string> panelTokens = panel == null ? null : panel.Tokens;
            foreach (KeyValuePair<string, string> item in ParseAdvanced(request.AdvancedText, result))
            {
                if (EnvSurface.ProtectedKeys.Contains(item.Key))
                {
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "ADV_PROTECTED",
                        item.Key + " is controlled by the launcher interface and cannot be " +
                        "overridden in Advanced settings."));
                    continue;
                }
                EnvClassification cls = EnvSurface.Classify(item.Key, panelTokens);
                if (cls.Kind == EnvKind.Unknown)
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "ADV_UNKNOWN_KEY",
                        item.Key + " is " + cls.Detail));
                else if (cls.Kind == EnvKind.UnknownMarker)
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "ADV_UNKNOWN_MARKER",
                        item.Key + ": " + cls.Detail));
                else if (cls.Kind == EnvKind.Stage1Static &&
                         request.Route != ImageRoute.IfSlideScanner)
                    result.Findings.Add(new GateFinding(
                        Severity.Block, "ADV_STAGE1_ON_FIJI_ROUTE",
                        item.Key + " is a stage 1 (QuPath) setting and this route never runs " +
                        "stage 1, so it would have no effect."));
            }

            // ---------------------------------------------------------
            // v1.7.2 behaviours that are kept
            // ---------------------------------------------------------
            if (panel != null && HasChannel(panel, "YAP") &&
                !string.Equals(request.Projection, "single", StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(request.Projection, "layer_aware", StringComparison.OrdinalIgnoreCase))
                result.Findings.Add(new GateFinding(
                    Severity.Block, "YAP_NEEDS_SINGLE_PLANE",
                    "This panel contains YAP nuclear-to-cytoplasmic analysis, which requires " +
                    "Z-stack handling = single or layer_aware. A maximum-intensity projection " +
                    "corrupts the ratio."));

            if (panel != null && HasChannel(panel, "AcTub") &&
                !string.Equals(request.WholeFieldCompartment, "airway",
                               StringComparison.OrdinalIgnoreCase))
                result.Findings.Add(new GateFinding(
                    Severity.Warn, "ACTUB_NO_AIRWAY_ROI",
                    "Without an independently assigned airway ROI, only nuclei meeting the " +
                    "strict bright/locally dense/size-bounded apical ciliary rule can be " +
                    "exploratory positive. All other AcTub calls stay indeterminate, not " +
                    "negative."));

            if (request.Tier == RunTier.Dry)
                result.Findings.Add(new GateFinding(
                    Severity.Warn, "TIER_DRY",
                    "Dry tier: this is a smoke test. Nothing it produces may be aggregated."));

            // A Block outranks a Confirm: if the run cannot start, no phrase
            // should be requested, or the dialog implies it can be unlocked.
            if (result.Blocked)
                result.RequiredPhrases.Clear();

            return result;
        }

        private static bool HasChannel(PanelDef panel, string marker)
        {
            string token = PanelRegistry.NormalizeMarkerToken(marker);
            foreach (ChannelDef c in panel.Channels)
                if (string.Equals(c.Token, token, StringComparison.Ordinal)) return true;
            return false;
        }

        private static string DescribeMarkers(List<ChannelDef> channels)
        {
            List<string> names = new List<string>();
            foreach (ChannelDef c in channels) names.Add(c.Marker);
            return string.Join(", ", names.ToArray());
        }

        /// Shape-parses the Advanced box. Shape problems become Block findings
        /// rather than exceptions so the live gate summary can show them while
        /// the user is still typing.
        public static Dictionary<string, string> ParseAdvanced(string text, GateResult sink)
        {
            Dictionary<string, string> values =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            string[] lines = (text ?? "").Replace("\r\n", "\n").Replace('\r', '\n').Split('\n');
            for (int index = 0; index < lines.Length; index++)
            {
                string line = lines[index].Trim();
                if (line.Length == 0 || line.StartsWith("#", StringComparison.Ordinal)) continue;
                int equals = line.IndexOf('=');
                if (equals <= 0)
                {
                    if (sink != null)
                        sink.Findings.Add(new GateFinding(
                            Severity.Block, "ADV_SHAPE",
                            "Advanced setting line " + (index + 1) + " must use KEY=VALUE."));
                    continue;
                }
                string key = line.Substring(0, equals).Trim().ToUpperInvariant();
                string value = line.Substring(equals + 1).Trim();
                if (!Regex.IsMatch(key, "^IFQ_[A-Z0-9_]+$"))
                {
                    if (sink != null)
                        sink.Findings.Add(new GateFinding(
                            Severity.Block, "ADV_KEY_SHAPE",
                            "Advanced setting line " + (index + 1) +
                            " has an invalid IFQ key: " + key));
                    continue;
                }
                if (value.Length == 0)
                {
                    if (sink != null)
                        sink.Findings.Add(new GateFinding(
                            Severity.Block, "ADV_EMPTY_VALUE",
                            key + " has an empty value. The engine treats an empty variable as " +
                            "absent and falls back silently, so this line would look like " +
                            "configuration and do nothing."));
                    continue;
                }
                values[key] = value;
            }
            return values;
        }
    }

    // =================================================================
    // 6. TOOL INVENTORY
    // =================================================================

    /// <summary>
    /// What is actually installed. Resolution only; no process is started here.
    /// </summary>
    internal sealed class ToolInventory
    {
        public string FijiExecutable;
        public string FijiDirectory;
        public string JavaExecutable;
        public string Ij1PatcherJar;
        public string QuPathExecutable;
        public string PythonExecutable;

        public bool FijiPresent { get { return !string.IsNullOrEmpty(FijiExecutable); } }
        public bool BundledJvmPresent
        {
            get
            {
                return !string.IsNullOrEmpty(JavaExecutable) &&
                       !string.IsNullOrEmpty(Ij1PatcherJar);
            }
        }
        public bool QuPathPresent { get { return !string.IsNullOrEmpty(QuPathExecutable); } }
        public bool PythonPresent { get { return !string.IsNullOrEmpty(PythonExecutable); } }

        public static ToolInventory Resolve(
            string fijiPath, string quPathPath, string pythonPath, string windowsArchitecture)
        {
            ToolInventory tools = new ToolInventory();
            tools.FijiExecutable = ResolveFiji(fijiPath, windowsArchitecture);
            tools.FijiDirectory = ResolveFijiDirectory(fijiPath, tools.FijiExecutable);
            if (tools.FijiDirectory != null)
            {
                tools.JavaExecutable = FindFirst(
                    Path.Combine(tools.FijiDirectory, "java"), "java.exe");
                tools.Ij1PatcherJar = FindFirst(
                    Path.Combine(tools.FijiDirectory, "jars"), "ij1-patcher-*.jar");
            }
            tools.QuPathExecutable = ResolveQuPath(quPathPath);
            tools.PythonExecutable = ResolvePython(pythonPath);
            return tools;
        }

        private static string ResolveFijiDirectory(string fijiPath, string resolvedExe)
        {
            if (!string.IsNullOrEmpty(fijiPath) && Directory.Exists(fijiPath))
                return Path.GetFullPath(fijiPath);
            if (!string.IsNullOrEmpty(resolvedExe))
                return Path.GetDirectoryName(Path.GetFullPath(resolvedExe));
            return null;
        }

        /// Identical preference order to v1.7.2's ResolveFijiExecutable.
        public static string ResolveFiji(string path, string architecture)
        {
            if (string.IsNullOrEmpty(path)) return null;
            if (File.Exists(path) && path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                return Path.GetFullPath(path);
            if (!Directory.Exists(path)) return null;

            string[] preferred;
            if (string.Equals(architecture, "ARM64", StringComparison.OrdinalIgnoreCase))
                preferred = new string[]
                {
                    "fiji-windows-arm64.exe", "fiji-windows-x64.exe",
                    "ImageJ-win64.exe", "fiji-windows.exe", "ImageJ.exe"
                };
            else
                preferred = new string[]
                {
                    "fiji-windows-x64.exe", "ImageJ-win64.exe",
                    "fiji-windows.exe", "ImageJ.exe", "fiji-windows-arm64.exe"
                };
            foreach (string name in preferred)
            {
                string candidate = Path.Combine(path, name);
                if (File.Exists(candidate)) return candidate;
            }
            string[] executables = Directory.GetFiles(path, "*.exe", SearchOption.TopDirectoryOnly);
            Array.Sort(executables, StringComparer.OrdinalIgnoreCase);
            foreach (string candidate in executables)
            {
                string name = Path.GetFileName(candidate);
                if (name.IndexOf("fiji", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    name.IndexOf("imagej", StringComparison.OrdinalIgnoreCase) >= 0)
                    return candidate;
            }
            return null;
        }

        public static string ResolveQuPath(string path)
        {
            if (string.IsNullOrEmpty(path)) return null;
            if (File.Exists(path) && path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                return Path.GetFullPath(path);
            if (!Directory.Exists(path)) return null;
            // The console build is required: the windowed .exe detaches and the
            // launcher would see an immediate exit 0 with no script output.
            string[] executables = Directory.GetFiles(path, "*.exe", SearchOption.TopDirectoryOnly);
            Array.Sort(executables, StringComparer.OrdinalIgnoreCase);
            foreach (string candidate in executables)
            {
                string name = Path.GetFileName(candidate);
                if (name.IndexOf("qupath", StringComparison.OrdinalIgnoreCase) >= 0 &&
                    name.IndexOf("console", StringComparison.OrdinalIgnoreCase) >= 0)
                    return candidate;
            }
            return null;
        }

        public static string ResolvePython(string path)
        {
            if (string.IsNullOrEmpty(path)) return null;
            if (File.Exists(path)) return Path.GetFullPath(path);
            return null;
        }

        private static string FindFirst(string root, string pattern)
        {
            try
            {
                if (!Directory.Exists(root)) return null;
                string[] hits = Directory.GetFiles(root, pattern, SearchOption.AllDirectories);
                if (hits.Length == 0) return null;
                Array.Sort(hits, StringComparer.OrdinalIgnoreCase);
                return hits[0];
            }
            catch { return null; }
        }
    }

    // =================================================================
    // 7. FIJI COMMAND LINES
    // =================================================================

    /// <summary>
    /// The single place where a run environment is handed to a child process.
    ///
    /// v1.7.2 did this inline in StartFijiRun: strip every inherited IFQ_*,
    /// then copy ours in. It is factored out here for one reason -- the legacy
    /// equivalence harness runs THIS code, so what it proves is what the
    /// launcher actually does, not what a copy of it does.
    /// </summary>
    internal static class EnvironmentApply
    {
        /// v1.7.2's ClearIfqEnvironment, verbatim in behaviour: a stale IFQ_*
        /// inherited from the shell must never reach the engine, because the
        /// engine reads every one of them and falls back silently.
        public static void ClearIfq(System.Collections.Specialized.StringDictionary environment)
        {
            List<string> stale = new List<string>();
            foreach (string key in environment.Keys)
                if (key != null && key.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase))
                    stale.Add(key);
            foreach (string key in stale)
                environment.Remove(key);
        }

        public static void Apply(
            System.Diagnostics.ProcessStartInfo psi, Dictionary<string, string> env)
        {
            ClearIfq(psi.EnvironmentVariables);
            foreach (KeyValuePair<string, string> item in env)
                psi.EnvironmentVariables[item.Key] = item.Value;
        }
    }

    internal static class FijiCommand
    {
        public static string Quote(string value)
        {
            return "\"" + (value ?? "").Replace("\"", "\\\"") + "\"";
        }

        /// v1.7.2, verbatim (IFQuantLauncher.cs StartFijiRun):
        ///   psi.Arguments = "--headless --console --run " + QuoteArgument(script)
        /// Route 4 uses this and only this.
        public static string LauncherExeArguments(string scriptPath)
        {
            return "--headless --console --run " + Quote(scriptPath);
        }

        /// The bundled JVM, argument-for-argument as scripts/Invoke-Stage2Sharded.ps1
        /// starts it. The ij1-patcher javaagent is what makes headless ImageJ1
        /// work; without it the engine throws on the first IJ call.
        public static string BundledJvmArguments(
            string fijiDirectory, string ij1PatcherJar, string scriptPath, string maxHeap)
        {
            string classPath =
                Path.Combine(fijiDirectory, "jars\\*") + ";" +
                Path.Combine(fijiDirectory, "plugins\\*");
            StringBuilder args = new StringBuilder();
            args.Append("--add-opens=java.base/java.lang=ALL-UNNAMED");
            args.Append(" -javaagent:").Append(Quote(ij1PatcherJar + "=init"));
            args.Append(" -Djava.awt.headless=true");
            args.Append(" -Dplugins.dir=").Append(Quote(fijiDirectory));
            args.Append(" -Xmx").Append(string.IsNullOrEmpty(maxHeap) ? "8g" : maxHeap);
            args.Append(" -cp ").Append(Quote(classPath));
            args.Append(" net.imagej.Main --headless --run ").Append(Quote(scriptPath));
            return args.ToString();
        }
    }

    // =================================================================
    // 8. ENVIRONMENT BUILDERS (routes 1 and 2)
    // =================================================================

    internal static class RunEnvironment
    {
        /// <summary>
        /// The stage 2 (Fiji) environment for routes 1 and 2.
        /// Route 4 does NOT come through here: see LegacyProfile.
        /// </summary>
        public static Dictionary<string, string> BuildStage2(
            RunRequest request, PanelDef panel, HashSet<string> engineThresholdMarkers,
            string registryPath, string outputDirectory, string inputDirectory,
            string autoPanelMapPath, bool previewOnly)
        {
            // R3 fail-closed. Reaching this with route 3 means the UI veto and
            // the gate were both bypassed programmatically. Throw before a
            // single variable is assigned, so no partial environment can escape
            // and no process can be started from it.
            if (request.Route == ImageRoute.HeBrightfield)
            {
                if (!LauncherBuild.BrightfieldRouteEnabled)
                    throw new InvalidOperationException(
                        "Route 3 (H&E / brightfield) is not available in this build and no run " +
                        "environment will be produced for it.\r\n\r\n" +
                        LauncherBuild.BrightfieldDisabledReason);
                // The flag is on but nothing has been wired to it yet. Refuse
                // here rather than fall through and emit a fluorescence
                // environment for a brightfield slide.
                throw new NotImplementedException(
                    "LauncherBuild.BrightfieldRouteEnabled is true, but no brightfield " +
                    "measurement module is wired into RunEnvironment.BuildStage2. Build the " +
                    "brightfield environment here before shipping a build with the flag on.");
            }
            if (request.Route == ImageRoute.LegacyFiji172)
                throw new InvalidOperationException(
                    "Route 4 must be built by LegacyProfile.BuildEnvironment, which is a " +
                    "transcription of v1.7.2. Building it here would add variables v1.7.2 " +
                    "never wrote and the run would no longer be legacy.");

            // H1. IFQ_PANEL is written first and is never optional.
            string panelKey = panel != null ? panel.Key : (request.PanelKey ?? "");
            if (panelKey.Trim().Length == 0)
                throw new InvalidOperationException(
                    "Refusing to build a run environment without IFQ_PANEL. The engine's " +
                    "fallback is panel T (IF_Quant_Pipeline.groovy:154), which measures an " +
                    "unrelated skin sample's channel map and reports numbers for it.");

            Dictionary<string, string> env =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

            env["IFQ_INPUT_DIR"] = Path.GetFullPath(inputDirectory);
            env["IFQ_OUTPUT_DIR"] = outputDirectory;
            env["IFQ_PANEL"] = panelKey;
            env["IFQ_MARKER_REGISTRY"] = registryPath;
            if (!string.IsNullOrEmpty(autoPanelMapPath))
                env["IFQ_PANEL_MAP_PATH"] = autoPanelMapPath;
            if (!string.IsNullOrEmpty(request.PanelConfigJson))
                env["IFQ_PANEL_CONFIG"] = Path.GetFullPath(request.PanelConfigJson);
            env["IFQ_RECURSIVE"] = request.Recursive ? "true" : "false";
            env["IFQ_INCLUDE_REGEX"] =
                string.IsNullOrEmpty(request.IncludeRegex) ? ".*" : request.IncludeRegex;
            env["IFQ_MAX_IMAGES"] = request.MaxImages.ToString(CultureInfo.InvariantCulture);
            env["IFQ_SEGMENTER"] = request.Segmenter;
            env["IFQ_PROJECTION"] = request.Projection;
            env["IFQ_SINGLE_PLANE"] = request.SinglePlane.ToString(CultureInfo.InvariantCulture);
            env["IFQ_EXPORT_DISPLAY_CHANNELS"] = "true";
            env["IFQ_DISPLAY_PREVIEW_ONLY"] = previewOnly ? "true" : "false";
            env["IFQ_TISSUE_MODE"] = request.TissueMode;
            env["IFQ_COMPARTMENT_MODE"] = request.CompartmentMode;
            env["IFQ_WHOLE_FIELD_COMPARTMENT"] = request.WholeFieldCompartment;
            // H4. Never settable from the UI or the Advanced box; a non-empty
            // output folder must abort the engine rather than be merged into.
            env["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "false";
            env["IFQ_MORPHOLOGY_PRIMARY"] = "true";

            // H3. Written explicitly on every route that is allowed to write it,
            // so the engine's default of 1 can never apply by omission.
            env["IFQ_MIN_INCLUDED_NUCLEI"] =
                request.MinIncludedNuclei.ToString(CultureInfo.InvariantCulture);

            // H2. One variable per analysis channel that has a value AND that the
            // engine actually reads. A marker outside the engine's closed
            // threshold list gets no variable at all, so the run record cannot
            // claim a fixed cutoff the engine never saw.
            if (panel != null)
            {
                foreach (ChannelDef channel in panel.AnalysisChannels)
                {
                    if (engineThresholdMarkers != null &&
                        !engineThresholdMarkers.Contains(channel.Token))
                        continue;
                    string value;
                    if (request.Thresholds.TryGetValue(channel.Token, out value) &&
                        !string.IsNullOrEmpty((value ?? "").Trim()))
                        env[channel.ThresholdEnvName] = value.Trim();
                }
            }

            // Route 2 forces the tile-appropriate settings. Tiles are single
            // plane, in one flat folder, and every one of them must be measured
            // or the slide area no longer adds up.
            if (request.Route == ImageRoute.IfSlideScanner)
            {
                env["IFQ_PROJECTION"] = "max";
                env["IFQ_SINGLE_PLANE"] = "-1";
                env["IFQ_RECURSIVE"] = "false";
                env["IFQ_INCLUDE_REGEX"] = ".*";
                env["IFQ_MAX_IMAGES"] = "0";
                env["IFQ_MIN_INCLUDED_NUCLEI"] = "0";
            }

            // Advanced free text last, and only for names the launcher does not
            // own. The gate has already blocked unknown and protected names.
            foreach (KeyValuePair<string, string> item in
                     FailClosedGate.ParseAdvanced(request.AdvancedText, null))
            {
                if (EnvSurface.ProtectedKeys.Contains(item.Key)) continue;
                env[item.Key] = item.Value;
            }
            return env;
        }

        /// Stage 1 (QuPath) environment for route 2.
        public static Dictionary<string, string> BuildStage1(RunRequest request, string panelKey)
        {
            Dictionary<string, string> env =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            env["IFQ_WSI_INPUT"] = Path.GetFullPath(request.WsiInput);
            env["IFQ_WSI_OUTPUT"] = Path.GetFullPath(request.WsiOutput);
            // One panel control writes both names. 'panel' is a grouping key
            // downstream, so a mismatch splits one animal into two rows.
            env["IFQ_WSI_PANEL"] = panelKey;
            env["IFQ_WSI_RESUME"] = request.WsiResume ? "true" : "false";
            env["IFQ_WSI_PARTITION_DAMAGE"] = request.WsiPartitionDamage ? "true" : "false";
            env["IFQ_WSI_DRY_RUN"] = request.Tier == RunTier.Dry ? "true" : "false";
            if (request.WsiMaxTilesPerSlide > 0)
                env["IFQ_WSI_MAX_TILES_PER_SLIDE"] =
                    request.WsiMaxTilesPerSlide.ToString(CultureInfo.InvariantCulture);
            if (!string.IsNullOrEmpty(request.SlideMetadataCsv))
                env["IFQ_WSI_SLIDE_METADATA"] = Path.GetFullPath(request.SlideMetadataCsv);

            foreach (KeyValuePair<string, string> item in
                     FailClosedGate.ParseAdvanced(request.AdvancedText, null))
            {
                if (EnvSurface.ProtectedKeys.Contains(item.Key)) continue;
                if (EnvSurface.Stage1Static.Contains(item.Key)) env[item.Key] = item.Value;
            }
            return env;
        }
    }

    // =================================================================
    // 9. LEGACY MODE -- byte-for-byte v1.7.2
    // =================================================================

    /// <summary>
    /// Route 4 exists so an analysis produced by IFQuantLauncher-v1.7.2.exe can
    /// be reproduced after the launcher gains routes. It is defined by an exact,
    /// testable property, not by intent:
    ///
    ///   For the same inputs, the set of environment variables the launcher
    ///   passes to Fiji, and the Fiji command line, are IDENTICAL to v1.7.2.
    ///
    /// BuildEnvironment below is a transcript of IFQuantLauncher.cs (v1.7.2)
    /// ReadAndValidateConfiguration lines 1394-1424, with nothing added. In
    /// particular it does NOT write IFQ_MIN_INCLUDED_NUCLEI and does NOT write
    /// any IFQ_*_THRESHOLD, because v1.7.2 never wrote them either -- and both
    /// change the numbers.
    /// </summary>
    internal static class LegacyProfile
    {
        public const string FrozenVersion = "1.7.2.0";

        /// Exactly the nineteen keys v1.7.2 writes, in the order it writes them.
        /// IFQ_PANEL_MAP_PATH and IFQ_PANEL_CONFIG are conditional there too.
        public static readonly string[] KeyOrder = new string[]
        {
            "IFQ_INPUT_DIR", "IFQ_OUTPUT_DIR", "IFQ_PANEL", "IFQ_MARKER_REGISTRY",
            "IFQ_PANEL_MAP_PATH", "IFQ_PANEL_CONFIG", "IFQ_RECURSIVE", "IFQ_INCLUDE_REGEX",
            "IFQ_MAX_IMAGES", "IFQ_SEGMENTER", "IFQ_PROJECTION", "IFQ_SINGLE_PLANE",
            "IFQ_EXPORT_DISPLAY_CHANNELS", "IFQ_DISPLAY_PREVIEW_ONLY", "IFQ_TISSUE_MODE",
            "IFQ_COMPARTMENT_MODE", "IFQ_WHOLE_FIELD_COMPARTMENT",
            "IFQ_ALLOW_NONEMPTY_OUTPUT", "IFQ_MORPHOLOGY_PRIMARY"
        };

        public static Dictionary<string, string> BuildEnvironment(
            string inputDirectory, string outputDirectory, string panelKey, string registryPath,
            string panelMapPath, string panelConfigPath, bool recursive, string includeRegex,
            int maxImages, string segmenter, string projection, int singlePlane,
            bool previewOnly, string tissueMode, string compartmentMode,
            string wholeFieldCompartment)
        {
            Dictionary<string, string> env =
                new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            env["IFQ_INPUT_DIR"] = Path.GetFullPath(inputDirectory);
            env["IFQ_OUTPUT_DIR"] = outputDirectory;
            env["IFQ_PANEL"] = panelKey;
            env["IFQ_MARKER_REGISTRY"] = registryPath;
            if (!string.IsNullOrEmpty(panelMapPath))
                env["IFQ_PANEL_MAP_PATH"] = panelMapPath;
            if (!string.IsNullOrEmpty(panelConfigPath))
                env["IFQ_PANEL_CONFIG"] = Path.GetFullPath(panelConfigPath);
            env["IFQ_RECURSIVE"] = recursive ? "true" : "false";
            env["IFQ_INCLUDE_REGEX"] = includeRegex;
            env["IFQ_MAX_IMAGES"] = maxImages.ToString(CultureInfo.InvariantCulture);
            env["IFQ_SEGMENTER"] = segmenter;
            env["IFQ_PROJECTION"] = projection;
            env["IFQ_SINGLE_PLANE"] = singlePlane.ToString(CultureInfo.InvariantCulture);
            env["IFQ_EXPORT_DISPLAY_CHANNELS"] = "true";   // DisplayChannelExportSetting(_)
            env["IFQ_DISPLAY_PREVIEW_ONLY"] = previewOnly ? "true" : "false";
            env["IFQ_TISSUE_MODE"] = tissueMode;
            env["IFQ_COMPARTMENT_MODE"] = compartmentMode;
            env["IFQ_WHOLE_FIELD_COMPARTMENT"] = wholeFieldCompartment;
            env["IFQ_ALLOW_NONEMPTY_OUTPUT"] = "false";
            env["IFQ_MORPHOLOGY_PRIMARY"] = "true";
            return env;
        }

        /// Canonical serialisation for the equivalence test. Ordinal-sorted, so
        /// dictionary iteration order cannot affect the comparison.
        public static string Canonicalize(Dictionary<string, string> env)
        {
            List<string> keys = new List<string>(env.Keys);
            keys.Sort(StringComparer.Ordinal);
            StringBuilder text = new StringBuilder();
            foreach (string key in keys)
                text.Append(key).Append('=').Append(env[key]).Append('\n');
            return text.ToString();
        }

        public static string Fingerprint(Dictionary<string, string> env)
        {
            using (SHA256 algorithm = SHA256.Create())
            {
                byte[] hash = algorithm.ComputeHash(
                    new UTF8Encoding(false).GetBytes(Canonicalize(env)));
                StringBuilder text = new StringBuilder(hash.Length * 2);
                foreach (byte b in hash)
                    text.Append(b.ToString("x2", CultureInfo.InvariantCulture));
                return text.ToString();
            }
        }

        /// The v1.7.2 Fiji command line, verbatim. Legacy mode keeps the
        /// launcher-exe invocation even where the bundled JVM would be more
        /// robust, because "more robust" is still a behaviour change.
        public static string CommandLine(string scriptPath)
        {
            return FijiCommand.LauncherExeArguments(scriptPath);
        }

        /// Deterministic fixture for the equivalence test. Fixed strings only,
        /// so the fingerprint is machine-independent.
        public static Dictionary<string, string> Fixture()
        {
            return BuildEnvironment(
                @"C:\fixture\input",
                @"C:\fixture\output\IFQ_run_20260807_000000",
                "LEFT",
                @"C:\fixture\runtime\config\lung_marker_registry.json",
                null, null, true, ".*", 0, "classic", "layer_aware", -1, false,
                "auto", "required", "unassigned");
        }

        // -----------------------------------------------------------------
        // Legacy is about NUMBERS, not just variables.
        //
        // The environment is half of what determines a result. The other half is
        // the pipeline and registry embedded at build time and extracted to a
        // version-keyed runtime directory. A v1.8.0 build made from a repo whose
        // IF_Quant_Pipeline.groovy has moved on would give DIFFERENT numbers from
        // an IDENTICAL environment.
        //
        // So route 4 checks the artefacts it is about to run and downgrades
        // itself honestly when they are not the v1.7.2 ones. It does not refuse:
        // refusing would make the mode useless after the first engine change.
        // -----------------------------------------------------------------

        public const string V172PipelineSha256 =
            "defffe6703e6da10ef7810977cb16473c0f36bf81ffa75fbbf6b81066de140ec";
        public const string V172RegistrySha256 =
            "20c6859d8b7d1114b5476eefc90d8c92affa78d9a59d1be11d2449ba98460b37";
        public const string V172ExeSha256 =
            "bd8e71764013c2dc7de0d43b76457fca136b50be20fc89e8d19d85fb4cb4a1c4";
        public const string V172ExeArchivePath = "legacy/launchers/IFQuantLauncher-v1.7.2.exe";

        /// <returns>null when the embedded artefacts are the v1.7.2 ones;
        /// otherwise the exact reason legacy mode is only approximate.</returns>
        public static string CheckEmbeddedArtefacts(
            string actualPipelineSha256, string actualRegistrySha256)
        {
            List<string> drift = new List<string>();
            if (!string.Equals(actualPipelineSha256, V172PipelineSha256,
                               StringComparison.OrdinalIgnoreCase))
                drift.Add("IF_Quant_Pipeline.groovy: embedded " + actualPipelineSha256 +
                          ", v1.7.2 shipped " + V172PipelineSha256);
            if (!string.Equals(actualRegistrySha256, V172RegistrySha256,
                               StringComparison.OrdinalIgnoreCase))
                drift.Add("lung_marker_registry.json: embedded " + actualRegistrySha256 +
                          ", v1.7.2 shipped " + V172RegistrySha256);
            if (drift.Count == 0) return null;
            return
                "Legacy mode will pass Fiji the exact v1.7.2 environment, but the embedded " +
                "analysis artefacts are NOT the ones v1.7.2 shipped:\r\n  " +
                string.Join("\r\n  ", drift.ToArray()) + "\r\n\r\n" +
                "The environment is reproduced; the numbers may not be. To reproduce the " +
                "numbers, run the archived binary " + V172ExeArchivePath +
                " (sha256 " + V172ExeSha256 + ") instead.";
        }
    }

    // =================================================================
    // 10. THE RUN RECORD  (H5)
    // =================================================================

    /// <summary>
    /// launcher_run.txt, extended from v1.7.2's WriteLauncherRecord.
    ///
    /// H5: a run whose thresholds are not frozen must be visibly marked
    /// EXPLORATORY in whatever record the launcher writes. Three places carry
    /// it, because any one of them can be lost:
    ///   * the run folder NAME gains _EXPLORATORY (survives being moved);
    ///   * launcher_run.txt carries run_classification and threshold_policy;
    ///   * EXPLORATORY_DO_NOT_AGGREGATE.txt sits in the folder.
    ///
    /// The marker file is written AFTER the engine exits, never before. H4 means
    /// the engine aborts on a non-empty IFQ_OUTPUT_DIR, so a marker file written
    /// up front would break every exploratory run it was meant to label.
    /// </summary>
    internal static class RunRecord
    {
        public static string Build(
            RunRequest request, GateResult gate, Dictionary<string, string> env,
            string launcherVersion, string architecture, string fijiExecutable,
            string invocationDescription, int exitCode, string manifestStatus,
            string pipelineSha256, string registrySha256, string legacyNote)
        {
            RouteSpec spec = RouteCatalog.Describe(request.Route);
            StringBuilder record = new StringBuilder();
            record.AppendLine("IF Quant Launcher run record");
            record.AppendLine("launcher_version=" + launcherVersion);
            record.AppendLine("recorded_at=" +
                DateTimeOffset.Now.ToString("o", CultureInfo.InvariantCulture));
            record.AppendLine("route=" + (int)request.Route + " " + spec.DisplayName);
            record.AppendLine("tier=" + request.Tier.ToString().ToLowerInvariant());

            // H5. The one line an aggregation script can grep for.
            record.AppendLine("run_classification=" +
                (gate.Exploratory ? "EXPLORATORY_DO_NOT_AGGREGATE" : "THRESHOLDS_FROZEN"));
            record.AppendLine("thresholds_frozen=" + (gate.Exploratory ? "false" : "true"));
            record.AppendLine("windows_architecture=" + architecture);
            record.AppendLine("fiji_executable=" + fijiExecutable);
            record.AppendLine("fiji_invocation=" + invocationDescription);
            record.AppendLine("fiji_exit_code=" + exitCode.ToString(CultureInfo.InvariantCulture));
            record.AppendLine("manifest_status=" + manifestStatus);
            record.AppendLine("pipeline_sha256=" + pipelineSha256);
            record.AppendLine("registry_sha256=" + registrySha256);
            if (!string.IsNullOrEmpty(legacyNote))
            {
                record.AppendLine();
                record.AppendLine("[legacy_equivalence]");
                record.AppendLine(legacyNote);
            }

            record.AppendLine();
            record.AppendLine("[threshold_policy]");
            if (request.Route == ImageRoute.LegacyFiji172)
                record.AppendLine(
                    "legacy_mode=v1.7.2 wrote no IFQ_*_THRESHOLD, so every analysis channel " +
                    "uses the engine's adaptive Otsu exactly as it did then");
            else if (gate.ThresholdPolicy.Count == 0)
                record.AppendLine("(no analysis channels resolved)");
            else
                foreach (string line in gate.ThresholdPolicy)
                    record.AppendLine(line);

            record.AppendLine();
            record.AppendLine("[gate_findings]");
            if (gate.Findings.Count == 0)
                record.AppendLine("(none)");
            else
                foreach (GateFinding f in gate.Findings)
                    record.AppendLine(
                        f.Severity.ToString().ToUpperInvariant() + " " + f.Code + ": " +
                        OneLine(f.Message));

            record.AppendLine();
            record.AppendLine("[environment]");
            List<string> keys = new List<string>(env.Keys);
            keys.Sort(StringComparer.Ordinal);
            foreach (string key in keys)
                record.AppendLine(key + "=" + env[key]);
            return record.ToString();
        }

        public static string ExploratoryMarkerText(RunRequest request, GateResult gate)
        {
            StringBuilder text = new StringBuilder();
            text.AppendLine("EXPLORATORY RUN -- DO NOT AGGREGATE, DO NOT REPORT");
            text.AppendLine();
            text.AppendLine(
                "At least one analysis channel in this run had no fixed, control-derived");
            text.AppendLine(
                "threshold, so the engine chose one per region with adaptive Otsu and");
            text.AppendLine(
                "recorded the source as 'adaptive_otsu_exploratory'. On a region that is");
            text.AppendLine(
                "mostly background the threshold comes from background: an uninfected");
            text.AppendLine(
                "control has read 4.95% KRT5+ at an Otsu threshold of 54.6, inside that");
            text.AppendLine(
                "channel's noise floor, and a background-dominated tile can report a");
            text.AppendLine("KRT5 pod area fraction of about 0.89.");
            text.AppendLine();
            text.AppendLine("Per-channel threshold source:");
            foreach (string line in gate.ThresholdPolicy)
                text.AppendLine("  " + line);
            text.AppendLine();
            text.AppendLine("Route: " + RouteCatalog.Describe(request.Route).DisplayName);
            text.AppendLine("Tier:  " + request.Tier.ToString().ToLowerInvariant());
            text.AppendLine("Panel: " + request.PanelKey);
            text.AppendLine();
            text.AppendLine(
                "Freeze every threshold from stained controls and re-run before any number");
            text.AppendLine("here is used as an endpoint.");
            return text.ToString();
        }

        private static string OneLine(string value)
        {
            return Regex.Replace(value ?? "", "\\s+", " ").Trim();
        }
    }

    // =================================================================
    // 11. PRE-START ASSERTIONS
    //
    // The gate is advisory to the UI. These are the last line: they run
    // between "the user pressed OK" and "a process exists", and they throw.
    // =================================================================

    internal static class PreStartAssertions
    {
        /// H1 + H4, enforced against the environment that is actually about to
        /// be handed to the process, not against the UI state that produced it.
        public static void AssertStage2Environment(
            Dictionary<string, string> env, string outputDirectory, ImageRoute route)
        {
            if (route == ImageRoute.HeBrightfield && !LauncherBuild.BrightfieldRouteEnabled)
                throw new InvalidOperationException(
                    "Route 3 (H&E / brightfield) is not available in this build.\r\n\r\n" +
                    LauncherBuild.BrightfieldDisabledReason);

            string panel;
            if (!env.TryGetValue("IFQ_PANEL", out panel) ||
                string.IsNullOrEmpty((panel ?? "").Trim()))
                throw new InvalidOperationException(
                    "H1: IFQ_PANEL is missing from the run environment. The engine would fall " +
                    "back to panel T and report numbers for an unrelated channel map. The run " +
                    "was not started.");

            string allowNonEmpty;
            if (!env.TryGetValue("IFQ_ALLOW_NONEMPTY_OUTPUT", out allowNonEmpty) ||
                !string.Equals(allowNonEmpty, "false", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException(
                    "H4: IFQ_ALLOW_NONEMPTY_OUTPUT must be false. The launcher never merges a " +
                    "run into an existing output folder. The run was not started.");

            if (route != ImageRoute.LegacyFiji172)
            {
                string floor;
                if (!env.TryGetValue("IFQ_MIN_INCLUDED_NUCLEI", out floor) ||
                    string.IsNullOrEmpty((floor ?? "").Trim()))
                    throw new InvalidOperationException(
                        "H3: IFQ_MIN_INCLUDED_NUCLEI is missing from the run environment. The " +
                        "engine default is 1, which silently drops sparse regions and takes " +
                        "their tissue area out of the total. The run was not started.");
            }
            else if (env.ContainsKey("IFQ_MIN_INCLUDED_NUCLEI"))
            {
                throw new InvalidOperationException(
                    "Legacy mode wrote IFQ_MIN_INCLUDED_NUCLEI, which v1.7.2 never wrote. That " +
                    "changes which regions are measured, so the run would not be legacy. The " +
                    "run was not started.");
            }

            AssertOutputDirectoryEmpty(outputDirectory);
        }

        /// H4. The engine aborts on a non-empty IFQ_OUTPUT_DIR
        /// (IF_Quant_Pipeline.groovy:3479-3484). Catching it here costs
        /// milliseconds instead of a JVM start plus a stack trace.
        public static void AssertOutputDirectoryEmpty(string outputDirectory)
        {
            if (string.IsNullOrEmpty(outputDirectory)) return;
            if (!Directory.Exists(outputDirectory)) return;
            string[] entries = Directory.GetFileSystemEntries(outputDirectory);
            if (entries.Length == 0) return;
            throw new InvalidOperationException(
                "H4: the run output folder is not empty:\r\n" + outputDirectory + "\r\n\r\n" +
                "It contains " + entries.Length + " item(s), starting with '" +
                Path.GetFileName(entries[0]) + "'. The engine refuses a non-empty output " +
                "directory so stale masks and cell tables from an earlier run cannot be mixed " +
                "into this one. The run was not started.");
        }

        /// The v1.7.2 rule, kept: nothing inherited from the parent process's
        /// environment may reach the engine.
        public static List<string> InheritedIfqNames()
        {
            List<string> names = new List<string>();
            foreach (System.Collections.DictionaryEntry entry in
                     Environment.GetEnvironmentVariables())
            {
                string key = Convert.ToString(entry.Key, CultureInfo.InvariantCulture);
                if (key != null && key.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase))
                    names.Add(key);
            }
            names.Sort(StringComparer.OrdinalIgnoreCase);
            return names;
        }
    }
}

