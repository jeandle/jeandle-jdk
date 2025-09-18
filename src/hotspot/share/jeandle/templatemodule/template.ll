; This file defines some LLVM functions which we call them "JavaOp". Each JavaOp represents a high-level java
; operation. These functions will be used by some passes to do Java-related optimizations. After corresponding
; optimizations, JavaOp will be inlined(lowered) by JavaOperationLower passes.

; =============================================================================================================
; Declare these runtime-related constants as global variables. The VM will define them as constants during
; Jeandle compiler initialization time.
;

@arrayOopDesc.length_offset_in_bytes = external global i32

define hotspotcc i32 @jeandle.instanceof(i32 %super_kid, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" {
    ; TODO: There should be a real implementation of instanceof here.
    ret i32 1
}

define hotspotcc i32 @jeandle.arraylength(ptr addrspace(1) nocapture readonly %array_oop) noinline "lower-phase"="0"  {
entry:
  %length_offset = load i32, ptr @arrayOopDesc.length_offset_in_bytes
  %length_addr = getelementptr inbounds i8, ptr addrspace(1) %array_oop, i32 %length_offset
  %length = load atomic i32, ptr addrspace(1) %length_addr unordered, align 4
  ret i32 %length
}
