// ============================================================================
// cache_slide_channels.groovy -- decode each slide ONCE, reuse it everywhere
// ============================================================================
// WHY
//   Threshold calibration is inherently iterative, and every probe re-read the
//   same slides from JPEG-2000. Measured on this dataset: a cold whole-slide
//   read at downsample 8 costs ~60 s per slide, and QuPath's readRegion ALWAYS
//   decodes all four channels even when only one is wanted. Eight calibration
//   probes over four slides is roughly half an hour of pure redundant decode.
//
//   This writes the decoded channels once as raw little-endian uint16 plus a
//   JSON sidecar. Reloading is a file read: milliseconds instead of a minute,
//   which is the difference between a parameter sweep you run and one you
//   avoid running.
//
// WHAT IT IS NOT
//   Not a substitute for the real data. The cache is derived, disposable, and
//   downsampled. Anything that must be measured at full resolution -- notably
//   confirming an operating point chosen on a coarse sweep -- must go back to
//   the .vsi. See docs/ECTOPIC_POD_ENDPOINT.md.
//
// RUN
//   IFQ_CACHE_INPUT   = folder of .vsi (or a single .vsi)
//   IFQ_CACHE_DIR     = where to write   (default X:\GitHub\IFQuant-Lung\.cache\slide_channels)
//   IFQ_CACHE_DS      = downsample       (default 8)
//   "X:\QuPath\QuPath-0.7.0 (console).exe" script scripts/cache_slide_channels.groovy
//
// LOADING (Groovy, in any probe)
//   def c = loadCache(cacheDir, slideStem)      // see loadCachedChannels below
//   short[] dapi = c.ch[0]; int w = c.width
// ============================================================================

import qupath.lib.images.servers.ImageServers
import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.meta.IMetadata
import ome.units.UNITS
import java.awt.image.DataBufferUShort
import java.awt.image.BandedSampleModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

def LOG = "[IFQ_CACHE]"
def logMsg = { String m -> println LOG + " " + m }
def failRun = { String m -> System.err.println("FATAL: " + m); println LOG + " FATAL: " + m; System.exit(1) }
def envOr = { String n, String d -> def v = System.getenv(n); (v == null || v.trim().isEmpty()) ? d : v.trim() }

def INPUT  = envOr("IFQ_CACHE_INPUT", "")
def OUTDIR = envOr("IFQ_CACHE_DIR", "X:\\GitHub\\IFQuant-Lung\\.cache\\slide_channels")
double DS  = Double.parseDouble(envOr("IFQ_CACHE_DS", "8"))
if (INPUT.isEmpty()) failRun("IFQ_CACHE_INPUT is required (a .vsi file or a folder of them)")

def inFile = new File(INPUT)
def slides = inFile.isDirectory() ?
    inFile.listFiles().findAll { it.name.toLowerCase().endsWith(".vsi") }.sort { it.name } :
    [inFile]
if (slides.isEmpty()) failRun("No .vsi found at " + INPUT)
// .ets files are the internal pyramid tiles inside the hidden _<name>_ folder.
// Opening one directly yields a partial read, so they are never candidates.
new File(OUTDIR).mkdirs()
logMsg("slides=" + slides.size() + "  ds=" + DS + "  out=" + OUTDIR)

def pickSeries = { String p ->
  def r = new ImageReader(); r.setFlattenedResolutions(false)
  IMetadata m = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(m)
  r.setId(p)
  def cands = []
  for (int s = 0; s < r.getSeriesCount(); s++) {
    r.setSeries(s); r.setResolution(0)
    def pw = m.getPixelsPhysicalSizeX(s); if (pw == null) continue
    double um = pw.value(UNITS.MICROMETER).doubleValue()
    if (r.getEffectiveSizeC() == 4 && r.getSizeZ() == 1 && um > 0 && um <= 0.5) cands << s
  }
  r.close()
  if (cands.size() != 1)
    throw new IllegalStateException("Expected exactly one 4-channel series at <=0.5 um/px, found " + cands)
  return cands[0]
}

int nDone = 0, nSkip = 0
slides.each { f ->
  String stem = f.name.replaceFirst(/\.vsi$/, "")
  def binF  = new File(OUTDIR, stem + "__ds" + (int) DS + ".raw")
  def metaF = new File(OUTDIR, stem + "__ds" + (int) DS + ".json")
  if (binF.isFile() && metaF.isFile()) { logMsg("  cached already: " + stem); nSkip++; return }

  long t0 = System.currentTimeMillis()
  int series = pickSeries(f.getAbsolutePath())
  def server = ImageServers.buildServer(f.toURI(), "--series", "" + series)
  try {
    def cal = server.getPixelCalibration()
    double pxW = cal.getPixelWidthMicrons(), pxH = cal.getPixelHeightMicrons()
    if (!cal.hasPixelSizeMicrons()) throw new IllegalStateException(stem + ": series is uncalibrated")

    def img = server.readRegion(DS, 0, 0, server.getWidth(), server.getHeight())
    def ras = img.getRaster()
    int w = img.getWidth(), h = img.getHeight(), nc = server.nChannels(), n = w * h
    def db = ras.getDataBuffer(); def sm = ras.getSampleModel()

    // one contiguous little-endian uint16 block per channel, channels in order
    def bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
    def os = new BufferedOutputStream(new FileOutputStream(binF), 1 << 20)
    try {
      for (int c = 0; c < nc; c++) {
        short[] px
        boolean fast = (db instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
                       sm.getBankIndices()[c] == c && sm.getBandOffsets()[c] == 0 &&
                       sm.getScanlineStride() == w && db.getOffsets()[c] == 0
        if (fast) {
          px = ((DataBufferUShort) db).getData(c)
        } else {
          int[] tmp = new int[n]; ras.getSamples(0, 0, w, h, c, tmp)
          px = new short[n]; for (int i = 0; i < n; i++) px[i] = (short) tmp[i]
        }
        // BULK transfer -- a per-element putShort() loop is ~37M dynamically
        // dispatched calls per channel in Groovy and dominates the write.
        bb.clear()
        bb.asShortBuffer().put(px)
        os.write(bb.array())
      }
    } finally { os.close() }

    // full-resolution dims are needed to reconstruct the coordinate frame
    def names = server.getMetadata().getChannels().collect { it.getName() }
    def meta = [
      slide_stem: stem, source_vsi: f.name, series_index: series,
      downsample: DS, width: w, height: h, n_channels: nc,
      full_width: server.getWidth(), full_height: server.getHeight(),
      pixel_size_um: pxW, pixel_size_um_y: pxH,
      pixel_size_um_at_ds: pxW * DS,
      channel_names: names,
      dtype: "uint16", byte_order: "little_endian",
      layout: "channel-major: channel 0 rows 0..h-1, then channel 1, ...",
      note: "DERIVED AND DISPOSABLE. Downsampled. Do not use where full resolution is required."
    ]
    metaF.setText(qupath.lib.io.GsonTools.getInstance(true).toJson(meta), "UTF-8")

    logMsg(String.format("  %s  series=%d  %dx%d x%dch  %.1f MB  (%d ms)",
        stem, series, w, h, nc, binF.length()/1e6, System.currentTimeMillis() - t0))
    nDone++
  } finally { server.close() }
}
logMsg("cached " + nDone + " slide(s), " + nSkip + " already present -> " + OUTDIR)
logMsg("Cache is derived and disposable; delete it freely. Re-measure at full resolution before locking anything.")
