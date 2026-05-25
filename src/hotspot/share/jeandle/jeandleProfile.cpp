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

uint JeandleProfile::entry_count() const {
  if (!JeandleUseProfile || _method == nullptr) {
    return 0;
  }
  int count = _method->interpreter_invocation_count();
  return count > 0 ? (uint) count : 0;
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
  result.valid     = true;
  return result;
}

void JeandleProfile::switch_at(int bci, GrowableArray<uint>& case_counts,
                               uint& default_count, bool& valid) const {
  valid = false;
  default_count = 0;
  if (!has_profile()) {
    return;
  }
  ciProfileData* data = _mdo->bci_to_data(bci, nullptr);
  if (data == nullptr || !data->is_MultiBranchData()) {
    return;
  }
  MultiBranchData* multi = data->as_MultiBranchData();
  default_count = multi->default_count();
  int num_cases = multi->number_of_cases();
  for (int i = 0; i < num_cases; i++) {
    case_counts.append(multi->count_at(i));
  }
  valid = true;
}
