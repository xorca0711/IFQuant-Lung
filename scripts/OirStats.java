import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.FormatTools;
import java.io.File;
import java.util.*;

/** Per-channel intensity distribution WITHIN DAPI-defined tissue, pooled over
 *  every 20x field of one acquisition folder. Answers whether the confocal
 *  KRT5 channel has the autofluorescence floor the slide scanner had. */
public class OirStats {

  static int[] readPlane(ImageReader r, int c, int w, int h) throws Exception {
    byte[] buf = r.openBytes(r.getIndex(0, c, 0));
    int bpp = FormatTools.getBytesPerPixel(r.getPixelType());
    boolean little = r.isLittleEndian();
    int n = w * h; int[] out = new int[n];
    for (int i = 0; i < n; i++) {
      if (bpp == 2) { int b0 = buf[i*2] & 0xff, b1 = buf[i*2+1] & 0xff;
                      out[i] = little ? (b0 | (b1 << 8)) : ((b0 << 8) | b1); }
      else out[i] = buf[i] & 0xff;
    }
    return out;
  }
  static int otsu(int[] hist, long total) {
    double sum = 0; for (int i = 0; i < hist.length; i++) sum += (double) i * hist[i];
    double sumB = 0, wB = 0, best = -1; int thr = 0;
    for (int t = 0; t < hist.length; t++) {
      wB += hist[t]; if (wB == 0) continue;
      double wF = total - wB; if (wF == 0) break;
      sumB += (double) t * hist[t];
      double mB = sumB/wB, mF = (sum-sumB)/wF, b = wB*wF*(mB-mF)*(mB-mF);
      if (b > best) { best = b; thr = t; }
    }
    return thr;
  }
  static int pct(long[] hist, long n, double p) {
    long tgt = (long) Math.ceil(p*n), acc = 0;
    for (int v = 0; v < hist.length; v++) { acc += hist[v]; if (acc >= tgt) return v; }
    return hist.length-1;
  }

  public static void main(String[] args) throws Exception {
    File dir = new File(args[0]);
    String label = args.length > 1 ? args[1] : dir.getName();
    File[] fs = dir.listFiles((d,n) -> n.toLowerCase().endsWith(".oir") && !n.startsWith("Map_A"));
    if (fs == null || fs.length == 0) { System.out.println("no fields in " + dir); return; }
    Arrays.sort(fs);

    int NC = 4;
    long[][] hist = new long[NC][65536];
    long tissuePx = 0, totalPx = 0;
    int nFields = 0;

    for (File f : fs) {
      ImageReader r = new ImageReader();
      IMetadata m = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(m);
      try {
        r.setId(f.getAbsolutePath());
        r.setSeries(0);
        int w = r.getSizeX(), h = r.getSizeY(), nc = Math.min(NC, r.getSizeC()), n = w*h;
        if (r.getSizeZ() != 1) { System.out.println("  skip (Z>1): " + f.getName()); r.close(); continue; }
        int[] dapi = readPlane(r, 0, w, h);
        int[] dh = new int[65536]; for (int v : dapi) dh[v]++;
        int tThr = otsu(dh, n);
        boolean[] tis = new boolean[n];
        for (int i = 0; i < n; i++) { tis[i] = dapi[i] > tThr; if (tis[i]) tissuePx++; }
        totalPx += n;
        for (int c = 0; c < nc; c++) {
          int[] px = (c == 0) ? dapi : readPlane(r, c, w, h);
          for (int i = 0; i < n; i++) if (tis[i]) hist[c][px[i]]++;
        }
        nFields++;
      } catch (Exception e) {
        System.out.println("  FAILED " + f.getName() + ": " + e.getMessage());
      } finally { r.close(); }
    }

    System.out.printf("%-44s fields=%d  tissue=%.1f%%%n", label, nFields, 100.0*tissuePx/Math.max(1,totalPx));
    if (nFields == 0) return;
    System.out.printf("   %-6s %7s %7s %7s %7s %7s %7s %9s%n","ch","p50","p90","p99","p99.9","p99.99","max","frac>500");
    String[] nm = {"DAPI","488","555","647"};
    for (int c = 0; c < NC; c++) {
      long tot = 0; for (long v : hist[c]) tot += v;
      if (tot == 0) continue;
      int mx = 0; for (int v = 65535; v >= 0; v--) if (hist[c][v] > 0) { mx = v; break; }
      long over = 0; for (int v = 501; v < 65536; v++) over += hist[c][v];
      System.out.printf("   %-6s %7d %7d %7d %7d %7d %7d %9.5f%n", nm[c],
          pct(hist[c],tot,.50), pct(hist[c],tot,.90), pct(hist[c],tot,.99),
          pct(hist[c],tot,.999), pct(hist[c],tot,.9999), mx, (double) over/tot);
    }
    System.out.println();
  }
}
