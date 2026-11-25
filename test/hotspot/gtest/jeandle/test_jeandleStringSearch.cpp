/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

#include "precompiled.hpp"
#include "jeandle/jeandleStringSearch.hpp"
#include "unittest.hpp"

namespace {

static int reference(const uint16_t* s, int n, const uint16_t* t, int m, int from) {
  for (int i = from; i <= n - m; i++) {
    int j = 0;
    while (j < m && s[i + j] == t[j]) j++;
    if (j == m) return i;
  }
  return -1;
}

// Count code-unit reads, including preprocessing, independently of wall time.
struct CountedUnit {
  uint16_t value;
  static size_t reads;
  operator uint16_t() const { reads++; return value; }
};
size_t CountedUnit::reads = 0;

TEST(JeandleStringSearch, differential) {
  uint16_t source[1024];
  uint16_t target[257];
  uint32_t random = 0x12345678;
  for (int trial = 0; trial < 20000; trial++) {
    random = random * 1664525 + 1013904223;
    int n = 1 + (random % 1024);
    random = random * 1664525 + 1013904223;
    int m = 1 + (random % 257);
    for (int i = 0; i < n; i++) {
      random = random * 1664525 + 1013904223;
      // The low bytes deliberately collide in the bad-character table.
      source[i] = (trial & 1) ? uint16_t((random >> 24) % 3) : uint16_t(((random >> 24) % 3) << 8);
    }
    for (int i = 0; i < m; i++) {
      random = random * 1664525 + 1013904223;
      target[i] = (trial & 1) ? uint16_t((random >> 24) % 3) : uint16_t(((random >> 24) % 3) << 8);
    }
    if (m <= n && (trial % 3) == 0) {
      int at = (n - m) / 2;
      for (int i = 0; i < m; i++) source[at + i] = target[i];
    }
    int from = (trial % 5) == 0 ? n : trial % n;
    ASSERT_EQ(reference(source, n, target, m, from),
              JeandleStringSearch::index_of(source, n, target, m, from));
  }
}

TEST(JeandleStringSearch, exhaustive_small) {
  uint16_t source[9];
  uint16_t target[6];
  for (int n = 0; n <= 9; n++) {
    for (int m = 1; m <= 6; m++) {
      for (int source_bits = 0; source_bits < (1 << n); source_bits++) {
        for (int i = 0; i < n; i++) source[i] = uint16_t(((source_bits >> i) & 1) << 8);
        for (int target_bits = 0; target_bits < (1 << m); target_bits++) {
          for (int i = 0; i < m; i++) target[i] = uint16_t(((target_bits >> i) & 1) << 8);
          for (int from : {0, n / 2, n}) {
            ASSERT_EQ(reference(source, n, target, m, from),
                      JeandleStringSearch::index_of(source, n, target, m, from));
          }
        }
      }
    }
  }
}

TEST(JeandleStringSearch, linear_work) {
  CountedUnit source[32768];
  CountedUnit target[8192];
  for (int m : {32, 128, 257, 8192}) {
    for (int period : {1, 2, 7, 16, 127}) {
      for (int i = 0; i < 32768; i++) source[i].value = uint16_t((i % period) << 8);
      for (int i = 0; i < m; i++) target[i].value = uint16_t((i % period) << 8);
      target[m - 2].value = 0xffff;
      CountedUnit::reads = 0;
      ASSERT_EQ(-1, JeandleStringSearch::index_of(source, 32768, target, m, 0));
      ASSERT_LE(CountedUnit::reads, size_t(12 * (32768 + m)));
      for (int i = 0; i < m; i++) source[32768 - m + i].value = target[i].value;
      CountedUnit::reads = 0;
      ASSERT_EQ(32768 - m, JeandleStringSearch::index_of(source, 32768, target, m, 0));
      ASSERT_LE(CountedUnit::reads, size_t(12 * (32768 + m)));
    }
  }
}

TEST(JeandleStringSearch, mixed_width) {
  uint16_t source[2048];
  uint8_t target[129];
  for (int i = 0; i < 2048; i++) source[i] = (i % 64) == 63 ? 'b' : 'a';
  for (int i = 0; i < 129; i++) target[i] = 'a';
  ASSERT_EQ(-1, JeandleStringSearch::index_of(source, 2048, target, 129, 0));
  for (int i = 1800; i < 1929; i++) source[i] = 'a';
  ASSERT_EQ(1792, JeandleStringSearch::index_of(source, 2048, target, 129, 0));
  ASSERT_EQ(1801, JeandleStringSearch::index_of(source, 2048, target, 129, 1801));
  ASSERT_EQ(-1, JeandleStringSearch::index_of(source, 2048, target, 129, 1921));
}

} // namespace
