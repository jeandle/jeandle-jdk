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
 * @library /test/lib
 * @modules jdk.internal.vm.ci/jdk.vm.ci.meta
 *          jdk.internal.vm.ci/jdk.vm.ci.runtime
 * 
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm/native -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xcomp -XX:-TieredCompilation -Xbatch -Xlog:jit+compilation=debug
 *      -XX:CompileCommand=compileonly,TestMakeNotEntrant::loopTest
 *      -XX:CompileCommand=compileonly,TestMakeNotEntrant::add
 *      -XX:CompileCommand=dontinline,TestMakeNotEntrant::add
 *      -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
 *      -XX:+UseJeandleCompiler TestMakeNotEntrant true
 * @run main/othervm/native -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI
 *      -Xcomp -XX:-TieredCompilation -Xbatch -Xlog:jit+compilation=debug
 *      -XX:CompileCommand=compileonly,TestMakeNotEntrant::loopTest
 *      -XX:CompileCommand=compileonly,TestMakeNotEntrant::add
 *      -XX:CompileCommand=dontinline,TestMakeNotEntrant::add
 *      -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
 *      -XX:-UseJeandleCompiler TestMakeNotEntrant true
 */

import java.lang.management.RuntimeMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.runtime.JVMCI;

public class TestMakeNotEntrant {
    public final static WhiteBox wb = WhiteBox.getWhiteBox();
    public final static int threadCount = 3;
    public final static int makeNotEntrantCount = 3;
    public static volatile boolean finish = false;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("true")) {
            List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
            List<String> cmdLine = new ArrayList<>();
            cmdLine.addAll(jvmArgs);
            cmdLine.add("TestMakeNotEntrant");
            ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmdLine);
            OutputAnalyzer output = new OutputAnalyzer(pb.start());
            output.shouldHaveExitValue(0);

            String message = "TestMakeNotEntrant::add (4 bytes)   made not entrant";
            checkOccurrence(output.getOutput(), message, makeNotEntrantCount);
            return;
        }

        test();
    }

    public static void test() throws Exception {
        for (int i = 0; i <= threadCount; i++) {
            runThread();
        }

        Method m = getMethod("add", int.class, int.class);
        int count = makeNotEntrantCount;
        while(count > 0) {
            if (wb.isMethodCompiled(m)) {
                makeNotEntrant(m);
                count--;
            }
        }
        Thread.sleep(100);
        finish = true;
    }

    public static Method getMethod(String methodName, Class<?>... parameterTypes) throws Exception {
        return TestMakeNotEntrant.class.getDeclaredMethod(methodName, parameterTypes);
    }

    public static void makeNotEntrant(Method m) {
        MetaAccessProvider metaAccess = JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
        metaAccess.lookupJavaMethod(m).reprofile();
    }

    public static void checkOccurrence(String output, String str, int time) {
        Pattern pattern = Pattern.compile(Pattern.quote(str));
        Matcher matcher = pattern.matcher(output);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        Asserts.assertEquals(count, time,
                "The string \"" + str + "\" should appear " + time + " times, but appears " + count + " times.");
    }

    public static void runThread() {
        Thread loopThread = new Thread(() -> {
            loopTest();
        });
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public static void loopTest() {
        while (!finish) {
            /* patching verified_entry without reserved 5-byte header in x86_64, the following cases will occur.
             * 1、SIGILL: The exit value of current process is not zero, can be observed.
             * 2、SIGSEGV: The exit value of current process is not zero, can be observed.
             * 3、Nothing happened but hurt header code, cannot be directly observed.
             *    The compiled code of Method "TestMakeNotEntrant::add" is
             *      0x0000:   push   %rbp
             *      0x0001:   lea    (%rsi,%rdx,1),%eax
             *      0x0004:   pop    %rbp
             *      0x0005:   ret
             *    so, the instruction "lea    (%rsi,%rdx,1) will definitely be modified when patching verified_entry,
             *    and we can check the eax register to determine whether this instruction has been corrupted.
             *    However, a single check is not sufficient because no other instructions in this loop will modify the eax register.
             *    Therefore, after the first check, we change the return value and check it again.
             */
            if (add(1, 2) != 3) {
                Asserts.fail();
            }
            if (add(2, 2) != 4) {
                Asserts.fail();
            }
        }
    }

    public static int add(int a, int b) {
        return a + b;
    }
}
