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

#include "utilities/debug.hpp"
#include "runtime/frame.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/safepoint.hpp"

#define GEN_ROUTINE_STUB(c_func, return_type, args_type)                                       \
  {                                                                                            \
    std::unique_ptr<llvm::LLVMContext> context_ptr = std::make_unique<llvm::LLVMContext>();    \
    llvm::LLVMContext& context = *context_ptr;                                                 \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, args_type, false);    \
    ResourceMark rm;                                                                           \
    JeandleCompilation compilation(target_machine,                                             \
                                   data_layout,                                                \
                                   CompilerThread::current()->env(),                           \
                                   std::move(context_ptr),                                     \
                                   #c_func,                                                    \
                                   CAST_FROM_FN_PTR(address, c_func),                          \
                                   func_type);                                                 \
    if (compilation.error_occurred()) { return false; }                                        \
    _stub_entry.insert({llvm::StringRef(#c_func), compilation.compiled_code()->stub_entry()}); \
  }

llvm::StringMap<address> JeandleRuntimeRoutine::_stub_entry;

bool JeandleRuntimeRoutine::generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout) {
  ALL_JEANDLE_ROUTINES(GEN_ROUTINE_STUB);
  return true;
}

//=============================================================================
//                        Jeandle Runtime Routines
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
