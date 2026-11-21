package dev.fengine.wallpaper.smart

/**
 * A description in the terms a photo can show. The owner writes about their own life ("my wife and
 * kids", "our dog at the beach"); no model can tell who someone is, and a small one reads "and" as
 * "all at once". So the description is split into parts, each asked on its own, and the owner's
 * people become what they look like. Pure: tested.
 */
object Wanted {
    /** Asked per photo at most; each part is one prompt. */
    const val MAX_PARTS = 4

    private val people =
        mapOf(
            "wife" to "a woman",
            "girlfriend" to "a woman",
            "mom" to "a woman",
            "mum" to "a woman",
            "mother" to "a woman",
            "grandma" to "an older woman",
            "grandmother" to "an older woman",
            "sister" to "a woman or a girl",
            "husband" to "a man",
            "boyfriend" to "a man",
            "dad" to "a man",
            "father" to "a man",
            "grandpa" to "an older man",
            "grandfather" to "an older man",
            "brother" to "a man or a boy",
            "partner" to "a person",
            "me" to "a person",
            "myself" to "a person",
            "kid" to "a child",
            "kids" to "children",
            "child" to "a child",
            "children" to "children",
            "son" to "a boy",
            "sons" to "boys",
            "daughter" to "a girl",
            "daughters" to "girls",
            "baby" to "a baby",
            "babies" to "babies",
            "toddler" to "a toddler",
            "family" to "a family together",
            "us" to "people together",
            "parents" to "a man and a woman",
            "grandparents" to "older people",
        )

    private val owner = setOf("my", "our", "the", "a", "an", "with")

    /** "my wife and kids" -> ["a woman", "children"]; at most [MAX_PARTS], never empty. */
    fun parts(description: String): List<String> {
        val pieces =
            description
                .lowercase()
                .split(Regex(",|;|&|/|\\+|\\band\\b|\\bor\\b"))
                .map { piece ->
                    piece
                        .trim()
                        .split(Regex("\\s+"))
                        .filter { it.isNotBlank() }
                        .dropWhile { it in owner }
                        .joinToString(" ") { people[it.trim('.', '!', '?', '\'', '"')] ?: it }
                        .trim('.', '!', '?', ' ')
                }
                .filter { it.isNotBlank() }
                .distinct()
        return pieces.ifEmpty { listOf(description.trim()) }.take(MAX_PARTS)
    }

    fun question(part: String): String = "Does this photo show $part? Answer with YES or NO only."
}
