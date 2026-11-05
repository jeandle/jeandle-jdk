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
 * 2 along with this work; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

/*
 * @test
 * @summary Verify ControlIntrinsic for all Jeandle boxing and unboxing intrinsics
 * @library /test/lib /
 * @run main/othervm compiler.jeandle.intrinsic.TestBoxingIntrinsic
 */

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestBoxingIntrinsic {
    private static final String DISABLE_BOXING_INTRINSICS =
            "-XX:ControlIntrinsic=-_Boolean_valueOf,-_Byte_valueOf,"
            + "-_Character_valueOf,-_Short_valueOf,-_Integer_valueOf,"
            + "-_Long_valueOf,-_Float_valueOf,-_Double_valueOf,"
            + "-_booleanValue,-_byteValue,-_charValue,-_shortValue,"
            + "-_intValue,-_longValue,-_floatValue,-_doubleValue";

    private static final String[] ROUND_TRIP_NAMES = {
            "booleanRoundTrip", "byteRoundTrip", "charRoundTrip",
            "shortRoundTrip", "intRoundTrip", "longRoundTrip",
            "floatRoundTrip", "doubleRoundTrip"
    };
    private static final Class<?>[] PARAMETER_TYPES = {
            boolean.class, byte.class, char.class, short.class,
            int.class, long.class, float.class, double.class
    };
    private static final String[] HOLDER_NAMES = {
            "Boolean", "Byte", "Character", "Short",
            "Integer", "Long", "Float", "Double"
    };
    private static final String[] UNBOX_NAMES = {
            "booleanValue", "byteValue", "charValue", "shortValue",
            "intValue", "longValue", "floatValue", "doubleValue"
    };

    public static void main(String[] args) throws Exception {
        runCase(true, true, true);
        runCase(false, true, true);
        runCase(true, true, false);
        runCase(false, true, false);
        // JeandleOptBoxing is a frontend isolation boundary, independent of
        // ControlIntrinsic: no semantic boxing JavaOp may be emitted when it
        // is disabled.
        runCase(true, false, true);
    }

    private static void runCase(boolean intrinsicEnabled,
                                boolean optBoxingEnabled,
                                boolean compressedOops) throws Exception {
        String mode = (intrinsicEnabled ? "enabled" : "disabled")
                + (optBoxingEnabled ? "_opt_boxing" : "_no_opt_boxing")
                + (compressedOops ? "_compressed" : "_uncompressed");
        String dumpPath = Files.createTempDirectory(
                "jeandle_boxing_control_" + mode).toString();
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-Xcomp", "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly,"
                        + TestWrapper.class.getName() + "::*"));
        for (String holder : HOLDER_NAMES) {
            command.add("-XX:CompileCommand=dontinline,java.lang."
                    + holder + "::*");
        }
        if (!compressedOops) {
            command.add("-XX:-UseCompressedOops");
            command.add("-XX:-UseCompressedClassPointers");
        }
        if (!intrinsicEnabled) {
            command.add("-XX:+UnlockDiagnosticVMOptions");
            command.add(DISABLE_BOXING_INTRINSICS);
        }
        if (!optBoxingEnabled) {
            command.add("-XX:-JeandleOptBoxing");
        }
        command.add(TestWrapper.class.getName());

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("BOXING-CONTROL-PASSED");
        checkIR(dumpPath, intrinsicEnabled && optBoxingEnabled);
    }

    private static void checkIR(String dumpPath, boolean enabled)
            throws Exception {
        for (int index = 0; index < ROUND_TRIP_NAMES.length; index++) {
            Method method = TestWrapper.class.getMethod(
                    ROUND_TRIP_NAMES[index], PARAMETER_TYPES[index]);
            FileCheck check = new FileCheck(dumpPath, method, false, 0);
            if (enabled) {
                // Every wrapper uses one semantic operation through PEA.
                // Boolean/Byte become cache-only and Float/Double become
                // allocation-only when the surviving operation is expanded.
                check.checkPattern("invoke hotspotcc.*@jeandle\\.box\\(");
                check.checkPattern("call hotspotcc.*@jeandle\\.unbox\\(");
                check.checkNotPattern("invoke hotspotcc.*java_lang_"
                        + HOLDER_NAMES[index] + "_valueOf");
                check.checkNotPattern("invoke hotspotcc.*java_lang_"
                        + HOLDER_NAMES[index] + "_" + UNBOX_NAMES[index]);
            } else {
                check.checkNotPattern("invoke hotspotcc.*@jeandle\\.box\\(");
                check.checkNotPattern("call hotspotcc.*@jeandle\\.unbox\\(");
                check.checkPattern("invoke hotspotcc.*java_lang_"
                        + HOLDER_NAMES[index] + "_valueOf");
                check.checkPattern("invoke hotspotcc.*java_lang_"
                        + HOLDER_NAMES[index] + "_" + UNBOX_NAMES[index]);
            }
        }
    }

    public static class TestWrapper {
        private static final Object[] CACHE_WARMUP = {
                Boolean.valueOf(false), Byte.valueOf((byte) 0),
                Character.valueOf((char) 0), Short.valueOf((short) 0),
                Integer.valueOf(0), Long.valueOf(0L),
                Float.valueOf(0.0f), Double.valueOf(0.0d)
        };

        public static void main(String[] args) {
            for (int round = 0; round < 1_000; round++) {
                boolean booleanValue = (round & 1) != 0;
                byte byteValue = (byte) round;
                char charValue = (char) round;
                short shortValue = (short) round;
                int intValue = round * 257 - 100_000;
                long longValue = (long) intValue * intValue - round;
                float floatValue = Float.intBitsToFloat(round * 0x10203);
                double doubleValue = Double.longBitsToDouble(
                        (long) round * 0x1020_3040_5060_7080L);

                Asserts.assertEquals(booleanRoundTrip(booleanValue), booleanValue);
                Asserts.assertEquals(byteRoundTrip(byteValue), byteValue);
                Asserts.assertEquals(charRoundTrip(charValue), charValue);
                Asserts.assertEquals(shortRoundTrip(shortValue), shortValue);
                Asserts.assertEquals(intRoundTrip(intValue), intValue);
                Asserts.assertEquals(longRoundTrip(longValue), longValue);
                Asserts.assertEquals(
                        Float.floatToRawIntBits(floatRoundTrip(floatValue)),
                        Float.floatToRawIntBits(floatValue));
                Asserts.assertEquals(
                        Double.doubleToRawLongBits(doubleRoundTrip(doubleValue)),
                        Double.doubleToRawLongBits(doubleValue));
            }
            if (CACHE_WARMUP.length != 8) {
                throw new AssertionError("wrapper caches were not initialized");
            }
            System.out.println("BOXING-CONTROL-PASSED");
        }

        public static boolean booleanRoundTrip(boolean value) {
            return Boolean.valueOf(value).booleanValue();
        }

        public static byte byteRoundTrip(byte value) {
            return Byte.valueOf(value).byteValue();
        }

        public static char charRoundTrip(char value) {
            return Character.valueOf(value).charValue();
        }

        public static short shortRoundTrip(short value) {
            return Short.valueOf(value).shortValue();
        }

        public static int intRoundTrip(int value) {
            return Integer.valueOf(value).intValue();
        }

        public static long longRoundTrip(long value) {
            return Long.valueOf(value).longValue();
        }

        public static float floatRoundTrip(float value) {
            return Float.valueOf(value).floatValue();
        }

        public static double doubleRoundTrip(double value) {
            return Double.valueOf(value).doubleValue();
        }
    }
}
