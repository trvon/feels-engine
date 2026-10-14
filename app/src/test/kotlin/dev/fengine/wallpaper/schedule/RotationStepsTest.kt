package dev.fengine.wallpaper.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class RotationStepsTest {
    private val empty = setOf("empty")

    @Test
    fun sameGroupOnBothScreensIsOneSharedCall() {
        assertEquals(
            listOf(RotationPlan.Step("a", 3)),
            RotationPlan.steps("a", "a") { it in empty },
        )
    }

    @Test
    fun differentGroupsAreOneCallPerScreen() {
        assertEquals(
            listOf(RotationPlan.Step("a", 1), RotationPlan.Step("b", 2)),
            RotationPlan.steps("a", "b") { it in empty },
        )
    }

    @Test
    fun emptyOrUnassignedScreensAreLeftAlone() {
        assertEquals(listOf(RotationPlan.Step("a", 1)), RotationPlan.steps("a", "empty") { it in empty })
        assertEquals(listOf(RotationPlan.Step("b", 2)), RotationPlan.steps(null, "b") { it in empty })
        assertEquals(emptyList<RotationPlan.Step>(), RotationPlan.steps("empty", null) { it in empty })
    }
}
