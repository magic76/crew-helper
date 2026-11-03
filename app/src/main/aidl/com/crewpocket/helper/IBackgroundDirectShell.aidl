package com.crewpocket.helper;

import android.os.ParcelFileDescriptor;

/** App talks directly to the authenticated shell helper; no socket relay. */
interface IBackgroundDirectShell {
    int[] identity() = 1;
    ParcelFileDescriptor executeCommand(in String commandJson) = 2;
}
