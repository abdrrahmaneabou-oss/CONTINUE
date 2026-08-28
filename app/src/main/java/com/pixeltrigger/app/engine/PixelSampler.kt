package com.pixeltrigger.app.engine

import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * PixelProbe v4 sampler.
 *
 * The monitored region is still exactly the configured 0.3 mm circle. The
 * difference from the old sampler is that we no longer walk every pixel in the
 * geometric circle on every frame. A tiny fixed probe plan is built once for
 * the current capture scale and then reused as direct byte offsets relative to
 * the probe center.
 *
 * Preferred plan: center + left + right + top + bottom (up to 5 points), but
 * only points that are geometrically inside the 0.3 mm ellipse are accepted.
 * If down-scaling makes the circle smaller than one capture pixel, the center
 * pixel alone is used rather than silently reading outside the requested ROI.
 */
object PixelSampler {
    private const val MAX_PROBE_POINTS = 5

    private data class ProbePlan(
        val radiusXBits: Int,
        val radiusYBits: Int,
        val count: Int,
        val dx: IntArray,
        val dy: IntArray,
    )

    private var cachedPlan: ProbePlan? = null

    fun sampleCircularRegion(
        image: Image,
        centerX: Int,
        centerY: Int,
        radiusX: Float,
        radiusY: Float,
    ): DetectionEngine.ColorSample? {
        val crop: Rect = image.cropRect
        if (centerX !in crop.left until crop.right || centerY !in crop.top until crop.bottom) return null

        val planes = image.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride < 3 || rowStride <= 0) return null

        val plan = planFor(radiusX, radiusY)
        val base = buffer.position()

        var redTotal = 0
        var greenTotal = 0
        var blueTotal = 0
        var luminanceTotal = 0
        var chromaTotal = 0
        var whiteCount = 0
        var darkCount = 0
        var count = 0

        var probe0 = 0
        var probe1 = 0
        var probe2 = 0
        var probe3 = 0
        var probe4 = 0

        var i = 0
        while (i < plan.count) {
            val x = centerX + plan.dx[i]
            val y = centerY + plan.dy[i]
            if (x >= crop.left && x < crop.right && y >= crop.top && y < crop.bottom) {
                val offset = base + y * rowStride + x * pixelStride
                if (offset >= 0 && offset + 2 < buffer.limit()) {
                    val red = buffer.get(offset).toInt() and 0xff
                    val green = buffer.get(offset + 1).toInt() and 0xff
                    val blue = buffer.get(offset + 2).toInt() and 0xff
                    val minimumChannel = min(red, min(green, blue))
                    val maximumChannel = max(red, max(green, blue))
                    val chroma = maximumChannel - minimumChannel
                    val luminance = ((red * 54) + (green * 183) + (blue * 19)) shr 8
                    val packed = (red shl 16) or (green shl 8) or blue

                    when (count) {
                        0 -> probe0 = packed
                        1 -> probe1 = packed
                        2 -> probe2 = packed
                        3 -> probe3 = packed
                        4 -> probe4 = packed
                    }

                    redTotal += red
                    greenTotal += green
                    blueTotal += blue
                    luminanceTotal += luminance
                    chromaTotal += chroma

                    if (
                        luminance >= DetectionEngine.WHITE_PIXEL_LUMINANCE &&
                        minimumChannel >= DetectionEngine.WHITE_PIXEL_MIN_CHANNEL &&
                        chroma <= DetectionEngine.WHITE_PIXEL_MAX_CHROMA
                    ) {
                        whiteCount++
                    }

                    if (
                        luminance <= DetectionEngine.DARK_PIXEL_MAX_LUMINANCE &&
                        maximumChannel <= DetectionEngine.DARK_PIXEL_MAX_CHANNEL &&
                        chroma <= DetectionEngine.DARK_PIXEL_MAX_CHROMA
                    ) {
                        darkCount++
                    }
                    count++
                }
            }
            i++
        }

        if (count < DetectionEngine.MIN_SAMPLE_PIXELS) return null
        return DetectionEngine.ColorSample(
            averageRed = redTotal / count,
            averageGreen = greenTotal / count,
            averageBlue = blueTotal / count,
            whiteRatio = whiteCount.toFloat() / count.toFloat(),
            darkRatio = darkCount.toFloat() / count.toFloat(),
            averageLuminance = luminanceTotal / count,
            averageChroma = chromaTotal / count,
            probeCount = count,
            probe0 = probe0,
            probe1 = probe1,
            probe2 = probe2,
            probe3 = probe3,
            probe4 = probe4,
        )
    }

    private fun planFor(radiusX: Float, radiusY: Float): ProbePlan {
        val safeRadiusX = max(radiusX, 0.5f)
        val safeRadiusY = max(radiusY, 0.5f)
        val xBits = safeRadiusX.toBits()
        val yBits = safeRadiusY.toBits()
        cachedPlan?.let { if (it.radiusXBits == xBits && it.radiusYBits == yBits) return it }

        val xs = IntArray(MAX_PROBE_POINTS)
        val ys = IntArray(MAX_PROBE_POINTS)
        var count = 0

        fun addIfInside(dx: Int, dy: Int) {
            if (count >= MAX_PROBE_POINTS) return
            val nx = dx / safeRadiusX
            val ny = dy / safeRadiusY
            if (nx * nx + ny * ny <= 1f) {
                var existing = false
                var j = 0
                while (j < count) {
                    if (xs[j] == dx && ys[j] == dy) {
                        existing = true
                        break
                    }
                    j++
                }
                if (!existing) {
                    xs[count] = dx
                    ys[count] = dy
                    count++
                }
            }
        }

        // Center first, then four cardinal points. The step is chosen once and
        // kept inside the requested ellipse; no circle scan occurs per frame.
        addIfInside(0, 0)
        val stepX = max(1, floor(safeRadiusX).toInt())
        val stepY = max(1, floor(safeRadiusY).toInt())
        addIfInside(-stepX, 0)
        addIfInside(stepX, 0)
        addIfInside(0, -stepY)
        addIfInside(0, stepY)

        return ProbePlan(
            radiusXBits = xBits,
            radiusYBits = yBits,
            count = count,
            dx = xs,
            dy = ys,
        ).also { cachedPlan = it }
    }
}
