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
#include "llvm/ADT/DenseMap.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/Target/TargetMachine.h"

#include <memory>
#include <string>

#include "jeandle/jeandleCompiledCode.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciMethod.hpp"
#include "memory/allocation.hpp"
#include "memory/arena.hpp"
#include "utilities/growableArray.hpp"

class ciCallProfile;
class ciObject;
class DirectiveSet;
class JeandleCompilation;
class JeandleInlineFailure;
class JeandleInlineTree;
class outputStream;

enum class JeandleInlineReason {
  InlineHot,
  ForceInlineByCompileCommand,
  ForceInlineByAnnotation,
  ForceInlineByCiReplay,
  ForceIncrementalInlineByCiReplay,
  ManyThrows,
  Accessor,
  FailedInitialChecks,
  NativeMethod,
  AbstractMethod,
  NotCompilableUnbalancedMonitors,
  NotCompilableFlowAnalysisFailed,
  CannotBeParsed,
  MethodHolderNotInitialized,
  DontInlineByAnnotation,
  MethodChangesCurrentThread,
  UnloadedSignatureClasses,
  DisallowedByCompileCommand,
  DisallowedByCiReplay,
  AlreadyCompiledIntoMediumMethod,
  AlreadyCompiledIntoBigMethod,
  HotMethodTooBig,
  TooBig,
  ExceptionMethod,
  NeverExecuted,
  LowCallSiteFrequency,
  SizeGreaterThanDesiredMethodLimit,
  NodeCountInliningCutoff,
  CallSiteNotReached,
  NotAnAccessor,
  MaxForceInlineLevel,
  InliningTooDeep,
  RecursiveInliningTooDeep,
  TooColdToInline,
  LLVMRootCalleeUnsupported,
  LLVMGetInlineCalleeIRFailed,
  LLVMMissingInlineCalleeDefinition,
  LLVMNotInlineViable,
  LLVMInlineFailed
};

const char* jeandle_inline_reason_name(JeandleInlineReason reason);

// Successful inlines form the semantic inline tree used by scope lookup,
// replay, and deopt metadata. Failed attempts are tracked separately so
// diagnostics can show them without making failed callees visible as scopes.
class JeandleInlineFailure : public AnyObj {
  int _caller_scope_id;
  int _caller_bci;
  ciMethod* _callee;
  JeandleInlineReason _reason;

 public:
  JeandleInlineFailure(int caller_scope_id,
                       int caller_bci,
                       ciMethod* callee,
                       JeandleInlineReason reason) :
                       _caller_scope_id(caller_scope_id),
                       _caller_bci(caller_bci),
                       _callee(callee),
                       _reason(reason) {}

  int caller_scope_id() const { return _caller_scope_id; }
  int caller_bci() const { return _caller_bci; }
  ciMethod* callee() const { return _callee; }
  JeandleInlineReason reason() const { return _reason; }
};

class JeandlePendingInlineTree : public AnyObj {
 public:
  int _caller_scope_id;
  int _caller_bci;
  ciMethod* _callee;
  JeandleInlineTree* _callee_tree;

  JeandlePendingInlineTree(int caller_scope_id,
                           int caller_bci,
                           ciMethod* callee,
                           JeandleInlineTree* callee_tree) :
                           _caller_scope_id(caller_scope_id),
                           _caller_bci(caller_bci),
                           _callee(callee),
                           _callee_tree(callee_tree) {}

  bool matches(int caller_scope_id, int caller_bci, ciMethod* callee) const {
    return _caller_scope_id == caller_scope_id &&
           _caller_bci == caller_bci &&
           _callee == callee;
  }
};

class JeandleInlineTree : public AnyObj {
  JeandleInlineTree* _caller_tree;
  ciMethod* _method;
  int _caller_bci;
  int _inline_depth;
  int _max_inline_level;
  uint _count_inline_bcs;
  JeandleInlineReason _reason;
  GrowableArray<JeandleInlineTree*> _subtrees;

  bool pass_initial_checks(JeandleCompilation* comp,
                           ciMethod* caller,
                           int caller_bci,
                           ciMethod* callee,
                           JeandleInlineReason& reason);
  JeandleInlineReason check_can_parse(ciMethod* callee) const;
  bool should_inline(JeandleCompilation* comp,
                     ciMethod* callee,
                     ciMethod* caller,
                     int caller_bci,
                     bool& forced_inline,
                     ciCallProfile& profile,
                     JeandleInlineReason& reason);
  bool should_not_inline(JeandleCompilation* comp,
                         ciMethod* callee,
                         ciMethod* caller,
                         int caller_bci,
                         ciCallProfile& profile,
                         JeandleInlineReason& reason);
  bool is_not_reached(ciMethod* callee,
                      ciMethod* caller,
                      int caller_bci,
                      ciCallProfile& profile);
  bool try_to_inline(JeandleCompilation* comp,
                     ciMethod* callee,
                     ciMethod* caller,
                     int caller_bci,
                     ciCallProfile& profile,
                     JeandleInlineReason& reason);

 public:
  JeandleInlineTree(JeandleInlineTree* caller_tree,
                    ciMethod* method,
                    int caller_bci,
                    int max_inline_level,
                    Arena* arena);

  JeandleInlineTree* caller_tree() const { return _caller_tree; }
  ciMethod* method() const { return _method; }
  int caller_bci() const { return _caller_bci; }
  int inline_depth() const { return _inline_depth; }
  int max_inline_level() const { return _max_inline_level; }
  uint count_inline_bcs() const { return _count_inline_bcs; }
  JeandleInlineReason reason() const { return _reason; }
  void set_reason(JeandleInlineReason reason) { _reason = reason; }
  const GrowableArray<JeandleInlineTree*>& subtrees() const { return _subtrees; }

  bool ok_to_inline(JeandleCompilation* comp,
                    ciMethod* callee,
                    int caller_bci,
                    JeandleInlineReason& reason);
  JeandleInlineTree* callee_at(int caller_bci, ciMethod* callee) const;
  // Inline tree allocation is separated from commit so allocation happens before
  // LLVM mutates IR. If the tree were allocated only after a successful LLVM
  // inline, an allocation failure could leave LLVM IR inlined while JVM inline
  // metadata is missing. Commit is still delayed until RecordInlineResult
  // reports InlineSuccess, so failed LLVM inline attempts are not recorded.
  JeandleInlineTree* allocate_inline_tree_for_callee(ciMethod* callee,
                                                     int caller_bci,
                                                     Arena* arena);
  void commit_inline_tree_for_callee(JeandleInlineTree* callee_tree);
  int count() const;
  void dump_replay_data(outputStream* out, int depth_adjust = 0) const;
};

class JeandleCompilation : public StackObj {
 public:
  // Compile a Java method.
  JeandleCompilation(llvm::TargetMachine* target_machine,
                     llvm::DataLayout* data_layout,
                     ciEnv* env,
                     ciMethod* method,
                     int entry_bci,
                     bool install_code,
                     DirectiveSet* directive,
                     llvm::MemoryBuffer* template_buffer);

  // Compile a runtime stub that call a JeandleRuntimeRoutine.
  JeandleCompilation(llvm::TargetMachine* target_machine,
                     llvm::DataLayout* data_layout,
                     ciEnv* env,
                     std::unique_ptr<llvm::LLVMContext> context,
                     const char* name,
                     address routine_address,
                     llvm::FunctionType* func_type);

  ~JeandleCompilation();

  static JeandleCompilation* current() { return (JeandleCompilation*) ciEnv::current()->compiler_data(); }

  // Error related:
  void report_error(const char* msg) {
    if (msg != nullptr && _error_msg == nullptr) {
      _error_msg = msg;
    }
  }
  bool error_occurred() const { return _error_msg != nullptr; }
  static void report_jeandle_error(const char* msg) { JeandleCompilation::current()->report_error(msg); }
  static bool jeandle_error_occurred() { return JeandleCompilation::current()->error_occurred(); }
  static void print_timers();

  void set_has_monitors(bool v) { _has_monitors = v; }

  int const_section_alignment() { return _const_section_alignment; }
  void set_const_section_alignment(int align) {
    if (align > _const_section_alignment) {
      _const_section_alignment = align;
    }
  }

  llvm::Module* llvm_module() { return _llvm_module.get(); }
  llvm::Value* find_or_insert_oop(ciObject* oop);

  ciMethod* method() { return _method; }

  void initialize_inline_tree();

  JeandleInlineTree* inline_tree_root() const { return _inline_tree_root; }
  JeandleInlineTree* inline_tree_for_scope(int scope_id) const;
  JeandleInlineTree* prepare_inline_tree_for_callee(int caller_scope_id,
                                                    int caller_bci,
                                                    ciMethod* callee);
  void commit_inline_tree_for_callee(int caller_scope_id,
                                     int caller_bci,
                                     ciMethod* callee);
  void record_inline_failure(int caller_scope_id,
                             int caller_bci,
                             ciMethod* callee,
                             JeandleInlineReason reason);
  void print_inline_tree(outputStream* out) const;

  JeandleCompiledCode* compiled_code() { return &_code; }

  uint* trap_hist() { return _trap_hist; }
  uint trap_count(uint reason) const {
    assert(reason < MethodData::_trap_hist_limit, "trap reason overflow");
    return _trap_hist[reason];
  }
  uint decompile_count() const { return _decompile_count; }
  void add_decompile_count(uint count) {
    uint old_count = _decompile_count;
    _decompile_count += count;
    if (_decompile_count < old_count || _decompile_count < count) {
      _decompile_count = uint(-1);
    }
  }

  Arena* arena() { return _arena; }

  const std::string name() { return _name; }

  bool is_osr_compilation() const { return _entry_bci != InvocationEntryBci; }
  bool over_inlining_cutoff() const;
  void* replay_inline_data() const { return _replay_inline_data; }

  void dump_inline_data(outputStream* out);
  void dump_inline_data_reduced(outputStream* out);
  void dump_inline_callee_replay_module();

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
  uint _trap_hist[MethodData::_trap_hist_limit];
  uint _decompile_count;
  void* _replay_inline_data;

  // LLVM uses -1 for the root Java method scope. Non-negative scope ids are
  // assigned in successful inline order and index this array.
  GrowableArray<JeandleInlineTree*> _inline_trees;
  GrowableArray<JeandlePendingInlineTree*> _pending_inline_trees;
  GrowableArray<JeandleInlineFailure*> _inline_failures;
  JeandleInlineTree* _inline_tree_root;

  // Record oop constants as module-level globals. This is shared by all
  // method parsers participating in one compilation, including inlinees.
  llvm::DenseMap<jobject, llvm::Value*> _oops;
  int _oop_idx;

  JeandleCompiledCode _code; // Compiled code.

  const char* _error_msg;

  bool _has_monitors;

  int _const_section_alignment;

  std::string next_oop_name(const char* klass_name);

  const char* check_can_parse(ciMethod* method);

  void initialize();
  void setup_llvm_module(llvm::MemoryBuffer* template_buffer);
  void compile_java_method();
  void compile_module();
  void install_code();
  int inline_scope_id_for_tree(const JeandleInlineTree* tree) const;
  void print_inline_tree_impl(outputStream* out,
                              const JeandleInlineTree* tree,
                              int scope_id,
                              const std::string& prefix) const;

  void dump_obj();
  void dump_ir(bool optimized);
};

#ifdef ASSERT
#define JEANDLE_CRASH_ON_ERROR(_error_msg)                            \
do {                                                                  \
  if (JeandleCrashOnError) {                                          \
    fatal("Compilation failed in '%s': %s",                           \
      JeandleCompilation::current()->name().c_str(), _error_msg);     \
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

#define JEANDLE_REPORT_ERROR_AND_RET_VOID(msg)                        \
do {                                                                  \
  JeandleCompilation::report_jeandle_error(msg);                      \
  return;                                                             \
} while (0)

#define JEANDLE_REPORT_ERROR_AND_RET(msg, return_val)                 \
do {                                                                  \
  JeandleCompilation::report_jeandle_error(msg);                      \
  return return_val;                                                  \
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
