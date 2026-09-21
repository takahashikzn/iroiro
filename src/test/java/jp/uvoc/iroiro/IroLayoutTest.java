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

import jp.uvoc.iroiro.IroLayout.Alignment;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * {@link IroLayout}.
 *
 * @author takahashikzn
 */
public class IroLayoutTest {

    /** A negative offset crops from the top-left, a positive one pads. */
    @Test
    public void placeDrawsAtTheOffset() {

        final var src = row(0xFFFF0000, 0xFF00FF00, 0xFF0000FF);

        assertThat(IroMisc.pixels(IroLayout.place(src, -1, 0, 2, 1))).containsExactly(0xFF00FF00, 0xFF0000FF);
        assertThat(IroMisc.pixels(IroLayout.place(src, 1, 0, 3, 1))).containsExactly(0, 0xFFFF0000, 0xFF00FF00);
    }

    @Test
    public void alignPlacesAndEnlargesTheBox() {

        final var src = row(0xFFFF0000, 0xFF00FF00);

        final var centered = IroLayout.align(src, Alignment.CENTER, 4, 4);
        assertThat(centered.getWidth()).isEqualTo(4);
        assertThat(centered.getHeight()).isEqualTo(4);
        assertThat(centered.getRGB(1, 1)).isEqualTo(0xFFFF0000);
        assertThat(centered.getRGB(0, 0) >>> 24).isZero();

        // smaller than the image: the box grows with its own aspect ratio until the image fits
        final var enlarged = IroLayout.align(new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB), Alignment.TOP_LEFT, 2, 2);
        assertThat(enlarged.getWidth()).isEqualTo(4);
        assertThat(enlarged.getHeight()).isEqualTo(4);
    }

    /** cos 90 is not quite zero; a quarter or half turn must not gain a row or a column. */
    @Test
    public void rotateFitsRightAnglesExactly() {

        final var src = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);

        final var quarter = IroLayout.rotate(src, 90, true);
        assertThat(quarter.getWidth()).isEqualTo(50);
        assertThat(quarter.getHeight()).isEqualTo(100);

        final var half = IroLayout.rotate(src, 180, true);
        assertThat(half.getWidth()).isEqualTo(100);
        assertThat(half.getHeight()).isEqualTo(50);

        assertThat(IroLayout.rotate(src, 360, true)).isSameAs(src);
    }

    private static BufferedImage row(final int... argb) {
        final var ret = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        ret.setRGB(0, 0, argb.length, 1, argb, 0, argb.length);
        return ret;
    }
}
