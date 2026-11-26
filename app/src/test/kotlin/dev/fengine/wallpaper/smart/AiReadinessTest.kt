package dev.fengine.wallpaper.smart

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun failuresReadAsClassAndMessage() {
        assertEquals(
            "IllegalStateException: AICore not bound",
            AiReadiness.describe(IllegalStateException(" AICore not bound ")),
        )
        assertEquals("IllegalStateException", AiReadiness.describe(IllegalStateException()))
        assertEquals("IllegalStateException", AiReadiness.describe(IllegalStateException("  ")))
    }

    @Test
    fun retryOnNpeRecoversFromTransientInitFailure() = runTest {
        var calls = 0
        val out =
            Outside.retryOnNpe(listOf(10L, 20L)) {
                calls++
                if (calls < 3) throw NullPointerException()
                42
            }
        assertEquals(42, out)
        assertEquals(3, calls)
    }

    @Test
    fun retryOnNpeDoesNotRetryOtherExceptions() = runTest {
        var calls = 0
        val err = runCatching {
            Outside.retryOnNpe(listOf(10L, 20L)) {
                calls++
                throw IllegalStateException("boom")
            }
        }
            .exceptionOrNull()
        assertTrue(err is IllegalStateException)
        assertEquals(1, calls)
    }
}
