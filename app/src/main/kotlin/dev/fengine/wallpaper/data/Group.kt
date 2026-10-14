package dev.fengine.wallpaper.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A named set of photos that can drive one or both screens.
 *
 * Two sources feed a group: [photos] the user picked, and [matched] photos a described [rule]
 * found in the library ("my dog at the beach"). [excluded] holds photos the user removed from the
 * matched set so a rescan does not bring them back.
 */
data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Persisted content URIs in the order the user added them. */
    val photos: List<String> = emptyList(),
    /** Index of the photo shown last from this group; -1 before the first rotation. */
    val cursor: Int = -1,
    /** Natural-language description, or null for a picked-only group. */
    val rule: String? = null,
    /** MediaStore image URIs the rule matched, newest first. */
    val matched: List<String> = emptyList(),
    /** Matched URIs the user removed. */
    val excluded: List<String> = emptyList(),
    /**
     * Photos the user handed the rule to look through (picker URIs, persisted grants). The phone's own
     * library is often nearly empty when Google Photos keeps everything in the cloud; this is how
     * cloud photos reach a described group.
     */
    val pool: List<String> = emptyList(),
    /** Human-readable result of the last scan. */
    val scanNote: String = "",
    /** The photo shown on the home rail; falls back to the first photo. */
    val coverUri: String? = null,
) {
    /** Everything the rotation may show: picked first, then found, one entry per photo (see [PhotoId]). */
    val allPhotos: List<String>
        get() {
            val out = excluded.map(PhotoId::identity).toHashSet()
            return PhotoId.distinct((photos + matched).filterNot { PhotoId.identity(it) in out })
        }
    val cover: String?
        get() {
            val all = allPhotos
            val wanted = coverUri?.let(PhotoId::identity)
            return all.firstOrNull { PhotoId.identity(it) == wanted } ?: all.firstOrNull()
        }
    val isDescribed: Boolean get() = rule != null

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

    companion object {
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
        )

        fun encode(groups: List<Group>): String = JSONArray(groups.map(Group::toJson)).toString()

        fun decode(json: String): List<Group> = runCatching {
            val a = JSONArray(json)
            List(a.length()) { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}
