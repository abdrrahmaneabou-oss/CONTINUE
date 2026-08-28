package com.pixeltrigger.app.input;

interface IShizukuInputService {
    int getBackendUid() = 1;
    int probeCapability() = 2;
    String getCapabilityDetail() = 3;
    oneway void injectTapFast(long triggerId, float x, float y, int displayId) = 4;
    long getLastDownNs() = 5;
    long getLastUpNs() = 6;
    String getLatencyDetail() = 7;
    void destroy() = 16777114;
}
