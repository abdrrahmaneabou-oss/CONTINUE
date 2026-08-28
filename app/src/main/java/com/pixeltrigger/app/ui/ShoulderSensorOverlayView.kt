package com.pixeltrigger.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Transparent-center 0.3 mm shoulder monitor circle. */
class ShoulderSensorOverlayView(
    context: Context,
    requestedVisibleDiameterPx: Int,
    private val sideLabel: String,
) : View(context) {
    enum class Status { WAITING, ARMED, FIRED, INPUT_NOT_READY }

    val visibleDiameterPx = maxOf(requestedVisibleDiameterPx, 1)

    private var status = Status.WAITING
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun setStatus(value: Status) {
        if (status == value) return
        status = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val diameter = min(visibleDiameterPx.toFloat(), min(width, height).toFloat()).coerceAtLeast(1f)
        val radius = diameter / 2f
        ring.strokeWidth = 1f.coerceAtMost(radius.coerceAtLeast(1f))
        ring.color = when (status) {
            Status.WAITING -> Color.rgb(255, 184, 77)
            Status.ARMED -> Color.rgb(65, 220, 125)
            Status.FIRED -> Color.rgb(255, 55, 65)
            Status.INPUT_NOT_READY -> Color.rgb(220, 85, 255)
        }
        canvas.drawCircle(cx, cy, (radius - ring.strokeWidth / 2f).coerceAtLeast(0f), ring)
    }
}
