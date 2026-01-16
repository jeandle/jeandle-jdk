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

#ifndef SHARE_JEANDLE_COMPILED_CODE_HPP
#define SHARE_JEANDLE_COMPILED_CODE_HPP

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/ADT/DenseMap.h"
#include "llvm/ExecutionEngine/JITLink/JITLink.h"
#include "llvm/IR/Statepoint.h"
#include "llvm/Object/ELFObjectFile.h"
#include "llvm/Object/StackMapParser.h"
#include "llvm/Support/DynamicLibrary.h"
#include "llvm/Support/MemoryBuffer.h"

#include "jeandle/jeandleExceptionHandlerTable.hpp"
#include "jeandle/jeandleCompiledCall.hpp"
#include "jeandle/jeandleReadELF.hpp"
#include "jeandle/jeandleResourceObj.hpp"
#include "jeandle/jeandleUtils.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "asm/codeBuffer.hpp"
#include "ci/ciEnv.hpp"
#include "ci/ciField.hpp"
#include "ci/ciMethod.hpp"
#include "code/exceptionHandlerTable.hpp"
#include "runtime/sharedRuntime.hpp"


class DeoptValueEncoding {
  friend class JeandleCompiledCode;
public:
  enum DeoptValueType {
    LocalType = 0,
    StackType = 1,
    ArgumentType = 2,
    MonitorType = 3,
    ScalarValueType = 4,
    LastType = ScalarValueType + 1
  };
  DeoptValueEncoding(int index, DeoptValueType value_type, BasicType basic_type):
    _index(index), _value_type(value_type), _basic_type(basic_type) {
    assert(_value_type == LocalType || _value_type == StackType || _value_type == MonitorType, "Unsupported value type");
  }

  uint64_t encode() {
    // encode format
    // |--- index ---|--- value_type ---|--- basic_type ---|
    // |0          31|32              47|48              63|
    return ((uint64_t)_index << 32) | ((uint64_t)(_value_type << 16)) | (uint64_t)(_basic_type);
  }

  static DeoptValueEncoding decode(uint64_t encode) {
    int index = (int)(encode >> 32);
    assert(index >= 0, "must be");
    int val_type = (int)((encode & 0xffff0000UL) >> 16);
    assert(val_type >= 0 && val_type < DeoptValueType::LastType, "must be");
    int basic_type = (int)((encode & 0xffffUL));
    assert(basic_type >= 0 && basic_type <= BasicType::T_ILLEGAL, "must be");
    return {index, (DeoptValueType)(val_type), (BasicType)(basic_type)};
  }

#ifdef ASSERT
  const char* value_type_name(DeoptValueType t) {
    switch (t) {
      case LocalType: return "LocalType";
      case StackType: return "StackType";
      case ArgumentType: return "ArgumentType";
      case MonitorType: return "MonitorType";
      case ScalarValueType: return "ScalarValueType";
      default: return "Unknown";
    }
  }
  void print() {
    ttyLocker ttyl;
    tty->print_cr("DeoptValueEncoding: index: %d value_type: %s, basic_type: %s",
                  _index, value_type_name(_value_type), type2name(_basic_type));
  }
#endif
private:
  int _index;
  DeoptValueType _value_type;
  BasicType _basic_type;
};

class CallSiteInfo : public JeandleCompilationResourceObj {
 public:
  CallSiteInfo(JeandleCompiledCall::Type type,
               address target,
               int bci,
               bool has_deopt_operands = false,
               uint64_t statepoint_id = llvm::StatepointDirectives::DefaultStatepointID) :
               _type(type),
               _target(target),
               _bci(bci),
               _has_deopt_operands(has_deopt_operands),
               _statepoint_id(statepoint_id) {
#ifdef ASSERT
    // We don't need to assign a unique statepoint id for each routine call site, only call type and target is used.
    bool use_default_statepoint_id = (statepoint_id == llvm::StatepointDirectives::DefaultStatepointID);
    bool is_routine_call = (type == JeandleCompiledCall::ROUTINE_CALL);
    bool is_external_call = (type == JeandleCompiledCall::EXTERNAL_CALL);
    assert(use_default_statepoint_id == (is_routine_call || is_external_call), "routine calls and external calls should use the default statepoint id");
#endif // ASSERT
  }


  int bci() const { return _bci; }
  void set_bci(int bci) { _bci = bci; }

  JeandleCompiledCall::Type type() const { return _type; }
  uint64_t statepoint_id() const { return _statepoint_id; }
  address target() const { return _target; }
  bool has_deopt_operands() const { return _has_deopt_operands; }

 private:
  JeandleCompiledCall::Type _type;
  address _target;
  int _bci;
  bool _has_deopt_operands;

  // Used to distinguish each call site in stackmaps.
  uint64_t _statepoint_id;
};

class JeandleOopMap {
public:
  JeandleOopMap(OopMap* oop_map, GrowableArray<ScopeValue*>* locals, GrowableArray<ScopeValue*>* stack, GrowableArray<MonitorValue*>* monitors, bool reexecute) :
      _oop_map(oop_map), _locals(locals), _stack(stack), _monitors(monitors), _reexecute(reexecute) {
  }

  OopMap* oop_map() const { return _oop_map; }
  GrowableArray<ScopeValue*>* locals() const { return _locals; }
  GrowableArray<ScopeValue*>* stack() const { return _stack; }
  GrowableArray<MonitorValue*>* monitors() const { return _monitors; }
  bool reexecute() const { return _reexecute; }

private:
  OopMap* _oop_map;
  GrowableArray<ScopeValue*>* _locals;
  GrowableArray<ScopeValue*>* _stack;
  GrowableArray<MonitorValue*>* _monitors;
  bool _reexecute;
};

using ObjectBuffer   = llvm::MemoryBuffer;
using LinkBlock      = llvm::jitlink::Block;
using LinkEdge       = llvm::jitlink::Edge;
using LinkKind       = llvm::jitlink::Edge::Kind;
using LinkSymbol     = llvm::jitlink::Symbol;
using StackMapParser = llvm::StackMapParser<ELFT::Endianness>;
using DynamicLibrary = llvm::sys::DynamicLibrary;

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
                      _func_name(JeandleFuncSig::method_name_with_signature(_method)) {}

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

  bool needs_clinit_barrier(ciField* ik,         ciMethod* accessing_method);
  bool needs_clinit_barrier(ciMethod* ik,        ciMethod* accessing_method);
  bool needs_clinit_barrier(ciInstanceKlass* ik, ciMethod* accessing_method);
  bool needs_clinit_barrier_on_entry();

 private:
  std::unique_ptr<ObjectBuffer> _obj; // Compiled instructions.
  std::unique_ptr<ELFObject> _elf;
  CodeBuffer _code_buffer; // Relocations and stubs.

  // Call sites in our compiled code:
  // Note that the main difference between routine calls and non-routine calls is that routine calls are found
  // from relocation of compiled objects directly, and non-routine calls are found from stackmaps and then
  // matched with the compile-time generated statepoint id.
  llvm::DenseMap<int, CallSiteInfo*> _routine_call_sites; // Contains all routine call sites, constructed from
                                                          // relocations of compiled objects in resolve_reloc_info.
  llvm::SmallVector<CallSiteInfo*> _non_routine_call_sites; // Contains all other call sites,
                                                            // constructed during LLVM IR generation.

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

  void resolve_reloc_info(JeandleAssembler& assembler);

  // Lookup address of const section in CodeBuffer.
  address lookup_const_section(llvm::StringRef name, JeandleAssembler& assembler);
  address resolve_const_edge(LinkBlock& block, LinkEdge& edge, JeandleAssembler& assembler);

  JeandleOopMap* build_oop_map(StackMapParser& stackmaps, StackMapParser::record_iterator& record, CallSiteInfo* call_info);
  LocationValue* new_loc_value(const StackMapParser::LocationAccessor& location, Location::Type type);
  void fill_one_scope_value(const StackMapParser& stackmaps, const DeoptValueEncoding& encode, const StackMapParser::LocationAccessor& location,
                            GrowableArray<ScopeValue*>* array, int& current_index);
  void fill_one_monitor_value(const StackMapParser& stackmaps, const DeoptValueEncoding& encode, const StackMapParser::LocationAccessor& object,
                              const StackMapParser::LocationAccessor& lock, GrowableArray<MonitorValue*>* array);

  void build_exception_handler_table();
  void build_implicit_exception_table();

  int frame_size_in_slots();
};


class StackMapUtil : public AllStatic {
public:
  static bool is_constant(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Constant
        || location.getKind() == StackMapParser::LocationKind::ConstantIndex;
  }

  static bool is_stack(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Indirect
        || location.getKind() == StackMapParser::LocationKind::Direct;
  }

  static bool is_register(const StackMapParser::LocationAccessor& location) {
    return location.getKind() == StackMapParser::LocationKind::Register;
  }

  static int32_t stack_offset(const StackMapParser::LocationAccessor& location) {
    if (is_stack(location)) {
      return location.getOffset();
    } else {
      ShouldNotReachHere();
    }
  }

  static uint32_t getConstantUint(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static uint64_t getConstantUlong(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static float    getConstantFloat(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
  static double   getConstantDouble(const StackMapParser& parser, const StackMapParser::LocationAccessor& location);
};

#endif // SHARE_JEANDLE_COMPILED_CODE_HPP
