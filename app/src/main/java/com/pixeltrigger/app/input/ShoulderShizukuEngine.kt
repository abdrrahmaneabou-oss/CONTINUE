package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku

/** App-side bridge for the independent V5 shoulder half. */
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

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShoulderInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_shoulder")
        .daemon(true)
        .tag("pixeltrigger-v5-shoulder-uinput")
        // Version 1 can remain alive inside Shizuku after an APK update. Bumping
        // this forces replacement with the repaired synchronous-result backend.
        .version(2)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
            ready = rc == 0
            status = runCatching { backend.status }.getOrDefault("init rc=$rc")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            remote = null
            ready = false
            status = "Shoulder UserService disconnected"
            lastFireResult = FIRE_NOT_CONNECTED
            if (!closed) mainHandler.post { connect() }
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

    private fun fire(key: Int, durationMs: Int): Boolean {
        val backend = remote ?: run {
            ready = false
            lastFireResult = FIRE_NOT_CONNECTED
            status = "Shoulder FIRE waiting for UserService reconnect"
            mainHandler.post { connect() }
            return false
        }
        return try {
            val rc = backend.fireKey(key, durationMs)
            lastFireResult = rc
            if (rc == ShoulderInputUserService.RESULT_OK) {
                ready = true
                true
            } else {
                ready = false
                status = runCatching { backend.status }.getOrDefault("Shoulder FIRE failed rc=$rc")
                false
            }
        } catch (failure: Throwable) {
            remote = null
            ready = false
            lastFireResult = FIRE_BINDER_ERROR
            status = "Shoulder FIRE binder error: ${failure.message ?: failure.javaClass.simpleName}"
            mainHandler.post { connect() }
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

    fun disconnect() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        ready = false
        binding = false
    }

    companion object {
        const val FIRE_NOT_CONNECTED = Int.MIN_VALUE
        const val FIRE_BINDER_ERROR = Int.MIN_VALUE + 1
    }
}
