// ============================================================================
// morph2.groovy -- lung ARCHITECTURE morphometry, QuPath 0.7.0 host.
// Rewrite of _drafts/morphometry/qupath_lung_morphometry.groovy after review.
//
// WHAT CHANGED vs THE DRAFT (all four are behaviour changes, not cosmetics)
//  1. COMPARTMENTS ARE THE LOCKED ONES.  The draft rebuilt its analysis ROI
//     from the Stage 1 ds=16 recipe (blur/Otsu/close/open/removeFragments) and
//     then applied the AGER damage rule inside it.  That is NOT the territory
//     the endpoint denominator is defined on.  scripts/measure_damage_locked.
//     groovy uses ds=8, DAPI blur sigma=2 px, Otsu over the WHOLE frame, a
//     close with r=4 and NO open and NO fragment removal.  This script is a
//     verbatim port of that, and it PROVES the port by reproducing the
//     published damaged fractions (0.93 / 0.18 / 6.71 / 4.68 %) before it
//     measures anything.  Without that the cross-check would be testing a
//     compartment nobody locked.
//  2. ORIENTATION WEIGHTING.  The draft pooled all four chord directions into
//     one accumulator.  On a square lattice the two diagonal test-line
//     families carry sqrt(2) x more test-line length than the axial families,
//     so the pooled MLI is weighted 29.3% / 29.3% / 20.7% / 20.7% over
//     orientation instead of uniformly.  Here every direction gets its own
//     additive pair and the derive step averages the four DIRECTIONAL MLIs,
//     which is the uniform-orientation estimator.  The pooled value is still
//     emitted so the difference can be seen.
//  3. THE PER-PIXEL HOT LOOPS THAT MADE IT UNRUNNABLE.  The draft's block
//     loop did `int gy = (ey + y) / K` per pixel in dynamic Groovy.  `/` on
//     two ints in Groovy returns a BigDecimal.  At ds=2 that is ~4e9 BigDecimal
//     divisions per slide.  Those loops are now inside @CompileStatic with
//     intdiv().  Same for the three `for (i in 0..<labC.length)` region-area
//     scans, which ran over 33 Mpx each in dynamic Groovy.
//  4. THREE TISSUE MASKS, ONE READ.  A fluorescence panel has no tissue
//     counterstain, so "which channels mean tissue" changes the answer.  More
//     importantly, building the tissue mask out of AGER while the compartments
//     are DEFINED by AGER density is circular -- the same circularity that got
//     the AGER co-negativity variant retracted.  Three masks are measured from
//     the same block read:
//        nuc = DAPI                      independent of AGER and of KRT5
//        dp  = DAPI or PDPN(Cy5)         best septum proxy that is still
//                                        independent of AGER and of KRT5
//        all = DAPI or KRT5 or AGER or PDPN   CIRCULAR by construction; carried
//                                        only to measure how big the
//                                        circularity is.
//     Per-channel thresholds, OR-ed -- not a max-projection against one
//     threshold, which would let the brightest channel set the rule for all.
//
// MODES
//   IFQ_M2_MODE=selftest   synthetic phantoms, known answers (from the draft,
//                          extended with a 4-direction orientation test)
//   IFQ_M2_MODE=calibrate  locked-detector port + per-channel intensity
//                          percentiles inside parenchyma.  CONTROLS ONLY.
//   IFQ_M2_MODE=sweep      resolution dependence, systematic windows
//   IFQ_M2_MODE=run        the measurement
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

def LOG = "[M2]"
def logMsg = { String m -> println LOG + " " + m }
def fail = { String m -> System.err.println("FATAL: " + m); println LOG + " FATAL: " + m; System.exit(1) }
def envOr = { String n, String d -> def v = System.getenv(n); (v == null || v.trim().isEmpty()) ? d : v.trim() }
def envD  = { String n, double d -> Double.parseDouble(envOr(n, "" + d)) }
def envI  = { String n, int d -> Integer.parseInt(envOr(n, "" + d)) }
def envB  = { String n, boolean d -> envOr(n, "" + d).toLowerCase() == "true" }
def csvF  = { v -> String s = (v == null) ? "" : v.toString()
              (s.contains(",")||s.contains("\"")||s.contains("\n")) ? "\"" + s.replace("\"","\"\"") + "\"" : s }
def csvR  = { List l -> l.collect { csvF(it) }.join(",") }

// ===========================================================================
// PIXEL MATH.  The measurement primitives are carried over VERBATIM from the
// draft (they pass 33/33 analytic phantom checks).  Everything added here is
// marked NEW.
// ===========================================================================
@groovy.transform.CompileStatic
class Morph {
  static final double EDM_PIXEL_OFFSET = 0.5d

  static double otsuAll(float[] f) {
    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE
    for (int i = 0; i < f.length; i++) { float v = f[i]; if (v < lo) lo = v; if (v > hi) hi = v }
    if (hi <= lo) return (double) lo
    int[] hist = new int[256]
    double sc = 255.0d / (hi - lo)
    for (int i = 0; i < f.length; i++) hist[(int) Math.round((f[i] - lo) * sc)]++
    int bin = new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hist)
    return lo + bin / sc
  }

  static double percentileWithin(float[] f, byte[] mask, double q) {
    int n = 0
    for (int i = 0; i < f.length; i++) if ((mask[i] & 0xFF) > 127) n++
    if (n == 0) return Double.NaN
    float[] v = new float[n]; int k = 0
    for (int i = 0; i < f.length; i++) if ((mask[i] & 0xFF) > 127) v[k++] = f[i]
    java.util.Arrays.sort(v)
    int idx = (int) Math.min((long) (n - 1), Math.max(0L, Math.round(q * (n - 1))))
    return (double) v[idx]
  }

  // ---- Cauchy-Crofton perimeter, 4 test-line directions --------------------
  static void croftonCrossings(byte[] tissue, byte[] roi, byte[] lab,
                               int w, int h, int cx0, int cy0, int cx1, int cy1, double[][] acc) {
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        int r = lab[i] & 0xFF
        if (r == 0) continue
        boolean t0 = (tissue[i] & 0xFF) > 127
        if (x + 1 < w) { int j = i + 1;     if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][0] += 1.0d }
        if (y + 1 < h) { int j = i + w;     if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][1] += 1.0d }
        if (x + 1 < w && y + 1 < h) { int j = i + w + 1; if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][2] += 1.0d }
        if (x + 1 < w && y - 1 >= 0) { int j = i - w + 1; if ((roi[j] & 0xFF) > 127 && (((tissue[j] & 0xFF) > 127) != t0)) acc[r][3] += 1.0d }
      }
    }
  }
  static double croftonPerimeterUm(double[] n4, double pxUm) {
    return (Math.PI / 8.0d) * ((n4[0] + n4[1]) * pxUm + (n4[2] + n4[3]) * pxUm / Math.sqrt(2.0d))
  }

  // ---- airspace chord scan -------------------------------------------------
  // acc[r][0] sum untruncated chord length (um)   [1] untruncated chord count
  //    [2] test-line length in ROI (um)           [3] air<->tissue transitions
  //    [4] truncated chord count                  [5] truncated chord length
  static void chordScan(byte[] tissue, byte[] roi, byte[] lab, int w, int h,
                        int cx0, int cy0, int cx1, int cy1,
                        int dx, int dy, double pxUm, double[][] acc) {
    double step = (dx != 0 && dy != 0) ? pxUm * Math.sqrt(2.0d) : pxUm
    int nStarts = 0
    int[] sx = new int[w + h]; int[] sy = new int[w + h]
    if (dx == 1 && dy == 0)      { for (int y = 0; y < h; y++) { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ } }
    else if (dx == 0 && dy == 1) { for (int x = 0; x < w; x++) { sx[nStarts] = x; sy[nStarts] = 0; nStarts++ } }
    else if (dx == 1 && dy == 1) { for (int y = h - 1; y >= 0; y--) { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ }
                                   for (int x = 1; x < w; x++) { sx[nStarts] = x; sy[nStarts] = 0; nStarts++ } }
    else                         { for (int y = 0; y < h; y++) { sx[nStarts] = 0; sy[nStarts] = y; nStarts++ }
                                   for (int x = 1; x < w; x++) { sx[nStarts] = x; sy[nStarts] = h - 1; nStarts++ } }
    for (int s = 0; s < nStarts; s++) {
      int x = sx[s], y = sy[s]
      boolean runOpen = false; double runLen = 0.0d
      int runRegion = 0; boolean runInCore = false; boolean runStartClean = false
      int prevState = -1
      while (x >= 0 && x < w && y >= 0 && y < h) {
        int i = y * w + x
        boolean inRoi = (roi[i] & 0xFF) > 127
        int state = -1
        if (inRoi) state = ((tissue[i] & 0xFF) > 127) ? 1 : 0
        boolean inCore = (x >= cx0 && x < cx1 && y >= cy0 && y < cy1)
        int r = lab[i] & 0xFF
        if (inRoi && r > 0) {
          if (inCore) acc[r][2] += step
          if (prevState >= 0 && state >= 0 && state != prevState && inCore) acc[r][3] += 1.0d
        }
        if (state == 0) {
          if (!runOpen) { runOpen = true; runLen = 0.0d; runRegion = r; runInCore = inCore; runStartClean = (prevState == 1) }
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
      if (runOpen && runInCore && runRegion > 0) { acc[runRegion][5] += runLen; acc[runRegion][4] += 1.0d }
    }
  }

  // ---- septal thickness by Euclidean distance transform --------------------
  static void edmAccum(byte[] tissue, byte[] roi, byte[] lab, int w, int h,
                       int cx0, int cy0, int cx1, int cy1, double pxUm, double[][] acc) {
    ByteProcessor bp = new ByteProcessor(w, h)
    byte[] p = (byte[]) bp.getPixels()
    for (int i = 0; i < p.length; i++)
      if (((roi[i] & 0xFF) > 127) && ((tissue[i] & 0xFF) > 127)) p[i] = (byte) 255
    FloatProcessor fp = new EDM().makeFloatEDM((ImageProcessor) bp, 0, false)
    float[] d = (float[]) fp.getPixels()
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((p[i] & 0xFF) <= 127) continue
        int r = lab[i] & 0xFF
        if (r == 0) continue
        double v = d[i] - EDM_PIXEL_OFFSET
        if (v < 0.0d) v = 0.0d
        acc[r][0] += v * pxUm
        acc[r][1] += 1.0d
      }
    }
  }

  static void areaAccum(byte[] tissue, byte[] roi, byte[] lab, int w, int h,
                        int cx0, int cy0, int cx1, int cy1, double[][] acc) {
    for (int y = cy0; y < cy1; y++) {
      int row = y * w
      for (int x = cx0; x < cx1; x++) {
        int i = row + x
        if ((roi[i] & 0xFF) <= 127) continue
        int r = lab[i] & 0xFF
        if (r == 0) continue
        acc[r][0] += 1.0d
        if ((tissue[i] & 0xFF) > 127) acc[r][1] += 1.0d else acc[r][2] += 1.0d
      }
    }
  }

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

  static void airspaceComponents(byte[] tissue, byte[] roi, byte[] lab,
                                 int w, int h, long bigPx, int nRegions, double[][] acc) {
    byte[] seen = new byte[w * h]
    int[] stack = new int[1 << 16]
    long[] perRegion = new long[nRegions + 1]
    for (int start = 0; start < w * h; start++) {
      if ((seen[start] & 0xFF) != 0) continue
      if ((roi[start] & 0xFF) <= 127) { seen[start] = (byte) 1; continue }
      if ((tissue[start] & 0xFF) > 127) { seen[start] = (byte) 1; continue }
      if ((lab[start] & 0xFF) == 0) { seen[start] = (byte) 1; continue }
      for (int k = 0; k <= nRegions; k++) perRegion[k] = 0L
      int sp = 0; stack[sp++] = start; seen[start] = (byte) 1
      long size = 0L
      while (sp > 0) {
        int i = stack[--sp]
        size++
        perRegion[lab[i] & 0xFF]++
        int x = i % w, y = i.intdiv(w)
        if (x > 0)     { int j = i - 1; if ((seen[j]&0xFF)==0 && (roi[j]&0xFF)>127 && (tissue[j]&0xFF)<=127 && (lab[j]&0xFF)!=0) { seen[j]=(byte)1; if (sp==stack.length) stack=java.util.Arrays.copyOf(stack, stack.length*2); stack[sp++]=j } }
        if (x < w - 1) { int j = i + 1; if ((seen[j]&0xFF)==0 && (roi[j]&0xFF)>127 && (tissue[j]&0xFF)<=127 && (lab[j]&0xFF)!=0) { seen[j]=(byte)1; if (sp==stack.length) stack=java.util.Arrays.copyOf(stack, stack.length*2); stack[sp++]=j } }
        if (y > 0)     { int j = i - w; if ((seen[j]&0xFF)==0 && (roi[j]&0xFF)>127 && (tissue[j]&0xFF)<=127 && (lab[j]&0xFF)!=0) { seen[j]=(byte)1; if (sp==stack.length) stack=java.util.Arrays.copyOf(stack, stack.length*2); stack[sp++]=j } }
        if (y < h - 1) { int j = i + w; if ((seen[j]&0xFF)==0 && (roi[j]&0xFF)>127 && (tissue[j]&0xFF)<=127 && (lab[j]&0xFF)!=0) { seen[j]=(byte)1; if (sp==stack.length) stack=java.util.Arrays.copyOf(stack, stack.length*2); stack[sp++]=j } }
      }
      int best = 0; long bestN = -1L
      for (int k = 1; k <= nRegions; k++) if (perRegion[k] > bestN) { bestN = perRegion[k]; best = k }
      if (best == 0) continue
      acc[best][0] += 1.0d
      acc[best][1] += (double) size
      if (size > bigPx) acc[best][2] += (double) size
    }
  }

  // ================= NEW =================================================
  /** Upsample coarse ROI + labels onto a fine block by exact k x k replication.
   *  This is the loop that was doing BigDecimal division per pixel. */
  static void upsampleLabels(byte[] roiC, byte[] labC, int cw, int chh,
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

  /** Per-region pixel counts of a coarse binary mask. */
  static void countByRegion(byte[] mask, byte[] labC, int n, double[] out) {
    for (int i = 0; i < n; i++) {
      int r = labC[i] & 0xFF
      if (r == 0) continue
      if (mask == null || (mask[i] & 0xFF) > 127) out[r] += 1.0d
    }
  }

  /** tissue = OR over selected channels of (value >= per-channel threshold). */
  static void orThreshold(short[][] chans, int[] use, double[] thr, int n, byte[] out) {
    for (int k = 0; k < use.length; k++) {
      short[] s = chans[use[k]]
      float t = (float) thr[k]
      for (int i = 0; i < n; i++) if (((float) (s[i] & 0xFFFF)) >= t) out[i] = (byte) 255
    }
  }

  /** Blur one raw ushort channel into a float array (ImageJ GaussianBlur). */
  static float[] blurChannel(short[] s, int w, int h, double sigmaPx) {
    FloatProcessor fp = new FloatProcessor(w, h)
    float[] o = (float[]) fp.getPixels()
    for (int i = 0; i < o.length; i++) o[i] = (float) (s[i] & 0xFFFF)
    if (sigmaPx > 0) new GaussianBlur().blurGaussian(fp, sigmaPx)
    return (float[]) fp.getPixels()
  }
}

// ===========================================================================
// SELF-TEST
// ===========================================================================
def selfTest = { ->
  println LOG + " ============ SELF-TEST ============"
  double pxUm = 1.0d
  int PASS = 0, FAIL = 0
  def check = { String what, double got, double want, double tol ->
    boolean ok = Math.abs(got - want) <= Math.abs(want) * tol
    if (ok) PASS++ else FAIL++
    println LOG + String.format("  %-56s got=%10.4f want=%10.4f %s", what, got, want, ok ? "PASS" : "FAIL")
  }
  [200, 400].each { int R ->
    int w = 2*R+40, h = 2*R+40
    byte[] tis = new byte[w*h]; byte[] roi = new byte[w*h]; byte[] lab = new byte[w*h]
    double cx = w/2.0d, cy = h/2.0d
    for (int y=0;y<h;y++) for (int x=0;x<w;x++) { int i=y*w+x; roi[i]=(byte)255; lab[i]=(byte)1
      double dx=x+0.5d-cx, dy=y+0.5d-cy; if (dx*dx+dy*dy <= (double)R*R) tis[i]=(byte)255 }
    double[][] acc = new double[2][4]
    Morph.croftonCrossings(tis, roi, lab, w, h, 0, 0, w, h, acc)
    check("Crofton perimeter disk R="+R, Morph.croftonPerimeterUm(acc[1], pxUm), 2*Math.PI*R, 0.02d)
  }
  [[4,40],[6,60],[3,30]].each { List cfg ->
    int T=(int)cfg[0], G=(int)cfg[1]; int P=T+G; int w=P*40, h=600
    byte[] tis=new byte[w*h]; byte[] roi=new byte[w*h]; byte[] lab=new byte[w*h]
    for (int y=0;y<h;y++) for (int x=0;x<w;x++){int i=y*w+x; roi[i]=(byte)255; lab[i]=(byte)1; if ((x%P)<T) tis[i]=(byte)255}
    double[][] a=new double[2][3]; Morph.areaAccum(tis,roi,lab,w,h,0,0,w,h,a)
    check("stripes T="+T+" G="+G+" airspace fraction", a[1][2]/a[1][0], (double)G/P, 0.005d)
    double[][] c=new double[2][6]; Morph.chordScan(tis,roi,lab,w,h,0,0,w,h,1,0,pxUm,c)
    check("stripes T="+T+" G="+G+" direct MLI h", c[1][0]/c[1][1], (double)G, 0.02d)
    check("stripes T="+T+" G="+G+" indirect 2L/N", 2.0d*c[1][2]/c[1][3], (double)P, 0.02d)
    double[][] e=new double[2][2]; Morph.edmAccum(tis,roi,lab,w,h,0,0,w,h,pxUm,e)
    check("stripes T="+T+" G="+G+" septum EDM", 4.0d*e[1][0]/e[1][1], (double)T, 0.15d)
    double[][] p=new double[2][4]; Morph.croftonCrossings(tis,roi,lab,w,h,0,0,w,h,p)
    check("stripes T="+T+" G="+G+" septum 2A/B", 2.0d*a[1][1]/Morph.croftonPerimeterUm(p[1],pxUm), (double)T, 0.30d)
  }
  // disks: mean chord = pi*D/4 in EVERY direction (isotropic phantom)
  [30, 60].each { int D ->
    int R=D/2, pitch=D+6, w=pitch*30, h=pitch*30
    byte[] tis=new byte[w*h]; byte[] roi=new byte[w*h]; byte[] lab=new byte[w*h]
    java.util.Arrays.fill(tis,(byte)255)
    for (int i=0;i<w*h;i++){roi[i]=(byte)255; lab[i]=(byte)1}
    for (int by=0;by<30;by++) for (int bx=0;bx<30;bx++){
      double ccx=bx*pitch+pitch/2.0d+((by%2==0)?0.0d:pitch/2.0d), ccy=by*pitch+pitch/2.0d
      for (int y=(int)(ccy-R-1); y<=(int)(ccy+R+1); y++){ if(y<0||y>=h) continue
        for (int x=(int)(ccx-R-1); x<=(int)(ccx+R+1); x++){ if(x<0||x>=w) continue
          double dx=x+0.5d-ccx, dy=y+0.5d-ccy; if (dx*dx+dy*dy<=(double)R*R) tis[y*w+x]=0 } } }
    int[][] dirs = [[1,0],[0,1],[1,1],[1,-1]] as int[][]
    double sumMli = 0.0d
    dirs.eachWithIndex { int[] dd, int k ->
      double[][] c=new double[2][6]
      Morph.chordScan(tis,roi,lab,w,h,0,0,w,h,dd[0],dd[1],pxUm,c)
      double m = c[1][0]/c[1][1]
      sumMli += m
      check("disks D="+D+" dir("+dd[0]+","+dd[1]+") MLI vs piD/4", m, Math.PI*D/4.0d, 0.12d)
    }
    check("disks D="+D+" orientation-averaged MLI", sumMli/4.0d, Math.PI*D/4.0d, 0.10d)
    double[][] k2=new double[2][3]; Morph.airspaceComponents(tis,roi,lab,w,h,Long.MAX_VALUE,1,k2)
    check("disks D="+D+" component count", k2[1][0], 900.0d, 0.05d)
  }
  // NEW: anisotropic phantom -- pooling 4 directions must NOT equal the
  // orientation average when the structure is anisotropic. This is the bug
  // the draft had: proves the two estimators are genuinely different.
  def aniso = { ->
    int T=4, G=40, P=T+G, w=P*40, h=600
    byte[] tis=new byte[w*h]; byte[] roi=new byte[w*h]; byte[] lab=new byte[w*h]
    for (int y=0;y<h;y++) for (int x=0;x<w;x++){int i=y*w+x; roi[i]=(byte)255; lab[i]=(byte)1; if ((x%P)<T) tis[i]=(byte)255}
    int[][] dirs = [[1,0],[0,1],[1,1],[1,-1]] as int[][]
    double[][] pooled = new double[2][6]
    double s = 0.0d; int nd = 0
    dirs.each { int[] dd ->
      double[][] c=new double[2][6]
      Morph.chordScan(tis,roi,lab,w,h,0,0,w,h,dd[0],dd[1],pxUm,c)
      Morph.chordScan(tis,roi,lab,w,h,0,0,w,h,dd[0],dd[1],pxUm,pooled)
      if (c[1][1] > 0) { s += c[1][0]/c[1][1]; nd++ }
    }
    double poolM = pooled[1][0]/pooled[1][1]
    double avgM  = s/nd
    println LOG + String.format("  anisotropic stripes: pooled-MLI=%.2f  orientation-averaged MLI=%.2f  (differ by %.1f%%)",
        poolM, avgM, 100.0*(poolM/avgM - 1.0))
    if (Math.abs(poolM/avgM - 1.0) < 1e-6) { FAIL++; println LOG + "  FAIL: pooled == averaged on an anisotropic phantom; the fix is not active" }
    else PASS++
    return true
  }
  aniso()
  // additivity
  def addTest = { ->
    int w=900,h=900
    byte[] tis=new byte[w*h]; byte[] roi=new byte[w*h]; byte[] lab=new byte[w*h]
    java.util.Random rnd=new java.util.Random(42L)
    for (int y=0;y<h;y++) for (int x=0;x<w;x++){int i=y*w+x; roi[i]=(byte)255; lab[i]=(byte)1
      if (((x/7)+(y/11))%3==0 || rnd.nextInt(100)<5) tis[i]=(byte)255}
    double[][] whole=new double[2][6]; Morph.chordScan(tis,roi,lab,w,h,0,0,w,h,1,0,pxUm,whole)
    double[][] blocks=new double[2][6]
    for (int by=0;by<3;by++) for (int bx=0;bx<3;bx++)
      Morph.chordScan(tis,roi,lab,w,h,bx*300,by*300,bx*300+300,by*300+300,1,0,pxUm,blocks)
    check("additivity chord length", blocks[1][0], whole[1][0], 1e-9d)
    check("additivity chord count",  blocks[1][1], whole[1][1], 1e-9d)
    double[][] pw=new double[2][4]; double[][] pb=new double[2][4]
    Morph.croftonCrossings(tis,roi,lab,w,h,0,0,w,h,pw)
    for (int by=0;by<3;by++) for (int bx=0;bx<3;bx++)
      Morph.croftonCrossings(tis,roi,lab,w,h,bx*300,by*300,bx*300+300,by*300+300,pb)
    check("additivity Crofton", pb[1][0]+pb[1][1]+pb[1][2]+pb[1][3], pw[1][0]+pw[1][1]+pw[1][2]+pw[1][3], 1e-9d)
    // NEW: upsampleLabels must replicate exactly
    int cw=17, chh=13, k=4
    byte[] roiC=new byte[cw*chh]; byte[] labC=new byte[cw*chh]
    for (int i=0;i<cw*chh;i++){ roiC[i]=(byte)((i%3==0)?255:0); labC[i]=(byte)((i%3==0)?((i%2==0)?1:2):0) }
    int bw=cw*k, bh=chh*k
    byte[] rB=new byte[bw*bh]; byte[] lB=new byte[bw*bh]
    Morph.upsampleLabels(roiC, labC, cw, chh, 0, 0, k, bw, bh, rB, lB)
    long bad=0
    for (int y=0;y<bh;y++) for (int x=0;x<bw;x++){
      int gi=(y/k)*cw+(x/k)
      if (rB[y*bw+x]!=roiC[gi] || lB[y*bw+x]!=labC[gi]) bad++ }
    check("upsampleLabels exact k x k replication (mismatches)", (double) bad, 0.0d, 1e-9d)
    return true
  }
  addTest()
  println LOG + " ============ SELF-TEST: " + PASS + " passed, " + FAIL + " failed ============"
  if (FAIL > 0) fail("self-test failed")
  return true
}

// ===========================================================================
// CONFIG
// ===========================================================================
def MODE      = envOr("IFQ_M2_MODE", "run").toLowerCase()
if (MODE == "selftest") { selfTest(); return }

def DIR       = envOr("IFQ_M2_DIR", "D:/Confocal_Images/20260806_CW/20260806_CW/")
def OUT       = envOr("IFQ_M2_OUT", "")
def ONLY      = envOr("IFQ_M2_ONLY", "")             // substring filter on slide name
def DS_C      = 8.0d                                  // LOCKED: the damage detector's resolution
def DS_F      = envD("IFQ_M2_DS_FINE", 4.0d)
def CORE_PX   = envI("IFQ_M2_BLOCK_CORE_PX", 2048)
def HALO_UM   = envD("IFQ_M2_BLOCK_HALO_UM", 200.0d)
def STRIDE    = envI("IFQ_M2_BLOCK_STRIDE", 1)        // 1 = full coverage; n = systematic 1/n^2 sample
def MAXBLK    = envI("IFQ_M2_MAX_BLOCKS", 0)
def PARTITION = envB("IFQ_M2_PARTITION", true)
def BIG_UM2   = envD("IFQ_M2_BIG_AIRSPACE_UM2", 10000.0d)

// LOCKED damage-detector parameters (scripts/measure_damage_locked.groovy)
def AGER_CH   = envI("IFQ_WSI_AGER_CHANNEL", 2)
def AGER_THR  = envD("IFQ_WSI_AGER_THRESHOLD", 150.0d)
def DMG_SIGMA = envD("IFQ_WSI_DAMAGE_SIGMA_UM", 40.0d)
def DMG_CUT   = envD("IFQ_WSI_DAMAGE_CUTOFF", 0.14d)

// per-channel tissue thresholds for the morphometry masks (raw uint16 units)
def T_DAPI    = envD("IFQ_M2_THR_DAPI", -1.0d)
def T_KRT5    = envD("IFQ_M2_THR_KRT5", -1.0d)
def T_AGER    = envD("IFQ_M2_THR_AGER", AGER_THR)
def T_PDPN    = envD("IFQ_M2_THR_PDPN", -1.0d)

def SWEEP_DS  = envOr("IFQ_M2_SWEEP_DS", "1,2,4,8,16")
def SWEEP_N   = envI("IFQ_M2_SWEEP_WINDOWS", 9)
def SWEEP_WIN = envI("IFQ_M2_SWEEP_WIN_PX", 2048)     // in FULL-RES px

def SLIDES = [
  [file: "IFNg KO(het) 26.03.25 m4-1 pr8 infection",    mouse: "m4-1", geno: "het", cond: "PR8",        role: "held out"],
  [file: "IFNg KO(het) 26.03.25 m4-2 pr8 no infection", mouse: "m4-2", geno: "het", cond: "uninfected", role: "CONTROL (calibration)"],
  [file: "IFNg KO(hom) 26.03.25 m2 pr8 infection",      mouse: "m2",   geno: "hom", cond: "PR8",        role: "held out"],
  [file: "IFNg KO(hom) 26.03.25 m6 pr8 no infection",   mouse: "m6",   geno: "hom", cond: "uninfected", role: "CONTROL (calibration)"],
].findAll { ONLY.isEmpty() || it.file.contains(ONLY) || it.mouse == ONLY }

int K = (int) Math.round(DS_C / DS_F)
if (Math.abs(DS_C / DS_F - K) > 1e-9 || K < 1) fail("8 / IFQ_M2_DS_FINE must be a positive integer; got " + (DS_C/DS_F))

def pickSeries = { String p ->
  def r = new ImageReader(); r.setFlattenedResolutions(false)
  IMetadata m = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(m)
  r.setId(p); int sel = -1
  for (int s = 0; s < r.getSeriesCount(); s++) {
    r.setSeries(s); r.setResolution(0)
    def pw = m.getPixelsPhysicalSizeX(s); if (pw == null) continue
    double um = pw.value(UNITS.MICROMETER).doubleValue()
    if (r.getEffectiveSizeC() == 4 && r.getSizeZ() == 1 && um > 0 && um <= 0.5) sel = s
  }
  r.close(); return sel
}

/** Read a region, return per-channel raw ushort arrays for ALL 4 channels. */
def readAll = { ImageServer server, double ds, int x, int y, int w, int h ->
  def img = server.readRegion(ds, x, y, w, h)
  int mw = img.getWidth(), mh = img.getHeight(), n = mw * mh
  def raster = img.getRaster()
  def sm = raster.getSampleModel(), db = raster.getDataBuffer()
  boolean fast = (db instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
                 raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0 &&
                 sm.getScanlineStride() == mw
  int nc = server.nChannels()
  short[][] out = new short[nc][]
  for (int c = 0; c < nc; c++) {
    if (fast && sm.getBankIndices()[c] == c && db.getOffsets()[c] == 0) out[c] = ((DataBufferUShort) db).getData(c)
    else { int[] tmp = new int[n]; raster.getSamples(0, 0, mw, mh, c, tmp)
           short[] o = new short[n]; for (int i = 0; i < n; i++) o[i] = (short) tmp[i]; out[c] = o }
  }
  return [w: mw, h: mh, chans: out]
}

// ---------------------------------------------------------------------------
// LOCKED DAMAGE DETECTOR -- verbatim port of scripts/measure_damage_locked.groovy
// Returns the ds=8 tissue mask and the damage mask on the SAME grid.
// ---------------------------------------------------------------------------
def lockedCompartments = { ImageServer server, double pxUm8 ->
  int W = server.getWidth(), H = server.getHeight()
  def r = readAll(server, DS_C, 0, 0, W, H)
  int w = (int) r.w, h = (int) r.h, n = w * h
  short[][] cs = (short[][]) r.chans
  float[] fd = Morph.blurChannel(cs[0], w, h, 2.0d)          // DAPI, sigma 2 px
  double tThr = Morph.otsuAll(fd)                            // Otsu over the WHOLE frame
  def tbp = new ByteProcessor(w, h); byte[] tb = (byte[]) tbp.getPixels()
  for (int i = 0; i < n; i++) if (fd[i] >= tThr) tb[i] = (byte) 255
  def rf = new RankFilters(); rf.rank(tbp, 4.0d, RankFilters.MAX); rf.rank(tbp, 4.0d, RankFilters.MIN)
  byte[] tissue = new byte[n]; long tN = 0L
  for (int i = 0; i < n; i++) if ((tb[i] & 0xFF) > 127) { tissue[i] = (byte) 255; tN++ }
  float[] fa = Morph.blurChannel(cs[AGER_CH], w, h, 1.0d)    // AGER, sigma 1 px
  def dp = new FloatProcessor(w, h); float[] d = (float[]) dp.getPixels()
  long aN = 0L
  for (int i = 0; i < n; i++) { boolean p = ((tissue[i]&0xFF)>127) && fa[i] >= AGER_THR; d[i] = p ? 1f : 0f; if (p) aN++ }
  new GaussianBlur().blurGaussian(dp, DMG_SIGMA / pxUm8)
  float[] dens = (float[]) dp.getPixels()
  byte[] dmg = new byte[n]; long dN = 0L
  for (int i = 0; i < n; i++) if (((tissue[i]&0xFF)>127) && dens[i] < DMG_CUT) { dmg[i] = (byte) 255; dN++ }
  return [w: w, h: h, tissue: tissue, dmg: dmg, chans: cs, otsu: tThr,
          tissuePx: tN, agerPx: aN, dmgPx: dN,
          tissuePct: 100.0 * tN / n, agerPct: 100.0 * aN / Math.max(1L, tN), dmgPct: 100.0 * dN / Math.max(1L, tN)]
}

// ===========================================================================
def outDir = OUT.isEmpty() ? null : new File(OUT)
if (outDir != null) outDir.mkdirs()

// ---------------------------------------------------------------------------
// CALIBRATE
// ---------------------------------------------------------------------------
if (MODE == "calibrate") {
  logMsg("LOCKED-DETECTOR PORT CHECK + per-channel intensity distributions")
  logMsg(String.format("locked: AGER thr=%.0f  sigma=%.0f um  cutoff=%.2f  at ds=%.0f", AGER_THR, DMG_SIGMA, DMG_CUT, DS_C))
  logMsg("")
  logMsg(String.format("%-9s %-11s %-24s %8s %8s %9s %9s", "mouse","cond","role","tissue%","AGER+%","DAMAGED%","otsu"))
  def chNames = ["DAPI(0)", "FITC/KRT5(1)", "Cy3/AGER(2)", "Cy5/PDPN(3)"]
  def calib = []
  SLIDES.each { sl ->
    def path = DIR + sl.file + ".vsi"
    def server = ImageServers.buildServer(new File(path).toURI(), "--series", "" + pickSeries(path))
    double pxUm8 = server.getPixelCalibration().getPixelWidthMicrons() * DS_C
    def lc = lockedCompartments(server, pxUm8)
    logMsg(String.format("%-9s %-11s %-24s %8.2f %8.2f %9.2f %9.1f",
        sl.mouse, sl.cond, sl.role, lc.tissuePct, lc.agerPct, lc.dmgPct, lc.otsu))
    calib << [mouse: sl.mouse, cond: sl.cond, dmgPct: lc.dmgPct, tissuePct: lc.tissuePct, agerPct: lc.agerPct]
    if (sl.cond == "uninfected") {
      short[][] cs = (short[][]) lc.chans
      int n = (int) lc.w * (int) lc.h
      logMsg("   per-channel raw uint16 percentiles INSIDE the locked tissue mask (control):")
      (0..3).each { int c ->
        def fp = new FloatProcessor((int) lc.w, (int) lc.h)
        float[] o = (float[]) fp.getPixels()
        for (int i = 0; i < n; i++) o[i] = (float) (cs[c][i] & 0xFFFF)
        def qs = [0.05d, 0.25d, 0.50d, 0.75d, 0.90d, 0.95d, 0.99d]
        def vals = qs.collect { Morph.percentileWithin(o, (byte[]) lc.tissue, it) }
        logMsg(String.format("     %-14s p5=%6.0f p25=%6.0f p50=%6.0f p75=%6.0f p90=%6.0f p95=%6.0f p99=%6.0f  otsu=%.0f",
            chNames[c], vals[0], vals[1], vals[2], vals[3], vals[4], vals[5], vals[6],
            Morph.otsuAll(o)))
      }
    }
    server.close()
  }
  logMsg("")
  logMsg("Published values to reproduce: het m4-2 0.93, hom m6 0.18, het m4-1 6.71, hom m2 4.68")
  if (outDir != null) new File(outDir, "calibration.json").setText(GsonTools.getInstance(true).toJson(calib), "UTF-8")
  return
}

// ---------------------------------------------------------------------------
// SWEEP -- resolution dependence, measured on systematically placed windows
// inside the locked tissue mask, separately for damaged and intact.
// ---------------------------------------------------------------------------
if (MODE == "sweep") {
  if (T_DAPI < 0) fail("IFQ_M2_THR_DAPI is required for sweep")
  def dsList = SWEEP_DS.split(",").collect { Double.parseDouble(it.trim()) }
  def maskDefs = [ [name: "nuc", ch: [0] as int[], thr: [T_DAPI] as double[]],
                   [name: "dp",  ch: [0, 3] as int[], thr: [T_DAPI, T_PDPN] as double[]],
                   [name: "all", ch: [0, 1, 2, 3] as int[], thr: [T_DAPI, T_KRT5, T_AGER, T_PDPN] as double[]] ]
  def rows = []
  SLIDES.each { sl ->
    def path = DIR + sl.file + ".vsi"
    def server = ImageServers.buildServer(new File(path).toURI(), "--series", "" + pickSeries(path))
    double pxUm0 = server.getPixelCalibration().getPixelWidthMicrons()
    def lc = lockedCompartments(server, pxUm0 * DS_C)
    int cw = (int) lc.w, chh = (int) lc.h
    byte[] tissue8 = (byte[]) lc.tissue, dmg8 = (byte[]) lc.dmg
    logMsg(String.format("SWEEP %s  damaged=%.2f%%", sl.mouse, lc.dmgPct))
    // choose SWEEP_N windows on a systematic grid, keeping the ones with the
    // highest locked-tissue coverage; and separately the most-damaged ones.
    int side8 = (int) Math.round(SWEEP_WIN / DS_C)
    def cands = []
    int gstep = Math.max(1, (int) (side8))
    for (int gy = 0; gy + side8 <= chh; gy += gstep) for (int gx = 0; gx + side8 <= cw; gx += gstep) {
      long t = 0L, dd = 0L
      for (int y = gy; y < gy + side8; y++) { int row = y * cw
        for (int x = gx; x < gx + side8; x++) { if ((tissue8[row+x]&0xFF)>127) { t++; if ((dmg8[row+x]&0xFF)>127) dd++ } } }
      if (t > 0.5 * side8 * side8) cands << [gx: gx, gy: gy, t: t, d: dd]
    }
    cands = cands.sort { -it.t }
    def picked = []
    // systematic: take every (size/N)th of the tissue-rich windows
    int stride = Math.max(1, (int) (cands.size() / Math.max(1, SWEEP_N)))
    for (int i = 0; i < cands.size() && picked.size() < SWEEP_N; i += stride) picked << cands[i]
    // plus the 3 most damaged windows, if any damage exists
    def dmgSorted = cands.sort { -it.d }
    int added = 0
    for (int i = 0; i < dmgSorted.size() && added < 3; i++) {
      if (dmgSorted[i].d > 0.05 * dmgSorted[i].t && !picked.any { it.gx == dmgSorted[i].gx && it.gy == dmgSorted[i].gy }) {
        picked << dmgSorted[i]; added++ }
    }
    logMsg("  " + picked.size() + " windows of " + SWEEP_WIN + " full-res px")
    dsList.each { double ds ->
      double px = pxUm0 * ds
      double[][] agg = new double[4][12]   // [maskIdx][...] pooled over windows; idx3 unused
      // acc layout: 0 roiPx,1 tisPx,2 airPx,3 chordLen,4 chordN,5 testline,6 trans,
      //             7 edmSum,8 edmPx,9..? perimeter n4 handled separately
      double[][] per = new double[4][4]
      double[][] mliDir = new double[4][8]  // per direction len,n x4
      picked.each { win ->
        int fx = (int) Math.round(win.gx * DS_C), fy = (int) Math.round(win.gy * DS_C)
        def rb = readAll(server, ds, fx, fy, SWEEP_WIN, SWEEP_WIN)
        int bw = (int) rb.w, bh = (int) rb.h, n = bw * bh
        short[][] cs = (short[][]) rb.chans
        // ROI = locked tissue mask, nearest-neighbour from the ds8 grid
        byte[] roi = new byte[n]; byte[] lab = new byte[n]
        double scale = DS_C / ds
        for (int y = 0; y < bh; y++) { int gy = win.gy + (int) (y / scale); if (gy >= chh) continue
          int grow = gy * cw, brow = y * bw
          for (int x = 0; x < bw; x++) { int gx = win.gx + (int) (x / scale); if (gx >= cw) continue
            if ((tissue8[grow+gx]&0xFF) > 127) { roi[brow+x] = (byte)255; lab[brow+x] = (byte)1 } } }
        maskDefs.eachWithIndex { md, int mi ->
          byte[] tis = new byte[n]
          Morph.orThreshold(cs, (int[]) md.ch, (double[]) md.thr, n, tis)
          double[][] a = new double[2][3]; Morph.areaAccum(tis, roi, lab, bw, bh, 0, 0, bw, bh, a)
          agg[mi][0] += a[1][0]; agg[mi][1] += a[1][1]; agg[mi][2] += a[1][2]
          int[][] dirs = [[1,0],[0,1],[1,1],[1,-1]] as int[][]
          dirs.eachWithIndex { int[] dd, int k ->
            double[][] c = new double[2][6]
            Morph.chordScan(tis, roi, lab, bw, bh, 0, 0, bw, bh, dd[0], dd[1], px, c)
            mliDir[mi][2*k] += c[1][0]; mliDir[mi][2*k+1] += c[1][1]
            agg[mi][3] += c[1][0]; agg[mi][4] += c[1][1]; agg[mi][5] += c[1][2]; agg[mi][6] += c[1][3]
          }
          double[][] p = new double[2][4]; Morph.croftonCrossings(tis, roi, lab, bw, bh, 0, 0, bw, bh, p)
          for (int q = 0; q < 4; q++) per[mi][q] += p[1][q]
          double[][] e = new double[2][2]; Morph.edmAccum(tis, roi, lab, bw, bh, 0, 0, bw, bh, px, e)
          agg[mi][7] += e[1][0]; agg[mi][8] += e[1][1]
        }
      }
      maskDefs.eachWithIndex { md, int mi ->
        double roiA = agg[mi][0] * px * px
        double tisA = agg[mi][1] * px * px
        double airFrac = agg[mi][2] / Math.max(1.0d, agg[mi][0])
        double mliPooled = agg[mi][3] / Math.max(1.0d, agg[mi][4])
        double s = 0.0d; int nd = 0
        for (int k = 0; k < 4; k++) if (mliDir[mi][2*k+1] > 0) { s += mliDir[mi][2*k]/mliDir[mi][2*k+1]; nd++ }
        double mliOrient = nd > 0 ? s / nd : Double.NaN
        double mliInd = 2.0d * agg[mi][5] / Math.max(1.0d, agg[mi][6])
        double perimUm = Morph.croftonPerimeterUm(per[mi], px)
        double thickEdm = 4.0d * agg[mi][7] / Math.max(1.0d, agg[mi][8])
        double thick2ab = 2.0d * tisA / Math.max(1e-9d, perimUm)
        double sv = (4.0d / Math.PI) * perimUm / Math.max(1e-9d, roiA)
        rows << [mouse: sl.mouse, cond: sl.cond, mask: md.name, ds: ds, px_um: px,
                 roi_mm2: roiA/1e6, tissue_frac: agg[mi][1]/Math.max(1.0d,agg[mi][0]),
                 air_frac: airFrac, mli_pooled_um: mliPooled, mli_orient_um: mliOrient,
                 mli_indirect_um: mliInd, septum_edm_um: thickEdm, septum_2ab_um: thick2ab,
                 surf_density_per_um: sv, perim_mm: perimUm/1000.0]
        logMsg(String.format("  ds=%-4.0f %-4s px=%.3f  airFrac=%.4f  MLIpool=%7.2f MLIorient=%7.2f MLIind=%7.2f  septEDM=%6.2f sept2AB=%6.2f  Sv=%.4f",
            ds, md.name, px, airFrac, mliPooled, mliOrient, mliInd, thickEdm, thick2ab, sv))
      }
    }
    server.close()
  }
  if (outDir != null) {
    def cols = ["mouse","cond","mask","ds","px_um","roi_mm2","tissue_frac","air_frac",
                "mli_pooled_um","mli_orient_um","mli_indirect_um","septum_edm_um","septum_2ab_um",
                "surf_density_per_um","perim_mm"]
    def sb = new StringBuilder(csvR(cols)).append("\n")
    rows.each { r -> sb.append(csvR(cols.collect { r[it] })).append("\n") }
    new File(outDir, "sweep_resolution.csv").setText(sb.toString(), "UTF-8")
    logMsg("wrote " + new File(outDir, "sweep_resolution.csv").getAbsolutePath())
  }
  return
}

// ---------------------------------------------------------------------------
// RUN
// ---------------------------------------------------------------------------
if (OUT.isEmpty()) fail("IFQ_M2_OUT is required in run mode")
if (T_DAPI < 0) fail("IFQ_M2_THR_DAPI is required (raw uint16). Lock it from CONTROLS via IFQ_M2_MODE=calibrate.")
if (T_PDPN < 0) fail("IFQ_M2_THR_PDPN is required")
if (T_KRT5 < 0) fail("IFQ_M2_THR_KRT5 is required")

def MASKS = [ [name: "nuc", ch: [0] as int[],          thr: [T_DAPI] as double[],                    indep: true ],
              [name: "dp",  ch: [0,3] as int[],        thr: [T_DAPI, T_PDPN] as double[],            indep: true ],
              [name: "all", ch: [0,1,2,3] as int[],    thr: [T_DAPI,T_KRT5,T_AGER,T_PDPN] as double[], indep: false] ]

def allRows = []
def qc = []
SLIDES.each { sl ->
  long tSlide = System.currentTimeMillis()
  def path = DIR + sl.file + ".vsi"
  def server = ImageServers.buildServer(new File(path).toURI(), "--series", "" + pickSeries(path))
  double pxUm0 = server.getPixelCalibration().getPixelWidthMicrons()
  double pxUm0h = server.getPixelCalibration().getPixelHeightMicrons()
  int W = server.getWidth(), H = server.getHeight()
  double px8 = pxUm0 * DS_C, pxF = pxUm0 * DS_F
  logMsg("")
  logMsg("======== " + sl.file + "  (" + W + "x" + H + ", " + String.format("%.4f", pxUm0) + " um/px)")
  def lc = lockedCompartments(server, px8)
  int cw = (int) lc.w, chh = (int) lc.h
  byte[] tissue8 = (byte[]) lc.tissue, dmg8 = (byte[]) lc.dmg
  logMsg(String.format("  LOCKED detector: tissue=%.2f%%  AGER+=%.2f%%  DAMAGED=%.2f%%  (Otsu=%.1f)",
      lc.tissuePct, lc.agerPct, lc.dmgPct, lc.otsu))

  int nRegions = PARTITION ? 2 : 1
  byte[] labC = new byte[cw * chh]
  for (int i = 0; i < labC.length; i++) {
    if ((tissue8[i] & 0xFF) <= 127) continue
    labC[i] = PARTITION ? (((dmg8[i] & 0xFF) > 127) ? (byte) 1 : (byte) 2) : (byte) 1
  }
  double[] regionPx = new double[nRegions + 1]
  Morph.countByRegion(null, labC, cw * chh, regionPx)
  logMsg(String.format("  regions (ds8 px): " + (1..nRegions).collect { r ->
      (PARTITION ? (r == 1 ? "damaged" : "intact") : "all") + "=" +
      String.format("%.3f mm2", regionPx[r] * px8 * px8 / 1e6) }.join("  ")))

  // accumulators, per mask per region
  int nM = MASKS.size()
  double[][][] areaAcc = new double[nM][nRegions+1][3]
  double[][][] perAcc  = new double[nM][nRegions+1][4]
  double[][][] chAcc   = new double[nM][nRegions+1][6]     // pooled 4 directions
  double[][][][] chDir = new double[nM][4][nRegions+1][2]  // per direction: len, n
  double[][][] edmAcc  = new double[nM][nRegions+1][2]
  byte[][] tisAnyC = new byte[nM][cw * chh]

  int fw = (int) Math.ceil(W / DS_F), fh = (int) Math.ceil(H / DS_F)
  int haloPx = (int) Math.ceil(HALO_UM / pxF)
  int coreF = ((int) (CORE_PX / K)) * K
  if (coreF <= 0) fail("block core smaller than K")
  int nBlocks = 0, nSkip = 0
  boolean capped = false
  long tF = System.currentTimeMillis()
  int bi = -1
  for (int by = 0; by < fh && !capped; by += coreF) {
    bi++
    int bj = -1
    for (int bx = 0; bx < fw; bx += coreF) {
      bj++
      if (MAXBLK > 0 && nBlocks >= MAXBLK) { capped = true; break }
      if (STRIDE > 1 && !((bi % STRIDE == 0) && (bj % STRIDE == 0))) continue
      int cwid = Math.min(coreF, fw - bx), chgt = Math.min(coreF, fh - by)
      // quick reject on the ds8 label grid
      int gx0 = bx.intdiv(K), gy0 = by.intdiv(K)
      int gx1 = Math.min(cw, (bx + cwid).intdiv(K) + 1), gy1 = Math.min(chh, (by + chgt).intdiv(K) + 1)
      long inRoi = 0L
      for (int yy = gy0; yy < gy1; yy++) { int row = yy * cw
        for (int xx = gx0; xx < gx1; xx++) if ((labC[row + xx] & 0xFF) != 0) inRoi++ }
      if (inRoi == 0L) { nSkip++; continue }

      int ex = Math.max(0, bx - haloPx), ey = Math.max(0, by - haloPx)
      int ex2 = Math.min(fw, bx + cwid + haloPx), ey2 = Math.min(fh, by + chgt + haloPx)
      int ew = ex2 - ex, eh = ey2 - ey
      def rb = readAll(server, DS_F, (int) Math.round(ex * DS_F), (int) Math.round(ey * DS_F),
                       (int) Math.round(ew * DS_F), (int) Math.round(eh * DS_F))
      int bw = (int) rb.w, bh = (int) rb.h, n = bw * bh
      if (Math.abs(bw - ew) > 1 || Math.abs(bh - eh) > 1)
        fail("fine block raster " + bw + "x" + bh + " != requested " + ew + "x" + eh)
      short[][] cs = (short[][]) rb.chans
      byte[] roiB = new byte[n], labB = new byte[n]
      Morph.upsampleLabels(tissue8, labC, cw, chh, ex, ey, K, bw, bh, roiB, labB)
      int lx0 = bx - ex, ly0 = by - ey
      int lx1 = Math.min(bw, lx0 + cwid), ly1 = Math.min(bh, ly0 + chgt)
      MASKS.eachWithIndex { md, int mi ->
        byte[] tis = new byte[n]
        Morph.orThreshold(cs, (int[]) md.ch, (double[]) md.thr, n, tis)
        Morph.areaAccum(tis, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, areaAcc[mi])
        Morph.croftonCrossings(tis, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, perAcc[mi])
        int[][] dirs = [[1,0],[0,1],[1,1],[1,-1]] as int[][]
        dirs.eachWithIndex { int[] dd, int k ->
          double[][] c = new double[nRegions+1][6]
          Morph.chordScan(tis, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, dd[0], dd[1], pxF, c)
          for (int r = 1; r <= nRegions; r++) {
            for (int q = 0; q < 6; q++) chAcc[mi][r][q] += c[r][q]
            chDir[mi][k][r][0] += c[r][0]; chDir[mi][k][r][1] += c[r][1]
          }
        }
        Morph.edmAccum(tis, roiB, labB, bw, bh, lx0, ly0, lx1, ly1, pxF, edmAcc[mi])
        Morph.maxDownsampleTissue(tis, roiB, bw, bh, lx0, ly0, lx1, ly1, ex, ey, K, cw, chh, tisAnyC[mi])
      }
      nBlocks++
      if (nBlocks % 10 == 0) logMsg("    ... " + nBlocks + " blocks (" + (System.currentTimeMillis()-tF)/1000 + " s)")
    }
  }
  logMsg("  fine pass ds=" + DS_F + ": " + nBlocks + " blocks, " + nSkip + " skipped, halo=" + haloPx +
         " px, " + (System.currentTimeMillis()-tF)/1000 + " s" + (capped ? "  *** CAPPED ***" : ""))

  // topology on the connectivity-preserving max-downsampled mask
  double[][][] compAcc = new double[nM][nRegions+1][3]
  MASKS.eachWithIndex { md, int mi ->
    Morph.airspaceComponents(tisAnyC[mi], tissue8, labC, cw, chh,
        (long) Math.round(BIG_UM2 / (px8 * px8)), nRegions, compAcc[mi])
  }

  double a8 = px8 * px8, aF = pxF * pxF
  (1..nRegions).each { int r ->
    String regionName = PARTITION ? (r == 1 ? "parenchyma_damaged" : "parenchyma_intact") : "parenchyma_all"
    def row = [:]
    row["image"] = sl.file; row["region"] = regionName; row["section_id"] = sl.file
    row["mouse_id"] = sl.mouse; row["genotype"] = sl.geno; row["condition"] = sl.cond; row["panel"] = "LEFT"
    row["region_area_um2"] = regionPx[r] * a8
    MASKS.eachWithIndex { md, int mi ->
      String m = "morph" + md.name
      row[m + "_tissue_positive_area_um2"] = areaAcc[mi][r][1] * aF
      row[m + "_air_positive_area_um2"]    = areaAcc[mi][r][2] * aF
      row[m + "_finepass_positive_area_um2"] = areaAcc[mi][r][0] * aF
      row[m + "_airspacec_positive_area_um2"] = compAcc[mi][r][1] * a8
      row[m + "_airspacec_n_components"]      = compAcc[mi][r][0]
      row[m + "_airspacebig_positive_area_um2"] = compAcc[mi][r][2] * a8
      row["class_" + m + "_perimeter_um_count"]     = Morph.croftonPerimeterUm(perAcc[mi][r], pxF)
      row["class_" + m + "_chordlen_um_count"]      = chAcc[mi][r][0]
      row["class_" + m + "_chordn_count"]           = chAcc[mi][r][1]
      row["class_" + m + "_testline_um_count"]      = chAcc[mi][r][2]
      row["class_" + m + "_transition_count"]       = chAcc[mi][r][3]
      row["class_" + m + "_chordtrunc_count"]       = chAcc[mi][r][4]
      row["class_" + m + "_chordtrunclen_um_count"] = chAcc[mi][r][5]
      ["d0","d90","d45","d135"].eachWithIndex { String dn, int k ->
        row["class_" + m + "_chordlen" + dn + "_um_count"] = chDir[mi][k][r][0]
        row["class_" + m + "_chordn" + dn + "_count"]      = chDir[mi][k][r][1]
      }
      row["class_" + m + "_edmhalf_um_count"] = edmAcc[mi][r][0]
      row["class_" + m + "_tissuepx_count"]   = edmAcc[mi][r][1]
    }
    row["class_morph_rows_count"] = 1.0d
    // QC / provenance -- dropped by aggregate_to_mouse.py, kept in the slide CSV
    row["morph_px_fine_um"] = pxF
    row["morph_px_coarse_um"] = px8
    row["morph_ds_fine"] = DS_F
    row["morph_thr_dapi"] = T_DAPI; row["morph_thr_krt5"] = T_KRT5
    row["morph_thr_ager"] = T_AGER; row["morph_thr_pdpn"] = T_PDPN
    row["morph_locked_damaged_pct_of_tissue"] = lc.dmgPct
    row["morph_locked_tissue_pct"] = lc.tissuePct
    row["morph_locked_agerpos_pct"] = lc.agerPct
    row["morph_n_blocks"] = nBlocks
    row["morph_block_stride"] = STRIDE
    row["morph_coverage_complete"] = (!capped && STRIDE == 1).toString()
    row["morph_halo_px"] = haloPx
    allRows << row
  }
  qc << [slide: sl.file, mouse: sl.mouse, cond: sl.cond, W: W, H: H, px_um: pxUm0,
         ds_fine: DS_F, ds_coarse: DS_C, stride: STRIDE, n_blocks: nBlocks,
         locked_tissue_pct: lc.tissuePct, locked_ager_pct: lc.agerPct, locked_damaged_pct: lc.dmgPct,
         locked_otsu: lc.otsu, thr: [dapi: T_DAPI, krt5: T_KRT5, ager: T_AGER, pdpn: T_PDPN],
         seconds: (System.currentTimeMillis()-tSlide)/1000]
  logMsg("  slide done in " + (System.currentTimeMillis()-tSlide)/1000 + " s")
  server.close()
}

def cols = []
allRows.each { r -> r.keySet().each { if (!cols.contains(it)) cols << it } }
def sb = new StringBuilder(csvR(cols)).append("\n")
allRows.each { r -> sb.append(csvR(cols.collect { c -> r[c] })).append("\n") }
def outCsv = new File(outDir, "morphometry_slide_summary.csv")
outCsv.setText(sb.toString(), "UTF-8")
new File(outDir, "morphometry_manifest.json").setText(
    GsonTools.getInstance(true).toJson([schema_version: "0.2", stage: "morphometry",
        generated_utc: java.time.Instant.now().toString(), slides: qc]), "UTF-8")
logMsg("")
logMsg("Wrote " + allRows.size() + " rows -> " + outCsv.getAbsolutePath())
