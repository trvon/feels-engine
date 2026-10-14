package dev.fengine.wallpaper.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width-driven layout decisions, computed once per page from the window width. Material's
 * window size classes drive it: compact (phones), medium (small tablets, unfolded foldables),
 * expanded (tablets). Tokens stay fixed; only these few numbers move.
 */
data class LayoutSpec(
    /** Columns in a photo grid. */
    val columns: Int,
    /** Width of a cover in the group rail. */
    val coverWidth: Dp,
    /** Content never grows past this; it centres instead, so tablets do not stretch text lines. */
    val maxContentWidth: Dp,
) {
    companion object {
        fun forWidth(widthDp: Int): LayoutSpec = when {
            widthDp < 360 -> LayoutSpec(columns = 3, coverWidth = 128.dp, maxContentWidth = 600.dp)   // small phones
            widthDp < 600 -> LayoutSpec(columns = 3, coverWidth = 150.dp, maxContentWidth = 600.dp)   // phones, cover screens
            widthDp < 840 -> LayoutSpec(columns = 4, coverWidth = 170.dp, maxContentWidth = 720.dp)   // unfolded foldables, small tablets
            else -> LayoutSpec(columns = 6, coverWidth = 190.dp, maxContentWidth = 840.dp)            // tablets
        }
    }
}

val LocalLayout = compositionLocalOf { LayoutSpec.forWidth(400) }

@Composable
fun currentLayoutSpec(): LayoutSpec = LayoutSpec.forWidth(LocalConfiguration.current.screenWidthDp)
