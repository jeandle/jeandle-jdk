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
 *          previously corrupted bytes, and the fallback for a miss.
 * @requires os.arch == "amd64" | os.arch == "x86_64"
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+UnlockDiagnosticVMOptions -XX:+ForceUnreachable
 *      -XX:CompileCommand=compileonly,java.lang.String::indexOf
 *      compiler.jeandle.TestRoutineCallForceUnreachable
 */

package compiler.jeandle;

public class TestRoutineCallForceUnreachable {
    // A match found by the vector path after several confirmed candidates.
    private static final String WITH_DOUBLE_SLASH =
            "/usr/local//share//jeandle//lib//llvm//bin//opt";

    // Single slashes act as candidate positions that all fail confirmation,
    // so the kernel exhausts its confirmation budget and takes the linear
    // fallback routine before concluding that no double slash exists.
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
        System.out.println("Passed");
    }
}
