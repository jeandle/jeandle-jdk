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

/*
 * @test
 * @summary Clone overrides retain nullable results, side effects, and exception identity
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run driver TestCloneOverrides
 */

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestCloneOverrides {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            runWorkload(args[0]);
            return;
        }
        for (String scenario : List.of("null", "throw", "deopt", "catch")) {
            runCase(scenario, true);
            runCase(scenario, false);
        }
    }

    private static void runCase(String scenario, boolean enabled) throws Exception {
        Path dump = Files.createTempDirectory("clone_override_");
        ArrayList<String> command = new ArrayList<>(List.of(
                "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                "-XX:+CIPrintCompilerName",
                "-Xlog:jeandle=debug,jit+compilation=debug", "-XX:+JeandleDumpIR",
                "-XX:JeandleDumpDirectory=" + dump,
                "-XX:CompileCommand=compileonly," + Base.class.getName() + "::*"));
        if (!enabled) {
            command.add("-XX:ControlIntrinsic=-_clone");
        }
        command.add(TestCloneOverrides.class.getName());
        command.add(scenario);
        OutputAnalyzer output = ProcessTools.executeProcess(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0).shouldContain("CLONE_OVERRIDE_PASS=" + scenario);
        String method = scenario.equals("null") ? "cloneIsNull"
                : scenario.equals("catch") ? "cloneAndCatch" : "doClone";
        output.shouldMatch("Jeandle:.*" + Pattern.quote(Base.class.getName() + "::" + method)
                + " \\([0-9]+ bytes\\)");
        if (enabled) {
            FileCheck raw = new FileCheck(dump.toString(),
                    Base.class.getDeclaredMethod(method, Base.class), false);
            raw.checkPatternAnywhere("invoke.*__jeandle_dynamic_call.*java_lang_Object_clone");
            raw.checkNotPattern("invoke hotspotcc nonnull.*__jeandle_dynamic_call.*java_lang_Object_clone");
            raw.checkPatternAnywhere(
                    "invoke.*__jeandle_dynamic_call.*java_lang_Object_clone.*\\[ \"deopt\"\\(i64 0,");
        }
    }

    private static void runWorkload(String scenario) throws Exception {
        // Load every override before compiling the wrappers. Loading a new
        // subclass later can invalidate CHA and hide the compiled slow path.
        NullClone nullable = new NullClone();
        ThrowClone throwing = new ThrowClone();
        Object marker = new Object();
        Method caller = Base.class.getDeclaredMethod("doClone", Base.class);
        DeoptClone deopting = new DeoptClone(caller, marker);
        Base ordinary = new Base();
        Asserts.assertFalse(Base.cloneIsNull(ordinary), "native clone result");
        Asserts.assertNotNull(Base.doClone(ordinary), "native clone result");
        Asserts.assertNotNull(Base.cloneAndCatch(ordinary), "native clone with handler result");
        Base.finallyCalls = 0;
        WhiteBox wb = WhiteBox.getWhiteBox();
        Asserts.assertTrue(wb.isMethodCompiled(Base.class.getDeclaredMethod("cloneIsNull", Base.class)),
                "nullable-result wrapper must have installed compiled code");
        Asserts.assertTrue(wb.isMethodCompiled(Base.class.getDeclaredMethod("doClone", Base.class)),
                "throwing-override wrapper must have installed compiled code");
        if (scenario.equals("null")) {
            Asserts.assertTrue(Base.cloneIsNull(nullable), "override may return null");
            Asserts.assertEquals(nullable.calls, 1, "null override must execute exactly once");
        } else if (scenario.equals("throw")) {
            try {
                Base.doClone(throwing);
                throw new AssertionError("missing CloneNotSupportedException");
            } catch (CloneNotSupportedException exception) {
                Asserts.assertEquals(throwing.calls, 1, "throwing override must execute exactly once");
                Asserts.assertSame(exception, throwing.firstException,
                        "propagate the original exception object");
            }
        } else if (scenario.equals("deopt")) {
            Asserts.assertSame(Base.doClone(deopting), marker, "override result after caller deoptimization");
            Asserts.assertEquals(deopting.calls, 1, "deoptimization must not repeat the override");
            Asserts.assertFalse(wb.isMethodCompiled(caller), "caller must have been deoptimized");
        } else {
            Asserts.assertTrue(wb.isMethodCompiled(Base.class.getDeclaredMethod("cloneAndCatch", Base.class)),
                    "exception-handler wrapper must have installed compiled code");
            Asserts.assertSame(Base.cloneAndCatch(throwing), throwing.firstException,
                    "compiled handler must receive the original exception");
            Asserts.assertEquals(throwing.calls, 1, "compiled handler must not repeat the override");
            Asserts.assertEquals(Base.finallyCalls, 1, "compiled finally must execute exactly once");
        }
        System.out.println("CLONE_OVERRIDE_PASS=" + scenario);
    }

    static class Base implements Cloneable {
        static int finallyCalls;

        static boolean cloneIsNull(Base source) throws CloneNotSupportedException {
            return source.clone() == null;
        }

        static Object doClone(Base source) throws CloneNotSupportedException {
            return source.clone();
        }

        static Object cloneAndCatch(Base source) {
            try {
                return source.clone();
            } catch (CloneNotSupportedException exception) {
                return exception;
            } finally {
                finallyCalls++;
            }
        }
    }

    static final class NullClone extends Base {
        int calls;

        @Override
        public Object clone() {
            calls++;
            return null;
        }
    }

    static final class ThrowClone extends Base {
        int calls;
        CloneNotSupportedException firstException;

        @Override
        public Object clone() throws CloneNotSupportedException {
            calls++;
            CloneNotSupportedException exception = new CloneNotSupportedException("call " + calls);
            if (calls == 1) {
                firstException = exception;
            }
            throw exception;
        }
    }

    static final class DeoptClone extends Base {
        final Method caller;
        final Object result;
        int calls;

        DeoptClone(Method caller, Object result) {
            this.caller = caller;
            this.result = result;
        }

        @Override
        public Object clone() {
            calls++;
            Asserts.assertTrue(WhiteBox.getWhiteBox().deoptimizeMethod(caller) > 0,
                    "deoptimize the caller while its override is active");
            return result;
        }
    }
}
