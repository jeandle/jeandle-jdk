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
        FileCheck fileCheckOpt = new FileCheck(currentDir, TestCatch.class.getDeclaredMethod("testCatch"), true);
        fileCheckOpt.check("landingpad token");

        FileCheck fileCheck = new FileCheck(currentDir, TestCatch.class.getDeclaredMethod("testCatch"), false);
        fileCheck.check("bci_2_exception_dispatch_to_bci_52:");
        fileCheck.checkNext("call hotspotcc i32 @jeandle.instanceof");

        fileCheck.check("bci_446_unwind_dest:");
        fileCheck.checkNext("landingpad i64");
        fileCheck.checkNext("cleanup");

        fileCheck.check("call hotspotcc void @install_exceptional_return");

    }

    static boolean testCatch() {
        int catched1 = 0;
        try {
            justThrow1();
        } catch (ArrayIndexOutOfBoundsException e) {
            catched1 = 1;
            for (int i = 0; i < 10; i++) {
                int handler = -i;
                try {
                    justThrow3();
                } catch (RuntimeException e1) {
                    handler = i;
                } catch (Exception e2) {
                    handler = 2 * i;
                } finally {
                    handler *= 10;
                }
                Asserts.assertEquals(handler, 10 * i);
            }
        } catch (RuntimeException e) {
            catched1 = 2;
        } catch (Exception e) {
            catched1 = 3;
        } finally {
            int handler = 1;
            try {
                justThrow1();
            } catch (ArrayIndexOutOfBoundsException e) {
                handler = 2;
            } finally {
                handler *= 10;
            }
            Asserts.assertEquals(handler, 20);
        }

        int catched2 = 0;
        try {
            justThrow2();
        } catch (ArrayIndexOutOfBoundsException e) {
            catched2 = 1;
        } catch (RuntimeException e) {
            catched2 = 2;
        } catch (Exception e) {
            for (int i = 0; i < 10; i++) {
                int handler = -i;
                try {
                    justThrow2();
                } catch (RuntimeException e1) {
                    handler = i;
                } catch (Exception e2) {
                    handler = 2 * i;
                } finally {
                    handler *= 10;
                }
                Asserts.assertEquals(handler, 20 * i);
            }
            catched2 = 3;
        }
        return catched1 == 1 && catched2 == 3;
    }

    static void justThrow1() throws ArrayIndexOutOfBoundsException {
        throw new ArrayIndexOutOfBoundsException("Expected ArrayIndexOutOfBoundsException");
    }

    static void justThrow2() throws Exception {
        throw new Exception("Expected Exception");
    }

    static void justThrow3() throws RuntimeException {
        throw new RuntimeException("Expected RuntimeException");
    }
}
