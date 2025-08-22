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
#include "llvm/Support/DataExtractor.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiledCode.hpp"

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

class JeandleCallReloc : public JeandleReloc {
 public:
  JeandleCallReloc(CallSiteInfo* call) : JeandleReloc(call->inst_offset()),
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
  if (!ReadELF::findFunc(*_elf, FuncSigAnalyze::method_name(_method), align, offset, code_size)) {
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

  if (!_method->is_static())
    assembler.emit_ic_check();

  // TODO: NativeJump::patch_verified_entry requires the first instruction of verified entry >= 5 bytes.
  _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());
  _prolog_length = masm->offset();
  assembler.emit_insts(((address) _obj->getBufferStart()) + offset, code_size);

  setup_frame_size();

  resolve_reloc_info(assembler);


  // No deopt support now.
  _offsets.set_value(CodeOffsets::Deopt, 0);

  // No exception support now.
  _offsets.set_value(CodeOffsets::Exceptions, 0);
}

// Get the frame size from .stack_sizes section.
void JeandleCompiledCode::setup_frame_size() {
  SectionInfo section_info(".stack_sizes");
  if (!ReadELF::findSection(*_elf, section_info)) {
    JeandleCompilation::report_jeandle_error(".stack_sizes section not found");
    return;
  }
  llvm::DataExtractor data_extractor(llvm::StringRef(((char*)_obj->getBufferStart()) + section_info._offset, section_info._size),
                                     true/* IsLittleEndian */, oopSize/* AddressSize */);
  uint64_t offset = 0;
  data_extractor.getUnsigned(&offset, oopSize);
  uint64_t stack_size = data_extractor.getULEB128(&offset);
  uint64_t frame_size = stack_size + oopSize/* return address */;
  assert(frame_size % StackAlignmentInBytes == 0, "frame size must be aligned");
  _frame_size = frame_size / oopSize;
}

void JeandleCompiledCode::resolve_reloc_info(JeandleAssembler& assembler) {
  llvm::SmallVector<JeandleReloc*> relocs;

  // Step1: Resolve LinkGraph.
  auto ssp = std::make_shared<llvm::orc::SymbolStringPool>();

  auto graph_on_err = llvm::jitlink::createLinkGraphFromObject(_elf->getMemoryBufferRef(), ssp);
  if (!graph_on_err) {
    JeandleCompilation::report_jeandle_error("failed to create LinkGraph");
    return;
  }

  auto link_graph = std::move(*graph_on_err);

  for (auto *block : link_graph->blocks()) {
    for (auto& edge : block->edges()) {
      auto& target = edge.getTarget();

      if (target.isDefined() && target.getSection().getName().starts_with(".rodata")) {
        // Const relocatinos.
        address target_addr = resolve_const_edge(*block, edge, assembler);
        if (target_addr == nullptr) {
          return;
        }
        relocs.push_back(new JeandleConstReloc(*block, edge, target_addr));
      } else if (!target.isDefined() && edge.getKind() == assembler.get_oop_reloc_kind()) {
        // Oop relocations.
        assert((*(target.getName())).starts_with("oop_handle"), "invalid oop relocation name");
        relocs.push_back(new JeandleOopReloc(block->getAddress().getValue() + edge.getOffset(), _oop_handles[(*(target.getName()))]));
      }
    }
  }

  // Step2: Resolve stackmaps.
  SectionInfo section_info(".llvm_stackmaps");
  if (ReadELF::findSection(*_elf, section_info)) {
    StackMapParser stackmaps(llvm::ArrayRef(((uint8_t*)object_start()) +
                                                     section_info._offset, section_info._size));
    for (auto record = stackmaps.records_begin(); record != stackmaps.records_end(); ++record) {
      if (CallSiteInfo* call = _call_sites.lookup(record->getID())) {
        assert(_prolog_length != -1, "prolog length must be initialized");
        uint32_t inst_offset = record->getInstructionOffset() + _prolog_length;
        call->set_inst_offset(inst_offset);

        relocs.push_back(new JeandleCallReloc(call));

        // No GC support now.
        _env->debug_info()->add_safepoint(inst_offset, build_oop_map(record));

        // No deopt support now.
        GrowableArray<ScopeValue*> *locarray = new GrowableArray<ScopeValue*>(0);
        GrowableArray<ScopeValue*> *exparray = new GrowableArray<ScopeValue*>(0);

        // No monitor support now.
        GrowableArray<MonitorValue*> *monarray = new GrowableArray<MonitorValue*>(0);

        DebugToken *locvals = _env->debug_info()->create_scope_values(locarray);
        DebugToken *expvals = _env->debug_info()->create_scope_values(exparray);
        DebugToken *monvals = _env->debug_info()->create_monitor_values(monarray);

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
      }
    }
  }

  // Step3: Sort jeandle relocs.
  llvm::sort(relocs.begin(), relocs.end(), [](const JeandleReloc* lhs, const JeandleReloc* rhs) {
      return lhs->offset() < rhs->offset();
  });

  // Step4: Emit jeandle relocs.
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

static VMReg resolve_vmreg(const StackMapParser::LocationAccessor& location, StackMapParser::LocationKind kind) {
  if (kind == StackMapParser::LocationKind::Register) {
    Register raw_reg = from_Dwarf2Register(location.getDwarfRegNum());
    return raw_reg->as_VMReg();
  } else if (kind == StackMapParser::LocationKind::Indirect) {
    assert(from_Dwarf2Register(location.getDwarfRegNum()) == rsp, "register of indirect kind must be rsp");
    int offset = location.getOffset();

    assert(offset % VMRegImpl::stack_slot_size == 0, "misaligned stack");
    int oop_slot = offset / VMRegImpl::stack_slot_size;

    return VMRegImpl::stack2reg(oop_slot);
  } else {
    ShouldNotReachHere();
  }
}

OopMap* JeandleCompiledCode::build_oop_map(StackMapParser::record_iterator& record) {
  assert(_frame_size > 0, "frame size must be greater than zero");
  OopMap* oop_map = new OopMap(frame_size_in_slots(), 0);

  for (auto location = record->location_begin(); location != record->location_end(); location++) {
    // Extract location of base pointer.
    auto base_location = *location;
    StackMapParser::LocationKind base_kind = base_location.getKind();

    if (base_kind != StackMapParser::LocationKind::Register &&
        base_kind != StackMapParser::LocationKind::Indirect) {
          continue;
    }

    // Extract location of derived pointer.
    location++;
    auto derived_location = *location;
    StackMapParser::LocationKind derived_kind = derived_location.getKind();

    assert(base_kind == derived_kind, "locations must be in pairs");
    assert(base_kind != StackMapParser::LocationKind::Direct, "invalid location kind");

    VMReg reg_base = resolve_vmreg(base_location, base_kind);
    VMReg reg_derived = resolve_vmreg(derived_location, derived_kind);

    if(reg_base == reg_derived) {
      // No derived pointer.
      oop_map->set_oop(reg_base);
    } else {
      // Derived pointer.
      Unimplemented();
    }
  }
  return oop_map;
}

int JeandleCompiledCode::frame_size_in_slots() {
  return _frame_size * sizeof(intptr_t) / VMRegImpl::stack_slot_size;
}
