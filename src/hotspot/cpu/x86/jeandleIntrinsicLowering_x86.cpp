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

bool JeandleIntrinsicLowering::cpu_supports_bit_shuffle() {
  // BMI2 provides PEXT (parallel bit extract) and PDEP (parallel bit
  // deposit) for Integer/Long.compress/expand. UseBMI2Instructions is set by
  // VM_Version when the hardware supports it and can be overridden via
  // -XX:-UseBMI2Instructions; apply_vm_flag_feature_overrides() keeps the
  // LLVM "bmi2" target feature in sync with the flag, so whenever this gate
  // passes, the emitted intrinsic can lower to the hardware instruction.
  return UseBMI2Instructions;
}

bool JeandleIntrinsicLowering::cpu_supports_spin_wait() {
  // PAUSE is part of SSE2, which is baseline on x86-64.
  return true;
}

uint64_t JeandleIntrinsicLowering::vectorized_mismatch_medium_path_limit() {
  // The medium tier is a fixed 128-bit <16 x i8> loop lowered to
  // pcmpeqb/pmovmskb, available from the SSE2 baseline regardless of the
  // AVX level. C2's ArrayOperationPartialInlineSize carries AVX-512
  // masked-vector ergonomics (0 on non-AVX-512 x86) and does not describe
  // this tier, so the limit is fixed instead of flag-derived. Unlike
  // aarch64, the user flag cannot disable this tier on x86.
  return 32;
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
  llvm::Value* address = _interp->_jvm->lpop();
  _interp->_jvm->apop();

  llvm::PointerType* ptr_type = llvm::PointerType::getUnqual(builder.getContext());
  llvm::Value* address_ptr = builder.CreateIntToPtr(address, ptr_type);
  if (VM_Version::supports_clflushopt()) {
    llvm::Intrinsic::ID id = VM_Version::supports_clwb()
        ? llvm::Intrinsic::x86_clwb
        : llvm::Intrinsic::x86_clflushopt;
    builder.CreateIntrinsic(id, {}, {address_ptr});
  } else {
    builder.CreateIntrinsic(llvm::Intrinsic::x86_sse2_clflush, {}, {address_ptr});
  }
  return true;
}

bool JeandleIntrinsicLowering::lower_writeback_sync(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  _interp->_jvm->apop();

  if (id == vmIntrinsics::_writebackPostSync0 &&
      (VM_Version::supports_clflushopt() || VM_Version::supports_clwb())) {
    builder.CreateIntrinsic(
        llvm::Intrinsic::x86_sse_sfence, llvm::ArrayRef<llvm::Type*>{}, {});
  }
  return true;
}

// Integer/Long.compress/expand(i, mask) via BMI2 PEXT/PDEP. PEXT extracts
// (compresses) and PDEP deposits (expands); both take (source, mask), which
// matches the Java argument order. LLVM names the intrinsics "bmi" even
// though PEXT/PDEP are BMI2 instructions.
bool JeandleIntrinsicLowering::lower_bit_shuffle(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  const bool is_long = (id == vmIntrinsics::_compress_l ||
                        id == vmIntrinsics::_expand_l);

  // Target signature is (int,int) or (long,long): the mask is the second JVM
  // stack slot, so it is popped first.
  llvm::Value* mask = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* value = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();

  llvm::Intrinsic::ID llvm_id = llvm::Intrinsic::not_intrinsic;
  switch (id) {
    case vmIntrinsics::_compress_i: llvm_id = llvm::Intrinsic::x86_bmi_pext_32; break;
    case vmIntrinsics::_expand_i:   llvm_id = llvm::Intrinsic::x86_bmi_pdep_32; break;
    case vmIntrinsics::_compress_l: llvm_id = llvm::Intrinsic::x86_bmi_pext_64; break;
    case vmIntrinsics::_expand_l:   llvm_id = llvm::Intrinsic::x86_bmi_pdep_64; break;
    default: break;
  }
  assert(llvm_id != llvm::Intrinsic::not_intrinsic,
         "unsupported bit-shuffle intrinsic id");
  llvm::Value* result = builder.CreateIntrinsic(llvm_id, {}, {value, mask});
  if (is_long) {
    _interp->_jvm->lpush(result);
  } else {
    _interp->_jvm->ipush(result);
  }
  return true;
}
