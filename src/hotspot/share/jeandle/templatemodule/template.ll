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

; Byte offsets of Array<Klass*> structure fields.
@KlassArray.base_offset_in_bytes = external global i32
@KlassArray.length_offset_in_bytes = external global i32

; Byte offsets of arrayOopDesc structure fields.
@arrayOopDesc.length_offset_in_bytes = external global i32
@arrayOopDesc.base_offset_in_bytes.int = external global i32

; Byte offsets for Klass structure fields.
@Klass.secondary_super_cache_offset = external global i32
@Klass.secondary_supers_offset = external global i32
@Klass.super_check_offset_offset = external global i32
@ObjArrayKlass.element_klass_offset = external global i32

; Byte offsets for oopDesc structure fields.
@oopDesc.klass_offset_in_bytes = external global i32

; Keep use to lately-used java operations, until it is lowered.
@llvm.used = appending addrspace(1) global [1 x ptr] [ptr @jeandle.card_table_barrier], section "llvm.metadata"

; Load klass pointer from oop
define hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" {
  %klass_offset = load i32, ptr @oopDesc.klass_offset_in_bytes
  %klass_addr = getelementptr inbounds i8, ptr addrspace(1) %oop, i32 %klass_offset
  %klass = load atomic ptr addrspace(0), ptr addrspace(1) %klass_addr unordered, align 8
  ret ptr addrspace(0) %klass
}

; This is the slow path for subtype checking when the fast path fails.
define hotspotcc i1 @jeandle.check_klass_subtype_slow_path(ptr addrspace(0) nocapture %sub_klass, ptr addrspace(0) nocapture %super_klass) "lower-phase"="0" {
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
define hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) nocapture %sub_klass, ptr addrspace(0) nocapture %super_klass) "lower-phase"="0" {
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

; Implementation of Java instanceof operation.
define hotspotcc i32 @jeandle.instanceof(ptr addrspace(0) nocapture %super_klass, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_false, label %check_subtype

return_false:
  ret i32 0

check_subtype:
  %sub_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %oop)
  %is_subtype = call hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) %sub_klass, ptr addrspace(0) %super_klass)

  %is_subtype_ext = zext i1 %is_subtype to i32
  ret i32 %is_subtype_ext
}

; Implementation of Java arraylength operation.
define hotspotcc i32 @jeandle.arraylength(ptr addrspace(1) nocapture readonly %array_oop) noinline "lower-phase"="0"  {
entry:
  %length_offset = load i32, ptr @arrayOopDesc.length_offset_in_bytes
  %length_addr = getelementptr inbounds i8, ptr addrspace(1) %array_oop, i32 %length_offset
  %length = load atomic i32, ptr addrspace(1) %length_addr unordered, align 4
  ret i32 %length
}

declare hotspotcc ptr @jeandle.current_thread()
declare hotspotcc ptr addrspace(1) @new_array(ptr, i32, ptr)

; Implementation of Java anewarray and newarray operation
define private hotspotcc ptr addrspace(1) @jeandle.newarray(ptr %array_klass, i32 %length) noinline "lower-phase"="0"  {
entry:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %array_oop = call hotspotcc ptr addrspace(1) @new_array(ptr %array_klass, i32 %length, ptr %current_thread)
  ret ptr addrspace(1) %array_oop
}

; Declaration of Java card table barrier.
declare hotspotcc void @jeandle.card_table_barrier(ptr addrspace(1) %addr) noinline "lower-phase"="1";

; Implementation of Java checkcast operation
define hotspotcc i1 @jeandle.checkcast(ptr addrspace(0) nocapture %super_klass, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_true, label %check_subtype

return_true:
  ret i1 true

check_subtype:
  %sub_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %oop)
  %is_subtype = call hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) %sub_klass, ptr addrspace(0) %super_klass)

  ret i1 %is_subtype
}

; Implementation of array store check operation
define hotspotcc i1 @jeandle.array_store_check(ptr addrspace(1) nocapture %oop, ptr addrspace(1) nocapture %array_oop) noinline "lower-phase"="0" {
entry:
  %is_null = icmp eq ptr addrspace(1) %oop, null
  br i1 %is_null, label %return_true, label %check_subtype

return_true:
  ret i1 true

check_subtype:
  %array_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %array_oop)
  %element_klass_offset = load i32, ptr @ObjArrayKlass.element_klass_offset;
  %element_klass_addr = getelementptr inbounds i8, ptr addrspace(0) %array_klass, i32 %element_klass_offset
  %element_klass = load atomic ptr addrspace(0), ptr addrspace(0) %element_klass_addr unordered, align 8
  %sub_klass = call hotspotcc ptr addrspace(0) @jeandle.load_klass(ptr addrspace(1) %oop)
  %is_subtype = call hotspotcc i1 @jeandle.check_klass_subtype(ptr addrspace(0) %sub_klass, ptr addrspace(0) %element_klass)

  ret i1 %is_subtype
}

; Implementation of Java idiv operation
define hotspotcc i32 @jeandle.idiv(i32 %dividend, i32 %divisor) noinline "lower-phase"="0" {
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
define hotspotcc i32 @jeandle.irem(i32 %dividend, i32 %divisor) noinline "lower-phase"="0" {
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
define hotspotcc i64 @jeandle.ldiv(i64 %dividend, i64 %divisor) noinline "lower-phase"="0" {
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
define hotspotcc i64 @jeandle.lrem(i64 %dividend, i64 %divisor) noinline "lower-phase"="0" {
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
