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
 */

#include "precompiled.hpp"
#include "compiler/compileBroker.hpp"
#include "runtime/vm_version.hpp"
#include "unittest.hpp"

#ifdef JEANDLE
#include "jeandle/jeandleRuntimeRoutine.hpp"
#endif

#ifdef JEANDLE
// Jeandle's Base64 decode kernel is generated on the compiler thread once the
// Jeandle compiler is initialized, and only where it replaces
// StubRoutines::base64_decodeBlock. On CPUs with AVX-512 VBMI pass -XX:UseAVX=2:
//   GTEST="VM_OPTIONS=-XX:UseAVX=2"
// jeandleRuntimeRoutine.hpp pulls LLVM headers after precompiled.hpp, as
// compileBroker.cpp does with jeandleCompiler.hpp.
#if defined(AMD64) && !defined(_WIN64)

static bool jeandle_initialized() {
  AbstractCompiler* compiler = CompileBroker::compiler(CompLevel_full_optimization);
  if (compiler == nullptr || !compiler->is_jeandle()) {
    tty->print_cr("The Jeandle compiler is disabled; skipped.");
    return false;
  }
  // Compiler threads initialize the compiler asynchronously at startup.
  for (int i = 0; i < 3000 && !compiler->is_initialized() && !compiler->is_failed(); i++) {
    os::naked_short_sleep(10);
  }
  if (!compiler->is_initialized()) {
    tty->print_cr("The Jeandle compiler is %s; skipped.",
                  compiler->is_failed() ? "failed" : "still initializing");
  }
  return compiler->is_initialized();
}

TEST_VM(JeandleRuntimeRoutine, base64_decode_tail) {
  if (!jeandle_initialized()) {
    return;
  }
  // The conditions under which generate_base64_decode_stub() registers the kernel.
  if (!UseBASE64Intrinsics || !VM_Version::supports_avx2()
      || (VM_Version::supports_avx512_vbmi() && VM_Version::supports_avx512bw())) {
    tty->print_cr("The shared Base64 decode stub is selected on this CPU; skipped.");
    return;
  }
  address entry = JeandleRuntimeRoutine::find_routine_entry("base64_decode_stub");
  ASSERT_NE(entry, nullptr) << "Jeandle Base64 decode kernel is not registered";

  typedef int (*decode_fn)(address, size_t, size_t, address, size_t, size_t, size_t);
  decode_fn decode = CAST_TO_FN_PTR(decode_fn, entry);
  // Each complete AAAA atom represents three zero bytes.
  for (size_t length = 4; length <= 160; length += 4) {
    for (size_t padding = 0; padding <= 2; padding++) {
      for (size_t offset = 0; offset <= 3; offset++) {
        for (size_t url = 0; url <= 1; url++) {
          unsigned char source[176];
          unsigned char dest[160];
          memset(source, 'A', sizeof(source));
          memset(source + offset + length - padding, '=', padding);
          memset(dest, 0x5a, sizeof(dest));
          size_t capacity = length / 4 * 3 - padding;
          int written = decode(source, offset, offset + length, dest, offset, url, 0);
          // The atom holding '=' is left to the Java decoder, every other
          // atom is decoded, and valid input never has anything stored past
          // the reported length.
          size_t expected = padding == 0 ? capacity : capacity + padding - 3;
          ASSERT_EQ((size_t)written, expected) << "length=" << length << " padding=" << padding
                                               << " offset=" << offset << " url=" << url;
          for (size_t i = 0; i < sizeof(dest); i++) {
            bool decoded = i >= offset && i < offset + expected;
            ASSERT_EQ(dest[i], decoded ? 0 : 0x5a) << "length=" << length << " padding=" << padding
                                                << " offset=" << offset << " index=" << i;
          }
        }
      }
    }
  }
}
#endif // AMD64 && !_WIN64
#endif // JEANDLE
