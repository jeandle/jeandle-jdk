/*
 * Copyright (c) 2026, Alibaba Group Holding Limited. All Rights Reserved.
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
import org.openjdk.jmh.annotations.*;

/** Multi-object publication and controls that escape at separate observers. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 8, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(3)
@State(Scope.Thread)
public class PEAPublicationBatch {
    @Param({"0", "1", "7", "8"})
    public int publicationRatio;
    private int sequence;

    static final class Pair {
        final int x;
        final int y;
        Pair(int x) { this.x = x; this.y = ~x; }
    }
    public static final class Node {
        int value;
        Node next;
        Object payload;
    }
    private final Object payload = new Object();

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long observe(Pair a, Pair b) {
        return ((long) a.x << 32) ^ (a.y + b.x + b.y);
    }
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long observeFour(Pair a, Pair b, Pair c, Pair d) {
        return observe(a, b) ^ observe(c, d);
    }
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long observeOne(Pair a) { return ((long) a.x << 32) ^ a.y; }

    @Benchmark
    public long twoOwners() {
        int value = sequence++;
        return observe(new Pair(value), new Pair(value + 1));
    }
    @Benchmark
    public long fourOwners() {
        int value = sequence++;
        return observeFour(new Pair(value), new Pair(value + 1),
                new Pair(value + 2), new Pair(value + 3));
    }
    @Benchmark
    public long lockedOwners() {
        int value = sequence++;
        Pair a = new Pair(value);
        Pair b = new Pair(value + 1);
        synchronized (a) {
            synchronized (b) {
                return observe(a, b);
            }
        }
    }
    @Benchmark
    public Node referenceCycle() {
        Node a = new Node();
        Node b = new Node();
        a.value = sequence++;
        b.value = ~a.value;
        a.next = b;
        b.next = a;
        a.payload = payload;
        return a;
    }
    @Benchmark
    public long separateObservers() {
        int value = sequence++;
        Pair a = new Pair(value);
        Pair b = new Pair(value + 1);
        return observeOne(a) ^ observeOne(b);
    }
    @Benchmark
    public long mixedPublication() {
        int value = sequence++;
        Pair a = new Pair(value);
        Pair b = new Pair(value + 1);
        if ((value & 7) < publicationRatio) {
            return observe(a, b);
        }
        return ((long) a.x << 32) ^ (a.y + b.x + b.y);
    }
}
