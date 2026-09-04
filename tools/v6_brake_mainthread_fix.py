from pathlib import Path

p = Path('app/src/main/java/com/pixeltrigger/app/ScreenCaptureService.kt')
s = p.read_text()
old = '''        analogBrakeOpen = open
        analogShoulder.setBrakeOpen(open)
        mainHandler.post {
            analogBrakeView?.setStatus(if (open) SensorStatus.FIRED else SensorStatus.ARMED)
            menuStatusText?.text = combinedStatusText()
        }
'''
new = '''        analogBrakeOpen = open
        // Touch intent, FAST promotion, and brake-driven DOWN/UP are serialized
        // on the main thread so a capture callback can never race a finger MOVE/UP.
        mainHandler.post {
            if (::analogShoulder.isInitialized && analogShoulder.brakeEnabled && analogBrakeOpen == open) {
                analogShoulder.setBrakeOpen(open)
                analogBrakeView?.setStatus(if (open) SensorStatus.FIRED else SensorStatus.ARMED)
                menuStatusText?.text = combinedStatusText()
            }
        }
'''
if s.count(old) != 1:
    raise SystemExit(f'expected one gate block, got {s.count(old)}')
s = s.replace(old, new, 1)
p.write_text(s)
