import qupath.lib.images.servers.ImageServers
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS

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
def DIR = "D:/Confocal_Images/20260806_CW/20260806_CW/"
["IFNg KO(het) 26.03.25 m4-1 pr8 infection",
 "IFNg KO(het) 26.03.25 m4-2 pr8 no infection",
 "IFNg KO(hom) 26.03.25 m2 pr8 infection",
 "IFNg KO(hom) 26.03.25 m6 pr8 no infection"].each { nm ->
  def path = DIR + nm + ".vsi"
  int ser = pickSeries(path)
  def sv = ImageServers.buildServer(new File(path).toURI(), "--series", "" + ser)
  def cal = sv.getPixelCalibration()
  println String.format("[BENCH] %-46s series=%d  %dx%d  %.5f um/px  %.2f mm x %.2f mm",
      nm, ser, sv.getWidth(), sv.getHeight(), cal.getPixelWidthMicrons(),
      sv.getWidth()*cal.getPixelWidthMicrons()/1000.0, sv.getHeight()*cal.getPixelHeightMicrons()/1000.0)
  sv.close()
}

// throughput benchmark on one slide
def path = DIR + "IFNg KO(het) 26.03.25 m4-2 pr8 no infection.vsi"
def sv = ImageServers.buildServer(new File(path).toURI(), "--series", "" + pickSeries(path))
int W = sv.getWidth(), H = sv.getHeight()
[2.0d, 4.0d].each { double ds ->
  int core = 2048
  long t0 = System.currentTimeMillis(); long px = 0L
  // 6 consecutive blocks across the middle band
  for (int b = 0; b < 6; b++) {
    int fx = (int)(W*0.20) + (int)(b*core*ds)
    int fy = (int)(H*0.45)
    if (fx + core*ds > W) break
    def img = sv.readRegion(ds, fx, fy, (int)(core*ds), (int)(core*ds))
    px += (long)img.getWidth()*img.getHeight()
  }
  long dt = System.currentTimeMillis()-t0
  println String.format("[BENCH] ds=%.0f  %d blocks-worth  %.1f Mpx out  %d ms  -> %.2f Mpx/s",
      ds, 6, px/1e6, dt, px/1e3/Math.max(1,dt))
  long fw = (long)Math.ceil(W/ds), fh = (long)Math.ceil(H/ds)
  println String.format("        whole slide at ds=%.0f is %dx%d = %.0f Mpx -> est %.1f min I/O (no halo)",
      ds, fw, fh, fw*fh/1e6, (fw*fh/1e6)/(px/1e3/Math.max(1,dt))/60.0)
}
sv.close()
