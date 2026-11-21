#!/usr/bin/env python3
# Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.

"""Check reductions after specializing the real hash template with opt.

Run explicitly with --opt pointing at a Jeandle LLVM opt binary. This is a
standalone template test: it does not establish HotSpot integration coverage.
"""

import argparse
from pathlib import Path
import re
import subprocess


def extract(template):
    source = template.read_text()
    begin = source.index("define private hotspotcc i32 @jeandle.vectorized_hashcode(")
    end = source.index("\n; Helpers use data pointers only within the leaf kernel.", begin)
    body = source[begin:end]
    return (body.replace("define private hotspotcc", "define")
            .replace(" addrspace(1)", "")
            .replace(' noinline optnone "lower-phase"="1" #0', ""))


def module(body, wide, lengths=(24, 64, 96, 104, 127)):
    header = f"@VM_Version.hashcode_vector_512bit = constant i1 {str(wide).lower()}\n"
    for kind in ("byte", "char", "short", "int"):
        header += f"@arrayOopDesc.base_offset_in_bytes.{kind} = constant i32 0\n"
    for lanes in (4, 8, 16):
        header += f"declare i32 @llvm.vector.reduce.add.v{lanes}i32(<{lanes} x i32>)\n"
    functions = []
    for kind in (4, 5, 8, 9, 10):
        for length in lengths:
            signature, instructions = body.split("{", 1)
            signature = signature.replace("@jeandle.vectorized_hashcode", f"@hash_{kind}_{length}")
            if length == "short":
                signature = signature.replace("%length", "%input.length")
                instructions = instructions.replace(
                    "entry:\n", "entry:\n  %length = and i32 %input.length, 31\n", 1)
            elif length is not None:
                instructions = re.sub(r"%length\b", str(length), instructions)
            instructions = re.sub(r"%basicType\b", str(kind), instructions)
            functions.append(signature + "{" + instructions)
    return header + "\n".join(functions)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--opt", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--template", type=Path, default=(Path(__file__).resolve().parents[7]
                        / "src/hotspot/share/jeandle/templatemodule/template.ll"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    body = extract(args.template)
    failures = []
    for mode, wide in (("x86-256", False), ("x86-512", True),
                       ("legacy", False)):
        source = args.output / (mode + ".ll")
        optimized = args.output / (mode + "-opt.ll")
        source.write_text(module(body, wide))
        subprocess.run([args.opt, "-passes=default<O3>,verify", "-S", str(source),
                        "-o", str(optimized)], check=True)
        functions = re.findall(r"define .*?@(hash_(\d+)_(\d+))\(.*?\n}\n",
                               optimized.read_text(), re.S)
        assert len(functions) == 25, f"{mode}: missing specialized kernels"
        for name, kind, length in functions:
            definition = re.search(r"define [^\n]*@" + name + r"\(.*?\n}",
                                   optimized.read_text(), re.S).group()
            reductions = re.findall(r"call i32 @llvm\.vector\.reduce\.add\.v(\d+)i32", definition)
            expected = 1
            if mode != "x86-512" and int(length) in (104, 127):
                expected = 2
            if len(reductions) != expected:
                failures.append(f"{mode} type={kind} length={length}: expected {expected} "
                                f"reduction, got {len(reductions)} ({reductions})")
            if mode == "x86-512" and int(length) == 64 and reductions != ["16"]:
                failures.append(f"{mode} type={kind} length=64: expected retained 16-lane reduction")
        print(f"{mode}: checked 25 specialized kernels", flush=True)
    # Dynamic length keeps the main recurrence alive. Its first incoming raw
    # group must initialize all four wide chains without a zero multiply.
    source = args.output / "x86-512-dynamic.ll"
    optimized = args.output / "x86-512-dynamic-opt.ll"
    source.write_text(module(body, True, lengths=(None,)))
    subprocess.run([args.opt, "-passes=default<O3>,verify", "-S", str(source),
                    "-o", str(optimized)], check=True)
    for kind in (4, 5, 8, 9, 10):
        definition = re.search(r"define [^\n]*@hash_" + str(kind) + r"_None\(.*?\n}",
                               optimized.read_text(), re.S).group()
        recurrences = re.findall(r" = mul <16 x i32> (%[\w.]+), splat \(i32 1304393729\)", definition)
        values = dict(re.findall(r"(%[\w.]+) = ([^\n]*)", definition))
        def raw_group(value):
            instruction = values.get(value, "")
            if instruction.startswith("load <16 x i32>"):
                return True
            widening = re.match(r"(?:sext|zext) <16 x i(?:8|16)> (%[\w.]+) to <16 x i32>", instruction)
            return widening is not None and values.get(widening[1], "").startswith("load <16 x i")
        initialized = 0
        for accumulator in recurrences:
            phi = values.get(accumulator, "")
            incoming = re.findall(r"\[ (%[\w.]+),", phi)
            if phi.startswith("phi <16 x i32>") and "zeroinitializer" not in phi and any(map(raw_group, incoming)):
                initialized += 1
        if len(recurrences) != 4 or initialized != 4:
            failures.append(f"x86-512 type={kind}: first raw group did not initialize "
                            "the four wide recurrence chains")
        # Prefix scaling can overlap the suffix recurrence. Its broadcast
        # multiplier must not depend on the suffix loop's final index.
        suffix_indices = {name for name, instruction in values.items()
                          if re.match(r"add (?:nuw |nsw )*i64 %[\w.]+, 8$", instruction)}
        def dependencies(value):
            seen = set()
            pending = [value]
            while pending:
                current = pending.pop()
                if current in seen:
                    continue
                seen.add(current)
                pending.extend(re.findall(r"%[\w.]+", values.get(current, "")))
            return seen
        checked_broadcasts = 0
        for instruction in values.values():
            if not instruction.startswith("mul <8 x i32>"):
                continue
            for operand in re.findall(r"%[\w.]+", instruction):
                broadcast = values.get(operand, "")
                if broadcast.startswith("shufflevector") and "zeroinitializer" in broadcast:
                    checked_broadcasts += 1
                    if dependencies(operand) & suffix_indices:
                        failures.append(f"x86-512 type={kind}: prefix multiplier waits "
                                        "for the suffix loop's final index")
        if checked_broadcasts != 1:
            failures.append(f"x86-512 type={kind}: expected one checked prefix broadcast, "
                            f"got {checked_broadcasts}")
    # A dynamic short length retains one to three suffix blocks but proves
    # that there is no vector prefix. Seed scaling must use immediate powers
    # in parallel with the vector recurrence, without a runtime multiplier.
    for mode, wide in (("x86-256", False), ("x86-512", True),
                       ("legacy", False)):
        source = args.output / (mode + "-short.ll")
        optimized = args.output / (mode + "-short-opt.ll")
        source.write_text(module(body, wide, lengths=("short",)))
        subprocess.run([args.opt, "-passes=default<O3>,verify", "-S", str(source),
                        "-o", str(optimized)], check=True)
        for kind in (4, 5, 8, 9, 10):
            definition = re.search(r"define [^\n]*@hash_" + str(kind) + r"_short\(.*?\n}",
                                   optimized.read_text(), re.S).group()
            if re.search(r"\bmul i32 %[\w.]+, %[\w.]+", definition):
                failures.append(f"{mode} type={kind}: short seed scaling uses a runtime multiplier")
    assert not failures, "hash kernel shape missing:\n" + "\n".join(failures)
    print("PASS 75 optimized kernel reduction shapes, including 256-bit and legacy architecture fallbacks")
    print("PASS five dynamic wide kernels initialize four raw recurrence chains from the first group")
    print("PASS five dynamic wide kernels scale their prefix independently of the suffix index")
    print("PASS 15 dynamic short kernels avoid runtime scalar multipliers")


if __name__ == "__main__":
    main()
