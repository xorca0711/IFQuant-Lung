import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.FormatTools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Is the DAPI channel adequate to count nuclei?
 *
 * The batch statistics say in-tissue DAPI has p90 = 4095 and frac>500 = 1.000,
 * i.e. it is saturated. That is a claim about a histogram. This renders the
 * evidence so it can be judged by eye, three ways side by side:
 *
 *   A  RAW, true full range [0, 4095]. What the detector actually recorded.
 *      If nuclei are a flat white sheet here, they cannot be separated by
 *      intensity, and no thresholding parameter can recover boundaries that
 *      were never digitised.
 *   B  CLIPPED PIXELS IN RED (value >= 4095) over the raw grey. This is the
 *      direct measure: red area is information destroyed at acquisition.
 *   C  LOW WINDOW [0, 1200]. What structure survives in the un-saturated part
 *      of the range -- i.e. whether a display or threshold workaround has
 *      anything left to work with.
 *
 * Percent saturated is computed over TISSUE (Otsu on DAPI), not the whole
 * frame, because background is empty and would dilute the number.
 *
 * usage: java DapiQc <out.png> <file1.oir> [file2.oir ...]
 */
public class DapiQc {

  static int[] readPlane(ImageReader r, int c, int w, int h) throws Exception {
    byte[] buf = r.openBytes(r.getIndex(0, c, 0));
    int bpp = FormatTools.getBytesPerPixel(r.getPixelType());
    boolean little = r.isLittleEndian();
    int n = w * h;
    int[] out = new int[n];
    for (int i = 0; i < n; i++) {
      if (bpp == 2) {
        int b0 = buf[i * 2] & 0xff, b1 = buf[i * 2 + 1] & 0xff;
        out[i] = little ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
      } else out[i] = buf[i] & 0xff;
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
      double mB = sumB / wB, mF = (sum - sumB) / wF, b = wB * wF * (mB - mF) * (mB - mF);
      if (b > best) { best = b; thr = t; }
    }
    return thr;
  }

  static int g(int v, double lo, double hi) {
    double t = (v - lo) / (hi - lo);
    if (t <= 0) return 0; if (t >= 1) return 255;
    return (int) Math.round(t * 255);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) { System.out.println("usage: DapiQc <out.png> <file.oir>..."); return; }
    File out = new File(args[0]);
    int ds = 2;                                 // downsample for a viewable sheet
    int rows = args.length - 1;
    int tileW = 2048 / ds, tileH = 2048 / ds, cap = 46;
    BufferedImage sheet = null;
    Graphics2D sg = null;

    for (int k = 1; k < args.length; k++) {
      File f = new File(args[k]);
      ImageReader r = new ImageReader();
      IMetadata md = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(md);
      try {
        r.setId(f.getAbsolutePath()); r.setSeries(0);
        int w = r.getSizeX(), h = r.getSizeY();
        int[] px = readPlane(r, 0, w, h);

        int[] hist = new int[65536];
        for (int v : px) hist[v]++;
        int tThr = otsu(hist, (long) w * h);
        long tissue = 0, sat = 0;
        for (int v : px) { }                       // (kept explicit below)
        for (int i = 0; i < px.length; i++) {
          if (px[i] > tThr) { tissue++; if (px[i] >= 4095) sat++; }
        }
        double pctSat = 100.0 * sat / Math.max(1, tissue);

        int tw = w / ds, th = h / ds;
        if (sheet == null) {
          sheet = new BufferedImage(tw * 3, (th + cap) * rows, BufferedImage.TYPE_INT_RGB);
          sg = sheet.createGraphics();
          sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                              RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
          tileW = tw; tileH = th;
        }
        int y0 = (k - 1) * (tileH + cap);

        for (int y = 0; y < th; y++) {
          for (int x = 0; x < tw; x++) {
            int v = px[(y * ds) * w + (x * ds)];
            int a = g(v, 0, 4095);
            sheet.setRGB(x, y0 + y, (a << 16) | (a << 8) | a);
            int b = (v >= 4095) ? 0xFF2000 : ((a << 16) | (a << 8) | a);
            sheet.setRGB(tw + x, y0 + y, b);
            int c = g(v, 0, 1200);
            sheet.setRGB(2 * tw + x, y0 + y, (c << 16) | (c << 8) | c);
          }
        }
        sg.setColor(Color.BLACK);
        sg.fillRect(0, y0 + tileH, tw * 3, cap);
        sg.setColor(Color.WHITE);
        sg.setFont(new Font("SansSerif", Font.BOLD, 17));
        sg.drawString(f.getName().replace(".oir", ""), 8, y0 + tileH + 19);
        sg.setFont(new Font("SansSerif", Font.PLAIN, 15));
        sg.drawString(String.format(
            "A raw [0-4095]      |      B clipped pixels in RED: %.1f%% of tissue saturated"
            + "      |      C low window [0-1200]", pctSat), 8, y0 + tileH + 39);
        System.out.printf("%-58s tissue_saturated=%.1f%%%n",
                          f.getName().replace(".oir", ""), pctSat);
      } catch (Exception e) {
        System.out.println("FAILED " + f.getName() + ": " + e.getMessage());
      } finally { r.close(); }
    }
    if (sg != null) sg.dispose();
    if (sheet != null) { ImageIO.write(sheet, "png", out); System.out.println("wrote " + out); }
  }
}
