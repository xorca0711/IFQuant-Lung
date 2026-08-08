# Development and AI assistance

Claude Code was used as an implementation and documentation assistant during
development of this repository. This is visible in the git history — a
substantial number of commits carry `Co-Authored-By: Claude`, and some pull
request descriptions state it explicitly. That is deliberate and is not
concealed.

**Scientific questions, experimental interpretation, analysis specifications,
acceptance and rejection criteria, validation strategy, and interpretation of
results were directed and reviewed by the repository author.** Generated
implementations were tested against the underlying data, the literature,
synthetic fixtures, or independent outputs before being retained.

The division is not "the author had ideas and the AI typed them." It is
narrower and more specific: **the AI could measure, but it could not know what
the tissue was, which comparisons were legitimate, or when a result should be
disbelieved.** Every entry below is a case where that distinction determined the
outcome.

---

## Author-directed scientific decisions

### 1. Fields and cells are not independent biological replicates

The pipeline can report ~20,000 cells from four animals. Treating those as the
sample size would inflate confidence by roughly the square root of cells per
mouse — manufacturing significance from a single animal.

The author specified the mouse as the statistical unit. `aggregate_to_mouse.py`
enforces the roll-up and prints the distinct-animal count so it cannot be
mistaken, and the constraint is stated in every document that reports a number.

→ [`WORKFLOW.md` §Statistical unit](WORKFLOW.md) · `aggregate_to_mouse.py`

### 2. Thresholds must be locked from controls, before the test data is opened

A cutoff chosen to separate the groups being compared is not a measurement of
those groups. The author directed that intensity cutoffs be re-derived **from
the uninfected controls only**, then frozen before infected animals were
measured.

`IFQ_KRT5_THRESHOLD = 300` comes from the two uninfected animals alone
(in-tissue p99.99 = 283 and 255, worst-of-both), giving control false-positive
area ≤ 1e-4 in each independently. Infected tissue then measured 8.1 % of area
above 500 — a result the cutoff had no opportunity to manufacture.

The author also accepted the consequence: for AGER and T1α, which are expressed
in healthy lung, **no such anchor exists**, so those cutoffs remain adaptive and
every output derived from them is labelled `adaptive_otsu_exploratory` rather
than reported as a result.

→ [`WORKFLOW.md` §Cutoff derivation](WORKFLOW.md)

### 3. Rejecting AGER and KRT8 when the data did not support them

Both markers were tested with a control-locked enrichment ratio
(R = infected fraction ÷ control fraction beyond the same cut) and both were
**rejected on the author's criteria**:

| Marker | R | Decision |
|---|---|---|
| AGER as a co-negativity marker | 0.99–1.05 | Retracted — the removal was definitional, not biological |
| KRT8 as a discriminator | 0.80–1.25 at every cut | Rejected — infected animals *bracket* the controls |

The AGER retraction reversed a marker the author had previously selected. The
KRT8 rejection came after the author specified the biological target (the
transitional/DATP state, not baseline alveolar epithelium), which is what made
the enrichment test the correct test.

Negative results are retained rather than deleted, so they are not re-derived.

→ [`docs/NEGATIVE_RESULTS.md`](docs/NEGATIVE_RESULTS.md) ·
`scripts/krt8_operating_point.py`

### 4. Defining which comparisons are and are not justified

The current batch is four mice: one per genotype × condition cell. Genotype is
therefore confounded with condition, and the difference between the two infected
animals (14.11 % vs 11.98 % KRT5⁺ area) cannot be distinguished from the
difference between those two animals.

The author's position, stated wherever a number appears: the measurement
behaves as expected and the infected/uninfected contrast is near-binary, but
**no statistical comparison of groups is possible from this batch**. The
reference study used n = 15 per group.

This constraint is documented as prohibitive, not as a caveat to be softened.

### 5. Cell-type identity — a call the software could not make

The ProSPC (488) channel showed a bright, continuous band lining the airway.
The pipeline had no concept that a correctly-thresholded, correctly-shaped,
sufficiently-bright population could be **the wrong cell type**.

The author identified it: those are not AT2 cells but other basal/club
lineages, and genuine AT2 have circular, perinuclear-granular morphology rather
than a continuous ring.

That specification led directly to a measurement which settled the question:
inside each population, ProSPC intensity is p50 = 430 (AT2) versus 385 (airway).
**Nearly identical** — so no intensity threshold can separate them, and the
apparent difference is contiguity and extent, not brightness. Seven display
iterations had been attempting the impossible before that call was made.

### 6. Section-level quality judgement from the bench

The author's PI identified a staining failure in one animal (M6) that no
in-image statistic had flagged. It was confirmed afterwards in the data — AGER
`frac>500` = 0.0097 in the LEFT section against 0.289 in the RIGHT section, same
antibody, same animal — but the hypothesis originated at the bench, and it
changed the interpretation: the KRT5 cutoff in practice rests on **one clean
control**, not two, and this is stated wherever the cutoff is cited.

### 7. Refusing an analysis that would have been selective manipulation

A proposal to remove the non-specific airway population using rolling-ball
background subtraction was **rejected by the author** on the grounds that the
filter radius was being chosen *because the unwanted content disappeared at that
value*.

That was correct, and it identified a repeat of an error already retired from
the codebase — a connected-component size gate that deleted image content from a
panel presented as a micrograph, which is selective manipulation under
Rossner & Yamada 2004 (*J Cell Biol* 166:11) rather than a display adjustment.

The resulting rule is encoded: a merge panel may not delete image content, and a
configuration still requesting it now fails rather than silently rendering
differently. Population suppression belongs to the QC overlay, where it is
visibly an analysis decision.

### 8. Endpoint definition, resolved against the primary source

The implementation computed KRT5⁺PDPN⁻ over a computed damaged area. The author
directed verification against the source paper, which specifies

> "percentages of **KRT5⁺PDPN⁺** areas in PDPN⁻ and KRT5⁺ areas"
> — Lin et al. 2024, *J Clin Invest* 134(19):e176828, Fig 2A–B

PDPN is expressed *by* dysplastic cells as well as AT1, so requiring
PDPN-negativity had been excluding the population being measured. The
denominator is also a hand-traced union of regions, not a computed density map —
which retired a calibrated detector that had been solving a problem the
reference does not have.

The corrected specification is declared, and the evaluator now **refuses to run
it** rather than dividing by the wrong denominator and exiting successfully.

---

## What the AI assistant did

Implementation and documentation, under the specifications above:

- Groovy/Java image-processing code, C#/WinForms launcher, Python aggregation
- Diagnostic probes and calibration scripts
- Documentation, diagrams, and provenance records
- Adversarial verification harnesses (mutation testing, execution-based
  equivalence checking, independent re-derivation of agent-produced results)

It also produced errors that the author caught, which is recorded here because
an honest account of AI-assisted work includes the failure rate:

- A segmentation overlay delivered when merge panels were requested
- Marker channels erased by over-aggressive display floors, twice
- Algorithmic diagrams downgraded to ASCII and presented as an improvement
- A single-field measurement quoted as if it were a batch statistic
- The background-subtraction proposal in §7

---

## How generated work was verified

Claims in this repository are not accepted because they were produced; they were
tested. Two are executable by a reader from a clean clone with no data:

```bash
powershell -ExecutionPolicy Bypass -File ./launcher/run_legacy_equivalence.ps1
powershell -ExecutionPolicy Bypass -File ./validation/run_demo.ps1
```

| Method | Applied to |
|---|---|
| **Execution-based equivalence** | Launcher legacy mode — 84 checks comparing what a *real child process* receives, not what a dictionary claims |
| **Mutation testing** | 28 mutants of the launcher's decision logic; 26 killed, 2 intentional surviving controls |
| **Replay to bit-identity** | The `blackBackground` defect was diagnosed by reproducing the corrupted output at **IoU = 1.0000**, which pins a cause rather than suggesting one |
| **Synthetic fixture** | `validation/` demonstrates that defect and its fix from a clone, with no patient data |
| **Regression measurement** | After the engine fix, area outputs were *measured* unchanged (worst 0.0209 pp) rather than assumed |
| **Held-out validation** | Thresholds locked on controls, then applied to infected animals not used in derivation |
| **Independent re-running** | Agent-produced results were re-executed by the author before being retained |

---

## Why this is stated plainly

AI assistance is visible in this repository's history and would be discoverable
regardless. Concealing it would be both dishonest and pointless.

The question worth answering is not whether an AI coding agent was used, but
whether scientific control was retained while using one. The record above is
offered as the evidence: the specifications, the rejections, the retraction of a
marker the author had chosen, the refusal of an analysis that would have looked
better, and the negative results kept on the record rather than deleted.
