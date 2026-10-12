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
 * 2 along with this work; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary Test primitive Unsafe compareAndExchange old-value semantics
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.FileInstaller . .
 * @run main/othervm compiler.jeandle.intrinsic.TestUnsafeCompareAndExchange
 */
package compiler.jeandle.intrinsic;

import jdk.internal.misc.Unsafe;
import compiler.jeandle.fileCheck.FileCheck;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestUnsafeCompareAndExchange {
  static final String[] NAMES = {"compareAndExchangeByte", "compareAndExchangeByteAcquire", "compareAndExchangeByteRelease", "compareAndExchangeShort", "compareAndExchangeShortAcquire", "compareAndExchangeShortRelease", "compareAndExchangeInt", "compareAndExchangeIntAcquire", "compareAndExchangeIntRelease", "compareAndExchangeLong", "compareAndExchangeLongAcquire", "compareAndExchangeLongRelease"};
  static final String[] IDS = {"_compareAndExchangeByte", "_compareAndExchangeByteAcquire", "_compareAndExchangeByteRelease", "_compareAndExchangeShort", "_compareAndExchangeShortAcquire", "_compareAndExchangeShortRelease", "_compareAndExchangeInt", "_compareAndExchangeIntAcquire", "_compareAndExchangeIntRelease", "_compareAndExchangeLong", "_compareAndExchangeLongAcquire", "_compareAndExchangeLongRelease"};
  static final Unsafe U = Unsafe.getUnsafe();
  static final long IOFF;
  static final long LOFF;
  static final long BOFF;
  static final long SOFF;
  static {
    try {
      IOFF = U.objectFieldOffset(Box.class.getDeclaredField("i"));
      LOFF = U.objectFieldOffset(Box.class.getDeclaredField("l"));
      BOFF = U.objectFieldOffset(Box.class.getDeclaredField("b"));
      SOFF = U.objectFieldOffset(Box.class.getDeclaredField("s"));
    } catch (Exception e) { throw new ExceptionInInitializerError(e); }
  }
  static class Box { volatile int i; volatile long l; volatile byte b; volatile short s; }

  static int caxInt(Box b, int e, int n) { return U.compareAndExchangeInt(b, IOFF, e, n); }
  static long caxLong(Box b, long e, long n) { return U.compareAndExchangeLong(b, LOFF, e, n); }
  static int caxIntAcquire(Box b, int e, int n) { return U.compareAndExchangeIntAcquire(b, IOFF, e, n); }
  static int caxIntRelease(Box b, int e, int n) { return U.compareAndExchangeIntRelease(b, IOFF, e, n); }
  static long caxLongAcquire(Box b, long e, long n) { return U.compareAndExchangeLongAcquire(b, LOFF, e, n); }
  static long caxLongRelease(Box b, long e, long n) { return U.compareAndExchangeLongRelease(b, LOFF, e, n); }
  static byte caxByte(Box b, byte e, byte n) { return U.compareAndExchangeByte(b, BOFF, e, n); }
  static byte caxByteAcquire(Box b, byte e, byte n) { return U.compareAndExchangeByteAcquire(b, BOFF, e, n); }
  static byte caxByteRelease(Box b, byte e, byte n) { return U.compareAndExchangeByteRelease(b, BOFF, e, n); }
  static short caxShort(Box b, short e, short n) { return U.compareAndExchangeShort(b, SOFF, e, n); }
  static short caxShortAcquire(Box b, short e, short n) { return U.compareAndExchangeShortAcquire(b, SOFF, e, n); }
  static short caxShortRelease(Box b, short e, short n) { return U.compareAndExchangeShortRelease(b, SOFF, e, n); }
  static int caxIntRaw(long p, int e, int n) { return U.compareAndExchangeInt(null, p, e, n); }
  static long caxLongRaw(long p, long e, long n) { return U.compareAndExchangeLong(null, p, e, n); }
  static byte caxByteRaw(long p, byte e, byte n) { return U.compareAndExchangeByte(null, p, e, n); }
  static short caxShortRaw(long p, short e, short n) { return U.compareAndExchangeShort(null, p, e, n); }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("child")) { semantics(); return; }
    runCase("enabled", true, null);
    runCase("control_disabled", false, "-XX:ControlIntrinsic=-" + String.join(",-", IDS));
    runCase("unsafe_ops_disabled", false, "-XX:-InlineUnsafeOps");
    runCase("natives_disabled", false, "-XX:-InlineNatives");
  }

  static void semantics() {
    Box b = new Box();
    for (int i = 0; i < 20_000; i++) {
      if (caxInt(b, i, i + 1) != i) throw new AssertionError("int old");
      if (caxLong(b, i, i + 1) != i) throw new AssertionError("long old");
    }
    if (caxInt(b, -1, 7) != 20_000) throw new AssertionError("int failure old");
    if (caxLong(b, -1, 7) != 20_000) throw new AssertionError("long failure old");
    if (caxIntAcquire(b, 20_000, 20_001) != 20_000 || b.i != 20_001) throw new AssertionError("int acquire");
    if (caxIntRelease(b, 20_001, 20_002) != 20_001 || b.i != 20_002) throw new AssertionError("int release");
    if (caxLongAcquire(b, 20_000, 20_001) != 20_000 || b.l != 20_001) throw new AssertionError("long acquire");
    if (caxLongRelease(b, 20_001, 20_002) != 20_001 || b.l != 20_002) throw new AssertionError("long release");
    for (int i = 0; i < 20_000; i++) {
      byte be = (byte) i, bn = (byte) (i + 1);
      short se = (short) i, sn = (short) (i + 1);
      b.b = be; if (caxByte(b, be, bn) != be || b.b != bn) throw new AssertionError("byte loop");
      b.b = be; if (caxByteAcquire(b, be, bn) != be || b.b != bn) throw new AssertionError("byte acquire loop");
      b.b = be; if (caxByteRelease(b, be, bn) != be || b.b != bn) throw new AssertionError("byte release loop");
      b.s = se; if (caxShort(b, se, sn) != se || b.s != sn) throw new AssertionError("short loop");
      b.s = se; if (caxShortAcquire(b, se, sn) != se || b.s != sn) throw new AssertionError("short acquire loop");
      b.s = se; if (caxShortRelease(b, se, sn) != se || b.s != sn) throw new AssertionError("short release loop");
    }
    b.b = (byte) 0x80;
    if (caxByte(b, (byte) 0x80, (byte) 0x7f) != -128 || b.b != (byte) 0x7f) throw new AssertionError("byte signed success");
    if (caxByte(b, (byte) 0x80, (byte) 1) != 127 || b.b != (byte) 0x7f) throw new AssertionError("byte failure old");
    if (caxByteAcquire(b, (byte) 0x7f, (byte) 2) != 127 || b.b != 2) throw new AssertionError("byte acquire");
    if (caxByteRelease(b, (byte) 2, (byte) 3) != 2 || b.b != 3) throw new AssertionError("byte release");
    b.s = (short) 0x8000;
    if (caxShort(b, (short) 0x8000, (short) 0x7fff) != -32768 || b.s != (short) 0x7fff) throw new AssertionError("short signed success");
    if (caxShort(b, (short) 0x8000, (short) 1) != 32767 || b.s != (short) 0x7fff) throw new AssertionError("short failure old");
    if (caxShortAcquire(b, (short) 0x7fff, (short) 4) != 32767 || b.s != 4) throw new AssertionError("short acquire");
    if (caxShortRelease(b, (short) 4, (short) 5) != 4 || b.s != 5) throw new AssertionError("short release");
    long ip = U.allocateMemory(4), lp = U.allocateMemory(8), bp = U.allocateMemory(1), sp = U.allocateMemory(2);
    try {
      U.putInt(null, ip, 11); U.putLong(null, lp, 22);
      if (caxIntRaw(ip, 11, 33) != 11 || U.getInt(null, ip) != 33) throw new AssertionError("raw int");
      if (caxLongRaw(lp, 22, 44) != 22 || U.getLong(null, lp) != 44) throw new AssertionError("raw long");
      U.putByte(null, bp, (byte) 0x80); U.putShort(null, sp, (short) 0x8000);
      if (caxByteRaw(bp, (byte) 0x80, (byte) 0x7f) != -128 || U.getByte(null, bp) != (byte) 0x7f) throw new AssertionError("raw byte");
      if (caxShortRaw(sp, (short) 0x8000, (short) 0x7fff) != -32768 || U.getShort(null, sp) != (short) 0x7fff) throw new AssertionError("raw short");
    } finally { U.freeMemory(ip); U.freeMemory(lp); U.freeMemory(bp); U.freeMemory(sp); }
    if (caxIntMaybeZero(false)) throw new AssertionError("zero-address false branch");
    System.out.println("TestUnsafeCompareAndExchange PASSED");
  }

  static void runCase(String name, boolean enabled, String option) throws Exception {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    String dump = enabled
        ? Files.createTempDirectory(tmp, "jeandle_cax_" + name).toString() : null;
    String wrapper = TestUnsafeCompareAndExchange.class.getName();
    ArrayList<String> command = new ArrayList<>(List.of(
        "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Dtest.jdk=" + System.getProperty("test.jdk"),
        "-Xbatch", "-Xcomp", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
        "-XX:+UseJeandleCompiler", "-XX:+UnlockDiagnosticVMOptions",
        "-Xlog:jeandle=debug,jit+compilation=debug", "-XX:+CIPrintCompilerName",
        "-XX:CompileCommand=compileonly," + wrapper + "::*",
        wrapper, "child"));
    if (enabled) {
      command.add(command.size() - 2, "-XX:+JeandleDumpIR");
      command.add(command.size() - 2, "-XX:JeandleDumpDirectory=" + dump);
      command.add(command.size() - 2, "-XX:CompileCommand=dontinline," + wrapper + "::*");
    }
    if (option != null) command.add(command.size() - 2, option);
    OutputAnalyzer output = ProcessTools.executeCommand(ProcessTools.createTestJavaProcessBuilder(command));
    output.shouldHaveExitValue(0).shouldContain("TestUnsafeCompareAndExchange PASSED");
    for (String namePart : NAMES) {
      String type = namePart.contains("Byte") ? "byte" : namePart.contains("Short") ? "short"
          : namePart.contains("Long") ? "long" : "int";
      String marker = "Method `virtual j" + type + " jdk.internal.misc.Unsafe." + namePart
          + "(jobject, jlong, j" + type + ", j" + type + ")` is parsed as intrinsic";
      if (enabled) output.shouldContain(marker).shouldContain("is parsed as intrinsic");
      else output.shouldNotContain(marker);
    }
    checkIR(dump, enabled, "caxByte", byte.class, "i8", "seq_cst", "seq_cst", 1);
    checkIR(dump, enabled, "caxByteAcquire", byte.class, "i8", "acquire", "acquire", 1);
    checkIR(dump, enabled, "caxByteRelease", byte.class, "i8", "release", "monotonic", 1);
    checkIR(dump, enabled, "caxShort", short.class, "i16", "seq_cst", "seq_cst", 2);
    checkIR(dump, enabled, "caxShortAcquire", short.class, "i16", "acquire", "acquire", 2);
    checkIR(dump, enabled, "caxShortRelease", short.class, "i16", "release", "monotonic", 2);
    checkIR(dump, enabled, "caxInt", int.class, "i32", "seq_cst", "seq_cst", 4);
    checkIR(dump, enabled, "caxIntAcquire", int.class, "i32", "acquire", "acquire", 4);
    checkIR(dump, enabled, "caxIntRelease", int.class, "i32", "release", "monotonic", 4);
    checkIR(dump, enabled, "caxLong", long.class, "i64", "seq_cst", "seq_cst", 8);
    checkIR(dump, enabled, "caxLongAcquire", long.class, "i64", "acquire", "acquire", 8);
    checkIR(dump, enabled, "caxLongRelease", long.class, "i64", "release", "monotonic", 8);
    if (enabled) {
      FileCheck zero = new FileCheck(dump, TestUnsafeCompareAndExchange.class
          .getDeclaredMethod("caxIntMaybeZero", boolean.class), false);
      zero.checkPattern("invoke hotspotcc .*Unsafe_compareAndExchangeInt");
      zero.checkNotPattern("inttoptr i64 0 to ptr");
    }
  }

  static void checkIR(String dump, boolean enabled, String method, Class<?> type,
                      String irType, String successOrder, String failureOrder,
                      int align) throws Exception {
    if (!enabled) return;
    FileCheck check = new FileCheck(dump, TestUnsafeCompareAndExchange.class
        .getDeclaredMethod(method, Box.class, type, type), false);
    check.checkPattern("cmpxchg ptr addrspace\\(1\\).*" + irType + ".*"
        + successOrder + " " + failureOrder + ", align " + align);
  }

  static boolean caxIntMaybeZero(boolean zero) {
    if (zero) return U.compareAndExchangeInt(null, 0L, 0, 1) == 0;
    return false;
  }
}
