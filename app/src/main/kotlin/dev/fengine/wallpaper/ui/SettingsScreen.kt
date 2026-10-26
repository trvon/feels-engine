package dev.fengine.wallpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.fengine.wallpaper.data.Backdrop
import dev.fengine.wallpaper.data.ChangeTriggers
import dev.fengine.wallpaper.data.ColorSource
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.PhotoFit
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.ThemeMode
import dev.fengine.wallpaper.ui.design.ActionBar
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.Headline
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.Radius
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Segmented
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.SwitchRow
import dev.fengine.wallpaper.ui.design.TextAction
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import kotlin.math.roundToInt

/**
 * Infrequent preferences; photo actions and screen assignment stay on Home. Every single choice of
 * 2 to 5 options is a segmented button (docs/product/2026-10-25-settings-review.md); groups are
 * ordered by how often they change: Rotation, Look, Appearance, Screen saver.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onBack: () -> Unit,
    onSetMode: (Mode) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetTimes: (List<Int>) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    motion: LiveWallpaper.State,
    onTurnOnMotion: () -> Unit,
    onSetStill: () -> Unit,
    dreamConfigured: Boolean,
    onOpenDreamSettings: () -> Unit,
    onSetDreamWirelessOnly: (Boolean) -> Unit,
    hasCoverScreen: Boolean,
    onSetThemeMode: (ThemeMode) -> Unit = {},
    onSetColorSource: (ColorSource) -> Unit = {},
    onSetBackdrop: (Backdrop) -> Unit = {},
    onSetPhotoFit: (PhotoFit) -> Unit = {},
    screenSaverWeek: Map<String, Int> = emptyMap(),
    onSetTapToChange: (Boolean) -> Unit = {},
    onSetChangeOnTheme: (Boolean) -> Unit = {},
    onSetDarkAtNight: (Boolean) -> Unit = {},
) {
    val moving = motion.innerHome || motion.innerLock || motion.coverHome || motion.coverLock
    Page {
        ActionBar("Back" to onBack)
        Headline("Settings", "Rotation, wallpaper and the way the app looks.")
        Spacer(Modifier.height(Space.section))

        PreferenceGroup("Rotation") {
            Segmented(
                options =
                    listOf(Mode.INTERVAL to "Every so often", Mode.TIME_OF_DAY to "Set times"),
                selected = settings.mode,
                onSelect = onSetMode,
            )
            Spacer(Modifier.height(Space.md))
            when (settings.mode) {
                Mode.INTERVAL -> {
                    IntervalSlider(settings.intervalMinutes, onSetInterval)
                    Spacer(Modifier.height(Space.sm))
                    Caption(
                        "Android may delay a change while the app goes unused; opening it now and then keeps changes on time."
                    )
                }
                Mode.TIME_OF_DAY ->
                    TimesEditor(
                        settings.timesOfDay,
                        onSetTimes,
                        android.text.format.DateFormat.is24HourFormat(LocalContext.current),
                    )
            }
            Spacer(Modifier.height(Space.lg))
            SwitchRow("Shuffle photos", settings.shuffle, onSetShuffle)
            Caption(
                if (settings.shuffle) "Photos play in a shuffled order."
                else "Photos play in group order."
            )
            SwitchRow("Darker photos in dark mode", settings.darkAtNight, onSetDarkAtNight)
            Caption(
                if (settings.darkAtNight)
                    "While the phone is dark, each change picks one of the group's darker photos when it has any."
                else "Every photo can come up, day or night."
            )
            Spacer(Modifier.height(Space.lg))
            Text("Also change", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "When I double-tap the home screen",
                settings.tapToChange,
                onSetTapToChange,
                enabled = moving,
                note = if (moving) null else "Needs Motion",
            )
            SwitchRow(
                "When light or dark mode switches",
                settings.changeOnTheme,
                onSetChangeOnTheme,
                enabled = moving,
                note = if (moving) null else "Needs Motion",
            )
            Caption(
                if (moving)
                    "Taps count on empty home screen space. These work even while rotation is off."
                else
                    "Needs Motion, under Look: the moving wallpaper is what hears taps and the switch."
            )
        }

        PreferenceGroup("Look") {
            val home = motion.innerHome || motion.coverHome
            val lock = motion.innerLock || motion.coverLock
            Segmented(
                options = listOf(false to "Still", true to "Motion"),
                selected = moving,
                onSelect = { wantMotion ->
                    if (wantMotion) onTurnOnMotion() else if (moving) onSetStill()
                },
            )
            Spacer(Modifier.height(Space.sm))
            Caption(
                when {
                    motion.coverHome && !motion.innerHome ->
                        "Motion is on the cover screen only. Open the phone and use Motion preview to set it on the inner screen."
                    home ->
                        (if (lock) "Motion is on home and lock screens."
                        else "Motion is on the home screen. The lock screen stays still.") +
                            (if (hasCoverScreen && motion.innerHome && !motion.coverHome)
                                " Open Motion preview while closed to set it on the cover too."
                            else "")
                    lock ->
                        "Motion is on the lock screen only. Open Motion preview to change its screen assignment."
                    else ->
                        "Each photo stays put until the next change. Motion starts after you confirm it in Android's preview."
                }
            )
            // A selected segment may not expose a click to accessibility services; keep the chooser
            // reachable.
            if (moving) TextAction("Motion preview", onTurnOnMotion)
            Spacer(Modifier.height(Space.md))
            Text("Fit", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.sm))
            Segmented(
                options = listOf(PhotoFit.FILL to "Fill screen", PhotoFit.WHOLE to "Whole photo"),
                selected = settings.photoFit,
                onSelect = onSetPhotoFit,
            )
            if (settings.photoFit == PhotoFit.WHOLE) {
                Spacer(Modifier.height(Space.md))
                Text("Around the photo", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Space.sm))
                Segmented(
                    options = listOf(Backdrop.BLACK to "Black", Backdrop.PHOTO to "Photo colours"),
                    selected = settings.backdrop,
                    onSelect = onSetBackdrop,
                )
            }
            Spacer(Modifier.height(Space.sm))
            Caption(
                if (settings.photoFit == PhotoFit.FILL)
                    "Motion and the screen saver crop each photo to fill the screen. Still wallpapers are always cropped by Android."
                else
                    "Motion and the screen saver show each whole photo, uncropped, without the drift."
            )
        }

        PreferenceGroup("Appearance") {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.sm))
            Segmented(
                options =
                    listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ),
                selected = settings.themeMode,
                onSelect = onSetThemeMode,
            )
            Spacer(Modifier.height(Space.md))
            Text("Colours", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.sm))
            Segmented(
                options = listOf(ColorSource.PHOTO to "From photo", ColorSource.PHONE to "Phone"),
                selected = settings.colorSource,
                onSelect = onSetColorSource,
            )
            Spacer(Modifier.height(Space.sm))
            Caption(
                if (settings.colorSource == ColorSource.PHOTO)
                    "The app takes its colours from the photo on your home screen and changes with it."
                else "The app uses your phone's own colour palette."
            )
        }

        PreferenceGroup("Screen saver") {
            val dream = settings.dreamGroup ?: settings.homeGroup
            Caption(
                when {
                    dream == null ->
                        "Assign a group under Screens on Home to give the screen saver photos."
                    dreamConfigured ->
                        "${dream.name} is selected. Android decides when the screen saver starts."
                    else ->
                        "${dream.name} is ready. Choose Feels Engine photos in Android's screen saver settings."
                }
            )
            TextAction(
                if (dreamConfigured) "Screen saver settings" else "Set up screen saver",
                onOpenDreamSettings,
            )
            Spacer(Modifier.height(Space.md))
            Text("Plays on", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.sm))
            Segmented(
                options = listOf(false to "Any charger", true to "Wireless or dock"),
                selected = settings.dreamWirelessOnly,
                onSelect = onSetDreamWirelessOnly,
            )
            Spacer(Modifier.height(Space.sm))
            val wiredSkips = screenSaverWeek["SKIP_WIRED"] ?: 0
            Caption(
                (if (settings.dreamWirelessOnly) "A cable, like a car charger, does not start it."
                else "Plays on any charger, cables included.") +
                    " It never plays while the phone is in car mode." +
                    (if (settings.dreamWirelessOnly && wiredSkips > 0)
                        " This week it stayed off on a cable ${if (wiredSkips == 1) "once" else "$wiredSkips times"}."
                    else "")
            )
        }
    }
}

/**
 * Any interval from 15 minutes to a week on one stepped slider: 15, 30, 45 minutes, 1, 1.5, 2, 3,
 * 4, 6, 8, 12 hours, 1, 2, 3 days, a week. The value is saved when the thumb is let go.
 */
@Composable
private fun IntervalSlider(minutes: Int, onChange: (Int) -> Unit) {
    val stops = ChangeTriggers.INTERVAL_STOPS
    var index by
        androidx.compose.runtime.remember(minutes) {
            mutableStateOf(ChangeTriggers.nearestStop(minutes).toFloat())
        }
    // Rounded, not truncated: a snapped 12.999999 is stop 13.
    val shown = stops[index.roundToInt().coerceIn(stops.indices)]
    Text(Format.every(shown), style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = index,
        onValueChange = { index = it },
        onValueChangeFinished = { onChange(stops[index.roundToInt().coerceIn(stops.indices)]) },
        valueRange = 0f..(stops.size - 1).toFloat(),
        steps = stops.size - 2,
        modifier =
            Modifier.fillMaxWidth().semantics {
                contentDescription = "Interval"
                stateDescription = Format.every(shown)
            },
    )
}

/** Tonal containers use the page's palette. */
@Composable
private fun PreferenceGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Section(title) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(Radius.card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(Space.lg), content = content)
        }
    }
}

/**
 * Set times as chips: tap X to remove one, "Add time" opens Material's time picker. No free text,
 * no Save step.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimesEditor(
    current: List<Int>,
    onChange: (List<Int>) -> Unit,
    is24: Boolean,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        current.forEach { minutes ->
            val label = Format.clock(minutes, is24)
            InputChip(
                selected = false,
                onClick = { if (current.size > 1) onChange(current - minutes) },
                label = { Text(label) },
                enabled = current.size > 1,
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                modifier = Modifier.semantics { contentDescription = "Remove $label" },
            )
        }
        AssistChip(onClick = { picking = true }, label = { Text("Add time") })
    }
    Spacer(Modifier.height(Space.sm))
    Caption(
        if (current.size > 1) "Tap a time to remove it."
        else "Keep at least one time. Add another to remove this one."
    )
    if (picking) {
        val state = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = is24)
        val chosen = state.hour * 60 + state.minute
        val already = chosen in current
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Add a time") },
            // Scrolls on a short screen (phone landscape), so Add is never cut off.
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TimePicker(state = state)
                    if (already) Caption("${Format.clock(chosen, is24)} is already set.")
                }
            },
            confirmButton = {
                TextAction("Add", enabled = !already) {
                    picking = false
                    onChange(TimeSlots.normalize(current + chosen))
                }
            },
            dismissButton = { TextAction("Cancel") { picking = false } },
        )
    }
}
