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
  product(bool, JeandleUseProfiledVirtualCallDevirtualization, true,        \
          "Use receiver type profile to devirtualize "                      \
          "invokevirtual/invokeinterface calls in Jeandle")                 \
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
// end of JEANDLE_FLAGS

DECLARE_FLAGS(JEANDLE_FLAGS)

#endif // SHARE_JEANDLE_GLOBALS_HPP
