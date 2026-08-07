# IFQuant-Lung Module Contract v1.0.0 (DRAFT)

**Status:** draft, nothing applied to the repo.
**Frozen upstream:** `IF_Quant_Pipeline.groovy`.
**Single aggregation path:** `aggregate_to_mouse.py` — must not fork, must not change.

Every line below that describes existing behaviour cites `file:line` in
`C:\Users\dream\Documents\GitHub\IFQuant-Lung` on branch `claude/qupath-wsi-stage1-tiling`.

---

## 0. The one-paragraph version

`aggregate_to_mouse.py` does **not** carry columns forward generically. It builds an
explicit whitelist of column-name *suffixes* (`classify_columns`, lines 114-201), sums
only those, recomputes a fixed set of ratios from the pooled sums, and **silently drops
everything else**. A new module therefore cannot "aggregate wrong" by accident — it
**disappears**. The contract is: *emit numerators and denominators using the recognised
suffix vocabulary; never emit a ratio you care about; select your fraction denominator by
choosing what you put in `region_area_um2`, and declare that choice in the `panel` column.*

---

## 1. What `aggregate_to_mouse.py` actually requires

### 1.1 Required columns

`aggregate_to_mouse.py:43-44`

```python
KEY_COLS   = ["mouse_id", "genotype", "condition", "panel"]
ROW_ID_COLS = ["image", "region", "section_id"]
```

`validate_rows` (line 71) hard-exits if any of those seven are absent from the header:

```python
missing_columns = [c for c in KEY_COLS + ROW_ID_COLS if c not in header]
if missing_columns:
    sys.exit("ERROR: run_summary.csv is missing required columns: " + ...)
```

### 1.2 Grouping key (what "a mouse" means)

`aggregate_to_mouse.py:212`

```python
key = tuple(r.get(k, "NA") for k in KEY_COLS)   # (mouse_id, genotype, condition, panel)
```

**`region` and `section_id` are NOT in the grouping key.** Every region and every section
of one animal is pooled into a single mouse row. This is the single most consequential
fact for module design: *you cannot separate two denominators by writing them into
different `region` values.* They will be summed together.

Group statistics key (`group_stats`, line 375): `(genotype, condition, panel)`;
`n_mice` = number of mouse rows in that cell (line 383, via `_stats`).

### 1.3 Identity guards (three separate hard exits)

| Guard | Line | Condition |
|---|---|---|
| unusable `mouse_id` | 76-85 | any row whose `mouse_id` is `""`, `NA`, `N/A`, `UNKNOWN` |
| identity conflict | 87-95 | one `mouse_id` mapping to >1 `(genotype, condition)` pair |
| duplicate row | 97-111 | see below |

### 1.4 Duplicate-row check — exact quote (`aggregate_to_mouse.py:97-111`)

```python
identity_column = "output_key" if "output_key" in header else "image"
seen = set()
duplicates = []
for r in rows:
    key_columns = [identity_column, "region", "section_id", "panel"]
    key = tuple((r.get(c) or "").strip() for c in key_columns)
    if key in seen:
        duplicates.append(key)
    seen.add(key)
if duplicates:
    sys.exit(f"ERROR: {len(duplicates)} duplicate output/image-region-section-panel row(s) detected. ...")
```

Note carefully:
* the row-uniqueness key is **`(output_key|image, region, section_id, panel)`**;
* **`mouse_id` is not in it.** Two different mice whose rows collide on those four values
  will be rejected as duplicates. Module row identity must be globally unique, not
  unique-within-mouse;
* `output_key` wins when present. The engine emits it (`IF_Quant_Pipeline.groovy:2594`),
  so **every new module must emit `output_key` too** or it will be keyed on `image` and
  can collide with engine rows in a merged CSV.

### 1.5 SUM vs RECOMPUTE vs DROP — the decisive rule

There is **no** "is this a fraction?" heuristic in the code. `classify_columns`
(lines 114-201) builds `sum_cols` as a *closed set* and `aggregate_mice` writes
`rec[...]` only from that set. Anything not in it is never read.

**Summed** — `aggregate_to_mouse.py:184-186`:

```python
sum_cols = (set(["region_area_um2", "n_nuclei"]) | set(pos_count) |
            set(pod_area) | set(n_pods) | set(class_count) | state_counts |
            set(nucleus_qc_count) | set(positive_area) | set(n_components))
```

**Recomputed** (pooled numerator / pooled denominator, never an average of row-level
ratios) — the complete list, with the exact denominator each one uses:

| Output column | Formula | Line |
|---|---|---|
| `{m}_density_per_mm2` | `sum({m}_pos_count) / (sum(region_area_um2)/1e6)` | 260 |
| `{m}_raw_mean_density_per_mm2` | `sum({m}_raw_mean_pos_count) / (sum(region_area_um2)/1e6)` | 266 |
| `{m}_morphology_positive_fraction_of_evaluable` | `/ sum({m}_morphology_evaluable_count)` | 285 |
| `{m}_morphology_negative_fraction_of_evaluable` | same denominator | 286 |
| `{m}_indeterminate_fraction_of_included` | `/ sum(n_nuclei)` | 287 |
| `{m}_intensity_morphology_discordant_fraction_of_evaluable` | `/ sum({m}_morphology_evaluable_count)` | 288 |
| `{m}_review_burden_proxy_fraction_of_included` | `/ sum(n_nuclei)` | 289 |
| `{m}_final_{positive,negative,indeterminate}_fraction_of_total_cells` | `/ sum(n_nuclei)` | 302 |
| `{m}_context_resolved_positive_fraction_of_total_cells` | `/ sum(n_nuclei)` | 307 |
| `{m}_context_resolved_positive_fraction` | `/ sum({m}_context_resolved_evaluable_count)` | 310 |
| `{m}_positive_area_fraction` | `sum({m}_positive_area_um2) / sum(region_area_um2)` | 320 |
| `{m}_mean_component_area_um2` | `/ sum({m}_n_components)` | 322 |
| `{m}_pod_area_frac` | `sum({m}_pod_area_um2) / sum(region_area_um2)` | 330 |
| `{m}_mean_pod_area_um2` | `/ sum({m}_n_pods)` | 332 |
| `class_{X}_density_per_mm2` | `/ (sum(region_area_um2)/1e6)` | 338 |
| `nucleus_candidate_acceptance_fraction` etc. | `/ sum(n_nucleus_candidates_total)` | 243-254 |

**Dropped** — everything else, with no warning. Verified empirically
(`scratchpad/probe/probe.csv`): a header containing `mean_linear_intercept_um`,
`alveolar_area_um2` and `my_custom_ratio` produced a `mouse_level_summary.csv` in which
none of those three columns exist. The same run correctly produced
`damage_positive_area_um2_total = 1000000.0` and `damage_positive_area_fraction = 0.25`
from `damage_positive_area_um2`, because that name matches `*_positive_area_um2`.

> **The rule, stated once:** the aggregator decides by **suffix match on the column name**,
> not by value, units, or magnitude. There are exactly two ways a module metric can
> survive to mouse level: (a) its name ends in a recognised **count/area suffix** and it is
> summed, or (b) it is one of the ~16 ratios the aggregator itself recomputes from those
> sums. A ratio a module computes and writes itself is **always** discarded.

### 1.6 The five suffix traps (read before naming anything)

1. `*_pod_area_um2` is summed **except** `*_mean_pod_area_um2` (line 169). Never name a
   mean anything `*_pod_area_um2`.
2. `*_pos_count` is summed except the four explicitly excluded audit variants
   (lines 120-126). `*_true_pos_count` is excluded from `pos_count` **and never added to
   any other list** — it is silently dropped. Do not use it.
3. `*_indeterminate_count` is a marker state column **unless** the name starts with
   `class_` (line 131-132), in which case it is a classification column. Prefix collisions
   are real.
4. A bare `*_area_um2` (e.g. `alveolar_area_um2`, `damaged_area_um2`) is **dropped**.
   Only `*_positive_area_um2` and `*_pod_area_um2` survive.
5. `region_area_um2` and `n_nuclei` are matched by **exact name** (line 184). No suffix
   variant works.

---

## 2. The contract

### 2.1 Row-identity columns (mandatory, every module, every row)

| Column | Type | Required | Semantics | Consumed at |
|---|---|---|---|---|
| `mouse_id` | string, non-empty, not `NA`/`N/A`/`UNKNOWN` | yes | biological replicate. **This is n.** | `agg:43,76` (grouping + guard) |
| `genotype` | string | yes | must be constant per `mouse_id` | `agg:43,87` |
| `condition` | string | yes | must be constant per `mouse_id` | `agg:43,87` |
| `panel` | string `"<PANEL_KEY>@<denominator_scope>"` | yes | grouping key **and** the declared denominator scope (§2.3) | `agg:43,375` |
| `image` | string | yes | source image / slide / tile stem | `agg:44,97` |
| `output_key` | string, globally unique | **yes for new modules** | preferred row identity; prevents collision with engine rows in a merged CSV | `agg:97` |
| `region` | string | yes | sub-region label. **Does not separate groups** — informational + row uniqueness only | `agg:44,101` |
| `section_id` | string | yes | physical section / tile id; counted into `n_sections` (`agg:221`) | `agg:44,101` |
| `module_id` | string, e.g. `morphometry.mli` | recommended | dropped by the aggregator; used by the merger and provenance sidecar | — |

`(output_key, region, section_id, panel)` must be unique across the **whole merged CSV**.

### 2.2 Measurement column naming convention (the part that must not be got wrong)

| You are measuring | Emit exactly | Aggregation | Mouse-level output you get free |
|---|---|---|---|
| the denominator of your fractions | `region_area_um2` | SUM | `total_tissue_area_um2` |
| nuclei in that denominator | `n_nuclei` | SUM | `total_nuclei` |
| a structure's **area** | `<Name>_positive_area_um2` (+ `<Name>_n_components`) | SUM | `<Name>_positive_area_um2_total`, `<Name>_positive_area_fraction`, `<Name>_mean_component_area_um2` |
| a **pod-like** structure (numerator with its own object count) | `<Name>_pod_area_um2` + `<Name>_n_pods` | SUM | `<Name>_pod_area_um2_total`, `<Name>_pod_area_frac`, `<Name>_n_pods_total`, `<Name>_mean_pod_area_um2` |
| **objects** whose density per mm² you want | `<Name>_pos_count` | SUM | `<Name>_pos_count_total`, `<Name>_density_per_mm2` |
| a **co-expression / neighbourhood class** | `class_<Label>_count` (+ `class_<Label>_evaluable_count`) | SUM | `class_<Label>_count_total`, `class_<Label>_density_per_mm2` |
| three-state marker calls | `<M>_final_{positive,negative,indeterminate}_cell_count` | SUM | `..._total` + `..._fraction_of_total_cells` |
| anything else numeric | **do not** — it is dropped | DROP | nothing |
| any ratio, mean, index, threshold | **do not** — dropped, and if it accidentally matches a suffix it is summed, which is wrong | DROP | nothing |

**Naming rules for `<Name>`:**
* ASCII `[A-Za-z0-9_-]`, no `.`; no `,` (CSV); must not itself end in a reserved suffix.
* Must be unique across all modules. Reserve namespaces by prefix:
  `morph_*` (morphometry), `spat_*` (spatial), `reg_*` (registration), marker symbols
  (engine, from `config/lung_marker_registry.json`).
* A `<Name>` that ends in `_mean`, `_median`, `_index`, `_ratio`, `_frac`, `_fraction`,
  `_per_mm2`, `_um` is **forbidden** — those are derived quantities and must not be
  emitted at all.

**Worked example — mean linear intercept (a classic morphometry index).**
MLI = 2 × (airspace area) / (septal boundary length). It is a *ratio*, so it must never be
a column. Emit the two extensive quantities instead:

```
morph_airspace_positive_area_um2   (SUM)   -> morph_airspace_positive_area_um2_total
morph_septalintercept_pos_count    (SUM)   -> morph_septalintercept_pos_count_total
```

and declare `mli_um = 2 * morph_airspace_positive_area_um2_total / morph_septalintercept_pos_count_total`
in `config/endpoints/*.json` (§2.4). Pooled-then-divided is also the statistically
correct MLI for a mouse; averaging per-tile MLI is not.

### 2.3 Choosing your denominator: the `panel@scope` rule

Because `region` does not separate groups (§1.2) and every fraction the aggregator
recomputes divides by `sum(region_area_um2)` or `sum(n_nuclei)`, a module's denominator
**is** whatever it writes into `region_area_um2`. Since `panel` is the only free
grouping key, the denominator scope must be encoded there:

```
panel = "<PANEL_KEY>@<denominator_scope>"
```

`denominator_scope` ∈ `{whole_tissue, parenchyma, damaged, intact, airway, vessel,
tumor, stroma, ali_membrane}` (extensible via `config/endpoints/_schema.json`).

*Invariant:* **within one `(mouse_id, genotype, condition, panel)` group, every row's
`region_area_um2` must measure the same anatomical scope, and rows must be
non-overlapping.** Overlap double-counts; mixed scope makes the fraction meaningless.

Verified (`scratchpad/probe/probe2.csv`, one mouse, two scopes, one CSV):

| `panel` | `region_area_um2` | `KRT5_pod_area_frac` | `damage_positive_area_fraction` |
|---|---|---|---|
| `LEFT@dmg` | 1 000 000 (damaged only) | **0.02** ← primary endpoint, pod / damaged | 0.0 (column absent) |
| `LEFT@paren` | 10 000 000 (whole parenchyma) | 0.002 (not the endpoint) | **0.1** ← % lung damaged |

Both survive to mouse level from one file, `aggregate_to_mouse.py` unchanged, and
`group_stats` keeps them apart because `panel` is in its key.

*Accepted cost:* a column absent from one scope's rows aggregates to `0.0` rather than
blank (`_num("") -> None`, filtered, `sum([]) -> 0.0`, line 228). `group_level_summary.csv`
will therefore contain some all-zero metric rows. The endpoint registry (§2.4) is what
tells a human which `(panel_scope, metric)` pairs are real.

### 2.4 Endpoint registry — where ratios are allowed to live

A ratio is never a column; it is a *declaration* evaluated against
`mouse_level_summary.csv` after aggregation.

```json
{
  "endpoint_id": "ectopic_pod_over_damaged",
  "label": "KRT5+ ectopic pod area / damaged alveolar area",
  "reference": "Lin et al. J Clin Invest 2024;134(19):e176828",
  "panel_scope": "LEFT@damaged",
  "numerator": "KRT5_pod_area_um2_total",
  "denominator": "total_tissue_area_um2",
  "already_computed_as": "KRT5_pod_area_frac",
  "units": "dimensionless area fraction",
  "n_definition": "mouse_id"
}
```

`already_computed_as` is the audit hook: if the aggregator already recomputes the ratio,
the registry must name it and a check must confirm
`numerator/denominator == already_computed_as` to within 1e-9.

---

## 3. Level reconciliation: tile / region / slide / mouse

### 3.1 Where rows live

| Level | Producer | Row granularity | File |
|---|---|---|---|
| region (field route) | `IF_Quant_Pipeline.groovy:2594` | one row per (image, region) | `<out>/run_summary.csv` |
| tile (WSI route) | same engine on Stage-1 tiles | one row per (tile, region); **two** when Stage 1 partitions damaged/intact | `<slide>/analysis*/run_summary.csv` |
| slide | `aggregate_tiles_to_slide.py:213` | one row per slide | `<root>/stats/slide_level_summary.csv` |
| **mouse** | `aggregate_to_mouse.py:208` | one row per (mouse, genotype, condition, panel) | `mouse_level_summary.csv` |
| group | `aggregate_to_mouse.py:360` | one row per (genotype, condition, panel, metric) | `group_level_summary.csv` |

`aggregate_to_mouse.py` is **level-agnostic**: it consumes region rows, tile rows or slide
rows identically. That is why it must not fork.

### 3.2 A slide-level module vs the tile-level engine

Morphometry on a whole tissue mask produces **one row per slide**; the engine produces
**hundreds of rows per slide**. They meet at the mouse row, and they must not double-count
`region_area_um2`, because that is the shared denominator.

Rule: **exactly one producer per `(mouse, panel@scope)` owns `region_area_um2`.**

Three ways to satisfy it, in order of preference:

**(a) Different scope (preferred, zero coupling).** The slide module emits
`panel = "LEFT@parenchyma"`, the engine chain emits `panel = "LEFT@damaged"`. Different
KEY groups, no interaction at all. Each owns its own denominator. Use this whenever the
module genuinely measures a different territory.

**(b) Same scope, module contributes numerators only.** The slide module joins the
engine's group (same `panel@scope`) and emits its `*_positive_area_um2` /
`*_pos_count` columns with **`region_area_um2` left blank**. Blank parses to `None`
(line 51), is filtered (line 227), and contributes 0 to the sum — so the engine keeps sole
ownership of the denominator and the module's fraction is
`slide-level numerator / tile-summed denominator`. This is only valid when the module's
measured territory is the *same territory* the tiles cover. Enforce with the coverage
check that Stage 3 already performs (`aggregate_tiles_to_slide.py:328-357`,
`tissue_area_rel_diff > 0.01` → `qc_status = PROBLEM`).

**(c) Never:** both emit `region_area_um2` for the same `(mouse, panel@scope)`. The
denominator doubles and every fraction halves, silently.

The reconciliation number itself — slide-mask area vs Σ tile core area — must be recorded
in the provenance block (`area_reconciliation`, §4) and, when it exceeds 1%, the module
must refuse to write, matching the precedent at `aggregate_tiles_to_slide.py:470-477`
("REFUSING to write slide_level_summary.csv").

### 3.3 Known gap this contract closes

`aggregate_tiles_to_slide.py:291-303` computes `damaged_area_um2`, `intact_area_um2`,
`damaged_fraction_of_parenchyma`, `<M>_pod_area_um2_in_intact` and
`<M>_pod_area_frac_of_intact`. **None of those names match a `sum_cols` pattern**, so all
five are dropped at mouse level — "% of lung damaged" never becomes a mouse-level
endpoint, and the intact-parenchyma KRT5 QC readout never reaches the group table.
Renaming under §2.2 (`damage_positive_area_um2` in a `@parenchyma`-scoped row) recovers
both without touching `aggregate_to_mouse.py`.

---

## 4. Provenance block

One JSON sidecar per module output CSV, named `<csv_stem>.provenance.json`, plus one
`module_run_manifest.json` per run directory. Schema in
`config/provenance.schema.json` (drafted alongside this file). It deliberately mirrors the
existing `run_manifest.json` shape (`run_timestamp`, `versions{}`, `config{}`, `images[]`)
so the two can be read by one parser.

Design intent: **a methods paragraph must be writable from the sidecars alone** — no
access to the code, the machine, or the operator's memory.

```json
{
  "provenance_schema_version": "1.0.0",
  "module": {
    "module_id": "morphometry.alveolar_architecture",
    "module_version": "0.1.0",
    "level": "slide",
    "panel_scope": "LEFT@parenchyma",
    "emits": ["morph_airspace_positive_area_um2", "morph_septalintercept_pos_count"],
    "owns_region_area_um2": true
  },
  "run": {
    "run_id": "20260807T143012Z-3f9a1c",
    "started_utc": "2026-08-07T14:30:12Z",
    "finished_utc": "2026-08-07T14:52:40Z",
    "operator": "chubyeon",
    "host": "DESKTOP-XXXX",
    "command_line": "python morphometry/measure_architecture.py --slide-root D:/wsi_stage1"
  },
  "software": {
    "ifquant_repo_commit": "a1b2c3d",
    "ifquant_repo_dirty": false,
    "ifquant_branch": "claude/qupath-wsi-stage1-tiling",
    "contract_version": "1.0.0",
    "aggregate_to_mouse_sha256": "…",
    "if_quant_pipeline_sha256": "…",
    "runtimes": [
      {"name": "python", "version": "3.12.4"},
      {"name": "qupath", "version": "0.7.0"},
      {"name": "imagej", "version": "1.54p99"},
      {"name": "bioformats", "version": "8.5.0"},
      {"name": "java", "version": "21.0.7"},
      {"name": "os", "version": "Windows 11 aarch64"}
    ],
    "libraries": [{"name": "numpy", "version": "2.1.0"}]
  },
  "inputs": [
    {
      "role": "source_image",
      "path": "D:/Confocal_Images/20260806_CW/20260806_CW/slideA.vsi",
      "series_index": 2,
      "pixel_size_um": 0.345,
      "size_px": [57165, 42154],
      "n_channels": 4,
      "bit_depth": 16,
      "dynamic_range_note": "12-bit data in uint16 (0-4095)",
      "sha256": null,
      "sha256_skipped_reason": "source >10 GB; identity carried by path+mtime+size",
      "size_bytes": 12345678901,
      "mtime_utc": "2026-08-06T09:11:02Z"
    },
    {"role": "stage1_manifest", "path": "D:/wsi_stage1/stage1_manifest.json", "sha256": "…"},
    {"role": "marker_registry", "path": "config/lung_marker_registry.json",
     "schema_version": "1.3.0", "sha256": "…"},
    {"role": "injury_model_profile", "path": "config/injury_models/influenza_pr8.json",
     "profile_id": "influenza_pr8", "profile_version": "1.0.0", "sha256": "…"}
  ],
  "parameters": {
    "locked": {
      "ager_threshold": 150,
      "damage_sigma_um": 40,
      "damage_cutoff": 0.14
    },
    "free": {"min_component_area_um2": 50.0},
    "lock_source": {
      "ager_threshold": "config/injury_models/influenza_pr8.json#thresholds.ager_threshold",
      "damage_sigma_um": "config/injury_models/influenza_pr8.json#thresholds.damage_sigma_um",
      "damage_cutoff": "config/injury_models/influenza_pr8.json#thresholds.damage_cutoff"
    },
    "calibration": {
      "calibrated_on": "uninfected control slides only",
      "calibration_run_id": "20260805T…",
      "criterion": "alpha = 0.01 false-positive area fraction on uninfected lung",
      "held_out_result": "infected 6.71% / 4.68% damaged vs control 0.93% / 0.18%",
      "locked_utc": "2026-08-05T00:00:00Z"
    }
  },
  "outputs": [
    {"path": "morphometry_slide_summary.csv", "n_rows": 4, "sha256": "…",
     "row_identity_key": ["output_key", "region", "section_id", "panel"]}
  ],
  "qc": {
    "status": "ok",
    "blocking": [],
    "warnings": [],
    "area_reconciliation": {
      "module_area_um2": 74991234.0,
      "reference_area_um2": 75002210.0,
      "reference_source": "sum(tile_manifest.core_raster_area_um2)",
      "rel_diff": 0.000146,
      "tolerance": 0.01,
      "passed": true
    },
    "coverage": {"n_expected": 372, "n_analyzed": 372, "fraction": 1.0}
  },
  "contract": {
    "contract_version": "1.0.0",
    "validated_by": "contract/ifq_contract.py",
    "validation_utc": "2026-08-07T14:52:41Z",
    "sum_columns": ["region_area_um2", "morph_airspace_positive_area_um2"],
    "recomputed_at_mouse_level": ["morph_airspace_positive_area_fraction"],
    "dropped_columns": [],
    "declared_endpoints": ["config/endpoints/mli.json"]
  },
  "statistics": {
    "n_definition": "mouse_id",
    "n_mice_contributed": 4,
    "pseudoreplication_guard": "rows are pooled to mouse by aggregate_to_mouse.py; group stats use n = mice"
  }
}
```

Non-negotiable fields for a methods section: `software.runtimes`, `inputs[].pixel_size_um`,
`parameters.locked` + `parameters.lock_source` + `parameters.calibration`,
`qc.area_reconciliation`, `statistics.n_definition`.

`sha256: null` is permitted **only** with a non-null `sha256_skipped_reason` — a ~10 GB
`.vsi` is not worth hashing on every run, but the omission must be explicit.

---

## 5. `config/` layout: registry and injury profiles side by side

Read first: `config/lung_marker_registry.json` (schema 1.3.0; top-level keys
`schema_version`, `title`, `scope`, `source_note`, `role_templates`, `research_profiles`,
`markers` — 72 markers) and `config/custom_panels.example.json` (schema 1.0.0; top-level
`schema_version`, `notes`, `panels`).

Two facts constrain any change:
* `IF_Quant_Pipeline.groovy:158` — `envOr("IFQ_MARKER_REGISTRY", new File("config/lung_marker_registry.json").getAbsolutePath())`. The default path is **relative to the process CWD** and the file is **frozen-engine input**.
* `launcher/build.ps1:12` embeds `config\lung_marker_registry.json` as the resource `IFQuant.lung_marker_registry.json` (consumed at `launcher/IFQuantLauncher.cs:2157,2171`).

So `config/lung_marker_registry.json` **cannot move and cannot gain required keys the
engine does not expect.** Injury-model profiles must therefore be *additive files beside
it*, never edits inside it.

```
config/
  lung_marker_registry.json      UNCHANGED   what a marker IS       (biology vocabulary)
  custom_panels.example.json     UNCHANGED   how channels map       (acquisition)
  injury_models/                 NEW         what a model DOES      (study parameters)
    _schema.json
    influenza_pr8.json
    bleomycin.json
    kras_luad.json
    treg_depletion.json
    ipf_fibrosis_human.json
    ali_organoid.json
  endpoints/                     NEW         what a NUMBER means    (ratio declarations)
    _schema.json
    ectopic_pod_over_damaged.json
  provenance.schema.json         NEW
```

**Separation of concerns, enforced by reference not by copy.** An injury-model profile
never restates marker biology; it *references* registry entries by symbol and fails
closed if the symbol is absent:

```json
{
  "profile_schema_version": "1.0.0",
  "profile_id": "influenza_pr8",
  "requires": {
    "marker_registry_schema": ">=1.3.0",
    "markers": ["DAPI", "KRT5", "AGER", "PDPN"],
    "research_profile": "acute_injury_regeneration"
  },
  "thresholds": { "…locked values, with calibration provenance…" }
}
```

Rules:
1. The registry is the **only** place a marker's aliases / role / localisation live.
   A profile that redefines a marker is invalid — `contract/ifq_contract.py` rejects any
   `markers` key inside a profile.
2. `research_profiles` inside the registry stay as they are (they are *questions and
   marker sets*). `config/injury_models/*` hold *numbers*: thresholds, sigmas, cutoffs,
   minimum areas, expected compartments, endpoint ids. A profile may `"extends"` a
   registry `research_profile` by name.
3. Every locked number carries its own `calibrated_on`, `criterion`, `held_out_result`,
   `locked_utc`, and `applies_to` guard (pixel size, magnification, species, fixation).
   A module must refuse to reuse a locked threshold outside its `applies_to` envelope —
   that is what stops the influenza AGER cutoff silently governing a bleomycin study.
4. `IFQ_MARKER_REGISTRY` (existing) is joined by a new `IFQ_INJURY_PROFILE`. Neither
   defaults to the other; both are recorded in provenance `inputs[]`.
5. Versioning: `schema_version` bumps are additive-only for the registry (the engine
   parses it); profiles are free to bump because only new code reads them.

---

## 6. Migration order

### Do first (zero risk, no repo files touched by the change semantics)
1. **Add `contract/ifq_contract.py`** and run it, read-only, against an existing
   `run_summary.csv` and `slide_level_summary.csv`. It imports `classify_columns` from
   the real `aggregate_to_mouse.py`, so it can never drift from the aggregator.
   *This is step 1 because it makes every later step checkable.*
2. **Add `config/provenance.schema.json`, `config/endpoints/`, `config/injury_models/`.**
   Pure additions; nothing reads them yet.
3. **Create empty `morphometry/`, `spatial/`, `registration/` with README stubs**
   naming their `panel@scope`, their owned columns, and whether they own
   `region_area_um2`. Reserving the namespace before code exists is what prevents two
   modules from claiming `morph_airspace_*`.
4. **Write `docs/MODULE_CONTRACT.md`** (this file) and link it from `README.md` and
   `docs/README.md`.

### Can wait
5. `merge_module_summaries.py` — only needed when a second producer exists.
6. Move `qupath_wsi_tile_export.groovy` → `wsi/` (docs-only cost, see `PROPOSED_TREE.md`).
7. Backfill provenance sidecars for the Stage-1/Stage-3 scripts.
8. Append `@<scope>` to `panel` in `aggregate_tiles_to_slide.py`. **Behaviour-changing**:
   it renames every existing mouse-level group. Do it behind an opt-in flag
   (`--panel-scope-suffix`), default off, and only at the start of a fresh analysis.

### Must never be done automatically
9. **Never** edit `IF_Quant_Pipeline.groovy`. Frozen.
10. **Never** edit `classify_columns`, `KEY_COLS`, `ROW_ID_COLS`, or the duplicate check
    in `aggregate_to_mouse.py` to accommodate a module. If a module does not fit, the
    module's column names are wrong. A widened `sum_cols` retro-actively changes what
    every past run meant.
11. **Never** auto-rename existing columns, auto-move files, or auto-run `git mv`. Every
    move in `PROPOSED_TREE.md` is a human decision with a listed reference cost.
12. **Never** copy a locked threshold from `config/injury_models/influenza_pr8.json` into
    another model's profile programmatically. Re-calibrate on that model's own controls.
13. **Never** let a module write a mouse-level or group-level CSV of its own. There is
    one path to `n = mice`.
