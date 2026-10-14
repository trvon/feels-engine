package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.smart.ScanPlan
import kotlinx.coroutines.runBlocking
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * A scan pass must only ever return candidates, never a photo the user removed, never a duplicate,
 * never more judge calls than the budget, and must hand back exactly what the budget left over.
 */
class ScanPlanProperties {

    private val keys: Arbitrary<String> = Arbitraries.of((0 until 40).map { "p$it" })
    private val labelNames: Arbitrary<String> = Arbitraries.of("Dog", "Beach", "Sky", "Child", "Food", "Car", "Pattern", "Flag")

    data class World(
        val pool: List<String>,
        val library: List<String>,
        val labels: Map<String, Map<String, Float>>,
        val relevant: Set<String>,
        val matched: List<String>,
        val excluded: List<String>,
        val verdicts: Map<String, Boolean>,
        val budget: Int,
        val canJudge: Boolean,
    )

    @Provide
    fun worlds(): Arbitrary<World> {
        val labelMap: Arbitrary<Map<String, Float>> =
            Arbitraries.maps(labelNames, Arbitraries.floats().between(0f, 1f)).ofMaxSize(3)
        return Combinators.combine(
            keys.list().ofMaxSize(8), keys.list().ofMaxSize(20),
            Arbitraries.maps(keys, labelMap).ofMaxSize(30), labelNames.set().ofMaxSize(4),
            keys.list().ofMaxSize(6), keys.list().ofMaxSize(6),
            Arbitraries.maps(keys, Arbitraries.of(true, false)).ofMaxSize(20),
            Arbitraries.integers().between(0, 12),
        ).`as` { pool: List<String>, lib: List<String>, labels: Map<String, Map<String, Float>>, rel: Set<String>, matched: List<String>, excl: List<String>, verdicts: Map<String, Boolean>, budget: Int ->
            World(pool, lib, labels, rel, matched, excl, verdicts, budget, canJudge = budget % 2 == 0)
        }
    }

    @Property(tries = 500)
    fun candidatesKeepPoolFirstWithoutDuplicates(@ForAll @Size(max = 10) pool: List<String>, @ForAll @Size(max = 20) lib: List<String>) {
        val out = ScanPlan.candidates(pool, lib)
        assertEquals(out.size, out.toSet().size)
        assertEquals((pool + lib).toSet(), out.toSet())
        // Every pool photo precedes every library-only photo.
        val lastPool = out.indexOfLast { it in pool }
        val firstLibOnly = out.indexOfFirst { it !in pool }
        assertTrue(firstLibOnly == -1 || lastPool < firstLibOnly)
    }

    @Property(tries = 500)
    fun orderedIsLabeledOnlyRelevanceDescendingAndStable(@ForAll("worlds") w: World) {
        val cands = ScanPlan.candidates(w.pool, w.library)
        val out = ScanPlan.ordered(cands, w.labels::get, w.relevant)
        assertTrue(out.all { w.labels[it] != null })
        assertEquals(cands.filter { w.labels[it] != null }, out.sortedBy { cands.indexOf(it) })
        fun score(k: String) = w.labels[k]!!.keys.count { it in w.relevant }
        out.zipWithNext().forEach { (a, b) ->
            assertTrue(score(a) >= score(b))
            if (score(a) == score(b)) assertTrue(cands.indexOf(a) < cands.indexOf(b))
        }
    }

    @Property(tries = 1000)
    fun passRespectsSeenBudgetAndCandidates(@ForAll("worlds") w: World) = runBlocking {
        val cands = ScanPlan.candidates(w.pool, w.library)
        val ordered = ScanPlan.ordered(cands, w.labels::get, w.relevant)
        val settled = ScanPlan.settled(w.matched, if (w.canJudge) w.verdicts::get else null)
        val seen = (settled + w.excluded).toSet()
        var calls = 0
        val pass = ScanPlan.pass(ordered, seen, w.labels::get, w.relevant, w.canJudge, w.verdicts::get, w.budget) { key ->
            calls++
            assertTrue("judged a cached photo", w.verdicts[key] == null)
            when (key.hashCode() % 3) { 0 -> true; 1 -> false; else -> null }
        }
        assertEquals(calls, pass.judged)
        assertTrue(pass.judged <= w.budget)
        assertEquals(pass.found.size, pass.found.toSet().size)
        assertTrue(pass.found.all { it in ordered })
        assertTrue(pass.found.none { it in seen })
        assertTrue(pass.found.none { it in w.excluded })
        if (!w.canJudge) {
            assertEquals(0, pass.judged)
            assertEquals(0, pass.pending)
            assertTrue(pass.found.all { k -> w.labels[k]!!.keys.any { it in w.relevant } })
            // Labels-only: everything unseen with a relevant label is found, nothing is left pending.
            assertEquals(ordered.filter { it !in seen && w.labels[it]!!.keys.any { l -> l in w.relevant } }, pass.found)
        } else {
            val unseen = ordered.filter { it !in seen }
            val needJudge = unseen.filter { w.verdicts[it] == null }
            assertEquals(maxOf(0, needJudge.size - w.budget), pass.pending)
            assertEquals(minOf(needJudge.size, w.budget), pass.judged)
            // Cached verdicts are applied verbatim.
            unseen.filter { w.verdicts[it] != null }.forEach { assertEquals(w.verdicts[it]!!, it in pass.found) }
            // A judge that could not look (null) hands the decision to the labels for that photo.
            needJudge.take(w.budget).filter { it.hashCode() % 3 == 2 }.forEach { k ->
                assertEquals(w.labels[k]!!.keys.any { it in w.relevant }, k in pass.found)
            }
        }
    }

    @Property(tries = 300)
    fun passSkipsPhotosSeenUnderAnotherIdentity(@ForAll @Size(max = 10) picked: List<String>, @ForAll @Size(max = 20) lib: List<String>) = runBlocking {
        // Identity: strip a "picker:" prefix so "picker:p3" and "p3" are the same photo.
        val identity = { k: String -> k.removePrefix("picker:") }
        val seen = picked.mapTo(HashSet(), identity)
        val labels = { _: String -> mapOf("Dog" to 1f) }
        val pass = ScanPlan.pass(lib, seen, labels, setOf("Dog"), canJudge = false, cached = { null }, budget = 0, identity = identity) { null }
        assertTrue(pass.found.none { identity(it) in seen })
        assertEquals(lib.filter { identity(it) !in seen }, pass.found)
    }

    @Property(tries = 300)
    fun settledNeverInventsAndKeepsAllWithoutModel(@ForAll @Size(max = 10) matched: List<String>, @ForAll verdicts: Map<String, Boolean>) {
        assertEquals(matched, ScanPlan.settled(matched, null))
        val withModel = ScanPlan.settled(matched, verdicts::get)
        assertTrue(withModel.all { it in matched && verdicts[it] == true })
        assertEquals(matched.filter { verdicts[it] == true }, withModel)
    }

    @Property(tries = 200)
    fun noteAlwaysNamesCountsAndPendingWinsOverCloudHint(
        @ForAll @IntRange(min = 0, max = 500) total: Int, @ForAll @IntRange(min = 0, max = 500) lib: Int,
        @ForAll @IntRange(min = 0, max = 100) pool: Int, @ForAll pending: Boolean,
    ) {
        val n = ScanPlan.note("labels only", "family", total, lib, pool, pending)
        assertTrue(n.startsWith(if (total == 1) "Found 1 photo with labels only." else "Found $total photos with labels only."))
        assertTrue(n.contains("Looked through $lib on this phone"))
        assertEquals(pool > 0, n.contains("$pool you picked"))
        assertEquals(pending, n.contains("next pass"))
        assertEquals(!pending && lib < 20 && pool == 0, n.contains("Google Photos"))
        // No vocabulary for the rule: say it is the words, not the photos. Only when nothing was found.
        val none = ScanPlan.note("labels only", "family", total, lib, pool, pending, noVocabulary = true)
        assertEquals(total == 0, none.contains("relates to \"family\""))
        assertTrue(none.contains("Looked through $lib on this phone"))
    }
}
