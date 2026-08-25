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

#include "jeandle/jeandleIntrinsicLowering.hpp"

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/Analysis/ConstantFolding.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/DerivedTypes.h"
#include "llvm/IR/MDBuilder.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciInstance.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciKlass.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "classfile/vmClasses.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "logging/log.hpp"
#include "oops/arrayOop.hpp"
#include "oops/klass.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/globals.hpp"
#include "runtime/vm_version.hpp"
#include "utilities/globalDefinitions.hpp"

// =============================================================================
// Call-site IR annotation helpers (migrated from JeandleIntrinsicIRSemantics)
// =============================================================================

void annotate_call(llvm::CallBase* call,
                   const CallSiteAttributeMetadata& attrs,
                   bool is_gc_leaf_entry) {
  if (attrs.gc_leaf_by_flags() || is_gc_leaf_entry) {
    llvm::LLVMContext& ctx = call->getContext();
    call->addFnAttr(llvm::Attribute::get(ctx, "gc-leaf-function"));
  }
}

void apply_memory_attr(llvm::CallBase* call, const CallSiteAttributeMetadata& attrs) {
  if (attrs.needs_gc_state() || attrs.may_deopt() || attrs.needs_exception_edge()) {
    return;
  }
  const bool reads = attrs.reads_memory();
  const bool writes = attrs.writes_memory();
  if (!reads && !writes) {
    call->setDoesNotAccessMemory();   // memory(none)
  } else if (reads && !writes) {
    call->setOnlyReadsMemory();        // memory(read)
  } else if (!reads && writes) {
    call->setOnlyWritesMemory();       // memory(write)
  }
}

static Klass* exact_java_klass_metadata(llvm::Value* value) {
  llvm::Argument* argument = llvm::dyn_cast<llvm::Argument>(value);
  if (argument != nullptr) {
    llvm::AttributeList attrs = argument->getParent()->getAttributes();
    unsigned arg_no = argument->getArgNo();
    if (!attrs.hasParamAttr(arg_no, llvm::jeandle::Attribute::JavaKlassExact)) {
      return nullptr;
    }
    llvm::Attribute klass_attr =
        attrs.getParamAttr(arg_no, llvm::jeandle::Attribute::JavaKlass);
    if (!klass_attr.isValid()) {
      return nullptr;
    }
    uint64_t klass_value = 0;
    if (klass_attr.getValueAsString().getAsInteger(10, klass_value)) {
      return nullptr;
    }
    return reinterpret_cast<Klass*>(klass_value);
  }

  llvm::CallBase* call = llvm::dyn_cast<llvm::CallBase>(value);
  if (call != nullptr) {
    if (!call->hasRetAttr(llvm::jeandle::Attribute::JavaKlassExact)) {
      return nullptr;
    }
    llvm::Attribute klass_attr =
        call->getAttributeAtIndex(llvm::AttributeList::ReturnIndex,
                                  llvm::jeandle::Attribute::JavaKlass);
    if (!klass_attr.isValid()) {
      return nullptr;
    }
    uint64_t klass_value = 0;
    if (klass_attr.getValueAsString().getAsInteger(10, klass_value)) {
      return nullptr;
    }
    return reinterpret_cast<Klass*>(klass_value);
  }

  llvm::LoadInst* load = llvm::dyn_cast<llvm::LoadInst>(value);
  if (load == nullptr ||
      load->getMetadata(llvm::jeandle::Metadata::JavaKlassExact) == nullptr) {
    return nullptr;
  }
  llvm::MDNode* klass_md =
      load->getMetadata(llvm::jeandle::Metadata::JavaKlass);
  if (klass_md == nullptr || klass_md->getNumOperands() != 1) {
    return nullptr;
  }
  llvm::Metadata* md = klass_md->getOperand(0).get();
  llvm::ConstantAsMetadata* constant_md =
      llvm::dyn_cast<llvm::ConstantAsMetadata>(md);
  if (constant_md == nullptr) {
    return nullptr;
  }
  llvm::ConstantInt* klass_value =
      llvm::dyn_cast<llvm::ConstantInt>(constant_md->getValue());
  return klass_value == nullptr
      ? nullptr
      : reinterpret_cast<Klass*>(klass_value->getZExtValue());
}

// =============================================================================
// JeandleIntrinsicLowering — construction
// =============================================================================

JeandleIntrinsicLowering::JeandleIntrinsicLowering(JeandleAbstractInterpreter* interp)
  : _interp(interp), _target(nullptr) {}

// =============================================================================
// is_supported — simple switch
// =============================================================================

bool JeandleIntrinsicLowering::is_supported(vmIntrinsics::ID id) {
  // CPU feature-dependent intrinsics — arch-specific checks
  switch (id) {
    case vmIntrinsics::_floor:
    case vmIntrinsics::_ceil:
    case vmIntrinsics::_rint:
      return cpu_supports_rounding();

    case vmIntrinsics::_bitCount_i:
    case vmIntrinsics::_bitCount_l:
      return cpu_supports_popcount();

    case vmIntrinsics::_onSpinWait:
      return cpu_supports_spin_wait();

    case vmIntrinsics::_vectorizedMismatch:
      return UseVectorizedMismatchIntrinsic;

    // floatToFloat16/float16ToFloat: gated on the same
    // VM_Version::supports_float16() predicate that turns on the template
    // interpreter's hardware entries (and gates C1/C2's intrinsic versions
    // upstream). Keeping the compiled-code gate identical to the
    // interpreter's keeps NaN semantics consistent across tiers: with
    // hardware support every tier quiets signaling NaNs the same way (see
    // lower_float16_convert); without it every tier runs the pure-Java
    // implementations.
    case vmIntrinsics::_floatToFloat16:
    case vmIntrinsics::_float16ToFloat:
      return VM_Version::supports_float16();

    default: break;
  }

  // Always-supported intrinsics — no CPU feature dependency
  switch (id) {
    // math
    case vmIntrinsics::_dabs:
    case vmIntrinsics::_fabs:
    case vmIntrinsics::_dsqrt:
    case vmIntrinsics::_dsqrt_strict:
    case vmIntrinsics::_iabs:
    case vmIntrinsics::_labs:
    case vmIntrinsics::_dsin:
    case vmIntrinsics::_dcos:
    case vmIntrinsics::_dtan:
    case vmIntrinsics::_dlog:
    case vmIntrinsics::_dlog10:
    case vmIntrinsics::_dexp:

    // min/max: no CPU gating needed. llvm.smin/smax always lower to a
    // compare+select/cmov sequence and llvm.minimum/maximum always lower to
    // a NaN/signed-zero-correct sequence on every target Jeandle supports;
    // neither ever falls back to a libcall the way llvm.floor/ceil/rint can.
    // Math and StrictMath share the same vmIntrinsics ID space and identical
    // javadoc-specified semantics here (strictfp has no effect on min/max),
    // so one lowering covers both.
    case vmIntrinsics::_min:
    case vmIntrinsics::_max:
    case vmIntrinsics::_min_strict:
    case vmIntrinsics::_max_strict:
    case vmIntrinsics::_minF:
    case vmIntrinsics::_maxF:
    case vmIntrinsics::_minD:
    case vmIntrinsics::_maxD:
    case vmIntrinsics::_minF_strict:
    case vmIntrinsics::_maxF_strict:
    case vmIntrinsics::_minD_strict:
    case vmIntrinsics::_maxD_strict:

    // fmaD/fmaF: no separate cpu_supports_fma() gate needed. Unlike
    // rounding/popcount (which have no shared-infrastructure flag check),
    // vmIntrinsics::is_disabled_by_flags() already requires UseFMA for these
    // two IDs and runs unconditionally after is_supported() in
    // try_lower_intrinsic(), so a hardware-less target is rejected there.
    // (apply_vm_flag_feature_overrides() also strips the LLVM "fma" target
    // feature when UseFMA is off, so even a hypothetical direct call here
    // would still lower correctly, just via a libcall instead of hardware.)
    case vmIntrinsics::_fmaD:
    case vmIntrinsics::_fmaF:

    // getClass
    case vmIntrinsics::_getClass:

    // Instrumentation.getObjectSize
    case vmIntrinsics::_getObjectSize:

    // Class queries (the same family as C2's inline_native_Class_query)
    case vmIntrinsics::_isInstance:
    case vmIntrinsics::_getModifiers:
    case vmIntrinsics::_isArray:
    case vmIntrinsics::_isPrimitive:
    case vmIntrinsics::_isInterface:
    case vmIntrinsics::_isHidden:
    case vmIntrinsics::_getSuperclass:
    case vmIntrinsics::_getClassAccessFlags:
    case vmIntrinsics::_Class_cast:
    case vmIntrinsics::_isAssignableFrom:

    // currentThread
    case vmIntrinsics::_currentThread:

    // Reference*
    case vmIntrinsics::_Reference_get:
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:

    // newArray
    case vmIntrinsics::_newArray:

    // Unsafe.allocateInstance
    case vmIntrinsics::_allocateInstance:

    // bitcast
    case vmIntrinsics::_floatToRawIntBits:
    case vmIntrinsics::_intBitsToFloat:
    case vmIntrinsics::_doubleToRawLongBits:
    case vmIntrinsics::_longBitsToDouble:

    // floatToIntBits/doubleToLongBits: no CPU gating needed, same bucket as
    // the other InlineMathNatives-only intrinsics above (min/max/fma) --
    // disabled_by_jvm_flags() only checks InlineMathNatives for these two IDs,
    // no hardware feature check. The NaN-canonicalizing compare+select this
    // lowers to (see lower_fp_to_bits_canonical) is always legal IR.
    case vmIntrinsics::_floatToIntBits:
    case vmIntrinsics::_doubleToLongBits:

    // fence
    case vmIntrinsics::_loadFence:
    case vmIntrinsics::_storeFence:
    case vmIntrinsics::_fullFence:

    // Preconditions
    case vmIntrinsics::_Preconditions_checkIndex:
    case vmIntrinsics::_Preconditions_checkLongIndex:

    // compare unsigned
    case vmIntrinsics::_compareUnsigned_i:
    case vmIntrinsics::_compareUnsigned_l:

    // count leading/trailing zeros
    // No CPU gating: LLVM lowers ctlz/cttz to native sequences on both x86-64
    // (bsr/bsf fallback when LZCNT/TZCNT are absent) and aarch64 (CLZ, RBIT+CLZ),
    // never to a libcall. Matches C2, which always intrinsifies these.
    case vmIntrinsics::_numberOfLeadingZeros_i:
    case vmIntrinsics::_numberOfLeadingZeros_l:
    case vmIntrinsics::_numberOfTrailingZeros_i:
    case vmIntrinsics::_numberOfTrailingZeros_l:

    // reverseBytes: full-width variants are direct bswap; narrow variants need
    // explicit zero/sign-extension semantics.
    case vmIntrinsics::_reverseBytes_i:
    case vmIntrinsics::_reverseBytes_l:
    case vmIntrinsics::_reverseBytes_s:
    case vmIntrinsics::_reverseBytes_c:

    // Math.{add,subtract,multiply,increment,decrement,negate}Exact:
    // UseMathExactIntrinsics and InlineMathNatives are enforced by
    // vmIntrinsics::is_disabled_by_flags(), which the caller
    // (try_lower_intrinsic()) already invokes unconditionally after
    // is_supported() returns true, so no extra check is needed here.
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
    case vmIntrinsics::_subtractExactI:
    case vmIntrinsics::_subtractExactL:
    case vmIntrinsics::_multiplyExactI:
    case vmIntrinsics::_multiplyExactL:
    case vmIntrinsics::_incrementExactI:
    case vmIntrinsics::_incrementExactL:
    case vmIntrinsics::_decrementExactI:
    case vmIntrinsics::_decrementExactL:
    case vmIntrinsics::_negateExactI:
    case vmIntrinsics::_negateExactL:

    // Math.multiplyHigh/unsignedMultiplyHigh: no CPU gating and no dedicated
    // VM flag either (unlike e.g. CRC32/AES/FMA) -- disabled_by_jvm_flags()
    // doesn't gate these IDs at all. The widen-to-i128/multiply/shift IR
    // always lowers validly: plain integer multiply and shift on i128 are
    // legal on every target Jeandle supports.
    case vmIntrinsics::_multiplyHigh:
    case vmIntrinsics::_unsignedMultiplyHigh:
      return true;

    default:
      return false;
  }
}

// =============================================================================
// trap_throttle_mask — simple switch
// =============================================================================

static constexpr JeandleTrapReasonMask trap_reason_mask_val(Deoptimization::DeoptReason reason) {
  return JeandleTrapReasonMask(1u) << static_cast<uint>(reason);
}

JeandleTrapReasonMask JeandleIntrinsicLowering::trap_throttle_mask(vmIntrinsics::ID id) {
  switch (id) {
    case vmIntrinsics::_isAssignableFrom:
    case vmIntrinsics::_getClassAccessFlags:
      return trap_reason_mask_val(Deoptimization::Reason_null_check);
    case vmIntrinsics::_Class_cast:
      return trap_reason_mask_val(Deoptimization::Reason_intrinsic);

    case vmIntrinsics::_allocateInstance:
    case vmIntrinsics::_getObjectSize:
      return trap_reason_mask_val(Deoptimization::Reason_null_check);
    case vmIntrinsics::_Preconditions_checkIndex:
    case vmIntrinsics::_Preconditions_checkLongIndex:
      return trap_reason_mask_val(Deoptimization::Reason_intrinsic) |
             trap_reason_mask_val(Deoptimization::Reason_range_check);
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
    case vmIntrinsics::_subtractExactI:
    case vmIntrinsics::_subtractExactL:
    case vmIntrinsics::_multiplyExactI:
    case vmIntrinsics::_multiplyExactL:
    case vmIntrinsics::_incrementExactI:
    case vmIntrinsics::_incrementExactL:
    case vmIntrinsics::_decrementExactI:
    case vmIntrinsics::_decrementExactL:
    case vmIntrinsics::_negateExactI:
    case vmIntrinsics::_negateExactL:
      return trap_reason_mask_val(Deoptimization::Reason_intrinsic);
    default:
      return 0;
  }
}

// =============================================================================
// lower — unified flat switch
// =============================================================================

bool JeandleIntrinsicLowering::lower(vmIntrinsics::ID id, const ciMethod* target) {
  _target = target;
  switch (id) {
    // Simple LLVM builtins (grouped by llvm intrinsic)
    case vmIntrinsics::_dabs:
    case vmIntrinsics::_fabs:
      return emit_llvm_builtin(llvm::Intrinsic::fabs);

    case vmIntrinsics::_dsqrt:
    case vmIntrinsics::_dsqrt_strict:
      return emit_llvm_builtin(llvm::Intrinsic::sqrt);

    case vmIntrinsics::_floor:
      return emit_llvm_builtin(llvm::Intrinsic::floor);
    case vmIntrinsics::_ceil:
      return emit_llvm_builtin(llvm::Intrinsic::ceil);
    case vmIntrinsics::_rint:
      // Math.rint is statically ties-to-even; llvm.rint follows the dynamic
      // FP rounding mode. Use llvm.roundeven (FRINTN / ROUNDSD with a static
      // nearest-even immediate), matching what C2's rmode_rint emits.
      return emit_llvm_builtin(llvm::Intrinsic::roundeven);

    case vmIntrinsics::_iabs:
    case vmIntrinsics::_labs:
      return emit_llvm_builtin(llvm::Intrinsic::abs,
                                {_interp->_ir_builder.getInt1(false)});

    // Math/StrictMath.min|max(int,int): plain two's-complement signed min/max,
    // identical for both classes.
    case vmIntrinsics::_min:
    case vmIntrinsics::_min_strict:
      return emit_llvm_builtin(llvm::Intrinsic::smin);
    case vmIntrinsics::_max:
    case vmIntrinsics::_max_strict:
      return emit_llvm_builtin(llvm::Intrinsic::smax);

    // Math/StrictMath.min|max(float|double,...): llvm.minimum/maximum
    // implement IEEE-754-2019 minimum/maximum (NaN propagates, -0.0 < +0.0),
    // matching the Math.{min,max} javadoc contract exactly.
    case vmIntrinsics::_minF:
    case vmIntrinsics::_minF_strict:
    case vmIntrinsics::_minD:
    case vmIntrinsics::_minD_strict:
      return emit_llvm_builtin(llvm::Intrinsic::minimum);
    case vmIntrinsics::_maxF:
    case vmIntrinsics::_maxF_strict:
    case vmIntrinsics::_maxD:
    case vmIntrinsics::_maxD_strict:
      return emit_llvm_builtin(llvm::Intrinsic::maximum);

    // Math.fma(float|double,...): llvm.fma is always a correctly-rounded
    // single-rounding fused multiply-add (never contracted like
    // llvm.fmuladd), matching the Math.fma javadoc contract.
    case vmIntrinsics::_fmaD:
    case vmIntrinsics::_fmaF:
      return emit_llvm_builtin(llvm::Intrinsic::fma);

    case vmIntrinsics::_bitCount_i:
    case vmIntrinsics::_bitCount_l:
      return lower_bit_count(id);

    case vmIntrinsics::_numberOfLeadingZeros_i:
    case vmIntrinsics::_numberOfLeadingZeros_l:
      return lower_count_zeros(id, llvm::Intrinsic::ctlz);
    case vmIntrinsics::_numberOfTrailingZeros_i:
    case vmIntrinsics::_numberOfTrailingZeros_l:
      return lower_count_zeros(id, llvm::Intrinsic::cttz);

    // Keep full-width variants as direct IR instead of relying on fallback
    // invoke inlining to recover llvm.bswap.
    case vmIntrinsics::_reverseBytes_i:
    case vmIntrinsics::_reverseBytes_l:
      return emit_llvm_builtin(llvm::Intrinsic::bswap);
    // char/short need a narrow swap plus zero/sign extension (see handler).
    case vmIntrinsics::_reverseBytes_c:
    case vmIntrinsics::_reverseBytes_s:
      return lower_reverse_bytes_narrow(id);

    // Dual-path libm (JeandleUseHotspotIntrinsics selects the path)
    // TODO/FIXME: LLVM's `llvm.sin`, `llvm.cos`, etc. do **not** guarantee
    // fdlibm-compatible results, especially for large inputs where range
    // reduction quality varies by target. This will cause the calculation
    // results to be inconsistent with those of the interpreter.
    //
    // TODO(#424): This is not AArch64-specific; x86 can diverge too when LLVM
    // lowers these intrinsics to a different libm implementation. We have
    // reproduced bit mismatches for dlog and dlog10, so the final design should
    // decide whether these stay LLVM-backed, become runtime-only, or get a
    // platform/semantics policy instead of this global switch.
    case vmIntrinsics::_dsin:
      return lower_dual_path_libm(llvm::Intrinsic::sin,
                                  "StubRoutines_dsin",
                                  &JeandleRuntimeRoutine::StubRoutines_dsin_callee,
                                  "SharedRuntime_dsin",
                                  &JeandleRuntimeRoutine::SharedRuntime_dsin_callee);
    case vmIntrinsics::_dcos:
      return lower_dual_path_libm(llvm::Intrinsic::cos,
                                  "StubRoutines_dcos",
                                  &JeandleRuntimeRoutine::StubRoutines_dcos_callee,
                                  "SharedRuntime_dcos",
                                  &JeandleRuntimeRoutine::SharedRuntime_dcos_callee);
    case vmIntrinsics::_dtan:
      return lower_dual_path_libm(llvm::Intrinsic::tan,
                                  "StubRoutines_dtan",
                                  &JeandleRuntimeRoutine::StubRoutines_dtan_callee,
                                  "SharedRuntime_dtan",
                                  &JeandleRuntimeRoutine::SharedRuntime_dtan_callee);
    case vmIntrinsics::_dlog:
      return lower_dual_path_libm(llvm::Intrinsic::log,
                                  "StubRoutines_dlog",
                                  &JeandleRuntimeRoutine::StubRoutines_dlog_callee,
                                  "SharedRuntime_dlog",
                                  &JeandleRuntimeRoutine::SharedRuntime_dlog_callee);
    case vmIntrinsics::_dlog10:
      return lower_dual_path_libm(llvm::Intrinsic::log10,
                                  "StubRoutines_dlog10",
                                  &JeandleRuntimeRoutine::StubRoutines_dlog10_callee,
                                  "SharedRuntime_dlog10",
                                  &JeandleRuntimeRoutine::SharedRuntime_dlog10_callee);
    case vmIntrinsics::_dexp:
      return lower_dual_path_libm(llvm::Intrinsic::exp,
                                  "StubRoutines_dexp",
                                  &JeandleRuntimeRoutine::StubRoutines_dexp_callee,
                                  "SharedRuntime_dexp",
                                  &JeandleRuntimeRoutine::SharedRuntime_dexp_callee);

    // getClass
    //
    // TODO 1: When the receiver's Java type is known at compile time (e.g., the
    // result of a `new` bytecode which carries a `java-klass` return attribute),
    // we can skip the `jeandle.load_klass` call that reads the object header and
    // use the known Klass pointer directly.
    //
    // TODO 2: Optimize the comparison between class pointers.
    case vmIntrinsics::_getClass:
      return lower_java_op("jeandle.get_class",
                           {CTRL_NONE, MEM_READ});

    // Thread.currentThread()
    case vmIntrinsics::_currentThread:
      return lower_java_op("jeandle.current_thread_obj",
                           {CTRL_NONE, MEM_READ});

    // Class queries
    case vmIntrinsics::_isInstance:
    case vmIntrinsics::_getModifiers:
    case vmIntrinsics::_isArray:
    case vmIntrinsics::_isPrimitive:
    case vmIntrinsics::_isInterface:
    case vmIntrinsics::_isHidden:
    case vmIntrinsics::_getSuperclass:
    case vmIntrinsics::_getClassAccessFlags:
      return lower_class_query(id);
    case vmIntrinsics::_Class_cast:
      return lower_class_cast();
    case vmIntrinsics::_isAssignableFrom:
      return lower_class_is_assignable_from();

    case vmIntrinsics::_getObjectSize:
      return lower_get_object_size();

    // Reference*
    case vmIntrinsics::_Reference_get:
      return lower_java_op("jeandle.reference_get",
                           {CTRL_NONE, MEM_READ});
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:
      return lower_java_op("jeandle.reference_refers_to",
                           {CTRL_NONE, MEM_READ});

    case vmIntrinsics::_vectorizedMismatch:
      return lower_vectorized_mismatch();

    // newArray
    case vmIntrinsics::_newArray:
      return lower_new_array();

    // Unsafe.allocateInstance
    case vmIntrinsics::_allocateInstance:
      return lower_unsafe_allocate_instance();

    // bitcast
    case vmIntrinsics::_floatToRawIntBits:
    case vmIntrinsics::_intBitsToFloat:
    case vmIntrinsics::_doubleToRawLongBits:
    case vmIntrinsics::_longBitsToDouble:
      return lower_llvm_bitcast();

    // floatToIntBits/doubleToLongBits
    case vmIntrinsics::_floatToIntBits:
    case vmIntrinsics::_doubleToLongBits:
      return lower_fp_to_bits_canonical(id);

    // floatToFloat16/float16ToFloat
    case vmIntrinsics::_floatToFloat16:
    case vmIntrinsics::_float16ToFloat:
      return lower_float16_convert(id);

    // fence
    case vmIntrinsics::_loadFence:
    case vmIntrinsics::_storeFence:
    case vmIntrinsics::_fullFence:
      return lower_llvm_fence(id);

    // onSpinWait
    case vmIntrinsics::_onSpinWait:
      return lower_spin_wait_hint();

    // Preconditions
    case vmIntrinsics::_Preconditions_checkIndex:
    case vmIntrinsics::_Preconditions_checkLongIndex:
      return lower_preconditions_check_index(id);

    // CompareUnsigned
    case vmIntrinsics::_compareUnsigned_i:
    case vmIntrinsics::_compareUnsigned_l:
      return lower_compare_unsigned(id);

    // addExact and the other exact-arithmetic intrinsics share the same
    // overflow-trap path implemented by lower_exact_arith.
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
      return lower_exact_arith(id, llvm::Intrinsic::sadd_with_overflow);

    // subtractExact/decrementExact/negateExact all reduce to
    // llvm.ssub.with.overflow (see lower_exact_arith).
    case vmIntrinsics::_subtractExactI:
    case vmIntrinsics::_subtractExactL:
    case vmIntrinsics::_decrementExactI:
    case vmIntrinsics::_decrementExactL:
    case vmIntrinsics::_negateExactI:
    case vmIntrinsics::_negateExactL:
      return lower_exact_arith(id, llvm::Intrinsic::ssub_with_overflow);

    case vmIntrinsics::_multiplyExactI:
    case vmIntrinsics::_multiplyExactL:
      return lower_exact_arith(id, llvm::Intrinsic::smul_with_overflow);

    case vmIntrinsics::_incrementExactI:
    case vmIntrinsics::_incrementExactL:
      return lower_exact_arith(id, llvm::Intrinsic::sadd_with_overflow);

    case vmIntrinsics::_multiplyHigh:
    case vmIntrinsics::_unsignedMultiplyHigh:
      return lower_multiply_high(id);

    default:
      return false;
  }
}

// =============================================================================
// Shared emit helpers
// =============================================================================

llvm::CallBase* JeandleIntrinsicLowering::emit_callsite(llvm::FunctionCallee callee,
                                                        llvm::CallingConv::ID cc,
                                                        llvm::ArrayRef<llvm::Value*> args,
                                                        const CallSiteAttributeMetadata& attrs,
                                                        bool is_gc_leaf_entry) {
  llvm::SmallVector<llvm::OperandBundleDef, 1> bundles;
  if (attrs.attach_deopt_bundle()) {
    bundles.push_back(_interp->create_current_deopt_bundle());
  }
  llvm::CallBase* site;
  if (attrs.needs_exception_edge()) {
    site = _interp->create_call_ex(callee, args, cc, bundles);
  } else {
    site = _interp->create_call(callee, args, cc, bundles);
    site->setDoesNotThrow();
    apply_memory_attr(site, attrs);
  }
  annotate_call(site, attrs, is_gc_leaf_entry);
  if (_target != nullptr) {
    attach_java_klass_ret_attr(site,
                               _target->signature()->return_type(),
                               *_interp->_context);
  }
  return site;
}

// =============================================================================
// emit_llvm_builtin — emit a llvm.* intrinsic call
// =============================================================================

bool JeandleIntrinsicLowering::emit_llvm_builtin(llvm::Intrinsic::ID llvm_id,
                                                   llvm::ArrayRef<llvm::Value*> extra_args) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  const int java_arg_count = sig->count();
  assert(_target->is_static(), "emit_llvm_builtin only supports static methods");

  BasicType return_type = sig->return_type()->basic_type();

  // Compute computational types for JVM stack pops.
  llvm::SmallVector<BasicType, 4> pop_types(java_arg_count);
  for (int i = 0; i < java_arg_count; ++i) {
    pop_types[i] = JeandleType::actual2computational(sig->type_at(i)->basic_type());
  }

  // Pop Java args from the JVM stack in reverse order (LIFO).
  llvm::SmallVector<llvm::Value*, 4> args;
  args.reserve(java_arg_count + extra_args.size());
  args.resize(java_arg_count);
  for (int i = java_arg_count - 1; i >= 0; --i) {
    args[i] = _interp->_jvm->pop(pop_types[i]);
  }

  // Append any extra LLVM-level arguments (e.g., i1 false for llvm.abs/ctlz/cttz).
  args.append(extra_args.begin(), extra_args.end());

  llvm::CallInst* call = builder.CreateIntrinsic(
      JeandleType::java2llvm(return_type, ctx), llvm_id, args);

  _interp->_jvm->push(return_type, call);
  return true;
}

// =============================================================================
// lower_dual_path_libm — JeandleUseHotspotIntrinsics selection
// =============================================================================

bool JeandleIntrinsicLowering::lower_dual_path_libm(llvm::Intrinsic::ID llvm_id,
                                                     const char* stub_name,
                                                     JeandleRuntimeCalleeFn stub_fn,
                                                     const char* shared_name,
                                                     JeandleRuntimeCalleeFn shared_fn) {
  if (JeandleUseHotspotIntrinsics) {
    // Try HotSpot runtime stub -> SharedRuntime -> llvm builtin
    JeandleRuntimeCalleeFn fn = nullptr;
    if (JeandleRuntimeRoutine::find_routine_entry(stub_name) != nullptr) {
      fn = stub_fn;
    } else if (JeandleRuntimeRoutine::find_routine_entry(shared_name) != nullptr) {
      fn = shared_fn;
    }
    if (fn != nullptr) {
      static constexpr CallSiteAttributeMetadata libm_attrs = {CTRL_NONE, MEM_NONE};
      llvm::Value* arg = _interp->_jvm->dpop();
      llvm::CallBase* site = emit_callsite(fn(_interp->_module), llvm::CallingConv::C,
                                           {arg}, libm_attrs, /*is_gc_leaf_entry=*/true);
      _interp->_jvm->dpush(site);
      return true;
    }
    // No runtime available, fall through to LLVM builtin
    return emit_llvm_builtin(llvm_id);
  } else {
    return emit_llvm_builtin(llvm_id);
  }
}

// =============================================================================
// lower_java_op — JavaOp-based intrinsic
// =============================================================================

bool JeandleIntrinsicLowering::lower_java_op(const char* java_op_name,
                                              const CallSiteAttributeMetadata& attrs) {
  llvm::Function* java_op = _interp->_module.getFunction(java_op_name);
  assert(java_op != nullptr, "invalid JavaOp");

  // Pop args from the JVM stack in reverse order (shape from signature)
  ciSignature* sig = _target->signature();
  const bool has_receiver = !_target->is_static();
  const int sig_count = sig->count();
  const int arg_count = sig_count + (has_receiver ? 1 : 0);

  llvm::SmallVector<llvm::Value*, 4> args;
  llvm::SmallVector<BasicType, 4> arg_types;
  args.resize(arg_count);
  arg_types.resize(arg_count);
  for (int i = 0; i < arg_count; ++i) {
    arg_types[i] = (has_receiver && i == 0)
        ? T_OBJECT
        : JeandleType::actual2computational(sig->type_at(i - (has_receiver ? 1 : 0))->basic_type());
  }
  for (int i = arg_count - 1; i >= 0; --i) {
    args[i] = _interp->_jvm->pop(arg_types[i]);
  }

  llvm::CallBase* site = emit_callsite(java_op, llvm::CallingConv::Hotspot_JIT, args, attrs);

  const BasicType result_type =
      JeandleType::actual2computational(sig->return_type()->basic_type());
  if (result_type != T_VOID) {
    _interp->_jvm->push(result_type, site);
  }
  return true;
}

// =============================================================================
// Per-intrinsic handlers
// =============================================================================

llvm::Value* JeandleIntrinsicLowering::emit_direct_mirror_from_klass(
    llvm::Value* klass, const char* name_prefix) {
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::PointerType* c_heap = llvm::PointerType::get(*_interp->_context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::PointerType* java_heap = llvm::PointerType::get(*_interp->_context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::GlobalVariable* offset_gv =
      _interp->_module.getGlobalVariable("Klass.java_mirror_offset", true);
  assert(offset_gv != nullptr, "Klass.java_mirror_offset global must exist");
  llvm::Value* offset = b.CreateLoad(b.getInt32Ty(), offset_gv);
  llvm::Value* handle_addr = b.CreateInBoundsGEP(b.getInt8Ty(), klass, offset,
                                                 std::string(name_prefix) + ".handle_addr");
  llvm::LoadInst* handle = b.CreateLoad(c_heap, handle_addr, std::string(name_prefix) + ".handle");
  handle->setAtomic(llvm::AtomicOrdering::Unordered);
  handle->setAlignment(llvm::Align(sizeof(void*)));
  llvm::LoadInst* mirror = b.CreateLoad(java_heap, handle, std::string(name_prefix) + ".mirror");
  mirror->setAtomic(llvm::AtomicOrdering::Unordered);
  mirror->setAlignment(llvm::Align(sizeof(void*)));
  return mirror;
}

ciObject* JeandleIntrinsicLowering::constant_oop(llvm::Value* value) const {
  llvm::LoadInst* load = llvm::dyn_cast<llvm::LoadInst>(value);
  if (load == nullptr) {
    return nullptr;
  }
  llvm::Value* address = load->getPointerOperand()->stripPointerCasts();
  llvm::GlobalVariable* handle = llvm::dyn_cast<llvm::GlobalVariable>(address);
  if (handle == nullptr) {
    return nullptr;
  }
  return _interp->_compiled_code.oop_for_handle_name(handle->getName());
}

ciType* JeandleIntrinsicLowering::constant_class_type(llvm::Value* mirror) const {
  ciObject* mirror_object = constant_oop(mirror);
  if (mirror_object == nullptr || !mirror_object->is_instance()) {
    return nullptr;
  }
  return mirror_object->as_instance()->java_mirror_type();
}

llvm::Value* JeandleIntrinsicLowering::constant_klass_value(ciKlass* klass) const {
  assert(klass != nullptr && klass->is_loaded(), "klass must be loaded");
  ciEnv* env = ciEnv::current();
  assert(env != nullptr && env->oop_recorder() != nullptr,
         "constant klass requires an active oop recorder");
  env->oop_recorder()->find_index(klass->constant_encoding());
  llvm::PointerType* klass_type = llvm::PointerType::get(
      *_interp->_context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  return _interp->_ir_builder.CreateIntToPtr(
      _interp->_ir_builder.getInt64(
          reinterpret_cast<intptr_t>(klass->constant_encoding())),
      klass_type, "class.constant.klass");
}

bool JeandleIntrinsicLowering::try_fold_constant_class_query(
    vmIntrinsics::ID id, llvm::Value* mirror, jint* result) const {
  ciType* mirror_type = constant_class_type(mirror);
  if (mirror_type == nullptr) {
    return false;
  }

  if (mirror_type->is_primitive_type()) {
    switch (id) {
      case vmIntrinsics::_isPrimitive:
        *result = 1;
        return true;
      case vmIntrinsics::_isArray:
      case vmIntrinsics::_isInterface:
      case vmIntrinsics::_isHidden:
        *result = 0;
        return true;
      case vmIntrinsics::_getModifiers:
      case vmIntrinsics::_getClassAccessFlags:
        *result = JVM_ACC_ABSTRACT | JVM_ACC_FINAL | JVM_ACC_PUBLIC;
        return true;
      default:
        return false;
    }
  }

  if (!mirror_type->is_klass() || !mirror_type->is_loaded()) {
    return false;
  }
  ciKlass* klass = mirror_type->as_klass();
  switch (id) {
    case vmIntrinsics::_isPrimitive:
      *result = 0;
      return true;
    case vmIntrinsics::_isArray:
      *result = klass->is_array_klass() ? 1 : 0;
      return true;
    case vmIntrinsics::_isInterface:
      *result = klass->is_interface() ? 1 : 0;
      return true;
    case vmIntrinsics::_isHidden:
      *result = klass->is_instance_klass() &&
                        klass->as_instance_klass()->is_hidden()
                    ? 1
                    : 0;
      return true;
    case vmIntrinsics::_getModifiers:
      *result = klass->modifier_flags();
      return true;
    case vmIntrinsics::_getClassAccessFlags:
      *result = klass->access_flags();
      return true;
    default:
      return false;
  }
}

// ---- lower_class_query ----
//
// Keep the C2 inline_native_Class_query family under one dispatcher, but split
// the implementation by operand-stack shape and result type.  This avoids a
// boolean-only monolith while preserving the shared Class-mirror model.
bool JeandleIntrinsicLowering::lower_class_query(vmIntrinsics::ID id) {
  switch (id) {
    case vmIntrinsics::_isArray:
    case vmIntrinsics::_isPrimitive:
    case vmIntrinsics::_isInterface:
    case vmIntrinsics::_isHidden:
      return lower_class_boolean_query(id);
    case vmIntrinsics::_getModifiers:
    case vmIntrinsics::_getClassAccessFlags:
      return lower_class_flags_query(id);
    case vmIntrinsics::_isInstance:
      return lower_class_is_instance();
    case vmIntrinsics::_getSuperclass:
      return lower_class_get_superclass();
    default:
      ShouldNotReachHere();
      return false;
  }
}

bool JeandleIntrinsicLowering::lower_class_cast() {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Value* object = _interp->_jvm->raw_peek(0).value();
  llvm::Value* mirror = _interp->_jvm->raw_peek(1).value();
  ciType* mirror_type = constant_class_type(mirror);
  if (mirror_type != nullptr && mirror_type->is_primitive_type()) {
    llvm::Value* is_null = builder.CreateICmpEQ(
        object, llvm::ConstantPointerNull::get(
                    llvm::cast<llvm::PointerType>(object->getType())),
        "class.cast.is_null");
    llvm::BasicBlock* pass =
        llvm::BasicBlock::Create(ctx, "class_cast_pass", _interp->_llvm_func);
    llvm::BasicBlock* fail =
        llvm::BasicBlock::Create(ctx, "class_cast_fail", _interp->_llvm_func);
    builder.CreateCondBr(is_null, pass, fail);
    _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                           Deoptimization::Action_maybe_recompile, fail);
    builder.SetInsertPoint(pass);
    _interp->_block->set_tail_llvm_block(pass);
    _interp->_jvm->apop();
    _interp->_jvm->apop();
    _interp->_jvm->push(T_OBJECT, object);
    return true;
  }

  if (mirror_type != nullptr && mirror_type->is_klass() && mirror_type->is_loaded()) {
    Klass* target_klass = reinterpret_cast<Klass*>(mirror_type->as_klass()->constant_encoding());
    Klass* object_klass = exact_java_klass_metadata(object);
    if (object_klass != nullptr) {
      if (object_klass->is_subtype_of(target_klass)) {
        _interp->_jvm->apop();
        _interp->_jvm->apop();
        _interp->_jvm->push(T_OBJECT, object);
        return true;
      }
      return false;
    }
  }

  llvm::Value *klass =
      mirror_type != nullptr && mirror_type->is_klass() &&
              mirror_type->is_loaded()
          ? constant_klass_value(mirror_type->as_klass())
          : _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  llvm::PointerType* klass_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* is_primitive = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(klass_ty), "class.cast.is_primitive");
  llvm::Value* is_null = builder.CreateICmpEQ(
      object, llvm::ConstantPointerNull::get(
                  llvm::cast<llvm::PointerType>(object->getType())),
      "class.cast.is_null");

  llvm::BasicBlock* check =
      llvm::BasicBlock::Create(ctx, "class_cast_check", _interp->_llvm_func);
  llvm::BasicBlock* pass =
      llvm::BasicBlock::Create(ctx, "class_cast_pass", _interp->_llvm_func);
  llvm::BasicBlock* fail =
      llvm::BasicBlock::Create(ctx, "class_cast_fail", _interp->_llvm_func);
  llvm::BasicBlock* non_null =
      llvm::BasicBlock::Create(ctx, "class_cast_non_null", _interp->_llvm_func);
  builder.CreateCondBr(is_null, pass, non_null);

  builder.SetInsertPoint(non_null);
  builder.CreateCondBr(is_primitive, fail, check);

  builder.SetInsertPoint(check);
  llvm::Value* ok =
      _interp->call_java_op("jeandle.check_instanceof", {klass, object});
  builder.CreateCondBr(ok, pass, fail);

  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_maybe_recompile, fail);
  builder.SetInsertPoint(pass);
  _interp->_block->set_tail_llvm_block(pass);
  _interp->_jvm->apop();
  _interp->_jvm->apop();
  _interp->_jvm->push(T_OBJECT, object);
  return true;
}

bool JeandleIntrinsicLowering::lower_class_is_assignable_from() {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Value* sub_mirror = _interp->_jvm->raw_peek(0).value();
  llvm::Value* super_mirror = _interp->_jvm->raw_peek(1).value();
  // Both operands are dereferenced below. Preserve Class.isAssignableFrom's
  // Java NPE contract before loading the mirror Klass fields.
  _interp->null_check(super_mirror);
  _interp->null_check(sub_mirror);
  ciType* super_type = constant_class_type(super_mirror);
  ciType* sub_type = constant_class_type(sub_mirror);
  if (super_type != nullptr && sub_type != nullptr) {
    bool foldable = true;
    bool result = false;
    if (super_type->is_primitive_type() || sub_type->is_primitive_type()) {
      result = super_type == sub_type;
    } else if (super_type->is_klass() && sub_type->is_klass() &&
               super_type->is_loaded() && sub_type->is_loaded()) {
      result = sub_type->as_klass()->is_subtype_of(super_type->as_klass());
    } else {
      foldable = false;
    }
    if (foldable) {
      _interp->_jvm->apop();
      _interp->_jvm->apop();
      _interp->_jvm->ipush(builder.getInt32(result ? 1 : 0));
      return true;
    }
  }

  llvm::PointerType* klass_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Constant* null_klass = llvm::ConstantPointerNull::get(klass_ty);
  llvm::Value *super_klass =
      super_type != nullptr && super_type->is_primitive_type() ? null_klass
      : super_type != nullptr && super_type->is_klass() &&
              super_type->is_loaded()
          ? constant_klass_value(super_type->as_klass())
          : _interp->call_java_op("jeandle.load_mirror_klass",
                                  {super_mirror});
  llvm::Value *sub_klass =
      sub_type != nullptr && sub_type->is_primitive_type() ? null_klass
      : sub_type != nullptr && sub_type->is_klass() && sub_type->is_loaded()
          ? constant_klass_value(sub_type->as_klass())
          : _interp->call_java_op("jeandle.load_mirror_klass",
                                  {sub_mirror});
  llvm::Value* super_primitive = builder.CreateICmpEQ(
      super_klass, null_klass,
      "class.assignable.super_primitive");
  llvm::Value* sub_primitive = builder.CreateICmpEQ(
      sub_klass, null_klass,
      "class.assignable.sub_primitive");
  llvm::Value* any_primitive = builder.CreateOr(
      super_primitive, sub_primitive, "class.assignable.any_primitive");
  llvm::Value* same_mirror = builder.CreateICmpEQ(
      super_mirror, sub_mirror, "class.assignable.same_primitive");
  llvm::Value* both_primitive = builder.CreateAnd(
      super_primitive, sub_primitive, "class.assignable.both_primitive");
  llvm::Value* primitive_result = builder.CreateAnd(
      both_primitive, same_mirror, "class.assignable.primitive_result");

  llvm::BasicBlock* primitive = builder.GetInsertBlock();
  llvm::BasicBlock* reference =
      llvm::BasicBlock::Create(ctx, "class_assignable_reference", _interp->_llvm_func);
  llvm::BasicBlock* merge =
      llvm::BasicBlock::Create(ctx, "class_assignable_merge", _interp->_llvm_func);
  builder.CreateCondBr(any_primitive, merge, reference);

  builder.SetInsertPoint(reference);
  llvm::Value* subtype = _interp->call_java_op(
      "jeandle.check_klass_subtype", {sub_klass, super_klass});
  builder.CreateBr(merge);
  llvm::BasicBlock* reference_end = builder.GetInsertBlock();

  builder.SetInsertPoint(merge);
  _interp->_block->set_tail_llvm_block(merge);
  llvm::PHINode* result = builder.CreatePHI(builder.getInt1Ty(), 2,
                                             "class.assignable.result");
  result->addIncoming(primitive_result, primitive);
  result->addIncoming(subtype, reference_end);
  _interp->_jvm->apop();
  _interp->_jvm->apop();
  _interp->_jvm->ipush(builder.CreateZExt(result, builder.getInt32Ty()));
  return true;
}

// Class mirrors for primitive types (including void.class) have a null
// java_lang_Class::_klass field. Reference and array mirrors point to their
// Klass metadata.
bool JeandleIntrinsicLowering::lower_class_boolean_query(vmIntrinsics::ID id) {
  assert(id == vmIntrinsics::_isArray ||
         id == vmIntrinsics::_isPrimitive ||
         id == vmIntrinsics::_isInterface ||
         id == vmIntrinsics::_isHidden, "unexpected Class query intrinsic");

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // Physical JVM slot map, top to bottom:
  //   raw depth 0: java.lang.Class receiver
  llvm::Value* mirror = _interp->_jvm->raw_peek(0).value();
  jint constant_result = 0;
  if (try_fold_constant_class_query(id, mirror, &constant_result)) {
    _interp->_jvm->apop();
    _interp->_jvm->ipush(builder.getInt32(constant_result));
    return true;
  }

  llvm::CallInst* klass =
      _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  klass->setName("class.query.klass");

  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* is_primitive = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty), "class.query.is_primitive");

  if (id == vmIntrinsics::_isPrimitive) {
    _interp->_jvm->apop();
    _interp->_jvm->ipush(builder.CreateZExt(is_primitive, builder.getInt32Ty(),
                                            "class.query.result"));
    return true;
  }

  _interp->_jvm->apop();
  llvm::BasicBlock* primitive_bb = builder.GetInsertBlock();
  llvm::BasicBlock* query_bb =
      llvm::BasicBlock::Create(ctx, "class_query_non_primitive", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "class_query_merge", _interp->_llvm_func);
  builder.CreateCondBr(is_primitive, merge_bb, query_bb);

  builder.SetInsertPoint(query_bb);
  llvm::Value* query_result = nullptr;
  switch (id) {
    case vmIntrinsics::_isArray: {
      llvm::CallInst* layout_helper =
          _interp->call_java_op("jeandle.layout_helper", {klass});
      layout_helper->setName("class.query.layout_helper");
      query_result = builder.CreateICmpSLT(
          layout_helper, builder.getInt32(Klass::_lh_neutral_value), "class.query.is_array");
      break;
    }
    case vmIntrinsics::_isInterface:
    case vmIntrinsics::_isHidden: {
      llvm::Value* access_flags_addr = builder.CreateInBoundsGEP(
          builder.getInt8Ty(), klass,
          builder.getInt32(in_bytes(Klass::access_flags_offset())));
      llvm::Value* access_flags =
          builder.CreateLoad(builder.getInt32Ty(), access_flags_addr, "class.query.access_flags");
      const jint flag = id == vmIntrinsics::_isInterface
          ? static_cast<jint>(JVM_ACC_INTERFACE)
          : static_cast<jint>(JVM_ACC_IS_HIDDEN_CLASS);
      llvm::Value* masked = builder.CreateAnd(access_flags, builder.getInt32(flag));
      query_result = builder.CreateICmpNE(masked, builder.getInt32(0), "class.query.flag_set");
      break;
    }
    default:
      ShouldNotReachHere();
  }
  llvm::Value* non_primitive_result =
      builder.CreateZExt(query_result, builder.getInt32Ty(), "class.query.non_primitive_result");
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* query_end_bb = builder.GetInsertBlock();

  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  llvm::PHINode* result = builder.CreatePHI(builder.getInt32Ty(), 2, "class.query.result");
  result->addIncoming(builder.getInt32(0), primitive_bb);
  result->addIncoming(non_primitive_result, query_end_bb);
  _interp->_jvm->ipush(result);
  return true;
}

// Class.getModifiers() and Reflection.getClassAccessFlags(Class) both return
// an i32 flag word.  They share the primitive-mirror default and differ only
// in the Klass field they expose.  The Reflection method is static, so it must
// explicitly null-check its Class argument before directly loading from the
// nonnull mirror.
bool JeandleIntrinsicLowering::lower_class_flags_query(vmIntrinsics::ID id) {
  assert(id == vmIntrinsics::_getModifiers ||
         id == vmIntrinsics::_getClassAccessFlags,
         "unexpected Class flags intrinsic");

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Value* mirror = _interp->_jvm->raw_peek(0).value();

  if (id == vmIntrinsics::_getClassAccessFlags) {
    assert(_target->is_static(), "Reflection.getClassAccessFlags must be static");
    _interp->null_check(mirror);
  } else {
    assert(!_target->is_static(), "Class.getModifiers must be an instance method");
  }

  jint constant_result = 0;
  if (try_fold_constant_class_query(id, mirror, &constant_result)) {
    _interp->_jvm->apop();
    _interp->_jvm->ipush(builder.getInt32(constant_result));
    return true;
  }

  llvm::CallInst* klass =
      _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  klass->setName("class.flags.klass");
  _interp->_jvm->apop();

  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* is_primitive = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty),
      "class.flags.is_primitive");

  llvm::BasicBlock* primitive_bb = builder.GetInsertBlock();
  llvm::BasicBlock* flags_bb =
      llvm::BasicBlock::Create(ctx, "class_flags_non_primitive", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "class_flags_merge", _interp->_llvm_func);
  builder.CreateCondBr(is_primitive, merge_bb, flags_bb);

  builder.SetInsertPoint(flags_bb);
  const int flags_offset = id == vmIntrinsics::_getModifiers
      ? in_bytes(Klass::modifier_flags_offset())
      : in_bytes(Klass::access_flags_offset());
  const char* addr_name = id == vmIntrinsics::_getModifiers
      ? "class.modifiers.addr"
      : "class.access_flags.addr";
  const char* value_name = id == vmIntrinsics::_getModifiers
      ? "class.modifiers.value"
      : "class.access_flags.value";
  llvm::Value* flags_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass, builder.getInt32(flags_offset),
      addr_name);
  llvm::Value* flags =
      builder.CreateLoad(builder.getInt32Ty(), flags_addr, value_name);
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* flags_end_bb = builder.GetInsertBlock();

  static constexpr jint primitive_flags =
      static_cast<jint>(JVM_ACC_ABSTRACT) |
      static_cast<jint>(JVM_ACC_FINAL) |
      static_cast<jint>(JVM_ACC_PUBLIC);
  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  llvm::PHINode* result =
      builder.CreatePHI(builder.getInt32Ty(), 2, "class.flags.result");
  result->addIncoming(builder.getInt32(primitive_flags), primitive_bb);
  result->addIncoming(flags, flags_end_bb);
  _interp->_jvm->ipush(result);
  return true;
}

// Class.isInstance(Object) has a two-oop operand stack and a dynamic subtype
// check. Its slow path may update Klass::_secondary_super_cache, but the
// complete check is a GC leaf and cannot deopt or throw.
bool JeandleIntrinsicLowering::lower_class_is_instance() {
  assert(_target->intrinsic_id() == vmIntrinsics::_isInstance,
         "unexpected Class.isInstance intrinsic");

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // Physical JVM slot map, top to bottom:
  //   raw depth 0: Object argument
  //   raw depth 1: java.lang.Class receiver
  llvm::Value* object = _interp->_jvm->raw_peek(0).value();
  llvm::Value* mirror = _interp->_jvm->raw_peek(1).value();
  ciType* mirror_type = constant_class_type(mirror);
  if (mirror_type != nullptr && mirror_type->is_primitive_type()) {
    _interp->_jvm->apop();
    _interp->_jvm->apop();
    _interp->_jvm->ipush(builder.getInt32(0));
    return true;
  }
  if (mirror_type != nullptr && mirror_type->is_klass() && mirror_type->is_loaded()) {
    Klass* target_klass = reinterpret_cast<Klass*>(mirror_type->as_klass()->constant_encoding());
    Klass* object_klass = exact_java_klass_metadata(object);
    if (object_klass != nullptr && object_klass->is_subtype_of(target_klass)) {
      llvm::Value* is_null = builder.CreateICmpEQ(
          object, llvm::ConstantPointerNull::get(
                      llvm::cast<llvm::PointerType>(object->getType())),
          "class.is_instance.is_null");
      _interp->_jvm->apop();
      _interp->_jvm->apop();
      _interp->_jvm->ipush(builder.CreateZExt(
          builder.CreateNot(is_null), builder.getInt32Ty(),
          "class.is_instance.static_result"));
      return true;
    }
    if (object_klass != nullptr) {
      _interp->_jvm->apop();
      _interp->_jvm->apop();
      _interp->_jvm->ipush(builder.getInt32(0));
      return true;
    }
  }
  llvm::Value *klass =
      mirror_type != nullptr && mirror_type->is_klass() &&
              mirror_type->is_loaded()
          ? constant_klass_value(mirror_type->as_klass())
          : _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  klass->setName("class.is_instance.klass");

  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* is_primitive = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty),
      "class.is_instance.is_primitive");

  llvm::BasicBlock* primitive_bb = builder.GetInsertBlock();
  llvm::BasicBlock* instance_bb =
      llvm::BasicBlock::Create(ctx, "class_is_instance_non_primitive", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "class_is_instance_merge", _interp->_llvm_func);
  builder.CreateCondBr(is_primitive, merge_bb, instance_bb);

  builder.SetInsertPoint(instance_bb);
  llvm::Value* instance_result =
      _interp->call_java_op("jeandle.instanceof", {klass, object});
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* instance_end_bb = builder.GetInsertBlock();

  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  llvm::PHINode* result =
      builder.CreatePHI(builder.getInt32Ty(), 2, "class.is_instance.result");
  result->addIncoming(builder.getInt32(0), primitive_bb);
  result->addIncoming(instance_result, instance_end_bb);

  _interp->_jvm->apop(); // object
  _interp->_jvm->apop(); // Class receiver
  _interp->_jvm->ipush(result);
  return true;
}

// Class.getSuperclass() returns null for primitive/void mirrors, interfaces,
// and Object; arrays report Object.class. Other classes expose the mirror of
// Klass::_super through its OopHandle. The direct Java-heap load is relocated
// by any later statepoint if its result remains live.
bool JeandleIntrinsicLowering::lower_class_get_superclass() {
  assert(_target->intrinsic_id() == vmIntrinsics::_getSuperclass,
         "unexpected Class.getSuperclass intrinsic");

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Value* mirror = _interp->_jvm->raw_peek(0).value();
  ciType* mirror_type = constant_class_type(mirror);
  if (mirror_type != nullptr) {
    ciInstance* result_mirror = nullptr;
    if (mirror_type->is_klass() && mirror_type->is_loaded()) {
      ciKlass* klass = mirror_type->as_klass();
      if (klass->is_array_klass()) {
        result_mirror = ciEnv::current()->Object_klass()->java_mirror();
      } else if (!klass->is_interface() && klass->is_instance_klass()) {
        ciInstanceKlass* super = klass->as_instance_klass()->super();
        if (super != nullptr) {
          result_mirror = super->java_mirror();
        }
      }
    }
    llvm::Value* result = result_mirror == nullptr
        ? llvm::ConstantPointerNull::get(llvm::PointerType::get(
              ctx, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
        : _interp->constant_to_value(ciConstant(T_OBJECT, result_mirror)).value();
    _interp->_jvm->apop();
    _interp->_jvm->apush(result);
    return true;
  }

  llvm::CallInst *klass =
      _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  klass->setName("class.super.klass_from_mirror");

  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::PointerType* java_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Constant* null_klass = llvm::ConstantPointerNull::get(c_heap_ptr_ty);
  llvm::Constant* null_mirror = llvm::ConstantPointerNull::get(java_heap_ptr_ty);
  llvm::Value* is_primitive = builder.CreateICmpEQ(
      klass, null_klass, "class.super.is_primitive");

  llvm::BasicBlock* non_primitive_bb =
      llvm::BasicBlock::Create(ctx, "class_super_non_primitive", _interp->_llvm_func);
  llvm::BasicBlock* null_result_bb =
      llvm::BasicBlock::Create(ctx, "class_super_null", _interp->_llvm_func);
  llvm::BasicBlock* load_mirror_bb =
      llvm::BasicBlock::Create(ctx, "class_super_load_mirror", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "class_super_merge", _interp->_llvm_func);
  builder.CreateCondBr(is_primitive, null_result_bb, non_primitive_bb);

  builder.SetInsertPoint(non_primitive_bb);
  llvm::Value* access_flags_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass,
      builder.getInt32(in_bytes(Klass::access_flags_offset())),
      "class.super.access_flags_addr");
  llvm::Value* access_flags = builder.CreateLoad(
      builder.getInt32Ty(), access_flags_addr, "class.super.access_flags");
  llvm::Value* interface_bits = builder.CreateAnd(
      access_flags, builder.getInt32(static_cast<jint>(JVM_ACC_INTERFACE)),
      "class.super.interface_bits");
  llvm::Value* is_interface = builder.CreateICmpNE(
      interface_bits, builder.getInt32(0), "class.super.is_interface");

  llvm::CallInst* layout_helper =
      _interp->call_java_op("jeandle.layout_helper", {klass});
  layout_helper->setName("class.super.layout_helper");
  llvm::Value* is_array = builder.CreateICmpSLT(
      layout_helper, builder.getInt32(Klass::_lh_neutral_value),
      "class.super.is_array");

  // Match C2's generate_interface_guard/generate_array_guard shape: do not
  // load Klass::_super on interface or array paths.  Besides avoiding an
  // unnecessary memory access, this keeps the common ordinary-class path
  // independent from the special-case result selection.
  llvm::BasicBlock* array_bb =
      llvm::BasicBlock::Create(ctx, "class_super_array", _interp->_llvm_func);
  llvm::BasicBlock* ordinary_bb =
      llvm::BasicBlock::Create(ctx, "class_super_ordinary", _interp->_llvm_func);
  builder.CreateCondBr(is_interface, null_result_bb, array_bb);

  builder.SetInsertPoint(array_bb);
  llvm::Value* object_klass = builder.CreateIntToPtr(
      builder.getInt64(reinterpret_cast<intptr_t>(vmClasses::Object_klass())),
      c_heap_ptr_ty, "class.super.object_klass");
  builder.CreateCondBr(is_array, load_mirror_bb, ordinary_bb);

  builder.SetInsertPoint(ordinary_bb);
  llvm::Value* super_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass,
      builder.getInt32(in_bytes(Klass::super_offset())),
      "class.super.addr");
  llvm::Value* super_klass =
      builder.CreateLoad(c_heap_ptr_ty, super_addr, "class.super.klass");
  llvm::Value* has_super = builder.CreateICmpNE(
      super_klass, null_klass, "class.super.has_super");
  builder.CreateCondBr(has_super, load_mirror_bb, null_result_bb);

  builder.SetInsertPoint(load_mirror_bb);
  llvm::PHINode* target_klass = builder.CreatePHI(c_heap_ptr_ty, 2,
                                                  "class.super.target_klass");
  target_klass->addIncoming(object_klass, array_bb);
  target_klass->addIncoming(super_klass, ordinary_bb);
  llvm::Value* superclass_mirror =
      emit_direct_mirror_from_klass(target_klass, "class.super.mirror");
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* mirror_end_bb = builder.GetInsertBlock();

  builder.SetInsertPoint(null_result_bb);
  builder.CreateBr(merge_bb);

  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  llvm::PHINode* result =
      builder.CreatePHI(java_heap_ptr_ty, 2, "class.super.result");
  result->addIncoming(null_mirror, null_result_bb);
  result->addIncoming(superclass_mirror, mirror_end_bb);

  _interp->_jvm->apop();
  _interp->_jvm->apush(result);
  return true;
}

bool JeandleIntrinsicLowering::lower_get_object_size() {
  assert(!_target->is_static(), "getObjectSize0 is an instance native method");
  assert(_target->signature()->count() == 2, "unexpected getObjectSize0 signature");

  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // Logical stack, top first: objectToSize, nativeAgent (long), receiver.
  // Physical raw slots: objectToSize, long placeholder, long value, receiver.
  // Preserve the complete pre-invoke state through the null-check trap.
  llvm::Value* object = _interp->_jvm->peek_value(0).value();
  _interp->null_check(object);

  // These phase-1 JavaOps are compiler pseudo-operations, not runtime calls.
  // Keeping the canonical operations allows metadata constant folding and
  // preserves the VM load attributes defined in template.ll.
  llvm::CallInst* klass = _interp->call_java_op("jeandle.load_klass", {object});
  klass->setName("get_object_size.klass");
  llvm::CallInst* layout = _interp->call_java_op("jeandle.layout_helper", {klass});
  layout->setName("get_object_size.layout");

  // Nothing below can safepoint, throw, or deopt. nativeAgent is unused,
  // matching C1 and C2's getObjectSize intrinsic.
  _interp->_jvm->apop();
  _interp->_jvm->lpop();
  _interp->_jvm->apop();

  llvm::Function* function = builder.GetInsertBlock()->getParent();
  llvm::BasicBlock* instance = llvm::BasicBlock::Create(
      *_interp->_context, "get_object_size.instance", function);
  llvm::BasicBlock* array = llvm::BasicBlock::Create(
      *_interp->_context, "get_object_size.array", function);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(
      *_interp->_context, "get_object_size.done", function);
  builder.CreateCondBr(
      builder.CreateICmpSLT(layout, builder.getInt32(Klass::_lh_neutral_value)),
      array, instance);

  // C1 and C2 deliberately return the layout-helper approximation for all
  // instances, including layouts carrying the allocation slow-path bit.
  builder.SetInsertPoint(instance);
  llvm::Value* instance_size32 = builder.CreateAnd(
      layout, builder.getInt32(~(jint)right_n_bits(LogBytesPerLong)),
      "get_object_size.instance.bytes");
  llvm::Value* instance_size = builder.CreateZExt(
      instance_size32, builder.getInt64Ty(), "get_object_size.instance.result");
  builder.CreateBr(done);
  llvm::BasicBlock* instance_end = builder.GetInsertBlock();

  // Array size is align(header + (length << log2(element size))).
  builder.SetInsertPoint(array);
  llvm::CallInst* length32 = _interp->call_java_op("jeandle.arraylength", {object});
  length32->setName("get_object_size.length");
  llvm::Value* length = builder.CreateZExt(
      length32, builder.getInt64Ty(), "get_object_size.length64");
  llvm::Value* header32 = builder.CreateAnd(
      builder.CreateLShr(layout, builder.getInt32(Klass::_lh_header_size_shift)),
      builder.getInt32(Klass::_lh_header_size_mask), "get_object_size.header");
  llvm::Value* header = builder.CreateZExt(header32, builder.getInt64Ty());
  assert(Klass::_lh_log2_element_size_shift == 0, "use shift in place");
  llvm::Value* element_shift32 = builder.CreateAnd(
      layout, builder.getInt32(Klass::_lh_log2_element_size_mask),
      "get_object_size.element.shift");
  llvm::Value* element_shift = builder.CreateZExt(
      element_shift32, builder.getInt64Ty());
  llvm::Value* body = builder.CreateShl(
      length, element_shift, "get_object_size.body");
  const jlong alignment_mask = static_cast<jlong>(MinObjAlignmentInBytesMask);
  llvm::Value* array_size = builder.CreateAnd(
      builder.CreateAdd(builder.CreateAdd(body, header),
                        builder.getInt64(alignment_mask)),
      builder.getInt64(~alignment_mask), "get_object_size.array.result");
  builder.CreateBr(done);
  llvm::BasicBlock* array_end = builder.GetInsertBlock();

  builder.SetInsertPoint(done);
  _interp->_block->set_tail_llvm_block(done);
  llvm::PHINode* result = builder.CreatePHI(
      builder.getInt64Ty(), 2, "get_object_size.result");
  result->addIncoming(instance_size, instance_end);
  result->addIncoming(array_size, array_end);
  _interp->_jvm->lpush(result);
  return true;
}

// ---- lower_llvm_bitcast ----
bool JeandleIntrinsicLowering::lower_llvm_bitcast() {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  BasicType src_type = sig->type_at(0)->basic_type();
  BasicType dst_type = sig->return_type()->basic_type();

  llvm::Value* src = _interp->_jvm->pop(src_type);
  llvm::Value* cast = builder.CreateBitCast(src, JeandleType::java2llvm(dst_type, ctx));
  _interp->_jvm->push(dst_type, cast);
  return true;
}

// ---- lower_fp_to_bits_canonical ----
// Float.floatToIntBits(float) / Double.doubleToLongBits(double): like the raw
// bitcast variants, but NaN inputs are canonicalized to the single NaN bit
// pattern Java specifies, instead of preserving whatever NaN payload/sign the
// input happened to carry. Mirrors C2's LibraryCallKit::inline_fp_conversions:
// arg != arg (unordered compare) is true only for NaN; select between the
// canonical NaN constant and the plain bitcast result.
bool JeandleIntrinsicLowering::lower_fp_to_bits_canonical(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_double = (id == vmIntrinsics::_doubleToLongBits);

  if (is_double) {
    llvm::Value* arg = _interp->_jvm->dpop();
    llvm::Value* is_nan = builder.CreateFCmpUNE(arg, arg);
    llvm::Value* bits = builder.CreateBitCast(arg, builder.getInt64Ty());
    llvm::Value* canonical_nan = builder.getInt64(0x7ff8000000000000ULL);
    _interp->_jvm->lpush(builder.CreateSelect(is_nan, canonical_nan, bits));
  } else {
    llvm::Value* arg = _interp->_jvm->fpop();
    llvm::Value* is_nan = builder.CreateFCmpUNE(arg, arg);
    llvm::Value* bits = builder.CreateBitCast(arg, builder.getInt32Ty());
    llvm::Value* canonical_nan = builder.getInt32(0x7fc00000);
    _interp->_jvm->ipush(builder.CreateSelect(is_nan, canonical_nan, bits));
  }
  return true;
}

// ---- lower_float16_convert ----
// Float.floatToFloat16(float) / Float.float16ToFloat(short): float16 values
// are carried as the raw bit pattern in a Java short, never as a distinct
// value type, so the non-NaN path is a real narrowing/widening fp convert
// (fptrunc/fpext through LLVM's `half` type) plus a bitcast to move between
// `half` and the integer bits -- not a plain bitcast like the 32/64-bit
// variants above.
//
// NaN results must not come from fptrunc/fpext. On machines where
// VM_Version::supports_float16() holds (the only ones where these
// intrinsics fire, mirroring the interpreter/C1/C2 gating), the template
// interpreter entries execute the hardware conversion (x86 vcvtph2ps/
// vcvtps2ph), which quiets signaling NaNs while preserving the payload.
// LLVM, however, treats NaN payloads as unspecified: InstCombine folds
// `fptrunc(fpext x)` to `x`, so a compiled
// floatToFloat16(float16ToFloat(x)) round trip returns sNaN bit patterns
// unchanged where the interpreter quiets them.
// compiler/intrinsics/float16/Binary16ConversionNaN.java compares exactly
// that round trip bit-for-bit against interpreter results for every 16-bit
// NaN pattern, so the compiled NaN behavior has to be pinned down: NaNs
// take an explicit integer-arithmetic path implementing the same
// quiet-and-preserve-payload semantics as the hardware conversion:
//   float16ToFloat: f32 = (sign16 << 16) | 0x7f800000 | ((sig10|0x200)<<13)
//   floatToFloat16: f16 = sign16 | 0x7e00 | ((f32bits >> 13) & 0x1ff)
// Encoding this in integer ops (selected on isNaN) makes it bit-exact by
// construction on every target -- LLVM cannot legally alter it, the fp
// convert only feeds the non-NaN result, and folding fptrunc(fpext x) is
// value-exact for non-NaN halves. The common case stays a single hardware
// conversion instruction plus a compare/select.
//
// Java short is a computational-int type on the JVM stack (like the
// reverseBytes_s/_c narrow variants above), so the i16 bit pattern is
// sign-extended to i32 on push, and truncated back from the popped i32 on the
// way in.
bool JeandleIntrinsicLowering::lower_float16_convert(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool to_f16 = (id == vmIntrinsics::_floatToFloat16);

  if (to_f16) {
    llvm::Value* arg = _interp->_jvm->fpop();
    // Non-NaN path: IEEE-754 narrowing conversion.
    llvm::Value* half = builder.CreateFPTrunc(arg, builder.getHalfTy());
    llvm::Value* conv_bits = builder.CreateBitCast(half, builder.getInt16Ty());
    // NaN path: quiet and preserve the top payload bits, matching the
    // hardware conversion the interpreter entry executes.
    llvm::Value* bits = builder.CreateBitCast(arg, builder.getInt32Ty());
    llvm::Value* nan16 = builder.CreateLShr(
        builder.CreateAnd(bits, 0x80000000), 16);
    nan16 = builder.CreateOr(nan16, 0x7e00);
    nan16 = builder.CreateOr(nan16,
        builder.CreateAnd(builder.CreateLShr(bits, 13), 0x1ff));
    llvm::Value* is_nan = builder.CreateFCmpUNO(arg, arg);
    llvm::Value* res = builder.CreateSelect(
        is_nan, builder.CreateTrunc(nan16, builder.getInt16Ty()), conv_bits);
    _interp->_jvm->ipush(builder.CreateSExt(res, builder.getInt32Ty()));
  } else {
    llvm::Value* arg = _interp->_jvm->ipop();
    llvm::Value* bits = builder.CreateTrunc(arg, builder.getInt16Ty());
    // Non-NaN path: IEEE-754 widening conversion (value-exact).
    llvm::Value* half = builder.CreateBitCast(bits, builder.getHalfTy());
    llvm::Value* conv = builder.CreateFPExt(half, builder.getFloatTy());
    // NaN path: quiet and preserve the payload, matching the hardware
    // conversion the interpreter entry executes.
    llvm::Value* w = builder.CreateZExt(bits, builder.getInt32Ty());
    llvm::Value* nan32 = builder.CreateShl(builder.CreateAnd(w, 0x8000), 16);
    nan32 = builder.CreateOr(nan32, 0x7f800000);
    nan32 = builder.CreateOr(nan32,
        builder.CreateShl(builder.CreateOr(builder.CreateAnd(w, 0x03ff),
                                           0x200), 13));
    llvm::Value* nan_f = builder.CreateBitCast(nan32, builder.getFloatTy());
    // NaN <=> all-ones exponent and nonzero significand.
    llvm::Value* is_nan = builder.CreateICmpUGT(
        builder.CreateAnd(w, 0x7fff), builder.getInt32(0x7c00));
    _interp->_jvm->fpush(builder.CreateSelect(is_nan, nan_f, conv));
  }
  return true;
}

// ---- lower_llvm_fence ----
bool JeandleIntrinsicLowering::lower_llvm_fence(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::AtomicOrdering ordering;
  switch (id) {
    case vmIntrinsics::_loadFence:  ordering = llvm::AtomicOrdering::Acquire;                break;
    case vmIntrinsics::_storeFence: ordering = llvm::AtomicOrdering::Release;                break;
    case vmIntrinsics::_fullFence:  ordering = llvm::AtomicOrdering::SequentiallyConsistent; break;
    default:
      ShouldNotReachHere();
      return false;
  }
  _interp->_jvm->apop(); // Unsafe receiver
  builder.CreateFence(ordering);
  return true;
}

// ---- lower_preconditions_check_index ----
bool JeandleIntrinsicLowering::lower_preconditions_check_index(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  int cur_bci = _interp->_bytecodes.cur_bci();
  bool is_long = id == vmIntrinsics::_Preconditions_checkLongIndex;

  // Peek logical values so the operand stack stays intact for the deopt bundle
  // captured by uncommon_trap; the real pops are deferred to the pass path.
  llvm::Value* exception_factory = _interp->_jvm->peek_value(0).value();
  llvm::Value* length            = _interp->_jvm->peek_value(1).value();
  llvm::Value* index             = _interp->_jvm->peek_value(2).value();
  (void)exception_factory;

  llvm::Type* integer_ty = is_long ? llvm::Type::getInt64Ty(ctx)
                                   : llvm::Type::getInt32Ty(ctx);
  llvm::Value* zero = llvm::ConstantInt::get(integer_ty, 0);

  llvm::BasicBlock* pass = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_pass", _interp->_llvm_func);
  llvm::BasicBlock* mid  = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_mid", _interp->_llvm_func);
  llvm::BasicBlock* fail_pre = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_fail_pre", _interp->_llvm_func);
  llvm::BasicBlock* fail_range = llvm::BasicBlock::Create(ctx,
      "bci_" + std::to_string(cur_bci) + "_checkIndex_fail_range", _interp->_llvm_func);

  llvm::Value* len_neg = builder.CreateICmp(llvm::CmpInst::ICMP_SLT, length, zero,
                                            "checkIndex.len_neg");
  builder.CreateCondBr(len_neg, fail_pre, mid);

  builder.SetInsertPoint(mid);
  llvm::Value* idx_oob = builder.CreateICmp(llvm::CmpInst::ICMP_UGE, index, length,
                                            "checkIndex.idx_oob");
  builder.CreateCondBr(idx_oob, fail_range, pass);

  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_make_not_entrant, fail_pre);
  _interp->uncommon_trap(Deoptimization::Reason_range_check,
                         Deoptimization::Action_make_not_entrant, fail_range);

  builder.SetInsertPoint(pass);
  _interp->_block->set_tail_llvm_block(pass);
  _interp->_jvm->apop(); // exception_factory
  if (is_long) {
    _interp->_jvm->lpop(); // length
    _interp->_jvm->lpop(); // index
  } else {
    _interp->_jvm->ipop(); // length
    _interp->_jvm->ipop(); // index
  }

  if (is_long) {
    _interp->_jvm->lpush(index);
  } else {
    _interp->_jvm->ipush(index);
  }
  return true;
}

// ---- lower_compare_unsigned (moved from try_lower_intrinsic) ----
bool JeandleIntrinsicLowering::lower_compare_unsigned(vmIntrinsics::ID id) {
  bool is_long = (id == vmIntrinsics::_compareUnsigned_l);

  llvm::Value* arg2 = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* arg1 = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();

  llvm::Value* is_less = _interp->_ir_builder.CreateICmpULT(arg1, arg2);
  llvm::Value* is_greater = _interp->_ir_builder.CreateICmpUGT(arg1, arg2);

  llvm::Value* select_greater = _interp->_ir_builder.CreateSelect(
      is_greater, JeandleType::int_const(_interp->_ir_builder, 1),
      JeandleType::int_const(_interp->_ir_builder, 0));

  llvm::Value* result = _interp->_ir_builder.CreateSelect(
      is_less, JeandleType::int_const(_interp->_ir_builder, -1), select_greater);
  _interp->_jvm->ipush(result);
  return true;
}

// ---- lower_bit_count ----
// Integer.bitCount(int) -> llvm.ctpop.i32 -> i32        (type matches, no truncate)
// Long.bitCount(long)   -> llvm.ctpop.i64 -> i64 -> trunc i32  (type mismatch: Java returns int)
bool JeandleIntrinsicLowering::lower_bit_count(vmIntrinsics::ID id) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_long = (id == vmIntrinsics::_bitCount_l);

  llvm::Value* arg = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Type* arg_ty = arg->getType(); // i32 or i64

  // llvm.ctpop requires return type == argument type.
  llvm::CallInst* call = builder.CreateIntrinsic(arg_ty, llvm::Intrinsic::ctpop, {arg});

  if (is_long) {
    // Long.bitCount(long) returns int in Java, but llvm.ctpop.i64 returns i64.
    // Truncate the result to i32.
    _interp->_jvm->ipush(builder.CreateTrunc(call, JeandleType::java2llvm(BasicType::T_INT, ctx)));
  } else {
    _interp->_jvm->ipush(call);
  }
  return true;
}

// ---- lower_count_zeros ----
// numberOfLeadingZeros  -> llvm.ctlz
// numberOfTrailingZeros -> llvm.cttz
// The _l variants return int in Java but llvm.ctlz/cttz.i64 returns i64, so trunc.
bool JeandleIntrinsicLowering::lower_count_zeros(vmIntrinsics::ID id,
                                                 llvm::Intrinsic::ID llvm_id) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_long = (id == vmIntrinsics::_numberOfLeadingZeros_l ||
                  id == vmIntrinsics::_numberOfTrailingZeros_l);

  llvm::Value* arg = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Type* arg_ty = arg->getType(); // i32 or i64

  // ctlz/cttz take a trailing i1 is_zero_poison flag; pass false so that
  // numberOf{Leading,Trailing}Zeros(0) is the bit width (32/64), not poison.
  llvm::CallInst* call =
      builder.CreateIntrinsic(arg_ty, llvm_id, {arg, builder.getInt1(false)});

  if (is_long) {
    _interp->_jvm->ipush(builder.CreateTrunc(call, JeandleType::java2llvm(BasicType::T_INT, ctx)));
  } else {
    _interp->_jvm->ipush(call);
  }
  return true;
}

// ---- lower_reverse_bytes_narrow ----
// Character.reverseBytes(char) / Short.reverseBytes(short). The value sits on
// the operand stack as a computational int, but only the low 16 bits are
// meaningful. Swap those bits as i16, then restore Java's zero/sign extension.
bool JeandleIntrinsicLowering::lower_reverse_bytes_narrow(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_char = (id == vmIntrinsics::_reverseBytes_c);

  llvm::Value* arg = _interp->_jvm->ipop();
  llvm::Value* narrow = builder.CreateTrunc(arg, builder.getInt16Ty());
  llvm::Value* swapped =
      builder.CreateIntrinsic(builder.getInt16Ty(), llvm::Intrinsic::bswap, {narrow});
  llvm::Value* result = is_char ? builder.CreateZExt(swapped, builder.getInt32Ty())
                                : builder.CreateSExt(swapped, builder.getInt32Ty());
  _interp->_jvm->ipush(result);
  return true;
}

// ---- lower_new_array ----
//
// Generates inline IR for Array.newInstance(Class<?>, int):
//   1. Null-check mirror  →  slow path (NPE)
//   2. Acquire-load klass from mirror  →  if null → slow path
//   3. Fast path: call unified jeandle.new_array(klass, length)
//   4. Slow path: call new_array_from_mirror(mirror, length, thread)
//   5. PHI merge
bool JeandleIntrinsicLowering::lower_new_array() {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Module& module = _interp->_module;

  // Pop mirror (Class<?>) and length (int) from JVM stack.
  // Array.newInstance(Class<?>, int) is a static method.
  llvm::Value* length = _interp->_jvm->ipop();
  llvm::Value* mirror = _interp->_jvm->apop();

  llvm::PointerType* java_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);

  // Create basic blocks for the fast/slow dispatch.
  llvm::BasicBlock* klass_load_bb =
      llvm::BasicBlock::Create(ctx, "newarray_klass_load", _interp->_llvm_func);
  llvm::BasicBlock* fast_bb =
      llvm::BasicBlock::Create(ctx, "newarray_fast", _interp->_llvm_func);
  llvm::BasicBlock* slow_bb =
      llvm::BasicBlock::Create(ctx, "newarray_slow", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "newarray_merge", _interp->_llvm_func);

  // Null guard: null mirror → slow path (will throw NPE via Reflection).
  llvm::Value* mirror_is_null = builder.CreateICmpEQ(
      mirror, llvm::ConstantPointerNull::get(java_heap_ptr_ty));
  builder.CreateCondBr(mirror_is_null, slow_bb, klass_load_bb);

  // Klass-load block: acquire-load the cached array_klass from the mirror.
  builder.SetInsertPoint(klass_load_bb);
  llvm::GlobalVariable* offset_gv =
      module.getGlobalVariable("java_lang_Class.array_klass_offset", /*AllowInternal=*/true);
  llvm::Value* offset = builder.CreateLoad(builder.getInt32Ty(), offset_gv);
  llvm::Value* klass_field_addr =
      builder.CreateInBoundsGEP(builder.getInt8Ty(), mirror, offset);
  llvm::LoadInst* klass = builder.CreateLoad(c_heap_ptr_ty, klass_field_addr);
  klass->setAtomic(llvm::AtomicOrdering::Acquire);
  klass->setAlignment(llvm::Align(sizeof(void*)));
  llvm::Value* klass_is_null = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty));
  builder.CreateCondBr(klass_is_null, slow_bb, fast_bb);

  // Fast path: klass resolved → call unified jeandle.new_array.
  // Unlike the bytecode path, the array klass is loaded from the mirror at runtime, so the
  // element layout isn't a compile-time constant. Decode it from Klass::layout_helper the way
  // C2's GraphKit::new_array does for reflective sites:
  //   base_offset = (lh >> _lh_header_size_shift) & _lh_header_size_mask
  //   log2_esize  = lh & 0x1f   (_lh_log2_element_size_shift == 0; masked < 32 for the shift,
  //                              valid l2esz is <= LogBytesPerLong)
  builder.SetInsertPoint(fast_bb);
  llvm::Value* layout_helper = _interp->call_java_op("jeandle.layout_helper", {klass});
  llvm::Value* base_offset = builder.CreateAnd(
      builder.CreateLShr(layout_helper, builder.getInt32(Klass::_lh_header_size_shift)),
      builder.getInt32(Klass::_lh_header_size_mask));
  llvm::Value* log2_esize = builder.CreateAnd(layout_helper, builder.getInt32(0x1f));
  llvm::Value* size_in_bytes = _interp->emit_array_size_in_bytes(length, log2_esize, base_offset);
  // Fast-path length cap, mirroring C2's reflective array path: the unscaled
  // FastAllocateSizeLimit bounds the byte size to <= FastAllocateSizeLimit << LogBytesPerLong
  // (~1MB) for any element type, so size_in_bytes cannot overflow i32. Larger reflective arrays
  // fall to the slow path.
  // TODO: this cap is a flat limit on element count, applied the same way regardless of element
  // type. Because it isn't scaled by element size, it effectively assumes every element is 8
  // bytes wide, so arrays of smaller elements (byte[], or reference arrays under compressed oops)
  // fall back to the slow path far earlier than their real byte size requires. The constant-klass
  // bytecode path (emit_jeandle_newarray) already scales it by element size:
  //     FastAllocateSizeLimit << (LogBytesPerLong - log2_esize)
  // We can do the same here using the log2_esize decoded just above -- one shift, covers every
  // element type, no extra branching.
  //
  // Going further like C2 (speculatively assuming a reference array so the whole layout folds to
  // constants) isn't worth it here: C2's real gain comes from optimizing the code after the
  // allocation -- folding a trailing arraycopy's address math and deleting the now-redundant
  // zeroing. Neither is reachable for us: the zeroing lives inside the opaque jeandle.new_array
  // helper, and this reflection site just returns the array with no copy to merge with.
  //
  // If that after-allocation win is ever worth pursuing, the path forward is not C2's guard but
  // making the zeroing removable at the call site: expose it as stores the optimizer can see (or
  // flag the region as already-zeroed) so a following overwrite can delete it, and let the
  // allocation fuse with the arraycopy.
  llvm::Value* length_limit = builder.getInt32((int)FastAllocateSizeLimit);

  static constexpr CallSiteAttributeMetadata fast_attrs =
      {CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE};
  llvm::Function* new_array_op = module.getFunction("jeandle.new_array");
  llvm::CallBase* fast_call =
      emit_callsite(new_array_op, llvm::CallingConv::Hotspot_JIT,
                    {klass, length, size_in_bytes, base_offset, length_limit}, fast_attrs);
  // emit_callsite with exception edge moves builder to a new normal_dest block.
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* fast_normal_bb = builder.GetInsertBlock();

  // Slow path: klass not cached or mirror is null → call new_array_from_mirror.
  builder.SetInsertPoint(slow_bb);
  llvm::Function* current_thread_fn = module.getFunction("jeandle.current_thread");
  llvm::CallInst* current_thread = builder.CreateCall(current_thread_fn);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  static constexpr CallSiteAttributeMetadata slow_attrs =
      {CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE};
  llvm::CallBase* slow_call = emit_callsite(
      JeandleRuntimeRoutine::new_array_from_mirror_callee(module),
      llvm::CallingConv::Hotspot_JIT,
      {mirror, length, current_thread}, slow_attrs);
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* slow_normal_bb = builder.GetInsertBlock();

  // Merge results via PHI.
  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  llvm::PHINode* result = builder.CreatePHI(java_heap_ptr_ty, 2, "newarray.result");
  result->addIncoming(fast_call, fast_normal_bb);
  result->addIncoming(slow_call, slow_normal_bb);

  _interp->_jvm->apush(result);
  return true;
}

bool JeandleIntrinsicLowering::lower_unsafe_allocate_instance() {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Module& m = _interp->_module;

  // Keep the invoke operands in the JVM state until every throwing or
  // safepointing call has captured its deopt bundle.
  llvm::Value* mirror = _interp->_jvm->raw_peek(0).value();
  llvm::Value* unsafe = _interp->_jvm->raw_peek(1).value();

  // Match normal invokevirtual ordering: validate the receiver before the
  // explicit Class<?> argument.
  _interp->null_check(unsafe);
  _interp->null_check(mirror);

  llvm::PointerType* c_heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);

  llvm::BasicBlock* allocation_check_bb =
      llvm::BasicBlock::Create(ctx, "unsafe_allocate_check", _interp->_llvm_func);
  llvm::BasicBlock* primitive_trap_bb = llvm::BasicBlock::Create(
      ctx, "unsafe_allocate_primitive_trap", _interp->_llvm_func);

  // TODO: Fold constant mirrors, their Klass, and the dependent layout and
  // initialization loads together in an LLVM pass.
  llvm::Value* klass_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), mirror,
      builder.getInt32(java_lang_Class::klass_offset()));
  llvm::Value* klass = builder.CreateLoad(
      c_heap_ptr_ty, klass_addr, "unsafe_allocate.klass");
  llvm::Value* klass_is_null = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty));
  builder.CreateCondBr(klass_is_null, primitive_trap_bb, allocation_check_bb);
  _interp->uncommon_trap(Deoptimization::Reason_null_check,
                         Deoptimization::Action_make_not_entrant,
                         primitive_trap_bb,
                         true /* should_reexecute */);

  builder.SetInsertPoint(allocation_check_bb);
  llvm::Value* layout_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass,
      builder.getInt32(in_bytes(Klass::layout_helper_offset())));
  llvm::Value* layout = builder.CreateLoad(
      builder.getInt32Ty(), layout_addr, "unsafe_allocate.layout");

  llvm::Value* init_state_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass,
      builder.getInt32(in_bytes(InstanceKlass::init_state_offset())));
  llvm::Value* init_state = builder.CreateLoad(
      builder.getInt8Ty(), init_state_addr, "unsafe_allocate.init_state");
  llvm::Value* init_delta = builder.CreateSub(
      builder.CreateZExt(init_state, builder.getInt32Ty()),
      builder.getInt32(InstanceKlass::fully_initialized),
      "unsafe_allocate.init_delta");

  // Match GraphKit::new_instance's reflective slow test. This also routes
  // arrays, interfaces, abstract classes and java.lang.Class to the runtime.
  llvm::Value* slow_path_bits = builder.CreateAnd(
      layout, builder.getInt32(Klass::_lh_instance_slow_path_bit),
      "unsafe_allocate.slow_path_bits");
  llvm::Value* slow_test = builder.CreateOr(
      slow_path_bits, init_delta, "unsafe_allocate.slow_test");
  llvm::Value* needs_slow_path = builder.CreateICmpNE(
      slow_test, builder.getInt32(0));

  // The layout helper stores the aligned instance size in bytes; its low bits
  // contain allocation flags and must not be passed to the TLAB allocator.
  llvm::Value* size_in_bytes = builder.CreateAnd(
      layout, builder.getInt32(~(jint)right_n_bits(LogBytesPerLong)),
      "unsafe_allocate.size_in_bytes");
  llvm::Function* new_instance_op = m.getFunction("jeandle.new_instance");
  assert(new_instance_op != nullptr, "jeandle.new_instance JavaOp must exist");
  // All reexecuting checks have completed. The allocation slow path must use
  // the post-invoke state, so do not keep the Unsafe receiver or Class mirror
  // live in its deopt bundle.
  _interp->_jvm->apop(); // mirror
  _interp->_jvm->apop(); // Unsafe receiver

  static constexpr CallSiteAttributeMetadata allocation_attrs =
      {CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE};
  // The JavaOp routes this initial slow test and TLAB exhaustion to one
  // shared new-instance runtime call.
  llvm::CallBase* result = emit_callsite(
      new_instance_op, llvm::CallingConv::Hotspot_JIT,
      {klass, size_in_bytes, needs_slow_path}, allocation_attrs);

  _interp->_jvm->apush(result);
  return true;
}

// ---- lower_vectorized_mismatch ----
//
// Use LLVM IR for byte ranges too small to benefit from the platform stub. The
// larger ranges retain the platform StubRoutines implementation, including its
// vector tiers where available.
bool JeandleIntrinsicLowering::lower_vectorized_mismatch() {
  if (!UseVectorizedMismatchIntrinsic ||
      JeandleRuntimeRoutine::find_routine_entry("StubRoutines_vectorizedMismatch") == nullptr) {
    return false;
  }

  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Type* i8 = b.getInt8Ty();

  // Operand stack, top to bottom:
  //   scale, length, bOffset, b, aOffset, a
  // All support checks above must complete before these values are consumed.
  llvm::Value* scale = _interp->_jvm->ipop();
  llvm::Value* length = _interp->_jvm->ipop();
  llvm::Value* b_offset = _interp->_jvm->lpop();
  llvm::Value* b_obj = _interp->_jvm->apop();
  llvm::Value* a_offset = _interp->_jvm->lpop();
  llvm::Value* a_obj = _interp->_jvm->apop();

  llvm::Value* a_addr = b.CreateGEP(i8, a_obj, a_offset, "mismatch_a_addr");
  llvm::Value* b_addr = b.CreateGEP(i8, b_obj, b_offset, "mismatch_b_addr");

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Type* i32 = b.getInt32Ty();
  llvm::Type* i64 = b.getInt64Ty();
  llvm::Function* f = _interp->_llvm_func;

  static constexpr unsigned small_path_limit = 16;

  // Compute in i64 so a large i32 length shifted by scale cannot wrap into an
  // inline tier. The VM guarantees scale is a valid element-size logarithm.
  llvm::Value* scale64 = b.CreateZExt(scale, i64, "mismatch_scale64");
  llvm::Value* byte_length = b.CreateShl(b.CreateZExt(length, i64), scale64,
                                         "mismatch_byte_length");
  llvm::Value* is_small = b.CreateICmpULT(
      byte_length, llvm::ConstantInt::get(i64, small_path_limit),
      "mismatch_inline_small");

  const uint64_t medium_path_limit =
      static_cast<uint64_t>(ArrayOperationPartialInlineSize);
  const bool use_medium_path = supports_vectorized_mismatch_medium_path() &&
                               medium_path_limit >= small_path_limit;
  llvm::BasicBlock* small_bb = llvm::BasicBlock::Create(ctx, "mismatch_inline_small", f);
  llvm::BasicBlock* dispatch_bb = llvm::BasicBlock::Create(ctx, "mismatch_dispatch_medium", f);
  llvm::BasicBlock* medium_bb = use_medium_path
      ? llvm::BasicBlock::Create(ctx, "mismatch_inline_medium", f) : nullptr;
  llvm::BasicBlock* stub_bb = llvm::BasicBlock::Create(ctx, "mismatch_stub", f);
  llvm::BasicBlock* done_bb = llvm::BasicBlock::Create(ctx, "mismatch_done", f);
  b.CreateCondBr(is_small, small_bb, dispatch_bb);

  // Tier 1: inline scalar IR for ranges shorter than 16 bytes.
  b.SetInsertPoint(small_bb);
  llvm::Value* small_result = emit_vectorized_mismatch_small(a_addr, b_addr, byte_length, scale64);
  llvm::BasicBlock* small_done_bb = b.GetInsertBlock();
  b.CreateBr(done_bb);

  llvm::Value* medium_result = nullptr;
  llvm::BasicBlock* medium_done_bb = nullptr;

  // Tier 2 is available only when the target can lower the fixed-width vector
  // IR efficiently. Unsupported targets skip directly to the platform stub.
  b.SetInsertPoint(dispatch_bb);
  if (use_medium_path) {
    llvm::Value* is_medium = b.CreateICmpULE(
        byte_length, llvm::ConstantInt::get(i64, medium_path_limit),
        "mismatch_inline_medium");
    b.CreateCondBr(is_medium, medium_bb, stub_bb);

    // Tier 2: inline 128-bit vector IR up to ArrayOperationPartialInlineSize.
    b.SetInsertPoint(medium_bb);
    medium_result = emit_vectorized_mismatch_medium(a_addr, b_addr, byte_length, scale64);
    medium_done_bb = b.GetInsertBlock();
    b.CreateBr(done_bb);
  } else {
    b.CreateBr(stub_bb);
  }

  // Tier 3: use the platform stub for large ranges, or as the fallback when
  // fixed-width vector IR is not enabled on the target.
  b.SetInsertPoint(stub_bb);
  static constexpr CallSiteAttributeMetadata attrs = {CTRL_NONE, MEM_READ};
  llvm::CallBase* call = emit_callsite(
      JeandleRuntimeRoutine::StubRoutines_vectorizedMismatch_callee(_interp->_module),
      llvm::CallingConv::C, {a_addr, b_addr, length, scale}, attrs,
      /*is_gc_leaf_entry=*/true);
  llvm::BasicBlock* stub_done_bb = b.GetInsertBlock();
  b.CreateBr(done_bb);

  // All enabled tiers produce the same element-index result.
  b.SetInsertPoint(done_bb);
  llvm::PHINode* result = b.CreatePHI(i32, use_medium_path ? 3 : 2, "mismatch_result");
  result->addIncoming(small_result, small_done_bb);
  if (use_medium_path) {
    result->addIncoming(medium_result, medium_done_bb);
  }
  result->addIncoming(call, stub_done_bb);
  _interp->_block->set_tail_llvm_block(done_bb);
  _interp->_jvm->ipush(result);
  return true;
}

llvm::Value* JeandleIntrinsicLowering::emit_vectorized_mismatch_small(
    llvm::Value* a_addr, llvm::Value* b_addr, llvm::Value* byte_length, llvm::Value* scale) {
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Type* i32 = b.getInt32Ty();
  llvm::Type* i64 = b.getInt64Ty();
  llvm::Function* f = _interp->_llvm_func;

  llvm::BasicBlock* first_check = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_check", f);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_done", f);
  llvm::PHINode* result = llvm::PHINode::Create(i32, 5, "mismatch_inline_small_result", done);
  b.CreateBr(first_check);

  // Compare the largest exact chunk first. All loads use Align(1), since
  // vectorizedMismatch also accepts direct, non-aligned Unsafe addresses.
  static constexpr unsigned widths[] = {8, 4, 2, 1};
  static constexpr const char* suffixes[] = {"i64", "i32", "i16", "i8"};
  llvm::BasicBlock* check = first_check;
  llvm::Value* pos = llvm::ConstantInt::get(i64, 0);
  for (unsigned index = 0; index < sizeof(widths) / sizeof(widths[0]); index++) {
    const unsigned width = widths[index];
    const char* suffix = suffixes[index];
    llvm::Type* chunk_ty = llvm::IntegerType::get(ctx, width * BitsPerByte);
    llvm::BasicBlock* load = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_load", f);
    llvm::BasicBlock* hit = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_hit", f);
    llvm::BasicBlock* equal = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_equal", f);
    llvm::BasicBlock* next_check = llvm::BasicBlock::Create(ctx, "mismatch_inline_small_check", f);

    // Use this width only when the unprocessed suffix is large enough.
    b.SetInsertPoint(check);
    llvm::Value* remaining = b.CreateSub(byte_length, pos, "mismatch_inline_small_remaining");
    b.CreateCondBr(b.CreateICmpUGE(remaining, llvm::ConstantInt::get(i64, width)), load, next_check);

    // Loads are explicitly unaligned because either base may be a raw Unsafe
    // address rather than an aligned Java array base.
    b.SetInsertPoint(load);
    llvm::Value* a_ptr = b.CreateGEP(b.getInt8Ty(), a_addr, pos, "mismatch_inline_small_a_addr");
    llvm::Value* b_ptr = b.CreateGEP(b.getInt8Ty(), b_addr, pos, "mismatch_inline_small_b_addr");
    llvm::Value* a_chunk = b.CreateAlignedLoad(
        chunk_ty, a_ptr, llvm::Align(1), llvm::Twine("mismatch_inline_small_a_") + suffix);
    llvm::Value* b_chunk = b.CreateAlignedLoad(
        chunk_ty, b_ptr, llvm::Align(1), llvm::Twine("mismatch_inline_small_b_") + suffix);
    llvm::Value* diff = b.CreateXor(a_chunk, b_chunk, "mismatch_inline_small_diff");
    b.CreateCondBr(b.CreateICmpNE(diff, llvm::ConstantInt::get(chunk_ty, 0)), hit, equal);

    // cttz identifies the first differing bit in the loaded little-endian
    // chunk. Convert it first to a byte index, then to an element index.
    b.SetInsertPoint(hit);
    llvm::Value* first_bit = b.CreateIntrinsic(llvm::Intrinsic::cttz, {chunk_ty},
        {diff, b.getInt1(true)}, nullptr, "mismatch_inline_small_cttz");
    llvm::Value* byte_in_chunk = b.CreateLShr(first_bit,
        llvm::ConstantInt::get(chunk_ty, LogBitsPerByte), "mismatch_inline_small_byte_in_chunk");
    llvm::Value* byte_index = b.CreateAdd(pos, b.CreateZExtOrTrunc(byte_in_chunk, i64),
                                           "mismatch_inline_small_byte_index");
    llvm::Value* element_index = b.CreateTrunc(
        b.CreateLShr(byte_index, scale), i32, "mismatch_inline_small_element_index");
    result->addIncoming(element_index, hit);
    b.CreateBr(done);

    // This chunk matched. Advance by its width and try the next smaller width.
    b.SetInsertPoint(equal);
    llvm::Value* next_pos = b.CreateAdd(pos, llvm::ConstantInt::get(i64, width),
                                        "mismatch_inline_small_next_pos");
    b.CreateBr(next_check);

    b.SetInsertPoint(next_check);
    llvm::PHINode* merged_pos = b.CreatePHI(i64, 2, "mismatch_inline_small_pos");
    merged_pos->addIncoming(pos, check);
    merged_pos->addIncoming(next_pos, equal);
    pos = merged_pos;
    check = next_check;
  }

  // No width found a difference, so the complete range matched.
  b.SetInsertPoint(check);
  result->addIncoming(llvm::ConstantInt::getSigned(i32, -1), check);
  b.CreateBr(done);

  b.SetInsertPoint(done);
  return result;
}

llvm::Value* JeandleIntrinsicLowering::emit_vectorized_mismatch_medium(
    llvm::Value* a_addr, llvm::Value* b_addr, llvm::Value* byte_length, llvm::Value* scale) {

  // We are generating a loop that does not have any safepoint.
  _interp->_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::SkipSafepointCoverageVerifier);

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Type* i8 = b.getInt8Ty();
  llvm::Type* i16 = b.getInt16Ty();
  llvm::Type* i32 = b.getInt32Ty();
  llvm::Type* i64 = b.getInt64Ty();
  static constexpr unsigned vector_bytes = 16;
  llvm::Type* vec_ty = llvm::FixedVectorType::get(i8, vector_bytes);
  llvm::Function* f = _interp->_llvm_func;

  llvm::BasicBlock* pred = b.GetInsertBlock();
  llvm::BasicBlock* head = llvm::BasicBlock::Create(ctx, "mismatch_inline_vector_head", f);
  llvm::BasicBlock* hit = llvm::BasicBlock::Create(ctx, "mismatch_inline_vector_hit", f);
  llvm::BasicBlock* matched = llvm::BasicBlock::Create(ctx, "mismatch_inline_vector_matched", f);
  llvm::BasicBlock* advance = llvm::BasicBlock::Create(ctx, "mismatch_inline_vector_advance", f);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(ctx, "mismatch_inline_vector_done", f);

  // The final vector load starts at byte_length - 16. When the range is not a
  // multiple of 16, this overlaps the preceding load and covers the tail
  // without an out-of-bounds access or a scalar cleanup loop.
  llvm::Value* last_start = b.CreateSub(
      byte_length, llvm::ConstantInt::get(i64, vector_bytes),
      "mismatch_inline_vector_last_start");
  b.CreateBr(head);

  // Compare one 16-byte window and reduce the per-byte comparison to a mask.
  b.SetInsertPoint(head);
  llvm::PHINode* pos = b.CreatePHI(i64, 2, "mismatch_inline_vector_pos");
  pos->addIncoming(llvm::ConstantInt::get(i64, 0), pred);
  llvm::Value* a_ptr = b.CreateGEP(i8, a_addr, pos, "mismatch_inline_vector_a_addr");
  llvm::Value* b_ptr = b.CreateGEP(i8, b_addr, pos, "mismatch_inline_vector_b_addr");
  llvm::Value* va = b.CreateAlignedLoad(vec_ty, a_ptr, llvm::Align(1),
                                        "mismatch_inline_vector_a");
  llvm::Value* vb = b.CreateAlignedLoad(vec_ty, b_ptr, llvm::Align(1),
                                        "mismatch_inline_vector_b");
  llvm::Value* byte_diff = b.CreateICmpNE(va, vb, "mismatch_inline_vector_diff");
  llvm::Value* mask = b.CreateBitCast(byte_diff, i16, "mismatch_inline_vector_mask");
  b.CreateCondBr(b.CreateICmpNE(mask, llvm::ConstantInt::get(i16, 0)), hit, matched);

  // Each bit in the mask represents one byte. cttz therefore gives the first
  // differing byte directly.
  b.SetInsertPoint(hit);
  llvm::Value* first_byte = b.CreateIntrinsic(llvm::Intrinsic::cttz, {i16},
      {mask, b.getInt1(true)}, nullptr, "mismatch_inline_vector_cttz");
  llvm::Value* byte_index = b.CreateAdd(pos, b.CreateZExt(first_byte, i64),
                                        "mismatch_inline_vector_byte_index");
  llvm::Value* element_index = b.CreateTrunc(b.CreateLShr(byte_index, scale), i32,
                                              "mismatch_inline_vector_element_index");
  b.CreateBr(done);

  // Reaching last_start means the entire byte range has been compared.
  b.SetInsertPoint(matched);
  b.CreateCondBr(b.CreateICmpEQ(pos, last_start), done, advance);

  // Advance normally when another full vector fits. Otherwise compare the
  // overlapping final window at last_start.
  b.SetInsertPoint(advance);
  llvm::Value* sequential = b.CreateAdd(
      pos, llvm::ConstantInt::get(i64, vector_bytes),
      "mismatch_inline_vector_sequential");
  llvm::Value* sequential_end = b.CreateAdd(
      sequential, llvm::ConstantInt::get(i64, vector_bytes));
  llvm::Value* next_pos = b.CreateSelect(b.CreateICmpULE(sequential_end, byte_length), sequential,
                                         last_start, "mismatch_inline_vector_next");
  pos->addIncoming(next_pos, advance);
  b.CreateBr(head);

  b.SetInsertPoint(done);
  llvm::PHINode* result = b.CreatePHI(i32, 2, "mismatch_inline_vector_result");
  result->addIncoming(element_index, hit);
  result->addIncoming(llvm::ConstantInt::getSigned(i32, -1), matched);
  return result;
}

// ---- lower_exact_arith ----
// Math.addExact/subtractExact/multiplyExact/incrementExact/decrementExact/negateExact
// llvm.s{sub,mul}.with.overflow, branch on the overflow bit to an
// uncommon_trap (Reason_intrinsic/Action_none) so the interpreter
// re-executes. Args are peeked (not popped) before the branch for the same
// deopt re-execution reason.
//
// increment/decrement/negate are unary in Java but all three reduce to the
// with-overflow intrinsic on a synthesized second operand: a+1, a-1, and 0-a
// respectively -- negation overflows exactly when a == MIN_VALUE, which is
// exactly when 0-a overflows the signed range, so ssub.with.overflow(0, a)
// detects it correctly without a separate check.
bool JeandleIntrinsicLowering::lower_exact_arith(vmIntrinsics::ID id,
                                                 llvm::Intrinsic::ID overflow_id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  int cur_bci = _interp->_bytecodes.cur_bci();

  bool is_long = (id == vmIntrinsics::_addExactL ||
                  id == vmIntrinsics::_subtractExactL ||
                  id == vmIntrinsics::_multiplyExactL ||
                  id == vmIntrinsics::_incrementExactL ||
                  id == vmIntrinsics::_decrementExactL ||
                  id == vmIntrinsics::_negateExactL);
  bool unary = (id == vmIntrinsics::_incrementExactI || id == vmIntrinsics::_incrementExactL ||
                id == vmIntrinsics::_decrementExactI || id == vmIntrinsics::_decrementExactL ||
                id == vmIntrinsics::_negateExactI     || id == vmIntrinsics::_negateExactL);

  llvm::Type* ty = JeandleType::java2llvm(
      is_long ? BasicType::T_LONG : BasicType::T_INT, ctx);

  llvm::Value* arg1;
  llvm::Value* arg2;
  if (unary) {
    llvm::Value* a = _interp->_jvm->peek_value(0).value();
    bool is_negate = (id == vmIntrinsics::_negateExactI || id == vmIntrinsics::_negateExactL);
    if (is_negate) {
      arg1 = llvm::ConstantInt::get(ty, 0);
      arg2 = a;
    } else {
      arg1 = a;
      arg2 = llvm::ConstantInt::get(ty, 1);
    }
  } else {
    arg2 = _interp->_jvm->peek_value(0).value();
    arg1 = _interp->_jvm->peek_value(1).value();
  }

  llvm::Value* res = builder.CreateIntrinsic(overflow_id, {ty}, {arg1, arg2});
  llvm::Value* result   = builder.CreateExtractValue(res, 0);
  llvm::Value* overflow = builder.CreateExtractValue(res, 1);

  // vmIntrinsics::name_at(id) yields e.g. "_subtractExactI", matching the
  // addExactI/addExactL block-label convention used by this shared helper.
  const std::string pfx = "bci_" + std::to_string(cur_bci) + vmIntrinsics::name_at(id);
  llvm::BasicBlock* ok_bb = llvm::BasicBlock::Create(ctx, pfx + "_ok",       _interp->_llvm_func);
  llvm::BasicBlock* ov_bb = llvm::BasicBlock::Create(ctx, pfx + "_overflow", _interp->_llvm_func);

  llvm::MDNode* bwmd = llvm::MDBuilder(ctx).createBranchWeights(1, 9999);
  builder.CreateCondBr(overflow, ov_bb, ok_bb, bwmd);
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_none, ov_bb,
                         true /* should_reexecute */);

  builder.SetInsertPoint(ok_bb);
  _interp->_block->set_tail_llvm_block(ok_bb);

  if (is_long) {
    _interp->_jvm->lpop();
    if (!unary) _interp->_jvm->lpop();
    _interp->_jvm->lpush(result);
  } else {
    _interp->_jvm->ipop();
    if (!unary) _interp->_jvm->ipop();
    _interp->_jvm->ipush(result);
  }
  return true;
}

// ---- lower_multiply_high ----
// Math.multiplyHigh(long,long) / Math.unsignedMultiplyHigh(long,long): the
// most significant 64 bits of the signed (resp. unsigned) 128-bit product of
// the two 64-bit operands. Sign/zero-extend both operands to i128, multiply,
// and shift right 64 -- the canonical wide-multiply idiom LLVM's instruction
// selection recognizes and lowers to a single hardware mul-high instruction
// (e.g. imulq/mulq on x86-64) rather than a real 128-bit multiply routine.
bool JeandleIntrinsicLowering::lower_multiply_high(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_unsigned = (id == vmIntrinsics::_unsignedMultiplyHigh);

  llvm::Value* y = _interp->_jvm->lpop();
  llvm::Value* x = _interp->_jvm->lpop();

  llvm::Type* wide_ty = builder.getIntNTy(128);
  llvm::Value* x_wide = is_unsigned ? builder.CreateZExt(x, wide_ty) : builder.CreateSExt(x, wide_ty);
  llvm::Value* y_wide = is_unsigned ? builder.CreateZExt(y, wide_ty) : builder.CreateSExt(y, wide_ty);
  llvm::Value* product = builder.CreateMul(x_wide, y_wide);
  llvm::Value* high = builder.CreateLShr(product, 64);

  _interp->_jvm->lpush(builder.CreateTrunc(high, builder.getInt64Ty()));
  return true;
}
