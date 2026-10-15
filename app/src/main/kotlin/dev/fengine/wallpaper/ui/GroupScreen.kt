package dev.fengine.wallpaper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.fengine.wallpaper.ui.design.PhotoImage
import dev.fengine.wallpaper.ui.design.Radius
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.ui.design.ActionBar
import dev.fengine.wallpaper.ui.design.BodyMuted
import dev.fengine.wallpaper.ui.design.Caption
import dev.fengine.wallpaper.ui.design.ChipRow
import dev.fengine.wallpaper.ui.design.Page
import dev.fengine.wallpaper.ui.design.PhotoGrid
import dev.fengine.wallpaper.ui.design.PhotoGridLimit
import dev.fengine.wallpaper.ui.design.Section
import dev.fengine.wallpaper.ui.design.Sheet
import dev.fengine.wallpaper.ui.design.Size
import dev.fengine.wallpaper.ui.design.Space
import dev.fengine.wallpaper.ui.design.StatusCard
import dev.fengine.wallpaper.ui.design.SwitchRow
import dev.fengine.wallpaper.ui.design.TextAction

private enum class Screens { BOTH, HOME, LOCK, NEITHER }

/**
 * One group. Top to bottom: actions, the name as the headline, which screens it feeds, then one
 * photo grid that holds everything the group will show, with found photos badged. Described
 * groups end with what they look for, where they look (one line, not a card) and whether
 * on-device AI is ready. Photos come before the machinery because they are what the owner came to see.
 */
@Composable
fun GroupScreen(
    settings: Settings,
    group: Group,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    onAssign: (home: Boolean, lock: Boolean) -> Unit,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onDelete: () -> Unit,
    onSetRule: (String) -> Unit = {},
    onFindPhotos: () -> Unit = {},
    /** True once the app may read the library; false means [onFindPhotos] will ask the system first. */
    hasLibraryAccess: Boolean = true,
    onExcludePhoto: (String) -> Unit = {},
    scanning: Boolean = false,
    indexing: Boolean = false,
    indexProgress: Pair<Int, Int>? = null,
    persistedGrants: Int = 0,
    aiStatus: String? = null,
    aiReadiness: AiReadiness? = null,
    onAiAction: (AiReadiness.Action) -> Unit = {},
    onSetCover: (String) -> Unit = {},
    onSetDream: (Boolean) -> Unit = {},
    onShowNow: (String) -> Unit = {},
    hasCoverScreen: Boolean = false,
    onSetCoverScreen: (Boolean) -> Unit = {},
) {
    val feedsHome = settings.homeGroupId == group.id
    val feedsLock = settings.lockGroupId == group.id
    var name by remember(group.id) { mutableStateOf(group.name) }

    // Everything on this screen saves the moment it changes. Say so, and flash "Saved" after each change
    // so the owner never wonders whether a Done or Save tap is still needed.
    var savedTick by remember(group.id) { mutableStateOf(0) }
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(savedTick) {
        if (savedTick > 0) { showSaved = true; delay(1_500); showSaved = false }
    }
    LaunchedEffect(group) { if (group != settings.group(group.id)) savedTick++ }
    val saved = { savedTick++ }
    var chosen by remember(group.id) { mutableStateOf<String?>(null) }
    // The one place the app asks for library access: a described group about to look through the
    // whole library. Said in plain words first, with the picker offered as the other way, so the
    // system dialog never arrives unexplained (Play photo-permissions policy, declaration on file).
    var askLibrary by remember { mutableStateOf(false) }
    if (askLibrary) {
        AlertDialog(
            onDismissRequest = { askLibrary = false },
            title = { Text("Look through all photos on this phone?") },
            text = {
                Text(
                    "Feels Engine labels the photos on this phone, on the phone, to find the ones that match " +
                        "your description, and keeps looking as new photos arrive. Nothing leaves the device. " +
                        "If you would rather choose the photos yourself, add them with the picker instead.",
                )
            },
            confirmButton = { TextAction("Allow") { askLibrary = false; onFindPhotos() } },
            dismissButton = { TextAction("Add photos instead") { askLibrary = false; onAddPhotos() } },
        )
    }
    chosen?.let { uri ->
        PhotoSheet(
            uri = uri,
            isCover = uri == group.cover,
            feeds = feedsHome || feedsLock,
            onShowNow = { onShowNow(uri); chosen = null },
            onMakeCover = { onSetCover(uri); saved(); chosen = null },
            onDismiss = { chosen = null },
        )
    }

    val focus = LocalFocusManager.current
    Page {
        ActionBar("Done" to { focus.clearFocus(); onBack() }, "Delete group" to onDelete)
        Spacer(Modifier.height(Space.sm))
        BasicTextField(
            value = name,
            onValueChange = { name = it; onRename(it); saved() },
            textStyle = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Caption(if (showSaved) "Saved" else "Tap the name to rename. Changes save as you make them.")
        Spacer(Modifier.height(Space.section))

        Section("Shows on") {
            ChipRow(
                options = listOf(Screens.BOTH to "Both screens", Screens.HOME to "Home", Screens.LOCK to "Lock", Screens.NEITHER to "Neither"),
                selected = when {
                    feedsHome && feedsLock -> Screens.BOTH
                    feedsHome -> Screens.HOME
                    feedsLock -> Screens.LOCK
                    else -> Screens.NEITHER
                },
                onSelect = { onAssign(it == Screens.BOTH || it == Screens.HOME, it == Screens.BOTH || it == Screens.LOCK) },
            )
            Spacer(Modifier.height(Space.md))
            SwitchRow("Screen saver", checked = settings.dreamGroupId == group.id, onChange = { onSetDream(it); saved() })
            Caption(if (settings.dreamGroupId == group.id) "Plays while the phone charges, once Feels Engine photos is chosen under Screen saver in Settings." else "Off: the home screen's group plays instead.")
            if (hasCoverScreen) {
                Spacer(Modifier.height(Space.md))
                SwitchRow("Cover screen", checked = settings.coverGroupId == group.id, onChange = { onSetCoverScreen(it); saved() })
                Caption(
                    if (settings.coverGroupId == group.id) "The cover screen shows this group; the inner screen shows its own."
                    else "Off: the cover screen shows the same photo as the inner screen.",
                )
            }
        }

        val shown = group.allPhotos
        Section(
            eyebrow = if (shown.isEmpty()) "Photos" else photos(shown.size),
            trailing = { FilledTonalButton(onClick = onAddPhotos) { Text("Add photos") } },
        ) {
            if (shown.isEmpty()) {
                BodyMuted(
                    "Add photos you would be happy to see all day, or describe below what the phone should find. Portraits with the subject low in the frame leave room for the clock.",
                )
            } else {
                val found = group.matched.toSet()
                val cover = group.cover
                Caption("Tap a photo to show it now or make it the cover.")
                Spacer(Modifier.height(Space.sm))
                PhotoGrid(
                    uris = shown,
                    badge = { uri -> when { uri == cover -> "Cover"; uri in found -> "Found"; else -> null } },
                    onRemove = { uri -> if (uri in found) onExcludePhoto(uri) else onRemovePhoto(uri) },
                    onClick = { uri -> chosen = uri },
                )
                if (shown.size > PhotoGridLimit) Caption("And ${"%,d".format(shown.size - PhotoGridLimit)} more. The rotation uses all of them.")
            }
        }

        if (persistedGrants >= MainViewModel.GRANT_WARN_AT) {
            Spacer(Modifier.height(Space.md))
            StatusCard(
                title = "Close to Android's limit for picked photos",
                body = "Android lets an app keep access to ${MainViewModel.GRANT_CEILING} picked photos at most, and quietly drops the oldest after that. " +
                    "This app holds ${"%,d".format(persistedGrants)}. For a large collection, keep the photos on this phone and describe them instead: the description looks through all of them.",
                tone = MaterialTheme.colorScheme.errorContainer,
            )
        }

        run {
            Section("Also find") {
                var rule by remember(group.id) { mutableStateOf(group.rule.orEmpty()) }
                val commitRule = { if (rule.trim() != group.rule) { onSetRule(rule.trim()); saved() } }
                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    placeholder = { Text("my dog at the beach, sunsets, the kids playing") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitRule(); focus.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commitRule() },
                )
                Spacer(Modifier.height(Space.md))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    FilledTonalButton(onClick = { commitRule(); if (hasLibraryAccess) onFindPhotos() else askLibrary = true }, enabled = rule.isNotBlank() && !scanning) {
                        Text(if (scanning) "Looking..." else "Find photos")
                    }
                    if (scanning) CircularProgressIndicator(modifier = Modifier.size(Size.progress), strokeWidth = Size.progressStroke)
                }
                Spacer(Modifier.height(Space.sm))
                Caption(aiStatus ?: group.scanNote.ifBlank { null } ?: "Describe what to look for and the phone adds matching photos to this group, now and as new ones arrive. Runs on this phone only.")
                if (group.hasDescription) indexProgress?.let { (done, total) ->
                    if (total > 0) Caption(
                        when {
                            done < total -> "Labeled ${"%,d".format(done)} of ${"%,d".format(total)} photos on this phone" + (if (indexing) ", still going." else ".")
                            else -> "All ${"%,d".format(total)} photos on this phone are labeled; new ones get labeled twice a day."
                        },
                    )
                }
                if (group.hasDescription) aiReadiness?.let {
                    Spacer(Modifier.height(Space.lg))
                    StatusCard(
                        title = it.headline,
                        body = it.detail,
                        tone = when (it.level) {
                            AiReadiness.Level.READY -> MaterialTheme.colorScheme.primaryContainer
                            AiReadiness.Level.DOWNLOADABLE, AiReadiness.Level.DOWNLOADING -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        action = it.action?.let { a ->
                            when (a) {
                                AiReadiness.Action.DOWNLOAD -> "Set up on-device AI"
                                AiReadiness.Action.OPEN_PLAY_STORE -> "Open AICore in Play Store"
                                AiReadiness.Action.CHECK_AGAIN -> "Check again"
                            } to { onAiAction(a) }
                        },
                    )
                }
            }
        }

    }
}

/** What one photo can do: go up on the screens now, or stand for the group on the home rail. */
@Composable
private fun PhotoSheet(uri: String, isCover: Boolean, feeds: Boolean, onShowNow: () -> Unit, onMakeCover: () -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss = onDismiss) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Size.sheetPreview)
                    .clip(RoundedCornerShape(Radius.card))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) { PhotoImage(uri, Modifier.fillMaxSize(), contentDescription = "Photo") }
            Spacer(Modifier.height(Space.lg))
            SheetAction(
                if (feeds) "Show now" else "Show now on both screens",
                if (feeds) "Goes up on the screens this group feeds. The rotation carries on from here." else "Makes this group active and puts this photo up.",
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
private fun SheetAction(title: String, caption: String, onClick: () -> Unit, enabled: Boolean = true) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Space.md),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Caption(caption)
    }
}
