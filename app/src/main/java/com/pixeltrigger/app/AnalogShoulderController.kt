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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * V6 finger-driven shoulder control.
 *
 * The base and knob deliberately live in separate overlay windows:
 * - the base is NOT_TOUCHABLE during normal use, so it never blocks the game;
 * - the knob is the only normal touch target and consumes the user's DOWN/MOVE/UP;
 * - while editing, the base becomes draggable and the knob becomes non-touchable.
 *
 * R/L itself is not a screen injection. Each contact starts PENDING: reaching
 * the knob travel limit chooses R immediately; otherwise the user-selected delay
 * chooses L. The chosen shoulder remains DOWN until that same finger is released.
 */
class AnalogShoulderController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val prefs: SharedPreferences,
) {
    private enum class Binding { R, L }
    private enum class Decision { IDLE, PENDING, R_ACTIVE, L_ACTIVE }

    private val positionStore = OrientationPositionStore(prefs)

    private var screenWidth = 1
    private var screenHeight = 1
    private var baseSizeHundredths = normalizeSize(prefs.getInt(KEY_BASE_SIZE, DEFAULT_BASE_SIZE))
    private var knobSizeHundredths = normalizeSize(prefs.getInt(KEY_KNOB_SIZE, DEFAULT_KNOB_SIZE))
    private var decisionDelayMs = normalizeDecisionDelay(
        prefs.getInt(KEY_DECISION_DELAY_MS, DEFAULT_DECISION_DELAY_MS),
    )

    var fastEnabled: Boolean = prefs.getBoolean(KEY_FAST_ENABLED, false)
        private set
    var brakeEnabled: Boolean = prefs.getBoolean(KEY_BRAKE_ENABLED, true)
        private set
    private var brakeOpen = false

    var isVisible: Boolean = prefs.getBoolean(KEY_VISIBLE, true)
        private set
    var isEditing: Boolean = false
        private set
    private var inputEnabled = true

    private var baseView: BaseCircleView? = null
    private var knobView: TextView? = null
    private var baseParams: WindowManager.LayoutParams? = null
    private var knobParams: WindowManager.LayoutParams? = null

    private var decision = Decision.IDLE
    private var pressBinding = Binding.L
    private var outputDown = false
    private var fingerOffsetX = 0f
    private var fingerOffsetY = 0f
    private var pendingDecisionRunnable: Runnable? = null

    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val knobSizeLabel: String get() = formatSize(knobSizeHundredths)
    val decisionDelayLabel: String get() = "$decisionDelayMs ms"
    val fastLabel: String get() = if (fastEnabled) "FAST ON" else "FAST OFF"
    val brakeLabel: String get() = if (brakeEnabled) "مكابح المراقبة ON" else "مكابح المراقبة OFF"

    fun create(width: Int, height: Int) {
        if (baseView != null) return
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        val baseTouch = baseTouchSizePx()
        val saved = positionStore.load(
            keyPrefix = POSITION_KEY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = baseTouch,
            overlayHeight = baseTouch,
            fallbackX = screenWidth / 2 - baseTouch / 2,
            fallbackY = screenHeight / 2 - baseTouch / 2,
        )

        val base = BaseCircleView(context).apply {
            circleDiameter = baseDiameterPx()
        }
        val baseLp = overlayParams(baseTouch, baseTouch, touchable = false).apply {
            x = saved.x
            y = saved.y
        }
        clampBase(baseLp)
        baseView = base
        baseParams = baseLp
        windowManager.addView(base, baseLp)
        attachBaseDrag(base, baseLp)

        val knob = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        knobView = knob
        rebuildKnobVisual()
        val knobLp = overlayParams(knobDiameterPx(), knobDiameterPx(), touchable = true)
        knobParams = knobLp
        centerKnob(updateWindow = false)
        windowManager.addView(knob, knobLp)
        attachKnobTouch(knob)

        applyVisibilityAndTouchability()
    }

    fun updateBounds(width: Int, height: Int) {
        forceRelease()
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        val base = baseView ?: return
        val lp = baseParams ?: return
        val touch = baseTouchSizePx()
        val saved = positionStore.load(
            keyPrefix = POSITION_KEY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = touch,
            overlayHeight = touch,
            fallbackX = screenWidth / 2 - touch / 2,
            fallbackY = screenHeight / 2 - touch / 2,
        )
        lp.width = touch
        lp.height = touch
        lp.x = saved.x
        lp.y = saved.y
        clampBase(lp)
        base.circleDiameter = baseDiameterPx()
        runCatching { windowManager.updateViewLayout(base, lp) }
        resizeKnobPreservingBaseCenter()
        centerKnob()
        applyVisibilityAndTouchability()
    }

    fun setInputEnabled(enabled: Boolean) {
        if (inputEnabled == enabled) return
        if (!enabled) forceRelease()
        inputEnabled = enabled
        applyVisibilityAndTouchability()
    }

    fun beginEditing() {
        forceRelease()
        isEditing = true
        centerKnob()
        baseView?.isEditing = true
        applyVisibilityAndTouchability()
    }

    fun finishEditing() {
        if (!isEditing) return
        forceRelease()
        isEditing = false
        baseView?.isEditing = false
        baseParams?.let(::saveBasePosition)
        centerKnob()
        applyVisibilityAndTouchability()
    }

    fun toggleVisible() {
        if (isVisible) forceRelease()
        isVisible = !isVisible
        prefs.edit().putBoolean(KEY_VISIBLE, isVisible).apply()
        applyVisibilityAndTouchability()
    }

    fun decreaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, -1))
    fun increaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, +1))
    fun decreaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, -1))
    fun increaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, +1))
    fun decreaseDecisionDelay() = setDecisionDelay(decisionDelayMs - DECISION_DELAY_STEP_MS)
    fun increaseDecisionDelay() = setDecisionDelay(decisionDelayMs + DECISION_DELAY_STEP_MS)

    fun toggleFast() {
        fastEnabled = !fastEnabled
        prefs.edit().putBoolean(KEY_FAST_ENABLED, fastEnabled).apply()
    }

    fun toggleBrakeEnabled() {
        brakeEnabled = !brakeEnabled
        prefs.edit().putBoolean(KEY_BRAKE_ENABLED, brakeEnabled).apply()
        // Enabling the brake is fail-closed until a fresh captured frame proves FIRE.
        if (brakeEnabled) brakeOpen = false
        reconcileOutput()
    }

    fun setBrakeOpen(open: Boolean) {
        if (brakeOpen == open) return
        brakeOpen = open
        reconcileOutput()
    }

    private fun setDecisionDelay(value: Int) {
        val normalized = normalizeDecisionDelay(value)
        if (normalized == decisionDelayMs) return
        forceRelease()
        decisionDelayMs = normalized
        prefs.edit().putInt(KEY_DECISION_DELAY_MS, normalized).apply()
    }

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
        clampBase(lp)
        base.circleDiameter = baseDiameterPx()
        runCatching { windowManager.updateViewLayout(base, lp) }
        centerKnob()
    }

    private fun setKnobSize(value: Int) {
        val normalized = normalizeSize(value)
        if (normalized == knobSizeHundredths) return
        forceRelease()
        knobSizeHundredths = normalized
        prefs.edit().putInt(KEY_KNOB_SIZE, normalized).apply()
        resizeKnobPreservingBaseCenter()
        centerKnob()
    }

    private fun resizeKnobPreservingBaseCenter() {
        val knob = knobView ?: return
        val lp = knobParams ?: return
        val diameter = knobDiameterPx()
        lp.width = diameter
        lp.height = diameter
        rebuildKnobVisual()
        runCatching { windowManager.updateViewLayout(knob, lp) }
    }

    private fun attachKnobTouch(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isVisible || isEditing || !inputEnabled) return@setOnTouchListener true
                    forceRelease()
                    val center = knobCenter()
                    fingerOffsetX = event.rawX - center.first
                    fingerOffsetY = event.rawY - center.second
                    decision = Decision.PENDING
                    view.alpha = 0.90f
                    rebuildKnobVisual()
                    schedulePendingL(view)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isEditing && inputEnabled && isVisible) {
                        val reachedRLimit = moveKnobToward(
                            event.rawX - fingerOffsetX,
                            event.rawY - fingerOffsetY,
                        )
                        if (decision == Decision.PENDING && reachedRLimit) {
                            activateDecision(Binding.R)
                        } else if (decision == Decision.L_ACTIVE && fastEnabled && reachedRLimit) {
                            upgradeLToR()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    finishContact()
                    view.alpha = 1f
                    centerKnob()
                    rebuildKnobVisual()
                    true
                }

                else -> true
            }
        }
    }

    private fun schedulePendingL(view: View) {
        cancelPendingDecision()
        val runnable = Runnable {
            pendingDecisionRunnable = null
            if (decision == Decision.PENDING && isVisible && !isEditing && inputEnabled) {
                activateDecision(Binding.L)
            }
        }
        pendingDecisionRunnable = runnable
        view.postDelayed(runnable, decisionDelayMs.toLong())
    }

    private fun cancelPendingDecision() {
        val runnable = pendingDecisionRunnable ?: return
        knobView?.removeCallbacks(runnable)
        pendingDecisionRunnable = null
    }

    private fun activateDecision(side: Binding) {
        if (decision != Decision.PENDING) return
        cancelPendingDecision()
        pressBinding = side
        decision = if (side == Binding.R) Decision.R_ACTIVE else Decision.L_ACTIVE
        reconcileOutput()
        knobView?.alpha = 0.78f
        rebuildKnobVisual()
    }

    /** FAST is one-way for a contact: a selected L may upgrade to R, never back. */
    private fun upgradeLToR() {
        if (!fastEnabled || decision != Decision.L_ACTIVE) return
        if (outputDown) endCurrentPress()
        pressBinding = Binding.R
        decision = Decision.R_ACTIVE
        reconcileOutput()
        knobView?.alpha = 0.78f
        rebuildKnobVisual()
    }

    /**
     * Intent and physical output are deliberately separate. The brake may suspend
     * an already-selected R/L with UP, then resume the SAME intent with DOWN when
     * the monitored pixel leaves white, as long as the finger contact still lives.
     */
    private fun reconcileOutput() {
        val selected = decision == Decision.R_ACTIVE || decision == Decision.L_ACTIVE
        val gateOpen = !brakeEnabled || brakeOpen
        val shouldBeDown = selected && gateOpen && inputEnabled && isVisible && !isEditing

        if (shouldBeDown && !outputDown) {
            outputDown = beginShoulderPress(pressBinding)
        } else if (!shouldBeDown && outputDown) {
            endCurrentPress()
        }
    }

    private fun finishContact() {
        if (decision == Decision.PENDING) cancelPendingDecision()
        if (outputDown) endCurrentPress()
        decision = Decision.IDLE
        outputDown = false
    }

    private fun beginShoulderPress(side: Binding): Boolean = when (side) {
        Binding.R -> ShoulderCaptureService.beginFingerHoldR()
        Binding.L -> ShoulderCaptureService.beginFingerHoldL()
    }

    private fun endCurrentPress() {
        when (pressBinding) {
            Binding.R -> ShoulderCaptureService.endFingerHoldR()
            Binding.L -> ShoulderCaptureService.endFingerHoldL()
        }
        outputDown = false
    }

    private fun forceRelease() {
        cancelPendingDecision()
        if (outputDown) endCurrentPress()
        // Ownership guards in ShoulderCaptureService make this a safe stuck-key fallback.
        ShoulderCaptureService.endAnyFingerHold()
        decision = Decision.IDLE
        outputDown = false
        knobView?.alpha = 1f
        centerKnob()
        rebuildKnobVisual()
    }

    private fun moveKnobToward(desiredCenterX: Float, desiredCenterY: Float): Boolean {
        val lp = knobParams ?: return false
        val view = knobView ?: return false
        val baseCenter = baseCenter()
        var dx = desiredCenterX - baseCenter.first
        var dy = desiredCenterY - baseCenter.second
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        // User specification: the moving circle's inner edge may reach the
        // base center, but must never cross it. Therefore max center offset is
        // exactly the knob radius.
        val maxOffset = knobDiameterPx() / 2f
        if (distance > maxOffset && distance > 0f) {
            val scale = maxOffset / distance
            dx *= scale
            dy *= scale
        }

        val diameter = knobDiameterPx()
        val centerX = (baseCenter.first + dx).coerceIn(diameter / 2f, screenWidth - diameter / 2f)
        val centerY = (baseCenter.second + dy).coerceIn(diameter / 2f, screenHeight - diameter / 2f)
        lp.x = (centerX - diameter / 2f).roundToInt()
        lp.y = (centerY - diameter / 2f).roundToInt()
        runCatching { windowManager.updateViewLayout(view, lp) }

        // R uses the actual post-screen-clamp knob position.
        val actualDx = centerX - baseCenter.first
        val actualDy = centerY - baseCenter.second
        val actualDistance = hypot(actualDx.toDouble(), actualDy.toDouble()).toFloat()
        return actualDistance + 0.5f >= maxOffset
    }

    private fun centerKnob(updateWindow: Boolean = true) {
        val lp = knobParams ?: return
        val diameter = knobDiameterPx()
        val center = baseCenter()
        val centerX = center.first.coerceIn(diameter / 2f, screenWidth - diameter / 2f)
        val centerY = center.second.coerceIn(diameter / 2f, screenHeight - diameter / 2f)
        lp.x = (centerX - diameter / 2f).roundToInt()
        lp.y = (centerY - diameter / 2f).roundToInt()
        if (updateWindow) knobView?.let { runCatching { windowManager.updateViewLayout(it, lp) } }
    }

    private fun attachBaseDrag(view: View, lp: WindowManager.LayoutParams) {
        var grabX = 0f
        var grabY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isEditing) return@setOnTouchListener true
                    grabX = event.rawX - lp.x
                    grabY = event.rawY - lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isEditing) {
                        lp.x = (event.rawX - grabX).roundToInt()
                        lp.y = (event.rawY - grabY).roundToInt()
                        clampBase(lp)
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        centerKnob()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isEditing) {
                        clampBase(lp)
                        runCatching { windowManager.updateViewLayout(view, lp) }
                        centerKnob()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun saveBasePosition(lp: WindowManager.LayoutParams) {
        positionStore.save(
            keyPrefix = POSITION_KEY,
            x = lp.x,
            y = lp.y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = lp.width,
            overlayHeight = lp.height,
        )
    }

    private fun applyVisibilityAndTouchability() {
        val base = baseView ?: return
        val knob = knobView ?: return
        base.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        knob.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE

        baseParams?.let { lp ->
            lp.flags = flags(touchable = isVisible && isEditing)
            runCatching { windowManager.updateViewLayout(base, lp) }
        }
        knobParams?.let { lp ->
            lp.flags = flags(touchable = isVisible && inputEnabled && !isEditing)
            runCatching { windowManager.updateViewLayout(knob, lp) }
        }
    }

    private fun rebuildKnobVisual() {
        knobView?.apply {
            text = when (decision) {
                Decision.IDLE -> "R/L"
                Decision.PENDING -> "…"
                Decision.R_ACTIVE -> "R"
                Decision.L_ACTIVE -> "L"
            }
            textSize = max(9f, knobDiameterPx() / context.resources.displayMetrics.scaledDensity * 0.26f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(
                    when (decision) {
                        Decision.R_ACTIVE -> Color.rgb(116, 63, 205)
                        Decision.L_ACTIVE -> Color.rgb(30, 139, 184)
                        Decision.PENDING -> Color.rgb(92, 102, 128)
                        Decision.IDLE -> Color.rgb(67, 112, 190)
                    },
                )
                setStroke(max(dp(1), knobDiameterPx() / 22), Color.argb(230, 245, 245, 255))
            }
        }
    }

    private fun baseCenter(): Pair<Float, Float> {
        val lp = baseParams
        if (lp == null) return screenWidth / 2f to screenHeight / 2f
        return (lp.x + lp.width / 2f) to (lp.y + lp.height / 2f)
    }

    private fun knobCenter(): Pair<Float, Float> {
        val lp = knobParams
        if (lp == null) return baseCenter()
        return (lp.x + lp.width / 2f) to (lp.y + lp.height / 2f)
    }

    private fun clampBase(lp: WindowManager.LayoutParams) {
        lp.x = lp.x.coerceIn(0, max(screenWidth - lp.width, 0))
        lp.y = lp.y.coerceIn(0, max(screenHeight - lp.height, 0))
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
    private fun knobDiameterPx(): Int = cmHundredthsToPx(knobSizeHundredths)
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
        knobView?.let { runCatching { windowManager.removeView(it) } }
        baseView = null
        knobView = null
        baseParams = null
        knobParams = null
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
        private const val KEY_KNOB_SIZE = "v6_analog_knob_size_hundredths_cm"
        private const val KEY_DECISION_DELAY_MS = "v6_analog_decision_delay_ms"
        private const val KEY_FAST_ENABLED = "v6_analog_fast_enabled"
        private const val KEY_BRAKE_ENABLED = "v6_analog_brake_enabled"
        private const val KEY_VISIBLE = "v6_analog_visible"
        private const val POSITION_KEY = "v6.analog.base"

        private const val DEFAULT_BASE_SIZE = 155
        private const val DEFAULT_KNOB_SIZE = 125
        private const val DEFAULT_DECISION_DELAY_MS = 150
        private const val DECISION_DELAY_MIN_MS = 50
        private const val DECISION_DELAY_MAX_MS = 500
        private const val DECISION_DELAY_STEP_MS = 50

        // Requested progression: 0.25, 0.35, ... 1.95, with 2.00 as the final cap.
        private val SIZE_STEPS = ((25..195 step 10).toList() + 200).toIntArray()

        private fun normalizeSize(value: Int): Int = SIZE_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 25

        private fun normalizeDecisionDelay(value: Int): Int {
            val clamped = value.coerceIn(DECISION_DELAY_MIN_MS, DECISION_DELAY_MAX_MS)
            return ((clamped + DECISION_DELAY_STEP_MS / 2) / DECISION_DELAY_STEP_MS * DECISION_DELAY_STEP_MS)
                .coerceIn(DECISION_DELAY_MIN_MS, DECISION_DELAY_MAX_MS)
        }

        private fun stepSize(current: Int, delta: Int): Int {
            val normalized = normalizeSize(current)
            val index = SIZE_STEPS.indexOf(normalized).coerceAtLeast(0)
            return SIZE_STEPS[(index + delta).coerceIn(0, SIZE_STEPS.lastIndex)]
        }

        private fun formatSize(value: Int): String = String.format(java.util.Locale.US, "%.2f cm", value / 100f)

        private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
