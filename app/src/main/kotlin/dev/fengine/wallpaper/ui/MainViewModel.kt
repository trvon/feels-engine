package dev.fengine.wallpaper.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.graphics.get
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.mlkit.genai.common.FeatureStatus
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Appearance
import dev.fengine.wallpaper.data.Backdrop
import dev.fengine.wallpaper.data.ColorSource
import dev.fengine.wallpaper.data.DynamicColorMode
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.Ledger
import dev.fengine.wallpaper.data.LedgerStore
import dev.fengine.wallpaper.data.Mode
import dev.fengine.wallpaper.data.PhotoFit
import dev.fengine.wallpaper.data.PhotoId
import dev.fengine.wallpaper.data.PhotoRecovery
import dev.fengine.wallpaper.data.Platform
import dev.fengine.wallpaper.data.Screen
import dev.fengine.wallpaper.data.Screens
import dev.fengine.wallpaper.data.Settings
import dev.fengine.wallpaper.data.ThemeMode
import dev.fengine.wallpaper.schedule.AppSleep
import dev.fengine.wallpaper.schedule.Rotation
import dev.fengine.wallpaper.schedule.RotationScheduler
import dev.fengine.wallpaper.smart.AiReadiness
import dev.fengine.wallpaper.smart.IndexLibraryWorker
import dev.fengine.wallpaper.smart.MediaLibrary
import dev.fengine.wallpaper.smart.NanoMatcher
import dev.fengine.wallpaper.smart.Outside
import dev.fengine.wallpaper.smart.PhotoIndex
import dev.fengine.wallpaper.smart.SmartGroupWorker
import dev.fengine.wallpaper.wallpaper.ClockLegibility
import dev.fengine.wallpaper.wallpaper.Displays
import dev.fengine.wallpaper.wallpaper.PhotoAccess
import dev.fengine.wallpaper.wallpaper.PhotoCopies
import dev.fengine.wallpaper.wallpaper.PhotoCopyWorker
import dev.fengine.wallpaper.wallpaper.SamsungDisplays
import dev.fengine.wallpaper.wallpaper.WallpaperCrop
import dev.fengine.wallpaper.wallpaper.live.LiveWallpaper
import dev.fengine.wallpaper.wallpaper.live.MotionCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as FengineApp).settings
    private val scheduler = RotationScheduler(app)

    init {
        // A force stop (or an OEM cleaner) cancels every alarm; opening the app arms them again.
        viewModelScope.launch {
            runCatching {
                val s = store.read()
                scheduler.sync(s)
                dev.fengine.wallpaper.shortcuts.NextPhotoWidget.publishPreview(
                    app,
                    s.homeGroup?.name,
                )
            }
        }
        checkPhotos()
    }

    /** Picked photos no group refers to any more (see [PhotoRecovery]); Home offers them back. */
    val orphanPhotos = MutableStateFlow<List<String>>(emptyList())

    /**
     * Photos marked unreadable go back into rotation when they open again, and grants without a
     * group are offered back. Runs when the app opens, in the foreground, so a cloud photo may
     * load.
     */
    fun checkPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val back =
                    store
                        .read()
                        .groups
                        .flatMap { it.unreadable }
                        .distinct()
                        .take(MAX_RECHECK)
                        .filter { PhotoAccess.readable(app, it) }
                if (back.isNotEmpty())
                    store.update { cur ->
                        cur.copy(groups = cur.groups.map { it.markReadable(back) })
                    }
                val granted =
                    app.contentResolver.persistedUriPermissions
                        .filter { it.isReadPermission }
                        .map { it.uri.toString() }
                orphanPhotos.value = PhotoRecovery.orphans(granted, store.read().groups)
                // While the originals are still here, keep copies for when they are not.
                PhotoCopyWorker.enqueue(app)
                refreshCopies()
                appSleepRestricted.value = AppSleep.restricted(AppSleep.status(app))
            }
                .onFailure { android.util.Log.w("MainViewModel", "photo check failed", it) }
        }
    }

    /** Put [orphanPhotos] back: into the one group with picked photos, or a new group. */
    fun putBackPhotos() {
        val uris = orphanPhotos.value
        if (uris.isEmpty()) return
        orphanPhotos.value = emptyList()
        viewModelScope.launch {
            store.update { s ->
                val target = PhotoRecovery.target(s.groups)
                if (target != null)
                    s.copy(
                        groups =
                            s.groups.map {
                                if (it.id == target) it.copy(photos = (it.photos + uris).distinct())
                                else it
                            }
                    )
                else {
                    val g = Group(name = "Put back", photos = uris)
                    s.copy(
                        groups = s.groups + g,
                        homeGroupId = s.homeGroupId ?: g.id,
                        lockGroupId = s.lockGroupId ?: g.id,
                    )
                }
            }
            scheduler.sync(store.read())
            checkPhotos()
        }
    }

    /** Release the grants of [orphanPhotos]; they can be picked again later. */
    fun letPhotosGo() {
        val uris = orphanPhotos.value
        orphanPhotos.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { uris.forEach(::releaseGrant) }
    }

    private val library = MediaLibrary(app)

    val settings: StateFlow<Settings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    /** True while a library scan is queued or running. */
    val scanning: StateFlow<Boolean> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(SmartGroupWorker.UNIQUE_NAME)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while the library indexer is queued or running. */
    val indexing: StateFlow<Boolean> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(IndexLibraryWorker.UNIQUE_NAME)
            .map { infos ->
                infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** (indexed, total) for the library, refreshed whenever indexing or scanning changes state. */
    val indexProgress: StateFlow<Pair<Int, Int>?> =
        combine(indexing, scanning) { a, b -> a to b }
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
    val persistedGrants: StateFlow<Int> =
        settings
            .map {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.persistedUriPermissions.size
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** On-device AI preparation status for the group screen; null when nothing is happening. */
    val aiStatus = MutableStateFlow<String?>(null)

    /** Plain-words readiness of Gemini Nano on this phone, refreshed when a group screen opens. */
    val aiReadiness = MutableStateFlow<AiReadiness?>(null)

    fun refreshAi() {
        viewModelScope.launch {
            val r = AiReadiness.check(getApplication())
            aiReadiness.value = r
            logAi(r.level.name.lowercase())
        }
    }

    /** Last AI state written to the ledger by this process, so a re-check writes no duplicate. */
    private var loggedAi: String? = null

    private fun logAi(state: String, code: Int? = null) {
        if (state == loggedAi) return
        loggedAi = state
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { LedgerStore(getApplication()).record(Ledger.Event.Ai(state, code)) }
        }
    }

    /** Download the on-device model now (reports through [aiStatus]), then re-check. */
    fun downloadAi() {
        viewModelScope.launch {
            val nano = NanoMatcher()
            try {
                // AICore can accept a download and never start it (seen on a Fold 8 while its own
                // update waited to activate): Outside.watch says so instead of "Preparing" forever.
                Outside.watch(nano.downloadEvents(), Outside.STALL) { view ->
                    aiStatus.value =
                        when (view) {
                            Outside.DownloadView.Preparing -> "Preparing on-device AI..."
                            is Outside.DownloadView.Downloading ->
                                "Downloading on-device AI, ${view.bytes / 1_000_000} MB"
                            Outside.DownloadView.Stalled -> AI_STALLED.also { logAi("stalled") }
                            Outside.DownloadView.Done,
                            Outside.DownloadView.Ended -> null
                            is Outside.DownloadView.Failed ->
                                failure(view.error).also {
                                    logAi(
                                        "failed",
                                        (view.error
                                                as? com.google.mlkit.genai.common.GenAiException)
                                            ?.errorCode,
                                    )
                                }
                        }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable: the ML Kit beta has thrown Errors (NoSuchMethodError) from download().
                android.util.Log.w("MainViewModel", "AI download failed", e)
                aiStatus.value = failure(e)
            } finally {
                runCatching { nano.close() }
            }
            refreshAi()
        }
    }

    private fun failure(e: Throwable): String {
        val g = e as? com.google.mlkit.genai.common.GenAiException
        val retry = runCatching { g?.retryDelay?.seconds }.getOrNull()
        return AiReadiness.downloadFailed(g?.errorCode, e.message ?: e.javaClass.simpleName, retry)
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

    fun renameGroup(id: String, name: String) =
        updateGroup(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    /**
     * Changing the description discards previous matches and looks through the photos already
     * handed over again, so a new wording never needs them picked a second time.
     */
    fun setRule(id: String, rule: String) {
        val before = settings.value.group(id) ?: return
        if (before.rule == rule) return
        viewModelScope.launch {
            store.update { s ->
                s.copy(
                    groups =
                        s.groups.map {
                            if (it.id == id)
                                it.copy(rule = rule, matched = emptyList(), scanNote = "")
                            else it
                        }
                )
            }
            if (rule.isNotBlank() && before.pool.isNotEmpty()) findPhotos()
        }
    }

    fun deleteGroup(id: String) {
        val removed = settings.value.group(id)?.let { it.photos + it.pool }.orEmpty()
        update { s -> Screens.withoutGroup(s, id) }
        val stillUsed =
            settings.value.groups.filterNot { it.id == id }.flatMap { it.photos + it.pool }.toSet()
        removed.filterNot { it in stillUsed }.forEach(::releaseGrant)
    }

    fun setDreamWirelessOnly(on: Boolean) = update { it.copy(dreamWirelessOnly = on) }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setColorSource(source: ColorSource) = update { it.copy(colorSource = source) }

    fun setBackdrop(backdrop: Backdrop) = update { it.copy(backdrop = backdrop) }

    fun setPhotoFit(fit: PhotoFit) = update { it.copy(photoFit = fit) }

    fun setDynamicColorMode(mode: DynamicColorMode) = update { s ->
        val seed =
            if (mode == DynamicColorMode.LOCKED)
                s.pinnedColorSeed ?: Appearance.COLOR_PRESETS.first().second
            else s.pinnedColorSeed
        s.copy(dynamicColorMode = mode, pinnedColorSeed = seed)
    }

    fun setPinnedColorSeed(seed: Int) = update {
        it.copy(dynamicColorMode = DynamicColorMode.LOCKED, pinnedColorSeed = seed)
    }

    /** Screen-saver starts by verdict over the last 7 days, from the local log. */
    val screenSaverWeek = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Give one screen to a group, or null to leave it unassigned. Nothing else moves. */
    fun assignScreen(screen: Screen, groupId: String?) = update {
        Screens.assign(it, screen, groupId)
    }

    /**
     * Photo picker grants are temporary by default. Persist each one so the background worker can
     * still open the URI after the process dies or the device reboots.
     */
    fun addPhotos(groupId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val kept =
            uris
                .filter { uri ->
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                        .isSuccess
                }
                .map(Uri::toString)
        updateGroup(groupId) { it.withPhotos(kept) }
        PhotoCopyWorker.enqueue(getApplication())
    }

    fun removePhoto(groupId: String, uri: String) {
        updateGroup(groupId) { it.copy(photos = it.photos - uri, cursor = -1) }
        val stillUsed =
            settings.value.groups.any { g -> g.id != groupId && (uri in g.photos || uri in g.pool) }
        if (!stillUsed) releaseGrant(uri)
    }

    /** Keep a photo in the group but out of rotation, or bring it back. */
    fun setSkipped(groupId: String, uri: String, skip: Boolean) =
        updateGroup(groupId) { g ->
            val rest = g.skipped.filterNot { PhotoId.identity(it) == PhotoId.identity(uri) }
            g.copy(skipped = if (skip) rest + uri else rest, cursor = -1)
        }

    /**
     * Whether the lock-screen clock will read well over this photo (null when it cannot be read).
     */
    suspend fun clockCheck(uri: String): ClockLegibility.Verdict? =
        withContext(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val target = Displays(app).largest
                val bmp =
                    PhotoCopies.decode(app, uri) { d, info, _ ->
                        val scale = 360f / maxOf(info.size.width, info.size.height)
                        if (scale < 1f)
                            d.setTargetSize(
                                (info.size.width * scale).toInt().coerceAtLeast(1),
                                (info.size.height * scale).toInt().coerceAtLeast(1),
                            )
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                val crop = WallpaperCrop.fit(bmp.width, bmp.height, target.width, target.height)
                val zone = ClockLegibility.zone(crop.left, crop.top, crop.right, crop.bottom)
                val lum = ArrayList<Double>()
                for (y in zone.top until zone.bottom step 2) for (x in
                    zone.left until zone.right step 2) lum +=
                    ClockLegibility.relativeLuminance(bmp[x, y])
                bmp.recycle()
                ClockLegibility.judge(lum.toDoubleArray())
            }
                .getOrNull()
        }

    /**
     * Photos the description should look through, from the system picker (cloud included). The
     * matches join the group with a Found badge; grants are persisted so a rescan can read them.
     */
    fun addToPool(groupId: String, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val resolver = getApplication<Application>().contentResolver
        val kept =
            uris
                .filter { uri ->
                    runCatching {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                        .isSuccess
                }
                .map(Uri::toString)
        updateGroup(groupId) { it.copy(pool = (it.pool + kept).distinct()) }
        // Look-through grants count against Android's ceiling: the upkeep releases the oldest of
        // them before picked photos are at risk (GrantPlan).
        PhotoCopyWorker.enqueue(getApplication())
        findPhotos()
    }

    /** The photo that represents the group on the home rail. */
    fun setCover(groupId: String, uri: String) = updateGroup(groupId) { it.copy(coverUri = uri) }

    companion object {
        /**
         * Persisted picker grants this Android version keeps (128 before Android 11, 512 after).
         */
        val GRANT_CEILING = Platform.grantCeiling(android.os.Build.VERSION.SDK_INT)
        val GRANT_WARN_AT = Platform.grantWarnAt(android.os.Build.VERSION.SDK_INT)

        /** Unreadable photos tried per app open; each may fetch a cloud item. */
        const val MAX_RECHECK = 64

        const val AI_STALLED =
            "AICore has not started the download. It is often finishing an update of its own: " +
                "leave the phone charging on Wi-Fi with the screen off for a while, then check " +
                "again. Finding photos uses labels meanwhile."
    }

    /** How many photos the system picker hands over per pick; the cloud has no bulk road. */
    fun pickerMax(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { android.provider.MediaStore.getPickImagesMaxLimit() }.getOrDefault(100)
        } else 100

    /** A found photo the user does not want: kept out of future scans too. */
    fun excludePhoto(groupId: String, uri: String) =
        updateGroup(groupId) {
            it.copy(
                matched = it.matched - uri,
                excluded = (it.excluded + uri).distinct(),
                cursor = -1,
            )
        }

    /** Which screens the live engine draws, per panel; refreshed off the main thread. */
    val motionState = MutableStateFlow(LiveWallpaper.State(false, false, false, false))

    /** Several binder calls (about ten on One UI): never on the main thread. */
    fun refreshMotion() {
        viewModelScope.launch(Dispatchers.IO) { motionState.value = motion() }
    }

    /** Which screens the live engine draws right now, per panel. */
    fun motion(): LiveWallpaper.State = runCatching {
        LiveWallpaper.state(getApplication())
    }
        .getOrDefault(LiveWallpaper.State(false, false, false, false))

    /** True when the user has chosen this app in Settings > Display > Screen saver. */
    fun dreamConfigured(): Boolean {
        val r = getApplication<Application>().contentResolver
        val enabled = android.provider.Settings.Secure.getInt(r, "screensaver_enabled", 0) == 1
        val components =
            android.provider.Settings.Secure.getString(r, "screensaver_components").orEmpty()
        return enabled && components.contains(getApplication<Application>().packageName)
    }

    /**
     * Start a library scan. Prepares Gemini Nano first when the device offers it (one-time model
     * download, reported through [aiStatus]); otherwise the scan uses labels only.
     */
    fun findPhotos() {
        viewModelScope.launch {
            val status = runCatching {
                NanoMatcher().use { it.status() }
            }
                .onFailure { android.util.Log.w("MainViewModel", "Gemini Nano status failed", it) }
                .getOrDefault(FeatureStatus.UNAVAILABLE)
            // The download runs on its own; the scan starts now with labels and judges with
            // Gemini Nano on later scans once it lands. Waiting here hung the scan whenever
            // AICore never started the download.
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING)
                downloadAi()
            if (library.hasAccess()) scheduler.indexNow()
            scheduler.scanNow()
        }
    }

    // Schedule

    fun setMode(mode: Mode) = update { it.copy(mode = mode) }

    fun setShuffle(shuffle: Boolean) = update { it.copy(shuffle = shuffle) }

    fun setTapToChange(on: Boolean) = update { it.copy(tapToChange = on) }

    fun setChangeOnTheme(on: Boolean) = update { it.copy(changeOnTheme = on) }

    fun setDarkAtNight(on: Boolean) = update { it.copy(darkAtNight = on) }

    /**
     * Copies kept and their size, for Settings; refreshed when the app opens or the switch moves.
     */
    val copies = MutableStateFlow(0 to 0L)

    private fun refreshCopies() {
        viewModelScope.launch(Dispatchers.IO) { copies.value = PhotoCopies.usage(getApplication()) }
    }

    /** Off deletes every copy; on saves them again in the background. */
    fun setKeepCopies(on: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(keepCopies = on) }
            if (!on) withContext(Dispatchers.IO) { PhotoCopies.clear(getApplication()) }
            else PhotoCopyWorker.enqueue(getApplication())
            refreshCopies()
        }
    }

    fun setIntervalMinutes(minutes: Int) = update {
        it.copy(intervalMinutes = minutes.coerceAtLeast(Settings.MIN_INTERVAL_MINUTES))
    }

    fun setTimesOfDay(minutes: List<Int>) = update {
        it.copy(timesOfDay = minutes.distinct().sorted())
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun rotateNow() = scheduler.rotateNow(source = Ledger.Source.NEXT)

    /** Home's one button before rotation runs: turn it on and put the next photo up now. */
    fun start() {
        viewModelScope.launch {
            store.update { it.copy(enabled = true) }
            scheduler.sync(store.read())
            scheduler.rotateNow(source = Ledger.Source.NEXT)
        }
    }

    /** The last 7 days of the local ledger in one sentence; null before anything was recorded. */
    val diagnostics = MutableStateFlow<String?>(null)

    fun refreshDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = LedgerStore(getApplication()).entries()
            val now = System.currentTimeMillis()
            val week = Ledger.summarize(entries, now - 7L * 24 * 3600 * 1000, now)
            diagnostics.value = if (entries.isEmpty()) null else Ledger.sentence(week)
            screenSaverWeek.value = week.screenSaver
        }
    }

    /** On every resume: notices Motion that Android turned off, or that came back. */
    fun checkMotion() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Rotation.observeMotion(getApplication()) }
            refreshDiagnostics()
        }
    }

    /** Home's one-time "Make it move" line: shown once, gone after a tap either way. */
    fun dismissMotionOffer() = update { it.copy(motionOffered = true) }

    /** Android will pause the app while it goes unused (AppSleep); refreshed when the app opens. */
    val appSleepRestricted = MutableStateFlow(false)

    fun openAppSleepSettings() {
        val app = getApplication<Application>()
        runCatching { app.startActivity(AppSleep.settingsIntent(app)) }
    }

    /**
     * "Pick it again": [picked] takes [old]'s place in the group, keeping its position, cover and
     * the photo on screen. The old grant goes when nothing else uses it.
     */
    fun replacePhoto(groupId: String, old: String, picked: Uri?) {
        picked ?: return
        val app = getApplication<Application>()
        val ok = runCatching {
            app.contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
            .isSuccess
        if (!ok) return
        val new = picked.toString()
        updateGroup(groupId) { it.replacePhoto(old, new) }
        val stillUsed =
            settings.value.groups.any { g ->
                g.id != groupId && g.allUris.any { PhotoId.identity(it) == PhotoId.identity(old) }
            }
        if (!stillUsed && PhotoId.identity(old) != PhotoId.identity(new)) releaseGrant(old)
        PhotoCopyWorker.enqueue(app)
    }

    fun dismissMotionLost() {
        viewModelScope.launch { MotionCheck.dismiss(getApplication()) }
    }

    /** Write the whole ledger to a file the owner picked (system "save as"). */
    fun exportLedger(target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            runCatching {
                // "wt": truncate, so overwriting a longer file leaves no stale tail.
                app.contentResolver.openOutputStream(target, "wt")?.use {
                    it.write(LedgerStore(app).text().toByteArray())
                }
            }
        }
    }

    /**
     * Make [groupId] the active scheme: it feeds both screens and its cover (or current photo)
     * shows right away. The rotation then carries on from that photo.
     */
    fun setActive(groupId: String, photo: String? = null) {
        val group = settings.value.group(groupId) ?: return
        val pool = group.rotation
        val index =
            photo?.let { pool.indexOf(it) }?.takeIf { it >= 0 }
                ?: pool.indexOf(group.cover).takeIf { it >= 0 }
                ?: group.cursor.coerceIn(0, (pool.size - 1).coerceAtLeast(0))
        viewModelScope.launch {
            // The first Set active on an install that has never changed a photo also starts the
            // rotation: a new person should not see one photo and then "Paused." (review item 7).
            store.update {
                it.copy(
                    homeGroupId = groupId,
                    lockGroupId = groupId,
                    enabled = it.enabled || it.lastRotationAt <= 0,
                )
            }
            scheduler.sync(store.read())
            if (pool.isNotEmpty())
                scheduler.rotateNow(
                    pinned = mapOf(groupId to index),
                    source = Ledger.Source.SET_ACTIVE,
                )
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
        val pins =
            listOfNotNull(s.homeGroup, s.lockGroup)
                .distinct()
                .filter { it.rotation.isNotEmpty() }
                .associate { it.id to it.cursor.coerceIn(0, it.rotation.size - 1) }
        scheduler.rotateNow(pinned = pins, forceStatic = true, source = Ledger.Source.STILL)
    }

    /**
     * True when this phone has two panels that can be targeted separately (Samsung display bits).
     */
    fun hasCoverScreen(): Boolean {
        val app = getApplication<Application>()
        return SamsungDisplays.hasDisplayBits() &&
            runCatching { Displays(app).let { it.largest != it.smallest } }.getOrDefault(false)
    }

    private fun updateGroup(id: String, transform: (Group) -> Group) = update { s ->
        s.copy(groups = s.groups.map { if (it.id == id) transform(it) else it })
    }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch {
            store.update(transform)
            scheduler.sync(store.read())
        }
    }

    private fun releaseGrant(uri: String) {
        runCatching {
            getApplication<Application>()
                .contentResolver
                .releasePersistableUriPermission(uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
