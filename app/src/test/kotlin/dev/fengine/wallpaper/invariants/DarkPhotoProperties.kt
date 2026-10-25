package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Brightness
import dev.fengine.wallpaper.schedule.RotationPlan
import kotlin.random.Random
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class DarkPhotoProperties {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    /** Black is dark, white is not, and darkening every pixel never makes a photo brighter. */
    @Property(tries = 500)
    fun lightnessIsMonotonic(
        @ForAll("pixels") px: IntArray,
        @ForAll @IntRange(min = 1, max = 255) d: Int,
    ) {
        assertTrue(Brightness.isDark(IntArray(16) { grey(0) }))
        assertFalse(Brightness.isDark(IntArray(16) { grey(255) }))
        assertFalse(Brightness.isDark(IntArray(0)))
        val darker =
            px.map { p ->
                    val r = ((p shr 16) and 0xFF) - d
                    val g = ((p shr 8) and 0xFF) - d
                    val b = (p and 0xFF) - d
                    (0xFF shl 24) or
                        (r.coerceAtLeast(0) shl 16) or
                        (g.coerceAtLeast(0) shl 8) or
                        b.coerceAtLeast(0)
                }
                .toIntArray()
        val m = Brightness.meanLightness(px)
        assertTrue(Brightness.meanLightness(darker) <= m + 1e-9)
        assertTrue(m in 0.0..100.0 + 1e-9)
    }

    /**
     * The pick is a preferred photo whenever the group has one; asks about each photo at most once
     * and stops at the first yes; with none preferred it equals the plain next photo.
     */
    @Property(tries = 1000)
    fun preferredPhotoWinsWhenThereIsOne(
        @ForAll("flags") dark: List<Boolean>,
        @ForAll @IntRange(min = -1, max = 40) cursor: Int,
        @ForAll shuffle: Boolean,
        @ForAll seed: Long,
    ) {
        val pool = dark.indices.map { "p$it" }
        val asked = mutableListOf<String>()
        val pick =
            RotationPlan.nextPreferred(pool, cursor, shuffle, Random(seed)) {
                asked += it
                dark[it.drop(1).toInt()]
            }
        assertEquals(asked.size, asked.toSet().size)
        if (dark.any { it }) {
            assertTrue(dark[pick])
            assertEquals(pool[pick], asked.last())
            if (!shuffle) {
                // Group order: the first dark photo after the cursor, the current one last.
                val n = pool.size
                val order =
                    (1..n)
                        .map { (cursor + it).mod(n) }
                        .let { o ->
                            val cur = cursor.takeIf { it in 0 until n }
                            o.filter { it != cur } + listOfNotNull(cur)
                        }
                assertEquals(order.first { dark[it] }, pick)
            }
        } else {
            assertEquals(pool.size, asked.size)
            if (!shuffle) assertEquals(RotationPlan.nextIndex(pool.size, cursor, false), pick)
            assertTrue(pick in pool.indices)
            if (pool.size > 1 && cursor in pool.indices) assertTrue(pick != cursor)
        }
    }

    @Provide
    fun pixels(): Arbitrary<IntArray> =
        Arbitraries.integers()
            .between(0, 0xFFFFFF)
            .map { it or (0xFF shl 24) }
            .array(IntArray::class.java)
            .ofMinSize(1)
            .ofMaxSize(64)

    @Provide
    fun flags(): Arbitrary<List<Boolean>> =
        Arbitraries.of(true, false).list().ofMinSize(1).ofMaxSize(20)
}
