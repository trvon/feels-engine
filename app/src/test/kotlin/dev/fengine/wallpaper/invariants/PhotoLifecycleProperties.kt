package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.schedule.RotationPlan
import dev.fengine.wallpaper.wallpaper.CopyPlan
import dev.fengine.wallpaper.wallpaper.PhotoCopies
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Model-based fuzz of a photo's life: the person adds, removes and skips photos; Google Photos
 * frees up space (the original goes) or the photo comes back; the copy worker runs; rotations run;
 * the app opens; copies are switched off and on. Random sequences of these drive the real [Group],
 * [RotationPlan.advance], [RotationPlan.settle] and [CopyPlan]; only the decode is modelled, as
 * [PhotoCopies.decode] does it (the original, else its copy).
 *
 * After every step: no photo the person chose is ever dropped by the app, the cursor names a photo,
 * a photo with a saved copy is never set aside, copies match the groups, and a rotation that showed
 * a photo leaves the cursor on it.
 */
class PhotoLifecycleProperties {

    sealed interface Op {
        data class Add(val ids: List<Int>) : Op

        data class Remove(val pick: Int) : Op

        data class Skip(val pick: Int, val on: Boolean) : Op

        data class Gone(val pick: Int) : Op

        data class Back(val pick: Int) : Op

        data object CopyRun : Op

        data object Rotate : Op

        data object Open : Op

        data object ToggleCopies : Op

        /** "Pick it again" on a photo, which comes back as a cloud picker URI. */
        data class Repick(val pick: Int) : Op
    }

    @Provide
    fun ops(): Arbitrary<List<Op>> {
        val pick = Arbitraries.integers().between(0, 40)
        fun w(n: Int, a: Arbitrary<out Op>): net.jqwik.api.Tuple.Tuple2<Int, Arbitrary<out Op>> =
            net.jqwik.api.Tuple.of(n, a)
        return Arbitraries.frequencyOf(
                w(
                    3,
                    Arbitraries.integers().between(0, 12).list().ofMinSize(1).ofMaxSize(4).map {
                        Op.Add(it)
                    },
                ),
                w(1, pick.map { Op.Remove(it) }),
                w(1, Arbitraries.of(true, false).flatMap { on -> pick.map { Op.Skip(it, on) } }),
                w(3, pick.map { Op.Gone(it) }),
                w(1, pick.map { Op.Back(it) }),
                w(2, Arbitraries.just(Op.CopyRun)),
                w(4, Arbitraries.just(Op.Rotate)),
                w(1, Arbitraries.just(Op.Open)),
                w(1, Arbitraries.just(Op.ToggleCopies)),
                w(1, pick.map { Op.Repick(it) }),
            )
            .list()
            .ofMaxSize(40)
    }

    /** Both URI forms of one photo, as the picker and the library hand them out. */
    private fun uri(id: Int) =
        if (id % 2 == 0)
            "content://media/picker/0/com.android.providers.media.photopicker/media/$id"
        else "content://media/external/images/media/$id"

    private class World {
        var group = Group(id = "g", name = "G")
        val gone = HashSet<String>() // identities whose original is off the phone
        val copies = HashSet<String>() // copy file names
        var keep = true
        val chosen = LinkedHashSet<String>() // identities the person added and has not removed
        var repicks = 0

        fun readable(u: String) = PhotoId.identity(u) !in gone || PhotoCopies.name(u) in copies

        fun at(pick: Int): String? =
            group.allPhotos.let { if (it.isEmpty()) null else it[pick % it.size] }
    }

    @Property(tries = 1500)
    fun aPhotosLifeKeepsEveryPromise(@ForAll("ops") ops: List<Op>) {
        val w = World()
        for (op in ops) {
            var shown: String? = null
            when (op) {
                is Op.Add -> {
                    val uris = op.ids.map(::uri)
                    w.group = w.group.withPhotos(uris)
                    w.chosen += uris.map(PhotoId::identity)
                }
                is Op.Remove ->
                    w.at(op.pick)?.let {
                        w.group = w.group.withoutPhoto(it)
                        w.chosen -= PhotoId.identity(it)
                    }
                is Op.Skip ->
                    w.at(op.pick)?.let { u ->
                        val rest =
                            w.group.skipped.filterNot {
                                PhotoId.identity(it) == PhotoId.identity(u)
                            }
                        w.group = w.group.copy(skipped = if (op.on) rest + u else rest, cursor = -1)
                    }
                is Op.Gone -> w.at(op.pick)?.let { w.gone += PhotoId.identity(it) }
                is Op.Back -> w.at(op.pick)?.let { w.gone -= PhotoId.identity(it) }
                Op.CopyRun -> {
                    val plan = CopyPlan.plan(w.group.allPhotos, w.copies.toSet(), w.keep)
                    w.copies -= plan.delete.toSet()
                    // A copy can only be made while the original is on the phone.
                    val copied = plan.save.filter { PhotoId.identity(it) !in w.gone }
                    copied.forEach { w.copies += PhotoCopies.name(it) }
                    // As PhotoCopyWorker does: what it could copy is readable again.
                    w.group = w.group.markReadable(copied)
                    if (w.keep) {
                        val present = w.group.allPhotos.filter { PhotoId.identity(it) !in w.gone }
                        assertTrue(
                            "every present photo has a copy",
                            present.all { PhotoCopies.name(it) in w.copies },
                        )
                        assertTrue(
                            "no stray copies",
                            w.copies.all { n ->
                                w.group.allPhotos.any { PhotoCopies.name(it) == n }
                            },
                        )
                    } else assertTrue("copies off leaves none", w.copies.isEmpty())
                }
                Op.Rotate -> shown = rotate(w)
                Op.Open -> {
                    // checkPhotos: originals that open again come back into rotation.
                    val back = w.group.unreadable.filter { PhotoId.identity(it) !in w.gone }
                    w.group = w.group.markReadable(back)
                }
                Op.ToggleCopies -> w.keep = !w.keep
                is Op.Repick ->
                    w.at(op.pick)?.let { old ->
                        val new =
                            "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/r${w.repicks++}"
                        val before =
                            w.group.allPhotos.indexOfFirst {
                                PhotoId.identity(it) == PhotoId.identity(old)
                            }
                        w.group = w.group.replacePhoto(old, new)
                        if (w.chosen.remove(PhotoId.identity(old)))
                            w.chosen += PhotoId.identity(new)
                        assertEquals(
                            "re-pick keeps the place",
                            before,
                            w.group.allPhotos.indexOf(new),
                        )
                        assertTrue("re-picked photo is in rotation", !w.group.isUnreadable(new))
                    }
            }
            check(w, op, shown)
        }
    }

    /** Rotation.run with a modelled decode: up to 4 passes while photos turn out unreadable. */
    private fun rotate(w: World): String? {
        var pins = emptyMap<String, Int>()
        var shown: String? = null
        repeat(4) {
            val before = w.group
            val plan =
                RotationPlan.advance(
                    homeGroupId = "g",
                    lockGroupId = null,
                    photos = { before.rotation },
                    cursor = { before.cursor },
                    shuffle = false,
                    liveHome = false,
                    liveLock = false,
                    pinned = pins,
                )
            if (plan.cursors.isEmpty()) return shown
            val unreadable = mutableListOf<Pair<String, String>>()
            for (write in plan.writes) {
                if (w.readable(write.uri)) shown = write.uri
                else unreadable += write.groupId to write.uri
            }
            for ((_, u) in unreadable) {
                assertTrue(
                    "a photo with a copy is never set aside",
                    PhotoCopies.name(u) !in w.copies,
                )
            }
            w.group =
                RotationPlan.settle(
                        listOf(w.group),
                        { before.takeIf { b -> b.id == it } },
                        plan.cursors,
                        unreadable,
                    )
                    .single()
            if (unreadable.isEmpty()) return shown
            pins = emptyMap()
        }
        return shown
    }

    private fun check(w: World, op: Op, shown: String?) {
        val g = w.group
        val ids = g.allPhotos.map(PhotoId::identity).toSet()
        assertTrue("$op dropped a chosen photo", w.chosen.all { it in ids })
        assertTrue(
            "$op cursor ${g.cursor} outside ${g.rotation.size}",
            g.cursor in -1 until g.rotation.size,
        )
        assertEquals("$op emptied the rotation", g.allPhotos.isEmpty(), g.rotation.isEmpty())
        assertTrue(
            "$op set aside a photo the app can still show from its copy",
            g.unreadable.none { PhotoCopies.name(it) in w.copies },
        )
        if (op == Op.Rotate && shown != null)
            assertEquals(
                "the cursor names the photo just shown",
                PhotoId.identity(shown),
                g.rotation.getOrNull(g.cursor)?.let(PhotoId::identity),
            )
    }
}
