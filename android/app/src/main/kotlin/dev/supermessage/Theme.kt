package dev.supermessage

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * The palette and type ramp, ported in structure (not in hex) from
 * `apple/Supermessage/Theme.swift` — see
 * `docs/superpowers/specs/2026-08-13-console-design.md` for the source
 * spec.
 *
 * The identity that travels between platforms is **structural**, not any
 * particular typeface or colour literal: serif for what an agent wrote,
 * sans for what the operator wrote, mono for data and sigils, and one
 * colour reserved for one meaning. iOS ties its faces to the system
 * (`.serif` resolves to New York, `.monospaced` to SF Mono) so that Dynamic
 * Type comes free; Android's equivalent is Compose's own generic
 * [FontFamily.Serif] / [FontFamily.SansSerif] / [FontFamily.Monospace] —
 * resolved by the platform, nothing bundled, same reasoning.
 *
 * Android has its own dynamic-colour story (Material You), so unlike the
 * faces, the seven semantic colour roles below are **not** transcriptions
 * of iOS's literals — Task 2, which adopts this theme across the app, is
 * free to layer dynamic colour on top of these roles later. What must not
 * drift is the set of roles themselves: `ground`, `sunken`, `hairline`,
 * `accent`, `signal`, `danger`, `ok`, each defined for both light and dark.
 */

/** The seven semantic colour roles, defined once per appearance. */
@Immutable
data class SupermessageColorRoles(
    /** The page itself. */
    val ground: Color,
    /** Behind a chip, a segmented control, an avatar with no picture. */
    val sunken: Color,
    /** Hairlines and dividers. */
    val hairline: Color,
    /** The chrome hue. Selection, focus, the send affordance, own bubbles. */
    val accent: Color,
    /**
     * Amber, and it means exactly one thing: the operator owes someone an
     * answer. It appears on a pending decision and nowhere else — not on
     * unread badges, not on hover, not on a warning. Reach for [danger] or
     * [accent] instead if what you are drawing is not a decision.
     */
    val signal: Color,
    val danger: Color,
    /** A room that is working. Never amber — this is good news. */
    val ok: Color,
) {
    companion object {
        /**
         * Paper: a warm, light ground that reads as a record of what was
         * done.
         */
        val light = SupermessageColorRoles(
            ground = Color(0xFFF6F4EF),
            sunken = Color(0xFFE7E4DB),
            hairline = Color(0xFFDDD9CF),
            accent = Color(0xFF3F4BB0),
            signal = Color(0xFFA8660A),
            danger = Color(0xFFC63E3E),
            ok = Color(0xFF2F7D5B),
        )

        /**
         * Slate: a cool, low-contrast dark for a console glanced at fifty
         * times a day. Accent steps back rather than brightening, so that
         * amber — the one reserved signal — carries.
         */
        val dark = SupermessageColorRoles(
            ground = Color(0xFF171B22),
            sunken = Color(0xFF232A35),
            hairline = Color(0xFF26303C),
            accent = Color(0xFF7FB4D8),
            signal = Color(0xFFE8A02E),
            danger = Color(0xFFE0A0A0),
            ok = Color(0xFF5FBB92),
        )
    }
}

/** The three faces, structural rather than decorative — see the file doc. */
@Immutable
data class SupermessageTypography(
    /** What an agent wrote. Serif, because the timeline is a reading surface. */
    val body: FontFamily,
    /** What the operator wrote. Sans — a command, not prose. */
    val own: FontFamily,
    /** Sigils, roles, timestamps, counts, code. Data. */
    val code: FontFamily,
)

/**
 * The one instance of [SupermessageTypography] this app uses. A `val`
 * rather than a role baked into each call site, matching
 * `Theme.swift`'s own top-level `static let body` / `own` / `code` — and
 * the single place `ThemeTest`'s mandated "make `own` serif too" mutation
 * has to change to prove the operator-face test actually fails.
 */
object SupermessageThemeFonts {
    val body: FontFamily = FontFamily.Serif
    val own: FontFamily = FontFamily.SansSerif
    val code: FontFamily = FontFamily.Monospace
}

private val LocalSupermessageColors = staticCompositionLocalOf { SupermessageColorRoles.light }
private val LocalSupermessageTypography = staticCompositionLocalOf {
    SupermessageTypography(
        body = SupermessageThemeFonts.body,
        own = SupermessageThemeFonts.own,
        code = SupermessageThemeFonts.code,
    )
}

/**
 * Read access to the current [SupermessageColorRoles] and
 * [SupermessageTypography], the way [MaterialTheme] itself offers
 * `MaterialTheme.colorScheme` / `MaterialTheme.typography` — an object and a
 * same-named `@Composable` function coexist because Kotlin resolves them
 * from different namespaces (value vs. function), the same pattern
 * `MaterialTheme` itself relies on.
 */
object SupermessageTheme {
    val colors: SupermessageColorRoles
        @Composable get() = LocalSupermessageColors.current

    val typography: SupermessageTypography
        @Composable get() = LocalSupermessageTypography.current
}

/**
 * Wraps [MaterialTheme] with this app's own semantic colours and faces.
 *
 * Only the roles are provided here — no composable in the app is
 * rewritten to use them yet. That adoption, including folding `RoomRow.kt`'s
 * `PendingAmber` and `DecisionCard.kt`'s `DecisionAmber` into
 * [SupermessageColorRoles.signal], is Task 2's job; this task exists only to
 * define the tokens and prove the face structure with tests.
 */
@Composable
fun SupermessageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) SupermessageColorRoles.dark else SupermessageColorRoles.light
    val typography = SupermessageTypography(
        body = SupermessageThemeFonts.body,
        own = SupermessageThemeFonts.own,
        code = SupermessageThemeFonts.code,
    )
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            background = colors.ground,
            surface = colors.ground,
            surfaceVariant = colors.sunken,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            background = colors.ground,
            surface = colors.ground,
            surfaceVariant = colors.sunken,
            outline = colors.hairline,
            outlineVariant = colors.hairline,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalSupermessageColors provides colors,
        LocalSupermessageTypography provides typography,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
