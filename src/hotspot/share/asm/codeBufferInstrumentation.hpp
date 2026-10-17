#ifndef SHARE_ASM_CODEBUFFERINSTRUMENTATION_HPP
#define SHARE_ASM_CODEBUFFERINSTRUMENTATION_HPP

#include "utilities/globalDefinitions.hpp"
#include "utilities/growableArray.hpp"
#include "runtime/atomic.hpp"
#include "runtime/mutex.hpp"
#include <atomic>

class outputStream;
class CodeBuffer;   // records are keyed by buffer identity

class CodeBufferInstrumentation : public CHeapObj<mtCompiler> {
 public:
  // Number of CodeBuffer sections (consts, insts, stubs) and of relocInfo types the
  // census can report. Both are mirrored here because this header must not depend on
  // codeBuffer.hpp (which includes this file). The .cpp checks the values at the first
  // use, so a drift upstream cannot silently truncate a histogram.
  enum {
    locs_sections   = 3,
    reloc_kind_max  = 32
  };

 private:
  struct ExpansionEvent {
    int  _section;        // SECT_CONSTS=0, SECT_INSTS=1, SECT_STUBS=2
    int  _amount;         // requested expansion bytes
    int  _new_total_cap;  // new total capacity after expansion
  };

  // Per-compilation record with full telemetry
  struct Record {
    // The buffer this record belongs to. Everything is looked up through this
    // pointer: addressing "the last appended record" mis-attributes data
    // whenever CodeBuffer::expand() creates a temporary buffer.
    const CodeBuffer* _buffer = nullptr;
    // Set once finalize() reported this buffer. Records without it belong to
    // temporary or unfinished buffers: counted, never summed.
    bool _finalized = false;
    // A CodeBuffer's address is reused once that buffer dies, so the pointer is
    // only unique while the buffer is alive: ~CodeBuffer() retires the record and a
    // later buffer at the same address gets a fresh one. Without this, compilations
    // at a reused address collapse onto a single record.
    bool _retired = false;
    const char* _name;
    // Initial allocation (from CodeBuffer::initialize + initialize_consts_size)
    int  _initial_code_size;       // total code buffer size requested
    int  _initial_consts_capacity; // consts section pre-allocated capacity
    int  _initial_stubs_size;      // stubs section size
    int  _initial_locs_size;       // relocation locs size
    // Expansion tracking
    int  _expand_count;
    GrowableArrayCHeap<ExpansionEvent, mtCompiler> _expansions;
    int  _total_expand_bytes;      // sum of all expansion amounts
    // Final actual usage (from CodeBuffer sections after finalize)
    int  _final_consts_size;       // actual bytes used in consts section
    int  _final_insts_size;        // actual bytes used in insts section
    int  _final_stubs_size;        // actual bytes used in stubs section
    int  _final_consts_capacity;   // final capacity of consts section
    // Deterministic const layout telemetry
    // _planned_consts_size < 0 means the planner was not used (legacy allocation).
    int  _planned_consts_size;     // ConstSectionPlan::total_size()
    int  _planned_padding;         // ConstSectionPlan::total_padding()
    int  _planned_alignment;       // ConstSectionPlan::max_alignment()
    int  _const_entries;           // number of planned const sections
    const char* _plan_status;      // ConstSectionPlan::status_name()
    bool _used_exact_allocation;   // consts capacity came from the plan, not the 48 KiB default
    int  _layout_fallback_count;   // runtime discovery of an unplanned const section
    // Relocation array (locs) census, one entry per section, in CodeBuffer
    // section order. CodeBuffer::expand() (CodeCache growth) and CodeSection::expand_locs()
    // (a ResourceArea realloc on the relocation array) are two different growth paths; the
    // fields above cover the first one; these cover the second.
    // _locs_planned_bytes[n] < 0 means the locs model did not size that section.
    int  _locs_planned_bytes[locs_sections];   // model prediction, bytes
    int  _locs_initial_elements[locs_sections];// slots allocated by initialize_locs()
    int  _locs_used_elements[locs_sections];   // slots in use at the end of finalize()
    int  _locs_final_elements[locs_sections];  // slot capacity at the end of finalize()
    int  _locs_expansions[locs_sections];      // expand_locs() reallocations
    int  _locs_expand_bytes[locs_sections];    // slots those reallocs added, in bytes
    int  _locs_gap_fillers[locs_sections];     // filler records spanning large offsets
    int  _locs_unstored[locs_sections];        // relocate() calls with no locs storage
    int  _reloc_records[locs_sections][reloc_kind_max];   // records by section and type
    int  _reloc_elements[locs_sections][reloc_kind_max];  // elements consumed by those

    Record(const char* name, int code_size, int consts_cap,
           int stubs_size, int locs_size)
      : _name(name), _initial_code_size(code_size),
        _initial_consts_capacity(consts_cap), _initial_stubs_size(stubs_size),
        _initial_locs_size(locs_size), _expand_count(0), _expansions(2),
        _total_expand_bytes(0), _final_consts_size(0), _final_insts_size(0),
        _final_stubs_size(0), _final_consts_capacity(0),
        _planned_consts_size(-1), _planned_padding(0), _planned_alignment(0),
        _const_entries(0), _plan_status("Unplanned"),
        _used_exact_allocation(false), _layout_fallback_count(0) {
      for (int s = 0; s < (int)locs_sections; s++) {
        _locs_planned_bytes[s]  = -1;
        _locs_initial_elements[s] = 0;
        _locs_used_elements[s]  = 0;
        _locs_final_elements[s] = 0;
        _locs_expansions[s]     = 0;
        _locs_expand_bytes[s]   = 0;
        _locs_gap_fillers[s]    = 0;
        _locs_unstored[s]       = 0;
        for (int k = 0; k < (int)reloc_kind_max; k++) {
          _reloc_records[s][k]  = 0;
          _reloc_elements[s][k] = 0;
        }
      }
    }

    // Computed metrics
    int consts_slack() const { return _final_consts_capacity - _final_consts_size; }
    double consts_waste_rate() const {
      return _final_consts_capacity > 0
        ? (double)consts_slack() / (double)_final_consts_capacity
        : 0.0;
    }
  };

  // Published atomically: the first compiler threads to arrive must not each
  // build their own instance.
  static std::atomic<CodeBufferInstrumentation*> _instance;
  // Appended from several compiler threads, so it needs a lock: without a lock,
  // appends race, and length()-1 does not identify a specific record.
  // Taken as a leaf: nothing else is locked while holding it, and records are
  // allocated before the lock is taken. The rank sits below CodeCache_lock
  // (nosafepoint-2) because ~CodeBuffer and initialize() can run while that lock
  // is held; a higher rank trips the lock-order assert.
  GrowableArrayCHeap<Record*, mtCompiler> _records;
  Mutex _lock;
  bool _output_done;

  // One unambiguous counting unit for every number this tool reports.
  //
  // The populations must not be mixed: "methods compiled" from the
  // plan trace, "records" from this instrumentation, and a capacity-derived
  // count are different populations and are not comparable.
  // Every number printed derives from exactly this struct, over exactly one
  // population: the recorded initialize() calls.
  struct Summary {
    int total_records;        // every recorded initialize() -> the counting unit
    int finalized_records;    // records a real compilation finished
    int unfinalized_records;  // temporary / never-finalized buffers
    int consts_allocating;    // records that actually reserved a consts section
    int nonempty_consts;      // records planning at least one const section
    int zero_consts;          // records planning none
    int exact_path;           // consts capacity came from ConstSectionPlan
    int legacy_path;          // consts capacity came from the 48 KiB default
    int fallback_records;     // records that hit runtime layout fallback
    int total_expands;
    int consts_expands;
    int insts_expands;
    int stubs_expands;
    int total_expand_bytes;
    int consts_usage;
    int consts_capacity;
    int planned_size_sum;
    // Locs / relocation census aggregates over finalized records only.
    int locs_expansions;
    int locs_expand_bytes;
    int locs_used_elements;
    int locs_capacity_elements;
    int locs_planned_bytes;
    int locs_gap_fillers;
    int locs_unstored;
    int records_with_locs_expansion;
    int records_with_exact_locs;
    int reloc_records_total;
    int reloc_elements_total;
    int reloc_kind_records[reloc_kind_max];
    int reloc_kind_elements[reloc_kind_max];
  };

  Summary compute_summary() const;

  // Declaration order is _records, _lock, _output_done - the initializer list must
  // follow it (-Werror=reorder).
  CodeBufferInstrumentation() : _records(8),
                                  _lock(Mutex::nosafepoint - 4, "CodeBufferInstrumentation_lock"),
                                  _output_done(false) {}
  void print_summary();

 public:
  static CodeBufferInstrumentation* instance();

  // The only place the develop flags are read. In a product build both fold away
  // to false/nullptr, so no #ifdef is needed at the dozen call sites.
  static bool enabled();
  // Idempotent per buffer. CodeBuffer::initialize() is entered twice for the
  // 4-argument form (delegating overload) and Jeandle calls it a third time;
  // repeated entry appends no duplicate records for one compilation.
  void record_initialize(const CodeBuffer* cb, const char* name, int code_size,
                         int consts_cap, int stubs_size, int locs_size);

  // Called from ~CodeBuffer(): closes the record so the address can be reused.
  void record_retire(const CodeBuffer* cb);

  void record_expand(const CodeBuffer* cb, int section, int amount,
                     int new_total_cap);

  // Enhanced finalize: captures actual usage + capacity + plan telemetry.
  // planned_consts_size < 0 => planner not used (legacy path).
  void record_finalize(const CodeBuffer* cb, int consts_size, int consts_cap,
                       int insts_size,
                       int stubs_size,
                       int planned_consts_size, int planned_padding,
                       int planned_alignment, int const_entries,
                       const char* plan_status, bool used_exact_allocation,
                       int layout_fallback_count);

  // Pull the locs and relocation census out of a buffer whose compilation is
  // being reported. `planned_bytes` holds one entry per section, or is nullptr when the
  // locs model did not size this buffer (legacy path). Reads only: the census itself is
  // maintained by CodeBuffer/CodeSection without a lock.
  void record_locs(const CodeBuffer* cb, const int* planned_bytes);

  static void output_and_shutdown();

 private:
  // Caller must hold _lock. The record for `cb`, or nullptr.
  Record* find_locked(const CodeBuffer* cb) const;

};

// Aggregate counters for the deterministic const layout work.
//
// These are global and updated from Jeandle compiler threads, so every field is
// updated atomically. They answer "how many methods
// actually took the exact path, and how many had to degrade?" without having to
// post-process per-method records by hand.
class ConstLayoutStats : public AllStatic {
 private:
  static volatile jint _methods_seen;        // methods that reached finalize()
  static volatile jint _exact_path;          // allocated from ConstSectionPlan
  static volatile jint _legacy_path;         // allocated with the 48 KiB default
  static volatile jint _plan_failures;       // planner refused to produce a layout
  static volatile jint _fallback_triggered;  // runtime discovery of an unplanned section
  static volatile jint  _const_expand_after_plan; // consts expanded despite exact allocation
  // The same question for the relocation array. A locs expansion is not a
  // correctness failure - CodeSection::relocate() simply grows the array - so it is
  // reported as a count and never asserted on.
  static volatile jint  _locs_expand_after_plan;
  static volatile jlong _exact_consts_bytes;  // consts bytes handed out by the planner
  static volatile jlong _legacy_consts_bytes; // consts bytes that legacy would have used

 public:
  static void reset();

  static void record_method() { Atomic::add(&_methods_seen, (jint)1); }

  static void record_exact(size_t consts_bytes) {
    Atomic::add(&_exact_path, (jint)1);
    Atomic::add(&_exact_consts_bytes, (jlong)consts_bytes);
  }

  static void record_legacy(size_t consts_bytes) {
    Atomic::add(&_legacy_path, (jint)1);
    Atomic::add(&_legacy_consts_bytes, (jlong)consts_bytes);
  }

  static void record_plan_failure() { Atomic::add(&_plan_failures, (jint)1); }
  static void record_fallback()     { Atomic::add(&_fallback_triggered, (jint)1); }
  static void record_const_expand_after_plan() {
    Atomic::add(&_const_expand_after_plan, (jint)1);
  }
  static void record_locs_expand_after_plan() {
    Atomic::add(&_locs_expand_after_plan, (jint)1);
  }

  static int  methods_seen()               { return Atomic::load(&_methods_seen); }
  static int  exact_path()                 { return Atomic::load(&_exact_path); }
  static int  legacy_path()                { return Atomic::load(&_legacy_path); }
  static int  plan_failures()              { return Atomic::load(&_plan_failures); }
  static int  fallback_triggered()         { return Atomic::load(&_fallback_triggered); }
  static int  const_expand_after_plan()    { return Atomic::load(&_const_expand_after_plan); }
  static int  locs_expand_after_plan()     { return Atomic::load(&_locs_expand_after_plan); }
  static jlong exact_consts_bytes()        { return Atomic::load(&_exact_consts_bytes); }
  static jlong legacy_consts_bytes()       { return Atomic::load(&_legacy_consts_bytes); }

  static void print_on(outputStream* st);
};

#endif // SHARE_ASM_CODEBUFFERINSTRUMENTATION_HPP
