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
 * @test TestTypeCheckElimination.java
 * @summary Test TypeCheckElimination pass eliminates redundant type checks
 * @library /test/lib /
 * @run driver TestTypeCheckElimination
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

public class TestTypeCheckElimination {

    // --- Type hierarchy ---
    static class Animal {
        int id = 1;
    }

    interface Barkable {
        void bark();
    }

    static class Dog extends Animal implements Barkable {
        public void bark() { }
    }

    static class Cat extends Animal { }

    static class Poodle extends Dog { }

    // =========================================================================
    // Group 1: Basic type knowledge
    // =========================================================================

    // 1a. Known subclass: Dog instanceof Animal -> eliminated (true)
    static boolean testKnownSubclass(Dog dog) {
        return dog instanceof Animal;
    }

    // 1b. Known interface: Dog instanceof Barkable -> eliminated (true)
    static boolean testKnownInterface(Dog dog) {
        return dog instanceof Barkable;
    }

    // 1c. Unknown type: Object instanceof Animal -> preserved
    static boolean testUnknownType(Object obj) {
        return obj instanceof Animal;
    }

    // 1d. Same type cast: String -> (String) -> eliminated (true)
    static String testSameTypeCast(String s) {
        return (String) s;
    }

    // =========================================================================
    // Group 2: Complex dominator tree scenarios
    // =========================================================================

    // 2a. Simple dominated check: instanceof guard then checkcast
    static Animal testDominatedCast(Object obj) {
        if (obj instanceof Animal) {
            return (Animal) obj;
        }
        return null;
    }

    // 2b. Nested dominated checks: multi-level type narrowing
    static int testNestedDominated(Object obj) {
        if (obj instanceof Animal) {
            if (obj instanceof Dog) {
                Dog d = (Dog) obj;
                return 3 + d.id;
            }
            Animal a = (Animal) obj;
            return 2 + a.id;
        }
        return 1;
    }

    // 2c. Diamond CFG: check NOT eliminated at merge point
    static boolean testDiamondCFG(Object obj, boolean flag) {
        if (flag) {
            if (obj instanceof Animal) {
                return true;
            }
        } else {
            if (obj instanceof String) {
                return true;
            }
        }
        // At merge: obj type is unknown, check should be preserved
        return obj instanceof Dog;
    }

    // 2d. Sequential independent checks on different objects
    static int testSequentialChecks(Object a, Object b) {
        int result = 0;
        if (a instanceof Animal) {
            result += 1;
        }
        if (b instanceof Dog) {
            result += 2;
        }
        return result;
    }

    // 2e. Deep dominator chain: instanceof dominates through multiple blocks
    static int testDeepDominatorChain(Object obj) {
        if (obj instanceof Animal) {
            int x = 1;
            x += 2;
            if (x > 0) {
                x += 3;
                Animal a = (Animal) obj;
                return a.hashCode() + x;
            }
            return x;
        }
        return 0;
    }

    // 2f. Loop with dominated check
    static int testLoopDominated(Object obj) {
        if (obj instanceof Animal) {
            int sum = 0;
            for (int i = 0; i < 10; i++) {
                Animal a = (Animal) obj;
                sum += a.id;
            }
            return sum;
        }
        return 0;
    }

    // 2g. Complex dominator chain
    static boolean testComplexDominated(Object x, Object y, boolean z) {
        Object result;
        if (z) {
            if (!(x instanceof Dog)) {
                return false;
            }
            result = x;
        } else {
            if (!(y instanceof Dog)) {
                return false;
            }
            result = y;
        }
        return result instanceof Animal;
    }

    // =========================================================================
    // Group 3: PHI node scenarios
    // =========================================================================

    // 3a. Diamond PHI with same type on both sides (exact from new)
    static boolean testDiamondPhiSameType(boolean flag) {
        Animal a;
        if (flag) {
            a = new Dog();
        } else {
            a = new Dog();
        }
        return a instanceof Animal; // Eliminated: both incomings are Dog (exact), subtype of Animal
    }

    // 3b. Diamond PHI with different subtypes (both subtypes of target)
    static boolean testDiamondPhiDifferentSubtypes(boolean flag) {
        Animal a;
        if (flag) {
            a = new Dog();
        } else {
            a = new Cat();
        }
        return a instanceof Animal; // Eliminated: LCA(Dog, Cat) = Animal, subtype of Animal
    }

    // 3c. Loop PHI type preservation: loop body always Dog
    static int testLoopPhiType(int n) {
        Animal a = new Dog();
        for (int i = 0; i < n; i++) {
            if (a instanceof Animal) { // Should be eliminated: a is always Dog
                a = new Dog();
            }
        }
        return a.id;
    }

    // =========================================================================
    // Group 4: Interface hierarchy edge cases
    // =========================================================================

    // 4a. Subclass interface inheritance: Poodle -> Dog -> Barkable
    static boolean testSubclassInterfaceInheritance(Poodle p) {
        return p instanceof Barkable; // Eliminated: Poodle extends Dog which implements Barkable
    }

    // =========================================================================
    // Group 5: Null handling
    // =========================================================================

    // 5a. Null instanceof always false (runtime correctness, no crash)
    static boolean testNullInstanceof() {
        Object obj = null;
        return obj instanceof Animal; // false at runtime
    }

    // =========================================================================
    // Group 6: Cascaded type checks
    // =========================================================================

    // 6a. Triple nested instanceof/checkcast
    static int testCascadedChecks(Object obj) {
        if (obj instanceof Animal) {
            if (obj instanceof Dog) {
                if (obj instanceof Poodle) {
                    return 3;
                }
                Dog d = (Dog) obj; // Eliminated: dominated by instanceof Dog
                return 2 + d.id;
            }
            Animal a = (Animal) obj; // Eliminated: dominated by instanceof Animal
            return 1 + a.id;
        }
        return 0;
    }

    // =========================================================================
    // Group 7: Exact type knowledge from new expressions
    // =========================================================================

    // 7a. new produces exact type: new Dog() instanceof Dog -> true
    static boolean testNewExactType() {
        Animal a = new Dog();
        return a instanceof Dog; // Eliminated: exact Dog is subtype of Dog
    }

    // 7b. new produces exact type, negative: new Cat() instanceof Dog -> false
    static boolean testNewExactTypeNegative() {
        Animal a = new Cat();
        return a instanceof Dog; // Eliminated to false: exact Cat is NOT subtype of Dog
    }

    // =========================================================================
    // Group 8: Negative type tests with exact types
    // =========================================================================

    // 8a. Exact unrelated type: new Dog() instanceof Cat -> false
    static boolean testExactUnrelatedType() {
        Object d = new Dog(); // Use Object to bypass Java compiler type check
        return d instanceof Cat; // Eliminated to false: exact Dog, not subtype of Cat
    }

    // =========================================================================
    // Group 9: Negated guard patterns
    // =========================================================================

    // 9a. Negated guard with early return: !(x instanceof T) → return
    // After the guard, x is known to be T on the fall-through path.
    static int testNegatedGuard(Object obj) {
        if (!(obj instanceof Animal)) {
            return -1;
        }
        // Here obj is known to be Animal (false-branch of negated check)
        Animal a = (Animal) obj; // Should be eliminated
        return a.id;
    }

    // 9b. Negated guard with else branch
    static int testNegatedGuardElse(Object obj) {
        if (!(obj instanceof Dog)) {
            return -1;
        } else {
            Dog d = (Dog) obj; // Should be eliminated (else = type confirmed)
            return d.id;
        }
    }

    // =========================================================================
    // Group 10: Checkcast dominates subsequent instanceof
    // =========================================================================

    // 10a. Successful checkcast proves type for subsequent checks
    static boolean testCheckcastDominatesInstanceof(Object obj) {
        Animal a = (Animal) obj; // checkcast to Animal
        return obj instanceof Animal; // Should be eliminated (dominated by checkcast)
    }

    // =========================================================================
    // Group 11: Transitive subtype relationships
    // =========================================================================

    // 11a. Transitive subtype
    static boolean testTransitiveSubtype(Object p) {
        if (p instanceof Poodle) {
            return p instanceof Animal; // Eliminated: exact Poodle is subtype of Animal
        }
        return false;
    }

    // 11b. Transitive non-subtype
    static boolean testTransitiveNonSubtype(Object p) {
        if (p instanceof Poodle) {
            return p instanceof Cat; // Eliminated to false: not subtype of Cat
        }
        return false;
    }

    // =========================================================================
    // Group 12: Redundant and widening checks
    // =========================================================================

    // 12a. Redundant duplicate check: second identical instanceof
    static int testRedundantDuplicateCheck(Object obj) {
        if (obj instanceof Dog) {
            if (obj instanceof Dog) { // Redundant — should be eliminated
                return 2;
            }
            return 1;
        }
        return 0;
    }

    // 12b. Widening check: instanceof Dog then instanceof Animal
    static boolean testWideningCheck(Object obj) {
        if (obj instanceof Dog) {
            return obj instanceof Animal; // Should be eliminated (Dog is subtype of Animal)
        }
        return false;
    }

    // =========================================================================
    // Group 13: Field type metadata
    // =========================================================================

    static class AnimalHolder {
        Dog dogField;
        AnimalHolder(Dog d) { this.dogField = d; }
    }

    // 13a. Field declared as Dog, check instanceof Animal
    static boolean testFieldType(AnimalHolder holder) {
        Object obj = holder.dogField; // Load has !java-klass metadata for Dog
        return obj instanceof Animal; // Should be eliminated (Dog is subtype of Animal)
    }

    // =========================================================================
    // Group 14: Multiple narrowing checks stacked
    // =========================================================================

    // 14a. instanceof Animal → instanceof Dog → checkcast Dog
    static int testStackedNarrowingChecks(Object obj) {
        if (obj instanceof Animal) {
            if (obj instanceof Dog) {
                Dog d = (Dog) obj; // Eliminated: dominated by instanceof Dog
                if (d instanceof Poodle) {
                    Poodle p = (Poodle) d; // Eliminated: dominated by instanceof Poodle
                    return 3 + p.id;
                }
                return 2 + d.id;
            }
            Animal a = (Animal) obj; // Eliminated: dominated by instanceof Animal
            return 1 + a.id;
        }
        return 0;
    }

    // =========================================================================
    // Group 15: Ternary / conditional expression
    // =========================================================================

    // 15a. Both branches are subtypes of target
    static boolean testTernaryBothSubtypes(boolean flag) {
        Object obj = flag ? new Dog() : new Cat();
        return obj instanceof Animal; // Eliminated: LCA(Dog, Cat) = Animal
    }

    // 15b. One branch is NOT a subtype — should NOT be eliminated
    static boolean testTernaryMixedTypes(boolean flag) {
        Object obj = flag ? new Dog() : "hello";
        return obj instanceof Animal; // NOT eliminated: LCA(Dog, String) = Object
    }

    // =========================================================================
    // Group 16: Loop with changing types (negative case)
    // =========================================================================

    // 16a. Loop body changes type each iteration — cannot eliminate
    static boolean testLoopChangingTypes(int n) {
        Object obj = new Dog();
        for (int i = 0; i < n; i++) {
            if (i % 2 == 0) {
                obj = new Dog();
            } else {
                obj = new Cat();
            }
        }
        return obj instanceof Dog; // NOT eliminated: obj could be Dog or Cat
    }

    // =========================================================================
    // Group 17: Mixed new and unknown in PHI (negative case)
    // =========================================================================

    // 17a. One branch new Dog(), other branch unknown Object
    static boolean testMixedNewAndUnknown(Object other, boolean flag) {
        Object obj = flag ? new Dog() : other;
        return obj instanceof Animal; // NOT eliminated: 'other' is unknown Object
    }

    // =========================================================================
    // Group 18: Very deep dominator chain
    // =========================================================================

    // 18a. instanceof guard, many blocks of computation, then checkcast
    static int testVeryDeepDominatorChain(Object obj) {
        if (obj instanceof Animal) {
            int x = 1;
            if (x > 0) x += 2;
            if (x > 1) x += 3;
            if (x > 2) x += 4;
            if (x > 3) x += 5;
            if (x > 4) x += 6;
            Animal a = (Animal) obj; // Should be eliminated: deeply dominated
            return a.id + x;
        }
        return 0;
    }

    // =========================================================================
    // Helper: extract IR section between "IR Dump Before" and "IR Dump After"
    // for a specific function, and the section after "IR Dump After".
    // =========================================================================

    /**
     * Extracts the "IR Dump Before TypeCheckElimination" section from output
     * for the given method name pattern.
     */
    static String extractBeforeIR(String stderr, String methodPattern) {
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump Before TypeCheckElimination") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) {
                break;
            }
            if (inSection) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the "IR Dump After TypeCheckElimination" section from output
     * for the given method name pattern.
     */
    static String extractAfterIR(String stderr, String methodPattern) {
        String[] lines = stderr.split("\\n");
        StringBuilder sb = new StringBuilder();
        boolean inSection = false;
        for (String line : lines) {
            if (line.contains("IR Dump After TypeCheckElimination") &&
                line.contains(methodPattern)) {
                inSection = true;
                continue;
            }
            if (inSection && line.contains("*** IR Dump ")) {
                break;
            }
            if (inSection) {
                sb.append(line).append("\n");
            }
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

    // =========================================================================
    // Main: driver mode (no args) vs child mode (with test name arg)
    // =========================================================================

    public static void main(String[] args) throws Exception {
        // Force-load inner classes so they are available during compilation.
        Class.forName("TestTypeCheckElimination$Animal");
        Class.forName("TestTypeCheckElimination$Dog");
        Class.forName("TestTypeCheckElimination$Cat");
        Class.forName("TestTypeCheckElimination$Poodle");
        Class.forName("TestTypeCheckElimination$Barkable");
        Class.forName("TestTypeCheckElimination$AnimalHolder");

        if (args.length == 0) {
            runAllTests();
        } else {
            runChildTest(args[0]);
        }
    }

    private static void runChildTest(String testName) {
        Dog dog = new Dog();
        Cat cat = new Cat();
        Poodle poodle = new Poodle();

        switch (testName) {
            case "testKnownSubclass":
                Asserts.assertTrue(testKnownSubclass(dog));
                break;
            case "testKnownInterface":
                Asserts.assertTrue(testKnownInterface(dog));
                break;
            case "testUnknownType":
                Asserts.assertTrue(testUnknownType(dog));
                Asserts.assertFalse(testUnknownType("hello"));
                Asserts.assertFalse(testUnknownType(null));
                break;
            case "testSameTypeCast":
                Asserts.assertEquals(testSameTypeCast("hello"), "hello");
                break;
            case "testDominatedCast":
                Asserts.assertEquals(testDominatedCast(dog), dog);
                Asserts.assertNull(testDominatedCast("not animal"));
                break;
            case "testNestedDominated":
                Asserts.assertEquals(testNestedDominated(dog), 3 + dog.id);
                Asserts.assertEquals(testNestedDominated(cat), 2 + cat.id);
                Asserts.assertEquals(testNestedDominated("hello"), 1);
                break;
            case "testDiamondCFG":
                Asserts.assertTrue(testDiamondCFG(dog, true));
                Asserts.assertTrue(testDiamondCFG("hello", false));
                Asserts.assertFalse(testDiamondCFG("hello", true));
                Asserts.assertTrue(testDiamondCFG(dog, false));
                break;
            case "testSequentialChecks":
                Asserts.assertEquals(testSequentialChecks(dog, dog), 3);
                Asserts.assertEquals(testSequentialChecks(dog, cat), 1);
                Asserts.assertEquals(testSequentialChecks("x", dog), 2);
                Asserts.assertEquals(testSequentialChecks("x", "y"), 0);
                break;
            case "testDeepDominatorChain":
                Asserts.assertTrue(testDeepDominatorChain(dog) != 0);
                Asserts.assertEquals(testDeepDominatorChain("hello"), 0);
                break;
            case "testLoopDominated":
                Asserts.assertEquals(testLoopDominated(dog), 10);
                Asserts.assertEquals(testLoopDominated("hello"), 0);
                break;
            case "testComplexDominated":
                Asserts.assertTrue(testComplexDominated(dog, dog, true));
                Asserts.assertTrue(testComplexDominated(dog, dog, false));
                Asserts.assertFalse(testComplexDominated(cat, cat, true));
                Asserts.assertFalse(testComplexDominated(cat, cat, false));
                Asserts.assertFalse(testComplexDominated(cat, dog, true));
                Asserts.assertFalse(testComplexDominated(dog, cat, false));
                break;
            case "testDiamondPhiSameType":
                Asserts.assertTrue(testDiamondPhiSameType(true));
                Asserts.assertTrue(testDiamondPhiSameType(false));
                break;
            case "testDiamondPhiDifferentSubtypes":
                Asserts.assertTrue(testDiamondPhiDifferentSubtypes(true));
                Asserts.assertTrue(testDiamondPhiDifferentSubtypes(false));
                break;
            case "testLoopPhiType":
                Asserts.assertEquals(testLoopPhiType(0), 1);
                Asserts.assertEquals(testLoopPhiType(5), 1);
                break;
            case "testSubclassInterfaceInheritance":
                Asserts.assertTrue(testSubclassInterfaceInheritance(poodle));
                break;
            case "testNullInstanceof":
                Asserts.assertFalse(testNullInstanceof());
                break;
            case "testCascadedChecks":
                Asserts.assertEquals(testCascadedChecks(poodle), 3);
                Asserts.assertEquals(testCascadedChecks(dog), 2 + dog.id);
                Asserts.assertEquals(testCascadedChecks(cat), 1 + cat.id);
                Asserts.assertEquals(testCascadedChecks("hello"), 0);
                break;
            case "testNewExactType":
                Asserts.assertTrue(testNewExactType());
                break;
            case "testNewExactTypeNegative":
                Asserts.assertFalse(testNewExactTypeNegative());
                break;
            case "testExactUnrelatedType":
                Asserts.assertFalse(testExactUnrelatedType());
                break;
            case "testNegatedGuard":
                Asserts.assertEquals(testNegatedGuard(dog), dog.id);
                Asserts.assertEquals(testNegatedGuard("hello"), -1);
                break;
            case "testNegatedGuardElse":
                Asserts.assertEquals(testNegatedGuardElse(dog), dog.id);
                Asserts.assertEquals(testNegatedGuardElse("hello"), -1);
                break;
            case "testCheckcastDominatesInstanceof":
                Asserts.assertTrue(testCheckcastDominatesInstanceof(dog));
                break;
            case "testTransitiveSubtype":
                Asserts.assertTrue(testTransitiveSubtype(poodle));
                break;
            case "testTransitiveNonSubtype":
                Asserts.assertFalse(testTransitiveNonSubtype(poodle));
                break;
            case "testRedundantDuplicateCheck":
                Asserts.assertEquals(testRedundantDuplicateCheck(dog), 2);
                Asserts.assertEquals(testRedundantDuplicateCheck(cat), 0);
                break;
            case "testWideningCheck":
                Asserts.assertTrue(testWideningCheck(dog));
                Asserts.assertFalse(testWideningCheck("hello"));
                break;
            case "testFieldType":
                Asserts.assertTrue(testFieldType(new AnimalHolder(dog)));
                break;
            case "testStackedNarrowingChecks":
                Asserts.assertEquals(testStackedNarrowingChecks(poodle), 3 + poodle.id);
                Asserts.assertEquals(testStackedNarrowingChecks(dog), 2 + dog.id);
                Asserts.assertEquals(testStackedNarrowingChecks(cat), 1 + cat.id);
                Asserts.assertEquals(testStackedNarrowingChecks("hello"), 0);
                break;
            case "testTernaryBothSubtypes":
                Asserts.assertTrue(testTernaryBothSubtypes(true));
                Asserts.assertTrue(testTernaryBothSubtypes(false));
                break;
            case "testTernaryMixedTypes":
                Asserts.assertTrue(testTernaryMixedTypes(true));
                Asserts.assertFalse(testTernaryMixedTypes(false));
                break;
            case "testLoopChangingTypes":
                Asserts.assertTrue(testLoopChangingTypes(0));
                break;
            case "testMixedNewAndUnknown":
                Asserts.assertTrue(testMixedNewAndUnknown(dog, true));
                Asserts.assertTrue(testMixedNewAndUnknown(dog, false));
                Asserts.assertFalse(testMixedNewAndUnknown("hello", false));
                break;
            case "testVeryDeepDominatorChain":
                Asserts.assertTrue(testVeryDeepDominatorChain(dog) > 0);
                Asserts.assertEquals(testVeryDeepDominatorChain("hello"), 0);
                break;
            default:
                throw new IllegalArgumentException("Unknown test: " + testName);
        }
    }

    private static final String LLVM_OPTIONS =
        "-XX:JeandleLLVMOptions=--print-before=type-check-elimination --print-after=type-check-elimination";

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
        cmd.add("-XX:CompileCommand=compileonly,TestTypeCheckElimination::" + compileOnly);
        cmd.add("TestTypeCheckElimination");
        cmd.add(testName);

        ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(cmd);
        return ProcessTools.executeProcess(pb);
    }

    private static void runAllTests() throws Exception {
        // === 1a. Known subclass: Dog instanceof Animal -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testKnownSubclass", "testKnownSubclass");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testKnownSubclass");
            String afterIR = extractAfterIR(fullOutput, "testKnownSubclass");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "1a: check_instanceof should exist before TypeCheckElimination");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "1a: check_instanceof should be eliminated after TypeCheckElimination");
        }

        // === 1b. Known interface: Dog instanceof Barkable -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testKnownInterface", "testKnownInterface");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testKnownInterface");
            String afterIR = extractAfterIR(fullOutput, "testKnownInterface");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "1b: check_instanceof should exist before TypeCheckElimination");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "1b: check_instanceof should be eliminated after TypeCheckElimination");
        }

        // === 1c. Unknown type: Object instanceof Animal -> preserved ===
        {
            OutputAnalyzer output = runTestProcess("testUnknownType", "testUnknownType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testUnknownType");
            String afterIR = extractAfterIR(fullOutput, "testUnknownType");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "1c: check_instanceof should exist before TypeCheckElimination");
            assertIRContains(afterIR, "jeandle.check_instanceof",
                "1c: check_instanceof should be preserved (unknown type)");
        }

        // === 1d. Same type cast: String -> (String) -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testSameTypeCast", "testSameTypeCast");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String afterIR = extractAfterIR(fullOutput, "testSameTypeCast");
            // checkcast to same type should be eliminated
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "1d: same-type checkcast should be eliminated");
        }

        // === 2a. Simple dominated cast ===
        {
            OutputAnalyzer output = runTestProcess("testDominatedCast", "testDominatedCast");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDominatedCast");
            String afterIR = extractAfterIR(fullOutput, "testDominatedCast");
            // Before: should have at least 2 check_instanceof (one for instanceof, one for checkcast)
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "2a: should have >= 2 check_instanceof before elimination, got " + beforeCount);
            // After: the dominated checkcast should be eliminated, but the guard instanceof stays
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertLT(afterCount, beforeCount,
                "2a: some check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 2b. Nested dominated checks ===
        {
            OutputAnalyzer output = runTestProcess("testNestedDominated", "testNestedDominated");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNestedDominated");
            String afterIR = extractAfterIR(fullOutput, "testNestedDominated");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // There should be instanceof Animal, instanceof Dog, checkcast Dog, checkcast Animal = 4 checks
            // After: the two checkcasts (dominated by their guards) should be eliminated
            Asserts.assertGTE(beforeCount, 4,
                "2b: should have >= 4 check_instanceof before elimination, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 2,
                "2b: dominated checkcasts should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 2c. Diamond CFG: final check NOT eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testDiamondCFG", "testDiamondCFG");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String afterIR = extractAfterIR(fullOutput, "testDiamondCFG");
            // The final 'obj instanceof Dog' at the merge point should be preserved
            assertIRContains(afterIR, "jeandle.check_instanceof",
                "2c: check at diamond merge should be preserved");
        }

        // === 2d. Sequential independent checks ===
        {
            OutputAnalyzer output = runTestProcess("testSequentialChecks", "testSequentialChecks");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testSequentialChecks");
            String afterIR = extractAfterIR(fullOutput, "testSequentialChecks");
            // Two independent checks on different objects, neither should be eliminated
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "2d: independent checks should not be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 2e. Deep dominator chain ===
        {
            OutputAnalyzer output = runTestProcess("testDeepDominatorChain", "testDeepDominatorChain");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeepDominatorChain");
            String afterIR = extractAfterIR(fullOutput, "testDeepDominatorChain");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "2e: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "2e: deeply dominated check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 2f. Loop with dominated check ===
        {
            OutputAnalyzer output = runTestProcess("testLoopDominated", "testLoopDominated");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testLoopDominated");
            String afterIR = extractAfterIR(fullOutput, "testLoopDominated");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "2f: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "2f: type check in loop should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 2g. Complex dominator chain ===
        {
            OutputAnalyzer output = runTestProcess("testComplexDominated", "testComplexDominated");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testComplexDominated");
            String afterIR = extractAfterIR(fullOutput, "testComplexDominated");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "2g: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 1,
                "2g: returned check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 3a. Diamond PHI with same type ===
        {
            OutputAnalyzer output = runTestProcess("testDiamondPhiSameType", "testDiamondPhiSameType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDiamondPhiSameType");
            String afterIR = extractAfterIR(fullOutput, "testDiamondPhiSameType");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "3a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "3a: check_instanceof should be eliminated (both incomings are Dog)");
        }

        // === 3b. Diamond PHI with different subtypes ===
        {
            OutputAnalyzer output = runTestProcess("testDiamondPhiDifferentSubtypes", "testDiamondPhiDifferentSubtypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDiamondPhiDifferentSubtypes");
            String afterIR = extractAfterIR(fullOutput, "testDiamondPhiDifferentSubtypes");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "3b: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "3b: check_instanceof should be eliminated (LCA of Dog/Cat is Animal)");
        }

        // === 3c. Loop PHI type ===
        {
            OutputAnalyzer output = runTestProcess("testLoopPhiType", "testLoopPhiType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testLoopPhiType");
            String afterIR = extractAfterIR(fullOutput, "testLoopPhiType");
            System.out.println("Before IR:");
            System.out.println(beforeIR);
            System.out.println("After IR:");
            System.out.println(afterIR);
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "3c: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "3c: loop PHI check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 4a. Subclass interface inheritance ===
        {
            OutputAnalyzer output = runTestProcess("testSubclassInterfaceInheritance", "testSubclassInterfaceInheritance");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testSubclassInterfaceInheritance");
            String afterIR = extractAfterIR(fullOutput, "testSubclassInterfaceInheritance");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "4a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "4a: Poodle instanceof Barkable should be eliminated");
        }

        // === 5a. Null instanceof (runtime correctness) ===
        {
            OutputAnalyzer output = runTestProcess("testNullInstanceof", "testNullInstanceof");
            output.shouldHaveExitValue(0);
        }

        // === 6a. Cascaded checks ===
        {
            OutputAnalyzer output = runTestProcess("testCascadedChecks", "testCascadedChecks");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testCascadedChecks");
            String afterIR = extractAfterIR(fullOutput, "testCascadedChecks");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 5,
                "6a: should have >= 5 check_instanceof before, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 2,
                "6a: dominated checkcasts should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 7a. New exact type ===
        {
            OutputAnalyzer output = runTestProcess("testNewExactType", "testNewExactType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNewExactType");
            String afterIR = extractAfterIR(fullOutput, "testNewExactType");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "7a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "7a: new Dog() instanceof Dog should be eliminated");
        }

        // === 7b. New exact type negative ===
        {
            OutputAnalyzer output = runTestProcess("testNewExactTypeNegative", "testNewExactTypeNegative");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNewExactTypeNegative");
            String afterIR = extractAfterIR(fullOutput, "testNewExactTypeNegative");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "7b: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "7b: new Cat() instanceof Dog should be eliminated to false");
        }

        // === 8a. Exact unrelated type ===
        {
            OutputAnalyzer output = runTestProcess("testExactUnrelatedType", "testExactUnrelatedType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testExactUnrelatedType");
            String afterIR = extractAfterIR(fullOutput, "testExactUnrelatedType");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "8a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "8a: new Dog() instanceof Cat should be eliminated to false");
        }

        // === 9a. Negated guard with early return ===
        {
            OutputAnalyzer output = runTestProcess("testNegatedGuard", "testNegatedGuard");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNegatedGuard");
            String afterIR = extractAfterIR(fullOutput, "testNegatedGuard");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "9a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "9a: checkcast after negated guard should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 9b. Negated guard with else ===
        {
            OutputAnalyzer output = runTestProcess("testNegatedGuardElse", "testNegatedGuardElse");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNegatedGuardElse");
            String afterIR = extractAfterIR(fullOutput, "testNegatedGuardElse");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "9b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "9b: checkcast in else of negated guard should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 10a. Checkcast dominates instanceof ===
        {
            OutputAnalyzer output = runTestProcess("testCheckcastDominatesInstanceof", "testCheckcastDominatesInstanceof");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testCheckcastDominatesInstanceof");
            String afterIR = extractAfterIR(fullOutput, "testCheckcastDominatesInstanceof");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "10a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "10a: instanceof after checkcast should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 11a. Transitive subtype ===
        {
            OutputAnalyzer output = runTestProcess("testTransitiveSubtype", "testTransitiveSubtype");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testTransitiveSubtype");
            String afterIR = extractAfterIR(fullOutput, "testTransitiveSubtype");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "11a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 1,
                "11a: transitive type check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 11b. Transitive non-subtype ===
        {
            OutputAnalyzer output = runTestProcess("testTransitiveNonSubtype", "testTransitiveNonSubtype");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testTransitiveNonSubtype");
            String afterIR = extractAfterIR(fullOutput, "testTransitiveNonSubtype");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "11b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 1,
                "11b: transitive type check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 12a. Redundant duplicate check ===
        {
            OutputAnalyzer output = runTestProcess("testRedundantDuplicateCheck", "testRedundantDuplicateCheck");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testRedundantDuplicateCheck");
            String afterIR = extractAfterIR(fullOutput, "testRedundantDuplicateCheck");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "12a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "12a: redundant duplicate check should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 12b. Widening check after narrowing ===
        {
            OutputAnalyzer output = runTestProcess("testWideningCheck", "testWideningCheck");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testWideningCheck");
            String afterIR = extractAfterIR(fullOutput, "testWideningCheck");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "12b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "12b: widening check (Dog->Animal) should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 13a. Field type metadata ===
        {
            OutputAnalyzer output = runTestProcess("testFieldType", "testFieldType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testFieldType");
            String afterIR = extractAfterIR(fullOutput, "testFieldType");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "13a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "13a: field typed as Dog, instanceof Animal should be eliminated");
        }

        // === 14a. Stacked narrowing checks ===
        {
            OutputAnalyzer output = runTestProcess("testStackedNarrowingChecks", "testStackedNarrowingChecks");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testStackedNarrowingChecks");
            String afterIR = extractAfterIR(fullOutput, "testStackedNarrowingChecks");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Animal, instanceof Dog, checkcast Dog, instanceof Poodle, checkcast Poodle, checkcast Animal = 6
            Asserts.assertGTE(beforeCount, 6,
                "14a: should have >= 6 check_instanceof before, got " + beforeCount);
            Asserts.assertLTE(afterCount, beforeCount - 3,
                "14a: dominated checkcasts should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 15a. Ternary both subtypes ===
        {
            OutputAnalyzer output = runTestProcess("testTernaryBothSubtypes", "testTernaryBothSubtypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testTernaryBothSubtypes");
            String afterIR = extractAfterIR(fullOutput, "testTernaryBothSubtypes");
            assertIRContains(beforeIR, "jeandle.check_instanceof",
                "15a: check_instanceof should exist before");
            assertIRNotContains(afterIR, "jeandle.check_instanceof",
                "15a: ternary with Dog/Cat instanceof Animal should be eliminated");
        }

        // === 15b. Ternary mixed types (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testTernaryMixedTypes", "testTernaryMixedTypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String afterIR = extractAfterIR(fullOutput, "testTernaryMixedTypes");
            assertIRContains(afterIR, "jeandle.check_instanceof",
                "15b: ternary Dog/String instanceof Animal should NOT be eliminated");
        }

        // === 16a. Loop with changing types (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testLoopChangingTypes", "testLoopChangingTypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String afterIR = extractAfterIR(fullOutput, "testLoopChangingTypes");
            assertIRContains(afterIR, "jeandle.check_instanceof",
                "16a: loop with changing types should NOT eliminate check");
        }

        // === 17a. Mixed new and unknown (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testMixedNewAndUnknown", "testMixedNewAndUnknown");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String afterIR = extractAfterIR(fullOutput, "testMixedNewAndUnknown");
            assertIRContains(afterIR, "jeandle.check_instanceof",
                "17a: mixed new Dog/unknown Object should NOT eliminate check");
        }

        // === 18a. Very deep dominator chain ===
        {
            OutputAnalyzer output = runTestProcess("testVeryDeepDominatorChain", "testVeryDeepDominatorChain");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testVeryDeepDominatorChain");
            String afterIR = extractAfterIR(fullOutput, "testVeryDeepDominatorChain");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "18a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "18a: deeply dominated checkcast should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        System.out.println("All TypeCheckElimination tests passed.");
    }

    static int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
}
