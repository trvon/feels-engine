package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.screensaver.DreamGate
import dev.fengine.wallpaper.screensaver.DreamGate.Verdict
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/** The screen saver never plays in car mode, never on a cable when restricted, and never on nothing. */
class DreamGateProperties {
    @Property(tries = 2000)
    fun rules(@ForAll car: Boolean, @ForAll @IntRange(min = 0, max = 15) plugged: Int, @ForAll wirelessOnly: Boolean, @ForAll photos: Boolean) {
        val v = DreamGate.decide(car, plugged, wirelessOnly, photos)
        if (car) assertEquals(Verdict.SKIP_CAR, v)
        val wirelessOrDock = plugged and (DreamGate.PLUGGED_WIRELESS or DreamGate.PLUGGED_DOCK) != 0
        if (wirelessOnly && !wirelessOrDock) assertNotEquals(Verdict.PLAY, v)
        if (!photos) assertNotEquals(Verdict.PLAY, v)
        // And it does play whenever nothing forbids it: the veto is never broader than its rules.
        if (!car && (!wirelessOnly || wirelessOrDock) && photos) assertEquals(Verdict.PLAY, v)
    }

    @Property(tries = 200)
    fun carUsbChargerNeverPlaysWhenRestricted(@ForAll photos: Boolean) {
        assertNotEquals(Verdict.PLAY, DreamGate.decide(carMode = false, plugged = 2, wirelessOnly = true, hasPhotos = photos))
    }
}
