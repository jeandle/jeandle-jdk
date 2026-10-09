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
 * @summary Test the intrinsic implementation of Object.getClass()
 * @library /test/lib /
 * @build jdk.test.lib.Asserts compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm compiler.jeandle.intrinsic.TestGetClass
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestGetClass {
    public static void main(String[] args) throws Exception {
        String dumpPath = Files.createTempDirectory("jeandle_test_getclass").toString();

        ArrayList<String> commandArgs = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::getClass_of_object",
                "-XX:CompileCommand=compileonly," + TestWrapper.class.getName() + "::getClass_of_exact",
                TestWrapper.class.getName()));

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(commandArgs);
        OutputAnalyzer output = ProcessTools.executeCommand(pb);

        output.shouldHaveExitValue(0)
              .shouldContain("is parsed as intrinsic");

        // Verify the jeandle.get_class JavaOp is present in the IR
        FileCheck checker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getClass_of_object", Object.class), false);
        checker.checkPattern("call hotspotcc .*@jeandle\\.get_class");

        // The object is allocated outside this compiled method, so PEA has no
        // virtual allocation to eliminate. CFF can still use its exact Klass
        // and fold getClass to the mirror handle.
        FileCheck exactInitial = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getClass_of_exact"), false);
        exactInitial.checkPattern("jeandle\\.get_class");

        FileCheck exactChecker = new FileCheck(dumpPath,
                TestWrapper.class.getMethod("getClass_of_exact"), true);
        exactChecker.checkNotPattern("call hotspotcc .*@jeandle\\.get_class");
        exactChecker.checkPattern("%folded\\.oop = load ptr addrspace\\(1\\), ptr .*oop_handle_");
    }

    static class TestWrapper {
        // This object is allocated during class initialization, outside the
        // method compiled by Jeandle. It is therefore not a PEA candidate.
        static final ExactObject exactObject = new ExactObject();

        public static void main(String[] args) {
            // Different object types
            Asserts.assertEquals(getClass_of_object(new Object()), Object.class,
                    "Object.getClass() should return Object.class");
            Asserts.assertEquals(getClass_of_object("hello"), String.class,
                    "String.getClass() should return String.class");
            Asserts.assertEquals(getClass_of_object(Integer.valueOf(42)), Integer.class,
                    "Integer.getClass() should return Integer.class");

            // Array types
            Asserts.assertEquals(getClass_of_object(new int[0]), int[].class,
                    "int[].getClass() should return int[].class");
            Asserts.assertEquals(getClass_of_object(new Object[0]), Object[].class,
                    "Object[].getClass() should return Object[].class");
            Asserts.assertEquals(getClass_of_object(new String[0]), String[].class,
                    "String[].getClass() should return String[].class");

            // Subclass — getClass() returns the actual runtime class, not the declared type
            Asserts.assertEquals(getClass_of_object(new HashMap<String, String>()), HashMap.class,
                    "HashMap.getClass() should return HashMap.class, not Map.class");
            Asserts.assertEquals(getClass_of_object(new ArrayList<String>()), ArrayList.class,
                    "ArrayList.getClass() should return ArrayList.class, not List.class");

            // NullPointerException on null receiver
            boolean gotNPE = false;
            try {
                getClass_of_object(null);
            } catch (NullPointerException e) {
                gotNPE = true;
            }
            Asserts.assertTrue(gotNPE, "getClass(null) should throw NullPointerException");

            Asserts.assertEquals(getClass_of_exact(), ExactObject.class,
                    "CFF should fold getClass for an exact external object");

            System.out.println("TestGetClass PASSED");
        }

        public static Class<?> getClass_of_object(Object obj) {
            return obj.getClass();
        }

        public static Class<?> getClass_of_exact() {
            return exactObject.getClass();
        }

        static final class ExactObject { }
    }
}
