package dev.fengine.wallpaper.share

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.schedule.RotationScheduler
import dev.fengine.wallpaper.wallpaper.SharePlan
import dev.fengine.wallpaper.wallpaper.SharedPhotos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Saves the shared photos the moment the share screen opens, while Google Photos' grant lasts (it
 * ends with the screen), and adds them to the group the owner chooses. Survives rotation and
 * folding with the screen; the copies go on in [viewModelScope].
 */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    data class State(
        val total: Int = 0,
        val saved: List<String> = emptyList(),
        val failed: Int = 0,
        val saving: Boolean = true,
        /** Set once the owner chose: (group id or null for a new group, look through). */
        val choice: Pair<String?, Boolean>? = null,
        /** Set once added: the group's name, for the closing message. */
        val addedTo: String? = null,
        /** Set with [addedTo] when its description should look through the photos now. */
        val findIn: String? = null,
    )

    val state = MutableStateFlow(State())
    private var started = false

    fun start(uris: List<Uri>) {
        if (started) return
        started = true
        state.value = State(total = uris.size)
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val gate = Semaphore(PARALLEL)
            var pending = uris
            var lastGain = SystemClock.elapsedRealtime()
            while (pending.isNotEmpty()) {
                val results =
                    pending
                        .map { u ->
                            async { gate.withPermit { u to SharedPhotos.save(context, u) } }
                        }
                        .awaitAll()
                val got = results.mapNotNull { it.second }
                if (got.isNotEmpty()) {
                    lastGain = SystemClock.elapsedRealtime()
                    state.update { it.copy(saved = it.saved + got) }
                }
                pending = results.filter { it.second == null }.map { it.first }
                state.update { it.copy(failed = pending.size) }
                if (pending.isEmpty()) break
                // Google Photos may still be fetching cloud originals: try again for a while.
                if (SystemClock.elapsedRealtime() - lastGain > SharePlan.GIVE_UP_MS) break
                delay(SharePlan.RETRY_MS)
            }
            Log.i(
                TAG,
                "shared ${uris.size}: saved ${state.value.saved.size}, unreadable ${pending.size}",
            )
            state.update { it.copy(saving = false) }
            addIfChosen()
        }
    }

    fun choose(groupId: String?, lookThrough: Boolean) {
        state.update { it.copy(choice = groupId to lookThrough) }
        if (!state.value.saving) viewModelScope.launch { addIfChosen() }
    }

    /**
     * The screen is closing before saving finished: add what was saved so far, if the owner chose.
     * Runs past this view model, which is being cleared.
     */
    fun closing() {
        val s = state.value
        if (cancelled || s.addedTo != null || s.choice == null || s.saved.isEmpty()) return
        closedEarly = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { add(s) }
    }

    private val adding = Mutex()
    private var cancelled = false
    /** The screen closed before adding finished. */
    @Volatile private var closedEarly = false

    /** Cancel: nothing is added; the saved files go with the next copy upkeep (SharePlan). */
    fun cancel() {
        cancelled = true
    }

    private suspend fun addIfChosen() = adding.withLock {
        val s = state.value
        if (cancelled || s.choice == null || s.saving || s.addedTo != null) return@withLock
        val (name, id) = withContext(NonCancellable) { add(s) }
        state.update { it.copy(addedTo = name, findIn = id.takeIf { s.choice.second }) }
    }

    /**
     * Adds [s]'s saved photos to the chosen group; returns the group's name and id. Looking through
     * is left to the app's screen ([ShareActivity] opens it): Gemini Nano answers only in the
     * foreground.
     */
    private suspend fun add(s: State): Pair<String, String> {
        val (groupId, lookThrough) = s.choice!!
        val context = getApplication<FengineApp>()
        var name = ""
        var id = ""
        context.settings.update { settings ->
            val target = settings.groups.firstOrNull { it.id == groupId } ?: Group(name = NEW_GROUP)
            val next = SharePlan.apply(target, s.saved, lookThrough)
            name = next.name
            id = next.id
            val groups =
                if (settings.groups.any { it.id == target.id })
                    settings.groups.map { if (it.id == target.id) next else it }
                else settings.groups + next
            settings.copy(groups = groups)
        }
        RotationScheduler(context).sync(context.settings.read())
        // Closed early: no screen to look through them on, so the background scan tries (labels
        // stand in until the app is opened).
        if (lookThrough && closedEarly) RotationScheduler(context).scanNow()
        return name to id
    }

    companion object {
        private const val TAG = "Share"
        private const val PARALLEL = 3
        const val NEW_GROUP = "Shared photos"
    }
}
