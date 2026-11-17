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
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you have any questions.
 */

package compiler.jeandle.intrinsic;

import java.nio.ByteOrder;
import java.util.Arrays;

import jdk.internal.misc.Unsafe;
import jdk.internal.util.ArraysSupport;
import jdk.test.whitebox.WhiteBox;

/*
 * @test
 * @summary Test Jeandle vectorizedHashCode intrinsic lowering and semantics
 * @requires (vm.cpu.features ~= ".*avx2.*") | (vm.simpleArch == "aarch64")
 * @modules java.base/jdk.internal.misc
 *          java.base/jdk.internal.util
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 *
 * @run main/othervm/timeout=600 -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -XX:CompileCommand=quiet
 *                   -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestVectorizedHashCode::hash*
 *                   -XX:CompileCommand=compileonly,java.lang.String*::hashCode
 *                   -XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestVectorizedHashCode::observeMaterialized
 *                   -XX:+UseJeandleCompiler -XX:CompileThreshold=1000 -Xbatch -XX:-TieredCompilation
 *                    compiler.jeandle.intrinsic.TestVectorizedHashCode
 */

public class TestVectorizedHashCode {

    private static final Unsafe U = Unsafe.getUnsafe();
    private static final WhiteBox WB = WhiteBox.getWhiteBox();

    // Dynamic-length wrappers keep lengths non-constant at the call site so the
    // intrinsic's runtime path (tier dispatch + tail loop) executes. Constant
    // wrappers pin boundary lengths across the 8/16-lane block edges; they
    // cover the folded shapes and, once the op is lowered, per-block masks.
    // T_SHORT and T_BOOLEAN get dynamic coverage plus spot constants; their
    // widenings share the byte/char machinery.

    static byte[] byteData = fillBytes(1200);
    static short[] shortData = fillShorts(1200);
    static char[] charData = fillChars(1200);
    static int[] intData = fillInts(1200);
    static byte[] utf16Bytes = fillUtf16Bytes(1200);

    static volatile int dynamicType = ArraysSupport.T_BYTE;

    private static byte[] fillBytes(int n) {
        byte[] a = new byte[n];
        for (int i = 0; i < n; i++) {
            a[i] = (byte) (i * 31 + 0x80);
        }
        return a;
    }

    private static short[] fillShorts(int n) {
        short[] a = new short[n];
        for (int i = 0; i < n; i++) {
            a[i] = (short) (i * 131 - 0x3000);
        }
        return a;
    }

    private static char[] fillChars(int n) {
        char[] a = new char[n];
        for (int i = 0; i < n; i++) {
            a[i] = (char) (i * 131 + 0x100);
        }
        return a;
    }

    private static int[] fillInts(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i * 0x9E3779B9;
        }
        return a;
    }

    // UTF-16 payload whose code units exceed 0xFF so the latin1 coder can
    // never apply. Each char occupies two native-endian bytes.
    private static byte[] fillUtf16Bytes(int chars) {
        byte[] a = new byte[chars * 2];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(a).order(ByteOrder.nativeOrder());
        for (int i = 0; i < chars; i++) {
            bb.putChar((char) (i * 131 + 0x100));
        }
        return a;
    }

    // ---- reference implementations (exactly ArraysSupport's private loops) ----

    private static int refSigned(int result, byte[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    private static int refUnsigned(int result, byte[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + (a[i] & 0xff);
        }
        return result;
    }

    private static int refShort(int result, short[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    private static int refChar(int result, char[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    private static int refUtf16(int result, byte[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + U.getCharUnaligned(a, Unsafe.ARRAY_BYTE_BASE_OFFSET + 2L * i);
        }
        return result;
    }

    private static int refInt(int result, int[] a, int from, int length) {
        for (int i = from; i < from + length; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    // ---- wrappers (compileonly target: *::hash*) ----

    static int hashArraysByte(byte[] a) { return Arrays.hashCode(a); }
    static int hashArraysShort(short[] a) { return Arrays.hashCode(a); }
    static int hashArraysChar(char[] a) { return Arrays.hashCode(a); }
    static int hashArraysInt(int[] a) { return Arrays.hashCode(a); }

    static int hashByteDyn(byte[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_BYTE);
    }

    static int hashByteConst0(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 0, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst1(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 1, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst7(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 7, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst8(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_BYTE); }
    static int hashByteConst9(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 9, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst15(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 15, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst16(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 16, 0, ArraysSupport.T_BYTE); }
    static int hashByteConst17(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst31(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 31, 1, ArraysSupport.T_BYTE); }
    static int hashByteConst32(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 32, 0, ArraysSupport.T_BYTE); }
    static int hashByteConst33(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 33, 1, ArraysSupport.T_BYTE); }

    // T_BOOLEAN designates unsigned bytes over a byte[] payload (the Java
    // fallback casts the Object argument to byte[]), matching StringLatin1.
    static int hashBooleanDyn(byte[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_BOOLEAN);
    }

    static int hashBooleanConst8(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_BOOLEAN); }
    static int hashBooleanConst17(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_BOOLEAN); }

    static int hashShortDyn(short[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_SHORT);
    }

    static int hashShortConst8(short[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_SHORT); }
    static int hashShortConst17(short[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_SHORT); }

    static int hashCharDyn(char[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_CHAR);
    }

    static int hashCharConst0(char[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 0, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst1(char[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 1, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst7(char[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 7, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst8(char[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_CHAR); }
    static int hashCharConst9(char[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 9, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst15(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 15, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst16(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 16, 0, ArraysSupport.T_CHAR); }
    static int hashCharConst17(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst31(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 31, 1, ArraysSupport.T_CHAR); }
    static int hashCharConst32(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 32, 0, ArraysSupport.T_CHAR); }
    static int hashCharConst33(char[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 33, 1, ArraysSupport.T_CHAR); }

    static int hashUtf16Dyn(byte[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_CHAR);
    }

    static int hashUtf16Const8(byte[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_CHAR); }
    static int hashUtf16Const17(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_CHAR); }
    static int hashUtf16Const33(byte[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 33, 1, ArraysSupport.T_CHAR); }

    static int hashIntDyn(int[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_INT);
    }

    static int hashIntConst0(int[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 0, 1, ArraysSupport.T_INT); }
    static int hashIntConst1(int[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 1, 1, ArraysSupport.T_INT); }
    static int hashIntConst7(int[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 7, 1, ArraysSupport.T_INT); }
    static int hashIntConst8(int[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 8, 0, ArraysSupport.T_INT); }
    static int hashIntConst9(int[] a)  { return ArraysSupport.vectorizedHashCode(a, 3, 9, 1, ArraysSupport.T_INT); }
    static int hashIntConst15(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 15, 1, ArraysSupport.T_INT); }
    static int hashIntConst16(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 16, 0, ArraysSupport.T_INT); }
    static int hashIntConst17(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 17, 1, ArraysSupport.T_INT); }
    static int hashIntConst31(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 31, 1, ArraysSupport.T_INT); }
    static int hashIntConst32(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 32, 0, ArraysSupport.T_INT); }
    static int hashIntConst33(int[] a) { return ArraysSupport.vectorizedHashCode(a, 3, 33, 1, ArraysSupport.T_INT); }

    // basicType from a non-constant static: must decline the intrinsic and
    // still compute correctly through the Java switch.
    static int hashDynamicType(byte[] a, int from, int length, int init) {
        return ArraysSupport.vectorizedHashCode(a, from, length, init, dynamicType);
    }

    // Constant ranges fold on virtual arrays; a dynamic allocation size
    // cannot establish a fixed-size virtual object.
    static int hashFreshConstant() {
        return Arrays.hashCode(new byte[]{1, 2, 3, 4});
    }

    static int hashFreshSeventeen(int value) {
        return Arrays.hashCode(new int[]{value, 1, 2, 3, 4, 5, 6, 7, 8,
                                         9, 10, 11, 12, 13, 14, 15, 16});
    }

    static int hashFreshEmpty() {
        return Arrays.hashCode(new byte[0]);
    }

    static int hashFreshDynamicLen(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + 0x80);
        }
        return Arrays.hashCode(b);
    }

    static int hashFreshByte22(int value) {
        byte[] a = {
            (byte) value, (byte) (value + 1), (byte) (value + 2), (byte) (value + 3), (byte) (value + 4),
            (byte) (value + 5), (byte) (value + 6), (byte) (value + 7), (byte) (value + 8), (byte) (value + 9),
            (byte) (value + 10), (byte) (value + 11), (byte) (value + 12), (byte) (value + 13), (byte) (value + 14),
            (byte) (value + 15), (byte) (value + 16), (byte) (value + 17), (byte) (value + 18), (byte) (value + 19),
            (byte) (value + 20), (byte) (value + 21)
        };
        return Arrays.hashCode(a);
    }

    static int hashFreshChar22(int value) {
        char[] a = {
            (char) value, (char) (value + 1), (char) (value + 2), (char) (value + 3), (char) (value + 4),
            (char) (value + 5), (char) (value + 6), (char) (value + 7), (char) (value + 8), (char) (value + 9),
            (char) (value + 10), (char) (value + 11), (char) (value + 12), (char) (value + 13), (char) (value + 14),
            (char) (value + 15), (char) (value + 16), (char) (value + 17), (char) (value + 18), (char) (value + 19),
            (char) (value + 20), (char) (value + 21)
        };
        return Arrays.hashCode(a);
    }

    static int hashFreshInt33(int value) {
        int[] a = {
            value, value + 1, value + 2, value + 3, value + 4,
            value + 5, value + 6, value + 7, value + 8, value + 9,
            value + 10, value + 11, value + 12, value + 13, value + 14,
            value + 15, value + 16, value + 17, value + 18, value + 19,
            value + 20, value + 21, value + 22, value + 23, value + 24,
            value + 25, value + 26, value + 27, value + 28, value + 29,
            value + 30, value + 31, value + 32
        };
        return Arrays.hashCode(a);
    }

    static int hashFreshUtf16(int low, int high, int init) {
        byte[] a = {(byte) low, (byte) high};
        return ArraysSupport.vectorizedHashCode(a, 0, 1, init, ArraysSupport.T_CHAR);
    }

    // The ordinary native Unsafe call makes the array escape before hashing.
    static int hashFreshUtf16Packed(int value, int init) {
        byte[] a = new byte[4];
        U.putIntUnaligned(a, Unsafe.ARRAY_BYTE_BASE_OFFSET, value);
        return ArraysSupport.vectorizedHashCode(a, 0, 2, init, ArraysSupport.T_CHAR);
    }

    static int hashFreshDynamicRange(int value, int from, int length, int init) {
        byte[] a = {(byte) value, -1, 127, -128, (byte) (value >>> 8), 5, 7, 9};
        byte[] alias = a;
        a[6] = (byte) (value >>> 16);
        int hash = ArraysSupport.vectorizedHashCode(a, from, length, init, ArraysSupport.T_BYTE);
        // The non-inlined observer reads the replayed cells after the hash
        // forces this fixed-size virtual object to materialize.
        return hash ^ observeMaterialized(a, alias, value);
    }

    static int observeMaterialized(byte[] a, byte[] alias, int value) {
        if (a != alias || a.length != 8 || a[0] != (byte) value
                || a[1] != -1 || a[2] != 127 || a[3] != -128
                || a[4] != (byte) (value >>> 8) || a[5] != 5
                || a[6] != (byte) (value >>> 16) || a[7] != 9) {
            throw new AssertionError("materialized array lost identity or initialized cells");
        }
        alias[7] = (byte) (value >>> 24);
        if (a[7] != (byte) (value >>> 24)) {
            throw new AssertionError("materialized aliases diverged");
        }
        return a[0] + a[4] + a[6] + a[7];
    }

    // A non-positive count folds to the initial value alone even with a
    // dynamic from index: the kernel exits before its basic-type dispatch,
    // so the virtual array never materializes.
    static int hashFreshNegative(int from, int init) {
        byte[] a = {(byte) from, 7, 9};
        return ArraysSupport.vectorizedHashCode(a, from & 7, -3, init, ArraysSupport.T_BYTE);
    }

    static int hashStringDyn(String s) {
        return s.hashCode();
    }

    private static void requireCompiled(String name, Class<?>... parameters) {
        try {
            java.lang.reflect.Method method = TestVectorizedHashCode.class.getDeclaredMethod(name, parameters);
            if (!WB.isMethodCompiled(method, false) || WB.getMethodCompilationLevel(method, false) != 4) {
                throw new AssertionError("missing level-4 compilation of " + method);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void expect(int expected, int actual, String what) {
        if (expected != actual) {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    private static final int[] LENGTHS = java.util.stream.IntStream.concat(
            java.util.stream.IntStream.rangeClosed(0, 129), java.util.stream.IntStream.of(1000)).toArray();
    private static final int[] CONST_LENGTHS = {0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33};

    // Initial values baked into the Const wrappers: 1 for even boundaries to
    // distinguish a dropped initial term, (length & 1) elsewhere; length 0
    // wrappers use 1 so the result is the initial value itself.
    private static int initFor(int length) {
        return length == 0 ? 1 : (length & 1);
    }
    private static final int[] FROMS = {0, 3};
    private static final int[] INITS = {0, 1, Integer.MIN_VALUE, Integer.MAX_VALUE};

    private static void verifyDynamic() {
        requireCompiled("hashDynamicType", byte[].class, int.class, int.class, int.class);
        for (int from : FROMS) {
            for (int init : INITS) {
                for (int length : LENGTHS) {
                    expect(refSigned(init, byteData, from, length),
                           hashByteDyn(byteData, from, length, init), "byte dyn " + from + "/" + length + "/" + init);
                    expect(refUnsigned(init, byteData, from, length),
                           hashBooleanDyn(byteData, from, length, init), "boolean dyn " + from + "/" + length + "/" + init);
                    expect(refShort(init, shortData, from, length),
                           hashShortDyn(shortData, from, length, init), "short dyn " + from + "/" + length + "/" + init);
                    expect(refChar(init, charData, from, length),
                           hashCharDyn(charData, from, length, init), "char dyn " + from + "/" + length + "/" + init);
                    expect(refUtf16(init, utf16Bytes, from, length),
                           hashUtf16Dyn(utf16Bytes, from, length, init), "utf16 dyn " + from + "/" + length + "/" + init);
                    expect(refInt(init, intData, from, length),
                           hashIntDyn(intData, from, length, init), "int dyn " + from + "/" + length + "/" + init);
                    dynamicType = ArraysSupport.T_BYTE;
                    expect(refSigned(init, byteData, from, length),
                           hashDynamicType(byteData, from, length, init), "dynamic signed " + from + "/" + length + "/" + init);
                    dynamicType = ArraysSupport.T_BOOLEAN;
                    expect(refUnsigned(init, byteData, from, length),
                           hashDynamicType(byteData, from, length, init), "dynamic unsigned " + from + "/" + length + "/" + init);
                }
            }
        }
        // Negative length is a no-op returning initialValue, matching the Java
        // fallback's empty loop and C2's signed short-loop fall-through.
        expect(42, hashByteDyn(byteData, 0, -3, 42), "byte negative length");
        expect(42, hashIntDyn(intData, 0, Integer.MIN_VALUE, 42), "int min length");
        requireCompiled("hashDynamicType", byte[].class, int.class, int.class, int.class);
        dynamicType = 6;
        try {
            hashDynamicType(byteData, 0, 8, 1);
            throw new AssertionError("unsupported basicType did not throw");
        } catch (IllegalArgumentException expected) {
        } finally {
            dynamicType = ArraysSupport.T_BYTE;
        }
    }

    private static void verifyConstants() {
        for (int length : CONST_LENGTHS) {
            int init = initFor(length);
            expect(refSigned(init, byteData, 3, length), length == 0 ? hashByteConst0(byteData)
                    : length == 1 ? hashByteConst1(byteData)
                    : length == 7 ? hashByteConst7(byteData)
                    : length == 8 ? hashByteConst8(byteData)
                    : length == 9 ? hashByteConst9(byteData)
                    : length == 15 ? hashByteConst15(byteData)
                    : length == 16 ? hashByteConst16(byteData)
                    : length == 17 ? hashByteConst17(byteData)
                    : length == 31 ? hashByteConst31(byteData)
                    : length == 32 ? hashByteConst32(byteData)
                    : hashByteConst33(byteData), "byte const " + length);
            expect(refChar(init, charData, 3, length), length == 0 ? hashCharConst0(charData)
                    : length == 1 ? hashCharConst1(charData)
                    : length == 7 ? hashCharConst7(charData)
                    : length == 8 ? hashCharConst8(charData)
                    : length == 9 ? hashCharConst9(charData)
                    : length == 15 ? hashCharConst15(charData)
                    : length == 16 ? hashCharConst16(charData)
                    : length == 17 ? hashCharConst17(charData)
                    : length == 31 ? hashCharConst31(charData)
                    : length == 32 ? hashCharConst32(charData)
                    : hashCharConst33(charData), "char const " + length);
            expect(refInt(init, intData, 3, length), length == 0 ? hashIntConst0(intData)
                    : length == 1 ? hashIntConst1(intData)
                    : length == 7 ? hashIntConst7(intData)
                    : length == 8 ? hashIntConst8(intData)
                    : length == 9 ? hashIntConst9(intData)
                    : length == 15 ? hashIntConst15(intData)
                    : length == 16 ? hashIntConst16(intData)
                    : length == 17 ? hashIntConst17(intData)
                    : length == 31 ? hashIntConst31(intData)
                    : length == 32 ? hashIntConst32(intData)
                    : hashIntConst33(intData), "int const " + length);
        }
        expect(refUnsigned(0, byteData, 3, 8), hashBooleanConst8(byteData), "boolean const 8");
        expect(refUnsigned(1, byteData, 3, 17), hashBooleanConst17(byteData), "boolean const 17");
        expect(refShort(0, shortData, 3, 8), hashShortConst8(shortData), "short const 8");
        expect(refShort(1, shortData, 3, 17), hashShortConst17(shortData), "short const 17");
        expect(refUtf16(0, utf16Bytes, 3, 8), hashUtf16Const8(utf16Bytes), "utf16 const 8");
        expect(refUtf16(1, utf16Bytes, 3, 17), hashUtf16Const17(utf16Bytes), "utf16 const 17");
        expect(refUtf16(1, utf16Bytes, 3, 33), hashUtf16Const33(utf16Bytes), "utf16 const 33");
    }

    private static void verifyPublicApis() {
        requireCompiled("hashArraysByte", byte[].class);
        requireCompiled("hashArraysShort", short[].class);
        requireCompiled("hashArraysChar", char[].class);
        requireCompiled("hashArraysInt", int[].class);
        // Arrays.hashCode delegates to vectorizedHashCode with initialValue 1.
        for (int length : LENGTHS) {
            byte[] b = new byte[length];
            char[] c = new char[length];
            short[] s = new short[length];
            int[] ia = new int[length];
            for (int i = 0; i < length; i++) {
                b[i] = (byte) (i * 31 + 0x80);
                c[i] = (char) (i * 131 + 0x100);
                s[i] = (short) (i * 131 - 0x3000);
                ia[i] = i * 0x9E3779B9;
            }
            expect(refSigned(1, b, 0, b.length), hashArraysByte(b), "Arrays.hashCode byte " + length);
            expect(refChar(1, c, 0, c.length), hashArraysChar(c), "Arrays.hashCode char " + length);
            expect(refShort(1, s, 0, s.length), hashArraysShort(s), "Arrays.hashCode short " + length);
            expect(refInt(1, ia, 0, ia.length), hashArraysInt(ia), "Arrays.hashCode int " + length);
        }
        expect(0, hashArraysByte(null), "Arrays.hashCode null byte");
        expect(0, hashArraysShort(null), "Arrays.hashCode null short");
        expect(0, hashArraysChar(null), "Arrays.hashCode null char");
        expect(0, hashArraysInt(null), "Arrays.hashCode null int");
        // String.hashCode exercises T_BOOLEAN (latin1) and T_CHAR-on-byte[]
        // (utf16) with initialValue 0. Fresh copies defeat the cached hash.
        for (int length : CONST_LENGTHS) {
            char[] latin = new char[length];
            char[] wide = new char[length];
            for (int i = 0; i < length; i++) {
                latin[i] = (char) ((i * 31 + 0x80) & 0xFF);
                wide[i] = (char) (i * 131 + 0x100);
            }
            String latin1 = new String(latin);
            String utf16 = new String(wide);
            expect(hashStringDyn(latin1), latinRef(latin1), "String latin1 " + length);
            expect(hashStringDyn(utf16), utf16Ref(utf16), "String utf16 " + length);
        }
    }

    // Charset-independent references: a latin1 String's bytes are the
    // ISO_8859_1 encoding; a UTF16 string's code units are its toCharArray
    // output, so the char-based reference is exact without touching the
    // private native-endian payload.
    private static int latinRef(String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return refUnsigned(0, b, 0, b.length);
    }

    private static int utf16Ref(String s) {
        char[] c = s.toCharArray();
        return refChar(0, c, 0, c.length);
    }

    private static volatile int sink;

    // Warm the compilation roots directly; the oracle and its large matrix
    // remain interpreted and run once after compilation has completed.
    private static void warmup() {
        char[] latin = new char[63];
        char[] wide = new char[63];
        Arrays.fill(latin, '\u00e9');
        Arrays.fill(wide, '\u8123');
        for (int i = 0; i < 2_000; i++) {
            int length = i & 127;
            int h = hashByteDyn(byteData, 3, length, i);
            h ^= hashBooleanDyn(byteData, 3, length, i);
            h ^= hashShortDyn(shortData, 3, length, i);
            h ^= hashCharDyn(charData, 3, length, i);
            h ^= hashUtf16Dyn(utf16Bytes, 3, length, i);
            h ^= hashIntDyn(intData, 3, length, i);
            dynamicType = (i & 1) == 0 ? ArraysSupport.T_BYTE : ArraysSupport.T_BOOLEAN;
            h ^= hashDynamicType(byteData, 3, length, i);
            h ^= hashArraysByte(byteData) ^ hashArraysShort(shortData);
            h ^= hashArraysChar(charData) ^ hashArraysInt(intData);
            h ^= hashFreshDynamicRange(i * 0x9e3779b9, i % 9, 8 - i % 9, i);
            h ^= hashFreshConstant() ^ hashFreshEmpty();
            h ^= hashFreshSeventeen(i) ^ hashFreshDynamicLen(64);
            h ^= hashFreshByte22(i) ^ hashFreshChar22(i) ^ hashFreshInt33(i);
            h ^= hashFreshUtf16(i, i * 17, i) ^ hashFreshUtf16Packed(i * 0x9e3779b9, i);
            h ^= hashFreshNegative(i, i * 31);
            // Construct from chars: copying a String can copy its cached hash.
            h ^= hashStringDyn(new String(latin));
            h ^= hashStringDyn(new String(wide));
            sink = h;
            sink ^= hashByteConst0(byteData);
            sink ^= hashByteConst1(byteData);
            sink ^= hashByteConst7(byteData);
            sink ^= hashByteConst8(byteData);
            sink ^= hashByteConst9(byteData);
            sink ^= hashByteConst15(byteData);
            sink ^= hashByteConst16(byteData);
            sink ^= hashByteConst17(byteData);
            sink ^= hashByteConst31(byteData);
            sink ^= hashByteConst32(byteData);
            sink ^= hashByteConst33(byteData);
            sink ^= hashBooleanConst8(byteData);
            sink ^= hashBooleanConst17(byteData);
            sink ^= hashShortConst8(shortData);
            sink ^= hashShortConst17(shortData);
            sink ^= hashCharConst0(charData);
            sink ^= hashCharConst1(charData);
            sink ^= hashCharConst7(charData);
            sink ^= hashCharConst8(charData);
            sink ^= hashCharConst9(charData);
            sink ^= hashCharConst15(charData);
            sink ^= hashCharConst16(charData);
            sink ^= hashCharConst17(charData);
            sink ^= hashCharConst31(charData);
            sink ^= hashCharConst32(charData);
            sink ^= hashCharConst33(charData);
            sink ^= hashUtf16Const8(utf16Bytes);
            sink ^= hashUtf16Const17(utf16Bytes);
            sink ^= hashUtf16Const33(utf16Bytes);
            sink ^= hashIntConst0(intData);
            sink ^= hashIntConst1(intData);
            sink ^= hashIntConst7(intData);
            sink ^= hashIntConst8(intData);
            sink ^= hashIntConst9(intData);
            sink ^= hashIntConst15(intData);
            sink ^= hashIntConst16(intData);
            sink ^= hashIntConst17(intData);
            sink ^= hashIntConst31(intData);
            sink ^= hashIntConst32(intData);
            sink ^= hashIntConst33(intData);
        }
    }

    public static void main(String[] args) throws Exception {
        warmup();
        verifyDynamic();
        verifyConstants();
        verifyPublicApis();
        for (int value : INITS) {
            int[] a = {value, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            expect(refInt(1, a, 0, a.length), hashFreshSeventeen(value), "fresh seventeen");
            expect(value, hashFreshNegative(value, value), "fresh negative length");
        }
        expect(955331, hashFreshConstant(), "fresh constant");
        expect(1, hashFreshEmpty(), "fresh empty");
        expect(refSigned(1, byteData, 0, 64), hashFreshDynamicLen(64), "fresh dynamic 64");
        verifyMaterialization();
        verifyScalarization();
        System.out.println("HASH-VALIDATION-PASSED");
        if (args.length == 0) {
            verifyIntrinsicLowering();
        }
    }

    private static void verifyScalarization() {
        java.util.Random random = new java.util.Random(0x6a09e667L);
        int[] boundaries = {0, -1, 127, 128, 255, 256, 32767, 32768, 65535, 65536,
                            Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int sample = 0; sample < boundaries.length + 64; sample++) {
            int value = sample < boundaries.length ? boundaries[sample] : random.nextInt();
            byte[] bytes = new byte[22];
            char[] chars = new char[22];
            int[] ints = new int[33];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = value + i;
                if (i < bytes.length) {
                    bytes[i] = (byte) (value + i);
                    chars[i] = (char) (value + i);
                }
            }
            expect(refSigned(1, bytes, 0, bytes.length), hashFreshByte22(value), "fresh byte22 " + value);
            expect(refChar(1, chars, 0, chars.length), hashFreshChar22(value), "fresh char22 " + value);
            expect(refInt(1, ints, 0, ints.length), hashFreshInt33(value), "fresh int33 " + value);
            int high = random.nextInt();
            byte[] utf16 = {(byte) value, (byte) high};
            byte[] packed = java.nio.ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(value).array();
            for (int init : INITS) {
                expect(refUtf16(init, utf16, 0, 1), hashFreshUtf16(value, high, init), "fresh utf16 " + value);
                expect(refUtf16(init, packed, 0, 2), hashFreshUtf16Packed(value, init), "fresh packed utf16 " + value);
            }
        }
    }

    private static void verifyMaterialization() {
        requireCompiled("hashFreshDynamicRange", int.class, int.class, int.class, int.class);
        for (int value : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x817f0080}) {
            byte[] a = {(byte) value, -1, 127, -128, (byte) (value >>> 8), 5,
                        (byte) (value >>> 16), 9};
            int cells = a[0] + a[4] + a[6] + (byte) (value >>> 24);
            for (int from = 0; from <= 8; from++) {
                for (int length = 0; length <= 8 - from; length++) {
                    for (int init : INITS) {
                        expect(refSigned(init, a, from, length) ^ cells,
                               hashFreshDynamicRange(value, from, length, init),
                               "materialization " + value + "/" + from + "/" + length + "/" + init);
                    }
                }
            }
        }
    }

    private static void verifyIntrinsicLowering() throws Exception {
        // Fork A pins UseAVX=2 so only the 256-bit tier is live; fork B pins
        // UseAVX=3 to exercise the 16-lane tier where the host has AVX-512.
        // IgnoreUnrecognizedVMOptions keeps the forks bootable on other archs.
        java.nio.file.Path dirA = java.nio.file.Files.createTempDirectory("jeandle_vhc_avx2");
        jdk.test.lib.process.OutputAnalyzer outA = runForensicsFork(dirA, "-XX:UseAVX=2");
        java.util.List<java.nio.file.Path> dumpsA = optimizedDumps(dirA);
        // The op call is visible in the pre-optimization dump; phase-1
        // lowering has inlined (and deleted) it by the optimized dump. Match
        // a call line, not the bare symbol: every module dump carries the
        // op's definition even where the intrinsic was declined.
        try (java.util.stream.Stream<java.nio.file.Path> all =
                     java.nio.file.Files.walk(dirA)) {
            boolean foundOpCall = all.filter(p -> p.getFileName().toString().endsWith(".ll"))
                                     .anyMatch(TestVectorizedHashCode::dumpCallsOp);
            if (!foundOpCall) {
                throw new AssertionError("compiled IR does not call jeandle.vectorized_hashcode");
            }
        }
        if (!dynDumpContains(dumpsA, "mul <8 x i32>")) {
            throw new AssertionError("UseAVX=2 IR has no 8-lane hash vector multiply");
        }
        if (dynDumpContains(dumpsA, "<16 x i32>")) {
            throw new AssertionError("UseAVX=2 IR unexpectedly retains 16-lane hash IR");
        }
        for (String method : new String[]{"hashShortConst8", "hashCharConst8", "hashUtf16Const8"}) {
            for (java.nio.file.Path dump : requireDumps(dumpsA, method)) {
                String text = dumpText(dump);
                if (!text.contains("mul <4 x i32>") || text.contains("load <8 x i16>")) {
                    throw new AssertionError("short word hash must use narrow vector reads: " + dump);
                }
            }
        }
        // Tier boundaries: 7 stays on the scalar tail, 31 takes the single-
        // accumulator mid tier (its blocks of eight fit three short chains),
        // and 32 enters the grouped vector loop.
        boolean const7HasVector = requireDumps(dumpsA, "hashByteConst7").stream()
                .anyMatch(q -> dumpText(q).contains("<8 x i32>"));
        if (const7HasVector) {
            throw new AssertionError("constant length 7 unexpectedly uses a vector tier");
        }
        for (String boundary : new String[]{"hashByteConst17_", "hashByteConst31_", "hashByteConst32_"}) {
            boolean hasVector = dumpsA.stream()
                    .filter(p -> p.getFileName().toString().contains(boundary))
                    .anyMatch(q -> dumpText(q).contains("mul <8 x i32>"));
            if (!hasVector) {
                throw new AssertionError(boundary + " misses the vector tier");
            }
        }
        // The trailing underscore matters: "hashByteConst1" is a prefix of the
        // Const15/16/17 dumps, which legitimately keep vector IR.
        boolean constSmallHasVector = requireDumps(dumpsA, "hashByteConst1").stream()
                .anyMatch(p -> dumpText(p).contains("<8 x i32>"));
        if (constSmallHasVector) {
            throw new AssertionError("constant length 1 unexpectedly retains vector hash IR");
        }
        // PEA shapes, judged on the expanded (optimized) IR: a constant-length
        // hash over a fresh allocation folds on the virtual array to the
        // exact constant (31*(31*(31*(31*1+1)+2)+3)+4 = 955331); a dynamic
        // length keeps the op, whose expanded 8-lane loop survives.
        boolean freshFolded = dumpsA.stream()
                .filter(p -> p.getFileName().toString().contains("hashFreshConstant_"))
                .anyMatch(p -> dumpText(p).contains("ret i32 955331"));
        if (!freshFolded) {
            throw new AssertionError("constant-length fresh-array hash did not fold to 955331");
        }
        boolean dynamicMaterialized = dumpsA.stream()
                .filter(p -> p.getFileName().toString().contains("hashFreshDynamicLen_"))
                .anyMatch(p -> dumpText(p).contains("mul <8 x i32>"));
        if (!dynamicMaterialized) {
            throw new AssertionError("dynamic-length fresh-array hash lost the vector body");
        }

        for (String type : new String[]{"Byte", "Short", "Char", "Int"}) {
            if (requireDumps(dumpsA, "hashArrays" + type).stream()
                    .noneMatch(p -> dumpText(p).contains("mul <8 x i32>"))) {
                throw new AssertionError("public " + type + " hash wrapper has no vector body");
            }
        }
        requireDumps(dumpsA, "hashDynamicType");
        for (java.nio.file.Path dump : requireDumps(dumpsA, "hashFreshDynamicRange")) {
            String text = dumpText(dump);
            if (!text.contains("%pea.matslot") || !text.contains("store atomic i8")
                    || !text.contains("observeMaterialized")) {
                throw new AssertionError("fixed-array dynamic range has no materialization/replay: " + dump);
            }
        }

        // The coder helpers can be compiled callees rather than inlined.
        // Require each vector body on the actual String caller chain.
        for (String coder : new String[]{"StringLatin1", "StringUTF16"}) {
            boolean stringLowered = stringHashLowered(dumpsA, coder);
            if (!stringLowered) {
                throw new AssertionError("compiled String caller chain has no " + coder + " vector hash body");
            }
        }
        for (String method : new String[]{"hashFreshByte22", "hashFreshChar22", "hashFreshInt33",
                                          "hashFreshUtf16", "hashFreshNegative"}) {
            for (java.nio.file.Path dump : requireDumps(dumpsA, method)) {
                String text = dumpText(dump);
                if (allocates(text) || dumpCallsOp(dump)) {
                    throw new AssertionError(method + " did not eliminate its hash and allocation: " + dump);
                }
            }
        }
        for (java.nio.file.Path dump : requireDumps(dumpsA, "hashFreshUtf16Packed")) {
            String text = dumpText(dump);
            boolean nativeStore = text.lines().anyMatch(line ->
                    (line.contains(" call ") || line.contains(" invoke "))
                    && line.contains("@\"jdk_internal_misc_Unsafe_putInt("));
            if (!allocates(text) || !nativeStore) {
                throw new AssertionError("native packed store lost conservative materialization: " + dump);
            }
        }
        boolean seventeenFolded = freshSeventeenFolded(dumpsA);
        if (!seventeenFolded) {
            throw new AssertionError("fresh seventeen-element hash did not scalarize");
        }

        if (hostHasAvx512()) {
            java.nio.file.Path dirB = java.nio.file.Files.createTempDirectory("jeandle_vhc_avx512");
            runForensicsFork(dirB, "-XX:UseAVX=3");
            java.util.List<java.nio.file.Path> dumpsB = optimizedDumps(dirB);
            if (!dynDumpContains(dumpsB, "mul <16 x i32>")) {
                throw new AssertionError("UseAVX=3 IR has no 16-lane hash vector multiply");
            }
            // AVX-512 groups start at 64 elements. The grouped 8-lane tier
            // serves 32..63; the mid tier also handles shorter inputs and
            // remainders, so 8-lane multiplies remain valid.
        }
    }

    private static boolean stringHashLowered(java.util.List<java.nio.file.Path> dumps, String coder) {
        String widening = coder.equals("StringLatin1") ? "zext <8 x i8>" : "zext <8 x i16>";
        java.util.function.Predicate<java.nio.file.Path> vectorBody = p -> {
            String text = dumpText(p);
            return text.contains(widening) && text.contains("mul <8 x i32>");
        };
        return dumps.stream()
                .filter(p -> p.getFileName().toString().contains("hashStringDyn_"))
                .anyMatch(p -> vectorBody.test(p)
                        || (dumpText(p).contains("java_lang_" + coder + "_hashCode")
                            && dumps.stream()
                                .filter(q -> q.getFileName().toString().contains("java_lang_" + coder + "_hashCode_"))
                                .anyMatch(vectorBody)));
    }

    private static boolean allocates(String text) {
        return text.contains("%tlab_")
                || text.matches("(?s).*\\b(?:call|invoke)\\b[^\\n]*@(?:jeandle\\.new_array|new_array)\\b.*");
    }

    private static boolean freshSeventeenFolded(java.util.List<java.nio.file.Path> dumps) {
        return dumps.stream()
                .filter(p -> p.getFileName().toString().contains("hashFreshSeventeen_"))
                .anyMatch(p -> {
                    String text = dumpText(p);
                    // At this phase retained allocations use TLAB IR and a
                    // statepoint whose runtime target is new_array.
                    return !allocates(text)
                            && text.matches("(?s).*\\bmul i32 [^\\n]*, 1353309697(?:\\n|, !).*")
                            && !text.contains("mul <8 x i32>");
                });
    }

    private static jdk.test.lib.process.OutputAnalyzer runForensicsFork(java.nio.file.Path dumpDirectory,
                                                                        String avxFlag) throws Exception {
        java.util.List<String> command = java.util.List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.util=ALL-UNNAMED",
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:CompileThreshold=1000",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+UseJeandleCompiler", avxFlag,
                "-Xlog:jeandle=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dumpDirectory,
                "-XX:CompileCommand=quiet",
                "-XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestVectorizedHashCode::hash*",
                "-XX:CompileCommand=compileonly,java.lang.String*::hashCode",
                "-XX:CompileCommand=dontinline,compiler.jeandle.intrinsic.TestVectorizedHashCode::observeMaterialized",
                TestVectorizedHashCode.class.getName(), "functional");
        jdk.test.lib.process.OutputAnalyzer output = jdk.test.lib.process.ProcessTools.executeCommand(
                jdk.test.lib.process.ProcessTools.createLimitedTestJavaProcessBuilder(
                        command.toArray(new String[0])));
        return output.shouldHaveExitValue(0)
                     .shouldContain("HASH-VALIDATION-PASSED")
                     .shouldContain("jdk.internal.util.ArraysSupport.vectorizedHashCode")
                     .shouldContain("is parsed as intrinsic");
    }

    // Shape checks read the final optimized IR only: the pre-optimization dump
    // contains the op body verbatim with every basic-type cluster.
    private static java.util.List<java.nio.file.Path> optimizedDumps(java.nio.file.Path dir) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith("_optimized.ll")).toList();
        }
    }

    private static java.util.List<java.nio.file.Path> requireDumps(
            java.util.List<java.nio.file.Path> dumps, String method) {
        String prefix = TestVectorizedHashCode.class.getName().replace('.', '_') + "_" + method + "_";
        java.util.List<java.nio.file.Path> matches = dumps.stream()
                .filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        if (matches.isEmpty()) {
            throw new AssertionError("missing optimized dump for " + method);
        }
        return matches;
    }

    private static boolean dynDumpContains(java.util.List<java.nio.file.Path> dumps, String needle) {
        return dumps.stream()
                .filter(p -> p.getFileName().toString().contains("hashByteDyn"))
                .anyMatch(p -> dumpText(p).contains(needle));
    }

    private static boolean hostHasAvx512() {
        java.nio.file.Path cpuinfo = java.nio.file.Path.of("/proc/cpuinfo");
        try {
            return java.nio.file.Files.exists(cpuinfo)
                    && java.nio.file.Files.readString(cpuinfo).contains("avx512f");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static boolean dumpCallsOp(java.nio.file.Path path) {
        return dumpText(path).matches("(?s).*call[^\\n]*@jeandle\\.vectorized_hashcode\\(.*");
    }

    private static String dumpText(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.readString(path);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
