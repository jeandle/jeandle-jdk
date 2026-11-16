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
 */

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "jeandle/jeandleRelocPlan.hpp"
#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandle_globals.hpp"

#include "utilities/ostream.hpp"

#include <algorithm>

// ---------------------------------------------------------------------------
// Encoding rules: what one relocation record costs
// ---------------------------------------------------------------------------
//
// The unit of a locs array is a relocInfo, which is exactly one `unsigned short`
// (relocInfo::_value) - sizeof(relocInfo) == sizeof(short), asserted by
// relocInfo::finish_prefix(). A record is written by CodeSection::relocate() as:
//
//   [filler]... [tag|type,format,offset] [prefix] [data...]
//
// where the prefix and the data only exist if Relocation::pack_data_to() writes any, and
// relocInfo::finish_prefix() then either
//   - removes the prefix again (no data),
//   - folds a single 10-bit value into the prefix word itself, or
//   - keeps the prefix word followed by `data` halfwords.
//
// Dropping the folding case is what makes this an upper bound: a record with N data
// halfwords costs at most 1 + 1 + N slots, and never less than 1.
int JeandleRelocPlan::record_elements(int data_halfwords) {
  if (data_halfwords <= 0) {
    return 1;
  }
  return 1 /* tag word */ + 1 /* prefix word */ + data_halfwords;
}

// The payload each type writes, from its pack_data_to() implementation.
//   pack_1_int_to(x)  -> at most one jint  -> 2 halfwords
//   pack_2_ints_to(x) -> at most two jints -> 4 halfwords
// Anything else (no override at all) writes nothing.
int JeandleRelocPlan::record_data_halfwords(relocInfo::relocType type, bool* known) {
  switch (type) {
    // No pack_data_to() override: the tag word is the entire record.
    case relocInfo::none:
    case relocInfo::runtime_call_type:
    case relocInfo::poll_type:
    case relocInfo::poll_return_type:
    case relocInfo::barrier_type:
    case relocInfo::entry_guard_type:
    case relocInfo::post_call_nop_type:
      return 0;

    // z/Architecture only (records the constant-pool offset of a runtime call). Jeandle
    // never emits one, but sizing it correctly keeps the table honest.
    case relocInfo::runtime_call_w_cp_type:
      return 2;

    // pack_1_int_to(): a scaled offset, a section-plus-offset word, or a method index.
    case relocInfo::static_call_type:
    case relocInfo::opt_virtual_call_type:
    case relocInfo::static_stub_type:
    case relocInfo::trampoline_stub_type:
    case relocInfo::internal_word_type:
    case relocInfo::section_word_type:
      return 2;

    // pack_2_ints_to(): two integers, each up to a jint.
    case relocInfo::oop_type:
    case relocInfo::metadata_type:
    case relocInfo::virtual_call_type:
#ifdef _LP64
    case relocInfo::external_word_type:  // an absolute address split into lo and hi
      return 4;
#else
    case relocInfo::external_word_type:
      return 2;
#endif

    // Jeandle's own types. Their encoding lives in relocInfo_<cpu>.cpp, so the count is
    // asked from the architecture, next to the emitters themselves.
    case relocInfo::jeandle_section_word_type:
    case relocInfo::jeandle_oop_type:
    case relocInfo::jeandle_oop_addr_type:
      return JeandleAssembler::reloc_record_data_halfwords(type);

    // data_prefix_tag is not addressable through relocate() (the relocInfo constructor
    // asserts against it), and anything added upstream must be sized deliberately.
    default:
      *known = false;
      return 0;
  }
}

// Exactly the loop in CodeSection::relocate(): while the distance does not fit in one
// record's offset field, spend a filler that advances by the largest encodable distance.
int JeandleRelocPlan::gap_fillers(size_t gap) {
  const size_t limit = (size_t)relocInfo::offset_limit();
  const size_t step = limit - (size_t)relocInfo::addr_unit();
  assert(step > 0, "offset_limit must exceed offset_unit");
  if (gap < limit) {
    return 0;
  }
  return (int)((gap - limit) / step + 1);
}

size_t JeandleRelocPlan::gap_filler_bound(size_t section_size) {
  const size_t limit = (size_t)relocInfo::offset_limit();
  const size_t step = limit - (size_t)relocInfo::addr_unit();
  // gap_fillers(g) <= g / step for every gap g, and the gaps of one section are disjoint
  // and start at the section base, so they sum to at most the section size.
  return section_size / step;
}

size_t JeandleRelocPlan::probe_headroom() {
  return (size_t)relocInfo::length_limit + 1;
}

size_t JeandleRelocPlan::minimum_capacity_elements() {
  // CodeSection::initialize_locs() raises any request to at least this many slots.
  return 4;
}

// ---------------------------------------------------------------------------
// Inventory: which emitter produces which record
// ---------------------------------------------------------------------------

void JeandleRelocPlan::refuse(Status status, int kind) {
  // The first refusal is the informative one; later ones must not overwrite it.
  if (_status == Status::Exact || _status == Status::NotComputed) {
    _status = status;
    _failed_kind = kind;
  }
}

void JeandleRelocPlan::expect(int section, relocInfo::relocType type, int count) {
  if (count <= 0) {
    return;
  }
  assert(section >= (int)CodeBuffer::SECT_FIRST && section < (int)CodeBuffer::SECT_LIMIT,
         "must be a CodeBuffer section");
  bool known = true;
  int data = record_data_halfwords(type, &known);
  if (!known) {
    refuse(Status::UnknownRecordType, (int)type);
    return;
  }
  const int elements = record_elements(data);
  const int slot = (int)type;
  assert(slot >= 0 && slot <= (int)relocInfo::type_mask, "relocInfo type out of table");
  // Guard against a wild count turning into an enormous request: refuse instead.
  if (elements > 0 && count > INT_MAX / elements) {
    refuse(Status::Overflow, slot);
    return;
  }
  const int previous = _records[section][slot];
  if (count > INT_MAX - previous) {
    refuse(Status::Overflow, slot);
    return;
  }
  _records[section][slot] = previous + count;
  _payload_elements[section] += (size_t)count * (size_t)elements;
}

void JeandleRelocPlan::compute(const Sources& sources,
                               size_t insts_size, size_t consts_size, size_t stubs_size) {
  _status = Status::Exact;
  _failed_kind = -1;

  if (!sources._method_compilation) {
    // Stub and runtime-routine compilations are assembled from HotSpot helper code whose
    // relocations Jeandle does not control; they are allocated conservatively
    // and they are a handful per VM, not thousands per run.
    refuse(Status::NonMethodCompilation, -1);
  }

  if (_status == Status::Exact) {
    // --- call sites, in the insts section --------------------------------──
    // One record per call site, at the displacement (x86_64) or at the call instruction
    // (RISC-V / AArch64). See JeandleAssembler::patch_static_call_site(),
    // patch_routine_call_site(), patch_ic_call_site(), patch_external_call_site().
    expect(CodeBuffer::SECT_INSTS, relocInfo::static_call_type, sources._static_call_sites);
    expect(CodeBuffer::SECT_INSTS, relocInfo::opt_virtual_call_type,
           sources._opt_virtual_call_sites);
    expect(CodeBuffer::SECT_INSTS, relocInfo::virtual_call_type, sources._virtual_call_sites);

    // Runtime routine calls always carry runtime_call_type. On x86_64 the external call
    // sites are patched through a trampoline with `relocInfo::none` at the call site
    // (jeandleAssembler_x86.cpp: patch_external_call_site -> trampoline_call asserts it),
    // while RISC-V and AArch64 go through trampoline_call(Address(..., runtime_call_type))
    // - same record cost, so both are counted as runtime calls.
    expect(CodeBuffer::SECT_INSTS, relocInfo::runtime_call_type,
           sources._routine_call_sites + sources._external_call_sites);

    // --- const-section cross references ------------------------------------
    expect(CodeBuffer::SECT_INSTS, relocInfo::jeandle_section_word_type,
           sources._section_word_insts);
    expect(CodeBuffer::SECT_CONSTS, relocInfo::jeandle_section_word_type,
           sources._section_word_consts);
    expect(CodeBuffer::SECT_INSTS, relocInfo::jeandle_oop_type, sources._oop_relocs);
    expect(CodeBuffer::SECT_CONSTS, relocInfo::jeandle_oop_addr_type,
           sources._oop_addr_relocs);

    // --- fixed frame components in the prolog ------------------------------
    // The receiver check jumps to the IC-miss stub: x86_64 uses jump_cc(), which
    // relocates unconditionally; RISC-V and AArch64 use far_jump(), which relocates only
    // when branches are far and emits a plain label patch otherwise. Counting the record
    // always costs one unused slot on those two when far branching is off, which is the
    // right trade against having to model a branch condition here.
    if (sources._ic_check) {
      expect(CodeBuffer::SECT_INSTS, relocInfo::runtime_call_type, 1);
    }

    // emit_clinit_barrier_on_entry(): mov_metadata() (a metadata record) plus a jump to
    // the handle-wrong-method stub (a runtime_call record).
    if (sources._clinit_barrier) {
      expect(CodeBuffer::SECT_INSTS, relocInfo::metadata_type, 1);
      expect(CodeBuffer::SECT_INSTS, relocInfo::runtime_call_type, 1);
    }

    // emit_nmethod_entry_barrier() plus the guard slot and the deferred stub.
    // RISC-V / AArch64 park a guard word in the code and relocate it with
    // entry_guard_Relocation (jeandleAssembler_riscv.cpp: JeandleEntryBarrierStub::emit);
    // x86_64 instead calls the barrier runtime routine from the deferred stub, which is a
    // runtime_call record. Both variants keep one barrier_type slot of margin: a GC
    // barrier patch point is the kind of record an upstream barrier change could add, and
    // one slot costs 2 bytes.
    if (sources._entry_barrier) {
      if (sources._entry_barrier_calls_runtime_stub) {
        expect(CodeBuffer::SECT_INSTS, relocInfo::runtime_call_type, 1);
      } else {
        expect(CodeBuffer::SECT_INSTS, relocInfo::entry_guard_type, 1);
      }
      expect(CodeBuffer::SECT_INSTS, relocInfo::barrier_type, 1);
    }

    // --- stubs section -----------------------------------------------------
    // A static call site always needs its interpreter-entry stub, which relates back to
    // the call site through one static_stub record (emit_static_call_stub()).
    //
    // "static call site" here means both kinds the resolver distinguishes only by the
    // target: a statically bound call is patched with an opt_virtual_call record at the
    // call site, but it still emits the very same stub, because JeandleCallReloc::emit_reloc()
    // branches on the call *type* (STATIC_CALL) and only patch_static_call_site() looks at
    // the target. Counting the stub records under _static_call_sites alone
    // under-counts them: statically bound calls emit the same stub, so a method
    // with no static calls can still have stub records.
    const int stub_bearers = sources._static_call_sites + sources._opt_virtual_call_sites;

    expect(CodeBuffer::SECT_STUBS, relocInfo::static_stub_type, stub_bearers);
    // The body of that stub relocates too, so the stub section holds more than one
    // record per stub. Reading emit_static_call_stub() gives the per-architecture
    // facts: every architecture
    // loads a method oop there (metadata record), and x86_64 also jumps through a
    // RuntimeAddress (runtime_call record).
    if (sources._static_call_stub_metadata_record) {
      expect(CodeBuffer::SECT_STUBS, relocInfo::metadata_type, stub_bearers);
    }
    if (sources._static_call_stub_runtime_call_record) {
      expect(CodeBuffer::SECT_STUBS, relocInfo::runtime_call_type, stub_bearers);
    }

    // A call whose target is too far for one branch is routed through a trampoline, and
    // every trampoline records which call owns it (trampoline_stub_type).
    //   * static / virtual / external calls: one per call site, emitted inline by
    //     emit_trampoline_stub() when this build branches far.
    //   * runtime calls: shared per target, emitted by CodeBuffer::finalize_stubs(), one
    //     record per call site (the first for a target comes from emit_trampoline_stub(),
    //     the rest from the loop over the collected caller offsets in codeBuffer_riscv.cpp).
    if (sources._far_branches) {
      expect(CodeBuffer::SECT_STUBS, relocInfo::trampoline_stub_type,
             sources._static_call_sites + sources._opt_virtual_call_sites +
             sources._virtual_call_sites + sources._external_call_sites +
             sources._routine_call_sites);
    } else {
      // x86_64: external calls always go through an trampoline stub on this path
      // (MacroAssembler::trampoline_call emits one unconditionally, guarded only by the
      // reachability of the pc-relative call that Jeandle asks it to build).
      expect(CodeBuffer::SECT_STUBS, relocInfo::trampoline_stub_type,
             sources._external_call_sites);
    }

    // Each handler stub jumps to a runtime routine: far_jump() relocates on the
    // architectures that branch far, and MacroAssembler::jump(RuntimeAddress(...))
    // relocates on x86_64. Both cost one runtime_call record.
    expect(CodeBuffer::SECT_STUBS, relocInfo::runtime_call_type, sources._handlers);
  }

  compute_sizes(insts_size, consts_size, stubs_size);
}

void JeandleRelocPlan::compute_sizes(size_t insts_size, size_t consts_size, size_t stubs_size) {
  const size_t section_sizes[CodeBuffer::SECT_LIMIT] = { consts_size, insts_size, stubs_size };

  for (int s = 0; s < (int)CodeBuffer::SECT_LIMIT; s++) {
    // Legacy provisioning, per section: the insts section got Jeandle's
    // `sizeof(relocInfo) + length_limit` bytes (8 slots after integer division), the two
    // secondary sections got initialize_locs(1), floored to 4 slots. Recorded so a reader
    // can compare what was asked for against what was actually usable.
    _legacy_capacity_elements[s] = (s == (int)CodeBuffer::SECT_INSTS)
                                       ? (size_t)((sizeof(relocInfo) + relocInfo::length_limit) /
                                                  sizeof(relocInfo))
                                       : minimum_capacity_elements();

    if (!is_exact()) {
      _bytes[s] = 0;
      _capacity_elements[s] = 0;
      _elements[s] = 0;
      _filler_elements[s] = 0;
      continue;
    }

    const size_t size = section_sizes[s];

    if (_payload_elements[s] == 0) {
      // No record is expected in this section, so the headroom below is not owed to it:
      // relocate() only demands `length_limit` free slots when it is about to write
      // something.
      // If a record does arrive here anyway, the array grows once and the
      // planned/actual census reports the model gap; correctness never depends on it.
      _filler_elements[s] = 0;
      _elements[s] = 0;
      _capacity_elements[s] = minimum_capacity_elements();
      _bytes[s] = _capacity_elements[s] * (size_t)sizeof(relocInfo);
      continue;
    }

    _filler_elements[s] = gap_filler_bound(size);
    _elements[s] = _payload_elements[s] + _filler_elements[s];

    // The expand test also inflates the requirement by the current record's own gap:
    // `req += offset / offset_limit`. Bound that by the section size too, so the request
    // provably never triggers expand_locs().
    const size_t probe_extra = size / (size_t)relocInfo::offset_limit();
    size_t capacity = _elements[s] + probe_extra + probe_headroom();
    if (capacity < minimum_capacity_elements()) {
      // initialize_locs() would raise it anyway; asking for the raised number keeps
      // "planned capacity" equal to "capacity HotSpot handed out".
      capacity = minimum_capacity_elements();
    }
    if (capacity > (size_t)INT_MAX / sizeof(relocInfo)) {
      refuse(Status::Overflow, s);
      _bytes[s] = 0;
      _capacity_elements[s] = 0;
      continue;
    }
    _capacity_elements[s] = capacity;
    _bytes[s] = capacity * (size_t)sizeof(relocInfo);
  }
}

int JeandleRelocPlan::records(int section, relocInfo::relocType type) const {
  assert(section >= (int)CodeBuffer::SECT_FIRST && section < (int)CodeBuffer::SECT_LIMIT,
         "must be a CodeBuffer section");
  const int slot = (int)type;
  if (slot < 0 || slot > (int)relocInfo::type_mask) {
    return 0;
  }
  return _records[section][slot];
}

int JeandleRelocPlan::record_count(int section) const {
  assert(section >= (int)CodeBuffer::SECT_FIRST && section < (int)CodeBuffer::SECT_LIMIT,
         "must be a CodeBuffer section");
  int total = 0;
  for (int k = 0; k <= (int)relocInfo::type_mask; k++) {
    total += _records[section][k];
  }
  return total;
}

void JeandleRelocPlan::fill_capacity_request(int out_bytes[CodeBuffer::SECT_LIMIT]) const {
  for (int s = 0; s < (int)CodeBuffer::SECT_LIMIT; s++) {
    out_bytes[s] = is_exact() ? (int)_bytes[s] : -1;
  }
}

const char* JeandleRelocPlan::status_name(Status status) {
  switch (status) {
    case Status::NotComputed:          return "NotComputed";
    case Status::Exact:                return "Exact";
    case Status::LegacyDisabled:       return "LegacyDisabled";
    case Status::NonMethodCompilation: return "NonMethodCompilation";
    case Status::UnknownRecordType:    return "UnknownRecordType";
    case Status::OutOfModelRecords:    return "OutOfModelRecords";
    case Status::Overflow:             return "Overflow";
  }
  return "Unknown";
}

const char* JeandleRelocPlan::type_name(relocInfo::relocType type) {
  switch (type) {
    case relocInfo::none:                   return "none";
    case relocInfo::oop_type:               return "oop";
    case relocInfo::virtual_call_type:      return "virtual_call";
    case relocInfo::opt_virtual_call_type:  return "opt_virtual_call";
    case relocInfo::static_call_type:       return "static_call";
    case relocInfo::static_stub_type:       return "static_stub";
    case relocInfo::runtime_call_type:      return "runtime_call";
    case relocInfo::external_word_type:     return "external_word";
    case relocInfo::internal_word_type:     return "internal_word";
    case relocInfo::section_word_type:      return "section_word";
    case relocInfo::poll_type:              return "poll";
    case relocInfo::poll_return_type:       return "poll_return";
    case relocInfo::metadata_type:          return "metadata";
    case relocInfo::trampoline_stub_type:   return "trampoline_stub";
    case relocInfo::runtime_call_w_cp_type: return "runtime_call_w_cp";
    case relocInfo::data_prefix_tag:        return "data_prefix_tag";
    case relocInfo::post_call_nop_type:     return "post_call_nop";
    case relocInfo::entry_guard_type:       return "entry_guard";
    case relocInfo::barrier_type:           return "barrier";
    case relocInfo::jeandle_section_word_type: return "jeandle_section_word";
    case relocInfo::jeandle_oop_type:       return "jeandle_oop";
    case relocInfo::jeandle_oop_addr_type:  return "jeandle_oop_addr";
    default:                                return "unknown";
  }
}

std::string JeandleRelocPlan::describe_section(int section) const {
  static const char* const section_names[CodeBuffer::SECT_LIMIT] = { "consts", "insts", "stubs" };
  std::string out;
  out += section_names[section];
  out += "[";
  bool first = true;
  for (int k = 0; k <= (int)relocInfo::type_mask; k++) {
    if (_records[section][k] == 0) {
      continue;
    }
    if (!first) {
      out += ",";
    }
    first = false;
    out += type_name((relocInfo::relocType)k);
    out += ":";
    out += std::to_string(_records[section][k]);
    out += "x";
    bool known = true;
    out += std::to_string(record_elements(record_data_halfwords((relocInfo::relocType)k, &known)));
  }
  out += "]";
  return out;
}
