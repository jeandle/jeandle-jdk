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
#include "llvm/ADT/FloatingPointMode.h"
#include "llvm/Analysis/ConstantFolding.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/JavaType.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/DerivedTypes.h"
#include "llvm/IR/MDBuilder.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciField.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "ci/ciSymbol.hpp"
#include "classfile/javaClasses.hpp"
#include "classfile/vmIntrinsics.hpp"
#include "interpreter/bytecodes.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "gc/shared/gc_globals.hpp"
#include "llvm/ADT/SmallPtrSet.h"
#include "logging/log.hpp"
#include "oops/arrayOop.hpp"
#include "oops/klass.hpp"
#include "oops/klass.inline.hpp"
#include "oops/klassVtable.hpp"
#include "oops/method.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/globals.hpp"
#include "runtime/vm_version.hpp"
#include "utilities/globalDefinitions.hpp"

#include <cstring>

// =============================================================================
struct UnsafePrimitiveTypeInfo {
  unsigned value_bits;
  llvm::MaybeAlign alignment;
  const char* type_name;
};

static UnsafePrimitiveTypeInfo unsafe_primitive_type_info(BasicType type) {
  switch (type) {
    case T_BOOLEAN: return {8,  llvm::MaybeAlign(1), "boolean"};
    case T_BYTE:  return {8,  llvm::MaybeAlign(1), "byte"};
    case T_SHORT: return {16, llvm::MaybeAlign(2), "short"};
    case T_CHAR:  return {16, llvm::MaybeAlign(2), "char"};
    case T_INT:   return {32, llvm::MaybeAlign(4), "int"};
    case T_LONG:  return {64, llvm::MaybeAlign(8), "long"};
    case T_FLOAT: return {32, llvm::MaybeAlign(4), "float"};
    case T_DOUBLE:return {64, llvm::MaybeAlign(8), "double"};
    default:
      ShouldNotReachHere();
  }
  return {0, llvm::MaybeAlign(), nullptr};
}

static llvm::Type* unsafe_primitive_memory_llvm_type(
    BasicType type, llvm::IRBuilder<>& builder) {
  switch (type) {
    case T_BOOLEAN:
    case T_BYTE:   return builder.getInt8Ty();
    case T_SHORT:
    case T_CHAR:   return builder.getInt16Ty();
    case T_INT:    return builder.getInt32Ty();
    case T_LONG:   return builder.getInt64Ty();
    case T_FLOAT:  return builder.getFloatTy();
    case T_DOUBLE: return builder.getDoubleTy();
    default:
      ShouldNotReachHere();
      return nullptr;
  }
}

static llvm::AtomicOrdering unsafe_atomic_ordering(UnsafeAccessKind access_kind) {
  switch (access_kind) {
    // A relaxed operation is non-atomic only when its operation shape is an
    // ordinary load/store. Once the operation itself is atomic (for example,
    // weakCompareAndSetPlain), LLVM spells relaxed ordering `monotonic`.
    // LLVM has no opaque ordering, so monotonic is also its conservative
    // atomic counterpart.
    case UnsafeAccessKind::Relaxed:
    case UnsafeAccessKind::Opaque:
      return llvm::AtomicOrdering::Monotonic;
    case UnsafeAccessKind::Volatile:
      return llvm::AtomicOrdering::SequentiallyConsistent;
    case UnsafeAccessKind::Acquire:
      return llvm::AtomicOrdering::Acquire;
    case UnsafeAccessKind::Release:
      return llvm::AtomicOrdering::Release;
    default:
      ShouldNotReachHere();
      return llvm::AtomicOrdering::NotAtomic;
  }
}

static bool unsafe_atomic_order_is_valid(UnsafeAtomicKind kind,
                                         UnsafeAccessKind access_kind) {
  switch (kind) {
    case UnsafeAtomicKind::CompareAndSet:
      return access_kind == UnsafeAccessKind::Volatile;
    case UnsafeAtomicKind::WeakCompareAndSet:
      return access_kind == UnsafeAccessKind::Relaxed ||
             access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Release ||
             access_kind == UnsafeAccessKind::Volatile;
    case UnsafeAtomicKind::CompareAndExchange:
      return access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Release ||
             access_kind == UnsafeAccessKind::Volatile;
    case UnsafeAtomicKind::GetAdd:
    case UnsafeAtomicKind::GetSet:
      return access_kind == UnsafeAccessKind::Volatile;
  }
  ShouldNotReachHere();
  return false;
}

static bool unsafe_has_known_zero_raw_address(llvm::Value* base,
                                              llvm::Value* offset) {
  const llvm::Constant* constant_base = llvm::dyn_cast<llvm::Constant>(base);
  if (constant_base == nullptr || !constant_base->isNullValue()) {
    return false;
  }
  const llvm::ConstantInt* constant_offset = llvm::dyn_cast<llvm::ConstantInt>(offset);
  return constant_offset != nullptr && constant_offset->isZero();
}

static bool unsafe_raw_access_may_be_zero(llvm::Value* base,
                                           llvm::Value* offset) {
  const llvm::Constant* constant_base = llvm::dyn_cast<llvm::Constant>(base);
  if (constant_base != nullptr && !constant_base->isNullValue()) {
    return false;
  }
  const llvm::ConstantInt* constant_offset = llvm::dyn_cast<llvm::ConstantInt>(offset);
  return constant_offset == nullptr || constant_offset->isZero();
}

static uint64_t unsafe_atomic_alignment_mask(BasicType type) {
  switch (type) {
    case T_BOOLEAN:
    case T_BYTE:
      return 0;
    case T_SHORT:
    case T_CHAR:
      return 1;
    case T_INT:
    case T_FLOAT:
      return 3;
    case T_LONG:
    case T_DOUBLE:
      return 7;
    default:
      ShouldNotReachHere();
      return 0;
  }
}

static bool unsafe_has_known_misaligned_atomic_offset(
    BasicType type, llvm::Value* offset) {
  const llvm::ConstantInt* constant_offset = llvm::dyn_cast<llvm::ConstantInt>(offset);
  return constant_offset != nullptr &&
      (constant_offset->getZExtValue() & unsafe_atomic_alignment_mask(type)) != 0;
}

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
static bool is_provably_null_oop_impl(
    llvm::Value* value, llvm::SmallPtrSetImpl<llvm::Value*>& visiting) {
  value = value->stripPointerCasts();
  if (llvm::isa<llvm::ConstantPointerNull>(value)) {
    return true;
  }

  llvm::PHINode* phi = llvm::dyn_cast<llvm::PHINode>(value);
  if (phi == nullptr || !visiting.insert(phi).second) {
    return false;
  }

  bool has_non_self_input = false;
  for (llvm::Value* incoming : phi->incoming_values()) {
    incoming = incoming->stripPointerCasts();
    if (incoming == phi) {
      continue;
    }
    has_non_self_input = true;
    if (!is_provably_null_oop_impl(incoming, visiting)) {
      visiting.erase(phi);
      return false;
    }
  }

  visiting.erase(phi);
  return has_non_self_input;
}

static bool is_provably_null_oop(llvm::Value* value) {
  llvm::SmallPtrSet<llvm::Value*, 8> visiting;
  return is_provably_null_oop_impl(value, visiting);
}
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

    // Unsafe park/unpark runtime entries.
    case vmIntrinsics::_park:
    case vmIntrinsics::_unpark:

    // Object monitor notifications.
    case vmIntrinsics::_notify:
    case vmIntrinsics::_notifyAll:

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

    // floating-point range checks
    case vmIntrinsics::_floatIsFinite:
    case vmIntrinsics::_floatIsInfinite:
    case vmIntrinsics::_doubleIsFinite:
    case vmIntrinsics::_doubleIsInfinite:
    // Unsafe plain get/put. Heap reference accesses require compressed-oop
    // conversion and collector barriers; raw references stay on the Java path.
    case vmIntrinsics::_getReference:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_getBoolean:
    case vmIntrinsics::_getByte:
    case vmIntrinsics::_getShort:
    case vmIntrinsics::_getChar:
    case vmIntrinsics::_getInt:
    case vmIntrinsics::_getLong:
    case vmIntrinsics::_getFloat:
    case vmIntrinsics::_getDouble:
    case vmIntrinsics::_putReference:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_putBoolean:
    case vmIntrinsics::_putByte:
    case vmIntrinsics::_putShort:
    case vmIntrinsics::_putChar:
    case vmIntrinsics::_putInt:
    case vmIntrinsics::_putLong:
    case vmIntrinsics::_putFloat:
    case vmIntrinsics::_putDouble:

    // Unsafe unaligned primitive get/put.
    case vmIntrinsics::_getShortUnaligned:
    case vmIntrinsics::_getCharUnaligned:
    case vmIntrinsics::_getIntUnaligned:
    case vmIntrinsics::_getLongUnaligned:
    case vmIntrinsics::_putShortUnaligned:
    case vmIntrinsics::_putCharUnaligned:
    case vmIntrinsics::_putIntUnaligned:
    case vmIntrinsics::_putLongUnaligned:

    // Unsafe volatile get/put.
    case vmIntrinsics::_getReferenceVolatile:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_getBooleanVolatile:
    case vmIntrinsics::_getByteVolatile:
    case vmIntrinsics::_getShortVolatile:
    case vmIntrinsics::_getCharVolatile:
    case vmIntrinsics::_getIntVolatile:
    case vmIntrinsics::_getLongVolatile:
    case vmIntrinsics::_getFloatVolatile:
    case vmIntrinsics::_getDoubleVolatile:
    case vmIntrinsics::_putReferenceVolatile:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_putBooleanVolatile:
    case vmIntrinsics::_putByteVolatile:
    case vmIntrinsics::_putShortVolatile:
    case vmIntrinsics::_putCharVolatile:
    case vmIntrinsics::_putIntVolatile:
    case vmIntrinsics::_putLongVolatile:
    case vmIntrinsics::_putFloatVolatile:
    case vmIntrinsics::_putDoubleVolatile:

    // Unsafe acquire loads.
    case vmIntrinsics::_getReferenceAcquire:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_getBooleanAcquire:
    case vmIntrinsics::_getByteAcquire:
    case vmIntrinsics::_getShortAcquire:
    case vmIntrinsics::_getCharAcquire:
    case vmIntrinsics::_getIntAcquire:
    case vmIntrinsics::_getLongAcquire:
    case vmIntrinsics::_getFloatAcquire:
    case vmIntrinsics::_getDoubleAcquire:

    // Unsafe release stores.
    case vmIntrinsics::_putReferenceRelease:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_putBooleanRelease:
    case vmIntrinsics::_putByteRelease:
    case vmIntrinsics::_putShortRelease:
    case vmIntrinsics::_putCharRelease:
    case vmIntrinsics::_putIntRelease:
    case vmIntrinsics::_putLongRelease:
    case vmIntrinsics::_putFloatRelease:
    case vmIntrinsics::_putDoubleRelease:

    // Unsafe opaque get/put.
    case vmIntrinsics::_getReferenceOpaque:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_getBooleanOpaque:
    case vmIntrinsics::_getByteOpaque:
    case vmIntrinsics::_getShortOpaque:
    case vmIntrinsics::_getCharOpaque:
    case vmIntrinsics::_getIntOpaque:
    case vmIntrinsics::_getLongOpaque:
    case vmIntrinsics::_getFloatOpaque:
    case vmIntrinsics::_getDoubleOpaque:
    case vmIntrinsics::_putReferenceOpaque:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_putBooleanOpaque:
    case vmIntrinsics::_putByteOpaque:
    case vmIntrinsics::_putShortOpaque:
    case vmIntrinsics::_putCharOpaque:
    case vmIntrinsics::_putIntOpaque:
    case vmIntrinsics::_putLongOpaque:
    case vmIntrinsics::_putFloatOpaque:
    case vmIntrinsics::_putDoubleOpaque:

    // Unsafe CAS. Reference atomics use collector-aware JavaOps.
    case vmIntrinsics::_compareAndSetReference:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_compareAndSetByte:
    case vmIntrinsics::_compareAndSetShort:
    case vmIntrinsics::_compareAndSetInt:
    case vmIntrinsics::_compareAndSetLong:

    // Unsafe weak CAS: Plain, Acquire, Release, then Volatile.
    case vmIntrinsics::_weakCompareAndSetReferencePlain:
    case vmIntrinsics::_weakCompareAndSetReferenceAcquire:
    case vmIntrinsics::_weakCompareAndSetReferenceRelease:
    case vmIntrinsics::_weakCompareAndSetReference:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_weakCompareAndSetBytePlain:
    case vmIntrinsics::_weakCompareAndSetByteAcquire:
    case vmIntrinsics::_weakCompareAndSetByteRelease:
    case vmIntrinsics::_weakCompareAndSetByte:
    case vmIntrinsics::_weakCompareAndSetShortPlain:
    case vmIntrinsics::_weakCompareAndSetShortAcquire:
    case vmIntrinsics::_weakCompareAndSetShortRelease:
    case vmIntrinsics::_weakCompareAndSetShort:
    case vmIntrinsics::_weakCompareAndSetIntPlain:
    case vmIntrinsics::_weakCompareAndSetIntAcquire:
    case vmIntrinsics::_weakCompareAndSetIntRelease:
    case vmIntrinsics::_weakCompareAndSetInt:
    case vmIntrinsics::_weakCompareAndSetLongPlain:
    case vmIntrinsics::_weakCompareAndSetLongAcquire:
    case vmIntrinsics::_weakCompareAndSetLongRelease:
    case vmIntrinsics::_weakCompareAndSetLong:

    // Unsafe compare-and-exchange: Volatile, Acquire, then Release.
    case vmIntrinsics::_compareAndExchangeReference:
    case vmIntrinsics::_compareAndExchangeReferenceAcquire:
    case vmIntrinsics::_compareAndExchangeReferenceRelease:
      return UseG1GC || UseSerialGC;
    case vmIntrinsics::_compareAndExchangeByte:
    case vmIntrinsics::_compareAndExchangeByteAcquire:
    case vmIntrinsics::_compareAndExchangeByteRelease:
    case vmIntrinsics::_compareAndExchangeShort:
    case vmIntrinsics::_compareAndExchangeShortAcquire:
    case vmIntrinsics::_compareAndExchangeShortRelease:
    case vmIntrinsics::_compareAndExchangeInt:
    case vmIntrinsics::_compareAndExchangeIntAcquire:
    case vmIntrinsics::_compareAndExchangeIntRelease:
    case vmIntrinsics::_compareAndExchangeLong:
    case vmIntrinsics::_compareAndExchangeLongAcquire:
    case vmIntrinsics::_compareAndExchangeLongRelease:

    case vmIntrinsics::_getAndAddByte:
    case vmIntrinsics::_getAndAddShort:
    case vmIntrinsics::_getAndAddInt:
    case vmIntrinsics::_getAndAddLong:
    case vmIntrinsics::_getAndSetByte:
    case vmIntrinsics::_getAndSetShort:
    case vmIntrinsics::_getAndSetInt:
    case vmIntrinsics::_getAndSetLong:
      return true;
    case vmIntrinsics::_getAndSetReference:
      return UseG1GC || UseSerialGC;

    // fence
    case vmIntrinsics::_loadFence:
    case vmIntrinsics::_storeFence:
    case vmIntrinsics::_storeStoreFence:
    case vmIntrinsics::_fullFence:

    // Preconditions
    case vmIntrinsics::_Preconditions_checkIndex:
    case vmIntrinsics::_Preconditions_checkLongIndex:

    // compare unsigned
    case vmIntrinsics::_compareUnsigned_i:
    case vmIntrinsics::_compareUnsigned_l:

    // divide unsigned
    case vmIntrinsics::_divideUnsigned_i:
    case vmIntrinsics::_divideUnsigned_l:
    // unsigned remainder
    case vmIntrinsics::_remainderUnsigned_i:
    case vmIntrinsics::_remainderUnsigned_l:

    // count leading/trailing zeros
    // No CPU gating: LLVM lowers ctlz/cttz to native sequences on both x86-64
    // (bsr/bsf fallback when LZCNT/TZCNT are absent) and aarch64 (CLZ, RBIT+CLZ),
    // never to a libcall. Matches C2, which always intrinsifies these.
    case vmIntrinsics::_numberOfLeadingZeros_i:
    case vmIntrinsics::_numberOfLeadingZeros_l:
    case vmIntrinsics::_numberOfTrailingZeros_i:
    case vmIntrinsics::_numberOfTrailingZeros_l:

    // reverse: LLVM bitreverse has exact i32/i64 Java operand widths and
    // lowers to a target-appropriate instruction sequence.
    case vmIntrinsics::_reverse_i:
    case vmIntrinsics::_reverse_l:

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

    // arraycopy
    case vmIntrinsics::_arraycopy:
    // hash code
    case vmIntrinsics::_hashCode:
    case vmIntrinsics::_identityHashCode:
      return true;

    // Single-block SHA compression. Availability is checked again by the
    // lowering because platform stubs are generated conditionally.
    case vmIntrinsics::_sha_implCompress:
    case vmIntrinsics::_sha2_implCompress:
    case vmIntrinsics::_sha5_implCompress:
    case vmIntrinsics::_sha3_implCompress:

    // StringUTF16 trusted single-code-unit access
    case vmIntrinsics::_getCharStringU:
    case vmIntrinsics::_putCharStringU:
      return true;

    default:
      return false;
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

    case vmIntrinsics::_reverse_i:
    case vmIntrinsics::_reverse_l:
      return emit_llvm_builtin(llvm::Intrinsic::bitreverse);

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
    // Exact receiver types are folded later by LLVM's ConstantFieldFolding
    // pass using the GetJavaMirror VM callback. This lowering keeps the
    // dynamic JavaOp so CFF can also see type information propagated by the
    // inline/PEA pipeline.
    //
    // TODO: Optimize the comparison between class pointers.
    case vmIntrinsics::_getClass:
      return lower_java_op("jeandle.get_class",
                           {CTRL_NONE, MEM_READ});

    // Thread.currentThread()
    case vmIntrinsics::_currentThread:
      return lower_java_op("jeandle.current_thread_obj",
                           {CTRL_NONE, MEM_READ});

    case vmIntrinsics::_park:
    case vmIntrinsics::_unpark:
      return lower_unsafe_park_unpark(id);

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

    case vmIntrinsics::_notify:
    case vmIntrinsics::_notifyAll:
      return lower_object_notify(id);

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

    // floating-point range checks
    case vmIntrinsics::_floatIsFinite:
    case vmIntrinsics::_floatIsInfinite:
    case vmIntrinsics::_doubleIsFinite:
    case vmIntrinsics::_doubleIsInfinite:
      return lower_fp_range_check(id);

    // floatToFloat16/float16ToFloat
    case vmIntrinsics::_floatToFloat16:
    case vmIntrinsics::_float16ToFloat:
      return lower_float16_convert(id);

    case vmIntrinsics::_getReference:
      return lower_unsafe_access(false, T_OBJECT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getBoolean:
      return lower_unsafe_access(false, T_BOOLEAN, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getByte:
      return lower_unsafe_access(false, T_BYTE, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getShort:
      return lower_unsafe_access(false, T_SHORT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getChar:
      return lower_unsafe_access(false, T_CHAR, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getInt:
      return lower_unsafe_access(false, T_INT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getLong:
      return lower_unsafe_access(false, T_LONG, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getFloat:
      return lower_unsafe_access(false, T_FLOAT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getDouble:
      return lower_unsafe_access(false, T_DOUBLE, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putReference:
      return lower_unsafe_access(true, T_OBJECT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putBoolean:
      return lower_unsafe_access(true, T_BOOLEAN, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putByte:
      return lower_unsafe_access(true, T_BYTE, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putShort:
      return lower_unsafe_access(true, T_SHORT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putChar:
      return lower_unsafe_access(true, T_CHAR, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putInt:
      return lower_unsafe_access(true, T_INT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putLong:
      return lower_unsafe_access(true, T_LONG, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putFloat:
      return lower_unsafe_access(true, T_FLOAT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putDouble:
      return lower_unsafe_access(true, T_DOUBLE, UnsafeAccessKind::Relaxed);

    // Unsafe unaligned get/put.
    case vmIntrinsics::_getShortUnaligned:
      return lower_unsafe_access(false, T_SHORT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getCharUnaligned:
      return lower_unsafe_access(false, T_CHAR, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getIntUnaligned:
      return lower_unsafe_access(false, T_INT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_getLongUnaligned:
      return lower_unsafe_access(false, T_LONG, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putShortUnaligned:
      return lower_unsafe_access(true, T_SHORT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putCharUnaligned:
      return lower_unsafe_access(true, T_CHAR, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putIntUnaligned:
      return lower_unsafe_access(true, T_INT, UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_putLongUnaligned:
      return lower_unsafe_access(true, T_LONG, UnsafeAccessKind::Relaxed);

    // Unsafe volatile get/put. Primitive accesses use seq_cst system-scope
    // atomics; reference accesses use collector-aware JavaOps.
    case vmIntrinsics::_getReferenceVolatile:
      return lower_unsafe_access(false, T_OBJECT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getBooleanVolatile:
      return lower_unsafe_access(false, T_BOOLEAN, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getByteVolatile:
      return lower_unsafe_access(false, T_BYTE, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getShortVolatile:
      return lower_unsafe_access(false, T_SHORT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getCharVolatile:
      return lower_unsafe_access(false, T_CHAR, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getIntVolatile:
      return lower_unsafe_access(false, T_INT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getLongVolatile:
      return lower_unsafe_access(false, T_LONG, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getFloatVolatile:
      return lower_unsafe_access(false, T_FLOAT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getDoubleVolatile:
      return lower_unsafe_access(false, T_DOUBLE, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putReferenceVolatile:
      return lower_unsafe_access(true, T_OBJECT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putBooleanVolatile:
      return lower_unsafe_access(true, T_BOOLEAN, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putByteVolatile:
      return lower_unsafe_access(true, T_BYTE, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putShortVolatile:
      return lower_unsafe_access(true, T_SHORT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putCharVolatile:
      return lower_unsafe_access(true, T_CHAR, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putIntVolatile:
      return lower_unsafe_access(true, T_INT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putLongVolatile:
      return lower_unsafe_access(true, T_LONG, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putFloatVolatile:
      return lower_unsafe_access(true, T_FLOAT, UnsafeAccessKind::Volatile);
    case vmIntrinsics::_putDoubleVolatile:
      return lower_unsafe_access(true, T_DOUBLE, UnsafeAccessKind::Volatile);

    // Unsafe acquire loads.
    case vmIntrinsics::_getReferenceAcquire:
      return lower_unsafe_access(false, T_OBJECT, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getBooleanAcquire:
      return lower_unsafe_access(false, T_BOOLEAN, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getByteAcquire:
      return lower_unsafe_access(false, T_BYTE, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getShortAcquire:
      return lower_unsafe_access(false, T_SHORT, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getCharAcquire:
      return lower_unsafe_access(false, T_CHAR, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getIntAcquire:
      return lower_unsafe_access(false, T_INT, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getLongAcquire:
      return lower_unsafe_access(false, T_LONG, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getFloatAcquire:
      return lower_unsafe_access(false, T_FLOAT, UnsafeAccessKind::Acquire);
    case vmIntrinsics::_getDoubleAcquire:
      return lower_unsafe_access(false, T_DOUBLE, UnsafeAccessKind::Acquire);

    // Unsafe release stores.
    case vmIntrinsics::_putReferenceRelease:
      return lower_unsafe_access(true, T_OBJECT, UnsafeAccessKind::Release);
    case vmIntrinsics::_putBooleanRelease:
      return lower_unsafe_access(true, T_BOOLEAN, UnsafeAccessKind::Release);
    case vmIntrinsics::_putByteRelease:
      return lower_unsafe_access(true, T_BYTE, UnsafeAccessKind::Release);
    case vmIntrinsics::_putShortRelease:
      return lower_unsafe_access(true, T_SHORT, UnsafeAccessKind::Release);
    case vmIntrinsics::_putCharRelease:
      return lower_unsafe_access(true, T_CHAR, UnsafeAccessKind::Release);
    case vmIntrinsics::_putIntRelease:
      return lower_unsafe_access(true, T_INT, UnsafeAccessKind::Release);
    case vmIntrinsics::_putLongRelease:
      return lower_unsafe_access(true, T_LONG, UnsafeAccessKind::Release);
    case vmIntrinsics::_putFloatRelease:
      return lower_unsafe_access(true, T_FLOAT, UnsafeAccessKind::Release);
    case vmIntrinsics::_putDoubleRelease:
      return lower_unsafe_access(true, T_DOUBLE, UnsafeAccessKind::Release);

    // Unsafe opaque get/put maps to monotonic system-scope atomics.
    case vmIntrinsics::_getReferenceOpaque:
      return lower_unsafe_access(false, T_OBJECT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getBooleanOpaque:
      return lower_unsafe_access(false, T_BOOLEAN, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getByteOpaque:
      return lower_unsafe_access(false, T_BYTE, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getShortOpaque:
      return lower_unsafe_access(false, T_SHORT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getCharOpaque:
      return lower_unsafe_access(false, T_CHAR, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getIntOpaque:
      return lower_unsafe_access(false, T_INT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getLongOpaque:
      return lower_unsafe_access(false, T_LONG, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getFloatOpaque:
      return lower_unsafe_access(false, T_FLOAT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_getDoubleOpaque:
      return lower_unsafe_access(false, T_DOUBLE, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putReferenceOpaque:
      return lower_unsafe_access(true, T_OBJECT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putBooleanOpaque:
      return lower_unsafe_access(true, T_BOOLEAN, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putByteOpaque:
      return lower_unsafe_access(true, T_BYTE, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putShortOpaque:
      return lower_unsafe_access(true, T_SHORT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putCharOpaque:
      return lower_unsafe_access(true, T_CHAR, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putIntOpaque:
      return lower_unsafe_access(true, T_INT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putLongOpaque:
      return lower_unsafe_access(true, T_LONG, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putFloatOpaque:
      return lower_unsafe_access(true, T_FLOAT, UnsafeAccessKind::Opaque);
    case vmIntrinsics::_putDoubleOpaque:
      return lower_unsafe_access(true, T_DOUBLE, UnsafeAccessKind::Opaque);

    // Unsafe compare-and-set.
    case vmIntrinsics::_compareAndSetReference:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::CompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndSetByte:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::CompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndSetShort:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::CompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndSetInt:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::CompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndSetLong:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::CompareAndSet,
                                 UnsafeAccessKind::Volatile);

    // Unsafe weak compare-and-set: Plain, Acquire, Release, then Volatile.
    case vmIntrinsics::_weakCompareAndSetReferencePlain:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_weakCompareAndSetReferenceAcquire:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_weakCompareAndSetReferenceRelease:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_weakCompareAndSetReference:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_weakCompareAndSetBytePlain:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_weakCompareAndSetByteAcquire:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_weakCompareAndSetByteRelease:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_weakCompareAndSetByte:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_weakCompareAndSetShortPlain:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_weakCompareAndSetShortAcquire:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_weakCompareAndSetShortRelease:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_weakCompareAndSetShort:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_weakCompareAndSetIntPlain:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_weakCompareAndSetIntAcquire:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_weakCompareAndSetIntRelease:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_weakCompareAndSetInt:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_weakCompareAndSetLongPlain:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Relaxed);
    case vmIntrinsics::_weakCompareAndSetLongAcquire:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_weakCompareAndSetLongRelease:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_weakCompareAndSetLong:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::WeakCompareAndSet,
                                 UnsafeAccessKind::Volatile);

    // Unsafe compare-and-exchange: Volatile, Acquire, then Release.
    case vmIntrinsics::_compareAndExchangeReference:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndExchangeReferenceAcquire:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_compareAndExchangeReferenceRelease:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_compareAndExchangeByte:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndExchangeByteAcquire:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_compareAndExchangeByteRelease:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_compareAndExchangeShort:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndExchangeShortAcquire:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_compareAndExchangeShortRelease:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_compareAndExchangeInt:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndExchangeIntAcquire:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_compareAndExchangeIntRelease:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_compareAndExchangeLong:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_compareAndExchangeLongAcquire:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Acquire);
    case vmIntrinsics::_compareAndExchangeLongRelease:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::CompareAndExchange,
                                 UnsafeAccessKind::Release);
    case vmIntrinsics::_getAndAddByte:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::GetAdd,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndAddShort:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::GetAdd,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndAddInt:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::GetAdd,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndAddLong:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::GetAdd,
                                 UnsafeAccessKind::Volatile);

    case vmIntrinsics::_getAndSetByte:
      return lower_unsafe_atomic(T_BYTE, UnsafeAtomicKind::GetSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndSetShort:
      return lower_unsafe_atomic(T_SHORT, UnsafeAtomicKind::GetSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndSetInt:
      return lower_unsafe_atomic(T_INT, UnsafeAtomicKind::GetSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndSetLong:
      return lower_unsafe_atomic(T_LONG, UnsafeAtomicKind::GetSet,
                                 UnsafeAccessKind::Volatile);
    case vmIntrinsics::_getAndSetReference:
      return lower_unsafe_atomic(T_OBJECT, UnsafeAtomicKind::GetSet,
                                 UnsafeAccessKind::Volatile);

    // Unsafe fences.
    case vmIntrinsics::_loadFence:
    case vmIntrinsics::_storeFence:
    case vmIntrinsics::_fullFence:
      _interp->null_check(_interp->_jvm->peek_value(0).value());
      return lower_llvm_fence(id);
    case vmIntrinsics::_storeStoreFence:
      _interp->null_check(_interp->_jvm->peek_value(0).value());
      return lower_store_store_fence();


    // onSpinWait
    case vmIntrinsics::_onSpinWait:
      return lower_spin_wait_hint();


    // Preconditions
    case vmIntrinsics::_Preconditions_checkIndex:
      return lower_preconditions_check_index(T_INT);
    case vmIntrinsics::_Preconditions_checkLongIndex:
      return lower_preconditions_check_index(T_LONG);

    // CompareUnsigned
    case vmIntrinsics::_compareUnsigned_i:
    case vmIntrinsics::_compareUnsigned_l:
      return lower_compare_unsigned(id);

    // divide unsigned
    case vmIntrinsics::_divideUnsigned_i:
    case vmIntrinsics::_divideUnsigned_l:
      return lower_divide_unsigned(id);
    // RemainderUnsigned
    case vmIntrinsics::_remainderUnsigned_i:
    case vmIntrinsics::_remainderUnsigned_l:
      return lower_remainder_unsigned(id);

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

    // arraycopy
    case vmIntrinsics::_arraycopy:
      return lower_arraycopy();

    case vmIntrinsics::_sha_implCompress:
    case vmIntrinsics::_sha2_implCompress:
    case vmIntrinsics::_sha5_implCompress:
    case vmIntrinsics::_sha3_implCompress:
      return lower_digestBase_implCompress(id);
    // HashCode
    case vmIntrinsics::_identityHashCode:
    case vmIntrinsics::_hashCode:
      return lower_hash_code(id);

    // StringUTF16 trusted single-code-unit access.
    case vmIntrinsics::_getCharStringU:
      return lower_string_char_access(/*is_store=*/false);
    case vmIntrinsics::_putCharStringU:
      return lower_string_char_access(/*is_store=*/true);

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
// lower_digestBase_implCompress — single-block SHA compression
// =============================================================================

bool JeandleIntrinsicLowering::lower_digestBase_implCompress(vmIntrinsics::ID id) {
  ciSignature* sig = _target->signature();
  if (sig->count() != 2 || sig->type_at(0)->basic_type() != T_ARRAY ||
      sig->type_at(1)->basic_type() != T_INT ||
      sig->type_at(0)->name() == nullptr ||
      strcmp(sig->type_at(0)->name(), "[B") != 0) {
    return false;
  }

  const char* state_signature = nullptr;
  const char* stub_name = nullptr;
  BasicType state_element_type = T_ILLEGAL;
  JeandleRuntimeCalleeFn callee_fn = nullptr;
  switch (id) {
    case vmIntrinsics::_sha_implCompress:
      state_signature = "[I";
      state_element_type = T_INT;
      stub_name = "StubRoutines_sha1_implCompress";
      callee_fn = &JeandleRuntimeRoutine::StubRoutines_sha1_implCompress_callee;
      break;
    case vmIntrinsics::_sha2_implCompress:
      state_signature = "[I";
      state_element_type = T_INT;
      stub_name = "StubRoutines_sha256_implCompress";
      callee_fn = &JeandleRuntimeRoutine::StubRoutines_sha256_implCompress_callee;
      break;
    case vmIntrinsics::_sha5_implCompress:
      state_signature = "[J";
      state_element_type = T_LONG;
      stub_name = "StubRoutines_sha512_implCompress";
      callee_fn = &JeandleRuntimeRoutine::StubRoutines_sha512_implCompress_callee;
      break;
    case vmIntrinsics::_sha3_implCompress:
      state_signature = "[B";
      state_element_type = T_BYTE;
      stub_name = "StubRoutines_sha3_implCompress";
      callee_fn = &JeandleRuntimeRoutine::StubRoutines_sha3_implCompress_callee;
      break;
    default:
      return false;
  }

  // StubRoutines entries are generated only on supported platforms. Never
  // materialize a direct call when the entry is absent.
  if (JeandleRuntimeRoutine::find_routine_entry(stub_name) == nullptr) {
    return false;
  }

  ciInstanceKlass* holder = _target->holder();
  ciField* state_field = holder->get_field_by_name(ciSymbol::make("state"),
                                                   ciSymbol::make(state_signature),
                                                   false);
  if (state_field == nullptr) {
    return false;
  }

  ciField* block_size_field = nullptr;
  if (id == vmIntrinsics::_sha3_implCompress) {
    block_size_field = holder->get_field_by_name(ciSymbol::make("blockSize"),
                                                 ciSymbol::make("I"), false);
    if (block_size_field == nullptr) {
      return false;
    }
  }

  // The intrinsic is entered with receiver, byte[] and offset on the JVM
  // stack. Peek until all optional checks and field lookups have succeeded so
  // a false return leaves the stack untouched for the normal invoke path.
  llvm::Value* receiver = _interp->_jvm->raw_peek(2).value();
  llvm::Value* src = _interp->_jvm->raw_peek(1).value();
  llvm::Value* ofs = _interp->_jvm->raw_peek(0).value();
  _interp->null_check(src);

  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& context = *_interp->_context;
  llvm::Type* java_oop_type = JeandleType::java2llvm(T_OBJECT, context);

  llvm::Value* src_offset = builder.CreateAdd(
      builder.getInt32(arrayOopDesc::base_offset_in_bytes(T_BYTE)), ofs,
      "sha_src_offset");
  llvm::Value* src_start = builder.CreateInBoundsPtrAdd(src, src_offset,
                                                        "sha_src_start");

  llvm::Value* state_field_addr = _interp->compute_instance_field_address(
      receiver, state_field->offset_in_bytes());
  BasicType state_ref_type = state_field->layout_type();
  if (UseCompressedOops && is_reference_type(state_ref_type)) {
    state_ref_type = T_NARROWOOP;
  }
  llvm::Value* state_array = _interp->load_from_address(state_field_addr,
                                                        state_ref_type, false);
  if (state_ref_type == T_NARROWOOP) {
    state_array = builder.CreateAddrSpaceCast(state_array, java_oop_type,
                                              "sha_state_array");
  }

  llvm::Value* state_base = builder.CreateInBoundsPtrAdd(
      state_array, builder.getInt32(arrayOopDesc::base_offset_in_bytes(state_element_type)),
      "sha_state_base");

  _interp->_jvm->ipop();  // ofs
  _interp->_jvm->apop();  // src
  _interp->_jvm->apop();  // receiver

  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE};
  if (block_size_field == nullptr) {
    emit_callsite(callee_fn(_interp->_module), llvm::CallingConv::C,
                  {src_start, state_base}, attrs,
                  /*is_gc_leaf_entry=*/true);
  } else {
    llvm::Value* block_size_addr = _interp->compute_instance_field_address(
        receiver, block_size_field->offset_in_bytes());
    llvm::Value* block_size = _interp->load_from_address(block_size_addr,
                                                         T_INT, false);
    emit_callsite(callee_fn(_interp->_module), llvm::CallingConv::C,
                  {src_start, state_base, block_size}, attrs,
                  /*is_gc_leaf_entry=*/true);
  }
  return true;
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

// ---- lower_unsafe_park_unpark ----
bool JeandleIntrinsicLowering::lower_unsafe_park_unpark(vmIntrinsics::ID id) {
  const char* routine_name = id == vmIntrinsics::_park
      ? "unsafe_park" : "unsafe_unpark";
  if (JeandleRuntimeRoutine::find_routine_entry(routine_name) == nullptr) {
    return false;
  }

  llvm::Module& module = _interp->_module;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::Function* current_thread_fn = module.getFunction("jeandle.current_thread");
  assert(current_thread_fn != nullptr, "jeandle.current_thread must exist");
  llvm::CallInst* current_thread = builder.CreateCall(current_thread_fn);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  // Both helpers enter the VM. park can block, and unpark protects its target
  // with a FastThreadsListHandle; neither is a GC leaf. Both may safepoint
  // inside the VM, and their effects cannot be replayed, so the deopt bundle
  // captured by emit_callsite must describe the post-invoke state: consume
  // the arguments before the callsite is created, exactly as ordinary Java
  // invoke lowering does.
  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};

  if (id == vmIntrinsics::_park) {
    // Logical values, top first: time (long), isAbsolute, Unsafe receiver.
    // Physical raw slots: long placeholder, long value, int, oop.
    // Save the arguments as SSA values first; popping them below only shapes
    // the deopt bundle and does not affect the call arguments.
    llvm::Value* time = _interp->_jvm->peek_value(0).value();
    llvm::Value* is_absolute = _interp->_jvm->peek_value(1).value();
    _interp->_jvm->lpop();
    _interp->_jvm->ipop();
    _interp->_jvm->apop();
    emit_callsite(JeandleRuntimeRoutine::unsafe_park_callee(module),
                  llvm::CallingConv::Hotspot_JIT,
                  {is_absolute, time, current_thread}, attrs);
    return true;
  }

  assert(id == vmIntrinsics::_unpark, "unexpected intrinsic");
  // Logical/physical values, top first: target Thread, Unsafe receiver.
  // Same as park: the saved SSA value feeds the call below, while the pops
  // make the deopt bundle describe the completed, post-invoke stack.
  llvm::Value* thread_oop = _interp->_jvm->peek_value(0).value();
  _interp->_jvm->apop();
  _interp->_jvm->apop();
  emit_callsite(JeandleRuntimeRoutine::unsafe_unpark_callee(module),
                llvm::CallingConv::Hotspot_JIT,
                {thread_oop, current_thread}, attrs);
  return true;
}

bool JeandleIntrinsicLowering::lower_object_notify(vmIntrinsics::ID id) {
  const bool notify_all = id == vmIntrinsics::_notifyAll;
  assert(notify_all || id == vmIntrinsics::_notify, "unexpected intrinsic");

  llvm::Module& module = _interp->_module;
  llvm::Value* receiver = _interp->_jvm->peek_value(0).value();
  llvm::Value* current_thread = _interp->call_java_op("jeandle.current_thread", {});
  _interp->_jvm->apop();
  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};
  llvm::FunctionCallee callee = notify_all
      ? JeandleRuntimeRoutine::monitor_notify_all_callee(module)
      : JeandleRuntimeRoutine::monitor_notify_callee(module);
  emit_callsite(callee, llvm::CallingConv::Hotspot_JIT,
                {receiver, current_thread}, attrs);
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
bool JeandleIntrinsicLowering::lower_preconditions_check_index(BasicType bt) {

  if (_interp->too_many_traps(Deoptimization::Reason_intrinsic) ||
      _interp->too_many_traps(Deoptimization::Reason_range_check)) {
    return false;
  }

  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  int cur_bci = _interp->_bytecodes.cur_bci();
  bool is_long = bt == T_LONG;

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

// ---- lower_remainder_unsigned ----
bool JeandleIntrinsicLowering::lower_remainder_unsigned(vmIntrinsics::ID id) {
  bool is_long = (id == vmIntrinsics::_remainderUnsigned_l);

  llvm::Value* divisor = _interp->_jvm->peek_value().value();
  _interp->zero_check(divisor);

  divisor = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* dividend = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* result = _interp->_ir_builder.CreateURem(dividend, divisor);

  if (is_long) {
    _interp->_jvm->lpush(result);
  } else {
    _interp->_jvm->ipush(result);
  }
  return true;
}

// ---- lower_divide_unsigned ----
bool JeandleIntrinsicLowering::lower_divide_unsigned(vmIntrinsics::ID id) {
  bool is_long = (id == vmIntrinsics::_divideUnsigned_l);

  llvm::Value* divisor = _interp->_jvm->peek_value(0).value();
  _interp->zero_check(divisor);

  divisor = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* dividend = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* result = _interp->_ir_builder.CreateUDiv(dividend, divisor);

  if (is_long) {
    _interp->_jvm->lpush(result);
  } else {
    _interp->_jvm->ipush(result);
  }
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

// ---- lower_fp_range_check ----
// Float/Double.isFinite and isInfinite map directly to llvm.is.fpclass.
bool JeandleIntrinsicLowering::lower_fp_range_check(vmIntrinsics::ID id) {
  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  bool is_double = id == vmIntrinsics::_doubleIsFinite ||
                   id == vmIntrinsics::_doubleIsInfinite;
  bool is_finite = id == vmIntrinsics::_floatIsFinite ||
                   id == vmIntrinsics::_doubleIsFinite;

  llvm::Value* arg = is_double ? _interp->_jvm->dpop() : _interp->_jvm->fpop();
  llvm::FPClassTest mask = is_finite ? llvm::fcFinite : llvm::fcInf;
  llvm::Value* result = builder.CreateIntrinsic(
      llvm::Intrinsic::is_fpclass,
      {arg->getType()},
      {arg, builder.getInt32(static_cast<uint32_t>(mask))});
  _interp->_jvm->ipush(builder.CreateZExt(result, builder.getInt32Ty()));
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
  llvm::Module& m = _interp->_module;

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
      m.getGlobalVariable("java_lang_Class.array_klass_offset", /*AllowInternal=*/true);
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
  llvm::Function* new_array_op = m.getFunction("jeandle.new_array");
  llvm::CallBase* fast_call =
      emit_callsite(new_array_op, llvm::CallingConv::Hotspot_JIT,
                    {klass, length, size_in_bytes, base_offset, length_limit}, fast_attrs);
  // emit_callsite with exception edge moves builder to a new normal_dest block.
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* fast_normal_bb = builder.GetInsertBlock();

  // Slow path: klass not cached or mirror is null → call new_array_from_mirror.
  builder.SetInsertPoint(slow_bb);
  llvm::Function* current_thread_fn = m.getFunction("jeandle.current_thread");
  llvm::CallInst* current_thread = builder.CreateCall(current_thread_fn);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  static constexpr CallSiteAttributeMetadata slow_attrs =
      {CTRL_NEEDS_EXCEPTION_EDGE, MEM_READ | MEM_WRITE};
  llvm::CallBase* slow_call = emit_callsite(
      JeandleRuntimeRoutine::new_array_from_mirror_callee(m),
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

// ---- lower_hash_code ----
//
// Handles both Object.hashCode() and System.identityHashCode(Object).
//
// Mirrors C2's inline_native_hashcode:
//
//   identityHashCode:
//     null      → 0                                          (JVM spec)
//     fast path → jeandle.hashcode_fast(obj) JavaOp (unlocked + hash assigned)
//     slow path → Java call to System.identityHashCode       (locked or hash not yet installed)
//
//   hashCode (virtual/interface):
//     vtable guard → fast path (inline mark word hash) → slow path (Java call)
//
//   hashCode (invokespecial):
//     fast path → slow path (no vtable guard needed; target is definitive)
//
bool JeandleIntrinsicLowering::lower_hash_code(vmIntrinsics::ID id) {
  const bool is_identity = (id == vmIntrinsics::_identityHashCode);
  assert(is_identity || id == vmIntrinsics::_hashCode, "unexpected intrinsic");
  assert(is_identity == _target->is_static(), "unexpected hashCode binding");
  assert(_target->signature()->count() == (is_identity ? 1 : 0),
         "unexpected hashCode signature");

  const Bytecodes::Code bc = _interp->_bytecodes.cur_bc_raw();
  ciMethod* target = const_cast<ciMethod*>(_target);
  ciKlass* declared_holder = _interp->_bytecodes.get_declared_method_holder();
  if (declared_holder == nullptr) {
    return false;
  }

  const bool is_interface = bc == Bytecodes::_invokeinterface;
  if (is_interface && !declared_holder->is_interface()) {
    return false;
  }

  // invokespecial and statically bound invokevirtual sites already have an
  // exact target. invokeinterface needs both an Object.hashCode vtable guard
  // and a declared-interface subtype guard before it may use the fast path.
  const bool needs_virtual_guard = !is_identity &&
                                   (is_interface ||
                                    (bc == Bytecodes::_invokevirtual &&
                                     !target->can_be_statically_bound()));
  const int vtable_idx = needs_virtual_guard
      ? target->vtable_index()
      : Method::invalid_vtable_index;
  assert(!needs_virtual_guard || vtable_idx >= 0,
         "invalid hashCode vtable index: %d", vtable_idx);

  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& builder = _interp->_ir_builder;

  // The single category-1 operand is at raw depth 0. Keep it on the JVM stack
  // through every guard and uncommon trap; emit_java_call expects the normal
  // post-invoke stack and is emitted only after the pop below.
  llvm::Value* obj = _interp->_jvm->raw_peek(0).value();
  if (obj == nullptr) {
    return false;
  }

  // Create all basic blocks upfront so branch targets are always available.
  llvm::BasicBlock* entry_bb  = builder.GetInsertBlock();
  llvm::BasicBlock* not_null_bb =
      is_identity ? llvm::BasicBlock::Create(ctx, "identityHashCode_not_null", _interp->_llvm_func)
                  : nullptr;
  llvm::BasicBlock* hash_fast_bb =
      llvm::BasicBlock::Create(ctx, "hashCode_fast", _interp->_llvm_func);
  llvm::BasicBlock* slow_call_bb =
      llvm::BasicBlock::Create(ctx, "hashCode_slow_call", _interp->_llvm_func);
  llvm::BasicBlock* merge_bb =
      llvm::BasicBlock::Create(ctx, "hashCode_merge", _interp->_llvm_func);

  // --- null check (identityHashCode only) ---
  // System.identityHashCode(null) returns 0 per JVM spec.
  if (is_identity) {
    llvm::PointerType* java_heap_ptr_ty =
        llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
    llvm::Value* obj_is_null = builder.CreateICmpEQ(
        obj, llvm::ConstantPointerNull::get(java_heap_ptr_ty),
        "identityHashCode.obj_is_null");
    builder.CreateCondBr(obj_is_null, merge_bb, not_null_bb);
    builder.SetInsertPoint(not_null_bb);
  }

  // --- vtable guard (genuinely virtual Object.hashCode only) ---
  if (needs_virtual_guard) {
    llvm::CallInst* klass =
        _interp->call_java_op("jeandle.load_klass", {obj});

    if (is_interface) {
      // Delay expansion of the secondary-super scan until after safepoint
      // coverage verification; the direct subtype JavaOp exposes its loop too
      // early in this pipeline.
      llvm::PointerType* c_heap_ptr_ty =
          llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
      Klass* interface_klass =
          reinterpret_cast<Klass*>(declared_holder->constant_encoding());
      llvm::Value* interface_klass_value = builder.CreateIntToPtr(
          builder.getInt64(reinterpret_cast<intptr_t>(interface_klass)),
          c_heap_ptr_ty, "hashCode.interface_klass");
      llvm::CallInst* receiver_is_subtype =
          _interp->call_java_op("jeandle.check_instanceof",
                                {interface_klass_value, obj});

      llvm::BasicBlock* interface_pass_bb = llvm::BasicBlock::Create(
          ctx, "hashCode.interface_check_pass", _interp->_llvm_func);
      llvm::BasicBlock* interface_fail_bb = llvm::BasicBlock::Create(
          ctx, "hashCode.interface_check_fail", _interp->_llvm_func);
      builder.CreateCondBr(receiver_is_subtype,
                           interface_pass_bb, interface_fail_bb);
      _interp->uncommon_trap(Deoptimization::Reason_class_check,
                             Deoptimization::Action_none,
                             interface_fail_bb);
      builder.SetInsertPoint(interface_pass_bb);
    }

    const int entry_offset = in_bytes(Klass::vtable_start_offset()) +
                             vtable_idx * vtableEntry::size_in_bytes() +
                             in_bytes(vtableEntry::method_offset());

    llvm::PointerType* c_heap_ptr_ty =
        llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
    llvm::Value* entry_addr = builder.CreateGEP(
        builder.getInt8Ty(), klass, builder.getInt32(entry_offset),
        "hashCode.vtable_entry");
    llvm::LoadInst* vtable_method = builder.CreateLoad(
        c_heap_ptr_ty, entry_addr, "hashCode.vtable_method");
    vtable_method->setAlignment(llvm::Align(sizeof(void*)));
    vtable_method->setAtomic(llvm::AtomicOrdering::Unordered);

    Method* expected_method = target->get_Method();
    llvm::Value* expected = builder.CreateIntToPtr(
        builder.getInt64((intptr_t)expected_method), c_heap_ptr_ty,
        "hashCode.expected_method");

    llvm::Value* methods_match = builder.CreateICmpEQ(
        vtable_method, expected, "hashCode.methods_match");
    builder.CreateCondBr(methods_match, hash_fast_bb, slow_call_bb);
  } else {
    // No vtable guard needed: branch directly to fast path.
    builder.CreateBr(hash_fast_bb);
  }

  // --- fast path: inline mark-word hash extraction ---
  builder.SetInsertPoint(hash_fast_bb);
  llvm::CallInst* fast_call =
      _interp->call_java_op("jeandle.hashcode_fast", {obj});
  llvm::Value* fast_ok =
      builder.CreateICmpNE(fast_call, builder.getInt32(0), "hashCode.fast_ok");
  builder.CreateCondBr(fast_ok, merge_bb, slow_call_bb);

  // --- slow path: Java call to the actual hashCode / identityHashCode method ---
  _interp->_jvm->apop();
  builder.SetInsertPoint(slow_call_bb);
  llvm::InvokeInst* slow_call = _interp->emit_java_call(
      target, declared_holder, _target->signature(), {obj},
      /*has_receiver=*/!is_identity,
      /*is_method_handle_invoke=*/false, bc);
  RETURN_ON_JEANDLE_ERROR(true);
  llvm::Value* slow_result = slow_call;
  builder.CreateBr(merge_bb);
  llvm::BasicBlock* slow_tail_bb = builder.GetInsertBlock();

  // --- merge ---
  builder.SetInsertPoint(merge_bb);
  _interp->_block->set_tail_llvm_block(merge_bb);
  const unsigned num_incoming = is_identity ? 3 : 2;
  llvm::PHINode* result =
      builder.CreatePHI(builder.getInt32Ty(), num_incoming, "hashCode.result");
  if (is_identity) {
    // null → 0
    result->addIncoming(builder.getInt32(0), entry_bb);
  }
  result->addIncoming(fast_call, hash_fast_bb);
  result->addIncoming(slow_result, slow_tail_bb);

  _interp->_jvm->ipush(result);
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

  // Preserve the mirror-to-Klass query as a phase-1 JavaOp so
  // ConstantFieldFolding can answer it for a constant Class mirror. Dynamic
  // mirrors lower back to the same VM field load before code generation.
  llvm::CallInst* klass =
      _interp->call_java_op("jeandle.load_mirror_klass", {mirror});
  klass->setName("unsafe_allocate.klass");
  llvm::Value* klass_is_null = builder.CreateICmpEQ(
      klass, llvm::ConstantPointerNull::get(c_heap_ptr_ty));
  builder.CreateCondBr(klass_is_null, primitive_trap_bb, allocation_check_bb);
  _interp->uncommon_trap(Deoptimization::Reason_null_check,
                         Deoptimization::Action_make_not_entrant,
                         primitive_trap_bb);

  builder.SetInsertPoint(allocation_check_bb);
  llvm::CallInst* layout =
      _interp->call_java_op("jeandle.layout_helper", {klass});
  layout->setName("unsafe_allocate.layout");

  llvm::CallInst* is_initialized =
      _interp->call_java_op("jeandle.klass_is_initialized", {klass});
  is_initialized->setName("unsafe_allocate.is_initialized");
  llvm::Value* needs_initialization = builder.CreateNot(
      is_initialized, "unsafe_allocate.needs_initialization");

  // Match GraphKit::new_instance's reflective slow test. This also routes
  // arrays, interfaces, abstract classes and java.lang.Class to the runtime.
  llvm::Value* slow_path_bits = builder.CreateAnd(
      layout, builder.getInt32(Klass::_lh_instance_slow_path_bit),
      "unsafe_allocate.slow_path_bits");
  llvm::Value* has_slow_path_bits = builder.CreateICmpNE(
      slow_path_bits, builder.getInt32(0),
      "unsafe_allocate.has_slow_path_bits");
  llvm::Value* needs_slow_path = builder.CreateOr(
      has_slow_path_bits, needs_initialization,
      "unsafe_allocate.needs_slow_path");

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

bool JeandleIntrinsicLowering::lower_unsafe_access(
    bool is_store, BasicType type, UnsafeAccessKind access_kind) {
  // Match C2's Compile::set_has_unsafe_access(true): the signal handler uses
  // the nmethod flag to turn SIGBUS from a raw access into InternalError.
  JeandleCompilation::current()->set_has_unsafe_access(true);
  assert(type >= T_BOOLEAN && type <= T_OBJECT,
         "unexpected Unsafe access type");
  assert(!is_store || access_kind != UnsafeAccessKind::Acquire,
         "acquire ordering is valid only for loads");
  assert(is_store || access_kind != UnsafeAccessKind::Release,
         "release ordering is valid only for stores");

  if (type == T_OBJECT) {
    return is_store ? lower_unsafe_reference_store(access_kind)
                    : lower_unsafe_reference_load(access_kind);
  }

  const bool uses_atomic_ir = access_kind != UnsafeAccessKind::Relaxed;
  const int offset_depth = is_store ? 1 : 0;
  const int base_depth = is_store ? 2 : 1;
  if (!guard_unsafe_primitive_access(type, offset_depth, base_depth,
                                     uses_atomic_ir)) {
    return false;
  }

  if (!uses_atomic_ir) {
    return lower_unsafe_plain_primitive_access(type, is_store);
  }
  return lower_unsafe_ordered_primitive_access(type, is_store, access_kind);
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
                         Deoptimization::Action_none, ov_bb);

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

// ---- generate_guard ----
llvm::BasicBlock* JeandleIntrinsicLowering::generate_guard(
    llvm::Value* test, llvm::BasicBlock* slow_bb, float true_prob) {

  llvm::BasicBlock* control_bb = _interp->_ir_builder.GetInsertBlock();
  if (control_bb == nullptr) {
    return nullptr;
  }

  llvm::ConstantInt* constant_test = llvm::dyn_cast<llvm::ConstantInt>(test);
  if (constant_test != nullptr && constant_test->isZero()) {
    return nullptr;
  }

  // Build an if node and its projections.
  // If test is true we take the slow path, which we assume is uncommon.
  llvm::Function* function = control_bb->getParent();
  llvm::LLVMContext& ctx = function->getContext();
  llvm::BasicBlock* if_slow =
      llvm::BasicBlock::Create(ctx, "arraycopy_guard_slow", function);
  llvm::BasicBlock* if_fast =
      llvm::BasicBlock::Create(ctx, "arraycopy_guard_fast", function);

  llvm::IRBuilder<> builder(control_bb);
  llvm::BranchInst* guard = builder.CreateCondBr(test, if_slow, if_fast);
  assert(true_prob > 0.0f && true_prob < 1.0f,
         "branch probability must be in (0, 1)");
  constexpr uint32_t BranchWeightScale = 1000000;
  uint32_t true_weight = static_cast<uint32_t>(true_prob * BranchWeightScale);
  assert(true_weight > 0 && true_weight < BranchWeightScale,
         "branch probability must map to non-zero branch weights");
  uint32_t false_weight = BranchWeightScale - true_weight;
  llvm::MDBuilder mdb(ctx);
  guard->setMetadata(llvm::LLVMContext::MD_prof,
                     mdb.createBranchWeights(true_weight, false_weight));

  llvm::IRBuilder<> slow_builder(if_slow);
  if (slow_bb != nullptr) {
    slow_builder.CreateBr(slow_bb);
  }

  _interp->_ir_builder.SetInsertPoint(if_fast);
  return if_slow;
}

llvm::BasicBlock* JeandleIntrinsicLowering::generate_fair_guard(
    llvm::Value* test, llvm::BasicBlock* region_bb) {
  return generate_guard(test, region_bb, PROB_FAIR);
}

void JeandleIntrinsicLowering::generate_negative_guard(
    llvm::Value* index, llvm::BasicBlock* slow_bb) {

  llvm::BasicBlock* control_bb = _interp->_ir_builder.GetInsertBlock();
  if (control_bb == nullptr) {
    return;
  }

  llvm::ConstantInt* constant_index = llvm::dyn_cast<llvm::ConstantInt>(index);
  if (constant_index != nullptr && constant_index->getSExtValue() >= 0) {
    return;
  }

  // TODO: Match C2 more closely by consulting LLVM range information here.
  // C2 skips this guard when _gvn.type(index)->higher_equal(TypeInt::POS).
  // Jeandle currently only folds constant non-negative indexes at lowering
  // time; later LLVM passes may still remove the generated compare.
  llvm::IRBuilder<> builder(control_bb);
  llvm::Value* is_negative = builder.CreateICmpSLT(
      index, builder.getInt32(0), "arraycopy_index_is_negative");
  llvm::BasicBlock* slow_control =
      generate_guard(is_negative, slow_bb, PROB_MIN);
  if (slow_control != nullptr) {
    // C2 creates CastII(index, TypeInt::POS) on the fast path. Materialize the
    // equivalent LLVM fact so later optimization can use the guarded range.
    llvm::IRBuilder<> fast_builder(_interp->_ir_builder.GetInsertBlock());
    llvm::Value* non_negative =
        fast_builder.CreateNot(is_negative, "arraycopy_index_non_negative");
    fast_builder.CreateIntrinsic(llvm::Intrinsic::assume, {}, {non_negative});
    _interp->_ir_builder.SetInsertPoint(fast_builder.GetInsertBlock());
  }
}

void JeandleIntrinsicLowering::generate_limit_guard(
    llvm::Value* offset, llvm::Value* copy_length,
    llvm::Value* array_length, llvm::BasicBlock* slow_bb) {
  llvm::BasicBlock* control_bb = _interp->_ir_builder.GetInsertBlock();
  if (control_bb == nullptr) {
    return;
  }

  llvm::ConstantInt* constant_offset = llvm::dyn_cast<llvm::ConstantInt>(offset);
  bool zero_offset = constant_offset !=nullptr && constant_offset->isZero();
  // TODO: Match C2's subseq_length->eqv_uncast(array_length). This currently
  // only catches exact SSA value identity; Jeandle does not yet strip
  // control-dependent integer range casts/assumes like C2 Node::uncast().
  if (zero_offset && copy_length == array_length) {
    return;
  }

  llvm::IRBuilder<> builder(control_bb);
  llvm::Value* last = copy_length;
  if (!zero_offset) {
    last = builder.CreateAdd(copy_length, offset, "arraycopy_end");
  }
  llvm::Value* is_over = builder.CreateICmpULT( array_length, last, "arraycopy_over");
  generate_guard(is_over, slow_bb, PROB_MIN);
}

// ---- generate_array_guard_common ----
// TODO: Add C2-like speculative/profiled array guards before this point, so
// weak Object-typed arraycopy sites can avoid repeated runtime klass loads.
llvm::BasicBlock* JeandleIntrinsicLowering::generate_array_guard_common(
    llvm::Value* klass, llvm::BasicBlock* region_bb,
    bool obj_array, bool not_array) {

  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::BasicBlock* control_bb = builder.GetInsertBlock();
  if (control_bb == nullptr) {
    return nullptr;
  }

  jint layout_con = 0;
  llvm::Value* layout_val = _interp->get_layout_helper(klass, layout_con);
  if (layout_val == nullptr) {
    bool query = obj_array
        ? Klass::layout_helper_is_objArray(layout_con)
        : Klass::layout_helper_is_array(layout_con);
    if (query == not_array) {
      return nullptr;
    } else {
      llvm::BasicBlock* always_branch = control_bb;
      if (region_bb != nullptr) {
        builder.CreateBr(region_bb);
      }
      // Match C2 generate_array_guard_common(): this is an always-taken guard
      // edge, so there is no fast-control continuation.
      builder.ClearInsertionPoint();
      return always_branch;
    }
  }

  llvm::Value* limit = obj_array
      ? builder.getInt32(static_cast<int>(Klass::_lh_array_tag_type_value
                                          << Klass::_lh_array_tag_shift))
      : builder.getInt32(Klass::_lh_neutral_value);
  llvm::Value* bol =
      builder.CreateICmpSLT(layout_val, limit,
                            obj_array ? "arraycopy_is_obj_array"
                                      : "arraycopy_is_array");
  if (not_array)
    bol = builder.CreateNot(bol, "arraycopy_guard_not");
  return generate_fair_guard(bol, region_bb);
}

// ---- lower_arraycopy ----
bool JeandleIntrinsicLowering::lower_arraycopy() {
  assert(_target->is_static(), "System.arraycopy is static");
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Module& m = _interp->_module;
  llvm::Function* f = _interp->_llvm_func;

  // Get the arguments.
  llvm::Value* src = _interp->_jvm->peek_value(4).value();
  llvm::Value* src_offset = _interp->_jvm->peek_value(3).value();
  llvm::Value* dest = _interp->_jvm->peek_value(2).value();
  llvm::Value* dest_offset = _interp->_jvm->peek_value(1).value();
  llvm::Value* length = _interp->_jvm->peek_value(0).value();

  // The following tests must be performed
  // (1) src and dest are arrays.
  // (2) src and dest arrays must have elements of the same BasicType
  // (3) src and dest must not be null.
  // (4) src_offset must not be negative.
  // (5) dest_offset must not be negative.
  // (6) length must not be negative.
  // (7) src_offset + length must not exceed length of src.
  // (8) dest_offset + length must not exceed length of dest.
  // (9) each element of an oop array must be assignable

  // (3) src and dest must not be null.
  // always do this here because we need the JVM state for uncommon traps
  _interp->null_check(src);
  _interp->null_check(dest);

  // TODO: Add C2's !can_emit_guards follow-up:
  //   alloc = tightly_coupled_allocation(dest);
  // after the mandatory null checks. Jeandle does not currently rediscover a
  // tightly allocated destination when guard emission is disabled, so the later
  // validated arraycopy pseudo node cannot use that allocation coupling yet.

  bool validated = false;
  bool negative_length_guard_generated = false;

  // TODO: Add speculative_type_not_null + maybe_cast_profiled_obj-style guards
  // to turn hot Object-typed sources and destinations into precise array types
  // before creating the validated arraycopy pseudo node.

  // TODO: Match C2s full admission/type-narrowing logic here: honor
  // can_emit_guards/tightly_coupled_allocation and the !src->is_top() /
  // !dest->is_top() reachability checks before entering the validated
  // guard-admission path. Jeandle does not currently model C2 top/dead-control
  // values at this lowering point, so it relies on LLVM CFG cleanup after
  // emitting guards.

  if (!_interp->too_many_traps(const_cast<ciMethod*>(_interp->_method),
                               _interp->_bytecodes.cur_bci(),
                               Deoptimization::Reason_intrinsic)) {
    // validate arguments: enables transformation the ArrayCopyNode
    validated = true;

    llvm::BasicBlock* slow_bb = llvm::BasicBlock::Create(ctx, "arraycopy_slow", f);
    _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                           Deoptimization::Action_make_not_entrant, slow_bb);

    // (1) src and dest are arrays.
    generate_non_array_guard(_interp->load_object_klass(src), slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }
    generate_non_array_guard(_interp->load_object_klass(dest), slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }
    // (2) src and dest arrays must have elements of the same BasicType:
    // completed by ArrayCopySpecialization, like C2 defers this to Ideal/macro
    // expansion.

    // (4) src_offset must not be negative.
    generate_negative_guard(src_offset, slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }

    // (5) dest_offset must not be negative.
    generate_negative_guard(dest_offset, slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }

    // (7) src_offset + length must not exceed length of src.
    generate_limit_guard(
        src_offset, length,
        _interp->call_java_op("jeandle.arraylength", {src}), slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }

    // (8) dest_offset + length must not exceed length of dest.
    generate_limit_guard(
        dest_offset, length,
        _interp->call_java_op("jeandle.arraylength", {dest}), slow_bb);
    if (b.GetInsertBlock() == nullptr) {
      _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
      return true;
    }

    // (6) length must not be negative.
    // This is also checked during arraycopy expansion, but C2 checks it here as
    // well when Escape Analysis can eliminate the ArrayCopyNode.
    if (JeandleDoPEA) {
      generate_negative_guard(length, slow_bb);
      negative_length_guard_generated = true;
      if (b.GetInsertBlock() == nullptr) {
        _interp->_block->set(JeandleBasicBlock::always_uncommon_trap);
        return true;
      }
    }

    // (9) each element of an oop array must be assignable.
    // Mirrors C2 gen_subtype_check(src, dest_klass): if the source array Klass
    // is not a subtype of the destination array Klass, the failure edge joins
    // the shared intrinsic slow/deoptimization path. This intentionally
    // validates only the no-checkcast ArrayCopyNode shape; C2's checkcast stub
    // path belongs to non-validated/generic macro expansion.
    if (src != dest) {
      llvm::Value* dest_klass = _interp->load_object_klass(dest);
      llvm::Value* is_instance = _interp->call_java_op(
          "jeandle.instanceof", {dest_klass, src});
      llvm::Value* not_instance = b.CreateICmpEQ(
          is_instance, b.getInt32(0), "arraycopy_not_instance");
      generate_guard(not_instance, slow_bb, PROB_MIN);
    }
    // TODO: JavaType can sharpen values from dominating jeandle.instanceof guards,
    // but this guard tests a dynamically loaded dest_klass. The current analysis
    //  cannot propagate the abstract Klass type of that value on the successful path,
    // so it cannot yet reproduce C2's CheckCastPPNode type narrowing.
  }

  // C2 checks stopped() here before creating ArrayCopyNode::make(). Jeandle has
  // no top control node; a null insertion block is the corresponding stopped
  // state at this lowering point.
  if (b.GetInsertBlock() == nullptr) {
    return true;
  }

  _interp->_block->set_tail_llvm_block(b.GetInsertBlock());
  _interp->_jvm->ipop(); // length
  _interp->_jvm->ipop(); // destPos
  _interp->_jvm->apop(); // dest
  _interp->_jvm->ipop(); // srcPos
  _interp->_jvm->apop(); // src

  // The pseudo call is the Jeandle equivalent of C2 ArrayCopyNode::make(). It is
  // created after guard admission, and the validated attribute corresponds
  // to C2 ac->set_arraycopy(validated).
  llvm::Function* arraycopy_callee = m.getFunction("jeandle.arraycopy");
  assert(arraycopy_callee != nullptr,
         "jeandle.arraycopy must be declared in template.ll");
  static constexpr CallSiteAttributeMetadata arraycopy_attrs = {
      CTRL_NEEDS_EXCEPTION_EDGE,
      MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};
  llvm::CallBase* arraycopy_call = emit_callsite(
      arraycopy_callee, llvm::CallingConv::Hotspot_JIT,
      {src, src_offset, dest, dest_offset, length,
       _interp->load_object_klass(src), _interp->load_object_klass(dest),
       _interp->call_java_op("jeandle.arraylength", {src}),
       _interp->call_java_op("jeandle.arraylength", {dest})},
      arraycopy_attrs);
  arraycopy_call->addFnAttr(llvm::Attribute::get(
      ctx, llvm::jeandle::Attribute::ArrayCopyKind,
      llvm::jeandle::Attribute::ArrayCopyKindArrayCopy));
  if (validated) {
    arraycopy_call->addFnAttr(llvm::Attribute::get(
        ctx, llvm::jeandle::Attribute::ValidatedArrayCopy));
  }
  if (negative_length_guard_generated) {
    arraycopy_call->addFnAttr(llvm::Attribute::get(
        ctx, llvm::jeandle::Attribute::ArrayCopyNegativeLengthGuard));
  }
  return true;
}

// StringUTF16 trusted single-code-unit access. Its byte[] backing stores
// UTF-16 code units in native-endian order, matching the target data layout.
// Keep the element access as i16 so LLVM can fold the two-byte element scale
// into the target load/store addressing mode (for example, AArch64's
// ldrh/strh [base, index, sxtw #1]).
bool JeandleIntrinsicLowering::lower_string_char_access(bool is_store) {
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Type* i8 = b.getInt8Ty();
  llvm::Type* i16 = b.getInt16Ty();

  llvm::Value* array = _interp->_jvm->peek_value(is_store ? 2 : 1).value();
  llvm::Value* index = _interp->_jvm->peek_value(is_store ? 1 : 0).value();

  assert(arrayOopDesc::base_offset_in_bytes(T_CHAR) == arrayOopDesc::base_offset_in_bytes(T_BYTE),
         "byte[] and char[] bases must agree");
  assert(type2aelembytes(T_CHAR) == type2aelembytes(T_BYTE) * 2,
         "char[] scale must be twice byte[] scale");

  _interp->null_check(array);

  llvm::Value* data = b.CreateInBoundsGEP(
      i8, array, b.getInt32(arrayOopDesc::base_offset_in_bytes(T_BYTE)),
      is_store ? "string_putchar_data" : "string_getchar_data");
  llvm::Value* addr = b.CreateInBoundsGEP(
      i16, data, index,
      is_store ? "string_putchar_addr" : "string_getchar_addr");

  if (is_store) {
    llvm::Value* ch = _interp->_jvm->peek_value(0).value();
    llvm::Value* ch16 = b.CreateTrunc(ch, i16, "string_putchar_value");
    llvm::StoreInst* store = b.CreateStore(ch16, addr);
    store->setAlignment(llvm::Align(2));
    store->setAtomic(llvm::AtomicOrdering::Unordered);

    _interp->_jvm->ipop(); // c
    _interp->_jvm->ipop(); // index
    _interp->_jvm->apop(); // value
  } else {
    llvm::LoadInst* loaded = b.CreateLoad(i16, addr, "string_getchar_result");
    loaded->setAlignment(llvm::Align(2));
    loaded->setAtomic(llvm::AtomicOrdering::Unordered);
    llvm::Value* result = b.CreateZExt(
        loaded, b.getInt32Ty(), "string_getchar_result_i32");

    _interp->_jvm->ipop(); // index
    _interp->_jvm->apop(); // value
    _interp->_jvm->ipush(result);
  }
  return true;
}

bool JeandleIntrinsicLowering::lower_unsafe_compare_and_set(
    BasicType type, UnsafeAccessKind access_kind, bool weak) {
  const UnsafePrimitiveTypeInfo type_info = unsafe_primitive_type_info(type);
  const unsigned value_bits = type_info.value_bits;
  const llvm::MaybeAlign alignment = type_info.alignment;
  const bool is_long = value_bits == 64;


  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Function* function = _interp->_llvm_func;

  // Logical operand stack, top to bottom:
  //   update, expected, long offset, base object, Unsafe receiver.
  // There are no guards or deopt paths below, so consume the values directly.
  // lpop handles the category-2 representation of long update and expected.
  llvm::Value* update = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* expected = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* offset = _interp->_jvm->lpop();
  llvm::Value* base = _interp->_jvm->apop();
  _interp->_jvm->apop();  // Unsafe receiver

  std::string block_prefix = std::string("unsafe_cas_") + type_info.type_name;
  // Byte and short arguments use the JVM int computational type. Preserve
  // Java fallback return semantics for legal classfile calls with
  // non-canonical high bits without suppressing the low-width CAS update.
  llvm::Value* is_canonical_expected = nullptr;
  if (value_bits < 32) {
    llvm::Type* narrow_type = llvm::IntegerType::get(ctx, value_bits);
    llvm::Value* narrow_expected = b.CreateTrunc(
        expected, narrow_type, block_prefix + "_expected");
    update = b.CreateTrunc(update, narrow_type,
                           block_prefix + "_update");

    llvm::Value* canonical_expected = b.CreateSExt(
        narrow_expected, b.getInt32Ty(), block_prefix + "_canonical_expected");
    is_canonical_expected = b.CreateICmpEQ(
        expected, canonical_expected, block_prefix + "_is_canonical_expected");
    expected = narrow_expected;
  }

  // All shared calculations must precede this terminator.
  llvm::BasicBlock* on_heap = llvm::BasicBlock::Create(
      ctx, block_prefix + "_on_heap", function);
  llvm::BasicBlock* native_address = llvm::BasicBlock::Create(
      ctx, block_prefix + "_native_address", function);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(
      ctx, block_prefix + "_done", function);
  b.CreateCondBr(b.CreateIsNull(base), native_address, on_heap);
  const llvm::AtomicOrdering ordering = unsafe_atomic_ordering(access_kind);
  // LLVM forbids release/acq_rel failure ordering. A release-only weak CAS
  // has no acquire semantics on failure, so use monotonic there.
  const llvm::AtomicOrdering failure_order =
      access_kind == UnsafeAccessKind::Release ? llvm::AtomicOrdering::Monotonic
                                               : ordering;
  auto emit_cas = [&](llvm::Value *address, const char *path) {
    llvm::AtomicCmpXchgInst *cas =
        b.CreateAtomicCmpXchg(address, expected, update, alignment, ordering,
                              failure_order, llvm::SyncScope::System);
    // LLVM rejects release/acq_rel failure order. A weak release CAS has a
    // monotonic failure path; all other modes retain their matching failure
    // ordering. This is the C2 access-kind contract, not a retry loop.
    cas->setWeak(weak);
    return b.CreateExtractValue(cas, 1, block_prefix + "_" + path + "_success");
  };

  b.SetInsertPoint(on_heap);
  _interp->_block->set_tail_llvm_block(on_heap);
  llvm::Value* heap_address = b.CreatePtrAdd(
      base, offset, block_prefix + "_heap_addr");
  llvm::Value* heap_success = emit_cas(heap_address, "heap");
  b.CreateBr(done);

  b.SetInsertPoint(native_address);
  _interp->_block->set_tail_llvm_block(native_address);
  llvm::PointerType* raw_ptr_type = llvm::PointerType::get(
      ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* raw_address = b.CreateIntToPtr(
      offset, raw_ptr_type, block_prefix + "_raw_addr");
  llvm::Value* raw_success = emit_cas(raw_address, "raw");
  b.CreateBr(done);

  b.SetInsertPoint(done);
  _interp->_block->set_tail_llvm_block(done);
  llvm::PHINode* success_phi = b.CreatePHI(b.getInt1Ty(), 2, block_prefix + "_success");
  success_phi->addIncoming(heap_success, on_heap);
  success_phi->addIncoming(raw_success, native_address);
  llvm::Value* success = success_phi;
  if (is_canonical_expected != nullptr) {
    success = b.CreateAnd(success, is_canonical_expected,
                          block_prefix + "_declared_success");
  }
  _interp->_jvm->ipush(b.CreateZExt(success, b.getInt32Ty()));
  return true;
}

// ---- lower_unsafe_compare_and_exchange ----
// Primitive CAX shares the heap/raw address and ordering contract with CAS,
// but returns the value observed before the exchange rather than a success bit.
bool JeandleIntrinsicLowering::lower_unsafe_compare_and_exchange(
    BasicType type, UnsafeAccessKind access_kind) {
  assert(type == T_BYTE || type == T_SHORT || type == T_INT || type == T_LONG,
         "unexpected primitive compare-and-exchange type");
  assert(access_kind == UnsafeAccessKind::Volatile ||
             access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Release,
         "unexpected primitive compare-and-exchange ordering");

  const UnsafePrimitiveTypeInfo type_info = unsafe_primitive_type_info(type);
  const unsigned value_bits = type_info.value_bits;
  const bool is_long = value_bits == 64;

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;

  // Logical operand stack, top to bottom:
  //   update, expected, long offset, base object, Unsafe receiver.
  // lower_unsafe_atomic has already guarded a possible raw null+zero
  // address while these values were still live for reexecution.
  llvm::Value *update = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value *expected =
      is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value *offset = _interp->_jvm->lpop();
  llvm::Value *base = _interp->_jvm->apop();
  _interp->_jvm->apop(); // Unsafe receiver

  const std::string prefix = std::string("unsafe_cax_") + type_info.type_name;
  if (value_bits < 32) {
    // byte/short descriptors still use the JVM int computational type.
    // The native Unsafe operation consumes only the declared low bits.
    llvm::Type *narrow_type = llvm::IntegerType::get(ctx, value_bits);
    expected = b.CreateTrunc(expected, narrow_type, prefix + "_expected");
    update = b.CreateTrunc(update, narrow_type, prefix + "_update");
  }

  llvm::BasicBlock *on_heap =
      llvm::BasicBlock::Create(ctx, prefix + "_on_heap", function);
  llvm::BasicBlock *native_address =
      llvm::BasicBlock::Create(ctx, prefix + "_native_address", function);
  llvm::BasicBlock *done =
      llvm::BasicBlock::Create(ctx, prefix + "_done", function);
  b.CreateCondBr(b.CreateIsNull(base), native_address, on_heap);

  const llvm::AtomicOrdering success_order =
      unsafe_atomic_ordering(access_kind);
  // LLVM forbids release/acq_rel failure ordering. A release CAX publishes
  // only on a successful update and observes the failed value monotonically.
  const llvm::AtomicOrdering failure_order =
      access_kind == UnsafeAccessKind::Release ? llvm::AtomicOrdering::Monotonic
                                               : success_order;
  auto emit_cax = [&](llvm::Value *address, const char *path) {
    llvm::AtomicCmpXchgInst *cax = b.CreateAtomicCmpXchg(
        address, expected, update, type_info.alignment, success_order,
        failure_order, llvm::SyncScope::System);
    cax->setWeak(false);
    return b.CreateExtractValue(cax, 0, prefix + "_" + path + "_old");
  };

  b.SetInsertPoint(on_heap);
  _interp->_block->set_tail_llvm_block(on_heap);
  llvm::Value *heap_address =
      b.CreatePtrAdd(base, offset, prefix + "_heap_addr");
  llvm::Value *heap_old = emit_cax(heap_address, "heap");
  b.CreateBr(done);

  b.SetInsertPoint(native_address);
  _interp->_block->set_tail_llvm_block(native_address);
  llvm::PointerType *raw_ptr_type =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value *raw_address =
      b.CreateIntToPtr(offset, raw_ptr_type, prefix + "_raw_addr");
  llvm::Value *raw_old = emit_cax(raw_address, "raw");
  b.CreateBr(done);

  b.SetInsertPoint(done);
  _interp->_block->set_tail_llvm_block(done);
  llvm::Type *memory_type = unsafe_primitive_memory_llvm_type(type, b);
  llvm::PHINode *old_value = b.CreatePHI(memory_type, 2, prefix + "_old");
  old_value->addIncoming(heap_old, on_heap);
  old_value->addIncoming(raw_old, native_address);
  if (is_long) {
    _interp->_jvm->lpush(old_value);
  } else if (value_bits < 32) {
    // JVM ireturn narrows byte/short results and sign-extends them back to the
    // caller's int computational type. Preserve that contract inlined.
    _interp->_jvm->ipush(
        b.CreateSExt(old_value, b.getInt32Ty(), prefix + "_result"));
  } else {
    _interp->_jvm->ipush(old_value);
  }
  return true;
}

// Unsafe reference CAS/CAX differs from primitive atomics in two material
// ways: a null base is not an inlineable raw oop address, and the heap write
// must run the collector's pre/post barriers.  Keep the Java operands live
// until the base guard and JavaOp callsite have captured reexecution/GC state.
bool JeandleIntrinsicLowering::lower_unsafe_reference_get_and_set() {
  assert(UseG1GC || UseSerialGC,
         "reference atomics require the existing G1/Serial barriers");

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;

  // Logical operands, top to bottom: update, long offset, base, Unsafe
  // receiver. Keep them live until all guards and the GC-state callsite have
  // captured a reexecutable Java state.
  llvm::Value *update = _interp->_jvm->peek_value(0).value();
  llvm::Value *offset = _interp->_jvm->peek_value(1).value();
  llvm::Value *base = _interp->_jvm->peek_value(2).value();
  llvm::Value *receiver = _interp->_jvm->peek_value(3).value();

  // This collector-aware JavaOp is heap-only. Preserve the original Unsafe
  // invocation for a statically known raw access, and deopt before consuming
  // operands when a dynamic base turns out to be null.
  if (is_provably_null_oop(base)) {
    return false;
  }
  if (_interp->too_many_traps(_interp->_method,
                              _interp->_bytecodes.cur_bci(),
                              Deoptimization::Reason_intrinsic)) {
    return false;
  }
  _interp->null_check(receiver);
  llvm::BasicBlock *heap = llvm::BasicBlock::Create(
      ctx, "unsafe_reference_get_and_set_heap", function);
  llvm::BasicBlock *raw = llvm::BasicBlock::Create(
      ctx, "unsafe_reference_get_and_set_raw", function);
  b.CreateCondBr(b.CreateIsNull(base), raw, heap);
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_make_not_entrant, raw);
  b.SetInsertPoint(heap);
  _interp->_block->set_tail_llvm_block(heap);

  llvm::Function *java_op =
      _interp->_module.getFunction("jeandle.unsafe_get_and_set_reference");
  assert(java_op != nullptr,
         "Unsafe getAndSetReference JavaOp must exist");
  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};
  llvm::CallBase *result = emit_callsite(
      java_op, llvm::CallingConv::Hotspot_JIT, {base, offset, update}, attrs);

  _interp->_jvm->apop(); // update
  _interp->_jvm->lpop(); // offset
  _interp->_jvm->apop(); // base
  _interp->_jvm->apop(); // Unsafe receiver
  _interp->_jvm->apush(result);
  return true;
}

bool JeandleIntrinsicLowering::lower_unsafe_reference_load(
    UnsafeAccessKind access_kind) {
  assert(access_kind == UnsafeAccessKind::Relaxed ||
             access_kind == UnsafeAccessKind::Opaque ||
             access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Volatile,
         "unexpected Unsafe reference load ordering");
  assert(UseG1GC || UseSerialGC,
         "reference loads require the existing G1/Serial barriers");

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;

  // Logical operands, top to bottom: long offset, base, Unsafe receiver.
  // `peek_value` accounts for the category-2 offset placeholder.
  llvm::Value *offset = _interp->_jvm->peek_value(0).value();
  llvm::Value *base = _interp->_jvm->peek_value(1).value();
  llvm::Value *receiver = _interp->_jvm->peek_value(2).value();

  // Decline a compile-time raw oop address without changing control or stack.
  if (is_provably_null_oop(base)) {
    return false;
  }
  if (_interp->too_many_traps(_interp->_method,
                              _interp->_bytecodes.cur_bci(),
                              Deoptimization::Reason_intrinsic)) {
    return false;
  }
  // Preserve invokevirtual semantics even for adversarial bytecode with a
  // null Unsafe receiver.  Keep every operand live until this deopt-capable
  // check and the later raw-base guard have captured the pre-invoke state.
  _interp->null_check(receiver);

  llvm::BasicBlock *heap =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_load_heap", function);
  llvm::BasicBlock *raw =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_load_raw", function);
  b.CreateCondBr(b.CreateIsNull(base), raw, heap);
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_make_not_entrant, raw);
  b.SetInsertPoint(heap);
  _interp->_block->set_tail_llvm_block(heap);

  const char *java_op_name = nullptr;
  switch (access_kind) {
  case UnsafeAccessKind::Relaxed:
    java_op_name = "jeandle.unsafe_get_reference";
    break;
  case UnsafeAccessKind::Opaque:
    java_op_name = "jeandle.unsafe_get_reference_opaque";
    break;
  case UnsafeAccessKind::Acquire:
    java_op_name = "jeandle.unsafe_get_reference_acquire";
    break;
  case UnsafeAccessKind::Volatile:
    java_op_name = "jeandle.unsafe_get_reference_volatile";
    break;
  default:
    ShouldNotReachHere();
  }
  llvm::Function *java_op = _interp->_module.getFunction(java_op_name);
  assert(java_op != nullptr, "Unsafe reference load JavaOp must exist");
  static constexpr CallSiteAttributeMetadata serial_attrs = {
      CTRL_NONE, MEM_READ};
  static constexpr CallSiteAttributeMetadata g1_attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE};
  const CallSiteAttributeMetadata &attrs = UseG1GC ? g1_attrs : serial_attrs;

  // The JavaOp and its G1 SATB slow path are GC leaves.  G1 still needs full
  // memory effects because the JavaOp may update the thread-local SATB queue.
  llvm::CallBase *result = emit_callsite(
      java_op, llvm::CallingConv::Hotspot_JIT, {base, offset}, attrs);
  _interp->_jvm->lpop(); // offset
  _interp->_jvm->apop(); // base
  _interp->_jvm->apop(); // Unsafe receiver
  _interp->_jvm->apush(result);
  return true;
}

bool JeandleIntrinsicLowering::lower_unsafe_reference_store(
    UnsafeAccessKind access_kind) {
  assert(access_kind == UnsafeAccessKind::Relaxed ||
             access_kind == UnsafeAccessKind::Opaque ||
             access_kind == UnsafeAccessKind::Release ||
             access_kind == UnsafeAccessKind::Volatile,
         "unexpected Unsafe reference store ordering");
  assert(UseG1GC || UseSerialGC,
         "reference stores require the existing G1/Serial barriers");

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;

  // Logical operands, top to bottom: value, long offset, base, receiver.
  llvm::Value *value = _interp->_jvm->peek_value(0).value();
  llvm::Value *offset = _interp->_jvm->peek_value(1).value();
  llvm::Value *base = _interp->_jvm->peek_value(2).value();
  llvm::Value *receiver = _interp->_jvm->peek_value(3).value();
  if (is_provably_null_oop(base)) {
    return false;
  }
  if (_interp->too_many_traps(_interp->_method,
                              _interp->_bytecodes.cur_bci(),
                              Deoptimization::Reason_intrinsic)) {
    return false;
  }
  _interp->null_check(receiver);

  llvm::BasicBlock *heap =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_store_heap", function);
  llvm::BasicBlock *raw =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_store_raw", function);
  b.CreateCondBr(b.CreateIsNull(base), raw, heap);
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_make_not_entrant, raw);
  b.SetInsertPoint(heap);
  _interp->_block->set_tail_llvm_block(heap);

  const char *java_op_name = nullptr;
  switch (access_kind) {
  case UnsafeAccessKind::Relaxed:
    java_op_name = "jeandle.unsafe_put_reference";
    break;
  case UnsafeAccessKind::Opaque:
    java_op_name = "jeandle.unsafe_put_reference_opaque";
    break;
  case UnsafeAccessKind::Release:
    java_op_name = "jeandle.unsafe_put_reference_release";
    break;
  case UnsafeAccessKind::Volatile:
    java_op_name = "jeandle.unsafe_put_reference_volatile";
    break;
  default:
    ShouldNotReachHere();
  }
  llvm::Function *java_op = _interp->_module.getFunction(java_op_name);
  assert(java_op != nullptr, "Unsafe reference store JavaOp must exist");
  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};
  emit_callsite(java_op, llvm::CallingConv::Hotspot_JIT, {base, offset, value},
                attrs);
  _interp->_jvm->apop(); // value
  _interp->_jvm->lpop(); // offset
  _interp->_jvm->apop(); // base
  _interp->_jvm->apop(); // receiver
  return true;
}

bool JeandleIntrinsicLowering::lower_unsafe_reference_compare_and_exchange(
    UnsafeAccessKind access_kind, bool returns_old, bool weak) {
  assert(access_kind == UnsafeAccessKind::Relaxed ||
             access_kind == UnsafeAccessKind::Volatile ||
             access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Release,
         "unexpected Unsafe reference compare-and-exchange ordering");
  assert(UseG1GC || UseSerialGC,
         "reference atomics require the existing G1/Serial barriers");

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;

  // Logical operands, top to bottom: update, expected, long offset, base,
  // Unsafe receiver.  `peek_value` accounts for the category-2 offset.
  llvm::Value *update = _interp->_jvm->peek_value(0).value();
  llvm::Value *expected = _interp->_jvm->peek_value(1).value();
  llvm::Value *offset = _interp->_jvm->peek_value(2).value();
  llvm::Value *base = _interp->_jvm->peek_value(3).value();
  llvm::Value *receiver = _interp->_jvm->peek_value(4).value();

  // Unlike primitive Unsafe atomics, raw `base == null` reference access is
  // not defined by this JavaOp: it has no collector barrier/address-space
  // contract.  A compile-time null retains the original invocation; a dynamic
  // null reexecutes it before this lowering consumes any operand.
  if (is_provably_null_oop(base)) {
    return false;
  }
  if (_interp->too_many_traps(_interp->_method,
                              _interp->_bytecodes.cur_bci(),
                              Deoptimization::Reason_intrinsic)) {
    return false;
  }
  _interp->null_check(receiver);
  llvm::BasicBlock *heap =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_cax_heap", function);
  llvm::BasicBlock *raw =
      llvm::BasicBlock::Create(ctx, "unsafe_reference_cax_raw", function);
  b.CreateCondBr(b.CreateIsNull(base), raw, heap);
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_make_not_entrant, raw);
  b.SetInsertPoint(heap);
  _interp->_block->set_tail_llvm_block(heap);

  const char *java_op_name = nullptr;
  if (!returns_old && weak) {
    if (access_kind == UnsafeAccessKind::Relaxed) {
      java_op_name = "jeandle.unsafe_weak_compare_and_set_reference_plain";
    } else if (access_kind == UnsafeAccessKind::Acquire) {
      java_op_name = "jeandle.unsafe_weak_compare_and_set_reference_acquire";
    } else if (access_kind == UnsafeAccessKind::Release) {
      java_op_name = "jeandle.unsafe_weak_compare_and_set_reference_release";
    } else {
      java_op_name = "jeandle.unsafe_weak_compare_and_set_reference";
    }
  } else if (!returns_old) {
    java_op_name = "jeandle.unsafe_compare_and_set_reference";
  } else if (access_kind == UnsafeAccessKind::Volatile) {
    java_op_name = "jeandle.unsafe_compare_and_exchange_reference";
  } else if (access_kind == UnsafeAccessKind::Acquire) {
    java_op_name = "jeandle.unsafe_compare_and_exchange_reference_acquire";
  } else {
    java_op_name = "jeandle.unsafe_compare_and_exchange_reference_release";
  }
  llvm::Function *java_op = _interp->_module.getFunction(java_op_name);
  assert(java_op != nullptr, "Unsafe reference atomic JavaOp must exist");

  // The JavaOp contains barriers and oop conversion.  The outer call is not a
  // GC leaf: capture the original invoke expression stack before popping it.
  static constexpr CallSiteAttributeMetadata attrs = {
      CTRL_NONE, MEM_READ | MEM_WRITE | MEM_NEEDS_GC_STATE};
  llvm::CallBase *result =
      emit_callsite(java_op, llvm::CallingConv::Hotspot_JIT,
                    {base, offset, expected, update}, attrs);

  _interp->_jvm->apop(); // update
  _interp->_jvm->apop(); // expected
  _interp->_jvm->lpop(); // offset
  _interp->_jvm->apop(); // base
  _interp->_jvm->apop(); // Unsafe receiver
  if (returns_old) {
    _interp->_jvm->apush(result);
  } else {
    _interp->_jvm->ipush(result);
  }
  return true;
}

// DigestBase.implCompressMultiBlock0 is a private root candidate. It
// dispatches only to the existing AArch64 multi-block leaf stubs after
// concrete-digest, collector, and byte-array bounds guards have preserved a
// reexecutable Java state.
bool JeandleIntrinsicLowering::guard_unsafe_primitive_access(
    BasicType type, int offset_depth, int base_depth,
    bool requires_atomic_alignment) {
  llvm::Value* offset = _interp->_jvm->peek_value(offset_depth).value();
  llvm::Value* base = _interp->_jvm->peek_value(base_depth).value();

  // Decline without changing IR so the normal Unsafe call remains intact.
  if ((requires_atomic_alignment &&
       unsafe_has_known_misaligned_atomic_offset(type, offset)) ||
      unsafe_has_known_zero_raw_address(base, offset)) {
    return false;
  }

  const bool needs_raw_zero_guard = unsafe_raw_access_may_be_zero(base, offset);
  const uint64_t alignment_mask =
      requires_atomic_alignment ? unsafe_atomic_alignment_mask(type) : 0;
  const bool needs_alignment_guard =
      alignment_mask != 0 && !llvm::isa<llvm::ConstantInt>(offset);
  if ((needs_raw_zero_guard || needs_alignment_guard) &&
      _interp->too_many_traps(_interp->_method,
                              _interp->_bytecodes.cur_bci(),
                              Deoptimization::Reason_intrinsic)) {
    return false;
  }

  // Preserve invokevirtual receiver semantics before any dynamic guard.
  _interp->null_check(_interp->_jvm->peek_value(base_depth + 1).value());

  llvm::IRBuilder<>& builder = _interp->_ir_builder;
  llvm::LLVMContext& context = *_interp->_context;
  if (needs_raw_zero_guard) {
    llvm::BasicBlock* raw_zero = llvm::BasicBlock::Create(
        context, "unsafe_raw_zero_address", _interp->_llvm_func);
    llvm::BasicBlock* pass = llvm::BasicBlock::Create(
        context, "unsafe_raw_address_pass", _interp->_llvm_func);
    llvm::Value* is_raw = builder.CreateIsNull(base, "unsafe_is_raw");
    llvm::Value* is_zero = builder.CreateICmpEQ(offset, builder.getInt64(0),
                                                "unsafe_raw_offset_is_zero");
    builder.CreateCondBr(builder.CreateAnd(is_raw, is_zero), raw_zero, pass);
    _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                           Deoptimization::Action_make_not_entrant, raw_zero);
    builder.SetInsertPoint(pass);
    _interp->_block->set_tail_llvm_block(pass);
  }

  if (needs_alignment_guard) {
    llvm::BasicBlock* misaligned = llvm::BasicBlock::Create(
        context, "unsafe_atomic_misaligned", _interp->_llvm_func);
    llvm::BasicBlock* pass = llvm::BasicBlock::Create(
        context, "unsafe_atomic_alignment_pass", _interp->_llvm_func);
    llvm::Value* low_bits =
        builder.CreateAnd(offset, builder.getInt64(alignment_mask),
                          "unsafe_atomic_alignment_bits");
    llvm::Value* is_misaligned = builder.CreateICmpNE(
        low_bits, builder.getInt64(0), "unsafe_atomic_is_misaligned");
    builder.CreateCondBr(is_misaligned, misaligned, pass);
    _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                           Deoptimization::Action_make_not_entrant, misaligned);
    builder.SetInsertPoint(pass);
    _interp->_block->set_tail_llvm_block(pass);
  }
  return true;
}

// ---- lower_unsafe_atomic ----
// Atomic operation shape and memory ordering are independent dimensions.
// Reference operations retain their collector-aware implementations; primitive
// operations share the raw-address and alignment admission above.
bool JeandleIntrinsicLowering::lower_unsafe_atomic(
    BasicType type, UnsafeAtomicKind kind, UnsafeAccessKind access_kind) {
  JeandleCompilation::current()->set_has_unsafe_access(true);
  if (!unsafe_atomic_order_is_valid(kind, access_kind)) {
    ShouldNotReachHere();
    return false;
  }

  if (type == T_OBJECT) {
    switch (kind) {
    case UnsafeAtomicKind::CompareAndSet:
      return lower_unsafe_reference_compare_and_exchange(
          access_kind, /*returns_old=*/false, /*weak=*/false);
    case UnsafeAtomicKind::WeakCompareAndSet:
      return lower_unsafe_reference_compare_and_exchange(
          access_kind, /*returns_old=*/false, /*weak=*/true);
    case UnsafeAtomicKind::CompareAndExchange:
      return lower_unsafe_reference_compare_and_exchange(access_kind);
    case UnsafeAtomicKind::GetAdd:
      ShouldNotReachHere();
      return false;
    case UnsafeAtomicKind::GetSet:
      return lower_unsafe_reference_get_and_set();
    }
  }

  int offset_depth;
  int base_depth;
  switch (kind) {
  case UnsafeAtomicKind::CompareAndSet:
  case UnsafeAtomicKind::WeakCompareAndSet:
  case UnsafeAtomicKind::CompareAndExchange:
    offset_depth = 2;
    base_depth = 3;
    break;
  case UnsafeAtomicKind::GetAdd:
  case UnsafeAtomicKind::GetSet:
    offset_depth = 1;
    base_depth = 2;
    break;
  }

  if (!guard_unsafe_primitive_access(type, offset_depth, base_depth,
                                     /*requires_atomic_alignment=*/true)) {
    return false;
  }

  switch (kind) {
  case UnsafeAtomicKind::CompareAndSet:
    return lower_unsafe_compare_and_set(type, access_kind);
  case UnsafeAtomicKind::WeakCompareAndSet:
    return lower_unsafe_compare_and_set(type, access_kind, /*weak=*/true);
  case UnsafeAtomicKind::CompareAndExchange:
    return lower_unsafe_compare_and_exchange(type, access_kind);
  case UnsafeAtomicKind::GetAdd:
    return lower_unsafe_atomic_rmw(type, llvm::AtomicRMWInst::Add, access_kind);
  case UnsafeAtomicKind::GetSet:
    return lower_unsafe_atomic_rmw(type, llvm::AtomicRMWInst::Xchg,
                                   access_kind);
  }
  ShouldNotReachHere();
  return false;
}

// ---- lower_unsafe_plain_primitive_access ----
// Plain Unsafe accesses are regular LLVM loads/stores. They deliberately do
// not use LLVM atomics: UnsafeAccessKind describes the Java contract, while
// this helper implements its relaxed load/store representation.
bool JeandleIntrinsicLowering::lower_unsafe_plain_primitive_access(
    BasicType type, bool is_store) {
  const UnsafePrimitiveTypeInfo type_info = unsafe_primitive_type_info(type);


  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Function* function = _interp->_llvm_func;
  llvm::Value* value = nullptr;

  // Logical operand stack, top to bottom:
  // getter: offset, base object, Unsafe receiver
  // setter: value, offset, base object, Unsafe receiver
  if (is_store) {
    switch (type) {
      case T_BOOLEAN:
      case T_BYTE:
      case T_SHORT:
      case T_CHAR:
      case T_INT:
        value = _interp->_jvm->ipop();
        break;
      case T_LONG:
        value = _interp->_jvm->lpop();
        break;
      case T_FLOAT:
        value = _interp->_jvm->fpop();
        break;
      case T_DOUBLE:
        value = _interp->_jvm->dpop();
        break;
      default:
        ShouldNotReachHere();
        return false;
    }
  }
  llvm::Value* offset = _interp->_jvm->lpop();
  llvm::Value* base = _interp->_jvm->apop();
  _interp->_jvm->apop();  // Unsafe receiver; checked by generic invoke lowering.

  const std::string prefix = std::string("unsafe_plain_") +
      (is_store ? "put_" : "get_") + type_info.type_name;
  llvm::Type* value_type = unsafe_primitive_memory_llvm_type(type, b);
  if (is_store && value->getType() != value_type) {
    // Boolean/byte/short/char use the JVM int computational type.
    value = b.CreateTrunc(value, value_type, prefix + "_value");
  }
  if (is_store && type == T_BOOLEAN) {
    // A Z descriptor consumes an int computational value. Match Unsafe's
    // native normalization rather than storing an arbitrary truncated byte.
    value = b.CreateAnd(value, b.getInt8(1), prefix + "_canonical");
  }

  llvm::BasicBlock* heap_block = llvm::BasicBlock::Create(ctx, prefix + "_heap", function);
  llvm::BasicBlock* raw_block = llvm::BasicBlock::Create(ctx, prefix + "_raw", function);
  llvm::BasicBlock* done_block = llvm::BasicBlock::Create(ctx, prefix + "_done", function);
  llvm::Value* is_raw = b.CreateIsNull(base, prefix + "_is_raw");
  b.CreateCondBr(is_raw, raw_block, heap_block);

  b.SetInsertPoint(heap_block);
  llvm::Value* heap_address = b.CreatePtrAdd(base, offset, prefix + "_heap_address");
  llvm::Value* heap_value = nullptr;
  if (is_store) {
    // Unsafe offsets can be arbitrary, so do not give LLVM a stronger
    // alignment guarantee than the API provides.
    b.CreateAlignedStore(value, heap_address, llvm::Align(1));
  } else {
    heap_value = b.CreateAlignedLoad(value_type, heap_address, llvm::Align(1),
                                     prefix + "_heap_value");
  }
  b.CreateBr(done_block);

  b.SetInsertPoint(raw_block);
  llvm::Module* module = function->getParent();
  llvm::Function* current_thread_fn = module->getFunction("jeandle.current_thread");
  llvm::CallInst* current_thread = b.CreateCall(current_thread_fn);
  llvm::Value* unsafe_access_flag = b.CreateGEP(
      b.getInt8Ty(), current_thread,
      b.getInt32(in_bytes(JavaThread::doing_unsafe_access_offset())),
      prefix + "_unsafe_access_flag");
  b.CreateStore(b.getInt8(1), unsafe_access_flag)->setVolatile(true);
  llvm::PointerType* raw_pointer_type = llvm::PointerType::get(
      ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* raw_address = b.CreateIntToPtr(offset, raw_pointer_type,
                                               prefix + "_raw_address");
  llvm::Value* raw_value = nullptr;
  if (is_store) {
    b.CreateAlignedStore(value, raw_address, llvm::Align(1));
  } else {
    raw_value = b.CreateAlignedLoad(value_type, raw_address, llvm::Align(1),
                                    prefix + "_raw_value");
  }
  b.CreateStore(b.getInt8(0), unsafe_access_flag)->setVolatile(true);
  b.CreateBr(done_block);

  b.SetInsertPoint(done_block);
  _interp->_block->set_tail_llvm_block(done_block);
  if (is_store) {
    return true;
  }

  llvm::PHINode* result = b.CreatePHI(value_type, 2, prefix + "_result");
  result->addIncoming(heap_value, heap_block);
  result->addIncoming(raw_value, raw_block);

  // C2 can omit this normalization for statically typed canonical boolean
  // fields. Jeandle has no equivalent alias proof, so retain Java boolean
  // semantics for heap, raw, and dynamically addressed accesses.
  switch (type) {
    case T_BOOLEAN: {
      llvm::Value* normalized = b.CreateZExt(
          b.CreateICmpNE(result, llvm::ConstantInt::get(value_type, 0),
                         prefix + "_nonzero"),
          b.getInt32Ty(), prefix + "_value");
      _interp->_jvm->ipush(normalized);
      break;
    }
    case T_BYTE:
      _interp->_jvm->ipush(b.CreateSExt(result, b.getInt32Ty(), prefix + "_value"));
      break;
    case T_SHORT:
      _interp->_jvm->ipush(b.CreateSExt(result, b.getInt32Ty(), prefix + "_value"));
      break;
    case T_CHAR:
      _interp->_jvm->ipush(b.CreateZExt(result, b.getInt32Ty(), prefix + "_value"));
      break;
    case T_INT:
      _interp->_jvm->ipush(result);
      break;
    case T_LONG:
      _interp->_jvm->lpush(result);
      break;
    case T_FLOAT:
      _interp->_jvm->fpush(result);
      break;
    case T_DOUBLE:
      _interp->_jvm->dpush(result);
      break;
    default:
      ShouldNotReachHere();
      return false;
  }
  return true;
}

// ---- lower_unsafe_ordered_primitive_access ----
// Primitive opaque/acquire/release/volatile accesses are system-scope LLVM
// atomics. The access kind remains intact until it is mapped to LLVM ordering.
bool JeandleIntrinsicLowering::lower_unsafe_ordered_primitive_access(
    BasicType type, bool is_store, UnsafeAccessKind access_kind) {
  assert(access_kind == UnsafeAccessKind::Opaque ||
             access_kind == UnsafeAccessKind::Acquire ||
             access_kind == UnsafeAccessKind::Release ||
             access_kind == UnsafeAccessKind::Volatile,
         "unexpected ordered Unsafe primitive access");
  const UnsafePrimitiveTypeInfo type_info = unsafe_primitive_type_info(type);

  llvm::IRBuilder<> &b = _interp->_ir_builder;
  llvm::LLVMContext &ctx = *_interp->_context;
  llvm::Function *function = _interp->_llvm_func;
  llvm::Type *memory_type = llvm::IntegerType::get(ctx, type_info.value_bits);
  llvm::Value *value = nullptr;

  // Logical operand stack, top to bottom:
  // getter: offset, base object, Unsafe receiver
  // setter: value, offset, base object, Unsafe receiver
  if (is_store) {
      switch (type) {
      case T_BOOLEAN:
      case T_BYTE:
      case T_SHORT:
      case T_CHAR:
      case T_INT:
        value = _interp->_jvm->ipop();
        break;
      case T_LONG:
        value = _interp->_jvm->lpop();
        break;
      case T_FLOAT:
        value = _interp->_jvm->fpop();
        break;
      case T_DOUBLE:
        value = _interp->_jvm->dpop();
        break;
      default:
        ShouldNotReachHere();
        return false;
      }
  }
  llvm::Value *offset = _interp->_jvm->lpop();
  llvm::Value *base = _interp->_jvm->apop();
  _interp->_jvm->apop();

  const char *access_name =
      access_kind == UnsafeAccessKind::Opaque    ? "unsafe_opaque_"
      : access_kind == UnsafeAccessKind::Acquire ? "unsafe_acquire_"
      : access_kind == UnsafeAccessKind::Release ? "unsafe_release_"
                                                 : "unsafe_volatile_";
  const std::string prefix = std::string(access_name) +
                             (is_store ? "put_" : "get_") + type_info.type_name;
  if (is_store) {
      if (type == T_FLOAT || type == T_DOUBLE) {
        value = b.CreateBitCast(value, memory_type, prefix + "_bits");
      } else if (value->getType() != memory_type) {
        value = b.CreateTrunc(value, memory_type, prefix + "_value");
      }
      if (type == T_BOOLEAN) {
        value = b.CreateAnd(value, b.getInt8(1), prefix + "_canonical");
      }
  }

  llvm::BasicBlock *heap_block =
      llvm::BasicBlock::Create(ctx, prefix + "_heap", function);
  llvm::BasicBlock *raw_block =
      llvm::BasicBlock::Create(ctx, prefix + "_raw", function);
  llvm::BasicBlock *done_block =
      llvm::BasicBlock::Create(ctx, prefix + "_done", function);
  b.CreateCondBr(b.CreateIsNull(base, std::string(access_name) + "base_is_null"), raw_block,
                 heap_block);

  auto emit_load = [&](llvm::Value *address, const llvm::Twine &name) {
    llvm::LoadInst *load =
        b.CreateAlignedLoad(memory_type, address, type_info.alignment, name);
    load->setOrdering(unsafe_atomic_ordering(access_kind));
    load->setSyncScopeID(llvm::SyncScope::System);
    return load;
  };
  auto emit_store = [&](llvm::Value *address) {
    llvm::StoreInst *store =
        b.CreateAlignedStore(value, address, type_info.alignment);
    store->setOrdering(unsafe_atomic_ordering(access_kind));
    store->setSyncScopeID(llvm::SyncScope::System);
    return store;
  };

  b.SetInsertPoint(heap_block);
  _interp->_block->set_tail_llvm_block(heap_block);
  llvm::Value *heap_address =
      b.CreatePtrAdd(base, offset, std::string(access_name) + "heap_address");
  llvm::Value *heap_value =
      is_store ? nullptr : emit_load(heap_address, prefix + "_heap_value");
  if (is_store) {
      emit_store(heap_address);
  }
  b.CreateBr(done_block);

  b.SetInsertPoint(raw_block);
  _interp->_block->set_tail_llvm_block(raw_block);
  llvm::Function *current_thread_fn = function->getParent()->getFunction("jeandle.current_thread");
  llvm::CallInst *current_thread = b.CreateCall(current_thread_fn);
  llvm::Value *unsafe_access_flag = b.CreateGEP(
      b.getInt8Ty(), current_thread,
      b.getInt32(in_bytes(JavaThread::doing_unsafe_access_offset())),
      std::string(access_name) + "unsafe_access_flag");
  b.CreateStore(b.getInt8(1), unsafe_access_flag)->setVolatile(true);
  llvm::PointerType *raw_pointer_type =
      llvm::PointerType::get(ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value *raw_address =
      b.CreateIntToPtr(offset, raw_pointer_type, std::string(access_name) + "raw_address");
  llvm::Value *raw_value =
      is_store ? nullptr : emit_load(raw_address, prefix + "_raw_value");
  if (is_store) {
      emit_store(raw_address);
  }
  b.CreateStore(b.getInt8(0), unsafe_access_flag)->setVolatile(true);
  b.CreateBr(done_block);

  b.SetInsertPoint(done_block);
  _interp->_block->set_tail_llvm_block(done_block);
  if (is_store) {
      return true;
  }

  llvm::PHINode *loaded = b.CreatePHI(memory_type, 2, prefix + "_value");
  loaded->addIncoming(heap_value, heap_block);
  loaded->addIncoming(raw_value, raw_block);
  switch (type) {
  case T_BOOLEAN:
      _interp->_jvm->ipush(b.CreateZExt(
          b.CreateICmpNE(loaded, llvm::ConstantInt::get(memory_type, 0),
                         prefix + "_nonzero"),
          b.getInt32Ty(), prefix + "_result"));
      break;
  case T_BYTE:
  case T_SHORT:
      _interp->_jvm->ipush(
          b.CreateSExt(loaded, b.getInt32Ty(), prefix + "_result"));
      break;
  case T_CHAR:
      _interp->_jvm->ipush(
          b.CreateZExt(loaded, b.getInt32Ty(), prefix + "_result"));
      break;
  case T_INT:
      _interp->_jvm->ipush(loaded);
      break;
  case T_LONG:
      _interp->_jvm->lpush(loaded);
      break;
  case T_FLOAT:
      _interp->_jvm->fpush(
          b.CreateBitCast(loaded, b.getFloatTy(), prefix + "_result"));
      break;
  case T_DOUBLE:
      _interp->_jvm->dpush(
          b.CreateBitCast(loaded, b.getDoubleTy(), prefix + "_result"));
      break;
  default:
      ShouldNotReachHere();
      return false;
  }
  return true;
}

// ---- lower_unsafe_atomic_rmw ----
bool JeandleIntrinsicLowering::lower_unsafe_atomic_rmw(
    BasicType type, llvm::AtomicRMWInst::BinOp operation,
    UnsafeAccessKind access_kind) {
  const char* operation_name = nullptr;
  switch (operation) {
    case llvm::AtomicRMWInst::Add:
      operation_name = "get_add";
      break;
    case llvm::AtomicRMWInst::Xchg:
      operation_name = "get_set";
      break;
    default:
      ShouldNotReachHere();
      return false;
  }

  const UnsafePrimitiveTypeInfo type_info = unsafe_primitive_type_info(type);
  const llvm::MaybeAlign alignment = type_info.alignment;
  const char* type_name = type_info.type_name;


  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::LLVMContext& ctx = *_interp->_context;
  llvm::Function* function = _interp->_llvm_func;
  const bool is_long = type == T_LONG;

  // Logical stack, top to bottom: update, long offset, base, Unsafe receiver.
  // There are no guards or deopt paths below, so consume the values directly.
  // lpop handles the category-2 representation of long update and offset.
  llvm::Value* update = is_long ? _interp->_jvm->lpop() : _interp->_jvm->ipop();
  llvm::Value* offset = _interp->_jvm->lpop();
  llvm::Value* base = _interp->_jvm->apop();
  _interp->_jvm->apop();  // Unsafe receiver

  if (type == T_BYTE || type == T_SHORT) {
    llvm::Type* narrow_type = llvm::IntegerType::get(
        ctx, type == T_BYTE ? 8 : 16);
    // Byte/short descriptors use the JVM int computational type.  The Java
    // fallback narrows the update before the atomic operation, including for
    // legal raw classfile callers that pass non-canonical high bits.
    update = b.CreateTrunc(update, narrow_type,
                           std::string("unsafe_") + operation_name + "_" +
                               type_name + "_update");
  }

  std::string prefix = std::string("unsafe_") + operation_name + "_" + type_name;
  llvm::BasicBlock* on_heap = llvm::BasicBlock::Create(
      ctx, prefix + "_on_heap", function);
  llvm::BasicBlock* native_address = llvm::BasicBlock::Create(
      ctx, prefix + "_native_address", function);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(
      ctx, prefix + "_done", function);
  b.CreateCondBr(b.CreateIsNull(base), native_address, on_heap);

  const llvm::AtomicOrdering ordering = unsafe_atomic_ordering(access_kind);
  auto emit_rmw = [&](llvm::Value* address) {
    return b.CreateAtomicRMW(operation, address, update, alignment, ordering,
                             llvm::SyncScope::System);
  };

  b.SetInsertPoint(on_heap);
  _interp->_block->set_tail_llvm_block(on_heap);
  llvm::Value* heap_address = b.CreatePtrAdd(
      base, offset, prefix + "_heap_addr");
  llvm::Value* heap_old = emit_rmw(heap_address);
  b.CreateBr(done);

  b.SetInsertPoint(native_address);
  _interp->_block->set_tail_llvm_block(native_address);
  llvm::PointerType* raw_ptr_type = llvm::PointerType::get(
      ctx, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* raw_address = b.CreateIntToPtr(
      offset, raw_ptr_type, prefix + "_raw_addr");
  llvm::Value* raw_old = emit_rmw(raw_address);
  b.CreateBr(done);

  b.SetInsertPoint(done);
  _interp->_block->set_tail_llvm_block(done);
  llvm::Type* value_type = is_long ? b.getInt64Ty() :
      (type == T_INT ? b.getInt32Ty() :
       llvm::IntegerType::get(ctx, type == T_BYTE ? 8 : 16));
  llvm::PHINode* old = b.CreatePHI(value_type, 2, prefix + "_old");
  old->addIncoming(heap_old, on_heap);
  old->addIncoming(raw_old, native_address);

  if (is_long) {
    _interp->_jvm->lpush(old);
  } else if (type == T_BYTE || type == T_SHORT) {
    _interp->_jvm->ipush(b.CreateSExt(old, b.getInt32Ty(), prefix + "_result"));
  } else {
    _interp->_jvm->ipush(old);
  }
  return true;
}

// ---- lower_preconditions_check_index ----
