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

#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "utilities/debug.hpp"
#include "asm/macroAssembler.hpp"
#include "asm/macroAssembler.inline.hpp"
#include "code/codeBlob.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oopsHierarchy.hpp"
#include "runtime/frame.hpp"
#include "runtime/interfaceSupport.inline.hpp"

#define __ masm->

// When a Jeandle compiled method throwing an exception, patch its return address to exceptional_exit blob.
JRT_ENTRY(void, JeandleRuntimeRoutine::install_exceptional_return(oopDesc* exception, JavaThread* current))
  assert(oopDesc::is_oop(exception), "must be a valid oop");
  RegisterMap r_map(current,
                    RegisterMap::UpdateMap::skip,
                    RegisterMap::ProcessFrames::include,
                    RegisterMap::WalkContinuation::skip);
  frame exception_frame = current->last_frame().sender(&r_map);
  CodeBlob* exception_code = exception_frame.cb();
  guarantee(exception_code != nullptr && exception_code->is_compiled_by_jeandle(), "install_exceptional_return must be jumped from jeandle compiled method");

  intptr_t* sender_sp = exception_frame.unextended_sp() + exception_code->frame_size();

  address* return_address = (address*)(sender_sp - 1);

  current->set_exception_pc(*return_address);
  current->set_exception_oop(exception);

  // Change the return address to exceptional return blob.
  *return_address = _routine_entry[_exceptional_return];
JRT_END

// When a Jeandle compiled method throwing an exception, its return address
// will be patched to this blob. Here we find the right exception handler,
// then jump to.
// The exception oop and the exception pc have been set by
// JeandleRuntimeRoutine::install_exceptional_return.
// On exit, we have exception oop in rax and exception pc in rdx.
void JeandleRuntimeRoutine::generate_exceptional_return() {
  // Allocate space for the code
  ResourceMark rm;
  // Setup code generation tools
  CodeBuffer buffer(_exceptional_return, 1024, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  address start = __ pc();

#ifdef ASSERT
  __ check_stack_alignment(rsp, "stack not aligned in Jeandle exceptional return");
#endif

  // Get the exception pc
  __ movptr(rax, Address(r15_thread, JavaThread::exception_pc_offset()));

  // Push the exception pc as return address. (for stack unwinding)
  __ push(rax);

  // rbp is an implicitly saved callee saved register (i.e., the calling
  // convention will save/restore it in the prolog/epilog).
  __ push(rbp);

  address frame_complete = __ pc();

  __ set_last_Java_frame(noreg, noreg, nullptr, rscratch1);

  __ mov(c_rarg0, r15_thread);
  __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, JeandleRuntimeRoutine::get_exception_handler)));

  OopMapSet* oop_maps = new OopMapSet();

  oop_maps->add_gc_map(__ pc() - start, new OopMap(4 /* frame_size in slot_size(4 bytes) */, 0));

  __ reset_last_Java_frame(false);

  // Now the exception handler is in rax.
  __ mov(r10, rax);

  // Move the exception oop to rax. Exception handler will use this.
  __ movptr(rax, Address(r15_thread, JavaThread::exception_oop_offset()));

  // Clear the exception oop so GC no longer processes it as a root.
  __ movptr(Address(r15_thread, JavaThread::exception_oop_offset()), NULL_WORD);

  // For not confusing exception handler, clear the exception pc.
  __ movptr(Address(r15_thread, JavaThread::exception_pc_offset()), NULL_WORD);

  __ pop(rbp);

  // Pop the exception pc to rdx. Exception handler will use this.
  __ pop(rdx);

  // Jump to the exception handler.
  __ jmp(r10);

  // Make sure all code is generated
  masm->flush();

  RuntimeStub* rs = RuntimeStub::new_runtime_stub(_exceptional_return,
                                                  &buffer,
                                                  frame_complete - start,
                                                  2 /* frame size */,
                                                  oop_maps,
                                                  false);

  _routine_entry[_exceptional_return] = rs->entry_point();
}
