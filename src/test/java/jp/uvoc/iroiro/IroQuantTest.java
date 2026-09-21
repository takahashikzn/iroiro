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

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

import static jp.uvoc.iroiro.IroMeasure.maxError;
import static jp.uvoc.iroiro.IroMisc.pixels;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.assertj.core.api.Assertions.*;


/**
 * Holds {@link IroQuant} to what it was measured to do.
 * <p>
 * The numeric thresholds sit a little below the measured values, so <b>before loosening one, find
 * out why the output got worse.</b> Apart from one public-domain photograph, everything here is
 * synthetic, so behavior that only real photographs expose -- an embedded color profile, say --
 * needs checking on real material as well.
 *
 * @author takahashikzn
 */
@RunWith(JUnitParamsRunner.class)
public class IroQuantTest {

    // ---------------------------------------------------------------- losslessness

    /**
     * At or below the palette size nothing is quantized and not one bit moves. Synthetic images -
     * charts, logos, rules, screenshots - nearly all land here, so this is the path whose breakage
     * would reach furthest.
     */
    @Test
    @Parameters(method = "losslessCases")
    public void lossless(final String name, final BufferedImage src) throws Exception {

        final var out = decode(IroQuant.encode(src));

        assertThat(out.getWidth()).as(name).isEqualTo(src.getWidth());
        assertThat(out.getHeight()).as(name).isEqualTo(src.getHeight());
        assertThat(maxError(src, out)).as(name + ": should be lossless").isZero();
    }

    @SuppressWarnings("unused")
    private Object[] losslessCases() {
        return new Object[] { //
            new Object[] { "8 colors", flat(200, 150, 8) }, //
            new Object[] { "2 colors", flat(64, 64, 2) }, //
            new Object[] { "256 grays", ramp(256, 64) }, //
            new Object[] { "mixed alpha", alpha(120, 120) }, //
            new Object[] { "1x1", flat(1, 1, 1) }, //
        };
    }

    /** Exactly 256 colors is lossless, 257 is not. The boundary is off by one or it is not. */
    @Test
    public void losslessBoundary() throws Exception {

        assertThat(maxError(distinct(64, 64, 256), decode(IroQuant.encode(distinct(64, 64, 256))))).isZero();
        assertThat(maxError(distinct(64, 64, 257), decode(IroQuant.encode(distinct(64, 64, 257))))).isPositive();
    }

    // ---------------------------------------------------------------- pixel access

    /**
     * {@link IroQuant} reads the raster directly for the types that matter rather than going through
     * {@code getRGB}. Run every type ImageIO and Java2D actually produce through it, to catch a band
     * order taken on faith. Sub-images are in the list because their raster carries an offset the
     * fast path must not assume away.
     */
    @Test
    @Parameters(method = "rasterTypes")
    public void rasterTypes(final String name, final BufferedImage src) throws Exception {
        assertThat(maxError(src, decode(IroQuant.encode(src)))).as(name).isZero();
    }

    @SuppressWarnings("unused")
    private Object[] rasterTypes() {

        final var base = flat(120, 80, 6);

        return new Object[] { //
            new Object[] { "INT_ARGB", convert(base, BufferedImage.TYPE_INT_ARGB) }, //
            new Object[] { "INT_RGB", convert(base, BufferedImage.TYPE_INT_RGB) }, //
            new Object[] { "INT_BGR", convert(base, BufferedImage.TYPE_INT_BGR) }, //
            new Object[] { "3BYTE_BGR", convert(base, BufferedImage.TYPE_3BYTE_BGR) }, //
            new Object[] { "4BYTE_ABGR", convert(base, BufferedImage.TYPE_4BYTE_ABGR) }, //
            new Object[] { "4BYTE_ABGR_PRE", convert(base, BufferedImage.TYPE_4BYTE_ABGR_PRE) }, //
            new Object[] { "BYTE_GRAY", convert(base, BufferedImage.TYPE_BYTE_GRAY) }, //
            new Object[] { "BYTE_INDEXED", convert(base, BufferedImage.TYPE_BYTE_INDEXED) }, //
            // the raster carries an offset; the fast path must decline these
            new Object[] { "subimage/INT_ARGB", convert(base, BufferedImage.TYPE_INT_ARGB).getSubimage(10, 10, 60, 40) }, //
            new Object[] { "subimage/3BYTE_BGR", convert(base, BufferedImage.TYPE_3BYTE_BGR).getSubimage(10, 10, 60, 40) }, //
        };
    }

    // ------------------------------------------------------------------ color space

    /**
     * <b>ImageIO does not apply a PNG's iCCP.</b> Emit the raw values and the color is arithmetically
     * right but displays wrong, so a profiled source has to be converted before it is reduced.
     */
    @Test
    public void embeddedProfileIsApplied() throws Exception {

        final var png = withProfile(noise(64, 64), ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB));
        final var raw = decode(png);
        final var srgb = IroQuant.toSRGB(raw, png);

        assertThat(srgb).as("a profiled source must be converted").isNotSameAs(raw);
        assertThat(maxError(raw, srgb)).as("pixels must actually move").isGreaterThan(8);
    }

    /** Untagged output lets a color managed viewer assume something other than sRGB and shift it. */
    @Test
    public void outputIsTaggedSRGB() throws Exception {

        assertThat(chunks(IroQuant.encode(ramp(256, 64)))).contains("sRGB", "gAMA");
        assertThat(chunks(IroQuant.encode(noise(200, 200)))).contains("sRGB", "gAMA");
    }

    /** No profile, no change. A malformed one must not throw either. */
    @Test
    public void noProfileIsLeftAlone() throws Exception {

        final var img = flat(32, 32, 4);

        assertThat(IroQuant.toSRGB(img, null)).isSameAs(img);
        assertThat(IroQuant.toSRGB(img, IroQuant.encode(img))).isSameAs(img);
        assertThat(IroQuant.toSRGB(img, new byte[] { 1, 2, 3 })).isSameAs(img);
    }

    // ------------------------------------------------------------------ output shape

    /** Drop the bit depth when the palette is small, or a palette image carries padding for nothing. */
    @Test
    @Parameters({ "2, 1", "4, 2", "16, 4", "17, 8", "200, 8" })
    public void bitDepth(final int colors, final int expected) throws Exception {
        assertThat(IroQuant.encode(flat(64, 64, colors))[24]).as(colors + " colors").isEqualTo((byte) expected);
    }

    /** No tRNS when nothing is translucent; writing one for a fully opaque palette is waste. */
    @Test
    public void trnsOnlyWhenNeeded() throws Exception {

        assertThat(chunks(IroQuant.encode(flat(64, 64, 8)))).doesNotContain("tRNS");
        assertThat(chunks(IroQuant.encode(alpha(64, 64)))).contains("tRNS");
    }

    /**
     * A photograph can be written as truecolor instead. A renderer that will not consider JPEG for a
     * palette PNG needs that, when the PNG file itself is not what gets delivered.
     */
    @Test
    public void palettizeLossyCanBeTurnedOff() throws Exception {

        final var img = noise(300, 300);

        assertThat(IroQuant.encode(img, IroQuant.Opts.DEFAULT)[25]).as("palette by default").isEqualTo((byte) 3);
        assertThat(IroQuant.encode(img, IroQuant.Opts.DEFAULT.palettizeLossy(false))[25]).as("truecolor").isIn((byte) 2, (byte) 6);
        // lossless material stays a palette whatever the setting says
        assertThat(IroQuant.encode(flat(64, 64, 8), IroQuant.Opts.DEFAULT.palettizeLossy(false))[25]).isEqualTo((byte) 3);
    }

    // ------------------------------------------------------------------ determinism

    /**
     * The per-pixel and per-bucket passes are split across threads. Every split is over an
     * independent range, so the output must not depend on how it was divided -- and this is the
     * kind of breakage that shows up as a rare, unreproducible artifact rather than a failure.
     */
    @Test
    public void parallelMatchesSerial() throws Exception {

        final var img = noise(1027, 513);

        assertThat(IroQuant.encode(img, IroQuant.Opts.DEFAULT)).isEqualTo(IroQuant.encode(img, IroQuant.Opts.DEFAULT.parallel(false)));
    }

    /** Row packing must restart at byte boundaries, even when the width is not byte-aligned. */
    @Test
    @Parameters({ "2", "4", "16", "256" })
    public void parallelPackedRowsMatchSerial(final int colors) throws Exception {

        final var src = flat(1027, 513, colors);
        final var parallel = IroQuant.encode(src, IroQuant.Opts.DEFAULT);
        assertThat(parallel).isEqualTo(IroQuant.encode(src, IroQuant.Opts.DEFAULT.parallel(false)));
        assertThat(maxError(src, decode(parallel))).isZero();
    }

    /** A filter's previous row must be the original row above a band, never a row of zeros. */
    @Test
    @Parameters({ "false", "true" })
    public void parallelTruecolorFiltersMatchSerial(final boolean alpha) throws Exception {

        final var src = noise(1027, 513);
        if (alpha) for (int y = 0; y < src.getHeight(); y++)
            for (int x = 0; x < src.getWidth(); x++)
                src.setRGB(x, y, ((x + y) & 255) << 24 | src.getRGB(x, y) & 0xFFFFFF);
        final var o = IroQuant.Opts.DEFAULT.maxColors(32).palettizeLossy(false);
        final var parallel = IroQuant.encode(src, o);
        assertThat(parallel[25]).isEqualTo((byte) (alpha ? 6 : 2));
        assertThat(parallel).isEqualTo(IroQuant.encode(src, o.parallel(false)));
        assertThat(pixels(decode(parallel))).isEqualTo(pixels(decode(IroQuant.encode(src, o.palettizeLossy(true)))));
    }

    // -------------------------------------------------------------------- limits

    /**
     * {@link IroQuant.Opts} is public, so a caller can reach every one of these. Let through, each
     * gives silently wrong output rather than an error: past 256 the palette index wraps and the extra
     * colors become color 0, and past {@link IroQuant.Opts#MAX_IMPORTANCE} the per-pixel weight
     * overflows its byte and the histogram counts go negative.
     */
    @Test
    public void rejectsUnusableOpts() {

        final var d = IroQuant.Opts.DEFAULT;

        assertThatIllegalArgumentException().as("over the format's palette").isThrownBy(() -> d.maxColors(257));
        assertThatIllegalArgumentException().as("empty palette").isThrownBy(() -> d.maxColors(0));
        assertThatIllegalArgumentException().as("weight past a signed byte").isThrownBy(() -> d.importance(IroQuant.Opts.MAX_IMPORTANCE + 1));
        assertThatIllegalArgumentException().as("negative k-means").isThrownBy(() -> d.kmeans(-1));
        assertThatIllegalArgumentException().as("dither NaN").isThrownBy(() -> d.dither(Double.NaN));
        assertThatIllegalArgumentException().as("dither over 1").isThrownBy(() -> d.dither(1.5));
        assertThatIllegalArgumentException().as("no such zlib level").isThrownBy(() -> d.deflate(0));
        assertThatIllegalArgumentException().as("no such zlib level").isThrownBy(() -> d.deflate(10));

        assertThat(d.maxColors(256).maxColors()).as("256 is the ceiling, not over it").isEqualTo(256);
    }

    /** {@code width * height} in int wraps, and a wrapped count passes a length check it should fail. */
    @Test
    @Parameters({ "65536, 65536", "46341, 46341", "2147483647, 2" })
    public void rejectsOverflowingDimensions(final int width, final int height) {

        assertThatIllegalArgumentException() //
            .as(width + "x" + height).isThrownBy(() -> IroQuant.encode(new int[16], width, height, IroQuant.Opts.DEFAULT));
    }

    // ------------------------------------------------------------------ profiles

    /**
     * A 16 bit image cannot be reinterpreted where it lies, and for a gray one {@code getRGB} is no
     * way around that: the image's own model is linear gray, so it would bend the very numbers the
     * embedded profile is there to interpret. Reading the raster is what makes this case work.
     */
    @Test
    public void wideGraySamplesAreConverted() throws Exception {

        final var img = new BufferedImage(64, 64, BufferedImage.TYPE_USHORT_GRAY);
        final var raster = img.getRaster();
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) raster.setSample(x, y, 0, (x * 1024 + y * 16) & 0xFFFF);

        final var png = withProfile(img, ICC_Profile.getInstance(ColorSpace.CS_GRAY));
        final var src = decode(png);

        assertThat(src.getRaster().getTransferType()).as("the fixture must really be 16 bit").isEqualTo(DataBuffer.TYPE_USHORT);
        assertThat(IroQuant.toSRGB(src, png)).as("the profile must be applied, not skipped").isNotSameAs(src);
    }

    /**
     * The one band of a palette holds indexes, not samples, so a one component profile matches it by
     * band count and would read those indexes as gray levels. Leave it alone instead.
     */
    @Test
    public void paletteUnderAGrayProfileIsLeftAlone() throws Exception {

        final var img = new BufferedImage(32, 32, BufferedImage.TYPE_BYTE_INDEXED);
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) img.setRGB(x, y, (x * 8) << 16 | (y * 8) << 8 | 0x40);

        final var png = withProfile(img, ICC_Profile.getInstance(ColorSpace.CS_GRAY));
        final var src = decode(png);

        assertThat(IroQuant.toSRGB(src, png)).as("half converting is worse than not converting").isSameAs(src);
    }

    /**
     * A few KB of deflated zeros inflate without bound. The heap that would take is an
     * {@link OutOfMemoryError}, which the caller's fallback cannot catch, so the profile has to be
     * abandoned rather than read.
     */
    @Test
    public void anInflationBombIsNotRead() throws Exception {

        final var img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        final var png = withChunk(image(img), "iCCP", bomb());
        final var src = decode(png);

        assertThat(IroQuant.toSRGB(src, png)).as("must give up rather than allocate").isSameAs(src);
    }

    private static byte[] bomb() {

        final var body = new ByteArrayOutputStream();
        body.write('p');
        body.write(0);
        body.write(0); // name, NUL, deflate
        body.writeBytes(deflate(new byte[1 << 26]));
        return body.toByteArray();
    }

    /** Write {@code img} to PNG and give it an {@code iCCP} carrying {@code profile}. */
    private static byte[] withProfile(final BufferedImage img, final ICC_Profile profile) throws IOException {

        final var body = new ByteArrayOutputStream();
        body.write('p');
        body.write(0);
        body.write(0); // name, NUL, deflate
        body.writeBytes(deflate(profile.getData()));

        return withChunk(image(img), "iCCP", body.toByteArray());
    }

    private static byte[] image(final BufferedImage img) throws IOException {

        final var bo = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }

    private static byte[] deflate(final byte[] raw) {

        final var d = new Deflater(6);
        try {
            d.setInput(raw);
            d.finish();

            final var out = new ByteArrayOutputStream();
            final var buf = new byte[1 << 16];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));

            return out.toByteArray();
        } finally { d.end(); }
    }

    /** Splice a chunk in after IHDR, which has to stay first. */
    private static byte[] withChunk(final byte[] png, final String type, final byte[] data) throws IOException {

        final int at = 8 + 4 + 4 + 13 + 4; // signature, then IHDR
        final var out = new ByteArrayOutputStream();
        out.write(png, 0, at);

        out.writeBytes(be(data.length));

        final var typed = new ByteArrayOutputStream();
        typed.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        typed.writeBytes(data);
        out.writeBytes(typed.toByteArray());

        final var crc = new CRC32();
        crc.update(typed.toByteArray());
        out.writeBytes(be((int) crc.getValue()));

        out.write(png, at, png.length - at);

        return out.toByteArray();
    }

    private static byte[] be(final int x) {
        return new byte[] { (byte) (x >>> 24), (byte) (x >>> 16), (byte) (x >>> 8), (byte) x };
    }

    // ---------------------------------------------------------------------- quality

    /** The reduced form must be smaller than the plain one, or there was no point reducing it. */
    @Test
    public void smallerThanImageIO() throws Exception {

        for (final var img: new BufferedImage[] { flat(400, 300, 8), noise(400, 300) }) {
            final var bo = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", bo);
            assertThat(IroQuant.encode(img).length).isLessThan(bo.size());
        }
    }

    /**
     * A real photograph, not a synthetic one: a truecolor PNG of cloud and sea must come out much
     * smaller as a palette PNG and still close to the original.
     * <p>
     * The fixture is "Seroja before Landfall" (NASA Worldview), a work of NASA in the
     * public domain, taken unchanged from Wikimedia Commons on 2026-09-21:
     * https://commons.wikimedia.org/wiki/File:Seroja_before_Landfall.png (777x465, 8-bit RGB,
     * 629,968 bytes, SHA-1 295aa2ce5fb830e70adb737503bc0dcaaf5eec5b).
     * <p>
     * Measured with the default options: 260,135 bytes (41%), SSIM 0.9953, Oklab error mean 0.51 and
     * p95 1.19. The thresholds sit a little below that.
     */
    @Test
    public void realPhotographShrinks() throws Exception {

        final byte[] png;
        try (final var in = IroQuantTest.class.getResourceAsStream("IroQuantTest-cyclone.png")) {
            assertThat(in).as("fixture").isNotNull();
            png = in.readAllBytes();
        }
        final var src = IroQuant.toSRGB(decode(png), png);

        final var out = IroQuant.encode(src);
        assertThat(out[25]).as("written as a palette").isEqualTo((byte) 3);
        assertThat(out.length).as("reduced size").isLessThan(png.length * 45 / 100);

        final var reduced = decode(out);
        assertThat(IroMeasure.ssim(src, reduced)).as("SSIM").isGreaterThan(0.99);
        final var error = IroMeasure.colorError(src, reduced, IroMeasure.Background.BLACK);
        assertThat(error.mean()).as("mean color error").isLessThan(0.6);
        assertThat(error.p95()).as("p95 color error").isLessThan(1.4);
    }

    // ------------------------------------------------------------------- fixtures

    private static BufferedImage flat(final int w, final int h, final int colors) {

        final var pal = new int[colors];
        final var rnd = new Random(1);
        for (int i = 0; i < colors; i++) pal[i] = 0xFF000000 | rnd.nextInt(1 << 24);

        final var i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, pal[(x / 7 + y / 5) % colors]);

        return i;
    }

    /** An image with exactly n colors, for the boundary. */
    private static BufferedImage distinct(final int w, final int h, final int n) {

        final var i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) i.setRGB(x, y, 0xFF000000 | ((y * w + x) % n) * 37);

        return i;
    }

    private static BufferedImage ramp(final int w, final int h) {

        final var i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int v = x % 256;
                i.setRGB(x, y, 0xFF000000 | v << 16 | v << 8 | v);
            }

        return i;
    }

    private static BufferedImage noise(final int w, final int h) {

        final var rnd = new Random(7);
        final var i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                final int r = cl((int) (128 + 110 * Math.sin(x * 0.02) * Math.cos(y * 0.013)) + rnd.nextInt(9) - 4);
                final int g = cl((int) (128 + 110 * Math.sin((x + y) * 0.011)) + rnd.nextInt(9) - 4);
                final int b = cl((int) (128 + 110 * Math.cos(x * 0.007 + y * 0.017)) + rnd.nextInt(9) - 4);
                i.setRGB(x, y, 0xFF000000 | r << 16 | g << 8 | b);
            }

        return i;
    }

    private static BufferedImage alpha(final int w, final int h) {

        final var i = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) //
                i.setRGB(x, y, (x < w / 3) ? 0x00123456 : (x < 2 * w / 3) ? 0x80FF3300 : 0xFF0033FF);

        return i;
    }

    private static BufferedImage convert(final BufferedImage src, final int type) {

        final var i = new BufferedImage(src.getWidth(), src.getHeight(), type);
        final var g = i.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();

        return i;
    }

    // ------------------------------------------------------------------- measures

    private static BufferedImage decode(final byte[] png) throws IOException {

        final var ret = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(ret).as("the output must decode").isNotNull();

        return ret;
    }

    private static HashSet<String> chunks(final byte[] png) {

        final var ret = new HashSet<String>();
        for (int i = 8; i + 12 <= png.length; ) {
            final int len = (png[i] & 0xFF) << 24 | (png[i + 1] & 0xFF) << 16 | (png[i + 2] & 0xFF) << 8 | png[i + 3] & 0xFF;
            if (len < 0 || png.length < i + 12 + len) break;

            ret.add(new String(png, i + 4, 4, java.nio.charset.StandardCharsets.US_ASCII));
            i += 12 + len;
        }

        return ret;
    }

    private static int cl(final int x) { return (x < 0) ? 0 : (255 < x) ? 255 : x; }
}
