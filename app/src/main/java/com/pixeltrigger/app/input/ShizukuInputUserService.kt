package com.pixeltrigger.app.input

import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * Minimal REDMAGIC/Nubia virtual-touch backend running as Shizuku shell UID 2000.
 *
 * A right-half FIRE arrives synchronously, sends DOWN, keeps the contact for 1 ms,
 * sends UP, and only then returns to the detector thread. No queue and no worker.
 */
class ShizukuInputUserService : IShizukuInputService.Stub {
    constructor()
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context)

    @Volatile private var lastDownNs = 0L
    @Volatile private var lastUpNs = 0L
    @Volatile private var lastRequestReceivedNs = 0L
    @Volatile private var lastDownCallStartNs = 0L
    @Volatile private var lastDownCallEndNs = 0L
    @Volatile private var lastUpCallStartNs = 0L
    @Volatile private var lastUpCallEndNs = 0L
    @Volatile private var detail = "not probed"

    private val nubiaInjector: NubiaVirtualTouchInjector? by lazy {
        NubiaVirtualTouchInjector.create().also {
            detail = it?.detail ?: "Nubia InputManager.virtualTouchEvent unavailable"
        }
    }

    override fun getBackendUid(): Int = Process.myUid()

    override fun probeCapability(): Int {
        return when {
            Process.myUid() != SHELL_UID -> {
                detail = "PixelTrigger requires Shizuku ADB/shell UID 2000; backend uid=${Process.myUid()}"
                STATUS_ROOT_OR_NON_SHELL_REJECTED
            }
            nubiaInjector == null -> {
                if (detail == "not probed") detail = "Nubia InputManager.virtualTouchEvent unavailable"
                STATUS_INJECTOR_UNAVAILABLE
            }
            else -> {
                detail = nubiaInjector!!.detail
                STATUS_SAFE
            }
        }
    }

    override fun getCapabilityDetail(): String = detail

    /** One detector FIRE equals exactly one complete Nubia DOWN/UP transaction. */
    override fun injectTapNow(triggerId: Long, x: Float, y: Float, displayId: Int): Int {
        lastRequestReceivedNs = SystemClock.elapsedRealtimeNanos()
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) }

        if (Process.myUid() != SHELL_UID) {
            detail = "tap rejected: UserService uid=${Process.myUid()}"
            return STATUS_ROOT_OR_NON_SHELL_REJECTED
        }
        if (triggerId <= 0L || !x.isFinite() || !y.isFinite()) {
            detail = "tap rejected: invalid argument"
            return STATUS_INVALID_ARGUMENT
        }

        val injector = nubiaInjector ?: run {
            detail = "tap rejected: Nubia injector unavailable"
            return STATUS_INJECTOR_UNAVAILABLE
        }

        @Suppress("UNUSED_VARIABLE")
        val ignoredDisplayId = displayId
        return sendNubiaTap(injector, x, y)
    }

    private fun sendNubiaTap(injector: NubiaVirtualTouchInjector, x: Float, y: Float): Int {
        val px = x.roundToInt()
        val py = y.roundToInt()

        try {
            lastDownCallStartNs = SystemClock.elapsedRealtimeNanos()
            injector.send(ACTION_DOWN, px, py)
            lastDownCallEndNs = SystemClock.elapsedRealtimeNanos()
            lastDownNs = lastDownCallEndNs
        } catch (failure: Throwable) {
            detail = "Nubia DOWN rejected: ${failure.javaClass.simpleName}: ${failure.message ?: "unknown"}"
            return STATUS_DOWN_REJECTED
        }

        val upDeadlineNs = lastDownCallEndNs + TAP_DURATION_NS
        while (SystemClock.elapsedRealtimeNanos() < upDeadlineNs) {
            // Deliberate 1 ms contact. This is the whole synthetic tap duration.
        }

        return try {
            lastUpCallStartNs = SystemClock.elapsedRealtimeNanos()
            injector.send(ACTION_UP, px, py)
            lastUpCallEndNs = SystemClock.elapsedRealtimeNanos()
            lastUpNs = lastUpCallEndNs
            detail = injector.detail
            STATUS_OK
        } catch (failure: Throwable) {
            detail = "Nubia UP rejected: ${failure.javaClass.simpleName}: ${failure.message ?: "unknown"}"
            val recovered = runCatching {
                lastUpCallStartNs = SystemClock.elapsedRealtimeNanos()
                injector.send(ACTION_UP, px, py)
                lastUpCallEndNs = SystemClock.elapsedRealtimeNanos()
                lastUpNs = lastUpCallEndNs
            }.isSuccess
            if (recovered) STATUS_OK else STATUS_UP_REJECTED
        }
    }

    override fun getLastDownNs(): Long = lastDownNs
    override fun getLastUpNs(): Long = lastUpNs

    override fun getLatencyDetail(): String {
        fun us(start: Long, end: Long): Long =
            if (start > 0L && end >= start) (end - start) / 1_000L else -1L

        val binderToDownUs = if (
            lastRequestReceivedNs > 0L &&
            lastDownCallStartNs >= lastRequestReceivedNs
        ) {
            (lastDownCallStartNs - lastRequestReceivedNs) / 1_000L
        } else {
            -1L
        }

        return "backend=${nubiaInjector?.kind ?: "none"}; " +
            "binderToDown=${binderToDownUs}us; " +
            "downCall=${us(lastDownCallStartNs, lastDownCallEndNs)}us; " +
            "upCall=${us(lastUpCallStartNs, lastUpCallEndNs)}us"
    }

    override fun destroy() {
        System.exit(0)
    }

    private interface NubiaVirtualTouchInjector {
        val detail: String
        val kind: String
        fun send(action: Int, x: Int, y: Int)

        companion object {
            fun create(): NubiaVirtualTouchInjector? =
                DirectBinderInjector.create() ?: ReflectionInjector.create()
        }
    }

    /** Direct verified Nubia IInputManager transaction. */
    private class DirectBinderInjector(private val binder: IBinder) : NubiaVirtualTouchInjector {
        override val kind: String = "direct-binder-126"
        override val detail: String =
            "REDMAGIC/Nubia direct IInputManager transaction ready (tx=$TRANSACTION_VIRTUAL_TOUCH_EVENT, keyCode=$VIRTUAL_KEYCODE, mode=$VIRTUAL_TOUCH_MODE, gamepadId=$VIRTUAL_GAMEPAD_ID)"

        override fun send(action: Int, x: Int, y: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(INPUT_MANAGER_DESCRIPTOR)
                data.writeInt(VIRTUAL_KEYCODE)
                data.writeInt(action)
                data.writeInt(VIRTUAL_TOUCH_MODE)
                data.writeInt(VIRTUAL_GAMEPAD_ID)
                data.writeInt(x)
                data.writeInt(y)
                if (!binder.transact(TRANSACTION_VIRTUAL_TOUCH_EVENT, data, reply, 0)) {
                    throw UnsupportedOperationException(
                        "IInputManager transaction $TRANSACTION_VIRTUAL_TOUCH_EVENT not handled",
                    )
                }
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        companion object {
            fun create(): DirectBinderInjector? = runCatching {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
                    .apply { isAccessible = true }
                val binder = getService.invoke(null, "input") as? IBinder ?: return@runCatching null
                val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
                if (descriptor != INPUT_MANAGER_DESCRIPTOR) return@runCatching null
                DirectBinderInjector(binder)
            }.getOrNull()
        }
    }

    /** ROM fallback selected once at startup; no reflection lookup happens per FIRE. */
    private class ReflectionInjector(
        private val inputManager: Any,
        private val eventMethod: Method,
    ) : NubiaVirtualTouchInjector {
        override val kind: String = "cached-reflection"
        override val detail: String =
            "REDMAGIC/Nubia cached virtualTouchEvent fallback ready (keyCode=$VIRTUAL_KEYCODE, mode=$VIRTUAL_TOUCH_MODE, gamepadId=$VIRTUAL_GAMEPAD_ID)"

        override fun send(action: Int, x: Int, y: Int) {
            eventMethod.invoke(
                inputManager,
                VIRTUAL_KEYCODE,
                action,
                VIRTUAL_TOUCH_MODE,
                VIRTUAL_GAMEPAD_ID,
                x,
                y,
            )
        }

        companion object {
            fun create(): ReflectionInjector? = runCatching {
                val clazz = Class.forName("android.hardware.input.InputManager")
                val instance = clazz.getDeclaredMethod("getInstance")
                    .apply { isAccessible = true }
                    .invoke(null)
                val event = clazz.getDeclaredMethod(
                    "virtualTouchEvent",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                ).apply { isAccessible = true }
                ReflectionInjector(instance, event)
            }.getOrNull()
        }
    }

    companion object {
        const val SHELL_UID = 2000
        const val TAP_DURATION_NS = 1_000_000L

        const val VIRTUAL_KEYCODE = -4
        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
        const val VIRTUAL_TOUCH_MODE = 1
        const val VIRTUAL_GAMEPAD_ID = -2

        private const val INPUT_MANAGER_DESCRIPTOR = "android.hardware.input.IInputManager"
        private const val TRANSACTION_VIRTUAL_TOUCH_EVENT = 126

        const val STATUS_OK = 0
        const val STATUS_DUPLICATE = 1
        const val STATUS_NOT_READY = 2
        const val STATUS_ROOT_OR_NON_SHELL_REJECTED = 3
        const val STATUS_INJECTOR_UNAVAILABLE = 4
        const val STATUS_CONCURRENT_TOUCH_UNSAFE = 5
        const val STATUS_CONCURRENT_TOUCH_UNKNOWN = 6
        const val STATUS_INVALID_ARGUMENT = 7
        const val STATUS_DOWN_REJECTED = 8
        const val STATUS_UP_REJECTED = 9
        const val STATUS_EXCEPTION = 10
        const val STATUS_SAFE = 100
    }
}
