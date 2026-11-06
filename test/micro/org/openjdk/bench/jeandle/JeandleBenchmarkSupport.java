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
package org.openjdk.bench.jeandle;

final class JeandleBenchmarkSupport {
    private JeandleBenchmarkSupport() {}

    static void requireJavaBlackhole() {
        // Jeandle needs the Java consumer to keep benchmark results alive.
        // Use the same consumer for C2 so the measurements remain comparable.
        if (Boolean.getBoolean("compilerBlackholesEnabled")) {
            throw new IllegalStateException("Run the JMH harness with "
                    + "-Djmh.blackhole.mode=FULL_DONTINLINE before -jar; "
                    + "Jeandle benchmarks require Java blackholes for both Jeandle and C2");
        }
    }

    // Setup-time sanity check of the JDK methods under measurement.
    static void check(boolean condition, String what) {
        if (!condition) {
            throw new IllegalStateException("Unexpected " + what + " result");
        }
    }
}
