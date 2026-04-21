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
#include "llvm/TargetParser/SubtargetFeature.h"

#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/arguments.hpp"

void apply_vm_flag_feature_overrides(llvm::SubtargetFeatures& features) {
  // Keep LLVM codegen aligned with HotSpot's VM-flag-controlled effective ISA
  // rather than the raw host feature set. This mirrors how C1/C2 use UseSSE
  // and UseAVX to gate instruction selection even when the hardware supports
  // more.
  if (UseSSE < 1) {
    features.AddFeature("sse", false);
  }
  if (UseSSE < 2) {
    features.AddFeature("sse2", false);
  }
  if (UseSSE < 3) {
    features.AddFeature("sse3", false);
    features.AddFeature("ssse3", false);
    features.AddFeature("sse4a", false);
  }
  if (UseSSE < 4) {
    features.AddFeature("sse4.1", false);
    features.AddFeature("sse4.2", false);
  }

  if (UseAVX < 1) {
    features.AddFeature("avx", false);
  }
  if (UseAVX < 2) {
    features.AddFeature("avx2", false);
  }
  if (UseAVX < 3) {
    features.AddFeature("avx512f", false);
    features.AddFeature("avx512dq", false);
    features.AddFeature("avx512cd", false);
    features.AddFeature("avx512bw", false);
    features.AddFeature("avx512vl", false);
    features.AddFeature("avx512vpopcntdq", false);
    features.AddFeature("avx512vbmi", false);
    features.AddFeature("avx512vbmi2", false);
    features.AddFeature("avx512vnni", false);
    features.AddFeature("avx512bitalg", false);
    features.AddFeature("avx512ifma", false);
  }

  if (!UseAES) {
    features.AddFeature("aes", false);
  }
  if (!UseCLMUL) {
    features.AddFeature("pclmul", false);
  }
  if (!UseFMA) {
    features.AddFeature("fma", false);
  }
  if (!UseBMI1Instructions) {
    features.AddFeature("bmi", false);
  }
  if (!UseBMI2Instructions) {
    features.AddFeature("bmi2", false);
  }
  if (!UsePopCountInstruction) {
    features.AddFeature("popcnt", false);
  }
  if (!UseCountLeadingZerosInstruction) {
    features.AddFeature("lzcnt", false);
  }
  if (!UseSHA) {
    features.AddFeature("sha", false);
  }
}

void JeandleFuncSig::setup_description(llvm::Function* func, bool is_stub) {
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  func->setGC(llvm::jeandle::JeandleGC);

  if (!is_stub) {
    llvm::GlobalVariable* personality_func = func->getParent()->getGlobalVariable("jeandle.personality");
    assert(personality_func != nullptr, "no personality function");
    func->setPersonalityFn(personality_func);
  }
  func->addFnAttr(llvm::Attribute::NoCfCheck);

  if (UseCompressedOops) {
    func->addFnAttr(llvm::Attribute::get(func->getContext(), llvm::jeandle::Attribute::UseCompressedOops));
  }

  // Always disable tail call for jeandle, to ensure the correct stack states.
  func->addFnAttr("disable-tail-calls", "true");
}
