/*
 * Copyright (c) 2025, 2026, the Jeandle-JDK Authors. All Rights Reserved.
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
#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/BinaryFormat/Dwarf.h"
#include "llvm/ExecutionEngine/JITLink/riscv.h"

#include "jeandle/jeandleAssembler.hpp"
#include "jeandle/jeandleCompiledCode.hpp"
#include "jeandle/jeandleCompilation.hpp"
#include "jeandle/jeandleReloc.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"

// Get the frame size from .stack_sizes section.
void JeandleCompiledCode::setup_frame_size() {
  SectionInfo section_info(".stack_sizes");
  bool found = ReadELF::findSection(*_elf, section_info);
  JEANDLE_ERROR_ASSERT_AND_RET_VOID_ON_FAIL(found, ".stack_sizes section not found");

  llvm::DataExtractor data_extractor(llvm::StringRef(((char*)_obj->getBufferStart()) + section_info._offset, section_info._size),
  true/* IsLittleEndian */, BytesPerWord/* AddressSize */);
  uint64_t offset = 0;
  data_extractor.getUnsigned(&offset, BytesPerWord);
  uint64_t frame_size_in_bytes = data_extractor.getULEB128(&offset);
  assert(frame_size_in_bytes % StackAlignmentInBytes == 0, "frame size must be aligned");
  _frame_size = frame_size_in_bytes / BytesPerWord;
}

bool JeandleCompiledCode::pd_build_exception_handler_table() {
  SectionInfo excpet_table_section(".gcc_except_table");
  if (ReadELF::findSection(*_elf, excpet_table_section)) {
    // Start of the exception handler table.
    const char* except_table_pointer = object_start() + excpet_table_section._offset;

    // Utilize DataExtractor to decode exception handler table.
    llvm::DataExtractor data_extractor(llvm::StringRef(except_table_pointer, excpet_table_section._size),
                                       ELFT::Endianness == llvm::endianness::little, /* IsLittleEndian */
                                       BytesPerWord/* AddressSize */);
    llvm::DataExtractor::Cursor data_cursor(0 /* Offset */);

    // Now decode exception handler table.
    // See EHStreamer::emitExceptionTable in Jeandle-LLVM for corresponding encoding.

    uint8_t header_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && header_encoding == llvm::dwarf::DW_EH_PE_omit, "invalid exception handler table header");

    uint8_t type_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && type_encoding == llvm::dwarf::DW_EH_PE_omit, "invalid exception handler table type encoding");

    uint8_t call_site_encoding = data_extractor.getU8(data_cursor);
    assert(data_cursor && call_site_encoding == llvm::dwarf::DW_EH_PE_udata4, "invalid exception handler table call site encoding");

    uint64_t call_site_table_length = data_extractor.getULEB128(data_cursor);
    assert(data_cursor, "invalid exception handler table call site table length");

    uint64_t call_site_table_start = data_cursor.tell();

    while (data_cursor.tell() < call_site_table_start + call_site_table_length) {
      uint64_t start = data_extractor.getU32(data_cursor) + _prolog_length;
      assert(data_cursor, "invalid exception handler start pc");

      uint64_t length = data_extractor.getU32(data_cursor);
      assert(data_cursor, "invalid exception handler length");

      uint64_t langding_pad = data_extractor.getU32(data_cursor) + _prolog_length;
      assert(data_cursor, "invalid exception handler landing pad");

      _exception_handler_table.add_handler(start, start + length, langding_pad);

      // Read an action table entry, but we don't use it.
      data_extractor.getULEB128(data_cursor);
      assert(data_cursor, "invalid exception handler action table entry");
    }
  }
  return true;
}

bool JeandleCompiledCode::pd_resolve_reloc(JeandleAssembler& assembler,
                                           llvm::SmallVector<JeandleReloc*>& relocs,
                                           llvm::jitlink::LinkGraph* link_graph) {
  llvm::DenseMap<std::pair<LinkBlock*, llvm::orc::ExecutorAddrDiff>, LinkEdge*> rel_high_edges;

  for (auto* block : link_graph->blocks()) {
    for (auto& edge : block->edges()) {
      if (edge.getKind() == llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_PCREL_HI20) {
        rel_high_edges[{block, edge.getOffset()}] = &edge;
      }
    }
  }

  for (auto *block : link_graph->blocks()) {
    // Resolve relocations in the compiled code and constant pool.
    if (block->getSection().getName().compare(".text") != 0 &&
        !block->getSection().getName().starts_with(".rodata")) {
      continue;
    }
    for (auto& edge : block->edges()) {
      auto& target = edge.getTarget();
      llvm::StringRef target_name = target.hasName() ? *(target.getName()) : "";

      if (JeandleAssembler::is_routine_call_reloc(target, edge.getKind())) {
        // Routine call relocations.
        address target_addr = JeandleRuntimeRoutine::get_routine_entry(target_name);

        int inst_end_offset = JeandleAssembler::fixup_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

        // TODO: Set the right bci.
        CallSiteInfo* call_info = new CallSiteInfo(JeandleCompiledCall::ROUTINE_CALL, target_addr, -1/* bci */);
        if (JeandleRuntimeRoutine::is_gc_leaf(target_addr)) {
          relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, nullptr /* no oopmap */, call_info));
        } else {
          // JeandleCallReloc for a non-gc-leaf routine call site will be created during stackmaps resolving because an oopmap is required.
          _routine_call_sites[inst_end_offset] = call_info;
        }
      } else if (JeandleAssembler::is_external_call_reloc(target, edge.getKind())) {
        // External call relocations.
        address target_addr = (address)DynamicLibrary::SearchForAddressOfSymbol(target_name.str().c_str());
        JEANDLE_ERROR_ASSERT_AND_RET_ON_FAIL(target_addr, "failed to find external symbol", true);

        int inst_end_offset = JeandleAssembler::fixup_call_inst_offset(static_cast<int>(block->getAddress().getValue() + edge.getOffset()));

        // TODO: Set the right bci.
        CallSiteInfo* call_info = new CallSiteInfo(JeandleCompiledCall::EXTERNAL_CALL, target_addr, -1/* bci */);
        // LLVM doesn't rewrite intrinsic calls to statepoints, so we don't need oopmaps for external calls.
        relocs.push_back(new JeandleCallReloc(inst_end_offset, _env, _method, nullptr /* no oopmap */, call_info));
      } else if (JeandleAssembler::is_section_word_reloc(edge, rel_high_edges)) {
        int64_t rel_offset = 0;
        auto actual_edge = edge;
        if (edge.getKind() != llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_PCREL_HI20 &&
            edge.getKind() != llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_ADD32 &&
            edge.getKind() != llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_SUB32) {
          auto high_edge = JeandleAssembler::getRelHighEdge(edge, rel_high_edges);
          assert(high_edge != nullptr, "high edge not found");
          actual_edge = *high_edge;
          rel_offset = edge.getOffset() - high_edge->getOffset();
        }
        address target_addr;
        int reloc_offset;
        int reloc_section;
        if (block->getSection().getName().compare(".text") == 0 &&
            (target.getSection().getName().compare(".text") == 0 ||
             target.getSection().getName().starts_with(".rodata"))) {
          target_addr = resolve_const_edge(*block, actual_edge, assembler);
          RETURN_ON_JEANDLE_ERROR(true);
          reloc_offset = static_cast<int>(block->getAddress().getValue() + edge.getOffset());
          reloc_section = CodeBuffer::SECT_INSTS;
          relocs.push_back(new JeandleSectionWordReloc(reloc_offset, edge, target_addr, reloc_section, rel_offset));
        } else if (block->getSection().getName().starts_with(".rodata") &&
                   target.getSection().getName().compare(".text") == 0) {
          assert(edge.getKind() == llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_ADD32, "Unexpected link kind");
          target_addr = _code_buffer.insts()->start() + target.getOffset();
          address reloc_base = lookup_const_section(block->getSection().getName(), assembler);
          RETURN_ON_JEANDLE_ERROR(true);
          reloc_offset = reloc_base + edge.getOffset() - _code_buffer.consts()->start();
          reloc_section = CodeBuffer::SECT_CONSTS;
          relocs.push_back(new JeandleSectionWordReloc(reloc_offset, edge, target_addr, reloc_section, 0));
        } else {
          assert(block->getSection().getName().starts_with(".rodata"), "invalid reloc section");
          assert(target.getSection().getName().starts_with(".rodata"), "invalid target section");
          assert(edge.getKind() == llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_SUB32, "Unexpected link kind");
          target_addr = _code_buffer.consts()->start() + target.getOffset();
          address reloc_base = lookup_const_section(block->getSection().getName(), assembler);
          reloc_offset = reloc_base + edge.getOffset() - _code_buffer.consts()->start();
          reloc_section = CodeBuffer::SECT_CONSTS;
          relocs.push_back(new JeandleSectionWordReloc(reloc_offset, edge, target_addr, reloc_section, 0));
        }
      } else if (JeandleAssembler::is_oop_reloc(edge, rel_high_edges)) {
        // Oop relocations.
        int64_t rel_offset = 0;
        auto actual_edge = edge;
        if (edge.getKind() != llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_PCREL_HI20) {
          auto high_edge = JeandleAssembler::getRelHighEdge(edge, rel_high_edges);
          assert(high_edge != nullptr, "high edge not found");
          actual_edge = *high_edge;
          rel_offset = edge.getOffset() - high_edge->getOffset();
        }
        auto actual_name = actual_edge.getTarget().getName();
        relocs.push_back(new JeandleOopReloc(static_cast<int>(block->getAddress().getValue() + edge.getOffset()),
                                             _oop_handles[(*actual_name)],
                                             edge.getAddend(),
                                             rel_offset));
      } else if (JeandleAssembler::is_oop_addr_reloc(edge, rel_high_edges)) {
        // Oop addr relocations.
        int64_t rel_offset = 0;
        auto actual_edge = edge;
        if (edge.getKind() != llvm::jitlink::riscv::EdgeKind_riscv::R_RISCV_PCREL_HI20) {
          auto high_edge = JeandleAssembler::getRelHighEdge(edge, rel_high_edges);
          assert(high_edge != nullptr, "high edge not found");
          actual_edge = *high_edge;
          rel_offset = edge.getOffset() - high_edge->getOffset();
        }
        auto actual_name = actual_edge.getTarget().getName();
        relocs.push_back(new JeandleOopAddrReloc(static_cast<int>(block->getAddress().getValue() + edge.getOffset()),
                                                 _oop_handles[(*actual_name)],
                                                 rel_offset));
      } else {
        // Unhandled relocations
        ShouldNotReachHere();
      }
    }
  }
  return true;
}
