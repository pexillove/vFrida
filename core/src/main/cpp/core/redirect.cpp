#include "redirect.h"
#include "b_native.h"
#include "log.h"

#include <cstring>
#include <cstdlib>
#include <list>

jmethodID getAbsolutePathMethodId;

static std::list<redirect::Rule> g_rules;

static char *replace(const char *str, const char *src, const char *dst) {
    const char *pos = str;
    int count = 0;
    while ((pos = strstr(pos, src))) {
        count++;
        pos += strlen(src);
    }

    size_t result_len = strlen(str) + (strlen(dst) - strlen(src)) * count + 1;
    char *result = (char *) malloc(result_len);
    memset(result, 0, strlen(result));

    const char *left = str;
    const char *right = nullptr;

    while ((right = strstr(left, src))) {
        strncat(result, left, right - left);
        strcat(result, dst);
        right += strlen(src);
        left = right;
    }
    strcat(result, left);
    return result;
}

namespace redirect {

const char *rewrite(const char *value) {
    if (strstr(value, "resource-cache")) {
        ALOGD("Blocking resource-cache path: %s", value);
        return "/dev/null";
    }

    if (strstr(value, "@idmap")) {
        ALOGD("Blocking idmap path: %s", value);
        return "/dev/null";
    }

    if (strstr(value, "systemui") && (strstr(value, ".frro") || strstr(value, "-accent-") || strstr(value, "-dynamic-") || strstr(value, "-neutral-"))) {
        ALOGD("Blocking systemui problematic path: %s", value);
        return "/dev/null";
    }

    if (strstr(value, "data@resource-cache@")) {
        ALOGD("Blocking data@resource-cache@ pattern: %s", value);
        return "/dev/null";
    }

    if (strstr(value, ".frro")) {
        ALOGD("Blocking .frro file: %s", value);
        return "/dev/null";
    }

    if (strstr(value, "systemui")) {
        ALOGD("Blocking systemui path: %s", value);
        return "/dev/null";
    }

    for (const Rule &rule : g_rules) {
        if (strstr(value, rule.from)
                && !strstr(value, "/v0/")
                && !strstr(value, "/blackbox/")) {
            return replace(value, rule.from, rule.to);
        }
    }
    return value;
}

jstring rewrite(JNIEnv *env, jstring value) {
    (void) env;
    return BNative::redirectPath(value);
}

jobject rewrite(JNIEnv *env, jobject file) {
    (void) env;
    return BNative::redirectPath(file);
}

void add(const char *from, const char *to) {
    Rule rule{};
    rule.from = strdup(from);
    rule.to = strdup(to);
    g_rules.push_back(rule);
}

void init(JNIEnv *env) {
    jclass tmpFile = env->FindClass("java/io/File");
    getAbsolutePathMethodId = env->GetMethodID(tmpFile, "getAbsolutePath", "()Ljava/lang/String;");
}

}  // namespace redirect
