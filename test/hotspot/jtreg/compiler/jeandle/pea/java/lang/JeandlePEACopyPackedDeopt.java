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

public class JeandlePEACopyPackedDeopt {
    public static long testPackedDeopt(Runnable hook, int value) {
        byte[] src = new byte[4];
        byte[] dst = new byte[6];
        StringUTF16.putChar(src, 0, value);
        StringUTF16.putChar(src, 1, value ^ 0xa55a);
        System.arraycopy(src, 0, dst, 1, 4);
        hook.run();
        byte[] last = new byte[4];
        System.arraycopy(dst, 1, last, 0, 4);
        return ((long) StringUTF16.getChar(src, 0) << 48)
                | ((long) StringUTF16.getChar(src, 1) << 32)
                | ((long) StringUTF16.getChar(last, 0) << 16)
                | StringUTF16.getChar(last, 1);
    }
}
