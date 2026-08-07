# IFQuant-Lung — proposed tree (DRAFT, nothing applied)

Legend: **UNCHANGED** = stays exactly where it is · **NEW** = does not exist today ·
**MOVED** = relocated, with every reference listed · **MOVE REJECTED** = considered and
deliberately left in place.

Guiding rule: **the repository grows sideways, not upward.** Every file that another file
resolves *by path* stays at its current path. Only files that are invoked by a human
(and referenced solely in prose) are candidates for a move.

---

## 0. The four hard path couplings that pin the root

Any reorganisation must respect these. All four verified by reading the source.

| # | Coupling | Evidence |
|---|---|---|
| 1 | The frozen engine resolves the marker registry **relative to the process CWD** | `IF_Quant_Pipeline.groovy:158` — `envOr("IFQ_MARKER_REGISTRY", new File("config/lung_marker_registry.json").getAbsolutePath())` |
| 2 | Stage 3 imports Stage 4 **as a filesystem sibling** | `aggregate_tiles_to_slide.py:69-74` — `sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))` then `from aggregate_to_mouse import classify_columns, _num, marker_of`, with `sys.exit("ERROR: aggregate_to_mouse.py must sit beside this script …")` |
| 3 | The sharded runner assumes the engine is **one level above `scripts/`** | `scripts/Invoke-Stage2Sharded.ps1:36` — `[string]$ScriptPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'IF_Quant_Pipeline.groovy')` |
| 4 | The launcher build embeds **root/engine + root/config/registry** as .NET resources | `launcher/build.ps1:11-12,58-59`; consumed at `launcher/IFQuantLauncher.cs:2156-2157,2167-2171` |

Consequence: `IF_Quant_Pipeline.groovy`, `aggregate_to_mouse.py`,
`aggregate_tiles_to_slide.py` and `config/lung_marker_registry.json` are **immovable**.
They are also exactly the shared-schema files, so this is a happy accident rather than a
constraint to fight.

---

## 1. Proposed tree

```
IFQuant-Lung/
│
├── IF_Quant_Pipeline.groovy                     UNCHANGED  ← FROZEN measurement engine
├── qupath_wsi_tile_export.groovy                MOVED  → wsi/   (cheap; §2.1)
├── aggregate_tiles_to_slide.py                  UNCHANGED  (MOVE REJECTED, §2.2)
├── aggregate_to_mouse.py                        UNCHANGED  ← SHARED, must not fork
├── merge_module_summaries.py                    NEW    (root — sibling of the aggregators)
├── samplesheet_template.csv                     UNCHANGED
├── README.md                                    UNCHANGED file, text edits only
├── WORKFLOW.md                                  UNCHANGED file, text edits only
├── .gitignore                                   UNCHANGED file, 3 lines appended (§4)
├── IFQuantLauncher-v1.7.2.exe / .sha256.txt     UNCHANGED
│
├── contract/                                    NEW  ← the enforcement layer
│   ├── README.md                                NEW
│   ├── ifq_contract.py                          NEW  runnable validator; imports
│   │                                                 classify_columns from the real
│   │                                                 aggregate_to_mouse.py so it cannot drift
│   ├── ifq_provenance.py                        NEW  provenance writer/validator helper
│   └── test_contract.py                         NEW  stdlib unittest, no deps
│
├── config/                                      SHARED — additive only
│   ├── README.md                                UNCHANGED file, section appended
│   ├── lung_marker_registry.json                UNCHANGED  ← IMMOVABLE (coupling 1 & 4)
│   ├── custom_panels.example.json               UNCHANGED
│   ├── provenance.schema.json                   NEW
│   ├── injury_models/                           NEW
│   │   ├── _schema.json                         NEW
│   │   ├── influenza_pr8.json                   NEW  ← holds the LOCKED damage detector
│   │   ├── bleomycin.json                       NEW  (thresholds null until calibrated)
│   │   ├── kras_luad.json                       NEW
│   │   ├── treg_depletion.json                  NEW
│   │   ├── ipf_fibrosis_human.json              NEW
│   │   └── ali_organoid.json                    NEW
│   └── endpoints/                               NEW
│       ├── _schema.json                         NEW
│       ├── ectopic_pod_over_damaged.json        NEW  ← the primary endpoint, declared
│       └── damaged_fraction_of_parenchyma.json  NEW
│
├── wsi/                                         NEW dir  (QuPath whole-slide front end)
│   ├── README.md                                NEW
│   └── qupath_wsi_tile_export.groovy            MOVED from root
│
├── morphometry/                                 NEW  (A) architecture, brightfield-capable
│   ├── README.md                                NEW  ← declares panel@scope + owned columns
│   ├── measure_architecture.groovy              NEW  (QuPath/Fiji front end)
│   └── emit_morphometry_summary.py              NEW  (writes contract-compliant CSV)
│
├── spatial/                                     NEW  (B) niche / neighbourhood statistics
│   ├── README.md                                NEW
│   └── emit_spatial_summary.py                  NEW  (consumes engine __cells.csv)
│
├── registration/                                NEW  (C) serial-section IF ↔ histology
│   ├── README.md                                NEW
│   └── emit_registration_summary.py             NEW
│
├── scripts/                                     UNCHANGED dir
│   ├── Invoke-Stage2Sharded.ps1                 UNCHANGED  ← IMMOVABLE (coupling 3)
│   ├── calibrate_damage_controls.groovy         UNCHANGED
│   ├── measure_damage_locked.groovy             UNCHANGED
│   ├── calibrate_krt5_controls.groovy           UNCHANGED
│   ├── TestRunErrorAudit.ps1                    UNCHANGED
│   ├── TestRunCellAudit.cs                      UNCHANGED
│   └── BuildTestRunErrorAuditWorkbook.ps1       UNCHANGED
│
├── docs/                                        UNCHANGED dir
│   ├── README.md                                UNCHANGED file, index entries added
│   ├── MODULE_CONTRACT.md                       NEW  ← the contract
│   ├── LEVELS_AND_DENOMINATORS.md               NEW  ← tile/slide/mouse reconciliation
│   ├── PROVENANCE.md                            NEW
│   ├── INJURY_MODEL_PROFILES.md                 NEW
│   ├── WSI_TILING_WORKFLOW.md                   UNCHANGED file, 2 path edits (§2.1)
│   ├── ECTOPIC_POD_ENDPOINT.md                  UNCHANGED
│   ├── MARKER_MORPHOLOGY_GUIDE.md               UNCHANGED
│   ├── UNIVERSAL_MARKER_CONFIGURATION.md        UNCHANGED
│   ├── COMPARTMENT_TAGS_AND_PROGRESSION.md      UNCHANGED
│   ├── Z_STACK_ANALYSIS.md                      UNCHANGED
│   ├── BRANCHING.md                             UNCHANGED
│   ├── PILOT_G002_MORPHOLOGY_RESULTS.md         UNCHANGED
│   ├── SCRIPT_SELF_REVIEW_20260723.md           UNCHANGED
│   ├── TEST_RUN_ERROR_RATE_AUDIT.md             UNCHANGED
│   └── UNIVERSAL_FALSE_NEGATIVE_AUDIT_20260728.md UNCHANGED
│
├── launcher/                                    UNCHANGED dir (coupling 4)
│   ├── IFQuantLauncher.cs                       UNCHANGED
│   ├── build.ps1                                UNCHANGED
│   ├── app.manifest                             UNCHANGED
│   └── README.md                                UNCHANGED
│
└── legacy/                                      UNCHANGED dir, entirely
```

`morphometry/`, `spatial/` and `registration/` are **peers of `wsi/`, not of `contract/`**:
they are *producers* of contract-compliant CSVs. `contract/` is the *referee*. Keeping the
referee out of the producer tree is what stops a module from "fixing" the contract to suit
itself.

---

## 2. Move analysis, file by file

### 2.1 `qupath_wsi_tile_export.groovy` → `wsi/qupath_wsi_tile_export.groovy` — **MOVE (recommended)**

Nothing resolves this file by path. It is launched by a human:
`"X:\QuPath\QuPath-0.7.0 (console).exe" script .\qupath_wsi_tile_export.groovy`.
The script itself resolves its inputs from environment variables, not from its own
location.

Complete reference list to update (7 sites, all prose):

| File:line | Current text | Action |
|---|---|---|
| `README.md:36` | ``- **`qupath_wsi_tile_export.groovy`** — whole-slide front end.`` | → `` `wsi/qupath_wsi_tile_export.groovy` `` |
| `WORKFLOW.md:95` | ``…`qupath_wsi_tile_export.groovy` (QuPath, Stage 1 tiling),`` | prefix `wsi/` |
| `docs/WSI_TILING_WORKFLOW.md:32` | ``Stage 1   qupath_wsi_tile_export.groovy   .vsi -> tiles/*.ome.tif`` | prefix `wsi/` |
| `docs/WSI_TILING_WORKFLOW.md:166` | ``& "X:\QuPath\…" script .\qupath_wsi_tile_export.groovy`` | → `.\wsi\qupath_wsi_tile_export.groovy` — **this one is a copy-pasted command; getting it wrong wastes a run** |
| `aggregate_tiles_to_slide.py:10` | docstring `Stage 1  qupath_wsi_tile_export.groovy` | prefix `wsi/` (comment only) |
| `qupath_wsi_tile_export.groovy:2` | own header banner | rewrite |
| `qupath_wsi_tile_export.groovy:32` | own usage example | rewrite |

Cost: 7 prose edits, zero code-resolution risk. Gain: `wsi/` becomes a real front-end
namespace that `morphometry/`/`spatial/`/`registration/` can be peers of, which is the
whole point of the requested hierarchy. **Worth it.**

### 2.2 `aggregate_tiles_to_slide.py` → `wsi/` — **MOVE REJECTED**

Superficially it belongs in `wsi/` (it is Stage 3 of the WSI route). It must not move.

`aggregate_tiles_to_slide.py:69-74` inserts **its own directory** on `sys.path` and
imports `aggregate_to_mouse`. Moving it to `wsi/` makes that `sys.path` entry `wsi/`,
`aggregate_to_mouse` is not there, and the script exits with
`"ERROR: aggregate_to_mouse.py must sit beside this script (its column classification is
reused so pooling cannot drift)."`

The two survivable fixes are both worse than not moving:
* move `aggregate_to_mouse.py` into `wsi/` as well — but it is the **shared** aggregator
  for the field/confocal route too, and burying it under `wsi/` mislabels it as
  WSI-specific. That is precisely the drift the no-fork rule exists to prevent;
* add a path shim to `aggregate_tiles_to_slide.py` — a new failure mode (a stale
  `aggregate_to_mouse.py` elsewhere on `sys.path` would be imported silently) in exchange
  for tidier directories.

Additional prose cost had it moved anyway: `README.md:41`, `WORKFLOW.md:97`,
`docs/WSI_TILING_WORKFLOW.md:41,225`, `scripts/Invoke-Stage2Sharded.ps1:17,188`,
`qupath_wsi_tile_export.groovy:12`. **The move costs more than it gains. Leave it.**

Mitigation: `wsi/README.md` states in its first line that Stage 3 lives at the repository
root next to Stage 4, and why.

### 2.3 `aggregate_to_mouse.py` — **MOVE REJECTED (never move)**

Referenced from 24 sites, including a live relative markdown link
(`WORKFLOW.md:102` → `[aggregate_to_mouse.py](aggregate_to_mouse.py)`), the sibling import
in `aggregate_tiles_to_slide.py:71`, and copy-paste commands at `README.md:312`,
`WORKFLOW.md:708`, `docs/WSI_TILING_WORKFLOW.md:226`. It is also the file the entire
contract is written against. Root, permanently.

### 2.4 `IF_Quant_Pipeline.groovy` — **MOVE REJECTED (frozen + coupled)**

`scripts/Invoke-Stage2Sharded.ps1:36` and `launcher/build.ps1:11` both resolve it by
path; `launcher/IFQuantLauncher.cs:2170` writes it back to a root. Frozen anyway.

### 2.5 `config/lung_marker_registry.json` — **MOVE REJECTED**

`IF_Quant_Pipeline.groovy:158` (frozen, CWD-relative default) and `launcher/build.ps1:12`
(`/resource:$registry,IFQuant.lung_marker_registry.json`). Also referenced from
`README.md:187,190,340`, `docs/UNIVERSAL_MARKER_CONFIGURATION.md:9,149`, `config/README.md:3`.

### 2.6 `samplesheet_template.csv` — **MOVE REJECTED (not worth it)**

Only 3 prose references (`README.md:43,299`, `WORKFLOW.md:103`), so the move is cheap —
but it is a *shared* metadata template consumed by both routes and by the launcher's
workflow, and `README.md:299` is a "copy this file" instruction. Nothing gained.

### 2.7 `scripts/*.groovy` calibration scripts — **MOVE REJECTED for now**

`calibrate_damage_controls.groovy` / `measure_damage_locked.groovy` are arguably
`morphometry/` citizens. But they produced the **locked** damage detector
(AGER 150, σ 40 µm, cutoff 0.14) and any path change invalidates the copy-paste record in
whatever lab notebook recorded the calibration run. Leave them; reference them from
`config/injury_models/influenza_pr8.json` by path instead. Revisit only after the
threshold is re-derived for a second injury model.

### 2.8 `legacy/` — **UNCHANGED, entirely**

It is an archive with its own index (`legacy/README.md`). Touching it destroys the thing
it is for.

---

## 3. What each new directory owns

| Directory | Owns `region_area_um2`? | Default `panel@scope` | Reserved column prefix |
|---|---|---|---|
| engine (`IF_Quant_Pipeline.groovy`) | **yes** | `<PANEL>@damaged` (WSI, partitioned) / `<PANEL>@whole_tissue` (field) | marker symbols from the registry |
| `wsi/` | no (writes `tile_manifest.csv`, not summary rows) | — | — |
| `morphometry/` | yes, on `@parenchyma` scope | `<PANEL>@parenchyma` | `morph_*` |
| `spatial/` | **no** — numerators only (§ contract 3.2b) | inherits the engine's scope | `spat_*`, `class_*` |
| `registration/` | no | `<PANEL>@registered` | `reg_*` |

The "owns `region_area_um2`" column is the double-counting guard. Exactly one **yes** per
`(mouse, panel@scope)`. `contract/ifq_contract.py --check-ownership` enforces it across a
merged CSV.

---

## 4. `.gitignore` additions (3 lines)

```
# module summaries + provenance sidecars are run outputs, not source
*_module_summary.csv
*.provenance.json
module_run_manifest.json
```

Rationale matches the existing block that already ignores `slide_level_summary*.csv`,
`mouse_level_summary.csv`, `group_level_summary.csv` (`.gitignore`, "Whole-slide (WSI)
Stage 1/2/3 artefacts" section). `config/**` stays tracked — profiles are source.

---

## 5. Summary count

| Class | Count |
|---|---|
| UNCHANGED (path and content) | 38 |
| UNCHANGED path, prose edits only | 6 (`README.md`, `WORKFLOW.md`, `.gitignore`, `config/README.md`, `docs/README.md`, `docs/WSI_TILING_WORKFLOW.md`) |
| MOVED | **1** (`qupath_wsi_tile_export.groovy` → `wsi/`), 7 prose references |
| MOVE REJECTED after analysis | 7 (§2.2–2.8) |
| NEW files | 27 |
| NEW directories | 6 (`contract/`, `wsi/`, `morphometry/`, `spatial/`, `registration/`, `config/injury_models/` + `config/endpoints/`) |

One move. That is the point.
