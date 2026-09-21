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
import java.awt.image.BufferedImage;

import org.junit.Test;

import static org.assertj.core.api.Assertions.*;


/**
 * {@link IroAlpha}.
 *
 * @author takahashikzn
 */
public class IroAlphaTest {

    @Test
    public void compose() {
        assertThat(IroAlpha.compose(color(255, 255, 255, 100), color(0, 0, 255, 80))).isEqualTo(color(92, 92, 255, 100));
        assertThat(IroAlpha.compose(color(255, 255, 255, 30), color(0, 0, 255, 50))).isEqualTo(color(58, 58, 122, 100));
    }

    /**
     * The tolerance applies to each channel. It used to be a range over the packed RGB integer, where
     * red dominates: any strong red counted as "near white" and went clear with it.
     */
    @Test
    public void transparentMatchesEachChannelWithinTolerance() {

        final var src = row(0xFFFFFFFF, 0xFFF8F8F8, 0xFFFA0000, 0xFF000000);

        final var exact = IroAlpha.transparent(src, Color.WHITE, 0);
        assertThat(alphas(exact)).containsExactly(0, 255, 255, 255);

        final var loose = IroAlpha.transparent(src, Color.WHITE, 0.05f);
        assertThat(alphas(loose)).as("near white goes, strong red stays").containsExactly(0, 0, 255, 255);

        assertThatIllegalArgumentException().isThrownBy(() -> IroAlpha.transparent(src, Color.WHITE, 1.5f));
    }

    private static Color color(final int r, final int g, final int b, final int a) {
        return new Color(r / 255f, g / 255f, b / 255f, a / 100f);
    }

    private static BufferedImage row(final int... argb) {
        final var ret = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        ret.setRGB(0, 0, argb.length, 1, argb, 0, argb.length);
        return ret;
    }

    private static int[] alphas(final BufferedImage img) {
        final var px = IroMisc.pixels(img);
        final var ret = new int[px.length];
        for (int i = 0; i < px.length; i++) ret[i] = px[i] >>> 24;
        return ret;
    }
}
