package me.timschneeberger.rootlessjamesdsp.interop

import kotlin.math.floor

object FieldSurroundDepthMapping {
    const val CONFIG_DEFAULT_WIDENING = 100
    const val CONFIG_DEFAULT_DEPTH = 0
    const val CONFIG_DEFAULT_MID_IMAGE = 100
    const val DEPTH_BRANCH_THRESHOLD = 500

    /**
     * Direct ViPER command-ID semantics (65556): raw short, no remap/clamp to 200..800.
     */
    fun toDirectDepthStrength(rawDepth: Int): Int {
        return rawDepth.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }

    /**
     * Legacy GStreamer-wrapper compatibility semantics (colm-depth raw 0..32767).
     * depth_internal = clamp((raw/32767)*600 + 200, 200, 800)
     */
    fun toWrapperCompatDepthStrength(rawDepth: Int): Int {
        val clampedRaw = rawDepth.coerceIn(0, 32767)
        val mapped = floor((clampedRaw.toDouble() / 32767.0) * 600.0 + 200.0).toInt()
        return mapped.coerceIn(200, 800)
    }

    fun isDepthStageEnabled(strength: Int): Boolean {
        return strength != 0
    }

    fun isDepthAtOrAboveBranchThreshold(strength: Int): Boolean {
        return strength >= DEPTH_BRANCH_THRESHOLD
    }
}
