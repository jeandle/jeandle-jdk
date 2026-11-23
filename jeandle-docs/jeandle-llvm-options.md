# Jeandle-LLVM Options

These options are available in the `opt` tool when Jeandle-LLVM is built.

| Option | Description |
| --- | --- |
| ```--jeandle``` | Run the Jeandle pass pipeline. |
| ```--jeandle-vm-callback-log=<filename>``` | Load a VM callback log file and register replay callbacks. This allows Jeandle passes that depend on VM callbacks to run without the JVM. Entries are consumed sequentially during replay. |
| ```--jeandle-inline-callee-ir=<filename>``` | Supply callee LLVM IR for Jeandle VM callback replay. Used with `--jeandle-vm-callback-log` to restore callee definitions when replay executes a successful `GetInlineCalleeIR` callback. If omitted, the path is derived from the callback log as described below. |

## Usage Examples

Run the full Jeandle pipeline on dumped IR:

```
opt --jeandle -S input.ll -o output.ll
```

Run a specific Jeandle pass with a VM callback log:

```
opt -S -passes="type-check-elimination" \
    --jeandle-vm-callback-log=path/to/log.cblog \
    input.ll -o output.ll
```

Run the Jeandle pipeline with a VM callback log:

```
opt --jeandle --jeandle-vm-callback-log=path/to/log.cblog \
    -S input.ll -o output.ll
```

### Replaying Inlining with Callee IR

The callback log records the results of VM calls, but does not contain the LLVM IR
of methods requested for inlining. `--jeandle-inline-callee-ir=<filename>` supplies
the separate IR module containing those callee definitions, allowing inlining to
be replayed without the JVM. The option is only used when
`--jeandle-vm-callback-log` is also specified; it does not select an optimization
pass. Use `--jeandle` for the full pipeline, or `-passes="jeandle-inline-driver"`
to run the inlining driver alone with a matching callback log.

Record a compilation with `-XX:+JeandleRecordVMCallbacks` and `-XX:+JeandleDumpIR`
as described in the [Debugging Guide](debugging-guide.md#recording-vm-callbacks).
The inlining stage also dumps `MethodName_timestamp_inline_callees.ll` in
`JeandleDumpDirectory`. Use the input IR, callback log, and callee IR from the same
compilation so that the recorded method IDs match the callee definitions.

Specify the callee IR path explicitly when the file has been moved or renamed:

```bash
opt --jeandle \
    --jeandle-vm-callback-log=path/to/MethodName_timestamp.cblog \
    --jeandle-inline-callee-ir=path/to/callees.ll \
    -S path/to/MethodName_timestamp.ll -o output.ll
```

If `--jeandle-inline-callee-ir` is omitted, `opt` replaces the callback log's
`.cblog` suffix with `_inline_callees.ll`. For example, the following command uses
`path/to/MethodName_timestamp_inline_callees.ll`:

```bash
opt --jeandle \
    --jeandle-vm-callback-log=path/to/MethodName_timestamp.cblog \
    -S path/to/MethodName_timestamp.ll -o output.ll
```

An explicit path overrides this default. If the callback log's filename does not
end in `.cblog`, `--jeandle-inline-callee-ir` must be provided; otherwise `opt`
reports an error because it cannot derive the path.

The callee IR file is loaded on demand, only when replay executes a
`GetInlineCalleeIR` callback whose recorded result is `true`. If no such callback
is executed, the file does not need to exist. When it is needed, a missing or
invalid file, or a missing callee definition for the recorded method ID, causes
replay to fail.

### Null and array bounds checks

`-passes=jeandle-array-length-optimization` performs immutable array-length CSE
and safe loop motion. `-passes=jeandle-check-optimization` runs bounded standard
LLVM scalar and loop check transforms, then lowers widenable conditions and
restores canonical loop form. Both operate on Java method compilation modules.
The JIT schedules them before safepoint strip mining, independently of PEA.

`-jeandle-optimize-checks=false` disables the additional loop/widening transforms;
scalar cleanup and lowering of temporary widenable conditions still run. The
expensive stage is capped at 256 guards and 10000 instructions per function;
LLVM profitability limits also constrain loop cloning. Use
`-pass-remarks-missed=jeandle-check-optimization` to diagnose the method budget.


Cold null and bounds checks may use LLVM widenable branches. A real check
failure retains its original exception reason; a failure introduced by widening
uses `predicate / reinterpret` and resumes at the original bytecode state.
After a predicate failure, subsequent compilation of that method keeps ordinary
checks. Widening requires trap profiling and an enabled interpreter; hot throws
and `-XX:-ProfileTraps` retain ordinary checks. Non-speculative elimination and
array-length folding remain available in those configurations.

`-passes=jeandle-affine-check-versioning` complements the standard transforms
for affine checks whose index stride differs from the loop controller stride.
It requires an innermost canonical loop and a dominating array length. It checks
controller wrap and index endpoints independently in a wider integer, then
selects a fast loop or the original checked loop. Failed guards preserve the
original exceptions and partial effects without new deoptimization states.
The pass versions at most four loops per function, each at most 200 instructions
and eight candidate checks. Both versions are marked to prevent repeated cloning.
Use `-jeandle-affine-check-versioning=false` to disable this complementary stage.
