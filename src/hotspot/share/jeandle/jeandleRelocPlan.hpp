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

#ifndef SHARE_JEANDLE_RELOCPLAN_HPP
#define SHARE_JEANDLE_RELOCPLAN_HPP

#include "asm/codeBuffer.hpp"
#include "code/relocInfo.hpp"

#include <cstdint>
#include <string>

// ---------------------------------------------------------------------------
// Sizing the fourth CodeBuffer section, locs
// ---------------------------------------------------------------------------
//
// HotSpot keeps the relocation information of each CodeBuffer section in its own array
// (`CodeSection::_locs_start` .. `_locs_limit`). The legacy provisioning asks for one
// size for the whole buffer:
//
//     sizeof(relocInfo) + relocInfo::length_limit
//
// which is 2 + 15 = 17 bytes, i.e. 8 relocInfo slots after the integer division in
// CodeBuffer::initialize() - while the consts and stubs sections were handed
// `initialize_locs(1)`, which HotSpot floors to 4 slots.
//
// CodeSection::relocate() refuses to write a record unless
//
//     locs_end + relocInfo::length_limit < locs_limit
//
// so a section holding one record already had to grow, and every further record grew it
// again through CodeSection::expand_locs(): a REALLOC_RESOURCE_ARRAY plus a copy of
// everything written so far. That growth path is invisible to CodeBuffer::expand()
// accounting. The counters (CodeBuffer's relocation census) measure the
// reallocations; the planner here removes them.
//
// Sizing locs is not `records * sizeof(relocInfo)`, for two reasons that both have their
// own function below:
//
//   1. A record is variable-length. relocInfo is one `unsigned short` holding
//      {type, format, offset}; a record with payload data is preceded by a prefix that
//      carries that data (folded into the prefix header itself when it is a single 10-bit
//      value). So a record costs between 1 and `relocInfo::length_limit` slots depending
//      on its type - and on the architecture, because RISC-V's jeandle_section_word
//      encoding stores 6 more halfwords than x86's.
//   2. Records store the distance from the *previous* relocated address, scaled by
//      `relocInfo::offset_unit`. Two records further apart than `relocInfo::offset_limit()`
//      bytes are bridged by filler records, one per step. A method with few records spread
//      over 4 KiB of code still pays for those fillers.
//
// Everything is an upper bound by construction: the per-type slot cost ignores the
// prefix-immediate folding (which only ever saves slots), and the filler term is derived
// from the section's own payload size, which bounds the sum of all record gaps.
class JeandleRelocPlan {
 public:
  // A refusal reason. Anything but Exact falls back to HotSpot's heuristic: a plan that
  // cannot name every record it expects is worse than no plan at all.
  enum class Status : uint8_t {
    NotComputed,        // decide_install_layout() has not run the planner
    Exact,              // every expected record is accounted for
    LegacyDisabled,     // -XX:-JeandleExactLocs: measuring the legacy provisioning
    NonMethodCompilation, // stub/routine compilation: no method to classify records from
    UnknownRecordType,  // a relocInfo type this model does not size
    OutOfModelRecords,  // call records the plan could not attribute to a known call site
    Overflow            // a size computation would not fit the requested unit
  };

  // The relocation-producing events of one compilation. Every field counts *emitters*,
  // read from the ELF object or from the same predicates used for insts and
  // stubs. Turning emitters into relocInfo records is this class's job.
  struct Sources {
    // Call sites, as LLVM actually emitted them (one per stackmap record): the
    // bytecode-level count under-counts, because LLVM inlining, loop unrolling and tail
    // duplication copy call instructions (MethodHandleNatives::<clinit>).
    int _static_call_sites;       // static_call_Relocation at the call site
    int _opt_virtual_call_sites;  // same site class, statically bound
    int _virtual_call_sites;      // inline-cache calls (DYNAMIC_CALL)
    int _routine_call_sites;      // calls of HotSpot runtime routines
    int _unique_routine_targets;  // distinct routine entries, for shared trampolines
    int _external_call_sites;     // calls of symbols outside HotSpot

    // Relocations discovered from the LinkGraph edges.
    int _section_word_insts;      // code refers to a constant
    int _section_word_consts;     // a constant refers back to code
    int _oop_relocs;              // oops referenced from the code section
    int _oop_addr_relocs;         // oop addresses referenced from the const section

    // Fixed frame components, each of which emits its own records.
    int  _handlers;               // exception + deopt (+ deopt-MH) handler stubs
    bool _ic_check;               // receiver check emitted in the prolog
    bool _clinit_barrier;         // class-initialisation barrier in the prolog
    bool _entry_barrier;          // nmethod entry barrier in the prolog
    bool _entry_barrier_calls_runtime_stub; // that barrier reaches its routine by call

    // Architecture facts, passed in rather than #ifdef'd, so that the inventory below is
    // readable as one table. `_far_branches` is MacroAssembler::far_branches() where the
    // architecture has one (x86_64 always branches far enough to avoid trampolines).
    // `_shared_trampolines` and `_unique_routine_targets` do not change the record count
    // (a shared trampoline still records every caller) but are carried for the stubs-side
    // model that consumes the same census.
    bool _far_branches;
    bool _shared_trampolines;     // runtime calls reach their target via a shared stub
    bool _method_compilation;     // _method != nullptr

    // What the body of one static-call stub relocates besides the static_stub record that
    // relates it to its call site. All three architectures load a method oop there, which
    // is a metadata record; x86_64 additionally jumps through a RuntimeAddress, which is a
    // runtime_call record. Read off emit_static_call_stub() in each
    // jeandleAssembler_<cpu>.cpp. These two facts are modelled explicitly instead of
    // assuming one record per stub.
    bool _static_call_stub_metadata_record;
    bool _static_call_stub_runtime_call_record;

    Sources()
      : _static_call_sites(0), _opt_virtual_call_sites(0), _virtual_call_sites(0),
        _routine_call_sites(0), _unique_routine_targets(0), _external_call_sites(0),
        _section_word_insts(0), _section_word_consts(0),
        _oop_relocs(0), _oop_addr_relocs(0),
        _handlers(0), _ic_check(false), _clinit_barrier(false), _entry_barrier(false),
        _entry_barrier_calls_runtime_stub(false),
        _far_branches(false), _shared_trampolines(false), _method_compilation(false),
        _static_call_stub_metadata_record(false),
        _static_call_stub_runtime_call_record(false) {}
  };

 private:
  Status _status;
  int _failed_kind;                                  // type or source that caused a refusal
  // Records expected per section and relocInfo type.
  int _records[CodeBuffer::SECT_LIMIT][relocInfo::type_mask + 1];
  size_t _payload_elements[CodeBuffer::SECT_LIMIT];  // records without fillers
  size_t _filler_elements[CodeBuffer::SECT_LIMIT];   // gap fillers, upper bound
  size_t _elements[CodeBuffer::SECT_LIMIT];          // payload + fillers
  size_t _capacity_elements[CodeBuffer::SECT_LIMIT]; // + relocate()'s probe headroom
  size_t _bytes[CodeBuffer::SECT_LIMIT];             // what initialize_locs() is asked for
  size_t _legacy_capacity_elements[CodeBuffer::SECT_LIMIT]; // what legacy provisioned

  void refuse(Status status, int kind);
  // Add one expected record, or refuse if the type has no size in this model.
  void expect(int section, relocInfo::relocType type, int count);
  void compute_sizes(size_t insts_size, size_t consts_size, size_t stubs_size);

 public:
  JeandleRelocPlan() : _status(Status::NotComputed), _failed_kind(-1) {
    for (int s = 0; s < (int)CodeBuffer::SECT_LIMIT; s++) {
      _payload_elements[s] = 0;
      _filler_elements[s] = 0;
      _elements[s] = 0;
      _capacity_elements[s] = 0;
      _bytes[s] = 0;
      _legacy_capacity_elements[s] = 0;
      for (int k = 0; k <= (int)relocInfo::type_mask; k++) {
        _records[s][k] = 0;
      }
    }
  }

  // Build the plan. `insts_size`, `consts_size`, `stubs_size` are the payload capacities
  // the sections are going to get - all three are already known before allocation, and
  // they are what bounds the record gaps.
  void compute(const Sources& sources,
               size_t insts_size, size_t consts_size, size_t stubs_size);

  // ---- encoding rules -----------------------------------------------------

  // Slots consumed by one record carrying `data_halfwords` of payload: the tag word, plus
  // - when there is data - one prefix word and the data itself.
  // See relocInfo::initialize() and relocInfo::finish_prefix().
  static int record_elements(int data_halfwords);

  // Halfwords written by `<type>_Relocation::pack_data_to()`, upper bound. Types whose
  // encoding is architecture-specific are delegated to
  // JeandleAssembler::reloc_record_data_halfwords(), which lives next to the emitters.
  // `*known` is set to false for types this model does not size.
  static int record_data_halfwords(relocInfo::relocType type, bool* known);

  // Filler records CodeSection::relocate() emits to bridge a gap of `gap` bytes.
  // Mirrors the loop in that function exactly.
  static int gap_fillers(size_t gap);

  // Upper bound for the filler slots any sequence of records inside a section of
  // `section_size` bytes can need: the gaps of one section are disjoint and sum to at
  // most its size, and every filler advances the point by `offset_limit - offset_unit`.
  static size_t gap_filler_bound(size_t section_size);

  // The headroom relocate() demands on top of the record itself: it expands when
  // `locs_end + length_limit >= locs_limit`, so capacity must also cover the largest
  // possible record after the one being written, plus one slot because the test is `>=`.
  static size_t probe_headroom();

  // HotSpot's own floor: initialize_locs() never allocates fewer slots than this.
  static size_t minimum_capacity_elements();

  // ---- results ------------------------------------------------------------

  bool is_exact() const { return _status == Status::Exact; }
  Status status() const { return _status; }
  static const char* status_name(Status status);
  const char* status_name() const { return status_name(_status); }
  int failed_kind() const { return _failed_kind; }

  int records(int section, relocInfo::relocType type) const;
  int record_count(int section) const;
  size_t payload_elements(int section) const { return _payload_elements[section]; }
  size_t filler_elements(int section) const  { return _filler_elements[section]; }
  size_t elements(int section) const         { return _elements[section]; }
  size_t capacity_elements(int section) const { return _capacity_elements[section]; }
  size_t bytes(int section) const             { return _bytes[section]; }
  size_t legacy_capacity_elements(int section) const { return _legacy_capacity_elements[section]; }

  // Bytes of capacity the legacy provisioning handed out, for the saved-by column.
  size_t legacy_bytes(int section) const {
    return _legacy_capacity_elements[section] * (size_t)sizeof(relocInfo);
  }

  // Copy the per-section byte requests out, for CodeBuffer::set_locs_capacity() and the
  // telemetry. A section the plan refused gets -1 so downstream readers can tell.
  void fill_capacity_request(int out_bytes[CodeBuffer::SECT_LIMIT]) const;

  // Human-readable record census of one section, for the trace lines.
  std::string describe_section(int section) const;

  // relocInfo type names, for tracing and diagnostics.
  static const char* type_name(relocInfo::relocType type);
};

#endif // SHARE_JEANDLE_RELOCPLAN_HPP
