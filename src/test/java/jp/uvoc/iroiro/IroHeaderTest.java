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

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Holds {@link IroHeader} to reading the same dimensions a decoder would, and to staying quiet on
 * anything else.
 * <p>
 * The point of the class is to answer without a decoder, so the reference here is what ImageIO
 * reports for the same bytes. Where the two could disagree the answer must be ImageIO's, because
 * that is what the rest of the pipeline sees.
 *
 * @author takahashikzn
 */
@RunWith(JUnitParamsRunner.class)
public class IroHeaderTest {

    // ------------------------------------------------------------------- agreement

    /**
     * Every format the class claims, against the decoder it is standing in for. Written through
     * ImageIO so the fixture is whatever this JDK actually produces, rather than a file that
     * happens to be shaped the way the parser expects.
     */
    @Test
    @Parameters({ "png", "jpeg", "gif", "bmp" })
    public void agreesWithTheDecoder(final String format) throws IOException {

        for (final var img: new BufferedImage[] { rgb(37, 19), rgb(1, 1), rgb(640, 480), rgb(1920, 1) }) {

            final var bytes = encode(img, format);
            if (bytes == null) continue; // no writer for it on this JDK

            final var size = IroHeader.size(bytes);

            assertThat(size).as(format + " " + img.getWidth() + "x" + img.getHeight()).isPresent();
            assertThat(size.get().width()).as(format + " width").isEqualTo(decodedWidth(bytes));
            assertThat(size.get().height()).as(format + " height").isEqualTo(decodedHeight(bytes));
        }
    }

    /** A JPEG carries its size in a frame header that sits after any number of other segments. */
    @Test
    public void jpegSizeIsFoundPastTheOtherSegments() throws IOException {

        final var bytes = encode(rgb(97, 43), "jpeg");

        assertThat(IroHeader.size(bytes)).contains(new IroHeader.Size(97, 43));
    }

    // ---------------------------------------------------------------------- silence

    /**
     * An unknown format has to come back empty rather than guessed at: the caller answers an empty
     * by asking a real decoder, and a wrong size would instead be believed.
     */
    @Test
    @Parameters(method = "notImages")
    public void unknownBytesAreNotGuessedAt(final String what, final byte[] bytes) {

        assertThat(IroHeader.size(bytes)).as(what).isEmpty();
    }

    @SuppressWarnings("unused")
    private Object[] notImages() {

        return new Object[] { //
            new Object[] { "empty", new byte[0] }, //
            new Object[] { "null", null }, //
            new Object[] { "text", "not an image at all, just some bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII) }, //
            new Object[] { "pdf", head(new byte[] { '%', 'P', 'D', 'F', '-', '1', '.', '7' }) }, //
            new Object[] { "zip", head(new byte[] { 'P', 'K', 3, 4 }) }, //
            new Object[] { "tiff", head(new byte[] { 'I', 'I', 42, 0 }) } //
        };
    }

    /**
     * {@code BM} is two bytes, which some unrelated file will match sooner or later. The size that
     * follows it would look perfectly plausible, so the header has to be corroborated before it is
     * believed - here by the declared file size and the offset to the pixels.
     */
    @Test
    public void bmpIsNotClaimedOnTheSignatureAlone() {

        final var rnd = new Random(42);
        int claimed = 0;

        for (int i = 0; i < 20000; i++) {
            final var b = new byte[16 + rnd.nextInt(200)];
            rnd.nextBytes(b);
            b[0] = 'B';
            b[1] = 'M';

            if (IroHeader.size(b).isPresent()) claimed++;
        }

        assertThat(claimed).as("random bytes wearing a BMP signature").isZero();
    }

    /** ... and a real one is still read. */
    @Test
    public void bmpIsReadWhenItIsOne() {

        final var b = new byte[54 + 16];
        b[0] = 'B';
        b[1] = 'M';
        le32(b, 2, b.length); // file size
        le32(b, 10, 54); // offset to the pixels
        le32(b, 14, 40); // BITMAPINFOHEADER
        le32(b, 18, 4);
        le32(b, 22, -2); // negative height: rows run top down

        assertThat(IroHeader.size(b)).contains(new IroHeader.Size(4, 2));
    }

    /**
     * The input is a file someone else supplied. A header that lies about its own lengths must come
     * back as "unknown", never as an exception - the caller has a decoder to fall back to, but only
     * if it gets the chance.
     */
    @Test
    public void aTruncatedOrLyingHeaderDoesNotThrow() throws IOException {

        for (final var format: new String[] { "png", "jpeg", "gif", "bmp" }) {

            final var bytes = encode(rgb(64, 64), format);
            if (bytes == null) continue;

            for (int cut = 1; cut < bytes.length; cut += Math.max(1, bytes.length / 97)) {
                final var b = new byte[cut];
                System.arraycopy(bytes, 0, b, 0, cut);

                IroHeader.size(b); // must return, whatever it decides
            }
        }

        final var rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            final var b = new byte[1 + rnd.nextInt(64)];
            rnd.nextBytes(b);

            IroHeader.size(b);
        }
    }

    // ------------------------------------------------------------------- fixtures

    private static BufferedImage rgb(final int w, final int h) {

        final var ret = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) ret.setRGB(x, y, x * 7 + y * 13);

        return ret;
    }

    /** @return null when this JDK has no writer for the format */
    private static byte[] encode(final BufferedImage img, final String format) throws IOException {

        final var bo = new ByteArrayOutputStream();

        return ImageIO.write(img, format, bo) ? bo.toByteArray() : null;
    }

    private static int decodedWidth(final byte[] bytes) throws IOException { return decode(bytes).getWidth(); }

    private static int decodedHeight(final byte[] bytes) throws IOException { return decode(bytes).getHeight(); }

    private static BufferedImage decode(final byte[] bytes) throws IOException {

        final var ret = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(ret).as("the fixture must decode").isNotNull();

        return ret;
    }

    /** {@code sig} followed by filler, long enough to get past the length guards. */
    private static byte[] head(final byte[] sig) {

        final var ret = new byte[128];
        new Random(1).nextBytes(ret);
        System.arraycopy(sig, 0, ret, 0, sig.length);

        return ret;
    }

    private static void le32(final byte[] b, final int at, final int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }
}
