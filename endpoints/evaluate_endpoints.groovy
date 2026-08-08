// ============================================================================
// evaluate_endpoints.groovy -- declarative RELATIONAL endpoints, evaluated by
// mask algebra on the masks IF_Quant_Pipeline.groovy already saves.
// ============================================================================
// WHY THIS EXISTS
//   The measurement engine is MARKER-WISE. positiveAreaInRoi() takes exactly
//   one marker's mask, and every recomputed fraction divides by a single
//   region_area_um2. So it cannot express a numerator that is a RELATION
//   between two markers -- and the reference endpoint is exactly that:
//   "KRT5+PDPN- area", not bare KRT5+ area (Lin et al. 2024, Fig 2A-B).
//
//   The engine does, however, save a calibrated binary mask per area marker,
//   per tile, computed on the whole field BEFORE region clipping. That is
//   enough to evaluate the relation afterwards, with no change to the frozen
//   engine. Region clipping becomes this script's job, which is correct: both
//   regions then share one identical threshold.
//
// WHAT IT IS NOT
//   Not a second measurement engine. It performs boolean algebra on masks that
//   were already produced and thresholded by the validated engine. It
//   introduces no new segmentation, no new thresholding, and no new
//   morphology.
//
// REGION MODE -- IFQ_ENDPOINT_REGION_MODE
//   The numerator MUST be clipped to the same region the engine measured in, or
//   the reported area silently includes tile halo / non-tissue background. An
//   earlier bug did exactly that, so there is no implicit fallback: the region
//   source is chosen explicitly and every mode fails closed.
//
//     roiset       (DEFAULT, unchanged whole-slide behaviour)
//                  region ROIs come from IFQ_TILES_DIR/<stem>_RoiSet.zip.
//                  A missing RoiSet is FATAL.
//
//     tissue_mask  whole-field runs (confocal fields, no tiling). The region is
//                  a binary mask TIFF under
//                    IFQ_TISSUE_MASK_DIR/<output_key>/*__<region>__tissue_region_mask.tif
//                  produced by endpoints/export_tissue_region_masks.groovy,
//                  which reconciles it against run_summary.region_area_um2.
//                  A missing or ambiguous mask is FATAL.
//                  NOTE: the engine does NOT export the tissue region it used --
//                  verified against the 260808 confocal output, which contains
//                  whole-field marker masks and per-region NUCLEI masks and
//                  nothing whose foreground is the region. That is why the mask
//                  has to be re-derived and proven, not just read.
//
//     whole_field  NO CLIPPING AT ALL. Only correct when the analysed region
//                  genuinely is the entire field (IFQ_TISSUE_MODE=whole_field in
//                  the engine run). Must be asked for by name; it is never a
//                  fallback. The script verifies the claim against
//                  run_summary.region_area_um2 and refuses if it does not hold.
//
// RUN (Fiji; the Fiji launcher .exe is broken on win-arm64, so call the JVM):
//   IFQ_ENDPOINT_SPEC        = config/endpoints/<id>.json
//   IFQ_ANALYSIS_DIR         = <slide>/analysis        (engine output)
//   IFQ_ENDPOINT_REGION_MODE = roiset | tissue_mask | whole_field   (default roiset)
//   IFQ_TILES_DIR            = <slide>/tiles           (roiset mode only)
//   IFQ_TISSUE_MASK_DIR      = <run>/tissue_masks      (tissue_mask mode only)
//   IFQ_ENDPOINT_OUT         = <slide>/endpoint_areas.csv
//   IFQ_ENDPOINT_AREA_TOL    = region-area reconciliation tolerance (default 1e-6)
//   IFQ_ENDPOINT_AREA_CHECK  = warn | fail. Default fail in tissue_mask and
//                              whole_field, warn in roiset (that mode predates
//                              the check and must not start failing on it).
//
// OUTPUT COLUMNS
//   output_key, image, region, <spec.output.area_column>,
//   qc_bare_<positive mask>_area_um2_in_region   -- the ceiling the AND cannot exceed
//   qc_region_area_um2_from_mask                 -- denominator this script actually used
//   qc_region_area_um2_reported                  -- run_summary.region_area_um2
//   qc_region_area_rel_diff, region_mode
//   The qc_ prefix keeps these outside every aggregate_to_mouse name whitelist,
//   so a naive join cannot turn a QC field into a summed endpoint.
// ============================================================================

import ij.IJ
import ij.ImagePlus
import ij.gui.Roi
import ij.io.RoiDecoder
import ij.process.ImageProcessor
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

def LOG = "[IFQ_ENDPOINT]"
def logMsg = { String m -> println LOG + " " + m }
def failRun = { String m ->
  System.err.println("FATAL: " + m); println LOG + " FATAL: " + m; System.exit(1)
}
def envOr = { String n, String d ->
  def v = System.getenv(n); return (v == null || v.trim().isEmpty()) ? d : v.trim()
}

def SPEC_PATH    = envOr("IFQ_ENDPOINT_SPEC", "")
def ANALYSIS_DIR = envOr("IFQ_ANALYSIS_DIR", "")
def TILES_DIR    = envOr("IFQ_TILES_DIR", "")
def MASK_DIR     = envOr("IFQ_TISSUE_MASK_DIR", "")
def OUT_PATH     = envOr("IFQ_ENDPOINT_OUT", "")
def REGION_MODE  = envOr("IFQ_ENDPOINT_REGION_MODE", "roiset").toLowerCase()
double AREA_TOL  = Double.parseDouble(envOr("IFQ_ENDPOINT_AREA_TOL", "1e-6"))
// The region-area reconciliation is always computed and always logged. It is
// only ENFORCED in the two new modes: roiset is pre-existing validated whole-
// slide behaviour and must not start failing because of a check added later.
def AREA_CHECK   = envOr("IFQ_ENDPOINT_AREA_CHECK",
                         REGION_MODE == "roiset" ? "warn" : "fail").toLowerCase()
if (!(AREA_CHECK in ["warn", "fail"]))
  failRun("IFQ_ENDPOINT_AREA_CHECK must be warn or fail; found '" + AREA_CHECK + "'")
if (SPEC_PATH.isEmpty())    failRun("IFQ_ENDPOINT_SPEC is required")
if (ANALYSIS_DIR.isEmpty()) failRun("IFQ_ANALYSIS_DIR is required")
if (OUT_PATH.isEmpty())     failRun("IFQ_ENDPOINT_OUT is required")
if (!(REGION_MODE in ["roiset", "tissue_mask", "whole_field"]))
  failRun("IFQ_ENDPOINT_REGION_MODE must be roiset, tissue_mask or whole_field; found '" +
          REGION_MODE + "'")
if (REGION_MODE == "roiset" && TILES_DIR.isEmpty())
  failRun("IFQ_TILES_DIR is required in region mode 'roiset' (the per-tile RoiSet.zip lives there)")
if (REGION_MODE == "tissue_mask" && MASK_DIR.isEmpty())
  failRun("IFQ_TISSUE_MASK_DIR is required in region mode 'tissue_mask'. Produce it first with " +
          "endpoints/export_tissue_region_masks.groovy, which validates the masks against " +
          "run_summary.region_area_um2.")

def spec = new groovy.json.JsonSlurper().parse(new File(SPEC_PATH))
def endpointId = spec.endpoint_id
def areaCol    = spec.output.area_column
def terms      = spec.numerator.terms
def op         = spec.numerator.op
def specPanel  = spec.panel == null ? "" : spec.panel.toString()
if (op != "AND") failRun("Only op=AND is implemented; found '" + op + "'")
if (!areaCol.endsWith("_pod_area_um2") && !areaCol.endsWith("_positive_area_um2"))
  failRun("output.area_column '" + areaCol + "' does not end in _pod_area_um2 or " +
          "_positive_area_um2, so aggregate_to_mouse would SILENTLY DROP it. " +
          "See docs/ECTOPIC_POD_ENDPOINT.md section 9.")

logMsg("endpoint : " + endpointId + "   ->  " + areaCol)
logMsg("numerator: " + terms.collect { (it.negate ? "NOT " : "") + it.mask }.join(" AND "))
logMsg("region   : mode=" + REGION_MODE + "  area_check=" + AREA_CHECK + "  tol=" + AREA_TOL)
logMsg("status   : " + spec.validation_status)
if (REGION_MODE == "whole_field") {
  logMsg("*** WARNING: region mode whole_field applies NO CLIPPING. Every reported area is the")
  logMsg("*** area over the complete field. This is only valid if the engine itself ran with")
  logMsg("*** IFQ_TISSUE_MODE=whole_field. The check against region_area_um2 below enforces that.")
}

// ---- RFC4180-ish CSV reader (the series name contains commas) --------------
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

def summaryFile = new File(ANALYSIS_DIR, "run_summary.csv")
if (!summaryFile.isFile()) failRun("No run_summary.csv in " + ANALYSIS_DIR)
def lines = summaryFile.readLines().findAll { !it.trim().isEmpty() }
if (lines.size() < 2) failRun("run_summary.csv has no data rows")
def hdr = csvSplit(lines[0])
def idx = { String c -> hdr.indexOf(c) }
["image", "region", "output_key", "region_area_um2"].each {
  if (idx(it) < 0) failRun("run_summary.csv lacks column '" + it + "'")
}
int iPanel = idx("panel")

// ---- ImageJ RoiSet reader (mirrors the engine's readRoiFile) ---------------
def readRoiZip = { File f ->
  def out = [:]
  def zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))
  try {
    ZipEntry e
    while ((e = zis.getNextEntry()) != null) {
      def bos = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n
      while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n)
      byte[] bytes = bos.toByteArray()
      if (bytes.length == 0) continue
      def r = new RoiDecoder(bytes, e.getName()).getRoi()
      if (r != null) out[e.getName().replaceFirst(/\.roi$/, "")] = r
    }
  } finally { zis.close() }
  return out
}

// ---- walk the engine's per-image output folders ----------------------------
// Group by OUTPUT_KEY, not by image. The engine writes one folder per
// output_key, and the same source stem can legitimately appear under several
// output_keys (e.g. the same field name in two acquisition cycles). Grouping by
// image merged those rows into one folder and lost regions.
def rows = []
def byKey = [:]
int nPanelSkipped = 0
lines[1..-1].each { ln ->
  def p = csvSplit(ln)
  if (!specPanel.isEmpty() && iPanel >= 0 && p[iPanel] != specPanel) { nPanelSkipped++; return }
  def key = p[idx("output_key")]
  byKey.computeIfAbsent(key, { [] }) << [image: p[idx("image")], region: p[idx("region")],
                                         output_key: key,
                                         reported_region_area: Double.parseDouble(p[idx("region_area_um2")])]
}
logMsg("output_keys selected: " + byKey.size() +
       (nPanelSkipped > 0 ? "   (" + nPanelSkipped + " row(s) skipped: panel != " + specPanel + ")" : ""))
if (byKey.isEmpty()) failRun("no rows selected from run_summary.csv")

int nEval = 0, nMissing = 0
double worstAreaRel = 0.0d
String worstAreaAt = ""

// The leading non-negated term. Its area over the same region is the ceiling
// the AND can never exceed, and it is written out as a QC column.
int baseIdx = -1
for (int i = 0; i < terms.size(); i++) { if (!terms[i].negate) { baseIdx = i; break } }
if (baseIdx < 0) failRun("numerator has no positive term; an all-negated AND has no meaningful ceiling")
def baseMaskName = terms[baseIdx].mask.toString()

byKey.each { outKey, regs ->
  def img = regs[0].image
  def imgDir = new File(ANALYSIS_DIR, outKey)
  if (!imgDir.isDirectory()) { logMsg("  no output folder for " + img + " (" + outKey + ")"); nMissing++; return }

  // resolve each mask named in the spec. The whole-field AREA masks carry no
  // region token; the per-region nuclei masks do, and must not be picked up.
  def maskImps = [:]
  boolean ok = true
  terms.each { t ->
    def hits = imgDir.listFiles().findAll { it.name.endsWith("__" + t.mask + ".tif") }
    if (hits.size() != 1) {
      logMsg("  " + outKey + ": expected exactly 1 '" + t.mask + "' mask, found " + hits.size())
      ok = false; return
    }
    maskImps[t.mask] = IJ.openImage(hits[0].getAbsolutePath())
  }
  if (!ok) { nMissing++; maskImps.values().each { it?.close() }; return }

  def first = maskImps[terms[0].mask]
  int w = first.getWidth(), h = first.getHeight()
  def cal = first.getCalibration()
  double pxA = cal.pixelWidth * cal.pixelHeight
  if (!(pxA > 0)) failRun(outKey + ": mask has no pixel calibration; refusing to report an area in pixels")

  // sanity: all masks must agree in size
  maskImps.each { k, v ->
    if (v.getWidth() != w || v.getHeight() != h)
      failRun(outKey + ": mask '" + k + "' is " + v.getWidth() + "x" + v.getHeight() + ", expected " + w + "x" + h)
  }

  // boolean AND over the (optionally negated) masks, plus -- for the
  // containment invariant -- the leading POSITIVE term on its own.
  boolean[] num = new boolean[w*h]
  java.util.Arrays.fill(num, true)
  boolean[] base = new boolean[w*h]
  for (int ti = 0; ti < terms.size(); ti++) {
    def t = terms[ti]
    def ip = maskImps[t.mask].getProcessor()
    boolean isBase = (ti == baseIdx)
    for (int i = 0; i < num.length; i++) {
      boolean on = (ip.get(i) > 127)
      if (isBase) base[i] = on
      if (!num[i]) continue
      if (t.negate) on = !on
      num[i] = on
    }
  }
  maskImps.values().each { it.close() }

  // ---- region resolution -----------------------------------------------
  // The engine strips ONLY the final extension, so a tile written as
  // "<tile>.ome.tif" has companion "<tile>.ome_RoiSet.zip" and appears in
  // run_summary.csv as image="<tile>.ome". The image value is therefore
  // already the stem -- do NOT strip ".ome" from it.
  def rois = [:]
  if (REGION_MODE == "roiset") {
    def roiFile = new File(TILES_DIR, img + "_RoiSet.zip")
    rois = roiFile.isFile() ? readRoiZip(roiFile) : [:]
    if (rois.isEmpty())
      failRun(img + ": no RoiSet at " + roiFile.getAbsolutePath() + ". Falling back to the " +
              "whole field would report UNCLIPPED areas that silently double-count the " +
              "tile halo and ignore the damaged/intact partition. Refusing. If this run has " +
              "no RoiSet because it is a whole-field acquisition, choose the region source " +
              "explicitly: IFQ_ENDPOINT_REGION_MODE=tissue_mask (preferred) or whole_field.")
  }

  regs.each { r ->
    byte[] regionPixels = null      // null means "no clipping"
    if (REGION_MODE == "roiset") {
      def roi = rois[r.region]
      if (roi == null) {
        logMsg("  WARNING " + outKey + ": RoiSet has no ROI named '" + r.region +
               "'; this (image, region) is measured UNCLIPPED.")
      } else {
        def m = new ij.process.ByteProcessor(w, h)
        m.setValue(255); m.fill(roi)
        regionPixels = (byte[]) m.getPixels()
      }
    } else if (REGION_MODE == "tissue_mask") {
      def keyDir = new File(MASK_DIR, outKey)
      def hits = keyDir.isDirectory() ?
        keyDir.listFiles().findAll { it.name.endsWith("__" + r.region + "__tissue_region_mask.tif") } : []
      if (hits.size() != 1)
        failRun(outKey + "/" + r.region + ": expected exactly 1 tissue_region_mask in " +
                keyDir.getAbsolutePath() + ", found " + hits.size() + ". Run " +
                "endpoints/export_tissue_region_masks.groovy first. There is no fallback: an " +
                "unclipped whole-field area is not a conservative approximation of a tissue area.")
      def rm = IJ.openImage(hits[0].getAbsolutePath())
      if (rm.getWidth() != w || rm.getHeight() != h)
        failRun(outKey + "/" + r.region + ": tissue mask is " + rm.getWidth() + "x" + rm.getHeight() +
                ", marker masks are " + w + "x" + h)
      def rp = rm.getProcessor()
      def m = new ij.process.ByteProcessor(w, h)
      for (int i = 0; i < w*h; i++) if (rp.get(i) > 127) m.set(i, 255)
      regionPixels = (byte[]) m.getPixels()
      rm.close()
    }
    // whole_field: regionPixels stays null, i.e. no clipping.

    long cnt = 0, baseCnt = 0, regCnt = 0
    for (int i = 0; i < num.length; i++) {
      boolean inRegion = (regionPixels == null) || ((regionPixels[i] & 0xFF) > 127)
      if (!inRegion) continue
      regCnt++
      if (base[i]) baseCnt++
      if (num[i]) cnt++
    }
    double regionAreaFromMask = regCnt * pxA
    double rel = r.reported_region_area > 0 ?
      Math.abs(regionAreaFromMask - r.reported_region_area) / r.reported_region_area : Double.NaN
    if (Double.isFinite(rel) && rel > worstAreaRel) { worstAreaRel = rel; worstAreaAt = outKey + "/" + r.region }

    // Mathematical invariant: an AND that includes the positive term can never
    // exceed that term alone over the same pixel set. A violation means the
    // masks or the region are inconsistent, not that the biology is surprising.
    if (cnt > baseCnt)
      failRun(outKey + "/" + r.region + ": numerator area (" + (cnt*pxA) + ") exceeds the bare '" +
              baseMaskName + "' area (" + (baseCnt*pxA) + ") in the same region. " +
              "Boolean algebra cannot do this; the inputs are inconsistent.")

    rows << [output_key: outKey, image: img, region: r.region,
             area: cnt * pxA, baseArea: baseCnt * pxA,
             regionAreaMask: regionAreaFromMask, regionAreaReported: r.reported_region_area,
             regionAreaRel: rel]
    nEval++
  }
}

if (rows.isEmpty()) failRun("No endpoint areas computed -- refusing to write an empty result")

// ---- region-area reconciliation gate ---------------------------------------
logMsg("worst region-area discrepancy vs run_summary.region_area_um2: " +
       String.format("%.6e", worstAreaRel) + (worstAreaAt.isEmpty() ? "" : "  at " + worstAreaAt))
if (worstAreaRel > AREA_TOL) {
  def hint = REGION_MODE == "whole_field" ?
    "In whole_field mode this means the engine did NOT analyse the whole field -- it clipped to a " +
    "smaller region -- so every area reported here would be measured over the wrong denominator." :
    "The region used here is not the region the engine measured in."
  def msg = "region area does not reconcile within IFQ_ENDPOINT_AREA_TOL=" + AREA_TOL +
            " (worst " + worstAreaRel + " at " + worstAreaAt + "). " + hint
  if (AREA_CHECK == "fail") failRun(msg)
  logMsg("*** WARNING (IFQ_ENDPOINT_AREA_CHECK=warn): " + msg)
}

def baseColName = baseMaskName.replaceAll(/[^A-Za-z0-9]+/, "_")
def out = new File(OUT_PATH)
out.getParentFile()?.mkdirs()
def sb = new StringBuilder()
sb.append("output_key,image,region,").append(areaCol)
  .append(",qc_bare_").append(baseColName).append("_area_um2_in_region")
  .append(",qc_region_area_um2_from_mask,qc_region_area_um2_reported,qc_region_area_rel_diff")
  .append(",region_mode\n")
rows.each { r ->
  sb.append('"').append(r.output_key.replace('"','""')).append('",')
  sb.append('"').append(r.image.replace('"','""')).append('",')
  sb.append('"').append(r.region.replace('"','""')).append('",')
  sb.append(r.area).append(',')
  sb.append(r.baseArea).append(',')
  sb.append(r.regionAreaMask).append(',')
  sb.append(r.regionAreaReported).append(',')
  sb.append(r.regionAreaRel).append(',')
  sb.append(REGION_MODE).append("\n")
}
out.setText(sb.toString(), "UTF-8")

logMsg("evaluated " + nEval + " (output_key, region) pairs; " + nMissing + " image(s) skipped")
logMsg("wrote " + out.getAbsolutePath())
logMsg("NOTE: " + spec.validation_status + " -- " +
       "t1a_threshold is " + spec.parameters.t1a_threshold.status +
       ", krt5_threshold is " + spec.parameters.krt5_threshold.status)

// ImageJ starts non-daemon threads even with --headless; exit explicitly so the
// command line does not hang after the last synchronous write.
if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(0)
