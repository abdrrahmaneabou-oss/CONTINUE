package com.pixeltrigger.app.input

data class TapRequest(
    val triggerId: Long,
    val x: Float,
    val y: Float,
    val requestedDurationMs: Long = 1L,
    val requestedAtNs: Long,
    val displayId: Int = 0,
)
