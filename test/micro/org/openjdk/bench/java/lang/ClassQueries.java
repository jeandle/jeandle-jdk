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

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
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
    private int cursor;

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
    public boolean isInstance() {
        boolean first = (cursor++ & 1) == 0;
        return (first ? klass : alternateKlass).isInstance(first ? object : alternateObject);
    }

    @Benchmark
    public int getModifiers() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).getModifiers();
    }

    @Benchmark
    public boolean isArray() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).isArray();
    }

    @Benchmark
    public boolean isPrimitive() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).isPrimitive();
    }

    @Benchmark
    public boolean isInterface() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).isInterface();
    }

    @Benchmark
    public boolean isHidden() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).isHidden();
    }

    @Benchmark
    public Class<?> getSuperclass() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).getSuperclass();
    }

    @Benchmark
    public int getClassAccessFlags() {
        return Reflection.getClassAccessFlags((cursor++ & 1) == 0 ? klass : alternateKlass);
    }

    @Benchmark
    public Object cast() {
        boolean first = (cursor++ & 1) == 0;
        return (first ? klass : alternateKlass).cast(first ? castObject : alternateCastObject);
    }

    @Benchmark
    public boolean isAssignableFrom() {
        return ((cursor++ & 1) == 0 ? klass : alternateKlass).isAssignableFrom(alternateKlass);
    }

    // Constant queries intentionally measure the remaining cost per invocation
    // after folding, including consumption by the JMH harness.
    @Benchmark
    public boolean constantIsInstanceHit() {
        return String.class.isInstance("value");
    }

    @Benchmark
    public boolean constantIsInstanceMiss() {
        return String.class.isInstance(Integer.valueOf(1));
    }

    @Benchmark
    public boolean constantIsInstanceNull() {
        return String.class.isInstance(null);
    }

    @Benchmark
    public Object constantCastTyped() {
        String value = "value";
        return String.class.cast(value);
    }

    @Benchmark
    public Object constantCastObject() {
        Object value = "value";
        return String.class.cast(value);
    }

    @Benchmark
    public Object constantCastNull() {
        return String.class.cast(null);
    }

    @Benchmark
    public Class<?> constantGetSuperclass() {
        return String.class.getSuperclass();
    }

    @Benchmark
    public int constantGetClassAccessFlags() {
        return Reflection.getClassAccessFlags(String.class);
    }

    @Benchmark
    public boolean constantAssignableFrom() {
        return Object.class.isAssignableFrom(String.class);
    }

    @Benchmark
    public boolean constantPrimitiveAssignableFrom() {
        return int.class.isAssignableFrom(long.class);
    }

    @Benchmark
    public boolean phiPrimitiveAssignableFrom() {
        Class<?> receiver = (cursor++ & 1) == 0 ? int.class : long.class;
        return receiver.isAssignableFrom(float.class);
    }

    static class Parent {}

    static class Child extends Parent {}

    static final class HiddenTemplate {}
}
