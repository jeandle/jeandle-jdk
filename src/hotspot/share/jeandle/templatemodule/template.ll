; This file defines some LLVM functions which we call them "JavaOp". Each JavaOp represents a high-level java
; operation. These functions will be used by some passes to do Java-related optimizations. After corresponding
; optimizations, JavaOp will be inlined(lowered) by JavaOperationLower passes.

define hotspotcc i32 @jeandle.instanceof(i32 %super_kid, ptr addrspace(1) nocapture %oop) noinline "lower-phase"="0" {
    ; TODO: There should be a real implementation of instanceof here.
    ret i32 1
}
