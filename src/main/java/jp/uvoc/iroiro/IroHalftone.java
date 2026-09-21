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


/**
 * Down to one bit per pixel: a plain threshold, ordered dithering, or error diffusion. The output
 * is always {@link BufferedImage#TYPE_BYTE_BINARY}, set bits being white.
 * <p>
 * How the gray levels are produced is up to the caller; a tone curve tuned for a particular
 * printer belongs before these, not in them.
 *
 * @author takahashikzn
 */
public final class IroHalftone {

    private IroHalftone() { }

    public enum Algorithm { BAYER, CLUSTERED, FLOYD_STEINBERG }

    private static final int WHITE = 0xFFFFFF;

    private static final int BLACK = 0x000000;

    /**
     * Dither a gray image. The levels are read with {@code getRGB}, i.e. through the image's own
     * color model; for {@link BufferedImage#TYPE_BYTE_GRAY} that includes its linear-to-sRGB curve.
     */
    public static BufferedImage dither(final BufferedImage gray, final Algorithm algo) {

        final var out = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_BYTE_BINARY);

        switch (algo) {
            case BAYER -> bayer8(gray, out);
            case CLUSTERED -> clustered8(gray, out);
            case FLOYD_STEINBERG -> floydSteinberg(gray, out);
        }

        return out;
    }

    /**
     * White where the Rec. 709 luminance reaches {@code level} (0 to 255), black elsewhere. Alpha
     * is ignored.
     */
    public static BufferedImage threshold(final BufferedImage img, final int level) {

        final int w = img.getWidth();
        final int h = img.getHeight();

        final var src = IroMisc.pixels(img);
        final var out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        final var dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        final int rowBytes = (w + 7) >>> 3;

        // by rows: 8 pixels share an output byte, so a range must not split a row
        IroMisc.bands(true, w, h, (from, to) -> {
            for (int y = from; y < to; y++) {
                final int srcOff = y * w;
                final int dstOff = y * rowBytes;

                for (int x = 0; x < w; x += 8) {
                    int bits = 0;
                    for (int i = 0; i < 8 && x + i < w; i++) {
                        final int p = src[srcOff + x + i];
                        if (level <= luminance(p >>> 16 & 0xFF, p >>> 8 & 0xFF, p & 0xFF)) bits |= 1 << (7 - i);
                    }
                    dst[dstOff + (x >>> 3)] = (byte) bits;
                }
            }
        });

        return out;
    }

    /** Rec. 709 in 8-bit fixed point. */
    private static int luminance(final int r, final int g, final int b) { return (r * 54 + g * 183 + b * 18) >> 8; }

    // ------------------------------------------------------------ ordered dithering

    /** The standard 8x8 Bayer index matrix, levels 0..63 scaled by 4 like the clustered screen. */
    private static void bayer8(final BufferedImage in, final BufferedImage out) {
        matrix(in, out, new int[][] {
            { 0, 128, 32, 160, 8, 136, 40, 168 }, //
            { 192, 64, 224, 96, 200, 72, 232, 104 }, //
            { 48, 176, 16, 144, 56, 184, 24, 152 }, //
            { 240, 112, 208, 80, 248, 120, 216, 88 }, //
            { 12, 140, 44, 172, 4, 132, 36, 164 }, //
            { 204, 76, 236, 108, 196, 68, 228, 100 }, //
            { 60, 188, 28, 156, 52, 180, 20, 148 }, //
            { 252, 124, 220, 92, 244, 116, 212, 84 } });
    }

    /**
     * A 45-degree clustered-dot screen, levels 0..63 scaled by 4: the 8x8 cluster-dot matrix published
     * in Sam Hocevar's libcaca study, part 2 (http://caca.zoy.org/study/part2.html), where it "mimics
     * the halftoning techniques used by newspapers". It is not the M = 4 screen of Ulichney's
     * Digital Halftoning (Figure 5.4), which differs.
     */
    private static void clustered8(final BufferedImage in, final BufferedImage out) {
        matrix(in, out, new int[][] {
            { 96, 40, 48, 104, 140, 188, 196, 148 }, //
            { 32, 0, 8, 56, 180, 236, 244, 204 }, //
            { 88, 24, 16, 64, 172, 228, 252, 212 }, //
            { 120, 80, 72, 112, 132, 164, 220, 156 }, //
            { 136, 184, 192, 144, 100, 44, 52, 108 }, //
            { 176, 232, 240, 200, 36, 4, 12, 60 }, //
            { 168, 224, 248, 208, 92, 28, 20, 68 }, //
            { 128, 160, 216, 152, 124, 84, 76, 116 } });
    }

    private static void matrix(final BufferedImage in, final BufferedImage out, final int[][] matrix) {

        final int w = in.getWidth();
        final int h = in.getHeight();
        final int n = matrix.length;

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int gray = in.getRGB(x, y) & 0xFF;
                out.setRGB(x, y, matrix[y % n][x % n] < gray ? WHITE : BLACK);
            }
    }

    // ------------------------------------------------------------- error diffusion

    private static void floydSteinberg(final BufferedImage in, final BufferedImage out) {

        final int w = in.getWidth();
        final int h = in.getHeight();
        final var error = new int[h][w];

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int old = (in.getRGB(x, y) & 0xFF) + error[y][x];
                final int now = old > 128 ? 255 : 0;
                out.setRGB(x, y, now == 255 ? WHITE : BLACK);

                final int e = old - now;

                if (x + 1 < w) error[y][x + 1] += e * 7 / 16;
                if (y + 1 < h && x > 0) error[y + 1][x - 1] += e * 3 / 16;
                if (y + 1 < h) error[y + 1][x] += e * 5 / 16;
                if (y + 1 < h && x + 1 < w) error[y + 1][x + 1] += e / 16;
            }
    }
}
