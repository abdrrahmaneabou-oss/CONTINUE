package com.pixeltrigger.app.input;

interface IShoulderInputService {
    int getBackendUid() = 1;
    int initBackend() = 2;
    String getStatus() = 3;
    oneway void fireKey(int linuxKeyCode, int durationMs) = 4;
    void destroy() = 16777114;
}
