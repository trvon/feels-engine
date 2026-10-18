package dev.fengine.wallpaper.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.ui.design.ActionBar
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.ChipRow
import dev.fengine.wallpaper.ui.design.ChoiceRow
import dev.fengine.wallpaper.ui.design.Headline
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.Radius
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Size
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.SwitchRow
import dev.fengine.wallpaper.ui.design.TextAction
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper

/** Infrequent preferences live here; photo actions and screen assignment stay on Home. */
@OptIn(ExperimentalLayoutApi::class)
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
) {
    Page {
        ActionBar("Back" to onBack)
        Headline("Settings", "Rotation, wallpaper and the way the app looks.")
        Spacer(Modifier.height(Space.section))

        PreferenceGroup("Rotation") {
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Every so often", "Change photos at a regular interval.", settings.mode == Mode.INTERVAL) {
                    onSetMode(Mode.INTERVAL)
                }
                ChoiceRow("At set times", "Change photos at the times you save below.", settings.mode == Mode.TIME_OF_DAY) {
                    onSetMode(Mode.TIME_OF_DAY)
                }
            }
            Spacer(Modifier.height(Space.md))
            when (settings.mode) {
                Mode.INTERVAL -> {
                    ChipRow(
                        options = listOf(15 to "15 min", 60 to "Hour", 180 to "3 hours", 1440 to "Day"),
                        selected = settings.intervalMinutes,
                        onSelect = onSetInterval,
                    )
                    Caption("${Format.schedule(settings)}. Android may delay changes to save battery.")
                }

                Mode.TIME_OF_DAY -> {
                    TimesEditor(settings.timesOfDay, onSetTimes)
                }
            }
            Spacer(Modifier.height(Space.lg))
            SwitchRow("Shuffle photos", settings.shuffle, onSetShuffle)
            Caption(if (settings.shuffle) "Photos play in a shuffled order." else "Photos play in group order.")
        }

        PreferenceGroup("Wallpaper") {
            val home = motion.innerHome || motion.coverHome
            val lock = motion.innerLock || motion.coverLock
            val moving = home || lock
            Column(Modifier.selectableGroup()) {
                ChoiceRow("Still", "Each photo stays put until the next change.", !moving) {
                    if (moving) onSetStill()
                }
                ChoiceRow("Motion", "Cross-fade photos and move with the home screen. Opens Android’s wallpaper preview.", moving) {
                    onTurnOnMotion()
                }
            }
            Spacer(Modifier.height(Space.sm))
            Caption(
                when {
                    motion.coverHome && !motion.innerHome -> {
                        "Motion is on the cover screen only. Open the phone and use Motion preview to set it on the inner screen."
                    }

                    home -> {
                        (if (lock) "Motion is on home and lock screens." else "Motion is on the home screen. The lock screen stays still.") +
                            (
                                if (hasCoverScreen && motion.innerHome &&
                                    !motion.coverHome
                                ) {
                                    " Open Motion preview while closed to set it on the cover too."
                                } else {
                                    ""
                                }
                            )
                    }

                    lock -> {
                        "Motion is on the lock screen only. Open Motion preview to change its screen assignment."
                    }

                    else -> {
                        "Still is active. Motion starts only after you confirm it in Android’s preview."
                    }
                },
            )
            // Selected radio options may not expose a click action to accessibility services.
            // Keep the chooser reachable for assigning Motion to another panel.
            if (moving) TextAction("Motion preview", onTurnOnMotion)
        }

        PreferenceGroup("Screen saver") {
            val dream = settings.dreamGroup ?: settings.homeGroup
            Caption(
                when {
                    dream == null -> "Assign a group under Screens on Home to give the screen saver photos."
                    dreamConfigured -> "${dream.name} is selected. Android controls when the screen saver starts."
                    else -> "${dream.name} is ready. Choose Feels Engine photos in Android’s screen saver settings."
                },
            )
            TextAction(if (dreamConfigured) "Screen saver settings" else "Set up screen saver", onOpenDreamSettings)
            Spacer(Modifier.height(Space.md))
            SwitchRow("Wireless charging or dock only", settings.dreamWirelessOnly, onSetDreamWirelessOnly)
            Caption(
                if (settings.dreamWirelessOnly) {
                    "A cable, like a car charger, does not start it. Car mode always blocks playback."
                } else {
                    "Allows any charger. Turn this on if playback starts in the car. Car mode always blocks playback."
                },
            )
        }

        PreferenceGroup("Appearance") {
            Text("Follows your phone", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Space.sm))
            Caption(
                (if (isSystemInDarkTheme()) "Dark theme is active. " else "Light theme is active. ") +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "App backgrounds and controls use Android’s system colors. Change your phone’s color palette to change them here. Your photos stay unchanged."
                    } else {
                        "App backgrounds follow the phone’s light or dark theme. System color palettes need Android 12 or later."
                    },
            )
            Spacer(Modifier.height(Space.md))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                val colors = MaterialTheme.colorScheme
                listOf(colors.primary, colors.secondary, colors.tertiary, colors.surfaceContainerHigh).forEach { color ->
                    Box(Modifier.size(Size.touchTarget).background(color, RoundedCornerShape(Radius.tile)))
                }
            }
        }
    }
}

/** Tonal containers use the same system palette as the page, never a sampled photo color. */
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

@Composable
private fun TimesEditor(
    current: List<Int>,
    onChange: (List<Int>) -> Unit,
) {
    var text by rememberSaveable(current) { mutableStateOf(current.joinToString(", ", transform = Format::clock)) }
    val parsed = TimeSlots.parse(text)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Times of day") },
        supportingText = {
            Text(
                if (parsed ==
                    null
                ) {
                    "Enter complete times, like 7:00, 19:00."
                } else {
                    "Separate times with commas. Changes apply when saved."
                },
            )
        },
        isError = parsed == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    TextAction("Save times", onClick = { parsed?.let(onChange) }, enabled = parsed != null && parsed != current)
}
