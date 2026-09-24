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
#include "llvm/ADT/SmallVector.h"
#include "llvm/Object/ELFObjectFile.h"
#include "llvm/Support/MemoryBuffer.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/allStatic.hpp"
#include "jeandle/jeandleConstSectionPlan.hpp"

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

  static bool is_jeandle_const_section(llvm::StringRef name) {
    return name == ".rodata" ||
           name.starts_with(".rodata.") ||
           name == ".data.rel.ro" ||
           name.starts_with(".data.rel.ro.");
  }

  static void collect_const_sections(ELFObject& elf,
                                     llvm::SmallVectorImpl<SectionInfo>& const_sections);

  // Week 5 (F2): every llvm::Expected is checked, so a malformed ELF makes the
  // planner fail (and the caller fall back) instead of aborting the VM via
  // Expected::value().
  //
  // consts_base_alignment is forwarded to the planner so that a section whose
  // alignment the CodeBuffer cannot guarantee for the consts base is rejected
  // rather than silently emitted at a misaligned address.
  static bool build_const_section_plan(ELFObject& elf,
                                       ConstSectionPlan& plan,
                                       uint64_t consts_base_alignment =
                                         ConstSectionPlan::MAX_SUPPORTED_CONST_ALIGNMENT);
};

#endif // SHARE_JEANDLE_READ_ELF_HPP
