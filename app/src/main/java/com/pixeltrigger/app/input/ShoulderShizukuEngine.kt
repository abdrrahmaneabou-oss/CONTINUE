package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/** App-side bridge for the shoulder uinput backend. */
class ShoulderShizukuEngine(private val context: Context) {
    @Volatile private var remote: IShoulderInputService? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var binding = false
    @Volatile var status: String = "Shizuku shoulder backend disconnected"
        private set
    @Volatile var lastFireResult: Int = FIRE_NOT_CONNECTED
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectRunnable = Runnable {
        reconnectScheduled.set(false)
        if (!closed) connect()
    }

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShoulderInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_shoulder")
        .daemon(true)
        .tag("pixeltrigger-v6-shoulder-uinput-held")
        // V6 adds explicit key DOWN/UP transactions. Force Shizuku to replace
        // every older V5 UserService instance that only understands timed fireKey.
        .version(3)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            reconnectScheduled.set(false)
            mainHandler.removeCallbacks(reconnectRunnable)
            binding = false
            remote = IShoulderInputService.Stub.asInterface(service)
            val backend = remote
            if (backend == null) {
                ready = false
                status = "Shoulder UserService binder unavailable"
                return
            }
            val uid = runCatching { backend.backendUid }.getOrDefault(-1)
            if (uid != ShoulderInputUserService.SHELL_UID) {
                ready = false
                status = "Shoulder backend rejected uid=$uid"
                return
            }
            val rc = runCatching { backend.initBackend() }.getOrDefault(-999)
            if (rc == ShoulderInputUserService.RESULT_OK) {
                // A reconnect must never inherit a stuck key from a lost client.
                runCatching { backend.releaseAll() }
            }
            ready = rc == ShoulderInputUserService.RESULT_OK
            status = runCatching { backend.status }.getOrDefault("init rc=$rc")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            remote = null
            ready = false
            status = "Shoulder UserService disconnected"
            lastFireResult = FIRE_NOT_CONNECTED
            scheduleReconnect()
        }
    }

    fun connect(): Boolean {
        closed = false
        if (remote != null || binding) return true
        if (!Shizuku.pingBinder()) {
            binding = false
            ready = false
            status = "Start Shizuku with Wireless debugging/ADB"
            return false
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != ShoulderInputUserService.SHELL_UID) {
            binding = false
            ready = false
            status = "Root/non-shell rejected: Shizuku uid=$uid"
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            binding = false
            ready = false
            status = "Shizuku permission required"
            return false
        }
        return runCatching {
            binding = true
            Shizuku.bindUserService(args, connection)
            true
        }.getOrElse {
            binding = false
            ready = false
            status = "Shoulder bind failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun isReady(): Boolean = ready && remote != null

    /** durationMs=0 means the proven 70 ms physical-style press. */
    fun fireR(durationMs: Int): Boolean = fire(ShoulderInputUserService.KEY_F7, durationMs)

    /** durationMs=0 means the proven 70 ms physical-style press. */
    fun fireL(durationMs: Int): Boolean = fire(ShoulderInputUserService.KEY_F8, durationMs)

    /** V6: keep the physical-style R source DOWN until releaseR(). */
    fun pressR(): Boolean = press(ShoulderInputUserService.KEY_F7)

    /** V6: keep the physical-style L source DOWN until releaseL(). */
    fun pressL(): Boolean = press(ShoulderInputUserService.KEY_F8)

    fun releaseR(): Boolean = release(ShoulderInputUserService.KEY_F7)

    fun releaseL(): Boolean = release(ShoulderInputUserService.KEY_F8)

    private fun fire(key: Int, durationMs: Int): Boolean {
        val backend = connectedBackend("FIRE") ?: return false
        return callBackend("FIRE") { backend.fireKey(key, durationMs) }
    }

    private fun press(key: Int): Boolean {
        val backend = connectedBackend("DOWN") ?: return false
        return callBackend("DOWN") { backend.pressKey(key) }
    }

    private fun release(key: Int): Boolean {
        val backend = connectedBackend("UP") ?: return false
        return callBackend("UP") { backend.releaseKeyNow(key) }
    }

    private fun connectedBackend(operation: String): IShoulderInputService? {
        val backend = remote
        if (backend == null) {
            ready = false
            lastFireResult = FIRE_NOT_CONNECTED
            status = "Shoulder $operation waiting for UserService reconnect"
            scheduleReconnect()
        }
        return backend
    }

    private inline fun callBackend(operation: String, block: () -> Int): Boolean {
        return try {
            val rc = block()
            lastFireResult = rc
            if (rc == ShoulderInputUserService.RESULT_OK) {
                ready = true
                true
            } else {
                ready = false
                status = runCatching { remote?.status }.getOrNull() ?: "Shoulder $operation failed rc=$rc"
                false
            }
        } catch (failure: Throwable) {
            remote = null
            ready = false
            lastFireResult = FIRE_BINDER_ERROR
            status = "Shoulder $operation binder error: ${failure.message ?: failure.javaClass.simpleName}"
            scheduleReconnect()
            false
        }
    }

    fun refreshStatus(): String {
        val backend = remote ?: return status
        status = runCatching { backend.status }.getOrDefault(status)
        return status
    }

    fun releaseAll(): Boolean {
        val backend = remote ?: return false
        return runCatching { backend.releaseAll() == ShoulderInputUserService.RESULT_OK }
            .getOrDefault(false)
    }

    private fun scheduleReconnect() {
        if (closed || !reconnectScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    fun disconnect() {
        closed = true
        reconnectScheduled.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        ready = false
        binding = false
    }

    companion object {
        const val FIRE_NOT_CONNECTED = Int.MIN_VALUE
        const val FIRE_BINDER_ERROR = Int.MIN_VALUE + 1
        private const val RECONNECT_DELAY_MS = 50L
    }
}
