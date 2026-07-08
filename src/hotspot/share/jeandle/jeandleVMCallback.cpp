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
#include "llvm/IR/Jeandle/VMCallback.h"
#include "llvm/IR/Jeandle/VMCallbackLog.h"

#include "jeandle/jeandleAbstractInterpreter.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandleVMCallback.hpp"
#include "jeandle/jeandleCompilation.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmClasses.hpp"
#include "ci/ciField.hpp"
#include "ci/ciInstance.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciObject.hpp"
#include "oops/fieldInfo.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "oops/instanceMirrorKlass.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/klass.inline.hpp"
#include "runtime/handles.inline.hpp"
#include "runtime/javaThread.hpp"
#include "utilities/globalDefinitions.hpp"

namespace {

bool jeandle_is_subtype(uintptr_t sub_klass, uintptr_t super_klass) {
  return ((Klass*)sub_klass)->is_subtype_of((Klass*)super_klass);
}

uintptr_t jeandle_get_common_super_klass(uintptr_t k1, uintptr_t k2) {
  Klass* lca = ((Klass*)k1)->LCA((Klass*)k2);
  return (uintptr_t)lca;
}

uintptr_t jeandle_get_field_type(uintptr_t klass_ptr, int offset) {
  Klass* klass = (Klass*)klass_ptr;
  if (!klass->is_instance_klass()) return 0;

  InstanceKlass* ik = InstanceKlass::cast(klass);
  for (JavaFieldStream fs(ik); !fs.done(); fs.next()) {
    if (fs.offset() == offset) {
      Symbol* sig = fs.signature();
      if (sig->char_at(0) == JVM_SIGNATURE_CLASS ||
          sig->char_at(0) == JVM_SIGNATURE_ARRAY) {
        Thread* current = Thread::current();
        HandleMark hm(current);
        Klass* field_klass = SystemDictionary::find_instance_or_array_klass(
            current, sig, Handle(current, ik->class_loader()),
            Handle(current, ik->protection_domain()));
        return (uintptr_t)field_klass; // 0 if not loaded
      }
      return 0; // primitive field
    }
  }
  return 0; // field not found at offset
}

bool jeandle_is_interface(uintptr_t klass_ptr) {
  return ((Klass*)klass_ptr)->is_interface();
}

bool jeandle_is_object_klass(uintptr_t klass_ptr) {
  return (Klass*)klass_ptr == vmClasses::Object_klass();
}

bool jeandle_is_effectively_final(uintptr_t klass_ptr) {
  Klass* klass = (Klass*)klass_ptr;
  if (klass->is_instance_klass())
    return InstanceKlass::cast(klass)->is_final();
  if (klass->is_typeArray_klass())
    return true;
  if (klass->is_objArray_klass())
    return jeandle_is_effectively_final(
        (uintptr_t)ObjArrayKlass::cast(klass)->bottom_klass());
  return false;
}

ciObject* jeandle_oop_by_id(int oop_id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  if (compilation == nullptr) {
    return nullptr;
  }

  return compilation->compiled_code()->oop_at(oop_id);
}

bool jeandle_constant_field(int oop_id, int offset, ciField** field, ciConstant* con) {
  ciObject* base_oop = jeandle_oop_by_id(oop_id);
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

int64_t jeandle_get_constant_field_value(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  // Callers must confirm foldability via jeandle_get_constant_field_info first
  // (it returns the HotSpot BasicType >= 0, or -1 when not foldable). This
  // function is only reached on that path; constancy is decided solely by
  // _info, never by the value returned here.
  bool foldable = jeandle_constant_field(oop_id, offset, &field, &con);
  assert(foldable, "jeandle_get_constant_field_value called on a non-foldable "
                   "field; caller must verify via jeandle_get_constant_field_info");

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

int jeandle_get_constant_field_info(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con))
    return -1;
  return field->layout_type();
}

const char* jeandle_get_oop_handle_name(int oop_id) {
  JeandleCompilation* compilation = JeandleCompilation::current();
  assert(compilation != nullptr, "no active compilation");
  JeandleCompiledCode* cc = compilation->compiled_code();
  // _oop_handles entries are individually heap-allocated and never relocated on
  // insert, so getKeyData() stays valid for the whole compilation — unlike the
  // std::strings in _oop_handle_info's SmallVector, whose buffers can move when
  // the vector reallocates.
  auto it = cc->oop_handles().find(cc->oop_handle_name(oop_id));
  assert(it != cc->oop_handles().end(), "oop handle name missing from map");
  return it->getKeyData();
}

uintptr_t jeandle_get_oop_klass(int oop_id) {
  ciObject* oop = jeandle_oop_by_id(oop_id);
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

ciMethod* jeandle_callback_method(uintptr_t method) {
  assert(method != 0, "callback method pointer must not be null");
  return (ciMethod*)method;
}

bool jeandle_is_ok_to_inline(int scope_id, int bci, uintptr_t callee_method) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  JeandleInlineTree* caller_tree = comp->inline_tree_for_scope(scope_id);
  assert(caller_tree != nullptr, "caller inline tree must exist");
  ciMethod* callee = jeandle_callback_method(callee_method);
  if (caller_tree->callee_at(bci, callee) != nullptr) {
    return true;
  }
  return caller_tree->ok_to_inline(comp, callee, bci);
}

bool jeandle_record_inline_success(int scope_id, int bci, uintptr_t callee_method) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  ciMethod* callee = jeandle_callback_method(callee_method);

  // LLVM calls this callback only after the inline transformation succeeds.
  // Keep Jeandle's inline tree in sync at that point so later policy checks
  // account for the bytecodes that were actually inlined.
  JeandleInlineTree* callee_tree = comp->build_inline_tree_for_callee(scope_id, bci, callee);
  // TODO: Allocate the callee inline tree before asking LLVM to inline. Today
  // this callback is reached after LLVM has already changed IR, so failing to
  // build the tree here would leave frontend inline metadata out of sync with
  // backend IR.
  guarantee(callee_tree != nullptr, "callee inline tree must be recorded after a successful inline");
  return true;
}

bool jeandle_get_inline_callee_ir(uintptr_t callee_method) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  llvm::Module* M = comp->llvm_module();
  ciMethod* callee = jeandle_callback_method(callee_method);
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

int64_t jeandle_get_new_statepoint_id(int64_t old_statepoint_id) {
  JeandleCompilation* comp = JeandleCompilation::current();
  assert(comp != nullptr, "Must be called in compile thread");
  assert(old_statepoint_id >= 0, "old statepoint id must be non-negative");

  return comp->compiled_code()->duplicate_non_routine_call_site(
      static_cast<uint64_t>(old_statepoint_id));
}

bool jeandle_record_inlining_complete() {
  if (JeandleRecordVMCallbacks) {
    JeandleCompilation* comp = JeandleCompilation::current();
    assert(comp != nullptr, "Must be called in compile thread");
    comp->dump_inline_callee_replay_module();
  }
  return true;
}

} // anonymous namespace

void register_jeandle_vm_callbacks() {
  llvm::jeandle::VMCallbacks callbacks;
  callbacks.IsSubtype = &jeandle_is_subtype;
  callbacks.GetCommonSuperKlass = &jeandle_get_common_super_klass;
  callbacks.GetFieldType = &jeandle_get_field_type;
  callbacks.IsInterface = &jeandle_is_interface;
  callbacks.IsObjectKlass = &jeandle_is_object_klass;
  callbacks.IsEffectivelyFinal = &jeandle_is_effectively_final;
  callbacks.GetConstantFieldValue = &jeandle_get_constant_field_value;
  callbacks.GetConstantFieldInfo = &jeandle_get_constant_field_info;
  callbacks.GetOopHandleName = &jeandle_get_oop_handle_name;
  callbacks.GetOopKlass = &jeandle_get_oop_klass;
  callbacks.GetInlineCalleeIR = &jeandle_get_inline_callee_ir;
  callbacks.GetNewStatepointID = &jeandle_get_new_statepoint_id;
  callbacks.IsOkToInline = &jeandle_is_ok_to_inline;
  callbacks.RecordInlineSuccess = &jeandle_record_inline_success;
  callbacks.RecordInliningComplete = &jeandle_record_inlining_complete;
  llvm::jeandle::registerVMCallbacks(callbacks);

  if (JeandleRecordVMCallbacks) {
    llvm::jeandle::enableVMCallbackRecording();
  }
}
