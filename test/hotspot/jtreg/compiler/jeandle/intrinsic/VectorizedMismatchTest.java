/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package compiler.jeandle.intrinsic;

/*
 * @test
 * @requires vm.opt.final.UseVectorizedMismatchIntrinsic == true
 * @summary Test Jeandle vectorizedMismatch intrinsic lowering and semantics
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.util
 * @library /test/lib /
 *
 *  @run main/othervm -XX:CompileCommand=quiet -XX:CompileCommand=compileonly,*::test*
 *                    -XX:CompileCommand=compileonly,*::mismatch*
 *                    -Xbatch -XX:-TieredCompilation
 *                    -XX:+IgnoreUnrecognizedVMOptions
 *                    -XX:UseAVX=3
 *                     compiler.jeandle.intrinsic.VectorizedMismatchTest
 *
 *  @run main/othervm -XX:CompileCommand=quiet -XX:CompileCommand=compileonly,*::test*
 *                    -XX:CompileCommand=compileonly,*::mismatch*
 *                    -Xbatch -XX:-TieredCompilation
 *                    -XX:+UnlockDiagnosticVMOptions -XX:+IgnoreUnrecognizedVMOptions
 *                    -XX:UseAVX=3 -XX:AVX3Threshold=0
 *                     compiler.jeandle.intrinsic.VectorizedMismatchTest
 */

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import jdk.test.lib.Platform;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class VectorizedMismatchTest {
    private static final Pattern STUB_CALL = Pattern.compile(
            "(?m)\\bcall\\b[^\\r\\n]*@StubRoutines_vectorizedMismatch\\b");
    private static final Pattern INLINE_SMALL_LOAD = Pattern.compile(
            "(?m)\\bmismatch_inline_small_a_i(?:64|32|16|8)\\b");
    private static final Pattern INLINE_SMALL_I64_LOAD = Pattern.compile(
            "(?m)\\bmismatch_inline_small_a_i64\\b");
    private static final Pattern INLINE_VECTOR_LOAD = Pattern.compile(
            "(?m)\\bmismatch_inline_vector_a\\b");
    private static final Pattern ARRAY_OPERATION_PARTIAL_INLINE_SIZE = Pattern.compile(
            "(?m)^\\s*intx\\s+ArrayOperationPartialInlineSize\\s*=\\s*(\\d+)\\b");
    private static final Pattern DIRECT_MEMORY_GEP = Pattern.compile(
            "(?m)getelementptr i8, ptr addrspace\\(1\\) null");

    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    // Dedicated wrappers keep the length constant at each compiled call site.
    // The selected lengths exercise the inline and platform-stub boundaries
    // after the element count is converted to a byte count.

    // Boolean cases
    private boolean[] boolean_a = new boolean[128];
    private boolean[] boolean_b = new boolean[128];

    int testBooleanConstantLength(int length) {
        boolean[] obja = boolean_a;
        boolean[] objb = boolean_b;
        long offset = Unsafe.ARRAY_BOOLEAN_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_BOOLEAN_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testBooleanConstantLength0()   { return testBooleanConstantLength(0);   }
    int testBooleanConstantLength1()   { return testBooleanConstantLength(1);   }
    int testBooleanConstantLength64()  { return testBooleanConstantLength(64);  }
    int testBooleanConstantLength128() { return testBooleanConstantLength(128); }

    // Byte cases
    private byte[] byte_a = new byte[128];
    private byte[] byte_b = new byte[128];

    int testByteConstantLength(int length) {
        byte[] obja = byte_a;
        byte[] objb = byte_b;
        long offset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testByteConstantLength0()   { return testByteConstantLength(0);   }
    int testByteConstantLength1()   { return testByteConstantLength(1);   }
    int testByteConstantLength8()   { return testByteConstantLength(8);   }
    int testByteConstantLength15()  { return testByteConstantLength(15);  }
    int testByteConstantLength16()  { return testByteConstantLength(16);  }
    int testByteConstantLength32()  { return testByteConstantLength(32);  }
    int testByteConstantLength63()  { return testByteConstantLength(63);  }
    int testByteConstantLength64()  { return testByteConstantLength(64);  }
    int testByteConstantLength128() { return testByteConstantLength(128); }

    // Short cases
    private short[] short_a = new short[64];
    private short[] short_b = new short[64];

    int testShortConstantLength(int length) {
        short[] obja = short_a;
        short[] objb = short_b;
        long offset = Unsafe.ARRAY_SHORT_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_SHORT_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testShortConstantLength0()  { return testShortConstantLength(0);  }
    int testShortConstantLength1()  { return testShortConstantLength(1);  }
    int testShortConstantLength32() { return testShortConstantLength(32); }
    int testShortConstantLength64() { return testShortConstantLength(64); }

    // Char cases
    private char[] char_a = new char[64];
    private char[] char_b = new char[64];

    int testCharConstantLength(int length) {
        char[] obja = char_a;
        char[] objb = char_b;
        long offset = Unsafe.ARRAY_CHAR_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_CHAR_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testCharConstantLength0()  { return testCharConstantLength(0);  }
    int testCharConstantLength1()  { return testCharConstantLength(1);  }
    int testCharConstantLength32() { return testCharConstantLength(32); }
    int testCharConstantLength64() { return testCharConstantLength(64); }

    // Int cases
    private int[] int_a = new int[32];
    private int[] int_b = new int[32];

    int testIntConstantLength(int length) {
        int[] obja = int_a;
        int[] objb = int_b;
        long offset = Unsafe.ARRAY_INT_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_INT_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testIntConstantLength0()  { return testIntConstantLength(0);  }
    int testIntConstantLength1()  { return testIntConstantLength(1);  }
    int testIntConstantLength16() { return testIntConstantLength(16); }
    int testIntConstantLength32() { return testIntConstantLength(32); }

    // Float cases
    private float[] float_a = new float[32];
    private float[] float_b = new float[32];

    int testFloatConstantLength(int length) {
        float[] obja = float_a;
        float[] objb = float_b;
        long offset = Unsafe.ARRAY_FLOAT_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_FLOAT_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testFloatConstantLength0()  { return testFloatConstantLength(0);  }
    int testFloatConstantLength1()  { return testFloatConstantLength(1);  }
    int testFloatConstantLength16() { return testFloatConstantLength(16); }
    int testFloatConstantLength32() { return testFloatConstantLength(32); }

    // Long cases
    private long[] long_a = new long[16];
    private long[] long_b = new long[16];

    int testLongConstantLength(int length) {
        long[] obja = long_a;
        long[] objb = long_b;
        long offset = Unsafe.ARRAY_LONG_BASE_OFFSET;
        int scale = ArraysSupport.LOG2_ARRAY_LONG_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testLongConstantLength0()  { return testLongConstantLength(0);  }
    int testLongConstantLength1()  { return testLongConstantLength(1);  }
    int testLongConstantLength8()  { return testLongConstantLength(8);  }
    int testLongConstantLength16() { return testLongConstantLength(16); }

    // Double cases
    private double[] double_a = new double[16];
    private double[] double_b = new double[16];

    int testDoubleConstantLength(int length) {
        double[] obja = double_a;
        double[] objb = double_b;
        long offset = Unsafe.ARRAY_DOUBLE_BASE_OFFSET;
        int  scale  = ArraysSupport.LOG2_ARRAY_DOUBLE_INDEX_SCALE;
        return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, length, scale);
    }

    int testDoubleConstantLength0()  { return testDoubleConstantLength(0);  }
    int testDoubleConstantLength1()  { return testDoubleConstantLength(1);  }
    int testDoubleConstantLength8()  { return testDoubleConstantLength(8);  }
    int testDoubleConstantLength16() { return testDoubleConstantLength(16); }

    // Class-initialization and loop-optimization cases
    static class ClassInitTest {
        static final int LENGTH = 64;
        static final int RESULT;
        static {
            byte[] arr1 = new byte[LENGTH];
            byte[] arr2 = new byte[LENGTH];
            for (int i = 0; i < 20_000; i++) {
                test(arr1, arr2);
            }
            RESULT = test(arr1, arr2);
        }

        static int test(byte[] obja, byte[] objb) {
            long offset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
            int  scale  = ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE;
            return ArraysSupport.vectorizedMismatch(obja, offset, objb, offset, LENGTH, scale); // LENGTH is not considered a constant
        }
    }

    int testConstantBeingInitialized() {
        return ClassInitTest.RESULT; // trigger class initialization
    }

    int testLoopUnswitch(int length) {
        long offset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        int  scale  = ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE;

        int acc = 0;
        for (int i = 0; i < 32; i++) {
            acc += ArraysSupport.vectorizedMismatch(byte_a, offset, byte_b, offset, length, scale);
        }
        return acc;
    }

    int testLoopHoist(int length, int stride) {
        long offset = Unsafe.ARRAY_BYTE_BASE_OFFSET;
        int  scale  = ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE;

        int acc = 0;

        for (int i = 0; i < 32; i += stride) {
            acc += ArraysSupport.vectorizedMismatch(byte_a, offset, byte_b, offset, length, scale);
        }
        return acc;
    }

    /* ==================================================================================== */

    public static void main(String[] args) throws Exception {
        VectorizedMismatchTest t = new VectorizedMismatchTest();
        for (int i = 0; i < 20_000; i++) {
            t.testBooleanConstantLength0();
            t.testBooleanConstantLength1();
            t.testBooleanConstantLength64();
            t.testBooleanConstantLength128();

            t.testByteConstantLength0();
            t.testByteConstantLength1();
            t.testByteConstantLength8();
            t.testByteConstantLength15();
            t.testByteConstantLength16();
            t.testByteConstantLength32();
            t.testByteConstantLength63();
            t.testByteConstantLength64();
            t.testByteConstantLength128();

            t.testShortConstantLength0();
            t.testShortConstantLength1();
            t.testShortConstantLength32();
            t.testShortConstantLength64();

            t.testCharConstantLength0();
            t.testCharConstantLength1();
            t.testCharConstantLength32();
            t.testCharConstantLength64();

            t.testIntConstantLength0();
            t.testIntConstantLength1();
            t.testIntConstantLength16();
            t.testIntConstantLength32();

            t.testFloatConstantLength0();
            t.testFloatConstantLength1();
            t.testFloatConstantLength16();
            t.testFloatConstantLength32();

            t.testLongConstantLength0();
            t.testLongConstantLength1();
            t.testLongConstantLength8();
            t.testLongConstantLength16();

            t.testDoubleConstantLength0();
            t.testDoubleConstantLength1();
            t.testDoubleConstantLength8();
            t.testDoubleConstantLength16();

            t.testConstantBeingInitialized();
            t.testLoopUnswitch(32);
            t.testLoopHoist(128, 2);
        }

        t.byte_b[7] = 1;
        expect(7, t.testByteConstantLength8());
        t.byte_b[7] = 0;
        t.byte_b[14] = 1;
        expect(14, t.testByteConstantLength15());
        t.byte_b[14] = 0;
        t.byte_b[15] = 1;
        expect(15, t.testByteConstantLength16());
        t.byte_b[15] = 0;
        t.byte_b[31] = 1;
        expect(31, t.testByteConstantLength32());
        t.byte_b[31] = 0;
        t.byte_b[62] = 1;
        expect(62, t.testByteConstantLength63());
        t.byte_b[62] = 0;

        verifyResultValues();
        verifyDirectMemoryValues();
        if (args.length == 0) {
            verifyIntrinsicLowering();
        } else {
            testBulkByteRanges();
        }
    }

    private static int mismatchBytes(byte[] a, byte[] b, int length) {
        return ArraysSupport.vectorizedMismatch(a, Unsafe.ARRAY_BYTE_BASE_OFFSET,
                                                b, Unsafe.ARRAY_BYTE_BASE_OFFSET,
                                                length,
                                                ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE);
    }

    private static int mismatchBytesAt(byte[] a, int aIndex,
                                       byte[] b, int bIndex, int length) {
        return ArraysSupport.vectorizedMismatch(a, Unsafe.ARRAY_BYTE_BASE_OFFSET + aIndex,
                                                b, Unsafe.ARRAY_BYTE_BASE_OFFSET + bIndex,
                                                length,
                                                ArraysSupport.LOG2_ARRAY_BYTE_INDEX_SCALE);
    }

    private static int testDirectBothNull(long aAddress, long bAddress, int length) {
        return ArraysSupport.vectorizedMismatch(null, aAddress, null, bAddress, length, 0);
    }

    private static int testDirectLeftNull(long aAddress, byte[] b, int bIndex, int length) {
        return ArraysSupport.vectorizedMismatch(null, aAddress,
                                                b, Unsafe.ARRAY_BYTE_BASE_OFFSET + bIndex,
                                                length, 0);
    }

    private static int testDirectRightNull(byte[] a, int aIndex, long bAddress, int length) {
        return ArraysSupport.vectorizedMismatch(a, Unsafe.ARRAY_BYTE_BASE_OFFSET + aIndex,
                                                null, bAddress, length, 0);
    }

    private static void verifyResultValues() {
        byte[] bytesA = new byte[65];
        byte[] bytesB = new byte[65];
        char[] charsA = new char[33];
        char[] charsB = new char[33];
        int[] intsA = new int[17];
        int[] intsB = new int[17];
        long[] longsA = new long[9];
        long[] longsB = new long[9];
        byte[] shiftedA = new byte[68];
        byte[] shiftedB = new byte[70];
        byte[] smallBytesA = new byte[15];
        byte[] smallBytesB = new byte[15];
        byte[] boundaryBytesA = new byte[16];
        byte[] boundaryBytesB = new byte[16];
        char[] smallCharsA = new char[7];
        char[] smallCharsB = new char[7];
        int[] smallIntsA = new int[3];
        int[] smallIntsB = new int[3];
        long[] smallLongsA = new long[1];
        long[] smallLongsB = new long[1];

        bytesB[0] = 1;
        charsB[7] = 1;
        intsB[5] = 1;
        longsB[3] = 1;
        shiftedB[5 + 17] = 1;
        smallBytesB[7] = 1;
        boundaryBytesB[15] = 1;
        smallCharsB[3] = 1;
        smallIntsB[1] = 1;
        smallLongsB[0] = 1;

        for (int i = 0; i < 20_000; i++) {
            expect(0, mismatchBytes(bytesA, bytesB, bytesA.length));
            expect(7, ArraysSupport.vectorizedMismatch(charsA, Unsafe.ARRAY_CHAR_BASE_OFFSET,
                                                        charsB, Unsafe.ARRAY_CHAR_BASE_OFFSET,
                                                        charsA.length,
                                                        ArraysSupport.LOG2_ARRAY_CHAR_INDEX_SCALE));
            expect(5, ArraysSupport.vectorizedMismatch(intsA, Unsafe.ARRAY_INT_BASE_OFFSET,
                                                        intsB, Unsafe.ARRAY_INT_BASE_OFFSET,
                                                        intsA.length,
                                                        ArraysSupport.LOG2_ARRAY_INT_INDEX_SCALE));
            expect(3, ArraysSupport.vectorizedMismatch(longsA, Unsafe.ARRAY_LONG_BASE_OFFSET,
                                                        longsB, Unsafe.ARRAY_LONG_BASE_OFFSET,
                                                        longsA.length,
                                                        ArraysSupport.LOG2_ARRAY_LONG_INDEX_SCALE));
            expect(17, mismatchBytesAt(shiftedA, 3, shiftedB, 5, 65));
            expect(7, mismatchBytes(smallBytesA, smallBytesB, smallBytesA.length));
            expect(15, mismatchBytes(boundaryBytesA, boundaryBytesB, boundaryBytesA.length));
            expect(3, ArraysSupport.vectorizedMismatch(smallCharsA, Unsafe.ARRAY_CHAR_BASE_OFFSET,
                                                        smallCharsB, Unsafe.ARRAY_CHAR_BASE_OFFSET,
                                                        smallCharsA.length,
                                                        ArraysSupport.LOG2_ARRAY_CHAR_INDEX_SCALE));
            expect(1, ArraysSupport.vectorizedMismatch(smallIntsA, Unsafe.ARRAY_INT_BASE_OFFSET,
                                                        smallIntsB, Unsafe.ARRAY_INT_BASE_OFFSET,
                                                        smallIntsA.length,
                                                        ArraysSupport.LOG2_ARRAY_INT_INDEX_SCALE));
            expect(0, ArraysSupport.vectorizedMismatch(smallLongsA, Unsafe.ARRAY_LONG_BASE_OFFSET,
                                                        smallLongsB, Unsafe.ARRAY_LONG_BASE_OFFSET,
                                                        smallLongsA.length,
                                                        ArraysSupport.LOG2_ARRAY_LONG_INDEX_SCALE));
        }

        bytesB[0] = 0;
        charsB[7] = 0;
        intsB[5] = 0;
        longsB[3] = 0;
        shiftedB[5 + 17] = 0;
        smallBytesB[7] = 0;
        boundaryBytesB[15] = 0;
        smallCharsB[3] = 0;
        smallIntsB[1] = 0;
        smallLongsB[0] = 0;
        expectNegative(mismatchBytes(bytesA, bytesB, bytesA.length));
        expectNegative(mismatchBytesAt(shiftedA, 3, shiftedB, 5, 65));
        expectNegative(mismatchBytes(smallBytesA, smallBytesB, smallBytesA.length));
        expectNegative(mismatchBytes(boundaryBytesA, boundaryBytesB, boundaryBytesA.length));
    }

    private static void verifyDirectMemoryValues() {
        // Exercise unaligned raw addresses and mixed heap/native bases across
        // the small, medium, and stub tiers.
        final int[] lengths = {8, 32, 64};
        final int aOffset = 1;
        final int bOffset = 3;
        final int maxLength = lengths[lengths.length - 1];
        long nativeA = UNSAFE.allocateMemory(maxLength + aOffset);
        long nativeB = UNSAFE.allocateMemory(maxLength + bOffset);
        byte[] heapA = new byte[maxLength + aOffset];
        byte[] heapB = new byte[maxLength + bOffset];

        try {
            for (int length : lengths) {
                fillNative(nativeA + aOffset, length);
                fillNative(nativeB + bOffset, length);
                fillHeap(heapA, aOffset, length);
                fillHeap(heapB, bOffset, length);
            }

            for (int i = 0; i < 20_000; i++) {
                for (int length : lengths) {
                    expectNegative(testDirectBothNull(nativeA + aOffset, nativeB + bOffset, length));
                    expectNegative(testDirectLeftNull(nativeA + aOffset, heapB, bOffset, length));
                    expectNegative(testDirectRightNull(heapA, aOffset, nativeB + bOffset, length));
                }
            }

            for (int length : lengths) {
                verifyDirectBothNull(nativeA + aOffset, nativeB + bOffset, length);
                verifyDirectLeftNull(nativeA + aOffset, heapB, bOffset, length);
                verifyDirectRightNull(heapA, aOffset, nativeB + bOffset, length);
            }
        } finally {
            UNSAFE.freeMemory(nativeA);
            UNSAFE.freeMemory(nativeB);
        }
    }

    private static void verifyDirectBothNull(long aAddress, long bAddress, int length) {
        fillNative(aAddress, length);
        fillNative(bAddress, length);
        expectNegative(testDirectBothNull(aAddress, bAddress, length));
        UNSAFE.putByte(bAddress, (byte) (UNSAFE.getByte(aAddress) ^ 1));
        expect(0, testDirectBothNull(aAddress, bAddress, length));
        fillNative(bAddress, length);
        UNSAFE.putByte(bAddress + length - 1, (byte) (UNSAFE.getByte(aAddress + length - 1) ^ 1));
        expect(length - 1, testDirectBothNull(aAddress, bAddress, length));
    }

    private static void verifyDirectLeftNull(long aAddress, byte[] b, int bIndex, int length) {
        fillNative(aAddress, length);
        fillHeap(b, bIndex, length);
        expectNegative(testDirectLeftNull(aAddress, b, bIndex, length));
        b[bIndex] ^= 1;
        expect(0, testDirectLeftNull(aAddress, b, bIndex, length));
        fillHeap(b, bIndex, length);
        b[bIndex + length - 1] ^= 1;
        expect(length - 1, testDirectLeftNull(aAddress, b, bIndex, length));
    }

    private static void verifyDirectRightNull(byte[] a, int aIndex, long bAddress, int length) {
        fillHeap(a, aIndex, length);
        fillNative(bAddress, length);
        expectNegative(testDirectRightNull(a, aIndex, bAddress, length));
        UNSAFE.putByte(bAddress, (byte) (a[aIndex] ^ 1));
        expect(0, testDirectRightNull(a, aIndex, bAddress, length));
        fillNative(bAddress, length);
        UNSAFE.putByte(bAddress + length - 1, (byte) (a[aIndex + length - 1] ^ 1));
        expect(length - 1, testDirectRightNull(a, aIndex, bAddress, length));
    }

    private static void fillNative(long address, int length) {
        for (int i = 0; i < length; i++) {
            UNSAFE.putByte(address + i, mismatchValue(i));
        }
    }

    private static void fillHeap(byte[] array, int offset, int length) {
        for (int i = 0; i < length; i++) {
            array[offset + i] = mismatchValue(i);
        }
    }

    private static byte mismatchValue(int index) {
        return (byte) (index * 31 + 7);
    }

    private static void testBulkByteRanges() {
        int[] lengths = { 255, 256, 257, 512, 513 };
        int[] mismatchIndices = { 0, 63, 64, 127, 128, 191, 192, 255, 256, 511, 512 };
        byte[] a = new byte[513];
        byte[] b = new byte[513];

        for (int i = 0; i < a.length; i++) {
            a[i] = b[i] = (byte) (i * 13);
        }

        for (int length : lengths) {
            expectNegative(mismatchBytes(a, b, length));
            for (int index : mismatchIndices) {
                if (index >= length) {
                    continue;
                }
                b[index] ^= 1;
                expect(index, mismatchBytes(a, b, length));
                b[index] = a[index];
            }
        }
    }

    private static void verifyIntrinsicLowering() throws Exception {
        Path dumpDirectory = Files.createTempDirectory("jeandle_vectorized_mismatch");
        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation",
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+UseJeandleCompiler",
                "-XX:+UseVectorizedMismatchIntrinsic",
                "-XX:+PrintFlagsFinal",
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.VectorizedMismatchTest::*",
                VectorizedMismatchTest.class.getName(), "functional"));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
              .shouldContain("jdk.internal.util.ArraysSupport.vectorizedMismatch")
              .shouldContain("is parsed as intrinsic");

        try (Stream<Path> files = Files.walk(dumpDirectory)) {
            List<Path> irFiles = files.filter(path -> path.toString().endsWith(".ll")).toList();
            boolean foundStubCall = irFiles.stream().anyMatch(VectorizedMismatchTest::containsStubCall);
            if (!foundStubCall) {
                throw new AssertionError("compiled IR does not call StubRoutines_vectorizedMismatch");
            }
            boolean foundInlineSmallPath = irFiles.stream()
                                                   .anyMatch(VectorizedMismatchTest::containsInlineSmallPath);
            if (!foundInlineSmallPath) {
                throw new AssertionError("compiled IR does not contain inline small mismatch loads");
            }
            boolean foundInlineSmallI64Path = irFiles.stream()
                                                      .anyMatch(VectorizedMismatchTest::containsInlineSmallI64Path);
            if (!foundInlineSmallI64Path) {
                throw new AssertionError("compiled IR does not contain inline 64-bit mismatch loads");
            }
            boolean foundInlineVectorPath = irFiles.stream()
                                                    .anyMatch(VectorizedMismatchTest::containsInlineVectorPath);
            var inlineSizeMatcher =
                    ARRAY_OPERATION_PARTIAL_INLINE_SIZE.matcher(output.getOutput());
            if (!inlineSizeMatcher.find()) {
                throw new AssertionError("ArrayOperationPartialInlineSize is missing from VM flags");
            }
            int partialInlineSize = Integer.parseInt(inlineSizeMatcher.group(1));
            boolean expectInlineVectorPath =
                    Platform.isAArch64() && partialInlineSize >= 16;
            if (expectInlineVectorPath && !foundInlineVectorPath) {
                throw new AssertionError("compiled IR does not contain inline vector mismatch loads");
            } else if (!expectInlineVectorPath && foundInlineVectorPath) {
                throw new AssertionError(
                        "compiled IR unexpectedly contains inline vector mismatch loads");
            }
            boolean foundDirectMemoryGep = irFiles.stream()
                                                   .anyMatch(VectorizedMismatchTest::containsDirectMemoryGep);
            if (!foundDirectMemoryGep) {
                throw new AssertionError("compiled IR does not contain a direct-memory mismatch GEP");
            }
        }
    }

    private static boolean containsStubCall(Path path) {
        try {
            return STUB_CALL.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsInlineSmallPath(Path path) {
        try {
            return INLINE_SMALL_LOAD.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsInlineVectorPath(Path path) {
        try {
            return INLINE_VECTOR_LOAD.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsInlineSmallI64Path(Path path) {
        try {
            return INLINE_SMALL_I64_LOAD.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsDirectMemoryGep(Path path) {
        try {
            return DIRECT_MEMORY_GEP.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void expect(int expected, int actual) {
        if (actual != expected) {
            throw new AssertionError("expected " + expected + ", got " + actual);
        }
    }

    private static void expectNegative(int actual) {
        if (actual >= 0) {
            throw new AssertionError("expected negative no-mismatch result, got " + actual);
        }
    }
}
