// ============================================================================
// export_tissue_region_masks.groovy -- reconstruct and export the per-field
// TISSUE REGION mask for runs whose regions came from auto_dapi detection.
// ============================================================================
// WHY THIS EXISTS
//   evaluate_endpoints.groovy needs a per-pixel REGION to clip the relational
//   numerator to. For whole-slide runs that region comes from the per-tile
//   <stem>_RoiSet.zip. Whole-field confocal runs have no RoiSet, and -- this was
//   verified, not assumed -- IF_Quant_Pipeline.groovy does NOT export the tissue
//   region it used. It exports:
//       <fileKey>__<marker>_<suffix>.tif            whole-field area masks
//       <fileKey>__<region>__nuclei_mask.tif        per-region NUCLEI labels
//       <fileKey>__<region>__DAPI_candidate_mask.tif
//   and nothing whose foreground is the tissue region itself. The region exists
//   only as an in-memory ShapeRoi (IF_Quant_Pipeline.groovy resolveTissueRois,
//   the auto_dapi branch) and survives to disk only as the scalar
//   region_area_um2 in run_summary.csv.
//
//   Clipping matters: on this confocal batch the unclipped whole-field T1A area
//   is up to 4x the region-clipped value the engine reported. Evaluating the
//   endpoint on the unclipped field would be wrong, not conservative.
//
//   So this script re-derives the region from the same source pixels with the
//   same constants, writes it as a binary mask, and PROVES the reconstruction by
//   reconciling its calibrated area against the engine's own region_area_um2.
//   That reconciliation is the whole point: if the numbers do not agree the
//   reconstruction is rejected and nothing downstream may use it.
//
// WHAT IT DOES NOT DO
//   It does not modify IF_Quant_Pipeline.groovy (frozen), it does not write into
//   the analysis directory, and it never writes to the source image tree.
//   It re-thresholds nothing that the endpoint uses as a numerator -- the marker
//   masks still come from the engine untouched.
//
// SCOPE / FAIL-CLOSED
//   Only tissue_roi_source == "auto_dapi" can be reconstructed. Manual-RoiSet
//   and whole_field runs are refused, because for those the region is either
//   already on disk (use region_mode=roiset) or trivially the field.
//
// ENV
//   IFQ_ANALYSIS_DIR    <run>/analysis     (needs run_summary.csv + run_manifest.json)
//   IFQ_SOURCE_DIR      root the manifest relative_path values hang off (READ ONLY)
//   IFQ_TISSUE_MASK_DIR where to write <output_key>/<fileKey>__<region>__tissue_region_mask.tif
//   IFQ_TISSUE_RECON_TOL max allowed |recon-reported|/reported per field (default 0.001)
//   IFQ_PANEL_FILTER    optional, e.g. "LEFT" -- only export for that panel
//
// RUN (Fiji launcher .exe is broken on win-arm64; call the JVM directly)
// ============================================================================

import ij.IJ
import ij.ImagePlus
import ij.Prefs
import ij.gui.Roi
import ij.gui.ShapeRoi
import ij.measure.Calibration
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.ChannelSplitter
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.filter.ThresholdToSelection
import ij.plugin.filter.GaussianBlur
import ij.process.ByteProcessor
import ij.process.ImageProcessor
import ij.process.ImageStatistics
import java.awt.Rectangle
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def LOG = "[IFQ_TISSUE_EXPORT]"
def logMsg = { String m -> println LOG + " " + m }
def failRun = { String m ->
  System.err.println("FATAL: " + m); println LOG + " FATAL: " + m; System.exit(1)
}
def envOr = { String n, String d ->
  def v = System.getenv(n); return (v == null || v.trim().isEmpty()) ? d : v.trim()
}

// ---- constants copied VERBATIM from IF_Quant_Pipeline.groovy lines 363-365 --
// If the engine ever changes these the reconciliation below will fail loudly,
// which is the intended tripwire.
final double TISSUE_BLUR_SIGMA_PX = 4.0
final String TISSUE_THRESH_METHOD = "Triangle"
final double TISSUE_MIN_AREA_UM2  = 2000.0

Prefs.blackBackground = true   // engine main() sets this; masks are 0/255

def ANALYSIS_DIR = envOr("IFQ_ANALYSIS_DIR", "")
def SOURCE_DIR   = envOr("IFQ_SOURCE_DIR", "")
def MASK_DIR     = envOr("IFQ_TISSUE_MASK_DIR", "")
def PANEL_FILTER = envOr("IFQ_PANEL_FILTER", "")
double TOL       = Double.parseDouble(envOr("IFQ_TISSUE_RECON_TOL", "0.001"))
if (ANALYSIS_DIR.isEmpty()) failRun("IFQ_ANALYSIS_DIR is required")
if (SOURCE_DIR.isEmpty())   failRun("IFQ_SOURCE_DIR is required")
if (MASK_DIR.isEmpty())     failRun("IFQ_TISSUE_MASK_DIR is required")
if (new File(MASK_DIR).getCanonicalPath() == new File(ANALYSIS_DIR).getCanonicalPath())
  failRun("IFQ_TISSUE_MASK_DIR must not be the analysis directory; that directory holds " +
          "the measured result and this script must not add files to it")

// ---- RFC4180-ish CSV reader (series names contain commas) -------------------
def csvSplit = { String line ->
  def out = []; def cur = new StringBuilder(); boolean q = false
  for (int i = 0; i < line.length(); i++) {
    char c = line.charAt(i)
    if (q) {
      if (c == '"') { if (i+1 < line.length() && line.charAt(i+1) == '"') { cur.append('"'); i++ } else q = false }
      else cur.append(c)
    } else if (c == '"') q = true
    else if (c == ',') { out << cur.toString(); cur.setLength(0) }
    else cur.append(c)
  }
  out << cur.toString(); return out
}

// ---- helpers copied VERBATIM from IF_Quant_Pipeline.groovy ------------------
// particlesToRois  (engine lines 969-1068)
// buildThresholdMask (engine lines 1584-1605)
// measureRoi (engine lines 1346-1353)
// They are duplicated rather than imported because the engine is a frozen
// batch script that cannot be sourced without running a batch. Any drift from
// the originals shows up immediately as a region_area_um2 mismatch.
def particlesToRois(ImagePlus imp, double minAreaCal, boolean excludeEdges,
                    double maxAreaCal = Double.POSITIVE_INFINITY) {
  ImagePlus work = imp
  Roi restriction = imp.getRoi()
  if (restriction != null) {
    work = new ImagePlus(imp.getTitle() + "_roi_restricted",
                         imp.getProcessor().duplicate())
    work.setCalibration(imp.getCalibration())
    ImageProcessor wp = work.getProcessor()
    Rectangle rb = restriction.getBounds()
    ImageProcessor rm = restriction.getMask()
    int rx = (int)rb.x, ry = (int)rb.y
    int rw = (int)rb.width, rh = (int)rb.height
    for (int y = 0; y < wp.getHeight(); y++) {
      for (int x = 0; x < wp.getWidth(); x++) {
        boolean inBounds = x >= rx && x < rx + rw && y >= ry && y < ry + rh
        if (!inBounds || (rm != null && rm.get(x - rx, y - ry) == 0)) wp.set(x, y, 0)
      }
    }
  }
  int opts = ParticleAnalyzer.SHOW_ROI_MASKS
  if (excludeEdges) opts |= ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES
  def rt = new ResultsTable()
  def workCal = work.getCalibration()
  double pixelArea = workCal.pixelWidth * workCal.pixelHeight
  double minAreaPixels = pixelArea > 0 ? minAreaCal / pixelArea : minAreaCal
  double maxAreaPixels = Double.isFinite(maxAreaCal) ?
    (pixelArea > 0 ? maxAreaCal / pixelArea : maxAreaCal) : Double.MAX_VALUE
  def pa = new ParticleAnalyzer(opts, Measurements.AREA, rt,
                                minAreaPixels, maxAreaPixels)
  pa.setHideOutputImage(true)
  ImageProcessor src = work.getProcessor()
  src.setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE)
  if (!pa.analyze(work)) {
    if (!work.is(imp)) work.close()
    return []
  }
  ImagePlus labels = pa.getOutputImage()
  if (!work.is(imp)) work.close()
  if (labels == null) return []
  ImageProcessor lp = labels.getProcessor()
  int nLabels = rt.size()
  int[] minX = new int[nLabels + 1]
  int[] minY = new int[nLabels + 1]
  int[] maxX = new int[nLabels + 1]
  int[] maxY = new int[nLabels + 1]
  java.util.Arrays.fill(minX, lp.getWidth())
  java.util.Arrays.fill(minY, lp.getHeight())
  java.util.Arrays.fill(maxX, -1)
  java.util.Arrays.fill(maxY, -1)
  for (int y = 0; y < lp.getHeight(); y++) {
    for (int x = 0; x < lp.getWidth(); x++) {
      int label = lp.get(x, y)
      if (label < 1 || label > nLabels) continue
      if (x < minX[label]) minX[label] = x
      if (x > maxX[label]) maxX[label] = x
      if (y < minY[label]) minY[label] = y
      if (y > maxY[label]) maxY[label] = y
    }
  }
  def out = []
  for (int i = 1; i <= nLabels; i++) {
    if (maxX[i] < minX[i] || maxY[i] < minY[i]) continue
    int w = maxX[i] - minX[i] + 1
    int h = maxY[i] - minY[i] + 1
    def bp = new ByteProcessor(w, h)
    for (int yy = 0; yy < h; yy++) {
      for (int xx = 0; xx < w; xx++) {
        if (lp.get(minX[i] + xx, minY[i] + yy) == i) bp.set(xx, yy, 255)
      }
    }
    bp.setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE)
    def particle = new ImagePlus("particle_label_" + i, bp)
    def r = ThresholdToSelection.run(particle)
    if (r != null) {
      def rb = r.getBounds()
      r.setLocation(minX[i] + rb.x, minY[i] + rb.y)
      out << r
    }
    particle.close()
  }
  labels.close()
  return out
}

def buildThresholdMask(ImagePlus ch, double blurSigma, String method) {
  ImagePlus dup = ch.duplicate()
  if (blurSigma > 0) new GaussianBlur().blurGaussian(dup.getProcessor(), blurSigma)
  IJ.setAutoThreshold(dup, method + " dark")
  double thr = dup.getProcessor().getMinThreshold()
  if (!Double.isFinite(thr) || thr < 0.0d) {
    dup.close()
    throw new IllegalStateException("Could not resolve a valid " + method +
      " threshold for channel '" + ch.getTitle() + "'")
  }
  IJ.run(dup, "Convert to Mask", "")
  dup.setCalibration(ch.getCalibration())
  return dup
}

def measureRoiArea(ImagePlus imp, Roi roi) {
  ImageProcessor ip = imp.getProcessor()
  ip.setRoi(roi)
  ImageStatistics st = ImageStatistics.getStatistics(
      ip, Measurements.MEAN | Measurements.AREA | Measurements.CENTROID,
      imp.getCalibration())
  return st.area
}

def bfOpen = { String path ->
  def opts = new ImporterOptions()
  opts.setId(path)
  opts.setSplitChannels(false)
  opts.setColorMode(ImporterOptions.COLOR_MODE_GRAYSCALE)
  opts.setVirtual(false)
  opts.setAutoscale(false)
  def imps = BF.openImagePlus(opts)
  if (imps == null || imps.length == 0)
    throw new IOException("Bio-Formats returned no image series for: " + path)
  if (imps.length != 1) {
    imps.each { if (it != null) { it.changes = false; it.close() } }
    throw new IllegalArgumentException("Bio-Formats found " + imps.length + " series in " + path)
  }
  return imps[0]
}

// ---- inputs -----------------------------------------------------------------
def summaryFile = new File(ANALYSIS_DIR, "run_summary.csv")
if (!summaryFile.isFile()) failRun("No run_summary.csv in " + ANALYSIS_DIR)
def manifestFile = new File(ANALYSIS_DIR, "run_manifest.json")
if (!manifestFile.isFile()) failRun("No run_manifest.json in " + ANALYSIS_DIR)

def slurper = new groovy.json.JsonSlurper()
def manifest = slurper.parse(manifestFile)
def byKey = [:]
manifest.images.each { im ->
  if (im.status == "success" && im.output_key != null) byKey[im.output_key.toString()] = im
}
logMsg("manifest successes: " + byKey.size())

def lines = summaryFile.readLines().findAll { !it.trim().isEmpty() }
def hdr = csvSplit(lines[0])
def col = { String c -> def i = hdr.indexOf(c); if (i < 0) failRun("run_summary.csv lacks '" + c + "'"); return i }
int iKey = col("output_key"), iReg = col("region"), iArea = col("region_area_um2")
int iPanel = hdr.indexOf("panel")

def wanted = []   // [output_key, region, reported_area]
lines[1..-1].each { ln ->
  def p = csvSplit(ln)
  if (!PANEL_FILTER.isEmpty() && iPanel >= 0 && p[iPanel] != PANEL_FILTER) return
  wanted << [key: p[iKey], region: p[iReg], reported: Double.parseDouble(p[iArea])]
}
// smoke-test escape hatch. Non-zero truncates the work list; the reconciliation
// report then covers only that subset and must not be treated as a full run.
int MAX_FIELDS = Integer.parseInt(envOr("IFQ_MAX_FIELDS", "0"))
if (MAX_FIELDS > 0) {
  def keys = wanted.collect { it.key }.unique().take(MAX_FIELDS) as Set
  wanted = wanted.findAll { keys.contains(it.key) }
  logMsg("IFQ_MAX_FIELDS=" + MAX_FIELDS + " -- PARTIAL RUN, not a full export")
}
logMsg("regions to reconstruct: " + wanted.size() +
       (PANEL_FILTER.isEmpty() ? "" : "  (panel=" + PANEL_FILTER + ")"))
if (wanted.isEmpty()) failRun("nothing selected")

new File(MASK_DIR).mkdirs()
def reconRows = []
double worstRel = 0.0d
String worstKey = ""
int nDone = 0

wanted.groupBy { it.key }.each { outKey, regs ->
  def im = byKey[outKey]
  if (im == null) failRun(outKey + ": no successful manifest entry; cannot resolve the source image")
  if (im.tissue_source != "auto_dapi")
    failRun(outKey + ": tissue_source is '" + im.tissue_source + "'. Only auto_dapi regions can be " +
            "reconstructed. For a manual RoiSet use IFQ_ENDPOINT_REGION_MODE=roiset.")
  def src = new File(SOURCE_DIR, im.relative_path.toString().replace("\\", File.separator))
  if (!src.isFile()) failRun(outKey + ": source image not found at " + src.getAbsolutePath())

  // DAPI channel index comes from the recorded channel signature, e.g.
  // "C1-DAPI_C2-KRT5-488_C3-AGER-555_C4-T1alpha-647" -> 1
  def sig = im.channel_signature.toString()
  def dapiTok = sig.split("_").find { it ==~ /C\d+-DAPI/ }
  if (dapiTok == null) failRun(outKey + ": no C<n>-DAPI token in channel_signature '" + sig + "'")
  int dapiIdx = Integer.parseInt(dapiTok.substring(1, dapiTok.indexOf('-')))

  ImagePlus raw = null
  def split = null
  try {
    raw = bfOpen(src.getAbsolutePath())
    Calibration cal = raw.getCalibration()
    split = ChannelSplitter.split(raw)
    if (split.length < dapiIdx) failRun(outKey + ": only " + split.length + " channels, need " + dapiIdx)
    def ch = split[dapiIdx - 1]
    ch.setCalibration(cal)
    if (ch.getNSlices() != 1)
      failRun(outKey + ": nSlices=" + ch.getNSlices() + ". This exporter only handles single-plane " +
              "fields, where the engine's projectChannelRange is an identity duplicate. A Z-stack " +
              "would need the engine's marker-specific slab selection reproduced exactly.")
    ch.setSlice(1)
    def dapi = new ImagePlus(ch.getTitle(), ch.getProcessor().duplicate())
    dapi.setCalibration(cal)

    def mask = buildThresholdMask(dapi, TISSUE_BLUR_SIGMA_PX, TISSUE_THRESH_METHOD)
    IJ.run(mask, "Options...", "iterations=2 count=1 do=Close")
    def rois = particlesToRois(mask, TISSUE_MIN_AREA_UM2, false)
    mask.close()
    if (rois.isEmpty()) failRun(outKey + ": DAPI tissue detection found no region")
    ShapeRoi merged = null
    rois.each { r -> def s = new ShapeRoi(r); merged = (merged == null) ? s : merged.or(s) }

    double reconArea = measureRoiArea(dapi, merged)

    // write the binary mask exactly as evaluate_endpoints will read it
    def outDir = new File(MASK_DIR, outKey); outDir.mkdirs()
    def bp = new ByteProcessor(dapi.getWidth(), dapi.getHeight())
    bp.setValue(255); bp.fill(merged)
    def maskImp = new ImagePlus("tissue_region", bp)
    maskImp.setCalibration(cal)

    regs.each { r ->
      // auto_dapi produces exactly one region per field, named "tissue"
      def path = new File(outDir, sig + "__" + r.region + "__tissue_region_mask.tif")
      IJ.saveAs(maskImp, "Tiff", path.getAbsolutePath())
      double rel = r.reported > 0 ? Math.abs(reconArea - r.reported) / r.reported : Double.NaN
      if (Double.isFinite(rel) && rel > worstRel) { worstRel = rel; worstKey = outKey + "/" + r.region }
      reconRows << [key: outKey, region: r.region, reported: r.reported,
                    recon: reconArea, rel: rel, file: path.getName()]
      nDone++
      logMsg(String.format("%-34s %-8s reported=%12.3f recon=%12.3f rel=%.3e",
                           outKey, r.region, r.reported, reconArea, rel))
    }
    maskImp.close()
    dapi.close()
  } finally {
    if (split != null) split.each { if (it != null) { it.changes = false; it.close() } }
    if (raw != null) { raw.changes = false; raw.close() }
  }
}

// ---- reconciliation report + fail-closed gate -------------------------------
def rep = new File(MASK_DIR, "tissue_region_reconciliation.csv")
def sb = new StringBuilder("output_key,region,region_area_um2_reported,region_area_um2_reconstructed,relative_difference,mask_file\n")
reconRows.each { r ->
  sb.append('"').append(r.key).append('","').append(r.region).append('",')
    .append(r.reported).append(',').append(r.recon).append(',').append(r.rel)
    .append(',"').append(r.file).append('"\n')
}
rep.setText(sb.toString(), "UTF-8")

logMsg("exported " + nDone + " tissue region mask(s) to " + MASK_DIR)
logMsg("worst relative area difference vs run_summary.region_area_um2: " +
       String.format("%.6e", worstRel) + "  at " + worstKey)
logMsg("wrote " + rep.getAbsolutePath())
if (worstRel > TOL) {
  failRun("reconstruction does not reproduce the engine's region_area_um2 within " + TOL +
          " (worst " + worstRel + " at " + worstKey + "). The exported masks are NOT the regions " +
          "the engine measured; refusing to bless them.")
}
logMsg("OK: reconstruction reproduces region_area_um2 within " + TOL)

// ImageJ starts non-daemon threads even with --headless; exit explicitly so the
// command line does not hang after the last synchronous write.
if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(0)
