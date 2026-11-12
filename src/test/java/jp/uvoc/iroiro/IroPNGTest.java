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
import java.util.Random;
import javax.imageio.ImageIO;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * {@link IroPNG#fast}.
 *
 * @author takahashikzn
 */
public class IroPNGTest {

    /**
     * The pixels come back exactly, with and without alpha, from the int rasters read directly and
     * from the other types read through the color model, in sizes from a single pixel to more rows
     * than one deflate segment holds.
     */
    @ParameterizedTest
    @CsvSource({ //
        "1, 1, 2", // TYPE_INT_ARGB
        "1, 1, 1", // TYPE_INT_RGB
        "300, 7, 2", //
        "1300, 1100, 2", // several bands and deflate segments
        "1300, 1100, 1", //
        "1300, 1100, 3", // TYPE_INT_ARGB_PRE
        "640, 480, 5", // TYPE_3BYTE_BGR
        "640, 480, 10", // TYPE_BYTE_GRAY
    })
    public void fastRoundTrips(final int width, final int height, final int type) throws Exception {

        final var img = new BufferedImage(width, height, type);
        final var rand = new Random(width * 31L + height + type);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                // gradients with noise, plus a transparent margin where the image has alpha
                final int a = (x < width / 8) ? 0 : 0xFF;
                final int c = ((x * 255 / width) << 16) | ((y * 255 / height) << 8) | rand.nextInt(256);
                img.setRGB(x, y, (a << 24) | c);
            }

        final var png = IroPNG.fast(img);
        final var back = ImageIO.read(new ByteArrayInputStream(png));

        assertThat(back.getColorModel().hasAlpha()).isEqualTo(img.getColorModel().hasAlpha());
        assertThat(back.getRGB(0, 0, width, height, null, 0, width)).isEqualTo(img.getRGB(0, 0, width, height, null, 0, width));
    }
}
