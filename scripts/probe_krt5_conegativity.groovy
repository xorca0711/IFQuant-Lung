// Does the paper's CO-NEGATIVITY constraint reject the KRT5 autofluorescence?
//
// Lin et al. 2024 (JCI 134(19):e176828) Fig 2A-B quantify "KRT5+PDPN- areas",
// not bare KRT5+ area. Autofluorescent structures are bright in EVERY channel,
// so they should be KRT5+ AND PDPN+ (and AGER+) and therefore rejected by that
// constraint. Genuine dysplastic pods are KRT5+ but PDPN-/AGER-.
//
// Measured here inside INTACT parenchyma of the UNINFECTED controls, which
// contains neither pods nor airway basal cells and is therefore a pure
// background compartment. If co-negativity is the discriminator, the false
// positive area fraction should collapse.
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
double AGER_THR = 150.0d, DMG_SIGMA = 40.0d, DMG_CUT = 0.14d   // locked damage params
def KRT5_THR = [100d, 150d, 200d, 250d, 300d, 400d, 500d]
def CONEG_THR = [100d, 150d, 200d, 300d]     // PDPN / AGER "negative" ceiling

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

def store=[:]
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
  float[] fa = toF(get(2),1.0d)                 // AGER  (ch2)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  for (int i=0;i<n;i++) d[i] = (tissue[i] && fa[i]>=AGER_THR) ? 1f : 0f
  new GaussianBlur().blurGaussian(dp, DMG_SIGMA/pxUm)
  float[] dens=(float[])dp.getPixels()
  boolean[] intact=new boolean[n]; long iN=0
  for (int i=0;i<n;i++) if (tissue[i] && dens[i] >= DMG_CUT) { intact[i]=true; iN++ }
  store[lbl]=[fk:toF(get(1),1.0d), fa:fa, fp:toF(get(3),1.0d), intact:intact, iN:iN, n:n]
  println String.format("loaded %s  intact=%d px", lbl, iN)
  server.close()
}
println ""
println "FALSE-POSITIVE AREA FRACTION inside INTACT control parenchyma (worst of both slides)"
println "columns: bare KRT5+  |  KRT5+ AND PDPN<t  |  KRT5+ AND AGER<t  |  KRT5+ AND BOTH<t"
println ""
CONEG_THR.each { ct ->
  println String.format("--- co-negativity ceiling t = %.0f ---", ct)
  println String.format("%-10s %12s %14s %14s %14s", "KRT5thr", "bare", "PDPN-", "AGER-", "PDPN- & AGER-")
  KRT5_THR.each { kt ->
    def bare=[], pn=[], an=[], bn=[]
    CONTROLS.each { lbl, nm ->
      def st=store[lbl]; long b=0,p=0,a=0,ba=0
      for (int i=0;i<st.n;i++){
        if (!st.intact[i]) continue
        if (st.fk[i] < kt) continue
        b++
        boolean pneg = st.fp[i] < ct
        boolean aneg = st.fa[i] < ct
        if (pneg) p++
        if (aneg) a++
        if (pneg && aneg) ba++
      }
      double dn=Math.max(1,st.iN)
      bare<<(b/dn); pn<<(p/dn); an<<(a/dn); bn<<(ba/dn)
    }
    println String.format("%-10.0f %12.6f %14.6f %14.6f %14.6f",
        kt, bare.max(), pn.max(), an.max(), bn.max())
  }
  println ""
}
println "Infected slides were NOT opened by this script."
