package com.pixeltrigger.app.input;

interface IShoulderInputService {
    int getBackendUid() = 1;
    int initBackend() = 2;
    String getStatus() = 3;
    int fireKey(int linuxKeyCode, int durationMs) = 4;
    int releaseAll() = 5;
    int pressKey(int linuxKeyCode) = 6;
    int releaseKeyNow(int linuxKeyCode) = 7;
    void destroy() = 16777114;
}
