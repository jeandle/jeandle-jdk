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

#include <cassert>
#include "llvm/Object/StackMapParser.h"
#include "llvm/Support/DataExtractor.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "utilities/debug.hpp"
#include "asm/macroAssembler.hpp"
#include "ci/ciEnv.hpp"

namespace {

class JeandleReloc {
 public:
  JeandleReloc(uint32_t offset) : _offset(offset) {}

  uint32_t offset() const { return _offset; }

  virtual void emit_reloc(JeandleAssembler& assembler) = 0;

  // JeandleReloc should be allocated by arena. Independent from JeandleCompilationResourceObj
  // to avoid ambiguous behavior during template specialization.
  void* operator new(size_t size) throw() {
    return JeandleCompilation::current()->arena()->Amalloc(size);
  }

  void* operator new(size_t size, Arena* arena) throw() {
    return arena->Amalloc(size);
  }

  void  operator delete(void* p) {} // nothing to do

 private:
  uint32_t _offset;
};

class JeandleConstReloc : public JeandleReloc {
 public:
  JeandleConstReloc(LinkBlock& block, LinkEdge& edge, address target) :
    JeandleReloc(block.getAddress().getValue() + edge.getOffset()),
    _kind(edge.getKind()),
    _addend(edge.getAddend()),
    _target(target) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    assembler.emit_const_reloc(offset(), _kind, _addend, _target);
  }

 private:
  LinkKind _kind;
  int64_t _addend;
  address _target;
};

class JeandleCallVMReloc : public JeandleReloc {
 public:
  JeandleCallVMReloc(LinkBlock& block, LinkEdge& edge, address target) :
    JeandleReloc(block.getAddress().getValue() + edge.getOffset()),
    _target(target) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    assembler.patch_call_vm(offset(), _target);
  }

 private:
  address _target;
};

class JeandleCallReloc : public JeandleReloc {
 public:
  JeandleCallReloc(CallSiteInfo* call) :
    JeandleReloc(call->inst_offset() - JeandleJavaCall::call_site_size(call->type())),
    _call(call) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    if (_call->type() == JeandleJavaCall::Type::STATIC_CALL) {
      assembler.emit_static_call_stub(_call);
      assembler.patch_static_call_site(_call);
    }

    if (_call->type() == JeandleJavaCall::Type::DYNAMIC_CALL) {
      assembler.patch_ic_call_site(_call);
    }
  }

 private:
  CallSiteInfo* _call;
};

class JeandleOopReloc : public JeandleReloc {
 public:
  JeandleOopReloc(uint32_t offset, jobject oop_handle) :
    JeandleReloc(offset),
    _oop_handle(oop_handle) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    assembler.emit_oop_reloc(offset(), _oop_handle);
  }

 private:
  jobject _oop_handle;
};

} // anonymous namespace

void JeandleCompiledCode::install_obj(std::unique_ptr<ObjectBuffer> obj) {
  _obj = std::move(obj);
  llvm::MemoryBufferRef memory_buffer = _obj->getMemBufferRef();
  auto elf_on_error = llvm::object::ObjectFile::createELFObjectFile(memory_buffer);
  if (!elf_on_error) {
    JeandleCompilation::report_jeandle_error("bad ELF file");
    return;
  }

  _elf = llvm::dyn_cast<ELFObject>(*elf_on_error);
  if (!_elf) {
    JeandleCompilation::report_jeandle_error("bad ELF file");
  }
}

void JeandleCompiledCode::finalize() {
  // Set up code buffer.
  uint64_t align;
  uint64_t offset;
  uint64_t code_size;
  if (!ReadELF::findFunc(*_elf, _func_name, align, offset, code_size)) {
    JeandleCompilation::report_jeandle_error("compiled function is not found in the ELF file");
    return;
  }

  // An estimated initial value.
  uint64_t consts_size = 6144 * wordSize;

  // TODO: How to figure out memory usage.
  _code_buffer.initialize(code_size + consts_size + 2048/* for prolog */,
                          sizeof(relocInfo) + relocInfo::length_limit,
                          128,
                          _env->oop_recorder());
  _code_buffer.initialize_consts_size(consts_size);

  MacroAssembler* masm = new MacroAssembler(&_code_buffer);
  masm->set_oop_recorder(_env->oop_recorder());
  JeandleAssembler assembler(masm);

  if (_method && !_method->is_static()) {
    // For Java method finalization.
    assembler.emit_ic_check();
  }

  // TODO: NativeJump::patch_verified_entry requires the first instruction of verified entry >= 5 bytes.
  _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());
  _prolog_length = masm->offset();
  assembler.emit_insts(((address) _obj->getBufferStart()) + offset, code_size);

  resolve_reloc_info(assembler);

  setup_frame_size();

  // No deopt support now.
  _offsets.set_value(CodeOffsets::Deopt, 0);

  // No exception support now.
  _offsets.set_value(CodeOffsets::Exceptions, 0);
}

void JeandleCompiledCode::resolve_reloc_info(JeandleAssembler& assembler) {
  llvm::SmallVector<JeandleReloc*> relocs;

  // Step 1: Resolve LinkGraph.
  auto ssp = std::make_shared<llvm::orc::SymbolStringPool>();

  auto graph_on_err = llvm::jitlink::createLinkGraphFromObject(_elf->getMemoryBufferRef(), ssp);
  if (!graph_on_err) {
    JeandleCompilation::report_jeandle_error("failed to create LinkGraph");
    return;
  }

  auto link_graph = std::move(*graph_on_err);

  for (auto *block : link_graph->blocks()) {
    // Only resolve relocations for instructions in the compiled method.
    if (block->getSection().getName().compare(".text") != 0) {
      continue;
    }
    for (auto& edge : block->edges()) {
      auto& target = edge.getTarget();

      if (!target.isDefined() && JeandleAssembler::is_call_vm_reloc_kind(edge.getKind())) {
        // Call VM relocations.
        address target_addr = JeandleRuntimeRoutine::get_stub_entry(*target.getName());
        JeandleCallVMReloc* call_vm_reloc = new JeandleCallVMReloc(*block, edge, target_addr);

        uint32_t call_inst_offset = JeandleAssembler::fixup_call_inst_offset(call_vm_reloc->offset());

        // TODO: Set the right bci.
        _safepoints[call_inst_offset] = new CallSiteInfo(0/* statepoint_id */, JeandleJavaCall::STATIC_CALL, target_addr, 0/* bci */);
        relocs.push_back(call_vm_reloc);

      } else if (target.isDefined() && JeandleAssembler::is_const_reloc_kind(edge.getKind())) {
        // Const relocations.
        assert(target.getSection().getName().starts_with(".rodata"), "invalid const section");
        address target_addr = resolve_const_edge(*block, edge, assembler);
        if (target_addr == nullptr) {
          return;
        }
        relocs.push_back(new JeandleConstReloc(*block, edge, target_addr));
      } else if (!target.isDefined() && JeandleAssembler::is_oop_reloc_kind(edge.getKind())) {
        // Oop relocations.
        assert((*(target.getName())).starts_with("oop_handle"), "invalid oop relocation name");
        relocs.push_back(new JeandleOopReloc(block->getAddress().getValue() + edge.getOffset(), _oop_handles[(*(target.getName()))]));
      } else {
        // Unhandled relocations
        ShouldNotReachHere();
      }
    }
  }

  // Step 2: Resolve stackmaps.
  SectionInfo section_info(".llvm_stackmaps");
  if (ReadELF::findSection(*_elf, section_info)) {
    llvm::StackMapParser<ELFT::Endianness> stackmaps(llvm::ArrayRef(((uint8_t*)object_start()) +
                                                     section_info._offset, section_info._size));
    for (auto record = stackmaps.records_begin(); record != stackmaps.records_end(); ++record) {
      if (CallSiteInfo* call = _call_sites.lookup(record->getID())) {
        assert(_prolog_length != -1, "prolog length must be initialized");
        uint32_t inst_offset = record->getInstructionOffset() + _prolog_length;
        call->set_inst_offset(inst_offset);

        relocs.push_back(new JeandleCallReloc(call));

        // No GC support now.
        _env->debug_info()->add_safepoint(inst_offset, new OopMap(stackmaps.getFunction(0).getStackSize(), 0));

        // No deopt support now.
        GrowableArray<ScopeValue*> *locarray = new GrowableArray<ScopeValue*>(0);
        GrowableArray<ScopeValue*> *exparray = new GrowableArray<ScopeValue*>(0);

        // No monitor support now.
        GrowableArray<MonitorValue*> *monarray = new GrowableArray<MonitorValue*>(0);

        DebugToken *locvals = _env->debug_info()->create_scope_values(locarray);
        DebugToken *expvals = _env->debug_info()->create_scope_values(exparray);
        DebugToken *monvals = _env->debug_info()->create_monitor_values(monarray);

        assert(_method, "invalid Java method");
        _env->debug_info()->describe_scope(inst_offset,
                                          methodHandle(),
                                          _method,
                                          call->bci(),
                                          false,
                                          false,
                                          false,
                                          false,
                                          false,
                                          false,
                                          locvals,
                                          expvals,
                                          monvals);

        _env->debug_info()->end_safepoint(inst_offset);

      } else if (CallSiteInfo* safepoint = _safepoints[record->getInstructionOffset()]) {
        // TODO: Add debug information for safepoints.
      }
    }
  }

  // Step 3: Sort jeandle relocs.
  llvm::sort(relocs.begin(), relocs.end(), [](const JeandleReloc* lhs, const JeandleReloc* rhs) {
      return lhs->offset() < rhs->offset();
  });

  // Step 4: Emit jeandle relocs.
  for (JeandleReloc* reloc : relocs) {
    reloc->emit_reloc(assembler);
  }
}

address JeandleCompiledCode::lookup_const_section(llvm::StringRef name, JeandleAssembler& assembler) {
  auto it = _const_sections.find(name);
  if (it == _const_sections.end()) {
    // Copy to CodeBuffer if const section is not found.
    SectionInfo section_info(name);
    if (!ReadELF::findSection(*_elf, section_info)) {
      JeandleCompilation::report_jeandle_error("const section not found, bad ELF file");
      return nullptr;
    }

    address target_base = _code_buffer.consts()->end();
    _const_sections.insert({name, target_base});
    assembler.emit_consts(((address) _obj->getBufferStart()) + section_info._offset, section_info._size);
    return target_base;
  }

  return it->getValue();
}

address JeandleCompiledCode::resolve_const_edge(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assembler) {
  auto& target = edge.getTarget();
  auto& target_section = target.getSection();
  auto target_name = target_section.getName();

  address target_base = lookup_const_section(target_name, assembler);
  if (target_base == nullptr) {
    return nullptr;
  }

  llvm::jitlink::SectionRange range(target_section);
  uint64_t offset_in_section = target.getAddress() - range.getFirstBlock()->getAddress();

  return target_base + offset_in_section;
}
