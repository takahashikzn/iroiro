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

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.RenderedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import static jp.uvoc.iroiro.IroMisc.*;


/**
 * Transparency: detecting it, flattening it, applying it, keying a color out, compositing, and
 * masks for formats that keep alpha separately (a PDF soft mask or 1-bit image mask).
 *
 * @author takahashikzn
 */
public final class IroAlpha {

    private IroAlpha() { }

    /** Whether any pixel is actually translucent, not merely whether the color model could hold one. */
    public static boolean hasAlpha(final BufferedImage img) {

        if (!img.getColorModel().hasAlpha()) return false;

        final var src = pixels(img);
        final var result = new AtomicBoolean(false);

        scan(true, src.length, src.length, (from, to) -> {
            for (int i = from; i < to; i++)
                if (result.get()) return;
                else if ((src[i] >>> 24) < 255) {
                    result.set(true);
                    return;
                }
        });

        return result.get();
    }

    /** Composite onto white, dropping the alpha channel. */
    public static BufferedImage removeAlpha(final RenderedImage in) {

        return draw(new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_RGB), g2d -> {
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, in.getWidth(), in.getHeight());
            g2d.setComposite(AlphaComposite.SrcOver);
            g2d.drawRenderedImage(in, new AffineTransform());
        });
    }

    /** Draw {@code img} with its opacity multiplied by {@code alpha} (0 to 1). */
    public static BufferedImage opacity(final Image img, final float alpha) {

        return draw(new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB), g2d -> {
            if (alpha < 1f) g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            drawImage(g2d, img, 0, 0);
        });
    }

    /**
     * Make {@code color} transparent. A pixel within {@code tolerance} of it on every RGB channel
     * (0 matches the color exactly, 1 matches anything) becomes fully clear; the source alpha is not
     * consulted. Every other pixel is kept as it was.
     */
    public static BufferedImage transparent(final BufferedImage img, final Color color, final float tolerance) {

        if (!(0 <= tolerance && tolerance <= 1)) throw new IllegalArgumentException("tolerance must be 0..1: " + tolerance);

        final int limit = Math.round(tolerance * 255);
        final int r = color.getRed();
        final int g = color.getGreen();
        final int b = color.getBlue();

        final int w = img.getWidth();
        final int h = img.getHeight();
        final var px = pixels(img);

        scan(true, px.length, px.length, (from, to) -> {
            for (int i = from; i < to; i++) {
                final int c = px[i];
                if (Math.abs((c >>> 16 & 0xFF) - r) <= limit && Math.abs((c >>> 8 & 0xFF) - g) <= limit && Math.abs((c & 0xFF) - b) <= limit) //
                    px[i] = 0;
            }
        });

        final var ret = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        ret.setRGB(0, 0, w, h, px, 0, w);

        return ret;
    }

    /** {@code over} composited onto {@code base}, as Java2D does it. The result is opaque. */
    public static Color compose(final Color base, final Color over) {

        return new Color(draw(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), g2d -> {
            g2d.setColor(base);
            g2d.fillRect(0, 0, 1, 1);
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, over.getAlpha() / 255f));
            g2d.setColor(over);
            g2d.fillRect(0, 0, 1, 1);
        }).getRGB(0, 0));
    }

    // ------------------------------------------------------------------------ masks

    public static final int MASK_ALPHA_THRESHOLD = 128;

    public static byte[] createBinaryMask(final BufferedImage img) { return createBinaryMask(img, MASK_ALPHA_THRESHOLD); }

    public static byte[] createBinaryMask(final BufferedImage img, final int threshold) {
        return createBinaryMask(pixels(img), img.getWidth(), img.getHeight(), threshold);
    }

    /**
     * One bit per pixel, MSB first, each row padded to a byte: set where alpha reaches
     * {@code alphaThreshold}. The layout of a 1-bit mask image.
     */
    public static byte[] createBinaryMask(final int[] argb, final int w, final int h, final int alphaThreshold) {

        final int rowBytes = (w + 7) >>> 3;
        final var out = new byte[rowBytes * h];

        // by rows: a row owns its bytes, where a split mid-row would share one with the next range
        bands(true, w, h, (from, to) -> {

            for (int y = from; y < to; y++) {

                final int srcOff = y * w;
                final int dstOff = y * rowBytes;

                int x = 0;

                // 8 pixels to a byte, MSB first
                for (; x + 8 <= w; x += 8) {
                    int b = 0;
                    final int base = srcOff + x;
                    for (int k = 0; k < 8; k++) {
                        final int a = (argb[base + k] >>> 24) & 0xFF;
                        if (a >= alphaThreshold) b |= 1 << (7 - k);
                    }
                    out[dstOff + (x >>> 3)] = (byte) b;
                }

                // the remainder
                if (x < w) {
                    int b = 0;
                    final int base = srcOff + x;
                    for (int k = 0; x + k < w; k++) {
                        final int a = (argb[base + k] >>> 24) & 0xFF;
                        if (a >= alphaThreshold) b |= 1 << (7 - k);
                    }
                    out[dstOff + (x >>> 3)] = (byte) b;
                }
            }
        });

        return out;
    }

    public static BufferedImage createAlphaMask(final BufferedImage img) { return createAlphaMask(pixels(img), img.getWidth(), img.getHeight()); }

    /** The alpha channel as an 8-bit gray image. */
    public static BufferedImage createAlphaMask(final int[] argb, final int w, final int h) {

        final var out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        final var dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();

        scan(true, argb.length, argb.length, (from, to) -> {
            for (int i = from; i < to; i++)
                dst[i] = (byte) (argb[i] >>> 24);
        });

        return out;
    }
}
