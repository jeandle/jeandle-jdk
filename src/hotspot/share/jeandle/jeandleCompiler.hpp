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

#ifndef SHARE_JEANDLE_COMPILER_HPP
#define SHARE_JEANDLE_COMPILER_HPP

#include <cassert>
#pragma push_macro("AARCH64")
#undef AARCH64
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/Target/TargetMachine.h"
#pragma pop_macro("AARCH64")

#include <memory>

#include "utilities/debug.hpp"
#include "compiler/abstractCompiler.hpp"
#include "compiler/compilerDirectives.hpp"

class JeandleCompiler : public AbstractCompiler {
 public:
  // Creation.
  JeandleCompiler(llvm::TargetMachine* target_machine);

  static JeandleCompiler* create();

  // Name of this compiler.
  virtual const char* name() { return "Jeandle"; }

  // Initialization.
  virtual void initialize();

  // Compilation entry point for methods.
  virtual void compile_method(ciEnv* env, ciMethod* target, int entry_bci, bool install_code, DirectiveSet* directive);

  // Print compilation timers and statistics.
  virtual void print_timers();

  llvm::TargetMachine* target_machine() { return _target_machine.get(); }
  llvm::DataLayout* data_layout() { return &_data_layout; }

 private:
  std::unique_ptr<llvm::TargetMachine> _target_machine;
  llvm::DataLayout _data_layout;

  // Read the template file into a global read-only memory buffer to ensure thread safety.
  std::unique_ptr<llvm::MemoryBuffer> _template_buffer;

  bool initialize_template_buffer();
};

#endif // SHARE_JEANDLE_COMPILER_HPP
