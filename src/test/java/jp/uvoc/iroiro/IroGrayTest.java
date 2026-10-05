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
import java.util.Random;

import org.junit.Test;

import static org.assertj.core.api.Assertions.*;


/**
 * {@link IroGray}.
 *
 * @author takahashikzn
 */
public class IroGrayTest {

    /** The raster holds the sRGB-encoded levels; {@code getRGB} on it reads them as linear and lightens them. */
    @Test
    public void argb2grayKeepsTheLevelsSRGBEncoded() {

        final var gray = IroGray.argb2gray(row(BufferedImage.TYPE_INT_RGB, 0x000000, 0x404040, 0x808080, 0xC0C0C0, 0xFFFFFF)).img();

        assertThat(gray.getType()).isEqualTo(BufferedImage.TYPE_BYTE_GRAY);
        assertThat(levels(gray)).containsExactly(0, 64, 128, 192, 255);
        assertThat(gray.getRGB(2, 0) & 0xFF).isGreaterThan(128);
    }

    @Test
    public void grayRGBReadsBackTheLevelsOfArgb2gray() {

        final var rnd = new Random(1);
        final var src = new BufferedImage(37, 5, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) for (int x = 0; x < src.getWidth(); x++) src.setRGB(x, y, rnd.nextInt());

        final var levels = levels(IroGray.argb2gray(src).img());
        final var rgb = IroGray.grayRGB(src);

        for (int y = 0, i = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++, i++)
                assertThat(rgb.getRGB(x, y)).as("(%d, %d)", x, y).isEqualTo(0xFF000000 | levels[i] * 0x010101);
    }

    @Test
    public void onWhiteFlattensTheAlphaOntoWhite() {
        assertThat(IroGray.onWhite(0xFF000000)).isZero();
        assertThat(IroGray.onWhite(0x00000000)).isEqualTo(255);
        assertThat(IroGray.onWhite(0x80000000)).isEqualTo(128);
        assertThat(IroGray.onWhite(0x00FF0000)).isEqualTo(255);
    }

    @Test
    public void masksOnlyWhereTheColorModelHasAlpha() {

        final var opaque = IroGray.argb2gray(row(BufferedImage.TYPE_INT_RGB, 0x808080));
        assertThat(opaque.smask().get()).isEmpty();
        assertThat(opaque.imask().get()).isEmpty();

        final var translucent = IroGray.argb2gray(row(BufferedImage.TYPE_INT_ARGB, 0x40000000, 0xC0000000));
        assertThat(levels(translucent.smask().get().orElseThrow())).containsExactly(0x40, 0xC0);
        assertThat(translucent.imask().get()).isPresent();
    }

    private static BufferedImage row(final int type, final int... argb) {
        final var ret = new BufferedImage(argb.length, 1, type);
        ret.setRGB(0, 0, argb.length, 1, argb, 0, argb.length);
        return ret;
    }

    private static int[] levels(final BufferedImage gray) {
        final var data = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        final var ret = new int[data.length];
        for (int i = 0; i < data.length; i++) ret[i] = data[i] & 0xFF;
        return ret;
    }
}
