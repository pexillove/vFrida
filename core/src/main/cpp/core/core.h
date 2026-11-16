#ifndef BLACKBOX_CORE_H
#define BLACKBOX_CORE_H

#include <jni.h>
#include <string>

#define VMCORE_CLASS "com/android/bb/BNative"

namespace core {

JavaVM *getJavaVM();
int getApiLevel();
int getCallingUid(JNIEnv *env, int orig);
jstring redirectPathString(JNIEnv *env, jstring path);
jobject redirectPathFile(JNIEnv *env, jobject path);
void bindSpindle(JNIEnv *env);
void onSpindleScript(const void *data, int len);
bool deliverSpindle(int id, const void *data, int len, std::string *reply);
bool launchForSpindle(const char *packageName);
bool launchForSpindleOnMain(const char *packageName);

}  // namespace core

#endif
