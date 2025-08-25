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

#ifndef SHARE_JEANDLE_CALL_VM_HPP
#define SHARE_JEANDLE_CALL_VM_HPP

#include <cassert>
#include "llvm/IR/Module.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/LLVMContext.h"

#include "utilities/debug.hpp"
#include "utilities/globalDefinitions.hpp"

class JeandleCallVM : public AllStatic {
 public:
  // Generate stubs that call JeandleRuntimeRoutine.
  static void generate_call_VM(const char* name, address c_func, llvm::FunctionType* func_type, llvm::Module& target_module);
};

#endif // SHARE_JEANDLE_CALL_VM_HPP
