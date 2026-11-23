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
#include "llvm/IR/GlobalVariable.h"
#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleType.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciSignature.hpp"
#include "runtime/deoptimization.hpp"

bool JeandleIntrinsicLowering::lower_inlinee() {
  const ciMethod* target = _interp->_method;
  const vmIntrinsics::ID id = target->intrinsic_id();
  switch (id) {
    case vmIntrinsics::_isDigit:
    case vmIntrinsics::_isLowerCase:
    case vmIntrinsics::_isUpperCase:
    case vmIntrinsics::_isWhitespace:
      break;
    default:
      return false;
  }
  if (target->is_static() || target->is_synchronized() ||
      !target->check_intrinsic_candidate() ||
      !_interp->is_intrinsic_enabled(id)) {
    return false;
  }

  assert(_interp->_parse_context.is_inlinee() && !_interp->is_osr(), "inlinee entry required");
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  llvm::Value* ch = _interp->_llvm_func->getArg(1);
  if (id != vmIntrinsics::_isDigit) {
    ch = b.CreateAnd(ch, b.getInt32(0xffff));
  }
  llvm::BasicBlock* fast = llvm::BasicBlock::Create(*_interp->_context, "character_intrinsic", _interp->_llvm_func);
  llvm::BasicBlock* fallback = llvm::BasicBlock::Create(*_interp->_context, "character_bytecodes", _interp->_llvm_func);
  b.CreateCondBr(b.CreateICmpULE(ch, b.getInt32(255)), fast, fallback);
  b.SetInsertPoint(fast);
  _interp->return_current(lower_character_predicate(id, ch));

  // The caller has checked the receiver before dispatch. Unsupported inputs
  // enter the original bytecodes with unchanged locals and an empty stack;
  // no synthetic invoke state or method-entry deoptimization is needed.
  b.SetInsertPoint(fallback);
  _interp->_block_builder->entry_block()->set_tail_llvm_block(fallback);
  return true;
}

llvm::Value* JeandleIntrinsicLowering::lower_character_predicate(vmIntrinsics::ID id, llvm::Value* ch) {
  llvm::IRBuilder<>& b = _interp->_ir_builder;
  if (id == vmIntrinsics::_isDigit) {
    llvm::Value* digit = b.CreateICmpULE(b.CreateSub(ch, b.getInt32('0')), b.getInt32('9' - '0'));
    return b.CreateZExt(digit, b.getInt32Ty());
  }

  // One compact immutable property byte lets related predicates share a
  // load after inlining, while constant characters still fold in LLVM.
  const char* name = "jeandle.latin1_properties";
  llvm::GlobalVariable* table = _interp->_module.getGlobalVariable(name);
  if (table == nullptr) {
    uint8_t properties[256];
    for (unsigned c = 0; c < 256; ++c) {
      const bool lower = (c >= 'a' && c <= 'z') || (c >= 0xdf && c <= 0xf6) ||
                         (c >= 0xf8) || c == 0xaa || c == 0xb5 || c == 0xba;
      const bool upper = (c >= 'A' && c <= 'Z') || (c >= 0xc0 && c <= 0xd6) ||
                         (c >= 0xd8 && c <= 0xde);
      const bool space = (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20);
      properties[c] = (lower ? 1 : 0) | (upper ? 2 : 0) | (space ? 4 : 0);
    }
    llvm::Constant* data = llvm::ConstantDataArray::get(b.getContext(), properties);
    // ODR linkage coalesces identical tables imported by the inline driver.
    table = new llvm::GlobalVariable(_interp->_module, data->getType(), true,
                llvm::GlobalValue::LinkOnceODRLinkage, data, name);
    table->setUnnamedAddr(llvm::GlobalValue::UnnamedAddr::Global);
    table->setDSOLocal(true);
    table->setAlignment(llvm::Align(16));
  }
  unsigned mask;
  switch (id) {
    case vmIntrinsics::_isLowerCase:  mask = 1; break;
    case vmIntrinsics::_isUpperCase:  mask = 2; break;
    case vmIntrinsics::_isWhitespace: mask = 4; break;
    default: ShouldNotReachHere();
  }
  llvm::Value* cell = b.CreateInBoundsGEP(table->getValueType(), table,
                        {b.getInt64(0), b.CreateZExt(ch, b.getInt64Ty())});
  llvm::Value* flags = b.CreateLoad(b.getInt8Ty(), cell);
  llvm::Value* result = b.CreateICmpNE(b.CreateAnd(flags, b.getInt8(mask)), b.getInt8(0));
  return b.CreateZExt(result, b.getInt32Ty());
}

bool JeandleIntrinsicLowering::lower_character_intrinsic(vmIntrinsics::ID id) {
  const bool predicate = !_target->is_static();
  const bool digit = id == vmIntrinsics::_isDigit;
  const bool scan = id == vmIntrinsics::_countPositives;
  const char* op = scan ? "jeandle.count_positives" : "jeandle.encode_array";
  if (!digit && _interp->too_many_traps(_interp->_method, _interp->_bytecodes.cur_bci(),
                                      Deoptimization::Reason_intrinsic)) {
    return false;
  }
  if (!predicate) {
    llvm::Function* function = _interp->_module.getFunction(op);
    llvm::Function* arraylength = _interp->_module.getFunction("jeandle.arraylength");
    if (function == nullptr || function->isDeclaration() || arraylength == nullptr || arraylength->isDeclaration()) {
      return false;
    }
  }

  llvm::IRBuilder<>& b = _interp->_ir_builder;
  ciSignature* sig = _target->signature();
  const int nargs = sig->count();
  llvm::SmallVector<llvm::Value*, 7> args;
  for (int i = 0; i < nargs; ++i) {
    args.push_back(_interp->_jvm->peek_value(nargs - i - 1).value());
  }
  llvm::BasicBlock* slow = nullptr;
  auto guard = [&](llvm::Value* failure) {
    if (slow == nullptr) {
      slow = llvm::BasicBlock::Create(*_interp->_context, "character_fallback", _interp->_llvm_func);
    }
    generate_guard(failure, slow, PROB_MIN);
  };
  llvm::Value* result;
  llvm::BasicBlock* done = nullptr;
  llvm::BasicBlock* empty = nullptr;
  if (predicate) {
    llvm::Value* ch = args[0];
    if (!digit) {
      // The property tables index (char) ch, including wrapped int inputs.
      ch = b.CreateAnd(ch, b.getInt32(0xffff));
      guard(b.CreateICmpUGT(ch, b.getInt32(255)));
    }
    result = lower_character_predicate(id, ch);
  } else {
    llvm::Value* count = args[scan ? 2 : 4];
    done = llvm::BasicBlock::Create(*_interp->_context, "character_done", _interp->_llvm_func);
    llvm::BasicBlock* nonempty = llvm::BasicBlock::Create(*_interp->_context, "character_nonempty", _interp->_llvm_func);
    empty = b.GetInsertBlock();
    llvm::Value* no_access = scan ? b.CreateICmpEQ(count, b.getInt32(0))
                                 : b.CreateICmpSLE(count, b.getInt32(0));
    b.CreateCondBr(no_access, done, nonempty);
    b.SetInsertPoint(nonempty);
    if (scan) {
      guard(b.CreateICmpSLT(count, b.getInt32(0)));
    }
    auto range = [&](llvm::Value* array, llvm::Value* offset, bool utf16_bytes) {
      guard(b.CreateIsNull(array));
      llvm::Value* length = _interp->call_java_op("jeandle.arraylength", {array});
      if (utf16_bytes) {
        length = b.CreateLShr(length, b.getInt32(1));
      }
      guard(b.CreateICmpSLT(offset, b.getInt32(0)));
      llvm::Value* limit = b.CreateAdd(b.CreateZExt(offset, b.getInt64Ty()), b.CreateZExt(count, b.getInt64Ty()));
      guard(b.CreateICmpUGT(limit, b.CreateZExt(length, b.getInt64Ty())));
    };
    const bool src_bytes = id == vmIntrinsics::_encodeByteISOArray;
    range(args[0], args[1], src_bytes);
    if (!scan) {
      range(args[2], args[3], false);
      if (src_bytes) {
        // Width-changing forward copies may read their own earlier stores.
        guard(b.CreateICmpEQ(args[0], args[2]));
      }
      args.push_back(b.getInt1(src_bytes));
      args.push_back(b.getInt1(id == vmIntrinsics::_encodeAsciiArray));
    }
    const CallSiteAttributeMetadata attrs = {CTRL_NONE, static_cast<uint16_t>(MEM_READ | (scan ? 0 : MEM_WRITE))};
    llvm::CallBase* call = _interp->call_java_op(op, args);
    call->setDoesNotThrow();
    annotate_call(call, attrs);
    apply_memory_attr(call, attrs);
    result = call;
  }

  // Deoptimization must see the original invoke arguments before any stores.
  if (slow != nullptr) {
    _interp->uncommon_trap(Deoptimization::Reason_intrinsic, Deoptimization::Action_maybe_recompile, slow);
  }
  for (int i = nargs - 1; i >= 0; --i) {
    _interp->_jvm->pop(JeandleType::actual2computational(sig->type_at(i)->basic_type()));
  }
  if (predicate) {
    _interp->_jvm->apop();
  }
  if (done != nullptr) {
    llvm::BasicBlock* last = b.GetInsertBlock();
    b.CreateBr(done);
    b.SetInsertPoint(done);
    llvm::PHINode* merged = b.CreatePHI(b.getInt32Ty(), 2, "character_result");
    merged->addIncoming(b.getInt32(0), empty);
    merged->addIncoming(result, last);
    result = merged;
  }
  _interp->_jvm->ipush(result);
  _interp->_block->set_tail_llvm_block(b.GetInsertBlock());
  return true;
}
