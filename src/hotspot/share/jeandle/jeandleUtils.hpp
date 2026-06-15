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

#ifndef SHARE_JEANDLE_UTILS_HPP
#define SHARE_JEANDLE_UTILS_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Function.h"
#include "llvm/IR/Module.h"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciMethod.hpp"

class Klass;
class ciType;

namespace llvm {
class CallBase;
class LLVMContext;
class SubtargetFeatures;
}

class JeandleFuncSig : public AllStatic {
 public:
  // Create a llvm function according to the Java method.
  static llvm::Function* create_llvm_func(ciMethod* method, llvm::Module& target_module, bool is_osr_entry);
  static std::string method_name(ciMethod* method);
  static std::string method_name_with_signature(ciMethod* method);
  static void setup_description(llvm::Function* func, bool is_stub = false);
};

// Check if a klass is an interface type that the bytecode verifier does not enforce.
bool is_unverified_interface(ciKlass* klass);
bool is_unverified_interface(Klass* klass);

// A klass is effectively final if no subtype can exist at runtime.
bool is_effectively_final(ciKlass* klass);
bool is_effectively_final(Klass* klass);

// Attach JavaKlass (and JavaKlassExact when applicable) return-value attributes
// to a CallBase, based on the Java return type derived from the callee signature.
// No-op when ret_type is primitive, when the klass is unloaded, or when the
// klass is an unverified interface — same exemption rules as the regular
// invoke() path uses.
void attach_java_klass_ret_attr(llvm::CallBase* call,
                                ciType* ret_type,
                                llvm::LLVMContext& ctx);

void apply_vm_flag_feature_overrides(llvm::SubtargetFeatures& features);

bool is_jeandle_compiler_thread(Thread* t);

class JeandleBitCast: public AllStatic {
public:
  template <typename To, typename From>
  static To bit_cast(const From& src) noexcept {
    static_assert(sizeof(To) == sizeof(From), "must be");
    static_assert(std::is_trivially_copyable_v<From>, "must be");
    static_assert(std::is_trivially_copyable_v<To>,   "must be");

    To dst;
    std::memcpy(&dst, &src, sizeof(To));
    return dst;
  }
};

#endif // SHARE_JEANDLE_UTILS_HPP
