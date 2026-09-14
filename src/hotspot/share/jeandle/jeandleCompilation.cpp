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

#include "compiler/compilerDefinitions.hpp"
#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/SmallVector.h"
#include "llvm/Bitcode/BitcodeReader.h"
#include "llvm/Jeandle/Jeandle.h"
#include "llvm/IR/CallingConv.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/Jeandle/Attributes.h"
#include "llvm/IR/Jeandle/GCStrategy.h"
#include "llvm/IR/Jeandle/Metadata.h"
#include "llvm/IR/Jeandle/VMCallbackLog.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/LLVMContext.h"
#include "llvm/IR/Metadata.h"
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
#include "llvm/Transforms/Utils/Cloning.h"
#include "llvm/Transforms/Utils.h"

#include <algorithm>
#include <filesystem>
#include <iomanip>
#include <optional>
#include <sstream>
#include <string>

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCallVM.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiler.hpp"
#include "jeandle/jeandle_globals.hpp"
#include "jeandle/jeandleType.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandleVMCallback.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciCallProfile.hpp"
#include "ci/ciKlass.hpp"
#include "ci/ciObject.hpp"
#include "ci/ciReplay.hpp"
#include "ci/ciMethodBlocks.hpp"
#include "ci/ciStreams.hpp"
#include "ci/ciTypeFlow.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "compiler/compilationPolicy.hpp"
#include "compiler/compilerDirectives.hpp"
#include "compiler/compiler_globals.hpp"
#include "compiler/compilerOracle.hpp"
#include "logging/log.hpp"
#include "memory/resourceArea.hpp"
#include "opto/c2_globals.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/globals.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/thread.hpp"
#include "runtime/timer.hpp"
#include "runtime/timerTrace.hpp"
#include "utilities/ostream.hpp"

enum JeandleTimerName : int {
  compilation_timer = 0,
    abstract_interpreter_timer,
    llvm_optimizer_timer,
    llvm_codegen_timer,
    finalize_timer,
  max_phase_timers
};

// Static timer array, corresponding to C1's Compilation::timers[]
static elapsedTimer jeandle_timers[max_phase_timers];

// Counts how many methods have been compiled by Jeandle (optional)
static int jeandle_compilation_count = 0;

static void print_inline_tree_method(outputStream* out, ciMethod* method) {
  method->holder()->print_name_on(out);
  out->print("::");
  method->name()->print_symbol_on(out);
  if (WizardMode) {
    method->signature()->as_symbol()->print_symbol_on(out);
  }
}

const char* jeandle_inline_reason_name(JeandleInlineReason reason) {
  switch (reason) {
    case JeandleInlineReason::InlineHot:
      return "inline (hot)";
    case JeandleInlineReason::ForceInlineByCompileCommand:
      return "force inline by CompileCommand";
    case JeandleInlineReason::ForceInlineByAnnotation:
      return "force inline by annotation";
    case JeandleInlineReason::ForceInlineByCiReplay:
      return "force inline by ciReplay";
    case JeandleInlineReason::ForceIncrementalInlineByCiReplay:
      return "force incremental inline by ciReplay";
    case JeandleInlineReason::ManyThrows:
      return "many throws";
    case JeandleInlineReason::Accessor:
      return "accessor";
    case JeandleInlineReason::FailedInitialChecks:
      return "failed initial checks";
    case JeandleInlineReason::NativeMethod:
      return "native method";
    case JeandleInlineReason::AbstractMethod:
      return "abstract method";
    case JeandleInlineReason::NotCompilableUnbalancedMonitors:
      return "not compilable (unbalanced monitors)";
    case JeandleInlineReason::NotCompilableFlowAnalysisFailed:
      return "not compilable (flow analysis failed)";
    case JeandleInlineReason::CannotBeParsed:
      return "cannot be parsed";
    case JeandleInlineReason::MethodHolderNotInitialized:
      return "method holder not initialized";
    case JeandleInlineReason::DontInlineByAnnotation:
      return "disallowed by DontInline annotation";
    case JeandleInlineReason::MethodChangesCurrentThread:
      return "method changes current thread";
    case JeandleInlineReason::UnloadedSignatureClasses:
      return "unloaded signature classes";
    case JeandleInlineReason::DisallowedByCompileCommand:
      return "disallowed by CompileCommand";
    case JeandleInlineReason::DisallowedByCiReplay:
      return "disallowed by ciReplay";
    case JeandleInlineReason::AlreadyCompiledIntoMediumMethod:
      return "already compiled into a medium method";
    case JeandleInlineReason::AlreadyCompiledIntoBigMethod:
      return "already compiled into a big method";
    case JeandleInlineReason::HotMethodTooBig:
      return "hot method too big";
    case JeandleInlineReason::TooBig:
      return "too big";
    case JeandleInlineReason::ExceptionMethod:
      return "exception method";
    case JeandleInlineReason::NeverExecuted:
      return "never executed";
    case JeandleInlineReason::LowCallSiteFrequency:
      return "low call site frequency";
    case JeandleInlineReason::SizeGreaterThanDesiredMethodLimit:
      return "size > DesiredMethodLimit";
    case JeandleInlineReason::NodeCountInliningCutoff:
      return "node count inlining cutoff";
    case JeandleInlineReason::CallSiteNotReached:
      return "call site not reached";
    case JeandleInlineReason::NotAnAccessor:
      return "not an accessor";
    case JeandleInlineReason::MaxForceInlineLevel:
      return "MaxForceInlineLevel";
    case JeandleInlineReason::InliningTooDeep:
      return "inlining too deep";
    case JeandleInlineReason::RecursiveInliningTooDeep:
      return "recursive inlining too deep";
    case JeandleInlineReason::TooColdToInline:
      return "too cold to inline";
    case JeandleInlineReason::LLVMRootCalleeUnsupported:
      return "LLVM root callee unsupported";
    case JeandleInlineReason::LLVMGetInlineCalleeIRFailed:
      return "LLVM get inline callee IR failed";
    case JeandleInlineReason::LLVMMissingInlineCalleeDefinition:
      return "LLVM missing inline callee definition";
    case JeandleInlineReason::LLVMNotInlineViable:
      return "LLVM not inline viable";
    case JeandleInlineReason::LLVMInlineFailed:
      return "LLVM inline failed";
    default:
      return "unknown";
  }
}

// Returns the const section alignment for the current Jeandle compilation.
// Only returns a valid value when running inside a Jeandle compilation thread;
// returns -1 otherwise (e.g., non-compiler threads, or C1/C2 compiler threads
// even when UseJeandleCompiler is enabled).
int jeandle_const_section_alignment() {
  Thread* t = Thread::current_or_null();
  if (is_jeandle_compiler_thread(t)) {
    JeandleCompilation* comp = JeandleCompilation::current();
    if (comp != nullptr) {
      return comp->const_section_alignment();
    }
  }
  return -1;
}

class JeandleTraceTime : public TraceTime {
 private:
  JeandleTimerName _timer;

 public:
  JeandleTraceTime(const char* name, JeandleTimerName timer_name)
  : TraceTime(name, &jeandle_timers[timer_name], CITime || CITimeEach, CITimeVerbose),
    _timer(timer_name)
  {
      // If compile logging is needed in the future, add log->begin_head()/stamp()/end_head() here
  }

  ~JeandleTraceTime() = default;
};

JeandleCompilation::JeandleCompilation(llvm::TargetMachine* target_machine,
                                       llvm::DataLayout* data_layout,
                                       ciEnv* env,
                                       ciMethod* method,
                                       int entry_bci,
                                       bool should_install,
                                       DirectiveSet* directive,
                                       llvm::MemoryBuffer* template_buffer) :
                                       _target_machine(target_machine),
                                       _data_layout(data_layout),
                                       _env(env),
                                       _method(method),
                                       _name(method->get_Method()->name_and_sig_as_C_string()),
                                       _entry_bci(entry_bci),
                                       _context(std::make_unique<llvm::LLVMContext>()),
                                       _replay_inline_data(nullptr),
                                       _inline_tree_root(nullptr),
                                       _oops(),
                                       _oop_idx(0),
                                       _code(env, method, entry_bci != InvocationEntryBci),
                                       _error_msg(nullptr),
                                       _has_monitors(false),
                                       _const_section_alignment(-1) {

  const char* reason = check_can_parse(method);
  if (reason != nullptr) {
    report_error(reason);
  }

  JeandleTraceTime tt_total("Jeandle Compile", compilation_timer);

  // Setup compilation.
  initialize();
  setup_llvm_module(template_buffer);

  if (ProfileTraps) {
    // Match C2: make sure decompile_count can be tracked for recompilation cutoffs.
    method->ensure_method_data();
  }

  if (error_occurred()) {
    _env->record_method_not_compilable(_error_msg);
    return;
  }

  assert(directive != nullptr, "directive must exist");
  if (directive->ReplayInlineOption) {
    _replay_inline_data = ciReplay::load_inline_data(method, entry_bci, env->comp_level());
  }

  // Let's compile.
  compile_java_method();

  if (error_occurred()) {
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
                                       address routine_address,
                                       llvm::FunctionType* func_type) :
                                       _target_machine(target_machine),
                                       _data_layout(data_layout),
                                       _env(env),
                                       _method(nullptr),
                                       _name(name),
                                       _entry_bci(-1),
                                       _context(std::move(context)),
                                       _llvm_module(std::make_unique<llvm::Module>(name, *_context)),
                                       _replay_inline_data(nullptr),
                                       _inline_tree_root(nullptr),
                                       _oops(),
                                       _oop_idx(0),
                                       _code(_env, name),
                                       _error_msg(nullptr),
                                       _has_monitors(false),
                                       _const_section_alignment(-1) {
  initialize();

  _llvm_module->setDataLayout(*_data_layout);
  _llvm_module->setTargetTriple(_target_machine->getTargetTriple());
  JeandleCallVM::generate_call_VM(name, routine_address, func_type, *_llvm_module, _code);

  // Verify module in debug builds.
  DEBUG_ONLY({
    bool is_failed = llvm::verifyModule(*_llvm_module, &llvm::errs());
    JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(!is_failed, "module verify failed in Jeandle stub compilation");
  });

  if (JeandleDumpRuntimeStubs) {
    dump_ir(false);
  }

  // Optimize.
  llvm::jeandle::optimize(*_llvm_module, llvm::OptimizationLevel::O3,
                          llvm::jeandle::PipelineMode::StubCompilation);

  // Verify module in debug builds after optimization.
  DEBUG_ONLY({
    bool is_failed = llvm::verifyModule(*_llvm_module, &llvm::errs());
    JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(!is_failed, "module verify failed after optimization in Jeandle stub compilation");
  });

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

JeandleCompilation::~JeandleCompilation() {
  _env->set_compiler_data(nullptr);
}

const char* JeandleCompilation::check_can_parse(ciMethod* method) {
  // Certain method cannot be parsed at all:
  if ( method->is_native())                   return "native method";
  if ( method->is_abstract())                 return "abstract method";
  if (!method->has_balanced_monitors())       return "not compilable (unbalanced monitors)";
  if ( method->get_flow_analysis()->failing()) return "not compilable (flow analysis failed)";
  if (!method->can_be_parsed())               return "cannot be parsed";
  return nullptr;
}

std::string JeandleCompilation::next_oop_name(const char* klass_name) {
  assert(klass_name != nullptr, "klass_name can not be null");
  return std::string("oop_handle_") + std::string(klass_name) + "_" + std::to_string(_oop_idx++);
}

llvm::Value* JeandleCompilation::find_or_insert_oop(ciObject* oop) {
  assert(_llvm_module != nullptr, "llvm module must exist");
  jobject oop_handle = oop->constant_encoding();
  if (llvm::Value* global_oop_handle = _oops.lookup(oop_handle)) {
    return global_oop_handle;
  }
  int oop_id = _code.find_or_insert_oop(oop);
  std::string oop_name = _code.oop_handle_name(oop_id);
  llvm::Value* global = _llvm_module->getOrInsertGlobal(
      oop_name,
      JeandleType::java2llvm(BasicType::T_OBJECT, *_context));
  llvm::GlobalVariable* global_oop_handle = llvm::cast<llvm::GlobalVariable>(global);
  global_oop_handle->setDSOLocal(true);
  _oops[oop_handle] = global_oop_handle;
  return global_oop_handle;
}

bool JeandleCompilation::over_inlining_cutoff() const {
  assert(_llvm_module != nullptr, "llvm module must exist");
  if (JeandleNodeCountInliningCutoff == 0) {
    return true;
  }

  std::string root_name = JeandleFuncSig::root_method_name(
      _method, is_osr_compilation());
  llvm::Function* root = _llvm_module->getFunction(root_name);
  assert(root != nullptr, "root Java method function must exist");

  intx instruction_count = 0;
  for (llvm::Instruction& inst : llvm::instructions(root)) {
    (void)inst;
    if (++instruction_count > JeandleNodeCountInliningCutoff) {
      return true;
    }
  }
  return false;
}

JeandleInlineTree::JeandleInlineTree(JeandleInlineTree* caller_tree,
                                     ciMethod* method,
                                     int caller_bci,
                                     int max_inline_level,
                                     Arena* arena) :
                                     _caller_tree(caller_tree),
                                     _method(method),
                                     _caller_bci(caller_bci),
                                     _inline_depth(caller_tree == nullptr ? 0 : caller_tree->inline_depth() + 1),
                                     _max_inline_level(max_inline_level),
                                     _count_inline_bcs(method == nullptr ? 0 : method->code_size_for_inlining()),
                                     _reason(JeandleInlineReason::InlineHot),
                                     _subtrees(arena, 2, 0, nullptr) {
}

static methodHandle jeandle_method_handle(ciMethod* method) {
  return methodHandle(Thread::current(), method->get_Method());
}

static bool jeandle_is_unboxing_method(ciMethod* callee) {
  return EliminateAutoBox && callee->is_unboxing_method();
}

static bool jeandle_exceeds_desired_method_limit(ciMethod* callee, int inline_bcs) {
  if (!ClipInlining || inline_bcs < DesiredMethodLimit) {
    return false;
  }

  // Match C2's DesiredMethodLimit gate: an annotated @ForceInline method may
  // cross this limit only when IncrementalInline is enabled. C2 may then delay
  // the inline; Jeandle currently has no late inline queue, so there is no
  // should_delay state to record here.
  return !callee->force_inline() || !IncrementalInline;
}

static bool jeandle_is_init_with_ea(ciMethod* callee,
                                    ciMethod* caller,
                                    ciMethod* root) {
  if (!DoEscapeAnalysis || !EliminateAllocations) {
    return false;
  }
  if (callee->is_initializer()) {
    return true;
  }
  if (caller->is_initializer() &&
      caller != root &&
      caller->holder()->is_subclass_of(callee->holder())) {
    return true;
  }
  if (EliminateAutoBox && callee->is_boxing_method()) {
    return true;
  }

  // Match C2's foreach/Iterator special case when the CI environment can
  // resolve java.util.Iterator. This is still a heuristic; failure to load the
  // klass simply means the method does not get the EA bump.
  ciType* ret_type = callee->signature()->return_type();
  ciKlass* iterator_klass = ciEnv::current()->Iterator_klass();
  if (ret_type->is_loaded() &&
      iterator_klass->is_loaded() &&
      ret_type->is_subtype_of(iterator_klass)) {
    return true;
  }
  return false;
}

#ifdef ASSERT
static void jeandle_report_invalid_invoke_bci(ciMethod* caller,
                                              int bci,
                                              const char* reason,
                                              int previous_bci = -1,
                                              Bytecodes::Code previous_bc = Bytecodes::_illegal,
                                              int next_bci = -1,
                                              Bytecodes::Code next_bc = Bytecodes::_illegal) {
  ResourceMark rm;
  stringStream method_name;
  caller->dump_name_as_ascii(&method_name);
  fatal("invalid Jeandle inline caller bci: method=%s bci=%d code_size=%d reason=%s "
        "previous_bci=%d previous_bc=%s next_bci=%d next_bc=%s",
        method_name.as_string(),
        bci,
        caller->code_size(),
        reason,
        previous_bci,
        previous_bci >= 0 ? Bytecodes::name(previous_bc) : "<none>",
        next_bci,
        next_bci >= 0 ? Bytecodes::name(next_bc) : "<none>");
}
#endif

static bool jeandle_is_valid_invoke_bci(ciMethod* caller, int bci) {
  if (bci < 0 || bci >= caller->code_size()) {
    DEBUG_ONLY(jeandle_report_invalid_invoke_bci(caller, bci, "bci is out of range");)
    return false;
  }

  // The LLVM inliner reports a raw integer bci. Make sure it is a bytecode
  // boundary before reading the opcode; otherwise operand bytes can be mistaken
  // for bytecodes and trip Bytecodes::check() in slowdebug builds.
  ciBytecodeStream iter(caller);
  int previous_bci = -1;
  Bytecodes::Code previous_bc = Bytecodes::_illegal;
  for (Bytecodes::Code bc = iter.next(); bc != ciBytecodeStream::EOBC(); bc = iter.next()) {
    if (iter.cur_bci() == bci) {
      if (!Bytecodes::is_invoke(bc)) {
        DEBUG_ONLY(jeandle_report_invalid_invoke_bci(caller, bci, "bci is not an invoke bytecode", iter.cur_bci(), bc);)
        return false;
      }
      return Bytecodes::is_invoke(bc);
    }
    if (iter.cur_bci() > bci) {
      DEBUG_ONLY(jeandle_report_invalid_invoke_bci(caller, bci, "bci is not a bytecode boundary", previous_bci, previous_bc, iter.cur_bci(), bc);)
      return false;
    }
    previous_bci = iter.cur_bci();
    previous_bc = bc;
  }
  DEBUG_ONLY(jeandle_report_invalid_invoke_bci(caller, bci, "bci is not a bytecode boundary", previous_bci, previous_bc);)
  return false;
}

bool JeandleInlineTree::pass_initial_checks(JeandleCompilation* comp,
                                            ciMethod* caller,
                                            int caller_bci,
                                            ciMethod* callee,
                                            JeandleInlineReason& reason) {
  if (callee == nullptr || caller == nullptr) {
    reason = JeandleInlineReason::FailedInitialChecks;
    return false;
  }
  if (!jeandle_is_valid_invoke_bci(caller, caller_bci)) {
    reason = JeandleInlineReason::FailedInitialChecks;
    return false;
  }

  ciInstanceKlass* callee_holder = callee->holder();
  if (!callee_holder->is_loaded()) {
    reason = JeandleInlineReason::FailedInitialChecks;
    return false;
  }
  if (!callee_holder->is_initialized() &&
      comp->compiled_code()->needs_clinit_barrier(callee_holder, caller)) {
    reason = JeandleInlineReason::MethodHolderNotInitialized;
    return false;
  }

  if (!UseInterpreter) {
    // C2 performs extra call-site resolution checks under -Xcomp. Keep the
    // same guard here so Jeandle does not inline from unresolved constant-pool
    // state when interpreter profiling is unavailable.
    ciBytecodeStream iter(caller);
    iter.force_bci(caller_bci);
    Bytecodes::Code call_bc = iter.cur_bc();
    if (call_bc != Bytecodes::_invokedynamic) {
      int index = iter.get_index_u2_cpcache();
      if (!caller->is_klass_loaded(index, call_bc, true)) {
        reason = JeandleInlineReason::FailedInitialChecks;
        return false;
      }
      if (!caller->check_call(index, call_bc == Bytecodes::_invokestatic)) {
        reason = JeandleInlineReason::FailedInitialChecks;
        return false;
      }
    }
  }
  return true;
}

JeandleInlineReason JeandleInlineTree::check_can_parse(ciMethod* callee) const {
  if (callee->is_native())                    return JeandleInlineReason::NativeMethod;
  if (callee->is_abstract())                  return JeandleInlineReason::AbstractMethod;
  if (!callee->has_balanced_monitors())       return JeandleInlineReason::NotCompilableUnbalancedMonitors;
  if (callee->get_flow_analysis()->failing()) return JeandleInlineReason::NotCompilableFlowAnalysisFailed;
  if (!callee->can_be_parsed())               return JeandleInlineReason::CannotBeParsed;
  return JeandleInlineReason::InlineHot;
}

bool JeandleInlineTree::should_inline(JeandleCompilation* comp,
                                      ciMethod* callee,
                                      ciMethod* caller,
                                      int caller_bci,
                                      bool& forced_inline,
                                      ciCallProfile& profile,
                                      JeandleInlineReason& reason) {
  if (CompilerOracle::should_inline(jeandle_method_handle(callee))) {
    forced_inline = true;
    reason = JeandleInlineReason::ForceInlineByCompileCommand;
    return true;
  }
  if (callee->force_inline()) {
    forced_inline = true;
    reason = JeandleInlineReason::ForceInlineByAnnotation;
    return true;
  }

  bool should_delay = false;
  int replay_inline_depth = inline_depth() + 1;
  if (ciReplay::should_inline(comp->replay_inline_data(),
                              callee,
                              caller_bci,
                              replay_inline_depth,
                              should_delay)) {
    // TODO: Replay records can mark an inline as late/incremental. Jeandle has
    // no late inline queue yet, so replay-forced inlines are performed
    // immediately even when should_delay is true.
    forced_inline = true;
    reason = should_delay ? JeandleInlineReason::ForceIncrementalInlineByCiReplay :
                            JeandleInlineReason::ForceInlineByCiReplay;
    return true;
  }

  int size = callee->code_size_for_inlining();
  if (callee->interpreter_throwout_count() > InlineThrowCount &&
      size < InlineThrowMaxSize) {
    reason = JeandleInlineReason::ManyThrows;
    return true;
  }

  int max_inline_size = MaxInlineSize;
  int call_site_count = caller->scale_count(profile.count());
  int invoke_count = caller->interpreter_invocation_count();
  assert(invoke_count != 0, "require invocation count greater than zero");
  double freq = (double)call_site_count / (double)invoke_count;

  if (freq >= InlineFrequencyRatio ||
      jeandle_is_unboxing_method(callee) ||
      jeandle_is_init_with_ea(callee, caller, comp->method())) {
    max_inline_size = FreqInlineSize;
  } else if (callee->has_compiled_code() &&
             callee->inline_instructions_size() > InlineSmallCode / 4) {
    reason = JeandleInlineReason::AlreadyCompiledIntoMediumMethod;
    return false;
  }

  if (size > max_inline_size) {
    reason = max_inline_size == FreqInlineSize ? JeandleInlineReason::HotMethodTooBig :
                                                 JeandleInlineReason::TooBig;
    return false;
  }
  reason = JeandleInlineReason::InlineHot;
  return true;
}

bool JeandleInlineTree::should_not_inline(JeandleCompilation* comp,
                                          ciMethod* callee,
                                          ciMethod* caller,
                                          int caller_bci,
                                          ciCallProfile& profile,
                                          JeandleInlineReason& reason) {
  if (callee->is_abstract()) {
    reason = JeandleInlineReason::AbstractMethod;
    return true;
  }
  if (!callee->holder()->is_initialized() &&
      comp->compiled_code()->needs_clinit_barrier(callee->holder(), caller)) {
    reason = JeandleInlineReason::MethodHolderNotInitialized;
    return true;
  }
  if (callee->is_native()) {
    reason = JeandleInlineReason::NativeMethod;
    return true;
  }
  if (callee->dont_inline()) {
    reason = JeandleInlineReason::DontInlineByAnnotation;
    return true;
  }
  if (callee->changes_current_thread() &&
      !comp->method()->changes_current_thread()) {
    reason = JeandleInlineReason::MethodChangesCurrentThread;
    return true;
  }
  if (callee->has_unloaded_classes_in_signature()) {
    reason = JeandleInlineReason::UnloadedSignatureClasses;
    return true;
  }

  // Jeandle does not have C2 DirectiveSet, but CompilerOracle exposes the
  // CompileCommand decisions we can honor here. Match C2's order: explicit
  // inline command wins over explicit no-inline, then annotation force-inline
  // can override only heuristic objections below.
  if (CompilerOracle::should_inline(jeandle_method_handle(callee))) {
    reason = JeandleInlineReason::ForceInlineByCompileCommand;
    return false;
  }
  if (CompilerOracle::should_not_inline(jeandle_method_handle(callee))) {
    reason = JeandleInlineReason::DisallowedByCompileCommand;
    return true;
  }

  bool should_delay = false;
  int replay_inline_depth = inline_depth() + 1;
  if (ciReplay::should_inline(comp->replay_inline_data(),
                              callee,
                              caller_bci,
                              replay_inline_depth,
                              should_delay)) {
    // TODO: Jeandle currently ignores replay late-inline timing and treats this
    // as an immediate replay-forced inline.
    reason = should_delay ? JeandleInlineReason::ForceIncrementalInlineByCiReplay :
                            JeandleInlineReason::ForceInlineByCiReplay;
    return false;
  }
  if (ciReplay::should_not_inline(comp->replay_inline_data(),
                                  callee,
                                  caller_bci,
                                  replay_inline_depth)) {
    reason = JeandleInlineReason::DisallowedByCiReplay;
    return true;
  }
  if (ciReplay::should_not_inline(callee)) {
    reason = JeandleInlineReason::DisallowedByCiReplay;
    return true;
  }

  if (callee->force_inline()) {
    reason = JeandleInlineReason::ForceInlineByAnnotation;
    return false;
  }
  if (jeandle_is_unboxing_method(callee)) {
    return false;
  }
  if (callee->has_compiled_code() &&
      callee->inline_instructions_size() > InlineSmallCode) {
    reason = JeandleInlineReason::AlreadyCompiledIntoBigMethod;
    return true;
  }

  if (caller_tree() != nullptr &&
      callee->holder()->is_subclass_of(ciEnv::current()->Throwable_klass())) {
    const JeandleInlineTree* top = this;
    while (top->caller_tree() != nullptr) {
      top = top->caller_tree();
    }
    if (!top->method()->holder()->is_subclass_of(ciEnv::current()->Throwable_klass())) {
      reason = JeandleInlineReason::ExceptionMethod;
      return true;
    }
  }

  if (callee->code_size() <= MaxTrivialSize) {
    return false;
  }

  if (UseInterpreter) {
    if (!callee->has_compiled_code() &&
        !callee->was_executed_more_than(0)) {
      reason = JeandleInlineReason::NeverExecuted;
      return true;
    }
    if (jeandle_is_init_with_ea(callee, caller, comp->method())) {
      return false;
    }
    if (MinInlineFrequencyRatio > 0) {
      int call_site_count = caller->scale_count(profile.count());
      int invoke_count = caller->interpreter_invocation_count();
      assert(invoke_count != 0, "require invocation count greater than zero");
      double freq = (double)call_site_count / (double)invoke_count;
      int cp_min_inv = MAX2(1, CompilationPolicy::min_invocations());
      double min_freq = MAX2(MinInlineFrequencyRatio, 1.0 / cp_min_inv);
      if (freq < min_freq) {
        reason = JeandleInlineReason::LowCallSiteFrequency;
        return true;
      }
    }
  }

  return false;
}

bool JeandleInlineTree::is_not_reached(ciMethod* callee,
                                       ciMethod* caller,
                                       int caller_bci,
                                       ciCallProfile& profile) {
  if (!UseInterpreter) {
    return false;
  }
  if (profile.count() > 0) {
    return false;
  }
  if (!callee->was_executed_more_than(0)) {
    return true;
  }
  if (caller->is_not_reached(caller_bci)) {
    return true;
  }
  if (profile.count() == -1) {
    return false;
  }

  ciMethodBlocks* caller_blocks = caller->get_method_blocks();
  return caller_blocks->block_containing(caller_bci)->start_bci() != 0;
}

bool JeandleInlineTree::try_to_inline(JeandleCompilation* comp,
                                      ciMethod* callee,
                                      ciMethod* caller,
                                      int caller_bci,
                                      ciCallProfile& profile,
                                      JeandleInlineReason& reason) {
  bool forced_inline = false;
  if (jeandle_exceeds_desired_method_limit(callee, (int)count_inline_bcs())) {
    reason = JeandleInlineReason::SizeGreaterThanDesiredMethodLimit;
    return false;
  }

  if (!should_inline(comp, callee, caller, caller_bci, forced_inline, profile, reason)) {
    return false;
  }
  if (should_not_inline(comp, callee, caller, caller_bci, profile, reason)) {
    return false;
  }

  if (InlineAccessors && callee->is_accessor()) {
    reason = JeandleInlineReason::Accessor;
    return true;
  }

  if (callee->code_size() > MaxTrivialSize) {
    // Match C2's parse-time node budget check with an LLVM IR instruction
    // budget for the root Java method. Jeandle has no late inline queue yet,
    // so non-forced inline candidates are rejected instead of delayed.
    if (comp->over_inlining_cutoff() &&
        (!callee->force_inline() || !IncrementalInline)) {
      reason = JeandleInlineReason::NodeCountInliningCutoff;
      return false;
    }
    if (!forced_inline &&
        !(!UseInterpreter && jeandle_is_init_with_ea(callee, caller, comp->method())) &&
        is_not_reached(callee, caller, caller_bci, profile)) {
      reason = JeandleInlineReason::CallSiteNotReached;
      return false;
    }
  }

  // If global inlining is disabled, only the InlineAccessors fast path above
  // may pass. LLVM normally enforces the same mode before invoking this policy.
  if (!Inline) {
    reason = JeandleInlineReason::NotAnAccessor;
    return false;
  }

  if (inline_depth() > MaxForceInlineLevel) {
    reason = JeandleInlineReason::MaxForceInlineLevel;
    return false;
  }
  if (inline_depth() > max_inline_level()) {
    if (!callee->force_inline() || !IncrementalInline) {
      reason = JeandleInlineReason::InliningTooDeep;
      return false;
    }
    // TODO: C2 delays this inline when not already in incremental inlining.
    // Jeandle currently has no late inline queue, so forced inline continues.
  }

  int recursive_inline_level = 0;
  for (const JeandleInlineTree* tree = this; tree != nullptr; tree = tree->caller_tree()) {
    if (tree->method() == callee) {
      recursive_inline_level++;
    }
  }
  if (recursive_inline_level > MaxRecursiveInlineLevel) {
    reason = JeandleInlineReason::RecursiveInliningTooDeep;
    return false;
  }
  // TODO: C2 gives compiled lambda forms special recursion handling based on
  // receiver identity from JVMState. JeandleInlineTree currently has no operand
  // map, so recursion is counted conservatively by method identity.

  int size = callee->code_size_for_inlining();
  if (jeandle_exceeds_desired_method_limit(callee, (int)count_inline_bcs() + size)) {
    reason = JeandleInlineReason::SizeGreaterThanDesiredMethodLimit;
    return false;
  }

  return true;
}

bool JeandleInlineTree::ok_to_inline(JeandleCompilation* comp,
                                     ciMethod* callee,
                                     int caller_bci,
                                     JeandleInlineReason& reason) {
  ciMethod* caller = method();
  assert(caller != nullptr, "caller method must exist");
  reason = JeandleInlineReason::InlineHot;

  // Jeandle gets here only after LLVM has selected a monomorphic Java call
  // site. CHA/PGO monomorphization is represented in IR before this policy;
  // this method mirrors C2's post-target-selection inline checks.
  if (!pass_initial_checks(comp, caller, caller_bci, callee, reason)) {
    return false;
  }

  // Keep parseability separate from heuristic policy, matching C2's
  // InlineTree::ok_to_inline structure.
  JeandleInlineReason parse_reason = check_can_parse(callee);
  if (parse_reason != JeandleInlineReason::InlineHot) {
    reason = parse_reason;
    return false;
  }

  ciCallProfile profile = caller->call_profile_at_bci(caller_bci);
  return try_to_inline(comp, callee, caller, caller_bci, profile, reason);
}

JeandleInlineTree* JeandleInlineTree::callee_at(int caller_bci, ciMethod* callee) const {
  for (int i = 0; i < _subtrees.length(); i++) {
    JeandleInlineTree* subtree = _subtrees.at(i);
    if (subtree->caller_bci() == caller_bci && subtree->method() == callee) {
      return subtree;
    }
  }
  return nullptr;
}

JeandleInlineTree* JeandleInlineTree::allocate_inline_tree_for_callee(ciMethod* callee,
                                                                      int caller_bci,
                                                                      Arena* arena) {
  // Allocate before LLVM mutates IR, but do not append the node until the inline succeeds.
  int max_inline_level_adjust = 0;
  if (method() != nullptr) {
    if (method()->is_compiled_lambda_form()) {
      max_inline_level_adjust += 1; // Do not count actions in MH or indy adapter frames.
    } else if (callee->is_method_handle_intrinsic() ||
               callee->is_compiled_lambda_form()) {
      max_inline_level_adjust += 1; // Do not count method handle calls from java.lang.invoke implementation.
    }
  }
  return new (arena) JeandleInlineTree(this, callee, caller_bci,
                                       max_inline_level() + max_inline_level_adjust,
                                       arena);
}

void JeandleInlineTree::commit_inline_tree_for_callee(JeandleInlineTree* callee_tree) {
  assert(callee_tree != nullptr, "callee inline tree must exist");
  assert(callee_tree->caller_tree() == this, "callee inline tree must belong to this caller");
  assert(callee_at(callee_tree->caller_bci(), callee_tree->method()) == nullptr,
         "callee inline tree must not be committed twice");
  // From this point on, the tree reflects a successful LLVM inline and can
  // contribute to replay data, inline scope lookup, and bytecode-size limits.
  _subtrees.append(callee_tree);
  for (JeandleInlineTree* caller = this; caller != nullptr; caller = caller->caller_tree()) {
    caller->_count_inline_bcs += callee_tree->count_inline_bcs();
  }
}

int JeandleInlineTree::count() const {
  int result = 1;
  for (int i = 0; i < _subtrees.length(); i++) {
    result += _subtrees.at(i)->count();
  }
  return result;
}

void JeandleInlineTree::dump_replay_data(outputStream* out, int depth_adjust) const {
  // Keep the replay inline record format identical to C2:
  //   <inline_depth> <caller_bci> <inline_late> <method>
  // Jeandle currently lets LLVM perform inlining immediately, so there is no
  // late inline queue equivalent to C2's late inlining CallGenerator.
  const int inline_late = 0;
  out->print(" %d %d %d ", inline_depth() + depth_adjust, caller_bci(), inline_late);
  method()->dump_name_as_ascii(out);
  for (int i = 0; i < _subtrees.length(); i++) {
    _subtrees.at(i)->dump_replay_data(out, depth_adjust);
  }
}

void JeandleCompilation::initialize_inline_tree() {
  assert(_method != nullptr, "root inline tree only exists for Java method compilation");
  assert(_inline_tree_root == nullptr, "root inline tree must be initialized once");
  _inline_tree_root = new (_arena) JeandleInlineTree(nullptr, _method, -1, MaxInlineLevel, _arena);
}

JeandleInlineTree* JeandleCompilation::inline_tree_for_scope(int scope_id) const {
  if (scope_id == -1) {
    return _inline_tree_root;
  }
  assert(scope_id >= 0, "invalid inline scope id");
  assert(scope_id < _inline_trees.length(), "inline scope must have been recorded");
  return _inline_trees.at(scope_id);
}

JeandleInlineTree* JeandleCompilation::prepare_inline_tree_for_callee(int caller_scope_id,
                                                                      int caller_bci,
                                                                      ciMethod* callee) {
  JeandleInlineTree* caller_tree = inline_tree_for_scope(caller_scope_id);
  assert(caller_tree != nullptr, "caller inline tree must exist");

  JeandleInlineTree* committed_tree = caller_tree->callee_at(caller_bci, callee);
  if (committed_tree != nullptr) {
    return committed_tree;
  }
  for (int i = 0; i < _pending_inline_trees.length(); i++) {
    JeandlePendingInlineTree* pending = _pending_inline_trees.at(i);
    if (pending->matches(caller_scope_id, caller_bci, callee)) {
      return pending->_callee_tree;
    }
  }

  JeandleInlineTree* callee_tree = caller_tree->allocate_inline_tree_for_callee(callee, caller_bci, _arena);
  _pending_inline_trees.append(new (_arena) JeandlePendingInlineTree(caller_scope_id,
                                                                     caller_bci,
                                                                     callee,
                                                                     callee_tree));
  return callee_tree;
}

void JeandleCompilation::commit_inline_tree_for_callee(int caller_scope_id,
                                                       int caller_bci,
                                                       ciMethod* callee) {
  JeandleInlineTree* caller_tree = inline_tree_for_scope(caller_scope_id);
  assert(caller_tree != nullptr, "caller inline tree must exist");

  JeandleInlineTree* committed_tree = caller_tree->callee_at(caller_bci, callee);
  if (committed_tree != nullptr) {
    _inline_trees.append(committed_tree);
    return;
  }

  JeandleInlineTree* callee_tree = nullptr;
  for (int i = 0; i < _pending_inline_trees.length(); i++) {
    JeandlePendingInlineTree* pending = _pending_inline_trees.at(i);
    if (pending->matches(caller_scope_id, caller_bci, callee)) {
      callee_tree = pending->_callee_tree;
      _pending_inline_trees.remove_at(i);
      break;
    }
  }
  assert(callee_tree != nullptr, "callee inline tree must have been prepared before a successful inline");

  caller_tree->commit_inline_tree_for_callee(callee_tree);
  // Keep this array in lockstep with LLVM's InlineScopes vector. LLVM assigns
  // the new scope id after a successful RecordInlineResult using the same
  // successful-inline order, so appending here produces the id that cloned child
  // call sites use.
  _inline_trees.append(callee_tree);
}

void JeandleCompilation::record_inline_failure(int caller_scope_id,
                                               int caller_bci,
                                               ciMethod* callee,
                                               JeandleInlineReason reason) {
  for (int i = 0; i < _inline_failures.length(); i++) {
    JeandleInlineFailure* failure = _inline_failures.at(i);
    if (failure->caller_scope_id() == caller_scope_id &&
        failure->caller_bci() == caller_bci &&
        failure->callee() == callee &&
        failure->reason() == reason) {
      return;
    }
  }
  _inline_failures.append(new (_arena) JeandleInlineFailure(caller_scope_id,
                                                            caller_bci,
                                                            callee,
                                                            reason));
}

static void insert_inline_tree_by_bci(GrowableArray<JeandleInlineTree*>* trees,
                                      JeandleInlineTree* tree) {
  int insert_at = trees->length();
  while (insert_at > 0 && tree->caller_bci() < trees->at(insert_at - 1)->caller_bci()) {
    insert_at--;
  }
  trees->insert_before(insert_at, tree);
}

static void insert_inline_failure_by_bci(GrowableArray<JeandleInlineFailure*>* failures,
                                         JeandleInlineFailure* failure) {
  int insert_at = failures->length();
  while (insert_at > 0 && failure->caller_bci() < failures->at(insert_at - 1)->caller_bci()) {
    insert_at--;
  }
  failures->insert_before(insert_at, failure);
}

int JeandleCompilation::inline_scope_id_for_tree(const JeandleInlineTree* tree) const {
  if (tree == _inline_tree_root) {
    return -1;
  }
  for (int i = 0; i < _inline_trees.length(); i++) {
    if (_inline_trees.at(i) == tree) {
      return i;
    }
  }
  ShouldNotReachHere();
  return -1;
}

void JeandleCompilation::print_inline_tree_impl(outputStream* out,
                                                const JeandleInlineTree* tree,
                                                int scope_id,
                                                const std::string& prefix) const {
  // Diagnostics merge successful children and failed attempts for this scope.
  // Keep the semantic tree untouched, and sort the merged view by caller bci so
  // the output follows bytecode order instead of callback order.
  GrowableArray<JeandleInlineTree*> subtrees;
  for (int i = 0; i < tree->subtrees().length(); i++) {
    insert_inline_tree_by_bci(&subtrees, tree->subtrees().at(i));
  }

  GrowableArray<JeandleInlineFailure*> failures;
  for (int i = 0; i < _inline_failures.length(); i++) {
    JeandleInlineFailure* failure = _inline_failures.at(i);
    if (failure->caller_scope_id() == scope_id) {
      insert_inline_failure_by_bci(&failures, failure);
    }
  }

  int subtree_index = 0;
  int failure_index = 0;
  int total = subtrees.length() + failures.length();
  for (int i = 0; i < total; i++) {
    bool use_failure;
    if (subtree_index >= subtrees.length()) {
      use_failure = true;
    } else if (failure_index >= failures.length()) {
      use_failure = false;
    } else {
      use_failure = failures.at(failure_index)->caller_bci() <
                    subtrees.at(subtree_index)->caller_bci();
    }

    bool is_last = i == total - 1;
    const char* branch = is_last ? "`- " : "|- ";
    std::string child_prefix = prefix + (is_last ? "   " : "|  ");
    if (use_failure) {
      JeandleInlineFailure* failure = failures.at(failure_index++);
      // Prefix failed attempts with "X" so large trees can be scanned from the
      // left edge: "@bci" means inlined, while "X@bci" means not inlined.
      out->print("%s%sX@%d ", prefix.c_str(), branch, failure->caller_bci());
      print_inline_tree_method(out, failure->callee());
      out->print("  [failed: %s]", jeandle_inline_reason_name(failure->reason()));
      out->cr();
    } else {
      JeandleInlineTree* subtree = subtrees.at(subtree_index++);
      out->print("%s%s@%d ", prefix.c_str(), branch, subtree->caller_bci());
      print_inline_tree_method(out, subtree->method());
      out->print("  [%s]", jeandle_inline_reason_name(subtree->reason()));
      out->cr();
      print_inline_tree_impl(out, subtree, inline_scope_id_for_tree(subtree), child_prefix);
    }
  }
}

void JeandleCompilation::print_inline_tree(outputStream* out) const {
  assert(_inline_tree_root != nullptr, "root inline tree must exist");
  assert(_inline_tree_root->method() != nullptr, "root inline tree node must have a method");
  ResourceMark rm;
  print_inline_tree_method(out, _inline_tree_root->method());
  out->cr();
  print_inline_tree_impl(out, _inline_tree_root, -1, "");
}

void JeandleCompilation::dump_inline_data(outputStream* out) {
  if (_inline_tree_root != nullptr) {
    out->print(" inline %d", _inline_tree_root->count());
    _inline_tree_root->dump_replay_data(out);
  }
}

void JeandleCompilation::dump_inline_data_reduced(outputStream* out) {
  assert(ReplayReduce, "");
  if (_inline_tree_root == nullptr) {
    return;
  }

  // Match C2's ReplayReduce shape: emit one synthetic "compile" line for each
  // depth-1 inline subtree, as if that inlinee were compiled as an entry method.
  // This makes it possible to iteratively shrink replay files while preserving
  // the inline decisions below that subtree.
  for (int i = 0; i < _inline_tree_root->subtrees().length(); i++) {
    JeandleInlineTree* sub = _inline_tree_root->subtrees().at(i);
    if (sub->inline_depth() != 1) {
      continue;
    }

    ciMethod* method = sub->method();
    int entry_bci = InvocationEntryBci;
    int comp_level = _env->task()->comp_level();
    out->print("compile ");
    method->dump_name_as_ascii(out);
    out->print(" %d %d", entry_bci, comp_level);
    out->print(" inline %d", sub->count());
    sub->dump_replay_data(out, -1);
    out->cr();
  }
}

static std::string construct_dump_path(const std::string& method_name,
                                       const std::string& timestamp,
                                       const std::string& suffix);

void JeandleCompilation::dump_inline_callee_replay_module() {
  assert(JeandleRecordVMCallbacks, "inline callee replay module is only dumped when recording VM callbacks");
  assert(_llvm_module != nullptr, "llvm module must exist");
  std::unique_ptr<llvm::Module> replay_module = llvm::CloneModule(*_llvm_module);
  assert(replay_module != nullptr, "failed to clone inline callee replay module");
  std::string root_name = JeandleFuncSig::root_method_name(_method, is_osr_compilation());

  // Keep only non-root Java method bodies for replay. Calls inside those methods
  // may still reference helper/runtime declarations, so non-replay functions are
  // first reduced to declarations and then erased only when they are unused.
  for (llvm::Function& F : *replay_module) {
    bool is_non_root_java_method =
        F.getFnAttribute(llvm::jeandle::Attribute::JavaMethod).isStringAttribute() &&
        F.getName() != root_name;
    if (is_non_root_java_method || F.isDeclaration()) {
      continue;
    }
    F.deleteBody();
  }

  llvm::SmallVector<llvm::Function*, 16> unused_functions;
  for (llvm::Function& F : *replay_module) {
    bool is_non_root_java_method =
        F.getFnAttribute(llvm::jeandle::Attribute::JavaMethod).isStringAttribute() &&
        F.getName() != root_name;
    if (!is_non_root_java_method && F.isDeclaration() && F.use_empty()) {
      unused_functions.push_back(&F);
    }
  }
  for (llvm::Function* F : unused_functions) {
    F->eraseFromParent();
  }

  std::string dump_path = construct_dump_path(_llvm_module->getModuleIdentifier(), _comp_start_time, "_inline_callees.ll");
  std::error_code err_code;
  llvm::raw_fd_ostream dump_stream(dump_path, err_code, llvm::sys::fs::OF_TextWithCRLF);

  if (err_code) {
    log_warning(jeandle)("Could not open inline callee IR replay file: %s, %s\n",
                         dump_path.c_str(),
                         err_code.message().c_str());
    return;
  }

  replay_module->print(dump_stream, nullptr);
}

void JeandleCompilation::install_code() {
  if (JeandlePrintInlineTree && _inline_tree_root != nullptr) {
    stringStream inline_tree;
    inline_tree.print_cr("Jeandle inline tree:");
    print_inline_tree(&inline_tree);

    ttyLocker ttyl;
    tty->print("%s", inline_tree.as_string());
  }

  _env->register_method(_method,
                        _entry_bci,
                        _code.offsets(),
                        _code.orig_pc_offset_in_bytes(),
                        _code.code_buffer(),
                        _code.frame_size(),
                        _env->debug_info()->_oopmaps,
                        _code.exception_handler_table(),
                        _code.implicit_exception_table(),
                        CompilerThread::current()->compiler(),
                        false, // temporary value
                        false, // temporary value
                        _has_monitors,
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

  Copy::zero_to_bytes(_trap_hist, sizeof(_trap_hist));
  _decompile_count = 0;

  set_has_monitors(false);

  // Get timestamp to mark dump files.
  auto now = std::chrono::system_clock::now();
  auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch());
  _comp_start_time = std::to_string(duration.count());
}

void JeandleCompilation::setup_llvm_module(llvm::MemoryBuffer* template_buffer) {
  // Get template module from the global memory buffer.
  llvm::Expected<std::unique_ptr<llvm::Module>> module_or_error =
      parseBitcodeFile(template_buffer->getMemBufferRef(), *_context);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(module_or_error, "Failed to parse template bitcode");
  _llvm_module = std::move(module_or_error.get());
  assert(_llvm_module != nullptr, "invalid llvm module");

  _llvm_module->setModuleIdentifier(JeandleFuncSig::method_name(_method));
  _llvm_module->setDataLayout(*_data_layout);
  _llvm_module->setTargetTriple(_target_machine->getTargetTriple());
  initialize_inline_tree();

  llvm::NamedMDNode* metadata_node = _llvm_module->getOrInsertNamedMetadata(llvm::jeandle::Metadata::JavaMethodCompilation);
  assert(metadata_node != nullptr, "invalid metadata node");
}

static std::string construct_dump_path(const std::string& method_name,
                                       const std::string& timestamp,
                                       const std::string& suffix) {
  assert(suffix == ".ll" || suffix == "_optimized.ll" || suffix == ".o" || suffix == ".cblog" || suffix == "_inline_callees.ll",
         "invalid suffix for dump file of Jeandle compiler");
  std::string dump_dir = JeandleDumpDirectory ? std::string(JeandleDumpDirectory) : std::string("./");

  // Full name.
  std::string file_name = dump_dir + '/' + method_name + '_' + timestamp + suffix;
  // Normalize the path and remove redundant separators.
  std::filesystem::path clean_path(std::move(file_name));

  return clean_path.lexically_normal().string();
}

void JeandleCompilation::compile_java_method() {
  // Build basic blocks. Then fill basic blocks with LLVM IR.
  {
    JeandleTraceTime tt_abstract_interpreter("Jeandle Abstract Interpret", abstract_interpreter_timer);
    JeandleParseContext parse_context = JeandleParseContext::root(_method);
    JeandleAbstractInterpreter interpret(parse_context, _entry_bci, *_llvm_module, _code, _trap_hist);
  }

  if (JeandleDumpIR) {
    dump_ir(false);
  }

  RETURN_VOID_ON_JEANDLE_ERROR();

  // Verify module in debug builds.
  DEBUG_ONLY({
    bool is_failed = llvm::verifyModule(*_llvm_module, &llvm::errs());
    JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(!is_failed, "module verify failed in Jeandle compilation");
  });

  // Scope the VM callback recorder to the optimization step.
  // Each concurrent compilation gets its own recorder via thread-local storage.
  std::optional<llvm::jeandle::VMCallbackLogRecorder> recorder;
  if (JeandleRecordVMCallbacks) {
    recorder.emplace();
  }

  // Optimize.
  {
    JeandleTraceTime tt_optimize("Jeandle LLVM Optimize", llvm_optimizer_timer);
    llvm::jeandle::optimize(*_llvm_module, llvm::OptimizationLevel::O3,
                            llvm::jeandle::PipelineMode::MethodCompilation);
  }

  // Verify module in debug builds after optimization.
  DEBUG_ONLY({
    bool is_failed = llvm::verifyModule(*_llvm_module, &llvm::errs());
    JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(!is_failed, "module verify failed after optimization in Jeandle compilation");
  });

  // Dump the VM callback log for this compilation.
  if (JeandleRecordVMCallbacks) {
    std::string dump_path = construct_dump_path(_llvm_module->getModuleIdentifier(), _comp_start_time, ".cblog");
    if (llvm::Error Err = recorder->dump(dump_path)) {
      log_warning(jeandle)("Could not dump VM callback log: %s",
                           llvm::toString(std::move(Err)).c_str());
    }
    // Destroy the recorder early to clear the thread-local ActiveRecorder,
    // so that any VM callbacks invoked during subsequent steps (dump_ir,
    // compile_module, etc.) are not incorrectly recorded.
    recorder.reset();
  }

  if (JeandleDumpIR) {
    dump_ir(true);
  }

  // Compile the module to an object file.
  {
    JeandleTraceTime tt_codegen("Jeandle LLVM CodeGen", llvm_codegen_timer);
    compile_module();
  }

  if (JeandleDumpObjects) {
    dump_obj();
  }

  RETURN_VOID_ON_JEANDLE_ERROR();

  // Unpack LLVM code information. Generate relocations, stubs and debug information.
  {
    JeandleTraceTime tt_finalize("Jeandle Finalize", finalize_timer);
    _code.finalize();
  }

  jeandle_compilation_count++;
}

void JeandleCompilation::compile_module() {
  // Hold binary codes.
  llvm::SmallVector<char, 0> obj_buffer;

  {
    llvm::raw_svector_ostream obj_stream(obj_buffer);

    llvm::legacy::PassManager pm;
    llvm::MCContext *ctx;

    bool unsupported = _target_machine->addPassesToEmitMC(pm, ctx, obj_stream);
    JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(!unsupported, "target does not support MC emission");

    pm.run(*_llvm_module);
  }

  auto object = std::make_unique<llvm::SmallVectorMemoryBuffer>(std::move(obj_buffer),
                                                                _llvm_module->getModuleIdentifier(),
                                                                false);
  _code.install_obj(std::move(object));
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
  std::string dump_path = construct_dump_path(_llvm_module->getModuleIdentifier(), _comp_start_time, optimized ? "_optimized.ll" : ".ll");
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

void JeandleCompilation::print_timers() {
  if (!CITime) {
    return;
  }
  tty->print_cr("    Jeandle Compile Time: %7.3f s", jeandle_timers[compilation_timer].seconds());
  tty->print_cr("       Abstract Interpret:  %7.3f s", jeandle_timers[abstract_interpreter_timer].seconds());
  tty->print_cr("       LLVM Optimize:       %7.3f s", jeandle_timers[llvm_optimizer_timer].seconds());
  tty->print_cr("       LLVM CodeGen:        %7.3f s", jeandle_timers[llvm_codegen_timer].seconds());
  tty->print_cr("       Finalize:            %7.3f s", jeandle_timers[finalize_timer].seconds());
  tty->print_cr("    (Jeandle compilations: %d)", jeandle_compilation_count);
}
