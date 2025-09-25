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

#ifndef SHARE_JEANDLE_COMPILED_CODE_HPP
#define SHARE_JEANDLE_COMPILED_CODE_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ExecutionEngine/JITLink/JITLink.h"
#include "llvm/IR/Statepoint.h"
#include "llvm/Object/ELFObjectFile.h"
#include "llvm/Object/StackMapParser.h"
#include "llvm/Support/MemoryBuffer.h"

#include "jeandle/jeandleExceptionHandlerTable.hpp"
#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleReadELF.hpp"
#include "jeandle/jeandleResourceObj.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "asm/codeBuffer.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciMethod.hpp"
#include "code/exceptionHandlerTable.hpp"

class CallSiteInfo : public JeandleCompilationResourceObj {
 public:
  CallSiteInfo(JeandleCompiledCall::Type type,
               address target,
               int bci,
               uint64_t statepoint_id = llvm::StatepointDirectives::DefaultStatepointID) :
               _type(type),
               _target(target),
               _bci(bci),
               _statepoint_id(statepoint_id) {
#ifdef ASSERT
    // We don't need to assign a unique statepoint id for each routine call site, only call type and target is used.
    bool use_default_statepoint_id = (statepoint_id == llvm::StatepointDirectives::DefaultStatepointID);
    bool is_routine_call = (type == JeandleCompiledCall::ROUTINE_CALL);
    assert(use_default_statepoint_id == is_routine_call, "routine calls should use the default statepoint id");
#endif // ASSERT
  }

  JeandleCompiledCall::Type type() const { return _type; }
  uint64_t statepoint_id() const { return _statepoint_id; }
  address target() const { return _target; }
  int bci() const { return _bci; }

 private:
  JeandleCompiledCall::Type _type;
  address _target;
  int _bci;

  // Used to distinguish each call site in stackmaps.
  uint64_t _statepoint_id;
};

using ObjectBuffer = llvm::MemoryBuffer;
using LinkBlock   = llvm::jitlink::Block;
using LinkEdge    = llvm::jitlink::Edge;
using StackMapParser = llvm::StackMapParser<ELFT::Endianness>;

class JeandleAssembler;
class JeandleCompiledCode : public StackObj {
 public:
  // For compiled Java methods.
  JeandleCompiledCode(ciEnv* env,
                      ciMethod* method) :
                      _obj(nullptr),
                      _elf(nullptr),
                      _code_buffer("JeandleCompiledCode"),
                      _frame_size(-1),
                      _prolog_length(-1),
                      _env(env),
                      _method(method),
                      _routine_entry(nullptr),
                      _func_name(JeandleFuncSig::method_name(_method)) {}

  // For compiled Jeandle runtime stubs.
  JeandleCompiledCode(ciEnv* env, const char* func_name) :
                      _obj(nullptr),
                      _elf(nullptr),
                      _code_buffer("JeandleCompiledStub"),
                      _frame_size(-1),
                      _prolog_length(-1),
                      _env(env),
                      _method(nullptr),
                      _routine_entry(nullptr),
                      _func_name(func_name) {}

  void install_obj(std::unique_ptr<ObjectBuffer> obj);

  void push_non_routine_call_site(CallSiteInfo* call_site) { _non_routine_call_sites.push_back(call_site); }
  uint64_t next_statepoint_id() { return _non_routine_call_sites.size(); }

  llvm::StringMap<jobject>& oop_handles() { return _oop_handles; }

  const char* object_start() const { return _obj->getBufferStart(); }
  size_t object_size() const { return _obj->getBufferSize(); }

  CodeBuffer* code_buffer() { return &_code_buffer; }

  CodeOffsets* offsets() { return &_offsets; }

  JeandleExceptionHandlerTable* exception_handler_table() { return &_exception_handler_table; }

  ImplicitExceptionTable* implicit_exception_table() { return &_implicit_exception_table; }

  int frame_size() const { return _frame_size; }

  address routine_entry() const { return _routine_entry; }
  void set_routine_entry(address entry) { _routine_entry = entry; }

  // Generate relocations, stubs and debug information.
  void finalize();

 private:
  std::unique_ptr<ObjectBuffer> _obj; // Compiled instructions.
  std::unique_ptr<ELFObject> _elf;
  CodeBuffer _code_buffer; // Relocations and stubs.

  // Call sites in our compiled code:
  // Note that the main different between routine calls and non-routine calls is, routine calls are found
  // from relocation of compiled objects directly, and non-routine calls are found from stackmaps then
  // matched with the compile-time generated statepoint id.
  llvm::DenseMap<int, CallSiteInfo*> _routine_call_sites; // Contains all routine call sites, constructed from
                                                          // relocations of compiled object in resolve_reloc_info.
  llvm::SmallVector<CallSiteInfo*> _non_routine_call_sites; // Contains all other call sites,
                                                            // constructed during LLVM IR generating.

  llvm::StringMap<address> _const_sections;
  llvm::StringMap<jobject> _oop_handles;
  CodeOffsets _offsets;
  JeandleExceptionHandlerTable _exception_handler_table;
  ImplicitExceptionTable _implicit_exception_table;
  int _frame_size;
  int _prolog_length;
  ciEnv* _env;
  ciMethod* _method;
  address _routine_entry;
  std::string _func_name;

  void setup_frame_size();

  void resolve_reloc_info(JeandleAssembler& assmebler);

  // Lookup address of const section in CodeBuffer.
  address lookup_const_section(llvm::StringRef name, JeandleAssembler& assmebler);
  address resolve_const_edge(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assmebler);

  OopMap* build_oop_map(StackMapParser::record_iterator& record);

  void build_exception_handler_table();

  int frame_size_in_slots();
};

#endif // SHARE_JEANDLE_COMPILED_CODE_HPP
