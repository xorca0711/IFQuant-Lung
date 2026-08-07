import qupath.lib.images.servers.ImageServers
def f = new File(System.getenv("IFQ_PROBE_FILE"))
def server = ImageServers.buildServer(f.toURI(), "--series", System.getenv("IFQ_PROBE_SERIES") ?: "2")
println "[PROBE] path=" + server.getPath()
println "[PROBE] W=" + server.getWidth() + " H=" + server.getHeight() + " nCh=" + server.nChannels() + " type=" + server.getPixelType()
def cal = server.getPixelCalibration()
println "[PROBE] pxW=" + cal.getPixelWidthMicrons() + " pxH=" + cal.getPixelHeightMicrons()
def md = server.getMetadata()
println "[PROBE] downsamples=" + (server.getPreferredDownsamples() as List)
println "[PROBE] tileW=" + md.getPreferredTileWidth() + " tileH=" + md.getPreferredTileHeight()
md.getLevels().eachWithIndex { lv, i -> println "[PROBE]   level " + i + ": ds=" + lv.getDownsample() + " " + lv.getWidth() + "x" + lv.getHeight() }
server.getMetadata().getChannels().eachWithIndex { c, i -> println "[PROBE]   ch " + i + ": " + c.getName() }
// timing: read a 2048x2048 fine region at ds 1,2,4
[1.0d, 2.0d, 4.0d, 8.0d].each { double ds ->
  int side = 2048
  int x = (int)(server.getWidth()/2), y = (int)(server.getHeight()/2)
  long t = System.currentTimeMillis()
  def img = server.readRegion(ds, x, y, (int)(side*ds), (int)(side*ds))
  println String.format("[PROBE] read ds=%.0f req=%dx%d got=%dx%d  %d ms", ds, (int)(side*ds), (int)(side*ds), img.getWidth(), img.getHeight(), System.currentTimeMillis()-t)
}
server.close()
