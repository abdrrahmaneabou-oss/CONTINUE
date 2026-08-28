package com.pixeltrigger.app.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuInputUserService::class.java.name),
    )
        .processNameSuffix("pixeltrigger_input")
        .daemon(true)
        .tag("pixeltrigger-input-v9-single-shot")
        // Force Shizuku to replace the old oneway/void daemon after APK update.
        .version(10)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
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
            if (!closed) mainHandler.post { connect() }
        }
    }

    fun connect(): Boolean {
        closed = false
        if (remote != null || binding) return true
        if (!Shizuku.pingBinder()) {
            binding = false
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            capabilityDetail = "Start Shizuku with Wireless debugging/ADB"
            return false
        }
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid != ShizukuInputUserService.SHELL_UID) {
            binding = false
            hotPathReady = false
            capability = InputCapability.ROOT_REJECTED
            capabilityDetail = "No-root policy: Shizuku must run as ADB shell UID 2000 (got $uid)"
            return false
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            binding = false
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
     * Fastest confirmed app-side path: one direct AIDL transaction. DOWN is sent
     * before the reply, so confirmation does not add delay before the action.
     */
    fun fireFast(x: Float, y: Float, displayId: Int = 0): Boolean {
        val service = remote ?: run {
            hotPathReady = false
            lastFireStatus = ShizukuInputUserService.STATUS_NOT_READY
            if (!closed) mainHandler.post { connect() }
            return false
        }
        val triggerId = ++fastTriggerId
        return try {
            val rc = service.injectTapFast(triggerId, x, y, displayId)
            lastFireStatus = rc
            if (rc == ShizukuInputUserService.STATUS_OK) {
                hotPathReady = true
                true
            } else {
                hotPathReady = false
                capabilityDetail = runCatching { service.capabilityDetail }
                    .getOrDefault("FIRE failed status=$rc")
                false
            }
        } catch (failure: Throwable) {
            remote = null
            hotPathReady = false
            capability = InputCapability.DISCONNECTED
            lastFireStatus = ShizukuInputUserService.STATUS_EXCEPTION
            capabilityDetail = "FIRE binder error: ${failure.message ?: failure.javaClass.simpleName}"
            if (!closed) mainHandler.post { connect() }
            false
        }
    }

    /** Compatibility path retained for non-hot-path callers/tests. */
    override fun tap(request: TapRequest): TapResult {
        val acceptedAt = SystemClock.elapsedRealtimeNanos()
        val service = remote
            ?: return TapResult.Failed(request.triggerId, acceptedAt, "Shizuku input service disconnected")
        return runCatching {
            val rc = service.injectTapFast(
                request.triggerId,
                request.x,
                request.y,
                request.displayId,
            )
            if (rc == ShizukuInputUserService.STATUS_OK) {
                TapResult.Completed(
                    triggerId = request.triggerId,
                    acceptedAtNs = acceptedAt,
                    downSentAtNs = 0L,
                    upSentAtNs = 0L,
                )
            } else {
                TapResult.Failed(request.triggerId, acceptedAt, "backend rejected FIRE status=$rc")
            }
        }.getOrElse {
            TapResult.Failed(request.triggerId, acceptedAt, "binder submit error: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun latencyDetail(): String {
        val service = remote ?: return "latency: disconnected"
        return runCatching { service.latencyDetail }.getOrDefault("latency: unavailable")
    }

    fun disconnect() {
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { Shizuku.unbindUserService(args, connection, false) }
        remote = null
        hotPathReady = false
        binding = false
        capability = InputCapability.DISCONNECTED
    }
}
