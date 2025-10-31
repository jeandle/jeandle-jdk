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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Attributes.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/Jeandle/Metadata.h"


#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethodBlocks.hpp"
#include "ci/ciSymbols.hpp"
#include "logging/log.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "utilities/ostream.hpp"

JeandleVMState::JeandleVMState(int max_stack, int max_locals, llvm::LLVMContext *context) :
                               _stack(), _locals(max_locals), _locks(), _context(context) {
  _stack.reserve(max_stack);
}

JeandleVMState::JeandleVMState(JeandleVMState* copy_from, bool clear_stack) :
                               _stack(),
                               _locals(copy_from->_locals),
                               _locks(copy_from->_locks),
                               _context(copy_from->_context) {
  _stack.reserve(copy_from->_stack.capacity());
  if (!clear_stack) {
    _stack.append(copy_from->_stack);
  }
}

JeandleVMState* JeandleVMState::copy(bool clear_stack) {
  JeandleVMState* copied =  new JeandleVMState(this, clear_stack);
  return copied;
}

JeandleVMState* JeandleVMState::copy_for_exception_handler(llvm::Value* exception_oop) {
  JeandleVMState* copied = copy(true);
  copied->apush(exception_oop);
  return copied;
}

bool JeandleVMState::match(JeandleVMState* to_match) {
  if (_locals.size() != to_match->_locals.size()) {
    return false;
  }

  if (_stack.size() != to_match->_stack.size()) {
    return false;
  }

  for (size_t i = 0; i < _stack.size(); i++) {
    if (_stack[i] == nullptr) {
      if (to_match->_stack[i] != nullptr) {
        return false;
      }
      continue;
    }

    if (to_match->_stack[i] == nullptr) {
      return false;
    }

    // For call instructions, getType() returns the return type.
    if (_stack[i]->getType() != to_match->_stack[i]->getType()) {
      return false;
    }
  }

  return true;
}

bool JeandleVMState::update_phi_nodes(JeandleVMState* income_jvm, llvm::BasicBlock* income_block) {
  if (!match(income_jvm)) {
    return false;
  }

  llvm::SmallVector<llvm::Value*>& income_locals = income_jvm->_locals;
  llvm::SmallVector<llvm::Value*>& income_stack = income_jvm->_stack;

  // Create phi nodes for locals.
  for (size_t i = 0; i < _locals.size(); i++) {
    if (_locals[i] == nullptr) {
      continue;
    }

    llvm::PHINode* phi_node = llvm::cast<llvm::PHINode>(_locals[i]);

    if (income_locals[i] == nullptr || phi_node->getType() != income_locals[i]->getType()) {
      invalidate_local(i);
      continue;
    }

    phi_node->addIncoming(income_locals[i], income_block);
  }

  // Create phi nodes for stack.
  for (size_t i = 0; i < _stack.size(); i++) {
    if (_stack[i] == nullptr) {
      continue;
    }

    llvm::PHINode* phi_node = llvm::cast<llvm::PHINode>(_stack[i]);

    phi_node->addIncoming(income_stack[i], income_block);
  }

  return true;
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

JeandleBasicBlock::JeandleBasicBlock(int block_id,
                                     int start_bci,
                                     int limit_bci,
                                     llvm::BasicBlock* header_llvm_block,
                                     ciBlock* ci_block) :
                                     _block_id(block_id),
                                     _flags(no_flag),
                                     _start_bci(start_bci),
                                     _limit_bci(limit_bci),
                                     _reverse_post_order(-1),
                                     _jvm(nullptr),
                                     _predecessors(),
                                     _successors(),
                                     _header_llvm_block(header_llvm_block),
                                     _tail_llvm_block(header_llvm_block),
                                     _ci_block(ci_block),
                                     _initial_jvm(nullptr) {}

bool JeandleBasicBlock::merge_VM_state_from(JeandleVMState* vm_state, llvm::BasicBlock* incoming, ciMethod* method) {
  if (_jvm == nullptr) {
    if (is_set(is_compiled)) {
      // A compiled block with null JeandleVMState.
      return false;
    }

    if (_predecessors.size() == 1) {
      // Just one predecessor. Copy its JeandleVMState.
      assert(!is_set(is_loop_header), "should not be a loop header");
      _jvm = vm_state->copy();
    } else {
      // More than one predecessors. Set up phi nodes.
      initialize_VM_state_from(vm_state, incoming, method->liveness_at_bci(_start_bci));
    }

    if (is_set(is_loop_header)) {
      // Copy loop header's initial JeandleVMState.
      _initial_jvm = _jvm->copy();
    }

    return true;

  } else if (!is_set(is_compiled) && !is_set(is_loop_header)) {
    assert(_predecessors.size() > 1, "more than one predecessors are needed for phi nodes");
    return _jvm->update_phi_nodes(vm_state, incoming);
  } else if (is_set(is_loop_header)) {
    assert(_initial_jvm != nullptr, "loop header initial JeandleVMState is needed");
    return _initial_jvm->update_phi_nodes(vm_state, incoming);
  }

  // Bad bytecodes.
  return false;
}

void JeandleBasicBlock::initialize_VM_state_from(JeandleVMState* incoming_state, llvm::BasicBlock* incoming_block, MethodLivenessResult liveness) {
  assert(_jvm == nullptr, "cannot initialize twice");

  llvm::IRBuilder<> ir_builder(_header_llvm_block);

  _jvm = new JeandleVMState(incoming_state->max_stack(), incoming_state->max_locals(), &ir_builder.getContext());

  for (size_t i = 0; i < incoming_state->locks_size(); i++) {
    llvm::Value* lock = incoming_state->lock_at(i);
    assert(lock != nullptr, "null lock");
    _jvm->push_lock(lock);
  }

  for (size_t i = 0; i < incoming_state->locals_size(); i++) {
    if (incoming_state->locals_at(i) == nullptr) {
      continue;
    }

    // Use method liveness to invalidate dead locals.
    if (liveness.is_valid() && !liveness.at(i)) {
      continue;
    }

    llvm::PHINode* phi_node = ir_builder.CreatePHI(incoming_state->locals_at(i)->getType(), 2);
    phi_node->addIncoming(incoming_state->locals_at(i), incoming_block);
    _jvm->set_locals_at(i, phi_node);
  }

  for (size_t i = 0; i < incoming_state->stack_size(); i++) {
    if (incoming_state->stack_at(i) == nullptr) {
      _jvm->raw_push(nullptr);
      continue;
    }

    llvm::PHINode* phi_node = ir_builder.CreatePHI(incoming_state->stack_at(i)->getType(), 2);
    phi_node->addIncoming(incoming_state->stack_at(i), incoming_block);
    _jvm->raw_push(phi_node);
  }
}

BasicBlockBuilder::BasicBlockBuilder(ciMethod* method,
                                     llvm::LLVMContext* context,
                                     llvm::Function* llvm_func) :
                                     _bci2block(method->code_size()),
                                     _method(method),
                                     _ci_blocks(_method->get_method_blocks()),
                                     _context(context),
                                     _llvm_func(llvm_func),
                                     _entry_block(new JeandleBasicBlock(-1, -1, -1, llvm::BasicBlock::Create(*_context, "entry", _llvm_func), nullptr)),
                                     _active(),
                                     _visited(),
                                     _next_block_order(-1) {
  generate_blocks();
  setup_exception_handlers();
  setup_control_flow();
  mark_loops();
}

void BasicBlockBuilder::generate_blocks() {
  // Create all basic blocks according to ciMethodBlocks.
  ciBytecodeStream codes(_method);
  JeandleBasicBlock* current = nullptr;
  while (codes.next() != ciBytecodeStream::EOBC()) {
    int bci = codes.cur_bci();
    if (_ci_blocks->is_block_start(bci)) {
      // Current position starts a new basic block.
      ciBlock* block = _ci_blocks->block_containing(bci);
      assert(block != nullptr, "must be valid basic block");
      current = new JeandleBasicBlock(block->index(),
                                      bci,
                                      block->limit_bci(),
                                      llvm::BasicBlock::Create(*_context, "bci_" + std::to_string(bci), _llvm_func),
                                      block);
      _bci2block[bci] = current;
    } else {
      // Current position is a part of the previous basic block.
      assert(bci > 0, "bci 0 must be the start of a basic block");
      _bci2block[bci] = current;
    }
  }
#ifdef ASSERT
  // Do we have a basic block for each bci now?
  codes.reset_to_bci(0);
  while (codes.next() != ciBytecodeStream::EOBC()) {
    int bci = codes.cur_bci();
    assert(_bci2block[bci] != nullptr, "invalid basic block");
  }
#endif // ASSERT
}

void BasicBlockBuilder::setup_exception_handlers() {
  // Connect all basic blocks according to exception handling information.
  ciBytecodeStream codes(_method);
  while (codes.next() != ciBytecodeStream::EOBC()) {
    int bci = codes.cur_bci();
    JeandleBasicBlock* block = _bci2block[bci];
    if (block->is_exception_handler()) {
      int covered_bci = block->exeption_range_start_bci();
      while (covered_bci < block->exeption_range_limit_bci()) {
        connect_block(block, _bci2block[covered_bci]);
        covered_bci = _bci2block[covered_bci]->limit_bci(); // Jump to the next block.
      }
    }
  }
}

void BasicBlockBuilder::setup_control_flow() {
  // Connect all basic blocks according to control flow transfer instructions.
  ciBytecodeStream codes(_method);

  JeandleBasicBlock* current = _entry_block;
  int limit_bci = _method->code_size();

  while (codes.next() != ciBytecodeStream::EOBC()) {
    int cur_bci = codes.cur_bci();

    if (_ci_blocks->is_block_start(cur_bci)) {
      if (current != nullptr) {
        connect_block(_bci2block[cur_bci], current);
      }
      current = _bci2block[cur_bci];
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
        if (codes.next_bci() < limit_bci) {
          connect_block(_bci2block[codes.next_bci()], current);
        }
        connect_block(_bci2block[codes.get_dest()], current);
        current = nullptr;
        break;

      case Bytecodes::_goto:
        connect_block(_bci2block[codes.get_dest()], current);
        current = nullptr;
        break;

      case Bytecodes::_goto_w:
        connect_block(_bci2block[codes.get_far_dest()], current);
        current = nullptr;
        break;

      case Bytecodes::_lookupswitch: {
        // Set block for each case.
        Bytecode_lookupswitch sw(&codes);
        int length = sw.number_of_pairs();
        for (int i = 0; i < length; i++) {
          connect_block(_bci2block[cur_bci + sw.pair_at(i).offset()], current);
        }
        connect_block(_bci2block[cur_bci + sw.default_offset()], current);
        current = nullptr;
        break;
      }

      case Bytecodes::_tableswitch: {
        // Set block for each case.
        Bytecode_tableswitch sw(&codes);
        int length = sw.length();
        for (int i = 0; i < length; i++) {
          connect_block(_bci2block[cur_bci + sw.dest_offset_at(i)], current);
        }
        connect_block(_bci2block[cur_bci + sw.default_offset()], current);
        current = nullptr;
        break;
      }

      default:
        break;
    }
  }
}

void BasicBlockBuilder::mark_loops() {
  ResourceMark rm;

  int num_blocks = _ci_blocks->num_blocks();

  _active.initialize(num_blocks);
  _visited.initialize(num_blocks);
  _next_block_order = num_blocks - 1;

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

JeandleAbstractInterpreter::JeandleAbstractInterpreter(ciMethod* method,
                                                       int entry_bci,
                                                       llvm::Module& target_module,
                                                       JeandleCompiledCode& code) :
                                                       _method(method),
                                                       _llvm_func(JeandleFuncSig::create_llvm_func(method, target_module)),
                                                       _entry_bci(entry_bci),
                                                       _context(&target_module.getContext()),
                                                       _bytecodes(_method),
                                                       _module(target_module),
                                                       _compiled_code(code),
                                                       _block_builder(new BasicBlockBuilder(method, _context, _llvm_func)),
                                                       _ir_builder(_block_builder->entry_block()->header_llvm_block()),
                                                       _oops(),
                                                       _block(nullptr),
                                                       _jvm(nullptr),
                                                       _work_list(),
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

  _block_builder->entry_block()->set_VM_state(initial_jvm);
}

void JeandleAbstractInterpreter::interpret() {
  JeandleBasicBlock* current = bci2block()[0];

  // Prepare work list. Push the first block.
  add_to_work_list(current);

  // Create branch from the entry block.
  _ir_builder.CreateBr(current->header_llvm_block());

  initialize_VM_state();

  if (!current->merge_VM_state_from(_block_builder->entry_block()->VM_state(), _block_builder->entry_block()->tail_llvm_block(), _method)) {
    JeandleCompilation::report_jeandle_error("failed to create initial VM state");
  }

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

  _ir_builder.SetInsertPoint(block->header_llvm_block());

  _block = block;
  _jvm = block->VM_state();
  assert(_jvm != nullptr, "JeandleVMState should not be null");

  _bytecodes.reset_to_bci(block->start_bci());

  Bytecodes::Code code = Bytecodes::_illegal;

  // Iterate all bytecodes.
  while ((code = _bytecodes.next()) != ciBytecodeStream::EOBC() &&
          !JeandleCompilation::jeandle_error_occurred() &&
          bci2block()[_bytecodes.cur_bci()] == _block) {
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

      case Bytecodes::_bipush: _jvm->ipush(JeandleType::int_const(_ir_builder, (((signed char*)_bytecodes.cur_bcp())[1]))); break;
      case Bytecodes::_sipush: _jvm->ipush(JeandleType::int_const(_ir_builder, (short)Bytes::get_Java_u2(_bytecodes.cur_bcp()+1))); break;

      case Bytecodes::_ldc:    // fall through
      case Bytecodes::_ldc_w:  // fall through
      case Bytecodes::_ldc2_w: load_constant(); break;

      // Loads:

      case Bytecodes::_iload_0: _jvm->ipush(_jvm->iload(0)); break;
      case Bytecodes::_iload_1: _jvm->ipush(_jvm->iload(1)); break;
      case Bytecodes::_iload_2: _jvm->ipush(_jvm->iload(2)); break;
      case Bytecodes::_iload_3: _jvm->ipush(_jvm->iload(3)); break;
      case Bytecodes::_iload: _jvm->ipush(_jvm->iload(_bytecodes.get_index())); break;

      case Bytecodes::_lload_0: _jvm->lpush(_jvm->lload(0)); break;
      case Bytecodes::_lload_1: _jvm->lpush(_jvm->lload(1)); break;
      case Bytecodes::_lload_2: _jvm->lpush(_jvm->lload(2)); break;
      case Bytecodes::_lload_3: _jvm->lpush(_jvm->lload(3)); break;
      case Bytecodes::_lload: _jvm->lpush(_jvm->lload(_bytecodes.get_index())); break;

      case Bytecodes::_fload_0: _jvm->fpush(_jvm->fload(0)); break;
      case Bytecodes::_fload_1: _jvm->fpush(_jvm->fload(1)); break;
      case Bytecodes::_fload_2: _jvm->fpush(_jvm->fload(2)); break;
      case Bytecodes::_fload_3: _jvm->fpush(_jvm->fload(3)); break;
      case Bytecodes::_fload: _jvm->fpush(_jvm->fload(_bytecodes.get_index())); break;

      case Bytecodes::_dload_0: _jvm->dpush(_jvm->dload(0)); break;
      case Bytecodes::_dload_1: _jvm->dpush(_jvm->dload(1)); break;
      case Bytecodes::_dload_2: _jvm->dpush(_jvm->dload(2)); break;
      case Bytecodes::_dload_3: _jvm->dpush(_jvm->dload(3)); break;
      case Bytecodes::_dload: _jvm->dpush(_jvm->dload(_bytecodes.get_index())); break;

      case Bytecodes::_aload_0: _jvm->apush(_jvm->aload(0)); break;
      case Bytecodes::_aload_1: _jvm->apush(_jvm->aload(1)); break;
      case Bytecodes::_aload_2: _jvm->apush(_jvm->aload(2)); break;
      case Bytecodes::_aload_3: _jvm->apush(_jvm->aload(3)); break;
      case Bytecodes::_aload: _jvm->apush(_jvm->aload(_bytecodes.get_index())); break;

      case Bytecodes::_iaload: // fall through
      case Bytecodes::_laload: // fall through
      case Bytecodes::_faload: // fall through
      case Bytecodes::_daload: // fall through
      case Bytecodes::_aaload: // fall through
      case Bytecodes::_baload: // fall through
      case Bytecodes::_caload: // fall through
      case Bytecodes::_saload: do_array_load(code); break;

      // Stores:

      case Bytecodes::_istore_0: _jvm->istore(0, _jvm->ipop()); break;
      case Bytecodes::_istore_1: _jvm->istore(1, _jvm->ipop()); break;
      case Bytecodes::_istore_2: _jvm->istore(2, _jvm->ipop()); break;
      case Bytecodes::_istore_3: _jvm->istore(3, _jvm->ipop()); break;
      case Bytecodes::_istore: _jvm->istore(_bytecodes.get_index(), _jvm->ipop()); break;

      case Bytecodes::_lstore_0: _jvm->lstore(0, _jvm->lpop()); break;
      case Bytecodes::_lstore_1: _jvm->lstore(1, _jvm->lpop()); break;
      case Bytecodes::_lstore_2: _jvm->lstore(2, _jvm->lpop()); break;
      case Bytecodes::_lstore_3: _jvm->lstore(3, _jvm->lpop()); break;
      case Bytecodes::_lstore: _jvm->lstore(_bytecodes.get_index(), _jvm->lpop()); break;

      case Bytecodes::_fstore_0: _jvm->fstore(0, _jvm->fpop()); break;
      case Bytecodes::_fstore_1: _jvm->fstore(1, _jvm->fpop()); break;
      case Bytecodes::_fstore_2: _jvm->fstore(2, _jvm->fpop()); break;
      case Bytecodes::_fstore_3: _jvm->fstore(3, _jvm->fpop()); break;
      case Bytecodes::_fstore: _jvm->fstore(_bytecodes.get_index(), _jvm->fpop()); break;

      case Bytecodes::_dstore_0: _jvm->dstore(0, _jvm->dpop()); break;
      case Bytecodes::_dstore_1: _jvm->dstore(1, _jvm->dpop()); break;
      case Bytecodes::_dstore_2: _jvm->dstore(2, _jvm->dpop()); break;
      case Bytecodes::_dstore_3: _jvm->dstore(3, _jvm->dpop()); break;
      case Bytecodes::_dstore: _jvm->dstore(_bytecodes.get_index(), _jvm->dpop()); break;

      case Bytecodes::_astore_0: _jvm->astore(0, _jvm->apop()); break;
      case Bytecodes::_astore_1: _jvm->astore(1, _jvm->apop()); break;
      case Bytecodes::_astore_2: _jvm->astore(2, _jvm->apop()); break;
      case Bytecodes::_astore_3: _jvm->astore(3, _jvm->apop()); break;
      case Bytecodes::_astore: _jvm->astore(_bytecodes.get_index(), _jvm->apop()); break;

      case Bytecodes::_iastore: // fall through
      case Bytecodes::_lastore: // fall through
      case Bytecodes::_fastore: // fall through
      case Bytecodes::_dastore: // fall through
      case Bytecodes::_aastore: // fall through
      case Bytecodes::_bastore: // fall through
      case Bytecodes::_castore: // fall through
      case Bytecodes::_sastore: do_array_store(code); break;

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
      case Bytecodes::_fneg: // fall through
      case Bytecodes::_frem: arith_op(BasicType::T_FLOAT, code); break;

      case Bytecodes::_dadd: // fall through
      case Bytecodes::_dsub: // fall through
      case Bytecodes::_dmul: // fall through
      case Bytecodes::_ddiv: // fall through
      case Bytecodes::_dneg: // fall through
      case Bytecodes::_drem: arith_op(BasicType::T_DOUBLE, code); break;

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

      case Bytecodes::_goto: goto_bci(_bytecodes.get_dest()); break;
      case Bytecodes::_jsr: Unimplemented(); break;
      case Bytecodes::_ret: Unimplemented(); break;

      case Bytecodes::_tableswitch: table_switch(); break;
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

      case Bytecodes::_new: do_new(); break;
      case Bytecodes::_newarray: newarray(_bytecodes.get_index_u1()); break;
      case Bytecodes::_anewarray: Unimplemented(); break;

      case Bytecodes::_arraylength: arraylength(); break;
      case Bytecodes::_athrow: dispatch_exception_to_handler(_jvm->apop()); break;

      case Bytecodes::_checkcast: Unimplemented(); break;
      case Bytecodes::_instanceof: instanceof(_bytecodes.get_index_u2()); break;

      case Bytecodes::_monitorenter: monitorenter(); break;
      case Bytecodes::_monitorexit: monitorexit(); break;

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
  if (block->tail_llvm_block()->getTerminator() == nullptr) {
    _ir_builder.CreateBr(bci2block()[_bytecodes.cur_bci()]->header_llvm_block());
  }

  block->set(JeandleBasicBlock::is_compiled);

  // Add all successors to work list and set up their JeandleVMStates.
  for (JeandleBasicBlock* suc : block->successors()) {
    // Don't update handlers' VM state here. They are updated by exception throwers.
    if (!suc->is_exception_handler() && !suc->merge_VM_state_from(block->VM_state(), block->tail_llvm_block(), _method)) {
      JeandleCompilation::report_jeandle_error("failed to update VM state");
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
  ciConstant con = _bytecodes.get_constant();
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
  llvm::Value* con = JeandleType::int_const(_ir_builder, _bytecodes.get_iinc_con());
  llvm::Value* result = _ir_builder.CreateAdd(_jvm->iload(_bytecodes.get_index()), con);
  _jvm->istore(_bytecodes.get_index(), result);
}

void JeandleAbstractInterpreter::if_zero(llvm::CmpInst::Predicate p) {
  if (_bytecodes.get_dest() < _bytecodes.cur_bci()) {
    add_safepoint_poll();
  }
  llvm::Value* v = _jvm->ipop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, v, JeandleType::int_const(_ir_builder, 0));
  _ir_builder.CreateCondBr(cond,
                           bci2block()[_bytecodes.get_dest()]->header_llvm_block(),
                           bci2block()[_bytecodes.next_bci()]->header_llvm_block());
}

void JeandleAbstractInterpreter::if_icmp(llvm::CmpInst::Predicate p) {
  if (_bytecodes.get_dest() < _bytecodes.cur_bci()) {
    add_safepoint_poll();
  }
  llvm::Value* r = _jvm->ipop();
  llvm::Value* l = _jvm->ipop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, l, r);
  _ir_builder.CreateCondBr(cond,
                           bci2block()[_bytecodes.get_dest()]->header_llvm_block(),
                           bci2block()[_bytecodes.next_bci()]->header_llvm_block());
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
  if (_bytecodes.get_dest() < _bytecodes.cur_bci()) {
    add_safepoint_poll();
  }
  llvm::Value* r = _jvm->apop();
  llvm::Value* l = _jvm->apop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, l, r);
  _ir_builder.CreateCondBr(cond,
                           bci2block()[_bytecodes.get_dest()]->header_llvm_block(),
                           bci2block()[_bytecodes.next_bci()]->header_llvm_block());
}

void JeandleAbstractInterpreter::if_null(llvm::CmpInst::Predicate p) {
  if (_bytecodes.get_dest() < _bytecodes.cur_bci()) {
    add_safepoint_poll();
  }
  llvm::Value* v = _jvm->apop();
  llvm::Value* cond = _ir_builder.CreateICmp(p, v, llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(v->getType())));
  _ir_builder.CreateCondBr(cond,
                           bci2block()[_bytecodes.get_dest()]->header_llvm_block(),
                           bci2block()[_bytecodes.next_bci()]->header_llvm_block());
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
  if (bci < _bytecodes.cur_bci()) {
    add_safepoint_poll();
  }
  _ir_builder.CreateBr(bci2block()[bci]->header_llvm_block());
}

void JeandleAbstractInterpreter::lookup_switch() {
  Bytecode_lookupswitch sw(&_bytecodes);

  int length = sw.number_of_pairs();
  int cur_bci = _bytecodes.cur_bci();

  llvm::Value* key = _jvm->ipop();
  llvm::BasicBlock* default_block = bci2block()[cur_bci + sw.default_offset()]->header_llvm_block();
  llvm::SwitchInst* switch_inst = _ir_builder.CreateSwitch(key, default_block, length);

  for (int i = 0; i < length; i++) {
    LookupswitchPair pair = sw.pair_at(i);
    switch_inst->addCase(JeandleType::int_const(_ir_builder, pair.match()), bci2block()[cur_bci + pair.offset()]->header_llvm_block());
  }
}

void JeandleAbstractInterpreter::table_switch() {
  Bytecode_tableswitch sw(&_bytecodes);

  int length = sw.length();
  int cur_bci = _bytecodes.cur_bci();
  int low = sw.low_key();

  llvm::Value* idx = _jvm->ipop();
  llvm::BasicBlock* default_block = bci2block()[cur_bci + sw.default_offset()]->header_llvm_block();
  llvm::SwitchInst* switch_inst = _ir_builder.CreateSwitch(idx, default_block, length);

  for (int i = 0; i < length; i++) {
    llvm::BasicBlock* case_block = bci2block()[cur_bci + sw.dest_offset_at(i)]->header_llvm_block();
    switch_inst->addCase(JeandleType::int_const(_ir_builder, i + low), case_block);
  }
}

// Generate call instructions.
// TODO: Reciever's null check.
void JeandleAbstractInterpreter::invoke() {
  bool will_link;
  ciSignature* declared_signature = nullptr;
  ciMethod* target = _bytecodes.get_method(will_link, &declared_signature);
  assert(declared_signature != nullptr, "cannot be null");
  assert(will_link == target->is_loaded(), "");

  // try inline callee as intrinsic
  if (target->is_loaded()
    && target->check_intrinsic_candidate()
    && inline_intrinsic(target)) {
    if (log_is_enabled(Debug, jeandle)) {
      ResourceMark rm;
      stringStream ss;
      target->print_name(&ss);
      log_debug(jeandle)("Method `%s` is parsed as intrinsic", ss.as_string());
    }
    return;
  }
  const Bytecodes::Code bc = _bytecodes.cur_bc();

  // Construct arguments.
  const int reciever =
    bc == Bytecodes::_invokespecial   ||
    bc == Bytecodes::_invokevirtual   ||
    bc == Bytecodes::_invokeinterface;
  const int arg_size = declared_signature->count() + reciever;
  llvm::SmallVector<llvm::Value*> args(arg_size);
  llvm::SmallVector<llvm::Type*> args_type(arg_size);
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
  llvm::FunctionType* func_type = llvm::FunctionType::get(JeandleType::java2llvm(return_type, *_context), args_type, false);
  llvm::FunctionCallee callee = _module.getOrInsertFunction(JeandleFuncSig::method_name(target), func_type);
  llvm::Function* func = llvm::cast<llvm::Function>(callee.getCallee());
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  func->setGC(llvm::jeandle::JeandleGC);

  // Decide call type and detination.
  JeandleCompiledCall::Type call_type = JeandleCompiledCall::NOT_A_CALL;
  address dest = nullptr;
  switch (bc) {
    case Bytecodes::_invokevirtual:  // fall through
    case Bytecodes::_invokeinterface: {
      call_type = JeandleCompiledCall::DYNAMIC_CALL;
      dest = SharedRuntime::get_resolve_virtual_call_stub();
      break;
    }
    case Bytecodes::_invokestatic: {
      call_type = JeandleCompiledCall::STATIC_CALL;
      dest = SharedRuntime::get_resolve_static_call_stub();
      break;
    }
    case Bytecodes::_invokedynamic: Unimplemented(); break;
    case Bytecodes::_invokespecial: {
      call_type = JeandleCompiledCall::STATIC_CALL;
      // TODO: Additional receiver subtype checks for interface calls via invokespecial.
      // Since checkcast and uncommon_trap have not yet been implemented, leave this for later.
      dest = SharedRuntime::get_resolve_opt_virtual_call_stub();
      break;
    }
    default: ShouldNotReachHere();
  }

  assert(call_type != JeandleCompiledCall::NOT_A_CALL, "legal call type");
  assert(dest != nullptr, "legal destination");

  // Record this call.
  uint32_t id = _compiled_code.next_statepoint_id();
  _compiled_code.push_non_routine_call_site(new CallSiteInfo(call_type, dest, _bytecodes.cur_bci(), id));

  // Every invoke instruction may throw exceptions, handle them here.
  DispatchedDest dispatched = dispatch_exception_for_invoke();

  // Create the invoke instruction.
  llvm::InvokeInst* invoke = _ir_builder.CreateInvoke(callee, dispatched._normal_dest, dispatched._unwind_dest, args);

  // Continue to interpret the remaining bytecodes in the current JeandleBasicBlock at dispatched._normal_dest.
  _ir_builder.SetInsertPoint(dispatched._normal_dest);

  // The dispatched._normal_dest is now the new tail block for the current JeandleBasicBlock.
  _block->set_tail_llvm_block(dispatched._normal_dest);

  // Apply attributes and calling convention.
  invoke->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  llvm::Attribute id_attr = llvm::Attribute::get(*_context,
                                                 llvm::jeandle::Attribute::StatepointID,
                                                 std::to_string(id));
  llvm::Attribute patch_bytes_attr = llvm::Attribute::get(*_context,
                                                 llvm::jeandle::Attribute::StatepointNumPatchBytes,
                                                 std::to_string(JeandleCompiledCall::call_site_patch_size(call_type)));
  invoke->addFnAttr(id_attr);
  invoke->addFnAttr(patch_bytes_attr);

  if (return_type != BasicType::T_VOID) {
    _jvm->push(return_type, invoke);
  }
}

bool JeandleAbstractInterpreter::inline_intrinsic(const ciMethod* target) {
  switch(target->intrinsic_id()) {
    case vmIntrinsics::_dabs: {
      _jvm->dpush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_DOUBLE, *_context), llvm::Intrinsic::fabs, {_jvm->dpop()}));
      break;
    }
    case vmIntrinsicID::_fabs: {
      _jvm->fpush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_FLOAT, *_context), llvm::Intrinsic::fabs, {_jvm->fpop()}));
      break;
    }
    case vmIntrinsicID::_iabs: {
      _jvm->ipush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_INT, *_context), llvm::Intrinsic::abs, {_jvm->ipop(), _ir_builder.getInt1(false)}));
      break;
    }
    case vmIntrinsicID::_labs: {
      _jvm->lpush(_ir_builder.CreateIntrinsic(JeandleType::java2llvm(BasicType::T_LONG, *_context), llvm::Intrinsic::abs, {_jvm->lpop(), _ir_builder.getInt1(false)}));
      break;
    }
    case vmIntrinsicID::_dsin: {
      llvm::FunctionCallee callee = StubRoutines::dsin() != nullptr ? JeandleRuntimeRoutine::hotspot_StubRoutines_dsin_callee(_module) :
                                                                      JeandleRuntimeRoutine::hotspot_SharedRuntime_dsin_callee(_module);
      _jvm->dpush(call_jeandle_routine(callee, {_jvm->dpop()}, llvm::CallingConv::C));
      break;
    }
    case vmIntrinsicID::_dcos: {
      llvm::FunctionCallee callee = StubRoutines::dcos() != nullptr ? JeandleRuntimeRoutine::hotspot_StubRoutines_dcos_callee(_module) :
                                                                      JeandleRuntimeRoutine::hotspot_SharedRuntime_dcos_callee(_module);
      _jvm->dpush(call_jeandle_routine(callee, {_jvm->dpop()}, llvm::CallingConv::C));
      break;
    }
    case vmIntrinsicID::_dtan: {
      llvm::FunctionCallee callee = StubRoutines::dtan() != nullptr ? JeandleRuntimeRoutine::hotspot_StubRoutines_dtan_callee(_module) :
                                                                      JeandleRuntimeRoutine::hotspot_SharedRuntime_dtan_callee(_module);
      _jvm->dpush(call_jeandle_routine(callee, {_jvm->dpop()}, llvm::CallingConv::C));
      break;
    }
    default:
      return false;
  }
  return true;
}

// Generate IR for calling into JeandleRuntimeRoutine
llvm::CallInst* JeandleAbstractInterpreter::call_jeandle_routine(llvm::FunctionCallee callee, llvm::ArrayRef<llvm::Value *> args, llvm::CallingConv::ID calling_conv) {
  llvm::CallInst *call = _ir_builder.CreateCall(callee, args);
  call->setCallingConv(calling_conv);
  return call;
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
  ciKlass* ci_super_klass = _bytecodes.get_klass();
  assert(ci_super_klass->is_loaded(), "klass must be loaded");

  Klass* super_klass = (Klass*)(ci_super_klass->constant_encoding());

  llvm::PointerType* klass_type = llvm::PointerType::get(*_context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* super_klass_addr = _ir_builder.getInt64((intptr_t)super_klass);
  llvm::Value* super_klass_ptr = _ir_builder.CreateIntToPtr(super_klass_addr, klass_type);

  llvm::CallInst* call = call_java_op("jeandle.instanceof", {super_klass_ptr, obj});

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
    case Bytecodes::_dsub: _jvm->push(type, _ir_builder.CreateFSub(l, r)); break;
    case Bytecodes::_fmul: // fall through
    case Bytecodes::_dmul: _jvm->push(type, _ir_builder.CreateFMul(l, r)); break;
    case Bytecodes::_fdiv: // fall through
    case Bytecodes::_ddiv: _jvm->push(type, _ir_builder.CreateFDiv(l, r)); break;
    case Bytecodes::_frem: {
      _jvm->fpush(call_jeandle_routine(JeandleRuntimeRoutine::hotspot_SharedRuntime_frem_callee(_module), {l, r}, llvm::CallingConv::C));
      break;
    }
    case Bytecodes::_drem: {
      _jvm->dpush(call_jeandle_routine(JeandleRuntimeRoutine::hotspot_SharedRuntime_drem_callee(_module), {l, r}, llvm::CallingConv::C));
      break;
    }
    case Bytecodes::_fneg: // fall through
    case Bytecodes::_dneg: {
      assert(l == nullptr, "only one operand for negation");
      _jvm->push(type, _ir_builder.CreateFNeg(r));
      break;
    }
    default: ShouldNotReachHere();
  }
}

llvm::CallInst* JeandleAbstractInterpreter::call_java_op(llvm::StringRef java_op, llvm::ArrayRef<llvm::Value*> args) {
  llvm::Function* java_op_func = _module.getFunction(java_op);
  assert(java_op_func != nullptr, "invalid JavaOp");
  llvm::CallInst* call_inst = _ir_builder.CreateCall(java_op_func, args);
  call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  return call_inst;
}

llvm::Value* JeandleAbstractInterpreter::find_or_insert_oop(ciObject* oop) {
  jobject oop_handle = oop->constant_encoding();
  if (llvm::Value* global_oop_handle = _oops.lookup(oop_handle)) {
    return global_oop_handle;
  }
  llvm::StringRef oop_name = next_oop_name();
  _compiled_code.oop_handles()[oop_name] = oop_handle;
  llvm::Value* global = _module.getOrInsertGlobal(
                               oop_name,
                               JeandleType::java2llvm(BasicType::T_OBJECT, *_context));
  llvm::GlobalVariable* global_oop_handle = llvm::cast<llvm::GlobalVariable>(global);
  global_oop_handle->setDSOLocal(true);
  _oops[oop_handle] = global_oop_handle;
  return global_oop_handle;
}

// TODO: clinit_barrier check.
// TODO: Handle field attributions like final, stable.
void JeandleAbstractInterpreter::do_field_access(bool is_get, bool is_static) {
  bool will_link;
  ciField* field = _bytecodes.get_field(will_link);
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

  bool is_volatile = field->is_volatile();
  llvm::Value* value = load_from_address(addr, field->layout_type(), is_volatile);
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

  bool is_volatile = field->is_volatile();
  store_to_address(addr, value, field->layout_type(), is_volatile);
}

llvm::Value* JeandleAbstractInterpreter::compute_instance_field_address(llvm::Value* obj, int offset) {
  return _ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(*_context), obj,
                                       _ir_builder.getInt64(offset));
}

llvm::Value* JeandleAbstractInterpreter::compute_static_field_address(ciInstanceKlass* holder, int offset) {
  ciInstance* holder_instance = holder->java_mirror();
  llvm::Value* holder_oop_handle = find_or_insert_oop(holder_instance);
  llvm::Value* holder_oop = _ir_builder.CreateLoad(JeandleType::java2llvm(BasicType::T_OBJECT, *_context), holder_oop_handle);
  return _ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(*_context),
                                       holder_oop,
                                       _ir_builder.getInt64(offset));
}

llvm::Value* JeandleAbstractInterpreter::load_from_address(llvm::Value* addr, BasicType type, bool is_volatile) {
  llvm::Type* expected_ty = JeandleType::java2llvm(type, *_context);
  llvm::LoadInst* load_inst = nullptr;
  llvm::Value* res_inst = nullptr;
  switch (type) {
    case T_BOOLEAN: {
      load_inst = _ir_builder.CreateLoad(llvm::Type::getInt8Ty(*_context), addr);
      res_inst = _ir_builder.CreateZExt(load_inst, expected_ty);
      break;
    }
    case T_BYTE: {
      load_inst = _ir_builder.CreateLoad(llvm::Type::getInt8Ty(*_context), addr);
      res_inst = _ir_builder.CreateSExt(load_inst, expected_ty);
      break;
    }
    case T_CHAR: {
      load_inst = _ir_builder.CreateLoad(llvm::Type::getInt16Ty(*_context), addr);
      res_inst = _ir_builder.CreateZExt(load_inst, expected_ty);
      break;
    }
    case T_SHORT: {
      load_inst = _ir_builder.CreateLoad(llvm::Type::getInt16Ty(*_context), addr);
      res_inst = _ir_builder.CreateSExt(load_inst, expected_ty);
      break;
    }
    default: {
      load_inst = _ir_builder.CreateLoad(expected_ty, addr);
      res_inst = load_inst;
      break;
    }
  }

  if (is_volatile) {
    load_inst->setAtomic(llvm::AtomicOrdering::SequentiallyConsistent);
  } else {
    load_inst->setAtomic(llvm::AtomicOrdering::Unordered);
  }

  return res_inst;
}

void JeandleAbstractInterpreter::store_to_address(llvm::Value* addr, llvm::Value* value, BasicType type, bool is_volatile) {
  llvm::Type* expected_ty = JeandleType::java2llvm(type, *_context);
  assert(value->getType() == expected_ty, "Value type must match field type");

  switch (type) {
    case T_BOOLEAN: // fall through
    case T_BYTE: {
      value = _ir_builder.CreateTrunc(value, llvm::Type::getInt8Ty(*_context));
      break;
    }
    case T_CHAR: // fall through
    case T_SHORT: {
      value = _ir_builder.CreateTrunc(value, llvm::Type::getInt16Ty(*_context));
      break;
    }
    default:
      break;
  }

  llvm::StoreInst* store_inst = _ir_builder.CreateStore(value, addr);

  if (is_volatile) {
    store_inst->setAtomic(llvm::AtomicOrdering::SequentiallyConsistent);
  } else {
    store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
  }
}

void JeandleAbstractInterpreter::add_safepoint_poll() {
  call_java_op("jeandle.safepoint_poll", {});
}

void JeandleAbstractInterpreter::arraylength() {
    // TODO: need null pointer check in the future
    llvm::Value* array_oop = _jvm->apop();
    llvm::CallInst* call = call_java_op("jeandle.arraylength", {array_oop});
    _jvm->ipush(call);
}

llvm::Value* JeandleAbstractInterpreter::compute_array_element_address(BasicType basic_type, llvm::Type* type) {
  llvm::Value* index = _jvm->ipop();
  llvm::Value* array_oop = _jvm->apop();
  llvm::Value* array_base_offset = _ir_builder.getInt32(arrayOopDesc::base_offset_in_bytes(basic_type));
  llvm::Value* array_base = _ir_builder.CreateInBoundsPtrAdd(array_oop, array_base_offset, "array_element_base");
  llvm::Value* element_address = _ir_builder.CreateInBoundsGEP(type, array_base, index, "array_element_address");
  return element_address;
}

llvm::Value* JeandleAbstractInterpreter::do_array_load_inner(BasicType basic_type, llvm::Type* load_type) {
  llvm::Value* element_address = compute_array_element_address(basic_type, load_type);
  llvm::LoadInst* load_inst = _ir_builder.CreateLoad(load_type, element_address);
  load_inst->setAtomic(llvm::AtomicOrdering::Unordered);
  return load_inst;
}

void JeandleAbstractInterpreter::do_array_load(Bytecodes::Code code) {
  switch (code) {
    case Bytecodes::_iaload: {
      llvm::Value* load_value = do_array_load_inner(T_INT, llvm::Type::getInt32Ty(*_context));
      _jvm->ipush(load_value);
      break;
    }
    case Bytecodes::_laload: {
      llvm::Value* load_value = do_array_load_inner(T_LONG, llvm::Type::getInt64Ty(*_context));
      _jvm->lpush(load_value);
      break;
    }
    case Bytecodes::_faload: {
      llvm::Value* load_value = do_array_load_inner(T_FLOAT, llvm::Type::getFloatTy(*_context));
      _jvm->fpush(load_value);
      break;
    }
    case Bytecodes::_daload: {
      llvm::Value* load_value = do_array_load_inner(T_DOUBLE, llvm::Type::getDoubleTy(*_context));
      _jvm->dpush(load_value);
      break;
    }
    case Bytecodes::_aaload: {
      llvm::Value* load_value = do_array_load_inner(
              T_OBJECT, llvm::PointerType::get(*_context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace));
      _jvm->apush(load_value);
      break;
    }
    case Bytecodes::_baload: {
      llvm::Value* load_value = do_array_load_inner(T_BYTE, llvm::Type::getInt8Ty(*_context));
      _jvm->ipush(_ir_builder.CreateSExt(load_value, JeandleType::java2llvm(BasicType::T_BYTE, *_context)));
      break;
    }
    case Bytecodes::_caload: {
      llvm::Value* load_value = do_array_load_inner(T_CHAR, llvm::Type::getInt16Ty(*_context));
      _jvm->ipush(_ir_builder.CreateZExt(load_value, JeandleType::java2llvm(BasicType::T_CHAR, *_context)));
      break;
    }
    case Bytecodes::_saload: {
      llvm::Value* load_value = do_array_load_inner(T_SHORT, llvm::Type::getInt16Ty(*_context));
      _jvm->ipush(_ir_builder.CreateSExt(load_value, JeandleType::java2llvm(BasicType::T_SHORT, *_context)));
      break;
    }
    default: ShouldNotReachHere();
  }
}

void JeandleAbstractInterpreter::do_array_store_inner(BasicType basic_type, llvm::Type* store_type, llvm::Value* value) {
  llvm::Value* element_address = compute_array_element_address(basic_type, store_type);
  llvm::StoreInst* store_inst = _ir_builder.CreateStore(value, element_address);
  store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
}

void JeandleAbstractInterpreter::do_array_store(Bytecodes::Code code) {
  llvm::Value* value = nullptr;
  switch (code) {
    case Bytecodes::_iastore: {
      value = _jvm->ipop();
      do_array_store_inner(T_INT, llvm::Type::getInt32Ty(*_context), value);
      break;
    }
    case Bytecodes::_lastore: {
      value = _jvm->lpop();
      do_array_store_inner(T_LONG, llvm::Type::getInt64Ty(*_context), value);
      break;
    }
    case Bytecodes::_fastore: {
      value = _jvm->fpop();
      do_array_store_inner(T_FLOAT, llvm::Type::getFloatTy(*_context), value);
      break;
    }
    case Bytecodes::_dastore: {
      value = _jvm->dpop();
      do_array_store_inner(T_DOUBLE, llvm::Type::getDoubleTy(*_context), value);
      break;
    }
    case Bytecodes::_aastore: {
      value = _jvm->apop();
      do_array_store_inner(T_OBJECT, llvm::PointerType::get(*_context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), value);
      break;
    }
    case Bytecodes::_bastore: {
      value = _ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt8Ty(*_context));
      do_array_store_inner(T_BYTE, llvm::Type::getInt8Ty(*_context), value);
      break;
    }
    case Bytecodes::_castore: {
      value = _ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt16Ty(*_context));
      do_array_store_inner(T_CHAR, llvm::Type::getInt16Ty(*_context), value);
      break;
    }
    case Bytecodes::_sastore: {
      value = _ir_builder.CreateTrunc(_jvm->ipop(), llvm::Type::getInt16Ty(*_context));
      do_array_store_inner(T_SHORT, llvm::Type::getInt16Ty(*_context), value);
      break;
    }
    default: ShouldNotReachHere();
  }
}

void JeandleAbstractInterpreter::do_new() {
  bool will_link;
  ciKlass* klass = _bytecodes.get_klass(will_link);
  assert(will_link, "_new: not link");

  if (klass->is_abstract() || klass->is_interface() ||
      klass->name() == ciSymbols::java_lang_Class() ||
      _bytecodes.is_unresolved_klass()) {
    /* TODO: Uncommon trap.
    uncommon_trap(Deoptimization::Reason_unhandled,
                  Deoptimization::Action_none,
                  klass);
    */
    Unimplemented();
    return;
  }
  // TODO: cl init barrier
  jint layout_helper = klass->layout_helper();
  assert(Klass::layout_helper_is_instance(layout_helper), "Unexpected klass");
  llvm::Value* size_in_bytes = _ir_builder.getInt32(Klass::layout_helper_size_in_bytes(layout_helper));

  Klass* klass_enc = (Klass*)(klass->constant_encoding());
  llvm::PointerType* klass_type = llvm::PointerType::get(*_context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* klass_addr = _ir_builder.getInt64((int64_t)klass_enc);
  llvm::Value* klass_ptr = _ir_builder.CreateIntToPtr(klass_addr, klass_type);

  _jvm->apush(call_java_op("jeandle.new_instance", {klass_ptr, size_in_bytes}));
}

JeandleAbstractInterpreter::DispatchedDest JeandleAbstractInterpreter::dispatch_exception_for_invoke() {
  int cur_bci = _bytecodes.cur_bci();

  DispatchedDest dispatched;

  // Create the unwind dest block.
  llvm::BasicBlock* unwind_dest = llvm::BasicBlock::Create(*_context,
                                                           "bci_" + std::to_string(cur_bci) + "_unwind_dest",
                                                           _llvm_func);
  dispatched._unwind_dest = unwind_dest;

  auto saved_insert_block = _ir_builder.GetInsertBlock();
  auto saved_insert_point = _ir_builder.GetInsertPoint();
  _ir_builder.SetInsertPoint(unwind_dest);

  // Create a landingpad instruction to indicate this is an unwind entry. But we never use the result from it.
  // Create our landingpad result type
  llvm::Type* landingpad_result_type = llvm::Type::getInt64Ty(*_context); // The landingpad type will be rewrite to token type by RS4GC to support statepoint.
  llvm::LandingPadInst* landingpad = _ir_builder.CreateLandingPad(landingpad_result_type,
                                                                  0 /* NumClauses */);
  // This landingpad should always be entered during exception handling.
  landingpad->setCleanup(true);

  // Read the exception oop from thread local storage.
  llvm::Value* exception_oop_addr = _ir_builder.CreateIntToPtr(_ir_builder.getInt64((uint64_t)JavaThread::exception_oop_offset()),
                                                               llvm::PointerType::get(*_context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  llvm::Value* exception_oop = _ir_builder.CreateLoad(JeandleType::java2llvm(BasicType::T_OBJECT, *_context), exception_oop_addr, true /* is_volatile */);

  // Clear the exception oop field in thread local storage.
  _ir_builder.CreateStore(llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(JeandleType::java2llvm(BasicType::T_OBJECT, *_context))),
                          exception_oop_addr,
                          true /* is_volatile */);

  dispatch_exception_to_handler(exception_oop);

  // Recover insert point.
  _ir_builder.SetInsertPoint(saved_insert_block, saved_insert_point);

  // Get the normal dest block.
  llvm::BasicBlock* normal_dest = llvm::BasicBlock::Create(*_context,
                                                           "bci_" + std::to_string(cur_bci) + "_normal_dest",
                                                           _llvm_func);
  dispatched._normal_dest = normal_dest;

  return dispatched;
}

// TODO: Add real dispatching process using 'instanceof'.
// As a workaround now, we always choose the first exception handler.
void JeandleAbstractInterpreter::dispatch_exception_to_handler(llvm::Value* exception_oop) {
  // Setup all handlers' VM state.
  for (ciExceptionHandlerStream handlers(_method, _bytecodes.cur_bci()); !handlers.is_done(); handlers.next()) {
    ciExceptionHandler* handler = handlers.handler();
    if (!handler->is_rethrow()) {
      int handler_bci = handler->handler_bci();
      JeandleBasicBlock* handler_block = bci2block()[handler_bci];
      assert(handler_block != nullptr, "invalid handler block");
      if (!handler_block->merge_VM_state_from(_jvm->copy_for_exception_handler(exception_oop), _ir_builder.GetInsertBlock(), _method)) {
        JeandleCompilation::report_jeandle_error("failed to update handler's VM state");
      }
    }
  }

  // Choose the first handler. (workaround)
  ciExceptionHandlerStream handlers(_method, _bytecodes.cur_bci());
  if (!handlers.is_done()) {
    ciExceptionHandler* handler = handlers.handler();
    if (handler->is_rethrow()) {
      // Rethrow it.
      throw_exception(exception_oop);
    } else {
      int handler_bci = handler->handler_bci();
      JeandleBasicBlock* handler_block = bci2block()[handler_bci];
      assert(handler_block != nullptr, "invalid handler block");
      _ir_builder.CreateBr(handler_block->header_llvm_block());
    }
    return;
  }

  // At least one handler is found.
  ShouldNotReachHere();
}

void JeandleAbstractInterpreter::throw_exception(llvm::Value* exception_oop) {
  // Call install_exceptional_return.
  llvm::CallInst* current_thread = call_java_op("jeandle.current_thread", {});
  llvm::CallInst* call_inst = call_jeandle_routine(JeandleRuntimeRoutine::install_exceptional_return_callee(_module),
                                                   {exception_oop, current_thread}, llvm::CallingConv::Hotspot_JIT);

  // Return
  llvm::Type* ret_type = _llvm_func->getReturnType();
  if (ret_type->isVoidTy()) {
    _ir_builder.CreateRetVoid();;
  } else if (ret_type->isIntegerTy()) {
    _ir_builder.CreateRet(llvm::ConstantInt::get(ret_type, 0));
  } else if (ret_type->isFloatTy() || ret_type->isDoubleTy()) {
    _ir_builder.CreateRet(llvm::ConstantFP::get(ret_type, 0.0));
  } else if (ret_type->isPointerTy()) {
    _ir_builder.CreateRet(llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(ret_type)));
  } else {
    ShouldNotReachHere();
  }
}

void JeandleAbstractInterpreter::newarray(int element_type){
  llvm::Value* length = _jvm->ipop();
  // Get array type from bytecode
  llvm::Value* type_value = _ir_builder.getInt32(static_cast<BasicType>(element_type));
  llvm::CallInst* result = call_java_op("jeandle.newarray", {type_value, length});
  _jvm->apush(result);
}

void JeandleAbstractInterpreter::monitorenter() {
  llvm::Value* obj = _jvm->apop();

  // Allocate a BasicLock on stack.
  // Alloca insts should be in the entry block to be 'StaticAlloca'. Then they could be folded into prologue code.
  llvm::IRBuilder entry_block_ir_builder(_block_builder->entry_block()->header_llvm_block()->getTerminator());
  llvm::Value* lock = entry_block_ir_builder.CreateAlloca(_ir_builder.getIntPtrTy(_module.getDataLayout()), llvm::jeandle::AddrSpace::CHeapAddrSpace, nullptr, "BasicLock");

  _jvm->push_lock(lock);

  llvm::FunctionCallee monitorenter_callee = JeandleRuntimeRoutine::hotspot_SharedRuntime_complete_monitor_locking_C_callee(_module);
  llvm::CallInst* current_thread = call_java_op("jeandle.current_thread", {});
  llvm::CallInst* call_monitorenter = _ir_builder.CreateCall(monitorenter_callee, {obj, lock, current_thread});
  call_monitorenter->setCallingConv(llvm::CallingConv::C);
}

void JeandleAbstractInterpreter::monitorexit() {

  llvm::Value* obj = _jvm->apop();

  null_check(obj);

  llvm::Value* lock = _jvm->pop_lock();

  llvm::FunctionCallee monitorexit_callee = JeandleRuntimeRoutine::hotspot_SharedRuntime_complete_monitor_unlocking_C_callee(_module);
  llvm::CallInst* current_thread = call_java_op("jeandle.current_thread", {});
  llvm::CallInst* call_monitorexit = _ir_builder.CreateCall(monitorexit_callee, {obj, lock, current_thread});
  call_monitorexit->setCallingConv(llvm::CallingConv::C);
}

// TODO: Implement me!
void JeandleAbstractInterpreter::null_check(llvm::Value* obj) {
  int cur_bci = _bytecodes.cur_bci();
  llvm::BasicBlock* null_check_pass = llvm::BasicBlock::Create(*_context,
                                                               "bci_" + std::to_string(cur_bci) + "_null_check_pass",
                                                               _llvm_func);
  llvm::BasicBlock* null_check_fail = llvm::BasicBlock::Create(*_context,
                                                               "bci_" + std::to_string(cur_bci) + "_null_check_fail",
                                                               _llvm_func);
  llvm::Value* if_null = _ir_builder.CreateICmp(llvm::CmpInst::ICMP_EQ,
                                                obj,
                                                llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(obj->getType())));
  _ir_builder.CreateCondBr(if_null, null_check_fail, null_check_pass);

  _ir_builder.SetInsertPoint(null_check_fail);
  dispatch_exception_to_handler(llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(JeandleType::java2llvm(BasicType::T_OBJECT, *_context))));

  _ir_builder.SetInsertPoint(null_check_pass);
  _block->set_tail_llvm_block(null_check_pass);
}
