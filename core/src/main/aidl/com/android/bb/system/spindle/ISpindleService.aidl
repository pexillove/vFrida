package com.android.bb.system.spindle;

import com.android.bb.system.spindle.ISpindleClient;

interface ISpindleService {
    boolean register(String name, ISpindleClient client);
    void emitLog(String text);
}
