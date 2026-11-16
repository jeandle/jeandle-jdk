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

package org.openjdk.bench.jeandle;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Clone through a guarded superclass, with exact and rejected controls. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class JeandleGuardedClone {
    private Sub source;
    private Base rejected;

    @Setup
    public void setup() throws CloneNotSupportedException {
        source = new Sub();
        source.value = 37;
        rejected = new Base();
        Base.exact(source);
    }

    @Benchmark
    public int guarded() throws CloneNotSupportedException {
        return Base.guarded(source);
    }

    @Benchmark
    public int exact() throws CloneNotSupportedException {
        return Base.exact(source);
    }

    @Benchmark
    public int rejected() throws CloneNotSupportedException {
        return Base.guarded(rejected);
    }

    public static class Base implements Cloneable {
        int value;

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int guarded(Base source) throws CloneNotSupportedException {
            if (!(source instanceof Sub)) {
                return -1;
            }
            Base copy = (Base) source.clone();
            return copy.value;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public static int exact(Sub source) throws CloneNotSupportedException {
            Base copy = (Base) source.clone();
            return copy.value;
        }
    }

    public static final class Sub extends Base { }
}
