package com.example.taskervideoplugin.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolutionSelectorTest {
    @Test
    fun parsesRegularResolution() {
        assertEquals(1920 to 1080, ResolutionSelector.parseDimensions("1920x1080"))
    }

    @Test
    fun keepsResolutionWhenTaskerTextWasAccidentallyAppended() {
        assertEquals(4000 to 2250, ResolutionSelector.parseDimensions("4000x2250%videoname"))
    }

    @Test
    fun rejectsValuesWithoutLeadingDimensions() {
        assertNull(ResolutionSelector.parseDimensions("%resolution"))
        assertNull(ResolutionSelector.parseDimensions("manual"))
    }
}
