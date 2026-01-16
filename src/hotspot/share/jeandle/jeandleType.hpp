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

#ifndef SHARE_JEANDLE_TYPE_HPP
#define SHARE_JEANDLE_TYPE_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Type.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
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

  // Convert a Java type to computational type
  // Reference: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html#jvms-2.11.1-320
  static BasicType actual2computational(BasicType bt) {
    switch (bt) {
      case T_BYTE   :
      case T_CHAR   :
      case T_SHORT  :
      case T_BOOLEAN:
      case T_INT    :
        return T_INT;
      case T_VOID   :
      case T_LONG   :
      case T_FLOAT  :
      case T_DOUBLE :
        return bt;
      case T_ARRAY  :
      case T_OBJECT :
        return T_OBJECT;
      default       :
        ShouldNotReachHere();
    }
  }
};

/* A pair of BasicType and llvm::Value* used by JeandleVMState */
class TypedValue {
private:
  BasicType _basic_type;
  llvm::Value * _value;

public:
  TypedValue(BasicType type, llvm::Value* value) : _basic_type(type), _value(value) {
    if (value == nullptr) {
      assert(type == T_ILLEGAL, "value is null");
    } else {
      assert(value->getType() == JeandleType::java2llvm(type, value->getContext()), "type does not match");
    }
  }
  TypedValue() : _basic_type(T_ILLEGAL), _value(nullptr) {}

  static TypedValue null_value() { return TypedValue(T_ILLEGAL, nullptr); }
  bool   is_null() const { return _basic_type == T_ILLEGAL && _value == nullptr; }

  BasicType computational_type() const { return JeandleType::actual2computational(_basic_type); }
  BasicType        actual_type() const { return _basic_type; }
  llvm::Value*           value() const { return _value; }
};

/* A pair of TypedValue and corresponding lock (llvm::Value*) used by monitors */
class LockValue {
private:
  TypedValue _object;
  llvm::Value* _basic_lock;

public:
  LockValue(TypedValue object, llvm::Value* lock) : _object(object), _basic_lock(lock) { }
  LockValue(BasicType type, llvm::Value* object, llvm::Value* lock)
    : _object(TypedValue(type, object)), _basic_lock(lock) { }
  LockValue() : _object(TypedValue()), _basic_lock(nullptr) { }

  bool equals(const LockValue& rhs) {
    return _object.value() == rhs._object.value() && _basic_lock == rhs._basic_lock;
  }

  TypedValue    object() const { return _object; }
  llvm::Value*    lock() const { return _basic_lock; }
  bool         is_null() const { return _object.is_null() || _basic_lock == nullptr; }

  void set_object(TypedValue object) { _object = object; }
  void set_lock(llvm::Value* lock) { _basic_lock = lock; }
};

#endif // SHARE_JEANDLE_TYPE_HPP
