package com.pixeltrigger.app.input

sealed interface TapResult {
    val triggerId: Long
    val acceptedAtNs: Long

    data class Completed(
        override val triggerId: Long,
        override val acceptedAtNs: Long,
        val downSentAtNs: Long,
        val upSentAtNs: Long,
    ) : TapResult

    /**
     * A real delivery failure. This is diagnostic only: callers must not turn it
     * into a synthetic "protection" cancellation, detector reset, or input lockout.
     */
    data class Failed(
        override val triggerId: Long,
        override val acceptedAtNs: Long,
        val reason: String,
    ) : TapResult

    /** Legacy result kept for source compatibility; the active tap path no longer emits it. */
    data class Rejected(
        override val triggerId: Long,
        override val acceptedAtNs: Long,
        val reason: String,
    ) : TapResult
}
