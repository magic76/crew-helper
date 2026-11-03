package com.crewpocket.helper;

/** Narrow Shizuku Binder protocol. Preview bytes travel through an FD pipe. */
interface IBackgroundShizukuService {
    int shellUid() = 1;
    String startHelper() = 2;
    /** App-granted one-time nonce forwarded on the child's stdin. */
    String startHelperDirect(String nonce) = 4;
    void destroy() = 16777114;
}
