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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/BasicBlock.h"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/SmallPtrSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/BasicBlock.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/LLVMContext.h"

#include "jeandle/jeandleCompilation.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethodBlocks.hpp"
#include "ci/compilerInterface.hpp"
#include "memory/allocation.hpp"
#include "utilities/bitMap.inline.hpp"

// Used by the abstract interpreter to trace JVM states.
class JeandleBasicBlock;
class JeandleVMState : public JeandleCompilationResourceObj {
 public:

  JeandleVMState(int max_stack, int max_locals, llvm::LLVMContext *context);

  JeandleVMState* copy(bool clear_stack = false);
  JeandleVMState* copy_for_exception_handler(llvm::Value* exception_oop);

  // Check with another JeandleVMState if all stack values are same types and locals sizes are the same.
  bool match(JeandleVMState* jvm);

  // Add incoming values for phi nodes. Return false if type check fails.
  bool update_phi_nodes(JeandleVMState* income_jvm, llvm::BasicBlock* income_block);

  // Stack operations:

  size_t stack_size() const { return _stack.size(); }
  size_t max_stack() const { return _stack.capacity(); }

  llvm::Value* stack_at(int index) { return _stack[index]; }

  void push(BasicType type, llvm::Value* value);
  llvm::Value* pop(BasicType type);

  void ipush(llvm::Value* value) { push(BasicType::T_INT, value); }
  llvm::Value* ipop() { return pop(BasicType::T_INT); }

  void lpush(llvm::Value* value) { push(BasicType::T_LONG, value); }
  llvm::Value* lpop() { return pop(BasicType::T_LONG); }

  void apush(llvm::Value* value) { push(BasicType::T_OBJECT, value); }
  llvm::Value* apop() { return pop(BasicType::T_OBJECT); }

  void fpush(llvm::Value* value) { push(BasicType::T_FLOAT, value); }
  llvm::Value* fpop() { return pop(BasicType::T_FLOAT); }

  void dpush(llvm::Value* value) { push(BasicType::T_DOUBLE, value); }
  llvm::Value* dpop() { return pop(BasicType::T_DOUBLE); }

  // Untyped manipulation (for dup_x1, etc.)
  void raw_push(llvm::Value* t) { _stack.push_back(t); }
  llvm::Value* raw_pop() { llvm::Value* v = _stack.back(); _stack.pop_back(); return v; }

  // Local variables operations:

  size_t locals_size() const { return _locals.size(); }
  size_t max_locals() const { return _locals.size(); }

  void invalidate_local(int index) { _locals[index] = nullptr; }

  llvm::Value* locals_at(int index) { return _locals[index]; }
  void set_locals_at(int index, llvm::Value* value) { _locals[index] = value; }

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

  // Locks operations:
  void push_lock(llvm::Value* lock) { _locks.push_back(lock); }
  llvm::Value* pop_lock() { llvm::Value* v = _locks.back(); _locks.pop_back(); return v; }
  size_t locks_size() const { return _locks.size(); }
  llvm::Value* lock_at(int index) { return _locks[index]; }

 private:
  llvm::SmallVector<llvm::Value*> _stack;
  llvm::SmallVector<llvm::Value*> _locals;
  llvm::SmallVector<llvm::Value*> _locks;

  llvm::LLVMContext* _context;

  JeandleVMState(JeandleVMState* copy_from, bool clear_stack = false);
};

class JeandleBasicBlock : public JeandleCompilationResourceObj {
 public:
  JeandleBasicBlock(int block_id, int start_bci, int limit_bci, llvm::BasicBlock* llvm_block, ciBlock* ci_block);

  // Update the JeandleVMState according to the predecessor block's stack values and locals.
  bool merge_VM_state_from(JeandleVMState* vm_state, llvm::BasicBlock* incoming, ciMethod* method);

  enum Flag {
    no_flag                       = 0,
    is_compiled                   = 1 << 0,
    is_on_work_list               = 1 << 1,
    is_loop_header                = 1 << 2,
  };

  void set(Flag f)                               { _flags |= f; }
  void clear(Flag f)                             { _flags &= ~f; }
  bool is_set(Flag f) const                      { return (_flags & f) != 0; }

  llvm::SmallPtrSet<JeandleBasicBlock*, 8>& successors() { return _successors; }
  void add_successor(JeandleBasicBlock* successor) {
    assert(successor != nullptr, "successor can not be null");
    _successors.insert(successor);
  }

  llvm::SmallPtrSet<JeandleBasicBlock*, 8>& predecessors() { return _predecessors; }
  void add_predecessor(JeandleBasicBlock* predecessor) {
    assert(predecessor != nullptr, "predecessor can not be null");
    _predecessors.insert(predecessor);
  }

  int reverse_post_order() const { return _reverse_post_order; }
  void set_reverse_post_order(int order) {  _reverse_post_order = order; }

  JeandleVMState* VM_state() { return _jvm; }
  void set_VM_state(JeandleVMState* jvm) { _jvm = jvm; }

  int block_id() const { return _block_id; }
  int start_bci() const { return _start_bci; }
  int limit_bci() const { return _limit_bci; }

  llvm::BasicBlock* header_llvm_block() { return _header_llvm_block; }

  llvm::BasicBlock* tail_llvm_block() { return _tail_llvm_block; }
  void set_tail_llvm_block(llvm::BasicBlock* block) { _tail_llvm_block = block; }

  bool is_exception_handler() { return _ci_block->is_handler(); }
  int exeption_range_start_bci() { return _ci_block->ex_start_bci(); }
  int exeption_range_limit_bci() { return _ci_block->ex_limit_bci(); }

 private:
  int _block_id;
  int _flags;
  int _start_bci;
  int _limit_bci;
  int _reverse_post_order;

  JeandleVMState* _jvm;

  llvm::SmallPtrSet<JeandleBasicBlock*, 8> _predecessors;
  llvm::SmallPtrSet<JeandleBasicBlock*, 8> _successors;

  llvm::BasicBlock* _header_llvm_block;
  llvm::BasicBlock* _tail_llvm_block;
  ciBlock* _ci_block;

  // The JeandleVMState recording the initial state of a loop header.
  // When a loop tail block is interpreted, we need to update the loop header's
  // phi nodes. Use this variable to find the right phi nodes to update.
  JeandleVMState* _initial_jvm;

  void initialize_VM_state_from(JeandleVMState* incoming_state, llvm::BasicBlock* incoming_block, MethodLivenessResult liveness);
};

class BasicBlockBuilder : public JeandleCompilationResourceObj {
 public:
  BasicBlockBuilder(ciMethod* method, llvm::LLVMContext* context, llvm::Function* llvm_func);

  llvm::SmallVector<JeandleBasicBlock*>& bci2block() { return _bci2block; }

  JeandleBasicBlock* entry_block() { return _entry_block; }

  static void connect_block(JeandleBasicBlock* child_block, JeandleBasicBlock* parent_block) {
    assert(child_block != nullptr && parent_block != nullptr, "connecting nullptr");
    child_block->add_predecessor(parent_block);
    parent_block->add_successor(child_block);
  }

 private:
  llvm::SmallVector<JeandleBasicBlock*> _bci2block;
  ciMethod* _method;
  ciMethodBlocks* _ci_blocks;
  llvm::LLVMContext* _context;
  llvm::Function* _llvm_func;
  JeandleBasicBlock* _entry_block; // a dummy block holding initial stack/locals state.

  // For loop marking and ordering.
  ResourceBitMap _active;
  ResourceBitMap _visited;
  int _next_block_order;

  void generate_blocks();
  void setup_exception_handlers();
  void setup_control_flow();
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
  ciBytecodeStream _bytecodes;
  llvm::Module& _module;
  JeandleCompiledCode& _compiled_code;
  BasicBlockBuilder* _block_builder;
  llvm::IRBuilder<> _ir_builder;

  // Record oop values.
  llvm::DenseMap<jobject, llvm::Value*> _oops;

  // The JeandleBasicBlock and its JeandleVMState currently being interpreted.
  JeandleBasicBlock* _block;
  JeandleVMState* _jvm;

  // Contains all blocks to interpret. Sorted by reverse-post-order.
  llvm::SmallVector<JeandleBasicBlock*> _work_list;

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
  void table_switch();
  void invoke();
  bool inline_intrinsic(const ciMethod* target);
  void stack_op(Bytecodes::Code code);
  void shift_op(BasicType type, Bytecodes::Code code);
  void instanceof(int klass_index);
  void arith_op(BasicType type, Bytecodes::Code code);

  llvm::CallInst* call_java_op(llvm::StringRef java_op, llvm::ArrayRef<llvm::Value*> args);
  llvm::CallInst* call_jeandle_routine(llvm::FunctionCallee callee, llvm::ArrayRef<llvm::Value*> arg, llvm::CallingConv::ID calling_conv);

  void add_safepoint_poll();

  llvm::SmallVector<JeandleBasicBlock*>& bci2block() { return _block_builder->bci2block(); }

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
  llvm::Value* load_from_address(llvm::Value* addr, BasicType type, bool is_volatile);
  void store_to_address(llvm::Value* addr, llvm::Value* value, BasicType type, bool is_volatile);

  void do_get_xxx(ciField* field, bool is_static);
  void do_put_xxx(ciField* field, bool is_static);

  void arraylength();

  // Implementation of array *aload and *astore bytecodes.
  void do_array_load(Bytecodes::Code code);
  void do_array_store(Bytecodes::Code code);
  llvm::Value* do_array_load_inner(BasicType basic_type, llvm::Type* load_type);
  void do_array_store_inner(BasicType basic_type, llvm::Type* store_type, llvm::Value* value);
  llvm::Value* compute_array_element_address(BasicType basic_type, llvm::Type* type);

  typedef struct {
    llvm::BasicBlock* _unwind_dest;
    llvm::BasicBlock* _normal_dest;
  } DispatchedDest;

  DispatchedDest dispatch_exception_for_invoke(); // Dispatch exceptions raised by invoke.
  void dispatch_exception_to_handler(llvm::Value* exception_oop); // Generate a series of IR to dispatch an exception to its handler.
  void throw_exception(llvm::Value* exception_oop);

  void newarray(int element_type);

  // Implementation of _new
  void do_new();

  void monitorenter();
  void monitorexit();

  void null_check(llvm::Value* obj);
};

#endif // SHARE_JEANDLE_ABSTRACT_INTERPRETER_HPP
