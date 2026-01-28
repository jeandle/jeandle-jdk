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

#define __ _masm->

void JeandleAssembler::emit_insts(address code_start, uint64_t code_size) {
  // Copy instructions.
  llvm::copy(llvm::ArrayRef(code_start, code_size), __ code()->insts_end());
  __ code()->set_insts_end(__ code()->insts_end() + code_size);
}

int JeandleAssembler::emit_consts(address consts_start, uint64_t consts_size, uint64_t alignment) {
  // Alignment
  assert(alignment <= (uint64_t)CodeEntryAlignment, "alignment must not exceed CodeEntryAlignment");
  int padding = 0;
  int size = __ code()->consts()->size();
  if (!is_aligned(size, alignment)) {
    padding = align_up(size, alignment) - size;
    __ code()->consts()->set_end(__ code()->consts()->end() + padding);
  }

  // Copy constants.
  llvm::copy(llvm::ArrayRef(consts_start, consts_size), __ code()->consts()->end());
  __ code()->consts()->set_end(__ code()->consts()->end() + consts_size);
  return padding;
}
