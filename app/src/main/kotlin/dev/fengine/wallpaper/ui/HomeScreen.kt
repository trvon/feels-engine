package dev.fengine.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.ui.design.Badge
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.CoverCard
import dev.fengine.wallpaper.ui.design.Eyebrow
import dev.fengine.wallpaper.ui.design.Headline
import dev.fengine.wallpaper.ui.design.NewCoverCard
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.PhotoImage
import dev.fengine.wallpaper.ui.design.Radius
import dev.fengine.wallpaper.ui.design.RailPadding
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Sheet
import dev.fengine.wallpaper.ui.design.Size
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.TextAction

/**
 * The one main screen. Reads top to bottom like a magazine page: a headline sentence that says what
 * the app is doing, the groups as covers, which group each screen shows (the only place that is
 * chosen) and one primary action: Start, then Next photo.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    settings: Settings,
    onOpenGroup: (String) -> Unit,
    onNewGroup: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onChangeNow: () -> Unit,
    /** Turns rotation on and puts a photo up now: the one button before anything runs. */
    onStart: () -> Unit = {},
    hasCoverScreen: Boolean = false,
    onAssignScreen: (Screen, String?) -> Unit = { _, _ -> },
    diagnostics: String? = null,
    onExportLog: () -> Unit = {},
    onTurnOnMotion: () -> Unit = {},
    onDismissMotionLost: () -> Unit = {},
    orphanPhotos: Int = 0,
    onPutBackPhotos: () -> Unit = {},
    onLetPhotosGo: () -> Unit = {},
    health: dev.fengine.wallpaper.data.Health.Line? = null,
    onHealthFix: (dev.fengine.wallpaper.data.Health.Line) -> Unit = {},
    onDismissMotionOffer: () -> Unit = {},
) {
    var choosingFor by remember { mutableStateOf<Screen?>(null) }
    choosingFor?.let { screen ->
        ScreenPickerSheet(
            screen = screen,
            groups = settings.groups,
            current = Screens.assigned(settings, screen),
            onPick = { id ->
                choosingFor = null
                onAssignScreen(screen, id)
            },
            onDismiss = { choosingFor = null },
        )
    }
    Page {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow("Feels Engine", Modifier.weight(1f))
            TextAction("Settings", onOpenSettings)
        }
        Spacer(Modifier.height(Space.sm))
        val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        Headline(
            Format.status(settings, is24),
            caption = Format.lastResult(settings, System.currentTimeMillis(), is24),
        )
        // Only when something is wrong: the worst single fact and its one fix (Health).
        health?.let { line ->
            Spacer(Modifier.height(Space.md))
            Caption(line.text)
            TextAction(
                when (line.fix) {
                    dev.fengine.wallpaper.data.Health.Fix.APP_SLEEP -> "Open app settings"
                    dev.fengine.wallpaper.data.Health.Fix.GROUP -> "Open the group"
                }
            ) {
                onHealthFix(line)
            }
        }
        // Once, after the first change: Motion is the app's headline look, and lives in Settings.
        if (!settings.motionOffered && !settings.motionOn && settings.lastRotationAt > 0) {
            Spacer(Modifier.height(Space.md))
            Caption("Make it move: Motion pans each photo as you swipe and fades between them.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                TextAction("Try Motion") {
                    onDismissMotionOffer()
                    onTurnOnMotion()
                }
                TextAction("Not now", onDismissMotionOffer)
            }
        }
        if (settings.motionLostAt > 0 && !settings.motionOn) {
            Spacer(Modifier.height(Space.md))
            Caption(Format.motionLost(settings.motionLostAt))
            // Wraps at large text sizes instead of squeezing Dismiss.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                TextAction("Turn Motion back on", onTurnOnMotion)
                TextAction("Dismiss", onDismissMotionLost)
            }
        }
        if (orphanPhotos > 0) {
            Spacer(Modifier.height(Space.md))
            Caption(Format.orphanPhotos(orphanPhotos))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                TextAction("Put them back", onPutBackPhotos)
                TextAction("Let them go", onLetPhotosGo)
            }
        }
        Spacer(Modifier.height(Space.section))

        Section("Groups") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.md),
                contentPadding = RailPadding,
            ) {
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

        Section("Screens") {
            Caption("Each screen can show its own group. Tap one to change it.")
            Spacer(Modifier.height(Space.sm))
            val screens = buildList {
                add(Screen.HOME)
                add(Screen.LOCK)
                if (hasCoverScreen) add(Screen.COVER)
                add(Screen.SCREEN_SAVER)
            }
            screens.forEach { screen ->
                ScreenRow(screenLabel(screen), screenValue(settings, screen)) {
                    choosingFor = screen
                }
            }
        }

        Section("Rotation", trailing = { TextAction("Schedule", onOpenSettings) }) {
            Caption(
                "${Format.schedule(settings, is24)} · ${if (settings.shuffle) "Shuffled" else "In group order"}"
            )
            Spacer(Modifier.height(Space.sm))
            ToggleRow(
                if (settings.enabled) "Rotation on" else "Rotation off",
                settings.enabled,
                onSetEnabled,
                enabled = settings.canRotate,
            )
            if (!settings.canRotate)
                Caption("Assign a group with photos to Home or Lock to start rotation.")
        }

        HorizontalDivider()
        Spacer(Modifier.height(Space.lg))
        // One button, the next thing to do: start the rotation, then change the photo. Which group
        // shows where is chosen above, under Screens, and nowhere else.
        Button(
            onClick = if (settings.enabled) onChangeNow else onStart,
            enabled = settings.canRotate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (settings.enabled) "Next photo" else "Start")
        }
        Spacer(Modifier.height(Space.section))
        Section("This week") {
            // The local ledger: stays on the phone; export writes a file the owner chooses.
            Caption(
                diagnostics
                    ?: "Nothing recorded yet. Changes, motion and the screen saver are noted here, on this phone only."
            )
            if (diagnostics != null) TextAction("Export log", onExportLog)
        }
    }
}

private fun coverCaption(g: Group): String =
    when {
        g.hasDescription && g.allPhotos.isEmpty() -> "Looks for: ${g.rule}"
        g.hasDescription -> "${photos(g.allPhotos.size)}, finds more"
        g.allPhotos.isEmpty() -> "No photos yet"
        else -> photos(g.allPhotos.size)
    }

private fun roleOf(
    s: Settings,
    g: Group,
): String? =
    when {
        s.homeGroupId == g.id && s.lockGroupId == g.id -> "Both screens"
        s.homeGroupId == g.id -> "Home"
        s.lockGroupId == g.id -> "Lock"
        else -> null
    }

@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) = dev.fengine.wallpaper.ui.design.SwitchRow(label, checked, onChange, enabled)

private fun screenLabel(screen: Screen) =
    when (screen) {
        Screen.HOME -> "Home screen"
        Screen.LOCK -> "Lock screen"
        Screen.COVER -> "Cover screen"
        Screen.SCREEN_SAVER -> "Screen saver"
    }

/** What the row says: the group, or what happens when the screen has none of its own. */
private fun screenValue(
    s: Settings,
    screen: Screen,
): String {
    Screens.assigned(s, screen)?.let { id ->
        return s.group(id)?.name ?: "Not set"
    }
    return when (screen) {
        Screen.HOME,
        Screen.LOCK -> "Left alone"
        Screen.COVER -> "Same as home"
        Screen.SCREEN_SAVER ->
            Screens.effective(s, screen)?.let { "Same as home (${it.name})" } ?: "Nothing to play"
    }
}

@Composable
private fun ScreenRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = Size.touchTarget)
                .clickable(onClickLabel = "Choose group", onClick = onClick)
                .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = Space.md),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * One screen's choice: any group, or none (home and lock left alone; cover and screen saver follow
 * home).
 */
@Composable
private fun ScreenPickerSheet(
    screen: Screen,
    groups: List<Group>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        Eyebrow(screenLabel(screen))
        Spacer(Modifier.height(Space.lg))
        val none =
            when (screen) {
                Screen.HOME,
                Screen.LOCK -> "Leave alone"
                Screen.COVER,
                Screen.SCREEN_SAVER -> "Same as home"
            }
        PickerRow(none, null, selected = current == null) { onPick(null) }
        groups.forEach { g ->
            PickerRow(g.name, g.cover, selected = g.id == current, caption = coverCaption(g)) {
                onPick(g.id)
            }
        }
    }
}

@Composable
private fun PickerRow(
    title: String,
    coverUri: String?,
    selected: Boolean,
    caption: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            modifier =
                Modifier.size(Size.sheetThumb)
                    .clip(RoundedCornerShape(Radius.tile))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            coverUri?.let { PhotoImage(it, Modifier.fillMaxSize(), contentDescription = null) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            caption?.let { Caption(it) }
        }
        if (selected) Badge("Selected")
    }
}
