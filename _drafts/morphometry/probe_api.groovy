// PROBE ONLY -- checks that every QuPath 0.7 / ImageJ API the morphometry
// module needs is actually present, before any of it is written into the module.
// Read-only: opens nothing on disk except to print class names.
def LOG = "[IFQ_MORPH_PROBE]"
def ok = { String what, Closure c ->
  try { def r = c(); println LOG + " OK    " + what + (r == null ? "" : "  -> " + r) }
  catch (Throwable t) { println LOG + " FAIL  " + what + "  -> " + t.getClass().getName() + ": " + t.getMessage() }
}

println LOG + " QuPath " + qupath.lib.common.GeneralTools.getVersion()
println LOG + " ImageJ " + ij.IJ.getFullVersion()

ok("ij.plugin.filter.EDM.makeFloatEDM") {
  def bp = new ij.process.ByteProcessor(16, 16)
  for (int y = 4; y < 12; y++) for (int x = 4; x < 12; x++) bp.set(x, y, 255)
  def fp = ij.plugin.filter.EDM.makeFloatEDM(bp, 0, false)
  return "centre EDM=" + fp.getf(8, 8) + " (expect 4.0 for an 8x8 square)"
}
ok("ij.plugin.filter.GaussianBlur")        { new ij.plugin.filter.GaussianBlur().getClass().getName() }
ok("ij.plugin.filter.RankFilters")         { new ij.plugin.filter.RankFilters().getClass().getName() }
ok("ij.process.AutoThresholder")           { new ij.process.AutoThresholder().getClass().getName() }
ok("qupath.lib.analysis.images.ContourTracing") { qupath.lib.analysis.images.ContourTracing.class.getName() }
ok("qupath.lib.roi.GeometryTools")         { qupath.lib.roi.GeometryTools.class.getName() }
ok("qupath.lib.images.servers.ImageServers") { qupath.lib.images.servers.ImageServers.class.getName() }
ok("qupath.lib.io.GsonTools")              { qupath.lib.io.GsonTools.class.getName() }

// ---- brightfield colour deconvolution: which package in 0.7? ----
["qupath.lib.color.ColorDeconvolutionStains",
 "qupath.lib.color.StainVector",
 "qupath.lib.color.ColorDeconvolutionHelper",
 "qupath.lib.color.ColorTransformer",
 "qupath.lib.analysis.algorithms.ColorTransformer",
 "qupath.lib.color.ColorDeconvolution",
 "qupath.imagej.tools.IJTools"].each { String cn ->
  ok("class " + cn) { Class.forName(cn).getName() }
}
ok("StainVector.createStainVector") {
  def sv = qupath.lib.color.StainVector.createStainVector("Hematoxylin", 0.65d, 0.70d, 0.29d)
  return sv.getName() + " " + sv.getArray()
}
ok("ColorDeconvolutionStains H&E default") {
  def s = qupath.lib.color.ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(
      qupath.lib.color.ColorDeconvolutionStains.DefaultColorDeconvolutionStains.H_E)
  return s.getStain(1).getName() + " / " + s.getStain(2).getName()
}
ok("ColorDeconvolutionHelper.colorDeconvolveRGBArray") {
  int[] rgb = [0xFF804060] as int[]
  def s = qupath.lib.color.ColorDeconvolutionStains.makeDefaultColorDeconvolutionStains(
      qupath.lib.color.ColorDeconvolutionStains.DefaultColorDeconvolutionStains.H_E)
  float[] out = qupath.lib.color.ColorDeconvolutionHelper.colorDeconvolveRGBArray(rgb, s, 0, null)
  return "H OD=" + out[0]
}
println LOG + " probe done"
