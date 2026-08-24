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

package org.openjdk.bench.java.lang;

import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import jdk.internal.reflect.Reflection;

import org.openjdk.jmh.infra.Blackhole;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED"
})
public class ClassQueries {
    private static final int LOOP_COUNT = 1_000_000;

    public enum Kind {
        OBJECT,
        INTERFACE,
        OBJECT_ARRAY,
        PRIMITIVE_ARRAY,
        PRIMITIVE,
        VOID,
        HIDDEN,
        CHILD
    }

    @Param({"OBJECT", "INTERFACE", "OBJECT_ARRAY", "PRIMITIVE_ARRAY",
            "PRIMITIVE", "VOID", "HIDDEN", "CHILD"})
    public Kind kind;

    private Class<?> klass;
    private Object object;
    private Class<?> alternateKlass;
    private Object alternateObject;
    private Object castObject;
    private Object alternateCastObject;

    @Setup
    public void setup() throws Exception {
        klass = switch (kind) {
            case OBJECT -> Object.class;
            case INTERFACE -> Runnable.class;
            case OBJECT_ARRAY -> Object[].class;
            case PRIMITIVE_ARRAY -> int[].class;
            case PRIMITIVE -> int.class;
            case VOID -> void.class;
            case HIDDEN -> defineHiddenClass();
            case CHILD -> Child.class;
        };
        object = switch (kind) {
            case OBJECT -> new Object();
            case INTERFACE -> new Object();
            case OBJECT_ARRAY -> new String[0];
            case PRIMITIVE_ARRAY -> new int[0];
            case PRIMITIVE -> Integer.valueOf(1);
            case VOID, HIDDEN -> null;
            case CHILD -> new Child();
        };
        alternateKlass = switch (kind) {
            case OBJECT -> Class.class;
            case INTERFACE -> CharSequence.class;
            case OBJECT_ARRAY -> String[].class;
            case PRIMITIVE_ARRAY -> long[].class;
            case PRIMITIVE -> long.class;
            case VOID -> int.class;
            case HIDDEN -> defineHiddenClass();
            case CHILD -> Parent.class;
        };
        alternateObject = switch (kind) {
            case OBJECT, INTERFACE -> new Object();
            case OBJECT_ARRAY -> new String[0];
            case PRIMITIVE_ARRAY -> new long[0];
            case PRIMITIVE -> Long.valueOf(1);
            case VOID, HIDDEN -> null;
            case CHILD -> new Parent();
        };
        castObject = switch (kind) {
            case OBJECT -> Class.class;
            case INTERFACE -> (Runnable) () -> { };
            case OBJECT_ARRAY -> new String[0];
            case PRIMITIVE_ARRAY -> new int[0];
            case PRIMITIVE, VOID, HIDDEN -> null;
            case CHILD -> new Child();
        };
        alternateCastObject = switch (kind) {
            case OBJECT -> Class.class;
            case INTERFACE -> "";
            case OBJECT_ARRAY -> new String[0];
            case PRIMITIVE_ARRAY -> new long[0];
            case PRIMITIVE, VOID, HIDDEN -> null;
            case CHILD -> new Parent();
        };
    }

    private static Class<?> defineHiddenClass() throws Exception {
        try (InputStream in = ClassQueries.class.getResourceAsStream(
                "ClassQueries$HiddenTemplate.class")) {
            byte[] bytes = Objects.requireNonNull(in, "hidden class bytes").readAllBytes();
            return MethodHandles.lookup().defineHiddenClass(bytes, false).lookupClass();
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isInstance() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass)
                    .isInstance((i & 1) == 0 ? object : alternateObject);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public int getModifiers() {
        int result = 0;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result += ((i & 1) == 0 ? klass : alternateKlass).getModifiers();
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isArray() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass).isArray();
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isPrimitive() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass).isPrimitive();
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isInterface() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass).isInterface();
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isHidden() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass).isHidden();
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void getSuperclass(Blackhole bh) {
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object value = ((i & 1) == 0 ? klass : alternateKlass).getSuperclass();
            if ((i & 5) == 1) v1 = value; else v2 = value;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public int getClassAccessFlags() {
        int result = 0;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result += Reflection.getClassAccessFlags(
                    (i & 1) == 0 ? klass : alternateKlass);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void cast(Blackhole bh) {
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object value = ((i & 1) == 0 ? klass : alternateKlass)
                    .cast((i & 1) == 0 ? castObject : alternateCastObject);
            if ((i & 5) == 1) v1 = value; else v2 = value;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean isAssignableFrom() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= ((i & 1) == 0 ? klass : alternateKlass)
                    .isAssignableFrom(alternateKlass);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean constantIsInstanceHit() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= String.class.isInstance("value");
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean constantIsInstanceMiss() {
        Object value = Integer.valueOf(1);
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= String.class.isInstance(value);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean constantIsInstanceNull() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= String.class.isInstance(null);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void constantCastTyped(Blackhole bh) {
        String value = "value";
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object result = String.class.cast(value);
            if ((i & 5) == 1) v1 = result; else v2 = result;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void constantCastObject(Blackhole bh) {
        Object value = "value";
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object result = String.class.cast(value);
            if ((i & 5) == 1) v1 = result; else v2 = result;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void constantCastNull(Blackhole bh) {
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object value = String.class.cast(null);
            if ((i & 5) == 1) v1 = value; else v2 = value;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public void constantGetSuperclass(Blackhole bh) {
        Object v1 = null, v2 = null;
        for (int i = 0; i < LOOP_COUNT; i++) {
            Object value = String.class.getSuperclass();
            if ((i & 5) == 1) v1 = value; else v2 = value;
        }
        bh.consume(v1); bh.consume(v2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public int constantGetClassAccessFlags() {
        int result = 0;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result += Reflection.getClassAccessFlags(String.class);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(LOOP_COUNT)
    public boolean constantAssignableFrom() {
        boolean result = false;
        for (int i = 0; i < LOOP_COUNT; i++) {
            result ^= Object.class.isAssignableFrom(String.class);
        }
        return result;
    }

    static class Parent {}

    static class Child extends Parent {}

    static final class HiddenTemplate {}
}
