package dev.fengine.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.ui.design.ActionBar
import dev.fengine.wallpaper.ui.design.BodyMuted
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.PhotoGrid
import dev.fengine.wallpaper.ui.design.PhotoGridLimit
import dev.fengine.wallpaper.ui.design.PhotoImage
import dev.fengine.wallpaper.ui.design.Radius
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Sheet
import dev.fengine.wallpaper.ui.design.Size
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.StatusCard
import dev.fengine.wallpaper.ui.design.SwitchRow
import dev.fengine.wallpaper.ui.design.TextAction
import dev.fengine.wallpaper.wallpaper.ClockLegibility
import kotlinx.coroutines.delay

/**
 * One group is its photos. Top to bottom: the name as the headline and one line saying where it
 * shows, the photo grid with Add photos, then what the phone should find, and Delete at the very
 * end. Which screen shows which group is chosen in one place only, Home's Screens; before, this
 * screen had its own chips and switches for the same choice (2026-11-20 review).
 */
@Composable
fun GroupScreen(
    settings: Settings,
    group: Group,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDelete: () -> Unit,
    onSetRule: (String) -> Unit = {},
    /** Opens the photo picker; what the user hands over is searched for the description. */
    onFindPhotos: () -> Unit = {},
    pickerMax: Int = 100,
    onExcludePhoto: (String) -> Unit = {},
    scanning: Boolean = false,
    indexing: Boolean = false,
    indexProgress: Pair<Int, Int>? = null,
    persistedGrants: Int = 0,
    aiStatus: String? = null,
    aiReadiness: AiReadiness? = null,
    onAiAction: (AiReadiness.Action) -> Unit = {},
    onSetCover: (String) -> Unit = {},
    onShowNow: (String) -> Unit = {},
    onSetSkipped: (String, Boolean) -> Unit = { _, _ -> },
    clockCheck: suspend (String) -> ClockLegibility.Verdict? = { null },
    onPickAgain: (String) -> Unit = {},
) {
    val feedsHome = settings.homeGroupId == group.id
    val feedsLock = settings.lockGroupId == group.id
    var name by remember(group.id) { mutableStateOf(group.name) }

    // Everything on this screen saves the moment it changes. Say so, and flash "Saved" after each
    // change
    // so the owner never wonders whether a Done or Save tap is still needed.
    var savedTick by remember(group.id) { mutableIntStateOf(0) }
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            showSaved = true
            delay(1_500)
            showSaved = false
        }
    }
    LaunchedEffect(group) { if (group != settings.group(group.id)) savedTick++ }
    val saved = { savedTick++ }
    var chosen by remember(group.id) { mutableStateOf<String?>(null) }
    chosen?.let { uri ->
        var verdict by remember(uri) { mutableStateOf<ClockLegibility.Verdict?>(null) }
        var checked by remember(uri) { mutableStateOf(false) }
        LaunchedEffect(uri) {
            verdict = clockCheck(uri)
            checked = true
        }
        PhotoSheet(
            uri = uri,
            isCover = uri == group.cover,
            feeds = feedsHome || feedsLock,
            skipped = group.isSkipped(uri),
            unreadable = group.isUnreadable(uri),
            clock =
                if (!checked) "Checking the area behind the clock..."
                else
                    when (verdict?.legible) {
                        true -> "The clock reads well on this photo."
                        false ->
                            "The area behind the clock is busy, so the time may be hard to read. You can keep the photo and leave it out of rotation."
                        null -> null
                    },
            onShowNow = {
                onShowNow(uri)
                chosen = null
            },
            onMakeCover = {
                onSetCover(uri)
                saved()
                chosen = null
            },
            onSetSkipped = {
                onSetSkipped(uri, it)
                saved()
            },
            onPickAgain = {
                onPickAgain(uri)
                chosen = null
            },
            onDismiss = { chosen = null },
        )
    }

    val focus = LocalFocusManager.current
    Page {
        ActionBar(
            "Done" to
                {
                    focus.clearFocus()
                    onBack()
                }
        )
        Spacer(Modifier.height(Space.sm))
        BasicTextField(
            value = name,
            onValueChange = {
                name = it
                onRename(it)
                saved()
            },
            textStyle =
                MaterialTheme.typography.displaySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Caption(
            if (showSaved) "Saved" else "Tap the name to rename. Changes save as you make them."
        )
        Spacer(Modifier.height(Space.sm))
        BodyMuted(showsOn(settings, group))
        Spacer(Modifier.height(Space.section))

        val shown = group.allPhotos
        Section(
            eyebrow = if (shown.isEmpty()) "Photos" else photos(shown.size),
            trailing = { FilledTonalButton(onClick = onAddPhotos) { Text("Add photos") } },
        ) {
            if (shown.isEmpty()) {
                BodyMuted(
                    "Add photos you would be happy to see all day, or describe below what the phone should find. Portraits with the subject low in the frame leave room for the clock."
                )
            } else {
                val found = group.matched.toSet()
                val cover = group.cover
                Caption("Tap a photo to show it now or make it the cover.")
                Spacer(Modifier.height(Space.sm))
                PhotoGrid(
                    uris = shown,
                    badge = { uri ->
                        when {
                            group.isUnreadable(uri) -> "Can't be read"
                            group.isSkipped(uri) -> "Skipped"
                            uri == cover -> "Cover"
                            uri in found -> "Found"
                            else -> null
                        }
                    },
                    onRemove = { uri ->
                        if (uri in found) onExcludePhoto(uri) else onRemovePhoto(uri)
                    },
                    onClick = { uri -> chosen = uri },
                )
                if (shown.size > PhotoGridLimit)
                    Caption(
                        "And ${"%,d".format(shown.size - PhotoGridLimit)} more. The rotation uses all of them."
                    )
            }
        }

        if (persistedGrants >= MainViewModel.GRANT_WARN_AT) {
            Spacer(Modifier.height(Space.md))
            StatusCard(
                title = "Close to Android's limit for picked photos",
                body =
                    "${"%,d".format(persistedGrants)} of ${MainViewModel.GRANT_CEILING} used. Android lets an app keep access to " +
                        "${MainViewModel.GRANT_CEILING} picked photos and quietly drops the oldest after that. The app keeps the " +
                        "photos your groups show at the front of the line and lets go of photos a description looked through " +
                        "but did not keep. Picked photos past the limit stop showing unless a copy is kept (Settings, Photos).",
                tone = MaterialTheme.colorScheme.errorContainer,
            )
        }

        run {
            Section("Also find") {
                var rule by remember(group.id) { mutableStateOf(group.rule.orEmpty()) }
                val commitRule = {
                    if (rule.trim() != group.rule) {
                        onSetRule(rule.trim())
                        saved()
                    }
                }
                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    placeholder = { Text("my dog at the beach, sunsets, the kids playing") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                commitRule()
                                focus.clearFocus()
                            }
                        ),
                    modifier =
                        Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitRule() },
                )
                Spacer(Modifier.height(Space.md))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    FilledTonalButton(
                        onClick = {
                            commitRule()
                            onFindPhotos()
                        },
                        enabled = rule.isNotBlank() && !scanning,
                    ) {
                        Text(
                            when {
                                scanning -> "Looking..."
                                group.pool.isEmpty() -> "Find photos"
                                else -> "Look through more"
                            }
                        )
                    }
                    if (scanning)
                        CircularProgressIndicator(
                            modifier = Modifier.size(Size.progress),
                            strokeWidth = Size.progressStroke,
                        )
                }
                Spacer(Modifier.height(Space.sm))
                // The scan result always shows; on-device AI speaks in its own card below.
                Caption(
                    group.scanNote.ifBlank { null }
                        ?: "Describe what to look for, then tap Find photos and choose photos to look through, up to $pickerMax at a time, from this phone or Google Photos. The phone keeps the ones that match, one per moment. Change the words any time: the photos you handed over are looked through again. Runs on this phone only."
                )
                // The phone only sees what it is handed: in 45 recent photos on a Fold 8 most were
                // screenshots and recipes, and Gemini Nano was right about every one (2026-11-20).
                // The picker's own Google Photos search finds the candidates; the phone then
                // judges.
                if (group.hasDescription)
                    Caption(
                        "Tip: in Google Photos, search for a person's name or a word like kids or beach, select the results, then Share › Feels Engine. Sharing has no limit on how many. The phone can only look through photos you hand it."
                    )
                if (group.hasDescription)
                    indexProgress?.let { (done, total) ->
                        if (total > 0)
                            Caption(
                                when {
                                    done < total ->
                                        "Labeled ${"%,d".format(done)} of ${"%,d".format(total)} photos on this phone" +
                                            (if (indexing) ", still going." else ".")
                                    else ->
                                        "All ${"%,d".format(total)} photos on this phone are labeled; new ones get labeled twice a day."
                                }
                            )
                    }
                if (group.hasDescription)
                    aiReadiness?.let {
                        Spacer(Modifier.height(Space.lg))
                        StatusCard(
                            title = it.headline,
                            body = aiStatus?.let { s -> "$s\n\n${it.detail}" } ?: it.detail,
                            tone =
                                when (it.level) {
                                    AiReadiness.Level.READY ->
                                        MaterialTheme.colorScheme.primaryContainer
                                    AiReadiness.Level.DOWNLOADABLE,
                                    AiReadiness.Level.DOWNLOADING ->
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            action =
                                it.action?.let { a ->
                                    when (a) {
                                        AiReadiness.Action.DOWNLOAD -> "Set up on-device AI"
                                        AiReadiness.Action.OPEN_PLAY_STORE ->
                                            "Open AICore in Play Store"
                                        AiReadiness.Action.CHECK_AGAIN -> "Check again"
                                    } to { onAiAction(a) }
                                },
                        )
                    }
            }
        }

        Spacer(Modifier.height(Space.section))
        TextAction("Delete group", onDelete)
    }
}

/**
 * Where this group shows, in one sentence, and where to change it: Home's Screens, the one place
 * screens are chosen.
 */
private fun showsOn(s: Settings, g: Group): String {
    val on =
        Screen.entries
            .filter { dev.fengine.wallpaper.data.Screens.assigned(s, it) == g.id }
            .map {
                when (it) {
                    Screen.HOME -> "home screen"
                    Screen.LOCK -> "lock screen"
                    Screen.COVER -> "cover screen"
                    Screen.SCREEN_SAVER -> "screen saver"
                }
            }
    return if (on.isEmpty()) "Not on any screen yet. Choose screens on Home, under Screens."
    else
        "Shows on the " +
            (if (on.size == 1) on[0] else on.dropLast(1).joinToString(", ") + " and " + on.last()) +
            ". Change this on Home, under Screens."
}

/** What one photo can do: go up on the screens now, or stand for the group on the home rail. */
@Composable
private fun PhotoSheet(
    uri: String,
    isCover: Boolean,
    feeds: Boolean,
    skipped: Boolean,
    unreadable: Boolean,
    clock: String?,
    onShowNow: () -> Unit,
    onMakeCover: () -> Unit,
    onSetSkipped: (Boolean) -> Unit,
    onPickAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(onDismiss = onDismiss) {
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .height(Size.sheetPreview)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PhotoImage(uri, Modifier.fillMaxSize(), contentDescription = "Photo")
        }
        if (unreadable) {
            Spacer(Modifier.height(Space.sm))
            Caption(
                "This photo could not be read: Google Photos may have freed up space, or it " +
                    "was deleted. It stays in the group and sits out until it reads again. Pick " +
                    "it again from Google Photos to bring it back in the same place."
            )
            SheetAction(
                "Pick it again",
                "Opens the photo picker. The photo you choose takes this one's place.",
                onPickAgain,
            )
        } else
            clock?.let {
                Spacer(Modifier.height(Space.sm))
                Caption(it)
            }
        Spacer(Modifier.height(Space.md))
        SwitchRow("Use in rotation", checked = !skipped, onChange = { onSetSkipped(!it) })
        if (!skipped)
            SheetAction(
                if (feeds) "Show now" else "Show now on both screens",
                if (feeds)
                    "Goes up on the screens this group feeds. The rotation carries on from here."
                else "Makes this group active and puts this photo up.",
                onShowNow,
            )
        SheetAction(
            if (isCover) "This is the cover" else "Make the cover",
            "The photo that stands for the group on the home rail, and the first one up when the group is set active.",
            onMakeCover,
            enabled = !isCover,
        )
    }
}

@Composable
private fun SheetAction(
    title: String,
    caption: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = Space.md)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Caption(caption)
    }
}
