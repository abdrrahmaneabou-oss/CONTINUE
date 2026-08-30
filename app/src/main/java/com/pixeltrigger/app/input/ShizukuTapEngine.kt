package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/**
 * Minimal app-side Shizuku tap bridge for the right-half detector.
 *
 * There is deliberately no pending FIRE queue, no one-way AIDL submission and no
 * second worker. A detector FIRE performs one synchronous Binder call and returns
 * only after Nubia has received DOWN and UP.
 */
class ShizukuTapEngine(private val context: Context) : TapEngine {
    override val name: String = "shizuku-redmagic-nubia-direct-confirmed"

    @Volatile private var remote: IShizukuInputService? = null
    @Volatile private var hotPathReady = false
    @Volatile private var closed = false
    @Volatile private var binding = false

    @Volatile var lastFireStatus: Int = ShizukuInputUserService.STATUS_NOT_READY
        private set
    @Volatile var capability: InputCapability = InputCapability.DISCONNECTED
        private set
    @Volatile var capabilityDetail: String = "Shizuku not connected"
        private set

    private var triggerId = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectScheduled = AtomicBoolean(false)
    private val reconnectRunnable = Runnable {
        reconnectScheduled.set(false)
        if (!closed) connect()
    }

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_input")
        .daemon(true)
        .tag("pixeltrigger-input-v11-direct-confirmed")
        // Force Shizuku to replace every older one-way/FIFO daemon.
        .version(14)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            reconnectScheduled.set(false)
            mainHandler.removeCallbacks(reconnectRunnable)
            binding = false
            remote = IShizukuInputService.Stub.asInterface(service)
            refreshCapability()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            remote = null
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "Shizuku UserService disconnected"
            lastFireStatus = ShizukuInputUserService.STATUS_NOT_READY
            scheduleReconnect()
        }
    }

    fun connect(): Boolean {
        closed = false
        if (remote != null || binding) return true

        if (!Shizuku.pingBinder()) {
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "Start Shizuku with Wireless debugging/ADB"
            return false
        }

        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != ShizukuInputUserService.SHELL_UID) {
            hotPathReady = false
            capability = InputCapability.ROOT_REJECTED
            capabilityDetail = "No-root policy: Shizuku must run as ADB shell UID 2000 (got $uid)"
            return false
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            hotPathReady = false
            capability = InputCapability.PERMISSION_REQUIRED
            capabilityDetail = "Shizuku permission required"
            return false
        }

        return runCatching {
            binding = true
            Shizuku.bindUserService(args, connection)
            true
        }.getOrElse {
            binding = false
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "bind failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    fun refreshCapability(): InputCapability {
        val service = remote ?: run {
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            return capability
        }

        val code = runCatching { service.probeCapability() }.getOrElse {
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "probe failed: ${it.message ?: it.javaClass.simpleName}"
            return capability
        }

        capabilityDetail = runCatching { service.capabilityDetail }.getOrDefault("status=$code")
        capability = when (code) {
            ShizukuInputUserService.STATUS_SAFE -> InputCapability.CONCURRENT_TOUCH_SAFE
            ShizukuInputUserService.STATUS_ROOT_OR_NON_SHELL_REJECTED -> InputCapability.ROOT_REJECTED
            ShizukuInputUserService.STATUS_INJECTOR_UNAVAILABLE -> InputCapability.INJECT_EVENTS_UNAVAILABLE
            ShizukuInputUserService.STATUS_CONCURRENT_TOUCH_UNKNOWN -> InputCapability.CONCURRENT_TOUCH_UNKNOWN
            ShizukuInputUserService.STATUS_CONCURRENT_TOUCH_UNSAFE -> InputCapability.CONCURRENT_TOUCH_UNSAFE
            else -> InputCapability.DISCONNECTED
        }
        hotPathReady = capability == InputCapability.CONCURRENT_TOUCH_SAFE
        return capability
    }

    fun isReady(): Boolean = remote != null && hotPathReady

    /**
     * One FIRE = one confirmed Nubia tap. This call is intentionally synchronous.
     * It normally occupies only the vendor DOWN + 1 ms contact + vendor UP.
     */
    fun fireFast(x: Float, y: Float, displayId: Int = 0): Boolean {
        val service = remote ?: run {
            lastFireStatus = ShizukuInputUserService.STATUS_NOT_READY
            scheduleReconnect()
            return false
        }

        val id = ++triggerId
        return try {
            val status = service.injectTapNow(id, x, y, displayId)
            lastFireStatus = status
            if (status == ShizukuInputUserService.STATUS_OK) {
                hotPathReady = true
                true
            } else {
                capabilityDetail = runCatching { service.capabilityDetail }
                    .getOrDefault("Nubia FIRE failed status=$status")
                false
            }
        } catch (failure: Throwable) {
            remote = null
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            lastFireStatus = ShizukuInputUserService.STATUS_EXCEPTION
            capabilityDetail = "FIRE Binder error: ${failure.message ?: failure.javaClass.simpleName}"
            scheduleReconnect()
            false
        }
    }

    override fun tap(request: TapRequest): TapResult {
        val acceptedAt = SystemClock.elapsedRealtimeNanos()
        val service = remote
            ?: return TapResult.Failed(request.triggerId, acceptedAt, "Shizuku input service disconnected")

        return try {
            val status = service.injectTapNow(
                request.triggerId,
                request.x,
                request.y,
                request.displayId,
            )
            if (status == ShizukuInputUserService.STATUS_OK) {
                TapResult.Completed(
                    triggerId = request.triggerId,
                    acceptedAtNs = acceptedAt,
                    downSentAtNs = service.lastDownNs,
                    upSentAtNs = service.lastUpNs,
                )
            } else {
                TapResult.Failed(request.triggerId, acceptedAt, "Nubia status=$status")
            }
        } catch (failure: Throwable) {
            TapResult.Failed(
                request.triggerId,
                acceptedAt,
                "binder error: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    fun latencyDetail(): String {
        val service = remote ?: return "latency: disconnected"
        return runCatching { service.latencyDetail }.getOrDefault("latency: unavailable")
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
        hotPathReady = false
        binding = false
        capability = InputCapability.DISCONNECTED
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 50L
    }
}
