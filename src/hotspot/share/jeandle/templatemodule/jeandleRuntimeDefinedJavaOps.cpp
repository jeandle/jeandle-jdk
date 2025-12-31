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
#include "llvm/IR/BasicBlock.h"
#include "llvm/IR/IRBuilder.h"

#include "jeandle/templatemodule/jeandleRuntimeDefinedJavaOps.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleRegister.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciUtilities.hpp"
#include "gc/shared/cardTable.hpp"
#include "gc/shared/gc_globals.hpp"
#include "gc/shared/tlab_globals.hpp"
#include "oops/arrayOop.hpp"
#include "oops/array.hpp"
#include "oops/klass.hpp"
#include "oops/objArrayKlass.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/safepointMechanism.hpp"

//                  name, lower_phase, return_type, arg_types
#define DEF_JAVA_OP(name, lower_phase, return_type, ...)                                        \
  void define_##name(llvm::Module& template_module) {                                           \
    if (RuntimeDefinedJavaOps::failed()) { return; }                                            \
    llvm::LLVMContext& context = template_module.getContext();                                  \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false); \
    llvm::StringRef func_name = "jeandle."#name;                                                \
    llvm::Function* func = llvm::cast<llvm::Function>(                                          \
        template_module.getOrInsertFunction(func_name, func_type).getCallee());                 \
    func->setLinkage(llvm::Function::PrivateLinkage);                                           \
    func->addFnAttr("lower-phase", #lower_phase);                                               \
    func->addFnAttr(llvm::Attribute::NoInline);                                                 \
    func->setCallingConv(llvm::CallingConv::Hotspot_JIT);                                       \
    llvm::BasicBlock* entry_block = llvm::BasicBlock::Create(context, "entry", func);           \
    llvm::IRBuilder<> ir_builder(entry_block);

#define JAVA_OP_END }

namespace {

DEF_JAVA_OP(current_thread, 0, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))
  llvm::NamedMDNode* thread_register = template_module.getNamedMetadata(llvm::jeandle::Metadata::CurrentThread);
  assert(thread_register != nullptr, "current_thread metadata must exist");
  llvm::Value* read_register_args[] = {llvm::MetadataAsValue::get(context, thread_register->getOperand(0))};
  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());
  llvm::CallInst* current_thread_value = ir_builder.CreateIntrinsic(llvm::Intrinsic::read_register,
                                                                    intptr_type,
                                                                    read_register_args);
  llvm::Value* current_thread_ptr = ir_builder.CreateIntToPtr(current_thread_value,
                                                              llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace));
  ir_builder.CreateRet(current_thread_ptr);
JAVA_OP_END

DEF_JAVA_OP(safepoint_poll, 1, llvm::Type::getVoidTy(context))
  llvm::BasicBlock* return_block = llvm::BasicBlock::Create(context, "return", func);
  llvm::BasicBlock* do_safepoint_block = llvm::BasicBlock::Create(context, "do_safepoint", func);

  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());

  // ******** Entry Block *********
  // Get the poll word pointer.
  llvm::Value* poll_word_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::polling_word_offset()),
                                                         llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  // Do poll.
  llvm::Value* poll_word = ir_builder.CreateLoad(intptr_type, poll_word_ptr, true /* is_volatile */);
  llvm::Value* if_safepoint = ir_builder.CreateICmp(llvm::CmpInst::ICMP_EQ,
                                                    poll_word,
                                                    llvm::ConstantInt::get(intptr_type, ~SafepointMechanism::poll_bit()));
  ir_builder.CreateCondBr(if_safepoint, return_block, do_safepoint_block);

  // ***** Do Safepoint Block *****
  ir_builder.SetInsertPoint(do_safepoint_block);
  // Get the current thread pointer as the safepoint handler's argument.
  llvm::Function* current_thread_func = template_module.getFunction("jeandle.current_thread");
  if (!current_thread_func) {
    RuntimeDefinedJavaOps::set_failed("jeandle.current_thread is not found in template module");
    return;
  }
  llvm::CallInst* current_thread = ir_builder.CreateCall(current_thread_func);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  // Call safepoint handler.
  llvm::CallInst* call_inst = ir_builder.CreateCall(JeandleRuntimeRoutine::safepoint_handler_callee(template_module), {current_thread});
  call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  ir_builder.CreateBr(return_block);

  // ******** Return Block ********
  ir_builder.SetInsertPoint(return_block);
  ir_builder.CreateRetVoid();
JAVA_OP_END

DEF_JAVA_OP(card_table_barrier, 1, llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* obj_addr = func->getArg(0);
  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());
  llvm::Value* obj_ptr = ir_builder.CreatePtrToInt(obj_addr, intptr_type);

  // Find the card table address.
  llvm::Value* card_table_offset = ir_builder.CreateLShr(obj_ptr, llvm::ConstantInt::get(intptr_type, (uint64_t)CardTable::card_shift()));

  llvm::Value* card_table_base_addr = ir_builder.CreateIntToPtr(llvm::ConstantInt::get(intptr_type, (uint64_t)ci_card_table_address()),
                                                                llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace));

  llvm::Value* card_table_addr = ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(context), card_table_base_addr, card_table_offset);

  // Store dirty value to card table.
  CardTable::CardValue dirty_value = CardTable::dirty_card_val();
  if (UseCondCardMark) {
    llvm::BasicBlock* already_dirty_block = llvm::BasicBlock::Create(context, "already_dirty", func);
    llvm::BasicBlock* store_dirty_block = llvm::BasicBlock::Create(context, "store_dirty", func);
    llvm::Value* card_value = ir_builder.CreateLoad(ir_builder.getInt8Ty(), card_table_addr);

    // Card is already dirty, skip storing dirty value.
    llvm::Value* if_already_dirty = ir_builder.CreateICmp(llvm::CmpInst::ICMP_EQ,
                                                          card_value,
                                                          llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value));
    ir_builder.CreateCondBr(if_already_dirty, already_dirty_block, store_dirty_block);

    // Card is not dirty, store dirty value.
    ir_builder.SetInsertPoint(store_dirty_block);
    llvm::StoreInst* store_inst = ir_builder.CreateStore(llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value), card_table_addr);
    store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
    ir_builder.CreateBr(already_dirty_block);

    // Card is dirty, return.
    ir_builder.SetInsertPoint(already_dirty_block);
  } else {
    llvm::StoreInst* store_inst = ir_builder.CreateStore(llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value), card_table_addr);
    store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
  }

  ir_builder.CreateRetVoid();
JAVA_OP_END

DEF_JAVA_OP(new_instance, 1, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace), // klass
            llvm::Type::getInt32Ty(context)) // size_in_bytes
  // Entry
  llvm::Value* klass = func->getArg(0);
  llvm::Value* size = func->getArg(1);
  // Get current thread pointer using jeandle.current_thread JavaOp
  llvm::Function* current_thread_func = template_module.getFunction("jeandle.current_thread");
  if (!current_thread_func) {
    RuntimeDefinedJavaOps::set_failed("jeandle.current_thread is not found in template module");
    return;
  }
  llvm::CallInst* current_thread = ir_builder.CreateCall(current_thread_func);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  // TLAB check
  llvm::BasicBlock* alloc_slow_path = llvm::BasicBlock::Create(context, "alloc_slow_path", func);
  llvm::BasicBlock* alloc_fast_path = llvm::BasicBlock::Create(context, "alloc_fast_path", func);
  llvm::BasicBlock* return_block = llvm::BasicBlock::Create(context, "return_block", func);
  llvm::Type*  oop_type = llvm::PointerType::get(context, llvm::jeandle::JavaHeapAddrSpace);
  llvm::Value* tlab_end_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::tlab_end_offset()),
                                                        llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  llvm::Value* tlab_top_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::tlab_top_offset()),
                                                        llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  llvm::Value* tlab_old_top = ir_builder.CreateLoad(oop_type, tlab_top_ptr);
  llvm::Value* tlab_end = ir_builder.CreateLoad(oop_type, tlab_end_ptr);
  llvm::Value* tlab_new_top = ir_builder.CreatePtrAdd(tlab_old_top, size);

  llvm::Value* if_tlab_full = ir_builder.CreateICmp(llvm::CmpInst::ICMP_UGE, tlab_new_top, tlab_end);
  ir_builder.CreateCondBr(if_tlab_full, alloc_slow_path, alloc_fast_path);

  // Alloc Fast Path
  ir_builder.SetInsertPoint(alloc_fast_path);
  ir_builder.CreateStore(tlab_new_top, tlab_top_ptr);

  // TODO: prefetch

  // initialize object header
  llvm::Value* alloc_oop = ir_builder.CreateIntToPtr(tlab_old_top, oop_type);
  llvm::Value* mark_word = ir_builder.getInt64(markWord::prototype().value()); // TODO: uint32_t for CompactObjectHeaders, it' not available in JDK21
  llvm::Value* mark_word_ptr = ir_builder.CreateIntToPtr(ir_builder.CreatePtrAdd(alloc_oop, ir_builder.getInt32(oopDesc::mark_offset_in_bytes())), oop_type);
  llvm::Value* klass_ptr = ir_builder.CreateIntToPtr(ir_builder.CreatePtrAdd(alloc_oop, ir_builder.getInt32(oopDesc::klass_offset_in_bytes())), oop_type);
  ir_builder.CreateStore(mark_word, mark_word_ptr);
  ir_builder.CreateStore(klass, klass_ptr);

  // clear memory
  if (!(UseTLAB && ZeroTLAB)) {
    int header_size = instanceOopDesc::base_offset_in_bytes();
    llvm::Value *base_ptr = ir_builder.CreateIntToPtr(ir_builder.CreatePtrAdd(alloc_oop, ir_builder.getInt32(header_size)), oop_type);
    int alignment = 8;  // TODO: maybe change with other runtime option
    ir_builder.CreateMemSet(base_ptr, ir_builder.getInt8(0), size, llvm::MaybeAlign(alignment));
  }

  // storestore membar for initialzation
  ir_builder.CreateFence(llvm::AtomicOrdering::Release);

  ir_builder.CreateBr(return_block);

  // Alloc Slow Path
  ir_builder.SetInsertPoint(alloc_slow_path);
  llvm::CallInst* call_result = ir_builder.CreateCall(JeandleRuntimeRoutine::new_instance_callee(template_module), {klass, current_thread});
  call_result->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  ir_builder.CreateBr(return_block);

  // Return
  ir_builder.SetInsertPoint(return_block);
  llvm::PHINode* phi = ir_builder.CreatePHI(oop_type, 2);
  phi->addIncoming(alloc_oop, alloc_fast_path);
  phi->addIncoming(call_result, alloc_slow_path);
  ir_builder.CreateRet(phi);
JAVA_OP_END

} // anonymous namespace

const char* RuntimeDefinedJavaOps::_error_msg = nullptr;

bool RuntimeDefinedJavaOps::define_all(llvm::Module& template_module) {
  reset_state();

  // Define all necessary metadata nodes:
  define_metadata(template_module);

  // Define all global variables.
  define_global_variables(template_module);

  // Define all runtime defined JavaOps:
  define_current_thread(template_module);
  define_safepoint_poll(template_module);
  define_card_table_barrier(template_module);
  define_new_instance(template_module);

  return failed();
}

void RuntimeDefinedJavaOps::define_metadata(llvm::Module& template_module) {
  llvm::LLVMContext& context = template_module.getContext();

  // Current thread register:
  {
    llvm::MDNode* thread_register = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_current_thread_pointer())});
    llvm::NamedMDNode* metadata_node = template_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::CurrentThread);
    metadata_node->addOperand(thread_register);
  }

  // Stack pointer register:
  {
    llvm::MDNode* stack_pointer = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_stack_pointer())});
    llvm::NamedMDNode* metadata_node = template_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::StackPointer);
    metadata_node->addOperand(stack_pointer);
  }
}

void RuntimeDefinedJavaOps::define_global_variables(llvm::Module& template_module) {
  llvm::LLVMContext& context = template_module.getContext();
  llvm::IRBuilder<> ir_builder(context);

  // Define a global variable in template module.
  auto define_global = [&](const llvm::StringRef name, llvm::Type* type, uint64_t value) {
    llvm::GlobalVariable* global_var = template_module.getGlobalVariable(name);
    assert(global_var && global_var->isDeclaration(), "unexpected declaration");

    global_var->setInitializer(llvm::ConstantInt::get(type, value));
    global_var->setConstant(true);
    global_var->setLinkage(llvm::GlobalValue::PrivateLinkage);
  };

  llvm::Type* int32_type = llvm::Type::getInt32Ty(context);

  define_global("KlassArray.base_offset_in_bytes",     int32_type, static_cast<uint64_t>(Array<Klass*>::base_offset_in_bytes()));
  define_global("KlassArray.length_offset_in_bytes",   int32_type, static_cast<uint64_t>(Array<Klass*>::length_offset_in_bytes()));
  define_global("arrayOopDesc.length_offset_in_bytes", int32_type, static_cast<uint64_t>(arrayOopDesc::length_offset_in_bytes()));
  define_global("arrayOopDesc.base_offset_in_bytes.int", int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_INT)));
  define_global("Klass.secondary_super_cache_offset",  int32_type, static_cast<uint64_t>(Klass::secondary_super_cache_offset()));
  define_global("Klass.secondary_supers_offset",       int32_type, static_cast<uint64_t>(Klass::secondary_supers_offset()));
  define_global("Klass.super_check_offset_offset",     int32_type, static_cast<uint64_t>(Klass::super_check_offset_offset()));
  define_global("ObjArrayKlass.element_klass_offset",  int32_type, static_cast<uint64_t>(ObjArrayKlass::element_klass_offset()));
  define_global("oopDesc.klass_offset_in_bytes",       int32_type, static_cast<uint64_t>(oopDesc::klass_offset_in_bytes()));
}
