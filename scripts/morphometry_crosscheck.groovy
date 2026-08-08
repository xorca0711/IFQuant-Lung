// ============================================================================
// morphometry_crosscheck.groovy
// Does the AGER-density "damaged" mask actually mark ARCHITECTURALLY damaged
// tissue, or only weakly-stained tissue?
// ============================================================================
// WHY THIS MATTERS
//   The endpoint denominator rests on ONE marker. If regions the AGER-density
//   detector calls "damaged" are not also architecturally distorted, the
//   denominator is measuring staining rather than injury, and the endpoint is
//   built on sand. This is the independent check.
//
// THE CIRCULARITY TRAP, and how it is avoided
//   "Damaged" is DEFINED by low AGER density. If the architecture measures were
//   also computed from AGER, "damaged regions have less tissue" would be a
//   tautology. So every morphometric measure here is computed from the DAPI
//   channel ONLY. AGER is used solely to define the compartments being
//   compared, never to measure them.
//
//   Cost of that choice, stated plainly: DAPI marks nuclei, not the full septal
//   wall, so the "tissue" mask is nuclei-weighted and these are PROXIES for the
//   classical stereological quantities, not the quantities themselves. Mean
//   linear intercept from a single 2D section is in any case a biased estimator
//   of the 3D value. Directional comparison between compartments on the SAME
//   section is what this supports; absolute values are not publishable numbers.
//
// RUNS ENTIRELY FROM THE CHANNEL CACHE -- no .vsi access, so it works while the
// data volume is offline.
//   IFQ_CACHE_DIR  default X:\GitHub\IFQuant-Lung\.cache\slide_channels
//   IFQ_CACHE_DS   default 8
// ============================================================================

import ij.process.*
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.RankFilters
import java.nio.ByteBuffer
import java.nio.ByteOrder

def LOG = "[IFQ_MORPH]"
def logMsg = { String m -> println LOG + " " + m }
def envOr = { String n, String d -> def v = System.getenv(n); (v == null || v.trim().isEmpty()) ? d : v.trim() }

def CACHE = envOr("IFQ_CACHE_DIR", "X:\\GitHub\\IFQuant-Lung\\.cache\\slide_channels")
double DS = Double.parseDouble(envOr("IFQ_CACHE_DS", "8"))
// LOCKED damage parameters -- docs/ECTOPIC_POD_ENDPOINT.md 4c
double AGER_THR = 150.0d, DMG_SIGMA = 40.0d, DMG_CUT = 0.14d

def loadCache = { String stem ->
  def metaF = new File(CACHE, stem + "__ds" + (int) DS + ".json")
  def binF  = new File(CACHE, stem + "__ds" + (int) DS + ".raw")
  def meta = qupath.lib.io.GsonTools.getInstance().fromJson(metaF.getText("UTF-8"), Map.class)
  int w = meta.width as int, h = meta.height as int, nc = meta.n_channels as int, n = w*h
  short[][] ch = new short[nc][]
  def is = new BufferedInputStream(new FileInputStream(binF), 1 << 20)
  try {
    byte[] buf = new byte[n*2]
    for (int c = 0; c < nc; c++) {
      int off = 0
      while (off < buf.length) { int r = is.read(buf, off, buf.length-off); if (r < 0) break; off += r }
      short[] px = new short[n]
      ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(px)
      ch[c] = px
    }
  } finally { is.close() }
  return [ch: ch, w: w, h: h, pxUm: (meta.pixel_size_um_at_ds as double)]
}

def otsuWithin = { float[] f, boolean[] m ->
  float lo=Float.MAX_VALUE, hi=-Float.MAX_VALUE
  for (int i=0;i<f.length;i++) if (m[i]) { if (f[i]<lo) lo=f[i]; if (f[i]>hi) hi=f[i] }
  if (hi<=lo) return (double) lo
  int[] hh=new int[256]; double sc=255.0d/(hi-lo)
  for (int i=0;i<f.length;i++) if (m[i]) hh[(int)Math.round((f[i]-lo)*sc)]++
  return lo + new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hh)/sc
}
def blur = { short[] px, int w, int h, double sig ->
  def fp = new FloatProcessor(w,h); float[] o = (float[]) fp.getPixels()
  for (int i=0;i<px.length;i++) o[i] = (float)(px[i] & 0xFFFF)
  if (sig > 0) new GaussianBlur().blurGaussian(fp, sig)
  return (float[]) fp.getPixels()
}

/** Mean chord length through !solid pixels, along rows and columns separately.
 *  Chords touching the region border are TRUNCATED and excluded -- including
 *  them would bias the mean downwards. */
def meanChord = { boolean[] solid, boolean[] region, int w, int h, double pxUm ->
  long sumH = 0, nH = 0, sumV = 0, nV = 0, truncated = 0
  // horizontal
  for (int y = 0; y < h; y++) {
    int run = 0; boolean started = false, valid = true
    for (int x = 0; x < w; x++) {
      int i = y*w + x
      if (!region[i]) { if (started && run > 0) truncated++; run = 0; started = false; valid = true; continue }
      if (!solid[i]) { run++; started = true }
      else { if (started && run > 0 && valid) { sumH += run; nH++ }; run = 0; started = false; valid = true }
    }
    if (started && run > 0) truncated++
  }
  // vertical
  for (int x = 0; x < w; x++) {
    int run = 0; boolean started = false, valid = true
    for (int y = 0; y < h; y++) {
      int i = y*w + x
      if (!region[i]) { if (started && run > 0) truncated++; run = 0; started = false; valid = true; continue }
      if (!solid[i]) { run++; started = true }
      else { if (started && run > 0 && valid) { sumV += run; nV++ }; run = 0; started = false; valid = true }
    }
    if (started && run > 0) truncated++
  }
  long nAll = nH + nV
  return [mli_um: nAll > 0 ? (double)(sumH+sumV)/nAll*pxUm : Double.NaN,
          mli_h_um: nH > 0 ? (double)sumH/nH*pxUm : Double.NaN,
          mli_v_um: nV > 0 ? (double)sumV/nV*pxUm : Double.NaN,
          n_chords: nAll, n_truncated: truncated]
}

def SLIDES = [
  ["het m4-1", "PR8       ", "IFNg KO(het) 26.03.25 m4-1 pr8 infection"],
  ["hom m2  ", "PR8       ", "IFNg KO(hom) 26.03.25 m2 pr8 infection"],
  ["het m4-2", "uninfected", "IFNg KO(het) 26.03.25 m4-2 pr8 no infection"],
  ["hom m6  ", "uninfected", "IFNg KO(hom) 26.03.25 m6 pr8 no infection"],
]

logMsg "morphometry from the DAPI channel ONLY; AGER defines compartments, never measures them"
logMsg String.format("locked damage params: AGER>=%.0f  sigma=%.0f um  cutoff=%.2f", AGER_THR, DMG_SIGMA, DMG_CUT)
println ""
println String.format("%-9s %-11s %-9s %8s %9s %9s %9s %9s",
    "slide","condition","compart","area_mm2","solid_fr","MLI_um","MLI_h/v","bnd/um")

def rows = []
SLIDES.each { lbl, cond, stem ->
  def c = loadCache(stem)
  int w = c.w, h = c.h, n = w*h
  double pxUm = c.pxUm

  // --- tissue envelope from DAPI (same recipe as Stage 1)
  float[] fd = blur(c.ch[0], w, h, 2.0d)
  boolean[] all = new boolean[n]; java.util.Arrays.fill(all, true)
  double tThr = otsuWithin(fd, all)
  def tbp = new ByteProcessor(w,h); byte[] tb = (byte[]) tbp.getPixels()
  for (int i=0;i<n;i++) if (fd[i] >= tThr) tb[i] = (byte)255
  def rf = new RankFilters()
  rf.rank(tbp, 4.0d, RankFilters.MAX); rf.rank(tbp, 4.0d, RankFilters.MIN)   // close
  boolean[] envelope = new boolean[n]
  for (int i=0;i<n;i++) envelope[i] = (tb[i] & 0xFF) > 127

  // --- SOLID (septal) vs airspace INSIDE the envelope, from DAPI only.
  //     Unblurred DAPI at its own within-envelope Otsu: nuclei mark septa.
  float[] fdRaw = blur(c.ch[0], w, h, 0.6d)
  double sThr = otsuWithin(fdRaw, envelope)
  boolean[] solid = new boolean[n]
  for (int i=0;i<n;i++) solid[i] = envelope[i] && fdRaw[i] >= sThr

  // --- compartments from AGER density (locked params)
  float[] fa = blur(c.ch[2], w, h, 1.0d)
  def dp = new FloatProcessor(w,h); float[] d = (float[]) dp.getPixels()
  for (int i=0;i<n;i++) d[i] = (envelope[i] && fa[i] >= AGER_THR) ? 1f : 0f
  new GaussianBlur().blurGaussian(dp, DMG_SIGMA / pxUm)
  float[] dens = (float[]) dp.getPixels()
  boolean[] dmg = new boolean[n], intact = new boolean[n]
  for (int i=0;i<n;i++) {
    if (!envelope[i]) continue
    if (dens[i] < DMG_CUT) dmg[i] = true else intact[i] = true
  }

  [["damaged", dmg], ["intact", intact]].each { cname, reg ->
    long nReg = 0, nSolid = 0, bnd = 0
    for (int i=0;i<n;i++) if (reg[i]) { nReg++; if (solid[i]) nSolid++ }
    // boundary length: solid pixels with a non-solid 4-neighbour, inside region
    for (int y=1;y<h-1;y++) for (int x=1;x<w-1;x++) {
      int i = y*w+x
      if (!reg[i] || !solid[i]) continue
      if (!solid[i-1] || !solid[i+1] || !solid[i-w] || !solid[i+w]) bnd++
    }
    def ch2 = meanChord(solid, reg, w, h, pxUm)
    double areaMm2 = nReg * pxUm * pxUm / 1e6
    double solidFr = nReg > 0 ? (double) nSolid / nReg : 0
    double bndDens = nReg > 0 ? (double) bnd * pxUm / (nReg * pxUm * pxUm) : 0
    rows << [slide: lbl, cond: cond.trim(), compart: cname, area: areaMm2,
             solid: solidFr, mli: ch2.mli_um, mliH: ch2.mli_h_um, mliV: ch2.mli_v_um,
             bnd: bndDens, nch: ch2.n_chords]
    println String.format("%-9s %-11s %-9s %8.2f %9.4f %9.2f %9.2f %9.4f",
        lbl, cond, cname, areaMm2, solidFr, ch2.mli_um, ch2.mli_h_um/Math.max(1e-9,ch2.mli_v_um), bndDens)
  }
}

println ""
println "DAMAGED vs INTACT, within each slide (the cross-check)"
println String.format("%-9s %-11s %10s %10s %10s", "slide","condition","solid_ratio","MLI_ratio","bnd_ratio")
SLIDES.each { lbl, cond, stem ->
  def dR = rows.find { it.slide == lbl && it.compart == "damaged" }
  def iR = rows.find { it.slide == lbl && it.compart == "intact" }
  if (dR == null || iR == null) return
  println String.format("%-9s %-11s %10.3f %10.3f %10.3f", lbl, cond,
      iR.solid > 0 ? dR.solid/iR.solid : Double.NaN,
      iR.mli   > 0 ? dR.mli/iR.mli     : Double.NaN,
      iR.bnd   > 0 ? dR.bnd/iR.bnd     : Double.NaN)
}
println ""
println "ratio = damaged / intact.  1.0 means the compartments are architecturally"
println "indistinguishable, which would mean the AGER denominator is NOT tracking injury."
