/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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

#ifndef CPU_RISCV_JEANDLEREGISTER_RISCV_HPP
#define CPU_RISCV_JEANDLEREGISTER_RISCV_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "register_riscv.hpp"

#ifdef _LP64
class JeandleRegister : public AllStatic {
 public:
  static const char* get_stack_pointer() {
    return "x2";
  }

  static const char* get_current_thread_pointer() {
    return "x23";
  }

  static const bool is_stack_pointer(Register reg) {
    return reg == x2;
  }

  static const Register decode_dwarf_register(int dwarf_encoding) {
    assert(dwarf_encoding >=0 && dwarf_encoding < Register::number_of_registers, "invalid dwarf register number");
    return _dwarf_registers[dwarf_encoding];
  }

 private:
  static constexpr const Register _dwarf_registers[Register::number_of_registers] = {
      x0,  x1,  x2,  x3,  x4,  x5,  x6,  x7,
      x8,  x9,  x10, x11, x12, x13, x14, x15,
      x16, x17, x18, x19, x20, x21, x22, x23,
      x24, x25, x26, x27, x28, x29, x30, x31,
  };
};
#endif // _LP64

#endif // CPU_RISCV_JEANDLEREGISTER_RISCV_HPP
