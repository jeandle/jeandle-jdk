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
#include "llvm/BinaryFormat/Dwarf.h"
#include "llvm/Support/DataExtractor.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleRegister.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "asm/macroAssembler.hpp"
#include "ci/ciEnv.hpp"
#include "code/vmreg.inline.hpp"

namespace {

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
  void* operator new(size_t size) throw() {
    return JeandleCompilation::current()->arena()->Amalloc(size);
  }

  void* operator new(size_t size, Arena* arena) throw() {
    return arena->Amalloc(size);
  }

  void  operator delete(void* p) {} // nothing to do

#ifdef ASSERT
 protected:
  bool _fixed_up = false;
#endif

 private:
  // Need fixing up with the prolog length.
  int _offset;
};

class JeandleConstReloc : public JeandleReloc {
 public:
  JeandleConstReloc(LinkBlock& block, LinkEdge& edge, address target) :
    JeandleReloc(static_cast<int>(block.getAddress().getValue() + edge.getOffset())),
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
  JeandleCallReloc(int inst_end_offset, ciEnv* env, ciMethod* method, OopMap* oop_map, CallSiteInfo* call) :
    JeandleReloc(inst_end_offset - JeandleCompiledCall::call_site_size(call->type()) /* beginning of a call instruction */),
    _env(env), _method(method), _oop_map(oop_map), _call(call) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    process_oop_map();

    switch (_call->type()) {
      case JeandleCompiledCall::STATIC_CALL:
        assembler.emit_static_call_stub(offset(), _call);
        assembler.patch_static_call_site(offset(), _call);
        break;

      case JeandleCompiledCall::STUB_C_CALL:
        assembler.patch_stub_C_call_site(offset(), _call);
        break;

      case JeandleCompiledCall::DYNAMIC_CALL:
        assembler.patch_ic_call_site(offset(), _call);
        break;

      case JeandleCompiledCall::ROUTINE_CALL:
        assembler.patch_routine_call_site(offset(), _call->target());
        break;

      default:
        ShouldNotReachHere();
        break;
    }
  }

 private:
  ciEnv* _env;
  ciMethod* _method;
  OopMap* _oop_map;
  CallSiteInfo* _call;
  int inst_end_offset() { return offset() + JeandleCompiledCall::call_site_size(_call->type()); }

  void process_oop_map() {
    assert(_oop_map != nullptr, "oopmap must be initialized");
    assert(inst_end_offset() >= 0, "pc offset must be initialized");
    assert(_fixed_up, "offset must be fixed up");

    DebugInformationRecorder* recorder = _env->debug_info();
    recorder->add_safepoint(inst_end_offset(), _oop_map);

    // No deopt support now.
    GrowableArray<ScopeValue*> *locarray = new GrowableArray<ScopeValue*>(0);
    GrowableArray<ScopeValue*> *exparray = new GrowableArray<ScopeValue*>(0);

    // No monitor support now.
    GrowableArray<MonitorValue*> *monarray = new GrowableArray<MonitorValue*>(0);

    DebugToken *locvals = recorder->create_scope_values(locarray);
    DebugToken *expvals = recorder->create_scope_values(exparray);
    DebugToken *monvals = recorder->create_monitor_values(monarray);

#ifdef ASSERT
    if (_call->type() != JeandleCompiledCall::STUB_C_CALL) {
      // If we are not compiling a call vm stub, there must be a valid Java method.
      assert(_method, "invalid Java method");
    }
#endif
    recorder->describe_scope(inst_end_offset(),
                             methodHandle(),
                             _method,
                             _call->bci(),
                             false,
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
};

class JeandleOopReloc : public JeandleReloc {
 public:
  JeandleOopReloc(int offset, jobject oop_handle) :
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

  // Initialize assembler.
  MacroAssembler* masm = new MacroAssembler(&_code_buffer);
  masm->set_oop_recorder(_env->oop_recorder());
  JeandleAssembler assembler(masm);

  if (_method && !_method->is_static()) {
    // For non-static Java method finalization.
    assembler.emit_ic_check();
  }

  _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());

  _prolog_length = masm->offset();

  assembler.emit_insts(((address) _obj->getBufferStart()) + offset, code_size);

  setup_frame_size();

  resolve_reloc_info(assembler);

  if (_method) {
    // For Java method compilation.
    build_exception_handler_table();
    _offsets.set_value(CodeOffsets::Exceptions, assembler.emit_exception_handler());
  }

  // generate shared trampoline stubs
  if (!_code_buffer.finalize_stubs()) {
    JeandleCompilation::report_jeandle_error("code cache full");
    return;
  }

  // No deopt support now.
  _offsets.set_value(CodeOffsets::Deopt, 0);
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

      if (!target.isDefined() && JeandleAssembler::is_routine_call_reloc_kind(edge.getKind())) {
        // Routine call relocations.
        address target_addr = JeandleRuntimeRoutine::get_routine_entry(*target.getName());

        int inst_end_offset = JeandleAssembler::fixup_routine_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

        // TODO: Set the right bci.
        _routine_call_sites[inst_end_offset] = new CallSiteInfo(JeandleCompiledCall::ROUTINE_CALL,
                                                                target_addr,
                                                                -1/* bci */);
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
        relocs.push_back(new JeandleOopReloc(static_cast<int>(block->getAddress().getValue() + edge.getOffset()), _oop_handles[(*(target.getName()))]));
      } else {
        // Unhandled relocations
        ShouldNotReachHere();
      }
    }
  }

  // Step 2: Resolve stackmaps.
  SectionInfo section_info(".llvm_stackmaps");
  if (ReadELF::findSection(*_elf, section_info)) {
    StackMapParser stackmaps(llvm::ArrayRef(((uint8_t*)object_start()) + section_info._offset, section_info._size));
    for (auto record = stackmaps.records_begin(); record != stackmaps.records_end(); ++record) {
      assert(_prolog_length != -1, "prolog length must be initialized");

      int inst_end_offset = static_cast<int>(record->getInstructionOffset());
      assert(inst_end_offset >=0, "invalid pc offset");

      CallSiteInfo* call_info = nullptr;
      if (record->getID() < _non_routine_call_sites.size()) {
        call_info = _non_routine_call_sites[record->getID()];
      } else {
        call_info = _routine_call_sites[inst_end_offset];
      }
      if (call_info) {
        relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, build_oop_map(record), call_info));
      }
    }
  }

  // Step 3: Sort jeandle relocs.
  llvm::sort(relocs.begin(), relocs.end(), [](const JeandleReloc* lhs, const JeandleReloc* rhs) {
      return lhs->offset() < rhs->offset();
  });

  // Step 4: Emit jeandle relocs.
  for (JeandleReloc* reloc : relocs) {
    reloc->fixup_offset(_prolog_length);
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
    Register reg = JeandleRegister::decode_dwarf_register(location.getDwarfRegNum());
    return reg->as_VMReg();
  } else if (kind == StackMapParser::LocationKind::Indirect) {
#ifdef ASSERT
    Register reg = JeandleRegister::decode_dwarf_register(location.getDwarfRegNum());
    assert(JeandleRegister::is_stack_pointer(reg), "register of indirect kind must be stack pointer");
#endif
    int offset = location.getOffset();

    assert(offset % VMRegImpl::stack_slot_size == 0, "misaligned stack offset");
    int oop_slot = offset / VMRegImpl::stack_slot_size;

    return VMRegImpl::stack2reg(oop_slot);
  }

  ShouldNotReachHere();
  return nullptr;
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

void JeandleCompiledCode::build_exception_handler_table() {
  SectionInfo excpet_table_section(".gcc_except_table");
  if (ReadELF::findSection(*_elf, excpet_table_section)) {
    // Start of the exception handler table.
    const char* except_table_pointer = object_start() + excpet_table_section._offset;

    // Utilize DataExtractor to decode exception handler table.
    llvm::DataExtractor data_extractor(llvm::StringRef(except_table_pointer, excpet_table_section._size),
                                       ELFT::Endianness == llvm::endianness::little, /* IsLittleEndian */
                                       BytesPerWord/* AddressSize */);
    llvm::DataExtractor::Cursor data_cursor(0 /* Offset */);

    // Now decode exception handler table.
    // See EHStreamer::emitExceptionTable in Jeandle-LLVM for corresponding encoding.

    uint8_t header_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && header_encoding == llvm::dwarf::DW_EH_PE_omit, "invalid exception handler table header");

    uint8_t type_encoding = data_extractor.getU8(data_cursor);;
    assert(data_cursor && type_encoding == llvm::dwarf::DW_EH_PE_omit, "invalid exception handler table type encoding");

    // We use ELF object files, and only x86 and AArch64 is supported now, so only ULEB128 encoding can be used for call site encoding.
    uint8_t call_site_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && call_site_encoding == llvm::dwarf::DW_EH_PE_uleb128, "invalid exception handler table call site encoding");

    uint64_t call_site_table_length = data_extractor.getULEB128(data_cursor);
    assert(data_cursor, "invalid exception handler table call site table length");

    uint64_t call_site_table_start = data_cursor.tell();

    while (data_cursor.tell() < call_site_table_start + call_site_table_length) {
      uint64_t start = data_extractor.getULEB128(data_cursor) + _prolog_length;
      assert(data_cursor, "invalid exception handler start pc");

      uint64_t length = data_extractor.getULEB128(data_cursor);
      assert(data_cursor, "invalid exception handler length");

      uint64_t langding_pad = data_extractor.getULEB128(data_cursor) + _prolog_length;
      assert(data_cursor, "invalid exception handler landing pad");

      _exception_handler_table.add_handler(start, start + length, langding_pad);

      // Read an action table entry, but we don't use it.
      data_extractor.getULEB128(data_cursor);
      assert(data_cursor, "invalid exception handler action table entry");
    }
  }
}

int JeandleCompiledCode::frame_size_in_slots() {
  return _frame_size * sizeof(intptr_t) / VMRegImpl::stack_slot_size;
}
