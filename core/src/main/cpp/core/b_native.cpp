#include "b_native.h"

#include "log.h"

#include <cstring>

namespace {

constexpr const char *kClass = "com/android/bb/BNative";

JavaVM *g_vm = nullptr;
jclass g_clazz = nullptr;
jmethodID g_get_calling_uid = nullptr;
jmethodID g_redirect_path_string = nullptr;
jmethodID g_redirect_path_file = nullptr;
jmethodID g_on_spindle_log = nullptr;
jmethodID g_on_spindle_script = nullptr;
jmethodID g_app_package_name = nullptr;
jmethodID g_xhs_dump_enabled = nullptr;
jmethodID g_fingerprint_dump_dir = nullptr;

}  // namespace

void BNative::setJavaVM(JavaVM *vm) {
    g_vm = vm;
}

JavaVM *BNative::javaVM() {
    return g_vm;
}

JNIEnv *BNative::env() {
    if (g_vm == nullptr) {
        return nullptr;
    }
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return nullptr;
        }
    }
    return env;
}

bool BNative::ensureBound(JNIEnv *jni) {
    if (g_clazz != nullptr) {
        return true;
    }
    if (jni == nullptr) {
        return false;
    }
    bind(jni);
    return g_clazz != nullptr;
}

void BNative::bind(JNIEnv *jni) {
    if (jni == nullptr || g_clazz != nullptr) {
        return;
    }
    jclass local = jni->FindClass(kClass);
    if (local == nullptr) {
        jni->ExceptionClear();
        ALOGE("BNative: class not found");
        return;
    }
    g_clazz = reinterpret_cast<jclass>(jni->NewGlobalRef(local));
    jni->DeleteLocalRef(local);

    g_get_calling_uid = jni->GetStaticMethodID(g_clazz, "getCallingUid", "(I)I");
    g_redirect_path_string = jni->GetStaticMethodID(
            g_clazz, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    g_redirect_path_file = jni->GetStaticMethodID(
            g_clazz, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");
    g_on_spindle_log = jni->GetStaticMethodID(g_clazz, "onSpindleLog", "(Ljava/lang/String;)V");
    g_on_spindle_script = jni->GetStaticMethodID(g_clazz, "onSpindleScript", "([B)V");
    g_app_package_name = jni->GetStaticMethodID(g_clazz, "getAppPackageName", "()Ljava/lang/String;");
    g_xhs_dump_enabled = jni->GetStaticMethodID(g_clazz, "isXhsFingerprintDumpEnabled", "()Z");
    g_fingerprint_dump_dir = jni->GetStaticMethodID(
            g_clazz, "getFingerprintDumpDir", "()Ljava/lang/String;");
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
    }
}

int BNative::getCallingUid(int orig) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || g_get_calling_uid == nullptr) {
        return orig;
    }
    jint uid = jni->CallStaticIntMethod(g_clazz, g_get_calling_uid, orig);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return orig;
    }
    return uid;
}

jstring BNative::redirectPath(jstring path) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || g_redirect_path_string == nullptr || path == nullptr) {
        return path;
    }
    auto out = reinterpret_cast<jstring>(
            jni->CallStaticObjectMethod(g_clazz, g_redirect_path_string, path));
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return path;
    }
    return out;
}

jobject BNative::redirectPath(jobject file) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || g_redirect_path_file == nullptr || file == nullptr) {
        return file;
    }
    jobject out = jni->CallStaticObjectMethod(g_clazz, g_redirect_path_file, file);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return file;
    }
    return out;
}

std::string BNative::callString(const char *name) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || name == nullptr) {
        return {};
    }
    jmethodID mid = nullptr;
    if (strcmp(name, "getAppPackageName") == 0) {
        mid = g_app_package_name;
    } else if (strcmp(name, "getFingerprintDumpDir") == 0) {
        mid = g_fingerprint_dump_dir;
    } else {
        mid = jni->GetStaticMethodID(g_clazz, name, "()Ljava/lang/String;");
        if (jni->ExceptionCheck()) {
            jni->ExceptionClear();
            return {};
        }
    }
    if (mid == nullptr) {
        return {};
    }
    auto value = reinterpret_cast<jstring>(jni->CallStaticObjectMethod(g_clazz, mid));
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return {};
    }
    if (value == nullptr) {
        return {};
    }
    const char *chars = jni->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        jni->ReleaseStringUTFChars(value, chars);
    }
    jni->DeleteLocalRef(value);
    return out;
}

bool BNative::callBoolean(const char *name) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || name == nullptr) {
        return false;
    }
    jmethodID mid = nullptr;
    if (strcmp(name, "isXhsFingerprintDumpEnabled") == 0) {
        mid = g_xhs_dump_enabled;
    } else {
        mid = jni->GetStaticMethodID(g_clazz, name, "()Z");
        if (jni->ExceptionCheck()) {
            jni->ExceptionClear();
            return false;
        }
    }
    if (mid == nullptr) {
        return false;
    }
    jboolean value = jni->CallStaticBooleanMethod(g_clazz, mid);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
        return false;
    }
    return value == JNI_TRUE;
}

std::string BNative::appPackageName() {
    return callString("getAppPackageName");
}

bool BNative::isXhsFingerprintDumpEnabled() {
    return callBoolean("isXhsFingerprintDumpEnabled");
}

std::string BNative::fingerprintDumpDir() {
    return callString("getFingerprintDumpDir");
}

void BNative::onSpindleLog(const char *text) {
    if (text == nullptr) {
        return;
    }
    JNIEnv *jni = env();
    if (!ensureBound(jni) || g_on_spindle_log == nullptr) {
        return;
    }
    jstring line = jni->NewStringUTF(text);
    if (line == nullptr) {
        return;
    }
    jni->CallStaticVoidMethod(g_clazz, g_on_spindle_log, line);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
    }
    jni->DeleteLocalRef(line);
}

void BNative::onSpindleScript(const void *data, int len) {
    JNIEnv *jni = env();
    if (!ensureBound(jni) || g_on_spindle_script == nullptr || data == nullptr || len < 0) {
        return;
    }
    jbyteArray bytes = jni->NewByteArray(len);
    if (bytes == nullptr) {
        return;
    }
    if (len > 0) {
        jni->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(data));
    }
    jni->CallStaticVoidMethod(g_clazz, g_on_spindle_script, bytes);
    if (jni->ExceptionCheck()) {
        jni->ExceptionClear();
    }
    jni->DeleteLocalRef(bytes);
}
