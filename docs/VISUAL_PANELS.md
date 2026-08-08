# Visual panels — a first-class module, not a rendering afterthought

> **Status: VALIDATED — v8 shipped and rendered.** The blocker (masks corrupted
> by the `blackBackground` bug) is resolved: v8 rendered **80 panels** and 80 QC
> overlays from `D:\IFQ_Runs\confocal_260808_fixed`, with every parameter it used
> recorded in `panel_qc.csv`. 80 rather than 82 because two `.oir` files are
> truncated at acquisition.
>
> Code: `panels/qc/RenderPanels.java` (v8 overlays, config
> `panels/qc/overlay_config_260808.csv`) and `panels/MergePanels.java`
> (fixed-window merge panels, config `panels/merge_config_260808.csv`).
> Outputs: `panels_v8/`, `qc_overlays/`, `merge_panels*/` under the run root.
>
> Two features described below are **PROPOSED, not implemented**: the endpoint
> overlay (§4) and the indeterminate-cell layer (§4). Both are marked in place.
> Last checked: 2026-08-08.

v1–v7 shipped and are superseded; the reason they were superseded matters more
than the versions.

## 1. Why this is its own module

A figure is a scientific claim. A panel that shows a cell the quantification did not
count, or omits one it did, contradicts the paper it illustrates — and nobody
notices, because the figure looks fine. So panel generation gets the same
treatment as measurement: declared rules, recorded parameters, and a way to be
wrong loudly.

It is nonetheless **not** a measurement path. No number here reaches
`run_summary.csv`. That separation is the point: the panels must be *derived from*
the measurement, never an independent re-derivation of it.

## 2. The operating rule, in priority order

Set by the operator, and binding:

1. **True-positive marked cells appear clearly.** Everything else yields to this.
2. **DAPI stays at high intensity** as the standard cell-localisation reference.
3. **Other channels sit a little weaker than DAPI.**
4. **The clearer the border between distinct marked cells, the better.**
5. **Comparability comes next** — wanted, but subordinate to 1–4.

## 3. What v1–v7 got wrong (one root cause)

Every version re-derived its own thresholds from raw pixel intensity and never
opened the masks the engine had already written.

That is a category error. "True-positive marked **cell**" is an object-level
property — it is the output of segmentation plus a per-marker decision. Intensity
windowing operates on pixels and cannot express it. A bright speck of debris and a
genuinely positive cell are the same pixel value.

The consequences were all predictable in hindsight:

| symptom | actual cause |
|---|---|
| borders never crisp across 7 revisions | chasing an object property with a pixel tool |
| "ProSPC airway lining shows and shouldn't" | intensity cannot separate populations that overlap in intensity |
| "some sections great, some blown out" | per-image windows re-derived instead of a fixed decision rule |
| figure could disagree with the count | two independent thresholding paths, never reconciled |

The engine exports, per field, per marker:

```
tissue__<MARKER>_morphology_positive_nuclei_mask.tif   cells CALLED POSITIVE
tissue__<MARKER>_indeterminate_nuclei_mask.tif         the three-state middle
tissue__nuclei_mask.tif                                all included nuclei
tissue__rejected_nuclei_mask.tif                       rejected candidates
<MARKER>_pod_mask.tif / <MARKER>_membrane_positive_mask.tif   area masks
```

Both panels have per-cell masks for all their markers, **including ProSPC and
KRT8**, which the area-mode registry does not cover. So the RIGHT panel is
renderable at cell level even though it has no area endpoints.

## 4. v8 — mask-driven, object-level, three layers

Composited in a fixed order so a border can never be washed out by an overlap.

**Layer 1 — scaffold.** DAPI, absolute window `250–2200` (the v3 values the operator
confirmed), blue, full weight. Absolute on purpose: a localisation reference that
moves per image is not a reference. Brightest layer, satisfying rule 2.

**Layer 2 — marker fill.** Fill the engine's mask for that marker at reduced weight
(~0.6 of DAPI, rule 3), modulated by the marker's real intensity *inside* the mask
so genuine variation stays visible. The mask decides *what*; intensity only shades
*within* what is already decided.

**Layer 3 — marker edge.** A 1–2 px outline of every positive object, drawn **last**
at full saturation. Borders become geometry rather than contrast, so rule 4 is
satisfied unconditionally — at any brightness, in any overlap.

Per-marker mode, **as shipped** in `panels/qc/overlay_config_260808.csv`:

| marker | mode | mask | edges | why |
|---|---|---|---|---|
| DAPI | `scaffold` | none — raw intensity, absolute window 250–2200 | 0 px | the localisation reference; a reference that moves per image is not a reference |
| KRT5 | `mask` | `KRT5_pod_mask.tif` | **2 px** | discrete pod objects, and the same component-filtered mask the endpoint script reads — so figure and endpoint cannot diverge |
| AGER | `mask` | `AGER_membrane_positive_mask.tif` | 0 px | continuous membrane mesh at 13–29% coverage; outlining it is noise, not clarity |
| T1A | `mask` | `T1A_membrane_positive_mask.tif` | 0 px | same |
| ProSPC | `mask` | `tissue__ProSPC_morphology_positive_nuclei_mask.tif` | **2 px** | discrete cells; the RIGHT panel has no area masks, which is why it renders at cell level |
| KRT8 | `mask` | `tissue__KRT8_morphology_positive_nuclei_mask.tif` | **2 px** | same |

Inside a mask, brightness never falls below `MIN_BRIGHT = 0.35`, so a
called-positive cell cannot render invisible. Gamma is 1.0 throughout — there is
no nonlinear transform to disclose.

A `raw` mode (windowed intensity, no mask) exists as a fallback for a marker with
no mask; nothing in the 260808 configuration uses it. Anything rendered that way
must be captioned **exploratory**, because it is the one mode not backed by a
measurement decision.

**No component gate.** v7 carried one, and it deleted the ProSPC airway lining
from the image. Under Rossner & Yamada 2004 (J Cell Biol 166:11) that is
selective manipulation of image content — categorically worse than a window or
gamma change — so it is retired. The airway is handled honestly instead: it stays
visible and is simply not outlined, because the engine did not call it positive.
The overlay asserts the *call*, not the pixels.

**Endpoint overlay — PROPOSED, not implemented.** Rendering the endpoint class
distinctly would make the figure show the reported quantity rather than its
ingredients. It is not built, and the algebra it would need has changed: the
current endpoint is **KRT5⁺PDPN⁺ over (PDPN⁻ OR KRT5⁺)**, not KRT5⁺PDPN⁻ — see
[`PROJECT_STATE.md`](PROJECT_STATE.md) §2. Building it against the old sign would
have put the wrong claim in a figure.

**Indeterminate cells — PROPOSED, not implemented.** They are the review burden
and worth being able to see, but they are not a claim, so they would ship default
**off**.

## 5. Comparability stops being a trade-off

This is the part worth noticing. The masks come from fixed, control-derived
thresholds applied identically to every image. So the *objects* shown are
comparable across mice by construction, while per-image adaptation is confined to
cosmetic shading **inside** objects whose membership was already decided globally.

Rule 5 is therefore satisfied without competing against rules 1–4 — comparability
moved down into the mask layer, where it belongs.

## 6. Self-evaluation of v8

Honest failure modes, not a feature list.

1. **Hard dependency on mask correctness — was a blocker, now cleared.** Until
   the missing `black` token at `IF_Quant_Pipeline.groovy:1783` was fixed,
   `Prefs.blackBackground` flipped to false before `segmentNuclei`, inverting
   `Fill Holes` and erasing every nucleus not touching the image frame. All
   per-cell masks in `D:\IFQ_Runs\confocal_260808\analysis` are **rim
   fragments** — 100% of candidate components border-touching in all 79 fields.
   v8 was held back from those masks and rendered only against
   `confocal_260808_fixed`. **The dependency itself does not go away**: a panel
   drawn from masks is exactly as right as the masks. That is the intended
   trade — it is what stops the figure and the table from disagreeing — but it
   means a segmentation regression becomes a figure regression silently.
2. **A mask-driven figure inherits calibration error.** If `IFQ_KRT5_THRESHOLD` is
   slightly wrong, the panel shows it. Honest, but it couples figure quality to
   calibration quality — and 300 currently rests on **one** sound control, since M6
   LEFT is a staining failure. The figure's authority is bounded by that.
3. **Fill weight for `area` markers needs care.** A 25%-coverage red fill will
   dominate a panel even at 0.6 weight.
4. **The ProSPC airway question is not automatically solved.** The per-cell call
   uses morphology plus `expectedCompartment: alveolar`, which *may* exclude the
   airway band — but that must be verified against the mask, not assumed. If it
   does not, the gate moves from pixel components to cells, using the engine's own
   compartment concept rather than a size number I invented.
5. ~~**The 8–800 µm² component gate was chosen by eye.**~~ **Retired in v8.**
   There is no component gate: the engine's own decision is the only gate, for
   the reason given in section 4.
6. **80/82 remains the ceiling.** Two `.oir` files are truncated at acquisition
   (7.3 / 8.2 MB against a uniform 37.7 MB) and fail in both Bio-Formats paths.
7. **DAPI saturation still limits the scaffold** (in-tissue p90 = 4095). The window
   recovers shape from below the clipped range; it cannot restore what was never
   digitised.

## 7. Provenance requirements

Non-negotiable, because a per-image adaptive figure is otherwise unauditable.
All three are **met** by the shipped v8:

- every panel captions its mask source, mode, and any intensity window used;
- **`panel_qc.csv`** (written beside the panels) records, per image and per
  marker: `output_key, panel, marker, mode, mask_file, mask_found, objects,
  mask_px, fill_weight, edge_px, fill_low, fill_high`. If a mask is missing,
  `mask_found=false` is in the record rather than a quietly blank layer;
- a panel rendered in `raw` mode is captioned **exploratory**, because it is the
  one mode not backed by a measurement decision. Nothing in the 260808
  configuration uses `raw`.
