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
#include "llvm/ExecutionEngine/JITLink/x86_64.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"

#include "utilities/debug.hpp"
#include "code/nativeInst.hpp"
#include "runtime/sharedRuntime.hpp"

#define __ _masm->

void JeandleAssembler::emit_static_call_stub(CallSiteInfo* call) {
  assert(call->inst_offset() != 0, "invalid call instruction address");
  assert(call->type() == JeandleJavaCall::Type::STATIC_CALL, "legal call type");
  address call_pc = __ addr_at(call->inst_offset() - JeandleJavaCall::call_site_size(JeandleJavaCall::Type::STATIC_CALL));

  int stub_size = 28;
  address stub = __ start_a_stub(stub_size);
  if (stub == nullptr) {
    JeandleCompilation::report_jeandle_error("static call stub overflow");
    return;
  }

  int start = __ offset();

  // FIXME: Whether we need alignment here?
  __ align(BytesPerWord, __ offset() + NativeMovConstReg::instruction_size + NativeCall::displacement_offset);
  __ relocate(static_stub_Relocation::spec(call_pc));
  __ mov_metadata(rbx, (Metadata*)nullptr);
  assert(((__ offset() + 1) % BytesPerWord) == 0, "must be aligned");
  __ jump(RuntimeAddress(__ pc()));

  assert(__ offset() - start <= stub_size, "stub too big");
  __ end_a_stub();
}

void JeandleAssembler::patch_static_call_site(CallSiteInfo* call) {
  assert(call->inst_offset() != 0, "invalid call instruction address");
  assert(call->type() == JeandleJavaCall::Type::STATIC_CALL, "legal call type");
  address call_pc =  __ addr_at(call->inst_offset() - JeandleJavaCall::call_site_size(JeandleJavaCall::Type::STATIC_CALL));

  // Set insts_end to where to patch.
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

  // Patch.
  __ call(AddressLiteral(call->target(), relocInfo::static_call_type));
  assert(__ offset() % 4 == 0, "must be aligned for MT-safe patch");

  // Recover insts_end.
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::patch_ic_call_site(CallSiteInfo* call) {
  assert(call->inst_offset() != 0, "invalid call instruction address");
  assert(call->type() == JeandleJavaCall::Type::DYNAMIC_CALL, "legal call type");

  int call_site_size = JeandleJavaCall::call_site_size(JeandleJavaCall::Type::DYNAMIC_CALL);
  address call_pc =  __ addr_at(call->inst_offset() - call_site_size);

  // Set insts_end to where to patch.
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

  // Patch.
  __ ic_call(call->target());
  assert(__ offset() % 4 == 0, "must be aligned for MT-safe patch");

  // Recover insts_end.
  __ code()->set_insts_end(insts_end);
}

void JeandleAssembler::emit_ic_check() {
  uint insts_size = __ code()->insts_size();
  if (UseCompressedClassPointers) {
    __ load_klass(rscratch1, j_rarg0, rscratch2);
    __ cmpptr(rax, rscratch1);
  } else {
    __ cmpptr(rax, Address(j_rarg0, oopDesc::klass_offset_in_bytes()));
  }

  __ jump_cc(Assembler::notEqual, RuntimeAddress(SharedRuntime::get_ic_miss_stub()));

  // Align to 8 byte.
  int nops_cnt = 8 - ((__ code()->insts_size() - insts_size) & 0x3);
  if (nops_cnt > 0)
    __ nop(nops_cnt);
}

using LinkKind_x86_64 = llvm::jitlink::x86_64::EdgeKind_x86_64;

void JeandleAssembler::emit_const_reloc(uint32_t operand_offset, LinkKind kind, int64_t addend, address target) {
  assert(operand_offset != 0, "invalid operand address");
  assert(kind == LinkKind_x86_64::Delta32, "invalid link kind");

  address at_address = __ code()->insts_begin() + operand_offset;
  address reloc_target = target + addend + sizeof(int32_t);
  RelocationHolder rspec = jeandle_section_word_Relocation::spec(reloc_target, CodeBuffer::SECT_CONSTS);

  __ code_section()->relocate(at_address, rspec, __ disp32_operand);
}

void JeandleAssembler::emit_oop_reloc(uint32_t offset, jobject oop_handle) {
  int index = __ oop_recorder()->find_index(oop_handle);
  RelocationHolder rspec = jeandle_oop_Relocation::spec(index);
  address at_address = __ code()->insts_begin() + offset;
  __ code_section()->relocate(at_address, rspec, __ disp32_operand);
}

void JeandleAssembler::patch_call_vm(uint32_t operand_offset, address target) {
  assert(operand_offset != 0, "invalid operand address");

  address call_pc = __ addr_at(operand_offset - 1);

  // Set insts_end to where to patch.
  address insts_end = __ code()->insts_end();
  __ code()->set_insts_end(call_pc);

  // Patch.
  __ call(AddressLiteral(target, relocInfo::static_call_type));

  // Recover insts_end.
  __ code()->set_insts_end(insts_end);
}

uint32_t JeandleAssembler::fixup_call_inst_offset(uint32_t offset) {
  return offset + 4;
}

bool JeandleAssembler::is_oop_reloc_kind(LinkKind kind) {
  return kind == LinkKind_x86_64::RequestGOTAndTransformToPCRel32GOTLoadREXRelaxable;
}

bool JeandleAssembler::is_call_vm_reloc_kind(LinkKind kind) {
  return kind == LinkKind_x86_64::BranchPCRel32;
}

bool JeandleAssembler::is_const_reloc_kind(LinkKind kind) {
  return kind == LinkKind_x86_64::Delta32;
}
