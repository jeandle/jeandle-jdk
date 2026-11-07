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
 * @summary Array copies preserve bytes written by the StringUTF16 packed store intrinsic
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandlePEACopyPackedSource.java
 * @run main/othervm -XX:-UseJeandleCompiler compiler.jeandle.pea.TestPEACopyPackedSource
 */

package compiler.jeandle.pea;

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestPEACopyPackedSource {
    public static void main(String[] args) throws Exception {
        run(true, false);
        run(false, false);
        run(false, true);
    }

    private static void run(boolean fold, boolean interpreter) throws Exception {
        ArrayList<String> options = new ArrayList<>(List.of(
                "--patch-module=java.base=" + System.getProperty("test.class.path")));
        if (interpreter) {
            options.add("-Xint");
        } else {
            options.addAll(List.of("-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                    "-XX:+UseJeandleCompiler", "-XX:+JeandleDoPEA",
                    "-XX:CompileCommand=compileonly,java.lang.JeandlePEACopyPackedSource::test*",
                    "-XX:JeandleLLVMOptions=-jeandle-pea-fold-copy=" + (fold ? 1 : 0)
                            + " -jeandle-dump-pea-stats"
                            + " -jeandle-dump-pea-ir=JeandlePEACopyPackedSource"));
        }
        options.add("java.lang.JeandlePEACopyPackedSource");
        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(options));
        output.shouldHaveExitValue(0).shouldContain("PACKED-COPY-PASSED");
        if (!interpreter) {
            output.shouldContain("store atomic i16");
            output.shouldContain("@jeandle.copy");
            for (String method : List.of("testInteriorByte", "testLeadingByte", "testWholeValue")) {
                output.shouldMatch("PEA stats @java_lang_JeandlePEACopyPackedSource_" + method
                        + "\\(\\)I[^\\r\\n]*: NeverEscapes=0 PartiallyEscapes=2"
                        + " AlwaysEscapes=0 CopyFolds=0");
            }
            output.shouldMatch("PEA stats @java_lang_JeandlePEACopyPackedSource_testDisjointWrite"
                    + "\\(\\)I[^\\r\\n]*CopyFolds=" + (fold ? 1 : 0));
        }
    }
}
