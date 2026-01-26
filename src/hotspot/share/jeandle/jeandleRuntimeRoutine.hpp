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
#include "llvm/IR/Constants.h"
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Module.h"
#include "llvm/Target/TargetMachine.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allStatic.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/sharedRuntime.hpp"
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
  def(new_array,                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(multianewarray2,            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(multianewarray3,            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(multianewarray4,            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(multianewarray5,            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::Type::getInt32Ty(context),                                              \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                \
  def(multianewarrayN,            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),                                 \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                  llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \

#define ALL_JEANDLE_ASSEMBLY_ROUTINES(def) \
  def(exceptional_return)                  \
  def(exception_handler)

//-----------------------------------------------------------------------------------------------------------------------------------
//    name                                       | func_entry             | return_type                        | arg_types
//-----------------------------------------------------------------------------------------------------------------------------------
#define ALL_HOTSPOT_ROUTINES(def)                                                                                                                         \
  def(StubRoutines_dsin,                          StubRoutines::dsin(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dcos,                          StubRoutines::dcos(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dtan,                          StubRoutines::dtan(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dlog,                          StubRoutines::dlog(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dlog10,                        StubRoutines::dlog10(),    llvm::Type::getDoubleTy(context),  llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dexp,                          StubRoutines::dexp(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(StubRoutines_dpow,                          StubRoutines::dpow(),    llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context),         \
                                                                                                                llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(uncommon_trap, SharedRuntime::uncommon_trap_blob()->entry_point(),   llvm::Type::getVoidTy(context),      llvm::Type::getInt32Ty(context))          \
                                                                                                                                                          \
  def(SharedRuntime_complete_monitor_locking_C,   SharedRuntime::complete_monitor_locking_C, llvm::Type::getVoidTy(context),                                             \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                                         \
  def(SharedRuntime_complete_monitor_unlocking_C, SharedRuntime::complete_monitor_unlocking_C, llvm::Type::getVoidTy(context),                                           \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                                                                                                         \
 def(SharedRuntime_throw_NullPointerException,    SharedRuntime::throw_NullPointerException, llvm::Type::getVoidTy(context),                                             \
                                                                                           llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \


//-----------------------------------------------------------------------------------------------------------------------------------
//    name                                       | func_entry             | return_type                        | arg_types
//-----------------------------------------------------------------------------------------------------------------------------------
#define ALL_HOTSPOT_C_FUNCTIONS(def)                                                                                                                      \
  def(SharedRuntime_dsin,                         SharedRuntime::dsin,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dcos,                         SharedRuntime::dcos,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dtan,                         SharedRuntime::dtan,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_drem,                         SharedRuntime::drem,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context),         \
                                                                                                                llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_frem,                         SharedRuntime::frem,     llvm::Type::getFloatTy(context),     llvm::Type::getFloatTy(context),          \
                                                                                                                llvm::Type::getFloatTy(context))          \
                                                                                                                                                          \
  def(SharedRuntime_dlog,                         SharedRuntime::dlog,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dlog10,                       SharedRuntime::dlog10,     llvm::Type::getDoubleTy(context),  llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dexp,                         SharedRuntime::dexp,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(SharedRuntime_dpow,                         SharedRuntime::dpow,     llvm::Type::getDoubleTy(context),    llvm::Type::getDoubleTy(context),         \
                                                                                                                llvm::Type::getDoubleTy(context))         \
                                                                                                                                                          \
  def(install_exceptional_return_for_call_vm,     JeandleRuntimeRoutine::install_exceptional_return_for_call_vm, llvm::Type::getVoidTy(context))          \


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

  static bool is_routine_entry(llvm::StringRef name) {
    return _routine_entry.contains(name);
  }

#ifdef ASSERT
  static llvm::StringMap<address> routine_entry() { return _routine_entry; }
#endif

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

#define DEF_HOTSPOT_C_FUNCTION_CALLEE(name, func_entry, return_type, ...)                                             \
  static llvm::FunctionCallee hotspot_##name##_callee(llvm::Module& target_module) {                                  \
    llvm::LLVMContext& context = target_module.getContext();                                                          \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);                       \
    llvm::GlobalValue* global_value = target_module.getNamedValue(#name);                                             \
    llvm::Constant* callee = nullptr;                                                                                 \
    if (global_value == nullptr) {                                                                                    \
      llvm::PointerType* c_func_ptr_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace); \
      llvm::Constant* c_func_addr = llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), (uint64_t)func_entry);    \
      callee = llvm::ConstantExpr::getIntToPtr(c_func_addr, c_func_ptr_type);                                         \
      llvm::GlobalAlias::create(c_func_ptr_type, llvm::jeandle::AddrSpace::CHeapAddrSpace,                            \
                                llvm::GlobalValue::ExternalLinkage, #name,                                            \
                                callee, &target_module);                                                              \
    } else if (llvm::GlobalAlias* global_alias = llvm::dyn_cast<llvm::GlobalAlias>(global_value)) {                   \
      callee = global_alias->getAliasee();                                                                            \
    }                                                                                                                 \
    assert(callee != nullptr, "callee should not be null");                                                           \
    return {func_type, callee};                                                                                       \
  }

  ALL_HOTSPOT_C_FUNCTIONS(DEF_HOTSPOT_C_FUNCTION_CALLEE);

 private:
  static llvm::StringMap<address> _routine_entry; // All the routines.

  // C/C++ routine implementations:

  static void safepoint_handler(JavaThread* current);

  // Install exceptional_return into the current java frame, for throwing exceptions.
  static void install_exceptional_return(oopDesc* exception, JavaThread* current);

  // Install exceptional_return into call_VM stub frame, for checking exceptions during call_VM.
  static void install_exceptional_return_for_call_vm();

  static address get_exception_handler(JavaThread* current);

  static address search_landingpad(JavaThread* current);

  // Array allocation routine
  static void new_instance(InstanceKlass* klass, JavaThread* current);
  static void new_array(Klass* array_type, int length, JavaThread* current);

  // Multi-dimensional array allocation routines
  static void multianewarray2(Klass* elem_type, int len1, int len2, JavaThread* current);
  static void multianewarray3(Klass* elem_type, int len1, int len2, int len3, JavaThread* current);
  static void multianewarray4(Klass* elem_type, int len1, int len2, int len3, int len4, JavaThread* current);
  static void multianewarray5(Klass* elem_type, int len1, int len2, int len3, int len4, int len5, JavaThread* current);
  static void multianewarrayN(Klass* elem_type, arrayOopDesc* dims, JavaThread* current);

  // Assembly routine implementations:

#define DEF_GENERETE_ASSEMBLY_ROUTINE(name) \
  static void generate_##name();

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_GENERETE_ASSEMBLY_ROUTINE);
};

#endif // SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
