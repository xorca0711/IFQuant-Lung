// probe_channels.groovy -- what tissue signal is actually available, and is it
// independent of AGER? Dumps per-channel distributions inside the Stage-1
// parenchyma ROI, and renders crops so the masks can be eyeballed.
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
import javax.imageio.ImageIO

def OUT = new File(System.getenv("PROBE_OUT") ?: ".")
OUT.mkdirs()
def DIR = "D:/Confocal_Images/20260806_CW/20260806_CW/"
def SLIDES = [
  ["het_m4-2_ctrl", "IFNg KO(het) 26.03.25 m4-2 pr8 no infection"],
  ["hom_m6_ctrl",   "IFNg KO(hom) 26.03.25 m6 pr8 no infection"],
  ["het_m4-1_pr8",  "IFNg KO(het) 26.03.25 m4-1 pr8 infection"],
  ["hom_m2_pr8",    "IFNg KO(hom) 26.03.25 m2 pr8 infection"],
]
def ONLY = System.getenv("PROBE_ONLY") ?: ""

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
def otsu = { float[] f, boolean[] m ->
  float lo=Float.MAX_VALUE, hi=-Float.MAX_VALUE
  for (int i=0;i<f.length;i++) if (m[i]) { if (f[i]<lo) lo=f[i]; if (f[i]>hi) hi=f[i] }
  if (hi<=lo) return (double) lo
  int[] hh=new int[256]; double sc=255.0d/(hi-lo)
  for (int i=0;i<f.length;i++) if (m[i]) hh[(int)Math.round((f[i]-lo)*sc)]++
  return lo + new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hh)/sc
}
def pct = { float[] f, boolean[] m, List qs ->
  int n=0; for (int i=0;i<f.length;i++) if (m[i]) n++
  float[] v=new float[n]; int k=0
  for (int i=0;i<f.length;i++) if (m[i]) v[k++]=f[i]
  java.util.Arrays.sort(v)
  return qs.collect { double q -> (double) v[(int)Math.min(n-1L, Math.max(0L, Math.round(q*(n-1))))] }
}

def withRetry = { String what, int tries, Closure body ->
  Throwable last = null
  for (int a = 1; a <= tries; a++) {
    try { return body() }
    catch (Throwable t) {
      last = t
      println "  [retry ${a}/${tries}] ${what} failed: ${t.getClass().getSimpleName()}: ${t.getMessage()}"
      Thread.sleep(5000L * a)
    }
  }
  throw new RuntimeException("gave up on " + what, last)
}

def slideBody = { String lbl, String name ->
  def path = DIR + name + ".vsi"
  def server = ImageServers.buildServer(new File(path).toURI(), "--series", ""+pickSeries(path))
  int W = server.getWidth(), H = server.getHeight()
  double px0 = server.getPixelCalibration().getPixelWidthMicrons()
  println "==== ${lbl}  ${W}x${H}  ${px0} um/px"

  // ---- Stage-1 ROI at ds16 mapped onto a ds8 grid -----------------------
  double DS = 8.0d
  def im = server.readRegion(DS,0,0,W,H)
  def ras = im.getRaster(); int w=im.getWidth(), h=im.getHeight(), n=w*h
  def dbuf = ras.getDataBuffer()
  def get = { int c ->
    def sm = ras.getSampleModel()
    if ((dbuf instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
        sm.getBankIndices()[c]==c && sm.getScanlineStride()==w) return ((DataBufferUShort)dbuf).getData(c)
    int[] t=new int[n]; ras.getSamples(0,0,w,h,c,t); short[] o=new short[n]
    for (int i=0;i<n;i++) o[i]=(short)t[i]; return o
  }
  short[][] raw = new short[4][]
  for (int c=0;c<4;c++) raw[c] = get(c)
  def toF = { short[] p, double sig ->
    def fp=new FloatProcessor(w,h); float[] o=(float[])fp.getPixels()
    for (int i=0;i<n;i++) o[i]=(float)(p[i]&0xFFFF)
    if (sig>0) new GaussianBlur().blurGaussian(fp,sig); return (float[])fp.getPixels()
  }
  // ROI: Stage-1 recipe = blur(sigma2 at ds16 ~= sigma4 at ds8) + Otsu(DAPI) + close4 + open2
  float[] fd = toF(raw[0], 4.0d)
  boolean[] all=new boolean[n]; java.util.Arrays.fill(all,true)
  double tThr = otsu(fd, all)
  def tbp=new ByteProcessor(w,h); byte[] tb=(byte[])tbp.getPixels()
  for (int i=0;i<n;i++) if (fd[i]>=tThr) tb[i]=(byte)255
  def rf=new RankFilters()
  rf.rank(tbp,8.0d,RankFilters.MAX); rf.rank(tbp,8.0d,RankFilters.MIN)
  rf.rank(tbp,4.0d,RankFilters.MIN); rf.rank(tbp,4.0d,RankFilters.MAX)
  boolean[] roi=new boolean[n]; long rN=0
  for (int i=0;i<n;i++){ roi[i]=(tb[i]&0xFF)>127; if(roi[i]) rN++ }
  println String.format("  ROI Otsu(DAPI)=%.1f  ROI=%d px = %.2f mm2 (%.1f%% of frame)",
      tThr, rN, rN*(px0*DS)*(px0*DS)/1e6, 100.0*rN/n)

  def QS = [0.01d,0.05d,0.10d,0.25d,0.50d,0.75d,0.90d,0.95d,0.99d]
  println String.format("  %-14s %s", "channel(ds8)", QS.collect{String.format("%8s","p"+(int)(it*100))}.join(" "))
  def names = ["0_DAPI","1_FITC_KRT5","2_Cy3_AGER","3_Cy5_PDPN"]
  float[][] chF = new float[4][]
  (0..3).each { c ->
    chF[c] = toF(raw[c], 0.0d)
    def p = pct(chF[c], roi, QS)
    println String.format("  %-14s %s | otsu=%.0f", names[c], p.collect{String.format("%8.0f",it)}.join(" "), otsu(chF[c], roi))
  }
  // max projections of interest
  def combos = [["0"      ,[0]],["0,1",[0,1]],["0,3",[0,3]],["0,1,3",[0,1,3]],["ALL4",[0,1,2,3]]]
  combos.each { cn, cs ->
    float[] m = new float[n]
    cs.each { int c -> for (int i=0;i<n;i++) if (chF[c][i]>m[i]) m[i]=chF[c][i] }
    def p = pct(m, roi, QS)
    println String.format("  MAX[%-9s] %s | otsu=%.0f", cn, p.collect{String.format("%8.0f",it)}.join(" "), otsu(m, roi))
  }

  // ---- correlation of each channel with the AGER damage map -------------
  // AGER damage: locked recipe at ds8
  float[] fa = toF(raw[2], 1.0d)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  for (int i=0;i<n;i++) d[i] = (roi[i] && fa[i]>=150f) ? 1f : 0f
  new GaussianBlur().blurGaussian(dp, 40.0d/(px0*DS))
  float[] dens=(float[])dp.getPixels()
  boolean[] dmg=new boolean[n]; long dN=0
  for (int i=0;i<n;i++){ dmg[i] = roi[i] && dens[i]<0.14f; if(dmg[i]) dN++ }
  println String.format("  AGER-damaged = %.2f%% of ROI", 100.0*dN/rN)
  // mean of each channel inside damaged vs intact
  (0..3).each { c ->
    double sd=0, si=0; long nd=0, ni=0
    for (int i=0;i<n;i++) { if(!roi[i]) continue; if(dmg[i]){sd+=chF[c][i];nd++} else {si+=chF[c][i];ni++} }
    println String.format("    %-14s mean in DAMAGED=%8.1f   in INTACT=%8.1f   ratio=%.3f",
        names[c], nd>0?sd/nd:0, ni>0?si/ni:0, (ni>0&&nd>0)?(sd/nd)/(si/ni):0)
  }

  // ---- render crops at ds2 for visual inspection ------------------------
  if ((System.getenv("PROBE_RENDER") ?: "false") == "true") {
    // densest 2048 window in the ds8 map
    int win = 2048  // fine px at ds2 => 4096 full-res px => 512 px at ds8
    int stepC = 512
    int bx=0, by=0; long best=-1
    for (int yy=0; yy+stepC<=h; yy+=stepC/2) for (int xx=0; xx+stepC<=w; xx+=stepC/2) {
      long c=0
      for (int y=yy;y<yy+stepC;y++) for (int x=xx;x<xx+stepC;x++) if (roi[y*w+x]) c++
      if (c>best){best=c;bx=xx;by=yy}
    }
    int fx = (int)(bx*DS), fy = (int)(by*DS), fwid = (int)(stepC*DS)
    println "  render window full-res (${fx},${fy}) ${fwid}px  (${String.format('%.0f',100.0*best/(stepC*(double)stepC))}% ROI)"
    def r2 = server.readRegion(2.0d, fx, fy, fwid, fwid)
    def ra2 = r2.getRaster(); int w2=r2.getWidth(), h2=r2.getHeight(), n2=w2*h2
    (0..3).each { c ->
      int[] t=new int[n2]; ra2.getSamples(0,0,w2,h2,c,t)
      def bp=new ByteProcessor(w2,h2); byte[] o=(byte[])bp.getPixels()
      // fixed display range 0..1200 so slides are comparable
      for (int i=0;i<n2;i++){ int v=(int)Math.min(255.0, t[i]*255.0/1200.0); o[i]=(byte)v }
      ImageIO.write(bp.getBufferedImage(), "png", new File(OUT, lbl+"_ch"+c+"_"+names[c]+".png"))
    }
  }
  server.close()
  return true
}

SLIDES.each { lbl, name ->
  if (!ONLY.isEmpty() && lbl != ONLY) return
  withRetry("slide " + lbl, 4) { slideBody(lbl, name) }
}
println "DONE"
