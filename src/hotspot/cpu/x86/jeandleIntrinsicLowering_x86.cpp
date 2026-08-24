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

bool JeandleIntrinsicLowering::supports_vectorized_mismatch_medium_path() {
  return false;
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
