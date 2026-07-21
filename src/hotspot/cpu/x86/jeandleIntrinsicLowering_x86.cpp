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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/IntrinsicsX86.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicLowering.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/globals.hpp"
#include "runtime/vm_version.hpp"

// =============================================================================
// Arch-specific CPU feature checks (x86)
// =============================================================================

bool JeandleIntrinsicLowering::cpu_supports_rounding() {
  // SSE4.1 provides ROUNDSS/ROUNDSD instructions for floor/ceil/rint.
  // UseSSE >= 4 reflects both hardware detection and user overrides,
  // and is what apply_vm_flag_feature_overrides() reads to control the
  // LLVM sse4.1 feature.
  return UseSSE >= 4;
}

bool JeandleIntrinsicLowering::cpu_supports_popcount() {
  // POPCNT instruction for bitCount_i/bitCount_l.
  // UsePopCountInstruction is set by VM_Version when the hardware supports
  // it and can be overridden via -XX:-UsePopCountInstruction.
  return UsePopCountInstruction;
}

bool JeandleIntrinsicLowering::cpu_supports_spin_wait() {
  // PAUSE is part of SSE2, which is baseline on x86-64.
  return true;
}

bool JeandleIntrinsicLowering::cpu_supports_cache_writeback() {
  // Matches C2's predicate for Op_CacheWB/Op_CacheWBPreSync/Op_CacheWBPostSync:
  // predicate(VM_Version::supports_data_cache_line_flush())
  return VM_Version::supports_data_cache_line_flush();
}

// =============================================================================
// Arch-specific intrinsic lowering (x86)
// =============================================================================

bool JeandleIntrinsicLowering::lower_spin_wait_hint() {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  // x86-64: PAUSE instruction — spin-wait hint that improves performance
  // and reduces power consumption in busy-wait loops.  An llvm.* intrinsic is never
  // rewritten to a statepoint, so no gc-leaf annotation is needed.
  builder.CreateIntrinsic(
      llvm::Intrinsic::x86_sse2_pause, llvm::ArrayRef<llvm::Type*>{}, {});
  // void return: nothing to push on the JVM operand stack
  return true;
}

bool JeandleIntrinsicLowering::lower_writeback0() {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = builder.getContext();

  // Pop address (long) and receiver (Unsafe) from the JVM stack.
  llvm::Value* address = _interp->_jvm->lpop();
  _interp->_jvm->apop(); // Unsafe receiver — unused

  // Cast long address to pointer, matching C2's CastX2PNode.
  llvm::PointerType* ptr_ty = llvm::PointerType::get(ctx, 0);
  llvm::Value* addr_ptr = builder.CreateIntToPtr(address, ptr_ty);

  // x86: Select best cache writeback instruction, matching C2's cache_wb().
  bool optimized = VM_Version::supports_clflushopt();
  bool no_evict = VM_Version::supports_clwb();

  if (optimized) {
    if (no_evict) {
      builder.CreateIntrinsic(llvm::Intrinsic::x86_clwb, {}, {addr_ptr});
    } else {
      builder.CreateIntrinsic(llvm::Intrinsic::x86_clflushopt, {}, {addr_ptr});
    }
  } else {
    // CLFLUSH is part of SSE2 and guaranteed available on x86-64 when
    // supports_data_cache_line_flush() returns true.
    builder.CreateIntrinsic(llvm::Intrinsic::x86_sse2_clflush, {}, {addr_ptr});
  }
  return true;
}

bool JeandleIntrinsicLowering::lower_writeback_sync(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  _interp->_jvm->apop(); // Unsafe receiver — unused

  // x86: SFENCE is needed for post-sync when using CLWB or CLFLUSHOPT.
  // Pre-sync is a no-op for all cases
  if (id == vmIntrinsics::_writebackPostSync0) {
    if (VM_Version::supports_clwb() || VM_Version::supports_clflushopt()) {
      builder.CreateIntrinsic(
          llvm::Intrinsic::x86_sse_sfence, llvm::ArrayRef<llvm::Type*>{}, {});
    }
    // else: CLFLUSH is serializing, no fence needed.
  }

  return true;
}
