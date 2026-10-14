package dev.fengine.wallpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.ChipRow
import dev.fengine.wallpaper.ui.design.CoverCard
import dev.fengine.wallpaper.ui.design.Eyebrow
import dev.fengine.wallpaper.ui.design.Headline
import dev.fengine.wallpaper.ui.design.NewCoverCard
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.RailPadding
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.TextAction

/**
 * The one main screen. Reads top to bottom like a magazine page: a headline sentence that says
 * what the app is doing, the groups as covers, the rhythm as chips, one primary action.
 */
@Composable
fun HomeScreen(
    settings: Settings,
    onOpenGroup: (String) -> Unit,
    onNewGroup: (described: Boolean) -> Unit,
    onSetMode: (Mode) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetTimes: (List<Int>) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onChangeNow: () -> Unit,
    dreamConfigured: Boolean = false,
    onOpenDreamSettings: () -> Unit = {},
    motion: Pair<Boolean, Boolean> = false to false,
    onTurnOnMotion: () -> Unit = {},
) {
    var chooser by remember { mutableStateOf(false) }
    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("New group") },
            text = { Text("Pick photos yourself, or describe what you want and let the phone find them.") },
            confirmButton = { TextAction("Describe it") { chooser = false; onNewGroup(true) } },
            dismissButton = { TextAction("Pick photos") { chooser = false; onNewGroup(false) } },
        )
    }
    Page {
        Eyebrow("Feels Engine")
        Spacer(Modifier.height(Space.sm))
        Headline(Format.status(settings), caption = settings.lastResult.ifBlank { null })
        Spacer(Modifier.height(Space.section))

        Section("Groups") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.md), contentPadding = RailPadding) {
                items(settings.groups.size) { i ->
                    val g = settings.groups[i]
                    CoverCard(
                        title = g.name,
                        caption = coverCaption(g),
                        coverUri = g.cover,
                        badge = roleOf(settings, g),
                        onClick = { onOpenGroup(g.id) },
                    )
                }
                item { NewCoverCard("New group") { chooser = true } }
            }
        }

        Section("Motion") {
            val (home, lock) = motion
            Caption(
                when {
                    home && lock -> "On. Photos cross-fade, drift with the home screen and settle on the lock screen."
                    home -> "On for the home screen. The lock screen keeps the still photo."
                    else -> "Off. Turn it on and photos cross-fade and move with the home screen, at no cost while nothing moves."
                },
            )
            TextAction(if (home) "Motion settings" else "Turn on motion", onTurnOnMotion)
        }
        Spacer(Modifier.height(Space.section))

        Section("Screen saver") {
            val dream = settings.dreamGroup ?: settings.homeGroup
            Caption(
                when {
                    dream == null -> "Give a group to a screen and it plays while the phone charges."
                    dreamConfigured -> "${dream.name} plays while the phone charges."
                    else -> "${dream.name} is ready to play while the phone charges. Choose Feels Engine photos under Screen saver in Settings."
                },
            )
            TextAction(if (dreamConfigured) "Screen saver settings" else "Set up in Settings", onOpenDreamSettings)
        }
        Spacer(Modifier.height(Space.section))

        Section("Rhythm") {
            ChipRow(
                options = listOf(Mode.INTERVAL to "Every so often", Mode.TIME_OF_DAY to "At set times"),
                selected = settings.mode,
                onSelect = onSetMode,
            )
            Spacer(Modifier.height(Space.md))
            when (settings.mode) {
                Mode.INTERVAL -> ChipRow(
                    options = listOf(15 to "15 min", 60 to "Hour", 180 to "3 hours", 1440 to "Day"),
                    selected = settings.intervalMinutes,
                    onSelect = onSetInterval,
                )
                Mode.TIME_OF_DAY -> TimesEditor(settings.timesOfDay, onSetTimes)
            }
            Spacer(Modifier.height(Space.lg))
            ToggleRow("Shuffle", settings.shuffle, onSetShuffle)
        }

        HorizontalDivider()
        Spacer(Modifier.height(Space.lg))
        ToggleRow(if (settings.enabled) "Rotation on" else "Rotation off", settings.enabled, onSetEnabled, enabled = settings.canRotate)
        Spacer(Modifier.height(Space.lg))
        Button(onClick = onChangeNow, enabled = settings.canRotate, modifier = Modifier.fillMaxWidth()) {
            Text("Change now")
        }
    }
}

private fun coverCaption(g: Group): String = when {
    g.isDescribed && g.allPhotos.isEmpty() -> "Looks for: ${g.rule?.ifBlank { "..." }}"
    g.isDescribed -> "${photos(g.allPhotos.size)}, updates itself"
    g.photos.isEmpty() -> "No photos yet"
    else -> photos(g.photos.size)
}

private fun roleOf(s: Settings, g: Group): String? = when {
    s.homeGroupId == g.id && s.lockGroupId == g.id -> "Both screens"
    s.homeGroupId == g.id -> "Home"
    s.lockGroupId == g.id -> "Lock"
    else -> null
}

@Composable
private fun TimesEditor(current: List<Int>, onChange: (List<Int>) -> Unit) {
    var text by remember(current) { mutableStateOf(current.joinToString(", ", transform = Format::clock)) }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v
            val parsed = v.split(',').mapNotNull { part ->
                val (h, m) = part.trim().split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val hh = h.toIntOrNull() ?: return@mapNotNull null
                val mm = m.toIntOrNull() ?: return@mapNotNull null
                if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
            }
            if (parsed.isNotEmpty()) onChange(parsed)
        },
        label = { Text("Times, like 7:00, 19:00") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
