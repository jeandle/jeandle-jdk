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

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/interfaceSupport.inline.hpp"

#define __ masm->

// When a Jeandle compiled method throwing an exception, patch its return address to exceptional_return blob.
JRT_LEAF(void, JeandleRuntimeRoutine::install_exceptional_return(oopDesc* exception, JavaThread* current))
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

  current->set_exception_pc(pauth_strip_verifiable(*return_address));
  current->set_exception_oop(exception);

  // Change the return address to exceptional return blob.
  *return_address = pauth_sign_return_address(_routine_entry[_exceptional_return]);
JRT_END

// When a Jeandle C routine throwing an exception, patch its return address to exceptional_return blob.
JRT_LEAF(void, JeandleRuntimeRoutine::install_exceptional_return_for_call_vm())
  JavaThread* current = JavaThread::current();
  assert(oopDesc::is_oop(current->pending_exception()), "must be a valid oop");
  frame routine_frame = current->last_frame();
  CodeBlob* routine_code = routine_frame.cb();
  guarantee(routine_code != nullptr, "routine_code must not be null");

  intptr_t* routine_sp = routine_frame.unextended_sp() + routine_code->frame_size();

  address* return_address = (address*)(routine_sp - 1);

  current->set_exception_pc(pauth_strip_verifiable(*return_address));
  current->set_exception_oop(current->pending_exception());
  current->clear_pending_exception();

  // Change the return address to exceptional return blob.
  *return_address = pauth_sign_return_address(_routine_entry[_exceptional_return]);
JRT_END

// When a Jeandle compiled method throwing an exception, its return address
// will be patched to this blob. Here we find the right exception handler,
// then jump to.
// The exception oop and the exception pc have been set by
// JeandleRuntimeRoutine::install_exceptional_return.
// On exit, we have exception oop in r0 and exception pc in r3.
void JeandleRuntimeRoutine::generate_exceptional_return() {
  // Allocate space for the code
  ResourceMark rm;
  // Setup code generation tools
  CodeBuffer buffer(_exceptional_return, 1024, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  const Register retval = r0;

  // Results:
  const Register exception_oop = r0;
  const Register exception_pc  = r3;

  address start = __ pc();

  // Get the exception pc
  __ ldr(exception_pc, Address(rthread, JavaThread::exception_pc_offset()));

  // Push the exception pc as return address. (for stack unwinding)
  __ stp(rfp, exception_pc, Address(__ pre(sp, -2 * wordSize)));

  address frame_complete = __ pc();

  {
    Label retaddr;
    __ set_last_Java_frame(sp, noreg, retaddr, rscratch1);
    __ mov(c_rarg0, rthread);
    __ lea(rscratch1, RuntimeAddress(CAST_FROM_FN_PTR(address, JeandleRuntimeRoutine::get_exception_handler)));
    __ blr(rscratch1);
    __ bind(retaddr);
  }

  OopMapSet* oop_maps = new OopMapSet();

  oop_maps->add_gc_map(__ pc() - start, new OopMap(4 /* frame_size in slot_size(4 bytes) */, 0));

  __ reset_last_Java_frame(false);

  // Now the exception handler is in retval.
  __ mov(rscratch1, retval);

  // Move the exception oop to r0. Exception handler will use this.
  __ ldr(exception_oop, Address(rthread, JavaThread::exception_oop_offset()));

  // Clear the exception oop so GC no longer processes it as a root.
  __ str(zr, Address(rthread, JavaThread::exception_oop_offset()));

  // For not confusing exception handler, clear the exception pc.
  __ str(zr, Address(rthread, JavaThread::exception_pc_offset()));
  
  // Pop the exception pc to r3. Exception handler will use this.
  __ ldp(rfp, exception_pc, Address(__ post(sp, 2 * wordSize)));

  // Jump to the exception handler.
  __ br(rscratch1);

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

// Exception handler for Jeandle compiled method.
// At the entry of exception handler, we already have exception oop in r0 and exception pc in r3.
// What we need to do is to find the right landingpad according to the exception pc, then jump into it.
void JeandleRuntimeRoutine::generate_exception_handler() {
  // Allocate space for the code
  ResourceMark rm;
  // Setup code generation tools
  CodeBuffer buffer(_exception_handler, 1024, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  const Register retval = r0;

  // incoming parameters
  const Register exception_oop = r0;
  const Register exception_pc  = r3;

  address start = __ pc();

  // Push the exception pc as return address. (for stack unwinding)
  __ stp(rfp, exception_pc, Address(__ pre(sp, -2 * wordSize)));

  // Set exception oop and exception pc
  __ str(exception_oop, Address(rthread, JavaThread::exception_oop_offset()));
  __ str(exception_pc, Address(rthread, JavaThread::exception_pc_offset()));

  address frame_complete = __ pc();

  {
    Label retaddr;
    __ set_last_Java_frame(sp, noreg, retaddr, rscratch1);
    __ mov(c_rarg0, rthread);
    __ lea(rscratch1, RuntimeAddress(CAST_FROM_FN_PTR(address, JeandleRuntimeRoutine::search_landingpad)));
    __ blr(rscratch1);
    __ bind(retaddr);
  }

  OopMapSet* oop_maps = new OopMapSet();

  oop_maps->add_gc_map(__ pc() - start, new OopMap(4 /* frame_size in slot_size(4 bytes) */, 0));

  __ reset_last_Java_frame(false);

  // Clear the exception pc.
  __ str(zr, Address(rthread, JavaThread::exception_pc_offset()));

  __ ldp(rfp, exception_pc, Address(__ post(sp, 2 * wordSize)));

  // Jump to the landingpad.
  __ br(retval);

  // Make sure all code is generated
  masm->flush();

  RuntimeStub* rs = RuntimeStub::new_runtime_stub(_exception_handler,
                                                  &buffer,
                                                  frame_complete - start,
                                                  2 /* frame size */,
                                                  oop_maps,
                                                  false);

  _routine_entry[_exception_handler] = rs->entry_point();
}
