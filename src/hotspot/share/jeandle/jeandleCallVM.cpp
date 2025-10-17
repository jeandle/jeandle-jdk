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
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Jeandle/GCStrategy.h"

#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandleCallVM.hpp"
#include "jeandle/jeandleRegister.hpp"

void JeandleCallVM::generate_call_VM(const char* name, address c_func, llvm::FunctionType* func_type, llvm::Module& target_module, JeandleCompiledCode& code) {
  llvm::Function* llvm_func = llvm::Function::Create(func_type,
                                                     llvm::Function::ExternalLinkage,
                                                     name,
                                                     target_module);
  JeandleFuncSig::setup_description(llvm_func, true /* is_stub */);
  llvm::LLVMContext& context = target_module.getContext();

  // Add needed metadatas.
  llvm::MDNode* thread_register = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_current_thread_pointer())});
  llvm::NamedMDNode* metadata_node = target_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::CurrentThread);
  metadata_node->addOperand(thread_register);

  llvm::BasicBlock* entry_block = llvm::BasicBlock::Create(context, "entry", llvm_func);
  llvm::IRBuilder<> ir_builder(entry_block);
  llvm::Type* intptr_type = ir_builder.getIntPtrTy(target_module.getDataLayout());

  // Set the last_Java_sp to enable stack unwind.
  llvm::Value* last_Java_sp_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::last_Java_sp_offset()),
                                                            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  llvm::MDNode* sp_register = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_stack_pointer())});
  llvm::Value* read_sp_args[] = {llvm::MetadataAsValue::get(context, sp_register)};
  llvm::CallInst* sp_value = ir_builder.CreateIntrinsic(llvm::Intrinsic::read_register,
                                                        intptr_type,
                                                        read_sp_args);
  ir_builder.CreateStore(sp_value, last_Java_sp_ptr);

  // Make arguments.
  llvm::SmallVector<llvm::Value*> args;
  for (llvm::Value& arg : llvm_func->args()) {
    args.push_back(&arg);
  }

  // Call to the C function.
  llvm::PointerType* c_func_ptr_type = llvm::PointerType::get(func_type, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* c_func_addr = ir_builder.getInt64((intptr_t)c_func);
  llvm::Value* c_func_ptr = ir_builder.CreateIntToPtr(c_func_addr, c_func_ptr_type);
  llvm::CallInst* call_c_func = ir_builder.CreateCall(func_type, c_func_ptr, args);
  call_c_func->setCallingConv(llvm::CallingConv::C);
  JeandleCompiledCall::Type call_type = JeandleCompiledCall::STUB_C_CALL;
  uint64_t statepoint_id = code.next_statepoint_id();
  code.push_non_routine_call_site(new CallSiteInfo(call_type, c_func, -1 /* bci */, statepoint_id));
  llvm::Attribute id_attr = llvm::Attribute::get(context,
                                                 llvm::jeandle::Attribute::StatepointID,
                                                 std::to_string(statepoint_id));
  llvm::Attribute patch_bytes_attr = llvm::Attribute::get(context,
                                                          llvm::jeandle::Attribute::StatepointNumPatchBytes,
                                                          std::to_string(JeandleCompiledCall::call_site_patch_size(call_type)));
  call_c_func->addFnAttr(id_attr);
  call_c_func->addFnAttr(patch_bytes_attr);

  // TODO: Check exceptions.

  // Clear the last_Java_sp.
  ir_builder.CreateStore(ir_builder.getInt64((intptr_t)nullptr), last_Java_sp_ptr);

  // Clear the last_Java_pc.
  llvm::Value* last_Java_pc_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::last_Java_pc_offset()),
                                                            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  ir_builder.CreateStore(ir_builder.getInt64((intptr_t)nullptr), last_Java_pc_ptr);

  // Return.
  if (func_type->getReturnType()->isVoidTy()) {
    ir_builder.CreateRetVoid();
    return;
  }

  llvm::Value* ret_val = call_c_func;

  // If the return type is a Java object, we need to load it from vm_result of JavaThread.
  llvm::PointerType* pointer_type = llvm::dyn_cast<llvm::PointerType>(func_type->getReturnType());
  if (pointer_type && pointer_type->getAddressSpace() == llvm::jeandle::AddrSpace::JavaHeapAddrSpace) {
    llvm::Value* vm_result_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::vm_result_offset()),
                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
    ret_val = ir_builder.CreateLoad(pointer_type, vm_result_ptr);
  }

  ir_builder.CreateRet(ret_val);
}
