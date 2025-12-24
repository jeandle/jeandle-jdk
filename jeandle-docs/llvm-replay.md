# Replaying Jeandle Compilation Process Using Jeandle LLVM Tools

## Brief Introduction

The Jeandle compiler supports dumping LLVM IR and replaying the compilation process through middle-end optimization and backend machine code generation using Jeandle LLVM tools.

## Prerequisites:

You need to first obtain the source code and build the JDK following the [Getting-Started](https://github.com/jeandle/jeandle-jdk/blob/main/jeandle-docs/getting-started.md) documentation. You should ensure that both `jeandle-jdk` and `jeandle-llvm` are used throughout the replay process.

The replay process relies on the following Jeandle flags: `JeandleDumpIR`, `UseJeandleCompiler`, and `JeandleDumpDirectory`.

If you are unfamiliar with these flags, please refer to [jeandle-flags](https://github.com/jeandle/jeandle-jdk/blob/main/jeandle-docs/jeandle-flags.md).

## Detailed Usage

An example of Fibonacci is as follows:

```java
public class Main {

    public static int fibonacci(int n) {
        if (n == 0) {
            return 0;
        } else if (n == 1) {
            return 1;
        } else {
            return fibonacci(n - 1) + fibonacci(n - 2);
        }
    }

    public static void main(String[] args) {
        int num = 10;
        for (int i = 0; i < num; i++) {
            System.out.print(fibonacci(i) + " ");
        }
    }
}
```

By enabling `-XX:+JeandleDumpIR` and `-XX:JeandleDumpDirectory`, the compiler will dump the IR and store it in the specified directory.

### Compile and Dump IR

Run the following command:

```bash
javac Main.java
java -XX:-TieredCompilation -Xcomp \
     -XX:CompileCommand=compileonly,Main::fibonacci \
     -XX:+UseJeandleCompiler \
     -XX:+JeandleDumpIR \
     -XX:JeandleDumpDirectory=/tmp/jeandle_ir Main
```

After execution, two IR files are generated for different replay stages:
- `[Method].ll`: Use for **Middle-end Replay** (via `opt`).
- `[Method]_optimized.ll`: Use for **Backend Replay** (via `llc`).

## Middle-End Optimization Replay

Use the `opt` tool on the **unoptimized IR file**:

```bash
opt -S -passes='rewrite-statepoints-for-gc,default<O3>' \
    [Method].ll \
    -o Main_fibonacci_manual_optimized.ll
```

# Backend Code Generation Replay

Backend replay uses the `llc` tool. After obtaining `Main_fibonacci_manual_optimized.ll` from the middle-end optimization replay phase, we proceed with backend machine code generation.

The backend replay command is as follows:

```bash
llc -O3 -filetype=obj -mtriple=x86_64-linux-gnu \
    Main_fibonacci_manual_optimized.ll \
    -o Main_fibonacci_middle_replay.o
```

Similarly, you can directly perform backend replay on `Main_fibonacci_1766477713319_optimized.ll`. The command is as follows:

```bash
llc -O3 -filetype=obj -mtriple=x86_64-linux-gnu \
    [Method]_optimized.ll \
    -o Main_fibonacci_backend_replay.o
```

## Result Analysis
Jeandle's backend enforces a critical constraint: **all Java call sites must be 4-byte aligned**.

Run the following commands to inspect the machine code:

```bash
llvm-objdump -d Main_fibonacci_middle_replay.o
llvm-objdump -d Main_fibonacci_backend_replay.o
```

Examine the disassembly output:

```text
     13: 90                            nop
     14: 0f 1f 00                      nopl    (%rax)
     17: 0f 1f 44 00 08                nopl    0x8(%rax,%rax)
     1c: 89 44 24 18                   movl    %eax, 0x18(%rsp)
```

Analysis:
- The NOP padding segment ends at address `0x17`
- The next instruction starts at address `0x1c` (decimal 28)
- Alignment check: `28 % 4 == 0` ✓

Result: Alignment is correct.
