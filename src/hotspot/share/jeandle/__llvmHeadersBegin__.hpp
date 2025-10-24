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

// Jeandle includes many LLVM header files, and produces some macro conflicts with Hotspot header files.
// The current conflicts are:
//   1. AARCH64
//   2. assert
// We use __llvmHeadersBegin__.hpp and __hotspotHeadersBegin__.hpp to solve them.

// All Jeandle source files should include __llvmHeadersBegin__.hpp and __hotspotHeadersBegin__.hpp
// like this:
//   #include "jeandle/__llvmHeadersBegin__.hpp"
//   // Here we can include header files from LLVM.
//
//   #include "jeandle/..."
//   #include "jeandle/..."
//
//   #include "jeandle/__hotspotHeadersBegin__.hpp"
//   // Here we can include header files from Hotspot.

// __llvmHeadersBegin__.hpp is used to define 'assert' from stdlib for LLVM, because Hotspot uses a self defined 'assert'
// and it's not compatible with stdlib's 'assert'. This header file also undefines 'AARCH64' which is defined by Hotspot
// and conflicts with LLVM's 'AArch64'.

#include <cassert>
#ifdef AARCH64
  #define SAVED_HOTSPOT_AARCH64
  #undef AARCH64
#endif // AARCH64
