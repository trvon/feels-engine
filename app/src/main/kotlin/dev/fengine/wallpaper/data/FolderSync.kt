package dev.fengine.wallpaper.data

/**
 * Folder sources, as pure rules (property-tested in `invariants/FolderSyncProperties`). A folder is
 * a tree URI the person picked with the system folder picker; its photos are document URIs of the
 * form `<tree>/document/<id>`. No photo permission is involved: one persisted grant covers the
 * folder.
 */
object FolderSync {
    /** Photos one group takes from its folders, so the settings file stays small. */
    const val MAX_PHOTOS = 2000

    /** How deep subfolders are listed. */
    const val MAX_DEPTH = 3

    fun belongsTo(photo: String, tree: String): Boolean = photo.startsWith("$tree/document/")

    /**
     * The group's folder photos after a sync. [current] keeps its order for photos still there,
     * photos that left the folder go, new ones are appended in [listed] order, removed ones
     * ([excluded]) never come back, at most [max] in all. A folder whose listing failed is not in
     * [listed]: the caller passes its current photos instead, so a failed read never empties it.
     */
    fun merge(
        current: List<String>,
        listed: List<String>,
        excluded: Collection<String>,
        max: Int = MAX_PHOTOS,
    ): List<String> {
        val out = excluded.map(PhotoId::identity).toHashSet()
        val there = listed.map(PhotoId::identity).toHashSet()
        val kept = current.filter { PhotoId.identity(it) in there && PhotoId.identity(it) !in out }
        val have = kept.map(PhotoId::identity).toHashSet()
        val fresh = listed.filter { PhotoId.identity(it) !in have && PhotoId.identity(it) !in out }
        return PhotoId.distinct(kept + fresh).take(max)
    }

    /**
     * What [listed] should be for one sync: each folder's fresh listing, or its current photos when
     * the listing failed (null).
     */
    fun listing(
        folders: List<String>,
        current: List<String>,
        lists: (String) -> List<String>?,
    ): List<String> = folders.flatMap { tree ->
        lists(tree) ?: current.filter { belongsTo(it, tree) }
    }

    /**
     * A short name for a folder: the last path segment of its tree document id
     * (`primary:Pictures/Wallpapers` -> `Wallpapers`; a volume root -> `Phone storage`).
     */
    fun label(tree: String): String {
        val id = java.net.URLDecoder.decode(tree.substringAfterLast("/tree/"), "UTF-8")
        val path = id.substringAfter(':', "")
        return path.trimEnd('/').substringAfterLast('/').ifBlank { "Phone storage" }
    }
}
