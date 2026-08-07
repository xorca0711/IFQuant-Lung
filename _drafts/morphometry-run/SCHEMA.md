# Morphometry module output schema

Conforms to `_drafts/hierarchy-contract/MODULE_CONTRACT.md` v1.0.0.
Every claim below is executed by `test_aggregation_contract.py` against the
**unmodified** `aggregate_to_mouse.py`, not asserted.

`module_id = morphometry.architecture`
Namespace: every measurement column contains `morph`.

---

## 0. The one rule that matters

`aggregate_to_mouse.py` decides what to do with a column by **suffix match on
the name**. There are exactly three fates:

| fate | what it means |
|---|---|
| **SUM** | added across every row of a `(mouse_id, genotype, condition, panel)` group |
| **RECOMPUTE** | the aggregator itself divides two pooled sums; the module never writes it |
| **DROP** | silently discarded, never reaches mouse level, no warning |

**No ratio, mean, index or threshold is ever emitted as a column.** A ratio that
happens to match a count suffix would be *summed*, which is the failure mode
this schema exists to prevent (`test_aggregation_contract.py` T2 executes it:
two per-slide MLIs of 61 µm and 97 µm named `class_morph_mli_count` become
`class_morph_mli_count_total = 158.0` — a length that means nothing).

---

## 1. Row identity — one row per (slide × compartment)

| column | type | fate | notes |
|---|---|---|---|
| `image` | string | DROP (identity) | slide stem |
| `output_key` | string | DROP (identity) | `<stem>__morph__ds<N>__<scope>`, globally unique |
| `region` | string | DROP (identity) | `parenchyma_<scope>`; **informational only** |
| `section_id` | string | DROP (identity) | slide stem; counted into `n_sections` |
| `mouse_id` | string | **KEY** | biological replicate. This is n. |
| `genotype` | string | **KEY** | |
| `condition` | string | **KEY** | |
| `panel` | string | **KEY** | `LEFT@<scope>` — **this is what carries the compartment** |
| `module_id` | string | DROP | provenance |

### Why the compartment lives in `panel` and not in `region`

`aggregate_to_mouse.py` groups on `KEY_COLS = [mouse_id, genotype, condition,
panel]` (line 43) and pools **across** `region` inside a group. Two rows for the
same animal that differ only in `region` are therefore **added together**.

`test_aggregation_contract.py` T4 runs both designs on the same numbers:

| design | mouse rows | recovered MLI |
|---|---|---|
| compartment in `region` (the draft) | **1** | one merged 56.00 µm |
| compartment in `panel` (this module) | **2** | damaged 20.00 µm, intact 60.00 µm |

The inputs were 20 µm (damaged) and 60 µm (intact). With the compartment in
`region`, the cross-check this module exists to perform is destroyed silently at
the mouse level. `MODULE_CONTRACT.md` §2.3 already specifies `panel@scope`; the
morphometry draft did not use it.

**Emitted** `scope ∈ {damaged_edge, damaged_core, intact_edge, intact_core}`.
These four **partition** the whole analysis ROI and never overlap, so the §2.3
non-overlap invariant holds inside every panel group. `*_edge` is the part
within 40 µm (one damage-detector σ) of the *other* compartment; `*_core` is the
rest.

**Composite** scopes — `damaged = damaged_edge + damaged_core`,
`intact = intact_edge + intact_core`, `parenchyma` = all four — are synthesised
by `morphometry_derive.py` by **summing the pooled primitives**, which is exact
because every carried column is additive. They are deliberately **not** emitted
by the Groovy: `damaged` overlaps `damaged_core`, and two overlapping rows inside
one panel group would be double-counted by `aggregate_to_mouse.py`.

Checked on the real output: `damaged_edge + damaged_core` reproduces the locked
detector's damaged area to 4 decimal places on all four slides
(0.0671, 0.0468, 0.0093, 0.0018 of tissue).

---

## 2. Denominator

| column | fate | mouse-level result |
|---|---|---|
| `region_area_um2` | **SUM** (exact-name match, `agg:184`) | `total_tissue_area_um2` |

Set to the compartment's own area on the coarse (2.76 µm/px) grid. Each panel
group therefore has its own denominator, which is exactly what makes
`_positive_area_fraction` meaningful per compartment.

---

## 3. Areas — `<Name>_positive_area_um2` (SUM)

| column | fate | what it is |
|---|---|---|
| `morph_tissue_positive_area_um2` | **SUM** | nucleated (DAPI⁺) phase, fine grid |
| `morph_airspace_positive_area_um2` | **SUM** | its complement inside the ROI |
| `morph_measured_positive_area_um2` | **SUM** | area the fine pass actually visited — the denominator every fraction uses, and the coverage/sampling-fraction QC |
| `morph_aircomp_positive_area_um2` | **SUM** | airspace area in labelled components (coarse grid) |
| `morph_aircomp_n_components` | **SUM** | paired with the above |
| `morph_airbig_positive_area_um2` | **SUM** | airspace in components > 10 000 µm² |
| `morph_lowair_positive_area_um2` | **SUM** | architecture-only "consolidated" area (optional; off unless a cutoff is locked) |

Free at mouse level, RECOMPUTED by the aggregator from pooled sums:
`<Name>_positive_area_um2_total`, `<Name>_positive_area_fraction`,
`<Name>_n_components_total`, `<Name>_mean_component_area_um2`.

> Trap 4 from the contract: a **bare** `*_area_um2` (e.g. `morph_airspace_area_um2`)
> is DROPPED. T1 executes this.

---

## 4. Lengths and counts — `class_<Label>_count` (SUM)

All additive by construction: each is attributed to the region of its first
pixel and counted only if that pixel is in the block core, so overlapping
halo blocks double-count nothing. Proved exactly (tolerance 1e-9) by the
`additivity:` self-test group.

| column | fate | what it is |
|---|---|---|
| `class_morph_perimeterum_count` | **SUM** | Crofton boundary length, µm |
| `class_morph_chordlen000um_count` … `_chordlen135um_count` | **SUM** | summed untruncated airspace chord length per orientation (0°, 45°, 90°, 135°), µm |
| `class_morph_chordn000_count` … `_chordn135_count` | **SUM** | untruncated chord count per orientation |
| `class_morph_testlineum_count` | **SUM** | total test-line length inside the compartment, µm |
| `class_morph_transition_count` | **SUM** | air↔tissue transitions |
| `class_morph_chordtruncn_count` | **SUM** | chords rejected as truncated |
| `class_morph_chordtrunclenum_count` | **SUM** | their summed length, µm |
| `class_morph_septaldistum_count` | **SUM** | Σ corrected distance-to-boundary over the tissue phase, µm |
| `class_morph_septalpx_count` | **SUM** | tissue pixels entering that sum |
| `class_morph_airdistum_count` | **SUM** | same for the airspace phase, µm |
| `class_morph_airpx_count` | **SUM** | airspace pixels entering that sum |
| `class_morph_sdist_b00_count` … `_b23_count` | **SUM** | histogram of tissue-phase distance, 0.5 µm bins, b23 = overflow |
| `class_morph_adist_b00_count` … `_b23_count` | **SUM** | same for the airspace phase |
| `class_morph_rows_count` | **SUM** | 1 per row; a mouse total ≠ n_regions means rows were lost |

**Why four separate chord orientations instead of one pooled pair.** Diagonal
test lines on a square lattice are spaced δ/√2 apart, so per unit area they
deliver √2 more test-line length and ~√2 more chords than the axial families.
Measured on the disk phantom in the self-test: the diagonals carry **1.41–1.43×**
the chords of the axes. Pooling all four numerators and denominators is
therefore a *weighted* orientation average with weights (1, √2, 1, √2), not the
equal-weight average stereology requires. Keeping them separate costs four extra
columns and makes the orientation average exact — and it makes anisotropy
measurable rather than assumed away.

**Why lengths are spelled `…um_count` and not `…_um_count`.** `MODULE_CONTRACT.md`
§2.2 forbids a `<Name>` ending in `_um`. That rule is aimed at derived scalars,
and an extensive summed length is not one — but the module conforms to the rule
as written rather than arguing with it. See REVIEW.md for the recommended
amendment.

---

## 5. QC / provenance — DROPPED by the aggregator, kept in the slide CSV

`morph_px_fine_um`, `morph_px_coarse_um`, `morph_ds_fine`,
`morph_tissue_threshold`, `morph_threshold_locked`, `morph_channels`,
`morph_dist_bin_um`, `morph_ager_threshold`, `morph_damage_sigma_um`,
`morph_damage_cutoff`, `morph_erode_um`, `morph_roi_thr`, `morph_n_blocks`,
`morph_block_stride`, `morph_coverage_complete`.

These are deliberately dropped: they describe the run, not the animal, and
summing or averaging them would be meaningless. They live in the slide-level CSV
and in `morphometry_manifest.json`. T5 asserts that **every** dropped column is
one of these and that no measurement column is dropped by accident.

---

## 6. RECOMPUTE — produced by `morphometry_derive.py`, never a column

Formed once, from pooled numerator and pooled denominator, after
`aggregate_to_mouse.py` has run.

| derived metric | formula from pooled totals |
|---|---|
| `morph_tissue_fraction` | `morph_tissue_positive_area_um2_total / morph_measured_positive_area_um2_total` |
| `morph_airspace_fraction` | `morph_airspace…_total / morph_measured…_total` |
| `morph_mli_dir{000,045,090,135}_um` | `class_morph_chordlen<θ>um_count_total / class_morph_chordn<θ>_count_total` |
| **`morph_mli_direct_um`** | **mean of the four `morph_mli_dir<θ>_um`** (equal orientation weight) |
| `morph_mli_anisotropy_ratio` | max/min over the four orientations |
| `morph_mli_indirect_um` | `2 × class_morph_testlineum_count_total / class_morph_transition_count_total` |
| `morph_chord_truncated_fraction` | `truncn / (truncn + Σ chordn)` |
| `morph_wall_thickness_edmmean_um` | `4 × class_morph_septaldistum_count_total / class_morph_septalpx_count_total` |
| `morph_wall_thickness_edmmedian_um` | `4 × median(pooled sdist histogram)` |
| `morph_wall_thickness_2a_over_b_um` | `2 × morph_tissue_positive_area_um2_total / class_morph_perimeterum_count_total` |
| `morph_airspace_width_edmmean_um` | `4 × class_morph_airdistum_count_total / class_morph_airpx_count_total` |
| `morph_airspace_width_edmmedian_um` | `4 × median(pooled adist histogram)` |
| `morph_surface_density_per_um` | `(4/π) × class_morph_perimeterum_count_total / morph_measured…_total` |
| `morph_finepass_coverage_of_compartment` | `morph_measured…_total / total_tissue_area_um2` |
| `morph_mean_airspace_component_eqdiam_um` | from `morph_aircomp_mean_component_area_um2` |
| `morph_confluent_airspace_fraction` | `morph_airbig…_total / morph_aircomp…_total` |
| `morph_connectivity_interpretable` | `false` when the above > 0.90 |

**MLI is not summable and is never carried.** `test_aggregation_contract.py`:

* **T1** — a column literally named `morph_mli_direct_um` at slide level is
  **absent** from `mouse_level_summary.csv`. So is `morph_septal_thickness_um`.
  The drop is silent: nothing in the aggregator's stdout mentions it.
* **T3** — pooling is not averaging. Two slides with 1000 chords / 61 000 µm and
  100 chords / 9 700 µm give a pooled MLI of **64.27 µm**; the mean of the two
  per-slide MLIs (61.0, 97.0) is **79.00 µm**, i.e. **+22.9 %**. The naive
  average weights a 100-chord slide equally with a 1000-chord slide.
* **T5** — the real emitted header contains no column whose `<Name>` ends in a
  forbidden suffix, every measurement column reaches mouse level, and
  `mouse_level_summary.csv` contains **no** column matching `mli` or
  `intercept`.

---

## 7. Sampling fraction and partial runs

`IFQ_MORPH_BLOCK_STRIDE = k` keeps the diagonal block lattice
`(bx_index + by_index) % k == 0` — systematic uniform sampling of fields over
the whole section, the classical SURS design, not a contiguous corner.

Because every derived ratio divides by `morph_measured_positive_area_um2_total`
rather than by the compartment area, a strided run yields the same estimators
with larger variance and **no bias**, and
`morph_finepass_coverage_of_compartment` reports the sampling fraction directly.
`morph_block_stride` and `morph_coverage_complete` record it in the slide CSV.
