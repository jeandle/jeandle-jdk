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

public class JeandleLateCharacterCaller {
    public static int latin1(int ch) {
        CharacterData data = CharacterData.of(ch & 255);
        return (data.isDigit(ch) ? 1 : 0)
                | (data.isLowerCase(ch) ? 2 : 0)
                | (data.isUpperCase(ch) ? 4 : 0)
                | (data.isWhitespace(ch) ? 8 : 0);
    }

    public static int nullable(boolean nullReceiver, int ch) {
        CharacterData data = nullReceiver ? null : CharacterData.of(0);
        return (data.isDigit(ch) ? 1 : 0)
                | (data.isLowerCase(ch) ? 2 : 0)
                | (data.isUpperCase(ch) ? 4 : 0)
                | (data.isWhitespace(ch) ? 8 : 0);
    }
}
