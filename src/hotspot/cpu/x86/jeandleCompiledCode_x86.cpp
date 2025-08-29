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

#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleCompilation.hpp"

// Get the frame size from .stack_sizes section.
void JeandleCompiledCode::setup_frame_size() {
  SectionInfo section_info(".stack_sizes");
  if (!ReadELF::findSection(*_elf, section_info)) {
    JeandleCompilation::report_jeandle_error(".stack_sizes section not found");
    return;
  }
  llvm::DataExtractor data_extractor(llvm::StringRef(((char*)_obj->getBufferStart()) + section_info._offset, section_info._size),
                                     true/* IsLittleEndian */, BytesPerWord/* AddressSize */);
  uint64_t offset = 0;
  data_extractor.getUnsigned(&offset, BytesPerWord);
  uint64_t stack_size = data_extractor.getULEB128(&offset);
  uint64_t frame_size = stack_size + BytesPerWord/* return address */;
  assert(frame_size % StackAlignmentInBytes == 0, "frame size must be aligned");
  _frame_size = frame_size / BytesPerWord;
}
