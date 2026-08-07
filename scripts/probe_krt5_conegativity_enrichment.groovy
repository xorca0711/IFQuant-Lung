// IS THE CO-NEGATIVITY GAIN REAL, OR DEFINITIONAL?
//
// Claim under test: requiring PDPN-/AGER- rejects KRT5 autofluorescence.
// Competing explanation: "intact" parenchyma is DEFINED as AGER-dense, so
// requiring AGER < t removes most of the area by construction and would shrink
// ANY area fraction, autofluorescent or not.
//
// DISCRIMINATING STATISTIC -- the enrichment ratio
//     R = P(co-negative | KRT5 bright) / P(co-negative)
//   R << 1  -> KRT5-bright pixels are preferentially CO-BRIGHT, i.e. genuine
//             autofluorescence, and the constraint is doing real work.
//   R ~= 1  -> the constraint removes area indiscriminately; the apparent gain
//             is definitional and buys nothing.
//
// SENSITIVITY CHECK the controls CAN provide: conducting airway is genuinely
// KRT5+ and genuinely PDPN-/AGER-, and it is present in uninfected lung inside
// the DAMAGED compartment. If the constraint preserves airway KRT5 while
// killing intact-parenchyma KRT5, it discriminates signal from background.
// If it kills both, it is just an area filter.
//
// Infected slides are NOT opened.
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
double AGER_THR = 150.0d, DMG_SIGMA = 40.0d, DMG_CUT = 0.14d
double KRT5_BRIGHT = 200.0d
def CEILINGS = [100d, 150d, 200d, 300d]

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
  boolean[] tissue=new boolean[n]
  for (int i=0;i<n;i++) tissue[i]=(tb[i]&0xFF)>127
  float[] fa = toF(get(2),1.0d), fk = toF(get(1),1.0d), fpd = toF(get(3),1.0d)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  for (int i=0;i<n;i++) d[i] = (tissue[i] && fa[i]>=AGER_THR) ? 1f : 0f
  new GaussianBlur().blurGaussian(dp, DMG_SIGMA/pxUm)
  float[] dens=(float[])dp.getPixels()
  boolean[] intact=new boolean[n], damaged=new boolean[n]
  long iN=0, dN=0
  for (int i=0;i<n;i++){
    if (!tissue[i]) continue
    if (dens[i] >= DMG_CUT) { intact[i]=true; iN++ } else { damaged[i]=true; dN++ }
  }

  println "################ " + lbl + " ################"
  println String.format("intact=%d px   damaged(mostly airway)=%d px", iN, dN)
  println ""
  println "IN INTACT PARENCHYMA (pure background -- no pods, no airway):"
  println String.format("%-8s %14s %14s %14s %14s %10s %10s",
      "ceiling", "P(PDPN<t)", "P(PDPN<t|K+)", "P(AGER<t)", "P(AGER<t|K+)", "R_pdpn", "R_ager")
  CEILINGS.each { ct ->
    long nb=0, pAll=0, aAll=0, pB=0, aB=0
    for (int i=0;i<n;i++){
      if (!intact[i]) continue
      boolean pn = fpd[i] < ct, an = fa[i] < ct
      if (pn) pAll++
      if (an) aAll++
      if (fk[i] >= KRT5_BRIGHT) { nb++; if (pn) pB++; if (an) aB++ }
    }
    double mp=(double)pAll/iN, ma=(double)aAll/iN
    double cp=nb>0?(double)pB/nb:0d, ca=nb>0?(double)aB/nb:0d
    println String.format("%-8.0f %14.6f %14.6f %14.6f %14.6f %10.3f %10.3f",
        ct, mp, cp, ma, ca, mp>0?cp/mp:Double.NaN, ma>0?ca/ma:Double.NaN)
  }
  println ""
  println "SENSITIVITY CHECK -- fraction of genuinely KRT5+ AIRWAY pixels PRESERVED"
  println "  (damaged compartment of an UNINFECTED animal is largely conducting airway,"
  println "   which is truly KRT5+ and truly PDPN-/AGER-; a good ceiling should KEEP these)"
  println String.format("%-8s %18s %18s", "ceiling", "kept if PDPN<t", "kept if AGER<t")
  CEILINGS.each { ct ->
    long nb=0, pB=0, aB=0
    for (int i=0;i<n;i++){
      if (!damaged[i]) continue
      if (fk[i] < KRT5_BRIGHT) continue
      nb++
      if (fpd[i] < ct) pB++
      if (fa[i]  < ct) aB++
    }
    println String.format("%-8.0f %18s %18s", ct,
        nb>0?String.format("%.3f",(double)pB/nb):"n/a",
        nb>0?String.format("%.3f",(double)aB/nb):"n/a")
  }
  println ""
  server.close()
}
println "Infected slides were NOT opened by this script."
