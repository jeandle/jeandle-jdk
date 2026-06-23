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
 * @summary Test scope value encoding when compiling Formatter$FormatSpecifier::print.
 * @library /test/lib
 * @run driver compiler.jeandle.deoptimize.TestFormatterScopeValue
 */

package compiler.jeandle.deoptimize;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestFormatterScopeValue {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runTest();
            return;
        }

        test();
    }

    private static void runTest() throws Exception {
        ArrayList<String> commandArgs = new ArrayList<>(List.of(
            "-Xcomp",
            "-Xbatch",
            "-XX:-TieredCompilation",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseJeandleCompiler",
            "-XX:CompileCommand=compileonly,java.util.Formatter$FormatSpecifier::print",
            TestFormatterScopeValue.class.getName(),
            "test"
        ));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0);
    }

    private static void test() {
        Calendar cal = new GregorianCalendar(2026, Calendar.MAY, 31, 12, 34, 56);
        LocalDateTime time = LocalDateTime.of(2026, 5, 31, 12, 34, 56);
        for (int i = 0; i < 500; i++) {
            String s = String.format(Locale.US,
                "%04d %s %.2f %,(d %020d %,.4f %tF %tT %tZ %tA %tB %tY %tH:%tM:%tS %tQ %tN %s %s%n",
                i, "jeandle", i / 3.0, -i, BigInteger.valueOf(i).pow(5), new BigDecimal("123456789.1234"),
                cal, cal, cal, cal, cal, cal, cal, cal, cal, cal, time, time, time);
            if (s.isEmpty()) {
                throw new AssertionError("empty formatter result");
            }
        }
    }
}
