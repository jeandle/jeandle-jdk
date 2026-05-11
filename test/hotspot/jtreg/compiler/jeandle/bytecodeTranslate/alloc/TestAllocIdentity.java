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

import java.util.IdentityHashMap;

import jdk.test.lib.Asserts;

/**
 * @test
 * @summary Verify each TLAB fast path allocation returns a unique, non-overlapping object reference
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocIdentity::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocIdentity
 */
public class TestAllocIdentity {

    static class Marker {
        int id;
        Marker(int id) { this.id = id; }
    }

    public static void main(String[] args) {
        testUniqueReferences();
        testNoAliasing();
        System.out.println("TestAllocIdentity passed.");
    }

    // Every new allocation must produce a distinct object reference
    static void testUniqueReferences() {
        int count = 5000;
        IdentityHashMap<Marker, Integer> seen = new IdentityHashMap<>();
        for (int i = 0; i < count; i++) {
            Marker m = new Marker(i);
            Asserts.assertNull(seen.put(m, i),
                "Duplicate object reference detected at iteration " + i);
        }
        Asserts.assertEquals(seen.size(), count, "Not all objects are unique");
    }

    // Verify that adjacent allocations do not alias (write to one doesn't affect the other)
    static void testNoAliasing() {
        for (int i = 0; i < 10_000; i++) {
            Marker a = new Marker(100);
            Marker b = new Marker(200);
            // Writing to 'a' must not affect 'b' and vice versa
            a.id = 999;
            Asserts.assertEquals(b.id, 200, "Aliasing detected: writing to 'a' changed 'b'");
            b.id = 888;
            Asserts.assertEquals(a.id, 999, "Aliasing detected: writing to 'b' changed 'a'");
        }
    }
}
