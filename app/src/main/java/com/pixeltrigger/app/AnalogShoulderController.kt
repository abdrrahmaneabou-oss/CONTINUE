package com.pixeltrigger.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * V6 split instant shoulder controls.
 *
 * IMPORTANT: visible R/L labels are intentionally swapped from the internal
 * shoulder side names because this device's GameSpace presents the opposite
 * label. Backend mapping is not changed:
 * - visible R -> existing internal L path
 * - visible L -> existing internal R path
 *
 * Both circles are immediate: DOWN starts the held shoulder key and UP/CANCEL
 * releases it. There is no WAIT, drag classification, FAST promotion or brake.
 */
class AnalogShoulderController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: SharedPreferences,
) {
    private enum class EditTarget { NONE, R_BASE, L_CIRCLE }

    private val positionStore = OrientationPositionStore(prefs)

    private var screenWidth = 1
    private var screenHeight = 1
    private var baseSizeHundredths = normalizeSize(prefs.getInt(KEY_BASE_SIZE, DEFAULT_BASE_SIZE))
    private var rCircleSizeHundredths = normalizeSize(prefs.getInt(KEY_R_CIRCLE_SIZE, DEFAULT_R_CIRCLE_SIZE))
    private var lCircleSizeHundredths = normalizeSize(prefs.getInt(KEY_L_CIRCLE_SIZE, DEFAULT_L_CIRCLE_SIZE))

    var rEnabled: Boolean = prefs.getBoolean(KEY_R_ENABLED, true)
        private set
    var lEnabled: Boolean = prefs.getBoolean(KEY_L_ENABLED, true)
        private set
    var rVisible: Boolean = prefs.getBoolean(KEY_R_VISIBLE, prefs.getBoolean(LEGACY_KEY_VISIBLE, true))
        private set
    var lVisible: Boolean = prefs.getBoolean(KEY_L_VISIBLE, true)
        private set

    private var inputEnabled = true
    private var editTarget = EditTarget.NONE
    val isEditing: Boolean get() = editTarget != EditTarget.NONE

    private var baseView: BaseCircleView? = null
    private var rCircleView: TextView? = null
    private var lCircleView: TextView? = null
    private var baseParams: WindowManager.LayoutParams? = null
    private var rCircleParams: WindowManager.LayoutParams? = null
    private var lCircleParams: WindowManager.LayoutParams? = null

    private var rPressDown = false
    private var lPressDown = false

    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val rCircleSizeLabel: String get() = formatSize(rCircleSizeHundredths)
    val lCircleSizeLabel: String get() = formatSize(lCircleSizeHundredths)

    fun create(width: Int, height: Int) {
        if (baseView != null) return
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        val baseTouch = baseTouchSizePx()
        val savedBase = positionStore.load(
            keyPrefix = R_BASE_POSITION_KEY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = baseTouch,
            overlayHeight = baseTouch,
            fallbackX = screenWidth / 2 - baseTouch / 2,
            fallbackY = screenHeight / 2 - baseTouch / 2,
        )
        val base = BaseCircleView(context).apply { circleDiameter = baseDiameterPx() }
        val baseLp = overlayParams(baseTouch, baseTouch, touchable = false).apply {
            x = savedBase.x
            y = savedBase.y
        }
        clamp(baseLp)
        baseView = base
        baseParams = baseLp
        windowManager.addView(base, baseLp)
        attachBaseDrag(base, baseLp)

        val rCircle = createCircleView("R", Color.rgb(67, 112, 190))
        val rLp = overlayParams(rCircleDiameterPx(), rCircleDiameterPx(), touchable = true)
        rCircleView = rCircle
        rCircleParams = rLp
        centerROnBase(updateWindow = false)
        windowManager.addView(rCircle, rLp)
        attachInstantR(rCircle)

        val lDiameter = lCircleDiameterPx()
        val savedL = positionStore.load(
            keyPrefix = L_CIRCLE_POSITION_KEY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = lDiameter,
            overlayHeight = lDiameter,
            fallbackX = (screenWidth / 2 + dp(120) - lDiameter / 2).coerceAtLeast(0),
            fallbackY = (screenHeight / 2 - lDiameter / 2).coerceAtLeast(0),
        )
        val lCircle = createCircleView("L", Color.rgb(116, 63, 205))
        val lLp = overlayParams(lDiameter, lDiameter, touchable = true).apply {
            x = savedL.x
            y = savedL.y
        }
        clamp(lLp)
        lCircleView = lCircle
        lCircleParams = lLp
        windowManager.addView(lCircle, lLp)
        attachInstantOrEditL(lCircle, lLp)

        applyState()
    }

    fun updateBounds(width: Int, height: Int) {
        forceRelease()
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        val base = baseView ?: return
        val baseLp = baseParams ?: return
        val baseTouch = baseTouchSizePx()
        val savedBase = positionStore.load(
            keyPrefix = R_BASE_POSITION_KEY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = baseTouch,
            overlayHeight = baseTouch,
            fallbackX = screenWidth / 2 - baseTouch / 2,
            fallbackY = screenHeight / 2 - baseTouch / 2,
        )
        baseLp.width = baseTouch
        baseLp.height = baseTouch
        baseLp.x = savedBase.x
        baseLp.y = savedBase.y
        clamp(baseLp)
        base.circleDiameter = baseDiameterPx()
        runCatching { windowManager.updateViewLayout(base, baseLp) }

        resizeRCircle()
        centerROnBase()

        val lView = lCircleView
        val lLp = lCircleParams
        if (lView != null && lLp != null) {
            val diameter = lCircleDiameterPx()
            val savedL = positionStore.load(
                keyPrefix = L_CIRCLE_POSITION_KEY,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                overlayWidth = diameter,
                overlayHeight = diameter,
                fallbackX = (screenWidth / 2 + dp(120) - diameter / 2).coerceAtLeast(0),
                fallbackY = (screenHeight / 2 - diameter / 2).coerceAtLeast(0),
            )
            lLp.width = diameter
            lLp.height = diameter
            lLp.x = savedL.x
            lLp.y = savedL.y
            clamp(lLp)
            updateCircleVisual(lView, "L", diameter, Color.rgb(116, 63, 205))
            runCatching { windowManager.updateViewLayout(lView, lLp) }
        }
        applyState()
    }

    fun setInputEnabled(enabled: Boolean) {
        if (inputEnabled == enabled) return
        if (!enabled) forceRelease()
        inputEnabled = enabled
        applyState()
    }

    fun beginEditingR() {
        forceRelease()
        rVisible = true
        prefs.edit().putBoolean(KEY_R_VISIBLE, true).apply()
        editTarget = EditTarget.R_BASE
        baseView?.isEditing = true
        applyState()
    }

    fun beginEditingL() {
        forceRelease()
        lVisible = true
        prefs.edit().putBoolean(KEY_L_VISIBLE, true).apply()
        editTarget = EditTarget.L_CIRCLE
        baseView?.isEditing = false
        applyState()
    }

    fun finishEditing() {
        when (editTarget) {
            EditTarget.R_BASE -> baseParams?.let(::saveRBasePosition)
            EditTarget.L_CIRCLE -> lCircleParams?.let(::saveLCirclePosition)
            EditTarget.NONE -> Unit
        }
        editTarget = EditTarget.NONE
        baseView?.isEditing = false
        applyState()
    }

    fun toggleREnabled() {
        if (rEnabled) releaseR()
        rEnabled = !rEnabled
        prefs.edit().putBoolean(KEY_R_ENABLED, rEnabled).apply()
        applyState()
    }

    fun toggleLEnabled() {
        if (lEnabled) releaseL()
        lEnabled = !lEnabled
        prefs.edit().putBoolean(KEY_L_ENABLED, lEnabled).apply()
        applyState()
    }

    fun toggleRVisible() {
        if (rVisible) releaseR()
        rVisible = !rVisible
        prefs.edit().putBoolean(KEY_R_VISIBLE, rVisible).apply()
        applyState()
    }

    fun toggleLVisible() {
        if (lVisible) releaseL()
        lVisible = !lVisible
        prefs.edit().putBoolean(KEY_L_VISIBLE, lVisible).apply()
        applyState()
    }

    fun decreaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, -1))
    fun increaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, +1))
    fun decreaseRCircleSize() = setRCircleSize(stepSize(rCircleSizeHundredths, -1))
    fun increaseRCircleSize() = setRCircleSize(stepSize(rCircleSizeHundredths, +1))
    fun decreaseLCircleSize() = setLCircleSize(stepSize(lCircleSizeHundredths, -1))
    fun increaseLCircleSize() = setLCircleSize(stepSize(lCircleSizeHundredths, +1))

    private fun setBaseSize(value: Int) {
        val normalized = normalizeSize(value)
        if (normalized == baseSizeHundredths) return
        forceRelease()
        val oldCenter = baseCenter()
        baseSizeHundredths = normalized
        prefs.edit().putInt(KEY_BASE_SIZE, normalized).apply()
        val base = baseView ?: return
        val lp = baseParams ?: return
        val touch = baseTouchSizePx()
        lp.width = touch
        lp.height = touch
        lp.x = (oldCenter.first - touch / 2f).roundToInt()
        lp.y = (oldCenter.second - touch / 2f).roundToInt()
        clamp(lp)
        base.circleDiameter = baseDiameterPx()
        runCatching { windowManager.updateViewLayout(base, lp) }
        centerROnBase()
    }

    private fun setRCircleSize(value: Int) {
        val normalized = normalizeSize(value)
        if (normalized == rCircleSizeHundredths) return
        forceRelease()
        rCircleSizeHundredths = normalized
        prefs.edit().putInt(KEY_R_CIRCLE_SIZE, normalized).apply()
        resizeRCircle()
        centerROnBase()
    }

    private fun setLCircleSize(value: Int) {
        val normalized = normalizeSize(value)
        if (normalized == lCircleSizeHundredths) return
        forceRelease()
        val view = lCircleView ?: return
        val lp = lCircleParams ?: return
        val oldCenterX = lp.x + lp.width / 2f
        val oldCenterY = lp.y + lp.height / 2f
        lCircleSizeHundredths = normalized
        prefs.edit().putInt(KEY_L_CIRCLE_SIZE, normalized).apply()
        val diameter = lCircleDiameterPx()
        lp.width = diameter
        lp.height = diameter
        lp.x = (oldCenterX - diameter / 2f).roundToInt()
        lp.y = (oldCenterY - diameter / 2f).roundToInt()
        clamp(lp)
        updateCircleVisual(view, "L", diameter, Color.rgb(116, 63, 205))
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun attachInstantR(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!inputEnabled || !rEnabled || !rVisible || isEditing || !insideCircle(view, event)) {
                        return@setOnTouchListener false
                    }
                    forceRelease()
                    // Visible R intentionally uses existing internal L path.
                    rPressDown = ShoulderCaptureService.beginFingerHoldL()
                    view.alpha = if (rPressDown) 0.72f else 1f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    releaseR()
                    view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun attachInstantOrEditL(view: View, lp: WindowManager.LayoutParams) {
        var grabX = 0f
        var grabY = 0f
        view.setOnTouchListener { _, event ->
            if (editTarget == EditTarget.L_CIRCLE) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        grabX = event.rawX - lp.x
                        grabY = event.rawY - lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (event.rawX - grabX).roundToInt()
                        lp.y = (event.rawY - grabY).roundToInt()
                        clamp(lp)
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clamp(lp)
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        saveLCirclePosition(lp)
                        true
                    }
                    else -> true
                }
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!inputEnabled || !lEnabled || !lVisible || isEditing || !insideCircle(view, event)) {
                            return@setOnTouchListener false
                        }
                        forceRelease()
                        // Visible L intentionally uses existing internal R path.
                        lPressDown = ShoulderCaptureService.beginFingerHoldR()
                        view.alpha = if (lPressDown) 0.72f else 1f
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        releaseL()
                        view.performClick()
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun attachBaseDrag(view: View, lp: WindowManager.LayoutParams) {
        var grabX = 0f
        var grabY = 0f
        view.setOnTouchListener { _, event ->
            if (editTarget != EditTarget.R_BASE) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabX = event.rawX - lp.x
                    grabY = event.rawY - lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (event.rawX - grabX).roundToInt()
                    lp.y = (event.rawY - grabY).roundToInt()
                    clamp(lp)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    centerROnBase()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    clamp(lp)
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    centerROnBase()
                    saveRBasePosition(lp)
                    true
                }
                else -> true
            }
        }
    }

    private fun releaseR() {
        if (rPressDown) ShoulderCaptureService.endFingerHoldL()
        rPressDown = false
        rCircleView?.alpha = 1f
    }

    private fun releaseL() {
        if (lPressDown) ShoulderCaptureService.endFingerHoldR()
        lPressDown = false
        lCircleView?.alpha = 1f
    }

    private fun forceRelease() {
        releaseR()
        releaseL()
        ShoulderCaptureService.endAnyFingerHold()
    }

    private fun applyState() {
        val base = baseView ?: return
        val rView = rCircleView ?: return
        val lView = lCircleView ?: return

        val editingR = editTarget == EditTarget.R_BASE
        val editingL = editTarget == EditTarget.L_CIRCLE

        base.visibility = if (rVisible || editingR) View.VISIBLE else View.INVISIBLE
        rView.visibility = if (rVisible || editingR) View.VISIBLE else View.INVISIBLE
        lView.visibility = if (lVisible || editingL) View.VISIBLE else View.INVISIBLE
        base.isEditing = editingR

        baseParams?.let { lp ->
            lp.flags = flags(touchable = editingR)
            runCatching { windowManager.updateViewLayout(base, lp) }
        }
        rCircleParams?.let { lp ->
            lp.flags = flags(touchable = inputEnabled && rEnabled && rVisible && !isEditing)
            runCatching { windowManager.updateViewLayout(rView, lp) }
        }
        lCircleParams?.let { lp ->
            lp.flags = flags(touchable = editingL || (inputEnabled && lEnabled && lVisible && !isEditing))
            runCatching { windowManager.updateViewLayout(lView, lp) }
        }

        rView.alpha = if (rEnabled) 1f else 0.45f
        lView.alpha = if (lEnabled) 1f else 0.45f
    }

    private fun resizeRCircle() {
        val view = rCircleView ?: return
        val lp = rCircleParams ?: return
        val diameter = rCircleDiameterPx()
        lp.width = diameter
        lp.height = diameter
        updateCircleVisual(view, "R", diameter, Color.rgb(67, 112, 190))
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun centerROnBase(updateWindow: Boolean = true) {
        val lp = rCircleParams ?: return
        val diameter = rCircleDiameterPx()
        val center = baseCenter()
        lp.x = (center.first - diameter / 2f).roundToInt()
        lp.y = (center.second - diameter / 2f).roundToInt()
        clamp(lp)
        if (updateWindow) rCircleView?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    private fun baseCenter(): Pair<Float, Float> {
        val lp = baseParams ?: return screenWidth / 2f to screenHeight / 2f
        return (lp.x + lp.width / 2f) to (lp.y + lp.height / 2f)
    }

    private fun saveRBasePosition(lp: WindowManager.LayoutParams) {
        positionStore.save(
            keyPrefix = R_BASE_POSITION_KEY,
            x = lp.x,
            y = lp.y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = lp.width,
            overlayHeight = lp.height,
        )
    }

    private fun saveLCirclePosition(lp: WindowManager.LayoutParams) {
        positionStore.save(
            keyPrefix = L_CIRCLE_POSITION_KEY,
            x = lp.x,
            y = lp.y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = lp.width,
            overlayHeight = lp.height,
        )
    }

    private fun insideCircle(view: View, event: MotionEvent): Boolean {
        val cx = view.width / 2f
        val cy = view.height / 2f
        val dx = event.x - cx
        val dy = event.y - cy
        val radius = minOf(view.width, view.height) / 2f
        return dx * dx + dy * dy <= radius * radius
    }

    private fun createCircleView(label: String, color: Int): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        updateCircleVisual(this, label, if (label == "R") rCircleDiameterPx() else lCircleDiameterPx(), color)
    }

    private fun updateCircleVisual(view: TextView, label: String, diameter: Int, color: Int) {
        view.text = label
        view.textSize = max(10f, diameter / context.resources.displayMetrics.scaledDensity * 0.28f)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color)
            setStroke(max(dp(1), diameter / 22), Color.argb(230, 245, 245, 255))
        }
    }

    private fun clamp(lp: WindowManager.LayoutParams) {
        lp.x = lp.x.coerceIn(0, max(screenWidth - max(lp.width, 1), 0))
        lp.y = lp.y.coerceIn(0, max(screenHeight - max(lp.height, 1), 0))
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags(touchable),
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun flags(touchable: Boolean): Int {
        var result = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!touchable) result = result or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return result
    }

    private fun baseDiameterPx(): Int = cmHundredthsToPx(baseSizeHundredths)
    private fun rCircleDiameterPx(): Int = cmHundredthsToPx(rCircleSizeHundredths)
    private fun lCircleDiameterPx(): Int = cmHundredthsToPx(lCircleSizeHundredths)
    private fun baseTouchSizePx(): Int = max(baseDiameterPx(), dp(48))

    private fun cmHundredthsToPx(value: Int): Int {
        val cm = value / 100f
        val metrics = context.resources.displayMetrics
        val x = metrics.xdpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val y = metrics.ydpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        return max((cm * ((x + y) / 2f) / 2.54f).roundToInt(), 1)
    }

    fun destroy() {
        forceRelease()
        baseView?.let { runCatching { windowManager.removeView(it) } }
        rCircleView?.let { runCatching { windowManager.removeView(it) } }
        lCircleView?.let { runCatching { windowManager.removeView(it) } }
        baseView = null
        rCircleView = null
        lCircleView = null
        baseParams = null
        rCircleParams = null
        lCircleParams = null
    }

    private class BaseCircleView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(78, 120, 120, 138) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 2f
            color = Color.argb(225, 226, 226, 242)
        }
        var circleDiameter: Int = 1
            set(value) {
                field = value.coerceAtLeast(1)
                invalidate()
            }
        var isEditing: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = circleDiameter / 2f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, fill)
            stroke.color = if (isEditing) Color.rgb(70, 218, 146) else Color.argb(225, 226, 226, 242)
            canvas.drawCircle(cx, cy, max(radius - stroke.strokeWidth / 2f, 0.5f), stroke)
        }
    }

    companion object {
        private const val KEY_BASE_SIZE = "v6_analog_base_size_hundredths_cm"
        private const val KEY_R_CIRCLE_SIZE = "v6_analog_knob_size_hundredths_cm"
        private const val KEY_L_CIRCLE_SIZE = "v6_split_l_circle_size_hundredths_cm"
        private const val KEY_R_ENABLED = "v6_split_r_enabled"
        private const val KEY_L_ENABLED = "v6_split_l_enabled"
        private const val KEY_R_VISIBLE = "v6_split_r_visible"
        private const val KEY_L_VISIBLE = "v6_split_l_visible"
        private const val LEGACY_KEY_VISIBLE = "v6_analog_visible"
        private const val R_BASE_POSITION_KEY = "v6.analog.base"
        private const val L_CIRCLE_POSITION_KEY = "v6.split.l.circle"

        private const val DEFAULT_BASE_SIZE = 155
        private const val DEFAULT_R_CIRCLE_SIZE = 125
        private const val DEFAULT_L_CIRCLE_SIZE = 125

        private val SIZE_STEPS = ((25..295 step 10).toList() + 300).toIntArray()

        private fun normalizeSize(value: Int): Int =
            SIZE_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 25

        private fun stepSize(current: Int, delta: Int): Int {
            val normalized = normalizeSize(current)
            val index = SIZE_STEPS.indexOf(normalized).coerceAtLeast(0)
            return SIZE_STEPS[(index + delta).coerceIn(0, SIZE_STEPS.lastIndex)]
        }

        private fun formatSize(value: Int): String =
            String.format(java.util.Locale.US, "%.2f cm", value / 100f)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
