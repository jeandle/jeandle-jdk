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

#ifndef CPU_RISCV_JEANDLESECTIONWORDRELOCATION_RISCV_HPP
#define CPU_RISCV_JEANDLESECTIONWORDRELOCATION_RISCV_HPP

 public:
  static RelocationHolder spec(address target, int section, int offset, int64_t rel_offset) {
    return RelocationHolder::construct<jeandle_section_word_Relocation>(target, section, offset, rel_offset);
  }

  jeandle_section_word_Relocation(address target, int section, int offset, int64_t rel_offset)
      : section_word_Relocation(target, section, relocInfo::jeandle_section_word_type),
        _offset(offset),
        _rel_offset(rel_offset) { }

  int64_t rel_offset() { return _rel_offset; }

  void set_rel_offset(int64_t rel_offset) {
    _rel_offset = rel_offset;

    // See jeandle_section_word_Relocation::pack_data_to/unpack_data.
    jint hi = high(_rel_offset);
    jint lo = low(_rel_offset);
    short* dp = data();
    int dlen = datalen();

    if (dp != nullptr && dlen >= 12) {
      dp[8]  = relocInfo::data0_from_int(hi);
      dp[9]  = relocInfo::data1_from_int(hi);
      dp[10] = relocInfo::data0_from_int(lo);
      dp[11] = relocInfo::data1_from_int(lo);
    }
  }

 private:
  // R_RISCV_PCREL_HI20 and R_RISCV_PCREL_LO12_I: offset relative to the high part instruction (riscv only)
  // R_RISCV_ADD32 and R_RISCV_SUB32: the previous target address
  int64_t _rel_offset;

#endif // CPU_RISCV_JEANDLESECTIONWORDRELOCATION_RISCV_HPP
