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
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/Type.h"

#include "jeandle/jeandleJavaCall.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "utilities/debug.hpp"
#include "code/nativeInst.hpp"

llvm::FunctionCallee JeandleJavaCall::callee(llvm::Module& target_module,
                                             ciMethod* target,
                                             llvm::Type* return_type,
                                             std::vector<llvm::Type*>& args_type) {
  llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, args_type, false);
  llvm::FunctionCallee callee = target_module.getOrInsertFunction(JeandleFuncSig::method_name(target), func_type);

  llvm::Function* func = llvm::cast<llvm::Function>(callee.getCallee());
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  func->setGC(llvm::jeandle::JeandleGC);

  return callee;
}

int JeandleJavaCall::call_site_size(JeandleJavaCall::Type call_type) {
  // STATIC_CALL
  if (call_type == JeandleJavaCall::Type::STATIC_CALL) {
    return NativeJump::instruction_size;
  }

  // DYNAMIC_CALL
  return NativeJump::instruction_size + NativeMovConstReg::instruction_size;
}
