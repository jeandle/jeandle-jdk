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
 * @summary Test StringUTF16 trusted getChar/putChar native-endian access
 * @library /test/lib /
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringUTF16CharCaller.java
 * @run main/othervm compiler.jeandle.intrinsic.TestStringUTF16CharAccess
 */
package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestStringUTF16CharAccess {
    private static final String PASSED = "TestStringUTF16CharAccess PASSED";
    private static final String GET_NAME = "java.lang.StringUTF16.getChar";
    private static final String PUT_NAME = "java.lang.StringUTF16.putChar";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            run(true, null);
            run(false, "-XX:ControlIntrinsic=-_getCharStringU,-_putCharStringU");
        } else {
            semantics(args[0].equals("enabled"));
        }
    }

    private static void run(boolean enabled, String option) throws Exception {
        Path dump = Files.createTempDirectory("jeandle_string_utf16_char_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "--patch-module=java.base=" + System.getProperty("test.class.path"),
                "-Xbatch", "-Xcomp", "-XX:-TieredCompilation",
                "-XX:-BackgroundCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+UnlockDiagnosticVMOptions", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly,java.lang.JeandleStringUTF16CharCaller::*"));
        if (option != null) {
            command.add(option);
        }
        command.add(TestStringUTF16CharAccess.class.getName());
        command.add(enabled ? "enabled" : "disabled");

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain(PASSED);
        String intrinsicLog = ".*" + GET_NAME + ".*is parsed as intrinsic.*";
        String storeIntrinsicLog = ".*" + PUT_NAME + ".*is parsed as intrinsic.*";
        if (enabled) {
            output.shouldMatch(intrinsicLog).shouldMatch(storeIntrinsicLog);
            Asserts.assertTrue(containsMarker(dump, "string_getchar_result"),
                    "missing getChar intrinsic IR");
            Asserts.assertTrue(containsMarker(dump, "load atomic i16"),
                    "getChar load must be unordered atomic");
            Asserts.assertTrue(containsMarker(dump, "string_putchar_value"),
                    "missing putChar intrinsic IR");
            Asserts.assertTrue(containsMarker(dump, "store atomic i16"),
                    "putChar store must be unordered atomic");
        } else {
            output.shouldNotMatch(intrinsicLog).shouldNotMatch(storeIntrinsicLog);
        }
    }

    private static boolean containsMarker(Path dump, String marker) throws Exception {
        try (Stream<Path> files = Files.walk(dump)) {
            return files.filter(path -> path.toString().endsWith(".ll"))
                    .anyMatch(path -> contains(path, marker));
        }
    }

    private static boolean contains(Path path, String marker) {
        try {
            return Files.readString(path).contains(marker);
        } catch (Exception e) {
            return false;
        }
    }

    private static void semantics(boolean enabled) throws Exception {
        // Initialize StringUTF16 before -Xcomp compiles the caller methods.
        Class.forName("java.lang.StringUTF16", true, null);
        Class<?> caller = Class.forName("java.lang.JeandleStringUTF16CharCaller");
        Method get = caller.getMethod("get", byte[].class, int.class);
        Method put = caller.getMethod("put", byte[].class, int.class, int.class);

        byte[] value = new byte[8];
        put.invoke(null, value, 1, 0x1234);
        byte first = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
                ? (byte) 0x12 : (byte) 0x34;
        byte second = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN
                ? (byte) 0x34 : (byte) 0x12;
        Asserts.assertEquals(first, value[2]);
        Asserts.assertEquals(second, value[3]);
        Asserts.assertEquals(0x1234, (int) (char) get.invoke(null, value, 1));

        put.invoke(null, value, 0, 0xabcd);
        Asserts.assertEquals(0xabcd, (int) (char) get.invoke(null, value, 0));

        // The intrinsic follows C2's trusted-caller contract and does not
        // perform bounds checks. Null references still throw NPE, while
        // out-of-bounds arguments are tested only through the disabled
        // fallback path.
        expectFailure(get, NullPointerException.class, null, 0);
        expectFailure(put, NullPointerException.class, null, 0, 1);
        if (!enabled) {
            expectFailure(get, ArrayIndexOutOfBoundsException.class, value, -1);
            expectFailure(get, ArrayIndexOutOfBoundsException.class, value, 4);
            expectFailure(put, ArrayIndexOutOfBoundsException.class, value, -1, 1);
            expectFailure(put, ArrayIndexOutOfBoundsException.class, value, 4, 1);
        }
        System.out.println(PASSED);
    }

    private static void expectFailure(Method method, Class<? extends Throwable> type,
                                      Object... args) throws Exception {
        try {
            method.invoke(null, args);
            throw new AssertionError("expected " + type.getName());
        } catch (InvocationTargetException e) {
            Asserts.assertTrue(type.isInstance(e.getCause()),
                    "expected " + type.getName() + ", got " + e.getCause());
        }
    }
}
