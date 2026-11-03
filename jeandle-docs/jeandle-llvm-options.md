# Jeandle-LLVM Options

These options are available in the `opt` tool when Jeandle-LLVM is built.

| Option | Description |
| --- | --- |
| ```--jeandle``` | Run the Jeandle pass pipeline. |
| ```--jeandle-vm-callback-log=<filename>``` | Load a VM callback log file and register replay callbacks. This allows Jeandle passes that depend on VM callbacks to run without the JVM. Entries are consumed sequentially during replay. |

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