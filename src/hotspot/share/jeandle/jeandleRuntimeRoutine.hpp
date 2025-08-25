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

#ifndef SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
#define SHARE_JEANDLE_RUNTIME_ROUTINE_HPP

#include <cassert>
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/Target/TargetMachine.h"

#include "utilities/debug.hpp"
#include "memory/allStatic.hpp"
#include "runtime/javaThread.hpp"
#include "utilities/globalDefinitions.hpp"

//------------------------------------------------------------------------------------------------------
//   |     c_func      |       return_type             |                    args_type
//------------------------------------------------------------------------------------------------------
#define ALL_JEANDLE_ROUTINES(def)                                                                                                     \
  def(safepoint_handler, llvm::Type::getVoidTy(context), {llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace)})

// JeandleRuntimeRoutine contains C/C++ functions that can be called from Jeandle compiled code.
class JeandleRuntimeRoutine : public AllStatic {
 public:
  // Generate all routine stubs.
  static bool generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout);

// Define all routines' llvm::FunctionCallee.
#define DEF_LLVM_CALLEE(c_func, return_type, args_type)                                             \
  static llvm::FunctionCallee c_func##_callee(llvm::Module& target_module) {                        \
    llvm::LLVMContext& context = target_module.getContext();                                        \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, args_type, false);         \
    llvm::FunctionCallee callee = target_module.getOrInsertFunction(#c_func, func_type);            \
    llvm::cast<llvm::Function>(callee.getCallee())->setCallingConv(llvm::CallingConv::Hotspot_JIT); \
    return callee;                                                                                  \
  }

  ALL_JEANDLE_ROUTINES(DEF_LLVM_CALLEE);

  static address get_stub_entry(llvm::StringRef name) {
    assert(_stub_entry.contains(name), "invalid runtime routine");
    return _stub_entry.lookup(name);
  }

 private:
  static llvm::StringMap<address> _stub_entry;

  static void safepoint_handler(JavaThread* current);
};

#endif // SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
