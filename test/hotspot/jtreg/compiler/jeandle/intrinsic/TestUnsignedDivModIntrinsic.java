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
 * @key randomness
 * @summary Test Jeandle lowering of Integer/Long unsigned divide and remainder
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsignedDivModIntrinsic
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsignedDivModIntrinsic {
    private static final String[] LOGS = {
            "Method `static jint java.lang.Integer.divideUnsigned(jint, jint)` is parsed as intrinsic",
            "Method `static jint java.lang.Integer.remainderUnsigned(jint, jint)` is parsed as intrinsic",
            "Method `static jlong java.lang.Long.divideUnsigned(jlong, jlong)` is parsed as intrinsic",
            "Method `static jlong java.lang.Long.remainderUnsigned(jlong, jlong)` is parsed as intrinsic"
    };

    private static final String DISABLE = String.join(",",
            "-_divideUnsigned_i", "-_divideUnsigned_l",
            "-_remainderUnsigned_i", "-_remainderUnsigned_l");

    public static void main(String[] args) throws Exception {
        String enabledDump = Files.createTempDirectory("jeandle_unsigned_divmod_enabled").toString();
        OutputAnalyzer enabled = runChild(enabledDump, true);
        enabled.shouldHaveExitValue(0);
        for (String log : LOGS) {
            enabled.shouldContain(log);
        }

        checkEnabledIR(enabledDump, "divideInt", int.class, "udiv", "i32");
        checkEnabledIR(enabledDump, "remainderInt", int.class, "urem", "i32");
        checkEnabledIR(enabledDump, "divideLong", long.class, "udiv", "i64");
        checkEnabledIR(enabledDump, "remainderLong", long.class, "urem", "i64");

        String disabledDump = Files.createTempDirectory("jeandle_unsigned_divmod_disabled").toString();
        OutputAnalyzer disabled = runChild(disabledDump, false);
        disabled.shouldHaveExitValue(0);
        for (String log : LOGS) {
            disabled.shouldNotContain(log);
        }

        checkDisabledIR(disabledDump, "divideInt", int.class, "udiv", "i32");
        checkDisabledIR(disabledDump, "remainderInt", int.class, "urem", "i32");
        checkDisabledIR(disabledDump, "divideLong", long.class, "udiv", "i64");
        checkDisabledIR(disabledDump, "remainderLong", long.class, "urem", "i64");
    }

    private static OutputAnalyzer runChild(String dumpPath, boolean enabled) throws Exception {
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler", "-Xcomp",
                "-XX:+UnlockDiagnosticVMOptions", "-Xlog:jeandle=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleDumpObjects",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::divideInt",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::remainderInt",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::divideLong",
                "-XX:CompileCommand=compileonly," + Workload.class.getName() + "::remainderLong"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=" + DISABLE);
        }
        command.add(Workload.class.getName());
        return ProcessTools.executeCommand(ProcessTools.createLimitedTestJavaProcessBuilder(command));
    }

    private static void checkEnabledIR(String dumpPath, String methodName, Class<?> type,
                                       String operation, String width) throws Exception {
        FileCheck check = new FileCheck(dumpPath,
                Workload.class.getMethod(methodName, type, type), false);
        check.checkPattern("%[0-9]+ = icmp eq " + width + " %1, 0");
        check.checkNextPattern("br i1 %[0-9]+, label %bci_.*_zero_check_fail, "
                + "label %bci_.*_zero_check_pass");
        check.checkNextPattern("bci_.*_zero_check_pass:");
        check.checkNextPattern("%[0-9]+ = " + operation + " " + width + " %0, %1");
        check.checkPattern("bci_.*_zero_check_fail:");
        String deopt = "%[0-9]+ = call hotspotcc " + width
                + " \\(\\.\\.\\.\\) @llvm\\.experimental\\.deoptimize\\." + width
                + "\\(i32 -122\\) \\[ \\\"deopt\\\"\\(.*";
        if (type == long.class) {
            deopt += "i64 8589934691, i32 0, i64 12884901987, i32 0, "
                    + "i64 65547, i64 %0, i64 8590000139, i64 %1,";
        } else {
            deopt += "i64 65546, i32 %0, i64 4295032842, i32 %1,";
        }
        check.checkNextPattern(deopt);
    }

    private static void checkDisabledIR(String dumpPath, String methodName, Class<?> type,
                                        String operation, String width) throws Exception {
        FileCheck check = new FileCheck(dumpPath,
                Workload.class.getMethod(methodName, type, type), false);
        check.checkNotPattern(operation + " " + width);
        check.checkNotPattern("llvm\\.experimental\\.deoptimize\\." + width);
    }

    public static class Workload {
        private static final BigInteger MASK_64 = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        private static final int[] INT_VALUES = {
                0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                0x80000001, 0x7ffffffe, 0x55555555, 0xaaaaaaaa
        };
        private static final long[] LONG_VALUES = {
                0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                0x8000000000000001L, 0x7ffffffffffffffeL,
                0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL
        };

        static final int INT_DUMMY = Integer.divideUnsigned(1, 1);
        static final long LONG_DUMMY = Long.remainderUnsigned(1L, 1L);

        public static void main(String[] args) {
            checkBoundaries();
            checkRandomValues();
            checkDivisionByZero();
            Asserts.assertEquals(divideInt(-1, 3), refDivideInt(-1, 3));
            Asserts.assertEquals(remainderLong(-1L, 7L), refRemainderLong(-1L, 7L));
        }

        private static void checkBoundaries() {
            for (int dividend : INT_VALUES) {
                for (int divisor : INT_VALUES) {
                    if (divisor != 0) {
                        checkInt(dividend, divisor);
                    }
                }
            }
            for (long dividend : LONG_VALUES) {
                for (long divisor : LONG_VALUES) {
                    if (divisor != 0) {
                        checkLong(dividend, divisor);
                    }
                }
            }
        }

        private static void checkRandomValues() {
            Random random = Utils.getRandomInstance();
            for (int i = 0; i < 1_000; i++) {
                int intDivisor = random.nextInt();
                if (intDivisor == 0) {
                    intDivisor = 1;
                }
                checkInt(random.nextInt(), intDivisor);

                long longDivisor = random.nextLong();
                if (longDivisor == 0) {
                    longDivisor = 1;
                }
                checkLong(random.nextLong(), longDivisor);
            }
        }

        private static void checkDivisionByZero() {
            expectArithmetic(() -> divideInt(1, 0));
            expectArithmetic(() -> remainderInt(1, 0));
            expectArithmetic(() -> divideLong(1L, 0L));
            expectArithmetic(() -> remainderLong(1L, 0L));
        }

        private static void checkInt(int dividend, int divisor) {
            Asserts.assertEquals(divideInt(dividend, divisor), refDivideInt(dividend, divisor));
            Asserts.assertEquals(remainderInt(dividend, divisor), refRemainderInt(dividend, divisor));
        }

        private static void checkLong(long dividend, long divisor) {
            Asserts.assertEquals(divideLong(dividend, divisor), refDivideLong(dividend, divisor));
            Asserts.assertEquals(remainderLong(dividend, divisor), refRemainderLong(dividend, divisor));
        }

        private static int refDivideInt(int dividend, int divisor) {
            return (int) (Integer.toUnsignedLong(dividend) / Integer.toUnsignedLong(divisor));
        }

        private static int refRemainderInt(int dividend, int divisor) {
            return (int) (Integer.toUnsignedLong(dividend) % Integer.toUnsignedLong(divisor));
        }

        private static long refDivideLong(long dividend, long divisor) {
            return unsigned(dividend).divide(unsigned(divisor)).longValue();
        }

        private static long refRemainderLong(long dividend, long divisor) {
            return unsigned(dividend).remainder(unsigned(divisor)).longValue();
        }

        private static BigInteger unsigned(long value) {
            return BigInteger.valueOf(value).and(MASK_64);
        }

        private static void expectArithmetic(Runnable action) {
            try {
                action.run();
                Asserts.fail("expected ArithmeticException");
            } catch (ArithmeticException expected) {
                // Expected.
            }
        }

        public static int divideInt(int dividend, int divisor) {
            return Integer.divideUnsigned(dividend, divisor);
        }

        public static int remainderInt(int dividend, int divisor) {
            return Integer.remainderUnsigned(dividend, divisor);
        }

        public static long divideLong(long dividend, long divisor) {
            return Long.divideUnsigned(dividend, divisor);
        }

        public static long remainderLong(long dividend, long divisor) {
            return Long.remainderUnsigned(dividend, divisor);
        }
    }
}
