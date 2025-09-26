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
 * @requires os.arch=="amd64" | os.arch=="x86_64"
 * @library /test/lib /
 * @run main/othervm -XX:CompileCommand=compileonly,compiler.jeandle.exception.TestCatch::testCatch
 *      -Xcomp -XX:-TieredCompilation -XX:+JeandleDumpIR -XX:+UseJeandleCompiler compiler.jeandle.exception.TestCatch
 */

package compiler.jeandle.exception;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;

public class TestCatch {
    public static void main(String[] args) throws Exception {
        Asserts.assertTrue(testCatch());

        String currentDir = System.getProperty("user.dir");
        FileCheck fileCheck = new FileCheck(currentDir, TestCatch.class.getDeclaredMethod("testCatch"), false);
        fileCheck.check("bci_2_unwind_dest:");
        fileCheck.checkNext("landingpad token");
        fileCheck.checkNext("cleanup");
    }

    static boolean testCatch() {
        boolean catched = false;
        try {
            justThrow();
        } catch (RuntimeException e) {
            catched = true;
        }
        return true;
    }

    static void justThrow() throws RuntimeException {
        throw new RuntimeException("Expected Exception");
    }
}
