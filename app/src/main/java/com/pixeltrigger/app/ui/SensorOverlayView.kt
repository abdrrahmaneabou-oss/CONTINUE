package com.pixeltrigger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Visible sensor ring stays transparent inside so it cannot tint the sampled pixels. */
class SensorOverlayView(context: Context, requestedVisibleDiameterPx: Int) : View(context) {
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var status: SensorStatus = SensorStatus.WAITING

    val visibleDiameterPx: Int = maxOf(requestedVisibleDiameterPx, 1)
    val outerDiameterPx: Int = visibleDiameterPx

    fun setStatus(value: SensorStatus) {
        status = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val diameter = min(visibleDiameterPx.toFloat(), min(width, height).toFloat()).coerceAtLeast(1f)
        val radius = diameter / 2f
        val strokeWidth = 1f.coerceAtMost(radius.coerceAtLeast(1f))
        val strokeRadius = (radius - strokeWidth / 2f).coerceAtLeast(0f)
        strokePaint.color = when (status) {
            SensorStatus.OFF -> Color.rgb(120, 120, 126)
            SensorStatus.WAITING -> Color.rgb(255, 184, 77)
            SensorStatus.ARMED -> Color.rgb(60, 220, 120)
            SensorStatus.FIRED -> Color.rgb(255, 80, 95)
            SensorStatus.INPUT_NOT_READY -> Color.rgb(220, 85, 255)
        }
        strokePaint.strokeWidth = strokeWidth
        canvas.drawCircle(cx, cy, strokeRadius, strokePaint)
    }
}
