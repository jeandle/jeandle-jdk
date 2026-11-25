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
#include "runtime/stubRoutines.hpp"
#include "unittest.hpp"

// The stub treats all vector registers as caller-saved.
#if defined(AMD64) && !defined(_WIN64)
TEST_VM(StubRoutines, base64_decode_tail) {
  address entry = StubRoutines::base64_decodeBlock();
  if (entry == nullptr) {
    tty->print_cr("Base64 decode stub is unavailable; skipped.");
    return;
  }
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
          // A stub may stop before the atom containing padding.
          ASSERT_GE(written, 0);
          ASSERT_LE((size_t)written, capacity);
          for (size_t i = 0; i < sizeof(dest); i++) {
            if (i < offset || i >= offset + capacity) {
              ASSERT_EQ(dest[i], 0x5a) << "length=" << length << " padding=" << padding
                                      << " offset=" << offset << " index=" << i;
            } else if (i < offset + written) {
              ASSERT_EQ(dest[i], 0);
            }
          }
        }
      }
    }
  }
}
#endif
