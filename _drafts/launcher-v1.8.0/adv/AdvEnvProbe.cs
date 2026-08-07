using System;
using System.Collections;
using System.Collections.Generic;

internal static class AdvEnvProbe
{
    static int Main()
    {
        List<string> lines = new List<string>();
        foreach (DictionaryEntry e in Environment.GetEnvironmentVariables())
        {
            string k = Convert.ToString(e.Key);
            if (k != null && k.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase))
                lines.Add(k + "=" + Convert.ToString(e.Value));
        }
        lines.Sort(StringComparer.Ordinal);
        foreach (string l in lines) Console.WriteLine(l);
        return 0;
    }
}
