// Measure the cost of the draft's dynamic-Groovy ROI/label upsample loop
// (qupath_lung_morphometry.groovy lines 1379-1389) against the @CompileStatic
// replacement, on one block-sized array.
@groovy.transform.CompileStatic
class Fast {
  static void upsample(byte[] roiC, byte[] labC, int cw, int ch, int gx0, int gy0,
                       int k, int w, int h, byte[] roiB, byte[] labB) {
    for (int y = 0; y < h; y++) {
      int gy = gy0 + Math.floorDiv(y, k)
      if (gy < 0 || gy >= ch) continue
      int grow = gy * cw, row = y * w
      for (int x = 0; x < w; x++) {
        int gx = gx0 + Math.floorDiv(x, k)
        if (gx < 0 || gx >= cw) continue
        int gi = grow + gx
        roiB[row + x] = roiC[gi]
        labB[row + x] = labC[gi]
      }
    }
  }
}

int K = 4
int bw = 2560, bh = 2560              // the draft's block+halo at ds 2
int cw = 7146, ch = 5269              // this dataset's coarse grid
int ex = 4000, ey = 4000              // fine-pixel offsets, as the draft uses them
byte[] roiC = new byte[cw * ch]; byte[] labC = new byte[cw * ch]
java.util.Arrays.fill(roiC, (byte) 255); java.util.Arrays.fill(labC, (byte) 1)
byte[] roiB = new byte[bw * bh]; byte[] labB = new byte[bw * bh]

// ---- the draft, verbatim (dynamic Groovy, '/' on ints -> BigDecimal) ----
long t0 = System.currentTimeMillis()
for (int y = 0; y < bh; y++) {
  int gy = (ey + y) / K
  if (gy >= ch) continue
  for (int x = 0; x < bw; x++) {
    int gx = (ex + x) / K
    if (gx >= cw) continue
    int gi = gy * cw + gx
    roiB[y * bw + x] = roiC[gi]
    labB[y * bw + x] = labC[gi]
  }
}
long tDyn = System.currentTimeMillis() - t0

// ---- the replacement ----
long t1 = System.currentTimeMillis()
Fast.upsample(roiC, labC, cw, ch, ex.intdiv(K), ey.intdiv(K), K, bw, bh, roiB, labB)
long tSta = System.currentTimeMillis() - t1
// second call, warm
long t2 = System.currentTimeMillis()
Fast.upsample(roiC, labC, cw, ch, ex.intdiv(K), ey.intdiv(K), K, bw, bh, roiB, labB)
long tSta2 = System.currentTimeMillis() - t2

println "[BENCH] block ${bw}x${bh} = ${(bw*bh/1e6).round(1)} Mpx"
println "[BENCH] draft, dynamic Groovy  : ${tDyn} ms"
println "[BENCH] @CompileStatic, cold    : ${tSta} ms"
println "[BENCH] @CompileStatic, warm    : ${tSta2} ms"
println "[BENCH] speedup (warm)          : ${tDyn / Math.max(1L, tSta2)}x"
println "[BENCH] draft cost for 60 blocks x 4 slides = ${(tDyn * 60 * 4 / 1000.0).round(0)} s of pure index arithmetic"
