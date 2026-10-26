package dev.fengine.wallpaper.ui

import android.app.Activity
import android.app.WallpaperManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.fengine.wallpaper.data.Appearance
import dev.fengine.wallpaper.data.ColorSource
import dev.fengine.wallpaper.data.ThemeMode

/**
 * Editorial theme: Material You colours drawn from the wallpaper itself on Android 12+, a serif
 * display face for headlines, generous leading. Everything else is Material 3 defaults.
 */
@Composable
fun FengineTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorSource: ColorSource = ColorSource.PHOTO,
    content: @Composable () -> Unit,
) {
    val dark = Appearance.isDark(themeMode, isSystemInDarkTheme())
    val context = LocalContext.current
    val seed = if (colorSource == ColorSource.PHOTO) rememberWallpaperSeed() else null
    val scheme =
        when {
            // From photo: the current wallpaper's main colour, on any Android version, recoloured
            // live.
            seed != null -> photoScheme(Palette.scheme(seed, dark), dark)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    // Status bar icons follow the app's theme, not only the phone's, when the owner forces one.
    val view = LocalView.current
    if (!view.isInEditMode)
        SideEffect {
            (view.context as? Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).run {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    val base = Typography()
    val typography =
        base.copy(
            displaySmall =
                base.displaySmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                ),
            headlineSmall =
                base.headlineSmall.copy(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                ),
            titleLarge =
                base.titleLarge.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
            labelLarge = base.labelLarge.copy(letterSpacing = 0.8.sp),
        )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}

/**
 * The wallpaper's main colour, updated whenever the wallpaper changes (our own rotations included).
 * WallpaperManager.getWallpaperColors and its listener need no permission (API 27).
 */
@Composable
private fun rememberWallpaperSeed(): Int? {
    val context = LocalContext.current
    val wm = remember { WallpaperManager.getInstance(context) }
    // Read off the main thread: the system may first extract colours from the wallpaper.
    var seed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(wm) {
        val first =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
                }
                    .getOrNull()
            }
        if (seed == null) seed = first
    }
    DisposableEffect(wm) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0)
                colors?.primaryColor?.toArgb()?.let { seed = it }
        }
        val handler = Handler(Looper.getMainLooper())
        runCatching { wm.addOnColorsChangedListener(listener, handler) }
        onDispose { runCatching { wm.removeOnColorsChangedListener(listener) } }
    }
    return seed
}

private fun photoScheme(p: Palette.Scheme, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    fun c(v: Int) = Color(v)
    return base.copy(
        primary = c(p.primary),
        onPrimary = c(p.onPrimary),
        primaryContainer = c(p.primaryContainer),
        onPrimaryContainer = c(p.onPrimaryContainer),
        secondary = c(p.secondary),
        onSecondary = c(p.onSecondary),
        secondaryContainer = c(p.secondaryContainer),
        onSecondaryContainer = c(p.onSecondaryContainer),
        tertiary = c(p.secondary),
        onTertiary = c(p.onSecondary),
        background = c(p.background),
        onBackground = c(p.onBackground),
        surface = c(p.background),
        onSurface = c(p.onBackground),
        surfaceVariant = c(p.surfaceVariant),
        onSurfaceVariant = c(p.onSurfaceVariant),
        surfaceContainerLowest = c(p.background),
        surfaceContainerLow = c(p.surfaceContainer),
        surfaceContainer = c(p.surfaceContainer),
        surfaceContainerHigh = c(p.surfaceVariant),
        surfaceContainerHighest = c(p.surfaceVariant),
        surfaceTint = c(p.primary),
        outline = c(p.outline),
        outlineVariant = c(p.outlineVariant),
        tertiaryContainer = c(p.secondaryContainer),
        onTertiaryContainer = c(p.onSecondaryContainer),
        inverseSurface = c(p.onBackground),
        inverseOnSurface = c(p.background),
        inversePrimary = c(p.primaryContainer),
    )
}
