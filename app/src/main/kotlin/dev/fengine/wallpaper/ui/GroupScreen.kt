package dev.fengine.wallpaper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
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
    onAddToPool: () -> Unit = {},
    onRemoveFromPool: (String) -> Unit = {},
    onExcludePhoto: (String) -> Unit = {},
    pickerMax: Int = 100,
    scanning: Boolean = false,
    indexing: Boolean = false,
    indexProgress: Pair<Int, Int>? = null,
    persistedGrants: Int = 0,
    aiStatus: String? = null,
    aiReadiness: AiReadiness? = null,
    onAiAction: (AiReadiness.Action) -> Unit = {},
    onSetCover: (String) -> Unit = {},
    onSetDream: (Boolean) -> Unit = {},
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

    Page {
        ActionBar("Done" to onBack, "Delete group" to onDelete)
        Spacer(Modifier.height(Space.sm))
        BasicTextField(
            value = name,
            onValueChange = { name = it; onRename(it); saved() },
            textStyle = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
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
        }

        val shown = group.allPhotos
        Section(
            eyebrow = if (shown.isEmpty()) "Photos" else photos(shown.size),
            trailing = { FilledTonalButton(onClick = onAddPhotos) { Text("Add photos") } },
        ) {
            if (shown.isEmpty()) {
                BodyMuted(
                    if (group.isDescribed) "Found photos will appear here. You can also add specific photos by hand."
                    else "Pick a few photos you would be happy to see all day. Portraits with the subject low in the frame leave room for the clock.",
                )
            } else {
                val found = group.matched.toSet()
                val cover = group.cover
                Caption("Tap a photo to make it the cover on the home screen.")
                Spacer(Modifier.height(Space.sm))
                PhotoGrid(
                    uris = shown,
                    badge = { uri -> when { uri == cover -> "Cover"; uri in found -> "Found"; else -> null } },
                    onRemove = { uri -> if (uri in found) onExcludePhoto(uri) else onRemovePhoto(uri) },
                    onClick = { uri -> onSetCover(uri); saved() },
                )
                if (shown.size > PhotoGridLimit) Caption("And ${"%,d".format(shown.size - PhotoGridLimit)} more. The rotation uses all of them.")
            }
        }

        if (persistedGrants >= MainViewModel.GRANT_WARN_AT) {
            Spacer(Modifier.height(Space.md))
            StatusCard(
                title = "Close to Android's limit for picked photos",
                body = "Android lets an app keep access to ${MainViewModel.GRANT_CEILING} picked photos at most, and quietly drops the oldest after that. " +
                    "This app holds ${"%,d".format(persistedGrants)}. For a large collection, keep the photos on this phone instead: a described group looks through all of them.",
                tone = MaterialTheme.colorScheme.errorContainer,
            )
        }

        if (group.isDescribed) {
            Section("Looks for") {
                var rule by remember(group.id) { mutableStateOf(group.rule.orEmpty()) }
                val commitRule = { if (rule.trim() != group.rule) { onSetRule(rule.trim()); saved() } }
                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    placeholder = { Text("my dog at the beach, sunsets, the kids playing") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitRule() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (!it.isFocused) commitRule() },
                )
                Spacer(Modifier.height(Space.md))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    FilledTonalButton(onClick = { commitRule(); onFindPhotos() }, enabled = rule.isNotBlank() && !scanning) {
                        Text(if (scanning) "Looking..." else "Find photos")
                    }
                    if (scanning) CircularProgressIndicator(modifier = Modifier.size(Size.progress), strokeWidth = Size.progressStroke)
                }
                Spacer(Modifier.height(Space.sm))
                Caption(aiStatus ?: group.scanNote.ifBlank { null } ?: "Runs on this phone only. Nothing leaves the device.")
                indexProgress?.let { (done, total) ->
                    if (total > 0) Caption(
                        when {
                            done < total -> "Labeled ${"%,d".format(done)} of ${"%,d".format(total)} photos on this phone" + (if (indexing) ", still going." else ".")
                            else -> "All ${"%,d".format(total)} photos on this phone are labeled; new ones get labeled twice a day."
                        },
                    )
                }
                Spacer(Modifier.height(Space.lg))
                // Where it looks: one caption, not a card. The scan note explains the cloud when the
                // library turns out small; this line states the scope and offers the picker. Added photos
                // stay visible as a small grid so a pick never seems to vanish into a count.
                Caption(
                    if (group.pool.isEmpty()) "Looks through the photos on this phone. If the picker shows only this phone's photos, sign in to Google Photos and turn on cloud media in the picker's settings."
                    else "Looks through the photos on this phone and these ${group.pool.size} you added. Matches show above with a Found badge.",
                )
                TextAction(if (group.pool.isEmpty()) "Add from Google Photos" else "Add more (up to $pickerMax at a time)", onAddToPool)
                if (group.pool.isNotEmpty()) PhotoGrid(group.pool, onRemove = onRemoveFromPool)
                aiReadiness?.let {
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
