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

#include <cassert>
#include "llvm/ADT/DenseMap.h"
#include "llvm/ExecutionEngine/JITLink/JITLink.h"
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/Object/ELFObjectFile.h"

#include "jeandle/jeandleJavaCall.hpp"
#include "jeandle/jeandleReadELF.hpp"
#include "jeandle/jeandleResourceObj.hpp"
#include  "jeandle/jeandleUtils.hpp"

#include "utilities/debug.hpp"
#include "asm/codeBuffer.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciMethod.hpp"
#include "code/exceptionHandlerTable.hpp"

class CallSiteInfo : public JeandleCompilationResourceObj {
 public:
  CallSiteInfo(uint32_t statepoint_id,
               JeandleJavaCall::Type type,
               address target,
               int bci) :
               _statepoint_id(statepoint_id),
               _type(type),
               _target(target),
               _bci(bci),
               _inst_offset(0) {}

  JeandleJavaCall::Type type() const { return _type; }
  uint32_t statepoint_id() const { return _statepoint_id; }
  address target() const { return _target; }
  int bci() const { return _bci; }

  uint32_t inst_offset() const { return _inst_offset; }
  void set_inst_offset(uint32_t offset) { _inst_offset = offset; }

 private:
  uint32_t _statepoint_id; // Used to distinguish each call site in stackmaps.
  JeandleJavaCall::Type _type;
  address _target;
  int _bci;
  uint32_t _inst_offset; // Instruction offset (from the start of the containing function).
};

using ObjectBuffer = llvm::MemoryBuffer;
using LinkBlock   = llvm::jitlink::Block;
using LinkEdge    = llvm::jitlink::Edge;

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
                      _stub_entry(nullptr),
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
                      _stub_entry(nullptr),
                      _func_name(func_name) {}

  void install_obj(std::unique_ptr<ObjectBuffer> obj);

  llvm::DenseMap<uint32_t, CallSiteInfo*>& call_sites() { return _call_sites; }

  llvm::StringMap<jobject>& oop_handles() { return _oop_handles; }

  const char* object_start() const { return _obj->getBufferStart(); }
  size_t object_size() const { return _obj->getBufferSize(); }

  CodeBuffer* code_buffer() { return &_code_buffer; }

  CodeOffsets* offsets() { return &_offsets; }

  ExceptionHandlerTable* exception_handler_table() { return &_exception_handler_table; }

  ImplicitExceptionTable* implicit_exception_table() { return &_implicit_exception_table; }

  int frame_size() const { return _frame_size; }

  address stub_entry() const { return _stub_entry; }
  void set_stub_entry(address entry) { _stub_entry = entry; }

  // Generate relocations, stubs and debug information.
  void finalize();

 private:
  std::unique_ptr<ObjectBuffer> _obj; // Compiled instructions.
  std::unique_ptr<ELFObject> _elf;
  CodeBuffer _code_buffer; // Relocations and stubs.
  llvm::DenseMap<uint32_t, CallSiteInfo*> _call_sites;
  llvm::DenseMap<uint32_t, CallSiteInfo*> _safepoints;
  llvm::StringMap<address> _const_sections;
  llvm::StringMap<jobject> _oop_handles;
  CodeOffsets _offsets;
  ExceptionHandlerTable _exception_handler_table;
  ImplicitExceptionTable _implicit_exception_table;
  int _frame_size;
  int _prolog_length;
  ciEnv* _env;
  ciMethod* _method;
  address _stub_entry;
  std::string _func_name;

  void setup_frame_size();

  void resolve_reloc_info(JeandleAssembler& assmebler);

  // Lookup address of const section in CodeBuffer.
  address lookup_const_section(llvm::StringRef name, JeandleAssembler& assmebler);
  address resolve_const_edge(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assmebler);
};

#endif // SHARE_JEANDLE_COMPILED_CODE_HPP
