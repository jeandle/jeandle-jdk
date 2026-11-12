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

import java.util.concurrent.TimeUnit;

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
 * Escaping clones bracket the eight-field specialization boundary.
 * Field counts include inherited fields; large layouts contain 32 fields.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleInstanceClone {
    private Empty empty;
    private Primitive1 primitive1;
    private Primitive8 primitive8;
    private Primitive9 primitive9;
    private Primitive32 primitive32;
    private Reference1 reference1;
    private Reference8 reference8;
    private Reference9 reference9;
    private Reference32 reference32;
    private Mixed8 mixed8;
    private Mixed9 mixed9;
    private Mixed32 mixed32;
    private Inherited8 inherited8;
    private Inherited9 inherited9;
    private Inherited32 inherited32;

    @Setup
    public void setup() throws ReflectiveOperationException, CloneNotSupportedException {
        empty = new Empty();
        JeandleCopySupport.initializeInstance(empty);
        JeandleCopySupport.checkInstance(empty, empty.clone(), 0);
        primitive1 = new Primitive1();
        JeandleCopySupport.initializeInstance(primitive1);
        JeandleCopySupport.checkInstance(primitive1, primitive1.clone(), 1);
        primitive8 = new Primitive8();
        JeandleCopySupport.initializeInstance(primitive8);
        JeandleCopySupport.checkInstance(primitive8, primitive8.clone(), 8);
        primitive9 = new Primitive9();
        JeandleCopySupport.initializeInstance(primitive9);
        JeandleCopySupport.checkInstance(primitive9, primitive9.clone(), 9);
        primitive32 = new Primitive32();
        JeandleCopySupport.initializeInstance(primitive32);
        JeandleCopySupport.checkInstance(primitive32, primitive32.clone(), 32);
        reference1 = new Reference1();
        JeandleCopySupport.initializeInstance(reference1);
        JeandleCopySupport.checkInstance(reference1, reference1.clone(), 1);
        reference8 = new Reference8();
        JeandleCopySupport.initializeInstance(reference8);
        JeandleCopySupport.checkInstance(reference8, reference8.clone(), 8);
        reference9 = new Reference9();
        JeandleCopySupport.initializeInstance(reference9);
        JeandleCopySupport.checkInstance(reference9, reference9.clone(), 9);
        reference32 = new Reference32();
        JeandleCopySupport.initializeInstance(reference32);
        JeandleCopySupport.checkInstance(reference32, reference32.clone(), 32);
        mixed8 = new Mixed8();
        JeandleCopySupport.initializeInstance(mixed8);
        JeandleCopySupport.checkInstance(mixed8, mixed8.clone(), 8);
        mixed9 = new Mixed9();
        JeandleCopySupport.initializeInstance(mixed9);
        JeandleCopySupport.checkInstance(mixed9, mixed9.clone(), 9);
        mixed32 = new Mixed32();
        JeandleCopySupport.initializeInstance(mixed32);
        JeandleCopySupport.checkInstance(mixed32, mixed32.clone(), 32);
        inherited8 = new Inherited8();
        JeandleCopySupport.initializeInstance(inherited8);
        JeandleCopySupport.checkInstance(inherited8, inherited8.clone(), 8);
        inherited9 = new Inherited9();
        JeandleCopySupport.initializeInstance(inherited9);
        JeandleCopySupport.checkInstance(inherited9, inherited9.clone(), 9);
        inherited32 = new Inherited32();
        JeandleCopySupport.initializeInstance(inherited32);
        JeandleCopySupport.checkInstance(inherited32, inherited32.clone(), 32);
    }

    @Benchmark
    public Empty cloneEmpty() throws CloneNotSupportedException {
        return empty.clone();
    }

    @Benchmark
    public Primitive1 clonePrimitive1() throws CloneNotSupportedException {
        return primitive1.clone();
    }

    @Benchmark
    public Primitive8 clonePrimitive8() throws CloneNotSupportedException {
        return primitive8.clone();
    }

    @Benchmark
    public Primitive9 clonePrimitive9() throws CloneNotSupportedException {
        return primitive9.clone();
    }

    @Benchmark
    public Primitive32 clonePrimitive32() throws CloneNotSupportedException {
        return primitive32.clone();
    }

    @Benchmark
    public Reference1 cloneReference1() throws CloneNotSupportedException {
        return reference1.clone();
    }

    @Benchmark
    public Reference8 cloneReference8() throws CloneNotSupportedException {
        return reference8.clone();
    }

    @Benchmark
    public Reference9 cloneReference9() throws CloneNotSupportedException {
        return reference9.clone();
    }

    @Benchmark
    public Reference32 cloneReference32() throws CloneNotSupportedException {
        return reference32.clone();
    }

    @Benchmark
    public Mixed8 cloneMixed8() throws CloneNotSupportedException {
        return mixed8.clone();
    }

    @Benchmark
    public Mixed9 cloneMixed9() throws CloneNotSupportedException {
        return mixed9.clone();
    }

    @Benchmark
    public Mixed32 cloneMixed32() throws CloneNotSupportedException {
        return mixed32.clone();
    }

    @Benchmark
    public Inherited8 cloneInherited8() throws CloneNotSupportedException {
        return inherited8.clone();
    }

    @Benchmark
    public Inherited9 cloneInherited9() throws CloneNotSupportedException {
        return inherited9.clone();
    }

    @Benchmark
    public Inherited32 cloneInherited32() throws CloneNotSupportedException {
        return inherited32.clone();
    }

    public static class Parent {
        public long parentLong;
        public int parentInt;
        public Object parentReference0;
        public Object parentReference1;
    }

    public static final class Empty implements Cloneable {

        @Override
        public Empty clone() throws CloneNotSupportedException {
            return (Empty) super.clone();
        }
    }

    public static final class Primitive1 implements Cloneable {
        public int i0;

        @Override
        public Primitive1 clone() throws CloneNotSupportedException {
            return (Primitive1) super.clone();
        }
    }

    public static final class Primitive8 implements Cloneable {
        public boolean z0;
        public byte b0;
        public char c0;
        public short s0;
        public int i0;
        public long l0;
        public float f0;
        public double d0;

        @Override
        public Primitive8 clone() throws CloneNotSupportedException {
            return (Primitive8) super.clone();
        }
    }

    public static final class Primitive9 implements Cloneable {
        public boolean z0;
        public byte b0;
        public char c0;
        public short s0;
        public int i0;
        public long l0;
        public float f0;
        public double d0;
        public long l1;

        @Override
        public Primitive9 clone() throws CloneNotSupportedException {
            return (Primitive9) super.clone();
        }
    }

    public static final class Primitive32 implements Cloneable {
        public long l0;
        public long l1;
        public long l2;
        public long l3;
        public long l4;
        public long l5;
        public long l6;
        public long l7;
        public long l8;
        public long l9;
        public long l10;
        public long l11;
        public long l12;
        public long l13;
        public long l14;
        public long l15;
        public long l16;
        public long l17;
        public long l18;
        public long l19;
        public long l20;
        public long l21;
        public long l22;
        public long l23;
        public long l24;
        public long l25;
        public long l26;
        public long l27;
        public long l28;
        public long l29;
        public long l30;
        public long l31;

        @Override
        public Primitive32 clone() throws CloneNotSupportedException {
            return (Primitive32) super.clone();
        }
    }

    public static final class Reference1 implements Cloneable {
        public Object r0;

        @Override
        public Reference1 clone() throws CloneNotSupportedException {
            return (Reference1) super.clone();
        }
    }

    public static final class Reference8 implements Cloneable {
        public Object r0;
        public Object r1;
        public Object r2;
        public Object r3;
        public Object r4;
        public Object r5;
        public Object r6;
        public Object r7;

        @Override
        public Reference8 clone() throws CloneNotSupportedException {
            return (Reference8) super.clone();
        }
    }

    public static final class Reference9 implements Cloneable {
        public Object r0;
        public Object r1;
        public Object r2;
        public Object r3;
        public Object r4;
        public Object r5;
        public Object r6;
        public Object r7;
        public Object r8;

        @Override
        public Reference9 clone() throws CloneNotSupportedException {
            return (Reference9) super.clone();
        }
    }

    public static final class Reference32 implements Cloneable {
        public Object r0;
        public Object r1;
        public Object r2;
        public Object r3;
        public Object r4;
        public Object r5;
        public Object r6;
        public Object r7;
        public Object r8;
        public Object r9;
        public Object r10;
        public Object r11;
        public Object r12;
        public Object r13;
        public Object r14;
        public Object r15;
        public Object r16;
        public Object r17;
        public Object r18;
        public Object r19;
        public Object r20;
        public Object r21;
        public Object r22;
        public Object r23;
        public Object r24;
        public Object r25;
        public Object r26;
        public Object r27;
        public Object r28;
        public Object r29;
        public Object r30;
        public Object r31;

        @Override
        public Reference32 clone() throws CloneNotSupportedException {
            return (Reference32) super.clone();
        }
    }

    public static final class Mixed8 implements Cloneable {
        public long l0;
        public Object r1;
        public long l2;
        public Object r3;
        public long l4;
        public Object r5;
        public long l6;
        public Object r7;

        @Override
        public Mixed8 clone() throws CloneNotSupportedException {
            return (Mixed8) super.clone();
        }
    }

    public static final class Mixed9 implements Cloneable {
        public long l0;
        public Object r1;
        public long l2;
        public Object r3;
        public long l4;
        public Object r5;
        public long l6;
        public Object r7;
        public long l8;

        @Override
        public Mixed9 clone() throws CloneNotSupportedException {
            return (Mixed9) super.clone();
        }
    }

    public static final class Mixed32 implements Cloneable {
        public long l0;
        public Object r1;
        public long l2;
        public Object r3;
        public long l4;
        public Object r5;
        public long l6;
        public Object r7;
        public long l8;
        public Object r9;
        public long l10;
        public Object r11;
        public long l12;
        public Object r13;
        public long l14;
        public Object r15;
        public long l16;
        public Object r17;
        public long l18;
        public Object r19;
        public long l20;
        public Object r21;
        public long l22;
        public Object r23;
        public long l24;
        public Object r25;
        public long l26;
        public Object r27;
        public long l28;
        public Object r29;
        public long l30;
        public Object r31;

        @Override
        public Mixed32 clone() throws CloneNotSupportedException {
            return (Mixed32) super.clone();
        }
    }

    public static final class Inherited8 extends Parent implements Cloneable {
        public int childI0;
        public Object childR1;
        public int childI2;
        public Object childR3;

        @Override
        public Inherited8 clone() throws CloneNotSupportedException {
            return (Inherited8) super.clone();
        }
    }

    public static final class Inherited9 extends Parent implements Cloneable {
        public int childI0;
        public Object childR1;
        public int childI2;
        public Object childR3;
        public int childI4;

        @Override
        public Inherited9 clone() throws CloneNotSupportedException {
            return (Inherited9) super.clone();
        }
    }

    public static final class Inherited32 extends Parent implements Cloneable {
        public int childI0;
        public Object childR1;
        public int childI2;
        public Object childR3;
        public int childI4;
        public Object childR5;
        public int childI6;
        public Object childR7;
        public int childI8;
        public Object childR9;
        public int childI10;
        public Object childR11;
        public int childI12;
        public Object childR13;
        public int childI14;
        public Object childR15;
        public int childI16;
        public Object childR17;
        public int childI18;
        public Object childR19;
        public int childI20;
        public Object childR21;
        public int childI22;
        public Object childR23;
        public int childI24;
        public Object childR25;
        public int childI26;
        public Object childR27;

        @Override
        public Inherited32 clone() throws CloneNotSupportedException {
            return (Inherited32) super.clone();
        }
    }
}

