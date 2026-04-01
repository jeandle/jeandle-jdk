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

#ifndef SHARE_JEANDLE_RELOC_HPP
#define SHARE_JEANDLE_RELOC_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "utilities/macros.hpp"

class JeandleAssembler;
class JeandleCompilation;
class JeandleStackMap;
class CallSiteInfo;
class JeandleCompiledCall;

using LinkBlock = llvm::jitlink::Block;
using LinkEdge = llvm::jitlink::Edge;
using LinkKind = llvm::jitlink::Edge::Kind;
using LinkSymbol = llvm::jitlink::Symbol;

class JeandleReloc {
 public:
  JeandleReloc(int offset) : _offset(offset) {
    assert(_offset >= 0, "invalid offset");
  }

  int offset() const { return _offset; }

  virtual void emit_reloc(JeandleAssembler& assembler) = 0;

  virtual void fixup_offset(int prolog_length) {
    _offset += prolog_length;
#ifdef ASSERT
    _fixed_up = true;
#endif
  }

  // JeandleReloc should be allocated by arena. Independent from JeandleCompilationResourceObj
  // to avoid ambiguous behavior during template specialization.
  void* operator new(size_t size) throw();

  void* operator new(size_t size, Arena* arena) throw() {
    return arena->Amalloc(size);
  }

  void  operator delete(void* p) {} // nothing to do

 protected:
  // Need fixing up with the prolog length.
  int _offset;
#ifdef ASSERT
  bool _fixed_up = false;
#endif
};

class JeandleCallReloc : public JeandleReloc {
 public:
  JeandleCallReloc(int inst_end_offset, ciEnv* env, ciMethod* method, JeandleStackMap* stack_map, CallSiteInfo* call);

  void emit_reloc(JeandleAssembler& assembler) override;

 private:
  ciEnv* _env;
  ciMethod* _method;
  JeandleStackMap* _stack_map;
  CallSiteInfo* _call;
  int inst_end_offset();

  void process_stack_map();
};

class JeandleSectionWordReloc : public JeandleReloc {
public:
  JeandleSectionWordReloc(int reloc_offset, LinkEdge& edge, address target, int reloc_section) :
      JeandleReloc(reloc_offset),
      _kind(edge.getKind()),
      _addend(edge.getAddend()),
      _target(target),
      _reloc_section(reloc_section) {}

  void emit_reloc(JeandleAssembler& assembler) override;
  bool pd_emit_reloc(JeandleAssembler& assembler);

  void fixup_offset(int prolog_length) override;
  bool pd_fixup_offset(int prolog_length);

private:
  LinkKind _kind;
  int64_t _addend;
  address _target;
  int _reloc_section;

#include CPU_HEADER(jeandleSectionWordReloc)
};

class JeandleOopReloc : public JeandleReloc {
 public:
  JeandleOopReloc(int offset, jobject oop_handle, int64_t addend) :
      JeandleReloc(offset),
      _oop_handle(oop_handle),
      _addend(addend) {}

  void emit_reloc(JeandleAssembler& assembler) override;
  bool pd_emit_reloc(JeandleAssembler& assembler);

 private:
  jobject _oop_handle;
  int64_t _addend;

#include CPU_HEADER(jeandleOopReloc)
};

class JeandleOopAddrReloc : public JeandleReloc {
 public:
  JeandleOopAddrReloc(int offset, jobject oop_handle) :
      JeandleReloc(offset),
      _oop_handle(oop_handle) {}

  void emit_reloc(JeandleAssembler& assembler) override;
  bool pd_emit_reloc(JeandleAssembler& assembler);

  void fixup_offset(int prolog_length) override {
    // This relocation resides in the const section, so the offset does not
    // need to be adjusted by the instruction section's prolog length.
    // The _fixed_up flag is set solely for assertion checks in debug builds.
#ifdef ASSERT
    _fixed_up = true;
#endif
  }

 private:
  jobject _oop_handle;

#include CPU_HEADER(jeandleOopAddrReloc)
};

#endif // SHARE_JEANDLE_RELOC_HPP
