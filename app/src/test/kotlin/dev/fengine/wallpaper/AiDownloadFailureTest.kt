package dev.fengine.wallpaper

import dev.fengine.wallpaper.smart.AiReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDownloadFailureTest {
    @Test
    fun namesTheCodeAndTheFix() {
        val s = AiReadiness.downloadFailed(501, "x", null)
        assertTrue(s, s.contains("code 501") && s.contains("free space"))
    }

    @Test
    fun readsAiCoreTypesFromTheMessage() {
        assertTrue(
            AiReadiness.downloadFailed(0, "AICore failed with 601-BINDING_FAILURE", null)
                .contains("Update AICore")
        )
        assertTrue(
            AiReadiness.downloadFailed(null, "606-FEATURE_NOT_FOUND", null).contains("setting up")
        )
    }

    @Test
    fun saysWhenToRetry() {
        assertTrue(AiReadiness.downloadFailed(9, "busy", 300).endsWith("Try again in 5 minutes."))
    }

    @Test
    fun unknownErrorsKeepTheirMessage() {
        assertEquals(
            "Download failed: NoSuchMethodError",
            AiReadiness.downloadFailed(null, "NoSuchMethodError", null),
        )
    }
}
