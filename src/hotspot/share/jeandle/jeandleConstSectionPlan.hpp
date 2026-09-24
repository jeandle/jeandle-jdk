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

#ifndef SHARE_JEANDLE_CONST_SECTION_PLAN_HPP
#define SHARE_JEANDLE_CONST_SECTION_PLAN_HPP

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// Representation of a planned const section
struct ConstSectionPlanEntry {
  uint32_t    _section_index; // ELF physical section header index (primary key)
  std::string _section_name;  // Section name (deep copy, lifecycle independent)
  uint64_t    _elf_offset;    // Byte offset in ELF object
  uint64_t    _size;          // Section size (sh_size)
  uint64_t    _alignment;     // Section alignment (sh_addralign)
  uint64_t    _consts_offset; // Calculated deterministic offset in CodeBuffer SECT_CONSTS

  ConstSectionPlanEntry(uint32_t idx, const std::string& name, uint64_t elf_off,
                        uint64_t sz, uint64_t align)
    : _section_index(idx), _section_name(name), _elf_offset(elf_off),
      _size(sz), _alignment(align), _consts_offset(0) {}
};

// Canonical sorting policies
enum class ConstSortPolicy {
  AlignmentDescending,  // Canonical axiom: Alignment desc -> Name asc -> Index asc (minimizes internal padding)
  ElfIndexAscending     // Baseline / physical order: ELF Section Index asc
};

// Outcome of ConstSectionPlan::calculate_layout().
//
// Every non-usable outcome has to map to a documented decision in
// JeandleCompiledCode::decide_install_layout(). The planner must never leave the
// caller guessing: it either produces a layout that is safe to allocate exactly,
// or it explains why it cannot.
enum class ConstPlanStatus {
  Unplanned,            // calculate_layout() has not run yet
  Ok,                   // layout computed, _total_size is safe to allocate
  EmptyPlan,            // no const sections; exact size is 0 (still usable)
  InvalidAlignment,     // sh_addralign is 0, not a power of two, or beyond the contract
  AlignmentBeyondBase,  // alignment exceeds what the consts base address can guarantee
  CapacityOverflow,     // cumulative size or padding overflows uint64
  ForcedFallback        // JeandleForceConstPlanFallback: planner forced to fail
};

// Deterministic layout planner for CodeBuffer consts
class ConstSectionPlan {
 public:
  // Explicitly defined maximum supported alignment contract for CodeBuffer consts
  static constexpr uint64_t MAX_SUPPORTED_CONST_ALIGNMENT = 64;

  // Human readable name of a planner outcome, for fallback diagnostics.
  static const char* status_name(ConstPlanStatus status);

 private:
  std::vector<ConstSectionPlanEntry> _entries;
  uint64_t _total_size;
  uint64_t _total_padding;   // bytes spent on inter-section alignment padding
  uint64_t _max_alignment;
  bool     _is_valid;
  ConstPlanStatus _status;
  uint32_t _failed_index;    // section index responsible for a failure, if any
  ConstSortPolicy _policy;

 public:
  ConstSectionPlan(ConstSortPolicy policy = ConstSortPolicy::AlignmentDescending)
    : _total_size(0), _total_padding(0), _max_alignment(1),
      _is_valid(false), _status(ConstPlanStatus::Unplanned),
      _failed_index(0), _policy(policy) {}

  void add_entry(const ConstSectionPlanEntry& entry) { _entries.push_back(entry); }
  const std::vector<ConstSectionPlanEntry>& entries() const { return _entries; }
  std::vector<ConstSectionPlanEntry>& entries() { return _entries; }

  uint64_t total_size() const { return _total_size; }
  uint64_t total_padding() const { return _total_padding; }
  uint64_t max_alignment() const { return _max_alignment; }
  bool is_valid() const { return _is_valid; }
  size_t entry_count() const { return _entries.size(); }
  bool is_empty() const { return _entries.empty(); }
  ConstSortPolicy policy() const { return _policy; }
  ConstPlanStatus status() const { return _status; }
  uint32_t failed_section_index() const { return _failed_index; }

  void set_policy(ConstSortPolicy policy) { _policy = policy; }

  // Diagnostic: mark the plan unusable exactly as a planner failure would.
  // There is no ELF input that reliably triggers a real failure, so
  // JeandleForceConstPlanFallback uses this to keep the legacy fallback path
  // covered by tests.
  void force_forced_fallback() {
    _is_valid = false;
    _status = ConstPlanStatus::ForcedFallback;
    _failed_index = 0;
    _total_size = 0;
    _total_padding = 0;
    _max_alignment = 1;
  }

  // Primary lookup key: by ELF section index (unique identity)
  const ConstSectionPlanEntry* find_by_index(uint32_t section_index) const {
    for (const auto& e : _entries) {
      if (e._section_index == section_index) return &e;
    }
    return nullptr;
  }

  // Diagnostic / auxiliary lookup: by section name
  const ConstSectionPlanEntry* find_by_name(const std::string& name) const {
    for (const auto& e : _entries) {
      if (e._section_name == name) return &e;
    }
    return nullptr;
  }

  // Calculate layout: sort entries and calculate deterministic offsets & total_size.
  //
  // consts_base_alignment: the alignment the CodeBuffer guarantees for the base
  // address of the consts section itself. Only if
  //      (consts_base % entry._alignment) == 0
  // is aligning a *relative* offset equivalent to aligning the resulting
  // *absolute* address. Week 5 therefore passes this in explicitly instead of
  // assuming it, so that a platform with a smaller CodeEntryAlignment (or one
  // where -XX:CodeEntryAlignment was lowered) falls back instead of silently
  // emitting misaligned const data.
  bool calculate_layout(uint64_t consts_base_alignment = MAX_SUPPORTED_CONST_ALIGNMENT);

  // Reset plan
  void clear() {
    _entries.clear();
    _total_size = 0;
    _total_padding = 0;
    _max_alignment = 1;
    _is_valid = false;
    _status = ConstPlanStatus::Unplanned;
    _failed_index = 0;
  }
};

#endif // SHARE_JEANDLE_CONST_SECTION_PLAN_HPP
