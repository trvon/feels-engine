package dev.fengine.wallpaper.smart

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReadinessTest {
    @Test
    fun factoryStubBuildsAreRecognised() {
        assertTrue(AiReadiness.isStub("0.stub.oem.aicore_stub_for_android_B_RC02.728786427"))
        assertTrue(AiReadiness.isStub(null))
        assertFalse(AiReadiness.isStub("0.release.qc.prod_aicore_20260917.00_RC01"))
    }
}
