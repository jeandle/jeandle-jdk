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

#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/frame.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/safepoint.hpp"

#define GEN_C_ROUTINE_STUB(c_func, return_type, ...)                                                 \
  {                                                                                                  \
    std::unique_ptr<llvm::LLVMContext> context_ptr = std::make_unique<llvm::LLVMContext>();          \
    llvm::LLVMContext& context = *context_ptr;                                                       \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);      \
    ResourceMark rm;                                                                                 \
    JeandleCompilation compilation(target_machine,                                                   \
                                   data_layout,                                                      \
                                   CompilerThread::current()->env(),                                 \
                                   std::move(context_ptr),                                           \
                                   #c_func,                                                          \
                                   CAST_FROM_FN_PTR(address, c_func),                                \
                                   func_type);                                                       \
    if (compilation.error_occurred()) { return false; }                                              \
    _routine_entry.insert({llvm::StringRef(#c_func), compilation.compiled_code()->routine_entry()}); \
  }

#define GEN_ASSEMBLY_ROUTINE_BLOB(name) \
  generate_##name();

#define REGISTER_HOTSPOT_ROUTINE(name, func_entry, ...) \
  _routine_entry.insert({llvm::StringRef(#name), (address)func_entry});

llvm::StringMap<address> JeandleRuntimeRoutine::_routine_entry;

bool JeandleRuntimeRoutine::generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout) {
  // For each C/C++ function, compile a runtime stub to wrap it.
  ALL_JEANDLE_C_ROUTINES(GEN_C_ROUTINE_STUB);

  // Generate assembly routines.
  ALL_JEANDLE_ASSEMBLY_ROUTINES(GEN_ASSEMBLY_ROUTINE_BLOB);

  // Register hotspot routines
  ALL_HOTSPOT_ROUTINES(REGISTER_HOTSPOT_ROUTINE);

  return true;
}

//=============================================================================
//                      Jeandle Runtime C/C++ Routines
//=============================================================================

JRT_ENTRY(void, JeandleRuntimeRoutine::safepoint_handler(JavaThread* current))
  RegisterMap r_map(current,
                    RegisterMap::UpdateMap::skip,
                    RegisterMap::ProcessFrames::include,
                    RegisterMap::WalkContinuation::skip);
  frame trap_frame = current->last_frame().sender(&r_map);
  CodeBlob* trap_cb = trap_frame.cb();
  guarantee(trap_cb != nullptr && trap_cb->is_compiled_by_jeandle(), "safepoint handler must be called from jeandle compiled method");

  ThreadSafepointState* state = current->safepoint_state();
  state->set_at_poll_safepoint(true);

  // TODO: Exception check.
  SafepointMechanism::process_if_requested_with_exit_check(current, false /* check asyncs */);

  state->set_at_poll_safepoint(false);
JRT_END

JRT_LEAF(address, JeandleRuntimeRoutine::get_exception_handler(JavaThread* current))
  return SharedRuntime::raw_exception_handler_for_return_address(current, current->exception_pc());
JRT_END

JRT_ENTRY(address, JeandleRuntimeRoutine::search_landingpad(JavaThread* current))
  assert(current->exception_oop() != nullptr, "exception oop is found");

  address pc = current->exception_pc();

  nmethod* nm = CodeCache::find_nmethod(pc);
  assert(nm != nullptr, "No nmethod found in Jeandle exception handler");
  assert(pc > nm->code_begin(), "sanity check");

  JeandleExceptionHandlerTable exception_table(nm);
  uint64_t handler_pc_offset = exception_table.find_handler(static_cast<uint64_t>(pc - nm->code_begin()));

  return nm->code_begin() + handler_pc_offset;
JRT_END

// Array allocation
JRT_ENTRY(void, JeandleRuntimeRoutine::new_typeArray(int type, int length, JavaThread* current))
  oop obj = oopFactory::new_typeArray(static_cast<BasicType>(type), length, current);
  current->set_vm_result(obj);
JRT_END
