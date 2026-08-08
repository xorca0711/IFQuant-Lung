import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import loci.formats.FormatTools;
import loci.formats.ImageReader;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v8 visual panels: MASK-DRIVEN, object-level, with an explicit outline layer.
 *
 * WHY v1-v7 WERE REPLACED
 *   All seven re-derived their own thresholds from raw pixel intensity and never
 *   opened the masks the engine had already written. That is a category error:
 *   "true-positive marked CELL" is an object-level property -- segmentation plus
 *   a per-marker decision -- and intensity windowing operates on pixels. A bright
 *   speck of debris and a genuinely positive cell are the same pixel value. Hence
 *   borders never came out crisp: an object property was being chased with a
 *   pixel tool.
 *
 *   v7 also carried a connected-component gate that DELETED image content
 *   (the ProSPC airway lining). Under Rossner & Yamada 2004 (J Cell Biol 166:11)
 *   that is selective manipulation of the image, categorically worse than any
 *   window or gamma change, and it is RETIRED here. The same goal is met
 *   honestly: outline the cells the pipeline called positive, and leave the
 *   image alone. An overlay is transparently an analysis result; a silently
 *   edited micrograph is not.
 *
 * THE THREE LAYERS, composited in this order so an outline can never be washed
 * out by an overlap:
 *
 *   1 SCAFFOLD   DAPI, raw intensity, ABSOLUTE window. Full weight, the
 *                brightest thing in the panel -- the standard cell-localisation
 *                reference. Absolute on purpose: a reference that moves per
 *                image is not a reference.
 *   2 FILL       For each marker, the ENGINE'S OWN mask, at reduced weight so
 *                markers sit under DAPI. Brightness inside the mask spans
 *                [minBright, 1] so a called-positive object can never render
 *                invisible -- priority 1 is "true positives appear clearly".
 *   3 OUTLINE    1..n px boundary of every positive object, drawn LAST at full
 *                saturation. Borders become GEOMETRY rather than contrast, so
 *                they hold at any brightness and through any overlap.
 *
 * IDENTITY comes from run_manifest.json (relative_path -> output_key), never
 * from parsing filenames. Olympus repeats field names across _Cycle folders --
 * M4-1 LEFT has G001_0001 five times -- and naming outputs by filename silently
 * overwrote 8 of 80 panels in an earlier version while the log still said 80.
 *
 * THIS RENDERS. IT DOES NOT MEASURE. No number here reaches run_summary.csv.
 *
 * CONFIG (CSV): marker,mode,mask_suffix,color,fill_weight,edge_px,low,high
 *   mode=scaffold  raw intensity, absolute low/high, no mask, no outline
 *   mode=mask      fill+outline the named mask; low/high blank = auto within mask
 *
 * USAGE
 *   java RenderPanels <inputRoot> <run_manifest.json> <analysisDir> <config.csv> <outDir>
 */
public class RenderPanels {

  static class Spec {
    String marker, mode, maskSuffix;
    int r, g, b;
    double fillWeight = 0.6, low = -1, high = -1;
    int edgePx = 0;
    /** A called-positive object never renders below this fraction of full. */
    static final double MIN_BRIGHT = 0.35;
    void setColor(String c) {
      c = c.trim().toLowerCase();
      if (c.equals("blue"))        { r = 40;  g = 90;  b = 255; }
      else if (c.equals("green"))  { r = 0;   g = 255; b = 40;  }
      else if (c.equals("red"))    { r = 255; g = 45;  b = 0;   }
      else if (c.equals("magenta")){ r = 255; g = 0;   b = 255; }
      else if (c.equals("cyan"))   { r = 0;   g = 230; b = 255; }
      else if (c.equals("yellow")) { r = 255; g = 225; b = 0;   }
      else                         { r = 255; g = 255; b = 255; }
    }
  }

  static final Map<String, String[]> PANEL_ORDER = new LinkedHashMap<String, String[]>();
  static {
    PANEL_ORDER.put("LEFT",  new String[] {"DAPI", "KRT5",   "AGER", "T1A"});
    PANEL_ORDER.put("RIGHT", new String[] {"DAPI", "ProSPC", "AGER", "KRT8"});
  }

  static int[] readPlane(ImageReader r, int c, int w, int h) throws Exception {
    byte[] buf = r.openBytes(r.getIndex(0, c, 0));
    int bpp = FormatTools.getBytesPerPixel(r.getPixelType());
    boolean little = r.isLittleEndian();
    int[] out = new int[w * h];
    for (int i = 0; i < out.length; i++) {
      if (bpp == 2) {
        int b0 = buf[i * 2] & 0xff, b1 = buf[i * 2 + 1] & 0xff;
        out[i] = little ? (b0 | (b1 << 8)) : ((b0 << 8) | b1);
      } else out[i] = buf[i] & 0xff;
    }
    return out;
  }

  /** Load an engine mask TIFF as a boolean foreground array, or null if absent. */
  static boolean[] readMask(File f, int w, int h) {
    if (f == null || !f.exists()) return null;
    try {
      BufferedImage bi = ImageIO.read(f);
      if (bi == null || bi.getWidth() != w || bi.getHeight() != h) return null;
      boolean[] m = new boolean[w * h];
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
          // > 0, NOT > 127. The engine writes TWO kinds of mask: <MARKER>_pod_mask /
          // *_membrane_positive_mask are uint8 0/255, but every tissue__*_nuclei_mask is a
          // uint16 LABEL image where each object carries its own integer id. A >127 test
          // renders only objects with id >= 128 -- and NOTHING at all when a field has
          // fewer than 128 objects (measured: AGER_morphology_positive max id = 11, so
          // frac>127 = 0.0000). It fails silently and the caption still reports a count.
          // Read the raw sample, not the LUT-mapped RGB:
          // Convert to Mask can leave an inverting LUT, which getRGB would honour
          // and silently invert the mask. Band 0 of the raster is the data.
          m[y * w + x] = bi.getRaster().getSample(x, y, 0) > 0;
      return m;
    } catch (Exception e) { return null; }
  }

  /** Find a mask file inside an output_key folder by its suffix (channel prefix varies). */
  static File findMask(File dir, String suffix) {
    File[] fs = dir.listFiles();
    if (fs == null) return null;
    for (File f : fs) if (f.getName().endsWith(suffix)) return f;
    return null;
  }

  /** 4-connected boundary of a mask, thickened inward by edgePx. */
  static boolean[] boundary(boolean[] m, int w, int h, int edgePx) {
    boolean[] e = new boolean[m.length];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int i = y * w + x;
        if (!m[i]) continue;
        boolean edge = (x == 0 || y == 0 || x == w - 1 || y == h - 1)
                    || !m[i - 1] || !m[i + 1] || !m[i - w] || !m[i + w];
        if (edge) e[i] = true;
      }
    }
    for (int pass = 1; pass < edgePx; pass++) {
      boolean[] grow = e.clone();
      for (int y = 1; y < h - 1; y++)
        for (int x = 1; x < w - 1; x++) {
          int i = y * w + x;
          if (!m[i] || e[i]) continue;
          if (e[i-1] || e[i+1] || e[i-w] || e[i+w]) grow[i] = true;
        }
      e = grow;
    }
    return e;
  }

  /** 8-connected component count, for the QC record. */
  static int countObjects(boolean[] m, int w, int h) {
    boolean[] seen = new boolean[m.length];
    int[] stack = new int[m.length];
    int n = 0;
    for (int s = 0; s < m.length; s++) {
      if (!m[s] || seen[s]) continue;
      n++; int sp = 0; stack[sp++] = s; seen[s] = true;
      while (sp > 0) {
        int cur = stack[--sp], cy = cur / w, cx = cur - cy * w;
        for (int dy = -1; dy <= 1; dy++) {
          int ny = cy + dy; if (ny < 0 || ny >= h) continue;
          for (int dx = -1; dx <= 1; dx++) {
            int nx = cx + dx; if (nx < 0 || nx >= w) continue;
            int ni = ny * w + nx;
            if (m[ni] && !seen[ni]) { seen[ni] = true; stack[sp++] = ni; }
          }
        }
      }
    }
    return n;
  }

  static int pctOf(int[] px, boolean[] mask, double p) {
    long[] h = new long[65536]; long n = 0;
    for (int i = 0; i < px.length; i++) if (mask == null || mask[i]) { h[px[i]]++; n++; }
    if (n == 0) return 0;
    long tgt = (long) Math.ceil(p * n), acc = 0;
    for (int v = 0; v < h.length; v++) { acc += h[v]; if (acc >= tgt) return v; }
    return 65535;
  }

  static List<Spec> readConfig(File f) throws Exception {
    List<Spec> out = new ArrayList<Spec>();
    for (String line : Files.readAllLines(f.toPath())) {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) continue;
      String[] p = s.split(",", -1);
      if (p[0].trim().equalsIgnoreCase("marker")) continue;
      Spec sp = new Spec();
      sp.marker = p[0].trim();
      sp.mode = p[1].trim().toLowerCase();
      sp.maskSuffix = p[2].trim();
      sp.setColor(p[3]);
      if (p.length > 4 && !p[4].trim().isEmpty()) sp.fillWeight = Double.parseDouble(p[4].trim());
      if (p.length > 5 && !p[5].trim().isEmpty()) sp.edgePx = Integer.parseInt(p[5].trim());
      if (p.length > 6 && !p[6].trim().isEmpty()) sp.low = Double.parseDouble(p[6].trim());
      if (p.length > 7 && !p[7].trim().isEmpty()) sp.high = Double.parseDouble(p[7].trim());
      if (!sp.mode.equals("scaffold") && !sp.mode.equals("mask"))
        throw new IllegalArgumentException("mode must be scaffold or mask: " + s);
      if (sp.mode.equals("mask") && sp.maskSuffix.isEmpty())
        throw new IllegalArgumentException("mode=mask needs a mask_suffix: " + s);
      out.add(sp);
    }
    return out;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 5) {
      System.out.println("usage: RenderPanels <inputRoot> <run_manifest.json> <analysisDir> <config.csv> <outDir>");
      return;
    }
    File root = new File(args[0]);
    JsonObject man = JsonParser.parseReader(new FileReader(args[1])).getAsJsonObject();
    File analysisDir = new File(args[2]);
    List<Spec> cfg = readConfig(new File(args[3]));
    File outDir = new File(args[4]); outDir.mkdirs();

    Map<String, Spec> byMarker = new LinkedHashMap<String, Spec>();
    for (Spec s : cfg) byMarker.put(s.marker, s);

    PrintWriter qc = new PrintWriter(new File(outDir, "panel_qc.csv"), "UTF-8");
    qc.println("output_key,panel,marker,mode,mask_file,mask_found,objects,mask_px,fill_weight,edge_px,fill_low,fill_high");

    JsonArray images = man.getAsJsonArray("images");
    int ok = 0, skip = 0;
    for (JsonElement el : images) {
      JsonObject im = el.getAsJsonObject();
      if (!"success".equals(im.get("status").getAsString())) { skip++; continue; }
      String rel = im.get("relative_path").getAsString();
      String key = im.get("output_key").getAsString();
      String panel = im.get("panel").getAsString();
      String[] order = PANEL_ORDER.get(panel);
      if (order == null) { skip++; continue; }

      File src = new File(root, rel);
      File keyDir = new File(analysisDir, key);
      if (!src.exists() || !keyDir.isDirectory()) {
        System.out.println("  MISSING inputs for " + key); skip++; continue;
      }

      ImageReader r = new ImageReader();
      IMetadata md = MetadataTools.createOMEXMLMetadata(); r.setMetadataStore(md);
      try {
        r.setId(src.getAbsolutePath()); r.setSeries(0);
        int w = r.getSizeX(), h = r.getSizeY(), nc = Math.min(order.length, r.getSizeC());
        double[] acc = new double[w * h * 3];
        StringBuilder cap = new StringBuilder();

        // ---- pass 1: scaffold + fills ----
        for (int c = 0; c < nc; c++) {
          Spec sp = byMarker.get(order[c]);
          if (sp == null) continue;
          int[] px = readPlane(r, c, w, h);

          boolean[] mask = null; File mf = null; int nObj = 0; long mpx = 0;
          if (sp.mode.equals("mask")) {
            mf = findMask(keyDir, sp.maskSuffix);
            mask = readMask(mf, w, h);
            if (mask == null) {
              // FAIL LOUDLY per marker rather than silently drawing raw pixels:
              // a panel that looks mask-driven but is not would misrepresent the
              // measurement.
              qc.println(key + "," + panel + "," + sp.marker + ",mask,\""
                         + (mf == null ? "NOT FOUND" : mf.getName()) + "\",false,0,0,,,,");
              System.out.println("  no mask for " + key + " / " + sp.marker + " -> marker omitted");
              cap.append("  ").append(sp.marker).append("=NO MASK");
              continue;
            }
            for (boolean v : mask) if (v) mpx++;
            nObj = countObjects(mask, w, h);
          }

          double lo = sp.low, hi = sp.high;
          if (lo < 0 || hi < 0) {                    // auto WITHIN the mask
            lo = pctOf(px, mask, 0.05);
            hi = pctOf(px, mask, 0.95);
            if (hi <= lo) hi = lo + 1;
          }

          for (int i = 0; i < px.length; i++) {
            if (mask != null && !mask[i]) continue;
            double t = (px[i] - lo) / (hi - lo);
            if (t < 0) t = 0; if (t > 1) t = 1;
            // inside a positive mask, never darker than MIN_BRIGHT
            double s = (mask == null) ? t : (Spec.MIN_BRIGHT + (1 - Spec.MIN_BRIGHT) * t);
            s *= sp.fillWeight;
            acc[i * 3]     += s * sp.r;
            acc[i * 3 + 1] += s * sp.g;
            acc[i * 3 + 2] += s * sp.b;
          }

          qc.println(key + "," + panel + "," + sp.marker + "," + sp.mode + ",\""
                     + (mf == null ? "" : mf.getName()) + "\"," + (sp.mode.equals("mask"))
                     + "," + nObj + "," + mpx + "," + sp.fillWeight + "," + sp.edgePx
                     + "," + (int) lo + "," + (int) hi);
          cap.append("  ").append(sp.marker).append(sp.mode.equals("scaffold")
              ? ("[" + (int) lo + "-" + (int) hi + "]")
              : ("=mask(" + nObj + " obj)"));
        }

        // ---- pass 2: outlines LAST, full saturation, so borders always survive ----
        for (int c = 0; c < nc; c++) {
          Spec sp = byMarker.get(order[c]);
          if (sp == null || !sp.mode.equals("mask") || sp.edgePx <= 0) continue;
          boolean[] mask = readMask(findMask(keyDir, sp.maskSuffix), w, h);
          if (mask == null) continue;
          boolean[] e = boundary(mask, w, h, sp.edgePx);
          for (int i = 0; i < e.length; i++) {
            if (!e[i]) continue;
            acc[i * 3] = sp.r; acc[i * 3 + 1] = sp.g; acc[i * 3 + 2] = sp.b;
          }
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[w * h];
        for (int i = 0; i < rgb.length; i++) {
          int rr = (int) Math.min(255, Math.round(acc[i * 3]));
          int gg = (int) Math.min(255, Math.round(acc[i * 3 + 1]));
          int bb = (int) Math.min(255, Math.round(acc[i * 3 + 2]));
          rgb[i] = (rr << 16) | (gg << 8) | bb;
        }
        img.setRGB(0, 0, w, h, rgb, 0, w);

        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRect(0, h - 60, w, 60);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 21));
        g2.drawString(key + "   [" + panel + "]", 12, h - 36);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g2.drawString("v8 mask-driven; outlines = engine positive calls; DAPI absolute."
                      + cap, 12, h - 13);
        g2.dispose();

        ImageIO.write(img, "png", new File(outDir, key + "__PANEL.png"));
        ok++;
        if (ok % 10 == 0) System.out.println("  " + ok + " rendered");
      } catch (Exception ex) {
        System.out.println("  FAILED " + key + ": " + ex.getMessage());
        skip++;
      } finally { r.close(); }
    }
    qc.close();
    System.out.println("DONE. rendered=" + ok + " skipped=" + skip + " -> " + outDir);
  }
}
