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
 * @summary Exact multi-target PEA harness runner, transcript parser, and dump pairing smoke test
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAHarnessSmoke --parser-only
 * @run main/othervm -XX:-UseJeandleCompiler
 *      -XX:-UseCompressedOops -XX:-UseCompressedClassPointers
 *      compiler.jeandle.pea.TestPEAHarnessSmoke
 */

package compiler.jeandle.pea;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

public class TestPEAHarnessSmoke {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAHarnessSmoke$TestWrapper";
    private static final String OSR_WRAPPER =
            "compiler.jeandle.pea.TestPEAHarnessSmoke$OSRIdentityWrapper";
    private static final String NORMAL_DEOPT_WRAPPER =
            "compiler.jeandle.pea.TestPEAHarnessSmoke$NormalDeoptIdentityWrapper";

    public static void main(String[] args) throws Exception {
        Method noArgs = TestWrapper.class.getMethod("test");
        Method complex = TestWrapper.class.getMethod("test", int.class, Point.class,
                Point[][].class, int[].class);
        Method decoy = TestWrapper.class.getMethod("testExtra");

        testMethodIds(noArgs, complex, decoy);
        testPoisonIdentifierRecognition(noArgs);
        testCallableOperandBoundaries(noArgs);
        testSyntheticParser(noArgs, complex, decoy);
        testEffectSequences(noArgs);
        testTypedDeoptParser(noArgs);
        testMalformedDeoptBundles(noArgs);
        testExactAllocationSelection(noArgs);
        testTypedAllocationSitesAndRetention(noArgs);
        testMultilineCallAndInvokeParsing(noArgs);
        testCompleteCallInstructionCollection(noArgs);
        testFlexibleAllocationResultsAndBoundaries(noArgs);
        testCommentCannotSupplyDeoptBundle(noArgs);
        testBlockLocalExactAssertions(noArgs);
        testCrossProcessExactIR(noArgs);
        testExactControlFlowBlocks(noArgs);
        testLoweredAllocationCounting(noArgs);
        testLockReplayParser(noArgs, complex);
        testMalformedTranscripts(noArgs);
        testMalformedLockReplays(noArgs);
        testManagedOptionRejection(noArgs);
        testLockingModes(noArgs);
        testInlineCommandHandling(noArgs, decoy);
        if (List.of(args).contains("--parser-only")) {
            System.out.println("TestPEAHarnessSmoke: parser OK");
            return;
        }
        testMethodIdRunsModesAndStructuralSoundness(noArgs);
        testExecutableDirectivesAndMaxArrayLength(noArgs);
        testActiveFrameArgumentChecks(decoy);
        testNormalActiveFrameOverloads();
        testNotCompilableFailsFast();
        testDumpPairing(noArgs, complex);
        testRealShapeRun(noArgs, complex, decoy);
        testIterationsAndExactEffects(noArgs, decoy);
        PEATestUtils.assertPEAOnOffEquivalent(WRAPPER, noArgs, complex);

        System.out.println("TestPEAHarnessSmoke: harness OK");
    }

    private static void testMethodIds(Method noArgs, Method complex, Method decoy) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.MethodId extra = PEATestUtils.MethodId.of(decoy);
        String stem = TestWrapper.class.getName().replace('.', '_') + "_test";

        Asserts.assertEquals(first.jvmDescriptor(), "()I");
        Asserts.assertEquals(overloaded.jvmDescriptor(),
                "(ILcompiler/jeandle/pea/TestPEAHarnessSmoke$Point;"
                        + "[[Lcompiler/jeandle/pea/TestPEAHarnessSmoke$Point;[I)I");
        Asserts.assertEquals(first.dumpStem(), stem);
        Asserts.assertEquals(first.llvmFunctionName(), stem + "()I");
        Asserts.assertEquals(overloaded.llvmFunctionName(), stem + overloaded.jvmDescriptor());
        Asserts.assertEquals(first.compileCommandPattern(),
                TestWrapper.class.getName() + "::test()I");
        Asserts.assertFalse(first.isOSR());
        Asserts.assertTrue(PEATestUtils.MethodId.osr(noArgs).isOSR());
        Asserts.assertEquals(PEATestUtils.MethodId.osr(noArgs).llvmFunctionName(),
                "__jeandle_osr." + first.llvmFunctionName() + ".root");
        Asserts.assertNotEquals(first.llvmFunctionName(), overloaded.llvmFunctionName());
        Asserts.assertNotEquals(first.dumpStem(), extra.dumpStem());
    }

    private static void testNormalActiveFrameOverloads() throws Exception {
        Method methodTarget = NormalDeoptIdentityWrapper.class.getMethod(
                "methodTarget", int.class);
        Method methodIdTarget = NormalDeoptIdentityWrapper.class.getMethod(
                "methodIdTarget", int.class);
        Method requestMethod = NormalDeoptIdentityWrapper.class.getDeclaredMethod(
                "requestMethod");
        Method requestMethodId = NormalDeoptIdentityWrapper.class.getDeclaredMethod(
                "requestMethodId");
        try (PEATestUtils.RunResult ignored = PEATestUtils.behaviorRun(
                NORMAL_DEOPT_WRAPPER, methodTarget, methodIdTarget)
                .dontinline(requestMethod)
                .dontinline(requestMethodId)
                .run()) {
            // The child validates both overloads against separate active nmethods.
        }
    }

    private static void testMethodIdRunsModesAndStructuralSoundness(Method target)
            throws Exception {
        PEATestUtils.MethodId normal = PEATestUtils.MethodId.rootOf(target);
        PEATestUtils.MethodId osr = PEATestUtils.MethodId.osr(target);

        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, normal).run()) {
            PEATestUtils.PEAReport byId = run.report(normal);
            Asserts.assertEquals(byId.round(0).neverEscapes(), 1);
            PEATestUtils.IRBody frontend = run.frontendIR(normal);
            PEATestUtils.IRBody optimized = run.finalIR(normal);
            Asserts.assertEquals(frontend.methodId(), normal);
            Asserts.assertEquals(optimized.methodId(), normal);
        }

        PEATestUtils.PEAOnOffResult tiered = PEATestUtils.behaviorRun(WRAPPER, normal)
                .tieredCompilation()
                .runPEAOnOffEquivalentWithCommands();
        for (List<String> command : List.of(tiered.onCommand(), tiered.offCommand())) {
            Asserts.assertFalse(command.contains("-XX:-TieredCompilation"));
            Asserts.assertFalse(command.contains("-Xcomp"));
        }

        PEATestUtils.PEAOnOffResult xcomp = PEATestUtils.behaviorRun(WRAPPER, normal)
                .xcomp()
                .runPEAOnOffEquivalentWithCommands();
        for (List<String> command : List.of(xcomp.onCommand(), xcomp.offCommand())) {
            Asserts.assertTrue(command.contains("-XX:-TieredCompilation"));
            Asserts.assertTrue(command.contains("-Xcomp"));
        }
        expectFailure("raw Xcomp execution mode", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-Xcomp"));
        expectFailure("raw Xint execution mode", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-Xint"));
        expectFailure("raw Xmixed execution mode", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-Xmixed"));
        expectFailure("shape Xcomp mode", () -> PEATestUtils.shapeRun(WRAPPER, normal).xcomp());
        expectFailure("tiered and Xcomp modes", () -> PEATestUtils.behaviorRun(WRAPPER, normal)
                .tieredCompilation().xcomp());

        Method osrMethod = OSRIdentityWrapper.class.getMethod("loop");
        Method requestDeopt = OSRIdentityWrapper.class.getDeclaredMethod("requestDeopt");
        PEATestUtils.MethodId osrTarget = PEATestUtils.MethodId.osr(osrMethod);
        try (PEATestUtils.RunResult osrRun = PEATestUtils.behaviorRun(OSR_WRAPPER, osrTarget)
                .dontinline(requestDeopt)
                .extraFlags("-XX:CompileThreshold=1000")
                .run()) {
            List<String> osrCommand = osrRun.command();
            Asserts.assertTrue(osrCommand.contains("-XX:+UseOnStackReplacement"));
            Asserts.assertTrue(osrCommand.contains("-XX:-UseCompressedOops"));
            Asserts.assertTrue(osrCommand.contains("-XX:-UseCompressedClassPointers"));
        }

        PEATestUtils.IRBody complete = bodyWithInstructions(normal,
                "merge: ; preds = %left, %right",
                "%value = phi i32 [ 1, %left ], [ 2, %right ]",
                "ret i32 %value");
        PEATestUtils.assertStructuralSoundness(complete, "complete structural smoke");
        PEATestUtils.IRBody deadPoison = bodyWithInstructions(normal,
                "%v = add i32 poison, 1",
                "%v1 = add i32 0, 1",
                "ret i32 1 ; %v is intentionally dead");
        PEATestUtils.assertStructuralSoundness(deadPoison, "dead poison structural smoke");
        PEATestUtils.IRBody livePoison = bodyWithInstructions(normal,
                "%value = add i32 poison, 1",
                "ret i32 %value");
        expectFailure("live poison structural soundness",
                () -> PEATestUtils.assertStructuralSoundness(
                        livePoison, "live poison structural smoke"));
        PEATestUtils.IRBody quotedLivePoison = bodyWithInstructions(normal,
                "%\"semi;local\" = add i32 poison, 1",
                "%use = call i32 @sink(ptr @\"semi;global\", i32 %\"semi;local\")",
                "ret i32 %use");
        expectFailure("quoted-semicolon live poison structural soundness",
                () -> PEATestUtils.assertStructuralSoundness(
                        quotedLivePoison, "quoted semicolon structural smoke"));
        PEATestUtils.IRBody quotedPoisonText = bodyWithInstructions(normal,
                "%value = call i32 @\"poison;not-a-value\"()",
                "ret i32 1");
        PEATestUtils.assertStructuralSoundness(
                quotedPoisonText, "quoted poison text structural smoke");
    }

    private static void testPoisonIdentifierRecognition(Method target) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(target);
        PEATestUtils.IRBody globalPoison = bodyWithInstructions(id,
                "%value = call i32 @poison()",
                "ret i32 1");
        PEATestUtils.assertStructuralSoundness(globalPoison, "global poison identifier smoke");
        PEATestUtils.IRBody localPoison = bodyWithInstructions(id,
                "%poison = add i32 0, 1",
                "ret i32 %poison");
        PEATestUtils.assertStructuralSoundness(localPoison, "local poison identifier smoke");
        PEATestUtils.IRBody labelPoison = bodyWithInstructions(id,
                "poison:",
                "ret i32 1");
        PEATestUtils.assertStructuralSoundness(labelPoison, "label poison identifier smoke");
        PEATestUtils.IRBody metadataPoison = bodyWithInstructions(id,
                "ret i32 1, !poison !0",
                "!poison = !{}");
        PEATestUtils.assertStructuralSoundness(metadataPoison,
                "named metadata poison identifier smoke");
    }

    private static void testTypedDeoptParser(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        String simple = deoptBundle(11,
                typed(0, 0, 10), "i32 1");
        String rich = "\"deopt\"("
                + "i64 0, i32 44, i32 44, "
                // Emit the array descriptor first to prove topology is independent
                // of descriptor order and supports forward references.
                + typed(1, 4, 13) + ", i64 2000, i32 2, "
                + typed(20, 0, 12) + ", ptr addrspace(1) null, "
                + typed(16, 8, 12) + ", i32 0, "
                + typed(0, 4, 12) + ", i64 1000, i32 2, "
                + typed(16, 0, 10) + ", i32 %scalar, "
                + typed(8, 8, 12) + ", i32 1, "
                + typed(0, 8, 12) + ", i32 0, "
                + typed(1, 0, 10) + ", i32 %local, "
                + typed(2, 0, 12) + ", ptr addrspace(1) null, "
                + typed(3, 0, 12) + ", ptr addrspace(1) %external, "
                + typed(1, 9, 12) + ", i32 1, "
                + typed(1, 3, 12) + ", i32 0, ptr %rootLock, "
                + typed(0, 5, 15) + ", ptr %origPc, "
                + typed(0, 6, 17) + ", i64 777, "
                + "i64 1, i32 55, i32 55, "
                + typed(0, 0, 12) + ", ptr addrspace(1) %inlineExternal, "
                + typed(0, 1, 10) + ", i32 9, "
                + typed(0, 3, 12) + ", ptr addrspace(1) null, ptr %inlineLock)";
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "call void @exact.site() [ " + simple + " ]",
                "call void @exact.site() [ " + rich + " ]",
                "ret i32 1");

        PEATestUtils.DeoptBundle bundle = body.deoptBundleAtCall("exact.site", 1);
        Asserts.assertEquals(bundle.rootScope().bci(), 44);
        Asserts.assertEquals(bundle.rootScope().duplicateBCI(), 44);
        Asserts.assertFalse(bundle.rootScope().shouldReexecute());
        Asserts.assertEquals(bundle.inlineScopes().size(), 1);
        PEATestUtils.DeoptScope inline = bundle.inlineScopes().get(0);
        Asserts.assertEquals(inline.methodOperand(), "i64 777");
        Asserts.assertTrue(inline.shouldReexecute());
        Asserts.assertEquals(inline.bci(), 55);
        Asserts.assertEquals(bundle.scopes(),
                List.of(bundle.rootScope(), bundle.inlineScopes().get(0)));

        Asserts.assertEquals(bundle.rootScope().locals().get(0).kind(),
                PEATestUtils.DeoptValueKind.VO_REF);
        Asserts.assertEquals(bundle.rootScope().locals().get(0).virtualObjectId(), 0);
        Asserts.assertEquals(bundle.rootScope().locals().get(1).kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(bundle.rootScope().locals().get(2).kind(),
                PEATestUtils.DeoptValueKind.NULL);
        Asserts.assertEquals(bundle.rootScope().locals().get(3).kind(),
                PEATestUtils.DeoptValueKind.MATERIALIZED_OOP);
        Asserts.assertEquals(bundle.rootScope().stack().get(0).kind(),
                PEATestUtils.DeoptValueKind.VO_REF);
        Asserts.assertThrows(UnsupportedOperationException.class,
                () -> bundle.rootScope().locals().put(9,
                        bundle.rootScope().locals().get(0)));

        Asserts.assertEquals(bundle.virtualObjects().size(), 2);
        PEATestUtils.VirtualObjectDescriptor instance = bundle.virtualObject(0);
        PEATestUtils.VirtualObjectDescriptor array = bundle.virtualObject(1);
        Asserts.assertEquals(instance.kind(), PEATestUtils.DescriptorKind.INSTANCE);
        Asserts.assertEquals(array.kind(), PEATestUtils.DescriptorKind.ARRAY);
        Asserts.assertEquals(instance.klassOperand(), "i64 1000");
        Asserts.assertEquals(array.klassOperand(), "i64 2000");
        Asserts.assertEquals(instance.fields().get(16).value().kind(),
                PEATestUtils.DeoptValueKind.SCALAR);
        Asserts.assertEquals(array.elements().get(20).value().kind(),
                PEATestUtils.DeoptValueKind.NULL);
        bundle.assertVORef(0, 8, 1);
        bundle.assertVORef(1, 16, 0);
        bundle.assertVirtualObjectIds(0, 1);

        PEATestUtils.DeoptMonitor eliminated = bundle.rootScope().monitors().get(0);
        Asserts.assertTrue(eliminated.eliminated());
        Asserts.assertEquals(eliminated.depth(), 0);
        Asserts.assertEquals(eliminated.owner().kind(),
                PEATestUtils.DeoptValueKind.VO_REF);
        Asserts.assertEquals(eliminated.owner().virtualObjectId(), 0);
        Asserts.assertEquals(eliminated.lockOperand(), "ptr %rootLock");
        PEATestUtils.DeoptMonitor real = inline.monitors().get(0);
        Asserts.assertFalse(real.eliminated());
        Asserts.assertEquals(real.owner().kind(), PEATestUtils.DeoptValueKind.NULL);

        Asserts.assertEquals(body.deoptBundleAtCall("exact.site", 0)
                .rootScope().bci(), 11);
        Asserts.assertEquals(body.callOccurrencesAtBCI("exact.site", 11),
                List.of(0));
        Asserts.assertEquals(body.callOccurrencesAtBCI("exact.site", 44),
                List.of(1));
        Asserts.assertEquals(body.callOccurrencesAtBCI("exact.site", 99),
                List.of());
        expectFailure("missing exact callee",
                () -> body.deoptBundleAtCall("exact.site.extra", 0));
        expectFailure("negative exact call occurrence",
                () -> body.deoptBundleAtCall("exact.site", -1));

        PEATestUtils.IRBody indirect = bodyWithInstructions(id,
                "call void %fp(ptr @exact.site) [ " + deoptBundle(71) + " ]",
                "ret i32 1");
        expectFailure("indirect call argument is not the callee",
                () -> indirect.deoptBundleAtCall("exact.site", 0));

        PEATestUtils.IRBody mixed = bodyWithInstructions(id,
                "call void %fp(ptr @exact.site) [ " + deoptBundle(71) + " ]",
                "call void @exact.site() [ " + deoptBundle(72) + " ]",
                "ret i32 1");
        Asserts.assertEquals(mixed.deoptBundleAtCall("exact.site", 0).rootScope().bci(), 72,
                "an indirect call must not prevent locating the later exact direct call");
    }

    private static void testMalformedDeoptBundles(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        expectDeoptFailure(id, "mismatched duplicated BCI",
                "\"deopt\"(i64 0, i32 1, i32 2)");
        expectDeoptFailure(id, "duplicate virtual-object id",
                "\"deopt\"(i64 0, i32 1, i32 1, "
                        + typed(0, 4, 12) + ", i64 10, i32 0, "
                        + typed(0, 4, 12) + ", i64 11, i32 0)");
        expectDeoptFailure(id, "duplicate descriptor offset",
                "\"deopt\"(i64 0, i32 1, i32 1, "
                        + typed(0, 4, 12) + ", i64 10, i32 2, "
                        + typed(8, 0, 10) + ", i32 1, "
                        + typed(8, 0, 10) + ", i32 2)");
        expectDeoptFailure(id, "dangling VORef",
                "\"deopt\"(i64 0, i32 1, i32 1, "
                        + typed(7, 8, 12) + ", i32 7)");
        expectDeoptFailure(id, "unknown value encoding",
                "\"deopt\"(i64 0, i32 1, i32 1, "
                        + typed(0, 2, 10) + ", i32 7)");
        for (int valueType : List.of(0, 1)) {
            String section = valueType == 0 ? "local" : "stack";
            for (int basicType : List.of(14, 16, 17, 18)) {
                expectDeoptFailure(id, "illegal " + section + " basic type " + basicType,
                        "\"deopt\"(i64 0, i32 1, i32 1, "
                                + typed(0, valueType, basicType) + ", i32 0)");
            }
        }
        expectDeoptFailure(id, "descriptor after root values",
                "\"deopt\"(i64 0, i32 1, i32 1, "
                        + typed(0, 0, 10) + ", i32 7, "
                        + typed(0, 4, 12) + ", i64 10, i32 0)");
        expectDeoptFailure(id, "duplicate deopt bundle",
                "\"deopt\"(i64 0, i32 1, i32 1) ] [ "
                        + "\"deopt\"(i64 0, i32 2, i32 2)");
    }

    private static void testExactAllocationSelection(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "%first = invoke ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(7) + " ] to label %next unwind label %fail",
                "%selected = invoke ptr addrspace(1) @jeandle.new_array() [ "
                        + deoptBundle(19) + " ] to label %next unwind label %fail",
                "ret i32 1");
        Asserts.assertEquals(body.deoptBundleAtAllocation("%selected")
                .rootScope().bci(), 19);
        Asserts.assertEquals(body.deoptBundleAtAllocation("%first")
                .rootScope().bci(), 7);
        expectFailure("missing allocation SSA",
                () -> body.deoptBundleAtAllocation("%missing"));
        expectFailure("allocation result without percent",
                () -> body.deoptBundleAtAllocation("selected"));

        PEATestUtils.IRBody indirect = bodyWithInstructions(id,
                "%indirect = call ptr addrspace(1) %allocfp("
                        + "ptr @jeandle.new_instance) [ " + deoptBundle(23) + " ]",
                "ret i32 1");
        expectFailure("indirect allocation argument is not the callee",
                () -> indirect.deoptBundleAtAllocation("%indirect"));

        PEATestUtils.IRBody ambiguous = bodyWithInstructions(id,
                "%same = invoke ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(7) + " ] to label %next unwind label %fail",
                "%same = invoke ptr addrspace(1) @jeandle.new_array() [ "
                        + deoptBundle(8) + " ] to label %next unwind label %fail",
                "ret i32 1");
        expectFailure("ambiguous allocation SSA",
                () -> ambiguous.deoptBundleAtAllocation("%same"));
    }

    private static void testTypedAllocationSitesAndRetention(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody before = bodyWithInstructions(id,
                "%instance = invoke ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(7) + " ] to label %next unwind label %fail",
                "%array = call ptr addrspace(1) @jeandle.new_array() [ "
                        + deoptBundle(19) + " ]",
                "ret i32 1");
        PEATestUtils.IRBody retained = bodyWithInstructions(id,
                "%instance = invoke ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(7) + " ] to label %next unwind label %fail",
                "ret i32 1");

        List<PEATestUtils.AllocationSite> allocations = before.allocations();
        Asserts.assertEquals(allocations.size(), 2);
        Asserts.assertEquals(allocations.get(0).result(), "%instance");
        Asserts.assertEquals(allocations.get(0).key(), new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 7));
        Asserts.assertEquals(allocations.get(1).result(), "%array");
        Asserts.assertEquals(allocations.get(1).key(), new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.ARRAY, 19));
        retained.assertRetainsExactlyOriginalAllocations(before,
                new PEATestUtils.AllocationKey(PEATestUtils.AllocationKind.INSTANCE, 7));
        expectFailure("retained allocation kind must match original",
                () -> retained.assertRetainsExactlyOriginalAllocations(before,
                        new PEATestUtils.AllocationKey(PEATestUtils.AllocationKind.ARRAY, 7)));
        expectAssertionFailure("retained allocation assertion rejects unrequested source allocation",
                () -> before.assertRetainsExactlyOriginalAllocations(before,
                        new PEATestUtils.AllocationKey(PEATestUtils.AllocationKind.INSTANCE, 7)));
    }

    private static void testMultilineCallAndInvokeParsing(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "%call = call i32 @multiline.call(\n"
                        + "  i32 7,\n"
                        + "  i32 9) [ " + deoptBundle(41) + " ]",
                "%invoke = invoke i32 @multiline.invoke(\n"
                        + "  i32 11,\n"
                        + "  i32 13) [ " + deoptBundle(43) + " ]\n"
                        + "  to label %next unwind label %fail",
                "next:",
                "ret i32 1",
                "fail:",
                "ret i32 0");
        Asserts.assertEquals(body.deoptBundleAtCall("multiline.call", 0).rootScope().bci(), 41);
        Asserts.assertEquals(body.deoptBundleAtCall("multiline.invoke", 0).rootScope().bci(), 43);
        Asserts.assertEquals(body.callOccurrencesAtBCI("multiline.call", 41), List.of(0));
        Asserts.assertEquals(body.callOccurrencesAtBCI("multiline.invoke", 43), List.of(0));
    }

    private static void testCallableOperandBoundaries(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody complete = bodyWithInstructions(id,
                "call void @direct() memory(none) [ \"tag\"(i32 1) ]",
                "call void %indirect() allocsize(0)",
                "call void inttoptr (i64 139956031309536 to ptr)() memory(none)",
                "call void select (i1 true, ptr @left, ptr @right)() memory(none)",
                "call void blockaddress(@function, %block)() allocsize(0)",
                "call void getelementptr inbounds (i8, ptr @base, i64 1)()",
                "call void bitcast (ptr @bitcast.target to ptr)()",
                "call void addrspacecast "
                        + "(ptr addrspace(1) @addrspace.target to ptr)()",
                "call void ptrauth (ptr @ptrauth.target, i32 0)()",
                "call void dso_local_equivalent @dso.target() [ "
                        + deoptBundle(47) + " ]",
                "call void no_cfi @no.cfi.target() [ " + deoptBundle(48) + " ]",
                "call void null()",
                "call void undef()",
                "call void poison()",
                "call void zeroinitializer()",
                "%named.no.cfi = call %no_cfi @named.no.cfi.direct() [ "
                        + deoptBundle(45) + " ]",
                "%named.dso = call %dso_local_equivalent @named.dso.direct() [ "
                        + deoptBundle(46) + " ]",
                "call void asm sideeffect \"\", \"\"() [ \"tag\"() ]",
                "call noundef nonnull ptr @return.attributes() memory(read)",
                "call <4 x i32> @vector.return()",
                "call %ReturnType ()* @typed.direct() [ " + deoptBundle(49) + " ]",
                "ret i32 1");
        Asserts.assertEquals(complete.peaAllocCount(), 0);
        Asserts.assertEquals(
                complete.deoptBundleAtCall("typed.direct", 0).rootScope().bci(), 49);
        Asserts.assertEquals(
                complete.deoptBundleAtCall("named.no.cfi.direct", 0).rootScope().bci(), 45);
        Asserts.assertEquals(
                complete.deoptBundleAtCall("named.dso.direct", 0).rootScope().bci(), 46);
        expectFailure("dso_local_equivalent is not an exact direct global callee",
                () -> complete.deoptBundleAtCall("dso.target", 0));
        expectFailure("no_cfi is not an exact direct global callee",
                () -> complete.deoptBundleAtCall("no.cfi.target", 0));
        for (String wrapped : List.of("bitcast.target", "addrspace.target",
                "ptrauth.target")) {
            expectFailure("constant-expression callee is not exact @" + wrapped,
                    () -> complete.deoptBundleAtCall(wrapped, 0));
        }

        PEATestUtils.IRBody constantInvoke = bodyWithInstructions(id,
                "invoke void ptrauth (ptr @invoke.ptrauth.target, i32 0)() [ "
                        + deoptBundle(50) + " ]"
                        + " to label %next unwind label %fail",
                "next:",
                "ret i32 1",
                "fail:",
                "ret i32 0");
        Asserts.assertEquals(constantInvoke.peaAllocCount(), 0);
        expectFailure("invoke constant-expression callee is not exact direct global",
                () -> constantInvoke.deoptBundleAtCall("invoke.ptrauth.target", 0));

        PEATestUtils.IRBody asmInvoke = bodyWithInstructions(id,
                "invoke void asm unwind \"\", \"\"()"
                        + " to label %next unwind label %fail",
                "next:",
                "ret i32 1",
                "fail:",
                "ret i32 0");
        Asserts.assertEquals(asmInvoke.peaAllocCount(), 0);

        PEATestUtils.IRBody truncatedExpression = bodyWithInstructions(id,
                "call void inttoptr (i64 139956031309536 to ptr) memory(none)",
                "ret i32 1");
        expectFailure("post-call memory attribute cannot complete a truncated inttoptr call",
                truncatedExpression::peaAllocCount);

        PEATestUtils.IRBody truncatedAsm = bodyWithInstructions(id,
                "call void asm sideeffect \"\", \"\" memory(none)",
                "ret i32 1");
        expectFailure("post-call memory attribute cannot complete truncated inline asm",
                truncatedAsm::peaAllocCount);

        PEATestUtils.IRBody truncatedAngle = bodyWithInstructions(id,
                "call <4 x i32 @vector.return()",
                "ret i32 1");
        expectFailure("unterminated angle delimiter",
                truncatedAngle::peaAllocCount);
    }

    private static void testCompleteCallInstructionCollection(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "%split = invoke ptr addrspace(1)\n"
                        + "  @jeandle.new_instance(\n"
                        + "  )\n"
                        + "  #7 nounwind\n"
                        + "  [ " + deoptBundle(51) + " ]\n"
                        + "  to label %next unwind label %fail",
                "next:",
                "%attr = call i32 @attributes.site(\n"
                        + "  i32 3)\n"
                        + "  #8 noinline \"tag\"=\"semi;colon\"\n"
                        + "  [ " + deoptBundle(53) + " ]\n"
                        + "  , !dbg !7",
                "%adjacent = add i32 %attr, 1",
                "ret i32 %adjacent",
                "fail:",
                "ret i32 0");

        List<PEATestUtils.AllocationSite> allocations = body.allocations();
        Asserts.assertEquals(allocations.size(), 1);
        Asserts.assertEquals(allocations.get(0).key(), new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.INSTANCE, 51));
        Asserts.assertFalse(allocations.get(0).instruction().contains("%adjacent"),
                "the collector must stop before the adjacent instruction");
        Asserts.assertEquals(body.deoptBundleAtAllocation("%split").rootScope().bci(), 51);
        Asserts.assertEquals(body.deoptBundleAtCall("attributes.site", 0).rootScope().bci(), 53);
    }

    private static void testFlexibleAllocationResultsAndBoundaries(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "%compact=call ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(61) + " ]",
                "%split =\n"
                        + "  call ptr addrspace(1) @jeandle.new_array() [ "
                        + deoptBundle(63) + " ]",
                "#dbg_value(ptr %split, !1, !DIExpression(), !2)",
                "store ptr addrspace(1) %split, ptr %out",
                "call void @resultless.neighbor()",
                "%single = add i32 1, 2",
                "ret i32 %single");

        List<PEATestUtils.AllocationSite> allocations = body.allocations();
        Asserts.assertEquals(body.peaAllocCount(), 2);
        Asserts.assertEquals(allocations.size(), 2);
        Asserts.assertEquals(allocations.get(0).result(), "%compact");
        Asserts.assertEquals(allocations.get(1).result(), "%split");
        Asserts.assertEquals(allocations.get(1).key(), new PEATestUtils.AllocationKey(
                PEATestUtils.AllocationKind.ARRAY, 63));
        for (PEATestUtils.AllocationSite allocation : allocations) {
            Asserts.assertFalse(allocation.instruction().contains("#dbg_value"));
            Asserts.assertFalse(allocation.instruction().contains("store ptr"));
            Asserts.assertFalse(allocation.instruction().contains("resultless.neighbor"));
            Asserts.assertFalse(allocation.instruction().contains("%single"));
        }
        Asserts.assertEquals(body.deoptBundleAtAllocation("%compact").rootScope().bci(), 61);
        Asserts.assertEquals(body.deoptBundleAtAllocation("%split").rootScope().bci(), 63);

        PEATestUtils.IRBody neighbors = bodyWithInstructions(id,
                "%beforeStore=call ptr addrspace(1) @jeandle.new_instance() [ "
                        + deoptBundle(65) + " ]",
                "store i32 1, ptr %out",
                "%beforeCall=call ptr addrspace(1) @jeandle.new_array() [ "
                        + deoptBundle(66) + " ]",
                "call void @resultless.neighbor()",
                "ret i32 1");
        List<PEATestUtils.AllocationSite> neighborAllocations = neighbors.allocations();
        Asserts.assertEquals(neighborAllocations.size(), 2);
        Asserts.assertFalse(neighborAllocations.get(0).instruction().contains("store i32"));
        Asserts.assertFalse(neighborAllocations.get(1).instruction()
                .contains("resultless.neighbor"));
    }

    private static void testCommentCannotSupplyDeoptBundle(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "call void @comment.site()",
                "; misleading " + deoptBundle(67),
                "store i32 1, ptr %out",
                "ret i32 1");
        expectFailure("comment cannot supply a deopt bundle",
                () -> body.deoptBundleAtCall("comment.site", 0));
    }

    private static void testBlockLocalExactAssertions(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "entry:",
                "%first = add i32 %x, 1",
                "%middle = add i32 %first, 2",
                "%last = add i32 %middle, 3",
                "br label %exit",
                "exit:",
                "ret i32 %last");
        PEATestUtils.IRBlock entry = body.blockContaining("%middle", 0);
        entry.assertPresent("%first");
        entry.assertOccurrenceCount("add i32", 3);
        entry.assertOccurrenceCountBetween("%first", 0, "add i32", "%last", 0, 2);
        entry.assertBetween("%first", 0, "%middle", 0, "%last", 0);
        entry.assertAbsentBetween("%first", 0, "ret i32", "%last", 0);
        expectAssertionFailure("block-local exact count rejects a false positive",
                () -> entry.assertOccurrenceCount("add i32", 2));
        expectAssertionFailure("block-local interval count rejects a false negative",
                () -> entry.assertOccurrenceCountBetween("%first", 0, "add i32", "%last", 0,
                        1));
    }

    private static void testCrossProcessExactIR(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody firstProcess = bodyWithInstructions(id,
                "%first = call \"java-klass\"=\"140069760409616\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 140069760409616 to ptr))"
                        + " [ \"deopt\"(i64 140069760409616, i64 7001) ]",
                "%second = call \"java-klass\"=\"140069760409744\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 140069760409744 to ptr))"
                        + " [ \"deopt\"(i64 140069760409744, i64 7001) ]",
                "ret i32 1");
        PEATestUtils.IRBody secondProcess = bodyWithInstructions(id,
                "%first = call \"java-klass\"=\"139839644114960\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 139839644114960 to ptr))"
                        + " [ \"deopt\"(i64 139839644114960, i64 7001) ]",
                "%second = call \"java-klass\"=\"139839644115088\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 139839644115088 to ptr))"
                        + " [ \"deopt\"(i64 139839644115088, i64 7001) ]",
                "ret i32 1");
        firstProcess.assertCrossProcessExactEquals(secondProcess,
                "klass addresses are process-local");

        PEATestUtils.IRBody changedNonKlassConstant = bodyWithInstructions(id,
                "%first = call \"java-klass\"=\"139839644114960\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 139839644114960 to ptr))"
                        + " [ \"deopt\"(i64 139839644114960, i64 7002) ]",
                "%second = call \"java-klass\"=\"139839644115088\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 139839644115088 to ptr))"
                        + " [ \"deopt\"(i64 139839644115088, i64 7002) ]",
                "ret i32 1");
        expectAssertionFailure("non-klass constants remain exact",
                () -> firstProcess.assertCrossProcessExactEquals(
                        changedNonKlassConstant, "non-klass constants differ"));

        String firstAddress = "140069760409616";
        String secondAddress = "139839644114960";
        expectAssertionFailure("klass digits in an identifier remain exact",
                () -> klassCollisionBody(id, firstAddress,
                        "%collision" + firstAddress + " = add i32 1, 2")
                        .assertCrossProcessExactEquals(
                                klassCollisionBody(id, secondAddress,
                                        "%collision" + secondAddress + " = add i32 1, 2"),
                                "identifier collision"));
        expectAssertionFailure("klass digits in a quoted string remain exact",
                () -> klassCollisionBody(id, firstAddress,
                        "call void asm sideeffect \"" + firstAddress + "\", \"\"()")
                        .assertCrossProcessExactEquals(
                                klassCollisionBody(id, secondAddress,
                                        "call void asm sideeffect \""
                                                + secondAddress + "\", \"\"()"),
                                "quoted string collision"));
        expectAssertionFailure("klass digits in a comment remain exact",
                () -> klassCollisionBody(id, firstAddress,
                        "; process-local text " + firstAddress)
                        .assertCrossProcessExactEquals(
                                klassCollisionBody(id, secondAddress,
                                        "; process-local text " + secondAddress),
                                "comment collision"));
        expectAssertionFailure("negative klass magnitude remains exact",
                () -> klassCollisionBody(id, firstAddress,
                        "call void @negative(i64 -" + firstAddress + ")")
                        .assertCrossProcessExactEquals(
                                klassCollisionBody(id, secondAddress,
                                        "call void @negative(i64 -" + secondAddress + ")"),
                                "negative integer collision"));
    }

    private static void testExactControlFlowBlocks(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "br i1 %ok, label %success, label %\"fallback.block\", !prof !1",
                "success:",
                "ret i32 1",
                "forward:",
                "br label %success",
                "\"fallback.block\":",
                "ret i32 0");
        PEATestUtils.IRBlock entry = body.blockByLabel("entry");
        Asserts.assertEquals(entry.label(), "entry");
        Asserts.assertEquals(entry.lines().get(0), "entry:");
        expectAssertionFailure("IR block lines are immutable",
                () -> entry.lines().add("ret i32 0"));
        Asserts.assertEquals(entry.conditionalBranchTargets(),
                List.of("success", "fallback.block"));
        Asserts.assertEquals(body.blockByLabel("%success").label(), "success");
        Asserts.assertEquals(body.blockByLabel("%\"fallback.block\"").label(),
                "fallback.block");
        Asserts.assertEquals(body.blockByLabel("forward").unconditionalBranchTarget(),
                "success");
        Asserts.assertTrue(body.blockByLabel("forward").isEmptyForwardingBlock());
        Asserts.assertEquals(body.blockByLabel("forward").emptyForwardingTarget(),
                "success");
        expectFailure("unknown exact block label",
                () -> body.blockByLabel("missing"));

        PEATestUtils.IRBody legalSyntax = bodyWithInstructions(id,
                "br i1 %\"condition,with,commas\", label %left, label %right,"
                        + " !dbg !DILocation(line: 1, scope: !11),"
                        + " !prof !{!\"branch_weights\", i32 1, i32 1000} ; branch comment",
                "left:",
                "br label %exit, !nosanitize !{} ; unconditional comment",
                "right:",
                "br label %exit",
                "exit:",
                "ret i32 0");
        Asserts.assertEquals(
                legalSyntax.blockByLabel("entry").conditionalBranchTargets(),
                List.of("left", "right"));
        Asserts.assertEquals(
                legalSyntax.blockByLabel("left").unconditionalBranchTarget(), "exit");
        PEATestUtils.IRBody invalidMetadata = bodyWithInstructions(id,
                "br i1 %condition, label %left, label %right, !dbg garbage",
                "left:",
                "ret i32 1",
                "right:",
                "ret i32 0");
        expectFailure("branch suffix must be an LLVM metadata attachment",
                () -> invalidMetadata.blockByLabel("entry").conditionalBranchTargets());

        String spacedLabel = "space  \tlabel";
        PEATestUtils.IRBody quotedWhitespace = bodyWithInstructions(id,
                "br label %\"space  \tlabel\"",
                "\"space  \tlabel\":",
                "ret i32 0");
        Asserts.assertEquals(
                quotedWhitespace.blockByLabel("%\"space  \tlabel\"").label(),
                spacedLabel);
        Asserts.assertEquals(
                quotedWhitespace.blockByLabel("entry").unconditionalBranchTarget(),
                spacedLabel);

        PEATestUtils.IRBody forwarding = bodyWithInstructions(id,
                "br label %debug.forward",
                "debug.forward:",
                "; ignored comment",
                "#dbg_value(i32 %value, !1, !DIExpression())",
                "br label %exit",
                "side.effect:",
                "store i32 1, ptr %out",
                "br label %exit",
                "exit:",
                "ret i32 0");
        Asserts.assertTrue(
                forwarding.blockByLabel("debug.forward").isEmptyForwardingBlock());
        Asserts.assertEquals(
                forwarding.blockByLabel("debug.forward").emptyForwardingTarget(), "exit");
        Asserts.assertFalse(
                forwarding.blockByLabel("side.effect").isEmptyForwardingBlock());
        expectFailure("a side-effecting block is not an empty forwarding block",
                () -> forwarding.blockByLabel("side.effect").emptyForwardingTarget());

        PEATestUtils.IRBody ambiguous = bodyWithInstructions(id,
                "same:",
                "br label %\"same\"",
                "\"same\":",
                "ret i32 0");
        expectFailure("quoted and unquoted labels normalize before uniqueness checks",
                () -> ambiguous.blockByLabel("same"));

        PEATestUtils.IRBody twoTerminators = bodyWithInstructions(id,
                "br i1 %first, label %left, label %right",
                "br i1 %second, label %left, label %right",
                "left:",
                "ret i32 1",
                "right:",
                "ret i32 0");
        expectFailure("conditional branch must be the exact single terminator",
                () -> twoTerminators.blockByLabel("entry").conditionalBranchTargets());
    }

    private static void testLoweredAllocationCounting(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "%direct=call ptr addrspace(1)\n"
                        + "  @new_instance(ptr %klass, ptr %thread)",
                "%array =\n"
                        + "  invoke token (i64, i32, ptr, i32, i32, ...)\n"
                        + "  @llvm.experimental.gc.statepoint.p0("
                        + "i64 1, i32 0, "
                        + "ptr elementtype(ptr addrspace(1) (ptr, i32, ptr)) @new_array, "
                        + "i32 3, i32 0, ptr %arrayKlass, i32 7, ptr %thread)"
                        + " to label %next unwind label %fail",
                "%instance = call token (i64, i32, ptr, i32, i32, ...)"
                        + " @llvm.experimental.gc.statepoint.p0("
                        + "i64 2, i32 0, "
                        + "ptr elementtype(ptr addrspace(1) (ptr, ptr)) @new_instance, "
                        + "i32 2, i32 0, ptr %klass, ptr %thread)",
                "call void %fp(ptr @new_array)",
                "call void inttoptr (i64 139956031309536 to ptr)\n"
                        + "  (ptr addrspace(1) %object, ptr %card)",
                "call dereferenceable(8) ptr "
                        + "inttoptr (i64 139956031309536 to ptr)()",
                "call void asm sideeffect \"\", \"\"()",
                "call void null()",
                "store i32 1, ptr %out",
                "call void @resultless.lowered.neighbor()",
                "ret i32 1");
        Asserts.assertEquals(body.loweredAllocCount(), 3,
                "direct and statepoint allocation callees are counted exactly"
                        + " while constant-expression indirect calls are skipped");

        PEATestUtils.IRBody truncatedIndirect = bodyWithInstructions(id,
                "call dereferenceable(8) ptr "
                        + "inttoptr (i64 139956031309536 to ptr)",
                "%later = call ptr addrspace(1) @new_instance(ptr %klass, ptr %thread)",
                "ret i32 1");
        expectFailure("return attribute plus balanced indirect callee without arguments",
                truncatedIndirect::loweredAllocCount);

        PEATestUtils.IRBody unterminatedIndirect = bodyWithInstructions(id,
                "call void inttoptr (i64 139956031309536 to ptr",
                "%later = call ptr addrspace(1) @new_instance(ptr %klass, ptr %thread)",
                "ret i32 1");
        expectFailure("unterminated indirect callable operand",
                unterminatedIndirect::loweredAllocCount);
    }

    private static void testSyntheticParser(Method noArgs, Method complex, Method decoy) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.MethodId extra = PEATestUtils.MethodId.of(decoy);

        String transcript = String.join("\r\n",
                before(extra, 0),
                function(extra, "ret i32 99"),
                stats(extra, 9, 9, 9),
                effect("Decoy", extra, 0, "ignored=true"),
                after(extra, 0),
                function(extra, "ret i32 99"),
                before(first, 0),
                function(first, "%alloc = invoke ptr addrspace(1) @jeandle.new_instance()",
                        "%twice = add i32 %x, %x", "ret i32 %twice"),
                stats(first, 1, 0, 0),
                effect("EliminateAllocation", first, 0, "[VO=0]"),
                effect("ReplaceLoad", first, 1, "value=%x"),
                after(first, 0, false),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                before(overloaded, 0),
                function(overloaded, "%alloc = invoke ptr addrspace(1) @jeandle.new_instance()",
                        "ret i32 %arg"),
                stats(overloaded, 1, 0, 0),
                effect("EliminateAllocation", overloaded, 0, "[VO=0]"),
                after(overloaded, 0),
                function(overloaded, "ret i32 %arg"),
                before(first, 1),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                stats(first, 0, 0, 0),
                after(first, 1),
                function(first, "%twice = add i32 %x, %x", "ret i32 %twice"),
                summary(extra, 1, "fixpoint"),
                summary(overloaded, 1, "iteration-cap"),
                summary(first, 2, "fixpoint"),
                "");

        PEATestUtils.PEAReport report = PEATestUtils.PEAReport.parse(
                transcript, first, overloaded);
        Asserts.assertEquals(report.report(first).roundCount(), 2);
        Asserts.assertEquals(report.report(overloaded).roundCount(), 1);
        Asserts.assertEquals(report.report(first).round(0).neverEscapes(), 1);
        Asserts.assertEquals(report.report(first).round(1).neverEscapes(), 0);
        Asserts.assertEquals(report.report(first).effects("EliminateAllocation").size(), 1);
        Asserts.assertEquals(report.report(overloaded).effects("EliminateAllocation").size(), 1);
        Asserts.assertEquals(report.report(first).stopReason(),
                PEATestUtils.PEAStopReason.FIXPOINT);
        Asserts.assertEquals(report.report(overloaded).stopReason(),
                PEATestUtils.PEAStopReason.ITERATION_CAP);
        Asserts.assertEquals(report.report(first).transformChangedRoundCount(), 1);
        Asserts.assertEquals(report.report(first).transformIdleRoundCount(), 1);
        report.report(first).assertFinalTransformIdle();
        report.report(first).assertStoppedAtFixpoint();
        report.report(overloaded).assertStoppedAtIterationCap();
        expectFailure("fixpoint assertion rejects iteration cap",
                () -> report.report(overloaded).assertStoppedAtFixpoint());
        expectFailure("iteration-cap assertion rejects fixpoint",
                () -> report.report(first).assertStoppedAtIterationCap());

        PEATestUtils.IRBody before = report.report(first).round0Before();
        PEATestUtils.IRBody after = report.report(first).finalAfter();
        Asserts.assertEquals(before.peaAllocCount(), 1);
        Asserts.assertEquals(before.loweredAllocCount(), 0);
        Asserts.assertEquals(after.peaAllocCount(), 0);
        Asserts.assertEquals(before.lineCount("add i32"), 1);
        Asserts.assertEquals(before.occurrenceCount("%x"), 2);
        before.assertBefore("%alloc", 0, "%twice", 0);
        before.assertBetween("%alloc", 0, "%x", 1, "ret i32", 0);
        before.assertAbsentBetween("%alloc", 0, "does.not.exist", "ret i32", 0);
        after.assertAbsent("jeandle.new_instance");
        Asserts.assertFalse(transcript.contains("function=@" + first.llvmFunctionName()),
                "Descriptor-bearing LLVM operands must exercise quoted parsing");
    }

    private static void testEffectSequences(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.PEARound valid = parseEffectTranscript(id,
                effectWithoutSequence("Materialize", id,
                        "[VO=0] block=%left target= seq=3   "
                                + "%value = call i32 asm \" seq=77 target= seq=88 \", \"\"()"),
                effectWithSequenceText("CreatePHI", id, "4294967295",
                        "[VO=1] offset=8"));
        Asserts.assertEquals(valid.effects().size(), 2);
        PEATestUtils.PEARound repeatedText = parseEffectTranscript(id,
                effect("Materialize", id, 1, "[VO=0] block=%left"),
                effect("Materialize", id, 2, "[VO=0] block=%left"));
        Asserts.assertEquals(repeatedText.effects().size(), 2,
                "different effects may have identical diagnostic text");
        PEATestUtils.PEAReport perRound = parseTwoRoundEffectTranscript(id);
        Asserts.assertEquals(perRound.round(0).effects().get(0).sequence(), 0L);
        Asserts.assertEquals(perRound.round(1).effects().get(0).sequence(), 0L);
        Asserts.assertEquals(parseEffectTranscript(id,
                "PEA: FutureEffect debug payload without typed grammar")
                .effects().size(), 0,
                "unknown future PEA diagnostics remain forward compatible");

        expectFailureContains("known effect missing function", "malformed",
                () -> parseEffectTranscript(id,
                        "PEA: ReplaceLoad [VO=0] seq=0"));
        expectFailureContains("known effect tab boundary missing function", "malformed",
                () -> parseEffectTranscript(id,
                        "PEA: ReplaceCall\t[VO=0] seq=0"));
        expectFailureContains("known effect invalid function", "malformed",
                () -> parseEffectTranscript(id,
                        "PEA: Materialize function=not-an-llvm-operand"
                                + " [VO=0] seq=0"));
        expectFailureContains("duplicate effect sequence", "duplicate effect sequence",
                () -> parseEffectTranscript(id,
                        effect("Materialize", id, 4, "[VO=0] block=%left"),
                        effect("CreatePHI", id, 4, "[VO=1] offset=8")));
        expectFailureContains("inverted effect sequence", "strictly increasing",
                () -> parseEffectTranscript(id,
                        effect("Materialize", id, 5, "[VO=0] block=%left"),
                        effect("CreatePHI", id, 4, "[VO=1] offset=8")));
        expectFailureContains("missing effect sequence", "missing seq=",
                () -> parseEffectTranscript(id,
                        effectWithoutSequence("Materialize", id,
                                "[VO=0] block=%left")));
        expectFailureContains("malformed effect sequence", "malformed seq=",
                () -> parseEffectTranscript(id,
                        effectWithSequenceText("Materialize", id, "invalid",
                                "[VO=0] block=%left")));
        expectFailureContains("duplicate effect sequence field", "duplicate seq= field",
                () -> parseEffectTranscript(id,
                        effectWithoutSequence("Materialize", id,
                                "[VO=0] seq=1 block=%left seq=2")));
        expectFailureContains("misplaced target effect sequence", "must follow target=",
                () -> parseEffectTranscript(id,
                        effectWithoutSequence("Materialize", id,
                                "[VO=0] target=%value seq=1")));
        expectFailureContains("duplicate pre-target effect sequence",
                "duplicate seq= field before target=",
                () -> parseEffectTranscript(id,
                        effectWithoutSequence("Materialize", id,
                                "[VO=0] seq=9 target= seq=10 %value = load i32, ptr %field")));
        expectFailureContains("non-final targetless effect sequence", "must be final",
                () -> parseEffectTranscript(id,
                        effectWithoutSequence("CreatePHI", id,
                                "[VO=0] seq=1 offset=8")));
        expectFailureContains("negative effect sequence", "non-negative",
                () -> parseEffectTranscript(id,
                        effectWithSequenceText("Materialize", id, "-1",
                                "[VO=0] block=%left")));
        expectFailureContains("overflowing effect sequence", "overflows uint32",
                () -> parseEffectTranscript(id,
                        effectWithSequenceText("Materialize", id, "4294967296",
                                "[VO=0] block=%left")));
        Asserts.assertEquals(valid.effects().get(0).sequence(), 3L);
        Asserts.assertEquals(valid.effects().get(1).sequence(), 0xFFFF_FFFFL);
        Asserts.assertEquals(valid.effects().get(0).detail(),
                "[VO=0] block=%left target=  "
                        + "%value = call i32 asm \" seq=77 target= seq=88 \", \"\"()",
                "typed effect detail exactly restores the pre-sequence producer form");
        Asserts.assertTrue(valid.effects().get(0).detail().contains("seq=77"),
                "removing seq= preserves the target instruction detail");
    }

    private static void testMalformedTranscripts(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        PEATestUtils.MethodId other = PEATestUtils.MethodId.osr(method);
        String body = function(id, "ret i32 1");
        String stat = stats(id, 0, 0, 0);

        expectFailure("missing after marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat), id));
        expectFailure("duplicate before marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, before(id, 0), body,
                        after(id, 0), body), id));
        expectFailure("duplicate after marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, after(id, 0), body,
                        after(id, 0), body), id));
        expectFailure("missing transform-idle flag", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat,
                        ";; PEA-DUMP after iter=0 function " + id.llvmFunctionName(), body), id));
        expectFailure("missing stats for active round", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body,
                        after(id, 0, false), body), id));
        PEATestUtils.PEAReport.parse(String.join("\n",
                before(id, 0), body, after(id, 0), body,
                summary(id, 1, "fixpoint")), id)
                .report(id).assertFinalTransformIdle();
        expectFailure("gapped rounds", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat, after(id, 0), body,
                        before(id, 2), body, stat, after(id, 2), body), id));
        expectFailure("active final transform", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat,
                        after(id, 0, false), body, summary(id, 1, "iteration-cap")), id)
                .report(id).assertFinalTransformIdle());
        expectFailure("fixpoint requires an idle final transform",
                () -> PEATestUtils.PEAReport.parse(
                        String.join("\n", before(id, 0), body, stat,
                                after(id, 0, false), body, summary(id, 1, "fixpoint")), id));
        expectFailure("fixpoint requires an unchanged complete final round",
                () -> PEATestUtils.PEAReport.parse(
                        String.join("\n", before(id, 0), body,
                                after(id, 0), function(id, "ret i32 2"),
                                summary(id, 1, "fixpoint")), id));
        expectFailure("missing summary", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, after(id, 0), body), id));
        expectFailure("duplicate summary", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, after(id, 0), body,
                        summary(id, 1, "fixpoint"), summary(id, 1, "fixpoint")), id));
        expectFailure("unknown summary stop reason", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, after(id, 0), body,
                        summary(id, 1, "unknown")), id));
        expectFailure("summary round mismatch", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, after(id, 0), body,
                        summary(id, 2, "fixpoint")), id));
        expectFailure("malformed summary before valid summary", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, after(id, 0), body,
                        ";; PEA-SUMMARY function " + id.llvmFunctionName()
                                + " rounds=1",
                        summary(id, 1, "fixpoint")), id));
        expectFailure("interleaved summary before after marker", () -> PEATestUtils.PEAReport.parse(
                String.join("\n", before(id, 0), body, stat,
                        summary(other, 1, "fixpoint"), after(id, 0), body,
                        summary(id, 1, "fixpoint")), id));
    }

    private static void testLockReplayParser(Method noArgs, Method complex) {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        PEATestUtils.PEALockReplay firstDepth =
                new PEATestUtils.PEALockReplay(7, 3, 13, 17, 1, 0);
        PEATestUtils.PEALockReplay secondReceiver =
                new PEATestUtils.PEALockReplay(7, 3, 13, 18, 2, 1);
        PEATestUtils.PEALockReplay secondReceiverAlias =
                new PEATestUtils.PEALockReplay(8, 3, 13, 18, 2, 1);
        PEATestUtils.PEALockReplay tenthDepth =
                new PEATestUtils.PEALockReplay(7, 3, 13, 17, 10, 2);
        PEATestUtils.PEALockReplay thirdReceiver =
                new PEATestUtils.PEALockReplay(7, 3, 13, 19, 11, 3);
        PEATestUtils.PEALockReplay thirdReceiverAlias =
                new PEATestUtils.PEALockReplay(8, 3, 13, 19, 11, 3);
        PEATestUtils.PEALockReplay otherSource =
                new PEATestUtils.PEALockReplay(7, 4, 14, 18, 4, 0);
        PEATestUtils.PEALockReplay laterRound =
                new PEATestUtils.PEALockReplay(8, 5, 16, 19, 3, 0);
        PEATestUtils.PEALockReplay otherFunction =
                new PEATestUtils.PEALockReplay(9, 6, 21, 22, 4, 0);

        String transcript = String.join("\n",
                before(first, 0),
                function(first, "ret i32 1"),
                stats(first, 0, 0, 0),
                lockReplay(first, firstDepth),
                lockReplay(first, secondReceiver),
                lockReplay(first, secondReceiverAlias),
                lockReplay(first, tenthDepth),
                lockReplay(first, thirdReceiver),
                lockReplay(first, thirdReceiverAlias),
                lockReplay(first, otherSource),
                effect("ReplaceLoad", first, 0, "depth=10"),
                after(first, 0, false),
                function(first, "ret i32 1"),
                before(overloaded, 0),
                function(overloaded, "ret i32 2"),
                stats(overloaded, 0, 0, 0),
                lockReplay(overloaded, otherFunction),
                after(overloaded, 0),
                function(overloaded, "ret i32 2"),
                before(first, 1),
                function(first, "ret i32 1"),
                stats(first, 0, 0, 0),
                lockReplay(first, laterRound),
                after(first, 1),
                function(first, "ret i32 1"),
                summary(overloaded, 1, "iteration-cap"),
                summary(first, 2, "fixpoint"));

        PEATestUtils.PEAReport reports = PEATestUtils.PEAReport.parse(
                transcript, first, overloaded);
        PEATestUtils.PEARound firstRound = reports.report(first).round(0);
        Asserts.assertEquals(firstRound.lockReplays(),
                List.of(firstDepth, secondReceiver, secondReceiverAlias, tenthDepth,
                        thirdReceiver, thirdReceiverAlias, otherSource));
        Asserts.assertEquals(reports.report(first).round(1).lockReplays(),
                List.of(laterRound));
        Asserts.assertEquals(reports.report(overloaded).round(0).lockReplays(),
                List.of(otherFunction));
        Asserts.assertEquals(firstRound.effects().size(), 1,
                "LockReplay diagnostics are typed separately from general effects");

        PEATestUtils.PEALockReplayGroup primary =
                new PEATestUtils.PEALockReplayGroup(7, 3, 13);
        PEATestUtils.PEALockReplayGroup alternate =
                new PEATestUtils.PEALockReplayGroup(7, 4, 14);
        PEATestUtils.PEALockReplayGroup aliasedConsumer =
                new PEATestUtils.PEALockReplayGroup(8, 3, 13);
        Asserts.assertEquals(firstRound.lockReplayGroups().size(), 3);
        Asserts.assertEquals(firstRound.lockReplayGroups().get(primary),
                List.of(firstDepth, secondReceiver, tenthDepth, thirdReceiver));
        Asserts.assertEquals(firstRound.lockReplayGroups().get(aliasedConsumer),
                List.of(secondReceiverAlias, thirdReceiverAlias));
        Asserts.assertEquals(firstRound.lockReplayGroups().get(alternate),
                List.of(otherSource));
        PEATestUtils.PEALockReplayPhysicalGroup physicalPrimary =
                new PEATestUtils.PEALockReplayPhysicalGroup(3, 13);
        PEATestUtils.PEALockReplayPhysicalGroup physicalAlternate =
                new PEATestUtils.PEALockReplayPhysicalGroup(4, 14);
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().size(), 2);
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().get(physicalPrimary),
                List.of(firstDepth, secondReceiver, secondReceiverAlias, tenthDepth,
                        thirdReceiver, thirdReceiverAlias));
        Asserts.assertEquals(firstRound.lockReplayPhysicalGroups().get(physicalAlternate),
                List.of(otherSource));
        firstRound.assertLockReplaySequence(primary,
                firstDepth, secondReceiver, tenthDepth, thirdReceiver);
        Asserts.assertEquals(firstRound.distinctLockReplaySourceCount(7), 2L);
    }

    private static void testMalformedLockReplays(Method method) {
        PEATestUtils.MethodId id = PEATestUtils.MethodId.of(method);
        String prefix = "PEA: LockReplay function=@\"" + id.llvmFunctionName() + "\" ";
        String valid = prefix + "logical_escape=1 batch=2 source=4"
                + " receiver_vo=5 depth=6 ordinal=0";

        expectFailure("LockReplay missing key", () -> parseLockTranscript(id,
                prefix + "logical_escape=1 batch=2 source=4"
                        + " receiver_vo=5 depth=6"));
        expectFailure("LockReplay extra key", () -> parseLockTranscript(id,
                valid + " extra=7"));
        expectFailure("LockReplay duplicate key", () -> parseLockTranscript(id,
                valid + " depth=7"));
        expectFailure("LockReplay negative value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=-6")));
        expectFailure("LockReplay non-decimal value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=0x6")));
        expectFailure("LockReplay overflow value", () -> parseLockTranscript(id,
                valid.replace("depth=6", "depth=2147483648")));
        expectFailure("LockReplay wrong key", () -> parseLockTranscript(id,
                valid.replace("receiver_vo=5", "receiverVo=5")));
        expectFailure("LockReplay wrong order", () -> parseLockTranscript(id,
                prefix + "batch=2 logical_escape=1 source=4"
                        + " receiver_vo=5 depth=6 ordinal=0"));
        expectFailure("duplicate LockReplay entry", () -> parseLockTranscript(id,
                valid, valid));
        expectFailure("non-contiguous LockReplay ordinal", () -> parseLockTranscript(id,
                valid, valid.replace("depth=6 ordinal=0", "depth=7 ordinal=2")));
        expectFailure("non-increasing LockReplay depth", () -> parseLockTranscript(id,
                valid, valid.replace("receiver_vo=5", "receiver_vo=6")
                        .replace("ordinal=0", "ordinal=1")));
        expectFailure("conflicting same-ordinal LockReplay alias", () ->
                parseLockTranscript(id, valid,
                        valid.replace("logical_escape=1", "logical_escape=2")
                                .replace("receiver_vo=5", "receiver_vo=6")));
        expectFailure("late same-ordinal LockReplay alias", () ->
                parseLockTranscript(id, valid,
                        valid.replace("receiver_vo=5 depth=6 ordinal=0",
                                "receiver_vo=6 depth=7 ordinal=1"),
                        valid.replace("logical_escape=1", "logical_escape=2")));
        expectFailure("reused LockReplay batch with different identity", () ->
                parseLockTranscript(id, valid,
                        valid.replace("logical_escape=1", "logical_escape=2")
                                .replace("source=4", "source=9")));
    }

    private static void parseLockTranscript(PEATestUtils.MethodId id, String... replays) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add(before(id, 0));
        lines.add(function(id, "ret i32 1"));
        lines.add(stats(id, 0, 0, 0));
        lines.addAll(List.of(replays));
        lines.add(after(id, 0));
        lines.add(function(id, "ret i32 1"));
        lines.add(summary(id, 1, "fixpoint"));
        PEATestUtils.PEAReport.parse(String.join("\n", lines), id);
    }

    private static void testDumpPairing(Method noArgs, Method complex) throws Exception {
        PEATestUtils.MethodId first = PEATestUtils.MethodId.of(noArgs);
        PEATestUtils.MethodId overloaded = PEATestUtils.MethodId.of(complex);
        Path dir = Files.createTempDirectory("pea-harness-dump-parser-");
        try {
            writePair(dir, first.dumpStem(), "100", function(first, "ret i32 11"),
                    function(first, "%alloc = invoke ptr addrspace(1) @new_instance()",
                            "ret i32 12"));
            writePair(dir, overloaded.dumpStem(), "200", function(overloaded, "ret i32 21"),
                    function(overloaded, "ret i32 22"));

            PEATestUtils.IRBody firstFront = PEATestUtils.frontendIR(dir, first);
            PEATestUtils.IRBody firstFinal = PEATestUtils.finalIR(dir, first);
            PEATestUtils.IRBody overloadFront = PEATestUtils.frontendIR(dir, overloaded);
            firstFront.assertPresent("ret i32 11");
            firstFinal.assertPresent("ret i32 12");
            Asserts.assertEquals(firstFinal.loweredAllocCount(), 1,
                    "Optimized dumps use lowered allocation helper names");
            Asserts.assertEquals(firstFinal.peaAllocCount(), 0,
                    "Optimized dumps must not be counted as PEA-stage allocations");
            overloadFront.assertPresent("ret i32 21");

            Files.writeString(dir.resolve(first.dumpStem() + "_orphan.ll"),
                    function(first, "ret i32 31"));
            Files.writeString(dir.resolve(first.dumpStem() + "_different_optimized.ll"),
                    function(first, "ret i32 32"));
            firstFinal.assertPresent("ret i32 12");

            writePair(dir, first.dumpStem(), "300", function(first, "ret i32 41"),
                    function(first, "ret i32 42"));
            expectFailure("ambiguous dump pairs",
                    () -> PEATestUtils.finalIR(dir, first));
        } finally {
            deleteTree(dir);
        }
    }

    private static void testManagedOptionRejection(Method target) {
        expectFailure("HotSpot PEA override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:-JeandleDoPEA"));
        expectFailure("HotSpot PEA assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:JeandleDoPEA=false"));
        expectFailure("LLVM container override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleLLVMOptions=-debug"));
        expectFailure("dump override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDumpDirectory=somewhere"));
        expectFailure("dump assignment override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDumpIR=false"));
        expectFailure("CompileCommand override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:CompileCommand=compileonly,*::*"));
        expectFailure("CompileCommandFile override", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:CompileCommandFile=commands.txt"));
        expectFailure("shape compiler-count override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraFlags("-XX:CICompilerCount=2"));
        expectFailure("argument file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("@arguments.txt"));
        expectFailure("HotSpot flags file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:Flags=flags.txt"));
        expectFailure("HotSpot VM options file", () -> PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:VMOptionsFile=options.txt"));
        expectFailure("iteration override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-pea-iterations=2"));
        expectFailure("trace assignment override", () -> PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-trace-pea=false"));
        expectFailure("compressed-oops assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:UseCompressedOops=true"));
        expectFailure("compressed-klass assignment override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraFlags("-XX:UseCompressedClassPointers=true"));
        expectFailure("PEA-off double-dash iteration override",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).peaOff()
                        .extraLLVMOptions("--jeandle-pea-iterations=2"));
        String offThenOption = failureMessage("PEA-off then safe LLVM option",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).peaOff()
                        .extraLLVMOptions("-verify-each"));
        String optionThenOff = failureMessage("safe LLVM option then PEA-off",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .extraLLVMOptions("-verify-each").peaOff());
        Asserts.assertEquals(offThenOption, optionThenOff);
        Asserts.assertEquals(offThenOption,
                "PEA-off runs do not accept extra LLVM options");

        PEATestUtils.behaviorRun(WRAPPER, target)
                .extraFlags("-XX:JeandleDoPEAExtra=false",
                        "-XX:UseCompressedOopsExperimental=true");
        PEATestUtils.shapeRun(WRAPPER, target)
                .extraLLVMOptions("-jeandle-pea-unrelated=1",
                        "-jeandle-trace-pea-extra=false");
    }

    private static void testLockingModes(Method target) {
        PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(1);
        PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(2);
        expectFailure("invalid locking mode zero",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(0));
        expectFailure("invalid locking mode three",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).lockingMode(3));
        expectFailure("repeated same locking mode", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).lockingMode(1).lockingMode(1));
        expectFailure("repeated different locking mode", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).lockingMode(1).lockingMode(2));
        expectFailure("raw experimental unlock flag", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).extraFlags("-XX:+UnlockExperimentalVMOptions"));
        expectFailure("raw locking mode flag", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).extraFlags("-XX:LockingMode=2"));
    }

    private static void testInlineCommandHandling(Method target, Method helper) {
        PEATestUtils.RunBuilder builder = PEATestUtils.behaviorRun(WRAPPER, target);
        builder.inline(helper);
        expectFailure("duplicate inline command", () -> builder.inline(helper));
        expectFailure("inline/dontinline conflict", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).inline(helper).dontinline(helper));
        expectFailure("dontinline/inline conflict", () -> PEATestUtils.behaviorRun(
                WRAPPER, target).dontinline(helper).inline(helper));
    }

    private static void testExecutableDirectivesAndMaxArrayLength(Method target)
            throws Exception {
        Constructor<TestWrapper> constructor = TestWrapper.class.getConstructor();
        Executable executableConstructor = constructor;
        PEATestUtils.PEAOnOffResult comparison = PEATestUtils.behaviorRun(WRAPPER, target)
                .compileonly(executableConstructor)
                .inline(executableConstructor)
                .maxArrayLength(128)
                .runPEAOnOffEquivalentWithCommands();
        String constructorPattern = WRAPPER + "::<init>()V";
        String compileOnly = "-XX:CompileCommand=compileonly," + constructorPattern;
        String inline = "-XX:CompileCommand=inline," + constructorPattern;
        for (List<String> command : List.of(comparison.onCommand(), comparison.offCommand())) {
            Asserts.assertEquals(command.stream().filter(compileOnly::equals).count(), 1L);
            Asserts.assertEquals(command.stream().filter(inline::equals).count(), 1L);
            Asserts.assertTrue(command.stream().anyMatch(option -> option.contains(
                    "-jeandle-pea-max-array-length=128")));
        }
        expectFailure("duplicate constructor compileonly",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .compileOnly(executableConstructor).compileOnly(executableConstructor));
        expectFailure("constructor inline/dontinline conflict",
                () -> PEATestUtils.behaviorRun(WRAPPER, target)
                        .inline(executableConstructor).dontinline(executableConstructor));
        expectFailure("negative maximum array length",
                () -> PEATestUtils.behaviorRun(WRAPPER, target).maxArrayLength(-1));
    }

    private static void testActiveFrameArgumentChecks(Method uncompiled) {
        Asserts.assertTrue(PEATestUtils.ActiveFrameDeoptEvidence.class.isRecord());
        Asserts.assertThrows(NullPointerException.class,
                () -> PEATestUtils.deoptimizeActiveFrame((Method) null, 1));
        Asserts.assertThrows(IllegalArgumentException.class,
                () -> PEATestUtils.deoptimizeActiveFrame(uncompiled, -1));
    }

    private static void testRealShapeRun(Method noArgs, Method complex, Method decoy)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, noArgs, complex)
                .lockingMode(1)
                .run()) {
            List<String> command = run.command();
            assertLockingModeCommand(command, 1);
            Asserts.assertEquals(command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions=")).count(), 1L);
            String llvm = command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions="))
                    .findFirst().orElseThrow();
            List<String> actualLLVMOptions = List.of(llvm.substring(
                    "-XX:JeandleLLVMOptions=".length()).split(" "));
            List<String> expectedLLVMOptions = List.of(
                    "-jeandle-trace-pea",
                    "-jeandle-dump-pea-stats",
                    "-jeandle-pea-analyze-function="
                            + PEATestUtils.MethodId.rootOf(noArgs).llvmFunctionName(),
                    "-jeandle-dump-pea-ir-function="
                            + PEATestUtils.MethodId.rootOf(noArgs).llvmFunctionName(),
                    "-jeandle-pea-analyze-function="
                            + PEATestUtils.MethodId.rootOf(complex).llvmFunctionName(),
                    "-jeandle-dump-pea-ir-function="
                            + PEATestUtils.MethodId.rootOf(complex).llvmFunctionName());
            Asserts.assertEquals(actualLLVMOptions.stream().sorted().toList(),
                    expectedLLVMOptions.stream().sorted().toList());
            for (Method method : List.of(noArgs, complex)) {
                String function = PEATestUtils.MethodId.rootOf(method).llvmFunctionName();
                Asserts.assertTrue(llvm.contains("-jeandle-pea-analyze-function=" + function));
                Asserts.assertTrue(llvm.contains("-jeandle-dump-pea-ir-function=" + function));
                PEATestUtils.PEAReport report = run.report(method);
                Asserts.assertTrue(report.round(0).hasStats());
                Asserts.assertEquals(report.round(0).neverEscapes(), 1);
                Asserts.assertEquals(report.round(0).partiallyEscapes(), 0);
                Asserts.assertEquals(report.round(0).alwaysEscapes(), 0);
                Asserts.assertEquals(report.effects("EliminateAllocation").size(), 1);
                Asserts.assertEquals(report.effects("ReplaceLoad").size(), 1);
                Asserts.assertEquals(report.round0Before().peaAllocCount(), 1);
                Asserts.assertEquals(report.finalAfter().peaAllocCount(), 0);
                Asserts.assertEquals(run.frontendIR(method).peaAllocCount(), 1);
                Asserts.assertEquals(run.finalIR(method).loweredAllocCount(), 0);
                run.finalIR(method).assertAbsent("@new_instance(");
            }
            Asserts.assertTrue(command.contains("-XX:CICompilerCount=1"));
            Asserts.assertTrue(command.contains("-XX:-UseCompressedOops"));
            Asserts.assertTrue(command.contains("-XX:-UseCompressedClassPointers"));
            Asserts.assertFalse(run.output().getStderr().contains(
                    PEATestUtils.MethodId.of(decoy).llvmFunctionName()));
        }
    }

    private static void testIterationsAndExactEffects(Method target, Method helper)
            throws Exception {
        try (PEATestUtils.RunResult run = PEATestUtils.shapeRun(WRAPPER, target)
                .peaIterations(4)
                .run()) {
            List<String> command = run.command();
            Asserts.assertFalse(command.contains("-XX:+UnlockExperimentalVMOptions"));
            Asserts.assertFalse(command.stream()
                    .anyMatch(s -> s.startsWith("-XX:LockingMode=")));
            String llvm = command.stream()
                    .filter(s -> s.startsWith("-XX:JeandleLLVMOptions="))
                    .findFirst().orElseThrow();
            Asserts.assertEquals(List.of(llvm.substring(
                    "-XX:JeandleLLVMOptions=".length()).split(" ")).stream()
                    .filter("-jeandle-pea-iterations=4"::equals).count(), 1L);
            PEATestUtils.PEAReport report = run.report(target);
            Asserts.assertTrue(report.roundCount() >= 2, "configured outer rounds");
            Asserts.assertTrue(report.round(report.roundCount() - 1).transformIdle(),
                    "last observed round must be transform-idle");
            report.round(0).uniqueEffect("EliminateAllocation", "jeandle.new_instance");
            Asserts.assertEquals(report.round(0).effectCount(
                    "EliminateAllocation", "jeandle.new_instance"), 1L);
            Asserts.assertEquals(report.round0Before().allocationBCIs().size(), 1);
            PEATestUtils.IRBlock allocationBlock = report.round0Before()
                    .blockContaining("@jeandle.new_instance", 0);
            Asserts.assertEquals(allocationBlock.occurrenceCount(
                    "@jeandle.new_instance"), 1);
            allocationBlock.assertAbsent("@jeandle.new_array");
            allocationBlock.assertBefore("@jeandle.new_instance", 0, "to label", 0);
        }

        Asserts.assertThrows(IllegalArgumentException.class,
                () -> PEATestUtils.shapeRun(WRAPPER, target).peaIterations(0));
        Asserts.assertThrows(IllegalArgumentException.class,
                () -> PEATestUtils.shapeRun(WRAPPER, target).peaIterations(17));

        PEATestUtils.PEAOnOffResult comparison = PEATestUtils.behaviorRun(WRAPPER, target)
                .lockingMode(2)
                .peaIterations(4)
                .dontinline(helper)
                .inline(target)
                .runPEAOnOffEquivalentWithCommands();
        assertLockingModeCommand(comparison.onCommand(), 2);
        assertLockingModeCommand(comparison.offCommand(), 2);
        String inline = "-XX:CompileCommand=inline,"
                + PEATestUtils.MethodId.of(target).compileCommandPattern();
        Asserts.assertEquals(comparison.onCommand().stream().filter(inline::equals).count(), 1L);
        Asserts.assertEquals(comparison.offCommand().stream().filter(inline::equals).count(), 1L);
        String dontinline = "-XX:CompileCommand=dontinline,"
                + PEATestUtils.MethodId.of(helper).compileCommandPattern();
        Asserts.assertTrue(comparison.onCommand().indexOf(inline)
                < comparison.onCommand().indexOf(dontinline),
                "inline commands must precede dontinline commands");
        Asserts.assertThrows(UnsupportedOperationException.class,
                () -> comparison.onCommand().add("-version"));
    }

    private static void assertLockingModeCommand(List<String> command, int mode) {
        String unlock = "-XX:+UnlockExperimentalVMOptions";
        String lockingMode = "-XX:LockingMode=" + mode;
        Asserts.assertEquals(command.stream().filter(unlock::equals).count(), 1L);
        Asserts.assertEquals(command.stream().filter(lockingMode::equals).count(), 1L);
        Asserts.assertTrue(command.indexOf(unlock) < command.indexOf(lockingMode),
                "Experimental options must be unlocked before selecting LockingMode");
    }

    private static void testNotCompilableFailsFast() throws Exception {
        ProcessBuilder process = ProcessTools.createLimitedTestJavaProcessBuilder(
                "-Xbootclasspath/a:.",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                "-XX:-TieredCompilation",
                "-XX:+UseJeandleCompiler",
                CompileFailureWrapper.class.getName());
        OutputAnalyzer output = ProcessTools.executeCommand(process);
        output.shouldHaveExitValue(0);
        output.shouldContain("PEATestUtils not-compilable fail-fast: OK");
    }

    private static String before(PEATestUtils.MethodId id, int round) {
        return ";; PEA-DUMP before iter=" + round + " function " + id.llvmFunctionName();
    }

    private static String after(PEATestUtils.MethodId id, int round) {
        return after(id, round, true);
    }

    private static String after(PEATestUtils.MethodId id, int round,
                                boolean transformIdle) {
        return ";; PEA-DUMP after iter=" + round + " function " + id.llvmFunctionName()
                + " transform_idle=" + transformIdle;
    }

    private static String summary(PEATestUtils.MethodId id, int rounds, String stop) {
        return ";; PEA-SUMMARY function " + id.llvmFunctionName()
                + " rounds=" + rounds + " stop=" + stop;
    }

    private static String stats(PEATestUtils.MethodId id, int never, int partial, int always) {
        return ";; PEA stats @" + id.llvmFunctionName() + ": NeverEscapes=" + never
                + " PartiallyEscapes=" + partial + " AlwaysEscapes=" + always;
    }

    private static PEATestUtils.PEARound parseEffectTranscript(
            PEATestUtils.MethodId id, String... effects) {
        String body = function(id, "ret i32 1");
        String transcript = String.join("\n",
                before(id, 0), body, stats(id, 0, 0, 0),
                String.join("\n", effects),
                after(id, 0, false), body,
                summary(id, 1, "iteration-cap"));
        return PEATestUtils.PEAReport.parse(transcript, id).report(id).round(0);
    }

    private static PEATestUtils.PEAReport parseTwoRoundEffectTranscript(
            PEATestUtils.MethodId id) {
        String body = function(id, "ret i32 1");
        String transcript = String.join("\n",
                before(id, 0), body, stats(id, 0, 0, 0),
                effect("Materialize", id, 0, "[VO=0] block=%left"),
                after(id, 0, false), body,
                before(id, 1), body, stats(id, 0, 0, 0),
                effect("Materialize", id, 0, "[VO=0] block=%left"),
                after(id, 1, false), body,
                summary(id, 2, "iteration-cap"));
        return PEATestUtils.PEAReport.parse(transcript, id).report(id);
    }

    private static String effect(
            String kind, PEATestUtils.MethodId id, int sequence, String detail) {
        return effectWithSequenceText(kind, id, Integer.toString(sequence), detail);
    }

    private static String effectWithSequenceText(
            String kind, PEATestUtils.MethodId id, String sequence, String detail) {
        return effectWithoutSequence(kind, id, detail) + " seq=" + sequence;
    }

    private static String effectWithoutSequence(
            String kind, PEATestUtils.MethodId id, String detail) {
        return "PEA: " + kind + " function=@\"" + id.llvmFunctionName() + "\" " + detail;
    }

    private static String lockReplay(PEATestUtils.MethodId id,
                                     PEATestUtils.PEALockReplay replay) {
        return "PEA: LockReplay function=@\"" + id.llvmFunctionName() + "\""
                + " logical_escape=" + replay.logicalEscape()
                + " batch=" + replay.batch()
                + " source=" + replay.source()
                + " receiver_vo=" + replay.receiverVO()
                + " depth=" + replay.depth()
                + " ordinal=" + replay.ordinal();
    }

    private static String function(PEATestUtils.MethodId id, String... instructions) {
        return "define hotspotcc i32 @\"" + id.llvmFunctionName() + "\"() {\nentry:\n  "
                + String.join("\n  ", instructions) + "\n}";
    }

    private static PEATestUtils.IRBody bodyWithInstructions(
            PEATestUtils.MethodId id, String... instructions) {
        String body = function(id, instructions);
        String transcript = String.join("\n",
                before(id, 0), body, stats(id, 0, 0, 0),
                after(id, 0), body, summary(id, 1, "fixpoint"));
        return PEATestUtils.PEAReport.parse(transcript, id).report(id).round0Before();
    }

    private static PEATestUtils.IRBody klassCollisionBody(
            PEATestUtils.MethodId id, String address, String collision) {
        return bodyWithInstructions(id,
                "%object = call \"java-klass\"=\"" + address + "\" ptr"
                        + " @jeandle.new_instance(ptr inttoptr"
                        + " (i64 " + address + " to ptr))"
                        + " [ \"deopt\"(i64 " + address + ", i64 7001) ]",
                collision,
                "ret i32 1");
    }

    private static void expectDeoptFailure(
            PEATestUtils.MethodId id, String label, String bundle) {
        PEATestUtils.IRBody body = bodyWithInstructions(id,
                "call void @malformed.site() [ " + bundle + " ]",
                "ret i32 1");
        expectFailure(label, () -> body.deoptBundleAtCall("malformed.site", 0));
    }

    private static String deoptBundle(int bci, String... values) {
        String suffix = values.length == 0 ? "" : ", " + String.join(", ", values);
        return "\"deopt\"(i64 0, i32 " + bci + ", i32 " + bci + suffix + ")";
    }

    private static String typed(int index, int valueType, int basicType) {
        long encoded = ((long) index << 32) | ((long) valueType << 16) | basicType;
        return "i64 " + Long.toUnsignedString(encoded);
    }

    private static void writePair(Path dir, String stem, String timestamp,
                                  String frontend, String optimized) throws IOException {
        Files.writeString(dir.resolve(stem + "_" + timestamp + ".ll"), frontend);
        Files.writeString(dir.resolve(stem + "_" + timestamp + "_optimized.ll"), optimized);
    }

    private static void expectFailure(String label, ThrowingRunnable action) {
        System.out.println("expected parser rejection: " + label + ": "
                + failureMessage(label, action));
    }

    private static void expectFailureContains(
            String label, String expectedText, ThrowingRunnable action) {
        String message = failureMessage(label, action);
        Asserts.assertTrue(message.contains(expectedText),
                label + ": useful failure message must contain '" + expectedText
                        + "', got: " + message);
        System.out.println("expected parser rejection: " + label + ": " + message);
    }

    private static void expectAssertionFailure(String label, ThrowingRunnable action) {
        try {
            action.run();
        } catch (AssertionError | RuntimeException expected) {
            System.out.println("expected assertion rejection: " + label + ": "
                    + expected.getMessage());
            return;
        } catch (Exception unexpected) {
            throw new RuntimeException("Wrong exception for " + label, unexpected);
        }
        throw new RuntimeException("Expected assertion failure: " + label);
    }

    private static String failureMessage(String label, ThrowingRunnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return expected.getMessage();
        } catch (Exception unexpected) {
            throw new RuntimeException("Wrong exception for " + label, unexpected);
        }
        throw new RuntimeException("Expected failure: " + label);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    public static class Point {
        int x;
    }

    public static class TestWrapper {
        public TestWrapper() {}

        public static void main(String[] args) throws Exception {
            new Point();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int first = test();
            int second = test(5, new Point(), new Point[1][1], new int[]{7});
            System.out.println("PEA-RESULT:" + first + "," + second);
        }

        public static int test() {
            Point point = new Point();
            point.x = 7;
            return point.x;
        }

        public static int test(int seed, Point unused, Point[][] nested, int[] values) {
            Point point = new Point();
            point.x = seed + nested.length + values[0];
            return point.x;
        }

        public static int testExtra() {
            return 99;
        }
    }

    public static class CompileFailureWrapper {
        public static void main(String[] args) throws Exception {
            Method method = CompileFailureWrapper.class.getMethod("target");
            WhiteBox whiteBox = WhiteBox.getWhiteBox();
            whiteBox.makeMethodNotCompilable(method, 4);

            long start = System.nanoTime();
            RuntimeException failure = Asserts.assertThrows(RuntimeException.class,
                    () -> PEATestUtils.enqueueAndAwaitLevel4(method));
            long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - start);
            Asserts.assertTrue(failure.getMessage().contains("not compilable at level 4"),
                    "fail-fast reports the compilation state: " + failure.getMessage());
            Asserts.assertTrue(elapsedMillis < 10_000,
                    "not-compilable target must fail promptly, elapsed=" + elapsedMillis + "ms");
            RuntimeException deoptFailure = Asserts.assertThrows(RuntimeException.class,
                    () -> PEATestUtils.deoptimizeActiveFrame(method, 1));
            Asserts.assertTrue(deoptFailure.getMessage().contains("compiled at level 4"),
                    "active-frame precondition reports exact compilation state");
            System.out.println("PEATestUtils not-compilable fail-fast: OK");
        }

        public static int target() {
            return 1;
        }
    }

    public static class NormalDeoptIdentityWrapper {
        private static Method methodTargetMethod;
        private static PEATestUtils.MethodId methodIdTargetId;
        private static PEATestUtils.ActiveFrameDeoptEvidence methodEvidence;
        private static PEATestUtils.ActiveFrameDeoptEvidence methodIdEvidence;

        public static void main(String[] args) throws Exception {
            new Point();
            methodTargetMethod = NormalDeoptIdentityWrapper.class.getMethod(
                    "methodTarget", int.class);
            methodIdTargetId = PEATestUtils.MethodId.of(
                    NormalDeoptIdentityWrapper.class.getMethod(
                            "methodIdTarget", int.class));
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            Asserts.assertEquals(methodTarget(7), 10,
                    "Method overload target resumes after deoptimization");
            assertEvidence(methodEvidence, PEATestUtils.MethodId.of(methodTargetMethod));
            Asserts.assertEquals(methodIdTarget(11), 16,
                    "MethodId overload target resumes after deoptimization");
            assertEvidence(methodIdEvidence, methodIdTargetId);
            System.out.println("PEA-RESULT:10,16");
        }

        public static int methodTarget(int seed) {
            Point point = new Point();
            point.x = seed;
            return point.x + requestMethod();
        }

        public static int methodIdTarget(int seed) {
            Point point = new Point();
            point.x = seed;
            return point.x + requestMethodId();
        }

        private static int requestMethod() {
            methodEvidence = PEATestUtils.deoptimizeActiveFrame(methodTargetMethod, 2);
            return 3;
        }

        private static int requestMethodId() {
            methodIdEvidence = PEATestUtils.deoptimizeActiveFrame(methodIdTargetId, 2);
            return 5;
        }

        private static void assertEvidence(
                PEATestUtils.ActiveFrameDeoptEvidence evidence,
                PEATestUtils.MethodId target) {
            Asserts.assertNotNull(evidence, target + ": missing active-frame evidence");
            Asserts.assertEquals(evidence.target(), target);
            Asserts.assertEquals(evidence.frameDepth(), 2);
            Asserts.assertEquals(evidence.compilationLevel(), 4);
            Asserts.assertEquals(evidence.markedNMethods(), 1);
            Asserts.assertTrue(evidence.frameDeoptimized());
        }
    }

    public static class OSRIdentityWrapper {
        private static PEATestUtils.MethodId osrTarget;
        private static PEATestUtils.ActiveFrameDeoptEvidence deoptEvidence;

        public static void main(String[] args) throws Exception {
            Method target = OSRIdentityWrapper.class.getDeclaredMethod("loop");
            osrTarget = PEATestUtils.MethodId.osr(target);
            int result = loop();
            Asserts.assertEquals(deoptEvidence.target(), osrTarget);
            Asserts.assertEquals(deoptEvidence.compilationLevel(), 4);
            Asserts.assertEquals(deoptEvidence.markedNMethods(), 1);
            Asserts.assertTrue(deoptEvidence.frameDeoptimized());
            Asserts.assertFalse(WhiteBox.getWhiteBox().isMethodCompiled(target, true),
                    "OSR nmethod must be unpacked before active-frame helper returns");
            Asserts.assertEquals(result, 705_506_480);
            System.out.println("PEA-RESULT:" + result);
        }

        public static int loop() {
            int result = 0;
            for (int i = 0; i < 100_000; i++) {
                result += i;
            }
            for (int i = 0; i < 1_024; i++) {
                result += i;
                if (i == 512) {
                    requestDeopt();
                }
            }
            Asserts.assertNotNull(deoptEvidence,
                    "loop must deoptimize an active OSR level-4 frame");
            return result;
        }

        private static void requestDeopt() {
            PEATestUtils.confirmLevel4(osrTarget);
            deoptEvidence = PEATestUtils.deoptimizeActiveFrame(osrTarget, 1);
        }
    }
}
