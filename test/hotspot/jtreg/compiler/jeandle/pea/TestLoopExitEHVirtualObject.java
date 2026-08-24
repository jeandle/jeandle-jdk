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
 * @summary PEA preserves loop-carried virtual state on normal and exceptional
 *          loop exits, including handler reads, writes, and conditional escape
 * @library /test/lib /
 * @build jdk.test.lib.Asserts jdk.test.whitebox.WhiteBox compiler.jeandle.pea.PEATestUtils
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -XX:-UseJeandleCompiler
 *      compiler.jeandle.pea.TestLoopExitEHVirtualObject
 */

package compiler.jeandle.pea;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.test.lib.Asserts;

public class TestLoopExitEHVirtualObject {
    private static final String WRAPPER =
            "compiler.jeandle.pea.TestLoopExitEHVirtualObject$TestWrapper";
    private static final Pattern BLOCK_LABEL =
            Pattern.compile("^([-A-Za-z$._0-9]+):(?: ;.*)?$");
    private static final Pattern INVOKE_DESTINATIONS =
            Pattern.compile("^to label (%\\S+) unwind label (%\\S+)$");
    private static final Pattern CONDITIONAL_BRANCH = Pattern.compile(
            "^br i1 [^,]+, label (%\\S+), label (%\\S+)(?:, .*)?$");
    private static final Pattern UNCONDITIONAL_BRANCH = Pattern.compile(
            "^br label (%\\S+)(?:, .*)?$");

    public static void main(String[] args) throws Exception {
        Method read = TestWrapper.class.getMethod("handlerRead", int.class, int.class);
        Method write = TestWrapper.class.getMethod("handlerWrite", int.class, int.class);
        Method escape = TestWrapper.class.getMethod(
                "handlerConditionalEscape", int.class, int.class, boolean.class);
        Method maybeThrow = TestWrapper.class.getMethod("maybeThrow", boolean.class);
        Method sink = TestWrapper.class.getMethod("sink", TestWrapper.Holder.class);
        Method[] targets = {read, write, escape};

        behaviorBuilder(targets, maybeThrow, sink).runPEAOnOffEquivalent();

        try (PEATestUtils.RunResult run = shapeBuilder(targets, maybeThrow, sink).run()) {
            assertLoopToHandlerInput(run.report(read).round0Before(), read, maybeThrow);
            assertLoopToHandlerInput(run.report(write).round0Before(), write, maybeThrow);
            assertLoopToHandlerInput(run.report(escape).round0Before(), escape, maybeThrow);

            assertNeverEscapeShape(run, read);
            assertNeverEscapeShape(run, write);
            assertConditionalEscapeShape(run, escape, sink);
        }
    }

    private static PEATestUtils.RunBuilder behaviorBuilder(Method[] targets,
                                                            Method maybeThrow,
                                                            Method sink) {
        return PEATestUtils.behaviorRun(WRAPPER, targets)
                .dontinline(maybeThrow)
                .dontinline(sink);
    }

    private static PEATestUtils.RunBuilder shapeBuilder(Method[] targets,
                                                         Method maybeThrow,
                                                         Method sink) {
        return PEATestUtils.shapeRun(WRAPPER, targets)
                .dontinline(maybeThrow)
                .dontinline(sink);
    }

    private static void assertLoopToHandlerInput(PEATestUtils.IRBody body, Method target,
                                                  Method maybeThrow) {
        Map<String, ParsedBlock> blocks = parseBlocks(body);
        InvokeEdge invoke = exactInvokeEdge(blocks, maybeThrow, target);

        Asserts.assertNotEquals(invoke.normalDestination(), invoke.unwindDestination(),
                target + ": invoke has distinct normal and unwind successors");
        Asserts.assertTrue(canReach(blocks, invoke.normalDestination(), invoke.block(),
                        Set.of(invoke.unwindDestination())),
                target + ": invoke normal successor reaches its block through a loop backedge");

        String catchDestination = canonicalCatchDestination(
                blocks, invoke.unwindDestination(), target, maybeThrow);
        Asserts.assertFalse(canReach(blocks, catchDestination, invoke.block(), Set.of()),
                target + ": handler is outside the invoke loop");
    }

    private static InvokeEdge exactInvokeEdge(Map<String, ParsedBlock> blocks, Method callee,
                                               Method target) {
        String calleeName = PEATestUtils.MethodId.of(callee).llvmFunctionName();
        String exactOperand = "@\"" + calleeName + "\"(";
        InvokeEdge found = null;
        for (ParsedBlock block : blocks.values()) {
            for (int index = 0; index < block.lines().size(); index++) {
                String line = block.lines().get(index);
                if (!line.startsWith("invoke ") || !line.contains(exactOperand)) {
                    continue;
                }
                Asserts.assertTrue(index + 1 < block.lines().size(),
                        target + ": invoke destination continuation exists");
                Matcher destinations = INVOKE_DESTINATIONS.matcher(block.lines().get(index + 1));
                Asserts.assertTrue(destinations.matches(),
                        target + ": anchored invoke destinations for " + callee);
                Asserts.assertEquals(index + 2, block.lines().size(),
                        target + ": invoke and destinations terminate their loop block");
                Asserts.assertTrue(found == null,
                        target + ": exact thrower appears as one invoke");
                found = new InvokeEdge(block.label(), destinations.group(1),
                        destinations.group(2));
            }
        }
        Asserts.assertNotNull(found, target + ": exact real invoke for " + callee);
        return found;
    }

    private static String canonicalCatchDestination(Map<String, ParsedBlock> blocks,
                                                     String unwindDestination,
                                                     Method target, Method callee) {
        ParsedBlock unwind = requireBlock(blocks, unwindDestination, target, callee);
        Asserts.assertEquals(unwind.lines().stream()
                        .filter(line -> line.startsWith("%")
                                && line.contains(" = landingpad ")).count(), 1L,
                target + ": invoke unwind block has one landingpad");

        List<String> unwindSuccessors = conditionalSuccessors(unwind, target, callee);
        String dispatch = uniqueSuccessor(blocks, unwindSuccessors, true, target, callee,
                "exception-dispatch");
        String typeCheck = uniqueSuccessor(blocks, unwindSuccessors, false, target, callee,
                "type-check");
        ParsedBlock checkBlock = requireBlock(blocks, typeCheck, target, callee);
        Asserts.assertEquals(checkBlock.lines().stream()
                        .filter(line -> line.contains("@jeandle.check_instanceof(")).count(), 1L,
                target + ": exact exception type check");

        List<String> checkSuccessors = conditionalSuccessors(checkBlock, target, callee);
        Asserts.assertTrue(checkSuccessors.contains(dispatch),
                target + ": failed type check reaches the same dispatch block");
        String catchDestination = checkSuccessors.stream()
                .filter(successor -> !successor.equals(dispatch))
                .findFirst().orElseThrow();
        requireBlock(blocks, catchDestination, target, callee);
        return catchDestination;
    }

    private static Map<String, ParsedBlock> parseBlocks(PEATestUtils.IRBody body) {
        Map<String, ParsedBlock> blocks = new LinkedHashMap<>();
        String currentLabel = null;
        ArrayList<String> currentLines = null;
        for (String line : body.lines()) {
            Matcher label = BLOCK_LABEL.matcher(line);
            if (label.matches()) {
                currentLabel = "%" + label.group(1);
                currentLines = new ArrayList<>();
                ParsedBlock previous = blocks.put(
                        currentLabel, new ParsedBlock(currentLabel, currentLines));
                Asserts.assertNull(previous, body.methodId() + ": duplicate block " + currentLabel);
            } else if (currentLines != null) {
                currentLines.add(line);
            }
        }
        Asserts.assertFalse(blocks.isEmpty(), body.methodId() + ": parsed CFG blocks");
        return blocks;
    }

    private static boolean canReach(Map<String, ParsedBlock> blocks, String start,
                                    String destination, Set<String> forbidden) {
        ArrayDeque<String> work = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        work.add(start);
        while (!work.isEmpty()) {
            String label = work.removeFirst();
            if (label.equals(destination)) {
                return true;
            }
            if (forbidden.contains(label) || !visited.add(label)) {
                continue;
            }
            ParsedBlock block = blocks.get(label);
            if (block == null) {
                continue;
            }
            work.addAll(successors(block));
        }
        return false;
    }

    private static List<String> successors(ParsedBlock block) {
        if (block.lines().isEmpty()) {
            return List.of();
        }
        String terminator = block.lines().get(block.lines().size() - 1);
        Matcher conditional = CONDITIONAL_BRANCH.matcher(terminator);
        if (conditional.matches()) {
            return List.of(conditional.group(1), conditional.group(2));
        }
        Matcher unconditional = UNCONDITIONAL_BRANCH.matcher(terminator);
        if (unconditional.matches()) {
            return List.of(unconditional.group(1));
        }
        Matcher invoke = INVOKE_DESTINATIONS.matcher(terminator);
        if (invoke.matches()) {
            return List.of(invoke.group(1), invoke.group(2));
        }
        return List.of();
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
                target + ": non-empty CFG block " + block.label());
        Matcher branch = CONDITIONAL_BRANCH.matcher(
                block.lines().get(block.lines().size() - 1));
        Asserts.assertTrue(branch.matches(),
                target + ": anchored conditional terminator for " + callee);
        Asserts.assertNotEquals(branch.group(1), branch.group(2),
                target + ": distinct conditional successors for " + callee);
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

    private static void assertNeverEscapeShape(PEATestUtils.RunResult run, Method target) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        assertRound0Stats(first, target, 1, 0, 0);
        Asserts.assertEquals(first.effectCount("EliminateAllocation", "[VO=0]"), 1L,
                target + ": loop-carried allocation is eliminated");
        Asserts.assertEquals(first.effectCount("Materialize"), 0L,
                target + ": handler does not materialize the loop-carried object");
        assertExactAtomicTargets(first, before, target, "ReplaceLoad", "load atomic i32",
                0);
        assertExactAtomicTargets(first, before, target, "EliminateStore", "store atomic i32",
                0);

        Asserts.assertEquals(before.allocationBCIs().size(), 1,
                target + ": one source allocation in round-0 input");
        Asserts.assertEquals(after.allocationBCIs(), List.of(),
                target + ": no allocation remains in final PEA IR");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 0,
                target + ": no object field load remains");
        Asserts.assertEquals(after.lineCount("store atomic i32"), 0,
                target + ": no object field store remains");
    }

    private static void assertConditionalEscapeShape(PEATestUtils.RunResult run, Method target,
                                                     Method sink) {
        PEATestUtils.PEAReport report = run.report(target);
        report.assertFinalTransformIdle();
        PEATestUtils.PEARound first = report.round(0);
        PEATestUtils.IRBody before = report.round0Before();
        PEATestUtils.IRBody after = report.finalAfter();

        assertRound0Stats(first, target, 0, 1, 0);
        for (PEATestUtils.PEARound round : report.rounds()) {
            Asserts.assertEquals(round.effectCount("Materialize", "[VO=0]"), 1L,
                    target + ": one handler escape materialization in round "
                            + round.iteration());
        }
        Asserts.assertEquals(after.allocationBCIs(), before.allocationBCIs(),
                target + ": conditional escape retains exactly the original allocation");
        Asserts.assertEquals(after.allocationBCIs().size(), 1,
                target + ": one original allocation remains");
        assertExactAtomicTargets(first, before, target, "EliminateStore", "store atomic i32",
                0);
        assertExactAtomicTargets(first, before, target, "ReplaceLoad", "load atomic i32", 1);
        Asserts.assertEquals(after.lineCount("store atomic i32"), 1,
                target + ": exactly one final field replay remains");
        Asserts.assertEquals(after.lineCount("load atomic i32"), 1,
                target + ": exactly one post-sink field load remains");

        String sinkName = PEATestUtils.MethodId.of(sink).llvmFunctionName();
        PEATestUtils.IRBlock sinkBlock = after.blockContaining(sinkName, 0);
        Asserts.assertEquals(sinkBlock.occurrenceCount("store atomic i32"), 1,
                target + ": field replay is in the sink predecessor block");
        sinkBlock.assertBefore("store atomic i32", 0, sinkName, 0);
    }

    private static void assertExactAtomicTargets(PEATestUtils.PEARound round,
                                                 PEATestUtils.IRBody before, Method target,
                                                 String kind, String opcode,
                                                 int expectedUnmatched) {
        Set<String> sourceInstructions = before.lines().stream()
                .filter(line -> line.contains(opcode))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> effectTargets = round.effects().stream()
                .filter(effect -> effect.kind().equals(kind))
                .filter(effect -> effect.detail().contains("[VO=0]"))
                .map(PEATestUtils.PEAEffect::detail)
                .filter(detail -> detail.contains(" target="))
                .map(detail -> detail.substring(detail.indexOf(" target=")
                        + " target=".length()).trim())
                .filter(instruction -> instruction.contains(opcode))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        Asserts.assertTrue(sourceInstructions.containsAll(effectTargets),
                target + ": every " + kind + " atomic target is a round-0 source instruction");
        Set<String> unmatched = new HashSet<>(sourceInstructions);
        unmatched.removeAll(effectTargets);
        Asserts.assertEquals(unmatched.size(), expectedUnmatched,
                target + ": exact unmatched round-0 " + opcode + " instructions");
    }

    private static void assertRound0Stats(PEATestUtils.PEARound round, Method target,
                                          int never, int partial, int always) {
        Asserts.assertTrue(round.hasStats(), target + ": round-0 PEA stats");
        Asserts.assertEquals(round.neverEscapes(), never, target + ": NeverEscapes");
        Asserts.assertEquals(round.partiallyEscapes(), partial,
                target + ": PartiallyEscapes");
        Asserts.assertEquals(round.alwaysEscapes(), always, target + ": AlwaysEscapes");
    }

    private record InvokeEdge(String block, String normalDestination,
                              String unwindDestination) {}

    private record ParsedBlock(String label, List<String> lines) {}

    public static class TestWrapper {
        private static final int ITERATIONS = 500;
        private static final String EXPECTED_PAYLOAD =
                "7,71,7123,10071,10071,10712,17123,"
                + "7,71,7123,20171,20171,20812,27223,"
                + "7,71,7123,31071,131071,31712,131071,131712,138123";
        static Holder saved;

        public static class Holder {
            public int value;
        }

        public static class Marker extends RuntimeException {
            private static final long serialVersionUID = 1L;
        }

        public static void main(String[] args) throws Exception {
            new Holder();
            new Marker();
            PEATestUtils.compileConfiguredTargetsAtLevel4();

            String payload = exercise();
            Asserts.assertEquals(payload, EXPECTED_PAYLOAD, "exact loop-exit EH payload");
            for (int iteration = 1; iteration < ITERATIONS; iteration++) {
                Asserts.assertEquals(exercise(), EXPECTED_PAYLOAD,
                        "loop-exit EH payload at iteration " + iteration);
            }
            System.out.println("PEA-RESULT:" + payload);
        }

        private static String exercise() {
            int[] results = {
                    handlerRead(0, -1), handlerRead(1, -1), handlerRead(3, -1),
                    handlerRead(1, 0), handlerRead(3, 0), handlerRead(3, 1),
                    handlerRead(3, 2),
                    handlerWrite(0, -1), handlerWrite(1, -1), handlerWrite(3, -1),
                    handlerWrite(1, 0), handlerWrite(3, 0), handlerWrite(3, 1),
                    handlerWrite(3, 2),
                    normalConditionalEscape(0), normalConditionalEscape(1),
                    normalConditionalEscape(3),
                    caughtConditionalEscape(1, 0, false),
                    caughtConditionalEscape(1, 0, true),
                    caughtConditionalEscape(3, 1, false),
                    caughtConditionalEscape(3, 0, true),
                    caughtConditionalEscape(3, 1, true),
                    caughtConditionalEscape(3, 2, true)
            };
            return Arrays.toString(results).replace("[", "").replace("]", "")
                    .replace(" ", "");
        }

        private static int normalConditionalEscape(int trips) {
            saved = null;
            int result = handlerConditionalEscape(trips, -1, true);
            Asserts.assertNull(saved, "normal exit does not execute the handler sink");
            return result;
        }

        private static int caughtConditionalEscape(int trips, int throwAt,
                                                    boolean escape) {
            saved = null;
            int result = handlerConditionalEscape(trips, throwAt, escape);
            if (escape) {
                Asserts.assertNotNull(saved, "exception handler escaped one object");
            } else {
                Asserts.assertNull(saved, "non-escaping handler kept the object virtual");
            }
            return result;
        }

        public static int handlerRead(int trips, int throwAt) {
            Holder holder = new Holder();
            holder.value = 7;
            try {
                for (int i = 0; i < trips; i++) {
                    holder.value = holder.value * 10 + i + 1;
                    maybeThrow(i == throwAt);
                }
            } catch (Marker expected) {
                return 10_000 + holder.value;
            }
            return holder.value;
        }

        public static int handlerWrite(int trips, int throwAt) {
            Holder holder = new Holder();
            holder.value = 7;
            try {
                for (int i = 0; i < trips; i++) {
                    holder.value = holder.value * 10 + i + 1;
                    maybeThrow(i == throwAt);
                }
            } catch (Marker expected) {
                holder.value += 100;
                return 20_000 + holder.value;
            }
            return holder.value;
        }

        public static int handlerConditionalEscape(int trips, int throwAt,
                                                   boolean escape) {
            Holder holder = new Holder();
            holder.value = 7;
            try {
                for (int i = 0; i < trips; i++) {
                    holder.value = holder.value * 10 + i + 1;
                    maybeThrow(i == throwAt);
                }
            } catch (Marker expected) {
                holder.value += 1_000;
                int expectedValue = holder.value;
                if (escape) {
                    sink(holder);
                    Holder observed = saved;
                    if (observed != holder) {
                        return -1;
                    }
                    int observedValue = observed.value;
                    if (observedValue != expectedValue) {
                        return -2;
                    }
                    return 130_000 + observedValue;
                }
                return 30_000 + holder.value;
            }
            return holder.value;
        }

        public static void maybeThrow(boolean doThrow) {
            if (doThrow) {
                throw new Marker();
            }
        }

        public static void sink(Holder holder) {
            saved = holder;
        }
    }
}
