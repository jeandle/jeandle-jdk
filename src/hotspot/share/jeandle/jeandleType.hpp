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

#ifndef SHARE_JEANDLE_TYPE_HPP
#define SHARE_JEANDLE_TYPE_HPP

#include <cassert>
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Type.h"

#include "utilities/debug.hpp"
#include "ci/compilerInterface.hpp"

class JeandleType : public AllStatic {
 public:

  // Convert a Java type to its LLVM type.
  static llvm::Type* java2llvm(BasicType jvm_type, llvm::LLVMContext& context);

  static bool is_double_word_type(llvm::Type* t) {
    return t->isIntegerTy(64) || t->isDoubleTy();
  }

  // Get a LLVM constant value according to a Java type.
  // For example: If you want to get a LLVM value that represent a Java int, use int_const().

  static llvm::ConstantInt* int_const(llvm::IRBuilder<>& builder, uint32_t value) {
    return builder.getInt32(value);
  }

  static llvm::ConstantInt* long_const(llvm::IRBuilder<>& builder, uint64_t value) {
    return builder.getInt64(value);
  }

  static llvm::ConstantFP* float_const(llvm::IRBuilder<>& builder, float value) {
    return (llvm::ConstantFP*)llvm::ConstantFP::get(builder.getFloatTy(), value);
  }

  static llvm::ConstantFP* double_const(llvm::IRBuilder<>& builder, double value) {
    return (llvm::ConstantFP*)llvm::ConstantFP::get(builder.getDoubleTy(), value);
  }
};

#endif // SHARE_JEANDLE_TYPE_HPP
