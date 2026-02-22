package me.timschneeberger.rootlessjamesdsp.interop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSurroundDepthMappingTest {
    @Test
    fun directPathDepthZeroDisablesStage() {
        val strength = FieldSurroundDepthMapping.toDirectDepthStrength(0)
        assertFalse(FieldSurroundDepthMapping.isDepthStageEnabled(strength))
    }

    @Test
    fun directPathDepth500TakesBranchThreshold() {
        val strength = FieldSurroundDepthMapping.toDirectDepthStrength(500)
        assertTrue(FieldSurroundDepthMapping.isDepthAtOrAboveBranchThreshold(strength))
    }

    @Test
    fun wrapperPathDepth32767MapsTo800() {
        val strength = FieldSurroundDepthMapping.toWrapperCompatDepthStrength(32767)
        assertEquals(800, strength)
    }

    @Test
    fun startupConfigHonorsDepthZeroDefault() {
        assertEquals(0, FieldSurroundDepthMapping.CONFIG_DEFAULT_DEPTH)
    }
}
