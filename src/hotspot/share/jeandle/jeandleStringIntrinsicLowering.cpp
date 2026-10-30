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
#include "llvm/IR/Constants.h"
#include "llvm/IR/DerivedTypes.h"
#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "memory/universe.hpp"
#include "runtime/deoptimization.hpp"

static const char* string_intrinsic_java_op(vmIntrinsics::ID id) {
  switch (id) {
    case vmIntrinsics::_equalsL:
    case vmIntrinsics::_equalsU:
      return "jeandle.string_equals";
    case vmIntrinsics::_compareToL:
    case vmIntrinsics::_compareToU:
    case vmIntrinsics::_compareToLU:
    case vmIntrinsics::_compareToUL:
      return "jeandle.string_compare";
    case vmIntrinsics::_indexOfL:
    case vmIntrinsics::_indexOfU:
    case vmIntrinsics::_indexOfUL:
    case vmIntrinsics::_indexOfIL:
    case vmIntrinsics::_indexOfIU:
    case vmIntrinsics::_indexOfIUL:
      return "jeandle.string_indexof";
    case vmIntrinsics::_indexOfL_char:
    case vmIntrinsics::_indexOfU_char:
      return "jeandle.string_indexof_char";
    case vmIntrinsics::_toBytesStringU:
    case vmIntrinsics::_getCharsStringU:
      return "jeandle.string_copy_utf16";
    case vmIntrinsics::_compressStringC:
    case vmIntrinsics::_compressStringB:
      return "jeandle.string_compress";
    case vmIntrinsics::_inflateStringC:
    case vmIntrinsics::_inflateStringB:
      return "jeandle.string_inflate";
    default:
      return nullptr;
  }
}

bool JeandleIntrinsicLowering::lower_string_intrinsic(vmIntrinsics::ID id) {
  // Refuse before changing either IR or the operand stack. Unusual inputs
  // then use Java after trap profiling, including its partial-write behavior.
  if (_interp->too_many_traps(Deoptimization::Reason_intrinsic)) {
    return false;
  }
  const char* op = string_intrinsic_java_op(id);
  auto has_definition = [&](const char* name) {
    llvm::Function* function = _interp->_module.getFunction(name);
    return function != nullptr && !function->isDeclaration();
  };
  if (op == nullptr || !has_definition(op) || !has_definition("jeandle.arraylength") ||
      (id == vmIntrinsics::_toBytesStringU && !has_definition("jeandle.new_array"))) {
    return false;
  }

  llvm::IRBuilder<>& b = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  const int nargs = sig->count();
  llvm::SmallVector<llvm::Value*, 5> args;
  for (int i = 0; i < nargs; ++i) {
    args.push_back(_interp->_jvm->peek_value(nargs - i - 1).value());
  }
  llvm::BasicBlock* slow = llvm::BasicBlock::Create(
      *_interp->_context, "string_fallback", _interp->_llvm_func);
  llvm::BasicBlock* done = nullptr;
  llvm::SmallVector<std::pair<llvm::Value*, llvm::BasicBlock*>, 2> early_results;
  auto early_return = [&](llvm::Value* condition, llvm::Value* result) {
    if (done == nullptr) {
      done = llvm::BasicBlock::Create(*_interp->_context, "string_done", _interp->_llvm_func);
    }
    llvm::BasicBlock* next = llvm::BasicBlock::Create(
        *_interp->_context, "string_nonempty", _interp->_llvm_func);
    early_results.push_back({result, b.GetInsertBlock()});
    b.CreateCondBr(condition, done, next);
    b.SetInsertPoint(next);
  };
  auto guard = [&](llvm::Value* failure) {
    generate_guard(failure, slow, PROB_MIN);
  };
  auto length = [&](llvm::Value* array, bool utf16_bytes) -> llvm::Value* {
    guard(b.CreateIsNull(array));
    llvm::Value* len = _interp->call_java_op("jeandle.arraylength", {array});
    if (utf16_bytes) {
      guard(b.CreateICmpNE(b.CreateAnd(len, b.getInt32(1)), b.getInt32(0)));
      len = b.CreateLShr(len, b.getInt32(1));
    }
    return len;
  };
  auto range = [&](llvm::Value* off, llvm::Value* count, llvm::Value* len) {
    guard(b.CreateICmpSLT(off, b.getInt32(0)));
    guard(b.CreateICmpSLT(count, b.getInt32(0)));
    // Widen before adding: malicious int ranges must never wrap into a
    // seemingly valid interval. Neither pointer arithmetic nor stores precede
    // these checks.
    llvm::Value* end = b.CreateAdd(b.CreateZExt(off, b.getInt64Ty()),
                                  b.CreateZExt(count, b.getInt64Ty()));
    guard(b.CreateICmpUGT(end, b.CreateZExt(len, b.getInt64Ty())));
  };

  llvm::SmallVector<llvm::Value*, 7> operands;
  bool writes = false;
  bool allocate = false;
  llvm::Value* allocation_length = nullptr;
  llvm::Value* equal_lengths = nullptr;

  switch (id) {
    case vmIntrinsics::_equalsL:
    case vmIntrinsics::_equalsU: {
      const bool utf16 = id == vmIntrinsics::_equalsU;
      llvm::Value* a_count = length(args[0], utf16);
      llvm::Value* b_count = length(args[1], utf16);
      equal_lengths = b.CreateICmpEQ(a_count, b_count);
      // Mismatched lengths require no content reads, even if one is empty.
      llvm::Value* bytes = utf16 ? b.CreateShl(a_count, b.getInt32(1)) : a_count;
      bytes = b.CreateSelect(equal_lengths, bytes, b.getInt32(0));
      operands = {args[0], args[1], bytes};
      break;
    }
    case vmIntrinsics::_compareToL:
    case vmIntrinsics::_compareToU:
    case vmIntrinsics::_compareToLU:
    case vmIntrinsics::_compareToUL: {
      const bool a_utf16 = id == vmIntrinsics::_compareToU || id == vmIntrinsics::_compareToUL;
      const bool b_utf16 = id == vmIntrinsics::_compareToU || id == vmIntrinsics::_compareToLU;
      llvm::Value* a_count = length(args[0], a_utf16);
      llvm::Value* b_count = length(args[1], b_utf16);
      operands = {args[0], a_count, args[1], b_count,
                  b.getInt32((a_utf16 ? 1 : 0) | (b_utf16 ? 2 : 0))};
      break;
    }
    case vmIntrinsics::_indexOfL:
    case vmIntrinsics::_indexOfU:
    case vmIntrinsics::_indexOfUL:
    case vmIntrinsics::_indexOfIL:
    case vmIntrinsics::_indexOfIU:
    case vmIntrinsics::_indexOfIUL: {
      const bool explicit_counts = nargs == 5;
      const bool a_utf16 = id != vmIntrinsics::_indexOfL && id != vmIntrinsics::_indexOfIL;
      const bool b_utf16 = id == vmIntrinsics::_indexOfU || id == vmIntrinsics::_indexOfIU;
      // Explicit counts address complete code units; an unused trailing byte
      // does not participate in Java's UTF16 bounds checks.
      auto search_length = [&](llvm::Value* array, bool utf16) -> llvm::Value* {
        if (explicit_counts && utf16) {
          return b.CreateLShr(length(array, false), b.getInt32(1));
        }
        return length(array, utf16);
      };
      llvm::Value* target = args[explicit_counts ? 2 : 1];
      llvm::Value* b_len = search_length(target, b_utf16);
      if (!explicit_counts) {
        early_return(b.CreateICmpEQ(b_len, b.getInt32(0)), b.getInt32(0));
      }
      llvm::Value* a_len = search_length(args[0], a_utf16);
      llvm::Value* a_count = explicit_counts ? args[1] : a_len;
      llvm::Value* b_count = explicit_counts ? args[3] : b_len;
      llvm::Value* from = explicit_counts ? args[4] : b.getInt32(0);
      // Empty targets differ between trusted explicit-count signatures and
      // must replay Java. Full-search's empty target returned above.
      if (explicit_counts) {
        // Lengths are nonnegative. Subtracting one with wrapping arithmetic
        // checks the target's nonempty interval in a single unsigned test.
        llvm::Value* invalid_source = b.CreateICmpUGT(a_count, a_len);
        llvm::Value* invalid_target = b.CreateICmpUGE(b.CreateSub(b_count, b.getInt32(1)), b_len);
        llvm::Value* invalid_start = b.CreateICmpUGT(from, a_count);
        guard(b.CreateOr(b.CreateOr(invalid_source, invalid_target), invalid_start));
      } else {
        guard(b.CreateICmpSLE(b_count, b.getInt32(0)));
        range(b.getInt32(0), a_count, a_len);
        range(b.getInt32(0), b_count, b_len);
        range(from, b.getInt32(0), a_count);
      }
      operands = {args[0], a_count, target, b_count, from,
                  b.getInt32((a_utf16 ? 1 : 0) | (b_utf16 ? 2 : 0))};
      break;
    }
    case vmIntrinsics::_indexOfL_char:
    case vmIntrinsics::_indexOfU_char: {
      const bool utf16 = id == vmIntrinsics::_indexOfU_char;
      if (!utf16) {
        early_return(b.CreateICmpSGE(args[2], args[3]), b.getInt32(-1));
      }
      llvm::Value* len = length(args[0], utf16);
      range(b.getInt32(0), args[3], len);
      range(args[2], b.getInt32(0), args[3]);
      operands = {args[0], args[1], args[2], args[3], b.getInt1(utf16)};
      break;
    }
    case vmIntrinsics::_toBytesStringU: {
      // Java allocates before reading the source. Invalid inputs replay the
      // original method, and an empty allocation does not inspect the source.
      guard(b.CreateICmpSLT(args[2], b.getInt32(0)));
      guard(b.CreateICmpUGT(args[2], b.getInt32(max_jint / 2)));
      llvm::BasicBlock* ready = llvm::BasicBlock::Create(
          *_interp->_context, "string_allocation_ready", _interp->_llvm_func);
      llvm::BasicBlock* nonempty = llvm::BasicBlock::Create(
          *_interp->_context, "string_copy_nonempty", _interp->_llvm_func);
      b.CreateCondBr(b.CreateICmpEQ(args[2], b.getInt32(0)), ready, nonempty);
      b.SetInsertPoint(nonempty);
      range(args[1], args[2], length(args[0], false));
      b.CreateBr(ready);
      b.SetInsertPoint(ready);
      allocation_length = b.CreateShl(args[2], b.getInt32(1));
      allocate = true;
      writes = true;
      operands = {args[0], args[1], nullptr, b.getInt32(0), args[2], b.getInt1(false)};
      break;
    }
    case vmIntrinsics::_getCharsStringU:
    case vmIntrinsics::_compressStringC:
    case vmIntrinsics::_compressStringB:
    case vmIntrinsics::_inflateStringC:
    case vmIntrinsics::_inflateStringB: {
      const bool copy = id == vmIntrinsics::_getCharsStringU;
      const bool compress = id == vmIntrinsics::_compressStringC || id == vmIntrinsics::_compressStringB;
      const bool src_bytes = copy || id == vmIntrinsics::_compressStringB;
      const bool dst_bytes = id == vmIntrinsics::_inflateStringB;
      llvm::Value* src = args[0];
      llvm::Value* src_off = args[1];
      llvm::Value* dst = args[copy ? 3 : 2];
      llvm::Value* dst_off = args[copy ? 4 : 3];
      llvm::Value* count = args[4];
      if (copy) {
        early_return(b.CreateICmpSGE(src_off, args[2]), nullptr);
        llvm::Value* wide_count = b.CreateSub(b.CreateSExt(args[2], b.getInt64Ty()),
                                             b.CreateSExt(src_off, b.getInt64Ty()));
        guard(b.CreateICmpSGT(wide_count, b.getInt64(max_jint)));
        count = b.CreateTrunc(wide_count, b.getInt32Ty());
      } else if (id == vmIntrinsics::_compressStringC || id == vmIntrinsics::_inflateStringC) {
        early_return(b.CreateICmpSLE(count, b.getInt32(0)), compress ? count : nullptr);
      } else {
        guard(b.CreateICmpSLT(count, b.getInt32(0)));
      }
      if (dst_bytes) {
        // The byte-backed inflate helper checks its destination even for an
        // empty loop, but does not dereference the source in that case.
        range(dst_off, count, length(dst, true));
        early_return(b.CreateICmpEQ(count, b.getInt32(0)), nullptr);
      }
      range(src_off, count, length(src, src_bytes));
      if (id == vmIntrinsics::_compressStringB) {
        early_return(b.CreateICmpEQ(count, b.getInt32(0)), b.getInt32(0));
      }
      if (!dst_bytes) {
        range(dst_off, count, length(dst, false));
      }
      if (id == vmIntrinsics::_compressStringB || dst_bytes) {
        // Width-changing forward loops can observe their own earlier writes.
        guard(b.CreateICmpEQ(src, dst));
      }
      operands = {src, src_off, dst, dst_off, count,
                  b.getInt1(copy || compress ? src_bytes : dst_bytes)};
      writes = true;
      break;
    }
    default:
      ShouldNotReachHere();
  }

  // Keep the original invoke operands until allocation has captured its
  // reexecute state. Every guard precedes allocation and bulk stores.
  // Guard failures replay the Java method; as in C2's string intrinsics, a
  // hot guard only discourages recompilation of this site rather than
  // discarding the compiled method outright.
  _interp->uncommon_trap(Deoptimization::Reason_intrinsic,
                         Deoptimization::Action_maybe_recompile, slow);
  llvm::Value* allocated = nullptr;
  llvm::BasicBlock* allocation_done = nullptr;
  if (allocate) {
    JeandlePreserveReexecuteState reexecute_scope(_interp);
    _interp->_should_reexecute = true;
    llvm::PointerType* klass_type = llvm::PointerType::get(
        *_interp->_context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
    llvm::Value* array_klass = b.CreateIntToPtr(
        b.getInt64((intptr_t)Universe::byteArrayKlassObj()), klass_type);
    // The copy overwrites every payload byte before the array can escape
    // (an empty allocation has no payload), so the allocation skips bulk
    // zeroing; the deferred publication barrier is re-issued at the merge
    // below, covering both the copy path and the empty path's header.
    allocated = _interp->emit_jeandle_newarray(array_klass, allocation_length,
                                               /*return_size_val=*/nullptr,
                                               /*deoptimize_on_exception=*/false,
                                               /*uninitialized=*/true,
                                               /*defer_initialization_membar=*/true);
    RETURN_ON_JEANDLE_ERROR(true);
    attach_java_klass_ret_attr(llvm::cast<llvm::CallBase>(allocated),
                               sig->return_type(), *_interp->_context);
    operands[2] = allocated;
  }
  for (int i = nargs - 1; i >= 0; --i) {
    _interp->_jvm->pop(JeandleType::actual2computational(sig->type_at(i)->basic_type()));
  }
  if (allocate) {
    allocation_done = llvm::BasicBlock::Create(
        *_interp->_context, "string_allocation_done", _interp->_llvm_func);
    llvm::BasicBlock* copy = llvm::BasicBlock::Create(
        *_interp->_context, "string_allocation_copy", _interp->_llvm_func);
    b.CreateCondBr(b.CreateICmpEQ(args[2], b.getInt32(0)), allocation_done, copy);
    b.SetInsertPoint(copy);
  }
  const CallSiteAttributeMetadata attrs = {
      CTRL_NONE, static_cast<uint16_t>(MEM_READ | (writes ? MEM_WRITE : 0))};
  // The copy's void return is distinct from the enclosing Java allocation's
  // byte[] result. Annotate the leaf call independently of the Java signature.
  llvm::CallBase* call = _interp->call_java_op(op, operands);
  call->setDoesNotThrow();
  annotate_call(call, attrs);
  apply_memory_attr(call, attrs);
  llvm::Value* result = call;
  if (equal_lengths != nullptr) {
    result = b.CreateAnd(result, b.CreateZExt(equal_lengths, b.getInt32Ty()));
  }
  if (allocate) {
    b.CreateBr(allocation_done);
    b.SetInsertPoint(allocation_done);
    // The allocator deferred its fast-path StoreStore: the header stores and
    // the payload copy publish together here, before the array escapes.
    // PEA elimination of the allocation leaves this barrier call behind; it
    // carries no virtual operands and is a no-op on TSO targets.
    _interp->call_java_op("jeandle.membar_storestore", {});
    RETURN_ON_JEANDLE_ERROR(true);
    _interp->_jvm->apush(allocated);
  } else {
    if (done != nullptr) {
      llvm::BasicBlock* last = b.GetInsertBlock();
      b.CreateBr(done);
      b.SetInsertPoint(done);
      if (sig->return_type()->basic_type() != T_VOID) {
        llvm::PHINode* merged = b.CreatePHI(b.getInt32Ty(), early_results.size() + 1);
        merged->addIncoming(result, last);
        for (const auto& early : early_results) {
          merged->addIncoming(early.first, early.second);
        }
        result = merged;
      }
    }
    if (sig->return_type()->basic_type() != T_VOID) {
      _interp->_jvm->ipush(result);
    }
  }
  _interp->_block->set_tail_llvm_block(b.GetInsertBlock());
  return true;
}
