// Copyright 2026 takahashikzn
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package jp.uvoc.iroiro;

import java.awt.Transparency;
import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.lang.System.Logger;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;


/**
 * PNG color reduction in pure Java: an adaptive palette of at most {@link Opts#maxColors()} entries
 * and the encoder to write it, with no process to spawn.
 * <p>
 * The point is the entry point taking a {@link BufferedImage} directly. Going through it skips the
 * whole "encode a 32bpp PNG with ImageIO, then hand the bytes to a separate reducer that decodes
 * them again" round trip, which costs more than the process launch it also removes.
 * <p>
 * The output is a palette PNG, which is the smallest form. {@link Opts#palettizeLossy()} turns that
 * off for the lossy case, writing the reduced pixels as truecolor instead: some products will not
 * consider JPEG for a palette PNG when exporting a PDF, and Flate on a photograph runs a couple of
 * times the size of a JPEG of it. Palettizing stays the default because it gives the smallest
 * file; turn it off when a PDF rendered from the image matters more than the image itself.
 * <p>
 * The algorithm is <b>variance bisection in alpha-premultiplied space</b>: evaluate cuts on every
 * axis using the total RGBA squared error, then split the box with the largest error reduction.
 * Optional k-means refinement improves that initial palette.
 * Premultiplying keeps the RGB of transparent pixels out of the distance metric. Images with no
 * more than {@link Opts#maxColors()} distinct colors are palettized as-is, so they are encoded
 * <b>losslessly</b>; synthetic images (charts, logos, rules, screenshots) usually take that path.
 * <p>
 * Dithering is available but off by default: its fine noise generally increases the PNG size.
 * <p>
 * <b>Depends on the JDK only</b>, like the rest of this package; {@link IroMisc} shares bounded image
 * workers with {@link IroResize}. No logging framework, no profiler hooks (wrap the call site instead).
 *
 * @author takahashikzn
 */
@SuppressWarnings({ "OverlyComplexArithmeticExpression", "MathClampMigration" })
public final class IroQuant {

    private static final Logger log = System.getLogger(IroQuant.class.getName());

    private IroQuant() { }

    /**
     * Quantization settings.
     *
     * @param palettizeLossy palettize even when the reduction loses colors. Smallest output, and
     * right when the image file itself, or a document carrying it, is what gets delivered.
     * Turning it off writes a photograph as truecolor so that a PDF exporter can still choose
     * JPEG for it -- see the class javadoc for why that is not the default.
     * @param importance how much heavier a pixel in a flat area counts than one on an edge, 0 to
     * weigh every pixel the same. See {@link #importance}.
     * @param kmeans maximum refinement passes; stops early when the assignments stop changing.
     * @param dither Floyd-Steinberg strength, 0 to 1, 0 being off. Raising {@code kmeans} shrinks
     * the <em>size</em> of the error but not the way it pools into bands across a smooth gradient,
     * which is what the eye picks up. Diffusing the error attacks that directly, at the cost of a
     * noticeably larger file -- the fine noise it trades the bands for does not deflate well.
     * Measured on the material this was tuned on, it made every metric worse and looked worse too,
     * so it stays off -- but that was before the error moved into premultiplied space, so measure
     * again before relying on it. The option is here because the trade is real for other material.
     * @param deflate zlib level, 1 to 9; see {@link #DEFLATE}. Raise it when the bytes matter more
     * than the wait -- when what gets delivered is the file itself rather than a rendering of it.
     * @param parallel split large images' independent passes across the shared image pool. The result
     * is identical either way -- every split is over an independent range -- so this is here to
     * pin that in a test, and for a caller that wants the whole reduction on one thread.
     */
    public record Opts(int maxColors, int kmeans, boolean palettizeLossy, double dither, double importance, int deflate, boolean parallel) {

        /**
         * Compression level for the pixels. The point of doing this in process is latency, and
         * compression was over half the cost of writing a palette PNG, so this sits near the fast
         * end -- but not at it. Level 1 is a trap on a zlib-ng build, where it gives up about a
         * quarter of the compression for a few milliseconds; level 2 buys that back almost
         * entirely. Level 9 is out of the question either way: it costs roughly ten times level 2
         * for another 15%, and on some inputs its match search degenerates outright (on one test
         * image: 0.2ms at level 2, 8.5ms at level 9).
         */
        public static final int DEFLATE = 2;

        /**
         * The heaviest weight {@link #importance} may ask for. A weight is held in one signed byte,
         * so {@code 1 + importance} has to stay inside it or the histogram counts go negative.
         */
        public static final int MAX_IMPORTANCE = 126;

        /** One k-means pass. It costs buckets * colors, and saturates by about two. */
        public static final Opts DEFAULT = new Opts(256, 1, true, 0, 3, DEFLATE, true);

        public Opts {
            // A palette index goes out as one byte, so 256 is the format's ceiling rather than a policy.
            // Past it the index wraps and the extra colors silently become color 0
            if (maxColors < 1 || 256 < maxColors) throw new IllegalArgumentException("maxColors must be 1..256: " + maxColors);
            if (kmeans < 0) throw new IllegalArgumentException("kmeans must not be negative: " + kmeans);
            if (!(0 <= dither && dither <= 1)) throw new IllegalArgumentException("dither must be 0..1: " + dither); // also rejects NaN
            if (!(0 <= importance && importance <= MAX_IMPORTANCE)) //
                throw new IllegalArgumentException("importance must be 0.." + MAX_IMPORTANCE + ": " + importance);
            if (deflate < Deflater.BEST_SPEED || Deflater.BEST_COMPRESSION < deflate) //
                throw new IllegalArgumentException("deflate must be 1..9: " + deflate);
        }

        public Opts maxColors(final int x) { return new Opts(x, this.kmeans, this.palettizeLossy, this.dither, this.importance, this.deflate, this.parallel); }

        public Opts kmeans(final int x) { return new Opts(this.maxColors, x, this.palettizeLossy, this.dither, this.importance, this.deflate, this.parallel); }

        public Opts palettizeLossy(final boolean x) {
            return new Opts(this.maxColors, this.kmeans, x, this.dither, this.importance, this.deflate, this.parallel);
        }

        public Opts dither(final double x) {
            return new Opts(this.maxColors, this.kmeans, this.palettizeLossy, x, this.importance, this.deflate, this.parallel);
        }

        public Opts importance(final double x) {
            return new Opts(this.maxColors, this.kmeans, this.palettizeLossy, this.dither, x, this.deflate, this.parallel);
        }

        public Opts deflate(final int x) { return new Opts(this.maxColors, this.kmeans, this.palettizeLossy, this.dither, this.importance, x, this.parallel); }

        public Opts parallel(final boolean x) {
            return new Opts(this.maxColors, this.kmeans, this.palettizeLossy, this.dither, this.importance, this.deflate, x);
        }
    }

    private static Opts opts = Opts.DEFAULT;

    public static Opts defaultOpts() { return opts; }

    public static void defaultOpts(final Opts x) { opts = x; }

    // ------------------------------------------------------------------- entry

    /**
     * Convert to sRGB using the profile the source PNG carries in {@code iCCP}. <b>ImageIO does not
     * apply it</b>, so the decoded pixels stay in the profile's space while the ColorModel claims
     * sRGB; written back out they come back a different color. Anything that reads the profile does
     * this conversion, so skipping it is a visible change rather than a neutral one.
     *
     * Handles gray, RGB and their alpha forms at any sample width, and a palette carrying an RGB
     * profile. Anything else - a palette under a gray profile, a profile whose component count does
     * not match the image - is left alone rather than half converted.
     *
     * @param png the encoded source, or {@code null} when the caller has no bytes to read
     */
    public static BufferedImage toSRGB(final BufferedImage img, final byte[] png) {

        if (png == null) return img;

        final var raw = IroPNG.iccp(png);
        if (raw == null) return img;

        try {
            final var cs = new ICC_ColorSpace(ICC_Profile.getInstance(raw));
            if (cs.isCS_sRGB()) return img;

            final boolean alpha = img.getColorModel().hasAlpha();
            final var cm = new ComponentColorModel(cs, alpha, false, alpha ? Transparency.TRANSLUCENT : Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

            // reinterpret the pixels in the embedded profile's space, then let ColorConvertOp do the work
            final var raster = img.getRaster();
            final int nc = cm.getNumComponents();
            final BufferedImage src;

            // A palette is tested first: its one band holds indexes, not samples, so a one component
            // profile would otherwise match it by band count and read the indexes as gray levels
            if (img.getColorModel() instanceof IndexColorModel) //
                src = (3 <= nc) ? depalette(img, cm, nc) : null; // a palette holds colors, so the profile has to
            else if (nc == raster.getNumBands() && raster.getTransferType() == DataBuffer.TYPE_BYTE) //
                src = new BufferedImage(cm, raster, false, null); // the samples are already laid out for it
            else if (nc == raster.getNumBands()) //
                src = narrow(img, cm, nc); // right bands, wider than a byte
            else //
                src = null;

            if (src == null) return img; // half converting is worse than not converting

            final var ret = new BufferedImage(img.getWidth(), img.getHeight(), alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

            // Not split across threads: ColorConvertOp on a getSubimage() pair silently leaves most
            // of the destination unwritten, and the pass is a small part of the whole anyway
            new ColorConvertOp(null).filter(src, ret);

            return ret;
        } catch (final Exception e) {
            log.log(Logger.Level.WARNING, "unusable iCCP, leaving the pixels alone", e);
            return img;
        }
    }

    /**
     * Resolve a palette into 8-bit samples in {@code cm}'s space. The raster is one band of indexes,
     * so there is nothing to reinterpret in place; {@code getRGB} returns the palette entry itself,
     * which is exactly the value the profile describes.
     */
    private static BufferedImage depalette(final BufferedImage img, final ComponentColorModel cm, final int nc) {

        final int w = img.getWidth();
        final int h = img.getHeight();

        final var raster = cm.createCompatibleWritableRaster(w, h);
        final var px = new int[w];
        final var smp = new int[w * nc];

        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, px, 0, w);

            for (int x = 0; x < w; x++) {
                final int c = px[x];
                final int o = x * nc;

                smp[o] = c >>> 16 & 0xFF;
                smp[o + 1] = c >>> 8 & 0xFF;
                smp[o + 2] = c & 0xFF;
                if (3 < nc) smp[o + 3] = c >>> 24;
            }

            raster.setPixels(0, y, w, 1, smp);
        }

        return new BufferedImage(cm, raster, false, null);
    }

    /**
     * Narrow samples wider than a byte, which is what a 16-bit image carries and what stops it being
     * reinterpreted where it lies. Reads the raster rather than {@code getRGB} so the stored value
     * arrives untouched: {@code getRGB} would run the image's own color model over it first, and for
     * a gray image that model is linear gray, which would bend the very numbers the embedded profile
     * is there to interpret. Any component count works, so a gray profile is handled too. 8 bits is
     * the precision the palette is built at regardless.
     */
    private static BufferedImage narrow(final BufferedImage img, final ComponentColorModel cm, final int nc) {

        final int w = img.getWidth();
        final int h = img.getHeight();
        final var src = img.getRaster();
        final var sm = img.getSampleModel();

        final var shift = new int[nc];
        for (int b = 0; b < nc; b++) shift[b] = sm.getSampleSize(b) - 8;

        final var dst = cm.createCompatibleWritableRaster(w, h);
        final var row = new int[w * nc];

        for (int y = 0; y < h; y++) {
            src.getPixels(0, y, w, 1, row);

            for (int o = 0; o < row.length; o += nc)
                for (int b = 0; b < nc; b++) {
                    final int v = row[o + b];
                    final int k = shift[b];
                    row[o + b] = (0 < k) ? v >> k : (k < 0) ? v << -k : v;
                }

            dst.setPixels(0, y, w, 1, row);
        }

        return new BufferedImage(cm, dst, false, null);
    }

    /** Reduce the colors and write a palette PNG. */
    public static byte[] encode(final BufferedImage img) { return encode(img, opts); }

    public static byte[] encode(final BufferedImage img, final Opts o) {

        final int w = img.getWidth();
        final int h = img.getHeight();

        return encode(argb(img), w, h, o);
    }

    /**
     * Extract the pixels as ARGB, reading the raster directly for the types ImageIO and Java2D
     * actually produce and falling back to {@code getRGB}, which runs a ColorModel conversion per
     * pixel, only for the rest.
     */
    private static int[] argb(final BufferedImage img) {

        final int w = img.getWidth();
        final int h = img.getHeight();
        final int n = w * h;
        final var sm = img.getSampleModel();
        final var db = img.getRaster().getDataBuffer();

        if (db.getNumBanks() == 1 && db.getOffset() == 0) {

            if (db instanceof DataBufferInt b && sm instanceof SinglePixelPackedSampleModel s //
                && s.getScanlineStride() == w && b.getData().length == n) {

                final var src = b.getData();

                switch (img.getType()) {
                    // the image's own backing store. Nothing here writes to it
                    case BufferedImage.TYPE_INT_ARGB -> { return src; }
                    case BufferedImage.TYPE_INT_RGB -> {
                        final var ret = new int[n];
                        for (int i = 0; i < n; i++) ret[i] = src[i] | 0xFF000000;
                        return ret;
                    }
                    default -> { }
                }
            }

            // TYPE_4BYTE_ABGR / TYPE_3BYTE_BGR. Take the band order from the SampleModel rather than assuming it
            if (db instanceof DataBufferByte b && sm instanceof PixelInterleavedSampleModel s //
                && (img.getType() == BufferedImage.TYPE_4BYTE_ABGR || img.getType() == BufferedImage.TYPE_3BYTE_BGR)) {

                final int ps = s.getPixelStride();
                final var off = s.getBandOffsets();
                final var src = b.getData();

                if (s.getScanlineStride() == w * ps && n * ps <= src.length) {
                    final int or = off[0];
                    final int og = off[1];
                    final int ob = off[2];
                    final int oa = (3 < off.length) ? off[3] : -1;

                    final var ret = new int[n];
                    for (int i = 0, j = 0; i < n; i++, j += ps)
                        ret[i] = ((oa < 0) ? 0xFF : src[j + oa] & 0xFF) << 24 //
                                 | (src[j + or] & 0xFF) << 16 //
                                 | (src[j + og] & 0xFF) << 8 //
                                 | (src[j + ob] & 0xFF);

                    return ret;
                }
            }
        }

        return img.getRGB(0, 0, w, h, new int[n], 0, w);
    }

    /** Entry point taking raw pixels, for callers that never build a {@link BufferedImage}. */
    public static byte[] encode(final int[] argb, final int width, final int height, final Opts o) {

        // in long, or 65536x65536 wraps to zero and passes every check below
        final long n = (long) width * height;

        if (width <= 0 || height <= 0 || Integer.MAX_VALUE < n || argb.length < n) //
            throw new IllegalArgumentException("bad image: %dx%d, %d pixels".formatted(width, height, argb.length));

        final int len = (int) n;

        // trailing pixels would otherwise pollute the histogram
        final var q = quantize((argb.length == len) ? argb : Arrays.copyOf(argb, len), width, height, o);

        return (q.lossless() || o.palettizeLossy()) //
            ? IroPNG.indexed(q.index(), q.palette(), width, height, o.deflate(), o.parallel()) //
            : IroPNG.truecolor(q.index(), q.palette(), width, height, o.deflate(), o.parallel());
    }

    // ------------------------------------------------------------- quantization

    private record Result(byte[] index, int[] palette, boolean lossless) { }

    /**
     * Where the exact-precision histogram gives up; past this it is rebuilt at 6 bits per RGB
     * channel for opaque images, or 5 bits per RGBA channel otherwise.
     * The coarse pass clusters on a rounded grid, which shows up as banding on smooth gradients, so
     * keep this high enough that photographs stay on the exact path. It only bounds memory - the
     * arrays grow to the colors actually present, so a small image never pays for it.
     */
    private static final int EXACT_LIMIT = 1 << 18;

    /** 5-bit RGBA keys plus a dedicated fully transparent key. */
    private static final int COARSE_LIMIT = (1 << 20) + 1;

    private static Result quantize(final int[] argb, final int width, final int height, final Opts o) {

        // Weighting the pixels is what keeps a gradient smooth; see importance()
        final var imp = (0 < o.importance()) ? importance(argb, width, height, o.importance(), o.parallel()) : null;

        var hist = histogram(argb, imp, 0, EXACT_LIMIT);
        final boolean exact = (hist != null); // the coarse histogram merges colors, so it can never be lossless
        if (!exact) {
            boolean opaque = true;
            for (final int c: argb)
                if ((c >>> 24) != 255) {
                    opaque = false;
                    break;
                }
            hist = histogram(argb, imp, opaque ? 6 : 5, opaque ? EXACT_LIMIT : COARSE_LIMIT);
        }
        if (hist == null) throw new IllegalStateException("histogram overflow"); // bounded by the coarse key space

        final int nb = hist.n;

        // Representative color per bucket, held in alpha-premultiplied space (pr = r * a / 255)
        final var pr = new double[nb];
        final var pg = new double[nb];
        final var pb = new double[nb];
        final var pa = new double[nb];

        for (int i = 0; i < nb; i++) {
            final double c = hist.cnt[i];
            // Keep fractional coordinates: rounding makes distinct low-alpha colors identical
            // to the reducer, even though palette() can represent them separately.
            pr[i] = hist.sr[i] / c / 255;
            pg[i] = hist.sg[i] / c / 255;
            pb[i] = hist.sb[i] / c / 255;
            pa[i] = hist.sa[i] / c;
        }

        final double[][] ch = { pr, pg, pb, pa };

        // bucket -> palette index
        final var map = new int[nb];
        final int n;

        if (nb <= o.maxColors()) {
            // The colors already fit. Palettize as-is without quantizing, which is lossless
            for (int i = 0; i < nb; i++) map[i] = i;
            n = nb;
        } else {
            // Mixing clear pixels into a faint color paints halos on formerly transparent areas.
            // A one-color request cannot keep both, but every larger palette reserves clear.
            int transparent = -1;
            if (1 < o.maxColors()) for (int i = 0; i < nb; i++)
                if (hist.sa[i] == 0) {
                    transparent = i;
                    break;
                }
            n = reduce(ch, hist.cnt, nb, map, o, transparent, argb.length);
        }

        return index(argb, hist, map, palette(hist, map, n), exact && nb <= o.maxColors(), width, height, o);
    }

    /**
     * Derive the palette from the accumulated sums rather than from the rounded per-bucket
     * coordinates. {@code sr / sa} is the exact alpha-weighted mean of the red channel, so a
     * bucket holding a single color reproduces it bit for bit -- which is what keeps the
     * "colors already fit" path lossless.
     */
    private static int[] palette(final _Hist h, final int[] map, final int n) {

        final var sr = new double[n];
        final var sg = new double[n];
        final var sb = new double[n];
        final var sa = new double[n];
        final var cnt = new double[n];

        for (int k = 0; k < h.n; k++) {
            final int i = map[k];
            sr[i] += h.sr[k];
            sg[i] += h.sg[k];
            sb[i] += h.sb[k];
            sa[i] += h.sa[k];
            cnt[i] += h.cnt[k];
        }

        final var ret = new int[n];
        for (int i = 0; i < n; i++) ret[i] = color(sr[i], sg[i], sb[i], sa[i], cnt[i]);

        return ret;
    }

    /** sr = sum(r * a * w), sa = sum(a * w), cnt = sum(w). */
    private static int color(final double sr, final double sg, final double sb, final double sa, final double cnt) {

        final int a = cnt <= 0 ? 0 : Math.clamp(0, round(sa / cnt), 255);
        if (a == 0 || sa <= 0) return 0;

        return a << 24 | Math.clamp(0, round(sr / sa), 255) << 16 | Math.clamp(0, round(sg / sa), 255) << 8 | Math.clamp(0, round(sb / sa), 255);
    }

    /**
     * Greedy variance bisection followed by k-means refinement.
     *
     * @return the number of palette entries
     */
    private static int reduce(final double[][] ch, final int[] cnt, final int nb, final int[] map, final Opts o, final int transparent, final int pixels) {

        final int first = transparent < 0 ? 0 : 1;
        final var ord = new int[nb - first];
        for (int i = 0, at = 0; i < nb; i++)
            if (i != transparent) ord[at++] = i;

        final var boxes = new _Box[Math.min(o.maxColors() - first, ord.length)];
        boxes[0] = box(ord, 0, ord.length, ch, cnt);
        int nbox = 1;

        final var tmp = new int[nb];

        while (nbox < boxes.length) {

            int pick = -1;
            double score = 0;
            for (int i = 0; i < nbox; i++)
                if (score < boxes[i].score) {
                    score = boxes[i].score;
                    pick = i;
                }

            if (pick < 0) break; // nothing left that can be split

            final var b = boxes[pick];
            final int cut = split(b, ord, ch, tmp);

            if (cut <= b.lo || b.hi <= cut) {
                b.score = 0;
                continue;
            } // could not split along the axis

            boxes[pick] = box(ord, b.lo, cut, ch, cnt);
            boxes[nbox++] = box(ord, cut, b.hi, ch, cnt);
        }

        // centroids, in alpha-premultiplied space
        final var cr = new double[nbox + first];
        final var cg = new double[nbox + first];
        final var cb = new double[nbox + first];
        final var ca = new double[nbox + first];

        for (int i = 0; i < nbox; i++) {
            centroid(boxes[i].lo, boxes[i].hi, ord, ch, cnt, i + first, cr, cg, cb, ca);
            for (int j = boxes[i].lo; j < boxes[i].hi; j++) map[ord[j]] = i + first;
        }
        nbox += first; // entry 0 and map[transparent] remain zero when clear is reserved

        for (int it = 0; it < o.kmeans(); it++) {
            if (!assign(ch, nb, map, nbox, first, cr, cg, cb, ca, o.parallel(), pixels)) return nbox;
            recentroid(ch, cnt, nb, map, nbox, cr, cg, cb, ca);
        }
        if (0 < o.kmeans()) assign(ch, nb, map, nbox, first, cr, cg, cb, ca, o.parallel(), pixels); // re-fit to the final centroids

        return nbox;
    }

    private static final class _Box {

        int lo;

        int hi;

        int axis;

        int cut;

        double min;

        double scale;

        double score; // actual reduction in total RGBA squared error at the best available cut
    }

    /**
     * Find the cut that removes the most total RGBA squared error, considering every axis.
     * Each axis has 256 candidate bins over this box's own range; sums stay at full precision.
     * The gain is wL*wR/wT * |meanL-meanR|^2, so no squared moments or repeated sorts are needed.
     * Ranking boxes by this gain spends each palette entry where it actually reduces the error.
     */
    private static _Box box(final int[] ord, final int lo, final int hi, final double[][] ch, final int[] cnt) {

        final var ret = new _Box();
        ret.lo = lo;
        ret.hi = hi;

        if (hi - lo <= 1) return ret; // an unsplittable box keeps score = 0

        double w = 0;
        final var s = new double[4];
        final var min = new double[] { 255, 255, 255, 255 };
        final var max = new double[4];

        for (int i = lo; i < hi; i++) {
            final int b = ord[i];
            final double c = cnt[b];
            w += c;

            for (int t = 0; t < 4; t++) {
                final double v = ch[t][b];
                s[t] += c * v;
                min[t] = Math.min(min[t], v);
                max[t] = Math.max(max[t], v);
            }
        }

        if (w <= 0) return ret;

        final var weights = new double[256];
        final var sums = new double[4][256];
        final var left = new double[4];

        for (int axis = 0; axis < 4; axis++) {
            if (max[axis] <= min[axis]) continue;
            final double scale = 255 / (max[axis] - min[axis]);
            Arrays.fill(weights, 0);
            for (final var sum: sums) Arrays.fill(sum, 0);
            Arrays.fill(left, 0);

            for (int i = lo; i < hi; i++) {
                final int k = ord[i];
                final int bin = bin(ch[axis][k], min[axis], scale);
                final double c = cnt[k];
                weights[bin] += c;
                for (int t = 0; t < 4; t++) sums[t][bin] += c * ch[t][k];
            }

            double wL = 0;
            for (int cut = 0; cut < 255; cut++) {
                wL += weights[cut];
                for (int t = 0; t < 4; t++) left[t] += sums[t][cut];
                final double wR = w - wL;
                if (weights[cut] == 0 || wL <= 0 || wR <= 0) continue;

                double distance = 0;
                for (int t = 0; t < 4; t++) {
                    final double d = left[t] / wL - (s[t] - left[t]) / wR;
                    distance += d * d;
                }
                final double gain = wL * wR / w * distance;
                if (ret.score < gain) {
                    ret.score = gain;
                    ret.axis = axis;
                    ret.cut = cut;
                    ret.min = min[axis];
                    ret.scale = scale;
                }
            }
        }

        return ret;
    }

    private static int bin(final double value, final double min, final double scale) {
        return Math.clamp(0, (int) ((value - min) * scale), 255);
    }

    /** Stable partition using the same bins as the gain calculation. */
    private static int split(final _Box b, final int[] ord, final double[][] ch, final int[] tmp) {

        final var v = ch[b.axis];
        int at = b.lo;
        for (int i = b.lo; i < b.hi; i++)
            if (bin(v[ord[i]], b.min, b.scale) <= b.cut) tmp[at++] = ord[i];
        final int cut = at;
        for (int i = b.lo; i < b.hi; i++)
            if (b.cut < bin(v[ord[i]], b.min, b.scale)) tmp[at++] = ord[i];
        System.arraycopy(tmp, b.lo, ord, b.lo, b.hi - b.lo);

        return cut;
    }

    private static void centroid(final int lo, final int hi, final int[] ord, final double[][] ch, final int[] cnt, final int i, //
        final double[] cr, final double[] cg, final double[] cb, final double[] ca) {

        double w = 0;
        double r = 0;
        double g = 0;
        double b = 0;
        double a = 0;

        for (int j = lo; j < hi; j++) {
            final int k = ord[j];
            final double c = cnt[k];
            w += c;
            r += c * ch[0][k];
            g += c * ch[1][k];
            b += c * ch[2][k];
            a += c * ch[3][k];
        }

        if (w <= 0) return;

        cr[i] = r / w;
        cg[i] = g / w;
        cb[i] = b / w;
        ca[i] = a / w;
    }

    /** Assign every bucket to its nearest centroid, keeping a reserved clear entry fixed. */
    private static boolean assign(final double[][] ch, final int nb, final int[] map, final int nbox, final int first, //
        final double[] cr, final double[] cg, final double[] cb, final double[] ca, final boolean parallel, final int pixels) {

        final var pr = ch[0];
        final var pg = ch[1];
        final var pb = ch[2];
        final var pa = ch[3];
        final var changed = new AtomicBoolean();
        final var neighbors = new long[nbox][nbox - first];

        // Start at the previous assignment. Any better centroid must lie within twice that
        // point-to-centroid distance, by the triangle inequality. Sort once per centroid, not
        // once per bucket, and stop scanning when the remaining candidates are beyond it.
        for (int i = first; i < nbox; i++) {
            final var order = neighbors[i];
            for (int j = first; j < nbox; j++) {
                final double dr = cr[i] - cr[j];
                final double dg = cg[i] - cg[j];
                final double db = cb[i] - cb[j];
                final double da = ca[i] - ca[j];
                // Nonnegative IEEE doubles sort in bit order. Reserve the bottom 8 mantissa
                // bits for the palette index (at most 255), rounding the bound down. Primitive
                // sorting avoids boxing 65536 distances on every refinement pass.
                final double d = dr * dr + dg * dg + db * db + da * da;
                order[j - first] = (Double.doubleToLongBits(d) & ~0xFFL) | j;
            }
            Arrays.sort(order);
        }

        // Every bucket is independent; shared centroid distances and neighbor orders are read-only.
        IroMisc.scan(parallel, nb, pixels, (from, to) -> {
            boolean moved = false;
            for (int k = from; k < to; k++) {
                if (first != 0 && pa[k] == 0) continue; // reserved clear entry never moves
                final double r = pr[k];
                final double g = pg[k];
                final double b = pb[k];
                final double a = pa[k];

                final int previous = map[k];
                final double rr = r - cr[previous];
                final double rg = g - cg[previous];
                final double rb = b - cb[previous];
                final double ra = a - ca[previous];
                int pick = previous;
                double best = rr * rr + rg * rg + rb * rb + ra * ra;
                // Keep this bound tied to the original centroid even after finding a better one.
                // The small outward tolerance prevents floating-point roundoff pruning a tie.
                final long bound = Double.doubleToLongBits(4 * best * (1 + 1e-12) + 1e-12) | 0xFFL;

                for (final long candidate: neighbors[previous]) {
                    if (bound < candidate) break;
                    final int i = (int) (candidate & 0xFF);
                    if (i == previous) continue;
                    final double dr = r - cr[i];
                    final double dg = g - cg[i];
                    final double db = b - cb[i];
                    final double da = a - ca[i];

                    final double d = dr * dr + dg * dg + db * db + da * da;
                    if (d < best || (d == best && i < pick)) {
                        best = d;
                        pick = i;
                    }
                }

                moved |= map[k] != pick;
                map[k] = pick;
            }
            if (moved) changed.set(true);
        });
        return changed.get();
    }

    private static void recentroid(final double[][] ch, final int[] cnt, final int nb, final int[] map, final int nbox, //
        final double[] cr, final double[] cg, final double[] cb, final double[] ca) {

        final var w = new double[nbox];
        final var r = new double[nbox];
        final var g = new double[nbox];
        final var b = new double[nbox];
        final var a = new double[nbox];

        for (int k = 0; k < nb; k++) {
            final int i = map[k];
            final double c = cnt[k];
            w[i] += c;
            r[i] += c * ch[0][k];
            g[i] += c * ch[1][k];
            b[i] += c * ch[2][k];
            a[i] += c * ch[3][k];
        }

        for (int i = 0; i < nbox; i++) {
            if (w[i] <= 0) continue; // an emptied cluster keeps its previous centroid

            cr[i] = r[i] / w[i];
            cg[i] = g[i] / w[i];
            cb[i] = b[i] / w[i];
            ca[i] = a[i] / w[i];
        }
    }

    // ------------------------------------------------------------------ indexing

    /**
     * Map the pixels onto palette indices, dropping unused entries and moving the ones with
     * alpha &lt; 255 to the front along the way. That shortens the tRNS chunk, and dropping to
     * 16 or fewer entries also lowers the bit depth.
     */
    private static Result index(final int[] argb, final _Hist hist, final int[] map, final int[] pal, final boolean lossless, //
        final int width, final int height, final Opts o) {

        final var used = new boolean[pal.length];
        for (int i = 0; i < hist.n; i++) used[map[i]] = true;

        final var remap = new int[pal.length];
        final var sorted = new int[pal.length];
        int n = 0;

        for (int i = 0; i < pal.length; i++) // translucent first
            if (used[i] && (pal[i] >>> 24) != 0xFF) {
                remap[i] = n;
                sorted[n++] = pal[i];
            }
        for (int i = 0; i < pal.length; i++) // opaque last
            if (used[i] && (pal[i] >>> 24) == 0xFF) {
                remap[i] = n;
                sorted[n++] = pal[i];
            }

        System.arraycopy(sorted, 0, pal, 0, n);

        final var palette = (n < pal.length) ? Arrays.copyOf(pal, n) : pal;

        if (!lossless && 0 < o.dither()) return new Result(dither(argb, width, height, palette, o.dither()), palette, false);

        final var ret = new byte[argb.length];
        final var h = hist;

        IroMisc.bands(o.parallel(), width, height, (from, to) -> {
            for (int i = from * width, e = to * width; i < e; i++) //
                ret[i] = (byte) remap[map[find(h, key(argb[i], h.bits))]];
        });

        return new Result(ret, palette, lossless);
    }

    /**
     * Floyd-Steinberg, serpentine, in the same premultiplied space as palette selection.
     * Only color error is diffused; source alpha is used for nearest-entry lookup, not diffused.
     */
    private static byte[] dither(final int[] argb, final int width, final int height, final int[] pal, final double amount) {

        final var ret = new byte[width * height];
        final var nearest = new _Nearest(pal);

        // Two rows of error is all that is ever live. A one pixel margin on each side removes the
        // bounds test from the inner loop
        var cur = new float[(width + 2) * 3];
        var next = new float[(width + 2) * 3];

        for (int y = 0; y < height; y++) {

            final boolean back = (y & 1) == 1; // serpentine, so the error does not drift into stripes
            Arrays.fill(next, 0f);

            for (int i = 0; i < width; i++) {
                final int x = back ? width - 1 - i : i;
                final int o = y * width + x;
                final int c = argb[o];
                final int e = (x + 1) * 3;

                final int a = c >>> 24;
                double r = Math.max(0, Math.min(a, (c >>> 16 & 0xFF) * a / 255.0 + cur[e] * amount));
                double g = Math.max(0, Math.min(a, (c >>> 8 & 0xFF) * a / 255.0 + cur[e + 1] * amount));
                double b = Math.max(0, Math.min(a, (c & 0xFF) * a / 255.0 + cur[e + 2] * amount));
                if (a == 255) { // exact 8-bit cache keys on opaque pixels
                    r = round(r);
                    g = round(g);
                    b = round(b);
                }

                final int pick = nearest.of(a, r, g, b);
                ret[o] = (byte) pick;
                if (a == 0) continue; // hidden RGB must not leak into a visible neighbor

                final int p = pal[pick];
                final double pa = (p >>> 24) / 255.0;
                final float dr = (float) (r - (p >>> 16 & 0xFF) * pa);
                final float dg = (float) (g - (p >>> 8 & 0xFF) * pa);
                final float db = (float) (b - (p & 0xFF) * pa);

                final int fwd = back ? -3 : 3;
                spread(cur, e + fwd, dr, dg, db, 7 / 16f);
                spread(next, e - fwd, dr, dg, db, 3 / 16f);
                spread(next, e, dr, dg, db, 5 / 16f);
                spread(next, e + fwd, dr, dg, db, 1 / 16f);
            }

            final var swap = cur;
            cur = next;
            next = swap;
        }

        return ret;
    }

    private static void spread(final float[] buf, final int at, final float r, final float g, final float b, final float w) {

        if (at < 0 || buf.length <= at + 2) return;

        buf[at] += r * w;
        buf[at + 1] += g * w;
        buf[at + 2] += b * w;
    }

    /** Exact nearest entry; a bounded cache verifies the full RGB key rather than merging colors. */
    private static final class _Nearest {

        private final double[][] ch;

        private final short[] lut = new short[1 << 15];

        private final int[] keys = new int[1 << 15];

        _Nearest(final int[] pal) {

            this.ch = new double[4][pal.length];
            Arrays.fill(this.lut, (short) -1);

            for (int i = 0; i < pal.length; i++) {
                final int c = pal[i];
                final int a = c >>> 24;
                this.ch[0][i] = (c >>> 16 & 0xFF) * a / 255.0;
                this.ch[1][i] = (c >>> 8 & 0xFF) * a / 255.0;
                this.ch[2][i] = (c & 0xFF) * a / 255.0;
                this.ch[3][i] = a;
            }
        }

        int of(final int a, final double r, final double g, final double b) {

            if (a != 255) return this.brute(a, r, g, b); // retain fractional, premultiplied coordinates

            final int k = (int) r << 16 | (int) g << 8 | (int) b;
            final int slot = mix(k) & (this.lut.length - 1);
            final short hit = this.lut[slot];
            if (0 <= hit && this.keys[slot] == k) return hit;

            final int found = this.brute(0xFF, r, g, b);
            this.keys[slot] = k;
            this.lut[slot] = (short) found;

            return found;
        }

        private int brute(final int a, final double r, final double g, final double b) {

            int pick = 0;
            double best = Double.MAX_VALUE;

            for (int i = 0; i < this.ch[0].length; i++) {
                final double dr = r - this.ch[0][i];
                final double dg = g - this.ch[1][i];
                final double db = b - this.ch[2][i];
                final double da = a - this.ch[3][i];

                final double d = dr * dr + dg * dg + db * db + da * da;
                if (d < best) {
                    best = d;
                    pick = i;
                }
            }

            return pick;
        }
    }

    // ------------------------------------------------------------------ histogram

    /**
     * Color histogram. Representatives are kept as sums of alpha-premultiplied components.
     * <p>
     * The key is ARGB itself, or rounded to {@code bits} per channel on the coarse path. Fully
     * transparent pixels have meaningless RGB, so they all collapse onto a single color.
     */
    private static final class _Hist {

        int n;

        int[] cnt;

        long[] sr;

        long[] sg;

        long[] sb;

        long[] sa;

        int[] hkey;

        int[] hval; // 0 = empty, otherwise bucket number + 1

        int hmask;

        int bits; // 0 = exact ARGB, 6 = opaque RGB, 5 = RGBA
    }

    private static int key(final int argb, final int bits) {

        final int a = argb >>> 24;
        if (a == 0) return 0; // fully transparent collapses to one color whatever the RGB

        if (bits == 0) return argb;

        if (bits == 6) return (argb >>> 18 & 0x3F) << 12 | (argb >>> 10 & 0x3F) << 6 | (argb >>> 2 & 0x3F);

        // Keep alpha=0 separate from near-transparent black, including on the coarse path.
        return 1 + ((a >>> 3) << 15 | (argb >>> 19 & 0x1F) << 10 | (argb >>> 11 & 0x1F) << 5 | (argb >>> 3 & 0x1F));
    }

    /** @return {@code null} once the bucket count would exceed {@code limit} */
    private static _Hist histogram(final int[] argb, final byte[] imp, final int bits, final int limit) {

        final var h = new _Hist();
        h.bits = bits;
        h.hmask = 1023;
        h.hkey = new int[1024];
        h.hval = new int[1024];
        h.cnt = new int[512];
        h.sr = new long[512];
        h.sg = new long[512];
        h.sb = new long[512];
        h.sa = new long[512];

        for (int i = 0; i < argb.length; i++) {
            final int px = argb[i];
            final int b = bucket(h, key(px, bits), limit);
            if (b < 0) return null;

            add(h, b, px, (imp == null) ? 1 : imp[i]);
        }

        return h;
    }

    /** @return the bucket number, or -1 once {@code limit} would be exceeded */
    private static int bucket(final _Hist h, final int k, final int limit) {

        int i = mix(k) & h.hmask;
        while (true) {
            final int v = h.hval[i];
            if (v == 0) break;
            if (h.hkey[i] == k) return v - 1;

            i = (i + 1) & h.hmask;
        }

        if (limit <= h.n) return -1;

        if (h.cnt.length <= h.n) {
            final int z = h.cnt.length * 2;
            h.cnt = Arrays.copyOf(h.cnt, z);
            h.sr = Arrays.copyOf(h.sr, z);
            h.sg = Arrays.copyOf(h.sg, z);
            h.sb = Arrays.copyOf(h.sb, z);
            h.sa = Arrays.copyOf(h.sa, z);
        }

        // Size against the real bucket count. Sizing against the pixel count costs tens of MB on photos
        if (h.hmask + 1 <= (h.n + 1) * 2) {
            rehash(h);
            i = mix(k) & h.hmask;
            while (h.hval[i] != 0) i = (i + 1) & h.hmask;
        }

        h.hkey[i] = k;
        h.hval[i] = h.n + 1;

        return h.n++;
    }

    private static void rehash(final _Hist h) {

        final int cap = (h.hmask + 1) * 2;
        final int mask = cap - 1;
        final var hkey = new int[cap];
        final var hval = new int[cap];

        for (int i = 0; i <= h.hmask; i++) {
            final int v = h.hval[i];
            if (v == 0) continue;

            int j = mix(h.hkey[i]) & mask;
            while (hval[j] != 0) j = (j + 1) & mask;

            hkey[j] = h.hkey[i];
            hval[j] = v;
        }

        h.hkey = hkey;
        h.hval = hval;
        h.hmask = mask;
    }

    private static void add(final _Hist h, final int i, final int px, final int w) {

        final int a = px >>> 24;
        final int aw = a * w;

        h.cnt[i] += w;
        h.sa[i] += aw;
        h.sr[i] += (long) (px >>> 16 & 0xFF) * aw; // premultiplied, kept scaled by 255
        h.sg[i] += (long) (px >>> 8 & 0xFF) * aw;
        h.sb[i] += (long) (px & 0xFF) * aw;
    }

    /**
     * The residual, in premultiplied 8-bit levels, at which a pixel's extra weight halves. Sensor
     * and JPEG noise of a few levels stays near full weight; any edge the eye resolves is well past it.
     */
    private static final double DETAIL = 12;

    /**
     * How much each pixel should count when the palette is chosen.
     * <p>
     * Counting every pixel once spends the palette where the pixels are, which is not where the eye
     * looks. A quantization step inside a smooth region reads as a band; the same step inside
     * detail is invisible. So a pixel counts more the better its surroundings are described by a
     * plane. A ramp, however steep, is a plane and counts in full -- it is exactly where bands
     * show -- while texture, noise and edges leave a residual and count less.
     * <p>
     * The measure is the residual of a least-squares plane through the pixel's 3x3 neighborhood,
     * per degree of freedom and summed over the premultiplied RGBA channels, so hidden RGB never
     * changes a visible pixel's weight. The weight falls off with it as {@code 1 / (1 + (d / DETAIL)^2)}.
     * A flat area that runs up against an edge bands along it, so each weight finally takes the
     * smallest of its 3x3 neighborhood: the pixels next to an edge count as detail too.
     *
     * @param strength how much heavier a flat pixel counts than one in detail
     */
    private static byte[] importance(final int[] argb, final int width, final int height, final double strength, final boolean parallel) {

        final var ret = new byte[argb.length];

        IroMisc.bands(parallel, width, height, (from, to) -> {
            final var rows = new int[3];

            for (int y = from; y < to; y++) {
                rows[0] = Math.max(0, y - 1) * width;
                rows[1] = y * width;
                rows[2] = Math.min(height - 1, y + 1) * width;

                for (int x = 0; x < width; x++) {
                    final int xl = Math.max(0, x - 1);
                    final int xr = Math.min(width - 1, x + 1);

                    // For a plane a + b*u + c*v over u, v in {-1, 0, 1}, the unexplained sum of squares
                    // is sum(p^2) - sum(p)^2 / 9 - (sum(u*p)^2 + sum(v*p)^2) / 6
                    double residual = 0;
                    for (int shift = 0; shift <= 24; shift += 8) {
                        double sum = 0;
                        double squares = 0;
                        double alongU = 0;
                        double alongV = 0;

                        for (int v = 0; v < 3; v++)
                            for (int u = 0; u < 3; u++) {
                                final double p = premultiplied(argb[rows[v] + (u == 0 ? xl : u == 1 ? x : xr)], shift);
                                sum += p;
                                squares += p * p;
                                alongU += (u - 1) * p;
                                alongV += (v - 1) * p;
                            }

                        residual += squares - sum * sum / 9 - (alongU * alongU + alongV * alongV) / 6;
                    }

                    final double d = Math.sqrt(Math.max(0, residual) / 6) / DETAIL;

                    // Opts caps strength at MAX_IMPORTANCE so this stays inside a signed byte
                    ret[rows[1] + x] = (byte) (1 + (int) (strength / (1 + d * d) + 0.5));
                }
            }
        });

        return erode(ret, width, height, parallel);
    }

    /** One premultiplied channel of an ARGB pixel, 0 to 255: alpha at {@code shift} 24, else R, G or B times alpha. */
    private static double premultiplied(final int argb, final int shift) {
        final int a = argb >>> 24;
        return (shift == 24) ? a : (argb >>> shift & 0xFF) * a / 255.0;
    }

    /** 3x3 minimum filter. */
    private static byte[] erode(final byte[] src, final int width, final int height, final boolean parallel) {

        final var ret = new byte[src.length];

        IroMisc.bands(parallel, width, height, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int y0 = Math.max(0, y - 1);
                final int y1 = Math.min(height - 1, y + 1);

                for (int x = 0; x < width; x++) {
                    final int x0 = Math.max(0, x - 1);
                    final int x1 = Math.min(width - 1, x + 1);

                    int m = Byte.MAX_VALUE;
                    for (int j = y0; j <= y1; j++) for (int i = x0; i <= x1; i++) m = Math.min(m, src[j * width + i]);

                    ret[y * width + x] = (byte) m;
                }
            }
        });

        return ret;
    }

    private static int find(final _Hist h, final int k) {

        int i = mix(k) & h.hmask;
        while (true) {
            final int v = h.hval[i];
            if (v == 0) return 0; // unreachable: every key was registered in the first pass
            if (h.hkey[i] == k) return v - 1;
            i = (i + 1) & h.hmask;
        }
    }

    private static int mix(final int x) {
        final int h = x * 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    private static int round(final double x) { return (int) (x + 0.5); }
}
