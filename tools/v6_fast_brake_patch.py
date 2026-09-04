from pathlib import Path
import re


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, got {count}")
    return text.replace(old, new, 1)


# ---------------- AnalogShoulderController ----------------
p = Path('app/src/main/java/com/pixeltrigger/app/AnalogShoulderController.kt')
s = p.read_text()

s = once(s,
'''    private var decisionDelayMs = normalizeDecisionDelay(
        prefs.getInt(KEY_DECISION_DELAY_MS, DEFAULT_DECISION_DELAY_MS),
    )

    var isVisible: Boolean = prefs.getBoolean(KEY_VISIBLE, true)
''',
'''    private var decisionDelayMs = normalizeDecisionDelay(
        prefs.getInt(KEY_DECISION_DELAY_MS, DEFAULT_DECISION_DELAY_MS),
    )

    var fastEnabled: Boolean = prefs.getBoolean(KEY_FAST_ENABLED, false)
        private set
    var brakeEnabled: Boolean = prefs.getBoolean(KEY_BRAKE_ENABLED, true)
        private set
    private var brakeOpen = false

    var isVisible: Boolean = prefs.getBoolean(KEY_VISIBLE, true)
''', 'analog feature prefs')

s = once(s,
'''    private var decision = Decision.IDLE
    private var pressBinding = Binding.L
    private var fingerOffsetX = 0f
''',
'''    private var decision = Decision.IDLE
    private var pressBinding = Binding.L
    private var outputDown = false
    private var fingerOffsetX = 0f
''', 'analog output ownership')

s = once(s,
'''    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val knobSizeLabel: String get() = formatSize(knobSizeHundredths)
    val decisionDelayLabel: String get() = "$decisionDelayMs ms"
''',
'''    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val knobSizeLabel: String get() = formatSize(knobSizeHundredths)
    val decisionDelayLabel: String get() = "$decisionDelayMs ms"
    val fastLabel: String get() = if (fastEnabled) "FAST ON" else "FAST OFF"
    val brakeLabel: String get() = if (brakeEnabled) "مكابح المراقبة ON" else "مكابح المراقبة OFF"
''', 'analog labels')

s = once(s,
'''    fun decreaseDecisionDelay() = setDecisionDelay(decisionDelayMs - DECISION_DELAY_STEP_MS)
    fun increaseDecisionDelay() = setDecisionDelay(decisionDelayMs + DECISION_DELAY_STEP_MS)

    private fun setDecisionDelay(value: Int) {
''',
'''    fun decreaseDecisionDelay() = setDecisionDelay(decisionDelayMs - DECISION_DELAY_STEP_MS)
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
''', 'analog feature controls')

s = once(s,
'''                        if (decision == Decision.PENDING && reachedRLimit) {
                            activateDecision(Binding.R)
                        }
''',
'''                        if (decision == Decision.PENDING && reachedRLimit) {
                            activateDecision(Binding.R)
                        } else if (decision == Decision.L_ACTIVE && fastEnabled && reachedRLimit) {
                            upgradeLToR()
                        }
''', 'FAST move routing')

old_logic = '''    private fun activateDecision(side: Binding) {
        if (decision != Decision.PENDING) return
        cancelPendingDecision()
        pressBinding = side
        decision = if (side == Binding.R) Decision.R_ACTIVE else Decision.L_ACTIVE
        beginShoulderPress(side)
        knobView?.alpha = 0.78f
        rebuildKnobVisual()
    }

    private fun finishContact() {
        when (decision) {
            Decision.PENDING -> cancelPendingDecision()
            Decision.R_ACTIVE, Decision.L_ACTIVE -> endCurrentPress()
            Decision.IDLE -> Unit
        }
        decision = Decision.IDLE
    }

    private fun beginShoulderPress(side: Binding): Boolean = when (side) {
        Binding.R -> ShoulderCaptureService.beginFingerHoldR()
        Binding.L -> ShoulderCaptureService.beginFingerHoldL()
    }

    private fun endCurrentPress() {
        if (decision != Decision.R_ACTIVE && decision != Decision.L_ACTIVE) return
        when (pressBinding) {
            Binding.R -> ShoulderCaptureService.endFingerHoldR()
            Binding.L -> ShoulderCaptureService.endFingerHoldL()
        }
    }

    private fun forceRelease() {
        cancelPendingDecision()
        if (decision == Decision.R_ACTIVE || decision == Decision.L_ACTIVE) {
            endCurrentPress()
        }
        ShoulderCaptureService.endAnyFingerHold()
        decision = Decision.IDLE
        knobView?.alpha = 1f
        centerKnob()
        rebuildKnobVisual()
    }
'''
new_logic = '''    private fun activateDecision(side: Binding) {
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
'''
s = once(s, old_logic, new_logic, 'analog gated output logic')

s = once(s,
'''        private const val KEY_DECISION_DELAY_MS = "v6_analog_decision_delay_ms"
        private const val KEY_VISIBLE = "v6_analog_visible"
''',
'''        private const val KEY_DECISION_DELAY_MS = "v6_analog_decision_delay_ms"
        private const val KEY_FAST_ENABLED = "v6_analog_fast_enabled"
        private const val KEY_BRAKE_ENABLED = "v6_analog_brake_enabled"
        private const val KEY_VISIBLE = "v6_analog_visible"
''', 'analog keys')

for token in ['KEY_FAST_ENABLED', 'KEY_BRAKE_ENABLED', 'upgradeLToR()', 'reconcileOutput()', 'setBrakeOpen(open: Boolean)']:
    if token not in s:
        raise SystemExit(f'analog invariant missing: {token}')
p.write_text(s)


# ---------------- ScreenCaptureService ----------------
p = Path('app/src/main/java/com/pixeltrigger/app/ScreenCaptureService.kt')
s = p.read_text()

s = once(s,
'''    private lateinit var manualTapPair: ManualNubiaPairController
    private lateinit var analogShoulder: AnalogShoulderController

    private var menuButton: TextView? = null
''',
'''    private lateinit var manualTapPair: ManualNubiaPairController
    private lateinit var analogShoulder: AnalogShoulderController
    private var analogBrakeView: SensorOverlayView? = null
    private var analogBrakeParams: WindowManager.LayoutParams? = null
    @Volatile private var analogBrakeEditing = false
    @Volatile private var analogBrakeOpen = false

    private var menuButton: TextView? = null
''', 'screen brake fields')

s = once(s,
'''    private fun processImage(image: Image) {
        // Right half remains first so the independent shoulder half can never
        // delay PixelProbe detection or its confirmed Nubia tap.
        processRightFrame(image)
        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)
    }

    private fun processRightFrame(image: Image) {
''',
'''    private fun processImage(image: Image) {
        // V6 brake shares the immutable capture frame but owns no PixelProbe state.
        processAnalogBrakeFrame(image)
        // Right half remains independent so V6 gating cannot change PixelProbe behavior.
        processRightFrame(image)
        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)
    }

    private fun processAnalogBrakeFrame(image: Image) {
        if (!::analogShoulder.isInitialized || !analogShoulder.brakeEnabled || analogBrakeEditing) return
        if (screenWidth <= 0 || screenHeight <= 0) return
        val crop = image.cropRect
        if (crop.width() <= 0 || crop.height() <= 0) return
        val lp = analogBrakeParams ?: return
        val sample = sampleSensor(image, crop, lp) ?: return

        // Exact same near-white definition used to arm the right-half 0.3 mm probes.
        // White = NO FIRE (green). Any clear departure = FIRE allowed (red).
        val open = !sample.isArmingWhite()
        if (open == analogBrakeOpen) return
        analogBrakeOpen = open
        analogShoulder.setBrakeOpen(open)
        mainHandler.post {
            analogBrakeView?.setStatus(if (open) SensorStatus.FIRED else SensorStatus.ARMED)
            menuStatusText?.text = combinedStatusText()
        }
    }

    private fun processRightFrame(image: Image) {
''', 'screen frame brake processing')

s = once(s,
'''        analogShoulder = AnalogShoulderController(this, windowManager, preferences)
        analogShoulder.create(screenWidth, screenHeight)
        analogShoulder.setInputEnabled(shoulderHalfEnabled)

        val buttonSize = dp(50)
''',
'''        analogShoulder = AnalogShoulderController(this, windowManager, preferences)
        analogShoulder.create(screenWidth, screenHeight)
        analogShoulder.setInputEnabled(shoulderHalfEnabled)
        createAnalogBrakeSensor()

        val buttonSize = dp(50)
''', 'create brake overlay hook')

insert_before = '''    private fun createGroupFiveExtraSensors() {
'''
brake_funcs = '''    private fun createAnalogBrakeSensor() {
        if (analogBrakeView != null) return
        val view = SensorOverlayView(this, sensorVisibleDiameter)
        view.setStatus(SensorStatus.ARMED)
        val saved = loadAnalogBrakePosition(
            screenWidth / 2 + dp(120) - sensorTouchSize / 2,
            screenHeight / 2 - sensorTouchSize / 2,
        )
        val lp = overlayParams(sensorTouchSize, sensorTouchSize).apply {
            x = saved.x
            y = saved.y
            flags = baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        clampCirclePosition(lp, sensorVisibleDiameter)
        analogBrakeView = view
        analogBrakeParams = lp
        windowManager.addView(view, lp)
        attachDrag(view, lp, sensorVisibleDiameter) { x, y -> saveAnalogBrakePosition(x, y) }
        analogBrakeOpen = false
        analogShoulder.setBrakeOpen(false)
        applyAnalogBrakeVisibilityAndTouchability()
    }

    private fun beginAnalogBrakeEditing() {
        if (!::analogShoulder.isInitialized || !analogShoulder.brakeEnabled) {
            showMessage("فعّل مكابح المراقبة أولًا")
            return
        }
        closeMenu()
        analogBrakeEditing = true
        analogBrakeOpen = false
        analogShoulder.setBrakeOpen(false)
        applyAnalogBrakeVisibilityAndTouchability()
        updateButtonVisual()
        showMessage("حرّك دائرة مكابح المراقبة ثم افتح القائمة واضغط حفظ المكبح")
    }

    private fun finishAnalogBrakeEditing() {
        if (!analogBrakeEditing) return
        analogBrakeEditing = false
        analogBrakeParams?.let { saveAnalogBrakePosition(it.x, it.y) }
        analogBrakeOpen = false
        analogShoulder.setBrakeOpen(false)
        analogBrakeView?.setStatus(SensorStatus.ARMED)
        applyAnalogBrakeVisibilityAndTouchability()
        updateButtonVisual()
        showMessage("تم حفظ موضع مكابح المراقبة")
    }

    private fun toggleAnalogBrake() {
        if (!::analogShoulder.isInitialized) return
        if (analogBrakeEditing) finishAnalogBrakeEditing()
        analogShoulder.toggleBrakeEnabled()
        analogBrakeOpen = false
        if (analogShoulder.brakeEnabled) {
            analogShoulder.setBrakeOpen(false)
            analogBrakeView?.setStatus(SensorStatus.ARMED)
        }
        applyAnalogBrakeVisibilityAndTouchability()
        menuStatusText?.text = combinedStatusText()
    }

    private fun applyAnalogBrakeVisibilityAndTouchability() {
        val view = analogBrakeView ?: return
        val lp = analogBrakeParams ?: return
        val enabled = ::analogShoulder.isInitialized && analogShoulder.brakeEnabled
        view.visibility = if (enabled || analogBrakeEditing) View.VISIBLE else View.INVISIBLE
        lp.flags = if (analogBrakeEditing) baseOverlayFlags()
        else baseOverlayFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

'''
s = once(s, insert_before, brake_funcs + insert_before, 'brake overlay functions')

# Add FAST + brake controls to V6 menu immediately after WAIT row.
wait_block = '''            content.addView(
                analogSizeRow(
                    "WAIT",
                    { analogShoulder.decisionDelayLabel },
                    { analogShoulder.decreaseDecisionDelay() },
                    { analogShoulder.increaseDecisionDelay() },
                ),
                matchWrap(dp(44)),
            )
'''
feature_ui = wait_block + '''            val fastBrakeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fastBrakeRow.addView(
                microCard(analogShoulder.fastLabel) {
                    analogShoulder.toggleFast()
                    closeMenu()
                },
                LinearLayout.LayoutParams(0, dp(40), 1f),
            )
            fastBrakeRow.addView(
                microCard(analogShoulder.brakeLabel) {
                    toggleAnalogBrake()
                    closeMenu()
                },
                LinearLayout.LayoutParams(0, dp(40), 1f),
            )
            content.addView(fastBrakeRow, matchWrap(dp(42)))

            val brakeEditRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            brakeEditRow.addView(
                microCard("✥ تعديل المكبح") { beginAnalogBrakeEditing() },
                LinearLayout.LayoutParams(0, dp(38), 1f),
            )
            brakeEditRow.addView(
                microCard("✓ حفظ المكبح") {
                    finishAnalogBrakeEditing()
                    closeMenu()
                },
                LinearLayout.LayoutParams(0, dp(38), 1f),
            )
            content.addView(brakeEditRow, matchWrap(dp(40)))
'''
s = once(s, wait_block, feature_ui, 'FAST brake menu cards')

s = once(s,
'''                    text = "بلوغ حد السحب قبل انتهاء WAIT = R فورًا • انتهاء WAIT أولًا = L • الإفلات قبل القرار = لا شيء"
''',
'''                    text = "R قبل WAIT = فوري • FAST يسمح L→R بعد WAIT • الأخضر يمنع/يوقف R/L • الأحمر يسمح ويعيد التشغيل ما دام الإصبع ضاغطًا"
''', 'V6 help text')

s = once(s,
'''        val analog = if (::analogShoulder.isInitialized) "V6 R→drag / L→wait" else "V6 --"
''',
'''        val analog = if (::analogShoulder.isInitialized) {
            val brake = if (analogShoulder.brakeEnabled) {
                if (analogBrakeOpen) "BRAKE FIRE" else "BRAKE NO-FIRE"
            } else "BRAKE OFF"
            "V6 ${if (analogShoulder.fastEnabled) "FAST" else "LOCK"} $brake"
        } else "V6 --"
''', 'combined V6 status')

s = once(s,
'''                (!::analogShoulder.isInitialized || !analogShoulder.isEditing)
''',
'''                (!::analogShoulder.isInitialized || !analogShoulder.isEditing) &&
                !analogBrakeEditing
''', 'hold guard brake edit')

# There are two matching edit-mode visual expressions; update both with brake editing.
s = s.replace(
'''                (::analogShoulder.isInitialized && analogShoulder.isEditing) -> Color.rgb(30, 165, 92)
''',
'''                (::analogShoulder.isInitialized && analogShoulder.isEditing) || analogBrakeEditing -> Color.rgb(30, 165, 92)
''', 1)
s = s.replace(
'''                (::analogShoulder.isInitialized && analogShoulder.isEditing) -> "✓"
''',
'''                (::analogShoulder.isInitialized && analogShoulder.isEditing) || analogBrakeEditing -> "✓"
''', 1)

s = once(s,
'''        targetView?.visibility = if (circlesVisible) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshDisplayGeometry() {
''',
'''        targetView?.visibility = if (circlesVisible) View.VISIBLE else View.INVISIBLE
        applyAnalogBrakeVisibilityAndTouchability()
    }

    private fun refreshDisplayGeometry() {
''', 'brake visibility hook')

s = once(s,
'''        if (::analogShoulder.isInitialized) analogShoulder.updateBounds(screenWidth, screenHeight)
        menuButtonParams?.let { lp ->
''',
'''        if (::analogShoulder.isInitialized) analogShoulder.updateBounds(screenWidth, screenHeight)
        restoreAnalogBrakePositionForCurrentProfile()
        menuButtonParams?.let { lp ->
''', 'rotation brake restore hook')

# Position helpers before rightSensorPositionKey.
position_anchor = '''    private fun rightSensorPositionKey(group: Int, slot: Int): String =
'''
position_funcs = '''    private fun loadAnalogBrakePosition(
        fallbackX: Int,
        fallbackY: Int,
    ): OrientationPositionStore.Position = positionStore.load(
        keyPrefix = ANALOG_BRAKE_POSITION_KEY,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        overlayWidth = sensorTouchSize,
        overlayHeight = sensorTouchSize,
        fallbackX = fallbackX,
        fallbackY = fallbackY,
    )

    private fun saveAnalogBrakePosition(x: Int, y: Int) {
        positionStore.save(
            keyPrefix = ANALOG_BRAKE_POSITION_KEY,
            x = x,
            y = y,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            overlayWidth = sensorTouchSize,
            overlayHeight = sensorTouchSize,
        )
    }

    private fun restoreAnalogBrakePositionForCurrentProfile() {
        val lp = analogBrakeParams ?: return
        val view = analogBrakeView ?: return
        val saved = loadAnalogBrakePosition(
            screenWidth / 2 + dp(120) - sensorTouchSize / 2,
            screenHeight / 2 - sensorTouchSize / 2,
        )
        lp.x = saved.x
        lp.y = saved.y
        clampCirclePosition(lp, sensorVisibleDiameter)
        runCatching { windowManager.updateViewLayout(view, lp) }
        analogBrakeOpen = false
        if (::analogShoulder.isInitialized && analogShoulder.brakeEnabled) analogShoulder.setBrakeOpen(false)
        view.setStatus(SensorStatus.ARMED)
        applyAnalogBrakeVisibilityAndTouchability()
    }

'''
s = once(s, position_anchor, position_funcs + position_anchor, 'brake position helpers')

s = once(s,
'''        targetView?.let { runCatching { windowManager.removeView(it) } }
        if (::manualTapPair.isInitialized) manualTapPair.destroy()
''',
'''        targetView?.let { runCatching { windowManager.removeView(it) } }
        analogBrakeView?.let { runCatching { windowManager.removeView(it) } }
        if (::manualTapPair.isInitialized) manualTapPair.destroy()
''', 'destroy brake overlay')

s = once(s,
'''        private const val RIGHT_TARGET_POSITION_KEY = "right.target"
''',
'''        private const val RIGHT_TARGET_POSITION_KEY = "right.target"
        private const val ANALOG_BRAKE_POSITION_KEY = "v6.analog.brake"
''', 'brake position constant')

# Version bump.
p.write_text(s)

p = Path('app/build.gradle.kts')
s = p.read_text()
s = once(s, 'versionCode = 63', 'versionCode = 64', 'version code')
s = once(s, 'versionName = "6.1-intent-routing"', 'versionName = "6.2-fast-monitor-brake"', 'version name')
p.write_text(s)

# Source invariants before compile.
controller = Path('app/src/main/java/com/pixeltrigger/app/AnalogShoulderController.kt').read_text()
service = Path('app/src/main/java/com/pixeltrigger/app/ScreenCaptureService.kt').read_text()
checks = {
    'FAST upgrade': 'decision == Decision.L_ACTIVE && fastEnabled && reachedRLimit',
    'continuous gate': 'private fun reconcileOutput()',
    'brake callback': 'fun setBrakeOpen(open: Boolean)',
    'brake sample': 'val open = !sample.isArmingWhite()',
    'green status': 'SensorStatus.ARMED',
    'red status': 'SensorStatus.FIRED',
    'brake card': 'مكابح المراقبة ON',
    'brake position': 'ANALOG_BRAKE_POSITION_KEY',
}
all_source = controller + '\n' + service
for label, needle in checks.items():
    if needle not in all_source:
        raise SystemExit(f'missing invariant {label}: {needle}')
