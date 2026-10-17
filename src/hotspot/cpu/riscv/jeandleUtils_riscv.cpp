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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/TargetParser/SubtargetFeature.h"
#include <string>

#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/arguments.hpp"

void apply_vm_flag_feature_overrides(llvm::SubtargetFeatures& features) {
  // Compressed instructions are always disabled for Jeandle methods.
  // patch_static_call_site and patch_ic_call_site emit a 4-byte jal at the
  // statepoint return PC, and NativeCall later patches that jal with a 32-bit
  // store. trampoline_call asserts the address is 4-byte aligned. With C, Zca,
  // Zcd, or Zcf that PC is legally 2 (mod 4). -XX:-UseRVC clears only "c";
  // Zca and Zcd stay enabled, so 16-bit parcels remain. UseRVC itself is left
  // unchanged so HotSpot's assembler can still compress.
  features.AddFeature("c", false);
  features.AddFeature("zca", false);
  features.AddFeature("zcd", false);
  features.AddFeature("zcf", false);
  if (!UseRVV) {
    features.AddFeature("v", false);
  }
  if (!UseZba) {
    features.AddFeature("zba", false);
  }
  if (!UseZbb) {
    features.AddFeature("zbb", false);
  }
  if (!UseZbs) {
    features.AddFeature("zbs", false);
  }
  if (!UseZic64b) {
    features.AddFeature("zic64b", false);
  }
  if (!UseZicbom) {
    features.AddFeature("zicbom", false);
  }
  if (!UseZicbop) {
    features.AddFeature("zicbop", false);
  }
  if (!UseZicboz) {
    features.AddFeature("zicboz", false);
  }
  if (!UseZihintpause) {
    features.AddFeature("zihintpause", false);
  }
}

void JeandleFuncSig::setup_description(llvm::Function* func, ciMethod* method, bool is_stub) {
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  func->setGC(llvm::jeandle::JeandleGC);

  if (!is_stub) {
    assert(method != nullptr, "Java method function must have a ciMethod");
    func->addFnAttr(llvm::Attribute::get(func->getContext(),
                                         llvm::jeandle::Attribute::JavaMethod,
                                         std::to_string((uintptr_t)method)));
    if (method->is_accessor()) {
      func->addFnAttr(llvm::Attribute::get(func->getContext(),
                                           llvm::jeandle::Attribute::JavaAccessorMethod));
    }
    llvm::GlobalVariable* personality_func = func->getParent()->getGlobalVariable("jeandle.personality");
    assert(personality_func != nullptr, "no personality function");
    func->setPersonalityFn(personality_func);
  }

  if (UseCompressedOops) {
    func->addFnAttr(llvm::Attribute::get(func->getContext(), llvm::jeandle::Attribute::UseCompressedOops));
  }

  // Always disable tail call for jeandle, to ensure the correct stack states.
  func->addFnAttr("disable-tail-calls", "true");
}
