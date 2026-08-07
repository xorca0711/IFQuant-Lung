import ij.process.ByteProcessor
import ij.process.FloatProcessor
import ij.process.ImageProcessor
import ij.plugin.filter.EDM

def LOG = "[IFQ_MORPH_PROBE3]"

@groovy.transform.CompileStatic
class EdmTest {
  static FloatProcessor edm(ByteProcessor bp) {
    return new EDM().makeFloatEDM((ImageProcessor) bp, 0, false)
  }
}

def bp = new ByteProcessor(16, 16)
for (int y = 4; y < 12; y++) for (int x = 4; x < 12; x++) bp.set(x, y, 255)

try {
  def fp = EdmTest.edm(bp)
  println LOG + " CompileStatic OK  centre=" + fp.getf(8, 8) + "  edgecol=" + fp.getf(4, 8) +
          "  (8x8 square: centre should be 4.0, first column 1.0)"
} catch (Throwable t) { println LOG + " CompileStatic FAIL " + t }

try {
  def m = EDM.class.getMethod("makeFloatEDM", ImageProcessor.class, int.class, boolean.class)
  def fp = (FloatProcessor) m.invoke(null, bp, 0, false)
  println LOG + " reflection OK    centre=" + fp.getf(8, 8)
} catch (Throwable t) { println LOG + " reflection FAIL " + t }

// mean EDM over a slab -> thickness estimator t = 4*mean(EDM)
@groovy.transform.CompileStatic
class SlabTest {
  static double meanEdmOfSlab(int thickness, int len) {
    ByteProcessor bp = new ByteProcessor(len, thickness + 40)
    for (int y = 20; y < 20 + thickness; y++)
      for (int x = 0; x < len; x++) bp.set(x, y, 255)
    FloatProcessor fp = new EDM().makeFloatEDM((ImageProcessor) bp, 0, false)
    float[] f = (float[]) fp.getPixels()
    byte[] m = (byte[]) bp.getPixels()
    double s = 0.0d; long n = 0L
    // ignore the 40 px at each end of the slab so the ends do not contaminate it
    for (int y = 0; y < thickness + 40; y++) {
      for (int x = 40; x < len - 40; x++) {
        int i = y * len + x
        if ((m[i] & 0xFF) > 127) { s += f[i]; n++ }
      }
    }
    return n > 0 ? s / n : 0.0d
  }
}
[3, 4, 6, 8, 12, 20].each { int t ->
  double me = SlabTest.meanEdmOfSlab(t, 400)
  println LOG + String.format("  slab t=%2d px  mean(EDM)=%6.3f  4*mean=%6.3f  ratio=%5.3f", t, me, 4 * me, 4 * me / t)
}

// ByteProcessor.skeletonize polarity check (Prefs.blackBackground hazard)
try {
  def sk = new ByteProcessor(21, 21)
  for (int y = 8; y <= 12; y++) for (int x = 2; x < 19; x++) sk.set(x, y, 255)
  long before = 0; byte[] p0 = (byte[]) sk.getPixels()
  for (int i = 0; i < p0.length; i++) if ((p0[i] & 0xFF) > 127) before++
  sk.skeletonize()
  long after = 0; byte[] p1 = (byte[]) sk.getPixels()
  for (int i = 0; i < p1.length; i++) if ((p1[i] & 0xFF) > 127) after++
  println LOG + " skeletonize(): fg " + before + " -> " + after +
          " (a 17x5 bar should thin to ~17; if it GREW, polarity is inverted)"
} catch (Throwable t) { println LOG + " skeletonize FAIL " + t }
println LOG + " ij.Prefs.blackBackground=" + ij.Prefs.blackBackground
