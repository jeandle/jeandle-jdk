# Jeandle-LLVM Options

These options are available in the `opt` tool when Jeandle-LLVM is built.

| Option | Description |
| --- | --- |
| ```--jeandle``` | Run the Jeandle pass pipeline. |
| ```--jeandle-vm-callback-log=<filename>``` | Load a VM callback log file and register replay callbacks. This allows Jeandle passes that depend on VM callbacks to run without the JVM. During replay, each callback result is resolved by looking up the callback kind and its arguments in the log, so entries may be queried repeatedly and in any order. |
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

## VM Callback Replay Semantics

A callback log stores entries of the form `callback(arguments) = result`;
it is a set of recorded query results, not a script consumed from start to
end. When a pass executes a VM callback during replay, Jeandle builds a key
from the callback kind and its arguments and looks the result up in the
loaded log:

- The same entry may be queried any number of times, and entries may be
  queried in any order. The replay sequence does not have to match the order
  in which the callbacks were recorded.
- If a callback is invoked with a key that has no corresponding entry in the
  log, replay fails with a fatal error (`VMCallbackLog missing entry for
  <callback>(<arguments>)`); no fallback value is substituted.
- When the log is loaded, an entry whose key and result repeat an earlier
  entry is accepted and deduplicated. If the same key appears with different
  results, loading fails with an error (`conflicting result for
  <callback>`), because such a callback would not be a pure query of its
  arguments.
- `GetNewStatepointID` is an id-allocation callback rather than a pure query,
  so it is never recorded in the log; replay allocates fresh synthetic ids
  for it instead of looking them up.
