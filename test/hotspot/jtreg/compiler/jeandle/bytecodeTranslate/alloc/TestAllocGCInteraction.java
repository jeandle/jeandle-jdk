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
 * @summary Test allocation under GC pressure: small heap forces frequent GC during TLAB fast path allocation
 * @library /test/lib
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xmx8m -Xmn4m
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocGCInteraction::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocGCInteraction
 * @run main/othervm -Xcomp -XX:-TieredCompilation -Xmx8m -Xmn4m -XX:+UseSerialGC
 *      -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.alloc.TestAllocGCInteraction::*
 *      -XX:+UseJeandleCompiler compiler.jeandle.bytecodeTranslate.alloc.TestAllocGCInteraction
 */
public class TestAllocGCInteraction {
    // Object large enough to cause frequent GC with small heap
    static class Payload {
        long a, b, c, d, e, f, g, h;
        int tag;

        Payload(int tag) {
            this.tag = tag;
            this.a = tag;
            this.b = tag + 1;
            this.c = tag + 2;
            this.d = tag + 3;
            this.e = tag + 4;
            this.f = tag + 5;
            this.g = tag + 6;
            this.h = tag + 7;
        }

        boolean verify() {
            return tag == (int) a && b == a + 1 && c == a + 2 && d == a + 3
                && e == a + 4 && f == a + 5 && g == a + 6 && h == a + 7;
        }
    }

    public static void main(String[] args) {
        testAllocationSurvivesGC();
        testRapidAllocTriggeringGC();
        System.out.println("TestAllocGCInteraction passed.");
    }

    // Allocate objects, keep some alive across GC, verify they're not corrupted
    static void testAllocationSurvivesGC() {
        Payload[] kept = new Payload[100];
        for (int i = 0; i < 10_000; i++) {
            Payload obj = new Payload(i);
            // Keep every 100th object alive to survive GC
            if (i % 100 == 0) {
                kept[i / 100 % kept.length] = obj;
            }
        }
        // Verify kept objects are still valid after many GC cycles
        for (Payload p : kept) {
            if (p != null) {
                Asserts.assertTrue(p.verify(), "Object corrupted after GC: tag=" + p.tag);
            }
        }
    }

    // Rapid allocation without retention - pure TLAB + GC pressure
    static void testRapidAllocTriggeringGC() {
        long sum = 0;
        for (int i = 0; i < 50_000; i++) {
            Payload obj = new Payload(i);
            sum += obj.tag;
        }
        // Verify computation correctness (sum of 0..49999)
        long expected = 49999L * 50000 / 2;
        Asserts.assertEquals(sum, expected, "Sum mismatch after GC-heavy allocation");
    }
}
