/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

package org.openjdk.bench.java.lang.instrument;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the public call path that reaches the _getObjectSize intrinsic. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(value = 3, jvmArgsAppend = {
        "--add-modules=jdk.attach",
        "-Djdk.attach.allowAttachSelf=true",
        "-XX:+EnableDynamicAgentLoading"
})
public class GetObjectSizeIntrinsic {
    @Param({"instance", "byteArray0", "byteArray1024", "objectArray1024"})
    public String shape;

    private Instrumentation instrumentation;
    private Object value;

    @Setup
    public void setup() throws Exception {
        instrumentation = GetObjectSizeAgent.instrumentation();
        value = switch (shape) {
            case "instance" -> new Holder();
            case "byteArray0" -> new byte[0];
            case "byteArray1024" -> new byte[1024];
            case "objectArray1024" -> new Object[1024];
            default -> throw new IllegalArgumentException("unknown shape: " + shape);
        };
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long getObjectSize() {
        return instrumentation.getObjectSize(value);
    }

    static class Holder {
        long value;
        Object reference;
    }
}
