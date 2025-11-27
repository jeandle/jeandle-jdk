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

// This file is used to resolve macro conflicts between LLVM and HotSpot.
// For more information on macro conflicts, see __llvmHeadersBegin__.hpp

// __hotspotHeadersBegin__.hpp undefines 'assert' from stdlib, and redefines
// 'AArch64' because it may be undefined by __llvmHeadersBegin__.hpp.

#undef assert
#ifdef SHARE_UTILITIES_DEBUG_HPP
  #define assert(p, ...) vmassert(p, __VA_ARGS__)
#endif // SHARE_UTILITIES_DEBUG_HPP

#ifdef SAVED_HOTSPOT_AARCH64
  #define AARCH64
  #undef SAVED_HOTSPOT_AARCH64
#endif // SAVED_HOTSPOT_AARCH64
