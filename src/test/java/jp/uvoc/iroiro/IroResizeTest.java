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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.*;


/**
 * Holds {@link IroResize} to what it was written for.
 * <p>
 * The reason this exists at all is memory: resizing a large image is usually handed to another
 * process because doing it in the JVM means holding the image at full size. So the case that matters most
 * is {@link #resizingFromBytesDoesNotHoldTheImageAtFullSize}, which is the only one that would
 * notice if the streaming decode were quietly lost.
 *
 * @author takahashikzn
 */
@RunWith(JUnitParamsRunner.class)
public class IroResizeTest {

    // ---------------------------------------------------------------------- contract

    /** The target is exact. Callers place the result by these numbers, so an off-by-one moves layout. */
    @Test
    @Parameters({ "640, 480, 320, 240", "640, 480, 100, 100", "640, 480, 1, 1", "37, 19, 640, 480", "640, 480, 641, 481" })
    public void theTargetSizeIsExact(final int sw, final int sh, final int dw, final int dh) {

        final var out = IroResize.resize(photo(sw, sh), dw, dh);

        assertThat(out.getWidth()).as("width").isEqualTo(dw);
        assertThat(out.getHeight()).as("height").isEqualTo(dh);
    }

    /** Asking for the size it already is must not resample it. */
    @Test
    public void aNoOpIsANoOp() {

        final var src = photo(64, 48);

        assertThat(IroResize.resize(src, 64, 48)).isSameAs(src);
    }

    @Test
    public void rejectsAnImpossibleTarget() {

        final var src = photo(64, 48);

        assertThatIllegalArgumentException().isThrownBy(() -> IroResize.resize(src, 0, 10));
        assertThatIllegalArgumentException().isThrownBy(() -> IroResize.resize(src, 10, -1));
    }

    // ----------------------------------------------------------------------- quality

    /**
     * A flat field must stay flat. Box shrink and Mitchell both average, so any indexing mistake in
     * either shows up here as edges or streaks that were never in the source.
     */
    @Test
    @Parameters({ "600, 400, 200, 133", "600, 400, 97, 61", "600, 400, 599, 399" })
    public void aFlatFieldSurvivesUnchanged(final int sw, final int sh, final int dw, final int dh) {

        final var src = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        final var g = src.createGraphics();
        g.setColor(new Color(0x40, 0x80, 0xC0));
        g.fillRect(0, 0, sw, sh);
        g.dispose();

        final var out = IroResize.resize(src, dw, dh);

        for (int y = 0; y < dh; y++)
            for (int x = 0; x < dw; x++) //
                assertThat(out.getRGB(x, y) & 0xFFFFFF).as("(%d,%d)".formatted(x, y)).isEqualTo(0x4080C0);
    }

    /**
     * Alpha is resampled premultiplied. Without that the color sitting under a fully transparent
     * pixel is averaged in as if it were visible, and it bleeds into whatever is beside it - which
     * is why a transparent region here is filled with a color that would be obvious if it leaked.
     */
    @Test
    public void aTransparentRegionDoesNotBleedIntoItsNeighbour() {

        final int n = 400;
        final var src = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) //
                src.setRGB(x, y, (x < n / 2) ? 0x00FF0000 : 0xFF0000FF); // transparent red | opaque blue

        final var out = IroResize.resize(src, n / 4, n / 4);

        // a column well inside the opaque half must still be pure blue
        final int x = out.getWidth() - 2;
        for (int y = 1; y < out.getHeight() - 1; y++) {
            final int c = out.getRGB(x, y);

            assertThat(c >>> 24).as("alpha at (%d,%d)".formatted(x, y)).isEqualTo(0xFF);
            assertThat(c >>> 16 & 0xFF).as("red must not have bled in at (%d,%d)".formatted(x, y)).isZero();
        }
    }

    /** Every raster type ImageIO and Java2D actually produce, to catch a band order taken on faith. */
    @Test
    @Parameters({ "1", "2", "5", "6", "10", "13" })
    public void everyRasterTypeIsResampled(final int type) {

        final var src = new BufferedImage(200, 150, type);
        final var g = src.createGraphics();
        g.drawImage(photo(200, 150), 0, 0, null);
        g.dispose();

        final var out = IroResize.resize(src, 50, 40);

        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(out.getHeight()).isEqualTo(40);
    }

    // ------------------------------------------------------------------- from bytes

    /** Every band starts with its own Mitchell halo, including upscaling and uneven box shrinking. */
    @Test
    @Parameters({ "1201, 803, 311, 201", "37, 19, 641, 479", "2049, 1537, 83, 61", "1025, 769, 513, 517" })
    public void parallelMatchesSerial(final int sw, final int sh, final int dw, final int dh) {

        final var src = photo(sw, sh);
        final var serial = IroResize.resize(src, dw, dh, false);
        final var parallel = IroResize.resize(src, dw, dh, true);
        assertThat(IroMisc.pixels(parallel)).isEqualTo(IroMisc.pixels(serial));
    }

    @Test
    @Parameters({ "1", "2", "5", "6", "7", "10", "13" })
    public void parallelRasterReadersMatchSerial(final int type) {

        final var src = new BufferedImage(901, 703, type);
        final var g = src.createGraphics();
        g.drawImage(photo(901, 703), 0, 0, null);
        g.dispose();
        if (src.getColorModel().hasAlpha()) for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                src.setRGB(x, y, ((x + y) & 255) << 24 | src.getRGB(x, y) & 0xFFFFFF);
        assertThat(IroMisc.pixels(IroResize.resize(src, 413, 307, true))).isEqualTo(IroMisc.pixels(IroResize.resize(src, 413, 307, false)));
    }

    @Test
    @Parameters({ "png", "jpeg" })
    public void parallelSubsampledDecodeMatchesSerial(final String format) throws IOException {

        final var bytes = encode(photo(2003, 1507), format);
        assertThat(IroMisc.pixels(IroResize.resize(bytes, 501, 377, true))).isEqualTo(IroMisc.pixels(IroResize.resize(bytes, 501, 377, false)));
    }

    @Test
    @Parameters({ "png", "jpeg" })
    public void resizingFromBytesGivesTheSameSizeAsFromAnImage(final String format) throws IOException {

        final var bytes = encode(photo(1200, 900), format);
        final var out = IroResize.resize(bytes, 300, 225);

        assertThat(out.getWidth()).isEqualTo(300);
        assertThat(out.getHeight()).isEqualTo(225);
    }

    /**
     * <b>This is the reason the class exists.</b> Decoding at full resolution and shrinking
     * afterwards costs a full size raster; subsampling as the decoder reads does not. Measured on
     * this fixture: <b>2.1MB through the subsampled read, 21.0MB when the image is decoded whole
     * first</b>. The bound sits between them with room for the noise a heap reading carries, so
     * losing the subsampling fails this rather than merely getting slower.
     */
    @Test
    public void resizingFromBytesDoesNotHoldTheImageAtFullSize() throws IOException {

        final var bytes = encode(photo(3000, 2000), "jpeg");

        final var before = used();
        final var out = IroResize.resize(bytes, 750, 500);
        final var after = used();

        assertThat(out.getWidth()).isEqualTo(750);

        // measured: 2.1MB here, 21.0MB without the subsampling
        assertThat(after - before).as("bytes held while resizing").isLessThan(8L * 1024 * 1024);
    }

    @Test
    public void bytesThatAreNotAnImageAreReported() {

        assertThatExceptionOfType(IOException.class) //
            .isThrownBy(() -> IroResize.resize("not an image".getBytes(StandardCharsets.US_ASCII), 10, 10));
    }

    // -------------------------------------------------------------------- fixtures

    /** Something with detail at every scale, so a filtering mistake has somewhere to show up. */
    private static BufferedImage photo(final int w, final int h) {

        final var ret = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int r = (x * 255 / Math.max(1, w - 1)) ^ (y & 0x0F) << 3;
                final int g = y * 255 / Math.max(1, h - 1);
                final int b = ((x >> 2) + (y >> 2)) & 0xFF;
                ret.setRGB(x, y, clamp(r) << 16 | clamp(g) << 8 | clamp(b));
            }

        return ret;
    }

    private static int clamp(final int x) { return Math.clamp(0, x, 255); }

    private static byte[] encode(final BufferedImage img, final String format) throws IOException {

        final var bo = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, bo)).as("no writer for " + format).isTrue();

        return bo.toByteArray();
    }

    /** Decoding it back proves the fixture is a real file rather than an empty array. */
    @SuppressWarnings("unused")
    private static BufferedImage decode(final byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private static long used() {

        final var rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) System.gc();

        return rt.totalMemory() - rt.freeMemory();
    }
}
