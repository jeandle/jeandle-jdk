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

#ifndef CPU_RISCV_JEANDLEREGISTER_RISCV_HPP
#define CPU_RISCV_JEANDLEREGISTER_RISCV_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "register_riscv.hpp"

#ifdef _LP64
class JeandleRegister : public AllStatic {
public:
  static const char* get_stack_pointer() {
    Unimplemented();
    return nullptr;
  }

  static const char* get_current_thread_pointer() {
    Unimplemented();
    return nullptr;
  }

  static const bool is_stack_pointer(Register reg) {
    Unimplemented();
    return false;
  }

  static const Register decode_dwarf_register(int dwarf_encoding) {
    Unimplemented();
    return x0;
  }
};
#endif // _LP64

#endif // CPU_RISCV_JEANDLEREGISTER_RISCV_HPP
