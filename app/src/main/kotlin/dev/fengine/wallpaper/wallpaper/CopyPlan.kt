package dev.fengine.wallpaper.wallpaper

import dev.fengine.wallpaper.data.PhotoId

/**
 * What one [PhotoCopyWorker] run does to the saved copies, free of Android types so the photo
 * lifecycle can be fuzzed: keep exactly one copy per photo a group shows, delete the rest, and
 * delete everything when keeping copies is off. A copy is never deleted while its photo is in a
 * group and copies are on.
 */
object CopyPlan {
    data class Plan(
        /** Photos to save a copy of, in group order, once each. */
        val save: List<String>,
        /** Copy file names to delete. */
        val delete: List<String>,
    )

    fun plan(
        photos: List<String>,
        existing: Set<String>,
        keep: Boolean,
        name: (String) -> String = PhotoCopies::name,
    ): Plan {
        if (!keep) return Plan(emptyList(), existing.sorted())
        val wanted = PhotoId.distinct(photos)
        val names = wanted.map(name)
        val keepNames = names.toHashSet()
        return Plan(
            save = wanted.zip(names).filter { (_, n) -> n !in existing }.map { it.first },
            delete = existing.filter { it !in keepNames }.sorted(),
        )
    }
}
