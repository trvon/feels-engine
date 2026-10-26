package dev.fengine.wallpaper.data

/**
 * Photos the app still holds a picker grant for but no group refers to. Before 0.5 a photo that
 * failed to read once was deleted from its group while its grant stayed; those grants are how the
 * photos come back.
 */
object PhotoRecovery {
    /** [granted] URIs, in order, that no group in [groups] refers to under any URI. */
    fun orphans(granted: List<String>, groups: List<Group>): List<String> {
        val used = groups.flatMap { it.allUris }.map(PhotoId::identity).toHashSet()
        return PhotoId.distinct(granted.filterNot { PhotoId.identity(it) in used })
    }

    /**
     * Where put-back photos go: the only group with picked photos, or the only group; otherwise
     * null, and the caller makes a new group.
     */
    fun target(groups: List<Group>): String? =
        groups.singleOrNull { it.photos.isNotEmpty() }?.id ?: groups.singleOrNull()?.id
}
