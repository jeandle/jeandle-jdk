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

#include "jeandle/jeandleAssembler.hpp"

void JeandleAssembler::emit_static_call_stub(int inst_offset, CallSiteInfo* call) {
  Unimplemented();
}

void JeandleAssembler::patch_static_call_site(int inst_offset, CallSiteInfo* call) {
  Unimplemented();
}

void JeandleAssembler::patch_stub_C_call_site(int inst_offset, CallSiteInfo* call) {
  Unimplemented();
}

void JeandleAssembler::patch_routine_call_site(int inst_offset, address target) {
  Unimplemented();
}

void JeandleAssembler::patch_ic_call_site(int inst_offset, CallSiteInfo* call) {
  Unimplemented();
}

void JeandleAssembler::emit_ic_check() {
  Unimplemented();
}

void JeandleAssembler::emit_verified_entry() {
  Unimplemented();
}

int JeandleAssembler::interior_entry_alignment() const {
  Unimplemented();
  return 0;
}

int JeandleAssembler::emit_exception_handler() {
  Unimplemented();
  return 0;
}

void JeandleAssembler::emit_const_reloc(int operand_offset, LinkKind kind, int64_t addend, address target) {
  Unimplemented();
}

void JeandleAssembler::emit_oop_reloc(int offset, jobject oop_handle) {
  Unimplemented();
}

int JeandleAssembler::fixup_routine_call_inst_offset(int offset) {
  Unimplemented();
}

bool JeandleAssembler::is_oop_reloc_kind(LinkKind kind) {
  Unimplemented();
}

bool JeandleAssembler::is_routine_call_reloc_kind(LinkKind kind) {
  Unimplemented();
}

bool JeandleAssembler::is_const_reloc_kind(LinkKind kind) {
  Unimplemented();
}
