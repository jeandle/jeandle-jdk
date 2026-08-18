/*
 * Copyright (c) 2025, the Jeandle-JDK Authors. All Rights Reserved.
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

#ifndef SHARE_JEANDLE_GLOBALS_HPP
#define SHARE_JEANDLE_GLOBALS_HPP

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "runtime/globals_shared.hpp"
#include "utilities/macros.hpp"
//
// Declare all global flags used by jeandle.
//
#define JEANDLE_FLAGS(develop,                                              \
                      develop_pd,                                           \
                      product,                                              \
                      product_pd,                                           \
                      notproduct,                                           \
                      range,                                                \
                      constraint)                                           \
                                                                            \
  product(bool, JeandleDumpObjects, false,                                  \
          "Dump object files after compilation")                            \
                                                                            \
  product(bool, JeandleDumpIR, false,                                       \
          "Dump ir before and after optimization")                          \
                                                                            \
  product(ccstr, JeandleDumpDirectory, nullptr,                             \
          "Dump destination for all Jeandle items")                         \
                                                                            \
  develop(bool, JeandleCrashOnError, DEBUG_ONLY(true) NOT_DEBUG(false),     \
          "Crash JVM on Jeandle errors")                                    \
                                                                            \
  product(bool, JeandleDumpRuntimeStubs, false,                             \
          "Dump Jeandle runtime stubs")                                     \
                                                                            \
  product(bool, JeandleUseHotspotIntrinsics, false,                         \
          "Prefer Hotspot intrinsics over LLVM intrinsics")                 \
                                                                            \
  product(ccstr, JeandleLLVMOptions, nullptr,                               \
          "Additional LLVM command line options")                           \
                                                                            \
  product(bool, JeandleRecordVMCallbacks, false,                            \
          "Record VM callback invocations for standalone LLVM testing")     \
                                                                            \
  product(bool, JeandleUseProfile, true,                                    \
          "Use interpreter/C1 profile (MDO) for branch/switch weights, "    \
          "unstable-if branch pruning")                                     \
                                                                            \
  product(intx, JeandleNodeCountInliningCutoff, 18000,                      \
          "If root LLVM IR instruction count exceeds limit stop inlining."  \
          "This value roughly follows C2's cutoff today; tune it later"     \
          "with real Jeandle workloads")                                    \
          range(0, max_jint)                                                \
                                                                            \
  product(bool, JeandlePrintInlineTree, false,                              \
          "Print Jeandle inline tree before installing compiled code")      \
                                                                            \
  product(bool, JeandleDoPEA, true,                                         \
          "Run Partial Escape Analysis (PEA) in the Jeandle optimization "  \
          "pipeline")                                                       \
                                                                            \
  product(bool, JeandleEliminateLocks, true,                                \
          "Enable lock elimination in Jeandle PEA")                         \
                                                                            \
  product(uintx, JeandleLoopStripMiningIter, 0,                             \
          "Number of iterations between safepoint polls in strip-mined "    \
          "counted loops (0 disables strip mining).")                       \
          range(0, max_juint)                                               \
                                                                            \
  NOT_COMPILER2(product(intx, ArrayOperationPartialInlineSize, 0,           \
          DIAGNOSTIC,                                                       \
          "Partial inline size used for small array operations"             \
          "(e.g. copy,cmp) acceleration.")                                  \
          range(0, 256))                                                    \
                                                                            \
  NOT_COMPILER2(product(bool, EliminateAutoBox, true,                       \
          "Control optimizations for autobox elimination"))                \
                                                                            \
  NOT_COMPILER2(product(bool, DoEscapeAnalysis, true,                       \
          "Perform escape analysis"))                                      \
                                                                            \
  NOT_COMPILER2(product(bool, EliminateAllocations, true,                   \
          "Use escape analysis to eliminate allocations"))                 \
                                                                            \
  NOT_COMPILER2(develop(bool, InlineAccessors, true,                        \
          "inline accessor methods (get/set)"))                            \
                                                                            \
  NOT_COMPILER2(product(intx, MaxInlineLevel, 15,                           \
          "maximum number of nested calls that are inlined by high tier "   \
          "compiler"                                                       \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product(intx, MaxRecursiveInlineLevel, 1,                   \
          "maximum number of nested recursive calls that are inlined by "   \
          "high tier compiler"                                             \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product(intx, InlineSmallCode, 1000,                        \
          "Only inline already compiled methods if their code size is "     \
          "less than this"                                                 \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product(intx, MaxInlineSize, 35,                            \
          "The maximum bytecode size of a method to be inlined by high "    \
          "tier compiler"                                                  \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product_pd(intx, FreqInlineSize,                            \
          "The maximum bytecode size of a frequent method to be inlined"    \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product(intx, MaxTrivialSize, 6,                            \
          "The maximum bytecode size of a trivial method to be inlined by " \
          "high tier compiler"                                             \
          range(0, max_jint)))                                              \
                                                                            \
  NOT_COMPILER2(product(bool, IncrementalInline, true,                      \
          "do post parse inlining"))                                       \
                                                                            \
  NOT_COMPILER2(develop(bool, PoisonOSREntry, true,                         \
          "Detect abnormal calls to OSR code"))                            \
                                                                            \
  NOT_COMPILER2(product(bool, InlineSecondarySupersTest, true, DIAGNOSTIC,  \
          "Inline the secondary supers hash lookup."))                     \
                                                                            \
// end of JEANDLE_FLAGS

DECLARE_FLAGS(JEANDLE_FLAGS)

#endif // SHARE_JEANDLE_GLOBALS_HPP
