package dev.fengine.wallpaper.smart

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Turns a description into photo matches in two stages: pick the ML Kit labels that could appear in
 * matching photos (cheap prefilter), then judge each candidate image.
 */
interface DescriptionMatcher {
    val name: String

    /**
     * Names cached verdicts; changes when the judging prompt does, so old answers are not reused.
     */
    val verdictKey: String
        get() = name

    /** Subset of [labels] that photos matching [description] would likely carry. */
    suspend fun relevantLabels(description: String, labels: Collection<String>): Set<String>

    /** True/false for one candidate, or null when this matcher cannot look at pixels. */
    suspend fun judge(bitmap: Bitmap, description: String): Boolean?
}

/**
 * Fallback without a language model: label names that share a word with the description, plus a few
 * synonyms. Candidates are accepted as matches without looking at pixels.
 */
class KeywordMatcher : DescriptionMatcher {
    override val name = "labels only"

    override suspend fun relevantLabels(
        description: String,
        labels: Collection<String>,
    ): Set<String> = select(description, labels)

    override suspend fun judge(bitmap: Bitmap, description: String): Boolean? = null

    companion object {
        private val synonyms =
            mapOf(
                "children" to listOf("child"),
                "dog" to listOf("dog", "puppy"),
                "puppy" to listOf("dog"),
                "cat" to listOf("cat", "kitten"),
                "sea" to listOf("beach", "ocean", "coast"),
                "ocean" to listOf("beach", "sea", "coast"),
                "mountains" to listOf("mountain"),
                "sunset" to listOf("sunset", "sky", "dusk"),
                "food" to listOf("food", "dish", "meal", "cuisine"),
                "city" to listOf("city", "skyline", "skyscraper", "street"),
                "night" to listOf("night", "dark"),
                "snow" to listOf("snow", "winter"),
                "flowers" to listOf("flower"),
                "car" to listOf("car", "vehicle"),
                // People words: the base vocabulary has no "family" or "friends", but it does see
                // faces, smiles and crowds.
                "family" to
                    listOf(
                        "person",
                        "smile",
                        "selfie",
                        "baby",
                        "child",
                        "crowd",
                        "laugh",
                        "love",
                        "picnic",
                        "fun",
                    ),
                "friends" to listOf("person", "smile", "selfie", "crowd", "laugh", "party", "fun"),
                "people" to listOf("person", "smile", "selfie", "crowd"),
                "person" to listOf("person", "selfie", "smile"),
                "portrait" to listOf("person", "selfie", "smile", "hair", "beard"),
                "selfie" to listOf("selfie", "person", "smile"),
                "baby" to listOf("baby", "child"),
                "wedding" to listOf("bride", "dress", "love", "party", "dance"),
                "party" to listOf("party", "fun", "dance", "crowd"),
                "vacation" to listOf("vacation", "beach", "swimming", "fun"),
                "holiday" to listOf("vacation", "beach", "swimming", "fun"),
                "trip" to listOf("vacation", "beach"),
                "pet" to listOf("pet", "dog", "cat"),
                "pets" to listOf("pet", "dog", "cat"),
                // The owner's own people: no model knows who they are, only what they look like.
                "wife" to listOf("person", "smile", "selfie", "dress", "hair"),
                "husband" to listOf("person", "smile", "selfie", "beard", "hair"),
                "partner" to listOf("person", "smile", "selfie"),
                "kid" to listOf("child", "baby", "toddler", "person", "smile"),
                "kids" to listOf("child", "baby", "toddler", "person", "smile"),
                "son" to listOf("child", "baby", "person"),
                "sons" to listOf("child", "baby", "person"),
                "daughter" to listOf("child", "baby", "person"),
                "daughters" to listOf("child", "baby", "person"),
                "mom" to listOf("person", "smile"),
                "dad" to listOf("person", "smile"),
                "grandma" to listOf("person", "smile"),
                "grandpa" to listOf("person", "smile"),
                "me" to listOf("person", "selfie"),
                "us" to listOf("person", "selfie", "smile", "crowd"),
            )

        fun select(description: String, labels: Collection<String>): Set<String> {
            val words = description.lowercase().split(Regex("[^a-z]+")).filter { it.length > 2 }
            val wanted = HashSet<String>()
            for (w in words) {
                wanted += w
                wanted += w.removeSuffix("s")
                synonyms[w]?.let { wanted += it }
            }
            return labels.filterTo(HashSet()) { label ->
                label.lowercase().split(Regex("[^a-z]+")).any {
                    it in wanted || it.removeSuffix("s") in wanted
                }
            }
        }
    }
}

/** Gemini Nano through the ML Kit Prompt API: on device, no network. */
class NanoMatcher : DescriptionMatcher, AutoCloseable {
    override val name = "Gemini Nano"

    /** v3: [judge] asks per part of the description, in what a photo shows (2026-11-20). */
    override val verdictKey = "Gemini Nano v3"

    private val model: GenerativeModel by lazy { Generation.getClient() }
    @Volatile private var initialized = false

    private fun client(): GenerativeModel {
        initialized = true
        return model
    }

    /**
     * Set by the first prompt that times out: AICore is not answering, so the rest of this run uses
     * labels instead of waiting out the deadline once per photo.
     */
    @Volatile private var silent = false

    private suspend fun <T> ask(what: String, call: suspend () -> T): Result<T> {
        if (silent) return Result.failure(Outside.TimedOut(what, 0))
        return Outside.within(Outside.PROMPT_MS, what) { call() }
            .onFailure {
                // AICore answers only while the app is on screen (error 30, Fold 8, 2026-11-20):
                // once refused, the rest of a background run would be refused too.
                if (
                    it is Outside.TimedOut ||
                        (it as? GenAiException)?.errorCode == BACKGROUND_BLOCKED
                )
                    silent = true
            }
    }

    /** Bounded: AICore can take its time or throw a transient NPE while binding (see [Outside]). */
    suspend fun status(): Int =
        withContext(Dispatchers.IO) {
            Outside.within(Outside.STATUS_MS, "AICore status") {
                    Outside.retryOnNpe { client().checkStatus() }
                }
                .getOrThrow()
                .also {
                    Log.i(
                        TAG,
                        "checkStatus=$it (AVAILABLE=${FeatureStatus.AVAILABLE}, DOWNLOADABLE=${FeatureStatus.DOWNLOADABLE}, UNAVAILABLE=${FeatureStatus.UNAVAILABLE})",
                    )
                }
        }

    fun download() = client().download()

    /** [download] in the app's terms, for [Outside.watch]. */
    fun downloadEvents(): kotlinx.coroutines.flow.Flow<Outside.Download> =
        client().download().map { st ->
            when (st) {
                is DownloadStatus.DownloadStarted -> Outside.Download.Started(st.bytesToDownload)
                is DownloadStatus.DownloadProgress ->
                    Outside.Download.Progress(st.totalBytesDownloaded)
                is DownloadStatus.DownloadCompleted -> Outside.Download.Completed
                is DownloadStatus.DownloadFailed -> Outside.Download.Failed(st.e)
            }
        }

    suspend fun isAvailable(): Boolean = runCatching {
        status() == FeatureStatus.AVAILABLE
    }
        .onFailure { Log.w(TAG, "checkStatus failed", it) }
        .getOrDefault(false)

    override suspend fun relevantLabels(
        description: String,
        labels: Collection<String>,
    ): Set<String> {
        val sorted = labels.sorted()
        val prompt =
            "Photo labels: ${sorted.joinToString(", ")}.\n" +
                "Which of these labels could appear in photos matching \"$description\"? " +
                "Reply with only a comma-separated list of labels copied from the list, up to 25."
        val text =
            ask("Gemini Nano labels") {
                    client()
                        .generateContent(
                            generateContentRequest(TextPart(prompt)) {
                                temperature = 0f
                                maxOutputTokens = 256
                            }
                        )
                        .candidates
                        .first()
                        .text
                }
                .getOrElse { e ->
                    Log.w(TAG, "label prompt failed", e)
                    return KeywordMatcher.select(description, labels)
                }
        val byLower = sorted.associateBy { it.lowercase() }
        val picked =
            text
                .split(',', '\n')
                .mapNotNull { byLower[it.trim().trim('.', '"').lowercase()] }
                .toSet()
        // The model's pick plus the word matches: for "my wife and kids" Gemini Nano offered
        // Beach and Picnic but not Person or Child, which the people synonyms supply.
        return picked + KeywordMatcher.select(description, labels)
    }

    /**
     * One plain question per part of the description ([Wanted.parts]); a YES to any part is a
     * match. Asked whole, "my wife and kids" got NO for photos Gemini Nano itself described as "a
     * young child sitting on a yellow inflatable" (Fold 8, 2026-11-20): it cannot know the owner's
     * people and read "and" as "all of them in one photo".
     */
    override suspend fun judge(bitmap: Bitmap, description: String): Boolean? {
        var answered = false
        for (part in Wanted.parts(description)) {
            val text =
                ask("Gemini Nano judge") {
                        client()
                            .generateContent(
                                generateContentRequest(
                                    ImagePart(bitmap),
                                    TextPart(Wanted.question(part)),
                                ) {
                                    temperature = 0f
                                    maxOutputTokens = 8
                                }
                            )
                            .candidates
                            .first()
                            .text
                    }
                    .getOrElse { e ->
                        Log.w(TAG, "judge failed", e)
                        null
                    } ?: continue
            answered = true
            Log.d(TAG, "judge \"$part\": ${text.trim().take(12)}")
            if (text.trim().uppercase().startsWith("YES")) return true
        }
        return if (answered) false else null
    }

    override fun close() {
        if (initialized) runCatching { model.close() }
    }

    private companion object {
        const val TAG = "NanoMatcher"
        const val BACKGROUND_BLOCKED = 30
    }
}
