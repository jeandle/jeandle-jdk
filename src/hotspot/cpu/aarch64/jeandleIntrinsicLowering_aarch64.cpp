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
#include "llvm/IR/IntrinsicsAArch64.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicLowering.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/vm_version.hpp"

// =============================================================================
// Arch-specific CPU feature checks (AArch64)
// =============================================================================

bool JeandleIntrinsicLowering::cpu_supports_rounding() {
  // AArch64 has FRINTM/FRINTP/FRINTX/FRINTI/FRINTA/FRINTN/FRINTZ as part of
  // the base FP ISA (ARMv8-A). Rounding is always available.
  return true;
}

bool JeandleIntrinsicLowering::cpu_supports_popcount() {
  // AArch64 always supports popcount via the NEON CNT instruction plus UADDV,
  // or via the CSSC scalar CNT instruction (ARMv8.8+/ARMv9.3+).
  return true;
}

bool JeandleIntrinsicLowering::cpu_supports_cache_writeback() {
  return VM_Version::supports_data_cache_line_flush();
}

bool JeandleIntrinsicLowering::cpu_supports_spin_wait() {
  // The current lowering always emits YIELD. Decline when VM_Version selected
  // NOP/ISB/NONE, and let the normal path preserve the platform policy.
  // TODO: honor NOP/ISB/YIELD and OnSpinWaitInstCount like the template
  // interpreter does.
  return VM_Version::spin_wait_desc().inst() == SpinWait::YIELD;
}

bool JeandleIntrinsicLowering::supports_vectorized_mismatch_medium_path() {
  // NEON (128-bit Advanced SIMD) is ARMv8-A baseline, always available.
  return true;
}

// =============================================================================
// Arch-specific intrinsic lowering (AArch64)
// =============================================================================

bool JeandleIntrinsicLowering::lower_spin_wait_hint() {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  // AArch64: YIELD instruction via llvm.aarch64.hint with hint value 1.
  // The hint encoding is defined in the ARMv8 architecture reference manual;
  // value 1 corresponds to YIELD, which signals the hardware that this thread
  // is in a spin-wait loop.
  // An llvm.* intrinsic is never rewritten to a statepoint, so no gc-leaf annotation
  // is needed.
  builder.CreateIntrinsic(
      llvm::Intrinsic::aarch64_hint, {}, {builder.getInt32(1)});
  // void return: nothing to push on the JVM operand stack
  return true;
}

bool JeandleIntrinsicLowering::lower_writeback0() {
  if (JeandleRuntimeRoutine::find_routine_entry(
          "StubRoutines_data_cache_writeback") == nullptr) {
    return false;
  }
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  _interp->null_check(_interp->_jvm->peek_value(1).value());
  llvm::Value* address = _interp->_jvm->lpop();
  _interp->_jvm->apop();
  // Unsafe.writeback0 accepts an arbitrary native address, not a C-heap object.
  llvm::PointerType* ptr_type = llvm::PointerType::getUnqual(builder.getContext());
  llvm::Value* address_ptr = builder.CreateIntToPtr(address, ptr_type);
  static constexpr CallSiteAttributeMetadata writeback_attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE};
  emit_callsite(
      JeandleRuntimeRoutine::StubRoutines_data_cache_writeback_callee(
          _interp->_module),
      llvm::CallingConv::C, {address_ptr}, writeback_attrs,
      /*is_gc_leaf_entry=*/true);
  return true;
}

bool JeandleIntrinsicLowering::lower_writeback_sync(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  _interp->null_check(_interp->_jvm->peek_value(0).value());
  _interp->_jvm->apop();
  if (id == vmIntrinsics::_writebackPostSync0) {
    // Keep the intrinsic's default unknown memory effects. Restricting DMB to
    // inaccessible memory would let LLVM move ordinary heap/raw accesses
    // across the post-writeback barrier.
    builder.CreateIntrinsic(
      llvm::Intrinsic::aarch64_dmb, {}, {builder.getInt32(0xb)});
  }
  return true;
}
