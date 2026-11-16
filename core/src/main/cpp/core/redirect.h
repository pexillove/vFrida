#ifndef BLACKBOX_REDIRECT_H
#define BLACKBOX_REDIRECT_H

#include <jni.h>

namespace redirect {

struct Rule {
    const char *from;
    const char *to;
};

void init(JNIEnv *env);

void add(const char *from, const char *to);

jstring rewrite(JNIEnv *env, jstring value);

jobject rewrite(JNIEnv *env, jobject file);

const char *rewrite(const char *value);

}  // namespace redirect

#endif
