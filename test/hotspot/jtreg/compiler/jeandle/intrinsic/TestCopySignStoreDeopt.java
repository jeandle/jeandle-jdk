/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License version 2 for more details (a copy is included
 * in the LICENSE file that accompanied this code).
 *
 * You should have received a copy of the GNU General Public License
 * version 2 along with this work; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

/*
 * @test
 * @summary copySign store lowering must preserve FP operand-stack values at executed deoptimization guards
 * @requires os.arch=="amd64" | os.arch=="x86_64"
 * @library /test/lib /
 * @run main/othervm/timeout=180 compiler.jeandle.intrinsic.TestCopySignStoreDeopt
 */

package compiler.jeandle.intrinsic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class TestCopySignStoreDeopt {
    private static final String OWNER = TestWrapper.class.getName();
    private static final Pattern BUNDLE_BCI = Pattern.compile(
            "\"deopt\"\\(i64 1, i32 ([0-9]+), i32 \\1,");
    private static final String SSA = "%[-a-zA-Z$._0-9]+";

    public static void main(String[] args) throws Exception {
        // A failing guard may invalidate its nmethod. Each action gets fresh
        // methods and profiles, and each wrapper takes at most one failure.
        for (String action : new String[] {"normal", "null", "bounds"}) {
            Path dump = Files.createTempDirectory("jeandle_copySign_store_deopt_" + action);
            Path log = dump.resolve("compilation.xml");
            OutputAnalyzer output = ProcessTools.executeCommand(
                    ProcessTools.createLimitedTestJavaProcessBuilder(
                            "-Xcomp", "-Xbatch", "-XX:-TieredCompilation", "-XX:+UseJeandleCompiler",
                            "-XX:+UseCopySignIntrinsic", "-XX:+UnlockDiagnosticVMOptions",
                            "-XX:+LogCompilation", "-XX:LogFile=" + log,
                            "-XX:+JeandleDumpIR", "-XX:JeandleDumpDirectory=" + dump,
                            "-XX:CompileCommand=compileonly," + OWNER + "::*Store",
                            OWNER, action));
            output.shouldHaveExitValue(0).shouldContain("COPY_SIGN_STORE_DEOPT_PASSED " + action);
            Document xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(log.toFile());
            for (boolean isDouble : new boolean[] {true, false}) {
                String method = isDouble ? "doubleStore" : "floatStore";
                int bci = checkIR(dump, method, isDouble);
                checkExecution(xml, method, isDouble, action, bci);
            }
        }
    }

    private static int checkIR(Path dump, String method, boolean isDouble) throws Exception {
        String fp = isDouble ? "double" : "float";
        String bits = isDouble ? "i64" : "i32";
        String suffix = isDouble ? "f64" : "f32";
        String raw = readIR(dump, method, false);
        Matcher call = Pattern.compile("(?m)^\\s*(" + SSA + ") = call " + fp
                + " @llvm\\.copysign\\." + suffix + "\\(").matcher(raw);
        Asserts.assertTrue(call.find(), "Missing raw copysign in " + method);
        String rawResult = call.group(1);
        Asserts.assertTrue(Pattern.compile("store atomic " + fp + " " + Pattern.quote(rawResult)
                + ",").matcher(raw).find(), "Raw result must feed the FP store: " + method);
        int bci = checkDeoptSlots(raw, rawResult, isDouble);

        String optimized = readIR(dump, method, true);
        Asserts.assertFalse(Pattern.compile("call " + fp + " @llvm\\.copysign\\.")
                .matcher(optimized).find(), "Store copysign was not lowered: " + method);
        Asserts.assertTrue(Pattern.compile("and " + bits + " [^\\n]*, "
                + (isDouble ? "-9223372036854775808" : "-2147483648"))
                .matcher(optimized).find(), "Missing integer sign mask: " + method);
        Matcher result = Pattern.compile("(?m)^\\s*(" + SSA + ") = bitcast " + bits
                + " (" + SSA + ") to " + fp + "\\b").matcher(optimized);
        boolean matched = false;
        while (result.find()) {
            String fpResult = result.group(1);
            String storedBits = result.group(2);
            if (!Pattern.compile("store atomic " + bits + " " + Pattern.quote(storedBits) + ",")
                    .matcher(optimized).find()) {
                continue;
            }
            Asserts.assertEquals(checkDeoptSlots(optimized, fpResult, isDouble), bci,
                    "Store deopt BCI must be unchanged: " + method);
            matched = true;
            break;
        }
        Asserts.assertTrue(matched, "Missing integer store with FP deopt reconstruction: " + method);
        return bci;
    }

    private static int checkDeoptSlots(String ir, String value, boolean isDouble) {
        // DeoptValueEncoding: (stack index << 32) | (StackType << 16) | BasicType.
        // At dastore/fastore the value is stack slot 2, after array and index;
        // StackType is 1, T_DOUBLE is 7, and T_FLOAT is 6.
        long encoding = (2L << 32) | (1L << 16) | (isDouble ? 7 : 6);
        String slot = "i64 " + encoding + ", " + (isDouble ? "double " : "float ") + value + ",";
        Set<Integer> bcis = new HashSet<>();
        int guards = 0;
        for (String line : ir.lines().toList()) {
            if (!line.contains("deoptimize") || !line.contains(slot)) {
                continue;
            }
            Matcher bci = BUNDLE_BCI.matcher(line);
            Asserts.assertTrue(bci.find(), "Missing reexecuted store BCI: " + line);
            bcis.add(Integer.parseInt(bci.group(1)));
            guards++;
        }
        Asserts.assertTrue(guards >= 2, "Both null and bounds guards must preserve FP result " + value);
        Asserts.assertEquals(bcis.size(), 1, "FP result must occupy the store's operand stack");
        return bcis.iterator().next();
    }

    private static String readIR(Path dump, String method, boolean optimized) throws Exception {
        String prefix = OWNER.replace('.', '_') + "_" + method + "_";
        List<Path> files;
        try (var paths = Files.list(dump)) {
            files = paths.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".ll")
                        && name.endsWith("_optimized.ll") == optimized;
            }).toList();
        }
        Asserts.assertEquals(files.size(), 1, "Expected one IR version for " + method + " in " + dump);
        return Files.readString(files.get(0));
    }

    private static void checkExecution(Document xml, String method, boolean isDouble,
                                       String action, int bci) {
        String target = OWNER + " " + method + (isDouble ? " ([D[D[DI)V" : " ([F[F[FI)V");
        Element compiled = null;
        NodeList methods = xml.getElementsByTagName("nmethod");
        for (int i = 0; i < methods.getLength(); i++) {
            Element nmethod = (Element) methods.item(i);
            if (target.equals(nmethod.getAttribute("method"))) {
                Asserts.assertEquals(nmethod.getAttribute("compiler"), "jeandle", target);
                Asserts.assertFalse(nmethod.hasAttribute("compile_kind"), "Expected ordinary entry: " + target);
                Asserts.assertTrue(compiled == null, "Unexpected recompilation of " + target);
                compiled = nmethod;
            }
        }
        Asserts.assertTrue(compiled != null, "Missing Jeandle nmethod: " + target);
        String id = compiled.getAttribute("compile_id");
        boolean found = false;
        NodeList traps = xml.getElementsByTagName("uncommon_trap");
        for (int i = 0; i < traps.getLength(); i++) {
            Element trap = (Element) traps.item(i);
            // Compilation-task trap descriptions are not executed events.
            if (!trap.hasAttribute("thread") || !id.equals(trap.getAttribute("compile_id"))) {
                continue;
            }
            Asserts.assertFalse(action.equals("normal"), "Unexpected normal-path trap: " + target);
            Asserts.assertEquals(trap.getAttribute("compiler"), "jeandle", target);
            Asserts.assertEquals(trap.getAttribute("reason"),
                    action.equals("null") ? "null_check" : "range_check", target);
            Asserts.assertTrue(Double.parseDouble(trap.getAttribute("stamp"))
                    >= Double.parseDouble(compiled.getAttribute("stamp")), "Trap precedes installation");
            NodeList frames = trap.getElementsByTagName("jvms");
            for (int j = 0; j < frames.getLength(); j++) {
                Element frame = (Element) frames.item(j);
                if (target.equals(frame.getAttribute("method"))
                        && Integer.toString(bci).equals(frame.getAttribute("bci"))) {
                    found = true;
                }
            }
        }
        Asserts.assertTrue(action.equals("normal") || found,
                "Missing executed " + action + " trap for " + target + " id=" + id + " bci=" + bci);
    }

    static class TestWrapper {
        public static void main(String[] args) {
            Math.copySign(1.0, -1.0);
            Math.copySign(1.0f, -1.0f);
            double magnitude = Double.longBitsToDouble(0x7ff0000000000042L);
            float magnitudeF = Float.intBitsToFloat(0x7f800042);
            double[] magnitudes = {magnitude};
            double[] signs = {-0.0};
            float[] magnitudesF = {magnitudeF};
            float[] signsF = {-0.0f};
            double[] out = new double[1];
            float[] outF = new float[1];
            doubleStore(magnitudes, signs, out, 0);
            floatStore(magnitudesF, signsF, outF, 0);
            Asserts.assertEquals(Double.doubleToRawLongBits(out[0]), 0xfff0000000000042L,
                    "double signaling NaN payload");
            Asserts.assertEquals(Float.floatToRawIntBits(outF[0]), 0xff800042,
                    "float signaling NaN payload");
            if (!args[0].equals("normal")) {
                boolean nullArray = args[0].equals("null");
                try {
                    doubleStore(magnitudes, signs, nullArray ? null : out, 1);
                    throw new AssertionError("Missing double store exception");
                } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
                    Asserts.assertTrue(nullArray ? e instanceof NullPointerException
                            : e instanceof ArrayIndexOutOfBoundsException, "Wrong double store exception");
                }
                try {
                    floatStore(magnitudesF, signsF, nullArray ? null : outF, 1);
                    throw new AssertionError("Missing float store exception");
                } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
                    Asserts.assertTrue(nullArray ? e instanceof NullPointerException
                            : e instanceof ArrayIndexOutOfBoundsException, "Wrong float store exception");
                }
            }
            System.out.println("COPY_SIGN_STORE_DEOPT_PASSED " + args[0]);
        }

        public static void doubleStore(double[] magnitude, double[] sign, double[] out, int index) {
            out[index] = Math.copySign(magnitude[0], sign[0]);
        }

        public static void floatStore(float[] magnitude, float[] sign, float[] out, int index) {
            out[index] = Math.copySign(magnitude[0], sign[0]);
        }
    }
}
