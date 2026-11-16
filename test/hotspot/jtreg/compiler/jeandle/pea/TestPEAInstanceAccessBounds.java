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

/*
 * @test
 * @summary Speculative instance accesses must not replay beyond the receiver layout
 * @requires vm.gc.G1
 * @library /test/lib /
 * @modules jdk.compiler
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+IgnoreUnrecognizedVMOptions -XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler
 *      -XX:+UseJeandleCompiler -XX:+DoEscapeAnalysis -XX:+JeandleDoPEA
 *      -XX:+UseG1GC -XX:-UseCompressedOops
 *      -Xms512m -Xmx4g compiler.jeandle.pea.TestPEAInstanceAccessBounds
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import jdk.test.whitebox.WhiteBox;

public class TestPEAInstanceAccessBounds {
    public static void main(String[] args) throws Exception {
        WhiteBox wb = WhiteBox.getWhiteBox();
        if (!Boolean.TRUE.equals(wb.getBooleanVMFlag("UseJeandleCompiler"))
                || !Boolean.TRUE.equals(wb.getBooleanVMFlag("JeandleDoPEA"))
                || Boolean.TRUE.equals(wb.getBooleanVMFlag("UseJVMCICompiler"))) {
            throw new AssertionError("Jeandle PEA must be enabled");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path source = Path.of(System.getProperty("test.src"));
        Path root = Path.of(System.getProperty("test.root"));
        Path output = Files.createTempDirectory(Path.of("."), "instance-bounds-javac");
        List<String> files = List.of(source.resolve("PEATestUtils.java").toString(),
                source.resolve("TestPEATypeChecks.java").toString());
        List<String> options = List.of("-g", "-d", output.toString(),
                "-sourcepath", root.resolve("../../lib").normalize() + ":" + root,
                "-classpath", output.toString());
        Method target = null;
        for (Method method : Class.forName("com.sun.tools.javac.comp.Resolve$4").getDeclaredMethods()) {
            if (method.getName().equals("argumentsAcceptable")) {
                target = method;
            }
        }
        if (target == null) {
            throw new AssertionError("javac resolution target not found");
        }
        wb.testSetDontInlineMethod(target, true);

        // Javac profiles both ArraySpliterator and IteratorSpliterator. Inlining
        // their alternatives must not add the larger layout's iterator field
        // to the smaller allocation's replay state, even on an infeasible path.
        // With wide oops that write would overwrite the following object's mark
        // word; G1's SATB pre-barrier would then enqueue that word as an oop.
        int compiledMarkingIterations = 0;
        boolean marking = false;
        wb.concurrentGCAcquireControl();
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                // Keep the warmup profiles, then explicitly compile the method
                // whose normal entry is checked around each javac invocation.
                if (iteration >= 15) {
                    PEATestUtils.enqueueAndAwaitLevel4(target);
                }
                boolean compiled = wb.getMethodCompilationLevel(target) == 4;
                if (!marking && iteration >= 15 && compiled) {
                    wb.concurrentGCRunTo(wb.AFTER_MARKING_STARTED);
                    marking = true;
                    System.out.println("INSTANCE-BOUNDS:SATB-ACTIVE:level=4");
                }
                try (StandardJavaFileManager manager =
                        compiler.getStandardFileManager(null, null, null)) {
                    if (!compiler.getTask(null, manager, null, options, null,
                            manager.getJavaFileObjectsFromStrings(files)).call()) {
                        throw new AssertionError("javac failed at iteration " + iteration);
                    }
                }
                if (marking) {
                    if (!wb.g1InConcurrentMark()) {
                        throw new AssertionError("SATB marking ended before verification");
                    }
                    if (compiled && wb.getMethodCompilationLevel(target) == 4) {
                        compiledMarkingIterations++;
                    }
                }
            }
            // Drain and validate the SATB buffers populated by compiled javac.
            wb.concurrentGCRunToIdle();
        } finally {
            wb.concurrentGCReleaseControl();
        }
        if (compiledMarkingIterations < 25) {
            throw new AssertionError("insufficient compiled SATB coverage: "
                    + compiledMarkingIterations);
        }
        System.out.println("INSTANCE-BOUNDS-PASSED:compiled-marking-iterations="
                + compiledMarkingIterations);
    }
}
