from pathlib import Path
import re


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


controller = Path("app/src/main/java/com/pixeltrigger/app/AnalogShoulderController.kt")
s = controller.read_text()

s = once(
    s,
    " * R/L itself is not a screen injection. The knob drives the existing shoulder\n * uinput backend with a real key DOWN on finger-down and key UP on finger-up.\n",
    " * R/L itself is not a screen injection. Each contact starts PENDING: reaching\n * the knob travel limit chooses R immediately; otherwise the user-selected delay\n * chooses L. The chosen shoulder remains DOWN until that same finger is released.\n",
    "doc",
)

s = once(
    s,
    '''    private enum class Binding { R, L }

    private val positionStore = OrientationPositionStore(prefs)

    private var screenWidth = 1
    private var screenHeight = 1
    private var baseSizeHundredths = normalizeSize(prefs.getInt(KEY_BASE_SIZE, DEFAULT_BASE_SIZE))
    private var knobSizeHundredths = normalizeSize(prefs.getInt(KEY_KNOB_SIZE, DEFAULT_KNOB_SIZE))
    private var binding = runCatching {
        Binding.valueOf(prefs.getString(KEY_BINDING, Binding.R.name) ?: Binding.R.name)
    }.getOrDefault(Binding.R)
''',
    '''    private enum class Binding { R, L }
    private enum class Decision { IDLE, PENDING, R_ACTIVE, L_ACTIVE }

    private val positionStore = OrientationPositionStore(prefs)

    private var screenWidth = 1
    private var screenHeight = 1
    private var baseSizeHundredths = normalizeSize(prefs.getInt(KEY_BASE_SIZE, DEFAULT_BASE_SIZE))
    private var knobSizeHundredths = normalizeSize(prefs.getInt(KEY_KNOB_SIZE, DEFAULT_KNOB_SIZE))
    private var decisionDelayMs = normalizeDecisionDelay(
        prefs.getInt(KEY_DECISION_DELAY_MS, DEFAULT_DECISION_DELAY_MS),
    )
''',
    "state",
)

s = once(
    s,
    '''    private var pressActive = false
    private var pressBinding = Binding.R
    private var downAtMs = 0L
    private var downRawX = 0f
    private var downRawY = 0f
    private var movedDuringPress = false
    private var fingerOffsetX = 0f
    private var fingerOffsetY = 0f

    private var quickTapCount = 0
    private var lastQuickTapUpMs = 0L

    val bindingLabel: String get() = binding.name
    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val knobSizeLabel: String get() = formatSize(knobSizeHundredths)
''',
    '''    private var decision = Decision.IDLE
    private var pressBinding = Binding.L
    private var fingerOffsetX = 0f
    private var fingerOffsetY = 0f
    private var pendingDecisionRunnable: Runnable? = null

    val baseSizeLabel: String get() = formatSize(baseSizeHundredths)
    val knobSizeLabel: String get() = formatSize(knobSizeHundredths)
    val decisionDelayLabel: String get() = "$decisionDelayMs ms"
''',
    "fields",
)

s = once(
    s,
    '''    fun beginEditing() {
        forceRelease()
        quickTapCount = 0
        isEditing = true
''',
    '''    fun beginEditing() {
        forceRelease()
        isEditing = true
''',
    "edit",
)

s = once(
    s,
    '''    fun decreaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, -1))
    fun increaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, +1))
    fun decreaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, -1))
    fun increaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, +1))

    private fun setBaseSize(value: Int) {
''',
    '''    fun decreaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, -1))
    fun increaseBaseSize() = setBaseSize(stepSize(baseSizeHundredths, +1))
    fun decreaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, -1))
    fun increaseKnobSize() = setKnobSize(stepSize(knobSizeHundredths, +1))
    fun decreaseDecisionDelay() = setDecisionDelay(decisionDelayMs - DECISION_DELAY_STEP_MS)
    fun increaseDecisionDelay() = setDecisionDelay(decisionDelayMs + DECISION_DELAY_STEP_MS)

    private fun setDecisionDelay(value: Int) {
        val normalized = normalizeDecisionDelay(value)
        if (normalized == decisionDelayMs) return
        forceRelease()
        decisionDelayMs = normalized
        prefs.edit().putInt(KEY_DECISION_DELAY_MS, normalized).apply()
    }

    private fun setBaseSize(value: Int) {
''',
    "delay-controls",
)

start = s.index("    private fun attachKnobTouch(view: View) {")
end = s.index("    private fun beginShoulderPress(side: Binding): Boolean", start)
s = s[:start] + '''    private fun attachKnobTouch(view: View) {
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

''' + s[end:]

release_start = s.index("    private fun endCurrentPress() {")
move_start = s.index("    private fun moveKnobToward(", release_start)
s = s[:release_start] + '''    private fun endCurrentPress() {
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

''' + s[move_start:]

s = once(
    s,
    '''    private fun moveKnobToward(desiredCenterX: Float, desiredCenterY: Float) {
        val lp = knobParams ?: return
        val view = knobView ?: return
''',
    '''    private fun moveKnobToward(desiredCenterX: Float, desiredCenterY: Float): Boolean {
        val lp = knobParams ?: return false
        val view = knobView ?: return false
''',
    "move-signature",
)

move_pos = s.index("    private fun moveKnobToward(")
center_pos = s.index("    private fun centerKnob(", move_pos)
move_block = s[move_pos:center_pos]
move_block = once(
    move_block,
    '''        lp.x = (centerX - diameter / 2f).roundToInt()
        lp.y = (centerY - diameter / 2f).roundToInt()
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

''',
    '''        lp.x = (centerX - diameter / 2f).roundToInt()
        lp.y = (centerY - diameter / 2f).roundToInt()
        runCatching { windowManager.updateViewLayout(view, lp) }

        // R uses the actual post-screen-clamp knob position.
        val actualDx = centerX - baseCenter.first
        val actualDy = centerY - baseCenter.second
        val actualDistance = hypot(actualDx.toDouble(), actualDy.toDouble()).toFloat()
        return actualDistance + 0.5f >= maxOffset
    }

''',
    "move-return",
)
s = s[:move_pos] + move_block + s[center_pos:]

visual_start = s.index("    private fun rebuildKnobVisual() {")
visual_end = s.index("    private fun baseCenter()", visual_start)
s = s[:visual_start] + '''    private fun rebuildKnobVisual() {
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

''' + s[visual_end:]

s = re.sub(
    r"\n    private fun dragSlopPxSquared\(\): Float \{.*?\n    \}\n",
    "\n",
    s,
    count=1,
    flags=re.S,
)

s = once(
    s,
    '''        private const val KEY_BASE_SIZE = "v6_analog_base_size_hundredths_cm"
        private const val KEY_KNOB_SIZE = "v6_analog_knob_size_hundredths_cm"
        private const val KEY_BINDING = "v6_analog_binding"
        private const val KEY_VISIBLE = "v6_analog_visible"
        private const val POSITION_KEY = "v6.analog.base"

        private const val DEFAULT_BASE_SIZE = 155
        private const val DEFAULT_KNOB_SIZE = 125
        private const val TRIPLE_TAP_MAX_HOLD_MS = 220L
        private const val TRIPLE_TAP_GAP_MS = 300L
''',
    '''        private const val KEY_BASE_SIZE = "v6_analog_base_size_hundredths_cm"
        private const val KEY_KNOB_SIZE = "v6_analog_knob_size_hundredths_cm"
        private const val KEY_DECISION_DELAY_MS = "v6_analog_decision_delay_ms"
        private const val KEY_VISIBLE = "v6_analog_visible"
        private const val POSITION_KEY = "v6.analog.base"

        private const val DEFAULT_BASE_SIZE = 155
        private const val DEFAULT_KNOB_SIZE = 125
        private const val DEFAULT_DECISION_DELAY_MS = 150
        private const val DECISION_DELAY_MIN_MS = 50
        private const val DECISION_DELAY_MAX_MS = 500
        private const val DECISION_DELAY_STEP_MS = 50
''',
    "constants",
)

s = once(
    s,
    '''        private fun normalizeSize(value: Int): Int = SIZE_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 25

        private fun stepSize(current: Int, delta: Int): Int {
''',
    '''        private fun normalizeSize(value: Int): Int = SIZE_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: 25

        private fun normalizeDecisionDelay(value: Int): Int {
            val clamped = value.coerceIn(DECISION_DELAY_MIN_MS, DECISION_DELAY_MAX_MS)
            return ((clamped + DECISION_DELAY_STEP_MS / 2) / DECISION_DELAY_STEP_MS * DECISION_DELAY_STEP_MS)
                .coerceIn(DECISION_DELAY_MIN_MS, DECISION_DELAY_MAX_MS)
        }

        private fun stepSize(current: Int, delta: Int): Int {
''',
    "normalize-delay",
)

for token in ["TRIPLE_TAP", "quickTapCount", "lastQuickTapUpMs", "KEY_BINDING", "bindingLabel", "private var binding ="]:
    if token in s:
        raise SystemExit(f"obsolete controller token remains: {token}")
controller.write_text(s)

screen = Path("app/src/main/java/com/pixeltrigger/app/ScreenCaptureService.kt")
s = screen.read_text()
s = once(
    s,
    'sectionLabel("V6  •  ANALOG SHOULDER  •  ${analogShoulder.bindingLabel}", Color.rgb(38, 118, 150))',
    'sectionLabel("V6  •  ANALOG SHOULDER  •  R بالسحب / L بالانتظار", Color.rgb(38, 118, 150))',
    "menu-header",
)
s = once(
    s,
    'val analog = if (::analogShoulder.isInitialized) "V6 ${analogShoulder.bindingLabel}" else "V6 --"',
    'val analog = if (::analogShoulder.isInitialized) "V6 R→drag / L→wait" else "V6 --"',
    "status",
)

knob_row = '''            content.addView(
                analogSizeRow(
                    "KNOB",
                    { analogShoulder.knobSizeLabel },
                    { analogShoulder.decreaseKnobSize() },
                    { analogShoulder.increaseKnobSize() },
                ),
                matchWrap(dp(44)),
            )
'''
wait_rows = knob_row + '''            content.addView(
                analogSizeRow(
                    "WAIT",
                    { analogShoulder.decisionDelayLabel },
                    { analogShoulder.decreaseDecisionDelay() },
                    { analogShoulder.increaseDecisionDelay() },
                ),
                matchWrap(dp(44)),
            )
'''
s = once(s, knob_row, wait_rows, "wait-row")
s = once(
    s,
    'text = "3 نقرات سريعة تبدّل R/L • الضغط والسحب يبقيان الكتف DOWN • الإفلات = UP"',
    'text = "بلوغ حد السحب قبل انتهاء WAIT = R فورًا • انتهاء WAIT أولًا = L • الإفلات قبل القرار = لا شيء"',
    "help",
)
if "analogShoulder.bindingLabel" in s or "3 نقرات سريعة تبدّل R/L" in s:
    raise SystemExit("obsolete ScreenCapture analog binding text remains")
screen.write_text(s)

gradle = Path("app/build.gradle.kts")
s = gradle.read_text()
s = once(
    s,
    '        versionCode = 62\n        versionName = "6.0-analog-shoulder"',
    '        versionCode = 63\n        versionName = "6.1-intent-routing"',
    "version",
)
gradle.write_text(s)
