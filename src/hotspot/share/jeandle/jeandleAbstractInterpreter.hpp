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

#ifndef SHARE_JEANDLE_ABSTRACT_INTERPRETER_HPP
#define SHARE_JEANDLE_ABSTRACT_INTERPRETER_HPP

#include <cassert>
#include "llvm/IR/BasicBlock.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/LLVMContext.h"

#include <vector>

#include "jeandle/jeandleCompilation.hpp"

#include "utilities/debug.hpp"
#include "ci/compilerInterface.hpp"
#include "memory/allocation.hpp"
#include "utilities/bitMap.inline.hpp"

// Used by the abstract interpreter to trace JVM states.
class JeandleBasicBlock;
class JeandleVMState : public JeandleCompilationResourceObj {
 public:

  JeandleVMState(int max_stack, int max_locals, llvm::LLVMContext *context);

  JeandleVMState(JeandleVMState* copy_from);

  JeandleVMState* copy(MethodLivenessResult liveness);

  // Create a new JeandleVMState by constructing phi nodes from a existing block.
  static JeandleVMState* create_phi_from(JeandleBasicBlock* from,
                                         JeandleBasicBlock* self,
                                         MethodLivenessResult liveness,
                                         llvm::IRBuilder<>* ir_builder);

  // Add incoming values for phi nodes. Return false if type check fails.
  bool phi(JeandleBasicBlock* income);

  // Check with another JeandleVMState if all stack values are same types and locals sizes are the same.
  bool match(JeandleVMState* jvm);

  // Stack operations:

  void ipush(llvm::Value* value) { push(BasicType::T_INT, value); }
  llvm::Value* ipop() { return pop(BasicType::T_INT); }

  void lpush(llvm::Value* value) { push(BasicType::T_LONG, value); }
  llvm::Value* lpop() { return pop(BasicType::T_LONG); }

  void apush(llvm::Value* value) { push(BasicType::T_OBJECT, value); }
  llvm::Value* apop() { return pop(BasicType::T_OBJECT); }

  void push(BasicType type, llvm::Value* value);
  llvm::Value* pop(BasicType type);

  void fpush(llvm::Value* value) { push(BasicType::T_FLOAT, value); }
  llvm::Value* fpop() { return pop(BasicType::T_FLOAT); }

  void dpush(llvm::Value* value) { push(BasicType::T_DOUBLE, value); }
  llvm::Value* dpop() { return pop(BasicType::T_DOUBLE); }

  // Untyped manipulation (for dup_x1, etc.)
  void raw_push(llvm::Value* t) { _stack.push_back(t); }
  llvm::Value* raw_pop() { llvm::Value* v = _stack.back(); _stack.pop_back(); return v; }

  // Local variables operations:

  size_t locals_size() const { return _locals.size(); }

  void invalidate_local(int index) { _locals[index] = nullptr; }

  llvm::Value* local_at(int index) { return _locals[index]; }

  llvm::Value* iload(int index) { return load(BasicType::T_INT, index); }
  void istore(int index, llvm::Value* value) { store(BasicType::T_INT, index, value); }

  llvm::Value* lload(int index) { return load(BasicType::T_LONG, index); }
  void lstore(int index, llvm::Value* value) { store(BasicType::T_LONG, index, value); }

  llvm::Value* aload(int index) { return load(BasicType::T_OBJECT, index); }
  void astore(int index, llvm::Value* value) { store(BasicType::T_OBJECT, index, value); }

  llvm::Value* load(BasicType type, int index);
  void store(BasicType type, int index, llvm::Value* value);

  llvm::Value* fload(int index) { return load(BasicType::T_FLOAT, index); }
  void fstore(int index, llvm::Value* value) { store(BasicType::T_FLOAT, index, value); }

  llvm::Value* dload(int index) { return load(BasicType::T_DOUBLE, index); }
  void dstore(int index, llvm::Value* value) { store(BasicType::T_DOUBLE, index, value); }

 private:
  std::vector<llvm::Value*> _stack;
  std::vector<llvm::Value*> _locals;

  llvm::LLVMContext* _context;
};

class JeandleBasicBlock : public JeandleCompilationResourceObj {
 public:
  JeandleBasicBlock(int block_id, int start_bci, llvm::BasicBlock* llvm_block);

  // Update the JeandleVMState according to the predecessor block's stack values and locals.
  bool income_block(JeandleBasicBlock* income, ciMethod* method, llvm::IRBuilder<>* ir_builder);

  enum Flag {
    no_flag                       = 0,
    is_compiled                   = 1 << 0,
    is_on_work_list               = 1 << 1,
    is_loop_header                = 1 << 2,
  };

  void set(Flag f)                               { _flags |= f; }
  void clear(Flag f)                             { _flags &= ~f; }
  bool is_set(Flag f) const                      { return (_flags & f) != 0; }

  std::vector<JeandleBasicBlock*>& successors() { return _successors; }
  void add_successors(JeandleBasicBlock* successor) {
    assert(successor != nullptr, "successor can not be null");
    _successors.push_back(successor);
  }

  std::vector<JeandleBasicBlock*>& predecessors() { return _predecessors; }
  void add_predecessors(JeandleBasicBlock* predecessor) { _predecessors.push_back(predecessor); }

  int reverse_post_order() const { return _reverse_post_order; }
  void set_reverse_post_order(int order) {  _reverse_post_order = order; }

  JeandleVMState* jvm_tracker() { return _jvm; }
  void set_jvm_tracker(JeandleVMState* jvm) { _jvm = jvm; }

  int block_id() const { return _block_id; }
  int start_bci() const { return _start_bci; }
  llvm::BasicBlock* llvm_block() { return _llvm_block; }

 private:
  int _block_id;
  std::vector<JeandleBasicBlock*> _predecessors;
  std::vector<JeandleBasicBlock*> _successors;
  int _flags;
  int _start_bci;
  llvm::BasicBlock* _llvm_block;
  int _reverse_post_order;
  JeandleVMState* _jvm;

  // The JeandleVMState recording the initial state of a loop header.
  // When a loop tail block is interpreted, we need to update the loop header's
  // phi nodes. Use this variable to find the right phi nodes to update.
  JeandleVMState* _initial_loop_header;
};

class BasicBlockBuilder : public JeandleCompilationResourceObj {
 public:
  BasicBlockBuilder(ciMethod* method, llvm::LLVMContext* context, llvm::Function* llvm_func);

  llvm::DenseMap<int, JeandleBasicBlock*>& bci2block() { return _bci2block; }

  JeandleBasicBlock* entry_block() { return _entry_block; }

 private:
  llvm::DenseMap<int, JeandleBasicBlock*> _bci2block;
  ciMethod* _method;
  llvm::LLVMContext* _context;
  llvm::Function* _llvm_func;
  JeandleBasicBlock* _entry_block; // a dummy block holding initial stack/locals state.
  int _next_block_id;

  // For loop marking and ordering.
  ResourceBitMap _active;
  ResourceBitMap _visited;
  int _next_block_order;

  void generate_blocks();
  JeandleBasicBlock* make_block_at(int bci, JeandleBasicBlock* current);

  void mark_loops();
  void mark_loops(JeandleBasicBlock* block);
};

// Convert java bytecodes to llvm ir.
class JeandleAbstractInterpreter : public StackObj {
 public:
  JeandleAbstractInterpreter(ciMethod* method,
                             int entry_bci,
                             llvm::Module& target_module,
                             JeandleCompiledCode& code);

 private:
  ciMethod* _method;
  llvm::Function* _llvm_func;
  int _entry_bci;
  llvm::LLVMContext* _context;
  ciBytecodeStream _codes;
  llvm::Module& _module;
  JeandleCompiledCode& _code;
  BasicBlockBuilder* _block_builder;
  llvm::IRBuilder<> _ir_builder;

  // Assign a unique statepoint id for each statepoint.
  uint32_t _statepoint_id;

  // Record oop values.
  llvm::DenseMap<jobject, llvm::Value*> _oops;

  // The JeandleBasicBlock and its JeandleVMState currently being interpreted.
  JeandleBasicBlock* _block;
  JeandleVMState* _jvm;

  // Contains all blocks to interpret. Sorted by reverse-post-order.
  std::vector<JeandleBasicBlock*> _work_list;

  void initialize_VM_state();
  void interpret();
  void interpret_block(JeandleBasicBlock* block);

  void add_to_work_list(JeandleBasicBlock* block);

  // Bytecode related process:
  void load_constant();
  void increment();
  void if_zero(llvm::CmpInst::Predicate p);
  void if_icmp(llvm::CmpInst::Predicate p);
  void if_acmp(llvm::CmpInst::Predicate p);
  void if_null(llvm::CmpInst::Predicate p);
  void fcmp(BasicType type, bool true_if_unordered);
  void lcmp();
  void goto_bci(int bci);
  void lookup_switch();
  void invoke();
  void stack_op(Bytecodes::Code code);
  void shift_op(BasicType type, Bytecodes::Code code);
  void instanceof(int klass_index);
  void arith_op(BasicType type, Bytecodes::Code code);

  llvm::CallInst* call_java_op(llvm::StringRef java_op, llvm::ArrayRef<llvm::Value*> args);

  void add_safepoint_poll();

  llvm::DenseMap<int, JeandleBasicBlock*>& bci2block() { return _block_builder->bci2block(); }

  uint32_t next_statepoint_id() { return _statepoint_id++; }

  llvm::Value* find_or_insert_oop(ciObject* oop);

  int _oop_idx;
  std::string next_oop_name() { return std::string("oop_handle_") + std::to_string(_oop_idx++); }

  // Implementation of _get* and _put* bytecodes.
  void do_getstatic() { do_field_access(true, true); }
  void do_getfield() { do_field_access(true, false); }
  void do_putstatic() { do_field_access(false, true); }
  void do_putfield() { do_field_access(false, false); }

  // Common code for making initial checks and forming addresses.
  void do_field_access(bool is_get, bool is_static);

  // Helper methods for field access.
  llvm::Value* compute_instance_field_address(llvm::Value* obj, int offset);
  llvm::Value* compute_static_field_address(ciInstanceKlass* holder, int offset);
  llvm::Value* load_from_address(llvm::Value* addr, BasicType type);
  void store_to_address(llvm::Value* addr, llvm::Value* value, BasicType type);

  void do_get_xxx(ciField* field, bool is_static);
  void do_put_xxx(ciField* field, bool is_static);
};

#endif // SHARE_JEANDLE_ABSTRACT_INTERPRETER_HPP
