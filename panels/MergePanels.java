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
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual merge panels with FIXED, ABSOLUTE display windows.
 *
 * WHY THIS EXISTS RATHER THAN THE ENGINE'S OWN PREVIEW
 *   IF_Quant_Pipeline.groovy renders its display channels from PERCENTILES of
 *   each image. That is fine for a per-image QC glance and wrong for three
 *   things we need here:
 *
 *   1. It re-normalises every field independently, so a normal-alveoli field
 *      and a lesion field are stretched differently and cannot be compared.
 *      For a figure spanning four mice that is actively misleading.
 *   2. "Strict KRT8, so it does not show on normal alveolar regions" is not
 *      expressible as a percentile: the top 2% of a normal field is still the
 *      top 2%, so normal alveoli keep lighting up.
 *   3. "Keep mRAGE intensity so weak AT1 membranes stay visible" needs a
 *      ceiling chosen from the WEAK sample, not from each image's own maximum.
 *
 *   So every marker gets one absolute window [low, high] plus a gamma, applied
 *   identically to every image in the batch. The window is burned into the
 *   panel caption, because a display range that is not stated is a figure that
 *   cannot be trusted.
 *
 * THIS RENDERS. IT DOES NOT MEASURE.
 *   No number produced here reaches run_summary.csv. Gamma and windowing are
 *   presentation only. Measurement remains the frozen engine's job.
 *
 * CONFIG (CSV, no JSON dependency):
 *   marker,low,high,gamma,color
 *   AGER,0,1200,0.75,red
 *   gamma < 1 lifts dim structure; it is monotone, so intensity ORDER is
 *   preserved and nothing is clipped. It is still a non-linear transform and
 *   is declared on the panel.
 *
 * USAGE
 *   java MergePanels <inputRoot> <panelMap.csv> <displayConfig.csv> <outDir> [includeRegex]
 */
public class MergePanels {

  static class Win {
    double low, high, gamma;
    int r, g, b;

    /**
     * "abs": low/high are raw detector units.
     * "rel": low/high are FRACTIONS of this section's own dynamic range,
     *        z = (I - B) / (S - B) with B = in-tissue p10 and S = in-tissue
     *        p99.5. Corrects per-section staining efficiency, which one
     *        absolute window cannot: measured AGER frac>500 is 0.0097 in
     *        M6 LEFT and 0.289 in M6 RIGHT -- same antibody, same animal.
     */
    String mode = "abs";
    /**
     * mode=auto only. An ABSOLUTE floor the per-image cut may never go below,
     * in detector units. This is how a marker keeps a validity guarantee while
     * still being tuned per image: KRT5 uses 300, the locked measurement
     * threshold, so a rendered pod is always a counted pod; KRT8 uses the
     * control-derived 700 so a per-image Otsu can never start lighting up
     * normal alveolar epithelium. <=0 means no guard.
     */
    double absFloor = 0;
    Win(double lo, double hi, double gm, String color) {
      low = lo; high = hi; gamma = gm;
      String c = color.trim().toLowerCase();
      if (c.equals("blue"))       { r = 0;   g = 80;  b = 255; }
      else if (c.equals("green")) { r = 0;   g = 255; b = 0;   }
      else if (c.equals("red"))   { r = 255; g = 40;  b = 0;   }
      else if (c.equals("magenta")){r = 255; g = 0;   b = 255; }
      else if (c.equals("cyan"))  { r = 0;   g = 255; b = 255; }
      else if (c.equals("yellow")){ r = 255; g = 220; b = 0;   }
      else                        { r = 255; g = 255; b = 255; } // white/gray
    }
  }

  /** Built-in acquisition channel order, mirroring the engine's LEFT/RIGHT panels. */
  static final Map<String, String[]> PANEL_ORDER = new HashMap<String, String[]>();
  static {
    PANEL_ORDER.put("LEFT",  new String[] {"DAPI", "KRT5",   "AGER", "T1A"});
    PANEL_ORDER.put("RIGHT", new String[] {"DAPI", "ProSPC", "AGER", "KRT8"});
  }

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
      } else {
        out[i] = buf[i] & 0xff;
      }
    }
    return out;
  }

  /** Absolute window + gamma -> 0..1. Values below low go to 0, above high to 1. */
  static double scale(int v, Win w) {
    if (w.high <= w.low) return 0.0;
    double t = (v - w.low) / (w.high - w.low);
    if (t <= 0.0) return 0.0;
    if (t >= 1.0) return 1.0;
    return (w.gamma == 1.0) ? t : Math.pow(t, w.gamma);
  }

  static int pct(long[] hist, long n, double p) {
    long tgt = (long) Math.ceil(p * n), acc = 0;
    for (int v = 0; v < hist.length; v++) { acc += hist[v]; if (acc >= tgt) return v; }
    return hist.length - 1;
  }

  static int otsu(long[] hist, long total) {
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

  static Map<String, Win> readConfig(File f) throws Exception {
    Map<String, Win> m = new LinkedHashMap<String, Win>();
    for (String line : Files.readAllLines(f.toPath())) {
      String s = line.trim();
      if (s.isEmpty() || s.startsWith("#")) continue;
      String[] p = s.split(",");
      if (p[0].trim().equalsIgnoreCase("marker")) continue;   // header
      if (p.length < 5) throw new IllegalArgumentException("bad config line: " + line);
      Win win = new Win(Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim()),
                        Double.parseDouble(p[3].trim()), p[4]);
      // RETIRED: the connected-component area gate. It deleted image content --
      // the ProSPC airway lining -- from a panel presented as a micrograph. Under
      // Rossner & Yamada 2004 (J Cell Biol 166:11) that is selective manipulation
      // of the image, a different and more serious category than any window or
      // gamma change. Suppressing a population now belongs to the QC OVERLAY,
      // which is transparently an analysis result, not to the merge panel.
      // An old config must FAIL here rather than silently render differently.
      if ((p.length >= 6 && !p[5].trim().isEmpty()) || (p.length >= 7 && !p[6].trim().isEmpty()))
        throw new IllegalArgumentException(
          "minAreaUm2/maxAreaUm2 are RETIRED and must be empty: '" + line + "'. " +
          "A merge panel may not delete image content. Use panels/qc/ for object filtering.");
      if (p.length >= 8 && !p[7].trim().isEmpty()) {
        String md = p[7].trim().toLowerCase();
        if (!md.equals("abs") && !md.equals("rel") && !md.equals("auto"))
          throw new IllegalArgumentException("mode must be abs, rel or auto, got '" + md + "'");
        win.mode = md;
      }
      if (p.length >= 9 && !p[8].trim().isEmpty()) win.absFloor = Double.parseDouble(p[8].trim());
      m.put(p[0].trim(), win);
    }
    return m;
  }

  static Map<String, String> readPanelMap(File f) throws Exception {
    Map<String, String> m = new HashMap<String, String>();
    for (String line : Files.readAllLines(f.toPath())) {
      String s = line.trim();
      if (s.isEmpty()) continue;
      int i = s.lastIndexOf(',');
      if (i < 0) continue;
      String rel = s.substring(0, i).trim();
      String panel = s.substring(i + 1).trim();
      if (rel.equalsIgnoreCase("relative_path")) continue;
      if (rel.startsWith("\"") && rel.endsWith("\"")) rel = rel.substring(1, rel.length() - 1);
      m.put(rel.replace('\\', '/').toLowerCase(), panel);
    }
    return m;
  }

  static void collect(File dir, List<File> out, String regex) {
    File[] fs = dir.listFiles();
    if (fs == null) return;
    Arrays.sort(fs);
    for (File f : fs) {
      if (f.isDirectory()) collect(f, out, regex);
      else if (f.getName().toLowerCase().endsWith(".oir")
               && !f.getName().startsWith("Map_A")
               && (regex == null || f.getAbsolutePath().replace('\\', '/').matches(regex)))
        out.add(f);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.out.println("usage: MergePanels <inputRoot> <panelMap.csv> <displayConfig.csv> <outDir> [includeRegex]");
      return;
    }
    File root = new File(args[0]);
    Map<String, String> panelMap = readPanelMap(new File(args[1]));
    Map<String, Win> cfg = readConfig(new File(args[2]));
    File outDir = new File(args[3]);
    outDir.mkdirs();
    String regex = args.length > 4 ? args[4] : null;

    List<File> files = new ArrayList<File>();
    collect(root, files, regex);
    System.out.println("fields found: " + files.size());

    PrintWriter man = new PrintWriter(new File(outDir, "merge_panel_manifest.csv"), "UTF-8");
    man.println("file,panel,status,note");
    PrintWriter qc = new PrintWriter(new File(outDir, "display_window_qc.csv"), "UTF-8");
    qc.println("file,panel,marker,mode,section_p10,section_p995,resolved_low,resolved_high,tissue_px");

    int ok = 0, fail = 0;
    for (File f : files) {
      String rel = f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1)
                    .replace('\\', '/').toLowerCase();
      String panel = panelMap.get(rel);
      if (panel == null) {
        // Fail closed. A field whose panel is unknown has an unknown channel
        // order, so colouring it would invent a marker assignment.
        System.out.println("  NO PANEL, skipped: " + f.getName());
        man.println("\"" + f.getName() + "\",,skipped,no panel-map row");
        fail++;
        continue;
      }
      String[] order = PANEL_ORDER.get(panel);
      if (order == null) {
        man.println("\"" + f.getName() + "\"," + panel + ",skipped,unknown panel key");
        fail++;
        continue;
      }

      ImageReader r = new ImageReader();
      IMetadata md = MetadataTools.createOMEXMLMetadata();
      r.setMetadataStore(md);
      try {
        r.setId(f.getAbsolutePath());
        r.setSeries(0);
        int w = r.getSizeX(), h = r.getSizeY();
        int nc = Math.min(order.length, r.getSizeC());
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] rgb = new int[w * h];
        double[] usedLo = new double[order.length];
        double[] usedHi = new double[order.length];

        // physical pixel area, for the component gate
        double pxUm = 0.3107;
        try {
          if (md.getPixelsPhysicalSizeX(0) != null)
            pxUm = md.getPixelsPhysicalSizeX(0).value().doubleValue();
        } catch (Exception ignore) { }
        double umPerPxSq = pxUm * pxUm;

        // Read every channel up front: the tissue mask comes from DAPI and is
        // needed before any channel can be normalised.
        int[][] plane = new int[nc][];
        for (int c = 0; c < nc; c++) plane[c] = readPlane(r, c, w, h);

        // Tissue = Otsu on DAPI. Percentiles are taken INSIDE tissue only;
        // background is mostly empty and would drag every percentile down.
        long[] dh = new long[65536];
        for (int v : plane[0]) dh[v]++;
        int tThr = otsu(dh, (long) w * h);
        boolean[] tis = new boolean[w * h];
        long nTis = 0;
        for (int i = 0; i < plane[0].length; i++) {
          tis[i] = plane[0][i] > tThr; if (tis[i]) nTis++;
        }

        for (int c = 0; c < nc; c++) {
          Win win = cfg.get(order[c]);
          if (win == null) {
            // A marker of the panel with no config row. This has happened by
            // accident (a stray edit merged a data line into a comment) and the
            // channel then vanished from every RIGHT panel with nothing to show
            // for it. Silence is the failure mode; say it loudly, every image.
            System.out.println("  WARNING: no config row for marker '" + order[c]
                               + "' (panel " + panel + ") -- channel NOT rendered: " + f.getName());
            continue;
          }
          int[] px = plane[c];

          // OPTICAL BACKGROUND FLOOR, from the airspace of THIS image.
          //
          // Statistics for the window are taken INSIDE the DAPI tissue mask, but
          // the panel renders the WHOLE field. When a channel is weak -- M6 LEFT
          // AGER is an established staining failure, frac>500 = 0.0097 -- the
          // in-tissue floor can land BELOW the noise of empty airspace, and the
          // entire frame washes with a dull haze of that channel's colour. That
          // is the "555 spill".
          //
          // Alveolar airspace contains no fluorophore, so it is this image's own
          // negative control for optical background. Nothing dimmer than airspace
          // noise may be drawn. Per image, per channel, no free parameter.
          double bgFloor = 0;
          {
            long[] bh = new long[65536]; long nBg = 0;
            for (int i = 0; i < px.length; i++) if (!tis[i]) { bh[px[i]]++; nBg++; }
            if (nBg > 1000) bgFloor = pct(bh, nBg, 0.999);
          }

          // Resolve the window for THIS section.
          double lo = win.low, hi = win.high, B = 0, S = 0;
          if (win.mode.equals("auto")) {
            // PER-IMAGE OPTIMISATION. Comparability across images is explicitly
            // NOT the objective here: the operator's alternative is retouching
            // each panel by hand, and an automated pass that records what it
            // chose is more reproducible than that. Every parameter below lands
            // in display_window_qc.csv, which is the part manual retouching
            // cannot offer.
            long[] hh = new long[65536];
            for (int i = 0; i < px.length; i++) if (tis[i]) hh[px[i]]++;
            if (nTis > 0) {
              B = pct(hh, nTis, 0.10);
              S = pct(hh, nTis, 0.995);
              // Otsu inside tissue picks this image's own true-positive cut --
              // the same judgement a person makes dragging a slider.
              double ot = otsu(hh, nTis);
              // The cut is the STRICTEST of: Otsu, the fractional floor, and the
              // absolute validity guard. Strictest wins so "true-positive only"
              // cannot be relaxed by an unlucky histogram.
              // FLOOR = this image's own range, NOT a fixed absolute number.
              //
              // Otsu is gone: for a broadly expressed marker it assumes two modes
              // of comparable mass and splits near background, so it was the
              // PERMISSIVE candidate, not the strict one (ProSPC resolved to
              // [269,577] against a control p50 of 219-263 and washed out).
              //
              // A fixed absFloor is equally wrong for constitutively expressed
              // markers, in the opposite direction: it ERASES any section whose
              // real signal sits below it. Measured on M6 LEFT, whose AGER stain
              // failed, section p99.5 = 390 and 245 against an absFloor of 400 --
              // the floor sat above the image's own ceiling and the channel
              // vanished. A weak section must still render; that is what tells
              // the reader the stain was weak.
              //
              // So: fraction of THIS image's in-tissue range, bounded below by
              // THIS image's airspace noise. Both terms are per-image, so no
              // section can be erased by another section's statistics, and
              // nothing dimmer than optical background is ever drawn.
              lo = B + win.low * Math.max(1.0, S - B);
              if (win.absFloor > 0) lo = Math.max(lo, win.absFloor);
              // CEILING FROM THE POSITIVE POPULATION, not from whole-tissue
              // percentiles. Anchoring hi to p99.5 of ALL tissue collapses the
              // window for any sparse marker, because most tissue is background
              // and p99.5 still sits near it. That produced ProSPC windows like
              // [241, 454] -- 213 units wide on a 12-bit image -- so everything
              // above 454 saturated and the channel blew out. Measured across
              // the RIGHT panels before this fix: ProSPC hi ranged 446-2405.
              // win.high is therefore a PERCENTILE OF THE PIXELS ABOVE lo.
              long nPos = 0;
              for (int v = (int) Math.ceil(lo); v < 65536; v++) nPos += hh[v];
              if (nPos > 0) {
                long tgt = (long) Math.ceil(win.high * nPos), acc = 0;
                int found = 65535;
                for (int v = (int) Math.ceil(lo); v < 65536; v++) {
                  acc += hh[v]; if (acc >= tgt) { found = v; break; }
                }
                hi = found;
              } else {
                hi = lo + 1;                       // nothing positive; draw nothing
              }
              // Guard against a degenerate window on a near-binary channel.
              if (hi <= lo) hi = lo + Math.max(1.0, 0.10 * Math.max(1.0, S - lo));
            } else { lo = Double.MAX_VALUE; hi = Double.MAX_VALUE; }
          } else if (win.mode.equals("rel")) {
            long[] hh = new long[65536];
            for (int i = 0; i < px.length; i++) if (tis[i]) hh[px[i]]++;
            if (nTis > 0) {
              B = pct(hh, nTis, 0.10);               // this section's background
              S = pct(hh, nTis, 0.995);              // this section's usable ceiling
              double range = Math.max(1.0, S - B);
              lo = B + win.low * range;
              hi = B + win.high * range;
            } else {
              // No tissue: nothing to normalise against. Draw nothing rather
              // than invent a window from an empty distribution.
              lo = Double.MAX_VALUE; hi = Double.MAX_VALUE;
            }
          }
          qc.println("\"" + f.getName() + "\"," + panel + "," + order[c] + "," + win.mode
                     + "," + (int) B + "," + (int) S + "," + (int) lo + "," + (int) hi
                     + "," + nTis);

          // Applied to every mode, including abs: a hand-set floor below the
          // airspace noise would spill just as badly.
          if (!win.mode.equals("scaffold") && bgFloor > lo) {
            lo = bgFloor;
            if (hi <= lo) hi = lo + 1;
          }
          usedLo[c] = lo; usedHi[c] = hi;
          Win eff = new Win(lo, hi, win.gamma, "white");
          for (int i = 0; i < px.length; i++) {
            double s = scale(px[i], eff);
            if (s <= 0.0) continue;
            int cr = (rgb[i] >> 16) & 0xff, cg = (rgb[i] >> 8) & 0xff, cb = rgb[i] & 0xff;
            // additive composite, saturating -- standard for fluorescence overlay
            cr = Math.min(255, cr + (int) Math.round(s * win.r));
            cg = Math.min(255, cg + (int) Math.round(s * win.g));
            cb = Math.min(255, cb + (int) Math.round(s * win.b));
            rgb[i] = (cr << 16) | (cg << 8) | cb;
          }
        }
        img.setRGB(0, 0, w, h, rgb, 0, w);

        // Caption the display provenance ONTO the image. A merge panel whose
        // window is only recorded in a sidecar becomes an uninterpretable JPEG
        // the moment it is pasted into a slide deck.
        // The caption MUST state the window that was actually used. It used to
        // print win.low/win.high -- the CONFIG values -- which in auto mode are a
        // fraction and a percentile, so every panel read "[0-0]" while rendering
        // with something entirely different. A panel that misstates its own
        // display range is worse than one with no caption: it looks auditable
        // and is not.
        StringBuilder cap = new StringBuilder();
        for (int c = 0; c < nc; c++) {
          Win win = cfg.get(order[c]);
          if (win == null) continue;
          if (cap.length() > 0) cap.append("   ");
          cap.append(order[c]).append(" [")
             .append((int) Math.round(usedLo[c])).append("-")
             .append((int) Math.round(usedHi[c])).append("]");
          if (win.gamma != 1.0) cap.append(" y=").append(win.gamma);

        }
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRect(0, h - 58, w, 58);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 20));
        g2.drawString(f.getName().replace(".oir", "") + "   [" + panel + "]", 12, h - 34);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 16));
        g2.drawString("FIXED display, identical across batch:  " + cap, 12, h - 12);
        g2.dispose();

        // Name the output from the RELATIVE PATH, not the bare filename.
        // Olympus repeats the same field name in every _Cycle folder -- M4-1
        // LEFT alone has G001_0001 five times across six cycles -- so naming
        // by filename silently overwrote 8 of 80 panels and the count still
        // looked right in the log. The folder is part of the identity.
        String stem = rel.substring(0, rel.length() - 4)   // drop .oir
                         .replace('/', '~').replace(' ', '_');
        if (stem.length() > 150) stem = stem.substring(stem.length() - 150);
        File png = new File(outDir, stem + "__MERGE.png");
        ImageIO.write(img, "png", png);
        man.println("\"" + f.getName() + "\"," + panel + ",ok,");
        ok++;
        if (ok % 10 == 0) System.out.println("  " + ok + "/" + files.size());
      } catch (Exception e) {
        System.out.println("  FAILED " + f.getName() + ": " + e.getMessage());
        man.println("\"" + f.getName() + "\"," + panel + ",failed,\"" + e.getMessage() + "\"");
        fail++;
      } finally {
        r.close();
      }
    }
    man.close();
    qc.close();
    System.out.println("DONE. ok=" + ok + " failed/skipped=" + fail + " -> " + outDir);
  }
}
