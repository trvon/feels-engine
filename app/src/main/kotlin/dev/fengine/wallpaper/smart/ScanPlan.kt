package dev.fengine.wallpaper.smart

/**
 * The decisions a scan pass makes, free of Android types so the invariant suite can drive them
 * with fake libraries, labels and judges. [SmartGroupWorker] supplies the I/O: listing the
 * library, labeling, decoding and the matcher.
 *
 * Photos are plain URI strings throughout; "labels" are ML Kit label names per photo.
 */
object ScanPlan {

    /** Pool photos first (the user handed them over on purpose), then the library, no duplicates. */
    fun candidates(pool: List<String>, library: List<String>): List<String> = (pool + library).distinct()

    /**
     * Labeled candidates ordered for judging: most relevant labels first, ties in the given order
     * (newest first for the library). Unlabeled photos wait for the next pass.
     */
    fun ordered(
        candidates: List<String>,
        labelsFor: (String) -> Map<String, Float>?,
        relevant: Set<String>,
    ): List<String> = candidates.filter { labelsFor(it) != null }
        .sortedByDescending { key -> labelsFor(key)!!.keys.count { it in relevant } }

    /**
     * Matches that survive a rescan. With a model that looks at pixels, only its confirmed verdicts
     * are settled, so labels-only matches from a weaker pass get re-judged. Without one, everything
     * already matched stays.
     */
    fun settled(matched: List<String>, confirmed: ((String) -> Boolean?)?): List<String> =
        if (confirmed == null) matched else matched.filter { confirmed(it) == true }

    data class Pass(
        /** New matches, in judging order. */
        val found: List<String>,
        /** Candidates skipped because the judge budget ran out. */
        val pending: Int,
        /** Judge calls made in this pass. */
        val judged: Int,
    )

    /**
     * One group's pass over [ordered]. [seen] photos (settled matches, exclusions and picked photos,
     * all under [identity]) are skipped.
     * When [canJudge] is false, a candidate must carry a relevant label to count and is accepted
     * without a verdict. Otherwise cached verdicts win, then [judge] runs until [budget] calls
     * have been spent; the rest are reported as pending. A null verdict (the model could not look
     * at this photo: AICore's policy check refuses some portraits) falls back to the labels for that
     * one photo, and is not cached, so a later pass may ask the model again.
     */
    suspend fun pass(
        ordered: List<String>,
        seen: Set<String>,
        labelsFor: (String) -> Map<String, Float>?,
        relevant: Set<String>,
        canJudge: Boolean,
        cached: (String) -> Boolean?,
        budget: Int,
        identity: (String) -> String = { it },
        judge: suspend (String) -> Boolean?,
    ): Pass {
        val found = ArrayList<String>()
        var pending = 0
        var judged = 0
        for (key in ordered) {
            if (identity(key) in seen) continue
            val labels = labelsFor(key) ?: continue
            if (!canJudge && labels.keys.none { it in relevant }) continue
            val yes = when {
                !canJudge -> true
                cached(key) != null -> cached(key)!!
                judged >= budget -> { pending++; continue }
                else -> { judged++; judge(key) ?: labels.keys.any { it in relevant } }
            }
            if (yes) found += key
        }
        return Pass(found, pending, judged)
    }

    /**
     * The sentence the group screen shows under "Find photos". [noVocabulary] is the labels-only
     * case where no label name relates to the rule at all: say so, because "Found 0" alone reads
     * like the photos are missing when it is the words that are.
     */
    fun note(
        matcherName: String,
        rule: String,
        total: Int,
        libraryCount: Int,
        poolCount: Int,
        morePending: Boolean,
        noVocabulary: Boolean = false,
    ): String {
        val looked = "Looked through $libraryCount on this phone" +
            (if (poolCount > 0) " and $poolCount you picked." else ".")
        if (noVocabulary && total == 0) {
            return "No photo label on this phone relates to \"$rule\", so labels only found nothing. $looked " +
                "Try words the labels know, like beach, dog, food, city or smile, or add photos by hand."
        }
        val tail = when {
            morePending -> " More to check on the next pass."
            libraryCount < 20 && poolCount == 0 ->
                " This phone keeps few photos itself; Google Photos holds the rest in the cloud. Add from Google Photos below to look through those too."
            else -> ""
        }
        return "Found ${if (total == 1) "1 photo" else "$total photos"} with $matcherName. $looked$tail"
    }
}
