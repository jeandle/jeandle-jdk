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

#include <cassert>
#include "llvm/IR/Jeandle/Metadata.h"

#include "jeandle/jeandleType.hpp"

llvm::Type* JeandleType::java2llvm(BasicType java_type, llvm::LLVMContext& context) {
  switch (java_type) {
    case BasicType::T_BOOLEAN:
      return llvm::Type::getInt32Ty(context);
    case BasicType::T_CHAR:
      Unimplemented();
      return nullptr;
    case BasicType::T_FLOAT:
      return llvm::Type::getFloatTy(context);
    case BasicType::T_DOUBLE:
      return llvm::Type::getDoubleTy(context);
    case BasicType::T_BYTE:
      Unimplemented();
      return nullptr;
    case BasicType::T_SHORT:
      Unimplemented();
      return nullptr;
    case BasicType::T_INT:
      return llvm::Type::getInt32Ty(context);
    case BasicType::T_LONG:
      return llvm::Type::getInt64Ty(context);
    case BasicType::T_OBJECT:
      return llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
    case BasicType::T_ARRAY:
      return llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
    case BasicType::T_VOID:
      return llvm::Type::getVoidTy(context);
    case BasicType::T_ADDRESS:
      Unimplemented();
      return nullptr;
    case BasicType::T_NARROWOOP:
      Unimplemented();
      return nullptr;
    case BasicType::T_METADATA:
      Unimplemented();
      return nullptr;
    case BasicType::T_NARROWKLASS:
      Unimplemented();
      return nullptr;
    case BasicType::T_CONFLICT:
      Unimplemented();
      return nullptr;
    case BasicType::T_ILLEGAL:
      Unimplemented();
      return nullptr;
    default:
      Unimplemented();
      return nullptr;
  }
}
