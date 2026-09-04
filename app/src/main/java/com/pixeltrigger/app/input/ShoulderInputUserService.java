package com.pixeltrigger.app.input;

import android.os.Process;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku UserService backend for the shoulder half.
 * Runs only under ADB shell UID 2000 and owns the persistent uinput devices.
 */
public final class ShoulderInputUserService extends IShoulderInputService.Stub {
    public static final int SHELL_UID = 2000;
    public static final int KEY_F7 = 65; // physical RedMagic R source
    public static final int KEY_F8 = 66; // physical RedMagic L source
    public static final int FLASH_MS = 70;
    public static final int RESULT_OK = 0;
    public static final int ERROR_BAD_UID = -100;
    public static final int ERROR_BAD_KEY = -101;
    public static final int ERROR_RELEASE_SCHEDULER = -102;

    static {
        System.loadLibrary("pixeltrigger_shoulder");
    }

    private final Object lock = new Object();
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private boolean f7Down;
    private boolean f8Down;
    private int f7Generation;
    private int f8Generation;
    private volatile String status = "UserService uid=" + Process.myUid();

    public ShoulderInputUserService() {
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @Override
    public int getBackendUid() {
        return Process.myUid();
    }

    @Override
    public int initBackend() {
        if (Process.myUid() != SHELL_UID) {
            status = "Rejected: expected shell uid 2000, got " + Process.myUid();
            return ERROR_BAD_UID;
        }
        synchronized (lock) {
            final int rc = nativeInit();
            status = nativeStatus();
            return rc;
        }
    }

    @Override
    public String getStatus() {
        return status + " | native=" + nativeStatus();
    }

    private int validateKey(int linuxKeyCode) {
        if (Process.myUid() != SHELL_UID) {
            status = "Rejected key operation: uid=" + Process.myUid();
            return ERROR_BAD_UID;
        }
        if (linuxKeyCode != KEY_F7 && linuxKeyCode != KEY_F8) {
            status = "Unsupported key=" + linuxKeyCode;
            return ERROR_BAD_KEY;
        }
        return RESULT_OK;
    }

    private void boostPriorityBestEffort() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable ignored) {
            // Best effort only.
        }
    }

    @Override
    public int fireKey(int linuxKeyCode, int durationMs) {
        boostPriorityBestEffort();
        final int validation = validateKey(linuxKeyCode);
        if (validation != RESULT_OK) return validation;

        final int requested = durationMs <= 0 ? FLASH_MS : Math.max(1000, Math.min(durationMs, 5000));
        final int generation;
        synchronized (lock) {
            // A fast rearm can legitimately produce another FIRE before the prior
            // 70 ms release. End the old contact and start the new one; never drop it.
            if (isDown(linuxKeyCode)) {
                final int upRc = nativeKeyUp(linuxKeyCode);
                setDown(linuxKeyCode, false);
                if (upRc != RESULT_OK) {
                    invalidateAllPressesLocked();
                }
            }

            int rc = nativeKeyDown(linuxKeyCode);
            if (rc != RESULT_OK) {
                invalidateAllPressesLocked();
                final int resetRc = nativeResetBackend();
                if (resetRc == RESULT_OK) rc = nativeKeyDown(linuxKeyCode);
                else rc = resetRc;
            }
            if (rc != RESULT_OK) {
                status = nativeStatus();
                return rc;
            }
            setDown(linuxKeyCode, true);
            generation = nextGeneration(linuxKeyCode);
            status = "DOWN key=" + linuxKeyCode + " durationMs=" + requested;
        }

        try {
            scheduler.schedule(() -> releaseKey(linuxKeyCode, generation), requested, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            releaseKey(linuxKeyCode, generation);
            status = "Release scheduler rejected key=" + linuxKeyCode + ": " + failure.getClass().getSimpleName();
            return ERROR_RELEASE_SCHEDULER;
        }
        return RESULT_OK;
    }

    /**
     * V6 continuous press. If a timed press is already down, keep the same
     * physical contact but advance its generation so the old scheduled UP can
     * no longer release the user's held finger press.
     */
    @Override
    public int pressKey(int linuxKeyCode) {
        boostPriorityBestEffort();
        final int validation = validateKey(linuxKeyCode);
        if (validation != RESULT_OK) return validation;

        synchronized (lock) {
            nextGeneration(linuxKeyCode);
            if (isDown(linuxKeyCode)) {
                status = "HELD key=" + linuxKeyCode + " (continued existing DOWN)";
                return RESULT_OK;
            }

            int rc = nativeKeyDown(linuxKeyCode);
            if (rc != RESULT_OK) {
                invalidateAllPressesLocked();
                final int resetRc = nativeResetBackend();
                if (resetRc == RESULT_OK) rc = nativeKeyDown(linuxKeyCode);
                else rc = resetRc;
            }
            if (rc != RESULT_OK) {
                status = nativeStatus();
                return rc;
            }
            setDown(linuxKeyCode, true);
            status = "HELD DOWN key=" + linuxKeyCode;
            return RESULT_OK;
        }
    }

    /** Immediate UP used when the user's finger leaves the V6 knob. */
    @Override
    public int releaseKeyNow(int linuxKeyCode) {
        boostPriorityBestEffort();
        final int validation = validateKey(linuxKeyCode);
        if (validation != RESULT_OK) return validation;

        synchronized (lock) {
            // Invalidate any old timed release first.
            nextGeneration(linuxKeyCode);
            if (!isDown(linuxKeyCode)) {
                status = "UP key=" + linuxKeyCode + " (already released)";
                return RESULT_OK;
            }

            final int rc = nativeKeyUp(linuxKeyCode);
            setDown(linuxKeyCode, false);
            if (rc == RESULT_OK) {
                status = "UP key=" + linuxKeyCode;
                return RESULT_OK;
            }

            // If UP itself failed, recreating the uinput devices is the safest
            // way to guarantee no logical shoulder remains stuck down.
            invalidateAllPressesLocked();
            final int resetRc = nativeResetBackend();
            status = resetRc == RESULT_OK ? "UP recovered by backend reset key=" + linuxKeyCode : nativeStatus();
            return resetRc;
        }
    }

    private void releaseKey(int linuxKeyCode, int generation) {
        synchronized (lock) {
            if (currentGeneration(linuxKeyCode) != generation) return;
            if (!isDown(linuxKeyCode)) return;
            final int rc = nativeKeyUp(linuxKeyCode);
            setDown(linuxKeyCode, false);
            status = rc == RESULT_OK ? "UP key=" + linuxKeyCode : nativeStatus();
        }
    }

    private boolean isDown(int key) {
        return key == KEY_F7 ? f7Down : f8Down;
    }

    private void setDown(int key, boolean value) {
        if (key == KEY_F7) f7Down = value;
        else f8Down = value;
    }

    private int nextGeneration(int key) {
        if (key == KEY_F7) return ++f7Generation;
        return ++f8Generation;
    }

    private int currentGeneration(int key) {
        return key == KEY_F7 ? f7Generation : f8Generation;
    }

    private void invalidateAllPressesLocked() {
        f7Generation++;
        f8Generation++;
        f7Down = false;
        f8Down = false;
    }

    @Override
    public int releaseAll() {
        synchronized (lock) {
            int result = RESULT_OK;
            if (f7Down) {
                final int rc = nativeKeyUp(KEY_F7);
                if (rc != RESULT_OK) result = rc;
            }
            if (f8Down) {
                final int rc = nativeKeyUp(KEY_F8);
                if (result == RESULT_OK && rc != RESULT_OK) result = rc;
            }
            invalidateAllPressesLocked();
            status = result == RESULT_OK ? "All shoulder keys released" : nativeStatus();
            return result;
        }
    }

    @Override
    public void destroy() {
        synchronized (lock) {
            releaseAll();
            nativeDestroy();
        }
        scheduler.shutdownNow();
        System.exit(0);
    }

    private static native int nativeInit();
    private static native int nativeKeyDown(int linuxKeyCode);
    private static native int nativeKeyUp(int linuxKeyCode);
    private static native int nativeResetBackend();
    private static native String nativeStatus();
    private static native void nativeDestroy();
}
