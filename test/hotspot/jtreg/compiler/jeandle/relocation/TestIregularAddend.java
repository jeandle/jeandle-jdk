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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @requires os.arch=="amd64" | os.arch=="x86_64"
 * @summary Correcting the gap in relocation target with special addend.
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/326
 *
 * @library /test/lib
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler compiler.jeandle.relocation.TestIregularAddend
 */

/*
 * The method log1p in this case, after being compiled by jeandle on X86_64
 * architecture, will generate a cmpeqsd instruction, which produce a irregular
 * addend in relocation info, and it will trigger a bug in jeandle reloc.
 */

package compiler.jeandle.relocation;

import jdk.test.lib.Asserts;

public class TestIregularAddend {
    // Java.lang.Math function Error tolerance is 1~2 ulp(according to JDK doc).
    // Test tolerance: 2 ulp (safe upper bound for standard libm precision validation).
    private static final int ULP_TOLERANCE = 2;

    public static void main(String[] args) {
        double sum = 0.0;
        for (int i = 0; i < 10000; i++) {
            sum += Math.log1p((double)i);
        }

        assertWithinUlp(sum, 82108.92783681415, ULP_TOLERANCE);
    }

    private static void assertWithinUlp(double computed, double reference, double maxUlp) {
        double error = Math.abs(computed - reference);
        double tolerance = maxUlp * StrictMath.ulp(reference);
        Asserts.assertLTE(error, tolerance);
    }
}
