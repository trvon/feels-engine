package dev.fengine.wallpaper.smart

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest

/**
 * Turns a description into photo matches in two stages: pick the ML Kit labels that could
 * appear in matching photos (cheap prefilter), then judge each candidate image.
 */
interface DescriptionMatcher {
    val name: String

    /** Subset of [labels] that photos matching [description] would likely carry. */
    suspend fun relevantLabels(description: String, labels: Collection<String>): Set<String>

    /** True/false for one candidate, or null when this matcher cannot look at pixels. */
    suspend fun judge(bitmap: Bitmap, description: String): Boolean?
}

/**
 * Fallback without a language model: label names that share a word with the description, plus a
 * few synonyms. Candidates are accepted as matches without looking at pixels.
 */
class KeywordMatcher : DescriptionMatcher {
    override val name = "labels only"

    override suspend fun relevantLabels(description: String, labels: Collection<String>): Set<String> =
        select(description, labels)

    override suspend fun judge(bitmap: Bitmap, description: String): Boolean? = null

    companion object {
        private val synonyms = mapOf(
            "kid" to listOf("child", "baby", "toddler"), "kids" to listOf("child", "baby", "toddler"),
            "children" to listOf("child"), "dog" to listOf("dog", "puppy"), "puppy" to listOf("dog"),
            "cat" to listOf("cat", "kitten"), "sea" to listOf("beach", "ocean", "coast"),
            "ocean" to listOf("beach", "sea", "coast"), "mountains" to listOf("mountain"),
            "sunset" to listOf("sunset", "sky", "dusk"), "food" to listOf("food", "dish", "meal", "cuisine"),
            "city" to listOf("city", "skyline", "skyscraper", "street"), "night" to listOf("night", "dark"),
            "snow" to listOf("snow", "winter"), "flowers" to listOf("flower"), "car" to listOf("car", "vehicle"),
            // People words: the base vocabulary has no "family" or "friends", but it does see faces, smiles and crowds.
            "family" to listOf("person", "smile", "selfie", "baby", "child", "crowd", "laugh", "love", "picnic", "fun"),
            "friends" to listOf("person", "smile", "selfie", "crowd", "laugh", "party", "fun"),
            "people" to listOf("person", "smile", "selfie", "crowd"), "person" to listOf("person", "selfie", "smile"),
            "portrait" to listOf("person", "selfie", "smile", "hair", "beard"), "selfie" to listOf("selfie", "person", "smile"),
            "baby" to listOf("baby", "child"), "wedding" to listOf("bride", "dress", "love", "party", "dance"),
            "party" to listOf("party", "fun", "dance", "crowd"), "vacation" to listOf("vacation", "beach", "swimming", "fun"),
            "holiday" to listOf("vacation", "beach", "swimming", "fun"), "trip" to listOf("vacation", "beach"),
            "pet" to listOf("pet", "dog", "cat"), "pets" to listOf("pet", "dog", "cat"),
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
                label.lowercase().split(Regex("[^a-z]+")).any { it in wanted || it.removeSuffix("s") in wanted }
            }
        }
    }
}

/** Gemini Nano through the ML Kit Prompt API: on device, no network. */
class NanoMatcher : DescriptionMatcher, AutoCloseable {
    override val name = "Gemini Nano"

    private val model: GenerativeModel = Generation.getClient()

    suspend fun status(): Int = model.checkStatus().also {
        Log.i(TAG, "checkStatus=$it (AVAILABLE=${FeatureStatus.AVAILABLE}, DOWNLOADABLE=${FeatureStatus.DOWNLOADABLE}, UNAVAILABLE=${FeatureStatus.UNAVAILABLE})")
    }
    fun download() = model.download()
    suspend fun isAvailable(): Boolean = runCatching { status() == FeatureStatus.AVAILABLE }
        .onFailure { Log.w(TAG, "checkStatus failed", it) }.getOrDefault(false)

    override suspend fun relevantLabels(description: String, labels: Collection<String>): Set<String> {
        val sorted = labels.sorted()
        val prompt = "Photo labels: ${sorted.joinToString(", ")}.\n" +
            "Which of these labels could appear in photos matching \"$description\"? " +
            "Reply with only a comma-separated list of labels copied from the list, up to 25."
        val text = runCatching {
            model.generateContent(generateContentRequest(TextPart(prompt)) { temperature = 0f; maxOutputTokens = 256 }).candidates.first().text
        }.getOrElse { e -> Log.w(TAG, "label prompt failed", e); return KeywordMatcher.select(description, labels) }
        val byLower = sorted.associateBy { it.lowercase() }
        val picked = text.split(',', '\n').mapNotNull { byLower[it.trim().trim('.', '"').lowercase()] }.toSet()
        return if (picked.isEmpty()) KeywordMatcher.select(description, labels) else picked
    }

    override suspend fun judge(bitmap: Bitmap, description: String): Boolean? {
        val prompt = "Does this photo match the description \"$description\"? Answer with YES or NO only."
        val text = runCatching {
            model.generateContent(generateContentRequest(ImagePart(bitmap), TextPart(prompt)) { temperature = 0f; maxOutputTokens = 8 }).candidates.first().text
        }.getOrElse { e -> Log.w(TAG, "judge failed", e); return null }
        return text.trim().uppercase().startsWith("YES")
    }

    override fun close() = model.close()

    private companion object { const val TAG = "NanoMatcher" }
}
