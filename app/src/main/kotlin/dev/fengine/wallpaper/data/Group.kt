package dev.fengine.wallpaper.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named set of photos that can drive one or both screens.
 *
 * There is one kind of group. Two sources feed it, and either may be empty: [photos] the user
 * picked, and [matched] photos its optional description ([rule]) found on the phone ("my dog at
 * the beach"). Both show in the same grid. [excluded] holds found photos the user removed so a
 * rescan does not bring them back.
 */
data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Persisted content URIs in the order the user added them. */
    val photos: List<String> = emptyList(),
    /** Index of the photo shown last from this group; -1 before the first rotation. */
    val cursor: Int = -1,
    /** What to look for on the phone; null or blank means the group only shows picked photos. */
    val rule: String? = null,
    /** MediaStore image URIs the rule matched, newest first. */
    val matched: List<String> = emptyList(),
    /** Matched URIs the user removed. */
    val excluded: List<String> = emptyList(),
    /**
     * Extra photos a description looks through besides the phone's library (picker URIs from
     * before groups were unified, 0.2.1 and earlier). Kept so those groups match the same photos;
     * new picks go to [photos] and show directly.
     */
    val pool: List<String> = emptyList(),
    /** Human-readable result of the last scan. */
    val scanNote: String = "",
    /** The photo shown on the home rail; falls back to the first photo. */
    val coverUri: String? = null,
    /** Photos kept in the group but left out of rotation (a busy area behind the clock, say). */
    val skipped: List<String> = emptyList(),
) {
    /** Everything the rotation may show: picked first, then found, one entry per photo (see [PhotoId]). */
    val allPhotos: List<String>
        get() {
            val out = excluded.map(PhotoId::identity).toHashSet()
            return PhotoId.distinct((photos + matched).filterNot { PhotoId.identity(it) in out })
        }
    /**
     * What the wallpaper, the live engine and the screen saver cycle through: every photo except
     * the skipped ones. If every photo is skipped the group still shows them all, so a screen
     * given to it never goes empty. The cursor indexes this list.
     */
    val rotation: List<String>
        get() {
            val all = allPhotos
            if (skipped.isEmpty()) return all
            val out = skipped.map(PhotoId::identity).toHashSet()
            return all.filterNot { PhotoId.identity(it) in out }.ifEmpty { all }
        }

    /** The group without one photo, wherever it came from (picked, found, looked through, skipped). */
    fun withoutPhoto(uri: String): Group {
        val id = PhotoId.identity(uri)
        fun List<String>.drop() = filterNot { PhotoId.identity(it) == id }
        return copy(
            photos = photos.drop(), matched = matched.drop(), pool = pool.drop(), skipped = skipped.drop(),
            coverUri = coverUri?.takeUnless { PhotoId.identity(it) == id }, cursor = -1,
        )
    }

    /**
     * The group without photos it found by reading the phone's library directly. Those URIs need
     * the library permission, which the app no longer has (2026-10-15); picked photos carry their
     * own grants and stay.
     */
    fun withoutLibraryFinds(): Group {
        if ((matched + pool + skipped).none(::isLibraryUri) && coverUri?.let(::isLibraryUri) != true) return this
        // Only the library copies go: a picked copy of the same photo has its own grant and stays.
        return copy(
            matched = matched.filterNot(::isLibraryUri), pool = pool.filterNot(::isLibraryUri),
            skipped = skipped.filterNot(::isLibraryUri),
            coverUri = coverUri?.takeUnless(::isLibraryUri), cursor = -1,
        )
    }

    fun isSkipped(uri: String): Boolean = skipped.any { PhotoId.identity(it) == PhotoId.identity(uri) }

    val cover: String?
        get() {
            val all = allPhotos
            val wanted = coverUri?.let(PhotoId::identity)
            return all.firstOrNull { PhotoId.identity(it) == wanted } ?: all.firstOrNull()
        }
    /** True when the group also finds photos from its description. */
    val hasDescription: Boolean get() = !rule.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("photos", JSONArray(photos))
        .put("cursor", cursor)
        .put("rule", rule ?: JSONObject.NULL)
        .put("matched", JSONArray(matched))
        .put("excluded", JSONArray(excluded))
        .put("pool", JSONArray(pool))
        .put("coverUri", coverUri ?: JSONObject.NULL)
        .put("scanNote", scanNote)
        .put("skipped", JSONArray(skipped))

    companion object {
        /** A MediaStore library URI, readable only with a library permission (picker URIs are not). */
        fun isLibraryUri(uri: String): Boolean = uri.startsWith("content://media/external/")

        private fun JSONObject.strings(key: String): List<String> =
            optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()

        fun fromJson(o: JSONObject): Group = Group(
            id = o.getString("id"),
            name = o.optString("name", "Untitled"),
            photos = o.strings("photos"),
            cursor = o.optInt("cursor", -1),
            rule = if (o.isNull("rule")) null else o.optString("rule"),
            matched = o.strings("matched"),
            excluded = o.strings("excluded"),
            pool = o.strings("pool"),
            coverUri = if (o.isNull("coverUri")) null else o.optString("coverUri"),
            scanNote = o.optString("scanNote", ""),
            skipped = o.strings("skipped"),
        )

        fun encode(groups: List<Group>): String = JSONArray(groups.map(Group::toJson)).toString()

        fun decode(json: String): List<Group> = runCatching {
            val a = JSONArray(json)
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
