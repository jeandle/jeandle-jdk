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
 */

/*
 * @test
 * @summary Test non-PEA array store check elimination for exact array types
 * @library /test/lib /
 * @run driver TestArrayStoreCheckElimination
 */

import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestArrayStoreCheckElimination {
    private static final String ARRAY_STORE_CHECK = "jeandle.array_store_check";
    private static final String LLVM_OPTIONS =
            "-XX:JeandleLLVMOptions=--print-before=type-check-elimination "
            + "--print-after=type-check-elimination";

    static Object[] exactObjectArrayStore(Object value) {
        Object[] array = new Object[1];
        array[0] = value;
        return array;
    }

    static String[] exactStringArrayStore(String value) {
        String[] array = new String[1];
        array[0] = value;
        return array;
    }

    static void nonExactArrayStore(Object[] array, Object value) {
        array[0] = value;
    }

    static Object[] exactStringArrayUnknownStore(Object value) {
        Object[] array = new String[1];
        array[0] = value;
        return array;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runDriver();
        } else {
            runChild(args[0]);
        }
    }

    private static void runDriver() throws Exception {
        assertEliminated("exactObjectArrayStore");
        assertEliminated("exactStringArrayStore");
        assertPreserved("nonExactArrayStore");
        assertPreserved("exactStringArrayUnknownStore");
    }

    private static void assertEliminated(String method) throws Exception {
        OutputAnalyzer output = runTestProcess(method);
        output.shouldHaveExitValue(0);
        String before = extractIR(output.getOutput(), method,
                "IR Dump Before TypeCheckElimination");
        String after = extractIR(output.getOutput(), method,
                "IR Dump After TypeCheckElimination");
        Asserts.assertGTE(countOccurrences(before, ARRAY_STORE_CHECK), 1,
                method + ": frontend IR must contain an array store check");
        Asserts.assertEquals(countOccurrences(after, ARRAY_STORE_CHECK), 0,
                method + ": compatible exact-array store check was not eliminated");
    }

    private static void assertPreserved(String method) throws Exception {
        OutputAnalyzer output = runTestProcess(method);
        output.shouldHaveExitValue(0);
        String before = extractIR(output.getOutput(), method,
                "IR Dump Before TypeCheckElimination");
        String after = extractIR(output.getOutput(), method,
                "IR Dump After TypeCheckElimination");
        Asserts.assertGTE(countOccurrences(before, ARRAY_STORE_CHECK), 1,
                method + ": frontend IR must contain an array store check");
        Asserts.assertGTE(countOccurrences(after, ARRAY_STORE_CHECK), 1,
                method + ": potentially failing array store check must be preserved");
    }

    private static OutputAnalyzer runTestProcess(String method) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("-Xcomp");
        command.add("-Xbatch");
        command.add("-XX:-TieredCompilation");
        command.add("-XX:+UseJeandleCompiler");
        command.add("-XX:-JeandleDoPEA");
        command.add(LLVM_OPTIONS);
        command.add("-XX:CompileCommand=compileonly,TestArrayStoreCheckElimination::"
                + method);
        command.add("TestArrayStoreCheckElimination");
        command.add(method);
        return ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
    }

    private static String extractIR(String output, String method, String marker) {
        StringBuilder result = new StringBuilder();
        boolean inSection = false;
        for (String line : output.split("\\n")) {
            if (line.contains(marker) && line.contains(method)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) {
                break;
            }
            if (inSection) {
                result.append(line).append('\n');
            }
        }
        Asserts.assertFalse(result.isEmpty(),
                method + ": missing " + marker + " output");
        return result.toString();
    }

    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) >= 0) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    private static void runChild(String method) {
        switch (method) {
            case "exactObjectArrayStore": {
                Object marker = new Object();
                Asserts.assertSame(exactObjectArrayStore(marker)[0], marker);
                Asserts.assertNull(exactObjectArrayStore(null)[0]);
                break;
            }
            case "exactStringArrayStore": {
                Asserts.assertEquals(exactStringArrayStore("ok")[0], "ok");
                Asserts.assertNull(exactStringArrayStore(null)[0]);
                break;
            }
            case "nonExactArrayStore": {
                Object marker = new Object();
                Object[] objects = new Object[1];
                nonExactArrayStore(objects, marker);
                Asserts.assertSame(objects[0], marker);

                Object[] strings = new String[1];
                nonExactArrayStore(strings, null);
                expectArrayStoreException(() -> nonExactArrayStore(strings, marker));
                break;
            }
            case "exactStringArrayUnknownStore": {
                Asserts.assertEquals(exactStringArrayUnknownStore("ok")[0], "ok");
                Asserts.assertNull(exactStringArrayUnknownStore(null)[0]);
                expectArrayStoreException(
                        () -> exactStringArrayUnknownStore(new Object()));
                break;
            }
            default:
                throw new IllegalArgumentException("Unknown test method: " + method);
        }
    }

    private static void expectArrayStoreException(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected ArrayStoreException");
        } catch (ArrayStoreException expected) {
            // Expected.
        }
    }
}
