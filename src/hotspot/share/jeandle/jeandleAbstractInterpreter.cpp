/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

#include "llvm/IR/Attributes.h"
#include "llvm/IR/Jeandle/Attributes.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleJavaCall.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "utilities/debug.hpp"
#include "ci/ciMethodBlocks.hpp"
#include "runtime/sharedRuntime.hpp"
#include "utilities/ostream.hpp"

JeandleBasicBlock::JeandleBasicBlock(int block_id,
                                     int start_bci,
                                     llvm::BasicBlock* llvm_block) :
                                     _block_id(block_id),
                                     _flags(no_flag),
                                     _start_bci(start_bci),
                                     _llvm_block(llvm_block),
                                     _reverse_post_order(-1),
                                     _jvm(nullptr),
                                     _initial_loop_header(nullptr) {}

bool JeandleBasicBlock::income_block(JeandleBasicBlock* income, ciMethod* method, llvm::IRBuilder<>* ir_builder) {
  JeandleVMState* income_jvm = income->jvm_tracker();

  if (_jvm == nullptr) {
    if (is_set(is_compiled)) {
      // A compiled block with null JeandleVMState.
      return false;
    }

    MethodLivenessResult liveness = method->liveness_at_bci(_start_bci);
    if (_predecessors.size() == 1) {
      // Just one predecessor. Copy its JeandleVMState.
      assert(!is_set(is_loop_header), "should not be a loop header");
      _jvm = income_jvm->copy(liveness);
    } else {
      // More than one predecessors. Set up phi nodes.
      _jvm = JeandleVMState::create_phi_from(income, this, liveness, ir_builder);
    }

    if (is_set(is_loop_header)) {
      // Copy loop header's initial JeandleVMState.
      _initial_loop_header = new JeandleVMState(_jvm);
    }

    return true;

  } else if (!is_set(is_compiled)) {
    assert(_predecessors.size() > 1, "more than one predecessors are needed for phi nodes");
    return _jvm->phi(income);
  } else if (is_set(is_loop_header)) {
    assert(_initial_loop_header != nullptr, "loop header initial JeandleVMState is needed");
    return _initial_loop_header->phi(income);
  }

  // Bad bytecodes.
  return false;
}

BasicBlockBuilder::BasicBlockBuilder(ciMethod* method,
                                     llvm::LLVMContext* context,
                                     llvm::Function* llvm_func) :
                                     _bci2block(),
                                     _method(method),
                                     _context(context),
                                     _llvm_func(llvm_func),
                                     _entry_block(new JeandleBasicBlock(-1, -1, llvm::BasicBlock::Create(*_context, "entry", _llvm_func))),
                                     _next_block_id(0),
                                     _active(),
                                     _visited(),
                                     _next_block_order(-1) {
  generate_blocks();
  mark_loops();
}

void BasicBlockBuilder::generate_blocks() {
  // ciMethodBlocks helps to cut bytecodes into basic blocks.
  ciMethodBlocks* ci_blocks = _method->get_method_blocks();

  ciBytecodeStream codes(_method);

  JeandleBasicBlock* current = _entry_block;

  int end_bci = _method->code_size();

  while (codes.next() != ciBytecodeStream::EOBC()) {
    int cur_bci = codes.cur_bci();

    if (ci_blocks->is_block_start(cur_bci)) {
      current = make_block_at(cur_bci, current);
    }

    assert(current != nullptr, "basic block can not be null");

    switch (codes.cur_bc()) {
      // Track bytecodes that affect the control flow.
      case Bytecodes::_athrow:  // fall through
      case Bytecodes::_ret:     // fall through
      case Bytecodes::_ireturn: // fall through
      case Bytecodes::_lreturn: // fall through
      case Bytecodes::_freturn: // fall through
      case Bytecodes::_dreturn: // fall through
      case Bytecodes::_areturn: // fall through
      case Bytecodes::_return:
        current = nullptr;
        break;

      case Bytecodes::_ifeq:      // fall through
      case Bytecodes::_ifne:      // fall through
      case Bytecodes::_iflt:      // fall through
      case Bytecodes::_ifge:      // fall through
      case Bytecodes::_ifgt:      // fall through
      case Bytecodes::_ifle:      // fall through
      case Bytecodes::_if_icmpeq: // fall through
      case Bytecodes::_if_icmpne: // fall through
      case Bytecodes::_if_icmplt: // fall through
      case Bytecodes::_if_icmpge: // fall through
      case Bytecodes::_if_icmpgt: // fall through
      case Bytecodes::_if_icmple: // fall through
      case Bytecodes::_if_acmpeq: // fall through
      case Bytecodes::_if_acmpne: // fall through
      case Bytecodes::_ifnull:    // fall through
      case Bytecodes::_ifnonnull:
        if (codes.next_bci() < end_bci) {
          make_block_at(codes.next_bci(), current);
        }
        make_block_at(codes.get_dest(), current);
        current = nullptr;
        break;

      case Bytecodes::_goto:
        make_block_at(codes.get_dest(), current);
        current = nullptr;
        break;

      case Bytecodes::_goto_w:
        make_block_at(codes.get_far_dest(), current);
        current = nullptr;
        break;

      case Bytecodes::_lookupswitch: {
        // Set block for each case.
        Bytecode_lookupswitch sw(&codes);
        int length = sw.number_of_pairs();
        for (int i = 0; i < length; i++) {
          make_block_at(cur_bci + sw.pair_at(i).offset(), current);
        }
        make_block_at(cur_bci + sw.default_offset(), current);
        current = nullptr;
        break;
      }

      default:
        break;
    }
  }
}

JeandleBasicBlock* BasicBlockBuilder::make_block_at(int bci, JeandleBasicBlock* current) {
  JeandleBasicBlock* b = _bci2block[bci];

  if (b == nullptr) {
    b = new JeandleBasicBlock(_next_block_id++, bci, llvm::BasicBlock::Create(*_context, "bci_" + std::to_string(bci), _llvm_func));
    _bci2block[bci] = b;
  }

  if (current != nullptr) {
    current->add_successors(b);
    b->add_predecessors(current);
  }

  return b;
}

void BasicBlockBuilder::mark_loops() {
  ResourceMark rm;

  _active.initialize(_next_block_id);
  _visited.initialize(_next_block_id);
  _next_block_order = _next_block_id - 1;

  mark_loops(_bci2block[0]);

  // Remove dangling Resource pointers before the ResourceMark goes out-of-scope.
  _active.resize(0);
  _visited.resize(0);
}

void BasicBlockBuilder::mark_loops(JeandleBasicBlock* block) {
  int block_id = block->block_id();

  if (_visited.at(block_id)) {
    if (_active.at(block_id)) {
      // Reached block via backward branch.
      block->set(JeandleBasicBlock::is_loop_header);
    }
    return;
  }

  // Set active and visited bits before successors are processed.
  _visited.set_bit(block_id);
  _active.set_bit(block_id);

  for (JeandleBasicBlock* suc : block->successors()) {
    mark_loops(suc);
  }

  // Clear active-bit after all successors are processed.
  _active.clear_bit(block_id);

  // Reverse-post-order numbering of all blocks.
  block->set_reverse_post_order(_next_block_order--);
}

JeandleVMState::JeandleVMState(int max_stack, int max_locals, llvm::LLVMContext *context) :
                               _stack(), _locals(max_locals), _context(context) {
  _stack.reserve(max_stack);
}

JeandleVMState::JeandleVMState(JeandleVMState* copy_from) :
                               _stack(copy_from->_stack),
                               _locals(copy_from->_locals),
                               _context(copy_from->_context) {
  _stack.reserve(copy_from->_stack.capacity());
}

JeandleVMState* JeandleVMState::copy(MethodLivenessResult liveness) {
  JeandleVMState* copied =  new JeandleVMState(this);

  // Use method liveness to invalidate dead locals.
  if (liveness.is_valid()) {
    for (int i = 0; i < (int) _locals.size(); i++) {
      if (_locals[i] != nullptr && !liveness.at(i)) {
        copied->invalidate_local(i);
      }
    }
  }

  return copied;
}

JeandleVMState* JeandleVMState::create_phi_from(JeandleBasicBlock* from,
                                                JeandleBasicBlock* self,
                                                MethodLivenessResult liveness,
                                                llvm::IRBuilder<>* ir_builder) {
  JeandleVMState* from_jvm = from->jvm_tracker();
  JeandleVMState* new_jvm = new JeandleVMState(from_jvm->_stack.capacity(), from_jvm->_locals.size(), from_jvm->_context);

  ir_builder->SetInsertPoint(self->llvm_block());

  for (size_t i = 0; i < from_jvm->_locals.size(); i++) {
    if (from_jvm->_locals[i] == nullptr) {
      continue;
    }

    // Use method liveness to invalidate dead locals.
    if (liveness.is_valid() && !liveness.at(i)) {
      continue;
    }

    llvm::PHINode* phi_node = ir_builder->CreatePHI(from_jvm->_locals[i]->getType(), 2, "");
    phi_node->addIncoming(from_jvm->_locals[i], from->llvm_block());
    new_jvm->_locals[i] = phi_node;
  }

  for (size_t i = 0; i < from_jvm->_stack.size(); i++) {
    if (from_jvm->_stack[i] == nullptr) {
      new_jvm->_stack.push_back(nullptr);
      continue;
    }

    llvm::PHINode* phi_node = ir_builder->CreatePHI(from_jvm->_stack[i]->getType(), 2, "");
    phi_node->addIncoming(from_jvm->_stack[i], from->llvm_block());
    new_jvm->_stack.push_back(phi_node);
  }

  return new_jvm;
}

bool JeandleVMState::phi(JeandleBasicBlock* income) {
  if (!match(income->jvm_tracker())) {
    return false;
  }

  std::vector<llvm::Value*>& in_locals = income->jvm_tracker()->_locals;
  std::vector<llvm::Value*>& in_stack = income->jvm_tracker()->_stack;

  llvm::BasicBlock* in_block = income->llvm_block();

  // Create phi nodes for locals.
  for (size_t i = 0; i < _locals.size(); i++) {
    if (_locals[i] == nullptr) {
      continue;
    }

    llvm::PHINode* phi_node = llvm::cast<llvm::PHINode>(_locals[i]);

    if (in_locals[i] == nullptr || phi_node->getType() != in_locals[i]->getType()) {
      invalidate_local(i);
      continue;
    }

    phi_node->addIncoming(in_locals[i], in_block);
  }

  // Create phi nodes for stack.
  for (size_t i = 0; i < _stack.size(); i++) {
    if (_stack[i] == nullptr) {
      continue;
    }

    llvm::PHINode* phi_node = llvm::cast<llvm::PHINode>(_stack[i]);

    phi_node->addIncoming(in_stack[i], in_block);
  }

  return true;
}

static bool match_values(std::vector<llvm::Value*>& v_a, std::vector<llvm::Value*>& v_b) {
  if (v_a.size() != v_b.size()) {
    return false;
  }

  for (size_t i = 0; i < v_a.size(); i++) {
    if (v_a[i] == nullptr) {
      if (v_b[i] != nullptr) {
        return false;
      }
      continue;
    }

    if (v_b[i] == nullptr) {
      return false;
    }

    // For call instructions, getType() returns the return type.
    if (v_a[i]->getType() != v_b[i]->getType()) {
      return false;
    }
  }

  return true;
}

bool JeandleVMState::match(JeandleVMState* jvm) {
  return _locals.size() == jvm->_locals.size() && match_values(_stack, jvm->_stack);
}

// Stack operations:

void JeandleVMState::push(BasicType type, llvm::Value* value) {
  assert(value != nullptr, "null value to push");
  assert(value->getType() == JeandleType::java2llvm(type, *_context), "type must match");
  _stack.push_back(value);
  if (is_double_word_type(type)) {
    _stack.push_back(nullptr);
  }
}

llvm::Value* JeandleVMState::pop(BasicType type) {
  if (is_double_word_type(type)) {
    assert(_stack.back() == nullptr, "hi-word of doubleword value must be null");
    _stack.pop_back();
  }
  llvm::Value* v = _stack.back();
  assert(v != nullptr, "null value to pop");
  assert(v->getType() == JeandleType::java2llvm(type, *_context), "type must match");
  _stack.pop_back();
  return v;
}

// Locals operations:

llvm::Value* JeandleVMState::load(BasicType type, int index) {
  assert(!is_double_word_type(type) || _locals[index + 1] == nullptr, "hi-word of doubleword value must be null");
  assert(_locals[index] != nullptr, "null value to load");
  assert(_locals[index]->getType() == JeandleType::java2llvm(type, *_context), "type must match");
  return _locals[index];
}

void JeandleVMState::store(BasicType type, int index, llvm::Value* value) {
  assert(value != nullptr, "null value to store");
  assert(value->getType() == JeandleType::java2llvm(type, *_context), "type must match");
  if (index > 0) {
    // When overwriting local i, check if i - 1 was the start of a double word local and kill it.
    llvm::Value* prev = _locals[index - 1];
    if (prev != nullptr && JeandleType::is_double_word_type(prev->getType())) {
      _locals[index - 1] = nullptr;
    }
  }
  _locals[index] = value;
  if (is_double_word_type(type)) {
    _locals[index + 1] = nullptr;
  }
}

JeandleAbstractInterpreter::JeandleAbstractInterpreter(ciMethod* method,
                                                       int entry_bci,
                                                       llvm::Module& target_module,
                                                       JeandleCompiledCode& code) :
                                                       _method(method),
                                                       _llvm_func(JeandleFuncSig::create_llvm_func(method, target_module)),
                                                       _entry_bci(entry_bci),
                                                       _context(&target_module.getContext()),
                                                       _codes(_method),
                                                       _module(target_module),
                                                       _code(code),
                                                       _block_builder(new BasicBlockBuilder(method, _context, _llvm_func)),
                                                       _ir_builder(_block_builder->entry_block()->llvm_block()),
                                                       _statepoint_id(0),
                                                       _oop_idx(0) {
  // Fill basic blocks with LLVM IR.
  interpret();
}

void JeandleAbstractInterpreter::initialize_VM_state() {
  JeandleVMState* initial_jvm = new JeandleVMState(_method->max_stack(), _method->max_locals(), _context);
  int locals_idx = 0; // next index in locals
  int arg_idx = 0;  // next index in arguments

  // Store the reciever into locals.
  if (!_method->is_static()) {
    initial_jvm->store(BasicType::T_OBJECT, 0, _llvm_func->getArg(0));
    locals_idx = 1;
    arg_idx = 1;
  }

  // Set up locals for incoming arguments.
  ciSignature* sig = _method->signature();
  for (int i = 0; i < sig->count(); ++i, ++arg_idx) {
    ciType* type = sig->type_at(i);
    initial_jvm->store(type->basic_type(), locals_idx, _llvm_func->getArg(arg_idx));
    locals_idx += type->size();
  }

  _block_builder->entry_block()->set_jvm_tracker(initial_jvm);
}

void JeandleAbstractInterpreter::interpret() {
  JeandleBasicBlock* current = bci2block()[0];

  // Prepare work list. Push the first block.
  add_to_work_list(current);

  // Create branch from the entry block.
  _ir_builder.CreateBr(current->llvm_block());

  initialize_VM_state();

  current->income_block(_block_builder->entry_block(), _method, &_ir_builder);

  // Iterate all blocks
  while (_work_list.size() > 0) {
    current = _work_list.back();
    _work_list.pop_back();
    current->clear(JeandleBasicBlock::is_on_work_list);

    interpret_block(current);
  }
}

void JeandleAbstractInterpreter::interpret_block(JeandleBasicBlock* block) {
  assert(block != nullptr, "compile a null block");

  _ir_builder.SetInsertPoint(block->llvm_block());

  _block = block;
  _jvm = block->jvm_tracker();
  assert(_jvm != nullptr, "JeandleVMState should not be null");

  _codes.reset_to_bci(block->start_bci());

  Bytecodes::Code code = Bytecodes::_illegal;

  // Iterate all bytecodes.
  while ((code = _codes.next()) != ciBytecodeStream::EOBC() && !JeandleCompilation::jeandle_error_occurred() &&
          (bci2block()[_codes.cur_bci()] == nullptr || bci2block()[_codes.cur_bci()] == block)) {
    // Handle by opcode, see: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-7.html
    switch (code) {
      case Bytecodes::_nop: break;

      // Constants:

      case Bytecodes::_iconst_m1: _jvm->ipush(JeandleType::int_const(_ir_builder, -1)); break;
      case Bytecodes::_iconst_0: _jvm->ipush(JeandleType::int_const(_ir_builder, 0)); break;
      case Bytecodes::_iconst_1: _jvm->ipush(JeandleType::int_const(_ir_builder, 1)); break;
      case Bytecodes::_iconst_2: _jvm->ipush(JeandleType::int_const(_ir_builder, 2)); break;
      case Bytecodes::_iconst_3: _jvm->ipush(JeandleType::int_const(_ir_builder, 3)); break;
      case Bytecodes::_iconst_4: _jvm->ipush(JeandleType::int_const(_ir_builder, 4)); break;
      case Bytecodes::_iconst_5: _jvm->ipush(JeandleType::int_const(_ir_builder, 5)); break;

      case Bytecodes::_lconst_0: _jvm->lpush(JeandleType::long_const(_ir_builder, 0)); break;
      case Bytecodes::_lconst_1: _jvm->lpush(JeandleType::long_const(_ir_builder, 1)); break;

      case Bytecodes::_fconst_0: _jvm->fpush(JeandleType::float_const(_ir_builder, 0)); break;
      case Bytecodes::_fconst_1: _jvm->fpush(JeandleType::float_const(_ir_builder, 1)); break;
      case Bytecodes::_fconst_2: _jvm->fpush(JeandleType::float_const(_ir_builder, 2)); break;

      case Bytecodes::_dconst_0: _jvm->dpush(JeandleType::double_const(_ir_builder, 0)); break;
      case Bytecodes::_dconst_1: _jvm->dpush(JeandleType::double_const(_ir_builder, 1)); break;

      case Bytecodes::_aconst_null: Unimplemented(); break;

      case Bytecodes::_bipush: _jvm->ipush(JeandleType::int_const(_ir_builder, (((signed char*)_codes.cur_bcp())[1]))); break;
      case Bytecodes::_sipush: _jvm->ipush(JeandleType::int_const(_ir_builder, (short)Bytes::get_Java_u2(_codes.cur_bcp()+1))); break;

      case Bytecodes::_ldc:    // fall through
      case Bytecodes::_ldc_w:  // fall through
      case Bytecodes::_ldc2_w: load_constant(); break;

      // Loads:

      case Bytecodes::_iload_0: _jvm->ipush(_jvm->iload(0)); break;
      case Bytecodes::_iload_1: _jvm->ipush(_jvm->iload(1)); break;
      case Bytecodes::_iload_2: _jvm->ipush(_jvm->iload(2)); break;
      case Bytecodes::_iload_3: _jvm->ipush(_jvm->iload(3)); break;
      case Bytecodes::_iload: _jvm->ipush(_jvm->iload(_codes.get_index())); break;

      case Bytecodes::_lload_0: _jvm->lpush(_jvm->lload(0)); break;
      case Bytecodes::_lload_1: _jvm->lpush(_jvm->lload(1)); break;
      case Bytecodes::_lload_2: _jvm->lpush(_jvm->lload(2)); break;
      case Bytecodes::_lload_3: _jvm->lpush(_jvm->lload(3)); break;
      case Bytecodes::_lload: _jvm->lpush(_jvm->lload(_codes.get_index())); break;

      case Bytecodes::_fload_0: _jvm->fpush(_jvm->fload(0)); break;
      case Bytecodes::_fload_1: _jvm->fpush(_jvm->fload(1)); break;
      case Bytecodes::_fload_2: _jvm->fpush(_jvm->fload(2)); break;
      case Bytecodes::_fload_3: _jvm->fpush(_jvm->fload(3)); break;
      case Bytecodes::_fload: _jvm->fpush(_jvm->fload(_codes.get_index())); break;

      case Bytecodes::_dload_0: _jvm->dpush(_jvm->dload(0)); break;
      case Bytecodes::_dload_1: _jvm->dpush(_jvm->dload(1)); break;
      case Bytecodes::_dload_2: _jvm->dpush(_jvm->dload(2)); break;
      case Bytecodes::_dload_3: _jvm->dpush(_jvm->dload(3)); break;
      case Bytecodes::_dload: _jvm->dpush(_jvm->dload(_codes.get_index())); break;

      case Bytecodes::_aload_0: _jvm->apush(_jvm->aload(0)); break;
      case Bytecodes::_aload_1: _jvm->apush(_jvm->aload(1)); break;
      case Bytecodes::_aload_2: _jvm->apush(_jvm->aload(2)); break;
      case Bytecodes::_aload_3: _jvm->apush(_jvm->aload(3)); break;
      case Bytecodes::_aload: _jvm->apush(_jvm->aload(_codes.get_index())); break;

      case Bytecodes::_iaload: Unimplemented(); break;
      case Bytecodes::_laload: Unimplemented(); break;
      case Bytecodes::_faload: Unimplemented(); break;
      case Bytecodes::_daload: Unimplemented(); break;
      case Bytecodes::_aaload: Unimplemented(); break;
      case Bytecodes::_baload: Unimplemented(); break;
      case Bytecodes::_caload: Unimplemented(); break;
      case Bytecodes::_saload: Unimplemented(); break;

      // Stores:

      case Bytecodes::_istore_0: _jvm->istore(0, _jvm->ipop()); break;
      case Bytecodes::_istore_1: _jvm->istore(1, _jvm->ipop()); break;
      case Bytecodes::_istore_2: _jvm->istore(2, _jvm->ipop()); break;
      case Bytecodes::_istore_3: _jvm->istore(3, _jvm->ipop()); break;
      case Bytecodes::_istore: _jvm->istore(_codes.get_index(), _jvm->ipop()); break;

      case Bytecodes::_lstore_0: _jvm->lstore(0, _jvm->lpop()); break;
      case Bytecodes::_lstore_1: _jvm->lstore(1, _jvm->lpop()); break;
      case Bytecodes::_lstore_2: _jvm->lstore(2, _jvm->lpop()); break;
      case Bytecodes::_lstore_3: _jvm->lstore(3, _jvm->lpop()); break;
      case Bytecodes::_lstore: _jvm->lstore(_codes.get_index(), _jvm->lpop()); break;

      case Bytecodes::_fstore_0: _jvm->fstore(0, _jvm->fpop()); break;
      case Bytecodes::_fstore_1: _jvm->fstore(1, _jvm->fpop()); break;
      case Bytecodes::_fstore_2: _jvm->fstore(2, _jvm->fpop()); break;
      case Bytecodes::_fstore_3: _jvm->fstore(3, _jvm->fpop()); break;
      case Bytecodes::_fstore: _jvm->fstore(_codes.get_index(), _jvm->fpop()); break;

      case Bytecodes::_dstore_0: _jvm->dstore(0, _jvm->dpop()); break;
      case Bytecodes::_dstore_1: _jvm->dstore(1, _jvm->dpop()); break;
      case Bytecodes::_dstore_2: _jvm->dstore(2, _jvm->dpop()); break;
      case Bytecodes::_dstore_3: _jvm->dstore(3, _jvm->dpop()); break;
      case Bytecodes::_dstore: _jvm->dstore(_codes.get_index(), _jvm->dpop()); break;

      case Bytecodes::_astore_0: _jvm->astore(0, _jvm->apop()); break;
      case Bytecodes::_astore_1: _jvm->astore(1, _jvm->apop()); break;
      case Bytecodes::_astore_2: _jvm->astore(2, _jvm->apop()); break;
      case Bytecodes::_astore_3: _jvm->astore(3, _jvm->apop()); break;
      case Bytecodes::_astore: _jvm->astore(_codes.get_index(), _jvm->apop()); break;

      case Bytecodes::_iastore: Unimplemented(); break;
      case Bytecodes::_lastore: Unimplemented(); break;
      case Bytecodes::_fastore: Unimplemented(); break;
      case Bytecodes::_dastore: Unimplemented(); break;
      case Bytecodes::_aastore: Unimplemented(); break;
      case Bytecodes::_bastore: Unimplemented(); break;
      case Bytecodes::_castore: Unimplemented(); break;
      case Bytecodes::_sastore: Unimplemented(); break;

      // Stack:

      case Bytecodes::_pop:      // fall through
      case Bytecodes::_pop2:     // fall through
      case Bytecodes::_dup:      // fall through
      case Bytecodes::_dup_x1:   // fall through
      case Bytecodes::_dup_x2:   // fall through
      case Bytecodes::_dup2:     // fall through
      case Bytecodes::_dup2_x1:  // fall through
      case Bytecodes::_dup2_x2:  // fall through
      case Bytecodes::_swap: stack_op(code); break;

      // Math:

      case Bytecodes::_iadd: // fall through
      case Bytecodes::_isub: // fall through
      case Bytecodes::_imul: // fall through
      case Bytecodes::_idiv: // fall through
      case Bytecodes::_irem: // fall through
      case Bytecodes::_iand: // fall through
      case Bytecodes::_ior:  // fall through
      case Bytecodes::_ixor: // fall through
      case Bytecodes::_ineg: arith_op(BasicType::T_INT, code); break;
      case Bytecodes::_ishl:  // fall through
      case Bytecodes::_ishr:  // fall through
      case Bytecodes::_iushr: shift_op(BasicType::T_INT, code); break;
      case Bytecodes::_iinc: increment(); break;

      case Bytecodes::_ladd: // fall through
      case Bytecodes::_lsub: // fall through
      case Bytecodes::_lmul: // fall through
      case Bytecodes::_ldiv: // fall through
      case Bytecodes::_lrem: // fall through
      case Bytecodes::_land: // fall through
      case Bytecodes::_lor:  // fall through
      case Bytecodes::_lxor: // fall through
      case Bytecodes::_lneg: arith_op(BasicType::T_LONG, code); break;
      case Bytecodes::_lshl:  // fall through
      case Bytecodes::_lshr:  // fall through
      case Bytecodes::_lushr: shift_op(BasicType::T_LONG, code); break;

      case Bytecodes::_fadd: // fall through
      case Bytecodes::_fsub: // fall through
      case Bytecodes::_fmul: // fall through
      case Bytecodes::_fdiv: // fall through
      case Bytecodes::_frem: // fall through
      case Bytecodes::_fneg: arith_op(BasicType::T_FLOAT, code); break;

      case Bytecodes::_dadd: // fall through
      case Bytecodes::_dsub: // fall through
      case Bytecodes::_dmul: // fall through
      case Bytecodes::_ddiv: // fall through
      case Bytecodes::_drem: // fall through
      case Bytecodes::_dneg: arith_op(BasicType::T_DOUBLE, code); break;

      // Conversions:

      case Bytecodes::_i2l: _jvm->lpush(_ir_builder.CreateSExt(_jvm->ipop(), JeandleType::java2llvm(BasicType::T_LONG, *_context))); break;
      case Bytecodes::_i2f: _jvm->fpush(_ir_builder.CreateSIToFP(_jvm->ipop(), JeandleType::java2llvm(BasicType::T_FLOAT, *_context))); break;
      case Bytecodes::_i2d: _jvm->dpush(_ir_builder.CreateSIToFP(_jvm->ipop(), JeandleType::java2llvm(BasicType::T_DOUBLE, *_context))); break;
      case Bytecodes::_i2b: _jvm->ipush(_ir_builder.CreateSExt(_ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt8Ty(*_context)), JeandleType::java2llvm(BasicType::T_INT, *_context))); break;
      case Bytecodes::_i2c: _jvm->ipush(_ir_builder.CreateZExt(_ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt16Ty(*_context)), JeandleType::java2llvm(BasicType::T_INT, *_context))); break;
      case Bytecodes::_i2s: _jvm->ipush(_ir_builder.CreateSExt(_ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt16Ty(*_context)), JeandleType::java2llvm(BasicType::T_INT, *_context))); break;

      case Bytecodes::_l2i: _jvm->ipush(_ir_builder.CreateTrunc(_jvm->lpop(), JeandleType::java2llvm(BasicType::T_INT, *_context))); break;
      case Bytecodes::_l2f: _jvm->fpush(_ir_builder.CreateSIToFP(_jvm->lpop(), JeandleType::java2llvm(BasicType::T_FLOAT, *_context))); break;
      case Bytecodes::_l2d: _jvm->dpush(_ir_builder.CreateSIToFP(_jvm->lpop(), JeandleType::java2llvm(BasicType::T_DOUBLE, *_context))); break;

      case Bytecodes::_f2i: _jvm->ipush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_INT, *_context), llvm::Intrinsic::fptosi_sat, {_jvm->fpop()})); break;
      case Bytecodes::_f2l: _jvm->lpush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_LONG, *_context), llvm::Intrinsic::fptosi_sat, {_jvm->fpop()})); break;
      case Bytecodes::_f2d: _jvm->dpush(_ir_builder.CreateFPExt(_jvm->fpop(), JeandleType::java2llvm(BasicType::T_DOUBLE, *_context))); break;

      case Bytecodes::_d2i: _jvm->ipush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_INT, *_context), llvm::Intrinsic::fptosi_sat, {_jvm->dpop()})); break;
      case Bytecodes::_d2l: _jvm->lpush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_LONG, *_context), llvm::Intrinsic::fptosi_sat, {_jvm->dpop()})); break;
      case Bytecodes::_d2f: _jvm->fpush(_ir_builder.CreateFPTrunc(_jvm->dpop(), JeandleType::java2llvm(BasicType::T_FLOAT, *_context))); break;

      // Comparisons:

      case Bytecodes::_ifeq: if_zero(llvm::CmpInst::ICMP_EQ); break;
      case Bytecodes::_ifne: if_zero(llvm::CmpInst::ICMP_NE); break;
      case Bytecodes::_iflt: if_zero(llvm::CmpInst::ICMP_SLT); break;
      case Bytecodes::_ifge: if_zero(llvm::CmpInst::ICMP_SGE); break;
      case Bytecodes::_ifgt: if_zero(llvm::CmpInst::ICMP_SGT); break;
      case Bytecodes::_ifle: if_zero(llvm::CmpInst::ICMP_SLE); break;

      case Bytecodes::_if_icmpeq: if_icmp(llvm::CmpInst::ICMP_EQ); break;
      case Bytecodes::_if_icmpne: if_icmp(llvm::CmpInst::ICMP_NE); break;
      case Bytecodes::_if_icmplt: if_icmp(llvm::CmpInst::ICMP_SLT); break;
      case Bytecodes::_if_icmpgt: if_icmp(llvm::CmpInst::ICMP_SGT); break;
      case Bytecodes::_if_icmpge: if_icmp(llvm::CmpInst::ICMP_SGE); break;
      case Bytecodes::_if_icmple: if_icmp(llvm::CmpInst::ICMP_SLE); break;

      case Bytecodes::_lcmp: lcmp(); break;

      case Bytecodes::_fcmpl: fcmp(T_FLOAT, false); break;
      case Bytecodes::_fcmpg: fcmp(T_FLOAT, true); break;

      case Bytecodes::_dcmpl: fcmp(T_DOUBLE, false); break;
      case Bytecodes::_dcmpg: fcmp(T_DOUBLE, true); break;

      case Bytecodes::_if_acmpeq: if_acmp(llvm::CmpInst::ICMP_EQ); break;
      case Bytecodes::_if_acmpne: if_acmp(llvm::CmpInst::ICMP_NE); break;

      // Control:

      case Bytecodes::_goto: goto_bci(_codes.get_dest()); break;
      case Bytecodes::_jsr: Unimplemented(); break;
      case Bytecodes::_ret: Unimplemented(); break;

      case Bytecodes::_tableswitch: Unimplemented(); break;
      case Bytecodes::_lookupswitch: lookup_switch(); break;

      case Bytecodes::_ireturn: _ir_builder.CreateRet(_jvm->ipop()); break;
      case Bytecodes::_lreturn: _ir_builder.CreateRet(_jvm->lpop()); break;
      case Bytecodes::_freturn: _ir_builder.CreateRet(_jvm->fpop()); break;
      case Bytecodes::_dreturn: _ir_builder.CreateRet(_jvm->dpop()); break;
      case Bytecodes::_areturn: _ir_builder.CreateRet(_jvm->apop()); break;
      case Bytecodes::_return: _ir_builder.CreateRetVoid(); break;

      // References:

      case Bytecodes::_getstatic: do_getstatic(); break;
      case Bytecodes::_putstatic: do_putstatic(); break;

      case Bytecodes::_getfield: do_getfield(); break;
      case Bytecodes::_putfield: do_putfield(); break;

      case Bytecodes::_invokevirtual:    // fall through
      case Bytecodes::_invokespecial:    // fall through
      case Bytecodes::_invokestatic:     // fall through
      case Bytecodes::_invokeinterface:  // fall through
      case Bytecodes::_invokedynamic: invoke(); break;

      case Bytecodes::_new: Unimplemented(); break;
      case Bytecodes::_newarray: Unimplemented(); break;
      case Bytecodes::_anewarray: Unimplemented(); break;

      case Bytecodes::_arraylength: Unimplemented(); break;
      case Bytecodes::_athrow: Unimplemented(); break;
      case Bytecodes::_checkcast: Unimplemented(); break;
      case Bytecodes::_instanceof: instanceof(_codes.get_index_u2()); break;

      case Bytecodes::_monitorenter: Unimplemented(); break;
      case Bytecodes::_monitorexit: Unimplemented(); break;

      // Extended:

      case Bytecodes::_wide: ShouldNotReachHere();

      case Bytecodes::_multianewarray: Unimplemented(); break;

      case Bytecodes::_ifnull: if_null(llvm::CmpInst::ICMP_EQ); break;
      case Bytecodes::_ifnonnull: if_null(llvm::CmpInst::ICMP_NE); break;

      case Bytecodes::_goto_w: Unimplemented(); break;
      case Bytecodes::_jsr_w: Unimplemented(); break;

      // Reserved:

      case Bytecodes::_breakpoint: Unimplemented(); break;

      default: {
        tty->print_cr("Unhandled bytecode %s", Bytecodes::name(code));
        ShouldNotReachHere();
      }
    }
  }

  // All blocks should has their terminator.
  if (block->llvm_block()->getTerminator() == nullptr) {
    _ir_builder.CreateBr(bci2block()[_codes.cur_bci()]->llvm_block());
  }

  block->set(JeandleBasicBlock::is_compiled);

  // Add all successors to work list and set up their JeandleVMStates.
  for (JeandleBasicBlock* suc : block->successors()) {
    if (!suc->income_block(block, _method, &_ir_builder)) {
      JeandleCompilation::report_jeandle_error("failed to create phi nodes");
      return;
    }

    if (!suc->is_set(JeandleBasicBlock::is_compiled)) {
      add_to_work_list(suc);
    }
  }
}

void JeandleAbstractInterpreter::add_to_work_list(JeandleBasicBlock* block) {
  if (!block->is_set(JeandleBasicBlock::is_on_work_list)) {
    block->set(JeandleBasicBlock::is_on_work_list);
    _work_list.push_back(block);

    // Sort blocks by their reverse-post-order.
    int rpo = block->reverse_post_order();
    int i = _work_list.size() - 2;
    while (i >= 0) {
      JeandleBasicBlock* cur = _work_list[i];
      if (cur->reverse_post_order() < rpo) {
        _work_list[i + 1] = cur;
      } else {
        break;
      }
      i--;
    }
    _work_list[i + 1] = block;
  }
}

void JeandleAbstractInterpreter::load_constant() {
  ciConstant con = _codes.get_constant();
  llvm::Value* value = nullptr;

  switch (con.basic_type()) {
    case BasicType::T_INT: value = JeandleType::int_const(_ir_builder, con.as_int()); break;
    case BasicType::T_LONG: value = JeandleType::long_const(_ir_builder, con.as_long()); break;
    case BasicType::T_FLOAT: value = JeandleType::float_const(_ir_builder, con.as_float()); break;
    case BasicType::T_DOUBLE: value = JeandleType::double_const(_ir_builder, con.as_double()); break;
    default: Unimplemented(); break;
  }

  _jvm->push(con.basic_type(), value);
}

void JeandleAbstractInterpreter::increment() {
  llvm::Value* con = JeandleType::int_const(_ir_builder, _codes.get_iinc_con());
  llvm::Value* result = _ir_builder.CreateAdd(_jvm->iload(_codes.get_index()), con);
  _jvm->istore(_codes.get_index(), result);
}

void JeandleAbstractInterpreter::if_zero(llvm::CmpInst::Predicate p) {
  llvm::Value* v = _jvm->ipop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, v, JeandleType::int_const(_ir_builder, 0));
  _ir_builder.CreateCondBr(cond, bci2block()[_codes.get_dest()]->llvm_block(), bci2block()[_codes.next_bci()]->llvm_block());
}

void JeandleAbstractInterpreter::if_icmp(llvm::CmpInst::Predicate p) {
  llvm::Value* r = _jvm->ipop();
  llvm::Value* l = _jvm->ipop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, l, r);
  _ir_builder.CreateCondBr(cond, bci2block()[_codes.get_dest()]->llvm_block(), bci2block()[_codes.next_bci()]->llvm_block());
}

void JeandleAbstractInterpreter::lcmp() {
  llvm::Value* r = _jvm->lpop();
  llvm::Value* l = _jvm->lpop();
  llvm::Value* ne_cmp = _ir_builder.CreateICmpNE(l, r);
  ne_cmp = _ir_builder.CreateZExt(ne_cmp, JeandleType::java2llvm(BasicType::T_INT, *_context));
  llvm::Value* lt_cmp = _ir_builder.CreateICmpSLT(l, r);
  llvm::Value* less_than = JeandleType::int_const(_ir_builder, -1);
  _jvm->ipush(_ir_builder.CreateSelect(lt_cmp, less_than, ne_cmp));
}

void JeandleAbstractInterpreter::if_acmp(llvm::CmpInst::Predicate p) {
  llvm::Value* r = _jvm->apop();
  llvm::Value* l = _jvm->apop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, l, r);
  _ir_builder.CreateCondBr(cond, bci2block()[_codes.get_dest()]->llvm_block(), bci2block()[_codes.next_bci()]->llvm_block());
}

void JeandleAbstractInterpreter::if_null(llvm::CmpInst::Predicate p) {
  llvm::Value* v = _jvm->apop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, v, llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(v->getType())));
  _ir_builder.CreateCondBr(cond, bci2block()[_codes.get_dest()]->llvm_block(), bci2block()[_codes.next_bci()]->llvm_block());
}

/*
 *  U  L  G  E  Inst         Flag
 * ---------------------------------------------------
 *  1 -1  1  0  fcmpg,dcmpg  true_if_unordered = true
 * -1 -1  1  0  fcmpl,dcmpl  true_if_unordered = false
 */
void JeandleAbstractInterpreter::fcmp(BasicType type, bool true_if_unordered) {
  assert(type == BasicType::T_FLOAT || type == BasicType::T_DOUBLE, "type must be float or double");
  llvm::Value* r = (type == BasicType::T_FLOAT) ? _jvm->fpop() : _jvm->dpop();
  llvm::Value* l = (type == BasicType::T_FLOAT) ? _jvm->fpop() : _jvm->dpop();

  llvm::Value* negative_case = nullptr;
  llvm::Value* non_negative_case = nullptr;
  if (true_if_unordered) {
    negative_case     = _ir_builder.CreateFCmpOLT(l, r);
    non_negative_case = _ir_builder.CreateFCmpUGT(l, r);
  } else {
    negative_case     = _ir_builder.CreateFCmpULT(l, r);
    non_negative_case = _ir_builder.CreateFCmpOGT(l, r);
  }

  non_negative_case = _ir_builder.CreateZExt(non_negative_case, JeandleType::java2llvm(BasicType::T_INT, *_context));
  _jvm->ipush(_ir_builder.CreateSelect(negative_case, JeandleType::int_const(_ir_builder, -1), non_negative_case));
}

void JeandleAbstractInterpreter::goto_bci(int bci) {
  if (bci < _codes.cur_bci()) {
    add_safepoint_poll();
  }
  _ir_builder.CreateBr(bci2block()[bci]->llvm_block());
}

void JeandleAbstractInterpreter::lookup_switch() {
  Bytecode_lookupswitch sw(&_codes);

  int length = sw.number_of_pairs();
  int cur_bci = _codes.cur_bci();

  llvm::Value* key = _jvm->ipop();
  llvm::BasicBlock* default_block = bci2block()[cur_bci + sw.default_offset()]->llvm_block();
  llvm::SwitchInst* switch_inst = _ir_builder.CreateSwitch(key, default_block, length);

  for (int i = 0; i < length; i++) {
    LookupswitchPair pair = sw.pair_at(i);
    switch_inst->addCase(JeandleType::int_const(_ir_builder, pair.match()), bci2block()[cur_bci + pair.offset()]->llvm_block());
  }
}

// Generate call instructions.
// TODO: Reciever's null check.
void JeandleAbstractInterpreter::invoke() {
  bool will_link;
  ciSignature* declared_signature = nullptr;
  ciMethod* target = _codes.get_method(will_link, &declared_signature);
  assert(declared_signature != nullptr, "cannot be null");
  assert(will_link == target->is_loaded(), "");

  const Bytecodes::Code bc = _codes.cur_bc();

  // Construct arguments.
  const int reciever =
    bc == Bytecodes::_invokespecial   ||
    bc == Bytecodes::_invokevirtual   ||
    bc == Bytecodes::_invokeinterface;
  const int arg_size = declared_signature->count() + reciever;
  std::vector<llvm::Value*> args(arg_size);
  std::vector<llvm::Type*> args_type(arg_size);
  for (int i = declared_signature->count() - 1; i >= 0; --i) {
    BasicType type = declared_signature->type_at(i)->basic_type();
    args[i + reciever] = _jvm->pop(type);
    args_type[i + reciever] = JeandleType::java2llvm(type, *_context);
  }
  if (reciever) {
    args[0] = _jvm->pop(BasicType::T_OBJECT);
    args_type[0] = JeandleType::java2llvm(BasicType::T_OBJECT, *_context);
  }

  // Declare callee function type.
  BasicType return_type = declared_signature->return_type()->basic_type();
  llvm::FunctionCallee callee = JeandleJavaCall::callee(_module, target, JeandleType::java2llvm(return_type, *_context), args_type);

  // Decide call type and detination.
  JeandleJavaCall::Type call_type = JeandleJavaCall::Type::NOT_A_CALL;
  address dest = nullptr;
  switch (bc) {
    case Bytecodes::_invokevirtual:  // fall through
    case Bytecodes::_invokeinterface: {
      call_type = JeandleJavaCall::Type::DYNAMIC_CALL;
      dest = SharedRuntime::get_resolve_virtual_call_stub();
      break;
    }
    case Bytecodes::_invokestatic: {
      call_type = JeandleJavaCall::Type::STATIC_CALL;
      dest = SharedRuntime::get_resolve_static_call_stub();
      break;
    }
    case Bytecodes::_invokedynamic: Unimplemented(); break;
    case Bytecodes::_invokespecial: Unimplemented(); break;
    default: ShouldNotReachHere();
  }

  assert(call_type != JeandleJavaCall::Type::NOT_A_CALL, "legal call type");
  assert(dest != nullptr, "legal destination");

  // Record this call.
  uint32_t id = next_statepoint_id();
  _code.call_sites()[id] = new CallSiteInfo(id, call_type, dest, _codes.cur_bci());

  // Create the call instruction.
  llvm::CallInst* call = _ir_builder.CreateCall(callee, args);

  // Apply attributes and calling convention.
  call->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  llvm::Attribute id_attr = llvm::Attribute::get(*_context,
                                                 llvm::jeandle::Attribute::StatepointID,
                                                 std::to_string(id));
  llvm::Attribute patch_bytes_attr = llvm::Attribute::get(*_context,
                                                 llvm::jeandle::Attribute::StatepointNumPatchBytes,
                                                 std::to_string(JeandleJavaCall::call_site_size(call_type)));
  call->addFnAttr(id_attr);
  call->addFnAttr(patch_bytes_attr);

  if (return_type != BasicType::T_VOID) {
    _jvm->push(return_type, call);
  }
}

void JeandleAbstractInterpreter::stack_op(Bytecodes::Code code) {
  switch (code) {
    case Bytecodes::_pop: {
      _jvm->raw_pop();
      break;
    }
    case Bytecodes::_pop2: {
      _jvm->raw_pop();
      _jvm->raw_pop();
      break;
    }
    case Bytecodes::_dup: {
      llvm::Value* value = _jvm->raw_pop();
      _jvm->raw_push(value);
      _jvm->raw_push(value);
      break;
    }
    case Bytecodes::_dup_x1: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      _jvm->raw_push(value1);
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      break;
    }
    case Bytecodes::_dup_x2: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      llvm::Value* value3 = _jvm->raw_pop();
      _jvm->raw_push(value1);
      _jvm->raw_push(value3);
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      break;
    }
    case Bytecodes::_dup2: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      break;
    }
    case Bytecodes::_dup2_x1: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      llvm::Value* value3 = _jvm->raw_pop();
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      _jvm->raw_push(value3);
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      break;
    }
    case Bytecodes::_dup2_x2: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      llvm::Value* value3 = _jvm->raw_pop();
      llvm::Value* value4 = _jvm->raw_pop();
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      _jvm->raw_push(value4);
      _jvm->raw_push(value3);
      _jvm->raw_push(value2);
      _jvm->raw_push(value1);
      break;
    }
    case Bytecodes::_swap: {
      llvm::Value* value1 = _jvm->raw_pop();
      llvm::Value* value2 = _jvm->raw_pop();
      _jvm->raw_push(value1);
      _jvm->raw_push(value2);
      break;
    }
    default: ShouldNotReachHere();
  }
}

void JeandleAbstractInterpreter::shift_op(BasicType type, Bytecodes::Code code) {
  switch (type) {
    case BasicType::T_INT: {
      llvm::Value* amount = _ir_builder.CreateAnd(_jvm->ipop(), _ir_builder.getInt32(0x1F));
      llvm::Value* operand = _jvm->ipop();
      switch (code) {
        case Bytecodes::_ishl: _jvm->ipush(_ir_builder.CreateShl(operand, amount)); break;
        case Bytecodes::_ishr: _jvm->ipush(_ir_builder.CreateAShr(operand, amount)); break;
        case Bytecodes::_iushr: _jvm->ipush(_ir_builder.CreateLShr(operand, amount)); break;
        default: ShouldNotReachHere();
      }
      break;
    }
    case BasicType::T_LONG: {
      llvm::Value* amount = _ir_builder.CreateZExt(_ir_builder.CreateAnd(_jvm->ipop(),
                                                   _ir_builder.getInt32(0x3F)),
                                                   JeandleType::java2llvm(BasicType::T_LONG, *_context));
      llvm::Value* operand = _jvm->lpop();
      switch (code) {
        case Bytecodes::_lshl: _jvm->lpush(_ir_builder.CreateShl(operand, amount)); break;
        case Bytecodes::_lshr: _jvm->lpush(_ir_builder.CreateAShr(operand, amount)); break;
        case Bytecodes::_lushr: _jvm->lpush(_ir_builder.CreateLShr(operand, amount)); break;
        default: ShouldNotReachHere();
      }
      break;
    }
    default: ShouldNotReachHere();
  }
}

void JeandleAbstractInterpreter::instanceof(int klass_index) {
  llvm::Value* obj = _jvm->apop();

  // TODO: check klass's loading state.

  llvm::Value* index_value = _ir_builder.getInt32(klass_index);
  llvm::CallInst* call = call_java_op("jeandle.instanceof", {index_value, obj});

  _jvm->ipush(call);
}

void JeandleAbstractInterpreter::arith_op(BasicType type, Bytecodes::Code code) {
  assert(type == BasicType::T_INT || type == BasicType::T_LONG ||
         type == BasicType::T_FLOAT || type == BasicType::T_DOUBLE, "unexpected type");

  llvm::Value* r = _jvm->pop(type);
  llvm::Value* l = nullptr;

  if (!(code == Bytecodes::_ineg || code == Bytecodes::_lneg ||
      code == Bytecodes::_fneg || code == Bytecodes::_dneg)) {
    l = _jvm->pop(type);
  }

  switch (code) {
    // Integral
    case Bytecodes::_iadd: // fall through
    case Bytecodes::_ladd: _jvm->push(type, _ir_builder.CreateAdd(l, r)); break;
    case Bytecodes::_isub: // fall through
    case Bytecodes::_lsub: _jvm->push(type, _ir_builder.CreateSub(l, r)); break;
    case Bytecodes::_imul: // fall through
    case Bytecodes::_lmul: _jvm->push(type, _ir_builder.CreateMul(l, r)); break;
    case Bytecodes::_idiv: // fall through
    case Bytecodes::_ldiv: _jvm->push(type, _ir_builder.CreateSDiv(l, r)); break;
    case Bytecodes::_irem: // fall through
    case Bytecodes::_lrem: _jvm->push(type, _ir_builder.CreateSRem(l, r)); break;
    case Bytecodes::_iand: // fall through
    case Bytecodes::_land: _jvm->push(type, _ir_builder.CreateAnd(l, r)); break;
    case Bytecodes::_ior:  // fall through
    case Bytecodes::_lor:  _jvm->push(type, _ir_builder.CreateOr(l, r)); break;
    case Bytecodes::_ixor: // fall through
    case Bytecodes::_lxor: _jvm->push(type, _ir_builder.CreateXor(l, r)); break;
    case Bytecodes::_ineg: // fall through
    case Bytecodes::_lneg: {
      assert(l == nullptr, "only one operand for negation");
      _jvm->push(type, _ir_builder.CreateNeg(r));
      break;
    }
    // Floating-Point
    case Bytecodes::_fadd: // fall through
    case Bytecodes::_dadd: _jvm->push(type, _ir_builder.CreateFAdd(l, r)); break;
    case Bytecodes::_fsub: // fall through
    case Bytecodes::_dsub: Unimplemented(); break;
    case Bytecodes::_fmul: // fall through
    case Bytecodes::_dmul: _jvm->push(type, _ir_builder.CreateFMul(l, r)); break;
    case Bytecodes::_fdiv: // fall through
    case Bytecodes::_ddiv: Unimplemented(); break;
    case Bytecodes::_frem: // fall through
    case Bytecodes::_drem: Unimplemented(); break;
    case Bytecodes::_fneg: // fall through
    case Bytecodes::_dneg: {
      assert(l == nullptr, "only one operand for negation");
      _jvm->push(type, _ir_builder.CreateFNeg(r));
      break;
    }
    default: ShouldNotReachHere();
  }
}

// TODO: clinit_barrier check.
// TODO: Handle field attributions like volatile, final, stable.
void JeandleAbstractInterpreter::do_field_access(bool is_get, bool is_static) {
  bool will_link;
  ciField* field = _codes.get_field(will_link);
  // TODO: Handle invalid fields.
  if (!will_link)
    Unimplemented();

  ciInstanceKlass* field_holder = field->holder();
  if (is_get && field->is_call_site_target() &&
      (!(_method->holder() == field_holder && _method->is_object_initializer()))) {
        // TODO: Uncommon trap.
        Unimplemented();
        return;
      }

  if (is_get) {
    do_get_xxx(field, is_static);
  } else {
    do_put_xxx(field, is_static);
  }
}

void JeandleAbstractInterpreter::do_get_xxx(ciField* field, bool is_static) {
  int offset = field->offset_in_bytes();
  llvm::Value* addr = nullptr;

  if (is_static) {
    addr = compute_static_field_address(field->holder(), offset);
  } else {
    addr = compute_instance_field_address(_jvm->apop(), offset);
  }

  llvm::Value* value = load_from_address(addr, field->layout_type());
  _jvm->push(field->type()->basic_type(), value);
}

void JeandleAbstractInterpreter::do_put_xxx(ciField* field, bool is_static) {
  int offset = field->offset_in_bytes();
  llvm::Value* addr = nullptr;

  llvm::Value* value = _jvm->pop(field->type()->basic_type());

  if (is_static) {
    addr = compute_static_field_address(field->holder(), offset);
  } else {
    addr = compute_instance_field_address(_jvm->apop(), offset);
  }

  store_to_address(addr, value, field->layout_type());
}

llvm::Value* JeandleAbstractInterpreter::compute_instance_field_address(llvm::Value* obj, int offset) {
  return _ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(*_context), obj,
                                       _ir_builder.getInt64(offset));
}

llvm::Value* JeandleAbstractInterpreter::compute_static_field_address(ciInstanceKlass* holder, int offset) {
  ciInstance* holder_instance = holder->java_mirror();
  llvm::Value* holder_oop = find_or_insert_oop(holder_instance);
  return _ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(*_context),
                                       holder_oop,
                                       _ir_builder.getInt64(offset));
}

llvm::Value* JeandleAbstractInterpreter::load_from_address(llvm::Value* addr, BasicType type) {
  llvm::Type* expected_ty = JeandleType::java2llvm(type, *_context);
  return _ir_builder.CreateLoad(expected_ty, addr);
}

void JeandleAbstractInterpreter::store_to_address(llvm::Value* addr, llvm::Value* value, BasicType type) {
  llvm::Type* expected_ty = JeandleType::java2llvm(type, *_context);
  assert(value->getType() == expected_ty, "Value type must match field type");

  llvm::StoreInst* store = _ir_builder.CreateStore(value, addr);
}

llvm::Value* JeandleAbstractInterpreter::find_or_insert_oop(ciObject* oop) {
  jobject oop_handle = oop->constant_encoding();
  if (llvm::Value* oop_value = _oops.lookup(oop_handle)) {
    return oop_value;
  }
  llvm::StringRef oop_name = next_oop_name();
  _code.oop_handles()[oop_name] = oop_handle;
  llvm::Value* oop_value = _module.getOrInsertGlobal(
                               oop_name,
                               JeandleType::java2llvm(BasicType::T_OBJECT, *_context));
  _oops[oop_handle] = oop_value;
  return oop_value;
}

llvm::CallInst* JeandleAbstractInterpreter::call_java_op(llvm::StringRef java_op, llvm::ArrayRef<llvm::Value*> args) {
  llvm::Function* java_op_func = _module.getFunction(java_op);
  assert(java_op_func != nullptr, "invalid JavaOp");
  llvm::CallInst* call_inst = _ir_builder.CreateCall(java_op_func, args);
  call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  return call_inst;
}

void JeandleAbstractInterpreter::add_safepoint_poll() {
  call_java_op("jeandle.safepoint_poll", {});
}
