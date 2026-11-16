#include "jni_hooks.h"

#include "core.h"
#include "log.h"
#include "redirect.h"

#include <jni.h>
#include <sys/stat.h>

#include <cstdint>
#include <cstring>
#include <string>

#ifndef __ANDROID_API_Q__
#define __ANDROID_API_Q__ 29
#endif
#ifndef __ANDROID_API_P__
#define __ANDROID_API_P__ 28
#endif

static constexpr uint32_t kAccPublic = 0x0001;
static constexpr uint32_t kAccStatic = 0x0008;
static constexpr uint32_t kAccFinal = 0x0010;
static constexpr uint32_t kAccNative = 0x0100;
static constexpr uint32_t kAccPublicApi = 0x10000000;
static constexpr uint32_t kAccFastNative = 0x00080000;
static constexpr uint32_t kAccNterpInvokeFastPathFlag = 0x00200000;

#define HOOK_JNI(ret, func, ...) \
    ret (*orig_##func)(__VA_ARGS__); \
    ret new_##func(__VA_ARGS__)

// --- trampolines ---

static void setDexFileReadonly(const char *filePath) {
    struct stat st{};
    if (stat(filePath, &st) != 0) {
        return;
    }
    if (chmod(filePath, S_IRUSR) != 0) {
        ALOGD("jni_hooks: dex readonly failed %s", filePath);
    }
}

HOOK_JNI(jstring, canonicalize0, JNIEnv *env, jobject obj, jstring path) {
    return orig_canonicalize0(env, obj, redirect::rewrite(env, path));
}

HOOK_JNI(jlong, getLastModifiedTime0, JNIEnv *env, jobject obj, jobject path) {
    return orig_getLastModifiedTime0(env, obj, redirect::rewrite(env, path));
}

HOOK_JNI(jboolean, setPermission0, JNIEnv *env, jobject obj, jobject file, jint access,
         jboolean enable, jboolean owneronly) {
    return orig_setPermission0(env, obj, redirect::rewrite(env, file), access, enable, owneronly);
}

HOOK_JNI(jboolean, createFileExclusively0, JNIEnv *env, jobject obj, jstring path) {
    return orig_createFileExclusively0(env, obj, redirect::rewrite(env, path));
}

HOOK_JNI(jobjectArray, list0, JNIEnv *env, jobject obj, jobject file) {
    return orig_list0(env, obj, redirect::rewrite(env, file));
}

HOOK_JNI(jboolean, createDirectory0, JNIEnv *env, jobject obj, jobject path) {
    return orig_createDirectory0(env, obj, redirect::rewrite(env, path));
}

HOOK_JNI(jboolean, setLastModifiedTime0, JNIEnv *env, jobject obj, jobject file, jobject time) {
    return orig_setLastModifiedTime0(env, obj, redirect::rewrite(env, file), time);
}

HOOK_JNI(jboolean, setReadOnly0, JNIEnv *env, jobject obj, jobject file) {
    return orig_setReadOnly0(env, obj, redirect::rewrite(env, file));
}

HOOK_JNI(jboolean, getSpace0, JNIEnv *env, jobject obj, jobject file, jint t) {
    return orig_getSpace0(env, obj, redirect::rewrite(env, file), t);
}

HOOK_JNI(jint, getCallingUid, JNIEnv *env, jobject obj) {
    return core::getCallingUid(env, orig_getCallingUid(env, obj));
}

HOOK_JNI(jobject, openDexFileNative, JNIEnv *env, jobject obj, jstring sourceName, jstring outputName,
         jint flags, jobject loader, jobject elements) {
    const char *source = env->GetStringUTFChars(sourceName, nullptr);
    if (source != nullptr) {
        if (strstr(source, "/v0/") != nullptr || strstr(source, "/blackbox/") != nullptr) {
            setDexFileReadonly(source);
        }
        env->ReleaseStringUTFChars(sourceName, source);
    }
    return orig_openDexFileNative(env, obj, sourceName, outputName, flags, loader, elements);
}

HOOK_JNI(jstring, nativeLoad, JNIEnv *env, jobject obj, jstring name, jobject class_loader) {
    return orig_nativeLoad(env, obj, name, class_loader);
}

HOOK_JNI(jstring, nativeLoad2, JNIEnv *env, jobject obj, jstring name, jobject class_loader,
         jobject caller) {
    return orig_nativeLoad2(env, obj, name, class_loader, caller);
}

namespace jni_hooks {

struct Env {
    bool ready = false;
    int api_level = 0;
    unsigned art_method_size = 0;
    unsigned art_field_size = 0;
    int art_method_flags_offset = 0;
    int art_method_native_offset = 0;
    int art_field_flags_offset = 0;

    jclass method_utils = nullptr;
    jmethodID get_method_desc = nullptr;
    jmethodID get_method_declaring_class = nullptr;
    jmethodID get_method_name = nullptr;
};

static Env g;

static uint32_t methodFlags(const char *art_method) {
    return *reinterpret_cast<const uint32_t *>(art_method + g.art_method_flags_offset);
}

static void setMethodFlags(char *art_method, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_method + g.art_method_flags_offset) = flags;
}

static bool addMethodFlag(char *art_method, uint32_t flag) {
    uint32_t old = methodFlags(art_method);
    uint32_t next = old | flag;
    if (next == old) {
        return false;
    }
    setMethodFlags(art_method, next);
    return true;
}

static bool clearMethodFlag(char *art_method, uint32_t flag) {
    uint32_t old = methodFlags(art_method);
    uint32_t next = old & ~flag;
    if (next == old) {
        return false;
    }
    setMethodFlags(art_method, next);
    return true;
}

static uint32_t fieldFlags(const char *art_field) {
    return *reinterpret_cast<const uint32_t *>(art_field + g.art_field_flags_offset);
}

static void setFieldFlags(char *art_field, uint32_t flags) {
    *reinterpret_cast<uint32_t *>(art_field + g.art_field_flags_offset) = flags;
}

static bool addFieldFlag(char *art_field, uint32_t flag) {
    uint32_t old = fieldFlags(art_field);
    uint32_t next = old | flag;
    if (next == old) {
        return false;
    }
    setFieldFlags(art_field, next);
    return true;
}

static bool clearFieldFlag(char *art_field, uint32_t flag) {
    uint32_t old = fieldFlags(art_field);
    uint32_t next = old & ~flag;
    if (next == old) {
        return false;
    }
    setFieldFlags(art_field, next);
    return true;
}

static void *artMethodOf(JNIEnv *env, jclass clazz, jmethodID methodId) {
    if (g.api_level >= __ANDROID_API_Q__) {
        jclass executable = env->FindClass("java/lang/reflect/Executable");
        jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
        jobject method = env->ToReflectedMethod(clazz, methodId, JNI_TRUE);
        void *art = reinterpret_cast<void *>(env->GetLongField(method, artId));
        env->DeleteLocalRef(method);
        env->DeleteLocalRef(executable);
        return art;
    }
    return methodId;
}

static void *artFieldOf(JNIEnv *env, jobject field) {
    if (g.api_level >= __ANDROID_API_Q__) {
        jclass fieldClass = env->FindClass("java/lang/reflect/Field");
        jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
        void *art = reinterpret_cast<void *>(env->CallLongMethod(field, getArtField));
        env->DeleteLocalRef(fieldClass);
        return art;
    }
    return env->FromReflectedField(field);
}

static bool prepareNativeEntry(void *artMethod) {
    auto *method = static_cast<char *>(artMethod);
    if ((methodFlags(method) & kAccNative) != kAccNative) {
        return false;
    }
    if (g.api_level < __ANDROID_API_P__) {
        clearMethodFlag(method, kAccFastNative);
    }
    return true;
}

static std::string methodUtilsString(JNIEnv *env, jmethodID mid, jobject javaMethod) {
    auto js = reinterpret_cast<jstring>(
            env->CallStaticObjectMethod(g.method_utils, mid, javaMethod));
    if (js == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(js, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(js, chars);
    }
    env->DeleteLocalRef(js);
    return out;
}

void hook(JNIEnv *env, const char *class_name, const char *method_name, const char *sign,
          void *new_fun, void **orig_fun, bool is_static) {
    if (!g.ready || class_name == nullptr || method_name == nullptr
            || sign == nullptr || new_fun == nullptr || orig_fun == nullptr) {
        return;
    }

    jclass clazz = env->FindClass(class_name);
    if (clazz == nullptr) {
        env->ExceptionClear();
        ALOGD("jni_hooks: FindClass fail %s.%s", class_name, method_name);
        return;
    }

    jmethodID method = is_static
            ? env->GetStaticMethodID(clazz, method_name, sign)
            : env->GetMethodID(clazz, method_name, sign);
    if (method == nullptr) {
        env->ExceptionClear();
        ALOGD("jni_hooks: GetMethodID fail %s.%s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }

    auto *artMethod = reinterpret_cast<uintptr_t *>(artMethodOf(env, clazz, method));
    if (!prepareNativeEntry(artMethod)) {
        ALOGD("jni_hooks: skip non-native %s.%s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }

    *orig_fun = reinterpret_cast<void *>(artMethod[g.art_method_native_offset]);
    JNINativeMethod natives[] = {{method_name, sign, new_fun}};
    if (env->RegisterNatives(clazz, natives, 1) < 0) {
        ALOGE("jni_hooks: RegisterNatives fail %s.%s", class_name, method_name);
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        return;
    }

    if (g.api_level == __ANDROID_API_O__ || g.api_level == __ANDROID_API_O_MR1__) {
        addMethodFlag(reinterpret_cast<char *>(artMethod), kAccFastNative);
    }
    env->DeleteLocalRef(clazz);
}

void hook(JNIEnv *env, jobject java_method, void *new_fun, void **orig_fun, bool is_static) {
    if (g.method_utils == nullptr || java_method == nullptr) {
        return;
    }
    std::string class_name = methodUtilsString(env, g.get_method_declaring_class, java_method);
    std::string method_name = methodUtilsString(env, g.get_method_name, java_method);
    std::string sign = methodUtilsString(env, g.get_method_desc, java_method);
    hook(env, class_name.c_str(), method_name.c_str(), sign.c_str(), new_fun, orig_fun, is_static);
}

__attribute__((section(".mytext"))) JNICALL void native_offset(JNIEnv *, jclass) {}

__attribute__((section(".mytext"))) JNICALL void native_offset2(JNIEnv *, jclass) {}

__attribute__((section(".mytext"))) JNICALL void set_method_accessible(
        JNIEnv *env, jclass, jclass clazz, jobject method) {
    jmethodID methodId = env->FromReflectedMethod(method);
    char *art_method = static_cast<char *>(artMethodOf(env, clazz, methodId));
    addMethodFlag(art_method, kAccPublic);
    if (g.api_level >= __ANDROID_API_Q__) {
        addMethodFlag(art_method, kAccPublicApi);
    }
}

__attribute__((section(".mytext"))) JNICALL void set_field_accessible(
        JNIEnv *env, jclass, jclass, jobject field) {
    char *art_field = static_cast<char *>(artFieldOf(env, field));
    addFieldFlag(art_field, kAccPublic);
    if (g.api_level >= __ANDROID_API_Q__) {
        addFieldFlag(art_field, kAccPublicApi);
    }
    clearFieldFlag(art_field, kAccFinal);
}

static bool registerHelpers(JNIEnv *env) {
    jclass clazz = env->FindClass("com/android/bb/jni/ArtBridge");
    if (clazz == nullptr) {
        env->ExceptionClear();
        ALOGE("jni_hooks: ArtBridge class missing");
        return false;
    }
    JNINativeMethod natives[] = {
            {"probeArtMethod", "()V", (void *) native_offset},
            {"probeArtMethod2", "()V", (void *) native_offset2},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V",
             (void *) set_method_accessible},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",
             (void *) set_field_accessible},
    };
    bool ok = env->RegisterNatives(clazz, natives,
                                   sizeof(natives) / sizeof(natives[0])) >= 0;
    if (!ok) {
        ALOGE("jni_hooks: helper RegisterNatives failed");
        env->ExceptionClear();
    }
    env->DeleteLocalRef(clazz);
    return ok;
}

static bool findFlagsOffset(char *start, unsigned size, uint32_t expect, int *out_offset) {
    for (unsigned i = 1; i < size; ++i) {
        auto value = *reinterpret_cast<uint32_t *>(start + i * sizeof(uint32_t));
        if (value == expect) {
            *out_offset = static_cast<int>(i * sizeof(uint32_t));
            return true;
        }
    }
    return false;
}

void setup(JNIEnv *env, int api_level) {
    g.ready = false;
    if (!registerHelpers(env)) {
        return;
    }
    g.api_level = api_level;

    jclass clazz = env->FindClass("com/android/bb/jni/ArtBridge");
    if (clazz == nullptr) {
        env->ExceptionClear();
        return;
    }

    jmethodID nativeOffsetId = env->GetStaticMethodID(clazz, "probeArtMethod", "()V");
    jmethodID nativeOffset2Id = env->GetStaticMethodID(clazz, "probeArtMethod2", "()V");
    jfieldID nativeOffsetFieldId = env->GetStaticFieldID(clazz, "PROBE_ART_FIELD", "I");
    jfieldID nativeOffsetField2Id = env->GetStaticFieldID(clazz, "PROBE_ART_FIELD_2", "I");
    if (nativeOffsetId == nullptr || nativeOffset2Id == nullptr
            || nativeOffsetFieldId == nullptr || nativeOffsetField2Id == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        ALOGE("jni_hooks: probe members missing");
        return;
    }

    jobject field1 = env->ToReflectedField(clazz, nativeOffsetFieldId, JNI_TRUE);
    jobject field2 = env->ToReflectedField(clazz, nativeOffsetField2Id, JNI_TRUE);
    void *nativeOffsetField = artFieldOf(env, field1);
    void *nativeOffsetField2 = artFieldOf(env, field2);
    env->DeleteLocalRef(field1);
    env->DeleteLocalRef(field2);
    g.art_field_size = static_cast<unsigned>(
            reinterpret_cast<uintptr_t>(nativeOffsetField2)
                    - reinterpret_cast<uintptr_t>(nativeOffsetField));

    void *nativeOffset = artMethodOf(env, clazz, nativeOffsetId);
    void *nativeOffset2 = artMethodOf(env, clazz, nativeOffset2Id);
    g.art_method_size = static_cast<unsigned>(
            reinterpret_cast<uintptr_t>(nativeOffset2)
                    - reinterpret_cast<uintptr_t>(nativeOffset));

    auto *artMethod = reinterpret_cast<uintptr_t *>(nativeOffset);
    bool found_native = false;
    for (unsigned i = 0; i < g.art_method_size; ++i) {
        if (reinterpret_cast<void *>(artMethod[i]) == reinterpret_cast<void *>(native_offset)) {
            g.art_method_native_offset = static_cast<int>(i);
            found_native = true;
            break;
        }
    }
    if (!found_native) {
        ALOGE("jni_hooks: art_method_native_offset not found");
        env->DeleteLocalRef(clazz);
        return;
    }

    uint32_t method_flags = kAccPublic | kAccStatic | kAccNative | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        method_flags |= kAccPublicApi;
    }
    if (api_level >= __ANDROID_API_S__) {
        method_flags |= kAccNterpInvokeFastPathFlag;
    }
    if (!findFlagsOffset(reinterpret_cast<char *>(artMethod), g.art_method_size, method_flags,
                         &g.art_method_flags_offset)) {
        ALOGE("jni_hooks: art_method_flags_offset not found");
        env->DeleteLocalRef(clazz);
        return;
    }

    uint32_t field_flags = kAccPublic | kAccStatic | kAccFinal;
    if (api_level >= __ANDROID_API_Q__) {
        field_flags |= kAccPublicApi;
    }
    if (!findFlagsOffset(reinterpret_cast<char *>(nativeOffsetField), g.art_field_size, field_flags,
                         &g.art_field_flags_offset)) {
        ALOGE("jni_hooks: art_field_flags_offset not found");
        env->DeleteLocalRef(clazz);
        return;
    }

    jclass utils = env->FindClass("com/android/bb/jni/MethodUtils");
    if (utils == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(clazz);
        ALOGE("jni_hooks: MethodUtils missing");
        return;
    }
    g.method_utils = reinterpret_cast<jclass>(env->NewGlobalRef(utils));
    env->DeleteLocalRef(utils);
    g.get_method_desc = env->GetStaticMethodID(
            g.method_utils, "getDesc", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    g.get_method_declaring_class = env->GetStaticMethodID(
            g.method_utils, "getDeclaringClass", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    g.get_method_name = env->GetStaticMethodID(
            g.method_utils, "getMethodName", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    env->DeleteLocalRef(clazz);
    g.ready = true;
}

static void tryHook(JNIEnv *env, const char *clazz, const char *name, const char *sign,
                    void *neu, void **orig, bool is_static) {
    try {
        hook(env, clazz, name, sign, neu, orig, is_static);
    } catch (...) {
        ALOGD("jni_hooks: failed %s.%s", clazz, name);
    }
}

void init(JNIEnv *env) {
    static const struct {
        const char *name;
        const char *sign;
        void *neu;
        void **orig;
    } kUnixFs[] = {
            {"canonicalize0", "(Ljava/lang/String;)Ljava/lang/String;",
             (void *) new_canonicalize0, (void **) &orig_canonicalize0},
            {"getLastModifiedTime0", "(Ljava/io/File;)J",
             (void *) new_getLastModifiedTime0, (void **) &orig_getLastModifiedTime0},
            {"setPermission0", "(Ljava/io/File;IZZ)Z",
             (void *) new_setPermission0, (void **) &orig_setPermission0},
            {"createFileExclusively0", "(Ljava/lang/String;)Z",
             (void *) new_createFileExclusively0, (void **) &orig_createFileExclusively0},
            {"list0", "(Ljava/io/File;)[Ljava/lang/String;",
             (void *) new_list0, (void **) &orig_list0},
            {"createDirectory0", "(Ljava/io/File;)Z",
             (void *) new_createDirectory0, (void **) &orig_createDirectory0},
            {"setLastModifiedTime0", "(Ljava/io/File;J)Z",
             (void *) new_setLastModifiedTime0, (void **) &orig_setLastModifiedTime0},
            {"setReadOnly0", "(Ljava/io/File;)Z",
             (void *) new_setReadOnly0, (void **) &orig_setReadOnly0},
            {"getSpace0", "(Ljava/io/File;I)J",
             (void *) new_getSpace0, (void **) &orig_getSpace0},
    };

    for (const auto &item : kUnixFs) {
        tryHook(env, "java/io/UnixFileSystem", item.name, item.sign, item.neu, item.orig, false);
    }

    tryHook(env, "android/os/Binder", "getCallingUid", "()I",
            (void *) new_getCallingUid, (void **) &orig_getCallingUid, true);

    if (core::getApiLevel() >= __ANDROID_API_U__) {
        tryHook(env, "dalvik/system/DexFile", "openDexFileNative",
                "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                (void *) new_openDexFileNative, (void **) &orig_openDexFileNative, true);
    }

    if (core::getApiLevel() >= __ANDROID_API_Q__) {
        tryHook(env, "java/lang/Runtime", "nativeLoad",
                "(Ljava/lang/String;Ljava/lang/ClassLoader;Ljava/lang/Class;)Ljava/lang/String;",
                (void *) new_nativeLoad2, (void **) &orig_nativeLoad2, true);
    } else {
        tryHook(env, "java/lang/Runtime", "nativeLoad",
                "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/String;",
                (void *) new_nativeLoad, (void **) &orig_nativeLoad, true);
    }
}

}  // namespace jni_hooks
