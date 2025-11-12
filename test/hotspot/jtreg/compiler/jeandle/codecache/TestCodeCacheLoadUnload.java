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
 * @test TestCodeCacheLoadUnload.java
 * @summary Test CodeCache load/unload for jeandle compiler
 * @requires vm.debug
 * @library /test/lib
 * @run driver compiler.jeandle.codecache.TestCodeCacheLoadUnload
 */

package compiler.jeandle.codecache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestCodeCacheLoadUnload {
    private static final String METHOD_FULL_NAME = "compiler.jeandle.codecache.Foo.doSomething(II)I";

    private static final String[] procArgs = new String[] {
        "-Xcomp",
        "-XX:-TieredCompilation",
        "-XX:CompileCommand=compileonly,compiler.jeandle.codecache.Foo::doSomething",
        "-XX:+UseJeandleCompiler",
        "-XX:+PrintCodeCache2",
        "-XX:+Verbose",
        "compiler.jeandle.codecache.TestCodeCacheLoadUnload"
    };

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            runVM();
        } else if (args[0].equals("load")) {
            test_load();
        } else if (args[0].equals("unload")) {
            test_unload();
        } else {
            throw new IllegalArgumentException("Unsupported argument: " + args[0]);
        }
    }

    public static void runVM() throws Exception {
        OutputAnalyzer outputLoad = runTestProcess("load");
        outputLoad.shouldHaveExitValue(0);
        outputLoad.shouldContain(METHOD_FULL_NAME);

        OutputAnalyzer outputUnload = runTestProcess("unload");
        outputUnload.shouldHaveExitValue(0);
        outputUnload.shouldNotContain(METHOD_FULL_NAME);
    }

    static OutputAnalyzer runTestProcess(String testType) throws Exception {
        List<String> loadCmd = new ArrayList();
        loadCmd.addAll(Arrays.asList(procArgs));
        loadCmd.add(testType);
        String testClassPath = System.getProperty("test.classes", ".");
        String classPathProp = "-Dtest.classes=" + testClassPath;
        loadCmd.add(0, classPathProp);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(loadCmd);
        return ProcessTools.executeProcess(pb);
    }

    static void test_load() throws Exception {
        compileCode();

        System.out.println("no gc");
    }

    static void test_unload() throws Exception {
        compileCode();

        System.out.println("before gc");
        System.gc();
    }

    static void compileCode() throws Exception {
        MyClassLoader loader = new MyClassLoader();
        Class<?> foo = loader.loadClass("compiler.jeandle.codecache.Foo", true);
        Method func = foo.getDeclaredMethod("doSomething", int.class, int.class);
        func.setAccessible(true);
        func.invoke(null, 4, 2);
    }
}

class MyClassLoader extends ClassLoader {
    public MyClassLoader() {
        super(ClassLoader.getSystemClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> c;
        if (name.equals("compiler.jeandle.codecache.Foo")) {
            c = defineClass(name, readClassFile("Foo.class"), null);
            if (resolve) {
                resolveClass(c);
            }
        } else {
            c = super.loadClass(name, resolve);
        }
        return c;
    }

    static ByteBuffer readClassFile(String name) {
        String testClassPath = System.getProperty("test.classes", ".");
        File f = new File(testClassPath + "/compiler/jeandle/codecache", name);
        try (FileInputStream fin = new FileInputStream(f)) {
            FileChannel fc = fin.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
        } catch (IOException e) {
            throw new RuntimeException("Can't open file: " + f, e);
        }
    }
}

class Foo {
    public static int doSomething(int a, int b) {
        for (int i = 0; i < b; i++) {
            a += 10;
            a = a % 2;
        }
        return a;
    }
}
