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

void JeandleRuntimeRoutine::generate_deopt_blob() {
  _routine_entry[_deopt_blob] = SharedRuntime::deopt_blob()->unpack();
}

// Clear a variable number of HeapWords using the C calling convention:
//   x0: HeapWord-aligned base
//   w1: HeapWord count
//
// This follows C2's zero_blocks strategy, but owns the tail clearing as well
// because an LLVM call cannot consume the adjusted x0/x1 values on return.
void JeandleRuntimeRoutine::generate_zero_heap_words_stub() {
  ResourceMark rm;
  CodeBuffer buffer(_zero_heap_words_stub, 1024, 64);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  const Register base = c_rarg0;
  const Register cnt = c_rarg1;
  Label done;
  Label base_aligned;

  address start = __ pc();

  // The C ABI supplies the i32 count in w1. Zero-extend it before using x1
  // for address arithmetic and loop control.
  __ uxtw(cnt, cnt);

  if (UseBlockZeroing) {
    int zva_length = VM_Version::zva_length();

    // Ensure ZVA length can be divided by 16. This is required by
    // the subsequent operations.
    assert (zva_length % 16 == 0, "Unexpected ZVA Length");

    __ tbz(base, 3, base_aligned);
    __ str(zr, Address(__ post(base, 8)));
    __ sub(cnt, cnt, 1);
    __ bind(base_aligned);

    // Ensure count >= zva_length * 2 so that it still deserves a zva after
    // alignment.
    Label small;
    int low_limit = MAX2(zva_length * 2, (int)BlockZeroingLowLimit);
    __ subs(rscratch1, cnt, low_limit >> 3);
    __ br(Assembler::LT, small);
    __ zero_dcache_blocks(base, cnt);
    __ bind(small);
  }

  {
    // Number of stp instructions we'll unroll
    const int unroll =
      MacroAssembler::zero_words_block_size / 2;
    // Clear the remaining blocks.
    Label loop;
    __ subs(cnt, cnt, unroll * 2);
    __ br(Assembler::LT, done);
    __ bind(loop);
    for (int i = 0; i < unroll; i++)
      __ stp(zr, zr, __ post(base, 16));
    __ subs(cnt, cnt, unroll * 2);
    __ br(Assembler::GE, loop);
    __ bind(done);
    __ add(cnt, cnt, unroll * 2);
  }

  // Unlike C2's zero_blocks stub, clear the tail here as well because the C
  // ABI call cannot return the adjusted base and count in its arguments.
  for (int i = MacroAssembler::zero_words_block_size >> 1; i > 1; i >>= 1) {
    Label l;
    __ tbz(cnt, exact_log2(i), l);
    for (int j = 0; j < i; j += 2) {
      __ stp(zr, zr, __ post(base, 2 * BytesPerWord));
    }
    __ bind(l);
  }
  {
    Label l;
    __ tbz(cnt, 0, l);
    __ str(zr, Address(base));
    __ bind(l);
  }

  __ ret(lr);
  masm->flush();

  RuntimeStub* stub = RuntimeStub::new_runtime_stub(
      _zero_heap_words_stub, &buffer, (int)(__ pc() - start),
      0 /* frame size */, nullptr /* oop maps */, false);
  address entry = stub->entry_point();
  _routine_entry[_zero_heap_words_stub] = entry;
  _gc_leaf_routines.insert(entry);
}
