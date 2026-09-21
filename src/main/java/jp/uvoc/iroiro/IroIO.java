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

import java.awt.image.RenderedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;


/**
 * Writing through ImageIO, with the retries it needs, and the text metadata (PNG {@code tEXt} and
 * the like) it can carry. Reading an image's size without decoding it is {@link IroHeader}'s.
 *
 * @author takahashikzn
 */
public final class IroIO {

    private IroIO() { }

    public enum ImgType {
        JPG, PNG, GIF, BMP, TIFF;

        public static boolean alphaSupported(final String ext) { return Set.of("png", "gif", "tiff").contains(ext.toLowerCase(Locale.ROOT)); }

        /** @return {@code null} when the name is none of these */
        public static ImgType resolve(final String name) {
            for (final var x: values())
                if (x.name().equals(name.toUpperCase(Locale.ROOT))) return x;

            return null;
        }
    }

    /**
     * {@link ImageIO#write}, retried on an opaque copy when no writer takes the image as it is (a
     * JPEG or BMP writer refuses alpha, for one).
     *
     * @param format an ImageIO format name such as {@code "png"} or {@code "jpeg"}
     */
    public static void write(final RenderedImage img, final String format, final OutputStream out) throws IOException {
        if (!ImageIO.write(img, format, out)) //
            ImageIO.write(IroAlpha.removeAlpha(img), format, out);
    }

    /**
     * @param format an ImageIO format name such as {@code "png"} or {@code "jpeg"}
     * @param quality 0 to 1 for a format that compresses lossily; negative leaves it to the writer
     */
    public static void writeImage(final RenderedImage in, final String format, final float quality, final OutputStream out) throws IOException {

        final var writer = writer(format);

        try (var ios = ImageIO.createImageOutputStream(out)) {
            final var param = writeParam(writer, quality);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(in, null, null), param);
        } catch (final IIOException e) {
            // The JDK's JPEG writer has no alpha support and rejects such an image with this message
            // (libjpeg's JERR_BAD_IN_COLORSPACE); flatten it and write again
            if (isJPEG(format) && "Bogus input colorspace".equals(e.getMessage())) {
                writeImage(IroAlpha.removeAlpha(in), format, quality, out);
                return;
            }

            throw e;
        } finally {
            writer.dispose();
        }
    }

    public static void writeImage(final RenderedImage in, final ImgType type, final float quality, final OutputStream out) throws IOException {
        writeImage(in, type.name().toLowerCase(Locale.ROOT), quality, out);
    }

    public static byte[] writeImage(final RenderedImage in, final ImgType type, final float quality) throws IOException {
        final var out = new ByteArrayOutputStream();
        writeImage(in, type, quality, out);
        return out.toByteArray();
    }

    private static boolean isJPEG(final String format) { return "jpg".equalsIgnoreCase(format) || "jpeg".equalsIgnoreCase(format); }

    /** TIFF with ZLib compression. */
    public static void writeTIFF(final RenderedImage image, final ImageOutputStream ios) throws IOException {

        final var writer = writer("TIFF");
        try {
            final var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType("ZLib");

            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }

        ios.flush();
    }

    public static byte[] toTIFF(final RenderedImage img) throws IOException {

        final var out = new ByteArrayOutputStream();
        try (var ios = new MemoryCacheImageOutputStream(out)) {
            writeTIFF(img, ios);
        }
        return out.toByteArray();
    }

    private static ImageWriter writer(final String name) { return ImageIO.getImageWritersByFormatName(name).next(); }

    private static ImageWriteParam writeParam(final ImageWriter writer, final float quality) {

        final var param = writer.getDefaultWriteParam();

        if (quality < 0 || !param.canWriteCompressed()) return param;

        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); // this leaves the compression type null
        param.setCompressionType(param.getCompressionTypes()[0]);
        param.setCompressionQuality(quality); // which this needs

        return param;
    }

    // --------------------------------------------------------------------- metadata

    /** The text entries (PNG {@code tEXt} and the like) of the first image, by keyword. Empty when unreadable. */
    public static Map<String, String> readMetadata(final InputStream in) throws IOException {

        final var ret = new HashMap<String, String>();

        try (var iis = ImageIO.createImageInputStream(in)) {
            final var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return ret;

            final var reader = readers.next();
            try {
                reader.setInput(iis, true);

                final var metadata = reader.getImageMetadata(0);
                if (metadata != null && metadata.getAsTree(IIOMetadataFormatImpl.standardMetadataFormatName) instanceof IIOMetadataNode root) {
                    final var entries = root.getElementsByTagName("TextEntry");
                    for (int i = 0, n = entries.getLength(); i < n; i++)
                        if (entries.item(i) instanceof IIOMetadataNode node) //
                            ret.put(node.getAttribute("keyword"), node.getAttribute("value"));
                }
            } finally { reader.dispose(); }
        }

        return ret;
    }

    /** Copy the image, adding {@code entries} as text entries. Writes nothing when no reader takes {@code in}. */
    public static void writeMetadata(final InputStream in, final OutputStream out, final Map<String, String> entries) throws IOException {

        try (var iis = ImageIO.createImageInputStream(in); var ios = ImageIO.createImageOutputStream(out)) {
            final var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return;

            final var reader = readers.next();
            try {
                reader.setInput(iis, true);

                final var image = reader.readAll(0, null);
                for (final var e: entries.entrySet()) addTextEntry(image.getMetadata(), e.getKey(), e.getValue());

                final var writer = ImageIO.getImageWriter(reader);
                if (writer == null) throw new IIOException("no writer for " + reader.getFormatName());
                try {
                    writer.setOutput(ios);
                    writer.write(image);
                } finally { writer.dispose(); }
            } finally { reader.dispose(); }
        }
    }

    private static void addTextEntry(final IIOMetadata metadata, final String key, final String value) throws IIOInvalidTreeException {

        final var textEntry = new IIOMetadataNode("TextEntry");
        textEntry.setAttribute("keyword", key);
        textEntry.setAttribute("value", value);

        final var text = new IIOMetadataNode("Text");
        text.appendChild(textEntry);

        final var root = new IIOMetadataNode(IIOMetadataFormatImpl.standardMetadataFormatName);
        root.appendChild(text);

        metadata.mergeTree(IIOMetadataFormatImpl.standardMetadataFormatName, root);
    }
}
