/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

/**
 * @test
 * @summary Support volatile field access
 *  issue: https://github.com/jeandle/jeandle-jdk/issues/28
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run main/othervm -Xcomp -XX:-TieredCompilation -XX:CompileCommand=compileonly,TestVolatile::test
 *      -XX:+UseJeandleCompiler -XX:+JeandleDumpIR TestVolatile
 */

import jdk.test.lib.Asserts;

import compiler.jeandle.fileCheck.FileCheck;

public class TestVolatile {
    private static volatile boolean flag = false;
    private static int counter = 0;

    public static void main(String[] args) throws Exception {
        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 1000000; i++) {
                while (flag) { }
                counter++;
                flag = true;
            }
        });

        thread1.start();
        test();

        thread1.join();

        Asserts.assertEquals(counter, 2000000);

        String currentDir = System.getProperty("user.dir");
        {
            FileCheck fileCheck = new FileCheck(currentDir,
                                                TestVolatile.class.getDeclaredMethod("test"),
                                                false);
            fileCheck.check("define hotspotcc void @\"TestVolatile_test");
            fileCheck.checkPattern("load atomic i8, ptr addrspace\\(1\\) %[0-9]+ seq_cst, align 1");
            fileCheck.checkPattern("store atomic i32 %[0-9]+, ptr addrspace\\(1\\) %[0-9]+ unordered, align 4");
            fileCheck.checkPattern("store atomic i8 0, ptr addrspace\\(1\\) %[0-9]+ seq_cst, align 1");
        }
    }

    private static void test() {
        for (int i = 0; i < 1000000; i++) {
            while (!flag) { }
            counter++;
            flag = false;
        }
    }
}
