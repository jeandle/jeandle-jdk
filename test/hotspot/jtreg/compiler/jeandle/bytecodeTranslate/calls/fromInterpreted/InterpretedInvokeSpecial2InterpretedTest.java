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

/*
 * @test
 * @summary check calls from interpreted to interpreted using InvokeSpecial
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile -source 10 -target 10 ../common/InvokeSpecial.java
 *
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:+UseJeandleCompiler
 *    -XX:CompileCommand=compileonly,compiler.jeandle.bytecodeTranslate.calls.common.*::*
 *    -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI -Xbootclasspath/a:.
 *    -XX:CompileCommand=exclude,compiler.jeandle.bytecodeTranslate.calls.common.InvokeSpecial::caller -XX:CompileCommand=exclude,compiler.jeandle.bytecodeTranslate.calls.common.InvokeSpecial::callee compiler.jeandle.bytecodeTranslate.calls.common.InvokeSpecial
 *    -checkCallerCompileLevel 0 -checkCalleeCompileLevel 0
 */
