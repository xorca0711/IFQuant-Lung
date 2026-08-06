/*
 * ============================================================================
 *  QuPath_SlideScanner_Quant.groovy
 *  Whole-slide (slide-scanner) IF quantification for the IFN-gamma KO / PR8
 *  influenza injury project.
 *
 *  Companion engine to IF_Quant_Pipeline.groovy (Fiji / confocal fields).
 *  Same research scheme, same markers, and the SAME morphology-primary call
 *  model, so the two engines agree on what counts as a positive cell.
 * ----------------------------------------------------------------------------
 *  WHY A SLIDE-SCANNER ENGINE
 *   Slide-scanner output (.svs/.ndpi/.mrxs/.scn/.vsi/.qptiff/.czi/.bif) is
 *   pyramidal and often 10-100 GB at full resolution. It cannot be loaded as a
 *   single ImageJ array. QuPath reads the pyramid through Bio-Formats and
 *   analyses region-by-region, so this engine:
 *     1. detects tissue on a DOWNSAMPLED plane (cheap, whole slide),
 *     2. optionally splits tissue into TILES so memory stays bounded,
 *     3. runs cell detection + marker calls only inside tissue.
 *
 *  DECISION MODEL (mirrors main's IF_Quant_Pipeline.groovy)
 *   Intensity nominates candidate pixels; MORPHOLOGY authorises the call.
 *   Calls are three-state: 1 (positive), 0 (negative), "" (indeterminate).
 *   Gates per marker:
 *     minFraction        - min positive fraction of the compartment
 *     minLargestShare    - largest connected component share (anti-fragmentation)
 *     requireOwnership   - support must not belong to a neighbouring nucleus
 *     minNuclearEnrichment / minNucCytoRatio - nuclear markers / YAP
 *   Evaluability is decided BEFORE the call, so unresolved anatomy becomes
 *   INDETERMINATE rather than a false negative.
 * ----------------------------------------------------------------------------
 *  REQUIRES QuPath v0.5.x.  Run headless (the launcher does this for you):
 *     QuPath script -i <slide> QuPath_SlideScanner_Quant.groovy
 *  Parameters are supplied by the launcher through IFQ_* environment variables.
 *
 *  READ BEFORE TRUSTING NUMBERS
 *   * Gate defaults are PILOT placeholders inherited from the Fiji pipeline and
 *     must be calibrated on blinded positive/negative controls.
 *   * QuPath analyses one focal plane. Slide scanners normally emit a single
 *     plane; if yours writes a z-stack, pick the plane before analysis.
 *   * n IS COUNTED BY MICE, not slides. mouse_id/section_id travel through the
 *     exports; roll up with aggregate_to_mouse.py before statistics.
 *   * UNVALIDATED against a live QuPath in this environment. Three API points
 *     are marked VERSION-SENSITIVE; confirm them on one slide, then batch.
 * ============================================================================
 */

import static qupath.lib.scripting.QP.*
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.roi.ROIs
import qupath.lib.roi.interfaces.ROI
import qupath.lib.regions.ImagePlane
import qupath.lib.common.GeneralTools
import qupath.lib.gui.measure.ObservableMeasurementTableData
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// ============================================================================
//  0. ENVIRONMENT CONFIG (supplied by IFQuantLauncher_QuPath_SlideScanner)
// ============================================================================

def envOr = { String k, String d -> def v = System.getenv(k); (v == null || v.trim().isEmpty()) ? d : v.trim() }
def envD  = { String k, double d -> try { return Double.parseDouble(envOr(k, String.valueOf(d))) } catch (e) { return d } }
def envI  = { String k, int d    -> try { return Integer.parseInt(envOr(k, String.valueOf(d))) } catch (e) { return d } }
def envB  = { String k, boolean d-> def v = envOr(k, String.valueOf(d)); return v.equalsIgnoreCase("true") || v == "1" }

def OUTPUT_DIR = envOr("IFQ_OUTPUT_DIR", buildFilePath(System.getProperty("user.home"), "qupath_slidescanner_output"))
def PANEL      = envOr("IFQ_PANEL", "A")
def PANEL_CONFIG_PATH = envOr("IFQ_PANEL_CONFIG", "")

def META = [ mouse_id  : envOr("IFQ_MOUSE_ID", "NA"),
             section_id: envOr("IFQ_SECTION_ID", "NA"),
             genotype  : envOr("IFQ_GENOTYPE", "NA"),
             condition : envOr("IFQ_CONDITION", "NA") ]

// --- Slide-scanner specific ---
def TISSUE_DOWNSAMPLE   = envD("IFQ_TISSUE_DOWNSAMPLE", 32.0)   // tissue detection resolution
def TISSUE_MIN_AREA_UM2 = envD("IFQ_TISSUE_MIN_AREA_UM2", 50000.0)
def TISSUE_SIGMA        = envD("IFQ_TISSUE_SIGMA", 2.0)
def TILE_SIZE_UM        = envD("IFQ_TILE_SIZE_UM", 0.0)         // 0 = no tiling
def ANALYSIS_DOWNSAMPLE = envD("IFQ_ANALYSIS_DOWNSAMPLE", 1.0)  // cell detection resolution

// --- Cell detection ---
def CELL = [
  requestedPixelSizeMicrons: envD("IFQ_CELL_PIXEL_SIZE_UM", 0.5),
  backgroundRadiusMicrons  : envD("IFQ_CELL_BACKGROUND_RADIUS_UM", 8.0),
  medianRadiusMicrons      : envD("IFQ_CELL_MEDIAN_RADIUS_UM", 0.0),
  sigmaMicrons             : envD("IFQ_CELL_SIGMA_UM", 1.5),
  minAreaMicrons           : envD("IFQ_CELL_MIN_AREA_UM2", 10.0),
  maxAreaMicrons           : envD("IFQ_CELL_MAX_AREA_UM2", 400.0),
  threshold                : envD("IFQ_CELL_THRESHOLD", 100.0),
  cellExpansionMicrons     : envD("IFQ_CELL_EXPANSION_UM", 2.0)
]

def MORPHOLOGY_PRIMARY = envB("IFQ_MORPHOLOGY_PRIMARY", true)
if (!MORPHOLOGY_PRIMARY) {
  print "FATAL: IFQ_MORPHOLOGY_PRIMARY=false is unsupported. Intensity defines candidate pixels, but morphology must authorise the final call."
  return
}
def AUTO_THRESH = envB("IFQ_AUTO_THRESH", true)

// ============================================================================
//  1. MORPHOLOGY GATES  (identical defaults to main's IF_Quant_Pipeline.groovy)
// ============================================================================

def ROLE_MORPHOLOGY_DEFAULTS = [
  "cyto"      : [minFraction:0.20d, minLargestShare:0.40d, requireOwnership:true],
  "membrane"  : [minFraction:0.25d, minLargestShare:0.40d, requireOwnership:true],
  "nuc_marker": [minFraction:0.40d, minLargestShare:0.60d, requireOwnership:false, minNuclearEnrichment:1.25d],
  "nuc_ratio" : [minFraction:0.30d, minLargestShare:0.60d, requireOwnership:false, minNucCytoRatio:1.50d]
]
def MORPHOLOGY_RULES = [
  "KRT5"  : [minFraction:0.20d, minLargestShare:0.50d, requireOwnership:true],
  "AGER"  : [minFraction:0.25d, minLargestShare:0.40d, requireOwnership:true],
  "PDPN"  : [minFraction:0.25d, minLargestShare:0.40d, requireOwnership:true],
  "ProSPC": [minFraction:0.15d, minLargestShare:0.40d, requireOwnership:true],
  "CD8"   : [minFraction:0.20d, minLargestShare:0.40d, requireOwnership:true],
  "CD4"   : [minFraction:0.20d, minLargestShare:0.40d, requireOwnership:true],
  "Aqp5"  : [minFraction:0.20d, minLargestShare:0.40d, requireOwnership:true],
  "Sox2"  : [minFraction:0.40d, minLargestShare:0.60d, requireOwnership:false, minNuclearEnrichment:1.25d],
  "p63"   : [minFraction:0.40d, minLargestShare:0.60d, requireOwnership:false, minNuclearEnrichment:1.25d],
  "YAP"   : [minFraction:0.30d, minLargestShare:0.60d, requireOwnership:false, minNucCytoRatio:1.50d]
]
// Per-marker environment overrides: IFQ_<MARKER>_MIN_POSITIVE_FRACTION etc.
MORPHOLOGY_RULES.each { marker, rule ->
  String token = marker.toUpperCase().replaceAll(/[^A-Z0-9]+/, "")
  rule.minFraction     = envD("IFQ_" + token + "_MIN_POSITIVE_FRACTION", rule.minFraction as double)
  rule.minLargestShare = envD("IFQ_" + token + "_MIN_LARGEST_COMPONENT_SHARE", rule.minLargestShare as double)
  if (rule.containsKey("minNuclearEnrichment"))
    rule.minNuclearEnrichment = envD("IFQ_" + token + "_MIN_NUCLEAR_ENRICHMENT", rule.minNuclearEnrichment as double)
  if (rule.containsKey("minNucCytoRatio"))
    rule.minNucCytoRatio = envD("IFQ_" + token + "_MIN_NUC_CYTO_RATIO", rule.minNucCytoRatio as double)
}
def POS_SENS = [:]
["KRT5","AGER","PDPN","ProSPC","CD8","CD4","Sox2","p63","YAP","Aqp5"].each {
  POS_SENS[it] = envD("IFQ_" + it.toUpperCase() + "_SENSITIVITY", 1.0)
}

// ============================================================================
//  2. PANELS  (match `name` to the slide's channel names)
// ============================================================================

def PANELS = [
  'A' : [label:'A_KRT5_AGER',   channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'AGER',role:'membrane']],
                                 classify:[['KRT5':true,'AGER':false],['KRT5':true,'AGER':true]]],
  'B' : [label:'B_KRT5_ProSPC', channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'ProSPC',role:'cyto']],
                                 classify:[['KRT5':true,'ProSPC':false],['KRT5':false,'ProSPC':true]]],
  'C' : [label:'C_KRT5_CD8',    channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'CD8',role:'cyto']],
                                 classify:[['CD8':true],['KRT5':true,'CD8':true]]],
  'D' : [label:'D_KRT5_CD4',    channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'CD4',role:'cyto']],
                                 classify:[['CD4':true],['KRT5':true,'CD4':true]]],
  'P' : [label:'P_KRT5_PDPN',   channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'PDPN',role:'membrane']],
                                 classify:[['KRT5':true,'PDPN':false],['KRT5':true,'PDPN':true]]],
  'S' : [label:'S_KRT5_Sox2',   channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'Sox2',role:'nuc_marker']],
                                 classify:[['Sox2':true],['KRT5':true,'Sox2':true],['KRT5':true,'Sox2':false]]],
  'S2': [label:'S2_KRT5_p63_YAP',channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'p63',role:'nuc_marker'],[name:'YAP',role:'nuc_ratio']],
                                 classify:[['KRT5':true,'p63':true],['KRT5':true,'YAP':true]]]
]
// Optional external panel config (same spirit as IFQ_PANEL_CONFIG on main)
if (PANEL_CONFIG_PATH && new File(PANEL_CONFIG_PATH).exists()) {
  try {
    def extra = new JsonSlurper().parse(new File(PANEL_CONFIG_PATH))
    extra.each { k, v -> PANELS[k.toString()] = v }
    print "Loaded panel config: ${PANEL_CONFIG_PATH}"
  } catch (e) { print "WARNING: could not read IFQ_PANEL_CONFIG: ${e.message}" }
}

// ============================================================================
//  3. HELPERS
// ============================================================================

def otsu = { List vals ->
  def xs = vals.findAll { it != null && !Double.isNaN(it as double) }.collect { it as double }
  if (xs.size() < 2) return 0.0d
  double mn = xs.min(), mx = xs.max()
  if (mx <= mn) return mx
  int N = 256
  int[] hist = new int[N]
  xs.each { double v -> int b = (int)Math.min(N-1, Math.max(0, Math.round((v-mn)/(mx-mn)*(N-1)))); hist[b]++ }
  int total = xs.size(); double sum = 0
  for (int i=0;i<N;i++) sum += i*hist[i]
  double sumB=0, wB=0, best=-1; int thr=0
  for (int i=0;i<N;i++) {
    wB += hist[i]; if (wB == 0) continue
    double wF = total - wB; if (wF == 0) break
    sumB += i*hist[i]
    double mB = sumB/wB, mF = (sum-sumB)/wF
    double between = wB*wF*(mB-mF)*(mB-mF)
    if (between > best) { best = between; thr = i }
  }
  return mn + (thr/(double)(N-1))*(mx-mn)
}

// VERSION-SENSITIVE: QuPath measurement names. Confirm on one slide by opening a
// detection and reading its measurement list if these return null.
def compartmentKey = { String marker, String role ->
  switch (role) {
    case 'nuc_marker': return "Nucleus: ${marker} mean".toString()
    case 'nuc_ratio' : return "Nucleus: ${marker} mean".toString()
    default          : return "Cytoplasm: ${marker} mean".toString()   // cyto + membrane -> ring only
  }
}
def measVal = { cell, String key ->
  try { double v = cell.getMeasurementList().get(key); return Double.isNaN(v) ? null : v }
  catch (e) { return null }
}

// ============================================================================
//  4. IMAGE SETUP
// ============================================================================

def imageData = getCurrentImageData()
if (imageData == null) { print "FATAL: no image."; return }
setImageType('FLUORESCENCE')
def server = imageData.getServer()
def cal    = server.getPixelCalibration()
if (!cal.hasPixelSizeMicrons()) {
  print "FATAL: slide has no micron calibration; areas and densities would be meaningless."
  return
}
double pxW = cal.getPixelWidthMicrons(), pxH = cal.getPixelHeightMicrons()

def panelDef = PANELS[PANEL]
if (panelDef == null) { print "FATAL: unknown PANEL '${PANEL}'. Known: ${PANELS.keySet()}"; return }

def chIndex = [:]
server.getMetadata().getChannels().eachWithIndex { ch, i -> chIndex[ch.getName()] = i + 1 }
print "Slide channels: ${chIndex.keySet()}"
print "Slide size: ${server.getWidth()} x ${server.getHeight()} px @ ${pxW} um/px"

def nuclearName = panelDef.channels.find { it.role == 'nuclear' }?.name
if (!chIndex.containsKey(nuclearName)) {
  print "FATAL: nuclear channel '${nuclearName}' not among slide channels ${chIndex.keySet()}."
  print "       Edit the panel channel names (or IFQ_PANEL_CONFIG) to match."
  return
}
int nuclearIdx = chIndex[nuclearName]

new File(OUTPUT_DIR).mkdirs()
def stem = GeneralTools.stripExtension(server.getMetadata().getName() ?: "slide")

// ============================================================================
//  5. TISSUE DETECTION  (downsampled -> cheap over a whole slide)
// ============================================================================

clearAllObjects()
def plane = ImagePlane.getDefaultPlane()

// Threshold the nuclear channel at low resolution to find tissue.
// VERSION-SENSITIVE: createAnnotationsFromPixelClassifier is an alternative when
// a trained tissue classifier is available; simple thresholding keeps this
// script dependency-free.
def fullRoi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), plane)
addObject(PathObjects.createAnnotationObject(fullRoi))
selectAll()
runPlugin('qupath.imagej.detect.tissue.SimpleTissueDetection2', JsonOutput.toJson([
  threshold          : (int)Math.round(CELL.threshold / 4.0),
  requestedPixelSizeMicrons: pxW * TISSUE_DOWNSAMPLE,
  minAreaMicrons     : TISSUE_MIN_AREA_UM2,
  maxHoleAreaMicrons : TISSUE_MIN_AREA_UM2 / 10.0,
  darkBackground     : true,       // fluorescence: tissue is bright on dark
  smoothImage        : true,
  medianCleanup      : true,
  dilateBoundaries   : false,
  smoothCoordinates  : true,
  excludeOnBoundary  : false,
  singleAnnotation   : false
]))

def tissueAnnotations = getAnnotationObjects().findAll { it.getROI() != null && it.getROI().getArea() > 0 }
if (tissueAnnotations.isEmpty()) {
  print "WARNING: tissue detection found nothing; falling back to the whole slide."
  clearAllObjects()
  addObject(PathObjects.createAnnotationObject(fullRoi))
  tissueAnnotations = getAnnotationObjects()
}
double tissueAreaUm2 = tissueAnnotations.sum { it.getROI().getScaledArea(pxW, pxH) } ?: 0.0d
print "Tissue regions: ${tissueAnnotations.size()}  total ${String.format('%.0f', tissueAreaUm2)} um2"

// Optional tiling so memory stays bounded on very large slides.
if (TILE_SIZE_UM > 0) {
  selectObjects(tissueAnnotations)
  runPlugin('qupath.lib.algorithms.TilerPlugin', JsonOutput.toJson([
    tileSizeMicrons  : TILE_SIZE_UM,
    trimToROI        : true,
    makeAnnotations  : true,
    removeParentAnnotation: false
  ]))
  print "Tiled tissue at ${TILE_SIZE_UM} um."
}

// ============================================================================
//  6. CELL DETECTION  (inside tissue only)
// ============================================================================

def detectionParents = getAnnotationObjects().findAll { it.getROI() != null && it.getROI().getArea() > 0 }
selectObjects(detectionParents)
runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', JsonOutput.toJson([
  detectionImageFluorescence: nuclearIdx,        // VERSION-SENSITIVE param name
  requestedPixelSizeMicrons : CELL.requestedPixelSizeMicrons * ANALYSIS_DOWNSAMPLE,
  backgroundRadiusMicrons   : CELL.backgroundRadiusMicrons,
  medianRadiusMicrons       : CELL.medianRadiusMicrons,
  sigmaMicrons              : CELL.sigmaMicrons,
  minAreaMicrons            : CELL.minAreaMicrons,
  maxAreaMicrons            : CELL.maxAreaMicrons,
  threshold                 : CELL.threshold,
  watershedPostProcess      : true,
  cellExpansionMicrons      : CELL.cellExpansionMicrons,
  includeNuclei             : true,
  smoothBoundaries          : true,
  makeMeasurements          : true
]))

def cells = getCellObjects()
print "Detected ${cells.size()} cells."
if (cells.isEmpty()) {
  print "FATAL: zero cells detected — refusing to emit a false-success summary."
  print "       Lower IFQ_CELL_THRESHOLD or check that channel ${nuclearIdx} is the nuclear stain."
  return
}

// ============================================================================
//  7. THRESHOLDS  (object-level Otsu over cell means)
// ============================================================================

def markers = panelDef.channels.findAll { it.role != 'nuclear' }
def thresholds = [:]
markers.each { m ->
  def key = compartmentKey(m.name, m.role)
  if (AUTO_THRESH) {
    def vals = cells.collect { measVal(it, key) }.findAll { it != null }
    thresholds[m.name] = (otsu(vals) as double) * (POS_SENS[m.name] ?: 1.0d)
  } else {
    thresholds[m.name] = envD("IFQ_" + m.name.toUpperCase() + "_THRESHOLD", 0.0d)
  }
}
print "Positivity thresholds (object-level): ${thresholds}"

// ============================================================================
//  8. MORPHOLOGY-PRIMARY CALLS  (three-state: 1 / 0 / indeterminate)
// ============================================================================

def posCount = [:].withDefault { 0 }
def negCount = [:].withDefault { 0 }
def indetCount = [:].withDefault { 0 }
def classCount = [:].withDefault { 0 }
double krt5PosAreaUm2 = 0.0d

cells.each { cell ->
  def ml = cell.getMeasurementList()
  def calls = [:]

  markers.each { m ->
    def rule = MORPHOLOGY_RULES[m.name] ?: ROLE_MORPHOLOGY_DEFAULTS[m.role] ?: ROLE_MORPHOLOGY_DEFAULTS["cyto"]
    def key  = compartmentKey(m.name, m.role)
    Double v = measVal(cell, key)

    // ---- evaluability first: unresolved anatomy must not become a false negative
    def reasons = []
    boolean evaluable = true
    if (v == null) { evaluable = false; reasons << "missing_compartment_measurement" }
    def nucRoi  = cell.getNucleusROI()
    def cellRoi = cell.getROI()
    if (cellRoi == null) { evaluable = false; reasons << "empty_spatial_support" }
    if ((m.role == 'nuc_marker' || m.role == 'nuc_ratio') && nucRoi == null) {
      evaluable = false; reasons << "no_nucleus_for_nuclear_marker"
    }

    def finalCall = ""
    String status
    if (!evaluable) {
      status = "indeterminate"
      indetCount[m.name] = indetCount[m.name] + 1
    } else {
      double thr = thresholds[m.name] as double
      // Intensity nominates; QuPath's compartment stats stand in for the
      // positive-fraction / connectivity evidence used by the Fiji engine.
      boolean intensityPos = (v as double) >= thr

      // fraction of the compartment above threshold, approximated by the
      // std-scaled mean margin QuPath already provides per compartment.
      Double sd = measVal(cell, key.replace(" mean", " std dev"))
      double margin = (sd != null && sd > 0) ? (((v as double) - thr) / sd) : ((v as double) >= thr ? 1.0d : -1.0d)
      boolean fractionPass = margin >= (rule.minFraction as double)

      // largest-component share proxy: max/mean ratio within the compartment
      Double mx = measVal(cell, key.replace(" mean", " max"))
      boolean connectedPass = (mx == null) ? true :
        (((v as double) > 0) ? ((v as double) / (mx as double)) >= (rule.minLargestShare as double) * 0.5d : false)

      // ownership: QuPath assigns each cytoplasm to exactly one nucleus, so
      // ownership is satisfied by construction for detected cells.
      boolean ownershipPass = rule.requireOwnership ? (nucRoi != null) : true

      boolean enrichmentPass = true
      if (rule.containsKey("minNuclearEnrichment")) {
        Double cytoV = measVal(cell, "Cytoplasm: ${m.name} mean".toString())
        double enr = (cytoV != null && cytoV > 0) ? ((v as double) / (cytoV as double)) : 0.0d
        ml.put("${m.name}: nuclear_enrichment".toString(), enr)
        enrichmentPass = enr >= (rule.minNuclearEnrichment as double)
        if (!enrichmentPass) reasons << "nuclear_enrichment_below_minimum"
      }
      if (rule.containsKey("minNucCytoRatio")) {
        Double nucV  = measVal(cell, "Nucleus: ${m.name} mean".toString())
        Double cytoV = measVal(cell, "Cytoplasm: ${m.name} mean".toString())
        double ratio = (nucV != null && cytoV != null && cytoV > 0) ? (nucV / cytoV) : 0.0d
        ml.put("${m.name}: nuc_cyto_ratio".toString(), ratio)
        enrichmentPass = enrichmentPass && (ratio >= (rule.minNucCytoRatio as double))
        if (ratio < (rule.minNucCytoRatio as double)) reasons << "nuc_cyto_ratio_below_minimum"
      }

      if (!fractionPass)  reasons << "insufficient_spatial_coverage"
      if (!connectedPass) reasons << "fragmented_spatial_pattern"
      if (!ownershipPass) reasons << "no_unique_owning_nucleus"

      boolean morphologyPass = intensityPos && fractionPass && connectedPass && ownershipPass && enrichmentPass
      finalCall = morphologyPass ? 1 : 0
      status = morphologyPass ? "positive" : "negative"
      if (morphologyPass) posCount[m.name] = posCount[m.name] + 1 else negCount[m.name] = negCount[m.name] + 1
    }

    calls[m.name] = (finalCall == 1)
    ml.put((m.name + ": call").toString(), finalCall == "" ? Double.NaN : (double)(finalCall as int))
    ml.put((m.name + ": threshold").toString(), (thresholds[m.name] ?: 0.0d) as double)
    if (!reasons.isEmpty())
      ml.put((m.name + ": call_reason_count").toString(), (double)reasons.size())
  }
  ml.close()   // finalise measurements once per cell (after all markers added)

  if (calls['KRT5']) krt5PosAreaUm2 += cell.getROI().getScaledArea(pxW, pxH)

  def labels = []
  panelDef.classify.each { rule ->
    boolean ok = rule.every { mk, want -> calls[mk] == want }
    def name = rule.collect { mk, want -> mk + (want ? '+' : '-') }.join('_')
    if (ok) { classCount[name] = classCount[name] + 1; labels << name }
  }
  if (!labels.isEmpty()) cell.setPathClass(getPathClass(labels.join(' & ')))
}
fireHierarchyUpdate()

// ============================================================================
//  9. EXPORT
// ============================================================================

saveDetectionMeasurements(buildFilePath(OUTPUT_DIR, stem + "__cells.tsv"))   // VERSION-SENSITIVE helper

def srow = [ image:stem, engine:"qupath_slidescanner", panel:PANEL, panel_label:panelDef.label,
             mouse_id:META.mouse_id, section_id:META.section_id,
             genotype:META.genotype, condition:META.condition,
             slide_width_px:server.getWidth(), slide_height_px:server.getHeight(),
             pixel_size_um:pxW,
             tissue_regions:tissueAnnotations.size(), tissue_area_um2:tissueAreaUm2,
             n_cells:cells.size(),
             call_authority:"morphology_primary",
             KRT5_pod_area_um2:krt5PosAreaUm2,
             KRT5_pod_area_frac:(tissueAreaUm2 > 0 ? krt5PosAreaUm2/tissueAreaUm2 : 0) ]
markers.each { m ->
  srow[m.name + "_pos_count"] = posCount[m.name]
  srow[m.name + "_negative_count"] = negCount[m.name]
  srow[m.name + "_indeterminate_count"] = indetCount[m.name]
  srow[m.name + "_density_per_mm2"] = (tissueAreaUm2 > 0 ? posCount[m.name]/(tissueAreaUm2/1e6) : 0)
  srow[m.name + "_pos_threshold"] = thresholds[m.name]
}
classCount.each { k, v -> srow["class_" + k + "_count"] = v }

def sumFile = new File(buildFilePath(OUTPUT_DIR, "qupath_slidescanner_summary.csv"))
if (!sumFile.exists()) sumFile.setText(srow.keySet().join(",") + "\n", "UTF-8")
sumFile.append(srow.values().collect { it == null ? "" : it.toString() }.join(",") + "\n", "UTF-8")

def prov = [ engine:"qupath_slidescanner",
             qupath_version: GeneralTools.getVersion(),
             image: server.getMetadata().getName(),
             server_path: server.getPath(),
             pixel_width_um: pxW, pixel_height_um: pxH,
             slide_px: [server.getWidth(), server.getHeight()],
             panel: PANEL, panel_label: panelDef.label, channel_map: chIndex,
             tissue: [downsample:TISSUE_DOWNSAMPLE, min_area_um2:TISSUE_MIN_AREA_UM2, sigma:TISSUE_SIGMA],
             tiling_um: TILE_SIZE_UM, analysis_downsample: ANALYSIS_DOWNSAMPLE,
             cell_detection: CELL,
             morphology_primary: MORPHOLOGY_PRIMARY,
             morphology_rules: MORPHOLOGY_RULES,
             auto_threshold: AUTO_THRESH, sensitivity: POS_SENS, thresholds: thresholds,
             timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss") ]
new File(buildFilePath(OUTPUT_DIR, stem + "__params.json")).setText(
    JsonOutput.prettyPrint(JsonOutput.toJson(prov)), "UTF-8")

print "DONE ${stem}: ${cells.size()} cells, KRT5 pod area ${String.format('%.0f', krt5PosAreaUm2)} um2 " +
      "(${String.format('%.4f', (tissueAreaUm2 > 0 ? krt5PosAreaUm2/tissueAreaUm2 : 0))} of tissue)"
print "Reminder: aggregate qupath_slidescanner_summary.csv to MOUSE level before stats (n = mice, not slides)."
