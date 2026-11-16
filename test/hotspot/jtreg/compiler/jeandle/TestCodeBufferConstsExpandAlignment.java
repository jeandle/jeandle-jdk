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

/**
 * @test
 * @summary A planned consts section keeps its alignment when the CodeBuffer
 *          expands.
 *
 *          CodeBuffer::expand() builds its replacement buffer through the
 *          CodeBuffer constructor, which takes the consts alignment from the
 *          current JeandleCompilation. The planned consts path copies the
 *          const sections itself, so it has to publish the plan's alignment
 *          there explicitly; if it does not, the first expansion re-divides
 *          the blob with sizeof(jdouble)-aligned consts while the buffer still
 *          requires the planned alignment, and the VM stops at the guarantee
 *          in CodeBuffer::verify_section_allocation().
 *
 *          JeandleStressCodeBufferExpand starts the stubs section empty, so
 *          every compiled method that emits a stub expands its buffer. The
 *          workload is java.util.Formatter::format, which has many static
 *          call sites and, on x86_64, planned const sections aligned above
 *          sizeof(jdouble). The driver requires both conditions to be met on
 *          at least one method, so the test cannot pass without exercising
 *          the path it guards.
 *
 * @requires vm.debug == true
 * @library /test/lib
 * @run driver compiler.jeandle.TestCodeBufferConstsExpandAlignment
 */

package compiler.jeandle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCodeBufferConstsExpandAlignment {

    private static final Pattern CONST_TOTAL =
            Pattern.compile("\\[JeandleConstTotal\\] Method: (.*?), planned=(-?\\d+), allocated=(-?\\d+), actual=(-?\\d+),.*?fallback=(\\d+), align=(\\d+), expands=(\\d+)");

    private static final String WORKLOAD = "workload";

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && WORKLOAD.equals(args[0])) {
            runWorkload();
            return;
        }

        ProcessBuilder pb = ProcessTools.createTestJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+UseJeandleCompiler",
                "-XX:+JeandleCodeBufferInstrument",
                "-XX:+JeandleStressCodeBufferExpand",
                "-Xbatch",
                "-XX:CompileCommand=compileonly,java.util.Formatter::format",
                "-cp", System.getProperty("java.class.path"),
                TestCodeBufferConstsExpandAlignment.class.getName(), WORKLOAD);

        OutputAnalyzer out = new OutputAnalyzer(pb.start());
        // Without the fix the child VM dies at the guarantee in
        // CodeBuffer::verify_section_allocation() with exit code 1.
        out.shouldHaveExitValue(0);
        out.shouldNotContain("start is aligned");
        out.shouldContain("workload done");
        String stdout = out.getStdout();

        int methods = 0;
        int expanded = 0;
        int expandedAboveDouble = 0;
        long maxAlign = 0;
        Matcher m = CONST_TOTAL.matcher(stdout);
        while (m.find()) {
            methods++;
            long planned  = Long.parseLong(m.group(2));
            long allocated = Long.parseLong(m.group(3));
            long actual   = Long.parseLong(m.group(4));
            long fallback = Long.parseLong(m.group(5));
            long align    = Long.parseLong(m.group(6));
            long expands  = Long.parseLong(m.group(7));

            Asserts.assertLTE(actual, allocated,
                    "a method must not write past the consts capacity (" + m.group(0) + ")");
            if (fallback == 0) {
                Asserts.assertEQ(actual, planned,
                        "exact allocation must emit exactly the planned consts bytes ("
                                + m.group(0) + ")");
            }
            if (expands > 0) {
                expanded++;
                if (fallback == 0 && planned > 0 && align > 8) {
                    expandedAboveDouble++;
                }
            }
            maxAlign = Math.max(maxAlign, align);
        }

        System.out.println("note: planned methods=" + methods + ", expanded=" + expanded
                + ", expanded with consts alignment > 8: " + expandedAboveDouble
                + ", max consts alignment=" + maxAlign);

        // Guard against a vacuous pass at every level the fix depends on.
        Asserts.assertGT(methods, 0, "no method took the planned consts path");
        Asserts.assertGT(expanded, 0,
                "JeandleStressCodeBufferExpand did not cause any CodeBuffer expansion;"
                        + " the expansion path was not exercised");
        if (Platform.isX64()) {
            Asserts.assertGT(expandedAboveDouble, 0,
                    "no expanded method had a planned consts section aligned above"
                            + " sizeof(jdouble) (max alignment seen: " + maxAlign + ");"
                            + " the alignment hand-off to CodeBuffer::expand() was not exercised");
        }
    }

    private static void runWorkload() {
        // The first call runs in the interpreter; its constant tail is the
        // reference every later, compiled call is checked against. The
        // varying head is checked against plain string concatenation, which
        // does not go through Formatter.
        String first = format(0);
        String tail = first.substring(head(0).length());
        for (int i = 0; i < 30_000; i++) {
            String expected = head(i) + tail;
            String actual = format(i);
            if (!expected.equals(actual)) {
                throw new RuntimeException("mismatch at " + i + ": expected '" + expected
                        + "', got '" + actual + "'");
            }
        }
        System.out.println("workload done");
    }

    private static String head(int i) {
        return "#" + i + "#|" + Integer.toHexString(i & 0xff) + "|";
    }

    private static String format(int i) {
        return String.format("#%d#|%x|%08.3f %-6s %x %e %,d %b",
                i, i & 0xff, 3.25, "ab", 0xCAFEL, 1.0e-3, 1234567, true);
    }
}
