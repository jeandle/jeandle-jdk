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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/Jeandle/Metadata.h"

#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "compiler/abstractCompiler.hpp"
#include "compiler/compilerThread.hpp"
#include "runtime/thread.hpp"

llvm::Function* JeandleFuncSig::create_llvm_func(ciMethod* method, llvm::Module& target_module) {
  llvm::SmallVector<llvm::Type*> args;
  llvm::LLVMContext& context = target_module.getContext();

  // Receiver is the first argument.
  if (!method->is_static()) {
    args.push_back(JeandleType::java2llvm(BasicType::T_OBJECT, context));
  }

  ciSignature* sig = method->signature();
  for (int i = 0; i < sig->count(); i++) {
    args.push_back(JeandleType::java2llvm(sig->type_at(i)->basic_type(), context));
  }

  llvm::FunctionType* func_type =
      llvm::FunctionType::get(JeandleType::java2llvm(sig->return_type()->basic_type(), context),
                              args,
                              false);
  llvm::Function* func = llvm::Function::Create(func_type,
                                                llvm::Function::ExternalLinkage,
                                                method_name_with_signature(method),
                                                target_module);

  // Attach java-klass type attributes to parameters.
  int arg_idx = 0;
  if (!method->is_static()) {
    ciInstanceKlass* holder = method->holder();
    // Skip interface types: the verifier does not enforce interface types,
    // so a parameter declared as an interface could hold any Object at runtime.
    // This matches C2's ignore_interfaces behavior (see type.cpp TypePtr::interfaces).
    if (holder->is_loaded() && !holder->is_interface()) {
      Klass* holder_klass = (Klass*)(holder->constant_encoding());
      func->addParamAttr(arg_idx, llvm::Attribute::get(context,
          llvm::jeandle::Attribute::JavaKlass,
          std::to_string((uintptr_t)holder_klass)));
      if (holder->is_final()) {
        func->addParamAttr(arg_idx, llvm::Attribute::get(context,
            llvm::jeandle::Attribute::JavaKlassExact));
      }
    }
    arg_idx++;
  }

  for (int i = 0; i < sig->count(); i++, arg_idx++) {
    ciType* type = sig->type_at(i);
    if (type->is_klass()) {
      ciKlass* klass = type->as_klass();
      // Skip interface types (see comment above for receiver).
      if (klass->is_loaded() && !klass->is_interface()) {
        Klass* klass_enc = (Klass*)(klass->constant_encoding());
        func->addParamAttr(arg_idx, llvm::Attribute::get(context,
            llvm::jeandle::Attribute::JavaKlass,
            std::to_string((uintptr_t)klass_enc)));
        if (klass->is_instance_klass() && klass->as_instance_klass()->is_final()) {
          func->addParamAttr(arg_idx, llvm::Attribute::get(context,
              llvm::jeandle::Attribute::JavaKlassExact));
        }
      }
    }
  }

  // Attach java-klass type attribute to return value.
  ciType* ret_type = sig->return_type();
  if (ret_type->is_klass()) {
    ciKlass* ret_klass = ret_type->as_klass();
    // Skip interface types (see comment above for receiver).
    if (ret_klass->is_loaded() && !ret_klass->is_interface()) {
      Klass* ret_klass_enc = (Klass*)(ret_klass->constant_encoding());
      func->addRetAttr(llvm::Attribute::get(context,
          llvm::jeandle::Attribute::JavaKlass,
          std::to_string((uintptr_t)ret_klass_enc)));
    }
  }

  setup_description(func);

  return func;
}

std::string JeandleFuncSig::method_name(ciMethod* method) {
  std::string class_name = std::string(method->holder()->name()->as_utf8());
  std::replace(class_name.begin(), class_name.end(), '/', '_');

  std::string method_name = std::string(method->name()->as_utf8());
  std::replace(method_name.begin(), method_name.end(), '/', '_');

  return class_name + "_" + method_name;
}

std::string JeandleFuncSig::method_name_with_signature(ciMethod* method) {
  std::string signature = std::string(method->signature()->as_symbol()->as_utf8());
  return method_name(method) + signature;
}

bool is_jeandle_compiler_thread(Thread* t) {
  if (t == nullptr || !t->is_Compiler_thread()) {
    return false;
  }
  CompilerThread* ct = CompilerThread::cast(t);
  AbstractCompiler* compiler = ct->compiler();
  return compiler != nullptr && compiler->is_jeandle();
}
