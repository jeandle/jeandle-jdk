# Clone, copy and related intrinsic microbenchmarks

These JMH benchmarks cover the intrinsics added or directly affected by the
paired clone/copy PRs, and compare Jeandle and C2 on the same release JDK. Build
Jeandle against **Release LLVM** before measuring. A fastdebug JDK or Debug LLVM
is suitable for correctness investigation, but cannot provide these performance
results.

Copy and allocation methods return their results to JMH; hash and
MethodHandle methods return computed values. Writeback uses an observable
cache-management sequence. Setup checks deterministic inputs, result identity,
runtime class, lengths, element/field values, shallow reference identity and
null padding where applicable. Instance-clone setup also verifies inherited
field counts. Reflection and correctness checks run outside measurement.
Methods report one operation per invocation; controls include their documented
source construction, allocation or Java dispatch work.

## Coverage

| Class | Purpose | Methods | Default cases |
| --- | --- | ---: | ---: |
| `JeandleInstanceClone` | Empty instances; 1, 8, 9, and 32 primitive/reference fields; mixed and inherited layouts with 8, 9, and 32 fields. The eight-field primitive layout contains all eight primitive types. | 15 | 15 |
| `JeandleArrayClone` | All eight primitive array kinds and shallow `Object[]` clone at lengths 0, 1, 7, 8, 9, 64, and 1024. Matched `int[]`/`Object[]` allocation and allocation-plus-arraycopy controls. | 13 | 91 |
| `JeandleArrayCopyOf` | Explicit target-mirror overloads: three-argument `Arrays.copyOf` and four-argument `copyOfRange`. Same type, widening `String[] -> Object[]`, checked narrowing `Object[] -> String[]`, shrink/equal/grow, nonzero range starts, and null padding. Allocation and arraycopy controls. | 16 | 112 |
| `JeandleSmallArrayCopy` | Literal lengths 8 and 9, literal mirrors and bounds, a static final typed source, plus prepared state arrays with length guards and runtime-valued elements. | 18 | 18 |
| `JeandleDynamicArrayCopyOf` | A target mirror loaded from mutable state: `Object[]`, `String[]`, or interface-element `CharSequence[]`, at lengths 8, 64, and 1024. Kept separate from literal-mirror cases. | 2 | 18 |
| `JeandleArrayCopy` | Existing-destination `System.arraycopy`: all eight primitive kinds and references, offsets 0/0 and 1/3, compatible checked narrowing, and forward/backward int/reference overlap. | 24 | 168 |
| `JeandleReflectArray` | `Array.newInstance`: literal byte/Object mirrors and dynamic mirrors for every primitive kind, references and array components; matching bytecode allocation controls. | 5 | 96 |
| `JeandleUnsafeAllocate` | `Unsafe.allocateInstance`: literal/dynamic class mirrors and primitive/reference/mixed layouts, with constructor controls and zero-initialization checks. | 9 | 9 |
| `JeandleHashCode` | Object/identity hash fast and first-use paths, override/interface dispatch, super calls and null identity hashes. | 11 | 11 |
| `JeandleMethodHandleInvoke` | Constant/volatile `MethodHandle.invokeExact` sites exercising the invokeBasic protocol, with direct arithmetic controls. | 6 | 6 |
| Existing `JeandleArrayEquals` | Byte/char equality and early mismatch; the PR only reformats its lowering. | 8 | 40 |
| Existing `org.openjdk.bench.jdk.internal.misc.UnsafeWriteback` | One pre-sync/writeback/post-sync sequence; the PR migrates the corresponding test to the shared FileCheck helper. | 1 | 1 |

The overlap methods mutate their fixtures repeatedly during measurement;
setup validates and resets them, and source contents can become uniform after
repeated overlapping moves. They measure repeated copy operations, without an
extra timed reinitialization. After warmup, the reference variants can repeatedly
store identical references; they do not model a stream of newly created young
references or establish its GC-barrier cost.

### Intrinsic inventory

The inventory includes new IDs, directly modified existing lowering, shared
allocation/call protocols, and the intrinsics named by test-only changes.
It does not treat every user of the generic call emitter as a newly changed
intrinsic.

| Intrinsic ID | API / covered path | Benchmarks |
| --- | --- | --- |
| `_clone` | `Object.clone`, instance and all array kinds | `JeandleInstanceClone`, `JeandleArrayClone` |
| `_copyOf` | Reference `Arrays.copyOf` | `JeandleArrayCopyOf`, `JeandleSmallArrayCopy`, `JeandleDynamicArrayCopyOf` |
| `_copyOfRange` | Reference `Arrays.copyOfRange` | Same three copy classes |
| `_arraycopy` | `System.arraycopy`, existing/fresh destinations and overlap | `JeandleArrayCopy` and copy/clone controls |
| `_newArray` | Reflective `Array.newInstance` and the shared array allocation helper | `JeandleReflectArray` |
| `_allocateInstance` | Unsafe allocation through the shared instance helper | `JeandleUnsafeAllocate` |
| `_hashCode` | Object hash, including virtual/interface/super dispatch | `JeandleHashCode` |
| `_identityHashCode` | Identity hash, fresh/prehashed/null/overridden receivers | `JeandleHashCode` |
| `_invokeBasic` | Internal MethodHandle invocation reached through `invokeExact` | `JeandleMethodHandleInvoke` |
| `_equalsB`, `_equalsC` | Byte/char equality (format-only diff) | Existing `JeandleArrayEquals` |
| `_writeback0`, `_writebackPreSync0`, `_writebackPostSync0` | Cache-line writeback and synchronization (test helper migration) | Existing `UnsafeWriteback.writebackOneLine` |

The added classes provide 119 methods / 544 default parameter combinations;
including the reused equality and writeback classes gives 128 methods / 585
combinations. Discovery and setup validation must be rerun after changes. The
core clone/copy comparison below selects only its original 64 methods / 254
combinations. Internal `invokeBasic` use and other intended lowering paths must
be checked in compiler output; API invocation alone is not proof of a specific
intrinsic being selected.

Shape comparisons use `Object[]` destinations, while mirror comparisons use equal
lengths. This avoids multiplying every mirror by every shape. The core clone/copy group contains
64 benchmark methods and 254 default parameter combinations; run selected groups
for investigation. The defaults are three forks, five one-second warmup
iterations, and five one-second measurement iterations. A complete default run
takes over two hours per compiler before startup and compilation overhead.

The ordinary `size` parameters are runtime values and do **not** promise scalar
copy specialization. In `JeandleSmallArrayCopy`, static-final source variants
remain separate from prepared-source variants. The prepared arrays are created
in `@Setup`, held in volatile state fields, and never allocated or initialized
inside a measured method. Explicit source-length guards describe the eight/nine-element inputs without
creating virtual arrays. They do not guarantee that Jeandle propagates those
facts before copy specialization: the EA-off diagnostic currently retains bulk
copies for both lengths. Label the observed path when interpreting results.
Matching `arraycopyPreparedObjects8/9` and
`arraycopyRangePreparedObjects8/9` controls use the same guards and inputs.
All methods return their escaping destination to JMH. Prepared eight-element
copies should allocate one 88-byte destination, and nine-element copies one
96-byte destination with the documented uncompressed layout; check measured
allocation rates rather than assuming equal work.

The new benchmark classes disable `DoEscapeAnalysis`, `EliminateAllocations`
and `JeandleDoPEA` in their default fork arguments. Pass the same flags explicitly
for external JVMs, non-forked runs, and the reused equality/writeback classes.
Confirm the intended scalar or bulk path in IR after changing EA policy;
array-length information can otherwise disappear from the specialization stage.

## Compile directly with JMH 1.37

Run these commands in Bash from a workspace containing `jeandle-jdk` and
`jeandle-llvm`. The output directory is independent of the JDK build.

```bash
COPY_WORKSPACE="$PWD"
COPY_JDK="$COPY_WORKSPACE/jeandle-jdk/build/linux-x86_64-server-release/images/jdk"
COPY_JARS="$COPY_WORKSPACE/jeandle-jdk/build/jmh/jars"
COPY_SRC="$COPY_WORKSPACE/jeandle-jdk/test/micro/org/openjdk/bench/jeandle"
COPY_RUN=$(mktemp -d /tmp/jeandle-copy-jmh.XXXXXX)
mkdir -p "$COPY_RUN/classes" "$COPY_RUN/generated"
COPY_DEPS="$COPY_JARS/jmh-core-1.37.jar:$COPY_JARS/jmh-generator-annprocess-1.37.jar:$COPY_JARS/jopt-simple-5.0.4.jar:$COPY_JARS/commons-math3-3.6.1.jar"
"$COPY_JDK/bin/javac" -J-XX:-UseJeandleCompiler \
    --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
    -cp "$COPY_DEPS" -processorpath "$COPY_DEPS" \
    -d "$COPY_RUN/classes" -s "$COPY_RUN/generated" \
    "$COPY_SRC"/Jeandle{CopySupport,InstanceClone,ArrayClone,ArrayCopyOf,SmallArrayCopy,DynamicArrayCopyOf,ArrayCopy,ReflectArray,UnsafeAllocate,HashCode,MethodHandleInvoke,ArrayEquals}.java \
    "$COPY_WORKSPACE/jeandle-jdk/test/micro/org/openjdk/bench/jdk/internal/misc/UnsafeWriteback.java"
COPY_CP="$COPY_RUN/classes:$COPY_DEPS"
"$COPY_JDK/bin/java" -XX:-UseJeandleCompiler -cp "$COPY_CP" \
    org.openjdk.jmh.Main -l \
    'org.openjdk.bench.jeandle.Jeandle(InstanceClone|ArrayClone|ArrayCopyOf|SmallArrayCopy|DynamicArrayCopyOf|ArrayCopy|ReflectArray|UnsafeAllocate|HashCode|MethodHandleInvoke|ArrayEquals)|org.openjdk.bench.jdk.internal.misc.UnsafeWriteback'
```

For Unsafe benchmarks in an externally launched process or a non-forked
run, also pass `--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED` and
`--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED` directly to that JVM.
Normal JMH forks obtain them from the benchmark annotations.

The launcher uses C2; `-jvm` and `-jvmArgs` below explicitly select the JVM and
compiler for every measured fork. No jar packaging step is required.

## Matched compiler settings

Use the same CPU affinity/resources, heap, GC, reference representation,
card-mark policy, bulk-zeroing policy, and escape-analysis policy for each
comparison. The following flags were accepted by the release JDK, including the
intrinsic-disable names. Record actual values for the binary being tested.

```bash
COPY_COMMON=(
    -XX:+UnlockDiagnosticVMOptions
    -XX:-TieredCompilation
    -Xms1g -Xmx1g
    -XX:+UseG1GC
    -XX:-UseCompressedOops
    -XX:-UseCompressedClassPointers
    -XX:-DoEscapeAnalysis
    -XX:-EliminateAllocations
    -XX:-JeandleDoPEA
    -XX:+ReduceInitialCardMarks
    -XX:+ReduceBulkZeroing
)
"$COPY_JDK/bin/java" "${COPY_COMMON[@]}" -XX:+UseJeandleCompiler \
    -XX:+PrintFlagsFinal -version >"$COPY_RUN/flags-jeandle.txt" 2>&1
"$COPY_JDK/bin/java" "${COPY_COMMON[@]}" -XX:-UseJeandleCompiler \
    -XX:+PrintFlagsFinal -version >"$COPY_RUN/flags-c2.txt" 2>&1
"$COPY_JDK/bin/java" "${COPY_COMMON[@]}" -XX:+UseJeandleCompiler \
    -XX:DisableIntrinsic=_clone,_copyOf,_copyOfRange -version
```

`-XX:-UseJeandleCompiler` selects C2 explicitly. Disabling tiered compilation
alone does not select C2. The optional intrinsic-off run leaves
`System.arraycopy` enabled so it measures the runtime clone and Java library
copyOf/copyOfRange paths with the same arraycopy support.

For a separate escape-analysis-on comparison, enable `DoEscapeAnalysis`,
`EliminateAllocations` and `JeandleDoPEA` consistently in every mode; override
the annotation arguments as well. Keep those results separate from EA-off data.
For a card-mark comparison, replace `+ReduceInitialCardMarks` in every mode.
For Serial GC, replace `+UseG1GC` with `+UseSerialGC` in every mode. Keep each
configuration in a separate result group. Compressed-reference comparisons must
likewise use matching effective oop and klass encodings.

## Focused exploratory comparison

The following matrix has 12 cases per mode. With one fork and three seconds
each of warmup and measurement it is intended for a roughly ten-minute
exploration, depending on compilation and machine load. It is not a substitute
for the default forks or sufficient warmup when results remain unstable.
Run modes sequentially; concurrent benchmarks or builds can distort results.
Use `-prof gc` to report allocation rate and bytes per operation.

```bash
for COPY_MODE in jeandle c2; do
    case "$COPY_MODE" in
        jeandle) COPY_COMPILER="-XX:+UseJeandleCompiler" ;;
        c2) COPY_COMPILER="-XX:-UseJeandleCompiler" ;;
    esac
    "$COPY_JDK/bin/java" -XX:-UseJeandleCompiler -cp "$COPY_CP" \
        org.openjdk.jmh.Main \
        'JeandleSmallArrayCopy\.(copyOfPreparedObjects|arraycopyPreparedObjects|copyOfRangePreparedObjects)[89]$|JeandleInstanceClone\.cloneReference(8|9|32)$' \
        -jvm "$COPY_JDK/bin/java" -jvmArgs "${COPY_COMMON[*]} $COPY_COMPILER" \
        -f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc \
        -rf json -rff "$COPY_RUN/$COPY_MODE-boundary.json"

    "$COPY_JDK/bin/java" -XX:-UseJeandleCompiler -cp "$COPY_CP" \
        org.openjdk.jmh.Main 'JeandleArrayClone\.clone(Ints|Objects)$' -p size=1024 \
        -jvm "$COPY_JDK/bin/java" -jvmArgs "${COPY_COMMON[*]} $COPY_COMPILER" \
        -f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc \
        -rf json -rff "$COPY_RUN/$COPY_MODE-arrays.json"

    "$COPY_JDK/bin/java" -XX:-UseJeandleCompiler -cp "$COPY_CP" \
        org.openjdk.jmh.Main 'JeandleArrayCopyOf\.copyOfNarrowed$' -p size=8 \
        -jvm "$COPY_JDK/bin/java" -jvmArgs "${COPY_COMMON[*]} $COPY_COMPILER" \
        -f 1 -wi 3 -w 1s -i 3 -r 1s -prof gc \
        -rf json -rff "$COPY_RUN/$COPY_MODE-checked.json"
done
```

To add a Jeandle intrinsic-off mode, use the same commands and append
`-XX:DisableIntrinsic=_clone,_copyOf,_copyOfRange` to its fork arguments.
For the full matrix, use the expression from the listing command,
remove `-p size`, and retain the annotation defaults. Run individual classes
or methods when diagnosing a regression; for example,
`'JeandleArrayCopyOf\.copyOf(GrowObjects|RangePaddedObjects)$' -p size=0,1,8,9,1024`
covers padding without repeating the mirror matrix.
For matched prepared-source range copies and controls, select
`'JeandleSmallArrayCopy\.(copyOfRangePreparedObjects|arraycopyRangePreparedObjects)[89]$'`.
Array lengths outside the defaults, such as 65536, can also be selected with
`-p size=65536`.

## Through the JDK test harness

The release configuration must have JMH configured with `--with-jmh`; having
the jars available for direct compilation does not configure the make harness.
Use the keyword `VM_OPTIONS` inside `MICRO`:

```bash
make -C "$COPY_WORKSPACE/jeandle-jdk" test \
    CONF=linux-x86_64-server-release \
    TEST="micro:org.openjdk.bench.jeandle.JeandleSmallArrayCopy" \
    MICRO="FORK=3;WARMUP_ITER=5;WARMUP_TIME=1;ITER=5;TIME=1;RESULTS_FORMAT=json;VM_OPTIONS=${COPY_COMMON[*]} -XX:+UseJeandleCompiler"
```

Repeat with `-XX:-UseJeandleCompiler` for C2. Keep the other fork arguments
identical.

Save JDK/LLVM revisions and dirty diffs, build configuration and commands,
actual linked LLVM, Java version and effective flags, CPU/OS/affinity, JMH
version, raw JSON, confidence intervals, allocation rates, and compilation
timing. Use separate diagnostic runs with `PrintCompilation`, compilation
logs, or Jeandle IR dumps to establish that hot methods entered the requested
compiler and that the intended scalar or stub paths were selected. Confirm
warmup stability before presenting performance conclusions.

## External JVM measurements in a socket-restricted environment

Use one fresh external JVM per case, compiler and repetition with `-f 0`, and
pass all VM options directly to that process. Install generated compiler hints
explicitly, including Blackhole rules, because non-forked JMH cannot install
its usual fork arguments. Use the same Blackhole mode in every compiler.

```bash
{
    echo quiet
    echo inline,org/openjdk/jmh/infra/Blackhole.consume
    echo dontinline,org/openjdk/jmh/infra/Blackhole.consumeCPU
    echo dontinline,org/openjdk/jmh/infra/Blackhole.consumeFull
    cat "$COPY_RUN/classes/META-INF/CompilerHints"
} > "$COPY_RUN/compiler-hints.txt"
COPY_EXTERNAL=(
    -Djmh.blackhole.mode=FULL_DONTINLINE
    -DcompilerBlackholesEnabled=false
    "-XX:CompileCommandFile=$COPY_RUN/compiler-hints.txt"
)
```

`FULL_DONTINLINE` uses JMH's full Java Blackhole implementation with its body
kept out of line. This adds consumption overhead to the measured invocation;
retain allocation/direct-call controls and do not interpret tiny scores as an
isolated intrinsic instruction latency. This configuration also restores the
writeback benchmark's generated `DONT_INLINE` hint. The external-launch protocol
remains distinct from standard managed forks. Record commands, effective flags,
compiler evidence and allocation rates, and keep historical EA-on results in a
separate dataset.
