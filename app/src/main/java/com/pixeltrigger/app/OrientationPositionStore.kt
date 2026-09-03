package com.pixeltrigger.app

import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * Orientation-aware overlay position storage.
 *
 * Portrait and landscape are separate profiles. A display rotation may
 * project the opposite profile for temporary use, but it never writes that
 * projection back. Returning to the original orientation therefore restores
 * the exact saved coordinates. Saved display/overlay dimensions allow a
 * proportional center-based projection only when dimensions genuinely differ.
 */
internal class OrientationPositionStore(
    private val preferences: SharedPreferences,
) {
    data class Position(val x: Int, val y: Int)

    private data class SavedPosition(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val overlayWidth: Int,
        val overlayHeight: Int,
    )

    fun isPortrait(screenWidth: Int, screenHeight: Int): Boolean =
        screenHeight >= screenWidth

    fun hasSaved(keyPrefix: String, screenWidth: Int, screenHeight: Int): Boolean {
        val profile = profileName(screenWidth, screenHeight)
        return preferences.contains(key(keyPrefix, profile, "x")) &&
            preferences.contains(key(keyPrefix, profile, "y"))
    }

    fun load(
        keyPrefix: String,
        screenWidth: Int,
        screenHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        fallbackX: Int,
        fallbackY: Int,
        legacyXKey: String? = null,
        legacyYKey: String? = null,
    ): Position {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val profile = profileName(width, height)

        read(keyPrefix, profile)?.let {
            return project(it, width, height, overlayWidth, overlayHeight)
        }

        // Existing PixelTrigger coordinates were created primarily in
        // portrait. Migrate them once into the portrait profile only.
        if (
            profile == PORTRAIT &&
            legacyXKey != null && legacyYKey != null &&
            preferences.contains(legacyXKey) && preferences.contains(legacyYKey)
        ) {
            val migrated = Position(
                preferences.getInt(legacyXKey, fallbackX),
                preferences.getInt(legacyYKey, fallbackY),
            )
            save(
                keyPrefix = keyPrefix,
                x = migrated.x,
                y = migrated.y,
                screenWidth = width,
                screenHeight = height,
                overlayWidth = overlayWidth,
                overlayHeight = overlayHeight,
                legacyXKey = legacyXKey,
                legacyYKey = legacyYKey,
            )
            return migrated
        }

        // No profile exists for this orientation yet. Derive a temporary
        // position from the opposite profile, but do NOT persist it. This is
        // what prevents landscape from corrupting a carefully tuned portrait.
        val opposite = if (profile == PORTRAIT) LANDSCAPE else PORTRAIT
        read(keyPrefix, opposite)?.let {
            return project(it, width, height, overlayWidth, overlayHeight)
        }

        return Position(fallbackX, fallbackY)
    }

    fun save(
        keyPrefix: String,
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
        legacyXKey: String? = null,
        legacyYKey: String? = null,
    ) {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val profile = profileName(width, height)
        val editor = preferences.edit()
            .putInt(key(keyPrefix, profile, "x"), x)
            .putInt(key(keyPrefix, profile, "y"), y)
            .putInt(key(keyPrefix, profile, "screen_w"), width)
            .putInt(key(keyPrefix, profile, "screen_h"), height)
            .putInt(key(keyPrefix, profile, "overlay_w"), overlayWidth.coerceAtLeast(1))
            .putInt(key(keyPrefix, profile, "overlay_h"), overlayHeight.coerceAtLeast(1))

        // Keep legacy keys synchronized with the portrait profile only.
        // Landscape must never overwrite the historic portrait coordinates.
        if (profile == PORTRAIT && legacyXKey != null && legacyYKey != null) {
            editor.putInt(legacyXKey, x).putInt(legacyYKey, y)
        }
        editor.apply()
    }

    private fun read(keyPrefix: String, profile: String): SavedPosition? {
        val xKey = key(keyPrefix, profile, "x")
        val yKey = key(keyPrefix, profile, "y")
        if (!preferences.contains(xKey) || !preferences.contains(yKey)) return null
        return SavedPosition(
            x = preferences.getInt(xKey, 0),
            y = preferences.getInt(yKey, 0),
            screenWidth = preferences.getInt(key(keyPrefix, profile, "screen_w"), 1).coerceAtLeast(1),
            screenHeight = preferences.getInt(key(keyPrefix, profile, "screen_h"), 1).coerceAtLeast(1),
            overlayWidth = preferences.getInt(key(keyPrefix, profile, "overlay_w"), 1).coerceAtLeast(1),
            overlayHeight = preferences.getInt(key(keyPrefix, profile, "overlay_h"), 1).coerceAtLeast(1),
        )
    }

    private fun project(
        saved: SavedPosition,
        screenWidth: Int,
        screenHeight: Int,
        overlayWidth: Int,
        overlayHeight: Int,
    ): Position {
        if (
            saved.screenWidth == screenWidth &&
            saved.screenHeight == screenHeight &&
            saved.overlayWidth == overlayWidth &&
            saved.overlayHeight == overlayHeight
        ) {
            return Position(saved.x, saved.y)
        }

        val centerXRatio =
            (saved.x + saved.overlayWidth / 2f) / saved.screenWidth.toFloat()
        val centerYRatio =
            (saved.y + saved.overlayHeight / 2f) / saved.screenHeight.toFloat()
        return Position(
            x = (centerXRatio * screenWidth - overlayWidth / 2f).roundToInt(),
            y = (centerYRatio * screenHeight - overlayHeight / 2f).roundToInt(),
        )
    }

    private fun profileName(screenWidth: Int, screenHeight: Int): String =
        if (isPortrait(screenWidth, screenHeight)) PORTRAIT else LANDSCAPE

    private fun key(prefix: String, profile: String, field: String): String =
        "position_v2.$prefix.$profile.$field"

    private companion object {
        const val PORTRAIT = "portrait"
        const val LANDSCAPE = "landscape"
    }
}
