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
  def(new_array_from_mirror,                                                        \
      JeandleRuntimeRoutine::new_array_from_mirror,                                 \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
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
  def(SharedRuntime_register_finalizer,                                             \
      SharedRuntime::register_finalizer,                                            \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace)) \
                                                                                    \
  def(instanceof_unloaded_or_null,                                                  \
      JeandleRuntimeRoutine::instanceof_unloaded_or_null,                           \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(SharedRuntime_slow_arraycopy_C,                                               \
      SharedRuntime::slow_arraycopy_C,                                              \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \

// Define a direct Jeandle runtime routine.
// def( name            ,
//      routine_address ,
//      reachable       ,
//      is_leaf         ,
//      return_type     ,
//      arg0_type       ,
//      arg1_type       ,
//         ...          ,
//      argn_type       )
//
// is_leaf is a Jeandle leaf-runtime contract, not only an is-gc-leaf hint.
// A leaf routine must not trigger GC, reach a safepoint, or produce Java-visible
// exceptional control flow. It is emitted with both gc-leaf-function and
// nounwind so LLVM can skip statepoint rewriting and EH edges for the call.
#define ALL_JEANDLE_DIRECT_ROUTINES(def)                                            \
  def(StubRoutines_dsin,                                                            \
      StubRoutines::dsin(),                                                         \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(StubRoutines_dcos,                                                            \
      StubRoutines::dcos(),                                                         \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(StubRoutines_dtan,                                                            \
      StubRoutines::dtan(),                                                         \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(StubRoutines_dlog,                                                            \
      StubRoutines::dlog(),                                                         \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(StubRoutines_dlog10,                                                          \
      StubRoutines::dlog10(),                                                       \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(StubRoutines_dexp,                                                            \
      StubRoutines::dexp(),                                                         \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(__llvm_deoptimize,                                                            \
      SharedRuntime::uncommon_trap_blob()->entry_point(),                           \
      true,                                                                         \
      false,                                                                        \
      llvm::Type::getVoidTy(context),                                               \
      llvm::Type::getInt32Ty(context))                                              \
                                                                                    \
  def(SharedRuntime_dsin,                                                           \
      SharedRuntime::dsin,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_dcos,                                                           \
      SharedRuntime::dcos,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_dtan,                                                           \
      SharedRuntime::dtan,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_drem,                                                           \
      SharedRuntime::drem,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_frem,                                                           \
      SharedRuntime::frem,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getFloatTy(context),                                              \
      llvm::Type::getFloatTy(context),                                              \
      llvm::Type::getFloatTy(context))                                              \
                                                                                    \
  def(SharedRuntime_dlog,                                                           \
      SharedRuntime::dlog,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_dlog10,                                                         \
      SharedRuntime::dlog10,                                                        \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(SharedRuntime_dexp,                                                           \
      SharedRuntime::dexp,                                                          \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getDoubleTy(context),                                             \
      llvm::Type::getDoubleTy(context))                                             \
                                                                                    \
  def(install_exceptional_return_for_call_vm,                                       \
      JeandleRuntimeRoutine::install_exceptional_return_for_call_vm,                \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getVoidTy(context))                                               \
                                                                                    \
  def(SharedRuntime_complete_monitor_unlocking_C,                                   \
      SharedRuntime::complete_monitor_unlocking_C,                                  \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),    \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
  def(StubRoutines_vectorizedMismatch,                                              \
      StubRoutines::vectorizedMismatch(),                                           \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context))                                              \
                                                                                    \
  def(StubRoutines_sha1_implCompress,                                               \
      StubRoutines::sha1_implCompress(),                                            \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace)) \
                                                                                    \
  def(StubRoutines_sha256_implCompress,                                             \
      StubRoutines::sha256_implCompress(),                                          \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace)) \
                                                                                    \
  def(StubRoutines_sha512_implCompress,                                             \
      StubRoutines::sha512_implCompress(),                                          \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace)) \
                                                                                    \
  def(StubRoutines_sha3_implCompress,                                               \
      StubRoutines::sha3_implCompress(),                                            \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context))                                              \
                                                                                    \
  def(SharedRuntime_OSR_migration_end,                                              \
      SharedRuntime::OSR_migration_end,                                             \
      false,                                                                        \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \
def(StubRoutines_generic_arraycopy,                                                 \
      StubRoutines::generic_arraycopy(),                                            \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::Type::getInt32Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jbyte_arraycopy,                                                 \
      StubRoutines::jbyte_arraycopy(),                                              \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jbyte_arraycopy,                                         \
      StubRoutines::arrayof_jbyte_arraycopy(),                                      \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jbyte_disjoint_arraycopy,                                        \
      StubRoutines::jbyte_disjoint_arraycopy(),                                     \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jbyte_disjoint_arraycopy,                                \
      StubRoutines::arrayof_jbyte_disjoint_arraycopy(),                             \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jshort_arraycopy,                                                \
      StubRoutines::jshort_arraycopy(),                                             \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jshort_arraycopy,                                        \
      StubRoutines::arrayof_jshort_arraycopy(),                                     \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jshort_disjoint_arraycopy,                                       \
      StubRoutines::jshort_disjoint_arraycopy(),                                    \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jshort_disjoint_arraycopy,                               \
      StubRoutines::arrayof_jshort_disjoint_arraycopy(),                            \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jint_arraycopy,                                                  \
      StubRoutines::jint_arraycopy(),                                               \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jint_arraycopy,                                          \
      StubRoutines::arrayof_jint_arraycopy(),                                       \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jint_disjoint_arraycopy,                                         \
      StubRoutines::jint_disjoint_arraycopy(),                                      \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jint_disjoint_arraycopy,                                 \
      StubRoutines::arrayof_jint_disjoint_arraycopy(),                              \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jlong_arraycopy,                                                 \
      StubRoutines::jlong_arraycopy(),                                              \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jlong_arraycopy,                                         \
      StubRoutines::arrayof_jlong_arraycopy(),                                      \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_jlong_disjoint_arraycopy,                                        \
      StubRoutines::jlong_disjoint_arraycopy(),                                     \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_jlong_disjoint_arraycopy,                                \
      StubRoutines::arrayof_jlong_disjoint_arraycopy(),                             \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_oop_arraycopy,                                                   \
      StubRoutines::oop_arraycopy(),                                                \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_oop_arraycopy,                                           \
      StubRoutines::arrayof_oop_arraycopy(),                                        \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_oop_disjoint_arraycopy,                                          \
      StubRoutines::oop_disjoint_arraycopy(),                                       \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_arrayof_oop_disjoint_arraycopy,                                  \
      StubRoutines::arrayof_oop_disjoint_arraycopy(),                               \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getVoidTy(context),                                               \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context))                                              \
                                                                                    \
  def(StubRoutines_checkcast_arraycopy,                                             \
      StubRoutines::checkcast_arraycopy(),                                          \
      true,                                                                         \
      true,                                                                         \
      llvm::Type::getInt32Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace), \
      llvm::Type::getInt64Ty(context),                                              \
      llvm::Type::getInt64Ty(context),                                              \
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))    \
                                                                                    \

#define ALL_JEANDLE_ASSEMBLY_ROUTINES(def) \
  def(exceptional_return)                  \
  def(exception_handler)                   \
  def(deopt_blob)


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

  // Look up a routine entry by name. Returns nullptr if the entry is absent
  // or if the registered address is null (e.g., StubRoutines not yet generated).
  static address find_routine_entry(llvm::StringRef name) {
    auto it = _routine_entry.find(name);
    if (it == _routine_entry.end()) return nullptr;
    return it->getValue();
  }

  static bool is_gc_leaf(address addr) {
    return _gc_leaf_routines.contains(addr);
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

#define DEF_DIRECT_ROUTINE_CALLEE(name, routine_address, reachable, is_leaf, return_type, ...)                         \
  static llvm::FunctionCallee name##_callee(llvm::Module& target_module) {                                             \
    llvm::LLVMContext& context = target_module.getContext();                                                           \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);                        \
    if (reachable) {                                                                                                   \
      llvm::FunctionCallee callee = target_module.getOrInsertFunction(#name, func_type);                               \
      llvm::Function* func = llvm::cast<llvm::Function>(callee.getCallee());                                           \
      func->setCallingConv(llvm::CallingConv::C);                                                                      \
      if (is_leaf) {                                                                                                   \
        func->addFnAttr(llvm::Attribute::NoUnwind);                                                                     \
        func->addFnAttr(llvm::Attribute::get(context, "gc-leaf-function"));                                            \
      }                                                                                                                \
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
  static llvm::DenseSet<address> _gc_leaf_routines; // All the gc leaf routines.

  // C/C++ routine implementations:

  static void safepoint_handler(JavaThread* current);

  // Install exceptional_return into the current java frame, for throwing exceptions.
  static void install_exceptional_return(oopDesc* exception, JavaThread* current);

  // Install exceptional_return into call_VM stub frame, for checking exceptions during call_VM.
  static void install_exceptional_return_for_call_vm();

  static address get_exception_handler(JavaThread* current);

  static address search_landingpad(JavaThread* current);

  // Allocation routine
  static void new_instance(Klass* klass, JavaThread* current);
  static void new_array(Klass* array_type, int length, JavaThread* current);
  // Slow-path array allocation: resolves the array klass from the component-type mirror
  // (java.lang.Class) and allocates via Reflection::reflect_new_array.  Used when the
  // cached array_klass field in the mirror has not been populated yet.
  static void new_array_from_mirror(oopDesc* mirror, int length, JavaThread* current);

  // Multi-dimensional array allocation routines
  static void multianewarray2(Klass* elem_type, int len1, int len2, JavaThread* current);
  static void multianewarray3(Klass* elem_type, int len1, int len2, int len3, JavaThread* current);
  static void multianewarray4(Klass* elem_type, int len1, int len2, int len3, int len4, JavaThread* current);
  static void multianewarray5(Klass* elem_type, int len1, int len2, int len3, int len4, int len5, JavaThread* current);
  static void multianewarrayN(Klass* elem_type, arrayOopDesc* dims, JavaThread* current);

  static jint instanceof_unloaded_or_null(Method* method, int cp_index, Klass* ex_klass, JavaThread* current);

  // Assembly routine implementations:

#define DEF_GENERATE_ASSEMBLY_ROUTINE(name) \
  static void generate_##name();

  ALL_JEANDLE_ASSEMBLY_ROUTINES(DEF_GENERATE_ASSEMBLY_ROUTINE);
};

#endif // SHARE_JEANDLE_RUNTIME_ROUTINE_HPP
