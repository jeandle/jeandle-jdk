AC_DEFUN_ONCE([LIB_SETUP_JEANDLE_LLVM],
[
  AC_ARG_WITH(jeandle-llvm,
    [AS_HELP_STRING([--with-jeandle-llvm], [specify the installation directory of jeandle-llvm])],
    [JEANDLE_LLVM_DIR="$withval"],
    [JEANDLE_LLVM_DIR=""])

  AC_ARG_WITH(host-jeandle-llvm,
    [AS_HELP_STRING([--with-host-jeandle-llvm],
     [specify the installation directory of jeandle-llvm of the host environment when crossing compile])],
    [OPENJDK_BUILD_JEANDLE_LLVM_DIR="$withval"],
    [OPENJDK_BUILD_JEANDLE_LLVM_DIR="$JEANDLE_LLVM_DIR"])

  AC_SUBST(JEANDLE_LLVM_DIR)
  AC_SUBST(OPENJDK_BUILD_JEANDLE_LLVM_DIR)
])
