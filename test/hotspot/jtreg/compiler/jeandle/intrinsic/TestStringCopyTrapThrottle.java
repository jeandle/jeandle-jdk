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
 * @summary Repeated String copy guard failures must settle on compiled Java fallback
 * @requires (os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64") & os.family!="windows"
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.intrinsic.StringIntrinsicTestSupport
 * @compile --patch-module java.base=${test.src} java/lang/JeandleStringCodingEncodeCaller.java
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyTrapThrottle zero
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyTrapThrottle null
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyTrapThrottle range
 * @run main/othervm compiler.jeandle.intrinsic.TestStringCopyTrapThrottle overflow
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

import static compiler.jeandle.intrinsic.StringIntrinsicTestSupport.*;

public class TestStringCopyTrapThrottle {
    private static final int RANGE_TRAP_LIMIT = 2;

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            Path dump = Files.createTempDirectory(Path.of(System.getProperty("user.dir")),
                    "string_copy_throttle_" + args[0]);
            List<String> command = new ArrayList<>(List.of(
                    "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                    "--patch-module=java.base=" + System.getProperty("test.class.path"),
                    "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                    "-XX:JeandleLLVMOptions=-jeandle-verify-safepoint-coverage=fatal",
                    "-XX:PerBytecodeTrapLimit=" + RANGE_TRAP_LIMIT,
                    "-Xlog:deoptimization=debug",
                    "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                    "-XX:CompileCommand=compileonly,java.lang.JeandleStringCodingEncodeCaller::*",
                    TestStringCopyTrapThrottle.class.getName(), args[0], dump.toString()));
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(command));
            output.shouldHaveExitValue(0).shouldContain("THROTTLE_OK " + args[0]);
            if (args[0].equals("zero")) {
                output.shouldNotMatch(
                        "JeandleStringCodingEncodeCaller\\.[^\\r\\n]*trap_bci=\\d+ intrinsic");
            }
            return;
        }

        WhiteBox wb = WhiteBox.getWhiteBox();
        int checked = 0;
        for (String name : List.of("compressChars", "compressBytes", "inflateChars",
                                   "inflateBytes", "encodeByte", "encodeAscii")) {
            boolean charSource = name.equals("compressChars") || name.equals("encodeAscii");
            boolean charDestination = name.equals("inflateChars");
            Method method = caller(name, charSource ? char[].class : byte[].class,
                    charDestination ? char[].class : byte[].class);
            Object src = charSource ? new char[64] : new byte[128];
            Object dst = charDestination ? new char[64] : new byte[128];
            // Resolve the candidate's holder before forcing compilation.
            method.invoke(null, src, 0, dst, 0, 32);
            wb.markMethodProfiled(method);
            compile(method);
            Asserts.assertTrue(contains(args[1], "JeandleStringCodingEncodeCaller_" + name,
                    name.startsWith("inflate") ? "inflate_vec" : "encode_vec"),
                    "initial compilation did not lower " + name);
            method.invoke(null, src, 0, dst, 0, 32);
            assertCompiled(method);
            Asserts.assertEquals(wb.getMethodTrapCount(method), 0,
                    "unexpected trap during warmup: " + name);

            boolean nullInput = args[0].equals("null");
            Object coldSrc = nullInput ? null : src;
            Object coldDst = dst;
            int coldLength = args[0].equals("zero") ? 0 : 32;
            int coldOffset = switch (args[0]) {
                case "range" -> -1;
                case "overflow" -> Integer.MAX_VALUE;
                default -> 0;
            };
            invokeCold(method, coldSrc, coldDst, coldOffset, coldLength, nullInput);
            if (args[0].equals("zero")) {
                // A valid empty range stays in compiled code without a trap.
                assertCompiled(method);
                Asserts.assertEquals(wb.getMethodTrapCount(method), 0);
            } else {
                boolean delayedRangeTrap = !name.startsWith("encode")
                        && (args[0].equals("range") || args[0].equals("overflow"));
                boolean delayedNullTrap = nullInput;
                if (delayedRangeTrap || delayedNullTrap) {
                    // maybe_recompile records an occasional failure but keeps
                    // the nmethod until the trap limit, including null NPEs.
                    assertCompiled(method);
                    Asserts.assertEquals(wb.getMethodTrapCount(method), 1,
                        "first failure must be recorded: " + name);
                    invokeCold(method, coldSrc, coldDst, coldOffset, coldLength, nullInput);
                }
                Asserts.assertFalse(wb.isMethodCompiled(method),
                        "guard must invalidate at the expected trap limit: " + name);
                Asserts.assertEquals(wb.getMethodTrapCount(method),
                        (delayedRangeTrap || delayedNullTrap) ? RANGE_TRAP_LIMIT : 1,
                        "wrong invalidation policy: " + name);
                compile(method);
            }
            int deopts = wb.getDeoptCount();
            for (int i = 0; i < 20; i++) {
                invokeCold(method, coldSrc, coldDst, coldOffset, coldLength, nullInput);
                assertCompiled(method);
            }
            Asserts.assertEquals(wb.getDeoptCount(), deopts,
                    "continued deoptimization after recompilation: " + name);
            checked++;
        }
        Asserts.assertEquals(checked, 6);
        System.out.println("THROTTLE_OK " + args[0] + " methods=" + checked);
    }

    private static void invokeCold(Method method, Object src, Object dst,
                                   int offset, int length, boolean expectNull) throws Exception {
        try {
            Object result = method.invoke(null, src, 0, dst, offset, length);
            Asserts.assertFalse(expectNull, "expected NullPointerException: " + method);
            Asserts.assertEquals(offset, 0, "expected bounds exception");
            if (method.getReturnType() == int.class) {
                Asserts.assertEquals(((Integer) result).intValue(), length);
            }
        } catch (InvocationTargetException e) {
            if (expectNull) {
                Asserts.assertTrue(e.getCause() instanceof NullPointerException,
                        "expected NPE: " + e.getCause());
            } else if (offset == 0 || !(e.getCause() instanceof IndexOutOfBoundsException)) {
                throw e;
            }
        }
    }
}
