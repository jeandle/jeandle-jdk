/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy of the LICENSE file included with
 * this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/Jeandle/VMCallback.h"
#include "llvm/IR/Jeandle/VMCallbackLog.h"
#include "llvm/IR/Jeandle/InvokeType.h"
#include "llvm/Transforms/Jeandle/CHADevirtualization.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandleVMCallback.hpp"
#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleCompiledCode.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciClassList.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciKlass.hpp"
#include "ci/ciSymbols.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "ci/ciField.hpp"
#include "ci/ciInstance.hpp"
#include "ci/ciMetadata.hpp"
#include "ci/ciObject.hpp"
#include "ci/ciType.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "logging/log.hpp"
#include "oops/fieldInfo.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/instanceMirrorKlass.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/sharedRuntime.hpp"
#include "utilities/globalDefinitions.hpp"

#include <cstdint>
#include <utility>

namespace {

// File-local helpers shared by the JeandleVMCallback callbacks below.

ciObject* oop_by_id(int oop_id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  if (compilation == nullptr) {
    return nullptr;
  }
  return compilation->compiled_code()->oop_at(oop_id);
}

bool constant_field(int oop_id, int offset, ciField** field, ciConstant* con) {
  ciObject* base_oop = oop_by_id(oop_id);
  if (base_oop == nullptr || base_oop->is_null_object()) {
    return false;
  }

  if (base_oop->is_array()) {
    // TODO: Support Stable array element folding in a follow-up pass.
    return false;
  }

  if (!base_oop->is_instance()) {
    return false;
  }

  ciInstance* instance = base_oop->as_instance();
  ciField* found = nullptr;
  ciConstant value;
  ciType* mirror_type = instance->java_mirror_type();
  if (mirror_type != nullptr && mirror_type->is_klass() &&
      mirror_type->as_klass()->is_instance_klass() &&
      offset >= InstanceMirrorKlass::offset_of_static_fields()) {
    found = mirror_type->as_klass()->as_instance_klass()->get_field_by_offset(offset, true);
    if (found == nullptr || !found->is_constant()) {
      return false;
    }
    value = found->constant_value();
  } else {
    found = instance->klass()->as_instance_klass()->get_field_by_offset(offset, false);
    if (found == nullptr || !found->is_constant()) {
      return false;
    }
    value = found->constant_value_of(instance);
  }

  if (!value.is_valid()) {
    return false;
  }

  if (is_reference_type(found->layout_type()) && !found->type()->is_loaded()) {
    return false;
  }

  *field = found;
  *con = value;
  return true;
}

ciMethod* callback_method(uintptr_t method) {
  assert(method != 0, "callback method pointer must not be null");
  return (ciMethod*)method;
}

JeandleInlineReason inline_reason_from_llvm(int reason) {
  switch (static_cast<llvm::jeandle::JeandleInlineReason>(reason)) {
    case llvm::jeandle::JeandleInlineReason::RootCalleeUnsupported:
      return JeandleInlineReason::LLVMRootCalleeUnsupported;
    case llvm::jeandle::JeandleInlineReason::GetInlineCalleeIRFailed:
      return JeandleInlineReason::LLVMGetInlineCalleeIRFailed;
    case llvm::jeandle::JeandleInlineReason::MissingInlineCalleeDefinition:
      return JeandleInlineReason::LLVMMissingInlineCalleeDefinition;
    case llvm::jeandle::JeandleInlineReason::NotInlineViable:
      return JeandleInlineReason::LLVMNotInlineViable;
    case llvm::jeandle::JeandleInlineReason::LLVMInlineFailed:
      return JeandleInlineReason::LLVMInlineFailed;
    case llvm::jeandle::JeandleInlineReason::InlineSuccess:
      ShouldNotReachHere();
  }
  ShouldNotReachHere();
  return JeandleInlineReason::LLVMInlineFailed;
}

} // anonymous namespace

// ---------------------------------------------------------------------------
// Type hierarchy / declared-field queries
// ---------------------------------------------------------------------------

bool JeandleVMCallback::is_subtype(uintptr_t sub_klass, uintptr_t super_klass) {
  return ((Klass*)sub_klass)->is_subtype_of((Klass*)super_klass);
}

uintptr_t JeandleVMCallback::get_common_super_klass(uintptr_t k1, uintptr_t k2) {
  Klass* lca = ((Klass*)k1)->LCA((Klass*)k2);
  return (uintptr_t)lca;
}

uintptr_t JeandleVMCallback::get_field_type(uintptr_t klass_ptr, int offset) {
  // RecoverTypeInfo invokes this from the LLVM optimizer with the compile thread
  // in _thread_in_native. VM_ENTRY_MARK is the canonical native->VM transition
  // (the same one CI uses for its callbacks); in _thread_in_vm the field's
  // declared type is resolved through the CI layer (ciInstanceKlass::
  // get_field_by_offset -> ciField::type), exactly like ciField::compute_type_impl
  // and the frontend — not via raw InstanceKlass/SystemDictionary/Handle, which
  // is what made the old implementation unsound. JeandleVMCallback is a friend
  // of ciEnv so ciEnv::get_metadata() is reachable.
  VM_ENTRY_MARK;
  Klass* holder = (Klass*)klass_ptr;
  if (holder == nullptr || !holder->is_instance_klass()) {
    return 0; // arrays/primitives have no Java instance fields
  }
  ciMetadata* meta = ciEnv::current()->get_metadata((Metadata*)holder);
  if (meta == nullptr || !meta->is_instance_klass()) {
    return 0;
  }
  // get_field_by_offset searches _nonstatic_fields (own + inherited), so an
  // inherited field is still found by offset.
  ciField* field = meta->as_instance_klass()->get_field_by_offset(offset, /*is_static=*/false);
  if (field == nullptr) {
    return 0;
  }
  ciType* type = field->type(); // lazily resolved by the CI
  if (!type->is_klass()) {
    return 0; // primitive field
  }
  ciKlass* field_klass = type->as_klass();
  if (!field_klass->is_loaded()) {
    return 0;
  }
  return (uintptr_t)(Klass*)(field_klass->constant_encoding());
}

bool JeandleVMCallback::is_interface(uintptr_t klass_ptr) {
  return ((Klass*)klass_ptr)->is_interface();
}

bool JeandleVMCallback::is_object_klass(uintptr_t klass_ptr) {
  return (Klass*)klass_ptr == vmClasses::Object_klass();
}

bool JeandleVMCallback::is_effectively_final(uintptr_t klass_ptr) {
  Klass* klass = (Klass*)klass_ptr;
  if (klass->is_instance_klass())
    return InstanceKlass::cast(klass)->is_final();
  if (klass->is_typeArray_klass())
    return true;
  if (klass->is_objArray_klass())
    return is_effectively_final(
        (uintptr_t)ObjArrayKlass::cast(klass)->bottom_klass());
  return false;
}

bool JeandleVMCallback::is_unverified_interface(uintptr_t klass_ptr) {
  // Reuses the shared helper that recurses through objArray bottom klasses,
  // matching the frontend's rule for which declared field types are unsafe to
  // attach (interface instance klasses and arrays whose element is such).
  // Qualified (:: ) to call the global helper, not this method itself.
  return ::is_unverified_interface((Klass*)klass_ptr);
}

// ---------------------------------------------------------------------------
// Constant field folding
// ---------------------------------------------------------------------------

int64_t JeandleVMCallback::get_constant_field_value(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  // Callers must confirm foldability via get_constant_field_info first
  // (it returns the HotSpot BasicType >= 0, or -1 when not foldable). This
  // function is only reached on that path; constancy is decided solely by
  // get_constant_field_info, never by the value returned here.
  bool foldable = constant_field(oop_id, offset, &field, &con);
  assert(foldable, "get_constant_field_value called on a non-foldable "
                   "field; caller must verify via get_constant_field_info");

  if (field->is_call_site_target()) {
    ciObject* base_oop = oop_by_id(oop_id);
    assert(base_oop != nullptr && base_oop->is_call_site(), "bad CallSite holder");
    ciCallSite* call_site = base_oop->as_call_site();
    if (!call_site->is_fully_initialized_constant_call_site()) {
      ciMethodHandle* target = con.as_object()->as_method_handle();
      ciEnv::current()->dependencies()->assert_call_site_target_value(call_site, target);
    }
  }

  switch (field->layout_type()) {
  case T_BOOLEAN:
  case T_BYTE:
  case T_CHAR:
  case T_SHORT:
  case T_INT:
    return static_cast<int64_t>(con.as_int());
  case T_LONG:
    return con.as_long();
  case T_FLOAT:
    return static_cast<int64_t>(static_cast<uint32_t>(jint_cast(con.as_float())));
  case T_DOUBLE:
    return jlong_cast(con.as_double());
  case T_OBJECT:
  case T_ARRAY: {
    ciObject* object = con.as_object();
    if (object->is_null_object()) {
      return static_cast<int64_t>(-1);
    }
    JeandleCompiledCode* compiled_code = JeandleCompilation::current()->compiled_code();
    int result_id = compiled_code->find_or_insert_oop(object);
    return static_cast<int64_t>(result_id);
  }
  default:
    ShouldNotReachHere();
  }
}

int JeandleVMCallback::get_constant_field_info(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!constant_field(oop_id, offset, &field, &con))
    return -1;
  return field->layout_type();
}

// ---------------------------------------------------------------------------
// Oop handles
// ---------------------------------------------------------------------------

std::string JeandleVMCallback::get_oop_handle_name(int oop_id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  assert(compilation != nullptr, "no active compilation");
  JeandleCompiledCode* cc = compilation->compiled_code();
  // _oop_handles entries are individually heap-allocated and never relocated on
  // insert, so getKeyData() stays valid for the whole compilation — unlike the
  // std::strings in _oop_handle_info's SmallVector, whose buffers can move when
  // the vector reallocates.
  auto it = cc->oop_handles().find(cc->oop_handle_name(oop_id));
  assert(it != cc->oop_handles().end(), "oop handle name missing from map");
  return it->getKey().str();
}

uintptr_t JeandleVMCallback::get_oop_klass(int oop_id) {
  ciObject* oop = oop_by_id(oop_id);
  if (oop == nullptr || oop->is_null_object()) {
    return 0;
  }
  ciKlass* klass = oop->klass();
  if (klass == nullptr || !klass->is_loaded()) {
    return 0;
  }
  // The constant oop is a single, compile-time-known object instance, so its
  // klass is the value's exact dynamic type. Mirrors the encoding used by the
  // frontend when attaching !java-klass metadata (jeandleAbstractInterpreter.cpp).
  return (uintptr_t)(Klass*)(klass->constant_encoding());
}

// ---------------------------------------------------------------------------
// Inlining
// ---------------------------------------------------------------------------

bool JeandleVMCallback::is_ok_to_inline(int scope_id, int bci, uintptr_t callee_method) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  JeandleInlineTree* caller_tree = comp->inline_tree_for_scope(scope_id);
  assert(caller_tree != nullptr, "caller inline tree must exist");
  ciMethod* callee = callback_method(callee_method);
  if (caller_tree->callee_at(bci, callee) != nullptr) {
    return true;
  }
  JeandleInlineReason reason = JeandleInlineReason::InlineHot;
  if (!caller_tree->ok_to_inline(comp, callee, bci, reason)) {
    comp->record_inline_failure(scope_id, bci, callee, reason);
    return false;
  }

  JeandleInlineTree* callee_tree = comp->prepare_inline_tree_for_callee(scope_id, bci, callee);
  assert(callee_tree != nullptr, "callee inline tree must be prepared before LLVM inline");
  callee_tree->set_reason(reason);
  return true;
}

bool JeandleVMCallback::record_inline_result(int scope_id, int bci, uintptr_t callee_method, int result) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  ciMethod* callee = callback_method(callee_method);

  llvm::jeandle::JeandleInlineReason llvm_reason =
      static_cast<llvm::jeandle::JeandleInlineReason>(result);
  if (llvm_reason == llvm::jeandle::JeandleInlineReason::InlineSuccess) {
    // IsOkToInline has already prepared this tree, so a successful result only
    // commits metadata in the same successful-inline order as LLVM's InlineScopes.
    comp->commit_inline_tree_for_callee(scope_id, bci, callee);
  } else {
    comp->record_inline_failure(scope_id,
                                bci,
                                callee,
                                inline_reason_from_llvm(result));
  }
  return true;
}

bool JeandleVMCallback::get_inline_callee_ir(uintptr_t callee_method) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  llvm::Module* M = comp->llvm_module();
  ciMethod* callee = callback_method(callee_method);
  std::string callee_name = JeandleFuncSig::method_name_with_signature(callee);
  llvm::Function* callee_func = M->getFunction(callee_name);
  if (callee_func != nullptr && !callee_func->isDeclaration()) {
    return true;
  }

  JeandleParseContext parse_context = JeandleParseContext::inlinee(callee);
  JeandleAbstractInterpreter interpret(parse_context, -1, *M, *comp->compiled_code(), comp->trap_hist());
  llvm::Function* resolved_func = M->getFunction(callee_name);
  assert(resolved_func != nullptr, "callee function not found");
  if (comp->error_occurred()) {
    if (!resolved_func->isDeclaration()) {
      resolved_func->deleteBody();
    }
    return false;
  }

  resolved_func->setLinkage(llvm::GlobalValue::AvailableExternallyLinkage);
  return true;
}

int64_t JeandleVMCallback::get_new_statepoint_id(int64_t old_statepoint_id) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  assert(old_statepoint_id >= 0, "old statepoint id must be non-negative");

  return comp->compiled_code()->duplicate_non_routine_call_site(
      static_cast<uint64_t>(old_statepoint_id));
}

bool JeandleVMCallback::record_inlining_complete() {
  if (JeandleRecordVMCallbacks) {
    JeandleCompilation* comp = JeandleCompilation::current();
    assert(comp != nullptr, "Must be called in compile thread");
    comp->dump_inline_callee_replay_module();
  }
  return true;
}

// ---------------------------------------------------------------------------
// CHA devirtualization
// ---------------------------------------------------------------------------

namespace {

// File-local CHA helpers. They return llvm::jeandle::CHAOptInfo, an LLVM-side
// type that cannot appear in the pure-HotSpot jeandleVMCallback.hpp, so they
// stay free functions here (internal linkage) and are only called by
// JeandleVMCallback::get_cha_opt_info below.

llvm::jeandle::CHAOptInfo optimize_invokeinterface(ciMethod* caller,
                              ciMethod* callee, ciInstanceKlass* holder) {
  ciInstanceKlass* singleton = holder->unique_implementor();
  if (singleton == nullptr) {
    return {};
  }
  assert(singleton != holder, "not a unique implementor");
  ciMethod* cha_monomorphic_target =
    callee->find_monomorphic_target(caller->holder(), holder, singleton);

  if (cha_monomorphic_target != nullptr &&
      cha_monomorphic_target->holder() != ciEnv::current()->Object_klass()) { // subtype check against Object is useless
    ciKlass* constraint = cha_monomorphic_target->holder();
    constraint = (constraint->is_subclass_of(singleton) ? constraint : singleton);
    ciEnv::current()->dependencies()->assert_unique_implementor(holder, singleton);
    ciEnv::current()->dependencies()->assert_unique_concrete_method(holder, cha_monomorphic_target, holder, callee);
    return {reinterpret_cast<uintptr_t>(constraint->constant_encoding()),
      reinterpret_cast<uintptr_t>(cha_monomorphic_target),
      llvm::jeandle::Deoptimization::Reason_class_check,
      JeandleFuncSig::method_name_with_signature(cha_monomorphic_target)};
  }
  return {};
}

llvm::jeandle::CHAOptInfo optimize_virtual_call(ciMethod* caller,
                            ciMethod* callee, ciInstanceKlass* holder,
                            Klass* receiver_klass, bool is_exact) {
  ciEnv* env = ciEnv::current();

  if (receiver_klass == nullptr)
    return {};

  if (receiver_klass->is_array_klass()) {
    if (callee->holder() == env->Object_klass() &&
        callee->name() != ciSymbols::finalize_method_name()) {
      return {reinterpret_cast<uintptr_t>(callee->holder()->constant_encoding()),
        reinterpret_cast<uintptr_t>(callee),
        llvm::jeandle::Deoptimization::Reason_receiver_constraint,
        JeandleFuncSig::method_name_with_signature(callee)};
    }
    return {};
  }

  if (!receiver_klass->is_instance_klass()) {
    return {};
  }

  // Bridge back into the friend class so the receiver Klass* is resolved through
  // the CI layer (ciEnv::get_instance_klass is private; reachable because
  // JeandleVMCallback is a friend of ciEnv).
  ciInstanceKlass* receiver_inst_klass =
      JeandleVMCallback::get_receiver_instance_klass(receiver_klass);
  ciInstanceKlass* actual_receiver = holder;
  bool actual_receiver_is_exact = false;
  if (receiver_inst_klass->is_loaded() && receiver_inst_klass->is_initialized() &&
      !receiver_inst_klass->is_interface() &&
      (receiver_inst_klass == actual_receiver || receiver_inst_klass->is_subtype_of(actual_receiver))) {
    actual_receiver = receiver_inst_klass;
    actual_receiver_is_exact = is_exact;
  }

  ciMethod* cha_monomorphic_target =
    callee->find_monomorphic_target(caller->holder(), holder, actual_receiver);
  if (cha_monomorphic_target != nullptr) {
    assert(!callee->can_be_statically_bound(), "should have been handled above");
    assert(!cha_monomorphic_target->is_abstract(), "");
    if (!cha_monomorphic_target->can_be_statically_bound(actual_receiver)) {
      env->dependencies()->assert_unique_concrete_method(actual_receiver,
        cha_monomorphic_target,
        holder, callee);
    }
    return {reinterpret_cast<uintptr_t>(cha_monomorphic_target->holder()->constant_encoding()),
      reinterpret_cast<uintptr_t>(cha_monomorphic_target),
      llvm::jeandle::Deoptimization::Reason_receiver_constraint,
      JeandleFuncSig::method_name_with_signature(cha_monomorphic_target)};
  }

  if (actual_receiver_is_exact) {
    ciMethod* exact_method = callee->resolve_invoke(caller->holder(), actual_receiver);
    if (exact_method != nullptr) {
      return {reinterpret_cast<uintptr_t>(exact_method->holder()->constant_encoding()),
        reinterpret_cast<uintptr_t>(exact_method),
        llvm::jeandle::Deoptimization::Reason_receiver_constraint,
        JeandleFuncSig::method_name_with_signature(exact_method)};
    }
  }

  return {};
}

} // anonymous namespace

ciInstanceKlass* JeandleVMCallback::get_receiver_instance_klass(Klass* receiver_klass) {
  if (receiver_klass == nullptr) {
    return nullptr;
  }
  assert(receiver_klass->is_instance_klass(), "must be instance klass");
  VM_ENTRY_MARK;
  return ciEnv::current()->get_instance_klass(receiver_klass);
}

std::string JeandleVMCallback::get_cha_opt_info(uintptr_t caller_ptr, uintptr_t callee_ptr,
                                                uintptr_t holder_ptr, uintptr_t receiver_klass_ptr,
                                                bool is_exact, int bytecode) {
  if (caller_ptr == 0 || callee_ptr == 0 || holder_ptr == 0) {
    return "";
  }

  ciMethod* caller = reinterpret_cast<ciMethod*>(caller_ptr);
  ciMethod* callee = reinterpret_cast<ciMethod*>(callee_ptr);
  ciInstanceKlass* holder = reinterpret_cast<ciInstanceKlass*>(holder_ptr);
  Klass* receiver_klass = reinterpret_cast<Klass*>(receiver_klass_ptr);

  llvm::jeandle::CHAOptInfo opt_info;
  log_debug(jeandle)("jeandle_get_cha_constraint::callee name: %s, callee holder: %s", callee->name()->as_utf8(), holder->name()->as_utf8());
  if (bytecode == llvm::jeandle::InvokeInterface || bytecode == llvm::jeandle::InvokeVirtual) {
    log_debug(jeandle)("jeandle_get_cha_constraint::invokevirtual");
    opt_info = optimize_virtual_call(caller, callee, holder, receiver_klass, is_exact);
  }

  if (!opt_info.Constraint && bytecode == llvm::jeandle::InvokeInterface) {
    log_debug(jeandle)("jeandle_get_cha_constraint::invokeinterface");
    opt_info = optimize_invokeinterface(caller, callee, holder);
  }

  if (opt_info.Constraint) {
    log_debug(jeandle)("jeandle_get_cha_constraint::constraint, " PTR_FORMAT, (long unsigned int)(opt_info.Constraint));
    return opt_info.encode();
  }
  return "";
}

// Change a virtual callsite to opt virtual call site.
bool JeandleVMCallback::update_to_static_opt_virtual_call(int64_t id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  assert(compilation != nullptr, "no active compilation");
  JeandleCompiledCode* cc = compilation->compiled_code();
  if (static_cast<size_t>(id) >= cc->non_routine_call_sites().size()) {
    return false;
  }
  CallSiteInfo* call_site = cc->non_routine_call_sites()[id];
  call_site->set_type(JeandleCompiledCall::STATIC_CALL);
  call_site->set_target(SharedRuntime::get_resolve_opt_virtual_call_stub());
  return true;
}

void JeandleVMCallback::register_callbacks() {
  llvm::jeandle::VMCallbacks callbacks;
  callbacks.IsSubtype = &JeandleVMCallback::is_subtype;
  callbacks.GetCommonSuperKlass = &JeandleVMCallback::get_common_super_klass;
  callbacks.GetFieldType = &JeandleVMCallback::get_field_type;
  callbacks.IsInterface = &JeandleVMCallback::is_interface;
  callbacks.IsObjectKlass = &JeandleVMCallback::is_object_klass;
  callbacks.IsUnverifiedInterface = &JeandleVMCallback::is_unverified_interface;
  callbacks.IsEffectivelyFinal = &JeandleVMCallback::is_effectively_final;
  callbacks.GetConstantFieldValue = &JeandleVMCallback::get_constant_field_value;
  callbacks.GetConstantFieldInfo = &JeandleVMCallback::get_constant_field_info;
  callbacks.GetOopHandleName = &JeandleVMCallback::get_oop_handle_name;
  callbacks.GetOopKlass = &JeandleVMCallback::get_oop_klass;
  callbacks.GetInlineCalleeIR = &JeandleVMCallback::get_inline_callee_ir;
  callbacks.GetNewStatepointID = &JeandleVMCallback::get_new_statepoint_id;
  callbacks.IsOkToInline = &JeandleVMCallback::is_ok_to_inline;
  callbacks.RecordInlineResult = &JeandleVMCallback::record_inline_result;
  callbacks.RecordInliningComplete = &JeandleVMCallback::record_inlining_complete;
  callbacks.GetCHAOptInfo = &JeandleVMCallback::get_cha_opt_info;
  callbacks.UpdateToStaticOptVirtualCall = &JeandleVMCallback::update_to_static_opt_virtual_call;
  llvm::jeandle::registerVMCallbacks(callbacks);

  if (JeandleRecordVMCallbacks) {
    llvm::jeandle::enableVMCallbackRecording();
  }
}
