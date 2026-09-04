package com.pixeltrigger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pixeltrigger.app.engine.DetectionEngine
import com.pixeltrigger.app.engine.PixelSampler
import com.pixeltrigger.app.input.InputCapability
import com.pixeltrigger.app.input.ShizukuTapEngine
import com.pixeltrigger.app.ui.SensorOverlayView
import com.pixeltrigger.app.ui.SensorStatus
import com.pixeltrigger.app.ui.TargetOverlayView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var preferences: SharedPreferences
    private lateinit var shoulderPreferences: SharedPreferences
    private lateinit var positionStore: OrientationPositionStore
    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensityDpi = 0

    private val sensorViews = arrayOfNulls<SensorOverlayView>(GROUP_COUNT)
    private val sensorParams = arrayOfNulls<WindowManager.LayoutParams>(GROUP_COUNT)
    private val detectionEngines = Array(GROUP_COUNT) { DetectionEngine() }

    // Groups 1-4 keep exactly one monitor. Group 5 owns these two extras in
    // addition to sensorViews[4] / sensorParams[4] / detectionEngines[4].
    private val groupFiveExtraViews = arrayOfNulls<SensorOverlayView>(GROUP_FIVE_EXTRA_COUNT)
    private val groupFiveExtraParams = arrayOfNulls<WindowManager.LayoutParams>(GROUP_FIVE_EXTRA_COUNT)
    private val groupFiveExtraEngines = Array(GROUP_FIVE_EXTRA_COUNT) { DetectionEngine() }

    private var sensorVisibleDiameter = 1
    private var sensorTouchSize = 1
    @Volatile private var activeGroup = 0

    private var targetView: TargetOverlayView? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var targetTouchSize = 1
    private var targetVisibleDiameter = 1

    private lateinit var manualTapPair: ManualNubiaPairController

    private var menuButton: TextView? = null
    private var menuButtonParams: WindowManager.LayoutParams? = null
    private var menuPanel: View? = null
    private var menuPanelParams: WindowManager.LayoutParams? = null
    private var menuStatusText: TextView? = null

    private var circlesVisible = true
    @Volatile private var engineEnabled = true
    @Volatile private var shoulderHalfEnabled = true
    @Volatile private var circleEditMode = false
    private var lastInputReady = false
    @Volatile private var groupFiveTapsPerFire = 1

    private data class GlobalEngineSnapshot(
        val rightEnabled: Boolean,
        val leftEnabled: Boolean,
        val manualEnabled: Boolean,
    )

    /** Non-null only while the 0.5 s global suspension is active. */
    private var globalSuspendSnapshot: GlobalEngineSnapshot? = null

    private lateinit var tapEngine: ShizukuTapEngine

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            mainHandler.removeCallbacks(refreshDisplayRunnable)
            mainHandler.postDelayed(refreshDisplayRunnable, DISPLAY_REFRESH_DEBOUNCE_MS)
        }
    }
    private val refreshDisplayRunnable = Runnable { refreshDisplayGeometry() }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        shoulderPreferences = getSharedPreferences(ShoulderCaptureService.PREFS_NAME, MODE_PRIVATE)
        positionStore = OrientationPositionStore(preferences)
        circlesVisible = preferences.getBoolean(KEY_CIRCLES_VISIBLE, true)
        engineEnabled = preferences.getBoolean(KEY_RIGHT_ENGINE_ENABLED, true)
        shoulderHalfEnabled = shoulderPreferences.getBoolean(ShoulderCaptureService.KEY_ENGINE_ENABLED, true)
        activeGroup = preferences.getInt(KEY_ACTIVE_GROUP, 0).coerceIn(0, GROUP_COUNT - 1)
        groupFiveTapsPerFire = preferences.getInt(KEY_GROUP_FIVE_TAPS_PER_FIRE, 1)
            .coerceIn(1, GROUP_FIVE_MAX_TAPS)

        detectionEngines.forEach(::configureRightDetector)
        groupFiveExtraEngines.forEach(::configureRightDetector)
        preferences.edit()
            .putBoolean(KEY_WHITE_REARM, true)
            .putBoolean(KEY_REARM_DELAY_ENABLED, false)
            .apply()

        tapEngine = ShizukuTapEngine(this)
        tapEngine.connect()
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).registerDisplayListener(displayListener, mainHandler)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:PixelMonitor")
            .apply { acquire() }
    }

    private fun configureRightDetector(engine: DetectionEngine) {
        engine.whiteRearmEnabled = true
        engine.rearmDelayEnabled = false
        engine.rearmSeconds = 10
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (mediaProjection == null) {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = projectionIntent(intent)
                if (resultCode == 0 || data == null) stopSelf() else setupProjection(resultCode, data)
            }
            ACTION_STOP -> shutdownCompletely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun projectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

    private fun setupProjection(resultCode: Int, resultData: Intent) {
        val bounds = currentScreenBounds()
        screenWidth = bounds.width()
        screenHeight = bounds.height()
        densityDpi = resources.displayMetrics.densityDpi
        updateCaptureGeometry()

        captureThread = HandlerThread("PixelTriggerCapture", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        mediaProjection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(resultCode, resultData)
            .also { projection ->
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() = stopSelf()
                }, mainHandler)
            }

        imageReader = createImageReader(captureWidth, captureHeight)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PixelTriggerDisplay",
            captureWidth,
            captureHeight,
            captureDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        )

        mainHandler.post {
            createOverlays()
            lastInputReady = tapEngine.isReady()
            refreshSensorStatus(lastInputReady)
        }
    }

    private fun updateCaptureGeometry() {
        captureWidth = max((screenWidth * CAPTURE_SCALE).roundToInt(), 1)
        captureHeight = max((screenHeight * CAPTURE_SCALE).roundToInt(), 1)
        captureDensityDpi = max((densityDpi * CAPTURE_SCALE).roundToInt(), 1)
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use(::processImage) }, captureHandler)
        }

    private fun processImage(image: Image) {
        // Right half remains first so the independent shoulder half can never
        // delay PixelProbe detection or its confirmed Nubia tap.
        processRightFrame(image)
        if (::manualTapPair.isInitialized) {
            manualTapPair.processMonitorFrame(image, screenWidth, screenHeight)
        }
        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)
    }

    private fun processRightFrame(image: Image) {
        if (!engineEnabled || circleEditMode) return

        val inputReady = tapEngine.isReady()
        if (inputReady != lastInputReady) {
            lastInputReady = inputReady
            refreshSensorStatus(inputReady)
        }
        if (screenWidth <= 0 || screenHeight <= 0) return
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return

        val index = activeGroup
        val now = SystemClock.elapsedRealtime()
        if (index == GROUP_FIVE_INDEX) {
            processGroupFiveFrame(image, crop, inputReady, now)
            return
        }

        val params = sensorParams[index] ?: return
        val sample = sampleSensor(image, crop, params) ?: return
        when (detectionEngines[index].processSample(sample, now)) {
            is DetectionEngine.Event.Armed,
            is DetectionEngine.Event.Rearmed,
            is DetectionEngine.Event.ManualRearmed -> refreshSensorStatus(inputReady)
            is DetectionEngine.Event.Fired -> {
                executeTapImmediately()
                refreshSensorStatus(inputReady, SensorStatus.FIRED)
            }
            else -> Unit
        }
    }

    /**
     * Group 5 owns three fully independent 0.3 mm probes. Every probe evaluates
     * the frame and may FIRE even when one or both siblings also FIRE from the
     * same visual event. A FIRE starts that probe's own configurable tap burst.
     */
    private fun processGroupFiveFrame(
        image: Image,
        crop: Rect,
        inputReady: Boolean,
        nowMs: Long,
    ) {
        var stateChanged = false
        var fired = false
        var slot = 0
        while (slot < GROUP_FIVE_SENSOR_COUNT) {
            val params = groupFiveParams(slot)
            if (params != null) {
                val sample = sampleSensor(image, crop, params)
                if (sample != null) {
                    when (groupFiveDetector(slot).processSample(sample, nowMs)) {
                        is DetectionEngine.Event.Armed,
                        is DetectionEngine.Event.Rearmed,
                        is DetectionEngine.Event.ManualRearmed -> stateChanged = true

                        is DetectionEngine.Event.Fired -> {
                            executeGroupFiveBurst()
                            fired = true
                        }
                        else -> Unit
                    }
                }
            }
            slot++
        }

        // Do not force one shared FIRED color. Each detector renders from its own
        // state, so a fired probe can be red while an armed sibling remains green.
        if (stateChanged || fired) refreshSensorStatus(inputReady)
    }

    private fun sampleSensor(image: Image, crop: Rect, params: WindowManager.LayoutParams): DetectionEngine.ColorSample? {
        val screenCenterX = params.x + sensorTouchSize / 2
        val screenCenterY = params.y + sensorTouchSize / 2
        val centerX = (crop.left + screenCenterX * crop.width().toFloat() / screenWidth).roundToInt()
            .coerceIn(crop.left, crop.right - 1)
        val centerY = (crop.top + screenCenterY * crop.height().toFloat() / screenHeight).roundToInt()
            .coerceIn(crop.top, crop.bottom - 1)
        val screenRadius = sensorVisibleDiameter / 2f
        val radiusX = max(0.5f, crop.width() * screenRadius / screenWidth)
        val radiusY = max(0.5f, crop.height() * screenRadius / screenHeight)
        return PixelSampler.sampleCircularRegion(image, centerX, centerY, radiusX, radiusY)
    }

    private fun executeTapImmediately() {
        if (!engineEnabled) return
        val target = targetParams ?: return
        tapEngine.fireFast(
            target.x + targetTouchSize / 2f,
            target.y + targetTouchSize / 2f,
            displayId = 0,
        )
    }

    /**
     * Group-5-only burst. Tap 1 is synchronous and immediate. Later taps are
     * scheduled on the existing capture/input handler at exact 50 ms offsets,
     * so detection is never blocked by sleeping between taps.
     */
    private fun executeGroupFiveBurst() {
        if (!engineEnabled || activeGroup != GROUP_FIVE_INDEX || circleEditMode) return
        val target = targetParams ?: return
        val x = target.x + targetTouchSize / 2f
        val y = target.y + targetTouchSize / 2f
        val tapCount = groupFiveTapsPerFire.coerceIn(1, GROUP_FIVE_MAX_TAPS)

        tapEngine.fireFast(x, y, displayId = 0)
        if (tapCount <= 1) return

        val handler = captureHandler ?: return
        var tapIndex = 1
        while (tapIndex < tapCount) {
            val delayMs = tapIndex * GROUP_FIVE_TAP_GAP_MS
            handler.postDelayed({
                if (engineEnabled && activeGroup == GROUP_FIVE_INDEX && !circleEditMode) {
                    tapEngine.fireFast(x, y, displayId = 0)
                }
            }, delayMs)
            tapIndex++
        }
    }

    private fun createOverlays() {
        if (sensorViews[0] != null) return
        sensorVisibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        sensorTouchSize = max(dp(48), sensorVisibleDiameter + dp(30))

        var group = 0
        while (group < GROUP_COUNT) {
            val sensor = SensorOverlayView(this, sensorVisibleDiameter)
            val defaultX = if (group == GROUP_FIVE_INDEX) groupFiveDefaultX(0)
            else screenWidth / 2 - sensorTouchSize / 2
            val defaultY = screenHeight / 2 - sensorTouchSize / 2
            val savedPosition = loadRightSensorPosition(group, 0, defaultX, defaultY)
            val lp = overlayParams(sensorTouchSize, sensorTouchSize).apply {
                x = savedPosition.x
                y = savedPosition.y
            }
            sensorViews[group] = sensor
            sensorParams[group] = lp
            clampCirclePosition(lp, sensorVisibleDiameter)
            windowManager.addView(sensor, lp)
            val savedGroup = group
            attachDrag(sensor, lp, sensorVisibleDiameter) { x, y ->
                detectionEngines[savedGroup].resetForSensorMove()
                saveRightSensorPosition(savedGroup, 0, x, y)
            }
            group++
        }

        createGroupFiveExtraSensors()
        // A migrated/saved profile can contain two probes at the same pixel.
        // Repair only that broken visual overlap so group 5 always exposes
        // three distinct monitoring circles to the user.
        repairGroupFiveVisualOverlap(persist = groupFiveCurrentProfileExists())

        targetVisibleDiameter = max(mmToPx(5f), dp(12))
        targetTouchSize = max(dp(52), dp(24) + targetVisibleDiameter)
        val target = TargetOverlayView(this, targetVisibleDiameter)
        val savedTargetPosition = loadTargetPosition(
            screenWidth / 2 + dp(70),
            screenHeight / 2 - targetTouchSize / 2,
        )
        val targetLp = overlayParams(targetTouchSize, targetTouchSize).apply {
            x = savedTargetPosition.x
            y = savedTargetPosition.y
        }
        targetView = target
        targetParams = targetLp
        clampCirclePosition(targetLp, targetVisibleDiameter)
        windowManager.addView(target, targetLp)
        attachDrag(target, targetLp, targetVisibleDiameter) { x, y ->
            saveTargetPosition(x, y)
        }

        manualTapPair = ManualNubiaPairController(this, windowManager, preferences)
        manualTapPair.create(screenWidth, screenHeight)

        val buttonSize = dp(50)
        val button = TextView(this).apply {
            text = "${activeGroup + 1}"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedBackground(Color.rgb(79, 52, 185), Color.rgb(155, 135, 255), 18f)
        }
        val buttonLp = overlayParams(buttonSize, buttonSize).apply {
            x = preferences.getInt(KEY_BUTTON_X, max(screenWidth - buttonSize - dp(12), 0))
            y = preferences.getInt(KEY_BUTTON_Y, dp(60))
            flags = baseOverlayFlags()
        }
        menuButton = button
        menuButtonParams = buttonLp
        clampPosition(buttonLp)
        windowManager.addView(button, buttonLp)
        attachFloatingButtonGesture(button, buttonLp)

        setConfigurationTouchability(false)
        applyGroupVisibility()
        updateButtonVisual()
    }

    private fun createGroupFiveExtraSensors() {
        var extra = 0
        while (extra < GROUP_FIVE_EXTRA_COUNT) {
            val slot = extra + 1
            val sensor = SensorOverlayView(this, sensorVisibleDiameter)
            val savedPosition = loadRightSensorPosition(
                GROUP_FIVE_INDEX,
                slot,
                groupFiveDefaultX(slot),
                groupFiveDefaultY(),
            )
            val lp = overlayParams(sensorTouchSize, sensorTouchSize).apply {
                x = savedPosition.x
                y = savedPosition.y
            }
            groupFiveExtraViews[extra] = sensor
            groupFiveExtraParams[extra] = lp
            clampCirclePosition(lp, sensorVisibleDiameter)
            windowManager.addView(sensor, lp)
            val savedExtra = extra
            attachDrag(sensor, lp, sensorVisibleDiameter) { x, y ->
                groupFiveExtraEngines[savedExtra].resetForSensorMove()
                saveRightSensorPosition(GROUP_FIVE_INDEX, savedExtra + 1, x, y)
            }
            extra++
        }
    }

    private fun setConfigurationTouchability(enabled: Boolean) {
        var i = 0
        while (i < GROUP_COUNT) {
            val view = sensorViews[i]
            val lp = sensorParams[i]
            if (view != null && lp != null) {
                lp.flags = if (enabled && i == activeGroup) baseOverlayFlags()
                else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            i++
        }

        var extra = 0
        while (extra < GROUP_FIVE_EXTRA_COUNT) {
            val view = groupFiveExtraViews[extra]
            val lp = groupFiveExtraParams[extra]
            if (view != null && lp != null) {
                lp.flags = if (enabled && activeGroup == GROUP_FIVE_INDEX) baseOverlayFlags()
                else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            extra++
        }

        targetView?.let { view ->
            targetParams?.let { lp ->
                lp.flags = if (enabled) baseOverlayFlags()
                else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
    }

    private fun attachFloatingButtonGesture(view: View, params: WindowManager.LayoutParams) {
        var longPressTriggered = false
        val holdRunnable = Runnable {
            if (!circleEditMode && (!::manualTapPair.isInitialized || !manualTapPair.isEditing)) {
                longPressTriggered = true
                toggleAllEngines()
            }
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                longPressTriggered = false
                mainHandler.removeCallbacks(holdRunnable)
                mainHandler.postDelayed(holdRunnable, ENGINE_HOLD_MS)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                mainHandler.removeCallbacks(holdRunnable)
                params.x -= distanceX.roundToInt()
                params.y -= distanceY.roundToInt()
                clampPosition(params)
                runCatching { windowManager.updateViewLayout(view, params) }
                preferences.edit().putInt(KEY_BUTTON_X, params.x).putInt(KEY_BUTTON_Y, params.y).apply()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(holdRunnable)
                if (longPressTriggered) return true
                if (circleEditMode) finishCirclePositionEditing() else toggleMenu()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                mainHandler.removeCallbacks(holdRunnable)
                if (longPressTriggered) return true
                if (::manualTapPair.isInitialized && manualTapPair.isEditing) toggleMenu()
                else if (circleEditMode) finishCirclePositionEditing()
                else switchToNextGroup()
                return true
            }

            override fun onLongPress(e: MotionEvent) = Unit
        })

        view.setOnTouchListener { _, event ->
            val handled = detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                mainHandler.removeCallbacks(holdRunnable)
            }
            handled
        }
    }

    private fun switchToNextGroup() {
        if (circleEditMode) return
        closeMenu()
        val next = (activeGroup + 1) % GROUP_COUNT
        resetGroupDetectors(activeGroup)
        resetGroupDetectors(next)
        activeGroup = next
        preferences.edit().putInt(KEY_ACTIVE_GROUP, next).apply()
        applyGroupVisibility()
        setConfigurationTouchability(false)
        refreshSensorStatus(tapEngine.isReady())
        updateButtonVisual()
        showMessage("مجموعة ${next + 1}")
    }

    private fun attachDrag(
        view: View,
        params: WindowManager.LayoutParams,
        visibleDiameter: Int,
        onMoved: (Int, Int) -> Unit,
    ) {
        var grabOffsetX = 0f
        var grabOffsetY = 0f
        var framePending = false
        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            view.postOnAnimation {
                framePending = false
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabOffsetX = event.rawX - params.x
                    grabOffsetY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - grabOffsetX).roundToInt()
                    params.y = (event.rawY - grabOffsetY).roundToInt()
                    clampCirclePosition(params, visibleDiameter)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampCirclePosition(params, visibleDiameter)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    onMoved(params.x, params.y)
                    true
                }
                else -> true
            }
        }
    }

    private fun beginCirclePositionEditing() {
        closeMenu()
        circleEditMode = true
        setCirclesVisible(true)
        setConfigurationTouchability(true)
        resetGroupDetectors(activeGroup)
        updateButtonVisual()
        val monitorLabel = if (activeGroup == GROUP_FIVE_INDEX) "دوائر المجموعة 5 الثلاث" else "دائرة المجموعة ${activeGroup + 1}"
        showMessage("اسحب $monitorLabel ودائرة الضغط ثم اضغط الزر للحفظ")
    }

    private fun finishCirclePositionEditing() {
        if (!circleEditMode) return
        circleEditMode = false
        setConfigurationTouchability(false)
        resetGroupDetectors(activeGroup)
        updateButtonVisual()
        showMessage("تم حفظ موضع المجموعة ${activeGroup + 1}")
    }

    private fun resetMonitorCircleToCenter() {
        closeMenu()
        setCirclesVisible(true)

        if (activeGroup == GROUP_FIVE_INDEX) {
            var slot = 0
            while (slot < GROUP_FIVE_SENSOR_COUNT) {
                val lp = groupFiveParams(slot)
                val view = groupFiveView(slot)
                if (lp != null && view != null) {
                    lp.x = groupFiveDefaultX(slot)
                    lp.y = groupFiveDefaultY()
                    clampCirclePosition(lp, sensorVisibleDiameter)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    saveRightSensorPosition(GROUP_FIVE_INDEX, slot, lp.x, lp.y)
                }
                slot++
            }
            resetGroupDetectors(GROUP_FIVE_INDEX)
            refreshSensorStatus(tapEngine.isReady())
            showMessage("أعيدت دوائر المجموعة 5 الثلاث إلى المنتصف")
            return
        }

        val lp = sensorParams[activeGroup] ?: return
        val view = sensorViews[activeGroup] ?: return
        lp.x = screenWidth / 2 - sensorTouchSize / 2
        lp.y = screenHeight / 2 - sensorTouchSize / 2
        clampCirclePosition(lp, sensorVisibleDiameter)
        runCatching { windowManager.updateViewLayout(view, lp) }
        saveRightSensorPosition(activeGroup, 0, lp.x, lp.y)
        resetGroupDetectors(activeGroup)
        refreshSensorStatus(tapEngine.isReady())
        showMessage("أعيدت دائرة المجموعة ${activeGroup + 1} إلى المنتصف")
    }

    private fun resetGroupDetectors(group: Int) {
        detectionEngines[group].resetForSensorMove()
        if (group == GROUP_FIVE_INDEX) groupFiveExtraEngines.forEach { it.resetForSensorMove() }
    }

    private fun resetAllRightDetectors() {
        detectionEngines.forEach { it.resetForSensorMove() }
        groupFiveExtraEngines.forEach { it.resetForSensorMove() }
    }

    private fun setGroupFiveTapsPerFire(value: Int) {
        val clamped = value.coerceIn(1, GROUP_FIVE_MAX_TAPS)
        groupFiveTapsPerFire = clamped
        preferences.edit().putInt(KEY_GROUP_FIVE_TAPS_PER_FIRE, clamped).apply()
    }

    private fun setRightEngineEnabled(enabled: Boolean) {
        engineEnabled = enabled
        preferences.edit().putBoolean(KEY_RIGHT_ENGINE_ENABLED, enabled).apply()
        resetAllRightDetectors()
        refreshSensorStatus(tapEngine.isReady(), if (enabled) null else SensorStatus.OFF)
        applyGroupVisibility()
        updateButtonVisual()
    }

    private fun toggleRightEngine() = setRightEngineEnabled(!engineEnabled)

    private fun setLeftEngineEnabled(enabled: Boolean) {
        shoulderHalfEnabled = enabled
        shoulderPreferences.edit().putBoolean(ShoulderCaptureService.KEY_ENGINE_ENABLED, enabled).apply()
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply {
                action = ShoulderCaptureService.ACTION_SET_ENABLED
                putExtra(ShoulderCaptureService.EXTRA_ENABLED, enabled)
            })
        }
        updateButtonVisual()
        menuStatusText?.text = combinedStatusText()
    }

    private fun toggleLeftEngine() = setLeftEngineEnabled(!shoulderHalfEnabled)

    /**
     * 0.5 s hold is a true suspend/resume operation. The first hold snapshots
     * each independent module exactly as it is and turns everything off. The
     * second hold restores that snapshot instead of blindly enabling all halves.
     */
    private fun toggleAllEngines() {
        val saved = globalSuspendSnapshot
        if (saved == null) {
            globalSuspendSnapshot = GlobalEngineSnapshot(
                rightEnabled = engineEnabled,
                leftEnabled = shoulderHalfEnabled,
                manualEnabled = ::manualTapPair.isInitialized && manualTapPair.isEnabled,
            )
            setRightEngineEnabled(false)
            setLeftEngineEnabled(false)
            if (::manualTapPair.isInitialized) manualTapPair.setEnabled(false)
            updateButtonVisual()
            showMessage("PixelTrigger V5 OFF • الحالة محفوظة")
            return
        }

        globalSuspendSnapshot = null
        setRightEngineEnabled(saved.rightEnabled)
        setLeftEngineEnabled(saved.leftEnabled)
        if (::manualTapPair.isInitialized) manualTapPair.setEnabled(saved.manualEnabled)
        updateButtonVisual()
        showMessage("تمت استعادة حالة PixelTrigger السابقة")
    }

    private fun activeGroupHasArmedDetector(): Boolean {
        if (activeGroup != GROUP_FIVE_INDEX) {
            return detectionEngines[activeGroup].state == DetectionEngine.State.ARMED
        }
        var slot = 0
        while (slot < GROUP_FIVE_SENSOR_COUNT) {
            if (groupFiveDetector(slot).state == DetectionEngine.State.ARMED) return true
            slot++
        }
        return false
    }

    private fun updateButtonVisual() {
        val button = menuButton ?: return
        val manualOff = !::manualTapPair.isInitialized || !manualTapPair.isEnabled
        val everythingOff = !engineEnabled && !shoulderHalfEnabled && manualOff
        val fill = when {
            circleEditMode || (::manualTapPair.isInitialized && manualTapPair.isEditing) -> Color.rgb(30, 165, 92)
            everythingOff -> Color.rgb(95, 95, 104)
            !engineEnabled -> Color.rgb(122, 92, 55)
            tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE -> Color.rgb(165, 70, 190)
            activeGroupHasArmedDetector() -> Color.rgb(32, 170, 88)
            else -> Color.rgb(79, 52, 185)
        }
        button.text = when {
            circleEditMode || (::manualTapPair.isInitialized && manualTapPair.isEditing) -> "✓"
            everythingOff -> "OFF"
            else -> "${activeGroup + 1}"
        }
        button.textSize = if (everythingOff) 10f else 17f
        button.background = roundedBackground(fill, Color.rgb(155, 135, 255), 18f)
    }

    private fun toggleMenu() {
        if (menuPanel != null) closeMenu() else showMenu()
    }

    private fun showMenu() {
    if (menuPanel != null) return
    tapEngine.refreshCapability()
    setConfigurationTouchability(false)

    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(10))
        background = roundedBackground(Color.rgb(247, 247, 251), Color.rgb(146, 142, 167), 18f)
    }
    val header = TextView(this).apply {
        text = "PixelTrigger V6  •  Group ${activeGroup + 1}"
        textSize = 16f
        setTextColor(Color.rgb(24, 23, 32))
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(9), dp(8), dp(9))
        background = roundedBackground(Color.rgb(228, 224, 247), Color.rgb(170, 159, 224), 12f)
    }
    root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(6), 0, 0)
    }
    menuStatusText = TextView(this).apply {
        text = combinedStatusText()
        textSize = 12f
        setTextColor(Color.rgb(42, 42, 52))
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(4), dp(6), dp(6))
    }
    content.addView(menuStatusText, matchWrap())

    val sectionShortcuts = mutableListOf<TextView>()
    val sectionBodies = mutableListOf<LinearLayout>()
    fun addAccordionSection(title: String, accent: Int, buildBody: (LinearLayout) -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(3), dp(4), dp(6))
        }
        buildBody(body)
        val shortcut = TextView(this).apply {
            text = "▸ $title"
            tag = title
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedBackground(Color.rgb(244, 242, 250), accent, 12f)
            isClickable = true
            isFocusable = true
        }
        shortcut.setOnClickListener {
            val opening = body.visibility != View.VISIBLE
            var i = 0
            while (i < sectionBodies.size) {
                sectionBodies[i].visibility = View.GONE
                val other = sectionShortcuts[i]
                other.text = "▸ ${other.tag as String}"
                i++
            }
            if (opening) {
                body.visibility = View.VISIBLE
                shortcut.text = "▾ $title"
            }
        }
        sectionShortcuts.add(shortcut)
        sectionBodies.add(body)
        content.addView(shortcut, matchWrap(dp(48)))
        content.addView(body, matchWrap())
    }

    addAccordionSection("النصف الأيمن", Color.rgb(83, 58, 170)) { body ->
        body.addView(sectionLabel("PIXELPROBE  •  GROUP ${activeGroup + 1}/$GROUP_COUNT", Color.rgb(83, 58, 170)), matchWrap())
        val positionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        positionRow.addView(smallCard("↺ المنتصف") { resetMonitorCircleToCenter() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        positionRow.addView(smallCard("✥ تعديل الموضع") { beginCirclePositionEditing() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        body.addView(positionRow, matchWrap(dp(60)))

        val monitorPlural = activeGroup == GROUP_FIVE_INDEX
        body.addView(
            smallCard(
                if (circlesVisible) {
                    "◉ إخفاء 80%"
                } else {
                    "○ إظهار 100%"
                },
            ) {
                setCirclesVisible(!circlesVisible)
                closeMenu()
            },
            matchWrap(dp(58)),
        )

        if (activeGroup == GROUP_FIVE_INDEX) {
            body.addView(sectionLabel("GROUP 5  •  ضغطات كل FIRE  •  فارق 50ms", Color.rgb(83, 58, 170)), matchWrap())
            val burstRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val burstValue = TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(48, 42, 70))
                background = roundedBackground(Color.rgb(239, 236, 252), Color.rgb(129, 110, 202), 12f)
            }
            fun refreshBurstValue() {
                burstValue.text = "×$groupFiveTapsPerFire لكل FIRE"
            }
            burstRow.addView(smallCard("−") {
                setGroupFiveTapsPerFire(groupFiveTapsPerFire - 1)
                refreshBurstValue()
            }, LinearLayout.LayoutParams(dp(64), dp(46)))
            burstRow.addView(burstValue, LinearLayout.LayoutParams(0, dp(46), 1f))
            burstRow.addView(smallCard("+") {
                setGroupFiveTapsPerFire(groupFiveTapsPerFire + 1)
                refreshBurstValue()
            }, LinearLayout.LayoutParams(dp(64), dp(46)))
            refreshBurstValue()
            body.addView(burstRow, matchWrap(dp(48)))
        }

        val rightToggle = smallCard(if (engineEnabled) "■ إيقاف النصف الأيمن" else "▶ تشغيل النصف الأيمن") {}
        rightToggle.setOnClickListener {
            toggleRightEngine()
            rightToggle.text = if (engineEnabled) "■ إيقاف النصف الأيمن" else "▶ تشغيل النصف الأيمن"
            menuStatusText?.text = combinedStatusText()
        }
        body.addView(rightToggle, matchWrap(dp(54)))
    }

    addAccordionSection("النصف الأيسر", Color.rgb(150, 49, 76)) { body ->
        val leftToggle = smallCard(if (shoulderHalfEnabled) "■ إيقاف النصف الأيسر" else "▶ تشغيل النصف الأيسر") {}
        leftToggle.setOnClickListener {
            toggleLeftEngine()
            leftToggle.text = if (shoulderHalfEnabled) "■ إيقاف النصف الأيسر" else "▶ تشغيل النصف الأيسر"
            menuStatusText?.text = combinedStatusText()
        }
        body.addView(leftToggle, matchWrap(dp(54)))
        body.addView(sectionLabel("SHOULDER  •  R / L", Color.rgb(150, 49, 76)), matchWrap())
        body.addView(shoulderControlCard(), matchWrap())
    }

    addAccordionSection("الدائرة R/L", Color.rgb(155, 95, 25)) { body ->
        if (!::manualTapPair.isInitialized) {
            body.addView(sectionLabel("الدائرة اليدوية غير جاهزة", Color.rgb(155, 95, 25)), matchWrap())
        } else {
            val manualRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val enabledButton = microCard(if (manualTapPair.isEnabled) "■ إيقاف" else "▶ تشغيل") {}
            enabledButton.setOnClickListener {
                manualTapPair.toggleEnabled()
                enabledButton.text = if (manualTapPair.isEnabled) "■ إيقاف" else "▶ تشغيل"
                updateButtonVisual()
                menuStatusText?.text = combinedStatusText()
            }
            manualRow.addView(enabledButton, LinearLayout.LayoutParams(0, dp(38), 1f))
            manualRow.addView(microCard("✥ تعديل") {
                closeMenu()
                manualTapPair.beginEditing()
                updateButtonVisual()
                showMessage("حرّك دائرة R/L اليدوية ثم افتح القائمة واضغط حفظ")
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            manualRow.addView(microCard("✓ حفظ") {
                manualTapPair.finishEditing()
                updateButtonVisual()
                closeMenu()
                showMessage("تم حفظ موضع دائرة R/L اليدوية")
            }, LinearLayout.LayoutParams(0, dp(38), 1f))
            val visibleButton = microCard(if (manualTapPair.isVisible) "◉ إخفاء 80%" else "○ إظهار 100%") {}
            visibleButton.setOnClickListener {
                manualTapPair.toggleVisible()
                visibleButton.text = if (manualTapPair.isVisible) "◉ إخفاء 80%" else "○ إظهار 100%"
            }
            manualRow.addView(visibleButton, LinearLayout.LayoutParams(0, dp(38), 1f))
            body.addView(manualRow, matchWrap(dp(40)))
            body.addView(
                sectionLabel("مراقبة المركز 0.3mm • أبيض/أصفر فاتح = ON • غير ذلك = OFF", Color.rgb(155, 95, 25)),
                matchWrap(),
            )

            val manualHoldRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val manualHoldLabel = TextView(this).apply {
                text = "HOLD الدائرة"
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.rgb(92, 58, 20))
            }
            val manualHoldValue = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.rgb(92, 58, 20))
            }
            fun refreshManualHold() {
                manualHoldValue.text = manualTapPair.holdLabel
                menuStatusText?.text = combinedStatusText()
            }
            val manualHoldMinus = menuButton("−") {
                manualTapPair.setHoldQuarters(manualTapPair.holdQuarters - 1)
                refreshManualHold()
            }
            val manualHoldPlus = menuButton("+") {
                manualTapPair.setHoldQuarters(manualTapPair.holdQuarters + 1)
                refreshManualHold()
            }
            manualHoldRow.addView(manualHoldLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
            manualHoldRow.addView(manualHoldMinus, LinearLayout.LayoutParams(dp(48), dp(42)))
            manualHoldRow.addView(manualHoldValue, LinearLayout.LayoutParams(dp(88), dp(42)))
            manualHoldRow.addView(manualHoldPlus, LinearLayout.LayoutParams(dp(48), dp(42)))
            refreshManualHold()
            body.addView(manualHoldRow, matchWrap(dp(46)))

            val bindRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val bindRealR = microCard("") {}
            val bindRealL = microCard("") {}
            fun refreshBindingButtons() {
                bindRealR.text = if (manualTapPair.bindingLabel == "R") "● ربط بـ L" else "○ ربط بـ L"
                bindRealL.text = if (manualTapPair.bindingLabel == "L") "● ربط بـ R" else "○ ربط بـ R"
                menuStatusText?.text = combinedStatusText()
            }
            bindRealR.setOnClickListener {
                manualTapPair.bindToR()
                refreshBindingButtons()
                showMessage("تم ربط الدائرة اليدوية بـ L")
            }
            bindRealL.setOnClickListener {
                manualTapPair.bindToL()
                refreshBindingButtons()
                showMessage("تم ربط الدائرة اليدوية بـ R")
            }
            bindRow.addView(bindRealR, LinearLayout.LayoutParams(0, dp(36), 1f))
            bindRow.addView(bindRealL, LinearLayout.LayoutParams(0, dp(36), 1f))
            refreshBindingButtons()
            body.addView(bindRow, matchWrap(dp(38)))
        }
    }

    content.addView(menuButton("إغلاق كل شيء وإغلاق التطبيق") { shutdownAndExitApp() }, matchWrap(dp(50), danger = true))

    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }
    root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

    val margin = dp(10)
    val availableWidth = max(screenWidth - margin * 2, 1)
    val availableHeight = max(screenHeight - margin * 2, 1)
    val width = min(dp(430), availableWidth).coerceAtLeast(min(dp(230), availableWidth))
    val height = min(dp(590), availableHeight).coerceAtLeast(min(dp(220), availableHeight))
    val lp = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = preferences.getInt(KEY_MENU_X, ((screenWidth - width) / 2).coerceAtLeast(margin))
        y = preferences.getInt(KEY_MENU_Y, ((screenHeight - height) / 2).coerceAtLeast(margin))
    }
    menuPanel = root
    menuPanelParams = lp
    clampMenuPosition(lp)
    windowManager.addView(root, lp)
    attachMenuDrag(header, root, lp)
}

    private fun shoulderControlCard(): View {
    val shoulderPrefs = getSharedPreferences(ShoulderCaptureService.PREFS_NAME, MODE_PRIVATE)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(Color.rgb(255, 238, 242), Color.rgb(226, 105, 133), 14f)
    }

    fun readQuarters(prefix: String): Int {
        val key = "shoulder_${prefix}_hold_quarters"
        if (shoulderPrefs.contains(key)) return shoulderPrefs.getInt(key, 0).coerceIn(0, 20)
        val migrated = if (shoulderPrefs.getBoolean("shoulder_${prefix}_hold", false)) {
            shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5) * 4
        } else {
            0
        }
        shoulderPrefs.edit().putInt(key, migrated).apply()
        return migrated
    }

    fun saveQuarters(prefix: String, quarters: Int) {
        val value = quarters.coerceIn(0, 20)
        val editor = shoulderPrefs.edit()
            .putInt("shoulder_${prefix}_hold_quarters", value)
            .putBoolean("shoulder_${prefix}_hold", value > 0)
        if (value > 0) {
            editor.putInt("shoulder_${prefix}_seconds", ((value + 2) / 4).coerceIn(1, 5))
        }
        editor.apply()
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply {
                action = ShoulderCaptureService.ACTION_SYNC_CONFIG
            })
        }
    }

    fun formatQuarters(quarters: Int): String {
        if (quarters <= 0) return "0s • Flash"
        val whole = quarters / 4
        val fraction = when (quarters % 4) {
            0 -> ""
            1 -> ".25"
            2 -> ".5"
            else -> ".75"
        }
        return "$whole${fraction}s"
    }

    fun sideRow(label: String, prefix: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sideLabel = TextView(this).apply {
            text = "$label HOLD"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.rgb(68, 35, 45))
        }
        val value = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.rgb(68, 35, 45))
        }
        fun refresh() {
            value.text = formatQuarters(readQuarters(prefix))
            menuStatusText?.text = combinedStatusText()
        }
        val minus = menuButton("−") {
            saveQuarters(prefix, readQuarters(prefix) - 1)
            refresh()
        }
        val plus = menuButton("+") {
            saveQuarters(prefix, readQuarters(prefix) + 1)
            refresh()
        }
        row.addView(sideLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(42)))
        row.addView(value, LinearLayout.LayoutParams(dp(88), dp(42)))
        row.addView(plus, LinearLayout.LayoutParams(dp(48), dp(42)))
        refresh()
        return row
    }

    card.addView(sideRow("R", "r"), matchWrap(dp(48)))
    card.addView(sideRow("L", "l"), matchWrap(dp(48)))

    val editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    editRow.addView(menuButton("تعديل R") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_R) }, LinearLayout.LayoutParams(0, dp(46), 1f))
    editRow.addView(menuButton("تعديل L") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_L) }, LinearLayout.LayoutParams(0, dp(46), 1f))
    editRow.addView(menuButton("✓ حفظ") { shoulderAction(ShoulderCaptureService.ACTION_DONE_EDIT) }, LinearLayout.LayoutParams(0, dp(46), 1f))
    card.addView(editRow, matchWrap(dp(48)))

    val resetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    resetRow.addView(menuButton("↺ R للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_R) }, LinearLayout.LayoutParams(0, dp(46), 1f))
    resetRow.addView(menuButton("↺ L للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_L) }, LinearLayout.LayoutParams(0, dp(46), 1f))
    card.addView(resetRow, matchWrap(dp(48)))
    return card
}

    private fun sectionLabel(value: String, color: Int) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(10), dp(4), dp(5))
    }

    private fun smallCard(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(48, 42, 70))
        setPadding(dp(6), dp(5), dp(6), dp(5))
        background = roundedBackground(Color.rgb(239, 236, 252), Color.rgb(129, 110, 202), 12f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun microCard(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 9.5f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(65, 48, 24))
        setPadding(dp(2), dp(2), dp(2), dp(2))
        background = roundedBackground(Color.rgb(255, 243, 220), Color.rgb(205, 143, 55), 8f)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun shoulderAction(actionValue: String) {
        runCatching { startService(Intent(this, ShoulderCaptureService::class.java).apply { action = actionValue }) }
        closeMenu()
    }

    private fun combinedStatusText(): String {
        val manual = if (::manualTapPair.isInitialized) {
            "MANUAL ${manualTapPair.displayBindingLabel} ${manualTapPair.statusLabel}"
        } else {
            "MANUAL OFF"
        }
        return "PixelProbe: ${engineStatusText()}  •  R/L: ${ShoulderCaptureService.statusSummary()}  •  $manual"
    }

    private fun engineStatusText(): String {
        if (!engineEnabled) return "OFF"
        if (tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE) return "WAITING_SHIZUKU"

        if (activeGroup == GROUP_FIVE_INDEX) {
            var armed = 0
            var waitingRearm = 0
            var slot = 0
            while (slot < GROUP_FIVE_SENSOR_COUNT) {
                when (groupFiveDetector(slot).state) {
                    DetectionEngine.State.ARMED -> armed++
                    DetectionEngine.State.WAITING_REARM -> waitingRearm++
                    else -> Unit
                }
                slot++
            }
            return when {
                armed > 0 -> "ARMED $armed/$GROUP_FIVE_SENSOR_COUNT"
                waitingRearm > 0 -> "WAITING_REARM"
                else -> "WAITING_FOR_WHITE"
            }
        }

        return when (detectionEngines[activeGroup].state) {
            DetectionEngine.State.ARMED -> "ARMED"
            DetectionEngine.State.WAITING_REARM -> "WAITING_REARM"
            else -> "WAITING_FOR_WHITE"
        }
    }

    private fun detectorStatus(engine: DetectionEngine, inputReady: Boolean, forced: SensorStatus?): SensorStatus =
        forced ?: when {
            !engineEnabled -> SensorStatus.OFF
            !inputReady -> SensorStatus.INPUT_NOT_READY
            engine.state == DetectionEngine.State.ARMED -> SensorStatus.ARMED
            engine.state == DetectionEngine.State.WAITING_REARM -> SensorStatus.FIRED
            else -> SensorStatus.WAITING
        }

    private fun refreshSensorStatus(inputReady: Boolean, forced: SensorStatus? = null) {
        mainHandler.post {
            val group = activeGroup
            if (group == GROUP_FIVE_INDEX) {
                var slot = 0
                while (slot < GROUP_FIVE_SENSOR_COUNT) {
                    groupFiveView(slot)?.setStatus(detectorStatus(groupFiveDetector(slot), inputReady, forced))
                    slot++
                }
            } else {
                val view = sensorViews[group]
                val engine = detectionEngines[group]
                view?.setStatus(detectorStatus(engine, inputReady, forced))
            }
            updateButtonVisual()
            menuStatusText?.text = combinedStatusText()
        }
    }

    private fun attachMenuDrag(handle: View, panel: View, params: WindowManager.LayoutParams) {
        var grabOffsetX = 0f
        var grabOffsetY = 0f
        var framePending = false
        fun updateNextFrame() {
            if (framePending) return
            framePending = true
            panel.postOnAnimation {
                framePending = false
                if (menuPanel === panel) runCatching { windowManager.updateViewLayout(panel, params) }
            }
        }
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabOffsetX = event.rawX - params.x
                    grabOffsetY = event.rawY - params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (event.rawX - grabOffsetX).roundToInt()
                    params.y = (event.rawY - grabOffsetY).roundToInt()
                    clampMenuPosition(params)
                    updateNextFrame()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clampMenuPosition(params)
                    runCatching { windowManager.updateViewLayout(panel, params) }
                    preferences.edit().putInt(KEY_MENU_X, params.x).putInt(KEY_MENU_Y, params.y).apply()
                    true
                }
                else -> true
            }
        }
    }

    private fun closeMenu() {
        menuPanel?.let { runCatching { windowManager.removeView(it) } }
        menuPanel = null
        menuPanelParams = null
        menuStatusText = null
        setConfigurationTouchability(false)
    }

    private fun setCirclesVisible(visible: Boolean) {
        circlesVisible = visible
        preferences.edit().putBoolean(KEY_CIRCLES_VISIBLE, visible).apply()
        applyGroupVisibility()
    }

    private fun applyGroupVisibility() {
        val activeAlpha = when {
            !engineEnabled -> 0f
            circlesVisible -> 1f
            else -> RIGHT_HIDDEN_ALPHA
        }
        var i = 0
        while (i < GROUP_COUNT) {
            sensorViews[i]?.let { view ->
                view.visibility = if (i == activeGroup) View.VISIBLE else View.INVISIBLE
                if (i == activeGroup) view.alpha = activeAlpha
            }
            i++
        }
        val groupFiveActive = activeGroup == GROUP_FIVE_INDEX
        groupFiveExtraViews.forEach { view ->
            view?.visibility = if (groupFiveActive) View.VISIBLE else View.INVISIBLE
            if (groupFiveActive) view?.alpha = activeAlpha
        }
        targetView?.let { view ->
            view.visibility = View.VISIBLE
            view.alpha = activeAlpha
        }
    }

    private fun refreshDisplayGeometry() {
        val bounds = currentScreenBounds()
        val newWidth = bounds.width()
        val newHeight = bounds.height()
        if (newWidth <= 0 || newHeight <= 0 || (newWidth == screenWidth && newHeight == screenHeight)) return
        screenWidth = newWidth
        screenHeight = newHeight
        densityDpi = resources.displayMetrics.densityDpi
        updateCaptureGeometry()

        captureHandler?.post {
            val replacement = createImageReader(captureWidth, captureHeight)
            val old = imageReader
            imageReader = replacement
            virtualDisplay?.resize(captureWidth, captureHeight, captureDensityDpi)
            virtualDisplay?.surface = replacement.surface
            old?.close()
            resetAllRightDetectors()
        }

        restoreRightOverlayPositionsForCurrentProfile()
        if (::manualTapPair.isInitialized) manualTapPair.updateBounds(screenWidth, screenHeight)
        menuButtonParams?.let { lp ->
            clampPosition(lp)
            menuButton?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
        }
        menuPanelParams?.let { lp ->
            val panel = menuPanel ?: return@let
            lp.width = min(dp(430), max(screenWidth - dp(20), 1))
            lp.height = min(dp(590), max(screenHeight - dp(20), 1))
            clampMenuPosition(lp)
            runCatching { windowManager.updateViewLayout(panel, lp) }
        }
        applyGroupVisibility()
    }

    private fun currentScreenBounds(): Rect = if (Build.VERSION.SDK_INT >= 30) {
        windowManager.currentWindowMetrics.bounds
    } else {
        @Suppress("DEPRECATION")
        val point = android.graphics.Point().also { windowManager.defaultDisplay.getRealSize(it) }
        Rect(0, 0, point.x, point.y)
    }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        params.x = params.x.coerceIn(0, max(screenWidth - max(params.width, 1), 0))
        params.y = params.y.coerceIn(0, max(screenHeight - max(params.height, 1), 0))
    }

    private fun clampCirclePosition(params: WindowManager.LayoutParams, visibleDiameter: Int) {
        val halfWindowW = max(params.width, 1) / 2f
        val halfWindowH = max(params.height, 1) / 2f
        val radius = max(visibleDiameter, 1) / 2f
        val centerX = (params.x + halfWindowW).coerceIn(radius, max(screenWidth - radius, radius))
        val centerY = (params.y + halfWindowH).coerceIn(radius, max(screenHeight - radius, radius))
        params.x = (centerX - halfWindowW).roundToInt()
        params.y = (centerY - halfWindowH).roundToInt()
    }

    private fun clampMenuPosition(params: WindowManager.LayoutParams) {
        params.x = params.x.coerceIn(0, max(screenWidth - params.width, 0))
        params.y = params.y.coerceIn(0, max(screenHeight - params.height, 0))
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        baseOverlayFlags(),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun baseOverlayFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun menuButton(textValue: String, action: () -> Unit): Button = Button(this).apply {
        text = textValue
        textSize = 13f
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWrap(
        height: Int = LinearLayout.LayoutParams.WRAP_CONTENT,
        danger: Boolean = false,
    ): LinearLayout.LayoutParams {
        @Suppress("UNUSED_VARIABLE") val ignored = danger
        return LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { bottomMargin = dp(5) }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun mmToPx(mm: Float): Int {
        val metrics = resources.displayMetrics
        val x = metrics.xdpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val y = metrics.ydpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        return (mm * ((x + y) / 2f) / 25.4f).roundToInt()
    }

    private fun rightSensorPositionKey(group: Int, slot: Int): String =
        "right.monitor.g${group + 1}.s${slot + 1}"

    private fun loadRightSensorPosition(
        group: Int,
        slot: Int,
        fallbackX: Int,
        fallbackY: Int,
    ): OrientationPositionStore.Position = positionStore.load(
        keyPrefix = rightSensorPositionKey(group, slot),
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        overlayWidth = sensorTouchSize,
        overlayHeight = sensorTouchSize,
        fallbackX = fallbackX,
        fallbackY = fallbackY,
        legacyXKey = sensorKeyX(group, slot),
        legacyYKey = sensorKeyY(group, slot),
    )

    private fun saveRightSensorPosition(group: Int, slot: Int, x: Int, y: Int) {
        positionStore.save(
            keyPrefix = rightSensorPositionKey(group, slot),
            x = x,
            y = y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = sensorTouchSize,
            overlayHeight = sensorTouchSize,
            legacyXKey = sensorKeyX(group, slot),
            legacyYKey = sensorKeyY(group, slot),
        )
    }

    private fun loadTargetPosition(
        fallbackX: Int,
        fallbackY: Int,
    ): OrientationPositionStore.Position = positionStore.load(
        keyPrefix = RIGHT_TARGET_POSITION_KEY,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        overlayWidth = targetTouchSize,
        overlayHeight = targetTouchSize,
        fallbackX = fallbackX,
        fallbackY = fallbackY,
        legacyXKey = KEY_TARGET_X,
        legacyYKey = KEY_TARGET_Y,
    )

    private fun saveTargetPosition(x: Int, y: Int) {
        positionStore.save(
            keyPrefix = RIGHT_TARGET_POSITION_KEY,
            x = x,
            y = y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = targetTouchSize,
            overlayHeight = targetTouchSize,
            legacyXKey = KEY_TARGET_X,
            legacyYKey = KEY_TARGET_Y,
        )
    }

    /**
     * Rotation never clamps the previous orientation in-place. Every
     * overlay is reloaded from the profile belonging to the new geometry.
     * If that profile has never been edited, OrientationPositionStore only
     * projects the opposite profile temporarily and leaves it untouched.
     */
    private fun restoreRightOverlayPositionsForCurrentProfile() {
        var group = 0
        while (group < GROUP_COUNT) {
            val lp = sensorParams[group]
            val view = sensorViews[group]
            if (lp != null && view != null) {
                val fallbackX = if (group == GROUP_FIVE_INDEX) groupFiveDefaultX(0)
                else screenWidth / 2 - sensorTouchSize / 2
                val fallbackY = screenHeight / 2 - sensorTouchSize / 2
                val saved = loadRightSensorPosition(group, 0, fallbackX, fallbackY)
                lp.x = saved.x
                lp.y = saved.y
                clampCirclePosition(lp, sensorVisibleDiameter)
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            group++
        }

        var extra = 0
        while (extra < GROUP_FIVE_EXTRA_COUNT) {
            val slot = extra + 1
            val lp = groupFiveExtraParams[extra]
            val view = groupFiveExtraViews[extra]
            if (lp != null && view != null) {
                val saved = loadRightSensorPosition(
                    GROUP_FIVE_INDEX,
                    slot,
                    groupFiveDefaultX(slot),
                    groupFiveDefaultY(),
                )
                lp.x = saved.x
                lp.y = saved.y
                clampCirclePosition(lp, sensorVisibleDiameter)
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
            extra++
        }

        // Rotation itself must never overwrite an orientation profile. We only
        // de-overlap the in-memory geometry here; a user save remains the only
        // normal persistence path during orientation changes.
        repairGroupFiveVisualOverlap(persist = false)

        val targetLp = targetParams
        val target = targetView
        if (targetLp != null && target != null) {
            val saved = loadTargetPosition(
                screenWidth / 2 + dp(70),
                screenHeight / 2 - targetTouchSize / 2,
            )
            targetLp.x = saved.x
            targetLp.y = saved.y
            clampCirclePosition(targetLp, targetVisibleDiameter)
            runCatching { windowManager.updateViewLayout(target, targetLp) }
        }
    }

    private fun sensorKeyX(group: Int, slot: Int = 0): String = when {
        group == 0 && slot == 0 -> KEY_SENSOR_X
        else -> "sensor_g${group + 1}_${slot + 1}_x"
    }

    private fun sensorKeyY(group: Int, slot: Int = 0): String = when {
        group == 0 && slot == 0 -> KEY_SENSOR_Y
        else -> "sensor_g${group + 1}_${slot + 1}_y"
    }

    private fun groupFiveDefaultSpacing(): Int = max(dp(64), sensorTouchSize + dp(12))

    private fun groupFiveDefaultX(slot: Int): Int {
        val center = screenWidth / 2 - sensorTouchSize / 2
        val spacing = groupFiveDefaultSpacing()
        return when (slot) {
            1 -> center - spacing
            2 -> center + spacing
            else -> center
        }
    }

    private fun groupFiveDefaultY(): Int = screenHeight / 2 - sensorTouchSize / 2

    private fun groupFiveCurrentProfileExists(): Boolean {
        var slot = 0
        while (slot < GROUP_FIVE_SENSOR_COUNT) {
            if (
                positionStore.hasSaved(
                    rightSensorPositionKey(GROUP_FIVE_INDEX, slot),
                    screenWidth,
                    screenHeight,
                )
            ) return true
            slot++
        }
        return false
    }

    /**
     * Group 5 is architecturally 3 probes (slot 0 + 2 extras). Old/migrated
     * coordinates can nevertheless put two windows on the same visual center,
     * making the user see only two circles. Preserve every valid position and
     * relocate only a colliding later slot.
     */
    private fun repairGroupFiveVisualOverlap(persist: Boolean) {
        val minDistance = max(sensorVisibleDiameter * 2, dp(4)).coerceAtLeast(1)
        val minDistanceSq = minDistance.toLong() * minDistance.toLong()
        val spacing = groupFiveDefaultSpacing()
        val centerX = screenWidth / 2 - sensorTouchSize / 2
        val centerY = screenHeight / 2 - sensorTouchSize / 2

        fun overlapsOther(slot: Int, x: Int, y: Int): Boolean {
            var other = 0
            while (other < GROUP_FIVE_SENSOR_COUNT) {
                if (other != slot) {
                    val otherLp = groupFiveParams(other)
                    if (otherLp != null) {
                        val dx = (x - otherLp.x).toLong()
                        val dy = (y - otherLp.y).toLong()
                        if (dx * dx + dy * dy < minDistanceSq) return true
                    }
                }
                other++
            }
            return false
        }

        var slot = 1
        while (slot < GROUP_FIVE_SENSOR_COUNT) {
            val lp = groupFiveParams(slot)
            val view = groupFiveView(slot)
            if (lp != null && view != null && overlapsOther(slot, lp.x, lp.y)) {
                val candidates = arrayOf(
                    groupFiveDefaultX(slot) to groupFiveDefaultY(),
                    (centerX - spacing) to centerY,
                    (centerX + spacing) to centerY,
                    centerX to (centerY - spacing),
                    centerX to (centerY + spacing),
                    (centerX - spacing) to (centerY - spacing),
                    (centerX + spacing) to (centerY + spacing),
                )

                val oldX = lp.x
                val oldY = lp.y
                var repaired = false
                for ((candidateX, candidateY) in candidates) {
                    lp.x = candidateX
                    lp.y = candidateY
                    clampCirclePosition(lp, sensorVisibleDiameter)
                    if (!overlapsOther(slot, lp.x, lp.y)) {
                        repaired = true
                        break
                    }
                }

                if (!repaired) {
                    lp.x = oldX
                    lp.y = oldY
                } else {
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    groupFiveDetector(slot).resetForSensorMove()
                    if (persist) {
                        saveRightSensorPosition(GROUP_FIVE_INDEX, slot, lp.x, lp.y)
                    }
                }
            }
            slot++
        }
    }

    private fun groupFiveDetector(slot: Int): DetectionEngine =
        if (slot == 0) detectionEngines[GROUP_FIVE_INDEX] else groupFiveExtraEngines[slot - 1]

    private fun groupFiveView(slot: Int): SensorOverlayView? =
        if (slot == 0) sensorViews[GROUP_FIVE_INDEX] else groupFiveExtraViews[slot - 1]

    private fun groupFiveParams(slot: Int): WindowManager.LayoutParams? =
        if (slot == 0) sensorParams[GROUP_FIVE_INDEX] else groupFiveExtraParams[slot - 1]

    private fun showMessage(message: String) {
        mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "PixelTrigger V5", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("PixelTrigger V5")
        .setContentText("4×1 + 1×3 PixelProbe + 1R + 1L")
        .setOngoing(true)
        .build()

    private fun shutdownCompletely() {
        closeMenu()
        runCatching { startService(Intent(this, ShoulderCaptureService::class.java).apply { action = ShoulderCaptureService.ACTION_STOP }) }
        stopSelf()
    }

    private fun shutdownAndExitApp() {
        closeMenu()
        runCatching { startService(Intent(this, ShoulderCaptureService::class.java).apply { action = ShoulderCaptureService.ACTION_STOP }) }
        stopSelf()
        mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 180L)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(displayListener)
        closeMenu()
        sensorViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        groupFiveExtraViews.forEach { it?.let { view -> runCatching { windowManager.removeView(view) } } }
        targetView?.let { runCatching { windowManager.removeView(it) } }
        if (::manualTapPair.isInitialized) manualTapPair.destroy()
        menuButton?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        captureThread?.quitSafely()
        if (::tapEngine.isInitialized) tapEngine.disconnect()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val ACTION_START = "com.pixeltrigger.app.action.START"
        const val ACTION_STOP = "com.pixeltrigger.app.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "pixeltrigger_monitor"
        private const val NOTIFICATION_ID = 41
        private const val PREFS_NAME = "pixeltrigger_prefs"
        private const val GROUP_COUNT = 5
        private const val GROUP_FIVE_INDEX = 4
        private const val GROUP_FIVE_SENSOR_COUNT = 3
        private const val GROUP_FIVE_EXTRA_COUNT = GROUP_FIVE_SENSOR_COUNT - 1
        private const val GROUP_FIVE_MAX_TAPS = 5
        private const val GROUP_FIVE_TAP_GAP_MS = 50L
        private const val MONITOR_DIAMETER_MM = 0.3f
        private const val RIGHT_HIDDEN_ALPHA = 0.20f
        private const val CAPTURE_SCALE = 0.5f
        private const val DISPLAY_REFRESH_DEBOUNCE_MS = 16L
        private const val ENGINE_HOLD_MS = 500L

        private const val KEY_ACTIVE_GROUP = "active_monitor_group"
        private const val KEY_SENSOR_X = "sensor_x"
        private const val KEY_SENSOR_Y = "sensor_y"
        private const val KEY_TARGET_X = "target_x"
        private const val KEY_TARGET_Y = "target_y"
        private const val KEY_BUTTON_X = "button_x"
        private const val KEY_BUTTON_Y = "button_y"
        private const val KEY_MENU_X = "menu_x"
        private const val KEY_MENU_Y = "menu_y"
        private const val KEY_CIRCLES_VISIBLE = "circles_visible"
        private const val KEY_RIGHT_ENGINE_ENABLED = "right_half_enabled"
        private const val KEY_GROUP_FIVE_TAPS_PER_FIRE = "group5_taps_per_fire"
        private const val KEY_WHITE_REARM = "white_rearm_enabled"
        private const val KEY_REARM_DELAY_ENABLED = "rearm_delay_enabled"
        private const val RIGHT_TARGET_POSITION_KEY = "right.target"
    }
}
