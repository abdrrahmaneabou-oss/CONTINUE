package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** App-side bridge for the independent V5 shoulder half. */
class ShoulderShizukuEngine(private val context: Context) {
    @Volatile private var remote: IShoulderInputService? = null
    @Volatile private var ready = false
    @Volatile var status: String = "Shizuku shoulder backend disconnected"
        private set

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShoulderInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_shoulder")
        .daemon(true)
        .tag("pixeltrigger-v5-shoulder-uinput")
        .version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
            remote = null
            ready = false
            status = "Shoulder UserService disconnected"
        }
    }

    fun connect(): Boolean {
        if (!Shizuku.pingBinder()) {
            ready = false
            status = "Start Shizuku with Wireless debugging/ADB"
            return false
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != ShoulderInputUserService.SHELL_UID) {
            ready = false
            status = "Root/non-shell rejected: Shizuku uid=$uid"
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ready = false
            status = "Shizuku permission required"
            return false
        }
        return runCatching {
            Shizuku.bindUserService(args, connection)
            true
        }.getOrElse {
            ready = false
            status = "Shoulder bind failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun isReady(): Boolean = ready && remote != null

    /** durationMs=0 means a fast 30 ms physical-style press. */
    fun fireR(durationMs: Int): Boolean = fire(ShoulderInputUserService.KEY_F7, durationMs)

    /** durationMs=0 means a fast 30 ms physical-style press. */
    fun fireL(durationMs: Int): Boolean = fire(ShoulderInputUserService.KEY_F8, durationMs)

    private fun fire(key: Int, durationMs: Int): Boolean {
        val backend = remote ?: return false
        if (!ready) return false
        return try {
            backend.fireKey(key, durationMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun refreshStatus(): String {
        val backend = remote ?: return status
        status = runCatching { backend.status }.getOrDefault(status)
        return status
    }

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        ready = false
    }
}
