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
#include "runtime/globals_extension.hpp"

void apply_vm_flag_feature_overrides(llvm::SubtargetFeatures& features) {
  if (!UseAES) {
    features.AddFeature("aes", false);
  }
  if (!UseSHA) {
    features.AddFeature("sha2", false);
    features.AddFeature("sha3", false);
  }
  if (FLAG_IS_CMDLINE(UseNeon) && !UseNeon) {
    features.AddFeature("neon", false);
  }
  if (!UseCRC32) {
    features.AddFeature("crc", false);
  }
  if (!UseLSE) {
    features.AddFeature("lse", false);
  }
  // TODO: SVE is disabled as a workaround. When LLVM uses SVE registers, it
  // generates `addvl sp, sp, #-N` in the prologue to allocate variable-length
  // spill space. But HotSpot's frame_size is a compile-time constant and cannot
  // include the addvl portion, causing GC stack walks to miscalculate sender_sp
  // and crash (SIGSEGV or heap assertion failures).
  features.AddFeature("sve2", false);
  features.AddFeature("sve", false);
}

void JeandleFuncSig::setup_description(llvm::Function* func, bool is_stub) {
  func->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  func->setGC(llvm::jeandle::JeandleGC);

  if (!is_stub) {
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
