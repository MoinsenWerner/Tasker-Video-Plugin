package com.example.taskervideoplugin.tasker

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPrefixTest {
    @Test
    fun everyActionUsesItsDedicatedPrefix() {
        assertEquals("vasrt_", StartVideoRunner().outputPrefix)
        assertEquals("vap_", PauseVideoRunner().outputPrefix)
        assertEquals("vaf_", ResumeVideoRunner().outputPrefix)
        assertEquals("vastp_", StopVideoRunner().outputPrefix)
        assertEquals("fa_", TakePhotoRunner().outputPrefix)
        assertEquals("vtf_", VideoToFramesRunner().outputPrefix)
        assertEquals("vab_", AudioBlockRunner().outputPrefix)
    }
}
