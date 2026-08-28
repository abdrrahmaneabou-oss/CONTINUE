package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku

/** App-side, no-root Shizuku tap backend. No Accessibility fallback is used silently. */
class ShizukuTapEngine(private val context: Context) : TapEngine {
    override val name: String = "shizuku-redmagic-nubia-inputreader-ultralow"

    @Volatile private var remote: IShizukuInputService? = null
    @Volatile private var hotPathReady: Boolean = false
    @Volatile private var closed: Boolean = false
    @Volatile private var binding: Boolean = false
    @Volatile var lastFireStatus: Int = ShizukuInputUserService.STATUS_NOT_READY
        private set
    @Volatile var capability: InputCapability = InputCapability.DISCONNECTED
        private set
    @Volatile var capabilityDetail: String = "Shizuku not connected"
        private set

    // FIRE is produced only by the capture thread, so this counter needs no AtomicLong.
    private var fastTriggerId: Long = 0L
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
        .tag("pixeltrigger-input-v9-single-shot")
        // Force Shizuku to discard the daemon from the broken synchronous build.
        .version(12)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            reconnectScheduled.set(false)
            mainHandler.removeCallbacks(reconnectRunnable)
            binding = false
            remote = IShizukuInputService.Stub.asInterface(service)
            flushPendingFire()
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
        if (remote != null) return true
        if (binding) {
            retryPendingConnection()
            return true
        }
        if (!Shizuku.pingBinder()) {
            binding = false
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "Start Shizuku with Wireless debugging/ADB"
            retryPendingConnection()
            return false
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != ShizukuInputUserService.SHELL_UID) {
            binding = false
            hotPathReady = false
            capability = InputCapability.ROOT_REJECTED
            capabilityDetail = "No-root policy: Shizuku must run as ADB shell UID 2000 (got $uid)"
            retryPendingConnection()
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            binding = false
            hotPathReady = false
            capability = InputCapability.PERMISSION_REQUIRED
            capabilityDetail = "Shizuku permission required"
            retryPendingConnection()
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
            retryPendingConnection()
            false
        }
    }

    /** Slow capability/diagnostic path; never called by FIRE itself. */
    fun refreshCapability(): InputCapability {
        val service = remote ?: run {
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            return capability
        }
        val code = runCatching { service.probeCapability() }.getOrElse {
            capabilityDetail = "probe failed (hot path preserved): ${it.message ?: it.javaClass.simpleName}"
            if (!hotPathReady) capability = InputCapability.DISCONNECTED
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

    /** Volatile-memory check only. */
    fun isReady(): Boolean = remote != null && hotPathReady

    /**
     * FIRE is never capability-gated. If Binder is connected, submit directly to
     * Nubia even when a stale diagnostic flag says otherwise. If the connection
     * vanished at this exact instant, retain this one shot and submit it as soon
     * as the fresh UserService connects.
     */
    fun fireFast(x: Float, y: Float, displayId: Int = 0): Boolean {
        val triggerId = ++fastTriggerId
        val service = remote ?: run {
            queuePendingFire(triggerId, x, y, displayId)
            return false
        }
        return try {
            service.injectTapFast(triggerId, x, y, displayId)
            lastFireStatus = ShizukuInputUserService.STATUS_OK
            true
        } catch (failure: Throwable) {
            queuePendingFire(triggerId, x, y, displayId, failure)
            false
        }
    }

    /** One non-blocking Binder submit for the independent two-target manual module. */
    fun firePairFast(
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        displayId: Int = 0,
    ): Boolean {
        val triggerId = ++fastTriggerId
        val service = remote ?: run {
            queuePendingPair(triggerId, firstX, firstY, secondX, secondY, displayId)
            return false
        }
        return try {
            service.injectTapPairFast(triggerId, firstX, firstY, secondX, secondY, displayId)
            lastFireStatus = ShizukuInputUserService.STATUS_OK
            true
        } catch (failure: Throwable) {
            queuePendingPair(triggerId, firstX, firstY, secondX, secondY, displayId, failure)
            false
        }
    }

    /** Compatibility path retained for non-hot-path callers/tests. */
    override fun tap(request: TapRequest): TapResult {
        val acceptedAt = SystemClock.elapsedRealtimeNanos()
        val service = remote
            ?: return TapResult.Failed(request.triggerId, acceptedAt, "Shizuku input service disconnected")
        return runCatching {
            service.injectTapFast(
                request.triggerId,
                request.x,
                request.y,
                request.displayId,
            )
            TapResult.Completed(
                triggerId = request.triggerId,
                acceptedAtNs = acceptedAt,
                downSentAtNs = 0L,
                upSentAtNs = 0L,
            )
        }.getOrElse {
            TapResult.Failed(request.triggerId, acceptedAt, "binder submit error: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun latencyDetail(): String {
        val service = remote ?: return "latency: disconnected"
        return runCatching { service.latencyDetail }.getOrDefault("latency: unavailable")
    }

    private data class PendingFire(
        val triggerId: Long,
        val x: Float,
        val y: Float,
        val displayId: Int,
        val secondX: Float = Float.NaN,
        val secondY: Float = Float.NaN,
    )

    @Volatile private var pendingFire: PendingFire? = null

    private fun queuePendingFire(
        triggerId: Long,
        x: Float,
        y: Float,
        displayId: Int,
        failure: Throwable? = null,
    ) {
        pendingFire = PendingFire(triggerId, x, y, displayId)
        remote = null
        hotPathReady = false
        capability = InputCapability.DISCONNECTED
        lastFireStatus = if (failure == null) {
            ShizukuInputUserService.STATUS_NOT_READY
        } else {
            ShizukuInputUserService.STATUS_EXCEPTION
        }
        capabilityDetail = if (failure == null) {
            "FIRE waiting for Nubia UserService connection"
        } else {
            "FIRE Binder interrupted; reconnecting: ${failure.message ?: failure.javaClass.simpleName}"
        }
        scheduleReconnect()
    }

    private fun queuePendingPair(
        triggerId: Long,
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        displayId: Int,
        failure: Throwable? = null,
    ) {
        pendingFire = PendingFire(triggerId, firstX, firstY, displayId, secondX, secondY)
        remote = null
        hotPathReady = false
        capability = InputCapability.DISCONNECTED
        lastFireStatus = if (failure == null) {
            ShizukuInputUserService.STATUS_NOT_READY
        } else {
            ShizukuInputUserService.STATUS_EXCEPTION
        }
        capabilityDetail = if (failure == null) {
            "Nubia tap pair waiting for UserService connection"
        } else {
            "Nubia tap pair Binder interrupted: ${failure.message ?: failure.javaClass.simpleName}"
        }
        scheduleReconnect()
    }

    private fun flushPendingFire() {
        val shot = pendingFire ?: return
        val service = remote ?: return
        try {
            if (shot.secondX.isFinite() && shot.secondY.isFinite()) {
                service.injectTapPairFast(
                    shot.triggerId,
                    shot.x,
                    shot.y,
                    shot.secondX,
                    shot.secondY,
                    shot.displayId,
                )
            } else {
                service.injectTapFast(shot.triggerId, shot.x, shot.y, shot.displayId)
            }
            if (pendingFire === shot) pendingFire = null
            lastFireStatus = ShizukuInputUserService.STATUS_OK
        } catch (failure: Throwable) {
            remote = null
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            lastFireStatus = ShizukuInputUserService.STATUS_EXCEPTION
            capabilityDetail = "Pending FIRE Binder interrupted: ${failure.message ?: failure.javaClass.simpleName}"
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (closed || !reconnectScheduled.compareAndSet(false, true)) return
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun retryPendingConnection() {
        if (pendingFire != null) scheduleReconnect()
    }

    fun disconnect() {
        closed = true
        reconnectScheduled.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        pendingFire = null
        hotPathReady = false
        binding = false
        capability = InputCapability.DISCONNECTED
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 50L
    }
}
