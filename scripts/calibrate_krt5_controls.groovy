// CONTROL-ONLY CALIBRATION of the KRT5 positivity threshold.
//
// Reads ONLY the two UNINFECTED slides. The infected slides are never opened,
// so the threshold cannot be tuned on the outcome.
//
// WHERE THE NEGATIVE COMES FROM. There is no secondary-only control section for
// this dataset, so the negative has to be biological. Uninfected lung still
// contains genuinely KRT5+ conducting-airway basal cells, so "uninfected tissue"
// as a whole is NOT a negative.
//
// But INTACT ALVEOLAR PARENCHYMA of an uninfected animal is:
//   - no ectopic pods, because there was no injury;
//   - no airway basal cells, because airway epithelium is AGER-negative and
//     therefore falls in the DAMAGED compartment, not the intact one.
// So KRT5 signal inside intact control parenchyma is background --
// autofluorescence, bleed-through and camera noise. That is the negative
// distribution, and it is measurable without any manual annotation.
//
// This matters because the KRT5 channel was exposed ~949 ms against ~0.5-2 ms
// for the others, so its autofluorescence floor is a real concern.
//
// SELECTION RULE, declared before any number is read:
//   CONSTRAINT : KRT5+ area fraction inside INTACT parenchyma <= alpha,
//                for BOTH control slides independently (worst-of-both, so one
//                clean slide cannot buy tolerance for a dirty one).
//   OBJECTIVE  : subject to that, the LOWEST threshold. KRT5+ fraction is
//                monotone DECREASING in threshold, so the lowest admissible
//                threshold is the most sensitive operating point that still
//                meets the specificity floor. Ties break toward the flattest
//                local slope.
//
// SANITY CHECK, not part of the rule: KRT5+ fraction inside the DAMAGED
// compartment of the same control slides. In an uninfected animal that
// compartment is largely conducting airway, which IS genuinely KRT5+, so it
// should stay clearly above the intact compartment. If a threshold flattens
// both to zero it has killed real signal, not just background.
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

double DS       = 8.0d
// LOCKED damage parameters (docs/ECTOPIC_POD_ENDPOINT.md 4c)
double AGER_THR = 150.0d, DMG_SIGMA = 40.0d, DMG_CUT = 0.14d

def KRT5_THR = [40d,60d,80d,100d,125d,150d,175d,200d,250d,300d,350d,400d,500d,600d]
def ALPHAS   = [0.00001d, 0.00005d, 0.0001d, 0.0005d, 0.001d]   // fraction of intact area

// UNINFECTED ONLY. Do not add infected slides.
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

def store = [:]
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
  // tissue
  float[] fd = toF(get(0),2.0d)
  boolean[] all=new boolean[n]; java.util.Arrays.fill(all,true)
  double tThr = otsuWithin(fd, all)
  def tbp=new ByteProcessor(w,h); byte[] tb=(byte[])tbp.getPixels()
  for (int i=0;i<n;i++) if (fd[i]>=tThr) tb[i]=(byte)255
  def rf=new RankFilters(); rf.rank(tbp,4.0d,RankFilters.MAX); rf.rank(tbp,4.0d,RankFilters.MIN)
  boolean[] tissue=new boolean[n]; long tN=0
  for (int i=0;i<n;i++){ tissue[i]=(tb[i]&0xFF)>127; if(tissue[i]) tN++ }
  // damaged / intact at the LOCKED parameters
  float[] fa = toF(get(2),1.0d)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  for (int i=0;i<n;i++) d[i] = (tissue[i] && fa[i]>=AGER_THR) ? 1f : 0f
  new GaussianBlur().blurGaussian(dp, DMG_SIGMA/pxUm)
  float[] dens=(float[])dp.getPixels()
  boolean[] intact=new boolean[n], damaged=new boolean[n]
  long iN=0, dN=0
  for (int i=0;i<n;i++){
    if (!tissue[i]) continue
    if (dens[i] < DMG_CUT) { damaged[i]=true; dN++ } else { intact[i]=true; iN++ }
  }
  float[] fk = toF(get(1),1.0d)
  store[lbl] = [fk:fk, intact:intact, damaged:damaged, iN:iN, dN:dN,
                n:n, w:w, h:h, tN:tN, pxUm:pxUm]
  println String.format("CONTROL %s : tissue=%.1f%%  intact=%.2f%% of tissue  damaged=%.2f%% of tissue",
      lbl, 100.0*tN/n, 100.0*iN/tN, 100.0*dN/tN)
  server.close()
}
println ""

println "KRT5 BACKGROUND DISTRIBUTION inside INTACT control parenchyma (the negative):"
println String.format("%-9s %8s %8s %8s %8s %8s %8s %8s", "slide","p50","p90","p99","p99.9","p99.99","max","mean+5sd")
CONTROLS.each { lbl, nm ->
  def st = store[lbl]
  int[] hist=new int[4200]; double sum=0, sum2=0
  for (int i=0;i<st.n;i++) if (st.intact[i]) {
    int v=(int)Math.min(4199, Math.max(0, st.fk[i])); hist[v]++; sum+=v; sum2+=(double)v*v
  }
  long N=st.iN
  def pct={ double p-> long tgt=(long)Math.ceil(p*N); long a=0
    for (int v=0;v<hist.length;v++){ a+=hist[v]; if(a>=tgt) return v }; return hist.length-1 }
  int mx=0; for (int v=hist.length-1; v>=0; v--) if (hist[v]>0) { mx=v; break }
  double mean=sum/N, sd=Math.sqrt(Math.max(0, sum2/N-mean*mean))
  println String.format("%-9s %8d %8d %8d %8d %8d %8d %8.0f",
      lbl, pct(.50), pct(.90), pct(.99), pct(.999), pct(.9999), mx, mean+5*sd)
}
println ""

// The engine does NOT report raw thresholded pixels. IF_Quant_Pipeline.groovy
// runs filterBinaryMaskByArea(rawAreaMask, POD_MIN_AREA_UM2 = 50 um2) before
// measuring pod area, so connected components below 50 um2 never count.
// Background is scattered specks; pods are large connected structures. Omitting
// this filter would overstate the false-positive rate and pick a threshold far
// higher than the engine actually needs.
double MIN_COMPONENT_UM2 = 50.0d

/** 8-connected component labelling, keeping only components >= minPx. */
def filterByArea = { boolean[] mask, int w, int h, int minPx ->
  int n = w*h
  int[] lab = new int[n]
  boolean[] keep = new boolean[n]
  int[] stack = new int[n]
  int cur = 0
  for (int s = 0; s < n; s++) {
    if (!mask[s] || lab[s] != 0) continue
    cur++
    int sp = 0; stack[sp++] = s; lab[s] = cur
    int count = 0
    int head = 0
    int[] members = new int[16]; int mlen = 0
    while (sp > 0) {
      int p = stack[--sp]; count++
      if (mlen == members.length) { def t = new int[mlen*2]; System.arraycopy(members,0,t,0,mlen); members = t }
      members[mlen++] = p
      int px = p % w, py = (int)(p / w)
      for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
        if (dx == 0 && dy == 0) continue
        int nx = px+dx, ny = py+dy
        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
        int q = ny*w + nx
        if (mask[q] && lab[q] == 0) { lab[q] = cur; stack[sp++] = q }
      }
    }
    if (count >= minPx) for (int k = 0; k < mlen; k++) keep[members[k]] = true
  }
  return keep
}

def res=[:]
println "KRT5+ AREA FRACTION by threshold, AFTER the engine's 50 um2 component filter"
println "   [INTACT = false positives | DAMAGED = mostly airway, real]"
println String.format("%-7s %-24s %-24s", "thr", "INTACT (worst of both)", "DAMAGED (worst of both)")
KRT5_THR.each { thr ->
  def iw=[], dw=[]
  CONTROLS.each { lbl, nm ->
    def st=store[lbl]
    int w = st.w, h = st.h
    int minPx = (int)Math.max(1, Math.round(MIN_COMPONENT_UM2 / (st.pxUm*st.pxUm)))
    boolean[] pos = new boolean[st.n]
    for (int i=0;i<st.n;i++) pos[i] = (st.intact[i] || st.damaged[i]) && st.fk[i] >= thr
    boolean[] kept = filterByArea(pos, w, h, minPx)
    long ic=0, dc=0
    for (int i=0;i<st.n;i++){
      if (!kept[i]) continue
      if (st.intact[i]) ic++ else if (st.damaged[i]) dc++
    }
    double ifr=(double)ic/Math.max(1,st.iN), dfr=(double)dc/Math.max(1,st.dN)
    res[[thr,lbl]]=[ifr,dfr]; iw<<ifr; dw<<dfr
  }
  println String.format("%-7.0f %-24s %-24s", thr,
      String.format("%.6f", iw.max()), String.format("%.6f", dw.max()))
}
println ""

println "SELECTED THRESHOLDS (lowest threshold with BOTH controls' intact fraction <= alpha)"
println String.format("%-10s %-8s %12s %12s %14s", "alpha", "KRT5thr", "het m4-2", "hom m6", "damaged(worst)")
ALPHAS.each { a ->
  def ok = KRT5_THR.findAll { thr -> CONTROLS.every { lbl, nm -> res[[thr,lbl]][0] <= a } }
  if (ok.isEmpty()) { println String.format("%-10.5f  -- no threshold in the sweep meets this alpha --", a); return }
  double thr = ok.min()
  println String.format("%-10.5f %-8.0f %12.6f %12.6f %14.6f", a, thr,
      res[[thr,"het m4-2"]][0], res[[thr,"hom m6  "]][0],
      [res[[thr,"het m4-2"]][1], res[[thr,"hom m6  "]][1]].max())
}
println ""
println "Infected slides were NOT opened by this script."
