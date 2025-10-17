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
#include "oops/arrayOop.hpp"
#include "oops/array.hpp"
#include "oops/klass.hpp"
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
  };

  llvm::Type* int32_type = llvm::Type::getInt32Ty(context);

  define_global("ArrayKlass.base_offset_in_bytes",     int32_type, static_cast<uint64_t>(Array<Klass*>::base_offset_in_bytes()));
  define_global("ArrayKlass.length_offset_in_bytes",   int32_type, static_cast<uint64_t>(Array<Klass*>::length_offset_in_bytes()));
  define_global("arrayOopDesc.length_offset_in_bytes", int32_type, static_cast<uint64_t>(arrayOopDesc::length_offset_in_bytes()));
  define_global("Klass.secondary_super_cache_offset",  int32_type, static_cast<uint64_t>(Klass::secondary_super_cache_offset()));
  define_global("Klass.secondary_supers_offset",       int32_type, static_cast<uint64_t>(Klass::secondary_supers_offset()));
  define_global("Klass.super_check_offset_offset",     int32_type, static_cast<uint64_t>(Klass::super_check_offset_offset()));
  define_global("oopDesc.klass_offset_in_bytes",       int32_type, static_cast<uint64_t>(oopDesc::klass_offset_in_bytes()));
}
