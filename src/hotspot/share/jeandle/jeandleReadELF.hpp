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

#ifndef SHARE_JEANDLE_READ_ELF_HPP
#define SHARE_JEANDLE_READ_ELF_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/Object/ELFObjectFile.h"
#include "llvm/Support/MemoryBuffer.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allStatic.hpp"

using ELFT = llvm::object::ELF64LE;
using ELFObject = llvm::object::ELFObjectFile<ELFT>;

struct SectionInfo {
  llvm::StringRef _name;
  uint64_t _offset; // Offset from the start of ELF file.
  uint64_t _size;
  uint64_t _alignment;

  SectionInfo(const llvm::StringRef name) : _name(name), _offset(0), _size(0), _alignment(1) { }
};

class ReadELF : public AllStatic {
 public:
  static bool findFunc(ELFObject& elf,
                       llvm::StringRef func_name,
                       uint64_t& align, // Instruction alignment.
                       uint64_t& offset, // Offset from the start of ELF file.
                       uint64_t& code_size);

  static bool findSection(ELFObject& elf,
                          SectionInfo& section_info);
};

#endif // SHARE_JEANDLE_READ_ELF_HPP
