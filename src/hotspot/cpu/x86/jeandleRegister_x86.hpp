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

#ifndef CPU_X86_JEANDLEREGISTER_X86_HPP
#define CPU_X86_JEANDLEREGISTER_X86_HPP

#include "utilities/debug.hpp"
#include "register_x86.hpp"

#ifdef _LP64
class JeandleRegister : public AllStatic {
public:
  static const char* get_stack_pointer() {
    return rsp->name();
  }

  static const char* get_current_thread_pointer() {
    return r15->name();
  }

  static const bool is_stack_pointer(Register reg) {
    return reg == rsp;
  }

  static const Register decode_dwarf_register(int dwarf_encoding) {
    assert(dwarf_encoding >=0 && dwarf_encoding < Register::number_of_registers, "invalid dwarf register number");
    return _dwarf_registers[dwarf_encoding];
  }

private:
  static constexpr const Register _dwarf_registers[Register::number_of_registers] = {
    rax, rdx, rcx, rbx, rsi, rdi, rbp, rsp,
    r8, r9, r10, r11, r12, r13, r14, r15
  };
};
#endif // _LP64

#endif // CPU_X86_JEANDLEREGISTER_X86_HPP
