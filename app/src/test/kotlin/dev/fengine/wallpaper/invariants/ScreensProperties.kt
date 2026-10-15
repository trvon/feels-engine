package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
import dev.fengine.wallpaper.data.Settings
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/** Every screen holds its own group: assigning one never moves another, and nothing points at a deleted group. */
class ScreensProperties {
    private val ids = listOf("a", "b", "c")

    @Provide
    fun settings(): Arbitrary<Settings> {
        val groups = Arbitraries.subsetOf(ids).map { set ->
            set.sorted().map { Group(id = it, name = it, photos = if (it == "c") emptyList() else listOf("content://p/$it")) }
        }
        val ref = Arbitraries.of("a", "b", "c", "gone").injectNull(0.3)
        return Combinators.combine(groups, ref, ref, ref, ref).`as` { g, h, l, c, d ->
            Settings(groups = g, homeGroupId = h, lockGroupId = l, coverGroupId = c, dreamGroupId = d)
        }
    }

    @Provide fun screens(): Arbitrary<Screen> = Arbitraries.of(*Screen.entries.toTypedArray())
    @Provide fun refs(): Arbitrary<String?> = Arbitraries.of("a", "b", "c", "gone").injectNull(0.3)

    @Property(tries = 3000)
    fun assignChangesOnlyThatScreen(@ForAll("settings") s: Settings, @ForAll("screens") screen: Screen, @ForAll("refs") id: String?) {
        val out = Screens.assign(s, screen, id)
        for (other in Screen.entries) if (other != screen) assertEquals(Screens.assigned(s, other), Screens.assigned(out, other))
        val expected = id?.takeIf { g -> s.groups.any { it.id == g } }
        assertEquals("only existing groups are assigned", expected, Screens.assigned(out, screen))
        assertEquals(s.groups, out.groups)
    }

    @Property(tries = 3000)
    fun deletingAGroupLeavesNoScreenPointingAtIt(@ForAll("settings") s: Settings, @ForAll("refs") id: String?) {
        val gone = id ?: return
        val out = Screens.withoutGroup(s, gone)
        assertTrue(out.groups.none { it.id == gone })
        for (screen in Screen.entries) {
            if (Screens.assigned(s, screen) == gone) assertNull(Screens.assigned(out, screen))
            else assertEquals(Screens.assigned(s, screen), Screens.assigned(out, screen))
        }
        assertTrue(Screens.of(out, gone).isEmpty())
    }

    @Property(tries = 2000)
    fun fallbacksFollowTheRules(@ForAll("settings") s: Settings) {
        assertEquals(s.homeGroup, Screens.effective(s, Screen.HOME))
        assertEquals(s.lockGroup, Screens.effective(s, Screen.LOCK))
        assertEquals(s.coverGroup ?: s.homeGroup, Screens.effective(s, Screen.COVER))
        val saver = Screens.effective(s, Screen.SCREEN_SAVER)
        when {
            s.dreamGroup != null -> assertEquals(s.dreamGroup, saver)
            s.homeGroup != null -> assertEquals(s.homeGroup, saver)
            else -> assertTrue("screen saver fell back to an empty group", saver == null || saver.allPhotos.isNotEmpty())
        }
    }

    @Property(tries = 1000)
    fun everyScreenCanHoldADifferentGroup(@ForAll("screens") a: Screen, @ForAll("screens") b: Screen) {
        if (a == b) return
        val s = Settings(groups = ids.map { Group(id = it, name = it) })
        val out = Screens.assign(Screens.assign(s, a, "a"), b, "b")
        assertEquals("a", Screens.assigned(out, a)); assertEquals("b", Screens.assigned(out, b))
        assertEquals(listOf(a), Screens.of(out, "a")); assertEquals(listOf(b), Screens.of(out, "b"))
    }
}
