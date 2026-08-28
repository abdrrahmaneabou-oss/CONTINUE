package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import rikka.shizuku.Shizuku

/** App-side, no-root Shizuku tap backend. No Accessibility fallback is used silently. */
class ShizukuTapEngine(private val context: Context) : TapEngine {
    override val name: String = "shizuku-redmagic-nubia-inputreader-ultralow"

    @Volatile private var remote: IShizukuInputService? = null
    @Volatile private var hotPathReady: Boolean = false
    @Volatile var capability: InputCapability = InputCapability.DISCONNECTED
        private set
    @Volatile var capabilityDetail: String = "Shizuku not connected"
        private set

    // FIRE is produced only by the capture thread, so this counter needs no AtomicLong.
    private var fastTriggerId: Long = 0L

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_input")
        .daemon(true)
        .tag("pixeltrigger-input-v9-single-shot")
        .version(9)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IShizukuInputService.Stub.asInterface(service)
            refreshCapability()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "Shizuku UserService disconnected"
        }
    }

    fun connect(): Boolean {
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
            Shizuku.bindUserService(args, connection)
            true
        }.getOrElse {
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "bind failed: ${it.message ?: it.javaClass.simpleName}"
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
     * Fastest app-side path: no TapRequest, no TapResult, no AtomicLong, no timestamp,
     * no diagnostics. One direct oneway AIDL submit and return.
     */
    fun fireFast(x: Float, y: Float, displayId: Int = 0): Boolean {
        val service = remote ?: return false
        if (!hotPathReady) return false
        val triggerId = ++fastTriggerId
        return try {
            service.injectTapFast(triggerId, x, y, displayId)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Compatibility path retained for non-hot-path callers/tests. */
    override fun tap(request: TapRequest): TapResult {
        val acceptedAt = SystemClock.elapsedRealtimeNanos()
        val service = remote
            ?: return TapResult.Failed(request.triggerId, acceptedAt, "Shizuku input service disconnected")
        if (!hotPathReady) {
            return TapResult.Failed(request.triggerId, acceptedAt, "Nubia input backend not warmed/ready")
        }

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

    fun disconnect() {
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        hotPathReady = false
        capability = InputCapability.DISCONNECTED
    }
}
