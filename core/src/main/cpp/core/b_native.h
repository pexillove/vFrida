#ifndef BLACKBOX_B_NATIVE_H
#define BLACKBOX_B_NATIVE_H

#include <jni.h>

#include <string>

/** JNI bridge to Java {@code com.android.bb.BNative}. */
class BNative {
public:
    static void setJavaVM(JavaVM *vm);
    static JavaVM *javaVM();

    /** Cache the Java class and common method IDs. Call from BNative.init. */
    static void bind(JNIEnv *env);

    static JNIEnv *env();

    static int getCallingUid(int orig);
    static jstring redirectPath(jstring path);
    static jobject redirectPath(jobject file);

    static std::string appPackageName();
    static bool isXhsFingerprintDumpEnabled();
    static std::string fingerprintDumpDir();

    static void onSpindleLog(const char *text);
    static void onSpindleScript(const void *data, int len);

private:
    BNative() = delete;

    static bool ensureBound(JNIEnv *env);
    static std::string callString(const char *name);
    static bool callBoolean(const char *name);
};

#endif
