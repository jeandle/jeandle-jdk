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

#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/sharedRuntime.hpp"
#include "runtime/stubRoutines.hpp"
#include "runtime/frame.hpp"
#include "runtime/interfaceSupport.inline.hpp"
#include "runtime/safepoint.hpp"
#include "runtime/vframeArray.hpp"

#define GEN_ROUTINE_STUB(name, ruotine_address, return_type, ...)                                    \
  {                                                                                                  \
    std::unique_ptr<llvm::LLVMContext> context_ptr = std::make_unique<llvm::LLVMContext>();          \
    llvm::LLVMContext& context = *context_ptr;                                                       \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false);      \
    ResourceMark rm;                                                                                 \
    JeandleCompilation compilation(target_machine,                                                   \
                                   data_layout,                                                      \
                                   CompilerThread::current()->env(),                                 \
                                   std::move(context_ptr),                                           \
                                   #name,                                                            \
                                   CAST_FROM_FN_PTR(address, ruotine_address),                       \
                                   func_type);                                                       \
    if (compilation.error_occurred()) { return false; }                                              \
    _routine_entry.insert({llvm::StringRef(#name), compilation.compiled_code()->routine_entry()});   \
  }

#define GEN_ASSEMBLY_ROUTINE_BLOB(name) \
  generate_##name();

#define REGISTER_DIRECT_ROUTINE(name, routine_address, reachable, return_type, ...) \
  if (reachable) { _routine_entry.insert({llvm::StringRef(#name), (address)routine_address}); }

llvm::StringMap<address> JeandleRuntimeRoutine::_routine_entry;

bool JeandleRuntimeRoutine::generate(llvm::TargetMachine* target_machine, llvm::DataLayout* data_layout) {
  // For each indirect routine, compile a runtime stub to wrap it.
  ALL_JEANDLE_INDIRECT_ROUTINES(GEN_ROUTINE_STUB);

  // Register direct routines.
  ALL_JEANDLE_DIRECT_ROUTINES(REGISTER_DIRECT_ROUTINE);

  // Generate assembly routines.
  ALL_JEANDLE_ASSEMBLY_ROUTINES(GEN_ASSEMBLY_ROUTINE_BLOB);
  return true;
}

//=============================================================================
//                      Jeandle Runtime C/C++ Routines
//=============================================================================

// This should be called in an assertion at the start of Jeandle runtime routines
// which are entered from compiled code (all of them)
#ifdef ASSERT
static bool check_jeandle_compiled_frame(JavaThread* thread) {
  assert(thread->last_frame().is_runtime_frame(), "cannot call runtime directly from compiled code");
  RegisterMap map(thread,
                  RegisterMap::UpdateMap::skip,
                  RegisterMap::ProcessFrames::include,
                  RegisterMap::WalkContinuation::skip);
  frame caller = thread->last_frame().sender(&map);
  assert(caller.is_jeandle_compiled_frame(), "not being called from Jeandle compiled like code");
  return true;
}
#endif // ASSERT

JRT_ENTRY(void, JeandleRuntimeRoutine::safepoint_handler(JavaThread* current))
  RegisterMap r_map(current,
                    RegisterMap::UpdateMap::skip,
                    RegisterMap::ProcessFrames::include,
                    RegisterMap::WalkContinuation::skip);
  frame trap_frame = current->last_frame().sender(&r_map);
  CodeBlob* trap_cb = trap_frame.cb();
  guarantee(trap_cb != nullptr && trap_cb->is_compiled_by_jeandle(), "safepoint handler must be called from jeandle compiled method");

  ThreadSafepointState* state = current->safepoint_state();
  state->set_at_poll_safepoint(true);

  // TODO: Exception check.
  SafepointMechanism::process_if_requested_with_exit_check(current, false /* check asyncs */);

  state->set_at_poll_safepoint(false);
JRT_END

JRT_LEAF(address, JeandleRuntimeRoutine::get_exception_handler(JavaThread* current))
  return SharedRuntime::raw_exception_handler_for_return_address(current, current->exception_pc());
JRT_END

JRT_ENTRY(address, JeandleRuntimeRoutine::search_landingpad(JavaThread* current))
  assert(current->exception_oop() != nullptr, "exception oop is found");

  address pc = current->exception_pc();

  nmethod* nm = CodeCache::find_nmethod(pc);
  assert(nm != nullptr, "No nmethod found in Jeandle exception handler");
  assert(pc > nm->code_begin(), "sanity check");

  JeandleExceptionHandlerTable exception_table(nm);
  uint64_t handler_pc_offset = exception_table.find_handler(static_cast<uint64_t>(pc - nm->code_begin()));

  return nm->code_begin() + handler_pc_offset;
JRT_END

// Array allocation. It's a copy of OptoRuntime::new_array_C
JRT_BLOCK_ENTRY(void, JeandleRuntimeRoutine::new_array(Klass* array_type, int len, JavaThread* current))
  JRT_BLOCK;
#ifndef PRODUCT
  SharedRuntime::_new_array_ctr++;            // new array requires GC
#endif
  assert(check_jeandle_compiled_frame(current), "incorrect caller");

  // Scavenge and allocate an instance.
  oop result;

  if (array_type->is_typeArray_klass()) {
    // The oopFactory likes to work with the element type.
    // (We could bypass the oopFactory, since it doesn't add much value.)
    BasicType elem_type = TypeArrayKlass::cast(array_type)->element_type();
    result = oopFactory::new_typeArray(elem_type, len, THREAD);
  } else {
    // Although the oopFactory likes to work with the elem_type,
    // the compiler prefers the array_type, since it must already have
    // that latter value in hand for the fast path.
    Handle holder(current, array_type->klass_holder()); // keep the array klass alive
    Klass* elem_type = ObjArrayKlass::cast(array_type)->element_klass();
    result = oopFactory::new_objArray(elem_type, len, THREAD);
  }

  // Pass oops back through thread local storage.  Our apparent type to Java
  // is that we return an oop, but we can block on exit from this routine and
  // a GC can trash the oop in C's return register.  The generated stub will
  // fetch the oop from TLS after any possible GC.
  // TODO : deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(result);
  JRT_BLOCK_END;

  // inform GC that we won't do card marks for initializing writes.
  SharedRuntime::on_slowpath_allocation_exit(current);
JRT_END

// It's a copy of OptoRuntime::new_instance_C
JRT_BLOCK_ENTRY(void, JeandleRuntimeRoutine::new_instance(InstanceKlass* klass, JavaThread* current))
  JRT_BLOCK;
#ifndef PRODUCT
    SharedRuntime::_new_instance_ctr++;         // new instance requires GC
#endif
    assert(check_jeandle_compiled_frame(current), "incorrect caller");

    // These checks are cheap to make and support reflective allocation.
    int lh = klass->layout_helper();
    if (Klass::layout_helper_needs_slow_path(lh) || !InstanceKlass::cast(klass)->is_initialized()) {
      Handle holder(current, klass->klass_holder()); // keep the klass alive
      klass->check_valid_for_instantiation(false, THREAD);
      if (!HAS_PENDING_EXCEPTION) {
        InstanceKlass::cast(klass)->initialize(THREAD);
      }
    }

    if (!HAS_PENDING_EXCEPTION) {
      // Scavenge and allocate an instance.
      Handle holder(current, klass->klass_holder()); // keep the klass alive
      oop result = InstanceKlass::cast(klass)->allocate_instance(THREAD);
      current->set_vm_result(result);

      // Pass oops back through thread local storage.  Our apparent type to Java
      // is that we return an oop, but we can block on exit from this routine and
      // a GC can trash the oop in C's return register.  The generated stub will
      // fetch the oop from TLS after any possible GC.
    }

    // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  JRT_BLOCK_END;

  // inform GC that we won't do card marks for initializing writes.
  SharedRuntime::on_slowpath_allocation_exit(current);
JRT_END

// Note: multianewarray for one dimension is handled by JeandleRuntimeRoutine::new_array.

// It's a copy of OptoRuntime::multianewarray2_C
// multianewarray for 2 dimensions
JRT_ENTRY(void, JeandleRuntimeRoutine::multianewarray2(Klass* elem_type, int len1, int len2, JavaThread* current))
#ifndef PRODUCT
  SharedRuntime::_multi2_ctr++;
#endif
  assert(check_jeandle_compiled_frame(current), "incorrect caller");
  assert(elem_type->is_klass(), "not a class");
  jint dims[2];
  dims[0] = len1;
  dims[1] = len2;
  Handle holder(current, elem_type->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(elem_type)->multi_allocate(2, dims, THREAD);
  // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(obj);
JRT_END

// It's a copy of OptoRuntime::multianewarray3_C
// multianewarray for 3 dimensions
JRT_ENTRY(void, JeandleRuntimeRoutine::multianewarray3(Klass* elem_type, int len1, int len2, int len3, JavaThread* current))
#ifndef PRODUCT
  SharedRuntime::_multi3_ctr++;
#endif
  assert(check_jeandle_compiled_frame(current), "incorrect caller");
  assert(elem_type->is_klass(), "not a class");
  jint dims[3];
  dims[0] = len1;
  dims[1] = len2;
  dims[2] = len3;
  Handle holder(current, elem_type->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(elem_type)->multi_allocate(3, dims, THREAD);
  // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(obj);
JRT_END

// It's a copy of OptoRuntime::multianewarray4_C
// multianewarray for 4 dimensions
JRT_ENTRY(void, JeandleRuntimeRoutine::multianewarray4(Klass* elem_type, int len1, int len2, int len3, int len4, JavaThread* current))
#ifndef PRODUCT
  SharedRuntime::_multi4_ctr++;
#endif
  assert(check_jeandle_compiled_frame(current), "incorrect caller");
  assert(elem_type->is_klass(), "not a class");
  jint dims[4];
  dims[0] = len1;
  dims[1] = len2;
  dims[2] = len3;
  dims[3] = len4;
  Handle holder(current, elem_type->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(elem_type)->multi_allocate(4, dims, THREAD);
  // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(obj);
JRT_END

// It's a copy of OptoRuntime::multianewarray5_C
// multianewarray for 5 dimensions
JRT_ENTRY(void, JeandleRuntimeRoutine::multianewarray5(Klass* elem_type, int len1, int len2, int len3, int len4, int len5, JavaThread* current))
#ifndef PRODUCT
  SharedRuntime::_multi5_ctr++;
#endif
  assert(check_jeandle_compiled_frame(current), "incorrect caller");
  assert(elem_type->is_klass(), "not a class");
  jint dims[5];
  dims[0] = len1;
  dims[1] = len2;
  dims[2] = len3;
  dims[3] = len4;
  dims[4] = len5;
  Handle holder(current, elem_type->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(elem_type)->multi_allocate(5, dims, THREAD);
  // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(obj);
JRT_END

// It's a copy of OptoRuntime::multianewarrayN_C
JRT_ENTRY(void, JeandleRuntimeRoutine::multianewarrayN(Klass* elem_type, arrayOopDesc* dims, JavaThread* current))
  assert(check_jeandle_compiled_frame(current), "incorrect caller");
  assert(elem_type->is_klass(), "not a class");
  assert(oop(dims)->is_typeArray(), "not an array");

  ResourceMark rm;
  jint len = dims->length();
  assert(len > 0, "Dimensions array should contain data");
  jint *c_dims = NEW_RESOURCE_ARRAY(jint, len);
  ArrayAccess<>::arraycopy_to_native<>(dims, typeArrayOopDesc::element_offset<jint>(0),
                                       c_dims, len);

  Handle holder(current, elem_type->klass_holder()); // keep the klass alive
  oop obj = ArrayKlass::cast(elem_type)->multi_allocate(len, c_dims, THREAD);
  // TODO: deoptimize_caller_frame(current, HAS_PENDING_EXCEPTION);
  current->set_vm_result(obj);
JRT_END
