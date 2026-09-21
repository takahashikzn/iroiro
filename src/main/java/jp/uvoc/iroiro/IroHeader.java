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

import java.util.Optional;


/**
 * Pixel dimensions straight out of an encoded image's header, without a decoder.
 * <p>
 * ImageIO can do this too, and normally should. This exists for the files it refuses: a JPEG whose
 * embedded profile is malformed makes the reader throw before it ever reports a size, even though
 * the two numbers wanted here sit in plain sight a few bytes in. Reading them directly costs
 * nothing and does not care what the rest of the file contains.
 * <p>
 * Every read is bounds checked against the array. The input is a file someone else supplied, so a
 * truncated or lying header has to come back as "unknown" rather than as an exception.
 * <p>
 * <b>Depends on the JDK only</b>, like the rest of this package.
 *
 * @author takahashikzn
 */
public final class IroHeader {

    private IroHeader() { }

    /** Pixel dimensions as the header declares them, before any orientation or scaling is applied. */
    public record Size(int width, int height) { }

    /**
     * @return empty when these bytes are not one of the formats read here, or when the header does
     * not hold a usable size. The caller is expected to fall back to a real decoder.
     */
    public static Optional<Size> size(final byte[] b) {

        if (b == null || b.length < 16) return Optional.empty();

        // Each of these identifies its own format and returns null for anything else, so the last
        // one is not a fallback: an unrecognized format falls out of the bottom the same as a
        // recognized one whose header does not hold a usable size
        Size ret;
        if ((ret = png(b)) != null) return Optional.of(ret);
        if ((ret = jpeg(b)) != null) return Optional.of(ret);
        if ((ret = gif(b)) != null) return Optional.of(ret);
        if ((ret = bmp(b)) != null) return Optional.of(ret);

        return Optional.empty();
    }

    /** IHDR is required to be the first chunk, so the size is always at a fixed offset. */
    private static Size png(final byte[] b) {

        if (b.length < 24) return null;
        if ((b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G') return null;
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') return null;

        return dim(be32(b, 16), be32(b, 20));
    }

    /**
     * Walk the marker segments to the frame header. The size lives in SOFn, and everything before
     * it - including the profile that trips the decoder - is skipped by length without being read.
     */
    private static Size jpeg(final byte[] b) {

        if ((b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return null;

        int at = 2;
        while (at + 4 <= b.length) {

            if ((b[at] & 0xFF) != 0xFF) return null; // not where a marker should be
            int m = b[at + 1] & 0xFF;

            // fill bytes: any number of 0xFF may pad the gap before a marker
            while (m == 0xFF && at + 2 < b.length) m = b[++at + 1] & 0xFF;

            if (m == 0xD8 || m == 0x01 || (0xD0 <= m && m <= 0xD7)) { // standalone, no length
                at += 2;
                continue;
            }
            if (m == 0xD9 || m == 0xDA) return null; // end of image, or the pixels start
            if (b.length < at + 4) return null; // the fill byte skip may have walked us to the end

            final int len = be16(b, at + 2);
            if (len < 2) return null;

            // SOF0..SOF15, minus the three markers that share the range but are not frame headers
            if (0xC0 <= m && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                if (b.length < at + 9) return null;
                return dim(be16(b, at + 7), be16(b, at + 5)); // height first in a frame header
            }

            at += 2 + len;
        }

        return null;
    }

    /** The logical screen descriptor follows the six byte signature, little endian. */
    private static Size gif(final byte[] b) {

        if (b.length < 10 || b[0] != 'G' || b[1] != 'I' || b[2] != 'F') return null;

        return dim(le16(b, 6), le16(b, 8));
    }

    /**
     * BITMAPINFOHEADER and later. Height is signed: negative means the rows run top down.
     * <p>
     * "BM" is only two bytes, far too weak on its own to say a file is a BMP - some other format
     * starting with those letters would otherwise yield a confident, wrong size. The declared file
     * size and the offset to the pixels are checked against the array as well, which together are
     * specific enough to trust.
     */
    private static Size bmp(final byte[] b) {

        if (b.length < 26 || b[0] != 'B' || b[1] != 'M') return null;

        final int declared = le32(b, 2);
        if (declared != 0 && declared != b.length) return null; // 0 is tolerated: some writers leave it

        final int dib = le32(b, 14);
        if (dib < 40) return null; // BITMAPCOREHEADER holds 16 bit dimensions; not worth it

        final int pixels = le32(b, 10);
        if (pixels < 14 + dib || b.length < pixels) return null;

        return dim(le32(b, 18), Math.abs(le32(b, 22)));
    }

    private static Size dim(final int w, final int h) {
        return (0 < w && 0 < h) ? new Size(w, h) : null;
    }

    private static int be32(final byte[] b, final int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }

    private static int be16(final byte[] b, final int at) {
        return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF;
    }

    private static int le32(final byte[] b, final int at) {
        return (b[at + 3] & 0xFF) << 24 | (b[at + 2] & 0xFF) << 16 | (b[at + 1] & 0xFF) << 8 | b[at] & 0xFF;
    }

    private static int le16(final byte[] b, final int at) {
        return (b[at + 1] & 0xFF) << 8 | b[at] & 0xFF;
    }
}
