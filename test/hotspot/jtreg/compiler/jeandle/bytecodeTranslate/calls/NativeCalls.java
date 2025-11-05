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

/* @test
 * @bug 8329126
 * @summary check that native methods get compiled
 *
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 *
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:+TieredCompilation compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:-TieredCompilation compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:+TieredCompilation -XX:TieredStopAtLevel=1 compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:+TieredCompilation -XX:TieredStopAtLevel=2 compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:+TieredCompilation -XX:TieredStopAtLevel=3 compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 * @run main/othervm/native -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -Xbatch -XX:-UseOnStackReplacement -XX:+TieredCompilation -XX:TieredStopAtLevel=4 compiler.jeandle.bytecodeTranslate.calls.NativeCalls
 */

package compiler.jeandle.bytecodeTranslate.calls;

import java.lang.reflect.Method;

import jdk.test.whitebox.WhiteBox;

public class NativeCalls {
    static Method emptyStaticNativeMethod;
    static Method callNativeMethod;
    static WhiteBox wb;
    static {
        init();
    }
    static void init() {
        System.loadLibrary("JeandleNativeCalls");
        wb = WhiteBox.getWhiteBox();
        try {
            emptyStaticNativeMethod = NativeCalls.class.getDeclaredMethod("emptyStaticNative");
            callNativeMethod = NativeCalls.class.getDeclaredMethod("callNative");
        } catch (NoSuchMethodException nsme) {
            throw new Error("TEST BUG: can't find test method", nsme);
        }
    }

    native static void emptyStaticNative();

    static void callNative() {
        emptyStaticNative();
    }

    static public void main(String[] args) {
        for (int i = 0; i < 20_000; i++) {
            callNative();
        }
        if (wb.getMethodCompilationLevel(callNativeMethod) > 0) {
            if (!wb.isMethodCompiled(emptyStaticNativeMethod)) {
                throw new Error("TEST BUG: '" + emptyStaticNativeMethod + "' should be compiled");
            }
        }
    }
}
