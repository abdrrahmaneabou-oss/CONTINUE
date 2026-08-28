package com.pixeltrigger.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single manual 13 mm shoulder trigger.
 * The visible circle is also the touch window: there is no larger invisible hit box.
 * While enabled and visible it reserves the selected shoulder side so the automatic
 * R/L detector cannot feed back on screen changes produced by the GameSpace macro.
 */
internal class ManualNubiaPairController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val preferences: SharedPreferences,
) {
    private enum class Binding { R, L }

    private data class Circle(
        val view: ManualCircleView,
        val params: WindowManager.LayoutParams,
        val keyX: String,
        val keyY: String,
    )

    private var screenWidth = 1
    private var screenHeight = 1
    private var circle: Circle? = null
    private var lastFireAtMs = 0L
    private var binding: Binding = when (preferences.getString(KEY_BINDING, Binding.R.name)) {
        Binding.L.name -> Binding.L
        else -> Binding.R
    }

    var isEnabled: Boolean = preferences.getBoolean(KEY_ENABLED, true)
        private set
    var isVisible: Boolean = preferences.getBoolean(KEY_VISIBLE, true)
        private set
    var isEditing: Boolean = false
        private set

    val bindingLabel: String
        get() = binding.name

    fun create(width: Int, height: Int) {
        if (circle != null) return
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)

        val triggerDiameter = mmToPx(TRIGGER_DIAMETER_MM)
        val view = ManualCircleView(context, triggerDiameter)
        val params = WindowManager.LayoutParams(
            triggerDiameter,
            triggerDiameter,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_TRIGGER_X, dp(16))
            y = preferences.getInt(KEY_TRIGGER_Y, screenHeight / 2 - triggerDiameter / 2)
        }
        val created = Circle(view, params, KEY_TRIGGER_X, KEY_TRIGGER_Y)
        clamp(params)
        circle = created
        windowManager.addView(view, params)
        attachGesture(created)
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

    fun bindToR() = setBinding(Binding.R)

    fun bindToL() = setBinding(Binding.L)

    private fun setBinding(newBinding: Binding) {
        binding = newBinding
        preferences.edit().putString(KEY_BINDING, newBinding.name).apply()
        applyState()
    }

    fun beginEditing() {
        isVisible = true
        isEditing = true
        preferences.edit().putBoolean(KEY_VISIBLE, true).apply()
        applyState()
    }

    fun finishEditing() {
        if (!isEditing) return
        isEditing = false
        circle?.let(::save)
        applyState()
    }

    fun updateBounds(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        circle?.let { current ->
            clamp(current.params)
            runCatching { windowManager.updateViewLayout(current.view, current.params) }
        }
    }

    fun destroy() {
        ShoulderCaptureService.clearManualReservation()
        circle?.let { runCatching { windowManager.removeView(it.view) } }
        circle = null
    }

    private fun attachGesture(current: Circle) {
        var grabX = 0f
        var grabY = 0f
        var firedForContact = false
        current.view.setOnTouchListener { view, event ->
            if (!isEditing) {
                if (!isEnabled || !isVisible) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!view.containsVisibleCircle(event.x, event.y)) {
                            firedForContact = false
                            return@setOnTouchListener false
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (!firedForContact && now - lastFireAtMs >= MIN_FIRE_INTERVAL_MS) {
                            firedForContact = true
                            lastFireAtMs = now
                            fireLinkedShoulder()
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
                        grabX = event.rawX - current.params.x
                        grabY = event.rawY - current.params.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        current.params.x = (event.rawX - grabX).roundToInt()
                        current.params.y = (event.rawY - grabY).roundToInt()
                        clamp(current.params)
                        runCatching { windowManager.updateViewLayout(current.view, current.params) }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        clamp(current.params)
                        runCatching { windowManager.updateViewLayout(current.view, current.params) }
                        save(current)
                        true
                    }
                    else -> true
                }
            }
        }
    }

    private fun fireLinkedShoulder() {
        when (binding) {
            Binding.R -> ShoulderCaptureService.fireConfiguredR()
            Binding.L -> ShoulderCaptureService.fireConfiguredL()
        }
    }

    private fun applyState() {
        val current = circle ?: return
        current.view.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
        current.view.setState(isEnabled, isEditing, binding.name)
        val touchable = isEditing || (isEnabled && isVisible)
        current.params.flags = if (touchable) baseFlags()
        else baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(current.view, current.params) }
        updateManualReservation()
    }

    private fun updateManualReservation() {
        if (!isEnabled || !isVisible) {
            ShoulderCaptureService.clearManualReservation()
            return
        }
        when (binding) {
            Binding.R -> ShoulderCaptureService.reserveManualR()
            Binding.L -> ShoulderCaptureService.reserveManualL()
        }
    }

    private fun save(current: Circle) {
        preferences.edit()
            .putInt(current.keyX, current.params.x)
            .putInt(current.keyY, current.params.y)
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
        private val diameter: Int,
    ) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = dp(11).toFloat()
        }
        private var enabled = true
        private var editing = false
        private var binding = Binding.R.name

        fun setState(isEnabled: Boolean, isEditing: Boolean, bindingName: String) {
            enabled = isEnabled
            editing = isEditing
            binding = bindingName
            invalidate()
        }

        fun containsVisibleCircle(x: Float, y: Float): Boolean {
            val cx = width / 2f
            val cy = height / 2f
            val radius = (diameter / 2f).coerceAtLeast(1f)
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= radius * radius
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
            val base = if (binding == Binding.R.name) Color.rgb(238, 84, 108) else Color.rgb(72, 145, 245)
            stroke.color = when {
                editing -> Color.rgb(70, 235, 125)
                enabled -> base
                else -> Color.rgb(125, 125, 132)
            }
            fill.color = Color.argb(58, Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            textPaint.color = stroke.color
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, stroke)
            val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(binding, cx, baseline, textPaint)
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    }

    companion object {
        private const val TRIGGER_DIAMETER_MM = 13f
        private const val MIN_FIRE_INTERVAL_MS = 120L

        private const val KEY_ENABLED = "manual_pair_enabled"
        private const val KEY_VISIBLE = "manual_pair_visible"
        private const val KEY_TRIGGER_X = "manual_pair_trigger_x"
        private const val KEY_TRIGGER_Y = "manual_pair_trigger_y"
        private const val KEY_BINDING = "manual_shoulder_binding"
    }
}
