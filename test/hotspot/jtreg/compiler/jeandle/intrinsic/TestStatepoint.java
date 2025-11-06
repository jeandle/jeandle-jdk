/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 */

/*
 * @test
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -XX:-TieredCompilation -Xcomp -Xbatch
 * -XX:+UseJeandleCompiler -XX:+JeandleDumpIR
 * -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestStatepoint::test_leaf_sin
 * -XX:CompileCommand=compileonly,compiler.jeandle.intrinsic.TestStatepoint::test_nonleaf_sync
 * compiler.jeandle.intrinsic.TestStatepoint
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import java.lang.reflect.Method;

public class TestStatepoint {

    public static void main(String[] args) throws Exception {
        test_leaf_sin(1.57);
        test_nonleaf_sync(new Object());

        String dumpDir = System.getProperty("user.dir"); // 假设 IR  dump 在 user.dir
        System.out.println("Checking IR files in: " + dumpDir);

        System.out.println("Checking Leaf Function (test_leaf_sin)...");
        Method leafMethod = TestStatepoint.class.getDeclaredMethod("test_leaf_sin", double.class);
        FileCheck leafCheck = new FileCheck(dumpDir, leafMethod, false /* optimized=false */);

        leafCheck.checkPattern("define .* @(StubRoutines_dsin|SharedRuntime_dsin)\\(.*\\) #\\d+.*\"gc-leaf-function\"");

        leafCheck.check("define hotspotcc double @\"compiler_jeandle_intrinsic_TestStatepoint_test_leaf_sin_\\(D\\)D\"");
        leafCheck.checkPattern("call .* @(StubRoutines_dsin|SharedRuntime_dsin)");
        leafCheck.checkNotPattern("invoke .* @(StubRoutines_dsin|SharedRuntime_dsin)");


        System.out.println("Checking Non-Leaf Function (test_nonleaf_sync)...");
        Method nonLeafMethod = TestStatepoint.class.getDeclaredMethod("test_nonleaf_sync", Object.class);
        FileCheck nonLeafCheck = new FileCheck(dumpDir, nonLeafMethod, false /* optimized=false */);

        nonLeafCheck.checkNotPattern("define .* @SharedRuntime_complete_monitor_locking_C\\(.*\\) #\\d+.*\"gc-leaf-function\"");

        nonLeafCheck.check("define hotspotcc void @\"compiler_jeandle_intrinsic_TestStatepoint_test_nonleaf_sync_\\(Ljava_lang_Object_\\)V\"");
        nonLeafCheck.checkPattern("invoke .* @SharedRuntime_complete_monitor_locking_C");
        nonLeafCheck.checkPattern("invoke .* @SharedRuntime_complete_monitor_locking_C.*\"jeandle.statepointID\"");
        nonLeafCheck.checkNotPattern("call .* @SharedRuntime_complete_monitor_locking_C");

        nonLeafCheck.checkPattern("invoke .* @SharedRuntime_complete_monitor_unlocking_C.*\"jeandle.statepointID\"");
        nonLeafCheck.checkNotPattern("call .* @SharedRuntime_complete_monitor_unlocking_C");

        System.out.println("FileCheck validation successful!");
    }

    public static double test_leaf_sin(double d) {
        return Math.sin(d);
    }

    public static void test_nonleaf_sync(Object o) {
        synchronized (o) {
            o.hashCode();
        }
    }
}