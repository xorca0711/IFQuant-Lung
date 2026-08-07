/*
 * ============================================================================
 *  QuPath_IF_Quant.groovy
 *  QuPath analysis pipeline for the IFN-gamma KO / PR8 influenza injury project
 *  (KRT5 pod remodeling readout). Sibling to the Fiji IF_Quant_Pipeline.groovy —
 *  SAME panels, markers and readouts, but built on QuPath's cell detection +
 *  per-compartment measurements.
 * ----------------------------------------------------------------------------
 *  Why a QuPath variant:
 *   - Native nucleus / cytoplasm / cell / membrane compartments -> the
 *     cytoplasmic ("cyto") and membrane markers are measured on the ring ONLY,
 *     not on nucleus+ring. This avoids the nuclear-dilution issue the Fiji smoke
 *     test surfaced for KRT5/AGER/PDPN.
 *   - Scales to whole-slide / large tiled fluorescence better than ImageJ.
 *
 *  Panels (DAPI + up to 2 primaries):
 *     A  KRT5/AGER    B  KRT5/ProSPC   C  KRT5/CD8   D  KRT5/CD4
 *     P  KRT5/PDPN    S  KRT5/Sox2     S2 KRT5/p63/YAP  (future)
 *
 *  Feature coverage (mirrors the Fiji pipeline):
 *   [x] Bio-Formats import (QuPath uses Bio-Formats; calibration preserved)
 *   [x] Channel-by-name mapping; per-compartment measurement
 *   [x] Tissue annotation (full image, or your own annotation/threshold)
 *   [x] Nucleus + cell detection (Watershed; StarDist optional block below)
 *   [x] KRT5+ pod AREA + KRT5+ cell counts
 *   [x] AGER/PDPN/ProSPC/CD4/CD8/Sox2 populations, densities per mm2
 *   [x] Double +/- classification (e.g. KRT5+/PDPN-)
 *   [x] Per-cell TSV, per-image summary CSV, provenance JSON
 * ----------------------------------------------------------------------------
 *  REQUIRES QuPath v0.5.x. Run from the GUI Script Editor with an image open,
 *  or headless (see QUPATH_README.md):
 *     QuPath script QuPath_IF_Quant.groovy -i /path/to/image.ome.tif
 *
 *  IMPORTANT — read before trusting numbers:
 *   * QuPath analyses a SINGLE 2D plane. Project/flatten confocal z-stacks
 *     before import (or set the plane); this script does not z-project.
 *   * This script is UNTESTED against a live QuPath here. Three lines are
 *     version-sensitive and marked "VERSION-SENSITIVE" — validate them once in
 *     your QuPath build, then batch. (Same validate-once discipline as Fiji.)
 *   * DEFAULT thresholds are placeholders. Confirm calls against the QuPath
 *     viewer and set POS_SENS / detection threshold before reporting.
 *   * n IS COUNTED BY MICE, not sections — mouse_id/section_id travel through
 *     the exports; aggregate to animal before stats (see aggregate_to_mouse.py).
 * ============================================================================
 */

import static qupath.lib.scripting.QP.*
import qupath.lib.objects.PathObjects
import qupath.lib.roi.ROIs
import qupath.lib.regions.ImagePlane
import qupath.lib.regions.RegionRequest
import qupath.lib.common.GeneralTools
import groovy.json.JsonOutput

// ============================================================================
//  1. USER CONFIG
// ============================================================================

def OUTPUT_DIR = buildFilePath(System.getProperty('user.home'), 'qupath_output')
def PANEL      = 'A'                       // A | B | C | D | P | S | S2

// Metadata (or parse from image name mouseID_condition_panel_section)
def META = [ mouse_id:'NA', section_id:'NA', genotype:'NA', condition:'NA' ]

// --- Cell detection (Watershed), calibrated microns ---
def CELL = [
  requestedPixelSizeMicrons: 0.5,
  backgroundRadiusMicrons  : 8.0,
  medianRadiusMicrons      : 0.0,
  sigmaMicrons             : 1.5,
  minAreaMicrons           : 10.0,
  maxAreaMicrons           : 400.0,
  threshold                : 100.0,   // nuclear-channel detection threshold (raw) -- TUNE
  cellExpansionMicrons     : 2.0,     // perinuclear ring (== RING_EXPAND_UM in Fiji)
  includeNuclei            : true,
  smoothBoundaries         : true,
  makeMeasurements         : true
]

// --- Positivity ---
// AUTO_THRESH=true -> per-marker cutoff = Otsu on the distribution of that
// marker's CELL/compartment means (object-level -> avoids the pixel-vs-object
// bias the Fiji run showed). Multiply by POS_SENS. Else use POS_THRESHOLD.
def AUTO_THRESH   = true
def POS_SENS      = ['KRT5':1.0,'AGER':1.0,'PDPN':1.0,'ProSPC':1.0,
                     'CD8':1.0,'CD4':1.0,'Sox2':1.0,'p63':1.0,'YAP':1.0]
def POS_THRESHOLD = [:]   // e.g. ['KRT5':120.0] when AUTO_THRESH=false

// --- Pod area ---
// 'cells'     = total area of KRT5+ detected cells (robust, QuPath-native)
// 'threshold' = raw KRT5+ pixel area (closest to the Fiji readout; reads raster)
def POD_METHOD          = 'cells'
def POD_THRESHOLD_SENS  = 1.0

// ============================================================================
//  2. PANEL DEFINITIONS  (match `name` to your image's channel names)
//     role: nuclear | cyto | membrane | nuc_marker | nuc_ratio
// ============================================================================

def PANELS = [
  'A' : [label:'A_KRT5_AGER',    channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'AGER',role:'membrane']],
                                  classify:[['KRT5':true,'AGER':false],['KRT5':true,'AGER':true]]],
  'B' : [label:'B_KRT5_ProSPC',  channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'ProSPC',role:'cyto']],
                                  classify:[['KRT5':true,'ProSPC':false],['KRT5':false,'ProSPC':true]]],
  'C' : [label:'C_KRT5_CD8',     channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'CD8',role:'cyto']],
                                  classify:[['CD8':true],['KRT5':true,'CD8':true]]],
  'D' : [label:'D_KRT5_CD4',     channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'CD4',role:'cyto']],
                                  classify:[['CD4':true],['KRT5':true,'CD4':true]]],
  'P' : [label:'P_KRT5_PDPN',    channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'PDPN',role:'membrane']],
                                  classify:[['KRT5':true,'PDPN':false],['KRT5':true,'PDPN':true]]],
  'S' : [label:'S_KRT5_Sox2',    channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'Sox2',role:'nuc_marker']],
                                  classify:[['Sox2':true],['KRT5':true,'Sox2':true],['KRT5':true,'Sox2':false]]],
  'S2': [label:'S2_KRT5_p63_YAP',channels:[[name:'DAPI',role:'nuclear'],[name:'KRT5',role:'cyto',areaMarker:true],[name:'p63',role:'nuc_marker'],[name:'YAP',role:'nuc_ratio']],
                                  classify:[['KRT5':true,'p63':true],['KRT5':true,'YAP':true]]]
]

// ============================================================================
//  3. HELPERS
// ============================================================================

// Otsu on a list of doubles (object-level threshold).
def otsu(List<Double> vals, int nbins = 256) {
  def xs = vals.findAll { it != null && !Double.isNaN(it) }
  if (xs.size() < 2) return 0.0
  double mn = xs.min(), mx = xs.max()
  if (mx <= mn) return mx
  int[] hist = new int[nbins]
  xs.each { double v -> int b = (int)Math.min(nbins-1, Math.max(0, Math.round((v-mn)/(mx-mn)*(nbins-1)))); hist[b]++ }
  int total = xs.size()
  double sum = 0; for (int i=0;i<nbins;i++) sum += i*hist[i]
  double sumB=0, wB=0, maxVar=-1; int thrBin=0
  for (int i=0;i<nbins;i++) {
    wB += hist[i]; if (wB==0) continue
    double wF = total-wB; if (wF==0) break
    sumB += i*hist[i]
    double mB = sumB/wB, mF = (sum-sumB)/wF
    double between = wB*wF*(mB-mF)*(mB-mF)
    if (between > maxVar) { maxVar=between; thrBin=i }
  }
  return mn + (thrBin/(double)(nbins-1))*(mx-mn)
}

// Compartment measurement name for a marker given its role. VERSION-SENSITIVE:
// QuPath names measurements "Nucleus: <ch> mean" / "Cytoplasm: <ch> mean" /
// "Cell: <ch> mean". Confirm the exact strings in your build (open a cell, read
// its measurement list) if lookups return null.
def compartmentKey(String marker, String role) {
  switch (role) {
    case 'nuc_marker': return "Nucleus: ${marker} mean"
    case 'membrane'  : return "Cytoplasm: ${marker} mean"   // ring; use Membrane if you enable it
    case 'nuc_ratio' : return "Nucleus: ${marker} mean"     // ratio handled separately
    default          : return "Cytoplasm: ${marker} mean"   // cyto -> the ring
  }
}

def measVal(cell, String key) {
  def ml = cell.getMeasurementList()
  double v = ml.get(key)          // VERSION-SENSITIVE: some builds use getMeasurementValue(key)
  return Double.isNaN(v) ? null : v
}

// ============================================================================
//  4. SETUP
// ============================================================================

def imageData = getCurrentImageData()
if (imageData == null) { print 'No image open.'; return }
setImageType('FLUORESCENCE')
def server = imageData.getServer()
def cal    = server.getPixelCalibration()
def hierarchy = imageData.getHierarchy()

def panelDef = PANELS[PANEL]
if (panelDef == null) { print "Unknown PANEL ${PANEL}"; return }

// channel name -> 1-based index
def serverChannels = server.getMetadata().getChannels()
def chIndex = [:]
serverChannels.eachWithIndex { ch, i -> chIndex[ch.getName()] = i + 1 }
print "Image channels: ${chIndex.keySet()}"

def nuclearName = panelDef.channels.find { it.role == 'nuclear' }?.name
if (!chIndex.containsKey(nuclearName)) {
  print "ERROR: nuclear channel '${nuclearName}' not found. Edit PANELS channel names to match: ${chIndex.keySet()}"
  return
}
int nuclearIdx = chIndex[nuclearName]

def outDir = new File(OUTPUT_DIR); outDir.mkdirs()
def stem = GeneralTools.stripExtension(server.getMetadata().getName() ?: 'image')

// ============================================================================
//  5. TISSUE ANNOTATION  (default: full image; replace with your own ROI /
//     a pixel-classifier tissue detection for lesion-restricted analysis)
// ============================================================================

clearAllObjects()
def plane = ImagePlane.getDefaultPlane()
def tissueRoi = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), plane)
def tissue = PathObjects.createAnnotationObject(tissueRoi)
addObject(tissue)
double tissueAreaUm2 = tissueRoi.getScaledArea(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons())

// ============================================================================
//  6. CELL DETECTION  (Watershed on the nuclear channel)
//     StarDist alternative: install the QuPath StarDist extension and replace
//     this block with StarDist2D.builder(modelPath)...detectObjects(...).
// ============================================================================

selectObjects(tissue)
def cellJson = JsonOutput.toJson([
  detectionImageFluorescence: nuclearIdx,        // VERSION-SENSITIVE param name
  requestedPixelSizeMicrons : CELL.requestedPixelSizeMicrons,
  backgroundRadiusMicrons   : CELL.backgroundRadiusMicrons,
  medianRadiusMicrons       : CELL.medianRadiusMicrons,
  sigmaMicrons              : CELL.sigmaMicrons,
  minAreaMicrons            : CELL.minAreaMicrons,
  maxAreaMicrons            : CELL.maxAreaMicrons,
  threshold                 : CELL.threshold,
  watershedPostProcess      : true,
  cellExpansionMicrons      : CELL.cellExpansionMicrons,
  includeNuclei             : CELL.includeNuclei,
  smoothBoundaries          : CELL.smoothBoundaries,
  makeMeasurements          : CELL.makeMeasurements
])
runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', cellJson)

def cells = getCellObjects()
print "Detected ${cells.size()} cells."

// ============================================================================
//  7. POSITIVITY THRESHOLDS  (object-level Otsu, or configured)
// ============================================================================

def markers = panelDef.channels.findAll { it.role != 'nuclear' }
def thresholds = [:]
markers.each { m ->
  def key = compartmentKey(m.name, m.role)
  if (AUTO_THRESH) {
    def vals = cells.collect { measVal(it, key) }.findAll { it != null }
    double t = otsu(vals)
    thresholds[m.name] = t * (POS_SENS[m.name] ?: 1.0)
  } else {
    thresholds[m.name] = (POS_THRESHOLD[m.name] ?: 0.0)
  }
}
print "Positivity thresholds: ${thresholds}"

// ============================================================================
//  8. CLASSIFY CELLS + tally
// ============================================================================

def posCount = [:].withDefault { 0 }
def classCount = [:].withDefault { 0 }
def krt5PosArea = 0.0

cells.each { cell ->
  def ml = cell.getMeasurementList()
  def calls = [:]
  markers.each { m ->
    def key = compartmentKey(m.name, m.role)
    double v = measVal(cell, key) ?: 0.0
    boolean pos
    if (m.role == 'nuc_ratio') {
      double nuc = measVal(cell, "Nucleus: ${m.name} mean") ?: 0.0
      double cyt = measVal(cell, "Cytoplasm: ${m.name} mean") ?: 0.0
      double ratio = (cyt > 0 ? nuc/cyt : 0.0)
      ml.put("${m.name}: nuc_cyto_ratio".toString(), ratio)
      pos = nuc >= thresholds[m.name]          // nuclear YAP positivity
    } else {
      pos = v >= thresholds[m.name]
    }
    calls[m.name] = pos
    ml.put("${m.name}: positive".toString(), pos ? 1 : 0)
    if (pos) posCount[m.name] = posCount[m.name] + 1
  }
  // KRT5+ cell area contribution (pod 'cells' method)
  if (calls['KRT5']) krt5PosArea += cell.getROI().getScaledArea(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons())

  // classifications
  def labels = []
  panelDef.classify.each { rule ->
    boolean ok = rule.every { mk, want -> calls[mk] == want }
    def keyName = rule.collect { mk, want -> mk + (want ? '+' : '-') }.join('_')
    ml.put(("class_" + keyName).toString(), ok ? 1 : 0)
    if (ok) { classCount[keyName] = classCount[keyName] + 1; labels << keyName }
  }
  if (labels) cell.setPathClass(getPathClass(labels.join(' & ')))
}
fireHierarchyUpdate()

// ============================================================================
//  9. POD AREA
// ============================================================================

double podAreaUm2
if (POD_METHOD == 'threshold') {
  // Raw KRT5+ pixel area over the tissue region (closest to the Fiji readout).
  int krt5Idx = chIndex[panelDef.channels.find { it.areaMarker }.name]
  def req = RegionRequest.createInstance(server.getPath(), 1.0,
              0, 0, server.getWidth(), server.getHeight(), plane.getZ(), plane.getT())
  def img = server.readRegion(req)                       // VERSION-SENSITIVE: readRegion vs readBufferedImage
  def raster = img.getRaster()
  int band = krt5Idx - 1
  def podVals = []
  // sample cell means to seed a pixel threshold consistent with object calls
  double podThr = (thresholds[panelDef.channels.find { it.areaMarker }.name]) * POD_THRESHOLD_SENS
  long posPx = 0
  double pxArea = cal.getPixelWidthMicrons() * cal.getPixelHeightMicrons()
  for (int y = 0; y < raster.getHeight(); y++)
    for (int x = 0; x < raster.getWidth(); x++)
      if (raster.getSampleDouble(x, y, band) >= podThr) posPx++
  podAreaUm2 = posPx * pxArea
} else {
  podAreaUm2 = krt5PosArea   // sum of KRT5+ cell areas
}

// ============================================================================
//  10. EXPORT
// ============================================================================

// per-cell measurements (detection table)
saveDetectionMeasurements(buildFilePath(OUTPUT_DIR, stem + '__cells.tsv'))   // VERSION-SENSITIVE helper

// per-image summary
def srow = [ image:stem, panel:PANEL,
             mouse_id:META.mouse_id, section_id:META.section_id,
             genotype:META.genotype, condition:META.condition,
             tissue_area_um2:tissueAreaUm2, n_cells:cells.size(),
             pod_method:POD_METHOD, KRT5_pod_area_um2:podAreaUm2,
             KRT5_pod_area_frac:(tissueAreaUm2>0 ? podAreaUm2/tissueAreaUm2 : 0) ]
markers.each { m ->
  srow[m.name + '_pos_count'] = posCount[m.name]
  srow[m.name + '_density_per_mm2'] = (tissueAreaUm2>0 ? posCount[m.name]/(tissueAreaUm2/1e6) : 0)
  srow[m.name + '_pos_threshold'] = thresholds[m.name]
}
classCount.each { k, v -> srow['class_' + k + '_count'] = v }

def sumFile = new File(buildFilePath(OUTPUT_DIR, 'qupath_summary.csv'))
if (!sumFile.exists()) sumFile.text = srow.keySet().join(',') + '\n'
sumFile.append(srow.values().collect { it == null ? '' : it.toString() }.join(',') + '\n')

// provenance
def prov = [ qupath_version: GeneralTools.getVersion(),
             image: server.getMetadata().getName(),
             pixel_width_um: cal.getPixelWidthMicrons(), pixel_height_um: cal.getPixelHeightMicrons(),
             panel: PANEL, panel_label: panelDef.label, channel_map: chIndex,
             cell_detection: CELL, auto_thresh: AUTO_THRESH, pos_sens: POS_SENS,
             thresholds: thresholds, pod_method: POD_METHOD ]
new File(buildFilePath(OUTPUT_DIR, stem + '__params.json')).text = JsonOutput.prettyPrint(JsonOutput.toJson(prov))

print "DONE: ${cells.size()} cells, KRT5 pod area ${podAreaUm2} um2 -> ${OUTPUT_DIR}"
print 'Reminder: aggregate qupath_summary.csv to MOUSE level before stats (n = mice).'
