package dev.fengine.wallpaper

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fengine.wallpaper.smart.PhotoIndex
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The SQLite index on a real Android: identity sharing, verdicts, the unlabeled query and the v0.2 import. */
@RunWith(AndroidJUnit4::class)
class PhotoIndexTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val picker = "content://media/picker/0/com.android.providers.media.photopicker/media/41"
    private val library = "content://media/external/images/media/41"
    private val cloud = "content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/abc-1_all_7"

    @Before fun clean() { context.deleteDatabase(PhotoIndex.NAME); File(context.filesDir, "label_index.json").delete(); File(context.filesDir, "label_index.json.migrated").delete() }
    @After fun drop() { context.deleteDatabase(PhotoIndex.NAME) }

    @Test fun pickerAndLibraryUrisShareOneRow() {
        PhotoIndex(context).use { index ->
            index.putLabels(picker, mapOf("Dog" to 0.9f))
            assertEquals(mapOf("Dog" to 0.9f), index.labelsFor(library))
            assertEquals(1, index.count())
            assertEquals(listOf(cloud), index.unlabeled(listOf(picker, library, cloud)))
            index.putLabels(cloud, emptyMap())
            assertEquals(2, index.count())
            assertEquals(setOf("Dog"), index.knownLabels())
        }
    }

    @Test fun verdictsAreKeyedByRuleAndIdentity() {
        PhotoIndex(context).use { index ->
            index.putVerdict("Gemini Nano|dogs", library, true)
            assertEquals(true, index.verdict("Gemini Nano|dogs", picker))
            assertNull(index.verdict("labels only|dogs", picker))
            index.putVerdict("Gemini Nano|dogs", picker, false)
            assertEquals(mapOf("media:41" to false), index.verdicts("Gemini Nano|dogs"))
        }
    }

    @Test fun legacyJsonIsImportedOnceThenRetired() {
        val legacy = File(context.filesDir, "label_index.json")
        legacy.writeText(JSONObject().put("labels", JSONObject().put(library, JSONObject().put("Beach", 0.7)))
            .put("verdicts", JSONObject().put("Gemini Nano|sea", JSONObject().put(library, true))).toString())
        PhotoIndex(context).use { index ->
            assertEquals(mapOf("Beach" to 0.7f), index.labelsFor(picker))
            assertEquals(true, index.verdict("Gemini Nano|sea", picker))
        }
        assertTrue(!legacy.exists())
        assertTrue(File(context.filesDir, "label_index.json.migrated").exists())
        PhotoIndex(context).use { index -> assertEquals(1, index.count()) }
    }

    @Test fun transactionBatchesWrites() {
        PhotoIndex(context).use { index ->
            index.transaction { repeat(200) { index.putLabels("content://media/external/images/media/$it", mapOf("Sky" to 0.5f)) } }
            assertEquals(200, index.count())
            assertTrue(index.unlabeled((0 until 200).map { "content://media/external/images/media/$it" }).isEmpty())
        }
    }
}
