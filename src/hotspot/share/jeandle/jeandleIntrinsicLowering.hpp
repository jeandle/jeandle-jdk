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
 */

#ifndef SHARE_JEANDLE_INTRINSIC_LOWERING_HPP
#define SHARE_JEANDLE_INTRINSIC_LOWERING_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InstrTypes.h"
#include "llvm/IR/Instruction.h"
#include "llvm/IR/Instructions.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "memory/allocation.hpp"
#include "runtime/deoptimization.hpp"

class JeandleAbstractInterpreter;
class ciMethod;

// =============================================================================
// Control-flow facts for a lowered intrinsic call site. Combined into
// CallSiteAttributeMetadata::control_flags with bitwise OR.
// =============================================================================
enum JeandleControlFlag : uint8_t {
  CTRL_NONE                 = 0,
  // The lowering can transfer control to uncommon_trap / deopt.
  CTRL_MAY_DEOPT            = 1u << 0,
  // The call may throw a Java exception and needs invoke-style exception
  // continuation handling.
  CTRL_NEEDS_EXCEPTION_EDGE = 1u << 1,
};

// =============================================================================
// Memory-effect facts for a lowered intrinsic call site. Combined into
// CallSiteAttributeMetadata::memory_flags with bitwise OR and translated into
// LLVM call-site memory attributes where safe.
// =============================================================================
enum JeandleMemoryFlag : uint16_t {
  MEM_NONE              = 0,
  MEM_READ              = 1u << 0,
  MEM_WRITE             = 1u << 1,
  MEM_NEEDS_GC_STATE    = 1u << 2,
};

// =============================================================================
// CallSiteAttributeMetadata — IR-level call-site facts. Narrowly focused on
// attributes that affect LLVM IR lowering (deopt bundles, exception edges,
// GC visibility, memory effects).
// =============================================================================
struct CallSiteAttributeMetadata {
  uint8_t  control_flags;   // bitmask of JeandleControlFlag
  uint16_t memory_flags;    // bitmask of JeandleMemoryFlag

  bool may_deopt()            const { return (control_flags & CTRL_MAY_DEOPT) != 0; }
  bool needs_exception_edge() const { return (control_flags & CTRL_NEEDS_EXCEPTION_EDGE) != 0; }
  bool reads_memory()         const { return (memory_flags  & MEM_READ) != 0; }
  bool writes_memory()        const { return (memory_flags  & MEM_WRITE) != 0; }
  bool needs_gc_state()       const { return (memory_flags  & MEM_NEEDS_GC_STATE) != 0; }
  bool attach_deopt_bundle()  const {
    return may_deopt() || needs_gc_state() || needs_exception_edge();
  }
  bool gc_leaf_by_flags()     const {
    return !needs_gc_state() && !may_deopt() && !needs_exception_edge();
  }
};

// =============================================================================
// JeandleRuntimeCalleeFn — function pointer type for runtime stub resolvers.
// Each resolver materializes the stub/SharedRuntime FunctionCallee in the
// given LLVM module.
// =============================================================================
using JeandleRuntimeCalleeFn = llvm::FunctionCallee (*)(llvm::Module&);

// =============================================================================
// Call-site IR annotation helpers.
// =============================================================================

// Stamp the gc-leaf-function attribute on a call site when the call-site
// metadata or runtime entry indicates a leaf call.
void annotate_call(llvm::CallBase* call,
                   const CallSiteAttributeMetadata& attrs,
                   bool is_gc_leaf_entry = false);

// Translate memory flags into LLVM call-site memory attributes. Only applied
// when the call is safe from LLVM's reordering perspective (no GC-state, no
// deopt, no exception edge).
void apply_memory_attr(llvm::CallBase* call,
                       const CallSiteAttributeMetadata& attrs);

using JeandleTrapReasonMask = uint32_t;
static_assert(Deoptimization::Reason_LIMIT <= 32,
              "JeandleTrapReasonMask must be widened");

class JeandleIntrinsicLowering : public StackObj {
 public:
  explicit JeandleIntrinsicLowering(JeandleAbstractInterpreter* interp);

  // Lower the given intrinsic. Returns true on success, false if the intrinsic
  // cannot be lowered (caller should fall back to normal invoke).
  bool lower(vmIntrinsics::ID id, const ciMethod* target);

  // Is this intrinsic ID one that Jeandle knows how to lower?
  static bool is_supported(vmIntrinsics::ID id);

  // Trap-throttle mask: deopt reasons that should throttle admission when
  // too many traps occurred at the invoke site. Returns 0 for intrinsics
  // that never deopt.
  static JeandleTrapReasonMask trap_throttle_mask(vmIntrinsics::ID id);

 private:
  JeandleAbstractInterpreter* _interp;
  const ciMethod* _target;

  // Arch-specific CPU feature checks. Defined in cpu/<arch>/jeandleIntrinsicLowering_<arch>.cpp.
  static bool cpu_supports_rounding();          // floor/ceil/rint
  static bool cpu_supports_popcount();          // bitCount_i/bitCount_l
  static bool cpu_supports_spin_wait();         // onSpinWait

  // ========================================================================
  // Shared emit helpers
  // ========================================================================

  // Central call-site emission: builds deopt bundle, emits call or invoke,
  // applies GC-leaf and memory annotations.
  llvm::CallBase* emit_callsite(llvm::FunctionCallee callee,
                                llvm::CallingConv::ID cc,
                                llvm::ArrayRef<llvm::Value*> args,
                                const CallSiteAttributeMetadata& attrs,
                                bool is_gc_leaf_entry = false);

  // Emit a llvm.* builtin. Pops all Java args from the JVM stack (from signature),
  // appends extra_args, creates the intrinsic call, and pushes the result.
  bool emit_llvm_builtin(llvm::Intrinsic::ID llvm_id,
                          llvm::ArrayRef<llvm::Value*> extra_args = {});

  // ========================================================================
  // Pattern helpers — reusable lowering patterns shared by multiple intrinsics
  // ========================================================================

  // Dual-path libm (dsin, dcos, dtan, dlog, dlog10, dexp):
  //   JeandleUseHotspotIntrinsics=true  -> try stub -> try SharedRuntime -> llvm builtin
  //   JeandleUseHotspotIntrinsics=false -> llvm builtin only
  bool lower_dual_path_libm(llvm::Intrinsic::ID llvm_id,
                            const char* stub_name,
                            JeandleRuntimeCalleeFn stub_fn,
                            const char* shared_name,
                            JeandleRuntimeCalleeFn shared_fn);

  // JavaOp-based intrinsic: resolve the named JavaOp, pop args, call, push result.
  bool lower_java_op(const char* java_op_name,
                     const CallSiteAttributeMetadata& attrs);

  // ========================================================================
  // Per-intrinsic handlers
  // ========================================================================
  bool lower_bit_count(vmIntrinsics::ID id);
  bool lower_count_zeros(vmIntrinsics::ID id, llvm::Intrinsic::ID llvm_id);
  bool lower_llvm_bitcast();
  bool lower_llvm_fence(vmIntrinsics::ID id);
  bool lower_preconditions_check_index(vmIntrinsics::ID id);
  bool lower_spin_wait_hint();       // arch-specific
  bool lower_compare_unsigned(vmIntrinsics::ID id);
  bool lower_new_array();

  };

#endif // SHARE_JEANDLE_INTRINSIC_LOWERING_HPP
