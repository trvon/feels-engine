package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.smart.Similar
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The near-duplicate filter: one photo per moment, nothing unread dropped, order kept. */
class SimilarProperties {
    @Provide
    fun photos(): Arbitrary<List<Pair<String, Similar.Look?>>> {
        // Few base hashes with a few flipped bits each, so look-alikes are common.
        val look =
            Combinators.combine(
                    Arbitraries.of(0L, -1L, 0x0F0F0F0F0F0F0F0FL, 0x123456789ABCDEFL),
                    Arbitraries.integers().between(0, 63).list().ofMaxSize(30),
                    Arbitraries.longs().between(0, 600_000).injectNull(0.3),
                )
                .`as` { base, flips, t ->
                    Similar.Look(flips.fold(base) { h, b -> h xor (1L shl b) }, t)
                }
                .injectNull(0.15)
        return look.list().ofMaxSize(25).map { ls -> ls.mapIndexed { i, l -> "p$i" to l } }
    }

    @Property(tries = 2000)
    fun keptPhotosAreDistinctOrderedAndNothingUnreadIsDropped(
        @ForAll("photos") photos: List<Pair<String, Similar.Look?>>,
        @ForAll("photos") group: List<Pair<String, Similar.Look?>>,
    ) {
        val looks = (photos + group.map { ("g" + it.first) to it.second }).toMap()
        val found = photos.map { it.first }
        val kept = group.map { "g" + it.first }
        val out = Similar.distinct(found, kept, looks::get)
        assertEquals("order kept", found.filter { it in out }, out)
        assertTrue("unread kept", found.filter { looks[it] == null }.all { it in out })
        val outLooks = out.mapNotNull { looks[it] }
        for (i in outLooks.indices) for (j in i + 1 until outLooks.size) assertFalse(
            "two alike kept",
            Similar.alike(outLooks[i], outLooks[j]),
        )
        val groupLooks = kept.mapNotNull { looks[it] }
        assertTrue(
            "alike to the group",
            outLooks.none { o -> groupLooks.any { Similar.alike(it, o) } },
        )
        // Every dropped photo is alike to something kept or in the group.
        for (d in found - out.toSet()) {
            val l = looks[d]!!
            assertTrue((outLooks + groupLooks).any { Similar.alike(it, l) })
        }
    }

    @Test
    fun aBurstBecomesOnePhotoButDistantMomentsStay() {
        val t = 1_700_000_000_000L
        val burst =
            (0 until 7).map {
                Similar.Look(0x0F0F0F0F0F0F0F0FL xor (1L shl it * 3), t + it * 2_000)
            }
        val other = Similar.Look(0x0F0F0F0F0F0F0F0FL xor 0xFFFFL, t + 86_400_000)
        val looks = burst.mapIndexed { i, l -> "b$i" to l }.toMap() + ("o" to other)
        assertEquals(
            listOf("b0", "o"),
            Similar.distinct(looks.keys.toList(), emptyList(), looks::get),
        )
    }

    @Test
    fun dHashSeesGradientDirection() {
        val left = IntArray(72) { 255 - (it % 9) * 20 }
        val right = IntArray(72) { (it % 9) * 20 }
        assertEquals(-1L, Similar.dHash(left))
        assertEquals(0L, Similar.dHash(right))
        assertEquals(64, Similar.distance(Similar.dHash(left), Similar.dHash(right)))
    }
}
