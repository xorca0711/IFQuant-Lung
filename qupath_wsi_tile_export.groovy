// ============================================================================
// qupath_wsi_tile_export.groovy  --  STAGE 1 of the whole-slide (WSI) route
// ============================================================================
// QuPath is the whole-slide FRONT END only. It never measures anything.
// It opens a slide-scanner container, picks the true high-resolution series,
// detects tissue once GLOBALLY, and cuts the tissue into small calibrated
// OME-TIFF tiles that the UNMODIFIED IF_Quant_Pipeline.groovy can analyse as
// ordinary images. ALL measurement stays in the validated Fiji engine.
//
//   Stage 1  (this script)  .vsi  ->  tiles/*.ome.tif + *_RoiSet.zip + samplesheet.csv
//   Stage 2  (unchanged)    IF_Quant_Pipeline.groovy on the tiles folder
//   Stage 3  aggregate_tiles_to_slide.py -> aggregate_to_mouse.py
//
// WHY THE _RoiSet.zip MATTERS (this is the whole trick):
//   Tiles overlap by a halo so that objects at a core boundary are fully
//   imaged. Overlap would double-count at seams. IF_Quant_Pipeline.groovy's
//   resolveTissueRois() already reads a "<stem>_RoiSet.zip" beside each image
//   and restricts EVERY measurement to it. So we write, per tile, one ROI =
//   (tile CORE rectangle) INTERSECT (global tissue mask). The validated engine
//   then clips pod area to the core and reports region_area_um2 for the core
//   only. Summing tiles is exact, with zero changes to the engine.
//
// VERIFIED BEHAVIOUR (QuPath 0.7.0, Olympus VS200 .vsi, 2026-08-06):
//   * exported tiles are BIT-IDENTICAL to the source region (all 4 channels)
//   * pixel calibration and channel names survive the round trip exactly
//   * output is a single flat series (IF_Quant_Pipeline rejects multi-series)
//   * sum of per-tile core ROI areas == whole-slide tissue geometry area
//
// USAGE (headless):
//   IFQ_WSI_INPUT=D:\Confocal_Images\...\slide.vsi  (file or folder of .vsi)
//   IFQ_WSI_OUTPUT=D:\wsi_stage1
//   "X:\QuPath\QuPath-0.7.0 (console).exe" script qupath_wsi_tile_export.groovy
//
// AREA endpoints are exact across seams. CELL COUNTS are NOT: the engine
// clips nuclei at the ROI edge rather than excluding them, so a nucleus
// straddling a core boundary can be counted in both neighbours. Stage 3
// de-duplicates counts using centroid_x_um/centroid_y_um plus the tile origin
// recorded in tile_manifest.csv. See docs/WSI_TILING_WORKFLOW.md.
// ============================================================================

import qupath.lib.images.servers.ImageServer
import qupath.lib.images.servers.ImageServers
import qupath.lib.images.writers.ome.OMEPyramidWriter
import qupath.lib.images.writers.ome.OMEPyramidWriter.CompressionType
import qupath.lib.analysis.images.ContourTracing
import qupath.lib.analysis.images.SimpleImage
import qupath.lib.analysis.images.SimpleImages
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.GeometryTools
import qupath.lib.roi.interfaces.ROI
import qupath.imagej.tools.IJTools

import loci.formats.ImageReader
import loci.formats.MetadataTools
import loci.formats.FormatTools
import loci.formats.meta.IMetadata
import ome.units.UNITS

import ij.io.RoiEncoder
import ij.process.ByteProcessor
import ij.process.FloatProcessor
import ij.process.AutoThresholder
import ij.plugin.filter.RankFilters
import ij.plugin.filter.GaussianBlur

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.geom.util.AffineTransformation

import qupath.lib.io.GsonTools          // QuPath 0.7 does not bundle groovy-json
import java.awt.image.BandedSampleModel
import java.awt.image.DataBufferUShort
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------
// Settings / fail-closed helpers  (same idiom as IF_Quant_Pipeline.groovy)
// ---------------------------------------------------------------------------
def LOG_TAG = "[IFQ_WSI]"
def logMsg  = { String m -> println LOG_TAG + " " + m }

def failRun = { String message, Throwable cause = null ->
  System.err.println("FATAL: " + message)
  println LOG_TAG + " FATAL: " + message
  if (cause != null) cause.printStackTrace()
  System.exit(1)
}

def envOr = { String name, String fallback ->
  def v = System.getenv(name)
  return (v == null || v.trim().isEmpty()) ? fallback : v.trim()
}
def envInt = { String name, int fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Integer.parseInt(raw) }
  catch (Exception e) { failRun(name + " must be an integer; found '" + raw + "'"); return fallback }
}
def envDouble = { String name, double fallback ->
  String raw = envOr(name, fallback.toString())
  try { return Double.parseDouble(raw) }
  catch (Exception e) { failRun(name + " must be a number; found '" + raw + "'"); return fallback }
}
def envBool = { String name, boolean fallback ->
  String raw = envOr(name, fallback.toString()).toLowerCase()
  if (!(raw in ["true", "false"])) failRun(name + " must be true or false; found '" + raw + "'")
  return raw == "true"
}

// ---- inputs -----------------------------------------------------------------
def INPUT          = envOr("IFQ_WSI_INPUT", "")
def OUTPUT         = envOr("IFQ_WSI_OUTPUT", "")
def SLIDE_META_CSV = envOr("IFQ_WSI_SLIDE_METADATA", "")

// ---- tiling -----------------------------------------------------------------
def CORE_PX        = envInt("IFQ_WSI_CORE_PX", 2048)
def HALO_PX        = envInt("IFQ_WSI_HALO_PX", 128)
def MIN_TISSUE_UM2 = envDouble("IFQ_WSI_MIN_TILE_TISSUE_UM2", 2000.0d)

// ---- series selection (refuse to quantify the macro/label/overview) ---------
def MAX_PIXEL_UM   = envDouble("IFQ_WSI_MAX_PIXEL_UM", 0.5d)
def EXPECT_NCH     = envInt("IFQ_WSI_EXPECT_CHANNELS", 4)
def CH_PATTERNS    = envOr("IFQ_WSI_CHANNEL_PATTERNS",
                       "^\\s*dapi|fitc|488|krt.?5|alexa.?488|cy3|555|ager|tritc|alexa.?555|cy5|647|pdpn|t1a|podoplanin|alexa.?647")

// ---- tissue detection (the DENOMINATOR of the primary endpoint) ------------
def TISSUE_DS      = envDouble("IFQ_WSI_TISSUE_DOWNSAMPLE", 16.0d)
def TISSUE_BLUR    = envDouble("IFQ_WSI_TISSUE_BLUR_SIGMA", 2.0d)
def TISSUE_CLOSE_R = envDouble("IFQ_WSI_TISSUE_CLOSE_RADIUS", 4.0d)
def TISSUE_OPEN_R  = envDouble("IFQ_WSI_TISSUE_OPEN_RADIUS", 2.0d)
def MIN_FRAG_MM2   = envDouble("IFQ_WSI_MIN_FRAGMENT_MM2", 0.05d)
// PROTOCOL DECISION, default OFF. Filling interior rings fills ALVEOLAR
// AIRSPACE and inflated the tissue denominator by 12.5% on the pilot slide
// (75.06 -> 84.47 mm2). Whether airspace counts as "tissue" is a scientific
// decision, not cleanup. Leave false unless the protocol says otherwise.
def FILL_HOLES     = envBool("IFQ_WSI_FILL_INTERIOR_RINGS", false)

// ---- export -----------------------------------------------------------------
def COMPRESSION    = envOr("IFQ_WSI_COMPRESSION", "ZLIB")
def WRITE_TILE_PX  = envInt("IFQ_WSI_WRITE_TILE_PX", 256)
def PARALLEL       = envInt("IFQ_WSI_PARALLEL", 4)
def RESUME         = envBool("IFQ_WSI_RESUME", true)
def DRY_RUN        = envBool("IFQ_WSI_DRY_RUN", false)
// Smoke-test aid: stop after N tiles per slide. 0 = no cap. A capped run is
// NOT a valid analysis -- the manifest records the cap so Stage 3 refuses it.
def MAX_TILES      = envInt("IFQ_WSI_MAX_TILES_PER_SLIDE", 0)

// ---- downstream metadata ----------------------------------------------------
def PANEL          = envOr("IFQ_WSI_PANEL", "LEFT")
// The ROI name becomes the "region" column AND supplies the compartment token.
// AGER and T1A declare expectedCompartment:"alveolar"; without a matching token
// every AGER/T1A call degrades to context_unresolved / indeterminate.
def ROI_NAME       = envOr("IFQ_WSI_ROI_NAME", "alveolar_core")

if (INPUT.isEmpty())  failRun("IFQ_WSI_INPUT is required (a .vsi file or a folder containing .vsi files)")
if (OUTPUT.isEmpty()) failRun("IFQ_WSI_OUTPUT is required")
if (HALO_PX < 0)      failRun("IFQ_WSI_HALO_PX must be >= 0")
if (CORE_PX <= 0)     failRun("IFQ_WSI_CORE_PX must be > 0")
if (!ROI_NAME.toLowerCase().contains("alveol")) {
  logMsg("WARNING: IFQ_WSI_ROI_NAME='" + ROI_NAME + "' has no 'alveol' token. " +
         "AGER/T1A carry expectedCompartment:'alveolar' and will report " +
         "context_unresolved / indeterminate. KRT5 pod area is unaffected.")
}
def compType
try { compType = CompressionType.valueOf(COMPRESSION) }
catch (Exception e) { failRun("IFQ_WSI_COMPRESSION must be one of UNCOMPRESSED, LZW, ZLIB, J2K; found '" + COMPRESSION + "'") }
if (OMEPyramidWriter.isLossyCompressionType(COMPRESSION.toLowerCase())
    || COMPRESSION in ["J2K_LOSSY", "JPEG"]) {
  failRun("IFQ_WSI_COMPRESSION='" + COMPRESSION + "' is LOSSY. This is quantitative data; refusing.")
}

// ---------------------------------------------------------------------------
// Hot pixel loops. Kept @CompileStatic -- dynamic Groovy per-pixel loops over
// ~9.4 Mpx dominated the runtime (~40 s/slide) in profiling.
// ---------------------------------------------------------------------------
@groovy.transform.CompileStatic
class Px {
  /** Unsigned short[] -> FloatProcessor. */
  static FloatProcessor toFloat(short[] pix, int w, int h) {
    FloatProcessor fp = new FloatProcessor(w, h)
    float[] out = (float[]) fp.getPixels()
    for (int i = 0; i < pix.length; i++) out[i] = (float) (pix[i] & 0xFFFF)
    return fp
  }
  /** 256-bin histogram over [lo,hi]; returns the Otsu threshold in RAW units. */
  static double otsuThreshold(float[] f) {
    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE
    for (int i = 0; i < f.length; i++) { float v = f[i]; if (v < lo) lo = v; if (v > hi) hi = v }
    if (hi <= lo) return (double) lo
    int[] hist = new int[256]
    double sc = 255.0d / (hi - lo)
    for (int i = 0; i < f.length; i++) hist[(int) Math.round((f[i] - lo) * sc)]++
    int bin = new AutoThresholder().getThreshold(AutoThresholder.Method.Otsu, hist)
    return lo + bin / sc
  }
  static ByteProcessor threshold(float[] f, int w, int h, double t) {
    ByteProcessor bp = new ByteProcessor(w, h)
    byte[] out = (byte[]) bp.getPixels()
    for (int i = 0; i < f.length; i++) if (f[i] >= t) out[i] = (byte) 255
    return bp
  }
  static long countForeground(ByteProcessor bp) {
    byte[] m = (byte[]) bp.getPixels()
    long n = 0
    for (int i = 0; i < m.length; i++) if ((m[i] & 0xFF) > 127) n++
    return n
  }
  static SimpleImage toSimpleImage(ByteProcessor bp, int w, int h) {
    byte[] m = (byte[]) bp.getPixels()
    SimpleImage si = SimpleImages.createFloatImage(w, h)
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++)
        si.setValue(x, y, ((m[y * w + x] & 0xFF) > 127) ? 1f : 0f)
    return si
  }
}

// ---------------------------------------------------------------------------
// Retry wrapper. The external volume holding the slides dropped out mid-session
// during development; a whole-slide run is tens of minutes long.
// ---------------------------------------------------------------------------
/**
 * RFC4180 field escaping. The Olympus series name is literally
 * "20x_DAPI, FITC, Cy3, Cy5(Gray)_01" -- it contains commas, so an unquoted
 * manifest silently shifts every later column.
 */
def csvField = { v ->
  String s = (v == null) ? "" : v.toString()
  if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r"))
    return "\"" + s.replace("\"", "\"\"") + "\""
  return s
}
def csvRow = { List vals -> vals.collect { csvField(it) }.join(",") }

def withRetry = { String what, int attempts, Closure body ->
  Throwable last = null
  for (int i = 1; i <= attempts; i++) {
    try { return body() }
    catch (Throwable t) {
      last = t
      logMsg("  retry " + i + "/" + attempts + " after failure in " + what + ": " + t.getMessage())
      Thread.sleep(2000L * i)
    }
  }
  throw new IllegalStateException("Gave up on " + what + " after " + attempts + " attempts", last)
}

// ---------------------------------------------------------------------------
// Series enumeration. checkImageSupport() silently DROPS thumbnail series and
// is not a series count. loci getSeriesCount() sees all of them.
// ---------------------------------------------------------------------------
def enumerateSeries = { String filePath ->
  def out = []
  def reader = new ImageReader()
  reader.setFlattenedResolutions(false)   // must match QuPath's own setting
  IMetadata meta = MetadataTools.createOMEXMLMetadata()
  reader.setMetadataStore(meta)
  try {
    reader.setId(filePath)
    int n = reader.getSeriesCount()
    for (int s = 0; s < n; s++) {
      reader.setSeries(s); reader.setResolution(0)
      def pxW = meta.getPixelsPhysicalSizeX(s)
      def pxH = meta.getPixelsPhysicalSizeY(s)
      int nCh = reader.getEffectiveSizeC()
      def chNames = (0..<nCh).collect { c -> try { meta.getChannelName(s, c) } catch (Exception e) { null } }
      out << [ series: s, name: meta.getImageName(s),
               width: reader.getSizeX(), height: reader.getSizeY(),
               nChannels: nCh, nZSlices: reader.getSizeZ(), nTimepoints: reader.getSizeT(),
               pixelType: FormatTools.getPixelTypeString(reader.getPixelType()),
               pixelWidthMicrons : pxW == null ? Double.NaN : pxW.value(UNITS.MICROMETER).doubleValue(),
               pixelHeightMicrons: pxH == null ? Double.NaN : pxH.value(UNITS.MICROMETER).doubleValue(),
               nResolutions: reader.getResolutionCount(),
               isThumbnail: reader.isThumbnailSeries(), channelNames: chNames ]
    }
  } finally { try { reader.close() } catch (Exception ignore) {} }
  return out
}

/**
 * Pick the one true scan series. Deliberately strict: this is the single
 * decision that separates a real result from a plausible wrong one.
 * QuPath opens series 0 by default, which on VS200 .vsi is the LABEL image;
 * and the "overview" series is a genuine calibrated DAPI fluorescence image at
 * 1.7 um/px, so a naive "reject > 2 um/px" rule would let it through.
 */
def selectSeries = { List seriesList, double maxPxUm, int nChReq, java.util.regex.Pattern chPat ->
  def rejected = []
  def cands = seriesList.findAll { s ->
    if (s.isThumbnail)                     { rejected << "${s.series}: thumbnail series";              return false }
    if (s.nChannels != nChReq)             { rejected << "${s.series}: nChannels=${s.nChannels} != ${nChReq}"; return false }
    if (s.nZSlices != 1)                   { rejected << "${s.series}: nZSlices=${s.nZSlices} != 1";   return false }
    // NaN handling: under Groovy operator semantics NaN compares GREATER than
    // every finite value, so do this explicitly rather than relying on it.
    if (Double.isNaN(s.pixelWidthMicrons) || !(s.pixelWidthMicrons > 0.0d)) {
      rejected << "${s.series}: uncalibrated (pixelWidthMicrons=${s.pixelWidthMicrons})"; return false }
    if (s.pixelWidthMicrons > maxPxUm)     { rejected << "${s.series}: ${s.pixelWidthMicrons} um/px > ${maxPxUm}"; return false }
    def bad = s.channelNames.findAll { nm -> nm == null || !(nm.toLowerCase() =~ chPat) }
    if (!bad.isEmpty())                    { rejected << "${s.series}: channel names not recognised ${s.channelNames}"; return false }
    return true
  }
  return [candidates: cands, rejected: rejected]
}

// ---------------------------------------------------------------------------
// Slide-level metadata. mouse_id must be real: aggregate_to_mouse.py rejects
// "NA"/"UNKNOWN", so guessing here would only surface as a confusing failure
// three stages later. Fail loudly instead.
// ---------------------------------------------------------------------------
def SLIDE_PATTERN = ~/(?i)^IFNg\s+KO\((het|hom)\)\s+([\d.]+)\s+(m[\w.-]+)\s+(pr8)\s+(no\s+infection|infection)\s*$/

def parseSlideName = { String stem ->
  def m = SLIDE_PATTERN.matcher(stem)
  if (!m.matches()) return null
  String geno = m.group(1).toLowerCase()
  String mouse = m.group(3)
  String cond  = m.group(5).toLowerCase().replaceAll(/\s+/, "_")
  return [ mouse_id : mouse,
           genotype : (geno == "het" ? "IFNg_KO_het" : "IFNg_KO_hom"),
           condition: (cond == "infection" ? "PR8" : "naive"),
           harvest  : m.group(2) ]
}

def readSlideMetadataCsv = { String p ->
  def out = [:]
  if (p.isEmpty()) return out
  def f = new File(p)
  if (!f.isFile()) failRun("IFQ_WSI_SLIDE_METADATA not found: " + p)
  def lines = f.readLines().findAll { !it.trim().isEmpty() && !it.trim().startsWith("#") }
  if (lines.isEmpty()) failRun("IFQ_WSI_SLIDE_METADATA is empty: " + p)
  def hdr = lines[0].split(",").collect { it.trim().toLowerCase() }
  ["vsi_filename", "mouse_id", "genotype", "condition"].each { req ->
    if (!hdr.contains(req)) failRun("IFQ_WSI_SLIDE_METADATA must have a '" + req + "' column; found " + hdr)
  }
  lines.drop(1).each { line ->
    def parts = line.split(",", -1).collect { it.trim() }
    def row = [:]; hdr.eachWithIndex { hname, i -> row[hname] = i < parts.size() ? parts[i] : "" }
    out[row.vsi_filename] = [ mouse_id: row.mouse_id, genotype: row.genotype,
                              condition: row.condition, harvest: (row.harvest ?: "") ]
  }
  return out
}

// ---------------------------------------------------------------------------
// Resolve the input slide list
// ---------------------------------------------------------------------------
def inFile = new File(INPUT)
def slides = []
if (inFile.isDirectory()) {
  // .vsi ONLY. Never open .ets directly -- those are the internal pyramid tiles
  // inside the hidden _<name>_ sidecar folder and give a broken partial read.
  slides = inFile.listFiles().findAll { it.isFile() && it.name.toLowerCase().endsWith(".vsi") }.sort { it.name }
} else if (inFile.isFile()) {
  if (!inFile.name.toLowerCase().endsWith(".vsi"))
    failRun("IFQ_WSI_INPUT must be a .vsi file (never .ets): " + INPUT)
  slides = [inFile]
} else {
  failRun("IFQ_WSI_INPUT does not exist: " + INPUT)
}
if (slides.isEmpty()) failRun("No .vsi files found under " + INPUT)

def slideMetaCsv = readSlideMetadataCsv(SLIDE_META_CSV)
def outRoot = new File(OUTPUT); outRoot.mkdirs()
def chPattern = java.util.regex.Pattern.compile(CH_PATTERNS, java.util.regex.Pattern.CASE_INSENSITIVE)

logMsg("slides            : " + slides.size())
logMsg("core/halo px      : " + CORE_PX + " / " + HALO_PX + "  (export " + (CORE_PX + 2*HALO_PX) + " px)")
logMsg("tissue downsample : " + TISSUE_DS + "   fillInteriorRings=" + FILL_HOLES)
logMsg("compression       : " + COMPRESSION + " (lossless)   panel=" + PANEL + "   roiName=" + ROI_NAME)
logMsg("output            : " + outRoot.getAbsolutePath())

def runRecord = [ schema_version: "1.0", stage: "wsi_tile_export",
                  generated_utc : java.time.Instant.now().toString(),
                  qupath_series_selection: [ max_pixel_um: MAX_PIXEL_UM, expect_channels: EXPECT_NCH ],
                  tiling: [ core_px: CORE_PX, halo_px: HALO_PX, min_tile_tissue_um2: MIN_TISSUE_UM2 ],
                  tissue: [ downsample: TISSUE_DS, blur_sigma_px: TISSUE_BLUR,
                            close_radius_px: TISSUE_CLOSE_R, open_radius_px: TISSUE_OPEN_R,
                            min_fragment_mm2: MIN_FRAG_MM2, fill_interior_rings: FILL_HOLES,
                            threshold_method: "Otsu", channel: "nuclear/DAPI (index 0)" ],
                  export: [ compression: COMPRESSION, write_tile_px: WRITE_TILE_PX,
                            format: "OME-TIFF, single series, single resolution" ],
                  downstream: [ panel: PANEL, roi_name: ROI_NAME ],
                  slides: [] ]

def mouseIndex = [:]   // mouse_id -> [genotype, condition]  (collision guard)
int totalTiles = 0

// ===========================================================================
// PER-SLIDE
// ===========================================================================
slides.each { slideFile ->
  String stem = slideFile.name.replaceFirst(/\.[^.]+$/, "")
  logMsg("")
  logMsg("=================================================================")
  logMsg("SLIDE: " + slideFile.name)

  // ---- metadata --------------------------------------------------------
  def md = slideMetaCsv[slideFile.name] ?: slideMetaCsv[stem] ?: parseSlideName(stem)
  if (md == null || !md.mouse_id || md.mouse_id.toString().trim().isEmpty()) {
    failRun("Cannot determine mouse_id for '" + slideFile.name + "'.\n" +
            "  Either rename to the documented convention " +
            "'IFNg KO(het|hom) <date> <mouse> pr8 [no ]infection.vsi'\n" +
            "  or supply IFQ_WSI_SLIDE_METADATA=<csv with vsi_filename,mouse_id,genotype,condition>.\n" +
            "  Refusing to emit mouse_id='NA' -- aggregate_to_mouse.py would reject it later.")
  }
  def prev = mouseIndex[md.mouse_id]
  if (prev != null && (prev.genotype != md.genotype || prev.condition != md.condition)) {
    failRun("mouse_id '" + md.mouse_id + "' maps to two different (genotype, condition) pairs: " +
            prev + " and " + md + ". n = MICE, so this must be resolved before analysis.")
  }
  mouseIndex[md.mouse_id] = [genotype: md.genotype, condition: md.condition]
  logMsg("  mouse_id=" + md.mouse_id + "  genotype=" + md.genotype + "  condition=" + md.condition)

  // ---- series selection ------------------------------------------------
  def seriesList = withRetry("enumerateSeries(" + slideFile.name + ")", 3) { enumerateSeries(slideFile.getAbsolutePath()) }
  logMsg("  series found: " + seriesList.size())
  seriesList.each { s ->
    logMsg(String.format("    [%d] %-42s %6dx%-6d C=%d Z=%d %8.4f um/px %s%s",
        s.series, (s.name ?: "?"), s.width, s.height, s.nChannels, s.nZSlices,
        s.pixelWidthMicrons, s.pixelType, s.isThumbnail ? " (thumbnail)" : ""))
  }
  def sel = selectSeries(seriesList, MAX_PIXEL_UM, EXPECT_NCH, chPattern)
  if (sel.candidates.size() != 1) {
    failRun("Expected exactly ONE series with <= " + MAX_PIXEL_UM + " um/px, " + EXPECT_NCH +
            " channels, Z=1 and recognisable channel names in '" + slideFile.name + "'.\n" +
            "  Found " + sel.candidates.size() + " candidate(s): " + sel.candidates.collect { it.series } + "\n" +
            "  Rejections: " + sel.rejected.join("; ") + "\n" +
            "  Refusing to guess -- picking the wrong series silently quantifies the macro/overview image.")
  }
  def chosen = sel.candidates[0]
  logMsg("  SELECTED series " + chosen.series + " '" + chosen.name + "' " +
         chosen.width + "x" + chosen.height + " @ " + chosen.pixelWidthMicrons + " um/px " +
         chosen.channelNames)

  ImageServer server = withRetry("buildServer", 3) {
    ImageServers.buildServer(slideFile.toURI(), "--series", "" + chosen.series)
  }
  def cal = server.getPixelCalibration()
  if (!cal.hasPixelSizeMicrons())
    failRun("Series " + chosen.series + " of " + slideFile.name + " has no pixel calibration.")
  double pxUm = cal.getPixelWidthMicrons()
  double pxUmH = cal.getPixelHeightMicrons()
  double aspect = Math.abs(pxUmH - pxUm) / pxUm
  if (aspect > 0.01d)
    failRun("Non-square pixels (" + pxUm + " x " + pxUmH + " um, " + (aspect*100) + "%). " +
            "IF_Quant_Pipeline.groovy rejects > 1%.")
  int W = server.getWidth(), H = server.getHeight()

  // ---- global tissue detection ----------------------------------------
  logMsg("  detecting tissue on channel 0 at downsample " + TISSUE_DS + " ...")
  long t0 = System.currentTimeMillis()
  def img = withRetry("readRegion(tissue)", 3) { server.readRegion(TISSUE_DS, 0, 0, W, H) }
  def raster = img.getRaster()
  int mw = img.getWidth(), mh = img.getHeight()
  def sm = raster.getSampleModel(), db = raster.getDataBuffer()
  short[] pix
  boolean fast = (db instanceof DataBufferUShort) && (sm instanceof BandedSampleModel) &&
                 raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0 &&
                 sm.getBankIndices()[0] == 0 && sm.getBandOffsets()[0] == 0 &&
                 sm.getScanlineStride() == mw && db.getOffsets()[0] == 0
  if (fast) {
    pix = ((DataBufferUShort) db).getData(0)
  } else {
    int[] tmp = new int[mw * mh]
    raster.getSamples(0, 0, mw, mh, 0, tmp)
    pix = new short[mw * mh]
    for (int i = 0; i < tmp.length; i++) pix[i] = (short) tmp[i]
  }

  def fp = Px.toFloat(pix, mw, mh)
  new GaussianBlur().blurGaussian(fp, TISSUE_BLUR)
  float[] f = (float[]) fp.getPixels()
  double thr = Px.otsuThreshold(f)
  def bp = Px.threshold(f, mw, mh, thr)
  // RankFilters MAX/MIN, NOT ByteProcessor.dilate()/erode(): with
  // ij.Prefs.blackBackground=false (the headless default) those are polarity
  // inverted and silently destroy the mask.
  def rf = new RankFilters()
  rf.rank(bp, TISSUE_CLOSE_R, RankFilters.MAX); rf.rank(bp, TISSUE_CLOSE_R, RankFilters.MIN)  // closing
  rf.rank(bp, TISSUE_OPEN_R,  RankFilters.MIN); rf.rank(bp, TISSUE_OPEN_R,  RankFilters.MAX)  // opening
  long fgPx = Px.countForeground(bp)
  if (fgPx <= 0)
    failRun("Tissue detection found NO foreground in " + slideFile.name +
            " (Otsu threshold " + thr + "). Refusing to emit an empty tiling.")
  def simple = Px.toSimpleImage(bp, mw, mh)

  // Pass the SAME downsample into the RegionRequest: createTracedGeometry then
  // does the scaling AND the origin offset, giving full-resolution coordinates.
  // Mixing raw pyramid levels with a different scale factor introduces a ~0.4%
  // Y stretch on this format.
  def req = RegionRequest.createInstance(server.getPath(), TISSUE_DS, 0, 0, W, H)
  Geometry g = ContourTracing.createTracedGeometry(simple, 0.5d, Double.POSITIVE_INFINITY, req)
  double minFragPx = (MIN_FRAG_MM2 * 1e6) / (pxUm * pxUm)
  g = GeometryTools.removeFragments(g, minFragPx)
  if (FILL_HOLES) g = GeometryTools.removeInteriorRings(g, minFragPx)
  g = GeometryTools.constrainToBounds(g, 0, 0, W, H)
  if (g == null || g.isEmpty()) failRun("Tissue geometry empty after cleanup for " + slideFile.name)
  double tissueMm2 = g.getArea() * pxUm * pxUm / 1e6
  logMsg(String.format("  tissue: Otsu=%.2f  mask=%.2f%%  area=%.2f mm2  (%d ms)",
      thr, 100.0 * fgPx / (mw * (double) mh), tissueMm2, System.currentTimeMillis() - t0))

  // ---- tile grid --------------------------------------------------------
  def slideOut = new File(outRoot, stem)
  def tilesDir = new File(slideOut, "tiles")
  tilesDir.mkdirs()
  def prep = new PreparedGeometryFactory().create(g)

  def manifestRows = []
  double coreTissueTotalPx = 0.0d
  int nWritten = 0, nSkipped = 0, nResumed = 0
  long tExport = System.currentTimeMillis()

  boolean capped = false
  for (int cy = 0; cy < H && !capped; cy += CORE_PX) {
    for (int cx = 0; cx < W; cx += CORE_PX) {
      if (MAX_TILES > 0 && manifestRows.size() >= MAX_TILES) { capped = true; break }
      int cw = Math.min(CORE_PX, W - cx)
      int chh = Math.min(CORE_PX, H - cy)
      def rect = GeometryTools.createRectangle(cx, cy, cw, chh)
      if (!prep.intersects(rect)) continue
      Geometry gi = g.intersection(rect)
      if (gi == null || gi.isEmpty()) continue
      double coreTissuePx = gi.getArea()
      if (coreTissuePx * pxUm * pxUm < MIN_TISSUE_UM2) { nSkipped++; continue }

      // export window = core grown by the halo, clipped to the slide
      int ex = Math.max(0, cx - HALO_PX)
      int ey = Math.max(0, cy - HALO_PX)
      int ex2 = Math.min(W, cx + cw + HALO_PX)
      int ey2 = Math.min(H, cy + chh + HALO_PX)
      int ew = ex2 - ex, eh = ey2 - ey

      String tileId   = String.format("x%06d_y%06d", cx, cy)
      String tileBase = stem.replaceAll(/[^A-Za-z0-9._-]+/, "-") + "_" + tileId
      def tileFile    = new File(tilesDir, tileBase + ".ome.tif")
      // IF_Quant_Pipeline strips ONLY the final extension, so the companion for
      // "foo.ome.tif" must be "foo.ome_RoiSet.zip" -- NOT "foo_RoiSet.zip".
      def roiFile     = new File(tilesDir, tileBase + ".ome_RoiSet.zip")

      coreTissueTotalPx += coreTissuePx

      boolean haveTile = RESUME && tileFile.isFile() && tileFile.length() > 0 && roiFile.isFile()
      if (haveTile) {
        nResumed++
      } else if (!DRY_RUN) {
        // --- ROI: core INTERSECT tissue, in coordinates local to the EXPORT
        //     window (which is clipped at slide edges, so use ex/ey not cx-HALO)
        Geometry local = AffineTransformation
            .translationInstance((double) -ex, (double) -ey).transform(gi)
        ROI roi = GeometryTools.geometryToROI(local, ImagePlane.getDefaultPlane())
        def ijRoi = IJTools.convertToIJRoi(roi, new ij.measure.Calibration(), 1.0d)
        ijRoi.setName(ROI_NAME)
        def bos = new ByteArrayOutputStream()
        new RoiEncoder(bos).write(ijRoi)     // returns void; do not test its value
        def zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(roiFile)))
        try {
          zos.putNextEntry(new ZipEntry(ROI_NAME + ".roi"))
          zos.write(bos.toByteArray())
          zos.closeEntry()
        } finally { zos.close() }

        // --- tile: flat single-series OME-TIFF (multi-series is rejected by Stage 2)
        withRetry("writeTile(" + tileBase + ")", 3) {
          if (tileFile.exists()) tileFile.delete()
          def ser = new OMEPyramidWriter.Builder(server)
              .region(ex, ey, ew, eh)
              .downsamples(1.0d)
              .compression(compType)
              .channelsPlanar()
              .tileSize(WRITE_TILE_PX)
              .parallelize(PARALLEL)
              .name(tileBase)
              .build()
          OMEPyramidWriter.createWriter(ser).writeImage(tileFile.getAbsolutePath())
          return true
        }
        nWritten++
      }

      manifestRows << [
        tile_id: tileId, tile_file: tileFile.name, roiset_file: roiFile.name,
        slide_stem: stem, source_vsi: slideFile.name,
        series_index: chosen.series, series_name: chosen.name,
        pixel_size_um: pxUm,
        core_x: cx, core_y: cy, core_w: cw, core_h: chh,
        export_x: ex, export_y: ey, export_w: ew, export_h: eh,
        halo_left: cx - ex, halo_top: cy - ey,
        halo_right: ex2 - (cx + cw), halo_bottom: ey2 - (cy + chh),
        core_tissue_area_px: coreTissuePx,
        core_tissue_area_um2: coreTissuePx * pxUm * pxUm,
        mouse_id: md.mouse_id, genotype: md.genotype, condition: md.condition,
        section_id: tileId, panel: PANEL, region_name: ROI_NAME
      ]

      if ((manifestRows.size() % 25) == 0)
        logMsg("    ... " + manifestRows.size() + " tiles (" + nWritten + " written, " + nResumed + " resumed)")
    }
  }

  if (manifestRows.isEmpty())
    failRun("No tiles intersect tissue for " + slideFile.name + " -- refusing to emit an empty run.")

  // ---- reconciliation: per-tile core areas must sum to the slide tissue ----
  double sumMm2 = coreTissueTotalPx * pxUm * pxUm / 1e6
  double relDiff = Math.abs(sumMm2 - tissueMm2) / tissueMm2
  logMsg(String.format("  tiles=%d written=%d resumed=%d skipped(<%.0f um2)=%d   (%d ms)",
      manifestRows.size(), nWritten, nResumed, MIN_TISSUE_UM2, nSkipped,
      System.currentTimeMillis() - tExport))
  logMsg(String.format("  SEAM CHECK: sum(core tissue) = %.4f mm2 vs slide tissue %.4f mm2  (rel diff %.3e)",
      sumMm2, tissueMm2, relDiff))
  if (capped) {
    logMsg("  *** CAPPED at IFQ_WSI_MAX_TILES_PER_SLIDE=" + MAX_TILES +
           " -- SMOKE TEST ONLY, coverage is incomplete and the seam check above is expected to fail. ***")
  } else if (relDiff > 1e-6 && nSkipped == 0) {
    logMsg("  WARNING: core areas do not sum to the slide tissue area. Cores must tile the slide exactly.")
  }

  // ---- samplesheet.csv, written INTO the tiles folder (= Stage 2 INPUT_DIR) --
  def ss = new StringBuilder("filename,mouse_id,section_id,genotype,condition,panel\n")
  manifestRows.each { r ->
    ss.append(csvRow([r.tile_file, r.mouse_id, r.section_id, r.genotype, r.condition, r.panel])).append("\n")
  }
  new File(tilesDir, "samplesheet.csv").setText(ss.toString(), "UTF-8")

  // ---- tile_manifest.csv --------------------------------------------------
  def cols = manifestRows[0].keySet() as List
  def mf = new StringBuilder(csvRow(cols)).append("\n")
  manifestRows.each { r -> mf.append(csvRow(cols.collect { c -> r[c] })).append("\n") }
  new File(slideOut, "tile_manifest.csv").setText(mf.toString(), "UTF-8")

  totalTiles += manifestRows.size()
  runRecord.slides << [
    source_vsi: slideFile.name, slide_stem: stem,
    series_index: chosen.series, series_name: chosen.name,
    width: W, height: H, pixel_size_um: pxUm, n_channels: chosen.nChannels,
    channel_names: chosen.channelNames,
    tissue_threshold_otsu: thr, tissue_area_mm2: tissueMm2,
    sum_core_tissue_mm2: sumMm2, seam_check_rel_diff: relDiff,
    n_tiles: manifestRows.size(), n_written: nWritten, n_resumed: nResumed,
    n_skipped_low_tissue: nSkipped,
    coverage_complete: !capped, max_tiles_cap: MAX_TILES, dry_run: DRY_RUN,
    mouse_id: md.mouse_id, genotype: md.genotype, condition: md.condition,
    tiles_dir: tilesDir.getAbsolutePath()
  ]
  server.close()
}

new File(outRoot, "stage1_manifest.json").setText(GsonTools.getInstance(true).toJson(runRecord), "UTF-8")

logMsg("")
logMsg("=================================================================")
logMsg("DONE. " + slides.size() + " slide(s), " + totalTiles + " tiles -> " + outRoot.getAbsolutePath())
logMsg("Wrote stage1_manifest.json and, per slide, tile_manifest.csv + tiles/samplesheet.csv")
logMsg("")
logMsg("NEXT (Stage 2) -- per slide, against the UNMODIFIED engine:")
logMsg("  IFQ_INPUT_DIR=<slide>/tiles  IFQ_OUTPUT_DIR=<slide>/analysis")
logMsg("  IFQ_PANEL=" + PANEL + "                     # default is 'T' (pilot) -- MUST be set")
logMsg("  IFQ_MIN_INCLUDED_NUCLEI=0            # or sparse tiles are dropped, losing their area")
logMsg("  IFQ_KRT5_THRESHOLD / IFQ_AGER_THRESHOLD / IFQ_T1A_THRESHOLD")
logMsg("                                       # MANDATORY: adaptive Otsu on a background tile")
logMsg("                                       # reports KRT5_pod_area_frac ~0.89")
