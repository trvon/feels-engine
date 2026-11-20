package dev.fengine.wallpaper.data

/**
 * Keeps the photos people chose from losing their picker grants. Android keeps at most 512
 * persisted grants (128 before Android 11) and silently drops the oldest by creation time; taking a
 * grant again resets that time (UriPermission.persistedCreateTime). Photos handed over only for a
 * description to look through use up grants, so near the ceiling the oldest of those go first, and
 * once a day the grants of every photo a group shows are taken again so they are never the oldest.
 * Pure: property-tested (docs/product/2026-11-20-product-review.md, item 3).
 */
object GrantPlan {
    /** Releasing starts above this share of the ceiling, and stops at it. */
    const val HIGH_WATER = 0.8

    const val RETAKE_EVERY_MS = 24 * 3_600_000L

    /**
     * URIs whose grants may go: in some group's look-through pool and nothing more (not picked, not
     * found, not skipped, set aside, a cover, or an orphan that Home offers back).
     */
    fun releasable(granted: Collection<String>, groups: List<Group>): List<String> {
        val kept =
            groups
                .flatMap { g ->
                    g.photos + g.matched + g.skipped + g.unreadable + listOfNotNull(g.coverUri)
                }
                .map(PhotoId::identity)
                .toHashSet()
        val pool = groups.flatMap { it.pool }.map(PhotoId::identity).toHashSet()
        return granted.filter { PhotoId.identity(it) in pool && PhotoId.identity(it) !in kept }
    }

    /**
     * Grants to release, oldest first, so at most [HIGH_WATER] of [ceiling] stay; [granted] is
     * (uri, persisted time). Empty below the high-water mark.
     */
    fun release(
        granted: List<Pair<String, Long>>,
        groups: List<Group>,
        ceiling: Int,
    ): List<String> {
        val target = (ceiling * HIGH_WATER).toInt()
        val excess = granted.size - target
        if (excess <= 0) return emptyList()
        val free = releasable(granted.map { it.first }, groups).toHashSet()
        return granted
            .filter { it.first in free }
            .sortedBy { it.second }
            .take(excess)
            .map { it.first }
    }

    /** The photos whose grants are taken again: every photo a group shows. */
    fun retake(groups: List<Group>): List<String> =
        PhotoId.distinct(groups.flatMap { it.allPhotos })

    fun retakeDue(lastMs: Long, now: Long): Boolean =
        now - lastMs >= RETAKE_EVERY_MS || now < lastMs
}
