package dev.fengine.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's promises, checked against the manifest every build: no network, no broad photo
 * permission (Play photo policy; docs/privacy.md). Libraries can add permissions in the merged
 * manifest, so INTERNET must be removed explicitly; the merged APK is also checked in CI
 * (ci.yml, "Merged manifest promises").
 */
class ManifestContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").takeIf { it.exists() }
        ?: File("app/src/main/AndroidManifest.xml")
    private val text = manifest.readText()

    @Test fun internetIsRemovedFromTheMergedManifest() {
        assertTrue(Regex("""android.permission.INTERNET"\s+tools:node="remove"""").containsMatchIn(text))
    }

    @Test fun noBroadPhotoPermission() {
        for (p in listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_VISUAL_USER_SELECTED", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE"))
            assertFalse("$p is requested", Regex("""<uses-permission[^>]*$p"(?![^>]*tools:node="remove")""").containsMatchIn(text))
    }
}
