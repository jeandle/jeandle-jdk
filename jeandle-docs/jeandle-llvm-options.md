# Jeandle-LLVM Options

These options are available in the `opt` tool when Jeandle-LLVM is built.

| Option | Description |
| --- | --- |
| ```--jeandle``` | Run the Jeandle pass pipeline. |
| ```--jeandle-vm-callback-log=<filename>``` | Load a VM callback log file and register replay callbacks. This allows Jeandle passes that depend on VM callbacks to run without the JVM. Entries are consumed sequentially during replay. The log path must end with `.cblog`. |
| ```--jeandle-inline-callee-ir=<filename>``` | Load the inline callee IR module used during Jeandle VM callback replay. When Jeandle passes replay a VM callback log without the JVM, they may need the IR of inlined callees to reconstruct call sites; this option points `opt` at that module. |

## `--jeandle-inline-callee-ir`

This option is only meaningful together with `--jeandle-vm-callback-log`: it supplies the inline callee IR module that Jeandle passes load during VM callback replay.

The value is a path to the IR file. When it is **not specified**, `opt` derives a default path from the callback log: if the log argument given to `--jeandle-vm-callback-log` ends with `.cblog`, the default is the same path with the extension replaced by `_inline_callees.ll` (e.g. `path/to/log.cblog` → `path/to/log_inline_callees.ll`). If you pass `--jeandle-vm-callback-log` without `--jeandle-inline-callee-ir` and the log path does not end with `.cblog`, `opt` errors out because it cannot derive a default.

The inline callee IR file is **required only if** the replay actually executes a successful `GetInlineCalleeIR` callback; otherwise the option (or its default) is ignored.

### Usage examples

Explicitly supply the inline callee IR during a Jeandle callback-log run:

```
opt -S -passes="type-check-elimination" \
    --jeandle-vm-callback-log=path/to/log.cblog \
    --jeandle-inline-callee-ir=path/to/inline_callees.ll \
    input.ll -o output.ll
```

Rely on the default path (`log.cblog` → `log_inline_callees.ll` placed next to the log):

```
opt --jeandle --jeandle-vm-callback-log=path/to/log.cblog \
    -S input.ll -o output.ll
```

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