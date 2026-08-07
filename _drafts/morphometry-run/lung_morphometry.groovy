// ============================================================================
// lung_morphometry.groovy -- lung ARCHITECTURE morphometry, QuPath host
// Rebuilt from _drafts/morphometry/qupath_lung_morphometry.groovy.
// ============================================================================
// WHAT IT IS FOR
//   An INDEPENDENT architectural check on the AGER-density "damaged alveolar
//   area" denominator. The locked detector (AGER thr 150, sigma 40 um, cutoff
//   0.14, at ds 8) calls some parenchyma damaged. If that territory is not also
//   architecturally distorted, the denominator is measuring STAINING, not
//   INJURY. This script measures architecture SEPARATELY inside the damaged and
//   intact compartments and reports the effect size.
//
// WHAT CHANGED FROM THE DRAFT (see REVIEW.md for the full list)
//   1. Compartments are carried in `panel` (= "<PANEL>@<scope>"), NOT in
//      `region`. aggregate_to_mouse.py pools ACROSS regions inside a mouse, so a
//      damaged row and an intact row with the same panel would be added
//      together and the comparison would vanish. panel is the only free
//      grouping key (MODULE_CONTRACT.md 2.3).
//   2. The analysis ROI is the DAMAGE DETECTOR's own tissue mask, reproduced
//      verbatim from scripts/measure_damage_locked.groovy (DAPI, blur 2,
//      whole-frame Otsu, close r=4 at ds 8), not the Stage 1 ds-16 recipe. The
//      cross-check has to be run on the endpoint's own denominator.
//   3. Chord directions are kept SEPARATE. Pooling all four into one
//      numerator/denominator pair over-weights the two diagonal families by
//      sqrt(2), because diagonal test lines are spaced delta/sqrt(2) apart and
//      therefore deliver sqrt(2) more test-line length per unit area. MLI is
//      now the equal-weight mean over the four orientations.
//   4. Distance-transform HISTOGRAMS (tissue phase and airspace phase) are
//      accumulated, so a pooled MEDIAN/p90 is available, not just 4*mean --
//      the mean is what blobs (vessels, consolidation) corrupt first.
//   5. Airspace-phase EDM added: 4*mean(airspace EDM) is a chord-free estimate
//      of airspace width. It agrees with MLI only if both are measuring what
//      they claim.
//   6. Every hot per-pixel loop is inside @CompileStatic. The draft's ROI
//      upsample and quick-reject loops were dynamic Groovy with BigDecimal
//      integer division (`(ey+y)/K`), ~13 M BigDecimal ops per block.
//   7. Blocks are processed in PARALLEL with per-block accumulators merged
//      under a lock. Bio-Formats read is the bottleneck (measured: 5.2 s for
//      2048x2048x4ch at ds 2).
//   8. Box-counting/fractal dimension REMOVED. It allocated w*h*(nRegions+1)
//      bytes at eps=1 (113 MB on this slide), the slope is a coastline quantity
//      that is pure resolution artefact at these pixel sizes, and nothing in
//      the cross-check needs it.
//
// THE HONEST LIMITATION, STATED UP FRONT
//   Panel LEFT is DAPI / KRT5-488 / AGER-555 / PDPN-647. There is NO tissue
//   counterstain. So the "tissue phase" this script segments is the NUCLEATED
//   phase (DAPI), and its complement is NOT alveolar airspace -- it is
//   airspace PLUS all anuclear septal wall and matrix. Every length here is
//   therefore an INTERNUCLEAR intercept, not an alveolar intercept, and is not
//   comparable to a published MLI. It is still a valid, AGER-independent
//   architectural descriptor, and that independence is the whole point.
//   IFQ_MORPH_CHANNELS has no default for exactly this reason.
//
// USAGE
//   IFQ_MORPH_INPUT      folder of .vsi or a single .vsi          (required)
//   IFQ_MORPH_OUTPUT     output folder                            (required unless probe mode)
//   IFQ_MORPH_CHANNELS   e.g. "0"  (DAPI only)                    (required)
//   IFQ_MORPH_TISSUE_THRESHOLD  number, or "otsu"                 (required)
//   IFQ_MORPH_DS_FINE    comma list, e.g. "2,4,8"   one CSV per value
//   IFQ_MORPH_SELFTEST=true   phantoms with analytic answers, then exit
//   IFQ_MORPH_CALIBRATE=true  signal distributions (run on CONTROLS only)
//   IFQ_MORPH_SWEEP=true      resolution sweep on fixed windows
// ============================================================================

import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServers
import qupath.lib.io.GsonTools

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

import java.awt.image.BandedSampleModel
import java.awt.image.DataBufferUShort
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ===========================================================================
// PIXEL MATH. Every accumulator is an ADDITIVE PRIMITIVE: pooling over blocks,
// slides and mice is exact summation, so no ratio is ever averaged.
// All methods are pure functions of their arguments => thread safe.
// ===========================================================================
@groovy.transform.CompileStatic
class Morph {

  // ImageJ's EDM gives a foreground pixel touching background the value 1,
  // where the continuous distance to the phase boundary is 0.5. For a slab of
  // thickness t px the discrete mean is exactly t/4 + 0.5 (analytic:
  // 2*(1+..+t/2)/t = t/4 + 1/2). Subtracting 0.5 restores t = 4*mean.
  static final double EDM_PIXEL_OFFSET = 0.5d

  static final int DIST_BINS = 24          // histogram of corrected distance
  static final double DIST_BIN_UM = 0.5d   // bin width, um; last bin overflows

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

  static double otsuWithin(float[] f, byte[] mask) {
    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE
    long n = 0L
    for (int i = 0; i < f.length; i++) {
      if (mask != null && (mask[i] & 0xFF) <= 127) continue
      float v = f[i]; if (v < lo) lo = v; if (v > hi) hi = v; n++
    }
    if (n == 0L || hi <= lo) return (double) lo
    int[] hist = new int[256]
    double sc = 255.0d / (hi - lo)
    for (int i = 0; i < f.length; i++) {
      if (mask != null && (mask[i] & 0xFF) <= 127) continue
      hist[(int) Math.round((f[i] - lo) * sc)]++
    }
    int bin = new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hist)
    return lo + bin / sc
  }

  static double percentileWithin(float[] f, byte[] mask, double q) {
    int n = 0
    for (int i = 0; i < f.length; i++) if (mask == null || (mask[i] & 0xFF) > 127) n++
    if (n == 0) return Double.NaN
    float[] v = new float[n]
    int k = 0
    for (int i = 0; i < f.length; i++) if (mask == null || (mask[i] & 0xFF) > 127) v[k++] = f[i]
    java.util.Arrays.sort(v)
    int idx = (int) Math.min((long) (n - 1), Math.max(0L, Math.round(q * (n - 1))))
    return (double) v[idx]
  }

  // -----------------------------------------------------------------------
  // CROFTON PERIMETER of the tissue/airspace interface.
  //   P = (pi/8) * [ (N_h + N_v)*delta + (N_d1 + N_d2)*delta/sqrt(2) ]
  // Verified analytically for a disk of radius R: N_h = N_v = 4R,
  // N_d1 = N_d2 = 4*sqrt(2)*R (diagonal line spacing is delta/sqrt(2)), so
  // P = (pi/8)(8R + 8R) = 2*pi*R exactly. A naive 4-connected boundary count
  // gives (N_h+N_v)*delta = 8R = +27%.
  // Only pairs where BOTH pixels are inside the ROI are counted, so the ROI
  // outline is never mistaken for alveolar surface. A crossing is attributed to
  // its FIRST pixel and only if that pixel is in the block core => exactly
  // additive over overlapping blocks.
  // -----------------------------------------------------------------------
  static void croftonCrossings(byte[] tissue, byte[] roi, byte[] regionLab,
                               int w, int h, int cx0, int cy0, int cx1, int cy1,
                               double[][] acc) {
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        int r = (int) (regionLab[i] & 0xFF)
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

  static double croftonPerimeterUm(double[] n4, double pxUm) {
    return (Math.PI / 8.0d) * ((n4[0] + n4[1]) * pxUm + (n4[2] + n4[3]) * pxUm / Math.sqrt(2.0d))
  }

  // -----------------------------------------------------------------------
  // AIRSPACE CHORD SCAN, ONE DIRECTION AT A TIME.
  //   acc[r][0] sum of UNTRUNCATED airspace chord lengths (um)
  //   acc[r][1] count of UNTRUNCATED airspace chords
  //   acc[r][2] test-line length inside the ROI (um)
  //   acc[r][3] air<->tissue transitions inside the ROI
  //   acc[r][4] count of TRUNCATED chords (excluded from the direct estimate)
  //   acc[r][5] summed length of the truncated chords (um)
  // A chord is UNTRUNCATED only if the pixel immediately before AND after it is
  // tissue inside the ROI. Chords clipped by the pleural surface, the block
  // edge or the ROI boundary are truncated and excluded -- that is what
  // controls Partial Chord Bias, and (4),(5) let a reader BOUND the residual
  // instead of assuming it away.
  // Attribution is by the FIRST pixel of the chord, counted only if that pixel
  // is in the block core, so overlapping blocks double-count nothing.
  // -----------------------------------------------------------------------
  static void chordScan(byte[] tissue, byte[] roi, byte[] regionLab,
                        int w, int h, int cx0, int cy0, int cx1, int cy1,
                        int dx, int dy, double pxUm, double[][] acc) {
    double step = (dx != 0 && dy != 0) ? pxUm * Math.sqrt(2.0d) : pxUm
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
      boolean runOpen = false
      double runLen = 0.0d
      int runRegion = 0
      boolean runInCore = false
      boolean runStartClean = false
      int prevState = -1               // -1 outside ROI, 0 air, 1 tissue
      while (x >= 0 && x < w && y >= 0 && y < h) {
        int i = y * w + x
        boolean inRoi = (roi[i] & 0xFF) > 127
        int state = -1
        if (inRoi) state = ((tissue[i] & 0xFF) > 127) ? 1 : 0
        boolean inCore = (x >= cx0 && x < cx1 && y >= cy0 && y < cy1)
        int r = (int) (regionLab[i] & 0xFF)

        if (inRoi && r > 0 && inCore) {
          acc[r][2] += step
          if (prevState >= 0 && state != prevState) acc[r][3] += 1.0d
        }

        if (state == 0) {
          if (!runOpen) {
            runOpen = true; runLen = 0.0d
            runRegion = r; runInCore = inCore
            runStartClean = (prevState == 1)
          }
          runLen += step
        } else {
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
      if (runOpen && runInCore && runRegion > 0) {
        acc[runRegion][5] += runLen; acc[runRegion][4] += 1.0d
      }
    }
  }

  // -----------------------------------------------------------------------
  // DISTANCE TRANSFORM of one phase.
  //   acc[r][0] sum of corrected distance (um)
  //   acc[r][1] pixel count
  //   acc[r][2 .. 2+DIST_BINS-1] histogram of corrected distance (um)
  // For a slab of thickness t, mean(distance) = t/4, so t = 4*mean. That
  // calibration is for SLAB-LIKE objects. On a disk of radius R it returns
  // 1.33R rather than 2R, which is why the histogram is carried too: a heavy
  // upper tail means the phase is blob-like (vessel, consolidation), and the
  // pooled median is the statistic that survives it.
  // Background = the other phase OR outside the ROI. Only CORE pixels are
  // accumulated; the halo is far wider than any plausible half-thickness.
  // -----------------------------------------------------------------------
  static void edmAccum(byte[] phase, byte[] roi, byte[] regionLab,
                       int w, int h, int cx0, int cy0, int cx1, int cy1,
                       double pxUm, double[][] acc, int base) {
    ByteProcessor bp = new ByteProcessor(w, h)
    byte[] p = (byte[]) bp.getPixels()
    for (int i = 0; i < p.length; i++)
      if (((roi[i] & 0xFF) > 127) && ((phase[i] & 0xFF) > 127)) p[i] = (byte) 255
    FloatProcessor fp = new EDM().makeFloatEDM((ImageProcessor) bp, 0, false)
    float[] d = (float[]) fp.getPixels()
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((p[i] & 0xFF) <= 127) continue
        int r = (int) (regionLab[i] & 0xFF)
        if (r == 0) continue
        double v = d[i] - EDM_PIXEL_OFFSET
        if (v < 0.0d) v = 0.0d
        double um = v * pxUm
        acc[r][base] += um
        acc[r][base + 1] += 1.0d
        int b = (int) (um / DIST_BIN_UM)
        if (b >= DIST_BINS) b = DIST_BINS - 1
        acc[r][base + 2 + b] += 1.0d
      }
    }
  }

  /** acc[r][0]=roi px, [1]=tissue px, [2]=air px. Core only. */
  static void areaAccum(byte[] tissue, byte[] roi, byte[] regionLab,
                        int w, int h, int cx0, int cy0, int cx1, int cy1, double[][] acc) {
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        int r = (int) (regionLab[i] & 0xFF)
        if (r == 0) continue
        acc[r][0] += 1.0d
        if ((tissue[i] & 0xFF) > 127) acc[r][1] += 1.0d else acc[r][2] += 1.0d
      }
    }
  }

  /** MAX-downsample the fine tissue mask onto the coarse grid (core only). */
  static void maxDownsampleTissue(byte[] tissue, byte[] roi, int w, int h,
                                  int cx0, int cy0, int cx1, int cy1,
                                  int gx0, int gy0, int k, int cw, int chh, byte[] outC) {
    for (int y = cy0; y < cy1; y++) {
      int gy = gy0 + Math.floorDiv(y, k)
      if (gy < 0 || gy >= chh) continue
      int row = y * w, grow = gy * cw
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        if ((tissue[i] & 0xFF) <= 127) continue
        int gx = gx0 + Math.floorDiv(x, k)
        if (gx < 0 || gx >= cw) continue
        outC[grow + gx] = (byte) 255
      }
    }
  }

  /** Nearest-neighbour upsample of the coarse ROI + label arrays onto the fine block grid. */
  static void upsampleLabels(byte[] roiC, byte[] labC, int cw, int chh,
                             int gx0, int gy0, int k, int w, int h,
                             byte[] roiB, byte[] labB) {
    for (int y = 0; y < h; y++) {
      int gy = gy0 + Math.floorDiv(y, k)
      if (gy < 0 || gy >= chh) continue
      int grow = gy * cw, row = y * w
      for (int x = 0; x < w; x++) {
        int gx = gx0 + Math.floorDiv(x, k)
        if (gx < 0 || gx >= cw) continue
        int gi = grow + gx
        roiB[row + x] = roiC[gi]
        labB[row + x] = labC[gi]
      }
    }
  }

  /** Does this coarse window contain any ROI pixel? Cheap block reject. */
  static boolean anyRoi(byte[] roiC, int cw, int chh, int gx0, int gy0, int gx1, int gy1) {
    int x0 = Math.max(0, gx0), y0 = Math.max(0, gy0)
    int x1 = Math.min(cw, gx1), y1 = Math.min(chh, gy1)
    for (int y = y0; y < y1; y++) {
      int row = y * cw
      for (int x = x0; x < x1; x++) if ((roiC[row + x] & 0xFF) > 127) return true
    }
    return false
  }

  static void threshold(float[] f, double thr, byte[] out) {
    for (int i = 0; i < f.length; i++) out[i] = (f[i] >= (float) thr) ? (byte) 255 : (byte) 0
  }

  /** Local area fraction of a 0/1 mask = Gaussian blur of that mask. Same
   *  construction the locked damage detector uses for AGER coverage. */
  static float[] localFraction(byte[] mask, int w, int h, double sigmaPx) {
    FloatProcessor fp = new FloatProcessor(w, h)
    float[] f = (float[]) fp.getPixels()
    for (int i = 0; i < f.length; i++) f[i] = ((mask[i] & 0xFF) > 127) ? 1f : 0f
    new GaussianBlur().blurGaussian(fp, sigmaPx)
    return (float[]) fp.getPixels()
  }

  // -----------------------------------------------------------------------
  // AIRSPACE CONNECTED COMPONENTS on the coarse grid.
  //   acc[r][0] n components, [1] total px, [2] px in components > bigPx
  // Whole components are assigned to the region holding the majority of their
  // pixels, so counts stay additive and no component straddles.
  // 4-connectivity: 8-connectivity leaks through diagonal gaps in a one-pixel
  // septum and merges every alveolus.
  // -----------------------------------------------------------------------
  static void airspaceComponents(byte[] tissue, byte[] roi, byte[] regionLab,
                                 int w, int h, long bigPx, int nRegions, double[][] acc) {
    byte[] seen = new byte[w * h]
    int[] stack = new int[1 << 16]
    long[] perRegion = new long[nRegions + 1]
    int n = w * h
    for (int start = 0; start < n; start++) {
      if ((seen[start] & 0xFF) != 0) continue
      if ((roi[start] & 0xFF) <= 127) { seen[start] = (byte) 1; continue }
      if ((tissue[start] & 0xFF) > 127) { seen[start] = (byte) 1; continue }
      if ((regionLab[start] & 0xFF) == 0) { seen[start] = (byte) 1; continue }
      for (int k = 0; k <= nRegions; k++) perRegion[k] = 0L
      int sp = 0
      stack[sp++] = start
      seen[start] = (byte) 1
      long size = 0L
      while (sp > 0) {
        int i = stack[--sp]
        size++
        perRegion[(int) (regionLab[i] & 0xFF)]++
        int x = i % w, y = Math.floorDiv(i, w)
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

  static void addInto(double[][] dst, double[][] src) {
    for (int r = 0; r < dst.length; r++)
      for (int c = 0; c < dst[r].length; c++) dst[r][c] += src[r][c]
  }
}

// ---------------------------------------------------------------------------
// env helpers
// ---------------------------------------------------------------------------
def LOG_TAG = "[IFQ_MORPH]"
def logMsg  = { String m -> println LOG_TAG + " " + m }
def failRun = { String message ->
  System.err.println("FATAL: " + message)
  println LOG_TAG + " FATAL: " + message
  System.exit(1)
}
def envOr = { String name, String fallback ->
  def v = System.getenv(name)
  return (v == null || v.trim().isEmpty()) ? fallback : v.trim()
}
def envInt = { String name, int fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Integer.parseInt(raw) } catch (Exception e) { failRun(name + " must be an integer; found '" + raw + "'"); return fallback }
}
def envDouble = { String name, double fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Double.parseDouble(raw) } catch (Exception e) { failRun(name + " must be a number; found '" + raw + "'"); return fallback }
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
// SELF-TEST -- phantoms with analytically known answers, pushed through the
// SHIPPED functions.
// ===========================================================================
def selfTest = { ->
  println LOG_TAG + " ============ SELF-TEST (synthetic phantoms) ============"
  double pxUm = 1.0d
  int PASS = 0, FAIL = 0
  def check = { String what, double got, double want, double tolFrac ->
    boolean ok = Math.abs(got - want) <= Math.abs(want) * tolFrac
    if (ok) PASS++ else FAIL++
    println LOG_TAG + String.format("  %-58s got=%10.4f want=%10.4f %s",
        what, got, want, ok ? "PASS" : "FAIL (tol " + (tolFrac * 100) + "%)")
  }

  // 1. Crofton perimeter, disk and square
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
    check("Crofton perimeter, disk R=" + R, p, 2 * Math.PI * R, 0.02d)
    double naive = (acc[1][0] + acc[1][1]) * pxUm
    println LOG_TAG + String.format("    (naive 4-connected boundary count = %.1f = %+.1f%%)",
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
    check("Crofton perimeter, axis-aligned square a=" + a,
        Morph.croftonPerimeterUm(acc[1], pxUm), 4.0d * a, 0.07d)
  }

  // 2. Striped phantom -- KNOWN MLI, airspace fraction, septal thickness
  [[4, 40], [6, 60], [3, 30], [8, 80]].each { List cfg ->
    int T = (int) cfg[0], G = (int) cfg[1]
    int P = T + G
    int w = P * 40, h = 600
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x; roi[i] = (byte) 255; lab[i] = (byte) 1
      if ((x % P) < T) tis[i] = (byte) 255
    }
    double[][] aacc = new double[2][3]
    Morph.areaAccum(tis, roi, lab, w, h, 0, 0, w, h, aacc)
    check("stripes T=" + T + " G=" + G + ": airspace fraction",
        aacc[1][2] / aacc[1][0], (double) G / P, 0.005d)
    double[][] cacc = new double[2][6]
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, pxUm, cacc)
    check("stripes T=" + T + " G=" + G + ": direct MLI (h) um", cacc[1][0] / cacc[1][1], (double) G, 0.02d)
    check("stripes T=" + T + " G=" + G + ": indirect MLI 2L/N um",
        2.0d * cacc[1][2] / cacc[1][3], (double) P, 0.02d)
    int NB = 2 + Morph.DIST_BINS
    double[][] eacc = new double[2][NB]
    Morph.edmAccum(tis, roi, lab, w, h, 0, 0, w, h, pxUm, eacc, 0)
    check("stripes T=" + T + " G=" + G + ": septal thickness 4*mean(EDM) um",
        4.0d * eacc[1][0] / eacc[1][1], (double) T, 0.15d)
    double[][] aeacc = new double[2][NB]
    byte[] air = new byte[w * h]
    for (int i = 0; i < air.length; i++) air[i] = ((tis[i] & 0xFF) > 127) ? (byte) 0 : (byte) 255
    Morph.edmAccum(air, roi, lab, w, h, 0, 0, w, h, pxUm, aeacc, 0)
    check("stripes T=" + T + " G=" + G + ": airspace width 4*mean(EDM) um",
        4.0d * aeacc[1][0] / aeacc[1][1], (double) G, 0.15d)
    double[][] pacc = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, pacc)
    double per = Morph.croftonPerimeterUm(pacc[1], pxUm)
    check("stripes T=" + T + " G=" + G + ": septal thickness 2A/B um",
        2.0d * aacc[1][1] / per, (double) T, 0.30d)
  }

  // 3. Circular-hole phantom -- mean chord of a disk under uniform lines = pi*D/4
  [30, 60].each { int D ->
    int R = D / 2
    int pitch = D + 6
    int w = pitch * 30, h = pitch * 30
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    java.util.Arrays.fill(tis, (byte) 255)
    java.util.Arrays.fill(roi, (byte) 255)
    java.util.Arrays.fill(lab, (byte) 1)
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
    // per-direction, equal orientation weight (the fix)
    double[][][] dacc = new double[4][2][6]
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1,  0, pxUm, dacc[0])
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1,  1, pxUm, dacc[1])
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 0,  1, pxUm, dacc[2])
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, -1, pxUm, dacc[3])
    double sm = 0.0d
    for (int d = 0; d < 4; d++) sm += dacc[d][1][0] / dacc[d][1][1]
    check("disks D=" + D + ": orientation-averaged MLI vs pi*D/4", sm / 4.0d, Math.PI * D / 4.0d, 0.10d)
    // the sqrt(2) weighting the draft had
    double lall = 0.0d, nall = 0.0d
    for (int d = 0; d < 4; d++) { lall += dacc[d][1][0]; nall += dacc[d][1][1] }
    println LOG_TAG + String.format("    pooled-across-directions MLI = %.3f (diagonals carry %.2fx the chords of the axes)",
        lall / nall, (dacc[1][1][1] + dacc[3][1][1]) / (dacc[0][1][1] + dacc[2][1][1]))
    double[][] kacc = new double[2][3]
    Morph.airspaceComponents(tis, roi, lab, w, h, Long.MAX_VALUE, 1, kacc)
    check("disks D=" + D + ": airspace component count", kacc[1][0], 900.0d, 0.05d)
    check("disks D=" + D + ": mean component area px", kacc[1][1] / kacc[1][0], Math.PI * R * R, 0.05d)
    // airspace EDM: for a disk of radius R the mean distance-to-boundary is R/3
    int NB = 2 + Morph.DIST_BINS
    double[][] aeacc = new double[2][NB]
    byte[] air = new byte[w * h]
    for (int i = 0; i < air.length; i++) air[i] = ((tis[i] & 0xFF) > 127) ? (byte) 0 : (byte) 255
    Morph.edmAccum(air, roi, lab, w, h, 0, 0, w, h, pxUm, aeacc, 0)
    check("disks D=" + D + ": mean airspace EDM vs R/3", aeacc[1][0] / aeacc[1][1], R / 3.0d, 0.12d)
  }

  // 4. ADDITIVITY: block cores == whole image, exactly. The property the whole
  //    aggregation contract rests on.
  def additivityTest = { ->
    int w = 900, h = 900
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    java.util.Random rnd = new java.util.Random(42L)
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      int i = y * w + x; roi[i] = (byte) 255
      lab[i] = (byte) ((x < 450) ? 1 : 2)          // two regions, to exercise labels
      if (((Math.floorDiv(x, 7)) + (Math.floorDiv(y, 11))) % 3 == 0 || rnd.nextInt(100) < 5) tis[i] = (byte) 255
    }
    double[][] whole = new double[3][6]
    Morph.chordScan(tis, roi, lab, w, h, 0, 0, w, h, 1, 0, pxUm, whole)
    double[][] blocks = new double[3][6]
    for (int by = 0; by < 3; by++) for (int bx = 0; bx < 3; bx++)
      Morph.chordScan(tis, roi, lab, w, h, bx * 300, by * 300, bx * 300 + 300, by * 300 + 300, 1, 0, pxUm, blocks)
    check("additivity: chord length sum (9 cores vs whole)", blocks[1][0] + blocks[2][0], whole[1][0] + whole[2][0], 1e-9d)
    check("additivity: chord count      (9 cores vs whole)", blocks[1][1] + blocks[2][1], whole[1][1] + whole[2][1], 1e-9d)
    check("additivity: test-line length (9 cores vs whole)", blocks[1][2] + blocks[2][2], whole[1][2] + whole[2][2], 1e-9d)
    check("additivity: per-region chord length, region 1",  blocks[1][0], whole[1][0], 1e-9d)
    double[][] pw = new double[3][4]; double[][] pb = new double[3][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, pw)
    for (int by = 0; by < 3; by++) for (int bx = 0; bx < 3; bx++)
      Morph.croftonCrossings(tis, roi, lab, w, h, bx * 300, by * 300, bx * 300 + 300, by * 300 + 300, pb)
    double sb = 0, sw = 0
    for (int r = 1; r <= 2; r++) for (int c = 0; c < 4; c++) { sb += pb[r][c]; sw += pw[r][c] }
    check("additivity: Crofton crossings (9 cores vs whole)", sb, sw, 1e-9d)
    int NB = 2 + Morph.DIST_BINS
    double[][] ew = new double[3][NB]; double[][] eb = new double[3][NB]
    Morph.edmAccum(tis, roi, lab, w, h, 0, 0, w, h, pxUm, ew, 0)
    for (int by = 0; by < 3; by++) for (int bx = 0; bx < 3; bx++)
      Morph.edmAccum(tis, roi, lab, w, h, bx * 300, by * 300, bx * 300 + 300, by * 300 + 300, pxUm, eb, 0)
    check("additivity: EDM distance sum  (9 cores vs whole)", eb[1][0] + eb[2][0], ew[1][0] + ew[2][0], 1e-9d)
    return true
  }
  additivityTest()

  // 5. Halo sufficiency: chord truncation with a finite halo must not change
  //    the UNTRUNCATED statistics of the core beyond the reported truncation.
  def haloTest = { ->
    int w = 1200, h = 200
    byte[] tis = new byte[w * h]; byte[] roi = new byte[w * h]; byte[] lab = new byte[w * h]
    java.util.Arrays.fill(roi, (byte) 255); java.util.Arrays.fill(lab, (byte) 1)
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if ((x % 44) < 4) tis[y * w + x] = (byte) 255
    // core 400..800, halo 100 -> read window 300..900
    double[][] full = new double[2][6]
    Morph.chordScan(tis, roi, lab, w, h, 400, 0, 800, h, 1, 0, 1.0d, full)
    int ew = 600
    byte[] t2 = new byte[ew * h]; byte[] r2 = new byte[ew * h]; byte[] l2 = new byte[ew * h]
    for (int y = 0; y < h; y++) for (int x = 0; x < ew; x++) {
      t2[y * ew + x] = tis[y * w + (300 + x)]; r2[y * ew + x] = (byte) 255; l2[y * ew + x] = (byte) 1
    }
    double[][] hal = new double[2][6]
    Morph.chordScan(t2, r2, l2, ew, h, 100, 0, 500, h, 1, 0, 1.0d, hal)
    check("halo: chord count in core matches full-image scan", hal[1][1], full[1][1], 1e-9d)
    check("halo: chord length in core matches full-image scan", hal[1][0], full[1][0], 1e-9d)
    return true
  }
  haloTest()

  println LOG_TAG + " ============ SELF-TEST: " + PASS + " passed, " + FAIL + " failed ============"
  if (FAIL > 0) failRun("self-test failed; the measurement code is wrong, do not run it on data")
  return true
}

// ===========================================================================
// INPUTS
// ===========================================================================
if (envBool("IFQ_MORPH_SELFTEST", false)) { selfTest(); logMsg("self-test only; exiting."); return }

def INPUT      = envOr("IFQ_MORPH_INPUT", "")
def OUTPUT     = envOr("IFQ_MORPH_OUTPUT", "")
def CALIBRATE  = envBool("IFQ_MORPH_CALIBRATE", false)
def SWEEP      = envBool("IFQ_MORPH_SWEEP", false)

double DS_COARSE = envDouble("IFQ_MORPH_DS_COARSE", 8.0d)     // must be the damage-detector ds
def DS_FINE_RAW  = envOr("IFQ_MORPH_DS_FINE", "2")
int CORE_FULL    = envInt("IFQ_MORPH_CORE_FULLRES_PX", 4096)
int HALO_FULL    = envInt("IFQ_MORPH_HALO_FULLRES_PX", 512)
int NTHREADS     = envInt("IFQ_MORPH_THREADS", 5)
int MAX_BLOCKS   = envInt("IFQ_MORPH_MAX_BLOCKS", 0)
// SYSTEMATIC UNIFORM SAMPLING of blocks. stride=1 measures every block that
// intersects the ROI. stride=k keeps the diagonal lattice (bx+by) % k == 0,
// which is uniform over the section rather than a contiguous corner, so the
// estimators stay unbiased -- this is the classical SURS field-sampling design
// (Hsia et al. 2010, ATS/ERS standards for quantitative assessment of lung
// structure). Every ratio uses the AREA ACTUALLY MEASURED as its denominator,
// and morph_measured_positive_area_um2 / region_area_um2 reports the sampling
// fraction, so a strided run is self-describing.
int STRIDE       = envInt("IFQ_MORPH_BLOCK_STRIDE", 1)

def CHANNELS_RAW = envOr("IFQ_MORPH_CHANNELS", "")
def THR_RAW      = envOr("IFQ_MORPH_TISSUE_THRESHOLD", "")
double SMOOTH_UM = envDouble("IFQ_MORPH_SMOOTH_UM", 0.0d)     // 0 = no pre-threshold blur

// LOCKED damage detector -- identical names AND identical defaults to
// scripts/measure_damage_locked.groovy.
int    AGER_CH      = envInt("IFQ_WSI_AGER_CHANNEL", 2)
double AGER_THR     = envDouble("IFQ_WSI_AGER_THRESHOLD", 150.0d)
double DAMAGE_SIGMA = envDouble("IFQ_WSI_DAMAGE_SIGMA_UM", 40.0d)
double DAMAGE_CUT   = envDouble("IFQ_WSI_DAMAGE_CUTOFF", 0.14d)
double CORE_ERODE_UM = envDouble("IFQ_MORPH_COMPARTMENT_ERODE_UM", 40.0d)

// consolidation / enlargement maps from ARCHITECTURE ALONE (AGER never used)
double CONS_SIGMA_UM = envDouble("IFQ_MORPH_CONSOLIDATION_SIGMA_UM", 40.0d)
double CONS_CUTOFF   = envDouble("IFQ_MORPH_CONSOLIDATION_CUTOFF", -1.0d)
double BIG_AIR_UM2   = envDouble("IFQ_MORPH_BIG_AIRSPACE_UM2", 10000.0d)

double MAX_PIXEL_UM = envDouble("IFQ_WSI_MAX_PIXEL_UM", 0.5d)
int    EXPECT_NCH   = envInt("IFQ_WSI_EXPECT_CHANNELS", 4)
def PANEL_KEY   = envOr("IFQ_MORPH_PANEL", "LEFT")
def MODULE_ID   = envOr("IFQ_MORPH_MODULE_ID", "morphometry.architecture")
def RUN_COMPONENTS = envBool("IFQ_MORPH_COMPONENTS", true)

if (INPUT.isEmpty()) failRun("IFQ_MORPH_INPUT is required")
if (!CALIBRATE && !SWEEP && OUTPUT.isEmpty()) failRun("IFQ_MORPH_OUTPUT is required")

double[] DS_FINE_LIST
try { DS_FINE_LIST = DS_FINE_RAW.split(",").collect { Double.parseDouble(it.trim()) } as double[] }
catch (Exception e) { failRun("IFQ_MORPH_DS_FINE must be comma-separated numbers; found '" + DS_FINE_RAW + "'") }
DS_FINE_LIST.each { double dsf ->
  double ratio = DS_COARSE / dsf
  if (Math.abs(ratio - Math.round(ratio)) > 1e-9d || ratio < 1)
    failRun("IFQ_MORPH_DS_COARSE / each IFQ_MORPH_DS_FINE must be a positive integer; got " + DS_COARSE + "/" + dsf)
  if (CORE_FULL % (int) DS_COARSE != 0 || CORE_FULL % (int) dsf != 0)
    failRun("IFQ_MORPH_CORE_FULLRES_PX must be a multiple of both downsamples")
  if (HALO_FULL % (int) DS_COARSE != 0 || HALO_FULL % (int) dsf != 0)
    failRun("IFQ_MORPH_HALO_FULLRES_PX must be a multiple of both downsamples")
}

int[] CHANNELS
if (CHANNELS_RAW.isEmpty())
  failRun("IFQ_MORPH_CHANNELS is REQUIRED and has no default.\n" +
          "  A marker panel has no tissue counterstain, so 'which channels mean tissue' is a\n" +
          "  scientific decision that must be recorded. For an INDEPENDENT check on the\n" +
          "  AGER-based damaged-area denominator the AGER channel (" + AGER_CH + ") MUST be excluded, and\n" +
          "  PDPN (3) is an AT1 marker like AGER so including it makes the check only\n" +
          "  channel-independent, not biology-independent. Panel LEFT: use \"0\" (DAPI).")
try { CHANNELS = CHANNELS_RAW.split(",").collect { Integer.parseInt(it.trim()) } as int[] }
catch (Exception e) { failRun("IFQ_MORPH_CHANNELS must be comma-separated integers") }
if ((CHANNELS as List).contains(AGER_CH))
  logMsg("*** WARNING: IFQ_MORPH_CHANNELS contains the AGER channel " + AGER_CH + ". The architecture " +
         "measure is then NOT independent of the denominator it is checking. Any agreement is circular. ***")

boolean thrIsOtsu = THR_RAW.equalsIgnoreCase("otsu")
double TISSUE_THR = -1.0d
if (THR_RAW.isEmpty() && !CALIBRATE && !SWEEP)
  failRun("IFQ_MORPH_TISSUE_THRESHOLD is REQUIRED (a number, or 'otsu').\n" +
          "  A per-slide adaptive threshold already INVERTED the damage endpoint once in this\n" +
          "  repo. Run IFQ_MORPH_CALIBRATE=true on the CONTROL slides, lock a number, pass it.")
if (!thrIsOtsu && !THR_RAW.isEmpty()) {
  try { TISSUE_THR = Double.parseDouble(THR_RAW) }
  catch (Exception e) { failRun("IFQ_MORPH_TISSUE_THRESHOLD must be a number or 'otsu'") }
}

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

def inFile = new File(INPUT)
def slides = []
if (inFile.isDirectory())
  slides = inFile.listFiles().findAll { it.isFile() && it.name.toLowerCase().endsWith(".vsi") }.sort { it.name }
else if (inFile.isFile()) slides = [inFile]
else failRun("IFQ_MORPH_INPUT does not exist: " + INPUT)
if (slides.isEmpty()) failRun("No .vsi found under " + INPUT)

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
      out << [series: s, width: reader.getSizeX(), height: reader.getSizeY(),
              nChannels: reader.getEffectiveSizeC(), nZ: reader.getSizeZ(),
              isThumbnail: reader.isThumbnailSeries(),
              pxUm: pxW == null ? Double.NaN : pxW.value(UNITS.MICROMETER).doubleValue()]
    }
  } finally { try { reader.close() } catch (Exception ignore) {} }
  return out
}
def selectSeries = { List sl ->
  def rej = []
  def cands = sl.findAll { s ->
    if (s.isThumbnail) { rej << "${s.series}: thumbnail"; return false }
    if (s.nChannels != EXPECT_NCH) { rej << "${s.series}: C=${s.nChannels}"; return false }
    if (s.nZ != 1) { rej << "${s.series}: Z=${s.nZ}"; return false }
    if (Double.isNaN(s.pxUm) || !(s.pxUm > 0.0d)) { rej << "${s.series}: uncalibrated"; return false }
    if (s.pxUm > MAX_PIXEL_UM) { rej << "${s.series}: ${s.pxUm} um/px"; return false }
    return true
  }
  return [candidates: cands, rejected: rej]
}

/** Read a region and return the requested channels as raw ushort arrays. */
def readChans = { ImageServer server, double ds, int x, int y, int w, int h, int[] chans ->
  def img = server.readRegion(ds, x, y, w, h)
  int mw = img.getWidth(), mh = img.getHeight(), n = mw * mh
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

// ===========================================================================
// PER-SLIDE
// ===========================================================================
def outRoot = OUTPUT.isEmpty() ? null : new File(OUTPUT)
if (outRoot != null) outRoot.mkdirs()
def rowsByDs = [:]          // ds -> list of rows
def qcRecords = []

// Scope order matches label values 1..4. These FOUR PARTITION the ROI and never
// overlap. The composite scopes a reader actually wants --
//   damaged    = damaged_edge + damaged_core
//   intact     = intact_edge  + intact_core
//   parenchyma = all four
// -- are synthesised by morphometry_derive.py by SUMMING the pooled primitives,
// which is exact because every carried column is additive. They are NOT emitted
// here, because emitting them would put overlapping rows in one panel group and
// aggregate_to_mouse.py would double-count the overlap.
def SCOPES = ["damaged_edge", "damaged_core", "intact_edge", "intact_core"]

slides.each { slideFile ->
  String stem = slideFile.name.replaceFirst(/\.[^.]+$/, "")
  logMsg("")
  logMsg("=========================================================")
  logMsg("SLIDE: " + slideFile.name)
  def md = parseSlideName(stem)
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
  int W = server.getWidth(), H = server.getHeight()
  double pxCoarse = pxUm0 * DS_COARSE
  logMsg(String.format("  series %d  %dx%d  %.5f um/px  coarse=%.4f um/px", chosen.series, W, H, pxUm0, pxCoarse))

  // -----------------------------------------------------------------------
  // COARSE PASS. The analysis ROI and the damaged/intact partition are the
  // LOCKED damage detector, reproduced verbatim from
  // scripts/measure_damage_locked.groovy so the cross-check is run on the
  // endpoint's own denominator and not on a lookalike.
  // -----------------------------------------------------------------------
  long t0 = System.currentTimeMillis()
  def rcAll = readChans(server, DS_COARSE, 0, 0, W, H, [0, AGER_CH] as int[])
  int cw = (int) rcAll.w, chh = (int) rcAll.h
  int cn = cw * chh
  logMsg(String.format("  coarse read %dx%d (%.1f Mpx) in %d ms", cw, chh, cn / 1e6, System.currentTimeMillis() - t0))
  short[][] cch = (short[][]) rcAll.chans

  def fpD = new FloatProcessor(cw, chh)
  float[] fd = (float[]) fpD.getPixels()
  for (int i = 0; i < cn; i++) fd[i] = (float) (cch[0][i] & 0xFFFF)
  new GaussianBlur().blurGaussian(fpD, 2.0d)
  fd = (float[]) fpD.getPixels()
  double tThr = Morph.otsuWithin(fd, null)
  def tbp = new ByteProcessor(cw, chh)
  byte[] roiC = (byte[]) tbp.getPixels()
  for (int i = 0; i < cn; i++) if (fd[i] >= (float) tThr) roiC[i] = (byte) 255
  def rf = new RankFilters()
  rf.rank(tbp, 4.0d, RankFilters.MAX); rf.rank(tbp, 4.0d, RankFilters.MIN)
  long roiPx = 0L
  for (int i = 0; i < cn; i++) if ((roiC[i] & 0xFF) > 127) roiPx++
  double coarseArea = pxCoarse * pxCoarse
  logMsg(String.format("  ROI (damage-detector tissue mask, DAPI Otsu=%.1f, close r=4): %.3f mm2 = %.2f%% of frame",
      tThr, roiPx * coarseArea / 1e6, 100.0 * roiPx / cn))

  def fpA = new FloatProcessor(cw, chh)
  float[] fa = (float[]) fpA.getPixels()
  for (int i = 0; i < cn; i++) fa[i] = (float) (cch[1][i] & 0xFFFF)
  new GaussianBlur().blurGaussian(fpA, 1.0d)
  fa = (float[]) fpA.getPixels()
  byte[] agerPos = new byte[cn]
  long aN = 0L
  for (int i = 0; i < cn; i++)
    if (((roiC[i] & 0xFF) > 127) && fa[i] >= (float) AGER_THR) { agerPos[i] = (byte) 255; aN++ }
  float[] dens = Morph.localFraction(agerPos, cw, chh, DAMAGE_SIGMA / pxCoarse)
  byte[] dmgC = new byte[cn]; byte[] intC = new byte[cn]
  long nDmg = 0L
  for (int i = 0; i < cn; i++) {
    if ((roiC[i] & 0xFF) <= 127) continue
    if (dens[i] < (float) DAMAGE_CUT) { dmgC[i] = (byte) 255; nDmg++ } else intC[i] = (byte) 255
  }
  logMsg(String.format("  LOCKED detector (AGER>=%.0f, sigma=%.0f um, cutoff=%.2f): AGER+=%.2f%% of tissue, DAMAGED=%.2f%% of tissue (%.4f mm2)",
      AGER_THR, DAMAGE_SIGMA, DAMAGE_CUT, 100.0 * aN / roiPx, 100.0 * nDmg / roiPx, nDmg * coarseArea / 1e6))

  // COMPARTMENT CORES.
  // The sigma-40 um smoothing that defines the compartments mixes them within
  // ~one sigma of their common boundary, so a core that excludes that zone is
  // the sensitivity analysis. The core must be defined by distance to the OTHER
  // COMPARTMENT, not by eroding the compartment itself: the tissue mask is a
  // lacy septal network, so plain erosion measures the width of the septa and
  // deletes 88-99% of BOTH compartments (measured) regardless of where the
  // damaged/intact boundary is. Dilating the opposite compartment and
  // subtracting keeps non-ROI territory neutral, which is what we want -- being
  // near the pleural surface is not being near intact tissue.
  double erodePx = CORE_ERODE_UM / pxCoarse
  def dilate = { byte[] m ->
    def bp = new ByteProcessor(cw, chh, (byte[]) m.clone(), null)
    new RankFilters().rank(bp, erodePx, RankFilters.MAX)
    return (byte[]) bp.getPixels()
  }
  byte[] dmgCore = new byte[cn]
  byte[] intCore = new byte[cn]
  if (erodePx >= 0.5d) {
    byte[] dInt = (byte[]) dilate(intC)
    byte[] dDmg = (byte[]) dilate(dmgC)
    for (int i = 0; i < cn; i++) {
      if (((dmgC[i] & 0xFF) > 127) && ((dInt[i] & 0xFF) <= 127)) dmgCore[i] = (byte) 255
      if (((intC[i] & 0xFF) > 127) && ((dDmg[i] & 0xFF) <= 127)) intCore[i] = (byte) 255
    }
  } else { dmgCore = (byte[]) dmgC.clone(); intCore = (byte[]) intC.clone() }

  // labels: 1 damaged-edge, 2 damaged-core, 3 intact-edge, 4 intact-core
  byte[] labC = new byte[cn]
  long[] scopePx = new long[5]
  for (int i = 0; i < cn; i++) {
    if ((roiC[i] & 0xFF) <= 127) continue
    int lab
    if ((dmgC[i] & 0xFF) > 127) lab = ((dmgCore[i] & 0xFF) > 127) ? 2 : 1
    else                        lab = ((intCore[i] & 0xFF) > 127) ? 4 : 3
    labC[i] = (byte) lab
    scopePx[lab]++
  }
  logMsg(String.format("  compartments (coarse px): dmg_edge=%d dmg_core=%d int_edge=%d int_core=%d  (erode %.1f um = %.2f px)",
      scopePx[1], scopePx[2], scopePx[3], scopePx[4], CORE_ERODE_UM, erodePx))

  if (CALIBRATE) {
    logMsg("  CALIBRATION -- run this on CONTROL slides only.")
    [0.01d, 0.05d, 0.10d, 0.25d, 0.50d, 0.75d, 0.90d, 0.95d, 0.99d].each { double q ->
      logMsg(String.format("    coarse tissue signal (ch %s) p%-5s inside ROI = %10.1f",
          CHANNELS.join("|"), (q * 100).toString(), Morph.percentileWithin(fd, roiC, q)))
    }
    // fine-resolution distribution on a systematic sample of blocks
    DS_FINE_LIST.each { double dsFine ->
      int K = (int) Math.round(DS_COARSE / dsFine)
      def sums = []
      int taken = 0
      for (int by = 0; by < H && taken < 8; by += CORE_FULL * 3) {
        for (int bx = 0; bx < W && taken < 8; bx += CORE_FULL * 3) {
          int gx0 = Math.floorDiv(bx, (int) DS_COARSE), gy0 = Math.floorDiv(by, (int) DS_COARSE)
          int gx1 = gx0 + Math.floorDiv(CORE_FULL, (int) DS_COARSE), gy1 = gy0 + Math.floorDiv(CORE_FULL, (int) DS_COARSE)
          if (!Morph.anyRoi(roiC, cw, chh, gx0, gy0, gx1, gy1)) continue
          int rw = Math.min(CORE_FULL, W - bx), rh = Math.min(CORE_FULL, H - by)
          def rb = readChans(server, dsFine, bx, by, rw, rh, CHANNELS)
          int bw = (int) rb.w, bh = (int) rb.h
          float[] bf = Morph.maxProject((short[][]) rb.chans, bw * bh)
          byte[] roiB = new byte[bw * bh]; byte[] labB = new byte[bw * bh]
          Morph.upsampleLabels(roiC, labC, cw, chh, gx0, gy0, K, bw, bh, roiB, labB)
          sums << [otsu: Morph.otsuWithin(bf, roiB),
                   p05: Morph.percentileWithin(bf, roiB, 0.05d),
                   p25: Morph.percentileWithin(bf, roiB, 0.25d),
                   p50: Morph.percentileWithin(bf, roiB, 0.50d),
                   p75: Morph.percentileWithin(bf, roiB, 0.75d),
                   p95: Morph.percentileWithin(bf, roiB, 0.95d)]
          taken++
        }
      }
      if (sums.isEmpty()) { logMsg("    ds " + dsFine + ": no ROI blocks sampled"); return }
      def mean = { String k -> sums.collect { it[k] }.sum() / sums.size() }
      logMsg(String.format("    ds %-4.1f (%.3f um/px) n=%d blocks: in-ROI Otsu=%.1f  p05=%.0f p25=%.0f p50=%.0f p75=%.0f p95=%.0f",
          dsFine, pxUm0 * dsFine, sums.size(), mean("otsu"), mean("p05"), mean("p25"), mean("p50"), mean("p75"), mean("p95")))
    }
    server.close()
    return
  }

  // -----------------------------------------------------------------------
  // RESOLUTION SWEEP -- every measure at several downsamples on the SAME
  // windows, so resolution dependence is measured, not asserted.
  // -----------------------------------------------------------------------
  if (SWEEP) {
    // pick up to 3 dense windows, one per compartment where possible
    def windows = []
    int stepG = Math.floorDiv(CORE_FULL, (int) DS_COARSE)
    [[1, "damaged"], [3, "intact"]].each { List spec ->
      int want = (int) spec[0]; String nm = (String) spec[1]
      int bestX = -1, bestY = -1; long bestN = -1L
      for (int gy = 0; gy + stepG <= chh; gy += stepG) {
        for (int gx = 0; gx + stepG <= cw; gx += stepG) {
          long c = 0L
          for (int y = gy; y < gy + stepG; y++) { int row = y * cw
            for (int x = gx; x < gx + stepG; x++) { int v = (int) (labC[row + x] & 0xFF)
              if (v == want || v == want + 1) c++ } }
          if (c > bestN) { bestN = c; bestX = gx; bestY = gy }
        }
      }
      if (bestX >= 0) windows << [x: bestX * (int) DS_COARSE, y: bestY * (int) DS_COARSE, name: nm,
                                  frac: bestN / (double) (stepG * stepG)]
    }
    windows.each { win ->
      logMsg(String.format("  SWEEP window '%s' at (%d,%d) %d px, %.1f%% of it is that compartment",
          win.name, win.x, win.y, CORE_FULL, 100.0 * win.frac))
      logMsg(String.format("  %5s %8s %9s %9s %9s %9s %9s %9s %9s %8s",
          "ds", "um/px", "tissFrac", "MLIdir", "MLIind", "airEDM", "thickEDM", "thick2AB", "SvPerUm", "trunc%"))
      [1.0d, 2.0d, 4.0d, 8.0d, 16.0d].each { double ds ->
        if (ds > DS_COARSE) return
        int K = (int) Math.round(DS_COARSE / ds)
        int rw = Math.min(CORE_FULL, W - (int) win.x), rh = Math.min(CORE_FULL, H - (int) win.y)
        def rb = readChans(server, ds, (int) win.x, (int) win.y, rw, rh, CHANNELS)
        int bw = (int) rb.w, bh = (int) rb.h
        double px = pxUm0 * ds
        float[] bf = Morph.maxProject((short[][]) rb.chans, bw * bh)
        byte[] roiB = new byte[bw * bh]; byte[] labB = new byte[bw * bh]
        int gx0 = Math.floorDiv((int) win.x, (int) DS_COARSE), gy0 = Math.floorDiv((int) win.y, (int) DS_COARSE)
        Morph.upsampleLabels(roiC, labC, cw, chh, gx0, gy0, K, bw, bh, roiB, labB)
        // collapse labels to one region so the sweep is about resolution only
        for (int i = 0; i < labB.length; i++) if ((labB[i] & 0xFF) > 0) labB[i] = (byte) 1
        byte[] tis = new byte[bw * bh]
        Morph.threshold(bf, thrIsOtsu ? Morph.otsuWithin(bf, roiB) : TISSUE_THR, tis)
        byte[] air = new byte[bw * bh]
        for (int i = 0; i < air.length; i++) air[i] = ((tis[i] & 0xFF) > 127) ? (byte) 0 : (byte) 255
        double[][] aacc = new double[2][3]; Morph.areaAccum(tis, roiB, labB, bw, bh, 0, 0, bw, bh, aacc)
        double[][][] dacc = new double[4][2][6]
        Morph.chordScan(tis, roiB, labB, bw, bh, 0, 0, bw, bh, 1,  0, px, dacc[0])
        Morph.chordScan(tis, roiB, labB, bw, bh, 0, 0, bw, bh, 1,  1, px, dacc[1])
        Morph.chordScan(tis, roiB, labB, bw, bh, 0, 0, bw, bh, 0,  1, px, dacc[2])
        Morph.chordScan(tis, roiB, labB, bw, bh, 0, 0, bw, bh, 1, -1, px, dacc[3])
        double mli = 0.0d, tl = 0.0d, tr = 0.0d, tn = 0.0d, cnq = 0.0d
        for (int d = 0; d < 4; d++) {
          mli += (dacc[d][1][1] > 0) ? dacc[d][1][0] / dacc[d][1][1] : 0.0d
          tl += dacc[d][1][2]; tr += dacc[d][1][3]; tn += dacc[d][1][4]; cnq += dacc[d][1][1]
        }
        mli /= 4.0d
        double[][] pacc = new double[2][4]; Morph.croftonCrossings(tis, roiB, labB, bw, bh, 0, 0, bw, bh, pacc)
        double per = Morph.croftonPerimeterUm(pacc[1], px)
        int NB = 2 + Morph.DIST_BINS
        double[][] eacc = new double[2][NB]; Morph.edmAccum(tis, roiB, labB, bw, bh, 0, 0, bw, bh, px, eacc, 0)
        double[][] aeacc = new double[2][NB]; Morph.edmAccum(air, roiB, labB, bw, bh, 0, 0, bw, bh, px, aeacc, 0)
        logMsg(String.format("  %5.1f %8.4f %9.4f %9.2f %9.2f %9.2f %9.3f %9.3f %9.5f %8.2f",
            ds, px, aacc[1][1] / Math.max(1.0d, aacc[1][0]), mli, 2.0d * tl / Math.max(1.0d, tr),
            4.0d * aeacc[1][0] / Math.max(1.0d, aeacc[1][1]),
            4.0d * eacc[1][0] / Math.max(1.0d, eacc[1][1]),
            2.0d * aacc[1][1] * px * px / Math.max(1e-9d, per),
            (4.0d / Math.PI) * per / Math.max(1e-9d, aacc[1][0] * px * px),
            100.0 * tn / Math.max(1.0d, tn + cnq)))
      }
    }
    server.close()
    return
  }

  // -----------------------------------------------------------------------
  // FINE PASS, once per requested fine downsample.
  // -----------------------------------------------------------------------
  DS_FINE_LIST.each { double dsFine ->
    int K = (int) Math.round(DS_COARSE / dsFine)
    double pxFine = pxUm0 * dsFine
    double fineArea = pxFine * pxFine
    int nRegions = 4
    int NB = 2 + Morph.DIST_BINS

    double[][] areaAcc = new double[nRegions + 1][3]
    double[][] perAcc  = new double[nRegions + 1][4]
    double[][][] chordAcc = new double[4][nRegions + 1][6]
    double[][] edmAcc  = new double[nRegions + 1][NB]
    double[][] aedmAcc = new double[nRegions + 1][NB]
    byte[] tisAnyC = new byte[cn]

    def lock = new Object()
    def blocks = []
    int biy = 0
    for (int by = 0; by < H; by += CORE_FULL) {
      int bix = 0
      for (int bx = 0; bx < W; bx += CORE_FULL) {
        if (STRIDE <= 1 || ((bix + biy) % STRIDE) == 0) blocks << [bx, by]
        bix++
      }
      biy++
    }
    def kept = blocks.findAll { List b ->
      int bx = (int) b[0], by = (int) b[1]
      int gx0 = Math.floorDiv(bx, (int) DS_COARSE), gy0 = Math.floorDiv(by, (int) DS_COARSE)
      int gx1 = gx0 + (int) Math.ceil(Math.min(CORE_FULL, W - bx) / DS_COARSE)
      int gy1 = gy0 + (int) Math.ceil(Math.min(CORE_FULL, H - by) / DS_COARSE)
      return Morph.anyRoi(roiC, cw, chh, gx0, gy0, gx1, gy1)
    }
    if (MAX_BLOCKS > 0 && kept.size() > MAX_BLOCKS) kept = kept.subList(0, MAX_BLOCKS)
    boolean capped = (MAX_BLOCKS > 0 && MAX_BLOCKS < blocks.size())
    logMsg(String.format("  fine ds %.1f (%.4f um/px): %d of %d blocks intersect the ROI%s",
        dsFine, pxFine, kept.size(), blocks.size(), capped ? "  *** CAPPED ***" : ""))

    long tF = System.currentTimeMillis()
    def done = new AtomicInteger(0)
    def pool = Executors.newFixedThreadPool(NTHREADS)
    def errors = java.util.Collections.synchronizedList([])
    kept.each { List b ->
      pool.submit({ ->
        try {
          int bx = (int) b[0], by = (int) b[1]
          int coreW = Math.min(CORE_FULL, W - bx), coreH = Math.min(CORE_FULL, H - by)
          int rx0 = Math.max(0, bx - HALO_FULL), ry0 = Math.max(0, by - HALO_FULL)
          int rx1 = Math.min(W, bx + coreW + HALO_FULL), ry1 = Math.min(H, by + coreH + HALO_FULL)
          // snap the read origin down to a coarse-pixel boundary so the
          // fine->coarse index map is an exact integer division
          rx0 = rx0 - (rx0 % (int) DS_COARSE); ry0 = ry0 - (ry0 % (int) DS_COARSE)
          int reqW = rx1 - rx0, reqH = ry1 - ry0
          def rb = readChans(server, dsFine, rx0, ry0, reqW, reqH, CHANNELS)
          int bw = (int) rb.w, bh = (int) rb.h
          if (Math.abs(bw - reqW / dsFine) > 1.5d || Math.abs(bh - reqH / dsFine) > 1.5d)
            throw new IllegalStateException("block raster " + bw + "x" + bh + " != " + (reqW / dsFine) + "x" + (reqH / dsFine))
          float[] bf = Morph.maxProject((short[][]) rb.chans, bw * bh)
          if (SMOOTH_UM > 0) {
            def fp = new FloatProcessor(bw, bh, bf, null)
            double sig = SMOOTH_UM / pxFine
            if (sig > 0.3d) { new GaussianBlur().blurGaussian(fp, sig); bf = (float[]) fp.getPixels() }
          }
          byte[] roiB = new byte[bw * bh]; byte[] labB = new byte[bw * bh]
          int gx0 = Math.floorDiv(rx0, (int) DS_COARSE), gy0 = Math.floorDiv(ry0, (int) DS_COARSE)
          Morph.upsampleLabels(roiC, labC, cw, chh, gx0, gy0, K, bw, bh, roiB, labB)
          byte[] tisB = new byte[bw * bh]
          Morph.threshold(bf, thrIsOtsu ? Morph.otsuWithin(bf, roiB) : TISSUE_THR, tisB)
          byte[] airB = new byte[bw * bh]
          for (int i = 0; i < airB.length; i++) airB[i] = ((tisB[i] & 0xFF) > 127) ? (byte) 0 : (byte) 255

          int lx0 = (int) Math.round((bx - rx0) / dsFine), ly0 = (int) Math.round((by - ry0) / dsFine)
          int lx1 = Math.min(bw, lx0 + (int) Math.round(coreW / dsFine))
          int ly1 = Math.min(bh, ly0 + (int) Math.round(coreH / dsFine))

          double[][] la = new double[nRegions + 1][3]
          double[][] lp = new double[nRegions + 1][4]
          double[][][] lc = new double[4][nRegions + 1][6]
          double[][] le = new double[nRegions + 1][NB]
          double[][] lae = new double[nRegions + 1][NB]
          Morph.areaAccum(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, la)
          Morph.croftonCrossings(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, lp)
          Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1,  0, pxFine, lc[0])
          Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1,  1, pxFine, lc[1])
          Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 0,  1, pxFine, lc[2])
          Morph.chordScan(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, 1, -1, pxFine, lc[3])
          Morph.edmAccum(tisB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, pxFine, le, 0)
          Morph.edmAccum(airB, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, pxFine, lae, 0)
          synchronized (lock) {
            Morph.addInto(areaAcc, la)
            Morph.addInto(perAcc, lp)
            for (int d = 0; d < 4; d++) Morph.addInto(chordAcc[d], lc[d])
            Morph.addInto(edmAcc, le)
            Morph.addInto(aedmAcc, lae)
            Morph.maxDownsampleTissue(tisB, roiB, bw, bh, lx0, ly0, lx1, ly1, gx0, gy0, K, cw, chh, tisAnyC)
          }
          int k = done.incrementAndGet()
          if (k % 20 == 0) logMsg("    ... " + k + "/" + kept.size() + " blocks (" + (System.currentTimeMillis() - tF) + " ms)")
        } catch (Throwable t) {
          errors << (b.toString() + ": " + t.toString())
        }
      } as Runnable)
    }
    pool.shutdown()
    pool.awaitTermination(12L, TimeUnit.HOURS)
    if (!errors.isEmpty()) failRun("fine pass had " + errors.size() + " block failure(s): " + errors.take(3).join(" | "))
    logMsg(String.format("  fine pass done: %d blocks in %d ms", kept.size(), System.currentTimeMillis() - tF))

    // ---- topology, on the connectivity-preserving max-downsampled mask ----
    double[][] compAcc = new double[nRegions + 1][3]
    if (RUN_COMPONENTS) {
      long tT = System.currentTimeMillis()
      Morph.airspaceComponents(tisAnyC, roiC, labC, cw, chh,
          (long) Math.round(BIG_AIR_UM2 / coarseArea), nRegions, compAcc)
      double cn0 = 0, ct = 0, cb = 0
      for (int r = 1; r <= nRegions; r++) { cn0 += compAcc[r][0]; ct += compAcc[r][1]; cb += compAcc[r][2] }
      logMsg(String.format("  topology: %.0f airspace components, %.1f%% of airspace area in components > %.0f um2 (%d ms)",
          cn0, ct > 0 ? 100.0 * cb / ct : 0.0d, BIG_AIR_UM2, System.currentTimeMillis() - tT))
      if (ct > 0 && cb / ct > 0.90d)
        logMsg("  *** >90% of airspace is in 'confluent' components. The segmented phase is not a " +
               "continuous barrier, so EVERY connectivity column (components, confluence) is " +
               "UNINTERPRETABLE. The area/length columns -- airspace fraction, MLI, perimeter, " +
               "thickness -- never use connectivity and are unaffected. With a marker panel and no " +
               "counterstain this is expected, not fixable by resolution. ***")
    }

    // ---- consolidation / enlargement maps from architecture alone --------
    byte[] consC = new byte[cn]
    long[] consPx = new long[nRegions + 1]
    if (CONS_CUTOFF > 0) {
      byte[] airC = new byte[cn]
      for (int i = 0; i < cn; i++)
        if (((roiC[i] & 0xFF) > 127) && ((tisAnyC[i] & 0xFF) <= 127)) airC[i] = (byte) 255
      float[] laf = Morph.localFraction(airC, cw, chh, CONS_SIGMA_UM / pxCoarse)
      for (int i = 0; i < cn; i++) {
        if ((roiC[i] & 0xFF) <= 127) continue
        if (laf[i] < (float) CONS_CUTOFF) { consC[i] = (byte) 255; consPx[(int) (labC[i] & 0xFF)]++ }
      }
    }

    // ---- EMIT: one row per scope, scope carried in `panel` ---------------
    def rows = rowsByDs.computeIfAbsent(dsFine, { k -> [] })
    (1..nRegions).each { int r ->
      String scope = SCOPES[r - 1]
      def row = [:]
      row["image"]      = stem
      row["output_key"] = stem + "__morph__ds" + (dsFine as int) + "__" + scope
      row["region"]     = "parenchyma_" + scope
      row["section_id"] = stem
      row["mouse_id"]   = md.mouse_id
      row["genotype"]   = md.genotype
      row["condition"]  = md.condition
      row["panel"]      = PANEL_KEY + "@" + scope
      row["module_id"]  = MODULE_ID
      // denominator: the compartment's own area on the coarse grid
      row["region_area_um2"] = scopePx[r] * coarseArea
      // areas measured in the fine pass
      row["morph_tissue_positive_area_um2"]   = areaAcc[r][1] * fineArea
      row["morph_airspace_positive_area_um2"] = areaAcc[r][2] * fineArea
      row["morph_measured_positive_area_um2"] = areaAcc[r][0] * fineArea
      row["morph_aircomp_positive_area_um2"]  = compAcc[r][1] * coarseArea
      row["morph_aircomp_n_components"]       = compAcc[r][0]
      row["morph_airbig_positive_area_um2"]   = compAcc[r][2] * coarseArea
      if (CONS_CUTOFF > 0) row["morph_lowair_positive_area_um2"] = consPx[r] * coarseArea
      // lengths and counts -- all additive. "um" is glued to the quantity name
      // because MODULE_CONTRACT 2.2 forbids a <Name> ending in "_um".
      row["class_morph_perimeterum_count"] = Morph.croftonPerimeterUm(perAcc[r], pxFine)
      ["000", "045", "090", "135"].eachWithIndex { String nm, int d ->
        row["class_morph_chordlen" + nm + "um_count"] = chordAcc[d][r][0]
        row["class_morph_chordn" + nm + "_count"]     = chordAcc[d][r][1]
      }
      double tl = 0, tr = 0, tn = 0, tlen = 0
      for (int d = 0; d < 4; d++) { tl += chordAcc[d][r][2]; tr += chordAcc[d][r][3]; tn += chordAcc[d][r][4]; tlen += chordAcc[d][r][5] }
      row["class_morph_testlineum_count"]      = tl
      row["class_morph_transition_count"]      = tr
      row["class_morph_chordtruncn_count"]     = tn
      row["class_morph_chordtrunclenum_count"] = tlen
      row["class_morph_septaldistum_count"] = edmAcc[r][0]
      row["class_morph_septalpx_count"]     = edmAcc[r][1]
      row["class_morph_airdistum_count"]    = aedmAcc[r][0]
      row["class_morph_airpx_count"]        = aedmAcc[r][1]
      for (int b = 0; b < Morph.DIST_BINS; b++) {
        row[String.format("class_morph_sdist_b%02d_count", b)] = edmAcc[r][2 + b]
        row[String.format("class_morph_adist_b%02d_count", b)] = aedmAcc[r][2 + b]
      }
      row["class_morph_rows_count"] = 1.0d
      // QC / provenance -- dropped by aggregate_to_mouse, kept in the slide CSV
      row["morph_px_fine_um"]      = pxFine
      row["morph_px_coarse_um"]    = pxCoarse
      row["morph_ds_fine"]         = dsFine
      row["morph_tissue_threshold"] = TISSUE_THR
      row["morph_threshold_locked"] = (!thrIsOtsu).toString()
      row["morph_channels"]        = CHANNELS.join("|")
      row["morph_dist_bin_um"]     = Morph.DIST_BIN_UM
      row["morph_ager_threshold"]  = AGER_THR
      row["morph_damage_sigma_um"] = DAMAGE_SIGMA
      row["morph_damage_cutoff"]   = DAMAGE_CUT
      row["morph_erode_um"]        = CORE_ERODE_UM
      row["morph_roi_thr"]         = tThr
      row["morph_n_blocks"]        = kept.size()
      row["morph_block_stride"]    = STRIDE
      row["morph_coverage_complete"] = (!capped).toString()
      rows << row
    }
    qcRecords << [slide: stem, ds_fine: dsFine, series: chosen.series, width: W, height: H,
                  px_um: pxUm0, roi_mm2: roiPx * coarseArea / 1e6,
                  damaged_frac_of_tissue: nDmg / (double) roiPx,
                  ager_pos_frac_of_tissue: aN / (double) roiPx,
                  roi_otsu: tThr, tissue_threshold: TISSUE_THR, threshold_locked: !thrIsOtsu,
                  channels: CHANNELS as List, n_blocks: kept.size(), coverage_complete: !capped,
                  scope_area_mm2: [damaged: scopePx[1] * coarseArea / 1e6,
                                   damaged_core: scopePx[2] * coarseArea / 1e6,
                                   intact: scopePx[3] * coarseArea / 1e6,
                                   intact_core: scopePx[4] * coarseArea / 1e6],
                  mouse_id: md.mouse_id]
  }
  server.close()
}

if (rowsByDs.isEmpty()) { logMsg("no rows emitted"); return }
rowsByDs.each { dsFine, rows ->
  def cols = []
  rows.each { r -> r.keySet().each { if (!cols.contains(it)) cols << it } }
  def sb = new StringBuilder(csvRow(cols)).append("\n")
  rows.each { r -> sb.append(csvRow(cols.collect { c -> r[c] })).append("\n") }
  def outCsv = new File(outRoot, "morphometry_slide_summary_ds" + (dsFine as int) + ".csv")
  outCsv.setText(sb.toString(), "UTF-8")
  logMsg("Wrote " + rows.size() + " row(s) -> " + outCsv.getAbsolutePath())
}
new File(outRoot, "morphometry_manifest.json").setText(
    GsonTools.getInstance(true).toJson([schema_version: "0.2", stage: "morphometry",
        generated_utc: java.time.Instant.now().toString(), slides: qcRecords]), "UTF-8")
logMsg("NEXT: python aggregate_to_mouse.py <csv> --outdir <stats>  then  python morphometry_derive.py <stats>/mouse_level_summary.csv")
