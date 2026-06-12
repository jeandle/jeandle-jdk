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

bool jeandle_is_constant_field(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  return jeandle_constant_field(oop_id, offset, &field, &con);
}

int jeandle_get_field_basic_type_by_oop(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return T_ILLEGAL;
  }
  return field->layout_type();
}

int jeandle_get_constant_field_int(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return 0;
  }
  return con.as_int();
}

int64_t jeandle_get_constant_field_long(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return 0;
  }
  return con.as_long();
}

int jeandle_get_constant_field_float_bits(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return 0;
  }
  return jint_cast(con.as_float());
}

int64_t jeandle_get_constant_field_double_bits(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return 0;
  }
  return jlong_cast(con.as_double());
}

int jeandle_get_constant_field_info(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con))
    return -1;
  return field->layout_type();
}

int jeandle_get_constant_field_oop(int oop_id, int offset) {
  ciField* field = nullptr;
  ciConstant con;
  if (!jeandle_constant_field(oop_id, offset, &field, &con)) {
    return -1;
  }

  ciObject* object = con.as_object();
  if (object->is_null_object()) {
    return -1;
  }

  JeandleCompiledCode* compiled_code = JeandleCompilation::current()->compiled_code();
  int result_id = compiled_code->find_or_insert_oop(object);
  compiled_code->ensure_oop_handle_alias(result_id);
  return result_id;
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
  callbacks.IsConstantField = &jeandle_is_constant_field;
  callbacks.GetFieldBasicTypeByOop = &jeandle_get_field_basic_type_by_oop;
  callbacks.GetConstantFieldInt = &jeandle_get_constant_field_int;
  callbacks.GetConstantFieldLong = &jeandle_get_constant_field_long;
  callbacks.GetConstantFieldFloatBits = &jeandle_get_constant_field_float_bits;
  callbacks.GetConstantFieldDoubleBits = &jeandle_get_constant_field_double_bits;
  callbacks.GetConstantFieldOop = &jeandle_get_constant_field_oop;
  callbacks.GetConstantFieldInfo = &jeandle_get_constant_field_info;
  llvm::jeandle::registerVMCallbacks(callbacks);

  if (JeandleRecordVMCallbacks) {
    llvm::jeandle::enableVMCallbackRecording();
  }
}
