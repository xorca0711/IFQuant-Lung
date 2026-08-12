// ============================================================================
// H&E exploratory pilot — QuPath 0.7 headless
//
// This is a bounded engineering run, not a validated pathology engine. It
// verifies H0-H3 of config/brightfield/he_decision_hierarchy.json and emits
// coarse, review-facing candidates. It never calls the fluorescence Fiji
// engine and never assigns immune lineage.
//
// Required environment:
//   IFQ_HE_STUDY_CONFIG   absolute study JSON path
//   IFQ_HE_OUTPUT         new/empty run directory
// Optional:
//   IFQ_HE_DOWNSAMPLE     default 64 (coarse pilot only)
//   IFQ_HE_TISSUE_OD_THRESHOLD default 0.18
// ============================================================================

import qupath.lib.images.servers.ImageServers
import qupath.lib.io.GsonTools

import javax.imageio.ImageIO
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.text.DecimalFormat

def fail = { String message ->
    System.err.println("HE_PILOT_ERROR\t${message}")
    System.exit(2)
}

def studyPath = System.getenv("IFQ_HE_STUDY_CONFIG")
def outputPath = System.getenv("IFQ_HE_OUTPUT")
if (!studyPath?.trim()) fail("IFQ_HE_STUDY_CONFIG is required")
if (!outputPath?.trim()) fail("IFQ_HE_OUTPUT is required")

File studyFile = new File(studyPath)
File outputRoot = new File(outputPath)
if (!studyFile.isFile()) fail("Study config not found: ${studyFile}")
if (!outputRoot.isDirectory() && !outputRoot.mkdirs()) fail("Cannot create output: ${outputRoot}")
if (outputRoot.listFiles()?.length) fail("Output directory must be empty: ${outputRoot}")

double downsample = 64.0d
try {
    def raw = System.getenv("IFQ_HE_DOWNSAMPLE")
    if (raw?.trim()) downsample = Double.parseDouble(raw)
} catch (Exception ignored) { fail("IFQ_HE_DOWNSAMPLE must be numeric") }
if (downsample < 8d || downsample > 128d) fail("Pilot downsample must be in [8,128]")
double tissueOdThreshold = 0.18d
try {
    def raw = System.getenv("IFQ_HE_TISSUE_OD_THRESHOLD")
    if (raw?.trim()) tissueOdThreshold = Double.parseDouble(raw)
} catch (Exception ignored) { fail("IFQ_HE_TISSUE_OD_THRESHOLD must be numeric") }
if (tissueOdThreshold < 0.10d || tissueOdThreshold > 1.0d)
    fail("Pilot tissue OD threshold must be in [0.10,1.0]")

def gson = GsonTools.getInstance(true)
Map study = gson.fromJson(studyFile.getText("UTF-8"), Map.class)
if (study.modality != "brightfield_he") fail("Study modality is not brightfield_he")
if (study.biological_unit != "mouse") fail("Study biological unit must be mouse")

File previewsDir = new File(outputRoot, "previews")
File overlaysDir = new File(outputRoot, "qc_overlays")
File tablesDir = new File(outputRoot, "tables")
[previewsDir, overlaysDir, tablesDir].each { it.mkdirs() }

// VSI pixel data are split between the small container and companion ETS
// files, so hashing the container alone would imply provenance coverage it
// does not provide. Record stable size/time identity for the source set and
// reserve SHA-256 for the small versioned configuration artifacts.
def sourceIdentity = { File file ->
    String stem = file.name.substring(0, file.name.length() - 4)
    def companions = file.parentFile.listFiles().findAll {
        it.isFile() && (it.name == file.name || it.name.startsWith(stem + "_"))
    }.sort { it.name }
    [
        file_count: companions.size(),
        total_bytes: companions.collect { it.length() }.sum() ?: file.length(),
        latest_modified_utc: companions.collect { it.lastModified() }.max()
    ]
}

def sha256 = { File file ->
    MessageDigest md = MessageDigest.getInstance("SHA-256")
    file.withInputStream { input ->
        byte[] buffer = new byte[1024 * 1024]
        int n
        while ((n = input.read(buffer)) > 0) md.update(buffer, 0, n)
    }
    md.digest().collect { String.format("%02x", it & 0xff) }.join()
}

def sanitize = { String value -> value.replaceAll('[^A-Za-z0-9._-]+', '_') }
def fmt = new DecimalFormat("0.000000")
def csv = { Object value ->
    String s = value == null ? "" : value.toString()
    '"' + s.replace('"', '""') + '"'
}

// Standard H&E optical-density basis. These vectors are provisional and are
// recorded in the manifest; a reviewed batch vector must replace them before
// any endpoint is confirmatory.
double[] hVec = [0.65111d, 0.70119d, 0.29049d]
double[] eVec = [0.21590d, 0.80120d, 0.55810d]
double[] rVec = [
    hVec[1] * eVec[2] - hVec[2] * eVec[1],
    hVec[2] * eVec[0] - hVec[0] * eVec[2],
    hVec[0] * eVec[1] - hVec[1] * eVec[0]
]
double rNorm = Math.sqrt(rVec.collect { it * it }.sum() as double)
rVec = rVec.collect { it / rNorm } as double[]
double[][] m = [
    [hVec[0], eVec[0], rVec[0]] as double[],
    [hVec[1], eVec[1], rVec[1]] as double[],
    [hVec[2], eVec[2], rVec[2]] as double[]
] as double[][]
double det = m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1]) -
             m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0]) +
             m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0])
double[][] inv = [
    [(m[1][1]*m[2][2]-m[1][2]*m[2][1])/det, (m[0][2]*m[2][1]-m[0][1]*m[2][2])/det, (m[0][1]*m[1][2]-m[0][2]*m[1][1])/det] as double[],
    [(m[1][2]*m[2][0]-m[1][0]*m[2][2])/det, (m[0][0]*m[2][2]-m[0][2]*m[2][0])/det, (m[0][2]*m[1][0]-m[0][0]*m[1][2])/det] as double[],
    [(m[1][0]*m[2][1]-m[1][1]*m[2][0])/det, (m[0][1]*m[2][0]-m[0][0]*m[2][1])/det, (m[0][0]*m[1][1]-m[0][1]*m[1][0])/det] as double[]
] as double[][]

int histogramBins = 2048
double histogramMax = 2.0d
def records = []

def sectionSpecs = []
(study.samples as List).each { Map sample ->
    (study.analytical_series.allow_names as List).eachWithIndex { Object seriesNameObj, int position ->
        String seriesName = seriesNameObj.toString()
        int seriesIndex = ((Number)study.analytical_series.series_index_by_name[seriesName]).intValue()
        String sectionId = (sample.section_ids as List)[position].toString()
        sectionSpecs << [sample: sample, seriesName: seriesName, seriesIndex: seriesIndex, sectionId: sectionId]
    }
}
if (sectionSpecs.size() != ((Number)study.expected_analytical_sections).intValue())
    fail("Study section count does not match expected_analytical_sections")

// Pass 1: source/series validation and H-concentration distributions.
sectionSpecs.eachWithIndex { Map spec, int ordinal ->
    Map sample = spec.sample as Map
    File slide = new File(study.source_root.toString(), sample.source_file.toString())
    if (!slide.isFile()) fail("Missing source slide: ${slide}")
    println "HE_PILOT_PROGRESS\tPASS1\t${ordinal + 1}/${sectionSpecs.size()}\t${spec.sectionId}"

    def server = ImageServers.buildServer(slide.toURI(), "--series", spec.seriesIndex.toString())
    try {
        Map identity = sourceIdentity(slide)
        def cal = server.pixelCalibration
        double pxW = cal.getPixelWidthMicrons()
        double pxH = cal.getPixelHeightMicrons()
        if (!server.isRGB()) fail("${spec.sectionId}: analytical series is not RGB")
        if (server.nChannels() != 3) fail("${spec.sectionId}: expected 3 packed RGB channels")
        if (!Double.isFinite(pxW) || pxW < 0.27d || pxW > 0.28d)
            fail("${spec.sectionId}: unexpected pixel width ${pxW}")

        BufferedImage image = server.readRegion(downsample, 0, 0, server.width, server.height)
        int width = image.width
        int height = image.height
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width)
        long[] hHist = new long[histogramBins]
        long tissuePixels = 0
        long redPenPixels = 0
        long darkReviewPixels = 0
        for (int argb : pixels) {
            int r = (argb >> 16) & 0xff
            int g = (argb >> 8) & 0xff
            int b = argb & 0xff
            boolean redPen = r > 120 && r > g * 1.35d && r > b * 1.20d && r - Math.min(g, b) > 45
            if (redPen) { redPenPixels++; continue }
            double odR = -Math.log((r + 1d) / 256d)
            double odG = -Math.log((g + 1d) / 256d)
            double odB = -Math.log((b + 1d) / 256d)
            double odSum = odR + odG + odB
            boolean tissue = odSum > tissueOdThreshold && Math.min(r, Math.min(g, b)) < 248
            if (!tissue) continue
            tissuePixels++
            if (r + g + b < 75) darkReviewPixels++
            double h = Math.max(0d, inv[0][0]*odR + inv[0][1]*odG + inv[0][2]*odB)
            int bin = Math.min(histogramBins - 1, Math.max(0, (int)Math.floor(h / histogramMax * histogramBins)))
            hHist[bin]++
        }
        if (tissuePixels < 1000) fail("${spec.sectionId}: too little tissue in pilot image")

        def percentile = { double q ->
            long target = Math.max(1L, (long)Math.ceil(q * tissuePixels))
            long seen = 0
            for (int i = 0; i < hHist.length; i++) {
                seen += hHist[i]
                if (seen >= target) return (i + 0.5d) * histogramMax / histogramBins
            }
            histogramMax
        }
        Map rec = [
            study_id: study.study_id, mouse_id: sample.mouse_id,
            biological_unit_id: sample.biological_unit_id,
            genotype: sample.genotype, condition: sample.condition,
            slide_id: sanitize(slide.name.substring(0, slide.name.length()-4)),
            section_id: spec.sectionId, series_name: spec.seriesName,
            series_index: spec.seriesIndex, source_file: slide.absolutePath,
            source_file_count: identity.file_count,
            source_total_bytes: identity.total_bytes,
            source_latest_modified_utc_ms: identity.latest_modified_utc,
            source_width_px: server.width,
            source_height_px: server.height, source_pixel_width_um: pxW,
            source_pixel_height_um: pxH, pilot_downsample: downsample,
            pilot_width_px: width, pilot_height_px: height,
            tissue_pixels: tissuePixels, red_pen_candidate_pixels: redPenPixels,
            dark_artifact_review_pixels: darkReviewPixels,
            h_p50: percentile(0.50d), h_p75: percentile(0.75d),
            h_p90: percentile(0.90d), h_p95: percentile(0.95d),
            image: image
        ]
        records << rec
    } finally { server.close() }
}

// Control-derived exploratory candidate threshold. This is not a biological
// calibration: it only gives the review overlays one consistent operating
// point that never looked at the infected sections.
def controls = records.findAll { it.condition == "PR8_no_infection" }
if (!controls) fail("No uninfected control sections were declared")
double denseHThreshold = controls.collect { it.h_p90 as double }.max() as double

// Pass 2: coarse candidate maps and review overlays.
records.eachWithIndex { Map rec, int ordinal ->
    println "HE_PILOT_PROGRESS\tPASS2\t${ordinal + 1}/${records.size()}\t${rec.section_id}"
    BufferedImage image = rec.remove("image") as BufferedImage
    int width = image.width
    int height = image.height
    int[] pixels = image.getRGB(0, 0, width, height, null, 0, width)
    BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    Graphics2D graphics = overlay.createGraphics()
    graphics.drawImage(image, 0, 0, null)
    long tissue = 0, dense = 0, red = 0, dark = 0
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int argb = pixels[y * width + x]
            int r = (argb >> 16) & 0xff
            int g = (argb >> 8) & 0xff
            int b = argb & 0xff
            boolean redPen = r > 120 && r > g * 1.35d && r > b * 1.20d && r - Math.min(g, b) > 45
            if (redPen) {
                red++
                overlay.setRGB(x, y, new Color(255, 170, 0, 150).getRGB())
                continue
            }
            double odR = -Math.log((r + 1d) / 256d)
            double odG = -Math.log((g + 1d) / 256d)
            double odB = -Math.log((b + 1d) / 256d)
            boolean inTissue = odR + odG + odB > tissueOdThreshold && Math.min(r, Math.min(g, b)) < 248
            if (!inTissue) continue
            tissue++
            boolean darkReview = r + g + b < 75
            if (darkReview) dark++
            double h = Math.max(0d, inv[0][0]*odR + inv[0][1]*odG + inv[0][2]*odB)
            if (h >= denseHThreshold) {
                dense++
                overlay.setRGB(x, y, new Color(0, 220, 255, 105).getRGB())
            } else if (darkReview) {
                overlay.setRGB(x, y, new Color(255, 0, 255, 150).getRGB())
            }
        }
    }
    graphics = overlay.createGraphics()
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    int banner = Math.max(42, (int)Math.round(height * 0.045d))
    graphics.setColor(new Color(0, 0, 0, 185))
    graphics.fillRect(0, 0, width, banner)
    graphics.setColor(Color.WHITE)
    graphics.setFont(new Font("SansSerif", Font.BOLD, (int)Math.max(14, banner / 3)))
    graphics.drawString("${rec.section_id} | CYAN dense-H candidate | ORANGE red-pen candidate | MAGENTA dark review", 14, banner * 2 / 3)
    graphics.dispose()

    File preview = new File(previewsDir, "${rec.section_id}__raw.png")
    File overlayFile = new File(overlaysDir, "${rec.section_id}__H3_candidates.png")
    ImageIO.write(image, "PNG", preview)
    ImageIO.write(overlay, "PNG", overlayFile)

    rec.dense_h_threshold = denseHThreshold
    rec.usable_tissue_pixel_fraction_of_frame = tissue / (double)(width * height)
    rec.dense_h_candidate_fraction_of_tissue = tissue ? dense / (double)tissue : Double.NaN
    rec.red_pen_candidate_fraction_of_frame = red / (double)(width * height)
    rec.dark_artifact_review_fraction_of_tissue = tissue ? dark / (double)tissue : Double.NaN
    rec.review_status = "REVIEW_REQUIRED"
    rec.run_classification = "EXPLORATORY_ENGINEERING_ONLY"
    rec.raw_preview = preview.name
    rec.qc_overlay = overlayFile.name
}

def columns = [
    "study_id", "mouse_id", "biological_unit_id", "genotype", "condition",
    "slide_id", "section_id", "series_name", "series_index", "source_file",
    "source_file_count", "source_total_bytes", "source_latest_modified_utc_ms",
    "source_width_px", "source_height_px",
    "source_pixel_width_um", "source_pixel_height_um", "pilot_downsample",
    "pilot_width_px", "pilot_height_px", "tissue_pixels",
    "usable_tissue_pixel_fraction_of_frame", "h_p50", "h_p75", "h_p90", "h_p95",
    "dense_h_threshold", "dense_h_candidate_fraction_of_tissue",
    "red_pen_candidate_pixels", "red_pen_candidate_fraction_of_frame",
    "dark_artifact_review_pixels", "dark_artifact_review_fraction_of_tissue",
    "review_status", "run_classification", "raw_preview", "qc_overlay"
]
File sectionCsv = new File(tablesDir, "he_section_qc.csv")
sectionCsv.withWriter("UTF-8") { writer ->
    writer.println(columns.collect(csv).join(','))
    records.each { rec -> writer.println(columns.collect { csv(rec[it]) }.join(',')) }
}

Map manifest = [
    schema_version: "1.0.0", module: "brightfield_he_exploratory_pilot",
    status: "COMPLETE_REVIEW_REQUIRED", run_classification: "EXPLORATORY_ENGINEERING_ONLY",
    study_id: study.study_id, biological_unit: "mouse",
    mouse_count: records.collect { it.mouse_id }.unique().size(),
    section_count: records.size(), downsample: downsample,
    tissue_od_sum_threshold: tissueOdThreshold,
    dense_h_candidate_rule: "H concentration >= max(uninfected section p90)",
    dense_h_threshold: denseHThreshold,
    provisional_stain_vectors: [hematoxylin: hVec, eosin: eVec],
    automatic_artifact_exclusion: ["red_pen_candidate"],
    review_only_artifacts: ["dark_saturated_candidate", "fold", "tear", "blur", "dust"],
    limitations: [
        "Downsampled pixel candidates are not validated pathology endpoints.",
        "Hematoxylin-dense signal denotes cellularity candidates, not immune lineage.",
        "No mouse-level biological comparison is emitted.",
        "Artifact, compartment and lesion masks require blinded review."
    ],
    study_config: studyFile.absolutePath,
    study_config_sha256: sha256(studyFile),
    outputs: [section_table: "tables/he_section_qc.csv", previews: "previews", overlays: "qc_overlays"]
]
new File(outputRoot, "he_run_manifest.json").setText(gson.toJson(manifest), "UTF-8")
new File(outputRoot, "REVIEW_REQUIRED.txt").setText(
    "EXPLORATORY ENGINEERING OUTPUT — NOT A REPORTABLE ENDPOINT\r\n" +
    "Review every H3 overlay. Cyan is a control-locked dense-hematoxylin candidate, " +
    "orange is an automatically excluded red-pen candidate, and magenta is a dark-artifact review candidate.\r\n",
    "UTF-8")

println "HE_PILOT_COMPLETE\t${outputRoot.absolutePath}\tsections=${records.size()}\tdense_h_threshold=${fmt.format(denseHThreshold)}"
