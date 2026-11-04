# Jeandle-LLVM Options

These options are available in the `opt` tool when Jeandle-LLVM is built.

| Option | Description |
| --- | --- |
| ```--jeandle``` | Run the Jeandle pass pipeline. |
| ```--jeandle-vm-callback-log=<filename>``` | Load a VM callback log file and register replay callbacks. This allows Jeandle passes that depend on VM callbacks to run without the JVM. Entries are consumed sequentially during replay. |
| ```--jeandle-inline-callee-ir=<filename>``` | Load the inline callee IR module used by VM callback replay. Only required when replay actually executes a successful `GetInlineCalleeIR` callback. If unset, the filename is derived from the VM callback log path (see below). |

## `--jeandle-inline-callee-ir`

When Jeandle compiles a method, it may inline callees that live in other compilation
units, and it records their IR through the `GetInlineCalleeIR` VM callback. Replaying a
recorded compilation outside the JVM therefore needs both pieces of input:

1. the callback log (`--jeandle-vm-callback-log`), which holds the recorded callback
   sequence, and
2. the inline callee IR module (`--jeandle-inline-callee-ir`), which holds the IR of the
   callees that the recorded sequence refers to.

### Value

A path to a textual LLVM IR (`.ll`) file that defines the inline callee bodies. The file
is loaded with `loadAndRegisterVMCallbackLog` together with the callback log.

### Default path

If `--jeandle-inline-callee-ir` is omitted, `opt` derives the path from the callback log
filename: it must end with `.cblog`, and the suffix is replaced by `_inline_callees.ll`.
For example:

```
--jeandle-vm-callback-log=dump.mycblog    -> dump.my_inline_callees.ll
--jeandle-vm-callback-log=logs/a.cblog    -> logs/a_inline_callees.ll
```

If the callback log does not end with `.cblog` and `--jeandle-inline-callee-ir` is not
given, `opt` exits with an error:

```
error loading inline callee IR replay module: -jeandle-vm-callback-log must end with
.cblog when -jeandle-inline-callee-ir is not specified
```

### When it is required

The inline callee IR file is only needed if the replay actually reaches a successful
`GetInlineCalleeIR` callback. If the recorded pipeline never inlines a cross-module
callee, the option can be left out entirely. Passing an explicit path that does not
exist is an error.

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

Run a pass that inlines cross-module callees, supplying the callee IR explicitly:

```
opt -S -passes="jeandle<O3>" \
    --jeandle-vm-callback-log=path/to/log.cblog \
    --jeandle-inline-callee-ir=path/to/log_inline_callees.ll \
    input.ll -o output.ll
```

Rely on the derived default path instead (requires a `.cblog` extension):

```
opt -S -passes="jeandle<O3>" \
    --jeandle-vm-callback-log=path/to/log.cblog \
    input.ll -o output.ll
# reads path/to/log_inline_callees.ll
```
