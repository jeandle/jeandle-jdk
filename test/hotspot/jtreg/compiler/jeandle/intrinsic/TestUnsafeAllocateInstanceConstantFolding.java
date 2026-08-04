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
 */

/*
 * @test
 * @summary Verify Unsafe.allocateInstance constant mirror, Klass metadata,
 *          and initialization queries are folded by Jeandle
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build compiler.jeandle.fileCheck.FileCheck
 * @run driver compiler.jeandle.intrinsic.TestUnsafeAllocateInstanceConstantFolding
 */

package compiler.jeandle.intrinsic;

import compiler.jeandle.fileCheck.FileCheck;
import jdk.internal.misc.Unsafe;
import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class TestUnsafeAllocateInstanceConstantFolding {
    public static void main(String[] args) throws Exception {
        Path dumpPath = Files.createTempDirectory("jeandle_unsafe_allocate_instance");
        String wrapper = TestWrapper.class.getName();

        ArrayList<String> command = new ArrayList<>(List.of(
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-Xbatch", "-XX:-TieredCompilation", "-XX:-BackgroundCompilation",
                "-XX:CompileThreshold=100",
                "-XX:+UseJeandleCompiler", "-Xlog:jeandle=debug,jit+compilation=debug",
                "-XX:+JeandleDumpIR", "-XX:+JeandleRecordVMCallbacks",
                "-XX:JeandleDumpDirectory=" + dumpPath,
                "-XX:CompileCommand=compileonly," + wrapper + "::allocate*",
                wrapper));

        OutputAnalyzer output = ProcessTools.executeCommand(
                ProcessTools.createLimitedTestJavaProcessBuilder(command));
        output.shouldHaveExitValue(0)
              .shouldContain("TestUnsafeAllocateInstanceConstantFolding PASSED")
              .shouldContain("allocateInstance")
              .shouldContain("is parsed as intrinsic");

        Method constantMethod = TestWrapper.class.getMethod("allocateConstant");
        FileCheck unoptimized = new FileCheck(dumpPath.toString(), constantMethod, false);
        unoptimized.checkPattern(
                "define hotspotcc .*TestUnsafeAllocateInstanceConstantFolding\\$TestWrapper_allocateConstant");
        unoptimized.checkPattern("jeandle\\.load_mirror_klass");
        unoptimized.checkPattern("jeandle\\.layout_helper");
        unoptimized.checkPattern("jeandle\\.klass_is_initialized");

        String callbackLog = Files.readString(findLatestDump(
                dumpPath, TestWrapper.class, "allocateConstant", ".cblog"));
        assertMatches(callbackLog, "(?m)^GetMirrorKlass [0-9]+ = [0-9]+$",
                "constant mirror should fold to its represented Klass");
        assertMatches(callbackLog, "(?m)^GetKlassLayoutHelper [0-9]+ = [1-9][0-9]*$",
                "constant Klass layout helper should be queried");
        assertMatches(callbackLog, "(?m)^IsKlassInitialized [0-9]+ = true$",
                "initialized constant Klass should remove the initialization test");
    }

    private static Path findLatestDump(Path directory, Class<?> holder,
                                       String methodName, String suffix) throws Exception {
        String prefix = holder.getName().replace('.', '_') + "_" + methodName;
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(prefix))
                        .filter(path -> path.getFileName().toString().endsWith(suffix))
                        .max(Comparator.comparing(path -> path.getFileName().toString()))
                        .orElseThrow(() -> new AssertionError(
                                "No " + suffix + " dump found for " + prefix));
        }
    }

    private static void assertMatches(String text, String regex, String message) {
        Asserts.assertTrue(Pattern.compile(regex).matcher(text).find(),
                message + "; callback log:\n" + text);
    }

    public static class TestWrapper {
        private static final Unsafe U = Unsafe.getUnsafe();
        private static int constructorCalls;
        private static int lazyClassInitializations;

        public static class Payload {
            int value = 42;

            public Payload() {
                constructorCalls++;
            }
        }

        public abstract static class AbstractPayload { }

        public interface PayloadInterface { }

        public static class LazyPayload {
            static {
                lazyClassInitializations++;
            }
        }

        public static Payload allocateConstant() throws InstantiationException {
            return (Payload) U.allocateInstance(Payload.class);
        }

        public static Object allocateDynamic(Class<?> klass) throws InstantiationException {
            return U.allocateInstance(klass);
        }

        private static void expectInstantiationException(Class<?> klass) {
            try {
                allocateDynamic(klass);
                throw new AssertionError("Expected InstantiationException for " + klass);
            } catch (InstantiationException expected) {
                // Expected.
            }
        }

        public static void main(String[] args) throws Exception {
            // The initialization callback may fold only a class already initialized
            // when allocateConstant is compiled.
            Class.forName(Payload.class.getName(), true, Payload.class.getClassLoader());

            for (int i = 0; i < 20_000; i++) {
                Payload constant = allocateConstant();
                Asserts.assertEquals(constant.value, 0,
                        "Unsafe.allocateInstance must skip field initializers");
                Object dynamic = allocateDynamic(Payload.class);
                Asserts.assertTrue(dynamic instanceof Payload);
            }
            Asserts.assertEquals(constructorCalls, 0,
                    "Unsafe.allocateInstance must not invoke constructors");

            Asserts.assertEquals(lazyClassInitializations, 0,
                    "class literal must not initialize LazyPayload");
            Asserts.assertTrue(allocateDynamic(LazyPayload.class) instanceof LazyPayload);
            Asserts.assertEquals(lazyClassInitializations, 1,
                    "dynamic initialization check must route an uninitialized class to runtime");

            expectInstantiationException(AbstractPayload.class);
            expectInstantiationException(PayloadInterface.class);
            expectInstantiationException(Object[].class);
            expectInstantiationException(int.class);

            System.out.println("TestUnsafeAllocateInstanceConstantFolding PASSED");
        }
    }
}
