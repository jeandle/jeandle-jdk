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
#pragma push_macro("AARCH64")
#undef AARCH64
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/Target/TargetMachine.h"
#pragma pop_macro("AARCH64")

#include "utilities/debug.hpp"
#include "memory/allStatic.hpp"
#include "runtime/javaThread.hpp"
#include "utilities/globalDefinitions.hpp"

//------------------------------------------------------------------------------------------------------------
//   |        c_func            |       return_type             |                    arg_types
//------------------------------------------------------------------------------------------------------------
#define ALL_JEANDLE_C_ROUTINES(def)                                                                                                             \
  def(safepoint_handler,          llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
  def(install_exceptional_return, llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))

#define ALL_JEANDLE_ASSEMBLY_ROUTINES(def) \
  def(exceptional_return)

// JeandleRuntimeRoutine contains C/C++/Assembly routines that can be called from Jeandle compiled code.
class JeandleRuntimeRoutine : public AllStatic {
 public:
  // Generate all routine stubs.
  static bool generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout);

// Define all routines' llvm::FunctionCallee.
#define DEF_LLVM_CALLEE(c_func, return_type, ...)                                                   \
  static llvm::FunctionCallee c_func##_callee(llvm::Module& target_module) {                        \
    llvm::LLVMContext& context = target_module.getContext();                                        \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);     \
    llvm::FunctionCallee callee = target_module.getOrInsertFunction(#c_func, func_type);            \
    llvm::cast<llvm::Function>(callee.getCallee())->setCallingConv(llvm::CallingConv::Hotspot_JIT); \
    return callee;                                                                                  \
  }

  ALL_JEANDLE_C_ROUTINES(DEF_LLVM_CALLEE);

  static address get_routine_entry(llvm::StringRef name) {
    assert(_routine_entry.contains(name), "invalid runtime routine");
    return _routine_entry.lookup(name);
  }

 private:
  static llvm::StringMap<address> _routine_entry; // All the routines.

  // C/C++ routine implementations:

  static void safepoint_handler(JavaThread* current);

  static void install_exceptional_return(oopDesc* exception, JavaThread* current);

  static address get_exception_handler(JavaThread* current);

  // Assembly routine implementations:

#define DEF_ASSEMBLY_ROUTINE(name)               \
  static void generate_##name();                 \
  static constexpr const char* _##name = #name;

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_ASSEMBLY_ROUTINE);
};

#endif // SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
