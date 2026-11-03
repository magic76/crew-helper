package com.crewpocket.helper;

/** No arbitrary commands or arguments. Only start the fixed background helper. */
interface IBackgroundShizukuService {
    int shellUid() = 1;
    String startHelper() = 2;
    void destroy() = 16777114;
}
