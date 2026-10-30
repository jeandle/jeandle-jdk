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
#include "asm/macroAssembler.hpp"
#include "asm/macroAssembler.inline.hpp"
#include "code/codeBlob.hpp"
#include "memory/resourceArea.hpp"
#include "oops/oopsHierarchy.hpp"
#include "runtime/frame.hpp"
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

  current->set_exception_pc(*return_address);
  current->set_exception_oop(exception);

  // Change the return address to exceptional return blob.
  *return_address = _routine_entry[_exceptional_return];
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

  current->set_exception_pc(*return_address);
  current->set_exception_oop(current->pending_exception());
  current->clear_pending_exception();

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

// Exception handler for Jeandle compiled method.
// At the entry of exception handler, we already have exception oop in rax and exception pc in rdx.
// What we need to do is to find the right landingpad according to the exception pc, then jump into it.
void JeandleRuntimeRoutine::generate_exception_handler() {
  // Allocate space for the code
  ResourceMark rm;
  // Setup code generation tools
  CodeBuffer buffer(_exception_handler, 1024, 512);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  address start = __ pc();

#ifdef ASSERT
  __ check_stack_alignment(rsp, "stack not aligned in Jeandle exceptional handler");
#endif

  // Push the exception pc as return address. (for stack unwinding)
  __ push(rdx);

  // rbp is an implicitly saved callee saved register (i.e., the calling
  // convention will save/restore it in the prolog/epilog).
  __ push(rbp);

  // Set exception oop and exception pc
  __ movptr(Address(r15_thread, JavaThread::exception_oop_offset()),rax);
  __ movptr(Address(r15_thread, JavaThread::exception_pc_offset()), rdx);

  address frame_complete = __ pc();

  __ set_last_Java_frame(noreg, noreg, nullptr, rscratch1);

  __ mov(c_rarg0, r15_thread);
  __ call(RuntimeAddress(CAST_FROM_FN_PTR(address, JeandleRuntimeRoutine::search_landingpad)));

  OopMapSet* oop_maps = new OopMapSet();

  oop_maps->add_gc_map(__ pc() - start, new OopMap(4 /* frame_size in slot_size(4 bytes) */, 0));

  __ reset_last_Java_frame(false);

  // Clear the exception pc.
  __ movptr(Address(r15_thread, JavaThread::exception_pc_offset()), NULL_WORD);

  __ pop(rbp);

  __ pop(rdx);

  // Jump to the landingpad.
  __ jmp(rax);

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

// Clear a variable number of HeapWords using the C calling convention.
// MacroAssembler::clear_mem implements C2's small-store, REP STOS, and
// vectorized strategies, but requires base/count in rdi/rcx.
void JeandleRuntimeRoutine::generate_zero_heap_words_stub() {
  ResourceMark rm;
  CodeBuffer buffer(_zero_heap_words_stub, 1024, 64);
  MacroAssembler* masm = new MacroAssembler(&buffer);

  address start = __ pc();

  assert(c_rarg0 == rdi && c_rarg1 == rsi, "unexpected C calling convention");

  // clear_mem requires the count in rcx. The public signature uses i32, so
  // movl also clears the upper count bits before 64-bit loop control.
  __ movl(rcx, c_rarg1);

  __ clear_mem(rdi, rcx, rax, xmm0, false,
               UseAVX > 2 ? k1 : knoreg);

  __ ret(0);
  masm->flush();

  RuntimeStub* stub = RuntimeStub::new_runtime_stub(
      _zero_heap_words_stub, &buffer, (int)(__ pc() - start),
      0 /* frame size */, nullptr /* oop maps */, false);
  address entry = stub->entry_point();
  _routine_entry[_zero_heap_words_stub] = entry;
  _gc_leaf_routines.insert(entry);
}

// C ABI: (UTF16 source, Latin1 destination, i32 count) -> count or zero.
// SIMD blocks are committed only after validation. A dirty block resumes at
// its first character so failure preserves the complete legal prefix.
void JeandleRuntimeRoutine::generate_string_compress_stub() {
  ResourceMark rm;
  CodeBuffer buffer(_string_compress_stub, 2048, 64);
  MacroAssembler* masm = new MacroAssembler(&buffer);
  const Register src = c_rarg0;
  const Register dst = c_rarg1;
  const Register count = c_rarg2;
  const Register original_count = r8;
  Label scalar, scalar_loop, success, failure, done;
  address start = __ pc();

  __ movl(original_count, count);
  if (UseAVX >= 2 && MaxVectorSize >= 32) {
    Label vector_loop, vector_tail;
    __ cmpl(count, 16);
    __ jcc(Assembler::less, scalar);
    __ movl(rax, 0xff00);
    __ movdl(xmm3, rax);
    __ vpbroadcastw(xmm3, xmm3, Assembler::AVX_256bit);
    __ bind(vector_loop);
    __ cmpl(count, 32);
    __ jcc(Assembler::less, vector_tail);
    __ vmovdqu(xmm0, Address(src, 0));
    __ vmovdqu(xmm1, Address(src, 32));
    __ vpor(xmm2, xmm0, xmm1, Assembler::AVX_256bit);
    __ vptest(xmm2, xmm3, Assembler::AVX_256bit);
    __ jcc(Assembler::notZero, scalar);
    __ vpackuswb(xmm0, xmm0, xmm1, Assembler::AVX_256bit);
    // Packing is local to 128-bit lanes; restore source character order.
    __ vpermq(xmm0, xmm0, 0xd8, Assembler::AVX_256bit);
    __ vmovdqu(Address(dst, 0), xmm0);
    __ addptr(src, 64);
    __ addptr(dst, 32);
    __ subl(count, 32);
    __ jmp(vector_loop);
    __ bind(vector_tail);
    __ cmpl(count, 16);
    __ jcc(Assembler::less, scalar);
    __ vmovdqu(xmm0, Address(src, 0));
    __ vptest(xmm0, xmm3, Assembler::AVX_256bit);
    __ jcc(Assembler::notZero, scalar);
    __ vpackuswb(xmm0, xmm0, xmm0, Assembler::AVX_256bit);
    __ vpermq(xmm0, xmm0, 0xd8, Assembler::AVX_256bit);
    __ movdqu(Address(dst, 0), xmm0);
    __ addptr(src, 32);
    __ addptr(dst, 16);
    __ subl(count, 16);
  } else if (UseSSE42Intrinsics) {
    Label vector_loop;
    __ cmpl(count, 16);
    __ jcc(Assembler::less, scalar);
    __ movl(rax, 0xff00ff00);
    __ movdl(xmm3, rax);
    __ pshufd(xmm3, xmm3, 0);
    __ bind(vector_loop);
    __ cmpl(count, 16);
    __ jcc(Assembler::less, scalar);
    __ movdqu(xmm0, Address(src, 0));
    __ movdqu(xmm1, Address(src, 16));
    __ movdqu(xmm2, xmm0);
    __ por(xmm2, xmm1);
    __ ptest(xmm2, xmm3);
    __ jcc(Assembler::notZero, scalar);
    __ packuswb(xmm0, xmm1);
    __ movdqu(Address(dst, 0), xmm0);
    __ addptr(src, 32);
    __ addptr(dst, 16);
    __ subl(count, 16);
    __ jmp(vector_loop);
  }

  __ bind(scalar);
  __ testl(count, count);
  __ jcc(Assembler::zero, success);
  __ bind(scalar_loop);
  __ load_unsigned_short(rax, Address(src, 0));
  __ cmpl(rax, 255);
  __ jcc(Assembler::above, failure);
  __ movb(Address(dst, 0), rax);
  __ addptr(src, 2);
  __ addptr(dst, 1);
  __ subl(count, 1);
  __ jcc(Assembler::notZero, scalar_loop);
  __ bind(success);
  __ movl(rax, original_count);
  __ jmp(done);
  __ bind(failure);
  __ xorl(rax, rax);
  __ bind(done);
  if (UseAVX > 0) {
    __ vzeroupper();
  }
  __ ret(0);
  masm->flush();

  RuntimeStub* stub = RuntimeStub::new_runtime_stub(
      _string_compress_stub, &buffer, (int)(__ pc() - start),
      0 /* frame size */, nullptr /* oop maps */, false);
  address entry = stub->entry_point();
  _routine_entry[_string_compress_stub] = entry;
  _gc_leaf_routines.insert(entry);
}

// C ABI: (Latin1 source, UTF16 destination, i32 count) -> void.
void JeandleRuntimeRoutine::generate_string_inflate_stub() {
  ResourceMark rm;
  CodeBuffer buffer(_string_inflate_stub, 2048, 64);
  MacroAssembler* masm = new MacroAssembler(&buffer);
  address start = __ pc();

  assert(c_rarg0 == rdi && c_rarg1 == rsi && c_rarg2 == rdx,
         "unexpected C calling convention");
  __ movptr(rax, rdi);
  __ movptr(rdi, rsi);
  __ movptr(rsi, rax);
  __ movl(rdx, rdx);
  __ byte_array_inflate(rsi, rdi, rdx, xmm0, rcx,
                        VM_Version::supports_avx512vlbw() && VM_Version::supports_bmi2() ? k1 : knoreg);
  if (UseAVX > 0) {
    __ vzeroupper();
  }
  __ ret(0);
  masm->flush();

  RuntimeStub* stub = RuntimeStub::new_runtime_stub(
      _string_inflate_stub, &buffer, (int)(__ pc() - start),
      0 /* frame size */, nullptr /* oop maps */, false);
  address entry = stub->entry_point();
  _routine_entry[_string_inflate_stub] = entry;
  _gc_leaf_routines.insert(entry);
}
