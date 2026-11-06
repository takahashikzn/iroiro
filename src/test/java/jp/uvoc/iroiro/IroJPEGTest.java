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
import java.io.IOException;
import java.util.Random;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Holds {@link IroJPEG#reconcileColorTransform} to repairing exactly the contradiction it names, and
 * to handing back everything else untouched - the same array, not an equal copy.
 * <p>
 * The fixtures are ImageIO's own JPEGs (JFIF, YCbCr, component ids 1 2 3) with an Adobe marker
 * spliced in, which is the shape of the files met in practice.
 *
 * @author takahashikzn
 */
public class IroJPEGTest {

    // -------------------------------------------------------------------- repair

    /** The repaired file decodes to what the file was before the marker was spliced in. */
    @Test
    public void theContradictionIsRepaired() throws IOException {

        final var plain = jpeg();
        final var broken = withAdobe(plain, 0);
        final var copy = broken.clone();

        final var fixed = IroJPEG.reconcileColorTransform(broken);

        assertThat(broken).as("the input is not written to").isEqualTo(copy);
        assertThat(diff(broken, fixed)).as("bytes changed").isEqualTo(1);
        assertThat(pixels(fixed)).isEqualTo(pixels(plain));
    }

    // ------------------------------------------------------------- left as it is

    @Test
    public void aConsistentFileIsLeftAlone() throws IOException {

        final var plain = jpeg();
        assertSame("JFIF only", plain);
        assertSame("JFIF and an Adobe marker that agrees", withAdobe(plain, 1));
        assertSame("YCCK/CMYK flag", withAdobe(plain, 2));
    }

    /** Without JFIF the Adobe flag is the only word on it, and every reader takes it. */
    @Test
    public void anAdobeMarkerAloneIsBelieved() throws IOException {

        assertSame("Adobe 0, no JFIF", withAdobe(withoutJfif(jpeg()), 0));
    }

    /** Component ids 'R', 'G', 'B' are an RGB file naming itself; there the JFIF marker is the error. */
    @Test
    public void rgbComponentIdsAreBelieved() throws IOException {

        assertSame("ids R G B", withAdobe(rgbIds(jpeg()), 0));
    }

    @Test
    public void grayIsLeftAlone() throws IOException {

        assertSame("one component", withAdobe(jpeg(BufferedImage.TYPE_BYTE_GRAY), 0));
    }

    @Test
    public void notAJpegIsLeftAlone() throws IOException {

        final var bo = new ByteArrayOutputStream();
        ImageIO.write(image(BufferedImage.TYPE_INT_RGB), "png", bo);

        assertSame("png", bo.toByteArray());
        assertSame("empty", new byte[0]);
        assertThat(IroJPEG.reconcileColorTransform(null)).isNull();
    }

    /** Someone else's file: truncated or random bytes come back, never an exception. */
    @Test
    public void malformedInputDoesNotThrow() throws IOException {

        final var broken = withAdobe(jpeg(), 0);
        for (int cut = 1; cut < broken.length; cut++) {
            final var b = new byte[cut];
            System.arraycopy(broken, 0, b, 0, cut);

            IroJPEG.reconcileColorTransform(b);
        }

        final var rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final var b = new byte[2 + rnd.nextInt(64)];
            rnd.nextBytes(b);
            b[0] = (byte) 0xFF;
            b[1] = (byte) 0xD8;

            IroJPEG.reconcileColorTransform(b);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static void assertSame(final String what, final byte[] b) {
        assertThat(IroJPEG.reconcileColorTransform(b)).as(what).isSameAs(b);
    }

    private static BufferedImage image(final int type) {

        final var ret = new BufferedImage(48, 32, type);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 48; x++) ret.setRGB(x, y, 0xFFFFFF - x * 0x030201 - y * 0x010203);

        return ret;
    }

    private static byte[] jpeg() throws IOException { return jpeg(BufferedImage.TYPE_INT_RGB); }

    private static byte[] jpeg(final int type) throws IOException {

        final var bo = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image(type), "jpeg", bo)).isTrue();

        final var ret = bo.toByteArray();
        assertThat(ret[3] & 0xFF).as("ImageIO starts with JFIF").isEqualTo(0xE0);

        return ret;
    }

    /** Splices an APP14 "Adobe" segment in right after SOI. */
    private static byte[] withAdobe(final byte[] b, final int transform) {

        final byte[] app14 = { (byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, (byte) transform };

        final var ret = new byte[b.length + app14.length];
        System.arraycopy(b, 0, ret, 0, 2);
        System.arraycopy(app14, 0, ret, 2, app14.length);
        System.arraycopy(b, 2, ret, 2 + app14.length, b.length - 2);

        return ret;
    }

    /** Drops the APP0 segment that ImageIO writes right after SOI. */
    private static byte[] withoutJfif(final byte[] b) {

        final int len = 2 + ((b[4] & 0xFF) << 8 | b[5] & 0xFF);

        final var ret = new byte[b.length - len];
        System.arraycopy(b, 0, ret, 0, 2);
        System.arraycopy(b, 2 + len, ret, 2, b.length - 2 - len);

        return ret;
    }

    /** Renames the three components of the frame header to 'R', 'G', 'B'. */
    private static byte[] rgbIds(final byte[] b) {

        final var ret = b.clone();
        for (int at = 2; ; ) {
            final int m = ret[at + 1] & 0xFF;
            final int len = (ret[at + 2] & 0xFF) << 8 | ret[at + 3] & 0xFF;

            if (m == 0xC0) {
                ret[at + 10] = 'R';
                ret[at + 13] = 'G';
                ret[at + 16] = 'B';
                return ret;
            }

            at += 2 + len;
        }
    }

    private static int diff(final byte[] x, final byte[] y) {

        assertThat(y).hasSameSizeAs(x);

        int ret = 0;
        for (int i = 0; i < x.length; i++) if (x[i] != y[i]) ret++;

        return ret;
    }

    private static int[] pixels(final byte[] b) throws IOException {

        final var img = ImageIO.read(new ByteArrayInputStream(b));

        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }
}
