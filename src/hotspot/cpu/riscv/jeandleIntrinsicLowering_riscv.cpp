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
#include "llvm/IR/IntrinsicsRISCV.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleIntrinsicLowering.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/vm_version.hpp"

// =============================================================================
// Arch-specific CPU feature checks (RISC-V)
// =============================================================================

bool JeandleIntrinsicLowering::cpu_supports_rounding() {
  // RISC-V rounding intrinsics are not yet supported by Jeandle.
  // When support is added, this should return true (LLVM provides custom
  // lowering via fcvt even without the Zfa extension).
  ShouldNotReachHere();
  return false;
}

bool JeandleIntrinsicLowering::cpu_supports_popcount() {
  // RISC-V popcount intrinsics are not yet supported by Jeandle.
  // When support is added, this should check UsePopCountInstruction (Zbb).
  ShouldNotReachHere();
  return false;
}

bool JeandleIntrinsicLowering::cpu_supports_spin_wait() {
  // RISC-V PAUSE instruction requires the Zihintpause extension.
  // UseZihintpause is set by VM_Version when the hardware supports it.
  return UseZihintpause;
}

// =============================================================================
// Arch-specific intrinsic lowering (RISC-V)
// =============================================================================

bool JeandleIntrinsicLowering::lower_spin_wait_hint() {
  // RISC-V: PAUSE instruction (FENCE w,r) via llvm.riscv.pause (Zihintpause).
  // cpu_supports_spin_wait() already verified UseZihintpause before
  // is_supported() returned true, so the target feature is guaranteed.
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  builder.CreateIntrinsic(
      llvm::Intrinsic::riscv_pause, llvm::ArrayRef<llvm::Type*>{}, {});
  // void return: nothing to push on the JVM operand stack
  return true;
}
