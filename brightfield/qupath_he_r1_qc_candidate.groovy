// H&E R1 production-QC candidate — QuPath 0.7 headless.
// Generates deterministic inventory, frozen-profile stain previews, cleaned
// tissue masks, explicit artifact candidates, review queue, and provenance.
// It does not generate pathology, nuclear, ordinal, or mouse-level endpoints.

import qupath.lib.images.servers.ImageServers
import qupath.lib.io.GsonTools

import javax.imageio.ImageIO
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.security.MessageDigest

def fail = { String message ->
    System.err.println("HE_R1_ERROR\t${message}")
    System.exit(2)
}
def envRequired = { String key ->
    String value = System.getenv(key)
    if (!value?.trim()) fail("${key} is required")
    value
}
def parseDoubleEnv = { String key, double fallback, double low, double high ->
    String raw = System.getenv(key)
    double value = fallback
    try { if (raw?.trim()) value = Double.parseDouble(raw) }
    catch (Exception ignored) { fail("${key} must be numeric") }
    if (value < low || value > high) fail("${key} must be in [${low},${high}]")
    value
}
def parseIntEnv = { String key, int fallback, int low, int high ->
    String raw = System.getenv(key)
    int value = fallback
    try { if (raw?.trim()) value = Integer.parseInt(raw) }
    catch (Exception ignored) { fail("${key} must be an integer") }
    if (value < low || value > high) fail("${key} must be in [${low},${high}]")
    value
}

File studyFile = new File(envRequired("IFQ_HE_STUDY_CONFIG"))
File profileFile = new File(envRequired("IFQ_HE_STAIN_PROFILE"))
File outputRoot = new File(envRequired("IFQ_HE_OUTPUT"))
if (!studyFile.isFile()) fail("Study config not found: ${studyFile}")
if (!profileFile.isFile()) fail("Stain profile not found: ${profileFile}")
if (!outputRoot.isDirectory() && !outputRoot.mkdirs()) fail("Cannot create output: ${outputRoot}")
if (outputRoot.listFiles()?.length) fail("Output directory must be empty: ${outputRoot}")

double downsample = parseDoubleEnv("IFQ_HE_DOWNSAMPLE", 32d, 8d, 128d)
double tissueOdThreshold = parseDoubleEnv("IFQ_HE_TISSUE_OD_THRESHOLD", 0.18d, 0.10d, 1.0d)
int minTissueComponentPixels = parseIntEnv("IFQ_HE_MIN_TISSUE_COMPONENT_PIXELS", 64, 1, 1000000)
int maxHolePixels = parseIntEnv("IFQ_HE_MAX_HOLE_PIXELS", 32, 0, 1000000)

def gson = GsonTools.getInstance(true)
Map study = gson.fromJson(studyFile.getText("UTF-8"), Map.class)
Map profile = gson.fromJson(profileFile.getText("UTF-8"), Map.class)
if (study.modality != "brightfield_he") fail("Study modality is not brightfield_he")
if (study.biological_unit != "mouse") fail("Study biological unit must be mouse")
if (profile.image_type != "BRIGHTFIELD_H_E") fail("Stain profile image_type must be BRIGHTFIELD_H_E")
if (profile.study_id != study.study_id) fail("Stain profile study_id does not match study")
if (!(profile.status in ["CANDIDATE_REVIEW_REQUIRED", "REVIEWED_LOCKED"]))
    fail("Unsupported stain-profile status: ${profile.status}")

File previewsDir = new File(outputRoot, "previews")
File stainsDir = new File(outputRoot, "stain_separation")
File masksDir = new File(outputRoot, "masks")
File overlaysDir = new File(outputRoot, "qc_overlays")
File tablesDir = new File(outputRoot, "tables")
[previewsDir, stainsDir, masksDir, overlaysDir, tablesDir].each { it.mkdirs() }

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
def csv = { Object value ->
    String text = value == null ? "" : value.toString()
    '"' + text.replace('"', '""') + '"'
}

double[] hVec = (profile.stain_vectors.hematoxylin as List).collect { ((Number)it).doubleValue() } as double[]
double[] eVec = (profile.stain_vectors.eosin as List).collect { ((Number)it).doubleValue() } as double[]
if (hVec.length != 3 || eVec.length != 3) fail("Stain vectors must each contain three values")
double[] background = (profile.background_rgb as List).collect { ((Number)it).doubleValue() } as double[]
if (background.length != 3 || background.any { it < 1d || it > 255d }) fail("Invalid background_rgb")

double[] residual = [
    hVec[1] * eVec[2] - hVec[2] * eVec[1],
    hVec[2] * eVec[0] - hVec[0] * eVec[2],
    hVec[0] * eVec[1] - hVec[1] * eVec[0]
]
double residualNorm = Math.sqrt(residual.collect { it * it }.sum() as double)
residual = residual.collect { it / residualNorm } as double[]
double[][] matrix = [
    [hVec[0], eVec[0], residual[0]] as double[],
    [hVec[1], eVec[1], residual[1]] as double[],
    [hVec[2], eVec[2], residual[2]] as double[]
] as double[][]
double determinant = matrix[0][0]*(matrix[1][1]*matrix[2][2]-matrix[1][2]*matrix[2][1]) -
    matrix[0][1]*(matrix[1][0]*matrix[2][2]-matrix[1][2]*matrix[2][0]) +
    matrix[0][2]*(matrix[1][0]*matrix[2][1]-matrix[1][1]*matrix[2][0])
if (Math.abs(determinant) < 1e-8d) fail("Stain vectors form a singular matrix")
double[][] inverse = [
    [(matrix[1][1]*matrix[2][2]-matrix[1][2]*matrix[2][1])/determinant, (matrix[0][2]*matrix[2][1]-matrix[0][1]*matrix[2][2])/determinant, (matrix[0][1]*matrix[1][2]-matrix[0][2]*matrix[1][1])/determinant] as double[],
    [(matrix[1][2]*matrix[2][0]-matrix[1][0]*matrix[2][2])/determinant, (matrix[0][0]*matrix[2][2]-matrix[0][2]*matrix[2][0])/determinant, (matrix[0][2]*matrix[1][0]-matrix[0][0]*matrix[1][2])/determinant] as double[],
    [(matrix[1][0]*matrix[2][1]-matrix[1][1]*matrix[2][0])/determinant, (matrix[0][1]*matrix[2][0]-matrix[0][0]*matrix[2][1])/determinant, (matrix[0][0]*matrix[1][1]-matrix[0][1]*matrix[1][0])/determinant] as double[]
] as double[][]

// Four-neighbour cleanup for coarse overview masks. Components touching the
// frame are background when filling holes; small foreground islands are glass.
def cleanBinaryMask = { boolean[] mask, int width, int height, int minComponent, int maxHole ->
    int size = mask.length
    boolean[] visited = new boolean[size]
    int[] queue = new int[size]
    for (int seed = 0; seed < size; seed++) {
        if (!mask[seed] || visited[seed]) continue
        int head = 0, tail = 0
        queue[tail++] = seed; visited[seed] = true
        while (head < tail) {
            int p = queue[head++], x = p % width, y = (int)(p / width)
            int q
            if (x > 0 && mask[q=p-1] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (x+1 < width && mask[q=p+1] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (y > 0 && mask[q=p-width] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (y+1 < height && mask[q=p+width] && !visited[q]) { visited[q]=true; queue[tail++]=q }
        }
        if (tail < minComponent) for (int i=0; i<tail; i++) mask[queue[i]] = false
    }
    if (maxHole <= 0) return
    visited = new boolean[size]
    for (int seed = 0; seed < size; seed++) {
        if (mask[seed] || visited[seed]) continue
        int head = 0, tail = 0
        boolean touchesFrame = false
        queue[tail++] = seed; visited[seed] = true
        while (head < tail) {
            int p = queue[head++], x = p % width, y = (int)(p / width)
            if (x == 0 || y == 0 || x+1 == width || y+1 == height) touchesFrame = true
            int q
            if (x > 0 && !mask[q=p-1] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (x+1 < width && !mask[q=p+1] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (y > 0 && !mask[q=p-width] && !visited[q]) { visited[q]=true; queue[tail++]=q }
            if (y+1 < height && !mask[q=p+width] && !visited[q]) { visited[q]=true; queue[tail++]=q }
        }
        if (!touchesFrame && tail <= maxHole) for (int i=0; i<tail; i++) mask[queue[i]] = true
    }
}

def sectionSpecs = []
(study.samples as List).each { Map sample ->
    (study.analytical_series.allow_names as List).eachWithIndex { Object seriesNameObject, int position ->
        String seriesName = seriesNameObject.toString()
        sectionSpecs << [sample: sample, seriesName: seriesName,
            seriesIndex: ((Number)study.analytical_series.series_index_by_name[seriesName]).intValue(),
            sectionId: (sample.section_ids as List)[position].toString()]
    }
}
if (sectionSpecs.size() != ((Number)study.expected_analytical_sections).intValue())
    fail("Study section count does not match expected_analytical_sections")

def records = []
def reviewRows = []
sectionSpecs.eachWithIndex { Map spec, int ordinal ->
    Map sample = spec.sample as Map
    File slide = new File(study.source_root.toString(), sample.source_file.toString())
    if (!slide.isFile()) fail("Missing source slide: ${slide}")
    println "HE_R1_PROGRESS\t${ordinal + 1}/${sectionSpecs.size()}\t${spec.sectionId}"
    def server = ImageServers.buildServer(slide.toURI(), "--series", spec.seriesIndex.toString())
    try {
        double pixelWidth = server.pixelCalibration.getPixelWidthMicrons()
        double pixelHeight = server.pixelCalibration.getPixelHeightMicrons()
        if (!server.isRGB() || server.nChannels() != 3) fail("${spec.sectionId}: expected packed RGB")
        if (!Double.isFinite(pixelWidth) || pixelWidth < 0.27d || pixelWidth > 0.28d)
            fail("${spec.sectionId}: unexpected calibration ${pixelWidth}")
        BufferedImage source = server.readRegion(downsample, 0, 0, server.width, server.height)
        int width = source.width, height = source.height, size = width * height
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width)
        boolean[] tissue = new boolean[size]
        boolean[] redPen = new boolean[size]
        boolean[] dark = new boolean[size]
        boolean[] chromatic = new boolean[size]
        boolean[] fold = new boolean[size]
        double[] hValues = new double[size]
        double[] eValues = new double[size]
        double[] residualValues = new double[size]
        for (int i=0; i<size; i++) {
            int rgb = pixels[i], r=(rgb>>16)&255, g=(rgb>>8)&255, b=rgb&255
            double odR=-Math.log((r+1d)/(background[0]+1d))
            double odG=-Math.log((g+1d)/(background[1]+1d))
            double odB=-Math.log((b+1d)/(background[2]+1d))
            double odSum=odR+odG+odB
            hValues[i]=Math.max(0d, inverse[0][0]*odR+inverse[0][1]*odG+inverse[0][2]*odB)
            eValues[i]=Math.max(0d, inverse[1][0]*odR+inverse[1][1]*odG+inverse[1][2]*odB)
            residualValues[i]=Math.max(0d, inverse[2][0]*odR+inverse[2][1]*odG+inverse[2][2]*odB)
            tissue[i]=odSum > tissueOdThreshold && Math.min(r, Math.min(g,b)) < 248
            redPen[i]=r > 120 && r > g*1.35d && r > b*1.20d && r-Math.min(g,b) > 45
            dark[i]=tissue[i] && r+g+b < 90
            chromatic[i]=tissue[i] && g > r*1.18d && g > b*1.18d && g-Math.min(r,b) > 25
            fold[i]=tissue[i] && odSum > 3.0d && !dark[i]
        }
        cleanBinaryMask(tissue, width, height, minTissueComponentPixels, maxHolePixels)
        long detected=0, excluded=0, provisionalUsable=0, redCount=0, darkCount=0, chromaticCount=0, foldCount=0
        BufferedImage tissueMask = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        BufferedImage artifactMask = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        Graphics2D og = overlay.createGraphics(); og.drawImage(source, 0, 0, null); og.dispose()
        BufferedImage separation = new BufferedImage(width*3, height, BufferedImage.TYPE_BYTE_GRAY)
        for (int y=0; y<height; y++) for (int x=0; x<width; x++) {
            int i=y*width+x
            if (tissue[i]) detected++
            boolean autoExcluded=tissue[i] && redPen[i]
            if (autoExcluded) { excluded++; redCount++ }
            if (tissue[i] && !autoExcluded) provisionalUsable++
            if (tissue[i] && dark[i]) darkCount++
            if (tissue[i] && chromatic[i]) chromaticCount++
            if (tissue[i] && fold[i]) foldCount++
            tissueMask.setRGB(x,y,tissue[i] ? 0xffffff : 0x000000)
            int artifactColor=0x000000
            if (autoExcluded) artifactColor=0xff8800
            else if (tissue[i] && dark[i]) artifactColor=0xff00ff
            else if (tissue[i] && chromatic[i]) artifactColor=0xffff00
            else if (tissue[i] && fold[i]) artifactColor=0x8800ff
            artifactMask.setRGB(x,y,artifactColor)
            if (autoExcluded) overlay.setRGB(x,y,new Color(255,136,0,180).getRGB())
            else if (tissue[i] && dark[i]) overlay.setRGB(x,y,new Color(255,0,255,145).getRGB())
            else if (tissue[i] && chromatic[i]) overlay.setRGB(x,y,new Color(255,255,0,145).getRGB())
            else if (tissue[i] && fold[i]) overlay.setRGB(x,y,new Color(136,0,255,115).getRGB())
            else if (tissue[i]) overlay.setRGB(x,y,new Color(0,220,120,30).getRGB())
            int hGray=(int)Math.max(0,Math.min(255,Math.round(255d*Math.exp(-hValues[i]))))
            int eGray=(int)Math.max(0,Math.min(255,Math.round(255d*Math.exp(-eValues[i]))))
            int rGray=(int)Math.max(0,Math.min(255,Math.round(255d*Math.exp(-residualValues[i]))))
            int hg=(hGray<<16)|(hGray<<8)|hGray, eg=(eGray<<16)|(eGray<<8)|eGray, rg=(rGray<<16)|(rGray<<8)|rGray
            separation.setRGB(x,y,hg); separation.setRGB(width+x,y,eg); separation.setRGB(2*width+x,y,rg)
        }
        Graphics2D graphics=overlay.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        int banner=Math.max(42,(int)Math.round(height*0.045d))
        graphics.setColor(new Color(0,0,0,190)); graphics.fillRect(0,0,width,banner)
        graphics.setColor(Color.WHITE); graphics.setFont(new Font("SansSerif",Font.BOLD,(int)Math.max(13,banner/3)))
        graphics.drawString("${spec.sectionId} | GREEN tissue | ORANGE excluded pen | MAGENTA dark | YELLOW chromatic | PURPLE fold",12,banner*2/3)
        graphics.dispose()
        Graphics2D sg=separation.createGraphics()
        sg.setColor(Color.WHITE); sg.setFont(new Font("SansSerif",Font.BOLD,16))
        sg.drawString("HEMATOXYLIN",12,24); sg.drawString("EOSIN",width+12,24); sg.drawString("RESIDUAL",2*width+12,24); sg.dispose()

        String id=spec.sectionId
        File rawFile=new File(previewsDir,"${id}__raw.png")
        File stainFile=new File(stainsDir,"${id}__H_E_residual.png")
        File tissueFile=new File(masksDir,"${id}__tissue_mask.png")
        File artifactFile=new File(masksDir,"${id}__artifact_candidates.png")
        File overlayFile=new File(overlaysDir,"${id}__R1_qc.png")
        ImageIO.write(source,"PNG",rawFile); ImageIO.write(separation,"PNG",stainFile)
        ImageIO.write(tissueMask,"PNG",tissueFile); ImageIO.write(artifactMask,"PNG",artifactFile); ImageIO.write(overlay,"PNG",overlayFile)
        Map rec=[study_id:study.study_id,mouse_id:sample.mouse_id,biological_unit_id:sample.biological_unit_id,
            slide_id:sanitize(slide.name.substring(0,slide.name.length()-4)),section_id:id,series_name:spec.seriesName,
            series_index:spec.seriesIndex,source_file:slide.absolutePath,source_width_px:server.width,source_height_px:server.height,
            pixel_width_um:pixelWidth,pixel_height_um:pixelHeight,analysis_downsample:downsample,mask_width_px:width,mask_height_px:height,
            detected_tissue_pixels:detected,automatic_excluded_pixels:excluded,provisional_usable_tissue_pixels:provisionalUsable,
            tissue_fraction_of_frame:detected/(double)size,automatic_excluded_fraction_of_tissue:detected?excluded/(double)detected:Double.NaN,
            red_pen_candidate_pixels:redCount,dark_review_pixels:darkCount,chromatic_review_pixels:chromaticCount,fold_review_pixels:foldCount,
            denominator_reconciles:(detected==excluded+provisionalUsable),stain_profile_id:profile.profile_id,
            stain_profile_status:profile.status,review_status:"REVIEW_REQUIRED",run_classification:"R1_CANDIDATE_NOT_REPORTABLE",
            raw_preview:rawFile.name,stain_separation:stainFile.name,tissue_mask:tissueFile.name,artifact_mask:artifactFile.name,qc_overlay:overlayFile.name]
        records << rec
        [[kind:"dark_saturated_candidate",count:darkCount],[kind:"chromatic_outlier_candidate",count:chromaticCount],[kind:"fold_or_dense_material_candidate",count:foldCount]].each { item ->
            reviewRows << [study_id:study.study_id,blind_section_id:"HE-${String.format('%03d',ordinal+1)}",section_id:id,
                review_kind:item.kind,candidate_pixels:item.count,decision:"PENDING",reviewer_id:"",reviewed_utc:"",notes:""]
        }
    } finally { server.close() }
}

def writeCsv = { File file, List columns, List rows ->
    file.withWriter("UTF-8") { writer ->
        writer.println(columns.collect(csv).join(','))
        rows.each { row -> writer.println(columns.collect { csv(row[it]) }.join(',')) }
    }
}
def inventoryColumns=["study_id","mouse_id","biological_unit_id","slide_id","section_id","series_name","series_index","source_file","source_width_px","source_height_px","pixel_width_um","pixel_height_um"]
writeCsv(new File(tablesDir,"he_input_inventory.csv"),inventoryColumns,records)
def qcColumns=["study_id","mouse_id","biological_unit_id","slide_id","section_id","series_name","series_index","source_file","source_width_px","source_height_px","pixel_width_um","pixel_height_um","analysis_downsample","mask_width_px","mask_height_px","detected_tissue_pixels","automatic_excluded_pixels","provisional_usable_tissue_pixels","tissue_fraction_of_frame","automatic_excluded_fraction_of_tissue","red_pen_candidate_pixels","dark_review_pixels","chromatic_review_pixels","fold_review_pixels","denominator_reconciles","stain_profile_id","stain_profile_status","review_status","run_classification","raw_preview","stain_separation","tissue_mask","artifact_mask","qc_overlay"]
writeCsv(new File(tablesDir,"he_section_qc.csv"),qcColumns,records)
def reviewColumns=["study_id","blind_section_id","section_id","review_kind","candidate_pixels","decision","reviewer_id","reviewed_utc","notes"]
writeCsv(new File(tablesDir,"he_review_queue.csv"),reviewColumns,reviewRows)

boolean allReconcile=records.every { it.denominator_reconciles }
Map manifest=[schema_version:"1.0.0",module:"brightfield_he_r1_qc_candidate",status:"COMPLETE_REVIEW_REQUIRED",
    run_classification:"R1_CANDIDATE_NOT_REPORTABLE",release_level:"R0",requested_release:"R1",
    study_id:study.study_id,biological_unit:"mouse",mouse_count:records.collect{it.mouse_id}.unique().size(),section_count:records.size(),
    stain_profile_id:profile.profile_id,stain_profile_status:profile.status,stain_profile_sha256:sha256(profileFile),
    study_config_sha256:sha256(studyFile),downsample:downsample,tissue_od_sum_threshold:tissueOdThreshold,
    min_tissue_component_pixels:minTissueComponentPixels,max_hole_pixels:maxHolePixels,denominator_reconciliation_passed:allReconcile,
    automatic_exclusions:["red_pen_candidate"],review_only_candidates:["dark_saturated_candidate","chromatic_outlier_candidate","fold_or_dense_material_candidate"],
    unavailable_artifact_detectors:["tear","bubble","focus_blur","dust_object"],
    limitations:["Candidate stain profile has not been reviewed and locked.","Tissue and artifact masks have not been audited against blinded annotations.","Review-only candidates remain in the provisional usable-tissue denominator until reviewed.","No lesion, cell, ordinal, mouse-level, or group endpoint is emitted."],
    outputs:[inventory:"tables/he_input_inventory.csv",section_qc:"tables/he_section_qc.csv",review_queue:"tables/he_review_queue.csv",previews:"previews",stain_separation:"stain_separation",masks:"masks",overlays:"qc_overlays"]]
new File(outputRoot,"he_run_manifest.json").setText(gson.toJson(manifest),"UTF-8")
new File(outputRoot,"REVIEW_REQUIRED.txt").setText("R1 CANDIDATE — NOT A REPORTABLE ENDPOINT\r\nReview all stain separations, tissue masks and artifact overlays. Complete the blinded queue before a profile can be locked.\r\n","UTF-8")
println "HE_R1_COMPLETE\t${outputRoot.absolutePath}\tsections=${records.size()}\treconciles=${allReconcile}"
