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

    // Unsafe primitive get/put — no GC barrier, inline load/store.
    // Plain (Relaxed): all 8 primitive types.
    case vmIntrinsics::_getBoolean:
    case vmIntrinsics::_getByte:
    case vmIntrinsics::_getChar:
    case vmIntrinsics::_getShort:
    case vmIntrinsics::_getInt:
    case vmIntrinsics::_getLong:
    case vmIntrinsics::_getFloat:
    case vmIntrinsics::_getDouble:
    case vmIntrinsics::_putBoolean:
    case vmIntrinsics::_putByte:
    case vmIntrinsics::_putChar:
    case vmIntrinsics::_putShort:
    case vmIntrinsics::_putInt:
    case vmIntrinsics::_putLong:
    case vmIntrinsics::_putFloat:
    case vmIntrinsics::_putDouble:
    // Volatile: Long/Double and Reference variants are deferred.
    case vmIntrinsics::_getBooleanVolatile:
    case vmIntrinsics::_getByteVolatile:
    case vmIntrinsics::_getCharVolatile:
    case vmIntrinsics::_getShortVolatile:
    case vmIntrinsics::_getIntVolatile:
    case vmIntrinsics::_getFloatVolatile:
    case vmIntrinsics::_putBooleanVolatile:
    case vmIntrinsics::_putByteVolatile:
    case vmIntrinsics::_putCharVolatile:
    case vmIntrinsics::_putShortVolatile:
    case vmIntrinsics::_putIntVolatile:
    case vmIntrinsics::_putFloatVolatile:
    // Opaque: all 8 primitive types.
    case vmIntrinsics::_getBooleanOpaque:
    case vmIntrinsics::_getByteOpaque:
    case vmIntrinsics::_getCharOpaque:
    case vmIntrinsics::_getShortOpaque:
    case vmIntrinsics::_getIntOpaque:
    case vmIntrinsics::_getLongOpaque:
    case vmIntrinsics::_getFloatOpaque:
    case vmIntrinsics::_getDoubleOpaque:
    case vmIntrinsics::_putBooleanOpaque:
    case vmIntrinsics::_putByteOpaque:
    case vmIntrinsics::_putCharOpaque:
    case vmIntrinsics::_putShortOpaque:
    case vmIntrinsics::_putIntOpaque:
    case vmIntrinsics::_putLongOpaque:
    case vmIntrinsics::_putFloatOpaque:
    case vmIntrinsics::_putDoubleOpaque:
    // Acquire get / Release put: all 8 primitive types.
    case vmIntrinsics::_getBooleanAcquire:
    case vmIntrinsics::_getByteAcquire:
    case vmIntrinsics::_getCharAcquire:
    case vmIntrinsics::_getShortAcquire:
    case vmIntrinsics::_getIntAcquire:
    case vmIntrinsics::_getLongAcquire:
    case vmIntrinsics::_getFloatAcquire:
    case vmIntrinsics::_getDoubleAcquire:
    case vmIntrinsics::_putBooleanRelease:
    case vmIntrinsics::_putByteRelease:
    case vmIntrinsics::_putCharRelease:
    case vmIntrinsics::_putShortRelease:
    case vmIntrinsics::_putIntRelease:
    case vmIntrinsics::_putLongRelease:
    case vmIntrinsics::_putFloatRelease:
    case vmIntrinsics::_putDoubleRelease:
    // Unaligned (Relaxed, align 1): short/char/int/long.
    case vmIntrinsics::_getShortUnaligned:
    case vmIntrinsics::_getCharUnaligned:
    case vmIntrinsics::_getIntUnaligned:
    case vmIntrinsics::_getLongUnaligned:
    case vmIntrinsics::_putShortUnaligned:
    case vmIntrinsics::_putCharUnaligned:
    case vmIntrinsics::_putIntUnaligned:
    case vmIntrinsics::_putLongUnaligned:

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

    case vmIntrinsics::_bitCount_i:
    case vmIntrinsics::_bitCount_l:
      return lower_bit_count(id);

    case vmIntrinsics::_numberOfLeadingZeros_i:
    case vmIntrinsics::_numberOfLeadingZeros_l:
      return lower_count_zeros(id, llvm::Intrinsic::ctlz);
    case vmIntrinsics::_numberOfTrailingZeros_i:
    case vmIntrinsics::_numberOfTrailingZeros_l:
      return lower_count_zeros(id, llvm::Intrinsic::cttz);

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

    // Unsafe primitive get/put — all routed to the shared lowering helper.
    case vmIntrinsics::_getBoolean:
    case vmIntrinsics::_getByte:
    case vmIntrinsics::_getChar:
    case vmIntrinsics::_getShort:
    case vmIntrinsics::_getInt:
    case vmIntrinsics::_getLong:
    case vmIntrinsics::_getFloat:
    case vmIntrinsics::_getDouble:
    case vmIntrinsics::_putBoolean:
    case vmIntrinsics::_putByte:
    case vmIntrinsics::_putChar:
    case vmIntrinsics::_putShort:
    case vmIntrinsics::_putInt:
    case vmIntrinsics::_putLong:
    case vmIntrinsics::_putFloat:
    case vmIntrinsics::_putDouble:
    case vmIntrinsics::_getBooleanVolatile:
    case vmIntrinsics::_getByteVolatile:
    case vmIntrinsics::_getCharVolatile:
    case vmIntrinsics::_getShortVolatile:
    case vmIntrinsics::_getIntVolatile:
    case vmIntrinsics::_getFloatVolatile:
    case vmIntrinsics::_putBooleanVolatile:
    case vmIntrinsics::_putByteVolatile:
    case vmIntrinsics::_putCharVolatile:
    case vmIntrinsics::_putShortVolatile:
    case vmIntrinsics::_putIntVolatile:
    case vmIntrinsics::_putFloatVolatile:
    case vmIntrinsics::_getBooleanOpaque:
    case vmIntrinsics::_getByteOpaque:
    case vmIntrinsics::_getCharOpaque:
    case vmIntrinsics::_getShortOpaque:
    case vmIntrinsics::_getIntOpaque:
    case vmIntrinsics::_getLongOpaque:
    case vmIntrinsics::_getFloatOpaque:
    case vmIntrinsics::_getDoubleOpaque:
    case vmIntrinsics::_putBooleanOpaque:
    case vmIntrinsics::_putByteOpaque:
    case vmIntrinsics::_putCharOpaque:
    case vmIntrinsics::_putShortOpaque:
    case vmIntrinsics::_putIntOpaque:
    case vmIntrinsics::_putLongOpaque:
    case vmIntrinsics::_putFloatOpaque:
    case vmIntrinsics::_putDoubleOpaque:
    case vmIntrinsics::_getBooleanAcquire:
    case vmIntrinsics::_getByteAcquire:
    case vmIntrinsics::_getCharAcquire:
    case vmIntrinsics::_getShortAcquire:
    case vmIntrinsics::_getIntAcquire:
    case vmIntrinsics::_getLongAcquire:
    case vmIntrinsics::_getFloatAcquire:
    case vmIntrinsics::_getDoubleAcquire:
    case vmIntrinsics::_putBooleanRelease:
    case vmIntrinsics::_putByteRelease:
    case vmIntrinsics::_putCharRelease:
    case vmIntrinsics::_putShortRelease:
    case vmIntrinsics::_putIntRelease:
    case vmIntrinsics::_putLongRelease:
    case vmIntrinsics::_putFloatRelease:
    case vmIntrinsics::_putDoubleRelease:
    case vmIntrinsics::_getShortUnaligned:
    case vmIntrinsics::_getCharUnaligned:
    case vmIntrinsics::_getIntUnaligned:
    case vmIntrinsics::_getLongUnaligned:
    case vmIntrinsics::_putShortUnaligned:
    case vmIntrinsics::_putCharUnaligned:
    case vmIntrinsics::_putIntUnaligned:
    case vmIntrinsics::_putLongUnaligned:
      return lower_unsafe_get_put(id);

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

// =============================================================================
// Unsafe primitive get/put (no GC barrier)
//
// Mirrors C2's inline_unsafe_access for primitive types: a plain load/store with
// an LLVM atomic ordering derived from C2's mo_decorator_for_access_kind. Primitives
// never touch oops, so (like C2, where access.is_oop() is false) there is no read or
// write barrier and no GC-state call site -- the base oop is tracked by RS4GC exactly
// as a getfield base is.
//
// Address space: base != null is an on-heap field (JavaHeap, RS4GC-tracked); base
// == null is an absolute native address (CHeap, e.g. allocateMemory/NIO). These are
// distinct LLVM pointer types. A literal-null base (aconst_null) lowers to native
// only, matching C2's IN_NATIVE; a non-constant base branches at runtime since
// Jeandle lacks C2's type lattice to resolve heap-vs-native statically.
// =============================================================================

namespace {
// Natural byte size / alignment of a primitive BasicType.
unsigned unsafe_basic_type_size(BasicType bt) {
  switch (bt) {
    case T_BOOLEAN: case T_BYTE:  return 1;
    case T_CHAR:   case T_SHORT:  return 2;
    case T_INT:    case T_FLOAT:  return 4;
    case T_LONG:   case T_DOUBLE: return 8;
    default: ShouldNotReachHere(); return 0;
  }
}

// The in-memory LLVM type (narrower than the computational type for sub-int kinds).
llvm::Type* unsafe_basic_type_mem_llvm(BasicType bt, llvm::LLVMContext& ctx) {
  switch (bt) {
    case T_BOOLEAN: case T_BYTE:  return llvm::Type::getInt8Ty(ctx);
    case T_CHAR:   case T_SHORT:  return llvm::Type::getInt16Ty(ctx);
    case T_INT:                    return llvm::Type::getInt32Ty(ctx);
    case T_LONG:                   return llvm::Type::getInt64Ty(ctx);
    case T_FLOAT:                  return llvm::Type::getFloatTy(ctx);
    case T_DOUBLE:                 return llvm::Type::getDoubleTy(ctx);
    default: ShouldNotReachHere(); return nullptr;
  }
}
} // namespace

// Map each Unsafe primitive get/put intrinsic to (store?, mem_type, ordering, unaligned).
// Ordering mirrors C2: Relaxed -> Unordered, Volatile -> SeqCst, Opaque -> Monotonic,
// Acquire -> Acquire (loads), Release -> Release (stores).
bool JeandleIntrinsicLowering::lower_unsafe_get_put(vmIntrinsics::ID id) {
  using AO = llvm::AtomicOrdering;
  switch (id) {
    // Plain (Relaxed) -- all 8 primitive types.
    case vmIntrinsics::_getBoolean: return emit_unsafe_access(false, T_BOOLEAN, AO::Unordered, false);
    case vmIntrinsics::_getByte:    return emit_unsafe_access(false, T_BYTE,    AO::Unordered, false);
    case vmIntrinsics::_getChar:    return emit_unsafe_access(false, T_CHAR,    AO::Unordered, false);
    case vmIntrinsics::_getShort:   return emit_unsafe_access(false, T_SHORT,   AO::Unordered, false);
    case vmIntrinsics::_getInt:     return emit_unsafe_access(false, T_INT,     AO::Unordered, false);
    case vmIntrinsics::_getLong:    return emit_unsafe_access(false, T_LONG,    AO::Unordered, false);
    case vmIntrinsics::_getFloat:   return emit_unsafe_access(false, T_FLOAT,   AO::Unordered, false);
    case vmIntrinsics::_getDouble:  return emit_unsafe_access(false, T_DOUBLE,  AO::Unordered, false);
    case vmIntrinsics::_putBoolean: return emit_unsafe_access(true,  T_BOOLEAN, AO::Unordered, false);
    case vmIntrinsics::_putByte:    return emit_unsafe_access(true,  T_BYTE,    AO::Unordered, false);
    case vmIntrinsics::_putChar:    return emit_unsafe_access(true,  T_CHAR,    AO::Unordered, false);
    case vmIntrinsics::_putShort:   return emit_unsafe_access(true,  T_SHORT,   AO::Unordered, false);
    case vmIntrinsics::_putInt:     return emit_unsafe_access(true,  T_INT,     AO::Unordered, false);
    case vmIntrinsics::_putLong:    return emit_unsafe_access(true,  T_LONG,    AO::Unordered, false);
    case vmIntrinsics::_putFloat:   return emit_unsafe_access(true,  T_FLOAT,   AO::Unordered, false);
    case vmIntrinsics::_putDouble:  return emit_unsafe_access(true,  T_DOUBLE,  AO::Unordered, false);
    // Volatile -- excludes Long/Double (deferred).
    case vmIntrinsics::_getBooleanVolatile: return emit_unsafe_access(false, T_BOOLEAN, AO::SequentiallyConsistent, false);
    case vmIntrinsics::_getByteVolatile:    return emit_unsafe_access(false, T_BYTE,    AO::SequentiallyConsistent, false);
    case vmIntrinsics::_getCharVolatile:    return emit_unsafe_access(false, T_CHAR,    AO::SequentiallyConsistent, false);
    case vmIntrinsics::_getShortVolatile:   return emit_unsafe_access(false, T_SHORT,   AO::SequentiallyConsistent, false);
    case vmIntrinsics::_getIntVolatile:     return emit_unsafe_access(false, T_INT,     AO::SequentiallyConsistent, false);
    case vmIntrinsics::_getFloatVolatile:   return emit_unsafe_access(false, T_FLOAT,   AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putBooleanVolatile: return emit_unsafe_access(true,  T_BOOLEAN, AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putByteVolatile:    return emit_unsafe_access(true,  T_BYTE,    AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putCharVolatile:    return emit_unsafe_access(true,  T_CHAR,    AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putShortVolatile:   return emit_unsafe_access(true,  T_SHORT,   AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putIntVolatile:     return emit_unsafe_access(true,  T_INT,     AO::SequentiallyConsistent, false);
    case vmIntrinsics::_putFloatVolatile:   return emit_unsafe_access(true,  T_FLOAT,   AO::SequentiallyConsistent, false);
    // Opaque -- all 8 primitive types.
    case vmIntrinsics::_getBooleanOpaque: return emit_unsafe_access(false, T_BOOLEAN, AO::Monotonic, false);
    case vmIntrinsics::_getByteOpaque:    return emit_unsafe_access(false, T_BYTE,    AO::Monotonic, false);
    case vmIntrinsics::_getCharOpaque:    return emit_unsafe_access(false, T_CHAR,    AO::Monotonic, false);
    case vmIntrinsics::_getShortOpaque:   return emit_unsafe_access(false, T_SHORT,   AO::Monotonic, false);
    case vmIntrinsics::_getIntOpaque:     return emit_unsafe_access(false, T_INT,     AO::Monotonic, false);
    case vmIntrinsics::_getLongOpaque:    return emit_unsafe_access(false, T_LONG,    AO::Monotonic, false);
    case vmIntrinsics::_getFloatOpaque:   return emit_unsafe_access(false, T_FLOAT,   AO::Monotonic, false);
    case vmIntrinsics::_getDoubleOpaque:  return emit_unsafe_access(false, T_DOUBLE,  AO::Monotonic, false);
    case vmIntrinsics::_putBooleanOpaque: return emit_unsafe_access(true,  T_BOOLEAN, AO::Monotonic, false);
    case vmIntrinsics::_putByteOpaque:    return emit_unsafe_access(true,  T_BYTE,    AO::Monotonic, false);
    case vmIntrinsics::_putCharOpaque:    return emit_unsafe_access(true,  T_CHAR,    AO::Monotonic, false);
    case vmIntrinsics::_putShortOpaque:   return emit_unsafe_access(true,  T_SHORT,   AO::Monotonic, false);
    case vmIntrinsics::_putIntOpaque:     return emit_unsafe_access(true,  T_INT,     AO::Monotonic, false);
    case vmIntrinsics::_putLongOpaque:    return emit_unsafe_access(true,  T_LONG,    AO::Monotonic, false);
    case vmIntrinsics::_putFloatOpaque:   return emit_unsafe_access(true,  T_FLOAT,   AO::Monotonic, false);
    case vmIntrinsics::_putDoubleOpaque:  return emit_unsafe_access(true,  T_DOUBLE,  AO::Monotonic, false);
    // Acquire get / Release put -- all 8 primitive types.
    case vmIntrinsics::_getBooleanAcquire: return emit_unsafe_access(false, T_BOOLEAN, AO::Acquire, false);
    case vmIntrinsics::_getByteAcquire:    return emit_unsafe_access(false, T_BYTE,    AO::Acquire, false);
    case vmIntrinsics::_getCharAcquire:    return emit_unsafe_access(false, T_CHAR,    AO::Acquire, false);
    case vmIntrinsics::_getShortAcquire:   return emit_unsafe_access(false, T_SHORT,   AO::Acquire, false);
    case vmIntrinsics::_getIntAcquire:     return emit_unsafe_access(false, T_INT,     AO::Acquire, false);
    case vmIntrinsics::_getLongAcquire:    return emit_unsafe_access(false, T_LONG,    AO::Acquire, false);
    case vmIntrinsics::_getFloatAcquire:   return emit_unsafe_access(false, T_FLOAT,   AO::Acquire, false);
    case vmIntrinsics::_getDoubleAcquire:  return emit_unsafe_access(false, T_DOUBLE,  AO::Acquire, false);
    case vmIntrinsics::_putBooleanRelease: return emit_unsafe_access(true,  T_BOOLEAN, AO::Release, false);
    case vmIntrinsics::_putByteRelease:    return emit_unsafe_access(true,  T_BYTE,    AO::Release, false);
    case vmIntrinsics::_putCharRelease:    return emit_unsafe_access(true,  T_CHAR,    AO::Release, false);
    case vmIntrinsics::_putShortRelease:   return emit_unsafe_access(true,  T_SHORT,   AO::Release, false);
    case vmIntrinsics::_putIntRelease:     return emit_unsafe_access(true,  T_INT,     AO::Release, false);
    case vmIntrinsics::_putLongRelease:    return emit_unsafe_access(true,  T_LONG,    AO::Release, false);
    case vmIntrinsics::_putFloatRelease:   return emit_unsafe_access(true,  T_FLOAT,   AO::Release, false);
    case vmIntrinsics::_putDoubleRelease:  return emit_unsafe_access(true,  T_DOUBLE,  AO::Release, false);
    // Unaligned (Relaxed, align 1) -- short/char/int/long. Non-atomic: an atomic
    // load/store with align < natural lowers to an __atomic libcall (unresolvable
    // external symbol); a plain misaligned load matches C2's generated code.
    case vmIntrinsics::_getShortUnaligned: return emit_unsafe_access(false, T_SHORT, AO::NotAtomic, true);
    case vmIntrinsics::_getCharUnaligned:  return emit_unsafe_access(false, T_CHAR,  AO::NotAtomic, true);
    case vmIntrinsics::_getIntUnaligned:   return emit_unsafe_access(false, T_INT,   AO::NotAtomic, true);
    case vmIntrinsics::_getLongUnaligned:  return emit_unsafe_access(false, T_LONG,  AO::NotAtomic, true);
    case vmIntrinsics::_putShortUnaligned: return emit_unsafe_access(true,  T_SHORT, AO::NotAtomic, true);
    case vmIntrinsics::_putCharUnaligned:  return emit_unsafe_access(true,  T_CHAR,  AO::NotAtomic, true);
    case vmIntrinsics::_putIntUnaligned:   return emit_unsafe_access(true,  T_INT,   AO::NotAtomic, true);
    case vmIntrinsics::_putLongUnaligned:  return emit_unsafe_access(true,  T_LONG,  AO::NotAtomic, true);
    default:
      ShouldNotReachHere();
      return false;
  }
}

// Emit the load/store for an Unsafe primitive access, choosing the address space
// from the base pointer. value/offset/base are popped LIFO from the JVM stack.
bool JeandleIntrinsicLowering::emit_unsafe_access(bool is_store, BasicType mem_type,
                                                   llvm::AtomicOrdering order, bool unaligned) {
  // Mark the compiled method as doing unsafe memory access so the JVM signal
  // handler converts a faulting access (e.g. SIGBUS on a truncated mmap) into an
  // InternalError instead of crashing. Matches C2's set_has_unsafe_access(true).
  JeandleCompilation::current()->set_has_unsafe_access(true);

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // JVM stack (top -> bottom):
  //   store: [value, offset, base, Unsafe receiver]
  //   load:  [offset, base, Unsafe receiver]
  llvm::Value* value = nullptr;
  if (is_store) {
    value = _interp->_jvm->pop(mem_type);
  }
  llvm::Value* offset = _interp->_jvm->lpop();  // i64 byte offset
  llvm::Value* base   = _interp->_jvm->apop();  // ptr addrspace(1), or null
  _interp->_jvm->apop();                        // Unsafe receiver (singleton, never null)

  const unsigned align = unaligned ? 1 : unsafe_basic_type_size(mem_type);
  llvm::Type* comp_ty = JeandleType::java2llvm(mem_type, ctx);  // computational type
  llvm::Type* mem_ty  = unsafe_basic_type_mem_llvm(mem_type, ctx);
  llvm::Type* i8_ty   = llvm::Type::getInt8Ty(ctx);
  llvm::PointerType* heap_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::PointerType* native_ptr_ty =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);

  // Load mem_ty from addr and widen to the computational type (mirrors load_from_address).
  auto emit_load = [&](llvm::Value* addr) -> llvm::Value* {
    llvm::LoadInst* ld = builder.CreateLoad(mem_ty, addr);
    // NotAtomic (unaligned access) is left as a plain misaligned load; an atomic
    // load with align < natural would lower to an __atomic libcall that Jeandle's
    // external-symbol resolver cannot handle.
    if (order != llvm::AtomicOrdering::NotAtomic) {
      ld->setAtomic(order);
    }
    ld->setAlignment(llvm::Align(align));
    switch (mem_type) {
      case T_BOOLEAN: case T_CHAR:  return builder.CreateZExt(ld, comp_ty);
      case T_BYTE:    case T_SHORT: return builder.CreateSExt(ld, comp_ty);
      default:                       return ld;
    }
  };
  // Narrow value (computational type) to mem_ty and store (mirrors store_to_address).
  auto emit_store = [&](llvm::Value* addr) {
    llvm::Value* v = value;
    switch (mem_type) {
      case T_BOOLEAN:
        v = builder.CreateAnd(builder.CreateTrunc(v, mem_ty), builder.getInt8(1));
        break;
      case T_BYTE: case T_CHAR: case T_SHORT:
        v = builder.CreateTrunc(v, mem_ty);
        break;
      default:
        break;
    }
    llvm::StoreInst* st = builder.CreateStore(v, addr);
    if (order != llvm::AtomicOrdering::NotAtomic) {
      st->setAtomic(order);
    }
    st->setAlignment(llvm::Align(align));
  };

  const bool base_is_null = llvm::isa<llvm::ConstantPointerNull>(base);

  if (is_store) {
    if (base_is_null) {
      emit_store(builder.CreateIntToPtr(offset, native_ptr_ty));
      return true;
    }
    llvm::BasicBlock* heap_bb   = llvm::BasicBlock::Create(ctx, "unsafe_store_heap",   _interp->_llvm_func);
    llvm::BasicBlock* native_bb = llvm::BasicBlock::Create(ctx, "unsafe_store_native", _interp->_llvm_func);
    llvm::BasicBlock* cont_bb   = llvm::BasicBlock::Create(ctx, "unsafe_store_cont",   _interp->_llvm_func);
    builder.CreateCondBr(builder.CreateICmpEQ(base, llvm::ConstantPointerNull::get(heap_ptr_ty)),
                         native_bb, heap_bb);

    builder.SetInsertPoint(heap_bb);
    emit_store(builder.CreateInBoundsGEP(i8_ty, base, offset));
    builder.CreateBr(cont_bb);

    builder.SetInsertPoint(native_bb);
    emit_store(builder.CreateIntToPtr(offset, native_ptr_ty));
    builder.CreateBr(cont_bb);

    builder.SetInsertPoint(cont_bb);
    _interp->_block->set_tail_llvm_block(cont_bb);
    return true;
  }

  // Load: PHI-merge the two paths.
  if (base_is_null) {
    _interp->_jvm->push(mem_type, emit_load(builder.CreateIntToPtr(offset, native_ptr_ty)));
    return true;
  }

  llvm::BasicBlock* heap_bb   = llvm::BasicBlock::Create(ctx, "unsafe_load_heap",   _interp->_llvm_func);
  llvm::BasicBlock* native_bb = llvm::BasicBlock::Create(ctx, "unsafe_load_native", _interp->_llvm_func);
  llvm::BasicBlock* cont_bb   = llvm::BasicBlock::Create(ctx, "unsafe_load_cont",   _interp->_llvm_func);
  builder.CreateCondBr(builder.CreateICmpEQ(base, llvm::ConstantPointerNull::get(heap_ptr_ty)),
                       native_bb, heap_bb);

  builder.SetInsertPoint(heap_bb);
  llvm::Value* heap_val = emit_load(builder.CreateInBoundsGEP(i8_ty, base, offset));
  builder.CreateBr(cont_bb);
  llvm::BasicBlock* heap_pred = builder.GetInsertBlock();

  builder.SetInsertPoint(native_bb);
  llvm::Value* native_val = emit_load(builder.CreateIntToPtr(offset, native_ptr_ty));
  builder.CreateBr(cont_bb);
  llvm::BasicBlock* native_pred = builder.GetInsertBlock();

  builder.SetInsertPoint(cont_bb);
  _interp->_block->set_tail_llvm_block(cont_bb);
  llvm::PHINode* phi = builder.CreatePHI(comp_ty, 2, "unsafe_get");
  phi->addIncoming(heap_val, heap_pred);
  phi->addIncoming(native_val, native_pred);
  _interp->_jvm->push(mem_type, phi);
  return true;
}
