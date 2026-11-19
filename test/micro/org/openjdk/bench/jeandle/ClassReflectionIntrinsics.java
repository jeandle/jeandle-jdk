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

import java.lang.invoke.MethodHandles;
import java.util.concurrent.TimeUnit;
import jdk.internal.reflect.Reflection;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED")
@State(Scope.Thread)
public class ClassReflectionIntrinsics {
    interface Face { }
    static class Base { }
    static final class Value extends Base implements Face {
        int number;
        Value(int number) { this.number = number; }
    }

    @State(Scope.Thread)
    public static class Inputs {
        @Param({"mixed", "monomorphic"})
        public String profile;
        public Class<?>[] targets;
        public Class<?>[] sources;
        public Object[] values;
        public int index;
        public Class<?> allocationTarget;
        public Object escaped;

        @Setup(Level.Trial)
        public void setup() {
            targets = profile.equals("mixed")
                    ? new Class<?>[]{Object.class, String.class, int.class, int[].class,
                            Face.class, String[][].class, Base.class, void.class}
                    : new Class<?>[]{String.class, String.class, String.class, String.class,
                            String.class, String.class, String.class, String.class};
            sources = new Class<?>[]{String.class, Object.class, int.class, int[].class,
                    Value.class, Object[][].class, Value.class, boolean.class};
            values = new Object[]{"value", new Object(), null, new int[4], new Value(5),
                    new String[2][1], Integer.valueOf(7), null};
            allocationTarget = Face.class;
            if (Reflection.getClassAccessFlags(int.class) != 1041
                    || int.class.cast(null) != null || !Face.class.isInstance(new Value(0))) {
                throw new AssertionError("Class query setup");
            }
        }
        public int next() { return index++ & 7; }
    }

    @Benchmark public int modifiers(Inputs data) {
        return data.targets[data.next()].getModifiers();
    }
    @Benchmark public int accessFlags(Inputs data) {
        return Reflection.getClassAccessFlags(data.targets[data.next()]);
    }
    @Benchmark public boolean isInterface(Inputs data) {
        return data.targets[data.next()].isInterface();
    }
    @Benchmark public boolean isArray(Inputs data) {
        return data.targets[data.next()].isArray();
    }
    @Benchmark public boolean isPrimitive(Inputs data) {
        return data.targets[data.next()].isPrimitive();
    }
    @Benchmark public boolean isHidden(Inputs data) {
        return data.targets[data.next()].isHidden();
    }
    @Benchmark public Class<?> superclass(Inputs data) {
        return data.targets[data.next()].getSuperclass();
    }
    @Benchmark public boolean isInstance(Inputs data) {
        int i = data.next();
        return data.targets[i].isInstance(data.values[i]);
    }
    @Benchmark public boolean isAssignableFrom(Inputs data) {
        int i = data.next();
        return data.targets[i].isAssignableFrom(data.sources[i]);
    }
    @Benchmark public Object castSuccess(Inputs data) {
        return Base.class.cast(data.values[4]);
    }
    @Benchmark public Object castFailure(Inputs data) {
        try {
            return String.class.cast(data.values[4]);
        } catch (ClassCastException expected) {
            return expected.getClass();
        }
    }
    @Benchmark public int mixedQueries(Inputs data) {
        Class<?> target = data.targets[data.next()];
        return target.getModifiers() ^ Reflection.getClassAccessFlags(target)
                ^ (target.isArray() ? 1 : 0) ^ (target.isInterface() ? 2 : 0)
                ^ (target.isPrimitive() ? 4 : 0) ^ (target.isHidden() ? 8 : 0)
                ^ (target.getSuperclass() == Object.class ? 16 : 0);
    }
    @Benchmark public boolean constantArray() { return String[][].class.isArray(); }
    @Benchmark public int constantModifiers() { return Value.class.getModifiers(); }
    @Benchmark public boolean constantAssignable() { return Base.class.isAssignableFrom(Value.class); }
    @Benchmark public Class<?> constantSuperclass() { return String[].class.getSuperclass(); }
    @Benchmark public boolean typedInstance(Inputs data) {
        return CharSequence.class.isInstance((String) data.values[0]);
    }
    @Benchmark public boolean virtualDynamic(Inputs data) {
        return data.allocationTarget.isInstance(new Value(data.index));
    }
    @Benchmark public Object escapingDynamic(Inputs data) {
        Value value = new Value(data.index);
        data.escaped = value;
        return data.allocationTarget.cast(value);
    }
    @Benchmark public Class<?> callerFold() { return MethodHandles.lookup().lookupClass(); }
    @Benchmark
    @Fork(value = 3, jvmArgsAppend = {
            "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
            "-XX:CompileCommand=dontinline,java.lang.invoke.MethodHandles::lookup"})
    public Class<?> callerFallback() { return lookupOutsideRoot(); }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public static Class<?> lookupOutsideRoot() {
        return MethodHandles.lookup().lookupClass();
    }
}
