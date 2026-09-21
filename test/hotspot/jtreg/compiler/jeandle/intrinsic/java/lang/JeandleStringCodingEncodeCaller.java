/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package java.lang;

public final class JeandleStringCodingEncodeCaller {
    private JeandleStringCodingEncodeCaller() {}

    public static int encodeByte(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
        return StringCoding.implEncodeISOArray(src, srcOff, dst, dstOff, len);
    }

    public static int encodeAscii(char[] src, int srcOff, byte[] dst, int dstOff, int len) {
        return StringCoding.implEncodeAsciiArray(src, srcOff, dst, dstOff, len);
    }

    public static int compressChars(char[] src, int srcOff, byte[] dst, int dstOff, int len) {
        return StringUTF16.compress(src, srcOff, dst, dstOff, len);
    }

    public static int compressBytes(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
        return StringUTF16.compress(src, srcOff, dst, dstOff, len);
    }

    public static void inflateChars(byte[] src, int srcOff, char[] dst, int dstOff, int len) {
        StringLatin1.inflate(src, srcOff, dst, dstOff, len);
    }

    public static void inflateBytes(byte[] src, int srcOff, byte[] dst, int dstOff, int len) {
        StringLatin1.inflate(src, srcOff, dst, dstOff, len);
    }

    // Allocate in the compiled method so PEA can virtualize both arrays.
    // Constant-index initialization stays virtual until the marker. A dynamic
    // initialization loop may materialize earlier, without any replay stores.
    // Nonzero offsets leave sentinel lanes before and after the copied range.
    public static int compressFreshArrays(int seed) {
        char[] src = new char[48];
        byte[] dst = new byte[48];
        src[3] = (char) (seed & 0x7f);
        src[34] = (char) ((seed + 31) & 0x7f);
        dst[0] = 0x5a;
        dst[47] = 0x5a;
        int hash = StringUTF16.compress(src, 3, dst, 5, 32);
        for (int i = 0; i < dst.length; i++) {
            hash = 31 * hash + (dst[i] & 0xff);
        }
        return hash;
    }

    public static int inflateFreshArrays(int seed) {
        byte[] src = new byte[48];
        char[] dst = new char[48];
        src[3] = (byte) seed;
        src[34] = (byte) (seed + 31);
        dst[0] = '\u3456';
        dst[47] = '\u3456';
        StringLatin1.inflate(src, 3, dst, 5, 32);
        int hash = 0;
        for (int i = 0; i < dst.length; i++) {
            hash = 31 * hash + dst[i];
        }
        return hash;
    }
}
