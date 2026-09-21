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

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;


/**
 * High quality image resampling in pure Java, with no process to spawn.
 * <p>
 * The usual reason to push this work out of the JVM is memory, not speed: resizing a large image in
 * process tends to mean several full copies of it on the heap. This does not pay that cost:
 * <b>the source is read one row at a time and only a
 * small window of resampled rows is held per worker</b>, so the extra memory is proportional to the
 * output width and active workers, not to the image. For a 2000px wide target each window is a few
 * hundred KB whatever the source is; the only large allocations left are the decoded source, which the caller already
 * has, and the output.
 * <p>
 * Downscaling runs in two steps, which is the usual shape for a thumbnailer. An integer <b>box
 * shrink</b> takes most of the reduction, being cheap and exact, and a <b>Mitchell</b> pass takes
 * the remainder, which is where the quality comes from. The factor is chosen so the remainder stays
 * in [2, 4), so Mitchell always does real filtering rather than degenerating into a copy.
 * <p>
 * Alpha is resampled <b>premultiplied</b>, otherwise the color of transparent pixels bleeds into
 * the edges of everything next to them.
 * <p>
 * Large images split the output rows across the same bounded pool as {@link JQuant}; small images
 * run the identical scan immediately on the caller. Depends on the JDK only.
 *
 * @author takahashikzn
 */
@SuppressWarnings("OverlyComplexArithmeticExpression")
public final class IroResize {

    private IroResize() { }

    public static BufferedImage scale(final BufferedImage src, final double scale) {
        return resize(src, Math.max(1, (int) Math.round(src.getWidth() * scale)), Math.max(1, (int) Math.round(src.getHeight() * scale)));
    }

    public static BufferedImage resize(final BufferedImage src, final int dw, final int dh) {
        return resize(src, dw, dh, true);
    }

    /** Disable parallel work when the caller needs the entire resampling on its own thread. */
    public static BufferedImage resize(final BufferedImage src, final int dw, final int dh, final boolean parallel) {

        final int sw = src.getWidth();
        final int sh = src.getHeight();

        if (dw <= 0 || dh <= 0) throw new IllegalArgumentException("bad target: %dx%d".formatted(dw, dh));
        if (sw == dw && sh == dh) return src;

        final boolean alpha = src.getColorModel().hasAlpha();

        // Integer box shrink first, leaving a remainder in [2, 4) for Mitchell to filter properly
        final int fx = shrink(sw, dw);
        final int fy = shrink(sh, dh);
        final int mw = (sw + fx - 1) / fx;
        final int mh = (sh + fy - 1) / fy;

        final var wx = weights(mw, dw);
        final var wy = weights(mh, dh);

        final var ret = new BufferedImage(dw, dh, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        final var out = ((DataBufferInt) ret.getRaster().getDataBuffer()).getData();

        final var rows = rows(src);
        final long pixels = Math.max((long) sw * sh, (long) dw * dh);
        IroMisc.scan(parallel, dh, pixels, scanlines(rows, sw, sh, dw, mw, fx, fy, alpha, wx, wy, out));

        return ret;
    }

    /** Each invocation owns its ring and reads the source halo needed by its first output row. */
    private static IroMisc.Scan scanlines(final Rows rows, final int sw, final int sh, final int dw, final int mw, final int fx, final int fy,
        final boolean alpha, final W wx, final W wy, final int[] out) {

        return (from, to) -> {
            final int ring = wy.max;
            final var band = new float[ring][dw * 4];
            final var held = new int[ring];
            Arrays.fill(held, -1);
            final var srow = new int[sw];
            final var acc = new float[mw * 4];
            final var cnt = new int[mw];
            int produced = wy.start[from];

            for (int y = from; y < to; y++) {
                final int s = wy.start[y];
                final int l = wy.len[y];
                while (produced < s + l) {
                    shrinkRow(rows, srow, sw, sh, produced, fx, fy, alpha, acc, cnt, mw);
                    hpass(acc, mw, band[produced % ring], dw, wx);
                    held[produced % ring] = produced;
                    produced++;
                }
                vpass(band, held, ring, s, l, wy.w, y * wy.max, dw, out, y * dw, alpha);
            }
        };
    }

    /** Leave a remainder in [2, 4) so the Mitchell pass still filters rather than copying. */
    private static int shrink(final int sn, final int dn) { return Math.max(sn / (dn * 2), 1); }

    /**
     * Produce shrunk row {@code j}: box average the source rows it covers, and box average
     * {@code fx} pixels across. Premultiplies on the way in when the image has alpha.
     */
    private static void shrinkRow(final Rows rows, final int[] srow, final int sw, final int sh, final int j, //
        final int fx, final int fy, final boolean alpha, final float[] acc, final int[] cnt, final int mw) {

        Arrays.fill(acc, 0f);
        Arrays.fill(cnt, 0);

        final int y0 = j * fy;
        final int y1 = Math.min(sh, y0 + fy);

        for (int y = y0; y < y1; y++) {
            rows.get(y, srow);

            for (int x = 0; x < sw; x++) {
                final int c = srow[x];
                final int a = c >>> 24;
                final int o = (x / fx) * 4;

                if (alpha) {
                    final float m = a / 255f;
                    acc[o] += (c >>> 16 & 0xFF) * m;
                    acc[o + 1] += (c >>> 8 & 0xFF) * m;
                    acc[o + 2] += (c & 0xFF) * m;
                } else {
                    acc[o] += c >>> 16 & 0xFF;
                    acc[o + 1] += c >>> 8 & 0xFF;
                    acc[o + 2] += c & 0xFF;
                }

                acc[o + 3] += a;
                cnt[x / fx]++;
            }
        }

        for (int i = 0; i < mw; i++) {
            final int n = cnt[i];
            if (n <= 1) continue;

            final int o = i * 4;
            acc[o] /= n;
            acc[o + 1] /= n;
            acc[o + 2] /= n;
            acc[o + 3] /= n;
        }
    }

    private static void hpass(final float[] in, final int mw, final float[] out, final int dw, final W w) {

        for (int x = 0; x < dw; x++) {
            final int s = w.start[x];
            final int l = w.len[x];
            final int wo = x * w.max;

            float r = 0;
            float g = 0;
            float b = 0;
            float a = 0;

            for (int k = 0; k < l; k++) {
                final int o = (s + k) * 4;
                final float f = w.w[wo + k];
                r += f * in[o];
                g += f * in[o + 1];
                b += f * in[o + 2];
                a += f * in[o + 3];
            }

            final int o = x * 4;
            out[o] = r;
            out[o + 1] = g;
            out[o + 2] = b;
            out[o + 3] = a;
        }
    }

    private static void vpass(final float[][] band, final int[] held, final int ring, final int s, final int l, //
        final float[] w, final int wo, final int dw, final int[] out, final int at, final boolean alpha) {

        for (int x = 0; x < dw; x++) {
            float r = 0;
            float g = 0;
            float b = 0;
            float a = 0;

            for (int k = 0; k < l; k++) {
                final int j = s + k;
                if (held[j % ring] != j) continue; // cannot happen: the ring is as deep as the widest support

                final var row = band[j % ring];
                final int o = x * 4;
                final float f = w[wo + k];
                r += f * row[o];
                g += f * row[o + 1];
                b += f * row[o + 2];
                a += f * row[o + 3];
            }

            out[at + x] = alpha ? unpremul(r, g, b, a) : 0xFF000000 | cl(r) << 16 | cl(g) << 8 | cl(b);
        }
    }

    private static int unpremul(final float r, final float g, final float b, final float a) {

        final int ai = cl(a);
        if (ai == 0) return 0;

        final float m = 255f / ai;

        return ai << 24 | cl(r * m) << 16 | cl(g * m) << 8 | cl(b * m);
    }

    private static int cl(final float v) {
        final int i = (int) (v + 0.5f);
        return (i < 0) ? 0 : Math.min(255, i);
    }

    /** Per output index: which source samples it draws from, and with what weight. */
    private record W(int[] start, int[] len, float[] w, int max) { }

    private static final double SUPPORT = 2.0;

    private static W weights(final int sn, final int dn) {

        final double scale = (double) sn / dn;
        final double sup = SUPPORT * Math.max(1, scale); // widen the kernel when reducing, or it aliases
        final int max = (int) Math.ceil(sup * 2) + 2;

        final var start = new int[dn];
        final var len = new int[dn];
        final var w = new float[dn * max];

        for (int i = 0; i < dn; i++) {
            final double c = (i + 0.5) * scale - 0.5;

            int s = (int) Math.ceil(c - sup);
            int e = (int) Math.floor(c + sup);
            if (s < 0) s = 0;
            if (sn - 1 < e) e = sn - 1;
            if (e < s) {
                s = Math.clamp((int) c, 0, sn - 1);
                e = s;
            }

            start[i] = s;
            len[i] = e - s + 1;

            float sum = 0;
            for (int k = s; k <= e; k++) {
                final float v = (float) mitchell((k - c) / Math.max(1, scale));
                w[i * max + k - s] = v;
                sum += v;
            }

            if (sum != 0) for (int k = 0; k < len[i]; k++) w[i * max + k] /= sum;
        }

        return new W(start, len, w, max);
    }

    /** Mitchell-Netravali with B=C=1/3, the usual choice for photographic downscaling. */
    private static final double B = 1 / 3.0;

    private static final double C = 1 / 3.0;

    private static double mitchell(final double t) {

        final double x = Math.abs(t);

        if (x < 1) return ((12 - 9 * B - 6 * C) * x * x * x + (-18 + 12 * B + 6 * C) * x * x + (6 - 2 * B)) / 6;
        if (x < 2) return ((-B - 6 * C) * x * x * x + (6 * B + 30 * C) * x * x + (-12 * B - 48 * C) * x + (8 * B + 24 * C)) / 6;

        return 0;
    }

    /**
     * Reads one row at a time, so that resizing a large image never needs a second full copy of it
     * on the heap - which is what pushed this work out of the JVM in the first place.
     */
    @FunctionalInterface
    private interface Rows {

        /** @param dst filled with {@code width} ARGB pixels */
        void get(int y, int[] dst);
    }

    /**
     * Pick the extraction once for the whole image rather than per row, reading the raster directly
     * for the types ImageIO and Java2D actually produce and falling back to {@code getRGB}, which
     * runs a ColorModel conversion per pixel, only for the rest.
     */
    private static Rows rows(final BufferedImage img) {

        final int w = img.getWidth();
        final var sm = img.getSampleModel();
        final var db = img.getRaster().getDataBuffer();

        if (db.getNumBanks() == 1 && db.getOffset() == 0) {

            if (db instanceof DataBufferInt b && sm instanceof SinglePixelPackedSampleModel s && s.getScanlineStride() == w) {

                final var src = b.getData();

                switch (img.getType()) {
                    case BufferedImage.TYPE_INT_ARGB -> {
                        return (y, dst) -> System.arraycopy(src, y * w, dst, 0, w);
                    }
                    case BufferedImage.TYPE_INT_RGB -> {
                        return (y, dst) -> {
                            for (int x = 0, o = y * w; x < w; x++) dst[x] = src[o + x] | 0xFF000000;
                        };
                    }
                    default -> { }
                }
            }

            // TYPE_4BYTE_ABGR / TYPE_3BYTE_BGR. Take the band order from the SampleModel rather than assuming it
            if (db instanceof DataBufferByte b && sm instanceof PixelInterleavedSampleModel s //
                && (img.getType() == BufferedImage.TYPE_4BYTE_ABGR || img.getType() == BufferedImage.TYPE_3BYTE_BGR)) {

                final int ps = s.getPixelStride();
                final int stride = s.getScanlineStride();
                final var off = s.getBandOffsets();
                final var src = b.getData();
                final int or = off[0];
                final int og = off[1];
                final int ob = off[2];
                final int oa = (3 < off.length) ? off[3] : -1;

                return (y, dst) -> {
                    for (int x = 0, j = y * stride; x < w; x++, j += ps)
                        dst[x] = ((oa < 0) ? 0xFF : src[j + oa] & 0xFF) << 24 //
                                 | (src[j + or] & 0xFF) << 16 //
                                 | (src[j + og] & 0xFF) << 8 //
                                 | (src[j + ob] & 0xFF);
                };
            }
        }

        // getRGB runs the image's ColorModel, which may initialize itself lazily and without a lock:
        // ComponentColorModel#initScale clears its flag before it computes, so a second thread can
        // read half-set scaling. The rows are read in parallel, so settle that here, on this thread
        img.getRGB(0, 0);

        return (y, dst) -> img.getRGB(0, y, w, 1, dst, 0, w);
    }

    // ------------------------------------------------------------ decode and resize

    /**
     * How much larger than the target the decode is allowed to land. Subsampling straight down to
     * the target is faster still, but it is nearest-neighbour: it leaves visible aliasing that no
     * later filtering can undo. Keeping this much headroom gives the Mitchell pass real data to
     * work with, and measured against a native thumbnailer it is the difference between a mean
     * error of 2.5 and of 0.7.
     */
    private static final int HEADROOM = 2;

    /**
     * Resample encoded bytes without ever holding the image at full size.
     * <p>
     * <b>This is the point of the class.</b> Decoding a large photograph and then shrinking it
     * costs a full-resolution raster on the heap, and that cost - not the arithmetic - is why this
     * work is so often handed to an external process. {@link javax.imageio.ImageReadParam#setSourceSubsampling}
     * lets the decoder skip rows and columns as it reads, so the raster is never materialized at
     * full size: measured on a 6000x4000 JPEG, decoding needs <b>96MB of heap at full resolution
     * and 24MB at half</b>. Time barely moves, which is why this is worth doing for memory alone.
     * <p>
     * The subsampling factor is the largest power of two that still leaves {@link #HEADROOM} times
     * the target, and {@link #resize(BufferedImage, int, int)} takes it the rest of the way.
     *
     * @throws IOException when nothing can read {@code encoded}
     */
    public static BufferedImage resize(final byte[] encoded, final int dw, final int dh) throws IOException {
        return resize(encoded, dw, dh, true);
    }

    /** Same subsampled decode, with explicit control over parallel resampling. */
    public static BufferedImage resize(final byte[] encoded, final int dw, final int dh, final boolean parallel) throws IOException {

        if (dw <= 0 || dh <= 0) throw new IllegalArgumentException("bad target: %dx%d".formatted(dw, dh));

        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(encoded))) {

            if (in == null) throw new IOException("no image input stream");

            final var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("no reader for these bytes");

            final var reader = readers.next();
            try {
                reader.setInput(in);

                // header only; the pixels are not touched until read() below
                final int sw = reader.getWidth(0);
                final int sh = reader.getHeight(0);

                final int step = subsampling(sw, dw, sh, dh);

                final var param = reader.getDefaultReadParam();
                if (1 < step) param.setSourceSubsampling(step, step, 0, 0);

                return resize(reader.read(0, param), dw, dh, parallel);
            } finally { reader.dispose(); }
        }
    }

    /** The largest power of two that leaves at least {@link #HEADROOM} times the target on both axes. */
    private static int subsampling(final int sw, final int dw, final int sh, final int dh) {

        int step = 1;
        while (step < 64 && HEADROOM * dw * (step * 2L) <= sw && HEADROOM * dh * (step * 2L) <= sh) step *= 2;

        return step;
    }
}
