// EnvProbe.exe -- stands in for Fiji.
//
// It does exactly one thing: print every IFQ_* variable it was actually
// handed, sorted, one KEY=VALUE per line. That turns "the launcher builds the
// right dictionary" into "the child process receives the right environment",
// which is the claim that matters and the only one a diff can settle.

using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;

internal static class EnvProbe
{
    private static int Main(string[] args)
    {
        List<string> lines = new List<string>();
        foreach (DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            string key = Convert.ToString(entry.Key, CultureInfo.InvariantCulture);
            if (key == null) continue;
            if (!key.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase)) continue;
            lines.Add(key + "=" + Convert.ToString(entry.Value, CultureInfo.InvariantCulture));
        }
        lines.Sort(StringComparer.Ordinal);
        Console.Out.Write("ARGV=" + string.Join(" ", args ?? new string[0]) + "\n");
        foreach (string line in lines) Console.Out.Write(line + "\n");
        return 0;
    }
}
