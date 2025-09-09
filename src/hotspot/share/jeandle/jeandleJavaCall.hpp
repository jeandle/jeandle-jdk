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

#ifndef SHARE_JEANDLE_JAVA_CALL_HPP
#define SHARE_JEANDLE_JAVA_CALL_HPP

#include <cassert>
#include "llvm/IR/Function.h"
#include "llvm/IR/Type.h"

#include "utilities/debug.hpp"
#include "ci/ciMethod.hpp"
#include "memory/allStatic.hpp"

class JeandleJavaCall : public AllStatic {
 public:

  enum Type {
    // Static calls dispatch directly to the verified entry point of a method and
    // are used for static calls and non−inlined virtual calls that have only one receiver.
    STATIC_CALL,

    // Dynamic calls dispatch to the unverified entry point of a method and are
    // preceded by an instruction that places an inline cache holder in a register.
    DYNAMIC_CALL,

    // For call vm stub, will call runtime routines
    VM_CALL,

    NOT_A_CALL,
  };

  static llvm::FunctionCallee callee(llvm::Module& target_module,
                                     ciMethod* target,
                                     llvm::Type* return_type,
                                     std::vector<llvm::Type*>& args_type);

  static int call_site_size(Type call_type);
};

#endif // SHARE_JEANDLE_JAVA_CALL_HPP
