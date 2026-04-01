;
; Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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

; ==============================================================================
; Platform-Specific JavaOp Implementation: x86_64 (AMD64)
; ==============================================================================

; Get the stack pointer
define hotspotcc i64 @jeandle.get_stack_pointer() "lower-phase"="0" {
    %stack_pointer = call i64 @llvm.read_register.i64(metadata !{!"rsp"})
    ret i64 %stack_pointer
}

; Try to release the monitor lock when the lock is inflated
define hotspotcc i1 @jeandle.try_release_monitor_lock(i64 %mark_word) "lower-phase"="0" {
entry:
  %monitor_ptr = inttoptr i64 %mark_word to ptr
  %recursions_offset_no_monitor_value = load i32, ptr @ObjectMonitor.recursions_offset_no_monitor_value
  %recursions_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %recursions_offset_no_monitor_value
  %recursions = load i64, ptr %recursions_addr, align 8
  %is_recursive_monitor_unlock = icmp ne i64 %recursions, 0
  br i1 %is_recursive_monitor_unlock, label %decrease_recursions, label %check_for_waiters

decrease_recursions:
  %new_recursions = sub i64 %recursions, 1
  store i64 %new_recursions, ptr %recursions_addr, align 8
  br label %return_true

check_for_waiters:
  %cxq_offset_no_monitor_value = load i32, ptr @ObjectMonitor.cxq_offset_no_monitor_value
  %cxq_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %cxq_offset_no_monitor_value
  %cxq = load atomic i64, ptr %cxq_addr unordered, align 8
  %EntryList_offset_no_monitor_value = load i32, ptr @ObjectMonitor.EntryList_offset_no_monitor_value
  %EntryList_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %EntryList_offset_no_monitor_value
  %EntryList = load atomic i64, ptr %EntryList_addr unordered, align 8
  %is_cxq_null = icmp eq i64 %cxq, 0
  %is_EntryList_null = icmp eq i64 %EntryList, 0
  %has_no_waiters = and i1 %is_cxq_null, %is_EntryList_null
  %owner_offset_no_monitor_value = load i32, ptr @ObjectMonitor.owner_offset_no_monitor_value
  %owner_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %owner_offset_no_monitor_value
  br i1 %has_no_waiters, label %clear_monitor_owner, label %check_candidate_thread

check_candidate_thread:
  %succ_offset_no_monitor_value = load i32, ptr @ObjectMonitor.succ_offset_no_monitor_value
  %succ_addr = getelementptr inbounds i8, ptr %monitor_ptr, i32 %succ_offset_no_monitor_value
  %succ = load atomic volatile i64, ptr %succ_addr unordered, align 8
  %has_no_candidate_threads = icmp eq i64 %succ, 0
  br i1 %has_no_candidate_threads, label %return_false, label %try_release_monitor

clear_monitor_owner:
  store atomic volatile i64 0, ptr %owner_addr release, align 8
  br label %return_true

try_release_monitor:
  store atomic volatile i64 0, ptr %owner_addr unordered, align 8
  fence seq_cst
  %new_succ = load atomic volatile i64, ptr %succ_addr unordered, align 8
  %is_candidate_thread_null = icmp eq i64 %new_succ, 0
  br i1 %is_candidate_thread_null, label %reacquire_monitor, label %return_true

reacquire_monitor:
  %current_thread = call hotspotcc ptr @jeandle.current_thread()
  %current_thread_as_int = ptrtoint ptr %current_thread to i64
  %monitor_cas = cmpxchg ptr %owner_addr, i64 0, i64 %current_thread_as_int acq_rel monotonic, align 8
  %monitor_reacquied = extractvalue { i64, i1 } %monitor_cas, 1
  br i1 %monitor_reacquied, label %return_false, label %return_true

return_true:
  ret i1 true

return_false:
  ret i1 false
}
