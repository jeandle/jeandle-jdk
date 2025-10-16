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

#include "jeandle/jeandleExceptionHandlerTable.hpp"

JeandleExceptionHandlerTable::JeandleExceptionHandlerTable(int initial_size) {
  guarantee(initial_size > 0, "initial size must be > 0");
  _table  = NEW_RESOURCE_ARRAY(JeandleHandlerTableEntry, initial_size);
  _length = 0;
  _size   = initial_size;
}

JeandleExceptionHandlerTable::JeandleExceptionHandlerTable(CompiledMethod* cm) {
  _table  = (JeandleHandlerTableEntry*)cm->handler_table_begin();
  _length = cm->handler_table_size() / sizeof(JeandleHandlerTableEntry);
  _size   = 0; // no space allocated by ExceptionHandlerTable!
}

void JeandleExceptionHandlerTable::add_handler(uint64_t start_pc_offset, uint64_t end_pc_offset, uint64_t handler_pc_offset) {
  if (_length >= _size) {
    // No enough space, grow the table (amortized growth, double its size)
    guarantee(_size > 0, "no space allocated, cannot grow the table");
    int new_size = _size * 2;
    _table = REALLOC_RESOURCE_ARRAY(JeandleHandlerTableEntry, _table, _size, new_size);
    _size = new_size;
  }
  assert(_length < _size, "sanity check");
  _table[_length++] = JeandleHandlerTableEntry{start_pc_offset, end_pc_offset, handler_pc_offset};
}

uint64_t JeandleExceptionHandlerTable::find_handler(uint64_t pc_offset) {
  for (int i = 0; i < _length; ++i) {
    if (pc_offset > _table[i].start_pc_offset() && pc_offset <= _table[i].end_pc_offset()) {
      return _table[i].handler_pc_offset();
    }
  }
  // At least one handler is found.
  ShouldNotReachHere();
  return 0;
}

void JeandleExceptionHandlerTable::copy_to(CompiledMethod* cm) {
  assert(size_in_bytes() == cm->handler_table_size(), "size of handler table allocated in compiled method incorrect");
  memmove(cm->handler_table_begin(), _table, size_in_bytes());
}

void JeandleExceptionHandlerTable::print(address base) const {
  bool have_base_addr = (base != nullptr);
  tty->print_cr("JeandleExceptionHandlerTable (size = %d bytes) (entry count = %d) :", size_in_bytes(), _length);
  for (int i = 0; i < _length; ++i) {
    if (have_base_addr) {
      tty->print_cr("start pc: " INTPTR_FORMAT ", end pc: " INTPTR_FORMAT ", handler pc: " INTPTR_FORMAT,
                    p2i(base + _table[i].start_pc_offset()),
                    p2i(base + _table[i].end_pc_offset()),
                    p2i(base + _table[i].handler_pc_offset()));
    } else {
      tty->print_cr("start pc offset: " UINT64_FORMAT ", end pc offset: " UINT64_FORMAT ", handler pc offset: " UINT64_FORMAT,
                    _table[i].start_pc_offset(),
                    _table[i].end_pc_offset(),
                    _table[i].handler_pc_offset());
    }
  }
}
