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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/Target/TargetMachine.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allStatic.hpp"
#include "runtime/javaThread.hpp"
#include "utilities/globalDefinitions.hpp"

//------------------------------------------------------------------------------------------------------------
//   |        c_func            |       return_type             |                    arg_types
//------------------------------------------------------------------------------------------------------------
#define ALL_JEANDLE_C_ROUTINES(def)                                                                                                             \
  def(safepoint_handler,          llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(install_exceptional_return, llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(new_instance,               llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(new_typeArray,              llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))

#define ALL_JEANDLE_ASSEMBLY_ROUTINES(def) \
  def(exceptional_return)                  \
  def(exception_handler)

//-----------------------------------------------------------------------------------------------------------------------------------
//    name                                       | func_entry             | return_type                        | arg_types
//-----------------------------------------------------------------------------------------------------------------------------------
#define ALL_HOTSPOT_ROUTINES(def)                                                                                                                         \
  def(SharedRuntime_dsin,                         SharedRuntime::dsin,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dsin,                          StubRoutines::dsin(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dcos,                         SharedRuntime::dcos,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dcos,                          StubRoutines::dcos(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dtan,                         SharedRuntime::dtan,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dtan,                          StubRoutines::dtan(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_drem,                         SharedRuntime::drem,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context),         \
                                                                                                                llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_frem,                         SharedRuntime::frem,     llvm::Type::getFloatTy(context),     llvm::Type::getFloatTy(context),          \
                                                                                                                llvm::Type::getFloatTy(context))          \
                                                                                                                                                          \
  def(SharedRuntime_complete_monitor_locking_C,   SharedRuntime::complete_monitor_locking_C, llvm::Type::getVoidTy(context),                                             \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                                         \
  def(SharedRuntime_complete_monitor_unlocking_C, SharedRuntime::complete_monitor_unlocking_C, llvm::Type::getVoidTy(context),                                           \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))


// JeandleRuntimeRoutine contains C/C++/Assembly routines and Hotspot routines that can be called from Jeandle compiled code.
// (Hotspot routines are some runtime functions provided by Hotspot. We can call them in Jeandle compiled code.)
//
// There are two ways to call a JeandleRuntimeRoutine: directly calling an assembly/Hotspot routine or calling a C/C++ routine
// through a runtime stub.
//
// For assembly/Hotspot routines, we can directly use their addresses to generate function calls in LLVM IR.
//
// For C/C++ routines, before jumping into the C/C++ function, we use a runtime stub to help adjust the VM state similar to
// what C2's GraphKit::gen_stub does, then the runtime stub uses the C/C++ function address to generate a function calling
// into it. The runtime stubs are compiled by LLVM for every C/C++ routine by JeandleCallVM.
class JeandleRuntimeRoutine : public AllStatic {
 public:
  // Generate all routines.
  static bool generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout);

  static address get_routine_entry(llvm::StringRef name) {
    assert(_routine_entry.contains(name), "invalid runtime routine: %s", name.str().c_str());
    return _routine_entry.lookup(name);
  }

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

// Define all assembly routine names.
#define DEF_ASSEMBLY_ROUTINE_NAME(name) \
  static constexpr const char* _##name = #name;

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_ASSEMBLY_ROUTINE_NAME);

#define DEF_HOTSPOT_ROUTINE_CALLEE(name, func_entry, return_type, ...)                          \
  static llvm::FunctionCallee hotspot_##name##_callee(llvm::Module& target_module) {            \
    llvm::LLVMContext& context = target_module.getContext();                                    \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false); \
    llvm::FunctionCallee callee = target_module.getOrInsertFunction(#name, func_type);          \
    llvm::cast<llvm::Function>(callee.getCallee())->setCallingConv(llvm::CallingConv::C);       \
    return callee;                                                                              \
  }

  ALL_HOTSPOT_ROUTINES(DEF_HOTSPOT_ROUTINE_CALLEE);

 private:
  static llvm::StringMap<address> _routine_entry; // All the routines.

  // C/C++ routine implementations:

  static void safepoint_handler(JavaThread* current);

  static void install_exceptional_return(oopDesc* exception, JavaThread* current);

  static address get_exception_handler(JavaThread* current);

  static address search_landingpad(JavaThread* current);

  // Array allocation routine
  static void new_typeArray(int type, int length, JavaThread* current);
  static void new_instance(InstanceKlass* klass, JavaThread* current);

  // Assembly routine implementations:

#define DEF_GENERETE_ASSEMBLY_ROUTINE(name) \
  static void generate_##name();

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_GENERETE_ASSEMBLY_ROUTINE);
};

#endif // SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
