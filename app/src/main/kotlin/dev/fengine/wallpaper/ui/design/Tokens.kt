package dev.fengine.wallpaper.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The design language in numbers. Screens never write a raw dp value; they name a token here.
 * `DesignLanguageTest` enforces that. The vocabulary is small on purpose: editorial pages are
 * built from generous vertical rhythm, one radius per element class, and a 3-column photo grid.
 */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 40.dp

    /** Side gutter of every page. */
    val page: Dp = 24.dp
    /** Gap between a section eyebrow and its content. */
    val eyebrow: Dp = 12.dp
    /** Gap between sections. */
    val section: Dp = 32.dp
}

object Radius {
    /** Photo tiles and small images. */
    val tile: Dp = 12.dp
    /** Cards, covers, status surfaces. */
    val card: Dp = 20.dp
}

object Grid {
    /** Columns come from [LayoutSpec]; this is the gap between tiles. */
    val gap: Dp = 8.dp
}

object Size {
    /** Cover aspect ratio (portrait, like a lock screen). */
    const val coverAspect = 0.8f
    /** Tap target for the remove affordance on a tile; the visible disc inside it is [tileActionDisc]. */
    val tileAction: Dp = 44.dp
    val tileActionDisc: Dp = 26.dp
    val tileIcon: Dp = 16.dp
    val progress: Dp = 20.dp
    val progressStroke: Dp = 2.dp
}
