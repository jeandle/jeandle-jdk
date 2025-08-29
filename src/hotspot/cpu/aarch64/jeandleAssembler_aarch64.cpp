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

#include <cassert>
#include "llvm/ExecutionEngine/JITLink/aarch64.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"

#include "utilities/debug.hpp"
#include "code/nativeInst.hpp"
#include "runtime/sharedRuntime.hpp"

#define __ _masm->

void JeandleAssembler::emit_static_call_stub(CallSiteInfo* call) {
  assert(call->type() == JeandleJavaCall::STATIC_CALL, "illegal call type");
  const int call_site_size = JeandleJavaCall::call_site_size(JeandleJavaCall::STATIC_CALL);
  address call_pc = __ addr_at(call->inst_offset() - call_site_size);

  // same as C1 call_stub_size()
  const int stub_size = 13 * NativeInstruction::instruction_size;
  address stub = __ start_a_stub(stub_size);
  if (stub == nullptr) {
    JeandleCompilation::report_jeandle_error("static call stub overflow");
    return;
  }

  int start = __ offset();

  __ relocate(static_stub_Relocation::spec(call_pc));
  __ isb();
  __ mov_metadata(rmethod, nullptr);
  __ movptr(rscratch1, 0);
  __ br(rscratch1);

  assert(__ offset() - start <= stub_size, "stub too big");
  __ end_a_stub();
}

void JeandleAssembler::patch_static_call_site(CallSiteInfo* call) {
  assert(call->type() == JeandleJavaCall::STATIC_CALL, "illegal call type");
  const int call_site_size = JeandleJavaCall::call_site_size(JeandleJavaCall::STATIC_CALL);
  address call_pc = __ addr_at(call->inst_offset() - call_site_size);

  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

  Address call_addr = Address(call->target(), relocInfo::static_call_type);
  // emit trampoline call for patch
  __ trampoline_call(call_addr);
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::patch_ic_call_site(CallSiteInfo* call) {
  assert(call->inst_offset() != 0, "invalid call instruction address");
  assert(call->type() == JeandleJavaCall::DYNAMIC_CALL, "illegal call type");

  const int call_site_size = JeandleJavaCall::call_site_size(JeandleJavaCall::DYNAMIC_CALL);
  address call_pc = __ addr_at(call->inst_offset() - call_site_size);

  // Set insts_end to where to patch
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

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

using LinkKind_aarch64 = llvm::jitlink::aarch64::EdgeKind_aarch64;

void JeandleAssembler::emit_const_reloc(uint32_t operand_offset, LinkKind kind, int64_t addend, address target) {
  assert(operand_offset != 0, "invalid operand address");
  assert(kind == LinkKind_aarch64::Page21 ||
         kind == LinkKind_aarch64::PageOffset12,
         "unexpected link kind: %d", kind);

  // only support adrp & ldr for now
  address at_addr = __ code()->insts_begin() + operand_offset;
  address reloc_target = target + addend;
  RelocationHolder rspec = jeandle_section_word_Relocation::spec(reloc_target, CodeBuffer::SECT_CONSTS);
  __ code_section()->relocate(at_addr, rspec);
}

void JeandleAssembler::emit_oop_reloc(uint32_t offset, jobject oop_handle) {
  address at_addr = __ code()->insts_begin() + offset;
  int index = __ oop_recorder()->find_index(oop_handle);
  RelocationHolder rspec = jeandle_oop_Relocation::spec(index);
  __ code_section()->relocate(at_addr, rspec);
}

void JeandleAssembler::patch_call_vm(uint32_t operand_offset, address target) {
  Unimplemented();
}

uint32_t JeandleAssembler::fixup_call_inst_offset(uint32_t offset) {
  Unimplemented();
  return 0;
}

bool JeandleAssembler::is_oop_reloc_kind(LinkKind kind) {
  return kind == LinkKind_aarch64::RequestGOTAndTransformToPage21 ||
         kind == LinkKind_aarch64::RequestGOTAndTransformToPageOffset12;
}

bool JeandleAssembler::is_call_vm_reloc_kind(LinkKind kind) {
  Unimplemented();
  return false;
}

bool JeandleAssembler::is_const_reloc_kind(LinkKind kind) {
  Unimplemented();
  return false;
}
