// ============================================================================
// qupath_lung_morphometry.groovy  --  MODULE A: lung ARCHITECTURE morphometry
// ============================================================================
// DRAFT. Lives in morphometry/. Host = QuPath. Measures TISSUE ARCHITECTURE,
// not markers. IF_Quant_Pipeline.groovy is NOT touched, NOT called, and NOT
// required by anything here.
//
// WHY THIS EXISTS
//   The marker pipeline answers "which cells are positive". It says nothing
//   about whether the tissue is architecturally normal. Injury models have
//   different architectural signatures (influenza: patchy consolidation;
//   bleomycin: septal thickening; elastase/emphysema: airspace enlargement).
//   Critically it also gives an INDEPENDENT CHECK on the AGER-density
//   "damaged alveolar area" denominator: if AGER-poor territory is not also
//   architecturally abnormal, that denominator is measuring STAINING, not
//   INJURY. This script emits the 2x2 area confusion table that tests exactly
//   that (see morph_alvdmg_* columns).
//
// AGGREGATION CONTRACT  (the single most important part of this file)
//   Output is a run_summary-shaped CSV that is fed to the UNMODIFIED
//   aggregate_to_mouse.py. It must not fork the mouse-level aggregation, so
//   every measurement is emitted as an ADDITIVE PRIMITIVE whose column name
//   lands in a suffix family aggregate_to_mouse.classify_columns() already
//   recognises (verified against aggregate_to_mouse.py lines 114-201):
//
//     <x>_positive_area_um2   -> summed;  <x>_positive_area_fraction
//                                and <x>_mean_component_area_um2 recomputed
//                                from POOLED numerators   (lines 164, 314-322)
//     <x>_n_components        -> summed, paired with the above  (line 169)
//     class_<x>_count         -> summed; also emits _density_per_mm2
//                                                          (lines 171-175, 335-338)
//     region_area_um2         -> summed -> total_tissue_area_um2  (line 232)
//
//   NOTHING here is a ratio. Ratios are RECOMPUTED from pooled numerator and
//   denominator by morphometry_derive.py after aggregate_to_mouse.py has run.
//   MLI IN PARTICULAR IS NOT SUMMABLE: it is (total chord length)/(chord count)
//   and averaging per-slide MLI values would weight a 1 mm2 slide the same as a
//   80 mm2 slide. Both primitives are carried; the ratio is never carried.
//
//   Columns aggregate_to_mouse.py does not recognise are silently DROPPED by
//   it (they never reach mouse level). That is deliberate here: QC/provenance
//   columns stay in the slide-level CSV and the sidecar JSON.
//
// TWO ANALYSIS RESOLUTIONS, BOTH LOCKED AND BOTH RECORDED
//   FINE  (default downsample 2 = 0.690 um/px on this scanner)
//         areas, Crofton perimeter, airspace chords/MLI, EDM septal thickness.
//         Septa are 2-5 um; at 0.69 um/px that is 3-7 px. Everything here is
//         resolution-dependent (see the header of the sweep report) so it is
//         locked, not chosen per slide.
//   COARSE (default downsample 8 = 2.760 um/px, the same downsample
//         scripts/measure_damage_locked.groovy uses for the damage detector)
//         the analysis ROI, airspace connected components, local-airspace-
//         fraction consolidation map, box counts, and the AGER comparison.
//   IFQ_MORPH_DS_COARSE / IFQ_MORPH_DS_FINE must be an exact integer ratio;
//   the fine grid is the coarse grid subdivided k x k, so the two passes
//   partition exactly the same territory with no resampling mismatch.
//
// WHAT IS *NOT* USED, AND WHY
//   The Stage 1 tissue mask is built at downsample 16 = 5.52 um/px
//   (qupath_wsi_tile_export.groovy line 148). A 3 um septum is 0.54 px there.
//   That mask cannot represent septa at all -- it is a parenchyma ENVELOPE.
//   The repo already shows this: IFQ_WSI_FILL_INTERIOR_RINGS fills the
//   interior rings and inflates tissue area by 12.5% (75.06 -> 84.47 mm2,
//   lines 153-157) because those rings ARE the airspaces. So the Stage 1 mask
//   is the right ROI and the wrong mask. This script reproduces the Stage 1
//   ROI recipe verbatim (same env names, same defaults, same Otsu/close/open
//   /removeFragments order) and then RE-SEGMENTS septum vs airspace inside it
//   at the fine resolution.
//
// USAGE (headless)
//   IFQ_MORPH_INPUT=D:\Confocal_Images\...            (a .vsi file or a folder)
//   IFQ_MORPH_OUTPUT=D:\morphometry_out
//   IFQ_MORPH_MODE=fluor|brightfield
//   IFQ_MORPH_CHANNELS=0,3                            (REQUIRED in fluor mode)
//   IFQ_MORPH_TISSUE_THRESHOLD=<number>|otsu          (REQUIRED)
//   "X:\QuPath\QuPath-0.7.0 (console).exe" script qupath_lung_morphometry.groovy
//
//   IFQ_MORPH_SELFTEST=true      run synthetic phantoms with KNOWN answers
//                                through the shipped functions and exit.
//   IFQ_MORPH_SWEEP=true         resolution-dependence sweep on one window.
//   IFQ_MORPH_CALIBRATE=true     print the local-airspace-fraction and tissue
//                                signal distributions over control parenchyma
//                                so the consolidation cutoff can be locked the
//                                same way the AGER cutoff was.
// ============================================================================

import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServers
import qupath.lib.analysis.images.ContourTracing
import qupath.lib.analysis.images.SimpleImage
import qupath.lib.analysis.images.SimpleImages
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.GeometryTools
import qupath.lib.io.GsonTools

import qupath.lib.color.ColorDeconvolutionStains
import qupath.lib.color.ColorDeconvolutionHelper

import ij.process.ByteProcessor
import ij.process.FloatProcessor
import ij.process.ImageProcessor
import ij.process.AutoThresholder
import ij.plugin.filter.RankFilters
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.EDM

import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS

import org.locationtech.jts.geom.Geometry
import java.awt.image.BandedSampleModel
import java.awt.image.DataBufferUShort

// ---------------------------------------------------------------------------
// Settings / fail-closed helpers  (same idiom as IF_Quant_Pipeline.groovy and
// qupath_wsi_tile_export.groovy)
// ---------------------------------------------------------------------------
def LOG_TAG = "[IFQ_MORPH]"
def logMsg  = { String m -> println LOG_TAG + " " + m }

def failRun = { String message, Throwable cause = null ->
  System.err.println("FATAL: " + message)
  println LOG_TAG + " FATAL: " + message
  if (cause != null) cause.printStackTrace()
  System.exit(1)
}
def envOr = { String name, String fallback ->
  def v = System.getenv(name)
  return (v == null || v.trim().isEmpty()) ? fallback : v.trim()
}
def envInt = { String name, int fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Integer.parseInt(raw) }
  catch (Exception e) { failRun(name + " must be an integer; found '" + raw + "'"); return fallback }
}
def envDouble = { String name, double fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Double.parseDouble(raw) }
  catch (Exception e) { failRun(name + " must be a number; found '" + raw + "'"); return fallback }
}
def envBool = { String name, boolean fallback ->
  String raw = envOr(name, fallback.toString()).toLowerCase()
  if (!(raw in ["true", "false"])) failRun(name + " must be true or false; found '" + raw + "'")
  return raw == "true"
}
def csvField = { v ->
  String s = (v == null) ? "" : v.toString()
  if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
    return "\"" + s.replace("\"", "\"\"") + "\""
  return s
}
def csvRow = { List vals -> vals.collect { csvField(it) }.join(",") }

// ===========================================================================
// PIXEL MATH.  @CompileStatic throughout: these loops run over ~10^8 px per
// slide and dynamic Groovy dispatch made the equivalent Stage 1 loops ~40 s.
//
// Every accumulator below is ADDITIVE by construction, which is what makes
// aggregate_to_mouse.py able to pool them without forking.
// ===========================================================================
@groovy.transform.CompileStatic
class Morph {

  // ---- constants verified empirically, not assumed --------------------
  // ImageJ's EDM returns the distance to the nearest BACKGROUND PIXEL, so a
  // foreground pixel touching background gets 1.0, where the continuous
  // distance to the phase boundary is 0.5. Measured on synthetic slabs
  // (probe_api3.groovy): mean(EDM) over a slab of thickness t px is exactly
  // t/4 + 0.5 for t = 4,6,8,12,20. Without this correction septal thickness is
  // inflated by 2 px -- 1.38 um at 0.69 um/px, ~46% of a 3 um septum.
  static final double EDM_PIXEL_OFFSET = 0.5d

  /** Per-pixel max over the selected channels. The panel-agnostic "is there
   *  anything here" signal. Which channels are selected is a SCIENTIFIC
   *  decision (see IFQ_MORPH_CHANNELS) and is never defaulted. */
  static float[] maxProject(short[][] chans, int n) {
    float[] out = new float[n]
    for (int c = 0; c < chans.length; c++) {
      short[] s = chans[c]
      for (int i = 0; i < n; i++) {
        float v = (float) (s[i] & 0xFFFF)
        if (v > out[i]) out[i] = v
      }
    }
    return out
  }

  /** Brightfield: summed optical density. In H&E/Masson/PSR the AIRSPACE IS
   *  THE WHITE BACKGROUND, so OD is a true tissue/air discriminator -- which
   *  no fluorescence marker panel provides. */
  static float[] odSum(int[] argb, int n, double maxR, double maxG, double maxB) {
    float[] out = new float[n]
    double[] lutR = ColorDeconvolutionHelper.makeODLUT(maxR)
    double[] lutG = ColorDeconvolutionHelper.makeODLUT(maxG)
    double[] lutB = ColorDeconvolutionHelper.makeODLUT(maxB)
    for (int i = 0; i < n; i++) {
      int v = argb[i]
      double od = lutR[(v >> 16) & 0xFF] + lutG[(v >> 8) & 0xFF] + lutB[v & 0xFF]
      out[i] = (float) od
    }
    return out
  }

  /** Otsu over the pixels selected by mask (255 = use). Returns RAW units. */
  static double otsuWithin(float[] f, byte[] mask) {
    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE
    for (int i = 0; i < f.length; i++) {
      if ((mask[i] & 0xFF) <= 127) continue
      float v = f[i]; if (v < lo) lo = v; if (v > hi) hi = v
    }
    if (hi <= lo) return (double) lo
    int[] hist = new int[256]
    double sc = 255.0d / (hi - lo)
    for (int i = 0; i < f.length; i++) {
      if ((mask[i] & 0xFF) <= 127) continue
      hist[(int) Math.round((f[i] - lo) * sc)]++
    }
    int bin = new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hist)
    return lo + bin / sc
  }

  static double percentileWithin(float[] f, byte[] mask, double q) {
    int n = 0
    for (int i = 0; i < f.length; i++) if ((mask[i] & 0xFF) > 127) n++
    if (n == 0) return Double.NaN
    float[] v = new float[n]
    int k = 0
    for (int i = 0; i < f.length; i++) if ((mask[i] & 0xFF) > 127) v[k++] = f[i]
    java.util.Arrays.sort(v)
    int idx = (int) Math.min((long) (n - 1), Math.max(0L, Math.round(q * (n - 1))))
    return (double) v[idx]
  }

  // -----------------------------------------------------------------------
  // CROFTON PERIMETER of the tissue/airspace interface.
  //
  // A naive 4-connected boundary-pixel count overestimates the perimeter of a
  // disk by 4/pi = +27%. Cauchy-Crofton with 4 test-line directions is
  //     P = (pi/2) * mean_theta( N(theta) * d(theta) )
  // where N is the number of boundary crossings along test lines of direction
  // theta and d is the spacing between those parallel lines. On a square
  // lattice of pitch delta the horizontal/vertical line families have
  // d = delta and the two diagonal families have d = delta/sqrt(2):
  //     P = (pi/8) * [ (N_h + N_v)*delta + (N_d1 + N_d2)*delta/sqrt(2) ]
  // Analytic check (in the self-test): exact for a disk, -5.2% for an
  // axis-aligned square, versus -21% for a 2-direction estimator and +27% for
  // the naive pixel-boundary count.
  //
  // ONLY pairs where BOTH pixels are inside the ROI are counted, so the ROI
  // outline (pleural surface / block edge) is not mistaken for alveolar
  // surface. A crossing is attributed to the region of its FIRST pixel, which
  // makes the count exactly additive over blocks with no double counting.
  // -----------------------------------------------------------------------
  static void croftonCrossings(byte[] tissue, byte[] roi, byte[] regionLab,
                               int w, int h, int cx0, int cy0, int cx1, int cy1,
                               double[][] acc) {
    // acc[region][0..3] = N_h, N_v, N_d1, N_d2
    for (int y = 0; y < h; y++) {
      int row = y * w
      for (int x = 0; x < w; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        if (x < cx0 || x >= cx1 || y < cy0 || y >= cy1) continue   // core only
        int r = regionLab[i] & 0xFF
        if (r == 0) continue
        boolean t0 = (tissue[i] & 0xFF) > 127
        if (x + 1 < w) {
          int j = i + 1
          if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][0] += 1.0d
        }
        if (y + 1 < h) {
          int j = i + w
          if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][1] += 1.0d
        }
        if (x + 1 < w && y + 1 < h) {
          int j = i + w + 1
          if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][2] += 1.0d
        }
        if (x + 1 < w && y - 1 >= 0) {
          int j = i - w + 1
          if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][3] += 1.0d
        }
      }
    }
  }

  /** Turn the 4 direction crossing counts into a perimeter in um. */
  static double croftonPerimeterUm(double[] n4, double pxUm) {
    return (Math.PI / 8.0d) * ((n4[0] + n4[1]) * pxUm + (n4[2] + n4[3]) * pxUm / Math.sqrt(2.0d))
  }

  // -----------------------------------------------------------------------
  // AIRSPACE CHORD SCAN (mean linear intercept).
  //
  // Walks parallel test lines in one of 4 directions and accumulates, per
  // region, the ADDITIVE primitives that MLI is built from. Nothing here is a
  // ratio.
  //   acc[r][0] sum of UNTRUNCATED airspace chord lengths (um)
  //   acc[r][1] number of UNTRUNCATED airspace chords
  //   acc[r][2] total test-line length inside the ROI (um)
  //   acc[r][3] number of air<->tissue transitions inside the ROI
  //   acc[r][4] number of TRUNCATED chords (rejected from the direct estimate)
  //   acc[r][5] summed length of the truncated chords (um) -- lets a reader
  //             bound the Partial Chord Bias instead of trusting that it is
  //             small (Madi et al. 2025 Physiol Meas: the indirect method
  //             overestimates MLI through Septa Bias and Partial Chord Bias)
  //
  // A chord is UNTRUNCATED only if the pixel immediately before it AND the
  // pixel immediately after it are tissue inside the ROI. A chord clipped by
  // the pleural surface, by a block edge, or by the ROI boundary is truncated
  // and is excluded from the direct estimate -- that is the whole point.
  //
  // Attribution: every chord belongs to the region of its FIRST pixel and is
  // counted only if that pixel is inside the block CORE. Test-line length and
  // transitions are attributed per pixel / per leading pixel. So running over
  // overlapping blocks with a halo double-counts nothing.
  // -----------------------------------------------------------------------
  static void chordScan(byte[] tissue, byte[] roi, byte[] regionLab,
                        int w, int h, int cx0, int cy0, int cx1, int cy1,
                        int dx, int dy, double pxUm, double[][] acc) {
    double step = (dx != 0 && dy != 0) ? pxUm * Math.sqrt(2.0d) : pxUm
    // Enumerate the starting points of every line in this direction.
    int nStarts = 0
    int[] sx = new int[w + h]
    int[] sy = new int[w + h]
    if (dx == 1 && dy == 0) {
      for (int y = 0; y < h; y++) { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ }
    } else if (dx == 0 && dy == 1) {
      for (int x = 0; x < w; x++) { sx[nStarts] = x; sy[nStarts] = 0; nStarts++ }
    } else if (dx == 1 && dy == 1) {
      for (int y = h - 1; y >= 0; y--) { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ }
      for (int x = 1; x < w; x++)      { sx[nStarts] = x; sy[nStarts] = 0; nStarts++ }
    } else { // dx == 1, dy == -1
      for (int y = 0; y < h; y++)      { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ }
      for (int x = 1; x < w; x++)      { sx[nStarts] = x; sy[nStarts] = h - 1; nStarts++ }
    }

    for (int s = 0; s < nStarts; s++) {
      int x = sx[s], y = sy[s]
      boolean runOpen = false          // currently inside an airspace run
      double runLen = 0.0d
      int runRegion = 0
      boolean runInCore = false
      boolean runStartClean = false    // preceded by tissue-in-ROI
      int prevState = -1               // -1 outside ROI, 0 air, 1 tissue
      while (x >= 0 && x < w && y >= 0 && y < h) {
        int i = y * w + x
        boolean inRoi = (roi[i] & 0xFF) > 127
        int state = -1
        if (inRoi) state = ((tissue[i] & 0xFF) > 127) ? 1 : 0
        boolean inCore = (x >= cx0 && x < cx1 && y >= cy0 && y < cy1)
        int r = regionLab[i] & 0xFF

        if (inRoi && r > 0) {
          if (inCore) acc[r][2] += step                       // test-line length
          if (prevState >= 0 && state >= 0 && state != prevState) {
            // transition; attribute to the LEADING pixel's core membership
            if (inCore) acc[r][3] += 1.0d
          }
        }

        if (state == 0) {                                     // airspace
          if (!runOpen) {
            runOpen = true; runLen = 0.0d
            runRegion = r; runInCore = inCore
            runStartClean = (prevState == 1)
          }
          runLen += step
        } else {                                              // tissue or outside ROI
          if (runOpen) {
            boolean clean = runStartClean && (state == 1)
            if (runInCore && runRegion > 0) {
              if (clean) { acc[runRegion][0] += runLen; acc[runRegion][1] += 1.0d }
              else       { acc[runRegion][5] += runLen; acc[runRegion][4] += 1.0d }
            }
            runOpen = false
          }
        }
        prevState = state
        x += dx; y += dy
      }
      if (runOpen) {   // ran off the edge of the block -> truncated by construction
        if (runInCore && runRegion > 0) { acc[runRegion][5] += runLen; acc[runRegion][4] += 1.0d }
      }
    }
  }

  // -----------------------------------------------------------------------
  // SEPTAL THICKNESS by Euclidean distance transform.
  //
  // For an infinite slab of thickness t the distance-to-boundary averaged over
  // the slab is t/4, so t = 4 * mean(EDM). Measured on synthetic slabs the
  // discrete EDM gives mean = t/4 + EDM_PIXEL_OFFSET, hence
  //     t_hat = 4 * mean(EDM - EDM_PIXEL_OFFSET) * pxUm
  // Accumulates SUM (in um) and the tissue pixel COUNT separately so the mean
  // is recomputed from pooled numerator/denominator, never averaged.
  //
  // This is calibrated for SLAB-LIKE objects, which is what a septum is. On a
  // disk of radius R it returns 1.33R rather than 2R. That is stated, not
  // hidden: the companion estimator 2A/B has the same slab calibration, and
  // the two disagreeing is a QC signal that the "tissue" is blob-like
  // (consolidation, a vessel wall) rather than septal.
  // -----------------------------------------------------------------------
  static void edmAccum(byte[] tissue, byte[] roi, byte[] regionLab,
                       int w, int h, int cx0, int cy0, int cx1, int cy1,
                       double pxUm, double[][] acc) {
    // EDM background = airspace OR outside the ROI. Outside the ROI at a block
    // edge is handled by the halo: only core pixels are accumulated, and the
    // halo is far wider than any septal half-thickness.
    ByteProcessor bp = new ByteProcessor(w, h)
    byte[] p = (byte[]) bp.getPixels()
    for (int i = 0; i < p.length; i++)
      if (((roi[i] & 0xFF) > 127) && ((tissue[i] & 0xFF) > 127)) p[i] = (byte) 255
    // makeFloatEDM is an INSTANCE method in the ImageJ bundled with QuPath
    // 0.7.0 (ImageJ 1.54p99), not static -- verified by reflection.
    FloatProcessor fp = new EDM().makeFloatEDM((ImageProcessor) bp, 0, false)
    float[] d = (float[]) fp.getPixels()
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((p[i] & 0xFF) <= 127) continue
        int r = regionLab[i] & 0xFF
        if (r == 0) continue
        double v = d[i] - EDM_PIXEL_OFFSET
        if (v < 0.0d) v = 0.0d
        acc[r][0] += v * pxUm        // sum of half-thickness proxy, um
        acc[r][1] += 1.0d            // tissue pixel count
      }
    }
  }

  /**
   * MAX-downsample the fine tissue mask onto the coarse grid: a coarse pixel
   * becomes tissue if ANY fine pixel inside it is tissue. Used to keep thin
   * septa CONNECTED at the coarse resolution so airspace component labelling
   * does not flood the whole parenchyma. Core pixels only, so it is exactly
   * additive over blocks.
   */
  static void maxDownsampleTissue(byte[] tissue, byte[] roi, int w, int h,
                                  int cx0, int cy0, int cx1, int cy1,
                                  int ex, int ey, int k, int cw, int chh, byte[] outC) {
    for (int y = cy0; y < cy1; y++) {
      int gy = (ey + y).intdiv(k)
      if (gy < 0 || gy >= chh) continue
      int row = y * w, grow = gy * cw
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        if ((tissue[i] & 0xFF) <= 127) continue
        int gx = (ex + x).intdiv(k)
        if (gx < 0 || gx >= cw) continue
        outC[grow + gx] = (byte) 255
      }
    }
  }

  /**
   * FIX (performance, blocking): upsample the coarse ROI and region-label grids
   * onto a fine block by exact k x k replication.
   *
   * The draft did this with a bare Groovy loop in script scope using `(ey+y)/K`.
   * Groovy's `/` on two ints yields a **BigDecimal**, so that inner statement
   * allocated two BigDecimals per pixel over bw*bh ~ 6.5e6 pixels per block and
   * ~130 blocks per slide -- ~1.7e9 BigDecimal divisions per slide. Measured:
   * the draft's own smoke run managed 6 blocks. Moving the loop in here, static,
   * with intdiv, is the difference between "runs" and "does not run".
   */
  static void upsampleRoiLabels(byte[] roiC, byte[] labC, int cw, int chh,
                                int ex, int ey, int k, int bw, int bh,
                                byte[] roiB, byte[] labB) {
    for (int y = 0; y < bh; y++) {
      int gy = (ey + y).intdiv(k)
      if (gy < 0 || gy >= chh) continue
      int grow = gy * cw, brow = y * bw
      for (int x = 0; x < bw; x++) {
        int gx = (ex + x).intdiv(k)
        if (gx < 0 || gx >= cw) continue
        int gi = grow + gx
        roiB[brow + x] = roiC[gi]
        labB[brow + x] = labC[gi]
      }
    }
  }

  /** Count coarse-grid pixels of a 0/255 mask per region label. */
  static void coarseMaskAccum(byte[] mask, byte[] regionLab, int n, int nRegions, double[] acc) {
    for (int i = 0; i < n; i++) {
      int r = regionLab[i] & 0xFF
      if (r == 0 || r > nRegions) continue
      if ((mask[i] & 0xFF) > 127) acc[r] += 1.0d
    }
  }

  /** Count coarse-grid pixels per region label (the region areas themselves). */
  static void coarseLabelAccum(byte[] regionLab, int n, int nRegions, double[] acc) {
    for (int i = 0; i < n; i++) {
      int r = regionLab[i] & 0xFF
      if (r == 0 || r > nRegions) continue
      acc[r] += 1.0d
    }
  }

  /** Pearson correlation of two float images over the mask. Used to MEASURE
   *  (not assume) how independent a candidate architecture channel is from the
   *  AGER channel that defines the damage denominator. */
  static double[] corrWithin(float[] a, float[] b, byte[] mask, int n) {
    double sa = 0, sb = 0; long m = 0
    for (int i = 0; i < n; i++) {
      if ((mask[i] & 0xFF) <= 127) continue
      sa += a[i]; sb += b[i]; m++
    }
    if (m < 2) return [Double.NaN, (double) m] as double[]
    double ma = sa / m, mb = sb / m
    double saa = 0, sbb = 0, sab = 0
    for (int i = 0; i < n; i++) {
      if ((mask[i] & 0xFF) <= 127) continue
      double da = a[i] - ma, db = b[i] - mb
      saa += da * da; sbb += db * db; sab += da * db
    }
    double den = Math.sqrt(saa * sbb)
    return [(den > 0 ? sab / den : Double.NaN), (double) m] as double[]
  }

  /** Plain area counts per region, core only. acc[r][0]=roi px, [1]=tissue px, [2]=air px */
  static void areaAccum(byte[] tissue, byte[] roi, byte[] regionLab,
                        int w, int h, int cx0, int cy0, int cx1, int cy1, double[][] acc) {
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        int r = regionLab[i] & 0xFF
        if (r == 0) continue
        acc[r][0] += 1.0d
        if ((tissue[i] & 0xFF) > 127) acc[r][1] += 1.0d else acc[r][2] += 1.0d
      }
    }
  }

  // -----------------------------------------------------------------------
  // AIRSPACE CONNECTED COMPONENTS (whole slide, coarse pass).
  //
  // Alveolar destruction MERGES alveoli into confluent airspaces, so the
  // component size distribution is a destruction readout that needs no human
  // scoring. Returns, per region:
  //   [r][0] number of components, [r][1] total px, [r][2] px in components
  //   larger than bigPx.
  // A component is assigned WHOLE to the region holding the majority of its
  // pixels, so components never straddle and the counts stay additive.
  // Iterative flood fill with an explicit int stack -- no per-pixel label
  // array, so a 37 Mpx slide costs one byte[] not one int[].
  // -----------------------------------------------------------------------
  static void airspaceComponents(byte[] tissue, byte[] roi, byte[] regionLab,
                                 int w, int h, long bigPx, int nRegions, double[][] acc) {
    byte[] seen = new byte[w * h]
    int[] stack = new int[1 << 16]
    long[] perRegion = new long[nRegions + 1]
    for (int start = 0; start < w * h; start++) {
      if ((seen[start] & 0xFF) != 0) continue
      if ((roi[start] & 0xFF) <= 127) { seen[start] = (byte) 1; continue }
      if ((tissue[start] & 0xFF) > 127) { seen[start] = (byte) 1; continue }
      if ((regionLab[start] & 0xFF) == 0) { seen[start] = (byte) 1; continue }
      // flood
      for (int k = 0; k <= nRegions; k++) perRegion[k] = 0L
      int sp = 0
      stack[sp++] = start
      seen[start] = (byte) 1
      long size = 0L
      while (sp > 0) {
        int i = stack[--sp]
        size++
        perRegion[regionLab[i] & 0xFF]++
        int x = i % w, y = i.intdiv(w)
        // 4-connectivity for airspace (8-connectivity would leak through
        // diagonal gaps in a one-pixel-thin septum and merge every alveolus)
        if (x > 0)     { int j = i - 1; if ((seen[j] & 0xFF) == 0 && (roi[j] & 0xFF) > 127 && (tissue[j] & 0xFF) <= 127 && (regionLab[j] & 0xFF) != 0) { seen[j] = (byte) 1; if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2); stack[sp++] = j } }
        if (x < w - 1) { int j = i + 1; if ((seen[j] & 0xFF) == 0 && (roi[j] & 0xFF) > 127 && (tissue[j] & 0xFF) <= 127 && (regionLab[j] & 0xFF) != 0) { seen[j] = (byte) 1; if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2); stack[sp++] = j } }
        if (y > 0)     { int j = i - w; if ((seen[j] & 0xFF) == 0 && (roi[j] & 0xFF) > 127 && (tissue[j] & 0xFF) <= 127 && (regionLab[j] & 0xFF) != 0) { seen[j] = (byte) 1; if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2); stack[sp++] = j } }
        if (y < h - 1) { int j = i + w; if ((seen[j] & 0xFF) == 0 && (roi[j] & 0xFF) > 127 && (tissue[j] & 0xFF) <= 127 && (regionLab[j] & 0xFF) != 0) { seen[j] = (byte) 1; if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, stack.length * 2); stack[sp++] = j } }
      }
      int best = 0; long bestN = -1L
      for (int k = 1; k <= nRegions; k++) if (perRegion[k] > bestN) { bestN = perRegion[k]; best = k }
      if (best == 0) continue
      acc[best][0] += 1.0d
      acc[best][1] += (double) size
      if (size > bigPx) acc[best][2] += (double) size
    }
  }

  /**
   * BOX COUNTS for the fractal box dimension of the tissue phase (Andersen et
   * al. 2012 IJCOPD showed D_B correlates inversely with Lm, R = -0.95, in
   * elastase mice). Boxes are anchored at the SLIDE origin so they are
   * identical between slides and between regions.
   * Emitted as raw counts per epsilon; the slope is fitted downstream from
   * POOLED counts, because a slope is not summable.
   */
  static void boxCounts(byte[] tissue, byte[] roi, byte[] regionLab,
                        int w, int h, int[] epsList, int nRegions, double[][] acc) {
    for (int e = 0; e < epsList.length; e++) {
      int eps = epsList[e]
      // Groovy's '/' on ints yields BigDecimal even under @CompileStatic;
      // intdiv() keeps these hot indices in integer arithmetic.
      int nbx = (w + eps - 1).intdiv(eps), nby = (h + eps - 1).intdiv(eps)
      byte[] hit = new byte[nbx * nby * (nRegions + 1)]
      for (int y = 0; y < h; y++) {
        int row = y * w, by = y.intdiv(eps)
        for (int x = 0; x < w; x++) {
          int i = row + x
          if ((roi[i] & 0xFF) <= 127) continue
          if ((tissue[i] & 0xFF) <= 127) continue
          int r = regionLab[i] & 0xFF
          if (r == 0) continue
          hit[(r * nby + by) * nbx + x.intdiv(eps)] = (byte) 1
        }
      }
      for (int r = 1; r <= nRegions; r++) {
        long c = 0L
        int base = r * nby * nbx
        for (int k = 0; k < nbx * nby; k++) if (hit[base + k] != (byte) 0) c++
        acc[r][e] += (double) c
      }
    }
  }

  /** Local area fraction of a 0/1 mask = Gaussian blur of that mask. Same
   *  construction the Stage 1 damage detector uses for AGER coverage
   *  (qupath_wsi_tile_export.groovy lines 586-618), deliberately, so the two
   *  maps are comparable rather than merely correlated. */
  static float[] localFraction(byte[] mask, byte[] roi, int w, int h, double sigmaPx) {
    FloatProcessor fp = new FloatProcessor(w, h)
    float[] f = (float[]) fp.getPixels()
    for (int i = 0; i < f.length; i++)
      f[i] = (((roi[i] & 0xFF) > 127) && ((mask[i] & 0xFF) > 127)) ? 1f : 0f
    new GaussianBlur().blurGaussian(fp, sigmaPx)
    return (float[]) fp.getPixels()
  }
}

// ===========================================================================
// SELF-TEST -- synthetic phantoms with analytically known answers, pushed
// through the SHIPPED functions above. Run with IFQ_MORPH_SELFTEST=true.
// ===========================================================================
def selfTest = { ->
  println LOG_TAG + " ================ SELF-TEST (synthetic phantoms) ================"
  double pxUm = 1.0d
  int PASS = 0, FAIL = 0
  def check = { String what, double got, double want, double tolFrac ->
    boolean ok = Math.abs(got - want) <= Math.abs(want) * tolFrac
    if (ok) PASS++ else FAIL++
    println LOG_TAG + String.format("  %-58s got=%10.4f  want=%10.4f  %s",
        what, got, want, ok ? "PASS" : "FAIL (tol " + (tolFrac * 100) + "%)")
  }

  // ---- 1. Crofton perimeter on a disk and a square -----------------------
  [200, 400].each { int R ->
    int w = 2 * R + 40, h = 2 * R + 40
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    double cx = w / 2.0d, cy = h / 2.0d
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x
      roi[i] = (byte) 255; lab[i] = (byte) 1
      double dx = x + 0.5d - cx, dy = y + 0.5d - cy
      if (dx * dx + dy * dy <= (double) R * R) tis[i] = (byte) 255
    }
    double[][] acc = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, acc)
    double p = Morph.croftonPerimeterUm(acc[1], pxUm)
    check("Crofton perimeter, disk R=" + R + " px", p, 2 * Math.PI * R, 0.02d)
    double naive = (acc[1][0] + acc[1][1]) * pxUm
    println LOG_TAG + String.format("    (naive 4-connected boundary count would give %.1f = %+.1f%%)",
        naive, 100.0 * (naive / (2 * Math.PI * R) - 1.0))
  }
  [300].each { int a ->
    int w = a + 40, h = a + 40
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x; roi[i] = (byte) 255; lab[i] = (byte) 1
      if (x >= 20 && x < 20 + a && y >= 20 && y < 20 + a) tis[i] = (byte) 255
    }
    double[][] acc = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, acc)
    check("Crofton perimeter, axis-aligned square a=" + a, Morph.croftonPerimeterUm(acc[1], pxUm), 4.0d * a, 0.07d)
  }

  // ---- 2. Striped phantom: KNOWN MLI, airspace fraction, septal thickness --
  // Vertical stripes: septum of T px, airspace of G px, period P = T + G.
  // Horizontal test lines see airspace chords of exactly G px.
  //   true airspace fraction = G / P
  //   true direct MLI (horizontal lines only) = G
  //   true septal thickness = T
  //   true 2A/B: A = T per period per row, B = 2 boundaries per period -> T.
  [[4, 40], [6, 60], [3, 30], [8, 80]].each { List cfg ->
    int T = (int) cfg[0], G = (int) cfg[1]
    int P = T + G
    int w = P * 40, h = 600
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x; roi[i] = (byte) 255; lab[i] = (byte) 1
      if ((x % P) < T) tis[i] = (byte) 255
    }
    // areas
    double[][] aacc = new double[2][3]
    Morph.areaAccum(tis, roi, lab, w, h, 0, 0, w, h, aacc)
    check("stripes T=" + T + " G=" + G + ": airspace fraction",
        aacc[1][2] / aacc[1][0], (double) G / P, 0.005d)
    // chords, horizontal only (the analytic case)
    double[][] cacc = new double[2][6]
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, pxUm, cacc)
    check("stripes T=" + T + " G=" + G + ": direct MLI (h) um",
        cacc[1][0] / cacc[1][1], (double) G, 0.02d)
    check("stripes T=" + T + " G=" + G + ": indirect MLI 2L/N um",
        2.0d * cacc[1][2] / cacc[1][3], (double) P, 0.02d)
    // septal thickness, two independent estimators
    double[][] eacc = new double[2][2]
    Morph.edmAccum(tis, roi, lab, w, h, 0, 0, w, h, pxUm, eacc)
    check("stripes T=" + T + " G=" + G + ": septal thickness EDM um",
        4.0d * eacc[1][0] / eacc[1][1], (double) T, 0.15d)
    double[][] pacc = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, pacc)
    double per = Morph.croftonPerimeterUm(pacc[1], pxUm)
    check("stripes T=" + T + " G=" + G + ": septal thickness 2A/B um",
        2.0d * aacc[1][1] / per, (double) T, 0.30d)
  }

  // ---- 3. Circular-hole phantom: MLI of a hexagonal alveolar lattice ------
  // Random-ish packed circular airspaces. The mean chord of a disk of
  // diameter D under uniform parallel lines is pi*D/4; averaged over lines
  // that hit it the mean chord length is pi*D/4. This checks the chord code
  // on curved boundaries, where run-length quantisation bites hardest.
  [30, 60].each { int D ->
    int R = D / 2
    int pitch = D + 6
    int w = pitch * 30, h = pitch * 30
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    java.util.Arrays.fill(tis, (byte) 255)
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { int i = y * w + x; roi[i] = (byte) 255; lab[i] = (byte) 1 }
    for (int by = 0; by < 30; by++) for (int bx = 0; bx < 30; bx++) {
      double ccx = bx * pitch + pitch / 2.0d + ((by % 2 == 0) ? 0.0d : pitch / 2.0d)
      double ccy = by * pitch + pitch / 2.0d
      for (int y = (int) (ccy - R - 1); y <= (int) (ccy + R + 1); y++) {
        if (y < 0 || y >= h) continue
        for (int x = (int) (ccx - R - 1); x <= (int) (ccx + R + 1); x++) {
          if (x < 0 || x >= w) continue
          double dx = x + 0.5d - ccx, dy = y + 0.5d - ccy
          if (dx * dx + dy * dy <= (double) R * R) tis[y * w + x] = 0
        }
      }
    }
    double[][] cacc = new double[2][6]
    // 4 directions, the way the module runs it
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, pxUm, cacc)
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 0, 1, pxUm, cacc)
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 1, pxUm, cacc)
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, -1, pxUm, cacc)
    check("disks D=" + D + ": direct MLI vs pi*D/4",
        cacc[1][0] / cacc[1][1], Math.PI * D / 4.0d, 0.10d)
    println LOG_TAG + String.format("    truncated chords: %.0f of %.0f (%.2f%%)",
        cacc[1][4], cacc[1][1] + cacc[1][4], 100.0 * cacc[1][4] / (cacc[1][1] + cacc[1][4]))

    // airspace components: 900 disks of area pi R^2
    double[][] kacc = new double[2][3]
    Morph.airspaceComponents(tis, roi, lab, w, h, Long.MAX_VALUE, 1, kacc)
    check("disks D=" + D + ": airspace component count", kacc[1][0], 900.0d, 0.05d)
    check("disks D=" + D + ": mean component area px", kacc[1][1] / kacc[1][0], Math.PI * R * R, 0.05d)
  }

  // ---- 4. ADDITIVITY: block-wise == whole-image, exactly -----------------
  // The property the whole aggregation contract rests on.
  // (Named closure, not a bare block: a bare `{ ... }` after a `.each { }` is
  //  parsed by Groovy as a SECOND closure argument to that each.)
  def additivityTest = { ->
    int w = 900, h = 900
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    java.util.Random rnd = new java.util.Random(42L)
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x; roi[i] = (byte) 255; lab[i] = (byte) 1
      if (((x / 7) + (y / 11)) % 3 == 0 || rnd.nextInt(100) < 5) tis[i] = (byte) 255
    }
    double[][] whole = new double[2][6]
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, pxUm, whole)
    double[][] blocks = new double[2][6]
    for (int by = 0; by < 3; by++) for (int bx = 0; bx < 3; bx++)
      Morph.chordScan(tis, roi, lab, w, h, bx * 300, by * 300, bx * 300 + 300, by * 300 + 300, 1, 0, pxUm, blocks)
    check("additivity: chord length sum (9 cores vs whole)", blocks[1][0], whole[1][0], 1e-9d)
    check("additivity: chord count      (9 cores vs whole)", blocks[1][1], whole[1][1], 1e-9d)
    check("additivity: test-line length (9 cores vs whole)", blocks[1][2], whole[1][2], 1e-9d)
    double[][] pw = new double[2][4]; double[][] pb = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, pw)
    for (int by = 0; by < 3; by++) for (int bx = 0; bx < 3; bx++)
      Morph.croftonCrossings(tis, roi, lab, w, h, bx * 300, by * 300, bx * 300 + 300, by * 300 + 300, pb)
    check("additivity: Crofton crossings (9 cores vs whole)",
        pb[1][0] + pb[1][1] + pb[1][2] + pb[1][3], pw[1][0] + pw[1][1] + pw[1][2] + pw[1][3], 1e-9d)
    return true
  }
  additivityTest()

  println LOG_TAG + " ================ SELF-TEST: " + PASS + " passed, " + FAIL + " failed ================"
  if (FAIL > 0) failRun("self-test failed; the measurement code is wrong, do not run it on data")
  return true
}

// ===========================================================================
// INPUTS
// ===========================================================================
def SELFTEST   = envBool("IFQ_MORPH_SELFTEST", false)
if (SELFTEST) { selfTest(); logMsg("self-test only; exiting."); return }

def INPUT      = envOr("IFQ_MORPH_INPUT", "")
def OUTPUT     = envOr("IFQ_MORPH_OUTPUT", "")
def MODE       = envOr("IFQ_MORPH_MODE", "fluor").toLowerCase()
def SWEEP      = envBool("IFQ_MORPH_SWEEP", false)
def CALIBRATE  = envBool("IFQ_MORPH_CALIBRATE", false)

def DS_COARSE  = envDouble("IFQ_MORPH_DS_COARSE", 8.0d)
def DS_FINE    = envDouble("IFQ_MORPH_DS_FINE", 2.0d)
def BLOCK_CORE = envInt("IFQ_MORPH_BLOCK_CORE_PX", 2048)     // in FINE pixels
def BLOCK_HALO = envInt("IFQ_MORPH_BLOCK_HALO_PX", 256)      // in FINE pixels
def MAX_BLOCKS = envInt("IFQ_MORPH_MAX_BLOCKS", 0)           // 0 = no cap

// tissue signal
def CHANNELS_RAW = envOr("IFQ_MORPH_CHANNELS", "")
def THR_RAW      = envOr("IFQ_MORPH_TISSUE_THRESHOLD", "")
def SMOOTH_UM    = envDouble("IFQ_MORPH_SMOOTH_UM", 0.7d)    // pre-threshold blur
def OPEN_PX      = envDouble("IFQ_MORPH_OPEN_PX", 0.0d)      // speckle removal, fine pass
// ---- brightfield -----------------------------------------------------------
// UNTESTED PATH: no brightfield slide was available in this session. The code
// compiles and the API signatures were verified by reflection against QuPath
// 0.7.0, but no H&E/Masson/PSR image has been through it. Treat every number it
// produces as unvalidated until it has been run against a real slide.
//
// Why brightfield is the SCIENTIFICALLY CORRECT route for this module:
//   In H&E / Masson / PSR the ALVEOLAR AIRSPACE IS THE WHITE BACKGROUND, so
//   summed optical density is a true tissue-vs-air discriminator. A
//   fluorescence marker panel has no tissue counterstain -- DAPI marks nuclei,
//   not septa, and any structural marker (AGER, T1alpha) is exactly the thing
//   the morphometry is supposed to check independently. So the fluorescence
//   route measures "nucleated/marked territory" and the brightfield route
//   measures tissue.
//
// Why QuPath is the right host and the frozen Fiji engine is not:
//   IF_Quant_Pipeline.groovy assumes dark-background fluorescence throughout
//   (intensity nominates candidate pixels above a background). Brightfield
//   inverts that -- signal is ABSORBANCE, and the unmixing needs per-image
//   stain vectors. QuPath already carries ColorDeconvolutionStains /
//   StainVector / ColorDeconvolutionHelper, already reads brightfield WSI
//   formats through Bio-Formats and OpenSlide, and already hosts Stage 1, so
//   the ROI and tiling logic is shared. Nothing here asks the frozen engine to
//   change.
//
//   IFQ_MORPH_BF_WHITE           the white point, per channel (uncropped slide
//                                background). Get it from an empty corner.
//   IFQ_MORPH_BF_STAINS          H_E | H_DAB | H_E_DAB, or six/nine numbers
//                                r1,g1,b1,r2,g2,b2[,r3,g3,b3] for custom
//                                vectors (Masson and PSR need custom vectors;
//                                the H&E defaults are wrong for them).
//   IFQ_MORPH_BF_TISSUE_STAIN    od_sum (default) | 1 | 2 | 3  -- which signal
//                                drives the tissue mask.
//   IFQ_MORPH_BF_STAIN2_THRESHOLD  optional: OD threshold on stain 2 to give a
//                                collagen/eosin area column. For Masson or PSR
//                                that is the fibrosis readout, and it lands in
//                                the same _positive_area_um2 family as
//                                everything else, so aggregate_to_mouse.py
//                                pools it with no change.
def BF_MAX_RGB   = envOr("IFQ_MORPH_BF_WHITE", "255,255,255")
def BF_STAINS    = envOr("IFQ_MORPH_BF_STAINS", "H_E")
def BF_TIS_STAIN = envOr("IFQ_MORPH_BF_TISSUE_STAIN", "od_sum").toLowerCase()
def BF_S2_THR    = envOr("IFQ_MORPH_BF_STAIN2_THRESHOLD", "")

// ROI = the Stage 1 recipe, verbatim, same env names so the two cannot drift
def TISSUE_DS      = envDouble("IFQ_WSI_TISSUE_DOWNSAMPLE", 16.0d)
def TISSUE_BLUR    = envDouble("IFQ_WSI_TISSUE_BLUR_SIGMA", 2.0d)
def TISSUE_CLOSE_R = envDouble("IFQ_WSI_TISSUE_CLOSE_RADIUS", 4.0d)
def TISSUE_OPEN_R  = envDouble("IFQ_WSI_TISSUE_OPEN_RADIUS", 2.0d)
def MIN_FRAG_MM2   = envDouble("IFQ_WSI_MIN_FRAGMENT_MM2", 0.05d)

// consolidation map (the architectural analogue of the AGER damage map)
def CONS_SIGMA_UM  = envDouble("IFQ_MORPH_CONSOLIDATION_SIGMA_UM", 40.0d)
def CONS_CUTOFF    = envDouble("IFQ_MORPH_CONSOLIDATION_CUTOFF", -1.0d)  // <0 = disabled
def BIG_AIRSPACE_UM2 = envDouble("IFQ_MORPH_BIG_AIRSPACE_UM2", 10000.0d)

// AGER comparison (independent check on the endpoint denominator)
def CMP_AGER     = envBool("IFQ_MORPH_COMPARE_AGER", false)
def AGER_CH      = envInt("IFQ_WSI_AGER_CHANNEL", 2)
def AGER_THR     = envDouble("IFQ_WSI_AGER_THRESHOLD", 150.0d)
def DAMAGE_SIGMA = envDouble("IFQ_WSI_DAMAGE_SIGMA_UM", 40.0d)
def DAMAGE_CUT   = envDouble("IFQ_WSI_DAMAGE_CUTOFF", 0.14d)
def PARTITION    = envBool("IFQ_MORPH_PARTITION_DAMAGE", false)

// series selection, same guards as Stage 1
def MAX_PIXEL_UM = envDouble("IFQ_WSI_MAX_PIXEL_UM", 0.5d)
def EXPECT_NCH   = envInt("IFQ_WSI_EXPECT_CHANNELS", 4)

def PANEL        = envOr("IFQ_MORPH_PANEL", envOr("IFQ_WSI_PANEL", "LEFT"))
def ROI_NAME     = envOr("IFQ_MORPH_ROI_NAME", "parenchyma_all")
def ROI_DAMAGED  = envOr("IFQ_WSI_ROI_NAME_DAMAGED", "parenchyma_damaged")
def ROI_INTACT   = envOr("IFQ_WSI_ROI_NAME_INTACT",  "parenchyma_intact")
def SLIDE_META   = envOr("IFQ_MORPH_SLIDE_METADATA", envOr("IFQ_WSI_SLIDE_METADATA", ""))
def BOX_EPS      = envOr("IFQ_MORPH_BOX_EPS_PX", "1,2,4,8,16,32,64")

if (INPUT.isEmpty())  failRun("IFQ_MORPH_INPUT is required (a .vsi file or a folder of .vsi)")
if (!SWEEP && !CALIBRATE && OUTPUT.isEmpty()) failRun("IFQ_MORPH_OUTPUT is required")
if (!(MODE in ["fluor", "brightfield"])) failRun("IFQ_MORPH_MODE must be fluor or brightfield; found '" + MODE + "'")

double ratio = DS_COARSE / DS_FINE
int K = (int) Math.round(ratio)
if (Math.abs(ratio - K) > 1e-9d || K < 1)
  failRun("IFQ_MORPH_DS_COARSE / IFQ_MORPH_DS_FINE must be a positive integer; got " +
          DS_COARSE + " / " + DS_FINE + " = " + ratio + ". The fine grid has to be the " +
          "coarse grid subdivided exactly, or the two passes measure different territory.")

int[] CHANNELS
if (MODE == "fluor") {
  if (CHANNELS_RAW.isEmpty())
    failRun("IFQ_MORPH_CHANNELS is REQUIRED in fluor mode and has no default.\n" +
            "  There is no tissue counterstain in a marker panel, so 'which channels mean tissue'\n" +
            "  is a scientific decision that must be recorded, not guessed.\n" +
            "  For an INDEPENDENT check on the AGER-based damaged-area denominator the AGER\n" +
            "  channel (IFQ_WSI_AGER_CHANNEL=" + AGER_CH + ") and the KRT5 numerator channel MUST be\n" +
            "  excluded, e.g. panel LEFT -> IFQ_MORPH_CHANNELS=0 (DAPI only) or 0,3.")
  try { CHANNELS = CHANNELS_RAW.split(",").collect { Integer.parseInt(it.trim()) } as int[] }
  catch (Exception e) { failRun("IFQ_MORPH_CHANNELS must be comma-separated integers; found '" + CHANNELS_RAW + "'") }
} else {
  CHANNELS = [0] as int[]
}
if (THR_RAW.isEmpty() && !CALIBRATE)
  failRun("IFQ_MORPH_TISSUE_THRESHOLD is REQUIRED (a number, or the literal 'otsu').\n" +
          "  A per-slide adaptive threshold already INVERTED the damage endpoint once in this\n" +
          "  repo (qupath_wsi_tile_export.groovy lines 176-179). Run with IFQ_MORPH_CALIBRATE=true\n" +
          "  on the CONTROL slides to see the signal distribution inside parenchyma, lock a\n" +
          "  number, and pass it here. 'otsu' is allowed but is recorded as NOT LOCKED and the\n" +
          "  output is stamped morph_threshold_locked=false.")
boolean thrIsOtsu = THR_RAW.equalsIgnoreCase("otsu")
double TISSUE_THR = -1.0d
if (!thrIsOtsu && !THR_RAW.isEmpty()) {
  try { TISSUE_THR = Double.parseDouble(THR_RAW) }
  catch (Exception e) { failRun("IFQ_MORPH_TISSUE_THRESHOLD must be a number or 'otsu'; found '" + THR_RAW + "'") }
}
if (CMP_AGER && MODE != "fluor") failRun("IFQ_MORPH_COMPARE_AGER requires IFQ_MORPH_MODE=fluor")
// FIX: the draft only WARNED here. A warning in a 40-minute headless log is not a
// control. If the architecture signal contains the AGER channel then the
// "independent check on the AGER denominator" is circular by construction, and
// the run must not silently produce publishable-looking numbers. Fail closed;
// IFQ_MORPH_ALLOW_CIRCULAR=true is the deliberate, recorded escape hatch used to
// quantify the circularity itself.
def ALLOW_CIRC = envBool("IFQ_MORPH_ALLOW_CIRCULAR", false)
boolean circular = (CMP_AGER || PARTITION) && (CHANNELS as List).contains(AGER_CH)
if (circular && !ALLOW_CIRC)
  failRun("IFQ_MORPH_CHANNELS=" + CHANNELS_RAW + " includes the AGER channel " + AGER_CH +
          " while the AGER damage map is in use (COMPARE_AGER=" + CMP_AGER +
          ", PARTITION_DAMAGE=" + PARTITION + ").\n" +
          "  The architecture measure would then be built from the SAME signal it is\n" +
          "  supposed to check independently, and any agreement would be circular.\n" +
          "  Set IFQ_MORPH_ALLOW_CIRCULAR=true only to MEASURE that circularity; the\n" +
          "  output is stamped morph_independent_of_ager=false.")
if (circular)
  logMsg("*** IFQ_MORPH_ALLOW_CIRCULAR=true: architecture is built from the AGER channel. " +
         "This run is a CIRCULARITY CONTROL, not an independent check. ***")
// The 2x2 confusion table is the deliverable of COMPARE_AGER, and it is empty
// unless there is an architecture-defined consolidation map to compare against.
if (CMP_AGER && !(CONS_CUTOFF > 0))
  failRun("IFQ_MORPH_COMPARE_AGER=true needs IFQ_MORPH_CONSOLIDATION_CUTOFF > 0.\n" +
          "  Without it the architecture map is empty and the 2x2 area confusion table\n" +
          "  degenerates to 'nothing is consolidated', which would be reported as perfect\n" +
          "  specificity and zero sensitivity. Run IFQ_MORPH_CALIBRATE=true on the CONTROLS\n" +
          "  and lock the cutoff at their p1, the way the AGER cutoff 0.14 was locked.")
int[] EPS
try { EPS = BOX_EPS.split(",").collect { Integer.parseInt(it.trim()) } as int[] }
catch (Exception e) { failRun("IFQ_MORPH_BOX_EPS_PX must be comma-separated integers") }

// ---------------------------------------------------------------------------
// Slide list + metadata (identical rules to Stage 1: mouse_id must be real,
// because aggregate_to_mouse.py rejects NA/UNKNOWN and n = MICE)
// ---------------------------------------------------------------------------
def SLIDE_PATTERN = ~/(?i)^IFNg\s+KO\((het|hom)\)\s+([\d.]+)\s+(m[\w.-]+)\s+(pr8)\s+(no\s+infection|infection)\s*$/
def parseSlideName = { String stem ->
  def m = SLIDE_PATTERN.matcher(stem)
  if (!m.matches()) return null
  String geno = m.group(1).toLowerCase()
  String cond = m.group(5).toLowerCase().replaceAll(/\s+/, "_")
  return [ mouse_id: m.group(3),
           genotype: (geno == "het" ? "IFNg_KO_het" : "IFNg_KO_hom"),
           condition: (cond == "infection" ? "PR8" : "naive") ]
}
def readSlideMetadataCsv = { String p ->
  def out = [:]
  if (p.isEmpty()) return out
  def f = new File(p)
  if (!f.isFile()) failRun("IFQ_MORPH_SLIDE_METADATA not found: " + p)
  def lines = f.readLines().findAll { !it.trim().isEmpty() && !it.trim().startsWith("#") }
  def hdr = lines[0].split(",").collect { it.trim().toLowerCase() }
  ["vsi_filename", "mouse_id", "genotype", "condition"].each { req ->
    if (!hdr.contains(req)) failRun("IFQ_MORPH_SLIDE_METADATA must have a '" + req + "' column")
  }
  lines.drop(1).each { line ->
    def parts = line.split(",", -1).collect { it.trim() }
    def row = [:]; hdr.eachWithIndex { hn, i -> row[hn] = i < parts.size() ? parts[i] : "" }
    out[row.vsi_filename] = [mouse_id: row.mouse_id, genotype: row.genotype, condition: row.condition]
  }
  return out
}

def inFile = new File(INPUT)
def slides = []
if (inFile.isDirectory())
  slides = inFile.listFiles().findAll { it.isFile() && it.name.toLowerCase().endsWith(".vsi") }.sort { it.name }
else if (inFile.isFile()) slides = [inFile]
else failRun("IFQ_MORPH_INPUT does not exist: " + INPUT)
if (slides.isEmpty()) failRun("No .vsi found under " + INPUT)
def slideMeta = readSlideMetadataCsv(SLIDE_META)

// ---------------------------------------------------------------------------
def enumerateSeries = { String filePath ->
  def out = []
  def reader = new ImageReader()
  reader.setFlattenedResolutions(false)
  IMetadata meta = MetadataTools.createOMEXMLMetadata()
  reader.setMetadataStore(meta)
  try {
    reader.setId(filePath)
    for (int s = 0; s < reader.getSeriesCount(); s++) {
      reader.setSeries(s); reader.setResolution(0)
      def pxW = meta.getPixelsPhysicalSizeX(s)
      out << [series: s, name: meta.getImageName(s), width: reader.getSizeX(), height: reader.getSizeY(),
              nChannels: reader.getEffectiveSizeC(), nZ: reader.getSizeZ(),
              isThumbnail: reader.isThumbnailSeries(), rgb: reader.isRGB(),
              pxUm: pxW == null ? Double.NaN : pxW.value(UNITS.MICROMETER).doubleValue()]
    }
  } finally { try { reader.close() } catch (Exception ignore) {} }
  return out
}
def selectSeries = { List sl ->
  def rej = []
  def cands = sl.findAll { s ->
    if (s.isThumbnail) { rej << "${s.series}: thumbnail"; return false }
    if (MODE == "fluor" && s.nChannels != EXPECT_NCH) { rej << "${s.series}: C=${s.nChannels}"; return false }
    if (s.nZ != 1) { rej << "${s.series}: Z=${s.nZ}"; return false }
    if (Double.isNaN(s.pxUm) || !(s.pxUm > 0.0d)) { rej << "${s.series}: uncalibrated"; return false }
    if (s.pxUm > MAX_PIXEL_UM) { rej << "${s.series}: ${s.pxUm} um/px"; return false }
    return true
  }
  return [candidates: cands, rejected: rej]
}

/** Read one region and return per-channel raw ushort arrays (fluor) or ARGB (brightfield). */
def readChannels = { ImageServer server, double ds, int x, int y, int w, int h, int[] chans ->
  def img = server.readRegion(ds, x, y, w, h)
  int mw = img.getWidth(), mh = img.getHeight(), n = mw * mh
  if (MODE == "brightfield") {
    int[] argb = img.getRGB(0, 0, mw, mh, null, 0, mw)
    return [w: mw, h: mh, argb: argb, img: img]
  }
  def raster = img.getRaster()
  def sm = raster.getSampleModel(), db = raster.getDataBuffer()
  boolean fast = (db instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
                 raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0 &&
                 sm.getScanlineStride() == mw
  short[][] out = new short[chans.length][]
  chans.eachWithIndex { int c, int k ->
    if (fast && sm.getBankIndices()[c] == c && db.getOffsets()[c] == 0) {
      out[k] = ((DataBufferUShort) db).getData(c)
    } else {
      int[] tmp = new int[n]
      raster.getSamples(0, 0, mw, mh, c, tmp)
      short[] o = new short[n]
      for (int i = 0; i < n; i++) o[i] = (short) tmp[i]
      out[k] = o
    }
  }
  return [w: mw, h: mh, chans: out]
}

// Stain vectors are built ONCE per run, never estimated per image. Per-image
// stain estimation is the brightfield equivalent of a per-slide adaptive
// threshold: it makes each slide internally tidy and mutually incomparable,
// which is the failure mode this repo already hit with adaptive Otsu on AGER.
def bfStains = null
if (MODE == "brightfield") {
  def parts = BF_STAINS.split(",").collect { it.trim() }
  if (parts.size() == 1) {
    try {
      bfStains = ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(
          ColorDeconvolutionStains.DefaultColorDeconvolutionStains.valueOf(parts[0]))
    } catch (Exception e) {
      failRun("IFQ_MORPH_BF_STAINS must be H_E, H_DAB, H_E_DAB, or 6/9 comma-separated " +
              "stain-vector numbers; found '" + BF_STAINS + "'")
    }
  } else if (parts.size() == 6 || parts.size() == 9) {
    def v = parts.collect { Double.parseDouble(it) }
    def s1 = qupath.lib.color.StainVector.createStainVector("Stain 1", v[0], v[1], v[2])
    def s2 = qupath.lib.color.StainVector.createStainVector("Stain 2", v[3], v[4], v[5])
    def wh0 = BF_MAX_RGB.split(",").collect { Double.parseDouble(it.trim()) }
    if (parts.size() == 9) {
      def s3 = qupath.lib.color.StainVector.createStainVector("Stain 3", v[6], v[7], v[8])
      bfStains = new ColorDeconvolutionStains("custom", s1, s2, s3, wh0[0], wh0[1], wh0[2])
    } else {
      bfStains = new ColorDeconvolutionStains("custom", s1, s2, wh0[0], wh0[1], wh0[2])
    }
  } else {
    failRun("IFQ_MORPH_BF_STAINS must be a preset name or 6 or 9 numbers; found '" + BF_STAINS + "'")
  }
  logMsg("  brightfield stains: " + bfStains.toString())
  logMsg("  *** brightfield mode is UNTESTED in this draft: no H&E/Masson/PSR slide was " +
         "available. Validate against a real slide before using any number it produces. ***")
}

def deconvolvedChannel = { Map r, int idx ->
  // ColorDeconvolutionHelper.colorDeconvolve(BufferedImage, stains, channel, out)
  // -- signature verified by reflection against QuPath 0.7.0.
  return (float[]) ColorDeconvolutionHelper.colorDeconvolve(
      (java.awt.image.BufferedImage) r.img, bfStains, idx, null)
}

def signalOf = { Map r ->
  if (MODE == "brightfield") {
    if (BF_TIS_STAIN == "od_sum") {
      def wh = BF_MAX_RGB.split(",").collect { Double.parseDouble(it.trim()) }
      return Morph.odSum((int[]) r.argb, ((int[]) r.argb).length, wh[0], wh[1], wh[2])
    }
    int idx = Integer.parseInt(BF_TIS_STAIN) - 1
    return deconvolvedChannel(r, idx)
  }
  short[][] cs = (short[][]) r.chans
  return Morph.maxProject(cs, cs[0].length)
}

// ===========================================================================
// PER-SLIDE
// ===========================================================================
def outRoot = OUTPUT.isEmpty() ? null : new File(OUTPUT)
if (outRoot != null) outRoot.mkdirs()
def allRows = []
def qcRecords = []

slides.each { slideFile ->
  String stem = slideFile.name.replaceFirst(/\.[^.]+$/, "")
  logMsg("")
  logMsg("=================================================================")
  logMsg("SLIDE: " + slideFile.name)

  def md = slideMeta[slideFile.name] ?: slideMeta[stem] ?: parseSlideName(stem)
  if (!CALIBRATE && !SWEEP && (md == null || !md.mouse_id))
    failRun("Cannot determine mouse_id for '" + slideFile.name + "'. n = MICE; refusing to emit NA.")

  def sel = selectSeries(enumerateSeries(slideFile.getAbsolutePath()))
  if (sel.candidates.size() != 1)
    failRun("Expected exactly ONE quantifiable series in '" + slideFile.name + "'; found " +
            sel.candidates.size() + ". Rejections: " + sel.rejected.join("; "))
  def chosen = sel.candidates[0]
  ImageServer server = ImageServers.buildServer(slideFile.toURI(), "--series", "" + chosen.series)
  def cal = server.getPixelCalibration()
  double pxUm0 = cal.getPixelWidthMicrons()
  double pxUm0h = cal.getPixelHeightMicrons()
  int W = server.getWidth(), H = server.getHeight()
  double pxCoarse = pxUm0 * DS_COARSE
  double pxFine   = pxUm0 * DS_FINE
  logMsg(String.format("  series %d  %dx%d  %.4f um/px   coarse=%.3f um/px  fine=%.3f um/px",
      chosen.series, W, H, pxUm0, pxCoarse, pxFine))

  // -----------------------------------------------------------------------
  // RESOLUTION SWEEP -- runs every measure at several downsamples on ONE
  // window so the resolution dependence is measured, not asserted.
  // -----------------------------------------------------------------------
  if (SWEEP) {
    // find the densest 4096 full-res window using the coarse DAPI image
    def probe = readChannels(server, 32.0d, 0, 0, W, H, [0] as int[])
    float[] pf = signalOf(probe)
    int pw = (int) probe.w, ph = (int) probe.h
    byte[] all = new byte[pw * ph]; java.util.Arrays.fill(all, (byte) 255)
    double pthr = Morph.otsuWithin(pf, all)
    int winFull = envInt("IFQ_MORPH_SWEEP_WINDOW_PX", 4096)
    int stepC = Math.max(1, (int) (winFull / 32.0d))
    int bestX = 0, bestY = 0; long bestN = -1L
    for (int by = 0; by + stepC <= ph; by += Math.max(1, stepC / 2)) {
      for (int bx = 0; bx + stepC <= pw; bx += Math.max(1, stepC / 2)) {
        long c = 0L
        for (int y = by; y < by + stepC; y++) for (int x = bx; x < bx + stepC; x++)
          if (pf[y * pw + x] >= pthr) c++
        if (c > bestN) { bestN = c; bestX = bx; bestY = by }
      }
    }
    int wx = (int) (bestX * 32.0d), wy = (int) (bestY * 32.0d)
    logMsg(String.format("  SWEEP window %d px at (%d,%d) full-res; %.1f%% tissue at ds32",
        winFull, wx, wy, 100.0 * bestN / (stepC * (double) stepC)))
    logMsg(String.format("  %6s %9s %8s %10s %10s %10s %10s %10s %9s",
        "ds", "um/px", "npx", "airFrac", "MLIdir_um", "MLIind_um", "perim_mm", "thickEDM", "thick2A/B"))
    [1.0d, 2.0d, 4.0d, 8.0d, 16.0d].each { double ds ->
      def r = readChannels(server, ds, wx, wy, winFull, winFull, CHANNELS)
      float[] f = signalOf(r)
      int w = (int) r.w, h = (int) r.h
      double px = pxUm0 * ds
      if (SMOOTH_UM > 0) {
        def fp = new FloatProcessor(w, h, f, null)
        double sig = SMOOTH_UM / px
        if (sig > 0.3d) { new GaussianBlur().blurGaussian(fp, sig); f = (float[]) fp.getPixels() }
      }
      byte[] roi = new byte[w * h]; java.util.Arrays.fill(roi, (byte) 255)
      byte[] lab = new byte[w * h]; java.util.Arrays.fill(lab, (byte) 1)
      double thr = thrIsOtsu ? Morph.otsuWithin(f, roi) : TISSUE_THR
      byte[] tis = new byte[w * h]
      for (int i = 0; i < f.length; i++) if (f[i] >= thr) tis[i] = (byte) 255
      double[][] aacc = new double[2][3]; Morph.areaAccum(tis, roi, lab, w, h, 0, 0, w, h, aacc)
      double[][] cacc = new double[2][6]
      Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, px, cacc)
      Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 0, 1, px, cacc)
      Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 1, px, cacc)
      Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, -1, px, cacc)
      double[][] pacc = new double[2][4]; Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, pacc)
      double per = Morph.croftonPerimeterUm(pacc[1], px)
      double[][] eacc = new double[2][2]; Morph.edmAccum(tis, roi, lab, w, h, 0, 0, w, h, px, eacc)
      logMsg(String.format("  %6.1f %9.4f %8d %10.4f %10.2f %10.2f %10.3f %10.3f %9.3f",
          ds, px, w * h, aacc[1][2] / Math.max(1.0d, aacc[1][0]),
          cacc[1][0] / Math.max(1.0d, cacc[1][1]),
          2.0d * cacc[1][2] / Math.max(1.0d, cacc[1][3]),
          per / 1000.0d,
          4.0d * eacc[1][0] / Math.max(1.0d, eacc[1][1]),
          2.0d * aacc[1][1] * px * px / Math.max(1e-9d, per)))
    }
    server.close()
    return   // sweep is diagnostic only; emit no CSV
  }

  // -----------------------------------------------------------------------
  // COARSE PASS -- whole slide in one array.
  // ROI construction is the Stage 1 recipe verbatim (same env names, same
  // order: blur -> Otsu on channel 0 -> close -> open -> removeFragments).
  // -----------------------------------------------------------------------
  long t0 = System.currentTimeMillis()
  def rc = readChannels(server, TISSUE_DS, 0, 0, W, H, [0] as int[])
  int tw = (int) rc.w, th = (int) rc.h
  float[] tf
  if (MODE == "brightfield") tf = signalOf(rc)
  else {
    short[][] cs0 = (short[][]) rc.chans
    tf = Morph.maxProject(cs0, cs0[0].length)
  }
  def tfp = new FloatProcessor(tw, th, tf, null)
  new GaussianBlur().blurGaussian(tfp, TISSUE_BLUR)
  tf = (float[]) tfp.getPixels()
  byte[] allT = new byte[tw * th]; java.util.Arrays.fill(allT, (byte) 255)
  double roiThr = Morph.otsuWithin(tf, allT)
  def roibp = new ByteProcessor(tw, th)
  byte[] roip = (byte[]) roibp.getPixels()
  for (int i = 0; i < tf.length; i++) if (tf[i] >= roiThr) roip[i] = (byte) 255
  def rf = new RankFilters()
  rf.rank(roibp, TISSUE_CLOSE_R, RankFilters.MAX); rf.rank(roibp, TISSUE_CLOSE_R, RankFilters.MIN)
  rf.rank(roibp, TISSUE_OPEN_R,  RankFilters.MIN); rf.rank(roibp, TISSUE_OPEN_R,  RankFilters.MAX)
  // remove small fragments via the same geometry route Stage 1 uses
  def si = SimpleImages.createFloatImage(tw, th)
  for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++)
    si.setValue(x, y, ((roip[y * tw + x] & 0xFF) > 127) ? 1f : 0f)
  def req = RegionRequest.createInstance(server.getPath(), TISSUE_DS, 0, 0, W, H)
  Geometry gRoi = ContourTracing.createTracedGeometry(si, 0.5d, Double.POSITIVE_INFINITY, req)
  double pxAreaUm2 = pxUm0 * pxUm0h
  gRoi = GeometryTools.removeFragments(gRoi, (MIN_FRAG_MM2 * 1e6) / pxAreaUm2)
  gRoi = GeometryTools.constrainToBounds(gRoi, 0, 0, W, H)
  if (gRoi == null || gRoi.isEmpty()) failRun("Empty ROI geometry for " + slideFile.name)
  double roiGeomMm2 = gRoi.getArea() * pxAreaUm2 / 1e6
  logMsg(String.format("  ROI (Stage 1 recipe @ ds%.0f): Otsu=%.1f  area=%.3f mm2   (%d ms)",
      TISSUE_DS, roiThr, roiGeomMm2, System.currentTimeMillis() - t0))

  // Read the coarse plane FIRST so the coarse grid size comes from the raster
  // QuPath actually returns. ceil(W/ds) is NOT always that size -- the second
  // control slide differed by a pixel and threw "width*height!=pixels.length".
  def rcF = readChannels(server, DS_COARSE, 0, 0, W, H, MODE == "brightfield" ? ([0] as int[]) : CHANNELS)
  int cw = (int) rcF.w, ch = (int) rcF.h
  if (Math.abs(cw - W / DS_COARSE) > 1.5d || Math.abs(ch - H / DS_COARSE) > 1.5d)
    failRun("Coarse raster " + cw + "x" + ch + " is not W/H divided by " + DS_COARSE +
            " (" + (W / DS_COARSE) + "x" + (H / DS_COARSE) + "). The fine->coarse index " +
            "mapping assumes a plain decimation of the same grid.")

  // rasterise the ROI onto the COARSE grid
  def rasteriseRoi = { ->
    def bp2 = new ByteProcessor(cw, ch)
    def loc = org.locationtech.jts.geom.util.AffineTransformation
        .scaleInstance(1.0d / DS_COARSE, 1.0d / DS_COARSE).transform(gRoi)
    def r2 = GeometryTools.geometryToROI(loc, qupath.lib.regions.ImagePlane.getDefaultPlane())
    def ir = qupath.imagej.tools.IJTools.convertToIJRoi(r2, new ij.measure.Calibration(), 1.0d)
    bp2.setValue(255); bp2.fill(ir)
    return (byte[]) bp2.getPixels()
  }
  byte[] roiC = (byte[]) rasteriseRoi()
  long roiCpx = 0L
  for (int i = 0; i < roiC.length; i++) if ((roiC[i] & 0xFF) > 127) roiCpx++
  logMsg(String.format("  ROI rasterised at coarse grid: %d px = %.3f mm2 (geometry %.3f mm2, rel diff %.2e)",
      roiCpx, roiCpx * pxCoarse * pxCoarse / 1e6, roiGeomMm2,
      Math.abs(roiCpx * pxCoarse * pxCoarse / 1e6 - roiGeomMm2) / roiGeomMm2))

  // coarse tissue/airspace segmentation, and the AGER damage map
  float[] cf = signalOf(rcF)
  if (SMOOTH_UM > 0) {
    def fp = new FloatProcessor(cw, ch, cf, null)
    double sig = SMOOTH_UM / pxCoarse
    if (sig > 0.3d) { new GaussianBlur().blurGaussian(fp, sig); cf = (float[]) fp.getPixels() }
  }
  double thrC = thrIsOtsu ? Morph.otsuWithin(cf, roiC) : TISSUE_THR
  byte[] tisC = new byte[cw * ch]
  for (int i = 0; i < cf.length; i++) if (cf[i] >= thrC) tisC[i] = (byte) 255

  // CALIBRATE mode: report the distributions and stop.
  if (CALIBRATE) {
    logMsg("  CALIBRATION REPORT (control slides only -- do not look at infected slides here)")
    [0.01d, 0.05d, 0.10d, 0.25d, 0.50d, 0.75d, 0.90d, 0.95d, 0.99d, 0.999d].each { double q ->
      logMsg(String.format("    tissue signal p%-6s inside parenchyma ROI = %10.1f",
          (q * 100).toString(), Morph.percentileWithin(cf, roiC, q)))
    }
    logMsg(String.format("    Otsu inside ROI = %.1f", Morph.otsuWithin(cf, roiC)))
    // local airspace fraction distribution at the locked threshold, if one was given
    if (!thrIsOtsu && TISSUE_THR > 0) {
      byte[] airC = new byte[cw * ch]
      for (int i = 0; i < cf.length; i++)
        if (((roiC[i] & 0xFF) > 127) && ((tisC[i] & 0xFF) <= 127)) airC[i] = (byte) 255
      float[] laf = Morph.localFraction(airC, roiC, cw, ch, CONS_SIGMA_UM / pxCoarse)
      logMsg(String.format("    local airspace fraction (sigma %.0f um) percentiles inside parenchyma:", CONS_SIGMA_UM))
      [0.001d, 0.01d, 0.05d, 0.10d, 0.25d, 0.50d, 0.75d, 0.90d, 0.99d].each { double q ->
        logMsg(String.format("      p%-6s = %.4f", (q * 100).toString(), Morph.percentileWithin(laf, roiC, q)))
      }
      logMsg("    -> lock IFQ_MORPH_CONSOLIDATION_CUTOFF at the p1 value of the CONTROLS " +
             "for a 1% false-positive rate, exactly as the AGER cutoff 0.14 was locked.")
    }
    server.close()
    return
  }

  // region labels on the coarse grid: 1 = whole parenchyma, or 1 = damaged, 2 = intact
  int nRegions = PARTITION ? 2 : 1
  byte[] labC = new byte[cw * ch]
  byte[] agerDmgC = new byte[cw * ch]
  byte[] agerPosC = null
  byte[] indepPosC = null
  double agerIndepCorr = Double.NaN
  if (CMP_AGER || PARTITION) {
    def ra = readChannels(server, DS_COARSE, 0, 0, W, H, [AGER_CH] as int[])
    if ((int) ra.w != cw || (int) ra.h != ch)
      failRun("AGER coarse raster " + ra.w + "x" + ra.h + " != tissue coarse raster " + cw + "x" + ch)
    short[][] acs = (short[][]) ra.chans
    def afp = new FloatProcessor(cw, ch)
    float[] af = (float[]) afp.getPixels()
    for (int i = 0; i < af.length; i++) af[i] = (float) (acs[0][i] & 0xFFFF)
    new GaussianBlur().blurGaussian(afp, 1.0d)
    af = (float[]) afp.getPixels()
    agerPosC = new byte[cw * ch]
    for (int i = 0; i < af.length; i++)
      if (((roiC[i] & 0xFF) > 127) && af[i] >= AGER_THR) agerPosC[i] = (byte) 255
    float[] dens = Morph.localFraction(agerPosC, roiC, cw, ch, DAMAGE_SIGMA / pxCoarse)
    for (int i = 0; i < dens.length; i++)
      if (((roiC[i] & 0xFF) > 127) && dens[i] < DAMAGE_CUT) agerDmgC[i] = (byte) 255
    // ---- INDEPENDENCE, MEASURED RATHER THAN ASSERTED -------------------
    // The architecture channel and the AGER channel that defines the damage
    // denominator are correlated by construction if they mark the same cell
    // type. Report the pixel-wise Pearson r inside the parenchyma ROI on
    // EVERY run, so no reader can take "independent check" on trust.
    double[] cr = Morph.corrWithin(cf, af, roiC, cw * ch)
    agerIndepCorr = cr[0]
    logMsg(String.format("  INDEPENDENCE: pixelwise Pearson r(architecture signal, AGER) " +
        "inside parenchyma = %+.3f over %.3g px", agerIndepCorr, cr[1]))
    if (!Double.isNaN(agerIndepCorr) && Math.abs(agerIndepCorr) >= 0.7d)
      logMsg("  *** r >= 0.70: the architecture signal is NOT independent of AGER. Any " +
             "agreement between the architecture map and the AGER damage map is partly " +
             "circular and must be reported as such. ***")
    // the architecture channel's own positive mask, for context
    indepPosC = new byte[cw * ch]
    for (int i = 0; i < cf.length; i++)
      if (((roiC[i] & 0xFF) > 127) && ((tisC[i] & 0xFF) > 127)) indepPosC[i] = (byte) 255
  }
  for (int i = 0; i < labC.length; i++) {
    if ((roiC[i] & 0xFF) <= 127) continue
    labC[i] = PARTITION ? (((agerDmgC[i] & 0xFF) > 127) ? (byte) 1 : (byte) 2) : (byte) 1
  }

  // Optional stain-2 (collagen for Masson/PSR, eosin for H&E) area mask.
  // Coarse pass only: a collagen AREA FRACTION does not need 0.69 um/px, and
  // keeping it out of the fine pass keeps the block loop cheap.
  byte[] stain2C = null
  if (MODE == "brightfield" && !BF_S2_THR.isEmpty()) {
    double s2thr = Double.parseDouble(BF_S2_THR)
    float[] s2 = deconvolvedChannel(rcF, 1)
    stain2C = new byte[cw * ch]
    for (int i = 0; i < s2.length; i++)
      if (((roiC[i] & 0xFF) > 127) && s2[i] >= s2thr) stain2C[i] = (byte) 255
  }

  // consolidation map from ARCHITECTURE ALONE
  byte[] consC = new byte[cw * ch]
  if (CONS_CUTOFF > 0) {
    byte[] airC = new byte[cw * ch]
    for (int i = 0; i < cf.length; i++)
      if (((roiC[i] & 0xFF) > 127) && ((tisC[i] & 0xFF) <= 127)) airC[i] = (byte) 255
    float[] laf = Morph.localFraction(airC, roiC, cw, ch, CONS_SIGMA_UM / pxCoarse)
    for (int i = 0; i < laf.length; i++)
      if (((roiC[i] & 0xFF) > 127) && laf[i] < CONS_CUTOFF) consC[i] = (byte) 255
  }

  // Connected components and box counts are deferred until AFTER the fine
  // pass. MEASURED, not assumed: running them on the coarse tissue mask put
  // 99.5% of the airspace area into "confluent" components on a NORMAL control
  // lung, because at 2.76 um/px a 3 um septum is ~1 px and does not form a
  // continuous barrier, so every alveolus leaks into its neighbour and the
  // whole parenchyma floods as one component.
  // The fix is not a finer whole-slide array (that slide is 534 Mpx at
  // 0.69 um/px) but a MAX-downsample: a coarse pixel counts as tissue if ANY
  // fine pixel inside it was tissue. That is a one-pixel dilation of the
  // septal network, which slightly shrinks airspace area but PRESERVES ITS
  // TOPOLOGY -- and topology is the only thing component labelling needs.
  // The fine pass fills tisAnyC as it goes, so this costs one extra byte
  // array (cw*ch) and no extra I/O.
  byte[] tisAnyC = new byte[cw * ch]

  // 2x2 area confusion: architecture-consolidated vs AGER-damaged
  double[][] cmp = new double[nRegions + 1][5]   // dmg&cons, dmg&!cons, !dmg&cons, !dmg&!cons, agerDmg
  if (CMP_AGER) {
    for (int i = 0; i < roiC.length; i++) {
      if ((roiC[i] & 0xFF) <= 127) continue
      int r = labC[i] & 0xFF; if (r == 0) continue
      boolean d = (agerDmgC[i] & 0xFF) > 127
      boolean c = (consC[i] & 0xFF) > 127
      if (d && c) cmp[r][0] += 1.0d
      else if (d && !c) cmp[r][1] += 1.0d
      else if (!d && c) cmp[r][2] += 1.0d
      else cmp[r][3] += 1.0d
      if (d) cmp[r][4] += 1.0d
    }
  }
  logMsg(String.format("  coarse pass done (%d ms). tissue thr=%.1f (%s)",
      System.currentTimeMillis() - t0, thrC, thrIsOtsu ? "OTSU, NOT LOCKED" : "locked"))

  // -----------------------------------------------------------------------
  // FINE PASS -- blocks with a halo. Areas, Crofton perimeter, chords, EDM.
  // -----------------------------------------------------------------------
  double[][] areaAcc  = new double[nRegions + 1][3]
  double[][] perAcc   = new double[nRegions + 1][4]
  double[][] chordAcc = new double[nRegions + 1][6]
  double[][] chordH   = new double[nRegions + 1][6]
  double[][] chordV   = new double[nRegions + 1][6]
  double[][] edmAcc   = new double[nRegions + 1][2]

  int fw = (int) Math.ceil(W / DS_FINE), fh = (int) Math.ceil(H / DS_FINE)
  // snap the block grid to the coarse grid so fine->coarse index mapping is exact
  // intdiv, not '/': Groovy's '/' on ints yields BigDecimal, so (2050/4)*4 is
  // 2050, not 2048, and the block core would stop being a whole number of
  // coarse pixels -- which is what makes every coarse pixel belong to exactly
  // one block core.
  int coreF = BLOCK_CORE.intdiv(K) * K
  if (coreF <= 0) failRun("IFQ_MORPH_BLOCK_CORE_PX must be at least IFQ_MORPH_DS_COARSE/IFQ_MORPH_DS_FINE")
  int nBlocks = 0, nSkipped = 0
  boolean capped = false
  long tF = System.currentTimeMillis()
  for (int by = 0; by < fh && !capped; by += coreF) {
    for (int bx = 0; bx < fw; bx += coreF) {
      if (MAX_BLOCKS > 0 && nBlocks >= MAX_BLOCKS) { capped = true; break }
      int cwid = Math.min(coreF, fw - bx), chgt = Math.min(coreF, fh - by)
      // quick reject using the coarse ROI. intdiv, NOT '/': Groovy's '/' on two
      // ints returns BigDecimal, which is both slow and (for the upper bound)
      // wrong, because ceil() is what is wanted there and BigDecimal->int
      // truncates.
      long inRoi = 0L
      int qy1 = Math.min(ch, (by + chgt + K - 1).intdiv(K))
      int qx1 = Math.min(cw, (bx + cwid + K - 1).intdiv(K))
      for (int yy = by.intdiv(K); yy < qy1; yy++)
        for (int xx = bx.intdiv(K); xx < qx1; xx++)
          if ((roiC[yy * cw + xx] & 0xFF) > 127) inRoi++
      if (inRoi == 0L) { nSkipped++; continue }

      int ex = Math.max(0, bx - BLOCK_HALO), ey = Math.max(0, by - BLOCK_HALO)
      int ex2 = Math.min(fw, bx + cwid + BLOCK_HALO), ey2 = Math.min(fh, by + chgt + BLOCK_HALO)
      int ew = ex2 - ex, eh = ey2 - ey
      def rb = readChannels(server, DS_FINE, (int) (ex * DS_FINE), (int) (ey * DS_FINE),
                            (int) (ew * DS_FINE), (int) (eh * DS_FINE),
                            MODE == "brightfield" ? ([0] as int[]) : CHANNELS)
      int bw = (int) rb.w, bh = (int) rb.h
      if (Math.abs(bw - ew) > 1 || Math.abs(bh - eh) > 1)
        failRun("Fine block raster " + bw + "x" + bh + " differs from the requested " +
                ew + "x" + eh + " by more than a pixel; the core window would be misplaced.")
      float[] bf = signalOf(rb)
      if (SMOOTH_UM > 0) {
        def fp = new FloatProcessor(bw, bh, bf, null)
        double sig = SMOOTH_UM / pxFine
        if (sig > 0.3d) { new GaussianBlur().blurGaussian(fp, sig); bf = (float[]) fp.getPixels() }
      }
      byte[] tisB = new byte[bw * bh]
      for (int i = 0; i < bf.length; i++) if (bf[i] >= thrC) tisB[i] = (byte) 255
      if (OPEN_PX > 0) {
        def obp = new ByteProcessor(bw, bh, tisB, null)
        def rf2 = new RankFilters()
        rf2.rank(obp, OPEN_PX, RankFilters.MIN); rf2.rank(obp, OPEN_PX, RankFilters.MAX)
        tisB = (byte[]) obp.getPixels()
      }
      // ROI + region labels by exact k x k upsample of the coarse grid
      byte[] roiB = new byte[bw * bh]
      byte[] labB = new byte[bw * bh]
      Morph.upsampleRoiLabels(roiC, labC, cw, ch, ex, ey, K, bw, bh, roiB, labB)
      int lx0 = bx - ex, ly0 = by - ey
      int lx1 = Math.min(bw, lx0 + cwid), ly1 = Math.min(bh, ly0 + chgt)
      Morph.areaAccum(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, areaAcc)
      Morph.croftonCrossings(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, perAcc)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1,  0, pxFine, chordAcc)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 0,  1, pxFine, chordAcc)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1,  1, pxFine, chordAcc)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1, -1, pxFine, chordAcc)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1,  0, pxFine, chordH)
      Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 0,  1, pxFine, chordV)
      Morph.edmAccum(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, pxFine, edmAcc)
      Morph.maxDownsampleTissue(tisB, roiB, bw, bh, lx0, ly0, lx1, ly1, ex, ey, K, cw, ch, tisAnyC)
      nBlocks++
      if (nBlocks % 10 == 0) logMsg("    ... " + nBlocks + " blocks (" + (System.currentTimeMillis() - tF) + " ms)")
    }
  }
  logMsg("  fine pass: " + nBlocks + " blocks, " + nSkipped + " skipped (" +
         (System.currentTimeMillis() - tF) + " ms)" + (capped ? "  *** CAPPED ***" : ""))

  // ---- deferred topology measures, on the connectivity-preserving mask -----
  long tT = System.currentTimeMillis()
  double[][] compAcc = new double[nRegions + 1][3]
  Morph.airspaceComponents(tisAnyC, roiC, labC, cw, ch,
      (long) Math.round(BIG_AIRSPACE_UM2 / (pxCoarse * pxCoarse)), nRegions, compAcc)
  double[][] boxAcc = new double[nRegions + 1][EPS.length]
  Morph.boxCounts(tisAnyC, roiC, labC, cw, ch, EPS, nRegions, boxAcc)
  double compTot = 0.0d, compBig = 0.0d, compN = 0.0d
  for (int r = 1; r <= nRegions; r++) { compN += compAcc[r][0]; compTot += compAcc[r][1]; compBig += compAcc[r][2] }
  logMsg(String.format("  topology: %.0f airspace components, mean %.0f um2, %.1f%% of airspace " +
      "in components > %.0f um2  (%d ms)", compN,
      compN > 0 ? compTot * pxCoarse * pxCoarse / compN : 0.0d,
      compTot > 0 ? 100.0 * compBig / compTot : 0.0d, BIG_AIRSPACE_UM2,
      System.currentTimeMillis() - tT))
  if (capped) {
    // A capped run leaves most of the ROI unvisited, and unvisited ROI has no
    // tissue in tisAnyC, so it is all "airspace" and floods as one component.
    // The confluence check below cannot say anything under a cap; do not print
    // a diagnosis that is guaranteed to fire.
    logMsg("  *** CAPPED run: the fine pass visited only " + nBlocks + " block(s), so most of " +
           "the ROI has no tissue in the topology mask and floods as one component. The " +
           "component, confluence and fractal columns are UNINTERPRETABLE for this run. ***")
  } else if (compTot > 0 && compBig / compTot > 0.90d) {
    logMsg("  *** WARNING: >90% of airspace sits in 'confluent' components on a complete run. " +
           "The septal network is not a continuous barrier in this mask, so every alveolus " +
           "leaks into its neighbour and the confluence and fractal columns are meaningless " +
           "(the AREA measures -- airspace fraction, MLI, perimeter, thickness -- are NOT " +
           "affected; they never use connectivity).")
    logMsg("      Two different causes, and they need different fixes:")
    logMsg("      (a) RESOLUTION -- lower IFQ_MORPH_DS_FINE so a septum is >= 3 px across.")
    logMsg("      (b) STAINING -- more likely in fluor mode. A marker panel has no tissue")
    logMsg("          counterstain, so the anuclear, unlabelled stretches of septum are")
    logMsg("          genuinely absent from the mask and no resolution recovers them.")
    logMsg("          If (b), airspace topology is not measurable from this panel at all;")
    logMsg("          use IFQ_MORPH_MODE=brightfield on a serial H&E/Masson section. ***")
  }

  // -----------------------------------------------------------------------
  // EMIT.  One row per region. Every value below is an ADDITIVE PRIMITIVE.
  // -----------------------------------------------------------------------
  double coarseArea = pxCoarse * pxCoarse
  double fineArea   = pxFine * pxFine
  // Static per-region coarse-grid tallies (the draft did these as dynamic
  // Groovy loops over 37e6 px, once per region per mask).
  double[] regionPx = new double[nRegions + 1]
  Morph.coarseLabelAccum(labC, cw * ch, nRegions, regionPx)
  double[] consPxR = new double[nRegions + 1]
  if (CONS_CUTOFF > 0) Morph.coarseMaskAccum(consC, labC, cw * ch, nRegions, consPxR)
  double[] stain2PxR = new double[nRegions + 1]
  if (stain2C != null) Morph.coarseMaskAccum(stain2C, labC, cw * ch, nRegions, stain2PxR)
  double[] agerPosPxR = new double[nRegions + 1]
  double[] indepPosPxR = new double[nRegions + 1]
  if (agerPosC != null) Morph.coarseMaskAccum(agerPosC, labC, cw * ch, nRegions, agerPosPxR)
  if (indepPosC != null) Morph.coarseMaskAccum(indepPosC, labC, cw * ch, nRegions, indepPosPxR)

  (1..nRegions).each { int r ->
    String regionName = PARTITION ? (r == 1 ? ROI_DAMAGED : ROI_INTACT) : ROI_NAME
    // ---------------------------------------------------------------------
    // FIX (correctness, blocking for the whole point of this module):
    // MODULE_CONTRACT.md section 1.2 -- `region` is NOT in aggregate_to_mouse.py's
    // grouping key (KEY_COLS = mouse_id, genotype, condition, panel). The draft
    // emitted the damaged and the intact row with the SAME panel, so
    // aggregate_to_mouse.py would have SUMMED them back into one mouse row and
    // the damaged-vs-intact cross-check -- the entire reason this module exists
    // -- would have silently vanished at mouse level. Tested explicitly in
    // test_aggregation_contract.py::test_partition_survives_aggregation.
    // The denominator scope therefore goes in `panel`, per contract section 2.3.
    String scope = PARTITION ? (r == 1 ? "damaged" : "intact") : "parenchyma"
    def row = [:]
    // --- identity (aggregate_to_mouse.py KEY_COLS + ROW_ID_COLS) ---
    row["output_key"] = stem + "|" + scope          // contract 1.4/2.1: globally unique
    row["image"]      = stem
    row["region"]     = regionName
    row["section_id"] = stem
    row["mouse_id"]   = md.mouse_id
    row["genotype"]   = md.genotype
    row["condition"]  = md.condition
    row["panel"]      = PANEL + "@" + scope
    row["module_id"]  = "morphometry.alveolar_architecture"
    // --- universal denominator (SUM -> total_tissue_area_um2) ---
    // Taken from the COARSE ROI so it is exactly the territory both passes saw.
    row["region_area_um2"] = regionPx[r] * coarseArea
    // --- areas (SUM; fraction and mean component area recomputed by aggregate_to_mouse) ---
    row["morph_tissue_positive_area_um2"]   = areaAcc[r][1] * fineArea
    row["morph_airspace_positive_area_um2"] = areaAcc[r][2] * fineArea
    row["morph_finepass_positive_area_um2"] = areaAcc[r][0] * fineArea   // coverage QC
    row["morph_airspacec_positive_area_um2"] = compAcc[r][1] * coarseArea
    row["morph_airspacec_n_components"]      = compAcc[r][0]
    row["morph_airspacebig_positive_area_um2"] = compAcc[r][2] * coarseArea
    if (CONS_CUTOFF > 0) row["morph_consolidated_positive_area_um2"] = consPxR[r] * coarseArea
    // Marker-area context per compartment, coarse grid. These are NOT
    // architecture measures; they are here so a reader can see whether the
    // "damaged" compartment is antigen-poor (staining) or cell-dense (injury).
    if (agerPosC != null)  row["morph_agerpos_positive_area_um2"]  = agerPosPxR[r] * coarseArea
    if (indepPosC != null) row["morph_indeppos_positive_area_um2"] = indepPosPxR[r] * coarseArea
    if (stain2C != null) row["morph_bfstain2_positive_area_um2"] = stain2PxR[r] * coarseArea
    if (CMP_AGER) {
      row["morph_agerdmg_positive_area_um2"]      = cmp[r][4] * coarseArea
      row["morph_alvdmgcons_positive_area_um2"]   = cmp[r][0] * coarseArea
      row["morph_alvdmgonly_positive_area_um2"]   = cmp[r][1] * coarseArea
      row["morph_alvconsonly_positive_area_um2"]  = cmp[r][2] * coarseArea
      row["morph_alvneither_positive_area_um2"]   = cmp[r][3] * coarseArea
    }
    // --- lengths and counts (SUM via the class_*_count family) ---
    // NOTE on naming: MODULE_CONTRACT.md section 2.2 forbids a <Name> that ends
    // in "_um" (it is reserved for derived quantities). The draft emitted
    // class_morph_perimeter_um_count, class_morph_chordlen_um_count, ... which
    // violate that rule. They would still have aggregated CORRECTLY (they match
    // class_*_count and lengths are extensive, so summing is right), but the
    // names are non-conformant, so they are renamed here. Every length column
    // below is in MICROMETRES; the unit lives in the schema and in
    // morph_length_units, not in the column name.
    row["class_morph_perimlen_count"]      = Morph.croftonPerimeterUm(perAcc[r], pxFine)
    row["class_morph_chordlen_count"]      = chordAcc[r][0]
    row["class_morph_chordn_count"]        = chordAcc[r][1]
    row["class_morph_testline_count"]      = chordAcc[r][2]
    row["class_morph_transition_count"]    = chordAcc[r][3]
    row["class_morph_chordtrunc_count"]    = chordAcc[r][4]
    row["class_morph_chordtrunclen_count"] = chordAcc[r][5]
    row["class_morph_chordlenh_count"]     = chordH[r][0]
    row["class_morph_chordnh_count"]       = chordH[r][1]
    row["class_morph_chordlenv_count"]     = chordV[r][0]
    row["class_morph_chordnv_count"]       = chordV[r][1]
    row["class_morph_edmhalf_count"]       = edmAcc[r][0]
    row["class_morph_tissuepx_count"]      = edmAcc[r][1]
    EPS.eachWithIndex { int e, int k -> row["class_morph_box_eps" + e + "_count"] = boxAcc[r][k] }
    // provenance carried as summable 1-per-row flags so a mismatch is visible
    // at mouse level as a total that is not equal to n_regions
    row["class_morph_rows_count"] = 1.0d
    row["class_morph_pxfine_ok_count"] = (Math.abs(pxFine - envDouble("IFQ_MORPH_EXPECT_PXFINE_UM", pxFine)) < 1e-6d) ? 1.0d : 0.0d
    // --- QC / provenance (dropped by aggregate_to_mouse, kept for the slide CSV) ---
    row["morph_px_fine_um"]     = pxFine
    row["morph_px_coarse_um"]   = pxCoarse
    row["morph_tissue_threshold"] = thrC
    row["morph_threshold_locked"] = (!thrIsOtsu).toString()
    row["morph_channels"]       = (MODE == "brightfield") ? "OD_sum" : CHANNELS.join("|")
    row["morph_mode"]           = MODE
    row["morph_denominator_scope"]     = scope
    row["morph_independent_of_ager"]   = (!circular).toString()
    row["morph_ager_pearson_r"]        = agerIndepCorr
    row["morph_ager_threshold"]        = AGER_THR
    row["morph_damage_sigma_um"]       = DAMAGE_SIGMA
    row["morph_damage_cutoff"]         = DAMAGE_CUT
    row["morph_damage_detect_ds"]      = DS_COARSE
    row["morph_roi_geom_mm2"]   = roiGeomMm2
    row["morph_n_blocks"]       = nBlocks
    row["morph_coverage_complete"] = (!capped).toString()
    row["morph_box_eps_px"]     = EPS.join("|")
    row["morph_length_units"]   = "um"
    row["morph_cons_cutoff"]    = CONS_CUTOFF
    row["morph_cons_sigma_um"]  = CONS_SIGMA_UM
    allRows << row
  }
  qcRecords << [slide: stem, series: chosen.series, width: W, height: H, px_um: pxUm0,
                ds_fine: DS_FINE, ds_coarse: DS_COARSE, roi_geom_mm2: roiGeomMm2,
                tissue_threshold: thrC, threshold_locked: !thrIsOtsu,
                channels: CHANNELS as List, mode: MODE, n_blocks: nBlocks,
                coverage_complete: !capped, partitioned: PARTITION, compare_ager: CMP_AGER,
                consolidation_cutoff: CONS_CUTOFF, mouse_id: md?.mouse_id]
  server.close()
}

if (allRows.isEmpty()) { logMsg("no rows emitted (sweep/calibrate mode)"); return }
def cols = []
allRows.each { r -> r.keySet().each { if (!cols.contains(it)) cols << it } }
def sb = new StringBuilder(csvRow(cols)).append("\n")
allRows.each { r -> sb.append(csvRow(cols.collect { c -> r[c] })).append("\n") }
def outCsv = new File(outRoot, "morphometry_slide_summary.csv")
outCsv.setText(sb.toString(), "UTF-8")
new File(outRoot, "morphometry_manifest.json").setText(
    GsonTools.getInstance(true).toJson([schema_version: "0.1-draft", stage: "morphometry",
        generated_utc: java.time.Instant.now().toString(), slides: qcRecords]), "UTF-8")
logMsg("")
logMsg("Wrote " + allRows.size() + " row(s) -> " + outCsv.getAbsolutePath())
logMsg("NEXT:  python aggregate_to_mouse.py " + outCsv.getAbsolutePath() + " --outdir <stats>")
logMsg("THEN:  python morphometry_derive.py <stats>/mouse_level_summary.csv")
logMsg("MLI and every other ratio is produced by morphometry_derive.py from POOLED primitives.")
