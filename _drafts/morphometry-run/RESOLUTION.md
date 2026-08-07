# Resolution dependence — measured, not assumed

The repo already knew tissue **area** moves 21 % between `IFQ_WSI_TISSUE_DOWNSAMPLE`
16 and 32 (`docs/WSI_TILING_WORKFLOW.md` line 123: 75.06 vs 91.15 mm²). Every
morphometric quantity is at least as resolution-sensitive, and several are far
worse. This page reports the measurement.

## Design

Two 4096 full-res-px (1.41 mm) windows per slide, chosen automatically as the
densest window of each compartment, measured at ds = 1, 2, 4, 8 (0.345, 0.690,
1.380, 2.760 µm/px) with the **same fixed tissue threshold (880)** and the same
compartment labels, so resolution is the only thing that varies.
`scripts` → `run/Sweep.ps1`, raw output in `run/sweep.log`.

## Raw

het m4-1 (infected), window in the DAMAGED compartment (42.1 % damaged):

| ds | µm/px | nucleated frac | MLI direct | MLI indirect | airspace width 4·EDM | wall thick 4·EDM | wall thick 2A/B | S_V (1/µm) | chords truncated |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 0.345 | 0.2106 | 10.94 | 16.63 | 9.72 | 3.500 | 2.215 | 0.24215 | 25.2 % |
| 2 | 0.690 | 0.2100 | 13.79 | 20.72 | 10.44 | 3.892 | 2.760 | 0.19377 | 29.7 % |
| 4 | 1.380 | 0.2076 | 16.52 | 24.98 | 11.75 | 4.597 | 3.286 | 0.16085 | 33.4 % |
| 8 | 2.760 | 0.1961 | 22.16 | 34.73 | 15.03 | 6.658 | 4.296 | 0.11620 | 40.3 % |

het m4-1, window in the INTACT compartment (61.5 % intact):

| ds | nucleated frac | MLI direct | MLI indirect | airspace 4·EDM | wall 4·EDM | wall 2A/B | S_V |
|---|---|---|---|---|---|---|---|
| 1 | 0.1986 | 14.08 | 21.73 | 11.03 | 3.712 | 2.735 | 0.18489 |
| 2 | 0.1988 | 16.28 | 24.67 | 11.32 | 3.934 | 3.116 | 0.16244 |
| 4 | 0.1987 | 17.75 | 26.99 | 11.79 | 4.411 | 3.403 | 0.14864 |
| 8 | 0.1972 | 20.99 | 32.80 | 13.41 | 6.186 | 4.087 | 0.12284 |

het m4-2 (control), DAMAGED window (only 1.6 % damaged — the control damaged
compartment is too small to fill a window):

| ds | nucleated frac | MLI direct | MLI indirect | airspace 4·EDM | wall 4·EDM | wall 2A/B | S_V |
|---|---|---|---|---|---|---|---|
| 1 | 0.1698 | 15.02 | 24.06 | 12.66 | 3.741 | 2.586 | 0.16722 |
| 2 | 0.1698 | 18.16 | 28.32 | 13.13 | 4.034 | 3.055 | 0.14157 |
| 4 | 0.1694 | 20.00 | 31.35 | 13.72 | 4.551 | 3.371 | 0.12798 |
| 8 | 0.1662 | 24.11 | 39.10 | 15.78 | 6.412 | 4.105 | 0.10308 |

het m4-2, INTACT window (67.7 % intact):

| ds | nucleated frac | MLI direct | MLI indirect | airspace 4·EDM | wall 4·EDM | wall 2A/B | S_V |
|---|---|---|---|---|---|---|---|
| 1 | 0.1421 | 17.82 | 24.81 | 12.84 | 3.136 | 2.231 | 0.16212 |
| 2 | 0.1416 | 21.23 | 29.06 | 13.32 | 3.396 | 2.615 | 0.13795 |
| 4 | 0.1402 | 23.61 | 32.56 | 14.04 | 3.939 | 2.897 | 0.12326 |
| 8 | 0.1324 | 29.71 | 42.60 | 16.63 | 5.902 | 3.559 | 0.09472 |

## Percent change from ds 1 (native), averaged over the four windows

| quantity | ds 2 | ds 4 | ds 8 | verdict |
|---|---|---|---|---|
| nucleated area fraction | **−0.1 %** | **−0.7 %** | **−4.1 %** | usable to ds 8 |
| airspace width 4·mean(EDM) | **+4.4 %** | **+11.4 %** | **+32.6 %** | usable to ds 2 |
| wall thickness 4·mean(EDM) | +8.3 % | +24.4 % | +79.1 % | resolution-bound |
| MLI indirect 2L/N | +18.2 % | +34.0 % | +73.5 % | strongly resolution-bound |
| wall thickness 2A/B | +18.5 % | +33.3 % | +65.4 % | strongly resolution-bound |
| **MLI direct** | **+20.4 %** | **+35.7 %** | **+69.7 %** | strongly resolution-bound |
| surface density S_V | −15.6 % | −25.2 % | −41.4 % | coastline; never comparable across ds |
| chords truncated (percentage points) | +4.0 | +6.3 | +11.4 | grows with pixel size |

## What this means

1. **The pre-registered 5 % locking criterion (R3) is met only at ds 1 for MLI.**
   Airspace fraction is fine anywhere down to ds 4; MLI is not fine anywhere but
   native. The rule was written before the numbers were seen and it is reported
   as failed rather than relaxed.
2. The cause is the mask, not the algorithm. On a **DAPI-only** mask the
   segmented phase is individual nuclei, 5–8 µm across, i.e. 15–23 px at ds 1 but
   4–6 px at ds 4. Small objects drop below threshold as the pixel grows, chords
   merge, and the intercept inflates. The draft's claim of **+1.8 %** for MLI at
   ds 2 was measured on a DAPI+T1α mask, where the phase is a much larger,
   smoother object; it does not transfer.
3. **The distance-transform airspace width is 4–5× less resolution-sensitive
   than the chord MLI** (+4.3 % vs +20.4 % at ds 2) while measuring the same
   physical quantity under the same slab calibration. That is a good reason to
   report it as the primary airspace-size statistic on this material, and it was
   not in the draft.
4. **Resolution dependence does not cancel in the damaged/intact ratio.** On
   m4-1, the damaged-window/intact-window ratio of MLI direct runs
   0.777 → 0.847 → 0.931 → 1.056 across ds 1 → 8, i.e. it **inverts sign by
   ds 8**. Any compartment contrast must be reported with its analysis
   resolution attached, and a contrast measured at the Stage-1 tissue-mask
   resolution (ds 16) would be meaningless.
5. Consequently the analysis is run at **two** resolutions on all four slides
   and both are reported. Nothing here is compared across resolutions.
