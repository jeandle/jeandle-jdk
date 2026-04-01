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

#ifndef CPU_RISCV_JEANDLEOOPRELOCATION_RISCV_HPP
#define CPU_RISCV_JEANDLEOOPRELOCATION_RISCV_HPP

 public:
  static RelocationHolder spec(int oop_index, int offset, int64_t rel_offset) {
    assert(oop_index > 0, "must be a pool-resident oop");
    return RelocationHolder::construct<jeandle_oop_Relocation>(oop_index, offset, rel_offset);
  }

  int64_t rel_offset() { return _rel_offset; }

 private:
  jeandle_oop_Relocation(int oop_index, int offset, int64_t rel_offset)
      : oop_Relocation(oop_index, offset, relocInfo::jeandle_oop_type),
        _rel_offset(rel_offset) { }

  int64_t _rel_offset; // offset relative to the high part instruction (riscv only)

#endif // CPU_RISCV_JEANDLEOOPRELOCATION_RISCV_HPP
