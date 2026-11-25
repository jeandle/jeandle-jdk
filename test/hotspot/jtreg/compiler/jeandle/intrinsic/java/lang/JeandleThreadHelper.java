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

import jdk.internal.vm.annotation.ChangesCurrentThread;

public class JeandleThreadHelper {
    public static Thread carrier() {
        return Thread.currentCarrierThread();
    }

    public static Object[] cache() {
        return Thread.scopedValueCache();
    }

    public static void setCache(Object[] cache) {
        Thread.setScopedValueCache(cache);
    }

    @ChangesCurrentThread
    public static Thread changeAndRestore(Thread replacement) {
        Thread carrier = Thread.currentCarrierThread();
        Thread original = Thread.currentThread();
        try {
            carrier.setCurrentThread(replacement);
            Thread seen = Thread.currentThread();
            if (seen != replacement) throw new AssertionError("current Thread was not changed");
            return seen;
        } finally {
            carrier.setCurrentThread(original);
            if (Thread.currentThread() != original) throw new AssertionError("current Thread was not restored");
        }
    }
}
