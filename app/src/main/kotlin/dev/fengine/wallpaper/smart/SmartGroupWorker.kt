package dev.fengine.wallpaper.smart

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fengine.wallpaper.FengineApp
import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId

/**
 * Finds photos for every described group, within a bounded budget per run so WorkManager's
 * 10-minute limit is never hit:
 * 1. list the newest images,
 * 2. label the ones not yet labeled (ML Kit, on device),
 * 3. per rule, ask the matcher which labels are relevant, take unjudged candidates that carry
 *    one, and judge them (Gemini Nano on supported devices; labels-only elsewhere),
 * 4. append matches to the group and record a note the UI shows.
 */
class SmartGroupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as FengineApp
        val store = app.settings
        val library = MediaLibrary(applicationContext)
        val groups = store.read().groups.filter { it.isDescribed }
        if (groups.isEmpty()) return Result.success()
        if (!library.hasAccess()) {
            note(groups.map { it.id }) { "Needs photo access to look through the library." }
            return Result.success()
        }

        val libraryKeys = library.newest(MAX_SCAN).map { it.uri.toString() }
        val index = PhotoIndex(applicationContext)
        // The pool is small and the user just handed it over: label it here. The library is the
        // indexer's job; it labels in slices and re-runs this scan after each one.
        val pool = groups.flatMap { it.pool }.distinct()
        val labeler = Labeler()
        var labeled = 0
        try {
            val todo = index.unlabeled(pool).take(LABEL_BUDGET)
            for (chunk in todo.chunked(10)) {
                val results = chunk.map { uri ->
                    uri to runCatching { labeler.label(Images.decode(applicationContext, Uri.parse(uri), 640)) }
                        .getOrElse { e -> Log.w(TAG, "label failed for $uri", e); emptyMap() }
                }
                index.transaction { results.forEach { (uri, l) -> index.putLabels(uri, l) } }
                labeled += chunk.size
                setProgress(workDataOf(KEY_LABELED to labeled))
            }
        } finally {
            labeler.close()
        }
        val unlabeled = index.unlabeled(libraryKeys).size
        if (unlabeled > 0) IndexLibraryWorker.enqueue(applicationContext)
        val labels = index.allLabels()
        val labelsFor = { uri: String -> labels[PhotoId.identity(uri)] }

        val nano = NanoMatcher()
        val matcher: DescriptionMatcher = if (nano.isAvailable()) nano else KeywordMatcher()
        // Labels rank candidates; they only gate when no model can look at pixels. With Nano
        // available, photos whose labels match go first and the rest of the newest photos follow,
        // so a description the label vocabulary cannot express ("small children") still gets judged.
        val canJudge = matcher !is KeywordMatcher
        var budgetLeft = JUDGE_BUDGET
        var morePending = false
        try {
            for (group in groups) {
                val rule = group.rule ?: continue
                val key = ruleKey(matcher.name, rule)
                val relevant = matcher.relevantLabels(rule, BASE_LABELS(applicationContext) + labels.values.flatMapTo(HashSet()) { it.keys })
                Log.i(TAG, "${group.name}: rule=\"$rule\" relevant labels=${relevant.sorted()}")
                // Only Nano-confirmed matches (or the user's exclusions) are settled; labels-only matches get re-judged once Nano is available.
                val verdicts = index.verdicts(key)
                val cached = { uri: String -> verdicts[PhotoId.identity(uri)] }
                val settled = ScanPlan.settled(group.matched, if (canJudge) cached else null)
                val dropped = group.matched - settled.toSet()
                val ordered = ScanPlan.ordered(ScanPlan.candidates(group.pool, libraryKeys), labelsFor, relevant)
                var judgedHere = 0
                val pass = ScanPlan.pass(
                    ordered = ordered,
                    // Picked photos are seen too: the picker and the library name the same file differently.
                    seen = (settled + group.excluded + group.photos).mapTo(HashSet(), PhotoId::identity),
                    identity = PhotoId::identity,
                    labelsFor = labelsFor,
                    relevant = relevant,
                    canJudge = canJudge,
                    cached = cached,
                    budget = budgetLeft,
                ) { uri ->
                    // Verdicts are cached per matcher: a labels-only pass must not stand in for Gemini Nano later.
                    val verdict = matcher.judge(Images.decode(applicationContext, Uri.parse(uri), 768), rule)
                    if (verdict != null) index.putVerdict(key, uri, verdict)
                    if (++judgedHere % 10 == 0) setProgress(workDataOf(KEY_LABELED to labeled))
                    verdict
                }
                budgetLeft -= pass.judged
                if (pass.pending > 0) morePending = true
                val note = ScanPlan.note(
                    matcherName = matcher.name,
                    rule = rule,
                    total = settled.size + pass.found.size,
                    libraryCount = libraryKeys.size,
                    poolCount = group.pool.size,
                    morePending = pass.pending > 0 || unlabeled > 0,
                    noVocabulary = !canJudge && relevant.isEmpty(),
                )
                store.update { s ->
                    s.copy(groups = s.groups.map {
                        if (it.id != group.id) it
                        else it.copy(matched = pass.found + (it.matched - dropped.toSet()), scanNote = note)
                    })
                }
                Log.i(TAG, "${group.name}: +${pass.found.size} (labels ${relevant.size}, judged ${pass.judged}, pending ${pass.pending})")
            }
        } finally {
            index.close(); nano.close()
        }
        if (morePending) continueSoon()
        return Result.success(workDataOf(KEY_LABELED to labeled, KEY_FOUND to 0))
    }

    /** Pick up where this pass stopped, after a short pause so the UI and battery get a breather. */
    private fun continueSoon() {
        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            UNIQUE_NAME,
            androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            androidx.work.OneTimeWorkRequestBuilder<SmartGroupWorker>()
                .setInitialDelay(20, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
    }



    private suspend fun note(ids: List<String>, text: (Group) -> String) {
        (applicationContext as FengineApp).settings.update { s ->
            s.copy(groups = s.groups.map { if (it.id in ids) it.copy(scanNote = text(it)) else it })
        }
    }

    companion object {
        /** One scan now, unless one is already queued. */
        fun enqueue(context: Context) {
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, androidx.work.ExistingWorkPolicy.KEEP, androidx.work.OneTimeWorkRequestBuilder<SmartGroupWorker>().build(),
            )
        }

        const val TAG = "SmartGroupWorker"
        const val UNIQUE_NAME = "smart-scan"
        const val PERIODIC_NAME = "smart-scan-periodic"
        const val KEY_LABELED = "labeled"
        const val KEY_FOUND = "found"
        const val MAX_SCAN = 100_000
        const val LABEL_BUDGET = 300
        const val JUDGE_BUDGET = 80

        fun ruleKey(matcher: String, rule: String) = "$matcher|${rule.trim().lowercase()}"

        /** The base model's label names, bundled so the first prompt can name them before any photo is labeled. */
        private var baseLabels: List<String>? = null
        @Suppress("FunctionName")
        fun BASE_LABELS(context: Context): List<String> = baseLabels ?: runCatching {
            context.assets.open("mlkit_labels.txt").bufferedReader().readLines().filter { it.isNotBlank() }
        }.getOrDefault(emptyList()).also { baseLabels = it }
    }
}
