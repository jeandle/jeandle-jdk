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
 * Installed identity hashes and first-use hash installation through both APIs.
 * Fresh-object methods include allocation. Overridden hashCode is a Java
 * dispatch control, while identityHashCode ignores that override.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "-XX:-DoEscapeAnalysis", "-XX:-EliminateAllocations", "-XX:-JeandleDoPEA"})
public class JeandleHashCode {
    private volatile Object prehashed;
    private volatile Object overridden;
    private volatile Object nullReceiver;
    private volatile HashCodeReceiver interfacePrehashed;
    private volatile HashCodeReceiver interfaceOverridden;
    private volatile SpecialHashCode specialPrehashed;

    @Setup
    public void setup() {
        Object plain = new Object();
        int plainHash = plain.hashCode();
        checkIdentityHash(plain);
        if (System.identityHashCode(plain) != plainHash) {
            throw new IllegalStateException("Object hashCode differs from identity hashCode");
        }
        prehashed = plain;

        OverriddenHashCode custom = new OverriddenHashCode(0x13579bdf);
        checkIdentityHash(custom);
        overridden = custom;
        if (objectHashCodeOverride() != 0x13579bdf) {
            throw new IllegalStateException("hashCode override was not dispatched");
        }

        InheritedHashCode inherited = new InheritedHashCode();
        checkIdentityHash(inherited);
        interfacePrehashed = inherited;
        interfaceOverridden = custom;
        if (interfaceHashCodePrehashed() != System.identityHashCode(inherited)
                || interfaceHashCodeOverride() != 0x13579bdf) {
            throw new IllegalStateException("Interface hashCode dispatch differs");
        }

        SpecialHashCode special = new SpecialHashCode();
        int specialHash = special.superHashCode();
        checkIdentityHash(special);
        specialPrehashed = special;
        if (specialHash != System.identityHashCode(special)
                || superHashCodePrehashed() != specialHash) {
            throw new IllegalStateException("Super hashCode differs from identity hashCode");
        }

        nullReceiver = null;
        if (identityHashCodeNull() != 0
                || objectHashCodePrehashed() != identityHashCodePrehashed()
                || identityHashCodeOverride() != System.identityHashCode(custom)) {
            throw new IllegalStateException("Identity hashCode semantics differ");
        }
    }

    @Benchmark
    public int objectHashCodePrehashed() {
        return prehashed.hashCode();
    }

    @Benchmark
    public int identityHashCodePrehashed() {
        return System.identityHashCode(prehashed);
    }

    @Benchmark
    public int objectHashCodeFresh() {
        return new Object().hashCode();
    }

    @Benchmark
    public int identityHashCodeFresh() {
        return System.identityHashCode(new Object());
    }

    @Benchmark
    public int objectHashCodeOverride() {
        return overridden.hashCode();
    }

    @Benchmark
    public int identityHashCodeOverride() {
        return System.identityHashCode(overridden);
    }

    @Benchmark
    public int interfaceHashCodePrehashed() {
        return interfacePrehashed.hashCode();
    }

    @Benchmark
    public int interfaceHashCodeOverride() {
        return interfaceOverridden.hashCode();
    }

    @Benchmark
    public int superHashCodePrehashed() {
        return specialPrehashed.superHashCode();
    }

    @Benchmark
    public int superHashCodeFresh() {
        return new SpecialHashCode().superHashCode();
    }

    @Benchmark
    public int identityHashCodeNull() {
        return System.identityHashCode(nullReceiver);
    }

    private static void checkIdentityHash(Object receiver) {
        int first = System.identityHashCode(receiver);
        if (System.identityHashCode(receiver) != first
                || (!(receiver instanceof OverriddenHashCode)
                    && receiver.hashCode() != first)) {
            throw new IllegalStateException("Identity hashCode is not stable");
        }
    }

    public interface HashCodeReceiver {
        int hashCode();
    }

    public static final class InheritedHashCode implements HashCodeReceiver {
    }

    public static final class SpecialHashCode {
        public int superHashCode() {
            return super.hashCode();
        }
    }

    public static final class OverriddenHashCode implements HashCodeReceiver {
        private final int value;

        OverriddenHashCode(int value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            return value;
        }
    }
}
