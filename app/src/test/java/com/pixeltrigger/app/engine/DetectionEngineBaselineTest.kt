package com.pixeltrigger.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionEngineBaselineTest {
    private fun sample(
        r: Int,
        g: Int,
        b: Int,
        whiteRatio: Float,
        darkRatio: Float,
        luminance: Int,
        chroma: Int,
    ) = DetectionEngine.ColorSample(r, g, b, whiteRatio, darkRatio, luminance, chroma)

    private fun rgb(v: Int): Int = (v shl 16) or (v shl 8) or v

    private fun probeSample(
        p0: Int,
        p1: Int = p0,
        p2: Int = p0,
        p3: Int = p0,
        p4: Int = p0,
        count: Int = 5,
        whiteRatio: Float = 1.0f,
    ): DetectionEngine.ColorSample {
        val values = intArrayOf(p0, p1, p2, p3, p4)
        var r = 0
        var g = 0
        var b = 0
        var i = 0
        while (i < count) {
            val packed = values[i]
            r += (packed ushr 16) and 0xff
            g += (packed ushr 8) and 0xff
            b += packed and 0xff
            i++
        }
        val divisor = count.coerceAtLeast(1)
        val ar = r / divisor
        val ag = g / divisor
        val ab = b / divisor
        val minimum = minOf(ar, ag, ab)
        val maximum = maxOf(ar, ag, ab)
        val chroma = maximum - minimum
        val luminance = ((ar * 54) + (ag * 183) + (ab * 19)) shr 8
        return DetectionEngine.ColorSample(
            averageRed = ar,
            averageGreen = ag,
            averageBlue = ab,
            whiteRatio = whiteRatio,
            darkRatio = 0f,
            averageLuminance = luminance,
            averageChroma = chroma,
            probeCount = count,
            probe0 = p0,
            probe1 = p1,
            probe2 = p2,
            probe3 = p3,
            probe4 = p4,
        )
    }

    private val white = sample(240, 240, 240, 0.90f, 0.00f, 240, 0)
    private val mixedWhite = sample(205, 205, 205, 0.55f, 0.00f, 182, 65)
    private val lightBlue = sample(45, 170, 205, 0.02f, 0.00f, 150, 160)
    private val mediumGray = sample(120, 120, 120, 0.05f, 0.00f, 120, 0)
    private val nearBlack = sample(19, 24, 29, 0.00f, 0.95f, 23, 10)
    private val darkSaturatedBlue = sample(0, 0, 150, 0.00f, 0.00f, 17, 150)

    private fun arm(e: DetectionEngine, s: DetectionEngine.ColorSample = white) {
        assertTrue(e.processSample(s, 1) is DetectionEngine.Event.None)
        assertTrue(e.processSample(s, 2) is DetectionEngine.Event.None)
        assertTrue(e.processSample(s, 3) is DetectionEngine.Event.Armed)
        assertEquals(DetectionEngine.State.ARMED, e.state)
    }

    @Test fun armingNeedsThreeConsecutiveWhiteFrames() {
        arm(DetectionEngine())
    }

    @Test fun nonWhiteBreaksConsecutiveWhiteSequenceBeforeArming() {
        val e = DetectionEngine()
        e.processSample(white, 1)
        e.processSample(white, 2)
        e.processSample(lightBlue, 3)
        assertTrue(e.processSample(white, 4) is DetectionEngine.Event.None)
        assertTrue(e.processSample(white, 5) is DetectionEngine.Event.None)
        assertTrue(e.processSample(white, 6) is DetectionEngine.Event.Armed)
    }

    @Test fun nonWhiteCannotArmFromWaiting() {
        val e = DetectionEngine()
        repeat(12) { index ->
            val s = when (index % 4) {
                0 -> lightBlue
                1 -> mediumGray
                2 -> nearBlack
                else -> darkSaturatedBlue
            }
            assertTrue(e.processSample(s, index.toLong()) is DetectionEngine.Event.None)
        }
        assertEquals(DetectionEngine.State.WAITING_FOR_WHITE, e.state)
    }

    @Test fun lightColorChangeNeverFiresAndKeepsArmed() {
        val e = DetectionEngine()
        arm(e)
        assertTrue(e.processSample(lightBlue, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)
        assertTrue(e.processSample(mediumGray, 5) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)
    }

    @Test fun oldThirtyFivePercentWhiteFallbackCannotFireLightColor() {
        val e = DetectionEngine()
        arm(e)
        val changedButLight = sample(100, 100, 100, 0.00f, 0.00f, 100, 0)
        assertTrue(e.processSample(changedButLight, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)
    }

    @Test fun darkFrameFiresImmediatelyAfterLightFramesWithoutRearming() {
        val e = DetectionEngine()
        arm(e)
        assertTrue(e.processSample(lightBlue, 4) is DetectionEngine.Event.None)
        assertTrue(e.processSample(mediumGray, 5) is DetectionEngine.Event.None)
        assertTrue(e.processSample(nearBlack, 6) is DetectionEngine.Event.Fired)
        assertEquals(DetectionEngine.State.WAITING_REARM, e.state)
    }

    @Test fun luminance90IsAcceptedAnd91IsRejected() {
        val reject = DetectionEngine()
        arm(reject)
        val l91 = sample(91, 91, 91, 0f, 0f, 91, 0)
        assertTrue(reject.processSample(l91, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, reject.state)

        val accept = DetectionEngine()
        arm(accept)
        val l90 = sample(90, 90, 90, 0f, 0f, 90, 0)
        assertTrue(accept.processSample(l90, 4) is DetectionEngine.Event.Fired)
    }

    @Test fun orangeBrownAround82NowFires() {
        val e = DetectionEngine()
        arm(e)
        val orangeBrown = sample(147, 70, 26, 0f, 0f, 82, 121)
        assertTrue(e.processSample(orangeBrown, 4) is DetectionEngine.Event.Fired)
    }

    @Test fun darkSaturatedColorCanFireBecauseGateIsDarknessNotHue() {
        val e = DetectionEngine()
        arm(e)
        assertTrue(e.processSample(darkSaturatedBlue, 4) is DetectionEngine.Event.Fired)
    }

    @Test fun legacyReadinessFlagCannotDelayQualifiedDarkFire() {
        val e = DetectionEngine()
        arm(e)
        assertTrue(e.processSample(nearBlack, 4, fireAllowed = false) is DetectionEngine.Event.Fired)
    }

    @Test fun mixedWhiteStillArmsAfterThreeFramesAndDoesNotSelfFire() {
        val e = DetectionEngine()
        arm(e, mixedWhite)
        assertTrue(e.processSample(mixedWhite, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)
    }

    @Test fun v4FivePointProbeRequiresQuorumAndDarkness() {
        val e = DetectionEngine()
        val armedWhite = probeSample(rgb(240))
        arm(e, armedWhite)

        val twoDarkThreeWhite = probeSample(
            p0 = rgb(20),
            p1 = rgb(20),
            p2 = rgb(240),
            p3 = rgb(240),
            p4 = rgb(240),
            whiteRatio = 0.60f,
        )
        assertTrue(e.processSample(twoDarkThreeWhite, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)

        val threeChangedButAverageTooLight = probeSample(
            p0 = rgb(90),
            p1 = rgb(90),
            p2 = rgb(90),
            p3 = rgb(240),
            p4 = rgb(240),
            whiteRatio = 0.40f,
        )
        assertTrue(e.processSample(threeChangedButAverageTooLight, 5) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)

        val fiveDark = probeSample(rgb(90), whiteRatio = 0f)
        assertTrue(e.processSample(fiveDark, 6) is DetectionEngine.Event.Fired)
    }

    @Test fun v4OneCapturePixelProbeFiresOnFirstQualifiedDarkFrame() {
        val e = DetectionEngine()
        val armedWhite = probeSample(rgb(240), count = 1)
        arm(e, armedWhite)

        val changedLight = probeSample(rgb(100), count = 1, whiteRatio = 0f)
        assertTrue(e.processSample(changedLight, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)

        val changedDark = probeSample(rgb(90), count = 1, whiteRatio = 0f)
        assertTrue(e.processSample(changedDark, 5) is DetectionEngine.Event.Fired)
    }

    @Test fun v4SmallPerPixelJitterDoesNotFire() {
        val e = DetectionEngine()
        val armedWhite = probeSample(rgb(240))
        arm(e, armedWhite)
        val jitter = probeSample(rgb(231))
        assertTrue(e.processSample(jitter, 4) is DetectionEngine.Event.None)
        assertEquals(DetectionEngine.State.ARMED, e.state)
    }

    @Test fun v4ThreeWhiteFramesAreAveragedIntoProbeBaseline() {
        val e = DetectionEngine()
        e.processSample(probeSample(rgb(236)), 1)
        e.processSample(probeSample(rgb(240)), 2)
        assertTrue(e.processSample(probeSample(rgb(244)), 3) is DetectionEngine.Event.Armed)
        val baseline = e.armedWhiteSample!!
        assertEquals(rgb(240), baseline.probe0)
        assertEquals(5, baseline.probeCount)
    }

    @Test fun frozenStateRulesRemainExact() {
        assertEquals(3, DetectionEngine.REQUIRED_ARM_FRAMES)
        assertEquals(3, DetectionEngine.REQUIRED_REARM_FRAMES)
        assertEquals(3, DetectionEngine.MANUAL_REARM_WHITE_FRAMES)
        assertEquals(0.3f, DetectionEngine.SENSOR_DIAMETER_MM)
        assertEquals(5, DetectionEngine.MAX_PROBE_POINTS)
        assertEquals(1, DetectionEngine.MIN_SAMPLE_PIXELS)
        assertEquals(18, DetectionEngine.PROBE_CHANNEL_DELTA)
        assertEquals(12, DetectionEngine.PROBE_LUMINANCE_DROP)
        assertEquals(90, DetectionEngine.FIRE_MAX_LUMINANCE)
        assertEquals(0.50f, DetectionEngine.ARM_WHITE_COVERAGE)
        assertEquals(190, DetectionEngine.WHITE_PIXEL_LUMINANCE)
        assertEquals(170, DetectionEngine.WHITE_PIXEL_MIN_CHANNEL)
        assertEquals(60, DetectionEngine.WHITE_PIXEL_MAX_CHROMA)
    }
}
