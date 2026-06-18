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
 * @test TestRepeatedConstantFolding.java
 * @summary Test RepeatedConstantFolding pass: static-final field folds across
 *          primitive types, reference chains, PHI / select scenarios, and the
 *          cascading-fold path that requires CFF + SCCP + SimplifyCFG iteration.
 * @library /test/lib /
 * @run driver TestRepeatedConstantFolding
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestRepeatedConstantFolding {

    // =========================================================================
    // Identity helpers: defeat javac's compile-time-constant inlining.
    // A static final primitive initialized with a literal becomes a JLS 15.29
    // constant variable, which javac splices into every use as a literal --
    // the getstatic disappears from bytecode and there is nothing for CFF to
    // fold. Routing the initializer through a non-CTE method call preserves
    // the getstatic.
    // =========================================================================
    private static boolean id(boolean v) { return v; }
    private static byte    id(byte v)    { return v; }
    private static char    id(char v)    { return v; }
    private static short   id(short v)   { return v; }
    private static int     id(int v)     { return v; }
    private static long    id(long v)    { return v; }
    private static float   fbits(int bits)  { return Float.intBitsToFloat(bits); }
    private static double  dbits(long bits) { return Double.longBitsToDouble(bits); }
    private static String  makeStr()   { return "rcf-test-string"; }
    private static Object  makeNull()  { return null; }

    // =========================================================================
    // Helper classes
    // =========================================================================

    // Untrusted instance-final holder: ciField::trust_final_non_static_fields()
    // does not list user classes, so instance final fields here must NOT fold.
    static final class IntHolder {
        final int v;
        IntHolder(int v) { this.v = v; }
    }

    // Static-final holder for every primitive plus a handful of reference
    // constants used across the suite.
    static final class Constants {
        static final boolean BOOL_T   = id(true);
        static final boolean BOOL_F   = id(false);
        static final byte    B_NEG    = id((byte) -1);
        static final byte    B_MIN    = id(Byte.MIN_VALUE);
        static final char    C_HI     = id((char) 0xFFFF);
        static final char    C_LO     = id('A');
        static final short   S_NEG    = id((short) -1);
        static final int     I_POS    = id(42);
        static final int     I_DEAD   = id(0xDEADBEEF);          // -559038737
        static final int     I_MIN    = id(Integer.MIN_VALUE);
        static final long    L_VAL    = id(0x0123456789ABCDEFL); // 81985529216486895
        static final long    L_MIN    = id(Long.MIN_VALUE);
        static final float   F_NAN    = fbits(0x7FC00001);
        static final float   F_PINF   = fbits(0x7F800000);
        static final float   F_NINF   = fbits(0xFF800000);
        static final float   F_NZERO  = fbits(0x80000000);
        static final float   F_SUBNRM = fbits(0x00000001);
        static final double  D_NAN    = dbits(0x7FF8000000000001L);
        static final double  D_MAX    = dbits(0x7FEFFFFFFFFFFFFFL);
        static final String  STR      = makeStr();
        static final String  NULLSTR  = (String) makeNull();
        static final Integer IBOX_INT = Integer.valueOf(777);
        static final int[]   ARR      = new int[] {10, 20, 30};
        static final IntHolder IBOX   = new IntHolder(777);
    }

    // Flagged constants used by the PHI / cascading scenarios.
    static final class Flagged {
        static final boolean FLAG_T = id(true);
        static final boolean FLAG_F = id(false);
        static final Integer A_INT  = Integer.valueOf(100);
        static final Integer B_INT  = Integer.valueOf(200);
        static final Integer C_INT  = Integer.valueOf(300);
        static final Integer D_INT  = Integer.valueOf(400);
    }

    // 5-level static-final reference chain. The Jeandle abstract interpreter
    // chases such chains during bytecode -> IR translation, so by the time
    // RCF sees the IR only the leaf primitive load remains -- but CFF still
    // has to fold it.
    static final class L4 { static final int LEAF = id(5555); }
    static final class L3 { static final L4 NEXT = new L4(); }
    static final class L2 { static final L3 NEXT = new L3(); }
    static final class L1 { static final L2 NEXT = new L2(); }
    static final class L0 { static final L1 NEXT = new L1(); }

    // Mutable / volatile holder used by the negative cases.
    static class Holder {
        static int mut = 99;
        static volatile int volInt = 5;
    }

    // Static-final reference whose <clinit> bumps a counter on the enclosing
    // class. Verifies the clinit barrier is preserved even when the read
    // is folded to a literal.
    static int clinitCount = 0;
    static class ClinitHolder {
        static final int CONST = id(123);
        static {
            TestRepeatedConstantFolding.clinitCount++;
        }
    }

    // =========================================================================
    // A. Primitive type coverage
    //
    // Targets bugs:
    //   - sub-int sign/zero extension flipped (byte vs char polarity)
    //   - float/double bit reconstruction wrong (NaN payload, signed zero,
    //     subnormal)
    //   - T_LONG path truncates int64_t to int
    //   - boolean vs byte mixed up (both are i8 with different extension)
    // =========================================================================

    static int  testStaticFinalBooleanTrue()    { return Constants.BOOL_T ? 1 : 0; }
    static int  testStaticFinalBooleanFalse()   { return Constants.BOOL_F ? 1 : 0; }
    static int  testStaticFinalByteNeg()        { return (int) Constants.B_NEG; }
    static int  testStaticFinalByteMin()        { return (int) Constants.B_MIN; }
    static int  testStaticFinalCharHigh()       { return (int) Constants.C_HI; }
    static int  testStaticFinalCharLow()        { return (int) Constants.C_LO; }
    static int  testStaticFinalShortNeg()       { return (int) Constants.S_NEG; }
    static int  testStaticFinalIntDead()        { return Constants.I_DEAD; }
    static int  testStaticFinalIntMin()         { return Constants.I_MIN; }
    static long testStaticFinalLongVal()        { return Constants.L_VAL; }
    static long testStaticFinalLongMin()        { return Constants.L_MIN; }
    static int  testStaticFinalFloatNaN()       { return Float.floatToRawIntBits(Constants.F_NAN); }
    static int  testStaticFinalFloatPInf()      { return Float.floatToRawIntBits(Constants.F_PINF); }
    static int  testStaticFinalFloatNInf()      { return Float.floatToRawIntBits(Constants.F_NINF); }
    static int  testStaticFinalFloatNZero()     { return Float.floatToRawIntBits(Constants.F_NZERO); }
    static int  testStaticFinalFloatSubnormal() { return Float.floatToRawIntBits(Constants.F_SUBNRM); }
    static long testStaticFinalDoubleNaN()      { return Double.doubleToRawLongBits(Constants.D_NAN); }
    static long testStaticFinalDoubleMax()      { return Double.doubleToRawLongBits(Constants.D_MAX); }
    static long testStaticFinalByteToLong()     { return (long) Constants.B_NEG; }

    // =========================================================================
    // B. Static-final reference folding
    // =========================================================================

    static int  testStaticFinalStringHash()       { return Constants.STR.hashCode(); }
    static int  testStaticFinalReferenceIdentity() { return Constants.STR == Constants.STR ? 1 : 0; }
    static int  testStaticFinalNullReference()    { return Constants.NULLSTR == null ? 1 : 0; }
    static int  testStaticFinalArrayLength()      { return Constants.ARR.length; }

    // =========================================================================
    // D. PHI / Select scenarios
    // =========================================================================

    // Same constant on both PHI arms -- lattice meet(C{id}, C{id}) = C{id}.
    static int  testPhiSameConstantTwoArms(boolean flag) {
        Integer x = flag ? Constants.IBOX_INT : Constants.IBOX_INT;
        return x.intValue();
    }

    // Distinct constants -- lattice meet(C{a}, C{b}) = Bottom.
    static int  testPhiTwoDifferentConstants(boolean flag) {
        Integer x = flag ? Constants.IBOX_INT : Flagged.A_INT;
        return x.intValue();
    }

    // One constant, one opaque -- lattice meet(C{a}, Bottom) = Bottom.
    static int  testPhiOneConstantOneOpaque(boolean flag, Integer param) {
        Integer x = flag ? Constants.IBOX_INT : param;
        return x.intValue();
    }

    // *** The central RCF scenario ***
    // Iter 1 CFF: FLAG_T folds to true; A_INT and B_INT loads each fold to
    //             their oop_handle global. PHI of two distinct ids stays
    //             Bottom (CFF cannot fold).
    // Iter 1 cleanup (SCCP + SimplifyCFG): branch becomes unconditional;
    //             dead arm dropped; PHI degenerates to one incoming.
    // Iter 2 CFF: PHI(x) now has a single Constant{idA} incoming -> fully
    //             folded. Returns 100 at runtime via Integer.intValue().
    static int  testCascadingBranchUnlocksPhi() {
        Integer x;
        if (Flagged.FLAG_T) x = Flagged.A_INT;
        else                x = Flagged.B_INT;
        return x.intValue();
    }

    // Self-cycle PHI: the loop-header reassigns x to the same constant.
    // Lattice must converge to C{id}, not Bottom (initial seed Top).
    static int  testSelfCyclePhiLoopReassign(int n) {
        Integer x = Constants.IBOX_INT;
        for (int i = 0; i < n; i++) x = Constants.IBOX_INT;
        return x.intValue();
    }

    // Mutual cycle of two PHIs that swap each iteration; both seeded from
    // the same constant -> both lattices = C{id} -> field loads fold.
    static int  testMutualCyclePhiSameIdFold(int n) {
        Integer a = Constants.IBOX_INT;
        Integer b = Constants.IBOX_INT;
        for (int i = 0; i < n; i++) {
            Integer t = a; a = b; b = t;
        }
        return a.intValue() + b.intValue();
    }

    // Same shape as above but seeded from distinct constants -> both
    // lattices = Bottom -> no fold.
    static int  testMutualCyclePhiDifferentIdNoFold(int n) {
        Integer a = Flagged.A_INT;
        Integer b = Flagged.B_INT;
        for (int i = 0; i < n; i++) {
            Integer t = a; a = b; b = t;
        }
        return a.intValue() + b.intValue();
    }

    // Nested ternary, 3-level PHI tree, all leaves the same id.
    static int  testNestedTernary(boolean f1, boolean f2) {
        Integer x = f1 ? (f2 ? Constants.IBOX_INT : Constants.IBOX_INT)
                       : (f2 ? Constants.IBOX_INT : Constants.IBOX_INT);
        return x.intValue();
    }

    // =========================================================================
    // E. Iteration / convergence
    // =========================================================================

    // Deep static-final reference chain. The abstract interpreter collapses
    // the chain to a single load against the leaf class mirror, but CFF
    // must still fold that leaf int load.
    static int  testDeepStaticFinalChain() {
        return L0.NEXT.NEXT.NEXT.NEXT.LEAF;
    }

    // Two independent cascading-PHI scenarios in one function. Both fold
    // in <= 2 RCF iterations.
    static int  testCascadingTwoLevels() {
        Integer a, b;
        if (Flagged.FLAG_T) a = Flagged.A_INT;
        else                a = Flagged.B_INT;
        if (Flagged.FLAG_T) b = Flagged.C_INT;
        else                b = Flagged.D_INT;
        return a.intValue() + b.intValue();
    }

    // =========================================================================
    // F. Negative cases
    // =========================================================================

    static int  testNonFinalStaticIntNoFold()   { return Holder.mut; }
    static int  testVolatileStaticIntNoFold()   { return Holder.volInt; }

    // The receiver is passed in as an opaque parameter so the JIT cannot
    // see it as a known oop -- avoids the uncommon_trap we would otherwise
    // hit when `compileonly` prevents inlining of `new IntHolder(...)`.
    static int  testInstanceFieldNonConstantHolderNoFold(IntHolder h) {
        return h.v;
    }

    // IntHolder is a user class -- ciField::trust_final_non_static_fields()
    // does not list it, so v is NOT a constant and the inner read must
    // survive RCF. The outer IBOX static-final reference itself IS folded.
    static int  testInstanceFinalUserClassNoFold() { return Constants.IBOX.v; }

    // After CFF folds NULLSTR to null and SCCP folds the icmp, SimplifyCFG
    // drops the dead .length() branch entirely -- so the test also checks
    // that we don't erroneously dereference a folded null.
    static int  testGetfieldOnNullStaticRefGuarded() {
        if (Constants.NULLSTR == null) return -1;
        return Constants.NULLSTR.length();
    }

    // =========================================================================
    // G. Side effects
    // =========================================================================

    // CONST = id(123) -- folded to literal 123, but ClinitHolder.<clinit>
    // must still run exactly once, bumping clinitCount.
    static int  testClinitSideEffectRuns()      { return ClinitHolder.CONST; }

    // Non-final read after write must observe the write.
    static int  testNonFinalStaticReadAfterWrite() {
        Holder.mut = 99;
        return Holder.mut;
    }

    // =========================================================================
    // I. Subtle CFF behavior
    // =========================================================================

    // javac emits two getstatic for `Constants.I_POS + Constants.I_POS`;
    // both must fold. SCCP collapses to a single literal.
    static int  testSameFieldReadTwice() { return Constants.I_POS + Constants.I_POS; }

    // =========================================================================
    // IR dump extraction helpers (mirrored from TestTypeCheckElimination).
    // =========================================================================

    static String extractBeforeIR(String stderr, String methodPattern) {
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump Before RepeatedConstantFolding") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) break;
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    static String extractAfterIR(String stderr, String methodPattern) {
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump After RepeatedConstantFolding") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) break;
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    static void assertIRContains(String ir, String pattern, String message) {
        Asserts.assertTrue(ir.contains(pattern),
            message + " -- expected to find: " + pattern + "\n  in IR:\n" + ir);
    }

    static void assertIRNotContains(String ir, String pattern, String message) {
        Asserts.assertFalse(ir.contains(pattern),
            message + " -- expected NOT to find: " + pattern + "\n  in IR:\n" + ir);
    }

    static int countOccurrences(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    // =========================================================================
    // Main: driver mode (no args) dispatches one child JVM per scenario.
    //       Child mode (with test name arg) runs that single test.
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force-load every holder so its oop_handle global exists and its
        // <clinit> has run before compilation. ClinitHolder is intentionally
        // omitted -- testClinitSideEffectRuns relies on its first read to
        // trigger init.
        Class.forName("TestRepeatedConstantFolding$Constants");
        Class.forName("TestRepeatedConstantFolding$Flagged");
        Class.forName("TestRepeatedConstantFolding$IntHolder");
        Class.forName("TestRepeatedConstantFolding$L0");
        Class.forName("TestRepeatedConstantFolding$L1");
        Class.forName("TestRepeatedConstantFolding$L2");
        Class.forName("TestRepeatedConstantFolding$L3");
        Class.forName("TestRepeatedConstantFolding$L4");
        Class.forName("TestRepeatedConstantFolding$Holder");

        if (args.length == 0) {
            runAllTests();
        } else {
            runChildTest(args[0]);
        }
    }

    private static void runChildTest(String testName) {
        switch (testName) {
            // --- A. Primitives ---
            case "testStaticFinalBooleanTrue":
                Asserts.assertEquals(testStaticFinalBooleanTrue(), 1);
                break;
            case "testStaticFinalBooleanFalse":
                Asserts.assertEquals(testStaticFinalBooleanFalse(), 0);
                break;
            case "testStaticFinalByteNeg":
                Asserts.assertEquals(testStaticFinalByteNeg(), -1);
                break;
            case "testStaticFinalByteMin":
                Asserts.assertEquals(testStaticFinalByteMin(), -128);
                break;
            case "testStaticFinalCharHigh":
                Asserts.assertEquals(testStaticFinalCharHigh(), 65535);
                break;
            case "testStaticFinalCharLow":
                Asserts.assertEquals(testStaticFinalCharLow(), 65);
                break;
            case "testStaticFinalShortNeg":
                Asserts.assertEquals(testStaticFinalShortNeg(), -1);
                break;
            case "testStaticFinalIntDead":
                Asserts.assertEquals(testStaticFinalIntDead(), 0xDEADBEEF);
                break;
            case "testStaticFinalIntMin":
                Asserts.assertEquals(testStaticFinalIntMin(), Integer.MIN_VALUE);
                break;
            case "testStaticFinalLongVal":
                Asserts.assertEquals(testStaticFinalLongVal(), 0x0123456789ABCDEFL);
                break;
            case "testStaticFinalLongMin":
                Asserts.assertEquals(testStaticFinalLongMin(), Long.MIN_VALUE);
                break;
            case "testStaticFinalFloatNaN":
                // floatToRawIntBits must preserve the NaN payload exactly.
                Asserts.assertEquals(testStaticFinalFloatNaN(), 0x7FC00001);
                break;
            case "testStaticFinalFloatPInf":
                Asserts.assertEquals(testStaticFinalFloatPInf(), 0x7F800000);
                break;
            case "testStaticFinalFloatNInf":
                Asserts.assertEquals(testStaticFinalFloatNInf(), 0xFF800000);
                break;
            case "testStaticFinalFloatNZero":
                Asserts.assertEquals(testStaticFinalFloatNZero(), 0x80000000);
                break;
            case "testStaticFinalFloatSubnormal":
                Asserts.assertEquals(testStaticFinalFloatSubnormal(), 0x00000001);
                break;
            case "testStaticFinalDoubleNaN":
                Asserts.assertEquals(testStaticFinalDoubleNaN(), 0x7FF8000000000001L);
                break;
            case "testStaticFinalDoubleMax":
                Asserts.assertEquals(testStaticFinalDoubleMax(), 0x7FEFFFFFFFFFFFFFL);
                break;
            case "testStaticFinalByteToLong":
                Asserts.assertEquals(testStaticFinalByteToLong(), -1L);
                break;

            // --- B. References ---
            case "testStaticFinalStringHash":
                Asserts.assertEquals(testStaticFinalStringHash(), Constants.STR.hashCode());
                break;
            case "testStaticFinalReferenceIdentity":
                Asserts.assertEquals(testStaticFinalReferenceIdentity(), 1);
                break;
            case "testStaticFinalNullReference":
                Asserts.assertEquals(testStaticFinalNullReference(), 1);
                break;
            case "testStaticFinalArrayLength":
                Asserts.assertEquals(testStaticFinalArrayLength(), 3);
                break;

            // --- D. PHI / Select ---
            case "testPhiSameConstantTwoArms":
                Asserts.assertEquals(testPhiSameConstantTwoArms(true), 777);
                Asserts.assertEquals(testPhiSameConstantTwoArms(false), 777);
                break;
            case "testPhiTwoDifferentConstants":
                Asserts.assertEquals(testPhiTwoDifferentConstants(true), 777);
                Asserts.assertEquals(testPhiTwoDifferentConstants(false), 100);
                break;
            case "testPhiOneConstantOneOpaque":
                Asserts.assertEquals(testPhiOneConstantOneOpaque(true, Integer.valueOf(42)), 777);
                Asserts.assertEquals(testPhiOneConstantOneOpaque(false, Integer.valueOf(42)), 42);
                break;
            case "testCascadingBranchUnlocksPhi":
                Asserts.assertEquals(testCascadingBranchUnlocksPhi(), 100);
                break;
            case "testSelfCyclePhiLoopReassign":
                Asserts.assertEquals(testSelfCyclePhiLoopReassign(0), 777);
                Asserts.assertEquals(testSelfCyclePhiLoopReassign(5), 777);
                break;
            case "testMutualCyclePhiSameIdFold":
                Asserts.assertEquals(testMutualCyclePhiSameIdFold(0), 1554);
                Asserts.assertEquals(testMutualCyclePhiSameIdFold(3), 1554);
                break;
            case "testMutualCyclePhiDifferentIdNoFold":
                Asserts.assertEquals(testMutualCyclePhiDifferentIdNoFold(0), 300);
                Asserts.assertEquals(testMutualCyclePhiDifferentIdNoFold(1), 300);
                break;
            case "testNestedTernary":
                Asserts.assertEquals(testNestedTernary(true, true), 777);
                Asserts.assertEquals(testNestedTernary(true, false), 777);
                Asserts.assertEquals(testNestedTernary(false, true), 777);
                Asserts.assertEquals(testNestedTernary(false, false), 777);
                break;

            // --- E. Iteration / convergence ---
            case "testDeepStaticFinalChain":
                Asserts.assertEquals(testDeepStaticFinalChain(), 5555);
                break;
            case "testCascadingTwoLevels":
                Asserts.assertEquals(testCascadingTwoLevels(), 400);
                break;

            // --- F. Negative ---
            case "testNonFinalStaticIntNoFold":
                Asserts.assertEquals(testNonFinalStaticIntNoFold(), 99);
                Holder.mut = 7;
                Asserts.assertEquals(testNonFinalStaticIntNoFold(), 7);
                Holder.mut = 99;
                break;
            case "testVolatileStaticIntNoFold":
                Asserts.assertEquals(testVolatileStaticIntNoFold(), 5);
                Holder.volInt = 11;
                Asserts.assertEquals(testVolatileStaticIntNoFold(), 11);
                Holder.volInt = 5;
                break;
            case "testInstanceFieldNonConstantHolderNoFold":
                Asserts.assertEquals(testInstanceFieldNonConstantHolderNoFold(new IntHolder(123)), 123);
                break;
            case "testInstanceFinalUserClassNoFold":
                Asserts.assertEquals(testInstanceFinalUserClassNoFold(), 777);
                break;
            case "testGetfieldOnNullStaticRefGuarded":
                Asserts.assertEquals(testGetfieldOnNullStaticRefGuarded(), -1);
                break;

            // --- G. Side effects ---
            case "testClinitSideEffectRuns":
                Asserts.assertEquals(clinitCount, 0,
                    "ClinitHolder must not be initialised before first access");
                Asserts.assertEquals(testClinitSideEffectRuns(), 123);
                Asserts.assertEquals(clinitCount, 1,
                    "ClinitHolder.<clinit> must run exactly once on first access");
                break;
            case "testNonFinalStaticReadAfterWrite":
                Asserts.assertEquals(testNonFinalStaticReadAfterWrite(), 99);
                break;

            // --- I. Subtle CFF behavior ---
            case "testSameFieldReadTwice":
                Asserts.assertEquals(testSameFieldReadTwice(), 84);
                break;

            default:
                throw new IllegalArgumentException("Unknown test: " + testName);
        }
    }

    private static final String LLVM_OPTIONS =
        "-XX:JeandleLLVMOptions=--print-before=repeated-constant-field-folding "
        + "--print-after=repeated-constant-field-folding";

    private static final String[] BASE_ARGS = {
        "-Xcomp", "-Xbatch", "-XX:-TieredCompilation",
        "-XX:+UseJeandleCompiler"
    };

    private static OutputAnalyzer runTestProcess(String testName, String compileOnly) throws Exception {
        List<String> cmd = new ArrayList<>();
        String testClassPath = System.getProperty("test.classes", ".");
        cmd.add("-Dtest.classes=" + testClassPath);
        cmd.addAll(Arrays.asList(BASE_ARGS));
        cmd.add(LLVM_OPTIONS);
        cmd.add("-XX:CompileCommand=compileonly,TestRepeatedConstantFolding::" + compileOnly);
        cmd.add("TestRepeatedConstantFolding");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    private static int countReferenceOccurrences(String ir, String prefix) {
        return countOccurrences(ir, prefix + " ptr addrspace(1)")
             + countOccurrences(ir, prefix + " ptr addrspace(3)");
    }

    // -------------------------------------------------------------------------
    // Reusable assertion shapes used by runAllTests.
    // -------------------------------------------------------------------------

    /**
     * Strong fold check: before-IR has >= 1 `load atomic <loadTy>`,
     * after-IR has zero, and `retLiteral` is present in the after-IR.
     */
    private static void runPrimitiveFold(String name, String loadTy, String retLiteral) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String beforeIR = extractBeforeIR(output.getOutput(), name);
        String afterIR  = extractAfterIR(output.getOutput(),  name);
        int beforeLoads = countOccurrences(beforeIR, "load atomic " + loadTy);
        int afterLoads  = countOccurrences(afterIR,  "load atomic " + loadTy);
        Asserts.assertGTE(beforeLoads, 1,
            name + ": expected >= 1 'load atomic " + loadTy + "' before RCF, got " + beforeLoads);
        Asserts.assertEquals(afterLoads, 0,
            name + ": all 'load atomic " + loadTy + "' should be folded, got " + afterLoads);
        assertIRContains(afterIR, retLiteral,
            name + ": expected folded return literal");
    }

    /**
     * Weaker fold check: only verifies the field load is folded. Used for
     * float/double primitives (the bit-cast to int may stay as a runtime
     * call so a literal return is not guaranteed) and for static-final
     * reference reads whose downstream comparisons cannot be folded by
     * SCCP alone (two separate loads of the same global do not collapse
     * without GVN/CSE, which RCF does not run).
     */
    private static void runPrimitiveFoldNoRetCheck(String name, String loadTy) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String beforeIR = extractBeforeIR(output.getOutput(), name);
        String afterIR  = extractAfterIR(output.getOutput(),  name);
        int beforeLoads = countOccurrences(beforeIR, "load atomic " + loadTy);
        int afterLoads  = countOccurrences(afterIR,  "load atomic " + loadTy);
        Asserts.assertGTE(beforeLoads, 1,
            name + ": expected >= 1 'load atomic " + loadTy + "' before RCF, got " + beforeLoads);
        Asserts.assertEquals(afterLoads, 0,
            name + ": all 'load atomic " + loadTy + "' should be folded, got " + afterLoads);
    }

    /**
     * PHI-of-same-constant fold: every per-arm `load atomic ptr addrspace(1|3)`
     * (a Java reference-field read) is folded to a direct `load <oop_handle>`
     * global. The PHI itself may or may not survive -- two separate loads of
     * the same LLVM global are not collapsed without GVN/EarlyCSE, which RCF
     * does not run -- so we deliberately don't assert PHI elimination here.
     * What matters is the lattice correctly identified each arm as folding
     * to the same Constant{id}.
     */
    private static void runPhiFold(String name) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String afterIR = extractAfterIR(output.getOutput(), name);
        int refLoads = countReferenceOccurrences(afterIR, "load atomic");
        Asserts.assertEquals(refLoads, 0,
            name + ": all 'load atomic ptr addrspace' should be folded; got " + refLoads);
    }

    /**
     * PHI of distinct or opaque sources: the data join (a `phi ptr addrspace(1|3)`
     * or, when SimplifyCFG converts the diamond to a select because one arm
     * has no side effects, a `select i1` of pointers) must survive RCF --
     * proves the lattice did NOT fold heterogeneous inputs.
     */
    private static void runPhiNoFold(String name) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String afterIR = extractAfterIR(output.getOutput(), name);
        int phiCount    = countReferenceOccurrences(afterIR, "phi");
        int selectCount = countOccurrences(afterIR, "select i1");
        Asserts.assertGTE(phiCount + selectCount, 1,
            name + ": a conditional data join (phi ptr addrspace(1|3) or select i1) "
                 + "must survive RCF; got phi=" + phiCount + " select=" + selectCount);
    }

    /**
     * Cascading scenario: before-IR has a `phi ptr addrspace(1|3)`, after-IR
     * does not. Proves CFF + SCCP + SimplifyCFG + CFF re-iteration actually
     * unlocked the PHI.
     */
    private static void runCascadingPhiFold(String name) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String beforeIR = extractBeforeIR(output.getOutput(), name);
        String afterIR  = extractAfterIR(output.getOutput(),  name);
        int beforePhi = countReferenceOccurrences(beforeIR, "phi");
        int afterPhi  = countReferenceOccurrences(afterIR, "phi");
        Asserts.assertGTE(beforePhi, 1,
            name + ": expected >= 1 'phi ptr addrspace' before RCF; got " + beforePhi);
        Asserts.assertEquals(afterPhi, 0,
            name + ": 'phi ptr addrspace' should be eliminated by RCF; got " + afterPhi);
        int refLoads = countReferenceOccurrences(afterIR, "load atomic");
        Asserts.assertEquals(refLoads, 0,
            name + ": all per-arm 'load atomic ptr addrspace' should be folded; got " + refLoads);
    }

    /**
     * Negative case: a load that RCF must NOT fold remains in the after-IR.
     */
    private static void runMustSurvive(String name, String mustSurvivePattern) throws Exception {
        OutputAnalyzer output = runTestProcess(name, name);
        output.shouldHaveExitValue(0);
        String afterIR = extractAfterIR(output.getOutput(), name);
        assertIRContains(afterIR, mustSurvivePattern,
            name + ": expected pattern to survive RCF");
    }

    // -------------------------------------------------------------------------
    // runAllTests: one block per scenario.
    // -------------------------------------------------------------------------

    private static void runAllTests() throws Exception {
        // === A. Primitive type coverage =====================================
        // A1: boolean -- both polarities. Catches i8-zero-vs-sign-extension
        //                bugs that flip the predicate.
        runPrimitiveFold("testStaticFinalBooleanTrue",   "i8",  "ret i32 1");
        runPrimitiveFold("testStaticFinalBooleanFalse",  "i8",  "ret i32 0");

        // A2: byte. B_NEG = -1: a sign-extension bug would give 255.
        runPrimitiveFold("testStaticFinalByteNeg",       "i8",  "ret i32 -1");
        runPrimitiveFold("testStaticFinalByteMin",       "i8",  "ret i32 -128");

        // A3: char. C_HI = 0xFFFF: a sign-extension bug would give -1.
        runPrimitiveFold("testStaticFinalCharHigh",      "i16", "ret i32 65535");
        runPrimitiveFold("testStaticFinalCharLow",       "i16", "ret i32 65");

        // A4: short.
        runPrimitiveFold("testStaticFinalShortNeg",      "i16", "ret i32 -1");

        // A5: int.
        runPrimitiveFold("testStaticFinalIntDead",       "i32", "ret i32 -559038737");
        runPrimitiveFold("testStaticFinalIntMin",        "i32", "ret i32 -2147483648");

        // A6: long. Long.MIN_VALUE specifically targets the int-truncation bug.
        runPrimitiveFold("testStaticFinalLongVal",       "i64", "ret i64 81985529216486895");
        runPrimitiveFold("testStaticFinalLongMin",       "i64", "ret i64 -9223372036854775808");

        // A7: float specials. Float.floatToRawIntBits may lower to a runtime
        // call so the return is not necessarily a literal -- we assert only
        // the field-load fold here; runtime value is checked in the child.
        runPrimitiveFoldNoRetCheck("testStaticFinalFloatNaN",       "float");
        runPrimitiveFoldNoRetCheck("testStaticFinalFloatPInf",      "float");
        runPrimitiveFoldNoRetCheck("testStaticFinalFloatNInf",      "float");
        runPrimitiveFoldNoRetCheck("testStaticFinalFloatNZero",     "float");
        runPrimitiveFoldNoRetCheck("testStaticFinalFloatSubnormal", "float");

        // A8: double specials.
        runPrimitiveFoldNoRetCheck("testStaticFinalDoubleNaN", "double");
        runPrimitiveFoldNoRetCheck("testStaticFinalDoubleMax", "double");

        // A9: sub-int fold followed by widening to long.
        runPrimitiveFold("testStaticFinalByteToLong",    "i8",  "ret i64 -1");

        // === B. Static-final references =====================================
        // B1: String.hashCode -- the outer Constants -> STR ref chain folds;
        //     the hashCode() call stays as an invoke.
        {
            OutputAnalyzer out = runTestProcess(
                "testStaticFinalStringHash", "testStaticFinalStringHash");
            out.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(out.getOutput(), "testStaticFinalStringHash");
            int refLoads = countReferenceOccurrences(afterIR, "load atomic");
            Asserts.assertEquals(refLoads, 0,
                "B1: STR reference load should be folded; got " + refLoads);
        }

        // B2: identity compare. CFF folds both loads to globals, but SCCP
        //     cannot collapse `icmp eq` between two separate loads of the
        //     same global (that would require GVN/EarlyCSE, which RCF does
        //     not run). So we only assert the loads were folded.
        runPrimitiveFoldNoRetCheck("testStaticFinalReferenceIdentity", "ptr addrspace");

        // B3: null reference. Both operands of the icmp become LLVM literal
        //     null after CFF, so SCCP DOES fold this case.
        runPrimitiveFold("testStaticFinalNullReference", "ptr addrspace", "ret i32 1");

        // B4: array length. ARR static-final reference must fold; arraylength
        //     itself remains (arrays are rejected by the CFF VM callback).
        {
            OutputAnalyzer out = runTestProcess(
                "testStaticFinalArrayLength", "testStaticFinalArrayLength");
            out.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(out.getOutput(), "testStaticFinalArrayLength");
            int refLoads = countReferenceOccurrences(afterIR, "load atomic");
            Asserts.assertEquals(refLoads, 0,
                "B4: ARR reference load should be folded; got " + refLoads);
        }

        // === D. PHI / Select ================================================
        runPhiFold("testPhiSameConstantTwoArms");
        runPhiNoFold("testPhiTwoDifferentConstants");
        runPhiNoFold("testPhiOneConstantOneOpaque");
        runCascadingPhiFold("testCascadingBranchUnlocksPhi");
        runPhiFold("testSelfCyclePhiLoopReassign");
        runPhiFold("testMutualCyclePhiSameIdFold");
        runPhiNoFold("testMutualCyclePhiDifferentIdNoFold");
        runPhiFold("testNestedTernary");

        // === E. Iteration / convergence ====================================
        // E1: deep chain. The abstract interpreter resolves the static-final
        //     reference walk to a single leaf-class-mirror load, so by the
        //     time RCF runs the only thing left to fold is `i32 LEAF`.
        runPrimitiveFold("testDeepStaticFinalChain",     "i32", "ret i32 5555");
        // E2: two independent cascading PHIs in one function.
        runCascadingPhiFold("testCascadingTwoLevels");

        // === F. Negative cases =============================================
        runMustSurvive("testNonFinalStaticIntNoFold", "load atomic i32");

        // F2: volatile static load is `seq_cst` and never folded.
        {
            OutputAnalyzer out = runTestProcess(
                "testVolatileStaticIntNoFold", "testVolatileStaticIntNoFold");
            out.shouldHaveExitValue(0);
            String afterIR = extractAfterIR(out.getOutput(), "testVolatileStaticIntNoFold");
            assertIRContains(afterIR, "seq_cst",
                "F2: volatile load (seq_cst) must survive RCF");
        }

        // F3: `new IntHolder(...)` produces an opaque oop -> the inner v
        //     load cannot be folded.
        runMustSurvive("testInstanceFieldNonConstantHolderNoFold", "load atomic i32");

        // F4: instance-final field on an untrusted user class (IntHolder).
        //     The outer IBOX reference does fold, but the inner `.v` does not.
        //     See ciField.cpp:trust_final_non_static_fields() -- IntHolder
        //     is not in the trusted set, so ciField::is_constant() returns
        //     false and the CFF VM callback refuses to fold.
        runMustSurvive("testInstanceFinalUserClassNoFold", "load atomic i32");

        // F5: null-guarded ref read. After CFF folds NULLSTR to null,
        //     SCCP folds the compare and SimplifyCFG drops the dead
        //     .length() branch -- leaving a constant -1 return.
        runPrimitiveFold("testGetfieldOnNullStaticRefGuarded", "ptr addrspace", "ret i32 -1");

        // === G. Side effects ===============================================
        // G1: clinit barrier preserved. Child-side asserts clinitCount goes
        //     from 0 to 1; runAllTests only needs to confirm the child
        //     succeeded.
        {
            OutputAnalyzer out = runTestProcess(
                "testClinitSideEffectRuns", "testClinitSideEffectRuns");
            out.shouldHaveExitValue(0);
        }
        // G2: read-after-write of a mutable static must observe the write.
        runMustSurvive("testNonFinalStaticReadAfterWrite", "load atomic i32");

        // === I. Subtle CFF behavior ========================================
        // I1: same field read twice in one method -- both occurrences must
        //     fold, and SCCP collapses the addition to a single literal.
        runPrimitiveFold("testSameFieldReadTwice", "i32", "ret i32 84");

        System.out.println("All RepeatedConstantFolding tests passed.");
    }
}
