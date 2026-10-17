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

#ifndef SHARE_JEANDLE_COMPILED_CODE_HPP
#define SHARE_JEANDLE_COMPILED_CODE_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ADT/DenseSet.h"
#include "llvm/ADT/SmallVector.h"
#include "llvm/ExecutionEngine/JITLink/JITLink.h"
#include "llvm/IR/Statepoint.h"
#include "llvm/IR/Jeandle/Deoptimization.h"
#include "llvm/Object/ELFObjectFile.h"
#include "llvm/Object/StackMapParser.h"
#include "llvm/Support/DynamicLibrary.h"
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/IR/Jeandle/Deoptimization.h"

#include "jeandle/jeandleExceptionHandlerTable.hpp"
#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleParseContext.hpp"
#include "jeandle/jeandleReadELF.hpp"
#include "jeandle/jeandleResourceObj.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "asm/codeBuffer.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciField.hpp"
#include "ci/ciMethod.hpp"
#include "ci/ciObject.hpp"
#include "code/exceptionHandlerTable.hpp"
#include "runtime/sharedRuntime.hpp"

class JeandleReloc;

using llvm::jeandle::DeoptValueEncoding;

#ifdef ASSERT
// The print helper function for print_deopt_value should have same ASSERT macro with the usage.
// So we keep them in jdk side instead of a method in DeoptValueEncoding.
static inline const char* value_type_name(DeoptValueEncoding::DeoptValueType t) {
  switch (t) {
    case DeoptValueEncoding::LocalType: return "LocalType";
    case DeoptValueEncoding::StackType: return "StackType";
    case DeoptValueEncoding::ArgumentType: return "ArgumentType";
    case DeoptValueEncoding::MonitorType: return "MonitorType";
    case DeoptValueEncoding::ScalarValueType: return "ScalarValueType";
    case DeoptValueEncoding::OrigPcSlotType: return "OrigPcSlotType";
    default: return "Unknown";
  }
}

static inline void print_deopt_value(DeoptValueEncoding deopt_value) {
  ttyLocker ttyl;
  tty->print_cr("DeoptValueEncoding: index: %d value_type: %s, basic_type: %s",
                deopt_value.index(), value_type_name(deopt_value.valueType()), type2name(static_cast<BasicType>(deopt_value.basicType())));
}
#endif

class CallSiteInfo : public JeandleCompilationResourceObj {
 public:
  CallSiteInfo(JeandleCompiledCall::Type type,
               address target,
               bool is_method_handle_invoke = false,
               uint64_t statepoint_id = llvm::StatepointDirectives::DefaultStatepointID,
               Method *attached_method = nullptr) :
               _type(type),
               _target(target),
               _is_method_handle_invoke(is_method_handle_invoke),
               _attached_method(attached_method),
               _statepoint_id(statepoint_id) {
#ifdef ASSERT
    // We don't need to assign a unique statepoint id for each routine call site, only call type and target is used.
    bool use_default_statepoint_id = (statepoint_id == llvm::StatepointDirectives::DefaultStatepointID);
    bool is_routine_call = (type == JeandleCompiledCall::ROUTINE_CALL);
    bool is_external_call = (type == JeandleCompiledCall::EXTERNAL_CALL);
    assert(use_default_statepoint_id == (is_routine_call || is_external_call), "routine calls and external calls should use the default statepoint id");
#endif // ASSERT
  }


  JeandleCompiledCall::Type type() const { return _type; }
  void set_type(JeandleCompiledCall::Type type) { _type = type; }
  uint64_t statepoint_id() const { return _statepoint_id; }
  address target() const { return _target; }
  void set_target(address target) { _target = target; }
  bool is_method_handle_invoke() const { return _is_method_handle_invoke; }
  void set_is_method_handle_invoke(bool is_method_handle_invoke) {
    _is_method_handle_invoke = is_method_handle_invoke;
  }
  Method* attached_method() const { return _attached_method; }
  void set_attached_method(Method* method) { _attached_method = method; }

 private:
  JeandleCompiledCall::Type _type;
  address _target;
  bool _is_method_handle_invoke;
  Method* _attached_method;

  // Used to distinguish each call site in stackmaps.
  uint64_t _statepoint_id;
};

class JeandleStackMap : public JeandleCompilationResourceObj {
public:
  JeandleStackMap(int bci, ciMethod* method, OopMap* oop_map, GrowableArray<ScopeValue*>* locals, GrowableArray<ScopeValue*>* stack, GrowableArray<MonitorValue*>* monitors, bool reexecute, GrowableArray<ScopeValue*>* objects = nullptr) :
      _bci(bci), _method(method), _oop_map(oop_map), _locals(locals), _stack(stack), _monitors(monitors), _reexecute(reexecute), _objects(objects) {
  }

  int bci() const { return _bci; }
  ciMethod* method() const { return _method; }
  OopMap* oop_map() const { return _oop_map; }
  GrowableArray<ScopeValue*>* locals() const { return _locals; }
  GrowableArray<ScopeValue*>* stack() const { return _stack; }
  GrowableArray<MonitorValue*>* monitors() const { return _monitors; }
  bool reexecute() const { return _reexecute; }
  // PEA scalar-replaced (virtual) objects described by ScalarValueType
  // descriptors in this scope's "deopt" operand bundle. nullptr when the scope
  // carries no VO descriptor. Fed to DebugInformationRecorder::dump_object_pool
  // so Deoptimization::realloc_objects can reallocate each object at deopt.
  GrowableArray<ScopeValue*>* objects() const { return _objects; }

private:
  int _bci;
  ciMethod* _method;
  OopMap* _oop_map;
  GrowableArray<ScopeValue*>* _locals;
  GrowableArray<ScopeValue*>* _stack;
  GrowableArray<MonitorValue*>* _monitors;
  bool _reexecute;
  GrowableArray<ScopeValue*>* _objects;
};

using ObjectBuffer   = llvm::MemoryBuffer;
using LinkBlock      = llvm::jitlink::Block;
using LinkEdge       = llvm::jitlink::Edge;
using LinkKind       = llvm::jitlink::Edge::Kind;
using LinkSymbol     = llvm::jitlink::Symbol;
using StackMapParser = llvm::StackMapParser<ELFT::Endianness>;
using DynamicLibrary = llvm::sys::DynamicLibrary;

struct OopHandleInfo {
  jobject handle;
  ciObject* oop;
  std::string name;
};

class JeandleEntryBarrierStub;
class JeandleAssembler;

// A VORef descriptor field whose target VO has not yet been parsed (forward
// reference, or a mutual cycle a.f=b, b.g=a). Resolved after the whole VO
// section has been parsed: every descriptor's ObjectValue is created and
// registered in vo_map first (C2 debugInfo.cpp:68-94 model), then deferred
// fields are filled. Captures the owning ObjectValue + the index in its
// field_values() of the placeholder slot to overwrite.
struct JeandleDeferredVORefField {
  ObjectValue* owning_ov;
  int field_values_index;
  int voref_id;
};

// What one compilation will relocate, counted once and read by two reservations.
//
// The stubs section and the locs section are both sized from the same
// facts: the LinkGraph edges of the object and its LLVM stackmap records.
// Sizing them from two independent passes would build a second LinkGraph
// and scan the stackmap section twice. `collect_call_site_census()`
// walks them once, in exactly the order and with the same classifications that
// resolve_reloc_info() will use, so the counts below are the record inventory rather than
// an estimate of it.
struct JeandleCallSiteCensus {
  // Call sites, classified the way the resolver will resolve them.
  int _static_call_sites;        // STATIC_CALL, resolved through the static-call stub
  int _opt_virtual_call_sites;   // STATIC_CALL bound to the opt-virtual resolver stub
  int _virtual_call_sites;       // DYNAMIC_CALL, inline-cache site
  int _routine_call_sites;      // calls of HotSpot runtime routines
  int _external_call_sites;     // calls of symbols outside HotSpot
  int _stub_c_call_sites;       // STUB_C_CALL sites

  // Cross-section references, one relocation record each.
  int _section_word_insts;       // code refers to a constant: relocated in insts
  int _section_word_consts;      // a constant refers to code: relocated in consts
  int _oop_relocs;               // oop handled from code
  int _oop_addr_relocs;          // oop address stored in a constant

  // Refusal inputs. Anything that cannot be attributed to a known emitter makes the locs
  // plan fall back, because an unexplained record is an under-provisioned array.
  int _unclassified_edges;
  int _unmapped_records;

  bool _has_method_handle_invoke;
  int _shared_trampoline_targets;

  JeandleCallSiteCensus()
    : _static_call_sites(0), _opt_virtual_call_sites(0), _virtual_call_sites(0),
      _routine_call_sites(0), _external_call_sites(0),
      _stub_c_call_sites(0), _section_word_insts(0), _section_word_consts(0),
      _oop_relocs(0), _oop_addr_relocs(0), _unclassified_edges(0), _unmapped_records(0),
      _has_method_handle_invoke(false), _shared_trampoline_targets(-1) {}

  int total_records() const {
    return _static_call_sites + _opt_virtual_call_sites + _virtual_call_sites +
           _routine_call_sites + _external_call_sites + _section_word_insts +
           _section_word_consts + _oop_relocs + _oop_addr_relocs;
  }
};

// Every capacity decision for one CodeBuffer installation.
//
// This struct has six core fields, plus the
// fields needed to make each allocation decision *explainable* afterwards:
// if we allocated exactly, we must also be able to say which plan produced
// the size and what its padding was.
struct InstallLayoutCore {
  size_t insts_payload;      // ELF text size plus the runtime prolog reserve
  size_t consts_payload;     // exact size from ConstSectionPlan, or legacy 48 KiB
  size_t stubs_payload;      // unchanged conservative estimate
  size_t locs_payload;       // unchanged conservative estimate
  size_t code_size_input;    // first argument of CodeBuffer::initialize(...)
  bool   used_legacy_fallback;
  // Exact insts planning
  size_t planned_prolog;     // condition-sensitive prolog upper bound
  size_t planned_post_stubs; // out-of-line insts stubs bound
  // Exact stubs planning
  size_t planned_stubs;          // condition-sensitive stubs upper bound
  size_t planned_static_calls;   // physical static calls counted from stackmaps
  size_t planned_external_calls; // external calls counted from LinkGraph
  bool   has_mh_invoke;          // whether method handle invoke deopt stub is needed
  // Exact locs planning. One entry per CodeBuffer section: the relocation array
  // of each section is sized separately, because a record is written into the locs array
  // of the section its address belongs to.
  int    planned_locs_records[CodeBuffer::SECT_LIMIT];  // relocation records expected
  size_t planned_locs_elements[CodeBuffer::SECT_LIMIT]; // relocInfo slots expected
  size_t planned_locs_bytes[CodeBuffer::SECT_LIMIT];    // capacity actually requested
  const char* locs_plan_status;  // JeandleRelocPlan::Status name
  bool   used_exact_locs;        // locs capacity came from the record model
  // diagnostics
  size_t planned_padding;    // bytes of inter-section padding inside consts
  size_t planned_alignment;  // max_alignment requested from the planner
  size_t planned_entries;    // number of planned const sections
  const char* plan_status;   // ConstPlanStatus name

  InstallLayoutCore()
    : insts_payload(0), consts_payload(0), stubs_payload(0), locs_payload(0),
      code_size_input(0), used_legacy_fallback(true), planned_prolog(0), planned_post_stubs(0),
      planned_stubs(0), planned_static_calls(0), planned_external_calls(0), has_mh_invoke(false),
      locs_plan_status("NotComputed"), used_exact_locs(false),
      planned_padding(0), planned_alignment(0), planned_entries(0), plan_status("Unplanned") {
    for (int s = 0; s < (int)CodeBuffer::SECT_LIMIT; s++) {
      planned_locs_records[s] = 0;
      planned_locs_elements[s] = 0;
      planned_locs_bytes[s] = 0;
    }
  }
};

// Allocation constants for the legacy path. These match the legacy values so that the
// legacy fallback path is bit-for-bit the old behaviour, and so that a reader
// can diff the two strategies without archeology.
static const size_t LEGACY_CONSTS_SIZE   = 6144 * wordSize;  // 48 KiB on RISCV64
static const size_t LEGACY_STUBS_SIZE    = 160;
static const size_t INSTS_PROLOG_RESERVE = 2048;                 // "for prolog"
// The legacy relocation-array request. Note what it actually
// buys: 17 bytes divided by sizeof(relocInfo) is 8 slots, while CodeSection::relocate()
// refuses to write unless 15 slots are free after the current end - so the legacy path
// reallocated on the first record. Kept as the comparison column, not as a strategy.
static const size_t LEGACY_LOCS_SIZE     = sizeof(relocInfo) + relocInfo::length_limit;

class JeandleCompiledCode : public StackObj {
 public:
  // Defined in the .cpp: records CodeBuffer telemetry once per finalize() call,
  // on every exit path. Needs access to record_finalize_telemetry().
  friend class FinalizeTelemetry;

  // For compiled Java methods.
  JeandleCompiledCode(ciEnv* env,
                      ciMethod* method,
                      bool is_osr_entry) :
                      _obj(nullptr),
                      _elf(nullptr),
                      _link_graph_ssp(nullptr),
                      _link_graph(nullptr),
                      _section_cache(),
                      _section_cache_built(false),
                      _code_buffer("JeandleCompiledCode"),
                      _routine_call_sites(),
                      _non_routine_call_sites(),
                      _const_section_addrs_by_index(),
                      _const_sections(),
                      _const_plan(),
                      _used_const_layout_fallback(false),
                      _layout_fallback_count(0),
                      _used_exact_allocation(false),
                      _planned_padding(0),
                      _planned_alignment(0),
                      _planned_entries(0),
                      _planned_consts_size(-1),
                      _planned_stubs_size(-1),
                      _plan_status("Unplanned"),
                      _planned_locs_records{ -1, -1, -1 },
                      _planned_locs_kinds{ { 0 } },
                      _planned_locs_elements{ 0, 0, 0 },
                      _planned_locs_bytes{ -1, -1, -1 },
                      _locs_plan_status("NotComputed"),
                      _used_exact_locs(false),
                      _locs_planned(false),
                      _oop_handles(),
                      _oop_handle_ids(),
                      _oop_handle_info(),
                      _offsets(),
                      _exception_handler_table(),
                      _implicit_exception_table(),
                      _frame_size(-1),
                      _prolog_length(-1),
                      _env(env),
                      _method(method),
                      _routine_entry(nullptr),
                      _func_name(JeandleFuncSig::root_method_name(_method, is_osr_entry)),
                      _orig_pc_slot(nullptr),
                      _orig_pc_offset_in_bytes(-1),
                      _interpreter_frame_size_in_bytes(0),
                      _has_method_handle_invoke(false) {}

  // For compiled Jeandle runtime stubs.
  JeandleCompiledCode(ciEnv* env, const char* func_name) :
                      _obj(nullptr),
                      _elf(nullptr),
                      _link_graph_ssp(nullptr),
                      _link_graph(nullptr),
                      _section_cache(),
                      _section_cache_built(false),
                      _code_buffer("JeandleCompiledStub"),
                      _routine_call_sites(),
                      _non_routine_call_sites(),
                      _const_section_addrs_by_index(),
                      _const_sections(),
                      _const_plan(),
                      _used_const_layout_fallback(false),
                      _layout_fallback_count(0),
                      _used_exact_allocation(false),
                      _planned_padding(0),
                      _planned_alignment(0),
                      _planned_entries(0),
                      _planned_consts_size(-1),
                      _planned_stubs_size(-1),
                      _plan_status("Unplanned"),
                      _planned_locs_records{ -1, -1, -1 },
                      _planned_locs_kinds{ { 0 } },
                      _planned_locs_elements{ 0, 0, 0 },
                      _planned_locs_bytes{ -1, -1, -1 },
                      _locs_plan_status("NotComputed"),
                      _used_exact_locs(false),
                      _locs_planned(false),
                      _oop_handles(),
                      _oop_handle_ids(),
                      _oop_handle_info(),
                      _offsets(),
                      _exception_handler_table(),
                      _implicit_exception_table(),
                      _frame_size(-1),
                      _prolog_length(-1),
                      _env(env),
                      _method(nullptr),
                      _routine_entry(nullptr),
                      _func_name(func_name),
                      _orig_pc_slot(nullptr),
                      _orig_pc_offset_in_bytes(-1),
                      _interpreter_frame_size_in_bytes(0),
                      _has_method_handle_invoke(false) {}

  const ConstSectionPlan& const_plan() const { return _const_plan; }

  // The name of the function being compiled, as it appears in the trace output.
  const std::string& func_name() const { return _func_name; }

  void install_obj(std::unique_ptr<ObjectBuffer> obj);

  void push_non_routine_call_site(CallSiteInfo* call_site) { _non_routine_call_sites.push_back(call_site); }
  uint64_t next_statepoint_id() { return _non_routine_call_sites.size(); }
  int64_t duplicate_non_routine_call_site(uint64_t old_statepoint_id) {
    assert(old_statepoint_id < _non_routine_call_sites.size(), "old statepoint id must exist");
    CallSiteInfo* old_call_site = _non_routine_call_sites[old_statepoint_id];
    assert(old_call_site != nullptr, "non-routine call site must exist");
    assert(old_call_site->statepoint_id() == old_statepoint_id,
           "statepoint id must match its call-site index");

    // LLVM may duplicate an inlined call site. Keep the stackmap contract that
    // a non-routine statepoint id directly indexes _non_routine_call_sites.
    uint64_t new_statepoint_id = next_statepoint_id();
    push_non_routine_call_site(new CallSiteInfo(old_call_site->type(),
                                                old_call_site->target(),
                                                old_call_site->is_method_handle_invoke(),
                                                new_statepoint_id,
                                                old_call_site->attached_method()));
    return static_cast<int64_t>(new_statepoint_id);
  }
  llvm::SmallVector<CallSiteInfo*>& non_routine_call_sites() { return _non_routine_call_sites; }

  int find_or_insert_oop(ciObject* oop);
  ciObject* oop_at(int oop_id);
  std::string oop_handle_name(int oop_id);
  // StringMap entries are individually heap-allocated and never relocated on
  // insertion, so their keys stay valid for the life of this JeandleCompiledCode
  // (unlike the std::strings in _oop_handle_info's SmallVector). Exposed so that
  // the GetOopHandleName callback can return a stable name pointer.
  llvm::StringMap<jobject>& oop_handles() { return _oop_handles; }

  const char* object_start() const { return _obj->getBufferStart(); }
  size_t object_size() const { return _obj->getBufferSize(); }

  CodeBuffer* code_buffer() { return &_code_buffer; }

  CodeOffsets* offsets() { return &_offsets; }

  JeandleExceptionHandlerTable* exception_handler_table() { return &_exception_handler_table; }

  ImplicitExceptionTable* implicit_exception_table() { return &_implicit_exception_table; }

  int frame_size() const { return _frame_size; }
  int orig_pc_offset_in_bytes() const { return _orig_pc_offset_in_bytes; }
  void set_orig_pc_slot(llvm::Value* slot) { _orig_pc_slot = slot; }
  llvm::Value* orig_pc_slot() const { return _orig_pc_slot; }
  void set_real_orig_pc_offset_in_bytes(int offset);
  void set_has_method_handle_invoke(bool z) { _has_method_handle_invoke = z; }

  address routine_entry() const { return _routine_entry; }
  void set_routine_entry(address entry) { _routine_entry = entry; }

  // Generate relocations, stubs and debug information.
  void finalize();

  bool needs_clinit_barrier(ciField* ik,         ciMethod* accessing_method);
  bool needs_clinit_barrier(ciMethod* ik,        ciMethod* accessing_method);
  bool needs_clinit_barrier(ciInstanceKlass* ik, ciMethod* accessing_method);
  bool needs_clinit_barrier_on_entry();
  bool needs_nmethod_entry_barrier();
  void update_interpreter_frame_size_in_bytes(int frame_size) { _interpreter_frame_size_in_bytes = MAX2(frame_size, _interpreter_frame_size_in_bytes); }
  int interpreter_frame_size_in_bytes() { return _interpreter_frame_size_in_bytes; }
  void record_stable_array(int oop_id, int dimension);
  int stable_array_dimension(int oop_id) const;

 private:
  std::unique_ptr<ObjectBuffer> _obj; // Compiled instructions.
  std::unique_ptr<ELFObject> _elf;
  std::shared_ptr<llvm::orc::SymbolStringPool> _link_graph_ssp;
  std::unique_ptr<llvm::jitlink::LinkGraph> _link_graph;
  llvm::SmallVector<SectionInfo, 32> _section_cache;
  bool _section_cache_built;
  llvm::jitlink::LinkGraph* shared_link_graph();
  bool find_section_cached(llvm::StringRef name, SectionInfo& section_info);

  CodeBuffer _code_buffer; // Relocations and stubs.

  // Call sites in our compiled code:
  // Note that the main difference between routine calls and non-routine calls is that routine calls are found
  // from relocation of compiled objects directly, and non-routine calls are found from stackmaps and then
  // matched with the compile-time generated statepoint id.
  llvm::DenseMap<int, CallSiteInfo*> _routine_call_sites; // Contains all routine call sites, constructed from
                                                          // relocations of compiled objects in resolve_reloc_info.
  llvm::SmallVector<CallSiteInfo*> _non_routine_call_sites; // Contains all other call sites,
                                                            // constructed during LLVM IR generation.

  llvm::DenseMap<uint32_t, address> _const_section_addrs_by_index;
  llvm::StringMap<address> _const_sections;
  ConstSectionPlan _const_plan;
  bool _used_const_layout_fallback;
  // Telemetry for one finalize() invocation.
  int _layout_fallback_count;   // unplanned const sections discovered at runtime
  bool _used_exact_allocation;  // consts capacity came from the plan
  size_t _planned_padding;
  size_t _planned_alignment;
  size_t _planned_entries;
  int64_t _planned_consts_size; // -1 when the planner was not used
  int64_t _planned_stubs_size;  // -1 when the planner was not used
  const char* _plan_status;
  // Locs telemetry for one finalize() invocation. `_planned_locs_bytes[n] < 0`
  // means the record model did not size that section and HotSpot's heuristic was used.
  int    _planned_locs_records[CodeBuffer::SECT_LIMIT];
  // Per-kind detail of the same plan, kept so that a planned/actual mismatch can name the
  // relocInfo type it missed instead of only the size of the gap.
  int    _planned_locs_kinds[CodeBuffer::SECT_LIMIT][relocInfo::type_mask + 1];
  size_t _planned_locs_elements[CodeBuffer::SECT_LIMIT];
  int64_t _planned_locs_bytes[CodeBuffer::SECT_LIMIT];
  const char* _locs_plan_status;
  bool _used_exact_locs;
  // True when a relocation plan was computed at all, whether or not it was applied: the
  // A/B arm applies nothing but still reports what the model would have asked for, which
  // is what keeps the two arms doing the same work and printing the same lines.
  bool _locs_planned;
  // Oop handles maintainer:
  llvm::StringMap<jobject> _oop_handles;                // name -> jobject
  llvm::DenseMap<jobject, int> _oop_handle_ids;         // jobject -> id
  llvm::SmallVector<OopHandleInfo> _oop_handle_info;    // index is oop_id

  CodeOffsets _offsets;
  JeandleExceptionHandlerTable _exception_handler_table;
  ImplicitExceptionTable _implicit_exception_table;
  int _frame_size;
  int _prolog_length;
  ciEnv* _env;
  ciMethod* _method;
  address _routine_entry;
  std::string _func_name;
  llvm::Value* _orig_pc_slot;
  int _orig_pc_offset_in_bytes;
  int _interpreter_frame_size_in_bytes;
  bool _has_method_handle_invoke;
  JeandleEntryBarrierStub* _entry_barrier_stub = nullptr;
  llvm::DenseMap<int, int> _stable_array_dimensions;

  void setup_frame_size();

  void resolve_reloc_info(JeandleAssembler& assembler);
  bool pd_resolve_reloc(JeandleAssembler& assembler,
                        llvm::SmallVector<JeandleReloc*>& relocs,
                        llvm::jitlink::LinkGraph* link_graph);

  // Architecture-specific half of the relocation census. Returns true when this
  // architecture classifies its edges as a pair (RISC-V's HI20/LO12), which then has to be
  // walked next to the resolver that does the same. Returning false means "use the generic
  // per-edge walk", exactly as resolve_reloc_info() uses pd_resolve_reloc().
  bool pd_collect_call_site_census(llvm::jitlink::LinkGraph* link_graph,
                                   JeandleCallSiteCensus& census);

  // Decide every allocation-relevant quantity before any section size
  // has been requested from the CodeBuffer. See the .cpp for why this has to
  // happen before initialize().
  void decide_install_layout(uint64_t elf_text_size, uint64_t func_align, InstallLayoutCore& layout);

  // Exact upper bound estimators for insts section
  size_t prolog_upper_bound(uint64_t func_align);
  size_t post_insts_stubs_upper_bound();

  // Exact upper bound estimator for stubs section
  size_t stubs_upper_bound(const JeandleCallSiteCensus& census, size_t& static_calls,
                           size_t& external_calls, bool& has_mh);

  // One pass over the LinkGraph edges and the LLVM stackmap records that answers
  // both reservations. Fills `census` exactly the way resolve_reloc_info() will later
  // resolve the same edges and records; returns false when the object cannot be inspected
  // at all, which puts the whole layout decision on the legacy path.
  bool collect_call_site_census(JeandleCallSiteCensus& census);

  // Exact upper bound estimator for the locs section of each CodeBuffer section.
  void plan_locs_capacity(const JeandleCallSiteCensus& census,
                          InstallLayoutCore& layout,
                          size_t insts_size, size_t consts_size, size_t stubs_size);

  // Records allocate/expand/finalize telemetry for this compilation.
  // `layout` is the decision that produced the buffer being reported, so that the planned
  // and the actual columns of every section are recorded from one source.
  void record_finalize_telemetry(const InstallLayoutCore& layout);

  // Centralized emission of planned const sections.
  void emit_planned_const_sections(JeandleAssembler& assembler);

  // Lookup address of const section in CodeBuffer (index is canonical primary identity).
  address lookup_const_section(uint32_t section_index, JeandleAssembler& assembler);
  address lookup_const_section(llvm::StringRef name, JeandleAssembler& assembler);
  address lookup_const_section_fallback(llvm::StringRef name, JeandleAssembler& assembler);
  address resolve_const_reloc_site(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assembler);
  address resolve_const_edge(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assembler);

  int parse_stackmap_prologue(StackMapParser::record_iterator& record,
                              StackMapParser::RecordAccessor::location_iterator& location);
  JeandleStackMap* parse_stackmap(StackMapParser& stackmaps,
                                  StackMapParser::record_iterator& record,
                                  StackMapParser::RecordAccessor::location_iterator& location,
                                  int& num_deopts,
                                  const JeandleParseContext& parse_context,
                                  ciMethod*& next_inlinee,
                                  llvm::DenseMap<int, ObjectValue*>& vo_map,
                                  GrowableArray<JeandleDeferredVORefField>& deferred_voref_fields);
  LocationValue* new_location_value(const StackMapParser::LocationAccessor& location, Location::Type type);
  void fill_one_scope_value(const StackMapParser& stackmaps, const DeoptValueEncoding& encode,
                            const StackMapParser::LocationAccessor& location, GrowableArray<ScopeValue*>* array);
  void fill_one_monitor_value(const StackMapParser& stackmaps, const DeoptValueEncoding& encode, const StackMapParser::LocationAccessor& object,
                              const StackMapParser::LocationAccessor& lock, GrowableArray<MonitorValue*>* array);

  void build_exception_handler_table();
  bool pd_build_exception_handler_table();
  void build_implicit_exception_table();

  int frame_size_in_slots();
};


class StackMapUtil : public AllStatic {
public:
  static bool is_constant(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Constant
        || location.getKind() == StackMapParser::LocationKind::ConstantIndex;
  }

  static bool is_stack(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Indirect
        || location.getKind() == StackMapParser::LocationKind::Direct;
  }

  static bool is_register(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Register;
  }

  static int32_t stack_offset(const StackMapParser::LocationAccessor& location) {
    if (is_stack(location)) {
      return location.getOffset();
    } else {
      ShouldNotReachHere();
    }
  }

  static uint32_t getConstantUint(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static uint64_t getConstantUlong(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static float    getConstantFloat(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static double   getConstantDouble(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
};

#endif // SHARE_JEANDLE_COMPILED_CODE_HPP
