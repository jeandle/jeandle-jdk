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

package compiler.jeandle.pea;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.test.lib.Asserts;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;

/** Shared exact runner and parser support for Jeandle PEA jtreg tests. */
public final class PEATestUtils {
    private static final int FULL_OPTIMIZATION_LEVEL = 4;
    private static final long COMPILE_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);
    private static final String COMPILED_SENTINEL = "PEA-COMPILED:";
    private static final String RESULT_SENTINEL = "PEA-RESULT:";
    private static final String CONFIGURED_TARGETS_PROPERTY =
            "compiler.jeandle.pea.configuredTargets";
    private static final String LLVM_OPTIONS_PREFIX = "-XX:JeandleLLVMOptions=";
    private static final String PEA_OFF_EXTRA_LLVM_ERROR =
            "PEA-off runs do not accept extra LLVM options";
    private static final Set<String> MANAGED_VM_OPTIONS = Set.of(
            "UnlockDiagnosticVMOptions",
            "UnlockExperimentalVMOptions",
            "WhiteBoxAPI",
            "TieredCompilation",
            "UseJeandleCompiler",
            "JeandleDoPEA",
            "JeandleDumpIR",
            "JeandleDumpDirectory",
            "UseCompressedOops",
            "UseCompressedClassPointers",
            "UseOnStackReplacement",
            "CICompilerCount",
            "CompileCommand",
            "CompileCommandFile",
            "CompileOnly",
            "Flags",
            "VMOptionsFile",
            "LockingMode",
            "JeandleLLVMOptions");
    private static final Set<String> MANAGED_LLVM_OPTIONS = Set.of(
            "jeandle-pea-iterations",
            "jeandle-pea-analyze-function",
            "jeandle-pea-analyze-only",
            "jeandle-pea-max-array-length",
            "jeandle-dump-pea-ir-function",
            "jeandle-dump-pea-ir",
            "jeandle-dump-pea-stats",
            "jeandle-trace-pea");

    private static final String[] NO_COMPRESSED_OOPS = {
            "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"};
    private static final String[] WHITEBOX_FLAGS = {
            "-Xbootclasspath/a:.", "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI"};
    private static final Pattern MARKER = Pattern.compile(
            "^;; PEA-DUMP (before|after) iter=(\\d+) function (.*?)"
                    + "(?: transform_idle=(true|false|0|1))?$"
    );
    private static final Pattern STATS = Pattern.compile(
            "^;; PEA stats @(.*): NeverEscapes=(\\d+) PartiallyEscapes=(\\d+)"
                    + " AlwaysEscapes=(\\d+)$"
    );
    private static final Pattern SUMMARY = Pattern.compile(
            "^;; PEA-SUMMARY function (.*?) rounds=(\\S+) stop=(\\S+)$"
    );
    private static final Pattern EFFECT = Pattern.compile(
            "^PEA: (\\S+) function=(@(?:\"(?:\\\\[0-9A-Fa-f]{2}|[^\"\\\\])*\""
                    + "|[-A-Za-z$._0-9]+))(?:\\s+(.*))?$"
    );
    private static final Set<String> KNOWN_TYPED_EFFECT_KINDS = Set.of(
            "ReplaceLoad",
            "ReplaceCall",
            "EliminateStore",
            "EliminateAllocation",
            "Materialize",
            "CreatePHI",
            "RewriteDeoptPool");
    private static final Pattern FINAL_EFFECT_SEQUENCE = Pattern.compile(
            "(?:^| )seq=(\\S*)$"
    );
    private static final Pattern EFFECT_SEQUENCE_FIELD = Pattern.compile(
            "(?<!\\S)seq=\\S*"
    );
    private static final String TARGET_EFFECT_SEQUENCE = "target= seq=";
    private static final Pattern LOCK_REPLAY = Pattern.compile(
            "^PEA: LockReplay function=(@(?:\"(?:\\\\[0-9A-Fa-f]{2}|[^\"\\\\])*\""
                    + "|[-A-Za-z$._0-9]+)) logical_escape=([0-9]+) batch=([0-9]+)"
                    + " source=([0-9]+) receiver_vo=([0-9]+)"
                    + " depth=([0-9]+) ordinal=([0-9]+)$"
    );
    private static final String LLVM_LOCAL_NAME =
            "%(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final Pattern CALL_OR_INVOKE_OPCODE = Pattern.compile(
            "^(?:" + LLVM_LOCAL_NAME + "\\s*=\\s*)?"
                    + "(?:(?:musttail|notail|tail)\\s+)?(call|invoke)\\b");
    private static final Pattern INLINE_ASM_CALLEE = Pattern.compile("\\basm\\b");
    private static final Pattern CONSTANT_CALLEE = Pattern.compile(
            "\\b(?:null|undef|poison|zeroinitializer)\\s*\\(");
    // LangRef defines exactly these two unparenthesized constant operators
    // whose operand is a global function name.
    private static final Set<String> NAMED_CONSTANT_CALLEE_OPERATORS =
            Set.of("dso_local_equivalent", "no_cfi");
    private static final Pattern ASSIGNED_INSTRUCTION = Pattern.compile(
            "^(" + LLVM_LOCAL_NAME + ")\\s*=");
    private static final Pattern ASSIGNMENT_ONLY = Pattern.compile(
            "^" + LLVM_LOCAL_NAME + "\\s*=\\s*$");
    private static final Pattern DEBUG_RECORD = Pattern.compile(
            "^#dbg_[A-Za-z0-9_]+\\b");
    private static final Pattern UNASSIGNED_INSTRUCTION = Pattern.compile(
            "^(?:(?:musttail|notail|tail)\\s+call|call|invoke|callbr|ret|br|switch|"
                    + "indirectbr|resume|catchswitch|catchret|cleanupret|unreachable|"
                    + "store|fence)\\b");

    private PEATestUtils() {}

    // JDK-generated LLVM symbols append the ciMethod identity as a numeric
    // suffix. Try the stable name first, then accept that runtime suffix.
    private static boolean matchesRuntimeFunctionName(String actual,
                                                       String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        return stableRuntimeFunctionName(actual).equals(expected);
    }

    private static String stableRuntimeFunctionName(String function) {
        boolean root = function.endsWith(".root");
        String candidate = root
                ? function.substring(0, function.length() - ".root".length())
                : function;
        int identitySeparator = candidate.lastIndexOf('.');
        if (identitySeparator < 0 || identitySeparator == candidate.length() - 1) {
            return function;
        }
        String stable = candidate.substring(0, identitySeparator);
        String identity = candidate.substring(identitySeparator + 1);
        int closeDescriptor = stable.lastIndexOf(')');
        if (stable.lastIndexOf('(', closeDescriptor) < 0
                || closeDescriptor == stable.length() - 1
                || !identity.chars().allMatch(Character::isDigit)) {
            return function;
        }
        return stable + (root ? ".root" : "");
    }

    // PEA assertions use stable Java method names. Remove only the numeric
    // identity after a JVM method descriptor; ordinary LLVM .1/.2 suffixes
    // and unrelated numeric constants remain visible to the tests.
    private static String normalizeRuntimeFunctionSymbols(String line) {
        StringBuilder normalized = null;
        int copiedThrough = 0;
        int searchFrom = 0;
        while (true) {
            int at = line.indexOf('@', searchFrom);
            if (at < 0) {
                break;
            }
            ParsedOperand operand;
            try {
                operand = parseLLVMNamedOperand(line, at);
            } catch (IllegalArgumentException malformed) {
                searchFrom = at + 1;
                continue;
            }
            searchFrom = operand.end;
            String stable = stableRuntimeFunctionName(operand.value);
            if (stable.equals(operand.value)) {
                continue;
            }

            boolean root = operand.value.endsWith(".root");
            int stableLengthWithoutRoot = stable.length()
                    - (root ? ".root".length() : 0);
            int identityEnd = operand.value.length()
                    - (root ? ".root".length() : 0);
            String identitySuffix = operand.value.substring(
                    stableLengthWithoutRoot, identityEnd);
            String rawOperand = line.substring(at, operand.end);
            int suffixAt = rawOperand.lastIndexOf(identitySuffix);
            if (suffixAt < 0) {
                throw new IllegalStateException(
                        "Runtime function identity is not present in LLVM operand: "
                                + rawOperand);
            }
            if (normalized == null) {
                normalized = new StringBuilder(line.length());
            }
            normalized.append(line, copiedThrough, at + suffixAt)
                    .append(rawOperand, suffixAt + identitySuffix.length(),
                            rawOperand.length());
            copiedThrough = operand.end;
        }
        if (normalized == null) {
            return line;
        }
        return normalized.append(line, copiedThrough, line.length()).toString();
    }

    /** Exact identity for one Java method in HotSpot commands and Jeandle IR. */
    public static final class MethodId {
        private final Method method;
        private final String jvmDescriptor;
        private final String dumpStem;
        private final String llvmFunctionName;
        private final String compileCommandPattern;
        private final boolean osr;
        // A compilation root's LLVM function is "<name>.root" (OSR root:
        // "__jeandle_osr.<name>.root"), disambiguating it from a recursive
        // CHA-devirtualization target that reuses the base name. Callee/inlined
        // methods use the plain base name, so only the compilation root sets
        // root=true.
        private final boolean root;

        private MethodId(Method method, boolean osr, boolean root) {
            this.method = Objects.requireNonNull(method);
            this.osr = osr;
            this.root = root;
            this.jvmDescriptor = MethodType.methodType(
                    method.getReturnType(), method.getParameterTypes()).descriptorString();
            this.dumpStem = method.getDeclaringClass().getName().replace('.', '_')
                    + "_" + method.getName();
            this.llvmFunctionName = (osr ? "__jeandle_osr." : "")
                    + dumpStem + jvmDescriptor + (root ? ".root" : "");
            this.compileCommandPattern = method.getDeclaringClass().getName() + "::"
                    + method.getName() + jvmDescriptor;
        }

        /** Non-root identity for a callee or inlined method (base name only). */
        public static MethodId of(Method method) {
            return new MethodId(method, false, false);
        }

        /** Root identity for a normal (non-OSR) compilation unit. */
        public static MethodId rootOf(Method method) {
            return new MethodId(method, false, true);
        }

        /** Root identity for an OSR compilation unit. */
        public static MethodId osr(Method method) {
            return new MethodId(method, true, true);
        }

        /** The root-compilation identity for this method (no-op if already a root). */
        public MethodId asRoot() {
            return root ? this : new MethodId(method, osr, true);
        }

        public Method method() {
            return method;
        }

        public String jvmDescriptor() {
            return jvmDescriptor;
        }

        public String dumpStem() {
            return dumpStem;
        }

        public String llvmFunctionName() {
            return llvmFunctionName;
        }

        public String compileCommandPattern() {
            return compileCommandPattern;
        }

        public boolean isOSR() {
            return osr;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MethodId id
                    && llvmFunctionName.equals(id.llvmFunctionName)
                    && compileCommandPattern.equals(id.compileCommandPattern)
                    && osr == id.osr;
        }

        @Override
        public int hashCode() {
            return Objects.hash(llvmFunctionName, compileCommandPattern, osr);
        }

        @Override
        public String toString() {
            return llvmFunctionName;
        }
    }

    /** Create an exact multi-target run with PEA diagnostics and IR dumps. */
    public static RunBuilder shapeRun(String wrapperFQN, Method... targets) {
        return shapeRun(wrapperFQN, methodIds(targets));
    }

    /** Create an exact multi-target run with PEA diagnostics and IR dumps. */
    public static RunBuilder shapeRun(String wrapperFQN, MethodId... targets) {
        return new RunBuilder(wrapperFQN, true, rootAll(targets));
    }

    /** Create an exact multi-target run for behavior comparison. */
    public static RunBuilder behaviorRun(String wrapperFQN, Method... targets) {
        return behaviorRun(wrapperFQN, methodIds(targets));
    }

    /** Create an exact multi-target run for behavior comparison. */
    public static RunBuilder behaviorRun(String wrapperFQN, MethodId... targets) {
        return new RunBuilder(wrapperFQN, false, rootAll(targets));
    }

    /** Builder for one child VM. Every target is explicit and descriptor-qualified. */
    public static final class RunBuilder {
        private static final int MAX_PEA_ITERATIONS = 16;
        private final String wrapperFQN;
        private final boolean shape;
        private final List<MethodId> targets;
        private final List<String> compileOnly;
        private final List<String> inline;
        private final List<String> dontInline;
        private final List<String> extraFlags;
        private final List<String> extraLLVMOptions;
        private boolean peaOn = true;
        private Integer peaIterations;
        private Integer maxArrayLength;
        private Integer lockingMode;
        private boolean tieredCompilation;
        private boolean xcomp;
        private boolean keepDumps;

        private RunBuilder(String wrapperFQN, boolean shape, MethodId... methodIds) {
            this.wrapperFQN = Objects.requireNonNull(wrapperFQN);
            this.shape = shape;
            if (methodIds.length == 0) {
                throw new IllegalArgumentException("At least one explicit target method is required");
            }
            LinkedHashMap<String, MethodId> unique = new LinkedHashMap<>();
            for (MethodId id : methodIds) {
                Objects.requireNonNull(id);
                if (!id.method().getDeclaringClass().getName().equals(wrapperFQN)) {
                    throw new IllegalArgumentException("Target " + id
                            + " is not declared by child wrapper " + wrapperFQN);
                }
                if (unique.put(id.llvmFunctionName(), id) != null) {
                    throw new IllegalArgumentException("Duplicate target " + id);
                }
            }
            this.targets = List.copyOf(unique.values());
            this.compileOnly = targets.stream().map(MethodId::compileCommandPattern)
                    .collect(Collectors.toCollection(ArrayList::new));
            this.inline = new ArrayList<>();
            this.dontInline = new ArrayList<>();
            this.extraFlags = new ArrayList<>();
            this.extraLLVMOptions = new ArrayList<>();
        }

        private RunBuilder(RunBuilder other) {
            this.wrapperFQN = other.wrapperFQN;
            this.shape = other.shape;
            this.targets = other.targets;
            this.compileOnly = new ArrayList<>(other.compileOnly);
            this.inline = new ArrayList<>(other.inline);
            this.dontInline = new ArrayList<>(other.dontInline);
            this.extraFlags = new ArrayList<>(other.extraFlags);
            this.extraLLVMOptions = new ArrayList<>(other.extraLLVMOptions);
            this.peaOn = other.peaOn;
            this.peaIterations = other.peaIterations;
            this.maxArrayLength = other.maxArrayLength;
            this.lockingMode = other.lockingMode;
            this.tieredCompilation = other.tieredCompilation;
            this.xcomp = other.xcomp;
            this.keepDumps = other.keepDumps;
        }

        public RunBuilder compileOnly(Method method) {
            return compileOnly((Executable) method);
        }

        public RunBuilder compileOnly(Executable executable) {
            addUnique(compileOnly, compileCommandPattern(executable), "compileonly");
            return this;
        }

        public RunBuilder compileonly(Method method) {
            return compileOnly(method);
        }

        public RunBuilder compileonly(Executable executable) {
            return compileOnly(executable);
        }

        public RunBuilder dontinline(Method method) {
            return dontinline((Executable) method);
        }

        public RunBuilder dontinline(Executable executable) {
            String pattern = compileCommandPattern(executable);
            rejectConflictingInlineCommand(pattern, inline, "inline", "dontinline");
            addUnique(dontInline, pattern, "dontinline");
            return this;
        }

        public RunBuilder inline(Method method) {
            return inline((Executable) method);
        }

        public RunBuilder inline(Executable executable) {
            String pattern = compileCommandPattern(executable);
            rejectConflictingInlineCommand(pattern, dontInline, "dontinline", "inline");
            addUnique(inline, pattern, "inline");
            return this;
        }

        public RunBuilder extraFlags(String... flags) {
            for (String flag : flags) {
                rejectRawExecutionMode(flag);
                rejectManagedVMFlag(flag);
                extraFlags.add(flag);
            }
            return this;
        }

        public RunBuilder extraLLVMOptions(String... options) {
            if (!peaOn && options.length != 0) {
                throw new IllegalStateException(PEA_OFF_EXTRA_LLVM_ERROR);
            }
            for (String option : options) {
                rejectManagedLLVMOption(option);
            }
            for (String option : options) {
                extraLLVMOptions.add(option);
            }
            return this;
        }

        public RunBuilder peaIterations(int iterations) {
            if (iterations < 1 || iterations > MAX_PEA_ITERATIONS) {
                throw new IllegalArgumentException(
                        "PEA iterations must be in [1, " + MAX_PEA_ITERATIONS + "]");
            }
            if (!peaOn) {
                throw new IllegalStateException("PEA-off runs force zero iterations");
            }
            peaIterations = iterations;
            return this;
        }

        /** Set the maximum constant array length eligible for PEA virtualization. */
        public RunBuilder maxArrayLength(int length) {
            if (length < 0) {
                throw new IllegalArgumentException("Maximum array length must be non-negative");
            }
            if (maxArrayLength != null) {
                throw new IllegalStateException("Maximum array length is already configured");
            }
            maxArrayLength = length;
            return this;
        }

        public RunBuilder lockingMode(int mode) {
            if (mode != 1 && mode != 2) {
                throw new IllegalArgumentException("LockingMode must be 1 or 2");
            }
            if (lockingMode != null) {
                throw new IllegalStateException("LockingMode is already configured");
            }
            lockingMode = mode;
            return this;
        }

        /** Run the child with tiered compilation enabled. */
        public RunBuilder tieredCompilation() {
            if (xcomp) {
                throw new IllegalStateException("-Xcomp and tiered compilation are exclusive");
            }
            tieredCompilation = true;
            return this;
        }

        /** Run a behavior-only child with eager compilation. */
        public RunBuilder xcomp() {
            if (shape) {
                throw new IllegalStateException("-Xcomp is only supported for behavior runs");
            }
            if (tieredCompilation) {
                throw new IllegalStateException("-Xcomp and tiered compilation are exclusive");
            }
            xcomp = true;
            return this;
        }

        public RunBuilder peaOff() {
            if (shape) {
                throw new IllegalStateException("A shape run requires PEA diagnostics");
            }
            if (!extraLLVMOptions.isEmpty()) {
                throw new IllegalStateException(PEA_OFF_EXTRA_LLVM_ERROR);
            }
            peaOn = false;
            peaIterations = null;
            return this;
        }

        public RunBuilder keepDumps() {
            keepDumps = true;
            return this;
        }

        public RunBuilder keepDumps(boolean keep) {
            keepDumps = keep;
            return this;
        }

        public RunResult run() throws Exception {
            Path dumpDir = Files.createTempDirectory("jeandle-pea-dumps-");
            boolean handedOff = false;
            try {
                List<String> command = command(dumpDir);
                ProcessBuilder pb = ProcessTools.createLimitedTestJavaProcessBuilder(command);
                OutputAnalyzer output = ProcessTools.executeCommand(pb);
                output.shouldHaveExitValue(0);
                RunResult result = new RunResult(output, command, dumpDir, targets, keepDumps, shape);
                result.assertRequestedMethodsCompiled();
                handedOff = true;
                return result;
            } finally {
                if (!handedOff && !keepDumps) {
                    deleteTree(dumpDir);
                }
            }
        }

        public void runPEAOnOffEquivalent() throws Exception {
            runPEAOnOffEquivalentImpl();
        }

        public PEAOnOffResult runPEAOnOffEquivalentWithCommands() throws Exception {
            return runPEAOnOffEquivalentImpl();
        }

        private PEAOnOffResult runPEAOnOffEquivalentImpl() throws Exception {
            RunBuilder onBuilder = new RunBuilder(this);
            onBuilder.peaOn = true;
            RunBuilder offBuilder = new RunBuilder(onBuilder).peaOff();
            try (RunResult on = onBuilder.run(); RunResult off = offBuilder.run()) {
                String onPayload = exactResultPayload(on.output().getStdout());
                String offPayload = exactResultPayload(off.output().getStdout());
                Asserts.assertEquals(onPayload, offPayload,
                        "PEA-on/off result payload mismatch");
                return new PEAOnOffResult(on.command(), off.command());
            }
        }

        private List<String> command(Path dumpDir) {
            ArrayList<String> command = new ArrayList<>();
            command.addAll(Arrays.asList(WHITEBOX_FLAGS));
            if (lockingMode != null) {
                command.add("-XX:+UnlockExperimentalVMOptions");
                command.add("-XX:LockingMode=" + lockingMode);
            }
            command.add("-Xbatch");
            if (!tieredCompilation) {
                command.add("-XX:-TieredCompilation");
            }
            if (xcomp) {
                command.add("-Xcomp");
            }
            if (targets.stream().anyMatch(MethodId::isOSR)) {
                command.add("-XX:+UseOnStackReplacement");
            }
            command.add("-XX:+UseJeandleCompiler");
            command.add(peaOn ? "-XX:+JeandleDoPEA" : "-XX:-JeandleDoPEA");
            command.add("-Xlog:jeandle=debug");
            command.add(shape ? "-XX:+JeandleDumpIR" : "-XX:-JeandleDumpIR");
            command.add("-XX:JeandleDumpDirectory=" + dumpDir);
            command.addAll(Arrays.asList(NO_COMPRESSED_OOPS));
            command.add("-D" + CONFIGURED_TARGETS_PROPERTY + "="
                    + targets.stream().map(PEATestUtils::configuredTarget)
                            .collect(Collectors.joining(",")));
            if (shape) {
                command.add("-XX:CICompilerCount=1");
            }
            for (String pattern : compileOnly) {
                command.add("-XX:CompileCommand=compileonly," + pattern);
            }
            for (String pattern : inline) {
                command.add("-XX:CompileCommand=inline," + pattern);
            }
            for (String pattern : dontInline) {
                command.add("-XX:CompileCommand=dontinline," + pattern);
            }

            List<String> llvmOptions = new ArrayList<>();
            if (!peaOn) {
                llvmOptions.add("-jeandle-pea-iterations=0");
                if (maxArrayLength != null) {
                    llvmOptions.add("-jeandle-pea-max-array-length=" + maxArrayLength);
                }
            } else {
                if (peaIterations != null) {
                    llvmOptions.add("-jeandle-pea-iterations=" + peaIterations);
                }
                if (maxArrayLength != null) {
                    llvmOptions.add("-jeandle-pea-max-array-length=" + maxArrayLength);
                }
                if (shape) {
                    llvmOptions.add("-jeandle-trace-pea");
                    llvmOptions.add("-jeandle-dump-pea-stats");
                    for (MethodId id : targets) {
                        llvmOptions.add("-jeandle-pea-analyze-function=" + id.llvmFunctionName());
                        llvmOptions.add("-jeandle-dump-pea-ir-function="
                                + id.llvmFunctionName());
                    }
                }
                llvmOptions.addAll(extraLLVMOptions);
            }
            if (!llvmOptions.isEmpty()) {
                command.add(LLVM_OPTIONS_PREFIX + String.join(" ", llvmOptions));
            }
            command.addAll(extraFlags);
            command.add(wrapperFQN);
            return List.copyOf(command);
        }
    }

    /** Immutable commands from a successful PEA-on/off behavior comparison. */
    public record PEAOnOffResult(List<String> onCommand, List<String> offCommand) {
        public PEAOnOffResult {
            onCommand = List.copyOf(Objects.requireNonNull(onCommand));
            offCommand = List.copyOf(Objects.requireNonNull(offCommand));
        }
    }

    /** Result of one child VM. Closing it removes its unique dump directory. */
    public static final class RunResult implements AutoCloseable {
        private final OutputAnalyzer output;
        private final List<String> command;
        private final Path dumpDir;
        private final List<MethodId> targets;
        private final boolean keepDumps;
        private final boolean shape;
        private PEAReport reports;

        private RunResult(OutputAnalyzer output, List<String> command, Path dumpDir,
                          List<MethodId> targets, boolean keepDumps, boolean shape) {
            this.output = output;
            this.command = command;
            this.dumpDir = dumpDir;
            this.targets = targets;
            this.keepDumps = keepDumps;
            this.shape = shape;
        }

        public OutputAnalyzer output() {
            return output;
        }

        public List<String> command() {
            return command;
        }

        public Path dumpDir() {
            return dumpDir;
        }

        public PEAReport report(Method method) {
            return report(MethodId.rootOf(method));
        }

        public PEAReport report(MethodId method) {
            if (!shape) {
                throw new IllegalStateException("Behavior runs do not collect PEA shape reports");
            }
            if (reports == null) {
                reports = PEAReport.parse(output.getStderr(), targets.toArray(MethodId[]::new));
            }
            return reports.report(method.asRoot());
        }

        public IRBody frontendIR(Method method) throws IOException {
            return frontendIR(MethodId.rootOf(method));
        }

        public IRBody frontendIR(MethodId method) throws IOException {
            return PEATestUtils.frontendIR(dumpDir, method.asRoot());
        }

        public IRBody finalIR(Method method) throws IOException {
            return finalIR(MethodId.rootOf(method));
        }

        public IRBody finalIR(MethodId method) throws IOException {
            return PEATestUtils.finalIR(dumpDir, method.asRoot());
        }

        private void assertRequestedMethodsCompiled() {
            List<String> lines = splitLines(output.getStdout());
            long allSentinels = lines.stream().filter(l -> l.startsWith(COMPILED_SENTINEL)).count();
            Asserts.assertEquals(allSentinels, (long) targets.size(),
                    "Expected exactly one compilation sentinel per requested method");
            for (MethodId id : targets) {
                String sentinel = compiledSentinel(id);
                long count = lines.stream().filter(sentinel::equals).count();
                Asserts.assertEquals(count, 1L, "Missing or duplicate sentinel " + sentinel);
            }
        }

        @Override
        public void close() throws IOException {
            if (!keepDumps) {
                deleteTree(dumpDir);
            }
        }
    }

    /** Enqueue each target at level 4 and wait for exact compilation confirmation. */
    public static void enqueueAndAwaitLevel4(Method... methods) throws InterruptedException {
        Objects.requireNonNull(methods);
        if (methods.length == 0) {
            throw new IllegalArgumentException("At least one method is required");
        }
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (Method method : methods) {
            Objects.requireNonNull(method);
            if (whiteBox.isMethodCompiled(method)
                    && whiteBox.getMethodCompilationLevel(method)
                            == FULL_OPTIMIZATION_LEVEL) {
                continue;
            }
            if (!whiteBox.isMethodCompilable(method, FULL_OPTIMIZATION_LEVEL)) {
                throw new RuntimeException("Method is not compilable at level 4: "
                        + MethodId.of(method));
            }
            if (!whiteBox.enqueueMethodForCompilation(
                    method, FULL_OPTIMIZATION_LEVEL)) {
                throw new RuntimeException("Level-4 compilation enqueue rejected for "
                        + MethodId.of(method));
            }
            long deadline = System.nanoTime() + COMPILE_TIMEOUT_NANOS;
            while (!whiteBox.isMethodCompiled(method)
                    || whiteBox.getMethodCompilationLevel(method)
                            != FULL_OPTIMIZATION_LEVEL) {
                if (!whiteBox.isMethodCompilable(method, FULL_OPTIMIZATION_LEVEL)) {
                    throw new RuntimeException("Method became not compilable at level 4: "
                            + MethodId.of(method));
                }
                if (System.nanoTime() - deadline >= 0) {
                    throw new RuntimeException("Timed out waiting for level-4 compilation of "
                            + MethodId.of(method));
                }
                Thread.sleep(10);
            }
        }
    }

    /**
     * Compile the exact normal-entry descriptor-qualified targets selected by the parent
     * runner. OSR targets must be naturally triggered by their child wrapper and then
     * confirmed with {@link #confirmLevel4(MethodId...)}.
     */
    public static void compileConfiguredTargetsAtLevel4() throws Exception {
        String configured = System.getProperty(CONFIGURED_TARGETS_PROPERTY);
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("No configured PEA targets");
        }
        ArrayList<Method> methods = new ArrayList<>();
        for (String target : configured.split(",", -1)) {
            int separator = target.indexOf('#');
            int descriptor = target.indexOf('(', separator + 1);
            if (separator <= 0 || descriptor <= separator + 1) {
                throw new IllegalStateException("Malformed configured PEA target " + target);
            }
            String className = target.substring(0, separator);
            String methodName = target.substring(separator + 1, descriptor);
            String jvmDescriptor = target.substring(descriptor);
            Class<?> holder = Class.forName(className);
            Method match = null;
            for (Method candidate : holder.getDeclaredMethods()) {
                if (candidate.getName().equals(methodName)
                        && descriptor(candidate).equals(jvmDescriptor)) {
                    if (match != null) {
                        throw new IllegalStateException("Ambiguous configured PEA target " + target);
                    }
                    match = candidate;
                }
            }
            if (match == null) {
                throw new IllegalStateException("Configured PEA target not found " + target);
            }
            methods.add(match);
        }
        enqueueAndAwaitLevel4(methods.toArray(Method[]::new));
        confirmLevel4(methods.toArray(Method[]::new));
    }

    /** Confirm level 4 in the child and publish one exact parent-visible sentinel per method. */
    public static void confirmLevel4(Method... methods) {
        confirmLevel4(methodIds(methods));
    }

    /** Confirm one exact normal or OSR level-4 nmethod in the child. */
    public static void confirmLevel4(MethodId... methods) {
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        for (MethodId method : methods) {
            Objects.requireNonNull(method);
            int level = whiteBox.getMethodCompilationLevel(method.method(), method.isOSR());
            if (!whiteBox.isMethodCompiled(method.method(), method.isOSR())
                    || level != FULL_OPTIMIZATION_LEVEL) {
                throw new RuntimeException(method + " compiled at level " + level
                        + ", expected " + FULL_OPTIMIZATION_LEVEL);
            }
            System.out.println(compiledSentinel(method));
        }
    }

    /**
     * Immutable evidence that one compiled target was deoptimized. For a normal
     * nmethod, {@code frameDeoptimized} records WhiteBox's active-frame observation.
     * For an OSR nmethod, it records that the OSR nmethod was synchronously unpacked
     * and is no longer compiled when the deoptimization VM operation returns.
     */
    public record ActiveFrameDeoptEvidence(
            MethodId target, int frameDepth, int compilationLevel,
            int markedNMethods, boolean frameDeoptimized) {
        public ActiveFrameDeoptEvidence {
            Objects.requireNonNull(target);
            if (frameDepth < 0 || compilationLevel != FULL_OPTIMIZATION_LEVEL
                    || markedNMethods != 1 || !frameDeoptimized) {
                throw new IllegalArgumentException(
                        "Active-frame evidence must describe one marked level-4 nmethod");
            }
        }
    }

    /**
     * Mark one exact level-4 nmethod and prove it was deoptimized without globally
     * deoptimizing unrelated frames. For a normal nmethod, the frame depth is relative
     * to this helper's caller. OSR nmethods are synchronously unpacked by the
     * deoptimization VM operation, so their evidence is their disappearance from the
     * OSR compiled-method table when this helper returns.
     */
    public static ActiveFrameDeoptEvidence deoptimizeActiveFrame(
            Method target, int frameDepth) {
        return deoptimizeActiveFrameImpl(MethodId.of(target), frameDepth);
    }

    /** Mark one exact normal or OSR level-4 nmethod for active-frame deoptimization. */
    public static ActiveFrameDeoptEvidence deoptimizeActiveFrame(
            MethodId target, int frameDepth) {
        return deoptimizeActiveFrameImpl(target, frameDepth);
    }

    private static ActiveFrameDeoptEvidence deoptimizeActiveFrameImpl(
            MethodId target, int frameDepth) {
        Objects.requireNonNull(target);
        if (frameDepth < 0) {
            throw new IllegalArgumentException("Frame depth must be non-negative");
        }
        WhiteBox whiteBox = WhiteBox.getWhiteBox();
        int level = whiteBox.getMethodCompilationLevel(target.method(), target.isOSR());
        if (!whiteBox.isMethodCompiled(target.method(), target.isOSR())
                || level != FULL_OPTIMIZATION_LEVEL) {
            throw new RuntimeException(target
                    + " must be compiled at level 4 before active-frame deoptimization"
                    + " (compiled=" + whiteBox.isMethodCompiled(target.method(), target.isOSR())
                    + ", level=" + level + ")");
        }
        int markedNMethods = whiteBox.deoptimizeMethod(target.method(), target.isOSR());
        if (markedNMethods != 1) {
            throw new RuntimeException("Expected exactly one marked nmethod for "
                    + target + ", got " + markedNMethods);
        }
        boolean frameDeoptimized;
        if (target.isOSR()) {
            frameDeoptimized = !whiteBox.isMethodCompiled(target.method(), true);
        } else {
            // WhiteBox counts both the public overload and this shared implementation.
            frameDeoptimized = whiteBox.isFrameDeoptimized(frameDepth + 2);
        }
        if (!frameDeoptimized) {
            throw new RuntimeException(target.isOSR()
                    ? "OSR nmethod remained compiled after deoptimization for " + target
                    : "Frame at depth " + frameDepth
                            + " was not deoptimized for " + target);
        }
        return new ActiveFrameDeoptEvidence(
                target, frameDepth, level,
                markedNMethods, frameDeoptimized);
    }

    private static String compiledSentinel(MethodId id) {
        return COMPILED_SENTINEL + id.llvmFunctionName() + ":level=4";
    }

    private static String configuredTarget(MethodId id) {
        return id.method().getDeclaringClass().getName() + "#"
                + id.method().getName() + id.jvmDescriptor();
    }

    private static String descriptor(Method method) {
        return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                .descriptorString();
    }

    /**
     * Parsed effect attributed to an exact LLVM function and PEA round.
     * The checked sequence is typed separately and removed from detail.
     */
    public static final class PEAEffect {
        private final String kind;
        private final String functionName;
        private final int iteration;
        private final long sequence;
        private final String detail;

        private PEAEffect(String kind, String functionName, int iteration,
                          long sequence, String detail) {
            this.kind = kind;
            this.functionName = functionName;
            this.iteration = iteration;
            this.sequence = sequence;
            this.detail = detail;
        }

        public String kind() {
            return kind;
        }

        public String functionName() {
            return functionName;
        }

        public int iteration() {
            return iteration;
        }

        public long sequence() {
            return sequence;
        }

        public String detail() {
            return detail;
        }
    }

    /** Exact typed values from one LockReplay diagnostic. */
    public record PEALockReplay(int logicalEscape, int batch, int source,
                                int receiverVO, int depth, int ordinal) {
        public PEALockReplay {
            if (logicalEscape < 0 || batch < 0 || source < 0
                    || receiverVO < 0 || depth < 0 || ordinal < 0) {
                throw new IllegalArgumentException("LockReplay values must be non-negative");
            }
        }

        public PEALockReplayGroup group() {
            return new PEALockReplayGroup(logicalEscape, batch, source);
        }

        public PEALockReplayPhysicalGroup physicalGroup() {
            return new PEALockReplayPhysicalGroup(batch, source);
        }
    }

    /** Why the iterative PEA driver stopped processing a function. */
    public enum PEAStopReason {
        FIXPOINT,
        ITERATION_CAP;

        private static PEAStopReason parse(MethodId method, String value) {
            return switch (value) {
                case "fixpoint" -> FIXPOINT;
                case "iteration-cap" -> ITERATION_CAP;
                default -> throw malformed(method, "unknown PEA summary stop reason: " + value);
            };
        }
    }

    /** One logical consumer's associations within a physical replay batch/path. */
    public record PEALockReplayGroup(int logicalEscape, int batch, int source) {
        public PEALockReplayGroup {
            if (logicalEscape < 0 || batch < 0 || source < 0) {
                throw new IllegalArgumentException("LockReplay group values must be non-negative");
            }
        }
    }

    /** One transform-consumed physical replay batch/path. */
    public record PEALockReplayPhysicalGroup(int batch, int source) {
        public PEALockReplayPhysicalGroup {
            if (batch < 0 || source < 0) {
                throw new IllegalArgumentException(
                        "Physical LockReplay group values must be non-negative");
            }
        }
    }

    private record LockReplayGrouping(
            Map<PEALockReplayGroup, List<PEALockReplay>> logical,
            Map<PEALockReplayPhysicalGroup, List<PEALockReplay>> physical) {}

    /** One complete before/stats/effects/after PEA iteration. */
    public static final class PEARound {
        private final int iteration;
        private final IRBody before;
        private final IRBody after;
        private final int neverEscapes;
        private final int partiallyEscapes;
        private final int alwaysEscapes;
        private final List<PEAEffect> effects;
        private final List<PEALockReplay> lockReplays;
        private final Map<PEALockReplayGroup, List<PEALockReplay>> lockReplayGroups;
        private final Map<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                lockReplayPhysicalGroups;
        private final boolean hasStats;
        private final boolean transformIdle;

        private PEARound(int iteration, IRBody before, IRBody after,
                         int neverEscapes, int partiallyEscapes, int alwaysEscapes,
                         List<PEAEffect> effects, List<PEALockReplay> lockReplays,
                         boolean hasStats, boolean transformIdle) {
            this.iteration = iteration;
            this.before = before;
            this.after = after;
            this.neverEscapes = neverEscapes;
            this.partiallyEscapes = partiallyEscapes;
            this.alwaysEscapes = alwaysEscapes;
            this.effects = List.copyOf(effects);
            this.lockReplays = List.copyOf(lockReplays);
            LockReplayGrouping grouping = groupLockReplays(this.lockReplays, iteration);
            this.lockReplayGroups = grouping.logical();
            this.lockReplayPhysicalGroups = grouping.physical();
            this.hasStats = hasStats;
            this.transformIdle = transformIdle;
        }

        public int iteration() {
            return iteration;
        }

        public IRBody before() {
            return before;
        }

        public IRBody after() {
            return after;
        }

        public int neverEscapes() {
            requireStats();
            return neverEscapes;
        }

        public int partiallyEscapes() {
            requireStats();
            return partiallyEscapes;
        }

        public int alwaysEscapes() {
            requireStats();
            return alwaysEscapes;
        }

        public boolean hasStats() {
            return hasStats;
        }

        public List<PEAEffect> effects() {
            return effects;
        }

        public List<PEALockReplay> lockReplays() {
            return lockReplays;
        }

        public Map<PEALockReplayGroup, List<PEALockReplay>> lockReplayGroups() {
            return lockReplayGroups;
        }

        public Map<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                lockReplayPhysicalGroups() {
            return lockReplayPhysicalGroups;
        }

        public void assertLockReplaySequence(PEALockReplayGroup group,
                                             PEALockReplay... expected) {
            Objects.requireNonNull(group);
            Objects.requireNonNull(expected);
            List<PEALockReplay> actual = lockReplayGroups.getOrDefault(group, List.of());
            Asserts.assertEquals(actual, List.of(expected),
                    "LockReplay sequence for " + group + " in round " + iteration);
        }

        public long distinctLockReplaySourceCount(int logicalEscape) {
            if (logicalEscape < 0) {
                throw new IllegalArgumentException("logical escape must be non-negative");
            }
            return lockReplayGroups.keySet().stream()
                    .filter(group -> group.logicalEscape() == logicalEscape)
                    .map(PEALockReplayGroup::source)
                    .distinct()
                    .count();
        }

        public boolean transformIdle() {
            return transformIdle;
        }

        public long effectCount(String kind, String... detailParts) {
            return matchingEffects(kind, detailParts).size();
        }

        public PEAEffect uniqueEffect(String kind, String... detailParts) {
            List<PEAEffect> matches = matchingEffects(kind, detailParts);
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected exactly one " + kind
                        + " effect matching " + Arrays.toString(detailParts)
                        + " in round " + iteration + ", got " + matches.size());
            }
            return matches.get(0);
        }

        private List<PEAEffect> matchingEffects(String kind, String... detailParts) {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(detailParts);
            for (String detailPart : detailParts) {
                Objects.requireNonNull(detailPart);
            }
            return effects.stream()
                    .filter(effect -> effect.kind().equals(kind))
                    .filter(effect -> Arrays.stream(detailParts)
                            .allMatch(effect.detail()::contains))
                    .collect(Collectors.toUnmodifiableList());
        }

        private void requireStats() {
            if (!hasStats) {
                throw new IllegalStateException("No PEA stats for round " + iteration);
            }
        }

        private static LockReplayGrouping groupLockReplays(
                List<PEALockReplay> replays, int iteration) {
            LinkedHashMap<PEALockReplayGroup, List<PEALockReplay>> logical =
                    new LinkedHashMap<>();
            LinkedHashMap<PEALockReplayPhysicalGroup, List<PEALockReplay>> physical =
                    new LinkedHashMap<>();
            LinkedHashMap<Integer, PEALockReplayPhysicalGroup> batchIdentities =
                    new LinkedHashMap<>();
            HashSet<PEALockReplay> associations = new HashSet<>();
            for (PEALockReplay replay : replays) {
                if (!associations.add(replay)) {
                    throw new IllegalArgumentException(
                            "Duplicate LockReplay association in round "
                            + iteration + ": " + replay);
                }
                PEALockReplayPhysicalGroup previousBatch =
                        batchIdentities.putIfAbsent(replay.batch(), replay.physicalGroup());
                if (previousBatch != null && !previousBatch.equals(replay.physicalGroup())) {
                    throw new IllegalArgumentException(
                            "LockReplay batch " + replay.batch()
                            + " has inconsistent physical identity in round " + iteration
                            + ": " + previousBatch + " vs " + replay.physicalGroup());
                }
                logical.computeIfAbsent(replay.group(), ignored -> new ArrayList<>()).add(replay);
                physical.computeIfAbsent(replay.physicalGroup(),
                        ignored -> new ArrayList<>()).add(replay);
            }

            LinkedHashMap<PEALockReplayPhysicalGroup, List<PEALockReplay>>
                    immutablePhysical = new LinkedHashMap<>();
            for (Map.Entry<PEALockReplayPhysicalGroup, List<PEALockReplay>> entry
                    : physical.entrySet()) {
                int currentOrdinal = -1;
                int currentReceiver = -1;
                int currentDepth = -1;
                for (PEALockReplay replay : entry.getValue()) {
                    if (replay.ordinal() == currentOrdinal) {
                        if (replay.receiverVO() == currentReceiver
                                && replay.depth() == currentDepth) {
                            continue;
                        }
                        throw new IllegalArgumentException(
                                "Conflicting LockReplay aliases for physical ordinal "
                                + replay.ordinal() + " in " + entry.getKey()
                                + " in round " + iteration);
                    }
                    if (replay.ordinal() != currentOrdinal + 1) {
                        throw new IllegalArgumentException(
                                "Non-contiguous physical LockReplay ordinal for "
                                + entry.getKey() + " in round " + iteration + ": expected "
                                + (currentOrdinal + 1) + ", got " + replay.ordinal());
                    }
                    if (replay.depth() <= currentDepth) {
                        throw new IllegalArgumentException(
                                "Non-increasing physical LockReplay depth for "
                                + entry.getKey() + " in round " + iteration);
                    }
                    currentOrdinal = replay.ordinal();
                    currentReceiver = replay.receiverVO();
                    currentDepth = replay.depth();
                }
                immutablePhysical.put(entry.getKey(), List.copyOf(entry.getValue()));
            }

            LinkedHashMap<PEALockReplayGroup, List<PEALockReplay>> immutableLogical =
                    new LinkedHashMap<>();
            for (Map.Entry<PEALockReplayGroup, List<PEALockReplay>> entry
                    : logical.entrySet()) {
                immutableLogical.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return new LockReplayGrouping(
                    Collections.unmodifiableMap(immutableLogical),
                    Collections.unmodifiableMap(immutablePhysical));
        }
    }

    /** Exact reports for one or more requested LLVM functions. */
    public static final class PEAReport {
        private final MethodId methodId;
        private final List<PEARound> rounds;
        private final PEAStopReason stopReason;
        private final Map<MethodId, PEAReport> reports;

        private PEAReport(MethodId methodId, List<PEARound> rounds,
                          PEAStopReason stopReason) {
            this.methodId = methodId;
            this.rounds = List.copyOf(rounds);
            this.stopReason = Objects.requireNonNull(stopReason);
            this.reports = Map.of();
        }

        private PEAReport(Map<MethodId, PEAReport> reports) {
            this.methodId = null;
            this.rounds = List.of();
            this.stopReason = null;
            this.reports = Collections.unmodifiableMap(new LinkedHashMap<>(reports));
        }

        public static PEAReport parse(String stderr, MethodId... methods) {
            if (methods.length == 0) {
                throw new IllegalArgumentException("At least one exact method is required");
            }
            List<String> lines = splitLines(stderr);
            LinkedHashMap<MethodId, PEAReport> parsed = new LinkedHashMap<>();
            for (MethodId method : methods) {
                if (parsed.containsKey(method)) {
                    throw new IllegalArgumentException("Duplicate report target " + method);
                }
                parsed.put(method, parseFunction(lines, method));
            }
            return new PEAReport(parsed);
        }

        public PEAReport report(MethodId method) {
            PEAReport report = reports.get(method);
            if (report == null) {
                throw new IllegalArgumentException("No report requested for " + method);
            }
            return report;
        }

        public MethodId methodId() {
            requireFunctionReport();
            return methodId;
        }

        public int roundCount() {
            requireFunctionReport();
            return rounds.size();
        }

        public List<PEARound> rounds() {
            requireFunctionReport();
            return rounds;
        }

        public PEAStopReason stopReason() {
            requireFunctionReport();
            return stopReason;
        }

        public int transformChangedRoundCount() {
            requireFunctionReport();
            return (int) rounds.stream().filter(round -> !round.transformIdle()).count();
        }

        public int transformIdleRoundCount() {
            requireFunctionReport();
            return (int) rounds.stream().filter(PEARound::transformIdle).count();
        }

        /** Verifies that the final configured iteration had an idle PEA transform. */
        public void assertFinalTransformIdle() {
            requireFunctionReport();
            PEARound last = rounds.get(rounds.size() - 1);
            if (!last.transformIdle()) {
                throw new IllegalStateException("PEA final transform is active for " + methodId
                        + ": final round " + last.iteration()
                        + " is not transform-idle");
            }
        }

        public void assertStoppedAtFixpoint() {
            assertStopReason(PEAStopReason.FIXPOINT);
        }

        public void assertStoppedAtIterationCap() {
            assertStopReason(PEAStopReason.ITERATION_CAP);
        }

        public PEARound round(int iteration) {
            requireFunctionReport();
            if (iteration < 0 || iteration >= rounds.size()) {
                throw new IllegalArgumentException("No PEA round " + iteration + " for " + methodId);
            }
            return rounds.get(iteration);
        }

        public IRBody round0Before() {
            return round(0).before();
        }

        public IRBody finalAfter() {
            requireFunctionReport();
            return rounds.get(rounds.size() - 1).after();
        }

        public List<PEAEffect> effects(String kind) {
            requireFunctionReport();
            return rounds.stream().flatMap(r -> r.effects().stream())
                    .filter(e -> e.kind().equals(kind)).collect(Collectors.toUnmodifiableList());
        }

        // Stats are per-outer-iteration: an allocation eliminated in round 0 reports
        // NeverEscapes=1 only in round 0's stats, and later rounds report 0. Escape
        // classification oracles must therefore ask "did some round report this
        // classification", not "does every round". Rounds without stats (e.g. an
        // allocation-free idle round) are skipped.
        public int maxNeverEscapes() {
            requireFunctionReport();
            int max = 0;
            for (PEARound round : rounds) {
                if (round.hasStats()) {
                    max = Math.max(max, round.neverEscapes());
                }
            }
            return max;
        }

        public int maxPartiallyEscapes() {
            requireFunctionReport();
            int max = 0;
            for (PEARound round : rounds) {
                if (round.hasStats()) {
                    max = Math.max(max, round.partiallyEscapes());
                }
            }
            return max;
        }

        public int maxAlwaysEscapes() {
            requireFunctionReport();
            int max = 0;
            for (PEARound round : rounds) {
                if (round.hasStats()) {
                    max = Math.max(max, round.alwaysEscapes());
                }
            }
            return max;
        }

        private void requireFunctionReport() {
            if (methodId == null) {
                throw new IllegalStateException("Select an exact method report first");
            }
        }

        private void assertStopReason(PEAStopReason expected) {
            requireFunctionReport();
            if (stopReason != expected) {
                throw new IllegalStateException("PEA stopped for " + methodId
                        + " because of " + stopReason + ", expected " + expected);
            }
        }

        private static PEAReport parseFunction(List<String> lines, MethodId method) {
            ArrayList<PEARound> rounds = new ArrayList<>();
            RoundBuilder current = null;
            Capture capture = Capture.NONE;
            String function = method.llvmFunctionName();
            PEAStopReason stopReason = null;

            for (String line : lines) {
                if (line.startsWith(";; PEA-SUMMARY")) {
                    Matcher summary = SUMMARY.matcher(line);
                    if (!summary.matches()) {
                        throw malformed(method, "malformed PEA summary: " + line);
                    }
                    if (!matchesRuntimeFunctionName(summary.group(1), function)) {
                        if (current != null && !current.afterSeen) {
                            throw malformed(method,
                                    "interleaved summary before after marker");
                        }
                        if (current != null) {
                            rounds.add(current.finish(method));
                            current = null;
                        }
                        capture = Capture.NONE;
                        continue;
                    }
                    capture = Capture.NONE;
                    if (stopReason != null) {
                        throw malformed(method, "duplicate PEA summary");
                    }
                    if (current != null) {
                        if (!current.afterSeen) {
                            throw malformed(method, "PEA summary before after marker for round "
                                    + current.iteration);
                        }
                        rounds.add(current.finish(method));
                        current = null;
                    }
                    int summaryRounds;
                    try {
                        summaryRounds = Integer.parseInt(summary.group(2));
                    } catch (NumberFormatException e) {
                        throw malformed(method, "PEA summary round count overflows int: "
                                + summary.group(2));
                    }
                    if (summaryRounds != rounds.size()) {
                        throw malformed(method, "PEA summary round count mismatch: expected "
                                + rounds.size() + ", got " + summaryRounds);
                    }
                    stopReason = PEAStopReason.parse(method, summary.group(3));
                    if (stopReason == PEAStopReason.FIXPOINT
                            && (rounds.isEmpty()
                            || !rounds.get(rounds.size() - 1).transformIdle())) {
                        throw malformed(method,
                                "fixpoint summary requires an idle final transform");
                    }
                    if (stopReason == PEAStopReason.FIXPOINT) {
                        PEARound last = rounds.get(rounds.size() - 1);
                        if (!last.before().lines().equals(last.after().lines())) {
                            throw malformed(method,
                                    "fixpoint summary requires an unchanged complete final round");
                        }
                    }
                    continue;
                }

                Matcher marker = MARKER.matcher(line);
                if (marker.matches()) {
                    String markerFunction = marker.group(3);
                    boolean matches = matchesRuntimeFunctionName(markerFunction, function);
                    if (!matches) {
                        if (current != null && !current.afterSeen) {
                            throw malformed(method, "interleaved marker before after marker");
                        }
                        if (current != null) {
                            rounds.add(current.finish(method));
                            current = null;
                        }
                        capture = Capture.NONE;
                        continue;
                    }

                    if (stopReason != null) {
                        throw malformed(method, "PEA marker after summary");
                    }

                    int iteration = Integer.parseInt(marker.group(2));
                    if (marker.group(1).equals("before")) {
                        if (current != null) {
                            if (!current.afterSeen) {
                                throw malformed(method, "duplicate or missing after marker for round "
                                        + current.iteration);
                            }
                            rounds.add(current.finish(method));
                        }
                        if (iteration != rounds.size()) {
                            throw malformed(method, "gapped or duplicate before marker: expected round "
                                    + rounds.size() + ", got " + iteration);
                        }
                        current = new RoundBuilder(iteration);
                        capture = Capture.BEFORE;
                    } else {
                        if (marker.group(4) == null) {
                            throw malformed(method,
                                    "after marker lacks transform-idle flag for round " + iteration);
                        }
                        if (current == null || current.iteration != iteration) {
                            throw malformed(method, "after marker without matching before for round "
                                    + iteration);
                        }
                        if (current.afterSeen) {
                            throw malformed(method, "duplicate after marker for round " + iteration);
                        }
                        current.afterSeen = true;
                        current.transformIdle = marker.group(4).equals("true")
                                || marker.group(4).equals("1");
                        capture = Capture.AFTER;
                    }
                    continue;
                }

                Matcher stats = STATS.matcher(line);
                if (stats.matches()) {
                    capture = Capture.NONE;
                    if (!matchesRuntimeFunctionName(stats.group(1), function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "stats outside an open round");
                    }
                    if (current.statsSeen) {
                        throw malformed(method, "duplicate stats for round " + current.iteration);
                    }
                    current.statsSeen = true;
                    current.never = Integer.parseInt(stats.group(2));
                    current.partial = Integer.parseInt(stats.group(3));
                    current.always = Integer.parseInt(stats.group(4));
                    continue;
                }

                Matcher effect = EFFECT.matcher(line);
                if (line.startsWith("PEA: LockReplay ")) {
                    capture = Capture.NONE;
                    Matcher lockReplay = LOCK_REPLAY.matcher(line);
                    if (!lockReplay.matches()) {
                        throw malformed(method, "malformed LockReplay line: " + line);
                    }
                    String effectFunction = decodeLLVMOperand(lockReplay.group(1));
                    if (!matchesRuntimeFunctionName(effectFunction, function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "LockReplay outside an open round");
                    }
                    current.lockReplays.add(new PEALockReplay(
                            lockReplayInt(method, "logical_escape", lockReplay.group(2)),
                            lockReplayInt(method, "batch", lockReplay.group(3)),
                            lockReplayInt(method, "source", lockReplay.group(4)),
                            lockReplayInt(method, "receiver_vo", lockReplay.group(5)),
                            lockReplayInt(method, "depth", lockReplay.group(6)),
                            lockReplayInt(method, "ordinal", lockReplay.group(7))));
                    continue;
                }
                if (effect.matches()) {
                    capture = Capture.NONE;
                    String effectFunction = decodeLLVMOperand(effect.group(2));
                    if (!matchesRuntimeFunctionName(effectFunction, function)) {
                        continue;
                    }
                    if (current == null || current.afterSeen) {
                        throw malformed(method, "effect outside an open round");
                    }
                    ParsedEffectDetail parsedDetail = parseEffectDetail(
                            method, current.iteration,
                            effect.group(3) == null ? "" : effect.group(3));
                    current.effects.add(new PEAEffect(effect.group(1), effectFunction,
                            current.iteration, parsedDetail.sequence(),
                            parsedDetail.semanticDetail()));
                    continue;
                }
                String malformedKind = knownTypedEffectKind(line);
                if (malformedKind != null) {
                    throw malformed(method, "malformed " + malformedKind
                            + " effect line: " + line);
                }

                if (line.startsWith(";; PEA-DUMP ") || line.startsWith(";; PEA stats @")
                        || line.startsWith("PEA: ")) {
                    capture = Capture.NONE;
                    continue;
                }
                if (current != null) {
                    if (capture == Capture.BEFORE) {
                        current.beforeLines.add(line);
                    } else if (capture == Capture.AFTER) {
                        current.afterLines.add(line);
                    }
                }
            }

            if (current != null) {
                if (!current.afterSeen) {
                    throw malformed(method, "missing after marker for round " + current.iteration);
                }
                rounds.add(current.finish(method));
            }
            if (rounds.isEmpty()) {
                throw malformed(method, "no exact PEA rounds found");
            }
            if (stopReason == null) {
                throw malformed(method, "missing PEA summary");
            }
            return new PEAReport(method, rounds, stopReason);
        }
    }

    private enum Capture { NONE, BEFORE, AFTER }

    private static final class RoundBuilder {
        private final int iteration;
        private final List<String> beforeLines = new ArrayList<>();
        private final List<String> afterLines = new ArrayList<>();
        private final List<PEAEffect> effects = new ArrayList<>();
        private final List<PEALockReplay> lockReplays = new ArrayList<>();
        private boolean statsSeen;
        private boolean afterSeen;
        private boolean transformIdle;
        private int never;
        private int partial;
        private int always;

        private RoundBuilder(int iteration) {
            this.iteration = iteration;
        }

        private PEARound finish(MethodId method) {
            if (!afterSeen) {
                throw malformed(method, "missing after marker for round " + iteration);
            }
            // An allocation-free idle transform need not request PEA analysis, so
            // no stats line is emitted for that round.
            if (!statsSeen && (!transformIdle || !effects.isEmpty()
                    || !lockReplays.isEmpty())) {
                throw malformed(method, "missing stats for active round " + iteration);
            }
            validateEffects(method);
            return new PEARound(iteration,
                    IRBody.fromModuleLines(beforeLines, method),
                    IRBody.fromModuleLines(afterLines, method),
                    never, partial, always, effects, lockReplays, statsSeen, transformIdle);
        }

        private void validateEffects(MethodId method) {
            HashSet<Long> sequences = new HashSet<>();
            long previousSequence = -1;
            for (PEAEffect effect : effects) {
                if (!sequences.add(effect.sequence())) {
                    throw malformed(method, "duplicate effect sequence "
                            + effect.sequence() + " in round " + iteration);
                }
                if (effect.sequence() <= previousSequence) {
                    throw malformed(method, "effect sequences must be strictly increasing"
                            + " in emitted trace order in round " + iteration
                            + ": previous=" + previousSequence
                            + ", current=" + effect.sequence());
                }
                previousSequence = effect.sequence();
            }
        }
    }

    private record ParsedEffectDetail(long sequence, String semanticDetail) {}

    private static ParsedEffectDetail parseEffectDetail(
            MethodId method, int iteration, String detail) {
        int target = findEffectField(detail, "target=");
        if (target >= 0) {
            if (EFFECT_SEQUENCE_FIELD.matcher(detail.substring(0, target)).find()) {
                throw malformed(method, "duplicate seq= field before target="
                        + " in round " + iteration + ": " + detail);
            }
            if (!detail.startsWith(TARGET_EFFECT_SEQUENCE, target)) {
                throw malformed(method, "target effect seq= must follow target="
                        + " in round " + iteration + ": " + detail);
            }
            int valueStart = target + TARGET_EFFECT_SEQUENCE.length();
            int valueEnd = detail.indexOf(' ', valueStart);
            if (valueEnd < 0 || valueEnd + 1 >= detail.length()) {
                throw malformed(method, "target effect seq= must precede an instruction"
                        + " in round " + iteration + ": " + detail);
            }
            String semanticDetail = (detail.substring(0, target)
                    + "target=" + detail.substring(valueEnd + 1)).trim();
            return parseEffectSequenceValue(
                    method, iteration, detail.substring(valueStart, valueEnd),
                    semanticDetail);
        }

        Matcher sequence = FINAL_EFFECT_SEQUENCE.matcher(detail);
        if (!sequence.find()) {
            if (EFFECT_SEQUENCE_FIELD.matcher(detail).find()) {
                throw malformed(method, "targetless effect seq= must be final"
                        + " in round " + iteration + ": " + detail);
            }
            throw malformed(method, "effect is missing seq= in round "
                    + iteration + ": " + detail);
        }
        String semanticDetail = detail.substring(0, sequence.start()).trim();
        if (EFFECT_SEQUENCE_FIELD.matcher(semanticDetail).find()) {
            throw malformed(method, "duplicate seq= field in targetless effect"
                    + " in round " + iteration + ": " + detail);
        }
        return parseEffectSequenceValue(
                method, iteration, sequence.group(1), semanticDetail);
    }

    private static ParsedEffectDetail parseEffectSequenceValue(
            MethodId method, int iteration, String value, String semanticDetail) {
        long parsed;
        if (value.startsWith("-")) {
            throw malformed(method, "effect seq= must be non-negative in round "
                    + iteration + ": " + value);
        }
        if (!value.matches("[0-9]+")) {
            throw malformed(method, "malformed seq= value in round "
                    + iteration + ": " + value);
        }
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw malformed(method, "effect seq= value overflows uint32 in round "
                    + iteration + ": " + value);
        }
        if (parsed > 0xFFFF_FFFFL) {
            throw malformed(method, "effect seq= value overflows uint32 in round "
                    + iteration + ": " + value);
        }
        return new ParsedEffectDetail(parsed, semanticDetail);
    }

    private static int findEffectField(String detail, String field) {
        int from = 0;
        while (true) {
            int fieldStart = detail.indexOf(field, from);
            if (fieldStart < 0) {
                return -1;
            }
            if (fieldStart == 0 || detail.charAt(fieldStart - 1) == ' ') {
                return fieldStart;
            }
            from = fieldStart + field.length();
        }
    }

    private static String knownTypedEffectKind(String line) {
        for (String kind : KNOWN_TYPED_EFFECT_KINDS) {
            String prefix = "PEA: " + kind;
            if (line.startsWith(prefix)
                    && (line.length() == prefix.length()
                    || Character.isWhitespace(line.charAt(prefix.length())))) {
                return kind;
            }
        }
        return null;
    }

    private static int lockReplayInt(MethodId method, String field, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw malformed(method, "LockReplay " + field + " value overflows int: " + value);
        }
    }

    /** HotSpot basic types carried by Jeandle's deoptimization encoding. */
    public enum DeoptBasicType {
        BOOLEAN(4), CHAR(5), FLOAT(6), DOUBLE(7), BYTE(8), SHORT(9),
        INT(10), LONG(11), OBJECT(12), ARRAY(13), VOID(14), ADDRESS(15),
        NARROW_OOP(16), METADATA(17), NARROW_KLASS(18), CONFLICT(19),
        ILLEGAL(99);

        private final int tag;

        DeoptBasicType(int tag) {
            this.tag = tag;
        }

        public int tag() {
            return tag;
        }

        private static DeoptBasicType fromTag(int tag) {
            for (DeoptBasicType type : values()) {
                if (type.tag == tag) {
                    return type;
                }
            }
            throw new IllegalStateException("Unknown deopt basic type " + tag);
        }
    }

    /** Semantic kind of one decoded deoptimization value. */
    public enum DeoptValueKind {
        SCALAR, NULL, MATERIALIZED_OOP, VO_REF
    }

    /** One immutable decoded scalar, oop, null, or virtual-object reference. */
    public record DeoptValue(
            DeoptValueKind kind, DeoptBasicType basicType,
            String operand, int virtualObjectId) {
        public DeoptValue {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(basicType);
            Objects.requireNonNull(operand);
            if (kind == DeoptValueKind.VO_REF && virtualObjectId < 0) {
                throw new IllegalArgumentException("VORef id must be non-negative");
            }
            if (kind != DeoptValueKind.VO_REF && virtualObjectId != -1) {
                throw new IllegalArgumentException("Only VORefs carry a virtual-object id");
            }
        }
    }

    /** Instance versus array reconstruction descriptor. */
    public enum DescriptorKind {
        INSTANCE, ARRAY
    }

    /** One descriptor field or array element, keyed by its byte offset. */
    public record VirtualObjectEntry(
            int offset, DeoptBasicType basicType, DeoptValue value) {
        public VirtualObjectEntry {
            if (offset < 0) {
                throw new IllegalArgumentException("Descriptor offset must be non-negative");
            }
            Objects.requireNonNull(basicType);
            Objects.requireNonNull(value);
        }
    }

    /** One immutable virtual-object descriptor from the root object pool. */
    public static final class VirtualObjectDescriptor {
        private final int id;
        private final DescriptorKind kind;
        private final String klassOperand;
        private final Map<Integer, VirtualObjectEntry> entries;

        private VirtualObjectDescriptor(
                int id, DescriptorKind kind, String klassOperand,
                Map<Integer, VirtualObjectEntry> entries) {
            this.id = id;
            this.kind = Objects.requireNonNull(kind);
            this.klassOperand = Objects.requireNonNull(klassOperand);
            this.entries = immutableLinkedMap(entries);
        }

        public int id() {
            return id;
        }

        public DescriptorKind kind() {
            return kind;
        }

        public String klassOperand() {
            return klassOperand;
        }

        public Map<Integer, VirtualObjectEntry> entries() {
            return entries;
        }

        public Map<Integer, VirtualObjectEntry> fields() {
            if (kind != DescriptorKind.INSTANCE) {
                throw new IllegalStateException("Array descriptor " + id + " has elements");
            }
            return entries;
        }

        public Map<Integer, VirtualObjectEntry> elements() {
            if (kind != DescriptorKind.ARRAY) {
                throw new IllegalStateException("Instance descriptor " + id + " has fields");
            }
            return entries;
        }
    }

    /** One decoded monitor at its lexical depth in a deopt scope. */
    public record DeoptMonitor(
            int depth, boolean eliminated, DeoptValue owner, String lockOperand) {
        public DeoptMonitor {
            if (depth < 0) {
                throw new IllegalArgumentException("Monitor depth must be non-negative");
            }
            Objects.requireNonNull(owner);
            Objects.requireNonNull(lockOperand);
        }
    }

    /** One immutable root or inlined Java scope in a deopt bundle. */
    public record DeoptScope(
            boolean root, String methodOperand, boolean shouldReexecute,
            int bci, int duplicateBCI, Map<Integer, DeoptValue> locals,
            Map<Integer, DeoptValue> stack, List<DeoptMonitor> monitors,
            String origPcOperand) {
        public DeoptScope {
            Objects.requireNonNull(methodOperand);
            locals = immutableLinkedMap(locals);
            stack = immutableLinkedMap(stack);
            monitors = List.copyOf(monitors);
            Objects.requireNonNull(origPcOperand);
        }
    }

    /** One exact typed deopt operand bundle and its root object pool. */
    public static final class DeoptBundle {
        private final List<DeoptScope> scopes;
        private final Map<Integer, VirtualObjectDescriptor> virtualObjects;

        private DeoptBundle(
                List<DeoptScope> scopes,
                Map<Integer, VirtualObjectDescriptor> virtualObjects) {
            this.scopes = List.copyOf(scopes);
            this.virtualObjects = immutableLinkedMap(virtualObjects);
            if (this.scopes.isEmpty() || !this.scopes.get(0).root()) {
                throw new IllegalArgumentException("A deopt bundle requires one root scope");
            }
        }

        public List<DeoptScope> scopes() {
            return scopes;
        }

        public DeoptScope rootScope() {
            return scopes.get(0);
        }

        public List<DeoptScope> inlineScopes() {
            return scopes.subList(1, scopes.size());
        }

        public Map<Integer, VirtualObjectDescriptor> virtualObjects() {
            return virtualObjects;
        }

        public VirtualObjectDescriptor virtualObject(int id) {
            VirtualObjectDescriptor descriptor = virtualObjects.get(id);
            if (descriptor == null) {
                throw new IllegalStateException("No virtual-object descriptor " + id);
            }
            return descriptor;
        }

        public void assertVirtualObjectIds(int... expectedIds) {
            HashSet<Integer> expected = new HashSet<>();
            for (int id : expectedIds) {
                if (id < 0 || !expected.add(id)) {
                    throw new IllegalArgumentException(
                            "Expected virtual-object ids must be unique and non-negative");
                }
            }
            Asserts.assertEquals(virtualObjects.keySet(), expected,
                    "Exact virtual-object id set");
        }

        public void assertVORef(int ownerId, int offset, int targetId) {
            VirtualObjectEntry entry = virtualObject(ownerId).entries().get(offset);
            Asserts.assertNotNull(entry,
                    "Missing descriptor entry owner=" + ownerId + " offset=" + offset);
            Asserts.assertEquals(entry.value().kind(), DeoptValueKind.VO_REF,
                    "Descriptor entry must be a VORef");
            Asserts.assertEquals(entry.value().virtualObjectId(), targetId,
                    "Descriptor VORef target");
        }
    }

    private record DecodedEncoding(
            int index, int valueType, DeoptBasicType basicType) {}

    private record ScopeBuilderResult(DeoptScope scope, int nextOperand) {}

    private static <K, V> Map<K, V> immutableLinkedMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** The source allocation operation represented by a Jeandle PEA allocation. */
    public enum AllocationKind {
        INSTANCE,
        ARRAY
    }

    /** Exact source identity for an allocation retained after PEA. */
    public record AllocationKey(AllocationKind kind, int bci) {
        public AllocationKey {
            Objects.requireNonNull(kind);
        }
    }

    /** One source-level Jeandle allocation instruction. */
    public record AllocationSite(AllocationKey key, String result, String instruction) {
        public AllocationSite {
            Objects.requireNonNull(key);
            Objects.requireNonNull(result);
            Objects.requireNonNull(instruction);
        }
    }

    /** One exact LLVM function definition with line- and occurrence-aware assertions. */
    public static final class IRBody {
        private static final String LLVM_LABEL_NAME =
                "(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
        private static final Pattern LOWERED_ALLOCATION_TARGET = Pattern.compile(
                "@new_(?:instance|array)\\s*$");
        private static final Pattern DEOPT_BCI = Pattern.compile(
                "\\\"deopt\\\"\\(i64 0, i32 (-?\\d+), i32 \\1(?:,|\\))");
        private static final Pattern BLOCK_LABEL = Pattern.compile(
                "^(" + LLVM_LABEL_NAME + "):(?: ;.*)?$");
        private static final Pattern JAVA_KLASS_ATTRIBUTE = Pattern.compile(
                "\"java-klass\"=\"([0-9]+)\"");
        private static final String DEOPT_BUNDLE_PREFIX = "[ \"deopt\"(";
        private static final String DEOPT_BUNDLE_SUFFIX = ") ]";
        private static final Pattern INLINE_SCOPE_METHOD_HEADER = Pattern.compile(
                "i64 393233, i64 ([0-9]+), i64 [01], i32 (-?[0-9]+), i32 \\2");
        private final MethodId method;
        private final List<String> lines;
        private final String text;

        private IRBody(MethodId method, List<String> lines) {
            this.method = method;
            this.lines = lines.stream()
                    .map(PEATestUtils::normalizeRuntimeFunctionSymbols)
                    .toList();
            this.text = String.join("\n", this.lines);
        }

        private static IRBody fromModuleLines(List<String> rawLines, MethodId method) {
            List<String> folded = rawLines.stream().map(PEATestUtils::fold)
                    .filter(s -> !s.isEmpty()).collect(Collectors.toList());
            ArrayList<List<String>> definitions = new ArrayList<>();
            for (int i = 0; i < folded.size(); i++) {
                String line = folded.get(i);
                String defined = definedFunctionName(line);
                if (!matchesRuntimeFunctionName(defined, method.llvmFunctionName())) {
                    continue;
                }
                ArrayList<String> body = new ArrayList<>();
                int depth = 0;
                boolean opened = false;
                for (int j = i; j < folded.size(); j++) {
                    String bodyLine = folded.get(j);
                    body.add(bodyLine);
                    int delta = braceDelta(bodyLine);
                    if (delta > 0) {
                        opened = true;
                    }
                    depth += delta;
                    if (opened && depth == 0) {
                        break;
                    }
                }
                if (!opened || depth != 0) {
                    throw new IllegalStateException("Unbalanced function definition for " + method);
                }
                definitions.add(body);
            }
            if (definitions.isEmpty()) {
                throw new IllegalStateException("Exact function definition not found: " + method);
            }
            if (definitions.size() != 1) {
                throw new IllegalStateException("Ambiguous exact function definitions for " + method
                        + ": " + definitions.size());
            }
            return new IRBody(method, definitions.get(0));
        }

        public MethodId methodId() {
            return method;
        }

        public List<String> lines() {
            return lines;
        }

        /**
         * Compares exact function IR emitted by independent JVM processes.
         * Runtime klass addresses are identified only by {@code java-klass}
         * attributes. Runtime method addresses are identified only as the
         * method operand following an exact inline-scope marker in a deopt
         * bundle. Both are replaced consistently; all other constants remain
         * exact.
         */
        public void assertCrossProcessExactEquals(IRBody other, String context) {
            Objects.requireNonNull(other);
            Objects.requireNonNull(context);
            Asserts.assertEquals(crossProcessExactLines(),
                    other.crossProcessExactLines(), context);
        }

        private List<String> crossProcessExactLines() {
            return crossProcessExactLines(lines);
        }

        private static List<String> crossProcessExactLines(List<String> sourceLines) {
            sourceLines = sourceLines.stream()
                    .map(PEATestUtils::normalizeRuntimeFunctionSymbols)
                    .toList();
            LinkedHashMap<String, String> klassReplacements = new LinkedHashMap<>();
            LinkedHashMap<String, String> methodReplacements = new LinkedHashMap<>();
            for (String line : sourceLines) {
                collectRuntimeAddresses(line, klassReplacements, methodReplacements);
            }
            if (klassReplacements.isEmpty() && methodReplacements.isEmpty()) {
                return sourceLines;
            }

            ArrayList<String> normalized = new ArrayList<>(sourceLines.size());
            for (String line : sourceLines) {
                normalized.add(normalizeRuntimeAddresses(
                        line, klassReplacements, methodReplacements));
            }
            return List.copyOf(normalized);
        }

        private static void collectRuntimeAddresses(
                String line, LinkedHashMap<String, String> klassReplacements,
                LinkedHashMap<String, String> methodReplacements) {
            for (int i = 0; i < line.length();) {
                char current = line.charAt(i);
                if (current == ';') {
                    return;
                }
                Matcher attribute = JAVA_KLASS_ATTRIBUTE.matcher(line);
                attribute.region(i, line.length());
                if (attribute.lookingAt()) {
                    klassReplacements.computeIfAbsent(attribute.group(1),
                            ignored -> "<java-klass-" + klassReplacements.size() + ">");
                    i = attribute.end();
                } else if (current == 'i') {
                    Matcher scope = inlineScopeMethodHeaderAt(line, i);
                    if (scope != null) {
                        methodReplacements.computeIfAbsent(scope.group(1),
                                ignored -> "<java-method-"
                                        + methodReplacements.size() + ">");
                        i = scope.end();
                    } else {
                        i++;
                    }
                } else if (current == '"') {
                    i = quotedTokenEnd(line, i);
                } else {
                    i++;
                }
            }
        }

        private static String normalizeRuntimeAddresses(
                String line, Map<String, String> klassReplacements,
                Map<String, String> methodReplacements) {
            StringBuilder normalized = new StringBuilder(line.length());
            for (int i = 0; i < line.length();) {
                char current = line.charAt(i);
                if (current == ';') {
                    normalized.append(line, i, line.length());
                    break;
                }

                Matcher attribute = JAVA_KLASS_ATTRIBUTE.matcher(line);
                attribute.region(i, line.length());
                if (attribute.lookingAt()) {
                    normalized.append("\"java-klass\"=\"")
                            .append(klassReplacements.get(attribute.group(1))).append('"');
                    i = attribute.end();
                    continue;
                }
                if (current == 'i') {
                    Matcher scope = inlineScopeMethodHeaderAt(line, i);
                    if (scope != null) {
                        normalized.append(line, i, scope.start(1))
                                .append(methodReplacements.get(scope.group(1)))
                                .append(line, scope.end(1), scope.end());
                        i = scope.end();
                        continue;
                    }
                }
                if (current == '"') {
                    int end = quotedTokenEnd(line, i);
                    normalized.append(line, i, end);
                    i = end;
                    continue;
                }
                if (!Character.isDigit(current)) {
                    normalized.append(current);
                    i++;
                    continue;
                }

                int end = i + 1;
                while (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++;
                }
                String value = line.substring(i, end);
                String replacement = klassReplacements.get(value);
                if (replacement != null && isUnsignedDecimalIntegerToken(line, i, end)) {
                    normalized.append(replacement);
                } else {
                    normalized.append(value);
                }
                i = end;
            }
            return normalized.toString();
        }

        private static Matcher inlineScopeMethodHeaderAt(String line, int index) {
            Matcher scope = INLINE_SCOPE_METHOD_HEADER.matcher(line);
            scope.region(index, line.length());
            if (!scope.lookingAt()) {
                return null;
            }
            int bundleStart = line.lastIndexOf(DEOPT_BUNDLE_PREFIX, index);
            if (bundleStart < 0) {
                return null;
            }
            int bundleEnd = line.indexOf(DEOPT_BUNDLE_SUFFIX,
                    bundleStart + DEOPT_BUNDLE_PREFIX.length());
            return bundleEnd >= scope.end() ? scope : null;
        }

        private static int quotedTokenEnd(String line, int quote) {
            for (int i = quote + 1; i < line.length(); i++) {
                char current = line.charAt(i);
                if (current == '\\' && i + 1 < line.length()) {
                    i++;
                } else if (current == '"') {
                    return i + 1;
                }
            }
            return line.length();
        }

        private static boolean isUnsignedDecimalIntegerToken(
                String line, int start, int end) {
            if (start > 0 && isIntegerTokenNeighbor(line.charAt(start - 1))) {
                return false;
            }
            if (end < line.length()
                    && (isIntegerTokenNeighbor(line.charAt(end))
                    || line.charAt(end) == ':')) {
                return false;
            }
            return true;
        }

        private static boolean isIntegerTokenNeighbor(char value) {
            return Character.isLetterOrDigit(value)
                    || value == '-' || value == '+' || value == '$'
                    || value == '.' || value == '_' || value == '%'
                    || value == '@' || value == '!' || value == '#'
                    || value == '\\';
        }

        public int peaAllocCount() {
            return (int) callInstructions().stream()
                    .map(CompleteCallInstruction::text)
                    .map(PEATestUtils::calledFunctionName)
                    .filter(callee -> allocationKind(callee) != null)
                    .count();
        }

        public int loweredAllocCount() {
            return (int) callInstructions().stream()
                    .map(CompleteCallInstruction::text)
                    .filter(this::isLoweredAllocation)
                    .count();
        }

        private boolean isLoweredAllocation(String line) {
            String callee = calledFunctionName(line);
            if ("new_instance".equals(callee) || "new_array".equals(callee)) {
                return true;
            }
            if (callee == null
                    || !callee.startsWith("llvm.experimental.gc.statepoint")) {
                return false;
            }

            int calleeAt = line.indexOf("@" + callee);
            if (calleeAt < 0) {
                throw new IllegalStateException(
                        method + ": malformed statepoint callee: " + line);
            }
            int open = line.indexOf('(', calleeAt + callee.length() + 1);
            if (open < 0) {
                throw new IllegalStateException(
                        method + ": malformed statepoint call: " + line);
            }
            int close = matchingDelimiter(line, open, '(', ')');
            if (close < 0) {
                throw new IllegalStateException(
                        method + ": malformed statepoint call: " + line);
            }
            List<String> operands = splitTopLevelOperands(
                    line.substring(open + 1, close), method);
            if (operands.size() < 3) {
                throw new IllegalStateException(
                        method + ": statepoint lacks a target operand: " + line);
            }
            return LOWERED_ALLOCATION_TARGET.matcher(operands.get(2)).find();
        }

        private static AllocationKind allocationKind(String callee) {
            if ("jeandle.new_instance".equals(callee)) {
                return AllocationKind.INSTANCE;
            }
            if ("jeandle.new_array".equals(callee)) {
                return AllocationKind.ARRAY;
            }
            return null;
        }

        public List<Integer> allocationBCIs() {
            return allocations().stream().map(site -> site.key().bci()).toList();
        }

        /** Maps each allocation result SSA value to its source BCI. */
        public Map<String, Integer> allocationBCIsByResult() {
            LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
            for (AllocationSite site : allocations()) {
                Integer previous = result.putIfAbsent(site.result(), site.key().bci());
                if (previous != null) {
                    throw new AssertionError(method + ": duplicate allocation SSA result: "
                            + site.result());
                }
            }
            return Collections.unmodifiableMap(result);
        }

        /** Return all Jeandle allocation instructions with typed source identities. */
        public List<AllocationSite> allocations() {
            ArrayList<AllocationSite> result = new ArrayList<>();
            for (CompleteCallInstruction call : callInstructions()) {
                String instruction = call.text();
                String callee = calledFunctionName(instruction);
                AllocationKind kind = allocationKind(callee);
                if (kind == null) {
                    continue;
                }
                Matcher assignment = ASSIGNED_INSTRUCTION.matcher(instruction);
                if (!assignment.find()) {
                    throw new AssertionError(method
                            + ": allocation lacks an SSA result: " + instruction);
                }
                Matcher matcher = DEOPT_BCI.matcher(instruction);
                if (!matcher.find()) {
                    throw new AssertionError(method
                            + ": allocation lacks a source BCI: " + instruction);
                }
                result.add(new AllocationSite(new AllocationKey(kind,
                        Integer.parseInt(matcher.group(1))),
                        assignment.group(1), instruction));
            }
            return List.copyOf(result);
        }

        /**
         * Assert that this body retains exactly the requested original allocation sites,
         * in source order, and that every retained site existed in {@code original}.
         */
        public void assertRetainsExactlyOriginalAllocations(
                IRBody original, AllocationKey... expected) {
            Objects.requireNonNull(original);
            Objects.requireNonNull(expected);
            List<AllocationKey> expectedKeys = List.of(expected.clone());
            List<AllocationKey> sourceKeys = original.allocations().stream()
                    .map(AllocationSite::key).toList();
            if (new HashSet<>(sourceKeys).size() != sourceKeys.size()) {
                throw new IllegalStateException(method
                        + ": original allocation keys must be unique: " + sourceKeys);
            }
            if (new HashSet<>(expectedKeys).size() != expectedKeys.size()) {
                throw new IllegalArgumentException(
                        "Expected retained allocation keys must be unique: " + expectedKeys);
            }
            for (AllocationKey key : expectedKeys) {
                if (!sourceKeys.contains(key)) {
                    throw new IllegalArgumentException(method + ": expected allocation " + key
                            + " is not an original allocation in " + original.method);
                }
            }
            List<AllocationKey> expectedInSourceOrder = sourceKeys.stream()
                    .filter(new HashSet<>(expectedKeys)::contains).toList();
            if (!expectedKeys.equals(expectedInSourceOrder)) {
                throw new IllegalArgumentException(method
                        + ": expected retained allocations must use original source order");
            }
            List<AllocationKey> retainedKeys = allocations().stream()
                    .map(AllocationSite::key).toList();
            Asserts.assertEquals(retainedKeys, expectedKeys,
                    method + ": retained allocations must be exactly the requested originals"
                            + " in source order");
        }

        /** Parse the bundle on one call selected by exact LLVM callee and occurrence. */
        public DeoptBundle deoptBundleAtCall(String exactCallee, int occurrence) {
            if (occurrence < 0) {
                throw new IllegalArgumentException("Call occurrence must be non-negative");
            }
            List<String> matches = instructionsCalling(exactCallee);
            if (occurrence >= matches.size()) {
                throw new IllegalStateException(method + ": no call occurrence " + occurrence
                        + " of exact callee @" + exactCallee);
            }
            return parseDeoptBundle(method, matches.get(occurrence));
        }

        /**
         * Return global exact-callee occurrence indices whose root deopt scope
         * has the requested BCI.
         */
        public List<Integer> callOccurrencesAtBCI(String exactCallee, int bci) {
            List<String> matches = instructionsCalling(exactCallee);
            ArrayList<Integer> result = new ArrayList<>();
            for (int occurrence = 0; occurrence < matches.size(); occurrence++) {
                DeoptBundle bundle = parseDeoptBundle(method, matches.get(occurrence));
                if (bundle.rootScope().bci() == bci) {
                    result.add(occurrence);
                }
            }
            return List.copyOf(result);
        }

        private List<String> instructionsCalling(String exactCallee) {
            Objects.requireNonNull(exactCallee);
            if (exactCallee.isEmpty() || exactCallee.charAt(0) == '@') {
                throw new IllegalArgumentException(
                        "Exact callee must be a non-empty raw LLVM function name");
            }
            ArrayList<String> matches = new ArrayList<>();
            for (CompleteCallInstruction call : callInstructions()) {
                String instruction = call.text();
                String callee = calledFunctionName(instruction);
                if (matchesRuntimeFunctionName(callee, exactCallee)) {
                    matches.add(instruction);
                }
            }
            return List.copyOf(matches);
        }

        /** Parse the bundle on one Jeandle allocation selected by exact SSA result. */
        public DeoptBundle deoptBundleAtAllocation(String allocationResult) {
            Objects.requireNonNull(allocationResult);
            if (allocationResult.length() < 2 || allocationResult.charAt(0) != '%'
                    || allocationResult.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(
                        "Allocation result must be an exact SSA name beginning with '%'");
            }
            ArrayList<String> matches = new ArrayList<>();
            for (AllocationSite site : allocations()) {
                if (allocationResult.equals(site.result())) {
                    matches.add(site.instruction());
                }
            }
            if (matches.isEmpty()) {
                throw new IllegalStateException(method
                        + ": allocation SSA result not found: " + allocationResult);
            }
            if (matches.size() != 1) {
                throw new IllegalStateException(method + ": ambiguous allocation SSA result "
                        + allocationResult + ": " + matches.size());
            }
            return parseDeoptBundle(method, matches.get(0));
        }

        private List<CompleteCallInstruction> callInstructions() {
            ArrayList<CompleteCallInstruction> result = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = cleanInstructionLine(lines.get(i));
                int nextTokenLine = i + 1;
                while (nextTokenLine < lines.size()
                        && cleanInstructionLine(lines.get(nextTokenLine)).isEmpty()) {
                    nextTokenLine++;
                }
                boolean directStart = containsCallOrInvoke(line);
                boolean splitAssignmentStart = ASSIGNMENT_ONLY.matcher(line).matches()
                        && nextTokenLine < lines.size()
                        && containsCallOrInvoke(line + " "
                                + cleanInstructionLine(lines.get(nextTokenLine)));
                if (!directStart && !splitAssignmentStart) {
                    continue;
                }
                CompleteCallInstruction instruction = instructionStartingAt(i);
                result.add(instruction);
                i = instruction.nextLine() - 1;
            }
            return List.copyOf(result);
        }

        private CompleteCallInstruction instructionStartingAt(int startLine) {
            StringBuilder instruction = new StringBuilder(
                    cleanInstructionLine(lines.get(startLine)));
            int i = startLine + 1;
            while (i < lines.size()) {
                String nextLine = cleanInstructionLine(lines.get(i));
                if (nextLine.isEmpty()) {
                    i++;
                    continue;
                }
                if (!instructionNeedsContinuation(instruction.toString(), nextLine)) {
                    break;
                }
                instruction.append(' ').append(nextLine);
                i++;
            }
            if (!containsCallOrInvoke(instruction.toString())
                    || !hasCompleteCallableOperand(instruction.toString())) {
                throw new IllegalStateException(method + ": malformed LLVM call instruction: "
                        + instruction);
            }
            if (hasUnbalancedInstructionDelimiters(instruction.toString())) {
                throw new IllegalStateException(method + ": unterminated LLVM instruction: "
                        + instruction);
            }
            return new CompleteCallInstruction(instruction.toString(), i);
        }

        private record CompleteCallInstruction(String text, int nextLine) {}

        public IRBlock blockContaining(String substring, int occurrence) {
            int position = occurrencePosition(substring, occurrence);
            int containingLine = -1;
            int lineStart = 0;
            for (int i = 0; i < lines.size(); i++) {
                int lineEnd = lineStart + lines.get(i).length();
                if (position < lineEnd) {
                    containingLine = i;
                    break;
                }
                lineStart = lineEnd + 1;
            }
            if (containingLine < 0) {
                throw new IllegalStateException(method + ": occurrence is outside the function");
            }

            int blockStart = containingLine;
            while (blockStart >= 0 && !BLOCK_LABEL.matcher(lines.get(blockStart)).matches()) {
                blockStart--;
            }
            if (blockStart < 0) {
                throw new IllegalStateException(method + ": occurrence " + occurrence + " of '"
                        + fold(substring) + "' is outside a labeled LLVM block");
            }

            int blockEnd = blockStart + 1;
            while (blockEnd < lines.size()
                    && !BLOCK_LABEL.matcher(lines.get(blockEnd)).matches()
                    && !lines.get(blockEnd).equals("}")) {
                blockEnd++;
            }
            return new IRBlock(method, lines.subList(blockStart, blockEnd));
        }

        public IRBlock blockByLabel(String exactLabel) {
            String normalized = normalizeBlockLabel(exactLabel);
            ArrayList<Integer> matches = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Matcher label = BLOCK_LABEL.matcher(lines.get(i));
                if (label.matches()
                        && normalized.equals(normalizeBlockLabel(label.group(1)))) {
                    matches.add(i);
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException(method + ": expected one exact block label '"
                        + normalized + "', got " + matches.size());
            }
            int blockStart = matches.get(0);
            int blockEnd = blockStart + 1;
            while (blockEnd < lines.size()
                    && !BLOCK_LABEL.matcher(lines.get(blockEnd)).matches()
                    && !lines.get(blockEnd).equals("}")) {
                blockEnd++;
            }
            return new IRBlock(method, lines.subList(blockStart, blockEnd));
        }

        public int lineCount(String substring) {
            String needle = fold(substring);
            return (int) lines.stream().filter(l -> l.contains(needle)).count();
        }

        public int occurrenceCount(String substring) {
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(needle, from)) >= 0) {
                count++;
                from += needle.length();
            }
            return count;
        }

        public void assertPresent(String substring) {
            Asserts.assertTrue(occurrenceCount(substring) > 0,
                    method + ": expected '" + fold(substring) + "'");
        }

        public void assertAbsent(String substring) {
            Asserts.assertEquals(occurrenceCount(substring), 0,
                    method + ": unexpected '" + fold(substring) + "'");
        }

        public void assertLineCount(String substring, int expected) {
            Asserts.assertEquals(lineCount(substring), expected,
                    method + ": line count for '" + fold(substring) + "'");
        }

        public void assertOccurrenceCount(String substring, int expected) {
            Asserts.assertEquals(occurrenceCount(substring), expected,
                    method + ": occurrence count for '" + fold(substring) + "'");
        }

        public void assertBefore(String first, int firstOccurrence,
                                 String second, int secondOccurrence) {
            int firstAt = occurrencePosition(first, firstOccurrence);
            int secondAt = occurrencePosition(second, secondOccurrence);
            Asserts.assertTrue(firstAt < secondAt, method + ": expected occurrence "
                    + firstOccurrence + " of '" + fold(first) + "' before occurrence "
                    + secondOccurrence + " of '" + fold(second) + "'");
        }

        public void assertBetween(String lower, int lowerOccurrence,
                                  String pattern, int patternOccurrence,
                                  String upper, int upperOccurrence) {
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int patternAt = occurrencePosition(pattern, patternOccurrence);
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= patternAt && patternAt < upperAt,
                    method + ": occurrence " + patternOccurrence + " of '" + fold(pattern)
                            + "' is outside the requested interval");
        }

        public void assertAbsentBetween(String lower, int lowerOccurrence,
                                        String pattern, String upper, int upperOccurrence) {
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= upperAt, method + ": invalid interval");
            Asserts.assertFalse(text.substring(lowerAt, upperAt).contains(fold(pattern)),
                    method + ": unexpected '" + fold(pattern) + "' in interval");
        }

        private int occurrencePosition(String substring, int occurrence) {
            if (occurrence < 0) {
                throw new IllegalArgumentException("Occurrence index must be non-negative");
            }
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int at = -needle.length();
            for (int i = 0; i <= occurrence; i++) {
                at = text.indexOf(needle, at + needle.length());
                if (at < 0) {
                    throw new IllegalStateException(method + ": no occurrence " + occurrence
                            + " of '" + needle + "'");
                }
            }
            return at;
        }
    }

    private static DeoptBundle parseDeoptBundle(MethodId method, String instruction) {
        int bundleStart = -1;
        int bundleCount = 0;
        for (int from = 0;
             (from = instruction.indexOf("\"deopt\"(", from)) >= 0;
             from += "\"deopt\"(".length()) {
            bundleStart = from;
            bundleCount++;
        }
        if (bundleCount != 1) {
            throw invalidDeopt(method, "expected exactly one deopt operand bundle, got "
                    + bundleCount + ": " + instruction);
        }
        int open = instruction.indexOf('(', bundleStart);
        int close = matchingDelimiter(instruction, open, '(', ')');
        if (close < 0) {
            throw invalidDeopt(method, "unterminated deopt operand bundle: " + instruction);
        }
        List<String> operands = splitTopLevelOperands(
                instruction.substring(open + 1, close), method);
        LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects =
                new LinkedHashMap<>();
        ArrayList<DeoptScope> scopes = new ArrayList<>();

        ScopeBuilderResult root = parseScope(
                method, operands, 0, true, "", virtualObjects);
        scopes.add(root.scope());
        int at = root.nextOperand();
        while (at < operands.size()) {
            DecodedEncoding marker = decodeEncoding(method, operands.get(at));
            if (marker.valueType() != 6 || marker.index() != 0
                    || marker.basicType() != DeoptBasicType.METADATA) {
                throw invalidDeopt(method,
                        "expected an exact MethodType inline-scope marker at operand " + at);
            }
            requireOperands(method, operands, at, 2, "inline-scope method marker");
            String methodOperand = operands.get(at + 1);
            requireI64Operand(method, methodOperand, "inline method");
            ScopeBuilderResult inline = parseScope(
                    method, operands, at + 2, false,
                    methodOperand, virtualObjects);
            scopes.add(inline.scope());
            at = inline.nextOperand();
        }
        validateVirtualObjectReferences(method, scopes, virtualObjects);
        return new DeoptBundle(scopes, virtualObjects);
    }

    private static ScopeBuilderResult parseScope(
            MethodId method, List<String> operands, int start,
            boolean root, String methodOperand,
            LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects) {
        requireOperands(method, operands, start, 3, "scope header");
        long shouldReexecute = parseI64Constant(
                method, operands.get(start), "should_reexecute");
        if (shouldReexecute != 0 && shouldReexecute != 1) {
            throw invalidDeopt(method,
                    "should_reexecute must be the i64 constant 0 or 1");
        }
        int bci = parseI32Constant(method, operands.get(start + 1), "bci");
        int duplicateBCI = parseI32Constant(
                method, operands.get(start + 2), "duplicated bci");
        if (bci != duplicateBCI) {
            throw invalidDeopt(method, "duplicated BCI mismatch: "
                    + bci + " != " + duplicateBCI);
        }
        int at = start + 3;
        if (root) {
            while (at < operands.size()) {
                DecodedEncoding encoding = tryDecodeEncoding(operands.get(at));
                if (encoding == null || encoding.valueType() != 4) {
                    break;
                }
                at = parseVirtualObjectDescriptor(
                        method, operands, at, encoding, virtualObjects);
            }
        }

        LinkedHashMap<Integer, DeoptValue> locals = new LinkedHashMap<>();
        LinkedHashMap<Integer, DeoptValue> stack = new LinkedHashMap<>();
        ArrayList<DeoptMonitor> monitors = new ArrayList<>();
        String origPcOperand = "";
        // HotSpot appends scope values in operand order. Normal encodings carry
        // their slot index, while a VORef encoding carries its VO id instead.
        int nextLocalSlot = 0;
        int nextStackSlot = 0;
        int phase = 0;
        while (at < operands.size()) {
            DecodedEncoding encoding = decodeEncoding(method, operands.get(at));
            int valueType = encoding.valueType();
            if (valueType == 6) {
                break;
            }
            if (valueType == 4) {
                throw invalidDeopt(method,
                        "virtual-object descriptor appears after root scope values");
            }
            if (valueType == 7) {
                throw invalidDeopt(method,
                        "narrow-oop markers are unsupported with compressed pointers disabled");
            }
            if (valueType == 0 || valueType == 8) {
                phase = requireScopePhase(method, phase, 0, "local");
                requireOperands(method, operands, at, 2, "local value");
                DeoptValue value = valueType == 8
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                if (valueType == 0 && encoding.index() != nextLocalSlot) {
                    throw invalidDeopt(method, "out-of-order local index "
                            + encoding.index() + ", expected " + nextLocalSlot);
                }
                if (valueType == 8
                        && encoding.index() != value.virtualObjectId()) {
                    throw invalidDeopt(method,
                            "VORef local encoding id does not match its value");
                }
                putUniqueScopeValue(
                        method, locals, nextLocalSlot, value, "local");
                nextLocalSlot += valueType == 8
                        ? 1 : scopeSlotWidth(encoding.basicType());
                at += 2;
            } else if (valueType == 1 || valueType == 9) {
                phase = requireScopePhase(method, phase, 1, "stack");
                requireOperands(method, operands, at, 2, "stack value");
                DeoptValue value = valueType == 9
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                if (valueType == 1 && encoding.index() != nextStackSlot) {
                    throw invalidDeopt(method, "out-of-order stack index "
                            + encoding.index() + ", expected " + nextStackSlot);
                }
                if (valueType == 9
                        && encoding.index() != value.virtualObjectId()) {
                    throw invalidDeopt(method,
                            "VORef stack encoding id does not match its value");
                }
                putUniqueScopeValue(
                        method, stack, nextStackSlot, value, "stack");
                nextStackSlot += valueType == 9
                        ? 1 : scopeSlotWidth(encoding.basicType());
                at += 2;
            } else if (valueType == 3) {
                phase = requireScopePhase(method, phase, 2, "monitor");
                requireOperands(method, operands, at, 3, "monitor value");
                if (encoding.basicType() != DeoptBasicType.OBJECT
                        || encoding.index() < 0 || encoding.index() > 1) {
                    throw invalidDeopt(method,
                            "monitor encoding requires T_OBJECT and kind index 0 or 1");
                }
                boolean eliminated = encoding.index() == 1;
                DeoptValue owner = eliminated
                        ? parseVORef(method, encoding, operands.get(at + 1))
                        : parseConcreteValue(method, encoding, operands.get(at + 1));
                monitors.add(new DeoptMonitor(
                        monitors.size(), eliminated, owner, operands.get(at + 2)));
                at += 3;
            } else if (valueType == 5) {
                phase = requireScopePhase(method, phase, 3, "orig-pc");
                if (!root || !origPcOperand.isEmpty()
                        || encoding.index() != 0
                        || encoding.basicType() != DeoptBasicType.ADDRESS) {
                    throw invalidDeopt(method, "malformed or duplicate root orig-pc slot");
                }
                requireOperands(method, operands, at, 2, "orig-pc value");
                origPcOperand = operands.get(at + 1);
                at += 2;
            } else {
                throw invalidDeopt(method,
                        "unsupported deopt value type " + valueType);
            }
        }
        return new ScopeBuilderResult(new DeoptScope(
                root, methodOperand, shouldReexecute == 1,
                bci, duplicateBCI, locals, stack, monitors, origPcOperand), at);
    }

    private static int parseVirtualObjectDescriptor(
            MethodId method, List<String> operands, int at,
            DecodedEncoding header,
            LinkedHashMap<Integer, VirtualObjectDescriptor> virtualObjects) {
        if (header.index() < 0
                || header.basicType() != DeoptBasicType.OBJECT
                        && header.basicType() != DeoptBasicType.ARRAY) {
            throw invalidDeopt(method,
                    "virtual-object descriptor requires a non-negative id"
                            + " and T_OBJECT or T_ARRAY");
        }
        requireOperands(method, operands, at, 3, "virtual-object descriptor header");
        requireI64Operand(method, operands.get(at + 1), "virtual-object klass");
        int fieldCount = parseI32Constant(
                method, operands.get(at + 2), "virtual-object field count");
        if (fieldCount < 0) {
            throw invalidDeopt(method,
                    "virtual-object field count must be non-negative");
        }
        requireOperands(method, operands, at, 3 + fieldCount * 2,
                "virtual-object descriptor fields");
        LinkedHashMap<Integer, VirtualObjectEntry> entries = new LinkedHashMap<>();
        int fieldAt = at + 3;
        for (int i = 0; i < fieldCount; i++) {
            DecodedEncoding field = decodeEncoding(method, operands.get(fieldAt));
            if (field.index() < 0 || field.valueType() != 0
                    && field.valueType() != 8) {
                throw invalidDeopt(method,
                        "descriptor entry requires LocalType or VORefLocalType");
            }
            DeoptValue value = field.valueType() == 8
                    ? parseVORef(method, field, operands.get(fieldAt + 1))
                    : parseConcreteValue(method, field, operands.get(fieldAt + 1));
            VirtualObjectEntry previous = entries.putIfAbsent(
                    field.index(), new VirtualObjectEntry(
                            field.index(), field.basicType(), value));
            if (previous != null) {
                throw invalidDeopt(method,
                        "duplicate descriptor offset " + field.index()
                                + " for virtual object " + header.index());
            }
            fieldAt += 2;
        }
        DescriptorKind kind = header.basicType() == DeoptBasicType.ARRAY
                ? DescriptorKind.ARRAY : DescriptorKind.INSTANCE;
        VirtualObjectDescriptor descriptor = new VirtualObjectDescriptor(
                header.index(), kind, operands.get(at + 1), entries);
        if (virtualObjects.putIfAbsent(header.index(), descriptor) != null) {
            throw invalidDeopt(method,
                    "duplicate virtual-object id " + header.index());
        }
        return fieldAt;
    }

    private static DeoptValue parseConcreteValue(
            MethodId method, DecodedEncoding encoding, String operand) {
        DeoptBasicType basicType = encoding.basicType();
        if (basicType == DeoptBasicType.OBJECT
                || basicType == DeoptBasicType.ARRAY) {
            if (!operand.startsWith("ptr ")) {
                throw invalidDeopt(method,
                        "oop value must be a typed ptr operand: " + operand);
            }
            DeoptValueKind kind = operand.matches(
                    "ptr(?: addrspace\\(1\\))? null")
                            ? DeoptValueKind.NULL
                            : DeoptValueKind.MATERIALIZED_OOP;
            return new DeoptValue(kind, basicType, operand, -1);
        }
        boolean validScalar = switch (basicType) {
            case BOOLEAN, CHAR, BYTE, SHORT, INT ->
                    operand.matches("i(?:1|8|16|32) .+");
            case LONG -> operand.startsWith("i64 ");
            case FLOAT -> operand.startsWith("float ");
            case DOUBLE -> operand.startsWith("double ");
            case ILLEGAL -> operand.matches("i(?:32|64) 0");
            default -> false;
        };
        if (!validScalar) {
            throw invalidDeopt(method, "basic type " + basicType
                    + " is incompatible with deopt operand " + operand);
        }
        return new DeoptValue(
                DeoptValueKind.SCALAR, basicType, operand, -1);
    }

    private static DeoptValue parseVORef(
            MethodId method, DecodedEncoding encoding, String operand) {
        if (encoding.basicType() != DeoptBasicType.OBJECT
                || encoding.valueType() != 8 && encoding.valueType() != 9
                        && encoding.valueType() != 3) {
            throw invalidDeopt(method,
                    "VORef requires T_OBJECT and a VORef slot, field, or monitor encoding");
        }
        int id = parseI32Constant(method, operand, "virtual-object reference");
        if (id < 0) {
            throw invalidDeopt(method,
                    "virtual-object reference id must be non-negative");
        }
        return new DeoptValue(
                DeoptValueKind.VO_REF, DeoptBasicType.OBJECT, operand, id);
    }

    private static void validateVirtualObjectReferences(
            MethodId method, List<DeoptScope> scopes,
            Map<Integer, VirtualObjectDescriptor> virtualObjects) {
        for (VirtualObjectDescriptor descriptor : virtualObjects.values()) {
            for (VirtualObjectEntry entry : descriptor.entries().values()) {
                validateVORef(method, entry.value(), virtualObjects);
            }
        }
        for (DeoptScope scope : scopes) {
            for (DeoptValue value : scope.locals().values()) {
                validateVORef(method, value, virtualObjects);
            }
            for (DeoptValue value : scope.stack().values()) {
                validateVORef(method, value, virtualObjects);
            }
            for (DeoptMonitor monitor : scope.monitors()) {
                validateVORef(method, monitor.owner(), virtualObjects);
            }
        }
    }

    private static void validateVORef(
            MethodId method, DeoptValue value,
            Map<Integer, VirtualObjectDescriptor> virtualObjects) {
        if (value.kind() == DeoptValueKind.VO_REF
                && !virtualObjects.containsKey(value.virtualObjectId())) {
            throw invalidDeopt(method,
                    "dangling virtual-object reference " + value.virtualObjectId());
        }
    }

    private static int requireScopePhase(
            MethodId method, int current, int requested, String section) {
        if (requested < current) {
            throw invalidDeopt(method,
                    "out-of-order " + section + " section");
        }
        return requested;
    }

    private static int scopeSlotWidth(DeoptBasicType type) {
        return type == DeoptBasicType.LONG || type == DeoptBasicType.DOUBLE
                ? 2 : 1;
    }

    private static void putUniqueScopeValue(
            MethodId method, Map<Integer, DeoptValue> values,
            int index, DeoptValue value, String section) {
        if (index < 0 || values.putIfAbsent(index, value) != null) {
            throw invalidDeopt(method,
                    "duplicate or negative " + section + " index " + index);
        }
    }

    private static DecodedEncoding decodeEncoding(MethodId method, String operand) {
        try {
            long encoded = parseUnsignedI64Constant(operand);
            long rawIndex = encoded >>> 32;
            if (rawIndex > Integer.MAX_VALUE) {
                throw invalidDeopt(method, "deopt encoding index overflows int");
            }
            int valueType = (int) ((encoded >>> 16) & 0xffff);
            int basicType = (int) (encoded & 0xffff);
            return new DecodedEncoding(
                    (int) rawIndex, valueType, DeoptBasicType.fromTag(basicType));
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    "deopt encoding must be an unsigned i64 constant: " + operand);
        } catch (IllegalStateException e) {
            throw invalidDeopt(method, e.getMessage());
        }
    }

    private static DecodedEncoding tryDecodeEncoding(String operand) {
        try {
            long encoded = parseUnsignedI64Constant(operand);
            long rawIndex = encoded >>> 32;
            int valueType = (int) ((encoded >>> 16) & 0xffff);
            int basicType = (int) (encoded & 0xffff);
            if (rawIndex > Integer.MAX_VALUE) {
                return null;
            }
            return new DecodedEncoding(
                    (int) rawIndex, valueType, DeoptBasicType.fromTag(basicType));
        } catch (NumberFormatException | IllegalStateException notEncoding) {
            return null;
        }
    }

    private static long parseUnsignedI64Constant(String operand) {
        if (!operand.matches("i64 [0-9]+")) {
            throw new NumberFormatException(operand);
        }
        return Long.parseUnsignedLong(operand.substring(4));
    }

    private static long parseI64Constant(
            MethodId method, String operand, String field) {
        try {
            return parseUnsignedI64Constant(operand);
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    field + " must be an unsigned i64 constant: " + operand);
        }
    }

    private static int parseI32Constant(
            MethodId method, String operand, String field) {
        if (!operand.matches("i32 -?[0-9]+")) {
            throw invalidDeopt(method,
                    field + " must be an i32 constant: " + operand);
        }
        try {
            return Integer.parseInt(operand.substring(4));
        } catch (NumberFormatException e) {
            throw invalidDeopt(method,
                    field + " overflows i32: " + operand);
        }
    }

    private static void requireI64Operand(
            MethodId method, String operand, String field) {
        parseI64Constant(method, operand, field);
    }

    private static void requireOperands(
            MethodId method, List<String> operands,
            int at, int count, String detail) {
        if (count < 0 || at < 0 || at > operands.size() - count) {
            throw invalidDeopt(method,
                    "truncated " + detail + " at operand " + at);
        }
    }

    private static List<String> splitTopLevelOperands(
            String text, MethodId method) {
        try {
            return splitTopLevelSegments(text, "deopt operand list");
        } catch (IllegalArgumentException malformed) {
            throw invalidDeopt(method, malformed.getMessage());
        }
    }

    private static List<String> splitTopLevelSegments(
            String text, String context) {
        ArrayList<String> result = new ArrayList<>();
        int start = 0;
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == '(') {
                parentheses++;
            } else if (ch == ')') {
                parentheses--;
            } else if (ch == '[') {
                brackets++;
            } else if (ch == ']') {
                brackets--;
            } else if (ch == '{') {
                braces++;
            } else if (ch == '}') {
                braces--;
            } else if (ch == '<') {
                angles++;
            } else if (ch == '>') {
                angles--;
            } else if (ch == ',' && parentheses == 0 && brackets == 0
                    && braces == 0 && angles == 0) {
                addTopLevelSegment(result, text.substring(start, i), context);
                start = i + 1;
            }
            if (parentheses < 0 || brackets < 0 || braces < 0 || angles < 0) {
                throw new IllegalArgumentException(
                        "unbalanced " + context + ": " + text);
            }
        }
        if (quoted || parentheses != 0 || brackets != 0
                || braces != 0 || angles != 0) {
            throw new IllegalArgumentException(
                    "unbalanced " + context + ": " + text);
        }
        addTopLevelSegment(result, text.substring(start), context);
        return List.copyOf(result);
    }

    private static void addTopLevelSegment(
            List<String> segments, String segment, String context) {
        String folded = fold(segment);
        if (folded.isEmpty()) {
            throw new IllegalArgumentException("empty " + context);
        }
        segments.add(folded);
    }

    private static int matchingDelimiter(
            String text, int open, char opening, char closing) {
        if (open < 0 || open >= text.length() || text.charAt(open) != opening) {
            return -1;
        }
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == opening) {
                depth++;
            } else if (ch == closing && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String calledFunctionName(String line) {
        Matcher operation = CALL_OR_INVOKE_OPCODE.matcher(line);
        if (!operation.find()) {
            return null;
        }
        ParsedCallableOperand callable = parseCallableOperand(line, operation.end());
        return callable == null ? null : callable.directGlobal();
    }

    private static boolean containsCallOrInvoke(String line) {
        return CALL_OR_INVOKE_OPCODE.matcher(line).find();
    }

    private static boolean instructionNeedsContinuation(
            String instruction, String nextLine) {
        if (!containsCallOrInvoke(instruction)) {
            return true;
        }
        return !isDefiniteInstructionBoundary(nextLine);
    }

    private static boolean isDefiniteInstructionBoundary(String line) {
        String folded = fold(line);
        return folded.equals("}") || IRBody.BLOCK_LABEL.matcher(folded).matches()
                || DEBUG_RECORD.matcher(folded).find()
                || ASSIGNED_INSTRUCTION.matcher(folded).find()
                || UNASSIGNED_INSTRUCTION.matcher(folded).find();
    }

    private static boolean hasUnbalancedInstructionDelimiters(String instruction) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        boolean quoted = false;
        for (int i = 0; i < instruction.length(); i++) {
            char ch = instruction.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
                continue;
            }
            switch (ch) {
                case '(' -> parentheses++;
                case ')' -> parentheses--;
                case '[' -> brackets++;
                case ']' -> brackets--;
                case '{' -> braces++;
                case '}' -> braces--;
                case '<' -> angles++;
                case '>' -> angles--;
                default -> { }
            }
            if (parentheses < 0 || brackets < 0 || braces < 0 || angles < 0) {
                throw new IllegalStateException("Unbalanced LLVM instruction delimiters: "
                        + instruction);
            }
        }
        return quoted || parentheses != 0 || brackets != 0 || braces != 0 || angles != 0;
    }

    private static boolean hasCompleteCallableOperand(String line) {
        Matcher operation = CALL_OR_INVOKE_OPCODE.matcher(line);
        if (!operation.find()) {
            return false;
        }
        ParsedCallableOperand callable = parseCallableOperand(line, operation.end());
        return callable != null
                && matchingDelimiter(line, callable.arguments(), '(', ')') >= 0;
    }

    private static ParsedCallableOperand parseCallableOperand(
            String line, int operationEnd) {
        int parentheses = 0;
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        boolean quoted = false;
        for (int at = operationEnd; at < line.length(); at++) {
            char ch = line.charAt(at);
            if (quoted) {
                if (ch == '\\') {
                    at++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
                continue;
            }
            if (ch == '(') {
                parentheses++;
                continue;
            }
            if (ch == ')') {
                parentheses--;
                continue;
            }
            if (ch == '[') {
                brackets++;
                continue;
            }
            if (ch == ']') {
                brackets--;
                continue;
            }
            if (ch == '{') {
                braces++;
                continue;
            }
            if (ch == '}') {
                braces--;
                continue;
            }
            if (ch == '<') {
                angles++;
                continue;
            }
            if (ch == '>') {
                angles--;
                continue;
            }
            if (parentheses != 0 || brackets != 0 || braces != 0 || angles != 0) {
                continue;
            }
            char sigil = line.charAt(at);
            if (sigil != '@' && sigil != '%') {
                continue;
            }
            ParsedOperand operand = parseLLVMNamedOperand(line, at);
            int next = skipWhitespace(line, operand.end);
            if (next < line.length() && line.charAt(next) == '('
                    && hasCallableSuffix(line, next)) {
                if (sigil == '%') {
                    return new ParsedCallableOperand(
                            next, CallableOperandKind.INDIRECT_LOCAL, null);
                }
                String precedingToken =
                        precedingTopLevelToken(line, operationEnd, at);
                CallableOperandKind kind =
                        NAMED_CONSTANT_CALLEE_OPERATORS.contains(precedingToken)
                                ? CallableOperandKind.CONSTANT
                                : CallableOperandKind.DIRECT_GLOBAL;
                return new ParsedCallableOperand(
                        next, kind, kind == CallableOperandKind.DIRECT_GLOBAL
                                ? operand.value : null);
            }
            at = operand.end - 1;
        }

        int expressionArguments =
                parenthesizedCallableArgumentListStart(line, operationEnd);
        if (expressionArguments >= 0) {
            return new ParsedCallableOperand(
                    expressionArguments, CallableOperandKind.CONSTANT_EXPRESSION, null);
        }

        Matcher asm = INLINE_ASM_CALLEE.matcher(line);
        if (asm.find(operationEnd)) {
            int afterConstraints = afterQuotedStrings(line, asm.end(), 2);
            if (afterConstraints < 0) {
                return null;
            }
            int arguments = skipWhitespace(line, afterConstraints);
            return arguments < line.length() && line.charAt(arguments) == '('
                    ? new ParsedCallableOperand(
                            arguments, CallableOperandKind.INLINE_ASM, null)
                    : null;
        }

        Matcher constant = CONSTANT_CALLEE.matcher(line);
        if (constant.find(operationEnd)) {
            return new ParsedCallableOperand(
                    line.indexOf('(', constant.start()),
                    CallableOperandKind.CONSTANT, null);
        }
        return null;
    }

    private static String precedingTopLevelToken(
            String text, int lowerBound, int before) {
        int end = before;
        while (end > lowerBound && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > lowerBound) {
            char ch = text.charAt(start - 1);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '$'
                    || ch == '.' || ch == '_' || ch == '%' || ch == '@')) {
                break;
            }
            start--;
        }
        return text.substring(start, end);
    }

    private enum CallableOperandKind {
        DIRECT_GLOBAL,
        INDIRECT_LOCAL,
        CONSTANT,
        CONSTANT_EXPRESSION,
        INLINE_ASM
    }

    private record ParsedCallableOperand(
            int arguments, CallableOperandKind kind, String directGlobal) {
        private ParsedCallableOperand {
            if (arguments < 0 || kind == null
                    || (kind == CallableOperandKind.DIRECT_GLOBAL)
                            != (directGlobal != null)) {
                throw new IllegalArgumentException("Invalid parsed LLVM callable operand");
            }
        }
    }

    private static int parenthesizedCallableArgumentListStart(
            String line, int operationEnd) {
        int brackets = 0;
        int braces = 0;
        int angles = 0;
        boolean quoted = false;
        for (int at = operationEnd; at < line.length(); at++) {
            char ch = line.charAt(at);
            if (quoted) {
                if (ch == '\\') {
                    at++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == '[') {
                brackets++;
            } else if (ch == ']') {
                brackets--;
            } else if (ch == '{') {
                braces++;
            } else if (ch == '}') {
                braces--;
            } else if (ch == '<') {
                angles++;
            } else if (ch == '>') {
                angles--;
            } else if (ch == '(' && brackets == 0 && braces == 0 && angles == 0) {
                int expressionClose = matchingDelimiter(line, at, '(', ')');
                if (expressionClose < 0) {
                    return -1;
                }
                int arguments = skipWhitespace(line, expressionClose + 1);
                if (arguments < line.length() && line.charAt(arguments) == '('
                        && hasCallableSuffix(line, arguments)) {
                    return arguments;
                }
                at = expressionClose;
            }
        }
        return -1;
    }

    private static boolean hasCallableSuffix(String line, int arguments) {
        int close = matchingDelimiter(line, arguments, '(', ')');
        if (close < 0) {
            return false;
        }
        int suffix = skipWhitespace(line, close + 1);
        return suffix >= line.length()
                || line.charAt(suffix) != '*'
                && line.charAt(suffix) != '@'
                && line.charAt(suffix) != '%';
    }

    private static int skipWhitespace(String text, int at) {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    private static int afterQuotedStrings(String text, int at, int count) {
        int completed = 0;
        boolean quoted = false;
        for (int i = at; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!quoted) {
                if (ch == '"') {
                    quoted = true;
                }
                continue;
            }
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                quoted = false;
                if (++completed == count) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private static String normalizeBlockLabel(String label) {
        Objects.requireNonNull(label);
        String operand = label.startsWith("%") ? label : "%" + label;
        ParsedOperand parsed = parseLLVMNamedOperand(operand, 0);
        if (parsed.end != operand.length()) {
            throw new IllegalArgumentException("Trailing characters in LLVM block label "
                    + label);
        }
        return parsed.value;
    }

    private static IllegalStateException invalidDeopt(MethodId method, String detail) {
        return new IllegalStateException(
                "Malformed deopt bundle for " + method + ": " + detail);
    }

    /** One labeled LLVM basic block with occurrence-aware assertions. */
    public static final class IRBlock {
        private static final Pattern METADATA_ATTACHMENT = Pattern.compile(
                "^![-A-Za-z$._0-9]+\\s+!.+$");
        private final MethodId method;
        private final List<String> lines;
        private final String text;

        private IRBlock(MethodId method, List<String> lines) {
            this.method = method;
            this.lines = List.copyOf(lines);
            this.text = String.join("\n", this.lines);
        }

        public String label() {
            Matcher label = IRBody.BLOCK_LABEL.matcher(lines.get(0));
            if (!label.matches()) {
                throw new IllegalStateException(method + ": block lacks a label: " + text);
            }
            return normalizeBlockLabel(label.group(1));
        }

        public List<String> lines() {
            return lines;
        }

        public List<String> conditionalBranchTargets() {
            List<String> instructions = semanticLines();
            List<String> branches = instructions.stream()
                    .filter(line -> line.startsWith("br ")).toList();
            if (branches.size() != 1
                    || !branches.get(0).equals(instructions.get(instructions.size() - 1))
                    || !branches.get(0).startsWith("br i1 ")) {
                throw new IllegalStateException(method + ": block " + label()
                        + " must end in exactly one conditional branch");
            }
            String branch = branches.get(0);
            List<String> operands = branchOperands(branch);
            if (operands.size() < 3
                    || !operands.get(0).startsWith("i1 ")
                    || operands.get(0).substring(3).isBlank()) {
                throw malformedBranch(branch);
            }
            validateMetadataAttachments(operands, 3, branch);
            return List.of(parseLabelOperand(operands.get(1), branch),
                    parseLabelOperand(operands.get(2), branch));
        }

        public String unconditionalBranchTarget() {
            List<String> instructions = semanticLines();
            List<String> branches = instructions.stream()
                    .filter(line -> line.startsWith("br ")).toList();
            if (branches.size() != 1
                    || !branches.get(0).equals(instructions.get(instructions.size() - 1))
                    || !branches.get(0).startsWith("br label ")) {
                throw new IllegalStateException(method + ": block " + label()
                        + " must end in exactly one unconditional branch");
            }
            String branch = branches.get(0);
            List<String> operands = branchOperands(branch);
            if (operands.isEmpty()) {
                throw malformedBranch(branch);
            }
            validateMetadataAttachments(operands, 1, branch);
            return parseLabelOperand(operands.get(0), branch);
        }

        public boolean isEmptyForwardingBlock() {
            List<String> instructions = semanticLines();
            if (instructions.size() != 2
                    || !IRBody.BLOCK_LABEL.matcher(instructions.get(0)).matches()
                    || !instructions.get(1).startsWith("br label ")) {
                return false;
            }
            unconditionalBranchTarget();
            return true;
        }

        public String emptyForwardingTarget() {
            if (!isEmptyForwardingBlock()) {
                throw new IllegalStateException(method + ": block " + label()
                        + " is not an empty forwarding block");
            }
            return unconditionalBranchTarget();
        }

        private List<String> semanticLines() {
            return lines.stream()
                    .map(PEATestUtils::cleanInstructionLine)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !DEBUG_RECORD.matcher(line).find())
                    .toList();
        }

        private List<String> branchOperands(String branch) {
            try {
                return splitTopLevelSegments(branch.substring(3), "branch operand list");
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(method + ": malformed branch in " + label()
                        + ": " + malformed.getMessage());
            }
        }

        private String parseLabelOperand(String operand, String branch) {
            if (!operand.startsWith("label ")) {
                throw malformedBranch(branch);
            }
            String labelOperand = operand.substring("label ".length());
            try {
                return normalizeBlockLabel(labelOperand);
            } catch (IllegalArgumentException malformed) {
                throw malformedBranch(branch);
            }
        }

        private void validateMetadataAttachments(
                List<String> operands, int firstMetadata, String branch) {
            for (int i = firstMetadata; i < operands.size(); i++) {
                if (!METADATA_ATTACHMENT.matcher(operands.get(i)).matches()) {
                    throw malformedBranch(branch);
                }
            }
        }

        private IllegalStateException malformedBranch(String branch) {
            return new IllegalStateException(method + ": malformed branch in "
                    + label() + ": " + branch);
        }

        public int occurrenceCount(String substring) {
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int count = 0;
            int from = 0;
            while ((from = text.indexOf(needle, from)) >= 0) {
                count++;
                from += needle.length();
            }
            return count;
        }

        public void assertAbsent(String substring) {
            Asserts.assertEquals(occurrenceCount(substring), 0,
                    method + ": unexpected '" + fold(substring) + "' in block");
        }

        public void assertPresent(String substring) {
            Asserts.assertTrue(occurrenceCount(substring) > 0,
                    method + ": expected '" + fold(substring) + "' in block");
        }

        public void assertOccurrenceCount(String substring, int expected) {
            Asserts.assertEquals(occurrenceCount(substring), expected,
                    method + ": occurrence count for '" + fold(substring) + "' in block");
        }

        public void assertBefore(String first, int firstOccurrence,
                                 String second, int secondOccurrence) {
            int firstAt = occurrencePosition(first, firstOccurrence);
            int secondAt = occurrencePosition(second, secondOccurrence);
            Asserts.assertTrue(firstAt < secondAt, method + ": expected occurrence "
                    + firstOccurrence + " of '" + fold(first) + "' before occurrence "
                    + secondOccurrence + " of '" + fold(second) + "' in block");
        }

        public void assertBetween(String lower, int lowerOccurrence,
                                  String pattern, int patternOccurrence,
                                  String upper, int upperOccurrence) {
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int patternAt = occurrencePosition(pattern, patternOccurrence);
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= patternAt && patternAt < upperAt,
                    method + ": occurrence " + patternOccurrence + " of '" + fold(pattern)
                            + "' is outside the requested block-local interval");
        }

        public void assertAbsentBetween(String lower, int lowerOccurrence,
                                        String pattern, String upper, int upperOccurrence) {
            assertOccurrenceCountBetween(lower, lowerOccurrence, pattern, upper, upperOccurrence,
                    0);
        }

        public void assertOccurrenceCountBetween(String lower, int lowerOccurrence,
                                                 String pattern, String upper, int upperOccurrence,
                                                 int expected) {
            if (expected < 0) {
                throw new IllegalArgumentException(
                        "Expected occurrence count must be non-negative");
            }
            int lowerAt = occurrencePosition(lower, lowerOccurrence) + fold(lower).length();
            int upperAt = occurrencePosition(upper, upperOccurrence);
            Asserts.assertTrue(lowerAt <= upperAt, method + ": invalid block-local interval");
            String needle = fold(pattern);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int count = 0;
            int from = lowerAt;
            while ((from = text.indexOf(needle, from)) >= 0 && from < upperAt) {
                count++;
                from += needle.length();
            }
            Asserts.assertEquals(count, expected,
                    method + ": occurrence count for '" + needle
                            + "' in requested block-local interval");
        }

        private int occurrencePosition(String substring, int occurrence) {
            if (occurrence < 0) {
                throw new IllegalArgumentException("Occurrence index must be non-negative");
            }
            String needle = fold(substring);
            if (needle.isEmpty()) {
                throw new IllegalArgumentException("Occurrence needle must not be empty");
            }
            int at = -needle.length();
            for (int i = 0; i <= occurrence; i++) {
                at = text.indexOf(needle, at + needle.length());
                if (at < 0) {
                    throw new IllegalStateException(method + ": no occurrence " + occurrence
                            + " of '" + needle + "' in block");
                }
            }
            return at;
        }
    }

    /** Resolve the exact timestamp-paired frontend dump for a method. */
    public static IRBody frontendIR(Path dumpDir, MethodId method) throws IOException {
        return dumpPair(dumpDir, method).frontend;
    }

    /** Resolve the exact timestamp-paired optimized dump for a method. */
    public static IRBody finalIR(Path dumpDir, MethodId method) throws IOException {
        return dumpPair(dumpDir, method).optimized;
    }

    private static DumpPair dumpPair(Path dumpDir, MethodId method) throws IOException {
        if (!Files.isDirectory(dumpDir)) {
            throw new IllegalArgumentException("Dump path is not a directory: " + dumpDir);
        }
        String prefix = method.dumpStem() + "_";
        LinkedHashMap<String, Path[]> byTimestamp = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.list(dumpDir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                String name = path.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".ll")
                        || name.endsWith("_inline_callees.ll")) {
                    return;
                }
                boolean optimized = name.endsWith("_optimized.ll");
                String suffix = optimized ? "_optimized.ll" : ".ll";
                String timestamp = name.substring(prefix.length(), name.length() - suffix.length());
                if (timestamp.isEmpty()) {
                    return;
                }
                Path[] pair = byTimestamp.computeIfAbsent(timestamp, ignored -> new Path[2]);
                int slot = optimized ? 1 : 0;
                if (pair[slot] != null) {
                    throw new IllegalStateException("Duplicate " + suffix + " dump for "
                            + method + " timestamp " + timestamp);
                }
                pair[slot] = path;
            });
        }

        ArrayList<DumpPair> exact = new ArrayList<>();
        for (Map.Entry<String, Path[]> entry : byTimestamp.entrySet()) {
            Path[] paths = entry.getValue();
            if (paths[0] == null || paths[1] == null) {
                continue;
            }
            IRBody frontend;
            IRBody optimized;
            try {
                frontend = IRBody.fromModuleLines(Files.readAllLines(paths[0]), method);
                optimized = IRBody.fromModuleLines(Files.readAllLines(paths[1]), method);
            } catch (IllegalStateException notThisMethod) {
                if (notThisMethod.getMessage().startsWith("Exact function definition not found:")) {
                    continue;
                }
                throw notThisMethod;
            }
            exact.add(new DumpPair(entry.getKey(), frontend, optimized));
        }
        if (exact.isEmpty()) {
            throw new IllegalStateException("No exact timestamp-paired dumps for " + method
                    + " in " + dumpDir);
        }
        if (exact.size() != 1) {
            throw new IllegalStateException("Ambiguous timestamp-paired dumps for " + method
                    + " in " + dumpDir + ": "
                    + exact.stream().map(p -> p.timestamp).collect(Collectors.joining(", ")));
        }
        return exact.get(0);
    }

    private static final class DumpPair {
        private final String timestamp;
        private final IRBody frontend;
        private final IRBody optimized;

        private DumpPair(String timestamp, IRBody frontend, IRBody optimized) {
            this.timestamp = timestamp;
            this.frontend = frontend;
            this.optimized = optimized;
        }
    }

    /** Compare the single stable result payload from PEA-on and PEA-off children. */
    public static void assertPEAOnOffEquivalent(String wrapperFQN, Method... targets)
            throws Exception {
        try (RunResult on = behaviorRun(wrapperFQN, targets).run();
             RunResult off = behaviorRun(wrapperFQN, targets).peaOff().run()) {
            String onPayload = exactResultPayload(on.output().getStdout());
            String offPayload = exactResultPayload(off.output().getStdout());
            Asserts.assertEquals(onPayload, offPayload, "PEA-on/off result payload mismatch");
        }
    }

    private static MethodId[] methodIds(Method... methods) {
        Objects.requireNonNull(methods);
        MethodId[] result = new MethodId[methods.length];
        for (int i = 0; i < methods.length; i++) {
            result[i] = MethodId.rootOf(Objects.requireNonNull(methods[i]));
        }
        return result;
    }

    // Every target handed to a run is a compilation root, so normalize any
    // callee-style MethodId to its root identity before building the run.
    private static MethodId[] rootAll(MethodId[] targets) {
        Objects.requireNonNull(targets);
        MethodId[] result = new MethodId[targets.length];
        for (int i = 0; i < targets.length; i++) {
            result[i] = Objects.requireNonNull(targets[i]).asRoot();
        }
        return result;
    }

    private static String exactResultPayload(String stdout) {
        List<String> results = splitLines(stdout).stream()
                .filter(line -> line.startsWith(RESULT_SENTINEL)).collect(Collectors.toList());
        if (results.size() != 1) {
            throw new IllegalStateException("Expected exactly one " + RESULT_SENTINEL
                    + " line, got " + results.size());
        }
        return results.get(0).substring(RESULT_SENTINEL.length());
    }

    private static final String LLVM_BLOCK_NAME =
            "(?:[-A-Za-z$._0-9]+|\"(?:[^\"\\\\]|\\\\.)*\")";
    private static final Pattern BLOCK_LABEL = Pattern.compile(
            "^(" + LLVM_BLOCK_NAME + "):(?:\\s*;.*)?$");
    private static final Pattern BLOCK_WITH_PREDECESSORS = Pattern.compile(
            "^(" + LLVM_BLOCK_NAME + "):\\s*; preds = (.+)$");
    private static final Pattern LLVM_BLOCK_REFERENCE = Pattern.compile(
            "%(" + LLVM_BLOCK_NAME + ")");
    private static final Pattern PHI_INCOMING_BLOCK = Pattern.compile(
            ",\\s*%(" + LLVM_BLOCK_NAME + ")\\s*\\]");
    private static final Pattern POISON_TOKEN = Pattern.compile(
            "(?<![-A-Za-z$._0-9%@!])poison(?![-A-Za-z$._0-9:])");
    private static final Pattern CONSTANT_BRANCH = Pattern.compile(
            "^br\\s+i1\\s+(true|false),\\s+label\\s+%("
                    + LLVM_BLOCK_NAME + "),\\s+label\\s+%(" + LLVM_BLOCK_NAME + ")");
    private static final Pattern SWITCH_HEADER = Pattern.compile(
            "^switch\\s+i\\d+\\s+(-?\\d+|true|false),\\s+label\\s+%("
                    + LLVM_BLOCK_NAME + ")\\s*\\[");
    private static final Pattern SWITCH_CASE = Pattern.compile(
            "\\bi\\d+\\s+(-?\\d+|true|false),\\s+label\\s+%("
                    + LLVM_BLOCK_NAME + ")");
    private static final Pattern LLVM_LABEL_REFERENCE = Pattern.compile(
            "\\blabel\\s+%(" + LLVM_BLOCK_NAME + ")");
    private static final Pattern TERMINATOR_START = Pattern.compile(
            "^(?:" + LLVM_LOCAL_NAME + "\\s*=\\s*)?"
                    + "(?:invoke|callbr|ret|br|switch|indirectbr|resume|catchswitch|"
                    + "catchret|cleanupret|unreachable)\\b");
    private static final Pattern ASSIGNED_OPCODE = Pattern.compile(
            "^" + LLVM_LOCAL_NAME + "\\s*=\\s*([a-z][a-z0-9.]*)\\b");
    private static final Set<String> PURE_POISON_OPCODES = Set.of(
            "add", "fadd", "sub", "fsub", "mul", "fmul", "udiv", "sdiv",
            "fdiv", "urem", "srem", "frem", "shl", "lshr", "ashr", "and",
            "or", "xor", "extractelement", "insertelement", "shufflevector",
            "extractvalue", "insertvalue", "getelementptr", "trunc", "zext",
            "sext", "fptrunc", "fpext", "fptoui", "fptosi", "uitofp",
            "sitofp", "ptrtoint", "inttoptr", "bitcast", "addrspacecast",
            "icmp", "fcmp", "phi", "select");

    /**
     * Verify that every PHI in the body carries exactly the incoming blocks printed in
     * its block's {@code ; preds =} comment (as a multiset, so duplicated predecessors
     * from multiple edges must appear multiple times). This is the oracle for
     * backedge/predecessor alignment: a PHI missing a latch incoming or carrying a
     * stale one is a broken commit, even when the IR still verifies.
     */
    public static void assertCompletePhis(IRBody body, String context) {
        validateCompletePhis(body.lines(), context);
    }

    /** Verify that no live poison value or incomplete PHI reaches the final IR. */
    public static void assertStructuralSoundness(IRBody body, String context) {
        Objects.requireNonNull(body);
        Objects.requireNonNull(context);
        assertNoLivePoison(body, context);
        assertCompletePhis(body, context);
    }

    /** Self-test of the PHI-completeness parser; call once per test main. */
    public static void assertPhiParserContracts() {
        List<String> complete = List.of(
                "merge: ; preds = %left, %left, %\"right path\"",
                "%value = phi i32 [ 1, %left ], [ 2, %\"right path\" ], [ 3, %left ]");
        validateCompletePhis(complete, "complete synthetic PHI");

        List<String> duplicateOneMissingOne = List.of(
                "merge: ; preds = %left, %left, %\"right path\"",
                "%value = phi i32 [ 1, %left ], [ 2, %left ], [ 3, %left ]");
        boolean rejected = false;
        try {
            validateCompletePhis(duplicateOneMissingOne,
                    "duplicate-one-missing-one synthetic PHI");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "PHI parser must reject equal-size predecessor multisets with a missing block");

        List<String> missingPrintedPredecessors = List.of(
                "with_preds: ; preds = %left, %right",
                "%good = phi i32 [ 1, %left ], [ 2, %right ]",
                "plain:",
                "%stale = phi i32 [ 1, %left ], [ 2, %right ]");
        rejected = false;
        try {
            validateCompletePhis(missingPrintedPredecessors,
                    "new-block predecessor reset synthetic PHI");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "PHI parser must reset predecessor information at every block label");
    }

    /** Self-test of the structural-soundness parsers; call once per test main. */
    public static void assertStructuralParserContracts() {
        assertPhiParserContracts();
        assertCrossProcessNormalizerContracts();

        List<String> deadConstantBranch = List.of(
                "entry:",
                "br i1 false, label %dead, label %live",
                "dead: ; preds = %entry",
                "call void @consume(ptr poison)",
                "ret void",
                "live: ; preds = %entry",
                "ret void");
        validateNoLivePoison(deadConstantBranch, "dead constant branch", "synthetic");

        List<String> poisonUsedOnlyInDeadBlock = List.of(
                "entry:",
                "%unused = getelementptr i8, ptr poison, i64 1",
                "br i1 false, label %dead, label %live",
                "dead: ; preds = %entry",
                "call void @consume(ptr %unused)",
                "ret void",
                "live: ; preds = %entry",
                "ret void");
        validateNoLivePoison(poisonUsedOnlyInDeadBlock,
                "poison used only in dead block", "synthetic");

        List<String> deadPhiIncoming = List.of(
                "entry:",
                "br i1 false, label %dead, label %live",
                "dead: ; preds = %entry",
                "br label %merge",
                "live: ; preds = %entry",
                "br label %merge",
                "merge: ; preds = %dead, %live",
                "%value = phi i32 [ poison, %dead ], [ 7, %live ]",
                "ret i32 %value");
        validateNoLivePoison(deadPhiIncoming, "dead PHI incoming", "synthetic");

        List<String> constantSwitch = List.of(
                "entry:",
                "switch i32 7, label %default [",
                "i32 7, label %selected",
                "i32 9, label %dead",
                "]",
                "dead: ; preds = %entry",
                "call void @consume(ptr poison)",
                "ret void",
                "selected: ; preds = %entry",
                "ret void",
                "default: ; preds = %entry",
                "ret void");
        validateNoLivePoison(constantSwitch, "constant switch", "synthetic");

        List<String> selectedPoison = List.of(
                "entry:",
                "br i1 true, label %live, label %clean",
                "clean: ; preds = %entry",
                "ret void",
                "live: ; preds = %entry",
                "call void @consume(ptr poison)",
                "ret void");
        boolean rejected = false;
        try {
            validateNoLivePoison(selectedPoison,
                    "selected constant branch", "synthetic");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "poison parser must reject poison in a selected constant successor");

        List<String> selectedPhiIncoming = List.of(
                "entry:",
                "br i1 true, label %live, label %dead",
                "dead: ; preds = %entry",
                "br label %merge",
                "live: ; preds = %entry",
                "br label %merge",
                "merge: ; preds = %dead, %live",
                "%value = phi i32 [ 7, %dead ], [ poison, %live ]",
                "ret i32 %value");
        rejected = false;
        try {
            validateNoLivePoison(selectedPhiIncoming,
                    "selected PHI incoming", "synthetic");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "poison parser must reject poison on a reachable PHI edge");

        List<String> reachablePoison = List.of(
                "entry:",
                "call void @consume(ptr poison)",
                "ret void");
        rejected = false;
        try {
            validateNoLivePoison(reachablePoison, "reachable poison", "synthetic");
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        Asserts.assertTrue(rejected,
                "poison parser must reject poison in an ordinary reachable block");

        List<String> unusedCallResult = List.of(
                "entry:",
                "%result = call i32 @consume(i32 poison)",
                "ret void");
        boolean rejectedUnusedCall = rejectsLivePoison(
                unusedCallResult, "unused side-effecting call result");

        List<String> backwardBackedgeUse = List.of(
                "entry:",
                "br label %header",
                "header: ; preds = %entry, %backedge",
                "%value = phi i32 [ 0, %entry ], [ %next, %backedge ]",
                "br i1 %again, label %backedge, label %exit",
                "backedge: ; preds = %header",
                "%next = add i32 poison, 1",
                "br label %header",
                "exit: ; preds = %header",
                "ret i32 %value");
        boolean rejectedBackwardUse = rejectsLivePoison(
                backwardBackedgeUse, "textually backward backedge use");

        Asserts.assertTrue(rejectedUnusedCall && rejectedBackwardUse,
                "poison parser must reject unused side-effecting results and "
                        + "textually backward uses: unusedCall="
                        + rejectedUnusedCall + ", backwardUse="
                        + rejectedBackwardUse);

        List<String> usedFrozenPoison = List.of(
                "entry:",
                "%value = freeze i32 poison",
                "ret i32 %value");
        validateNoLivePoison(
                usedFrozenPoison, "used frozen poison", "synthetic");
    }

    private static void assertCrossProcessNormalizerContracts() {
        List<String> runtimeFunctionsFirst = List.of(
                "define void @\"pkg_Test_work()V.111111.root\"() {",
                "call void @\"pkg_Test_helper(I)V.222222\"(i32 1)",
                "}");
        List<String> runtimeFunctionsSecond = List.of(
                "define void @\"pkg_Test_work()V.333333.root\"() {",
                "call void @\"pkg_Test_helper(I)V.444444\"(i32 1)",
                "}");
        Asserts.assertEquals(IRBody.crossProcessExactLines(runtimeFunctionsFirst),
                IRBody.crossProcessExactLines(runtimeFunctionsSecond),
                "cross-process normalizer handles runtime Java method identities");

        String ordinarySuffixes = "call void @ordinary.1(i32 123456)";
        Asserts.assertEquals(normalizeRuntimeFunctionSymbols(ordinarySuffixes),
                ordinarySuffixes,
                "runtime symbol normalizer preserves ordinary LLVM suffixes and constants");

        List<String> first = List.of(
                "%object = call \"java-klass\"=\"101010101010\" ptr "
                        + "inttoptr (i64 101010101010 to ptr)",
                "call void @poll() [ \"deopt\"(i64 0, i32 7, i32 7, "
                        + "i64 393233, i64 202020202020, i64 0, i32 11, i32 11) ]");
        List<String> second = List.of(
                "%object = call \"java-klass\"=\"303030303030\" ptr "
                        + "inttoptr (i64 303030303030 to ptr)",
                "call void @poll() [ \"deopt\"(i64 0, i32 7, i32 7, "
                        + "i64 393233, i64 404040404040, i64 0, i32 11, i32 11) ]");
        Asserts.assertEquals(IRBody.crossProcessExactLines(first),
                IRBody.crossProcessExactLines(second),
                "cross-process normalizer handles exact klass and inline-method positions");

        List<String> unrelatedFirst = List.of("call void @use(i64 505050505050)");
        List<String> unrelatedSecond = List.of("call void @use(i64 606060606060)");
        Asserts.assertNotEquals(IRBody.crossProcessExactLines(unrelatedFirst),
                IRBody.crossProcessExactLines(unrelatedSecond),
                "cross-process normalizer preserves unrelated integer constants");

        List<String> collisionFirst = List.of(
                "call void @ordinary(i64 393233, i64 707070707070, "
                        + "i64 0, i32 13, i32 13)");
        List<String> collisionSecond = List.of(
                "call void @ordinary(i64 393233, i64 808080808080, "
                        + "i64 0, i32 13, i32 13)");
        Asserts.assertNotEquals(IRBody.crossProcessExactLines(collisionFirst),
                IRBody.crossProcessExactLines(collisionSecond),
                "cross-process normalizer requires exact deopt-bundle context");
    }

    private static boolean rejectsLivePoison(List<String> lines, String context) {
        try {
            validateNoLivePoison(lines, context, "synthetic");
            return false;
        } catch (IllegalStateException expected) {
            return true;
        }
    }

    private static void validateCompletePhis(List<String> lines, String context) {
        Map<String, Integer> currentPredecessors = null;
        String currentBlock = null;
        for (String line : lines) {
            Matcher anyBlock = BLOCK_LABEL.matcher(line);
            if (anyBlock.matches()) {
                currentBlock = anyBlock.group(1);
                currentPredecessors = null;
            }
            Matcher block = BLOCK_WITH_PREDECESSORS.matcher(line);
            if (block.matches()) {
                currentPredecessors = blockReferences(block.group(2));
                continue;
            }
            if (!line.contains(" = phi ")) {
                continue;
            }
            if (currentPredecessors == null) {
                throw new IllegalStateException(context
                        + ": PHI outside a block with printed predecessors: " + line);
            }
            Map<String, Integer> incomingBlocks = new HashMap<>();
            Matcher incoming = PHI_INCOMING_BLOCK.matcher(line);
            while (incoming.find()) {
                incomingBlocks.merge(incoming.group(1), 1, Integer::sum);
            }
            if (!incomingBlocks.equals(currentPredecessors)) {
                throw new IllegalStateException(context + ": PHI in block " + currentBlock
                        + " has incoming predecessors " + incomingBlocks
                        + ", expected " + currentPredecessors + ": " + line);
            }
        }
    }

    private static Map<String, Integer> blockReferences(String text) {
        Map<String, Integer> result = new HashMap<>();
        Matcher reference = LLVM_BLOCK_REFERENCE.matcher(text);
        while (reference.find()) {
            result.merge(reference.group(1), 1, Integer::sum);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("Printed predecessor list has no block reference: "
                    + text);
        }
        return result;
    }

    private static void assertNoLivePoison(IRBody body, String context) {
        validateNoLivePoison(body.lines(), context, body.methodId().toString());
    }

    private static void validateNoLivePoison(List<String> lines, String context,
                                             String method) {
        StructuralReachability reachability = structuralReachability(lines);
        for (int i = 0; i < lines.size(); i++) {
            if (!reachability.lines().contains(i)) {
                continue;
            }
            String instruction = withoutInlineComment(lines.get(i));
            String reachableInstruction = withoutUnreachablePhiIncoming(
                    instruction, reachability.blocks());
            if (!containsPoison(reachableInstruction)) {
                continue;
            }
            if (isFreezeInstruction(instruction)) {
                continue;
            }
            Matcher assignment = ASSIGNED_INSTRUCTION.matcher(instruction);
            if (!assignment.find()
                    || !isPurePoisonInstruction(instruction)
                    || isUsedInReachableInstructions(lines, assignment.group(1),
                            i, reachability)) {
                throw new IllegalStateException(context + ": live poison in "
                        + method + ": " + instruction);
            }
        }
    }

    private static StructuralReachability structuralReachability(List<String> lines) {
        LinkedHashMap<String, List<Integer>> blocks = new LinkedHashMap<>();
        String currentBlock = null;
        boolean unlabeledInstruction = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher label = BLOCK_LABEL.matcher(lines.get(i));
            if (label.matches()) {
                currentBlock = label.group(1);
                if (blocks.putIfAbsent(currentBlock, new ArrayList<>()) != null) {
                    throw new IllegalStateException(
                            "Duplicate block label in structural parser: " + currentBlock);
                }
            }
            if (currentBlock != null) {
                blocks.get(currentBlock).add(i);
            } else {
                String line = fold(withoutInlineComment(lines.get(i)));
                unlabeledInstruction |= !line.isEmpty()
                        && !line.startsWith("define ") && !line.equals("{");
            }
        }
        if (blocks.isEmpty() || unlabeledInstruction) {
            return allReachable(lines, blocks.keySet());
        }

        HashSet<String> reachableBlocks = new HashSet<>();
        ArrayList<String> worklist = new ArrayList<>();
        worklist.add(blocks.keySet().iterator().next());
        for (int next = 0; next < worklist.size(); next++) {
            String block = worklist.get(next);
            if (!reachableBlocks.add(block)) {
                continue;
            }
            for (String successor : structuralSuccessors(
                    lines, blocks.get(block), blocks.keySet())) {
                if (blocks.containsKey(successor) && !reachableBlocks.contains(successor)) {
                    worklist.add(successor);
                }
            }
        }

        HashSet<Integer> reachableLines = new HashSet<>();
        for (String block : reachableBlocks) {
            reachableLines.addAll(blocks.get(block));
        }
        return new StructuralReachability(reachableLines, reachableBlocks);
    }

    private static StructuralReachability allReachable(
            List<String> lines, Set<String> blocks) {
        HashSet<Integer> allLines = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            allLines.add(i);
        }
        return new StructuralReachability(allLines, new HashSet<>(blocks));
    }

    private static Set<String> structuralSuccessors(List<String> lines,
                                                    List<Integer> blockLines,
                                                    Set<String> allBlocks) {
        int terminatorAt = -1;
        for (int i = 0; i < blockLines.size(); i++) {
            String line = fold(withoutInlineComment(lines.get(blockLines.get(i))));
            if (TERMINATOR_START.matcher(line).find()) {
                terminatorAt = i;
            }
        }
        if (terminatorAt < 0) {
            return allBlocks;
        }

        String terminator = fold(blockLines.subList(terminatorAt, blockLines.size())
                .stream().map(lines::get)
                .map(PEATestUtils::withoutInlineComment)
                .collect(Collectors.joining("\n")));

        Matcher branch = CONSTANT_BRANCH.matcher(terminator);
        if (branch.find()) {
            String selected = branch.group(
                    Boolean.parseBoolean(branch.group(1)) ? 2 : 3);
            return allBlocks.contains(selected) ? Set.of(selected) : allBlocks;
        }

        Matcher switchHeader = SWITCH_HEADER.matcher(terminator);
        if (switchHeader.find()) {
            String selectedValue = normalizeIntegerConstant(switchHeader.group(1));
            String selectedBlock = switchHeader.group(2);
            Matcher switchCase = SWITCH_CASE.matcher(
                    terminator.substring(switchHeader.end()));
            while (switchCase.find()) {
                if (normalizeIntegerConstant(switchCase.group(1)).equals(selectedValue)) {
                    selectedBlock = switchCase.group(2);
                    break;
                }
            }
            return allBlocks.contains(selectedBlock)
                    ? Set.of(selectedBlock) : allBlocks;
        }

        HashSet<String> successors = new HashSet<>();
        Matcher reference = LLVM_LABEL_REFERENCE.matcher(terminator);
        while (reference.find()) {
            successors.add(reference.group(1));
        }
        if (!allBlocks.containsAll(successors)) {
            return allBlocks;
        }
        return successors;
    }

    private static String withoutUnreachablePhiIncoming(
            String instruction, Set<String> reachableBlocks) {
        if (!instruction.contains(" = phi ") || !containsPoison(instruction)) {
            return instruction;
        }
        ArrayList<int[]> unreachableIncoming = new ArrayList<>();
        Matcher incoming = PHI_INCOMING_BLOCK.matcher(instruction);
        while (incoming.find()) {
            if (reachableBlocks.contains(incoming.group(1))) {
                continue;
            }
            int close = incoming.end() - 1;
            int open = matchingOpeningSquare(instruction, close);
            if (open < 0) {
                return instruction;
            }
            unreachableIncoming.add(new int[] {open, close + 1});
        }
        StringBuilder reachable = new StringBuilder(instruction);
        for (int i = unreachableIncoming.size() - 1; i >= 0; i--) {
            int[] range = unreachableIncoming.get(i);
            reachable.replace(range[0], range[1], "[ 0, %unreachable ]");
        }
        return reachable.toString();
    }

    private static int matchingOpeningSquare(String text, int close) {
        if (close < 0 || close >= text.length() || text.charAt(close) != ']') {
            return -1;
        }
        ArrayList<Integer> openings = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i <= close; i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == '[') {
                openings.add(i);
            } else if (ch == ']') {
                if (openings.isEmpty()) {
                    return -1;
                }
                int open = openings.remove(openings.size() - 1);
                if (i == close) {
                    return open;
                }
            }
        }
        return -1;
    }

    private static String normalizeIntegerConstant(String value) {
        return switch (value) {
            case "true" -> "1";
            case "false" -> "0";
            default -> value;
        };
    }

    private record StructuralReachability(Set<Integer> lines, Set<String> blocks) {}

    private static boolean containsPoison(String line) {
        return POISON_TOKEN.matcher(withoutQuotedText(line)).find();
    }

    private static boolean isPurePoisonInstruction(String instruction) {
        Matcher opcode = ASSIGNED_OPCODE.matcher(fold(instruction));
        return opcode.find() && PURE_POISON_OPCODES.contains(opcode.group(1));
    }

    private static boolean isFreezeInstruction(String instruction) {
        Matcher opcode = ASSIGNED_OPCODE.matcher(fold(instruction));
        return opcode.find() && opcode.group(1).equals("freeze");
    }

    private static boolean isUsedInReachableInstructions(
            List<String> lines, String value, int definition,
            StructuralReachability reachability) {
        Pattern exactLocalUse = Pattern.compile(
                "(?<![-A-Za-z$._0-9])" + Pattern.quote(value)
                        + "(?![-A-Za-z$._0-9])");
        for (int i = 0; i < lines.size(); i++) {
            if (!reachability.lines().contains(i)) {
                continue;
            }
            String instruction = withoutUnreachablePhiIncoming(
                    withoutInlineComment(lines.get(i)), reachability.blocks());
            if (i == definition) {
                Matcher assignment = ASSIGNED_INSTRUCTION.matcher(instruction);
                if (!assignment.find()) {
                    return true;
                }
                instruction = instruction.substring(assignment.end());
            }
            if (exactLocalUse.matcher(instruction).find()) {
                return true;
            }
        }
        return false;
    }

    private static String withoutInlineComment(String line) {
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == ';') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String withoutQuotedText(String line) {
        StringBuilder result = new StringBuilder(line.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quoted) {
                result.append(' ');
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
            } else {
                result.append(current);
                if (current == '"') {
                    quoted = true;
                }
            }
        }
        return result.toString();
    }

    private static void rejectManagedVMFlag(String flag) {
        if (flag.startsWith("@")) {
            throw new IllegalArgumentException("Caller may not use an argument file " + flag);
        }
        if (CONFIGURED_TARGETS_PROPERTY.equals(systemPropertyName(flag))) {
            throw new IllegalArgumentException(
                    "Caller may not override configured PEA targets " + flag);
        }
        String optionName = vmOptionName(flag);
        if (optionName != null && MANAGED_VM_OPTIONS.contains(optionName)) {
            throw new IllegalArgumentException("Caller may not override managed VM flag " + flag);
        }
    }

    private static void rejectRawExecutionMode(String flag) {
        if ("-Xcomp".equals(flag) || "-Xint".equals(flag) || "-Xmixed".equals(flag)) {
            throw new IllegalArgumentException(
                    "Caller may not override typed execution mode " + flag);
        }
    }

    private static String systemPropertyName(String flag) {
        if (!flag.startsWith("-D") || flag.length() == 2) {
            return null;
        }
        int equals = flag.indexOf('=', 2);
        return equals < 0 ? flag.substring(2) : flag.substring(2, equals);
    }

    private static String vmOptionName(String flag) {
        if (!flag.startsWith("-XX:") || flag.length() == 4) {
            return null;
        }
        String option = flag.substring(4);
        if (option.charAt(0) == '+' || option.charAt(0) == '-') {
            option = option.substring(1);
        }
        int equals = option.indexOf('=');
        if (equals >= 0) {
            option = option.substring(0, equals);
        }
        if (option.endsWith(":")) {
            option = option.substring(0, option.length() - 1);
        }
        return option;
    }

    private static void rejectManagedLLVMOption(String option) {
        String trimmed = option.trim();
        if (trimmed.isEmpty() || trimmed.contains(" ") || trimmed.contains("\t")) {
            throw new IllegalArgumentException("LLVM options must be individual non-empty arguments: "
                    + option);
        }
        String optionName = trimmed.replaceFirst("^-+", "");
        int equals = optionName.indexOf('=');
        if (equals >= 0) {
            optionName = optionName.substring(0, equals);
        }
        if (MANAGED_LLVM_OPTIONS.contains(optionName)) {
            throw new IllegalArgumentException("Caller may not override managed PEA option " + option);
        }
    }

    private static String compileCommandPattern(Executable executable) {
        Objects.requireNonNull(executable);
        Class<?> returnType = executable instanceof Method method
                ? method.getReturnType() : void.class;
        String descriptor = MethodType.methodType(returnType, executable.getParameterTypes())
                .descriptorString();
        String name = executable instanceof Constructor<?> ? "<init>" : executable.getName();
        return executable.getDeclaringClass().getName() + "::" + name + descriptor;
    }

    private static void addUnique(List<String> patterns, String pattern, String command) {
        if (patterns.contains(pattern)) {
            throw new IllegalArgumentException("Duplicate " + command + " method " + pattern);
        }
        patterns.add(pattern);
    }

    private static void rejectConflictingInlineCommand(
            String pattern, List<String> conflicting,
            String existingCommand, String requestedCommand) {
        if (conflicting.contains(pattern)) {
            throw new IllegalArgumentException("Conflicting " + existingCommand + "/"
                    + requestedCommand + " method " + pattern);
        }
    }

    private static String definedFunctionName(String line) {
        if (!line.startsWith("define ")) {
            return null;
        }
        int at = line.indexOf('@');
        if (at < 0) {
            return null;
        }
        return parseLLVMOperand(line, at).value;
    }

    private static String decodeLLVMOperand(String operand) {
        ParsedOperand parsed = parseLLVMOperand(operand, 0);
        if (parsed.end != operand.length()) {
            throw new IllegalArgumentException("Trailing characters in LLVM operand " + operand);
        }
        return parsed.value;
    }

    private static ParsedOperand parseLLVMOperand(String text, int at) {
        if (at >= text.length() || text.charAt(at) != '@') {
            throw new IllegalArgumentException("Expected LLVM global operand at " + at + ": " + text);
        }
        return parseLLVMNamedOperand(text, at);
    }

    private static ParsedOperand parseLLVMNamedOperand(String text, int at) {
        if (at >= text.length()
                || text.charAt(at) != '@' && text.charAt(at) != '%') {
            throw new IllegalArgumentException("Expected LLVM named operand at "
                    + at + ": " + text);
        }
        int index = at + 1;
        if (index < text.length() && text.charAt(index) == '"') {
            index++;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < text.length() && text.charAt(index) != '"') {
                char ch = text.charAt(index++);
                if (ch == '\\') {
                    if (index + 1 >= text.length()
                            || !isHex(text.charAt(index)) || !isHex(text.charAt(index + 1))) {
                        throw new IllegalArgumentException("Malformed LLVM quoted operand: " + text);
                    }
                    bytes.write(Integer.parseInt(text.substring(index, index + 2), 16));
                    index += 2;
                } else {
                    byte[] encoded = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
                    bytes.writeBytes(encoded);
                }
            }
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unterminated LLVM quoted operand: " + text);
            }
            return new ParsedOperand(bytes.toString(StandardCharsets.UTF_8), index + 1);
        }
        int start = index;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (!(Character.isLetterOrDigit(ch) || ch == '-' || ch == '$'
                    || ch == '.' || ch == '_')) {
                break;
            }
            index++;
        }
        if (index == start) {
            throw new IllegalArgumentException("Empty LLVM named operand: " + text);
        }
        return new ParsedOperand(text.substring(start, index), index);
    }

    private static final class ParsedOperand {
        private final String value;
        private final int end;

        private ParsedOperand(String value, int end) {
            this.value = value;
            this.end = end;
        }
    }

    private static boolean isHex(char ch) {
        return ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F';
    }

    private static int braceDelta(String line) {
        int delta = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (!quoted && ch == ';') {
                break;
            }
            if (ch == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted && ch == '\\') {
                i = Math.min(i + 2, line.length() - 1);
                continue;
            }
            if (!quoted && ch == '{') {
                delta++;
            } else if (!quoted && ch == '}') {
                delta--;
            }
        }
        return delta;
    }

    private static String fold(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean quoted = false;
        boolean pendingWhitespace = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (quoted) {
                result.append(ch);
                if (ch == '\\' && i + 1 < value.length()) {
                    result.append(value.charAt(++i));
                } else if (ch == '"') {
                    quoted = false;
                }
                continue;
            }
            if (ch == '"') {
                if (pendingWhitespace && !result.isEmpty()) {
                    result.append(' ');
                }
                pendingWhitespace = false;
                quoted = true;
                result.append(ch);
            } else if (Character.isWhitespace(ch)) {
                pendingWhitespace = true;
            } else {
                if (pendingWhitespace && !result.isEmpty()) {
                    result.append(' ');
                }
                pendingWhitespace = false;
                result.append(ch);
            }
        }
        return result.toString();
    }

    private static String cleanInstructionLine(String line) {
        return fold(stripLLVMComment(line));
    }

    private static String stripLLVMComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '\\') {
                    i = Math.min(i + 2, line.length() - 1);
                } else if (ch == '"') {
                    quoted = false;
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ';') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static List<String> splitLines(String text) {
        return Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    private static IllegalStateException malformed(MethodId method, String detail) {
        return new IllegalStateException("Malformed PEA transcript for " + method + ": " + detail);
    }

    private static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

}
