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
 * @library /test/lib
 * @run driver TestUnloadedCatchKlass
 */

import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;
import java.nio.file.Files;
import java.nio.file.Path;

public class TestUnloadedCatchKlass {

    private static final String classDir = System.getProperty("test.classes");

    static void mayThrow(boolean throwA) throws UnloadedExceptionA, UnloadedExceptionB {
        if (throwA) {
            throw new UnloadedExceptionA();
        }
        throw new UnloadedExceptionB();
    }

    static String test(boolean throwA) {
        try {
            try {
                mayThrow(throwA);
                return "ok";
            } catch (UnloadedExceptionA e) {
                return "caught A";
            } catch (UnloadedExceptionB e) {
                return "caught B";
            } catch (Exception e) {
                return "caught Exception: " + e.getClass().getName();
            }
        } catch (NoClassDefFoundError e) {
            return "caught NoClassDefFoundError";
        }
    }

    static void verify(String testName, String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new RuntimeException(testName + " failed: expected '" + expected + "' but got '" + actual + "'");
        }
        System.out.println(testName + " passed: " + actual);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("run-normal")) {
            verify("test", test(true), "caught A");
            verify("test", test(false), "caught B");
        } else if (args.length > 0 && args[0].equals("run-deleted")) {
            verify("test", test(true), "caught NoClassDefFoundError");
            verify("test", test(false), "caught NoClassDefFoundError");
        } else {
            ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder("-Xcomp", "-XX:-TieredCompilation",
                "-Xverify:none", TestUnloadedCatchKlass.class.getName(), "run-normal");

            Path exceptionAClass = Path.of(classDir, "UnloadedExceptionA.class");
            Files.delete(exceptionAClass);

            pb = ProcessTools.createLimitedTestJavaProcessBuilder("-Xcomp", "-XX:-TieredCompilation",
                "-Xverify:none", TestUnloadedCatchKlass.class.getName(), "run-deleted");
        }
    }
}

class UnloadedExceptionA extends Exception {}
class UnloadedExceptionB extends Exception {}
