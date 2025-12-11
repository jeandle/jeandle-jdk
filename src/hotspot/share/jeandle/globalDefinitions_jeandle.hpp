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

#ifndef SHARE_GLOBALDEFINITIONS_JEANDLE_HPP
#define SHARE_GLOBALDEFINITIONS_JEANDLE_HPP

#ifdef LINUX
// Only libmath is supported for now.
constexpr const char* LibmName = "libm.so.6";
#else
#error "Unsupported OS"
#endif

// USE_TRAMPOLINE_STUB_FIX_OWNER enables relocating trampoline stubs. Needed for external function calls.
#define USE_TRAMPOLINE_STUB_FIX_OWNER

#endif // SHARE_GLOBALDEFINITIONS_JEANDLE_HPP
