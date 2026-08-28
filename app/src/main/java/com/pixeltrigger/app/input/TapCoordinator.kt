package com.pixeltrigger.app.input

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** Converts each detector FIRE directly into one tap request. Rebuilt after protection-gate cleanup. */
class TapCoordinator(
    private val engine: TapEngine,
) {
    private val ids = AtomicLong(0L)

    fun fire(x: Float, y: Float, displayId: Int = 0): TapResult {
        val request = TapRequest(
            triggerId = ids.incrementAndGet(),
            x = x,
            y = y,
            requestedDurationMs = 1L,
            requestedAtNs = SystemClock.elapsedRealtimeNanos(),
            displayId = displayId,
        )
        return when (val result = engine.tap(request)) {
            is TapResult.Rejected -> TapResult.Failed(
                triggerId = result.triggerId,
                acceptedAtNs = result.acceptedAtNs,
                reason = result.reason,
            )
            else -> result
        }
    }
}
