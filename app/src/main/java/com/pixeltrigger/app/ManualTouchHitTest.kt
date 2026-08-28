package com.pixeltrigger.app

import android.view.View

/** Exact circular hit-test for the manual 13 mm trigger window. */
internal fun View.containsVisibleCircle(x: Float, y: Float): Boolean {
    val cx = width / 2f
    val cy = height / 2f
    val radius = (minOf(width, height) / 2f).coerceAtLeast(1f)
    val dx = x - cx
    val dy = y - cy
    return dx * dx + dy * dy <= radius * radius
}
