"""Derive a KRT8-high operating point from the UNINFECTED CONTROLS ONLY.

KRT8 is constitutively expressed in alveolar epithelium, so "the control should
be negative" -- the rule that locked IFQ_KRT5_THRESHOLD -- is unavailable. What
IS available: the controls define what ORDINARY alveolar KRT8 looks like, and
the transitional/DATP state is a genuine upper tail. So the cut is an upper
quantile of the pooled control per-cell distribution, and the question is
whether infected mice are ENRICHED above it.

Worst-of-both-controls, as used elsewhere in this project: take the higher of
the two control quantiles so neither animal alone sets a permissive cut.

The decisive number is the ENRICHMENT RATIO
    R = (infected fraction above cut) / (control fraction above cut)
By construction the control fraction is ~ (1 - q). R ~ 1 means KRT8 carries no
signal in this panel and no threshold will rescue it.
"""
import csv, glob, os, statistics

ROOT = r"D:\IFQ_Runs\confocal_260808_fixed\analysis"
SUMMARY = os.path.join(ROOT, "run_summary.csv")

cond = {}
with open(SUMMARY, newline="", encoding="utf-8-sig") as fh:
    for r in csv.DictReader(fh):
        cond[r["output_key"]] = (r["mouse_id"], r["condition"], r["panel"])

vals = {}   # mouse -> list of per-cell KRT8_mean
for f in glob.glob(os.path.join(ROOT, "*", "*__cells.csv")):
    key = os.path.basename(os.path.dirname(f))
    if key not in cond:
        continue
    mouse, c, panel = cond[key]
    if panel != "RIGHT":
        continue
    with open(f, newline="", encoding="utf-8-sig") as fh:
        for row in csv.DictReader(fh):
            v = row.get("KRT8_mean", "")
            if v not in ("", None):
                try:
                    vals.setdefault((mouse, c), []).append(float(v))
                except ValueError:
                    pass

def q(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(p * len(xs)))]

print("per-cell KRT8_mean, RIGHT panel")
print(f"{'mouse':6} {'cond':11} {'n':>7} {'p50':>8} {'p90':>8} {'p95':>8} {'p99':>8} {'p99.9':>9}")
for k in sorted(vals, key=lambda k: (k[1], k[0])):
    v = vals[k]
    print(f"{k[0]:6} {k[1]:11} {len(v):7d} {q(v,.50):8.1f} {q(v,.90):8.1f} "
          f"{q(v,.95):8.1f} {q(v,.99):8.1f} {q(v,.999):9.1f}")

ctrl = {k: v for k, v in vals.items() if k[1] == "uninfected"}
inf  = {k: v for k, v in vals.items() if k[1] != "uninfected"}

print("\ncut derived from CONTROLS ONLY (worst-of-both), applied to held-out infected")
print(f"{'quantile':>9} {'cut':>8} | " +
      " ".join(f"{m:>9}" for m, _ in sorted(inf)) +
      " | " + " ".join(f"{m:>9}" for m, _ in sorted(ctrl)) + f" | {'R':>6}")

for p in (0.90, 0.95, 0.99, 0.995, 0.999):
    cut = max(q(v, p) for v in ctrl.values())          # worst-of-both
    fi = {m: sum(1 for x in v if x > cut) / len(v) for (m, c), v in sorted(inf.items())}
    fc = {m: sum(1 for x in v if x > cut) / len(v) for (m, c), v in sorted(ctrl.items())}
    mean_i = statistics.mean(fi.values())
    mean_c = statistics.mean(fc.values())
    R = mean_i / mean_c if mean_c > 0 else float("inf")
    print(f"{p:9.3f} {cut:8.1f} | " +
          " ".join(f"{v:9.4f}" for v in fi.values()) + " | " +
          " ".join(f"{v:9.4f}" for v in fc.values()) + f" | {R:6.2f}")

print("\nR = mean(infected fraction) / mean(control fraction) at the same cut.")
print("R near 1 => no enrichment => KRT8 carries no separating signal in this panel.")
