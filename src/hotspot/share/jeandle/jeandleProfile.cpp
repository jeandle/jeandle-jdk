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
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

#include "jeandle/jeandleProfile.hpp"
#include "jeandle/jeandle_globals.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciMethodData.hpp"
#include "oops/methodData.hpp"

JeandleProfile::JeandleProfile(ciMethod* method)
  : _method(method),
    _mdo((JeandleUseProfile && method != nullptr) ? method->method_data() : nullptr) {}

bool JeandleProfile::has_profile() const {
  return _mdo != nullptr && !_mdo->is_empty();
}

bool JeandleProfile::is_mature() const {
  return _mdo != nullptr && _mdo->is_mature();
}

// A branch/case count too large to fit in a signed int is treated as
// untrustworthy, mirroring C2's counters_are_meaningful (opto/parse2.cpp),
// which reads JumpData::taken() into an int and rejects negative values.
//
// There is no JVM-wide overflow invariant on these cells: the interpreter
// saturates the full 64-bit cell at UINT64_MAX (cpu/x86/interp_masm_x86.cpp,
// addptr+sbbptr in profile_taken_branch), C1 plain-adds and wraps, and
// JumpData::inc_taken (a uint32-saturating helper) has no callers. The old
// == max_juint test only caught full 64-bit saturation; this covers every
// count whose magnitude C2 would also reject. Same idiom as
// CounterData::count() (methodData.hpp).
static bool count_overflowed(uint c) {
  return c > (uint) max_jint;
}

JeandleProfile::BranchCounts JeandleProfile::branch_at(int bci) const {
  BranchCounts result = {0, 0, false};
  if (!has_profile()) {
    return result;
  }
  // Pass nullptr (not _method) so bci_to_data reads the regular per-bci
  // ProfileData rather than the SpeculativeTrapData extra-data region.
  ciProfileData* data = _mdo->bci_to_data(bci, nullptr);
  if (data == nullptr || !data->is_BranchData()) {
    return result;
  }
  BranchData* branch = data->as_BranchData();
  result.taken     = branch->taken();
  result.not_taken = branch->not_taken();
  result.valid     = !(count_overflowed(result.taken) ||
                      count_overflowed(result.not_taken) ||
                      result.taken + result.not_taken < 40);
  return result;
}

JeandleProfile::SwitchCounts JeandleProfile::switch_at(int bci) const {
  SwitchCounts result;
  result.default_count = 0;
  result.valid = false;
  if (!has_profile()) {
    return result;
  }
  ciProfileData* data = _mdo->bci_to_data(bci, nullptr);
  if (data == nullptr || !data->is_MultiBranchData()) {
    return result;
  }
  result.valid = true;
  MultiBranchData* multi = data->as_MultiBranchData();
  result.default_count = (uint32_t) multi->default_count();
  result.valid = !count_overflowed(multi->default_count());
  int num_cases = multi->number_of_cases();
  for (int i = 0; i < num_cases; i++) {
    uint c = multi->count_at(i);
    if (count_overflowed(c)) result.valid = false;
    result.case_counts.push_back((uint32_t) c);
  }
  return result;
}
