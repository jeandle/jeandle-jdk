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

#ifndef SHARE_JEANDLE_EXCEPTION_HANDLER_TABLE_HPP
#define SHARE_JEANDLE_EXCEPTION_HANDLER_TABLE_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "code/exceptionHandlerTable.hpp"
#include "code/compiledMethod.hpp"
#include "utilities/ostream.hpp"

class JeandleHandlerTableEntry {
 private:
  uint64_t _start_pc_offset;
  uint64_t _end_pc_offset;
  uint64_t _handler_pc_offset;

 public:
  JeandleHandlerTableEntry(uint64_t start_pc_offset, uint64_t end_pc_offset, uint64_t handler_pc_offset) {
    _start_pc_offset = start_pc_offset;
    _end_pc_offset = end_pc_offset;
    _handler_pc_offset = handler_pc_offset;
  }

  uint64_t start_pc_offset() const { return _start_pc_offset; }
  uint64_t end_pc_offset() const { return _end_pc_offset; }
  uint64_t handler_pc_offset() const { return _handler_pc_offset; }
};

class JeandleExceptionHandlerTable : public ExceptionHandlerTableInterface {
 public:
  JeandleExceptionHandlerTable(int initial_size = 8);

  JeandleExceptionHandlerTable(CompiledMethod* cm);

  void add_handler(uint64_t start_pc_offset, uint64_t end_pc_offset, uint64_t handler_pc_offset);

  uint64_t find_handler(uint64_t pc_offset);

  void copy_to(CompiledMethod* nm) override;
  void print(address base = nullptr) const override;
  int size_in_bytes() const override { return align_up(_length * (int)sizeof(JeandleHandlerTableEntry), oopSize); }

 private:
  JeandleHandlerTableEntry* _table;    // the table

  int _length; // the current length of the table
  int _size;   // the number of allocated entries
};

#endif // SHARE_JEANDLE_EXCEPTION_HANDLER_TABLE_HPP
