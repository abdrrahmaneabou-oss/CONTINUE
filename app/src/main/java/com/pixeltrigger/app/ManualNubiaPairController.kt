package com.pixeltrigger.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.pixeltrigger.app.input.ShizukuTapEngine
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Independent three-circle manual module:
 * 10 mm trigger -> 3 mm target A -> short gap -> 3 mm target B.
 * It shares only the verified Nubia transport; no detector or shoulder state.
 */
internal class ManualNubiaPairController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val preferences: SharedPreferences,
    private val tapEngine: ShizukuTapEngine,
) {
    private enum class Role { TRIGGER, TARGET_A, TARGET_B }

    private data class Circle(
        val role: Role,
        val view: ManualCircleView,
        val params: WindowManager.LayoutParams,
        val keyX: String,
        val keyY: String,
    )

    private var screenWidth = 1
    private var screenHeight = 1
    private val circles = ArrayList<Circle>(3)

    var isEnabled: Boolean = preferences.getBoolean(KEY_ENABLED, true)
        private set
    var isVisible: Boolean = preferences.getBoolean(KEY_VISIBLE, true)
        private set
    var isEditing: Boolean = false
        private set

    fun create(width: Int, height: Int) {
        if (circles.isNotEmpty()) return
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        val triggerDiameter = mmToPx(TRIGGER_DIAMETER_MM)
        val targetDiameter = mmToPx(TARGET_DIAMETER_MM)
        val triggerWindow = max(triggerDiameter + dp(8), dp(48))
        val targetWindow = max(targetDiameter + dp(20), dp(48))

        addCircle(
            role = Role.TRIGGER,
            visibleDiameter = triggerDiameter,
            windowSize = triggerWindow,
            keyX = KEY_TRIGGER_X,
            keyY = KEY_TRIGGER_Y,
            defaultX = dp(16),
            defaultY = screenHeight / 2 - triggerWindow / 2,
        )
        addCircle(
            role = Role.TARGET_A,
            visibleDiameter = targetDiameter,
            windowSize = targetWindow,
            keyX = KEY_TARGET_A_X,
            keyY = KEY_TARGET_A_Y,
            defaultX = screenWidth * 2 / 3 - targetWindow / 2,
            defaultY = screenHeight / 2 - targetWindow - dp(12),
        )
        addCircle(
            role = Role.TARGET_B,
            visibleDiameter = targetDiameter,
            windowSize = targetWindow,
            keyX = KEY_TARGET_B_X,
            keyY = KEY_TARGET_B_Y,
            defaultX = screenWidth * 2 / 3 - targetWindow / 2,
            defaultY = screenHeight / 2 + dp(12),
        )
        applyState()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) isEditing = false
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        applyState()
    }

    fun toggleEnabled() = setEnabled(!isEnabled)

    fun setVisible(visible: Boolean) {
        isVisible = visible
        if (!visible) isEditing = false
        preferences.edit().putBoolean(KEY_VISIBLE, visible).apply()
        applyState()
    }

    fun toggleVisible() = setVisible(!isVisible)

    fun beginEditing() {
        isVisible = true
        isEditing = true
        preferences.edit().putBoolean(KEY_VISIBLE, true).apply()
        applyState()
    }

    fun finishEditing() {
        if (!isEditing) return
        isEditing = false
        savePositions()
        applyState()
    }

    fun updateBounds(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        circles.forEach { circle ->
            clamp(circle.params)
            runCatching { windowManager.updateViewLayout(circle.view, circle.params) }
        }
    }

    fun destroy() {
        circles.forEach { runCatching { windowManager.removeView(it.view) } }
        circles.clear()
    }

    private fun addCircle(
        role: Role,
        visibleDiameter: Int,
        windowSize: Int,
        keyX: String,
        keyY: String,
        defaultX: Int,
        defaultY: Int,
    ) {
        val view = ManualCircleView(context, role, visibleDiameter)
        val params = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(keyX, defaultX)
            y = preferences.getInt(keyY, defaultY)
        }
        val circle = Circle(role, view, params, keyX, keyY)
        clamp(params)
        circles.add(circle)
        windowManager.addView(view, params)
        attachGesture(circle)
    }

    private fun attachGesture(circle: Circle) {
        var grabX = 0f
        var grabY = 0f
        var firedForContact = false
        circle.view.setOnTouchListener { view, event ->
            if (!isEditing) {
                if (circle.role != Role.TRIGGER || !isEnabled || !isVisible) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!firedForContact) {
                            firedForContact = true
                            firePair()
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        firedForContact = false
                        view.performClick()
                        true
                    }
                    else -> true
                }
            } else {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        grabX = event.rawX - circle.params.x
                        grabY = event.rawY - circle.params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        circle.params.x = (event.rawX - grabX).roundToInt()
                        circle.params.y = (event.rawY - grabY).roundToInt()
                        clamp(circle.params)
                        runCatching { windowManager.updateViewLayout(circle.view, circle.params) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clamp(circle.params)
                        runCatching { windowManager.updateViewLayout(circle.view, circle.params) }
                        save(circle)
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun firePair() {
        val first = circles.firstOrNull { it.role == Role.TARGET_A } ?: return
        val second = circles.firstOrNull { it.role == Role.TARGET_B } ?: return
        tapEngine.firePairFast(
            first.params.x + first.params.width / 2f,
            first.params.y + first.params.height / 2f,
            second.params.x + second.params.width / 2f,
            second.params.y + second.params.height / 2f,
            displayId = 0,
        )
    }

    private fun applyState() {
        circles.forEach { circle ->
            circle.view.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
            circle.view.setState(isEnabled, isEditing)
            val touchable = isEditing || (circle.role == Role.TRIGGER && isEnabled && isVisible)
            circle.params.flags = if (touchable) baseFlags()
            else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { windowManager.updateViewLayout(circle.view, circle.params) }
        }
    }

    private fun savePositions() = circles.forEach(::save)

    private fun save(circle: Circle) {
        preferences.edit()
            .putInt(circle.keyX, circle.params.x)
            .putInt(circle.keyY, circle.params.y)
            .apply()
    }

    private fun clamp(params: WindowManager.LayoutParams) {
        params.x = params.x.coerceIn(0, max(screenWidth - max(params.width, 1), 0))
        params.y = params.y.coerceIn(0, max(screenHeight - max(params.height, 1), 0))
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun mmToPx(mm: Float): Int {
        val metrics = context.resources.displayMetrics
        val x = metrics.xdpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        val y = metrics.ydpi.takeIf { it.isFinite() && it in 100f..1000f } ?: metrics.densityDpi.toFloat()
        return max((mm * ((x + y) / 2f) / 25.4f).roundToInt(), 1)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private class ManualCircleView(
        context: Context,
        private val role: Role,
        private val diameter: Int,
    ) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private var enabled = true
        private var editing = false

        fun setState(isEnabled: Boolean, isEditing: Boolean) {
            enabled = isEnabled
            editing = isEditing
            invalidate()
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = (diameter / 2f - stroke.strokeWidth).coerceAtLeast(1f)
            val base = when (role) {
                Role.TRIGGER -> Color.rgb(255, 170, 45)
                Role.TARGET_A -> Color.rgb(45, 205, 255)
                Role.TARGET_B -> Color.rgb(255, 85, 190)
            }
            stroke.color = when {
                editing -> Color.rgb(70, 235, 125)
                enabled -> base
                else -> Color.rgb(125, 125, 132)
            }
            fill.color = Color.argb(if (role == Role.TRIGGER) 52 else 25, Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, stroke)
            if (role != Role.TRIGGER) {
                val arm = radius * 0.62f
                canvas.drawLine(cx - arm, cy, cx + arm, cy, stroke)
                canvas.drawLine(cx, cy - arm, cx, cy + arm, stroke)
            }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    companion object {
        private const val TRIGGER_DIAMETER_MM = 10f
        private const val TARGET_DIAMETER_MM = 3f

        private const val KEY_ENABLED = "manual_pair_enabled"
        private const val KEY_VISIBLE = "manual_pair_visible"
        private const val KEY_TRIGGER_X = "manual_pair_trigger_x"
        private const val KEY_TRIGGER_Y = "manual_pair_trigger_y"
        private const val KEY_TARGET_A_X = "manual_pair_target_a_x"
        private const val KEY_TARGET_A_Y = "manual_pair_target_a_y"
        private const val KEY_TARGET_B_X = "manual_pair_target_b_x"
        private const val KEY_TARGET_B_Y = "manual_pair_target_b_y"
    }
}
