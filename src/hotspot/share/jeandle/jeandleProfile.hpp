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

#ifndef SHARE_JEANDLE_PROFILE_HPP
#define SHARE_JEANDLE_PROFILE_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciMethodData.hpp"
#include "memory/allocation.hpp"
#include "utilities/growableArray.hpp"

// Read-only view of a method's MDO for the Jeandle JIT. Callers must treat
// has_profile()==false as "emit the conservative shape", never as an error --
// methods compiled under -Xcomp or never run interpreted will hit it.
class JeandleProfile : public StackObj {
  ciMethod*     _method;
  ciMethodData* _mdo;

 public:
  explicit JeandleProfile(ciMethod* method);

  bool has_profile() const;

  // True when the MDO has enough samples to trust for speculation. Speculative
  // transforms (unstable-if prune, guarded devirt) must gate on this.
  bool is_mature() const;

  uint entry_count() const;

  struct BranchCounts {
    uint taken;
    uint not_taken;
    bool valid;
    // A side reached the saturating max (max_juint): its ratio is no longer
    // trustworthy. Speculative use (weights, prune) treats it conservatively.
    bool overflow;
  };
  BranchCounts branch_at(int bci) const;

  // Per-case + default execution counts for a tableswitch/lookupswitch at
  // `bci`. Appends one count per case to `case_counts` in bytecode order.
  // `overflow` is set when any count saturated at max_juint.
  void switch_at(int bci, GrowableArray<uint>& case_counts,
                 uint& default_count, bool& valid, bool& overflow) const;
};

#endif // SHARE_JEANDLE_PROFILE_HPP
