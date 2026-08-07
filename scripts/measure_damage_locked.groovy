// Apply the LOCKED damage-detector parameters to a set of slides and report the
// damaged fraction. Companion to scripts/calibrate_damage_controls.groovy, which
// DERIVED these values from the uninfected controls alone.
//
// Locked operating point (alpha = 1% false positive on uninfected lung):
//   IFQ_WSI_AGER_THRESHOLD = 150
//   IFQ_WSI_DAMAGE_SIGMA_UM = 40
//   IFQ_WSI_DAMAGE_CUTOFF = 0.14
// See docs/ECTOPIC_POD_ENDPOINT.md section 4c.
//
// The control slides are re-measured here only to confirm this script
// reproduces the calibration (expected: het m4-2 0.93%, hom m6 0.18%).
// The INFECTED slides were never seen by the calibration, so their numbers are
// a HELD-OUT readout and must be reported as such -- not as validation.
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

double DS         = 8.0d
double AGER_THR   = (System.getenv("IFQ_WSI_AGER_THRESHOLD") ?: "150").toDouble()
double SIGMA_UM   = (System.getenv("IFQ_WSI_DAMAGE_SIGMA_UM") ?: "40").toDouble()
double CUTOFF     = (System.getenv("IFQ_WSI_DAMAGE_CUTOFF") ?: "0.14").toDouble()

def SLIDES = [
  ["het m4-2", "uninfected", "CONTROL (seen by calibration)", "IFNg KO(het) 26.03.25 m4-2 pr8 no infection"],
  ["hom m6  ", "uninfected", "CONTROL (seen by calibration)", "IFNg KO(hom) 26.03.25 m6 pr8 no infection"],
  ["het m4-1", "PR8       ", "HELD OUT",                      "IFNg KO(het) 26.03.25 m4-1 pr8 infection"],
  ["hom m2  ", "PR8       ", "HELD OUT",                      "IFNg KO(hom) 26.03.25 m2 pr8 infection"],
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

println String.format("LOCKED PARAMETERS: AGER threshold=%.0f  sigma=%.0f um  cutoff=%.2f  (detection at ds=%.0f)",
    AGER_THR, SIGMA_UM, CUTOFF, DS)
println ""
println String.format("%-9s %-11s %-30s %9s %9s %10s", "slide", "condition", "role", "tissue%", "AGER+%", "DAMAGED%")
println "-" * 86

SLIDES.each { lbl, cond, role, name ->
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

  float[] fa = toF(get(2),1.0d)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  long aN=0
  for (int i=0;i<n;i++){ boolean p = tissue[i] && fa[i]>=AGER_THR; d[i]= p?1f:0f; if(p) aN++ }
  new GaussianBlur().blurGaussian(dp, SIGMA_UM/pxUm)
  float[] dens=(float[])dp.getPixels()
  long dmg=0
  for (int i=0;i<n;i++) if (tissue[i] && dens[i] < CUTOFF) dmg++

  println String.format("%-9s %-11s %-30s %9.2f %9.2f %10.2f",
      lbl, cond, role, 100.0*tN/n, 100.0*aN/Math.max(1,tN), 100.0*dmg/Math.max(1,tN))
  server.close()
}
println ""
println "Controls should reproduce the calibration (het m4-2 0.93, hom m6 0.18)."
println "Infected values are HELD OUT -- they were not used to choose these parameters."
