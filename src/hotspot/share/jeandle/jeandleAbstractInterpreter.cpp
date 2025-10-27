case Bytecodes::_aconst_null: {
  // _aconst_null pushes a null object reference onto the operand stack.
  // Get the LLVM type for an object pointer (Oop).
  llvm::Type* oop_type = JeandleType::oop_type(*_context);
  // Create a null constant of the appropriate pointer type.
  llvm::Value* null_oop = llvm::ConstantPointerNull::get(llvm::cast<llvm::PointerType>(oop_type));
  jvm->apush(null_oop); // Push the null object reference.
  // The program counter (pc) will be advanced by the main bytecode loop.
  break;
}