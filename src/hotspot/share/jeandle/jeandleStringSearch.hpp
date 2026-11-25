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

#ifndef SHARE_JEANDLE_JEANDLESTRINGSEARCH_HPP
#define SHARE_JEANDLE_JEANDLESTRINGSEARCH_HPP

#include "memory/allStatic.hpp"
#include <stdint.h>

// Constant-space Two-Way search (Crochemore and Perrin). A hashed bad-character
// table skips absent suffixes without changing the linear worst-case bound.
// All indices/counts are validated nonnegative Java character indices; the
// target is nonempty. This kernel neither allocates nor reaches a safepoint.
class JeandleStringSearch : AllStatic {
  template <typename T>
  static int maximal_suffix(const T* target, int count, bool reverse, int& period) {
    int suffix = -1;
    int candidate = 0;
    int offset = 1;
    period = 1;
    while (offset < count - candidate) {
      uint16_t a = target[candidate + offset];
      uint16_t b = target[suffix + offset];
      if (a == b) {
        if (offset == period) {
          candidate += period;
          offset = 1;
        } else {
          offset++;
        }
      } else if (reverse ? a > b : a < b) {
        candidate += offset;
        offset = 1;
        period = candidate - suffix;
      } else {
        suffix = candidate;
        candidate++;
        offset = period = 1;
      }
    }
    return suffix;
  }

 public:
  template <typename S, typename T>
  static int index_of(const S* source, int source_count,
                      const T* target, int target_count, int from) {
    int limit = source_count - target_count;
    if (from > limit) return -1;

    int period;
    int suffix = maximal_suffix(target, target_count, false, period);
    int reverse_period;
    int reverse_suffix = maximal_suffix(target, target_count, true, reverse_period);
    if (reverse_suffix > suffix) {
      suffix = reverse_suffix;
      period = reverse_period;
    }
    int split = suffix + 1;
    int prefix = 0;
    while (prefix < split && uint16_t(target[prefix]) == uint16_t(target[prefix + period])) prefix++;
    int remembered = 0;
    if (prefix == split) {
      remembered = target_count - period;
    } else {
      int right = target_count - split;
      period = (suffix > right ? suffix : right) + 1;
    }

    // Collisions retain the rightmost occurrence of the low byte. They can
    // only shorten a safe shift, including when source characters exceed 255.
    int shifts[256];
    for (int i = 0; i < 256; i++) shifts[i] = target_count;
    for (int i = 0; i < target_count; i++) shifts[uint16_t(target[i]) & 255] = target_count - 1 - i;

    int memory = 0;
    int pos = from;
    while (pos <= limit) {
      int skip = shifts[uint16_t(source[pos + target_count - 1]) & 255];
      if (skip != 0) {
        pos += skip > memory ? skip : memory;
        memory = 0;
        continue;
      }
      int i = split > memory ? split : memory;
      while (i < target_count && uint16_t(target[i]) == uint16_t(source[pos + i])) i++;
      if (i < target_count) {
        pos += i - suffix;
        memory = 0;
        continue;
      }
      i = split;
      while (i > memory && uint16_t(target[i - 1]) == uint16_t(source[pos + i - 1])) i--;
      if (i <= memory) return pos;
      pos += period;
      memory = remembered;
    }
    return -1;
  }
};

#endif // SHARE_JEANDLE_JEANDLESTRINGSEARCH_HPP
