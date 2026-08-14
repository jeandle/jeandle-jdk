/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
#include "llvm/ADT/DenseSet.h"
#include "llvm/BinaryFormat/Dwarf.h"
#include "llvm/Object/FaultMapParser.h"
#include "llvm/Support/DataExtractor.h"

#include <utility>

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleRegister.hpp"
#include "jeandle/jeandleReloc.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "asm/macroAssembler.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciInstanceKlass.hpp"
#include "ci/ciUtilities.inline.hpp"
#include "code/vmreg.inline.hpp"
#include "gc/shared/barrierSet.hpp"
#include "gc/shared/barrierSetAssembler.hpp"
#include "interpreter/interpreter.hpp"
#include "logging/log.hpp"
#include "oops/klass.inline.hpp"
#include "oops/fieldStreams.inline.hpp"
#include "runtime/signature.hpp"
#include "runtime/jniHandles.hpp"
#include "runtime/os.hpp"

// Provide swap overload for JeandleReloc* to resolve ambiguity
inline void swap(JeandleReloc*& a, JeandleReloc*& b) {
  std::swap(a, b);
}

// Decide whether to emit a stack overflow check for the compiled entry based on
// Java call presence and frame size pressure (skip stub compilations).
static bool need_stack_overflow_check(bool is_method_compilation,
                                      bool has_java_calls,
                                      int frame_size_in_bytes) {
  if (!is_method_compilation) {
    return false;
  }

  return has_java_calls ||
         frame_size_in_bytes > (int)(os::vm_page_size() >> 3) DEBUG_ONLY(|| true);
}

static std::string oop_handle_name_for(const char* klass_name, int oop_id) {
  assert(klass_name != nullptr, "klass_name can not be null");
  return std::string("oop_handle_") + klass_name + "_" + std::to_string(oop_id);
}

int JeandleCompiledCode::find_or_insert_oop(ciObject* oop) {
  jobject oop_handle = oop->constant_encoding();
  auto existing = _oop_handle_ids.find(oop_handle);
  if (existing != _oop_handle_ids.end()) {
    return existing->second;
  }

  int oop_id = _oop_handle_info.size();
  std::string oop_name = oop_handle_name_for(oop->klass()->external_name(), oop_id);
  _oop_handle_ids[oop_handle] = oop_id;
  _oop_handles[oop_name] = oop_handle;
  _oop_handle_info.push_back({oop_handle, oop, std::move(oop_name)});
  return oop_id;
}

ciObject* JeandleCompiledCode::oop_at(int oop_id) {
  assert(oop_id >= 0 && (size_t)oop_id < _oop_handle_info.size(), "unknown oop id");
  return _oop_handle_info[oop_id].oop;
}

std::string JeandleCompiledCode::oop_handle_name(int oop_id) {
  assert(oop_id >= 0 && (size_t)oop_id < _oop_handle_info.size(), "unknown oop id");
  return _oop_handle_info[oop_id].name;
}

bool JeandleCompiledCode::needs_clinit_barrier_on_entry() {
  if (_method == nullptr) {
    return false;
  }
  return VM_Version::supports_fast_class_init_checks() && _method->needs_clinit_barrier();
}

bool JeandleCompiledCode::needs_clinit_barrier(ciField* field, ciMethod* accessing_method) {
  return field->is_static() && needs_clinit_barrier(field->holder(), accessing_method);
}

bool JeandleCompiledCode::needs_clinit_barrier(ciMethod* method, ciMethod* accessing_method) {
  return method->is_static() && needs_clinit_barrier(method->holder(), accessing_method);
}

bool JeandleCompiledCode::needs_clinit_barrier(ciInstanceKlass* holder, ciMethod* accessing_method) {
  if (holder->is_initialized()) {
    return false;
  }
  if (holder->is_being_initialized()) {
    if (accessing_method->holder() == holder) {
      // Access inside a class. The barrier can be elided when access happens in <clinit>,
      // <init>, or a static method. In all those cases, there was an initialization
      // barrier on the holder klass passed.
      if (accessing_method->is_static_initializer() ||
          accessing_method->is_object_initializer() ||
          accessing_method->is_static()) {
        return false;
      }
    } else if (accessing_method->holder()->is_subclass_of(holder)) {
      // Access from a subclass. The barrier can be elided only when access happens in <clinit>.
      // In case of <init> or a static method, the barrier is on the subclass is not enough:
      // child class can become fully initialized while its parent class is still being initialized.
      if (accessing_method->is_static_initializer()) {
        return false;
      }
    }
    ciMethod* root = _method; // the root method of compilation
    if (root != accessing_method) {
      return needs_clinit_barrier(holder, root); // check access in the context of compilation root
    }
  }
  return true;
}

bool JeandleCompiledCode::needs_nmethod_entry_barrier() {
  if (_method == nullptr) {
    return false;
  }
  return BarrierSet::barrier_set()->barrier_set_nmethod() != nullptr;
}

void JeandleCompiledCode::install_obj(std::unique_ptr<ObjectBuffer> obj) {
  _obj = std::move(obj);
  llvm::MemoryBufferRef memory_buffer = _obj->getMemBufferRef();
  auto elf_or_error = llvm::object::ObjectFile::createELFObjectFile(memory_buffer);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(elf_or_error, "bad ELF file");

  _elf = llvm::dyn_cast<ELFObject>(*elf_or_error);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(_elf, "bad ELF file");

  int func_count = 0;
  for (const llvm::object::ELFSymbolRef &sym : _elf->symbols()) {
    llvm::Expected<llvm::object::SymbolRef::Type> type = sym.getType();
    if (!type || (*type) != llvm::object::SymbolRef::Type::ST_Function) {
      continue;
    }
    func_count++;
  }
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(func_count == 1,
    "expected exactly one compiled function in ELF, but found multiple");
}

void JeandleCompiledCode::finalize() {
  // Set up code buffer.
  uint64_t align;
  uint64_t offset;
  uint64_t code_size;
  bool found = ReadELF::findFunc(*_elf, _func_name, align, offset, code_size);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(found, "compiled function is not found in the ELF file");

  setup_frame_size();
  RETURN_VOID_ON_JEANDLE_ERROR();
  assert(_frame_size > 0, "frame size must be positive");

  // An estimated initial value.
  uint64_t consts_size = 6144 * wordSize;

  // TODO: How to figure out memory usage.
  _code_buffer.initialize(code_size + consts_size + 2048/* for prolog */,
                          sizeof(relocInfo) + relocInfo::length_limit,
                          160,
                          _env->oop_recorder());
  if (_code_buffer.blob() == nullptr) {
    JEANDLE_REPORT_ERROR_AND_RET_VOID("CodeCache is full");
  }
  _code_buffer.initialize_consts_size(consts_size);

  // Initialize assembler.
  MacroAssembler* masm = new MacroAssembler(&_code_buffer);
  masm->set_oop_recorder(_env->oop_recorder());
  JeandleAssembler assembler(masm);

  bool is_osr_compilation = JeandleCompilation::current()->is_osr_compilation();

  if (is_osr_compilation) {
    assert(masm->offset() == 0, "sanity");
    _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());
    if (PoisonOSREntry) {
      assembler.emit_poisoned_osr_entry();
    }
  } else if (_method && !_method->is_static()) {
    // For non-static Java method finalization.
    assembler.emit_ic_check();
  }

  masm->align(assembler.interior_entry_alignment());

  if (is_osr_compilation) {
    _offsets.set_value(CodeOffsets::OSR_Entry, masm->offset());
  } else {
    _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());
  }
  assembler.emit_verified_entry();

  if (needs_clinit_barrier_on_entry()) {
    Klass* klass = (Klass*)_method->holder()->constant_encoding();
    assembler.emit_clinit_barrier_on_entry(klass);
  }

  int frame_size_in_bytes = _frame_size * BytesPerWord;
  bool is_method_compilation = _method != nullptr;
  bool has_java_calls = !_non_routine_call_sites.empty();
  int bang_size_in_bytes = MAX2(frame_size_in_bytes + os::extra_bang_size_in_bytes(), interpreter_frame_size_in_bytes());
  if (need_stack_overflow_check(is_method_compilation, has_java_calls, bang_size_in_bytes)) {
    masm->generate_stack_overflow_check(bang_size_in_bytes);
  }

  if (needs_nmethod_entry_barrier()) {
    _entry_barrier_stub = new (_env->arena()) JeandleEntryBarrierStub();
    int entry_barrier_offset = assembler.emit_nmethod_entry_barrier(_entry_barrier_stub);
    _offsets.set_value(CodeOffsets::NMethod_Entry_Barrier, entry_barrier_offset);
  }

  assert(align > 1, "invalid alignment");
  masm->align(static_cast<int>(align));

  _prolog_length = masm->offset();

  assembler.emit_insts(((address) _obj->getBufferStart()) + offset, code_size);

  resolve_reloc_info(assembler);
  RETURN_VOID_ON_JEANDLE_ERROR();

  // generate shared trampoline stubs
  if (!_code_buffer.finalize_stubs()) {
    JEANDLE_REPORT_ERROR_AND_RET_VOID("shared stub overflow");
  }

  if (_entry_barrier_stub != nullptr) {
    _entry_barrier_stub->emit(masm);
  }

  if (_method) {
    // For Java method compilation.
    if (!pd_build_exception_handler_table()) {
      build_exception_handler_table();
    }
    _offsets.set_value(CodeOffsets::Exceptions, assembler.emit_exception_handler());
    RETURN_VOID_ON_JEANDLE_ERROR();
  }

  build_implicit_exception_table();

  if (_method) {
    _offsets.set_value(CodeOffsets::Deopt, assembler.emit_deopt_handler());
    RETURN_VOID_ON_JEANDLE_ERROR();
    if (_has_method_handle_invoke) {
      _offsets.set_value(CodeOffsets::DeoptMH, assembler.emit_deopt_handler());
      RETURN_VOID_ON_JEANDLE_ERROR();
    }
  }
}

void JeandleCompiledCode::resolve_reloc_info(JeandleAssembler& assembler) {
  llvm::SmallVector<JeandleReloc*> relocs;

  // Step 1: Resolve LinkGraph.
  auto ssp = std::make_shared<llvm::orc::SymbolStringPool>();

  auto graph_or_err = llvm::jitlink::createLinkGraphFromObject(_elf->getMemoryBufferRef(), ssp);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(graph_or_err, "failed to create LinkGraph");

  auto link_graph = std::move(*graph_or_err);

  if (!pd_resolve_reloc(assembler, relocs, link_graph.get())) {
    for (auto *block : link_graph->blocks()) {
      // Resolve relocations in the compiled code and constant pool.
      if (block->getSection().getName().compare(".text") != 0 &&
          !block->getSection().getName().starts_with(".data.rel.ro") &&
          !block->getSection().getName().starts_with(".rodata")) {
        continue;
      }
      for (auto& edge : block->edges()) {
        auto& target = edge.getTarget();
        llvm::StringRef target_name = target.hasName() ? *(target.getName()) : "";

        if (JeandleAssembler::is_routine_call_reloc(target, edge.getKind())) {
          // Routine call relocations.
          address target_addr = JeandleRuntimeRoutine::get_routine_entry(target_name);

          int inst_end_offset = JeandleAssembler::fixup_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

          CallSiteInfo* call_info = new CallSiteInfo(JeandleCompiledCall::ROUTINE_CALL, target_addr);
          if (JeandleRuntimeRoutine::is_gc_leaf(target_addr)) {
            relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, call_info));
          } else {
            // JeandleCallReloc for a non-gc-leaf routine call site will be created during stackmaps resolving because an oopmap is required.
            _routine_call_sites[inst_end_offset] = call_info;
          }
        } else if (JeandleAssembler::is_external_call_reloc(target, edge.getKind())) {
          // External call relocations.
          address target_addr = (address)DynamicLibrary::SearchForAddressOfSymbol(target_name.str().c_str());
          JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(target_addr, "failed to find external symbol");

          int inst_end_offset = JeandleAssembler::fixup_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

          CallSiteInfo* call_info = new CallSiteInfo(JeandleCompiledCall::EXTERNAL_CALL, target_addr);
          // LLVM doesn't rewrite intrinsic calls to statepoints, so we don't need oopmaps for external calls.
          relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, call_info));
        } else if (JeandleAssembler::is_section_word_reloc(target, edge.getKind())) {
          // Const relocations.
          address target_addr;
          int reloc_offset;
          int reloc_section;
          if (target.getSection().getName().starts_with(".rodata") ||
              target.getSection().getName().starts_with(".data.rel.ro")) {
            assert(block->getSection().getName().compare(".text") == 0, "invalid reloc section");
            target_addr = resolve_const_edge(*block, edge, assembler);
            RETURN_VOID_ON_JEANDLE_ERROR();
            reloc_offset = static_cast<int>(block->getAddress().getValue() + edge.getOffset());
            reloc_section = CodeBuffer::SECT_INSTS;
          } else {
            assert(target.getSection().getName().compare(".text") == 0, "invalid target section");
            target_addr = _code_buffer.insts()->start();
            address reloc_site = resolve_const_reloc_site(*block, edge, assembler);
            RETURN_VOID_ON_JEANDLE_ERROR();
            reloc_offset = reloc_site - _code_buffer.consts()->start();
            reloc_section = CodeBuffer::SECT_CONSTS;
          }
          relocs.push_back(new JeandleSectionWordReloc(reloc_offset, edge, target_addr, reloc_section));
        } else if (JeandleAssembler::is_oop_reloc(target, edge.getKind())) {
          // Oop relocations.
          assert((target_name).starts_with("oop_handle"), "invalid oop relocation name");
          auto oop_it = _oop_handles.find(target_name);
          JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(oop_it != _oop_handles.end(), "missing oop handle in relocation");
          relocs.push_back(new JeandleOopReloc(static_cast<int>(block->getAddress().getValue() + edge.getOffset()),
                                               oop_it->getValue(),
                                               edge.getAddend()));
        } else if (JeandleAssembler::is_oop_addr_reloc(target, edge.getKind())) {
          // Oop addr relocations.
          assert((target_name).starts_with("oop_handle"), "invalid oop relocation name");
          auto oop_it = _oop_handles.find(target_name);
          JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(oop_it != _oop_handles.end(), "missing oop handle in relocation");
          address reloc_site = resolve_const_reloc_site(*block, edge, assembler);
          RETURN_VOID_ON_JEANDLE_ERROR();
          relocs.push_back(new JeandleOopAddrReloc(static_cast<int>(reloc_site - _code_buffer.consts()->start()),
                                                   oop_it->getValue()));
        } else {
          // Unhandled relocations
          ShouldNotReachHere();
        }
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
        auto location = record->location_begin();
        int num_deopts = parse_stackmap_prologue(record, location);
        JeandleCallReloc* reloc = new JeandleCallReloc(inst_end_offset, _env, _method, call_info);
        JeandleParseContext parse_context = _method != nullptr ? JeandleParseContext::root(_method)
                                                               : JeandleParseContext();
        // A stackmap record may contain several Java scopes after LLVM inlines
        // callee IR into the root method. Jeandle emits each inlinee scope with a
        // leading MethodType marker. parse_stackmap consumes one scope at a time:
        // it stops at that marker, returns the caller scope, and passes the marked
        // method back as next_inlinee so the next iteration can parse the inlinee
        // frame with the right ciMethod for BCI and scope-value decoding.
        // Record-level (whole-deopt-point) VO id -> ObjectValue map, shared by
        // every scope parsed from this stackmap record. PEA emits ALL VO
        // descriptors into the ROOT scope's VO section (the deopt-point-level
        // object pool — C2 dump_object_pool-before-scope-values analog), so a
        // VORef slot / eliminated-monitor owner in ANY scope resolves against
        // an ObjectValue created while parsing the root scope (scopes are
        // parsed outermost-first). Per-scope maps would reject exactly those
        // outer-scope references — this record-level sharing is that fix.
        llvm::DenseMap<int, ObjectValue*> vo_map;
        GrowableArray<JeandleDeferredVORefField> deferred_voref_fields;
        do {
          ciMethod* next_inlinee = nullptr;
          reloc->add_stack_map(parse_stackmap(stackmaps, record, location, num_deopts,
                                              parse_context, next_inlinee,
                                              vo_map, deferred_voref_fields));
          if (next_inlinee != nullptr) {
            parse_context = JeandleParseContext::inlinee(next_inlinee);
          }
        } while (location != record->location_end());
        relocs.push_back(reloc);
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
    RETURN_VOID_ON_JEANDLE_ERROR();
  }
}

address JeandleCompiledCode::lookup_const_section(llvm::StringRef name, JeandleAssembler& assembler) {
  auto it = _const_sections.find(name);
  if (it == _const_sections.end()) {
    // Copy to CodeBuffer if const section is not found.
    SectionInfo section_info(name);
    bool found = ReadELF::findSection(*_elf, section_info);
    JEANDLE_ERROR_ASSERT_AND_RET_ON_FAIL(found, "const section not found, bad ELF file", nullptr);

    address target_base = _code_buffer.consts()->end();
    int padding = assembler.emit_consts(((address) _obj->getBufferStart()) + section_info._offset,
                                         section_info._size,
                                         section_info._alignment);
    target_base += padding;
    _const_sections.insert({name, target_base});
    return target_base;
  }

  return it->getValue();
}

address JeandleCompiledCode::resolve_const_reloc_site(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assembler) {
  llvm::StringRef section_name = block.getSection().getName();
  assert(section_name.starts_with(".rodata") || section_name.starts_with(".data.rel.ro"),
         "invalid const relocation section");

  address section_base = lookup_const_section(section_name, assembler);
  if (section_base == nullptr) {
    return nullptr;
  }

  llvm::jitlink::SectionRange range(block.getSection());
  uint64_t offset_in_section = block.getAddress() - range.getFirstBlock()->getAddress();
  return section_base + offset_in_section + edge.getOffset();
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

LocationValue* JeandleCompiledCode::new_location_value(const StackMapParser::LocationAccessor& location, Location::Type type) {
  return StackMapUtil::is_stack(location)
    ? new LocationValue(Location::new_stk_loc(type, StackMapUtil::stack_offset(location)))
    : new LocationValue(Location::new_reg_loc(type, resolve_vmreg(location, location.getKind())));
}

void JeandleCompiledCode::fill_one_scope_value(const StackMapParser& stackmaps,
                                               const DeoptValueEncoding& encode,
                                               const StackMapParser::LocationAccessor& location,
                                               GrowableArray<ScopeValue*>* array) {
  assert(array != nullptr, "sanity");
  bool is_constant = StackMapUtil::is_constant(location);
  switch (static_cast<BasicType>(encode.basicType())) {
  case T_INT: {
    if (is_constant) {
      jint const_int = JeandleBitCast::bit_cast<jint>(StackMapUtil::getConstantUint(stackmaps, location));
      array->append(new ConstantIntValue(const_int));
    } else {
      array->append(new_location_value(location, Location::normal));
    }
    break;
  }
  case T_LONG: {
    // 2 stack slots for long type
    array->append(new ConstantIntValue((jint)0));
    if (is_constant) {
      jlong const_long = JeandleBitCast::bit_cast<jlong>(StackMapUtil::getConstantUlong(stackmaps, location));
      array->append(new ConstantLongValue(const_long));
    } else {
      array->append(new_location_value(location, Location::lng));
    }
    break;
  }
  case T_FLOAT: {
    if (is_constant) {
      array->append(new ConstantIntValue(jint_cast(StackMapUtil::getConstantFloat(stackmaps, location))));
    } else {
      array->append(new_location_value(location, Location::normal));
    }
    break;
  }
  case T_DOUBLE: {
    // 2 stack slots for double type
    array->append(new ConstantIntValue((jint)0));
    if (is_constant) {
      array->append(new ConstantDoubleValue(StackMapUtil::getConstantDouble(stackmaps, location)));
    } else {
      array->append(new_location_value(location, Location::dbl));
    }
    break;
  }
  case T_OBJECT: {
    if (is_constant) {
      uint64_t v = StackMapUtil::getConstantUlong(stackmaps, location);
      if (v == 0L) {
        array->append(new ConstantOopWriteValue(nullptr));
      } else {
        /* No constant oop is embedding into code */
        ShouldNotReachHere();
      }
    } else {
      array->append(new_location_value(location, Location::oop));
    }
    break;
  }
  case T_ILLEGAL: {
    uint32_t val = StackMapUtil::getConstantUint(stackmaps, location);
    assert(val == 0, "must be zero for T_ILLEGAL");
    // put an illegal value
    array->append(new LocationValue(Location()));
    break;
  }
  default:
    Unimplemented();
  }
}

void JeandleCompiledCode::fill_one_monitor_value(const StackMapParser& stackmaps,
                                                 const DeoptValueEncoding& encode,
                                                 const StackMapParser::LocationAccessor& object,
                                                 const StackMapParser::LocationAccessor& lock,
                                                 GrowableArray<MonitorValue*>* array) {
  assert(array != nullptr, "sanity");
  assert(static_cast<BasicType>(encode.basicType()) == T_OBJECT, "should be");

  bool is_constant = StackMapUtil::is_constant(object);
  ScopeValue* locked_object = nullptr;
  if (is_constant) {
    uint64_t v = StackMapUtil::getConstantUlong(stackmaps, object);
    if (v == 0L) {
      locked_object = new ConstantOopWriteValue(nullptr);
    } else {
      /* No constant oop is embedding into code */
      ShouldNotReachHere();
    }
  } else {
    locked_object = new_location_value(object, Location::oop);
  }
  Location basic_lock = Location::new_stk_loc(Location::normal, StackMapUtil::stack_offset(lock));
  array->append(new MonitorValue(locked_object, basic_lock, false /* eliminated */));
}

static bool bytecode_should_reexecute(Bytecodes::Code code) {
  if (code == Bytecodes::_ireturn || code == Bytecodes::_lreturn ||
      code == Bytecodes::_freturn || code == Bytecodes::_dreturn ||
      code == Bytecodes::_areturn || code == Bytecodes::_return) {
    return true;
  } else {
    return AbstractInterpreter::bytecode_should_reexecute(code);
  }
}

// PEA VO deopt: one non-static, non-injected instance field of the layout that
// Deoptimization::reassign_fields_by_klass walks. Used to build an ObjectValue's
// field_values in exactly the order/count reassign consumes (1 slot for int-like
// and reference fields, 2 for long/double), padding untouched fields with type
// defaults so field_at(svIndex) never reads out of bounds at deopt.
struct JeandleReassignedField {
  int offset;
  BasicType type;
};

static int jeandle_compare_reassigned_field(JeandleReassignedField* a,
                                            JeandleReassignedField* b) {
  return a->offset - b->offset;
}

// One emitted VO descriptor field, classified as either a plain scalar value
// (resolved immediately via fill_one_scope_value) or a VORef to another VO in
// the same deopt point (resolved by vo-id through vo_map, possibly deferred for
// forward references / cycles — see JeandleDeferredVORefField). A scalar
// long/double field occupies TWO field_values slots: sv1 is the hi placeholder
// (ConstantIntValue(0)) and sv2 is the lo full value
// (ConstantLongValue/ConstantDoubleValue); sv2 is null for single-slot fields.
struct JeandleEmitField {
  int offset;
  bool is_voref;
  ScopeValue* sv1 = nullptr;  // first scope value; valid when !is_voref
  ScopeValue* sv2 = nullptr;  // second scope value (long/double only); null
                              // for single-slot (int/float/ref) fields
  int voref_id = -1;          // valid when is_voref (vo-id of the referenced VO)
};

int JeandleCompiledCode::parse_stackmap_prologue(StackMapParser::record_iterator& record,
                                                 StackMapParser::RecordAccessor::location_iterator& location) {
  assert(_frame_size > 0, "frame size must be greater than zero");

  // The first 2 constants are ignored, the third constant is the number of deopt operands
  assert(location != record->location_end(), "must be in range");

  // Ignore frame size
  auto first = *(location++);
  assert(location != record->location_end(), "must be in range");

  // Ignore frame offset
  auto second = *(location++);
  assert(location != record->location_end(), "must be in range");

  auto third = *(location++);

  assert(first.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");
  assert(second.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");
  assert(third.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");

  return third.getSmallConstant();
}

JeandleStackMap* JeandleCompiledCode::parse_stackmap(StackMapParser& stackmaps,
                                                     StackMapParser::record_iterator& record,
                                                     StackMapParser::RecordAccessor::location_iterator& location,
                                                     int& num_deopts,
                                                     const JeandleParseContext& parse_context,
                                                     ciMethod*& next_inlinee,
                                                     llvm::DenseMap<int, ObjectValue*>& vo_map,
                                                     GrowableArray<JeandleDeferredVORefField>& deferred_voref_fields) {
  bool reexecute = false;
  int bci = -1;
  ciMethod* current_method = parse_context.method();
  next_inlinee = nullptr;

  if (num_deopts > 0) {
    assert(current_method != nullptr, "must be method compilation");

    // should_reexecute flag goes first (explicitly set by intrinsic lowering to match C2 behavior).
    // Pushed as i64 on the frontend side so it can't be mistaken for a duplicated-bci marker
    // (see JeandleAbstractInterpreter::deopt_args), so read it with the wide-constant accessor.
    assert(location != record->location_end(), "must be in range");
    bool forced_reexecute = (StackMapUtil::getConstantUlong(stackmaps, *(location++)) != 0);
    num_deopts--;

    // bci goes next in deopt operands
    assert(location != record->location_end(), "must be in range");
    bci = (location++)->getSmallConstant();
    assert(location != record->location_end(), "must be in range");
    guarantee(bci == (int)((location++)->getSmallConstant()), "duplicated bci must match");
    num_deopts -= 2;

    if (bci != InvocationEntryBci) {
      Bytecodes::Code code = current_method->java_code_at_bci(bci);
      reexecute = forced_reexecute || bytecode_should_reexecute(code); /* TODO: special case of multianewarray, please check GraphKit::should_reexecute_implied_by_bytecode */
    }
  }

#ifdef ASSERT
  if (num_deopts > 0 && log_is_enabled(Trace, jeandle)) {
    tty->print("Parsing stackmap at bci %d, num_deopts = %d, reexecute = %d, inst_offset = 0x%X\n", bci, num_deopts, reexecute, record->getInstructionOffset());
  }
#endif

  // build scope values
  GrowableArray<ScopeValue*>* locals = num_deopts > 0 ? new GrowableArray<ScopeValue*>(current_method->max_locals()) : nullptr;
  GrowableArray<ScopeValue*>* stack  = num_deopts > 0 ? new GrowableArray<ScopeValue*>(current_method->max_stack()) : nullptr;
  GrowableArray<MonitorValue*>* monitors = num_deopts > 0 ? new GrowableArray<MonitorValue*>() : nullptr;
  llvm::DenseSet<int> narrow_oop_locations;
  // Record-level VO id -> ObjectValue map for PEA virtual-object (VO)
  // descriptors, shared by the caller (resolve_reloc_info) across every scope
  // parsed from this stackmap record. PEA emits ALL VO descriptors into the
  // ROOT scope's VO section — the deopt-point-level object pool. Each
  // ScalarValueType registers its ObjectValue before parsing its fields, so
  // self and backward VORef fields resolve immediately. Only references to a
  // descriptor not registered yet (forward references, including cycles) are
  // recorded in deferred_voref_fields and resolved after their targets have
  // been parsed.
  // Resolve every deferred VORef field now that all target descriptors for
  // this scope have been registered.
  // Called before each scope return (end-of-scope and the MethodType marker).
  auto flush_deferred_voref_fields = [&]() {
    for (int i = 0; i < deferred_voref_fields.length(); i++) {
      const JeandleDeferredVORefField& D = deferred_voref_fields.at(i);
      ObjectValue* target = vo_map.lookup(D.voref_id);
      assert(target != nullptr,
             "dangling VORef field: vo_id %d not described by a ScalarValueType",
             D.voref_id);
      D.owning_ov->field_values()->at(D.field_values_index) = target;
    }
    // The list is record-level (survives across scopes of this record); clear
    // it after each flush so entries are not flushed again at a later scope.
    deferred_voref_fields.clear();
  };
  // The objects array accumulates every ObjectValue built this scope and is
  // handed to DebugInformationRecorder::dump_object_pool for realloc_objects.
  GrowableArray<ScopeValue*>* objects = nullptr;
  while (num_deopts > 0) {
    // local and stack deopt arguments are passed as a pair: <encode, value>
    // monitor deopt arguments are passed as a tuple: <encode, object, lock>
    assert(location != record->location_end(), "must be in range");
    auto encode_location = *(location++);

    uint64_t encode = StackMapUtil::getConstantUlong(stackmaps, encode_location);
    DeoptValueEncoding enc = DeoptValueEncoding::decode(encode);
    int type = enc.valueType();

#ifdef ASSERT
    if (log_is_enabled(Trace, jeandle)) {
      print_deopt_value(enc);
    }
#endif

    switch (type) {
      case DeoptValueEncoding::LocalType: // fall through
      case DeoptValueEncoding::StackType: {
        assert(location != record->location_end(), "must be in range");
        auto value_location = *(location++);

        bool is_local = type == DeoptValueEncoding::LocalType;
        fill_one_scope_value(stackmaps, enc, value_location,
                             is_local ? locals : stack);
        num_deopts -= 2;
        break;
      }
      case DeoptValueEncoding::MonitorType: {
        assert(location != record->location_end(), "must be in range");
        auto obj_location = *(location++);

        assert(location != record->location_end(), "must be in range");
        auto lock_location = *(location++);

        if (enc.index() == 1) {
          // A PEA-ELIMINATED lock on a VIRTUAL object. The owner slot carries
          // the owner VO's vo-id as an i32 CONSTANT (NOT a live oop); resolve
          // it through vo_map to the owner's ObjectValue*, which was already
          // parsed from the ScalarValueType descriptor section earlier in this
          // deopt point (descriptors live in the root scope's VO section;
          // vo_map is record-level). Build a MonitorValue with eliminated=true
          // so HotSpot relock_objects re-acquires the monitor on the realloc'd
          // owner at deopt (C2/Graal MonitorValue{owner=ObjectValue,
          // eliminated=true} analog; docs/c2-ea-deopt-survey.md §4.6). The
          // basic_lock slot is preserved verbatim; ObjectSynchronizer::enter
          // initializes it.
          int vo_id = (int)StackMapUtil::getConstantUint(stackmaps, obj_location);
          ObjectValue* owner_ov = vo_map.lookup(vo_id);
          assert(owner_ov != nullptr,
                 "PEA eliminated-lock owner vo_id %d not described by a VO "
                 "descriptor in this deopt point",
                 vo_id);
          Location basic_lock = Location::new_stk_loc(Location::normal,
                                 StackMapUtil::stack_offset(lock_location));
          monitors->append(new MonitorValue(owner_ov, basic_lock,
                                            true /* eliminated */));
        } else {
          // index=0: REAL (non-eliminated) lock — owner is a live oop (a
          // stack/register location, or null). eliminated=false (the lock is
          // genuinely held, e.g. a re-emitted monitorenter on a materialized
          // VO's OrigAlloc), so relock_objects leaves it alone.
          fill_one_monitor_value(stackmaps, enc, obj_location, lock_location,
                                 monitors);
        }
        num_deopts -= 3;
        break;
      }
      case DeoptValueEncoding::OrigPcSlotType: {
        assert(location != record->location_end(), "must be in range");
        auto orig_pc_location = *(location++);
        assert(StackMapUtil::is_stack(orig_pc_location), "orig pc slot must be stack allocated");
        set_real_orig_pc_offset_in_bytes(StackMapUtil::stack_offset(orig_pc_location));
        num_deopts -= 2;
        break;
      }
      case DeoptValueEncoding::MethodType: {
        // MethodType is the first value in the stack map of inlinee
        // and it also serves as a marker to stop parsing the previous stack map.
        assert(location != record->location_end(), "must be in range");
        next_inlinee = (ciMethod*)(StackMapUtil::getConstantUlong(stackmaps, *(location++)));
        num_deopts -= 2;
        // The marker belongs to the next inlinee scope. Return the caller scope
        // now and let the outer loop continue parsing from the same stackmap
        // record; only the youngest scope consumes the oopmap tail. Flush any
        // deferred VORef fields for this scope first (forward refs / cycles
        // whose targets are now all parsed).
        flush_deferred_voref_fields();
        return new JeandleStackMap(bci, current_method, nullptr, locals, stack, monitors, reexecute, objects);
      }
      case DeoptValueEncoding::NarrowOopMarkerType: {
        assert(UseCompressedOops, "narrowoop only valid with CompressedOops");
        assert(location != record->location_end(), "must be in range");
        auto narrow_oop_location = *(location++);
        StackMapParser::LocationKind narrow_oop_kind = narrow_oop_location.getKind();
        if (narrow_oop_kind == StackMapParser::LocationKind::Register ||
            narrow_oop_kind == StackMapParser::LocationKind::Indirect) {
          VMReg narrow_oop_reg = resolve_vmreg(narrow_oop_location, narrow_oop_kind);
          narrow_oop_locations.insert(narrow_oop_reg->value());
        }
        num_deopts -= 2;
        break;
      }
      case DeoptValueEncoding::ScalarValueType: {
        // PEA virtual-object descriptor. The header (this encoding) location was
        // already consumed by the loop; the wire layout that remains is:
        //   [klass]       i64 constant = raw Klass* identity
        //   [field_count] i32 constant
        //   field_count x ([field_enc][field_value])
        // See DeoptValueEncoding::ScalarValueType (Jeandle/Deoptimization.h) and
        // appendVirtualObjectDescriptor (JeandleTransformUtils.cpp). The parser
        // consumes (3 + 2*field_count) locations for one descriptor.
        int vo_id = enc.index();

        assert(location != record->location_end(), "must be in range");
        uint64_t klass_raw = StackMapUtil::getConstantUlong(stackmaps, *(location++));

        assert(location != record->location_end(), "must be in range");
        int field_count = (int)StackMapUtil::getConstantUint(stackmaps, *(location++));

        // Resolve klass -> java mirror via the ci interface (matches
        // jeandle_get_java_mirror and C2 FillLocArray). parse_stackmap runs at
        // code-installation time on the compiler thread, which is
        // _thread_in_native; a raw klass->java_mirror() + JNIHandles::make_local
        // would oop-access in that state and trip
        // AccessInternal::check_access_thread_state. ciKlass::java_mirror
        // ->constant_encoding() reads only cached ci state and is safe from
        // compiler threads. Wrap as ConstantOopWriteValue so
        // Deoptimization::realloc_objects can recover the Klass and allocate the
        // right type (instance via allocate_instance, array via the array klass
        // allocate, with length derived from field_values.size()).
        Klass* klass = (Klass*)klass_raw;
        const bool is_array =
            klass->is_typeArray_klass() || klass->is_objArray_klass();

        VM_ENTRY_MARK;
        ciKlass* ci_k = ciEnv::current()->get_klass(klass);
        assert(ci_k != nullptr && ci_k->is_loaded(),
               "PEA VO klass must be loaded");
        ConstantOopWriteValue* klass_sv = new ConstantOopWriteValue(
            ci_k->java_mirror()->constant_encoding());
        ObjectValue* ov = new ObjectValue(vo_id, klass_sv);

        if (objects == nullptr) {
          objects = new GrowableArray<ScopeValue*>();
        }
        objects->append(ov);
        vo_map[vo_id] = ov;

        // Read the emitted (touched) fields into an offset-keyed list. The
        // offset rides in the field encoding's Index field (see
        // appendVirtualObjectDescriptor). A field whose encoding ValueTy is
        // VORefLocalType is a VORef FIELD: its value slot is an i32 vo-id
        // referencing another VO in this deopt point, and its ScopeValue is that VO's
        // ObjectValue (resolved via vo_map, possibly deferred for forward refs
        // / cycles). A scalar field is resolved here via fill_one_scope_value.
        // We do NOT append to field_values yet: reassign_fields_by_klass walks
        // ALL non-static, non-injected fields of the InstanceKlass hierarchy
        // offset-sorted and consumes field_values in that order (1 slot for
        // int-like/ref, 2 for long/double), so we enumerate the same layout
        // and emit each field (emitted value if touched, else a type default)
        // in that exact order.
        GrowableArray<JeandleEmitField> emit_fields;
        for (int i = 0; i < field_count; i++) {
          assert(location != record->location_end(), "must be in range");
          auto field_enc_location = *(location++);
          DeoptValueEncoding field_enc = DeoptValueEncoding::decode(
              StackMapUtil::getConstantUlong(stackmaps, field_enc_location));
          assert(location != record->location_end(), "must be in range");
          auto field_value_location = *(location++);
          JeandleEmitField ef;
          ef.offset = field_enc.index();
          ef.is_voref = false;
          ef.voref_id = -1;
          if (field_enc.valueType() == DeoptValueEncoding::VORefLocalType) {
            // VORef field: value slot is an i32 vo-id. Do NOT route through
            // fill_one_scope_value — its T_OBJECT constant branch would trip
            // ShouldNotReachHere on the non-oop vo-id constant.
            ef.is_voref = true;
            ef.voref_id =
                (int)StackMapUtil::getConstantUint(stackmaps, field_value_location);
          } else {
            GrowableArray<ScopeValue*> one;
            fill_one_scope_value(stackmaps, field_enc, field_value_location, &one);
            // fill_one_scope_value emits one ScopeValue for single-slot fields,
            // two (ConstantIntValue(0) hi + ConstantLong/DoubleValue lo) for
            // long/double, per the JeandleEmitField sv1/sv2 contract. The wire
            // still carries ONE (enc, value) entry per touched field.
            assert(one.length() == 1 || one.length() == 2,
                   "emitted field must be single-slot or long/double two-slot");
            ef.sv1 = one.at(0);
            if (one.length() == 2)
              ef.sv2 = one.at(1);
          }
          emit_fields.append(ef);
        }

        if (is_array) {
          // Array: the LLVM emit provides ALL elements (field_count ==
          // ArrayLength, touched + default) in offset / element-index order, so
          // emit them directly. Arrays have no InstanceKlass field stream, so
          // there is no layout walk; reassign_type_array_elements /
          // reassign_object_array_elements consume field_values in index order
          // and HotSpot's realloc_objects derives the length from
          // field_values.size() (typeArray len = field_size()/type2size;
          // objArray len = field_size()).
          for (int j = 0; j < emit_fields.length(); j++) {
            const JeandleEmitField& ef = emit_fields.at(j);
            if (ef.is_voref) {
              // objArray element referencing another VO: resolve via vo_map now
              // if already parsed, else defer (forward ref / cycle).
              ObjectValue* target = vo_map.lookup(ef.voref_id);
              if (target != nullptr) {
                ov->field_values()->append(target);
              } else {
                int idx = ov->field_values()->length();
                ov->field_values()->append(nullptr); // placeholder
                deferred_voref_fields.append({ov, idx, ef.voref_id});
              }
            } else {
              // Scalar element (primitive, or a live materialized oop); append
              // sv1 (and sv2 if long/double, per JeandleEmitField).
              ov->field_values()->append(ef.sv1);
              if (ef.sv2 != nullptr)
                ov->field_values()->append(ef.sv2);
            }
          }
        } else {
          // Instance: enumerate the InstanceKlass layout EXACTLY as
          // reassign_fields_by_klass does for a Jeandle-compiled (non-JVMCI)
          // method: skip_internal == true (deoptimization.cpp:371), so injected
          // fields are excluded. Sort by offset with the same comparator so the
          // consume order matches.
          assert(klass->is_instance_klass(),
                 "PEA instance VO must be an instance klass");
          InstanceKlass* ik = InstanceKlass::cast(klass);
          GrowableArray<JeandleReassignedField> layout;
          for (InstanceKlass* k = ik; k != nullptr; k = k->superklass()) {
            for (AllFieldStream fs(k); !fs.done(); fs.next()) {
              if (fs.access_flags().is_static()) continue;
              if (fs.field_flags().is_injected()) continue; // skip_internal=true
              layout.append({fs.offset(), Signature::basic_type(fs.signature())});
            }
          }
          layout.sort(jeandle_compare_reassigned_field);
          for (int i = 0; i < layout.length(); i++) {
            int off = layout.at(i).offset;
            BasicType bt = layout.at(i).type;
            // Find the emitted field matching this layout offset (if touched).
            const JeandleEmitField* ef = nullptr;
            for (int j = 0; j < emit_fields.length(); j++) {
              if (emit_fields.at(j).offset == off) {
                ef = &emit_fields.at(j);
                break;
              }
            }
            if (ef != nullptr && ef->is_voref) {
              // VORef field. Resolve via vo_map now if the target is already
              // parsed (backward ref / self-cycle); otherwise defer (forward
              // ref / mutual cycle) — the placeholder is overwritten once the
              // whole VO section has been parsed.
              ObjectValue* target = vo_map.lookup(ef->voref_id);
              if (target != nullptr) {
                ov->field_values()->append(target);
              } else {
                int idx = ov->field_values()->length();
                ov->field_values()->append(nullptr); // placeholder
                deferred_voref_fields.append({ov, idx, ef->voref_id});
              }
            } else if (ef != nullptr) {
              // Touched scalar field; append sv1 (and sv2 if long/double, per
              // JeandleEmitField) to match the layout slot count
              // reassign_fields_by_klass consumes for this field.
              ov->field_values()->append(ef->sv1);
              if (ef->sv2 != nullptr)
                ov->field_values()->append(ef->sv2);
            } else if (bt == T_LONG) {
              // Untouched wide fields use the same typed two-slot form as
              // touched fields. On LP64 the second slot supplies all 64 bits.
              ov->field_values()->append(new ConstantIntValue(0));
              ov->field_values()->append(new ConstantLongValue((jlong)0));
            } else if (bt == T_DOUBLE) {
              ov->field_values()->append(new ConstantIntValue(0));
              ov->field_values()->append(new ConstantDoubleValue(0.0));
            } else if (is_reference_type(bt)) {
              ov->field_values()->append(new ConstantOopWriteValue(nullptr));
            } else {
              ov->field_values()->append(new ConstantIntValue(0));
            }
          }
        }
        num_deopts -= 3 + 2 * field_count;
        break;
      }
      case DeoptValueEncoding::VORefLocalType: // fall through
      case DeoptValueEncoding::VORefStackType: {
        // A locals / stack slot that references a VO described by a
        // ScalarValueType descriptor earlier in this deopt point (all
        // descriptors live in the root scope's VO section; vo_map is
        // record-level). The trailing location
        // is the i32 vo_id; the slot's ScopeValue is the ObjectValue for that id.
        // Two distinct types (not one VORefType) so the parser routes the slot to
        // the correct interpreter array (locals vs expression stack).
        assert(location != record->location_end(), "must be in range");
        int vo_id = (int)StackMapUtil::getConstantUint(stackmaps, *(location++));

        ObjectValue* ov = vo_map.lookup(vo_id);
        assert(ov != nullptr, "dangling VORef: vo_id %d not described by a ScalarValueType", vo_id);

        bool is_local = type == DeoptValueEncoding::VORefLocalType;
        (is_local ? locals : stack)->append(ov);
        num_deopts -= 2;
        break;
      }
      default:
        Unimplemented();
    }

  }

  // build oop map
  OopMap* oop_map = new OopMap(frame_size_in_slots(), 0);
  llvm::DenseSet<int> wide_oop_roots;

  auto set_wide_oop_once = [&](VMReg reg) {
    assert(reg->is_valid(), "invalid oop VMReg");
    assert(oop_map->legal_vm_reg_name(reg), "illegal oopMap register name");

    if (wide_oop_roots.insert(reg->value()).second) {
      oop_map->set_oop(reg);
    }
  };


  while (location != record->location_end()) {
    // Each GC pair is encoded as: base location, derived location.
    auto base_location = *(location++);

    assert(location != record->location_end(), "missing derived pointer location");
    auto derived_location = *(location++);

    StackMapParser::LocationKind base_kind = base_location.getKind();
    StackMapParser::LocationKind derived_kind = derived_location.getKind();

    if (derived_kind != StackMapParser::LocationKind::Register &&
        derived_kind != StackMapParser::LocationKind::Indirect) {
      continue;
    }

    VMReg reg_derived = resolve_vmreg(derived_location, derived_kind);
    bool is_narrowoop = UseCompressedOops && narrow_oop_locations.contains(reg_derived->value());

    if (!is_narrowoop) {

      if (base_kind != StackMapParser::LocationKind::Register &&
          base_kind != StackMapParser::LocationKind::Indirect) {
        continue;
      }
      VMReg reg_base = resolve_vmreg(base_location, base_kind);

      if (reg_base == reg_derived) {
        set_wide_oop_once(reg_derived);
      } else {
        set_wide_oop_once(reg_base);
        oop_map->set_derived_oop(reg_derived, reg_base);
      }
    } else {
      oop_map->set_narrowoop(reg_derived);
    }
  }
  // Flush any deferred VORef fields for this scope (forward refs / cycles
  // whose target VOs are now all parsed and registered in vo_map).
  flush_deferred_voref_fields();
  return new JeandleStackMap(bci, current_method, oop_map, locals, stack, monitors, reexecute, objects);
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

    uint8_t type_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && type_encoding == llvm::dwarf::DW_EH_PE_omit, "invalid exception handler table type encoding");

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

void JeandleCompiledCode::build_implicit_exception_table() {
  SectionInfo section_info(".llvm_faultmaps");
  if (!ReadELF::findSection(*_elf, section_info)) {
      // No implicit exception table.
      return;
  }

  uint64_t section_begin = (uint64_t)object_start() + section_info._offset;
  uint64_t section_end = section_begin + section_info._size;

  llvm::FaultMapParser faultmaps((uint8_t*)section_begin, (uint8_t*)section_end);

#ifdef ASSERT
  auto version = faultmaps.getFaultMapVersion();
  assert(version == 1, "unsupported fault map version");

  auto num_functions = faultmaps.getNumFunctions();
  assert(num_functions == 1, "only one function should exist in the fault map");
#endif

  auto function_info = faultmaps.getFirstFunctionInfo();
  auto num_faulting_pcs = function_info.getNumFaultingPCs();

  for (uint32_t i = 0; i < num_faulting_pcs; i++) {
    auto fault_info = function_info.getFunctionFaultInfoAt(i);

    auto faulting_offset = fault_info.getFaultingPCOffset() + _prolog_length;
    auto handler_offset = fault_info.getHandlerPCOffset() + _prolog_length;

    _implicit_exception_table.append(faulting_offset, handler_offset);
  }
}

int JeandleCompiledCode::frame_size_in_slots() {
  return _frame_size * sizeof(intptr_t) / VMRegImpl::stack_slot_size;
}

void JeandleCompiledCode::set_real_orig_pc_offset_in_bytes(int offset) {
  assert(offset >= 0, "sanity");
  if (_orig_pc_offset_in_bytes == -1) {
    _orig_pc_offset_in_bytes = offset;
  } else {
    assert(_orig_pc_offset_in_bytes == offset, "orig pc slot offset must be stable");
  }
}

uint32_t StackMapUtil::getConstantUint(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  switch (location.getKind()) {
    case StackMapParser::LocationKind::Constant:
      return location.getSmallConstant();
    case StackMapParser::LocationKind::ConstantIndex: {
      // is it possible llvm embed a int value as a long?
      uint32_t index = location.getConstantIndex();
      uint64_t val = parser.getConstant(index).getValue();
      assert(val <= UINT32_MAX, "must be in range");
      return (uint32_t)val;
    }
    default:
      ShouldNotReachHere();
  }
}

uint64_t StackMapUtil::getConstantUlong(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  switch (location.getKind()) {
  case StackMapParser::LocationKind::Constant:
    return (uint64_t)(JeandleBitCast::bit_cast<int32_t>(location.getSmallConstant()));
  case StackMapParser::LocationKind::ConstantIndex: {
    uint32_t index = location.getConstantIndex();
    return parser.getConstant(index).getValue();
  }
  default:
    ShouldNotReachHere();
  }
}

float StackMapUtil::getConstantFloat(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  return JeandleBitCast::bit_cast<float>(getConstantUint(parser, location));
}

double StackMapUtil::getConstantDouble(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  return JeandleBitCast::bit_cast<double>(getConstantUlong(parser, location));
}
