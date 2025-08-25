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

class JeandleRegister : public AllStatic {
public:
  static const char* get_stack_pointer() {
#ifdef _LP64
    return rsp->name();
#else
    Unimplemented();
    return nullptr;
#endif // _LP64
  }

  static const char* get_current_thread_pointer() {
#ifdef _LP64
    return r15->name();
#else
    Unimplemented();
    return nullptr;
#endif // _LP64
  }
};

#endif // CPU_X86_JEANDLEREGISTER_X86_HPP
