// EnvProbe.exe -- prints the IFQ_* environment it actually received, sorted.
// Used by LegacyEquivalence.cs section [d]: two children are started with the
// v1.7.2 environment and with route 4's, and their output is diffed.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Globalization;

internal static class EnvProbe
{
    private static int Main(string[] args)
    {
        Console.WriteLine("ENVPROBE");
        Console.WriteLine("ARGC=" + args.Length.ToString(CultureInfo.InvariantCulture));
        for (int i = 0; i < args.Length; i++)
            Console.WriteLine("ARG" + i.ToString(CultureInfo.InvariantCulture) + "=" + args[i]);

        List<string> lines = new List<string>();
        foreach (DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            string key = Convert.ToString(entry.Key, CultureInfo.InvariantCulture);
            if (key == null) continue;
            if (!key.StartsWith("IFQ_", StringComparison.OrdinalIgnoreCase)) continue;
            lines.Add(key + "=" + Convert.ToString(entry.Value, CultureInfo.InvariantCulture));
        }
        lines.Sort(StringComparer.Ordinal);
        foreach (string line in lines) Console.WriteLine(line);
        Console.WriteLine("END");
        return 0;
    }
}
