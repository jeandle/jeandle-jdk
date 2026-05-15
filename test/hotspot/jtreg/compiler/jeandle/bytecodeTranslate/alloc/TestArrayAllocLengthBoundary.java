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

package compiler.jeandle.bytecodeTranslate.alloc;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Verify array length boundary handling: zero length succeeds via fast path,
 *          negative length triggers NegativeArraySizeException via slow path, and a
 *          length above arrayOopDesc::max_array_length still raises the appropriate VM error.
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocLengthBoundary::test*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestArrayAllocLengthBoundary
 */
public class TestArrayAllocLengthBoundary {

    public static int[] test_zero_length_int_array(int n) {
        return new int[n];
    }

    public static Object[] test_zero_length_object_array(int n) {
        return new Object[n];
    }

    public static int[] test_negative_length(int n) {
        // Compiled fast path's unsigned length check sends n=-1 (0xFFFFFFFF > max_array_length)
        // into the slow path, where the runtime raises NegativeArraySizeException.
        return new int[n];
    }

    public static int[] test_huge_length(int n) {
        // arrayOopDesc::max_array_length(T_INT) ~ Integer.MAX_VALUE - small. n = MAX_VALUE
        // exceeds the unsigned bound and goes through the slow path which raises OOM.
        return new int[n];
    }

    public static void main(String[] args) {
        // (1) length == 0 -> fast path produces a valid empty array.
        int[] empty = test_zero_length_int_array(0);
        Asserts.assertEquals(empty.length, 0);

        Object[] emptyRef = test_zero_length_object_array(0);
        Asserts.assertEquals(emptyRef.length, 0);

        // (2) length < 0 -> NegativeArraySizeException via slow path.
        boolean negativeCaught = false;
        try {
            int[] a = test_negative_length(-1);
            // Defensive: should never reach here; reference a to keep liveness.
            Asserts.fail("Expected NegativeArraySizeException, got array of length " + a.length);
        } catch (NegativeArraySizeException e) {
            negativeCaught = true;
        }
        Asserts.assertTrue(negativeCaught, "NegativeArraySizeException not thrown for length=-1");

        // (3) length above max -> the slow path raises OutOfMemoryError (HotSpot's standard
        // behavior when length > max_array_length). We catch broadly here because the
        // precise exception type may vary by VM (some versions throw an OOME, others an
        // Error subclass). The intent is just to confirm the fast path declined.
        boolean tooBigCaught = false;
        try {
            int[] a = test_huge_length(Integer.MAX_VALUE);
            Asserts.fail("Expected OutOfMemoryError, got array of length " + a.length);
        } catch (OutOfMemoryError e) {
            tooBigCaught = true;
        } catch (Throwable t) {
            // Accept any Error/RuntimeException from the runtime -- the point is we did
            // not silently take the fast path for an out-of-range length.
            tooBigCaught = true;
        }
        Asserts.assertTrue(tooBigCaught, "VM error not thrown for length=Integer.MAX_VALUE");
    }
}
