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
import uniffi.supermessage_ffi.peerColorIndex

/**
 * The type ramp, and the bridge from this app's colour roles into Material.
 *
 * **The colour values are not here.** They live in `design/tokens.toml` and
 * arrive as [GeneratedThemeTokens], emitted by
 * `scripts/generate-tokens.py`. They used to be written out here, and
 * separately in `apple/Supermessage/Theme.swift` and `src/app.css`, each
 * re-derived by eye from `docs/superpowers/specs/2026-08-13-console-design.md`
 * — by 2026-09 none of the three agreed, down to amber being three
 * different ambers and this file's dark accent being a different *hue* from
 * its own light one. See `docs/design-language.md` for the rules and
 * `docs/superpowers/specs/2026-09-12-design-language-tokens-design.md` for
 * why it is generated.
 *
 * The identity that travels between platforms is **structural**: serif for
 * what an agent wrote, sans for what the operator wrote, mono for data and
 * sigils, and one colour reserved for one meaning. iOS ties its faces to
 * the system (`.serif` resolves to New York, `.monospaced` to SF Mono) so
 * that Dynamic Type comes free; Android's equivalent is Compose's own
 * generic [FontFamily.Serif] / [FontFamily.SansSerif] /
 * [FontFamily.Monospace] — resolved by the platform, nothing bundled, same
 * reasoning.
 *
 * There are now **sixteen** roles rather than seven, and they are the same
 * sixteen on every platform. The nine that are new here are the three text
 * ranks, `surface-raised`, `border-strong`, `accent-content`,
 * `accent-soft`, `signal-soft` and `scrim` — most of which this app has
 * been doing without, falling through to Material defaults instead.
 */

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
    val body: FontFamily = ThemeType.body
    val own: FontFamily = ThemeType.bodyOwn
    val code: FontFamily = FontFamily.Monospace
}

private val LocalSupermessageColors =
    staticCompositionLocalOf { GeneratedThemeTokens.paper }
private val LocalSupermessagePeers =
    staticCompositionLocalOf { GeneratedPeers.paper }
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

    /**
     * A sender's colour: one of seven, picked by `peer_color_index` in the
     * core so a person has the same colour on every platform.
     */
    @Composable
    fun peer(userId: String): Color {
        val peers = LocalSupermessagePeers.current
        return peers[peerColorIndex(userId).toInt() % peers.size]
    }
}

/**
 * Wraps [MaterialTheme] with this app's own semantic colours and faces.
 *
 * Task 2 is what adopts this — `MainActivity` now wraps its content in this
 * composable rather than a bare `MaterialTheme`, and `RoomRow.kt`'s former
 * `PendingAmber` and `DecisionCard.kt`'s former `DecisionAmber` are both gone,
 * folded into the one [SupermessageColorRoles.signal] token they were always
 * describing.
 */
@Composable
fun SupermessageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Android binds `paper` to light: paper is what "light" means on a
    // phone. The third appearance is not a user setting, which is why there
    // is nothing to read here beyond the system's own dark flag.
    val colors = if (darkTheme) GeneratedThemeTokens.dark else GeneratedThemeTokens.paper
    val typography = SupermessageTypography(
        body = SupermessageThemeFonts.body,
        own = SupermessageThemeFonts.own,
        code = SupermessageThemeFonts.code,
    )
    // The bridge, and it does more work than it looks like.
    //
    // Ninety colour reads across this app go through
    // `MaterialTheme.colorScheme`, and only five reach for
    // `SupermessageTheme.colors` directly. So whatever is mapped here is,
    // in practice, what the app is painted with — and until now the three
    // text roles were not mapped at all, which is why Android has been
    // rendering body text in Material's default grey rather than in this
    // palette's `content`.
    //
    // Mapping onBackground / onSurface / onSurfaceVariant is therefore the
    // cheapest way to get the three-rank hierarchy onto this platform, and
    // the reason it arrives in P1 rather than waiting for P6's proper
    // adoption pass.
    //
    // Named arguments throughout, and that is not style: `darkColorScheme`
    // takes some thirty parameters and its third positional is
    // `primaryContainer`, not `surface`. Passing this list positionally
    // compiles and mis-maps the whole palette.
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentContent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.content,
            // Mapped because they are used and were not: `ScopeChip` draws
            // its selected state from `secondaryContainer`, so without these
            // two lines one chip in the search panel took Material's own
            // defaults rather than anything in design/tokens.toml.
            //
            // `accentSoft` under `content` is not a choice made here. That
            // pairing is what the palette already asserts — `accent-soft`
            // carries `contrast = [{ against = "content", min = 4.5 }]`, and
            // the generator fails the build if it stops holding.
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.content,
            background = colors.surface,
            onBackground = colors.content,
            surface = colors.surface,
            onSurface = colors.content,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.contentMuted,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
            error = colors.danger,
            scrim = colors.scrim,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.accentContent,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.content,
            // Mapped because they are used and were not: `ScopeChip` draws
            // its selected state from `secondaryContainer`, so without these
            // two lines one chip in the search panel took Material's own
            // defaults rather than anything in design/tokens.toml.
            //
            // `accentSoft` under `content` is not a choice made here. That
            // pairing is what the palette already asserts — `accent-soft`
            // carries `contrast = [{ against = "content", min = 4.5 }]`, and
            // the generator fails the build if it stops holding.
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.content,
            background = colors.surface,
            onBackground = colors.content,
            surface = colors.surface,
            onSurface = colors.content,
            surfaceVariant = colors.surfaceSunken,
            onSurfaceVariant = colors.contentMuted,
            outline = colors.border,
            outlineVariant = colors.borderStrong,
            error = colors.danger,
            scrim = colors.scrim,
        )
    }

    CompositionLocalProvider(
        LocalSupermessageColors provides colors,
        LocalSupermessageTypography provides typography,
        LocalSupermessagePeers provides if (darkTheme) GeneratedPeers.dark else GeneratedPeers.paper,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
