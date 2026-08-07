#!/usr/bin/env python3
"""
spatial/spatial_core.py
=====================================================================
Numerical primitives for the spatial / niche statistics module (Track B).

Dependencies
------------
  numpy      REQUIRED  (verified present: 2.5.1 on
                        C:/Users/dream/AppData/Local/Programs/Python/Python312-arm64/python.exe)
  scipy      NOT AVAILABLE on that interpreter -- verified ModuleNotFoundError.
             Every routine that would normally be scipy.ndimage / scipy.spatial
             is implemented here from scratch.
  tifffile   OPTIONAL (verified present: 2026.7.14). Used ONLY to read the
             engine's per-tile `<stem>__<MARKER>_pod_mask.tif`. Guarded import;
             absence is reported, never silently worked around.

What lives here
---------------
  edt_capped()            exact Euclidean distance transform, clamped at a cap.
                          Separable: exact 1D column sweep, then an exact
                          bounded lower-envelope combination along x. Used for
                          (a) distance-to-structure, (b) distance-to-window-
                          boundary (border edge correction), (c) building the
                          nucleus-support window by dilation.
  UniformGrid             fixed-cell-size neighbour lookup. Replaces
                          scipy.spatial.cKDTree. Bucket-grouped so the inner
                          loop is vectorised.
  set_covariance()        |W INTERSECT (W + v)| for every offset v within a cap,
                          by FFT autocorrelation of the window indicator. This
                          is what makes the Ohser-Stoyan TRANSLATION edge
                          correction exact for an arbitrary (lacy, multiply
                          connected) tissue window.
  rasterise_points()      point set -> boolean raster + affine.
  Raster                  raster + origin + pixel size, with um<->px mapping.

Nothing in this file reads or writes the repository. It has no opinion about
biology; spatial_stats.py supplies that.
=====================================================================
"""
import math
import sys

try:
    import numpy as np
except ImportError:  # pragma: no cover
    sys.exit("ERROR: spatial/ requires numpy. Verified available on "
             "C:/Users/dream/AppData/Local/Programs/Python/Python312-arm64/python.exe "
             "(numpy 2.5.1). Install it for the interpreter you are using.")


# --------------------------------------------------------------------------
# Raster geometry
# --------------------------------------------------------------------------
class Raster:
    """A boolean raster plus the affine that maps microns to pixel indices.

    x_um = origin_x_um + (col + 0.5) * px_um      (pixel CENTRES)
    col  = floor((x_um - origin_x_um) / px_um)
    """

    def __init__(self, mask, origin_x_um, origin_y_um, px_um):
        if mask.dtype != np.bool_:
            raise TypeError("Raster mask must be boolean")
        self.mask = mask
        self.origin_x_um = float(origin_x_um)
        self.origin_y_um = float(origin_y_um)
        self.px_um = float(px_um)
        self.h, self.w = mask.shape

    def to_px(self, x_um, y_um):
        col = np.floor((np.asarray(x_um, dtype=np.float64) - self.origin_x_um) / self.px_um).astype(np.int64)
        row = np.floor((np.asarray(y_um, dtype=np.float64) - self.origin_y_um) / self.px_um).astype(np.int64)
        return row, col

    def inside(self, row, col):
        return (row >= 0) & (row < self.h) & (col >= 0) & (col < self.w)

    def sample(self, field, x_um, y_um, outside_value):
        """Nearest-pixel sample of a float field with this raster's geometry."""
        row, col = self.to_px(x_um, y_um)
        ok = self.inside(row, col)
        out = np.full(row.shape, float(outside_value), dtype=np.float64)
        out[ok] = field[row[ok], col[ok]]
        return out, ok

    @property
    def pixel_area_um2(self):
        return self.px_um * self.px_um

    def area_um2(self):
        return float(self.mask.sum()) * self.pixel_area_um2


def rasterise_points(x_um, y_um, px_um, pad_um=0.0, bounds=None):
    """Boolean raster with one True pixel per occupied cell of the grid."""
    x_um = np.asarray(x_um, dtype=np.float64)
    y_um = np.asarray(y_um, dtype=np.float64)
    if x_um.size == 0:
        raise ValueError("rasterise_points: empty point set")
    if bounds is None:
        x0, x1 = x_um.min() - pad_um, x_um.max() + pad_um
        y0, y1 = y_um.min() - pad_um, y_um.max() + pad_um
    else:
        x0, y0, x1, y1 = bounds
    w = int(math.ceil((x1 - x0) / px_um)) + 1
    h = int(math.ceil((y1 - y0) / px_um)) + 1
    mask = np.zeros((h, w), dtype=bool)
    col = np.floor((x_um - x0) / px_um).astype(np.int64)
    row = np.floor((y_um - y0) / px_um).astype(np.int64)
    np.clip(col, 0, w - 1, out=col)
    np.clip(row, 0, h - 1, out=row)
    mask[row, col] = True
    return Raster(mask, x0, y0, px_um)


# --------------------------------------------------------------------------
# Exact capped Euclidean distance transform (replaces scipy.ndimage)
# --------------------------------------------------------------------------
def edt_capped(seed, cap_px):
    """Euclidean distance in PIXELS from every pixel to the nearest True in
    `seed`, exact for every true distance <= cap_px, clamped to cap_px above.

    Separable exact algorithm:
      1. per-column 1D distance g(x, y) to the nearest seed in the same column
         (two sweeps; exact for the 1D problem),
      2. d(x, y)^2 = min_{|dx| <= R} dx^2 + g(x + dx, y)^2.
         Restricting |dx| <= R = cap_px loses nothing, because any true
         distance <= R has its argmin within R columns.

    The clamp is deliberate: on a 20 mm slide an uncapped EDT costs an
    unbounded number of passes, and every downstream consumer bins distance
    only up to a declared maximum. Values that come back exactly == cap_px mean
    ">= cap_px" and callers must treat them as censored, not as a measurement.
    """
    if seed.dtype != np.bool_:
        raise TypeError("edt_capped: seed must be boolean")
    if not seed.any():
        raise ValueError("edt_capped: seed mask is empty -- there is no "
                         "structure to measure distance to. Refusing to return "
                         "a uniform cap that would read as a real measurement.")
    cap = float(cap_px)
    if cap < 1.0:
        raise ValueError("edt_capped: cap_px must be >= 1")
    h, w = seed.shape
    big = cap + 1.0

    g = np.full((h, w), big, dtype=np.float32)
    g[seed] = 0.0
    for y in range(1, h):
        np.minimum(g[y], g[y - 1] + 1.0, out=g[y])
    for y in range(h - 2, -1, -1):
        np.minimum(g[y], g[y + 1] + 1.0, out=g[y])
    np.minimum(g, big, out=g)

    g2 = g * g
    best = g2.copy()
    radius = int(math.ceil(cap))
    for dx in range(1, radius + 1):
        d2 = np.float32(dx * dx)
        np.minimum(best[:, :w - dx], g2[:, dx:] + d2, out=best[:, :w - dx])
        np.minimum(best[:, dx:], g2[:, :w - dx] + d2, out=best[:, dx:])
    out = np.sqrt(best, out=best)
    np.minimum(out, np.float32(cap), out=out)
    return out


def dilate_binary(mask, radius_px):
    """Exact disc dilation via the capped EDT. radius_px may be fractional."""
    if radius_px <= 0:
        return mask.copy()
    cap = max(1.0, math.ceil(radius_px) + 1.0)
    return edt_capped(mask, cap) <= float(radius_px)


# --------------------------------------------------------------------------
# Window set covariance -> Ohser-Stoyan translation edge-correction weights
# --------------------------------------------------------------------------
class TranslationCorrector:
    """Exact translation (Ohser-Stoyan) edge correction for an ARBITRARY window.

    For a pair of points separated by the vector v, the translation-corrected
    weight is
            w(v) = |W| / gamma(v),      gamma(v) = |W INTERSECT (W + v)|
    and gamma is the set covariance of the window, obtained here as the
    autocorrelation of the window indicator by FFT.

    Why this and not the textbook "Ripley isotropic" correction:
      * Ripley's isotropic weight is the reciprocal of the fraction of the
        circle of radius d_ij centred on point i that lies inside W. Every
        quick implementation evaluates that with the closed-form RECTANGLE
        formulas, which silently substitute the bounding box for the real
        window. On a lung section the tissue occupies ~20-27% of the canvas and
        is lacy, so the bounding-box correction is ~1 everywhere: it is
        numerically indistinguishable from NO correction while looking
        principled. That is the single most common way tissue Ripley analyses
        go wrong.
      * The border (reduced-sample) correction is honest and assumption-free
        but on lacy parenchyma almost every point lies within 40 um of tissue
        boundary, so it discards nearly the whole pattern at the radii of
        interest. It is retained here as a QC comparator, not the primary.
      * The translation correction is exact for any window whose covariance you
        can compute, keeps every pair, and the covariance of a raster window is
        one FFT.

    The covariance is evaluated on a COARSER raster than the analysis raster
    (`cov_px_um`, default 8 um). gamma(v) is a smooth, slowly varying function
    of v, and the memory cost of an FFT on a full-slide 2 um raster is not.
    """

    def __init__(self, window, max_offset_um, cov_px_um=8.0):
        if not isinstance(window, Raster):
            raise TypeError("TranslationCorrector needs a Raster window")
        self.cov_px_um = float(cov_px_um)
        self.max_offset_um = float(max_offset_um)

        # Downsample the window to the covariance raster by block-OR.
        factor = max(1, int(round(self.cov_px_um / window.px_um)))
        m = window.mask
        hh = (m.shape[0] // factor) * factor
        ww = (m.shape[1] // factor) * factor
        if hh == 0 or ww == 0:
            raise ValueError("TranslationCorrector: window smaller than one covariance pixel")
        small = m[:hh, :ww].reshape(hh // factor, factor, ww // factor, factor).any(axis=(1, 3))
        self.cov_px_um = window.px_um * factor
        self.small = small

        h, w = small.shape
        pad = np.zeros((2 * h, 2 * w), dtype=np.float64)
        pad[:h, :w] = small
        spec = np.fft.rfft2(pad)
        cov = np.fft.irfft2(spec * np.conj(spec), s=pad.shape)

        m_off = int(math.ceil(self.max_offset_um / self.cov_px_um)) + 1
        self.m_off = m_off
        idx = np.arange(-m_off, m_off + 1)
        block = cov[np.ix_(idx % (2 * h), idx % (2 * w))]
        # Rasterisation noise can make the covariance slightly negative.
        np.maximum(block, 0.0, out=block)
        self.gamma_px = block                       # pixels^2, shape (2M+1, 2M+1)
        self.window_area_px = float(small.sum())
        if self.window_area_px <= 0:
            raise ValueError("TranslationCorrector: window is empty")
        self.n_gamma_zero = 0

    def weights(self, dx_um, dy_um):
        """Translation weights |W| / gamma(v) for pair offset vectors, in um."""
        cx = np.rint(np.asarray(dx_um, dtype=np.float64) / self.cov_px_um).astype(np.int64)
        cy = np.rint(np.asarray(dy_um, dtype=np.float64) / self.cov_px_um).astype(np.int64)
        np.clip(cx, -self.m_off, self.m_off, out=cx)
        np.clip(cy, -self.m_off, self.m_off, out=cy)
        g = self.gamma_px[cy + self.m_off, cx + self.m_off]
        bad = g <= 0.0
        n_bad = int(bad.sum())
        if n_bad:
            self.n_gamma_zero += n_bad
            g = np.where(bad, self.window_area_px, g)   # weight 1; counted in QC
        w = self.window_area_px / g
        # gamma <= |W| always, so the weight can never be < 1. Anything below 1
        # means the covariance raster and the analysis raster disagree.
        np.maximum(w, 1.0, out=w)
        return w


# --------------------------------------------------------------------------
# Neighbour lookup (replaces scipy.spatial.cKDTree)
# --------------------------------------------------------------------------
class UniformGrid:
    """Fixed-cell-size bucket grid over a 2D point set, in microns.

    Query points are processed BUCKET BY BUCKET, so the distance computation is
    one vectorised (n_query_in_bucket x n_candidates) block per bucket rather
    than one Python iteration per query point.
    """

    def __init__(self, x_um, y_um, cell_um):
        self.x = np.asarray(x_um, dtype=np.float64)
        self.y = np.asarray(y_um, dtype=np.float64)
        self.n = self.x.size
        self.cell = float(cell_um)
        if self.cell <= 0:
            raise ValueError("UniformGrid: cell_um must be > 0")
        self.ix = np.floor(self.x / self.cell).astype(np.int64)
        self.iy = np.floor(self.y / self.cell).astype(np.int64)
        order = np.lexsort((self.iy, self.ix))
        self.order = order
        self.sx = self.ix[order]
        self.sy = self.iy[order]
        self.buckets = {}
        if self.n:
            change = np.flatnonzero(np.r_[True, (self.sx[1:] != self.sx[:-1]) |
                                                (self.sy[1:] != self.sy[:-1])])
            ends = np.r_[change[1:], self.n]
            for s, e in zip(change, ends):
                self.buckets[(int(self.sx[s]), int(self.sy[s]))] = (int(s), int(e))

    def _candidates(self, bx, by):
        parts = []
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                se = self.buckets.get((bx + dx, by + dy))
                if se is not None:
                    parts.append(self.order[se[0]:se[1]])
        if not parts:
            return np.empty(0, dtype=np.int64)
        return np.concatenate(parts)

    def query_buckets(self, qx, qy):
        """Yield (query_index_array, candidate_index_array, D2 matrix) blocks.

        D2[a, b] = squared distance from query qi[a] to grid point cj[b].
        Only candidates in the 3x3 bucket neighbourhood are returned, so the
        caller MUST use a search radius <= cell_um.
        """
        qx = np.asarray(qx, dtype=np.float64)
        qy = np.asarray(qy, dtype=np.float64)
        qix = np.floor(qx / self.cell).astype(np.int64)
        qiy = np.floor(qy / self.cell).astype(np.int64)
        qorder = np.lexsort((qiy, qix))
        sqx, sqy = qix[qorder], qiy[qorder]
        if qorder.size == 0:
            return
        change = np.flatnonzero(np.r_[True, (sqx[1:] != sqx[:-1]) | (sqy[1:] != sqy[:-1])])
        ends = np.r_[change[1:], qorder.size]
        for s, e in zip(change, ends):
            qi = qorder[s:e]
            cj = self._candidates(int(sqx[s]), int(sqy[s]))
            if cj.size == 0:
                yield qi, cj, np.empty((qi.size, 0), dtype=np.float64)
                continue
            # Chunk so one enormous bucket cannot allocate an unbounded matrix.
            step = max(1, int(4_000_000 // max(1, cj.size)))
            for k in range(0, qi.size, step):
                blk = qi[k:k + step]
                dx = qx[blk][:, None] - self.x[cj][None, :]
                dy = qy[blk][:, None] - self.y[cj][None, :]
                yield blk, cj, dx * dx + dy * dy


# --------------------------------------------------------------------------
# Small helpers
# --------------------------------------------------------------------------
def bin_label(lo, hi):
    """CSV-safe label for a distance bin, e.g. (0, 25) -> 'd0_25um',
    (-50, -25) -> 'dm50_m25um'. 'm' is minus."""
    def _f(v):
        s = ("m" if v < 0 else "") + (f"{abs(v):g}").replace(".", "p")
        return s
    return f"d{_f(lo)}_{_f(hi)}um"


def histogram_counts(values, edges):
    """Counts per [edges[k], edges[k+1]) bin. Values outside are not counted;
    the caller receives the overflow/underflow separately so nothing is lost
    silently."""
    v = np.asarray(values, dtype=np.float64)
    e = np.asarray(edges, dtype=np.float64)
    idx = np.searchsorted(e, v, side="right") - 1
    under = int((idx < 0).sum())
    over = int((idx >= e.size - 1).sum())
    keep = (idx >= 0) & (idx < e.size - 1)
    counts = np.bincount(idx[keep], minlength=e.size - 1).astype(np.int64)
    return counts, under, over


def weighted_histogram(values, weights, edges):
    v = np.asarray(values, dtype=np.float64)
    wt = np.asarray(weights, dtype=np.float64)
    e = np.asarray(edges, dtype=np.float64)
    idx = np.searchsorted(e, v, side="right") - 1
    keep = (idx >= 0) & (idx < e.size - 1)
    return np.bincount(idx[keep], weights=wt[keep], minlength=e.size - 1)
