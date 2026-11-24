/*
 * Copyright (c) 2026, The Jeandle-JDK Authors. All Rights Reserved.
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

/**
 * @test
 * @summary The string-indexOf kernel must not reach its linear-scan fallback
 *          through a routine-call relocation: routine call sites are a single
 *          rel32 call with no reserved patch space, and patching them with a
 *          far (movabs + call register) sequence when the routine is out of
 *          rel32 reach - forced here by ForceUnreachable - overwrites the
 *          instructions that follow the site and crashes the compiled method
 *          when it executes. The fallback is called through a baked absolute
 *          address instead (see template.ll and
 *          jeandleRuntimeDefinedJavaOps.cpp). The test compiles
 *          String.indexOf and executes vector hits, an early hit over the
 *          previously corrupted bytes, and the fallback for misses and late
 *          matches with Latin1 and UTF16 strings.
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+UnlockDiagnosticVMOptions -XX:+ForceUnreachable -XX:+CompactStrings
 *      -XX:CompileCommand=compileonly,java.lang.String::indexOf
 *      compiler.jeandle.TestRoutineCallForceUnreachable
 */

package compiler.jeandle;

import java.util.Arrays;

public class TestRoutineCallForceUnreachable {
    // A match found by the vector-filtered path.
    private static final String WITH_DOUBLE_SLASH =
            "/usr/local//share//jeandle//lib//llvm//bin//opt";

    // Isolated slashes are rejected by the two-character filter.
    private static final String SINGLE_SLASHES_ONLY =
            "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z"
            + "/a/b/c/d/e/f/g/h/i/j/k/l/m/n/o/p/q/r/s/t/u/v/w/x/y/z";

    // Reproduces the crash originally observed in path normalization: this
    // exact shape executes the corrupted bytes that follow the patched
    // routine call site on unfixed builds.
    private static final String MANY_DOUBLE_SLASHES =
            "/some/deep//double//slash//path//with//many//separators//here";

    public static void main(String[] args) {
        int sum = 0;
        for (int i = 0; i < 100_000; i++) {
            sum += WITH_DOUBLE_SLASH.indexOf("//");
            sum += SINGLE_SLASHES_ONLY.indexOf("//");
            sum += MANY_DOUBLE_SLASHES.indexOf("//");
        }

        // "/usr/local" is 10 chars, so the first "//" starts at index 10;
        // the slash-only string has no match; "/some/deep" is 10 chars too.
        int expected = 10 + (-1) + 10;
        if (sum != expected * 100_000) {
            throw new RuntimeException("unexpected indexOf results: " + sum);
        }
        // Resolve the UTF16 search path before checking the long inputs.
        if ("\u1261\u1261".indexOf("\u1261", 0) != 0) {
            throw new AssertionError("unexpected UTF16 warmup result");
        }
        for (String encoding : new String[] {"LL", "UU", "UL"}) {
            testLinearFallback(encoding);
        }
        System.out.println("Passed");
    }

    private static void testLinearFallback(String encoding) {
        char base = encoding.equals("UU") ? '\u1261' : 'a';
        char[] source = new char[16384];
        char[] needle = new char[64];
        Arrays.fill(source, base);
        Arrays.fill(needle, base);
        // Every 64-character candidate contains a mismatch. Its position
        // varies between candidates, so learning a filter cannot avoid
        // exhausting the full-confirmation budget.
        for (int i = 31; i < source.length; i += 32) {
            source[i] = (char) (base + 1);
        }
        if (encoding.equals("UL")) {
            source[0] = '\u1234';
        }
        String target = new String(needle);
        for (int expected : new int[] {-1, source.length - needle.length}) {
            if (expected >= 0) {
                System.arraycopy(needle, 0, source, expected, needle.length);
            }
            String input = new String(source);
            for (int from : new int[] {0, 1, source.length / 2}) {
                int actual = input.indexOf(target, from);
                if (actual != expected) {
                    throw new AssertionError("unexpected indexOf result for " + encoding
                            + ", from=" + from + ": " + actual + " != " + expected);
                }
            }
        }
    }
}
