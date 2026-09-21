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
import java.util.Random;

import jp.uvoc.iroiro.IroHalftone.Algorithm;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * @author takahashikzn
 */
@RunWith(JUnitParamsRunner.class)
public class IroHalftoneTest {

    /**
     * An 8x8 screen holds each of its 64 levels exactly once, so a uniform gray lights one more cell
     * per step of 4. A matrix with uneven levels reproduces some tones too light and others too dark.
     */
    @Test
    @Parameters({ "bayer", "clustered" })
    public void screensReproduceEveryLevelEvenly(final Algorithm algo) {

        for (int gray = 0; gray < 256; gray++) {
            final var src = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB); // getRGB returns the level as is
            for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) src.setRGB(x, y, gray * 0x010101);

            assertThat(whites(IroHalftone.dither(src, algo))).as("gray %d", gray).isEqualTo(Math.min(64, (gray + 3) / 4));
        }
    }

    /** Each output byte takes its own 8 pixels, also when the width is not a multiple of 8. */
    @Test
    @Parameters({ "1", "7", "9", "37", "100" })
    public void thresholdPacksEachPixelIntoItsOwnBit(final int width) {

        final var rnd = new Random(width);
        final var src = new BufferedImage(width, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) for (int x = 0; x < width; x++) src.setRGB(x, y, rnd.nextInt(1 << 24));

        final var out = IroHalftone.threshold(src, 128);

        for (int y = 0; y < 3; y++)
            for (int x = 0; x < width; x++) {
                final int p = src.getRGB(x, y);
                final boolean white = 128 <= ((p >>> 16 & 0xFF) * 54 + (p >>> 8 & 0xFF) * 183 + (p & 0xFF) * 18) >> 8;
                assertThat(out.getRGB(x, y) == 0xFFFFFFFF).as("(%d, %d)", x, y).isEqualTo(white);
            }
    }

    private static int whites(final BufferedImage img) {
        int ret = 0;
        for (final int c: IroMisc.pixels(img)) if (c == 0xFFFFFFFF) ret++;
        return ret;
    }
}
