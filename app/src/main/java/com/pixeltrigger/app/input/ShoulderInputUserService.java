package com.pixeltrigger.app.input;

import android.os.Process;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService backend for the V5 shoulder half.
 * Runs only under ADB shell UID 2000 and owns the persistent uinput devices.
 */
public final class ShoulderInputUserService extends IShoulderInputService.Stub {
    public static final int SHELL_UID = 2000;
    public static final int KEY_F7 = 65; // physical RedMagic R source
    public static final int KEY_F8 = 66; // physical RedMagic L source
    public static final int FLASH_MS = 30;

    static {
        System.loadLibrary("pixeltrigger_shoulder");
    }

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);
    private boolean f7Down;
    private boolean f8Down;
    private volatile String status = "UserService uid=" + Process.myUid();

    @Override
    public int getBackendUid() {
        return Process.myUid();
    }

    @Override
    public int initBackend() {
        if (Process.myUid() != SHELL_UID) {
            status = "Rejected: expected shell uid 2000, got " + Process.myUid();
            return -100;
        }
        final int rc = nativeInit();
        status = nativeStatus();
        return rc;
    }

    @Override
    public String getStatus() {
        return status + " | native=" + nativeStatus();
    }

    @Override
    public void fireKey(int linuxKeyCode, int durationMs) {
        if (Process.myUid() != SHELL_UID) {
            status = "Rejected fire: uid=" + Process.myUid();
            return;
        }
        if (linuxKeyCode != KEY_F7 && linuxKeyCode != KEY_F8) {
            status = "Unsupported key=" + linuxKeyCode;
            return;
        }

        final int requested = durationMs <= 0 ? FLASH_MS : Math.max(1000, Math.min(durationMs, 5000));
        synchronized (lock) {
            if (isDown(linuxKeyCode)) {
                status = "Ignored duplicate while key is already down: " + linuxKeyCode;
                return;
            }
            final int rc = nativeKeyDown(linuxKeyCode);
            if (rc != 0) {
                status = nativeStatus();
                return;
            }
            setDown(linuxKeyCode, true);
            status = "DOWN key=" + linuxKeyCode + " durationMs=" + requested;
        }

        scheduler.schedule(() -> releaseKey(linuxKeyCode), requested, TimeUnit.MILLISECONDS);
    }

    private void releaseKey(int linuxKeyCode) {
        synchronized (lock) {
            if (!isDown(linuxKeyCode)) return;
            final int rc = nativeKeyUp(linuxKeyCode);
            setDown(linuxKeyCode, false);
            status = rc == 0 ? "UP key=" + linuxKeyCode : nativeStatus();
        }
    }

    private boolean isDown(int key) {
        return key == KEY_F7 ? f7Down : f8Down;
    }

    private void setDown(int key, boolean value) {
        if (key == KEY_F7) f7Down = value;
        else f8Down = value;
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            if (f7Down) nativeKeyUp(KEY_F7);
            if (f8Down) nativeKeyUp(KEY_F8);
            f7Down = false;
            f8Down = false;
            nativeDestroy();
        }
        scheduler.shutdownNow();
        System.exit(0);
    }

    private static native int nativeInit();
    private static native int nativeKeyDown(int linuxKeyCode);
    private static native int nativeKeyUp(int linuxKeyCode);
    private static native String nativeStatus();
    private static native void nativeDestroy();
}
