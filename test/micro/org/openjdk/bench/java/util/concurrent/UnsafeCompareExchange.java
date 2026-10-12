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
package org.openjdk.bench.java.util.concurrent;

import java.util.concurrent.TimeUnit;

import jdk.internal.misc.Unsafe;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED"
})
public class UnsafeCompareExchange {
    private static final int OPERATIONS = 100_000;
    private static final Unsafe U = Unsafe.getUnsafe();

    private static final class Holder {
        int intValue;
        Object referenceValue;
    }

    private static final long INT_OFFSET = U.objectFieldOffset(Holder.class, "intValue");
    private static final long REFERENCE_OFFSET =
            U.objectFieldOffset(Holder.class, "referenceValue");

    private final Holder holder = new Holder();
    private final Object first = new Object();
    private final Object second = new Object();

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndSetInt() {
        int expected = holder.intValue;
        int successes = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int update = expected + 1;
            if (U.compareAndSetInt(holder, INT_OFFSET, expected, update)) {
                expected = update;
                successes++;
            }
        }
        return successes;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeInt() {
        int expected = holder.intValue;
        int checksum = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int old = U.compareAndExchangeInt(holder, INT_OFFSET, expected, expected + 1);
            checksum += old;
            expected = old + 1;
        }
        return checksum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeIntAcquire() {
        int expected = holder.intValue;
        int checksum = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int old = U.compareAndExchangeIntAcquire(holder, INT_OFFSET, expected, expected + 1);
            checksum += old;
            expected = old + 1;
        }
        return checksum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeIntRelease() {
        int expected = holder.intValue;
        int checksum = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int old = U.compareAndExchangeIntRelease(holder, INT_OFFSET, expected, expected + 1);
            checksum += old;
            expected = old + 1;
        }
        return checksum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetIntPlain() {
        return weakCompareAndSetInt(0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetIntAcquire() {
        return weakCompareAndSetInt(1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetIntRelease() {
        return weakCompareAndSetInt(2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetIntVolatile() {
        return weakCompareAndSetInt(3);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private int weakCompareAndSetInt(int mode) {
        int expected = holder.intValue;
        int successes = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            int update = expected + 1;
            boolean success = switch (mode) {
                case 0 -> U.weakCompareAndSetIntPlain(holder, INT_OFFSET, expected, update);
                case 1 -> U.weakCompareAndSetIntAcquire(holder, INT_OFFSET, expected, update);
                case 2 -> U.weakCompareAndSetIntRelease(holder, INT_OFFSET, expected, update);
                default -> U.weakCompareAndSetInt(holder, INT_OFFSET, expected, update);
            };
            if (success) {
                expected = update;
                successes++;
            }
        }
        return successes;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndSetReference() {
        Object expected = holder.referenceValue;
        int successes = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            Object update = expected == first ? second : first;
            if (U.compareAndSetReference(holder, REFERENCE_OFFSET, expected, update)) {
                expected = update;
                successes++;
            }
        }
        return successes;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int getAndSetReference() {
        Object current = holder.referenceValue;
        int checksum = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            Object update = current == first ? second : first;
            Object old = U.getAndSetReference(holder, REFERENCE_OFFSET, update);
            if (old == current) {
                checksum++;
            }
            current = update;
        }
        return checksum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeReference() {
        return compareAndExchangeReference(0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeReferenceAcquire() {
        return compareAndExchangeReference(1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int compareAndExchangeReferenceRelease() {
        return compareAndExchangeReference(2);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private int compareAndExchangeReference(int mode) {
        Object expected = holder.referenceValue;
        int successes = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            Object update = expected == first ? second : first;
            Object old = switch (mode) {
                case 1 -> U.compareAndExchangeReferenceAcquire(
                        holder, REFERENCE_OFFSET, expected, update);
                case 2 -> U.compareAndExchangeReferenceRelease(
                        holder, REFERENCE_OFFSET, expected, update);
                default -> U.compareAndExchangeReference(
                        holder, REFERENCE_OFFSET, expected, update);
            };
            if (old == expected) {
                expected = update;
                successes++;
            } else {
                expected = old;
            }
        }
        return successes;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetReferencePlain() {
        return weakCompareAndSetReference(0);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetReferenceAcquire() {
        return weakCompareAndSetReference(1);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetReferenceRelease() {
        return weakCompareAndSetReference(2);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @OperationsPerInvocation(OPERATIONS)
    public int weakCompareAndSetReferenceVolatile() {
        return weakCompareAndSetReference(3);
    }

    @CompilerControl(CompilerControl.Mode.INLINE)
    private int weakCompareAndSetReference(int mode) {
        Object expected = holder.referenceValue;
        int successes = 0;
        for (int i = 0; i < OPERATIONS; i++) {
            Object update = expected == first ? second : first;
            boolean success = switch (mode) {
                case 0 -> U.weakCompareAndSetReferencePlain(
                        holder, REFERENCE_OFFSET, expected, update);
                case 1 -> U.weakCompareAndSetReferenceAcquire(
                        holder, REFERENCE_OFFSET, expected, update);
                case 2 -> U.weakCompareAndSetReferenceRelease(
                        holder, REFERENCE_OFFSET, expected, update);
                default -> U.weakCompareAndSetReference(
                        holder, REFERENCE_OFFSET, expected, update);
            };
            if (success) {
                expected = update;
                successes++;
            }
        }
        return successes;
    }
}
