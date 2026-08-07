// Verify the channel cache reproduces a fresh decode EXACTLY, and measure the
// speedup. A cache that silently differs from source would corrupt every
// calibration built on it, so this compares pixel by pixel rather than
// spot-checking.
//
//   IFQ_CACHE_DIR   default X:\ifq_cache
//   IFQ_CACHE_INPUT folder of .vsi (to re-read one slide fresh)
//   IFQ_CACHE_DS    default 8
import qupath.lib.images.servers.ImageServers
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS
import java.awt.image.DataBufferUShort
import java.awt.image.BandedSampleModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

def LOG = "[IFQ_CACHEVERIFY]"
def logMsg = { String m -> println LOG + " " + m }
def envOr = { String n, String d -> def v = System.getenv(n); (v == null || v.trim().isEmpty()) ? d : v.trim() }

def CACHE = envOr("IFQ_CACHE_DIR", "X:\\ifq_cache")
def INPUT = envOr("IFQ_CACHE_INPUT", "D:\\Confocal_Images\\20260806_CW\\20260806_CW")
double DS = Double.parseDouble(envOr("IFQ_CACHE_DS", "8"))

/** Load one cached slide. Returns [ch: short[][], width:, height:, meta:]. */
def loadCache = { String cacheDir, String stem, double ds ->
  def metaF = new File(cacheDir, stem + "__ds" + (int) ds + ".json")
  def binF  = new File(cacheDir, stem + "__ds" + (int) ds + ".raw")
  if (!metaF.isFile() || !binF.isFile())
    throw new IllegalStateException("No cache for '" + stem + "' at ds=" + (int) ds + " in " + cacheDir)
  // QuPath does NOT bundle groovy-json (Fiji does). Use QuPath's Gson.
  def meta = qupath.lib.io.GsonTools.getInstance().fromJson(metaF.getText("UTF-8"), Map.class)
  int w = meta.width as int, h = meta.height as int, nc = meta.n_channels as int
  int n = w * h
  long expect = (long) n * 2L * nc
  if (binF.length() != expect)
    throw new IllegalStateException("Cache size mismatch for " + stem + ": got " + binF.length() + ", expected " + expect)
  short[][] ch = new short[nc][]
  def is = new BufferedInputStream(new FileInputStream(binF), 1 << 20)
  try {
    byte[] buf = new byte[n * 2]
    for (int c = 0; c < nc; c++) {
      int off = 0
      while (off < buf.length) { int r = is.read(buf, off, buf.length - off); if (r < 0) break; off += r }
      // BULK transfer. A per-element getShort() loop is ~37M dynamically
      // dispatched calls per channel in Groovy and dominates the load time;
      // asShortBuffer().get(array) is a single native copy.
      short[] px = new short[n]
      ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(px)
      ch[c] = px
    }
  } finally { is.close() }
  return [ch: ch, width: w, height: h, meta: meta]
}

def pickSeries = { String p ->
  def r = new ImageReader(); r.setFlattenedResolutions(false)
  IMetadata m = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(m)
  r.setId(p); def cands = []
  for (int s = 0; s < r.getSeriesCount(); s++) {
    r.setSeries(s); r.setResolution(0)
    def pw = m.getPixelsPhysicalSizeX(s); if (pw == null) continue
    double um = pw.value(UNITS.MICROMETER).doubleValue()
    if (r.getEffectiveSizeC() == 4 && r.getSizeZ() == 1 && um > 0 && um <= 0.5) cands << s
  }
  r.close(); return cands[0]
}

def slides = new File(INPUT).listFiles().findAll { it.name.toLowerCase().endsWith(".vsi") }.sort { it.name }
def target = slides[0]
String stem = target.name.replaceFirst(/\.vsi$/, "")
logMsg("verifying: " + stem)

long tC = System.currentTimeMillis()
def cached = loadCache(CACHE, stem, DS)
tC = System.currentTimeMillis() - tC
logMsg(String.format("  cache load : %d ms   (%dx%d x%dch)", tC, cached.width, cached.height, cached.ch.length))

long tF = System.currentTimeMillis()
def server = ImageServers.buildServer(target.toURI(), "--series", "" + pickSeries(target.getAbsolutePath()))
def img = server.readRegion(DS, 0, 0, server.getWidth(), server.getHeight())
def ras = img.getRaster(); def db = ras.getDataBuffer(); def sm = ras.getSampleModel()
int w = img.getWidth(), h = img.getHeight(), nc = server.nChannels(), n = w * h
tF = System.currentTimeMillis() - tF
logMsg(String.format("  fresh read : %d ms   (%dx%d x%dch)", tF, w, h, nc))

if (w != cached.width || h != cached.height || nc != cached.ch.length) {
  logMsg("  *** DIMENSION MISMATCH -- cache is NOT usable ***")
  server.close(); System.exit(1)
}

long totalDiff = 0
for (int c = 0; c < nc; c++) {
  short[] fresh
  boolean fast = (db instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
                 sm.getBankIndices()[c] == c && sm.getScanlineStride() == w
  if (fast) { fresh = ((DataBufferUShort) db).getData(c) }
  else { int[] t = new int[n]; ras.getSamples(0,0,w,h,c,t); fresh = new short[n]; for (int i=0;i<n;i++) fresh[i]=(short)t[i] }
  long d = 0
  short[] cc = cached.ch[c]
  for (int i = 0; i < n; i++) if (cc[i] != fresh[i]) d++
  totalDiff += d
  logMsg(String.format("  ch%d %-12s differing pixels: %d", c, cached.meta.channel_names[c], d))
}
server.close()

logMsg("")
if (totalDiff == 0) {
  logMsg(String.format("*** CACHE IS EXACT -- 0 differing pixels across %d channels ***", nc))
  logMsg(String.format("    speedup: %.1fx  (%d ms -> %d ms)", (double) tF / Math.max(1, tC), tF, tC))
} else {
  logMsg("*** CACHE DIFFERS in " + totalDiff + " pixel(s) -- DO NOT USE ***")
  System.exit(1)
}
