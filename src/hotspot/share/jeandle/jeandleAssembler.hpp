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

#ifndef SHARE_JEANDLE_ASSEMBLER_HPP
#define SHARE_JEANDLE_ASSEMBLER_HPP

#include <cassert>
#include "llvm/ExecutionEngine/JITLink/JITLink.h"

#include "jeandle/jeandleCompilation.hpp"

#include "utilities/debug.hpp"
#include "asm/macroAssembler.hpp"

using LinkKind  = llvm::jitlink::Edge::Kind;

class JeandleAssembler : public StackObj {
 public:
  JeandleAssembler(MacroAssembler* masm) : _masm(masm) {}

  void emit_static_call_stub(CallSiteInfo* call);

  void patch_static_call_site(CallSiteInfo* call);

  void patch_ic_call_site(CallSiteInfo* call);

  void emit_ic_check();

  void emit_insts(address code_start, uint64_t code_size);

  void emit_consts(address consts_start, uint64_t consts_size);

  void emit_const_reloc(uint32_t offset, LinkKind kind, int64_t addend, address target);

  void emit_oop_reloc(uint32_t offset, jobject oop_handle);

  void patch_call_vm(uint32_t offset, address target);

  // Redirect an offset from the displacement to the end of the call instruction
  static uint32_t fixup_call_inst_offset(uint32_t offset);

  static bool is_oop_reloc_kind(LinkKind kind);

  static bool is_call_vm_reloc_kind(LinkKind kind);

  static bool is_const_reloc_kind(LinkKind kind);

 private:
  MacroAssembler* _masm;
};

#endif // SHARE_JEANDLE_ASSEMBLER_HPP
