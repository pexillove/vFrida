package com.android.bb.system.spindle;

interface ISpindleClient {
    byte[] onFrame(in byte[] frame);
    boolean launch(String packageName);
}
