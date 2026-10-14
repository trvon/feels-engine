package dev.fengine.wallpaper.invariants

import dev.fengine.wallpaper.ui.design.LayoutSpec
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.Assert.assertTrue

/** Layout rules must stay sane from watch-sized widths to desktop windows. */
class LayoutSpecProperties {
    @Property(tries = 500)
    fun columnsAndWidthsGrowWithTheWindow(@ForAll @IntRange(min = 200, max = 3000) a: Int, @ForAll @IntRange(min = 200, max = 3000) b: Int) {
        val (small, large) = if (a <= b) a to b else b to a
        val s = LayoutSpec.forWidth(small); val l = LayoutSpec.forWidth(large)
        assertTrue(s.columns in 2..8 && l.columns in 2..8)
        assertTrue("columns shrink from $small to $large", s.columns <= l.columns)
        assertTrue(s.coverWidth <= l.coverWidth && s.maxContentWidth <= l.maxContentWidth)
        // A tile is never narrower than a comfortable tap target on any width.
        val tile = (minOf(small, s.maxContentWidth.value.toInt()) - 48) / s.columns
        assertTrue("tile ${tile}dp too small at ${small}dp", tile >= 44)
    }
}
