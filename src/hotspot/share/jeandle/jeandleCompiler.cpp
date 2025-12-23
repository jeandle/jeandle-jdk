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
#include "llvm/Bitcode/BitcodeWriter.h"
#include "llvm/IRReader/IRReader.h"
#include "llvm/MC/TargetRegistry.h"
#include "llvm/Support/SmallVectorMemoryBuffer.h"
#include "llvm/Support/SourceMgr.h"
#include "llvm/TargetParser/Host.h"

#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiler.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/templatemodule/jeandleRuntimeDefinedJavaOps.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/arguments.hpp"

JeandleCompiler::JeandleCompiler(llvm::TargetMachine* target_machine) :
                                 AbstractCompiler(compiler_jeandle),
                                 _target_machine(target_machine),
                                 _data_layout(target_machine->createDataLayout()),
                                 _template_buffer(nullptr) {}

JeandleCompiler* JeandleCompiler::create() {
  llvm::Triple target_triple = llvm::Triple(llvm::sys::getProcessTriple());

  std::string err_msg;
  const llvm::Target* target = llvm::TargetRegistry::lookupTarget(target_triple.getTriple(), err_msg);
  if (!target) {
    fatal("Cannot create LLVM target machine: %s", err_msg.c_str());
  }
  if (!target->hasJIT()) {
    fatal("Target has no JIT support");
  }

  llvm::TargetOptions options;
  llvm::SubtargetFeatures features;
  options.EmitStackSizeSection = true;

  llvm::TargetMachine* target_machine = target->createTargetMachine(target_triple.getTriple(), ""/* CPU */, features.getString(), options,
                                                                    llvm::Reloc::Model::PIC_, llvm::CodeModel::Model::Small,
                                                                    llvm::CodeGenOptLevel::Aggressive, true/* JIT */);

  return new JeandleCompiler(target_machine);
}

void JeandleCompiler::initialize() {
  if (should_perform_init()) {
    if (!initialize_commandline_options()) {
      set_state(failed);
      return;
    }
    if (!JeandleRuntimeRoutine::generate(target_machine(), data_layout())) {
      set_state(failed);
      return;
    }
    if (!initialize_template_buffer()) {
      set_state(failed);
      return;
    }
    if (!initialize_dynamic_library()) {
      set_state(failed);
      return;
    }
#ifdef ASSERT
    for (auto& routine_entry : JeandleRuntimeRoutine::routine_entry()) {
      assert(DynamicLibrary::SearchForAddressOfSymbol(routine_entry.first().data()) == nullptr, "overlapping symbol");
    }
#endif
    set_state(initialized);
  }
}

void JeandleCompiler::compile_method(ciEnv* env, ciMethod* target, int entry_bci, bool install_code, DirectiveSet* directive) {
  ResourceMark rm;
  JeandleCompilation compilation(target_machine(), data_layout(), env, target, entry_bci, install_code, _template_buffer.get());
}

void JeandleCompiler::print_timers() {
  JeandleCompilation::print_timers();
}

bool JeandleCompiler::initialize_template_buffer() {
  llvm::LLVMContext tmp_context;
  llvm::SMDiagnostic error;

  std::unique_ptr<llvm::Module> template_module = llvm::parseIRFile(Arguments::get_jeandle_template_path(), error, tmp_context);
#ifdef assert
  if (template_module == nullptr) {
    tty->print_cr("Error parsing template module from file '%s':", Arguments::get_jeandle_template_path());
    tty->print_cr("  Line: %d, Column: %d", error.getLineNo(), error.getColumnNo());
    tty->print_cr("  Filename: %s", error.getFilename().str().c_str());
    tty->print_cr("  Message: %s", error.getMessage().str().c_str());
    tty->print_cr("  Line contents: %s", error.getLineContents().str().c_str());
    fatal("cannot create template module from file '%s'", Arguments::get_jeandle_template_path());
  }
#endif

  if (template_module == nullptr) {
    return false;
  }

  bool failed = RuntimeDefinedJavaOps::define_all(*template_module);
  assert(!failed, "cannot define runtime defined JavaOps: %s", RuntimeDefinedJavaOps::error_msg());
  if (failed) {
    return false;
  }

  llvm::SmallVector<char, 0> bitcode_buffer;
  llvm::raw_svector_ostream bitcode_stream(bitcode_buffer);
  llvm::WriteBitcodeToFile(*template_module, bitcode_stream);

  _template_buffer = std::make_unique<llvm::SmallVectorMemoryBuffer>(std::move(bitcode_buffer), "template module", false);
  assert(_template_buffer != nullptr, "cannot initialize template module buffer");
  return _template_buffer != nullptr;
}

bool JeandleCompiler::initialize_commandline_options() {
    std::vector<std::string> argv_string = {
      "placeholder",
      "-enable-implicit-null-checks",
      "-imp-null-check-page-size=" + std::to_string(os::vm_page_size())
    };

    std::vector<const char*> argv;
    for (const auto& s : argv_string) {
        argv.push_back(s.c_str());
    }

    return llvm::cl::ParseCommandLineOptions(argv.size(), argv.data());
}

bool JeandleCompiler::initialize_dynamic_library() {
  return !DynamicLibrary::LoadLibraryPermanently(LibmName);
}
