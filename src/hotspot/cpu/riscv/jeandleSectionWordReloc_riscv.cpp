/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ExecutionEngine/JITLink/riscv.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleReloc.hpp"

bool JeandleSectionWordReloc::pd_emit_reloc(JeandleAssembler& assembler) {
  assembler.emit_section_word_reloc(offset(), _kind, _addend, _target, _reloc_section, _rel_offset);
  return true;
}

bool JeandleSectionWordReloc::pd_fixup_offset(int prolog_length) {
  if (_reloc_section == CodeBuffer::SECT_INSTS) {
    _offset += prolog_length;
  } else if (_reloc_section == CodeBuffer::SECT_CONSTS &&
             _kind == llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_ADD32) {
    _target += prolog_length;
  } else {
    assert(_reloc_section == CodeBuffer::SECT_CONSTS, "unexpected code section");
    assert(_kind == llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_SUB32, "unexpected link kind");
    // do nothing here.
  }
#ifdef ASSERT
  _fixed_up = true;
#endif
  return true;
}
