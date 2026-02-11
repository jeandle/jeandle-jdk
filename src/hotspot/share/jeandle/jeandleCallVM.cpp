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
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"

void JeandleCallVM::generate_call_VM(const char* name, address routine_address, llvm::FunctionType* func_type, llvm::Module& target_module, JeandleCompiledCode& code) {
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
  llvm::PointerType* routine_address_ptr_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* routine_address_addr = ir_builder.getInt64((intptr_t)routine_address);
  llvm::Value* routine_address_ptr = ir_builder.CreateIntToPtr(routine_address_addr, routine_address_ptr_type);
  llvm::CallInst* call_routine_address = ir_builder.CreateCall(func_type, routine_address_ptr, args);
  call_routine_address->setCallingConv(llvm::CallingConv::C);
  JeandleCompiledCall::Type call_type = JeandleCompiledCall::STUB_C_CALL;
  uint64_t statepoint_id = code.next_statepoint_id();
  code.push_non_routine_call_site(new CallSiteInfo(call_type, routine_address, -1 /* bci */, false /* _has_deopt_operands */, statepoint_id));
  llvm::Attribute id_attr = llvm::Attribute::get(context,
                                                 llvm::jeandle::Attribute::StatepointID,
                                                 std::to_string(statepoint_id));
  llvm::Attribute patch_bytes_attr = llvm::Attribute::get(context,
                                                          llvm::jeandle::Attribute::StatepointNumPatchBytes,
                                                          std::to_string(JeandleCompiledCall::call_site_patch_size(call_type)));
  call_routine_address->addFnAttr(id_attr);
  call_routine_address->addFnAttr(patch_bytes_attr);

  // Check exceptions.
  llvm::Value* pending_exception_addr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)Thread::pending_exception_offset()),
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  llvm::Value* pending_exception = ir_builder.CreateLoad(JeandleType::java2llvm(T_OBJECT, context), pending_exception_addr);
  llvm::Value* null_oop = llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(JeandleType::java2llvm(T_OBJECT, context)));
  llvm::Value* if_not_null = ir_builder.CreateICmp(llvm::CmpInst::ICMP_NE, pending_exception, null_oop);

  llvm::BasicBlock* forward_exception_block = llvm::BasicBlock::Create(context, "forward_exception", llvm_func);
  llvm::BasicBlock* no_exception_block = llvm::BasicBlock::Create(context, "no_exception", llvm_func);

  ir_builder.CreateCondBr(if_not_null, forward_exception_block, no_exception_block);
  ir_builder.SetInsertPoint(forward_exception_block);

  llvm::CallInst* call_inst = ir_builder.CreateCall(JeandleRuntimeRoutine::install_exceptional_return_for_call_vm_callee(target_module), {});
  call_inst->setCallingConv(llvm::CallingConv::C);

  // Return
  llvm::Type* ret_type = func_type->getReturnType();
  if (ret_type->isVoidTy()) {
    ir_builder.CreateRetVoid();;
  } else if (ret_type->isIntegerTy()) {
    ir_builder.CreateRet(llvm::ConstantInt::get(ret_type, 0));
  } else if (ret_type->isFloatTy() || ret_type->isDoubleTy()) {
    ir_builder.CreateRet(llvm::ConstantFP::get(ret_type, 0.0));
  } else if (ret_type->isPointerTy()) {
    ir_builder.CreateRet(llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(ret_type)));
  } else {
    ShouldNotReachHere();
  }

  ir_builder.SetInsertPoint(no_exception_block);

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

  llvm::Value* ret_val = call_routine_address;

  // If the return type is a Java object, we need to load it from vm_result of JavaThread.
  llvm::PointerType* pointer_type = llvm::dyn_cast<llvm::PointerType>(func_type->getReturnType());
  if (pointer_type && pointer_type->getAddressSpace() == llvm::jeandle::AddrSpace::JavaHeapAddrSpace) {
    llvm::Value* vm_result_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::vm_result_offset()),
                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
    ret_val = ir_builder.CreateLoad(pointer_type, vm_result_ptr);
    // Clear the vm_result.
    ir_builder.CreateStore(ir_builder.getInt64((intptr_t)nullptr), vm_result_ptr);
  }

  ir_builder.CreateRet(ret_val);
}
