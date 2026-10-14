package dev.fengine.wallpaper.smart

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.google.mlkit.genai.common.FeatureStatus

/**
 * What the user needs to know about on-device AI on this phone, in plain words, with the one
 * action that moves it forward. Combines three signals: Google's published device list, the state
 * of the Android AICore package (a factory "stub" build cannot serve models), and the ML Kit
 * feature status for Gemini Nano.
 */
data class AiReadiness(
    val level: Level,
    val headline: String,
    val detail: String,
    val action: Action? = null,
) {
    enum class Level { READY, DOWNLOADABLE, DOWNLOADING, NEEDS_AICORE, NOT_LISTED, UNAVAILABLE }
    enum class Action { DOWNLOAD, OPEN_PLAY_STORE, CHECK_AGAIN }

    companion object {
        const val AICORE_PACKAGE = "com.google.android.aicore"

        /** Device families on Google's ML Kit GenAI Prompt API list (developers.google.com/ml-kit/genai, read 2026-10-13). */
        private val listedModels = listOf(
            "Pixel 9", "Pixel 10", "Pixel 11",       // Google
            "SM-F97", "SM-F96", "SM-F77",             // Galaxy Z Fold8 (observed SM-F971U), Fold7, Flip8 family
        )

        fun deviceListed(): Boolean = listedModels.any { Build.MODEL.startsWith(it, ignoreCase = true) }

        /** AICore version name, or null when the package is absent. */
        fun aicoreVersion(context: Context): String? = runCatching {
            context.packageManager.getPackageInfo(AICORE_PACKAGE, 0).versionName
        }.getOrNull()

        fun isStub(version: String?): Boolean = version == null || version.contains("stub", ignoreCase = true)

        suspend fun check(context: Context): AiReadiness {
            val version = aicoreVersion(context)
            val listed = deviceListed()
            val status = runCatching { NanoMatcher().use { it.status() } }.getOrElse { FeatureStatus.UNAVAILABLE }
            return when {
                status == FeatureStatus.AVAILABLE -> AiReadiness(
                    Level.READY, "On-device AI is ready",
                    "Gemini Nano judges each photo against your description, on this phone only.",
                )
                status == FeatureStatus.DOWNLOADING -> AiReadiness(
                    Level.DOWNLOADING, "On-device AI is downloading",
                    "The model is a few hundred megabytes. Finding photos uses labels until it lands.",
                    Action.CHECK_AGAIN,
                )
                status == FeatureStatus.DOWNLOADABLE -> AiReadiness(
                    Level.DOWNLOADABLE, "On-device AI can be set up",
                    "A one-time download, best on Wi-Fi. Until then, finding photos uses labels only.",
                    Action.DOWNLOAD,
                )
                isStub(version) && listed -> AiReadiness(
                    Level.NEEDS_AICORE, "Android AICore needs an update",
                    "Google lists this phone as supported, but the AICore service on it is a factory placeholder" +
                        (version?.let { " ($it)" } ?: "") + ". Update it in the Play Store, then check again.",
                    Action.OPEN_PLAY_STORE,
                )
                !listed -> AiReadiness(
                    Level.NOT_LISTED, "Labels only on this phone",
                    "Google has not listed ${Build.MANUFACTURER} ${Build.MODEL} for Gemini Nano. Finding photos uses " +
                        "on-device labels, which works for places, animals, food and scenes.",
                    Action.CHECK_AGAIN,
                )
                else -> AiReadiness(
                    Level.UNAVAILABLE, "On-device AI is not available yet",
                    "AICore ${version ?: "is missing"} and reports the model as unavailable. This can resolve after " +
                        "AICore finishes setting up. Finding photos uses labels until then.",
                    Action.CHECK_AGAIN,
                )
            }
        }

        fun playStoreIntent(): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$AICORE_PACKAGE"))
                .setPackage("com.android.vending")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
