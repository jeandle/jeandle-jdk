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
#include "llvm/BinaryFormat/Dwarf.h"
#include "llvm/Object/FaultMapParser.h"
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
#include "interpreter/interpreter.hpp"
#include "logging/log.hpp"
#include "runtime/os.hpp"

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

 protected:
  // Need fixing up with the prolog length.
  int _offset;
#ifdef ASSERT
  bool _fixed_up = false;
#endif
};

class JeandleSectionWordReloc : public JeandleReloc {
 public:
  JeandleSectionWordReloc(int reloc_offset, LinkEdge& edge, address target, int reloc_section) :
    JeandleReloc(reloc_offset),
    _kind(edge.getKind()),
    _addend(edge.getAddend()),
    _target(target),
    _reloc_section(reloc_section) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    assembler.emit_section_word_reloc(offset(), _kind, _addend, _target, _reloc_section);
  }

  virtual void fixup_offset(int prolog_length) {
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

 private:
  LinkKind _kind;
  int64_t _addend;
  address _target;
  int _reloc_section;
};

class JeandleCallReloc : public JeandleReloc {
 public:
  JeandleCallReloc(int inst_end_offset, ciEnv* env, ciMethod* method, JeandleOopMap* oop_map, CallSiteInfo* call) :
    JeandleReloc(inst_end_offset - JeandleCompiledCall::call_site_size(call->type()) /* beginning of a call instruction */),
    _env(env), _method(method), _oop_map(oop_map), _call(call) {}

  void emit_reloc(JeandleAssembler& assembler) override {
    // Each call reloc has an oopmap, except for EXTERNAL_CALL.
    assert((_call->type() != JeandleCompiledCall::EXTERNAL_CALL && _oop_map != nullptr) ||
           (_call->type() == JeandleCompiledCall::EXTERNAL_CALL && _oop_map == nullptr),
           "unmatched call type and oopmap");
    if (_oop_map != nullptr) {
      process_oop_map();
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
        assert(_oop_map == nullptr, "no oopmap in external call");
        assembler.patch_external_call_site(offset(), _call);
        RETURN_VOID_ON_JEANDLE_ERROR();
        break;
      default:
        ShouldNotReachHere();
        break;
    }
  }

 private:
  ciEnv* _env;
  ciMethod* _method;
  JeandleOopMap* _oop_map;
  CallSiteInfo* _call;
  int inst_end_offset() { return offset() + JeandleCompiledCall::call_site_size(_call->type()); }

  void process_oop_map() {
    assert(_oop_map != nullptr, "oopmap must be initialized");
    assert(inst_end_offset() >= 0, "pc offset must be initialized");
    assert(_fixed_up, "offset must be fixed up");

    DebugInformationRecorder* recorder = _env->debug_info();
    recorder->add_safepoint(inst_end_offset(), _oop_map->oop_map());

    DebugToken *locvals = recorder->create_scope_values(_oop_map->locals());
    DebugToken *expvals = recorder->create_scope_values(_oop_map->stack());
    DebugToken *monvals = recorder->create_monitor_values(_oop_map->monitors());

    recorder->describe_scope(inst_end_offset(),
                             methodHandle(),
                             _method,
                             _call->bci(),
                             _oop_map->reexecute(),
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

static bool need_clinit_barrier_on_entry(ciMethod* method) {
  return VM_Version::supports_fast_class_init_checks() && method->needs_clinit_barrier();
}

void JeandleCompiledCode::install_obj(std::unique_ptr<ObjectBuffer> obj) {
  _obj = std::move(obj);
  llvm::MemoryBufferRef memory_buffer = _obj->getMemBufferRef();
  auto elf_or_error = llvm::object::ObjectFile::createELFObjectFile(memory_buffer);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(elf_or_error, "bad ELF file");

  _elf = llvm::dyn_cast<ELFObject>(*elf_or_error);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(_elf, "bad ELF file");
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

  masm->align(assembler.interior_entry_alignment());

  _offsets.set_value(CodeOffsets::Verified_Entry, masm->offset());
  assembler.emit_verified_entry();

  if (_method && need_clinit_barrier_on_entry(_method)) {
    Klass* klass = (Klass*)_method->holder()->constant_encoding();
    assembler.emit_clinit_barrier_on_entry(klass);
  }

  int frame_size_in_bytes = _frame_size * BytesPerWord;
  bool is_method_compilation = _method != nullptr;
  bool has_java_calls = !_non_routine_call_sites.empty();
  if (need_stack_overflow_check(is_method_compilation, has_java_calls, frame_size_in_bytes)) {
    // TODO: redesign interpreter frame sizing that comes from interpreter states recorded
    // in stackmaps, which are only captured for uncommon traps (deoptimization paths).
    int bang_size_in_bytes = frame_size_in_bytes + os::extra_bang_size_in_bytes();
    masm->generate_stack_overflow_check(bang_size_in_bytes);
  }

  assert(align > 1, "invalid alignment");
  masm->align(static_cast<int>(align));

  _prolog_length = masm->offset();

  assembler.emit_insts(((address) _obj->getBufferStart()) + offset, code_size);

  resolve_reloc_info(assembler);
  RETURN_VOID_ON_JEANDLE_ERROR();

  // generate shared trampoline stubs
  bool success = _code_buffer.finalize_stubs();
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(success, "shared stub overflow");

  if (_method) {
    // For Java method compilation.
    build_exception_handler_table();
    _offsets.set_value(CodeOffsets::Exceptions, assembler.emit_exception_handler());
    RETURN_VOID_ON_JEANDLE_ERROR();
  }

  build_implicit_exception_table();

  // TODO: generate code for deopt handler.
  _offsets.set_value(CodeOffsets::Deopt, masm->offset());
}

void JeandleCompiledCode::resolve_reloc_info(JeandleAssembler& assembler) {
  llvm::SmallVector<JeandleReloc*> relocs;

  // Step 1: Resolve LinkGraph.
  auto ssp = std::make_shared<llvm::orc::SymbolStringPool>();

  auto graph_or_err = llvm::jitlink::createLinkGraphFromObject(_elf->getMemoryBufferRef(), ssp);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(graph_or_err, "failed to create LinkGraph");

  auto link_graph = std::move(*graph_or_err);

  for (auto *block : link_graph->blocks()) {
    // Resolve relocations in the compiled code and constant pool.
    if (block->getSection().getName().compare(".text") != 0 &&
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

        // TODO: Set the right bci.
        // JeandleCallReloc for a routine call site will be created during stackmaps resolving because an oopmap is required.
        _routine_call_sites[inst_end_offset] = new CallSiteInfo(JeandleCompiledCall::ROUTINE_CALL,
                                                                target_addr,
                                                                -1/* bci */,
                                                                target_addr == JeandleRuntimeRoutine::get_routine_entry("uncommon_trap")/* has_deopt_operands */);
      } else if (JeandleAssembler::is_external_call_reloc(target, edge.getKind())) {
        // External call relocations.
        address target_addr = (address)DynamicLibrary::SearchForAddressOfSymbol(target_name.str().c_str());
        JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(target_addr, "failed to find external symbol");

        int inst_end_offset = JeandleAssembler::fixup_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

        // TODO: Set the right bci.
        CallSiteInfo* call_info = new CallSiteInfo(JeandleCompiledCall::EXTERNAL_CALL,
                                                   target_addr,
                                                   -1/* bci */);
        // LLVM doesn't rewrite intrinsic calls to statepoints, so we don't need oopmaps for external calls.
        relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, nullptr /* no oopmap */, call_info));
      } else if (JeandleAssembler::is_const_reloc(target, edge.getKind())) {
        // Const relocations.
        address target_addr;
        int reloc_offset;
        int reloc_section;
        if (target.getSection().getName().starts_with(".rodata")) {
          assert(block->getSection().getName().compare(".text") == 0, "invalid reloc section");
          target_addr = resolve_const_edge(*block, edge, assembler);
          RETURN_VOID_ON_JEANDLE_ERROR();
          reloc_offset = static_cast<int>(block->getAddress().getValue() + edge.getOffset());
          reloc_section = CodeBuffer::SECT_INSTS;
        } else {
          assert(target.getSection().getName().compare(".text") == 0, "invalid target section");
          assert(block->getSection().getName().starts_with(".rodata"), "invalid reloc section");
          target_addr = _code_buffer.insts()->start();
          address reloc_base = lookup_const_section(block->getSection().getName(), assembler);
          RETURN_VOID_ON_JEANDLE_ERROR();
          reloc_offset = reloc_base + edge.getOffset() - _code_buffer.consts()->start();
          reloc_section = CodeBuffer::SECT_CONSTS;
        }
        relocs.push_back(new JeandleSectionWordReloc(reloc_offset, edge, target_addr, reloc_section));
      } else if (JeandleAssembler::is_oop_reloc(target, edge.getKind())) {
        // Oop relocations.
        assert((target_name).starts_with("oop_handle"), "invalid oop relocation name");
        relocs.push_back(new JeandleOopReloc(static_cast<int>(block->getAddress().getValue() + edge.getOffset()), _oop_handles[(target_name)]));
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
        relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, build_oop_map(stackmaps, record, call_info), call_info));
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

LocationValue* JeandleCompiledCode::new_loc_value(const StackMapParser::LocationAccessor& location, Location::Type type) {
  return StackMapUtil::is_stack(location)
    ? new LocationValue(Location::new_stk_loc(type, StackMapUtil::stack_offset(location)))
    : new LocationValue(Location::new_reg_loc(type, resolve_vmreg(location, location.getKind())));
}

void JeandleCompiledCode::fill_one_scope_value(const StackMapParser& stackmaps,
                                               const DeoptValueEncoding& encode,
                                               const StackMapParser::LocationAccessor& location,
                                               GrowableArray<ScopeValue*>* array,
                                               int& index) {
  assert(array != nullptr, "sanity");
  bool is_constant = StackMapUtil::is_constant(location);
  switch (encode._basic_type) {
  case T_INT: {
    if (is_constant) {
      array->at_put_grow(index++, new ConstantIntValue(StackMapUtil::getConstantUint(stackmaps, location)));
    } else {
      array->at_put_grow(index++, new_loc_value(location, Location::normal));
    }
    break;
  }
  case T_LONG: {
    // 2 stack slots for long type
    array->at_put_grow(index++, new ConstantIntValue((jint)0));
    if (is_constant) {
      array->at_put_grow(index++, new ConstantLongValue(StackMapUtil::getConstantUlong(stackmaps, location)));
    } else {
      array->at_put_grow(index++, new_loc_value(location, Location::lng));
    }
    break;
  }
  case T_FLOAT: {
    if (is_constant) {
      array->at_put_grow(index++, new ConstantIntValue(jint_cast(StackMapUtil::getConstantFloat(stackmaps, location))));
    } else {
      array->at_put_grow(index++, new_loc_value(location, Location::normal));
    }
    break;
  }
  case T_DOUBLE: {
    // 2 stack slots for double type
    array->at_put_grow(index++, new ConstantIntValue((jint)0));
    if (is_constant) {
      array->at_put_grow(index++, new ConstantDoubleValue(StackMapUtil::getConstantDouble(stackmaps, location)));
    } else {
      array->at_put_grow(index++, new_loc_value(location, Location::dbl));
    }
    break;
  }
  case T_OBJECT: {
    if (is_constant) {
      uint64_t v = StackMapUtil::getConstantUlong(stackmaps, location);
      if (v == 0L) {
        array->at_put_grow(index++, new ConstantOopWriteValue(nullptr));
      } else {
        /* No constant oop is embedding into code */
        ShouldNotReachHere();
      }
    } else {
      array->at_put_grow(index++, new_loc_value(location, Location::oop));
    }
    break;
  }
  case T_ILLEGAL: {
    uint32_t val = StackMapUtil::getConstantUint(stackmaps, location);
    assert(val == 0, "must be zero for T_ILLEGAL");
    // put an illegal value
    array->at_put_grow(index++, new LocationValue(Location()));
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
  bool is_constant = StackMapUtil::is_constant(object);
  assert(encode._basic_type == T_OBJECT, "should be");
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
    locked_object = new_loc_value(object, Location::oop);
  }
  Location basic_lock = Location::new_stk_loc(Location::normal, StackMapUtil::stack_offset(lock));
  array->append(new MonitorValue(locked_object, basic_lock, false /* FIXME */));
}

JeandleOopMap* JeandleCompiledCode::build_oop_map(StackMapParser& stackmaps, StackMapParser::record_iterator& record, CallSiteInfo* call_info) {
  assert(_frame_size > 0, "frame size must be greater than zero");
  OopMap* oop_map = new OopMap(frame_size_in_slots(), 0);

  // It comes from observation of llvm stackmap, it may be changed in future.
  // The first 2 constants are ignored, the third constant is the number of deopt operands
  auto location = record->location_begin();
  auto first = *(location++);
  assert(location != record->location_end(), "must be in range");
  auto second = *(location++);
  assert(location != record->location_end(), "must be in range");
  assert(first.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");
  assert(second.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");
  int num_deopts = 0;
  bool reexecute = false;
  if (call_info->has_deopt_operands()) {
    assert(this->_method != nullptr, "must be method compilation");
    auto third = *(location++);
    assert(third.getKind() == StackMapParser::LocationKind::Constant, "unexpected kind");
    num_deopts = third.getSmallConstant();
    assert(num_deopts >= 0, "negative number");

    // bci goes first in deopt operands
    int bci = (location++)->getSmallConstant();
    num_deopts--;
    call_info->set_bci(bci);

    if (bci != InvocationEntryBci) {
      Bytecodes::Code code = _method->java_code_at_bci(bci);
      reexecute = Interpreter::bytecode_should_reexecute(code); /* TODO: special case of multianewarray, please check GraphKit::should_reexecute_implied_by_bytecode */
    }
  }

  // build scope values
  GrowableArray<ScopeValue*>* locals = num_deopts > 0 ? new GrowableArray<ScopeValue*>(_method->max_locals()) : nullptr;
  GrowableArray<ScopeValue*>* stack  = num_deopts > 0 ? new GrowableArray<ScopeValue*>(_method->max_stack()) : nullptr;
  GrowableArray<MonitorValue*>* monitors = num_deopts > 0 ? new GrowableArray<MonitorValue*>() : nullptr;
  int local_index = 0;
  int stack_index = 0;
  while (num_deopts > 0) {
    // local and stack deopt arguments are passed as a pair: <encode, value>
    // monitor deopt arguments are passed as a tuple: <encode, object, lock>
    assert(location != record->location_end(), "must be in range");
    auto encode_location = *(location++);
    // check the encoding type first
    uint64_t encode = StackMapUtil::getConstantUlong(stackmaps, encode_location);
    DeoptValueEncoding enc = DeoptValueEncoding::decode(encode);
    int type = enc._value_type;
    assert(type == DeoptValueEncoding::LocalType || type == DeoptValueEncoding::StackType ||
           type == DeoptValueEncoding::MonitorType, "Unsupported type");

#ifdef ASSERT
    if (log_is_enabled(Trace, jeandle)) {
      enc.print();
    }
#endif

    if (type == DeoptValueEncoding::LocalType || type == DeoptValueEncoding::StackType) {
      // fill scope value
      assert(location != record->location_end(), "must be in range");
      auto value_location = *(location++);
      bool is_local = type == DeoptValueEncoding::LocalType;
      fill_one_scope_value(stackmaps, enc, value_location,
                           is_local ? locals : stack,
                           is_local ? local_index : stack_index);
      num_deopts -= 2;
    } else if (type == DeoptValueEncoding::MonitorType) {
      // fill monitor value
      assert(location != record->location_end(), "must be in range");
      auto obj_location = *(location++);
      assert(location != record->location_end(), "must be in range");
      auto lock_location = *(location++);
      fill_one_monitor_value(stackmaps, enc, obj_location, lock_location, monitors);
      num_deopts -= 3;
    } else {
      Unimplemented();
    }
  }

  // build oop map
  for (; location != record->location_end(); location++) {
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

    assert(base_kind != StackMapParser::LocationKind::Direct, "invalid location kind");

    VMReg reg_base = resolve_vmreg(base_location, base_kind);
    VMReg reg_derived = resolve_vmreg(derived_location, derived_kind);

    if(reg_base == reg_derived) {
      // No derived pointer.
      oop_map->set_oop(reg_base);
    } else {
      // Derived pointer.
      oop_map->set_derived_oop(reg_derived, reg_base);
    }
  }
  return new JeandleOopMap(oop_map, locals, stack, monitors, reexecute);
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
    return (uint64_t)location.getSmallConstant();
  case StackMapParser::LocationKind::ConstantIndex: {
    uint32_t index = location.getConstantIndex();
    return parser.getConstant(index).getValue();
  }
  default:
    ShouldNotReachHere();
  }
}

float StackMapUtil::getConstantFloat(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  union {
    uint32_t u;
    float f;
  } uf;
  uf.u = getConstantUint(parser, location);
  return uf.f;
}

double StackMapUtil::getConstantDouble(const StackMapParser& parser, const StackMapParser::LocationAccessor& location) {
  union {
    uint64_t u;
    double d;
  } ud;
  ud.u = getConstantUlong(parser, location);
  return ud.d;
}
