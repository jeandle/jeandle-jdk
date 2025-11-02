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
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox compiler.jeandle.fileCheck.FileCheck
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseSerialGC -XX:MaxTenuringThreshold=2 -XX:CompileCommand=compileonly,TestCardTableBarrier::test*
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+JeandleDumpIR TestCardTableBarrier
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -XX:+UseSerialGC -XX:MaxTenuringThreshold=2 -XX:CompileCommand=compileonly,TestCardTableBarrier::test*
 *      -Xcomp -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+JeandleDumpIR -XX:+UseCondCardMark TestCardTableBarrier
 */

import jdk.test.lib.Asserts;
import jdk.test.whitebox.WhiteBox;

import compiler.jeandle.fileCheck.FileCheck;

public class TestCardTableBarrier {
    private final static WhiteBox wb = WhiteBox.getWhiteBox();

    static class YoungGenObject {
        public int value;

        public YoungGenObject(int value) {
            this.value = value;
        }
    }

    static class OldObject {
        public int value;
        public YoungGenObject youngReference;

        public OldObject(int value) {
            this.value = value;
            this.youngReference = null;
        }
    }

    private static YoungGenObject createYoungGenObject(int value) {
        return new YoungGenObject(value);
    }

    private static void testYGC(OldObject oldObject) {
        oldObject.youngReference = createYoungGenObject(1);

        wb.youngGC();

        Asserts.assertEquals(oldObject.youngReference.value, 1);
        Asserts.assertTrue(wb.isObjectInOldGen(oldObject));
        Asserts.assertFalse(wb.isObjectInOldGen(oldObject.youngReference));
    }

    private static void testFGC(OldObject oldObject) {
        oldObject.youngReference = createYoungGenObject(2);

        wb.fullGC();

        Asserts.assertEquals(oldObject.youngReference.value, 2);
        Asserts.assertTrue(wb.isObjectInOldGen(oldObject));
        Asserts.assertTrue(wb.isObjectInOldGen(oldObject.youngReference));
    }

    public static void main(String[] args) throws Exception {
        OldObject oldObject = new OldObject(0);
        Class.forName("TestCardTableBarrier$YoungGenObject");

        // Trigger young GC to promote the oldObject to the old generation.
        // Number of young GC should be greater than MaxTenuringThreshold.
        wb.youngGC();
        wb.youngGC();
        wb.youngGC();

        Asserts.assertTrue(wb.isObjectInOldGen(oldObject));

        testYGC(oldObject);
        testFGC(oldObject);

        String currentDir = System.getProperty("user.dir");
        {
            if (wb.getVMFlag("UseCondCardMark").toString().equals("false")) {
                FileCheck fileCheck = new FileCheck(currentDir, TestCardTableBarrier.class.getDeclaredMethod("testYGC", OldObject.class), false, 0);
                fileCheck.check("define private hotspotcc void @jeandle.card_table_barrier(ptr addrspace(1) %addr)");
                fileCheck.checkNext("entry:");
                fileCheck.checkNext("%0 = ptrtoint ptr addrspace(1) %addr to i64");
                fileCheck.checkNext("%1 = lshr i64 %0, 9");
                fileCheck.checkNext("%2 = getelementptr inbounds i8, ptr inttoptr");
                fileCheck.checkNext("store atomic i8 0, ptr %2 unordered, align 1");
                fileCheck.checkNext("ret void");
            } else {
                FileCheck fileCheck = new FileCheck(currentDir, TestCardTableBarrier.class.getDeclaredMethod("testYGC", OldObject.class), false, 1);
                fileCheck.check("define private hotspotcc void @jeandle.card_table_barrier(ptr addrspace(1) %addr)");
                fileCheck.checkNext("entry:");
                fileCheck.checkNext("%0 = ptrtoint ptr addrspace(1) %addr to i64");
                fileCheck.checkNext("%1 = lshr i64 %0, 9");
                fileCheck.checkNext("%2 = getelementptr inbounds i8, ptr inttoptr");
                fileCheck.checkNext("%3 = load i8, ptr %2, align 1");
                fileCheck.checkNext("%4 = icmp eq i8 %3, 0");
                fileCheck.checkNext("br i1 %4, label %already_dirty, label %store_dirty");
                fileCheck.checkNext("already_dirty:");
                fileCheck.checkNext("ret void");
                fileCheck.checkNext("store_dirty:");
                fileCheck.checkNext("store atomic i8 0, ptr %2 unordered, align 1");
                fileCheck.checkNext("br label %already_dirty");
            }
        }
    }
}
