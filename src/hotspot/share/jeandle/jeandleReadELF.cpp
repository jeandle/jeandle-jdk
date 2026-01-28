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

#include "jeandle/jeandleReadELF.hpp"

using llvm::object::SymbolRef;

bool ReadELF::findFunc(ELFObject& elf,
                       llvm::StringRef func_name,
                       uint64_t& align,
                       uint64_t& offset,
                       uint64_t& code_size) {
  for (const llvm::object::ELFSymbolRef &sym : elf.symbols()) {
    llvm::Expected<SymbolRef::Type> type = sym.getType();
    if (!type || (*type) != SymbolRef::Type::ST_Function) {
      continue;
    }

    llvm::Expected<llvm::StringRef> sym_name = sym.getName();
    if (!sym_name) {
      continue;
    }

    if (sym_name->compare(func_name) == 0) {
      // Found here.

      llvm::Expected<llvm::object::section_iterator> section = sym.getSection();
      if (!section) {
        return false;
      }

      align = (*section)->getAlignment().value();

      // Code offset in its section.
      llvm::Expected<uint64_t> sym_value = sym.getValue();
      if (!sym_value) {
        return false;
      }

      offset = elf.getSection((*section)->getRawDataRefImpl())->sh_offset
                                  +
                              *sym_value;

      code_size = sym.getSize();

      return true;
    }
  }

  return false;
}

bool ReadELF::findSection(ELFObject& elf, SectionInfo& section_info) {
  for (auto sec = elf.section_begin(); sec != elf.section_end(); ++sec) {
    llvm::Expected<llvm::StringRef> cur_name = sec->getName();
    if (!cur_name) {
      continue;
    }

    if (cur_name->compare(section_info._name) == 0) {
      // Found here.
      section_info._offset = elf.getSection(sec->getRawDataRefImpl())->sh_offset;
      section_info._size = sec->getSize();
      section_info._alignment = sec->getAlignment().value();
      return true;
    }
  }
  return false;
}
