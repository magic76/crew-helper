package com.crewpocket.helper;

/** No arbitrary commands or arguments. Only start the fixed background helper. */
interface IBackgroundShizukuService {
    int shellUid();
    String startHelper();
    void destroy() = 16777114;
}
