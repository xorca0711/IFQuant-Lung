// demo_blackbackground_bug.groovy -- reproduce and demonstrate the project's
// central bug on the synthetic fixture, using the REAL ImageJ code path.
//
// THE BUG. IF_Quant_Pipeline.groovy line 1793 runs, on the auto-tissue mask:
//     IJ.run(mask, "Options...", "iterations=2 count=1 black do=Close")
// An earlier version omitted the `black` token. ImageJ's Binary Options dialog
// is a GenericDialog: in macro mode an ABSENT checkbox keyword reads as
// UNCHECKED, so the buggy string silently wrote Prefs.blackBackground=false
// GLOBALLY -- overriding the `Prefs.blackBackground = true` set at pipeline
// startup (line 3501). The tissue stage runs BEFORE nucleus segmentation, so
// every subsequent `Fill Holes` (ij.plugin.filter.Binary computes
// fg = Prefs.blackBackground ? 255 : 0, then flips again for an inverted LUT)
// ran with inverted polarity: the border flood-fill claims the whole field and
// every nucleus NOT touching the image frame is erased.
//
// THIS DEMO runs the exact production nucleus-candidate sequence twice on the
// same deterministic synthetic field:
//   world A: after IJ.run(mask, "Options...", "iterations=2 count=1 black do=Close")  (the FIXED call)
//   world B: after IJ.run(mask, "Options...", "iterations=2 count=1 do=Close")        (the BUGGY call)
// and prints both particle counts plus the measured Prefs.blackBackground after
// each Options... call, so the mechanism itself is visible.
//
// TRANSCRIPTION SOURCES (IF_Quant_Pipeline.groovy is FROZEN; nothing there is
// touched -- the steps below are transcribed verbatim, with shipped defaults):
//   line 3501       : Prefs.blackBackground = true            (pipeline startup)
//   lines 1584-1604 : buildThresholdMask (tissue duplicate -> Gaussian blur ->
//                     setAutoThreshold "<method> dark" -> Convert to Mask)
//   lines 363-365   : TISSUE_BLUR_SIGMA_PX = 4.0, TISSUE_THRESH_METHOD = "Triangle"
//   line 1793       : the Options... call under test
//   lines 1808-1866 : segmentNuclei classic path, local_phansalkar branch:
//     1812-1814 duplicate processor (not ImagePlus.duplicate), keep calibration
//     1838-1840 binary work copy
//     1842-1844 px = max(pixelWidth,1e-9); backgroundRadiusPx=max(3,round(15.0/px));
//               localRadiusPx=max(3,round(4.0/px))   (defaults, lines 292-293)
//     1845      IJ.run(m,"Subtract Background...","rolling=<bg> sliding")
//     1846      GaussianBlur sigma 1.0                (default, line 294)
//     1847      IJ.run(m,"Enhance Contrast...","saturated=0.35 normalize") (line 295)
//     1848      8-bit conversion
//     1849-1850 IJ.run(m,"Auto Local Threshold",
//                 "method=Phansalkar radius=<r> parameter_1=0 parameter_2=0 white")
//     1856      IJ.run(m,"Fill Holes","")             <-- the polarity victim
//     1857      IJ.run(m,"Watershed","")
//     1863      candidates = particlesToRois(m, 0.0, false)
//     1866      included   = particlesToRois(m, cfg.minNucArea, true)
//               (MIN_NUCLEUS_AREA_UM2 default 8.0 um^2, line 346)
//   lines 969-1015  : particlesToRois counting core (ParticleAnalyzer,
//                     SHOW_ROI_MASKS, calibrated-to-pixel area conversion,
//                     setThreshold(128,255,NO_LUT_UPDATE))
//
// Prefs.blackBackground is restored to true at the end regardless of outcome.
//
// Requires validation/out/fixture_dapi.tif (run generate_fixture.groovy first;
// run_demo.ps1 does both). No absolute paths: the out dir comes from
// IFQ_VALIDATION_OUT or defaults to ./out.

import ij.IJ
import ij.ImagePlus
import ij.Prefs
import ij.io.FileSaver
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.ParticleAnalyzer
import ij.process.ImageProcessor

// ------------------------------------------------------------------- inputs
def outDirPath = System.getenv("IFQ_VALIDATION_OUT")
if (outDirPath == null || outDirPath.trim().isEmpty()) {
  outDirPath = new File("out").getAbsolutePath()
}
def outDir = new File(outDirPath)
def fixtureFile = new File(outDir, "fixture_dapi.tif")
def truthFile = new File(outDir, "fixture_truth.txt")
if (!fixtureFile.isFile() || !truthFile.isFile()) {
  System.err.println("FATAL: fixture not found under " + outDir +
                     " -- run generate_fixture.groovy first (or use run_demo.ps1).")
  if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(1)
}
def truth = [:]
truthFile.getText("UTF-8").eachLine { line ->
  def kv = line.split("=", 2)
  if (kv.length == 2) truth[kv[0].trim()] = kv[1].trim()
}
int truthInterior = truth["interior_blobs"].toInteger()
int truthBorder   = truth["border_blobs"].toInteger()
int truthTotal    = truth["total_blobs"].toInteger()

// Counting core, transcribed from particlesToRois (lines 969-1015). No ROI
// restriction is ever set here, so the ROI-clearing branch does not apply.
def countParticles = { ImagePlus mask, double minAreaUm2, boolean excludeEdges ->
  def work = new ImagePlus(mask.getTitle() + "_count", mask.getProcessor().duplicate())
  work.setCalibration(mask.getCalibration())
  int opts = ParticleAnalyzer.SHOW_ROI_MASKS                       // line 994
  if (excludeEdges) opts |= ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES // line 995
  def rt = new ResultsTable()
  def workCal = work.getCalibration()
  double pixelArea = workCal.pixelWidth * workCal.pixelHeight       // line 1001
  double minAreaPixels = pixelArea > 0 ? minAreaUm2 / pixelArea : minAreaUm2 // line 1002
  def pa = new ParticleAnalyzer(opts, Measurements.AREA, rt, minAreaPixels, Double.MAX_VALUE)
  pa.setHideOutputImage(true)                                       // line 1007
  work.getProcessor().setThreshold(128, 255, ImageProcessor.NO_LUT_UPDATE) // line 1011
  boolean ok = pa.analyze(work)
  int n = ok ? rt.getCounter() : -1
  work.close()
  return n
}

// One "world": pipeline startup pref -> tissue stage -> Options... variant ->
// production nucleus-candidate sequence -> particle counts.
def runWorld = { String label, String optionsString ->
  Prefs.blackBackground = true                    // line 3501 (pipeline startup)
  def dapi = IJ.openImage(fixtureFile.getAbsolutePath())
  double px = dapi.getCalibration().pixelWidth
  if (Math.abs(px - 0.31d) > 0.01d) {
    println "WARNING: fixture calibration not restored from TIFF (pixelWidth=" + px + "); forcing 0.31 um/px"
    def cal = dapi.getCalibration(); cal.pixelWidth = 0.31d; cal.pixelHeight = 0.31d; cal.setUnit("micron")
  }

  // --- tissue stage: buildThresholdMask (lines 1584-1604), then line 1793 ---
  def tissue = new ImagePlus(dapi.getTitle() + "_tissue", dapi.getProcessor().duplicate())
  tissue.setCalibration(dapi.getCalibration())
  new GaussianBlur().blurGaussian(tissue.getProcessor(), 4.0d)  // TISSUE_BLUR_SIGMA_PX, line 363
  IJ.setAutoThreshold(tissue, "Triangle dark")                  // TISSUE_THRESH_METHOD, lines 364/1593
  IJ.run(tissue, "Convert to Mask", "")                         // line 1601
  boolean prefBefore = Prefs.blackBackground
  IJ.run(tissue, "Options...", optionsString)                   // line 1793 / buggy historical variant
  boolean prefAfter = Prefs.blackBackground
  tissue.close()

  // --- nucleus stage: segmentNuclei classic local_phansalkar (lines 1808-1857) ---
  def crop = new ImagePlus(dapi.getTitle() + "_segmentation_work",
                           dapi.getProcessor().duplicate())     // lines 1812-1813
  crop.setCalibration(dapi.getCalibration())                    // line 1814
  def m = new ImagePlus(crop.getTitle() + "_binary_work",
                        crop.getProcessor().duplicate())        // lines 1838-1839
  m.setCalibration(dapi.getCalibration())                       // line 1840
  double pxSafe = Math.max(dapi.getCalibration().pixelWidth, 1.0e-9d)          // line 1842
  int backgroundRadiusPx = Math.max(3, (int) Math.round(15.0d / pxSafe))       // line 1843, default line 292
  int localRadiusPx = Math.max(3, (int) Math.round(4.0d / pxSafe))             // line 1844, default line 293
  IJ.run(m, "Subtract Background...", "rolling=" + backgroundRadiusPx + " sliding") // line 1845
  new GaussianBlur().blurGaussian(m.getProcessor(), 1.0d)       // line 1846, default line 294
  IJ.run(m, "Enhance Contrast...", "saturated=0.35 normalize")  // line 1847, default line 295
  if (m.getBitDepth() != 8) IJ.run(m, "8-bit", "")              // line 1848
  IJ.run(m, "Auto Local Threshold",
         "method=Phansalkar radius=" + localRadiusPx + " parameter_1=0 parameter_2=0 white") // lines 1849-1850
  IJ.run(m, "Fill Holes", "")                                   // line 1856  <-- polarity victim
  IJ.run(m, "Watershed", "")                                    // line 1857

  int candidates = countParticles(m, 0.0d, false)               // line 1863
  int included = countParticles(m, 8.0d, true)                  // line 1866, default line 346
  new FileSaver(m).saveAsTiff(new File(outDir, "mask_world" + label + ".tif").getAbsolutePath())
  m.close(); crop.close(); dapi.close()

  println "WORLD " + label + ": Options string            = '" + optionsString + "'"
  println "WORLD " + label + ": Prefs.blackBackground      = " + prefBefore + " before Options..., " + prefAfter + " after"
  println "WORLD " + label + ": candidate particles        = " + candidates + "  (any size, edges included; cf. line 1863)"
  println "WORLD " + label + ": included nuclei            = " + included + "  (>= 8 um^2, edge-excluded; cf. line 1866)"
  return [candidates: candidates, included: included, prefAfter: prefAfter]
}

println "=== blackBackground bug demo (synthetic fixture, real ImageJ code path) ==="
println "TRUTH: interior blobs=" + truthInterior + " border blobs=" + truthBorder + " total=" + truthTotal
def a = runWorld("A", "iterations=2 count=1 black do=Close")   // the FIXED call (line 1793)
def b = runWorld("B", "iterations=2 count=1 do=Close")         // the BUGGY historical call

// Restore the global preference no matter what the worlds did to it.
Prefs.blackBackground = true
println "Prefs.blackBackground restored to true"

String factor = (b.included == 0) ? "infinite (world B included = 0)" :
                String.format("%.1fx", a.included / (double) b.included)
println "RESULT: worldA_included=" + a.included + " worldB_included=" + b.included +
        " undercount_factor=" + factor

// Verdict. World A must recover the interior ground truth to within 25%
// (border blobs are edge-excluded by the pipeline's own rule even when the
// code is correct, so the reference for the included count is interior_blobs).
// World B must collapse to under 20% of world A.
boolean aOk = truthInterior > 0 &&
              Math.abs(a.included - truthInterior) <= 0.25d * truthInterior
boolean bCollapsed = b.included < 0.2d * a.included
boolean mechanismShown = a.prefAfter && !b.prefAfter
if (aOk && bCollapsed && mechanismShown) {
  println "VERDICT: PASS -- fixed call keeps blackBackground=true and recovers " +
          a.included + "/" + truthInterior + " interior nuclei (within 25%); " +
          "buggy call flips blackBackground to false and collapses the count to " +
          b.included + " (< 20% of world A)."
} else {
  println "VERDICT: FAIL -- aWithinTolerance=" + aOk + " (worldA=" + a.included +
          " vs interior truth=" + truthInterior + " at 25%), bCollapsed=" + bCollapsed +
          " (worldB=" + b.included + "), prefFlipObserved=" + mechanismShown +
          " (A after=" + a.prefAfter + ", B after=" + b.prefAfter + ")"
}

if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(0)
