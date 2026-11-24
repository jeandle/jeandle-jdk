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
 * @summary Escaping intrinsic allocations have non-null normal results with PEA enabled or disabled
 * @library /test/lib /
 * @modules java.base/jdk.internal.misc
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:+JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestIntrinsicAllocationFacts::test*
 *      compiler.jeandle.checks.TestIntrinsicAllocationFacts
 * @run main/othervm -Xcomp -Xbatch -XX:-TieredCompilation -XX:+UseJeandleCompiler
 *      -XX:+JeandleDumpIR -XX:-JeandleDoPEA
 *      -XX:CompileCommand=compileonly,compiler.jeandle.checks.TestIntrinsicAllocationFacts::test*
 *      compiler.jeandle.checks.TestIntrinsicAllocationFacts
 */
package compiler.jeandle.checks;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;

public class TestIntrinsicAllocationFacts {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static volatile Object escaped;
    private static final Object initializedArray = Array.newInstance(int.class, 0);
    static class Payload { }
    abstract static class AbstractPayload { }

    public static int testUnsafe(Class<?> type) throws InstantiationException {
        Object result = UNSAFE.allocateInstance(type);
        escaped = result;
        return result == null ? -1234567 : 1;
    }

    public static int testReflection(Class<?> type, int length) {
        Object result = Array.newInstance(type, length);
        escaped = result;
        return result == null ? -1234567 : 1;
    }

    private static FileCheck ir(Method method, boolean optimized) throws Exception {
        FileCheck check = new FileCheck(System.getProperty("user.dir"), method, optimized);
        check.selectFunction("define.*TestIntrinsicAllocationFacts_" + method.getName() + ".*root");
        return check;
    }

    public static void main(String[] args) throws Exception {
        new Payload();
        Asserts.assertEquals(testUnsafe(Payload.class), 1);
        Asserts.assertTrue(escaped instanceof Payload);
        Asserts.assertEquals(testReflection(int.class, 3), 1);
        Asserts.assertEquals(((int[]) escaped).length, 3);
        Method unsafe = TestIntrinsicAllocationFacts.class.getDeclaredMethod("testUnsafe", Class.class);
        ir(unsafe, false).checkPattern("invoke hotspotcc noundef nonnull.*@jeandle.new_instance");
        ir(unsafe, true).checkPattern("(?:ret i32 1|phi i32.*\\[ 1,)");
        ir(unsafe, true).checkNotPattern("-1234567");
        Method reflection = TestIntrinsicAllocationFacts.class.getDeclaredMethod(
                "testReflection", Class.class, int.class);
        ir(reflection, true).checkPattern("(?:ret i32 1|phi i32.*\\[ 1,)");
        ir(reflection, true).checkNotPattern("-1234567");
        ir(reflection, true).checkPattern("@llvm.experimental.gc.statepoint");
        Asserts.assertEquals(testReflection(Payload.class, 0), 1);
        Asserts.assertEquals(((Object[]) escaped).length, 0);
        Object previous = escaped;
        try {
            testReflection(int.class, -1);
            throw new AssertionError("missing negative length exception");
        } catch (NegativeArraySizeException expected) {
            Asserts.assertEquals(escaped, previous);
        }
        try {
            testReflection(null, 1);
            throw new AssertionError("missing null mirror exception");
        } catch (NullPointerException expected) {
            Asserts.assertEquals(escaped, previous);
        }
        try {
            testUnsafe(AbstractPayload.class);
            throw new AssertionError("missing instantiation exception");
        } catch (InstantiationException expected) {
            Asserts.assertEquals(escaped, previous);
        }
        System.out.println("INTRINSIC_ALLOCATION_FACTS_EXECUTED");
    }
}
