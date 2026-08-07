import io, sys

p = sys.argv[1]
s = io.open(p, encoding='utf-8').read()

def sub(old, new):
    global s
    assert s.count(old) == 1, ("NOT FOUND OR AMBIGUOUS: " + old[:70])
    s = s.replace(old, new)

sub(
"""        private ToolInventory ResolveTools(RunRequest request)
        {
            return ToolInventory.Resolve(
                request.FijiPath, request.QuPathExecutable, request.PythonExecutable,
                GetWindowsArchitecture());
        }""",
"""        private ToolInventory cachedTools;
        private string cachedToolsKey;

        /// <summary>
        /// Resolving the tools walks the Fiji tree for java.exe and
        /// ij1-patcher-*.jar. That costs a few hundred milliseconds, and the
        /// live gate summary runs on every keystroke -- so without this cache
        /// every character typed into the filename filter paid for a recursive
        /// directory scan. The key is every input the resolution depends on.
        /// </summary>
        private ToolInventory ResolveTools(RunRequest request)
        {
            string key =
                (request.FijiPath ?? "") + "|" + (request.QuPathExecutable ?? "") + "|" +
                (request.PythonExecutable ?? "") + "|" + GetWindowsArchitecture();
            if (cachedTools != null &&
                string.Equals(cachedToolsKey, key, StringComparison.Ordinal))
                return cachedTools;
            cachedToolsKey = key;
            cachedTools = ToolInventory.Resolve(
                request.FijiPath, request.QuPathExecutable, request.PythonExecutable,
                GetWindowsArchitecture());
            return cachedTools;
        }""")

sub(
"""        private void OnRouteChanged()
        {
            ImageRoute route = SelectedRoute;
            RouteSpec spec = RouteCatalog.Describe(route);
""",
"""        private void OnRouteChanged()
        {
            // Every group box below is AutoSize, inside an AutoSize
            // TableLayoutPanel, inside an AutoScroll one. Toggling their
            // visibility one statement at a time re-lays the whole form once
            // per statement, which measured in SECONDS. One pass instead.
            SuspendLayout();
            try { OnRouteChangedCore(); }
            finally { ResumeLayout(true); }
        }

        private void OnRouteChangedCore()
        {
            ImageRoute route = SelectedRoute;
            RouteSpec spec = RouteCatalog.Describe(route);
""")

sub(
"""        private void RebuildThresholdGrid()
        {
            if (thresholdTable == null) return;
""",
"""        private void RebuildThresholdGrid()
        {
            if (thresholdTable == null) return;
            thresholdTable.SuspendLayout();
            try { RebuildThresholdGridCore(); }
            finally { thresholdTable.ResumeLayout(true); }
        }

        private void RebuildThresholdGridCore()
        {
""")

sub(
"""        private void RefreshGateSummary()
        {
            if (suppressGateRefresh || gateSummaryLabel == null) return;
            try
            {""",
"""        /// Assigning Text or BackColor on an AutoSize label inside an AutoSize
        /// table triggers a layout pass even when the value has not changed,
        /// and this method runs on every keystroke. Only assign what differs.
        private void SetGateSummary(string text, Color fore, Color back)
        {
            if (!string.Equals(gateSummaryLabel.Text, text, StringComparison.Ordinal))
                gateSummaryLabel.Text = text;
            if (gateSummaryLabel.ForeColor != fore) gateSummaryLabel.ForeColor = fore;
            if (gateSummaryLabel.BackColor != back) gateSummaryLabel.BackColor = back;
        }

        private void RefreshGateSummary()
        {
            if (suppressGateRefresh || gateSummaryLabel == null) return;
            try
            {""")

sub(
"""                    gateSummaryLabel.ForeColor = Color.White;
                    gateSummaryLabel.BackColor = Color.FromArgb(160, 30, 30);
                    gateSummaryLabel.Text = "Cannot start:  " + Summarise(gate, Severity.Block);
                    runButton.Enabled = false;""",
"""                    SetGateSummary("Cannot start:  " + Summarise(gate, Severity.Block),
                                   Color.White, Color.FromArgb(160, 30, 30));
                    runButton.Enabled = false;""")

sub(
"""                    gateSummaryLabel.ForeColor = Color.Black;
                    gateSummaryLabel.BackColor = Color.FromArgb(250, 220, 150);
                    gateSummaryLabel.Text =
                        "Will run FLAGGED (" + string.Join(" ", gate.FolderStamps().ToArray()) +
                        "):  " + Summarise(gate, Severity.Confirm);
                    runButton.Enabled = true;""",
"""                    SetGateSummary(
                        "Will run FLAGGED (" + string.Join(" ", gate.FolderStamps().ToArray()) +
                        "):  " + Summarise(gate, Severity.Confirm),
                        Color.Black, Color.FromArgb(250, 220, 150));
                    runButton.Enabled = true;""")

sub(
"""                    gateSummaryLabel.ForeColor = Color.White;
                    gateSummaryLabel.BackColor = Color.FromArgb(30, 110, 60);
                    gateSummaryLabel.Text = "Ready.  " + DescribeReadyState(request, gate);
                    runButton.Enabled = true;""",
"""                    SetGateSummary("Ready.  " + DescribeReadyState(request, gate),
                                   Color.White, Color.FromArgb(30, 110, 60));
                    runButton.Enabled = true;""")

sub(
"""                gateSummaryLabel.ForeColor = Color.White;
                gateSummaryLabel.BackColor = Color.FromArgb(160, 30, 30);
                gateSummaryLabel.Text = "Cannot start:  " + ex.Message;
                runButton.Enabled = false;""",
"""                SetGateSummary("Cannot start:  " + ex.Message,
                               Color.White, Color.FromArgb(160, 30, 30));
                runButton.Enabled = false;""")

sub(
"""        private void UpdateThresholdStatusLabels(PanelDef panel)
        {
            if (panel == null) return;""",
"""        private static void SetStatus(Label label, string text, Color colour)
        {
            if (!string.Equals(label.Text, text, StringComparison.Ordinal)) label.Text = text;
            if (label.ForeColor != colour) label.ForeColor = colour;
        }

        private void UpdateThresholdStatusLabels(PanelDef panel)
        {
            if (panel == null) return;""")

sub(
"""                    status.ForeColor = Color.FromArgb(150, 60, 0);
                    status.Text =
                        "NO VARIABLE — the engine reads no threshold for this marker; it is " +
                        "adaptive by construction";
                    continue;""",
"""                    SetStatus(status,
                        "NO VARIABLE — the engine reads no threshold for this marker; it is " +
                        "adaptive by construction", Color.FromArgb(150, 60, 0));
                    continue;""")

sub(
"""                    status.ForeColor = Color.DarkRed;
                    status.Text = "ADAPTIVE (per-region Otsu)" +
                        (channel.AreaMarker ? " — on an AREA endpoint" : "");
                    continue;""",
"""                    SetStatus(status, "ADAPTIVE (per-region Otsu)" +
                        (channel.AreaMarker ? " — on an AREA endpoint" : ""), Color.DarkRed);
                    continue;""")

sub(
"""                    status.ForeColor = Color.DarkRed;
                    status.Text = "NOT A POSITIVE NUMBER";
                    continue;""",
"""                    SetStatus(status, "NOT A POSITIVE NUMBER", Color.DarkRed);
                    continue;""")

sub(
"""                status.ForeColor = Color.FromArgb(0, 110, 40);
                status.Text = "fixed_predeclared";""",
"""                SetStatus(status, "fixed_predeclared", Color.FromArgb(0, 110, 40));""")

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print("patched " + p)
