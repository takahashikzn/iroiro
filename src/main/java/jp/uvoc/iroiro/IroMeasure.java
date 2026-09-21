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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import javax.imageio.ImageIO;

import static jp.uvoc.iroiro.IroMisc.*;


/**
 * How far one image is from another: what the tests here hold the tools to, and what a caller can
 * use to check its own output. Both images must have the same size, except for
 * {@link #similarity}, which resamples them to a common one.
 *
 * @author takahashikzn
 */
public final class IroMeasure {

    private IroMeasure() { }

    public static double similarity(final byte[] l, final byte[] r) throws IOException {
        return similarity(new ByteArrayInputStream(l), new ByteArrayInputStream(r));
    }

    public static double similarity(final InputStream l, final InputStream r) throws IOException { return similarity(ImageIO.read(l), ImageIO.read(r)); }

    /**
     * 1 minus the mean absolute RGB difference, normalized to 0..1. Images of different sizes are
     * both resampled to the smaller width and height first.
     */
    public static double similarity(final BufferedImage l, final BufferedImage r) {

        final int w = Math.min(l.getWidth(), r.getWidth());
        final int h = Math.min(l.getHeight(), r.getHeight());

        final var x = pixels(IroResize.resize(l, w, h));
        final var y = pixels(IroResize.resize(r, w, h));

        long diff = 0;
        for (int i = 0; i < x.length; i++)
            for (int shift = 0; shift < 24; shift += 8)
                diff += Math.abs((x[i] >>> shift & 0xFF) - (y[i] >>> shift & 0xFF));

        return 1 - (diff / (w * h * 3d) / 255);
    }

    /** Largest absolute 8-bit RGBA channel error; RGB is ignored when both pixels are clear. */
    public static int maxError(final BufferedImage a, final BufferedImage b) {
        sameSize(a, b);
        final var x = pixels(a);
        final var y = pixels(b);
        int max = 0;
        for (int i = 0; i < x.length; i++) {
            if ((x[i] >>> 24) == 0 && (y[i] >>> 24) == 0) continue;
            for (int shift = 0; shift <= 24; shift += 8)
                max = Math.max(max, Math.abs((x[i] >>> shift & 255) - (y[i] >>> shift & 255)));
        }
        return max;
    }

    /** Mean of each pixel's largest straight-sRGB channel error. Alpha itself is not compared. */
    public static double avgError(final BufferedImage a, final BufferedImage b) {
        sameSize(a, b);
        final var x = pixels(a);
        final var y = pixels(b);
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            if ((x[i] >>> 24) == 0 && (y[i] >>> 24) == 0) continue;
            int max = 0;
            for (int shift = 0; shift < 24; shift += 8)
                max = Math.max(max, Math.abs((x[i] >>> shift & 255) - (y[i] >>> shift & 255)));
            sum += max;
        }
        return (double) sum / x.length;
    }

    /** Mean squared Euclidean error in 0..255 premultiplied sRGB plus alpha, per pixel. */
    public static double premultipliedError(final BufferedImage a, final BufferedImage b) {
        sameSize(a, b);
        final var x = pixels(a);
        final var y = pixels(b);
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            final int aa = x[i] >>> 24;
            final int ba = y[i] >>> 24;
            sum += (aa - ba) * (aa - ba);
            for (int shift = 0; shift < 24; shift += 8) {
                final double d = ((x[i] >>> shift & 255) * aa - (y[i] >>> shift & 255) * ba) / 255.0;
                sum += d * d;
            }
        }
        return sum / x.length;
    }

    /**
     * Luma-only SSIM over 8x8 windows at 4-pixel intervals (K1=.01, K2=.03, sample covariance).
     * Includes the right/bottom edge and uses smaller windows for images smaller than 8 pixels.
     * Alpha and chroma are not measured; use colorError for color-sensitive or translucent input.
     */
    public static double ssim(final BufferedImage a, final BufferedImage b) {
        sameSize(a, b);
        final int w = a.getWidth();
        final int h = a.getHeight();
        final int bw = Math.min(8, w);
        final int bh = Math.min(8, h);
        final int nx = (w - bw + 3) / 4 + 1;
        final int ny = (h - bh + 3) / 4 + 1;
        final int samples = bw * bh;
        final var x = gray(a);
        final var y = gray(b);
        final var scores = new double[nx * ny];

        scan(true, ny, (long) w * h, (from, to) -> {
            for (int iy = from; iy < to; iy++)
                for (int ix = 0; ix < nx; ix++) {
                    final int bx = Math.min(ix * 4, w - bw);
                    final int by = Math.min(iy * 4, h - bh);
                    double mx = 0;
                    double my = 0;
                    for (int j = 0; j < bh; j++)
                        for (int i = 0; i < bw; i++) {
                            final int at = (by + j) * w + bx + i;
                            mx += x[at];
                            my += y[at];
                        }
                    mx /= samples;
                    my /= samples;
                    double vx = 0;
                    double vy = 0;
                    double cxy = 0;
                    for (int j = 0; j < bh; j++)
                        for (int i = 0; i < bw; i++) {
                            final int at = (by + j) * w + bx + i;
                            final double dx = x[at] - mx;
                            final double dy = y[at] - my;
                            vx += dx * dx;
                            vy += dy * dy;
                            cxy += dx * dy;
                        }
                    final int divisor = Math.max(1, samples - 1);
                    vx /= divisor;
                    vy /= divisor;
                    cxy /= divisor;
                    scores[iy * nx + ix] = score(mx, my, cxy, vx, vy);
                }
        });

        // Fixed summation order, independent of how the windows were divided among workers.
        return sum(scores) / scores.length;
    }

    private static double score(final double mx, final double my, final double cxy, final double vx, final double vy) {
        return ((2 * mx * my + 6.5025) * (2 * cxy + 58.5225)) / ((mx * mx + my * my + 6.5025) * (vx + vy + 58.5225));
    }

    public enum Background { BLACK, WHITE, CHECKERBOARD }

    /** Oklab Euclidean distances scaled by 100; not CIEDE2000 or a visibility threshold. */
    public record ColorError(double mean, double p95, double max) { }

    /** Measure colors after linear-light compositing onto the chosen background (8px checker tiles). */
    public static ColorError colorError(final BufferedImage a, final BufferedImage b, final Background background) {

        sameSize(a, b);
        Objects.requireNonNull(background, "background");
        final int w = a.getWidth();
        final var x = pixels(a);
        final var y = pixels(b);
        final var errors = new double[x.length];
        bands(true, w, a.getHeight(), (from, to) -> {
            final var p = new double[3];
            final var q = new double[3];
            for (int i = from * w; i < to * w; i++) {
                final double bg = switch (background) {
                    case BLACK -> 0;
                    case WHITE -> 1;
                    case CHECKERBOARD -> ((i % w / 8 + i / w / 8) & 1);
                };
                oklab(x[i], bg, p);
                oklab(y[i], bg, q);
                double square = 0;
                for (int c = 0; c < 3; c++) square += (p[c] - q[c]) * (p[c] - q[c]);
                errors[i] = 100 * Math.sqrt(square);
            }
        });

        final var sum = sum(errors);
        Arrays.sort(errors);
        return new ColorError(sum / errors.length, errors[(int) Math.ceil(errors.length * 0.95) - 1], errors[errors.length - 1]);
    }

    private static double sum(final double[] vals) {
        double sum = 0;
        for (final var error: vals) sum += error;
        return sum;
    }

    /**
     * Linear-light compositing followed by Bjorn Ottosson's public-domain <a href="https://bottosson.github.io/posts/oklab/#converting-from-linear-srgb-to-oklab">Oklab transform</a>.
     */
    private static void oklab(final int c, final double background, final double[] out) {

        final double a = (c >>> 24) / 255.0;
        final double r = LINEAR[c >>> 16 & 255] * a + background * (1 - a);
        final double g = LINEAR[c >>> 8 & 255] * a + background * (1 - a);
        final double b = LINEAR[c & 255] * a + background * (1 - a);
        final double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        final double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        final double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
    }

    private static final double[] LINEAR = linear();

    private static double[] linear() {
        final var values = new double[256];
        for (int i = 0; i < values.length; i++) {
            final double x = i / 255.0;
            values[i] = x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4);
        }
        return values;
    }

    private static int[] gray(final BufferedImage image) {
        final var ret = pixels(image);
        for (int i = 0; i < ret.length; i++)
            ret[i] = gray(ret[i]);
        return ret;
    }

    private static int gray(final int c) { return (299 * (c >>> 16 & 255) + 587 * (c >>> 8 & 255) + 114 * (c & 255)) / 1000; }

    private static void sameSize(final BufferedImage a, final BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) throw new IllegalArgumentException("image dimensions must match");
    }
}
