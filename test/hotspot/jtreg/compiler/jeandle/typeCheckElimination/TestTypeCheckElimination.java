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

    // 1e. instanceof Object: any obj instanceof Object -> eliminated (true)
    static boolean testInstanceofObject(Object obj) {
        return obj instanceof Object;
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
    // Group 20: Negative constraints from failed type checks (ExcludedKlasses)
    // =========================================================================

    // 20a. Failed instanceof Animal → instanceof Dog should be false
    static boolean testDeniedByFailedCheck(Object obj) {
        if (!(obj instanceof Animal)) {
            return obj instanceof Dog; // Eliminated to false: Dog subtype of excluded Animal
        }
        return true;
    }

    // 20b. Failed instanceof Animal → instanceof Poodle should be false (transitive)
    static boolean testDeniedTransitive(Object obj) {
        if (!(obj instanceof Animal)) {
            return obj instanceof Poodle; // Eliminated to false: Poodle -> Dog -> Animal
        }
        return true;
    }

    // 20c. Failed instanceof Dog does NOT exclude Animal (negative)
    static boolean testDeniedNotApplicable(Object obj) {
        if (!(obj instanceof Dog)) {
            return obj instanceof Animal; // NOT eliminated: Animal is not subtype of Dog
        }
        return true;
    }

    // 20d. Failed instanceof Dog → instanceof Dog should be false (same type)
    static boolean testDeniedSameType(Object obj) {
        if (!(obj instanceof Dog)) {
            return obj instanceof Dog; // Eliminated to false: same excluded type
        }
        return true;
    }

    // 20e. Failed instanceof Barkable (interface) DOES exclude Dog
    // Dog implements Barkable, so if obj is NOT Barkable, it can't be Dog.
    static boolean testDeniedInterfaceNotApplicable(Object obj) {
        if (!(obj instanceof Barkable)) {
            return obj instanceof Dog; // Eliminated: Dog is subtype of excluded Barkable
        }
        return true;
    }

    // =========================================================================
    // Group 21: Array klass type checks
    // =========================================================================

    // 21a. new String[] instanceof String[] → eliminated (new → exact, String is final)
    static boolean testArrayExactFinalElement() {
        Object o = new String[1];
        return o instanceof String[]; // Eliminated: exact String[]
    }

    // 21b. String[] param instanceof Object[] → eliminated (String[] exact, subtype of Object[])
    static boolean testArrayParamFinalElement(String[] s) {
        return s instanceof Object[]; // Eliminated: String[] is subtype of Object[]
    }

    // 21c. Animal[] param instanceof Dog[] → NOT eliminated (Animal not final)
    static boolean testArrayParamNonFinal(Animal[] a) {
        return a instanceof Dog[]; // NOT eliminated: Dog[] subtype of Animal[], a could be Dog[]
    }

    // 21d. new int[] instanceof int[] → eliminated (new → exact, primitive array)
    static boolean testPrimitiveArray() {
        Object o = new int[1];
        return o instanceof int[]; // Eliminated: exact int[]
    }

    // =========================================================================
    // Group 22: Field load exactness (final field types)
    // =========================================================================

    static class StringHolder {
        String stringField;
        StringHolder(String s) { this.stringField = s; }
    }

    // 22a. Field declared as String (final), load and check instanceof String
    static boolean testFinalFieldType(StringHolder holder) {
        Object obj = holder.stringField; // Load has java-klass=String, java-klass-exact
        return obj instanceof String; // Eliminated: exact String is subtype of String
    }

    // =========================================================================
    // Group 23: Return type exactness (final return types)
    // =========================================================================

    static String getAString() { return "hello"; }

    // 23a. Method returns String (final), check instanceof String
    static boolean testFinalReturnType() {
        Object obj = getAString(); // Return has java-klass=String, java-klass-exact
        return obj instanceof String; // Eliminated: exact String is subtype of String
    }

    // =========================================================================
    // Group 24: PHI incoming block branch sharpening
    // When a PHI incoming comes from a block whose branch checks the value's
    // type, that branch should be considered to sharpen the PHI's type.
    // =========================================================================

    // 24a. Direct branch to PHI: true-branch of instanceof goes to merge
    static boolean testPhiIncomingBranchDirect(Object obj) {
        Object x;
        if (obj instanceof Dog) {
            x = obj; // on true-branch, obj is Dog
        } else {
            x = new Dog();
        }
        // x = phi [obj (from instanceof-true), new Dog() (from else)]
        // Both incomings are Dog → instanceof Animal should be eliminated
        return x instanceof Animal;
    }

    // 24b. Loop header branch sharpens back-edge incoming
    static int testPhiLoopHeaderSharpening(Object obj) {
        int sum = 0;
        while (obj instanceof Animal) {
            Animal a = (Animal) obj; // Should be eliminated: dominated by while-condition
            sum += a.id;
            if (sum > 100) break;
            obj = new Dog(); // back-edge: new Dog, which passes while-condition
        }
        return sum;
    }

    // 24c. Negative: incoming branch goes to PHI via false-branch (type denied)
    static boolean testPhiIncomingBranchDenied(Object obj) {
        Object x;
        if (obj instanceof Dog) {
            x = new Cat();
        } else {
            x = obj; // on false-branch, obj is NOT Dog
        }
        // x = phi [new Cat() (true), obj (false)]
        // obj incoming is NOT Dog, but could be Animal → cannot eliminate
        return x instanceof Animal;
    }

    // =========================================================================
    // Group 25: LCA trace result must not be used for negative constraints
    // When a condition is a PHI of different type checks (Dog/Cat), the LCA
    // (Animal) is valid for positive sharpening but NOT for exclusion on the
    // false-branch. Failing one check doesn't mean the obj isn't the LCA type.
    // =========================================================================

    // 25a. PHI of different checks, false-branch should NOT exclude LCA
    static boolean testNoOverExcludingLCA(Object obj, boolean flag) {
        boolean check;
        if (flag) {
            check = obj instanceof Dog;
        } else {
            check = obj instanceof Cat;
        }
        // check = phi [instanceof Dog, ...], [instanceof Cat, ...]
        // LCA(Dog, Cat) = Animal
        if (!check) {
            // On false-branch: one of the checks failed, but obj could still
            // be Animal (e.g., flag=true, obj is Cat → Dog check fails, but
            // obj IS Animal). Must NOT exclude Animal.
            return obj instanceof Animal; // Should NOT be eliminated
        }
        return true;
    }

    // =========================================================================
    // Group 26: LCA-based positive sharpening via PHI
    // When a condition is a PHI of different type checks (Dog/Cat), the LCA
    // (Animal) is valid for positive sharpening on the true-branch. If one
    // check passed, the object IS at least the LCA type.
    // =========================================================================

    // 26a. PHI of different checks, true-branch SHOULD sharpen to LCA
    static boolean testPhiLCAPositiveSharpening(Object obj, boolean flag) {
        boolean check;
        if (flag) {
            check = obj instanceof Dog;
        } else {
            check = obj instanceof Cat;
        }
        // check = phi [instanceof Dog, ...], [instanceof Cat, ...]
        // LCA(Dog, Cat) = Animal
        if (check) {
            // On true-branch: one of Dog/Cat check passed → obj IS Animal
            return obj instanceof Animal; // Should be eliminated
        }
        return false;
    }

    // 26b. PHI of different checks with deeper hierarchy
    static boolean testPhiLCADeeperHierarchy(Object obj, boolean flag) {
        boolean check;
        if (flag) {
            check = obj instanceof Poodle;
        } else {
            check = obj instanceof Cat;
        }
        // LCA(Poodle, Cat) = Animal
        if (check) {
            return obj instanceof Animal; // Should be eliminated
        }
        return false;
    }

    // 26c. PHI of same-type checks (no LCA needed, should still work)
    static boolean testPhiSameKlassPositiveSharpening(Object obj, boolean flag) {
        boolean check;
        if (flag) {
            check = obj instanceof Dog;
        } else {
            check = obj instanceof Dog;
        }
        // Both incomings are Dog — no LCA needed
        if (check) {
            return obj instanceof Animal; // Should be eliminated
        }
        return false;
    }

    // =========================================================================
    // Group 27: And with mixed negation (the bug fix)
    // When a condition is And(instanceof A, NOT instanceof B), on the
    // true-branch obj IS A AND obj IS NOT B. Both constraints should apply.
    // =========================================================================

    // 27a. And(instanceof Animal, NOT instanceof Dog): true-branch confirms
    //      Animal and excludes Dog → subsequent instanceof Dog is false
    static boolean testAndMixedNegation(Object obj) {
        if (obj instanceof Animal && !(obj instanceof Dog)) {
            // obj IS Animal AND obj IS NOT Dog
            return obj instanceof Dog; // Eliminated to false: excluded Dog
        }
        return true;
    }

    // 27b. And(instanceof Animal, NOT instanceof Dog): the instanceof Animal
    //      on the true-branch should still be confirmed (redundant check eliminated)
    static boolean testAndMixedNegationPositive(Object obj) {
        if (obj instanceof Animal && !(obj instanceof Dog)) {
            // obj IS Animal AND obj IS NOT Dog
            Animal a = (Animal) obj; // Eliminated: dominated by instanceof Animal
            return a.id > 0;
        }
        return false;
    }

    // 27c. And of two negated checks (De Morgan): And(NOT instanceof Dog, NOT instanceof Cat)
    //      On true-branch: both checks failed → obj IS NOT Dog AND obj IS NOT Cat
    //      Subsequent instanceof Dog should be eliminated to false
    static boolean testAndBothNegated(Object obj) {
        if (!(obj instanceof Dog) && !(obj instanceof Cat)) {
            // obj IS NOT Dog AND obj IS NOT Cat
            return obj instanceof Dog; // Eliminated to false
        }
        return true;
    }

    // 27d. And of two negated checks: subsequent instanceof Cat also false
    static boolean testAndBothNegatedCat(Object obj) {
        if (!(obj instanceof Dog) && !(obj instanceof Cat)) {
            return obj instanceof Cat; // Eliminated to false
        }
        return true;
    }

    // =========================================================================
    // Group 28: And with only one side matching
    // When only one operand of And traces to a type check, the false-branch
    // constraints must be cleared — the And being false could be due to the
    // non-type-check operand, not the matched one.
    // =========================================================================

    // 28a. And partial match: false-branch must NOT fold
    static boolean testAndPartialMatchFalseBranch(Object obj, boolean sideCond) {
        if ((obj instanceof Dog) && sideCond) {
            return true;
        }
        return obj instanceof Dog; // Must NOT be eliminated to false
    }

    // =========================================================================
    // Group 29: PHI with constant incoming on wrong branch (issue 3)
    //
    // Constant-false incomings must invalidate false-branch type info (could
    // have been taken without any type check). Constant-true incomings must
    // invalidate true-branch type info for the same reason.
    // =========================================================================

    // 29a. PHI constant-false: false-branch must NOT fold
    static boolean testPhiConstantFalseBranch(Object obj, boolean flag) {
        boolean b = flag ? (obj instanceof Dog) : false;
        if (!b) {
            return obj instanceof Dog; // Must NOT be eliminated
        }
        return true;
    }

    // 29b. PHI constant-true: true-branch must NOT fold
    static boolean testPhiConstantTrueBranch(Object obj, boolean flag) {
        boolean b = flag ? true : (obj instanceof Dog);
        if (b) {
            return obj instanceof Dog; // Must NOT be eliminated
        }
        return false;
    }

    // =========================================================================
    // Group 30: typeUnion preserves exclusions across PHI with mixed
    //           known-Klass/unknown-Klass incomings (issue 8 fix)
    //
    // Pattern: y = PHI[new Dog(), null] forces getPhiJavaType(y) to return {}
    // (null incoming is unknown → bail). After a failed instanceof check on y,
    // sharpening gives {Klass=0, ExcludedKlasses={...}}. A second PHI
    // x = PHI[new Cat(), y] triggers typeUnion({Klass=Cat, Exact=true},
    // {Klass=0, ExcludedKlasses={...}}). The fix preserves the exclusion when
    // Cat is provably disjoint from the excluded type.
    // =========================================================================

    // 30a. PHI union preserves class exclusion
    static boolean testPhiUnionExclusionPreserved(boolean flag1, boolean flag2) {
        Object y;
        if (flag1) {
            y = new Dog();
        } else {
            y = null;
        }
        Object x;
        if (flag2) {
            x = new Cat();
        } else {
            if (y instanceof Dog) {
                return true;
            }
            x = y; // NOT Dog, base Klass=0 (from null-containing PHI)
        }
        return x instanceof Dog; // Should fold to false: Cat is not Dog, y is not Dog
    }

    // 30b. PHI union preserves interface exclusion
    static boolean testPhiUnionExclusionPreservedInterface(boolean flag1, boolean flag2) {
        Object y;
        if (flag1) {
            y = new Dog();
        } else {
            y = null;
        }
        Object x;
        if (flag2) {
            x = new Cat(); // Cat does NOT implement Barkable
        } else {
            if (y instanceof Barkable) {
                return true;
            }
            x = y; // NOT Barkable
        }
        return x instanceof Barkable; // Should fold to false: Cat is not Barkable, y is not Barkable
    }

    // =========================================================================
    // Group 31: Exact non-interface class vs interface type checks
    //
    // Verifies that exact concrete class types correctly fold instanceof
    // checks against interfaces — both implemented and unimplemented.
    // Also guards against the TCE fix for interface+Exact soundness.
    // =========================================================================

    // 31a. Exact Cat (doesn't implement Barkable) instanceof Barkable → false
    static boolean testExactClassVsUnimplementedInterface() {
        Object obj = new Cat();
        return obj instanceof Barkable; // Eliminated to false: exact Cat, not subtype of Barkable
    }

    // 31b. Exact Dog (implements Barkable) instanceof Barkable → true
    static boolean testExactClassVsImplementedInterface() {
        Object obj = new Dog();
        return obj instanceof Barkable; // Eliminated to true: exact Dog IS subtype of Barkable
    }

    // =========================================================================
    // Group 32: extractKlassConstant robustness regression tests
    // =========================================================================

    // 32a. Multiple sequential type checks with different klass constants.
    // Exercises extractKlassConstant being called multiple times within one
    // function, each with a different klass constant argument.
    static int testMultipleKlassExtractions(Object obj) {
        if (obj instanceof Animal) {
            if (obj instanceof Dog) {
                if (obj instanceof Poodle) {
                    return 4;
                }
                if (obj instanceof Barkable) { // interface klass constant
                    return 3;
                }
                return 2;
            }
            return 1;
        }
        return 0;
    }

    // 32b. Type check after method call returning typed value.
    // The return value has klass attributes; combined with a subsequent
    // instanceof, this tests klass extraction alongside attribute-based type.
    static Dog createDog() { return new Dog(); }
    static boolean testKlassExtractionAfterCall() {
        Object obj = createDog();
        return obj instanceof Animal; // Eliminated: Dog is subtype of Animal
    }

    // 32c. Type check on array element — array element loads carry type metadata.
    // Animal[] element instanceof Animal → eliminated (element type is Animal).
    static boolean testKlassExtractionWithArray(Animal[] animals) {
        if (animals.length > 0) {
            Object first = animals[0];
            return first instanceof Animal; // Eliminated: Animal[] element IS Animal
        }
        return false;
    }

    // =========================================================================
    // Group 33: Array element load type propagation (aaload metadata)
    // =========================================================================

    // 33a. Final element type: String[] element instanceof String → eliminated (exact)
    static boolean testAaloadFinalElement(String[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof String;
        }
        return false;
    }

    // 33b. Final element type negative: String[] element instanceof Animal → eliminated (false)
    static boolean testAaloadFinalElementNegative(String[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof Animal;
        }
        return false;
    }

    // 33c. Non-final element: Animal[] element instanceof Dog → preserved
    //      Animal is not final, so element could be Dog or not.
    static boolean testAaloadNonFinalElement(Animal[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof Dog;
        }
        return false;
    }

    // 33d. Subtype element: Dog[] element instanceof Animal → eliminated (true)
    static boolean testAaloadSubtypeElement(Dog[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof Animal;
        }
        return false;
    }

    // 33e. Interface element: Dog[] element instanceof Barkable → eliminated (true)
    static boolean testAaloadInterfaceElement(Dog[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof Barkable;
        }
        return false;
    }

    // 33f. Interface array: Barkable[] element instanceof Dog → preserved
    //      Interface arrays have no reliable type metadata (is_unverified_interface).
    static boolean testAaloadInterfaceArray(Barkable[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof Dog;
        }
        return false;
    }

    // 33g. Nested array: String[][] element instanceof String[] → eliminated (exact)
    //      Element type of String[][] is String[], which is effectively final.
    static boolean testAaloadNestedArray(String[][] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            return elem instanceof String[];
        }
        return false;
    }

    // 33h. Array element with dominating type check:
    //      Animal[] param, guard on element instanceof Dog, then instanceof Animal → eliminated
    static int testAaloadWithGuard(Animal[] arr) {
        if (arr.length > 0) {
            Object elem = arr[0];
            if (elem instanceof Dog) {
                return (elem instanceof Animal) ? 2 : 1; // Animal check eliminated
            }
            return 0;
        }
        return -1;
    }

    // 33i. Multiple loads from same array, different checks.
    static int testAaloadMultipleElements(Animal[] arr) {
        if (arr.length > 1) {
            Object first = arr[0];
            Object second = arr[1];
            int r = 0;
            if (first instanceof Animal) r += 1;  // eliminated: Animal[] element IS Animal
            if (second instanceof Dog) r += 2;    // preserved: not all Animals are Dogs
            return r;
        }
        return 0;
    }

    // =========================================================================
    // Group 34: Or with type checks (De Morgan dual of And)
    // When a condition is Or(A, B), on the false-branch BOTH A AND B are false
    // (AllOf semantics). On the true-branch at least one is true (OneOf — weak).
    // =========================================================================

    // 34a. Or(instanceof Dog, instanceof Cat): false-branch excludes both
    //      → subsequent instanceof Dog is false
    static boolean testOrBothPositive(Object obj) {
        if (obj instanceof Dog || obj instanceof Cat) {
            return true;
        }
        // obj IS NOT Dog AND obj IS NOT Cat
        return obj instanceof Dog; // Eliminated to false: excluded Dog
    }

    // 34b. Or(instanceof Dog, instanceof Cat): false-branch also excludes Cat
    static boolean testOrBothPositiveCat(Object obj) {
        if (obj instanceof Dog || obj instanceof Cat) {
            return true;
        }
        // obj IS NOT Dog AND obj IS NOT Cat
        return obj instanceof Cat; // Eliminated to false: excluded Cat
    }

    // 34c. Or(NOT instanceof Dog, NOT instanceof Animal): false-branch means
    //      both negated checks are false → instanceof Dog IS true AND
    //      instanceof Animal IS true → obj IS Dog AND obj IS Animal
    //      → checkcast Animal is safe, instanceof Dog confirmed
    static boolean testOrBothNegated(Object obj) {
        if (!(obj instanceof Dog) || !(obj instanceof Animal)) {
            return false;
        }
        // obj IS Dog AND obj IS Animal
        Animal a = (Animal) obj; // Eliminated: confirmed Animal
        return a.id > 0;
    }

    // 34d. Or(instanceof Dog, instanceof Cat): true-branch has weak info
    //      (OneOf — don't know which) → instanceof Animal NOT eliminated
    static boolean testOrTrueBranchWeak(Object obj) {
        if (obj instanceof Dog || obj instanceof Cat) {
            return obj instanceof Animal; // Must NOT be eliminated
        }
        return false;
    }

    // =========================================================================
    // Group 35: Or with only one side matching
    // When only one operand of Or traces to a type check, the true-branch
    // constraints must be cleared — the Or being true could be due to the
    // non-type-check operand, not the matched one.
    // =========================================================================

    // 35a. Or partial match: true-branch must NOT fold
    static boolean testOrPartialMatchTrueBranch(Object obj, boolean sideCond) {
        if ((obj instanceof Dog) || sideCond) {
            return obj instanceof Dog; // Must NOT be eliminated to true
        }
        return false;
    }

    // =========================================================================
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
        Class.forName("TestTypeCheckElimination$StringHolder");

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
            case "testInstanceofObject":
                Asserts.assertTrue(testInstanceofObject(dog));
                Asserts.assertTrue(testInstanceofObject("hello"));
                Asserts.assertTrue(testInstanceofObject(42));
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
            case "testDeniedByFailedCheck":
                Asserts.assertTrue(testDeniedByFailedCheck(dog));
                Asserts.assertFalse(testDeniedByFailedCheck("hello"));
                break;
            case "testDeniedTransitive":
                Asserts.assertTrue(testDeniedTransitive(dog));
                Asserts.assertFalse(testDeniedTransitive("hello"));
                break;
            case "testDeniedNotApplicable":
                Asserts.assertTrue(testDeniedNotApplicable(dog));
                Asserts.assertTrue(testDeniedNotApplicable(cat));
                break;
            case "testDeniedSameType":
                Asserts.assertTrue(testDeniedSameType(dog));
                Asserts.assertFalse(testDeniedSameType("hello"));
                break;
            case "testDeniedInterfaceNotApplicable":
                Asserts.assertFalse(testDeniedInterfaceNotApplicable(cat));
                Asserts.assertTrue(testDeniedInterfaceNotApplicable(dog));
                break;
            case "testArrayExactFinalElement":
                Asserts.assertTrue(testArrayExactFinalElement());
                break;
            case "testArrayParamFinalElement":
                Asserts.assertTrue(testArrayParamFinalElement(new String[]{"a"}));
                break;
            case "testArrayParamNonFinal":
                Asserts.assertFalse(testArrayParamNonFinal(new Animal[]{cat}));
                Asserts.assertTrue(testArrayParamNonFinal(new Dog[]{dog}));
                break;
            case "testPrimitiveArray":
                Asserts.assertTrue(testPrimitiveArray());
                break;
            case "testFinalFieldType":
                Asserts.assertTrue(testFinalFieldType(new StringHolder("hello")));
                break;
            case "testFinalReturnType":
                Asserts.assertTrue(testFinalReturnType());
                break;
            case "testPhiIncomingBranchDirect":
                Asserts.assertTrue(testPhiIncomingBranchDirect(dog));
                Asserts.assertTrue(testPhiIncomingBranchDirect("hello"));
                break;
            case "testPhiLoopHeaderSharpening":
                Asserts.assertTrue(testPhiLoopHeaderSharpening(dog) > 0);
                Asserts.assertEquals(testPhiLoopHeaderSharpening("hello"), 0);
                break;
            case "testPhiIncomingBranchDenied":
                Asserts.assertFalse(testPhiIncomingBranchDenied("hello"));
                Asserts.assertTrue(testPhiIncomingBranchDenied(dog));
                break;
            case "testNoOverExcludingLCA":
                // flag=true, obj=Cat: Dog check fails, but Cat IS Animal → should return true
                Asserts.assertTrue(testNoOverExcludingLCA(cat, true));
                // flag=false, obj=Dog: Cat check fails, but Dog IS Animal → should return true
                Asserts.assertTrue(testNoOverExcludingLCA(dog, false));
                // flag=true, obj=String: Dog check fails, String is NOT Animal → should return false
                Asserts.assertFalse(testNoOverExcludingLCA("hello", true));
                break;
            case "testPhiLCAPositiveSharpening":
                // flag=true, obj=Dog: Dog check passes → obj IS Animal
                Asserts.assertTrue(testPhiLCAPositiveSharpening(dog, true));
                // flag=false, obj=Cat: Cat check passes → obj IS Animal
                Asserts.assertTrue(testPhiLCAPositiveSharpening(cat, false));
                // flag=true, obj=String: Dog check fails → false
                Asserts.assertFalse(testPhiLCAPositiveSharpening("hello", true));
                break;
            case "testPhiLCADeeperHierarchy":
                // flag=true, obj=Poodle: Poodle check passes → obj IS Animal
                Asserts.assertTrue(testPhiLCADeeperHierarchy(poodle, true));
                // flag=false, obj=Cat: Cat check passes → obj IS Animal
                Asserts.assertTrue(testPhiLCADeeperHierarchy(cat, false));
                // flag=true, obj=String: Poodle check fails → false
                Asserts.assertFalse(testPhiLCADeeperHierarchy("hello", true));
                break;
            case "testPhiSameKlassPositiveSharpening":
                // Both arms check Dog; obj=Dog → true
                Asserts.assertTrue(testPhiSameKlassPositiveSharpening(dog, true));
                Asserts.assertTrue(testPhiSameKlassPositiveSharpening(dog, false));
                // obj=String → false
                Asserts.assertFalse(testPhiSameKlassPositiveSharpening("hello", true));
                break;
            case "testAndMixedNegation":
                // obj=Cat: Cat IS Animal but NOT Dog → Dog check returns false
                Asserts.assertFalse(testAndMixedNegation(cat));
                // obj=Dog: instanceof Animal true, but instanceof Dog also true → guard fails
                Asserts.assertTrue(testAndMixedNegation(dog));
                // obj=String: instanceof Animal fails → guard fails
                Asserts.assertTrue(testAndMixedNegation("hello"));
                break;
            case "testAndMixedNegationPositive":
                Asserts.assertTrue(testAndMixedNegationPositive(cat));
                Asserts.assertFalse(testAndMixedNegationPositive(dog));
                Asserts.assertFalse(testAndMixedNegationPositive("hello"));
                break;
            case "testAndBothNegated":
                // obj=String: not Dog, not Cat → guard true, Dog check false
                Asserts.assertFalse(testAndBothNegated("hello"));
                // obj=Dog: instanceof Dog true → guard fails
                Asserts.assertTrue(testAndBothNegated(dog));
                // obj=Cat: instanceof Cat true → guard fails
                Asserts.assertTrue(testAndBothNegated(cat));
                break;
            case "testAndBothNegatedCat":
                Asserts.assertFalse(testAndBothNegatedCat("hello"));
                Asserts.assertTrue(testAndBothNegatedCat(dog));
                Asserts.assertTrue(testAndBothNegatedCat(cat));
                break;
            case "testAndPartialMatchFalseBranch":
                // obj=Dog, sideCond=false: Dog check passes but side fails → else, Dog check true
                Asserts.assertTrue(testAndPartialMatchFalseBranch(dog, false));
                // obj=String, sideCond=false: Dog check fails → else, Dog check false
                Asserts.assertFalse(testAndPartialMatchFalseBranch("hello", false));
                // obj=Dog, sideCond=true: guard passes → return true
                Asserts.assertTrue(testAndPartialMatchFalseBranch(dog, true));
                break;
            case "testPhiConstantFalseBranch":
                // obj=Dog, flag=true: instanceof Dog → true, !b → false, return true
                Asserts.assertTrue(testPhiConstantFalseBranch(dog, true));
                // obj=Dog, flag=false: b=false, !b → true, instanceof Dog → true
                Asserts.assertTrue(testPhiConstantFalseBranch(dog, false));
                // obj=String, flag=true: instanceof Dog → false, !b → true, instanceof Dog → false
                Asserts.assertFalse(testPhiConstantFalseBranch("hello", true));
                // obj=String, flag=false: b=false, !b → true, instanceof Dog → false
                Asserts.assertFalse(testPhiConstantFalseBranch("hello", false));
                break;
            case "testPhiConstantTrueBranch":
                // obj=Dog, flag=true: b=true, instanceof Dog → true
                Asserts.assertTrue(testPhiConstantTrueBranch(dog, true));
                // obj=Dog, flag=false: instanceof Dog → true, b → true, instanceof Dog → true
                Asserts.assertTrue(testPhiConstantTrueBranch(dog, false));
                // obj=String, flag=true: b=true, instanceof Dog → false
                Asserts.assertFalse(testPhiConstantTrueBranch("hello", true));
                // obj=String, flag=false: instanceof Dog → false, b → false, return false
                Asserts.assertFalse(testPhiConstantTrueBranch("hello", false));
                break;
            case "testPhiUnionExclusionPreserved":
                // (true, true): x=Cat, not Dog → false
                Asserts.assertFalse(testPhiUnionExclusionPreserved(true, true));
                // (true, false): y=Dog, instanceof Dog → true (early return)
                Asserts.assertTrue(testPhiUnionExclusionPreserved(true, false));
                // (false, true): x=Cat, not Dog → false
                Asserts.assertFalse(testPhiUnionExclusionPreserved(false, true));
                // (false, false): y=null, instanceof false, x=null → false
                Asserts.assertFalse(testPhiUnionExclusionPreserved(false, false));
                break;
            case "testPhiUnionExclusionPreservedInterface":
                Asserts.assertFalse(testPhiUnionExclusionPreservedInterface(true, true));
                Asserts.assertTrue(testPhiUnionExclusionPreservedInterface(true, false));
                Asserts.assertFalse(testPhiUnionExclusionPreservedInterface(false, true));
                Asserts.assertFalse(testPhiUnionExclusionPreservedInterface(false, false));
                break;
            case "testExactClassVsUnimplementedInterface":
                Asserts.assertFalse(testExactClassVsUnimplementedInterface());
                break;
            case "testExactClassVsImplementedInterface":
                Asserts.assertTrue(testExactClassVsImplementedInterface());
                break;
            case "testMultipleKlassExtractions":
                Asserts.assertEquals(testMultipleKlassExtractions(new Poodle()), 4);
                Asserts.assertEquals(testMultipleKlassExtractions(new Dog()), 3);
                Asserts.assertEquals(testMultipleKlassExtractions(cat), 1);
                Asserts.assertEquals(testMultipleKlassExtractions("hello"), 0);
                break;
            case "testKlassExtractionAfterCall":
                Asserts.assertTrue(testKlassExtractionAfterCall());
                break;
            case "testKlassExtractionWithArray":
                Asserts.assertTrue(testKlassExtractionWithArray(new Animal[]{dog}));
                Asserts.assertTrue(testKlassExtractionWithArray(new Animal[]{cat}));
                Asserts.assertFalse(testKlassExtractionWithArray(new Animal[]{}));
                break;
            case "testAaloadFinalElement":
                Asserts.assertTrue(testAaloadFinalElement(new String[]{"a"}));
                Asserts.assertFalse(testAaloadFinalElement(new String[]{}));
                break;
            case "testAaloadFinalElementNegative":
                Asserts.assertFalse(testAaloadFinalElementNegative(new String[]{"a"}));
                break;
            case "testAaloadNonFinalElement":
                Asserts.assertTrue(testAaloadNonFinalElement(new Animal[]{dog}));
                Asserts.assertFalse(testAaloadNonFinalElement(new Animal[]{cat}));
                break;
            case "testAaloadSubtypeElement":
                Asserts.assertTrue(testAaloadSubtypeElement(new Dog[]{dog}));
                break;
            case "testAaloadInterfaceElement":
                Asserts.assertTrue(testAaloadInterfaceElement(new Dog[]{dog}));
                break;
            case "testAaloadInterfaceArray":
                Asserts.assertTrue(testAaloadInterfaceArray(new Barkable[]{dog}));
                break;
            case "testAaloadNestedArray":
                Asserts.assertTrue(testAaloadNestedArray(new String[][]{{"a"}}));
                break;
            case "testAaloadWithGuard":
                Asserts.assertEquals(testAaloadWithGuard(new Animal[]{dog}), 2);
                Asserts.assertEquals(testAaloadWithGuard(new Animal[]{cat}), 0);
                break;
            case "testAaloadMultipleElements":
                Asserts.assertEquals(testAaloadMultipleElements(new Animal[]{dog, dog}), 3);
                Asserts.assertEquals(testAaloadMultipleElements(new Animal[]{cat, cat}), 1);
                break;
            case "testOrBothPositive":
                // not Dog, not Cat → guard false, instanceof Dog → false
                Asserts.assertFalse(testOrBothPositive("hello"));
                // Dog → guard true
                Asserts.assertTrue(testOrBothPositive(dog));
                // Cat → guard true
                Asserts.assertTrue(testOrBothPositive(cat));
                break;
            case "testOrBothPositiveCat":
                Asserts.assertFalse(testOrBothPositiveCat("hello"));
                Asserts.assertTrue(testOrBothPositiveCat(dog));
                Asserts.assertTrue(testOrBothPositiveCat(cat));
                break;
            case "testOrBothNegated":
                // IS Dog AND IS Animal → a.id > 0 → true
                Asserts.assertTrue(testOrBothNegated(dog));
                // NOT Dog → guard true (first arm), returns false
                Asserts.assertFalse(testOrBothNegated(cat));
                // NOT Animal → guard true, returns false
                Asserts.assertFalse(testOrBothNegated("hello"));
                break;
            case "testOrTrueBranchWeak":
                // Dog → guard true, instanceof Animal → true
                Asserts.assertTrue(testOrTrueBranchWeak(dog));
                // Cat → guard true, instanceof Animal → true
                Asserts.assertTrue(testOrTrueBranchWeak(cat));
                // not Dog, not Cat → guard false
                Asserts.assertFalse(testOrTrueBranchWeak("hello"));
                break;
            case "testOrPartialMatchTrueBranch":
                // Dog → guard true (first arm), instanceof Dog → true
                Asserts.assertTrue(testOrPartialMatchTrueBranch(dog, false));
                // sideCond true, not Dog → instanceof Dog false
                Asserts.assertFalse(testOrPartialMatchTrueBranch("hello", true));
                // both true
                Asserts.assertTrue(testOrPartialMatchTrueBranch(dog, true));
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "1a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "1a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 1b. Known interface: Dog instanceof Barkable -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testKnownInterface", "testKnownInterface");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testKnownInterface");
            String afterIR = extractAfterIR(fullOutput, "testKnownInterface");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "1b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "1b: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 1c. Unknown type: Object instanceof Animal -> preserved ===
        {
            OutputAnalyzer output = runTestProcess("testUnknownType", "testUnknownType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testUnknownType");
            String afterIR = extractAfterIR(fullOutput, "testUnknownType");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "1c: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "1c: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 1d. Same type cast: String -> (String) -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testSameTypeCast", "testSameTypeCast");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testSameTypeCast");
            String afterIR = extractAfterIR(fullOutput, "testSameTypeCast");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, 0,
                "1d: same-type checkcast should be eliminated, got " + afterCount);
        }

        // === 1e. instanceof Object: always true -> eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testInstanceofObject", "testInstanceofObject");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testInstanceofObject");
            String afterIR = extractAfterIR(fullOutput, "testInstanceofObject");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "1e: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "1e: instanceof Object should be eliminated, got " + afterCount);
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
            String beforeIR = extractBeforeIR(fullOutput, "testDiamondCFG");
            String afterIR = extractAfterIR(fullOutput, "testDiamondCFG");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // The final 'obj instanceof Dog' at the merge point should be preserved
            Asserts.assertGTE(afterCount, 1,
                "2c: check at diamond merge should be preserved, got " + afterCount);
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "3a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "3a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 3b. Diamond PHI with different subtypes ===
        {
            OutputAnalyzer output = runTestProcess("testDiamondPhiDifferentSubtypes", "testDiamondPhiDifferentSubtypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDiamondPhiDifferentSubtypes");
            String afterIR = extractAfterIR(fullOutput, "testDiamondPhiDifferentSubtypes");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "3b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "3b: all check_instanceof should be eliminated, got " + afterCount);
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "4a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "4a: all check_instanceof should be eliminated, got " + afterCount);
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "7a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "7a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 7b. New exact type negative ===
        {
            OutputAnalyzer output = runTestProcess("testNewExactTypeNegative", "testNewExactTypeNegative");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNewExactTypeNegative");
            String afterIR = extractAfterIR(fullOutput, "testNewExactTypeNegative");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "7b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "7b: all check_instanceof should be eliminated to false, got " + afterCount);
        }

        // === 8a. Exact unrelated type ===
        {
            OutputAnalyzer output = runTestProcess("testExactUnrelatedType", "testExactUnrelatedType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testExactUnrelatedType");
            String afterIR = extractAfterIR(fullOutput, "testExactUnrelatedType");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "8a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "8a: all check_instanceof should be eliminated to false, got " + afterCount);
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "13a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "13a: all check_instanceof should be eliminated, got " + afterCount);
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
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "15a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "15a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 15b. Ternary mixed types (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testTernaryMixedTypes", "testTernaryMixedTypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testTernaryMixedTypes");
            String afterIR = extractAfterIR(fullOutput, "testTernaryMixedTypes");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "15b: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 16a. Loop with changing types (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testLoopChangingTypes", "testLoopChangingTypes");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testLoopChangingTypes");
            String afterIR = extractAfterIR(fullOutput, "testLoopChangingTypes");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "16a: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 17a. Mixed new and unknown (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testMixedNewAndUnknown", "testMixedNewAndUnknown");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testMixedNewAndUnknown");
            String afterIR = extractAfterIR(fullOutput, "testMixedNewAndUnknown");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "17a: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
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

        // === 20a. Denied by failed check: !(instanceof Animal) → instanceof Dog = false ===
        {
            OutputAnalyzer output = runTestProcess("testDeniedByFailedCheck", "testDeniedByFailedCheck");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeniedByFailedCheck");
            String afterIR = extractAfterIR(fullOutput, "testDeniedByFailedCheck");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "20a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "20a: instanceof Dog after failed instanceof Animal should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 20b. Denied transitive: !(instanceof Animal) → instanceof Poodle = false ===
        {
            OutputAnalyzer output = runTestProcess("testDeniedTransitive", "testDeniedTransitive");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeniedTransitive");
            String afterIR = extractAfterIR(fullOutput, "testDeniedTransitive");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "20b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "20b: instanceof Poodle after failed instanceof Animal should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 20c. Denied not applicable: !(instanceof Dog) does NOT exclude Animal ===
        {
            OutputAnalyzer output = runTestProcess("testDeniedNotApplicable", "testDeniedNotApplicable");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeniedNotApplicable");
            String afterIR = extractAfterIR(fullOutput, "testDeniedNotApplicable");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "20c: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 20d. Denied same type: !(instanceof Dog) → instanceof Dog = false ===
        {
            OutputAnalyzer output = runTestProcess("testDeniedSameType", "testDeniedSameType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeniedSameType");
            String afterIR = extractAfterIR(fullOutput, "testDeniedSameType");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "20d: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "20d: duplicate instanceof Dog after failed instanceof Dog should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 20e. Denied interface excludes subclass: !(instanceof Barkable) excludes Dog ===
        {
            OutputAnalyzer output = runTestProcess("testDeniedInterfaceNotApplicable", "testDeniedInterfaceNotApplicable");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testDeniedInterfaceNotApplicable");
            String afterIR = extractAfterIR(fullOutput, "testDeniedInterfaceNotApplicable");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "20e: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "20e: instanceof Dog after failed instanceof Barkable should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 21a. Array exact final element: new String[] instanceof String[] ===
        {
            OutputAnalyzer output = runTestProcess("testArrayExactFinalElement", "testArrayExactFinalElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testArrayExactFinalElement");
            String afterIR = extractAfterIR(fullOutput, "testArrayExactFinalElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "21a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "21a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 21b. Array param final element: String[] instanceof Object[] ===
        {
            OutputAnalyzer output = runTestProcess("testArrayParamFinalElement", "testArrayParamFinalElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testArrayParamFinalElement");
            String afterIR = extractAfterIR(fullOutput, "testArrayParamFinalElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "21b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "21b: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 21c. Array param non-final: Animal[] instanceof Dog[] — NOT eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testArrayParamNonFinal", "testArrayParamNonFinal");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testArrayParamNonFinal");
            String afterIR = extractAfterIR(fullOutput, "testArrayParamNonFinal");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "21c: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 21d. Primitive array: new int[] instanceof int[] ===
        {
            OutputAnalyzer output = runTestProcess("testPrimitiveArray", "testPrimitiveArray");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPrimitiveArray");
            String afterIR = extractAfterIR(fullOutput, "testPrimitiveArray");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "21d: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "21d: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 22a. Final field type: String field instanceof String ===
        {
            OutputAnalyzer output = runTestProcess("testFinalFieldType", "testFinalFieldType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testFinalFieldType");
            String afterIR = extractAfterIR(fullOutput, "testFinalFieldType");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "22a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "22a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 23a. Final return type: String return instanceof String ===
        {
            OutputAnalyzer output = runTestProcess("testFinalReturnType", "testFinalReturnType");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testFinalReturnType");
            String afterIR = extractAfterIR(fullOutput, "testFinalReturnType");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "23a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "23a: all check_instanceof should be eliminated, got " + afterCount);
        }

        // === 24a. PHI incoming branch direct: instanceof true-branch to merge ===
        {
            OutputAnalyzer output = runTestProcess("testPhiIncomingBranchDirect", "testPhiIncomingBranchDirect");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiIncomingBranchDirect");
            String afterIR = extractAfterIR(fullOutput, "testPhiIncomingBranchDirect");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // Before: instanceof Dog (guard) + instanceof Animal (check) = 2
            // After: instanceof Dog stays (Object param), instanceof Animal eliminated
            Asserts.assertGTE(beforeCount, 2,
                "24a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "24a: instanceof Animal on PHI with Dog incomings should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 24b. Loop header branch sharpens back-edge incoming ===
        {
            OutputAnalyzer output = runTestProcess("testPhiLoopHeaderSharpening", "testPhiLoopHeaderSharpening");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiLoopHeaderSharpening");
            String afterIR = extractAfterIR(fullOutput, "testPhiLoopHeaderSharpening");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "24b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "24b: checkcast in loop body should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 24c. PHI incoming branch denied: false-branch to merge (negative) ===
        {
            OutputAnalyzer output = runTestProcess("testPhiIncomingBranchDenied", "testPhiIncomingBranchDenied");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiIncomingBranchDenied");
            String afterIR = extractAfterIR(fullOutput, "testPhiIncomingBranchDenied");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "24c: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 25a. LCA trace must not over-exclude on false-branch ===
        {
            OutputAnalyzer output = runTestProcess("testNoOverExcludingLCA", "testNoOverExcludingLCA");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testNoOverExcludingLCA");
            String afterIR = extractAfterIR(fullOutput, "testNoOverExcludingLCA");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // Dog check + Cat check + Animal check = 3. None should be eliminated.
            // LCA(Dog,Cat)=Animal must NOT be excluded on the false-branch.
            Asserts.assertEquals(afterCount, beforeCount,
                "25a: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 26a. PHI LCA positive sharpening: true-branch should eliminate Animal check ===
        {
            OutputAnalyzer output = runTestProcess("testPhiLCAPositiveSharpening", "testPhiLCAPositiveSharpening");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiLCAPositiveSharpening");
            String afterIR = extractAfterIR(fullOutput, "testPhiLCAPositiveSharpening");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // Before: Dog check + Cat check + Animal check = 3
            // After: Dog + Cat stay (guards), Animal check eliminated on true-branch
            Asserts.assertGTE(beforeCount, 3,
                "26a: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "26a: instanceof Animal should be eliminated on true-branch via LCA; before=" + beforeCount + " after=" + afterCount);
        }

        // === 26b. PHI LCA deeper hierarchy: Poodle/Cat → Animal ===
        {
            OutputAnalyzer output = runTestProcess("testPhiLCADeeperHierarchy", "testPhiLCADeeperHierarchy");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiLCADeeperHierarchy");
            String afterIR = extractAfterIR(fullOutput, "testPhiLCADeeperHierarchy");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "26b: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "26b: instanceof Animal should be eliminated on true-branch via LCA; before=" + beforeCount + " after=" + afterCount);
        }

        // === 26c. PHI same klass: both arms Dog, no LCA needed ===
        {
            OutputAnalyzer output = runTestProcess("testPhiSameKlassPositiveSharpening", "testPhiSameKlassPositiveSharpening");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiSameKlassPositiveSharpening");
            String afterIR = extractAfterIR(fullOutput, "testPhiSameKlassPositiveSharpening");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "26c: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "26c: instanceof Animal should be eliminated on true-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 27a. And mixed negation: instanceof Animal && !(instanceof Dog) → Dog eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAndMixedNegation", "testAndMixedNegation");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAndMixedNegation");
            String afterIR = extractAfterIR(fullOutput, "testAndMixedNegation");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Animal + instanceof Dog (guard) + instanceof Dog (inner) = 3
            Asserts.assertGTE(beforeCount, 3,
                "27a: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "27a: instanceof Dog after And guard should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 27b. And mixed negation positive: checkcast Animal eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAndMixedNegationPositive", "testAndMixedNegationPositive");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAndMixedNegationPositive");
            String afterIR = extractAfterIR(fullOutput, "testAndMixedNegationPositive");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Animal + instanceof Dog (guard) + checkcast Animal = 3
            Asserts.assertGTE(beforeCount, 3,
                "27b: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "27b: checkcast Animal after And guard should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 27c. And both negated: !(instanceof Dog) && !(instanceof Cat) → Dog false ===
        {
            OutputAnalyzer output = runTestProcess("testAndBothNegated", "testAndBothNegated");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAndBothNegated");
            String afterIR = extractAfterIR(fullOutput, "testAndBothNegated");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Dog (guard) + instanceof Cat (guard) + instanceof Dog (inner) = 3
            Asserts.assertGTE(beforeCount, 3,
                "27c: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "27c: instanceof Dog after double-negated And should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 27d. And both negated: instanceof Cat also false ===
        {
            OutputAnalyzer output = runTestProcess("testAndBothNegatedCat", "testAndBothNegatedCat");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAndBothNegatedCat");
            String afterIR = extractAfterIR(fullOutput, "testAndBothNegatedCat");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "27d: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "27d: instanceof Cat after double-negated And should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 28a. And partial match: false-branch must NOT fold ===
        {
            OutputAnalyzer output = runTestProcess("testAndPartialMatchFalseBranch", "testAndPartialMatchFalseBranch");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAndPartialMatchFalseBranch");
            String afterIR = extractAfterIR(fullOutput, "testAndPartialMatchFalseBranch");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Dog (guard) + instanceof Dog (after else) = 2
            // The second instanceof Dog must NOT be eliminated on the false-branch
            Asserts.assertGTE(beforeCount, 2,
                "28a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "28a: no check_instanceof should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 29a. PHI constant-false: false-branch must NOT fold ===
        {
            OutputAnalyzer output = runTestProcess("testPhiConstantFalseBranch", "testPhiConstantFalseBranch");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiConstantFalseBranch");
            String afterIR = extractAfterIR(fullOutput, "testPhiConstantFalseBranch");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "29a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "29a: no check_instanceof should be eliminated on false-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 29b. PHI constant-true: true-branch must NOT fold ===
        {
            OutputAnalyzer output = runTestProcess("testPhiConstantTrueBranch", "testPhiConstantTrueBranch");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiConstantTrueBranch");
            String afterIR = extractAfterIR(fullOutput, "testPhiConstantTrueBranch");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "29b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "29b: no check_instanceof should be eliminated on true-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 30a. PHI union preserves class exclusion ===
        {
            OutputAnalyzer output = runTestProcess("testPhiUnionExclusionPreserved", "testPhiUnionExclusionPreserved");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiUnionExclusionPreserved");
            String afterIR = extractAfterIR(fullOutput, "testPhiUnionExclusionPreserved");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "30a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "30a: final instanceof Dog should be eliminated via preserved exclusion; before=" + beforeCount + " after=" + afterCount);
        }

        // === 30b. PHI union preserves interface exclusion ===
        {
            OutputAnalyzer output = runTestProcess("testPhiUnionExclusionPreservedInterface", "testPhiUnionExclusionPreservedInterface");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testPhiUnionExclusionPreservedInterface");
            String afterIR = extractAfterIR(fullOutput, "testPhiUnionExclusionPreservedInterface");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "30b: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "30b: final instanceof Barkable should be eliminated via preserved exclusion; before=" + beforeCount + " after=" + afterCount);
        }

        // === 31a. Exact Cat vs unimplemented Barkable → false ===
        {
            OutputAnalyzer output = runTestProcess("testExactClassVsUnimplementedInterface", "testExactClassVsUnimplementedInterface");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testExactClassVsUnimplementedInterface");
            String afterIR = extractAfterIR(fullOutput, "testExactClassVsUnimplementedInterface");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "31a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "31a: exact Cat instanceof Barkable should be eliminated to false, got " + afterCount);
        }

        // === 31b. Exact Dog vs implemented Barkable → true ===
        {
            OutputAnalyzer output = runTestProcess("testExactClassVsImplementedInterface", "testExactClassVsImplementedInterface");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testExactClassVsImplementedInterface");
            String afterIR = extractAfterIR(fullOutput, "testExactClassVsImplementedInterface");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "31b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "31b: exact Dog instanceof Barkable should be eliminated to true, got " + afterCount);
        }

        // === 32a. Multiple sequential klass extractions ===
        {
            OutputAnalyzer output = runTestProcess("testMultipleKlassExtractions", "testMultipleKlassExtractions");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testMultipleKlassExtractions");
            String afterIR = extractAfterIR(fullOutput, "testMultipleKlassExtractions");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // Animal + Dog + Poodle + Barkable + their checkcasts = many checks
            // Dominated checks (after guards) should be eliminated
            Asserts.assertGTE(beforeCount, 4,
                "32a: should have >= 4 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "32a: dominated checks should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 32b. Klass extraction after method call ===
        {
            OutputAnalyzer output = runTestProcess("testKlassExtractionAfterCall", "testKlassExtractionAfterCall");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testKlassExtractionAfterCall");
            String afterIR = extractAfterIR(fullOutput, "testKlassExtractionAfterCall");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "32b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "32b: Dog return instanceof Animal should be eliminated, got " + afterCount);
        }

        // === 32c. Klass extraction with array element — now eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testKlassExtractionWithArray", "testKlassExtractionWithArray");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testKlassExtractionWithArray");
            String afterIR = extractAfterIR(fullOutput, "testKlassExtractionWithArray");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "32c: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "32c: Animal[] element instanceof Animal should be eliminated, got " + afterCount);
        }

        // === 33a. Final element: String[] element instanceof String → eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadFinalElement", "testAaloadFinalElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadFinalElement");
            String afterIR = extractAfterIR(fullOutput, "testAaloadFinalElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "33a: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "33a: String[] element instanceof String should be eliminated, got " + afterCount);
        }

        // === 33b. Final element negative: String[] element instanceof Animal → eliminated (false) ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadFinalElementNegative", "testAaloadFinalElementNegative");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadFinalElementNegative");
            String afterIR = extractAfterIR(fullOutput, "testAaloadFinalElementNegative");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "33b: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "33b: String[] element instanceof Animal should be eliminated (false), got " + afterCount);
        }

        // === 33c. Non-final element: Animal[] element instanceof Dog → preserved ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadNonFinalElement", "testAaloadNonFinalElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadNonFinalElement");
            String afterIR = extractAfterIR(fullOutput, "testAaloadNonFinalElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "33c: Animal[] element instanceof Dog should be preserved; before=" + beforeCount + " after=" + afterCount);
        }

        // === 33d. Subtype element: Dog[] element instanceof Animal → eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadSubtypeElement", "testAaloadSubtypeElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadSubtypeElement");
            String afterIR = extractAfterIR(fullOutput, "testAaloadSubtypeElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "33d: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "33d: Dog[] element instanceof Animal should be eliminated, got " + afterCount);
        }

        // === 33e. Interface element: Dog[] element instanceof Barkable → eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadInterfaceElement", "testAaloadInterfaceElement");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadInterfaceElement");
            String afterIR = extractAfterIR(fullOutput, "testAaloadInterfaceElement");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "33e: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "33e: Dog[] element instanceof Barkable should be eliminated, got " + afterCount);
        }

        // === 33f. Interface array: Barkable[] element instanceof Dog → preserved ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadInterfaceArray", "testAaloadInterfaceArray");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadInterfaceArray");
            String afterIR = extractAfterIR(fullOutput, "testAaloadInterfaceArray");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertEquals(afterCount, beforeCount,
                "33f: Barkable[] element instanceof Dog should be preserved; before=" + beforeCount + " after=" + afterCount);
        }

        // === 33g. Nested array: String[][] element instanceof String[] → eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadNestedArray", "testAaloadNestedArray");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadNestedArray");
            String afterIR = extractAfterIR(fullOutput, "testAaloadNestedArray");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 1,
                "33g: should have >= 1 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, 0,
                "33g: String[][] element instanceof String[] should be eliminated, got " + afterCount);
        }

        // === 33h. Array element with guard: Dog guard then instanceof Animal → eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadWithGuard", "testAaloadWithGuard");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadWithGuard");
            String afterIR = extractAfterIR(fullOutput, "testAaloadWithGuard");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "33h: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "33h: instanceof Animal after Dog guard should be eliminated; before=" + beforeCount + " after=" + afterCount);
        }

        // === 33i. Multiple elements: Animal instanceof eliminated, Dog instanceof preserved ===
        {
            OutputAnalyzer output = runTestProcess("testAaloadMultipleElements", "testAaloadMultipleElements");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testAaloadMultipleElements");
            String afterIR = extractAfterIR(fullOutput, "testAaloadMultipleElements");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 2,
                "33i: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "33i: Animal check should be eliminated but Dog check preserved; before=" + beforeCount + " after=" + afterCount);
            Asserts.assertGTE(afterCount, 1,
                "33i: Dog check should be preserved, got " + afterCount);
        }

        // === 34a. Or both positive: instanceof Dog || instanceof Cat → Dog eliminated on false-branch ===
        {
            OutputAnalyzer output = runTestProcess("testOrBothPositive", "testOrBothPositive");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testOrBothPositive");
            String afterIR = extractAfterIR(fullOutput, "testOrBothPositive");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Dog (guard) + instanceof Cat (guard) + instanceof Dog (inner) = 3
            Asserts.assertGTE(beforeCount, 3,
                "34a: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "34a: instanceof Dog after Or guard should be eliminated on false-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 34b. Or both positive: instanceof Dog || instanceof Cat → Cat eliminated on false-branch ===
        {
            OutputAnalyzer output = runTestProcess("testOrBothPositiveCat", "testOrBothPositiveCat");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testOrBothPositiveCat");
            String afterIR = extractAfterIR(fullOutput, "testOrBothPositiveCat");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "34b: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "34b: instanceof Cat after Or guard should be eliminated on false-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 34c. Or both negated: !(instanceof Dog) || !(instanceof Animal) → checkcast Animal eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testOrBothNegated", "testOrBothNegated");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testOrBothNegated");
            String afterIR = extractAfterIR(fullOutput, "testOrBothNegated");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Dog (guard) + instanceof Animal (guard) + checkcast Animal = 3
            Asserts.assertGTE(beforeCount, 3,
                "34c: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertLT(afterCount, beforeCount,
                "34c: checkcast Animal after Or guard should be eliminated on false-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 34d. Or true-branch weak: instanceof Animal must NOT be eliminated ===
        {
            OutputAnalyzer output = runTestProcess("testOrTrueBranchWeak", "testOrTrueBranchWeak");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testOrTrueBranchWeak");
            String afterIR = extractAfterIR(fullOutput, "testOrTrueBranchWeak");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            Asserts.assertGTE(beforeCount, 3,
                "34d: should have >= 3 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "34d: no check_instanceof should be eliminated on true-branch; before=" + beforeCount + " after=" + afterCount);
        }

        // === 35a. Or partial match: true-branch must NOT fold ===
        {
            OutputAnalyzer output = runTestProcess("testOrPartialMatchTrueBranch", "testOrPartialMatchTrueBranch");
            output.shouldHaveExitValue(0);
            String fullOutput = output.getOutput();
            String beforeIR = extractBeforeIR(fullOutput, "testOrPartialMatchTrueBranch");
            String afterIR = extractAfterIR(fullOutput, "testOrPartialMatchTrueBranch");
            int beforeCount = countOccurrences(beforeIR, "jeandle.check_instanceof");
            int afterCount = countOccurrences(afterIR, "jeandle.check_instanceof");
            // instanceof Dog (guard) + instanceof Dog (inner) = 2
            Asserts.assertGTE(beforeCount, 2,
                "35a: should have >= 2 check_instanceof before, got " + beforeCount);
            Asserts.assertEquals(afterCount, beforeCount,
                "35a: no check_instanceof should be eliminated on true-branch; before=" + beforeCount + " after=" + afterCount);
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
