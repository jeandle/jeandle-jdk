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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ExecutionEngine/JITLink/aarch64.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "code/nativeInst.hpp"
#include "runtime/sharedRuntime.hpp"

#define __ _masm->

void JeandleAssembler::emit_static_call_stub(int inst_offset, CallSiteInfo* call) {
  assert(call->type() == JeandleCompiledCall::STATIC_CALL, "illegal call type");
  address call_address = __ addr_at(inst_offset);

  // same as C1 call_stub_size()
  const int stub_size = 13 * NativeInstruction::instruction_size;
  address stub = __ start_a_stub(stub_size);
  if (stub == nullptr) {
    JeandleCompilation::report_jeandle_error("static call stub overflow");
    return;
  }

  int start = __ offset();

  __ relocate(static_stub_Relocation::spec(call_address));
  __ isb();
  __ mov_metadata(rmethod, nullptr);
  __ movptr(rscratch1, 0);
  __ br(rscratch1);

  assert(__ offset() - start <= stub_size, "stub too big");
  __ end_a_stub();
}

void JeandleAssembler::patch_static_call_site(int inst_offset, CallSiteInfo* call) {
  assert(call->type() == JeandleCompiledCall::STATIC_CALL, "illegal call type");
  address call_address = __ addr_at(inst_offset);

  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_address);

  relocInfo::relocType rtype;
  if (call->target() == SharedRuntime::get_resolve_opt_virtual_call_stub()) {
    rtype = relocInfo::opt_virtual_call_type;
  } else {
    assert(call->target() == SharedRuntime::get_resolve_static_call_stub(), "illegal call target");
    rtype = relocInfo::static_call_type;
  }
  Address call_addr = Address(call->target(), rtype);
  // emit trampoline call for patch
  __ trampoline_call(call_addr);
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::patch_stub_C_call_site(int inst_offset, CallSiteInfo* call) {
  assert(call->type() == JeandleCompiledCall::STUB_C_CALL, "illegal call type");
  address patch_pc = __ addr_at(inst_offset);

  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(patch_pc);
  Label return_pc;
  return_pc.add_patch_at(__ code(), __ locator());
  // set last_Java_pc
  __ adr(rscratch1, return_pc);
  __ str(rscratch1, Address(rthread, JavaThread::frame_anchor_offset() +
                                     JavaFrameAnchor::last_Java_pc_offset()));
  __ movptr(rscratch2, (uintptr_t)call->target());
  __ blr(rscratch2);
  __ bind(return_pc);
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::patch_routine_call_site(int inst_offset, address target) {
  address call_pc = __ addr_at(inst_offset);

  // Set insts_end to where to patch
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

  __ trampoline_call(Address(target, relocInfo::runtime_call_type));

  // Recover insts_end
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::patch_ic_call_site(int inst_offset, CallSiteInfo* call) {
  assert(inst_offset >= 0, "invalid call instruction address");
  assert(call->type() == JeandleCompiledCall::DYNAMIC_CALL, "illegal call type");

  address call_address = __ addr_at(inst_offset);

  // Set insts_end to where to patch
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_address);

  // Patch
  __ ic_call(call->target());

  // Restore insts_end
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::emit_ic_check() {
  int start_offset = __ offset();
  // rscratch2: ic_klass
  // j_rarg0: receiver
  __ cmp_klass(j_rarg0, rscratch2, rscratch1);

  Label dont;
  __ br(Assembler::EQ, dont);
  __ far_jump(RuntimeAddress(SharedRuntime::get_ic_miss_stub()));

  if (__ offset() - start_offset > 4 * 4) {
    __ align(CodeEntryAlignment);
  }

  __ bind(dont);
}

int JeandleAssembler::emit_exception_handler() {
  // TODO
  return 0;
}

using LinkKind_aarch64 = llvm::jitlink::aarch64::EdgeKind_aarch64;

void JeandleAssembler::emit_const_reloc(int operand_offset, LinkKind kind, int64_t addend, address target) {
  assert(operand_offset >= 0, "invalid operand address");
  assert(kind == LinkKind_aarch64::Page21 ||
         kind == LinkKind_aarch64::PageOffset12,
         "unexpected link kind: %d", kind);

  // only support adrp & ldr for now
  address at_addr = __ code()->insts_begin() + operand_offset;
  address reloc_target = target + addend;
  RelocationHolder rspec = jeandle_section_word_Relocation::spec(reloc_target, CodeBuffer::SECT_CONSTS);
  __ code_section()->relocate(at_addr, rspec);
}

void JeandleAssembler::emit_oop_reloc(int offset, jobject oop_handle) {
  address at_addr = __ code()->insts_begin() + offset;
  int index = __ oop_recorder()->find_index(oop_handle);
  RelocationHolder rspec = jeandle_oop_Relocation::spec(index);
  __ code_section()->relocate(at_addr, rspec);
}

int JeandleAssembler::fixup_routine_call_inst_offset(int offset) {
  assert(offset >= 0, "invalid offset");
  // point to the end of call instruction
  return offset + NativeInstruction::instruction_size;
}

bool JeandleAssembler::is_oop_reloc_kind(LinkKind kind) {
  return kind == LinkKind_aarch64::Page21 ||
         kind == LinkKind_aarch64::PageOffset12;
}

bool JeandleAssembler::is_routine_call_reloc_kind(LinkKind kind) {
  return kind == LinkKind_aarch64::Branch26PCRel;
}

bool JeandleAssembler::is_const_reloc_kind(LinkKind kind) {
  return kind == LinkKind_aarch64::Page21 ||
         kind == LinkKind_aarch64::PageOffset12;
}
