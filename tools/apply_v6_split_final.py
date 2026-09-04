from pathlib import Path
import re

ROOT = Path('.')

# ---- ScreenCaptureService: remove brake/wait/fast/sensitivity UI and swap visible R/L labels only.
p = ROOT / 'app/src/main/java/com/pixeltrigger/app/ScreenCaptureService.kt'
text = p.read_text()

text = text.replace('import android.widget.SeekBar\n', '')

old = '''    private lateinit var manualTapPair: ManualNubiaPairController\n    private lateinit var analogShoulder: AnalogShoulderController\n    private var analogBrakeView: SensorOverlayView? = null\n    private var analogBrakeParams: WindowManager.LayoutParams? = null\n    @Volatile private var analogBrakeEditing = false\n    @Volatile private var analogBrakeOpen = false\n'''
new = '''    private lateinit var manualTapPair: ManualNubiaPairController\n    private lateinit var analogShoulder: AnalogShoulderController\n'''
assert old in text
text = text.replace(old, new, 1)

pattern = re.compile(r'''    private fun processImage\(image: Image\) \{.*?\n    private fun processRightFrame''', re.S)
replacement = '''    private fun processImage(image: Image) {\n        processRightFrame(image)\n        ShoulderCaptureService.dispatchSharedFrame(image, screenWidth, screenHeight)\n    }\n\n    private fun processRightFrame'''
text, n = pattern.subn(replacement, text, count=1)
assert n == 1

text = text.replace('        createAnalogBrakeSensor()\n', '', 1)

pattern = re.compile(r'''\n    private fun createAnalogBrakeSensor\(\) \{.*?\n    private fun createGroupFiveExtraSensors''', re.S)
text, n = pattern.subn('\n    private fun createGroupFiveExtraSensors', text, count=1)
assert n == 1

text = text.replace('''                (!::analogShoulder.isInitialized || !analogShoulder.isEditing) &&\n                !analogBrakeEditing\n''', '''                (!::analogShoulder.isInitialized || !analogShoulder.isEditing)\n''', 1)
text = text.replace('(::analogShoulder.isInitialized && analogShoulder.isEditing) || analogBrakeEditing', '(::analogShoulder.isInitialized && analogShoulder.isEditing)')

# Swap visible MANUAL labels only; backend bind calls stay untouched.
text = text.replace('MANUAL  •  SHOULDER R/L', 'MANUAL  •  SHOULDER L/R')
text = text.replace('حرّك دائرة R/L اليدوية', 'حرّك دائرة L/R اليدوية')
text = text.replace('موضع دائرة R/L اليدوية', 'موضع دائرة L/R اليدوية')
text = text.replace('''microCard(if (manualTapPair.bindingLabel == "R") "● ربط بـ R" else "○ ربط بـ R") {\n                    manualTapPair.bindToR()''', '''microCard(if (manualTapPair.bindingLabel == "R") "● ربط بـ L" else "○ ربط بـ L") {\n                    manualTapPair.bindToR()''')
text = text.replace('showMessage("تم ربط الدائرة اليدوية بـ R")', 'showMessage("تم ربط الدائرة اليدوية بـ L")')
text = text.replace('''microCard(if (manualTapPair.bindingLabel == "L") "● ربط بـ L" else "○ ربط بـ L") {\n                    manualTapPair.bindToL()''', '''microCard(if (manualTapPair.bindingLabel == "L") "● ربط بـ R" else "○ ربط بـ R") {\n                    manualTapPair.bindToL()''')
text = text.replace('showMessage("تم ربط الدائرة اليدوية بـ L")', 'showMessage("تم ربط الدائرة اليدوية بـ R")', 1)
# The previous replacement could hit the first message after it was changed; normalize the two blocks explicitly.
text = text.replace('''manualTapPair.bindToR()\n                    closeMenu()\n                    showMessage("تم ربط الدائرة اليدوية بـ R")''', '''manualTapPair.bindToR()\n                    closeMenu()\n                    showMessage("تم ربط الدائرة اليدوية بـ L")''')
text = text.replace('''manualTapPair.bindToL()\n                    closeMenu()\n                    showMessage("تم ربط الدائرة اليدوية بـ L")''', '''manualTapPair.bindToL()\n                    closeMenu()\n                    showMessage("تم ربط الدائرة اليدوية بـ R")''')

# Replace the entire V6 menu with two independent instant circles.
pattern = re.compile(r'''        if \(::analogShoulder\.isInitialized\) \{\n            content\.addView\(\n                sectionLabel\("V6  •  ANALOG SHOULDER.*?\n        \}\n\n        content\.addView\(sectionLabel\("SHOULDER  •  R / L"''', re.S)
new_block = '''        if (::analogShoulder.isInitialized) {\n            content.addView(\n                sectionLabel("V6  •  INSTANT SHOULDER  •  R + L", Color.rgb(38, 118, 150)),\n                matchWrap(),\n            )\n\n            val rActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }\n            rActions.addView(\n                microCard("✥ تعديل R") {\n                    closeMenu()\n                    analogShoulder.beginEditingR()\n                    updateButtonVisual()\n                    showMessage("حرّك قاعدة دائرة R ثم افتح القائمة واضغط حفظ")\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            rActions.addView(\n                microCard("✓ حفظ R") {\n                    analogShoulder.finishEditing()\n                    updateButtonVisual()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            rActions.addView(\n                microCard(if (analogShoulder.rEnabled) "R ON" else "R OFF") {\n                    analogShoulder.toggleREnabled()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            rActions.addView(\n                microCard(if (analogShoulder.rVisible) "◉ إخفاء R" else "○ إظهار R") {\n                    analogShoulder.toggleRVisible()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            content.addView(rActions, matchWrap(dp(42)))\n\n            val lActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }\n            lActions.addView(\n                microCard("✥ تعديل L") {\n                    closeMenu()\n                    analogShoulder.beginEditingL()\n                    updateButtonVisual()\n                    showMessage("حرّك دائرة L ثم افتح القائمة واضغط حفظ")\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            lActions.addView(\n                microCard("✓ حفظ L") {\n                    analogShoulder.finishEditing()\n                    updateButtonVisual()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            lActions.addView(\n                microCard(if (analogShoulder.lEnabled) "L ON" else "L OFF") {\n                    analogShoulder.toggleLEnabled()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            lActions.addView(\n                microCard(if (analogShoulder.lVisible) "◉ إخفاء L" else "○ إظهار L") {\n                    analogShoulder.toggleLVisible()\n                    closeMenu()\n                },\n                LinearLayout.LayoutParams(0, dp(40), 1f),\n            )\n            content.addView(lActions, matchWrap(dp(42)))\n\n            content.addView(\n                analogSizeRow(\n                    "BASE R",\n                    { analogShoulder.baseSizeLabel },\n                    { analogShoulder.decreaseBaseSize() },\n                    { analogShoulder.increaseBaseSize() },\n                ),\n                matchWrap(dp(44)),\n            )\n            content.addView(\n                analogSizeRow(\n                    "R",\n                    { analogShoulder.rCircleSizeLabel },\n                    { analogShoulder.decreaseRCircleSize() },\n                    { analogShoulder.increaseRCircleSize() },\n                ),\n                matchWrap(dp(44)),\n            )\n            content.addView(\n                analogSizeRow(\n                    "L",\n                    { analogShoulder.lCircleSizeLabel },\n                    { analogShoulder.decreaseLCircleSize() },\n                    { analogShoulder.increaseLCircleSize() },\n                ),\n                matchWrap(dp(44)),\n            )\n            content.addView(\n                TextView(this).apply {\n                    text = "فوري: لمس R أو L يبدأ الضغط مباشرة، ورفع الإصبع يوقفه مباشرة • لا WAIT ولا FAST ولا مكابح"\n                    textSize = 10.5f\n                    gravity = Gravity.CENTER\n                    setTextColor(Color.rgb(55, 75, 86))\n                    setPadding(dp(5), dp(3), dp(5), dp(6))\n                },\n                matchWrap(),\n            )\n        }\n\n        content.addView(sectionLabel("SHOULDER  •  L / R"'''
text, n = pattern.subn(new_block, text, count=1)
assert n == 1

# Swap visible labels in the legacy shoulder configuration card only; prefixes/actions stay untouched.
text = text.replace('card.addView(sideRow("R", "r"), matchWrap(dp(48)))', 'card.addView(sideRow("L", "r"), matchWrap(dp(48)))')
text = text.replace('card.addView(sideRow("L", "l"), matchWrap(dp(48)))', 'card.addView(sideRow("R", "l"), matchWrap(dp(48)))')
text = text.replace('menuButton("تعديل R") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_R) }', 'menuButton("تعديل L") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_R) }')
text = text.replace('menuButton("تعديل L") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_L) }', 'menuButton("تعديل R") { shoulderAction(ShoulderCaptureService.ACTION_EDIT_L) }')
text = text.replace('menuButton("↺ R للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_R) }', 'menuButton("↺ L للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_R) }')
text = text.replace('menuButton("↺ L للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_L) }', 'menuButton("↺ R للمنتصف") { shoulderAction(ShoulderCaptureService.ACTION_RESET_L) }')

# Remove obsolete sensitivity row function.
pattern = re.compile(r'''\n    private fun analogSensitivityRow\(\): View \{.*?\n    private fun sectionLabel''', re.S)
text, n = pattern.subn('\n    private fun sectionLabel', text, count=1)
assert n == 1

# Simplify status and swap displayed manual R/L only.
pattern = re.compile(r'''    private fun combinedStatusText\(\): String \{.*?\n    private fun engineStatusText''', re.S)
replacement = '''    private fun combinedStatusText(): String {\n        val manual = if (::manualTapPair.isInitialized) {\n            val displayBinding = if (manualTapPair.bindingLabel == "R") "L" else "R"\n            "MANUAL $displayBinding ${if (manualTapPair.isEnabled) "ON" else "OFF"}"\n        } else {\n            "MANUAL OFF"\n        }\n        val analog = if (::analogShoulder.isInitialized) {\n            "V6 R:${if (analogShoulder.rEnabled) "ON" else "OFF"} L:${if (analogShoulder.lEnabled) "ON" else "OFF"}"\n        } else "V6 --"\n        return "PixelProbe: ${engineStatusText()}  •  L/R: ${ShoulderCaptureService.statusSummary()}  •  $manual  •  $analog"\n    }\n\n    private fun engineStatusText'''
text, n = pattern.subn(replacement, text, count=1)
assert n == 1

text = text.replace('        applyAnalogBrakeVisibilityAndTouchability()\n', '')
text = text.replace('        restoreAnalogBrakePositionForCurrentProfile()\n', '')

pattern = re.compile(r'''\n    private fun loadAnalogBrakePosition\(.*?\n    private fun rightSensorPositionKey''', re.S)
text, n = pattern.subn('\n    private fun rightSensorPositionKey', text, count=1)
assert n == 1

text = text.replace('        analogBrakeView?.let { runCatching { windowManager.removeView(it) } }\n', '')
text = text.replace('        private const val ANALOG_BRAKE_POSITION_KEY = "v6.analog.brake"\n', '')

# No obsolete references may survive.
for obsolete in ['analogBrake', 'brakeEnabled', 'fastEnabled', 'decisionDelayLabel', 'dragSensitivityLabel', 'analogSensitivityRow', 'toggleAnalogBrake', 'beginAnalogBrakeEditing']:
    assert obsolete not in text, obsolete

p.write_text(text)

# ---- Manual circle: swap only the painted letter; internal binding/color/behavior remain unchanged.
p = ROOT / 'app/src/main/java/com/pixeltrigger/app/ManualNubiaPairController.kt'
text = p.read_text()
old = '            canvas.drawText(binding, cx, baseline, textPaint)\n'
new = '''            val displayBinding = if (binding == Binding.R.name) "L" else "R"\n            canvas.drawText(displayBinding, cx, baseline, textPaint)\n'''
assert old in text
text = text.replace(old, new, 1)
p.write_text(text)

# ---- Shoulder status strings: visual swap only; internal Side and keys are untouched.
p = ROOT / 'app/src/main/java/com/pixeltrigger/app/ShoulderCaptureService.kt'
text = p.read_text()
old = '''        val manual = manualReservedSide?.name?.let { " • Manual $it" } ?: ""\n        val finger = fingerReservedSide?.name?.let { " • V6 hold $it" } ?: ""\n        return "R $r  •  L $l  •  $state$manual$finger"\n'''
new = '''        fun display(side: Side): String = if (side == Side.R) "L" else "R"\n        val manual = manualReservedSide?.let { " • Manual ${display(it)}" } ?: ""\n        val finger = fingerReservedSide?.let { " • V6 hold ${display(it)}" } ?: ""\n        return "L $r  •  R $l  •  $state$manual$finger"\n'''
assert old in text
text = text.replace(old, new, 1)
text = text.replace('fun statusSummary(): String = activeInstance?.summary() ?: "R/L starting…"', 'fun statusSummary(): String = activeInstance?.summary() ?: "L/R starting…"')
p.write_text(text)

# ---- Version.
p = ROOT / 'app/build.gradle.kts'
text = p.read_text()
assert 'versionCode = 65' in text
assert 'versionName = "6.3-final-drag-sensitivity"' in text
text = text.replace('versionCode = 65', 'versionCode = 66', 1)
text = text.replace('versionName = "6.3-final-drag-sensitivity"', 'versionName = "6.4-split-instant-shoulders"', 1)
p.write_text(text)
