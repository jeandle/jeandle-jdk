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
 *
 */

package java.lang;

public final class JeandleStringUTF16CharCaller {
    private JeandleStringUTF16CharCaller() {}

    public static char get(byte[] value, int index) {
        return StringUTF16.getChar(value, index);
    }

    public static void put(byte[] value, int index, int c) {
        StringUTF16.putChar(value, index, c);
    }

    public static char defaultFirstByte(int high) {
        byte[] value = new byte[2];
        value[1] = (byte) high;
        return StringUTF16.getChar(value, 0);
    }

    public static char packedChar(int value) {
        byte[] bytes = new byte[4];
        StringUTF16.putChar(bytes, 1, value);
        return StringUTF16.getChar(bytes, 1);
    }

    public static char compressPackedPrefix() {
        byte[] bytes = new byte[2];
        StringUTF16.putChar(bytes, 0, 0x1122);
        char[] source = {'A', '\u0100'};
        int count = StringUTF16.compress(source, 0, bytes, 0, 2);
        return (char) (StringUTF16.getChar(bytes, 0) + count);
    }

}
