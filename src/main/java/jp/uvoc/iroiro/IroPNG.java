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

import java.io.ByteArrayOutputStream;
import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;


/**
 * Writes a palette PNG (color type 3). Bypassing ImageIO's PNGWriter is what lets us pick the
 * bit depth and truncate the tRNS chunk ourselves.
 */
final class IroPNG {

    private static final Logger log = System.getLogger(IroPNG.class.getName());

    private IroPNG() { }

    private static final byte[] SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

    static byte[] indexed(final byte[] index, final int[] palette, final int width, final int height, final int level, final boolean parallel) {

        final int depth = depth(palette.length);

        final var out = new ByteArrayOutputStream(Math.max(1024, index.length / 2));
        out.writeBytes(SIGNATURE);

        chunk(out, "IHDR", ihdr(width, height, depth, 3));
        colorspace(out);
        chunk(out, "PLTE", plte(palette));

        final var trns = trns(palette);
        if (0 < trns.length) chunk(out, "tRNS", trns);

        chunk(out, "IDAT", idat(index, width, height, depth, level, parallel));
        chunk(out, "IEND", new byte[0]);

        return out.toByteArray();
    }

    /**
     * Writes a truecolor PNG (color type 2 or 6) from the reduced pixels. Used for the lossy
     * case: some products will not consider JPEG for a palette PNG when exporting a PDF, and
     * for a photograph that costs far more in the PDF than palettizing saves in the PNG.
     */
    static byte[] truecolor(final byte[] index, final int[] palette, final int width, final int height, final int level, final boolean parallel) {

        boolean alpha = false;
        for (final int c: palette)
            if ((c >>> 24) != 0xFF) {
                alpha = true;
                break;
            }

        final var out = new ByteArrayOutputStream(Math.max(1024, index.length));
        out.writeBytes(SIGNATURE);

        chunk(out, "IHDR", ihdr(width, height, 8, alpha ? 6 : 2));
        colorspace(out);
        chunk(out, "IDAT", idatTruecolor(index, palette, width, height, alpha ? 4 : 3, level, parallel));
        chunk(out, "IEND", new byte[0]);

        return out.toByteArray();
    }

    /**
     * Deflate the truecolor scanlines both ways and keep the smaller. Per-row filtering is what
     * makes a photograph compress, but switching filters between rows breaks up the long range
     * matches that periodic, synthetic images live on, where it can lose badly to no filtering
     * at all. One extra deflate is cheap insurance against that.
     */
    private static byte[] idatTruecolor(final byte[] index, final int[] palette, final int width, final int height, final int bpp, final int level,
        final boolean parallel) {

        final int stride = width * bpp;
        final var raw = new byte[(stride + 1) * height];
        IroMisc.bands(parallel, width, height, scanlines(index, palette, width, bpp, true, raw));
        final var filtered = deflate(raw, level);
        IroMisc.bands(parallel, width, height, scanlines(index, palette, width, bpp, false, raw));
        final var plain = deflate(raw, level);

        return (filtered.length <= plain.length) ? filtered : plain;
    }

    /**
     * Build the scanlines. With {@code filter}, each row is tried with all five PNG filters and
     * the one with the smallest sum of absolute signed bytes wins, the heuristic libpng uses.
     */
    private static IroMisc.Scan scanlines(final byte[] index, final int[] palette, final int width, final int bpp, final boolean filter, final byte[] raw) {

        final int stride = width * bpp;
        return (from, to) -> {
            var cur = new byte[stride];
            var prev = filter ? new byte[stride] : null;
            final var cand = filter ? new byte[5][stride] : null;
            // Filters refer to the preceding source row, even at a worker's first row.
            if (filter && from > 0) unpack(index, palette, (from - 1) * width, width, bpp, prev);

            for (int y = from; y < to; y++) {
                final int dst = y * (stride + 1);
                unpack(index, palette, y * width, width, bpp, cur);
                if (!filter) {
                    raw[dst] = 0;
                    System.arraycopy(cur, 0, raw, dst + 1, stride);
                    continue;
                }

                int pick = 0;
                long best = Long.MAX_VALUE;
                for (int f = 0; f < 5; f++) {
                    final var t = cand[f];
                    long sum = 0;
                    for (int i = 0; i < stride; i++) {
                        final int a = (bpp <= i) ? cur[i - bpp] & 0xFF : 0;
                        final int b = prev[i] & 0xFF;
                        final int c = (bpp <= i) ? prev[i - bpp] & 0xFF : 0;
                        final int v = cur[i] & 0xFF;
                        final int d = switch (f) {
                            case 1 -> v - a;
                            case 2 -> v - b;
                            case 3 -> v - ((a + b) >> 1);
                            case 4 -> v - paeth(a, b, c);
                            default -> v;
                        } & 0xFF;
                        t[i] = (byte) d;
                        sum += (d < 128) ? d : 256 - d;
                    }
                    if (sum < best) {
                        best = sum;
                        pick = f;
                    }
                }
                raw[dst] = (byte) pick;
                System.arraycopy(cand[pick], 0, raw, dst + 1, stride);
                final var swap = prev;
                prev = cur;
                cur = swap;
            }
        };
    }

    private static void unpack(final byte[] index, final int[] palette, final int src, final int width, final int bpp, final byte[] row) {
        for (int x = 0, at = 0; x < width; x++) {
            final int c = palette[index[src + x] & 0xFF];
            row[at++] = (byte) (c >>> 16);
            row[at++] = (byte) (c >>> 8);
            row[at++] = (byte) c;
            if (bpp == 4) row[at++] = (byte) (c >>> 24);
        }
    }

    private static int paeth(final int a, final int b, final int c) {

        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);

        return (pa <= pb && pa <= pc) ? a : (pb <= pc) ? b : c;
    }

    /**
     * Say the output is sRGB. Untagged, a color managed viewer is free to assume something
     * else and the color shifts even though the pixels are right.
     */
    static void colorspace(final ByteArrayOutputStream out) {

        chunk(out, "sRGB", new byte[] { 0 }); // perceptual

        final var g = new byte[4];
        putInt(g, 0, 45455); // 1/2.2, what every writer pairs with sRGB
        chunk(out, "gAMA", g);
    }

    /** Ceiling on an inflated profile. Real ones are kilobytes; this is already far past any of them. */
    private static final int ICCP_MAX = 1 << 23;

    /** The ICC profile a PNG carries in {@code iCCP}, or null. */
    static byte[] iccp(final byte[] png) {

        try {
            fail:
            for (int i = 8; i + 12 <= png.length; ) {
                final int len = (png[i] & 0xFF) << 24 | (png[i + 1] & 0xFF) << 16 | (png[i + 2] & 0xFF) << 8 | png[i + 3] & 0xFF;
                if (len < 0 || png.length < i + 12 + len) break;

                if (isIDAT(png, i)) break; // never appears after the pixels

                if (isICCP(png, i)) {
                    int at = i + 8;
                    final int end = at + len;
                    while (at < end && png[at] != 0) at++; // profile name
                    at += 2; // NUL + compression method (0 = deflate)
                    if (end <= at) break;

                    try (var inf = new Inflater()) {
                        inf.setInput(png, at, end - at);
                        final var bo = new ByteArrayOutputStream(1 << 14);
                        final var buf = new byte[1 << 13];
                        int total = 0;
                        while (!inf.finished()) {
                            final int n = inf.inflate(buf);
                            if (n == 0) break;

                            // A few KB of deflated data can expand without bound. Past this it is a
                            // decompression bomb rather than a profile, and the heap it would take is
                            // an Error the caller's fallback cannot catch
                            if (ICCP_MAX < (total += n)) break fail;

                            bo.write(buf, 0, n);
                        }
                        return bo.toByteArray();
                    }
                }

                i += 12 + len;
            }
        } catch (final Exception e) {
            // a corrupt deflate stream in a broken or crafted file, the rest being bounds checked
            log.log(Logger.Level.WARNING, "unreadable iCCP, leaving the pixels alone", e);
        }

        return null; // fail
    }

    private static boolean isIDAT(final byte[] png, final int i) {
        return png[i + 4] == 'I' && png[i + 5] == 'D' && png[i + 6] == 'A' && png[i + 7] == 'T';
    }

    private static boolean isICCP(final byte[] png, final int i) {
        return png[i + 4] == 'i' && png[i + 5] == 'C' && png[i + 6] == 'C' && png[i + 7] == 'P';
    }

    private static int depth(final int colors) {
        if (colors <= 2) return 1;
        if (colors <= 4) return 2;
        if (colors <= 16) return 4;
        return 8;
    }

    private static byte[] ihdr(final int width, final int height, final int depth, final int colorType) {

        final var ret = new byte[13];
        putInt(ret, 0, width);
        putInt(ret, 4, height);
        ret[8] = (byte) depth;
        ret[9] = (byte) colorType; // 2 = RGB, 3 = palette, 6 = RGBA
        ret[10] = 0; // compression: deflate
        ret[11] = 0; // filter
        ret[12] = 0; // interlace: none

        return ret;
    }

    private static byte[] plte(final int[] palette) {

        final var ret = new byte[palette.length * 3];
        for (int i = 0, j = 0; i < palette.length; i++) {
            ret[j++] = (byte) (palette[i] >>> 16);
            ret[j++] = (byte) (palette[i] >>> 8);
            ret[j++] = (byte) palette[i];
        }

        return ret;
    }

    /** Translucent entries sit at the front, so the trailing opaque ones need not be written. */
    private static byte[] trns(final int[] palette) {

        int n = palette.length;
        while (0 < n && (palette[n - 1] >>> 24) == 0xFF) n--;

        final var ret = new byte[n];
        for (int i = 0; i < n; i++) ret[i] = (byte) (palette[i] >>> 24);

        return ret;
    }

    private static byte[] idat(final byte[] index, final int width, final int height, final int depth, final int level, final boolean parallel) {

        final int stride = (width * depth + 7) / 8;
        final var raw = new byte[(stride + 1) * height];
        IroMisc.bands(parallel, width, height, packedScanlines(index, width, depth, stride, raw));
        return deflate(raw, level);
    }

    private static IroMisc.Scan packedScanlines(final byte[] index, final int width, final int depth, final int stride, final byte[] raw) {
        return (from, to) -> {
            for (int y = from; y < to; y++) {
                final int src = y * width;
                int dst = y * (stride + 1);
                raw[dst++] = 0; // filter: None
                if (depth == 8) {
                    System.arraycopy(index, src, raw, dst, width);
                    continue;
                }
                final int ppb = 8 / depth;
                final int mask = (1 << depth) - 1;
                for (int x = 0; x < width; x += ppb) {
                    int acc = 0;
                    for (int i = 0; i < ppb; i++)
                        acc = (acc << depth) | ((x + i < width) ? index[src + x + i] & mask : 0);
                    raw[dst++] = (byte) acc;
                }
            }
        };
    }

    private static byte[] deflate(final byte[] raw, final int level) {

        try (var def = new Deflater(level)) {
            def.setInput(raw);
            def.finish();

            final var out = new ByteArrayOutputStream(raw.length / 4 + 64);
            final var buf = new byte[1024 * 64];
            while (!def.finished()) out.write(buf, 0, def.deflate(buf));

            return out.toByteArray();
        }
    }

    private static void chunk(final ByteArrayOutputStream out, final String type, final byte[] data) {

        final var len = new byte[4];
        putInt(len, 0, data.length);
        out.writeBytes(len);

        final var name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(name);
        out.writeBytes(data);

        final var crc = new CRC32();
        crc.update(name);
        crc.update(data);

        final var val = new byte[4];
        putInt(val, 0, (int) crc.getValue());
        out.writeBytes(val);
    }

    private static void putInt(final byte[] buf, final int at, final int val) {
        buf[at] = (byte) (val >>> 24);
        buf[at + 1] = (byte) (val >>> 16);
        buf[at + 2] = (byte) (val >>> 8);
        buf[at + 3] = (byte) val;
    }
}
