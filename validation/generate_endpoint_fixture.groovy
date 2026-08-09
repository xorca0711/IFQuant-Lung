// Deterministic 4x2 mask fixture for evaluate_endpoints.groovy.
// Expected algebra inside the all-pixel tissue region:
//   KRT5 = {0,1,2,3}; PDPN = {0,1,4,5}
//   numerator   KRT5 AND PDPN       = 2 pixels
//   denominator NOT PDPN OR KRT5    = 6 pixels
//   fraction                         = 1/3

import ij.IJ
import ij.ImagePlus
import ij.process.ByteProcessor
import groovy.json.JsonOutput

def rootPath = System.getenv("IFQ_ENDPOINT_FIXTURE_ROOT")
if (rootPath == null || rootPath.trim().isEmpty())
  throw new IllegalArgumentException("IFQ_ENDPOINT_FIXTURE_ROOT is required")

def root = new File(rootPath)
def analysis = new File(root, "analysis")
def keyDir = new File(analysis, "synthetic_key")
def tissueDir = new File(root, "tissue_masks/synthetic_key")
keyDir.mkdirs(); tissueDir.mkdirs()

def writeMask = { File path, Set<Integer> onPixels ->
  def ip = new ByteProcessor(4, 2)
  onPixels.each { ip.set(it, 255) }
  def imp = new ImagePlus(path.name, ip)
  imp.getCalibration().pixelWidth = 1.0d
  imp.getCalibration().pixelHeight = 1.0d
  imp.getCalibration().setUnit("um")
  IJ.saveAsTiff(imp, path.getAbsolutePath())
  imp.close()
}

writeMask(new File(keyDir, "synthetic__KRT5_pod_mask.tif"), [0,1,2,3] as Set)
writeMask(new File(keyDir, "synthetic__T1A_membrane_positive_mask.tif"), [0,1,4,5] as Set)
writeMask(new File(tissueDir, "synthetic__tissue__tissue_region_mask.tif"),
          [0,1,2,3,4,5,6,7] as Set)

new File(analysis, "run_summary.csv").setText(
  "image,region,output_key,region_area_um2,panel\n" +
  "synthetic,tissue,synthetic_key,8,LEFT\n", "UTF-8")

def spec = [
  schema_version: "2.0.0", endpoint_id: "synthetic_union", panel: "LEFT",
  numerator: [op: "AND", terms: [
    [mask: "KRT5_pod_mask", negate: false],
    [mask: "T1A_membrane_positive_mask", negate: false]
  ]],
  denominator: [op: "OR", terms: [
    [mask: "T1A_membrane_positive_mask", negate: true],
    [mask: "KRT5_pod_mask", negate: false]
  ]],
  output: [
    area_column: "synthetic_pod_area_um2",
    denominator_area_column: "synthetic_denominator_area_um2",
    fraction_column: "synthetic_fraction"
  ],
  parameters: [
    krt5_threshold: [value: 300, status: "FIXED_TEST"],
    t1a_threshold: [value: 200, status: "FIXED_TEST"]
  ],
  validation_status: "SYNTHETIC_TEST"
]
new File(root, "spec.json").setText(JsonOutput.prettyPrint(JsonOutput.toJson(spec)), "UTF-8")
println "ENDPOINT_FIXTURE: wrote " + root.getAbsolutePath()

if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(0)
