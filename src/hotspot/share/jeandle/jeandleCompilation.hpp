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

#ifndef SHARE_JEANDLE_COMPILATION_HPP
#define SHARE_JEANDLE_COMPILATION_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Module.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/Target/TargetMachine.h"

#include <memory>

#include "jeandle/jeandleCompiledCode.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciMethod.hpp"
#include "memory/arena.hpp"

class JeandleCompilation : public StackObj {
 public:
  // Compile a Java method.
  JeandleCompilation(llvm::TargetMachine* target_machine,
                     llvm::DataLayout* data_layout,
                     ciEnv* env,
                     ciMethod* method,
                     int entry_bci,
                     bool install_code,
                     llvm::MemoryBuffer* template_buffer);

  // Compile a runtime stub that call a JeandleRuntimeRoutine.
  JeandleCompilation(llvm::TargetMachine* target_machine,
                     llvm::DataLayout* data_layout,
                     ciEnv* env,
                     std::unique_ptr<llvm::LLVMContext> context,
                     const char* name,
                     address routine_address,
                     llvm::FunctionType* func_type);

  ~JeandleCompilation() = default;

  static JeandleCompilation* current() { return (JeandleCompilation*) ciEnv::current()->compiler_data(); }

  // Error related:
  void report_error(const char* msg) {
    if (msg != nullptr) {
      _error_msg = msg;
    }
  }
  bool error_occurred() const { return _error_msg != nullptr; }
  static void report_jeandle_error(const char* msg) { JeandleCompilation::current()->report_error(msg); }
  static bool jeandle_error_occurred() { return JeandleCompilation::current()->error_occurred(); }
  static void print_timers();

  void set_has_monitors(bool v) { _has_monitors = v; }

  JeandleCompiledCode* compiled_code() { return &_code; }

  Arena* arena() { return _arena; }

  const std::string name() { return _name; }

 private:
  Arena* _arena; // Hold compilation life-time objects (JeandleCompilationResourceObj).
  llvm::TargetMachine* _target_machine;
  llvm::DataLayout* _data_layout;
  ciEnv* _env;
  ciMethod* _method;
  const std::string _name;
  int _entry_bci;
  std::unique_ptr<llvm::LLVMContext> _context;
  std::unique_ptr<llvm::Module> _llvm_module;
  std::string _comp_start_time;

  JeandleCompiledCode _code; // Compiled code.

  const char* _error_msg;

  bool _has_monitors;

  const char* check_can_parse(ciMethod* method);

  void initialize();
  void setup_llvm_module(llvm::MemoryBuffer* template_buffer);
  void compile_java_method();
  void compile_module();
  void install_code();

  void dump_obj();
  void dump_ir(bool optimized);
};


#ifdef ASSERT
#define JEANDLE_CRASH_ON_ERROR(_error_msg)                            \
do {                                                                  \
  if (JeandleCrashOnError) {                                          \
    fatal("Compilation failed in '%s': %s", JeandleCompilation::current()->name().c_str(), _error_msg); \
  }                                                                   \
} while (0)
#else
#define JEANDLE_CRASH_ON_ERROR(_error_msg) (void)(0)
#endif

#define JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(p, msg)             \
do {                                                                  \
  if (!(p)) {                                                         \
    JeandleCompilation::report_jeandle_error(msg);                    \
    JEANDLE_CRASH_ON_ERROR(msg);                                      \
    return;                                                           \
  }                                                                   \
} while (0)

#define JEANDLE_ERROR_ASSERT_AND_RET_ON_FAIL(p, msg, return_val)      \
do {                                                                  \
  if (!(p)) {                                                         \
    JeandleCompilation::report_jeandle_error(msg);                    \
    JEANDLE_CRASH_ON_ERROR(msg);                                      \
    return return_val;                                                \
  }                                                                   \
} while (0)

#define RETURN_VOID_ON_JEANDLE_ERROR()                                \
do {                                                                  \
  if (JeandleCompilation::jeandle_error_occurred()) {                 \
    return;                                                           \
  }                                                                   \
} while (0)

#define RETURN_ON_JEANDLE_ERROR(return_val)                           \
do {                                                                  \
  if (JeandleCompilation::jeandle_error_occurred()) {                 \
    return return_val;                                                \
  }                                                                   \
} while (0)

#endif // SHARE_JEANDLE_COMPILATION_HPP
