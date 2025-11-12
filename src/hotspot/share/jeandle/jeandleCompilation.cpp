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
#include "llvm/ADT/SmallVector.h"
#include "llvm/Bitcode/BitcodeReader.h"
#include "llvm/Jeandle/Jeandle.h"
#include "llvm/IR/CallingConv.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/LegacyPassManager.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/Verifier.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Support/FileSystem.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Support/TargetSelect.h"
#include "llvm/Support/SmallVectorMemoryBuffer.h"
#include "llvm/Transforms/Utils.h"

#include <algorithm>
#include <filesystem>
#include <iomanip>
#include <sstream>
#include <string>

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCallVM.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiler.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "logging/log.hpp"
#include "runtime/sharedRuntime.hpp"

JeandleCompilation::JeandleCompilation(llvm::TargetMachine* target_machine,
                                       llvm::DataLayout* data_layout,
                                       ciEnv* env,
                                       ciMethod* method,
                                       int entry_bci,
                                       bool should_install,
                                       llvm::MemoryBuffer* template_buffer) :
                                       _target_machine(target_machine),
                                       _data_layout(data_layout),
                                       _env(env),
                                       _method(method),
                                       _entry_bci(entry_bci),
                                       _context(std::make_unique<llvm::LLVMContext>()),
                                       _code(env, method),
                                       _error_msg(nullptr) {
  if (entry_bci != InvocationEntryBci) {
    env->record_method_not_compilable("OSR not supported");
    return;
  }

  // Setup compilation.
  initialize();
  setup_llvm_module(template_buffer);

  // Let's compile.
  compile_java_method();

  if (error_occurred()) {
#ifdef ASSERT
    if (JeandleCrashOnError) {
      fatal("%s", _error_msg);
    }
#endif
    _env->record_method_not_compilable(_error_msg);
    return;
  }

  // Install code.
  if (should_install) {
    install_code();
  }

}

JeandleCompilation::JeandleCompilation(llvm::TargetMachine* target_machine,
                                       llvm::DataLayout* data_layout,
                                       ciEnv* env,
                                       std::unique_ptr<llvm::LLVMContext> context,
                                       const char* name,
                                       address c_func,
                                       llvm::FunctionType* func_type) :
                                       _target_machine(target_machine),
                                       _data_layout(data_layout),
                                       _env(env),
                                       _method(nullptr),
                                       _entry_bci(-1),
                                       _context(std::move(context)),
                                       _llvm_module(std::make_unique<llvm::Module>(name, *_context)),
                                       _code(_env, name),
                                       _error_msg(nullptr) {
  initialize();

  _llvm_module->setDataLayout(*_data_layout);
  JeandleCallVM::generate_call_VM(name, c_func, func_type, *_llvm_module, _code);

#ifdef ASSERT
  // Verify.
  if (llvm::verifyModule(*_llvm_module, &llvm::errs())) {
    if (JeandleCrashOnError) {
      fatal("module verify failed in Jeandle stub compilation");
    }
    return;
  }
#endif

  if (JeandleDumpRuntimeStubs) {
    dump_ir(false);
  }

    // Optimize.
  llvm::jeandle::optimize(_llvm_module.get(), llvm::OptimizationLevel::O3);

  if (JeandleDumpRuntimeStubs) {
    dump_ir(true);
  }

  // Compile the module to an object file.
  compile_module();

  if (JeandleDumpRuntimeStubs) {
    dump_obj();
  }

  assert(!error_occurred(), "Jeandle stub compilation should not fail");
  if (error_occurred()) {
    return;
  }

  _code.finalize();

  assert(!error_occurred(), "Jeandle stub compilation should not fail");
  if (error_occurred()) {
    return;
  }

  RuntimeStub *rs = RuntimeStub::new_runtime_stub(name,
                                                  _code.code_buffer(),
                                                  CodeOffsets::frame_never_safe,
                                                  _code.frame_size(),
                                                  _env->debug_info()->_oopmaps,
                                                  false);
  assert(rs != nullptr && rs->is_runtime_stub(), "sanity check");
  _code.set_routine_entry(rs->entry_point());
}

void JeandleCompilation::install_code() {
  _env->register_method(_method,
                        _entry_bci,
                        _code.offsets(),
                        0, // temporary value
                        _code.code_buffer(),
                        _code.frame_size(),
                        _env->debug_info()->_oopmaps,
                        _code.exception_handler_table(),
                        _code.implicit_exception_table(),
                        CompilerThread::current()->compiler(),
                        false, // temporary value
                        false, // temporary value
                        false, // temporary value
                        0); // temporary value
}

void JeandleCompilation::initialize() {
  _arena = Thread::current()->resource_area();
  _env->set_compiler_data(this);

  // Use an oop recorder bound to the CI environment.
  // (The default oop recorder is ignorant of the CI.)
  OopRecorder* ooprec = new OopRecorder(_env->arena());
  _env->set_oop_recorder(ooprec);
  _env->set_debug_info(new DebugInformationRecorder(ooprec));
  _env->debug_info()->set_oopmaps(new OopMapSet());
  _env->set_dependencies(new Dependencies(_env));

  // Get timestamp to mark dump files.
  auto now = std::chrono::system_clock::now();
  auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch());
  _comp_start_time = std::to_string(duration.count());
}

void JeandleCompilation::setup_llvm_module(llvm::MemoryBuffer* template_buffer) {
  // Get template module from the global memory buffer.
  llvm::Expected<std::unique_ptr<llvm::Module>> module_or_error =
      parseBitcodeFile(template_buffer->getMemBufferRef(), *_context);
  if (!module_or_error) {
    report_jeandle_error("Failed to parse template bitcode");
    return;
  }
  _llvm_module = std::move(module_or_error.get());
  assert(_llvm_module != nullptr, "invalid llvm module");

  _llvm_module->setModuleIdentifier(JeandleFuncSig::method_name(_method));
  _llvm_module->setDataLayout(*_data_layout);

  llvm::NamedMDNode* metadata_node = _llvm_module->getOrInsertNamedMetadata(llvm::jeandle::Metadata::JavaMethodCompilation);
  assert(metadata_node != nullptr, "invalid metadata node");
}

void JeandleCompilation::compile_java_method() {
  // Build basic blocks. Then fill basic blocks with LLVM IR.
  {
    JeandleAbstractInterpreter interpret(_method, _entry_bci, *_llvm_module, _code);
  }

  if (JeandleDumpIR) {
    dump_ir(false);
  }

  if (error_occurred()) {
    return;
  }

#ifdef ASSERT
  // Verify.
  if (llvm::verifyModule(*_llvm_module, &llvm::errs())) {
    report_error("module verify failed in Jeandle compilation");
    return;
  }
#endif

  // Optimize.
  llvm::jeandle::optimize(_llvm_module.get(), llvm::OptimizationLevel::O3);

  if (JeandleDumpIR) {
    dump_ir(true);
  }

  // Compile the module to an object file.
  compile_module();

  if (JeandleDumpObjects) {
    dump_obj();
  }

  if (error_occurred()) {
    return;
  }

  // Unpack LLVM code information. Generate relocations, stubs and debug information.
  _code.finalize();
}

void JeandleCompilation::compile_module() {
  // Hold binary codes.
  llvm::SmallVector<char, 0> obj_buffer;

  {
    llvm::raw_svector_ostream obj_stream(obj_buffer);

    llvm::legacy::PassManager pm;
    llvm::MCContext *ctx;

    if (_target_machine->addPassesToEmitMC(pm, ctx, obj_stream)) {
      JeandleCompilation::report_jeandle_error("target does not support MC emission");
      return;
    }

    pm.run(*_llvm_module);
  }

  auto object = std::make_unique<llvm::SmallVectorMemoryBuffer>(std::move(obj_buffer),
                                                                _llvm_module->getModuleIdentifier(),
                                                                false);
  _code.install_obj(std::move(object));
}

static std::string construct_dump_path(const std::string& method_name,
                                       const std::string& timestamp,
                                       const std::string& suffix) {
  assert(suffix == ".ll" || suffix == "-optimized.ll" || suffix == ".o", "invalid suffix for dump file of Jeandle compiler");
  std::string dump_dir = JeandleDumpDirectory ? std::string(JeandleDumpDirectory) : std::string("./");

  // Full name.
  std::string file_name = dump_dir + '/' + method_name + '-' + timestamp + suffix;

  // Normalize the path and remove redundant separators.
  std::filesystem::path clean_path(std::move(file_name));

  return clean_path.lexically_normal().string();
}

void JeandleCompilation::dump_obj() {
  std::string dump_path = construct_dump_path(_llvm_module->getModuleIdentifier(), _comp_start_time, ".o");

  std::error_code err_code;
  llvm::raw_fd_ostream dump_stream(dump_path, err_code);
  if (err_code) {
    log_warning(jit, dump)("Could not open file: %s, %s\n",
                           dump_path.c_str() ,err_code.message().c_str());
    return;
  }

  dump_stream.write(_code.object_start(), _code.object_size());
}

void JeandleCompilation::dump_ir(bool optimized) {
  std::string dump_path = construct_dump_path(_llvm_module->getModuleIdentifier(), _comp_start_time, optimized ? "-optimized.ll" : ".ll");

  std::error_code err_code;
  llvm::raw_fd_ostream dump_stream(dump_path, err_code, llvm::sys::fs::OF_TextWithCRLF);

  if (err_code) {
    log_warning(jit, dump)("Could not open file: %s, %s\n",
                           dump_path.c_str(),
                           err_code.message().c_str());
    return;
  }

  _llvm_module->print(dump_stream, nullptr);
}
