;
; Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
; DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.

; This code is free software; you can redistribute it and/or modify it
; under the terms of the GNU General Public License version 2 only, as
; published by the Free Software Foundation.

; This code is distributed in the hope that it will be useful, but WITHOUT
; ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
; FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
; version 2 for more details (a copy is included in the LICENSE file that
; accompanied this code).

; You should have received a copy of the GNU General Public License version
; 2 along with this work; if not, write to the Free Software Foundation,
; Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
;

; This file defines some LLVM functions which we call them "JavaOp". Each JavaOp represents a high-level java
; operation. These functions will be used by some passes to do Java-related optimizations. After corresponding
; optimizations, JavaOp will be inlined(lowered) by JavaOperationLower passes.

; =============================================================================================================
; Declare these runtime-related constants as global variables. The VM will define them as constants during
; Jeandle compiler initialization time.
;

; We use a null personality function for exception handlers.
@jeandle.personality = global ptr null

; Debug mode
@DEBUG_MODE = external global i1

; Widest usable vector width for the string kernels (AVX-512 VL+BW with a 64
; byte MaxVectorSize on x86, false elsewhere). Constant at JVM start; kernels
; branch on it so the optimizer folds away the unused width.
@VM_Version.string_vector_512bit = external global i1

; System word size
@WordSize = external global i64

; Byte offsets of Array<Klass*> structure fields.
@KlassArray.base_offset_in_bytes = external global i32
@KlassArray.length_offset_in_bytes = external global i32

; Byte offsets of arrayOopDesc structure fields.
@arrayOopDesc.length_offset_in_bytes = external global i32
; Per-element-type array base offset. Consumed by the LLVM-side partial escape
; analyzer (VMConstants::fromModule) so it can compute element addresses for
; virtualised arrays. The .int member predates the others.
@arrayOopDesc.base_offset_in_bytes.boolean = external global i32
@arrayOopDesc.base_offset_in_bytes.byte    = external global i32
@arrayOopDesc.base_offset_in_bytes.char    = external global i32
@arrayOopDesc.base_offset_in_bytes.short   = external global i32
@arrayOopDesc.base_offset_in_bytes.int     = external global i32
@arrayOopDesc.base_offset_in_bytes.long    = external global i32
@arrayOopDesc.base_offset_in_bytes.float   = external global i32
@arrayOopDesc.base_offset_in_bytes.double  = external global i32
@arrayOopDesc.base_offset_in_bytes.object  = external global i32
; Per-element-type array element size in bytes. Same delivery model; consumed
; by VMConstants::fromModule alongside the base offsets.
@arrayOopDesc.element_size.boolean = external global i32
@arrayOopDesc.element_size.byte    = external global i32
@arrayOopDesc.element_size.char    = external global i32
@arrayOopDesc.element_size.short   = external global i32
@arrayOopDesc.element_size.int     = external global i32
@arrayOopDesc.element_size.long    = external global i32
@arrayOopDesc.element_size.float   = external global i32
@arrayOopDesc.element_size.double  = external global i32
@arrayOopDesc.element_size.object  = external global i32

; Byte offsets for Klass structure fields.
@Klass.access_flags_offset = external global i32
@Klass.java_mirror_offset = external global i32
@Klass.layout_helper_offset = external global i32
@Klass.secondary_super_cache_offset = external global i32
@Klass.secondary_supers_offset = external global i32
@Klass.super_check_offset_offset = external global i32
@ObjArrayKlass.element_klass_offset = external global i32

; InstanceKlass initialization state used by klass_is_initialized.
@InstanceKlass.init_state_offset = external global i32
@InstanceKlass.fully_initialized = external global i8

; Byte offsets for oopDesc structure fields.
@oopDesc.klass_offset_in_bytes = external global i32
@oopDesc.mark_offset_in_bytes = external global i32
@instanceOopDesc.base_offset_in_bytes = external global i32

; Byte offsets for JavaThread structure fields.
@JavaThread.tlab_end_offset = external global i64
@JavaThread.tlab_top_offset = external global i64
@JavaThread.tlab_pf_top_offset = external global i64

; Byte offsets for markWord structure fields.
@markWord.prototype_value = external global i64

; Global vm options
@VMOptions.UseTLAB = external global i1
@VMOptions.ZeroTLAB = external global i1
@VMOptions.ReduceBulkZeroing = external global i1
@VMOptions.ReduceInitialCardMarks = external global i1
@VMOptions.AllocateInstancePrefetchLines = external global i32
@VMOptions.AllocatePrefetchLines = external global i32
; Heap layout switches consumed by VMConstants::fromModule on the LLVM side.
@VMOptions.UseCompressedClassPointers = external global i1
@VMOptions.UseCompressedOops = external global i1

; Arraycopy optimization thresholds supplied by HotSpot at startup.
@VMOptions.ArrayOperationPartialInlineSize = external global i32
@VMOptions.ArrayCopyLoadStoreMaxElem = external global i32

; Byte offsets for java.lang.ref.Reference instance fields.
@java_lang_ref_Reference.referent_offset = external global i32

; Byte offset of the represented Klass* in java.lang.Class.
@java_lang_Class.klass_offset = external global i32

; Byte offset of the cached array klass in java.lang.Class (injected field).
; Stores the array Klass* for this component type once the array type has been loaded.
; Zero/null means the array klass has not yet been resolved.
@java_lang_Class.array_klass_offset = external global i32

; Byte offsets for BasicLock structure fields.
@BasicLock.displaced_header_offset_in_bytes = external global i32

; Byte offsets for JavaThread structure fields.
@JavaThread.held_monitor_count_offset = external global i32
@JavaThread.lock_stack_end = external global i32
@JavaThread.lock_stack_top_offset = external global i32

; Byte offsets for ObjectMonitor structure fields.
@ObjectMonitor.EntryList_offset_no_monitor_value = external global i32
@ObjectMonitor.cxq_offset_no_monitor_value = external global i32
@ObjectMonitor.owner_offset_no_monitor_value = external global i32
@ObjectMonitor.recursions_offset_no_monitor_value = external global i32
@ObjectMonitor.succ_offset_no_monitor_value = external global i32
@ObjectMonitor.ANONYMOUS_OWNER = external global i64

; Static constants in markWord
@markWord.clear_lock_mask = external global i64
@markWord.monitor_value = external global i64
@markWord.unlocked_value = external global i64
@markWord.unused_mark_value = external global i64

; Global definitions
@JVM_ACC_IS_VALUE_BASED_CLASS = external global i32
@JVM_ACC_HAS_FINALIZER = external global i32
@oopSize = external global i32
@check_recursive_mask_value = external global i64

; Byte offsets for G1ThreadLocalData structure fields.
@G1ThreadLocalData.satb_mark_queue_active_offset = external global i32
@G1ThreadLocalData.satb_mark_queue_index_offset = external global i32
@G1ThreadLocalData.satb_mark_queue_buffer_offset = external global i32
@G1ThreadLocalData.dirty_card_queue_index_offset = external global i32
@G1ThreadLocalData.dirty_card_queue_buffer_offset = external global i32

; Card table constants
@CardTable.card_shift = external global i64
@ci_card_table_address = external global i64
@HeapRegion.LogOfHRGrainBytes = external global i64
@G1CardTable.g1_young_card_val = external global i8
@G1CardTable.dirty_card_val = external global i8

; Address for G1BarrierSetRuntime functions.
@G1BarrierSetRuntime.write_ref_field_pre_entry = external global i64
@G1BarrierSetRuntime.write_ref_field_post_entry = external global i64

; Address for the monitorexit slow-path routine (SharedRuntime::complete_monitor_unlocking_C).
; It is a direct routine with reachable=false, so the monitorexit slow paths call it via this
; baked absolute address (load + inttoptr + indirect call) rather than a PC-relative branch.
@SharedRuntime.complete_monitor_unlocking_C = external global i64

declare hotspotcc void @jeandle.clone_payload(ptr addrspace(1), i64, ptr addrspace(1), i64, i64, i1)

; Keep use to lately-used java operations, until it is lowered.
@llvm.used = appending addrspace(1) global [9 x ptr] [
  ptr @jeandle.card_table_barrier,
  ptr @jeandle.g1_pre_barrier,
  ptr @jeandle.g1_post_barrier,
  ptr @jeandle.pre_barrier,
  ptr @jeandle.post_barrier,
  ptr @jeandle.encode_heap_oop,
  ptr @jeandle.decode_heap_oop,
  ptr @jeandle.clone_payload,
  ptr @jeandle.membar_storestore
], section "llvm.metadata"

declare hotspotcc ptr addrspace(0) @jeandle.decode_klass(i32)
declare hotspotcc i32 @jeandle.encode_klass(ptr addrspace(0))

declare hotspotcc ptr addrspace(1) @jeandle.decode_heap_oop(ptr addrspace(3))
declare hotspotcc ptr addrspace(3) @jeandle.encode_heap_oop(ptr addrspace(1))

; Load klass pointer from oop
; lower-phase=1: survive JavaOperationLower(0) so the call reaches PEA, which
; folds it via foldLoadKlass (a virtual object's klass is a compile-time
; constant). At lower-phase=0 the body is expanded before PEA, exposing a raw
; load of the object's klass header; resolveAccess returns nullopt for header
; offsets and processLoad marks the object ineligible — defeating
; virtualization for any virtual receiver whose klass is inspected (e.g. the
; load_klass inside instanceof's expansion on a virtual receiver). Non-virtual
; receivers are unaffected: the call survives to JavaOperationLower(1) and the
; same body is inlined there.
define hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) nocapture %oop) noinline "lower-phase"="1" #0 {
  %klass_offset = load i32, ptr @oopDesc.klass_offset_in_bytes
  %klass_addr = getelementptr inbounds i8, ptr addrspace(1) %oop, i32 %klass_offset

  %use_compressed = load i1, ptr @VMOptions.UseCompressedClassPointers
  br i1 %use_compressed, label %compressed, label %uncompressed

compressed:
  %narrow = load atomic i32, ptr addrspace(1) %klass_addr unordered, align 4
  %decoded = call hotspotcc ptr addrspace(0) @jeandle.decode_klass(i32 %narrow)
  ret ptr addrspace(0) %decoded

uncompressed:
  %wide = load atomic ptr addrspace(0), ptr addrspace(1) %klass_addr unordered, align 8
  ret ptr addrspace(0) %wide
}

; Test whether a previously loaded dynamic Klass is exactly the expected Klass.
; Profile devirtualization shares one phase-1 load across all exact checks for
; a receiver. JavaType traces actual_klass back to that load for path-sensitive
; type propagation before JavaOperationLower(1) expands both operations.
define hotspotcc i1 @jeandle.check_exact_klass(ptr addrspace(0) nocapture %expected_klass, ptr addrspace(0) nocapture %actual_klass) noinline "lower-phase"="1" #0 {
  %is_exact = icmp eq ptr addrspace(0) %actual_klass, %expected_klass
  ret i1 %is_exact
}

; Load the reference Klass represented by a java.lang.Class mirror. Primitive
; mirrors contain a null Klass*. Keeping this as a phase-1 JavaOp lets
; ConstantFieldFolding answer the query from a constant mirror before exposing
; the VM-injected field load.
define hotspotcc ptr addrspace(0) @jeandle.load_mirror_klass(ptr addrspace(1) nocapture readonly %mirror) noinline "lower-phase"="1" #0 {
entry:
  %klass_offset = load i32, ptr @java_lang_Class.klass_offset
  %klass_addr = getelementptr inbounds i8, ptr addrspace(1) %mirror, i32 %klass_offset
  %klass = load atomic ptr addrspace(0), ptr addrspace(1) %klass_addr unordered, align 8
  ret ptr addrspace(0) %klass
}

; Load Klass::layout_helper from a Klass pointer. Keeping this as a phase-1
; JavaOp lets ConstantFieldFolding answer the query while the Klass is still a
; compile-time constant; unresolved queries lower to the ordinary VM load.
define hotspotcc i32 @jeandle.layout_helper(ptr addrspace(0) nocapture readonly %klass) noinline "lower-phase"="1" #0 {
entry:
  %layout_helper_offset = load i32, ptr @Klass.layout_helper_offset
  %layout_helper_addr = getelementptr inbounds i8, ptr addrspace(0) %klass, i32 %layout_helper_offset
  %layout_helper = load atomic i32, ptr addrspace(0) %layout_helper_addr unordered, align 4
  ret i32 %layout_helper
}

; Query a Klass' current initialization state. Keeping this opaque through
; phase 0 lets ConstantFieldFolding replace the query with true for a constant
; Klass already known by the VM to be initialized. Otherwise phase 1 lowers it
; to the runtime state load, preserving later class initialization.
define hotspotcc i1 @jeandle.klass_is_initialized(ptr addrspace(0) nocapture readonly %klass) noinline "lower-phase"="1" #0 {
entry:
  %init_state_offset = load i32, ptr @InstanceKlass.init_state_offset
  %init_state_addr = getelementptr inbounds i8, ptr addrspace(0) %klass, i32 %init_state_offset
  %init_state = load volatile i8, ptr addrspace(0) %init_state_addr, align 1
  %fully_initialized = load i8, ptr @InstanceKlass.fully_initialized
  %is_initialized = icmp eq i8 %init_state, %fully_initialized
  ret i1 %is_initialized
}

; Load the element Klass from an ObjArrayKlass.
define hotspotcc ptr addrspace(0) @jeandle.load_array_element_klass(ptr addrspace(0) nocapture %array_klass) noinline "lower-phase"="1" #0 {
  %element_klass_offset = load i32, ptr @ObjArrayKlass.element_klass_offset
  %element_klass_addr = getelementptr inbounds i8, ptr addrspace(0) %array_klass, i32 %element_klass_offset
  %element_klass = load atomic ptr addrspace(0), ptr addrspace(0) %element_klass_addr unordered, align 8
  ret ptr addrspace(0) %element_klass
}

; This is the slow path for subtype checking when the fast path fails.
define hotspotcc i1 @jeandle.check_klass_subtype_slow_path(ptr addrspace(0) nocapture %sub_klass, ptr addrspace(0) nocapture %super_klass) "lower-phase"="0" #0 {
entry:
  ; Load secondary_supers array and secondary_super_cache.
  %secondary_supers_offset = load i32, ptr @Klass.secondary_supers_offset
  %secondary_supers_addr = getelementptr inbounds i8, ptr addrspace(0) %sub_klass, i32 %secondary_supers_offset
  %secondary_supers = load atomic ptr addrspace(0), ptr addrspace(0) %secondary_supers_addr unordered, align 8

  ; Load length and base address of secondary_supers array.
  %length_offset = load i32, ptr @KlassArray.length_offset_in_bytes
  %length_addr = getelementptr inbounds i8, ptr addrspace(0) %secondary_supers, i32 %length_offset
  %length = load atomic i32, ptr addrspace(0) %length_addr unordered, align 4
  %base_offset = load i32, ptr @KlassArray.base_offset_in_bytes
  %base_addr = getelementptr inbounds i8, ptr addrspace(0) %secondary_supers, i32 %base_offset

  br label %scan_loop

scan_loop:
  ; Scan the secondary_supers until the super_klass is found, if not found, then return false.
  %index = phi i32 [0, %entry], [%next_index, %continue_loop]
  %current_ptr = phi ptr [%base_addr, %entry], [%next_ptr, %continue_loop]

  ; Check loop end
  %scan_done = icmp eq i32 %index, %length
  br i1 %scan_done, label %return_false, label %loop_body

loop_body:
  %current_klass = load atomic ptr addrspace(0), ptr addrspace(0) %current_ptr unordered, align 8
  %is_match = icmp eq ptr addrspace(0) %super_klass, %current_klass
  br i1 %is_match, label %return_true, label %continue_loop

continue_loop:
  %next_index = add i32 %index, 1
  %next_ptr = getelementptr ptr, ptr addrspace(0) %base_addr, i32 %next_index
  br label %scan_loop

return_true:
  ; Success, cache the super klass we found.
  %secondary_super_cache_offset = load i32, ptr @Klass.secondary_super_cache_offset
  %secondary_super_cache_addr = getelementptr inbounds i8, ptr addrspace(0) %sub_klass, i32 %secondary_super_cache_offset
  store atomic ptr addrspace(0) %super_klass, ptr addrspace(0) %secondary_super_cache_addr unordered, align 8

  ret i1 true

return_false:
  ret i1 false
}

; Check if the sub_klass extends from the super_klass using both primary and secondary supers.
; Fast path: checks primary super chain.
; Slow path: scans secondary supers array if needed.
define hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) nocapture %sub_klass, ptr addrspace(0) nocapture %super_klass) "lower-phase"="1" #0 {
entry:
  %is_same_klass = icmp eq ptr addrspace(0) %sub_klass, %super_klass
  br i1 %is_same_klass, label %return_true, label %check_primary_supers

check_primary_supers:
  ; Load super_check_offset_offset
  %super_check_offset_offset = load i32, ptr @Klass.super_check_offset_offset
  %super_check_offset_addr = getelementptr inbounds i8, ptr addrspace(0) %super_klass, i32 %super_check_offset_offset
  %super_check_offset = load atomic i32, ptr addrspace(0) %super_check_offset_addr unordered, align 4

  ; Load super_check klass from _primary_supers of the sub_klass.
  %super_check_addr = getelementptr inbounds i8, ptr addrspace(0) %sub_klass, i32 %super_check_offset
  %super_check = load atomic ptr addrspace(0), ptr addrspace(0) %super_check_addr unordered, align 8

  %is_super_match = icmp eq ptr %super_klass, %super_check
  br i1 %is_super_match, label %return_true, label %check_secondary_supers

check_secondary_supers:
  ; Check if there are secondary supers.
  %secondary_super_cache_offset = load i32, ptr @Klass.secondary_super_cache_offset
  %has_secondary = icmp eq i32 %super_check_offset, %secondary_super_cache_offset
  br i1 %has_secondary, label %slow_path, label %return_false

slow_path:
  %is_subtype_slow = call hotspotcc i1 @jeandle.check_klass_subtype_slow_path(ptr addrspace(0) %sub_klass, ptr addrspace(0) %super_klass)
  br i1 %is_subtype_slow, label %return_true, label %return_false

return_true:
  ret i1 true
return_false:
  ret i1 false
}

define hotspotcc i1 @jeandle.check_instanceof(ptr addrspace(0) nocapture %super_klass, ptr addrspace(1) nocapture nonnull %oop) noinline "lower-phase"="1" #0 {
entry:
  %sub_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) nonnull %oop)
  %is_subtype = call hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) %sub_klass, ptr addrspace(0) %super_klass)
  ret i1 %is_subtype
}

; Implementation of Java instanceof operation.
define hotspotcc i32 @jeandle.instanceof(ptr addrspace(0) nocapture %super_klass, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" #0 {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_false, label %check_subtype

return_false:
  ret i32 0

check_subtype:
  %is_subtype = call hotspotcc i1 @jeandle.check_instanceof(ptr addrspace(0) %super_klass, ptr addrspace(1) nocapture nonnull %oop)
  %is_subtype_ext = zext i1 %is_subtype to i32
  ret i32 %is_subtype_ext
}

declare hotspotcc ptr addrspace(1) @new_instance(ptr, ptr)
declare hotspotcc void @jeandle.zero_half_heap_word(ptr addrspace(1) align 4)
declare hotspotcc void @jeandle.zero_heap_words(ptr addrspace(1) align 8, i32)
declare hotspotcc void @jeandle.allocation_prefetch(ptr addrspace(1), ptr addrspace(1), i32)

; Implementation of Java new object
define private hotspotcc ptr addrspace(1) @jeandle.new_instance(ptr %klass, i32 %size_in_bytes, i1 %initial_slow_test) noinline "lower-phase"="1" "jeandle.not-guaranteed-safepoint" {
entry:
  ; The caller may already know that this allocation requires the runtime.
  ; Share that path with a TLAB exhaustion instead of creating another call.
  br i1 %initial_slow_test, label %alloc_slow_path, label %check_tlab

check_tlab:
  %use_tlab = load i1, ptr @VMOptions.UseTLAB
  br i1 %use_tlab, label %test_tlab, label %alloc_slow_path

test_tlab:
  %tlab_top_offset = load i64, ptr @JavaThread.tlab_top_offset
  %tlab_end_offset = load i64, ptr @JavaThread.tlab_end_offset

  %tlab_top_ptr = inttoptr i64 %tlab_top_offset to ptr addrspace(2)
  %tlab_end_ptr = inttoptr i64 %tlab_end_offset to ptr addrspace(2)

  %tlab_old_top = load ptr addrspace(1), ptr addrspace(2) %tlab_top_ptr, align 8
  %tlab_end = load ptr addrspace(1), ptr addrspace(2) %tlab_end_ptr, align 8

  %tlab_new_top = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %size_in_bytes
  %if_tlab_full = icmp uge ptr addrspace(1) %tlab_new_top, %tlab_end
  br i1 %if_tlab_full, label %alloc_slow_path, label %alloc_fast_path

alloc_slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %slow_alloc_obj = call hotspotcc ptr addrspace(1) @new_instance(ptr %klass, ptr %current_thread) [ "deopt"() ]
  br label %return_block

alloc_fast_path:
  %instance_prefetch_lines = load i32, ptr @VMOptions.AllocateInstancePrefetchLines
  call hotspotcc void @jeandle.allocation_prefetch(ptr addrspace(1) %tlab_old_top,
                                                    ptr addrspace(1) %tlab_new_top,
                                                    i32 %instance_prefetch_lines)
  store ptr addrspace(1) %tlab_new_top, ptr addrspace(2) %tlab_top_ptr, align 8
  %mark_word_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %mark_word_offset

  %klass_offset = load i32, ptr @oopDesc.klass_offset_in_bytes
  %klass_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %klass_offset

  %prototype_value = load i64, ptr @markWord.prototype_value

  store i64 %prototype_value, ptr addrspace(1) %mark_word_addr, align 8

  %use_compressed_klass = load i1, ptr @VMOptions.UseCompressedClassPointers
  br i1 %use_compressed_klass, label %store_narrow_klass, label %store_wide_klass

store_narrow_klass:
  %narrow_klass = call hotspotcc i32 @jeandle.encode_klass(ptr %klass)
  store i32 %narrow_klass, ptr addrspace(1) %klass_addr, align 4
  br label %post_klass_store

store_wide_klass:
  store ptr %klass, ptr addrspace(1) %klass_addr, align 8
  br label %post_klass_store

post_klass_store:
  %zero_tlab = load i1, ptr @VMOptions.ZeroTLAB
  %skip_clear = and i1 %use_tlab, %zero_tlab
  br i1 %skip_clear, label %initialization_membar, label %clear_memory

clear_memory:
  %base_offset = load i32, ptr @instanceOopDesc.base_offset_in_bytes
  %base_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %base_offset
  %payload_size = sub i32 %size_in_bytes, %base_offset
  %base_low_bits = and i32 %base_offset, 7
  %needs_4_byte_head = icmp eq i32 %base_low_bits, 4
  br i1 %needs_4_byte_head, label %clear_4_byte_head, label %clear_words

clear_4_byte_head:
  call hotspotcc void @jeandle.zero_half_heap_word(ptr addrspace(1) %base_addr)
  %aligned_base_addr = getelementptr i8, ptr addrspace(1) %base_addr, i32 4
  %aligned_payload_size = sub i32 %payload_size, 4
  br label %clear_words

clear_words:
  %clear_base_addr = phi ptr addrspace(1) [ %base_addr, %clear_memory ], [ %aligned_base_addr, %clear_4_byte_head ]
  %clear_payload_size = phi i32 [ %payload_size, %clear_memory ], [ %aligned_payload_size, %clear_4_byte_head ]
  %payload_words = lshr exact i32 %clear_payload_size, 3
  call hotspotcc void @jeandle.zero_heap_words(ptr addrspace(1) %clear_base_addr, i32 %payload_words)
  br label %initialization_membar

initialization_membar:
  ; Publish object initialization with a StoreStore barrier. This orders the
  ; header and zeroing stores without imposing the LoadStore part of release.
  call hotspotcc void @jeandle.membar_storestore()
  br label %return_block

return_block:
  %obj = phi ptr addrspace(1) [ %tlab_old_top, %initialization_membar ], [ %slow_alloc_obj, %alloc_slow_path ]
  ret ptr addrspace(1) %obj
}

; Implementation of Java arraylength operation.
; lower-phase=1: survive JavaOperationLower(0) so the call reaches PEA, which
; folds it via foldArrayLength (a virtual array's length is a compile-time
; constant). The frontend emits this op both for the arraylength bytecode and
; for bounds checks, so every virtual array's element access benefits.
; processLoad's length-offset fold remains as the fallback for any raw
; length-header load (e.g. produced by hand-written IR). Non-virtual receivers
; are unaffected: the call survives to JavaOperationLower(1) and the same body
; is inlined there.
define hotspotcc i32 @jeandle.arraylength(ptr addrspace(1) nocapture readonly %array_oop) noinline "lower-phase"="1" #0 {
entry:
  %length_offset = load i32, ptr @arrayOopDesc.length_offset_in_bytes
  %length_addr = getelementptr inbounds i8, ptr addrspace(1) %array_oop, i32 %length_offset
  %length = load atomic i32, ptr addrspace(1) %length_addr unordered, align 4, !range !0
  ret i32 %length
}

declare hotspotcc ptr @jeandle.current_thread()
declare hotspotcc ptr addrspace(1) @new_array(ptr, i32, ptr)
declare hotspotcc void @SharedRuntime_register_finalizer(ptr, ptr addrspace(1))

; Shared pseudo operation for System.arraycopy, Arrays.copyOf[Range] and clone.
; Array copies use element indices/counts; instance and raw-array payload clones
; use byte offsets, a Java-long payload count and object byte sizes instead.
; This is not a JavaOp:
; CopySpecialization must expand every call before JavaOperationLower(1),
; so this declaration must not carry a lower-phase attribute.
declare hotspotcc void @jeandle.copy(
    ptr addrspace(1), i64, ptr addrspace(1), i64, i64,
    ptr addrspace(0), ptr addrspace(0), i64, i64
)

; Slow-path runtime routines for monitor JavaOps. The LOCKING routine is an
; indirect routine called via a JIT stub (hotspotcc); declared here and
; resolved at link time via a routine-call reloc to the stub. The UNLOCKING
; routine is a direct leaf (reachable=false) whose far C++ address cannot be
; reached by a PC-relative jump, so it is NOT declared here; the monitorexit
; slow paths call it via a baked absolute-address global
; (@SharedRuntime.complete_monitor_unlocking_C) defined in
; jeandleRuntimeDefinedJavaOps.cpp — same pattern as the G1 barrier globals.
declare hotspotcc void @SharedRuntime_complete_monitor_locking_C(ptr addrspace(1), ptr addrspace(0), ptr)

; IR-level runtime target that RewriteStatepointsForGC lowers each
; llvm.experimental.deoptimize call onto (RewriteStatepointsForGC.cpp). Declared
; here rather than synthesized per-method so every compilation module starts with
; it. The `hotspotcc` convention (= CallingConv::Hotspot_JIT) MUST match the
; uncommon_trap_blob entry resolved at JIT link time (jeandleRuntimeRoutine.hpp);
; do not simplify to the default CC -- RS4GC would otherwise synthesize a
; default-CC declaration and the lowered call would mismatch the runtime entry.
declare hotspotcc void @__llvm_deoptimize(i32)

; Arraycopy stub entry points resolved by jeandleRuntimeRoutine.hpp.
declare hotspotcc void @SharedRuntime_slow_arraycopy_C(ptr addrspace(1), i32, ptr addrspace(1), i32, i32, ptr)
declare i32 @StubRoutines_generic_arraycopy(ptr addrspace(1), i32, ptr addrspace(1), i32, i32)
declare i32 @StubRoutines_checkcast_arraycopy(ptr addrspace(1), ptr addrspace(1), i64, i64, ptr addrspace(0))
declare i32 @StubRoutines_checkcast_arraycopy_uninit(ptr addrspace(1), ptr addrspace(1), i64, i64, ptr addrspace(0))
declare void @StubRoutines_oop_arraycopy(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_oop_arraycopy_uninit(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_arrayof_oop_arraycopy(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_arrayof_oop_arraycopy_uninit(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_oop_disjoint_arraycopy(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_oop_disjoint_arraycopy_uninit(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_arrayof_oop_disjoint_arraycopy(ptr addrspace(1), ptr addrspace(1), i64)
declare void @StubRoutines_arrayof_oop_disjoint_arraycopy_uninit(ptr addrspace(1), ptr addrspace(1), i64)

; jbyte
declare void @StubRoutines_jbyte_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jbyte_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_jbyte_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jbyte_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind

; jshort
declare void @StubRoutines_jshort_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jshort_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_jshort_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jshort_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind

; jint
declare void @StubRoutines_jint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_jint_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jint_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind

; jlong
declare void @StubRoutines_jlong_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jlong_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_jlong_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind
declare void @StubRoutines_arrayof_jlong_disjoint_arraycopy(
    ptr addrspace(1) readonly captures(none) %src,
    ptr addrspace(1) writeonly captures(none) %dest,
    i64 %count
) memory(argmem: readwrite) nounwind


; Unified array allocation JavaOp.  Both bytecode (newarray/anewarray) and intrinsic
; (_newArray / Array.newInstance) paths call this function.
; LLVM passes identify array allocation by matching on this function name.
; The trailing flags serve tightly coupled initializers such as the string
; toBytes copy: %uninitialized skips the bulk payload zeroing because the
; caller overwrites every byte before the array can escape, and
; %defer_initialization_membar moves the fast path's publication barrier to
; that initializer, which re-issues it once the payload is complete.
define private hotspotcc ptr addrspace(1) @jeandle.new_array(ptr %array_klass, i32 %length, i32 %size_in_bytes, i32 %header_size, i32 %length_limit, i1 %uninitialized, i1 %defer_initialization_membar) noinline "lower-phase"="1" "jeandle.not-guaranteed-safepoint" {
entry:
  ; Fast-path length cap. %length_limit is a size-derived bound (FastAllocateSizeLimit, scaled
  ; by element size on the caller side) chosen so %size_in_bytes cannot overflow i32 -- it is
  ; NOT arrayOopDesc::max_array_length(), which is ~max_jint on LP64 and would let a large
  ; length wrap the size computation. The unsigned compare also routes a negative %length
  ; (e.g. -1) to the slow path, where the runtime raises NegativeArraySizeException.
  %too_long = icmp ugt i32 %length, %length_limit
  br i1 %too_long, label %array_slow_path, label %check_tlab

check_tlab:
  %use_tlab = load i1, ptr @VMOptions.UseTLAB
  br i1 %use_tlab, label %test_tlab, label %array_slow_path

test_tlab:
  %tlab_top_offset = load i64, ptr @JavaThread.tlab_top_offset
  %tlab_end_offset = load i64, ptr @JavaThread.tlab_end_offset

  %tlab_top_ptr = inttoptr i64 %tlab_top_offset to ptr addrspace(2)
  %tlab_end_ptr = inttoptr i64 %tlab_end_offset to ptr addrspace(2)

  %tlab_old_top = load ptr addrspace(1), ptr addrspace(2) %tlab_top_ptr, align 8
  %tlab_end = load ptr addrspace(1), ptr addrspace(2) %tlab_end_ptr, align 8

  %tlab_new_top = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %size_in_bytes
  %if_tlab_full = icmp uge ptr addrspace(1) %tlab_new_top, %tlab_end
  br i1 %if_tlab_full, label %array_slow_path, label %array_fast_path

array_slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %slow_array_oop = call hotspotcc ptr addrspace(1) @new_array(ptr %array_klass, i32 %length, ptr %current_thread) [ "deopt"() ]
  br label %array_return

array_fast_path:
  %array_prefetch_lines = load i32, ptr @VMOptions.AllocatePrefetchLines
  call hotspotcc void @jeandle.allocation_prefetch(ptr addrspace(1) %tlab_old_top,
                                                    ptr addrspace(1) %tlab_new_top,
                                                    i32 %array_prefetch_lines)
  store ptr addrspace(1) %tlab_new_top, ptr addrspace(2) %tlab_top_ptr, align 8

  ; Header: mark word, klass pointer, length. No inter-field barriers; the
  ; trailing StoreStore barrier publishes the whole object as a unit.
  %mark_word_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %mark_word_offset

  %klass_offset = load i32, ptr @oopDesc.klass_offset_in_bytes
  %klass_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %klass_offset

  %length_offset = load i32, ptr @arrayOopDesc.length_offset_in_bytes
  %length_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %length_offset

  %prototype_value = load i64, ptr @markWord.prototype_value

  store i64 %prototype_value, ptr addrspace(1) %mark_word_addr, align 8
  %array_use_compressed_klass = load i1, ptr @VMOptions.UseCompressedClassPointers
  br i1 %array_use_compressed_klass, label %array_store_narrow_klass, label %array_store_wide_klass

array_store_narrow_klass:
  %array_narrow_klass = call hotspotcc i32 @jeandle.encode_klass(ptr %array_klass)
  store i32 %array_narrow_klass, ptr addrspace(1) %klass_addr, align 4
  br label %array_post_klass_store

array_store_wide_klass:
  store ptr %array_klass, ptr addrspace(1) %klass_addr, align 8
  br label %array_post_klass_store

array_post_klass_store:
  ; Length is always a 4-byte signed int regardless of UseCompressedClassPointers, but the
  ; length offset itself is CCP-aware on the C++ side (arrayOopDesc::length_offset_in_bytes
  ; shifts when narrow klass is stored), so this single store covers both layouts.
  store i32 %length, ptr addrspace(1) %length_addr, align 4

  %zero_tlab = load i1, ptr @VMOptions.ZeroTLAB
  %tlab_is_zeroed = and i1 %use_tlab, %zero_tlab
  ; A tightly coupled initializer overwrites the payload in full before the
  ; array escapes, so its allocation needs no bulk zeroing here.
  %skip_clear = or i1 %uninitialized, %tlab_is_zeroed
  br i1 %skip_clear, label %array_after_payload_init, label %array_clear_memory

array_clear_memory:
  ; Array payloads are HeapWord-aligned. Reuse the platform-aware zeroing
  ; JavaOp used by new_instance for constant and variable word counts.
  %base_addr = getelementptr i8, ptr addrspace(1) %tlab_old_top, i32 %header_size
  %payload_size = sub i32 %size_in_bytes, %header_size
  %payload_words = lshr exact i32 %payload_size, 3
  call hotspotcc void @jeandle.zero_heap_words(
      ptr addrspace(1) %base_addr, i32 %payload_words)
  br label %array_after_payload_init

array_after_payload_init:
  ; Only a tightly coupled initialization may defer this fast-path barrier.
  ; The initializer re-issues it after its payload stores; the normal VM
  ; slow allocation path above always publishes on its own.
  br i1 %defer_initialization_membar, label %array_return, label %array_init_membar

array_init_membar:
  ; Publish array initialization with a StoreStore barrier.
  call hotspotcc void @jeandle.membar_storestore()
  br label %array_return

array_return:
  %array_oop = phi ptr addrspace(1) [ %tlab_old_top, %array_after_payload_init ], [ %tlab_old_top, %array_init_membar ], [ %slow_array_oop, %array_slow_path ]
  ret ptr addrspace(1) %array_oop
}

; Declaration of Java card table barrier.
; Phase 9 barrier JavaOps are lowered after RS4GC. They materialize raw
; addresses derived from oops, such as card-table addresses, which RS4GC does
; not track. Keeping them opaque until phase 9 prevents O3 from reusing those
; raw derived addresses across safepoints.
declare hotspotcc void @jeandle.card_table_barrier(ptr addrspace(1) %addr)

define private hotspotcc void @jeandle.g1_satb_enqueue(ptr addrspace(1) %pre_val) "lower-phase"="1" #0 {
entry:
  %index_offset = load i32, ptr @G1ThreadLocalData.satb_mark_queue_index_offset
  %buffer_offset = load i32, ptr @G1ThreadLocalData.satb_mark_queue_buffer_offset
  %index_adr = inttoptr i32 %index_offset to ptr addrspace(2)
  %buffer_adr = inttoptr i32 %buffer_offset to ptr addrspace(2)
  %index = load i64, ptr addrspace(2) %index_adr
  %buffer = load ptr, ptr addrspace(2) %buffer_adr
  %is_zero = icmp eq i64 %index, 0
  br i1 %is_zero, label %buffer_is_full, label %store_in_buffer

buffer_is_full:
  %callee_addr = load i64, ptr @G1BarrierSetRuntime.write_ref_field_pre_entry
  %callee = inttoptr i64 %callee_addr to ptr
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call void %callee(ptr addrspace(1) %pre_val, ptr %current_thread) #0
  br label %done

store_in_buffer:
  %wordsize = load i64, ptr @WordSize
  %next_index = sub i64 %index, %wordsize
  %log_addr = getelementptr inbounds i8, ptr %buffer, i64 %next_index
  store atomic ptr addrspace(1) %pre_val, ptr %log_addr unordered, align 8
  store atomic i64 %next_index, ptr addrspace(2) %index_adr unordered, align 8
  br label %done

done:
  ret void
}

; Implementation of Java g1 pre barrier.
define private hotspotcc void @jeandle.g1_pre_barrier(ptr addrspace(1) %addr) noinline "lower-phase"="9" #0 {
entry:
  %marking_offset = load i32, ptr @G1ThreadLocalData.satb_mark_queue_active_offset
  %marking_adr = inttoptr i32 %marking_offset to ptr addrspace(2)
  %marking = load i8, ptr addrspace(2) %marking_adr
  %is_not_marking = icmp eq i8 %marking, 0
  br i1 %is_not_marking, label %done, label %load_pre_value

done:
  ret void

load_pre_value:
  %use_compressed = load i1, ptr @VMOptions.UseCompressedOops
  br i1 %use_compressed, label %compressed, label %uncompressed

compressed:
  %narrow_val = load atomic ptr addrspace(3), ptr addrspace(1) %addr unordered, align 4
  %narrow_is_null = icmp eq ptr addrspace(3) %narrow_val, null
  br i1 %narrow_is_null, label %done, label %decode_narrow

decode_narrow:
  %pre_val_c = addrspacecast ptr addrspace(3) %narrow_val to ptr addrspace(1)
  br label %enqueue
  
uncompressed:
  %pre_val_u = load atomic ptr addrspace(1), ptr addrspace(1) %addr unordered, align 8
  %wide_is_null = icmp eq ptr addrspace(1) %pre_val_u, null
  br i1 %wide_is_null, label %done, label %enqueue

enqueue:
  %pre_val = phi ptr addrspace(1) [ %pre_val_c, %decode_narrow ], [ %pre_val_u, %uncompressed ]
  call hotspotcc void @jeandle.g1_satb_enqueue(ptr addrspace(1) %pre_val)
  ret void
}

; Implementation of Java g1 pre barrier loaded.
define private hotspotcc void @jeandle.g1_pre_barrier_loaded(ptr addrspace(1) %pre_val) noinline "lower-phase"="9" #0 {
entry:
  %marking_offset = load i32, ptr @G1ThreadLocalData.satb_mark_queue_active_offset
  %marking_adr = inttoptr i32 %marking_offset to ptr addrspace(2)
  %marking = load i8, ptr addrspace(2) %marking_adr
  %is_not_marking = icmp eq i8 %marking, 0
  br i1 %is_not_marking, label %done, label %check_null

done:
  ret void

check_null:
  %is_null = icmp eq ptr addrspace(1) %pre_val, null
  br i1 %is_null, label %done, label %enqueue

enqueue:
  call hotspotcc void @jeandle.g1_satb_enqueue(ptr addrspace(1) %pre_val)
  ret void
}

; Implementation of Java g1 post barrier.
define private hotspotcc void @jeandle.g1_post_barrier(ptr addrspace(1) %addr, ptr addrspace(1) captures(none) %oop) noinline "lower-phase"="9" #0 {
entry:
  %index_offset = load i32, ptr @G1ThreadLocalData.dirty_card_queue_index_offset
  %buffer_offset = load i32, ptr @G1ThreadLocalData.dirty_card_queue_buffer_offset
  %index_adr = inttoptr i32 %index_offset to ptr addrspace(2)
  %buffer_adr = inttoptr i32 %buffer_offset to ptr addrspace(2)
  %addr.int = ptrtoint ptr addrspace(1) %addr to i64
  %card_shift = load i64, ptr @CardTable.card_shift
  %card_offset = lshr i64 %addr.int, %card_shift
  %ci_card_table_address = load i64, ptr @ci_card_table_address
  %card_base_addr = inttoptr i64 %ci_card_table_address to ptr
  %card_adr = getelementptr inbounds i8, ptr %card_base_addr, i64 %card_offset
  %oop.int = ptrtoint ptr addrspace(1) %oop to i64
  %xor_val = xor i64 %addr.int, %oop.int
  %hr_grain_bytes = load i64, ptr @HeapRegion.LogOfHRGrainBytes
  %xor_res = lshr i64 %xor_val, %hr_grain_bytes
  %is_zero = icmp eq i64 %xor_res, 0
  br i1 %is_zero, label %post_barrier_done, label %same_region_filtered

post_barrier_done:
  ret void

same_region_filtered:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %post_barrier_done, label %val_nullptr_filtered

val_nullptr_filtered:
  %card_value = load atomic i8, ptr %card_adr unordered, align 1
  %young_card = load i8, ptr @G1CardTable.g1_young_card_val
  %is_young = icmp eq i8 %card_value, %young_card
  br i1 %is_young, label %post_barrier_done, label %young_card_filtered

young_card_filtered:
  ; TODO: fence seq_cst is overly strict here. We only need StoreStore
  ; ordering between the oop store and the card table read/write
  fence seq_cst
  %card_val_reload = load atomic i8, ptr %card_adr unordered, align 1
  %dirty_card = load i8, ptr @G1CardTable.dirty_card_val
  %is_dirty = icmp eq i8 %card_val_reload, %dirty_card
  br i1 %is_dirty, label %post_barrier_done, label %store_dirty_block

store_dirty_block:
  store atomic i8 0, ptr %card_adr release, align 1
  %index = load i64, ptr addrspace(2) %index_adr
  %is_full = icmp eq i64 %index, 0
  br i1 %is_full, label %buffer_is_full, label %store_in_buffer

buffer_is_full:
  %callee_addr = load i64, ptr @G1BarrierSetRuntime.write_ref_field_post_entry
  %callee = inttoptr i64 %callee_addr to ptr
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call void %callee(ptr %card_adr, ptr %current_thread) #0
  br label %post_barrier_done

store_in_buffer:
  %wordsize = load i64, ptr @WordSize
  %next_index = sub i64 %index, %wordsize
  %buffer = load ptr, ptr addrspace(2) %buffer_adr
  %log_addr = getelementptr inbounds i8, ptr %buffer, i64 %next_index
  store atomic ptr %card_adr, ptr %log_addr unordered, align 8
  store atomic i64 %next_index, ptr addrspace(2) %index_adr unordered, align 8
  br label %post_barrier_done
}

; Declaration of Java pre barrier.
declare hotspotcc void @jeandle.pre_barrier(ptr addrspace(1) %addr)

; Declaration of Java post barrier.
declare hotspotcc void @jeandle.post_barrier(ptr addrspace(1) %addr, ptr addrspace(1) nocapture %oop)

; Identity marker for Java type narrowing.
; Semantically returns the same oop. The java-klass return attribute is attached
; at call sites, not here.
define hotspotcc ptr addrspace(1) @jeandle.assume_java_type(ptr addrspace(1) %oop) noinline "lower-phase"="1" #0 {
entry:
  ret ptr addrspace(1) %oop
}

; Implementation of Java checkcast operation
define hotspotcc i1 @jeandle.checkcast(ptr addrspace(0) nocapture %super_klass, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" #0 {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_true, label %check_subtype

return_true:
  ret i1 true

check_subtype:
  %is_subtype = call hotspotcc i1 @jeandle.check_instanceof(ptr addrspace(0) %super_klass, ptr addrspace(1) nocapture nonnull %oop)

  ret i1 %is_subtype
}

; Implementation of array store check operation
; lower-phase=1: survive JavaOperationLower(0) so the call reaches PEA, which
; folds it via foldArrayStoreCheck (eliding it for provably-compatible values
; and for primitive arrays). At lower-phase=0 the body is expanded before PEA,
; exposing a raw load of the array's klass header; resolveAccess returns
; nullopt for header offsets and processLoad marks the array ineligible —
; defeating array virtualization for EVERY aastore into an Object[] (the
; frontend emits this check for every reference array store).
define hotspotcc i1 @jeandle.array_store_check(ptr addrspace(1) nocapture %oop, ptr addrspace(1) nocapture %array_oop) noinline "lower-phase"="1" #0 {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_true, label %check_subtype

return_true:
  ret i1 true

check_subtype:
  %array_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %array_oop)
  %element_klass = call hotspotcc ptr addrspace(0) @jeandle.load_array_element_klass(ptr addrspace(0) %array_klass)
  %is_subtype = call hotspotcc i1 @jeandle.check_instanceof(ptr addrspace(0) %element_klass, ptr addrspace(1) nocapture nonnull %oop)

  ret i1 %is_subtype
}

; Implementation of Java idiv operation
define hotspotcc i32 @jeandle.idiv(i32 %dividend, i32 %divisor) noinline "lower-phase"="0" #0 {
entry:
  ; Check if dividend == Integer.MIN_VALUE (-2147483648)
  %is_min_int = icmp eq i32 %dividend, -2147483648
  br i1 %is_min_int, label %check_divisor, label %normal_idiv

check_divisor:
  %is_minus_one = icmp eq i32 %divisor, -1
  br i1 %is_minus_one, label %return_min_int, label %normal_idiv

return_min_int:
  ret i32 -2147483648

normal_idiv:
  %result = sdiv i32 %dividend, %divisor
  ret i32 %result
}

; Implementation of Java irem operation
define hotspotcc i32 @jeandle.irem(i32 %dividend, i32 %divisor) noinline "lower-phase"="0" #0 {
entry:
  ; Check if dividend == Integer.MIN_VALUE (-2147483648)
  %is_min_int = icmp eq i32 %dividend, -2147483648
  br i1 %is_min_int, label %check_divisor, label %normal_irem

check_divisor:
  %is_minus_one = icmp eq i32 %divisor, -1
  br i1 %is_minus_one, label %return_zero, label %normal_irem

return_zero:
  ret i32 0

normal_irem:
  %result = srem i32 %dividend, %divisor
  ret i32 %result
}

; Implementation of Java ldiv operation
define hotspotcc i64 @jeandle.ldiv(i64 %dividend, i64 %divisor) noinline "lower-phase"="0" #0 {
entry:
  ; Check if dividend == Long.MIN_VALUE (-9223372036854775808)
  %is_min_long = icmp eq i64 %dividend, -9223372036854775808
  br i1 %is_min_long, label %check_divisor, label %normal_ldiv

check_divisor:
  %is_minus_one = icmp eq i64 %divisor, -1
  br i1 %is_minus_one, label %return_min_long, label %normal_ldiv

return_min_long:
  ret i64 -9223372036854775808

normal_ldiv:
  %result = sdiv i64 %dividend, %divisor
  ret i64 %result
}

; Implementation of Java lrem operation
define hotspotcc i64 @jeandle.lrem(i64 %dividend, i64 %divisor) noinline "lower-phase"="0" #0 {
entry:
  ; Check if dividend == Long.MIN_VALUE (-9223372036854775808)
  %is_min_long = icmp eq i64 %dividend, -9223372036854775808
  br i1 %is_min_long, label %check_divisor, label %normal_lrem

check_divisor:
  %is_minus_one = icmp eq i64 %divisor, -1
  br i1 %is_minus_one, label %return_zero, label %normal_lrem

return_zero:
  ret i64 0

normal_lrem:
  %result = srem i64 %dividend, %divisor
  ret i64 %result
}

; Check if the object is value based
; lower-phase=1: survive JavaOperationLower(0) so the call reaches PEA, which
; folds it via foldCheckIfValueBased (collapsing it to a constant when the
; receiver's exact klass is known — always the case for a virtual object).
; At lower-phase=0 the body's raw klass-header load marks the receiver
; ineligible, defeating monitor elision for every value-based-candidate
; receiver.
define hotspotcc i1 @jeandle.check_if_value_based(ptr addrspace(1) nocapture %obj) "lower-phase"="1" #0 {
entry:
  %obj_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %obj)
  %access_flags_offset = load i32, ptr @Klass.access_flags_offset
  %access_flags_addr = getelementptr inbounds i8, ptr addrspace(0) %obj_klass, i32 %access_flags_offset
  %access_flags = load i32, ptr addrspace(0) %access_flags_addr
  %is_value_based_mask = load i32, ptr @JVM_ACC_IS_VALUE_BASED_CLASS
  %masked_value = and i32 %access_flags, %is_value_based_mask
  %is_value_based = icmp ne i32 %masked_value, 0
  ret i1 %is_value_based
}

; Register finalizer for an object only when its exact klass requires it.
; lower-phase=1: survive JavaOperationLower(0) so the call reaches PEA, which
; folds it via foldRegisterFinalizerIfNeeded (eliding it for non-finalizer
; klasses). At lower-phase=0 the body is expanded before PEA, exposing a raw
; load of the object's klass header; resolveAccess returns nullopt for header
; offsets and processLoad markIneligible's the object — defeating virtualization
; for EVERY allocation (every alloc has this finalizer check).
define hotspotcc void @jeandle.register_finalizer_if_needed(ptr addrspace(1) %obj) noinline "lower-phase"="1" {
entry:
  %obj_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %obj)
  %access_flags_offset = load i32, ptr @Klass.access_flags_offset
  %access_flags_addr = getelementptr inbounds i8, ptr addrspace(0) %obj_klass, i32 %access_flags_offset
  %access_flags = load i32, ptr addrspace(0) %access_flags_addr
  %has_finalizer_mask = load i32, ptr @JVM_ACC_HAS_FINALIZER
  %masked_value = and i32 %access_flags, %has_finalizer_mask
  %has_finalizer = icmp ne i32 %masked_value, 0
  br i1 %has_finalizer, label %register_finalizer, label %return

register_finalizer:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call hotspotcc void @SharedRuntime_register_finalizer(ptr %current_thread, ptr addrspace(1) %obj)
  br label %return

return:
  ret void
}

; Check if the lock is inflated
define hotspotcc i1 @jeandle.check_inflated(i64 %mark_word) "lower-phase"="0" #0 {
entry:
  %markWord_monitor_value = load i64, ptr @markWord.monitor_value
  %masked_value = and i64 %mark_word, %markWord_monitor_value
  %is_inflated = icmp ne i64 %masked_value, 0
  ret i1 %is_inflated
}

; Try to acquire the monitor lock when the lock is inflated
define hotspotcc i1 @jeandle.try_acquire_monitor_lock(i64 %mark_word, ptr addrspace(0) nocapture %lock) "lower-phase"="0" #0 {
entry:
  %monitor_ptr = inttoptr i64 %mark_word to ptr
  %owner_offset_no_monitor_value = load i32, ptr @ObjectMonitor.owner_offset_no_monitor_value
  %owner_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %owner_offset_no_monitor_value
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %current_thread_as_int = ptrtoint ptr %current_thread to i64
  %monitor_cas = cmpxchg ptr %owner_addr, i64 0, i64 %current_thread_as_int acq_rel monotonic, align 8
  %displaced_header_offset = load i32, ptr @BasicLock.displaced_header_offset_in_bytes
  %destory_lock_record_addr = getelementptr inbounds i8, ptr %lock, i32 %displaced_header_offset
  %unused_mark_value = load i64, ptr @markWord.unused_mark_value
  store i64 %unused_mark_value, ptr %destory_lock_record_addr, align 8
  %monitor_acquied = extractvalue { i64, i1 } %monitor_cas, 1
  br i1 %monitor_acquied, label %return_true, label %check_recursive_monitor

check_recursive_monitor:
  %monitor_owner = extractvalue { i64, i1 } %monitor_cas, 0
  %is_recursive_monitor_lock = icmp eq i64 %monitor_owner, %current_thread_as_int
  br i1 %is_recursive_monitor_lock, label %increase_recursions, label %return_false

increase_recursions:
  %recursions_offset_no_monitor_value = load i32, ptr @ObjectMonitor.recursions_offset_no_monitor_value
  %recursions_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %recursions_offset_no_monitor_value
  %recursions = load i64, ptr %recursions_addr, align 8
  %new_recursions = add i64 %recursions, 1
  store i64 %new_recursions, ptr %recursions_addr, align 8
  br label %return_true

return_true:
  ret i1 true

return_false:
  ret i1 false
}

; Increment held_monitor_count in JavaThread
define hotspotcc void @jeandle.increment_lock_count() "lower-phase"="0" #0 {
entry:
  %held_monitor_count_offset = load i32, ptr @JavaThread.held_monitor_count_offset
  %held_monitor_count_offset_zext = zext i32 %held_monitor_count_offset to i64
  %held_monitor_count_addr = inttoptr i64 %held_monitor_count_offset_zext to ptr addrspace(2)
  %held_monitor_count = load i64, ptr addrspace(2) %held_monitor_count_addr, align 8
  %new_held_monitor_count = add i64 %held_monitor_count, 1
  store i64 %new_held_monitor_count, ptr addrspace(2) %held_monitor_count_addr, align 8
  ret void
}

; Decrement held_monitor_count in JavaThread
define hotspotcc void @jeandle.decrement_lock_count() "lower-phase"="0" #0 {
entry:
  %held_monitor_count_offset = load i32, ptr @JavaThread.held_monitor_count_offset
  %held_monitor_count_offset_zext = zext i32 %held_monitor_count_offset to i64
  %held_monitor_count_addr = inttoptr i64 %held_monitor_count_offset_zext to ptr addrspace(2)
  %held_monitor_count = load i64, ptr addrspace(2) %held_monitor_count_addr, align 8
  %new_held_monitor_count = sub i64 %held_monitor_count, 1
  store i64 %new_held_monitor_count, ptr addrspace(2) %held_monitor_count_addr, align 8
  ret void
}

; clear the lock stack top oop in debug mode
define hotspotcc void @jeandle.clear_oop_in_lock_stack_top(i32 %lock_stack_top) "lower-phase"="0" #0 {
entry:
  %is_debug = load i1, ptr @DEBUG_MODE
  br i1 %is_debug, label %debug_path, label %release_path

debug_path:
  %lock_stack_top_zext = zext i32 %lock_stack_top to i64
  %clear_oop_addr = inttoptr i64 %lock_stack_top_zext to ptr addrspace(2)
  store atomic i64 0, ptr addrspace(2) %clear_oop_addr unordered, align 8
  ret void

release_path:
  ret void
}

; Monitor enter/exit JavaOps are lower-phase=1 so they survive
; JavaOperationLower(0) (which runs before PEA) and reach PEA as a single
; opaque call each. PEA then folds balanced monitorenter/monitorexit pairs on
; virtual receivers (foldMonitorEnter/foldMonitorExit) and, on a deopt,
; records the lock as eliminated (deopt descriptor MonitorType index=1 with a
; VORef owner), which HotSpot re-acquires via relock_objects. At lower-phase=0
; the body is inlined before PEA into raw mark-word cmpxchg memory ops that
; PEA cannot recognize, so no lock would ever be elided. (Same lever already
; used by register_finalizer_if_needed and the allocation intrinsics.)
;
; Implementation of monitorenter when LockingMode == 0. A complete JavaOp:
; the fast path attempts the lock; every failure path falls through to the
; slow path, which delegates to the SharedRuntime slow routine. The fast path
; increments held_monitor_count on success; the slow path does NOT (the runtime
; routine manages its own counter), preserving the pre-refactor semantics.
; Fast path implementation of monitorenter when LockingMode == 0
define hotspotcc void @jeandle.monitorenter_with_monitor_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %is_inflated = call hotspotcc i1 @jeandle.check_inflated(i64 %mark_word)
  br i1 %is_inflated, label %monitor_lock_fast_path, label %slow_path

monitor_lock_fast_path:
  %acquired = call hotspotcc i1 @jeandle.try_acquire_monitor_lock(i64 %mark_word, ptr addrspace(0) %lock)
  br i1 %acquired, label %increment_lock_count_and_return, label %slow_path

increment_lock_count_and_return:
  call hotspotcc void @jeandle.increment_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call hotspotcc void @SharedRuntime_complete_monitor_locking_C(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread)
  ret void
}

; Implementation of monitorenter when LockingMode == 1. Complete JavaOp:
; thin-lock fast path (with recursive-owner check); all failures go to slow_path.
; Fast path implementation of monitorenter when LockingMode == 1
define hotspotcc void @jeandle.monitorenter_with_thin_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %is_inflated = call hotspotcc i1 @jeandle.check_inflated(i64 %mark_word)
  br i1 %is_inflated, label %monitor_lock_fast_path, label %thin_lock_path

thin_lock_path:
  %markWord_unlocked_value = load i64, ptr @markWord.unlocked_value
  %unlocked_mark_word = or i64 %mark_word, %markWord_unlocked_value
  store i64 %unlocked_mark_word, ptr %lock, align 8
  %lock_as_int = ptrtoint ptr %lock to i64
  %thin_lock_cas = cmpxchg ptr addrspace(1) %mark_word_addr, i64 %unlocked_mark_word, i64 %lock_as_int acq_rel monotonic, align 8
  %thin_lock_acquired = extractvalue { i64, i1 } %thin_lock_cas, 1
  br i1 %thin_lock_acquired, label %increment_lock_count_and_return, label %check_recursive_thin_lock

check_recursive_thin_lock:
  %stack_top = call hotspotcc i64 @jeandle.get_stack_pointer()
  %thin_lock_owner = extractvalue { i64, i1 } %thin_lock_cas, 0
  %offset_from_sp = sub i64 %thin_lock_owner, %stack_top
  %check_recursive_mask_value = load i64, ptr @check_recursive_mask_value
  %recursive_masked_value = and i64 %offset_from_sp, %check_recursive_mask_value
  store i64 %recursive_masked_value, ptr %lock, align 8
  %is_recursive_thin_lock = icmp eq i64 %recursive_masked_value, 0
  br i1 %is_recursive_thin_lock, label %increment_lock_count_and_return, label %slow_path

monitor_lock_fast_path:
  %acquired = call hotspotcc i1 @jeandle.try_acquire_monitor_lock(i64 %mark_word, ptr addrspace(0) %lock)
  br i1 %acquired, label %increment_lock_count_and_return, label %slow_path

increment_lock_count_and_return:
  call hotspotcc void @jeandle.increment_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call hotspotcc void @SharedRuntime_complete_monitor_locking_C(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread)
  ret void
}

; Implementation of monitorenter when LockingMode == 2. Complete JavaOp:
; lightweight-lock fast path (CAS + lock-stack push); all failures (stack full,
; CAS lost, inflated-monitor acquire fail) go to slow_path.
; Fast path implementation of monitorenter when LockingMode == 2
define hotspotcc void @jeandle.monitorenter_with_lightweight_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %is_inflated = call hotspotcc i1 @jeandle.check_inflated(i64 %mark_word)
  br i1 %is_inflated, label %monitor_lock_fast_path, label %lightweight_lock_path

lightweight_lock_path:
  %lock_stack_top_offset = load i32, ptr @JavaThread.lock_stack_top_offset
  %lock_stack_top_offset_zext = zext i32 %lock_stack_top_offset to i64
  %lock_stack_top_addr = inttoptr i64 %lock_stack_top_offset_zext to ptr addrspace(2)
  %lock_stack_top = load i32, ptr addrspace(2) %lock_stack_top_addr, align 4
  %lock_stack_end = load i32, ptr @JavaThread.lock_stack_end
  %is_lock_stack_full = icmp sge i32 %lock_stack_top, %lock_stack_end
  br i1 %is_lock_stack_full, label %slow_path, label %lightweight_lock

lightweight_lock:
  %markWord_clear_lock_mask = load i64, ptr @markWord.clear_lock_mask
  %mark_word_clear_lock = and i64 %mark_word, %markWord_clear_lock_mask
  %markWord_unlocked_value = load i64, ptr @markWord.unlocked_value
  %unlocked_mark_word = or i64 %mark_word_clear_lock, %markWord_unlocked_value
  %lightweight_lock_cas = cmpxchg ptr addrspace(1) %mark_word_addr, i64 %unlocked_mark_word, i64 %mark_word_clear_lock acq_rel monotonic, align 8
  %lightweight_lock_acquired = extractvalue { i64, i1 } %lightweight_lock_cas, 1
  br i1 %lightweight_lock_acquired, label %push_oop_to_lock_stack, label %slow_path

push_oop_to_lock_stack:
  %lock_stack_top_zext = zext i32 %lock_stack_top to i64
  %store_oop_addr = inttoptr i64 %lock_stack_top_zext to ptr addrspace(2)
  store atomic ptr addrspace(1) %obj, ptr addrspace(2) %store_oop_addr unordered, align 8
  %oopSize = load i32, ptr @oopSize
  %lock_stack_top_increased = add i32 %lock_stack_top, %oopSize
  store i32 %lock_stack_top_increased, ptr addrspace(2) %lock_stack_top_addr, align 4
  br label %increment_lock_count_and_return

monitor_lock_fast_path:
  %acquired = call hotspotcc i1 @jeandle.try_acquire_monitor_lock(i64 %mark_word, ptr addrspace(0) %lock)
  br i1 %acquired, label %increment_lock_count_and_return, label %slow_path

increment_lock_count_and_return:
  call hotspotcc void @jeandle.increment_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  call hotspotcc void @SharedRuntime_complete_monitor_locking_C(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread)
  ret void
}

; Implementation of monitorexit when LockingMode == 0. Complete JavaOp: the
; fast path releases the lock and decrements held_monitor_count on success;
; failure falls through to the slow path. The slow path does NOT call
; decrement_lock_count (the pre-refactor user-IR slow path did not either);
; the runtime routine handles release on its own.
; Fast path implementation of monitorexit when LockingMode == 0
define hotspotcc void @jeandle.monitorexit_with_monitor_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %released = call hotspotcc i1 @jeandle.try_release_monitor_lock(i64 %mark_word)
  br i1 %released, label %decrement_lock_count_and_return, label %slow_path

decrement_lock_count_and_return:
  call hotspotcc void @jeandle.decrement_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %callee_addr = load i64, ptr @SharedRuntime.complete_monitor_unlocking_C
  %callee = inttoptr i64 %callee_addr to ptr
  call void %callee(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread) #0
  ret void
}

; Implementation of monitorexit when LockingMode == 1. Complete JavaOp: thin
; recursive-unlock fast path, else inflated release, else thin-unlock CAS;
; all failures fall through to slow_path.
; Fast path implementation of monitorexit when LockingMode == 1
define hotspotcc void @jeandle.monitorexit_with_thin_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %displaced_header_offset = load i32, ptr @BasicLock.displaced_header_offset_in_bytes
  %displaced_header_addr = getelementptr inbounds i8, ptr %lock, i32 %displaced_header_offset
  %displaced_header = load i64, ptr %displaced_header_addr, align 8
  %is_recursive_stack_unlock = icmp eq i64 %displaced_header, 0
  br i1 %is_recursive_stack_unlock, label %decrement_lock_count_and_return, label %check_if_lock_is_inflated

check_if_lock_is_inflated:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %is_inflated = call hotspotcc i1 @jeandle.check_inflated(i64 %mark_word)
  br i1 %is_inflated, label %monitor_unlock_fast_path, label %thin_unlock_path

thin_unlock_path:
  %lock_as_int = ptrtoint ptr %lock to i64
  %thin_lock_cas = cmpxchg ptr addrspace(1) %mark_word_addr, i64 %lock_as_int, i64 %displaced_header acq_rel monotonic, align 8
  %thin_lock_released = extractvalue { i64, i1 } %thin_lock_cas, 1
  br i1 %thin_lock_released, label %decrement_lock_count_and_return, label %slow_path

monitor_unlock_fast_path:
  %released = call hotspotcc i1 @jeandle.try_release_monitor_lock(i64 %mark_word)
  br i1 %released, label %decrement_lock_count_and_return, label %slow_path

decrement_lock_count_and_return:
  call hotspotcc void @jeandle.decrement_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %callee_addr = load i64, ptr @SharedRuntime.complete_monitor_unlocking_C
  %callee = inttoptr i64 %callee_addr to ptr
  call void %callee(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread) #0
  ret void
}

; Implementation of monitorexit when LockingMode == 2. Complete JavaOp:
; lightweight-unlock fast path (CAS + lock-stack pop); inflated release with an
; anonymous-owner guard. All failures fall through to slow_path.
; Fast path implementation of monitorexit when LockingMode == 2
define hotspotcc void @jeandle.monitorexit_with_lightweight_lock(ptr addrspace(1) nocapture %obj, ptr addrspace(0) nocapture %lock) "lower-phase"="1" #0 {
entry:
  %mark_offset = load i32, ptr @oopDesc.mark_offset_in_bytes
  %mark_word_addr = getelementptr inbounds i8, ptr addrspace(1) %obj, i32 %mark_offset
  %mark_word = load atomic i64, ptr addrspace(1) %mark_word_addr unordered, align 8
  %is_inflated = call hotspotcc i1 @jeandle.check_inflated(i64 %mark_word)
  br i1 %is_inflated, label %check_anonymous_owner, label %lightweight_unlock_path

lightweight_unlock_path:
  %markWord_unlocked_value = load i64, ptr @markWord.unlocked_value
  %unlocked_mark_word = or i64 %mark_word, %markWord_unlocked_value
  %lightweight_lock_cas = cmpxchg ptr addrspace(1) %mark_word_addr, i64 %mark_word, i64 %unlocked_mark_word acq_rel monotonic, align 8
  %lightweight_lock_released = extractvalue { i64, i1 } %lightweight_lock_cas, 1
  br i1 %lightweight_lock_released, label %pop_oop_from_lock_stack, label %slow_path

pop_oop_from_lock_stack:
  %lock_stack_top_offset = load i32, ptr @JavaThread.lock_stack_top_offset
  %lock_stack_top_offset_zext = zext i32 %lock_stack_top_offset to i64
  %lock_stack_top_addr = inttoptr i64 %lock_stack_top_offset_zext to ptr addrspace(2)
  %lock_stack_top = load i32, ptr addrspace(2) %lock_stack_top_addr, align 4
  %oopSize = load i32, ptr @oopSize
  %new_lock_stack_top = sub i32 %lock_stack_top, %oopSize
  store i32 %new_lock_stack_top, ptr addrspace(2) %lock_stack_top_addr, align 4
  call hotspotcc void @jeandle.clear_oop_in_lock_stack_top(i32 %new_lock_stack_top)
  br label %decrement_lock_count_and_return

check_anonymous_owner:
  %monitor_ptr = inttoptr i64 %mark_word to ptr
  %owner_offset_no_monitor_value = load i32, ptr @ObjectMonitor.owner_offset_no_monitor_value
  %owner_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %owner_offset_no_monitor_value
  %owner = load atomic volatile i64, ptr %owner_addr unordered, align 8
  %anonymous_owner_mask = load i64, ptr @ObjectMonitor.ANONYMOUS_OWNER
  %masked_owner = and i64 %owner, %anonymous_owner_mask
  %is_anonymous_owner = icmp ne i64 %masked_owner, 0
  br i1 %is_anonymous_owner, label %slow_path, label %monitor_unlock_fast_path

monitor_unlock_fast_path:
  %released = call hotspotcc i1 @jeandle.try_release_monitor_lock(i64 %mark_word)
  br i1 %released, label %decrement_lock_count_and_return, label %slow_path

decrement_lock_count_and_return:
  call hotspotcc void @jeandle.decrement_lock_count()
  ret void

slow_path:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %callee_addr = load i64, ptr @SharedRuntime.complete_monitor_unlocking_C
  %callee = inttoptr i64 %callee_addr to ptr
  call void %callee(ptr addrspace(1) %obj, ptr addrspace(0) %lock, ptr %current_thread) #0
  ret void
}

; String bulk kernels
;
; All ranges are validated by the frontend. These finite leaf operations do
; not allocate, trap, or cover an enclosing loop's safepoint poll. Scalar
; accesses retain Java's unordered semantics; vectors follow the bulk-array
; intrinsic contract. No operand aliasing attributes are inferred.
; Private kernels defer optional optimization until phase1 forces inlining;
; their callers then optimize normally with concrete encoding and layout.

define private hotspotcc i32 @jeandle.string_equals(ptr addrspace(1) nocapture readonly %a, ptr addrspace(1) nocapture readonly %b, i32 %bytes) noinline optnone "lower-phase"="1" #0 memory(read) {
entry:
  %base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %ap = getelementptr i8, ptr addrspace(1) %a, i32 %base
  %bp = getelementptr i8, ptr addrspace(1) %b, i32 %base
  %n = zext i32 %bytes to i64
  %diff = call hotspotcc i32 @jeandle.memcmp(ptr addrspace(1) %ap, ptr addrspace(1) %bp, i64 %n)
  %same = icmp eq i32 %diff, 0
  %result = zext i1 %same to i32
  ret i32 %result
}

define private hotspotcc i32 @jeandle.string_compare(ptr addrspace(1) nocapture readonly %a, i32 %aCount, ptr addrspace(1) nocapture readonly %b, i32 %bCount, i32 %encoding) noinline optnone "lower-phase"="1" #0 memory(read) {
entry:
  %base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %ap = getelementptr i8, ptr addrspace(1) %a, i32 %base
  %bp = getelementptr i8, ptr addrspace(1) %b, i32 %base
  %abit = and i32 %encoding, 1
  %bbit = and i32 %encoding, 2
  %au = icmp ne i32 %abit, 0
  %bu = icmp ne i32 %bbit, 0
  %result = call hotspotcc i32 @jeandle.string_compare_data(ptr addrspace(1) %ap, i32 %aCount, ptr addrspace(1) %bp, i32 %bCount, i1 %au, i1 %bu)
  ret i32 %result
}



declare ptr addrspace(1) @memchr(ptr addrspace(1) readonly, i32, i64) #0 nofree willreturn memory(argmem: read)

define private hotspotcc i32 @jeandle.string_indexof(ptr addrspace(1) nocapture readonly %src, i32 %srcCount, ptr addrspace(1) nocapture readonly %tgt, i32 %tgtCount, i32 %from, i32 %encoding) noinline optnone "lower-phase"="1" #0 memory(read) {
entry:
  %base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %sp = getelementptr i8, ptr addrspace(1) %src, i32 %base
  %tp = getelementptr i8, ptr addrspace(1) %tgt, i32 %base
  %sbit = and i32 %encoding, 1
  %tbit = and i32 %encoding, 2
  %su = icmp ne i32 %sbit, 0
  %tu = icmp ne i32 %tbit, 0
  %latin = icmp eq i32 %encoding, 0
  %same.encoding = icmp eq i1 %su, %tu
  %count64 = zext i32 %tgtCount to i64
  %byte.shift = zext i1 %su to i64
  %compare.bytes = shl i64 %count64, %byte.shift
  %little = icmp eq i16 bitcast (<16 x i1> <i1 true, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false> to i16), 1
  %limit = sub i32 %srcCount, %tgtCount
  %fits = icmp sle i32 %from, %limit
  br i1 %fits, label %prepare, label %miss
prepare:
  %first = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 0, i1 %tu)
  %single = icmp eq i32 %tgtCount, 1
  br i1 %single, label %one, label %pair.dispatch
one:
  %outside.latin1 = icmp ugt i32 %first, 255
  %source.latin1 = xor i1 %su, true
  %impossible = and i1 %outside.latin1, %source.latin1
  br i1 %impossible, label %miss, label %one.search
one.search:
  br i1 %su, label %one.word, label %one.byte
one.word:
  %one.result = call hotspotcc i32 @jeandle.string_find_char(ptr addrspace(1) %sp, i32 %first, i32 %from, i32 %srcCount, i1 %su)
  ret i32 %one.result
one.byte:
  %one.from = zext i32 %from to i64
  %one.base = getelementptr i8, ptr addrspace(1) %sp, i64 %one.from
  %one.left = sub i32 %srcCount, %from
  %one.count = zext i32 %one.left to i64
  %one.found = call ptr addrspace(1) @memchr(ptr addrspace(1) %one.base, i32 %first, i64 %one.count) #0 nobuiltin
  %one.missed = icmp eq ptr addrspace(1) %one.found, null
  %one.address = ptrtoint ptr addrspace(1) %one.found to i64
  %one.source = ptrtoint ptr addrspace(1) %sp to i64
  %one.offset = sub i64 %one.address, %one.source
  %one.index = trunc i64 %one.offset to i32
  %one.answer = select i1 %one.missed, i32 -1, i32 %one.index
  ret i32 %one.answer
pair.dispatch:
  %pair.only = icmp eq i32 %tgtCount, 2
  %last.offset = sub i32 %tgtCount, 1
  %candidate.count = sub i32 %limit, %from
  %small.source = icmp ult i32 %candidate.count, 15
  br i1 %small.source, label %unprepared, label %pair
unprepared:
  br label %scan.header
pair:
  %last = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 %last.offset, i1 %tu)
  ; Equal endpoints use the adjacent character as the vector filter.
  %same.ends = icmp eq i32 %first, %last
  br i1 %same.ends, label %second, label %filter
second:
  %second.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 1, i1 %tu)
  br label %filter
filter:
  %prepared.filter.offset = phi i32 [ %last.offset, %pair ], [ 1, %second ]
  %prepared.filter.char = phi i32 [ %last, %pair ], [ %second.char, %second ]
  br label %scan.header
scan.header:
  %pos = phi i32 [ %from, %unprepared ], [ %from, %filter ], [ %next, %vector.next ], [ %l64.next.pos, %latin64.next ]
  %filter.offset = phi i32 [ 0, %unprepared ], [ %prepared.filter.offset, %filter ], [ %filter.offset, %vector.next ], [ %filter.offset, %latin64.next ]
  %filter.char = phi i32 [ %first, %unprepared ], [ %prepared.filter.char, %filter ], [ %filter.char, %vector.next ], [ %filter.char, %latin64.next ]
  %remaining = sub i32 %limit, %pos
  %more.candidates = icmp sge i32 %remaining, 0
  br i1 %more.candidates, label %scan.width, label %miss
scan.width:
  %wide16 = icmp sge i32 %remaining, 15
  br i1 %wide16, label %vector.prepare, label %short.width
short.width:
  %wide4 = icmp sge i32 %remaining, 3
  br i1 %wide4, label %short.window.check, label %scalar.load
; 4..15 complete candidates: a single 16-lane window pair replaces the
; multi-round 4-lane scan when both window loads end inside the source array
; (pos+filter.offset+16 <= srcCount). Lanes past the last complete candidate
; are masked off; every surviving lane is a complete, in-bounds candidate.
short.window.check:
  %window.span = add i32 %filter.offset, 16
  %window.last = sub i32 %srcCount, %window.span
  %window.safe = icmp sle i32 %pos, %window.last
  br i1 %window.safe, label %short.window.dispatch, label %short.prepare
short.window.dispatch:
  br i1 %su, label %short.window.words, label %short.window.bytes
short.window.bytes:
  %swb.pos = zext i32 %pos to i64
  %swb.filter.pos = add i32 %pos, %filter.offset
  %swb.filter64 = zext i32 %swb.filter.pos to i64
  %swb.p0 = getelementptr i8, ptr addrspace(1) %sp, i64 %swb.pos
  %swb.p1 = getelementptr i8, ptr addrspace(1) %sp, i64 %swb.filter64
  %swb.v0 = load <16 x i8>, ptr addrspace(1) %swb.p0, align 1
  %swb.v1 = load <16 x i8>, ptr addrspace(1) %swb.p1, align 1
  %swb.first8 = trunc i32 %first to i8
  %swb.filter8 = trunc i32 %filter.char to i8
  %swb.f.ins = insertelement <16 x i8> poison, i8 %swb.first8, i32 0
  %swb.f.vec = shufflevector <16 x i8> %swb.f.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  %swb.l.ins = insertelement <16 x i8> poison, i8 %swb.filter8, i32 0
  %swb.l.vec = shufflevector <16 x i8> %swb.l.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  %swb.eq0 = icmp eq <16 x i8> %swb.v0, %swb.f.vec
  %swb.eq1 = icmp eq <16 x i8> %swb.v1, %swb.l.vec
  %swb.matches = and <16 x i1> %swb.eq0, %swb.eq1
  %swb.bits = bitcast <16 x i1> %swb.matches to i16
  %swb.first.fits = icmp ule i32 %first, 255
  %swb.filter.fits = icmp ule i32 %filter.char, 255
  %swb.chars.fit = and i1 %swb.first.fits, %swb.filter.fits
  %swb.masked.bits = select i1 %swb.chars.fit, i16 %swb.bits, i16 0
  br label %short.window.done
short.window.words:
  %sww.filter.pos = add i32 %pos, %filter.offset
  %sww.v0 = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %sp, i32 %pos, i1 true)
  %sww.v1 = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %sp, i32 %sww.filter.pos, i1 true)
  %sww.first16 = trunc i32 %first to i16
  %sww.filter16 = trunc i32 %filter.char to i16
  %sww.f.ins = insertelement <16 x i16> poison, i16 %sww.first16, i32 0
  %sww.f.vec = shufflevector <16 x i16> %sww.f.ins, <16 x i16> poison, <16 x i32> zeroinitializer
  %sww.l.ins = insertelement <16 x i16> poison, i16 %sww.filter16, i32 0
  %sww.l.vec = shufflevector <16 x i16> %sww.l.ins, <16 x i16> poison, <16 x i32> zeroinitializer
  %sww.eq0 = icmp eq <16 x i16> %sww.v0, %sww.f.vec
  %sww.eq1 = icmp eq <16 x i16> %sww.v1, %sww.l.vec
  %sww.matches = and <16 x i1> %sww.eq0, %sww.eq1
  %sww.bits = bitcast <16 x i1> %sww.matches to i16
  br label %short.window.done
short.window.done:
  %sw.bits = phi i16 [ %swb.masked.bits, %short.window.bytes ], [ %sww.bits, %short.window.words ]
  %sw.reverse = call i16 @llvm.bitreverse.i16(i16 %sw.bits)
  %sw.ordered = select i1 %little, i16 %sw.bits, i16 %sw.reverse
  %sw.mask32 = zext i16 %sw.ordered to i32
  %sw.valid.lanes = add i32 %remaining, 1
  %sw.valid.one = shl i32 1, %sw.valid.lanes
  %sw.valid.mask = sub i32 %sw.valid.one, 1
  %sw.masked = and i32 %sw.mask32, %sw.valid.mask
  br label %block.ready
short.prepare:
  ; Four complete candidates permit bounded first and endpoint vectors.
  ; Prepare the endpoint filter only when a first character matches.
  %short.pos = zext i32 %pos to i64
  br i1 %su, label %short.first.words, label %short.first.bytes
short.first.words:
  %short.word.ptr = getelementptr i16, ptr addrspace(1) %sp, i64 %short.pos
  %short.word.load = load <4 x i16>, ptr addrspace(1) %short.word.ptr, align 1
  %short.first16 = trunc i32 %first to i16
  %short.first.word.ins = insertelement <4 x i16> poison, i16 %short.first16, i32 0
  %short.first.word.vec = shufflevector <4 x i16> %short.first.word.ins, <4 x i16> poison, <4 x i32> zeroinitializer
  %short.first.word.eq = icmp eq <4 x i16> %short.word.load, %short.first.word.vec
  %short.first.word.bits = bitcast <4 x i1> %short.first.word.eq to i4
  br label %short.first.mask
short.first.bytes:
  %short.byte.ptr = getelementptr i8, ptr addrspace(1) %sp, i64 %short.pos
  %short.byte.load = load <4 x i8>, ptr addrspace(1) %short.byte.ptr, align 1
  %short.first8 = trunc i32 %first to i8
  %short.first.byte.ins = insertelement <4 x i8> poison, i8 %short.first8, i32 0
  %short.first.byte.vec = shufflevector <4 x i8> %short.first.byte.ins, <4 x i8> poison, <4 x i32> zeroinitializer
  %short.first.byte.eq = icmp eq <4 x i8> %short.byte.load, %short.first.byte.vec
  %short.first.byte.bits = bitcast <4 x i1> %short.first.byte.eq to i4
  %short.first.fits = icmp ule i32 %first, 255
  %short.first.byte.mask = select i1 %short.first.fits, i4 %short.first.byte.bits, i4 0
  br label %short.first.mask
short.first.mask:
  %short.first.bits = phi i4 [ %short.first.word.bits, %short.first.words ], [ %short.first.byte.mask, %short.first.bytes ]
  %short.any.first = icmp ne i4 %short.first.bits, 0
  br i1 %short.any.first, label %short.filter.prepare, label %short.done
short.filter.prepare:
  %short.last = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 %last.offset, i1 %tu)
  %short.last.index = add i32 %pos, %last.offset
  %short.last.pos = zext i32 %short.last.index to i64
  br i1 %su, label %short.filter.words, label %short.filter.bytes
short.filter.words:
  %short.last.word.ptr = getelementptr i16, ptr addrspace(1) %sp, i64 %short.last.pos
  %short.last.word.load = load <4 x i16>, ptr addrspace(1) %short.last.word.ptr, align 1
  %short.last16 = trunc i32 %short.last to i16
  %short.last.word.ins = insertelement <4 x i16> poison, i16 %short.last16, i32 0
  %short.last.word.vec = shufflevector <4 x i16> %short.last.word.ins, <4 x i16> poison, <4 x i32> zeroinitializer
  %short.last.word.eq = icmp eq <4 x i16> %short.last.word.load, %short.last.word.vec
  %short.last.word.bits = bitcast <4 x i1> %short.last.word.eq to i4
  %short.word.matches = and i4 %short.first.bits, %short.last.word.bits
  br label %short.done
short.filter.bytes:
  %short.last.byte.ptr = getelementptr i8, ptr addrspace(1) %sp, i64 %short.last.pos
  %short.last.byte.load = load <4 x i8>, ptr addrspace(1) %short.last.byte.ptr, align 1
  %short.last8 = trunc i32 %short.last to i8
  %short.last.byte.ins = insertelement <4 x i8> poison, i8 %short.last8, i32 0
  %short.last.byte.vec = shufflevector <4 x i8> %short.last.byte.ins, <4 x i8> poison, <4 x i32> zeroinitializer
  %short.last.byte.eq = icmp eq <4 x i8> %short.last.byte.load, %short.last.byte.vec
  %short.last.byte.bits = bitcast <4 x i1> %short.last.byte.eq to i4
  %short.byte.matches = and i4 %short.first.bits, %short.last.byte.bits
  %short.last.fits = icmp ule i32 %short.last, 255
  %short.byte.mask = select i1 %short.last.fits, i4 %short.byte.matches, i4 0
  br label %short.done
short.done:
  %short.matches = phi i4 [ 0, %short.first.mask ], [ %short.word.matches, %short.filter.words ], [ %short.byte.mask, %short.filter.bytes ]
  %short.bits32 = zext i4 %short.matches to i32
  %short.reverse = call i32 @llvm.bitreverse.i32(i32 %short.bits32)
  %short.reverse4 = lshr i32 %short.reverse, 28
  %short.mask = select i1 %little, i32 %short.bits32, i32 %short.reverse4
  br label %block.ready
vector.prepare:
  %first16 = trunc i32 %first to i16
  %filter16 = trunc i32 %filter.char to i16
  %first.ins = insertelement <16 x i16> poison, i16 %first16, i32 0
  %first.vec = shufflevector <16 x i16> %first.ins, <16 x i16> poison, <16 x i32> zeroinitializer
  %filter.ins = insertelement <16 x i16> poison, i16 %filter16, i32 0
  %filter.vec = shufflevector <16 x i16> %filter.ins, <16 x i16> poison, <16 x i32> zeroinitializer
  %first8 = trunc i32 %first to i8
  %filter8 = trunc i32 %filter.char to i8
  %first.byte.ins = insertelement <32 x i8> poison, i8 %first8, i32 0
  %first.bytes = shufflevector <32 x i8> %first.byte.ins, <32 x i8> poison, <32 x i32> zeroinitializer
  %filter.byte.ins = insertelement <32 x i8> poison, i8 %filter8, i32 0
  %filter.bytes = shufflevector <32 x i8> %filter.byte.ins, <32 x i8> poison, <32 x i32> zeroinitializer
  %first.byte16.ins = insertelement <16 x i8> poison, i8 %first8, i32 0
  %first.bytes16 = shufflevector <16 x i8> %first.byte16.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  %filter.byte16.ins = insertelement <16 x i8> poison, i8 %filter8, i32 0
  %filter.bytes16 = shufflevector <16 x i8> %filter.byte16.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  br label %latin.check
latin.check:
  %wide32 = icmp sge i32 %remaining, 31
  %latin.wide = and i1 %wide32, %latin
  %latin.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %wide64 = icmp sge i32 %remaining, 63
  %latin.use64 = and i1 %latin.avx512, %wide64
  %latin.scan64 = and i1 %latin.use64, %latin
  br i1 %latin.scan64, label %latin64.load, label %latin.check32
latin.check32:
  br i1 %latin.wide, label %latin.load, label %vector.load
; One 64-byte window pair covers two 32-lane blocks. Both windows stay inside
; the source: lane 63 is a complete candidate, so pos+63+tgtCount <= srcCount.
; The two halves feed the existing 32-lane block machinery unchanged; an empty
; low half falls straight to the high half, and both empty advances 64 lanes
; with a single pair of loads.
latin64.load:
  %l64.pos = zext i32 %pos to i64
  %l64.filter = add i32 %pos, %filter.offset
  %l64.filter64 = zext i32 %l64.filter to i64
  %l64.p0 = getelementptr i8, ptr addrspace(1) %sp, i64 %l64.pos
  %l64.p1 = getelementptr i8, ptr addrspace(1) %sp, i64 %l64.filter64
  %l64.v0 = load <64 x i8>, ptr addrspace(1) %l64.p0, align 1
  %l64.v1 = load <64 x i8>, ptr addrspace(1) %l64.p1, align 1
  %l64.first.ins = insertelement <64 x i8> poison, i8 %first8, i32 0
  %l64.first.splat = shufflevector <64 x i8> %l64.first.ins, <64 x i8> poison, <64 x i32> zeroinitializer
  %l64.filter.ins = insertelement <64 x i8> poison, i8 %filter8, i32 0
  %l64.filter.splat = shufflevector <64 x i8> %l64.filter.ins, <64 x i8> poison, <64 x i32> zeroinitializer
  %l64.eq0 = icmp eq <64 x i8> %l64.v0, %l64.first.splat
  %l64.eq1 = icmp eq <64 x i8> %l64.v1, %l64.filter.splat
  %l64.matches = and <64 x i1> %l64.eq0, %l64.eq1
  %l64.bits = bitcast <64 x i1> %l64.matches to i64
  %l64.reverse = call i64 @llvm.bitreverse.i64(i64 %l64.bits)
  %l64.ordered = select i1 %little, i64 %l64.bits, i64 %l64.reverse
  %l64.lo = trunc i64 %l64.ordered to i32
  %l64.shifted = lshr i64 %l64.ordered, 32
  %l64.hi = trunc i64 %l64.shifted to i32
  %l64.lo.any = icmp ne i32 %l64.lo, 0
  br i1 %l64.lo.any, label %latin64.lo.ready, label %latin64.hi.check
latin64.hi.check:
  %l64.hi.any = icmp ne i32 %l64.hi, 0
  br i1 %l64.hi.any, label %latin64.hi.ready, label %latin64.next
latin64.next:
  %l64.next.pos = add i32 %pos, 64
  br label %scan.header, !llvm.loop !1
latin64.lo.ready:
  br label %block.ready
latin64.hi.ready:
  %l64.hi.pos = add i32 %pos, 32
  br label %block.ready
latin.load:
  ; Both filter vectors end within the validated source range.
  %latin.pos = zext i32 %pos to i64
  %latin.filter = add i32 %pos, %filter.offset
  %latin.filter64 = zext i32 %latin.filter to i64
  %latin.p0 = getelementptr i8, ptr addrspace(1) %sp, i64 %latin.pos
  %latin.p1 = getelementptr i8, ptr addrspace(1) %sp, i64 %latin.filter64
  %latin.v0 = load <32 x i8>, ptr addrspace(1) %latin.p0, align 1
  %latin.v1 = load <32 x i8>, ptr addrspace(1) %latin.p1, align 1
  %latin.eq0 = icmp eq <32 x i8> %latin.v0, %first.bytes
  %latin.eq1 = icmp eq <32 x i8> %latin.v1, %filter.bytes
  %latin.matches = and <32 x i1> %latin.eq0, %latin.eq1
  %latin.bits = bitcast <32 x i1> %latin.matches to i32
  %latin.reverse = call i32 @llvm.bitreverse.i32(i32 %latin.bits)
  %latin.mask = select i1 %little, i32 %latin.bits, i32 %latin.reverse
  br label %block.ready
vector.load:
  %filter.pos = add i32 %pos, %filter.offset
  br i1 %latin, label %latin16.load, label %word.width.check
word.width.check:
  %w32.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %w32.fits = icmp sge i32 %remaining, 31
  %w32.use = and i1 %w32.avx512, %w32.fits
  br i1 %w32.use, label %word32.load, label %word16.load
; A 32-character window pair halves the UTF16 scan iterations. Lane 31 is a
; complete candidate, so both 64-byte loads stay inside the source.
word32.load:
  %w32.pos64 = zext i32 %pos to i64
  %w32.fpos64 = zext i32 %filter.pos to i64
  %w32.p0 = getelementptr i16, ptr addrspace(1) %sp, i64 %w32.pos64
  %w32.p1 = getelementptr i16, ptr addrspace(1) %sp, i64 %w32.fpos64
  %w32.v0 = load <32 x i16>, ptr addrspace(1) %w32.p0, align 1
  %w32.v1 = load <32 x i16>, ptr addrspace(1) %w32.p1, align 1
  %w32.first.ins = insertelement <32 x i16> poison, i16 %first16, i32 0
  %w32.first.splat = shufflevector <32 x i16> %w32.first.ins, <32 x i16> poison, <32 x i32> zeroinitializer
  %w32.filter.ins = insertelement <32 x i16> poison, i16 %filter16, i32 0
  %w32.filter.splat = shufflevector <32 x i16> %w32.filter.ins, <32 x i16> poison, <32 x i32> zeroinitializer
  %w32.eq0 = icmp eq <32 x i16> %w32.v0, %w32.first.splat
  %w32.eq1 = icmp eq <32 x i16> %w32.v1, %w32.filter.splat
  %w32.matches = and <32 x i1> %w32.eq0, %w32.eq1
  %w32.bits = bitcast <32 x i1> %w32.matches to i32
  %w32.reverse = call i32 @llvm.bitreverse.i32(i32 %w32.bits)
  %w32.mask = select i1 %little, i32 %w32.bits, i32 %w32.reverse
  br label %block.ready
latin16.load:
  %latin16.pos = zext i32 %pos to i64
  %latin16.filter = zext i32 %filter.pos to i64
  %latin16.p0 = getelementptr i8, ptr addrspace(1) %sp, i64 %latin16.pos
  %latin16.p1 = getelementptr i8, ptr addrspace(1) %sp, i64 %latin16.filter
  %latin16.v0 = load <16 x i8>, ptr addrspace(1) %latin16.p0, align 1
  %latin16.v1 = load <16 x i8>, ptr addrspace(1) %latin16.p1, align 1
  %latin16.eq0 = icmp eq <16 x i8> %latin16.v0, %first.bytes16
  %latin16.eq1 = icmp eq <16 x i8> %latin16.v1, %filter.bytes16
  %latin16.matches = and <16 x i1> %latin16.eq0, %latin16.eq1
  br label %mask16
word16.load:
  %v0 = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %sp, i32 %pos, i1 %su)
  %v1 = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %sp, i32 %filter.pos, i1 %su)
  %eq0 = icmp eq <16 x i16> %v0, %first.vec
  %eq1 = icmp eq <16 x i16> %v1, %filter.vec
  %candidates = and <16 x i1> %eq0, %eq1
  br label %mask16
mask16:
  %matches16 = phi <16 x i1> [ %latin16.matches, %latin16.load ], [ %candidates, %word16.load ]
  %bits16 = bitcast <16 x i1> %matches16 to i16
  %reverse16 = call i16 @llvm.bitreverse.i16(i16 %bits16)
  %ordered16 = select i1 %little, i16 %bits16, i16 %reverse16
  %mask32 = zext i16 %ordered16 to i32
  br label %block.ready
scalar.load:
  %scalar.index = phi i32 [ %pos, %short.width ], [ %scalar.next.index, %scalar.next ]
  %scalar.first = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %sp, i32 %scalar.index, i1 %su)
  %scalar.first.same = icmp eq i32 %scalar.first, %first
  br i1 %scalar.first.same, label %scalar.filter, label %scalar.next
scalar.next:
  %scalar.next.index = add i32 %scalar.index, 1
  %scalar.more = icmp sle i32 %scalar.next.index, %limit
  br i1 %scalar.more, label %scalar.load, label %miss
scalar.filter:
  %scalar.last = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 %last.offset, i1 %tu)
  %scalar.last.index = add i32 %scalar.index, %last.offset
  %scalar.source.last = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %sp, i32 %scalar.last.index, i1 %su)
  %scalar.last.same = icmp eq i32 %scalar.source.last, %scalar.last
  br i1 %scalar.last.same, label %block.ready, label %scalar.next
block.ready:
  %block.pos = phi i32 [ %pos, %latin.load ], [ %pos, %mask16 ], [ %pos, %short.done ], [ %scalar.index, %scalar.filter ], [ %pos, %short.window.done ], [ %pos, %latin64.lo.ready ], [ %l64.hi.pos, %latin64.hi.ready ], [ %pos, %word32.load ]
  %mask = phi i32 [ %latin.mask, %latin.load ], [ %mask32, %mask16 ], [ %short.mask, %short.done ], [ 1, %scalar.filter ], [ %sw.masked, %short.window.done ], [ %l64.lo, %latin64.lo.ready ], [ %l64.hi, %latin64.hi.ready ], [ %w32.mask, %word32.load ]
  %block.size = phi i32 [ 32, %latin.load ], [ 16, %mask16 ], [ 4, %short.done ], [ 1, %scalar.filter ], [ 16, %short.window.done ], [ 32, %latin64.lo.ready ], [ 32, %latin64.hi.ready ], [ 32, %word32.load ]
  ; Word-lane blocks (UTF16 sources) must refine with a word window; byte
  ; windows compare zext lanes and would mis-filter 16-bit candidates.
  %block.words = phi i1 [ false, %latin.load ], [ false, %mask16 ], [ false, %short.done ], [ false, %scalar.filter ], [ false, %short.window.done ], [ false, %latin64.lo.ready ], [ false, %latin64.hi.ready ], [ true, %word32.load ]
  ; Every lane is a complete, in-bounds candidate. Refine dense endpoint
  ; matches with the adjacent character; empty masks keep the two-load path.
  %block.has.candidates = icmp ne i32 %mask, 0
  br i1 %block.has.candidates, label %refine.check, label %vector.next
refine.check:
  %mask.minus.one = sub i32 %mask, 1
  %other.candidates = and i32 %mask, %mask.minus.one
  %dense = icmp ne i32 %other.candidates, 0
  %not.adjacent = icmp ne i32 %filter.offset, 1
  %dense.endpoints = and i1 %dense, %not.adjacent
  %full.vector = icmp uge i32 %block.size, 16
  %refine = and i1 %dense.endpoints, %full.vector
  br i1 %refine, label %adjacent.prepare, label %block.filtered
adjacent.prepare:
  %adjacent.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %tp, i32 1, i1 %tu)
  %adjacent.pos = add i32 %block.pos, 1
  br i1 %block.words, label %adjacent.words32, label %adjacent.size.dispatch
adjacent.size.dispatch:
  %block32 = icmp eq i32 %block.size, 32
  br i1 %block32, label %adjacent.bytes, label %adjacent.words
adjacent.words32:
  %adj.word32 = trunc i32 %adjacent.char to i16
  %adj.w32.ins = insertelement <32 x i16> poison, i16 %adj.word32, i32 0
  %adj.w32.splat = shufflevector <32 x i16> %adj.w32.ins, <32 x i16> poison, <32 x i32> zeroinitializer
  %adj.w32.off = zext i32 %adjacent.pos to i64
  %adj.w32.ptr = getelementptr i16, ptr addrspace(1) %sp, i64 %adj.w32.off
  %adj.w32.load = load <32 x i16>, ptr addrspace(1) %adj.w32.ptr, align 1
  %adj.w32.eq = icmp eq <32 x i16> %adj.w32.load, %adj.w32.splat
  %adj.w32.bits = bitcast <32 x i1> %adj.w32.eq to i32
  %adj.w32.reverse = call i32 @llvm.bitreverse.i32(i32 %adj.w32.bits)
  %adjacent.mask32w = select i1 %little, i32 %adj.w32.bits, i32 %adj.w32.reverse
  br label %adjacent.done
adjacent.bytes:
  %adjacent.byte = trunc i32 %adjacent.char to i8
  %adjacent.ins32 = insertelement <32 x i8> poison, i8 %adjacent.byte, i32 0
  %adjacent.vec32 = shufflevector <32 x i8> %adjacent.ins32, <32 x i8> poison, <32 x i32> zeroinitializer
  %adjacent.offset = zext i32 %adjacent.pos to i64
  %adjacent.ptr = getelementptr i8, ptr addrspace(1) %sp, i64 %adjacent.offset
  %adjacent.load32 = load <32 x i8>, ptr addrspace(1) %adjacent.ptr, align 1
  %adjacent.eq32 = icmp eq <32 x i8> %adjacent.load32, %adjacent.vec32
  %adjacent.bits32 = bitcast <32 x i1> %adjacent.eq32 to i32
  %adjacent.reverse32 = call i32 @llvm.bitreverse.i32(i32 %adjacent.bits32)
  %adjacent.mask32 = select i1 %little, i32 %adjacent.bits32, i32 %adjacent.reverse32
  br label %adjacent.done
adjacent.words:
  %adjacent.word = trunc i32 %adjacent.char to i16
  %adjacent.ins16 = insertelement <16 x i16> poison, i16 %adjacent.word, i32 0
  %adjacent.vec16 = shufflevector <16 x i16> %adjacent.ins16, <16 x i16> poison, <16 x i32> zeroinitializer
  %adjacent.load16 = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %sp, i32 %adjacent.pos, i1 %su)
  %adjacent.eq16 = icmp eq <16 x i16> %adjacent.load16, %adjacent.vec16
  %adjacent.bits16 = bitcast <16 x i1> %adjacent.eq16 to i16
  %adjacent.reverse16 = call i16 @llvm.bitreverse.i16(i16 %adjacent.bits16)
  %adjacent.ordered16 = select i1 %little, i16 %adjacent.bits16, i16 %adjacent.reverse16
  %adjacent.mask16 = zext i16 %adjacent.ordered16 to i32
  br label %adjacent.done
adjacent.done:
  %adjacent.mask = phi i32 [ %adjacent.mask32, %adjacent.bytes ], [ %adjacent.mask16, %adjacent.words ], [ %adjacent.mask32w, %adjacent.words32 ]
  %refined.mask = and i32 %mask, %adjacent.mask
  br label %block.filtered
block.filtered:
  %candidate.mask = phi i32 [ %mask, %refine.check ], [ %refined.mask, %adjacent.done ]
  br label %candidate.check
candidate.check:
  %pending = phi i32 [ %candidate.mask, %block.filtered ], [ %cleared, %candidate.next ]
  %any = icmp ne i32 %pending, 0
  br i1 %any, label %candidate, label %vector.next
candidate:
  %lane = call i32 @llvm.cttz.i32(i32 %pending, i1 true)
  %index = add i32 %block.pos, %lane
  br i1 %pair.only, label %hit, label %candidate.validate
candidate.validate:
  %index64 = zext i32 %index to i64
  %shift = zext i1 %su to i64
  %offset = shl i64 %index64, %shift
  %candidate.ptr = getelementptr i8, ptr addrspace(1) %sp, i64 %offset
  br i1 %same.encoding, label %candidate.equals, label %candidate.compare
candidate.equals:
  %candidate.comparison = call hotspotcc i32 @jeandle.memcmp(ptr addrspace(1) %candidate.ptr, ptr addrspace(1) %tp, i64 %compare.bytes)
  %candidate.equal = icmp eq i32 %candidate.comparison, 0
  br label %candidate.result
candidate.compare:
  %ordered.comparison = call hotspotcc i32 @jeandle.string_compare_data(ptr addrspace(1) %candidate.ptr, i32 %tgtCount, ptr addrspace(1) %tp, i32 %tgtCount, i1 %su, i1 %tu)
  %ordered.equal = icmp eq i32 %ordered.comparison, 0
  br label %candidate.result
candidate.result:
  %matched = phi i1 [ %candidate.equal, %candidate.equals ], [ %ordered.equal, %candidate.compare ]
  br i1 %matched, label %hit, label %candidate.next
candidate.next:
  %without.lowest = sub i32 %pending, 1
  %cleared = and i32 %pending, %without.lowest
  br label %candidate.check
hit:
  ret i32 %index
vector.next:
  %next = add i32 %block.pos, %block.size
  ; Keep vector preparation behind the width dispatch for scalar searches.
  br label %scan.header, !llvm.loop !1
miss:
  ret i32 -1
}

define private hotspotcc i32 @jeandle.string_indexof_char(ptr addrspace(1) nocapture readonly %src, i32 %ch, i32 %from, i32 %max, i1 %utf16) noinline optnone "lower-phase"="1" #0 memory(read) {
entry:
  %base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %data = getelementptr i8, ptr addrspace(1) %src, i32 %base
  %result = call hotspotcc i32 @jeandle.string_find_char(ptr addrspace(1) %data, i32 %ch, i32 %from, i32 %max, i1 %utf16)
  ret i32 %result
}

; Native leaf kernels preserve element atomicity without exposing shared
; array accesses to ordinary LLVM vector memory operations.
declare i32 @string_compress_stub(ptr addrspace(1) nocapture readonly align 2,
                                  ptr addrspace(1) nocapture writeonly,
                                  i32) #0 nofree willreturn memory(argmem: readwrite)
declare void @string_inflate_stub(ptr addrspace(1) nocapture readonly,
                                  ptr addrspace(1) nocapture writeonly align 2,
                                  i32) #0 nofree willreturn memory(argmem: readwrite)

; Byte and char arrays both store UTF16 code units in native byte order.
define private hotspotcc void @jeandle.string_copy_utf16(ptr addrspace(1) nocapture readonly %src, i32 %srcOff, ptr addrspace(1) nocapture writeonly %dst, i32 %dstOff, i32 %count, i1 %srcBytes) noinline optnone "lower-phase"="1" #0 memory(read, argmem: readwrite) {
entry:
  %byte.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %char.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.char
  %sb = select i1 %srcBytes, i32 %byte.base, i32 %char.base
  %db = select i1 %srcBytes, i32 %char.base, i32 %byte.base
  %sdata = getelementptr i8, ptr addrspace(1) %src, i32 %sb
  %ddata = getelementptr i8, ptr addrspace(1) %dst, i32 %db
  %so = zext i32 %srcOff to i64
  %do = zext i32 %dstOff to i64
  %sp = getelementptr i16, ptr addrspace(1) %sdata, i64 %so
  %dp = getelementptr i16, ptr addrspace(1) %ddata, i64 %do
  %n = zext i32 %count to i64
  call void @StubRoutines_jshort_disjoint_arraycopy(ptr addrspace(1) align 2 %sp, ptr addrspace(1) align 2 %dp, i64 %n) #0
  ret void
}

define private hotspotcc i32 @jeandle.string_compress(ptr addrspace(1) nocapture readonly %src, i32 %srcOff, ptr addrspace(1) nocapture writeonly %dst, i32 %dstOff, i32 %count, i1 %srcBytes) noinline optnone "lower-phase"="1" #0 memory(read, argmem: readwrite) {
entry:
  %byte.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %char.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.char
  %sb = select i1 %srcBytes, i32 %byte.base, i32 %char.base
  %sdata = getelementptr i8, ptr addrspace(1) %src, i32 %sb
  %ddata = getelementptr i8, ptr addrspace(1) %dst, i32 %byte.base
  %so = zext i32 %srcOff to i64
  %do = zext i32 %dstOff to i64
  %sp = getelementptr i16, ptr addrspace(1) %sdata, i64 %so
  %dp = getelementptr i8, ptr addrspace(1) %ddata, i64 %do
  %result = call i32 @string_compress_stub(ptr addrspace(1) %sp, ptr addrspace(1) %dp, i32 %count) #0
  ret i32 %result
}

define private hotspotcc void @jeandle.string_inflate(ptr addrspace(1) nocapture readonly %src, i32 %srcOff, ptr addrspace(1) nocapture writeonly %dst, i32 %dstOff, i32 %count, i1 %dstBytes) noinline optnone "lower-phase"="1" #0 memory(read, argmem: readwrite) {
entry:
  %byte.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.byte
  %char.base = load i32, ptr @arrayOopDesc.base_offset_in_bytes.char
  %db = select i1 %dstBytes, i32 %byte.base, i32 %char.base
  %sdata = getelementptr i8, ptr addrspace(1) %src, i32 %byte.base
  %ddata = getelementptr i8, ptr addrspace(1) %dst, i32 %db
  %so = zext i32 %srcOff to i64
  %do = zext i32 %dstOff to i64
  %sp = getelementptr i8, ptr addrspace(1) %sdata, i64 %so
  %dp = getelementptr i16, ptr addrspace(1) %ddata, i64 %do
  call void @string_inflate_stub(ptr addrspace(1) %sp, ptr addrspace(1) %dp, i32 %count) #0
  ret void
}

; Helpers use data pointers only within the leaf kernel. They retain address
; space 1 and are lowered with their callers, before GC statepoint rewriting.
define private hotspotcc i32 @jeandle.string_compare_data(ptr addrspace(1) nocapture readonly %a, i32 %ac, ptr addrspace(1) nocapture readonly %b, i32 %bc, i1 %au, i1 %bu) noinline optnone "lower-phase"="1" #0 memory(argmem: read) {
entry:
  %less = icmp ult i32 %ac, %bc
  %n = select i1 %less, i32 %ac, i32 %bc
  %mixed = xor i1 %au, %bu
  %nonempty = icmp ne i32 %n, 0
  %check.first = and i1 %mixed, %nonempty
  br i1 %check.first, label %mixed.first, label %encoding.dispatch
mixed.first:
  ; Preserve the exact unsigned character difference before vector setup.
  %first.a = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %a, i32 0, i1 %au)
  %first.b = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %b, i32 0, i1 %bu)
  %first.diff = sub i32 %first.a, %first.b
  %first.equal = icmp eq i32 %first.diff, 0
  br i1 %first.equal, label %encoding.dispatch, label %mixed.diff
mixed.diff:
  ret i32 %first.diff
encoding.dispatch:
  %utf16 = or i1 %au, %bu
  br i1 %utf16, label %vector.check, label %latin.check
latin.check:
  %latin.pos = phi i32 [ 0, %encoding.dispatch ], [ %latin.next, %latin.advance ], [ %latin64.next, %latin64.advance ]
  %latin.remaining = sub i32 %n, %latin.pos
  %latin.wide = icmp uge i32 %latin.remaining, 32
  br i1 %latin.wide, label %latin.width64, label %vector.check
latin.width64:
  ; The 64-byte tier is gated on a constant global so the optimizer removes it
  ; entirely on targets without AVX-512 VL+BW vectors.
  %latin.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %latin.fits64 = icmp uge i32 %latin.remaining, 64
  %latin.use64 = and i1 %latin.avx512, %latin.fits64
  br i1 %latin.use64, label %latin64.load, label %latin.load
latin64.load:
  %latin64.off = zext i32 %latin.pos to i64
  %latin64.ap = getelementptr i8, ptr addrspace(1) %a, i64 %latin64.off
  %latin64.bp = getelementptr i8, ptr addrspace(1) %b, i64 %latin64.off
  %latin64.va = load <64 x i8>, ptr addrspace(1) %latin64.ap, align 1
  %latin64.vb = load <64 x i8>, ptr addrspace(1) %latin64.bp, align 1
  %latin64.neq = icmp ne <64 x i8> %latin64.va, %latin64.vb
  %latin64.bits = bitcast <64 x i1> %latin64.neq to i64
  %latin64.any = icmp ne i64 %latin64.bits, 0
  br i1 %latin64.any, label %latin64.diff, label %latin64.advance
latin64.diff:
  ; Reload the mismatching bytes as scalars instead of extracting from the
  ; vector: a variable-lane extractelement forces the whole vector through
  ; the stack, whose store-to-load forwarding stalls on mid-block lanes. The
  ; bytes are in the same cache lines the window loads just touched.
  %latin64.lane = call hotspotcc i32 @jeandle.string_first_lane64(<64 x i1> %latin64.neq)
  %latin64.mismatch = add i32 %latin.pos, %latin64.lane
  %latin64.mismatch64 = zext i32 %latin64.mismatch to i64
  %latin64.ca.ptr = getelementptr i8, ptr addrspace(1) %a, i64 %latin64.mismatch64
  %latin64.cb.ptr = getelementptr i8, ptr addrspace(1) %b, i64 %latin64.mismatch64
  %latin64.ca8 = load i8, ptr addrspace(1) %latin64.ca.ptr, align 1
  %latin64.cb8 = load i8, ptr addrspace(1) %latin64.cb.ptr, align 1
  %latin64.ca = zext i8 %latin64.ca8 to i32
  %latin64.cb = zext i8 %latin64.cb8 to i32
  %latin64.result = sub i32 %latin64.ca, %latin64.cb
  ret i32 %latin64.result
latin64.advance:
  %latin64.next = add i32 %latin.pos, 64
  br label %latin.check
latin.load:
  %latin.offset = zext i32 %latin.pos to i64
  %latin.ap = getelementptr i8, ptr addrspace(1) %a, i64 %latin.offset
  %latin.bp = getelementptr i8, ptr addrspace(1) %b, i64 %latin.offset
  %latin.a = load <32 x i8>, ptr addrspace(1) %latin.ap, align 1
  %latin.b = load <32 x i8>, ptr addrspace(1) %latin.bp, align 1
  %latin.neq = icmp ne <32 x i8> %latin.a, %latin.b
  %latin.bits = bitcast <32 x i1> %latin.neq to i32
  %latin.any = icmp ne i32 %latin.bits, 0
  br i1 %latin.any, label %latin.diff, label %latin.advance
latin.diff:
  ; Scalar reload at the mismatch index; see latin64.diff for the rationale.
  %latin.lane = call hotspotcc i32 @jeandle.string_first_lane32(<32 x i1> %latin.neq)
  %latin.mismatch = add i32 %latin.pos, %latin.lane
  %latin.mismatch64 = zext i32 %latin.mismatch to i64
  %latin.ca.ptr = getelementptr i8, ptr addrspace(1) %a, i64 %latin.mismatch64
  %latin.cb.ptr = getelementptr i8, ptr addrspace(1) %b, i64 %latin.mismatch64
  %latin.ca8 = load i8, ptr addrspace(1) %latin.ca.ptr, align 1
  %latin.cb8 = load i8, ptr addrspace(1) %latin.cb.ptr, align 1
  %latin.ca = zext i8 %latin.ca8 to i32
  %latin.cb = zext i8 %latin.cb8 to i32
  %latin.result = sub i32 %latin.ca, %latin.cb
  ret i32 %latin.result
latin.advance:
  %latin.next = add i32 %latin.pos, 32
  br label %latin.check
vector.check:
  %pos = phi i32 [ 0, %encoding.dispatch ], [ %latin.pos, %latin.check ], [ %next, %vector.next ], [ %vw32.next, %vw32.next.block ]
  %remaining = sub i32 %n, %pos
  %wide = icmp uge i32 %remaining, 16
  br i1 %wide, label %vw32.check, label %narrow.check
vw32.check:
  ; The 32-lane tier sits behind the 16-lane gate so sub-16 compares keep
  ; their original dispatch depth.
  %vw32.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %vw32.fits = icmp uge i32 %remaining, 32
  %vw32.use = and i1 %vw32.avx512, %vw32.fits
  br i1 %vw32.use, label %vw32.a.select, label %vector.load
vw32.a.select:
  br i1 %au, label %vw32.a.wide, label %vw32.a.narrow
vw32.a.wide:
  %vw32.ap = getelementptr i16, ptr addrspace(1) %a, i32 %pos
  %vw32.aw = load <32 x i16>, ptr addrspace(1) %vw32.ap, align 1
  br label %vw32.a.done
vw32.a.narrow:
  %vw32.aq = getelementptr i8, ptr addrspace(1) %a, i32 %pos
  %vw32.ab = load <32 x i8>, ptr addrspace(1) %vw32.aq, align 1
  %vw32.az = zext <32 x i8> %vw32.ab to <32 x i16>
  br label %vw32.a.done
vw32.a.done:
  %vw32.av = phi <32 x i16> [ %vw32.aw, %vw32.a.wide ], [ %vw32.az, %vw32.a.narrow ]
  br i1 %bu, label %vw32.b.wide, label %vw32.b.narrow
vw32.b.wide:
  %vw32.bp = getelementptr i16, ptr addrspace(1) %b, i32 %pos
  %vw32.bw = load <32 x i16>, ptr addrspace(1) %vw32.bp, align 1
  br label %vw32.cmp
vw32.b.narrow:
  %vw32.bq = getelementptr i8, ptr addrspace(1) %b, i32 %pos
  %vw32.bb = load <32 x i8>, ptr addrspace(1) %vw32.bq, align 1
  %vw32.bz = zext <32 x i8> %vw32.bb to <32 x i16>
  br label %vw32.cmp
vw32.cmp:
  %vw32.bv = phi <32 x i16> [ %vw32.bw, %vw32.b.wide ], [ %vw32.bz, %vw32.b.narrow ]
  %vw32.ne = icmp ne <32 x i16> %vw32.av, %vw32.bv
  %vw32.bits = bitcast <32 x i1> %vw32.ne to i32
  %vw32.any = icmp ne i32 %vw32.bits, 0
  br i1 %vw32.any, label %vw32.diff, label %vw32.next.block
vw32.diff:
  ; Scalar reload at the mismatch index; see latin64.diff for the rationale.
  ; load_char widens Latin1 lanes the same way the vector compare did.
  %vw32.lane = call hotspotcc i32 @jeandle.string_first_lane32(<32 x i1> %vw32.ne)
  %vw32.mismatch = add i32 %pos, %vw32.lane
  %vw32.ca = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %a, i32 %vw32.mismatch, i1 %au)
  %vw32.cb = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %b, i32 %vw32.mismatch, i1 %bu)
  %vw32.value = sub i32 %vw32.ca, %vw32.cb
  ret i32 %vw32.value
vw32.next.block:
  %vw32.next = add i32 %pos, 32
  br label %vector.check
; Sub-16 remainders (short candidates in substring verification) compare one
; 8-wide and then one 4-wide block instead of draining scalarly; the exact
; difference value is recovered from the first mismatching lane.
narrow.check:
  %has8 = icmp uge i32 %remaining, 8
  br i1 %has8, label %n8.a.select, label %narrow.check4
n8.a.select:
  br i1 %au, label %n8.a.wide, label %n8.a.narrow
n8.a.wide:
  %n8.ap = getelementptr i16, ptr addrspace(1) %a, i32 %pos
  %n8.aw = load <8 x i16>, ptr addrspace(1) %n8.ap, align 2
  br label %n8.a.done
n8.a.narrow:
  %n8.aq = getelementptr i8, ptr addrspace(1) %a, i32 %pos
  %n8.ab = load <8 x i8>, ptr addrspace(1) %n8.aq, align 1
  %n8.az = zext <8 x i8> %n8.ab to <8 x i16>
  br label %n8.a.done
n8.a.done:
  %n8.av = phi <8 x i16> [ %n8.aw, %n8.a.wide ], [ %n8.az, %n8.a.narrow ]
  br i1 %bu, label %n8.b.wide, label %n8.b.narrow
n8.b.wide:
  %n8.bp = getelementptr i16, ptr addrspace(1) %b, i32 %pos
  %n8.bw = load <8 x i16>, ptr addrspace(1) %n8.bp, align 2
  br label %n8.cmp
n8.b.narrow:
  %n8.bq = getelementptr i8, ptr addrspace(1) %b, i32 %pos
  %n8.bb = load <8 x i8>, ptr addrspace(1) %n8.bq, align 1
  %n8.bz = zext <8 x i8> %n8.bb to <8 x i16>
  br label %n8.cmp
n8.cmp:
  %n8.bv = phi <8 x i16> [ %n8.bw, %n8.b.wide ], [ %n8.bz, %n8.b.narrow ]
  %n8.ne = icmp ne <8 x i16> %n8.av, %n8.bv
  %n8.bits = bitcast <8 x i1> %n8.ne to i8
  %n8.any = icmp ne i8 %n8.bits, 0
  %pos8.done = add i32 %pos, 8
  br i1 %n8.any, label %n8.diff, label %narrow.check4
n8.diff:
  %n8.lane8 = call i8 @llvm.cttz.i8(i8 %n8.bits, i1 true)
  %n8.lane = zext i8 %n8.lane8 to i32
  %n8.ca = extractelement <8 x i16> %n8.av, i32 %n8.lane
  %n8.cb = extractelement <8 x i16> %n8.bv, i32 %n8.lane
  %n8.ca32 = zext i16 %n8.ca to i32
  %n8.cb32 = zext i16 %n8.cb to i32
  %n8.value = sub i32 %n8.ca32, %n8.cb32
  ret i32 %n8.value
narrow.check4:
  %pos4v = phi i32 [ %pos, %narrow.check ], [ %pos8.done, %n8.cmp ]
  %rem4 = sub i32 %n, %pos4v
  %has4 = icmp uge i32 %rem4, 4
  br i1 %has4, label %n4.a.select, label %tail.check
n4.a.select:
  br i1 %au, label %n4.a.wide, label %n4.a.narrow
n4.a.wide:
  %n4.ap = getelementptr i16, ptr addrspace(1) %a, i32 %pos4v
  %n4.aw = load <4 x i16>, ptr addrspace(1) %n4.ap, align 2
  br label %n4.a.done
n4.a.narrow:
  %n4.aq = getelementptr i8, ptr addrspace(1) %a, i32 %pos4v
  %n4.ab = load <4 x i8>, ptr addrspace(1) %n4.aq, align 1
  %n4.az = zext <4 x i8> %n4.ab to <4 x i16>
  br label %n4.a.done
n4.a.done:
  %n4.av = phi <4 x i16> [ %n4.aw, %n4.a.wide ], [ %n4.az, %n4.a.narrow ]
  br i1 %bu, label %n4.b.wide, label %n4.b.narrow
n4.b.wide:
  %n4.bp = getelementptr i16, ptr addrspace(1) %b, i32 %pos4v
  %n4.bw = load <4 x i16>, ptr addrspace(1) %n4.bp, align 2
  br label %n4.cmp
n4.b.narrow:
  %n4.bq = getelementptr i8, ptr addrspace(1) %b, i32 %pos4v
  %n4.bb = load <4 x i8>, ptr addrspace(1) %n4.bq, align 1
  %n4.bz = zext <4 x i8> %n4.bb to <4 x i16>
  br label %n4.cmp
n4.cmp:
  %n4.bv = phi <4 x i16> [ %n4.bw, %n4.b.wide ], [ %n4.bz, %n4.b.narrow ]
  %n4.ne = icmp ne <4 x i16> %n4.av, %n4.bv
  %n4.bits = bitcast <4 x i1> %n4.ne to i4
  %n4.any = icmp ne i4 %n4.bits, 0
  br i1 %n4.any, label %n4.diff, label %n4.equal
n4.diff:
  %n4.lane4 = call i4 @llvm.cttz.i4(i4 %n4.bits, i1 true)
  %n4.lane = zext i4 %n4.lane4 to i32
  %n4.ca = extractelement <4 x i16> %n4.av, i32 %n4.lane
  %n4.cb = extractelement <4 x i16> %n4.bv, i32 %n4.lane
  %n4.ca32 = zext i16 %n4.ca to i32
  %n4.cb32 = zext i16 %n4.cb to i32
  %n4.value = sub i32 %n4.ca32, %n4.cb32
  ret i32 %n4.value
n4.equal:
  %pos4.done = add i32 %pos4v, 4
  br label %tail.check
vector.load:
  %va = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %a, i32 %pos, i1 %au)
  %vb = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %b, i32 %pos, i1 %bu)
  %neq = icmp ne <16 x i16> %va, %vb
  %any = call i1 @llvm.vector.reduce.or.v16i1(<16 x i1> %neq)
  br i1 %any, label %vector.diff, label %vector.next
vector.diff:
  ; Scalar reload at the mismatch index; see latin64.diff for the rationale.
  ; load_char widens Latin1 lanes the same way the vector compare did.
  %lane = call hotspotcc i32 @jeandle.string_first_lane(<16 x i1> %neq)
  %mismatch = add i32 %pos, %lane
  %ca.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %a, i32 %mismatch, i1 %au)
  %cb.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %b, i32 %mismatch, i1 %bu)
  %diff = sub i32 %ca.char, %cb.char
  ret i32 %diff
vector.next:
  %next = add i32 %pos, 16
  br label %vector.check
tail.check:
  %i = phi i32 [ %pos4v, %narrow.check4 ], [ %pos4.done, %n4.equal ], [ %i.next, %tail.next ]
  %more = icmp ult i32 %i, %n
  br i1 %more, label %tail.load, label %equal
tail.load:
  %a.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %a, i32 %i, i1 %au)
  %b.char = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %b, i32 %i, i1 %bu)
  %chars.eq = icmp eq i32 %a.char, %b.char
  br i1 %chars.eq, label %tail.next, label %tail.diff
tail.diff:
  %scalar.diff = sub i32 %a.char, %b.char
  ret i32 %scalar.diff
tail.next:
  %i.next = add i32 %i, 1
  br label %tail.check
equal:
  %length.diff = sub i32 %ac, %bc
  ret i32 %length.diff
}

define private hotspotcc i32 @jeandle.string_find_char(ptr addrspace(1) nocapture readonly %data, i32 %ch, i32 %from, i32 %max, i1 %utf16) noinline optnone "lower-phase"="1" #0 memory(argmem: read) {
entry:
  %not.char = icmp ugt i32 %ch, 65535
  %invalid = and i1 %utf16, %not.char
  br i1 %invalid, label %miss, label %prepare
prepare:
  ; Valid nonnegative positions fit a full block through these inclusive bounds.
  %last.full32 = sub i32 %max, 32
  %last.full16 = sub i32 %max, 16
  %byte = and i32 %ch, 255
  %value = select i1 %utf16, i32 %ch, i32 %byte
  %c = trunc i32 %value to i16
  %ins = insertelement <16 x i16> poison, i16 %c, i32 0
  %splat = shufflevector <16 x i16> %ins, <16 x i16> poison, <16 x i32> zeroinitializer
  %byte.char = trunc i32 %byte to i8
  %byte.ins = insertelement <32 x i8> poison, i8 %byte.char, i32 0
  %byte.splat = shufflevector <32 x i8> %byte.ins, <32 x i8> poison, <32 x i32> zeroinitializer
  %byte16.ins = insertelement <16 x i8> poison, i8 %byte.char, i32 0
  %byte16.splat = shufflevector <16 x i8> %byte16.ins, <16 x i8> poison, <16 x i32> zeroinitializer
  br i1 %utf16, label %vector.check, label %latin.check
latin.check:
  %latin.pos = phi i32 [ %from, %prepare ], [ %latin.next, %latin.next.block ], [ %latin64.next, %latin64.next.block ]
  %latin.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %last.full64 = sub i32 %max, 64
  %latin.fits64 = icmp sle i32 %latin.pos, %last.full64
  %latin.use64 = and i1 %latin.avx512, %latin.fits64
  br i1 %latin.use64, label %latin64.load, label %latin.check32
latin.check32:
  %latin.wide = icmp sle i32 %latin.pos, %last.full32
  br i1 %latin.wide, label %latin.load, label %vector.check
latin64.load:
  %latin64.off = zext i32 %latin.pos to i64
  %latin64.ptr = getelementptr i8, ptr addrspace(1) %data, i64 %latin64.off
  %latin64.v = load <64 x i8>, ptr addrspace(1) %latin64.ptr, align 1
  %latin64.splat.ins = insertelement <64 x i8> poison, i8 %byte.char, i32 0
  %latin64.splat = shufflevector <64 x i8> %latin64.splat.ins, <64 x i8> poison, <64 x i32> zeroinitializer
  %latin64.matches = icmp eq <64 x i8> %latin64.v, %latin64.splat
  %latin64.bits = bitcast <64 x i1> %latin64.matches to i64
  %latin64.any = icmp ne i64 %latin64.bits, 0
  br i1 %latin64.any, label %latin64.hit, label %latin64.next.block
latin64.hit:
  %latin64.lane = call hotspotcc i32 @jeandle.string_first_lane64(<64 x i1> %latin64.matches)
  %latin64.result = add i32 %latin.pos, %latin64.lane
  ret i32 %latin64.result
latin64.next.block:
  %latin64.next = add i32 %latin.pos, 64
  br label %latin.check
latin.load:
  %latin.offset = zext i32 %latin.pos to i64
  %latin.ptr = getelementptr i8, ptr addrspace(1) %data, i64 %latin.offset
  %latin.v = load <32 x i8>, ptr addrspace(1) %latin.ptr, align 1
  %latin.matches = icmp eq <32 x i8> %latin.v, %byte.splat
  %latin.bits = bitcast <32 x i1> %latin.matches to i32
  %latin.any = icmp ne i32 %latin.bits, 0
  br i1 %latin.any, label %latin.hit, label %latin.next.block
latin.hit:
  %latin.lane = call hotspotcc i32 @jeandle.string_first_lane32(<32 x i1> %latin.matches)
  %latin.result = add i32 %latin.pos, %latin.lane
  ret i32 %latin.result
latin.next.block:
  %latin.next = add i32 %latin.pos, 32
  br label %latin.check
vector.check:
  %pos = phi i32 [ %from, %prepare ], [ %latin.pos, %latin.check32 ], [ %next, %vector.next ], [ %vw32.pos.next, %vw32.next ]
  %wide = icmp sle i32 %pos, %last.full16
  br i1 %wide, label %vector.dispatch, label %tail.check
vector.dispatch:
  %vw32.avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %vw32.last.full32 = sub i32 %max, 32
  %vw32.fits = icmp sle i32 %pos, %vw32.last.full32
  ; The 32-lane helper loads 16-bit units, so only the UTF16 scan may use it;
  ; Latin1 already has its own 64/32/16-byte tiering above.
  %vw32.eligible = and i1 %utf16, %vw32.fits
  %vw32.use = and i1 %vw32.avx512, %vw32.eligible
  br i1 %vw32.use, label %vw32.load, label %vw16.dispatch
vw16.dispatch:
  br i1 %utf16, label %vector.load, label %latin16.load
vw32.load:
  %vw32.splat.ins = insertelement <32 x i16> poison, i16 %c, i32 0
  %vw32.splat = shufflevector <32 x i16> %vw32.splat.ins, <32 x i16> poison, <32 x i32> zeroinitializer
  %vw32.pos64 = zext i32 %pos to i64
  %vw32.ptr = getelementptr i16, ptr addrspace(1) %data, i64 %vw32.pos64
  %vw32.v = load <32 x i16>, ptr addrspace(1) %vw32.ptr, align 1
  %vw32.eq = icmp eq <32 x i16> %vw32.v, %vw32.splat
  %vw32.any = call i1 @llvm.vector.reduce.or.v32i1(<32 x i1> %vw32.eq)
  br i1 %vw32.any, label %vw32.hit, label %vw32.next
vw32.hit:
  %vw32.lane = call hotspotcc i32 @jeandle.string_first_lane32(<32 x i1> %vw32.eq)
  %vw32.result = add i32 %pos, %vw32.lane
  ret i32 %vw32.result
vw32.next:
  %vw32.pos.next = add i32 %pos, 32
  br label %vector.check
latin16.load:
  %latin16.offset = zext i32 %pos to i64
  %latin16.ptr = getelementptr i8, ptr addrspace(1) %data, i64 %latin16.offset
  %latin16.v = load <16 x i8>, ptr addrspace(1) %latin16.ptr, align 1
  %latin16.eq = icmp eq <16 x i8> %latin16.v, %byte16.splat
  br label %vector.matches
vector.load:
  %v = call hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) %data, i32 %pos, i1 %utf16)
  %word.eq = icmp eq <16 x i16> %v, %splat
  br label %vector.matches
vector.matches:
  %eq = phi <16 x i1> [ %latin16.eq, %latin16.load ], [ %word.eq, %vector.load ]
  %any = call i1 @llvm.vector.reduce.or.v16i1(<16 x i1> %eq)
  br i1 %any, label %vector.hit, label %vector.next
vector.hit:
  %lane = call hotspotcc i32 @jeandle.string_first_lane(<16 x i1> %eq)
  %result = add i32 %pos, %lane
  ret i32 %result
vector.next:
  %next = add i32 %pos, 16
  br label %vector.check
tail.check:
  %i = phi i32 [ %pos, %vector.check ], [ %i.next, %tail.next ]
  %more = icmp slt i32 %i, %max
  br i1 %more, label %tail.load, label %miss
tail.load:
  %x = call hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) %data, i32 %i, i1 %utf16)
  %same = icmp eq i32 %x, %value
  br i1 %same, label %tail.hit, label %tail.next
tail.hit:
  ret i32 %i
tail.next:
  %i.next = add i32 %i, 1
  br label %tail.check
miss:
  ret i32 -1
}

define private hotspotcc <16 x i16> @jeandle.string_load_vector(ptr addrspace(1) nocapture readonly %data, i32 %pos, i1 %utf16) noinline optnone "lower-phase"="1" #0 memory(argmem: read) {
entry:
  %offset = zext i32 %pos to i64
  br i1 %utf16, label %wide, label %narrow
wide:
  %up = getelementptr i16, ptr addrspace(1) %data, i64 %offset
  %uv = load <16 x i16>, ptr addrspace(1) %up, align 2
  ret <16 x i16> %uv
narrow:
  %lp = getelementptr i8, ptr addrspace(1) %data, i64 %offset
  %lv = load <16 x i8>, ptr addrspace(1) %lp, align 1
  %widened = zext <16 x i8> %lv to <16 x i16>
  ret <16 x i16> %widened
}

define private hotspotcc i32 @jeandle.string_load_char(ptr addrspace(1) nocapture readonly %data, i32 %pos, i1 %utf16) noinline optnone "lower-phase"="1" #0 memory(argmem: read) {
entry:
  %offset = zext i32 %pos to i64
  br i1 %utf16, label %wide, label %narrow
wide:
  %up = getelementptr i16, ptr addrspace(1) %data, i64 %offset
  %uc = load atomic i16, ptr addrspace(1) %up unordered, align 2
  %u32 = zext i16 %uc to i32
  ret i32 %u32
narrow:
  %lp = getelementptr i8, ptr addrspace(1) %data, i64 %offset
  %lc = load atomic i8, ptr addrspace(1) %lp unordered, align 1
  %l32 = zext i8 %lc to i32
  ret i32 %l32
}

; A constant lane-zero predicate identifies the target's bitcast lane order.
; Constant folding selects cttz on little-endian layouts and ctlz on big-endian
; layouts, without a runtime test. Every caller proves the mask is nonzero.
define private hotspotcc i32 @jeandle.string_first_lane(<16 x i1> %mask) noinline optnone "lower-phase"="1" #0 memory(none) {
entry:
  %bits = bitcast <16 x i1> %mask to i16
  %little = icmp eq i16 bitcast (<16 x i1> <i1 true, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false> to i16), 1
  %low = call i16 @llvm.cttz.i16(i16 %bits, i1 true)
  %high = call i16 @llvm.ctlz.i16(i16 %bits, i1 true)
  %first = select i1 %little, i16 %low, i16 %high
  %result = zext i16 %first to i32
  ret i32 %result
}

define private hotspotcc i32 @jeandle.string_first_lane32(<32 x i1> %mask) noinline optnone "lower-phase"="1" #0 memory(none) {
entry:
  %bits = bitcast <32 x i1> %mask to i32
  %little = icmp eq i32 bitcast (<32 x i1> <
      i1 true, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false> to i32), 1
  %low = call i32 @llvm.cttz.i32(i32 %bits, i1 true)
  %high = call i32 @llvm.ctlz.i32(i32 %bits, i1 true)
  %first = select i1 %little, i32 %low, i32 %high
  ret i32 %first
}

define private hotspotcc i32 @jeandle.string_first_lane64(<64 x i1> %mask) noinline optnone "lower-phase"="1" #0 memory(none) {
entry:
  %bits = bitcast <64 x i1> %mask to i64
  %little = icmp eq i64 bitcast (<64 x i1> <
      i1 true, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false,
      i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false, i1 false> to i64), 1
  %low = call i64 @llvm.cttz.i64(i64 %bits, i1 true)
  %high = call i64 @llvm.ctlz.i64(i64 %bits, i1 true)
  %first = select i1 %little, i64 %low, i64 %high
  %result = trunc i64 %first to i32
  ret i32 %result
}

declare i1 @llvm.vector.reduce.or.v16i1(<16 x i1>)
declare i1 @llvm.vector.reduce.or.v32i1(<32 x i1>)
declare i64 @llvm.bitreverse.i64(i64)
declare i16 @llvm.bitreverse.i16(i16)
declare i32 @llvm.bitreverse.i32(i32)
declare i64 @llvm.cttz.i64(i64, i1 immarg)
declare i64 @llvm.ctlz.i64(i64, i1 immarg)
declare i16 @llvm.cttz.i16(i16, i1 immarg)
declare i16 @llvm.ctlz.i16(i16, i1 immarg)
declare i32 @llvm.cttz.i32(i32, i1 immarg)
declare i32 @llvm.ctlz.i32(i32, i1 immarg)
declare i8 @llvm.cttz.i8(i8, i1 immarg)
declare i4 @llvm.cttz.i4(i4, i1 immarg)
declare void @llvm.memcpy.p1.p1.i64(ptr addrspace(1) noalias nocapture writeonly, ptr addrspace(1) noalias nocapture readonly, i64, i1 immarg)

; =============================================================================
; jeandle.memcmp - Vectorized bcmp-Style Equality Check
; =============================================================================
;
; Purpose:
;   Provides an always-present, local definition for the memcmp symbol used
;   by array equality.
;
; Semantics (bcmp, not memcmp):
;   Returns 0 when the two ranges are equal, 1 when they are not. Every
;   in-tree call site only tests the result against zero, so
;   libc memcmp's first-difference index and sign are dead values; computing
;   them cost a cttz plus two re-loads on every mismatch in the previous
;   version. LLVM itself makes the same simplification (memcmp -> bcmp)
;   whenever a result is only compared with zero, so any pass-introduced
;   memcmp call that could resolve here also only tests equality.
;
; Structure (never reads outside [0, n); every load is align 1):
;   n >= 32 : 32-byte vector loop over [0, n-32), then one overlapping
;             <32 x i8> comparison anchored at n-32, so every length is
;             covered by whole vector comparisons with no scalar tail.
;   n >= 8  : i64 loop over [0, n-8), then one overlapping i64 comparison
;             anchored at n-8 (at most 3 loop iterations in this range).
;   n >= 4  : i32 comparisons at the beginning and end.
;   n >= 2  : i16 comparisons at the beginning and end.
;   n == 1  : one byte comparison.
;   A mismatch returns immediately; no first-difference index is recovered,
;   so the implementation is endianness-independent.
;   32 bytes exploits AVX2-class hosts directly; on SSE2/NEON baselines LLVM
;   legalizes <32 x i8> into two <16 x i8> comparisons per iteration, which
;   is never worse than the previous 16-byte loop.
; =============================================================================
define hotspotcc i32 @jeandle.memcmp(ptr addrspace(1) nocapture readonly %a, ptr addrspace(1) nocapture readonly %b, i64 noundef %n) "lower-phase"="1" #0 noinline {
entry:
  %n_is_zero = icmp eq i64 %n, 0
  br i1 %n_is_zero, label %return_zero, label %size_dispatch

size_dispatch:
  %is_vector_sized = icmp ugt i64 %n, 31
  br i1 %is_vector_sized, label %vec_width.check, label %qword_dispatch

vec_width.check:
  ; 64-byte tier on AVX-512 VL+BW targets; the constant global lets the
  ; optimizer drop it everywhere else.
  %avx512 = load i1, ptr @VM_Version.string_vector_512bit
  %is_64_sized = icmp ugt i64 %n, 63
  %use_64 = and i1 %avx512, %is_64_sized
  br i1 %use_64, label %vec64_preheader, label %vec_preheader

; ---------------------------------------------------------------------------
; 64-byte vector path (n >= 64), same overlapping-block contract as the
; 32-byte path: the loop drains [0, n-64) and the final comparison at n-64
; covers the tail, overlapping already-compared bytes for non-multiples.
; ---------------------------------------------------------------------------
vec64_preheader:
  %vec64_limit = sub nuw i64 %n, 64
  %has_vec64_body = icmp ne i64 %vec64_limit, 0
  br i1 %has_vec64_body, label %vec64_loop, label %vec64_final

vec64_loop:
  %vec64_i = phi i64 [ 0, %vec64_preheader ], [ %vec64_i_next, %vec64_loop_continue ]
  %vec64_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %vec64_i
  %vec64_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %vec64_i
  %vec64_a = load <64 x i8>, ptr addrspace(1) %vec64_a_ptr, align 1
  %vec64_b = load <64 x i8>, ptr addrspace(1) %vec64_b_ptr, align 1
  %vec64_ne = icmp ne <64 x i8> %vec64_a, %vec64_b
  %vec64_ne_mask = bitcast <64 x i1> %vec64_ne to i64
  %vec64_any_ne = icmp ne i64 %vec64_ne_mask, 0
  br i1 %vec64_any_ne, label %return_one, label %vec64_loop_continue

vec64_loop_continue:
  %vec64_i_next = add i64 %vec64_i, 64
  %vec64_more = icmp ult i64 %vec64_i_next, %vec64_limit
  br i1 %vec64_more, label %vec64_loop, label %vec64_final

vec64_final:
  %vec64_final_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %vec64_limit
  %vec64_final_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %vec64_limit
  %vec64_final_a = load <64 x i8>, ptr addrspace(1) %vec64_final_a_ptr, align 1
  %vec64_final_b = load <64 x i8>, ptr addrspace(1) %vec64_final_b_ptr, align 1
  %vec64_final_ne = icmp ne <64 x i8> %vec64_final_a, %vec64_final_b
  %vec64_final_mask = bitcast <64 x i1> %vec64_final_ne to i64
  %vec64_final_any = icmp ne i64 %vec64_final_mask, 0
  br i1 %vec64_final_any, label %return_one, label %return_zero

qword_dispatch:
  %is_qword_sized = icmp ugt i64 %n, 7
  br i1 %is_qword_sized, label %qword_preheader, label %dword_dispatch

; ---------------------------------------------------------------------------
; 32-byte vector path (n >= 32). The loop drains [0, n-32); the final
; overlapping comparison at n-32 covers the last 32 bytes, so a length that
; is a multiple of 32 compares the last chunk exactly once, and any other
; length overlaps already-compared bytes instead of needing a scalar tail.
; ---------------------------------------------------------------------------
vec_preheader:
  %vec_limit = sub nuw i64 %n, 32
  %has_vec_body = icmp ne i64 %vec_limit, 0
  br i1 %has_vec_body, label %vec_loop, label %vec_final

vec_loop:
  %vec_i = phi i64 [ 0, %vec_preheader ], [ %vec_i_next, %vec_loop_continue ]
  %vec_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %vec_i
  %vec_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %vec_i
  %vec_a = load <32 x i8>, ptr addrspace(1) %vec_a_ptr, align 1
  %vec_b = load <32 x i8>, ptr addrspace(1) %vec_b_ptr, align 1
  %vec_ne = icmp ne <32 x i8> %vec_a, %vec_b
  %vec_ne_mask = bitcast <32 x i1> %vec_ne to i32
  %vec_any_ne = icmp ne i32 %vec_ne_mask, 0
  br i1 %vec_any_ne, label %return_one, label %vec_loop_continue

vec_loop_continue:
  %vec_i_next = add i64 %vec_i, 32
  %vec_more = icmp ult i64 %vec_i_next, %vec_limit
  br i1 %vec_more, label %vec_loop, label %vec_final

vec_final:
  %final_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %vec_limit
  %final_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %vec_limit
  %final_a = load <32 x i8>, ptr addrspace(1) %final_a_ptr, align 1
  %final_b = load <32 x i8>, ptr addrspace(1) %final_b_ptr, align 1
  %final_ne = icmp ne <32 x i8> %final_a, %final_b
  %final_ne_mask = bitcast <32 x i1> %final_ne to i32
  %final_any_ne = icmp ne i32 %final_ne_mask, 0
  br i1 %final_any_ne, label %return_one, label %return_zero

; ---------------------------------------------------------------------------
; Overlapping qword path (8 <= n < 32). The loop drains [0, n-8); the final
; comparison at n-8 covers the last 8 bytes, overlapping bytes the loop
; already compared when n is not a multiple of 8.
; ---------------------------------------------------------------------------
qword_preheader:
  %qword_limit = sub nuw i64 %n, 8
  %has_qword_body = icmp ne i64 %qword_limit, 0
  br i1 %has_qword_body, label %qword_loop, label %qword_final

qword_loop:
  %qword_i = phi i64 [ 0, %qword_preheader ], [ %qword_i_next, %qword_loop_continue ]
  %qword_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %qword_i
  %qword_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %qword_i
  %qword_a = load i64, ptr addrspace(1) %qword_a_ptr, align 1
  %qword_b = load i64, ptr addrspace(1) %qword_b_ptr, align 1
  %qword_ne = icmp ne i64 %qword_a, %qword_b
  br i1 %qword_ne, label %return_one, label %qword_loop_continue

qword_loop_continue:
  %qword_i_next = add i64 %qword_i, 8
  %qword_more = icmp ult i64 %qword_i_next, %qword_limit
  br i1 %qword_more, label %qword_loop, label %qword_final

qword_final:
  %qword_fa_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %qword_limit
  %qword_fb_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %qword_limit
  %qword_fa = load i64, ptr addrspace(1) %qword_fa_ptr, align 1
  %qword_fb = load i64, ptr addrspace(1) %qword_fb_ptr, align 1
  %qword_fne = icmp ne i64 %qword_fa, %qword_fb
  br i1 %qword_fne, label %return_one, label %return_zero

; ---------------------------------------------------------------------------
; Tiny bulk equality compares only bytes inside the validated ranges.
; Operand ranges may overlap; all packed accesses are unaligned.
; ---------------------------------------------------------------------------
dword_dispatch:
  %is_dword_sized = icmp ugt i64 %n, 3
  br i1 %is_dword_sized, label %dword_first, label %word_dispatch

dword_first:
  %dword_a = load i32, ptr addrspace(1) %a, align 1
  %dword_b = load i32, ptr addrspace(1) %b, align 1
  %dword_ne = icmp ne i32 %dword_a, %dword_b
  br i1 %dword_ne, label %return_one, label %dword_tail_check

dword_tail_check:
  %dword_limit = sub nuw i64 %n, 4
  %has_dword_tail = icmp ne i64 %dword_limit, 0
  br i1 %has_dword_tail, label %dword_tail, label %return_zero

dword_tail:
  %dword_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %dword_limit
  %dword_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %dword_limit
  %dword_fa = load i32, ptr addrspace(1) %dword_a_ptr, align 1
  %dword_fb = load i32, ptr addrspace(1) %dword_b_ptr, align 1
  %dword_fne = icmp ne i32 %dword_fa, %dword_fb
  br i1 %dword_fne, label %return_one, label %return_zero

word_dispatch:
  %is_word_sized = icmp ugt i64 %n, 1
  br i1 %is_word_sized, label %word_first, label %byte_compare

word_first:
  %word_a = load i16, ptr addrspace(1) %a, align 1
  %word_b = load i16, ptr addrspace(1) %b, align 1
  %word_ne = icmp ne i16 %word_a, %word_b
  br i1 %word_ne, label %return_one, label %word_tail_check

word_tail_check:
  %word_limit = sub nuw i64 %n, 2
  %has_word_tail = icmp ne i64 %word_limit, 0
  br i1 %has_word_tail, label %word_tail, label %return_zero

word_tail:
  %word_a_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %a, i64 %word_limit
  %word_b_ptr = getelementptr inbounds nuw i8, ptr addrspace(1) %b, i64 %word_limit
  %word_fa = load i16, ptr addrspace(1) %word_a_ptr, align 1
  %word_fb = load i16, ptr addrspace(1) %word_b_ptr, align 1
  %word_fne = icmp ne i16 %word_fa, %word_fb
  br i1 %word_fne, label %return_one, label %return_zero

byte_compare:
  %byte_a = load i8, ptr addrspace(1) %a, align 1
  %byte_b = load i8, ptr addrspace(1) %b, align 1
  %byte_eq = icmp eq i8 %byte_a, %byte_b
  br i1 %byte_eq, label %return_zero, label %return_one

return_zero:
  ret i32 0

return_one:
  ret i32 1
}

attributes #0 = { nounwind "gc-leaf-function" }

; Java array lengths are nonnegative signed integers.
!0 = !{i32 0, i32 -2147483648}

!1 = distinct !{!1, !2}
!2 = !{!"llvm.licm.disable"}
