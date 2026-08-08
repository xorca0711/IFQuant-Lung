# How the analysis works

> **Status: CURRENT.** Written for the person who runs the microscope, reviews
> the output, or needs to understand what the numbers mean — not for the person
> editing the code. No command line required to read this.
> For the technical entry point see [`README.md`](README.md);
> for what is and is not established see its status tables.
> Last checked: 2026-08-08.

**The question this answers:** after influenza injury, does knocking out IFN-γ
change how much of the lung is repaired by dysplastic KRT5⁺ cells instead of by
normal alveolar cells?

**What the software does:** turns microscope images into a number per mouse,
and keeps enough evidence that anyone can check where the number came from.

---

## 1. The journey of one image

```
   ┌──────────────┐
   │  MICROSCOPE  │  confocal field, or whole-slide scan
   └──────┬───────┘
          │  a 4-colour image: DAPI + three markers
          ▼
   ┌──────────────┐
   │  1. WHERE IS │  Find the tissue. Ignore empty glass.
   │    TISSUE?   │  → everything after this is measured inside tissue only
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  2. WHERE    │  Find every cell nucleus from the DAPI channel.
   │   ARE CELLS? │  → ~15,000 nuclei per mm² in mouse lung
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  3. WHICH    │  For each cell, and each marker: positive, negative,
   │  CELLS ARE   │  or "cannot tell". Three answers, not two.
   │   POSITIVE?  │  → uses SHAPE first, brightness second (see §3)
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  4. HOW MUCH │  Also measure marker AREA, independent of cells.
   │     AREA?    │  → this is what the published endpoint uses
   └──────┬───────┘
          ▼
   ┌──────────────┐
   │  5. ROLL UP  │  fields → section → slide → ONE ROW PER MOUSE
   │              │  → because the statistical unit is the animal (§6)
   └──────────────┘
```

Every step writes its own evidence to disk: the tissue outline, the nucleus
mask, a per-cell table, a marker mask, and a QC picture. **If a number looks
wrong you can open the picture that produced it.**

---

## 2. Why two programs instead of one

```
        .vsi whole slide                       .oir / .czi / .nd2
        19.3 GB, 57165 × 42154 px              confocal field, ~38 MB
                │                                       │
                ▼                                       │
        ┌───────────────┐                                │
        │    QuPath     │  opens the slide               │
        │  reads, cuts  │  cuts it into 2048 px tiles    │
        │  MEASURES     │  writes them to disk           │
        │    NOTHING    │                                │
        └───────┬───────┘                                │
                │  tiles on disk                         │
                └──────────────┬─────────────────────────┘
                               ▼
                     ┌───────────────────┐
                     │       Fiji        │   ← THE ONLY THING
                     │  IF_Quant_        │     THAT MEASURES
                     │  Pipeline.groovy  │
                     └─────────┬─────────┘
                               ▼
                     ┌───────────────────┐
                     │      Python       │  adds up. Decides nothing.
                     └─────────┬─────────┘
                               ▼
                        one row per mouse
```

**Why not do it all in QuPath?** Because then there would be two programs that
measure, and the answer would depend on which one you used. Whole slides and
confocal fields go through the *same* engine, so a KRT5⁺ area means the same
thing either way.

**Why can't Fiji open the slide itself?** The Olympus `.vsi` format uses a
compression codec QuPath bundles and Fiji does not. QuPath is also the only one
that can cut up an image far larger than the computer's memory.

**Why files on disk between them?** The two programs run on incompatible
versions of Java and cannot call each other directly. Handing over files is the
standard solution (Chiaruttini et al. 2022, *Front Comput Sci* 3:780026).

---

## 3. The idea that makes the calls trustworthy

A naive approach asks *"is this cell brighter than X?"* That fails constantly:
a speck of dust is bright, a folded piece of tissue is bright, and a real cell
in a dim section is not.

This pipeline asks a different question first — **does the signal have the right
shape, in the right place?**

```
   marker signal on a cell
            │
            ├─ Is it in the right PLACE for this marker?
            │    nuclear marker → in the nucleus
            │    membrane marker → a thin rim around the cell
            │    cytoplasmic marker → a ring outside the nucleus
            │
            ├─ Is it CONNECTED, or scattered specks?
            │    → a real stain is continuous; noise is not
            │
            ├─ Is it in the right TISSUE COMPARTMENT?
            │    → an alveolar marker should not be in an airway
            │
            └─ Only then: is it bright enough?

   Result: POSITIVE  /  NEGATIVE  /  CANNOT TELL
```

**"Cannot tell" is a real answer.** Cells that are ambiguous are counted as
ambiguous, not silently pushed into whichever side is more convenient. If a
large fraction of a run comes back ambiguous, that is telling you the staining
or the threshold needs attention — and the pipeline reports that fraction rather
than hiding it.

---

## 4. Where the numbers come from, and what could go wrong

| The number | Comes from | The honest caveat |
|---|---|---|
| **KRT5⁺ area %** | pixels above a fixed cutoff, inside tissue | The cutoff was set from uninfected control mice — see §5 |
| **KRT5 pod count** | connected blobs of KRT5⁺ area | Two touching pods count as one |
| **cells positive for X** | shape gates above, per cell | Needs correct nucleus detection; this had a serious bug, now fixed |
| **T1α / AGER area %** | same as KRT5, but the cutoff adapts per image | Marked `exploratory` — these markers are present in every animal, so there is no "should be zero" control to calibrate against |
| **per-mouse values** | all that mouse's fields added together | Fields from one mouse are not independent samples |

---

## 5. How a cutoff is chosen (and why it is not chosen from the results)

This is the single most important protection against fooling yourself.

```
   WRONG                             RIGHT
   ─────                             ─────
   Look at infected + control.       Look at CONTROL ANIMALS ONLY.
   Pick the cutoff that              Pick the cutoff where controls
   separates them nicely.            are essentially all negative.
            │                                   │
            ▼                                   ▼
   The cutoff now depends on         FREEZE IT. Write it down.
   the answer you wanted.                       │
                                                ▼
                                     Only now measure the infected
                                     animals — with a cutoff that
                                     could not have been tuned to them.
```

Worked example, KRT5: in the two uninfected mice, 99.99 % of tissue pixels sit
below 283 and 255. The cutoff is set to **300** and frozen. Infected tissue then
has 8.1 % of its area above 500 — a result the cutoff had no opportunity to
manufacture.

**Where this could not be done:** AGER and T1α are present in healthy lung too,
so "controls should be negative" gives nothing to anchor to. Those cutoffs adapt
per image and every number derived from them is labelled `exploratory`. That
label is not decoration — it means *do not put this in a figure as a result*.

---

## 6. The statistical unit is the mouse

```
   ✗ WRONG                          ✓ RIGHT
   1 mouse                          1 mouse
     └─ 10 fields                     └─ 10 fields ──┐
          └─ 2,000 cells                             ├── averaged
                                                     ▼
   "n = 20,000 cells!"              ONE number for that mouse
                                    n = number of MICE
```

Two thousand cells from one animal tell you about **one animal**. Treating them
as independent samples inflates confidence enormously and is one of the most
common errors in image-based biology. `aggregate_to_mouse.py` exists to enforce
this, and prints the animal count so it cannot be mistaken.

**For the current dataset that means:** 4 mice, one per genotype × condition
combination. **No statistics are possible.** The measurements describe four
animals. They do not compare groups, and no amount of cells or fields changes
that.

---

## 7. What "checked" means here

Three different things, deliberately not called by the same name:

| Word used | What it means |
|---|---|
| **VALIDATED** | Something was run, and the number it produced is written down where you can see it |
| **PROPOSED** | Designed and maybe implemented, but never confirmed against data |
| **EXPLORATORY** | Measured, but with a cutoff that adapted to the image — a hypothesis, not a result |

A worked example of the difference, because it is the project's most useful
story: a **single missing word** in one line of image-processing code silently
deleted almost every nucleus that did not touch the edge of the image. Measured
cell density read 152 per mm² when the truth was about 15,400 — a **101-fold
undercount** — and nothing crashed, nothing warned, and the output looked
entirely normal.

It was found by re-running the broken sequence deliberately and reproducing the
corrupted output *exactly* (a pixel-for-pixel match), which is what proves the
diagnosis rather than merely suggesting it. Then the fix was verified not to
disturb the area measurements: worst change, 0.02 of a percentage point.

The lesson is in [`docs/NEGATIVE_RESULTS.md`](docs/NEGATIVE_RESULTS.md): results
that turned out to be wrong are kept and labelled, because a project that only
records its successes cannot be audited.

---

## 8. Running it

**If you are at the microscope:** download the launcher from
[Releases](https://github.com/xorca0711/IFQuant-Lung/releases), pick the kind of
image you have, point it at a folder, press Run. It refuses to start rather than
guess when something is ambiguous.

**The four routes it offers:**

| Route | For | Uses |
|---|---|---|
| 1 | confocal / field images | Fiji |
| 2 | whole-slide `.vsi` | QuPath → Fiji → Python |
| 3 | H&E / brightfield | *deliberately disabled* |
| 4 | reproduce an old v1.7.2 run | Fiji |

Route 3 is visible but greyed out on purpose. Hiding it would invite someone to
send an H&E slide through route 1, which **would not fail** — it would produce a
confident, wrong answer, because the engine assumes bright signal on a dark
background and H&E is the opposite.

**Before a real run, five things to confirm:**

1. The channel order matches the panel you selected
2. Thresholds are frozen, not adaptive, if this is a confirmatory run
3. Nucleus detection looks sane in the QC image — the count should be thousands per mm², not tens
4. The ambiguous ("cannot tell") fraction is not enormous
5. You are comparing mice, not fields or cells

Full command-line detail lives in [`README.md`](README.md).

---

## 9. Honest limitations

- **The corrected endpoint has never been computed.** The definition was found
  to be inverted against the source paper and has been fixed *as a
  specification*; the software that evaluates it cannot yet compute the required
  denominator, and refuses rather than producing a plausible wrong number.
- **One control animal has a staining failure**, so the KRT5 cutoff effectively
  rests on a single clean control.
- **The KRT8 marker was tested and rejected** — it did not separate infected
  from uninfected at any cutoff.
- **Nothing here is reproducible from a code checkout alone**, because the image
  data cannot be published. The one exception is the launcher equivalence check,
  which runs from a clone with no data at all.
- **n = 1 per group.** Stated again because it is the limitation that matters
  most and the one most easily forgotten.

---

## 10. Reference

Endpoint definition: Lin X. et al., *J Clin Invest* 2024;134(19):e176828
([10.1172/JCI176828](https://doi.org/10.1172/JCI176828)) — dysplastic KRT5⁺PDPN⁺
area over damaged alveolar area, measured there by hand-tracing on whole-lobe
mosaics with n = 15 mice per group.

Marker biology and the reasoning behind each gate:
[`docs/MARKER_MORPHOLOGY_GUIDE.md`](docs/MARKER_MORPHOLOGY_GUIDE.md).
Exported columns and the full QC acceptance order:
[`docs/MARKER_MORPHOLOGY_GUIDE.md`](docs/MARKER_MORPHOLOGY_GUIDE.md) and
[`README.md`](README.md#outputs).
