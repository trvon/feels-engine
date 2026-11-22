package dev.fengine.wallpaper.wallpaper

import dev.fengine.wallpaper.data.Group
import dev.fengine.wallpaper.data.PhotoId
import java.security.MessageDigest

/**
 * Photos shared to the app from Google Photos (Share › Feels Engine), free of Android types.
 *
 * Google Photos' share grants cannot be persisted and end when the receiving screen closes
 * (SecurityException 5 s after finish, Fold 8, 2026-11-20), so the app keeps a screen-sized copy of
 * each one in its own storage and the group names that copy (`content://<app>.photos/shared/…`).
 * The copy is the photo: it never goes unreadable, keeping copies off does not delete it, and it
 * goes once no group uses it.
 */
object SharePlan {
    const val DIR = "shared"

    /** Files younger than this stay even when unused: a share screen may still be adding them. */
    const val GRACE_MS = 3_600_000L

    /** A not-yet-readable item is tried again this often while the share screen is open. */
    const val RETRY_MS = 2_000L

    /** Saving stops when nothing new could be read for this long. */
    const val GIVE_UP_MS = 45_000L

    /** New copies are WebP; .jpg ones came from the first test build. */
    private val shared = Regex("^content://[^/]+\\.photos/$DIR/([0-9a-f]{32}\\.(?:webp|jpg))$")

    fun isShared(uri: String): Boolean = shared.matches(uri)

    /** The file a shared photo URI names, or null for any other URI. */
    fun fileName(uri: String): String? = shared.matchEntire(uri)?.groupValues?.get(1)

    /** One file per shared item: sharing the same photo again finds the same copy. */
    fun nameFor(source: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) } + ".webp"
    }

    fun uriFor(authority: String, name: String) = "content://$authority/$DIR/$name"

    /**
     * Saved shared files no group uses any more and older than [GRACE_MS]; [files] maps a file name
     * to its last-modified time.
     */
    fun unused(files: Map<String, Long>, groups: List<Group>, now: Long): List<String> {
        val used =
            groups
                .flatMap { g ->
                    g.photos +
                        g.matched +
                        g.pool +
                        g.skipped +
                        g.unreadable +
                        listOfNotNull(g.coverUri)
                }
                .mapNotNull(::fileName)
                .toHashSet()
        return files
            .filter { (name, at) -> name !in used && (now - at >= GRACE_MS || at > now) }
            .keys
            .sorted()
    }

    /**
     * The group with [saved] shared photos: shown as they are, or handed to its description to look
     * through ([lookThrough], only when it has one).
     */
    fun apply(group: Group, saved: List<String>, lookThrough: Boolean): Group =
        if (lookThrough && group.hasDescription)
            group.copy(pool = PhotoId.distinct(group.pool + saved))
        else group.withPhotos(saved)
}
