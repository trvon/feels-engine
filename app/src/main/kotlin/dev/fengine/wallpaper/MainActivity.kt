package dev.fengine.wallpaper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fengine.wallpaper.ui.FengineTheme
import dev.fengine.wallpaper.ui.GroupScreen
import dev.fengine.wallpaper.ui.HomeScreen
import dev.fengine.wallpaper.ui.MainViewModel
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.smart.MediaLibrary

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FengineTheme { FengineApp(viewModel) } }
    }
}

/** Two screens, one piece of navigation state. No navigation library until a third screen exists. */
@Composable
private fun FengineApp(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var openGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val openGroup = settings.group(openGroupId)

    val picker = rememberLauncherForActivityResult(PickMultipleVisualMedia(100)) { uris ->
        openGroupId?.let { vm.addPhotos(it, uris) }
    }
    val poolPicker = rememberLauncherForActivityResult(PickMultipleVisualMedia(vm.pickerMax())) { uris ->
        openGroupId?.let { vm.addToPool(it, uris) }
    }
    val context = LocalContext.current
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val indexing by vm.indexing.collectAsStateWithLifecycle()
    val persistedGrants by vm.persistedGrants.collectAsStateWithLifecycle()
    val indexProgress by vm.indexProgress.collectAsStateWithLifecycle()
    val aiStatus by vm.aiStatus.collectAsStateWithLifecycle()
    val aiReadiness by vm.aiReadiness.collectAsStateWithLifecycle()
    LaunchedEffect(openGroupId) { if (openGroup?.isDescribed == true) vm.refreshAi() }
    // Library access is asked for only when a described group looks for photos.
    val mediaPermission = rememberLauncherForActivityResult(RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.findPhotos()
    }

    if (openGroup != null) {
        BackHandler { openGroupId = null }
        GroupScreen(
            settings = settings,
            group = openGroup,
            onBack = { openGroupId = null },
            onRename = { vm.renameGroup(openGroup.id, it) },
            onAssign = { home, lock -> vm.assign(openGroup.id, home, lock) },
            onAddPhotos = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            onRemovePhoto = { vm.removePhoto(openGroup.id, it) },
            onDelete = { vm.deleteGroup(openGroup.id); openGroupId = null },
            onSetRule = { vm.setRule(openGroup.id, it) },
            onFindPhotos = {
                if (vm.hasLibraryAccess()) vm.findPhotos()
                else mediaPermission.launch(MediaLibrary.requiredPermissions().toTypedArray())
            },
            onExcludePhoto = { vm.excludePhoto(openGroup.id, it) },
            onAddToPool = { poolPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            onRemoveFromPool = { vm.removeFromPool(openGroup.id, it) },
            onSetCover = { vm.setCover(openGroup.id, it) },
            onSetDream = { vm.setDreamGroup(openGroup.id, it) },
            onShowNow = { uri ->
                if (settings.homeGroupId != openGroup.id && settings.lockGroupId != openGroup.id) vm.setActive(openGroup.id)
                vm.showNow(openGroup.id, uri)
            },
            hasCoverScreen = vm.hasCoverScreen(),
            onSetCoverScreen = { vm.setCoverGroup(openGroup.id, it) },
            pickerMax = vm.pickerMax(),
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
            onNewGroup = { described ->
                openGroupId = vm.createGroup("Group ${settings.groups.size + 1}", rule = if (described) "" else null)
            },
            onSetMode = vm::setMode,
            onSetInterval = vm::setIntervalMinutes,
            onSetTimes = vm::setTimesOfDay,
            onSetShuffle = vm::setShuffle,
            onSetEnabled = vm::setEnabled,
            onChangeNow = vm::rotateNow,
            onSetActive = vm::setActive,
            onSetStill = vm::setStill,
            dreamConfigured = vm.dreamConfigured(),
            motion = vm.motion(),
            onTurnOnMotion = {
                runCatching { context.startActivity(LiveWallpaper.chooserIntent(context)) }
                    .onFailure { context.startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
            },
            onOpenDreamSettings = {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }
                    .onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)) }
            },
        )
    }
}
