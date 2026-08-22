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
 */

/*
 * @test
 * @summary Stress raw Klass constants in Class intrinsics across class unloading
 * @requires os.arch=="amd64" | os.arch=="x86_64" | os.arch=="aarch64"
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UnlockDiagnosticVMOptions
 *      -XX:+WhiteBoxAPI -XX:+ClassUnloading -XX:-BackgroundCompilation
 *      -XX:-TieredCompilation -XX:+UseJeandleCompiler -XX:+InlineClassNatives
 *      compiler.jeandle.intrinsic.TestClassConstantUnloading
 */

package compiler.jeandle.intrinsic;

import compiler.whitebox.CompilerWhiteBoxTest;
import jdk.test.whitebox.WhiteBox;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

public class TestClassConstantUnloading {
    private static final WhiteBox WB = WhiteBox.getWhiteBox();
    private static final String TARGET_NAME =
            "compiler.jeandle.intrinsic.TestClassConstantUnloading$UnloadableTarget";
    private static final String TARGET_RESOURCE =
            "TestClassConstantUnloading$UnloadableTarget.class";
    private static final int STRESS_ROUNDS = 10;

    public static void main(String[] args) throws Exception {
        byte[] targetBytes = readTargetBytes();
        for (int i = 0; i < STRESS_ROUNDS; i++) {
            awaitCustomClassUnloading(runCustomLoaderRound(targetBytes), i);
            awaitHiddenClassUnloading(runHiddenClassRound(targetBytes), i);
        }
    }

    private static byte[] readTargetBytes() throws IOException {
        try (InputStream in = TestClassConstantUnloading.class.getResourceAsStream(
                TARGET_RESOURCE)) {
            return Objects.requireNonNull(in, TARGET_RESOURCE).readAllBytes();
        }
    }

    private static CustomReferences runCustomLoaderRound(byte[] targetBytes)
            throws Exception {
        TargetLoader loader = new TargetLoader(targetBytes);
        Class<?> target = Class.forName(TARGET_NAME, true, loader);
        compileAndVerify(target);
        return new CustomReferences(new WeakReference<>(loader),
                                    new WeakReference<>(target));
    }

    private static WeakReference<Class<?>> runHiddenClassRound(byte[] targetBytes)
            throws Exception {
        Class<?> target = MethodHandles.lookup()
                .defineHiddenClass(targetBytes, true)
                .lookupClass();
        compileAndVerify(target);
        return new WeakReference<>(target);
    }

    private static void compileAndVerify(Class<?> target) throws Exception {
        Constructor<?> constructor = target.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();

        Method isInstance = target.getDeclaredMethod("constantIsInstance", Object.class);
        Method cast = target.getDeclaredMethod("constantCast", Object.class);
        Method assignable = target.getDeclaredMethod("constantAssignable", Class.class);
        Method assignableArgument = target.getDeclaredMethod(
                "constantAssignableArgument", Class.class);
        isInstance.setAccessible(true);
        cast.setAccessible(true);
        assignable.setAccessible(true);
        assignableArgument.setAccessible(true);

        compile(isInstance);
        compile(cast);
        compile(assignable);
        compile(assignableArgument);

        if (!((Boolean) isInstance.invoke(null, instance))) {
            throw new RuntimeException("constant isInstance failed for " + target);
        }
        if (cast.invoke(null, instance) != instance) {
            throw new RuntimeException("constant cast failed for " + target);
        }
        if (!((Boolean) assignable.invoke(null, target))) {
            throw new RuntimeException("constant isAssignableFrom failed for " + target);
        }
        if (!((Boolean) assignableArgument.invoke(null, target))) {
            throw new RuntimeException(
                    "constant isAssignableFrom argument failed for " + target);
        }
    }

    private static void compile(Method method) {
        if (!WB.enqueueMethodForCompilation(
                method, CompilerWhiteBoxTest.COMP_LEVEL_FULL_OPTIMIZATION)
                || !WB.isMethodCompiled(method)) {
            throw new RuntimeException("Jeandle did not compile " + method);
        }
    }

    private static void awaitCustomClassUnloading(CustomReferences refs, int round) {
        for (int attempt = 0; attempt < 20; attempt++) {
            WB.fullGC();
            if (refs.loader().get() == null && refs.target().get() == null) {
                return;
            }
        }
        throw new RuntimeException("custom class did not unload in round " + round);
    }

    private static void awaitHiddenClassUnloading(WeakReference<Class<?>> target,
                                                   int round) {
        for (int attempt = 0; attempt < 20; attempt++) {
            WB.fullGC();
            if (target.get() == null) {
                return;
            }
        }
        throw new RuntimeException("hidden class did not unload in round " + round);
    }

    private record CustomReferences(WeakReference<ClassLoader> loader,
                                    WeakReference<Class<?>> target) {}

    private static final class TargetLoader extends ClassLoader {
        private final byte[] targetBytes;

        TargetLoader(byte[] targetBytes) {
            super(TestClassConstantUnloading.class.getClassLoader());
            this.targetBytes = targetBytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && name.equals(TARGET_NAME)) {
                    loaded = defineClass(name, targetBytes, 0, targetBytes.length);
                }
                if (loaded == null) {
                    loaded = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    public static class UnloadableTarget {
        public static boolean constantIsInstance(Object object) {
            return UnloadableTarget.class.isInstance(object);
        }

        public static Object constantCast(Object object) {
            return UnloadableTarget.class.cast(object);
        }

        public static boolean constantAssignable(Class<?> klass) {
            return UnloadableTarget.class.isAssignableFrom(klass);
        }

        public static boolean constantAssignableArgument(Class<?> klass) {
            return klass.isAssignableFrom(UnloadableTarget.class);
        }
    }
}
