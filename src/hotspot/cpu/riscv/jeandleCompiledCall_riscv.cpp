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

#include "jeandle/jeandleCompiledCall.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "nativeInst_riscv.hpp"

int JeandleCompiledCall::call_site_size(JeandleCompiledCall::Type call_type) {
  if (call_type == JeandleCompiledCall::ROUTINE_CALL) {
    return NativeInstruction::instruction_size;
  } else if (call_type == JeandleCompiledCall::EXTERNAL_CALL) {
    return NativeInstruction::instruction_size;
  }

  return call_site_patch_size(call_type);
}

int JeandleCompiledCall::call_site_patch_size(JeandleCompiledCall::Type call_type) {
  assert(call_type != JeandleCompiledCall::NOT_A_CALL, "sanity");
  switch (call_type) {
    case JeandleCompiledCall::STATIC_CALL:
      return NativeInstruction::instruction_size;
    case JeandleCompiledCall::DYNAMIC_CALL:
      // lui + addi + slli + addi + slli + addi + jalr
      return NativeMovConstReg::movptr_instruction_size + NativeInstruction::instruction_size;
    case JeandleCompiledCall::ROUTINE_CALL:
      // No need to patch routine call site.
      return 0;
    case JeandleCompiledCall::STUB_C_CALL:
      // auipc + addi + sd + lui + addi + slli + addi + slli + jalr
      return NativeInstruction::instruction_size * 9;
    default:
      ShouldNotReachHere();
      break;
  }
}
