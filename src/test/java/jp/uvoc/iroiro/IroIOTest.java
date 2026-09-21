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
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * {@link IroIO}.
 *
 * @author takahashikzn
 */
public class IroIOTest {

    @Test
    public void textMetadataRoundTrips() throws Exception {

        final var png = new ByteArrayOutputStream();
        ImageIO.write(row(0xFF123456), "png", png);

        final var annotated = new ByteArrayOutputStream();
        IroIO.writeMetadata(new ByteArrayInputStream(png.toByteArray()), annotated, Map.of("key", "value"));

        assertThat(IroIO.readMetadata(new ByteArrayInputStream(annotated.toByteArray()))).containsEntry("key", "value");
    }

    /** The JPEG writer refuses alpha; the image is flattened onto white and written anyway. */
    @Test
    public void writeFallsBackToOpaque() throws Exception {

        final var out = new ByteArrayOutputStream();
        IroIO.write(row(0x80FF0000, 0xFF00FF00), "jpeg", out);

        assertThat(ImageIO.read(new ByteArrayInputStream(out.toByteArray()))).isNotNull();
    }

    private static BufferedImage row(final int... argb) {
        final var ret = new BufferedImage(argb.length, 1, BufferedImage.TYPE_INT_ARGB);
        ret.setRGB(0, 0, argb.length, 1, argb, 0, argb.length);
        return ret;
    }
}
