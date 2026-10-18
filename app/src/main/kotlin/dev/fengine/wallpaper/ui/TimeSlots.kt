package dev.fengine.wallpaper.ui

/** A time edit is one transaction: reject the whole draft if any slot is incomplete or invalid. */
object TimeSlots {
    private val clock = Regex("""(\p{Nd}{1,2}):(\p{Nd}{2})""")

    fun parse(text: String): List<Int>? {
        val slots =
            text.split(',').map { part ->
                val match = clock.matchEntire(part.trim()) ?: return null
                val hour = match.groupValues[1].toIntOrNull() ?: return null
                val minute = match.groupValues[2].toIntOrNull() ?: return null
                if (hour !in 0..23 || minute !in 0..59) return null
                hour * 60 + minute
            }
        return slots.distinct().sorted()
    }
}
