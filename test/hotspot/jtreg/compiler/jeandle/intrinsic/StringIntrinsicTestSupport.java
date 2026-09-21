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

package compiler.jeandle.intrinsic;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.Utils;
import jdk.test.whitebox.WhiteBox;
import jdk.test.whitebox.code.NMethod;

final class StringIntrinsicTestSupport {
    private StringIntrinsicTestSupport() {}

    static Method caller(String name, Class<?> srcType, Class<?> dstType)
            throws ReflectiveOperationException {
        return Class.forName("java.lang.JeandleStringCodingEncodeCaller").getMethod(
                name, srcType, int.class, dstType, int.class, int.class);
    }

    static void assertCompiled(Method... methods) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            Asserts.assertTrue(wb.isMethodCompiled(method), "not compiled: " + method);
            Asserts.assertEquals(wb.getMethodCompilationLevel(method), 4,
                    "wrong compilation level: " + method);
        }
    }

    static void compile(Method method) {
        WhiteBox wb = WhiteBox.getWhiteBox();
        Asserts.assertTrue(wb.enqueueMethodForCompilation(method, 4),
                "could not enqueue: " + method);
        Asserts.assertTrue(Utils.waitForCondition(() -> wb.isMethodCompiled(method),
                Utils.adjustTimeout(10_000)), "compilation timed out: " + method);
        assertCompiled(method);
    }

    // Recompile after profile training. Preserve only the newly emitted IR for
    // each installed nmethod, so warmup and later cold-case dumps cannot pass
    // the driver's intrinsic assertions. Compile overloads one at a time since
    // Jeandle's dump names do not contain a method descriptor.
    static int[] beginChecks(String dump, Method... methods) throws Exception {
        Path checked = Files.createDirectory(Path.of(dump, "checked"));
        int[] ids = new int[methods.length];
        WhiteBox wb = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            wb.deoptimizeMethod(method);
        }
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            String name = method.getDeclaringClass().getName().replace('.', '_')
                    + "_" + method.getName() + "_";
            List<Path> before = optimizedIR(Path.of(dump), name);
            String methodKey = method.getDeclaringClass().getName() + "::" + method.getName();
            // PEA dumps use stderr. Bound them on that same stream so a test
            // can associate replay evidence with the installed compilation.
            System.err.println("STRING_COPY_COMPILE_BEGIN " + methodKey);
            compile(method);
            ids[i] = compilationId(method);
            System.err.println("STRING_COPY_COMPILE_END " + ids[i] + " " + methodKey);
            List<Path> fresh = optimizedIR(Path.of(dump), name).stream()
                    .filter(path -> !before.contains(path)).toList();
            Asserts.assertEquals(fresh.size(), 1, "expected one fresh IR dump: " + method);
            Path file = fresh.getFirst();
            Files.copy(file, checked.resolve(ids[i] + "_" + file.getFileName()));
            Path raw = file.resolveSibling(file.getFileName().toString()
                    .replace("_optimized.ll", ".ll"));
            Files.copy(raw, checked.resolve(ids[i] + "_" + raw.getFileName()));
            System.out.println("STRING_COPY_COMPILE " + ids[i] + " " + method);
        }
        return ids;
    }

    static void endChecks(int[] ids, Method... methods) {
        assertCompiled(methods);
        for (int i = 0; i < methods.length; i++) {
            Asserts.assertEquals(compilationId(methods[i]), ids[i],
                    "nmethod changed during semantic checks: " + methods[i]);
        }
    }

    private static int compilationId(Method method) {
        NMethod code = NMethod.get(method, false);
        Asserts.assertNotNull(code, "missing installed nmethod: " + method);
        return code.compile_id;
    }

    private static List<Path> optimizedIR(Path directory, String method) throws Exception {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.toString().endsWith("_optimized.ll"))
                    .filter(path -> path.getFileName().toString().contains(method)).toList();
        }
    }

    private static List<Path> checkedIR(String dump, String method) throws Exception {
        Path directory = Path.of(dump, "checked");
        // The trap-throttle test intentionally inspects successive compilations
        // directly; the semantic tests use the snapshots from beginChecks.
        if (!Files.isDirectory(directory)) {
            directory = Path.of(dump);
        }
        List<Path> files = optimizedIR(directory, method);
        Asserts.assertFalse(files.isEmpty(), "missing optimized IR for " + method);
        return files;
    }

    static void assertContains(String dump, String method, String marker, String message)
            throws Exception {
        // Every checked overload must contain the intrinsic, not merely one
        // method sharing its name. Negative checks below still scan for any hit.
        for (Path file : checkedIR(dump, method)) {
            Asserts.assertTrue(Files.readString(file).contains(marker), message + ": " + file);
        }
    }

    static boolean contains(String dump, String method, String marker) throws Exception {
        // A negative control with no compilation evidence must not pass.
        for (Path file : checkedIR(dump, method)) {
            if (Files.readString(file).contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static void assertMaterializationMarker(String dump, String method, boolean expected)
            throws Exception {
        String call = "\"jeandle.pea.materialize\"(";
        for (Path optimized : checkedIR(dump, method)) {
            Path raw = optimized.resolveSibling(optimized.getFileName().toString()
                    .replace("_optimized.ll", ".ll"));
            String rawIR = Files.readString(raw);
            Asserts.assertEquals(rawIR.contains(call), expected,
                    "unexpected materialization marker in " + raw);
            if (expected) {
                assertMarkerMemoryEffects(rawIR, call, raw);
            }
            Asserts.assertFalse(rawIR.contains("@jeandle.string_intrinsic_memory("),
                    "obsolete materialization JavaOp in " + raw);
            Asserts.assertFalse(Files.readString(optimized).contains(call),
                    "materialization marker survived cleanup in " + optimized);
        }
    }

    static void assertPEAMaterialization(String dump, String methodKey, String stderr)
            throws Exception {
        String method = methodKey.substring(methodKey.lastIndexOf("::") + 2);
        for (Path optimized : checkedIR(dump, method)) {
            String checkedDump = checkedCompilationDump(optimized, methodKey, stderr);
            Asserts.assertTrue(checkedDump.contains("pea.matslot"),
                    "PEA did not replay local array state for " + optimized);
        }
    }

    private static void assertMarkerMemoryEffects(String ir, String marker, Path file) {
        Pattern attributeRef = Pattern.compile("@llvm\\.sideeffect\\(\\)\\s+#(\\d+)\\s+\\[");
        for (String line : ir.lines().filter(value -> value.contains(marker)).toList()) {
            Matcher match = attributeRef.matcher(line);
            Asserts.assertTrue(match.find(), "marker has no call-site attributes in " + file);
            String prefix = "attributes #" + match.group(1) + " = {";
            String attributes = ir.lines().filter(value -> value.startsWith(prefix))
                    .findFirst().orElse("");
            Asserts.assertTrue(attributes.contains("memory(inaccessiblemem: readwrite)"),
                    "marker has unexpected memory effects in " + file + ": " + attributes);
        }
    }

    static void assertMaterializationMarkerAfterInlining(String dump, String methodKey,
                                                         boolean expected, String stderr)
            throws Exception {
        String method = methodKey.substring(methodKey.lastIndexOf("::") + 2);
        String marker = "\"jeandle.pea.materialize\"(";
        for (Path optimized : checkedIR(dump, method)) {
            String checkedDump = checkedCompilationDump(optimized, methodKey, stderr);
            Asserts.assertEquals(checkedDump.contains(marker), expected,
                    "unexpected post-inline materialization marker for " + optimized);
            Asserts.assertFalse(Files.readString(optimized).contains(marker),
                    "materialization marker survived cleanup in " + optimized);
        }
    }

    private static String checkedCompilationDump(Path optimized, String methodKey, String stderr) {
        String fileName = optimized.getFileName().toString();
        String compileId = fileName.substring(0, fileName.indexOf('_'));
        int end = stderr.indexOf("STRING_COPY_COMPILE_END " + compileId + " " + methodKey);
        Asserts.assertTrue(end >= 0, "missing compilation end for " + optimized);
        int begin = stderr.lastIndexOf("STRING_COPY_COMPILE_BEGIN " + methodKey, end);
        Asserts.assertTrue(begin >= 0, "missing compilation start for " + optimized);
        String checkedDump = stderr.substring(begin, end);
        Asserts.assertTrue(checkedDump.contains("PEA-DUMP"), "PEA did not run for " + optimized);
        return checkedDump;
    }
}
