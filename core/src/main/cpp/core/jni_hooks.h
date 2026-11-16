#ifndef BLACKBOX_JNI_HOOKS_H
#define BLACKBOX_JNI_HOOKS_H

#include <jni.h>

namespace jni_hooks {

/** Probe ArtMethod layout and register helper natives. Call once at BNative.init. */
void setup(JNIEnv *env, int api_level);

/** Install UnixFileSystem / Binder / DexFile / Runtime hooks. */
void init(JNIEnv *env);

void hook(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
          void *new_fun, void **orig_fun, bool is_static);

void hook(JNIEnv *env, jobject java_method, void *new_fun, void **orig_fun, bool is_static);

}  // namespace jni_hooks

#endif
