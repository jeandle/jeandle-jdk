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

import java.util.Random;
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

/*
 * Lock-coarsening coverage for Jeandle.
 *
 * Verified optimized today:
 *   - straight_line_blocks: eight same-object regions in one basic block.
 *   - straight_chain_calls: the upstream chain_method_calls shape, with its
 *     synchronized helper forced inline (@CompilerControl.INLINE).
 *   - nested_same_object: a synchronized method with sequential redundant
 *     regions; only the outermost acquisition remains.
 *   - adjacent_loop_body: two same-object regions in one loop body, proving
 *     that the transform preserves the loop back edge.
 *   - fully_covered_diamond: same-object regions on both arms of a branch;
 *     the branches are folded into the surrounding critical section.
 *   - exceptional_second_region: a may-throw call in the second region; the
 *     failure edge deoptimizes with the retained monitor still live.
 *   - exceptional_first_region: the same guarded-call shape in the first
 *     region, which requires the first enter and exit to live in different
 *     basic blocks.
 *
 * The transform deliberately does not support several independent monitor
 * enters, multiple normal exits in the first region, loops inside a region,
 * or entries into a region from outside it.  Those shapes are left untouched
 * until a concrete Java use case requires the extra rewrite.
 *
 * Coverage probe (the current transform intentionally leaves this alone):
 *   - early_return_paths: separate lexical regions on distinct return paths.
 *
 * unrelated_objects is the negative control.
 *
 * Run with:
 *   -XX:+UnlockExperimentalVMOptions -XX:+UseJeandleCompiler
 *   -XX:+JeandleLockCoarsening
 * and compare against -XX:-JeandleLockCoarsening.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = {"-XX:+UseJeandleCompiler"})
@State(Scope.Benchmark)
public class JeandleLockCoarseningBenchmark {

  private static final int LOOP_TRIP_COUNT = 8;

  private final Random random = new Random(16384);
  private final Object secondLock = new Object();

  private volatile int defaultValue;
  private volatile int incrementValue;
  private volatile boolean left;

  @Setup
  public void setup() {
    defaultValue = 32 + random.nextInt(64);
    incrementValue = random.nextInt(32);
    left = random.nextBoolean();
  }

  /** Verified: straight-line chain of separate same-object monitor regions. */
  @Benchmark
  public int straight_line_blocks() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    return result;
  }

  /** Verified: the original benchmark shape, with the helper forced inline. */
  @Benchmark
  public int straight_chain_calls() {
    int result = defaultValue << 1;
    result = sum(result);
    result = sum(result);
    result = sum(result);
    result = sum(result);
    result = sum(result);
    result = sum(result);
    result = sum(result);
    result = sum(result);
    return result;
  }

  /** Verified: redundant regions inside a synchronized method. */
  @Benchmark
  public synchronized int nested_same_object() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (this) {
      result += incrementValue;
    }
    return result;
  }

  /** Verified: a fully covered diamond with a monitor region per arm. */
  @Benchmark
  public int fully_covered_diamond() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    if (left) {
      synchronized (this) {
        result += incrementValue;
      }
    } else {
      synchronized (this) {
        result += incrementValue;
      }
    }
    synchronized (this) {
      result += incrementValue;
    }
    return result;
  }

  /** Verified: two monitor regions in one loop body. */
  @Benchmark
  public int adjacent_loop_body() {
    int result = defaultValue;
    for (int i = 0; i < LOOP_TRIP_COUNT; i++) {
      synchronized (this) {
        result += incrementValue;
      }
      synchronized (this) {
        result += incrementValue;
      }
    }
    return result;
  }

  /** Shape probe: monitor regions on separate early-return paths. */
  @Benchmark
  public int early_return_paths() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    if (left) {
      synchronized (this) {
        result += incrementValue;
      }
      return result;
    }
    synchronized (this) {
      result += incrementValue;
    }
    return result;
  }

  /** Verified: the first region contains a may-throw call across blocks. */
  @Benchmark
  public int exceptional_first_region() {
    int result = defaultValue << 1;
    try {
      synchronized (this) {
        result += incrementValue;
        result += maybeThrow();
      }
    } catch (IllegalStateException expected) {
      return -1;
    }
    synchronized (this) {
      result += incrementValue;
    }
    return result;
  }

  /** Verified: the second region contains a may-throw call. */
  @Benchmark
  public int exceptional_second_region() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    try {
      synchronized (this) {
        result += incrementValue;
        result += maybeThrow();
      }
    } catch (IllegalStateException expected) {
      return -1;
    }
    return result;
  }

  /** Negative control: monitor regions on different objects. */
  @Benchmark
  public int unrelated_objects() {
    int result = defaultValue << 1;
    synchronized (this) {
      result += incrementValue;
    }
    synchronized (secondLock) {
      result += incrementValue;
    }
    return result;
  }

  @CompilerControl(CompilerControl.Mode.INLINE)
  private synchronized int sum(int value) {
    return value + incrementValue;
  }

  private static int maybeThrow() {
    if (Thread.currentThread().isInterrupted()) {
      throw new IllegalStateException("benchmark control path");
    }
    return 0;
  }
}
