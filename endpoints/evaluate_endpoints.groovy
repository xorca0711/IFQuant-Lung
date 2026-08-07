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
// RUN (Fiji; the Fiji launcher .exe is broken on win-arm64, so call the JVM):
//   IFQ_ENDPOINT_SPEC = config/endpoints/<id>.json
//   IFQ_ANALYSIS_DIR  = <slide>/analysis        (engine output)
//   IFQ_TILES_DIR     = <slide>/tiles           (for the per-tile RoiSet.zip)
//   IFQ_ENDPOINT_OUT  = <slide>/endpoint_areas.csv
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
def OUT_PATH     = envOr("IFQ_ENDPOINT_OUT", "")
if (SPEC_PATH.isEmpty())    failRun("IFQ_ENDPOINT_SPEC is required")
if (ANALYSIS_DIR.isEmpty()) failRun("IFQ_ANALYSIS_DIR is required")
if (TILES_DIR.isEmpty())    failRun("IFQ_TILES_DIR is required")
if (OUT_PATH.isEmpty())     failRun("IFQ_ENDPOINT_OUT is required")

def spec = new groovy.json.JsonSlurper().parse(new File(SPEC_PATH))
def endpointId = spec.endpoint_id
def areaCol    = spec.output.area_column
def terms      = spec.numerator.terms
def op         = spec.numerator.op
if (op != "AND") failRun("Only op=AND is implemented; found '" + op + "'")
if (!areaCol.endsWith("_pod_area_um2") && !areaCol.endsWith("_positive_area_um2"))
  failRun("output.area_column '" + areaCol + "' does not end in _pod_area_um2 or " +
          "_positive_area_um2, so aggregate_to_mouse would SILENTLY DROP it. " +
          "See docs/ECTOPIC_POD_ENDPOINT.md section 9.")

logMsg("endpoint : " + endpointId + "   ->  " + areaCol)
logMsg("numerator: " + terms.collect { (it.negate ? "NOT " : "") + it.mask }.join(" AND "))
logMsg("status   : " + spec.validation_status)

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
["image", "region", "output_key"].each { if (idx(it) < 0) failRun("run_summary.csv lacks column '" + it + "'") }

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
def rows = []
def byImage = [:]
lines[1..-1].each { ln ->
  def p = csvSplit(ln)
  def img = p[idx("image")], reg = p[idx("region")], ok = p[idx("output_key")]
  byImage.computeIfAbsent(img, { [] }) << [region: reg, output_key: ok, cells: p]
}
logMsg("images in run_summary: " + byImage.size())

int nEval = 0, nMissing = 0
byImage.each { img, regs ->
  // engine writes each image into OUTPUT_DIR/<output_key>/
  def outKey = regs[0].output_key
  def imgDir = new File(ANALYSIS_DIR, outKey)
  if (!imgDir.isDirectory()) { logMsg("  no output folder for " + img + " (" + outKey + ")"); nMissing++; return }

  // resolve each mask named in the spec. The whole-field AREA masks carry no
  // region token; the per-region nuclei masks do, and must not be picked up.
  def maskImps = [:]
  boolean ok = true
  terms.each { t ->
    def hits = imgDir.listFiles().findAll { it.name.endsWith("__" + t.mask + ".tif") }
    if (hits.size() != 1) {
      logMsg("  " + img + ": expected exactly 1 '" + t.mask + "' mask, found " + hits.size())
      ok = false; return
    }
    maskImps[t.mask] = IJ.openImage(hits[0].getAbsolutePath())
  }
  if (!ok) { nMissing++; maskImps.values().each { it?.close() }; return }

  def first = maskImps[terms[0].mask]
  int w = first.getWidth(), h = first.getHeight()
  def cal = first.getCalibration()
  double pxA = cal.pixelWidth * cal.pixelHeight
  if (!(pxA > 0)) failRun(img + ": mask has no pixel calibration; refusing to report an area in pixels")

  // sanity: all masks must agree in size
  maskImps.each { k, v ->
    if (v.getWidth() != w || v.getHeight() != h)
      failRun(img + ": mask '" + k + "' is " + v.getWidth() + "x" + v.getHeight() + ", expected " + w + "x" + h)
  }

  // boolean AND over the (optionally negated) masks
  boolean[] num = new boolean[w*h]
  java.util.Arrays.fill(num, true)
  terms.each { t ->
    def ip = maskImps[t.mask].getProcessor()
    for (int i = 0; i < num.length; i++) {
      if (!num[i]) continue
      boolean on = (ip.get(i) > 127)
      if (t.negate) on = !on
      num[i] = on
    }
  }
  maskImps.values().each { it.close() }

  // clip to each region ROI and report the calibrated area
  // The engine strips ONLY the final extension, so a tile written as
  // "<tile>.ome.tif" has companion "<tile>.ome_RoiSet.zip" and appears in
  // run_summary.csv as image="<tile>.ome". The image value is therefore
  // already the stem -- do NOT strip ".ome" from it.
  def roiFile = new File(TILES_DIR, img + "_RoiSet.zip")
  def rois = roiFile.isFile() ? readRoiZip(roiFile) : [:]
  if (rois.isEmpty())
    failRun(img + ": no RoiSet at " + roiFile.getAbsolutePath() + ". Falling back to the " +
            "whole field would report UNCLIPPED areas that silently double-count the " +
            "tile halo and ignore the damaged/intact partition. Refusing.")

  regs.each { r ->
    long cnt = 0
    def roi = rois[r.region]
    if (roi == null) {
      for (int i = 0; i < num.length; i++) if (num[i]) cnt++
    } else {
      def m = new ij.process.ByteProcessor(w, h)
      m.setValue(255); m.fill(roi)
      def mp = (byte[]) m.getPixels()
      for (int i = 0; i < num.length; i++) if (num[i] && (mp[i] & 0xFF) > 127) cnt++
    }
    rows << [output_key: r.output_key, image: img, region: r.region,
             area: cnt * pxA]
    nEval++
  }
}

if (rows.isEmpty()) failRun("No endpoint areas computed -- refusing to write an empty result")

def out = new File(OUT_PATH)
out.getParentFile()?.mkdirs()
def sb = new StringBuilder()
sb.append("output_key,image,region,").append(areaCol).append("\n")
rows.each { r ->
  sb.append('"').append(r.output_key.replace('"','""')).append('",')
  sb.append('"').append(r.image.replace('"','""')).append('",')
  sb.append('"').append(r.region.replace('"','""')).append('",')
  sb.append(r.area).append("\n")
}
out.setText(sb.toString(), "UTF-8")

logMsg("evaluated " + nEval + " (image, region) pairs; " + nMissing + " image(s) skipped")
logMsg("wrote " + out.getAbsolutePath())
logMsg("NOTE: " + spec.validation_status + " -- " +
       "t1a_threshold is " + spec.parameters.t1a_threshold.status +
       ", krt5_threshold is " + spec.parameters.krt5_threshold.status)
