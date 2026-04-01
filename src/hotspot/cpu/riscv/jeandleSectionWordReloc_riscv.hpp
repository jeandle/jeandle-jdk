/*
 * Copyright (c) 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef CPU_RISCV_JEANDLESECTIONWORDRELOC_RISCV_HPP
#define CPU_RISCV_JEANDLESECTIONWORDRELOC_RISCV_HPP

 public:
  JeandleSectionWordReloc(int reloc_offset, LinkEdge& edge, address target, int reloc_section, int64_t rel_offset) :
      JeandleReloc(reloc_offset),
      _kind(edge.getKind()),
      _addend(edge.getAddend()),
      _target(target),
      _reloc_section(reloc_section),
      _rel_offset(rel_offset) {}

 private:
  int64_t _rel_offset; // offset relative to the high part instruction (riscv only)

#endif // CPU_RISCV_JEANDLESECTIONWORDRELOC_RISCV_HPP
