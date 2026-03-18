/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

/*
 * @test
 * @summary Test that Long.valueOf return correct results.
 *          This is used to test the correctness of the initialization of cached Long objects in LongCache.
 *          The loop in this section will be deoptimized and fall back to interpreted execution
 *          because Long has not been resolved.
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -Xbatch -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler TestLongValueOf
 */
import jdk.test.lib.Asserts;

public class TestLongValueOf {

    public static void main(String[] args) throws Exception {
        String[] testCases = {
            "0",
            "1",
            "-1",
            "100",
            "-100",
            "127",
            "-128",
            String.valueOf(Integer.MAX_VALUE),
            String.valueOf(Integer.MIN_VALUE)
        };
        long[] results = {
            0,
            1,
            -1,
            100,
            -100,
            127,
            -128,
            (long)(int)Integer.MAX_VALUE,
            (long)(int)Integer.MIN_VALUE
        };

        for (int i = 0; i < testCases.length; i++) {
            long longVal = Long.valueOf(testCases[i]);

            Asserts.assertTrue(longVal == results[i],
                    "Mismatch for input \"" + testCases[i] + "\": " +
                    "Long.valueOf = " + longVal + ", " +
                    "Expected: " + testCases[i]
            );
        }
    }
}
