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

import java.awt.Color;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

import static jp.uvoc.iroiro.IroMisc.*;


/**
 * Where an image goes on a canvas: side by side, aligned in a box, at an offset, or turned.
 * Resampling to a different size is {@link IroResize}'s.
 *
 * @author takahashikzn
 */
public final class IroLayout {

    private IroLayout() { }

    public enum Orient { VERTICAL, HORIZONTAL }

    /** Put {@code second} below or to the right of {@code first}, on white. */
    public static BufferedImage join(final BufferedImage first, final BufferedImage second, final Orient orient) {

        final boolean below = orient == Orient.VERTICAL;

        // where the second image starts; the canvas is that plus the second image along the axis
        final int x = below ? 0 : first.getWidth();
        final int y = below ? first.getHeight() : 0;
        final int w = below ? Math.max(first.getWidth(), second.getWidth()) : x + second.getWidth();
        final int h = below ? y + second.getHeight() : Math.max(first.getHeight(), second.getHeight());

        return draw(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), g2d -> {
            g2d.setBackground(Color.WHITE);
            g2d.clearRect(0, 0, w, h);
            g2d.drawRenderedImage(first, new AffineTransform());
            g2d.drawRenderedImage(second, AffineTransform.getTranslateInstance(x, y));
        });
    }

    public enum Alignment { TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT, CENTER }

    /**
     * Place {@code img}, at its own size, in a transparent {@code width} x {@code height} box by
     * {@code alignment}. A box smaller than the image is enlarged, keeping its aspect ratio, until
     * the image fits, so nothing is cut off.
     */
    public static BufferedImage align(final BufferedImage img, final Alignment alignment, final int width, final int height) {

        final int iw = img.getWidth();
        final int ih = img.getHeight();

        int bw = width;
        int bh = height;
        if (width < iw || height < ih) {
            final double rate = Math.max((double) iw / width, (double) ih / height);
            bw = (int) Math.ceil(width * rate);
            bh = (int) Math.ceil(height * rate);
        }

        final int dx = bw - iw;
        final int dy = bh - ih;

        final int x = switch (alignment) {
            case TOP_LEFT, LEFT, BOTTOM_LEFT -> 0;
            case TOP, CENTER, BOTTOM -> dx / 2;
            case TOP_RIGHT, RIGHT, BOTTOM_RIGHT -> dx;
        };
        final int y = switch (alignment) {
            case TOP_LEFT, TOP, TOP_RIGHT -> 0;
            case LEFT, CENTER, RIGHT -> dy / 2;
            case BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT -> dy;
        };

        return draw(new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB), g2d -> {
            g2d.addRenderingHints(QUALITY);
            drawImage(g2d, img, x, y, iw, ih);
        });
    }

    /**
     * Draw {@code img} with its top-left corner at ({@code x}, {@code y}) on a transparent
     * {@code w} x {@code h} canvas; whatever falls outside is cut off. A negative offset crops
     * from the image's top-left, a positive one pads it.
     */
    public static BufferedImage place(final Image img, final int x, final int y, final int w, final int h) {
        return draw(new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB), g2d -> drawImage(g2d, img, x, y));
    }

    /**
     * Rotate clockwise by {@code degree} around the center. With {@code fit} the canvas grows to hold
     * the whole rotated image; without it the canvas keeps the original size and the corners are cut.
     */
    public static BufferedImage rotate(final BufferedImage img, final int degree, final boolean fit) {

        if (degree % 360 == 0) return img;

        final int w = img.getWidth();
        final int h = img.getHeight();
        final double rad = Math.toRadians(degree);
        final var at = new AffineTransform();
        final BufferedImage ret;

        if (fit) {
            final double sin = Math.abs(Math.sin(rad));
            final double cos = Math.abs(Math.cos(rad));
            // cos 90 is 6e-17, not 0: without the slack a quarter turn gains a column, and the
            // half-pixel shift that comes with it blurs the whole image and leaves a clear edge
            final int w2 = (int) Math.ceil(w * cos + h * sin - 1e-9);
            final int h2 = (int) Math.ceil(h * cos + w * sin - 1e-9);

            ret = new BufferedImage(w2, h2, BufferedImage.TYPE_INT_ARGB);
            at.translate(w2 / 2d, h2 / 2d);
            at.rotate(rad, 0, 0);
            at.translate(-w / 2d, -h / 2d);
        } else {
            ret = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            at.rotate(rad, w / 2d, h / 2d);
        }

        return new AffineTransformOp(at, new RenderingHints(QUALITY)).filter(img, ret);
    }
}
