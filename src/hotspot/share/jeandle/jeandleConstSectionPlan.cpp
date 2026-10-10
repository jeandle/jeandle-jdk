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

#if defined(__has_include) && __has_include("jeandle/jeandleConstSectionPlan.hpp")
#include "jeandle/jeandleConstSectionPlan.hpp"
#else
#include "jeandleConstSectionPlan.hpp"
#endif

#include <algorithm>

const char* ConstSectionPlan::status_name(ConstPlanStatus status) {
  switch (status) {
    case ConstPlanStatus::Unplanned:           return "Unplanned";
    case ConstPlanStatus::Ok:                  return "Ok";
    case ConstPlanStatus::EmptyPlan:           return "EmptyPlan";
    case ConstPlanStatus::InvalidAlignment:    return "InvalidAlignment";
    case ConstPlanStatus::AlignmentBeyondBase: return "AlignmentBeyondBase";
    case ConstPlanStatus::CapacityOverflow:    return "CapacityOverflow";
    case ConstPlanStatus::ForcedFallback:      return "ForcedFallback";
    default:                                   return "Unknown";
  }
}

// Records why the planner gave up. Kept as a private helper so every failure
// path is guaranteed to leave the plan in a consistent, non-usable state.
#define CONST_PLAN_FAIL(s, idx)                 \
  do {                                          \
    _is_valid = false;                          \
    _status = (s);                              \
    _failed_index = (idx);                      \
    _total_size = 0;                            \
    _total_padding = 0;                         \
    _max_alignment = 1;                         \
    return false;                               \
  } while (0)

bool ConstSectionPlan::calculate_layout(uint64_t consts_base_alignment) {
  if (_entries.empty()) {
    _total_size = 0;
    _total_padding = 0;
    _max_alignment = 1;
    _is_valid = true;
    _status = ConstPlanStatus::EmptyPlan;
    _failed_index = 0;
    return true;
  }

  // Step 1: Canonical sorting according to the selected policy
  if (_policy == ConstSortPolicy::AlignmentDescending) {
    // Canonical Axioms:
    // Primary: alignment descending (reduces internal padding)
    // Secondary: section name ascending (deterministic tie-break across builds)
    // Tertiary: section index ascending (stable physical tie-break)
    std::sort(_entries.begin(), _entries.end(),
      [](const ConstSectionPlanEntry& a, const ConstSectionPlanEntry& b) {
        if (a._alignment != b._alignment) {
          return a._alignment > b._alignment;
        }
        if (a._section_name != b._section_name) {
          return a._section_name < b._section_name;
        }
        return a._section_index < b._section_index;
      });
  } else {
    // Baseline / Physical Order:
    // Sort by ELF section index ascending
    std::sort(_entries.begin(), _entries.end(),
      [](const ConstSectionPlanEntry& a, const ConstSectionPlanEntry& b) {
        return a._section_index < b._section_index;
      });
  }

  // Step 2: Deterministic cursor accumulation and alignment validation
  uint64_t cursor = 0;
  uint64_t padding_total = 0;
  uint64_t max_align = 1;

  for (auto& entry : _entries) {
    uint64_t align = entry._alignment;

    // Strict boundary checks:
    // 1. Non-zero
    // 2. Power of 2
    // 3. Within MAX_SUPPORTED_CONST_ALIGNMENT contract (64 bytes)
    if (align == 0 || (align & (align - 1)) != 0 || align > MAX_SUPPORTED_CONST_ALIGNMENT) {
      CONST_PLAN_FAIL(ConstPlanStatus::InvalidAlignment, entry._section_index);
    }

    // Week 5 (F1): the relative-offset layout is only equivalent to absolute
    // address alignment when the consts base itself already satisfies `align`.
    // Refuse instead of emitting misaligned const data.
    if (align > consts_base_alignment) {
      CONST_PLAN_FAIL(ConstPlanStatus::AlignmentBeyondBase, entry._section_index);
    }

    if (align > max_align) {
      max_align = align;
    }

    // Advance cursor to aligned position
    uint64_t padding = (align - (cursor & (align - 1))) & (align - 1);
    if (UINT64_MAX - cursor < padding) {
      CONST_PLAN_FAIL(ConstPlanStatus::CapacityOverflow, entry._section_index);
    }
    cursor += padding;
    padding_total += padding;

    entry._consts_offset = cursor;

    // Advance cursor by size with robust overflow check
    if (UINT64_MAX - cursor < entry._size) {
      CONST_PLAN_FAIL(ConstPlanStatus::CapacityOverflow, entry._section_index);
    }
    cursor += entry._size;
  }

  _total_size = cursor;
  _total_padding = padding_total;
  _max_alignment = max_align;
  _is_valid = true;
  _status = ConstPlanStatus::Ok;
  _failed_index = 0;
  return true;
}
