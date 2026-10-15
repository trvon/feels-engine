package dev.fengine.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.fengine.wallpaper.ui.design.Badge
import dev.fengine.wallpaper.ui.design.PhotoImage
import dev.fengine.wallpaper.ui.design.Radius
import dev.fengine.wallpaper.ui.design.Size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
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
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
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
import dev.fengine.wallpaper.ui.design.Sheet
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.TextAction
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper

/**
 * The one main screen. Reads top to bottom like a magazine page: a headline sentence that says
 * what the app is doing, the groups as covers, the rhythm as chips, one primary action.
 */
@Composable
fun HomeScreen(
    settings: Settings,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onSetMode: (Mode) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetTimes: (List<Int>) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onChangeNow: () -> Unit,
    onSetActive: (String) -> Unit = {},
    dreamConfigured: Boolean = false,
    onOpenDreamSettings: () -> Unit = {},
    motion: LiveWallpaper.State = LiveWallpaper.State(false, false, false, false),
    onTurnOnMotion: () -> Unit = {},
    onSetStill: () -> Unit = {},
    hasCoverScreen: Boolean = false,
    onAssignScreen: (Screen, String?) -> Unit = { _, _ -> },
    onSetDreamWirelessOnly: (Boolean) -> Unit = {},
    diagnostics: String? = null,
    onExportLog: () -> Unit = {},
) {
    var choosingFor by remember { mutableStateOf<Screen?>(null) }
    choosingFor?.let { screen ->
        ScreenPickerSheet(
            screen = screen,
            groups = settings.groups,
            current = Screens.assigned(settings, screen),
            onPick = { id -> choosingFor = null; onAssignScreen(screen, id) },
            onDismiss = { choosingFor = null },
        )
    }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        GroupPickerSheet(
            groups = settings.groups.filter { it.allPhotos.isNotEmpty() },
            activeId = settings.homeGroupId.takeIf { it == settings.lockGroupId },
            onPick = { picking = false; onSetActive(it) },
            onDismiss = { picking = false },
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
                item { NewCoverCard("New group", onNewGroup) }
            }
        }

        Section("Look") {
            // A foldable can have the engine on one panel only: One UI's chooser puts it on whichever
            // panel is active when the user taps Set. Say which, so a folded choice is not a mystery.
            val home = motion.innerHome || motion.coverHome
            val lock = motion.innerLock || motion.coverLock
            val coverOnly = motion.coverHome && !motion.innerHome
            val innerOnly = motion.innerHome && !motion.coverHome
            ChipRow(
                options = listOf(Look.STILL to "Still", Look.MOTION to "Motion"),
                selected = if (home) Look.MOTION else Look.STILL,
                onSelect = { if (it == Look.MOTION) onTurnOnMotion() else onSetStill() },
            )
            Spacer(Modifier.height(Space.sm))
            Caption(
                when {
                    coverOnly -> "Motion is on the cover screen only. Open the phone and choose Motion again to put it on the inner screen too."
                    home && lock -> "Photos cross-fade and drift with the home screen, and settle on the lock screen." +
                        (if (innerOnly) " The cover screen stays still; choose Motion while closed to change that." else "") +
                        " Costs nothing while nothing moves."
                    home -> "Photos cross-fade and drift with the home screen. The lock screen keeps the still photo." +
                        (if (innerOnly) " The cover screen stays still; choose Motion while closed to change that." else "")
                    else -> "Each photo is set once and stays put. Motion cross-fades between photos and moves them with the home screen."
                },
            )
        }

        Section("Screens") {
            Caption("Each screen can show its own group. Tap one to change it.")
            Spacer(Modifier.height(Space.sm))
            val screens = buildList {
                add(Screen.HOME); add(Screen.LOCK)
                if (hasCoverScreen) add(Screen.COVER)
                add(Screen.SCREEN_SAVER)
            }
            screens.forEach { screen ->
                ScreenRow(screenLabel(screen), screenValue(settings, screen)) { choosingFor = screen }
            }
        }

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
            Spacer(Modifier.height(Space.sm))
            ToggleRow("Wireless charging or dock only", settings.dreamWirelessOnly, onSetDreamWirelessOnly)
            Caption(
                if (settings.dreamWirelessOnly) "A cable, like a car charger, does not start it. It never plays while the phone is in car mode."
                else "Plays on any charger. It never plays while the phone is in car mode; turn this on if it starts in the car anyway.",
            )
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
        Button(onClick = { picking = true }, enabled = settings.groups.any { it.allPhotos.isNotEmpty() }, modifier = Modifier.fillMaxWidth()) {
            Text("Set active")
        }
        Spacer(Modifier.height(Space.sm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextAction("Next photo", onChangeNow)
        }
        Spacer(Modifier.height(Space.section))
        Section("This week") {
            // The local ledger: stays on the phone; export writes a file the owner chooses.
            Caption(diagnostics ?: "Nothing recorded yet. Changes, motion and the screen saver are noted here, on this phone only.")
            if (diagnostics != null) TextAction("Export log", onExportLog)
        }
    }
}

private enum class Look { STILL, MOTION }

/** Pick the group that both screens show from now on. Its cover goes up right away. */
@Composable
private fun GroupPickerSheet(groups: List<Group>, activeId: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss = onDismiss) {
            Eyebrow("Set active")
            Spacer(Modifier.height(Space.sm))
            Text("Both screens show this group, starting with its cover.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Space.lg))
            groups.forEach { g ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(g.id) }
                        .padding(vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(Size.sheetThumb)
                            .clip(RoundedCornerShape(Radius.tile))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) { g.cover?.let { PhotoImage(it, Modifier.fillMaxSize(), contentDescription = null) } }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium)
                        Caption(coverCaption(g))
                    }
                    if (g.id == activeId) Badge("Active")
                }
            }
    }
}

private fun coverCaption(g: Group): String = when {
    g.hasDescription && g.allPhotos.isEmpty() -> "Looks for: ${g.rule}"
    g.hasDescription -> "${photos(g.allPhotos.size)}, finds more"
    g.allPhotos.isEmpty() -> "No photos yet"
    else -> photos(g.allPhotos.size)
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

private fun screenLabel(screen: Screen) = when (screen) {
    Screen.HOME -> "Home screen"
    Screen.LOCK -> "Lock screen"
    Screen.COVER -> "Cover screen"
    Screen.SCREEN_SAVER -> "Screen saver"
}

/** What the row says: the group, or what happens when the screen has none of its own. */
private fun screenValue(s: Settings, screen: Screen): String {
    Screens.assigned(s, screen)?.let { id -> return s.group(id)?.name ?: "Not set" }
    return when (screen) {
        Screen.HOME, Screen.LOCK -> "Left alone"
        Screen.COVER -> "Same as home"
        Screen.SCREEN_SAVER -> Screens.effective(s, screen)?.let { "Same as home (${it.name})" } ?: "Nothing to play"
    }
}

@Composable
private fun ScreenRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** One screen's choice: any group, or none (home and lock left alone; cover and screen saver follow home). */
@Composable
private fun ScreenPickerSheet(screen: Screen, groups: List<Group>, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss = onDismiss) {
        Eyebrow(screenLabel(screen))
        Spacer(Modifier.height(Space.lg))
        val none = when (screen) {
            Screen.HOME, Screen.LOCK -> "Leave alone"
            Screen.COVER, Screen.SCREEN_SAVER -> "Same as home"
        }
        PickerRow(none, null, selected = current == null) { onPick(null) }
        groups.forEach { g -> PickerRow(g.name, g.cover, selected = g.id == current, caption = coverCaption(g)) { onPick(g.id) } }
    }
}

@Composable
private fun PickerRow(title: String, coverUri: String?, selected: Boolean, caption: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier = Modifier
                .size(Size.sheetThumb)
                .clip(RoundedCornerShape(Radius.tile))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) { coverUri?.let { PhotoImage(it, Modifier.fillMaxSize(), contentDescription = null) } }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            caption?.let { Caption(it) }
        }
        if (selected) Badge("Selected")
    }
}
