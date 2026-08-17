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
#include "llvm/IR/Instructions.h"
#include "llvm/IR/Jeandle/Deoptimization.h"
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
#include "ci/ciMemberName.hpp"
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
#include "oops/objArrayKlass.hpp"
#include "oops/typeArrayKlass.hpp"
#include "runtime/globals.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/sharedRuntime.hpp"
#include "utilities/globalDefinitions.hpp"

#include <cstdint>
#include <utility>

namespace {

// File-local helpers shared by the JeandleVMCallback callbacks below.

// Map HotSpot BasicType to the JBasicType enum used on the LLVM side
// (Boolean=0..Object=8, Count=9). Returns Count as the "no element type"
// sentinel for primitives we don't model or unknown inputs.
static int basictype_to_jbasictype(BasicType bt) {
  switch (bt) {
    case T_BOOLEAN: return 0;
    case T_BYTE:    return 1;
    case T_CHAR:    return 2;
    case T_SHORT:   return 3;
    case T_INT:     return 4;
    case T_LONG:    return 5;
    case T_FLOAT:   return 6;
    case T_DOUBLE:  return 7;
    case T_OBJECT:
    case T_ARRAY:   return 8;
    default:        return 9; // JBasicType::Count
  }
}

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

ciObject* jeandle_oop_by_id(int oop_id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  if (compilation == nullptr) {
    return nullptr;
  }

  return compilation->compiled_code()->oop_at(oop_id);
}

ciMethod* jeandle_callback_method(uintptr_t method) {
  assert(method != 0, "callback method pointer must not be null");
  return (ciMethod*)method;
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
// Partial escape analysis (PEA) support
// ---------------------------------------------------------------------------

// Returns 1 iff the target runtime requires strict monitor-stack nesting
// (HotSpot's lightweight locking mode). PEA uses this to decide whether to
// cascade-materialize still-locked virtual objects at a materialization point.
// Mirrors Graal's PlatformConfigurationProvider.requiresStrictLockOrder.
int JeandleVMCallback::requires_strict_lock_order() {
  return LockingMode == LM_LIGHTWEIGHT ? 1 : 0;
}

// Element basic type of an array klass, encoded as the LLVM-side JBasicType
// integer. Returns 9 (Count) for non-array klasses or null/unknown inputs.
int JeandleVMCallback::element_basictype_of_array_klass(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 9;
  Klass* k = (Klass*)klass_ptr;
  if (k->is_typeArray_klass()) {
    return basictype_to_jbasictype(TypeArrayKlass::cast(k)->element_type());
  }
  if (k->is_objArray_klass()) {
    return 8; // JBasicType::Object
  }
  return 9; // JBasicType::Count
}

// Element klass of an object-array klass; 0 for primitive arrays / null / else.
uintptr_t JeandleVMCallback::array_element_klass(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 0;
  Klass* k = (Klass*)klass_ptr;
  if (k->is_objArray_klass()) {
    return (uintptr_t)ObjArrayKlass::cast(k)->element_klass();
  }
  return 0;
}

// True iff the klass carries the jdk.internal.ValueBased annotation
// (access_flags().is_value_based_class()). PEA force-materializes such a
// virtual so the runtime value-based warning fires on a real oop.
bool JeandleVMCallback::is_value_based(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  return ((Klass*)klass_ptr)->access_flags().is_value_based_class();
}

// JBasicType integer of the boxed primitive if klass is one of the eight
// autobox wrappers (Boolean..Double); 9 (Count) otherwise. Boxing klasses are
// preloaded, so the VM-klass pointer compare below is sufficient.
int JeandleVMCallback::is_boxed(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return 9; // JBasicType::Count sentinel
  Klass* k = (Klass*)klass_ptr;
  // Order matches JBasicType (Boolean=0..Double=7).
  if (k == vmClasses::Boolean_klass())   return 0;
  if (k == vmClasses::Byte_klass())      return 1;
  if (k == vmClasses::Character_klass()) return 2;
  if (k == vmClasses::Short_klass())     return 3;
  if (k == vmClasses::Integer_klass())   return 4;
  if (k == vmClasses::Long_klass())      return 5;
  if (k == vmClasses::Float_klass())     return 6;
  if (k == vmClasses::Double_klass())    return 7;
  return 9; // JBasicType::Count
}

// True iff the klass declares/inherits a non-trivial finalize(). PEA refuses
// to virtualize such allocations: HotSpot registers the finalizer at the
// original allocation site, and eliding the alloc would skip registration.
bool JeandleVMCallback::has_finalizer(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  Klass* k = (Klass*)klass_ptr;
  if (!k->is_instance_klass()) return false;
  return InstanceKlass::cast(k)->has_finalizer();
}

// True iff the klass is safe to virtualize. Identity-sensitive subtypes
// (java.lang.ref.Reference and Thread hierarchies) cannot be elided: the
// runtime keys reference-queue enqueue and thread-list registration off
// actual object identity. Mirrors Graal's canVirtualize.
bool JeandleVMCallback::can_virtualize(uintptr_t klass_ptr) {
  if (klass_ptr == 0) return false;
  Klass* k = (Klass*)klass_ptr;
  Klass* ref_klass = vmClasses::Reference_klass();
  if (ref_klass != nullptr && k->is_subtype_of(ref_klass)) return false;
  Klass* thread_klass = vmClasses::Thread_klass();
  if (thread_klass != nullptr && k->is_subtype_of(thread_klass)) return false;
  return true;
}

// ---------------------------------------------------------------------------
// Constant field folding
// ---------------------------------------------------------------------------

llvm::jeandle::ConstantFieldResult
JeandleVMCallback::get_constant_field(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!constant_field(oop_id, offset, &field, &con))
    return {-1, 0};

  int basic_type = field->layout_type();

  if (field->is_call_site_target()) {
    ciObject* base_oop = oop_by_id(oop_id);
    assert(base_oop != nullptr && base_oop->is_call_site(), "bad CallSite holder");
    ciCallSite* call_site = base_oop->as_call_site();
    if (!call_site->is_fully_initialized_constant_call_site()) {
      ciMethodHandle* target = con.as_object()->as_method_handle();
      ciEnv::current()->dependencies()->assert_call_site_target_value(call_site, target);
    }
  }

  switch (basic_type) {
  case T_BOOLEAN:
  case T_BYTE:
  case T_CHAR:
  case T_SHORT:
  case T_INT:
    return {basic_type, static_cast<int64_t>(con.as_int())};
  case T_LONG:
    return {basic_type, con.as_long()};
  case T_FLOAT:
    return {basic_type,
            static_cast<int64_t>(static_cast<uint32_t>(jint_cast(con.as_float())))};
  case T_DOUBLE:
    return {basic_type, jlong_cast(con.as_double())};
  case T_OBJECT:
  case T_ARRAY: {
    ciObject* object = con.as_object();
    if (object->is_null_object()) {
      return {basic_type, -1};
    }
    JeandleCompiledCode* compiled_code = JeandleCompilation::current()->compiled_code();
    int result_id = compiled_code->find_or_insert_oop(object);
    return {basic_type, static_cast<int64_t>(result_id)};
  }
  default:
    ShouldNotReachHere();
  }
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

int JeandleVMCallback::get_java_mirror(uintptr_t klass_ptr) {
  // Given a VM Klass pointer, return the oop id of its java.lang.Class mirror
  // via the CI layer so PEA's foldGetClass can replace jeandle.get_class on a
  // virtual receiver with a GC-safe constant mirror load. Returns -1 (=> PEA
  // bails and materializes, sound) when the klass/mirror is unavailable.
  if (klass_ptr == 0) {
    return -1;
  }
  VM_ENTRY_MARK;
  ciKlass* ci_k = ciEnv::current()->get_klass((Klass*)klass_ptr);
  if (ci_k == nullptr || !ci_k->is_loaded()) {
    return -1;
  }
  ciInstance* mirror = ci_k->java_mirror();
  if (mirror == nullptr) {
    return -1;
  }
  JeandleCompilation* compilation = JeandleCompilation::current();
  assert(compilation != nullptr, "no active compilation");
  return compilation->compiled_code()->find_or_insert_oop(mirror);
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
  JeandleAbstractInterpreter interpret(parse_context, -1, *M,
                                       *comp->compiled_code(), comp->trap_hist());
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

// File-local CHA helpers.

llvm::jeandle::CHAOptInfo optimize_method_handle_intrinsic(
    ciMethod* callee, uintptr_t oop_id, Klass* receiver_klass, bool is_exact) {
  vmIntrinsics::ID iid = callee->intrinsic_id();
  bool input_not_const = true;
  switch (iid) {
  case vmIntrinsics::_invokeBasic:
    {
      ciObject* oop = jeandle_oop_by_id(oop_id);
      if (oop == nullptr) {
        log_debug(jeandle)("optimize_method_handle_intrinsic: _invokeBasic: receiver is always null");
        return {};
      }
      ciMethod* target = oop->as_method_handle()->get_vmtarget();
      if (!ciMethod::is_consistent_info(callee, target)) {
        log_debug(jeandle)(
          "optimize_method_handle_intrinsic: _invokeBasic: signatures mismatch %s %s",
          callee->name()->as_utf8(),
          target->name()->as_utf8());
        return {};
      }
      return {reinterpret_cast<uintptr_t>(target->holder()->constant_encoding()),
              reinterpret_cast<uintptr_t>(target),
              llvm::jeandle::CHAOptInfo::packDeoptreasonInfo(
                target->is_static(), target->is_accessor(),
                llvm::jeandle::Deoptimization::Reason_none),
              JeandleFuncSig::method_name_with_signature(target)};
    }
    break;
  case vmIntrinsics::_linkToVirtual:
  case vmIntrinsics::_linkToStatic:
  case vmIntrinsics::_linkToSpecial:
  case vmIntrinsics::_linkToInterface:
    {
      // Get MemberName argument:
      ciObject* member_name = jeandle_oop_by_id(oop_id);
      if (member_name == nullptr) {
        log_debug(jeandle)("optimize_method_handle_intrinsic: _linkTo*: member_name not constant");
        return {};
      }
      ciMethod* target = member_name->as_member_name()->get_vmtarget();

      if (!ciMethod::is_consistent_info(callee, target)) {
        log_debug(jeandle)("optimize_method_handle_intrinsic: _linkTo*: signatures mismatch %s %s", callee->name()->as_utf8(), target->name()->as_utf8());
        return {};
      }

      // In lambda forms we erase signature types to avoid resolving issues
      // involving class loaders.  When we optimize a method handle invoke
      // to a direct call we must cast the receiver and arguments to its
      // actual types.
      const int is_static = target->is_static() ? 1 : 0;
      return {reinterpret_cast<uintptr_t>(target->holder()) | 1,
          reinterpret_cast<uintptr_t>(target),
          llvm::jeandle::CHAOptInfo::packTargetInfo(
              target->is_static(), target->is_accessor(),
              target->can_be_statically_bound(), target->signature()->count()),
          JeandleFuncSig::method_name_with_signature(target)};
    }
    break;
    case vmIntrinsics::_linkToNative:
      log_debug(jeandle)("optimize_method_handle_intrinsic: _linkToNative: native call");
      break;
    default:
      fatal("unexpected intrinsic %d: %s", vmIntrinsics::as_int(iid), vmIntrinsics::name_at(iid));
      break;
  }
  return {};
}

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
    assert(!cha_monomorphic_target->is_static(), "should not be static");
    return {reinterpret_cast<uintptr_t>(constraint->constant_encoding()),
      reinterpret_cast<uintptr_t>(cha_monomorphic_target),
      llvm::jeandle::CHAOptInfo::packDeoptreasonInfo(
        0, cha_monomorphic_target->is_accessor(),
        llvm::jeandle::Deoptimization::Reason_class_check),
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
      assert(!callee->is_static(), "should not be static");
      return {reinterpret_cast<uintptr_t>(callee->holder()->constant_encoding()),
        reinterpret_cast<uintptr_t>(callee),
        llvm::jeandle::CHAOptInfo::packDeoptreasonInfo(
          0, callee->is_accessor(),
          llvm::jeandle::Deoptimization::Reason_receiver_constraint),
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
    assert(!cha_monomorphic_target->is_static(), "should not be static");
    return {reinterpret_cast<uintptr_t>(cha_monomorphic_target->holder()->constant_encoding()),
      reinterpret_cast<uintptr_t>(cha_monomorphic_target),
      llvm::jeandle::CHAOptInfo::packDeoptreasonInfo(
        0, cha_monomorphic_target->is_accessor(),
        llvm::jeandle::Deoptimization::Reason_receiver_constraint),
      JeandleFuncSig::method_name_with_signature(cha_monomorphic_target)};
  }

  if (actual_receiver_is_exact) {
    ciMethod* exact_method = callee->resolve_invoke(caller->holder(), actual_receiver);
    if (exact_method != nullptr) {
      assert(!exact_method->is_static(), "should not be static");
      return {reinterpret_cast<uintptr_t>(exact_method->holder()->constant_encoding()),
        reinterpret_cast<uintptr_t>(exact_method),
        llvm::jeandle::CHAOptInfo::packDeoptreasonInfo(
          0, exact_method->is_accessor(),
          llvm::jeandle::Deoptimization::Reason_receiver_constraint),
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

llvm::jeandle::CHAOptResult JeandleVMCallback::get_cha_opt_info(uintptr_t caller_ptr, uintptr_t callee_ptr,
                                                                uintptr_t holder_ptr, uintptr_t receiver_klass_ptr,
                                                                bool is_exact, int bytecode, int oop_id) {
  if (caller_ptr == 0 || callee_ptr == 0 || holder_ptr == 0) {
    return {};
  }

  ciMethod* caller = reinterpret_cast<ciMethod*>(caller_ptr);
  ciMethod* callee = reinterpret_cast<ciMethod*>(callee_ptr);
  ciInstanceKlass* holder = reinterpret_cast<ciInstanceKlass*>(holder_ptr);
  Klass* receiver_klass = reinterpret_cast<Klass*>(receiver_klass_ptr);

  llvm::jeandle::CHAOptInfo opt_info;
  log_debug(jeandle)("jeandle_get_cha_constraint::callee name: %s, callee holder: %s", callee->name()->as_utf8(), holder->name()->as_utf8());
  if (callee->is_method_handle_intrinsic()) {
    log_debug(jeandle)("jeandle_get_cha_constraint::method handle intrinsic");
    opt_info = optimize_method_handle_intrinsic(callee, oop_id, receiver_klass, is_exact);
    if (opt_info.Method == 0) {
      return {};
    }
    return {opt_info.ConstraintOrHolder, opt_info.Method,
            opt_info.DeoptReasonOrTargetInfo, std::move(opt_info.MethodName)};
  }

  if (bytecode == llvm::jeandle::InvokeInterface || bytecode == llvm::jeandle::InvokeVirtual) {
    log_debug(jeandle)("jeandle_get_cha_constraint::invokevirtual");
    opt_info = optimize_virtual_call(caller, callee, holder, receiver_klass, is_exact);
  }

  if (!opt_info.constraint() && bytecode == llvm::jeandle::InvokeInterface) {
    log_debug(jeandle)("jeandle_get_cha_constraint::invokeinterface");
    opt_info = optimize_invokeinterface(caller, callee, holder);
  }

  if (opt_info.constraint()) {
    log_debug(jeandle)("jeandle_get_cha_constraint::constraint, " PTR_FORMAT, (long unsigned int)(opt_info.constraint()));
    return {opt_info.ConstraintOrHolder, opt_info.Method,
            opt_info.DeoptReasonOrTargetInfo, std::move(opt_info.MethodName)};
  }
  return {};
}

// Returns true if the call site was updated
bool JeandleVMCallback::update_call_site(int64_t id, int dest, bool need_attched, uintptr_t method) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  assert(compilation != nullptr, "no active compilation");
  JeandleCompiledCode* cc = compilation->compiled_code();
  if (static_cast<size_t>(id) >= cc->non_routine_call_sites().size()) {
    return false;
  }
  CallSiteInfo* call_site = cc->non_routine_call_sites()[id];
  switch(static_cast<llvm::jeandle::CHADestKind>(dest)) {
    case llvm::jeandle::StaticCall:
      call_site->set_type(JeandleCompiledCall::STATIC_CALL);
      call_site->set_target(SharedRuntime::get_resolve_static_call_stub());
      break;
    case llvm::jeandle::VirtualCall:
      call_site->set_type(JeandleCompiledCall::DYNAMIC_CALL);
      call_site->set_target(SharedRuntime::get_resolve_virtual_call_stub());
      break;
    case llvm::jeandle::OptVirtualCall:
      call_site->set_type(JeandleCompiledCall::STATIC_CALL);
      call_site->set_target(SharedRuntime::get_resolve_opt_virtual_call_stub());
      break;
    default:
      return false;
  }
  ciMethod* ci_method = reinterpret_cast<ciMethod*>(method);
  call_site->set_is_method_handle_invoke(ci_method->is_method_handle_intrinsic() ||
                                         ci_method->is_compiled_lambda_form());
  // False means this update does not provide a new attached method.
  // Preserve an existing one from an earlier MethodHandle intrinsic rewrite.
  if (need_attched) {
    Method* method = reinterpret_cast<Method*>(ci_method->constant_encoding());
    call_site->set_attached_method(method);
  }
  return true;
}

uintptr_t JeandleVMCallback::get_signature_accessing_klass(uintptr_t method) {
  ciMethod* m = jeandle_callback_method(method);
  ciKlass* k = m->signature()->accessing_klass();
  if (!k->is_loaded()) {
    return 0;
   }
  return reinterpret_cast<uintptr_t>(k->constant_encoding());
}

int64_t JeandleVMCallback::get_signature_arg_type(uintptr_t method, int index) {
  ciMethod* m = jeandle_callback_method(method);
  if (index == -1) {
    return m->signature()->return_type()->basic_type();
  }
  return m->signature()->type_at(index)->basic_type();
}

uintptr_t JeandleVMCallback::get_signature_arg_type_klass(uintptr_t method, int index) {
  ciMethod* m = jeandle_callback_method(method);
  ciType* t = m->signature()->type_at(index);
  if (!t->is_klass()) {
    return 0;
  }
  ciKlass* k = t->as_klass();
  if (!k->is_loaded()) {
    return 0;
  }
  return reinterpret_cast<uintptr_t>(k->constant_encoding());
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
  callbacks.RequiresStrictLockOrder = &JeandleVMCallback::requires_strict_lock_order;
  callbacks.ElementBasicTypeOfArrayKlass = &JeandleVMCallback::element_basictype_of_array_klass;
  callbacks.ArrayElementKlass = &JeandleVMCallback::array_element_klass;
  callbacks.IsValueBased = &JeandleVMCallback::is_value_based;
  callbacks.IsBoxed = &JeandleVMCallback::is_boxed;
  callbacks.HasFinalizer = &JeandleVMCallback::has_finalizer;
  callbacks.CanVirtualize = &JeandleVMCallback::can_virtualize;
  callbacks.GetConstantField = &JeandleVMCallback::get_constant_field;
  callbacks.GetOopHandleName = &JeandleVMCallback::get_oop_handle_name;
  callbacks.GetOopKlass = &JeandleVMCallback::get_oop_klass;
  callbacks.GetJavaMirror = &JeandleVMCallback::get_java_mirror;
  callbacks.GetInlineCalleeIR = &JeandleVMCallback::get_inline_callee_ir;
  callbacks.GetNewStatepointID = &JeandleVMCallback::get_new_statepoint_id;
  callbacks.IsOkToInline = &JeandleVMCallback::is_ok_to_inline;
  callbacks.RecordInlineResult = &JeandleVMCallback::record_inline_result;
  callbacks.RecordInliningComplete = &JeandleVMCallback::record_inlining_complete;
  callbacks.GetCHAOptInfo = &JeandleVMCallback::get_cha_opt_info;
  callbacks.UpdateCallSite = &JeandleVMCallback::update_call_site;
  callbacks.GetSignatureAccessingKlass = &JeandleVMCallback::get_signature_accessing_klass;
  callbacks.GetSignatureArgType = &JeandleVMCallback::get_signature_arg_type;
  callbacks.GetSignatureArgTypeKlass = &JeandleVMCallback::get_signature_arg_type_klass;
  llvm::jeandle::registerVMCallbacks(callbacks);

  if (JeandleRecordVMCallbacks) {
    llvm::jeandle::enableVMCallbackRecording();
  }
}
