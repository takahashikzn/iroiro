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
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import javax.imageio.ImageIO;

import static jp.uvoc.iroiro.IroMeasure.*;
import static jp.uvoc.iroiro.IroMisc.*;

import org.junit.Test;

import static org.assertj.core.api.Assertions.*;


/**
 * Color-sensitive quality guards and adversarial cases for the quantizer.
 * Oklab distances below are scaled by 100; they are not CIEDE2000 or a visibility threshold.
 * Alpha is evaluated after linear-light compositing onto black, white and a checkerboard.
 */
public class IroQuantQualityTest {

    /** A high-variance green axis is a worse cut than red when all three channels are counted. */
    @Test
    public void splittingMinimizesTheCombinedColorError() throws Exception {
        final int[] px = { 0xFF3C6B4E, 0xFF55304F, 0xFF625964, 0xFF73361D, 0xFF58574A, 0xFF210C7B };
        final var out = decode(IroQuant.encode(px, px.length, 1, IroQuant.Opts.DEFAULT.maxColors(2).kmeans(0).importance(0)));
        double sse = 0;
        for (int i = 0; i < px.length; i++)
            for (int shift = 0; shift < 24; shift += 8) {
                final int d = (px[i] >>> shift & 255) - (out.getRGB(i, 0) >>> shift & 255);
                sse += d * d;
            }
        // After rounding, the red cut has SSE 6852 and the green cut 10310.
        assertThat(sse).isLessThan(7000);
    }

    /** At alpha 1, rounding the premultiplied values would put all 64 colors on one coordinate. */
    @Test
    public void lowAlphaColorsKeepFractionalPrecision() throws Exception {
        final var px = new int[64];
        for (int i = 0; i < px.length; i++) px[i] = 0x01000000 | i << 16 | i << 8 | i;
        final var out = decode(IroQuant.encode(px, px.length, 1, IroQuant.Opts.DEFAULT.maxColors(8).importance(0)));
        for (int i = 0; i < px.length; i++) {
            assertThat(out.getRGB(i, 0) >>> 24).isEqualTo(1);
            assertThat(Math.abs((out.getRGB(i, 0) & 255) - i)).isLessThanOrEqualTo(4);
        }
    }

    /** Force the fallback, then check colors that shared a 5-bit cell but have separate 6-bit cells. */
    @Test
    public void opaqueFallbackRetainsSixBitPrecision() throws Exception {
        final int cube = 1 << 18;
        final var px = new int[cube * 9 + 512];
        for (int i = 0; i < cube; i++)
            px[i] = 0xFF000000 | (32 + (i >>> 12)) << 16 | (32 + (i >>> 6 & 63)) << 8 | (32 + (i & 63));
        px[cube] = 0xFFFFFFFF; // 262145 distinct colors, even before adding the repeated colors
        for (int i = cube + 1; i < px.length; i++) px[i] = (i & 1) == 0 ? 0xFF404040 : 0xFF474747;
        final var o = IroQuant.Opts.DEFAULT.maxColors(2).importance(0);
        final var bytes = IroQuant.encode(px, 512, px.length / 512, o);
        final var out = decode(bytes);
        assertThat(out.getRGB(2, 512)).isNotEqualTo(out.getRGB(3, 512));
        assertThat(bytes).isEqualTo(IroQuant.encode(px, 512, px.length / 512, o.parallel(false)));
    }

    /** A near-zero diffusion strength must not merge nearby colors in the color lookup. */
    @Test
    public void ditherCacheDoesNotMergeNearbyPaletteColors() throws Exception {
        final var px = new int[120];
        final int[] blue = { 100, 101, 103 };
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | blue[i % 3];
        final var o = IroQuant.Opts.DEFAULT.maxColors(2).importance(0);
        final var plain = decode(IroQuant.encode(px, 120, 1, o));
        final var dithered = decode(IroQuant.encode(px, 120, 1, o.dither(1e-9)));
        assertThat(pixels(dithered)).isEqualTo(pixels(plain));
    }

    /** Neither importance weighting nor error diffusion may observe fully transparent RGB. */
    @Test
    public void hiddenRgbDoesNotChangeVisibleOutput() throws Exception {
        final var src = alphaGradient();
        final var a = pixels(src);
        final var b = a.clone();
        for (int i = 0; i < a.length; i++)
            if ((a[i] >>> 24) == 0) b[i] ^= (i * 123457) & 0xFFFFFF;
        for (final double dither: new double[] { 0, 0.5, 1 }) {
            final var o = IroQuant.Opts.DEFAULT.maxColors(32).dither(dither);
            assertThat(IroQuant.encode(a, src.getWidth(), src.getHeight(), o)).as("hidden RGB, dither=%s", dither)
                .isEqualTo(IroQuant.encode(b, src.getWidth(), src.getHeight(), o));
        }
    }

    /** Both opaque colors and translucency must survive a histogram rebuild. */
    @Test
    public void alphaFallbackAndTruecolorAgree() throws Exception {
        final var px = new int[513 * 513];
        for (int i = 0; i < px.length; i++)
            px[i] = (32 + i % 224) << 24 | i * 31 & 0xFFFFFF;
        px[px.length - 2] = 0x01000000; // must not share the clear pixel's coarse bucket
        px[px.length - 1] = 0; // alpha must be inspected across the entire source
        final var original = fromPixels(px, 513, 513);
        final var o = IroQuant.Opts.DEFAULT.importance(0);
        final var indexed = IroQuant.encode(original, o);
        final var out = decode(indexed);
        assertThat(out.getRGB(512, 512) >>> 24).as("clear stays clear").isZero();
        assertThat(out.getRGB(511, 512) >>> 24).as("near-clear black has its own bucket").isPositive();
        assertThat(indexed).isEqualTo(IroQuant.encode(original, o.parallel(false)));
        assertThat(pixels(out)).isEqualTo(pixels(decode(IroQuant.encode(original, o.palettizeLossy(false)))));
        assertThat(((IndexColorModel) out.getColorModel()).getMapSize()).isLessThanOrEqualTo(256);
        for (final var background: Background.values())
            assertThat(colorError(original, out, background).mean()).as("background %s", background).isLessThan(5);
    }

    @Test
    public void translucentGradientStaysCloseOnDifferentBackgrounds() throws Exception {
        final var src = alphaGradient();
        for (final double dither: new double[] { 0, 0.5, 1 }) {
            final var out = decode(IroQuant.encode(src, IroQuant.Opts.DEFAULT.dither(dither)));
            for (int y = 0; y < src.getHeight(); y++)
                for (int x = 0; x < src.getWidth(); x++)
                    if ((src.getRGB(x, y) >>> 24) == 0) assertThat(out.getRGB(x, y) >>> 24).isZero();
            for (final var background: Background.values()) {
                final var error = colorError(src, out, background);
                assertThat(error.mean()).as("mean, background=%s, dither=%s", background, dither).isLessThan(2);
                assertThat(error.p95()).as("p95, background=%s, dither=%s", background, dither).isLessThan(5);
            }
        }
    }

    /** Reserving clear must still obey tiny palette limits and never mutate the source pixels. */
    @Test
    public void paletteLimitsAlsoApplyToTransparentImages() throws Exception {
        final var src = alphaGradient();
        final var px = pixels(src);
        final var original = px.clone();
        for (final int colors: new int[] { 1, 2, 16, 256 })
            for (final double dither: new double[] { 0, 1 }) {
                final var bytes = IroQuant.encode(px, src.getWidth(), src.getHeight(), IroQuant.Opts.DEFAULT.maxColors(colors).dither(dither));
                final var out = decode(bytes);
                // ImageIO pads a 1-entry palette to 2 entries; inspect the actual PNG declaration.
                assertThat(paletteSize(bytes)).isLessThanOrEqualTo(colors);
                if (1 < colors) assertThat(out.getRGB(0, 0) >>> 24).isZero();
                assertThat(px).isEqualTo(original);
            }
    }

    /** The metric must catch a chroma error even when the integer grayscale values match. */
    @Test
    public void colorMetricDetectsChromaAndIgnoresHiddenRgb() {
        final var red = fromPixels(new int[] { 0xFFFF0000 }, 1, 1);
        final var gray = fromPixels(new int[] { 0xFF4C4C4C }, 1, 1);
        assertThat(colorError(red, gray, Background.BLACK).mean()).isGreaterThan(20);
        assertThat(colorError(red, red, Background.BLACK).max()).isZero();
        final var clear = fromPixels(new int[] { 0 }, 1, 1);
        final var hidden = fromPixels(new int[] { 0x00FFFFFF }, 1, 1);
        assertThat(colorError(clear, hidden, Background.BLACK).max()).isZero();
        assertThat(colorError(clear, hidden, Background.WHITE).max()).isZero();
        assertThat(colorError(fromPixels(new int[] { 0xFF000000 }, 1, 1), fromPixels(new int[] { 0xFFFFFFFF }, 1, 1), Background.BLACK).mean()).isCloseTo(100,
            within(1e-5));
    }

    @Test
    public void colorMetricCompositesInLinearLight() {
        final var halfWhite = fromPixels(new int[] { 0x80FFFFFF }, 1, 1);
        final var black = fromPixels(new int[] { 0xFF000000 }, 1, 1);
        final var white = fromPixels(new int[] { 0xFFFFFFFF }, 1, 1);
        assertThat(colorError(halfWhite, black, Background.BLACK).mean()).isCloseTo(100 * Math.cbrt(128 / 255.0), within(1e-5));
        assertThat(colorError(halfWhite, white, Background.WHITE).max()).isZero();
    }

    /** More refinement must not increase the unweighted premultiplied objective. */
    @Test
    public void refinementDoesNotIncreaseError() throws Exception {
        final var src = alphaGradient();
        double previous = Double.POSITIVE_INFINITY;
        for (int kmeans = 0; kmeans <= 4; kmeans++) {
            final var out = decode(IroQuant.encode(src, IroQuant.Opts.DEFAULT.importance(0).maxColors(32).kmeans(kmeans)));
            final double error = premultipliedError(src, out);
            assertThat(error).as("kmeans=%s", kmeans).isLessThanOrEqualTo(previous + 0.1);
            previous = error;
        }
    }

    /** Check the distance-bound optimization against an independent exhaustive oracle, including ties. */
    @Test
    public void prunedAssignmentsMatchExhaustiveSearch() throws Exception {
        final var assign =
            IroQuant.class.getDeclaredMethod("assign", double[][].class, int.class, int[].class, int.class, int.class, double[].class, double[].class,
                double[].class, double[].class, boolean.class, int.class);
        assign.setAccessible(true);
        final int count = 4096;
        final int colors = 256;
        final var random = new Random(71);
        final var ch = new double[4][count];
        final var centers = new double[4][colors];
        for (int i = 1; i < count; i++) {
            ch[3][i] = 1 + random.nextDouble() * 254;
            for (int c = 0; c < 3; c++) ch[c][i] = random.nextDouble() * ch[3][i];
        }
        for (int i = 1; i < colors; i++) {
            centers[3][i] = 1 + random.nextDouble() * 254;
            for (int c = 0; c < 3; c++) centers[c][i] = random.nextDouble() * centers[3][i];
        }
        for (int c = 0; c < 4; c++) {
            centers[c][1] = centers[c][2] = centers[c][3] = c == 3 ? 255 : 32 * (c + 1);
            ch[c][1] = ch[c][2] = centers[c][1];
        }
        centers[0][3] += 2;
        ch[0][1] += 1; // a tie exactly on the 2*radius pruning boundary

        for (int first = 0; first <= 1; first++) {
            final var initial = new int[count];
            final var expected = new int[count];
            for (int k = 1; k < count; k++) {
                initial[k] = first + random.nextInt(colors - first);
                double best = Double.POSITIVE_INFINITY;
                for (int i = first; i < colors; i++) {
                    double distance = 0;
                    for (int c = 0; c < 4; c++) {
                        final double d = ch[c][k] - centers[c][i];
                        distance += d * d;
                    }
                    if (distance < best) {
                        best = distance;
                        expected[k] = i;
                    }
                }
            }
            initial[1] = 3;
            initial[2] = 2; // a duplicate centroid must still choose the lowest palette index
            for (final boolean parallel: new boolean[] { false, true }) {
                final var map = initial.clone();
                assign.invoke(null, ch, count, map, colors, first, centers[0], centers[1], centers[2], centers[3], parallel, PARALLEL_MIN * 2);
                assertThat(map).isEqualTo(expected);
                assertThat(
                    assign.invoke(null, ch, count, map, colors, first, centers[0], centers[1], centers[2], centers[3], parallel, PARALLEL_MIN * 2)).isEqualTo(
                    false);
            }
        }
    }

    private static BufferedImage alphaGradient() {
        final var px = new int[192 * 128];
        for (int y = 0; y < 128; y++)
            for (int x = 0; x < 192; x++) {
                final int alpha = x % 17 == 0 ? 0 : y * 2;
                px[y * 192 + x] = alpha << 24 | (x * 255 / 191) << 16 | (255 - x * 255 / 191) << 8 | (y * 255 / 127);
            }
        return fromPixels(px, 192, 128);
    }

    private static BufferedImage richColors() {
        final var px = new int[1024 * 512];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | i * 31 & 0xFFFFFF;
        return fromPixels(px, 1024, 512);
    }

    private static int paletteSize(final byte[] png) {
        for (int at = 8; at < png.length; ) {
            final int len = ByteBuffer.wrap(png, at, 4).getInt();
            if (ByteBuffer.wrap(png, at + 4, 4).getInt() == 0x504C5445) return len / 3; // PLTE
            at += len + 12;
        }
        throw new AssertionError("no PLTE");
    }

    private static BufferedImage fromPixels(final int[] px, final int w, final int h) {
        final var img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, px, 0, w);
        return img;
    }

    private static BufferedImage decode(final byte[] bytes) throws Exception {
        final var img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(img).isNotNull();
        return img;
    }

    /**
     * Manual benchmark: run this class with the normal test classpath, optionally passing an
     * output directory for decoded-image inspection. Timings are medians after warm-up; they
     * are reported, never asserted. Compare revisions using the same JVM and machine.
     */
    static void main(final String[] args) throws Exception {
        final var o = IroQuant.Opts.DEFAULT.parallel(false);
        final Path dir = args.length == 0 ? null : Path.of(args[0]);
        if (dir != null) Files.createDirectories(dir);
        System.out.println("image,bytes,median_ms,mean_oklab,p95_oklab,max_oklab,premul_mse");
        for (final var name: new String[] { "alpha", "rich" }) {
            final var src = name.equals("alpha") ? alphaGradient() : richColors();
            for (int i = 0; i < 8; i++) IroQuant.encode(src, o);
            final var times = new long[9];
            byte[] bytes = null;
            for (int i = 0; i < times.length; i++) {
                final long start = System.nanoTime();
                bytes = IroQuant.encode(src, o);
                times[i] = System.nanoTime() - start;
            }
            Arrays.sort(times);
            final var out = decode(bytes);
            final var error = colorError(src, out, Background.BLACK);
            System.out.printf(Locale.ROOT, "%s,%d,%.3f,%.6f,%.6f,%.6f,%.6f%n", name, bytes.length, times[times.length / 2] / 1e6, error.mean(), error.p95(),
                error.max(), premultipliedError(src, out));
            if (dir != null) {
                Files.write(dir.resolve(name + ".png"), bytes);
                ImageIO.write(src, "png", dir.resolve(name + "-source.png").toFile());
            }
        }
    }
}
