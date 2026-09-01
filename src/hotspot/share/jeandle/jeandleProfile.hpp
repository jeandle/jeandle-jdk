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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/SmallVector.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciMethodData.hpp"
#include "memory/allocation.hpp"
#include "runtime/deoptimization.hpp"

#include <cstdint>

class ciInstanceKlass;
class ciKlass;

// Read-only view of a method's MDO for the Jeandle JIT. Callers must treat
// has_profile()==false as "emit the conservative shape", never as an error --
// methods compiled under -Xcomp or never run interpreted will hit it.
class JeandleProfile : public StackObj {
  ciMethod*     _method;
  ciMethodData* _mdo;

  // C2-compatible speculation failure gates. Keep them private so callers use
  // devirtualization_at() as the single receiver-profile policy entry point.
  bool has_trap_at(int bci, Deoptimization::DeoptReason reason) const;
  bool has_too_many_traps(Deoptimization::DeoptReason reason) const;
  bool has_too_many_recompiles(int bci,
                               Deoptimization::DeoptReason reason) const;

 public:
  struct DevirtualizationInfo {
    ciKlass* receiver = nullptr;
    ciMethod* target = nullptr;
    int64_t receiver_count = 0;
    int64_t total_count = 0;
    Deoptimization::DeoptReason deopt_reason = Deoptimization::Reason_none;
    bool deoptimize_on_miss = false;
    ciKlass* receiver2 = nullptr;
    ciMethod* target2 = nullptr;
    int64_t receiver_count2 = 0;

    bool is_valid() const { return receiver != nullptr && target != nullptr; }
    bool is_bimorphic() const { return receiver2 != nullptr; }
  };

  explicit JeandleProfile(ciMethod* method);

  bool has_profile() const;

  // True when the MDO has enough samples to trust for speculation. Speculative
  // transforms (unstable-if prune, guarded devirt) must gate on this.
  bool is_mature() const;

  // Single JDK-side entry point for receiver profile maturity, morphism,
  // target resolution, and speculative trap gating.
  DevirtualizationInfo devirtualization_at(ciMethod* callee,
                                            ciInstanceKlass* holder,
                                            int bci) const;

  struct BranchCounts {
    uint taken;
    uint not_taken;
    bool valid;
  };
  BranchCounts branch_at(int bci) const;

  // Per-case + default execution counts for a tableswitch/lookupswitch.
  // case_counts holds one count per case in bytecode order.
  struct SwitchCounts {
    llvm::SmallVector<uint32_t, 8> case_counts;
    uint32_t default_count;
    bool valid;
  };
  SwitchCounts switch_at(int bci) const;
};

#endif // SHARE_JEANDLE_PROFILE_HPP
