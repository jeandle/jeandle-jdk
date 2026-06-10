#include <stdio.h>
#include <string.h>
#include "jvmti.h"

extern "C" {

static jvmtiEnv* jvmti = nullptr;
static jobject exception_obj = nullptr;

#define LOG(...)                 \
  do {                           \
    printf(__VA_ARGS__);         \
    printf("\n");                \
    fflush(stdout);              \
  } while (0)

JNIEXPORT jint JNICALL
Agent_OnLoad(JavaVM* jvm, char* options, void* reserved) {
  LOG("Agent_OnLoad started");

  if (jvm->GetEnv(reinterpret_cast<void**>(&jvmti), JVMTI_VERSION) != JNI_OK || jvmti == nullptr) {
    return JNI_ERR;
  }

  jvmtiCapabilities caps;
  memset(&caps, 0, sizeof(caps));
  caps.can_signal_thread = 1;

  jvmtiError err = jvmti->AddCapabilities(&caps);
  if (err != JVMTI_ERROR_NONE) {
    LOG("AddCapabilities failed: %d", err);
    return JNI_ERR;
  }

  LOG("Agent_OnLoad finished");
  return JNI_OK;
}

JNIEXPORT void JNICALL
Java_compiler_jeandle_safepoint_TestJeandleAsyncExceptionAtSafepoint_prepareAgent(JNIEnv* env, jclass cls, jobject exception) {
  exception_obj = env->NewGlobalRef(exception);
  if (exception_obj == nullptr) {
    env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "failed to create global exception reference");
  }
}

JNIEXPORT jint JNICALL
Java_compiler_jeandle_safepoint_TestJeandleAsyncExceptionAtSafepoint_stopThread(JNIEnv* env, jclass cls, jthread thread) {
  if (jvmti == nullptr) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "JVMTI environment is not initialized");
    return JVMTI_ERROR_INTERNAL;
  }
  if (exception_obj == nullptr) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "exception object is not initialized");
    return JVMTI_ERROR_INTERNAL;
  }

  jvmtiError err = jvmti->StopThread(thread, exception_obj);
  LOG("StopThread returned: %d", err);
  return static_cast<jint>(err);
}

} // extern "C"

