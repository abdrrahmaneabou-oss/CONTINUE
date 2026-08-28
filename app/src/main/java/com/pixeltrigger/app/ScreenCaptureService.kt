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
    private var sensorVisibleDiameter = 1
    private var sensorTouchSize = 1
    @Volatile private var activeGroup = 0

    private var targetView: TargetOverlayView? = null
    private var targetParams: WindowManager.LayoutParams? = null
    private var targetTouchSize = 1

    private var menuButton: TextView? = null
    private var menuButtonParams: WindowManager.LayoutParams? = null
    private var menuPanel: View? = null
    private var menuPanelParams: WindowManager.LayoutParams? = null
    private var menuStatusText: TextView? = null

    private var circlesVisible = true
    @Volatile private var engineEnabled = true
    @Volatile private var systemEnabled = true
    @Volatile private var circleEditMode = false
    private var lastInputReady = false

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
        circlesVisible = preferences.getBoolean(KEY_CIRCLES_VISIBLE, true)
        activeGroup = preferences.getInt(KEY_ACTIVE_GROUP, 0).coerceIn(0, GROUP_COUNT - 1)
        detectionEngines.forEach {
            it.whiteRearmEnabled = true
            it.rearmDelayEnabled = false
            it.rearmSeconds = 10
        }
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
        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)
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
        val params = sensorParams[index] ?: return
        val sample = sampleSensor(image, crop, params) ?: return
        val now = SystemClock.elapsedRealtime()
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

    private fun createOverlays() {
        if (sensorViews[0] != null) return
        sensorVisibleDiameter = max(mmToPx(MONITOR_DIAMETER_MM), 1)
        sensorTouchSize = max(dp(48), sensorVisibleDiameter + dp(30))

        var group = 0
        while (group < GROUP_COUNT) {
            val sensor = SensorOverlayView(this, sensorVisibleDiameter)
            val lp = overlayParams(sensorTouchSize, sensorTouchSize).apply {
                x = preferences.getInt(sensorKeyX(group), screenWidth / 2 - sensorTouchSize / 2)
                y = preferences.getInt(sensorKeyY(group), screenHeight / 2 - sensorTouchSize / 2)
            }
            sensorViews[group] = sensor
            sensorParams[group] = lp
            clampCirclePosition(lp, sensorVisibleDiameter)
            windowManager.addView(sensor, lp)
            val savedGroup = group
            attachDrag(sensor, lp, sensorVisibleDiameter) { x, y ->
                detectionEngines[savedGroup].resetForSensorMove()
                preferences.edit().putInt(sensorKeyX(savedGroup), x).putInt(sensorKeyY(savedGroup), y).apply()
            }
            group++
        }

        val targetVisibleDiameter = max(mmToPx(5f), dp(12))
        targetTouchSize = max(dp(52), dp(24) + targetVisibleDiameter)
        val target = TargetOverlayView(this, targetVisibleDiameter)
        val targetLp = overlayParams(targetTouchSize, targetTouchSize).apply {
            x = preferences.getInt(KEY_TARGET_X, screenWidth / 2 + dp(70))
            y = preferences.getInt(KEY_TARGET_Y, screenHeight / 2 - targetTouchSize / 2)
        }
        targetView = target
        targetParams = targetLp
        clampCirclePosition(targetLp, targetVisibleDiameter)
        windowManager.addView(target, targetLp)
        attachDrag(target, targetLp, targetVisibleDiameter) { x, y ->
            preferences.edit().putInt(KEY_TARGET_X, x).putInt(KEY_TARGET_Y, y).apply()
        }

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
            if (!circleEditMode) {
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
                if (circleEditMode) finishCirclePositionEditing() else switchToNextGroup()
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
        detectionEngines[activeGroup].resetForSensorMove()
        detectionEngines[next].resetForSensorMove()
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
        detectionEngines[activeGroup].resetForSensorMove()
        updateButtonVisual()
        showMessage("اسحب دائرة المجموعة ${activeGroup + 1} ودائرة الضغط ثم اضغط الزر للحفظ")
    }

    private fun finishCirclePositionEditing() {
        if (!circleEditMode) return
        circleEditMode = false
        setConfigurationTouchability(false)
        detectionEngines[activeGroup].resetForSensorMove()
        updateButtonVisual()
        showMessage("تم حفظ موضع المجموعة ${activeGroup + 1}")
    }

    private fun resetMonitorCircleToCenter() {
        closeMenu()
        setCirclesVisible(true)
        val lp = sensorParams[activeGroup] ?: return
        val view = sensorViews[activeGroup] ?: return
        lp.x = screenWidth / 2 - sensorTouchSize / 2
        lp.y = screenHeight / 2 - sensorTouchSize / 2
        clampCirclePosition(lp, sensorVisibleDiameter)
        runCatching { windowManager.updateViewLayout(view, lp) }
        preferences.edit().putInt(sensorKeyX(activeGroup), lp.x).putInt(sensorKeyY(activeGroup), lp.y).apply()
        detectionEngines[activeGroup].resetForSensorMove()
        refreshSensorStatus(tapEngine.isReady())
        showMessage("أعيدت دائرة المجموعة ${activeGroup + 1} إلى المنتصف")
    }

    private fun setRightEngineEnabled(enabled: Boolean) {
        engineEnabled = enabled
        detectionEngines.forEach { it.resetForSensorMove() }
        refreshSensorStatus(tapEngine.isReady(), if (enabled) null else SensorStatus.OFF)
        updateButtonVisual()
    }

    private fun toggleRightEngine() = setRightEngineEnabled(!engineEnabled)

    private fun toggleAllEngines() {
        systemEnabled = !systemEnabled
        setRightEngineEnabled(systemEnabled)
        runCatching {
            startService(Intent(this, ShoulderCaptureService::class.java).apply {
                action = ShoulderCaptureService.ACTION_SET_ENABLED
                putExtra(ShoulderCaptureService.EXTRA_ENABLED, systemEnabled)
            })
        }
        showMessage(if (systemEnabled) "PixelTrigger V5 ON" else "PixelTrigger V5 OFF")
    }

    private fun updateButtonVisual() {
        val button = menuButton ?: return
        val state = detectionEngines[activeGroup].state
        val fill = when {
            circleEditMode -> Color.rgb(30, 165, 92)
            !engineEnabled -> Color.rgb(95, 95, 104)
            tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE -> Color.rgb(165, 70, 190)
            state == DetectionEngine.State.ARMED -> Color.rgb(32, 170, 88)
            else -> Color.rgb(79, 52, 185)
        }
        button.text = when {
            circleEditMode -> "✓"
            !engineEnabled -> "OFF"
            else -> "${activeGroup + 1}"
        }
        button.textSize = if (engineEnabled) 17f else 10f
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
            text = "PixelTrigger V5  •  Group ${activeGroup + 1}"
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

        content.addView(sectionLabel("PIXELPROBE  •  GROUP ${activeGroup + 1}/4", Color.rgb(83, 58, 170)), matchWrap())
        val rightRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rightRow1.addView(smallCard("↺ المنتصف") { resetMonitorCircleToCenter() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        rightRow1.addView(smallCard("✥ تعديل الموضع") { beginCirclePositionEditing() }, LinearLayout.LayoutParams(0, dp(58), 1f))
        content.addView(rightRow1, matchWrap(dp(60)))

        val rightRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rightRow2.addView(
            smallCard(if (circlesVisible) "◉ إخفاء الدائرة" else "○ إظهار الدائرة") {
                setCirclesVisible(!circlesVisible)
                closeMenu()
            },
            LinearLayout.LayoutParams(0, dp(58), 1f),
        )
        rightRow2.addView(
            smallCard(if (engineEnabled) "■ إيقاف المراقبة" else "▶ تشغيل المراقبة") {
                toggleRightEngine()
                closeMenu()
            },
            LinearLayout.LayoutParams(0, dp(58), 1f),
        )
        content.addView(rightRow2, matchWrap(dp(60)))

        content.addView(sectionLabel("SHOULDER  •  R / L", Color.rgb(150, 49, 76)), matchWrap())
        content.addView(shoulderControlCard(), matchWrap())

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

        fun sideRow(label: String, prefix: String): View {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val hold = Switch(this).apply {
                text = "$label HOLD"
                isChecked = shoulderPrefs.getBoolean("shoulder_${prefix}_hold", false)
                setTextColor(Color.rgb(68, 35, 45))
            }
            val value = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.rgb(68, 35, 45))
            }
            fun refresh() {
                value.text = if (hold.isChecked) "${shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1).coerceIn(1, 5)}s" else "Flash"
            }
            hold.setOnCheckedChangeListener { _, checked ->
                shoulderPrefs.edit().putBoolean("shoulder_${prefix}_hold", checked).apply()
                refresh()
            }
            val minus = menuButton("−") {
                val n = (shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1) - 1).coerceIn(1, 5)
                shoulderPrefs.edit().putInt("shoulder_${prefix}_seconds", n).apply()
                refresh()
            }
            val plus = menuButton("+") {
                val n = (shoulderPrefs.getInt("shoulder_${prefix}_seconds", 1) + 1).coerceIn(1, 5)
                shoulderPrefs.edit().putInt("shoulder_${prefix}_seconds", n).apply()
                refresh()
            }
            row.addView(hold, LinearLayout.LayoutParams(0, dp(46), 1f))
            row.addView(minus, LinearLayout.LayoutParams(dp(48), dp(42)))
            row.addView(value, LinearLayout.LayoutParams(dp(54), dp(42)))
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

    private fun shoulderAction(actionValue: String) {
        runCatching { startService(Intent(this, ShoulderCaptureService::class.java).apply { action = actionValue }) }
        closeMenu()
    }

    private fun combinedStatusText(): String =
        "PixelProbe: ${engineStatusText()}  •  R/L: ${ShoulderCaptureService.statusSummary()}"

    private fun engineStatusText(): String = when {
        !engineEnabled -> "OFF"
        tapEngine.capability != InputCapability.CONCURRENT_TOUCH_SAFE -> "WAITING_SHIZUKU"
        detectionEngines[activeGroup].state == DetectionEngine.State.ARMED -> "ARMED"
        detectionEngines[activeGroup].state == DetectionEngine.State.WAITING_REARM -> "WAITING_REARM"
        else -> "WAITING_FOR_WHITE"
    }

    private fun refreshSensorStatus(inputReady: Boolean, forced: SensorStatus? = null) {
        mainHandler.post {
            val view = sensorViews[activeGroup]
            val engine = detectionEngines[activeGroup]
            val status = forced ?: when {
                !engineEnabled -> SensorStatus.OFF
                !inputReady -> SensorStatus.INPUT_NOT_READY
                engine.state == DetectionEngine.State.ARMED -> SensorStatus.ARMED
                engine.state == DetectionEngine.State.WAITING_REARM -> SensorStatus.FIRED
                else -> SensorStatus.WAITING
            }
            view?.setStatus(status)
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
        var i = 0
        while (i < GROUP_COUNT) {
            sensorViews[i]?.visibility = if (circlesVisible && i == activeGroup) View.VISIBLE else View.INVISIBLE
            i++
        }
        targetView?.visibility = if (circlesVisible) View.VISIBLE else View.INVISIBLE
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
            detectionEngines.forEach { it.resetForSensorMove() }
        }

        sensorParams.forEachIndexed { i, lp ->
            val view = sensorViews[i]
            if (lp != null && view != null) {
                clampCirclePosition(lp, sensorVisibleDiameter)
                runCatching { windowManager.updateViewLayout(view, lp) }
            }
        }
        targetParams?.let { lp ->
            targetView?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
        }
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

    private fun sensorKeyX(group: Int): String = if (group == 0) KEY_SENSOR_X else "sensor_g${group + 1}_1_x"
    private fun sensorKeyY(group: Int): String = if (group == 0) KEY_SENSOR_Y else "sensor_g${group + 1}_1_y"

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
        .setContentText("4×1 PixelProbe + 1R + 1L")
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
        targetView?.let { runCatching { windowManager.removeView(it) } }
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
        private const val GROUP_COUNT = 4
        private const val MONITOR_DIAMETER_MM = 0.3f
        private const val CAPTURE_SCALE = 0.5f
        private const val DISPLAY_REFRESH_DEBOUNCE_MS = 16L
        private const val ENGINE_HOLD_MS = 750L

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
        private const val KEY_WHITE_REARM = "white_rearm_enabled"
        private const val KEY_REARM_DELAY_ENABLED = "rearm_delay_enabled"
    }
}
