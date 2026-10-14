package dev.fengine.wallpaper.data

/**
 * One photo can reach the app under two URIs: the photo picker's
 * (`content://media/picker/0/<provider>/media/35`) and the library's
 * (`content://media/external/images/media/35`). Both end in the MediaStore id, so the app treats
 * them as the same photo. Anything else, including cloud picker items, is identified by its URI.
 */
object PhotoId {
    private val media = Regex("^content://media/.*/(\\d+)$")

    fun identity(uri: String): String = media.find(uri)?.let { "media:" + it.groupValues[1] } ?: uri

    /** [uris] in order with later duplicates (by [identity]) dropped. */
    fun distinct(uris: List<String>): List<String> = uris.distinctBy(::identity)
}
