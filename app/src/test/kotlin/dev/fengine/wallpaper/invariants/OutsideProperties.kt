package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.smart.Outside
import dev.fengine.wallpaper.smart.Outside.Download
import dev.fengine.wallpaper.smart.Outside.DownloadView
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Calls into other apps' services (AICore, ML Kit) may answer late, fail, or never answer. In
 * virtual time, for any such behaviour: a bounded call returns by its deadline and never swallows
 * the caller's cancellation, and a download watch tells "stalled" from "downloading" and always
 * reports the end the flow actually reached. The Fold 8's AICore that never started a download is
 * the "hangs" case.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutsideProperties {

    enum class Answer {
        RETURNS,
        THROWS,
        HANGS,
    }

    @Property(tries = 500)
    fun aBoundedCallAnswersByItsDeadline(
        @ForAll answer: Answer,
        @ForAll @LongRange(min = 0, max = 200_000) after: Long,
        @ForAll @LongRange(min = 1, max = 120_000) deadline: Long,
    ) = runTest {
        Assume.that(after != deadline)
        val started = currentTime
        val result =
            Outside.within(deadline, "probe") {
                // if, not when: a when over an enum adds a synthetic class jqwik cannot name.
                if (answer == Answer.HANGS) awaitCancellation()
                delay(after)
                if (answer == Answer.THROWS) throw IllegalStateException("boom")
                42
            }
        val took = currentTime - started
        val inTime = answer != Answer.HANGS && after < deadline
        assertEquals(if (inTime) after else deadline, took)
        assertEquals(inTime && answer == Answer.RETURNS, result.isSuccess)
        if (!inTime) assertTrue(result.exceptionOrNull() is Outside.TimedOut)
        if (inTime && answer == Answer.THROWS)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Property(tries = 300)
    fun theCallersCancellationIsNeverSwallowed(
        @ForAll @LongRange(min = 1, max = 50_000) cancelAt: Long,
        @ForAll @LongRange(min = 1, max = 120_000) deadline: Long,
    ) = runTest {
        Assume.that(cancelAt < deadline)
        var after = false
        val job = launch {
            Outside.within(deadline, "probe") { awaitCancellation() }
            after = true // a swallowed cancellation would carry on to here
        }
        advanceTimeBy(cancelAt)
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertFalse("work after a cancelled call ran", after)
    }

    /** One step of a download: wait [gap] ms, then [event]. */
    data class Step(val gap: Long, val event: Download)

    enum class Tail {
        ENDS,
        HANGS,
        THROWS,
    }

    @Provide
    fun steps(): Arbitrary<List<Step>> {
        val event: Arbitrary<Download> =
            Arbitraries.oneOf(
                Arbitraries.longs().between(0, 900_000_000).map { Download.Started(it) },
                Arbitraries.longs().between(0, 900_000_000).map { Download.Progress(it) },
                Arbitraries.just(Download.Completed),
                Arbitraries.just(Download.Failed(IllegalStateException("aicore"))),
            )
        return Combinators.combine(Arbitraries.longs().between(0, 60_000), event)
            .`as`(::Step)
            .list()
            .ofMaxSize(8)
    }

    @Property(tries = 1000)
    fun aDownloadWatchReportsWhatHappened(
        @ForAll("steps") steps: List<Step>,
        @ForAll tail: Tail,
        @ForAll @IntRange(min = 1, max = 200) stallSeconds: Int,
    ) = runTest {
        val stall = stallSeconds * 1000L
        val failure = IllegalStateException("flow broke")
        val events = flow {
            for (s in steps) {
                delay(s.gap)
                emit(s.event)
            }
            if (tail == Tail.HANGS) awaitCancellation()
            if (tail == Tail.THROWS) throw failure
        }
        val seen = mutableListOf<Pair<Long, DownloadView>>()
        val watch =
            launch(start = CoroutineStart.UNDISPATCHED) {
                Outside.watch(events, stall) { seen += currentTime to it }
            }
        val total = steps.sumOf { it.gap }
        advanceTimeBy(total + stall + 1)
        runCurrent()

        // When the first real sign of life came: a non-Started event, or the flow breaking.
        var t = 0L
        var firstMove: Long? = null
        for (s in steps) {
            t += s.gap
            if (firstMove == null && s.event !is Download.Started) firstMove = t
        }
        if (firstMove == null && tail == Tail.THROWS) firstMove = total
        // Quiet from the request until the first sign of life, or until the flow ended.
        val quietUntil = firstMove ?: if (tail == Tail.HANGS) Long.MAX_VALUE else total
        Assume.that(quietUntil != stall)

        assertEquals("Preparing comes first", DownloadView.Preparing, seen.first().second)
        val stalls = seen.filter { it.second == DownloadView.Stalled }
        if (quietUntil > stall) {
            assertEquals("one stall notice", 1, stalls.size)
            assertEquals("stall notice on time", stall, stalls.single().first)
        } else assertTrue("no stall notice after progress", stalls.isEmpty())

        val moves = steps.filter { it.event !is Download.Started }
        // The first Completed or Failed is final.
        val terminal =
            moves
                .firstOrNull { it.event == Download.Completed || it.event is Download.Failed }
                ?.event
        val terminalView =
            when (terminal) {
                Download.Completed -> DownloadView.Done
                is Download.Failed -> DownloadView.Failed(terminal.error)
                else -> null
            }
        if (tail == Tail.HANGS)
            assertTrue("still watching a flow that has not ended", watch.isActive)
        else if (tail == Tail.THROWS) {
            assertFalse(watch.isActive)
            assertEquals(terminalView ?: DownloadView.Failed(failure), seen.last().second)
        } else {
            run {
                assertFalse(watch.isActive)
                // Never left showing "Preparing" or a byte count once the flow is over.
                val expected = terminalView ?: DownloadView.Ended
                assertEquals("ends on how the flow ended", expected, seen.last().second)
            }
        }
        watch.cancel()
    }
}
