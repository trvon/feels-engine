package dev.fengine.wallpaper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.ui.FengineTheme
import dev.fengine.wallpaper.ui.GroupScreen
import dev.fengine.wallpaper.ui.HomeScreen
import dev.fengine.wallpaper.ui.MainViewModel
import dev.fengine.wallpaper.ui.SettingsScreen
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep system-bar icons legible when the Compose theme follows the phone.
        enableEdgeToEdge()
        setContent {
            val s by viewModel.settings.collectAsStateWithLifecycle()
            FengineTheme(s.themeMode, s.colorSource) { FengineApp(viewModel) }
        }
    }
}

/** Home, groups and secondary Settings. Navigation state is local and survives recreation. */
@Composable
private fun FengineApp(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val openGroup = settings.group(openGroupId)

    val picker =
        rememberLauncherForActivityResult(PickMultipleVisualMedia(vm.pickerMax())) { uris ->
            openGroupId?.let { vm.addPhotos(it, uris) }
        }
    val context = LocalContext.current
    val screenSaverWeek by vm.screenSaverWeek.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    LaunchedEffect(openGroupId, settings.lastResult) {
        if (openGroupId == null) vm.refreshDiagnostics()
    }
    val exportLog =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            uri?.let(vm::exportLedger)
        }
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val indexing by vm.indexing.collectAsStateWithLifecycle()
    val persistedGrants by vm.persistedGrants.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()
    val aiStatus by vm.aiStatus.collectAsStateWithLifecycle()
    val aiReadiness by vm.aiReadiness.collectAsStateWithLifecycle()
    LaunchedEffect(openGroupId, openGroup?.hasDescription) {
        if (openGroup?.hasDescription == true) vm.refreshAi()
    }
    // Find photos hands the description a batch from the system picker; no broad photo permission.
    val findPicker =
        rememberLauncherForActivityResult(PickMultipleVisualMedia(vm.pickerMax())) { uris ->
            openGroupId?.let { vm.addToPool(it, uris) }
        }

    // Returning from Android's chooser or from elsewhere: Motion may have come back or gone.
    LifecycleResumeEffect(Unit) {
        vm.checkMotion()
        onPauseOrDispose {}
    }
    val turnOnMotion = {
        runCatching { context.startActivity(LiveWallpaper.chooserIntent(context)) }
            .onFailure {
                context.startActivity(
                    Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                )
            }
        Unit
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        var motion by remember { mutableStateOf(vm.motion()) }
        var dreamConfigured by remember { mutableStateOf(vm.dreamConfigured()) }
        // A chooser can change system state without emitting a DataStore update.
        LifecycleResumeEffect(Unit) {
            motion = vm.motion()
            dreamConfigured = vm.dreamConfigured()
            onPauseOrDispose {}
        }
        LaunchedEffect(settings) { motion = vm.motion() }
        SettingsScreen(
            settings = settings,
            onBack = { settingsOpen = false },
            onSetMode = vm::setMode,
            onSetInterval = vm::setIntervalMinutes,
            onSetTimes = vm::setTimesOfDay,
            onSetShuffle = vm::setShuffle,
            onSetStill = vm::setStill,
            onSetDreamWirelessOnly = vm::setDreamWirelessOnly,
            onSetThemeMode = vm::setThemeMode,
            onSetColorSource = vm::setColorSource,
            onSetBackdrop = vm::setBackdrop,
            onSetPhotoFit = vm::setPhotoFit,
            screenSaverWeek = screenSaverWeek,
            onSetTapToChange = vm::setTapToChange,
            onSetChangeOnTheme = vm::setChangeOnTheme,
            hasCoverScreen = vm.hasCoverScreen(),
            dreamConfigured = dreamConfigured,
            motion = motion,
            onTurnOnMotion = turnOnMotion,
            onOpenDreamSettings = {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS))
                }
                    .onFailure {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                        )
                    }
            },
        )
    } else if (openGroup != null) {
        BackHandler { openGroupId = null }
        GroupScreen(
            settings = settings,
            group = openGroup,
            onBack = { openGroupId = null },
            onRename = { vm.renameGroup(openGroup.id, it) },
            onAssign = { home, lock -> vm.assign(openGroup.id, home, lock) },
            onAddPhotos = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            onRemovePhoto = { vm.removePhoto(openGroup.id, it) },
            onDelete = {
                vm.deleteGroup(openGroup.id)
                openGroupId = null
            },
            onSetRule = { vm.setRule(openGroup.id, it) },
            onFindPhotos = { findPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            pickerMax = vm.pickerMax(),
            onExcludePhoto = { vm.excludePhoto(openGroup.id, it) },
            onSetCover = { vm.setCover(openGroup.id, it) },
            onSetDream = { vm.setDreamGroup(openGroup.id, it) },
            onShowNow = { uri ->
                // One rotation either way: making the group active already puts the tapped photo
                // up.
                if (settings.homeGroupId != openGroup.id && settings.lockGroupId != openGroup.id) {
                    vm.setActive(openGroup.id, uri)
                } else {
                    vm.showNow(openGroup.id, uri)
                }
            },
            onSetSkipped = { uri, skip -> vm.setSkipped(openGroup.id, uri, skip) },
            clockCheck = vm::clockCheck,
            hasCoverScreen = vm.hasCoverScreen(),
            onSetCoverScreen = { vm.setCoverGroup(openGroup.id, it) },
            scanning = scanning,
            indexing = indexing,
            persistedGrants = persistedGrants,
            indexProgress = indexProgress,
            aiStatus = aiStatus,
            aiReadiness = aiReadiness,
            onAiAction = { action ->
                when (action) {
                    AiReadiness.Action.DOWNLOAD -> vm.downloadAi()
                    AiReadiness.Action.OPEN_PLAY_STORE -> vm.openAiCoreInPlayStore()
                    AiReadiness.Action.CHECK_AGAIN -> vm.refreshAi()
                }
            },
        )
    } else {
        HomeScreen(
            settings = settings,
            onOpenGroup = { openGroupId = it },
            onNewGroup = { openGroupId = vm.createGroup("Group ${settings.groups.size + 1}") },
            onOpenSettings = { settingsOpen = true },
            onSetEnabled = vm::setEnabled,
            onChangeNow = vm::rotateNow,
            onSetActive = vm::setActive,
            hasCoverScreen = vm.hasCoverScreen(),
            onAssignScreen = vm::assignScreen,
            diagnostics = diagnostics,
            onExportLog = { exportLog.launch("feels-engine-log.jsonl") },
            onTurnOnMotion = turnOnMotion,
            onDismissMotionLost = vm::dismissMotionLost,
        )
    }
}
