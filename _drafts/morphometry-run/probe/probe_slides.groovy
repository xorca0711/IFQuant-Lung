// Probe: pyramid levels, read throughput, per-channel signal distributions
// inside the damage-detector tissue mask, on all four slides.
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

def DIR = "D:/Confocal_Images/20260806_CW/20260806_CW/"
def NAMES = [
  "IFNg KO(het) 26.03.25 m4-1 pr8 infection",
  "IFNg KO(het) 26.03.25 m4-2 pr8 no infection",
  "IFNg KO(hom) 26.03.25 m2 pr8 infection",
  "IFNg KO(hom) 26.03.25 m6 pr8 no infection",
]

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
def pct = { float[] f, boolean[] m, double q ->
  int n=0; for (int i=0;i<f.length;i++) if (m[i]) n++
  if (n==0) return Double.NaN
  float[] v=new float[n]; int k=0
  for (int i=0;i<f.length;i++) if (m[i]) v[k++]=f[i]
  java.util.Arrays.sort(v)
  return (double) v[(int)Math.min(n-1, Math.max(0, Math.round(q*(n-1))))]
}

NAMES.each { name ->
  String path = DIR + name + ".vsi"
  println "=========================================================="
  println "SLIDE " + name
  int ser = pickSeries(path)
  // pyramid levels via Bio-Formats
  def r = new ImageReader(); r.setFlattenedResolutions(false); r.setId(path)
  r.setSeries(ser)
  println "  series=" + ser + "  resolutionCount=" + r.getResolutionCount()
  for (int lv=0; lv<r.getResolutionCount(); lv++) {
    r.setResolution(lv)
    println String.format("    level %d: %d x %d  (tile %d x %d)", lv, r.getSizeX(), r.getSizeY(), r.getOptimalTileWidth(), r.getOptimalTileHeight())
  }
  r.close()

  def server = ImageServers.buildServer(new File(path).toURI(), "--series", ""+ser)
  println "  QuPath server downsamples: " + (server.getPreferredDownsamples() as List)
  int W = server.getWidth(), H = server.getHeight()
  double px0 = server.getPixelCalibration().getPixelWidthMicrons()
  println String.format("  %d x %d  %.5f um/px  nCh=%d  type=%s", W, H, px0, server.nChannels(), server.getPixelType())

  // read whole slide at ds8
  long t0 = System.currentTimeMillis()
  double DS = 8.0d
  def im = server.readRegion(DS,0,0,W,H)
  long tRead = System.currentTimeMillis()-t0
  int w=im.getWidth(), h=im.getHeight(), n=w*h
  println String.format("  ds8 whole-slide read: %d x %d = %.1f Mpx in %d ms", w, h, n/1e6, tRead)
  def ras = im.getRaster(); def dbuf = ras.getDataBuffer()
  def get = { int c ->
    def sm = ras.getSampleModel()
    if ((dbuf instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
        sm.getBankIndices()[c]==c && sm.getScanlineStride()==w) return ((DataBufferUShort)dbuf).getData(c)
    int[] t=new int[n]; ras.getSamples(0,0,w,h,c,t); short[] o=new short[n]
    for (int i=0;i<n;i++) o[i]=(short)t[i]; return o
  }
  def toF = { short[] p, double sig ->
    def fp=new FloatProcessor(w,h); float[] o=(float[])fp.getPixels()
    for (int i=0;i<n;i++) o[i]=(float)(p[i]&0xFFFF)
    if (sig>0) new GaussianBlur().blurGaussian(fp,sig); return (float[])fp.getPixels()
  }
  // damage-detector tissue mask, verbatim from measure_damage_locked.groovy
  float[] fd = toF(get(0),2.0d)
  boolean[] all=new boolean[n]; java.util.Arrays.fill(all,true)
  double tThr = otsuWithin(fd, all)
  def tbp=new ByteProcessor(w,h); byte[] tb=(byte[])tbp.getPixels()
  for (int i=0;i<n;i++) if (fd[i]>=tThr) tb[i]=(byte)255
  def rf=new RankFilters(); rf.rank(tbp,4.0d,RankFilters.MAX); rf.rank(tbp,4.0d,RankFilters.MIN)
  boolean[] tissue=new boolean[n]; long tN=0
  for (int i=0;i<n;i++){ tissue[i]=(tb[i]&0xFF)>127; if(tissue[i]) tN++ }
  double pxUm = px0*DS
  println String.format("  DAPI Otsu tissue thr=%.1f  tissue=%.2f%% of frame = %.2f mm2", tThr, 100.0*tN/n, tN*pxUm*pxUm/1e6)

  // damaged mask
  float[] fa = toF(get(2),1.0d)
  def dp=new FloatProcessor(w,h); float[] d=(float[])dp.getPixels()
  long aN=0
  for (int i=0;i<n;i++){ boolean p = tissue[i] && fa[i]>=150.0f; d[i]= p?1f:0f; if(p) aN++ }
  new GaussianBlur().blurGaussian(dp, 40.0d/pxUm)
  float[] dens=(float[])dp.getPixels()
  boolean[] dmg=new boolean[n]; boolean[] itc=new boolean[n]
  long nd=0
  for (int i=0;i<n;i++) { if (tissue[i]) { if (dens[i] < 0.14f) { dmg[i]=true; nd++ } else itc[i]=true } }
  println String.format("  AGER+ = %.2f%% of tissue;  DAMAGED = %.2f%% of tissue (%.3f mm2)", 100.0*aN/tN, 100.0*nd/tN, nd*pxUm*pxUm/1e6)

  // channel distributions in tissue, damaged, intact  (raw, unblurred)
  ["DAPI","FITC/KRT5","Cy3/AGER","Cy5/PDPN"].eachWithIndex { cn, ci ->
    float[] f = toF(get(ci), 0.0d)
    println String.format("  ch%d %-10s  tissue p01=%6.0f p05=%6.0f p25=%6.0f p50=%6.0f p75=%6.0f p95=%6.0f p99=%6.0f max=%6.0f",
        ci, cn, pct(f,tissue,0.01), pct(f,tissue,0.05), pct(f,tissue,0.25), pct(f,tissue,0.50),
        pct(f,tissue,0.75), pct(f,tissue,0.95), pct(f,tissue,0.99), pct(f,tissue,1.0))
    println String.format("      %-14s  DMG    p05=%6.0f p25=%6.0f p50=%6.0f p75=%6.0f p95=%6.0f | INTACT p05=%6.0f p25=%6.0f p50=%6.0f p75=%6.0f p95=%6.0f",
        "", pct(f,dmg,0.05), pct(f,dmg,0.25), pct(f,dmg,0.50), pct(f,dmg,0.75), pct(f,dmg,0.95),
        pct(f,itc,0.05), pct(f,itc,0.25), pct(f,itc,0.50), pct(f,itc,0.75), pct(f,itc,0.95))
    // outside-tissue background (the true noise floor)
    boolean[] bg = new boolean[n]; for (int i=0;i<n;i++) bg[i] = !tissue[i]
    println String.format("      %-14s  OUTSIDE p50=%6.0f p95=%6.0f p99=%6.0f", "", pct(f,bg,0.50), pct(f,bg,0.95), pct(f,bg,0.99))
  }

  // timing at finer downsamples on a 4096-px window in the densest area
  int step = 512
  int bx=0, by=0; long best=-1
  for (int yy=0; yy+step<=h; yy+=step/2) for (int xx=0; xx+step<=w; xx+=step/2) {
    long c=0
    for (int y=yy;y<yy+step;y++) for (int x=xx;x<xx+step;x++) if (tissue[y*w+x]) c++
    if (c>best) { best=c; bx=xx; by=yy }
  }
  int fx=(int)(bx*DS), fy=(int)(by*DS)
  println String.format("  densest window at full-res (%d,%d), %.1f%% tissue at ds8", fx, fy, 100.0*best/(step*(double)step))
  [1.0d,2.0d,4.0d].each { double ds ->
    long tt=System.currentTimeMillis()
    def r2 = server.readRegion(ds, fx, fy, 4096, 4096)
    println String.format("    read 4096x4096 full-res window at ds=%.0f -> %dx%d in %d ms", ds, r2.getWidth(), r2.getHeight(), System.currentTimeMillis()-tt)
  }
  server.close()
}
println "PROBE DONE"
