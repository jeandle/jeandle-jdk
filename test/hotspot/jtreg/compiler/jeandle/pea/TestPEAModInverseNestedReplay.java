/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  See the LICENSE file for
 * more details.
 */

/*
 * @test
 * @summary PEA must never drop a nested virtual object's field replay: a
 *          virtual object referenced by a surviving materialization must not
 *          be classified NeverEscapes. Drives java.math.MutableBigInteger
 *          .modInverse (whose fixup/mul inlinees hold a loop-rewritten array
 *          field on a holder that escapes mid-loop), the shape that exposed
 *          the lost-replay miscompile as a NullPointerException on a holder's
 *          freshly allocated array field.
 * @library /test/lib
 * @build jdk.test.lib.Asserts
 * @run main/othervm -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *                   -XX:+UseJeandleCompiler -Xbatch
 *                   -XX:CompileCommand=compileonly,java.math.MutableBigInteger::modInverse
 *                   compiler.jeandle.pea.TestPEAModInverseNestedReplay
 */

package compiler.jeandle.pea;

import java.math.BigInteger;
import java.util.Random;

import jdk.test.lib.Asserts;

public class TestPEAModInverseNestedReplay {
    public static void main(String[] args) {
        Random rnd = new Random(12345);
        int count = 0;
        for (int i = 0; i < 3000; i++) {
            BigInteger m = BigInteger.probablePrime(192, rnd);
            BigInteger x = new BigInteger(192, rnd);
            BigInteger inv;
            try {
                inv = x.modInverse(m);
            } catch (ArithmeticException e) {
                continue; // not coprime; no inverse exists.
            }
            Asserts.assertEquals(x.multiply(inv).mod(m), BigInteger.ONE,
                    "x * x.modInverse(m) mod m must be 1 at iteration " + i);
            count++;
        }
        System.out.println("OK " + count);
    }
}
