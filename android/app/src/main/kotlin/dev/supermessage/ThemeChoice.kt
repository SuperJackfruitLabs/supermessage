package dev.supermessage

import androidx.compose.ui.graphics.Color

/**
 * The reader's appearance choices, ported from `apple/Supermessage/Appearance.swift`.
 *
 * Every value names a palette in `design/tokens.toml` whose contrast
 * contracts the generator has already checked, so no choice can produce an
 * unreadable screen. [raw] is what is stored (`appearance.mode`).
 */
enum class AppearanceMode(val label: String, val raw: String) {
    SYSTEM("System", "system"),
    LIGHT("Light", "light"),
    DARK("Dark", "dark"),
}

/**
 * Which palette "dark" means. Tinted is the house dark; Black is true #000
 * for OLED, and stays a choice somebody makes rather than what they are given.
 */
enum class DarkStyle(val label: String, val raw: String) {
    TINTED("Tinted", "tinted"),
    BLACK("Black", "black"),
}

/** The account's choice of mode, dark style and accent ([accent] `null` is the house violet). */
data class ThemeChoice(
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    val darkStyle: DarkStyle = DarkStyle.TINTED,
    val accent: String? = null,
)

/**
 * One accent's palettes, keyed by appearance (`light`, `dark`, `paper`,
 * `black`), or `null` for the house violet and for a name this build does not
 * know (a preference written by a newer version reads as the house palette).
 */
internal fun accentSet(name: String): Map<String, SupermessageColorRoles>? = when (name) {
    "blue" -> GeneratedAccents.blue
    "graphite" -> GeneratedAccents.graphite
    "green" -> GeneratedAccents.green
    "pink" -> GeneratedAccents.pink
    "teal" -> GeneratedAccents.teal
    else -> null
}

private fun ThemeChoice.set(): Map<String, SupermessageColorRoles>? = accent?.let(::accentSet)

/**
 * The whole palette for this choice (`ThemeChoice.palette`, `Appearance.swift:61-66`).
 *
 * Black keeps its black grounds whatever the accent: each accent's `black`
 * palette is itself black-grounded, and the choice never mixes an accent's
 * tinted surfaces into it.
 */
fun ThemeChoice.palette(dark: Boolean): SupermessageColorRoles {
    val set = set()
    if (!dark) return set?.get("paper") ?: GeneratedThemeTokens.paper
    if (darkStyle == DarkStyle.BLACK) return set?.get("black") ?: GeneratedThemeTokens.black
    return set?.get("dark") ?: GeneratedThemeTokens.dark
}

/** The per-person colours for this choice; Black has its own set. */
fun ThemeChoice.peers(dark: Boolean): List<Color> = when {
    !dark -> GeneratedPeers.paper
    darkStyle == DarkStyle.BLACK -> GeneratedPeers.black
    else -> GeneratedPeers.dark
}

/** Whether this choice paints a dark appearance, given whether the phone is dark. */
fun ThemeChoice.resolvesDark(systemDark: Boolean): Boolean = when (mode) {
    AppearanceMode.SYSTEM -> systemDark
    AppearanceMode.LIGHT -> false
    AppearanceMode.DARK -> true
}
