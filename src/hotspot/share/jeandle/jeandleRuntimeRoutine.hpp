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
#include "runtime/stubRoutines.hpp"
#include "utilities/globalDefinitions.hpp"

// Define an indirect Jeandle runtime routine.
// def( name            ,
//      routine_address ,
//      return_type     ,
//      arg0_type       ,
//      arg1_type       ,
//         ...          ,
//      argn_type       )
#define ALL_JEANDLE_INDIRECT_ROUTINES(def)                                          \
  def(safepoint_handler,                                                            \
      JeandleRuntimeRoutine::safepoint_handler,                                     \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(install_exceptional_return,                                                   \
      JeandleRuntimeRoutine::install_exceptional_return,                            \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(new_instance,                                                                 \
      JeandleRuntimeRoutine::new_instance,                                          \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(new_array,                                                                    \
      JeandleRuntimeRoutine::new_array,                                             \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(multianewarray2,                                                              \
      JeandleRuntimeRoutine::multianewarray2,                                       \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(multianewarray3,                                                              \
      JeandleRuntimeRoutine::multianewarray3,                                       \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(multianewarray4,                                                              \
      JeandleRuntimeRoutine::multianewarray4,                                       \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(multianewarray5,                                                              \
      JeandleRuntimeRoutine::multianewarray5,                                       \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(multianewarrayN,                                                              \
      JeandleRuntimeRoutine::multianewarrayN,                                       \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(SharedRuntime_complete_monitor_locking_C,                                     \
      SharedRuntime::complete_monitor_locking_C,                                    \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(SharedRuntime_complete_monitor_unlocking_C,                                   \
      SharedRuntime::complete_monitor_unlocking_C,                                  \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \

// Define a direct Jeandle runtime routine.
// def( name            ,
//      routine_address ,
//      reachable       ,
//      return_type     ,
//      arg0_type       ,
//      arg1_type       ,
//         ...          ,
//      argn_type       )
#define ALL_JEANDLE_DIRECT_ROUTINES(def)                             \
  def(StubRoutines_dsin,                                             \
      StubRoutines::dsin(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dcos,                                             \
      StubRoutines::dcos(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dtan,                                             \
      StubRoutines::dtan(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dlog,                                             \
      StubRoutines::dlog(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dlog10,                                           \
      StubRoutines::dlog10(),                                        \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dexp,                                             \
      StubRoutines::dexp(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(StubRoutines_dpow,                                             \
      StubRoutines::dpow(),                                          \
      true,                                                          \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(uncommon_trap,                                                 \
      SharedRuntime::uncommon_trap_blob()->entry_point(),            \
      true,                                                          \
      llvm::Type::getVoidTy(context),                                \
      llvm::Type::getInt32Ty(context))                               \
                                                                     \
  def(SharedRuntime_dsin,                                            \
      SharedRuntime::dsin,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_dcos,                                            \
      SharedRuntime::dcos,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_dtan,                                            \
      SharedRuntime::dtan,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_drem,                                            \
      SharedRuntime::drem,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_frem,                                            \
      SharedRuntime::frem,                                           \
      false,                                                         \
      llvm::Type::getFloatTy(context),                               \
      llvm::Type::getFloatTy(context),                               \
      llvm::Type::getFloatTy(context))                               \
                                                                     \
  def(SharedRuntime_dlog,                                            \
      SharedRuntime::dlog,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_dlog10,                                          \
      SharedRuntime::dlog10,                                         \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_dexp,                                            \
      SharedRuntime::dexp,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(SharedRuntime_dpow,                                            \
      SharedRuntime::dpow,                                           \
      false,                                                         \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context),                              \
      llvm::Type::getDoubleTy(context))                              \
                                                                     \
  def(install_exceptional_return_for_call_vm,                        \
      JeandleRuntimeRoutine::install_exceptional_return_for_call_vm, \
      false,                                                         \
      llvm::Type::getVoidTy(context))                                \


#define ALL_JEANDLE_ASSEMBLY_ROUTINES(def) \
  def(exceptional_return)                  \
  def(exception_handler)


// JeandleRuntimeRoutine contains C/C++/Assembly routines and Hotspot routines that can be called from Jeandle compiled code.
// There are two ways to call a JeandleRuntimeRoutine:
//   1. For ALL_JEANDLE_INDIRECT_ROUTINES, call a routine through a runtime stub. The runtime stub will help adjust the VM
//      state similar to what C2's GraphKit::gen_stub does.
//   2. For ALL_JEANDLE_ASSEMBLY_ROUTINES and ALL_JEANDLE_DIRECT_ROUTINES, directly call a routine according to its runtime address.
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

#define DEF_INDIRECT_ROUTINE_CALLEE(name, routine_address, return_type, ...)                        \
  static llvm::FunctionCallee name##_callee(llvm::Module& target_module) {                          \
    llvm::LLVMContext& context = target_module.getContext();                                        \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);     \
    llvm::FunctionCallee callee = target_module.getOrInsertFunction(#name, func_type);              \
    llvm::cast<llvm::Function>(callee.getCallee())->setCallingConv(llvm::CallingConv::Hotspot_JIT); \
    return callee;                                                                                  \
  }

  ALL_JEANDLE_INDIRECT_ROUTINES(DEF_INDIRECT_ROUTINE_CALLEE);

#define DEF_DIRECT_ROUTINE_CALLEE(name, routine_address, reachable, return_type, ...)                                  \
  static llvm::FunctionCallee name##_callee(llvm::Module& target_module) {                                             \
    llvm::LLVMContext& context = target_module.getContext();                                                           \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);                        \
    if (reachable) {                                                                                                   \
      llvm::FunctionCallee callee = target_module.getOrInsertFunction(#name, func_type);                               \
      llvm::cast<llvm::Function>(callee.getCallee())->setCallingConv(llvm::CallingConv::C);                            \
      return callee;                                                                                                   \
    }                                                                                                                  \
    llvm::GlobalValue* address_value = target_module.getNamedValue(#name);                                             \
    llvm::Constant* callee_address = nullptr;                                                                          \
    if (address_value == nullptr) {                                                                                    \
      llvm::PointerType* func_ptr_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);    \
      llvm::Constant* addr_value = llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), (uint64_t)routine_address); \
      callee_address = llvm::ConstantExpr::getIntToPtr(addr_value, func_ptr_type);                                     \
      llvm::GlobalAlias::create(func_ptr_type, llvm::jeandle::AddrSpace::CHeapAddrSpace,                               \
                                llvm::GlobalValue::ExternalLinkage, #name,                                             \
                                callee_address, &target_module);                                                       \
    } else if (llvm::GlobalAlias* address_alias = llvm::dyn_cast<llvm::GlobalAlias>(address_value)) {                  \
      callee_address = address_alias->getAliasee();                                                                    \
    }                                                                                                                  \
    assert(callee_address != nullptr, "callee should not be null");                                                    \
    return {func_type, callee_address};                                                                                \
  }

  ALL_JEANDLE_DIRECT_ROUTINES(DEF_DIRECT_ROUTINE_CALLEE);

// Define all assembly routine names.
#define DEF_ASSEMBLY_ROUTINE_NAME(name) \
  static constexpr const char* _##name = #name;

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_ASSEMBLY_ROUTINE_NAME);

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
