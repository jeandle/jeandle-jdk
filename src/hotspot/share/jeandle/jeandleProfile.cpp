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
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciCallProfile.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciKlass.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciMethodData.hpp"
#include "ci/ciSymbols.hpp"
#include "logging/log.hpp"
#include "oops/methodData.hpp"
#include "opto/c2_globals.hpp"
#include "runtime/globals.hpp"

JeandleProfile::JeandleProfile(ciMethod* method)
  : _method(method),
    _mdo((JeandleUseProfile && method != nullptr) ? method->method_data() : nullptr) {}

bool JeandleProfile::has_profile() const {
  return _mdo != nullptr && !_mdo->is_empty();
}

bool JeandleProfile::is_mature() const {
  return _mdo != nullptr && _mdo->is_mature();
}

bool JeandleProfile::has_trap_at(int bci,
                                 Deoptimization::DeoptReason reason) const {
  if (_mdo == nullptr) {
    return false;
  }
  // Treat the conservative "maybe trapped here" answer as a real trap for
  // speculation gating. Metadata-only uses such as branch_weights do not need
  // this guard; uncommon-trap/speculative transforms do.
  JeandleCompilation* compilation = JeandleCompilation::current();
  // Speculative trap data is keyed by the compilation root, not the inline
  // method whose MDO is being queried.
  ciMethod* trap_method =
      Deoptimization::reason_is_speculate(reason) ? compilation->method()
                                                  : nullptr;
  return _mdo->has_trap_at(bci, trap_method, reason) != 0;
}

bool JeandleProfile::has_too_many_traps(
    Deoptimization::DeoptReason reason) const {
  if (_mdo == nullptr || _mdo->is_empty()) {
    return false;
  }
  return JeandleCompilation::current()->trap_count(reason) >=
         Deoptimization::per_method_trap_limit(reason);
}

bool JeandleProfile::has_too_many_recompiles(
    int bci, Deoptimization::DeoptReason reason) const {
  if (_mdo == nullptr || _mdo->is_empty()) {
    return false;
  }

  uint bc_cutoff = static_cast<uint>(PerBytecodeRecompilationCutoff) / 8;
  uint method_cutoff = static_cast<uint>(PerMethodRecompilationCutoff) / 2 + 1;
  Deoptimization::DeoptReason per_bc_reason =
      Deoptimization::reason_recorded_per_bytecode_if_any(reason);
  JeandleCompilation* compilation = JeandleCompilation::current();
  // Match C2: speculative trap data is associated with the root compilation
  // method, while _method identifies the MDO being queried.
  ciMethod* trap_method =
      Deoptimization::reason_is_speculate(reason) ? compilation->method()
                                                  : nullptr;

  if ((per_bc_reason == Deoptimization::Reason_none ||
       _mdo->has_trap_at(bci, trap_method, reason) != 0) &&
      _mdo->trap_recompiled_at(bci, trap_method) &&
      _mdo->overflow_recompile_count() >= bc_cutoff) {
    return true;
  }

  return compilation->trap_count(reason) != 0 &&
         compilation->decompile_count() >= method_cutoff;
}

static ciMethod* resolve_profile_virtual_target(ciMethod* caller,
                                                ciMethod* callee,
                                                ciInstanceKlass* holder,
                                                ciKlass* receiver) {
  if (receiver == nullptr) {
    return nullptr;
  }

  if (receiver->is_array_klass()) {
    if (callee->holder() == ciEnv::current()->Object_klass() &&
        callee->name() != ciSymbols::finalize_method_name()) {
      return callee;
    }
    return nullptr;
  }

  if (!is_valid_instance_receiver(receiver, holder)) {
    return nullptr;
  }
  return callee->resolve_invoke(caller->holder(),
                                receiver->as_instance_klass());
}

static JeandleProfile::DevirtualizationInfo
select_profile_targets(ciMethod* caller, ciMethod* callee,
                       ciInstanceKlass* holder, ciCallProfile& call_profile) {
  if (!call_profile.has_receiver(0) || call_profile.count() <= 0 ||
      call_profile.receiver_count(0) <= 0) {
    return {};
  }

  int64_t receiver_count = call_profile.receiver_count(0);
  int64_t total_count = call_profile.count();
  int morphism = call_profile.morphism();
  bool has_major_receiver = 100.0 * call_profile.receiver_prob(0) >=
                            static_cast<float>(TypeProfileMajorReceiverPercent);
  bool bimorphic_candidate = morphism == 2 && UseBimorphicInlining;
  if (morphism != 1 && !has_major_receiver && !bimorphic_candidate) {
    return {};
  }

  auto resolve_target = [&](ciKlass* receiver) -> ciMethod* {
    ciMethod* target =
        resolve_profile_virtual_target(caller, callee, holder, receiver);
    return target != nullptr && !target->is_abstract() ? target : nullptr;
  };

  ciKlass* receiver = call_profile.receiver(0);
  ciMethod* target = resolve_target(receiver);
  if (target == nullptr) {
    return {};
  }

  ciKlass* receiver2 = nullptr;
  ciMethod* target2 = nullptr;
  int64_t receiver_count2 = 0;
  if (bimorphic_candidate) {
    if (call_profile.has_receiver(1) && call_profile.receiver_count(1) > 0) {
      receiver2 = call_profile.receiver(1);
      target2 = resolve_target(receiver2);
      if (target2 == nullptr) {
        receiver2 = nullptr;
      } else {
        receiver_count2 = call_profile.receiver_count(1);
      }
    }
  }

  JeandleProfile::DevirtualizationInfo result;
  result.receiver = receiver;
  result.target = target;
  result.receiver_count = receiver_count;
  result.total_count = total_count;
  result.receiver2 = receiver2;
  result.target2 = target2;
  result.receiver_count2 = receiver_count2;
  return result;
}

JeandleProfile::DevirtualizationInfo
JeandleProfile::devirtualization_at(ciMethod* callee, ciInstanceKlass* holder,
                                    int bci) const {
  if (_method == nullptr || callee == nullptr || holder == nullptr ||
      callee->can_be_statically_bound() || !is_mature() || !UseTypeProfile ||
      !UseJeandleCompiler || !JeandleUseProfiledVirtualCallDevirtualization) {
    return {};
  }

  ciCallProfile call_profile = _method->call_profile_at_bci(bci);
  int morphism = call_profile.morphism();
  DevirtualizationInfo result =
      select_profile_targets(_method, callee, holder, call_profile);
  if (!result.is_valid()) {
    return {};
  }

  Deoptimization::DeoptReason reason = morphism == 2
                                           ? Deoptimization::Reason_bimorphic
                                           : Deoptimization::Reason_class_check;
  // Match C2's hysteresis: an initial miss refreshes receiver profiling through
  // deoptimization. Once the same BCI/reason has trapped, subsequent compiles
  // keep the guarded fast path but select a dynamic virtual-call miss path.
  bool deoptimize_on_miss = (morphism == 1 || result.receiver2 != nullptr) &&
                            !has_trap_at(bci, reason) &&
                            !has_too_many_traps(reason) &&
                            !has_too_many_recompiles(bci, reason);

  if (result.receiver2 == nullptr) {
    log_debug(jeandle)("profile_devirt_candidate: caller=%s bci=%d receiver=%s "
                       "target=%s count=" INT64_FORMAT " total=" INT64_FORMAT
                       " morphism=%d miss=%s",
                       _method->name()->as_utf8(), bci,
                       result.receiver->name()->as_utf8(),
                       result.target->name()->as_utf8(), result.receiver_count,
                       result.total_count, morphism,
                       deoptimize_on_miss ? "uncommon_trap" : "virtual_call");
  } else {
    log_debug(jeandle)(
        "profile_devirt_candidate: caller=%s bci=%d receiver=%s "
        "receiver2=%s target=%s target2=%s count=" INT64_FORMAT
        " count2=" INT64_FORMAT " total=" INT64_FORMAT " morphism=%d miss=%s",
        _method->name()->as_utf8(), bci, result.receiver->name()->as_utf8(),
        result.receiver2->name()->as_utf8(), result.target->name()->as_utf8(),
        result.target2->name()->as_utf8(), result.receiver_count,
        result.receiver_count2, result.total_count, morphism,
        deoptimize_on_miss ? "uncommon_trap" : "virtual_call");
  }

  result.deopt_reason = reason;
  result.deoptimize_on_miss = deoptimize_on_miss;
  return result;
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
