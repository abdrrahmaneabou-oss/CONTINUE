package com.pixeltrigger.app.engine

import kotlin.math.abs

/**
 * PixelTrigger v4 detector.
 *
 * Arming remains exactly three consecutive WHITE frames. During those three
 * frames the detector builds a stable baseline for the fixed 0.3 mm probe.
 * Once ARMED, FIRE requires BOTH a meaningful departure from that baseline and
 * a dark current sample (average luminance <= 90). Light/non-white colors keep
 * the engine ARMED and never add a debounce, timer, or extra-frame wait.
 */
class DetectionEngine(
    var whiteRearmEnabled: Boolean = true,
    var rearmDelayEnabled: Boolean = false,
    var rearmSeconds: Int = 10,
) {
    enum class State { WAITING_FOR_WHITE, ARMED, WAITING_REARM }

    data class ColorSample(
        val averageRed: Int,
        val averageGreen: Int,
        val averageBlue: Int,
        val whiteRatio: Float,
        val darkRatio: Float,
        val averageLuminance: Int,
        val averageChroma: Int,
        val probeCount: Int = 0,
        val probe0: Int = 0,
        val probe1: Int = 0,
        val probe2: Int = 0,
        val probe3: Int = 0,
        val probe4: Int = 0,
    ) {
        fun isArmingWhite(): Boolean = whiteRatio >= ARM_WHITE_COVERAGE
        fun isHoldingWhite(): Boolean = whiteRatio >= HOLD_WHITE_COVERAGE
        fun isFireDark(): Boolean = darkRatio >= FIRE_DARK_COVERAGE
        fun isFireLuminance(): Boolean = averageLuminance <= FIRE_MAX_LUMINANCE

        fun probeAt(index: Int): Int = when (index) {
            0 -> probe0
            1 -> probe1
            2 -> probe2
            3 -> probe3
            4 -> probe4
            else -> 0
        }

        fun isProbeDepartureFrom(reference: ColorSample): Boolean {
            val count = minOf(probeCount, reference.probeCount, MAX_PROBE_POINTS)
            if (count <= 0) return false

            val quorum = probeQuorum(count)
            var changed = 0
            var i = 0
            while (i < count) {
                if (probePointChanged(reference.probeAt(i), probeAt(i))) {
                    changed++
                    if (changed >= quorum) return true
                }
                i++
            }
            return false
        }

        fun isPredictiveWhiteLossFrom(reference: ColorSample): Boolean {
            val coverageDrop = reference.whiteRatio - whiteRatio
            if (coverageDrop >= PREDICTIVE_WHITE_COVERAGE_DROP) return true

            val referenceMin = minOf(reference.averageRed, reference.averageGreen, reference.averageBlue)
            val currentMin = minOf(averageRed, averageGreen, averageBlue)
            val minChannelDrop = referenceMin - currentMin
            val luminanceDrop = reference.averageLuminance - averageLuminance
            val chromaRise = averageChroma - reference.averageChroma

            if (
                luminanceDrop >= PREDICTIVE_LUMINANCE_DROP &&
                minChannelDrop >= PREDICTIVE_MIN_CHANNEL_DROP
            ) return true

            return chromaRise >= PREDICTIVE_CHROMA_RISE &&
                luminanceDrop >= PREDICTIVE_COLOR_LUMINANCE_DROP
        }

        private fun probeQuorum(count: Int): Int = when {
            count >= 5 -> 3
            count >= 3 -> 2
            else -> 1
        }

        private fun probePointChanged(referencePacked: Int, currentPacked: Int): Boolean {
            val rr = (referencePacked ushr 16) and 0xff
            val rg = (referencePacked ushr 8) and 0xff
            val rb = referencePacked and 0xff
            val cr = (currentPacked ushr 16) and 0xff
            val cg = (currentPacked ushr 8) and 0xff
            val cb = currentPacked and 0xff

            val maxDelta = maxOf(abs(rr - cr), abs(rg - cg), abs(rb - cb))
            if (maxDelta >= PROBE_CHANNEL_DELTA) return true

            val referenceLuma = ((rr * 54) + (rg * 183) + (rb * 19)) shr 8
            val currentLuma = ((cr * 54) + (cg * 183) + (cb * 19)) shr 8
            if (referenceLuma - currentLuma >= PROBE_LUMINANCE_DROP) return true

            return isPackedWhite(referencePacked) && !isPackedWhite(currentPacked)
        }

        private fun isPackedWhite(packed: Int): Boolean {
            val r = (packed ushr 16) and 0xff
            val g = (packed ushr 8) and 0xff
            val b = packed and 0xff
            val minimum = minOf(r, g, b)
            val maximum = maxOf(r, g, b)
            val chroma = maximum - minimum
            val luminance = ((r * 54) + (g * 183) + (b * 19)) shr 8
            return luminance >= WHITE_PIXEL_LUMINANCE &&
                minimum >= WHITE_PIXEL_MIN_CHANNEL &&
                chroma <= WHITE_PIXEL_MAX_CHROMA
        }
    }

    sealed interface Event {
        data object None : Event
        data class Armed(val sample: ColorSample) : Event
        data class Fired(val firedAtMs: Long) : Event
        data class Rearmed(val sample: ColorSample) : Event
        data class ManualRearmed(val sample: ColorSample) : Event
        data object ManualRearmTimedOut : Event
        data object ManualRearmCleared : Event
    }

    var state: State = State.WAITING_FOR_WHITE
        private set
    var armedWhiteSample: ColorSample? = null
        private set
    var firedAtMs: Long = 0
        private set
    var manualRearmRequestedAtMs: Long = 0
        private set

    private var whiteFrames: Int = 0
    private var manualRearmWhiteFrames: Int = 0

    private var whiteRedSum = 0L
    private var whiteGreenSum = 0L
    private var whiteBlueSum = 0L
    private var whiteRatioSum = 0f
    private var whiteDarkRatioSum = 0f
    private var whiteLuminanceSum = 0L
    private var whiteChromaSum = 0L

    private var baselineProbeCount = 0
    private val probeRedSum = LongArray(MAX_PROBE_POINTS)
    private val probeGreenSum = LongArray(MAX_PROBE_POINTS)
    private val probeBlueSum = LongArray(MAX_PROBE_POINTS)

    fun processSample(
        sample: ColorSample,
        nowMs: Long,
        @Suppress("UNUSED_PARAMETER") fireAllowed: Boolean = true,
    ): Event {
        val manualEvent = processOneTimeRearmOverride(sample, nowMs)
        if (manualEvent is Event.ManualRearmed || manualEvent is Event.ManualRearmTimedOut) {
            return manualEvent
        }
        return updateTriggerState(sample, nowMs)
    }

    fun requestOneTimeRearmOverride(nowMs: Long): Boolean {
        if (!whiteRearmEnabled || !rearmDelayEnabled || state != State.WAITING_REARM) return false
        manualRearmRequestedAtMs = nowMs
        manualRearmWhiteFrames = 0
        return true
    }

    fun resetForSensorMove() {
        state = State.WAITING_FOR_WHITE
        clearOneTimeRearmRequest()
        armedWhiteSample = null
        resetWhiteSequence()
    }

    /**
     * Multi-sensor synchronization hook. When any sibling sensor fires, the
     * remaining detectors enter the same WAITING_REARM epoch without emitting
     * another FIRE. No timing wait or extra frame is added to the winning path.
     */
    fun synchronizeAfterExternalFire(nowMs: Long) {
        state = State.WAITING_REARM
        clearOneTimeRearmRequest()
        armedWhiteSample = null
        firedAtMs = nowMs
        resetWhiteSequence()
    }

    private fun processOneTimeRearmOverride(sample: ColorSample, nowMs: Long): Event {
        val requestedAt = manualRearmRequestedAtMs
        if (requestedAt == 0L) return Event.None

        if (state != State.WAITING_REARM || !whiteRearmEnabled || !rearmDelayEnabled) {
            clearOneTimeRearmRequest()
            return Event.ManualRearmCleared
        }
        if (nowMs - requestedAt < MANUAL_REARM_MENU_SETTLE_MS) return Event.None

        manualRearmWhiteFrames = if (sample.isArmingWhite()) manualRearmWhiteFrames + 1 else 0
        if (manualRearmWhiteFrames >= MANUAL_REARM_WHITE_FRAMES) {
            clearOneTimeRearmRequest()
            arm(sample)
            return Event.ManualRearmed(sample)
        }
        if (nowMs - requestedAt >= MANUAL_REARM_TIMEOUT_MS) {
            clearOneTimeRearmRequest()
            return Event.ManualRearmTimedOut
        }
        return Event.None
    }

    private fun updateTriggerState(sample: ColorSample, nowMs: Long): Event = when (state) {
        State.WAITING_FOR_WHITE -> {
            if (sample.isArmingWhite()) {
                appendWhite(sample)
                if (whiteFrames >= REQUIRED_ARM_FRAMES) {
                    val baseline = averagedWhiteBaseline()
                    arm(baseline)
                    Event.Armed(baseline)
                } else Event.None
            } else {
                resetWhiteSequence()
                Event.None
            }
        }

        State.ARMED -> {
            val reference = armedWhiteSample
            val changed = if (reference != null && sample.probeCount > 0 && reference.probeCount > 0) {
                sample.isProbeDepartureFrom(reference)
            } else {
                reference != null && sample.isPredictiveWhiteLossFrom(reference)
            }

            if (changed && sample.isFireLuminance()) {
                fire(nowMs)
                Event.Fired(nowMs)
            } else Event.None
        }

        State.WAITING_REARM -> {
            if (sample.isArmingWhite()) appendWhite(sample) else resetWhiteSequence()
            val whiteReady = whiteRearmEnabled && whiteFrames >= REQUIRED_REARM_FRAMES
            val delayReady = !rearmDelayEnabled || nowMs - firedAtMs >= rearmSeconds * 1000L
            if (whiteReady && delayReady) {
                val baseline = averagedWhiteBaseline()
                arm(baseline)
                Event.Rearmed(baseline)
            } else Event.None
        }
    }

    private fun appendWhite(sample: ColorSample) {
        if (whiteFrames > 0 && baselineProbeCount != sample.probeCount) {
            resetWhiteSequence()
        }
        if (whiteFrames == 0) baselineProbeCount = sample.probeCount.coerceIn(0, MAX_PROBE_POINTS)

        whiteFrames++
        whiteRedSum += sample.averageRed
        whiteGreenSum += sample.averageGreen
        whiteBlueSum += sample.averageBlue
        whiteRatioSum += sample.whiteRatio
        whiteDarkRatioSum += sample.darkRatio
        whiteLuminanceSum += sample.averageLuminance
        whiteChromaSum += sample.averageChroma

        var i = 0
        while (i < baselineProbeCount) {
            val packed = sample.probeAt(i)
            probeRedSum[i] = probeRedSum[i] + (((packed ushr 16) and 0xff).toLong())
            probeGreenSum[i] = probeGreenSum[i] + (((packed ushr 8) and 0xff).toLong())
            probeBlueSum[i] = probeBlueSum[i] + ((packed and 0xff).toLong())
            i++
        }
    }

    private fun averagedWhiteBaseline(): ColorSample {
        val count = whiteFrames.coerceAtLeast(1)
        return ColorSample(
            averageRed = (whiteRedSum / count).toInt(),
            averageGreen = (whiteGreenSum / count).toInt(),
            averageBlue = (whiteBlueSum / count).toInt(),
            whiteRatio = whiteRatioSum / count.toFloat(),
            darkRatio = whiteDarkRatioSum / count.toFloat(),
            averageLuminance = (whiteLuminanceSum / count).toInt(),
            averageChroma = (whiteChromaSum / count).toInt(),
            probeCount = baselineProbeCount,
            probe0 = averagedPackedProbe(0, count),
            probe1 = averagedPackedProbe(1, count),
            probe2 = averagedPackedProbe(2, count),
            probe3 = averagedPackedProbe(3, count),
            probe4 = averagedPackedProbe(4, count),
        )
    }

    private fun averagedPackedProbe(index: Int, count: Int): Int {
        if (index >= baselineProbeCount) return 0
        val r = (probeRedSum[index] / count).toInt()
        val g = (probeGreenSum[index] / count).toInt()
        val b = (probeBlueSum[index] / count).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun resetWhiteSequence() {
        whiteFrames = 0
        whiteRedSum = 0L
        whiteGreenSum = 0L
        whiteBlueSum = 0L
        whiteRatioSum = 0f
        whiteDarkRatioSum = 0f
        whiteLuminanceSum = 0L
        whiteChromaSum = 0L
        baselineProbeCount = 0
        var i = 0
        while (i < MAX_PROBE_POINTS) {
            probeRedSum[i] = 0L
            probeGreenSum[i] = 0L
            probeBlueSum[i] = 0L
            i++
        }
    }

    private fun arm(sample: ColorSample) {
        state = State.ARMED
        armedWhiteSample = sample
        resetWhiteSequence()
    }

    private fun fire(nowMs: Long) {
        state = State.WAITING_REARM
        clearOneTimeRearmRequest()
        armedWhiteSample = null
        firedAtMs = nowMs
        resetWhiteSequence()
    }

    private fun clearOneTimeRearmRequest() {
        manualRearmRequestedAtMs = 0L
        manualRearmWhiteFrames = 0
    }

    companion object {
        const val MAX_PROBE_POINTS = 5

        const val WHITE_PIXEL_LUMINANCE = 190
        const val WHITE_PIXEL_MIN_CHANNEL = 170
        const val WHITE_PIXEL_MAX_CHROMA = 60
        const val MIN_SAMPLE_PIXELS = 1

        const val ARM_WHITE_COVERAGE = 0.50f
        const val HOLD_WHITE_COVERAGE = 0.35f

        const val PROBE_CHANNEL_DELTA = 18
        const val PROBE_LUMINANCE_DROP = 12

        const val PREDICTIVE_WHITE_COVERAGE_DROP = 0.15f
        const val PREDICTIVE_LUMINANCE_DROP = 18
        const val PREDICTIVE_MIN_CHANNEL_DROP = 14
        const val PREDICTIVE_CHROMA_RISE = 24
        const val PREDICTIVE_COLOR_LUMINANCE_DROP = 8

        const val ARM_WHITE_AVERAGE_LUMINANCE = 195
        const val ARM_WHITE_AVERAGE_CHROMA = 50
        const val HOLD_WHITE_AVERAGE_LUMINANCE = 170
        const val HOLD_WHITE_AVERAGE_CHROMA = 70

        const val MIN_CHANGE_CHANNEL_DELTA = 30
        const val MIN_CHANGE_LUMINANCE_DROP = 26
        const val MIN_CHANGE_CHROMA_RISE = 24
        const val MIN_CHANGE_WHITE_COVERAGE_DROP = 0.35f

        const val FIRE_MAX_LUMINANCE = 90

        const val DARK_PIXEL_MAX_LUMINANCE = 88
        const val DARK_PIXEL_MAX_CHANNEL = 118
        const val DARK_PIXEL_MAX_CHROMA = 72
        const val FIRE_DARK_COVERAGE = 0.45f
        const val FIRE_MAX_CHROMA = DARK_PIXEL_MAX_CHROMA

        const val REQUIRED_ARM_FRAMES = 3
        const val REQUIRED_CHANGE_FRAMES = 1
        const val REQUIRED_REARM_FRAMES = 3
        const val SENSOR_DIAMETER_MM = 0.3f

        const val MANUAL_REARM_MENU_SETTLE_MS = 35L
        const val MANUAL_REARM_TIMEOUT_MS = 500L
        const val MANUAL_REARM_WHITE_FRAMES = 3
    }
}
