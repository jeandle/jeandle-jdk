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
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleReloc.hpp"

void* JeandleReloc::operator new(size_t size) throw() {
  return JeandleCompilation::current()->arena()->Amalloc(size);
}

JeandleCallReloc::JeandleCallReloc(int inst_end_offset, ciEnv *env, ciMethod *method, JeandleStackMap *stack_map,
                                   CallSiteInfo *call) :
    JeandleReloc(
        inst_end_offset - JeandleCompiledCall::call_site_size(call->type()) /* beginning of a call instruction */),
    _env(env), _method(method), _stack_map(stack_map), _call(call) {}

void JeandleCallReloc::emit_reloc(JeandleAssembler &assembler) {
#ifdef ASSERT
  // Each call reloc has an oopmap, except for EXTERNAL_CALL.
  if (_call->type() == JeandleCompiledCall::ROUTINE_CALL) {
    bool is_gc_leaf = JeandleRuntimeRoutine::is_gc_leaf(_call->target());
    assert(is_gc_leaf == (_stack_map == nullptr), "unmatched call type and oopmap");
  } else if (_call->type() == JeandleCompiledCall::EXTERNAL_CALL) {
    assert(_stack_map == nullptr, "unmatched call type and oopmap");
  } else {
    assert(_stack_map != nullptr, "unmatched call type and oopmap");
  }
#endif // ASSERT

  if (_stack_map != nullptr) {
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
      assert(_stack_map == nullptr, "no oopmap in external call");
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
  assert(_stack_map != nullptr, "oopmap must be initialized");
  assert(inst_end_offset() >= 0, "pc offset must be initialized");
  assert(_fixed_up, "offset must be fixed up");

  DebugInformationRecorder *recorder = _env->debug_info();
  recorder->add_safepoint(inst_end_offset(), _stack_map->oop_map());

  DebugToken *locvals = recorder->create_scope_values(_stack_map->locals());
  DebugToken *expvals = recorder->create_scope_values(_stack_map->stack());
  DebugToken *monvals = recorder->create_monitor_values(_stack_map->monitors());

  recorder->describe_scope(inst_end_offset(),
                           methodHandle(),
                           _method,
                           _call->bci(),
                           _stack_map->reexecute(),
                           false,
                           false,
                           false,
                           false,
                           false,
                           locvals,
                           expvals,
                           monvals);

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
