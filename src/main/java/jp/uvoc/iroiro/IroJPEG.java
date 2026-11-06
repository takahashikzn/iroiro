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

import java.nio.charset.StandardCharsets;


/**
 * Operations specific to JPEG.
 *
 * @author takahashikzn
 */
public final class IroJPEG {

    private IroJPEG() { }

    // marker codes, the byte after 0xFF, named as in ITU T.81
    private static final int SOI = 0xD8, EOI = 0xD9, SOS = 0xDA, TEM = 0x01, RST0 = 0xD0, RST7 = 0xD7, APP0 = 0xE0, APP14 = 0xEE;

    // SOF0..SOF15 share their range with three markers that are not frame headers
    private static final int SOF0 = 0xC0, SOF15 = 0xCF, DHT = 0xC4, JPG = 0xC8, DAC = 0xCC;

    private static final byte[] JFIF = "JFIF\0".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ADOBE = "Adobe".getBytes(StandardCharsets.US_ASCII);

    /** Offset of the transform flag in the APP14 payload, after "Adobe", version, flags0 and flags1. */
    private static final int ADOBE_TRANSFORM = 11;

    private static final byte TRANSFORM_NONE = 0;

    private static final byte TRANSFORM_YCC = 1;

    /** Offsets in the frame header payload: the component count, then 3 bytes per component, id first. */
    private static final int SOF_NF = 5, SOF_IDS = 6, SOF_COMP = 3;

    /**
     * Settles a three component JPEG that says two different things about how its components are
     * encoded.
     * <p>
     * The JFIF marker (APP0) means YCbCr. The Adobe marker (APP14) carries a transform flag of its
     * own, where 0 means the components are stored untransformed, i.e. RGB. Some writers emit both
     * with the flag at 0 and encode YCbCr all the same. Readers then split on which marker to believe:
     * <ul>
     * <li>libjpeg lets JFIF win, so image viewers and browsers show the picture as intended;</li>
     * <li>the PDF specification lets the Adobe flag win for DCTDecode, so the same bytes passed
     * through into a PDF are read as RGB - white comes out as (255, 128, 128), pink;</li>
     * <li>ImageIO refuses the file with "Unsupported Image Type".</li>
     * </ul>
     * Setting the flag to 1 makes all of them read what libjpeg reads. Only the markers are read and
     * nothing is decoded, so the compressed pixels pass through untouched.
     * <p>
     * The input is a file someone else supplied: malformed bytes are returned as they are, never
     * answered with an exception.
     *
     * @return {@code b} itself unless the markers contradict each other as above; then a copy with
     * the Adobe transform flag set to YCbCr
     */
    public static byte[] reconcileColorTransform(final byte[] b) {

        if (b == null || b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != SOI) return b;

        var jfif = false;
        int flag = -1; // where the Adobe transform flag sits; the last APP14 wins, as in libjpeg
        int comps = 0;
        var rgbIds = false;

        int at = 2;
        while (at + 4 <= b.length) {

            if ((b[at] & 0xFF) != 0xFF) return b;
            int m = b[at + 1] & 0xFF;

            while (m == 0xFF && at + 2 < b.length) m = b[++at + 1] & 0xFF; // fill bytes

            if (m == SOI || m == TEM || (RST0 <= m && m <= RST7)) { // standalone, no length
                at += 2;
                continue;
            }
            if (m == EOI || m == SOS) break; // the markers that matter all precede the scan
            if (b.length < at + 4) return b;

            final int len = be16(b, at + 2);
            if (len < 2 || b.length < at + 2 + len) return b;

            final int data = at + 4;
            final int size = len - 2;

            if (m == APP0 && JFIF.length <= size && startsWith(b, data, JFIF)) jfif = true;
            if (m == APP14 && ADOBE_TRANSFORM < size && startsWith(b, data, ADOBE)) flag = data + ADOBE_TRANSFORM;
            if (SOF0 <= m && m <= SOF15 && m != DHT && m != JPG && m != DAC && SOF_IDS <= size) {
                comps = b[data + SOF_NF] & 0xFF;
                // ids 'R', 'G', 'B' are how an RGB JPEG names itself; JFIF is the liar then
                rgbIds = comps == 3 && SOF_IDS + 3 * SOF_COMP <= size && id(b, data, 0) == 'R' && id(b, data, 1) == 'G' && id(b, data, 2) == 'B';
            }

            at += 2 + len;
        }

        if (!jfif || flag < 0 || b[flag] != TRANSFORM_NONE || comps != 3 || rgbIds) return b;

        final var ret = b.clone();
        ret[flag] = TRANSFORM_YCC;

        return ret;
    }

    private static int id(final byte[] b, final int sof, final int comp) { return b[sof + SOF_IDS + comp * SOF_COMP]; }

    private static boolean startsWith(final byte[] b, final int at, final byte[] sig) {

        for (int i = 0; i < sig.length; i++) if (b[at + i] != sig[i]) return false;

        return true;
    }

    private static int be16(final byte[] b, final int at) { return (b[at] & 0xFF) << 8 | b[at + 1] & 0xFF; }
}
