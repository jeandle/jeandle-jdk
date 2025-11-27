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

#ifndef SHARE_JEANDLE_COMPILED_CALL_HPP
#define SHARE_JEANDLE_COMPILED_CALL_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Function.h"
#include "llvm/IR/Type.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"
#include "memory/allStatic.hpp"

class JeandleCompiledCall : public AllStatic {
 public:

  enum Type {
    // To call a Java method, static calls dispatch directly to the verified entry point of a method and
    // are used for static calls and non-inlined virtual calls that have only one receiver.
    STATIC_CALL,

    // To call a Java method, dynamic calls dispatch to the unverified entry point of a method and are
    // preceded by an instruction that places an inline cache holder in a register.
    DYNAMIC_CALL,

    // Call a runtime routine. Different from other calls, routine calls are not generated as nops by LLVM,
    // because we don't need extra instructions and any alignment for routine call sites. That means,
    // call_site_patch_size(ROUTINE_CALL) is zero. So we have relocation information about routine calls in compiled object,
    // and no need to use statepoint id to distinguish each routine call site.
    // For more information, see JeandleRuntimeRoutine.
    ROUTINE_CALL,

    // Used by JeandleRuntimeRoutine stubs to call C/C++ functions. For more information, see JeandleRuntimeRoutine
    // and JeandleCallVM.
    STUB_C_CALL,

    NOT_A_CALL,
  };

  // Call site size for a call type.
  static int call_site_size(Type call_type);

  // Nop instruction size that should be emitted for a call site.
  static int call_site_patch_size(Type call_type);
};

#endif // SHARE_JEANDLE_COMPILED_CALL_HPP
