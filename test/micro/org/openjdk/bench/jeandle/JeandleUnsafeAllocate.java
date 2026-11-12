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
package org.openjdk.bench.jeandle;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Escaping Unsafe allocations with literal and runtime class mirrors.
 * Constructor controls allocate the same classes but include field
 * initialization, which Unsafe.allocateInstance deliberately skips.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"})
public class JeandleUnsafeAllocate {
    private static final Unsafe U = getUnsafe();
    private static final Object FIRST = new Object();
    private static final Object SECOND = new Object();

    private volatile Class<?> primitiveClass;
    private volatile Class<?> referenceClass;
    private volatile Class<?> mixedClass;

    @Setup
    public void setup() throws InstantiationException {
        primitiveClass = Primitive.class;
        referenceClass = Reference.class;
        mixedClass = Mixed.class;

        Primitive primitive = constructPrimitive();
        Primitive primitiveLiteral = allocatePrimitive();
        Primitive primitiveDynamic = (Primitive) allocateDynamicPrimitive();
        checkIdentityAndClass(Primitive.class, primitive, primitiveLiteral, primitiveDynamic);
        if (!primitive.z || primitive.b != 0x5a || primitive.c != 0x4567
                || primitive.s != 12345 || primitive.i != 0x12345678
                || primitive.l != 0x123456789abcdefL || primitive.f != 1.25f
                || primitive.d != -2.5) {
            throw new IllegalStateException("Primitive constructor fields differ");
        }
        checkZeroFields(primitiveLiteral);
        checkZeroFields(primitiveDynamic);

        Reference reference = constructReference();
        Reference referenceLiteral = allocateReference();
        Reference referenceDynamic = (Reference) allocateDynamicReference();
        checkIdentityAndClass(Reference.class, reference, referenceLiteral, referenceDynamic);
        if (reference.a != FIRST || reference.b != SECOND
                || reference.c != FIRST || reference.d != SECOND) {
            throw new IllegalStateException("Reference constructor fields differ");
        }
        checkZeroFields(referenceLiteral);
        checkZeroFields(referenceDynamic);

        Mixed mixed = constructMixed();
        Mixed mixedLiteral = allocateMixed();
        Mixed mixedDynamic = (Mixed) allocateDynamicMixed();
        checkIdentityAndClass(Mixed.class, mixed, mixedLiteral, mixedDynamic);
        if (mixed.i != 0x12345678 || mixed.l != 0x123456789abcdefL
                || mixed.a != FIRST || mixed.b != SECOND) {
            throw new IllegalStateException("Mixed constructor fields differ");
        }
        checkZeroFields(mixedLiteral);
        checkZeroFields(mixedDynamic);
    }

    @Benchmark
    public Primitive allocatePrimitive() throws InstantiationException {
        return (Primitive) U.allocateInstance(Primitive.class);
    }

    @Benchmark
    public Reference allocateReference() throws InstantiationException {
        return (Reference) U.allocateInstance(Reference.class);
    }

    @Benchmark
    public Mixed allocateMixed() throws InstantiationException {
        return (Mixed) U.allocateInstance(Mixed.class);
    }

    @Benchmark
    public Object allocateDynamicPrimitive() throws InstantiationException {
        return U.allocateInstance(primitiveClass);
    }

    @Benchmark
    public Object allocateDynamicReference() throws InstantiationException {
        return U.allocateInstance(referenceClass);
    }

    @Benchmark
    public Object allocateDynamicMixed() throws InstantiationException {
        return U.allocateInstance(mixedClass);
    }

    @Benchmark
    public Primitive constructPrimitive() {
        return new Primitive();
    }

    @Benchmark
    public Reference constructReference() {
        return new Reference();
    }

    @Benchmark
    public Mixed constructMixed() {
        return new Mixed();
    }

    private static void checkIdentityAndClass(Class<?> type, Object constructed,
                                             Object literal, Object dynamic) {
        if (constructed == literal || constructed == dynamic || literal == dynamic
                || constructed.getClass() != type || literal.getClass() != type
                || dynamic.getClass() != type) {
            throw new IllegalStateException("Allocation identity or class differs");
        }
    }

    private static void checkZeroFields(Primitive value) {
        if (value.z || value.b != 0 || value.c != 0 || value.s != 0
                || value.i != 0 || value.l != 0
                || Float.floatToRawIntBits(value.f) != 0
                || Double.doubleToRawLongBits(value.d) != 0) {
            throw new IllegalStateException("Unsafe primitive fields are not zero");
        }
    }

    private static void checkZeroFields(Reference value) {
        if (value.a != null || value.b != null || value.c != null || value.d != null) {
            throw new IllegalStateException("Unsafe reference fields are not null");
        }
    }

    private static void checkZeroFields(Mixed value) {
        if (value.i != 0 || value.l != 0 || value.a != null || value.b != null) {
            throw new IllegalStateException("Unsafe mixed fields are not zero");
        }
    }

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static final class Primitive {
        public boolean z;
        public byte b;
        public char c;
        public short s;
        public int i;
        public long l;
        public float f;
        public double d;

        public Primitive() {
            z = true;
            b = 0x5a;
            c = 0x4567;
            s = 12345;
            i = 0x12345678;
            l = 0x123456789abcdefL;
            f = 1.25f;
            d = -2.5;
        }
    }

    public static final class Reference {
        public Object a;
        public Object b;
        public Object c;
        public Object d;

        public Reference() {
            a = FIRST;
            b = SECOND;
            c = FIRST;
            d = SECOND;
        }
    }

    public static final class Mixed {
        public int i;
        public long l;
        public Object a;
        public Object b;

        public Mixed() {
            i = 0x12345678;
            l = 0x123456789abcdefL;
            a = FIRST;
            b = SECOND;
        }
    }
}
