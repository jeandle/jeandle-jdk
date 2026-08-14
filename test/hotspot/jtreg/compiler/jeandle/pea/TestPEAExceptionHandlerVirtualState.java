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
 * @summary PEA merges virtual field state from two invoke unwind predecessors
 *          for read, write, escape, and lock exception handlers
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestPEAExceptionHandlerVirtualState
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestPEAExceptionHandlerVirtualState {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestPEAExceptionHandlerVirtualState$TestWrapper";
    private static final Pattern INVOKE_DESTINATIONS =
            Pattern.compile("^to label (%\\S+) unwind label (%\\S+)$");
    private static final Pattern BLOCK_LABEL =
            Pattern.compile("^([-A-Za-z$._0-9]+):(?: ;.*)?$");
    private static final Pattern CONDITIONAL_BRANCH = Pattern.compile(
            "^br i1 [^,]+, label (%\\S+), label (%\\S+)(?:, .*)?$");

    public static void main(String[] args) throws Exception {
        Method read = TestWrapper.class.getMethod("read", boolean.class);
        Method write = TestWrapper.class.getMethod("write", boolean.class);
        Method escape = TestWrapper.class.getMethod("escape", boolean.class);
        Method lock = TestWrapper.class.getMethod("lock", boolean.class);
        Method throwLeft = TestWrapper.class.getMethod("throwLeft");
        Method throwRight = TestWrapper.class.getMethod("throwRight");
        Method sink = TestWrapper.class.getMethod("sink", TestWrapper.Box.class);
        Method[] targets = {read, write, escape, lock};

        behaviorBuilder(targets, throwLeft, throwRight, sink)
                .lockingMode(1)
                .runPEAOnOffEquivalent();
        behaviorBuilder(targets, throwLeft, throwRight, sink)
                .lockingMode(2)
                .runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = shapeBuilder(
                targets, throwLeft, throwRight, sink).lockingMode(2).run()) {
            List<Integer> readBCIs = assertPEAInputShape(run, read, throwLeft, throwRight);
            List<Integer> writeBCIs = assertPEAInputShape(run, write, throwLeft, throwRight);
            List<Integer> escapeBCIs = assertPEAInputShape(run, escape, throwLeft, throwRight);
            List<Integer> lockBCIs = assertPEAInputShape(run, lock, throwLeft, throwRight);

            assertNeverEscapeShape(run, read, readBCIs, false);
            assertNeverEscapeShape(run, write, writeBCIs, false);
            assertEscapeShape(run, escape, sink, escapeBCIs);
            assertNeverEscapeShape(run, lock, lockBCIs, true);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method throwLeft,
                                                            Method throwRight,
                                                            Method sink) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(throwLeft)
                .dontinline(throwRight)
                .dontinline(sink);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                         Method throwLeft,
                                                         Method throwRight,
                                                         Method sink) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(throwLeft)
                .dontinline(throwRight)
                .dontinline(sink);
    }

    private static List<Integer> assertPEAInputShape(PEATestUtils.RunResult run,
                                                     Method target,
                                                     Method throwLeft,
                                                     Method throwRight) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.IRBody peaInput = report.round0Before();
        List<Integer> sourceBCIs = peaInput.allocationBCIs();
        Asserts.assertEquals(sourceBCIs.size(), 2,
                target + ": two round-0 PEA input source allocations");
        Set<Integer> distinctBCIs = new HashSet<>(sourceBCIs);
        Asserts.assertEquals(distinctBCIs.size(), 2,
                target + ": distinct p and q allocation BCIs");

        InvokeEdge leftEdge = exactInvokeEdge(peaInput, throwLeft, target);
        InvokeEdge rightEdge = exactInvokeEdge(peaInput, throwRight, target);
        Asserts.assertNotEquals(leftEdge.lineIndex(), rightEdge.lineIndex(),
                target + ": throwers are distinct real invoke terminators");
        Asserts.assertNotEquals(leftEdge.normalDestination(), leftEdge.unwindDestination(),
                target + ": left invoke has distinct normal and unwind successors");
        Asserts.assertNotEquals(rightEdge.normalDestination(), rightEdge.unwindDestination(),
                target + ": right invoke has distinct normal and unwind successors");
        Asserts.assertNotEquals(leftEdge.unwindDestination(), rightEdge.unwindDestination(),
                target + ": each invoke BCI has a distinct landingpad state");
        String leftCatch = canonicalCatchDestination(
                peaInput, leftEdge.unwindDestination(), target, throwLeft);
        String rightCatch = canonicalCatchDestination(
                peaInput, rightEdge.unwindDestination(), target, throwRight);
        Asserts.assertEquals(leftCatch, rightCatch,
                target + ": both thrower landingpads dispatch to the common handler");
        return sourceBCIs;
    }

    private static InvokeEdge exactInvokeEdge(PEATestUtils.IRBody body, Method callee,
                                               Method target) {
        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        String exactOperand = "@\"" + calleeName + "\"(";
        InvokeEdge found = null;
        List<String> lines = body.lines();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("invoke ") || !line.contains(exactOperand)) {
                continue;
            }
            Asserts.assertTrue(index + 1 < lines.size(),
                    target + ": invoke destination continuation exists for " + callee);
            Matcher destinations = INVOKE_DESTINATIONS.matcher(lines.get(index + 1));
            Asserts.assertTrue(destinations.matches(),
                    target + ": anchored invoke destinations for " + callee);
            Asserts.assertTrue(found == null,
                    target + ": exact callee appears as one invoke: " + callee);
            found = new InvokeEdge(index, destinations.group(1), destinations.group(2));
        }
        Asserts.assertNotNull(found, target + ": exact real invoke for " + callee);
        return found;
    }

    private static String canonicalCatchDestination(PEATestUtils.IRBody body,
                                                     String unwindDestination,
                                                     Method target, Method callee) {
        Map<String, ParsedBlock> blocks = parseBlocks(body);
        ParsedBlock unwind = requireBlock(blocks, unwindDestination, target, callee);
        Asserts.assertEquals(unwind.lines().stream()
                        .filter(line -> line.startsWith("%")
                                && line.contains(" = landingpad ")).count(), 1L,
                target + ": immediate unwind block has one landingpad for " + callee);

        List<String> unwindSuccessors = conditionalSuccessors(unwind, target, callee);
        String dispatch = uniqueSuccessor(blocks, unwindSuccessors, true, target, callee,
                "exception-dispatch");
        String typeCheck = uniqueSuccessor(blocks, unwindSuccessors, false, target, callee,
                "type-check");
        ParsedBlock checkBlock = requireBlock(blocks, typeCheck, target, callee);
        Asserts.assertEquals(checkBlock.lines().stream()
                        .filter(line -> line.contains("@jeandle.check_instanceof(")).count(), 1L,
                target + ": exact exception type check for " + callee);

        List<String> checkSuccessors = conditionalSuccessors(checkBlock, target, callee);
        Asserts.assertTrue(checkSuccessors.contains(dispatch),
                target + ": type-check failure reaches the same dispatch block for " + callee);
        String catchDestination = checkSuccessors.stream()
                .filter(successor -> !successor.equals(dispatch))
                .findFirst().orElseThrow();
        requireBlock(blocks, catchDestination, target, callee);
        return catchDestination;
    }

    private static Map<String, ParsedBlock> parseBlocks(PEATestUtils.IRBody body) {
        Map<String, ParsedBlock> blocks = new LinkedHashMap<>();
        String currentLabel = null;
        java.util.ArrayList<String> currentLines = null;
        for (String line : body.lines()) {
            Matcher label = BLOCK_LABEL.matcher(line);
            if (label.matches()) {
                currentLabel = "%" + label.group(1);
                currentLines = new java.util.ArrayList<>();
                ParsedBlock previous = blocks.put(
                        currentLabel, new ParsedBlock(currentLabel, currentLines));
                Asserts.assertNull(previous, body.methodId() + ": duplicate block " + currentLabel);
            } else if (currentLines != null) {
                currentLines.add(line);
            }
        }
        return blocks;
    }

    private static ParsedBlock requireBlock(Map<String, ParsedBlock> blocks, String label,
                                            Method target, Method callee) {
        ParsedBlock block = blocks.get(label);
        Asserts.assertNotNull(block,
                target + ": missing CFG block " + label + " for " + callee);
        return block;
    }

    private static List<String> conditionalSuccessors(ParsedBlock block, Method target,
                                                       Method callee) {
        Asserts.assertTrue(!block.lines().isEmpty(),
                target + ": non-empty CFG block " + block.label() + " for " + callee);
        String terminator = block.lines().get(block.lines().size() - 1);
        Matcher branch = CONDITIONAL_BRANCH.matcher(terminator);
        Asserts.assertTrue(branch.matches(),
                target + ": anchored conditional terminator in " + block.label()
                        + " for " + callee);
        Asserts.assertNotEquals(branch.group(1), branch.group(2),
                target + ": distinct conditional successors in " + block.label());
        return List.of(branch.group(1), branch.group(2));
    }

    private static String uniqueSuccessor(Map<String, ParsedBlock> blocks,
                                          List<String> successors, boolean dispatch,
                                          Method target, Method callee, String role) {
        List<String> matches = successors.stream()
                .filter(successor -> isExceptionDispatch(
                        requireBlock(blocks, successor, target, callee)) == dispatch)
                .toList();
        Asserts.assertEquals(matches.size(), 1,
                target + ": unique " + role + " successor for " + callee);
        return matches.get(0);
    }

    private static boolean isExceptionDispatch(ParsedBlock block) {
        return block.lines().stream()
                .anyMatch(line -> line.contains("@install_exceptional_return("));
    }

    private static void assertNeverEscapeShape(PEATestUtils.RunResult run, Method target,
                                                List<Integer> sourceBCIs,
                                                boolean expectLocks) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        assertRound0Stats(first, target, 2, 0, 0);
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 0), 1L,
                target + ": p allocation is eliminated");
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 1), 1L,
                target + ": q allocation is eliminated");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": neither handler object materializes");

        long pLoads = effectCountForVO(first, "ReplaceLoad", 0, "load atomic i32");
        long qLoads = effectCountForVO(first, "ReplaceLoad", 1, "load atomic i32");
        Asserts.assertEquals(pLoads + qLoads, (long) before.lineCount("load atomic i32"),
                target + ": every integer field load is scalar-replaced in round 0");
        long pStores = effectCountForVO(first, "EliminateStore", 0, "store atomic i32");
        long qStores = effectCountForVO(first, "EliminateStore", 1, "store atomic i32");
        Asserts.assertEquals(pStores + qStores, (long) before.lineCount("store atomic i32"),
                target + ": every integer field store is eliminated in round 0");

        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": no allocation remains in final PEA IR");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 0,
                target + ": no integer field load remains in final PEA IR");
        Asserts.assertEquals(after.lineCount("store atomic i32"), 0,
                target + ": no integer field store remains in final PEA IR");
        Asserts.assertEquals(sourceBCIs.size(), 2, target + ": round-0 PEA input allocation gate");

        if (expectLocks) {
            int monitorEnters = before.lineCount("jeandle.monitorenter_with_lightweight_lock");
            int monitorExits = before.lineCount("jeandle.monitorexit_with_lightweight_lock");
            Asserts.assertTrue(monitorEnters > 0,
                    target + ": round-0 PEA input monitorenter exists");
            Asserts.assertTrue(monitorExits > 0,
                    target + ": round-0 PEA input monitorexit exists");
            Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0,
                            "jeandle.monitorenter_with_lightweight_lock"),
                    (long) monitorEnters, target + ": every monitorenter is folded");
            Asserts.assertEquals(effectCountForVO(first, "ReplaceCall", 0,
                            "jeandle.monitorexit_with_lightweight_lock"),
                    (long) monitorExits, target + ": every monitorexit is folded");
            after.assertAbsent("jeandle.monitorenter");
            after.assertAbsent("jeandle.monitorexit");
        }
    }

    private static void assertEscapeShape(PEATestUtils.RunResult run, Method target,
                                          Method sink, List<Integer> sourceBCIs) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody after = report.finalAfter();

        assertRound0Stats(first, target, 1, 1, 0);
        Asserts.assertEquals(effectCountForVO(first, "EliminateAllocation", 1), 1L,
                target + ": q allocation is eliminated in round 0");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceLoad", 1,
                        "load atomic i32"), 1L,
                target + ": handler q load is scalar-replaced");
        Asserts.assertEquals(effectCountForVO(first, "ReplaceLoad", 0,
                        "load atomic i32"), 0L,
                target + ": PEA does not scalar-replace the post-sink p load");
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize"), 1L,
                    target + ": one p materialization in round " + round.iteration());
            Asserts.assertEquals(effectCountForVO(round, "Materialize", 0), 1L,
                    target + ": p materializes exactly once in round "
                            + round.iteration());
        }

        Asserts.assertEquals(after.allocationBCIs(), List.of(sourceBCIs.get(0)),
                target + ": only p's original allocation BCI survives");
        Asserts.assertEquals(after.lineCount("store atomic i32 43"), 1,
                target + ": exactly one p field replay remains");
        Asserts.assertEquals(after.lineCount("store atomic i32 41"), 0,
                target + ": overwritten p initialization is not replayed");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 1,
                target + ": only the post-sink p field load remains real");

        String sinkName = PEATestUtils.MethodId.of(sink).llvmFunctionName();
        PEATestUtils.IRBlock sinkBlock = after.blockContaining(sinkName, 0);
        Asserts.assertEquals(sinkBlock.occurrenceCount("store atomic i32 43"), 1,
                target + ": replay is in the sink predecessor block");
        sinkBlock.assertBefore("store atomic i32 43", 0, sinkName, 0);
    }

    private static void assertRound0Stats(PEATestUtils.PEARound round, Method target,
                                          int never, int partial, int always) {
        Asserts.assertTrue(round.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(round.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(round.partiallyEscapes(), partial,
                target + ": PartiallyEscapes");
        Asserts.assertEquals(round.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    private static long effectCountForVO(PEATestUtils.PEARound round, String kind,
                                         int objectId, String... detailParts) {
        String objectToken = "[VO=" + objectId + "]";
        return round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> Arrays.asList(effect.detail().split("\\s+")).contains(objectToken))
                .filter(effect -> Arrays.stream(detailParts)
                        .allMatch(effect.detail()::contains))
                .count();
    }

    private record InvokeEdge(int lineIndex, String normalDestination,
                              String unwindDestination) {}

    private record ParsedBlock(String label, List<String> lines) {}

    public static class TestWrapper {
        private static final int ITERATIONS = 500;
        private static final String EXPECTED_PAYLOAD =
                "4107,4108,4307,4308,4307,4308,4407,4408";
        public static Box saved;

        public static class Box {
            public int x;
        }

        public static class Marker extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Box();
            new Marker();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            int[] expected = {4107, 4108, 4307, 4308, 4307, 4308, 4407, 4408};
            for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                int[] actual = {
                        read(true), read(false),
                        write(true), write(false),
                        escape(true), escape(false),
                        lock(true), lock(false)
                };
                for (int index = 0; index < expected.length; index++) {
                    Asserts.assertEquals(actual[index], expected[index],
                            "exception-handler case " + index
                                    + " at iteration " + iteration);
                }
            }
            System.out.println("PEA-RESULT:" + EXPECTED_PAYLOAD);
        }

        public static int read(boolean left) {
            Box p = new Box();
            Box q = new Box();
            p.x = 41;
            try {
                if (left) {
                    q.x = 7;
                    throwLeft();
                } else {
                    q.x = 8;
                    throwRight();
                }
            } catch (Marker expected) {
                return p.x * 100 + q.x;
            }
            return -1;
        }

        public static int write(boolean left) {
            Box p = new Box();
            Box q = new Box();
            p.x = 41;
            try {
                if (left) {
                    q.x = 7;
                    throwLeft();
                } else {
                    q.x = 8;
                    throwRight();
                }
            } catch (Marker expected) {
                p.x = 43;
                return p.x * 100 + q.x;
            }
            return -1;
        }

        public static int escape(boolean left) {
            Box p = new Box();
            Box q = new Box();
            p.x = 41;
            try {
                if (left) {
                    q.x = 7;
                    throwLeft();
                } else {
                    q.x = 8;
                    throwRight();
                }
            } catch (Marker expected) {
                p.x = 43;
                sink(p);
                Box observed = saved;
                if (observed != p) {
                    return -2;
                }
                int value = observed.x;
                if (value != 43) {
                    return -3;
                }
                return value * 100 + q.x;
            }
            return -1;
        }

        public static int lock(boolean left) {
            Box p = new Box();
            Box q = new Box();
            p.x = 41;
            try {
                if (left) {
                    q.x = 7;
                    throwLeft();
                } else {
                    q.x = 8;
                    throwRight();
                }
            } catch (Marker expected) {
                synchronized (p) {
                    p.x = 44;
                }
                return p.x * 100 + q.x;
            }
            return -1;
        }

        public static void throwLeft() {
            throw new Marker();
        }

        public static void throwRight() {
            throw new Marker();
        }

        public static void sink(Box p) {
            saved = p;
        }
    }
}
