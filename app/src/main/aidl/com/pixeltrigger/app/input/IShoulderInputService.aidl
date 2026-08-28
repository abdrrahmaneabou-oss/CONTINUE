package com.pixeltrigger.app.input;

interface IShoulderInputService {
    int getBackendUid() = 1;
    int initBackend() = 2;
    String getStatus() = 3;
    int fireKey(int linuxKeyCode, int durationMs) = 4;
    int releaseAll() = 5;
    void destroy() = 16777114;
}
