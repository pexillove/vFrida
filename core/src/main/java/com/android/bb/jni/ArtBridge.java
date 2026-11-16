package com.android.bb.jni;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


public final class ArtBridge {
    public static final int PROBE_ART_FIELD = 0;

    public static final int PROBE_ART_FIELD_2 = 0;

    public static final native void probeArtMethod();

    public static native void probeArtMethod2();

    public static native void setAccessible(Class<?> clazz, Method method);

    public static native void setAccessible(Class<?> clazz, Field field);
}
