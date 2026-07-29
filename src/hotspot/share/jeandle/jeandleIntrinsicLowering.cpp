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
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "logging/log.hpp"
#include "oops/arrayOop.hpp"
#include "oops/klass.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/globals.hpp"
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

    // Reference*
    case vmIntrinsics::_Reference_get:
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:

    // newArray
    case vmIntrinsics::_newArray:

    // bitcast
    case vmIntrinsics::_floatToRawIntBits:
    case vmIntrinsics::_intBitsToFloat:
    case vmIntrinsics::_doubleToRawLongBits:
    case vmIntrinsics::_longBitsToDouble:

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
    // addExact
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
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
    case vmIntrinsics::_Preconditions_checkIndex:
    case vmIntrinsics::_Preconditions_checkLongIndex:
      return trap_reason_mask_val(Deoptimization::Reason_intrinsic) |
             trap_reason_mask_val(Deoptimization::Reason_range_check);
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
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
                           {CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE});

    // Reference*
    case vmIntrinsics::_Reference_get:
      return lower_java_op("jeandle.reference_get",
                           {CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE});
    case vmIntrinsics::_Reference_refersTo0:
    case vmIntrinsics::_PhantomReference_refersTo0:
      return lower_java_op("jeandle.reference_refers_to",
                           {CTRL_NONE, MEM_READ | MEM_NEEDS_GC_STATE});

    case vmIntrinsics::_vectorizedMismatch:
      return lower_vectorized_mismatch();

    // newArray
    case vmIntrinsics::_newArray:
      return lower_new_array();

    // bitcast
    case vmIntrinsics::_floatToRawIntBits:
    case vmIntrinsics::_intBitsToFloat:
    case vmIntrinsics::_doubleToRawLongBits:
    case vmIntrinsics::_longBitsToDouble:
      return lower_llvm_bitcast();

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

    // addExact
    case vmIntrinsics::_addExactI:
    case vmIntrinsics::_addExactL:
      return lower_add_exact(id);

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
  llvm::Value* lh_addr = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), klass, builder.getInt32(in_bytes(Klass::layout_helper_offset())));
  llvm::Value* layout_helper = builder.CreateLoad(builder.getInt32Ty(), lh_addr);
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

// ---- lower_add_exact ----
// Math.addExact(int,int) / Math.addExact(long,long):
//   Use llvm.sadd.with.overflow; on overflow take an uncommon_trap
//   (Reason_intrinsic / Action_none) so the interpreter re-executes.
//   Args are peeked (not popped) before the branch so the statepoint
//   captures the full pre-call stack for correct deopt re-execution.
bool JeandleIntrinsicLowering::lower_add_exact(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  int cur_bci = _interp->_bytecodes.cur_bci();
  bool is_long = (id == vmIntrinsics::_addExactL);

  llvm::Type* ty = JeandleType::java2llvm(
      is_long ? BasicType::T_LONG : BasicType::T_INT, ctx);

  llvm::Value* arg2 = _interp->_jvm->peek_value(0).value();
  llvm::Value* arg1 = _interp->_jvm->peek_value(1).value();

  llvm::Value* res = builder.CreateIntrinsic(
      llvm::Intrinsic::sadd_with_overflow, {ty}, {arg1, arg2});
  llvm::Value* result   = builder.CreateExtractValue(res, 0);
  llvm::Value* overflow = builder.CreateExtractValue(res, 1);

  const std::string pfx = "bci_" + std::to_string(cur_bci) +
                           (is_long ? "_addExactL" : "_addExactI");
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
    _interp->_jvm->lpop(); // arg2
    _interp->_jvm->lpop(); // arg1
    _interp->_jvm->lpush(result);
  } else {
    _interp->_jvm->ipop(); // arg2
    _interp->_jvm->ipop(); // arg1
    _interp->_jvm->ipush(result);
  }
  return true;
}
