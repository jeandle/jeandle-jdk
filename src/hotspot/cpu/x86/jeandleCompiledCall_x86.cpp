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

#include "jeandle/jeandleCompiledCall.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "nativeInst_x86.hpp"

int JeandleCompiledCall::call_site_size(JeandleCompiledCall::Type call_type) {
  switch (call_type) {
    case JeandleCompiledCall::ROUTINE_CALL:
    case JeandleCompiledCall::STUB_C_CALL:
    case JeandleCompiledCall::EXTERNAL_CALL:
      return NativeJump::instruction_size;
    default:
      break;
  }

  return call_site_patch_size(call_type);
}

int JeandleCompiledCall::call_site_patch_size(JeandleCompiledCall::Type call_type) {
  assert(call_type != JeandleCompiledCall::NOT_A_CALL, "sanity");
  switch (call_type) {
    case JeandleCompiledCall::STATIC_CALL:
      return NativeJump::instruction_size;
    case JeandleCompiledCall::DYNAMIC_CALL:
      return NativeJump::instruction_size + NativeMovConstReg::instruction_size;
    case JeandleCompiledCall::ROUTINE_CALL:
      // No need to patch routine call site.
      return 0;
    case JeandleCompiledCall::STUB_C_CALL:
      // No need to patch stub C call site on x86. So we return 0 here.
      return 0;
    default:
      ShouldNotReachHere();
      break;
  }
}
