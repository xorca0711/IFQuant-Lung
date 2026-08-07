def LOG = "[IFQ_MORPH_PROBE2]"
def dump = { String cn, String filter ->
  try {
    Class c = Class.forName(cn)
    println LOG + " ---- " + cn
    c.getMethods().findAll { it.getName().toLowerCase().contains(filter.toLowerCase()) }
      .collect { m -> "    " + (java.lang.reflect.Modifier.isStatic(m.getModifiers()) ? "static " : "") +
                       m.getReturnType().getSimpleName() + " " + m.getName() +
                       "(" + m.getParameterTypes().collect { it.getSimpleName() }.join(", ") + ")" }
      .unique().sort().each { println it }
  } catch (Throwable t) { println LOG + " FAIL " + cn + " " + t }
}
dump("ij.plugin.filter.EDM", "edm")
dump("ij.plugin.filter.EDM", "makeFloat")
dump("qupath.lib.color.ColorDeconvolutionHelper", "deconvolve")
dump("qupath.lib.color.ColorDeconvolutionHelper", "od")
dump("qupath.lib.color.ColorTransformer", "value")
dump("ij.process.ByteProcessor", "skeleton")

// real EDM behaviour check with whatever signature exists
try {
  def bp = new ij.process.ByteProcessor(16, 16)
  for (int y = 4; y < 12; y++) for (int x = 4; x < 12; x++) bp.set(x, y, 255)
  def fp = ij.plugin.filter.EDM.makeFloatEDM(bp, 0, false)
  println LOG + " EDM 8x8 square centre=" + fp.getf(8, 8) + " edge=" + fp.getf(4, 8)
} catch (Throwable t) { println LOG + " EDM(bp,0,false) -> " + t.getMessage() }
