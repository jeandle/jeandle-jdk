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

#include <cassert>
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"

#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

llvm::Function* JeandleFuncSig::create_llvm_func(ciMethod* method, llvm::Module& target_module) {
  llvm::SmallVector<llvm::Type*> args;
  llvm::LLVMContext& context = target_module.getContext();

  // Reciever is the first argument.
  if (!method->is_static()) {
    args.push_back(JeandleType::java2llvm(BasicType::T_OBJECT, context));
  }

  ciSignature* sig = method->signature();
  for (int i = 0; i < sig->count(); i++) {
    args.push_back(JeandleType::java2llvm(sig->type_at(i)->basic_type(), context));
  }

  llvm::FunctionType* func_type =
      llvm::FunctionType::get(JeandleType::java2llvm(sig->return_type()->basic_type(), context),
                              args,
                              false);
  llvm::Function* func = llvm::Function::Create(func_type,
                                                llvm::Function::ExternalLinkage,
                                                method_name(method),
                                                target_module);

  setup_description(func);

  return func;
}

std::string JeandleFuncSig::method_name(ciMethod* method) {
  std::string class_name = std::string(method->holder()->name()->as_utf8());
  std::replace(class_name.begin(), class_name.end(), '/', '_');

  std::string method_name = std::string(method->name()->as_utf8());
  std::replace(method_name.begin(), method_name.end(), '/', '_');

  std::string sig_name = std::string(method->signature()->as_symbol()->as_utf8());
  std::replace(sig_name.begin(), sig_name.end(), '/', '_');

  return class_name
         + "_" + method_name
         + "_" + sig_name;
}

void JeandleFuncSig::setup_description(llvm::Function* func) {
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  func->setGC(llvm::jeandle::JeandleGC);

  if (UseCompressedOops) {
    func->addFnAttr(llvm::Attribute::get(func->getContext(), llvm::jeandle::Attribute::UseCompressedOops));
  }
}
