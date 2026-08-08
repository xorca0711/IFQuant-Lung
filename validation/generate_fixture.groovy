// generate_fixture.groovy -- synthetic DAPI-like validation field for IFQuant-Lung.
//
// Produces a deterministic 512x512 16-bit image with clearly separated
// Gaussian-blob "nuclei" (~5-6 um apparent equivalent diameter at the declared
// 0.31 um/px calibration), mild Gaussian noise and a dim background:
//
//   - 196 interior blobs on a jittered 14x14 grid (none within ~35 px of the frame)
//   - 12 blobs centred ON the image frame, so they touch the border after
//     thresholding. These exist because the blackBackground bug's signature is
//     "only border-connected components survive" -- without them world B of the
//     demo would trivially count 0 and the mechanism would be less visible.
//
// Fixed seed => identical pixels and identical ground-truth counts on every run.
// No absolute paths: output directory comes from IFQ_VALIDATION_OUT (set by
// run_demo.ps1) or defaults to ./out relative to the working directory.
//
// Outputs (both under the out dir):
//   fixture_dapi.tif    the synthetic field (16-bit, calibrated 0.31 um/px)
//   fixture_truth.txt   ground-truth blob counts (key=value)
//
// Run headless:  net.imagej.Main --headless --run generate_fixture.groovy
// (see run_demo.ps1 for the full JVM invocation)

import ij.ImagePlus
import ij.io.FileSaver
import ij.measure.Calibration
import ij.process.ShortProcessor

import java.util.Random

// ---------------------------------------------------------------- parameters
final int    W     = 512
final int    H     = 512
final double PX_UM = 0.31          // um per pixel, matches the confocal 20x 2k fields
final long   SEED  = 20260808L     // fixed: fixture must be bit-identical across runs

final double BASELINE    = 300.0d  // dim background offset (counts)
final double NOISE_SIGMA = 40.0d   // mild Gaussian read-noise

final int    CELL   = 32           // grid cell for interior blob placement (px)
final double JITTER = 8.0d         // total jitter range per axis (+/- 4 px)

// Blob shape: sigma 4.2-4.8 px. The thresholded object comes out at roughly
// 2*sigma radius, i.e. ~17-19 px diameter = ~5.3-5.9 um equivalent diameter.
final double SIGMA_MIN = 4.2d
final double SIGMA_SPAN = 0.6d
final double AMP_MIN = 9000.0d
final double AMP_SPAN = 3000.0d

// ------------------------------------------------------------------- output
def outDirPath = System.getenv("IFQ_VALIDATION_OUT")
if (outDirPath == null || outDirPath.trim().isEmpty()) {
  outDirPath = new File("out").getAbsolutePath()
}
def outDir = new File(outDirPath)
if (!outDir.isDirectory() && !outDir.mkdirs()) {
  System.err.println("FATAL: cannot create output directory: " + outDir)
  if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(1)
}

// ------------------------------------------------------------------ synthesis
Random rnd = new Random(SEED)

// Background + noise first, in fixed row-major order (determinism depends on
// a fixed draw order from the single Random stream).
double[] img = new double[W * H]
for (int i = 0; i < W * H; i++) {
  img[i] = BASELINE + NOISE_SIGMA * rnd.nextGaussian()
}

// Blob centres. Interior: jittered grid over cells 1..14 (centres >= ~44 px
// from the frame, so no interior blob can touch the border).
def centres = []   // each: [cx, cy, isBorder]
for (int gy = 1; gy <= 14; gy++) {
  for (int gx = 1; gx <= 14; gx++) {
    double cx = gx * CELL + CELL / 2 + (rnd.nextDouble() - 0.5d) * JITTER
    double cy = gy * CELL + CELL / 2 + (rnd.nextDouble() - 0.5d) * JITTER
    centres << [cx, cy, false]
  }
}
// Border blobs: centres exactly on the frame (half-blobs, guaranteed to touch
// the image edge in the binary mask).
[[64, 0], [192, 0], [320, 0], [448, 0],
 [64, H - 1], [192, H - 1], [320, H - 1], [448, H - 1],
 [0, 128], [0, 384], [W - 1, 128], [W - 1, 384]].each { c ->
  centres << [(double) c[0], (double) c[1], true]
}

int interiorCount = centres.count { !it[2] }
int borderCount   = centres.count { it[2] }

// Render each blob as an additive 2D Gaussian.
centres.each { c ->
  double cx = c[0], cy = c[1]
  double sigma = SIGMA_MIN + rnd.nextDouble() * SIGMA_SPAN
  double amp   = AMP_MIN + rnd.nextDouble() * AMP_SPAN
  int r = (int) Math.ceil(3.5d * sigma)
  int x0 = Math.max(0, (int) Math.floor(cx) - r), x1 = Math.min(W - 1, (int) Math.ceil(cx) + r)
  int y0 = Math.max(0, (int) Math.floor(cy) - r), y1 = Math.min(H - 1, (int) Math.ceil(cy) + r)
  double twoSigma2 = 2.0d * sigma * sigma
  for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
      double dx = x - cx, dy = y - cy
      img[y * W + x] += amp * Math.exp(-(dx * dx + dy * dy) / twoSigma2)
    }
  }
}

// Clamp to 16-bit.
short[] pix = new short[W * H]
for (int i = 0; i < W * H; i++) {
  long v = Math.round(img[i])
  if (v < 0L) v = 0L
  if (v > 65535L) v = 65535L
  pix[i] = (short) (v & 0xFFFFL)
}

def imp = new ImagePlus("fixture_dapi", new ShortProcessor(W, H, pix, null))
def cal = new Calibration()
cal.pixelWidth = PX_UM
cal.pixelHeight = PX_UM
cal.setUnit("micron")
imp.setCalibration(cal)

def tifPath = new File(outDir, "fixture_dapi.tif").getAbsolutePath()
if (!new FileSaver(imp).saveAsTiff(tifPath)) {
  System.err.println("FATAL: could not save " + tifPath)
  if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(1)
}

def truthText = "seed=" + SEED + "\n" +
                "width_px=" + W + "\nheight_px=" + H + "\n" +
                "pixel_size_um=" + PX_UM + "\n" +
                "interior_blobs=" + interiorCount + "\n" +
                "border_blobs=" + borderCount + "\n" +
                "total_blobs=" + (interiorCount + borderCount) + "\n"
new File(outDir, "fixture_truth.txt").setText(truthText, "UTF-8")

println "FIXTURE: wrote " + tifPath
println "FIXTURE: " + W + "x" + H + " 16-bit, " + PX_UM + " um/px, seed=" + SEED
println "FIXTURE: interior blobs=" + interiorCount + " border blobs=" + borderCount +
        " total=" + (interiorCount + borderCount)

if (java.awt.GraphicsEnvironment.isHeadless()) System.exit(0)
