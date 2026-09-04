package com.pixeltrigger.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pixeltrigger.app.engine.DetectionEngine
import com.pixeltrigger.app.engine.PixelSampler
import com.pixeltrigger.app.input.ShoulderShizukuEngine
import com.pixeltrigger.app.ui.SensorOverlayView
import com.pixeltrigger.app.ui.SensorStatus
import kotlin.math.max
import kotlin.math.roundToInt

class ShoulderCaptureService : Service() {
    private enum class Side { R, L }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var positionStore: OrientationPositionStore
    private lateinit var shoulderInput: ShoulderShizukuEngine
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var visibleDiameter = 1
    private var touchSize = 1

    private var rView: SensorOverlayView? = null
    private var lView: SensorOverlayView? = null
    private var rParams: WindowManager.LayoutParams? = null
    private var lParams: WindowManager.LayoutParams? = null
    private val rDetector = DetectionEngine()
    private val lDetector = DetectionEngine()

    @Volatile private var editSide: Side? = null
    @Volatile private var engineEnabled = true
    private var lastInputReady = false
    private var fingerHeldSide: Side? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        positionStore = OrientationPositionStore(prefs)
        engineEnabled = prefs.getBoolean(KEY_ENGINE_ENABLED, true)
        configureDetectors()
        shoulderInput = ShoulderShizukuEngine(this)
        shoulderInput.connect()
        activeInstance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ShoulderController")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSharedMode()
            ACTION_EDIT_R -> enterEdit(Side.R)
            ACTION_EDIT_L -> enterEdit(Side.L)
            ACTION_DONE_EDIT -> leaveEdit()
            ACTION_RESET_R -> resetToCenter(Side.R)
            ACTION_RESET_L -> resetToCenter(Side.L)
            ACTION_SYNC_CONFIG -> configureDetectors()
            ACTION_SET_ENABLED -> setEngineEnabled(intent.getBooleanExtra(EXTRA_ENABLED, true))
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun configureDetectors() {
        listOf(rDetector, lDetector).forEach {
            it.whiteRearmEnabled = true
            it.rearmDelayEnabled = false
            it.rearmSeconds = 10
        }
    }

    private fun startSharedMode() {
        if (rView != null) return
        val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        screenWidth = bounds.width().coerceAtLeast(1)
        screenHeight = bounds.height().coerceAtLeast(1)
        createOverlays()
        lastInputReady = shoulderInput.isReady()
        refreshStatusViews()
    }

    private fun consumeSharedFrame(image: Image, sourceWidth: Int, sourceHeight: Int) {
        if (rView == null || !engineEnabled || editSide != null) return
        if (
            sourceWidth > 0 && sourceHeight > 0 &&
            (sourceWidth != screenWidth || sourceHeight != screenHeight)
        ) {
            screenWidth = sourceWidth
            screenHeight = sourceHeight
            mainHandler.post {
                restoreCirclePositionsForCurrentProfile()
                rDetector.resetForSensorMove()
                lDetector.resetForSensorMove()
                refreshStatusViews()
            }
            // Skip the transition frame so stale portrait coordinates can
            // never be sampled against landscape geometry (or vice versa).
            return
        }
        if (screenWidth <= 0 || screenHeight <= 0) return

        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return
        val ready = shoulderInput.isReady()
        if (ready != lastInputReady) {
            lastInputReady = ready
            mainHandler.post { refreshStatusViews() }
        }

        val now = SystemClock.elapsedRealtime()
        processSide(Side.R, image, crop, rParams, rDetector, ready, now)
        processSide(Side.L, image, crop, lParams, lDetector, ready, now)
    }

    private fun processSide(
        side: Side,
        image: Image,
        crop: Rect,
        params: WindowManager.LayoutParams?,
        detector: DetectionEngine,
        inputReady: Boolean,
        nowMs: Long,
    ) {
        // A visible/enabled manual shoulder circle owns its selected side exclusively.
        // This prevents the macro's own screen changes from feeding back into the
        // automatic detector and recursively firing the same R/L action.
        if (manualReservedSide == side || fingerReservedSide == side) return

        val lp = params ?: return
        val sample = sample(image, crop, lp) ?: return
        when (detector.processSample(sample, nowMs)) {
            is DetectionEngine.Event.Armed,
            is DetectionEngine.Event.Rearmed,
            is DetectionEngine.Event.ManualRearmed -> mainHandler.post { refreshSideStatus(side, inputReady) }

            is DetectionEngine.Event.Fired -> {
                val fired = inputReady && fireConfiguredSide(side)
                mainHandler.post {
                    setSideStatus(side, if (fired) SensorStatus.FIRED else SensorStatus.INPUT_NOT_READY)
                }
            }
            else -> Unit
        }
    }

    private fun sample(image: Image, crop: Rect, lp: WindowManager.LayoutParams): DetectionEngine.ColorSample? {
        val screenCenterX = lp.x + touchSize / 2
        val screenCenterY = lp.y + touchSize / 2
        val centerX = (crop.left + screenCenterX * crop.width().toFloat() / screenWidth).roundToInt()
            .coerceIn(crop.left, crop.right - 1)
        val centerY = (crop.top + screenCenterY * crop.height().toFloat() / screenHeight).roundToInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val screenRadius = visibleDiameter / 2f
        val radiusX = max(0.5f, crop.width() * screenRadius / screenWidth)
        val radiusY = max(0.5f, crop.height() * screenRadius / screenHeight)
        return PixelSampler.sampleCircularRegion(image, centerX, centerY, radiusX, radiusY)
    }

    private fun pressDurationMs(side: Side): Int {
        val prefix = if (side == Side.R) "r" else "l"
        if (!prefs.getBoolean("shoulder_${prefix}_hold", false)) return 0
        return prefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5) * 1000
    }

    /** Single source of truth for physical-style R/L firing. */
    private fun fireConfiguredSide(side: Side): Boolean {
        if (!::shoulderInput.isInitialized) return false
        if (!shoulderInput.isReady()) {
            shoulderInput.connect()
            return false
        }
        return if (side == Side.R) {
            shoulderInput.fireR(pressDurationMs(Side.R))
        } else {
            shoulderInput.fireL(pressDurationMs(Side.L))
        }
    }

    /** V6 finger DOWN: reserve that detector side and keep F7/F8 physically held. */
    private fun beginFingerHoldSide(side: Side): Boolean {
        if (!engineEnabled || !::shoulderInput.isInitialized) return false
        if (!shoulderInput.isReady()) {
            shoulderInput.connect()
            return false
        }

        val previous = fingerHeldSide
        if (previous != null && previous != side) endFingerHoldSide(previous)

        fingerReservedSide = side
        fingerHeldSide = side
        onManualReservationChanged()
        val pressed = if (side == Side.R) shoulderInput.pressR() else shoulderInput.pressL()
        if (!pressed) {
            fingerHeldSide = null
            if (fingerReservedSide == side) fingerReservedSide = null
            onManualReservationChanged()
        }
        return pressed
    }

    /** V6 finger UP/CANCEL. No duration is guessed; UP happens at this call. */
    private fun endFingerHoldSide(requestedSide: Side?): Boolean {
        if (!::shoulderInput.isInitialized) return false
        val held = fingerHeldSide
        val target = requestedSide ?: held
        if (target == null) {
            fingerReservedSide = null
            return true
        }

        // V6 must never release a timed/manual shoulder press it does not own.
        // Only the side currently reserved/held by the finger-driven controller
        // is eligible for this immediate UP.
        if (held != target && fingerReservedSide != target) return true

        val released = if (target == Side.R) shoulderInput.releaseR() else shoulderInput.releaseL()
        if (fingerHeldSide == target || requestedSide == null) fingerHeldSide = null
        if (fingerReservedSide == target || requestedSide == null) fingerReservedSide = null
        onManualReservationChanged()
        return released
    }

    private fun createOverlays() {
        visibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        touchSize = max(dp(48), visibleDiameter + dp(30))
        rView = createCircle(Side.R)
        lView = createCircle(Side.L)
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun createCircle(side: Side): SensorOverlayView {
        val view = SensorOverlayView(this, visibleDiameter)
        val prefix = shoulderPositionPrefix(side)
        val saved = positionStore.load(
            keyPrefix = prefix,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = touchSize,
            overlayHeight = touchSize,
            fallbackX = normalizedPosition(side, "x", defaultX(side), screenWidth),
            fallbackY = normalizedPosition(side, "y", screenHeight / 2 - touchSize / 2, screenHeight),
        )
        val lp = overlayParams(touchSize, touchSize).apply {
            x = saved.x
            y = saved.y
            flags = baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        clamp(lp, touchSize, touchSize)
        if (side == Side.R) rParams = lp else lParams = lp
        windowManager.addView(view, lp)
        attachDrag(view, lp, side)
        if (positionStore.isPortrait(screenWidth, screenHeight) && !positionStore.hasSaved(prefix, screenWidth, screenHeight)) {
            savePosition(side, lp)
        }
        return view
    }

    private fun defaultX(side: Side): Int {
        val center = screenWidth / 2 - touchSize / 2
        return center + if (side == Side.R) -dp(90) else dp(90)
    }

    private fun normalizedPosition(side: Side, axis: String, fallback: Int, dimension: Int): Int {
        val key = positionKey(side, axis)
        if (!prefs.contains(key)) return fallback
        return (prefs.getFloat(key, 0f).coerceIn(0f, 1f) * dimension).roundToInt()
    }

    private fun savePosition(side: Side, lp: WindowManager.LayoutParams) {
        positionStore.save(
            keyPrefix = shoulderPositionPrefix(side),
            x = lp.x,
            y = lp.y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = touchSize,
            overlayHeight = touchSize,
        )
        // Preserve the old normalized portrait keys for downgrade/source
        // compatibility, but landscape must never overwrite them.
        if (positionStore.isPortrait(screenWidth, screenHeight)) {
            prefs.edit()
                .putFloat(positionKey(side, "x"), lp.x.toFloat() / screenWidth.toFloat())
                .putFloat(positionKey(side, "y"), lp.y.toFloat() / screenHeight.toFloat())
                .apply()
        }
    }

    private fun shoulderPositionPrefix(side: Side): String =
        "shoulder.${side.name.lowercase()}"

    private fun restoreCirclePositionsForCurrentProfile() {
        restoreSidePosition(Side.R, rView, rParams)
        restoreSidePosition(Side.L, lView, lParams)
    }

    private fun restoreSidePosition(
        side: Side,
        view: SensorOverlayView?,
        lp: WindowManager.LayoutParams?,
    ) {
        if (view == null || lp == null) return
        val saved = positionStore.load(
            keyPrefix = shoulderPositionPrefix(side),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = touchSize,
            overlayHeight = touchSize,
            fallbackX = normalizedPosition(side, "x", defaultX(side), screenWidth),
            fallbackY = normalizedPosition(side, "y", screenHeight / 2 - touchSize / 2, screenHeight),
        )
        lp.x = saved.x
        lp.y = saved.y
        clamp(lp, touchSize, touchSize)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun positionKey(side: Side, axis: String) = "shoulder_${side.name.lowercase()}_${axis}"

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams, side: Side) {
        var grabX = 0f
        var grabY = 0f
        var framePending = false
        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            view.postOnAnimation {
                framePending = false
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabX = event.rawX - lp.x
                    grabY = event.rawY - lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (event.rawX - grabX).roundToInt()
                    lp.y = (event.rawY - grabY).roundToInt()
                    clamp(lp, touchSize, touchSize)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clamp(lp, touchSize, touchSize)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    savePosition(side, lp)
                    detector(side).resetForSensorMove()
                    true
                }
                else -> false
            }
        }
    }

    private fun detector(side: Side) = if (side == Side.R) rDetector else lDetector

    private fun enterEdit(side: Side) {
        editSide = side
        detector(side).resetForSensorMove()
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun leaveEdit() {
        editSide = null
        rDetector.resetForSensorMove()
        lDetector.resetForSensorMove()
        updateCircleTouchability()
        refreshStatusViews()
    }

    private fun resetToCenter(side: Side) {
        val lp = if (side == Side.R) rParams else lParams
        val view = if (side == Side.R) rView else lView
        if (lp == null || view == null) return
        lp.x = screenWidth / 2 - touchSize / 2
        lp.y = screenHeight / 2 - touchSize / 2
        clamp(lp, touchSize, touchSize)
        runCatching { windowManager.updateViewLayout(view, lp) }
        savePosition(side, lp)
        detector(side).resetForSensorMove()
        refreshSideStatus(side, shoulderInput.isReady())
    }

    private fun updateCircleTouchability() {
        updateTouchability(Side.R, rView, rParams)
        updateTouchability(Side.L, lView, lParams)
    }

    private fun updateTouchability(side: Side, view: SensorOverlayView?, lp: WindowManager.LayoutParams?) {
        if (view == null || lp == null) return
        lp.flags = if (editSide == side) baseFlags()
        else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun setEngineEnabled(enabled: Boolean) {
        engineEnabled = enabled
        prefs.edit().putBoolean(KEY_ENGINE_ENABLED, enabled).apply()
        if (!enabled) {
            endFingerHoldSide(null)
            shoulderInput.releaseAll()
        }
        rDetector.resetForSensorMove()
        lDetector.resetForSensorMove()
        refreshStatusViews()
    }

    private fun onManualReservationChanged() {
        // Drop any previously armed baseline immediately. When reservation is
        // removed the detector must acquire a fresh three-white-frame baseline.
        rDetector.resetForSensorMove()
        lDetector.resetForSensorMove()
        refreshStatusViews()
    }

    private fun refreshStatusViews() {
        val ready = shoulderInput.isReady()
        refreshSideStatus(Side.R, ready)
        refreshSideStatus(Side.L, ready)
    }

    private fun refreshSideStatus(side: Side, ready: Boolean) {
        val status = when {
            manualReservedSide == side || fingerReservedSide == side -> SensorStatus.OFF
            !engineEnabled -> SensorStatus.OFF
            !ready -> SensorStatus.INPUT_NOT_READY
            detector(side).state == DetectionEngine.State.ARMED -> SensorStatus.ARMED
            detector(side).state == DetectionEngine.State.WAITING_REARM -> SensorStatus.FIRED
            else -> SensorStatus.WAITING
        }
        setSideStatus(side, status)
    }

    private fun setSideStatus(side: Side, status: SensorStatus) {
        (if (side == Side.R) rView else lView)?.setStatus(status)
    }

    private fun summary(): String {
        val r = if (!prefs.getBoolean("shoulder_r_hold", false)) "Flash" else "${prefs.getInt("shoulder_r_seconds", 1).coerceIn(1, 5)}s"
        val l = if (!prefs.getBoolean("shoulder_l_hold", false)) "Flash" else "${prefs.getInt("shoulder_l_seconds", 1).coerceIn(1, 5)}s"
        val state = if (engineEnabled) "ON" else "OFF"
        val manual = manualReservedSide?.name?.let { " • Manual $it" } ?: ""
        val finger = fingerReservedSide?.name?.let { " • V6 hold $it" } ?: ""
        return "R $r  •  L $l  •  $state$manual$finger"
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun clamp(lp: WindowManager.LayoutParams, width: Int, height: Int) {
        lp.x = lp.x.coerceIn(0, max(screenWidth - width, 0))
        lp.y = lp.y.coerceIn(0, max(screenHeight - height, 0))
    }

    private fun mmToPx(mm: Float): Int {
        val metrics = resources.displayMetrics
        val x = metrics.xdpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val y = metrics.ydpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        return (mm * ((x + y) / 2f) / 25.4f).roundToInt()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PixelTrigger Shoulder", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("PixelTrigger V6 — R/L")
        .setContentText("1 R + 1 L • PixelProbe detector path")
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        rView?.let { runCatching { windowManager.removeView(it) } }
        lView?.let { runCatching { windowManager.removeView(it) } }
        if (::shoulderInput.isInitialized) {
            endFingerHoldSide(null)
            shoulderInput.releaseAll()
            shoulderInput.disconnect()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeInstance: ShoulderCaptureService? = null
        @Volatile private var manualReservedSide: Side? = null
        @Volatile private var fingerReservedSide: Side? = null

        fun dispatchSharedFrame(image: Image, screenWidth: Int, screenHeight: Int) {
            activeInstance?.consumeSharedFrame(image, screenWidth, screenHeight)
        }

        /** Manual overlay entry points intentionally bypass automatic detector state. */
        fun fireConfiguredR(): Boolean = activeInstance?.fireConfiguredSide(Side.R) ?: false

        fun fireConfiguredL(): Boolean = activeInstance?.fireConfiguredSide(Side.L) ?: false

        fun beginFingerHoldR(): Boolean = activeInstance?.beginFingerHoldSide(Side.R) ?: false

        fun beginFingerHoldL(): Boolean = activeInstance?.beginFingerHoldSide(Side.L) ?: false

        fun endFingerHoldR(): Boolean = activeInstance?.endFingerHoldSide(Side.R) ?: false

        fun endFingerHoldL(): Boolean = activeInstance?.endFingerHoldSide(Side.L) ?: false

        fun endAnyFingerHold(): Boolean = activeInstance?.endFingerHoldSide(null) ?: false

        fun reserveManualR() = setManualReservation(Side.R)

        fun reserveManualL() = setManualReservation(Side.L)

        fun clearManualReservation() = setManualReservation(null)

        private fun setManualReservation(side: Side?) {
            if (manualReservedSide == side) return
            manualReservedSide = side
            activeInstance?.onManualReservationChanged()
        }

        fun statusSummary(): String = activeInstance?.summary() ?: "R/L starting…"

        const val ACTION_START = "com.pixeltrigger.app.action.START_SHOULDER"
        const val ACTION_STOP = "com.pixeltrigger.app.action.STOP_SHOULDER"
        const val ACTION_EDIT_R = "com.pixeltrigger.app.action.EDIT_SHOULDER_R"
        const val ACTION_EDIT_L = "com.pixeltrigger.app.action.EDIT_SHOULDER_L"
        const val ACTION_DONE_EDIT = "com.pixeltrigger.app.action.DONE_SHOULDER_EDIT"
        const val ACTION_RESET_R = "com.pixeltrigger.app.action.RESET_SHOULDER_R"
        const val ACTION_RESET_L = "com.pixeltrigger.app.action.RESET_SHOULDER_L"
        const val ACTION_SYNC_CONFIG = "com.pixeltrigger.app.action.SYNC_SHOULDER_CONFIG"
        const val ACTION_SET_ENABLED = "com.pixeltrigger.app.action.SET_SHOULDER_ENABLED"
        const val EXTRA_ENABLED = "shoulder_enabled"
        const val PREFS_NAME = "pixeltrigger_shoulder_v5"
        const val KEY_ENGINE_ENABLED = "shoulder_half_enabled"
        const val MONITOR_DIAMETER_MM = 0.3f
        private const val CHANNEL_ID = "pixeltrigger_shoulder"
        private const val NOTIFICATION_ID = 5205
    }
}
