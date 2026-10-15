package dev.fengine.wallpaper.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.wallpaper.ClockLegibility
import dev.fengine.wallpaper.wallpaper.WallpaperCrop
import dev.fengine.wallpaper.data.Platform
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.schedule.RotationScheduler
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.smart.MediaLibrary
import dev.fengine.wallpaper.smart.NanoMatcher
import dev.fengine.wallpaper.smart.SmartGroupWorker
import dev.fengine.wallpaper.wallpaper.Displays
import dev.fengine.wallpaper.wallpaper.SamsungDisplays
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import dev.fengine.wallpaper.smart.PhotoIndex
import dev.fengine.wallpaper.smart.IndexLibraryWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as FengineApp).settings
    private val scheduler = RotationScheduler(app)
    private val library = MediaLibrary(app)

    val settings: StateFlow<Settings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** True while a library scan is queued or running. */
    val scanning: StateFlow<Boolean> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(SmartGroupWorker.UNIQUE_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while the library indexer is queued or running. */
    val indexing: StateFlow<Boolean> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(IndexLibraryWorker.UNIQUE_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** (indexed, total) for the library, refreshed whenever indexing or scanning changes state. */
    val indexProgress: StateFlow<Pair<Int, Int>?> = combine(indexing, scanning) { a, b -> a to b }
        .map { withContext(Dispatchers.IO) { indexCounts() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun indexCounts(): Pair<Int, Int>? {
        if (!library.hasAccess()) return null
        val total = library.newest(IndexLibraryWorker.MAX_LIBRARY).size
        val indexed = PhotoIndex(getApplication()).use { it.count() }
        return indexed.coerceAtMost(total) to total
    }

    /**
     * Picked photos the system still lets the app read. Android keeps at most [GRANT_CEILING]
     * persisted picker grants per app and silently drops the oldest beyond that, so the UI warns
     * well before. Refreshed with the settings flow, which changes on every pick.
     */
    val persistedGrants: StateFlow<Int> = settings
        .map { withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.persistedUriPermissions.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** On-device AI preparation status for the group screen; null when nothing is happening. */
    val aiStatus = MutableStateFlow<String?>(null)

    /** Plain-words readiness of Gemini Nano on this phone, refreshed when a group screen opens. */
    val aiReadiness = MutableStateFlow<AiReadiness?>(null)

    fun refreshAi() {
        viewModelScope.launch { aiReadiness.value = AiReadiness.check(getApplication()) }
    }

    /** Download the on-device model now (reports through [aiStatus]), then re-check. */
    fun downloadAi() {
        viewModelScope.launch {
            val nano = NanoMatcher()
            try {
                aiStatus.value = "Preparing on-device AI..."
                nano.download().collect { st ->
                    aiStatus.value = when (st) {
                        is DownloadStatus.DownloadProgress -> "Downloading on-device AI, ${st.totalBytesDownloaded / 1_000_000} MB"
                        is DownloadStatus.DownloadCompleted -> null
                        is DownloadStatus.DownloadFailed -> "Download failed: ${st.e.message}"
                        else -> aiStatus.value
                    }
                }
            } catch (e: Exception) {
                aiStatus.value = "Download failed: ${e.message}"
            } finally {
                nano.close()
            }
            refreshAi()
        }
    }

    fun openAiCoreInPlayStore() {
        runCatching { getApplication<Application>().startActivity(AiReadiness.playStoreIntent()) }
    }

    fun hasLibraryAccess(): Boolean = library.hasAccess()

    // Groups

    /** Creates a group, optionally with a description. The first group feeds both screens. */
    fun createGroup(name: String, rule: String? = null): String {
        val group = Group(name = name.ifBlank { "Untitled" }, rule = rule)
        update { s ->
            val first = s.groups.isEmpty()
            s.copy(
                groups = s.groups + group,
                homeGroupId = if (first) group.id else s.homeGroupId,
                lockGroupId = if (first) group.id else s.lockGroupId,
            )
        }
        return group.id
    }

    fun renameGroup(id: String, name: String) = updateGroup(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    /** Changing the description discards previous matches; the next scan rebuilds them. */
    fun setRule(id: String, rule: String) = updateGroup(id) {
        if (it.rule == rule) it else it.copy(rule = rule, matched = emptyList(), scanNote = "")
    }

    fun deleteGroup(id: String) {
        val removed = settings.value.group(id)?.let { it.photos + it.pool }.orEmpty()
        update { s -> Screens.withoutGroup(s, id) }
        val stillUsed = settings.value.groups.filterNot { it.id == id }.flatMap { it.photos + it.pool }.toSet()
        removed.filterNot { it in stillUsed }.forEach(::releaseGrant)
    }

    fun setDreamWirelessOnly(on: Boolean) = update { it.copy(dreamWirelessOnly = on) }

    /** Give one screen to a group, or null to leave it unassigned. Nothing else moves. */
    fun assignScreen(screen: Screen, groupId: String?) = update { Screens.assign(it, screen, groupId) }

    /**
     * Photo picker grants are temporary by default. Persist each one so the background worker can
     * still open the URI after the process dies or the device reboots.
     */
    fun addPhotos(groupId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val kept = uris.filter { uri ->
            runCatching {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
        }.map(Uri::toString)
        updateGroup(groupId) { it.copy(photos = (it.photos + kept).distinct()) }
    }

    fun removePhoto(groupId: String, uri: String) {
        updateGroup(groupId) { it.copy(photos = it.photos - uri, cursor = -1) }
        val stillUsed = settings.value.groups.any { g -> g.id != groupId && (uri in g.photos || uri in g.pool) }
        if (!stillUsed) releaseGrant(uri)
    }

    /** Keep a photo in the group but out of rotation, or bring it back. */
    fun setSkipped(groupId: String, uri: String, skip: Boolean) = updateGroup(groupId) { g ->
        val rest = g.skipped.filterNot { PhotoId.identity(it) == PhotoId.identity(uri) }
        g.copy(skipped = if (skip) rest + uri else rest, cursor = -1)
    }

    /** Whether the lock-screen clock will read well over this photo (null when it cannot be read). */
    suspend fun clockCheck(uri: String): ClockLegibility.Verdict? = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val target = Displays(app).largest
            val bmp = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(app.contentResolver, uri.toUri())) { d, info, _ ->
                val scale = 360f / maxOf(info.size.width, info.size.height)
                if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val crop = WallpaperCrop.fit(bmp.width, bmp.height, target.width, target.height)
            val zone = ClockLegibility.zone(crop.left, crop.top, crop.right, crop.bottom)
            val lum = ArrayList<Double>()
            for (y in zone.top until zone.bottom step 2) for (x in zone.left until zone.right step 2) lum += ClockLegibility.relativeLuminance(bmp.getPixel(x, y))
            bmp.recycle()
            ClockLegibility.judge(lum.toDoubleArray())
        }.getOrNull()
    }

    /** The photo that represents the group on the home rail. */
    fun setCover(groupId: String, uri: String) = updateGroup(groupId) { it.copy(coverUri = uri) }

    companion object {
        /** Persisted picker grants this Android version keeps (128 before Android 11, 512 after). */
        val GRANT_CEILING = Platform.grantCeiling(android.os.Build.VERSION.SDK_INT)
        val GRANT_WARN_AT = Platform.grantWarnAt(android.os.Build.VERSION.SDK_INT)
    }

    /** How many photos the system picker hands over per pick; the cloud has no bulk road. */
    fun pickerMax(): Int = if (android.os.Build.VERSION.SDK_INT >= 33) {
        runCatching { android.provider.MediaStore.getPickImagesMaxLimit() }.getOrDefault(100)
    } else 100

    /** A found photo the user does not want: kept out of future scans too. */
    fun excludePhoto(groupId: String, uri: String) =
        updateGroup(groupId) { it.copy(matched = it.matched - uri, excluded = (it.excluded + uri).distinct(), cursor = -1) }

    /** The group the screen saver plays; off means the home group plays. */
    fun setDreamGroup(groupId: String, on: Boolean) =
        update { it.copy(dreamGroupId = if (on) groupId else it.dreamGroupId.takeUnless { d -> d == groupId }) }

    /** Which screens the live engine draws right now, per panel. */
    fun motion(): LiveWallpaper.State =
        runCatching { LiveWallpaper.state(getApplication()) }.getOrDefault(LiveWallpaper.State(false, false, false, false))

    /** True when the user has chosen this app in Settings > Display > Screen saver. */
    fun dreamConfigured(): Boolean {
        val r = getApplication<Application>().contentResolver
        val enabled = android.provider.Settings.Secure.getInt(r, "screensaver_enabled", 0) == 1
        val components = android.provider.Settings.Secure.getString(r, "screensaver_components").orEmpty()
        return enabled && components.contains(getApplication<Application>().packageName)
    }

    /** Which screens this group feeds. Picking a screen replaces whatever group fed it before. */
    fun assign(groupId: String, home: Boolean, lock: Boolean) = update { s ->
        s.copy(
            homeGroupId = if (home) groupId else s.homeGroupId.takeUnless { it == groupId },
            lockGroupId = if (lock) groupId else s.lockGroupId.takeUnless { it == groupId },
        )
    }

    /**
     * Start a library scan. Prepares Gemini Nano first when the device offers it (one-time model
     * download, reported through [aiStatus]); otherwise the scan uses labels only.
     */
    fun findPhotos() {
        viewModelScope.launch {
            val nano = NanoMatcher()
            try {
                when (nano.status()) {
                    FeatureStatus.AVAILABLE -> aiStatus.value = null
                    FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                        aiStatus.value = "Preparing on-device AI..."
                        nano.download().collect { st ->
                            aiStatus.value = when (st) {
                                is DownloadStatus.DownloadProgress -> "Downloading on-device AI, ${st.totalBytesDownloaded / 1_000_000} MB"
                                is DownloadStatus.DownloadCompleted -> null
                                is DownloadStatus.DownloadFailed -> "On-device AI unavailable; using labels only"
                                else -> aiStatus.value
                            }
                        }
                    }
                    else -> aiStatus.value = "This phone has no on-device AI; using labels only"
                }
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "Gemini Nano preparation failed", e)
                aiStatus.value = "On-device AI unavailable; using labels only"
            } finally {
                nano.close()
            }
            scheduler.indexNow()
            scheduler.scanNow()
            // From here the scan note speaks; the readiness card already explains the labels-only case.
            aiStatus.value = null
        }
    }

    // Schedule

    fun setMode(mode: Mode) = update { it.copy(mode = mode) }
    fun setShuffle(shuffle: Boolean) = update { it.copy(shuffle = shuffle) }
    fun setIntervalMinutes(minutes: Int) =
        update { it.copy(intervalMinutes = minutes.coerceAtLeast(Settings.MIN_INTERVAL_MINUTES)) }
    fun setTimesOfDay(minutes: List<Int>) = update { it.copy(timesOfDay = minutes.distinct().sorted()) }
    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun rotateNow() = scheduler.rotateNow(source = Ledger.Source.NEXT)

    /** The last 7 days of the local ledger in one sentence; null before anything was recorded. */
    val diagnostics = MutableStateFlow<String?>(null)

    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = LedgerStore(getApplication()).entries()
            val now = System.currentTimeMillis()
            diagnostics.value = if (entries.isEmpty()) null
            else Ledger.sentence(Ledger.summarize(entries, now - 7L * 24 * 3600 * 1000, now))
        }
    }

    /** Write the whole ledger to a file the owner picked (system "save as"). */
    fun exportLedger(target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            runCatching {
                app.contentResolver.openOutputStream(target)?.use { it.write(LedgerStore(app).text().toByteArray()) }
            }
        }
    }

    /**
     * Make [groupId] the active scheme: it feeds both screens and its cover (or current photo) shows
     * right away. The rotation then carries on from that photo.
     */
    fun setActive(groupId: String, photo: String? = null) {
        val group = settings.value.group(groupId) ?: return
        val pool = group.rotation
        val index = photo?.let { pool.indexOf(it) }?.takeIf { it >= 0 }
            ?: pool.indexOf(group.cover).takeIf { it >= 0 }
            ?: group.cursor.coerceIn(0, (pool.size - 1).coerceAtLeast(0))
        viewModelScope.launch {
            store.update { it.copy(homeGroupId = groupId, lockGroupId = groupId) }
            scheduler.sync(store.read())
            if (pool.isNotEmpty()) scheduler.rotateNow(pinned = mapOf(groupId to index), source = Ledger.Source.SET_ACTIVE)
        }
    }

    /** Show this photo on the screens its group feeds, now, and continue the rotation from it. */
    fun showNow(groupId: String, uri: String) {
        val group = settings.value.group(groupId) ?: return
        val index = group.rotation.indexOf(uri).takeIf { it >= 0 } ?: return
        scheduler.rotateNow(pinned = mapOf(groupId to index), source = Ledger.Source.SHOW_NOW)
    }

    /**
     * Still: write the current photos as static wallpapers, which replaces the live engine on every
     * screen it drew. Motion is turned on through the system chooser, see MainActivity.
     */
    fun setStill() {
        val s = settings.value
        val pins = listOfNotNull(s.homeGroup, s.lockGroup).distinct()
            .filter { it.rotation.isNotEmpty() }
            .associate { it.id to it.cursor.coerceIn(0, it.rotation.size - 1) }
        scheduler.rotateNow(pinned = pins, forceStatic = true, source = Ledger.Source.STILL)
    }

    /** On a foldable, the group the cover screen shows; off means the same photo as the inner screen. */
    fun setCoverGroup(groupId: String, on: Boolean) =
        update { it.copy(coverGroupId = if (on) groupId else it.coverGroupId.takeUnless { c -> c == groupId }) }

    /** True when this phone has two panels that can be targeted separately (Samsung display bits). */
    fun hasCoverScreen(): Boolean {
        val app = getApplication<Application>()
        return SamsungDisplays.hasDisplayBits() && runCatching { Displays(app).let { it.largest != it.smallest } }.getOrDefault(false)
    }

    private fun updateGroup(id: String, transform: (Group) -> Group) =
        update { s -> s.copy(groups = s.groups.map { if (it.id == id) transform(it) else it }) }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            store.update(transform)
            scheduler.sync(store.read())
        }
    }

    private fun releaseGrant(uri: String) {
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
