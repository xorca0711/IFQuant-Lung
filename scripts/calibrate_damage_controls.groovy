// CONTROL-ONLY CALIBRATION of the damaged-area parameters.
//
// This script reads ONLY the two UNINFECTED slides. The infected slides are not
// opened, so the chosen operating point cannot be tuned on the outcome.
//
// SELECTION RULE, declared before any number is looked at:
//   Uninfected lung has an intact AT1 sheet, so its damaged fraction is the
//   FALSE-POSITIVE RATE of the damage detector.
//   CONSTRAINT  : max(damaged% over BOTH control slides) <= alpha
//   OBJECTIVE   : subject to that, take the LARGEST cutoff -- damaged% is
//                 monotone increasing in cutoff, so the largest admissible
//                 cutoff is the most sensitive operating point that still meets
//                 the specificity floor. Ties broken toward the flattest local
//                 slope (a plateau is less sensitive to the exact value).
// Both controls must pass, not their mean: one clean slide must not buy
// tolerance for a dirty one.
import qupath.lib.images.servers.ImageServers
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS
import ij.process.*
import ij.plugin.filter.RankFilters
import ij.plugin.filter.GaussianBlur
import java.awt.image.DataBufferUShort
import java.awt.image.BandedSampleModel

double DS = 8.0d
def AGER_THR = [150.0d, 200.0d, 250.0d, 300.0d]
def SIGMA_UM = [10.0d, 20.0d, 30.0d, 40.0d, 60.0d, 80.0d]
def CUTOFFS  = [0.02d,0.04d,0.06d,0.08d,0.10d,0.14d,0.18d,0.22d,0.26d,0.30d,0.35d,0.40d,0.50d]
def ALPHAS   = [0.005d, 0.01d, 0.02d, 0.05d]

// UNINFECTED ONLY. Do not add infected slides to this list.
def CONTROLS = [
  ["het m4-2", "IFNg KO(het) 26.03.25 m4-2 pr8 no infection"],
  ["hom m6  ", "IFNg KO(hom) 26.03.25 m6 pr8 no infection"],
]
def DIR = "D:/Confocal_Images/20260806_CW/20260806_CW/"

def pickSeries = { String p ->
  def r = new ImageReader(); r.setFlattenedResolutions(false)
  IMetadata m = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(m)
  r.setId(p); int sel = -1
  for (int s = 0; s < r.getSeriesCount(); s++) {
    r.setSeries(s); r.setResolution(0)
    def pw = m.getPixelsPhysicalSizeX(s); if (pw == null) continue
    double um = pw.value(UNITS.MICROMETER).doubleValue()
    if (r.getEffectiveSizeC()==4 && r.getSizeZ()==1 && um>0 && um<=0.5) sel = s
  }
  r.close(); return sel
}
def otsuWithin = { float[] f, boolean[] m ->
  float lo=Float.MAX_VALUE, hi=-Float.MAX_VALUE
  for (int i=0;i<f.length;i++) if (m[i]) { if (f[i]<lo) lo=f[i]; if (f[i]>hi) hi=f[i] }
  if (hi<=lo) return (double) lo
  int[] hh=new int[256]; double sc=255.0d/(hi-lo)
  for (int i=0;i<f.length;i++) if (m[i]) hh[(int)Math.round((f[i]-lo)*sc)]++
  return lo + new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hh)/sc
}

def data = [:]
CONTROLS.each { lbl, name ->
  def path = DIR + name + ".vsi"
  def server = ImageServers.buildServer(new File(path).toURI(), "--series", ""+pickSeries(path))
  double pxUm = server.getPixelCalibration().getPixelWidthMicrons()*DS
  def im = server.readRegion(DS,0,0,server.getWidth(),server.getHeight())
  def ras = im.getRaster(); int w=im.getWidth(), h=im.getHeight(), n=w*h
  def dbuf = ras.getDataBuffer()
  def get = { int c ->
    def sm = ras.getSampleModel()
    if ((dbuf instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
        sm.getBankIndices()[c]==c && sm.getScanlineStride()==w) return ((DataBufferUShort)dbuf).getData(c)
    int[] t=new int[n]; ras.getSamples(0,0,w,h,c,t); short[] o=new short[n]
    for (int i=0;i<n;i++) o[i]=(short)t[i]; return o
  }
  def toF = { short[] px, double sig ->
    def fp=new FloatProcessor(w,h); float[] o=(float[])fp.getPixels()
    for (int i=0;i<n;i++) o[i]=(float)(px[i]&0xFFFF)
    if (sig>0) new GaussianBlur().blurGaussian(fp,sig); return (float[])fp.getPixels()
  }
  float[] fd = toF(get(0),2.0d)
  boolean[] all=new boolean[n]; java.util.Arrays.fill(all,true)
  double tThr = otsuWithin(fd, all)
  def tbp=new ByteProcessor(w,h); byte[] tb=(byte[])tbp.getPixels()
  for (int i=0;i<n;i++) if (fd[i]>=tThr) tb[i]=(byte)255
  def rf=new RankFilters(); rf.rank(tbp,4.0d,RankFilters.MAX); rf.rank(tbp,4.0d,RankFilters.MIN)
  boolean[] tissue=new boolean[n]; long tN=0
  for (int i=0;i<n;i++){ tissue[i]=(tb[i]&0xFF)>127; if(tissue[i]) tN++ }
  data[lbl] = [fa: toF(get(2),1.0d), tissue: tissue, tN: tN, n: n, w: w, h: h, pxUm: pxUm]
  println String.format("loaded CONTROL %s : tissue=%.1f%%  (%.2f um/px)", lbl, 100.0*tN/n, pxUm)
  server.close()
}
println ""

// damagedPct[thr][sigma][cutoff][slide]
def result = [:]
AGER_THR.each { thr -> SIGMA_UM.each { sg ->
  CONTROLS.each { lbl, nm ->
    def st = data[lbl]
    def dp = new FloatProcessor(st.w, st.h); float[] d=(float[])dp.getPixels()
    for (int i=0;i<st.n;i++) d[i] = (st.tissue[i] && st.fa[i] >= thr) ? 1f : 0f
    new GaussianBlur().blurGaussian(dp, sg / st.pxUm)
    float[] dens=(float[])dp.getPixels()
    CUTOFFS.each { cut ->
      long dmg=0
      for (int i=0;i<st.n;i++) if (st.tissue[i] && dens[i] < cut) dmg++
      result[[thr,sg,cut,lbl]] = 100.0d*dmg/Math.max(1,st.tN)
    }
  }
}}

println "CONTROL damaged% (worst of the two control slides) -- this is the FALSE POSITIVE RATE"
println String.format("%-7s %-7s %s", "AGERthr", "sigma", CUTOFFS.collect{ String.format("%6.2f",it) }.join(" "))
AGER_THR.each { thr -> SIGMA_UM.each { sg ->
  def cells = CUTOFFS.collect { cut ->
    double worst = CONTROLS.collect { lbl, nm -> result[[thr,sg,cut,lbl]] }.max()
    String.format("%6.2f", worst)
  }
  println String.format("%-7.0f %-7.0f %s", thr, sg, cells.join(" "))
}}
println ""

println "SELECTED OPERATING POINTS (largest cutoff with BOTH controls <= alpha)"
println String.format("%-7s %-7s %-7s %-8s %8s %8s %8s", "alpha", "AGERthr", "sigma", "cutoff", "het m4-2", "hom m6", "slope")
ALPHAS.each { a ->
  def best = null
  AGER_THR.each { thr -> SIGMA_UM.each { sg ->
    def ok = CUTOFFS.findAll { cut ->
      CONTROLS.every { lbl, nm -> result[[thr,sg,cut,lbl]] <= a*100.0d }
    }
    if (ok.isEmpty()) return
    double cut = ok.max()
    int idx = CUTOFFS.indexOf(cut)
    double slope = 0.0d
    if (idx > 0) {
      double w1 = CONTROLS.collect{ lbl,nm -> result[[thr,sg,cut,lbl]] }.max()
      double w0 = CONTROLS.collect{ lbl,nm -> result[[thr,sg,CUTOFFS[idx-1],lbl]] }.max()
      slope = (w1-w0)/(cut-CUTOFFS[idx-1])
    }
    // objective: largest admissible cutoff; tie-break on flattest slope
    if (best == null || cut > best.cut || (cut == best.cut && slope < best.slope))
      best = [thr:thr, sg:sg, cut:cut, slope:slope]
  }}
  if (best == null) { println String.format("%-7.3f  -- no setting meets this alpha --", a); return }
  println String.format("%-7.3f %-7.0f %-7.0f %-8.2f %8.3f %8.3f %8.2f",
      a, best.thr, best.sg, best.cut,
      result[[best.thr,best.sg,best.cut,"het m4-2"]],
      result[[best.thr,best.sg,best.cut,"hom m6  "]], best.slope)
}
println ""
println "Infected slides were NOT opened by this script."
