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

#include "jeandle/__llvmHeadersBegin__.hpp"
#include "llvm/IR/BasicBlock.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/InlineAsm.h"
#include "llvm/IR/Intrinsics.h"
#if defined(RISCV64)
#include "llvm/IR/IntrinsicsRISCV.h"
#endif

#include "jeandle/templatemodule/jeandleRuntimeDefinedJavaOps.hpp"
#include "jeandle/jeandleRuntimeRoutine.hpp"
#include "jeandle/jeandleRegister.hpp"
#include "jeandle/jeandleUtils.hpp"
#include "jeandle/jeandleCompiledCall.hpp"

#include "jeandle/__hotspotHeadersBegin__.hpp"
#include "ci/ciUtilities.hpp"
#include "classfile/symbolTable.hpp"
#include "classfile/systemDictionary.hpp"
#include "classfile/vmSymbols.hpp"
#include "gc/g1/g1BarrierSetRuntime.hpp"
#include "gc/g1/g1CardTable.hpp"
#include "gc/g1/g1ThreadLocalData.hpp"
#include "gc/g1/heapRegion.hpp"
#include "gc/shared/cardTable.hpp"
#include "gc/shared/gc_globals.hpp"
#include "gc/shared/tlab_globals.hpp"
#include "oops/arrayOop.hpp"
#include "oops/array.hpp"
#include "oops/compressedOops.inline.hpp"
#include "oops/instanceKlass.hpp"
#include "oops/instanceMirrorKlass.hpp"
#include "classfile/javaClasses.hpp"
#include "oops/klass.hpp"
#include "oops/markWord.hpp"
#include "oops/objArrayKlass.hpp"
#include "oops/oop.hpp"
#include "runtime/javaThread.hpp"
#include "runtime/deoptimization.hpp"
#include "runtime/fieldDescriptor.hpp"
#include "runtime/fieldDescriptor.inline.hpp"
#include "runtime/objectMonitor.hpp"
#include "runtime/safepointMechanism.hpp"
#include "runtime/sharedRuntime.hpp"

#include <atomic>

// All JavaOps are nounwind and gc-leaf-function by default. These attributes
// are a paired Jeandle leaf contract: add them together, or remove them
// together.
//
// The pairing matters for two reasons:
//   1. Keep JavaOps nounwind whenever possible. Inlining an invoke rewrites
//      calls inside the inlinee into invokes; if a JavaOp can unwind, every such
//      invoke carries extra EH control flow that later lowering must remove
//      explicitly.
//   2. Any gc-leaf-function must also be nounwind. RS4GC skips gc-leaf calls, so
//      an invoke to a gc-leaf function would keep its landingpad type unchanged
//      while other landingpads are rewritten to token, producing mixed
//      landingpad types in one module.
//
// JavaOps have no supported use case for splitting these attributes, so if a
// JavaOp may trigger GC safepoints or throw asynchronous exceptions, it must
// remove both attributes.
//                  name, lower_phase, return_type, arg_types
#define DEF_JAVA_OP(name, lower_phase, return_type, ...)                                        \
  void define_##name(llvm::Module& template_module) {                                           \
    if (RuntimeDefinedJavaOps::failed()) { return; }                                            \
    llvm::LLVMContext& context = template_module.getContext();                                  \
    llvm::FunctionType* func_type = llvm::FunctionType::get(return_type, {__VA_ARGS__}, false); \
    llvm::StringRef func_name = "jeandle."#name;                                                \
    llvm::Function* func = llvm::cast<llvm::Function>(                                          \
        template_module.getOrInsertFunction(func_name, func_type).getCallee());                 \
    func->setLinkage(llvm::Function::PrivateLinkage);                                           \
    func->addFnAttr("lower-phase", #lower_phase);                                               \
    func->addFnAttr(llvm::Attribute::NoInline);                                                 \
    func->addFnAttr(llvm::Attribute::NoUnwind);                                                 \
    func->addFnAttr("gc-leaf-function");                                                        \
    func->setCallingConv(llvm::CallingConv::Hotspot_JIT);                                       \
    llvm::BasicBlock* entry_block = llvm::BasicBlock::Create(context, "entry", func);           \
    llvm::IRBuilder<> ir_builder(entry_block);

#define JAVA_OP_END }

namespace {

// We cannot obtain contexts such as BCI in DEF_JAVA_OP.
// But we can pass the external deopt bundle into this empty one via inlining.
llvm::OperandBundleDef create_empty_deopt_bundle() {
  return llvm::OperandBundleDef("deopt", llvm::SmallVector<llvm::Value*>{});
}

DEF_JAVA_OP(current_thread, 0, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))
  llvm::NamedMDNode* thread_register = template_module.getNamedMetadata(llvm::jeandle::Metadata::CurrentThread);
  assert(thread_register != nullptr, "current_thread metadata must exist");
  llvm::Value* read_register_args[] = {llvm::MetadataAsValue::get(context, thread_register->getOperand(0))};
  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());
  llvm::CallInst* current_thread_value = ir_builder.CreateIntrinsic(llvm::Intrinsic::read_register,
                                                                    intptr_type,
                                                                    read_register_args);
  llvm::Value* current_thread_ptr = ir_builder.CreateIntToPtr(current_thread_value,
                                                              llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace));
  ir_builder.CreateRet(current_thread_ptr);
JAVA_OP_END

// Wrapper unboxing JavaOp. The frontend passes the LLVM-side JBasicType wire
// id and a non-null wrapper oop. Keeping the offset selection in this
// runtime-defined body makes the JavaOp layout-sensitive while keeping the
// semantic call visible until PEA has handled a virtual wrapper.
DEF_JAVA_OP(unbox, 2, llvm::Type::getInt64Ty(context),
            llvm::Type::getInt32Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* kind = func->getArg(0);
  llvm::Value* box = func->getArg(1);

  llvm::BasicBlock* default_block = llvm::BasicBlock::Create(context, "unbox.invalid", func);
  llvm::BasicBlock* done_block = llvm::BasicBlock::Create(context, "unbox.done", func);
  llvm::PHINode* result = llvm::PHINode::Create(llvm::Type::getInt64Ty(context), 8,
                                                 "unbox.result", done_block);
  llvm::SwitchInst* dispatch = ir_builder.CreateSwitch(kind, default_block, 8);

  auto emit_case = [&](int case_id, BasicType type, int offset,
                       bool sign_extend, bool floating) {
    llvm::BasicBlock* block = llvm::BasicBlock::Create(
        context, "unbox.case", func);
    dispatch->addCase(llvm::ConstantInt::get(llvm::Type::getInt32Ty(context), case_id),
                      block);
    llvm::IRBuilder<> case_builder(block);
    llvm::Type* memory_type = nullptr;
    switch (type) {
      case T_BOOLEAN:
      case T_BYTE:   memory_type = case_builder.getInt8Ty(); break;
      case T_CHAR:
      case T_SHORT:  memory_type = case_builder.getInt16Ty(); break;
      case T_INT:    memory_type = case_builder.getInt32Ty(); break;
      case T_LONG:   memory_type = case_builder.getInt64Ty(); break;
      case T_FLOAT:  memory_type = case_builder.getFloatTy(); break;
      case T_DOUBLE: memory_type = case_builder.getDoubleTy(); break;
      default:       ShouldNotReachHere();
    }
    llvm::Value* address = case_builder.CreateInBoundsGEP(
        case_builder.getInt8Ty(), box,
        case_builder.getInt32(offset), "unbox.value.address");
    llvm::Value* loaded = case_builder.CreateLoad(memory_type, address,
                                                  "unbox.value");
    llvm::Value* encoded = nullptr;
    if (floating) {
      llvm::Type* bits_type = type == T_FLOAT
          ? case_builder.getInt32Ty() : case_builder.getInt64Ty();
      llvm::Value* bits = case_builder.CreateBitCast(loaded, bits_type,
                                                     "unbox.bits");
      encoded = type == T_FLOAT
          ? case_builder.CreateZExt(bits, case_builder.getInt64Ty(),
                                    "unbox.float.bits64")
          : bits;
    } else if (type == T_LONG) {
      encoded = loaded;
    } else if (sign_extend) {
      encoded = case_builder.CreateSExt(loaded, case_builder.getInt64Ty(),
                                        "unbox.sext");
    } else {
      encoded = case_builder.CreateZExt(loaded, case_builder.getInt64Ty(),
                                        "unbox.zext");
    }
    result->addIncoming(encoded, block);
    case_builder.CreateBr(done_block);
  };

  emit_case(0, T_BOOLEAN, java_lang_boxing_object::value_offset(T_BOOLEAN),
            false, false);
  emit_case(1, T_BYTE, java_lang_boxing_object::value_offset(T_BYTE),
            true, false);
  emit_case(2, T_CHAR, java_lang_boxing_object::value_offset(T_CHAR),
            false, false);
  emit_case(3, T_SHORT, java_lang_boxing_object::value_offset(T_SHORT),
            true, false);
  emit_case(4, T_INT, java_lang_boxing_object::value_offset(T_INT),
            true, false);
  emit_case(5, T_LONG, java_lang_boxing_object::value_offset(T_LONG),
            true, false);
  emit_case(6, T_FLOAT, java_lang_boxing_object::value_offset(T_FLOAT),
            false, true);
  emit_case(7, T_DOUBLE, java_lang_boxing_object::value_offset(T_DOUBLE),
            false, true);

  ir_builder.SetInsertPoint(default_block);
  ir_builder.CreateUnreachable();
  ir_builder.SetInsertPoint(done_block);
  ir_builder.CreateRet(result);
JAVA_OP_END

// Generic wrapper boxing JavaOp. The semantic operation stays intact until
// PEA. Escaping paths therefore retain the complete valueOf behavior:
// Boolean uses TRUE/FALSE, integral wrappers use frontend-resolved cache
// constants with a live-cache fallback, and Float/Double always allocate. The
// first three operands remain the stable semantic ABI consumed by PEA; the
// remaining operands only specialize post-PEA materialization.
DEF_JAVA_OP(box, 2,
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt32Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace),
            llvm::Type::getInt64Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt64Ty(context),
            llvm::Type::getInt64Ty(context),
            llvm::Type::getInt1Ty(context))
  // DEF_JAVA_OP defaults to a nounwind gc-leaf operation. Boxing is different:
  // its cache-miss path calls jeandle.new_instance and therefore must retain
  // the normal JavaOp call semantics while PEA is still allowed to see it.
  func->removeFnAttr(llvm::Attribute::NoUnwind);
  func->removeFnAttr("gc-leaf-function");
  func->addFnAttr("jeandle.not-guaranteed-safepoint");

  llvm::Type* int8_type = llvm::Type::getInt8Ty(context);
  llvm::Type* int16_type = llvm::Type::getInt16Ty(context);
  llvm::Type* int32_type = llvm::Type::getInt32Ty(context);
  llvm::Type* int64_type = llvm::Type::getInt64Ty(context);
  llvm::PointerType* java_ptr =
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::PointerType* c_heap_ptr =
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::PointerType* narrow_ptr =
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace);
  llvm::Function* mirror_fn = template_module.getFunction("jeandle.load_mirror_from_klass");
  llvm::Function* layout_fn = template_module.getFunction("jeandle.layout_helper");
  llvm::Function* new_fn = template_module.getFunction("jeandle.new_instance");
  if (mirror_fn == nullptr || layout_fn == nullptr || new_fn == nullptr) {
    RuntimeDefinedJavaOps::set_failed("boxing JavaOp dependencies are missing");
    return;
  }

  llvm::IRBuilder<> &builder = ir_builder;
  llvm::Value* kind = func->getArg(0);
  llvm::Value* wrapper_klass = func->getArg(1);
  llvm::Value* raw_value = func->getArg(2);
  llvm::Value* profiled_cache_or_true = func->getArg(3);
  llvm::Value* profiled_false = func->getArg(4);
  llvm::Value* profiled_cache_low = func->getArg(5);
  llvm::Value* profiled_cache_length = func->getArg(6);
  llvm::Value* has_profiled_cache = func->getArg(7);
  auto load_global_i32 = [&](llvm::IRBuilder<>& b, const char* name) {
    llvm::GlobalVariable* global = template_module.getGlobalVariable(name, true);
    assert(global != nullptr, "boxing global is missing");
    return b.CreateLoad(llvm::Type::getInt32Ty(context), global, name);
  };
  auto load_oop = [&](llvm::IRBuilder<>& b,
                      llvm::Value* address,
                      const char* name) -> llvm::Value* {
    if (UseCompressedOops) {
      llvm::LoadInst* narrow = b.CreateLoad(
          narrow_ptr, address, llvm::Twine(name) + ".narrow");
      return b.CreateAddrSpaceCast(
          narrow, java_ptr, llvm::Twine(name) + ".addrspacecast");
    }
    return b.CreateLoad(java_ptr, address, llvm::Twine(name) + ".wide");
  };
  auto load_static_ref = [&](llvm::IRBuilder<>& b,
                             const char* klass_name,
                             const char* offset_name) -> llvm::Value* {
    llvm::GlobalVariable* klass_global =
        template_module.getGlobalVariable(klass_name, true);
    assert(klass_global != nullptr, "boxing class global is missing");
    llvm::Value* klass_raw = b.CreateLoad(int64_type, klass_global);
    llvm::Value* klass = b.CreateIntToPtr(klass_raw, c_heap_ptr);
    llvm::CallInst* mirror = b.CreateCall(mirror_fn, {klass});
    mirror->setCallingConv(llvm::CallingConv::Hotspot_JIT);
    llvm::Value* offset = load_global_i32(b, offset_name);
    llvm::Value* address = b.CreateInBoundsGEP(
        int8_type, mirror, offset, "box.static.ref.address");
    return load_oop(b, address, "box.static.ref");
  };
  auto load_static_int = [&](llvm::IRBuilder<>& b,
                             const char* klass_name,
                             const char* offset_name,
                             const char* value_name) -> llvm::Value* {
    llvm::GlobalVariable* klass_global =
        template_module.getGlobalVariable(klass_name, true);
    assert(klass_global != nullptr, "boxing class global is missing");
    llvm::Value* klass_raw = b.CreateLoad(int64_type, klass_global);
    llvm::Value* klass = b.CreateIntToPtr(klass_raw, c_heap_ptr);
    llvm::CallInst* mirror = b.CreateCall(mirror_fn, {klass});
    mirror->setCallingConv(llvm::CallingConv::Hotspot_JIT);
    llvm::Value* offset = load_global_i32(b, offset_name);
    llvm::Value* address = b.CreateInBoundsGEP(
        int8_type, mirror, offset, llvm::Twine(value_name) + ".address");
    llvm::Value* value = b.CreateLoad(int32_type, address, value_name);
    return b.CreateSExt(value, int64_type, llvm::Twine(value_name) + ".i64");
  };
  auto load_payload = [&](llvm::IRBuilder<>& b,
                          llvm::Value* object,
                          BasicType payload_type) -> llvm::Value* {
    llvm::Value* address = b.CreateInBoundsGEP(
        int8_type, object,
        b.getInt32(java_lang_boxing_object::value_offset(payload_type)));
    llvm::Value* loaded = nullptr;
    switch (payload_type) {
      case T_BYTE:
        loaded = b.CreateLoad(int8_type, address);
        return b.CreateSExt(loaded, int64_type);
      case T_CHAR:
        loaded = b.CreateLoad(int16_type, address);
        return b.CreateZExt(loaded, int64_type);
      case T_SHORT:
        loaded = b.CreateLoad(int16_type, address);
        return b.CreateSExt(loaded, int64_type);
      case T_INT:
        loaded = b.CreateLoad(int32_type, address);
        return b.CreateSExt(loaded, int64_type);
      case T_LONG:
        return b.CreateLoad(int64_type, address);
      default:
        ShouldNotReachHere();
        return llvm::PoisonValue::get(int64_type);
    }
  };
  auto store_payload = [&](llvm::IRBuilder<>& b,
                           llvm::Value* object,
                           BasicType payload_type) {
    llvm::Value* address = b.CreateInBoundsGEP(
        int8_type, object,
        b.getInt32(java_lang_boxing_object::value_offset(payload_type)));
    switch (payload_type) {
      case T_BOOLEAN:
      case T_BYTE:
        b.CreateStore(b.CreateTrunc(raw_value, int8_type), address);
        break;
      case T_CHAR:
      case T_SHORT:
        b.CreateStore(b.CreateTrunc(raw_value, int16_type), address);
        break;
      case T_INT:
        b.CreateStore(b.CreateTrunc(raw_value, int32_type), address);
        break;
      case T_LONG:
        b.CreateStore(raw_value, address);
        break;
      case T_FLOAT: {
        llvm::Value* bits = b.CreateTrunc(raw_value, int32_type);
        b.CreateStore(b.CreateBitCast(bits, llvm::Type::getFloatTy(context)),
                      address);
        break;
      }
      case T_DOUBLE:
        b.CreateStore(b.CreateBitCast(raw_value, llvm::Type::getDoubleTy(context)),
                      address);
        break;
      default:
        ShouldNotReachHere();
    }
  };
  auto emit_allocation = [&](llvm::IRBuilder<>& b,
                             BasicType payload_type) -> llvm::Value* {
    llvm::CallInst* layout = b.CreateCall(layout_fn, {wrapper_klass});
    layout->setCallingConv(llvm::CallingConv::Hotspot_JIT);
    llvm::Value* size = b.CreateAnd(layout, b.getInt32(-2), "box.size");
    llvm::Value* slow = b.CreateICmpNE(
        b.CreateAnd(layout, b.getInt32(1)), b.getInt32(0), "box.slow");
    llvm::CallInst* object = b.CreateCall(
        new_fn, {wrapper_klass, size, slow},
        {create_empty_deopt_bundle()});
    object->setCallingConv(llvm::CallingConv::Hotspot_JIT);
    store_payload(b, object, payload_type);
    // The wrapper payload is part of object initialization. Publish it after
    // both fast and slow allocation paths instead of letting new_instance's
    // fast-path barrier run before this store.
    b.CreateFence(llvm::AtomicOrdering::Release);
    return object;
  };

  llvm::BasicBlock* invalid = llvm::BasicBlock::Create(context, "box.invalid", func);
  llvm::BasicBlock* done = llvm::BasicBlock::Create(context, "box.done", func);
  llvm::PHINode* result = llvm::PHINode::Create(java_ptr, 8, "box.result", done);
  llvm::SwitchInst* dispatch = builder.CreateSwitch(kind, invalid, 8);

  auto emit_uncached_case = [&](int case_id, BasicType payload_type) {
    llvm::BasicBlock* block =
        llvm::BasicBlock::Create(context, "box.uncached", func);
    dispatch->addCase(llvm::cast<llvm::ConstantInt>(
        llvm::ConstantInt::get(int32_type, case_id)), block);
    llvm::IRBuilder<> b(block);
    llvm::Value* object = emit_allocation(b, payload_type);
    b.CreateBr(done);
    result->addIncoming(object, block);
  };
  auto emit_boolean_case = [&]() {
    llvm::BasicBlock* block =
        llvm::BasicBlock::Create(context, "box.boolean", func);
    llvm::BasicBlock* constant_block =
        llvm::BasicBlock::Create(context, "box.boolean.constant", func);
    llvm::BasicBlock* dynamic_block =
        llvm::BasicBlock::Create(context, "box.boolean.dynamic", func);
    dispatch->addCase(llvm::cast<llvm::ConstantInt>(
        llvm::ConstantInt::get(int32_type, 0)), block);
    llvm::IRBuilder<> b(block);
    b.CreateCondBr(has_profiled_cache, constant_block, dynamic_block);

    llvm::IRBuilder<> constant_builder(constant_block);
    llvm::Value* constant_is_true = constant_builder.CreateICmpNE(
        raw_value, constant_builder.getInt64(0));
    llvm::Value* constant_object = constant_builder.CreateSelect(
        constant_is_true, profiled_cache_or_true, profiled_false);
    constant_builder.CreateBr(done);
    result->addIncoming(constant_object, constant_block);

    llvm::IRBuilder<> dynamic_builder(dynamic_block);
    llvm::Value* true_object = load_static_ref(
        dynamic_builder, "jeandle.box.Boolean.klass",
        "jeandle.box.Boolean.true_offset");
    llvm::Value* false_object = load_static_ref(
        dynamic_builder, "jeandle.box.Boolean.klass",
        "jeandle.box.Boolean.false_offset");
    llvm::Value* dynamic_is_true = dynamic_builder.CreateICmpNE(
        raw_value, dynamic_builder.getInt64(0));
    llvm::Value* dynamic_object = dynamic_builder.CreateSelect(
        dynamic_is_true, true_object, false_object);
    dynamic_builder.CreateBr(done);
    result->addIncoming(dynamic_object, dynamic_block);
  };
  auto emit_cached_case = [&](int case_id,
                              BasicType payload_type,
                              const char* klass_name,
                              const char* offset_name,
                              const char* low_offset_name,
                              const char* high_offset_name) {
    llvm::BasicBlock* block =
        llvm::BasicBlock::Create(context, "box.cached", func);
    llvm::BasicBlock* constant_metadata =
        llvm::BasicBlock::Create(context, "box.cache.constant", func);
    llvm::BasicBlock* dynamic_metadata =
        llvm::BasicBlock::Create(context, "box.cache.dynamic", func);
    llvm::BasicBlock* check =
        llvm::BasicBlock::Create(context, "box.cache.check", func);
    llvm::BasicBlock* hit =
        llvm::BasicBlock::Create(context, "box.cache_hit", func);
    llvm::BasicBlock* miss =
        llvm::BasicBlock::Create(context, "box.cache_miss", func);
    dispatch->addCase(llvm::cast<llvm::ConstantInt>(
        llvm::ConstantInt::get(int32_type, case_id)), block);
    llvm::IRBuilder<> b(block);
    b.CreateCondBr(has_profiled_cache, constant_metadata, dynamic_metadata);

    llvm::IRBuilder<> constant_builder(constant_metadata);
    constant_builder.CreateBr(check);

    llvm::IRBuilder<> dynamic_builder(dynamic_metadata);
    llvm::Value* dynamic_cache =
        load_static_ref(dynamic_builder, klass_name, offset_name);
    llvm::Value* dynamic_low = nullptr;
    llvm::Value* dynamic_length = nullptr;
    if (low_offset_name != nullptr) {
      assert(high_offset_name != nullptr, "both Integer cache bounds required");
      dynamic_low = load_static_int(
          dynamic_builder, klass_name, low_offset_name, "box.cache.low");
      llvm::Value* dynamic_high = load_static_int(
          dynamic_builder, klass_name, high_offset_name, "box.cache.high");
      dynamic_length = dynamic_builder.CreateAdd(
          dynamic_builder.CreateSub(dynamic_high, dynamic_low),
          dynamic_builder.getInt64(1), "box.cache.length");
    } else {
      assert(high_offset_name == nullptr, "both Integer cache bounds required");
      llvm::Value* dynamic_base = dynamic_builder.CreateInBoundsGEP(
          int8_type, dynamic_cache,
          load_global_i32(dynamic_builder,
                          "arrayOopDesc.base_offset_in_bytes.object"));
      llvm::Value* first =
          load_oop(dynamic_builder, dynamic_base, "box.cache.first");
      dynamic_low = load_payload(dynamic_builder, first, payload_type);
      llvm::Value* length_offset =
          load_global_i32(dynamic_builder,
                          "arrayOopDesc.length_offset_in_bytes");
      llvm::Value* length_address = dynamic_builder.CreateInBoundsGEP(
          int8_type, dynamic_cache, length_offset,
          "box.cache.length.address");
      llvm::Value* length_value =
          dynamic_builder.CreateLoad(int32_type, length_address,
                                     "box.cache.length");
      dynamic_length = dynamic_builder.CreateZExt(length_value, int64_type);
    }
    dynamic_builder.CreateBr(check);

    llvm::IRBuilder<> check_builder(check);
    llvm::PHINode* cache = check_builder.CreatePHI(java_ptr, 2, "box.cache");
    cache->addIncoming(profiled_cache_or_true, constant_metadata);
    cache->addIncoming(dynamic_cache, dynamic_metadata);
    llvm::PHINode* low = check_builder.CreatePHI(
        int64_type, 2, "box.cache.low");
    low->addIncoming(profiled_cache_low, constant_metadata);
    low->addIncoming(dynamic_low, dynamic_metadata);
    llvm::PHINode* length = check_builder.CreatePHI(
        int64_type, 2, "box.cache.length");
    length->addIncoming(profiled_cache_length, constant_metadata);
    length->addIncoming(dynamic_length, dynamic_metadata);
    llvm::Value* base = check_builder.CreateInBoundsGEP(
        int8_type, cache,
        load_global_i32(check_builder,
                        "arrayOopDesc.base_offset_in_bytes.object"));
    llvm::Value* index = nullptr;
    llvm::Value* in_cache = nullptr;
    if (payload_type == T_BYTE || payload_type == T_CHAR ||
        payload_type == T_SHORT || payload_type == T_INT) {
      // Keep byte, char, short, and int cache indexes in their Java
      // computational type. The semantic ABI carries the payload in i64, but
      // widening these operations creates avoidable extensions on the
      // cache-hit hot path.
      llvm::Value* narrow_raw = check_builder.CreateTrunc(
          raw_value, int32_type, "box.cache.raw.i32");
      llvm::Value* narrow_low = check_builder.CreateTrunc(
          low, int32_type, "box.cache.low.i32");
      llvm::Value* narrow_length = check_builder.CreateTrunc(
          length, int32_type, "box.cache.length.i32");
      llvm::Value* narrow_index = check_builder.CreateSub(
          narrow_raw, narrow_low, "box.cache.index.i32");
      in_cache = check_builder.CreateICmpULT(
          narrow_index, narrow_length, "box.cache.in_range");
      index = check_builder.CreateZExt(
          narrow_index, int64_type, "box.cache.index");
    } else {
      index = check_builder.CreateSub(
          raw_value, low, "box.cache.index");
    }
    // One unsigned comparison covers both bounds: values below low wrap to a
    // value at least as large as the non-negative cache length.
    if (case_id == 1) {
      // Every canonical byte is cached. The frontend's raw-byte guard proves
      // that raw_value is the sign extension of an actual byte, so no range
      // branch or allocation path is needed.
      check_builder.CreateBr(hit);
    } else {
      if (in_cache == nullptr) {
        in_cache = check_builder.CreateICmpULT(
            index, length, "box.cache.in_range");
      }
      check_builder.CreateCondBr(in_cache, hit, miss);
    }

    llvm::IRBuilder<> hit_builder(hit);
    llvm::Value* element_size = hit_builder.CreateSExt(
        load_global_i32(hit_builder, "heapOopSize"), int64_type);
    llvm::Value* byte_offset = hit_builder.CreateMul(index, element_size);
    llvm::Value* element_address = hit_builder.CreateInBoundsGEP(
        int8_type, base, byte_offset);
    llvm::Value* cached_object =
        load_oop(hit_builder, element_address, "box.cache.element");
    hit_builder.CreateBr(done);
    result->addIncoming(cached_object, hit);

    if (case_id != 1) {
      llvm::IRBuilder<> miss_builder(miss);
      llvm::Value* allocated = emit_allocation(miss_builder, payload_type);
      miss_builder.CreateBr(done);
      result->addIncoming(allocated, miss);
    } else {
      new llvm::UnreachableInst(context, miss);
    }
  };

  emit_boolean_case();
  emit_cached_case(1, T_BYTE, "jeandle.box.ByteCache.klass",
                   "jeandle.box.ByteCache.cache_offset", nullptr, nullptr);
  emit_cached_case(2, T_CHAR, "jeandle.box.CharacterCache.klass",
                   "jeandle.box.CharacterCache.cache_offset", nullptr, nullptr);
  emit_cached_case(3, T_SHORT, "jeandle.box.ShortCache.klass",
                   "jeandle.box.ShortCache.cache_offset", nullptr, nullptr);
  emit_cached_case(4, T_INT, "jeandle.box.IntegerCache.klass",
                   "jeandle.box.IntegerCache.cache_offset",
                   "jeandle.box.IntegerCache.low_offset",
                   "jeandle.box.IntegerCache.high_offset");
  emit_cached_case(5, T_LONG, "jeandle.box.LongCache.klass",
                   "jeandle.box.LongCache.cache_offset", nullptr, nullptr);
  emit_uncached_case(6, T_FLOAT);
  emit_uncached_case(7, T_DOUBLE);

  builder.SetInsertPoint(invalid);
  builder.CreateUnreachable();
  builder.SetInsertPoint(done);
  builder.CreateRet(result);
JAVA_OP_END


DEF_JAVA_OP(membar_cpuorder, 1, llvm::Type::getVoidTy(context))
  // C2's MemBarCPUOrder only prevents compiler movement; it has no hardware
  // encoding on the supported platforms.
  llvm::FunctionType* barrier_type =
      llvm::FunctionType::get(ir_builder.getVoidTy(), false);
  llvm::InlineAsm* barrier = llvm::InlineAsm::get(
      barrier_type, "", "~{memory}", /*hasSideEffects=*/true);
  ir_builder.CreateCall(barrier_type, barrier);
  ir_builder.CreateRetVoid();
JAVA_OP_END

static void emit_allocation_prefetch_loop(llvm::IRBuilder<>& builder,
                                          llvm::Function* function,
                                          llvm::Value* start,
                                          llvm::Value* lines,
                                          int64_t first_offset,
                                          int64_t step_size,
                                          int32_t rw,
                                          int32_t locality,
                                          llvm::BasicBlock* return_block) {
  llvm::LLVMContext& context = function->getContext();
  llvm::BasicBlock* entry_block = builder.GetInsertBlock();
  llvm::BasicBlock* loop_block =
      llvm::BasicBlock::Create(context, "allocation_prefetch_loop", function);
  builder.CreateBr(loop_block);
  builder.SetInsertPoint(loop_block);

  llvm::PHINode* index = builder.CreatePHI(builder.getInt32Ty(), 2, "prefetch_index");
  index->addIncoming(builder.getInt32(0), entry_block);
  llvm::Value* index64 = builder.CreateZExt(index, builder.getInt64Ty());
  llvm::Value* step = builder.CreateMul(index64, builder.getInt64(step_size));
  llvm::Value* offset = builder.CreateAdd(step, builder.getInt64(first_offset));
  llvm::Value* address = builder.CreateInBoundsGEP(
      builder.getInt8Ty(), start, offset, "prefetch_address");
  builder.CreateIntrinsic(
      llvm::Intrinsic::prefetch, start->getType(),
      {address, builder.getInt32(rw), builder.getInt32(locality), builder.getInt32(1)});

  llvm::Value* next_index = builder.CreateAdd(index, builder.getInt32(1));
  llvm::Value* done = builder.CreateICmpUGE(next_index, lines);
  llvm::BasicBlock* next_block =
      llvm::BasicBlock::Create(context, "allocation_prefetch_next", function);
  builder.CreateCondBr(done, return_block, next_block);
  builder.SetInsertPoint(next_block);
  builder.CreateBr(loop_block);
  index->addIncoming(next_index, next_block);
}

DEF_JAVA_OP(allocation_prefetch, 1, llvm::Type::getVoidTy(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt32Ty(context))
  llvm::Value* old_top = func->getArg(0);
  llvm::Value* new_top = func->getArg(1);
  llvm::Value* lines = func->getArg(2);
  llvm::BasicBlock* return_block = llvm::BasicBlock::Create(context, "return", func);
  llvm::Value* has_lines = ir_builder.CreateICmpNE(lines, ir_builder.getInt32(0));

  // Keep all allocation policy in this reusable JavaOp. The allocation sites
  // only provide the old/new TLAB tops and the allocation-specific line count.
  if (AllocatePrefetchStyle <= 0 || AllocatePrefetchDistance == 0) {
    ir_builder.CreateBr(return_block);
  } else {
    int32_t rw = 1;
    int32_t locality = 3;
#if defined(AMD64)
    // LLVM's x86 patterns use rw=1 for PREFETCHW. Other HotSpot allocation
    // choices are ordinary read prefetches selected by locality.
    rw = AllocatePrefetchInstr == 3 ? 1 : 0;
    switch (AllocatePrefetchInstr) {
      case 0: locality = 0; break; // PREFETCHNTA
      case 1: locality = 3; break; // PREFETCHT0
      case 2: locality = 1; break; // PREFETCHT2
      default: locality = 3; break; // PREFETCHW
    }
#endif

    if (AllocatePrefetchStyle == 2) {
      llvm::GlobalVariable* pf_top_offset_global =
          template_module.getGlobalVariable("JavaThread.tlab_pf_top_offset", /*AllowInternal=*/true);
      llvm::Value* pf_top_offset = ir_builder.CreateLoad(
          ir_builder.getInt64Ty(), pf_top_offset_global);
      llvm::PointerType* tls_pointer_type =
          llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace);
      llvm::Value* pf_top_ptr = ir_builder.CreateIntToPtr(pf_top_offset, tls_pointer_type);
      llvm::Value* old_pf_top = ir_builder.CreateLoad(old_top->getType(), pf_top_ptr);
      llvm::Value* needs_prefetch = ir_builder.CreateICmpUGE(new_top, old_pf_top);
      llvm::BasicBlock* prefetch_block =
          llvm::BasicBlock::Create(context, "allocation_prefetch_watermark", func);
      llvm::BasicBlock* has_lines_block =
          llvm::BasicBlock::Create(context, "allocation_prefetch_has_lines", func);
      ir_builder.CreateCondBr(needs_prefetch, has_lines_block, return_block);
      ir_builder.SetInsertPoint(has_lines_block);
      ir_builder.CreateCondBr(has_lines, prefetch_block, return_block);
      ir_builder.SetInsertPoint(prefetch_block);
      llvm::Value* new_pf_top = ir_builder.CreateInBoundsGEP(
          ir_builder.getInt8Ty(), old_pf_top,
          ir_builder.getInt64(AllocatePrefetchDistance));
      ir_builder.CreateStore(new_pf_top, pf_top_ptr);
      emit_allocation_prefetch_loop(ir_builder, func, old_pf_top, lines,
                                    AllocatePrefetchDistance, AllocatePrefetchStepSize,
                                    rw, locality, return_block);
    } else if (AllocatePrefetchStyle == 3) {
      llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());
      llvm::Value* old_top_int = ir_builder.CreatePtrToInt(old_top, intptr_type);
      llvm::Value* first = ir_builder.CreateAdd(
          old_top_int, ir_builder.getInt64(AllocatePrefetchStepSize + AllocatePrefetchDistance));
      llvm::Value* mask = ir_builder.getInt64(
          ~(uint64_t)(AllocatePrefetchStepSize - 1));
      llvm::Value* aligned = ir_builder.CreateAnd(first, mask);
      llvm::Value* cache_address = ir_builder.CreateIntToPtr(aligned, old_top->getType());
      llvm::BasicBlock* prefetch_block =
          llvm::BasicBlock::Create(context, "allocation_prefetch_has_lines", func);
      ir_builder.CreateCondBr(has_lines, prefetch_block, return_block);
      ir_builder.SetInsertPoint(prefetch_block);
      emit_allocation_prefetch_loop(ir_builder, func, cache_address, lines, 0,
                                    AllocatePrefetchStepSize, rw, locality,
                                    return_block);
    } else {
      // Style 1 starts at new_top + distance and advances by step_size.
      llvm::BasicBlock* prefetch_block =
          llvm::BasicBlock::Create(context, "allocation_prefetch_has_lines", func);
      ir_builder.CreateCondBr(has_lines, prefetch_block, return_block);
      ir_builder.SetInsertPoint(prefetch_block);
      emit_allocation_prefetch_loop(ir_builder, func, new_top, lines,
                                    AllocatePrefetchDistance, AllocatePrefetchStepSize,
                                    rw, locality, return_block);
    }
  }

  ir_builder.SetInsertPoint(return_block);
  ir_builder.CreateRetVoid();
JAVA_OP_END

static void emit_variable_zero_heap_words(llvm::IRBuilder<>& builder,
                                          llvm::Function* function,
                                          llvm::Value* base,
                                          llvm::Value* word_count,
                                          llvm::BasicBlock* stub_block,
                                          llvm::BasicBlock* return_block) {
  llvm::LLVMContext& context = function->getContext();

#if defined(AARCH64)
  // AArch64 keeps the 0..7 HeapWord tail inline and delegates larger clears
  // to the block-zeroing stub, matching MacroAssembler::zero_words.
  llvm::BasicBlock* tail_4_block =
      llvm::BasicBlock::Create(context, "variable_tail_4", function);
  llvm::BasicBlock* tail_2_block =
      llvm::BasicBlock::Create(context, "variable_tail_2", function);
  llvm::BasicBlock* tail_1_block =
      llvm::BasicBlock::Create(context, "variable_tail_1", function);
  llvm::BasicBlock* tail_return_block =
      llvm::BasicBlock::Create(context, "variable_tail_return", function);

  llvm::Value* use_stub = builder.CreateICmpUGE(word_count, builder.getInt32(8));
  builder.CreateCondBr(use_stub, stub_block, tail_4_block);

  // Clear the 0..7 HeapWord tail by testing the 4/2/1 count bits. This is the
  // same compact shape used by C2 after its zero_blocks call.
  builder.SetInsertPoint(tail_4_block);
  llvm::Value* has_4_words = builder.CreateICmpNE(
      builder.CreateAnd(word_count, builder.getInt32(4)), builder.getInt32(0));
  llvm::BasicBlock* clear_4_words_block =
      llvm::BasicBlock::Create(context, "clear_4_words", function);
  builder.CreateCondBr(has_4_words, clear_4_words_block, tail_2_block);

  builder.SetInsertPoint(clear_4_words_block);
  llvm::Value* zero_pair = llvm::ConstantInt::get(builder.getInt128Ty(), 0);
  llvm::StoreInst* clear_first_pair = builder.CreateStore(zero_pair, base, true);
  clear_first_pair->setAlignment(llvm::Align(HeapWordSize));
  llvm::Value* second_pair_addr = builder.CreateInBoundsGEP(
      builder.getInt64Ty(), base, builder.getInt32(2));
  llvm::StoreInst* clear_second_pair =
      builder.CreateStore(zero_pair, second_pair_addr, true);
  clear_second_pair->setAlignment(llvm::Align(HeapWordSize));
  llvm::Value* base_after_4 = builder.CreateInBoundsGEP(
      builder.getInt64Ty(), base, builder.getInt32(4));
  builder.CreateBr(tail_2_block);

  builder.SetInsertPoint(tail_2_block);
  llvm::PHINode* tail_2_base = builder.CreatePHI(base->getType(), 2, "tail_2_base");
  tail_2_base->addIncoming(base, tail_4_block);
  tail_2_base->addIncoming(base_after_4, clear_4_words_block);
  llvm::Value* has_2_words = builder.CreateICmpNE(
      builder.CreateAnd(word_count, builder.getInt32(2)), builder.getInt32(0));
  llvm::BasicBlock* clear_2_words_block =
      llvm::BasicBlock::Create(context, "clear_2_words", function);
  builder.CreateCondBr(has_2_words, clear_2_words_block, tail_1_block);

  builder.SetInsertPoint(clear_2_words_block);
  llvm::StoreInst* clear_2_words = builder.CreateStore(
      llvm::ConstantInt::get(builder.getInt128Ty(), 0), tail_2_base, true);
  clear_2_words->setAlignment(llvm::Align(HeapWordSize));
  llvm::Value* base_after_2 = builder.CreateInBoundsGEP(
      builder.getInt64Ty(), tail_2_base, builder.getInt32(2));
  builder.CreateBr(tail_1_block);

  builder.SetInsertPoint(tail_1_block);
  llvm::PHINode* tail_1_base = builder.CreatePHI(base->getType(), 2, "tail_1_base");
  tail_1_base->addIncoming(tail_2_base, tail_2_block);
  tail_1_base->addIncoming(base_after_2, clear_2_words_block);
  llvm::Value* has_1_word = builder.CreateICmpNE(
      builder.CreateAnd(word_count, builder.getInt32(1)), builder.getInt32(0));
  builder.CreateCondBr(has_1_word, tail_return_block, return_block);

  builder.SetInsertPoint(tail_return_block);
  llvm::StoreInst* clear_1_word = builder.CreateStore(builder.getInt64(0), tail_1_base, true);
  clear_1_word->setAlignment(llvm::Align(HeapWordSize));
  builder.CreateBr(return_block);
#elif defined(RISCV64)
  if (UseRVV && !UseBlockZeroing) {
    // Match C2's clear_array_v with an e64,m4 strip-mined RVV loop.
    llvm::BasicBlock* variable_block = builder.GetInsertBlock();
    llvm::BasicBlock* loop_block =
        llvm::BasicBlock::Create(context, "rvv_loop", function);
    llvm::Value* initial_remaining = builder.CreateZExt(
        word_count, builder.getInt64Ty(), "rvv_remaining");
    llvm::CallInst* initial_vl = builder.CreateIntrinsic(
        llvm::Intrinsic::riscv_vsetvli, {builder.getInt64Ty()},
        {initial_remaining, builder.getInt64(3), builder.getInt64(2)});
    llvm::VectorType* vector_type = llvm::VectorType::get(
        builder.getInt64Ty(), llvm::ElementCount::getScalable(4));
    llvm::Value* zero_vector = builder.CreateIntrinsic(
        llvm::Intrinsic::riscv_vmv_v_x,
        {vector_type, builder.getInt64Ty()},
        {llvm::PoisonValue::get(vector_type), builder.getInt64(0), initial_vl},
        {}, "rvv_zero");
    builder.CreateBr(loop_block);

    builder.SetInsertPoint(loop_block);
    llvm::PHINode* current_base = builder.CreatePHI(base->getType(), 2, "rvv_base");
    llvm::PHINode* remaining = builder.CreatePHI(builder.getInt64Ty(), 2, "rvv_remaining");
    current_base->addIncoming(base, variable_block);
    remaining->addIncoming(initial_remaining, variable_block);

    llvm::CallInst* vl = builder.CreateIntrinsic(
        llvm::Intrinsic::riscv_vsetvli, {builder.getInt64Ty()},
        {remaining, builder.getInt64(3), builder.getInt64(2)}, {}, "rvv_vl");
    builder.CreateIntrinsic(
        llvm::Intrinsic::riscv_vse,
        {vector_type, current_base->getType(), builder.getInt64Ty()},
        {zero_vector, current_base, vl});

    llvm::Value* next_remaining = builder.CreateSub(remaining, vl, "rvv_next_remaining");
    llvm::Value* next_base = builder.CreateInBoundsGEP(
        builder.getInt64Ty(), current_base, vl, "rvv_next_base");
    llvm::Value* has_remaining = builder.CreateICmpNE(
        next_remaining, builder.getInt64(0));
    builder.CreateCondBr(has_remaining, loop_block, return_block);
    current_base->addIncoming(next_base, loop_block);
    remaining->addIncoming(next_remaining, loop_block);
  } else {
    builder.CreateBr(stub_block);
  }
#else
  // The x86 stub owns the complete variable-count strategy.
  builder.CreateBr(stub_block);
#endif
}

#if defined(AMD64)
static void emit_constant_zero_heap_words(llvm::IRBuilder<>& builder,
                                          llvm::Function* function,
                                          llvm::Value* base,
                                          llvm::Value* word_count,
                                          llvm::BasicBlock* return_block) {
  // i128 stores split into scalar stores on x86. Use explicit vectors, capped
  // at 32 bytes to avoid AVX-512 setup for short clears. HeapWord alignment is
  // sufficient for unaligned vector stores; never assume vector alignment.
  int max_words = 1;
  if (UseSSE >= 2 && MaxVectorSize >= 16) {
    max_words = UseAVX >= 2 && MaxVectorSize >= 32 ? 4 : 2;
  }

  for (int words = max_words; words != 0; words >>= 1) {
    llvm::BasicBlock* dispatch_block = builder.GetInsertBlock();
    llvm::BasicBlock* loop_block =
        llvm::BasicBlock::Create(function->getContext(), "constant_clear_loop", function);
    llvm::BasicBlock* next_block =
        llvm::BasicBlock::Create(function->getContext(), "constant_clear_next", function);
    llvm::Value* store_count = builder.CreateUDiv(word_count, builder.getInt32(words));
    if (words != max_words) {
      store_count = builder.CreateAnd(store_count, builder.getInt32(1));
    }
    llvm::Value* has_stores = builder.CreateICmpNE(store_count, builder.getInt32(0));
    builder.CreateCondBr(has_stores, loop_block, next_block);

    builder.SetInsertPoint(loop_block);
    llvm::PHINode* index = builder.CreatePHI(builder.getInt32Ty(), 2, "constant_clear_index");
    index->addIncoming(builder.getInt32(0), dispatch_block);
    llvm::Type* store_type = builder.getInt64Ty();
    if (words > 1) {
      store_type = llvm::FixedVectorType::get(builder.getInt64Ty(), words);
    }
    llvm::Value* address = builder.CreateInBoundsGEP(store_type, base, index);
    // Volatility prevents loop-idiom recognition from introducing a memset call.
    llvm::StoreInst* store = builder.CreateStore(llvm::Constant::getNullValue(store_type), address, true);
    store->setAlignment(llvm::Align(HeapWordSize));
    llvm::Value* next_index = builder.CreateAdd(index, builder.getInt32(1));
    llvm::Value* done = builder.CreateICmpEQ(next_index, store_count);
    builder.CreateCondBr(done, next_block, loop_block);
    index->addIncoming(next_index, loop_block);

    builder.SetInsertPoint(next_block);
    // Each narrower store handles one bit of the remainder, so no store can
    // extend beyond the payload, including for zero or odd word counts.
    base = builder.CreateInBoundsGEP(store_type, base, store_count);
  }
  builder.CreateBr(return_block);
}
#endif

DEF_JAVA_OP(zero_heap_words, 1, llvm::Type::getVoidTy(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt32Ty(context))
  llvm::Value* base = func->getArg(0);
  llvm::Value* word_count = func->getArg(1);
  func->addParamAttr(0, llvm::Attribute::getWithAlignment(context, llvm::Align(HeapWordSize)));

  llvm::BasicBlock* constant_dispatch_block = llvm::BasicBlock::Create(context, "constant_dispatch", func);
#if !defined(AMD64)
  llvm::BasicBlock* constant_odd_word_block = llvm::BasicBlock::Create(context, "constant_odd_word", func);
  llvm::BasicBlock* constant_pair_dispatch_block = llvm::BasicBlock::Create(context, "constant_pair_dispatch", func);
  llvm::BasicBlock* constant_pair_loop_block = llvm::BasicBlock::Create(context, "constant_pair_loop", func);
#endif
  llvm::BasicBlock* variable_block = llvm::BasicBlock::Create(context, "variable", func);
  llvm::BasicBlock* variable_stub_block = llvm::BasicBlock::Create(context, "variable_stub", func);
  llvm::BasicBlock* return_block = llvm::BasicBlock::Create(context, "return", func);

  // JavaOperationLower(1) inlines this JavaOp before llvm.is.constant is
  // lowered. Constant propagation can therefore expose a constant word count
  // at the allocation site. Keep only short constants inline, matching C2's
  // platform thresholds; larger constants use the platform zeroing stub.
  llvm::Value* is_constant = ir_builder.CreateIntrinsic(
      llvm::Intrinsic::is_constant, llvm::Type::getInt32Ty(context), {word_count});
  llvm::Value* inline_constant = is_constant;
#if defined(AARCH64)
  llvm::Value* below_block_zeroing_limit = ir_builder.CreateICmpULT(
      word_count, ir_builder.getInt32(BlockZeroingLowLimit / HeapWordSize));
  inline_constant = ir_builder.CreateAnd(is_constant, below_block_zeroing_limit);
#elif defined(RISCV64)
  if (UseRVV) {
    // C2 prefers its RVV ClearArray rule even when the count is constant.
    inline_constant = ir_builder.getFalse();
  } else {
    llvm::Value* below_block_zeroing_limit = ir_builder.CreateICmpULT(
        word_count, ir_builder.getInt32(BlockZeroingLowLimit / HeapWordSize));
    inline_constant = ir_builder.CreateAnd(is_constant, below_block_zeroing_limit);
  }
#elif defined(AMD64)
  llvm::Value* is_short_clear = ir_builder.CreateICmpULE(
      word_count, ir_builder.getInt32(InitArrayShortSize / HeapWordSize));
  inline_constant = ir_builder.CreateAnd(is_constant, is_short_clear);
#endif
  ir_builder.CreateCondBr(inline_constant, constant_dispatch_block, variable_block);

  ir_builder.SetInsertPoint(constant_dispatch_block);
#if defined(AMD64)
  emit_constant_zero_heap_words(ir_builder, func, base, word_count, return_block);
#else
  // Follow C2's constant-count shape: clear an odd leading HeapWord, then clear
  // pairs of HeapWords. A volatile i128 zero store lowers to one STP on AArch64,
  // while volatility prevents LoopIdiomRecognize from recreating memset. The
  // constant-trip pair loop can still be fully unrolled.
  llvm::Value* odd_word = ir_builder.CreateAnd(word_count, ir_builder.getInt32(1));
  llvm::Value* has_odd_word = ir_builder.CreateICmpNE(odd_word, ir_builder.getInt32(0));
  ir_builder.CreateCondBr(has_odd_word, constant_odd_word_block, constant_pair_dispatch_block);

  ir_builder.SetInsertPoint(constant_odd_word_block);
  llvm::StoreInst* odd_store = ir_builder.CreateStore(ir_builder.getInt64(0), base, true);
  odd_store->setAlignment(llvm::Align(8));
  llvm::Value* base_after_odd = ir_builder.CreateInBoundsGEP(ir_builder.getInt64Ty(), base, ir_builder.getInt32(1));
  ir_builder.CreateBr(constant_pair_dispatch_block);

  ir_builder.SetInsertPoint(constant_pair_dispatch_block);
  llvm::PHINode* pair_base = ir_builder.CreatePHI(base->getType(), 2, "pair_base");
  pair_base->addIncoming(base, constant_dispatch_block);
  pair_base->addIncoming(base_after_odd, constant_odd_word_block);
  llvm::Value* pair_count = ir_builder.CreateLShr(word_count, ir_builder.getInt32(1));
  llvm::Value* has_pairs = ir_builder.CreateICmpNE(pair_count, ir_builder.getInt32(0));
  ir_builder.CreateCondBr(has_pairs, constant_pair_loop_block, return_block);

  ir_builder.SetInsertPoint(constant_pair_loop_block);
  llvm::PHINode* pair_index = ir_builder.CreatePHI(ir_builder.getInt32Ty(), 2, "pair_index");
  pair_index->addIncoming(ir_builder.getInt32(0), constant_pair_dispatch_block);
  llvm::Value* pair_addr = ir_builder.CreateInBoundsGEP(ir_builder.getInt128Ty(), pair_base, pair_index);
  llvm::Value* zero_pair = llvm::ConstantInt::get(ir_builder.getInt128Ty(), 0);
  llvm::StoreInst* pair_store = ir_builder.CreateStore(zero_pair, pair_addr, true);
  pair_store->setAlignment(llvm::Align(8));
  llvm::Value* next_pair = ir_builder.CreateAdd(pair_index, ir_builder.getInt32(1), "next_pair");
  llvm::Value* constant_done = ir_builder.CreateICmpEQ(next_pair, pair_count);
  ir_builder.CreateCondBr(constant_done, return_block, constant_pair_loop_block);
  pair_index->addIncoming(next_pair, constant_pair_loop_block);
#endif

  ir_builder.SetInsertPoint(variable_block);
  emit_variable_zero_heap_words(
      ir_builder, func, base, word_count, variable_stub_block, return_block);

  ir_builder.SetInsertPoint(variable_stub_block);
  llvm::FunctionType* stub_type = llvm::FunctionType::get(
      llvm::Type::getVoidTy(context), {base->getType(), word_count->getType()}, false);
  llvm::FunctionCallee stub_callee =
      template_module.getOrInsertFunction("zero_heap_words_stub", stub_type);
  llvm::Function* stub = llvm::cast<llvm::Function>(stub_callee.getCallee());
  stub->setCallingConv(llvm::CallingConv::C);
  stub->addFnAttr(llvm::Attribute::NoUnwind);
  stub->addFnAttr("gc-leaf-function");
  llvm::CallInst* stub_call = ir_builder.CreateCall(stub_callee, {base, word_count});
  stub_call->setCallingConv(llvm::CallingConv::C);
  ir_builder.CreateBr(return_block);

  ir_builder.SetInsertPoint(return_block);
  ir_builder.CreateRetVoid();
JAVA_OP_END

// Clear one half HeapWord (4 bytes on supported 64-bit platforms). Keeping
// this as a phase-1 JavaOp lets allocation optimization passes identify small
// zero-filled gaps before lowering.
DEF_JAVA_OP(zero_half_heap_word, 1, llvm::Type::getVoidTy(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* address = func->getArg(0);
  func->addParamAttr(0, llvm::Attribute::getWithAlignment(context, llvm::Align(HeapWordSize / 2)));
  llvm::StoreInst* zero_store = ir_builder.CreateStore(ir_builder.getInt32(0), address);
  zero_store->setAlignment(llvm::Align(HeapWordSize / 2));
  ir_builder.CreateRetVoid();
JAVA_OP_END

DEF_JAVA_OP(safepoint_poll, 1, llvm::Type::getVoidTy(context))
  // safepoint_poll may trigger GC and throw asynchronous exceptions,
  // so it must not be nounwind or gc-leaf-function.
  func->removeFnAttr(llvm::Attribute::NoUnwind);
  func->removeFnAttr("gc-leaf-function");
  llvm::BasicBlock* return_block = llvm::BasicBlock::Create(context, "return", func);
  llvm::BasicBlock* do_safepoint_block = llvm::BasicBlock::Create(context, "do_safepoint", func);

  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());

  // ******** Entry Block *********
  // Get the poll word pointer.
  llvm::Value* poll_word_ptr = ir_builder.CreateIntToPtr(ir_builder.getInt64((uint64_t)JavaThread::polling_word_offset()),
                                                         llvm::PointerType::get(context, llvm::jeandle::AddrSpace::TLSAddrSpace));
  // Do poll.
  llvm::Value* poll_word = ir_builder.CreateLoad(intptr_type, poll_word_ptr, true /* is_volatile */);
  llvm::Value* masked = ir_builder.CreateAnd(poll_word, llvm::ConstantInt::get(intptr_type, SafepointMechanism::poll_bit()));
  llvm::Value* need_safepoint = ir_builder.CreateICmpNE(masked, llvm::ConstantInt::get(intptr_type, 0));
  ir_builder.CreateCondBr(need_safepoint, do_safepoint_block, return_block);

  // ***** Do Safepoint Block *****
  ir_builder.SetInsertPoint(do_safepoint_block);
  // Get the current thread pointer as the safepoint handler's argument.
  llvm::Function* current_thread_func = template_module.getFunction("jeandle.current_thread");
  if (!current_thread_func) {
    RuntimeDefinedJavaOps::set_failed("jeandle.current_thread is not found in template module");
    return;
  }
  llvm::CallInst* current_thread = ir_builder.CreateCall(current_thread_func);
  current_thread->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  // Call safepoint handler.
  llvm::CallInst* call_inst = ir_builder.CreateCall(JeandleRuntimeRoutine::safepoint_handler_callee(template_module), {current_thread},
                                                    {create_empty_deopt_bundle()});
  call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  ir_builder.CreateBr(return_block);

  // ******** Return Block ********
  ir_builder.SetInsertPoint(return_block);
  ir_builder.CreateRetVoid();
JAVA_OP_END

// Phase 9 barrier JavaOps are lowered after RS4GC. They materialize raw
// addresses derived from oops, such as card-table addresses, which RS4GC does
// not track. Keeping them opaque until phase 9 prevents O3 from reusing those
// raw derived addresses across safepoints.
DEF_JAVA_OP(card_table_barrier, 9, llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* obj_addr = func->getArg(0);
  llvm::Type* intptr_type = ir_builder.getIntPtrTy(template_module.getDataLayout());
  llvm::Value* obj_ptr = ir_builder.CreatePtrToInt(obj_addr, intptr_type);

  // Find the card table address.
  llvm::Value* card_table_offset = ir_builder.CreateLShr(obj_ptr, llvm::ConstantInt::get(intptr_type, (uint64_t)CardTable::card_shift()));

  llvm::Value* card_table_base_addr = ir_builder.CreateIntToPtr(llvm::ConstantInt::get(intptr_type, (uint64_t)ci_card_table_address()),
                                                                llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace));

  llvm::Value* card_table_addr = ir_builder.CreateInBoundsGEP(llvm::Type::getInt8Ty(context), card_table_base_addr, card_table_offset);

  // Store dirty value to card table.
  CardTable::CardValue dirty_value = CardTable::dirty_card_val();
  if (UseCondCardMark) {
    llvm::BasicBlock* already_dirty_block = llvm::BasicBlock::Create(context, "already_dirty", func);
    llvm::BasicBlock* store_dirty_block = llvm::BasicBlock::Create(context, "store_dirty", func);
    llvm::Value* card_value = ir_builder.CreateLoad(ir_builder.getInt8Ty(), card_table_addr);

    // Card is already dirty, skip storing dirty value.
    llvm::Value* if_already_dirty = ir_builder.CreateICmp(llvm::CmpInst::ICMP_EQ,
                                                          card_value,
                                                          llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value));
    ir_builder.CreateCondBr(if_already_dirty, already_dirty_block, store_dirty_block);

    // Card is not dirty, store dirty value.
    ir_builder.SetInsertPoint(store_dirty_block);
    llvm::StoreInst* store_inst = ir_builder.CreateStore(llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value), card_table_addr);
    store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
    ir_builder.CreateBr(already_dirty_block);

    // Card is dirty, return.
    ir_builder.SetInsertPoint(already_dirty_block);
  } else {
    llvm::StoreInst* store_inst = ir_builder.CreateStore(llvm::ConstantInt::get(ir_builder.getInt8Ty(), (uint64_t)dirty_value), card_table_addr);
    store_inst->setAtomic(llvm::AtomicOrdering::Unordered);
  }

  ir_builder.CreateRetVoid();
JAVA_OP_END

DEF_JAVA_OP(pre_barrier, 9, llvm::Type::getVoidTy(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  // Only serial/G1 GC is supported on jeandle for now
  if (UseG1GC) {
    // TODO: implement ReduceInitialCardMarks
    llvm::Function* g1_pre_barrier_func = template_module.getFunction("jeandle.g1_pre_barrier");
    assert(g1_pre_barrier_func != nullptr, "g1_pre_barrier function not found");
    llvm::CallInst* call_inst = ir_builder.CreateCall(g1_pre_barrier_func, {func->getArg(0)});
    call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  } else {
    assert(UseSerialGC, "only Serial and G1 GC are supported");
  }
  ir_builder.CreateRetVoid();
JAVA_OP_END

DEF_JAVA_OP(post_barrier, 9, llvm::Type::getVoidTy(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  // Only serial/G1 GC is supported on jeandle for now
  if (UseG1GC) {
    // TODO: implement ReduceInitialCardMarks
    llvm::Function* g1_post_barrier_func = template_module.getFunction("jeandle.g1_post_barrier");
    assert(g1_post_barrier_func != nullptr, "g1_post_barrier function not found");
    llvm::CallInst* call_inst = ir_builder.CreateCall(g1_post_barrier_func, {func->getArg(0), func->getArg(1)});
    call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  } else {
    assert(UseSerialGC, "only Serial and G1 GC are supported");
    llvm::Function* card_table_barrier_func = template_module.getFunction("jeandle.card_table_barrier");
    assert(card_table_barrier_func != nullptr, "card_table_barrier function not found");
    llvm::CallInst* call_inst = ir_builder.CreateCall(card_table_barrier_func, {func->getArg(0)});
    call_inst->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  }
  ir_builder.CreateRetVoid();
JAVA_OP_END

// Load the java.lang.Class mirror rooted in a non-null Klass::_java_mirror
// OopHandle. Boxing uses this to read initialized wrapper cache fields.
DEF_JAVA_OP(load_mirror_from_klass, 2,
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))
  llvm::Value* klass = func->getArg(0);
  llvm::GlobalVariable* mirror_offset_gv =
      template_module.getGlobalVariable("Klass.java_mirror_offset", /*AllowInternal=*/true);
  if (!mirror_offset_gv) {
    RuntimeDefinedJavaOps::set_failed("Klass.java_mirror_offset global not found in template module");
    return;
  }

  llvm::Value* mirror_offset =
      ir_builder.CreateLoad(ir_builder.getInt32Ty(), mirror_offset_gv);
  llvm::Value* oop_handle_addr =
      ir_builder.CreateInBoundsGEP(ir_builder.getInt8Ty(), klass, mirror_offset);
  llvm::Type* c_heap_ptr_ty =
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* oop_handle = ir_builder.CreateLoad(c_heap_ptr_ty, oop_handle_addr);
  llvm::Type* mirror_ty =
      llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Value* mirror = ir_builder.CreateLoad(mirror_ty, oop_handle);
  ir_builder.CreateRet(mirror);
JAVA_OP_END

// Object.getClass(): load the java.lang.Class mirror for an object.
// Two-level load via the OopHandle stored in Klass::_java_mirror:
//   1. Load klass from object header (jeandle.load_klass).
//   2. Load the OopHandle pointer from klass + java_mirror_offset  -> oop* in C heap.
//   3. Dereference the OopHandle to get the actual mirror oop in the Java heap.
// The mirror is always reachable (a GC root inside the Klass), so no null check is needed.
//
// Exact receiver types are folded by LLVM ConstantFieldFolding before this
// JavaOp is expanded. This body remains the dynamic fallback for receivers
// whose exact Klass is unavailable.
DEF_JAVA_OP(get_class, 1, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))  // obj (receiver)
  llvm::Value* obj = func->getArg(0);
  // Step 1: load klass pointer from object header
  llvm::Function* load_klass_func = template_module.getFunction("jeandle.load_klass");
  if (!load_klass_func) {
    RuntimeDefinedJavaOps::set_failed("jeandle.load_klass is not found in template module");
    return;
  }
  llvm::CallInst* klass = ir_builder.CreateCall(load_klass_func, {obj});
  klass->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  // Step 2: load OopHandle (oop*) stored at klass + java_mirror_offset
  llvm::GlobalVariable* mirror_offset_gv = template_module.getGlobalVariable("Klass.java_mirror_offset", /*AllowInternal=*/true);
  if (!mirror_offset_gv) {
    RuntimeDefinedJavaOps::set_failed("Klass.java_mirror_offset global not found in template module");
    return;
  }
  llvm::Value* mirror_offset = ir_builder.CreateLoad(ir_builder.getInt32Ty(), mirror_offset_gv);
  llvm::Value* oop_handle_addr = ir_builder.CreateInBoundsGEP(ir_builder.getInt8Ty(), klass, mirror_offset);
  llvm::Type* c_heap_ptr_ty = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* oop_handle = ir_builder.CreateLoad(c_heap_ptr_ty, oop_handle_addr);
  // Step 3: dereference OopHandle to get the actual mirror oop in the Java heap
  llvm::Type* mirror_ty = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Value* mirror = ir_builder.CreateLoad(mirror_ty, oop_handle);
  ir_builder.CreateRet(mirror);
JAVA_OP_END

// Thread.currentThread(): load the java.lang.Thread oop for the current thread.
// Two-level load via the OopHandle stored in JavaThread::_vthread, identical in
// structure to jeandle.get_class for Klass::_java_mirror:
//   1. Materialize the current JavaThread* (r15 / x28 / x23) via jeandle.current_thread.
//   2. Load the OopHandle pointer at JavaThread + vthread_offset  -> oop* in C heap.
//   3. Dereference the OopHandle to get the Thread oop in the Java heap.
// _vthread is the value returned by Thread.currentThread(): the mounted virtual
// thread, otherwise the carrier thread's _threadObj. Matches C2's
// inline_native_currentThread() -> generate_virtual_thread().
DEF_JAVA_OP(current_thread_obj, 1,
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  // Step 1: materialize the current JavaThread* via the existing JavaOp.
  llvm::Function* current_thread_func = template_module.getFunction("jeandle.current_thread");
  if (!current_thread_func) {
    RuntimeDefinedJavaOps::set_failed("jeandle.current_thread is not found in template module");
    return;
  }
  llvm::CallInst* jt = ir_builder.CreateCall(current_thread_func);
  jt->setCallingConv(llvm::CallingConv::Hotspot_JIT);

  // Step 2: load the OopHandle (oop*) stored at JavaThread + vthread_offset.
  llvm::Value* vthread_handle_addr = ir_builder.CreateInBoundsGEP(
      ir_builder.getInt8Ty(), jt,
      ir_builder.getInt32(in_bytes(JavaThread::vthread_offset())));
  llvm::Type* c_heap_ptr_ty = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace);
  llvm::Value* oop_handle = ir_builder.CreateLoad(c_heap_ptr_ty, vthread_handle_addr);

  // Step 3: dereference the OopHandle to get the java.lang.Thread oop in the Java heap.
  llvm::Type* thread_oop_ty = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Value* thread_oop = ir_builder.CreateLoad(thread_oop_ty, oop_handle);
  ir_builder.CreateRet(thread_oop);
JAVA_OP_END

// Reference.refersTo0 / PhantomReference.refersTo0:
// Load the referent field and compare with the given object, returning a boolean.
// No GC barrier is applied (AS_NO_KEEPALIVE semantics): refersTo0 should not keep
// the referent alive. Equivalent to C2's inline_reference_refersTo0() which uses
// the AS_NO_KEEPALIVE decorator to suppress the G1 SATB pre-barrier.
DEF_JAVA_OP(reference_refers_to, 1, llvm::Type::getInt32Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),  // reference (this)
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))  // obj
  llvm::Value* ref_obj    = func->getArg(0);
  llvm::Value* compare_to = func->getArg(1);
  llvm::GlobalVariable* offset_gv = template_module.getGlobalVariable("java_lang_ref_Reference.referent_offset", /*AllowInternal=*/true);
  if (!offset_gv) {
    RuntimeDefinedJavaOps::set_failed("java_lang_ref_Reference.referent_offset global not found in template module");
    return;
  }
  llvm::Value* offset = ir_builder.CreateLoad(ir_builder.getInt32Ty(), offset_gv);
  llvm::Value* referent_addr = ir_builder.CreateInBoundsGEP(ir_builder.getInt8Ty(), ref_obj, offset);
  llvm::Type* ref_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Value* referent = nullptr;
  if (UseCompressedOops) {
    llvm::Type* narrow_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace);
    llvm::LoadInst* narrow_oop = ir_builder.CreateLoad(narrow_type, referent_addr);
    narrow_oop->setAtomic(llvm::AtomicOrdering::Unordered);
    referent = ir_builder.CreateAddrSpaceCast(narrow_oop, ref_type);
  } else {
    llvm::LoadInst* wide_oop = ir_builder.CreateLoad(ref_type, referent_addr);
    wide_oop->setAtomic(llvm::AtomicOrdering::Unordered);
    referent = wide_oop;
  }
  // CPUOrder fence: equivalent to C2's MemBarCPUOrder. Prevents the compiler from
  // CSE'ing this referent load across safepoints (GC can change the referent at
  // any safepoint). Singlethread scope ensures no hardware fence instructions.
  ir_builder.CreateFence(llvm::AtomicOrdering::SequentiallyConsistent,
                         llvm::SyncScope::SingleThread);
  llvm::Value* is_equal = ir_builder.CreateICmpEQ(referent, compare_to);
  // JVM boolean on the operand stack is i32
  llvm::Value* result = ir_builder.CreateZExt(is_equal, ir_builder.getInt32Ty());
  ir_builder.CreateRet(result);
JAVA_OP_END

// Reference.get: load the referent, apply the G1 SATB pre-barrier (if using G1GC),
// and insert a CPUOrder fence to prevent the optimizer from CSE'ing referent loads
// across safepoints (GC can change the referent value asynchronously).
// Equivalent to C2's inline_reference_get(): Unordered load + MemBarCPUOrder.
DEF_JAVA_OP(reference_get, 1,
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* ref_obj = func->getArg(0);
  llvm::GlobalVariable* offset_gv = template_module.getGlobalVariable("java_lang_ref_Reference.referent_offset", /*AllowInternal=*/true);
  if (!offset_gv) {
    RuntimeDefinedJavaOps::set_failed("java_lang_ref_Reference.referent_offset global not found in template module");
    return;
  }
  llvm::Value* offset = ir_builder.CreateLoad(ir_builder.getInt32Ty(), offset_gv);
  llvm::Value* referent_addr = ir_builder.CreateInBoundsGEP(ir_builder.getInt8Ty(), ref_obj, offset);
  llvm::Type* ref_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace);
  llvm::Value* referent = nullptr;
  if (UseCompressedOops) {
    llvm::Type* narrow_type = llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace);
    llvm::LoadInst* narrow_oop = ir_builder.CreateLoad(narrow_type, referent_addr);
    narrow_oop->setAtomic(llvm::AtomicOrdering::Unordered);
    referent = ir_builder.CreateAddrSpaceCast(narrow_oop, ref_type);
  } else {
    llvm::LoadInst* wide_oop = ir_builder.CreateLoad(ref_type, referent_addr);
    wide_oop->setAtomic(llvm::AtomicOrdering::Unordered);
    referent = wide_oop;
  }
  // G1 SATB pre-barrier: record the loaded referent value so concurrent marking
  // does not miss it. In C2, the ON_WEAK_OOP_REF decorator triggers this in the
  // GC barrier set; here we call the barrier directly with the already-loaded value.
  if (UseG1GC) {
    llvm::Function* barrier_func = template_module.getFunction("jeandle.g1_pre_barrier_loaded");
    assert(barrier_func != nullptr, "jeandle.g1_pre_barrier_loaded not found");
    llvm::CallInst* call = ir_builder.CreateCall(barrier_func, {referent});
    call->setCallingConv(llvm::CallingConv::Hotspot_JIT);
  }
  // CPUOrder fence: equivalent to C2's MemBarCPUOrder. Prevents the compiler from
  // commoning/CSE'ing this referent load across safepoints, since GC can clear the
  // referent at any safepoint. The singlethread scope ensures no hardware fence
  // instructions are emitted (x86/AArch64/RISC-V lower this to ISD::MEMBARRIER,
  // which becomes a no-op in assembly).
  ir_builder.CreateFence(llvm::AtomicOrdering::SequentiallyConsistent,
                         llvm::SyncScope::SingleThread);
  ir_builder.CreateRet(referent);
JAVA_OP_END

// Fast path for System.identityHashCode / Object.hashCode: extract the hash
// from the object's mark word inline. Returns the hash on success, 0 on failure
// (object locked/inflated, or hash not yet installed). Caller must guarantee
// obj != null and must fall back to a runtime call when this returns 0.
// Keep the identity observation opaque until PEA has materialized a virtual
// receiver and replayed its state; phase 1 expands the mark-word access later.
DEF_JAVA_OP(hashcode_fast, 1, llvm::Type::getInt32Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* obj = func->getArg(0);

  llvm::IntegerType* mark_ty = ir_builder.getIntNTy(sizeof(uintptr_t) * 8);
  llvm::Value* mark_addr = ir_builder.CreateGEP(
      ir_builder.getInt8Ty(), obj,
      ir_builder.getInt64(oopDesc::mark_offset_in_bytes()));
  llvm::LoadInst* mark = ir_builder.CreateLoad(mark_ty, mark_addr, "mark");
  mark->setAlignment(llvm::Align(sizeof(uintptr_t)));
  mark->setAtomic(llvm::AtomicOrdering::Unordered);

  llvm::Constant* lock_mask_c =
      llvm::ConstantInt::get(mark_ty, markWord::lock_mask_in_place);
  llvm::Value* lock_bits = ir_builder.CreateAnd(mark, lock_mask_c, "lock_bits");

  llvm::BasicBlock* hash_check_bb = llvm::BasicBlock::Create(context, "hash_check", func);
  llvm::BasicBlock* success_bb    = llvm::BasicBlock::Create(context, "success", func);
  llvm::BasicBlock* fail_bb       = llvm::BasicBlock::Create(context, "fail", func);

  // Lock-state guard: LM_LIGHTWEIGHT fails on monitor_value; default fails on
  // anything that isn't unlocked_value (stack-locked or inflated).
  if (LockingMode == LM_LIGHTWEIGHT) {
    llvm::Constant* monitor_c = llvm::ConstantInt::get(mark_ty, markWord::monitor_value);
    llvm::Value* is_monitor = ir_builder.CreateICmpEQ(lock_bits, monitor_c, "is_monitor");
    ir_builder.CreateCondBr(is_monitor, fail_bb, hash_check_bb);
  } else {
    llvm::Constant* unlocked_c = llvm::ConstantInt::get(mark_ty, markWord::unlocked_value);
    llvm::Value* is_unlocked = ir_builder.CreateICmpEQ(lock_bits, unlocked_c, "is_unlocked");
    ir_builder.CreateCondBr(is_unlocked, hash_check_bb, fail_bb);
  }

  // hash = ((mark >> hash_shift) & hash_mask) truncated to i32.
  // hash_mask is 31 bits, so the i32 truncation is lossless.
  ir_builder.SetInsertPoint(hash_check_bb);
  llvm::Constant* hash_shift_c = llvm::ConstantInt::get(mark_ty, markWord::hash_shift);
  llvm::Value* shifted = ir_builder.CreateLShr(mark, hash_shift_c, "hash_shifted");
  llvm::Value* hash_i32 = ir_builder.CreateTrunc(shifted, ir_builder.getInt32Ty(), "hash_truncated");
  llvm::Constant* hash_mask_c = llvm::ConstantInt::get(ir_builder.getInt32Ty(), markWord::hash_mask);
  llvm::Value* hash = ir_builder.CreateAnd(hash_i32, hash_mask_c, "hash");
  // no_hash = 0; any successfully-installed hash is non-zero.
  llvm::Value* has_hash = ir_builder.CreateICmpNE(hash, ir_builder.getInt32(0), "has_hash");
  ir_builder.CreateCondBr(has_hash, success_bb, fail_bb);

  ir_builder.SetInsertPoint(success_bb);
  ir_builder.CreateRet(hash);

  ir_builder.SetInsertPoint(fail_bb);
  ir_builder.CreateRet(ir_builder.getInt32(0));
JAVA_OP_END

DEF_JAVA_OP(encode_heap_oop, 9, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace))
  llvm::Value* obj_addr = func->getArg(0);
  llvm::Value* obj_ptr = ir_builder.CreatePtrToInt(obj_addr, llvm::Type::getInt64Ty(context));
  llvm::Value* narrow_ptr = nullptr;
  if (CompressedOops::base() == nullptr) {
    if (CompressedOops::shift() != 0) {
      obj_ptr = ir_builder.CreateLShr(obj_ptr, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedOops::shift()));
    }
    narrow_ptr = ir_builder.CreateTrunc(obj_ptr, llvm::Type::getInt32Ty(context));
  } else {
    llvm::NamedMDNode* heap_base = template_module.getNamedMetadata(llvm::jeandle::Metadata::HeapBase);
    assert(heap_base != nullptr, "heap_base metadata must exist");
    llvm::Value* read_register_args[] = {llvm::MetadataAsValue::get(context, heap_base->getOperand(0))};
    llvm::CallInst* base  = ir_builder.CreateIntrinsic(llvm::Intrinsic::read_register,
                                                      llvm::Type::getInt64Ty(context),
                                                      read_register_args);
    llvm::Value* diff = ir_builder.CreateSub(obj_ptr, base);

    llvm::Value* is_null = ir_builder.CreateIsNull(obj_addr);
    llvm::Value* safe_diff = ir_builder.CreateSelect(is_null, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), 0), diff);

    llvm::Value* shifted = ir_builder.CreateLShr(safe_diff, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedOops::shift()));

    narrow_ptr = ir_builder.CreateTrunc(shifted, llvm::Type::getInt32Ty(context));
  }
  llvm::Value* narrow_addr = ir_builder.CreateIntToPtr(narrow_ptr, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace));
  ir_builder.CreateRet(narrow_addr);
JAVA_OP_END

DEF_JAVA_OP(decode_heap_oop, 9, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::NarrowOopAddrSpace))
  llvm::Value* narrow_addr = func->getArg(0);
  llvm::Value* narrow_ptr = ir_builder.CreatePtrToInt(narrow_addr, llvm::Type::getInt32Ty(context));
  llvm::Value* obj_ptr = ir_builder.CreateZExt(narrow_ptr, llvm::Type::getInt64Ty(context));
  if (CompressedOops::base() == nullptr) {
    if (CompressedOops::shift() != 0) {
      obj_ptr = ir_builder.CreateShl(obj_ptr, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedOops::shift()));
    }
  } else {
    llvm::Value* shifted = ir_builder.CreateShl(obj_ptr, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedOops::shift()));
    llvm::NamedMDNode* heap_base = template_module.getNamedMetadata(llvm::jeandle::Metadata::HeapBase);
    assert(heap_base != nullptr, "heap_base metadata must exist");
    llvm::Value* read_register_args[] = {llvm::MetadataAsValue::get(context, heap_base->getOperand(0))};
    llvm::CallInst* base  = ir_builder.CreateIntrinsic(llvm::Intrinsic::read_register,
                                                      llvm::Type::getInt64Ty(context),
                                                      read_register_args);
    llvm::Value* decoded = ir_builder.CreateAdd(shifted, base);

    llvm::Value* is_null = ir_builder.CreateIsNull(narrow_addr);
    obj_ptr = ir_builder.CreateSelect(is_null, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), 0), decoded);
  }
  llvm::Value* obj_addr = ir_builder.CreateIntToPtr(obj_ptr, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace));
  ir_builder.CreateRet(obj_addr);
JAVA_OP_END

DEF_JAVA_OP(encode_klass, 1, llvm::Type::getInt32Ty(context), llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace))
  llvm::Value* klass_ptr = func->getArg(0);
  llvm::Value* wide = ir_builder.CreatePtrToInt(klass_ptr, llvm::Type::getInt64Ty(context));

  if (CompressedKlassPointers::base() != nullptr) {
    wide = ir_builder.CreateSub(wide, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), (uint64_t)CompressedKlassPointers::base()));
  }

  if (CompressedKlassPointers::shift() != 0) {
    assert(LogKlassAlignmentInBytes == CompressedKlassPointers::shift(), "decode alg wrong");
    wide = ir_builder.CreateLShr(wide, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedKlassPointers::shift()));
  }

  llvm::Value* narrow_klass = ir_builder.CreateTrunc(wide, llvm::Type::getInt32Ty(context));

  ir_builder.CreateRet(narrow_klass);
JAVA_OP_END

DEF_JAVA_OP(decode_klass, 1, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace), llvm::Type::getInt32Ty(context))
  llvm::Value* narrow_klass = func->getArg(0);
  llvm::Value* wide = ir_builder.CreateZExt(narrow_klass, llvm::Type::getInt64Ty(context));

  // wide = (narrow << shift) + base
  // Klass* is never null, no null check needed.
  if (CompressedKlassPointers::shift() != 0) {
    assert(LogKlassAlignmentInBytes == CompressedKlassPointers::shift(), "decode alg wrong");
    wide = ir_builder.CreateShl(wide, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), CompressedKlassPointers::shift()));
  }

  if (CompressedKlassPointers::base() != nullptr) {
    wide = ir_builder.CreateAdd(wide, llvm::ConstantInt::get(llvm::Type::getInt64Ty(context), (uint64_t)CompressedKlassPointers::base()));
  }

  llvm::Value* klass_ptr = ir_builder.CreateIntToPtr(wide, llvm::PointerType::get(context, llvm::jeandle::AddrSpace::CHeapAddrSpace));
  ir_builder.CreateRet(klass_ptr);
JAVA_OP_END

// HeapAccess<>::clone supplies the collector's bulk post-barrier.
static void emit_runtime_clone(llvm::Module& module,
                               llvm::IRBuilder<>& builder,
                               llvm::Value* src,
                               llvm::Value* dst,
                               llvm::Value* payload_long_count) {
  llvm::LLVMContext& context = builder.getContext();
  llvm::Type* intptr_type = builder.getIntPtrTy(module.getDataLayout());

  // The native clone expects the complete object size in HeapWords. Restore
  // the header omitted from the pseudo operation's Java-long payload count.
  llvm::Value* header_long_count = llvm::ConstantInt::get(
      intptr_type,
      clone_payload_base_offset(/*is_array=*/false) >> LogBytesPerLong);
  llvm::Value* payload_long_count_x = builder.CreateIntCast(
      payload_long_count, intptr_type, /*isSigned=*/true,
      "clone.payload_long_count_x");
  llvm::Value* object_long_count = builder.CreateAdd(
      payload_long_count_x, header_long_count, "clone.object_long_count");
  llvm::Value* object_heap_word_count = builder.CreateShl(
      object_long_count, llvm::ConstantInt::get(intptr_type, LogHeapWordsPerLong),
      "clone.object_heap_word_count");

  llvm::FunctionCallee clone =
      JeandleRuntimeRoutine::JeandleRuntime_clone_callee(module);
  llvm::CallInst* call =
      builder.CreateCall(clone, {src, dst, object_heap_word_count});
  call->setCallingConv(llvm::CallingConv::C);
  call->addFnAttr(llvm::Attribute::NoUnwind);
  call->addFnAttr(llvm::Attribute::get(context, "gc-leaf-function"));
}

static void emit_raw_clone_payload_copy(llvm::Module& module,
                                        llvm::IRBuilder<>& builder,
                                        llvm::Value* src,
                                        llvm::Value* src_offset,
                                        llvm::Value* dst,
                                        llvm::Value* dst_offset,
                                        llvm::Value* payload_long_count) {
  llvm::Value* payload_src = builder.CreateGEP(
      builder.getInt8Ty(), src, src_offset, "clone.payload_src");
  llvm::Value* payload_dst = builder.CreateGEP(
      builder.getInt8Ty(), dst, dst_offset, "clone.payload_dst");
  llvm::Function* arraycopy =
      module.getFunction("StubRoutines_jlong_disjoint_arraycopy");
  assert(arraycopy != nullptr, "clone arraycopy stub must exist");
  llvm::CallInst* call = builder.CreateCall(
      arraycopy->getFunctionType(), arraycopy,
      {payload_src, payload_dst, payload_long_count});
  call->setCallingConv(llvm::CallingConv::C);
  call->addFnAttr(llvm::Attribute::NoUnwind);
  call->addFnAttr(llvm::Attribute::get(
      builder.getContext(), "gc-leaf-function"));
}

// The destination must be a fresh allocation tightly coupled to this copy,
// with no intervening safepoint or publication. ReduceInitialCardMarks relies
// on that property to omit barriers via compensating allocation card marks.
// With barriers required, only primitive arrays may take the raw path;
// reference arrays are routed separately to oop-aware arraycopy stubs.
// Instance copies use HeapAccess<>::clone and its bulk region invalidation.
// This conservatively dirties the destination for both Serial and G1; C2's
// CardTableBarrierSetC2::clone instead emits post_barrier, including G1's
// dynamic young-card check.
DEF_JAVA_OP(clone_payload, 1, llvm::Type::getVoidTy(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt64Ty(context),
            llvm::PointerType::get(context, llvm::jeandle::AddrSpace::JavaHeapAddrSpace),
            llvm::Type::getInt64Ty(context), llvm::Type::getInt64Ty(context),
            llvm::Type::getInt1Ty(context))
  llvm::Value* src = func->getArg(0);
  llvm::Value* src_offset = func->getArg(1);
  llvm::Value* dst = func->getArg(2);
  llvm::Value* dst_offset = func->getArg(3);
  llvm::Value* payload_long_count = func->getArg(4);
  llvm::Value* is_clone_instance = func->getArg(5);

  if (copy_requires_gc_barriers(/*tightly_coupled_alloc=*/true,
                                 T_OBJECT)) {
    llvm::BasicBlock* runtime_clone =
        llvm::BasicBlock::Create(context, "clone.runtime", func);
    llvm::BasicBlock* raw_clone =
        llvm::BasicBlock::Create(context, "clone.raw", func);
    llvm::BasicBlock* done =
        llvm::BasicBlock::Create(context, "clone.done", func);
    ir_builder.CreateCondBr(is_clone_instance, runtime_clone, raw_clone);

    ir_builder.SetInsertPoint(runtime_clone);
    emit_runtime_clone(template_module, ir_builder, src, dst, payload_long_count);
    ir_builder.CreateBr(done);

    ir_builder.SetInsertPoint(raw_clone);
    emit_raw_clone_payload_copy(template_module, ir_builder, src, src_offset,
                                dst, dst_offset, payload_long_count);
    ir_builder.CreateBr(done);

    ir_builder.SetInsertPoint(done);
  } else {
    emit_raw_clone_payload_copy(template_module, ir_builder, src, src_offset,
                                dst, dst_offset, payload_long_count);
  }
  ir_builder.CreateRetVoid();
JAVA_OP_END

static inline void insert_patch_size_metadata(
  llvm::Module &template_module, llvm::LLVMContext &context,
  const char *patch_type, int patch_size) {
    llvm::NamedMDNode* patch_node = template_module.getOrInsertNamedMetadata(patch_type);
    assert(patch_node != nullptr, "invalid patch node");
    llvm::Metadata* patch_size_md =
    llvm::ConstantAsMetadata::get(
      llvm::ConstantInt::get(llvm::Type::getInt32Ty(context),
      patch_size));
      patch_node->addOperand(llvm::MDNode::get(context, patch_size_md));
    }

} // anonymous namespace

namespace {

enum BoxMetadataKind : uint8_t {
  BoxMetadataBoolean = 0,
  BoxMetadataByteCache,
  BoxMetadataCharacterCache,
  BoxMetadataShortCache,
  BoxMetadataIntegerCache,
  BoxMetadataLongCache,
  BoxMetadataKindCount
};

enum BoxMetadataState : uint8_t {
  BoxMetadataUnseen = 0,
  BoxMetadataInitializing,
  BoxMetadataReady
};

struct BoxMetadataValues {
  uintptr_t klass;
  int32_t offset0;
  int32_t offset1;
  int32_t offset2;
};

// Cache classes are initialized lazily. Keep successful lookups for the rest
// of the VM lifetime, but do not permanently remember an unsuccessful lookup:
// a later valueOf call may have initialized the cache class.
std::atomic<uint8_t> box_metadata_states[BoxMetadataKindCount];
BoxMetadataValues box_metadata_values[BoxMetadataKindCount];

bool query_box_metadata(BoxMetadataKind kind, BoxMetadataValues& values) {
  Symbol* klass_name = nullptr;
  Symbol* signature0 = nullptr;
  Symbol* signature1 = nullptr;
  Symbol* signature2 = nullptr;
  const char* field_name0 = nullptr;
  const char* field_name1 = nullptr;
  const char* field_name2 = nullptr;

  switch (kind) {
    case BoxMetadataBoolean:
      klass_name = vmSymbols::java_lang_Boolean();
      signature0 = vmSymbols::java_lang_Boolean_signature();
      signature1 = vmSymbols::java_lang_Boolean_signature();
      field_name0 = "TRUE";
      field_name1 = "FALSE";
      break;
    case BoxMetadataByteCache:
      klass_name = vmSymbols::java_lang_Byte_ByteCache();
      signature0 = vmSymbols::java_lang_Byte_array_signature();
      field_name0 = "cache";
      break;
    case BoxMetadataCharacterCache:
      klass_name = vmSymbols::java_lang_Character_CharacterCache();
      signature0 = vmSymbols::java_lang_Character_array_signature();
      field_name0 = "cache";
      break;
    case BoxMetadataShortCache:
      klass_name = vmSymbols::java_lang_Short_ShortCache();
      signature0 = vmSymbols::java_lang_Short_array_signature();
      field_name0 = "cache";
      break;
    case BoxMetadataIntegerCache:
      klass_name = vmSymbols::java_lang_Integer_IntegerCache();
      signature0 = vmSymbols::java_lang_Integer_array_signature();
      signature1 = vmSymbols::int_signature();
      signature2 = vmSymbols::int_signature();
      field_name0 = "cache";
      field_name1 = "low";
      field_name2 = "high";
      break;
    case BoxMetadataLongCache:
      klass_name = vmSymbols::java_lang_Long_LongCache();
      signature0 = vmSymbols::java_lang_Long_array_signature();
      field_name0 = "cache";
      break;
    default:
      return false;
  }

  InstanceKlass* klass = SystemDictionary::find_instance_klass(
      Thread::current(), klass_name, Handle(), Handle());
  if (klass == nullptr || !klass->is_initialized()) {
    return false;
  }

  auto find_offset = [&](const char* field_name, Symbol* signature,
                         int32_t& offset) {
    TempNewSymbol field_symbol = SymbolTable::new_symbol(field_name);
    fieldDescriptor field;
    if (klass->find_field(field_symbol, signature, true, &field) == nullptr) {
      return false;
    }
    offset = field.offset();
    return true;
  };

  if (!find_offset(field_name0, signature0, values.offset0)) {
    return false;
  }
  values.offset1 = 0;
  if (field_name1 != nullptr &&
      !find_offset(field_name1, signature1, values.offset1)) {
    return false;
  }
  values.offset2 = 0;
  if (field_name2 != nullptr &&
      !find_offset(field_name2, signature2, values.offset2)) {
    return false;
  }
  values.klass = reinterpret_cast<uintptr_t>(klass);
  return true;
}

bool get_box_metadata(BoxMetadataKind kind, BoxMetadataValues& values) {
  std::atomic<uint8_t>& state = box_metadata_states[kind];
  if (state.load(std::memory_order_acquire) == BoxMetadataReady) {
    values = box_metadata_values[kind];
    return true;
  }

  uint8_t expected = BoxMetadataUnseen;
  if (!state.compare_exchange_strong(expected, BoxMetadataInitializing,
                                     std::memory_order_acq_rel,
                                     std::memory_order_acquire)) {
    // Another compiler thread is resolving this kind. It is safe for this
    // compilation to use the template's zero marker; a later compilation
    // will observe the published result.
    return false;
  }

  BoxMetadataValues resolved{};
  if (!query_box_metadata(kind, resolved)) {
    // Do not cache the negative result: lazy cache initialization can make a
    // later compilation eligible for semantic boxing.
    state.store(BoxMetadataUnseen, std::memory_order_release);
    return false;
  }

  box_metadata_values[kind] = resolved;
  state.store(BoxMetadataReady, std::memory_order_release);
  values = resolved;
  return true;
}

void set_box_metadata_global(llvm::Module& module,
                             const char* name,
                             llvm::Type* type,
                             uint64_t value) {
  llvm::GlobalVariable* global =
      module.getGlobalVariable(name, /*AllowInternal=*/true);
  guarantee(global != nullptr, "boxing metadata global is missing: %s", name);
  global->setInitializer(llvm::ConstantInt::get(type, value));
  global->setConstant(true);
}

} // anonymous namespace

const char* RuntimeDefinedJavaOps::_error_msg = nullptr;

bool RuntimeDefinedJavaOps::define_all(llvm::Module& template_module) {
  reset_state();

  // Define all necessary metadata nodes:
  define_metadata(template_module);

  // Define all global variables.
  define_global_variables(template_module);

  // Define all runtime defined JavaOps:
  define_current_thread(template_module);
  define_membar_cpuorder(template_module);
  define_allocation_prefetch(template_module);
  define_zero_half_heap_word(template_module);
  define_zero_heap_words(template_module);
  define_safepoint_poll(template_module);
  define_card_table_barrier(template_module);
  define_pre_barrier(template_module);
  define_post_barrier(template_module);
  define_load_mirror_from_klass(template_module);
  define_get_class(template_module);
  define_current_thread_obj(template_module);
  define_reference_refers_to(template_module);
  define_reference_get(template_module);
  define_encode_heap_oop(template_module);
  define_decode_heap_oop(template_module);
  define_encode_klass(template_module);
  define_decode_klass(template_module);
  define_hashcode_fast(template_module);
  define_unbox(template_module);
  define_box(template_module);
  define_clone_payload(template_module);

  return failed();
}

void RuntimeDefinedJavaOps::refresh_boxing_metadata(llvm::Module& target_module) {
  llvm::LLVMContext& context = target_module.getContext();
  llvm::Type* int32_type = llvm::Type::getInt32Ty(context);
  llvm::Type* int64_type = llvm::Type::getInt64Ty(context);

  auto refresh = [&](BoxMetadataKind kind,
                     const char* klass_global,
                     const char* offset0_global,
                     const char* offset1_global = nullptr,
                     const char* offset2_global = nullptr) {
    BoxMetadataValues values{};
    if (!get_box_metadata(kind, values)) {
      return;
    }
    set_box_metadata_global(target_module, klass_global, int64_type,
                            static_cast<uint64_t>(values.klass));
    set_box_metadata_global(target_module, offset0_global, int32_type,
                            static_cast<uint64_t>(values.offset0));
    if (offset1_global != nullptr) {
      set_box_metadata_global(target_module, offset1_global, int32_type,
                              static_cast<uint64_t>(values.offset1));
    }
    if (offset2_global != nullptr) {
      set_box_metadata_global(target_module, offset2_global, int32_type,
                              static_cast<uint64_t>(values.offset2));
    }
  };

  refresh(BoxMetadataBoolean,
          "jeandle.box.Boolean.klass",
          "jeandle.box.Boolean.true_offset",
          "jeandle.box.Boolean.false_offset");
  refresh(BoxMetadataByteCache,
          "jeandle.box.ByteCache.klass",
          "jeandle.box.ByteCache.cache_offset");
  refresh(BoxMetadataCharacterCache,
          "jeandle.box.CharacterCache.klass",
          "jeandle.box.CharacterCache.cache_offset");
  refresh(BoxMetadataShortCache,
          "jeandle.box.ShortCache.klass",
          "jeandle.box.ShortCache.cache_offset");
  refresh(BoxMetadataIntegerCache,
          "jeandle.box.IntegerCache.klass",
          "jeandle.box.IntegerCache.cache_offset",
          "jeandle.box.IntegerCache.low_offset",
          "jeandle.box.IntegerCache.high_offset");
  refresh(BoxMetadataLongCache,
          "jeandle.box.LongCache.klass",
          "jeandle.box.LongCache.cache_offset");
}

void RuntimeDefinedJavaOps::define_metadata(llvm::Module& template_module) {
  llvm::LLVMContext& context = template_module.getContext();

  // Current thread register:
  {
    llvm::MDNode* thread_register = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_current_thread_pointer())});
    llvm::NamedMDNode* metadata_node = template_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::CurrentThread);
    metadata_node->addOperand(thread_register);
  }

  // Stack pointer register:
  {
    llvm::MDNode* stack_pointer = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_stack_pointer())});
    llvm::NamedMDNode* metadata_node = template_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::StackPointer);
    metadata_node->addOperand(stack_pointer);
  }

  // Heap base register (only reserved when compressed oops is enabled):
  if (UseCompressedOops) {
    llvm::MDNode* heap_base_register = llvm::MDNode::get(context, {llvm::MDString::get(context, JeandleRegister::get_heap_base_pointer())});
    llvm::NamedMDNode* metadata_node = template_module.getOrInsertNamedMetadata(llvm::jeandle::Metadata::HeapBase);
    metadata_node->addOperand(heap_base_register);
  }

  // Call patch size info.
  {
    insert_patch_size_metadata(template_module, context,
        llvm::jeandle::Metadata::StaticCallPatchSize,
        JeandleCompiledCall::call_site_patch_size(JeandleCompiledCall::STATIC_CALL));
    insert_patch_size_metadata(template_module, context,
        llvm::jeandle::Metadata::DynamicCallPatchSize,
        JeandleCompiledCall::call_site_patch_size(JeandleCompiledCall::DYNAMIC_CALL));
  }
}

void RuntimeDefinedJavaOps::define_global_variables(llvm::Module& template_module) {
  llvm::LLVMContext& context = template_module.getContext();
  llvm::IRBuilder<> ir_builder(context);

  // Define a global variable in template module.
  auto define_global = [&](const llvm::StringRef name, llvm::Type* type, uint64_t value) {
    llvm::GlobalVariable* global_var =
        template_module.getGlobalVariable(name, /*AllowInternal=*/true);
    assert(global_var && global_var->isDeclaration(), "unexpected declaration");

    global_var->setInitializer(llvm::ConstantInt::get(type, value));
    global_var->setConstant(true);
    global_var->setLinkage(llvm::GlobalValue::PrivateLinkage);
  };

  llvm::Type* int1_type  = llvm::Type::getInt1Ty(context);
  llvm::Type* int8_type  = llvm::Type::getInt8Ty(context);
  llvm::Type* int32_type = llvm::Type::getInt32Ty(context);
  llvm::Type* int64_type = llvm::Type::getInt64Ty(context);

#ifdef ASSERT
  define_global("DEBUG_MODE",                                       int1_type,  static_cast<uint64_t>(true));
#else
  define_global("DEBUG_MODE",                                       int1_type,  static_cast<uint64_t>(false));
#endif

  // The cache classes are not VM classes, so keep their metadata and static
  // field offsets in the template as compile-time constants. Integer uses the
  // live low/high fields because its CDS-restored backing array may be larger
  // than the cache range selected for the current run. The other integral
  // wrappers have fixed caches whose bounds are represented by their arrays.
  auto define_box_static_field = [&](const char* klass_global,
                                     const char* offset_global,
                                     Symbol* klass_name,
                                     Symbol* signature,
                                     const char* field_name) -> bool {
    InstanceKlass* klass = SystemDictionary::find_instance_klass(
        Thread::current(), klass_name, Handle(), Handle());
    auto define_zero_if_needed = [&](const char* name, llvm::Type* type) {
      llvm::GlobalVariable* global =
          template_module.getGlobalVariable(name, /*AllowInternal=*/true);
      if (global != nullptr && global->isDeclaration())
        define_global(name, type, 0);
    };
    if (klass == nullptr || !klass->is_initialized()) {
      // Cache classes can be initialized after the compiler template is
      // created. Leave a safe zero marker; the frontend then rejects the
      // semantic box and keeps the original valueOf invoke.
      define_zero_if_needed(klass_global, int64_type);
      define_zero_if_needed(offset_global, int32_type);
      return false;
    }
    TempNewSymbol field_symbol = SymbolTable::new_symbol(field_name);
    fieldDescriptor field;
    if (klass->find_field(field_symbol, signature, true, &field) == nullptr) {
      define_zero_if_needed(klass_global, int64_type);
      define_zero_if_needed(offset_global, int32_type);
      return false;
    }
    llvm::GlobalVariable* class_global =
        template_module.getGlobalVariable(klass_global, /*AllowInternal=*/true);
    if (class_global != nullptr && class_global->isDeclaration()) {
      define_global(klass_global, int64_type,
                    static_cast<uint64_t>(reinterpret_cast<uintptr_t>(klass)));
    }
    define_global(offset_global, int32_type,
                  static_cast<uint64_t>(field.offset()));
    return true;
  };

  define_box_static_field("jeandle.box.Boolean.klass",
                          "jeandle.box.Boolean.true_offset",
                          vmSymbols::java_lang_Boolean(),
                          vmSymbols::java_lang_Boolean_signature(), "TRUE");
  define_box_static_field("jeandle.box.Boolean.klass",
                          "jeandle.box.Boolean.false_offset",
                          vmSymbols::java_lang_Boolean(),
                          vmSymbols::java_lang_Boolean_signature(), "FALSE");
  define_box_static_field("jeandle.box.ByteCache.klass",
                          "jeandle.box.ByteCache.cache_offset",
                          vmSymbols::java_lang_Byte_ByteCache(),
                          vmSymbols::java_lang_Byte_array_signature(), "cache");
  define_box_static_field("jeandle.box.CharacterCache.klass",
                          "jeandle.box.CharacterCache.cache_offset",
                          vmSymbols::java_lang_Character_CharacterCache(),
                          vmSymbols::java_lang_Character_array_signature(), "cache");
  define_box_static_field("jeandle.box.ShortCache.klass",
                          "jeandle.box.ShortCache.cache_offset",
                          vmSymbols::java_lang_Short_ShortCache(),
                          vmSymbols::java_lang_Short_array_signature(), "cache");
  define_box_static_field("jeandle.box.IntegerCache.klass",
                          "jeandle.box.IntegerCache.cache_offset",
                          vmSymbols::java_lang_Integer_IntegerCache(),
                          vmSymbols::java_lang_Integer_array_signature(), "cache");
  define_box_static_field("jeandle.box.IntegerCache.klass",
                          "jeandle.box.IntegerCache.low_offset",
                          vmSymbols::java_lang_Integer_IntegerCache(),
                          vmSymbols::int_signature(), "low");
  define_box_static_field("jeandle.box.IntegerCache.klass",
                          "jeandle.box.IntegerCache.high_offset",
                          vmSymbols::java_lang_Integer_IntegerCache(),
                          vmSymbols::int_signature(), "high");
  define_box_static_field("jeandle.box.LongCache.klass",
                          "jeandle.box.LongCache.cache_offset",
                          vmSymbols::java_lang_Long_LongCache(),
                          vmSymbols::java_lang_Long_array_signature(), "cache");

  define_global("KlassArray.base_offset_in_bytes",                  int32_type, static_cast<uint64_t>(Array<Klass*>::base_offset_in_bytes()));
  define_global("KlassArray.length_offset_in_bytes",                int32_type, static_cast<uint64_t>(Array<Klass*>::length_offset_in_bytes()));
  define_global("arrayOopDesc.length_offset_in_bytes",              int32_type, static_cast<uint64_t>(arrayOopDesc::length_offset_in_bytes()));
  // Per-element-type array base offsets. Consumed by the LLVM-side
  // partial escape analyzer via VMConstants::fromModule (see
  // jeandle-llvm/llvm/include/llvm/IR/Jeandle/VMConstants.h). The naming
  // convention must match jBasicTypeName() on the LLVM side.
  define_global("arrayOopDesc.base_offset_in_bytes.boolean",        int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_BOOLEAN)));
  define_global("arrayOopDesc.base_offset_in_bytes.byte",           int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_BYTE)));
  define_global("arrayOopDesc.base_offset_in_bytes.char",           int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_CHAR)));
  define_global("arrayOopDesc.base_offset_in_bytes.short",          int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_SHORT)));
  define_global("arrayOopDesc.base_offset_in_bytes.int",            int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_INT)));
  define_global("arrayOopDesc.base_offset_in_bytes.long",           int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_LONG)));
  define_global("arrayOopDesc.base_offset_in_bytes.float",          int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_FLOAT)));
  define_global("arrayOopDesc.base_offset_in_bytes.double",         int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_DOUBLE)));
  define_global("arrayOopDesc.base_offset_in_bytes.object",         int32_type, static_cast<uint64_t>(arrayOopDesc::base_offset_in_bytes(T_OBJECT)));
  define_global("heapOopSize",                                      int32_type, static_cast<uint64_t>(heapOopSize));
  // Per-element-type array element size in bytes. Same delivery model.
  define_global("arrayOopDesc.element_size.boolean",                int32_type, static_cast<uint64_t>(type2aelembytes(T_BOOLEAN)));
  define_global("arrayOopDesc.element_size.byte",                   int32_type, static_cast<uint64_t>(type2aelembytes(T_BYTE)));
  define_global("arrayOopDesc.element_size.char",                   int32_type, static_cast<uint64_t>(type2aelembytes(T_CHAR)));
  define_global("arrayOopDesc.element_size.short",                  int32_type, static_cast<uint64_t>(type2aelembytes(T_SHORT)));
  define_global("arrayOopDesc.element_size.int",                    int32_type, static_cast<uint64_t>(type2aelembytes(T_INT)));
  define_global("arrayOopDesc.element_size.long",                   int32_type, static_cast<uint64_t>(type2aelembytes(T_LONG)));
  define_global("arrayOopDesc.element_size.float",                  int32_type, static_cast<uint64_t>(type2aelembytes(T_FLOAT)));
  define_global("arrayOopDesc.element_size.double",                 int32_type, static_cast<uint64_t>(type2aelembytes(T_DOUBLE)));
  define_global("arrayOopDesc.element_size.object",                 int32_type, static_cast<uint64_t>(type2aelembytes(T_OBJECT)));
  define_global("Klass.access_flags_offset",                        int32_type, static_cast<uint64_t>(Klass::access_flags_offset()));
  define_global("Klass.java_mirror_offset",                         int32_type, static_cast<uint64_t>(in_bytes(Klass::java_mirror_offset())));
  define_global("Klass.layout_helper_offset",                       int32_type, static_cast<uint64_t>(in_bytes(Klass::layout_helper_offset())));
  define_global("Klass.secondary_super_cache_offset",               int32_type, static_cast<uint64_t>(Klass::secondary_super_cache_offset()));
  define_global("Klass.secondary_supers_offset",                    int32_type, static_cast<uint64_t>(Klass::secondary_supers_offset()));
  define_global("Klass.super_check_offset_offset",                  int32_type, static_cast<uint64_t>(Klass::super_check_offset_offset()));
  define_global("ObjArrayKlass.element_klass_offset",               int32_type, static_cast<uint64_t>(ObjArrayKlass::element_klass_offset()));
  define_global("InstanceKlass.init_state_offset",                  int32_type, static_cast<uint64_t>(in_bytes(InstanceKlass::init_state_offset())));
  define_global("InstanceKlass.fully_initialized",                  int8_type,  static_cast<uint64_t>(InstanceKlass::fully_initialized));
  define_global("oopDesc.klass_offset_in_bytes",                    int32_type, static_cast<uint64_t>(oopDesc::klass_offset_in_bytes()));
  define_global("oopDesc.mark_offset_in_bytes",                     int32_type, static_cast<uint64_t>(oopDesc::mark_offset_in_bytes()));
  define_global("java_lang_boxing_object.value_offset",             int32_type, static_cast<uint64_t>(java_lang_boxing_object::value_offset(T_INT)));
  define_global("java_lang_boxing_object.long_value_offset",        int32_type, static_cast<uint64_t>(java_lang_boxing_object::value_offset(T_LONG)));
  define_global("java_lang_ref_Reference.referent_offset",          int32_type, static_cast<uint64_t>(java_lang_ref_Reference::referent_offset()));
  define_global("java_lang_Class.klass_offset",                     int32_type, static_cast<uint64_t>(java_lang_Class::klass_offset()));
  define_global("java_lang_Class.array_klass_offset",               int32_type, static_cast<uint64_t>(java_lang_Class::array_klass_offset()));
  define_global("BasicLock.displaced_header_offset_in_bytes",       int32_type, static_cast<uint64_t>(BasicLock::displaced_header_offset_in_bytes()));
  define_global("JavaThread.held_monitor_count_offset",             int32_type, static_cast<uint64_t>(JavaThread::held_monitor_count_offset()));
  define_global("JavaThread.lock_stack_end",                        int32_type, static_cast<uint64_t>(LockStack::end_offset()));
  define_global("JavaThread.lock_stack_top_offset",                 int32_type, static_cast<uint64_t>(JavaThread::lock_stack_top_offset()));
  define_global("ObjectMonitor.EntryList_offset_no_monitor_value",  int32_type, static_cast<uint64_t>(OM_OFFSET_NO_MONITOR_VALUE_TAG(EntryList)));
  define_global("ObjectMonitor.cxq_offset_no_monitor_value",        int32_type, static_cast<uint64_t>(OM_OFFSET_NO_MONITOR_VALUE_TAG(cxq)));
  define_global("ObjectMonitor.owner_offset_no_monitor_value",      int32_type, static_cast<uint64_t>(OM_OFFSET_NO_MONITOR_VALUE_TAG(owner)));
  define_global("ObjectMonitor.recursions_offset_no_monitor_value", int32_type, static_cast<uint64_t>(OM_OFFSET_NO_MONITOR_VALUE_TAG(recursions)));
  define_global("ObjectMonitor.succ_offset_no_monitor_value",       int32_type, static_cast<uint64_t>(OM_OFFSET_NO_MONITOR_VALUE_TAG(succ)));
  define_global("instanceOopDesc.base_offset_in_bytes",             int32_type, static_cast<uint64_t>(instanceOopDesc::base_offset_in_bytes()));


  define_global("markWord.clear_lock_mask",                         int64_type, static_cast<uint64_t>(~(int32_t)markWord::lock_mask_in_place));
  define_global("markWord.monitor_value",                           int64_type, static_cast<uint64_t>(markWord::monitor_value));
  define_global("markWord.unlocked_value",                          int64_type, static_cast<uint64_t>(markWord::unlocked_value));
  define_global("markWord.unused_mark_value",                       int64_type, static_cast<uint64_t>(markWord::unused_mark().value()));
  define_global("ObjectMonitor.ANONYMOUS_OWNER",                    int64_type, static_cast<uint64_t>(ObjectMonitor::ANONYMOUS_OWNER));
  define_global("JavaThread.tlab_end_offset",                       int64_type, static_cast<uint64_t>(JavaThread::tlab_end_offset()));
  define_global("JavaThread.tlab_top_offset",                       int64_type, static_cast<uint64_t>(JavaThread::tlab_top_offset()));
  define_global("JavaThread.tlab_pf_top_offset",                    int64_type, static_cast<uint64_t>(JavaThread::tlab_pf_top_offset()));
  define_global("markWord.prototype_value",                         int64_type, static_cast<uint64_t>(markWord::prototype().value()));

  define_global("JVM_ACC_IS_VALUE_BASED_CLASS",                     int32_type, static_cast<uint64_t>(JVM_ACC_IS_VALUE_BASED_CLASS));
  define_global("JVM_ACC_HAS_FINALIZER",                            int32_type, static_cast<uint64_t>(JVM_ACC_HAS_FINALIZER));
  define_global("oopSize",                                          int32_type, static_cast<uint64_t>(oopSize));

  define_global("check_recursive_mask_value",                       int64_type, static_cast<uint64_t>(7 - (int)os::vm_page_size()));

  define_global("G1ThreadLocalData.satb_mark_queue_active_offset",  int32_type, static_cast<uint64_t>(G1ThreadLocalData::satb_mark_queue_active_offset()));
  define_global("G1ThreadLocalData.satb_mark_queue_index_offset",   int32_type, static_cast<uint64_t>(G1ThreadLocalData::satb_mark_queue_index_offset()));
  define_global("G1ThreadLocalData.satb_mark_queue_buffer_offset",  int32_type, static_cast<uint64_t>(G1ThreadLocalData::satb_mark_queue_buffer_offset()));
  define_global("G1ThreadLocalData.dirty_card_queue_index_offset",  int32_type, static_cast<uint64_t>(G1ThreadLocalData::dirty_card_queue_index_offset()));
  define_global("G1ThreadLocalData.dirty_card_queue_buffer_offset", int32_type, static_cast<uint64_t>(G1ThreadLocalData::dirty_card_queue_buffer_offset()));
  define_global("CardTable.card_shift",                             int64_type, static_cast<uint64_t>(CardTable::card_shift()));
  define_global("HeapRegion.LogOfHRGrainBytes",                     int64_type, static_cast<uint64_t>(HeapRegion::LogOfHRGrainBytes));
  define_global("WordSize",                                         int64_type, static_cast<uint64_t>(sizeof(intptr_t)));
  define_global("VMOptions.ArrayOperationPartialInlineSize",        int32_type, static_cast<uint64_t>(ArrayOperationPartialInlineSize));
  define_global("VMOptions.ArrayCopyLoadStoreMaxElem",              int32_type, static_cast<uint64_t>(ArrayCopyLoadStoreMaxElem));
  define_global("G1CardTable.g1_young_card_val",                    int8_type,  static_cast<uint64_t>(G1CardTable::g1_young_card_val()));
  define_global("G1CardTable.dirty_card_val",                       int8_type,  static_cast<uint64_t>(G1CardTable::dirty_card_val()));
  define_global("ci_card_table_address",                            int64_type, static_cast<uint64_t>(p2i(ci_card_table_address())));
  define_global("G1BarrierSetRuntime.write_ref_field_pre_entry",    int64_type, static_cast<uint64_t>(p2i(CAST_FROM_FN_PTR(address, G1BarrierSetRuntime::write_ref_field_pre_entry))));
  define_global("G1BarrierSetRuntime.write_ref_field_post_entry",   int64_type, static_cast<uint64_t>(p2i(CAST_FROM_FN_PTR(address, G1BarrierSetRuntime::write_ref_field_post_entry))));
  define_global("SharedRuntime.complete_monitor_unlocking_C",       int64_type, static_cast<uint64_t>(p2i(CAST_FROM_FN_PTR(address, SharedRuntime::complete_monitor_unlocking_C))));

  define_global("VMOptions.UseTLAB",                                int1_type, static_cast<uint64_t>(UseTLAB));
  define_global("VMOptions.ZeroTLAB",                               int1_type, static_cast<uint64_t>(ZeroTLAB));
  define_global("VMOptions.ReduceBulkZeroing",                      int1_type, static_cast<uint64_t>(ReduceBulkZeroing));
  define_global("VMOptions.ReduceInitialCardMarks",                 int1_type, static_cast<uint64_t>(ReduceInitialCardMarks));
  define_global("VMOptions.AllocateInstancePrefetchLines",          int32_type, static_cast<uint64_t>(AllocateInstancePrefetchLines));
  define_global("VMOptions.AllocatePrefetchLines",                  int32_type, static_cast<uint64_t>(AllocatePrefetchLines));
  define_global("VMOptions.UseCompressedClassPointers",             int1_type, static_cast<uint64_t>(UseCompressedClassPointers));
  define_global("VMOptions.UseCompressedOops",                      int1_type, static_cast<uint64_t>(UseCompressedOops));
}
