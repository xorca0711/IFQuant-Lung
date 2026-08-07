# Why QuPath and Fiji are used together, and how

This repository runs QuPath and Fiji **side by side, each doing what it is good
at**, with a file-based handoff between them. That is a deliberate architecture,
and it follows an established published pattern rather than being invented here.

## The reference

> Chiaruttini N, Burri O, Haub P, Guiet R, Sordet-Dessimoz J, Seitz A. (2022)
> **An Open-Source Whole Slide Image Registration Workflow at Cellular Precision
> Using Fiji, QuPath and Elastix.** *Frontiers in Computer Science* 3:780026.
> https://doi.org/10.3389/fcomp.2021.780026

Their problem was whole-slide registration, not quantification. But the
*integration* problem is the same one this repo faces, and their solution is the
one this repo uses.

### What they established

**1. Each tool has a job the other does badly.** QuPath is the whole-slide
front end: it reads multiresolution formats through Bio-Formats without
conversion, handles gigapixel pyramids, and organises images into a project.
Fiji brings the heavy image-processing ecosystem (ImgLib2, BigDataViewer,
BigWarp, and the ability to bridge to native libraries such as elastix).

**2. They cannot share a process.** From the paper: *"while Fiji and QuPath are
both Java-based software, they are incompatible in terms of Java versions."*
That single sentence is why the integration is **file-based**, not an in-process
API call. It is not a shortcut — it is the only robust option.

**3. The handoff is the actual engineering work.** Their contribution was not a
new algorithm; it was the missing connective pieces — opening a QuPath project
in BigDataViewer, exporting transformations, transferring regions of interest
back. They are explicit that *"the quality of the multi-modal registration
algorithm is only one factor among many others influencing the adoption of an
imaging analysis workflow."*

**4. Work at the coarsest resolution that answers the question.** They register
affinely at 10 µm/px and refine on sparse patches at 1 µm/px, rather than
processing whole slides at full resolution.

## How this repository maps onto that pattern

| | Chiaruttini 2022 | IFQuant-Lung |
|---|---|---|
| **QuPath's job** | project organisation, WSI display, ROI transfer | open `.vsi`, pick the true 20x series, detect tissue, cut calibrated tiles |
| **Fiji's job** | registration (BigWarp, elastix bridge) | **all measurement** — the validated morphology-first engine |
| **Handoff** | transformation files in the project entry folder | OME-TIFF tiles + `_RoiSet.zip` on disk |
| **Return path** | transforms imported back into QuPath | none — results go to CSV, then Python |
| **Multi-resolution** | 10 µm/px coarse, 1 µm/px patches | tissue detection at ~2.8 µm/px, measurement at 0.345 µm/px |

### Where we deliberately differ

**Results do not return to QuPath.** Their workflow round-trips because
registration output is only useful inside a viewer. Ours terminates in CSV
because the endpoint is a number per mouse, and the statistical unit lives in
Python. A round trip would add a dependency and buy nothing.

**QuPath measures nothing.** This is the strictest rule in the repo. QuPath
reads, tiles, and shapes ROIs; every number comes from the Fiji engine. The
reason is drift: two independent measurement engines would diverge, and the
morphology-first decision model has been validated exactly once. An earlier
branch of this project built a second QuPath-side measurement engine
(PRs #9, #10) and it was rejected for this reason.

**The ROI is the interface, not just a crop.** Their ROI transfer moves regions
between registered images. Here, the per-tile `_RoiSet.zip` is what makes
overlapping tiles sum correctly: the engine restricts every measurement to the
supplied ROI, so tiles can overlap for segmentation context while their measured
areas remain disjoint. That is what makes the Stage 1 → Stage 2 → Stage 3 chain
reconcile exactly.

## The practical consequences

**Version incompatibility is real and local.** Fiji's bundled Bio-Formats here
lacks the JPEG-2000 codec (`ome-jai`) that Olympus `.ets` tiles need, so Fiji
**cannot read `.vsi` pixel data at all** on this machine. QuPath ships the codec.
This is a concrete instance of the paper's general point, and it is why the
whole-slide route cannot simply be "point Fiji at the slide".

**File-based handoff makes each stage independently checkable.** Because the
interface is files, every stage can be validated on its own: exported tiles were
confirmed bit-identical to the source region, ROI areas were confirmed to match
the manifest exactly, and Stage 2's summed region areas reconcile against Stage 1
to machine epsilon. An in-process integration would have made those checks much
harder to write.

**It also survives crashes.** The tiling stage is resumable and its manifest is
on disk, which mattered when the data volume dropped out mid-run twice during
development.

## Reading order

* [`WSI_TILING_WORKFLOW.md`](WSI_TILING_WORKFLOW.md) — the concrete whole-slide route
* [`ECTOPIC_POD_ENDPOINT.md`](ECTOPIC_POD_ENDPOINT.md) — what the numbers mean and how they were calibrated
* [`../WORKFLOW.md`](../WORKFLOW.md) — the field/confocal route and the shared interpretation model
