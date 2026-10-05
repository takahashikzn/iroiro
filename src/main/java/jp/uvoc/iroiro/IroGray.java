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
import java.util.Optional;
import java.util.function.Supplier;

import static jp.uvoc.iroiro.IroAlpha.MASK_ALPHA_THRESHOLD;
import static jp.uvoc.iroiro.IroMisc.*;


/**
 * Gray levels: the Rec. 709 weights over the sRGB-encoded components, with any transparency
 * flattened onto white. The levels stay sRGB-encoded.
 * <p>
 * {@link BufferedImage#TYPE_BYTE_GRAY} is defined as linear gray, so {@code getRGB} on one lightens
 * sRGB-encoded levels (128 reads back as 188). {@link #argb2gray} still uses it, for writers that take
 * the raster as is; {@link #grayRGB} holds the same levels where {@code getRGB} returns them
 * unchanged. Storing linear levels instead would crush the shadows in 8 bits (0..32 into 0..3).
 *
 * @author takahashikzn
 */
@SuppressWarnings("OverlyComplexArithmeticExpression")
public final class IroGray {

    private IroGray() { }

    /** The gray image, and the masks for its alpha, computed on demand from the same pixels. */
    public record GrayedImage(BufferedImage img, Supplier<Optional<BufferedImage>> smask, Supplier<Optional<byte[]>> imask) { }

    /**
     * The levels in a {@link BufferedImage#TYPE_BYTE_GRAY} raster, to be written as is. The masks are
     * those of {@link IroAlpha#createAlphaMask(BufferedImage)} and
     * {@link IroAlpha#createBinaryMask(BufferedImage)}, taken from the same pixels; both are empty
     * when the color model has no alpha.
     */
    public static GrayedImage argb2gray(final BufferedImage img) {

        final int w = img.getWidth();
        final int h = img.getHeight();

        final var src = pixels(img);
        final var out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        final var dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();

        scan(true, src.length, src.length, (from, to) -> {
            for (int i = from; i < to; i++)
                dst[i] = (byte) onWhite(src[i]);
        });

        final var alpha = img.getColorModel().hasAlpha();
        return new GrayedImage(out //
            , alpha ? () -> Optional.of(IroAlpha.createAlphaMask(src, w, h)) : Optional::empty //
            , alpha ? () -> Optional.of(IroAlpha.createBinaryMask(src, w, h, MASK_ALPHA_THRESHOLD)) : Optional::empty //
        );
    }

    /**
     * The levels of {@link #argb2gray} as {@link BufferedImage#TYPE_INT_RGB}, for readers that go
     * through {@code getRGB}, such as {@link IroHalftone#dither}.
     */
    public static BufferedImage grayRGB(final BufferedImage img) {

        final var src = pixels(img);
        final var out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        final var dst = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();

        scan(true, src.length, src.length, (from, to) -> {
            for (int i = from; i < to; i++)
                dst[i] = onWhite(src[i]) * 0x010101;
        });

        return out;
    }

    /** The level of an ARGB pixel composited onto white. */
    public static int onWhite(final int argb) {

        final int l = luminance(argb >>> 16 & 0xFF, argb >>> 8 & 0xFF, argb & 0xFF);
        final int a = argb >>> 24;

        return a == 255 ? l : 255 - ((255 - l) * a >> 8);
    }

    /**
     * Rec. 709 in 8-bit fixed point, rounded. The weights sum to 256, so a gray keeps its level and
     * white stays 255; against {@link #rgb2gray} the level is off by at most 1.
     */
    public static int luminance(final int r, final int g, final int b) { return (r * 54 + g * 183 + b * 19 + 128) >> 8; }

    /** The Rec. 709 level of an RGB color as 0 to 1. Black and white come out exactly 0 and 1. */
    public static double rgb2gray(final int r, final int g, final int b) {

        if (r == 0 && g == 0 && b == 0) return 0;
        if (r == 255 && g == 255 && b == 255) return 1;

        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
    }
}
