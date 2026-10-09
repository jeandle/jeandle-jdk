/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleReloc.hpp"

void* JeandleReloc::operator new(size_t size) throw() {
  return JeandleCompilation::current()->arena()->Amalloc(size);
}

JeandleCallReloc::JeandleCallReloc(int inst_end_offset, ciEnv *env, ciMethod *method, CallSiteInfo *call) :
    JeandleReloc(
        inst_end_offset - JeandleCompiledCall::call_site_size(call->type()) /* beginning of a call instruction */),
    _env(env), _method(method), _call(call) {}

void JeandleCallReloc::emit_reloc(JeandleAssembler &assembler) {
#ifdef ASSERT
  // Each call reloc has an oopmap, except for EXTERNAL_CALL.
  if (_call->type() == JeandleCompiledCall::ROUTINE_CALL) {
    bool is_gc_leaf = JeandleRuntimeRoutine::is_gc_leaf(_call->target());
    assert(is_gc_leaf == _stack_maps.empty(), "unmatched call type and oopmap");
  } else if (_call->type() == JeandleCompiledCall::EXTERNAL_CALL) {
    assert(_stack_maps.empty(), "unmatched call type and oopmap");
  } else {
    assert(!_stack_maps.empty(), "unmatched call type and oopmap");
  }
#endif // ASSERT

  if (!_stack_maps.empty()) {
    process_stack_map();
  }

  switch (_call->type()) {
    case JeandleCompiledCall::STATIC_CALL:
      assembler.emit_static_call_stub(offset(), _call);
      RETURN_VOID_ON_JEANDLE_ERROR();
      assembler.patch_static_call_site(offset(), _call);
      break;

    case JeandleCompiledCall::STUB_C_CALL:
      assembler.patch_stub_C_call_site(offset(), _call);
      break;

    case JeandleCompiledCall::DYNAMIC_CALL:
      assembler.patch_ic_call_site(offset(), _call);
      RETURN_VOID_ON_JEANDLE_ERROR();
      break;

    case JeandleCompiledCall::ROUTINE_CALL:
      assembler.patch_routine_call_site(offset(), _call->target());
      RETURN_VOID_ON_JEANDLE_ERROR();
      break;

    case JeandleCompiledCall::EXTERNAL_CALL:
      assert(_stack_maps.empty(), "no oopmap in external call");
      assembler.patch_external_call_site(offset(), _call);
      RETURN_VOID_ON_JEANDLE_ERROR();
      break;
    default:
      ShouldNotReachHere();
      break;
  }
}

int JeandleCallReloc::inst_end_offset() {
  return offset() + JeandleCompiledCall::call_site_size(_call->type());
}

void JeandleCallReloc::process_stack_map() {
  assert(!_stack_maps.empty(), "oopmap must be initialized");
  assert(inst_end_offset() >= 0, "pc offset must be initialized");
  assert(_fixed_up, "offset must be fixed up");

  DebugInformationRecorder *recorder = _env->debug_info();
  recorder->add_safepoint(inst_end_offset(), _stack_maps.back()->oop_map());

  // Stub compilation does not need to emit scope values.
  if (_method == nullptr) {
    recorder->end_safepoint(inst_end_offset());
    return;
  }

  if (_call->is_method_handle_invoke()) {
    // nmethod requires a DeoptMH handler exactly when at least one emitted
    // PcDesc is marked as a method-handle invoke. Set the flag here, where the
    // final post-LLVM-inlining debug info is actually recorded.
    JeandleCompilation::current()->compiled_code()->set_has_method_handle_invoke(true);
  }

  // Collect the per-scope PEA virtual-object pools into one safepoint-wide
  // pool. PEA emits all ScalarValueType descriptors into the ROOT scope's VO
  // section (the deopt-point-level object pool), so they all land in the root
  // scope's JeandleStackMap — but merge across all (post-LLVM-inlining)
  // scopes here anyway, keeping this side placement-agnostic. realloc_objects
  // reads a single pool via the PcDesc obj_decode_offset.
  GrowableArray<ScopeValue*>* object_pool = nullptr;
  for (JeandleStackMap* stack_map : _stack_maps) {
    if (stack_map->objects() != nullptr && stack_map->objects()->length() > 0) {
      if (object_pool == nullptr) {
        object_pool = new GrowableArray<ScopeValue*>();
      }
      for (int i = 0; i < stack_map->objects()->length(); i++) {
        object_pool->append(stack_map->objects()->at(i));
      }
    }
  }

  // Dump the object pool BEFORE serializing the scope values. The decode side
  // (ScopeDesc ctor) reads the object pool first (PcDesc obj_decode_offset) to
  // populate _objects, then scope values reference those objects by id
  // (OBJECT_ID_CODE). For that to resolve, each ObjectValue's FULL encoding
  // (OBJECT_CODE) must land in the pool before any scope value marks it visited
  // (a visit turns later writes into an OBJECT_ID_CODE back-ref, leaving the
  // pool with only a ref the pool-first decode cannot resolve). Matches C2
  // (PhaseOutput: dump_object_pool before create_scope_values).
  if (object_pool != nullptr) {
    recorder->dump_object_pool(object_pool);
  }

  for (JeandleStackMap* stack_map : _stack_maps) {
    DebugToken *locvals = recorder->create_scope_values(stack_map->locals());
    DebugToken *expvals = recorder->create_scope_values(stack_map->stack());
    DebugToken *monvals = recorder->create_monitor_values(stack_map->monitors());

    // has_ea_local_in_scope gates the escape-barrier object-deopt path; set it
    // for any scope that carries a scalar-replaced object. arg_escape stays
    // false (ArgEscape objects are out of scope). PEA-eliminated monitor
    // reconstruction IS handled: a MonitorValue with eliminated=true is
    // serialized here and relocked by relock_objects at deopt; that is
    // independent of the arg_escape / escape-barrier path.
    bool has_ea_local = (stack_map->objects() != nullptr && stack_map->objects()->length() > 0);
    recorder->describe_scope(inst_end_offset(),
                             methodHandle(),
                             stack_map->method(),
                             stack_map->bci(),
                             stack_map->reexecute(),
                             false,
                             _call->is_method_handle_invoke(),
                             false,
                             has_ea_local,
                             false,
                             locvals,
                             expvals,
                             monvals);
  }

  recorder->end_safepoint(inst_end_offset());
}

void JeandleSectionWordReloc::emit_reloc(JeandleAssembler &assembler) {
  if (pd_emit_reloc(assembler)) {
    return;
  }

  assembler.emit_section_word_reloc(offset(), _kind, _addend, _target, _reloc_section);
}

void JeandleSectionWordReloc::fixup_offset(int prolog_length) {
  if (pd_fixup_offset(prolog_length)) {
    return;
  }

  if (_reloc_section == CodeBuffer::SECT_INSTS) {
    _offset += prolog_length;
  } else {
    assert(_reloc_section == CodeBuffer::SECT_CONSTS, "unexpected code section");
    _target += prolog_length;
  }
#ifdef ASSERT
  _fixed_up = true;
#endif
}

void JeandleOopReloc::emit_reloc(JeandleAssembler& assembler) {
  if (pd_emit_reloc(assembler)) {
    return;
  }

  assembler.emit_oop_reloc(offset(), _oop_handle, _addend);
}

void JeandleOopAddrReloc::emit_reloc(JeandleAssembler& assembler) {
  if (pd_emit_reloc(assembler)) {
    return;
  }

  assembler.emit_oop_addr_reloc(offset(), _oop_handle);
}
