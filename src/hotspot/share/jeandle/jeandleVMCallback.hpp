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

#ifndef SHARE_JEANDLE_VM_CALLBACK_HPP
#define SHARE_JEANDLE_VM_CALLBACK_HPP

#include "memory/allocation.hpp"
#include <string>

class ciInstanceKlass;
class Klass;

// JeandleVMCallback collects the VM callbacks that the LLVM-side optimization
// pipeline queries during a Jeandle compilation. All members are static.
//
// The class is a friend of ciEnv so that the callbacks can query runtime information
// from ciEnv.
class JeandleVMCallback : public AllStatic {
 public:
  // Register all callbacks with the LLVM pipeline. Called once from
  // JeandleCompiler::initialize().
  static void register_callbacks();

  // Type hierarchy / declared-field queries.
  static bool      is_subtype(uintptr_t sub_klass, uintptr_t super_klass);
  static uintptr_t get_common_super_klass(uintptr_t k1, uintptr_t k2);
  static uintptr_t get_field_type(uintptr_t klass_ptr, int offset);
  static bool      is_interface(uintptr_t klass_ptr);
  static bool      is_object_klass(uintptr_t klass_ptr);
  static bool      is_unverified_interface(uintptr_t klass_ptr);
  static bool      is_effectively_final(uintptr_t klass_ptr);

  // Constant field folding.
  static int64_t   get_constant_field_value(int oop_id, int offset);
  static int       get_constant_field_info(int oop_id, int offset);

  // Oop handles.
  static std::string get_oop_handle_name(int oop_id);
  static uintptr_t   get_oop_klass(int oop_id);

  // Inlining.
  static bool      get_inline_callee_ir(uintptr_t callee_method);
  static int64_t   get_new_statepoint_id(int64_t old_statepoint_id);
  static bool      is_ok_to_inline(int scope_id, int bci, uintptr_t callee_method);
  static bool      record_inline_result(int scope_id, int bci, uintptr_t callee_method, int result);
  static bool      record_inlining_complete();

  // CHA devirtualization.
  static std::string get_cha_opt_info(uintptr_t caller_ptr, uintptr_t callee_ptr,
                                      uintptr_t holder_ptr, uintptr_t receiver_klass_ptr,
                                      bool is_exact, int bytecode);
  static bool update_to_static_opt_virtual_call(int64_t id);

  // Replaces the now-removed ciEnv::get_instance_klass_for_klass: maps a raw
  // receiver Klass* to a ciInstanceKlass*, preserving the null-check + assert +
  // VM_ENTRY_MARK the old public wrapper carried. Public because it is called
  // from the file-local CHA helpers in jeandleVMCallback.cpp (anonymous-namespace
  // free functions, which have no member access). The private ciEnv::get_instance
  // klass it delegates to is reachable because JeandleVMCallback is a friend of
  // ciEnv.
  static ciInstanceKlass* get_receiver_instance_klass(Klass* receiver_klass);
};

#endif // SHARE_JEANDLE_VM_CALLBACK_HPP
