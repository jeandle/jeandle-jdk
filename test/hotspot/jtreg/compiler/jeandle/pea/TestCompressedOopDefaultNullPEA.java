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
 * @summary PEA default compressed-reference nulls are decoded without leaving
 *          an addrspacecast ConstantExpr for instruction selection.
 * @run main/othervm -Xbatch -XX:-TieredCompilation -XX:CompileThreshold=1
 *      -XX:+UseJeandleCompiler -XX:+JeandleDoPEA
 *      -XX:+UseCompressedOops -XX:+UseCompressedClassPointers
 *      -XX:CompileCommand=compileonly,compiler.jeandle.pea.TestCompressedOopDefaultNullPEA$TestWrapper::test
 *      compiler.jeandle.pea.TestCompressedOopDefaultNullPEA
 */

package compiler.jeandle.pea;

public class TestCompressedOopDefaultNullPEA {
    public static class TestWrapper {
        private static final class Holder {
            Object[] values;
        }

        public static int test(int count) {
            Holder holder = new Holder();
            Object[] values = holder.values;
            int result = 1;
            if (values != null) {
                int limit = Math.min(count, values.length);
                for (int i = 0; i < limit; i++) {
                    if (values[i] != null) {
                        result++;
                    }
                }
            }
            return result;
        }
    }

    public static void main(String[] args) {
        int result = 0;
        for (int i = 0; i < 20_000; i++) {
            result = TestWrapper.test(128);
        }
        System.out.println("TestCompressedOopDefaultNullPEA result: " + result);
        if (result != 1) {
            throw new RuntimeException("Unexpected result: " + result);
        }
    }
}
