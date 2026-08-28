package com.pixeltrigger.app.input

interface TapEngine {
    val name: String
    fun tap(request: TapRequest): TapResult
}
