package dev.fengine.wallpaper.data

/**
 * The places a group can show, and which group each one uses. One group per place; the same
 * group may serve several places. Pure, so the assignment rules are property-tested
 * (`invariants/ScreensProperties`) instead of living in the view model.
 */
enum class Screen { HOME, LOCK, COVER, SCREEN_SAVER }

object Screens {
    /** The group assigned to [screen] itself, without fallbacks; null means unassigned. */
    fun assigned(s: Settings, screen: Screen): String? = when (screen) {
        Screen.HOME -> s.homeGroupId
        Screen.LOCK -> s.lockGroupId
        Screen.COVER -> s.coverGroupId
        Screen.SCREEN_SAVER -> s.dreamGroupId
    }

    /**
     * The group [screen] actually shows. Home and lock show nothing when unassigned (the app
     * leaves that wallpaper alone). The cover screen follows the inner home screen and the
     * screen saver follows home, then any group with photos, so it never plays an empty screen.
     */
    fun effective(s: Settings, screen: Screen): Group? = when (screen) {
        Screen.HOME -> s.homeGroup
        Screen.LOCK -> s.lockGroup
        Screen.COVER -> s.coverGroup ?: s.homeGroup
        Screen.SCREEN_SAVER -> s.dreamGroup ?: s.homeGroup ?: s.groups.firstOrNull { it.allPhotos.isNotEmpty() }
    }

    /** Give [screen] to [groupId] (null to unassign). No other screen changes. */
    fun assign(s: Settings, screen: Screen, groupId: String?): Settings {
        val id = groupId?.takeIf { g -> s.groups.any { it.id == g } }
        return when (screen) {
            Screen.HOME -> s.copy(homeGroupId = id)
            Screen.LOCK -> s.copy(lockGroupId = id)
            Screen.COVER -> s.copy(coverGroupId = id)
            Screen.SCREEN_SAVER -> s.copy(dreamGroupId = id)
        }
    }

    /** Remove a group and every screen that pointed at it. */
    fun withoutGroup(s: Settings, groupId: String): Settings = Screen.entries
        .fold(s.copy(groups = s.groups.filterNot { it.id == groupId })) { acc, screen ->
            if (assigned(acc, screen) == groupId) assign(acc, screen, null) else acc
        }

    /** The screens [groupId] is assigned to, in display order. */
    fun of(s: Settings, groupId: String): List<Screen> = Screen.entries.filter { assigned(s, it) == groupId }
}
