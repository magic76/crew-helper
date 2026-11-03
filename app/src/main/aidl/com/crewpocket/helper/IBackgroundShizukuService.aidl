package com.crewpocket.helper;

import android.os.ParcelFileDescriptor;

/** Narrow Shizuku Binder protocol. Preview bytes travel through an FD pipe. */
interface IBackgroundShizukuService {
    int shellUid() = 1;
    String startHelper() = 2;
    ParcelFileDescriptor relay(in String requestJson) = 3;
    void destroy() = 16777114;
}
