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

import java.nio.ByteOrder;

public class JeandlePEACopyPackedSource {
    public static int testInteriorByte() {
        byte[] src = new byte[2];
        byte[] dst = new byte[1];
        StringUTF16.putChar(src, 0, 0x2211);
        System.arraycopy(src, 1, dst, 0, 1);
        return dst[0] & 0xff;
    }

    public static int testLeadingByte() {
        byte[] src = new byte[2];
        byte[] dst = new byte[1];
        StringUTF16.putChar(src, 0, 0x2211);
        System.arraycopy(src, 0, dst, 0, 1);
        return dst[0] & 0xff;
    }

    public static int testWholeValue() {
        byte[] src = new byte[2];
        byte[] dst = new byte[2];
        StringUTF16.putChar(src, 0, 0x2211);
        System.arraycopy(src, 0, dst, 0, 2);
        return ((dst[0] & 0xff) << 8) | (dst[1] & 0xff);
    }

    public static int testDisjointWrite() {
        byte[] src = new byte[4];
        byte[] dst = new byte[1];
        StringUTF16.putChar(src, 0, 0x2211);
        System.arraycopy(src, 3, dst, 0, 1);
        return dst[0] & 0xff;
    }

    public static void main(String[] args) throws Exception {
        // Initialize the trusted intrinsic holder before compiling the targets.
        Class.forName("java.lang.StringUTF16", true, null);
        boolean bigEndian = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;
        check(testInteriorByte(), bigEndian ? 0x11 : 0x22);
        check(testLeadingByte(), bigEndian ? 0x22 : 0x11);
        check(testWholeValue(), bigEndian ? 0x2211 : 0x1122);
        check(testDisjointWrite(), 0);
        System.out.println("PACKED-COPY-PASSED");
    }

    private static void check(int actual, int expected) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }
}
